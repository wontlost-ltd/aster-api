package io.aster.policy.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.aster.common.JacksonMappers;
import io.aster.policy.api.EvaluationFailure;
import io.aster.policy.rest.model.EvaluationResponse;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败响应的形状：HTTP 状态取自归因结果，响应体仍是 EvaluationResponse——
 * 旧字段 error 保留（老客户端照常解析），新增 code。不启动 Quarkus。
 */
class PolicyEvaluationFailureResponseTest {

    @Test
    void 状态与响应体() throws Exception {
        WebApplicationException ex = PolicyEvaluationResource.failureException(
            new EvaluationFailure(409, "no_executable_version", "Policy m.f has no executable version"), 12, "corr-1");

        assertThat(ex.getResponse().getStatus()).isEqualTo(409);
        EvaluationResponse body = (EvaluationResponse) ex.getResponse().getEntity();
        JsonNode json = JacksonMappers.DEFAULT.readTree(JacksonMappers.DEFAULT.writeValueAsString(body));
        assertThat(json.path("error").asText()).isEqualTo("Policy m.f has no executable version");
        assertThat(json.path("code").asText()).isEqualTo("no_executable_version");
        assertThat(json.path("result").isNull()).isTrue();
        assertThat(json.path("evidence").path("correlationId").asText()).isEqualTo("corr-1");
    }

    @Test
    void 成功响应不输出code_字节不变() throws Exception {
        String json = JacksonMappers.DEFAULT.writeValueAsString(EvaluationResponse.success(1, 3));
        assertThat(json).doesNotContain("\"code\"");
    }
}
