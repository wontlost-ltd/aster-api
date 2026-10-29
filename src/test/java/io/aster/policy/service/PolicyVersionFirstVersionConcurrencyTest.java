package io.aster.policy.service;

import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发创建版本的哈希链完整性（issue #407）。
 *
 * <p>首个版本创建时策略尚无任何版本行，链尾行锁锁不到东西；并发事务都读到空链而各自
 * {@code prevHash = null}，链在起点分叉。修复后以策略目录行锁串行化，必须恰有一个链头。
 */
@QuarkusTest
class PolicyVersionFirstVersionConcurrencyTest {

    private static final int THREADS = 8;

    @Inject
    PolicyVersionService versionService;

    private PolicyCatalog catalog;
    private String policyId;

    /** 独立的模块/函数名：避免与种子数据共用 policyId 而污染链头判定。 */
    @BeforeEach
    @Transactional
    void initCatalog() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        PolicyCatalog entity = new PolicyCatalog();
        entity.id = UUID.randomUUID();
        entity.tenantId = "tenant-first-version-" + suffix;
        entity.moduleName = "aster.concurrency" + suffix;
        entity.functionName = "decide";
        entity.domain = "test";
        Instant now = Instant.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.persist();
        catalog = entity;
        policyId = entity.moduleName + "." + entity.functionName;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        PolicyCatalog.update("defaultVersionId = null where id = ?1", catalog.id);
        PolicyVersion.delete("policyId = ?1", policyId);
        PolicyCatalog.delete("id = ?1", catalog.id);
    }

    @Test
    void concurrentFirstVersionsFormSingleLinearChain() throws Exception {
        List<PolicyVersion> created = createConcurrently(THREADS);

        assertSingleLinearChain(created, THREADS);
    }

    @Test
    void concurrentCreationOnExistingChainStaysLinear() throws Exception {
        versionService.createVersion(catalog.id, "// seed", "en-US");

        List<PolicyVersion> created = createConcurrently(THREADS);

        assertEquals(THREADS, created.size());
        List<PolicyVersion> all = allVersions();
        assertSingleLinearChain(all, THREADS + 1);
    }

    @Test
    void unknownCatalogIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> versionService.createVersion(UUID.randomUUID(), "// x", "en-US"));
    }

    private List<PolicyVersion> createConcurrently(int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<PolicyVersion>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String cnl = "// concurrent " + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return versionService.createVersion(catalog.id, cnl, "en-US");
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "工作线程应在期限内就绪");
            go.countDown();
            List<PolicyVersion> result = new ArrayList<>();
            for (Future<PolicyVersion> f : futures) {
                result.add(f.get(60, TimeUnit.SECONDS));
            }
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<PolicyVersion> allVersions() {
        return PolicyVersion.list("policyId = ?1", policyId);
    }

    /** 链头恰有一个（prevHash 为 null），其余 prevHash 互不相同（无分叉）。 */
    private void assertSingleLinearChain(List<PolicyVersion> versions, int expected) {
        List<PolicyVersion> stored = allVersions();
        assertEquals(expected, stored.size(), "版本数应与创建次数一致");
        long heads = stored.stream().filter(v -> v.prevHash == null).count();
        assertEquals(1, heads, "链头（prev_hash 为 null）必须恰有一个");
        Set<String> prevs = new HashSet<>();
        for (PolicyVersion v : stored) {
            if (v.prevHash != null) {
                assertTrue(prevs.add(v.prevHash), "同一 prev_hash 不得挂两个后继: " + v.prevHash);
            }
        }
        Set<String> links = new HashSet<>();
        stored.forEach(v -> links.add(v.chainLink()));
        assertTrue(links.containsAll(prevs), "每个 prev_hash 必须指向链内某版本");
        assertNotNull(versions);
    }
}
