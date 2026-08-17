package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.AuditEventListener;
import io.aster.policy.event.EventType;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.aster.test.BlockingDbTestHelper;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计哈希链并发安全测试（issue #115）。
 *
 * <p>{@code AuditEventListener.onAuditEvent} 是 {@code @ObservesAsync}——并发多线程投递。
 * 同租户多条事件并发进入时，若各自独立「读最新哈希→算当前哈希→持久化」，会都读到同一
 * prev_hash → 链分叉（同一 prev_hash 挂两条后继），削弱防篡改语义。修复用 per-tenant
 * advisory lock 串行化追加。本测试并发 fireAsync 大量同租户事件，断言最终链无分叉。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
public class AuditHashChainConcurrencyTest {

    private static final int CONCURRENT_EVENTS = 30;

    @Inject
    BlockingDbTestHelper db;

    @Inject
    Event<AuditEvent> auditEventProducer;

    @BeforeEach
    void cleanup() {
        db.executeAsAuditMaintenance("DELETE FROM audit_logs");
    }

    @Test
    void tenantLockIdIsStableAndDeterministic() {
        // 同一 tenantId 每次映射到同一 lockId；不同 tenantId 极大概率不同（SHA-256 前 8 字节）。
        long a1 = AuditEventListener.tenantLockId("tenant-alpha");
        long a2 = AuditEventListener.tenantLockId("tenant-alpha");
        long b = AuditEventListener.tenantLockId("tenant-beta");
        assertEquals(a1, a2, "同一 tenantId 的 lockId 必须稳定");
        assertTrue(a1 != b, "不同 tenantId 的 lockId 应不同（碰撞概率极低）");
    }

    @Test
    void concurrentSameTenantAppendsProduceNoFork() throws Exception {
        String tenantId = "tenant-fork-check";

        // 并发投递 N 条同租户事件——@ObservesAsync 在多线程池上并发执行 onAuditEvent。
        for (int i = 0; i < CONCURRENT_EVENTS; i++) {
            auditEventProducer.fireAsync(createEvent(tenantId, "module" + i, "fn" + i));
        }
        waitForAuditRecord(tenantId, CONCURRENT_EVENTS);

        // 取该租户全部已算哈希的记录，按链顺序（timestamp, id）排列。
        List<AuditLog> logs = AuditLog.find(
                "tenantId = ?1 and currentHash is not null order by timestamp asc, id asc", tenantId)
                .list();
        assertEquals(CONCURRENT_EVENTS, logs.size(),
                "所有事件都应被持久化且算出哈希");

        // 断言 1：无分叉——每个非 null 的 prev_hash 至多被一条记录引用（否则同一前驱挂两条后继）。
        Set<String> seenPrev = new HashSet<>();
        for (AuditLog log : logs) {
            if (log.prevHash != null) {
                assertTrue(seenPrev.add(log.prevHash),
                        "检测到链分叉：prev_hash 被多条记录引用 = " + log.prevHash);
            }
        }

        // 断言 2：恰有一个 genesis（prev_hash == null）——串行化后同租户唯一首块。
        long genesisCount = logs.stream().filter(l -> l.prevHash == null).count();
        assertEquals(1, genesisCount, "同租户应恰有一个 genesis 块（prev_hash=null）");

        // 断言 3：所有 current_hash 唯一（无重复节点）。
        Set<String> hashes = new HashSet<>();
        for (AuditLog log : logs) {
            assertNotNull(log.currentHash);
            assertTrue(hashes.add(log.currentHash),
                    "检测到重复 current_hash（异常）= " + log.currentHash);
        }

        // 断言 4：从 genesis 出发能走通一条覆盖全部 N 条记录的单链
        // （每个 current_hash 恰好是下一条的 prev_hash）。
        AuditLog genesis = logs.stream().filter(l -> l.prevHash == null).findFirst().orElseThrow();
        int walked = 1;
        String cursor = genesis.currentHash;
        boolean advanced = true;
        while (advanced) {
            advanced = false;
            for (AuditLog log : logs) {
                if (cursor.equals(log.prevHash)) {
                    cursor = log.currentHash;
                    walked++;
                    advanced = true;
                    break;
                }
            }
        }
        assertEquals(CONCURRENT_EVENTS, walked,
                "从 genesis 出发的单链应覆盖全部 " + CONCURRENT_EVENTS + " 条记录（无断链/无分叉）");
    }

    @Test
    void concurrentGenesisAcrossTenantsDoNotConflict() throws Exception {
        // 多租户首事件并发——各自独立 genesis，advisory lock 按租户隔离，互不冲突。
        int tenantCount = 10;
        for (int t = 0; t < tenantCount; t++) {
            auditEventProducer.fireAsync(createEvent("tenant-genesis-" + t, "m", "f"));
        }
        for (int t = 0; t < tenantCount; t++) {
            waitForAuditRecord("tenant-genesis-" + t, 1);
        }
        // 每个租户恰好一条 genesis
        for (int t = 0; t < tenantCount; t++) {
            List<AuditLog> logs = AuditLog.find("tenantId = ?1", "tenant-genesis-" + t).list();
            assertEquals(1, logs.size());
            assertEquals(null, logs.get(0).prevHash, "每租户首块应为 genesis");
            assertNotNull(logs.get(0).currentHash);
        }
    }

    private AuditEvent createEvent(String tenantId, String module, String function) {
        return new AuditEvent(
                EventType.POLICY_EVALUATION,
                Instant.now(),
                tenantId,
                module,
                function,
                null, null, null,
                "test-user",
                true,
                50L,
                null,
                Map.of(),
                null, null, null, null);
    }

    private void waitForAuditRecord(String tenantId, int expectedCount) throws InterruptedException {
        for (int i = 0; i < 50; i++) { // 最多等待 5 秒
            long count = AuditLog.count("tenantId = ?1", tenantId);
            if (count >= expectedCount) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timeout waiting for audit record: tenant=" + tenantId
                + ", expected=" + expectedCount);
    }
}
