package io.aster.guard.receipt;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.GuardDecisionService;
import io.aster.guard.GuardReceiptBuilder;
import io.aster.guard.api.ConsumeRequest;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.receipt.GuardReceiptStore.ConsumeVerdict;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 执行收据的原子一次性 consume（ADR 0040 §12 D5）。顺序固定为四步，审计追加不在持有行锁的事务里进行：
 * <ol>
 *   <li>入参校验（不合法 → {@link InvalidConsumeException}），按租户取决策（跨租户 / 不存在 → empty，不入链）；</li>
 *   <li>{@link AuditChainAppender#appendSync} 写 {@code ACTION_GUARD_CONSUME_ATTEMPT}（自身独立事务）；
 *       失败 → {@link ExecutionReceiptUnavailableException}（503 {@code receipt_unavailable}），收据行不动；</li>
 *   <li>{@link GuardReceiptStore#consume} 加锁判定；成功时以第 2 步的事件 id 为权威指针 {@code consume_audit_id}；</li>
 *   <li>写 {@code ACTION_GUARD_CONSUME_OUTCOME}（{@code consumed | rejected:<code>}）；失败只记日志——行已提交，
 *       链上只剩尝试事件是预期的崩溃窗口，对账只认 {@code consume_audit_id}。</li>
 * </ol>
 * 并发 consume 各留一条尝试事件，只有被行引用的那条生效（与审批「先入链再加锁」同法，§4.2）。
 *
 * <p>阻塞方法：须在非事务的 worker 线程调用。
 */
@ApplicationScoped
public class GuardReceiptService {
  private static final Logger LOG = Logger.getLogger(GuardReceiptService.class);
  /** 与 guard_receipts.executor_id 列宽一致：超长直接拒绝，不静默截断。 */
  static final int MAX_EXECUTOR_ID = 255;
  private static final String ALLOW = "ALLOW";
  private static final Pattern ACTION_HASH = Pattern.compile("[0-9a-f]{64}");
  /** UUID 形态（8-4-4-4-12 hex）；不用 {@link UUID#fromString}，它接受各段位数不足的写法。 */
  private static final Pattern UUID_SHAPE =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  /**
   * consume 结果。
   *
   * @param executionReceipt 决策响应中的执行收据（成功响应原样带回）；无收据的决策为 null
   */
  public record Result(ConsumeVerdict verdict, ExecutionReceipt executionReceipt) {}

  private GuardReceiptStore store;
  private AuditChainAppender appender;
  private GuardReceiptBuilder receiptBuilder;
  private GuardDecisionService decisions;
  private ReceiptSigningKeys keys;

  /** CDI 客户端代理所需。 */
  protected GuardReceiptService() {
  }

  @Inject
  public GuardReceiptService(GuardReceiptStore store, AuditChainAppender appender, GuardReceiptBuilder receiptBuilder,
                             GuardDecisionService decisions, ReceiptSigningKeys keys) {
    this.store = store;
    this.appender = appender;
    this.receiptBuilder = receiptBuilder;
    this.decisions = decisions;
    this.keys = keys;
  }

  /**
   * @param performedBy 请求身份（{@code RequestIdentityResolver.performedBy}），作为两条事件的 performed_by
   * @return empty 表示决策不存在或属于其他租户（404，不入链）
   * @throws InvalidConsumeException              入参不合法
   * @throws ExecutionReceiptUnavailableException 尝试事件未能入链，状态不变
   */
  public Optional<Result> consume(String tenantId, String performedBy, UUID decisionId, ConsumeRequest req) {
    validate(req);
    Optional<GuardDecisionResponse> decision = decisions.get(tenantId, decisionId);
    if (decision.isEmpty()) {
      return Optional.empty();
    }
    GuardReceiptEntity snapshot = store.findReceipt(tenantId, decisionId).orElseGet(() -> absent(tenantId, decisionId));
    AppendedRecord attempt = appendAttempt(snapshot, req, decision.get(), performedBy);
    ConsumeVerdict verdict = enrich(
        store.consume(tenantId, decisionId, req, attempt.auditId(), keys::isRevoked), decision.get());
    appendOutcome(tenantId, decisionId, req, verdict, attempt.currentHash(), performedBy);
    return Optional.of(new Result(verdict, decision.get().executionReceipt()));
  }

  /** 按 actionHash → attemptId → executorId 的顺序报首个不合法字段；空请求体按 actionHash 缺失处理。 */
  static void validate(ConsumeRequest req) {
    if (req == null || req.actionHash() == null || !ACTION_HASH.matcher(req.actionHash()).matches()) {
      throw new InvalidConsumeException("actionHash", "actionHash 须为 64 位小写 hex");
    }
    if (req.attemptId() == null || !UUID_SHAPE.matcher(req.attemptId()).matches()) {
      throw new InvalidConsumeException("attemptId", "attemptId 须为 UUID 形态");
    }
    String executorId = req.executorId();
    if (executorId == null || executorId.isBlank() || executorId.length() > MAX_EXECUTOR_ID
        || executorId.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidConsumeException("executorId", "executorId 须为 1–" + MAX_EXECUTOR_ID + " 字符且不含控制字符");
    }
  }

  /** 无收据行（DENY / PENDING / 旧 ALLOW）时尝试事件只带决策与租户：尝试本身同样要留痕。 */
  private static GuardReceiptEntity absent(String tenantId, UUID decisionId) {
    GuardReceiptEntity r = new GuardReceiptEntity();
    r.decisionId = decisionId;
    r.tenantId = tenantId;
    return r;
  }

  private AppendedRecord appendAttempt(GuardReceiptEntity snapshot, ConsumeRequest req,
                                       GuardDecisionResponse decision, String performedBy) {
    String decisionReceiptHash = decision.receipt() == null ? null : decision.receipt().hash();
    try {
      return appender.appendSync(receiptBuilder.consumeAttemptEvent(snapshot, req.actionHash(), req.attemptId(),
          req.executorId(), decisionReceiptHash, performedBy));
    } catch (AuditChainAppender.AuditAppendException e) {
      LOG.errorf(e, "Action Guard consume 尝试事件写入失败，收据状态不变: decision=%s", snapshot.decisionId);
      throw new ExecutionReceiptUnavailableException("consume 尝试事件写入审计链失败", e);
    }
  }

  /**
   * 补上存储层不掌握的响应字段：{@code not_allowed} 带决策当前的 effectiveOutcome（ALLOW 却无收据行即
   * V6.35.0 之前的旧决策，附 {@code legacy: true}）；{@code kid_revoked} 带当前密钥配置 revision。
   */
  private ConsumeVerdict enrich(ConsumeVerdict verdict, GuardDecisionResponse decision) {
    if (!(verdict instanceof ConsumeVerdict.Rejected rejected)) {
      return verdict;
    }
    Map<String, Object> extra = new LinkedHashMap<>(rejected.extra());
    switch (rejected.code()) {
      case GuardReceiptJpaStore.NOT_ALLOWED -> {
        extra.put("effectiveOutcome", decision.effectiveOutcome());
        if (ALLOW.equals(decision.effectiveOutcome())) {
          extra.put("legacy", true);
        }
      }
      case GuardReceiptJpaStore.KID_REVOKED -> extra.put("revision", keys.revision());
      default -> {
        return verdict;
      }
    }
    return new ConsumeVerdict.Rejected(rejected.code(), Collections.unmodifiableMap(extra));
  }

  private void appendOutcome(String tenantId, UUID decisionId, ConsumeRequest req, ConsumeVerdict verdict,
                             String attemptReceiptHash, String performedBy) {
    String outcome = verdict instanceof ConsumeVerdict.Rejected r ? "rejected:" + r.code() : "consumed";
    try {
      appender.appendSync(receiptBuilder.consumeOutcomeEvent(tenantId, decisionId, req.attemptId(), req.executorId(),
          outcome, attemptReceiptHash, performedBy));
    } catch (RuntimeException e) {
      // 行已提交：不回滚，链上只留尝试事件，查询方按 consume_audit_id 判定并标 crashed-unknown
      LOG.errorf(e, "Action Guard consume 结果事件写入失败（判定已生效）: decision=%s outcome=%s", decisionId, outcome);
    }
  }
}
