package io.aster.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardDecisionResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Action Guard 待审批的人工结论与过期（ADR 0040 §4）。
 *
 * <p>每次迁移的顺序固定为三步，且审计追加不在任何持有审批行锁的事务里进行：
 * <ol>
 *   <li>读（短事务）：取审批行与决策行，做全部校验——存在 → PENDING 且未过期 → 四眼 → 角色 → comment；</li>
 *   <li>{@link AuditChainAppender#appendSync} 写 {@code ACTION_GUARD_APPROVAL} 事件（自身独立事务），
 *       metadata 的 decisionReceiptHash 指回决策收据；失败则 {@code receipt_unavailable}，状态不变；</li>
 *   <li>{@link GuardDecisionStore#completeApproval}：加 {@code PESSIMISTIC_WRITE} 锁复核仍可迁移，
 *       同一事务内写审批终态与决策行 response_json。</li>
 * </ol>
 * 第 1 步与第 3 步之间若被并发处理（他人先批 / 刚好过期），第 3 步返回 false：已追加的事件作为
 * 「一次被否决的审批尝试」留在链上，本次调用报 {@code approval_not_pending}，审批行与决策不受影响。
 *
 * <p>阻塞方法：须在非事务的 worker 线程调用（同 {@link GuardDecisionService}）。
 */
@ApplicationScoped
public class GuardApprovalService {
  private static final Logger LOG = Logger.getLogger(GuardApprovalService.class);
  /** 与 guard_approvals.decided_by / comment 列宽一致：超长直接拒绝，不静默截断审批人身份或理由。 */
  static final int MAX_DECIDED_BY = 255;
  static final int MAX_COMMENT = 1000;
  /** 单次过期扫描的上限；剩余的由下一轮调度处理。 */
  static final int EXPIRY_BATCH = 500;

  private GuardDecisionStore store;
  private AuditChainAppender appender;
  private GuardReceiptBuilder receiptBuilder;
  private ObjectMapper objectMapper;

  /** CDI 客户端代理所需。 */
  protected GuardApprovalService() {
  }

  @Inject
  public GuardApprovalService(GuardDecisionStore store, AuditChainAppender appender, GuardReceiptBuilder receiptBuilder,
                              ObjectMapper objectMapper) {
    this.store = store;
    this.appender = appender;
    this.receiptBuilder = receiptBuilder;
    this.objectMapper = objectMapper;
  }

  /**
   * 批准或拒绝一条待审批，返回重算后的决策响应（批准 ALLOW，拒绝 DENY）。
   *
   * @param decidedBy    审批人（{@code X-User-Id}），不得等于发起主体
   * @param businessRole 审批人的业务角色（{@code X-User-Business-Role}）；审批指定角色时必须相等
   * @param comment      拒绝时必填
   * @throws GuardApprovalConflictException 校验失败、并发冲突或审批事件写入失败
   */
  public GuardDecisionResponse decideApproval(String tenantId, UUID approvalId, String decidedBy, String businessRole,
                                              boolean approve, String comment) {
    GuardApprovalEntity approval = store.findApproval(tenantId, approvalId)
        .orElseThrow(() -> conflict("approval_not_found", "审批不存在: " + approvalId));
    Instant now = Instant.now();
    validate(approval, now, decidedBy, businessRole, approve, comment);
    approval.status = approve ? GuardApprovalStatus.APPROVED : GuardApprovalStatus.REJECTED;
    approval.decidedBy = decidedBy;
    approval.decidedAt = now;
    approval.comment = isBlank(comment) ? null : comment;
    return complete(approval).orElseThrow(() -> notPending(approvalId));
  }

  /** 租户内按状态分页列出审批（按创建时间倒序）。 */
  public List<GuardApprovalEntity> list(String tenantId, GuardApprovalStatus status, int page, int size) {
    return store.listApprovals(tenantId, status, page, size);
  }

  /**
   * 把 {@code now} 时已过期的 PENDING 审批标为 EXPIRED，逐条写 {@code ACTION_GUARD_APPROVAL}（outcome EXPIRED）。
   * 单条失败只记日志，留给下一轮重试。
   *
   * @return 本轮实际迁移为 EXPIRED 的条数
   */
  public int expireDue(Instant now) {
    int expired = 0;
    for (GuardApprovalEntity approval : store.findExpiredPending(now, EXPIRY_BATCH)) {
      if (approval.isExpired(now) && expireOne(approval, now)) {
        expired++;
      }
    }
    return expired;
  }

  private boolean expireOne(GuardApprovalEntity approval, Instant now) {
    approval.status = GuardApprovalStatus.EXPIRED;
    approval.decidedBy = null;
    approval.decidedAt = now;
    approval.comment = null;
    try {
      return complete(approval).isPresent();
    } catch (RuntimeException e) {
      LOG.errorf(e, "Action Guard 审批过期处理失败，下一轮重试: approval=%s", approval.id);
      return false;
    }
  }

  /** 校验顺序即 ADR 0040 §4.2 的规则顺序：存在 → PENDING 且未过期 → 四眼 → 角色 → comment。 */
  private static void validate(GuardApprovalEntity a, Instant now, String decidedBy, String businessRole,
                               boolean approve, String comment) {
    if (a.status != GuardApprovalStatus.PENDING || a.isExpired(now)) {
      throw notPending(a.id);
    }
    if (Objects.equals(decidedBy, a.principalId)) {
      throw conflict("segregation_of_duties", "发起主体不能审批自己的动作");
    }
    if (a.requiredRole != null && !a.requiredRole.equals(businessRole)) {
      throw conflict("role_mismatch", "该审批需要业务角色: " + a.requiredRole);
    }
    if (!approve && isBlank(comment)) {
      throw conflict("comment_required", "拒绝必须填写 comment");
    }
    if (isBlank(decidedBy) || decidedBy.length() > MAX_DECIDED_BY || (comment != null && comment.length() > MAX_COMMENT)) {
      throw conflict("invalid_approval", "审批人标识须为 1–" + MAX_DECIDED_BY + " 字符，comment 不超过 " + MAX_COMMENT + " 字符");
    }
  }

  /**
   * 第 2、3 步：先入链，再加锁迁移。approval 已带目标状态；返回 empty 表示加锁后发现已不可迁移。
   *
   * @throws GuardApprovalConflictException {@code receipt_unavailable}：审批事件未入链，状态不变
   */
  private Optional<GuardDecisionResponse> complete(GuardApprovalEntity approval) {
    GuardDecisionEntity decision = store.findDecision(approval.tenantId, approval.decisionId).orElseThrow(
        () -> new IllegalStateException("审批 " + approval.id + " 关联的决策不存在: " + approval.decisionId));
    approval.receiptAuditId = appendEvent(approval, decision.receiptHash).auditId();
    GuardDecisionResponse response = readResponse(decision).withApprovalStatus(approval.status);
    if (!store.completeApproval(approval, writeJson(response))) {
      return Optional.empty();
    }
    return Optional.of(response);
  }

  private AppendedRecord appendEvent(GuardApprovalEntity approval, String decisionReceiptHash) {
    try {
      return appender.appendSync(receiptBuilder.approvalEvent(approval, decisionReceiptHash));
    } catch (AuditChainAppender.AuditAppendException e) {
      LOG.errorf(e, "Action Guard 审批事件写入失败，审批状态不变: approval=%s", approval.id);
      throw conflict("receipt_unavailable", "审批事件写入审计链失败，请稍后重试");
    }
  }

  private GuardDecisionResponse readResponse(GuardDecisionEntity e) {
    try {
      return objectMapper.readValue(e.responseJson, GuardDecisionResponse.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("guard_decisions.response_json 无法解析: " + e.id, ex);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Action Guard 序列化失败", ex);
    }
  }

  private static GuardApprovalConflictException notPending(UUID approvalId) {
    return conflict("approval_not_pending", "审批已处理或已过期: " + approvalId);
  }

  private static GuardApprovalConflictException conflict(String code, String message) {
    return new GuardApprovalConflictException(code, message);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
