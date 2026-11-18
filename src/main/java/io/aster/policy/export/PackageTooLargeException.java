package io.aster.policy.export;

/** 组装出的 zip 超过上限（ADR 0059 修订 3：1 MiB），端点据此给出 E734。 */
public final class PackageTooLargeException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final long size;
  private final long limit;

  public PackageTooLargeException(long size, long limit) {
    super("package size " + size + " exceeds " + limit);
    this.size = size;
    this.limit = limit;
  }

  public long size() {
    return size;
  }

  public long limit() {
    return limit;
  }
}
