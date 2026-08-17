package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 审计哈希载荷单测（2026-08-17 安全审计）。
 *
 * <p>不需要 Quarkus / 数据库上下文——纯函数，直接断言摘要行为。
 *
 * <p>守护两条不变量：
 * <ol>
 *   <li><b>V1 必须与历史实现逐字节一致</b>——否则既有审计链会全部验证失败
 *       （表现为「全链被篡改」的假警报）。</li>
 *   <li><b>V2 必须覆盖全部业务字段</b>——尤其是 performedBy / reason 这类
 *       问责字段。此前它们不在链内，可被静默改写而验证仍返回 valid。</li>
 * </ol>
 */
class AuditHashPayloadTest {

    private static AuditLog sample() {
        AuditLog log = new AuditLog();
        log.prevHash = "a".repeat(64);
        log.eventType = "POLICY_ROLLBACK";
        log.timestamp = Instant.parse("2026-08-17T10:00:00Z");
        log.tenantId = "tenant-a";
        log.performedBy = "alice";
        log.policyModule = "credit.risk";
        log.policyFunction = "assess";
        log.policyId = "pol-1";
        log.fromVersion = 7L;
        log.toVersion = 6L;
        log.executionTimeMs = 42L;
        log.success = Boolean.TRUE;
        log.reason = "回滚：v7 拒绝率异常";
        log.errorMessage = null;
        log.notes = "值班批准";
        log.metadata = "{\"ticket\":\"OPS-1\"}";
        log.clientIp = "10.0.0.1";
        log.userAgent = "curl/8";
        return log;
    }

    // ============================================================
    // V1：必须逐字节复刻历史公式（否则既有链全部失效）
    // ============================================================

    @Test
    void v1ReproducesLegacyFormulaByteForByte() {
        AuditLog log = sample();

        // 历史实现（AuditEventListener / AuditChainVerifier 中曾各有一份拷贝）：
        // SHA256(prev + eventType + timestamp + tenantId + policyModule + policyFunction + success)
        StringBuilder expected = new StringBuilder();
        expected.append(log.prevHash);
        expected.append(log.eventType);
        expected.append(log.timestamp.toString());
        expected.append(log.tenantId);
        expected.append(log.policyModule);
        expected.append(log.policyFunction);
        expected.append(log.success.toString());

        assertEquals(
            DigestUtils.sha256Hex(expected.toString()),
            AuditHashPayload.digest(log, AuditHashPayload.V1_LEGACY_SIX_FIELDS),
            "V1 公式一旦漂移，所有历史审计链会立刻验证失败（假『全链被篡改』告警）"
        );
    }

    @Test
    void v1TreatsNullVersionAsLegacy() {
        AuditLog log = sample();
        assertEquals(
            AuditHashPayload.digest(log, AuditHashPayload.V1_LEGACY_SIX_FIELDS),
            AuditHashPayload.digest(log, null),
            "读到 hash_version 为 null 的旧对象时必须按 V1 处理"
        );
    }

