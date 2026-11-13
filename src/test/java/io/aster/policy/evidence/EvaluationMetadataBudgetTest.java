package io.aster.policy.evidence;

import io.aster.audit.chain.AuditMetadataSize;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估事件 metadata 的预检降级（aster-api#493）：超预算时按固定顺序缩短可选键、inputRefs 降为 {@code ["*"]}，
 * 仍超限则只留下不可审批的最小键集；任何情况下都不让追加器截断出非法 JSON。
 */
public class EvaluationMetadataBudgetTest {
  private static final String CID = "0f8fad5b-d9cb-469f-a165-70867728950e";
  private static final String HASH = "a".repeat(64);
  private static final List<String> REFS = List.of("context.req.amount", "context.req.country");

  /** 一条正常大小的档案模块评估事件。 */
  public static Map<String, Object> normal() {
    EvaluationEvidence e = new EvaluationEvidence(CID, "REQUIRE_APPROVAL", "R-1", List.of("EU_AI_ACT:ART14"), 7L,
        new AgentIdentity("openai", "gpt", "1", "s-1"), "Credit Officer", "复核", "eu-ai-act-high-risk", HASH, HASH)
        .withInputRefs(REFS, true);
    Map<String, Object> m = new HashMap<>(Map.of("contextSize", 1, "applicantId", "A-1"));
    m.putAll(e.toMetadata());
    return m;
  }

  @Test
  void 预算内的事件原样返回_不写降级标记() {
    Map<String, Object> m = normal();

    Map<String, Object> fitted = EvaluationMetadataBudget.fit(m);

    assertThat(fitted).isEqualTo(m).doesNotContainKey(EvaluationMetadataBudget.KEY_METADATA_DEGRADED);
  }

  @Test
  void 空与null原样返回() {
    assertThat(EvaluationMetadataBudget.fit(Map.of())).isEmpty();
    assertThat(EvaluationMetadataBudget.fit(null)).isEmpty();
  }

  @Test
  void applicantId与locale超过上限时截到255并记入降级标记() {
    Map<String, Object> m = normal();
    m.put(EvaluationMetadataBudget.KEY_APPLICANT_ID, "x".repeat(10_000));
    m.put(EvaluationMetadataBudget.KEY_LOCALE, "y".repeat(10_000));

    Map<String, Object> fitted = EvaluationMetadataBudget.fit(m);

    assertThat((String) fitted.get("applicantId")).isEqualTo("x".repeat(255));
    assertThat((String) fitted.get("locale")).isEqualTo("y".repeat(255));
    assertThat(fitted.get(EvaluationMetadataBudget.KEY_METADATA_DEGRADED))
        .isEqualTo(List.of("applicantId", "locale"));
    assertThat(AuditMetadataSize.measure(fitted)).isLessThanOrEqualTo(AuditMetadataSize.BUDGET);
  }

  @Test
  void 输入不被修改() {
    Map<String, Object> m = normal();
    m.put("applicantId", "x".repeat(10_000));
    Map<String, Object> copy = new HashMap<>(m);

    EvaluationMetadataBudget.fit(m);

    assertThat(m).isEqualTo(copy);
  }

  /**
   * 缩短顺序：applicantId → locale → agentSession → agentVersion → agentModel → agentProvider → reason。
   * 只缩到达标为止：靠前的键缩完仍超才动下一个；身份与结论键一律不动。
   */
  @Test
  void 按固定顺序缩短_先applicantId后reason_身份与结论键不动() {
    String esc = "\u0001".repeat(255); // 每个字符序列化为 \u0001，6 倍膨胀
    Map<String, Object> m = new LinkedHashMap<>(normal());
    m.put("applicantId", esc);
    m.put("locale", esc);
    m.put("reason", "r".repeat(255));

    Map<String, Object> fitted = EvaluationMetadataBudget.fit(m);

    assertThat(AuditMetadataSize.measure(fitted)).isLessThanOrEqualTo(AuditMetadataSize.BUDGET);
    assertThat(fitted.get("reason")).as("排在最后的 reason 无需缩短").isEqualTo("r".repeat(255));
    @SuppressWarnings("unchecked")
    List<String> degraded = (List<String>) fitted.get(EvaluationMetadataBudget.KEY_METADATA_DEGRADED);
    assertThat(degraded).isNotEmpty();
    assertThat(EvaluationMetadataBudget.SHRINK_ORDER.subList(0, degraded.size())).isEqualTo(degraded);
    assertProtectedUnchanged(m, fitted);
  }

