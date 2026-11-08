package io.aster.guard.provenance;

import io.aster.guard.GuardInvalidActionException;

/**
 * 签名事实片段验签失败（ADR 0040 §11 D3），REST 层映射为 400 {@code { "error": "invalid_fact", "issuer", "kid", "reason" }}。
 *
 * <p>事实是 {@code action} 的一部分，所以继承 {@link GuardInvalidActionException}：未单独映射本异常的调用点
 * 仍按 400 {@code invalid_action} 拒绝，不会漏成 500 或被当作合法请求。{@code issuer} / {@code kid} 取自请求原文，
 * 无法确定（{@code facts} 不是数组、条数超限、事实不是对象）时为 null。
 */
public class GuardInvalidFactException extends GuardInvalidActionException {
  private static final long serialVersionUID = 1L;

  /** 结构不合法：缺字段、类型不符、未知字段、时间无法解析、claims 为空、facts 不是数组。 */
  public static final String MALFORMED = "malformed";
  public static final String TOO_MANY_FACTS = "too_many_facts";
  public static final String TOO_MANY_CLAIMS = "too_many_claims";
  public static final String PATH_TOO_LONG = "path_too_long";
  /** {@code sources} 的值不是字符串或超过 64 字符。 */
  public static final String INVALID_SOURCE = "invalid_source";
  /** 载荷含非有限数或 |x| &gt; 2^53−1 的数值（与 {@code jcs-sha256/v1} 共享向量同名）。 */
  public static final String UNSAFE_INTEGER = "unsafe_integer";
  /** 载荷字符串含孤立代理项（与共享向量同名）。 */
  public static final String INVALID_STRING = "invalid_string";
  public static final String UNSUPPORTED_ALG = "unsupported_alg";
  /** 本租户签发方表中没有该 {@code (issuer, kid)}。 */
  public static final String UNKNOWN_ISSUER = "unknown_issuer";
  public static final String ISSUER_DISABLED = "issuer_disabled";
  /** 签发方行已过 {@code not_after}（{@code now >= notAfter}）。 */
  public static final String ISSUER_EXPIRED = "issuer_expired";
  public static final String AUDIENCE_MISMATCH = "audience_mismatch";
  /** {@code expiresAt <= issuedAt}。 */
  public static final String INVALID_WINDOW = "invalid_window";
  public static final String WINDOW_TOO_LONG = "window_too_long";
  /** {@code now < issuedAt − 60s}。 */
  public static final String NOT_YET_VALID = "not_yet_valid";
  /** {@code now >= expiresAt}（排他）。 */
  public static final String EXPIRED = "expired";
  public static final String BAD_SIGNATURE = "bad_signature";
  public static final String CLAIM_PATH_ABSENT = "claim_path_absent";
  public static final String CLAIM_MISMATCH = "claim_mismatch";
  /** {@code sources} 键集与 {@code claims} 键集不相等。 */
  public static final String SOURCES_MISMATCH = "sources_mismatch";

  private final String issuer;
  private final String kid;
  private final String reason;

  public GuardInvalidFactException(String issuer, String kid, String reason, String message) {
    super(message);
    this.issuer = issuer;
    this.kid = kid;
    this.reason = reason;
  }

  public String issuer() {
    return issuer;
  }

  public String kid() {
    return kid;
  }

  public String reason() {
    return reason;
  }
}
