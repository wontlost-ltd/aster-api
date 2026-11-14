package io.aster.guard.bypass;

import io.aster.guard.receipt.GuardReceiptEntity;
import io.restassured.response.Response;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 的「三样断言」：决策记录（{@link #decisionRecorded}）、业务侧没有副作用（{@link #noSideEffect}）、
 * 收据行与审计链一致（{@link #notConsumed} / {@link #consumedExactlyOnce}）。
 *
 * <p>「有效消耗」只认 {@code guard_receipts.consume_audit_id} 指向的尝试事件：「未消耗」= 收据行不存在，或
 * {@code consumed_at} 与 {@code consume_audit_id} 都为空，且 {@code GET /api/v1/audit/receipts} 的 {@code consumes[]}
 * 中没有 {@code authoritative: true}。对账（#465）上线前以它将读取的同一数据代替。读取一律用调用方给出的有读权限的 key。
 */
public final class BypassAssertions {
  private BypassAssertions() {
  }

  /** {@code GET /decisions/{id}} 200，且 {@code effectiveOutcome} 与 {@code reasonCode} 相等（null 表示不写出）。 */
  public static void decisionRecorded(BypassFixture api, String key, String decisionId, String effectiveOutcome,
                                      String reasonCodeOrNull) {
    Response r = api.decision(key, decisionId);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("decisionId")).isEqualTo(decisionId);
    assertThat(r.jsonPath().getString("effectiveOutcome")).isEqualTo(effectiveOutcome);
    assertThat(r.jsonPath().getString("reasonCode")).isEqualTo(reasonCodeOrNull);
  }

  public static void noSideEffect(SideEffectLedger ledger, String decisionId) {
    assertThat(ledger.forDecision(decisionId)).as("决策 %s 不得产生副作用", decisionId).isEmpty();
  }

  public static void notConsumed(BypassFixture api, String key, String decisionId) {
    GuardReceiptEntity row = BypassFixture.receiptRow(decisionId);
    if (row != null) {
      assertThat(row.consumedAt).as("consumed_at").isNull();
      assertThat(row.consumeAuditId).as("consume_audit_id").isNull();
    }
    assertThat(authoritativeConsumes(api, key, decisionId)).as("权威 consume").isEmpty();
    Response d = api.decision(key, decisionId);
    assertThat(d.statusCode()).as(d.asString()).isEqualTo(200);
    assertThat(d.jsonPath().getMap("consumedBy")).as("回读的 consumedBy").isNull();
  }

  /**
   * 恰好一次有效消耗：收据行 {@code consume_audit_id} 非空，{@code consumes[]} 恰一条 {@code authoritative: true}、
   * 其 {@code auditId} 等于该指针，且其 {@code attemptId} 等于账本中该决策唯一条目的 {@code attemptId}。
   */
  public static void consumedExactlyOnce(BypassFixture api, String key, String decisionId, SideEffectLedger ledger) {
    GuardReceiptEntity row = BypassFixture.receiptRow(decisionId);
    assertThat(row).as("收据行").isNotNull();
    assertThat(row.consumedAt).isNotNull();
    assertThat(row.consumeAuditId).isNotNull();
    List<Map<String, Object>> authoritative = authoritativeConsumes(api, key, decisionId);
    assertThat(authoritative).hasSize(1);
    assertThat(((Number) authoritative.get(0).get("auditId")).longValue()).isEqualTo(row.consumeAuditId);
    List<SideEffectLedger.Entry> effects = ledger.forDecision(decisionId);
    assertThat(effects).as("账本").hasSize(1);
    assertThat(authoritative.get(0).get("attemptId")).isEqualTo(effects.get(0).attemptId());
    assertThat(row.attemptId).isEqualTo(effects.get(0).attemptId());
  }

  private static List<Map<String, Object>> authoritativeConsumes(BypassFixture api, String key, String decisionId) {
    Response r = api.auditReceipts(key, decisionId);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    List<Map<String, Object>> consumes = r.jsonPath().getList("consumes");
    return consumes == null ? List.of()
        : consumes.stream().filter(c -> Boolean.TRUE.equals(c.get("authoritative"))).toList();
  }
}
