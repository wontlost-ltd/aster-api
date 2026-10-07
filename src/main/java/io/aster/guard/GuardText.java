package io.aster.guard;

/** guard 包内共用的文本判定与截断。 */
final class GuardText {
  private GuardText() {
  }

  /** null 或只含空白视为未提供。 */
  static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** 超过 {@code max} 字符时只保留前 {@code max} 个字符；null 原样返回。 */
  static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
