package io.aster.policy.compiler;

import io.aster.policy.entity.ArtifactType;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyCompilerTypecheckVersionTest {
    @Test
    void artifactAndCoreJsonDoNotBypassSourceTypecheck() {
        PolicySourceRepository repository = mock(PolicySourceRepository.class);
        PolicyCompiler compiler = new PolicyCompiler(repository, new io.aster.policy.stability.StabilityEnforcement(),
            new io.aster.policy.analysis.RuleConflictDiagnostics());
        String source = """
            Module M.
            Define Status as one of Approved, Rejected.
            Define Decision has status, reason.
            Rule r given x as Int, produce Decision:
              Return Decision with status set to Approved, reason set to "ok".
            """;
        PolicyVersion version = new PolicyVersion();
        version.id = 91L;
        version.content = source;
        version.locale = "en-US";
        version.coreJson = "{\"module\":\"M\"}";
        PolicyArtifact artifact = new PolicyArtifact();
        artifact.id = UUID.randomUUID();
        artifact.policyVersionId = 91L;
        artifact.artifactType = ArtifactType.CORE_JSON.name();
        artifact.content = "{\"module\":\"M\"}".getBytes(StandardCharsets.UTF_8);
        artifact.contentSha256 = "unused";
        artifact.compilerOpts = "{}";
        artifact.createdAt = Instant.now();
        when(repository.findCoreJsonArtifact(91L)).thenReturn(Optional.of(artifact));
        when(repository.findVersionById(91L)).thenReturn(Optional.of(version));

        var checked = compiler.typecheckVersion(version);
        var runtime = compiler.compile(91L);

        assertThat(checked.isSuccess()).isFalse();
        assertThat(checked.getDiagnostics()).anySatisfy(d -> assertThat(d.code()).isEqualTo("E012"));
        assertThat(runtime.isSuccess()).isTrue();
    }
}
