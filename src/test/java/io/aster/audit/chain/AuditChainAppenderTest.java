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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 同步审计追加（ADR 0040 §5.1）：返回行 id 与哈希；与异步路径同算法；失败不留行。 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class AuditChainAppenderTest {
  static final String TENANT = "tenant-guard-appender";
  @Inject AuditChainAppender appender;
  @Inject BlockingDbTestHelper db;

  @BeforeEach void clean() { db.executeAsAuditMaintenance("DELETE FROM audit_logs WHERE tenant_id = '" + TENANT + "'"); }

  static AuditEvent event(Map<String, Object> metadata) {
    return new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), TENANT, "guard.probe", "decide", null,
        null, 3L, "agent-42", false, 12L, null, metadata, null, null, null, null);
  }

  @Test void 同步追加返回链哈希且与持久化行一致() {
    var first = appender.appendSync(event(Map.of("decisionId", "d-1", "outcome", "DENY")));
    var second = appender.appendSync(event(Map.of("decisionId", "d-2", "outcome", "ALLOW")));
    assertThat(first.auditId()).isNotNull();
    assertThat(first.currentHash()).hasSize(64);
    assertThat(second.prevHash()).isEqualTo(first.currentHash());
    AuditLog row = AuditLog.findById(second.auditId());
    assertThat(row.currentHash).isEqualTo(second.currentHash());
    assertThat(row.eventType).isEqualTo("ACTION_GUARD_DECISION");
    assertThat(row.metadata).contains("\"decisionId\":\"d-2\"");
  }

  @Test void 哈希与异步监听器算法一致() {
    var appended = appender.appendSync(event(Map.of("decisionId", "d-3")));
    AuditLog row = AuditLog.findById(appended.auditId());
    assertThat(AuditHashPayload.digest(row, row.hashVersion)).isEqualTo(row.currentHash);
  }

  @Test void 失败时不写行() {
    // tenantId 超过列长 100 → 持久化失败 → 抛异常且表中无该租户行
    String longTenant = "t".repeat(150);
    AuditEvent bad = new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), longTenant, "m", "f", null,
        null, null, "u", false, null, null, Map.of(), null, null, null, null);
    assertThatThrownBy(() -> appender.appendSync(bad)).isInstanceOf(AuditChainAppender.AuditAppendException.class);
    assertThat(AuditLog.<AuditLog>find("tenantId = ?1", longTenant).list()).isEmpty();
  }
}
