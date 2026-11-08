package io.aster.guard.receipt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 执行收据（ADR 0040 §12 D1）：服务端对 ALLOW 决策签发的 Ed25519 签名凭证，执行方凭它本地验签后执行动作。
 *
 * <p>签名 = Ed25519 作用于 {@link Payload} 的 RFC 8785（JCS）规范化 UTF-8 字节串（不二次哈希），
 * base64url 无填充、64 字节。{@code kid} 在外层重复一次，供执行方先按 kid 选公钥再解析载荷；
 * 验签时外层 kid 必须与载荷内 kid 一致。
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ExecutionReceipt(String kid, Payload payload, String signature) {

  /**
   * 被签名的载荷，字段按字母序：{@code actionHash, decisionId, expiresAt, issuedAt, kid, outcome, policyVersionId,
   * tenantId, v}。时间为 ISO-8601 UTC 字符串；{@code expiresAt} 排他（有效当且仅当 {@code now < expiresAt}）。
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
