package io.aster.policy.rest.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CNL 源码长度上限回归（算法复杂度 DoS 防护）。
 *
 * <p>背景：实测原始 CNL {@code source} 在解析/规范化上呈超线性耗时
 * （10KB≈1.5s，50KB >15s）。无上限时少量大 body 即可耗尽 worker 线程池。
 * 这里钉死：超过 {@link CnlSourceLimits#MAX_SOURCE_LENGTH} 的 source 在 bean
 * validation 层即被拒（快速 400），不进入昂贵解析；合法长度照常通过。
 */
class CnlSourceLimitsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void teardown() {
        if (factory != null) factory.close();
    }

    private static String sourceOfLength(int n) {
        // 合法的极小 CNL 前缀 + 填充，仅用于触发长度约束，不要求可解析。
        StringBuilder sb = new StringBuilder(n + 16);
        sb.append("Module m. ");
        while (sb.length() < n) sb.append('x');
        return sb.substring(0, n);
    }

    @Test
    void schemaRequestRejectsOversizedSource() {
        // 审计 #98 Medium 3：/schema 是匿名解析面，上限压到 MAX_ANON_SOURCE_LENGTH（16 KiB）。
        String tooBig = sourceOfLength(CnlSourceLimits.MAX_ANON_SOURCE_LENGTH + 1);
        SchemaRequest req = new SchemaRequest(tooBig, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "超长 source 必须触发约束违规");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("source")),
            "违规应落在 source 字段上");
    }

    @Test
    void schemaRequestAcceptsSourceAtLimit() {
        // 恰好等于匿名上限（16 KiB）应通过——SchemaRequest 走匿名限额而非 64 KiB 认证限额。
        String atLimit = sourceOfLength(CnlSourceLimits.MAX_ANON_SOURCE_LENGTH);
        SchemaRequest req = new SchemaRequest(atLimit, null, null);
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "恰好等于匿名上限的 source 应通过校验");
    }

    @Test
    void schemaRequestAcceptsTypicalSource() {
        SchemaRequest req = new SchemaRequest(
            "Module m.\n\nRule r given amount as Number, produce:\n  Return amount < 100.", null, null);
        var violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "典型小策略应通过校验");
    }

    @Test
    void sourcePolicyRequestRejectsOversizedSource() {
        String tooBig = sourceOfLength(CnlSourceLimits.MAX_SOURCE_LENGTH + 1);
        SourcePolicyRequest req = new SourcePolicyRequest(tooBig, java.util.Map.of(), null, null, null, null, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "超长 source 必须触发约束违规");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("source")),
            "违规应落在 source 字段上");
    }

    @Test
    void evaluationRequestRejectsTooManyContextElements() {
        Object[] tooMany = new Object[CnlSourceLimits.MAX_CONTEXT_ELEMENTS + 1];
        java.util.Arrays.fill(tooMany, 1);
        EvaluationRequest req = new EvaluationRequest("m", "f", tooMany, null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "超量 context 必须触发约束违规");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("context")),
            "违规应落在 context 字段上");
    }

    @Test
    void evaluationRequestAcceptsNormalContext() {
        EvaluationRequest req = new EvaluationRequest("m", "f", new Object[] { 1, "x", true }, null);
        assertTrue(validator.validate(req).isEmpty(), "正常 context 应通过校验");
    }

    @Test
    void jsonPolicyRequestRejectsOversizedPolicy() {
        StringBuilder sb = new StringBuilder(CnlSourceLimits.MAX_JSON_POLICY_LENGTH + 2);
        while (sb.length() <= CnlSourceLimits.MAX_JSON_POLICY_LENGTH) sb.append('x');
        JsonPolicyRequest req = new JsonPolicyRequest(sb.toString(), java.util.Map.of(), null);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "超长 policy 必须触发约束违规");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("policy")),
            "违规应落在 policy 字段上");
    }

    @Test
    void validationRequestRejectsOversizedIdentifiers() {
        String tooLong = "x".repeat(CnlSourceLimits.MAX_IDENTIFIER_LENGTH + 1);
        ValidationRequest req = new ValidationRequest(tooLong, tooLong);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "超长 module/function 名必须触发约束违规");
    }

    @Test
    void validationRequestAcceptsNormalIdentifiers() {
        ValidationRequest req = new ValidationRequest("aster.finance.loan", "evaluateLoanEligibility");
        assertTrue(validator.validate(req).isEmpty(), "正常 module/function 名应通过");
    }

    @Test
    void rollbackRequestRejectsOversizedReason() {
        String tooLong = "x".repeat(CnlSourceLimits.MAX_FREETEXT_LENGTH + 1);
        RollbackRequest req = new RollbackRequest(1L, tooLong);
        var violations = validator.validate(req);
        assertFalse(violations.isEmpty(), "超长 reason 必须触发约束违规");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("reason")),
            "违规应落在 reason 字段上");
    }
}
