package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardDecisionResponse.ReceiptView;
import io.aster.guard.api.GuardFromEvidenceRequest;
import io.aster.guard.provenance.FactIssuerStore;
import io.aster.guard.provenance.FactVerifier;
import io.aster.guard.provenance.ProfileRequirements;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.policy.governance.RuleMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.aster.policy.api.PolicyEvaluationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 从证据开决策的重放分支（不启动 Quarkus：重放在查链与落库之前返回）。 */
class GuardEvidenceDecisionServiceTest {
  private static final String TENANT = "t-evidence-unit";
  private static final String CID = "ev-unit-1";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final GuardDecisionStore store = mock(GuardDecisionStore.class);
  private final GuardDecisionService decisionService = mock(GuardDecisionService.class);
  private final PolicyEvaluationService evaluationService = mock(PolicyEvaluationService.class);
  private final ProfileRequirements profileRequirements = mock(ProfileRequirements.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final GuardEvidenceDecisionService service = new GuardEvidenceDecisionService(store, decisionService,
      new GuardActionMapper(), mock(AuditChainAppender.class), mock(GuardReceiptBuilder.class), objectMapper,
      new FactVerifier(mock(FactIssuerStore.class), Clock.systemUTC()), evaluationService, profileRequirements, meters,
      Duration.ofHours(24));

  private GuardDecisionEntity storedRow(GuardDecisionResponse response) throws Exception {
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.id = UUID.fromString(response.decisionId());
    e.tenantId = TENANT;
    e.evidenceCorrelationId = CID;
    e.responseJson = objectMapper.writeValueAsString(response);
    return e;
  }

  private static GuardDecisionResponse response(String effectiveOutcome) {
    return new GuardDecisionResponse(null, List.of("GDPR:ART17"), UUID.randomUUID().toString(), effectiveOutcome, null,
        "REQUIRE_APPROVAL", null, null, null, new ReceiptView(11L, "h", "p"), "Data Protection Officer", "R-1");
  }

  @Test
  void 决策行命中且可回读时回放当前响应() throws Exception {
    GuardDecisionResponse stored = response("PENDING");
    GuardDecisionResponse current = response("ALLOW");
    GuardDecisionEntity row = storedRow(stored);
    when(store.findByEvidenceCorrelationId(TENANT, CID)).thenReturn(Optional.of(row));
    when(decisionService.get(TENANT, row.id)).thenReturn(Optional.of(current));

    assertThat(service.createFromEvidence(TENANT, "u-req", Set.of(), new GuardFromEvidenceRequest(CID, null), null, null)).isEqualTo(current);
    verify(store, never()).saveDecision(any(), any(), any());
  }

  @Test
  void 决策行命中但回读为空时回放行内响应_不再插入() throws Exception {
    GuardDecisionResponse stored = response("PENDING");
    GuardDecisionEntity row = storedRow(stored);
    when(store.findByEvidenceCorrelationId(TENANT, CID)).thenReturn(Optional.of(row));
    when(decisionService.get(TENANT, row.id)).thenReturn(Optional.empty());

    assertThat(service.createFromEvidence(TENANT, "u-req", Set.of(), new GuardFromEvidenceRequest(CID, null), null, null)).isEqualTo(stored);
    verify(store, never()).saveDecision(any(), any(), any());
  }

  // ---- 档案来源门（ADR 0040 §11 D5 实施澄清；Task 16 carry-over 1/2/6） ----

  private static final String MODULE = "guard.customer";
  private static final String FUNCTION = "decide";

  private static Action action() {
    return new Action(new Principal("u-req", "user", null), new ActionName("approve_loan"), null,
        Map.of("amount", 250000), null);
  }

  /** 从证据开出、尚未过门的 REQUIRE_APPROVAL 草稿（结论取自事件）。 */
  private static GuardDecisionDraft pending() {
    GuardActionMapper mapper = new GuardActionMapper();
    return new GuardDecisionDraft(TENANT, MODULE, FUNCTION, null, mapper.flatten(action()),
        mapper.contextHash(action().context()), null, null, "REQUIRE_APPROVAL", "Credit Officer", "复核", "R-1",
        List.of("EU_AI_ACT:ART14"), null, null);
  }

  /** 评估事件 metadata；profile 为 null 时不写该键（无档案模块的事件）。 */
  private static Map<String, Object> event(Long policyVersionId, String profile) {
    Map<String, Object> m = new HashMap<>(Map.of("correlationId", CID, "outcome", "REQUIRE_APPROVAL"));
    if (policyVersionId != null) {
      m.put("policyVersionId", policyVersionId);
    }
    if (profile != null) {
      m.put("profile", profile);
    }
    return m;
  }

  private double unavailableCount() {
    return meters.get("aster.guard.rule.metadata.unavailable").tag("path", "from_evidence").counter().count();
  }

  /** 读不到元数据（evaluate-source 模块无已保存版本）且事件档案要求 verifiedInputs → DENY input_refs_unavailable。 */
  @Test
  void unreadableMetadataWithRequiringProfileDeniesInputRefsUnavailable() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION)).thenThrow(new IllegalStateException("无已保存版本"));
    when(profileRequirements.verifiedInputsRequired("eu-ai-act-high-risk")).thenReturn(true);

    GuardDecisionDraft gated = service.gate(pending(), action(), event(null, "eu-ai-act-high-risk"));

    assertThat(gated.outcome()).isEqualTo("DENY");
    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.INPUT_REFS_UNAVAILABLE);
    assertThat(gated.unverifiedFields()).isEmpty();
    assertThat(gated.needsApproval()).isFalse();
    assertThat(unavailableCount()).isEqualTo(1.0);
  }

  /**
   * 读不到元数据而档案不要求（今天全部档案如此）→ 放行、草稿不变，counter +1；日志：事件无 profile 时 DEBUG
   * （evaluate-source 锚定是常态），带非空 profile 时 WARN。
   */
  @Test
  void unreadableMetadataWithoutRequirementPassesAndCounts() {
    when(evaluationService.ruleMetadata(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("无版本"));
    List<LogRecord> records = new CopyOnWriteArrayList<>();
    Handler probe = new Handler() {
      @Override public void publish(LogRecord r) { records.add(r); }
      @Override public void flush() { }
      @Override public void close() { }
    };
    Logger logger = Logger.getLogger(GuardEvidenceDecisionService.class.getName());
    Level original = logger.getLevel();
    logger.addHandler(probe);
    logger.setLevel(Level.ALL);
    GuardDecisionDraft draft = pending();
    try {
      assertThat(service.gate(draft, action(), event(null, null))).isEqualTo(draft);
      assertThat(records).isNotEmpty().allMatch(r -> r.getLevel().intValue() < Level.INFO.intValue());
      records.clear();
      assertThat(service.gate(draft, action(), event(null, "governed"))).isEqualTo(draft);
      assertThat(records).anyMatch(r -> r.getLevel().intValue() == Level.WARNING.intValue());
    } finally {
      logger.removeHandler(probe);
      logger.setLevel(original);
    }
    assertThat(unavailableCount()).isEqualTo(2.0);
  }

  /** 事件带 policyVersionId 时按该版本取元数据（激活版本已移动也读评估时的版本），不走按活跃版本解析的入口。 */
  @Test
  void metadataPinnedToEventPolicyVersionId() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, "1730890123456")).thenReturn(
        new RuleMetadata("R-1", List.of(), "eu-ai-act-high-risk", List.of("context.amount"), true));

    GuardDecisionDraft gated = service.gate(pending(), action(), event(1730890123456L, "eu-ai-act-high-risk"));

    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.UNVERIFIED_INPUT);
    assertThat(gated.unverifiedFields()).containsExactly("context.amount");
    verify(evaluationService, never()).ruleMetadata(any(), any(), any());
    assertThat(unavailableCount()).isZero();
  }

  /** 今天（registry 1.1.0 / 1.2.0）没有档案要求 verifiedInputs：真实元数据下门不改变任何草稿。 */
  @Test
  void notRequiredMetadataLeavesDraftUnchanged() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, "7")).thenReturn(
        new RuleMetadata("R-1", List.of(), "eu-ai-act-high-risk", null, false));
    GuardDecisionDraft draft = pending();

    assertThat(service.gate(draft, action(), event(7L, "eu-ai-act-high-risk"))).isEqualTo(draft);
  }
}
