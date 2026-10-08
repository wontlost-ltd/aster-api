package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardApprovalView;
import io.aster.guard.api.GuardDecisionResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.aster.guard.GuardText.isAnonymous;
import static io.aster.guard.GuardText.isBlank;

/**
 * Action Guard 待审批的人工结论与过期（ADR 0040 §4）。
 *
 * <p>每次迁移的顺序固定为三步，且审计追加不在任何持有审批行锁的事务里进行：
 * <ol>
 *   <li>读（短事务）：取审批行与决策行，做全部校验——存在 → PENDING 且未过期 → 四眼（发起主体、请求方用户、
 *       请求所用 API key）→ 角色 → comment；</li>
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
  private GuardJson json;

  /** CDI 客户端代理所需。 */
  protected GuardApprovalService() {
  }

  @Inject
  public GuardApprovalService(GuardDecisionStore store, AuditChainAppender appender, GuardReceiptBuilder receiptBuilder,
                              ObjectMapper objectMapper) {
    this.store = store;
    this.appender = appender;
    this.receiptBuilder = receiptBuilder;
    this.json = new GuardJson(objectMapper);
  }

  /**
   * 批准或拒绝一条待审批，返回重算后的决策响应（批准 ALLOW，拒绝 DENY）。
   *
   * @param decidedBy        审批人（{@code RequestIdentityResolver.performedBy}）：不得等于发起主体或提交请求的用户；
   *                         空白或 {@code anonymous} 一律 {@code user_required}，不以兜底身份落库
   * @param approverApiKeyId 审批请求所用的已验证 API key：不得等于提交决策请求所用的 key
   * @param businessRoles    审批人已验证的业务角色集合（{@code RequestIdentityResolver.businessRoles}）；审批指定角色时须包含之
   * @param comment          拒绝时必填
   * @throws GuardApprovalConflictException 校验失败、并发冲突或审批事件写入失败
   */
  public GuardDecisionResponse decideApproval(String tenantId, UUID approvalId, String decidedBy,
                                              String approverApiKeyId, List<String> businessRoles, boolean approve,
                                              String comment) {
    if (isAnonymous(decidedBy)) {
      throw conflict("user_required", "审批需要已验证的用户身份");
    }
    GuardApprovalEntity approval = store.findApproval(tenantId, approvalId)
        .orElseThrow(() -> conflict("approval_not_found", "审批不存在: " + approvalId));
    GuardDecisionEntity decision = decisionOf(approval);
    Instant now = Instant.now();
    checkPending(approval, now);
    checkSegregation(approval, decision, decidedBy, approverApiKeyId);
    checkRoleAndInput(approval, decidedBy, businessRoles, approve, comment);
    approval.status = approve ? GuardApprovalStatus.APPROVED : GuardApprovalStatus.REJECTED;
    approval.decidedBy = decidedBy;
    approval.decidedAt = now;
    approval.comment = isBlank(comment) ? null : comment;
    return complete(approval, decision).orElseThrow(() -> notPending(approvalId));
  }

  /**
   * 租户内按状态分页列出审批（按创建时间倒序）；PENDING 只含当前仍可审批的行。
   * 每项合入关联决策的字段（ADR 0042 §4.3）：整页决策一次批量查询，不逐条回读。
   */
  public List<GuardApprovalView> list(String tenantId, GuardApprovalStatus status, int page, int size) {
    List<GuardApprovalEntity> approvals = store.listApprovals(tenantId, status, Instant.now(), page, size);
    if (approvals.isEmpty()) {
      return List.of();
    }
    Map<UUID, GuardDecisionEntity> byId = store.findDecisions(tenantId,
            approvals.stream().map(a -> a.decisionId).collect(Collectors.toSet()))
        .stream().collect(Collectors.toMap(d -> d.id, Function.identity()));
    return approvals.stream().map(a -> view(a, byId.get(a.decisionId))).toList();
  }

  private GuardApprovalView view(GuardApprovalEntity a, GuardDecisionEntity d) {
    return GuardApprovalView.of(a, d, d == null ? null : controlsOrNull(d));
  }

  /** 列表只读展示：单行 controls 损坏不应让整页 500，记 warn 后该项省略 controls。 */
  private List<String> controlsOrNull(GuardDecisionEntity d) {
    try {
      return json.readControls(d);
    } catch (IllegalStateException e) {
      LOG.warnf(e, "Action Guard 决策 controls 无法解析，列表项省略该字段: tenant=%s decision=%s", d.tenantId, d.id);
      return null;
    }
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
      return complete(approval, decisionOf(approval)).isPresent();
    } catch (RuntimeException e) {
      LOG.errorf(e, "Action Guard 审批过期处理失败，下一轮重试: approval=%s", approval.id);
      return false;
    }
  }

  // 校验顺序即 ADR 0040 §4.2 的规则顺序：存在 → PENDING 且未过期 → 四眼 → 角色 → comment → 列宽。

  private static void checkPending(GuardApprovalEntity a, Instant now) {
    if (a.status != GuardApprovalStatus.PENDING || a.isExpired(now)) {
      throw notPending(a.id);
    }
  }

  /**
   * 四眼：审批人不得是发起主体（principal_id）、提交决策请求的用户（requested_by），
   * 也不得与提交请求共用同一个已验证 API key（requested_api_key_id）。只比对双方都非空白的值：
   * 本列引入之前的决策行没有请求方身份，只按 principal_id 比对。
   */
  private static void checkSegregation(GuardApprovalEntity a, GuardDecisionEntity d, String decidedBy,
                                       String approverApiKeyId) {
    if (sameIdentity(decidedBy, a.principalId) || sameIdentity(decidedBy, d.requestedBy)
        || sameIdentity(approverApiKeyId, d.requestedApiKeyId)) {
      throw conflict("segregation_of_duties", "动作的发起主体或提交方不能审批该动作");
    }
  }

  /**
   * 角色 → 拒绝须带 comment → 列宽（审批人 ≤ 255（空白已在入口按 user_required 拒绝）、comment ≤ 1000，超长拒绝而不是截断）。
   * 角色集合匹配（ADR 0042 §2.3）：所需角色须在已验证集合内；ESCALATE 无所需角色，任何已验证成员可审。
   * 集合由身份解析层给出（已去空白），这里不再读请求头，也不做二次规整。
   */
  private static void checkRoleAndInput(GuardApprovalEntity a, String decidedBy, List<String> businessRoles,
                                        boolean approve, String comment) {
    if (a.requiredRole != null && !businessRoles.contains(a.requiredRole)) {
      throw conflict("role_mismatch", "该审批需要业务角色: " + a.requiredRole, Map.of("verifiedRoles", businessRoles));
    }
    if (!approve && isBlank(comment)) {
      throw conflict("comment_required", "拒绝必须填写 comment");
    }
    if (decidedBy.length() > MAX_DECIDED_BY || (comment != null && comment.length() > MAX_COMMENT)) {
      throw conflict("invalid_approval", "审批人标识须为 1–" + MAX_DECIDED_BY + " 字符，comment 不超过 " + MAX_COMMENT + " 字符");
    }
  }

  private static boolean sameIdentity(String a, String b) {
    return !isBlank(a) && a.equals(b);
  }

  private GuardDecisionEntity decisionOf(GuardApprovalEntity approval) {
    return store.findDecision(approval.tenantId, approval.decisionId).orElseThrow(
        () -> new IllegalStateException("审批 " + approval.id + " 关联的决策不存在: " + approval.decisionId));
  }

  /**
   * 第 2、3 步：先入链，再加锁迁移。approval 已带目标状态；返回 empty 表示加锁后发现已不可迁移。
   *
   * @throws GuardApprovalConflictException {@code receipt_unavailable}：审批事件未入链，状态不变
   */
  private Optional<GuardDecisionResponse> complete(GuardApprovalEntity approval, GuardDecisionEntity decision) {
    approval.receiptAuditId = appendEvent(approval, decision.receiptHash).auditId();
    GuardDecisionResponse response = json.readResponse(decision).withApprovalStatus(approval.status);
    if (!store.completeApproval(approval, json.writeJson(response))) {
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

  private static GuardApprovalConflictException notPending(UUID approvalId) {
    return conflict("approval_not_pending", "审批已处理或已过期: " + approvalId);
  }

  private static GuardApprovalConflictException conflict(String code, String message) {
    return new GuardApprovalConflictException(code, message);
  }

  private static GuardApprovalConflictException conflict(String code, String message, Map<String, Object> details) {
    return new GuardApprovalConflictException(code, message, details);
  }
}
