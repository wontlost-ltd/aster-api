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

    /** ADR 0060 §7.4：cloud 回源已下发版本冻结的 aliasSet（kind → 别名数组），必须解析进 record。 */
    @Test
    void sourceClientParsesAliasSet() {
        JsonObject body = new JsonObject(Map.of("versionId", "pv-1", "policyId", "p", "version", 2,
            "content", "模块 M。", "aliasSet", Map.of("PLUS", List.of("接 着"), "RETURN", List.of("低头"))));
        var source = PolicyVersionSourceClient.fromJson(body);
        assertThat(source.aliasSet())
            .containsEntry("PLUS", List.of("接 着"))
            .containsEntry("RETURN", List.of("低头"))
            .hasSize(2);
    }

    @Test
    void 响应不带别名或别名非对象时为空Map() {
        JsonObject plain = new JsonObject(Map.of("versionId", "pv-1", "policyId", "p", "content", "Modul M."));
        assertThat(PolicyVersionSourceClient.fromJson(plain).aliasSet()).isEmpty();
        assertThat(PolicyVersionSourceClient.fromJson(plain.copy().put("aliasSet", "x")).aliasSet()).isEmpty();
        // 非数组的值、数组里的非字符串元素一律丢弃，不让坏数据变成 ClassCastException 打断整段重放
        JsonObject odd = plain.copy().put("aliasSet", new JsonObject()
            .put("PLUS", new io.vertx.core.json.JsonArray().add("接 着").add(1))
            .put("RETURN", "低头"));
        assertThat(PolicyVersionSourceClient.fromJson(odd).aliasSet())
            .containsExactly(Map.entry("PLUS", List.of("接 着")));
    }

    @Test
    void 旧构造器的别名为空Map() {
        assertThat(new PolicyVersionSourceClient.VersionSource("pv", "p", 1, "c").aliasSet()).isEmpty();
        assertThat(new PolicyVersionSourceClient.VersionSource("pv", "p", 1, "c", Map.of()).aliasSet()).isEmpty();
    }
}
