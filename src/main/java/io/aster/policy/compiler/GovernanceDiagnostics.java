package io.aster.policy.compiler;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.governance.GovernanceChecker;
import aster.core.typecheck.model.Diagnostic;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 治理诊断（W700/E701/E702/W704）→ 编译诊断。
 *
 * <p>编译端点此前不跑治理检查；ADR 0045 起与稳定性（W600）、规则冲突（W601）诊断
 * 走同一 diagnostics 通道合流。治理诊断 blocking 恒 false，不改变编译成功与否；
 * 档案违规（E705/E706，ADR 0046 §4）保留 error 严重级，由 cloud 保存门据此拒绝保存。
 * 只调用 {@link GovernanceChecker#checkModule}，不跑完整类型检查。
 * 检查器抛出运行时异常时降级为无治理诊断（与 RuleConflictDiagnostics 同构）：
 * 该路径同时服务 /validate 与求值的缓存编译，治理检查绝不能把成功编译变成失败。
 */
public final class GovernanceDiagnostics {

    private static final Logger LOG = Logger.getLogger(GovernanceDiagnostics.class);

    private GovernanceDiagnostics() {}

    /** 对 Core IR 模块跑治理检查，逐条转换为编译诊断（保持 core 输出顺序）。 */
    public static List<CompilationResult.Diagnostic> scan(CoreModel.Module core, GovernanceChecker checker) {
        try {
            return map(checker.checkModule(core));
        } catch (RuntimeException e) {
            LOG.warnf("治理检查失败（降级为不提示，不影响编译）: %s", e.getMessage());
            return List.of();
        }
    }

    private static List<CompilationResult.Diagnostic> map(List<Diagnostic> found) {
        List<CompilationResult.Diagnostic> mapped = new ArrayList<>(found.size());
        for (Diagnostic d : found) {
            mapped.add(toCompilationDiagnostic(d));
        }
        return mapped;
    }

    /**
     * 行列取 core span（1-based），缺失时归一为 1/1，终点缺失时取起点。
     * cloud 保存门按 severity==='error' 拒绝。code 保留原值（E701/E702 仍为 E70x）。
     */
    private static CompilationResult.Diagnostic toCompilationDiagnostic(Diagnostic d) {
        CoreModel.Origin origin = d.span().orElse(null);
        CoreModel.Position start = origin == null ? null : origin.start;
        CoreModel.Position end = origin == null || origin.end == null ? start : origin.end;
        // ADR 0046：档案违规（E705/E706）是模块作者自愿声明的约束，须阻止保存，保留 error；
        // 其余治理码按 ADR 0045 在编译通道恒为 warning
        String severity = isProfileCode(d.code()) ? "error" : "warning";
        return new CompilationResult.Diagnostic(
            lineOf(start), colOf(start), lineOf(end), colOf(end),
            d.message(),
            d.code().code(),
            severity,
            null, null,
            false);
    }

    private static boolean isProfileCode(ErrorCode code) {
        return code == ErrorCode.GOV_PROFILE_UNKNOWN || code == ErrorCode.GOV_PROFILE_VIOLATION;
    }

    private static int lineOf(CoreModel.Position p) {
        return p != null && p.line > 0 ? p.line : 1;
    }

    private static int colOf(CoreModel.Position p) {
        return p != null && p.col > 0 ? p.col : 1;
    }
}
