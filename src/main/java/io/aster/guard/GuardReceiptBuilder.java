package io.aster.guard;

import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 构造 Action Guard 的审计收据事件（ADR 0040 §5.2）。
 *
 * <p>metadata 序列化必须 ≤ 4096 字节：追加器超限会截断成非法 JSON。因此每个字符串值都按上界截断，
 * controls 限条数与单条长度——请求侧文本已由 {@link GuardActionMapper} 限为 255 字符，
 * 这里的截断兜住规则侧（reason / ruleId / controls）与调用方身份等不受请求校验约束的值。
 * 明文 context 绝不进入收据，只写 contextHash。
 */
@ApplicationScoped
public class GuardReceiptBuilder {
  static final int MAX_TEXT = 255;
  static final int MAX_CONTROLS = 16;
  static final int MAX_CONTROL_LENGTH = 64;
  static final int MAX_COMMENT = 1000;
  /** 与 audit_logs.performed_by 列宽一致，超长会让整条收据写入失败。 */
  static final int MAX_PERFORMED_BY = 100;
  static final String SYSTEM_ACTOR = "SYSTEM";

  /** 决策事件：success 仅在 outcome 为 ALLOW 时为 true；reason 经追加器脱敏后写入 audit_logs.reason。 */
  public AuditEvent decisionEvent(GuardDecisionDraft draft, UUID decisionId, UUID approvalId, String performedBy) {
    Map<String, Object> m = new LinkedHashMap<>();
    put(m, "decisionId", decisionId);
    put(m, "outcome", draft.outcome());
    put(m, "effectiveOutcome", draft.effectiveOutcome());
    put(m, "reasonCode", draft.reasonCodeValue());
    put(m, "reason", clip(draft.reason(), MAX_TEXT));
    put(m, "ruleId", clip(draft.ruleId(), MAX_TEXT));
    put(m, "controls", controls(draft.controls()));
    put(m, "principalId", clip(draft.principalId(), MAX_TEXT));
    put(m, "principalType", clip(draft.principalType(), MAX_TEXT));
    put(m, "actionName", clip(draft.actionName(), MAX_TEXT));
    put(m, "resourceType", clip(draft.resourceType(), MAX_TEXT));
    put(m, "resourceId", clip(draft.resourceId(), MAX_TEXT));
    put(m, "contextHash", draft.contextHash());
    put(m, "agentModel", clip(draft.agentModel(), MAX_TEXT));
    put(m, "policyVersionId", draft.policyVersion() == null ? null : draft.policyVersion().id());
    put(m, "sourceHash", draft.policyVersion() == null ? null : clip(draft.policyVersion().sourceHash(), MAX_TEXT));
    put(m, "approvalId", approvalId);
    put(m, "idempotencyKey", clip(draft.idempotencyKey(), MAX_TEXT));
    Long version = draft.policyVersion() == null ? null : draft.policyVersion().version();
    return new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), draft.tenantId(),
        draft.policyModule(), draft.policyFunction(), null, null, version,
        clip(performedBy, MAX_PERFORMED_BY), draft.allowed(), null, draft.reasonCodeValue(),
        Collections.unmodifiableMap(m), null, null, null, null);
  }

  /**
   * 审批事件：读取审批行的<b>目标状态</b>（status / decidedBy / comment），调用方须先把这些字段
   * 设为本次审批结论再构造事件；decisionReceiptHash 指回决策收据，把两条事件串起来。
   * 无 decidedBy（如过期）时由 {@code SYSTEM} 发起；success 仅在 APPROVED 时为 true。
   */
  public AuditEvent approvalEvent(GuardApprovalEntity a, String decisionReceiptHash) {
    Map<String, Object> m = new LinkedHashMap<>();
    put(m, "decisionId", a.decisionId);
    put(m, "approvalId", a.id);
    put(m, "outcome", a.status == null ? null : a.status.name());
    put(m, "decidedBy", clip(a.decidedBy, MAX_TEXT));
    put(m, "requiredRole", clip(a.requiredRole, MAX_TEXT));
    put(m, "comment", clip(a.comment, MAX_COMMENT));
    put(m, "decisionReceiptHash", decisionReceiptHash);
    String actor = a.decidedBy == null ? SYSTEM_ACTOR : clip(a.decidedBy, MAX_PERFORMED_BY);
    return new AuditEvent(EventType.ACTION_GUARD_APPROVAL, Instant.now(), a.tenantId,
        null, null, null, null, null, actor, a.status == GuardApprovalStatus.APPROVED, null, null,
        Collections.unmodifiableMap(m), null, null, null, null);
  }

  /** null 不写入：追加器本也丢弃顶层 null，这里提前省掉，测试与线上看到同一份键集。 */
  private static void put(Map<String, Object> m, String key, Object value) {
    if (value != null) {
      m.put(key, value instanceof UUID u ? u.toString() : value);
    }
  }

  private static List<String> controls(List<String> controls) {
    if (controls == null || controls.isEmpty()) {
      return null;
    }
    return controls.stream().limit(MAX_CONTROLS).map(c -> clip(c, MAX_CONTROL_LENGTH)).toList();
  }

  private static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
