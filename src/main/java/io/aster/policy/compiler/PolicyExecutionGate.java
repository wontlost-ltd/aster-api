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

    /** 一次完成读阶段与判定阶段；调用方不在意连接占用时使用。 */
    public GateDecision check(long versionId) {
        return check(prefetch(versionId));
    }

    /**
     * 读阶段：只做内存与数据库读取（须在事务内调用），不做类型检查。
     * 能直接定论（门禁关闭、已缓存为有效、已有判定行、版本不存在、存储异常）时 decided 非空；
     * 否则带回待同步验证的版本，交给 {@link #check(Prefetch)} 在事务外完成 CPU 密集的类型检查。
     */
    public Prefetch prefetch(long versionId) {
        String toolchain = toolchains.currentToolchainId();
        if ("off".equalsIgnoreCase(gateMode)) return Prefetch.decided(new GateDecision(true, List.of(), "valid-cached", toolchain));
        String key = versionId + "|" + toolchain;
        if (validCache.containsKey(key)) return Prefetch.decided(new GateDecision(true, List.of(), "valid-cached", toolchain));
        try {
            var stored = validations.findStatus(versionId, toolchain);
            if (stored.isPresent()) return Prefetch.decided(decide(stored.get().status(), stored.get().diagnostics(), key, toolchain));
            PolicyVersion version = versions.findVersionById(versionId).orElse(null);
            if (version == null) return Prefetch.decided(new GateDecision(false, List.of(), "invalid-enforce", toolchain));
            return new Prefetch(null, version, key, toolchain);
        } catch (Exception e) {
            LOG.errorf(e, "策略版本验证存储失败: versionId=%d", versionId);
            return Prefetch.decided(new GateDecision(false, List.of(), "storage-error", toolchain));
        }
    }

    /**
     * 判定阶段：读阶段已定论则直接返回；否则对版本快照做类型检查并以独立短事务落库。
     * 不依赖调用方事务，应在事务外调用，避免类型检查期间占着调用方的连接。
     */
    public GateDecision check(Prefetch prefetch) {
        if (prefetch.decided() != null) return prefetch.decided();
        PolicyVersion version = prefetch.version();
        try {
            CompilationResult checked = compiler.typecheckVersion(version);
            boolean valid = ValidationRepository.isValid(checked);
            var diagnostics = ValidationRepository.diagnosticsOf(checked);
            validations.insertIfAbsent(version.id, prefetch.toolchain(), valid ? "valid" : "invalid", diagnostics, null, false);
            return decide(valid ? "valid" : "invalid", diagnostics, prefetch.key(), prefetch.toolchain(), "sync-validated");
        } catch (Exception e) {
            LOG.errorf(e, "策略版本验证存储失败: versionId=%d", version.id);
            return new GateDecision(false, List.of(), "storage-error", prefetch.toolchain());
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

    /** 读阶段结果：decided 非空即已定论；否则 version 是待验证的已脱离事务的版本快照。 */
    public record Prefetch(GateDecision decided, PolicyVersion version, String key, String toolchain) {
        static Prefetch decided(GateDecision decision) {
            return new Prefetch(decision, null, null, decision.toolchainId());
        }
    }
}
