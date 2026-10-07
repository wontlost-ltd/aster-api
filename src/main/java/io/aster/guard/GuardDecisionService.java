package io.aster.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardActionRequest;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardDecisionResponse.ApprovalView;
import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.guard.api.GuardDecisionResponse.ReceiptView;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.governance.VerdictView;
import io.aster.policy.repository.PolicySourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Action Guard 决策编排（ADR 0040 §2–§5）：幂等查重 → 扁平化 → 解析活跃版本 → 评估 → 同步收据 → 决策行与待审批行同一事务落库。
 *
 * <p>fail-closed：非 Verdict、评估异常 / 超时、收据写入失败一律判 DENY 并给出 reasonCode；
 * 收据写入失败时不创建待审批，保证「没有入链的决策不会被人批准放行」。
 * 只有策略不存在与请求非法以异常形式外抛（404 / 400）。
 *
 * <p>阻塞方法：须在非事务的 worker 线程调用——收据追加与各次持久化各自开独立事务，
 * 外层若持有事务（尤其持有同租户链锁）会自死锁。
 */
@ApplicationScoped
public class GuardDecisionService {
  private static final Logger LOG = Logger.getLogger(GuardDecisionService.class);
  static final Duration EVALUATION_TIMEOUT = Duration.ofSeconds(10);
  static final int MAX_COLUMN_TEXT = 255;
  /** waitSeconds 上限与轮询间隔（ADR 0040 §3.1）：等待只是省掉客户端轮询，不能长时间占住 worker 线程。 */
  static final int MAX_WAIT_SECONDS = 30;
  static final long WAIT_POLL_MILLIS = 500;

  /** 活跃版本查询的事务边界：生产为 requiringNew，单测直接执行（不启动 Quarkus）。 */
  @FunctionalInterface
  interface VersionLookupTx {
    Optional<PolicyVersion> run(Supplier<Optional<PolicyVersion>> lookup);
  }

  private PolicyEvaluationService evaluationService;
  private PolicySourceRepository policySourceRepository;
  private AuditChainAppender appender;
  private GuardDecisionStore store;
  private GuardActionMapper mapper;
  private GuardReceiptBuilder receiptBuilder;
  private ObjectMapper objectMapper;
  private Duration approvalTtl;
  private VersionLookupTx versionLookupTx;

  /** CDI 客户端代理所需。 */
  protected GuardDecisionService() {
  }

  @Inject
  public GuardDecisionService(PolicyEvaluationService evaluationService, PolicySourceRepository policySourceRepository,
                              AuditChainAppender appender, GuardDecisionStore store, GuardActionMapper mapper,
                              GuardReceiptBuilder receiptBuilder, ObjectMapper objectMapper,
                              @ConfigProperty(name = "aster.guard.approval-ttl", defaultValue = "PT24H") Duration approvalTtl) {
    this(evaluationService, policySourceRepository, appender, store, mapper, receiptBuilder, objectMapper, approvalTtl,
        lookup -> QuarkusTransaction.requiringNew().call(lookup::get));
  }

  GuardDecisionService(PolicyEvaluationService evaluationService, PolicySourceRepository policySourceRepository,
                       AuditChainAppender appender, GuardDecisionStore store, GuardActionMapper mapper,
                       GuardReceiptBuilder receiptBuilder, ObjectMapper objectMapper, Duration approvalTtl,
                       VersionLookupTx versionLookupTx) {
    this.evaluationService = evaluationService;
    this.policySourceRepository = policySourceRepository;
    this.appender = appender;
    this.store = store;
    this.mapper = mapper;
    this.receiptBuilder = receiptBuilder;
    this.objectMapper = objectMapper;
    this.approvalTtl = approvalTtl;
    this.versionLookupTx = versionLookupTx;
  }

  /**
   * 对一个提议动作作出决策。同一租户同一幂等键重放返回首次响应（含同一 decisionId 与收据）；
   * 并发同键时后写者回读先到者的响应。结果为 PENDING 且 {@code waitSeconds > 0} 时，
   * 在时限内等待人工结论，返回等待结束时的最新响应。
   *
   * @throws GuardInvalidActionException  请求体校验失败
   * @throws GuardPolicyNotFoundException 策略不存在或无活跃版本
   */
  public GuardDecisionResponse decide(String tenantId, String performedBy, GuardActionRequest req, String rawIdempotencyKey) {
    GuardDecisionResponse response = decideOnce(tenantId, performedBy, req, rawIdempotencyKey);
    return awaitApproval(tenantId, response, clampWaitSeconds(req == null ? null : req.waitSeconds()));
  }

