package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 审计哈希 V2 黄金向量。
 *
 * <p>aster-lang.dev 的 AI Guardian 演示在浏览器里用 TS 复刻了 {@link AuditHashPayload} 的 V2 公式，
 * 同一组向量写在 aster-dev 的 {@code src/features/guardian/evidence.test.ts}。任何一侧改变公式，
 * 各自的测试都会失败——这是两份实现保持逐字节一致的唯一锚点。
 *
 * <p>覆盖：创世块（prevHash 为 null）、链式块、非 ASCII 与 BMP 外字符（长度按 UTF-16 码元计）、
 * null 与空串必须得到不同摘要。
 */
class AuditHashPayloadVectorsTest {

    static final String V1 = "52072f98c77244cca47abd2eea21dee4d2354a9eb9636884a2e6ad9789fac724";
    static final String V2 = "98536dd23f36d57b63547be50dd70c9e617152491d0bd8d7fa6dce85e3bbd239";
    static final String V3 = "882fcb4d75425715649efe36df3aaa660c416a0489614f42b3c6918b55b91c54";
    static final String V4 = "6131ac2cfef032dd5fe783d140bd47f061fde3f5568c98785f05331dd12ef8e3";

    private static AuditLog decision(String prevHash, String ts, boolean success, String metadata) {
        AuditLog log = new AuditLog();
        log.prevHash = prevHash;
        log.eventType = "ACTION_GUARD_DECISION";
        log.timestamp = Instant.parse(ts);
        log.tenantId = "demo";
        log.performedBy = "support-bot";
        log.policyModule = "guard.support";
        log.policyFunction = "decide";
        log.toVersion = 1L;
        log.success = success;
        log.metadata = metadata;
        return log;
    }

    private static AuditLog v1() {
        return decision(null, "2026-10-18T09:00:00Z", true,
            "{\"outcome\":\"ALLOW\",\"ruleId\":\"SUPPORT-001\",\"actionName\":\"issue_refund\"}");
    }

    private static AuditLog v2() {
        return decision(V1, "2026-10-18T09:00:05Z", false,
            "{\"outcome\":\"REQUIRE_APPROVAL\",\"ruleId\":\"SUPPORT-001\",\"actionName\":\"delete_customer_record\"}");
    }

    private static AuditLog v3() {
        AuditLog log = new AuditLog();
        log.prevHash = V2;
        log.eventType = "ACTION_GUARD_APPROVAL";
        log.timestamp = Instant.parse("2026-10-18T09:01:00Z");
        log.tenantId = "demo";
        log.performedBy = "dana";
        log.success = Boolean.TRUE;
        log.reason = "数据保护官已批准 — Löschung ✓ 🛡";
        log.metadata = "{\"outcome\":\"APPROVED\",\"requiredRole\":\"Data Protection Officer\"}";
        return log;
    }

    private static String digest(AuditLog log) {
        return AuditHashPayload.digest(log, AuditHashPayload.CURRENT_VERSION);
    }

    @Test
    void genesisDecision() {
        assertEquals(V1, digest(v1()));
    }

    @Test
    void chainedDecision() {
        assertEquals(V2, digest(v2()));
    }

    @Test
    void nonAsciiReasonUsesUtf16Length() {
        assertEquals(24, v3().reason.length());
        assertEquals(V3, digest(v3()));
    }

    @Test
    void emptyReasonDiffersFromNull() {
        AuditLog withEmpty = v1();
        withEmpty.reason = "";
        assertEquals(V4, digest(withEmpty));
        assertNotEquals(V1, V4);
    }
}
