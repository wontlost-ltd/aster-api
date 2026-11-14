package io.aster.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardFromEvidenceRequest;
import io.aster.guard.provenance.FactVerifier;
import io.aster.guard.provenance.FactVerifier.VerifiedFacts;
import io.aster.guard.provenance.GuardInvalidFactException;
import io.aster.guard.api.GuardActionRequest;
import io.aster.guard.provenance.InputSourceResolver;
import io.aster.guard.provenance.ProfileRequirements;
import io.aster.guard.provenance.VerifiedInputsGate;
import io.aster.guard.snapshot.DecisionInputFactory;
import io.aster.guard.snapshot.EvaluationClassificationWriter;
import io.aster.guard.snapshot.GuardDecisionInputEntity;
import io.aster.guard.snapshot.GuardEvaluationClassificationEntity;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.EventType;
import io.aster.policy.evidence.EvaluationEvidence;
import io.aster.policy.governance.RuleMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.aster.guard.GuardDecisionAssembler.buildResponse;
import static io.aster.guard.GuardDecisionAssembler.declaredAgent;
import static io.aster.guard.GuardDecisionAssembler.toApproval;
import static io.aster.guard.GuardText.isAnonymous;
import static io.aster.guard.GuardText.isBlank;

/**
 * 从证据开决策（ADR 0042 §3.1）：以审计链上已有的 {@code POLICY_EVALUATION} 事件为锚，开一条待审批的 guard 决策。
 *
 * <p>不重新评估：结论（outcome / role / reason / ruleId / controls）与策略地址全部取自事件行。
 * 决策另写一条 {@code ACTION_GUARD_DECISION} 收据行（metadata 含 {@code evidenceCorrelationId} 指回评估事件），
 * 使收据查询与证据导出能按 decisionId 取回审批；收据写入失败按 DENY {@code receipt_unavailable} 处理。同一租户同一相关键只开一次决策，重复调用返回既有决策（{@code action} 不比对）。
 *
 * <p>与 {@code POST /decisions} 同一来源判定（ADR 0040 §11 D2 (b)(c)）：请求自带的签名事实在查既有决策之前验签，
 * 失败即 400 {@code invalid_fact}；签名内的用户即 performedBy，{@code principal.type == "user"} 且 id 相同时 verified。
 * 同样执行档案来源门（ADR 0040 §11 D5）：模块档案要求 verifiedInputs 而规则引用的输入为 declared 时，决策为
 * DENY（{@code unverified_input} / {@code input_refs_unavailable}）而非 PENDING，写决策收据与决策行但不建待审批。
 * 门的 DENY 同样写决策行，因而消耗该证据的相关键：同一相关键重放返回这条 DENY，补齐事实后须用新的评估事件。
 *
 * <p>阻塞方法：须在非事务的 worker 线程调用——存储层每步自开独立事务，唯一索引冲突时才能干净地回读先到者。
 */
@ApplicationScoped
public class GuardEvidenceDecisionService {
  static final Set<String> APPROVABLE = Set.of(GuardDecisionDraft.REQUIRE_APPROVAL, GuardDecisionDraft.ESCALATE);
  /** 与 audit_logs.correlation_id / guard_decisions.evidence_correlation_id 列宽一致。 */
  static final int MAX_CORRELATION_ID = 64;
  private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() { };
  private static final Logger LOG = Logger.getLogger(GuardEvidenceDecisionService.class);

  private GuardDecisionStore store;
  private GuardDecisionService decisionService;
  private GuardActionMapper mapper;
  private AuditChainAppender appender;
  private GuardReceiptBuilder receiptBuilder;
  private ObjectMapper objectMapper;
  private GuardJson json;
  private GuardDecisionAssembler assembler;
  private FactVerifier factVerifier;
  private PolicyEvaluationService evaluationService;
  private ProfileRequirements profileRequirements;
  private Counter metadataUnavailable;
  private Duration approvalTtl;
  private EvaluationClassificationWriter classificationWriter;

  /** CDI 客户端代理所需。 */
  protected GuardEvidenceDecisionService() {
  }

