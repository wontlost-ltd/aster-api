package io.aster.policy.rest.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.evidence.AgentIdentity;
import org.junit.jupiter.api.Test;

class EvaluationResponseEvidenceTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test void withEvidence输出evidence对象且字母序位置正确() throws Exception {
    EvaluationResponse r = EvaluationResponse.success(42, 3L).withEvidence("c-1");
    String json = mapper.writeValueAsString(r);
    assertThat(json).contains("\"evidence\":{\"correlationId\":\"c-1\"}");
    assertThat(json.indexOf("\"error\"")).isLessThan(json.indexOf("\"evidence\""));
    assertThat(json.indexOf("\"evidence\"")).isLessThan(json.indexOf("\"executionTimeMs\""));
  }

  @Test void 无evidence时不输出键() throws Exception {
    assertThat(mapper.writeValueAsString(EvaluationResponse.success(42, 3L))).doesNotContain("evidence");
  }

  @Test void withReplayMetadata与withTraceSkeleton保留evidence() {
    EvaluationResponse r = EvaluationResponse.success(1, 1L).withEvidence("c-9").withReplayMetadata(null).withTraceSkeleton(null);
    assertThat(r.evidence().correlationId()).isEqualTo("c-9");
  }

  @Test void 请求记录接受agent字段() throws Exception {
    EvaluationRequest req = mapper.readValue(
        "{\"policyModule\":\"m\",\"policyFunction\":\"f\",\"context\":[{}],\"agent\":{\"provider\":\"anthropic\",\"model\":\"claude\"}}",
        EvaluationRequest.class);
    assertThat(req.agent()).isEqualTo(new AgentIdentity("anthropic", "claude", null, null));
    assertThat(mapper.readValue("{\"policy\":\"{}\",\"context\":{}}", JsonPolicyRequest.class).agent()).isNull();
  }
}
