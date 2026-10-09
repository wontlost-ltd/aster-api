package io.aster.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardFromEvidenceRequest;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.EventType;
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
  private Duration approvalTtl;

  /** CDI 客户端代理所需。 */
  protected GuardEvidenceDecisionService() {
  }

  @Inject
  public GuardEvidenceDecisionService(GuardDecisionStore store, GuardDecisionService decisionService,
                                      GuardActionMapper mapper, AuditChainAppender appender,
                                      GuardReceiptBuilder receiptBuilder, ObjectMapper objectMapper,
                                      @ConfigProperty(name = "aster.guard.approval-ttl", defaultValue = "PT24H") Duration approvalTtl) {
    this.store = store;
    this.decisionService = decisionService;
    this.mapper = mapper;
    this.appender = appender;
    this.receiptBuilder = receiptBuilder;
    this.objectMapper = objectMapper;
    this.json = new GuardJson(objectMapper);
    this.assembler = new GuardDecisionAssembler(json);
    this.approvalTtl = approvalTtl;
  }

  /**
   * 以评估事件为锚开决策、写决策收据并建待审批；收据写不进链时决策改判 DENY 且不建审批。
   *
   * @param performedBy 签名内的用户（{@code RequestIdentityResolver.performedBy}），记为请求方供四眼校验
   * @throws GuardApprovalConflictException {@code user_required} / {@code invalid_action}(400)、
   *                                        {@code evidence_not_found}(404)、{@code evidence_not_approvable}(409)
   */
  public GuardDecisionResponse createFromEvidence(String tenantId, String performedBy, GuardFromEvidenceRequest req) {
    if (isAnonymous(performedBy)) {
      throw new GuardApprovalConflictException("user_required", "from-evidence 需要签名内的用户身份");
    }
    String correlationId = validCorrelationId(req);
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
    GuardDecisionDraft draft = draftOf(tenantId, event, req, metadata, outcome);

    UUID decisionId = UUID.randomUUID();
    UUID approvalId = UUID.randomUUID();
    AppendedRecord receipt = appendReceipt(draft, decisionId, approvalId, performedBy, correlationId);
    if (receipt == null) {
      draft = draft.deny(GuardReasonCode.RECEIPT_UNAVAILABLE);
      approvalId = null;
    }
    Instant expiresAt = Instant.now().plus(approvalTtl);
    GuardDecisionResponse response = buildResponse(decisionId, draft, receipt, approvalId, expiresAt);
    GuardApprovalEntity approval = approvalId == null ? null : toApproval(approvalId, decisionId, draft, expiresAt);
    GuardDecisionEntity entity = assembler.toEntity(decisionId, draft, receipt, response, performedBy, null);
    entity.evidenceCorrelationId = correlationId;
    // 版本列取事件 metadata（ADR 0041 有值时才写）；响应体的 policyVersion 按裁定保持 null
    entity.policyVersionId = metadata.get("policyVersionId") instanceof Number n ? n.longValue() : null;
    try {
      store.saveDecision(entity, approval);
    } catch (GuardIdempotencyRaceException race) {
      // 并发同一证据：唯一索引让后写者失败，回读先到者的决策
      return existing(tenantId, correlationId).orElseThrow(() -> race);
    }
    return response;
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
  private GuardDecisionDraft draftOf(String tenantId, AuditLog event, GuardFromEvidenceRequest req,
                                     Map<String, Object> m, String outcome) {
    try {
      return new GuardDecisionDraft(tenantId, Objects.requireNonNullElse(event.policyModule, ""),
          Objects.requireNonNullElse(event.policyFunction, ""), null, mapper.flatten(req.action()),
          mapper.contextHash(req.action().context()), null, outcome, text(m, "role"), text(m, "reason"),
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
