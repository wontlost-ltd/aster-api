package io.aster.guard.bypass;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存副作用账本：业务侧视角的「动作真的执行了」。
 *
 * <p>只有两条路径会写它：参考执行方在 consume 200 之后，以及攻击者原语在 consume 200 之后
 * （模拟「拿到 200 就执行」的恶意执行方）。因此「账本里没有某决策的条目」等价于「服务端从未对该决策给出 consume 200」，
 * 不是同义反复。线程安全：并发 consume 用例从多个线程写入。
 */
public final class SideEffectLedger {
  /** 一次已发生的副作用。 */
  public record Entry(String decisionId, String attemptId, String effect) {}

  private final List<Entry> entries = new CopyOnWriteArrayList<>();

  public void record(String decisionId, String attemptId, String effect) {
    entries.add(new Entry(decisionId, attemptId, effect));
  }

  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  public List<Entry> forDecision(String decisionId) {
    return entries.stream().filter(e -> e.decisionId().equals(decisionId)).toList();
  }
}
