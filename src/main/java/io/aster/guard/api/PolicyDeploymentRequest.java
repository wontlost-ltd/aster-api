package io.aster.guard.api;

import java.util.List;
import java.util.Map;

/**
 * {@code PUT /api/v1/guard/policy-deployments/{cloudPolicyId}} 的请求体（ADR 0054 §11 D1）。
 * 字段语义见 {@link io.aster.guard.deploy.PolicyDeployment}；{@code aliasSet / vocabulary} 可省略。
 */
public record PolicyDeploymentRequest(
    Long generation,
    String cloudVersionId,
    Integer cloudVersion,
    String moduleName,
    String functionName,
    String source,
    String locale,
    Map<String, List<String>> aliasSet,
    Map<String, Object> vocabulary,
    String sourceSha256) {}
