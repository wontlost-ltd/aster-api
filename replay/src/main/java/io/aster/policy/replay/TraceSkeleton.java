package io.aster.policy.replay;

import io.aster.policy.api.model.DecisionTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * 决策骨架：{@link DecisionTrace} 的**脱敏**投影，只保留聚合分析所需的结构。
 *
 * <p><b>用途</b>：支撑"条件漏斗 / 死分支"这类业务视图——统计每个条件被判定为
 * 真/假的条数，回答"这条策略实际是怎么走的""哪个条件从未命中过"。
 *
 * <p><b>★为什么必须是独立类型而不是复用 DecisionTrace</b>：
 * {@link DecisionTrace.TraceStep#result()} 是 {@code Object}，会携带**业务值**
 * （如 {@code result=680} 是信用分、{@code result="张三"} 是姓名）。
 * 把整棵 trace 落库等于绕过 {@code replayRetentionEnabled}（默认关）
 * 偷偷留存明文输入，会直接击穿平台的 PII 边界。
 *
 * <p>本类型通过**结构上不存在 result 字段**来保证这件事做不到——
 * 而不是依赖调用方"记得脱敏"。这是刻意的设计：安全边界应由类型系统保证，
 * 不应由纪律保证。
 *
 * <p><b>expression 为什么可以留</b>：它是**策略源码片段**（如
 * {@code "客户是 VIP"}），由策略作者编写，属于业务规则而非用户数据。
 * 与 CNL 源码本身同级——源码已经明文存在 policy_versions 里。
 *
 * <p><b>不含 finalResult / executionTimeMs</b>：前者是业务输出（PII 面），
 * 后者对条件漏斗无意义且会让相同结构的骨架产生不同内容，妨碍聚合去重。
 */
public record TraceSkeleton(
    /** 骨架 schema 版本，消费侧据此判断字段语义。 */
    String schemaVersion,
    /** 模块名（策略元数据，非用户数据）。 */
    String moduleName,
    /** 函数名（同上）。 */
    String functionName,
    /** 扁平化的判定步骤。 */
    List<SkeletonStep> steps
) {
    /** 当前骨架 schema 版本。字段语义变更时必须 bump。 */
    public static final String SCHEMA_VERSION = "trace-skeleton/v1";

    /**
     * 单个判定步骤（**无 result 字段**，见类注释）。
     *
     * @param stepId     稳定标识：`<depth>.<sequence>`，同一策略跨执行可对齐聚合
     * @param expression 条件原文（策略源码片段）
     * @param matched    该条件是否判定为真
     * @param depth      嵌套深度（0 为顶层），供 UI 还原层级
     */
    public record SkeletonStep(
        String stepId,
        String expression,
        boolean matched,
        int depth
    ) {}

    /**
     * 从 {@link DecisionTrace} 投影出骨架，**丢弃所有 result 值**。
     *
     * <p>trace 为 null（未启用 trace 采集）时返回 null——调用方据此决定是否落库，
     * 不构造空骨架（空骨架与"确实没有条件"无法区分，会污染聚合分母）。
     */
    public static TraceSkeleton from(DecisionTrace trace) {
        if (trace == null) {
            return null;
        }
        List<SkeletonStep> flat = new ArrayList<>();
        flatten(trace.steps(), 0, flat);
        return new TraceSkeleton(SCHEMA_VERSION, trace.moduleName(), trace.functionName(), flat);
    }

    /**
     * 深度优先展平嵌套步骤。
     *
     * <p>扁平化而非保留树形：聚合查询要的是"条件 X 命中多少次"，
     * 扁平结构让消费侧按 stepId 直接 group by，不必递归遍历 jsonb。
     * depth 字段保留了还原层级所需的信息。
     */
    private static void flatten(List<DecisionTrace.TraceStep> steps, int depth, List<SkeletonStep> out) {
        if (steps == null) {
            return;
        }
        for (DecisionTrace.TraceStep s : steps) {
            if (s == null) {
                continue;
            }
            out.add(new SkeletonStep(
                depth + "." + s.sequence(),
                s.expression(),
                s.matched(),
                depth));
            flatten(s.children(), depth + 1, out);
        }
    }
}
