package io.aster.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardActionRequest;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.guard.provenance.FactVerifier;
import io.aster.guard.provenance.FactVerifier.VerifiedFacts;
import io.aster.guard.provenance.GuardInvalidFactException;
import io.aster.guard.provenance.InputSourceResolver;
import io.aster.guard.provenance.InputSources;
import io.aster.guard.provenance.VerifiedInputsGate;
import io.aster.guard.receipt.ExecutionReceiptIssuer;
import io.aster.guard.receipt.ExecutionReceiptUnavailableException;
import io.aster.guard.snapshot.DecisionInputFactory;
import io.aster.guard.snapshot.GuardDecisionInputEntity;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.governance.InputClassification;
import io.aster.policy.governance.RuleMetadata;
import io.aster.policy.governance.VerdictView;
import io.aster.policy.repository.PolicySourceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.aster.guard.GuardDecisionAssembler.buildResponse;
import static io.aster.guard.GuardDecisionAssembler.declaredAgent;
import static io.aster.guard.GuardDecisionAssembler.toApproval;
import static io.aster.guard.GuardText.isBlank;

/**
 * Action Guard 决策编排（ADR 0040 §2–§5）：扁平化 → 签名事实验签与逐字段来源判定（§11 D2 / D4）→ 幂等查重（指纹比对）
 * → 解析活跃版本 → 档案来源门（§11 D5）→ 评估 → 同步收据 → 决策行与待审批行同一事务落库。
 * 来源不传给规则，只进响应、决策行与收据。
 *
 * <p>fail-closed：非 Verdict、评估异常 / 超时、收据写入失败、执行收据签发失败一律判 DENY 并给出 reasonCode；
 * 收据写入失败时不创建待审批，保证「没有入链的决策不会被人批准放行」。
 * ALLOW 在写链收据之前签发执行收据（ADR 0040 §12 D1 (1)），签发失败时入链的就已是 DENY，链与决策行一致。
 * 只有策略不存在与请求非法以异常形式外抛（404 / 400）。
 *
 * <p>阻塞方法：须在非事务的 worker 线程调用——收据追加与各次持久化各自开独立事务，
 * 外层若持有事务（尤其持有同租户链锁）会自死锁。
 */
@ApplicationScoped
public class GuardDecisionService {
  private static final Logger LOG = Logger.getLogger(GuardDecisionService.class);
  static final Duration EVALUATION_TIMEOUT = Duration.ofSeconds(10);
  static final int MAX_COLUMN_TEXT = GuardDecisionAssembler.MAX_COLUMN_TEXT;
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
  private GuardJson json;
  private GuardDecisionAssembler assembler;
  private ExecutionReceiptIssuer receiptIssuer;
  private FactVerifier factVerifier;
  private InputSourceResolver inputSourceResolver;
  private Duration approvalTtl;
  private VersionLookupTx versionLookupTx;
  private Counter metadataUnavailable;

  /** CDI 客户端代理所需。 */
  protected GuardDecisionService() {
  }

  @Inject
  public GuardDecisionService(PolicyEvaluationService evaluationService, PolicySourceRepository policySourceRepository,
                              AuditChainAppender appender, GuardDecisionStore store, GuardActionMapper mapper,
                              GuardReceiptBuilder receiptBuilder, ObjectMapper objectMapper,
                              ExecutionReceiptIssuer receiptIssuer, FactVerifier factVerifier,
                              InputSourceResolver inputSourceResolver, MeterRegistry meterRegistry,
                              @ConfigProperty(name = "aster.guard.approval-ttl", defaultValue = "PT24H") Duration approvalTtl) {
    this(evaluationService, policySourceRepository, appender, store, mapper, receiptBuilder, objectMapper, receiptIssuer,
        factVerifier, inputSourceResolver, meterRegistry, approvalTtl,
        lookup -> QuarkusTransaction.requiringNew().call(lookup::get));
  }

  GuardDecisionService(PolicyEvaluationService evaluationService, PolicySourceRepository policySourceRepository,
                       AuditChainAppender appender, GuardDecisionStore store, GuardActionMapper mapper,
                       GuardReceiptBuilder receiptBuilder, ObjectMapper objectMapper,
                       ExecutionReceiptIssuer receiptIssuer, FactVerifier factVerifier,
                       InputSourceResolver inputSourceResolver, MeterRegistry meterRegistry, Duration approvalTtl,
                       VersionLookupTx versionLookupTx) {
    this.evaluationService = evaluationService;
    this.policySourceRepository = policySourceRepository;
    this.appender = appender;
    this.store = store;
    this.mapper = mapper;
    this.receiptBuilder = receiptBuilder;
    this.json = new GuardJson(objectMapper);
    this.assembler = new GuardDecisionAssembler(json);
    this.receiptIssuer = receiptIssuer;
    this.factVerifier = factVerifier;
    this.inputSourceResolver = inputSourceResolver;
    this.approvalTtl = approvalTtl;
    this.versionLookupTx = versionLookupTx;
    this.metadataUnavailable = GuardMetrics.ruleMetadataUnavailable(meterRegistry, "decisions");
  }