  @Test
  void 可选文本全部缩完仍超限时inputRefs降为星号_不删除() {
    Map<String, Object> m = normal();
    m.put("role", "\"".repeat(255));
    m.put("ruleId", "\u0001".repeat(255));
    m.put("controls", IntStream.range(0, 14).mapToObj(i -> "C".repeat(62) + String.format("%02d", i)).toList());
    m.put("inputRefs", largestRefs());

    Map<String, Object> fitted = EvaluationMetadataBudget.fit(m);

    assertThat(AuditMetadataSize.measure(fitted)).isLessThanOrEqualTo(AuditMetadataSize.BUDGET);
    assertThat(fitted).containsEntry("inputRefs", List.of("*"))
        .containsEntry("verifiedInputsRequired", true)
        .containsEntry("outcome", "REQUIRE_APPROVAL")
        .containsEntry("role", m.get("role"))
        .containsEntry("ruleId", m.get("ruleId"))
        .containsEntry("controls", m.get("controls"))
        .containsEntry("profile", "eu-ai-act-high-risk");
    @SuppressWarnings("unchecked")
    List<String> degraded = (List<String>) fitted.get(EvaluationMetadataBudget.KEY_METADATA_DEGRADED);
    assertThat(degraded).endsWith("inputRefs");
  }

  /**
   * 结论键本身超预算（病态策略：role / ruleId / controls 满是需转义字符）：只留下不可审批的最小键集。
   * 没有 outcome，from-evidence 按 evidence_not_approvable 拒绝；绝不留下缺 role 却可审批的事件。
   */
  @Test
  void 结论键本身超预算时降为最小键集_去掉outcome与role() {
    Map<String, Object> m = normal();
    m.put("role", "\"".repeat(255));
    m.put("ruleId", "\u0001".repeat(255));
    m.put("controls", IntStream.range(0, 16).mapToObj(i -> "\"".repeat(64)).toList());

    Map<String, Object> fitted = EvaluationMetadataBudget.fit(m);

    assertThat(AuditMetadataSize.measure(fitted)).isLessThanOrEqualTo(AuditMetadataSize.BUDGET);
    assertThat(fitted).doesNotContainKeys("outcome", "role", "controls", "reason", "applicantId")
        .containsEntry("correlationId", CID)
        .containsEntry("ruleId", m.get("ruleId"))
        .containsEntry("profile", "eu-ai-act-high-risk")
        .containsEntry("policyVersionId", 7L)
        .containsEntry("sourceHash", HASH)
        .containsEntry("sourceEnvelopeSha256", HASH)
        .containsEntry("inputRefs", List.of("*"))
        .containsEntry("verifiedInputsRequired", true)
        .containsEntry("inputSource", "declared");
    @SuppressWarnings("unchecked")
    List<String> degraded = (List<String>) fitted.get(EvaluationMetadataBudget.KEY_METADATA_DEGRADED);
    assertThat(degraded).endsWith(EvaluationMetadataBudget.OVERSIZE);
  }

  /** 最小键集的上界：保留的每个文本键都撑到 255 个 6 倍转义字符时退到只剩相关键（截到列宽 64），仍在预算内。 */
  @Test
  void 最小键集在最坏取值下仍在预算内() {
    String esc = "\u0001".repeat(255);
    Map<String, Object> m = new HashMap<>();
    m.put("correlationId", esc);
    m.put("ruleId", esc);
    m.put("profile", esc);
    m.put("sourceHash", esc);
    m.put("sourceEnvelopeSha256", esc);
    m.put("policyVersionId", Long.MIN_VALUE);
    m.put("inputRefs", largestRefs());
    m.put("verifiedInputsRequired", true);
    m.put("agentSource", "declared");
    m.put("inputSource", "declared");
    m.put("outcome", esc);
    m.put("role", esc);

    Map<String, Object> fitted = EvaluationMetadataBudget.fit(m);

    assertThat(AuditMetadataSize.measure(fitted)).isLessThanOrEqualTo(AuditMetadataSize.BUDGET);
    assertThat(fitted).doesNotContainKeys("outcome", "role")
        .containsEntry("correlationId", esc.substring(0, 64))
        .containsEntry("inputSource", "declared");
  }

  private static void assertProtectedUnchanged(Map<String, Object> before, Map<String, Object> after) {
    for (String k : List.of("correlationId", "outcome", "ruleId", "role", "controls", "profile", "policyVersionId",
        "sourceHash", "sourceEnvelopeSha256", "inputRefs", "verifiedInputsRequired", "inputSource", "agentSource")) {
      assertThat(after.get(k)).as(k).isEqualTo(before.get(k));
    }
  }

  /** 恰在 512 字节上限内的引用集。 */
  public static List<String> largestRefs() {
    List<String> refs = new java.util.ArrayList<>();
    int i = 0;
    while (true) {
      List<String> next = new java.util.ArrayList<>(refs);
      next.add("context.req.field" + i++);
      if (EvaluationEvidence.inputRefsJsonBytes(next) > EvaluationEvidence.MAX_INPUT_REFS_BYTES
          || next.size() > EvaluationEvidence.MAX_INPUT_REFS) {
        return refs;
      }
      refs = next;
    }
  }
}