  @Inject
  public GuardEvidenceDecisionService(GuardDecisionStore store, GuardDecisionService decisionService,
                                      GuardActionMapper mapper, AuditChainAppender appender,
                                      GuardReceiptBuilder receiptBuilder, ObjectMapper objectMapper,
                                      FactVerifier factVerifier, PolicyEvaluationService evaluationService,
                                      ProfileRequirements profileRequirements, MeterRegistry meterRegistry,
                                      @ConfigProperty(name = "aster.guard.approval-ttl", defaultValue = "PT24H") Duration approvalTtl,
                                      EvaluationClassificationWriter classificationWriter) {
    this.store = store;
    this.decisionService = decisionService;
    this.mapper = mapper;
    this.appender = appender;
    this.receiptBuilder = receiptBuilder;
    this.objectMapper = objectMapper;
    this.json = new GuardJson(objectMapper);
    this.assembler = new GuardDecisionAssembler(json);
    this.factVerifier = factVerifier;
    this.evaluationService = evaluationService;
    this.profileRequirements = profileRequirements;
    this.metadataUnavailable = GuardMetrics.ruleMetadataUnavailable(meterRegistry, "from_evidence");
    this.approvalTtl = approvalTtl;
    this.classificationWriter = classificationWriter;
  }

  /**
   * 以评估事件为锚开决策、写决策收据并建待审批；收据写不进链时决策改判 DENY 且不建审批。
   *
   * @param performedBy   签名内的用户（{@code RequestIdentityResolver.performedBy}），记为请求方供四眼校验
   * @param businessRoles 签名内的已验证业务角色，用于判定 {@code principal.roles} 是否 verified
   * @param actionHash    请求体 {@code action} 原文的 {@code jcs-sha256/v1}，写入决策行；审批转 ALLOW 时据此签发执行收据
   * @param rawAction     请求体 {@code action} 原文树，其中的 {@code facts} 在查既有决策之前验签；null 视为无签名事实
   * @throws GuardInvalidFactException      签名事实验签失败（先于重放，不会被既有决策掩盖）
   * @throws GuardApprovalConflictException {@code user_required} / {@code invalid_action}(400)、
   *                                        {@code evidence_not_found}(404)、{@code evidence_not_approvable}(409)、
   *                                        {@code evidence_input_mismatch}(409，ADR 0042 §12 D1)
   */
  public GuardDecisionResponse createFromEvidence(String tenantId, String performedBy, Set<String> businessRoles,
                                                  GuardFromEvidenceRequest req, String actionHash, JsonNode rawAction) {
    if (isAnonymous(performedBy)) {
      throw new GuardApprovalConflictException("user_required", "from-evidence 需要签名内的用户身份");
    }
    String correlationId = validCorrelationId(req);
    // 与 POST /decisions 同序（ADR 0040 §11 D4）：本次请求自带的事实先验签再查既有决策；重放返回首次决策的来源
    VerifiedFacts facts = factVerifier.verify(tenantId, rawAction);
    Optional<GuardDecisionResponse> replay = existing(tenantId, correlationId);
    if (replay.isPresent()) {
      return replay.get();
    }
    AuditLog event = evaluationEvent(tenantId, correlationId);
    Map<String, Object> metadata = metadataOf(event);
    String outcome = text(metadata, "outcome");
    // 无 outcome（失败的评估事件、metadata 被截断无法解析）同样不可审批；Set.of 的 contains(null) 会抛 NPE，须先判空
    if (outcome == null || !APPROVABLE.contains(outcome)) {
      throw new GuardApprovalConflictException("evidence_not_approvable", "评估结论不需要审批",
          Map.of("outcome", String.valueOf(outcome)));
    }
    GuardDecisionDraft evaluated = draftOf(tenantId, event, req, actionHash, metadata, outcome);
    // 输入绑定（ADR 0042 §12 D1）：可审批判定之后、来源门之前。不一致即 409，不建决策、不写收据、不消耗该证据
    Optional<GuardEvaluationClassificationEntity> classification = classificationRow(tenantId, correlationId);
    requireBoundInput(classification, evaluated.contextHash());
    GuardDecisionDraft draft = gate(evaluated
        .withInputSources(InputSourceResolver.resolve(req.action(), facts, performedBy, businessRoles)), req.action(),
        metadata);

    UUID decisionId = UUID.randomUUID();
    UUID approvalId = draft.needsApproval() ? UUID.randomUUID() : null;
    AppendedRecord receipt = appendReceipt(draft, decisionId, approvalId, performedBy, correlationId);
    if (receipt == null) {
      draft = draft.deny(GuardReasonCode.RECEIPT_UNAVAILABLE);
      approvalId = null;
    }
    Instant expiresAt = Instant.now().plus(approvalTtl);
    // 证据只开可审批的决策，不存在直接 ALLOW：执行收据只会在审批转 ALLOW 时签发
    GuardDecisionResponse response = buildResponse(decisionId, draft, receipt, approvalId, expiresAt, null);
    GuardApprovalEntity approval = approvalId == null ? null : toApproval(approvalId, decisionId, draft, expiresAt);
    // 输入快照只随审批行写：门 DENY 与 receipt_unavailable 不写（ADR 0042 §12 D1）
    GuardDecisionInputEntity input = approval == null ? null
        : DecisionInputFactory.fromEvidence(decisionId, tenantId, req.action(), draft.contextHash(), classification);
    GuardDecisionEntity entity = assembler.toEntity(decisionId, draft, receipt, response, performedBy, null);
    entity.evidenceCorrelationId = correlationId;
    // 版本列取事件 metadata（ADR 0041 有值时才写）；响应体的 policyVersion 按裁定保持 null
    entity.policyVersionId = metadata.get("policyVersionId") instanceof Number n ? n.longValue() : null;
    try {
      store.saveDecision(entity, approval, null, input);
    } catch (GuardIdempotencyRaceException race) {
      // 并发同一证据：唯一索引让后写者失败，回读先到者的决策
      return existing(tenantId, correlationId).orElseThrow(() -> race);
    }
    return response;
  }

