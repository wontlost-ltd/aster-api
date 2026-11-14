package io.aster.guard.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.reconcile.SideEffectClassifier.Classification;
import io.aster.guard.reconcile.SideEffectClassifier.ReceiptSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 单租户孤儿检测（ADR 0054 §10.3）。全部方法要求调用方已开事务；不自开事务、不入链、不计指标。
 *
 * <p>账本类（收据四类与 {@code orphan_receipt}）各是一条 {@code INSERT … SELECT … ON CONFLICT DO NOTHING RETURNING}：
 * SELECT 以 {@code NOT EXISTS} 排除已记录的事实，已记录的行不占批次名额，超限时下一轮从未记录的行继续，不会饿死；
 * {@code executor_label} 在 SQL 内按 {@link ExecutorLabels} 同语义归一。「有效消耗」只看 {@code consume_audit_id}
 * （ADR 0041 §12 D1）。上报类逐条取收据快照，在 Java 侧由 {@link SideEffectClassifier} 分类后写回。
 */
@ApplicationScoped
public class GuardOrphanDetector {
  /** {@code executorId} 归一的 SQL 表达式（{@code %1$s} 为列名），与 {@link ExecutorLabels#of} 同语义。 */
  static final String LABEL_SQL = "CASE WHEN %1$s IS NULL OR %1$s !~ '\\S' THEN 'unknown' "
      + "WHEN split_part(lower(%1$s), '@', 1) = ANY(string_to_array(CAST(:labels AS varchar), ',')) "
      + "THEN split_part(lower(%1$s), '@', 1) ELSE 'other' END";
  private static final String INSERT_HEAD = "INSERT INTO guard_orphan_actions (tenant_id, kind, severity, subject_key, "
      + "decision_id, executor_label, executor_id, detail, first_run_id, detected_at) ";
  private static final String INSERT_TAIL = " ON CONFLICT (tenant_id, kind, subject_key) DO NOTHING "
      + "RETURNING id, kind, executor_label";
  /** 收据行上的账本类：{@code %1$s} kind、{@code %2$s} 额外 JOIN、{@code %3$s} 判定条件、{@code %4$s} detail 额外键。 */
  private static final String RECEIPT_LEDGER = INSERT_HEAD
      + "SELECT r.tenant_id, '%1$s', '%5$s', CAST(r.decision_id AS varchar), r.decision_id, "
      + LABEL_SQL.formatted("r.executor_id") + ", r.executor_id, "
      + "jsonb_strip_nulls(jsonb_build_object('decisionId', CAST(r.decision_id AS varchar), 'executorId', r.executor_id, "
      + "'attemptId', r.attempt_id, 'consumedAt', r.consumed_at, 'expiresAt', r.expires_at%4$s)), :runId, :now "
      + "FROM guard_receipts r %2$s "
      + "WHERE r.tenant_id = :t AND %3$s "
      + "AND NOT EXISTS (SELECT 1 FROM guard_orphan_actions o WHERE o.tenant_id = :t AND o.kind = '%1$s' "
      + "AND o.subject_key = CAST(r.decision_id AS varchar)) "
      + "ORDER BY r.issued_at, r.decision_id LIMIT :limit" + INSERT_TAIL;
  private static final String CONSUMED_WITHOUT_RESULT = "r.consume_audit_id IS NOT NULL AND r.consumed_at >= :since "
      + "AND r.consumed_at < :cutoff AND r.result_status IS NULL";
  private static final String ALLOW_UNCONSUMED = "r.consume_audit_id IS NULL AND r.expires_at < :cutoff "
      + "AND r.issued_at >= :since";
  /** 审批转 ALLOW 的决策 outcome 仍是 REQUIRE_APPROVAL，须由 APPROVED 审批放行；无审批行时 a.status 为 NULL，须先 COALESCE。 */
  private static final String CONSUME_ON_NON_ALLOW = "r.consume_audit_id IS NOT NULL AND r.issued_at >= :since "
      + "AND (d.id IS NULL OR d.tenant_id <> r.tenant_id "
      + "OR NOT (d.outcome = 'ALLOW' OR COALESCE(a.status, '') = 'APPROVED'))";
  private static final String NON_ALLOW_JOIN = "LEFT JOIN guard_decisions d ON d.id = r.decision_id "
      + "LEFT JOIN guard_approvals a ON a.decision_id = r.decision_id";
  private static final String NON_ALLOW_DETAIL = ", 'outcome', d.outcome, 'approvalStatus', a.status";
  private static final String RESULT_WITHOUT_CONSUME = "r.result_status IS NOT NULL AND r.consume_audit_id IS NULL "
      + "AND r.issued_at >= :since";
  /** 规范 UUID 形态才转 uuid，其余（截断或手工构造的值）按 NULL 处理而不是让整条 SQL 失败。 */
  private static final String LOG_DECISION_UUID = "CAST(CASE WHEN l.decision_id ~ "
      + "'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' "
      + "THEN l.decision_id END AS uuid)";
  /**
   * {@code orphan_receipt}（#522）：链上有决策事件、本租户无对应决策行。只看 {@code [since, cutoff)} 内的事件——
   * 宽限之内的事件可能仍在「先入链、后落库」的两步之间。
   */
  static final String ORPHAN_RECEIPT = INSERT_HEAD
      + "SELECT l.tenant_id, 'orphan_receipt', 'info', l.decision_id, " + LOG_DECISION_UUID + ", 'unknown', NULL, "
      + "jsonb_build_object('decisionId', l.decision_id, 'auditId', l.id, 'eventAt', l.timestamp), :runId, :now "
      + "FROM audit_logs l "
      + "WHERE l.tenant_id = :t AND l.event_type = 'ACTION_GUARD_DECISION' AND l.timestamp >= :since "
      + "AND l.timestamp < :cutoff AND l.decision_id IS NOT NULL "
      + "AND NOT EXISTS (SELECT 1 FROM guard_decisions d WHERE d.tenant_id = :t AND d.id = " + LOG_DECISION_UUID + ") "
      + "AND NOT EXISTS (SELECT 1 FROM guard_orphan_actions o WHERE o.tenant_id = :t AND o.kind = 'orphan_receipt' "
      + "AND o.subject_key = l.decision_id) "
      + "ORDER BY l.id LIMIT :limit" + INSERT_TAIL;
  /** 待对账上报 + 收据快照；earlier 为同一上报方对同一决策是否已有已匹配上报（不论先后，后到的才是重放）。 */
  static final String PENDING_SIDE_EFFECTS = "SELECT s.id, s.source, s.effect_id, s.occurred_at, s.action_name, "
      + "s.resource_type, s.resource_id, s.decision_id, s.attempt_id, s.action_hash, "
      + "d.id IS NOT NULL, r.decision_id IS NOT NULL, r.consume_audit_id, r.attempt_id, r.action_hash, "
      + "EXISTS (SELECT 1 FROM guard_side_effects p WHERE p.tenant_id = s.tenant_id AND p.decision_id = s.decision_id "
      + "AND p.source = s.source AND p.id <> s.id AND p.reconcile_outcome = 'matched') "
      + "FROM guard_side_effects s "
      + "LEFT JOIN guard_decisions d ON d.id = s.decision_id AND d.tenant_id = s.tenant_id "
      + "LEFT JOIN guard_receipts r ON r.decision_id = d.id AND r.tenant_id = s.tenant_id "
      + "WHERE s.tenant_id = :t AND s.reconciled_at IS NULL ORDER BY s.id LIMIT :limit";
  static final String INSERT_SIDE_EFFECT_ORPHAN = INSERT_HEAD
      + "VALUES (:t, :kind, :severity, :subject, CAST(CAST(:decisionId AS varchar) AS uuid), :label, NULL, "
      + "CAST(:detail AS jsonb), :runId, :now)" + INSERT_TAIL.replace("RETURNING id, kind, executor_label", "RETURNING id");
  static final String LINK_SIDE_EFFECT = "UPDATE guard_orphan_actions SET side_effect_id = :sideEffectId WHERE id = :id";
  static final String MARK_SIDE_EFFECT = "UPDATE guard_side_effects SET reconciled_at = :now, reconcile_outcome = :outcome "
      + "WHERE id = :id";
  static final String RESOLVE_LATE_RESULT = "UPDATE guard_orphan_actions o SET status = 'RESOLVED', "
      + "resolution = 'late_result', resolved_at = :now FROM guard_receipts r "
      + "WHERE o.tenant_id = :t AND o.status = 'OPEN' AND o.kind = 'consumed_without_result' "
      + "AND r.decision_id = o.decision_id AND r.tenant_id = o.tenant_id AND r.result_status IS NOT NULL";
  static final String RESOLVE_AGED_OUT = "UPDATE guard_orphan_actions SET status = 'RESOLVED', resolution = 'aged_out', "
      + "resolved_at = :now WHERE tenant_id = :t AND status = 'OPEN' "
      + "AND kind IN ('allow_unconsumed', 'orphan_receipt') AND detected_at < :ageCutoff";

