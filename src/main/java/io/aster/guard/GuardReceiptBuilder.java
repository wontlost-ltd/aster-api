package io.aster.guard;

import io.aster.audit.chain.AuditMetadataSize;
import io.aster.guard.api.ResultRequest;
import io.aster.guard.provenance.InputSources;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.evidence.EvaluationEvidence;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.aster.guard.GuardText.clip;

/**
 * 构造 Action Guard 的审计收据事件（ADR 0040 §5.2）。
 *
 * <p>metadata 序列化必须 ≤ 4096 字节：追加器超限会截断成非法 JSON。因此每个字符串值都按上界截断，
 * controls 与 principalRoles 限条数与单条长度——请求侧文本已由 {@link GuardActionMapper} 限为 255 字符，
 * 这里的截断兜住规则侧（reason / ruleId / controls）与调用方身份等不受请求校验约束的值。
 * 明文 context 绝不进入收据，只写 contextHash。
 *
 * <p>截断之后仍可能超限：追加器先 PII 脱敏（如邮箱 {@code a@b.cc} 变为 {@code ***@***.***}，会变长；
 * 身份键与 UUID 形态的值豁免）再做 JSON 序列化（{@code "} 与 {@code \} 转义后长度翻倍），最后按字符数比较。
 * 因此决策事件与审批事件在构造后都按与追加器相同的方式（脱敏 + 序列化）测量，超过 {@link #METADATA_BUDGET}
 * 就按固定优先级缩短自由文本，见 {@link #fitBudget}。
 */
@ApplicationScoped
public class GuardReceiptBuilder {
  static final int MAX_TEXT = 255;
  /** 免脱敏的摘要键按 SHA-256 hex 长度截断，防止调用方把长文本借豁免原样写入链。 */
  static final int DIGEST_LENGTH = 64;
  static final int MAX_CONTROLS = 16;
  static final int MAX_CONTROL_LENGTH = 64;
  static final int MAX_COMMENT = 1000;
  /** 与 audit_logs.performed_by 列宽一致，超长会让整条收据写入失败。 */
  static final int MAX_PERFORMED_BY = 100;
  public static final String SYSTEM_ACTOR = "SYSTEM";
  /** 与 guard_fact_issuers.kid 列宽一致。 */
  static final int MAX_KID = 64;
  /** 吊销事件逐条列出的受影响决策上限（ADR 0040 §11 D4）。 */
  static final int MAX_AFFECTED_DECISIONS = 64;
  /** metadata 序列化长度预算：低于追加器的 4096 上限，留出余量（与评估事件共用 {@link AuditMetadataSize#BUDGET}）。 */
  static final int METADATA_BUDGET = AuditMetadataSize.BUDGET;
  /** 输入来源键（ADR 0041 §11 D1）：verified 路径、签发方、签发端来源、档案来源门拒绝时的未验证路径。 */
  static final String KEY_VERIFIED_FIELDS = "verifiedFields";
  static final String KEY_FACT_ISSUERS = "factIssuers";
  static final String KEY_FACT_SOURCES = "factSources";
  static final String KEY_UNVERIFIED_FIELDS = "unverifiedFields";
  static final String KEY_INPUT_SOURCES_TRUNCATED = "inputSourcesTruncated";
  /** 四个来源列表任一被缩短（含超过条数上限被截断）即写 {@code inputSourcesTruncated: true}；该键本身永不删除。 */
  static final Set<String> INPUT_SOURCE_LISTS =
      Set.of(KEY_VERIFIED_FIELDS, KEY_FACT_ISSUERS, KEY_FACT_SOURCES, KEY_UNVERIFIED_FIELDS);
  /** verifiedFields / factSources / unverifiedFields 至多 64 项、factIssuers 至多 8 项（ADR 0041 §11 D1）。 */
  static final int MAX_INPUT_SOURCE_ITEMS = 64;
  static final int MAX_FACT_ISSUERS = 8;
  /**
   * 决策事件超出预算时的缩短顺序（ADR 0041 §11 D1）：先缩对追溯价值最低、体积最大的字段，最后才动「谁对什么做了什么」。
   * reason（规则说明）→ agentSession → agentVersion → principalRoles → factIssuers → verifiedFields → factSources
   * → unverifiedFields → agentModel → agentProvider → principalType → resourceType → resourceId → actionName
   * → principalId；文本每轮缩到不超过一半，列表每轮删掉最后一项，直到达标或该字段为空，再处理下一个。
   * 身份与完整性字段（decisionId、approvalId、contextHash、outcome、effectiveOutcome、reasonCode、
   * ruleId、policyVersionId、sourceHash、idempotencyKey）以及 principalIdentityMatched、factExpiresAt、
   * inputSourcesTruncated 永不改动；自由文本全部清空仍超限时，最后才删除 controls。
   */
  static final List<String> SHRINK_ORDER = List.of("reason", EvaluationEvidence.KEY_AGENT_SESSION,
      EvaluationEvidence.KEY_AGENT_VERSION, "principalRoles", KEY_FACT_ISSUERS, KEY_VERIFIED_FIELDS, KEY_FACT_SOURCES,
      KEY_UNVERIFIED_FIELDS, EvaluationEvidence.KEY_AGENT_MODEL, EvaluationEvidence.KEY_AGENT_PROVIDER,
      "principalType", "resourceType", "resourceId", "actionName", "principalId");
  /**
   * 审批事件唯一可缩短的键：comment 是审批人自由填写的理由；decidedBy、requiredRole 是审批的身份事实，
   * 与 decisionId、approvalId、outcome、decisionReceiptHash 一样永不改动。按现有列宽（comment ≤ 1000、
   * 其余 ≤ 255）最坏转义约 3.3 KB，本不会触发；走同一预算是为了列宽日后放宽时不会退回非法 JSON 截断。
   */
  static final List<String> APPROVAL_SHRINK_ORDER = List.of("comment");
  /** 执行结果事件唯一可缩短的键：errorCode 是执行方自填文本，其余为身份与摘要，永不改动。 */
  static final List<String> RESULT_SHRINK_ORDER = List.of("errorCode");