  private GuardDecisionResponse decideOnce(String tenantId, String performedBy, GuardActionRequest req,
                                           String rawIdempotencyKey) {
    String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
    Optional<GuardDecisionResponse> replay = findByIdempotencyKey(tenantId, idempotencyKey);
    if (replay.isPresent()) {
      return replay.get();
    }
    validateAddress(req);
    Map<String, Object> flat = mapper.flatten(req.action());
    String contextHash = mapper.contextHash(req.action().context());
    PolicyVersionView version = resolveVersion(tenantId, req.policyModule(), req.policyFunction());
    GuardDecisionDraft draft = evaluate(new GuardDecisionDraft(tenantId, req.policyModule(), req.policyFunction(),
        idempotencyKey, flat, contextHash, version, null, null, null, null, null, null));

    UUID decisionId = UUID.randomUUID();
    UUID approvalId = draft.needsApproval() ? UUID.randomUUID() : null;
    AppendedRecord receipt = appendReceipt(draft, decisionId, approvalId, performedBy);
    if (receipt == null) {
      draft = draft.deny(GuardReasonCode.RECEIPT_UNAVAILABLE);
      approvalId = null;
    }
    Instant expiresAt = Instant.now().plus(approvalTtl);
    GuardDecisionResponse response = buildResponse(decisionId, draft, receipt, approvalId, expiresAt);
    GuardApprovalEntity approval = approvalId == null ? null : toApproval(approvalId, decisionId, draft, expiresAt);
    try {
      store.saveDecision(toEntity(decisionId, draft, receipt, response), approval);
    } catch (GuardIdempotencyRaceException race) {
      return findByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(() -> race);
    }
    return response;
  }

  /** 回读决策的当前响应（含审批结论与读取时的过期判定）；跨租户返回 empty。 */
  public Optional<GuardDecisionResponse> get(String tenantId, UUID decisionId) {
    return store.findDecision(tenantId, decisionId).map(this::currentResponse);
  }

  /** null 与负数为 0（不等待），超过上限按 {@link #MAX_WAIT_SECONDS}。 */
  static int clampWaitSeconds(Integer waitSeconds) {
    return waitSeconds == null ? 0 : Math.max(0, Math.min(MAX_WAIT_SECONDS, waitSeconds));
  }

  /** 每 {@link #WAIT_POLL_MILLIS} 回读一次，直到不再 PENDING 或到时；线程被中断则立即返回已知最新响应。 */
  private GuardDecisionResponse awaitApproval(String tenantId, GuardDecisionResponse response, int waitSeconds) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
    GuardDecisionResponse latest = response;
    while (GuardDecisionDraft.PENDING.equals(latest.effectiveOutcome()) && System.nanoTime() < deadline) {
      long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      try {
        Thread.sleep(Math.max(1, Math.min(WAIT_POLL_MILLIS, remainingMillis)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return latest;
      }
      latest = get(tenantId, UUID.fromString(latest.decisionId())).orElse(latest);
    }
    return latest;
  }

  private Optional<GuardDecisionResponse> findByIdempotencyKey(String tenantId, String idempotencyKey) {
    if (idempotencyKey == null) {
      return Optional.empty();
    }
    return store.findByIdempotencyKey(tenantId, idempotencyKey).map(this::currentResponse);
  }

  /**
   * 存储响应 + 审批现状。审批迁移会同步改写 response_json，所以只有仍显示 PENDING 的响应需要再看审批行：
   * 读取时判定过期，不依赖调度器及时（ADR 0040 §4.3），不写库。
   */
  private GuardDecisionResponse currentResponse(GuardDecisionEntity e) {
    GuardDecisionResponse stored = readResponse(e);
    if (!GuardDecisionDraft.PENDING.equals(stored.effectiveOutcome())) {
      return stored;
    }
    return stored.withApprovalStatus(approvalStatus(e.id, Instant.now()));
  }

  /** PENDING 却没有审批行（原子落库后不应出现）按 EXPIRED 处理：fail-closed，且再也无法被批准。 */
  private GuardApprovalStatus approvalStatus(UUID decisionId, Instant now) {
    return store.findApprovalByDecision(decisionId)
        .map(a -> a.isExpired(now) ? GuardApprovalStatus.EXPIRED : a.status)
        .orElse(GuardApprovalStatus.EXPIRED);
  }

  /** 空白视为未提供；超长（列宽 255）或含控制字符在评估与写收据之前就拒绝。 */
  private static String normalizeIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    if (key.length() > MAX_COLUMN_TEXT || key.chars().anyMatch(Character::isISOControl)) {
      throw new GuardInvalidActionException("Idempotency-Key 超过 " + MAX_COLUMN_TEXT + " 字符或含控制字符");
    }
    return key;
  }

  private static void validateAddress(GuardActionRequest req) {
    if (req == null || isBlank(req.policyModule()) || isBlank(req.policyFunction())) {
      throw new GuardInvalidActionException("policyModule 与 policyFunction 必填");
    }
  }

  private PolicyVersionView resolveVersion(String tenantId, String module, String function) {
    PolicyVersion v = versionLookupTx.run(() -> policySourceRepository.findActiveCatalog(tenantId, module, function)
            .flatMap(catalog -> policySourceRepository.findActiveVersion(catalog.id)))
        .orElseThrow(() -> new GuardPolicyNotFoundException("策略不存在或无活跃版本: " + module + "." + function));
    return new PolicyVersionView(v.id, v.sourceHash, v.version);
  }