  /**
   * 对一个提议动作作出决策。同一租户同一幂等键重放同一决策（同一 decisionId 与收据，审批状态取当前值）；
   * 同键但动作指纹不同则拒绝；并发同键时后写者回读先到者的决策。结果为 PENDING 且 {@code waitSeconds > 0} 时，
   * 在时限内等待人工结论，返回等待结束时的最新响应。
   *
   * @param performedBy 请求方用户，与 {@code apiKeyId} 一起记入决策行，供审批四眼校验
   * @param apiKeyId    已验证的 API key（未经 API key 鉴权时为 null）
   * @param actionHash  请求体 {@code action} 原文的 {@code jcs-sha256/v1}（{@link GuardRequestBodyReader}）；
   *                    不参与幂等指纹（ADR 0040 §3.1 七项不变）
   * @param rawAction   请求体 {@code action} 原文树，其中的 {@code facts} 在幂等查键之前验签；null 视为无签名事实
   * @throws GuardInvalidFactException          签名事实验签失败（先于幂等查键，所以不会被重放掩盖）
   * @throws GuardInvalidActionException        请求体或幂等键校验失败
   * @throws GuardIdempotencyMismatchException  幂等键已绑定另一个提议动作
   * @throws GuardPolicyNotFoundException       策略不存在或无活跃版本
   */
  public GuardDecisionResponse decide(String tenantId, String performedBy, String apiKeyId, GuardActionRequest req,
                                      String actionHash, JsonNode rawAction, String rawIdempotencyKey) {
    return decide(tenantId, performedBy, apiKeyId, Set.of(), req, actionHash, rawAction, rawIdempotencyKey);
  }

  /**
   * 同上，并带提交者的已验证业务角色（{@code RequestIdentityResolver.businessRoles()}）：
   * {@code principal.roles} 只有在这些角色之内才能被服务端证实（ADR 0040 §11 D2 (a)）；不带时 roles 恒为 declared。
   */
  public GuardDecisionResponse decide(String tenantId, String performedBy, String apiKeyId, Set<String> businessRoles,
                                      GuardActionRequest req, String actionHash, JsonNode rawAction,
                                      String rawIdempotencyKey) {
    GuardDecisionResponse response = decideOnce(tenantId, performedBy, apiKeyId, businessRoles, req, actionHash,
        rawAction, rawIdempotencyKey);
    return awaitApproval(tenantId, response, clampWaitSeconds(req == null ? null : req.waitSeconds()));
  }

