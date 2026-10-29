package io.aster.policy.service;

import aster.core.lexicon.SemanticTokenKind;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 D 端到端集成测试（ADR 0022 §11，需真实 PG + Flyway V6.14.0）。
 *
 * <p>验证 service+DB 全链路：结构化 createVersion → 校验 → canonicalJson 冻结 →
 * 持久化 alias_set + source_envelope_sha256 → 读回 → 非法别名拒绝。覆盖 C1/C2 落库。
 */
@QuarkusTest
class PolicyVersionAliasIntegrationTest extends BasePolicyVersionServiceTest {

    private static final String SRC =
        "Module M.\n\nRule p given x as Int, produce Int:\n  Return x multiplied by 3.";

    @Test
    void createWithAliasSetPersistsCanonicalJsonAndEnvelope() {
        Map<SemanticTokenKind, List<String>> aliasSet =
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by"));

        PolicyVersion v = versionService.createVersion(
            catalog.id, SRC, "en-US", "manual", "owner", aliasSet, null);

        assertNotNull(v.id);
        // 读回 DB（脱离持久化上下文确认真落库）
        PolicyVersion reloaded = PolicyVersion.findById(v.id);
        assertNotNull(reloaded.aliasSet, "alias_set 应落库");
        assertTrue(reloaded.aliasSet.contains("TIMES") && reloaded.aliasSet.contains("multiplied by"));
        assertNotNull(reloaded.sourceEnvelopeSha256, "source_envelope_sha256 应落库");
        assertEquals(64, reloaded.sourceEnvelopeSha256.length());
        // chainLink 用 envelope（C1：进链）
        assertEquals(reloaded.sourceEnvelopeSha256, reloaded.chainLink());
    }

