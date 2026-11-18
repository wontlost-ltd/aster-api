package io.aster.policy.export;

/** 导出在组装 zip 之前被拒绝（签名不可用、kid 未进锚、target 不可用）；{@link #code()} 为对外 503 错误码。 */
public final class PackageExportUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public PackageExportUnavailableException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
