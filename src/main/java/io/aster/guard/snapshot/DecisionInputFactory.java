package io.aster.guard.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.api.GuardActionRequest;
import io.aster.policy.governance.InputClassification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 构造待审批决策的输入快照行（ADR 0042 §12 D1 / D2）。
 *
 * <p>{@code action_json} 是请求 {@code action} 去掉 {@code facts} 后的 JSON：签名事实已在决策收据里（来源判定），
 * 签名本身不是给审批人看的输入。用独立的普通 {@link ObjectMapper} 序列化，保留 context 中的 null 叶子，
 * 不受全局 Jackson 配置影响，读取时据此重算 {@code context_hash} 复核完整性。
 */
public final class DecisionInputFactory {
  private static final ObjectMapper PLAIN = new ObjectMapper();

  private DecisionInputFactory() {
  }

  /**
   * {@code POST /api/v1/guard/decisions}：请求的 action 就是被评估的输入（{@code direct}）；标记来自决策所用版本的 IR，
   * 推导不出（null）时为 {@code none}。
   */
  public static GuardDecisionInputEntity direct(UUID decisionId, String tenantId, GuardActionRequest.Action action,
                                                String contextHash, InputClassification classification) {
    return row(decisionId, tenantId, action, contextHash, classification == null ? null : classification.toJson(),
        classification == null ? GuardDecisionInputEntity.MARKS_NONE : GuardDecisionInputEntity.MARKS_VERSION,
        GuardDecisionInputEntity.BINDING_DIRECT);
  }

  /**
   * from-evidence：分类行缺失 → {@code unbound / none}；分类行 {@code input_hash} 为 null（位置格式）→ {@code unbound}；
   * 否则 {@code evaluated}（调用方已核对哈希一致）。有分类时标记来源为 {@code evaluation}，否则 {@code none}。
   */
  public static GuardDecisionInputEntity fromEvidence(UUID decisionId, String tenantId, GuardActionRequest.Action action,
                                                      String contextHash,
                                                      Optional<GuardEvaluationClassificationEntity> row) {
    String classification = row.map(r -> r.classification).orElse(null);
    boolean bound = row.map(r -> r.inputHash != null).orElse(false);
    return row(decisionId, tenantId, action, contextHash, classification,
        classification == null ? GuardDecisionInputEntity.MARKS_NONE : GuardDecisionInputEntity.MARKS_EVALUATION,
        bound ? GuardDecisionInputEntity.BINDING_EVALUATED : GuardDecisionInputEntity.BINDING_UNBOUND);
  }

  /** 去掉 facts 的 action JSON（键序与请求记录一致）。 */
  static String actionJson(GuardActionRequest.Action action) {
    ObjectNode tree = PLAIN.valueToTree(action);
    tree.remove("facts");
    return tree.toString();
  }

  private static GuardDecisionInputEntity row(UUID decisionId, String tenantId, GuardActionRequest.Action action,
                                              String contextHash, String classification, String marksSource,
                                              String inputBinding) {
    GuardDecisionInputEntity e = new GuardDecisionInputEntity();
    e.decisionId = decisionId;
    e.tenantId = tenantId;
    e.actionJson = actionJson(action);
    e.contextHash = contextHash;
    e.classification = classification;
    e.marksSource = marksSource;
    e.inputBinding = inputBinding;
    e.createdAt = Instant.now();
    return e;
  }
}
