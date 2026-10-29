package io.aster.policy.service;

import io.aster.policy.entity.PolicyVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * envelope tip-anchor 校验器测试（ADR 0022 §11.5）——纯逻辑，无 DB。
 *
 * <p>验证补哈希链尾部缺口：重算 envelope 能检测任意行（含链尾最新行）的篡改。
 */
class PolicyVersionEnvelopeVerifierTest {

    private final PolicyVersionEnvelopeVerifier verifier = new PolicyVersionEnvelopeVerifier();

    private static PolicyVersion versionWith(String content, String aliasSet, String locale, String toolchain) {
        PolicyVersion v = new PolicyVersion();
        v.id = 1L;
        v.content = content;
        v.aliasSet = aliasSet;
        v.locale = locale;
        v.sourceToolchainId = toolchain;
        v.sourceEnvelopeSha256 = PolicyVersion.computeSourceEnvelope(content, aliasSet, locale, toolchain, "");
        return v;
    }

    @Test
    void intactWhenEnvelopeMatches() {
        PolicyVersion v = versionWith("Module M.", "{\"TIMES\":[\"multiplied by\"]}", "en-US", "abi=1.0;core=dev");
        assertEquals(PolicyVersionEnvelopeVerifier.VerifyResult.Status.INTACT, verifier.verify(v).status());
    }

    @Test
    void tamperedWhenAliasSetSwappedAfterTheFact() {
        // 模拟链尾篡改：alias_set 被改但 envelope 未同步重算（或被改成别的值）
        PolicyVersion v = versionWith("Module M.", "{\"TIMES\":[\"multiplied by\"]}", "en-US", "abi=1.0;core=dev");
        v.aliasSet = "{\"TIMES\":[\"scaled by\"]}"; // 篡改 alias_set，envelope 仍是旧的
        assertEquals(PolicyVersionEnvelopeVerifier.VerifyResult.Status.TAMPERED, verifier.verify(v).status());
    }

    @Test
    void tamperedWhenContentChanged() {
        PolicyVersion v = versionWith("Module M.", null, "en-US", "abi=1.0;core=dev");
        v.content = "Module Evil.";
        assertEquals(PolicyVersionEnvelopeVerifier.VerifyResult.Status.TAMPERED, verifier.verify(v).status());
    }

    @Test
    void tamperedWhenEnvelopeFieldItselfSwapped() {
        // 攻击者改 alias_set 并把 envelope 也改成"另一套别名的 envelope"——重算仍不符当前字段
        PolicyVersion v = versionWith("Module M.", "{\"TIMES\":[\"multiplied by\"]}", "en-US", "abi=1.0;core=dev");
        String otherEnvelope = PolicyVersion.computeSourceEnvelope(
            "Module M.", "{\"TIMES\":[\"scaled by\"]}", "en-US", "abi=1.0;core=dev", "");
        v.sourceEnvelopeSha256 = otherEnvelope; // envelope 改成"scaled by"版本的
        // 但 alias_set 字段仍是 multiplied by → 重算(multiplied by)≠存储(scaled by 的 envelope)
        assertEquals(PolicyVersionEnvelopeVerifier.VerifyResult.Status.TAMPERED, verifier.verify(v).status());
    }

    @Test
    void selfConsistentFullTamperPassesVerify_documentedLimitation() {
        // 威胁边界（勿夸大）：攻击者同时改 content + alias_set 并**重算** envelope，使四者自洽
        // → verifier 会"通过"。这是本校验器的已知边界，防全套自洽篡改须靠外部锚定/审计链。
        PolicyVersion v = versionWith("Module M.", "{\"TIMES\":[\"multiplied by\"]}", "en-US", "abi=1.0;core=dev");
        // 全套篡改：改 content + alias_set，并同步重算 envelope（攻击者有 DB 写权限即可）
        v.content = "Module Evil.";
        v.aliasSet = "{\"TIMES\":[\"scaled by\"]}";
        v.sourceEnvelopeSha256 = PolicyVersion.computeSourceEnvelope(
            v.content, v.aliasSet, v.locale, v.sourceToolchainId, "");
        // verifier 重算与篡改后字段自洽 → INTACT（本校验器**防不住**全套自洽篡改）
        assertEquals(PolicyVersionEnvelopeVerifier.VerifyResult.Status.INTACT, verifier.verify(v).status());
    }

    @Test
    void skippedWhenLegacyNoEnvelope() {
        PolicyVersion v = new PolicyVersion();
        v.id = 2L;
        v.content = "Module M.";
        v.sourceEnvelopeSha256 = null;
        v.sourceToolchainId = null;
        assertEquals(PolicyVersionEnvelopeVerifier.VerifyResult.Status.SKIPPED_LEGACY, verifier.verify(v).status());
    }
}
