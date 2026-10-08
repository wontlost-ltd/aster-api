package io.aster.guard;

import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.governance.VerdictView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次 guard 决策在写收据、落库之前的完整事实：谁在哪个策略版本上对什么动作得到了什么结论。
 *
 * <p>收据事件、决策行、待审批行与响应体都只从这里取值，保证四者一致。
 *
 * @param action     {@link GuardActionMapper#flatten} 产出的扁平 Action（键固定）
 * @param outcome    {@code ALLOW | DENY | REQUIRE_APPROVAL | ESCALATE}
 * @param role       Verdict 的 role（REQUIRE_APPROVAL 时即所需审批角色）
 * @param reasonCode fail-closed 原因；Verdict 正常返回时为 null
 * @param agent      调用方自报的模型身份（已校验归一）；请求未带 agent 时为 null
 */
public record GuardDecisionDraft(
    String tenantId,
    String policyModule,
    String policyFunction,
    String idempotencyKey,
    Map<String, Object> action,
    String contextHash,
    PolicyVersionView policyVersion,
    String outcome,
    String role,
    String reason,
    String ruleId,
    List<String> controls,
    GuardReasonCode reasonCode,
    AgentIdentity agent) {

  static final String ALLOW = "ALLOW";
  static final String DENY = "DENY";
  static final String REQUIRE_APPROVAL = "REQUIRE_APPROVAL";
  static final String ESCALATE = "ESCALATE";
  static final String PENDING = "PENDING";
  /** guard 认可的 Verdict 结论；其余取值一律按非 Verdict 拒绝。 */
  static final Set<String> VERDICT_OUTCOMES = Set.of(ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE);

  public GuardDecisionDraft {
    action = action == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(action));
    controls = controls == null ? List.of() : List.copyOf(controls);
  }

  /** 补上评估前解析到的活跃策略版本（幂等查重在版本解析之前完成）。 */
  public GuardDecisionDraft withPolicyVersion(PolicyVersionView version) {
    return new GuardDecisionDraft(tenantId, policyModule, policyFunction, idempotencyKey, action, contextHash,
        version, outcome, role, reason, ruleId, controls, reasonCode, agent);
  }

  /** 记录被执行规则的身份（{@code @id} / {@code @control}），与结论无关。 */
  public GuardDecisionDraft withRule(String newRuleId, List<String> newControls) {
    return new GuardDecisionDraft(tenantId, policyModule, policyFunction, idempotencyKey, action, contextHash,
        policyVersion, outcome, role, reason, newRuleId, newControls, reasonCode, agent);
  }

  /** 采纳规则返回的 Verdict 结论。 */
  public GuardDecisionDraft withVerdict(VerdictView verdict) {
    return new GuardDecisionDraft(tenantId, policyModule, policyFunction, idempotencyKey, action, contextHash,
        policyVersion, verdict.outcome(), verdict.role(), verdict.reason(), ruleId, controls, null, agent);
  }

  /** fail-closed：改判 DENY 并记录原因；保留 ruleId / controls 以便追溯被评估的规则，清掉不再成立的 role / reason。 */
  public GuardDecisionDraft deny(GuardReasonCode code) {
    return new GuardDecisionDraft(tenantId, policyModule, policyFunction, idempotencyKey, action, contextHash,
        policyVersion, DENY, null, null, ruleId, controls, code, agent);
  }

  /** REQUIRE_APPROVAL / ESCALATE 需要人工复核。 */
  public boolean needsApproval() {
    return REQUIRE_APPROVAL.equals(outcome) || ESCALATE.equals(outcome);
  }

  /** Agent 视角的结论：待审批统一为 PENDING。 */
  public String effectiveOutcome() {
    return needsApproval() ? PENDING : outcome;
  }

  /** 审批所需角色：只有 REQUIRE_APPROVAL 指定角色；ESCALATE 任意复核人。 */
  public String requiredRole() {
    return REQUIRE_APPROVAL.equals(outcome) ? role : null;
  }

  public boolean allowed() {
    return ALLOW.equals(outcome);
  }

  public String principalId() {
    return text("principal_id");
  }

  public String principalType() {
    return text("principal_type");
  }

  /** {@link GuardActionMapper#flatten} 已校验并冻结的 principal.roles；缺失时为空列表。 */
  public List<String> principalRoles() {
    return action.get("principal_roles") instanceof List<?> roles
        ? roles.stream().map(String::valueOf).toList()
        : List.of();
  }

  public String actionName() {
    return text("action");
  }

  public String resourceType() {
    return text("resource_type");
  }

  public String resourceId() {
    return text("resource_id");
  }

  public String reasonCodeValue() {
    return reasonCode == null ? null : reasonCode.code();
  }

  private String text(String key) {
    Object v = action.get(key);
    return v == null ? "" : v.toString();
  }
}
