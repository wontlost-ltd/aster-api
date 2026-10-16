package io.aster.llm.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.llm.safety.SafetyVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 安全拒答流：必须是与其它帧一致的裸 JSON data 项，不得自带 SSE 前缀（否则被 RESTEasy 二次封装）。 */
class AiAssistantResourceRefusalStreamTest {

    @Test
    @DisplayName("拒答流恰好一项，为裸 JSON：type=error 且保留 error/message/rule_id")
    void refusalIsSingleBareJsonFrame() throws Exception {
        SafetyVerdict v = SafetyVerdict.block("rule-x", "超出范围 \"引号\"\n换行");
        List<String> items = new AiAssistantResource().refusalStream(v)
            .collect().asList().await().atMost(Duration.ofSeconds(1));

        assertThat(items).hasSize(1);
        String item = items.get(0);
        assertThat(item).doesNotStartWith("event:").doesNotContain("data:");
        JsonNode n = new ObjectMapper().readTree(item);
        assertThat(n.get("type").asText()).isEqualTo("error");
        assertThat(n.get("error").asText()).isEqualTo("out_of_scope");
        assertThat(n.get("message").asText()).isEqualTo("超出范围 \"引号\"\n换行");
        assertThat(n.get("rule_id").asText()).isEqualTo("rule-x");
    }
}
