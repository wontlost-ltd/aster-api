package io.aster.guard.reconcile;

import io.aster.guard.reconcile.GuardOrphanFeedService.Summary;
import io.aster.guard.reconcile.GuardOrphanFeedService.SummaryQuery;
import io.aster.guard.reconcile.GuardOrphanFeedService.TenantIndexPage;
import io.aster.guard.reconcile.GuardOrphanFeedService.TenantIndexQuery;
import io.aster.guard.reconcile.GuardOrphanFeedService.TenantMax;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.aster.guard.reconcile.GuardOrphanActionEntity.ACKNOWLEDGED;
import static io.aster.guard.reconcile.GuardOrphanActionEntity.OPEN;
import static io.aster.guard.reconcile.GuardOrphanActionEntity.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

/** 孤儿通知源查询服务（ADR 0065 §3.4.2）：只列 OPEN critical、按 id 水位增量汇总、detectedSince 解析与收紧（R2）。 */
@QuarkusTest
class GuardOrphanFeedServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

  @Inject GuardOrphanFeedService service;
  @Inject EntityManager em;
  @ConfigProperty(name = "aster.guard.reconciliation.lookback") Duration lookback;

  private final List<String> tenants = new ArrayList<>();

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> {
      for (String t : tenants) {
        GuardOrphanActionEntity.delete("tenantId = ?1", t);
      }
    });
  }

  private String tenant(String scenario) {
    String t = ReconcileFixture.tenant("feed-" + scenario);
    tenants.add(t);
    return t;
  }

  /** 字典序可控的租户前缀：以 {@code zz} 开头，避开其他用例的 {@code tenant-…} 数据。 */
  private String pagedTenant(String prefix, String suffix) {
    String t = prefix + "-" + suffix;
    tenants.add(t);
    return t;
  }

  private static long seed(String tenant, OrphanKind kind, String subject, String status, Instant at, long run) {
    return ReconcileFixture.seedOrphan(tenant, kind, subject, status, at, run);
  }

  private long seedOpen(String tenant, OrphanKind kind, String subject) {
    return seed(tenant, kind, subject, OPEN, Instant.now(), 1);
  }

  private Instant since() {
    return Instant.now().minus(Duration.ofHours(1));
  }

  /** 翻完所有页，取出某租户的索引项。 */
  private Optional<TenantMax> indexOf(String tenant, Instant since) {
    String after = null;
    while (true) {
      TenantIndexPage page = service.tenants(new TenantIndexQuery(since, after, 500));
      for (TenantMax m : page.items()) {
        if (m.tenantId().equals(tenant)) {
          return Optional.of(m);
        }
      }
      if (page.next() == null) {
        return Optional.empty();
      }
      after = page.next();
    }
  }

  private Summary summaryOf(String tenant, long afterId, Instant since) {
    return service.summary(new SummaryQuery(tenant, afterId, since));
  }

  private static JsonObjectBuilder body(String since) {
    return Json.createObjectBuilder().add("detectedSince", since);
  }

  @Test
  void indexOnlyOpenCritical() {
    String t = tenant("idx-open");
    long maxOpen = 0;
    for (OrphanKind k : OrphanKind.values()) {
      long id = seedOpen(t, k, "open:" + k.wire());
      if (k.severity().equals("critical")) {
        maxOpen = Math.max(maxOpen, id);
      }
    }
    seed(t, OrphanKind.SIDE_EFFECT_MISMATCH, "ack", ACKNOWLEDGED, Instant.now(), 1);
    seed(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "res", RESOLVED, Instant.now(), 1);

    assertThat(indexOf(t, since())).contains(new TenantMax(t, maxOpen));
    assertThat(summaryOf(t, 0, since()).total()).isEqualTo(5);
  }

  @Test
  void warningAndInfoExcluded() {
    String t = tenant("idx-noncrit");
    seedOpen(t, OrphanKind.CONSUMED_WITHOUT_RESULT, "w");
    seedOpen(t, OrphanKind.ALLOW_UNCONSUMED, "i");

    assertThat(indexOf(t, since())).isEmpty();
    Summary s = summaryOf(t, 0, since());
    assertThat(s.total()).isZero();
    assertThat(s.maxId()).isZero();
  }

  @Test
  void indexOneRowPerTenantAndPages() {
    String prefix = "zzfeed-" + UUID.randomUUID();
    String a = pagedTenant(prefix, "a");
    String b = pagedTenant(prefix, "b");
    String c = pagedTenant(prefix, "c");
    seedOpen(a, OrphanKind.SIDE_EFFECT_MISMATCH, "1");
    long aMax = seedOpen(a, OrphanKind.SIDE_EFFECT_UNCONSUMED, "2");
    long bMax = seedOpen(b, OrphanKind.RESULT_WITHOUT_CONSUME, "1");
    long cMax = seedOpen(c, OrphanKind.CONSUME_ON_NON_ALLOW, "1");

    TenantIndexPage first = service.tenants(new TenantIndexQuery(since(), prefix, 2));
    assertThat(first.items()).containsExactly(new TenantMax(a, aMax), new TenantMax(b, bMax));
    assertThat(first.next()).isEqualTo(b);

    TenantIndexPage second = service.tenants(new TenantIndexQuery(since(), b, 2));
    assertThat(second.items()).containsExactly(new TenantMax(c, cMax));
    assertThat(second.next()).isNull();
  }

  @Test
  void detectedSinceFilters() {
    String t = tenant("idx-since");
    Instant now = Instant.now();
    seed(t, OrphanKind.SIDE_EFFECT_MISMATCH, "old", OPEN, now.minus(Duration.ofDays(8)), 1);

    Instant since = now.minus(Duration.ofDays(7));
    assertThat(indexOf(t, since)).isEmpty();
    assertThat(summaryOf(t, 0, since).total()).isZero();
    assertThat(indexOf(t, now.minus(Duration.ofDays(9)))).isPresent();
  }

  @Test
  void summaryAfterIdAndEmpty() {
    String t = tenant("sum-after");
    Instant base = Instant.now().minusSeconds(600);
    long x = seed(t, OrphanKind.SIDE_EFFECT_MISMATCH, "x", OPEN, base, 1);
    Instant yAt = base.plusSeconds(60);
    Instant zAt = base.plusSeconds(120);
    seed(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "y", OPEN, yAt, 2);
    long z = seed(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "z", OPEN, zAt, 3);

    Summary s = summaryOf(t, x, since());
    assertThat(s.tenantId()).isEqualTo(t);
    assertThat(s.total()).isEqualTo(2);
    assertThat(s.maxId()).isEqualTo(z);
    assertThat(s.byKind()).containsExactly(Map.entry("side_effect_unconsumed", 2L));
    assertThat(s.firstDetectedAt()).isEqualTo(yAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertThat(s.lastDetectedAt()).isEqualTo(zAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertThat(s.runIds()).containsExactly(3L, 2L);

    Summary empty = summaryOf(t, z, since());
    assertThat(empty.total()).isZero();
    assertThat(empty.maxId()).isEqualTo(z);
    assertThat(empty.byKind()).isEmpty();
    assertThat(empty.firstDetectedAt()).isNull();
    assertThat(empty.lastDetectedAt()).isNull();
    assertThat(empty.runIds()).isEmpty();
  }

  @Test
  void summaryByKindSortedAcrossKinds() {
    String t = tenant("sum-kinds");
    seedOpen(t, OrphanKind.SIDE_EFFECT_WITHOUT_DECISION, "1");
    seedOpen(t, OrphanKind.RESULT_WITHOUT_CONSUME, "1");
    seedOpen(t, OrphanKind.RESULT_WITHOUT_CONSUME, "2");

    Summary s = summaryOf(t, 0, since());

    assertThat(s.total()).isEqualTo(3);
    assertThat(s.byKind()).containsExactly(Map.entry("result_without_consume", 2L),
        Map.entry("side_effect_without_decision", 1L));
  }

  @Test
  void runIdsDistinctDescCapped() {
    String t = tenant("sum-runs");
    for (int run = 1; run <= 10; run++) {
      seed(t, OrphanKind.SIDE_EFFECT_MISMATCH, "r" + run, OPEN, Instant.now(), run);
    }
    seed(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "dup", OPEN, Instant.now(), 10);

    Summary s = summaryOf(t, 0, since());

    assertThat(s.total()).isEqualTo(11);
    assertThat(s.runIds()).containsExactly(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L);
  }

  @Test
  void acknowledgedRowsExcluded() {
    String t = tenant("ack");
    long id = seedOpen(t, OrphanKind.SIDE_EFFECT_MISMATCH, "a");
    assertThat(indexOf(t, since())).contains(new TenantMax(t, id));

    QuarkusTransaction.requiringNew().run(() -> GuardOrphanActionEntity.update("status = ?1 where id = ?2",
        ACKNOWLEDGED, id));

    assertThat(indexOf(t, since())).isEmpty();
    assertThat(summaryOf(t, 0, since()).total()).isZero();
  }

  @Test
  void backlogDoesNotStarve() {
    String t = tenant("backlog");
    QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
        "INSERT INTO guard_orphan_actions (tenant_id, kind, severity, subject_key, executor_label, detail, status, "
            + "first_run_id, detected_at) SELECT :t, 'side_effect_mismatch', 'critical', 'bulk-' || g, 'other', "
            + "'{}'::jsonb, 'OPEN', 1, now() FROM generate_series(1, 600) g").setParameter("t", t).executeUpdate());
    long afterId = QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
        "SELECT MAX(id) FROM guard_orphan_actions WHERE tenant_id = :t").setParameter("t", t).getSingleResult())
        .longValue());
    long fresh = seedOpen(t, OrphanKind.SIDE_EFFECT_UNCONSUMED, "fresh");

    Summary s = summaryOf(t, afterId, since());

    assertThat(s.total()).isEqualTo(1);
    assertThat(s.maxId()).isEqualTo(fresh);
    assertThat(indexOf(t, since())).contains(new TenantMax(t, fresh));
  }

  @Test
  void summaryCountsAndMaxIdFromOneStatement() {
    String sql = GuardOrphanFeedService.SUMMARY_SQL;
    Matcher m = Pattern.compile("(?i)\\bselect\\b").matcher(sql);
    int selects = 0;
    while (m.find()) {
      selects++;
    }
    assertThat(selects).isEqualTo(1);
    assertThat(sql.split("(?i)from guard_orphan_actions", -1)).hasSize(2);
  }

  @Test
  void parseRejectsBadDetectedSince() {
    Instant floor = NOW.minus(lookback);
    assertThat(service.parseIndex(Json.createObjectBuilder().build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body("yesterday").build(), NOW)).isEmpty();
    assertThat(service.parseIndex(Json.createObjectBuilder().add("detectedSince", 5).build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(floor.minus(Duration.ofMinutes(31)).toString()).build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(NOW.plus(Duration.ofMinutes(6)).toString()).build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(NOW.plus(Duration.ofMinutes(5)).toString()).build(), NOW)).isPresent();
    assertThat(service.parseSummary(summaryBody(floor.minus(Duration.ofMinutes(31)).toString()).build(), NOW))
        .isEmpty();
  }

  @Test
  void parseClampsIntoWindowEdge() {
    Instant floor = NOW.minus(lookback);
    for (Duration offset : List.of(Duration.ofMinutes(30), Duration.ofMinutes(9), Duration.ofMinutes(1))) {
      Instant sent = floor.minus(offset);
      assertThat(service.parseIndex(body(sent.toString()).build(), NOW)).get()
          .extracting(TenantIndexQuery::detectedSince).as("index -%s", offset).isEqualTo(floor);
      assertThat(service.parseSummary(summaryBody(sent.toString()).build(), NOW)).get()
          .extracting(SummaryQuery::detectedSince).as("summary -%s", offset).isEqualTo(floor);
    }
    Instant inside = floor.plusSeconds(1);
    assertThat(service.parseIndex(body(inside.toString()).build(), NOW)).get()
        .extracting(TenantIndexQuery::detectedSince).isEqualTo(inside);
    assertThat(service.parseIndex(body(floor.toString()).build(), NOW)).get()
        .extracting(TenantIndexQuery::detectedSince).isEqualTo(floor);
  }

  @Test
  void clampNeverReturnsOlderRows() {
    String t = tenant("clamp-old");
    Instant now = Instant.now();
    seed(t, OrphanKind.SIDE_EFFECT_MISMATCH, "older", OPEN, now.minus(lookback).minus(Duration.ofMinutes(2)), 1);
    Instant sent = now.minus(lookback).minus(Duration.ofMinutes(4));

    Instant since = service.parseIndex(body(sent.toString()).build(), now).orElseThrow().detectedSince();

    assertThat(indexOf(t, since)).isEmpty();
    assertThat(summaryOf(t, 0, since).total()).isZero();
  }

  @Test
  void clampKeepsWindowEdge() {
    String t = tenant("clamp-edge");
    Instant now = Instant.now();
    long id = seed(t, OrphanKind.SIDE_EFFECT_MISMATCH, "edge", OPEN, now.minus(lookback).plus(Duration.ofMinutes(1)),
        1);
    // 云端偏差 -5m 且耗时 20m，以及偏差 +5m 且耗时 0：发送值相对 now_api - lookback 的两个极端偏移
    for (Duration offset : List.of(Duration.ofMinutes(-30), Duration.ZERO)) {
      Instant since = service.parseIndex(body(now.minus(lookback).plus(offset).toString()).build(), now)
          .orElseThrow().detectedSince();
      assertThat(indexOf(t, since)).as("offset %s", offset).contains(new TenantMax(t, id));
      assertThat(summaryOf(t, 0, since).total()).as("offset %s", offset).isEqualTo(1);
    }
  }

  @Test
  void parseIndexLimitAndAfterTenant() {
    String since = NOW.toString();
    assertThat(service.parseIndex(body(since).build(), NOW)).get()
        .isEqualTo(new TenantIndexQuery(NOW, null, 500));
    assertThat(service.parseIndex(body(since).add("limit", 1).add("afterTenant", "t-1").build(), NOW)).get()
        .isEqualTo(new TenantIndexQuery(NOW, "t-1", 1));
    assertThat(service.parseIndex(body(since).add("limit", 500).build(), NOW)).isPresent();
    assertThat(service.parseIndex(body(since).add("unknown", "x").build(), NOW)).isPresent();
    assertThat(service.parseIndex(body(since).add("limit", 0).build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(since).add("limit", 501).build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(since).add("limit", "5").build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(since).add("limit", 1.5).build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(since).add("afterTenant", " ").build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(since).add("afterTenant", "x".repeat(256)).build(), NOW)).isEmpty();
    assertThat(service.parseIndex(body(since).add("afterTenant", "x".repeat(255)).build(), NOW)).isPresent();
    assertThat(service.parseIndex(body(since).add("afterTenant", 5).build(), NOW)).isEmpty();
  }

  @Test
  void parseSummaryTenantAndAfterId() {
    String since = NOW.toString();
    assertThat(service.parseSummary(summaryBody(since).build(), NOW)).get()
        .isEqualTo(new SummaryQuery("t-1", 7, NOW));
    assertThat(service.parseSummary(summaryBody(since).add("afterId", 0).build(), NOW)).get()
        .isEqualTo(new SummaryQuery("t-1", 0, NOW));
    assertThat(service.parseSummary(summaryBody(since).add("afterId", -1).build(), NOW)).isEmpty();
    assertThat(service.parseSummary(summaryBody(since).add("afterId", "7").build(), NOW)).isEmpty();
    assertThat(service.parseSummary(summaryBody(since).add("afterId", 1.5).build(), NOW)).isEmpty();
    assertThat(service.parseSummary(body(since).add("tenantId", "t-1").build(), NOW)).isEmpty();
    assertThat(service.parseSummary(body(since).add("afterId", 1).build(), NOW)).isEmpty();
    assertThat(service.parseSummary(summaryBody(since).add("tenantId", " ").build(), NOW)).isEmpty();
    assertThat(service.parseSummary(summaryBody(since).add("tenantId", "x".repeat(256)).build(), NOW)).isEmpty();
    assertThat(service.parseSummary(summaryBody(since).add("tenantId", "x".repeat(255)).build(), NOW)).isPresent();
  }

  private static JsonObjectBuilder summaryBody(String since) {
    JsonObject base = body(since).add("tenantId", "t-1").add("afterId", 7).build();
    JsonObjectBuilder b = Json.createObjectBuilder();
    base.forEach(b::add);
    return b;
  }
}
