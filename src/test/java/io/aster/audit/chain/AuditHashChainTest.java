package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Task 3.2 - 审计哈希链功能测试
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
public class AuditHashChainTest {

    @Inject
    BlockingDbTestHelper db;

    @Inject
    Event<AuditEvent> auditEventProducer;

    @BeforeEach
    void cleanup() {
        db.execute("DELETE FROM audit_logs");
    }

    @Test
    void testFirstRecordGenesisBlock() throws Exception {
        // 创建第一条审计记录（genesis block）
        String tenantId = "tenant-genesis";
        AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.policy", "evaluate");

        auditEventProducer.fireAsync(event);
        waitForAuditRecord(tenantId, 1);

        // 验证 genesis block
        AuditLog log = AuditLog.findByTenant(tenantId).get(0);
        assertNull(log.prevHash, "Genesis block should have prevHash = null");
        assertNotNull(log.currentHash, "Genesis block should have currentHash");
        assertEquals(64, log.currentHash.length(), "SHA256 hex should be 64 characters");
    }

    @Test
    void testSequentialRecords() throws Exception {
        String tenantId = "tenant-sequential";

        // 创建 3 条连续的审计记录
        for (int i = 1; i <= 3; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.policy", "eval" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i);
            Thread.sleep(50); // 确保时间戳不同
        }

        // 验证哈希链连续性
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertEquals(3, logs.size());

        // logs 按 timestamp desc 排序，需要反转
        AuditLog log1 = logs.get(2); // 最早
        AuditLog log2 = logs.get(1);
        AuditLog log3 = logs.get(0); // 最新

        // 验证第一条记录（genesis）
        assertNull(log1.prevHash);
        assertNotNull(log1.currentHash);

        // 验证第二条记录链接到第一条
        assertEquals(log1.currentHash, log2.prevHash, "log2.prevHash should equal log1.currentHash");
        assertNotNull(log2.currentHash);
        assertNotEquals(log1.currentHash, log2.currentHash, "Hashes should be different");

        // 验证第三条记录链接到第二条
        assertEquals(log2.currentHash, log3.prevHash, "log3.prevHash should equal log2.currentHash");
        assertNotNull(log3.currentHash);
        assertNotEquals(log2.currentHash, log3.currentHash, "Hashes should be different");
    }

    @Test
    void testHashComputation() throws Exception {
        String tenantId = "tenant-hash-test";
        Instant timestamp = Instant.parse("2025-01-15T10:00:00Z");

        AuditEvent event = new AuditEvent(
            EventType.POLICY_EVALUATION,
            timestamp,
            tenantId,
            "test.module",
            "testFunction",
            null,
            null,
            null,
            "test-user",
            true,
            100L,
            null,
            Map.of(),
            null, null, null, null  // Phase 3.7 fields
        );

        auditEventProducer.fireAsync(event);
        waitForAuditRecord(tenantId, 1);

        AuditLog log = AuditLog.findByTenant(tenantId).get(0);

        // ★2026-08-17 审计：此处原本在测试里**手抄一遍 6 字段公式**再比对。
        //   这既是「测试复刻算法」的假绿模式（比对的是测试自己的实现，
        //   而非生产代码真的这么算），也让公式升级必然误报。
        //   现改为调用生产代码的唯一公式入口 AuditHashPayload，
        //   并按记录自身的 hash_version 选择算法。
        assertEquals(
            AuditHashPayload.digest(log, log.hashVersion),
            log.currentHash,
            "持久化的 current_hash 必须与 AuditHashPayload 对同一记录的计算一致"
        );

        // 新写入必须使用 V2（全业务字段），否则问责字段又会滑出链外
        assertEquals(AuditHashPayload.CURRENT_VERSION, log.hashVersion,
            "新审计记录必须以 V2 全字段公式入链");
    }

