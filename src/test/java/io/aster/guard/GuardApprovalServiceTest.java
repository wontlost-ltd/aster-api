package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardDecisionResponse.ApprovalView;
import io.aster.guard.api.GuardDecisionResponse.ReceiptView;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR 0040 §4：审批的四眼、角色、comment、状态校验，审批事件与过期调度（纯 JUnit + Mockito）。 */
class GuardApprovalServiceTest {
  private static final String TENANT = "tenant-a";
  private static final String DPO = "Data Protection Officer";
  private static final String DECISION_HASH = "a".repeat(64);
  private static final String APPROVAL_HASH = "e".repeat(64);
  /** 提交决策请求的用户与 API key（决策行 requested_by / requested_api_key_id）。 */
  private static final String REQUESTER = "requester-1";
  private static final String REQUESTER_KEY = "ak-requester";

  private GuardDecisionStore store;
  private AuditChainAppender appender;
  private final ObjectMapper json = new ObjectMapper();
  private GuardApprovalService service;

  @BeforeEach
  void setUp() {
    store = mock(GuardDecisionStore.class);
    appender = mock(AuditChainAppender.class);
    service = new GuardApprovalService(store, appender, new GuardReceiptBuilder(), json);
    when(appender.appendSync(any())).thenReturn(new AppendedRecord(9001L, APPROVAL_HASH, DECISION_HASH));
    when(store.completeApproval(any(), anyString())).thenReturn(true);
  }

  /** 待审批行 + 对应决策行（决策响应为 PENDING），并挂到 store 上。 */
  private GuardApprovalEntity pending(String requiredRole, Instant expiresAt) throws Exception {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = UUID.randomUUID();
    a.tenantId = TENANT;
    a.decisionId = UUID.randomUUID();
    a.outcome = requiredRole == null ? "ESCALATE" : "REQUIRE_APPROVAL";
    a.requiredRole = requiredRole;
    a.principalId = "agent-42";
    a.status = GuardApprovalStatus.PENDING;
    a.expiresAt = expiresAt;
    when(store.findApproval(TENANT, a.id)).thenReturn(Optional.of(a));
    when(store.findDecision(TENANT, a.decisionId)).thenReturn(Optional.of(decisionOf(a)));
    return a;
  }

  private GuardApprovalEntity pending(String requiredRole) throws Exception {
    return pending(requiredRole, Instant.now().plus(Duration.ofHours(1)));
  }

  private GuardDecisionEntity decisionOf(GuardApprovalEntity a) throws Exception {
    GuardDecisionResponse r = new GuardDecisionResponse(
        new ApprovalView(a.expiresAt.toString(), a.id.toString(), a.requiredRole, "PENDING"),
        List.of(), a.decisionId.toString(), "PENDING", a.outcome, null, "customer record deletion", null,
        new ReceiptView(88L, DECISION_HASH, "0".repeat(64)), a.requiredRole, "PROBE-001");
    GuardDecisionEntity d = new GuardDecisionEntity();
    d.id = a.decisionId;
    d.tenantId = TENANT;
    d.receiptHash = DECISION_HASH;
    d.responseJson = json.writeValueAsString(r);
    d.requestedBy = REQUESTER;
    d.requestedApiKeyId = REQUESTER_KEY;
    return d;
  }