  /**
   * 档案来源门（ADR 0040 §11 D5）。被拒时改判 DENY 并带上 unverifiedFields，调用方随之不建待审批。
   *
   * <p>判定顺序（ADR 0040 §11 D5 实施澄清，ADR 0046 §11.6 第 4 条）：
   * <ol>
   *   <li>要求项在消费时由事件 {@code profile} 经 {@link ProfileRequirements}（当前注册表）重算；事件里的
   *       {@code verifiedInputsRequired} 是写事件时的快照、只供审计，<b>不读</b>——否则 1.3.0 翻转前写下的 false 快照
   *       会在翻转后放行未验证输入；</li>
   *   <li>事件自带 {@code inputRefs}（Task 16b 起，写事件时已映射为 {@code context.<实际匹配键>.<path>}）→ 直接使用，
   *       不读元数据：evaluate-source 锚定的模块没有已保存版本，事件是唯一来源；形态不对按 {@code "*"}；</li>
   *   <li>否则（16b 之前的旧事件，或 IR 没有引用集）按事件的 {@code policyVersionId} 取规则元数据（事件没有版本时取活跃版本）。
   *       元数据显示要求同样算要求；它的 {@code inputRefs} 是引擎原样路径，缺写事件时的输入匹配键，非空即按 {@code "*"}
   *       （fail-closed）；没有引用集 → {@code input_refs_unavailable}；</li>
   *   <li>元数据读不到：要求 → DENY {@code input_refs_unavailable}，否则放行；计 counter，事件无 profile 时只记 DEBUG，
   *       带 profile 时记 WARN。</li>
   * </ol>
   * 注册表 1.3.0（引擎 pin 1.0.45）起 {@code eu-ai-act-high-risk} 要求 verifiedInputs：声明该档案的事件，被引用输入未经事实证实即 DENY；
   * 其余档案不要求，门不改变草稿。
   */
  GuardDecisionDraft gate(GuardDecisionDraft draft, GuardActionRequest.Action action, Map<String, Object> event) {
    String profile = text(event, EvaluationEvidence.KEY_PROFILE);
    // 在读元数据之前判定：它的异常不是「元数据读不到」，不计 counter，原样上抛
    boolean profileRequires = profileRequirements.verifiedInputsRequired(profile);
    RuleMetadata meta;
    if (event.containsKey(EvaluationEvidence.KEY_INPUT_REFS)) {
      meta = new RuleMetadata(draft.ruleId(), draft.controls(), profile,
          eventInputRefs(event.get(EvaluationEvidence.KEY_INPUT_REFS)), profileRequires);
    } else {
      try {
        meta = evidenceShape(ruleMetadata(draft, event), profileRequires);
      } catch (RuntimeException e) {
        return withoutRuleMetadata(draft, profile, profileRequires, e);
      }
    }
    return VerifiedInputsGate.check(meta, draft.inputSources(), VerifiedInputsGate.presentLeafPaths(action))
        .map(denial -> draft.denyUnverified(denial.code(), denial.unverifiedFields()))
        .orElse(draft);
  }