  private GuardDecisionResponse decideOnce(String tenantId, String performedBy, String apiKeyId,
                                           Set<String> businessRoles, GuardActionRequest req, String actionHash,
                                           JsonNode rawAction, String rawIdempotencyKey) {
    String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
    validateAddress(req);
    Map<String, Object> flat = mapper.flatten(req.action());
    // 本次请求自带的事实必须有效：验签失败即 400，先于幂等查键（ADR 0040 §11 D4），重放不能替无效事实放行。
    // 来源只属于首次决策：重放返回存储的 inputSources，本次请求的事实不改写它（facts 不在幂等指纹里）
    VerifiedFacts facts = factVerifier.verify(tenantId, rawAction);
    InputSources sources = InputSourceResolver.resolve(req.action(), facts, performedBy, businessRoles);
    GuardDecisionDraft base = new GuardDecisionDraft(tenantId, req.policyModule(), req.policyFunction(),
        idempotencyKey, flat, mapper.contextHash(req.action().context()), actionHash, null, null, null, null, null, null,
        null, declaredAgent(req.action().agent()), sources);
    IdempotencyFingerprint fingerprint = IdempotencyFingerprint.of(base);
    Optional<GuardDecisionResponse> replay = findByIdempotencyKey(tenantId, idempotencyKey, fingerprint);
    if (replay.isPresent()) {
      return replay.get();
    }
    PolicyVersionView version = resolveVersion(tenantId, req.policyModule(), req.policyFunction());
    GuardDecisionDraft versioned = base.withPolicyVersion(version);
    GuardDecisionDraft draft = gate(versioned, req.action()).orElseGet(() -> evaluate(versioned));

    UUID decisionId = UUID.randomUUID();
    ExecutionReceiptIssuer.Issued issued = draft.allowed() ? issueReceipt(draft, decisionId) : null;
    if (draft.allowed() && issued == null) {
      draft = draft.deny(GuardReasonCode.RECEIPT_UNAVAILABLE);
    }
    UUID approvalId = draft.needsApproval() ? UUID.randomUUID() : null;
    AppendedRecord receipt = appendReceipt(draft, decisionId, approvalId, performedBy);
    if (receipt == null) {
      draft = draft.deny(GuardReasonCode.RECEIPT_UNAVAILABLE);
      approvalId = null;
      issued = null;
    }
    Instant expiresAt = Instant.now().plus(approvalTtl);
    GuardDecisionResponse response = buildResponse(decisionId, draft, receipt, approvalId, expiresAt,
        issued == null ? null : issued.receipt());
    GuardApprovalEntity approval = approvalId == null ? null : toApproval(approvalId, decisionId, draft, expiresAt);
    // 输入快照只为真正建了审批行的决策写（ADR 0042 §12 D1）；门 DENY 与 receipt_unavailable 没有审批行，也不写
    GuardDecisionInputEntity input = approval == null ? null
        : DecisionInputFactory.direct(decisionId, tenantId, req.action(), draft.contextHash(), classificationOf(draft));
    try {
      store.saveDecision(assembler.toEntity(decisionId, draft, receipt, response, performedBy, apiKeyId), approval,
          issued == null ? null : issued.row(), input);
    } catch (GuardIdempotencyRaceException race) {
      return findByIdempotencyKey(tenantId, idempotencyKey, fingerprint).orElseThrow(() -> race);
    }
    return response;
  }

