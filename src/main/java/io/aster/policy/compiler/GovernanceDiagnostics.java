package io.aster.policy.compiler;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.GovernanceChecker;
import aster.core.typecheck.model.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** 行列取 core span 起点（1-based），缺失时归一为 1/1；severity 用小写字符串契约。 */
    private static CompilationResult.Diagnostic toCompilationDiagnostic(Diagnostic d) {
        CoreModel.Position start = d.span().map(o -> o.start).orElse(null);
        int line = start != null && start.line > 0 ? start.line : 1;
        int col = start != null && start.col > 0 ? start.col : 1;
        return new CompilationResult.Diagnostic(
            line, col, line, col,
            d.message(),
            d.code().code(),
            d.severity().name().toLowerCase(Locale.ROOT),
            null, null,
            false);
    }
}
