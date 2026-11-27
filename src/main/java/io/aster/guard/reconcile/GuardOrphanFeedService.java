package io.aster.guard.reconcile;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 孤儿通知源查询（ADR 0065 §3.4.2）：① 列出「有 OPEN critical 孤儿」的租户及其最大 id；② 按 id 水位对单个租户做增量汇总。
 * 判定「值得通知」只在这里的 SQL 里（{@code severity = 'critical'}）。两条语句都是单条只读查询，各自开只读事务。
 *
 * <p>时间窗口以本服务所在节点的时钟为准：调用方传入的 {@code detectedSince} 早于 {@code now - lookback} 不超过
 * {@link #CLAMP_BAND} 时收紧为 {@code now - lookback}（吸收两侧时钟偏差与调用耗时，R2），再早或晚于 {@code now + }{@link #FUTURE_SKEW}
 * 一律拒绝，因此有效窗口恒不超过 lookback。
 */
@ApplicationScoped
public class GuardOrphanFeedService {
  /** {@code detectedSince} 早于 {@code now - lookback} 时允许被收紧的最大幅度。 */
  static final Duration CLAMP_BAND = Duration.ofMinutes(30);
  /** {@code detectedSince} 晚于 now 的容忍度（时钟偏差）。 */
  static final Duration FUTURE_SKEW = Duration.ofMinutes(5);
  static final int DEFAULT_LIMIT = 500;
  static final int MAX_LIMIT = 500;
  static final int MAX_TENANT_LENGTH = 255;
  static final int MAX_RUN_IDS = 8;

  static final String INDEX_SQL = "SELECT tenant_id, MAX(id) FROM guard_orphan_actions "
      + "WHERE status = 'OPEN' AND severity = 'critical' AND detected_at >= :since "
      + "AND (CAST(:after AS varchar) IS NULL OR tenant_id > :after) "
      + "GROUP BY tenant_id ORDER BY tenant_id LIMIT :limitPlusOne";

  static final String SUMMARY_SQL = "SELECT kind, COUNT(*), MIN(detected_at), MAX(detected_at), MAX(id), "
      + "(ARRAY_AGG(DISTINCT first_run_id ORDER BY first_run_id DESC))[1:8] "
      + "FROM guard_orphan_actions "
      + "WHERE tenant_id = :t AND status = 'OPEN' AND severity = 'critical' "
      + "AND detected_at >= :since AND id > :after GROUP BY kind";

  public record TenantIndexQuery(Instant detectedSince, String afterTenant, int limit) {}

  public record TenantMax(String tenantId, long maxId) {}

  /** 一页租户索引；{@code next} 为下一页的 {@code afterTenant}，无更多时为 null。 */
  public record TenantIndexPage(List<TenantMax> items, String next) {}

  public record SummaryQuery(String tenantId, long afterId, Instant detectedSince) {}

  /** 租户在水位之后的 OPEN critical 汇总；无行时 {@code maxId = afterId}、时间为 null、集合为空。 */
  public record Summary(String tenantId, long maxId, long total, Map<String, Long> byKind, Instant firstDetectedAt,
      Instant lastDetectedAt, List<Long> runIds) {}

  @Inject EntityManager em;
  @ConfigProperty(name = "aster.guard.reconciliation.lookback") Duration lookback;

  public Optional<TenantIndexQuery> parseIndex(JsonObject body, Instant now) {
    Optional<Instant> since = detectedSince(body, now);
    Optional<Integer> limit = optionalInt(body, "limit", 1, MAX_LIMIT, DEFAULT_LIMIT);
    boolean hasAfter = body.containsKey("afterTenant");
    Optional<String> after = requiredText(body, "afterTenant");
    if (since.isEmpty() || limit.isEmpty() || (hasAfter && after.isEmpty())) {
      return Optional.empty();
    }
    return Optional.of(new TenantIndexQuery(since.get(), after.orElse(null), limit.get()));
  }

  public Optional<SummaryQuery> parseSummary(JsonObject body, Instant now) {
    Optional<Instant> since = detectedSince(body, now);
    Optional<String> tenant = requiredText(body, "tenantId");
    Optional<Long> afterId = requiredLong(body, "afterId");
    if (since.isEmpty() || tenant.isEmpty() || afterId.isEmpty() || afterId.get() < 0) {
      return Optional.empty();
    }
    return Optional.of(new SummaryQuery(tenant.get(), afterId.get(), since.get()));
  }

  public TenantIndexPage tenants(TenantIndexQuery q) {
    List<?> rows = QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(INDEX_SQL)
        .setParameter("since", q.detectedSince()).setParameter("after", q.afterTenant())
        .setParameter("limitPlusOne", q.limit() + 1).getResultList());
    List<TenantMax> items = new ArrayList<>(rows.size());
    for (Object row : rows) {
      Object[] c = (Object[]) row;
      items.add(new TenantMax((String) c[0], ((Number) c[1]).longValue()));
    }
    if (items.size() <= q.limit()) {
      return new TenantIndexPage(List.copyOf(items), null);
    }
    List<TenantMax> page = List.copyOf(items.subList(0, q.limit()));
    return new TenantIndexPage(page, page.get(page.size() - 1).tenantId());
  }

  public Summary summary(SummaryQuery q) {
    List<?> rows = QuarkusTransaction.requiringNew().call(() -> em.createNativeQuery(SUMMARY_SQL)
        .setParameter("t", q.tenantId()).setParameter("since", q.detectedSince()).setParameter("after", q.afterId())
        .getResultList());
    Map<String, Long> byKind = new TreeMap<>();
    TreeSet<Long> runIds = new TreeSet<>(Comparator.reverseOrder());
    long total = 0;
    long maxId = q.afterId();
    Instant first = null;
    Instant last = null;
    for (Object row : rows) {
      Object[] c = (Object[]) row;
      long count = ((Number) c[1]).longValue();
      byKind.put((String) c[0], count);
      total += count;
      first = earlier(first, toInstant(c[2]));
      last = later(last, toInstant(c[3]));
      maxId = Math.max(maxId, ((Number) c[4]).longValue());
      for (Object runId : (Object[]) c[5]) {
        runIds.add(((Number) runId).longValue());
      }
    }
    List<Long> topRuns = runIds.stream().limit(MAX_RUN_IDS).toList();
    return new Summary(q.tenantId(), maxId, total, byKind, first, last, topRuns);
  }

  private Optional<Instant> detectedSince(JsonObject body, Instant now) {
    if (!(body.get("detectedSince") instanceof JsonString text)) {
      return Optional.empty();
    }
    Instant since;
    try {
      since = Instant.parse(text.getString());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
    Instant floor = now.minus(lookback);
    if (since.isBefore(floor.minus(CLAMP_BAND)) || since.isAfter(now.plus(FUTURE_SKEW))) {
      return Optional.empty();
    }
    return Optional.of(since.isBefore(floor) ? floor : since);
  }

  private static Optional<String> requiredText(JsonObject body, String key) {
    if (!(body.get(key) instanceof JsonString s)) {
      return Optional.empty();
    }
    String v = s.getString();
    return v.isBlank() || v.length() > MAX_TENANT_LENGTH ? Optional.empty() : Optional.of(v);
  }

  private static Optional<Long> requiredLong(JsonObject body, String key) {
    if (!(body.get(key) instanceof JsonNumber n) || !n.isIntegral()) {
      return Optional.empty();
    }
    try {
      return Optional.of(n.bigIntegerValueExact().longValueExact());
    } catch (ArithmeticException e) {
      return Optional.empty();
    }
  }

  private static Optional<Integer> optionalInt(JsonObject body, String key, int min, int max, int fallback) {
    if (!body.containsKey(key)) {
      return Optional.of(fallback);
    }
    return requiredLong(body, key).filter(v -> v >= min && v <= max).map(Long::intValue);
  }

  private static Instant toInstant(Object v) {
    return switch (v) {
      case Instant i -> i;
      case OffsetDateTime o -> o.toInstant();
      case Timestamp t -> t.toInstant();
      default -> throw new IllegalStateException("无法识别的时间类型: " + v.getClass());
    };
  }

  private static Instant earlier(Instant a, Instant b) {
    return a == null || b.isBefore(a) ? b : a;
  }

  private static Instant later(Instant a, Instant b) {
    return a == null || b.isAfter(a) ? b : a;
  }
}