    @Test
    void unknownVersionFailsClosed() {
        AuditLog log = sample();
        // ★未知版本必须显式抛错（fail-closed），不得静默降级到 V1。
        //   降级看似保守，实则危险：只要攻击者让 6 个 V1 字段与 current_hash 自洽，
        //   一条验证器根本不理解的记录就会被判为有效。
        //   无法理解的版本 = 无法验证 → 必须是显式失败。
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> AuditHashPayload.digest(log, (short) 99),
            "未知 hash_version 必须抛错，绝不能静默按 V1 计算"
        );
    }

    // ============================================================
    // ★V1 的盲区（这正是本次审计发现的漏洞）：改问责字段不改摘要
    // ============================================================

    @Test
    void v1IsBlindToAccountabilityFields_documentedVulnerability() {
        AuditLog a = sample();
        AuditLog b = sample();
        b.performedBy = "mallory";      // 谁做的
        b.reason = "伪造的理由";          // 为什么
        b.clientIp = "1.2.3.4";
        b.metadata = "{}";

        // 这条断言记录的是**缺陷本身**：V1 下改写问责字段，摘要不变 → 验证仍 valid。
        assertEquals(
            AuditHashPayload.digest(a, AuditHashPayload.V1_LEGACY_SIX_FIELDS),
            AuditHashPayload.digest(b, AuditHashPayload.V1_LEGACY_SIX_FIELDS),
            "V1 只覆盖 6 字段——这是历史行为，也是 V2 存在的理由"
        );
    }

    // ============================================================
    // V2：全部业务字段必须进链（逐字段验证，不留盲区）
    // ============================================================

    @Test
    void v2DetectsTamperingOfEveryBusinessField() {
        String base = AuditHashPayload.digest(sample(), AuditHashPayload.V2_FULL_CANONICAL);

        record Mutation(String name, java.util.function.Consumer<AuditLog> apply) { }
        java.util.List<Mutation> mutations = java.util.List.of(
            new Mutation("prevHash", l -> l.prevHash = "b".repeat(64)),
            new Mutation("eventType", l -> l.eventType = "POLICY_CREATED"),
            new Mutation("timestamp", l -> l.timestamp = Instant.parse("2026-08-17T10:00:01Z")),
            new Mutation("tenantId", l -> l.tenantId = "tenant-b"),
            new Mutation("performedBy", l -> l.performedBy = "mallory"),
            new Mutation("policyModule", l -> l.policyModule = "other.mod"),
            new Mutation("policyFunction", l -> l.policyFunction = "other"),
            new Mutation("policyId", l -> l.policyId = "pol-2"),
            new Mutation("fromVersion", l -> l.fromVersion = 8L),
            new Mutation("toVersion", l -> l.toVersion = 5L),
            new Mutation("executionTimeMs", l -> l.executionTimeMs = 43L),
            new Mutation("success", l -> l.success = Boolean.FALSE),
            new Mutation("reason", l -> l.reason = "伪造的理由"),
            new Mutation("errorMessage", l -> l.errorMessage = "injected"),
            new Mutation("notes", l -> l.notes = "改过了"),
            new Mutation("metadata", l -> l.metadata = "{\"ticket\":\"OPS-2\"}"),
            new Mutation("clientIp", l -> l.clientIp = "1.2.3.4"),
            new Mutation("userAgent", l -> l.userAgent = "evil/1")
        );

        for (Mutation m : mutations) {
            AuditLog mutated = sample();
            m.apply().accept(mutated);
            assertNotEquals(
                base,
                AuditHashPayload.digest(mutated, AuditHashPayload.V2_FULL_CANONICAL),
                "篡改 " + m.name() + " 必须改变摘要——否则该字段不在链内保护范围"
            );
        }
    }

    @Test
    void v2DistinguishesNullFromEmptyString() {
        AuditLog withNull = sample();
        withNull.reason = null;
        AuditLog withEmpty = sample();
        withEmpty.reason = "";

        assertNotEquals(
            AuditHashPayload.digest(withNull, AuditHashPayload.V2_FULL_CANONICAL),
            AuditHashPayload.digest(withEmpty, AuditHashPayload.V2_FULL_CANONICAL),
            "null 与空串必须可区分，否则『把 reason 从 null 改成空串』能逃过校验"
        );
    }

    @Test
    void v2HasNoConcatenationBoundaryAmbiguity() {
        // 裸拼接的经典缺陷：("ab","c") 与 ("a","bc") 产出同一串，
        // 攻击者可在相邻字段间搬运字符而摘要不变。长度前缀编码必须消除该歧义。
        AuditLog x = sample();
        x.policyModule = "ab";
        x.policyFunction = "c";

        AuditLog y = sample();
        y.policyModule = "a";
        y.policyFunction = "bc";

        assertNotEquals(
            AuditHashPayload.digest(x, AuditHashPayload.V2_FULL_CANONICAL),
            AuditHashPayload.digest(y, AuditHashPayload.V2_FULL_CANONICAL),
            "相邻字段间搬运字符必须改变摘要（长度前缀编码的作用）"
        );
    }

    @Test
    void v1AndV2DifferForSameRecord() {
        AuditLog log = sample();
        assertNotEquals(
            AuditHashPayload.digest(log, AuditHashPayload.V1_LEGACY_SIX_FIELDS),
            AuditHashPayload.digest(log, AuditHashPayload.V2_FULL_CANONICAL),
            "两个版本必须产出不同摘要——否则版本列没有意义"
        );
    }

    @Test
    void currentVersionIsV2() {
        assertEquals(AuditHashPayload.V2_FULL_CANONICAL, AuditHashPayload.CURRENT_VERSION,
            "新写入必须使用全字段公式");
    }
}
