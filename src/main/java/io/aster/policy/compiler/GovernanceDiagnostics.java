package io.aster.policy.compiler;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.GovernanceChecker;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.List;

/**
 * 治理诊断（W700/E701/E702/W704）→ 编译诊断。
 *
 * <p>编译端点此前不跑治理检查；ADR 0045 起与稳定性（W600）、规则冲突（W601）诊断
 * 走同一 diagnostics 通道合流。治理诊断只作提示：blocking 恒 false，不改变编译成功与否。
 * 只调用 {@link GovernanceChecker#checkModule}，不跑完整类型检查。
 */
public final class GovernanceDiagnostics {

    private GovernanceDiagnostics() {}

    /** 对 Core IR 模块跑治理检查，逐条转换为编译诊断（保持 core 输出顺序）。 */
    public static List<CompilationResult.Diagnostic> scan(CoreModel.Module core, GovernanceChecker checker) {
        List<Diagnostic> found = checker.checkModule(core);
        List<CompilationResult.Diagnostic> mapped = new ArrayList<>(found.size());
        for (Diagnostic d : found) {
            mapped.add(toCompilationDiagnostic(d));
        }
        return mapped;
    }

    /**
     * 行列取 core span（1-based），缺失时归一为 1/1，终点缺失时取起点。
     * 编译通道上治理诊断恒为 warning——cloud 保存门按 severity==='error' 拒绝，治理检查按
     * ADR 0045 永不阻断；语言层检查器的严重级不变。code 保留原值（E701/E702 仍为 E70x）。
     */
    private static CompilationResult.Diagnostic toCompilationDiagnostic(Diagnostic d) {
        CoreModel.Origin origin = d.span().orElse(null);
        CoreModel.Position start = origin == null ? null : origin.start;
        CoreModel.Position end = origin == null || origin.end == null ? start : origin.end;
        return new CompilationResult.Diagnostic(
            lineOf(start), colOf(start), lineOf(end), colOf(end),
            d.message(),
            d.code().code(),
            "warning",
            null, null,
            false);
    }

    private static int lineOf(CoreModel.Position p) {
        return p != null && p.line > 0 ? p.line : 1;
    }

    private static int colOf(CoreModel.Position p) {
        return p != null && p.col > 0 ? p.col : 1;
    }
}