    @Test
    void persistedEnvelopeUsesFiveSegmentsAndVerifiesIntact() {
        // ADR 0051 §7：即使创建时带了词汇（只用于碰撞校验），api 版本按无词汇编译，第 5 段为空串；
        // 创建与校验取同一词汇段，落库后校验器必须判 INTACT。
        aster.core.identifier.VocabularyScopes scopes = io.aster.replay.core.ReplayExecutionCore.buildVocabularyScopes(
            Map.of("id", "t", "name", "t", "locale", "en-US", "version", "1.0.0",
                "structs", List.of(Map.of("canonical", "Driver", "localized", "Fahrer", "kind", "struct")),
                "fields", List.of(), "functions", List.of(), "enumValues", List.of(), "literals", List.of()));
        assertNotNull(scopes);
        PolicyVersion v = versionService.createVersion(catalog.id, SRC, "en-US", "manual", "owner",
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by")), scopes);
        PolicyVersion reloaded = PolicyVersion.findById(v.id);

        assertTrue(reloaded.sourceToolchainId.matches(
            "^abi=1\\.1;core=[^;]+;validator=[^;]+;resolver=1;casefold=15\\.1;build=[^;]+$"), reloaded.sourceToolchainId);
        assertEquals(PolicyVersion.computeSourceEnvelope(reloaded.content, reloaded.aliasSet, reloaded.locale,
            reloaded.sourceToolchainId, ""), reloaded.sourceEnvelopeSha256);
        assertEquals(PolicyVersionEnvelopeVerifier.VerifyResult.Status.INTACT,
            new PolicyVersionEnvelopeVerifier().verify(reloaded).status());
    }

    @Test
    void noAliasSetLeavesColumnsNull_backwardCompatible() {
        PolicyVersion v = versionService.createVersion(catalog.id, SRC.replace("multiplied by", "times"), "en-US");
        PolicyVersion reloaded = PolicyVersion.findById(v.id);
        assertNull(reloaded.aliasSet, "无别名 → alias_set NULL");
        // envelope 仍计算（覆盖 content+locale+toolchain），但 aliasSet 段为空
        assertNotNull(reloaded.sourceEnvelopeSha256);
    }

    @Test
    void rejectsSensitiveKindAliasAtCreate() {
        // H3：敏感 kind 别名在结构化入口被拒（社会工程防护，落库前拦截）
        Map<SemanticTokenKind, List<String>> bad =
            Map.of(SemanticTokenKind.RETURN, List.of("approve as"));
        assertThrows(IllegalArgumentException.class,
            () -> versionService.createVersion(catalog.id, SRC, "en-US", "manual", "owner", bad, null));
    }

    @Test
    void rejectsNonCanonicalJsonAtStringEntry() {
        // String 入口：非规范 JSON（key 未排序/未归一）被拒
        String nonCanonical = "{\"TIMES\":[\"Scaled By\"]}"; // 大写非规范
        assertThrows(IllegalArgumentException.class,
            () -> versionService.createVersion(catalog.id, SRC, "en-US", "manual", "owner", nonCanonical));
    }

    @Test
    void rollbackCarriesFrozenAliasSet() {
        // C2 真流程：v1 带别名(审批+激活) → v2 无别名(审批+激活，活跃切到 v2) → rollback 到 v1。
        // 断言 rollback 激活的是 v1 行（带其冻结别名）+ catalog 指针回退到 v1。
        Map<SemanticTokenKind, List<String>> aliasSet =
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by"));
        PolicyVersion v1 = versionService.createVersion(
            catalog.id, SRC, "en-US", "manual", "owner", aliasSet, null);
        // 显式 version 号避免同毫秒撞号（@PrePersist 用 epoch-milli）
        setVersionNumber(v1.id, 3001L);
        versionService.submitForApproval(v1.id, "owner");
        versionService.approveVersion(v1.id, "approver");
        versionService.activateVersion(v1.id, "approver");

        // v2：无别名，审批+激活 → 活跃切到 v2，catalog.defaultVersionId=v2
        PolicyVersion v2 = versionService.createVersion(
            catalog.id, SRC.replace("multiplied by", "times"), "en-US");
        setVersionNumber(v2.id, 3002L);
        versionService.submitForApproval(v2.id, "owner");
        versionService.approveVersion(v2.id, "approver");
        versionService.activateVersion(v2.id, "approver");
        // 前置：在独立事务读，避免把 catalog 实例放进 L1 缓存影响后续 fresh read
        assertEquals(v2.id, freshCatalogDefault(), "前置：激活 v2 后 catalog 指向 v2");

        // rollback 到 v1（激活已存行，不重编译/不读当前配置）
        PolicyVersion rolled = versionService.rollbackToVersion(v1.policyId, 3001L, "admin");
        assertEquals(v1.id, rolled.id, "回滚返回 version=3001 的 v1");

        // v1 行的 aliasSet 完好（端到端证明 rollback 携带冻结别名）。fresh read 避免 L1 缓存。
        PolicyVersion v1After = freshVersion(v1.id);
        assertNotNull(v1After.aliasSet, "rollback 后 v1 仍带其冻结 aliasSet");
        assertTrue(v1After.aliasSet.contains("multiplied by"));
        assertEquals(Boolean.TRUE, v1After.active, "v1 应重新激活");
        // catalog 指针回退到 v1（fresh tx 读，避免命中 L1 缓存里的旧 v2 指针）
        assertEquals(v1.id, freshCatalogDefault(), "rollback 后 catalog 指针应回退到 v1");
        // v2 已停用
        assertEquals(Boolean.FALSE, freshVersion(v2.id).active, "v2 应停用");
    }

    private Long freshCatalogDefault() {
        return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> io.aster.policy.entity.PolicyCatalog.<io.aster.policy.entity.PolicyCatalog>findById(catalog.id).defaultVersionId);
    }

    private PolicyVersion freshVersion(Long id) {
        return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> PolicyVersion.<PolicyVersion>findById(id));
    }

    /** 显式设 version 号（避免同毫秒撞号），独立 tx 提交。 */
    void setVersionNumber(Long versionPk, long versionNumber) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            PolicyVersion v = PolicyVersion.findById(versionPk);
            v.version = versionNumber;
            v.persist();
        });
    }
}
