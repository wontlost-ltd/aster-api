package io.aster.guard.provenance;

import io.aster.audit.chain.AuditChainAppender;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.LockModeType;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardApprovalService;
import io.aster.guard.GuardApprovalService.ExpiryOutcome;
import io.aster.guard.GuardDecisionStore;
import io.aster.guard.GuardReceiptBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
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
 * <p>每次吊销恰好一条事件：禁用（{@code markRevoked}）记下 {@code revoked_at / revoked_by}；事件与
 * {@code revocation_audit_id} 在同一事务内写入（先以 {@code FOR UPDATE} 锁签发方行并复核该列仍为 NULL，再在本事务内追加），
 * 因此并发的补写彼此串行、崩溃或追加失败都不会留下「有事件无标记」或「两条事件」。{@code affectedDecisionIds} 在写事件时
 * 按库查询（本租户因该签发方吊销而 EXPIRED 的审批），包含审批时就地过期的项，与本次调用迁移了哪些无关。
 *
 * <p>幂等与重试：对已禁用的签发方再次调用（重复 DELETE / PUT {@code enabled=false}）时，仍扫描一遍依赖它的 PENDING
 * 并补齐上一次中途失败留下的审批；事件已入链则不再写。事件尚未入链（追加失败或进程在禁用后崩溃）的行由同一次重试，
 * 或过期调度的补写扫描（沿用存储的 {@code revoked_by}）补上。新建即禁用的行没有吊销时刻，不写事件。
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
   * 吊销 {@code (tenantId, issuer, kid)} 并联动处置依赖它的待审批；已吊销的签发方再次调用时补齐遗留审批与未入链的事件。
   *
   * @param performedBy 操作者（管理端点为签名内的用户；{@code not_after} 到期为 {@code SYSTEM}）。只在本次完成禁用时记入
   *                    {@code revoked_by}；事件一律取存储的 {@code revoked_by}
   * @return 本次转为 EXPIRED 的审批数
   * @throws FactIssuerRevocationIncompleteException 有审批迁移失败或吊销事件未入链：签发方已禁用，重试可补齐
   */
  public int revoke(String tenantId, String issuer, String kid, String performedBy) {
    issuers.markRevoked(tenantId, issuer, kid, performedBy, clock.instant());
    return cascade(tenantId, issuer, kid);
  }

  /**
   * 调度入口（与审批过期同一轮）：(1) 把仍启用但 {@code now >= notAfter} 的签发方转为吊销（{@code performedBy=SYSTEM}）；
   * (2) 补写已吊销但事件尚未入链的签发方。每类每轮至多 {@link #LAPSED_BATCH} 个；单个失败只记日志，留给下一轮。
   *
   * @return 本轮转为 EXPIRED 的审批总数
   */
  public int revokeLapsed() {
    int expired = 0;
    for (GuardFactIssuerEntity row : issuers.findLapsedEnabled(clock.instant(), LAPSED_BATCH)) {
      expired += tryRevoke(row, GuardReceiptBuilder.SYSTEM_ACTOR);
    }
    for (GuardFactIssuerEntity row : issuers.findRevocationEventPending(LAPSED_BATCH)) {
      expired += tryRevoke(row, row.revokedBy);
    }
    return expired;
  }

  private int tryRevoke(GuardFactIssuerEntity row, String performedBy) {
    try {
      return revoke(row.tenantId, row.issuer, row.kid, performedBy);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Action Guard 签发方吊销联动未完成，下一轮重试: tenant=%s issuer=%s kid=%s", row.tenantId, row.issuer,
          row.kid);
      return 0;
    }
  }

  private int cascade(String tenantId, String issuer, String kid) {
    int expired = 0;
    int failed = 0;
    for (GuardApprovalEntity approval : store.findPendingByFactIssuer(tenantId, issuer + "#" + kid)) {
      ExpiryOutcome outcome = approvals.expire(approval, clock.instant(), GuardApprovalEntity.EXPIRED_FACT_ISSUER_REVOKED);
      if (outcome == ExpiryOutcome.EXPIRED) {
        expired++;
      } else if (outcome == ExpiryOutcome.FAILED) {
        failed++;
      }
    }
    recordRevokedEvent(tenantId, issuer, kid);
    if (failed > 0) {
      throw new FactIssuerRevocationIncompleteException(
          "签发方已吊销，但有 " + failed + " 条待审批未能转为 EXPIRED，请重试");
    }
    return expired;
  }

  /**
   * 若该签发方已吊销且事件尚未入链：锁行、复核、按库计算受影响决策、在本事务内追加事件并记下 {@code revocation_audit_id}。
   * 事务内追加只持有本租户链锁与该签发方行锁，审批迁移事务不取链锁，不构成环。
   */
  private void recordRevokedEvent(String tenantId, String issuer, String kid) {
    try {
      QuarkusTransaction.requiringNew().run(() -> {
        GuardFactIssuerEntity row = GuardFactIssuerEntity.findById(new GuardFactIssuerEntity.Key(tenantId, issuer, kid),
            LockModeType.PESSIMISTIC_WRITE);
        if (row == null || row.revokedAt == null || row.revocationAuditId != null) {
          return;
        }
        List<UUID> affected = store.findRevokedDecisionIds(tenantId, issuer + "#" + kid);
        row.revocationAuditId = appender.appendInCurrentTransaction(
            receiptBuilder.issuerRevokedEvent(tenantId, issuer, kid, row.revokedBy, affected)).auditId();
      });
    } catch (RuntimeException e) {
      LOG.errorf(e, "Action Guard 签发方吊销事件写入失败，留待重试: tenant=%s issuer=%s kid=%s", tenantId, issuer, kid);
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
