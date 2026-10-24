package io.aster.policy.compiler;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.policy.stability.ToolchainIdentityProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PolicyExecutionGate {
    private static final Logger LOG = Logger.getLogger(PolicyExecutionGate.class);
    @Inject ValidationRepository validations;
    @Inject PolicySourceRepository versions;
    @Inject PolicyCompiler compiler;
    @Inject ToolchainIdentityProvider toolchains;
    @ConfigProperty(name = "aster.compile.gate", defaultValue = "on") String gateMode;
    @ConfigProperty(name = "aster.compile.typecheck", defaultValue = "enforce") String typecheckMode;
    private final ConcurrentHashMap<String, Boolean> validCache = new ConcurrentHashMap<>();

    public GateDecision check(long versionId) {
        String toolchain = toolchains.currentToolchainId();
        if ("off".equalsIgnoreCase(gateMode)) return new GateDecision(true, List.of(), "valid-cached", toolchain);
        String key = versionId + "|" + toolchain;
        if (validCache.containsKey(key)) return new GateDecision(true, List.of(), "valid-cached", toolchain);
        try {
            var stored = validations.findStatus(versionId, toolchain);
            if (stored.isPresent()) return decide(stored.get().status(), stored.get().diagnostics(), key, toolchain);
            PolicyVersion version = versions.findVersionById(versionId).orElse(null);
            if (version == null) return new GateDecision(false, List.of(), "invalid-enforce", toolchain);
            CompilationResult checked = compiler.typecheckVersion(version);
            boolean valid = ValidationRepository.isValid(checked);
            var diagnostics = ValidationRepository.diagnosticsOf(checked);
            validations.insertIfAbsent(versionId, toolchain, valid ? "valid" : "invalid", diagnostics, null);
            return decide(valid ? "valid" : "invalid", diagnostics, key, toolchain, "sync-validated");
        } catch (Exception e) {
            LOG.errorf(e, "策略版本验证存储失败: versionId=%d", versionId);
            return new GateDecision(false, List.of(), "storage-error", toolchain);
        }
    }

    private GateDecision decide(String status, List<CompilationResult.Diagnostic> diagnostics,
                                String key, String toolchain) {
        return decide(status, diagnostics, key, toolchain, null);
    }

    private GateDecision decide(String status, List<CompilationResult.Diagnostic> diagnostics,
                                String key, String toolchain, String validReason) {
        if ("valid".equals(status)) {
            validCache.putIfAbsent(key, Boolean.TRUE);
            return new GateDecision(true, diagnostics, validReason == null ? "valid" : validReason, toolchain);
        }
        boolean enforce = "enforce".equalsIgnoreCase(typecheckMode);
        if (!enforce) LOG.warnf("策略版本类型检查无效，warn 模式继续执行: toolchain=%s", toolchain);
        return new GateDecision(!enforce, diagnostics, enforce ? "invalid-enforce" : "invalid-warn", toolchain);
    }

    /** 以类型化异常上抛，由 REST 层 EvaluationFailure 归因为 422 policy_version_invalid。 */
    public static void reject(GateDecision decision) {
        throw new PolicyVersionInvalidException(decision.diagnostics(), decision.toolchainId(), decision.reason());
    }

    public record GateDecision(boolean allowed, List<CompilationResult.Diagnostic> diagnostics,
                               String reason, String toolchainId) {}
}