  /** 评估并把结果映射到草稿；传给规则的是不可变 Map（规则运行时可经 interop 写入可变集合）。 */
  private GuardDecisionDraft evaluate(GuardDecisionDraft base) {
    PolicyEvaluationResult result;
    try {
      result = evaluationService.evaluatePolicy(base.tenantId(), base.policyModule(), base.policyFunction(),
          new Object[]{Map.copyOf(base.action())}).await().atMost(EVALUATION_TIMEOUT);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Action Guard 评估失败，按 DENY 处理: %s.%s", base.policyModule(), base.policyFunction());
      return base.deny(GuardReasonCode.EVALUATION_FAILED);
    }
    if (result == null) {
      return base.deny(GuardReasonCode.EVALUATION_FAILED);
    }
    VerdictView verdict = result.getDecision();
    if (!acceptable(verdict)) {
      return base.withRule(result.getRuleId(), result.getControls()).deny(GuardReasonCode.NOT_A_VERDICT);
    }
    return base.withRule(result.getRuleId(), result.getControls()).withVerdict(verdict);
  }

  /**
   * guard 只接受已知 outcome、且 role / reason 不超过列宽的 Verdict；超长不静默截断
   * （截断后的 role 会让审批角色比对失真），而是按 not_a_verdict 拒绝。
   */
  private static boolean acceptable(VerdictView verdict) {
    return verdict != null
        && GuardDecisionDraft.VERDICT_OUTCOMES.contains(verdict.outcome())
        && fits(verdict.role())
        && fits(verdict.reason());
  }

  private static boolean fits(String s) {
    return s == null || s.length() <= MAX_COLUMN_TEXT;
  }

  /** 同步写决策收据；失败返回 null，由调用方改判 DENY receipt_unavailable。 */
  private AppendedRecord appendReceipt(GuardDecisionDraft draft, UUID decisionId, UUID approvalId, String performedBy) {
    try {
      return appender.appendSync(receiptBuilder.decisionEvent(draft, decisionId, approvalId, performedBy));
    } catch (AuditChainAppender.AuditAppendException e) {
      LOG.errorf(e, "Action Guard 收据写入失败，按 DENY 处理: decision=%s", decisionId);
      return null;
    }
  }

  private static GuardDecisionResponse buildResponse(UUID decisionId, GuardDecisionDraft draft, AppendedRecord receipt,
                                                     UUID approvalId, Instant expiresAt) {
    ApprovalView approval = approvalId == null ? null
        : new ApprovalView(expiresAt.toString(), approvalId.toString(), draft.requiredRole(), GuardApprovalStatus.PENDING.name());
    ReceiptView receiptView = receipt == null ? null
        : new ReceiptView(receipt.auditId(), receipt.currentHash(), receipt.prevHash());
    return new GuardDecisionResponse(approval, draft.controls(), decisionId.toString(), draft.effectiveOutcome(),
        draft.outcome(), draft.policyVersion(), draft.reason(), draft.reasonCodeValue(), receiptView, draft.role(),
        draft.ruleId());
  }

  private GuardDecisionEntity toEntity(UUID decisionId, GuardDecisionDraft draft, AppendedRecord receipt,
                                       GuardDecisionResponse response) {
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.id = decisionId;
    e.tenantId = draft.tenantId();
    e.idempotencyKey = draft.idempotencyKey();
    e.policyModule = draft.policyModule();
    e.policyFunction = draft.policyFunction();
    e.policyVersionId = draft.policyVersion().id();
    e.sourceHash = draft.policyVersion().sourceHash();
    e.principalId = draft.principalId();
    e.actionName = draft.actionName();
    e.resourceType = draft.resourceType();
    e.resourceId = draft.resourceId();
    e.contextHash = draft.contextHash();
    e.outcome = draft.outcome();
    e.reason = draft.reason();
    e.requiredRole = draft.requiredRole();
    e.reasonCode = draft.reasonCodeValue();
    e.ruleId = clip(draft.ruleId(), MAX_COLUMN_TEXT);
    e.controls = draft.controls().isEmpty() ? null : writeJson(draft.controls());
    e.receiptAuditId = receipt == null ? null : receipt.auditId();
    e.receiptHash = receipt == null ? null : receipt.currentHash();
    e.responseJson = writeJson(response);
    return e;
  }

  private static GuardApprovalEntity toApproval(UUID approvalId, UUID decisionId, GuardDecisionDraft draft, Instant expiresAt) {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = approvalId;
    a.tenantId = draft.tenantId();
    a.decisionId = decisionId;
    a.outcome = draft.outcome();
    a.requiredRole = draft.requiredRole();
    a.principalId = draft.principalId();
    a.status = GuardApprovalStatus.PENDING;
    a.expiresAt = expiresAt;
    return a;
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

  private static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
