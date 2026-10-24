package io.aster.policy.api;

import io.aster.policy.compiler.PolicyExecutionGate;
import io.aster.policy.compiler.ValidationRepository;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.policy.stability.ToolchainIdentityProvider;
import jakarta.transaction.Transactional;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import java.util.Optional;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(GateOnTestProfile.class)
class PolicyEvaluationGateIT {
    @Inject PolicyExecutionGate gate;
    @InjectMock PolicySourceRepository versions;
    @InjectMock ValidationRepository validations;
    @InjectMock ToolchainIdentityProvider toolchains;

    @Test @Transactional void missingProofSynchronouslyRejectsSourceWithE012() {
        PolicyVersion version = new PolicyVersion();
        version.id = 987654L;
        version.locale = "en-US";
        version.content = """
            Module M.
            Define Status as one of Approved, Rejected.
            Define Decision has status, reason.
            Rule r given x as Int, produce Decision:
              Return Decision with status set to Approved, reason set to "ok".
            """;
        when(toolchains.currentToolchainId()).thenReturn("tc");
        when(validations.findStatus(987654L, "tc")).thenReturn(Optional.empty());
        when(versions.findVersionById(987654L)).thenReturn(Optional.of(version));
        var decision = gate.check(987654L);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.diagnostics()).anySatisfy(d -> assertThat(d.code()).isEqualTo("E012"));
    }

    /** 端点级：门禁拒绝必须以 422 + policy_version_invalid 回给调用方，而不是被包成 500。 */
    @Test void evaluateEndpointReturns422WhenGateRefuses() {
        io.aster.policy.entity.PolicyCatalog catalog = new io.aster.policy.entity.PolicyCatalog();
        catalog.id = java.util.UUID.randomUUID();
        catalog.defaultVersionId = 987655L;
        PolicyVersion version = new PolicyVersion();
        version.id = 987655L;
        version.tenantId = "default";
        version.moduleName = "M";
        version.functionName = "r";
        version.active = true;
        version.locale = "en-US";
        version.content = """
            Module M.
            Define Status as one of Approved, Rejected.
            Define Decision has status, reason.
            Rule r given x as Int, produce Decision:
              Return Decision with status set to Approved, reason set to "ok".
            """;
        when(toolchains.currentToolchainId()).thenReturn("tc");
        when(validations.findStatus(987655L, "tc")).thenReturn(Optional.empty());
        when(versions.findActiveCatalog("default", "M", "r")).thenReturn(Optional.of(catalog));
        when(versions.findActiveVersion(catalog.id)).thenReturn(Optional.of(version));
        when(versions.findVersionById(987655L)).thenReturn(Optional.of(version));
        io.restassured.RestAssured.given()
            .contentType("application/json")
            .header("X-Tenant-Id", "default")
            .body("{\"policyModule\":\"M\",\"policyFunction\":\"r\",\"context\":[{\"x\":1}]}")
        .when().post("/api/v1/policies/evaluate")
        .then().statusCode(422)
            .body("code", org.hamcrest.Matchers.equalTo("policy_version_invalid"))
            .body("error", org.hamcrest.Matchers.containsString("E012"));
    }
}
