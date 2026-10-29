package io.aster.policy.service;

import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发创建版本的哈希链完整性（issue #407）。
 *
 * <p>首个版本创建时策略尚无任何版本行，链尾行锁锁不到东西；并发事务都读到空链而各自
 * {@code prevHash = null}，链在起点分叉。链按 policyId 跨租户共享，故用按 policyId 的
 * advisory 锁串行化；不锁 catalog 行，以免与激活的「版本行 → catalog 行」顺序成环。
 */
@QuarkusTest
class PolicyVersionFirstVersionConcurrencyTest {

    private static final int THREADS = 8;

    @Inject
    PolicyVersionService versionService;

    @Inject
    io.agroal.api.AgroalDataSource dataSource;

    private String suffix;
    private String moduleName;
    private String policyId;
    private PolicyCatalog catalog;
    private PolicyCatalog otherTenantCatalog;

    /** 独立的模块/函数名：避免与种子数据共用 policyId 而污染链头判定。 */
    @BeforeEach
    @Transactional
    void initCatalogs() {
        suffix = UUID.randomUUID().toString().replace("-", "");
        moduleName = "aster.concurrency" + suffix;
        policyId = moduleName + ".decide";
        catalog = persistCatalog("tenant-a-" + suffix);
        otherTenantCatalog = persistCatalog("tenant-b-" + suffix);
    }

    private PolicyCatalog persistCatalog(String tenantId) {
        PolicyCatalog entity = new PolicyCatalog();
        entity.id = UUID.randomUUID();
        entity.tenantId = tenantId;
        entity.moduleName = moduleName;
        entity.functionName = "decide";
        entity.domain = "test";
        Instant now = Instant.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.persist();
        return entity;
    }

    @AfterEach
    @Transactional
    void cleanup() throws IOException {
        PolicyCatalog.update("defaultVersionId = null where moduleName = ?1", moduleName);
        PolicyVersion.delete("policyId = ?1", policyId);
        PolicyCatalog.delete("moduleName = ?1", moduleName);
        deleteRecursively(Paths.get("target", "policies", moduleName));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void concurrentFirstVersionsFormSingleLinearChain() throws Exception {
        createConcurrently(catalogs(THREADS, false));

        assertSingleLinearChain(THREADS);
    }

    @Test
    void concurrentFirstVersionsAcrossTenantsShareOneChain() throws Exception {
        createConcurrently(catalogs(THREADS, true));

        assertSingleLinearChain(THREADS);
    }

    @Test
    void concurrentCreationOnExistingChainStaysLinear() throws Exception {
        versionService.createVersion(catalog.id, "// seed", "en-US");

        createConcurrently(catalogs(THREADS, false));

        assertSingleLinearChain(THREADS + 1);
    }

    /**
     * 激活/回滚的加锁顺序是「版本行 → catalog 行」；创建版本若先锁 catalog 行就会与之成环
     * （deadlock detected, 40P01）。本用例用一条 JDBC 事务按激活的顺序加锁：先锁该策略全部版本行，
     * 等创建线程跑起来后再更新 catalog 行。创建线程若持有 catalog 行锁，两边互等，PG 会在
     * deadlock_timeout（默认 1s）后中止其一；采用 advisory 锁则两边都能完成。
     */
    @Test
    void createInterleavedWithActivationOrderDoesNotDeadlock() throws Exception {
        versionService.createVersion(catalog.id, "// seed", "en-US");
        CountDownLatch rowsLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> activation = pool.submit(() -> {
                try (java.sql.Connection c = dataSource.getConnection()) {
                    c.setAutoCommit(false);
                    try (java.sql.PreparedStatement lockRows = c.prepareStatement(
                             "UPDATE policy_versions SET notes = notes WHERE policy_id = ?");
                         java.sql.PreparedStatement touchCatalog = c.prepareStatement(
                             "UPDATE policy_catalog SET updated_at = now() WHERE id = ?")) {
                        lockRows.setString(1, policyId);
                        lockRows.executeUpdate();
                        rowsLocked.countDown();
                        Thread.sleep(2500);
                        touchCatalog.setObject(1, catalog.id);
                        touchCatalog.executeUpdate();
                        c.commit();
                    }
                }
                return null;
            });
            Future<PolicyVersion> creation = pool.submit(() -> {
                rowsLocked.await();
                return versionService.createVersion(catalog.id, "// draft", "en-US");
            });
            activation.get(60, TimeUnit.SECONDS);
            creation.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertSingleLinearChain(2);
    }

    @Test
    void unknownCatalogIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> versionService.createVersion(UUID.randomUUID(), "// x", "en-US"));
    }

    /** 每个线程使用的 catalog；crossTenant 时奇偶线程分属两个租户的同名策略。 */
    private List<UUID> catalogs(int threads, boolean crossTenant) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            ids.add(crossTenant && i % 2 == 1 ? otherTenantCatalog.id : catalog.id);
        }
        return ids;
    }

    private void createConcurrently(List<UUID> catalogIds) throws Exception {
        int threads = catalogIds.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<PolicyVersion>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String cnl = "// concurrent " + i;
                UUID catalogId = catalogIds.get(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return versionService.createVersion(catalogId, cnl, "en-US");
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "工作线程应在期限内就绪");
            go.countDown();
            for (Future<PolicyVersion> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 按 (version, id) 排序后，链头 prev_hash 为 null，其余每行 prev_hash 等于前一行的 chainLink()。 */
    private void assertSingleLinearChain(int expected) {
        List<PolicyVersion> stored = PolicyVersion.list("policyId = ?1 ORDER BY version ASC, id ASC", policyId);
        assertEquals(expected, stored.size(), "版本数应与创建次数一致");
        assertNull(stored.get(0).prevHash, "链头 prev_hash 必须为 null");
        for (int i = 1; i < stored.size(); i++) {
            assertEquals(stored.get(i - 1).chainLink(), stored.get(i).prevHash,
                "第 " + i + " 个版本的 prev_hash 必须指向按 (version, id) 排序的前一版本");
        }
    }
}