    /**
     * ★问责字段必须进链：直接比对「只改 performedBy」是否会改变摘要。
     *
     * <p>修复前 performedBy 不在哈希输入内，本断言会失败——那正是漏洞本身。
     */
    @Test
    void accountabilityFieldsAreInsideTheChain() throws Exception {
        String tenantId = "tenant-acct-inline";
        auditEventProducer.fireAsync(createEvent(tenantId, "POLICY_EVALUATION", "m", "f"));
        waitForAuditRecord(tenantId, 1);

        AuditLog log = AuditLog.findByTenant(tenantId).get(0);
        String before = AuditHashPayload.digest(log, log.hashVersion);

        String original = log.performedBy;
        log.performedBy = "mallory-" + System.nanoTime();
        String after = AuditHashPayload.digest(log, log.hashVersion);
        log.performedBy = original; // 复原内存对象，避免污染后续断言

        assertNotEquals(before, after,
            "改写 performedBy（谁做的）必须改变摘要——否则问责信息不在防篡改保护范围内");
    }

    @Test
    void testDifferentTenantsIsolated() throws Exception {
        // 为两个不同租户创建审计记录
        String tenantA = "tenant-A";
        String tenantB = "tenant-B";

        auditEventProducer.fireAsync(createEvent(tenantA, "POLICY_EVALUATION", "test.a", "eval1"));
        waitForAuditRecord(tenantA, 1);

        auditEventProducer.fireAsync(createEvent(tenantB, "POLICY_EVALUATION", "test.b", "eval1"));
        waitForAuditRecord(tenantB, 1);

        auditEventProducer.fireAsync(createEvent(tenantA, "POLICY_EVALUATION", "test.a", "eval2"));
        waitForAuditRecord(tenantA, 2);

        // 验证租户 A 的哈希链
        List<AuditLog> logsA = AuditLog.findByTenant(tenantA);
        assertEquals(2, logsA.size());
        AuditLog a1 = logsA.get(1); // 最早
        AuditLog a2 = logsA.get(0); // 最新
        assertNull(a1.prevHash);
        assertEquals(a1.currentHash, a2.prevHash);

        // 验证租户 B 的哈希链（独立）
        List<AuditLog> logsB = AuditLog.findByTenant(tenantB);
        assertEquals(1, logsB.size());
        AuditLog b1 = logsB.get(0);
        assertNull(b1.prevHash, "Tenant B should have its own genesis block");

        // 验证两个租户的哈希值不同
        assertNotEquals(a1.currentHash, b1.currentHash, "Different tenants should have different hashes");
    }

    @Test
    void testSequentialInsertsWithDelay() throws Exception {
        String tenantId = "tenant-sequential-delay";
        int count = 5;

        // 顺序创建多条审计记录，确保每条记录的事务都已提交
        for (int i = 0; i < count; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.sequential", "eval" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50); // 确保事务提交和时间戳不同
        }

        // 验证哈希链完整性
        List<AuditLog> logs = AuditLog.findByTenant(tenantId);
        assertEquals(count, logs.size());

        // 反转列表以按时间升序排列
        List<AuditLog> sortedLogs = new ArrayList<>(logs);
        sortedLogs.sort((a, b) -> a.timestamp.compareTo(b.timestamp));

        // 验证哈希链连续性
        for (int i = 0; i < sortedLogs.size(); i++) {
            AuditLog log = sortedLogs.get(i);
            assertNotNull(log.currentHash, "All logs should have currentHash");

            if (i == 0) {
                assertNull(log.prevHash, "First log should have prevHash = null");
            } else {
                AuditLog prevLog = sortedLogs.get(i - 1);
                assertEquals(prevLog.currentHash, log.prevHash,
                    "Log " + i + " should link to previous log");
            }
        }
    }

    private AuditEvent createEvent(String tenantId, String eventType, String module, String function) {
        return new AuditEvent(
            EventType.valueOf(eventType),
            Instant.now(),
            tenantId,
            module,
            function,
            null,
            null,
            null,
            "test-user",
            true,
            50L,
            null,
            Map.of(),
            null, null, null, null  // Phase 3.7 fields
        );
    }

    private void waitForAuditRecord(String tenantId, int expectedCount) throws InterruptedException {
        for (int i = 0; i < 50; i++) { // 最多等待 5 秒
            long count = AuditLog.count("tenantId = ?1", tenantId);
            if (count >= expectedCount) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timeout waiting for audit record: tenant=" + tenantId + ", expected=" + expectedCount);
    }
}
