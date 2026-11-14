package io.aster.guard.reconcile;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Locale;

/**
 * 把执行方自报的 {@code executorId}（{@code <component>@<host>[:<inst>]}）或上报方 {@code source} 归一为低基数标签
 * （计划 D6）：取第一个 {@code @} 之前的部分并小写，在白名单内取原值，否则 {@code other}；整串为空白为 {@code unknown}。
 *
 * <p>账本类孤儿在 {@code INSERT … SELECT} 内归一，SQL 侧的 CASE 表达式（{@link GuardOrphanDetector}）必须与本类同语义，
 * 由检测器测试逐值锁定。
 */
@ApplicationScoped
public class ExecutorLabels {
  public static final String OTHER = "other";
  public static final String UNKNOWN = "unknown";

  private List<String> whitelist;

  /** CDI 客户端代理所需。 */
  protected ExecutorLabels() {
  }

  @Inject
  public ExecutorLabels(@ConfigProperty(name = "aster.guard.reconciliation.executor-labels") List<String> whitelist) {
    this.whitelist = whitelist.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
        .distinct().toList();
  }

  public String of(String executorIdOrSource) {
    if (executorIdOrSource == null || executorIdOrSource.isBlank()) {
      return UNKNOWN;
    }
    String lower = executorIdOrSource.toLowerCase(Locale.ROOT);
    int at = lower.indexOf('@');
    String component = at < 0 ? lower : lower.substring(0, at);
    return whitelist.contains(component) ? component : OTHER;
  }

  /** 归一后的白名单（小写、去空白、去重，保持配置顺序）。 */
  public List<String> whitelist() {
    return whitelist;
  }
}
