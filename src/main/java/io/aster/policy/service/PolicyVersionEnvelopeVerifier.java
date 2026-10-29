package io.aster.policy.service;

import io.aster.policy.entity.PolicyVersion;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * 版本 envelope 完整性校验（ADR 0022 §11.5，补哈希链尾部缺口）。
 *
 * <p>哈希链（{@code prevHash → chainLink}）能检测"有后继版本的历史行"被篡改，但**最新行**
 * （链尾）被改 {@code alias_set + source_envelope_sha256} 时无后继 prevHash 可断链。本校验器
 * **重算** envelope 与存储值比对，检测**非自洽/部分篡改**——含链尾最新行。
 *
 * <p><b>威胁边界（必须明确，勿夸大）</b>：本校验器检测的是"改了 content/alias_set/locale/
 * toolchain 之一但**没同步重算** envelope"的篡改（即字段间不自洽）。它**防不住**有 DB 写权限的
 * 攻击者**同时**改 content + alias_set + source_envelope_sha256 + source_toolchain_id 使四者
 * 自洽——重算会"通过"。防全套自洽篡改需**外部锚定**：append-only 审计链、DB trigger+审计、
 * 行签名、或把 envelope/链尾写入外部 WORM 存储/不可变账本（属生产安全增强，见跨仓清单）。
 *
 * <p>用**创建时**的工具链身份（{@link PolicyVersion#sourceToolchainId}）重算，把"非自洽篡改"与
 * "引擎升级"区分开：toolchain 不变时 envelope 必须逐字节相等，否则即篡改；toolchain 缺失
 * （旧版本）则跳过（无法重算，留给哈希链覆盖）。
 */
@ApplicationScoped
public class PolicyVersionEnvelopeVerifier {

    /** 单版本校验结果。 */
    public record VerifyResult(Long versionId, Status status, String detail) {
        public enum Status {
            /** envelope 重算与存储一致——完整。 */
            INTACT,
            /** envelope 与重算不符——疑似篡改。 */
            TAMPERED,
            /** 无 envelope/toolchain（旧版本）——本校验器不覆盖，由哈希链负责。 */
            SKIPPED_LEGACY
        }
    }

    /**
     * 校验单个版本的 envelope 完整性。
     */
    public VerifyResult verify(PolicyVersion v) {
        if (v.sourceEnvelopeSha256 == null || v.sourceEnvelopeSha256.isBlank()
            || v.sourceToolchainId == null || v.sourceToolchainId.isBlank()) {
            return new VerifyResult(v.id, VerifyResult.Status.SKIPPED_LEGACY,
                "无 envelope/toolchain（旧版本），由哈希链覆盖");
        }
        String recomputed = PolicyVersion.computeSourceEnvelope(
            v.content, v.aliasSet, v.locale, v.sourceToolchainId, v.compileScopesJson());
        if (recomputed.equals(v.sourceEnvelopeSha256) || matchesPreResolverFormat(v)) {
            return new VerifyResult(v.id, VerifyResult.Status.INTACT, null);
        }
        return new VerifyResult(v.id, VerifyResult.Status.TAMPERED,
            "envelope 重算不符：content/alias_set/locale 可能被篡改");
    }

    /**
     * 尚未迁移（resolverVersion 为 NULL）的存量行保留 ADR 0051 之前的 4 段 envelope（ADR 0051 §9）：
     * 迁移失败被归档的行刻意不动旧产物，按旧格式重算才不会误报篡改。已迁移行只认 5 段。
     */
    private static boolean matchesPreResolverFormat(PolicyVersion v) {
        return v.resolverVersion == null && PolicyVersion.computeLegacySourceEnvelope(
            v.content, v.aliasSet, v.locale, v.sourceToolchainId).equals(v.sourceEnvelopeSha256);
    }

    /**
     * 校验某策略的全部版本，返回疑似篡改的版本结果（INTACT/SKIPPED 不返回）。
     */
    public List<VerifyResult> verifyTampered(String policyId) {
        List<PolicyVersion> versions = PolicyVersion.list("policyId", policyId);
        return versions.stream()
            .map(this::verify)
            .filter(r -> r.status() == VerifyResult.Status.TAMPERED)
            .toList();
    }
}