  private AuditEvent appendedEvent() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(appender).appendSync(captor.capture());
    return captor.getValue();
  }

  private static void assertCode(Runnable call, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOf(GuardApprovalConflictException.class)
        .extracting(e -> ((GuardApprovalConflictException) e).code())
        .isEqualTo(code);
  }

  private void verifyNothingWritten() {
    verify(appender, never()).appendSync(any());
    verify(store, never()).completeApproval(any(), anyString());
  }

  @Test
  void 发起主体自批_segregation_of_duties() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    assertCode(() -> service.decideApproval(TENANT, a.id, "agent-42", null, DPO, true, null), "segregation_of_duties");
    verifyNothingWritten();
  }

  @Test
  void 提交请求的用户自批_segregation_of_duties() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    assertCode(() -> service.decideApproval(TENANT, a.id, REQUESTER, null, DPO, true, null), "segregation_of_duties");
    verifyNothingWritten();
  }

  @Test
  void 与提交请求共用同一API_key_segregation_of_duties() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", REQUESTER_KEY, DPO, true, null),
        "segregation_of_duties");
    verifyNothingWritten();
  }

  @Test
  void 不同用户不同API_key可批() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    GuardDecisionResponse r = service.decideApproval(TENANT, a.id, "dpo-1", "ak-dpo", DPO, true, null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
  }

  @Test
  void 决策行无请求方身份时只按principal比对_空白不算相同() throws Exception {
    GuardApprovalEntity a = pending(DPO);
    GuardDecisionEntity legacy = decisionOf(a);
    legacy.requestedBy = null;
    legacy.requestedApiKeyId = null;
    when(store.findDecision(TENANT, a.decisionId)).thenReturn(Optional.of(legacy));

    GuardDecisionResponse r = service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, true, null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
  }

  @Test
  void 角色不符或缺失_role_mismatch() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, "Engineer", true, null), "role_mismatch");
    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, null, true, null), "role_mismatch");
    verifyNothingWritten();
  }

  @Test
  void 业务角色带首尾空白时去空白后比对() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    GuardDecisionResponse r = service.decideApproval(TENANT, a.id, "dpo-1", null, " " + DPO + " ", true, null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
  }

  @Test
  void required_role为空_ESCALATE_任意角色可批() throws Exception {
    GuardApprovalEntity a = pending(null);

    GuardDecisionResponse r = service.decideApproval(TENANT, a.id, "reviewer-1", null, null, true, null);

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
  }

  @Test
  void 拒绝必须有comment_comment_required() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, false, null), "comment_required");
    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, false, "   "), "comment_required");
    verifyNothingWritten();
  }

  @Test
  void 非PENDING_approval_not_pending() throws Exception {
    GuardApprovalEntity a = pending(DPO);
    a.status = GuardApprovalStatus.APPROVED;

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, true, null), "approval_not_pending");
    verifyNothingWritten();
  }

  @Test
  void 已过期_approval_not_pending() throws Exception {
    GuardApprovalEntity a = pending(DPO, Instant.now().minusSeconds(1));

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, true, null), "approval_not_pending");
    verifyNothingWritten();
  }

  @Test
  void 审批不存在_approval_not_found() {
    when(store.findApproval(eq(TENANT), any())).thenReturn(Optional.empty());

    assertCode(() -> service.decideApproval(TENANT, UUID.randomUUID(), "dpo-1", null, DPO, true, null), "approval_not_found");
  }

  @Test
  void comment或审批人超过列宽_invalid_approval() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, false, "x".repeat(1001)), "invalid_approval");
    assertCode(() -> service.decideApproval(TENANT, a.id, "d".repeat(256), null, DPO, true, null), "invalid_approval");
    verifyNothingWritten();
  }

  @Test
  void 批准成功写APPROVAL事件且metadata指回决策收据哈希() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    GuardDecisionResponse r = service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, true, "ok");

    assertThat(r.effectiveOutcome()).isEqualTo("ALLOW");
    assertThat(r.approval().status()).isEqualTo("APPROVED");
    assertThat(r.decisionId()).isEqualTo(a.decisionId.toString());
    AuditEvent event = appendedEvent();
    assertThat(event.eventType()).isEqualTo(EventType.ACTION_GUARD_APPROVAL);
    assertThat(event.tenantId()).isEqualTo(TENANT);
    assertThat(event.success()).isTrue();
    assertThat(event.metadata())
        .containsEntry("decisionReceiptHash", DECISION_HASH)
        .containsEntry("outcome", "APPROVED")
        .containsEntry("decidedBy", "dpo-1")
        .containsEntry("approvalId", a.id.toString())
        .containsEntry("decisionId", a.decisionId.toString());

    InOrder order = inOrder(appender, store);
    order.verify(appender).appendSync(any());
    ArgumentCaptor<GuardApprovalEntity> decided = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    ArgumentCaptor<String> responseJson = ArgumentCaptor.forClass(String.class);
    order.verify(store).completeApproval(decided.capture(), responseJson.capture());
    assertThat(decided.getValue().status).isEqualTo(GuardApprovalStatus.APPROVED);
    assertThat(decided.getValue().decidedBy).isEqualTo("dpo-1");
    assertThat(decided.getValue().decidedAt).isNotNull();
    assertThat(decided.getValue().comment).isEqualTo("ok");
    assertThat(decided.getValue().receiptAuditId).isEqualTo(9001L);
    assertThat(json.readValue(responseJson.getValue(), GuardDecisionResponse.class)).isEqualTo(r);
  }

  @Test
  void 拒绝成功DENY且comment进入事件() throws Exception {
    GuardApprovalEntity a = pending(DPO);

    GuardDecisionResponse r = service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, false, "no legal basis");

    assertThat(r.effectiveOutcome()).isEqualTo("DENY");
    assertThat(r.approval().status()).isEqualTo("REJECTED");
    AuditEvent event = appendedEvent();
    assertThat(event.success()).isFalse();
    assertThat(event.metadata()).containsEntry("outcome", "REJECTED").containsEntry("comment", "no legal basis");
  }

  @Test
  void 审批事件写入失败_receipt_unavailable且不改状态() throws Exception {
    GuardApprovalEntity a = pending(DPO);
    when(appender.appendSync(any())).thenThrow(new AuditChainAppender.AuditAppendException("down", null));

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, true, null), "receipt_unavailable");
    verify(store, never()).completeApproval(any(), anyString());
  }

  @Test
  void 加锁后发现已被他人处理_approval_not_pending() throws Exception {
    GuardApprovalEntity a = pending(DPO);
    when(store.completeApproval(any(), anyString())).thenReturn(false);

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, true, null), "approval_not_pending");
  }

  @Test
  void tx1与tx2之间到期_加锁复核失败_approval_not_pending() throws Exception {
    Instant expiresAt = Instant.now().plusMillis(150);
    GuardApprovalEntity a = pending(DPO, expiresAt);
    GuardApprovalEntity lockedRow = new GuardApprovalEntity();
    lockedRow.status = GuardApprovalStatus.PENDING;
    lockedRow.expiresAt = expiresAt;
    // 模拟 tx2：审计追加之后、加锁之前行已到期，按真实的可迁移判定（加锁时刻的 now）复核
    when(store.completeApproval(any(), anyString())).thenAnswer(inv -> {
      Thread.sleep(300);
      return GuardDecisionJpaStore.transitionable(lockedRow, inv.getArgument(0), Instant.now());
    });

    assertCode(() -> service.decideApproval(TENANT, a.id, "dpo-1", null, DPO, true, null), "approval_not_pending");
    ArgumentCaptor<GuardApprovalEntity> decided = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    verify(store).completeApproval(decided.capture(), anyString());
    assertThat(decided.getValue().decidedAt).isBefore(expiresAt);
  }

  @Test
  void expireDue只处理过期PENDING并逐条写事件() throws Exception {
    Instant now = Instant.now();
    GuardApprovalEntity e1 = pending(DPO, now.minusSeconds(60));
    GuardApprovalEntity e2 = pending(null, now.minusSeconds(1));
    GuardApprovalEntity fresh = pending(DPO, now.plusSeconds(60));
    GuardApprovalEntity done = pending(DPO, now.minusSeconds(60));
    done.status = GuardApprovalStatus.APPROVED;
    when(store.findExpiredPending(eq(now), anyInt())).thenReturn(List.of(e1, e2, fresh, done));

    int n = service.expireDue(now);

    assertThat(n).isEqualTo(2);
    ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
    verify(appender, times(2)).appendSync(events.capture());
    assertThat(events.getAllValues()).allSatisfy(ev -> {
      assertThat(ev.eventType()).isEqualTo(EventType.ACTION_GUARD_APPROVAL);
      assertThat(ev.metadata()).containsEntry("outcome", "EXPIRED").containsEntry("decisionReceiptHash", DECISION_HASH);
      assertThat(ev.performedBy()).isEqualTo("SYSTEM");
    });
    assertThat(events.getAllValues()).extracting(ev -> ev.metadata().get("approvalId"))
        .containsExactly(e1.id.toString(), e2.id.toString());
    ArgumentCaptor<GuardApprovalEntity> decided = ArgumentCaptor.forClass(GuardApprovalEntity.class);
    ArgumentCaptor<String> responseJson = ArgumentCaptor.forClass(String.class);
    verify(store, times(2)).completeApproval(decided.capture(), responseJson.capture());
    assertThat(decided.getAllValues()).allSatisfy(d -> {
      assertThat(d.status).isEqualTo(GuardApprovalStatus.EXPIRED);
      assertThat(d.decidedBy).isNull();
      assertThat(d.receiptAuditId).isEqualTo(9001L);
    });
    for (String s : responseJson.getAllValues()) {
      GuardDecisionResponse r = json.readValue(s, GuardDecisionResponse.class);
      assertThat(r.effectiveOutcome()).isEqualTo("DENY");
      assertThat(r.approval().status()).isEqualTo("EXPIRED");
    }
  }

  @Test
  void expireDue单条事件失败不影响其余() throws Exception {
    Instant now = Instant.now();
    GuardApprovalEntity e1 = pending(DPO, now.minusSeconds(60));
    GuardApprovalEntity e2 = pending(DPO, now.minusSeconds(60));
    when(store.findExpiredPending(eq(now), anyInt())).thenReturn(List.of(e1, e2));
    when(appender.appendSync(any()))
        .thenThrow(new AuditChainAppender.AuditAppendException("down", null))
        .thenReturn(new AppendedRecord(9002L, APPROVAL_HASH, DECISION_HASH));

    assertThat(service.expireDue(now)).isEqualTo(1);
    verify(store, times(1)).completeApproval(any(), anyString());
  }
}