  /**
   * 幂等指纹（ADR 0040 §3.1）：同一键只能重放同一个提议动作。比对策略地址、主体、动作、资源与 context 摘要；
   * principal.type / roles / agent.model 不参与（它们不改变「对什么做什么」）。
   */
  record IdempotencyFingerprint(String policyModule, String policyFunction, String principalId, String actionName,
                                String resourceType, String resourceId, String contextHash) {
    static IdempotencyFingerprint of(GuardDecisionDraft d) {
      return new IdempotencyFingerprint(d.policyModule(), d.policyFunction(), d.principalId(), d.actionName(),
          d.resourceType(), d.resourceId(), d.contextHash());
    }

    static IdempotencyFingerprint of(GuardDecisionEntity e) {
      return new IdempotencyFingerprint(e.policyModule, e.policyFunction, e.principalId, e.actionName,
          e.resourceType, e.resourceId, e.contextHash);
    }
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

  /** 键命中且指纹一致才重放；指纹不一致说明调用方把同一个键用在了另一个动作上。 */
  private Optional<GuardDecisionResponse> findByIdempotencyKey(String tenantId, String idempotencyKey,
                                                               IdempotencyFingerprint fingerprint) {
    if (idempotencyKey == null) {
      return Optional.empty();
    }
    Optional<GuardDecisionEntity> stored = store.findByIdempotencyKey(tenantId, idempotencyKey);
    if (stored.isPresent() && !fingerprint.equals(IdempotencyFingerprint.of(stored.get()))) {
      throw new GuardIdempotencyMismatchException("Idempotency-Key 已用于另一个提议动作，请为新动作使用新键");
    }
    return stored.map(this::currentResponse);
  }

  /**
   * 存储响应 + 审批现状 + 事实现状。审批迁移会同步改写 response_json，所以只有仍显示 PENDING 的响应需要再看审批行：
   * 读取时判定过期，不依赖调度器及时（ADR 0040 §4.3），不写库。首次决策的事实已失效时追加
   * {@code inputSources.factsStale}（ADR 0040 §11 D4）：不重验、不改判、不写事件、不改写 response_json。
   */
  private GuardDecisionResponse currentResponse(GuardDecisionEntity e) {
    GuardDecisionResponse stored = json.readResponse(e);
    if (inputSourceResolver.factsStale(e.tenantId, e.factExpiresAt, json.readFactIssuers(e))) {
      stored = stored.withFactsStale();
    }
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

  /**
   * 档案来源门（ADR 0040 §11 D5）：档案要求 verifiedInputs 而规则引用的输入未全部 verified 时，不评估、直接 DENY
   * （reason 为 null，收据列出 unverifiedFields）；之后照常写收据与决策行，DENY 不建审批、不签执行收据。
   * 读不到规则元数据（编译失败等，评估也会以同一原因失败）按 evaluation_failed 拒绝并计数，同样不执行规则。
   * 档案不要求时返回 empty，由 {@link #evaluate} 照旧评估。元数据按决策已解析的版本读取，与评估同一版本。
   */
  private Optional<GuardDecisionDraft> gate(GuardDecisionDraft base, GuardActionRequest.Action action) {
    RuleMetadata meta;
    try {
      meta = evaluationService.ruleMetadata(base.tenantId(), base.policyModule(), base.policyFunction(),
          versionIdOf(base));
    } catch (RuntimeException e) {
      metadataUnavailable.increment();
      LOG.warnf("Action Guard 读取规则元数据失败，按 DENY 处理: %s.%s: %s: %s", base.policyModule(),
          base.policyFunction(), e.getClass().getSimpleName(), e.getMessage());
      LOG.debugf(e, "Action Guard 读取规则元数据失败堆栈: %s.%s", base.policyModule(), base.policyFunction());
      return Optional.of(base.deny(GuardReasonCode.EVALUATION_FAILED));
    }
    return VerifiedInputsGate.check(meta, base.inputSources(), VerifiedInputsGate.presentLeafPaths(action))
        .map(denial -> base.withRule(meta.ruleId(), meta.controls())
            .denyUnverified(denial.code(), denial.unverifiedFields()));
  }

  /**
   * 决策所用版本的 guard 形状输入分类（ADR 0042 §12 D2，{@code marks_source = version}）：与 {@link #gate} 读元数据同一版本、
   * 同一编译产物 memo。读不到只降级为 null（视图 {@code none}，全部 context 打码），不影响决策。
   */
  private InputClassification classificationOf(GuardDecisionDraft draft) {
    try {
      return evaluationService.guardInputClassification(draft.tenantId(), draft.policyModule(), draft.policyFunction(),
          versionIdOf(draft), GuardActionMapper.FLAT_KEY_PATHS);
    } catch (RuntimeException e) {
      LOG.warnf("Action Guard 读取输入分类失败，审批视图按无标记处理: %s.%s: %s", draft.policyModule(),
          draft.policyFunction(), e.toString());
      return null;
    }
  }

  /** 决策已解析的活跃版本 id（{@code String.valueOf(PolicyVersion.id)}，与编译缓存键同形）。 */
  private static String versionIdOf(GuardDecisionDraft draft) {
    return draft.policyVersion() == null || draft.policyVersion().id() == null
        ? null : String.valueOf(draft.policyVersion().id());
  }

  /**
   * 评估并把结果映射到草稿；传给规则的是不可变 Map（规则运行时可经 interop 写入可变集合）。
   * 按 {@link #gate} 读元数据时的同一版本评估。
   * 不用 {@code Map.copyOf}：它拒绝 null 值且打乱键序，而 context 允许 null 叶子（已由 mapper 递归冻结）。
   */
  private GuardDecisionDraft evaluate(GuardDecisionDraft base) {
    PolicyEvaluationResult result;
    try {
      result = evaluationService.evaluatePolicyAtVersion(base.tenantId(), base.policyModule(), base.policyFunction(),
          versionIdOf(base), new Object[]{Collections.unmodifiableMap(new LinkedHashMap<>(base.action()))})
          .await().atMost(EVALUATION_TIMEOUT);
    } catch (RuntimeException e) {
      // 调用方可随意触发（如规则不接受的 context 类型）：WARN 只记一行，堆栈留给 DEBUG，避免日志被逐请求放大。
      LOG.warnf("Action Guard 评估失败，按 DENY 处理: %s.%s: %s: %s", base.policyModule(), base.policyFunction(),
          e.getClass().getSimpleName(), e.getMessage());
      LOG.debugf(e, "Action Guard 评估失败堆栈: %s.%s", base.policyModule(), base.policyFunction());
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

  /** 签发执行收据；签名失败返回 null，由调用方改判 DENY receipt_unavailable（与链收据失败同一路径）。 */
  private ExecutionReceiptIssuer.Issued issueReceipt(GuardDecisionDraft draft, UUID decisionId) {
    try {
      Long policyVersionId = draft.policyVersion() == null ? null : draft.policyVersion().id();
      return receiptIssuer.issueNow(decisionId, draft.tenantId(), draft.actionHash(), policyVersionId);
    } catch (ExecutionReceiptUnavailableException e) {
      LOG.errorf(e, "Action Guard 执行收据签发失败，按 DENY 处理: decision=%s", decisionId);
      return null;
    }
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
}