  /**
   * 评估分类行（ADR 0042 §12 D1）。读失败按缺行处理（{@code unbound / none}），审批人在视图里看得到「未核对」；
   * 读失败不会放过哈希不一致——缺行时本就不核对。
   */
  private Optional<GuardEvaluationClassificationEntity> classificationRow(String tenantId, String correlationId) {
    try {
      return classificationWriter.find(tenantId, correlationId);
    } catch (RuntimeException e) {
      LOG.warnf("from-evidence 读取评估分类行失败，按未核对处理: tenant=%s correlationId=%s: %s", tenantId, correlationId,
          e.toString());
      return Optional.empty();
    }
  }

  /**
   * 分类行带输入哈希时，请求的 {@code action.context} 必须与评估时的输入同一哈希（{@code sha256-sorted-json/v1}）；
   * 行缺失或哈希为 null（位置格式输入）不核对，决策照建并标 {@code unbound}。
   *
   * @throws GuardApprovalConflictException {@code evidence_input_mismatch}(409)
   */
  static void requireBoundInput(Optional<GuardEvaluationClassificationEntity> row, String contextHash) {
    String expected = row.map(r -> r.inputHash).orElse(null);
    if (expected != null && !expected.equals(contextHash)) {
      throw new GuardApprovalConflictException("evidence_input_mismatch",
          "action.context 与该评估事件的输入不一致；请提交被评估的同一输入");
    }
  }

