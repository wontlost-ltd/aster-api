package io.aster.policy.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationEvidenceTest {
  @Test void toMetadata扁平键且null不写键() {
    EvaluationEvidence e = new EvaluationEvidence("c-1", "ALLOW", "R-1", List.of("GDPR:ART17"), null,
        new AgentIdentity("anthropic", "claude", null, "s-1"));
    assertThat(e.toMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
        "correlationId", "c-1", "outcome", "ALLOW", "ruleId", "R-1", "controls", List.of("GDPR:ART17"),
        "agentProvider", "anthropic", "agentModel", "claude", "agentSession", "s-1", "agentSource", "declared"));
  }

  @Test void 无agent无规则时只剩correlationId() {
    EvaluationEvidence e = new EvaluationEvidence("c-2", null, null, List.of(), null, null);
    assertThat(e.toMetadata()).containsOnlyKeys("correlationId");
  }

  @Test void of生成UUID相关键() {
    EvaluationEvidence e = EvaluationEvidence.of("DENY", null, null, 7L, null);
    assertThat(e.correlationId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    assertThat(e.toMetadata()).containsEntry("policyVersionId", 7L);
  }

  @Test void 裁剪保证最坏情况序列化长度小于3800() throws Exception {
    List<String> controls = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "C".repeat(100) + i).toList();
    EvaluationEvidence e = new EvaluationEvidence("c-3", "REQUIRE_APPROVAL", "R".repeat(500), controls, 1L,
        new AgentIdentity("p".repeat(500), "m".repeat(500), "v".repeat(500), "s".repeat(500)));
    Map<String, Object> m = e.toMetadata();
    assertThat(((List<?>) m.get("controls"))).hasSize(16);
    assertThat(((List<?>) m.get("controls")).get(0).toString()).hasSize(64);
    assertThat(m.get("ruleId").toString()).hasSize(255);
    assertThat(m.get("agentProvider").toString()).hasSize(255);
    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
    assertThat(json.length()).isLessThan(3800);
  }

  @Test void 超长correlationId被裁剪到255() {
    EvaluationEvidence e = new EvaluationEvidence("x".repeat(300), null, null, List.of(), null, null);
    assertThat(e.toMetadata().get("correlationId").toString()).hasSize(255);
  }

  @Test void 裁剪不留下孤立代理字符() {
    String s = "a".repeat(254) + "\uD83D\uDE00";
    EvaluationEvidence e = new EvaluationEvidence("c", s, null, List.of(s.substring(0, 63) + "\uD83D\uDE00"), null, null);
    String outcome = e.toMetadata().get("outcome").toString();
    assertThat(outcome.length()).isLessThanOrEqualTo(255);
    assertThat(Character.isSurrogate(outcome.charAt(outcome.length() - 1))).isFalse();
    String control = ((List<?>) e.toMetadata().get("controls")).get(0).toString();
    assertThat(Character.isSurrogate(control.charAt(control.length() - 1))).isFalse();
  }
}
