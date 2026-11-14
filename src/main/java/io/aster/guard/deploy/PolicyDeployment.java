package io.aster.guard.deploy;

import java.util.List;
import java.util.Map;

/**
 * 一次 cloud 策略部署的请求（ADR 0054 §11 D1）：cloud 把某策略的默认版本连同冻结别名与冻结词汇推给 aster-api。
 *
 * @param cloudPolicyId  cloud 策略 id（路径参数），即目录的 {@code external_ref}
 * @param generation     cloud 为该策略预留的部署代际；更小的代际按 {@code stale_generation} 拒绝
 * @param cloudVersionId cloud 版本 id，即版本的 {@code external_ref}
 * @param cloudVersion   cloud 版本号（只用于审计与回显）
 * @param moduleName     源码 {@code Module} 头
 * @param functionName   cloud {@code /execute} 实际执行的入口规则
 * @param source         策略源码（CNL）
 * @param locale         源码语言
 * @param aliasSet       冻结别名集（kind 名 → 短语），可为 null
 * @param vocabulary     冻结领域词汇（DomainVocabulary），可为 null
 * @param sourceSha256   cloud 对源码 UTF-8 字节的 sha256 小写 hex；服务端自算比对
 */
public record PolicyDeployment(
    String cloudPolicyId,
    long generation,
    String cloudVersionId,
    int cloudVersion,
    String moduleName,
    String functionName,
    String source,
    String locale,
    Map<String, List<String>> aliasSet,
    Map<String, Object> vocabulary,
    String sourceSha256) {

  /** 部署 / 撤回的结果。 */
  public sealed interface DeployResult permits Deployed, Withdrawn, Rejected {}

  /**
   * 部署成功。
   *
   * @param created 本次是否新建了版本行（false = 同 cloud 版本的重放或切回旧版本）
   */
  public record Deployed(long policyVersionId, long version, String sourceHash, boolean created) implements DeployResult {}

  /** 撤回成功（含已撤回的幂等重放）。 */
  public record Withdrawn() implements DeployResult {}

  /**
   * 拒绝：{@code status} 为 HTTP 状态码，{@code error} 为错误码，{@code detail} 为附加字段（可为 null）。
   * 码表：400 {@code invalid_deployment}；404（无此部署，空体）；409 {@code stale_generation | generation_conflict | address_conflict |
   * deployment_mismatch | concurrent_activation}；422 {@code compile_failed | function_not_found | vocabulary_invalid |
   * stability_blocked}。
   */
  public record Rejected(int status, String error, String message, Object detail) implements DeployResult {}
}
