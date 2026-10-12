package io.aster.policy.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CedarEngine 的纯 JUnit 测试：直接调用 cedar-java 原生库，不启动 Quarkus。 */
class CedarEngineTest {

    @Test
    void permitAndForbidWithIds() {
        var engine = new CedarEngine();
        String policies = """
            @id("r#1")
            forbid (principal, action, resource) when { action == Aster::Action::"delete" };
            @id("r#2")
            permit (principal, action, resource) when { !(action == Aster::Action::"delete") };
            """;
        var ids = List.of("r#1", "r#2");
        var req = new CedarEngine.CedarRequest("p", "agent", List.of(), "delete", "doc", "d1", Map.of());
        var d = engine.authorize(policies, ids, req);
        assertThat(d.allow()).isFalse();
        assertThat(d.determiningPolicyIds()).containsExactly("r#1");
        var ok = engine.authorize(policies, ids, new CedarEngine.CedarRequest("p", "agent", List.of(), "read", "doc", "d1", Map.of()));
        assertThat(ok.allow()).isTrue();
        assertThat(ok.determiningPolicyIds()).containsExactly("r#2");
    }

    @Test
    void strictValidationRejectsUnguardedOptionalAccess() {
        var engine = new CedarEngine();
        String schema = """
            {"Aster":{"entityTypes":{"Principal":{"shape":{"type":"Record","attributes":{"type":{"type":"String"},"roles":{"type":"Set","element":{"type":"String"}}}}},
             "Resource":{"shape":{"type":"Record","attributes":{"type":{"type":"String"}}}}},
             "actions":{"read":{"appliesTo":{"principalTypes":["Principal"],"resourceTypes":["Resource"],
               "context":{"type":"Record","attributes":{"amount":{"type":"Long","required":false}}}}}}}}
            """;
        String bad = "@id(\"x#1\")\npermit (principal, action, resource) when { context.amount > 1 };\n";
        String good = "@id(\"x#1\")\npermit (principal, action, resource) when { context has amount && context.amount > 1 };\n";
        assertThat(engine.validate(bad, List.of("x#1"), schema).ok()).isFalse();
        assertThat(engine.validate(good, List.of("x#1"), schema).ok()).isTrue();
    }

    @Test
    void missingAttributeIsErrorNotMatch() {
        var engine = new CedarEngine();
        String policies = "@id(\"x#1\")\npermit (principal, action, resource) when { context.amount > 1 };\n";
        var d = engine.authorize(policies, List.of("x#1"), new CedarEngine.CedarRequest("p", "agent", List.of(), "read", "doc", "d", Map.of()));
        assertThat(d.allow()).isFalse();
        assertThat(d.determiningPolicyIds()).isEmpty();
    }
}
