package io.aster.guard;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Agent;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.policy.entity.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策收据经真实追加器入库后，身份键逐字保留（ADR 0040 §5.2）。决策 id 由服务随机生成，
 * 无法稳定构造会被 PII 脱敏改写的取值，所以这里直接用收据构造器 + {@code appendSync} 追加。
 */
@QuarkusTest
class GuardReceiptPersistenceTest {
  /** 每次 JVM 用独立租户：审计链只追加不删除。 */
  private static final String TENANT = "tenant-guard-receipt-" + UUID.randomUUID().toString().substring(0, 8);
  /** 含「4 组 4 位数字」片段，未豁免时会被 CREDIT_CARD_PATTERN 改写。 */
  private static final String DECISION_ID = "12345678-1234-5678-9012-abcdef123456";
  private static final String APPROVAL_ID = "abcdef12-1234-5678-9012-345678901234";
  private static final String IDEMPOTENCY_KEY = "87654321-4321-8765-2109-fedcba654321";

  @Inject GuardReceiptBuilder receiptBuilder;
  @Inject GuardActionMapper mapper;
  @Inject AuditChainAppender appender;

  @Test
  void 数字形UUID身份键逐字写入audit_logs_metadata() {
    Action a = new Action(new Principal("agent-42", "agent", List.of("ops-bot")), new ActionName("delete_customer_record"),
        new Resource("customer", "C-1001"), Map.of("amount", 250000), new Agent("claude-fable-5-1", "s-1"));
    GuardDecisionDraft draft = new GuardDecisionDraft(TENANT, "guard.probe", "decide", IDEMPOTENCY_KEY, mapper.flatten(a),
        mapper.contextHash(a.context()), new PolicyVersionView(1L, "b".repeat(64), 1L), "REQUIRE_APPROVAL",
        "Data Protection Officer", "customer record deletion", "PROBE-001", List.of(), null);

    AppendedRecord appended = appender.appendSync(receiptBuilder.decisionEvent(draft, UUID.fromString(DECISION_ID),
        UUID.fromString(APPROVAL_ID), "user-1"));
    AuditLog row = AuditLog.findById(appended.auditId());

    assertThat(row.eventType).isEqualTo("ACTION_GUARD_DECISION");
    assertThat(row.metadata).contains("\"decisionId\":\"" + DECISION_ID + "\"")
        .contains("\"approvalId\":\"" + APPROVAL_ID + "\"")
        .contains("\"idempotencyKey\":\"" + IDEMPOTENCY_KEY + "\"");
  }
}
