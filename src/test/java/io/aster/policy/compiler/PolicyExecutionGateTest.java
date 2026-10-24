package io.aster.policy.compiler;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.policy.stability.ToolchainIdentityProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PolicyExecutionGateTest {
    ValidationRepository validation;
    PolicySourceRepository versions;
    PolicyCompiler compiler;
    ToolchainIdentityProvider toolchains;
    PolicyExecutionGate gate;

    @BeforeEach void setUp() {
        validation = mock(ValidationRepository.class);
        versions = mock(PolicySourceRepository.class);
        compiler = mock(PolicyCompiler.class);
        toolchains = mock(ToolchainIdentityProvider.class);
        when(toolchains.currentToolchainId()).thenReturn("tc");
        gate = new PolicyExecutionGate();
        gate.validations = validation;
        gate.versions = versions;
        gate.compiler = compiler;
        gate.toolchains = toolchains;
        gate.gateMode = "on";
        gate.typecheckMode = "enforce";
    }

    @Test void validCachedStatusIsCachedOnlyForValid() {
        when(validation.findStatus(7, "tc")).thenReturn(Optional.of(new ValidationRepository.ValidationStatus("valid", List.of())));
        assertThat(gate.check(7).reason()).isEqualTo("valid");
        assertThat(gate.check(7).reason()).isEqualTo("valid-cached");
        verify(validation, times(1)).findStatus(7, "tc");
    }

    @Test void invalidStatusEnforceRefusesAndWarnAllows() {
        var invalid = new ValidationRepository.ValidationStatus("invalid", List.of());
        when(validation.findStatus(8, "tc")).thenReturn(Optional.of(invalid));
        gate.typecheckMode = "enforce";
        assertThat(gate.check(8).reason()).isEqualTo("invalid-enforce");
        gate.typecheckMode = "warn";
        assertThat(gate.check(8).reason()).isEqualTo("invalid-warn");
        assertThat(gate.check(8).allowed()).isTrue();
    }

    @Test void invalidRowsAreNeverCached() {
        when(validation.findStatus(12, "tc")).thenReturn(Optional.of(
            new ValidationRepository.ValidationStatus("invalid", List.of())));
        gate.check(12);
        gate.check(12);
        verify(validation, times(2)).findStatus(12, "tc");
    }

    @Test void missingStatusSynchronouslyValidatesThenPersists() {
        PolicyVersion version = new PolicyVersion(); version.id = 9L;
        when(validation.findStatus(9, "tc")).thenReturn(Optional.empty());
        when(versions.findVersionById(9L)).thenReturn(Optional.of(version));
        when(compiler.typecheckVersion(version)).thenReturn(CompilationResult.success("{}", CompilationMetadata.empty()));
        assertThat(gate.check(9).reason()).isEqualTo("sync-validated");
        verify(validation).insertIfAbsent(9, "tc", "valid", List.of(), null, false);
    }

    @Test void missingStatusInvalidRefusesInEnforce() {
        PolicyVersion version = new PolicyVersion(); version.id = 10L;
        when(validation.findStatus(10, "tc")).thenReturn(Optional.empty());
        when(versions.findVersionById(10L)).thenReturn(Optional.of(version));
        var diagnostic = new CompilationResult.Diagnostic(1, 1, 1, 1, "bad", "E012", "error", null, null, true);
        when(compiler.typecheckVersion(version)).thenReturn(CompilationResult.failure(List.of("bad"), List.of(diagnostic)));
        assertThat(gate.check(10).reason()).isEqualTo("invalid-enforce");
    }

    @Test void missingInvalidStatusWarnsButAllows() {
        PolicyVersion version = new PolicyVersion(); version.id = 13L;
        when(validation.findStatus(13, "tc")).thenReturn(Optional.empty());
        when(versions.findVersionById(13L)).thenReturn(Optional.of(version));
        var diagnostic = new CompilationResult.Diagnostic(1, 1, 1, 1, "bad", "E012", "warning", null, null, false);
        when(compiler.typecheckVersion(version)).thenReturn(CompilationResult.success("{}", CompilationMetadata.empty(), List.of(diagnostic)));
        gate.typecheckMode = "warn";
        var decision = gate.check(13);
        assertThat(decision.reason()).isEqualTo("invalid-warn");
        assertThat(decision.allowed()).isTrue();
        verify(validation).insertIfAbsent(13, "tc", "invalid", List.of(diagnostic), null, false);
    }

    @Test void storageFailuresRefuseEvenInWarnAndGateOffAllows() {
        when(validation.findStatus(11, "tc")).thenThrow(new IllegalStateException("offline"));
        gate.typecheckMode = "warn";
        assertThat(gate.check(11).reason()).isEqualTo("storage-error");
        gate.gateMode = "off";
        assertThat(gate.check(11).allowed()).isTrue();
    }

    @Test void writeFailureRefusesEvenAfterSuccessfulSynchronousValidation() {
        PolicyVersion version = new PolicyVersion(); version.id = 14L;
        when(validation.findStatus(14, "tc")).thenReturn(Optional.empty());
        when(versions.findVersionById(14L)).thenReturn(Optional.of(version));
        when(compiler.typecheckVersion(version)).thenReturn(CompilationResult.success("{}", CompilationMetadata.empty()));
        doThrow(new IllegalStateException("offline")).when(validation).insertIfAbsent(14, "tc", "valid", List.of(), null, false);
        gate.typecheckMode = "warn";
        assertThat(gate.check(14).reason()).isEqualTo("storage-error");
    }
}
