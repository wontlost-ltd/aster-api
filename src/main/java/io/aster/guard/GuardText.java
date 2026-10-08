package io.aster.guard;

/** guard 包内共用的文本判定与截断。 */
final class GuardText {
  /** 与 {@code RequestIdentityResolver} 无用户时的兜底值一致。 */
  static final String ANONYMOUS_USER = "anonymous";

  private GuardText() {
  }

  /** null 或只含空白视为未提供。 */
  static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * 签名内的用户缺失：空白或身份解析层的兜底值 {@code anonymous}（ADR 0042 §4.2）。
   * 开决策与审批都要求真实用户，否则请求方 / 审批人无法参与四眼比对。
   */
  static boolean isAnonymous(String user) {
    return isBlank(user) || ANONYMOUS_USER.equals(user);
  }

  /** 超过 {@code max} 字符时只保留前 {@code max} 个字符；null 原样返回。 */
  static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