  /** 扫描窗口：账本类按 {@code [since, cutoff)}；{@code now} 是本轮的逻辑时刻（写入 detected_at 等）。 */
  public record Window(Instant now, Instant since, Instant cutoff) {
    public static Window of(Instant now, Duration lookback, Duration grace) {
      return new Window(now, now.minus(lookback), now.minus(grace));
    }
  }

  public record Detected(long orphanId, OrphanKind kind, String executorLabel) {}

  public record SideEffectBatch(int checked, List<Detected> detected) {}

  @Inject
  EntityManager em;

  @Inject
  ExecutorLabels labels;

  @Inject
  ObjectMapper mapper;

  /** 账本四类与 {@code orphan_receipt}，每类至多 {@code batchLimit} 条新孤儿。 */
  public List<Detected> detectLedger(String tenantId, Window w, long runId, int batchLimit) {
    List<Detected> out = new ArrayList<>();
    out.addAll(receiptLedger(OrphanKind.CONSUMED_WITHOUT_RESULT, "", CONSUMED_WITHOUT_RESULT, "", tenantId, w, runId,
        batchLimit));
    out.addAll(receiptLedger(OrphanKind.ALLOW_UNCONSUMED, "", ALLOW_UNCONSUMED, "", tenantId, w, runId, batchLimit));
    out.addAll(receiptLedger(OrphanKind.CONSUME_ON_NON_ALLOW, NON_ALLOW_JOIN, CONSUME_ON_NON_ALLOW, NON_ALLOW_DETAIL,
        tenantId, w, runId, batchLimit));
    out.addAll(receiptLedger(OrphanKind.RESULT_WITHOUT_CONSUME, "", RESULT_WITHOUT_CONSUME, "", tenantId, w, runId,
        batchLimit));
    out.addAll(returning(bind(ORPHAN_RECEIPT, tenantId, w, runId, batchLimit).getResultList()));
    return out;
  }

