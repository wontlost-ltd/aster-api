package io.aster.policy.api.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.governance.VerdictView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 {@link PolicyEvaluationResult} 可由不注册任何模块的普通 ObjectMapper 反序列化。
 * Redis 缓存后端按 JSON 存取结果，命中时必须能还原，否则评估在第二次相同请求时失败。
 */
class PolicyEvaluationResultJacksonTest {

    /** Redis 中实际存储的 policy-results 值（本地栈抓取）。 */
    private static final String REDIS_JSON = "{\"result\":{\"reason\":\"customer record deletion\","
        + "\"role\":\"Data Protection Officer\",\"__type\":\"Verdict\",\"outcome\":\"REQUIRE_APPROVAL\"},"
        + "\"executionTimeMs\":6.118652,\"fromCache\":false,"
        + "\"decision\":{\"outcome\":\"REQUIRE_APPROVAL\",\"reason\":\"customer record deletion\","
        + "\"role\":\"Data Protection Officer\"},\"ruleId\":\"CUST-DEL-001\",\"controls\":[\"GDPR:ART17\"]}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void Redis实际存储的JSON可反序列化() throws Exception {
        PolicyEvaluationResult r = mapper.readValue(REDIS_JSON, PolicyEvaluationResult.class);

        assertThat(r.getResult()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) r.getResult()).get("__type")).isEqualTo("Verdict");
        assertThat(r.getExecutionTimeMs()).isEqualTo(6.118652);
        assertThat(r.isFromCache()).isFalse();
        assertThat(r.getDecision())
            .isEqualTo(new VerdictView("REQUIRE_APPROVAL", "Data Protection Officer", "customer record deletion"));
        assertThat(r.getRuleId()).isEqualTo("CUST-DEL-001");
        assertThat(r.getControls()).isEqualTo(List.of("GDPR:ART17"));
    }

    @Test
    void 序列化后反序列化往返相等() throws Exception {
        PolicyEvaluationResult original = mapper.readValue(REDIS_JSON, PolicyEvaluationResult.class);

        String json = mapper.writeValueAsString(original);
        PolicyEvaluationResult back = mapper.readValue(json, PolicyEvaluationResult.class);

        assertThat(back).isEqualTo(original);
    }

    /** issue #459：版本键随结果进 Redis 缓存，命中时还原；旧实例写入的无版本 JSON 读回为 null。 */
    @Test
    void 版本键往返相等且旧JSON读回为null() throws Exception {
        PolicyEvaluationResult withVersion = new PolicyEvaluationResult(true, 1.5, false, null, "R-1", List.of(),
            null, 42L, "ab".repeat(32));
        String json = mapper.writeValueAsString(withVersion);
        assertThat(json).contains("\"policyVersionId\":42").contains("\"sourceHash\":\"" + "ab".repeat(32) + "\"");
        PolicyEvaluationResult back = mapper.readValue(json, PolicyEvaluationResult.class);
        assertThat(back).isEqualTo(withVersion);
        assertThat(back.getPolicyVersionId()).isEqualTo(42L);
        assertThat(back.getSourceHash()).isEqualTo("ab".repeat(32));

        PolicyEvaluationResult legacy = mapper.readValue(REDIS_JSON, PolicyEvaluationResult.class);
        assertThat(legacy.getPolicyVersionId()).isNull();
        assertThat(legacy.getSourceHash()).isNull();
        assertThat(mapper.writeValueAsString(legacy)).doesNotContain("policyVersionId").doesNotContain("sourceHash");
    }

    @Test
    void 缺治理字段且含未知字段时仍可反序列化() throws Exception {
        String json = "{\"result\":true,\"executionTimeMs\":1.5,\"fromCache\":false,\"extra\":1}";

        PolicyEvaluationResult r = mapper.readValue(json, PolicyEvaluationResult.class);

        assertThat(r.getResult()).isEqualTo(true);
        assertThat(r.getExecutionTimeMs()).isEqualTo(1.5);
        assertThat(r.getDecision()).isNull();
        assertThat(r.getRuleId()).isNull();
        assertThat(r.getControls()).isEmpty();
    }

    @Test
    void profile往返且未声明时不写入缓存JSON() throws Exception {
        PolicyEvaluationResult governed = new PolicyEvaluationResult(true, 1.0, false, null, "R-1", List.of(), "governed");
        PolicyEvaluationResult back = mapper.readValue(mapper.writeValueAsString(governed), PolicyEvaluationResult.class);
        assertThat(back).isEqualTo(governed);
        assertThat(back.getProfile()).isEqualTo("governed");

        PolicyEvaluationResult none = new PolicyEvaluationResult(true, 1.0, false, null, "R-1", List.of());
        assertThat(mapper.writeValueAsString(none)).doesNotContain("profile");
        assertThat(mapper.readValue(REDIS_JSON, PolicyEvaluationResult.class).getProfile()).isNull();
    }
}