  /** 事件自带的引用集须是字符串列表；其余形态（含 null 项）按「*」处理。 */
  private static List<String> eventInputRefs(Object raw) {
    if (raw instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    return List.of(VerifiedInputsGate.ALL_INPUTS);
  }

  private RuleMetadata ruleMetadata(GuardDecisionDraft draft, Map<String, Object> event) {
    return event.get(EvaluationEvidence.KEY_POLICY_VERSION_ID) instanceof Number version
        ? evaluationService.ruleMetadata(draft.tenantId(), draft.policyModule(), draft.policyFunction(),
            String.valueOf(version.longValue()))
        : evaluationService.ruleMetadata(draft.tenantId(), draft.policyModule(), draft.policyFunction());
  }

  /**
   * 把版本元数据转成 from-evidence 可用的形态：要求项与事件档案的重算结果取或；IR 引用非空时整体视为 {@code "*"}
   * （引擎路径没有证据形状的匹配键前缀），空列表（规则不读输入）保持为空。
   */
  private static RuleMetadata evidenceShape(RuleMetadata meta, boolean profileRequires) {
    List<String> refs = meta.inputRefs() == null || meta.inputRefs().isEmpty()
        ? meta.inputRefs() : List.of(VerifiedInputsGate.ALL_INPUTS);
    return new RuleMetadata(meta.ruleId(), meta.controls(), meta.profile(), refs,
        meta.verifiedInputsRequired() || profileRequires);
  }

  /** 元数据读不到：按事件档案判定（未知档案按不要求，ADR 0046 §11.6 第 6 条）。 */
  private GuardDecisionDraft withoutRuleMetadata(GuardDecisionDraft draft, String profile, boolean profileRequires,
                                                 RuntimeException e) {
    metadataUnavailable.increment();
    if (isBlank(profile)) {
      LOG.debugf("from-evidence 读不到规则元数据，事件无档案: %s.%s: %s", draft.policyModule(), draft.policyFunction(),
          e.getMessage());
    } else {
      LOG.warnf("from-evidence 读不到规则元数据，按事件档案 %s 判定: %s.%s: %s", profile, draft.policyModule(),
          draft.policyFunction(), e.getMessage());
    }
    return profileRequires
        ? draft.denyUnverified(GuardReasonCode.INPUT_REFS_UNAVAILABLE, List.of())
        : draft;
  }

  /** 同步写决策收据（带锚定的评估相关键）；失败返回 null，由调用方改判 DENY receipt_unavailable。 */
  private AppendedRecord appendReceipt(GuardDecisionDraft draft, UUID decisionId, UUID approvalId, String performedBy,
                                       String correlationId) {
    try {
      return appender.appendSync(receiptBuilder.decisionEvent(draft, decisionId, approvalId, performedBy, correlationId));
    } catch (AuditChainAppender.AuditAppendException e) {
      LOG.errorf(e, "from-evidence 决策收据写入失败，按 DENY 处理: decision=%s", decisionId);
      return null;
    }
  }

  private static String validCorrelationId(GuardFromEvidenceRequest req) {
    String cid = req == null ? null : req.correlationId();
    if (isBlank(cid) || cid.length() > MAX_CORRELATION_ID) {
      throw new GuardApprovalConflictException("invalid_action", "correlationId 必填且不超过 " + MAX_CORRELATION_ID + " 字符");
    }
    return cid;
  }

  /**
   * 既有决策按当前审批状态回放（含读取时的过期判定），与 Idempotency-Key 重放同一语义。
   * 决策行已命中却回读不到（两次读取之间被删除）时回放该行存储的响应：行已存在就绝不再插入，
   * 否则会撞唯一索引变成 500。
   */
  private Optional<GuardDecisionResponse> existing(String tenantId, String correlationId) {
    return store.findByEvidenceCorrelationId(tenantId, correlationId)
        .map(e -> decisionService.get(tenantId, e.id).orElseGet(() -> json.readResponse(e)));
  }

  /**
   * 租户内同相关键的评估事件；跨租户与非评估事件一律视为不存在，不泄露存在性。
   * 相关键是随机 UUID，正常不会重复；万一同键有多条评估事件，{@code findByCorrelationIds} 按 id 升序返回，
   * {@code findFirst()} 固定取最早的一条。
   */
  private static AuditLog evaluationEvent(String tenantId, String correlationId) {
    List<AuditLog> rows = QuarkusTransaction.requiringNew()
        .call(() -> AuditLog.findByCorrelationIds(tenantId, List.of(correlationId)));
    return rows.stream()
        .filter(r -> EventType.POLICY_EVALUATION.name().equals(r.eventType))
        .findFirst()
        .orElseThrow(() -> new GuardApprovalConflictException("evidence_not_found", "审计链上无此评估事件: " + correlationId));
  }

  /** metadata 缺失或无法解析时视为空：outcome 取不到，按不可审批拒绝。 */
  private Map<String, Object> metadataOf(AuditLog event) {
    if (isBlank(event.metadata)) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(event.metadata, METADATA_TYPE);
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  /** 策略地址取事件列，结论取事件 metadata；ADR 0041 未写策略版本，版本为 null。 */
  private GuardDecisionDraft draftOf(String tenantId, AuditLog event, GuardFromEvidenceRequest req, String actionHash,
                                     Map<String, Object> m, String outcome) {
    try {
      return new GuardDecisionDraft(tenantId, Objects.requireNonNullElse(event.policyModule, ""),
          Objects.requireNonNullElse(event.policyFunction, ""), null, mapper.flatten(req.action()),
          mapper.contextHash(req.action().context()), actionHash, null, outcome, text(m, "role"), text(m, "reason"),
          text(m, "ruleId"), controls(m), null, declaredAgent(req.action().agent()));
    } catch (GuardInvalidActionException bad) {
      throw new GuardApprovalConflictException("invalid_action", bad.getMessage());
    }
  }

  private static List<String> controls(Map<String, Object> m) {
    return m.get("controls") instanceof List<?> list
        ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
        : List.of();
  }

  private static String text(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? null : v.toString();
  }
}
