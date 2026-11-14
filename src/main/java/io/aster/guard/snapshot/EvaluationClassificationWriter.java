package io.aster.guard.snapshot;

import io.aster.guard.GuardActionMapper;
import io.aster.policy.governance.InputClassification;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 可审批评估的分类行写入（ADR 0042 §12 D1）：三条 {@code /evaluate*} 路径在评估事件发布之后调用。
 *
 * <p>只为非 simulate、结论 ∈ {REQUIRE_APPROVAL, ESCALATE} 的评估写。{@code input_hash} 只对命名格式（JSON 对象）
 * 输入计算，与 guard 决策的 {@code context_hash} 同一配方（{@link GuardActionMapper#contextHash}）；位置格式为 null，
 * 分类也随之为 null（无从按参数取路径）。
 *
 * <p>分类行不是评估结果的一部分：任何失败只记 WARN 与 counter，不上抛；缺行只会让 from-evidence 降级为
 * {@code unbound / none}（fail-closed）。在 IO 线程上调用时转到 worker 池执行，不阻塞事件循环。
 */
@ApplicationScoped
public class EvaluationClassificationWriter {
  static final String WRITE_FAILED = "aster.guard.classification.write.failed";
  private static final Set<String> APPROVABLE = Set.of("REQUIRE_APPROVAL", "ESCALATE");
  private static final Logger LOG = Logger.getLogger(EvaluationClassificationWriter.class);

  private ClassificationRowStore store;
  private GuardActionMapper mapper;
  private Counter writeFailed;

  /** CDI 客户端代理所需。 */
  protected EvaluationClassificationWriter() {
  }

  @Inject
  public EvaluationClassificationWriter(ClassificationRowStore store, GuardActionMapper mapper, MeterRegistry registry) {
    this.store = store;
    this.mapper = mapper;
    this.writeFailed = Counter.builder(WRITE_FAILED)
        .description("评估分类行写入失败或同键冲突的次数（ADR 0042 §12 D1）")
        .register(registry);
  }

  /**
   * @param requestContext 请求的 {@code context} 原值（命名格式为 Map，位置格式为数组 / 列表）
   * @param outcome        评估结论（Verdict outcome），非 Verdict 为 null
   * @param classification 本次评估执行 IR 推导的分类；null 表示推导失败或超上限
   */
  public void record(String tenantId, String correlationId, Object requestContext, String outcome,
                     InputClassification classification, boolean simulate) {
    if (simulate || correlationId == null || outcome == null || !APPROVABLE.contains(outcome)) {
      return;
    }
    if (Context.isOnEventLoopThread()) {
      try {
        Infrastructure.getDefaultWorkerPool().execute(
            () -> write(tenantId, correlationId, requestContext, classification));
      } catch (RuntimeException rejected) {
        writeFailed.increment();
        LOG.warnf("评估分类行写入未能调度，from-evidence 将按 unbound 处理: tenant=%s correlationId=%s: %s",
            tenantId, correlationId, rejected.toString());
      }
      return;
    }
    write(tenantId, correlationId, requestContext, classification);
  }

  public Optional<GuardEvaluationClassificationEntity> find(String tenantId, String correlationId) {
    return store.find(tenantId, correlationId);
  }

  private void write(String tenantId, String correlationId, Object requestContext,
                     InputClassification classification) {
    try {
      GuardEvaluationClassificationEntity row = new GuardEvaluationClassificationEntity();
      row.tenantId = tenantId;
      row.correlationId = correlationId;
      row.createdAt = Instant.now();
      if (requestContext instanceof Map<?, ?> named) {
        row.inputHash = mapper.contextHash(asStringMap(named));
        row.classification = classification == null ? null : classification.toJson();
      }
      if (!store.insertIfAbsent(row)) {
        writeFailed.increment();
        LOG.warnf("评估分类行已存在，保留先写者: tenant=%s correlationId=%s", tenantId, correlationId);
      }
    } catch (RuntimeException e) {
      writeFailed.increment();
      LOG.warnf("评估分类行写入失败，from-evidence 将按 unbound 处理: tenant=%s correlationId=%s: %s",
          tenantId, correlationId, e.toString());
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asStringMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }
}
