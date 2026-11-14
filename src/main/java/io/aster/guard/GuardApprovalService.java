package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardApprovalView;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.provenance.InputSourceResolver;
import io.aster.guard.receipt.ExecutionReceiptIssuer;
import io.aster.guard.receipt.ExecutionReceiptUnavailableException;
import io.aster.guard.snapshot.InputSnapshotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 *       同一事务内写审批终态与决策行 response_json；目标为 APPROVED 时以加锁后的 now 签发执行收据，
 *       收据行与 response_json 中的收据同一事务写入（ADR 0040 §12 D1 (2) / D4）。签发失败则整个事务回滚，
 *       报 {@code receipt_unavailable}，审批保持 PENDING。</li>
 * </ol>
 * 第 1 步与第 3 步之间若被并发处理（他人先批 / 刚好过期 / 签发方吊销），第 3 步返回 false：已追加的事件作为
 * 「一次被否决的审批尝试」留在链上，本次调用报 {@code approval_not_pending}，审批行与决策不受影响。
 * {@code approval_not_pending} 的错误体在审批已因签发方吊销转 EXPIRED 时附 {@code expiredReason}（ADR 0040 §11 D4）。
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
  private ExecutionReceiptIssuer receiptIssuer;
  private InputSourceResolver inputSources;
  private InputSnapshotService inputSnapshots;

  /** 单条过期迁移的结果：已迁移、加锁后发现已不是 PENDING（被并发处理）、或审计 / 存储失败（状态不变）。 */
  public enum ExpiryOutcome { EXPIRED, NOT_PENDING, FAILED }

  /** CDI 客户端代理所需。 */
  protected GuardApprovalService() {
  }

  @Inject
  public GuardApprovalService(GuardDecisionStore store, AuditChainAppender appender, GuardReceiptBuilder receiptBuilder,
                              ObjectMapper objectMapper, ExecutionReceiptIssuer receiptIssuer,
                              InputSourceResolver inputSources, InputSnapshotService inputSnapshots) {
    this.store = store;
    this.appender = appender;
    this.receiptBuilder = receiptBuilder;
    this.json = new GuardJson(objectMapper);
    this.receiptIssuer = receiptIssuer;
    this.inputSources = inputSources;
    this.inputSnapshots = inputSnapshots;
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
    return decideApproval(tenantId, approvalId, decidedBy, approverApiKeyId, businessRoles, approve, comment, null);
  }

  /**
   * 同上，并记录审批人所见输入视图的快照哈希（ADR 0042 §12 D6）：服务端为<b>该审批人</b>重算打码视图哈希
   * {@code H_masked}，本人解除过时再算 {@code H_revealed}。提交的哈希等于其一 → {@code matched = true}，
   * {@code inputRevealed} 为是否等于 {@code H_revealed}；都不等 → 409 {@code input_snapshot_mismatch}，状态不变；
   * 未提交（旧 SDK）→ 记 {@code H_masked}、{@code matched = false}、{@code inputRevealed} 为本人是否解除过。
   * 输入不可用时不记哈希，只记 {@code inputStatus}。别人的解除不影响本人。
   */
  public GuardDecisionResponse decideApproval(String tenantId, UUID approvalId, String decidedBy,
                                              String approverApiKeyId, List<String> businessRoles, boolean approve,
                                              String comment, String inputSnapshotHash) {
    if (isAnonymous(decidedBy)) {
      throw conflict("user_required", "审批需要已验证的用户身份");
    }
    GuardApprovalEntity approval = store.findApproval(tenantId, approvalId)
        .orElseThrow(() -> conflict("approval_not_found", "审批不存在: " + approvalId));
    GuardDecisionEntity decision = decisionOf(approval);
    Instant now = Instant.now();
    checkPending(approval, now);
    expireIfIssuerRevoked(approval, decision, now);
    checkSegregation(approval, decision, decidedBy, approverApiKeyId);
    checkRoleAndInput(approval, decidedBy, businessRoles, approve, comment);
    recordSnapshot(approval, decidedBy, inputSnapshotHash);
    approval.status = approve ? GuardApprovalStatus.APPROVED : GuardApprovalStatus.REJECTED;
    approval.decidedBy = decidedBy;
    approval.decidedAt = now;
    approval.comment = isBlank(comment) ? null : comment;
    Optional<GuardDecisionResponse> done = complete(approval, decision);
    if (done.isPresent()) {
      return done.get();
    }
    // 加锁后不可迁移：可能是签发方恰在校验与加锁之间被吊销（批准在锁内被拒），此时按吊销路径就地转 EXPIRED
    expireIfIssuerRevoked(approval, decision, now);
    throw notPendingNow(tenantId, approvalId);
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
    Set<UUID> decisionIds = approvals.stream().map(a -> a.decisionId).collect(Collectors.toSet());
    Map<UUID, GuardDecisionEntity> byId = store.findDecisions(tenantId, decisionIds)
        .stream().collect(Collectors.toMap(d -> d.id, Function.identity()));
    Set<UUID> withInput = inputSnapshots.decisionsWithInput(tenantId, decisionIds);
    return approvals.stream().map(a -> view(a, byId.get(a.decisionId), withInput)).toList();
  }

  private GuardApprovalView view(GuardApprovalEntity a, GuardDecisionEntity d, Set<UUID> withInput) {
    String inputStatus = a.status == GuardApprovalStatus.PENDING
        ? (withInput.contains(a.decisionId) ? InputSnapshotService.AVAILABLE : InputSnapshotService.UNAVAILABLE)
        : a.inputStatus;
    return GuardApprovalView.of(a, d, d == null ? null : controlsOrNull(d), inputStatus);
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
      if (approval.isExpired(now) && expire(approval, now, null) == ExpiryOutcome.EXPIRED) {
        expired++;
      }
    }
    return expired;
  }

  /**
   * 把一条审批转 EXPIRED，走与自然过期相同的「追加审批事件 → 加锁迁移」：审批事件 outcome 为 EXPIRED、由
   * {@code SYSTEM} 发起，{@code expiredReason} 非 null 时写入审批行与事件 metadata。
   * 加锁后已不是 PENDING（被批准 / 拒绝 / 已过期）则不改动、返回 {@link ExpiryOutcome#NOT_PENDING}。
   * 失败只记日志、返回 {@link ExpiryOutcome#FAILED}，审批保持 PENDING。阻塞，须在非事务线程调用。
   *
   * @param expiredReason 自然过期为 null；签发方吊销为 {@link GuardApprovalEntity#EXPIRED_FACT_ISSUER_REVOKED}
   */
  public ExpiryOutcome expire(GuardApprovalEntity approval, Instant now, String expiredReason) {
    approval.status = GuardApprovalStatus.EXPIRED;
    approval.decidedBy = null;
    approval.decidedAt = now;
    approval.comment = null;
    approval.expiredReason = expiredReason;
    try {
      return complete(approval, decisionOf(approval)).isPresent() ? ExpiryOutcome.EXPIRED : ExpiryOutcome.NOT_PENDING;
    } catch (RuntimeException e) {
      LOG.errorf(e, "Action Guard 审批过期处理失败，留待重试: approval=%s reason=%s", approval.id, expiredReason);
      return ExpiryOutcome.FAILED;
    }
  }

  /**
   * 吊销联动的就地兜底（ADR 0040 §11 D4，fail-closed）：决策所依赖的签发方已吊销而审批仍是 PENDING
   * （吊销与决策落库并发、或联动中途失败而未覆盖到它）时，不放行人工处理，就地按吊销同一路径转 EXPIRED，
   * 本次调用报 {@code approval_not_pending}（附 {@code expiredReason}）。迁移失败则报 {@code receipt_unavailable}，状态不变。
   */
  private void expireIfIssuerRevoked(GuardApprovalEntity approval, GuardDecisionEntity decision, Instant now) {
    if (!inputSources.issuersRevoked(approval.tenantId, json.readFactIssuers(decision))) {
      return;
    }
    if (expire(approval, now, GuardApprovalEntity.EXPIRED_FACT_ISSUER_REVOKED) == ExpiryOutcome.FAILED) {
      throw conflict("receipt_unavailable", "审批事件写入审计链失败，请稍后重试");
    }
    throw notPendingNow(approval.tenantId, approval.id);
  }

  // 校验顺序即 ADR 0040 §4.2 的规则顺序：存在 → PENDING 且未过期 → 所依赖签发方未吊销（§11 D4）→ 四眼 → 角色 → comment → 列宽。

  private static void checkPending(GuardApprovalEntity a, Instant now) {
    if (a.status != GuardApprovalStatus.PENDING || a.isExpired(now)) {
      throw notPending(a);
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

  /** 把快照哈希四键写进审批行的目标状态（审批事件与加锁迁移都读这里）；校验失败抛 409，状态不变。 */
  private void recordSnapshot(GuardApprovalEntity approval, String decidedBy, String submitted) {
    InputSnapshotService.ApproverHashes h = inputSnapshots.hashesFor(approval, decidedBy);
    approval.inputStatus = h.status();
    if (!InputSnapshotService.AVAILABLE.equals(h.status())) {
      approval.inputSnapshotHash = null;
      approval.inputSnapshotMatched = false;
      approval.inputRevealed = false;
      return;
    }
    boolean ownReveal = h.revealed() != null;
    approval.revealedAtHash = ownReveal;
    if (submitted == null) {
      approval.inputSnapshotHash = h.masked();
      approval.inputSnapshotMatched = false;
      approval.inputRevealed = ownReveal;
    } else if (submitted.equals(h.masked())) {
      approval.inputSnapshotHash = h.masked();
      approval.inputSnapshotMatched = true;
      approval.inputRevealed = false;
    } else if (ownReveal && submitted.equals(h.revealed())) {
      approval.inputSnapshotHash = h.revealed();
      approval.inputSnapshotMatched = true;
      approval.inputRevealed = true;
    } else {
      throw conflict("input_snapshot_mismatch", "输入视图已变化，请刷新后重新审阅");
    }
  }

  /**
   * 锁内复核快照（ADR 0042 §12 D6）：哈希在加锁前按审批人当时的解除状态算出；若本人的首次解除恰在其后、加锁之前提交，
   * 记下的 {@code inputRevealed} 会与事实不符——此时整个迁移回滚并报 {@code input_snapshot_mismatch}，客户端刷新视图后重试。
   * 已追加的审批事件作为一次被否决的尝试留在链上（与并发处理同一口径）。过期迁移与输入不可用不复核。
   */
  private void requireSnapshotUnchanged(GuardApprovalEntity approval) {
    if (approval.status == GuardApprovalStatus.EXPIRED || approval.decidedBy == null
        || !InputSnapshotService.AVAILABLE.equals(approval.inputStatus)) {
      return;
    }
    if (approval.revealedAtHash != inputSnapshots.revealedNow(approval, approval.decidedBy)) {
      throw conflict("input_snapshot_mismatch", "输入视图已变化（解除状态改变），请刷新后重新审阅");
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
   * 批准且需签发时，先预判签发器可用再写审批事件：不可签发（active key 已到期）时直接 {@code receipt_unavailable}，
   * 不在链上为每次点击留下一条注定失败的审批事件。
   *
   * @throws GuardApprovalConflictException {@code receipt_unavailable}：审批事件未入链或执行收据签发失败，状态不变
   */
  private Optional<GuardDecisionResponse> complete(GuardApprovalEntity approval, GuardDecisionEntity decision) {
    boolean issues = approval.status == GuardApprovalStatus.APPROVED && canIssue(decision);
    if (issues && !receiptIssuer.available()) {
      LOG.errorf("Action Guard 审批转 ALLOW 时执行收据不可签发，审批状态不变且不写审批事件: approval=%s", approval.id);
      throw conflict("receipt_unavailable", "执行收据暂不可签发，请稍后重试");
    }
    approval.receiptAuditId = appendEvent(approval, decision.receiptHash).auditId();
    GuardDecisionResponse base = json.readResponse(decision).withApprovalStatus(approval.status);
    AtomicReference<GuardDecisionResponse> written = new AtomicReference<>(base);
    boolean completed;
    try {
      completed = store.completeApproval(approval, now -> {
        requireSnapshotUnchanged(approval);
        if (!issues) {
          return new GuardDecisionStore.ApprovalWrite(json.writeJson(base), null);
        }
        ExecutionReceiptIssuer.Issued issued = receiptIssuer.issue(decision.id, decision.tenantId, decision.actionHash,
            decision.policyVersionId, now);
        written.set(base.withExecutionReceipt(issued.receipt()));
        return new GuardDecisionStore.ApprovalWrite(json.writeJson(written.get()), issued.row());
      });
    } catch (ExecutionReceiptUnavailableException e) {
      LOG.errorf(e, "Action Guard 审批转 ALLOW 时执行收据签发失败，审批状态不变: approval=%s", approval.id);
      throw conflict("receipt_unavailable", "执行收据签发失败，请稍后重试");
    }
    return completed ? Optional.of(written.get()) : Optional.empty();
  }

  /**
   * 能否为审批转 ALLOW 签发执行收据。只有 V6.35.0 之前开出、没有 {@code action_hash} 的旧决策不签发
   * （ALLOW 但无收据，consume 时 {@code not_allowed}，ADR 0040 §12 D10 不回填）。策略版本不是前提：
   * aster-cloud {@code evaluateSource} 锚定的 from-evidence 决策没有已保存版本，收据载荷省略 {@code policyVersionId}
   * 照常签发（ADR 0040 §12 D1 / D9）。
   */
  private static boolean canIssue(GuardDecisionEntity decision) {
    if (decision.actionHash != null) {
      return true;
    }
    LOG.warnf("Action Guard 旧决策（无 action_hash）审批转 ALLOW，不签发执行收据: decision=%s", decision.id);
    return false;
  }

  private AppendedRecord appendEvent(GuardApprovalEntity approval, String decisionReceiptHash) {
    try {
      return appender.appendSync(receiptBuilder.approvalEvent(approval, decisionReceiptHash));
    } catch (AuditChainAppender.AuditAppendException e) {
      LOG.errorf(e, "Action Guard 审批事件写入失败，审批状态不变: approval=%s", approval.id);
      throw conflict("receipt_unavailable", "审批事件写入审计链失败，请稍后重试");
    }
  }

  /** 被并发处理后重读审批行，使错误体反映它此刻的终态（如签发方吊销导致的 EXPIRED）。 */
  private GuardApprovalConflictException notPendingNow(String tenantId, UUID approvalId) {
    return store.findApproval(tenantId, approvalId).map(GuardApprovalService::notPending)
        .orElseGet(() -> conflict("approval_not_pending", "审批已处理或已过期: " + approvalId));
  }

  /** {@code approval_not_pending}；审批行带 {@code expiredReason} 时并入错误体。 */
  private static GuardApprovalConflictException notPending(GuardApprovalEntity current) {
    String message = "审批已处理或已过期: " + current.id;
    return current.expiredReason == null
        ? conflict("approval_not_pending", message)
        : conflict("approval_not_pending", message, Map.of("expiredReason", current.expiredReason));
  }

  private static GuardApprovalConflictException conflict(String code, String message) {
    return new GuardApprovalConflictException(code, message);
  }

  private static GuardApprovalConflictException conflict(String code, String message, Map<String, Object> details) {
    return new GuardApprovalConflictException(code, message, details);
  }
}
