package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.test.BlockingDbTestHelper;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 证据冗余列（ADR 0041 §2.4）：落库投影、按租户隔离查询、批量上限；列不影响哈希校验。 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class AuditChainAppenderEvidenceColumnsTest {
  static final String TENANT = "tenant-evidence-cols";
  @Inject AuditChainAppender appender;
  @Inject BlockingDbTestHelper db;

  @BeforeEach void clean() { db.executeAsAuditMaintenance("DELETE FROM audit_logs WHERE tenant_id = '" + TENANT + "'"); }

  static AuditEvent event(Map<String, Object> metadata) {
    return new AuditEvent(EventType.POLICY_EVALUATION, Instant.now(), TENANT, "m", "f", null,
        null, null, "u", true, 5L, null, metadata, null, null, null, null);
  }

  @Test void 按相关键与决策ID查询并按租户隔离() {
    var appended = appender.appendSync(event(Map.of("correlationId", "cid-1", "decisionId", "did-1")));
    List<AuditLog> byCorrelation = AuditLog.findByCorrelationIds(TENANT, List.of("cid-1"));
    assertThat(byCorrelation).hasSize(1);
    assertThat(byCorrelation.get(0).currentHash).isNotBlank().isEqualTo(appended.currentHash());
    assertThat(AuditLog.findByDecisionIds(TENANT, List.of("did-1"))).hasSize(1);
    assertThat(AuditLog.findByCorrelationIds("other-tenant", List.of("cid-1"))).isEmpty();
    assertThat(AuditLog.findByCorrelationIds(TENANT, List.of())).isEmpty();
  }

  @Test void 列不进哈希载荷_哈希仍可复算() {
    var appended = appender.appendSync(event(Map.of("correlationId", "cid-2")));
    AuditLog row = AuditLog.findById(appended.auditId());
    assertThat(AuditHashPayload.digest(row, row.hashVersion)).isEqualTo(row.currentHash);
  }

  @Test void 超过200个id抛异常() {
    List<String> ids = IntStream.range(0, 201).mapToObj(i -> "id-" + i).toList();
    assertThatThrownBy(() -> AuditLog.findByCorrelationIds(TENANT, ids)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuditLog.findByDecisionIds(TENANT, ids)).isInstanceOf(IllegalArgumentException.class);
  }
}
