package io.aster.policy.replay.batch;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** cloud 版本源码响应的解析：词汇快照随版本下发时原样带入 What-If 重跑（ADR 0051）。 */
class PolicyVersionSourceJsonTest {

    @Test
    void 响应带词汇时解析为词汇Map() {
        JsonObject body = new JsonObject(Map.of("versionId", "pv-1", "policyId", "p", "version", 3,
            "content", "Modul M.", "vocabulary", Map.of("id", "v", "structs",
                List.of(Map.of("canonical", "Driver", "localized", "Fahrer", "kind", "struct")))));
        var source = PolicyVersionSourceClient.fromJson(body);
        assertThat(source.version()).isEqualTo(3);
        assertThat(source.vocabulary()).containsEntry("id", "v").containsKey("structs");
    }

    @Test
    void 响应不带词汇或词汇非对象时视为无词汇() {
        JsonObject plain = new JsonObject(Map.of("versionId", "pv-1", "policyId", "p", "content", "Modul M."));
        assertThat(PolicyVersionSourceClient.fromJson(plain).vocabulary()).isNull();
        assertThat(PolicyVersionSourceClient.fromJson(plain.copy().put("vocabulary", "x")).vocabulary()).isNull();
    }
}
