package io.aster.guard.provenance;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardApprovalService;
import io.aster.guard.GuardApprovalService.ExpiryOutcome;
import io.aster.guard.GuardDecisionStore;
import io.aster.guard.GuardReceiptBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 签发方吊销联动（ADR 0040 §11 D4，fail-closed、服务端主动）：签发方被禁用（DELETE 或 PUT {@code enabled=false}）
 * 或过 {@code not_after} 时，把本租户依赖它的 PENDING 审批逐条转 EXPIRED（{@code expired_reason=fact_issuer_revoked}，
 * 走与自然过期相同的「追加审批事件 → 加锁迁移」），再在租户链上追加一条 {@code GUARD_FACT_ISSUER_REVOKED}。
 *
 * <p>顺序是先把签发方行置为禁用、再找依赖它的审批：禁用提交之后新决策已无法引用该签发方的事实（验签即 400），
 * 找出的集合不会再增长；与决策落库并发而漏掉的审批，由审批时的就地检查兜底（{@code GuardApprovalService}）。
 *
 * <p>幂等与重试：禁用是条件更新，只有完成「启用 → 禁用」的那次调用记为吊销；对已禁用的签发方再次调用时
 * 仍扫描一遍依赖它的 PENDING（正常情况下已为空，因此不写事件、不迁移任何审批），从而上一次联动中途失败
 * 留下的审批会在重试时被补齐，且只在确有新迁移时追加事件。
 *
 * <p>阻塞方法：审计追加要求非事务的 worker 线程，调用方不得在外层事务中调用。
 */
@ApplicationScoped
public class FactIssuerRevocationService {
  private static final Logger LOG = Logger.getLogger(FactIssuerRevocationService.class);
  /** 每轮调度至多处理的过期签发方数；剩余的留给下一轮。 */
  static final int LAPSED_BATCH = 100;

  private FactIssuerStore issuers;
  private GuardDecisionStore store;
  private GuardApprovalService approvals;
  private AuditChainAppender appender;
  private GuardReceiptBuilder receiptBuilder;
  private Clock clock;

  /** CDI 客户端代理所需。 */
  protected FactIssuerRevocationService() {
  }

  @Inject
  public FactIssuerRevocationService(FactIssuerStore issuers, GuardDecisionStore store, GuardApprovalService approvals,
                                     AuditChainAppender appender, GuardReceiptBuilder receiptBuilder, Clock clock) {
    this.issuers = issuers;
    this.store = store;
    this.approvals = approvals;
    this.appender = appender;
    this.receiptBuilder = receiptBuilder;
    this.clock = clock;
  }

  /**
   * 吊销 {@code (tenantId, issuer, kid)} 并联动处置依赖它的待审批。
   *
   * @param performedBy 操作者（管理端点为签名内的用户；{@code not_after} 到期为 {@code SYSTEM}）
   * @return 本次转为 EXPIRED 的审批数
   * @throws FactIssuerRevocationIncompleteException 有审批迁移失败或吊销事件未入链：签发方已禁用，重试可补齐
   */
  public int revoke(String tenantId, String issuer, String kid, String performedBy) {
    boolean revokedNow = issuers.markRevoked(tenantId, issuer, kid);
    return cascade(tenantId, issuer, kid, performedBy, revokedNow);
  }

  /**
   * 调度入口：把仍启用但 {@code now >= notAfter} 的签发方转为吊销（{@code performedBy=SYSTEM}），每轮至多
   * {@link #LAPSED_BATCH} 个。单个签发方失败只记日志，不影响其余。
   *
   * @return 本轮转为 EXPIRED 的审批总数
   */
  public int revokeLapsed() {
    int expired = 0;
    for (GuardFactIssuerEntity row : issuers.findLapsedEnabled(clock.instant(), LAPSED_BATCH)) {
      try {
        expired += revoke(row.tenantId, row.issuer, row.kid, GuardReceiptBuilder.SYSTEM_ACTOR);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Action Guard 签发方到期吊销联动未完成: tenant=%s issuer=%s kid=%s", row.tenantId, row.issuer,
            row.kid);
      }
    }
    return expired;
  }

  private int cascade(String tenantId, String issuer, String kid, String performedBy, boolean revokedNow) {
    List<UUID> affected = new ArrayList<>();
    int failed = 0;
    for (GuardApprovalEntity approval : store.findPendingByFactIssuer(tenantId, issuer + "#" + kid)) {
      ExpiryOutcome outcome = approvals.expire(approval, clock.instant(), GuardApprovalEntity.EXPIRED_FACT_ISSUER_REVOKED);
      if (outcome == ExpiryOutcome.EXPIRED) {
        affected.add(approval.decisionId);
      } else if (outcome == ExpiryOutcome.FAILED) {
        failed++;
      }
    }
    if (revokedNow || !affected.isEmpty()) {
      appendRevokedEvent(tenantId, issuer, kid, performedBy, affected);
    }
    if (failed > 0) {
      throw new FactIssuerRevocationIncompleteException(
          "签发方已吊销，但有 " + failed + " 条待审批未能转为 EXPIRED，请重试");
    }
    return affected.size();
  }

  private void appendRevokedEvent(String tenantId, String issuer, String kid, String performedBy, List<UUID> affected) {
    try {
      appender.appendSync(receiptBuilder.issuerRevokedEvent(tenantId, issuer, kid, performedBy, affected));
    } catch (AuditChainAppender.AuditAppendException e) {
      LOG.errorf(e, "Action Guard 签发方吊销事件写入失败: tenant=%s issuer=%s kid=%s affected=%d", tenantId, issuer, kid,
          affected.size());
      throw new FactIssuerRevocationIncompleteException("签发方已吊销，但吊销事件写入审计链失败，请重试");
    }
  }

  /** 签发方已禁用，但联动未完成（审批迁移或事件入链失败）；对外映射为 503 {@code receipt_unavailable}。 */
  public static final class FactIssuerRevocationIncompleteException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FactIssuerRevocationIncompleteException(String message) {
      super(message);
    }
  }
}
