package io.aster.guard.receipt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 执行收据（ADR 0040 §12 D1）：服务端对 ALLOW 决策签发的 Ed25519 签名凭证，执行方凭它本地验签后执行动作。
 *
 * <p>签名 = Ed25519 作用于 {@link Payload} 的 RFC 8785（JCS）规范化 UTF-8 字节串（不二次哈希），
 * base64url 无填充、64 字节。{@code kid} 在外层重复一次，供执行方先按 kid 选公钥再解析载荷；
 * 验签时外层 kid 必须与载荷内 kid 一致。
 *
 * <p>{@code revision} 是签发时的密钥配置修订号（ADR 0040 §12 D6），在外层、<b>不受签名覆盖</b>：它只是执行方
 * 强制刷新 {@code /keys} 的提示（大于本地缓存即刷新），不参与任何放行判定。V6.35.0 之后、本字段之前存储的收据没有它。
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ExecutionReceipt(String kid, Payload payload, Long revision, String signature) {

  /**
   * 被签名的载荷，字段按字母序：{@code actionHash, decisionId, expiresAt, issuedAt, kid, outcome, policyVersionId,
   * tenantId, v}。时间为 ISO-8601 UTC 字符串；{@code expiresAt} 排他（有效当且仅当 {@code now < expiresAt}）。
   * {@code policyVersionId} 可选：决策没有已保存的策略版本时（aster-cloud {@code evaluateSource} 锚定的 from-evidence
   * 决策）为 null，按 NON_EMPTY 不写出、不进入签名字节；此时收据仍以 actionHash / decisionId / tenantId / 时间窗绑定。
   */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record Payload(String actionHash,
                        String decisionId,
                        String expiresAt,
                        String issuedAt,
                        String kid,
                        String outcome,
                        Long policyVersionId,
                        String tenantId,
                        Integer v) {
  }
}
