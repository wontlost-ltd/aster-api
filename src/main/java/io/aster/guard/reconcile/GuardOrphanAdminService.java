package io.aster.guard.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.GuardReceiptBuilder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 对账摘要、孤儿列表与管理员确认（ADR 0054 §10.2）。只读方法各自开只读事务；确认在一个事务里
 * 「孤儿行 {@code FOR UPDATE} → 本事务内追加 {@code GUARD_ORPHAN_ACKNOWLEDGED}（链锁）→ 写状态」，与对账同一锁顺序（行锁 → 链锁）。
 * 调用方不得在外层事务中调用。
 */
@ApplicationScoped
public class GuardOrphanAdminService {
  static final String OPEN_BY_KIND = "SELECT kind, count(*) FROM guard_orphan_actions WHERE tenant_id = :t "
      + "AND status = 'OPEN' GROUP BY kind";

  /** 列表过滤条件；{@code before} 为 null 表示从最新开始。 */
  public record ListQuery(String status, OrphanKind kind, int limit, Long before) {}

  /** 一页孤儿与下一页游标（无更多时为 null）。 */
  public record Page(List<GuardOrphanActionEntity> items, Long next) {}

  public record Summary(Optional<GuardReconciliationRunEntity> lastRun, Map<String, Long> open, long openTotal) {}

  /** 确认结果。 */
  public sealed interface AckOutcome {
    record Acknowledged(GuardOrphanActionEntity orphan) implements AckOutcome {}

    record NotFound() implements AckOutcome {}

    record NotOpen(String status) implements AckOutcome {}
  }

  @Inject EntityManager em;
  @Inject AuditChainAppender appender;
  @Inject GuardReceiptBuilder receiptBuilder;
  @Inject ObjectMapper mapper;
  @Inject Clock clock;

  public Summary summary(String tenantId) {
    return QuarkusTransaction.requiringNew().call(() -> {
      Map<String, Long> open = new TreeMap<>();
      for (Object row : em.createNativeQuery(OPEN_BY_KIND).setParameter("t", tenantId).getResultList()) {
        Object[] c = (Object[]) row;
        open.put((String) c[0], ((Number) c[1]).longValue());
      }
      long total = open.values().stream().mapToLong(Long::longValue).sum();
      return new Summary(GuardReconciliationRunEntity.latest(tenantId), open, total);
    });
  }

  public Page list(String tenantId, ListQuery q) {
    return QuarkusTransaction.requiringNew().call(() -> {
      StringBuilder where = new StringBuilder("tenantId = :t and status = :status");
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("t", tenantId);
      params.put("status", q.status());
      if (q.kind() != null) {
        where.append(" and kind = :kind");
        params.put("kind", q.kind().wire());
      }
      if (q.before() != null) {
        where.append(" and id < :before");
        params.put("before", q.before());
      }
      List<GuardOrphanActionEntity> rows = GuardOrphanActionEntity
          .<GuardOrphanActionEntity>find(where + " order by id desc", params).page(0, q.limit() + 1).list();
      boolean more = rows.size() > q.limit();
      List<GuardOrphanActionEntity> items = more ? rows.subList(0, q.limit()) : rows;
      return new Page(List.copyOf(items), more ? items.get(items.size() - 1).id : null);
    });
  }

  public AckOutcome acknowledge(String tenantId, long orphanId, String comment, String performedBy) {
    return QuarkusTransaction.requiringNew().call(() -> {
      Optional<GuardOrphanActionEntity> locked = GuardOrphanActionEntity
          .<GuardOrphanActionEntity>find("id = ?1 and tenantId = ?2", orphanId, tenantId)
          .withLock(LockModeType.PESSIMISTIC_WRITE).firstResultOptional();
      if (locked.isEmpty()) {
        return new AckOutcome.NotFound();
      }
      GuardOrphanActionEntity row = locked.get();
      if (!GuardOrphanActionEntity.OPEN.equals(row.status)) {
        return new AckOutcome.NotOpen(row.status);
      }
      long auditId = appender.appendInCurrentTransaction(receiptBuilder.orphanAcknowledgedEvent(tenantId, row.id,
          row.kind, row.decisionId, row.sideEffectId, comment, performedBy)).auditId();
      row.status = GuardOrphanActionEntity.ACKNOWLEDGED;
      row.resolution = GuardOrphanActionEntity.RESOLUTION_ACKNOWLEDGED;
      row.resolvedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
      row.acknowledgedBy = performedBy;
      row.ackComment = comment;
      row.ackAuditId = auditId;
      return new AckOutcome.Acknowledged(row);
    });
  }

  /** 列表项形状（ADR 0054 §10.2）：null 字段不写，{@code detail} 以 JSON 对象原样给出。 */
  public Map<String, Object> view(GuardOrphanActionEntity o) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", o.id);
    m.put("kind", o.kind);
    m.put("severity", o.severity);
    m.put("status", o.status);
    putIfNotNull(m, "decisionId", o.decisionId == null ? null : o.decisionId.toString());
    putIfNotNull(m, "sideEffectId", o.sideEffectId);
    m.put("executor", o.executorLabel);
    putIfNotNull(m, "executorId", o.executorId);
    m.put("detectedAt", iso(o.detectedAt));
    putIfNotNull(m, "resolvedAt", iso(o.resolvedAt));
    putIfNotNull(m, "resolution", o.resolution);
    putIfNotNull(m, "acknowledgedBy", o.acknowledgedBy);
    m.put("detail", tree(o.detail));
    return m;
  }

  private JsonNode tree(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("孤儿 detail 不是合法 JSON", e);
    }
  }

  static String iso(Instant t) {
    return t == null ? null : t.toString();
  }

  private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
    if (value != null) {
      m.put(key, value);
    }
  }
}
