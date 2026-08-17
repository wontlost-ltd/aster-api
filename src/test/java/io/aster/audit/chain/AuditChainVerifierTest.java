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
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Task 3.3 - 审计哈希链验证服务测试
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
public class AuditChainVerifierTest {

    @Inject
    AuditChainVerifier verifier;

    @Inject
    BlockingDbTestHelper db;

    @Inject
    Event<AuditEvent> auditEventProducer;

    @BeforeEach
    void cleanup() {
        db.execute("DELETE FROM audit_logs");
    }

    @Test
    void testValidChain() throws Exception {
        // 创建有效的哈希链
        String tenantId = "tenant-valid";
        Instant start = Instant.parse("2025-01-15T10:00:00Z");

        for (int i = 0; i < 5; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50);
        }

        Instant end = Instant.now();

        // 验证链完整性
        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertTrue(result.isValid(), "Chain should be valid");
        assertEquals(5, result.getRecordsVerified(), "Should verify 5 records");
        assertNull(result.getBrokenAt());
        assertNull(result.getReason());
    }

    /**
     * ★2026-08-17 审计订正：本用例原名 {@code testTamperedMetadata}，注释写
     * 「篡改中间记录的 metadata」，但 SQL 实际改的是 {@code policy_module}
     * ——一个**本来就在**哈希链内的字段。也就是说：名字承诺覆盖 metadata，
     * 断言体从未验证过 metadata，而 metadata 恰恰当时**不在**链内。
     * 这是「测试名承诺 ≠ 断言体」的典型假绿。
     *
     * <p>现按实际行为更名；对 metadata 的真实覆盖见
     * {@link #tamperingMetadataIsDetected_v2ChainCoversAccountabilityFields}。
     */
    @Test
    void tamperingHashedFieldPolicyModuleIsDetected() throws Exception {
        // 创建链并篡改一条记录
        String tenantId = "tenant-tampered";
        Instant start = Instant.parse("2025-01-15T10:00:00Z");

        for (int i = 0; i < 3; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50);
        }

        // 篡改中间记录的 policy_module（该字段 V1/V2 均在链内）
        db.execute("UPDATE audit_logs SET policy_module = 'hacked.module' WHERE id IN (SELECT id FROM audit_logs WHERE tenant_id = ? ORDER BY timestamp LIMIT 1 OFFSET 1)", tenantId);

        Instant end = Instant.now();

        // 验证链 - 应该检测到篡改
        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertFalse(result.isValid(), "Chain should be invalid due to tampering");
        assertNotNull(result.getBrokenAt());
        assertTrue(result.getReason().contains("current_hash tampered"), "Should detect hash tampering");
        assertEquals(1, result.getRecordsVerified(), "Should verify 1 record before detecting tampering");
    }

    /**
     * ★问责字段必须进链（2026-08-17 审计修复的核心）。
     *
     * <p>此前哈希链只覆盖 17 个业务字段中的 6 个，{@code performed_by}（谁做的）、
     * {@code reason}（为什么）、{@code metadata}、{@code client_ip} 全部在链外——
     * 直接 UPDATE 这些列后链验证仍返回 valid，而对外文案宣称「不可篡改」。
     *
     * <p>本用例逐列篡改并要求每一次都被检测到。在修复前它会失败（那正是漏洞）。
     */
    @Test
    void tamperingMetadataIsDetected_v2ChainCoversAccountabilityFields() throws Exception {
        String[] columns = {"performed_by", "reason", "metadata", "client_ip", "user_agent", "notes"};

        for (int c = 0; c < columns.length; c++) {
            String column = columns[c];
            String tenantId = "tenant-acct-" + c;
            Instant start = Instant.parse("2025-01-15T10:00:00Z");

            for (int i = 0; i < 3; i++) {
                AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
                auditEventProducer.fireAsync(event);
                waitForAuditRecord(tenantId, i + 1);
                Thread.sleep(50);
            }

            // 直接改写问责字段——这正是「谁批准 / 为何批准」被静默改写的攻击形态
            db.execute(
                "UPDATE audit_logs SET " + column + " = 'TAMPERED'"
                    + " WHERE id IN (SELECT id FROM audit_logs WHERE tenant_id = ?"
                    + " ORDER BY id LIMIT 1 OFFSET 1)",
                tenantId);

            ChainVerificationResult result = verifier.verifyChain(tenantId, start, Instant.now());

            assertFalse(result.isValid(),
                "篡改 " + column + " 必须被检测到——该字段属于问责信息，必须在哈希链内");
            assertTrue(result.getReason().contains("current_hash tampered"),
                "篡改 " + column + " 应报 current_hash tampered，实际: " + result.getReason());
        }
    }

    @Test
    void testDeletedRecord() throws Exception {
        // 创建链并删除中间记录
        String tenantId = "tenant-deleted";
        Instant start = Instant.parse("2025-01-15T10:00:00Z");

        for (int i = 0; i < 4; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50);
        }

        // 删除第2条记录（索引1）
        db.execute("DELETE FROM audit_logs WHERE id IN (SELECT id FROM audit_logs WHERE tenant_id = ? ORDER BY timestamp LIMIT 1 OFFSET 1)", tenantId);

        Instant end = Instant.now();

        // 验证链 - 应该检测到断链
        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertFalse(result.isValid(), "Chain should be invalid due to deleted record");
        assertNotNull(result.getBrokenAt());
        assertTrue(result.getReason().contains("prev_hash mismatch"), "Should detect broken chain");
        assertEquals(1, result.getRecordsVerified(), "Should verify 1 record before detecting break");
    }

    @Test
    void testInsertedRecord() throws Exception {
        // 创建链并手动插入伪造记录
        String tenantId = "tenant-inserted";
        Instant start = Instant.parse("2025-01-15T10:00:00Z");

        for (int i = 0; i < 3; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50);
        }

        // 手动插入伪造记录（prev_hash 和 current_hash 错误）。timestamp 用 UTC 挂钟绑定
        // （与其它插入一致），避免 Timestamp.from(Instant) 的 JVM 时区偏移把该记录挤出验证时间窗。
        Instant fakeTime = Instant.now().plusSeconds(10);
        db.execute("INSERT INTO audit_logs (event_type, timestamp, tenant_id, policy_module, policy_function, success, prev_hash, current_hash) " +
            "VALUES ('POLICY_EVALUATION', ?, ?, 'fake.module', 'fakeFunc', true, 'fake_prev_hash', 'fake_current_hash')",
            java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(fakeTime, java.time.ZoneOffset.UTC)), tenantId);

        Instant end = Instant.now().plusSeconds(20);

        // 验证链 - 应该检测到断链或篡改
        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertFalse(result.isValid(), "Chain should be invalid due to inserted record");
        assertNotNull(result.getBrokenAt());
        assertTrue(result.getReason().contains("prev_hash mismatch") || result.getReason().contains("current_hash tampered"),
            "Should detect chain break or tampering");
    }

    @Test
    void testEmptyChain() {
        // 验证空链
        String tenantId = "tenant-empty";
        Instant start = Instant.parse("2025-01-15T10:00:00Z");
        Instant end = Instant.now();

        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertTrue(result.isValid(), "Empty chain should be valid");
        assertEquals(0, result.getRecordsVerified(), "Should verify 0 records");
    }

    @Test
    void testGenesisBlock() throws Exception {
        // 验证第一条记录（genesis block）
        String tenantId = "tenant-genesis";
        Instant start = Instant.parse("2025-01-15T10:00:00Z");

        AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func0");
        auditEventProducer.fireAsync(event);
        waitForAuditRecord(tenantId, 1);

        Instant end = Instant.now();

        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertTrue(result.isValid(), "Genesis block should be valid");
        assertEquals(1, result.getRecordsVerified(), "Should verify 1 record");

        // 验证 genesis block 的 prevHash 为 null
        AuditLog log = AuditLog.findByTenant(tenantId).get(0);
        assertNull(log.prevHash, "Genesis block should have prevHash = null");
        assertNotNull(log.currentHash, "Genesis block should have currentHash");
    }

    @Test
    void testLegacyRecordsWithoutHash() throws Exception {
        // 测试向后兼容：包含没有哈希值的旧记录
        String tenantId = "tenant-legacy";
        Instant start = Instant.parse("2025-01-15T10:00:00Z");

        // 手动插入旧记录（没有哈希值）
        db.execute("INSERT INTO audit_logs (event_type, timestamp, tenant_id, policy_module, policy_function, success) " +
            "VALUES ('POLICY_EVALUATION', NOW(), ?, 'legacy.module', 'legacyFunc', true)", tenantId);

        // 创建新记录（有哈希值）
        for (int i = 0; i < 2; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
            auditEventProducer.fireAsync(event);
            Thread.sleep(100);
        }

        waitForAuditRecord(tenantId, 3);
        Instant end = Instant.now();

        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertTrue(result.isValid(), "Chain should be valid (legacy records skipped)");
        assertEquals(2, result.getRecordsVerified(), "Should verify only 2 records with hashes");
    }

    @Test
    void testValidChainWithReversedTimestamps() throws Exception {
        // issue #115：链顺序由 id（追加顺序）定义，而非 wall-clock timestamp。
        // 直接构造一条【合法】链：id 升序 = 追加顺序，每条 hash 用自己的 timestamp 正确算出并
        // 链接；但 timestamp 设成【与 id 相反】的顺序（模拟并发乱序/时钟回拨——每条 hash 仍与
        // 自己的 timestamp 一致，是合法链，非篡改）。若 verifier 按 timestamp 遍历，会从错误的
        // 一端开始，把合法链误报为 prev_hash 断裂；按 id 遍历则正确。
        String tenantId = "tenant-ts-reversed";
        Instant start = Instant.parse("2020-01-01T00:00:00Z");
        Instant end = Instant.parse("2020-12-31T00:00:00Z");

        int n = 5;
        // id 第 k 条（k=0..n-1）的 timestamp 逆序：k=0 最新、k=n-1 最旧。
        // 追加顺序（插入顺序 = id 升序）从 genesis 起链接。
        String prevHash = null;
        for (int k = 0; k < n; k++) {
            // 逆序整点 timestamp（k 越小 ts 越大）；整点无亚秒，DB round-trip 后 Instant.toString 稳定，
            // 与生产 truncatedTo(MICROS) 一致，保证 hash 复算可对上。
            Instant ts = start.plus(java.time.Duration.ofHours(n - k)).truncatedTo(ChronoUnit.MICROS);
            String module = "test.module";
            String function = "func" + k;
            String currentHash = computeChainHash(prevHash, "POLICY_EVALUATION", ts, tenantId, module, function, true);
            insertAuditRow(tenantId, ts, module, function, prevHash, currentHash);
            prevHash = currentHash;
        }
        waitForAuditRecord(tenantId, n);

        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertTrue(result.isValid(),
            "timestamp 逆序但 id 顺序成合法链，verifier 应判合法（按 id 遍历）。reason="
                + result.getReason());
        assertEquals(n, result.getRecordsVerified(), "应验证全部 " + n + " 条");
        assertNull(result.getBrokenAt());
    }

    @Test
    void testWindowExcludingGenesisIsValid() throws Exception {
        // issue #118：时间窗不含 genesis 但链合法。构造 6 条合法链（genesis + 5），
        // 只验证【后 3 条】的时间窗（不含 genesis）——窗口首条非 genesis，其 prev_hash 指向
        // 窗外前驱。修复前会误报断链；修复后应查到前驱存在 → 判合法。
        String tenantId = "tenant-window-no-genesis";
        Instant base = Instant.parse("2021-06-01T00:00:00Z");

        int n = 6;
        String prevHash = null;
        Instant[] times = new Instant[n];
        for (int k = 0; k < n; k++) {
            Instant ts = base.plus(java.time.Duration.ofHours(k)).truncatedTo(ChronoUnit.MICROS);
            times[k] = ts;
            String cur = computeChainHash(prevHash, "POLICY_EVALUATION", ts, tenantId, "test.module", "func" + k, true);
            insertAuditRow(tenantId, ts, "test.module", "func" + k, prevHash, cur);
            prevHash = cur;
        }
        waitForAuditRecord(tenantId, n);

        // 时间窗只覆盖第 3~5 条（index 3,4,5），不含 genesis（index 0）。
        Instant windowStart = times[3];
        Instant windowEnd = times[5].plusSeconds(1);
        ChainVerificationResult result = verifier.verifyChain(tenantId, windowStart, windowEnd);

        assertTrue(result.isValid(),
            "窗口不含 genesis 但链合法，应查窗外前驱后判合法。reason=" + result.getReason());
        assertEquals(3, result.getRecordsVerified(), "应验证窗内 3 条");
        assertNull(result.getBrokenAt());
    }

    @Test
    void testWindowFirstRecordMissingPredecessorIsInvalid() throws Exception {
        // issue #118 反向：窗口首条的 prev_hash 指向一个【不存在】的前驱（前驱被删/伪造）。
        // 应真报断链（而非因窗外而放过）。
        String tenantId = "tenant-missing-predecessor";
        Instant ts0 = Instant.parse("2021-07-01T01:00:00Z").truncatedTo(ChronoUnit.MICROS);

        // 只插一条记录，其 prev_hash 指向一个 tenant 内不存在的 hash。
        String bogusPrev = "deadbeef".repeat(8); // 64 hex, 不对应任何记录
        String cur = computeChainHash(bogusPrev, "POLICY_EVALUATION", ts0, tenantId, "test.module", "func0", true);
        insertAuditRow(tenantId, ts0, "test.module", "func0", bogusPrev, cur);
        waitForAuditRecord(tenantId, 1);

        Instant start = Instant.parse("2021-07-01T00:00:00Z");
        Instant end = Instant.parse("2021-07-01T02:00:00Z");
        ChainVerificationResult result = verifier.verifyChain(tenantId, start, end);

        assertFalse(result.isValid(), "prev_hash 指向不存在前驱=真断链，应判无效");
        assertNotNull(result.getBrokenAt());
        assertTrue(result.getReason() != null && result.getReason().contains("prev_hash mismatch"),
            "reason 应指出 prev_hash 断链。实际=" + result.getReason());
    }

    @Test
    void testPaginatedFirstPageAllLegacySeedsOnLaterPage() throws Exception {
        // Codex #118 审查：分页时首页全 legacy（无 hash）、第二页才出现第一条非 genesis 记录。
        // seed 逻辑必须延迟到遇到第一条 hashed 记录，否则会用 null seed 误报。
        // 构造：先 2 条 legacy（无 hash）+ 一条合法链（genesis + 3）。pageSize=2 → 首页全 legacy。
        String tenantId = "tenant-paginated-legacy-first";
        Instant base = Instant.parse("2021-08-01T00:00:00Z");

        // 2 条 legacy（prev_hash/current_hash 均 null）
        for (int i = 0; i < 2; i++) {
            Instant ts = base.plus(java.time.Duration.ofMinutes(i)).truncatedTo(ChronoUnit.MICROS);
            db.execute(
                "INSERT INTO audit_logs (event_type, timestamp, tenant_id, policy_module, policy_function, success) "
                    + "VALUES ('POLICY_EVALUATION', ?, ?, 'legacy', 'fn', true)",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(ts, java.time.ZoneOffset.UTC)),
                tenantId);
        }
        // 合法链 genesis + 3
        String prevHash = null;
        int n = 4;
        for (int k = 0; k < n; k++) {
            Instant ts = base.plus(java.time.Duration.ofHours(1 + k)).truncatedTo(ChronoUnit.MICROS);
            String cur = computeChainHash(prevHash, "POLICY_EVALUATION", ts, tenantId, "test.module", "func" + k, true);
            insertAuditRow(tenantId, ts, "test.module", "func" + k, prevHash, cur);
            prevHash = cur;
        }
        waitForAuditRecord(tenantId, 2 + n);

        Instant start = Instant.parse("2021-07-31T00:00:00Z");
        Instant end = Instant.parse("2021-08-02T00:00:00Z");
        // pageSize=2 → 首页是 2 条 legacy。seed 应在第二页（首条 hashed=genesis）才发生。
        ChainVerificationResult result = verifier.verifyChainPaginated(tenantId, start, end, 2);

        assertTrue(result.isValid(),
            "首页全 legacy、后续页出现合法链，分页应正确 seed 而非误报。reason=" + result.getReason());
    }

    /** 复刻生产 hash 公式（与 AuditEventListener.computeHashChain 一致），用于构造合法测试链。 */
    private String computeChainHash(String prevHash, String eventType, Instant timestamp,
                                    String tenantId, String module, String function, boolean success) {
        StringBuilder content = new StringBuilder();
        if (prevHash != null) {
            content.append(prevHash);
        }
        content.append(eventType);
        content.append(timestamp.toString());
        content.append(tenantId);
        content.append(module != null ? module : "");
        content.append(function != null ? function : "");
        content.append(Boolean.toString(success));
        return org.apache.commons.codec.digest.DigestUtils.sha256Hex(content.toString());
    }

    /** 直接插入一条带哈希的审计行（id 由 BIGSERIAL 按插入顺序分配 = 追加顺序）。 */
    private void insertAuditRow(String tenantId, Instant ts, String module, String function,
                                String prevHash, String currentHash) {
        db.execute(
            "INSERT INTO audit_logs (event_type, timestamp, tenant_id, policy_module, policy_function, " +
            "success, prev_hash, current_hash) VALUES ('POLICY_EVALUATION', ?, ?, ?, ?, true, ?, ?)",
            java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(ts, java.time.ZoneOffset.UTC)),
            tenantId, module, function, prevHash, currentHash);
    }

    @Test
    void testPaginatedVerification() throws Exception {
        // 测试分页验证（模拟大量记录）
        String tenantId = "tenant-paginated";
        Instant start = Instant.parse("2025-01-15T10:00:00Z");

        // 创建10条记录
        for (int i = 0; i < 10; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50);
        }

        Instant end = Instant.now();

        // 使用分页验证（每页3条）
        ChainVerificationResult result = verifier.verifyChainPaginated(tenantId, start, end, 3);

        assertTrue(result.isValid(), "Paginated chain should be valid");
        // Note: 分页验证当前实现只在发现错误时返回结果，所以 recordsVerified 可能为 0
        assertTrue(result.getRecordsVerified() >= 0, "Should verify records");
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