  /** 决策事件：success 仅在 outcome 为 ALLOW 时为 true；reason 经追加器脱敏后写入 audit_logs.reason。 */
  public AuditEvent decisionEvent(GuardDecisionDraft draft, UUID decisionId, UUID approvalId, String performedBy) {
    return decisionEvent(draft, decisionId, approvalId, performedBy, null);
  }

  /**
   * 同上，并以 {@code evidenceCorrelationId} 记下锚定的评估事件相关键（ADR 0042 §3.1 from-evidence 决策），
   * 使锚定关系落在链上；为 null 时不写该键。
   */
  public AuditEvent decisionEvent(GuardDecisionDraft draft, UUID decisionId, UUID approvalId, String performedBy,
                                  String evidenceCorrelationId) {
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
    put(m, "principalRoles", roles(draft.principalRoles()));
    put(m, "actionName", clip(draft.actionName(), MAX_TEXT));
    put(m, "resourceType", clip(draft.resourceType(), MAX_TEXT));
    put(m, "resourceId", clip(draft.resourceId(), MAX_TEXT));
    put(m, "contextHash", draft.contextHash());
    putAgent(m, draft.agent());
    put(m, "policyVersionId", draft.policyVersion() == null ? null : draft.policyVersion().id());
    put(m, "sourceHash", draft.policyVersion() == null ? null : clip(draft.policyVersion().sourceHash(), MAX_TEXT));
    put(m, "approvalId", approvalId);
    put(m, "idempotencyKey", clip(draft.idempotencyKey(), MAX_TEXT));
    put(m, "evidenceCorrelationId", evidenceCorrelationId);
    putInputSources(m, draft.inputSources());
    putList(m, KEY_UNVERIFIED_FIELDS, draft.unverifiedFields(), MAX_INPUT_SOURCE_ITEMS);
    Long version = draft.policyVersion() == null ? null : draft.policyVersion().version();
    return new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), draft.tenantId(),
        draft.policyModule(), draft.policyFunction(), null, null, version,
        clip(performedBy, MAX_PERFORMED_BY), draft.allowed(), null, draft.reasonCodeValue(),
        Collections.unmodifiableMap(fitBudget(m, SHRINK_ORDER)), null, null, null, null);
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
    put(m, "expiredReason", a.expiredReason);
    String actor = a.decidedBy == null ? SYSTEM_ACTOR : clip(a.decidedBy, MAX_PERFORMED_BY);
    return new AuditEvent(EventType.ACTION_GUARD_APPROVAL, Instant.now(), a.tenantId,
        null, null, null, null, null, actor, a.status == GuardApprovalStatus.APPROVED, null, null,
        Collections.unmodifiableMap(fitBudget(m, APPROVAL_SHRINK_ORDER)), null, null, null, null);
  }

  /**
   * 消耗尝试事件（ADR 0040 §12 D1）：先于判定入链，是权威消耗点。attemptId / executorId 为执行方自报，
   * 只截断不校验；只投影 decision_id 列，不写 correlationId。
   */
  public AuditEvent consumeAttemptEvent(GuardReceiptEntity r, String actionHash, String attemptId,
                                        String executorId, String decisionReceiptHash, String performedBy) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", "attempt");
    put(m, "decisionId", r.decisionId);
    put(m, "actionHash", clip(actionHash, DIGEST_LENGTH));
    put(m, "attemptId", clip(attemptId, MAX_TEXT));
    put(m, "executorId", clip(executorId, MAX_TEXT));
    put(m, "kid", clip(r.kid, MAX_TEXT));
    put(m, "receiptIssuedAt", r.issuedAt == null ? null : r.issuedAt.toString());
    put(m, "receiptExpiresAt", r.expiresAt == null ? null : r.expiresAt.toString());
    put(m, "decisionReceiptHash", clip(decisionReceiptHash, DIGEST_LENGTH));
    return receiptEvent(EventType.ACTION_GUARD_CONSUME_ATTEMPT, r.tenantId, performedBy, true, m, List.of());
  }

  /** 消耗判定事件：outcome 为 {@code consumed} 或 {@code rejected:<code>}；attemptReceiptHash 指回尝试事件。 */
  public AuditEvent consumeOutcomeEvent(String tenantId, UUID decisionId, String attemptId, String executorId,
                                        String outcome, String attemptReceiptHash, String performedBy) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", "outcome");
    put(m, "decisionId", decisionId);
    put(m, "attemptId", clip(attemptId, MAX_TEXT));
    put(m, "executorId", clip(executorId, MAX_TEXT));
    put(m, "outcome", clip(outcome, MAX_TEXT));
    put(m, "attemptReceiptHash", clip(attemptReceiptHash, DIGEST_LENGTH));
    return receiptEvent(EventType.ACTION_GUARD_CONSUME_OUTCOME, tenantId, performedBy, "consumed".equals(outcome), m,
        List.of());
  }

  /** 执行结果事件：success 仅在 status 为 executed 时为 true；唯一可缩短的键是调用方自填的 errorCode。 */
  public AuditEvent resultEvent(String tenantId, UUID decisionId, ResultRequest req, String consumeReceiptHash,
                                String performedBy) {
    Map<String, Object> m = new LinkedHashMap<>();
    put(m, "decisionId", decisionId);
    put(m, "attemptId", clip(req.attemptId(), MAX_TEXT));
    put(m, "executorId", clip(req.executorId(), MAX_TEXT));
    put(m, "status", clip(req.status(), MAX_TEXT));
    put(m, "finishedAt", clip(req.finishedAt(), MAX_TEXT));
    put(m, "resultHash", clip(req.resultHash(), DIGEST_LENGTH));
    put(m, "errorCode", clip(req.errorCode(), MAX_TEXT));
    put(m, "consumeReceiptHash", clip(consumeReceiptHash, DIGEST_LENGTH));
    return receiptEvent(EventType.ACTION_GUARD_RESULT, tenantId, performedBy, "executed".equals(req.status()), m,
        RESULT_SHRINK_ORDER);
  }

  /**
   * 签发方吊销事件（ADR 0040 §11 D4，ADR 0041 §11）：写在租户链上，一条吊销对应多条决策，因此不写 decisionId
   * （decision_id 列不投影）。{@code affectedDecisionIds} 为本次实际转 EXPIRED 的审批所属决策，至多
   * {@link #MAX_AFFECTED_DECISIONS} 项；超出时另写总数 {@code affectedCount} 与 {@code affectedTruncated: true}。
   * issuer / kid 按列宽截断（255 / 64），64 个 UUID 加上限长的 issuer 仍在预算内，不需要缩短顺序。
   *
   * @param performedBy 操作者；{@code not_after} 到期由调度发起时为 {@code SYSTEM}
   */
  public AuditEvent issuerRevokedEvent(String tenantId, String issuer, String kid, String performedBy,
                                       List<UUID> affectedDecisionIds) {
    Map<String, Object> m = new LinkedHashMap<>();
    put(m, "issuer", clip(issuer, MAX_TEXT));
    put(m, "kid", clip(kid, MAX_KID));
    put(m, "performedBy", clip(performedBy, MAX_PERFORMED_BY));
    m.put("affectedDecisionIds", affectedDecisionIds.stream().limit(MAX_AFFECTED_DECISIONS).map(UUID::toString).toList());
    if (affectedDecisionIds.size() > MAX_AFFECTED_DECISIONS) {
      m.put("affectedCount", affectedDecisionIds.size());
      m.put("affectedTruncated", Boolean.TRUE);
    }
    return receiptEvent(EventType.GUARD_FACT_ISSUER_REVOKED, tenantId, performedBy, true, m, List.of());
  }

  private AuditEvent receiptEvent(EventType type, String tenantId, String performedBy, boolean success,
                                  Map<String, Object> m, List<String> shrinkOrder) {
    Objects.requireNonNull(tenantId, "tenantId");
    return new AuditEvent(type, Instant.now(), tenantId, null, null, null, null, null,
        clip(performedBy, MAX_PERFORMED_BY), success, null, null,
        Collections.unmodifiableMap(fitBudget(m, shrinkOrder)), null, null, null, null);
  }

  /**
   * 输入来源键（ADR 0041 §11 D1），null / 空不写：全 declared 且未做身份判定的决策与引入来源标记前的收据键集相同。
   * 列表已是字典序（{@link InputSources}），超过条数上限时截去末项并置 {@code inputSourcesTruncated}。
   */
  private static void putInputSources(Map<String, Object> m, InputSources s) {
    putList(m, KEY_VERIFIED_FIELDS, List.copyOf(s.verified()), MAX_INPUT_SOURCE_ITEMS);
    putList(m, KEY_FACT_ISSUERS, List.copyOf(s.factIssuers()), MAX_FACT_ISSUERS);
    putList(m, KEY_FACT_SOURCES, s.factSourceEntries(), MAX_INPUT_SOURCE_ITEMS);
    put(m, "principalIdentityMatched", s.principalIdentityMatched());
    put(m, "factExpiresAt", s.factExpiresAt() == null ? null : s.factExpiresAt().toString());
  }

  private static void putList(Map<String, Object> m, String key, List<String> values, int max) {
    if (values.isEmpty()) {
      return;
    }
    m.put(key, values.size() > max ? List.copyOf(values.subList(0, max)) : values);
    if (values.size() > max) {
      m.put(KEY_INPUT_SOURCES_TRUNCATED, Boolean.TRUE);
    }
  }

  /**
   * 按给定顺序缩短自由文本直到测量长度 ≤ {@link #METADATA_BUDGET}；仍超限则删除 controls（审批事件没有该键）。
   * 缩短任一来源列表时同时写 {@code inputSourcesTruncated: true}（首次缩短即置，之后的测量已计入该键）。
   */
  Map<String, Object> fitBudget(Map<String, Object> m, List<String> shrinkOrder) {
    for (String key : shrinkOrder) {
      while (measure(m) > METADATA_BUDGET && shrinkable(m.get(key))) {
        m.put(key, shrink(m.get(key)));
        if (INPUT_SOURCE_LISTS.contains(key)) {
          m.put(KEY_INPUT_SOURCES_TRUNCATED, Boolean.TRUE);
        }
      }
    }
    if (measure(m) > METADATA_BUDGET) {
      m.remove("controls");
    }
    return m;
  }

  /** 与追加器一致的测量，见 {@link AuditMetadataSize#measure}。 */
  int measure(Map<String, Object> m) {
    return AuditMetadataSize.measure(m);
  }

  private static boolean shrinkable(Object v) {
    return v instanceof String s && !s.isEmpty() || v instanceof List<?> l && !l.isEmpty();
  }

  /** 文本缩到一半；列表删掉最后一项（保留前面的项原文，不截断单项）。 */
  private static Object shrink(Object v) {
    if (v instanceof List<?> l) {
      return List.copyOf(l.subList(0, l.size() - 1));
    }
    return half((String) v);
  }

  /** 缩到一半，不切开代理对。 */
  private static String half(String s) {
    int cut = s.length() / 2;
    if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) {
      cut--;
    }
    return s.substring(0, cut);
  }

  /**
   * 模型身份键（ADR 0041 §3 / §8.1）：顶层 agentModel 始终保留——无 agent 时写空串，与引入结构化身份前
   * 的收据形态一致；其余四键只在有 agent 时写入。
   */
  private static void putAgent(Map<String, Object> m, AgentIdentity agent) {
    if (agent == null) {
      m.put(EvaluationEvidence.KEY_AGENT_MODEL, "");
      return;
    }
    put(m, EvaluationEvidence.KEY_AGENT_PROVIDER, clip(agent.provider(), MAX_TEXT));
    put(m, EvaluationEvidence.KEY_AGENT_MODEL, clip(agent.model(), MAX_TEXT));
    put(m, EvaluationEvidence.KEY_AGENT_VERSION, clip(agent.version(), MAX_TEXT));
    put(m, EvaluationEvidence.KEY_AGENT_SESSION, clip(agent.session(), MAX_TEXT));
    m.put(EvaluationEvidence.KEY_AGENT_SOURCE, EvaluationEvidence.SOURCE_DECLARED);
  }

  /** null 不写入：追加器本也丢弃顶层 null，这里提前省掉，测试与线上看到同一份键集。 */
  private static void put(Map<String, Object> m, String key, Object value) {
    if (value != null) {
      m.put(key, value instanceof UUID u ? u.toString() : value);
    }
  }

  /** roles 已由 {@link GuardActionMapper} 限为 ≤ 16 项、每项 ≤ 255 字符；这里同样设上界，空列表不写。 */
  private static List<String> roles(List<String> roles) {
    if (roles.isEmpty()) {
      return null;
    }
    return roles.stream().limit(GuardActionMapper.MAX_ROLES).map(r -> clip(r, MAX_TEXT)).toList();
  }

  private static List<String> controls(List<String> controls) {
    if (controls == null || controls.isEmpty()) {
      return null;
    }
    return controls.stream().limit(MAX_CONTROLS).map(c -> clip(c, MAX_CONTROL_LENGTH)).toList();
  }
}
