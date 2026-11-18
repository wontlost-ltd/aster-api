package io.aster.policy.export;

import java.util.List;

/** 入口确定不了（多 Rule 且无 {@code @entry}、或指定的入口不存在）；端点映射为 400 {@code entry_required}（修订 3 P2-R9）。 */
public final class EntryRequiredException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final transient List<String> candidates;

  public EntryRequiredException(List<String> candidates) {
    super("entry_required");
    this.candidates = List.copyOf(candidates);
  }

  public List<String> candidates() {
    return candidates;
  }
}
