package io.aster.policy.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

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

  /** 每个可变长字段都撑到上界之外：逐键断言被裁到上界，整体序列化仍低于 guard 预算 3800。 */
  @Test void 裁剪保证最坏情况序列化长度小于3800() throws Exception {
    List<String> controls = IntStream.range(0, 40).mapToObj(i -> "C".repeat(100) + i).toList();
    EvaluationEvidence e = new EvaluationEvidence("c".repeat(500), "O".repeat(500), "R".repeat(500), controls,
        Long.MAX_VALUE, new AgentIdentity("p".repeat(500), "m".repeat(500), "v".repeat(500), "s".repeat(500)));
    Map<String, Object> m = e.toMetadata();
    List<?> clipped = (List<?>) m.get("controls");
    assertThat(clipped).hasSize(EvaluationEvidence.MAX_CONTROLS);
    assertThat(clipped).allSatisfy(c -> assertThat(c.toString()).hasSize(EvaluationEvidence.MAX_CONTROL_LENGTH));
    for (String key : List.of("correlationId", "outcome", "ruleId", EvaluationEvidence.KEY_AGENT_PROVIDER,
        EvaluationEvidence.KEY_AGENT_MODEL, EvaluationEvidence.KEY_AGENT_VERSION, EvaluationEvidence.KEY_AGENT_SESSION)) {
      assertThat(m.get(key).toString()).as(key).hasSize(AgentIdentity.MAX_TEXT);
    }
    String json = new ObjectMapper().writeValueAsString(m);
    assertThat(json.length()).isLessThan(3800);
  }

  @Test void controls为null时不写键() {
    EvaluationEvidence e = new EvaluationEvidence("c-4", null, null, null, null, null);
    assertThat(e.toMetadata()).containsOnlyKeys("correlationId");
  }

  @Test void controls中的null项被丢弃() {
    EvaluationEvidence e = new EvaluationEvidence("c-5", null, null, Arrays.asList("a", null, "b"), null, null);
    assertThat(e.toMetadata()).containsEntry("controls", List.of("a", "b"));
  }

  @Test void agent五键名常量与线上键名一致() {
    EvaluationEvidence e = new EvaluationEvidence("c-6", null, null, List.of(), null,
        new AgentIdentity("p", "m", "v", "s"));
    assertThat(e.toMetadata()).containsEntry("agentProvider", "p").containsEntry("agentModel", "m")
        .containsEntry("agentVersion", "v").containsEntry("agentSession", "s").containsEntry("agentSource", "declared");
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
