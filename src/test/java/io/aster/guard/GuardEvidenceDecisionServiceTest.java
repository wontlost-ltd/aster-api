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
import aster.core.typecheck.governance.ControlRegistry;
import io.aster.policy.evidence.EvaluationEvidence;
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
    assertThat(gated.unverifiedFields()).containsExactly("*");
    verify(evaluationService, never()).ruleMetadata(any(), any(), any());
    assertThat(unavailableCount()).isZero();
  }

  /** 元数据显示不要求 verifiedInputs（如 registry 1.2.0 之前写下的版本元数据）且注册表未被模拟为要求时，门不改变草稿。 */
  @Test
  void notRequiredMetadataLeavesDraftUnchanged() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, "7")).thenReturn(
        new RuleMetadata("R-1", List.of(), "eu-ai-act-high-risk", null, false));
    GuardDecisionDraft draft = pending();

    assertThat(service.gate(draft, action(), event(7L, "eu-ai-act-high-risk"))).isEqualTo(draft);
  }

  /**
   * 裁定 1：要求项在消费时按事件 profile + 当前注册表重算。版本元数据说「不要求」（如翻转前的产物快照），
   * 而事件档案在当前注册表下要求 → 按要求判定。
   */
  @Test
  void requirementRecomputedFromEventProfile() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, "7")).thenReturn(
        new RuleMetadata("R-1", List.of(), "eu-ai-act-high-risk", null, false));
    when(profileRequirements.verifiedInputsRequired("eu-ai-act-high-risk")).thenReturn(true);

    GuardDecisionDraft gated = service.gate(pending(), action(), event(7L, "eu-ai-act-high-risk"));

    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.INPUT_REFS_UNAVAILABLE);
  }

  /** 版本元数据的 IR 引用是引擎原样路径，无法映射到证据形状：非空即按「*」判定（全部出现的叶子须 verified）。 */
  @Test
  void irRefsFromVersionMetadataTreatedAsStar() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, "7")).thenReturn(
        new RuleMetadata("R-1", List.of(), "eu-ai-act-high-risk", List.of("context.amount"), true));

    GuardDecisionDraft gated = service.gate(pending(), action(), event(7L, "eu-ai-act-high-risk"));

    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.UNVERIFIED_INPUT);
    assertThat(gated.unverifiedFields()).containsExactly("*");
  }

  /** 档案要求查询自身抛错：原样上抛，不读元数据，不计入「元数据读不到」的 counter。 */
  @Test
  void profileLookupFailurePropagatesAndIsNotCountedAsMetadataUnavailable() {
    when(profileRequirements.verifiedInputsRequired("eu-ai-act-high-risk"))
        .thenThrow(new IllegalStateException("registry-broken"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.gate(pending(), action(), event(7L, "eu-ai-act-high-risk")))
        .isInstanceOf(IllegalStateException.class).hasMessage("registry-broken");
    assertThat(unavailableCount()).isZero();
    verify(evaluationService, never()).ruleMetadata(any(), any(), any(), any());
  }

  // ---- Task 16b：事件自带的 inputRefs 优先；要求项一律按事件 profile + 当前注册表重算 ----

  /** 注入的注册表：eu-ai-act-high-risk 要求 verifiedInputs（模拟 registry 1.3.0 翻转之后）。 */
  private static ControlRegistry registryRequiring(boolean verifiedInputs) {
    return new ControlRegistry("1.3.0-test", Set.of("EU_AI_ACT:ART14"), Map.of("EU_AI_ACT:ART14", "EU_AI_ACT"),
        Map.of("eu-ai-act-high-risk", new ControlRegistry.Profile("eu-ai-act-high-risk", true, true,
            List.of("EU_AI_ACT"), verifiedInputs)));
  }

  private GuardEvidenceDecisionService serviceWith(ControlRegistry registry) {
    return new GuardEvidenceDecisionService(store, decisionService, new GuardActionMapper(),
        mock(AuditChainAppender.class), mock(GuardReceiptBuilder.class), objectMapper,
        new FactVerifier(mock(FactIssuerStore.class), Clock.systemUTC()), evaluationService,
        new ProfileRequirements(registry), meters, Duration.ofHours(24));
  }

  private static Map<String, Object> eventWithRefs(Object inputRefs, Boolean snapshot) {
    Map<String, Object> m = event(null, "eu-ai-act-high-risk");
    m.put(EvaluationEvidence.KEY_INPUT_REFS, inputRefs);
    if (snapshot != null) {
      m.put(EvaluationEvidence.KEY_VERIFIED_INPUTS_REQUIRED, snapshot);
    }
    return m;
  }

  /** PR 2 的 fail-open 关闭用例，改用真实 ProfileRequirements 与注入注册表：读不到元数据且要求 → DENY。 */
  @Test
  void failOpenClosedWithRealProfileRequirements() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION)).thenThrow(new IllegalStateException("无已保存版本"));

    GuardDecisionDraft gated = serviceWith(registryRequiring(true))
        .gate(pending(), action(), event(null, "eu-ai-act-high-risk"));

    assertThat(gated.outcome()).isEqualTo("DENY");
    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.INPUT_REFS_UNAVAILABLE);
    assertThat(unavailableCount()).isEqualTo(1.0);
    // 档案不要求 verifiedInputs 的注册表（如 1.2.0）下同一事件放行；默认注册表 1.3.0 起高风险档案要求，见上一断言
    GuardDecisionDraft draft = pending();
    assertThat(serviceWith(registryRequiring(false)).gate(draft, action(), event(null, "eu-ai-act-high-risk")))
        .isEqualTo(draft);
  }

  /**
   * evaluate-source 锚定的模块没有已保存版本：直接使用事件里写事件时已映射的 {@code context.<键>.…}，不读元数据。
   * 引用的 context.amount 为 declared → DENY unverified_input 且点名该路径；规则不读输入（[]）→ 放行。
   */
  @Test
  void eventCarriedInputRefsUsedForEvaluateSourceModule() {
    GuardEvidenceDecisionService svc = serviceWith(registryRequiring(true));

    GuardDecisionDraft gated = svc.gate(pending(), action(), eventWithRefs(List.of("context.amount"), true));

    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.UNVERIFIED_INPUT);
    assertThat(gated.unverifiedFields()).containsExactly("context.amount");
    GuardDecisionDraft draft = pending();
    assertThat(svc.gate(draft, action(), eventWithRefs(List.of(), true))).isEqualTo(draft);
    verify(evaluationService, never()).ruleMetadata(any(), any(), any());
    verify(evaluationService, never()).ruleMetadata(any(), any(), any(), any());
    assertThat(unavailableCount()).isZero();
  }

  /** 16b 之前的旧事件没有 inputRefs：回退到版本元数据，读不到且档案要求 → input_refs_unavailable。 */
  @Test
  void eventWithoutInputRefsFallsBackToProfile() {
    when(evaluationService.ruleMetadata(TENANT, MODULE, FUNCTION, "7")).thenReturn(
        new RuleMetadata("R-1", List.of(), "eu-ai-act-high-risk", null, false));

    GuardDecisionDraft gated = serviceWith(registryRequiring(true))
        .gate(pending(), action(), event(7L, "eu-ai-act-high-risk"));

    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.INPUT_REFS_UNAVAILABLE);
    verify(evaluationService).ruleMetadata(TENANT, MODULE, FUNCTION, "7");
  }

  /**
   * 事件的 verifiedInputsRequired 只是写事件时的快照：1.3.0 翻转前写下的 false 快照，在当前注册表要求时照样按要求判定；
   * 反过来快照为 true 而当前注册表不要求，也不据快照拒绝。
   */
  @Test
  void eventRequirementSnapshotIgnored() {
    GuardDecisionDraft gated = serviceWith(registryRequiring(true))
        .gate(pending(), action(), eventWithRefs(List.of("context.amount"), false));
    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.UNVERIFIED_INPUT);

    GuardDecisionDraft draft = pending();
    assertThat(serviceWith(registryRequiring(false)).gate(draft, action(), eventWithRefs(List.of("context.amount"), true)))
        .isEqualTo(draft);
  }

  /** 事件里的 inputRefs 形态不对（非列表、含非字符串项）：按「*」处理（fail-closed）。 */
  @Test
  void malformedEventInputRefsTreatedAsStar() {
    GuardEvidenceDecisionService svc = serviceWith(registryRequiring(true));

    assertThat(svc.gate(pending(), action(), eventWithRefs("context.amount", null)).unverifiedFields())
        .containsExactly("*");
    assertThat(svc.gate(pending(), action(), eventWithRefs(java.util.Arrays.asList("context.amount", 3), null))
        .unverifiedFields()).containsExactly("*");
  }

  /**
   * aster-api#493：预算降级把 inputRefs 降为 {@code ["*"]}（不删除）。要求 verifiedInputs 的档案下，降级事件的门判定为
   * DENY unverified_input 且点名「*」——降级只会更严，绝不因丢键而放行未验证输入。
   */
  @Test
  void budgetDegradedInputRefsFailClosed() {
    Map<String, Object> raw = eventWithRefs(java.util.stream.IntStream.range(0, 20)
        .mapToObj(i -> "context.req.f" + i).toList(), true);
    raw.put("role", "\"".repeat(255));
    raw.put("ruleId", "\u0001".repeat(255));
    raw.put("controls", java.util.stream.IntStream.range(0, 16)
        .mapToObj(i -> "\"".repeat(20) + "C".repeat(42) + String.format("%02d", i)).toList());
    Map<String, Object> degraded = io.aster.policy.evidence.EvaluationMetadataBudget.fit(raw);
    assertThat(degraded).containsEntry(EvaluationEvidence.KEY_INPUT_REFS, List.of("*"));

    GuardDecisionDraft gated = serviceWith(registryRequiring(true)).gate(pending(), action(), degraded);

    assertThat(gated.outcome()).isEqualTo("DENY");
    assertThat(gated.reasonCode()).isEqualTo(GuardReasonCode.UNVERIFIED_INPUT);
    assertThat(gated.unverifiedFields()).containsExactly("*");
  }
}
