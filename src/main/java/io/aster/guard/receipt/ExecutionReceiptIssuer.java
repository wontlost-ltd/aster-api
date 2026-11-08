package io.aster.guard.receipt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 执行收据签发（ADR 0040 §12 D1/D4）：签名并给出要落库的 {@code guard_receipts} 行。
 *
 * <p>直接 ALLOW 与审批转 ALLOW 两条路径共用：签发时刻截到毫秒——载荷里的时间串与库里 {@code timestamptz}
 * 往返后逐字节相同，重放与回读拿到的收据不会因精度漂移而与签名不符。
 */
@ApplicationScoped
public class ExecutionReceiptIssuer {

  /** 一次签发：响应里的收据视图与同一事务要插入的收据行。 */
  public record Issued(ExecutionReceipt receipt, GuardReceiptEntity row) {}

  private ExecutionReceiptSigner signer;
  private Clock clock;

  /** CDI 客户端代理所需。 */
  protected ExecutionReceiptIssuer() {
  }

  @Inject
  public ExecutionReceiptIssuer(ExecutionReceiptSigner signer, Clock clock) {
    this.signer = signer;
    this.clock = clock;
  }

  /** 以注入时钟的当前时刻签发（直接 ALLOW）。 */
  public Issued issueNow(UUID decisionId, String tenantId, String actionHash, long policyVersionId) {
    return issue(decisionId, tenantId, actionHash, policyVersionId, clock.instant());
  }

  /**
   * 以给定时刻签发（审批转 ALLOW 时为加锁后的 now）。
   *
   * @throws ExecutionReceiptUnavailableException 签名失败；调用方按 fail-closed 处理，不得放行
   */
  public Issued issue(UUID decisionId, String tenantId, String actionHash, long policyVersionId, Instant at) {
    Instant issuedAt = at.truncatedTo(ChronoUnit.MILLIS);
    ExecutionReceipt receipt;
    try {
      if (actionHash == null) {
        throw new IllegalArgumentException("actionHash 缺失");
      }
      receipt = signer.sign(decisionId.toString(), tenantId, actionHash, policyVersionId, issuedAt);
    } catch (RuntimeException e) {
      throw new ExecutionReceiptUnavailableException("执行收据签发失败: decision=" + decisionId, e);
    }
    GuardReceiptEntity row = new GuardReceiptEntity();
    row.decisionId = decisionId;
    row.tenantId = tenantId;
    row.actionHash = actionHash;
    row.kid = receipt.kid();
    row.issuedAt = issuedAt;
    row.expiresAt = Instant.parse(receipt.payload().expiresAt());
    row.signature = receipt.signature();
    row.createdAt = clock.instant();
    return new Issued(receipt, row);
  }
}
