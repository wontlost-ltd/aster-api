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
  @Inject AuditChainVerifier verifier;

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

  /** aster-api#498：超列宽的 error_message / performed_by / 策略标识列截断后照常入链，哈希按实际落库值计算，验链通过。 */
  @Test void 截断后的超长列照常入链且验链通过() {
    Instant start = Instant.now().minusSeconds(1);
    String performedBy = "1.1.1.1 ".repeat(12).substring(0, 95); // 脱敏后约 190 字符
    AuditEvent oversized = new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), TENANT, "m".repeat(300),
        "f".repeat(300), "p".repeat(300), null, null, performedBy, false, 12L, "e".repeat(999) + "\uD83D\uDE00" + "boom ".repeat(2_000),
        Map.of("decisionId", "d-clip"), null, null, null, null);

    appender.appendSync(event(Map.of("decisionId", "d-before")));
    var clipped = appender.appendSync(oversized);
    appender.appendSync(event(Map.of("decisionId", "d-after")));

    AuditLog row = AuditLog.findById(clipped.auditId());
    // 列宽边界落在代理对中间：整对舍去，落库值与哈希输入一致（孤立代理项经 JDBC 会被改写，验链即失败）
    assertThat(row.errorMessage).isEqualTo("e".repeat(999));
    assertThat(row.performedBy).hasSize(100).startsWith("***.***.***.***");
    assertThat(row.policyModule).hasSize(200);
    assertThat(row.policyFunction).hasSize(200);
    assertThat(row.policyId).hasSize(100);
    assertThat(AuditHashPayload.digest(row, row.hashVersion)).isEqualTo(row.currentHash);
    ChainVerificationResult result = verifier.verifyChain(TENANT, start, Instant.now().plusSeconds(1));
    assertThat(result.isValid()).as(result.getReason()).isTrue();
    assertThat(result.getRecordsVerified()).isEqualTo(3);
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