  private List<Detected> receiptLedger(OrphanKind kind, String join, String where, String detail, String tenantId,
                                       Window w, long runId, int batchLimit) {
    String sql = RECEIPT_LEDGER.formatted(kind.wire(), join, where, detail, kind.severity());
    return returning(bind(sql, tenantId, w, runId, batchLimit).getResultList());
  }

  /** 只绑定 SQL 实际引用的参数：Hibernate 对未引用的命名参数直接报错，而各类别用到的窗口端点不同。 */
  private Query bind(String sql, String tenantId, Window w, long runId, int batchLimit) {
    Query q = em.createNativeQuery(sql).setParameter("t", tenantId).setParameter("runId", runId)
        .setParameter("now", w.now()).setParameter("limit", batchLimit);
    if (sql.contains(":since")) {
      q.setParameter("since", w.since());
    }
    if (sql.contains(":cutoff")) {
      q.setParameter("cutoff", w.cutoff());
    }
    if (sql.contains(":labels")) {
      q.setParameter("labels", String.join(",", labels.whitelist()));
    }
    return q;
  }

  private static List<Detected> returning(List<?> rows) {
    List<Detected> out = new ArrayList<>(rows.size());
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      out.add(new Detected(((Number) cols[0]).longValue(), OrphanKind.fromWire((String) cols[1]).orElseThrow(),
          (String) cols[2]));
    }
    return out;
  }

  /**
   * 处理至多 {@code batchLimit} 条待对账上报（按 id 升序）：逐条分类，孤儿入表，每条写回 {@code reconciled_at} 与结论。
   * 同批内前一条判 matched 后，同一 {@code (decisionId, source)} 的后一条即判重放。
   */
  public SideEffectBatch detectSideEffects(String tenantId, Window w, long runId, int batchLimit) {
    List<?> rows = em.createNativeQuery(PENDING_SIDE_EFFECTS).setParameter("t", tenantId)
        .setParameter("limit", batchLimit).getResultList();
    Set<String> matchedInBatch = new HashSet<>();
    List<Detected> detected = new ArrayList<>();
    for (Object row : rows) {
      Object[] c = (Object[]) row;
      GuardSideEffectEntity e = sideEffectOf(c);
      ReceiptSnapshot snapshot = new ReceiptSnapshot(Boolean.TRUE.equals(c[10]), Boolean.TRUE.equals(c[11]),
          c[12] == null ? null : ((Number) c[12]).longValue(), (String) c[13], (String) c[14]);
      String key = e.decisionId + "|" + e.source;
      boolean earlier = Boolean.TRUE.equals(c[15]) || matchedInBatch.contains(key);
      Classification cls = SideEffectClassifier.classify(e, snapshot, earlier);
      if (cls.kind().isEmpty()) {
        matchedInBatch.add(key);
      } else {
        recordSideEffectOrphan(tenantId, w, runId, e, cls).ifPresent(detected::add);
      }
      em.createNativeQuery(MARK_SIDE_EFFECT).setParameter("now", w.now())
          .setParameter("outcome", cls.kind().map(OrphanKind::wire).orElse(SideEffectClassifier.MATCHED))
          .setParameter("id", e.id).executeUpdate();
    }
    return new SideEffectBatch(rows.size(), detected);
  }

  private Optional<Detected> recordSideEffectOrphan(String tenantId, Window w, long runId,
                                                             GuardSideEffectEntity e, Classification cls) {
    OrphanKind kind = cls.kind().orElseThrow();
    String label = labels.of(e.source);
    List<?> ids = em.createNativeQuery(INSERT_SIDE_EFFECT_ORPHAN).setParameter("t", tenantId)
        .setParameter("kind", kind.wire()).setParameter("severity", kind.severity())
        .setParameter("subject", "se:" + e.id)
        .setParameter("decisionId", e.decisionId == null ? null : e.decisionId.toString())
        .setParameter("label", label).setParameter("detail", detailOf(e, cls.reason()))
        .setParameter("runId", runId).setParameter("now", w.now()).getResultList();
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    long orphanId = ((Number) ids.get(0)).longValue();
    em.createNativeQuery(LINK_SIDE_EFFECT).setParameter("sideEffectId", e.id).setParameter("id", orphanId)
        .executeUpdate();
    return Optional.of(new Detected(orphanId, kind, label));
  }

  private static GuardSideEffectEntity sideEffectOf(Object[] c) {
    GuardSideEffectEntity e = new GuardSideEffectEntity();
    e.id = ((Number) c[0]).longValue();
    e.source = (String) c[1];
    e.effectId = (String) c[2];
    e.occurredAt = instantOf(c[3]);
    e.actionName = (String) c[4];
    e.resourceType = (String) c[5];
    e.resourceId = (String) c[6];
    e.decisionId = c[7] == null ? null : c[7] instanceof UUID u ? u : UUID.fromString(c[7].toString());
    e.attemptId = (String) c[8];
    e.actionHash = (String) c[9];
    return e;
  }

  private static Instant instantOf(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Instant i) {
      return i;
    }
    if (v instanceof OffsetDateTime o) {
      return o.toInstant();
    }
    if (v instanceof java.sql.Timestamp ts) {
      return ts.toInstant();
    }
    return OffsetDateTime.parse(v.toString()).withOffsetSameInstant(ZoneOffset.UTC).toInstant();
  }

  /** 孤儿 detail 复制上报的关键字段：上报行被保留期清理后孤儿仍自描述。 */
  private String detailOf(GuardSideEffectEntity e, String reason) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("effectId", e.effectId);
    m.put("source", e.source);
    m.put("actionName", e.actionName);
    putIfNotNull(m, "resourceType", e.resourceType);
    putIfNotNull(m, "resourceId", e.resourceId);
    putIfNotNull(m, "occurredAt", e.occurredAt == null ? null : e.occurredAt.toString());
    putIfNotNull(m, "decisionId", e.decisionId == null ? null : e.decisionId.toString());
    putIfNotNull(m, "attemptId", e.attemptId);
    putIfNotNull(m, "actionHash", e.actionHash);
    m.put("reason", reason);
    try {
      return mapper.writeValueAsString(m);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("孤儿 detail 序列化失败", ex);
    }
  }

  private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
    if (value != null) {
      m.put(key, value);
    }
  }

  /**
   * 自动解除：{@code consumed_without_result} 补到结果 → {@code late_result}；info 类（{@code allow_unconsumed}、
   * {@code orphan_receipt}）检出满 {@code ttl} → {@code aged_out}。{@code side_effect_*} 等 critical 类从不自动解除。
   *
   * @return 解除行数
   */
  public int autoResolve(String tenantId, Window w, Duration allowUnconsumedTtl) {
    int late = em.createNativeQuery(RESOLVE_LATE_RESULT).setParameter("t", tenantId).setParameter("now", w.now())
        .executeUpdate();
    int aged = em.createNativeQuery(RESOLVE_AGED_OUT).setParameter("t", tenantId).setParameter("now", w.now())
        .setParameter("ageCutoff", w.now().minus(allowUnconsumedTtl)).executeUpdate();
    return late + aged;
  }

  public int countOpen(String tenantId) {
    return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM guard_orphan_actions WHERE tenant_id = :t AND status = 'OPEN'")
        .setParameter("t", tenantId).getSingleResult()).intValue();
  }
}
