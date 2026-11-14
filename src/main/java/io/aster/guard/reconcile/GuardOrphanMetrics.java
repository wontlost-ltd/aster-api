package io.aster.guard.reconcile;

import io.aster.guard.reconcile.GuardReconciliationService.TenantOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Guard 对账指标（ADR 0054 §10.4，计划 D6）。名称按 #465 原文，不加 {@code aster.} 前缀：
 * <ul>
 *   <li>{@code guard.orphan.actions{tenant,executor,kind}}（MultiGauge）：当前 OPEN 孤儿数，只发非零序列；每个副本定时
 *       从库重读，各副本同值，告警取 {@code max}。{@code tenant} 至多 {@code metric-max-tenants} 个，其余合并为 {@code _other}。</li>
 *   <li>{@code guard.orphan.actions.detected{kind}}（Counter）：新孤儿数，只由检出它的副本累加，{@code sum} 正确。</li>
 *   <li>{@code guard.reconciliation.runs{outcome}}（Counter）：按租户计。</li>
 *   <li>{@code guard.reconciliation.last.success}（Gauge，秒）：本副本最近一次完整跑完租户循环的 epoch 秒，进程内值。</li>
 * </ul>
 */
@ApplicationScoped
public class GuardOrphanMetrics {
  static final String OPEN = "guard.orphan.actions";
  static final String DETECTED = "guard.orphan.actions.detected";
  static final String RUNS = "guard.reconciliation.runs";
  static final String LAST_SUCCESS = "guard.reconciliation.last.success";
  static final String OTHER_TENANT = "_other";
  static final String OPEN_COUNTS = "SELECT tenant_id, executor_label, kind, count(*) FROM guard_orphan_actions "
      + "WHERE status = 'OPEN' GROUP BY 1, 2, 3";

  /** 一条 OPEN 计数序列。 */
  public record OpenCount(String tenant, String executor, String kind, long count) {}

  private MeterRegistry registry;
  private EntityManager em;
  private int maxTenants;
  private AtomicLong lastSuccess;
  private MultiGauge open;

  /** CDI 客户端代理所需。 */
  protected GuardOrphanMetrics() {
  }

  @Inject
  public GuardOrphanMetrics(MeterRegistry registry, EntityManager em,
                            @ConfigProperty(name = "aster.guard.reconciliation.metric-max-tenants") int maxTenants) {
    this.registry = registry;
    this.em = em;
    this.maxTenants = maxTenants;
    this.lastSuccess = new AtomicLong();
    Gauge.builder(LAST_SUCCESS, lastSuccess, AtomicLong::doubleValue)
        .description("本副本最近一次完整跑完 Guard 对账租户循环的 epoch 秒")
        .baseUnit("seconds")
        .register(registry);
    this.open = MultiGauge.builder(OPEN)
        .description("当前 OPEN 的 Guard 孤儿动作数（只发非零序列）")
        .register(registry);
  }

  public void recordDetected(OrphanKind kind) {
    Counter.builder(DETECTED).description("Guard 对账新检出的孤儿动作数").tag("kind", kind.wire())
        .register(registry).increment();
  }

  public void recordRun(TenantOutcome outcome) {
    Counter.builder(RUNS).description("Guard 对账按租户的执行次数").tag("outcome", outcome.wire())
        .register(registry).increment();
  }

  public void markLoopSucceeded(Instant at) {
    lastSuccess.set(at.getEpochSecond());
  }

  /** 从库重读 OPEN 计数并覆盖 MultiGauge 的全部序列：已归零的序列随之消失。 */
  @Scheduled(every = "${aster.guard.reconciliation.metric-refresh}", identity = "guard-orphan-metrics",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
  public void refresh() {
    List<OpenCount> rows;
    try {
      rows = QuarkusTransaction.requiringNew().call(this::readOpenCounts);
    } catch (RuntimeException e) {
      Log.warnf(e, "Guard 孤儿指标刷新失败，沿用上一轮序列");
      return;
    }
    List<MultiGauge.Row<?>> gaugeRows = bound(rows, maxTenants).stream()
        .<MultiGauge.Row<?>>map(r -> MultiGauge.Row.of(
            Tags.of("tenant", r.tenant(), "executor", r.executor(), "kind", r.kind()), r.count()))
        .toList();
    open.register(gaugeRows, true);
  }

  private List<OpenCount> readOpenCounts() {
    List<?> result = em.createNativeQuery(OPEN_COUNTS).getResultList();
    List<OpenCount> rows = new ArrayList<>(result.size());
    for (Object o : result) {
      Object[] c = (Object[]) o;
      rows.add(new OpenCount((String) c[0], (String) c[1], (String) c[2], ((Number) c[3]).longValue()));
    }
    return rows;
  }

  /**
   * 限制 {@code tenant} 标签基数（纯函数）：按租户 OPEN 总数降序、同数按租户名升序取前 {@code maxTenants} 个租户，
   * 其余行按 {@code (executor, kind)} 累加到 {@code tenant="_other"}。
   */
  public static List<OpenCount> bound(List<OpenCount> rows, int maxTenants) {
    Map<String, Long> totals = rows.stream()
        .collect(Collectors.groupingBy(OpenCount::tenant, Collectors.summingLong(OpenCount::count)));
    Set<String> kept = new HashSet<>(totals.entrySet().stream()
        .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
            .thenComparing(Map.Entry::getKey))
        .limit(Math.max(maxTenants, 0)).map(Map.Entry::getKey).toList());
    List<OpenCount> out = new ArrayList<>();
    Map<String, Long> merged = new LinkedHashMap<>();
    for (OpenCount r : rows) {
      if (kept.contains(r.tenant())) {
        out.add(r);
      } else {
        merged.merge(r.executor() + "\u0000" + r.kind(), r.count(), Long::sum);
      }
    }
    merged.forEach((key, count) -> {
      String[] parts = key.split("\u0000", 2);
      out.add(new OpenCount(OTHER_TENANT, parts[0], parts[1], count));
    });
    return out;
  }
}
