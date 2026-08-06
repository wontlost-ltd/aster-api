package io.aster.policy.analysis;

import aster.core.ast.Module;
import io.aster.policy.compiler.CompilationResult;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link RuleConflictAnalyzer} 的静态检测结果接进编译诊断链（Phase 2 接线）。
 *
 * <p><b>为什么它值得接进来</b>：条件漏斗（Phase 1）只能说「这个条件在**最近 N 条**
 * 样本里没命中过」——采样得不出「它永远不会命中」。本分析器走的是纯 AST 静态
 * 可达性，不需要任何执行数据，正好补上采样给不出的那个结论；两者互补。
 *
 * <p><b>严重度恒为 warning，永不阻断编译。</b>理由：
 * <ul>
 *   <li>本分析器的承诺是「宁可漏报，不可误报」，但它经过四轮交叉审查仍数次
 *       被构造出误报（Set 重绑定、Long &gt; 2^53、恒假分支早退……）。一个曾多次
 *       误报的检查不该有能力拒绝用户的策略。</li>
 *   <li>「这条规则永远不会成立」在业务上未必是错误——占位规则、灰度期临时
 *       关闭的分支都可能是有意为之。</li>
 * </ul>
 * 与 ADR 0031 的 stability gate 同理：给提示，不夺决策权。
 *
 * <p><b>诊断码复用 W600 的邻位 W601</b>——⚠️ 见下方 {@link #CONFLICT_CODE} 注释里
 * 记录的技术债。
 */
@ApplicationScoped
public class RuleConflictDiagnostics {

    private static final Logger LOG = Logger.getLogger(RuleConflictDiagnostics.class);

    /**
     * 规则冲突诊断码。
     *
     * <p>⚠️ <b>已知技术债（不假装它不存在）</b>：按 ADR 0031，诊断码本应单源于
     * {@code aster-lang-ts/shared/error_codes.json}，由 generator 同时产出 TS 与 Java
     * 常量。但 W600 至今也**没有**进那个注册表（ADR 0031 M3 列为未完成项），
     * 两个引擎各自硬编码了本地常量。
     *
     * <p>本类沿用同一模式而非顺手开一条跨仓发版链：新增一个码要改 error_codes.json
     * → 重跑双引擎 generator → 发 aster-lang-ts + aster-lang-core → bump 本仓 pin，
     * 只为一条 warning 不划算，且会让本次改动的验证面失控。
     *
     * <p><b>偿还方式</b>：随 ADR 0031 M3（W600 单源化）一起把 W601 补进注册表。
     * 在那之前，W6xx 段由本仓与 aster-lang-core 约定保留给诊断类告警。
     */
    public static final String CONFLICT_CODE = "W601";

    /**
     * 扫描 AST，产出规则冲突诊断。
     *
     * <p>★<b>永不抛异常</b>：分析器是**辅助**能力，它自己出问题不该连累编译。
     * 任何 RuntimeException 都降级为「本次不给提示」并留日志——静默少报，
     * 好过让一条本可以编译的策略因为分析器的 bug 编不过。
     *
     * @param astModule 已解析的 AST；null 时返回空列表
     * @return 诊断列表（可能为空），severity 恒 warning、blocking 恒 false
     */
    public List<CompilationResult.Diagnostic> scan(Module astModule) {
        if (astModule == null) {
            return List.of();
        }
        try {
            List<RuleConflictAnalyzer.Finding> findings = RuleConflictAnalyzer.analyze(astModule);
            if (findings.isEmpty()) {
                return List.of();
            }
            List<CompilationResult.Diagnostic> out = new ArrayList<>(findings.size());
            for (RuleConflictAnalyzer.Finding f : findings) {
                out.add(toDiagnostic(f));
            }
            LOG.debugf("规则冲突扫描：%d 条提示", out.size());
            return out;
        } catch (RuntimeException e) {
            LOG.warnf("规则冲突扫描失败（降级为不提示，不影响编译）: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Finding → Diagnostic。
     *
     * <p>行号来自 AST 的 Span；分析器拿不到行号时返回 0，这里归一成 1
     * （诊断协议是 1-based，0 会让前端定位到不存在的位置）。列号一律 1：
     * 分析器目前只记录到行粒度。
     */
    private static CompilationResult.Diagnostic toDiagnostic(RuleConflictAnalyzer.Finding f) {
        int line = f.line() > 0 ? f.line() : 1;
        return new CompilationResult.Diagnostic(
            line, 1, line, 1,
            f.message(),
            CONFLICT_CODE,
            "warning",
            // featureId 复用 kind 的小写形态，供前端按类型分组/过滤
            f.kind().name().toLowerCase(java.util.Locale.ROOT),
            // nodeKind：这两类检测都锚在 If 条件上
            "If",
            // ★blocking 恒 false —— 见类注释：曾多次误报的检查不该有权拒绝用户的策略
            false
        );
    }
}
