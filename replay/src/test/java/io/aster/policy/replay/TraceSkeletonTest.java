package io.aster.policy.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.api.model.DecisionTrace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceSkeleton 测试。
 *
 * <p>最重要的一组断言是 <b>PII 边界</b>：骨架里绝不能出现业务值。
 * 这不是风格问题——骨架会对**所有租户**落库（不受 replayRetentionEnabled 门控），
 * 一旦携带 result 值，等于绕过该开关偷偷留存了明文输入。
 */
class TraceSkeletonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 造一棵含**敏感值**的 trace：信用分、姓名、金额都放进 result。 */
    private static DecisionTrace traceWithSensitiveValues() {
        var inner = new DecisionTrace.TraceStep(
            1, "信用分 >= 700", 680, false, List.of());
        var outer = new DecisionTrace.TraceStep(
            1, "客户是 VIP", "张三", true, List.of(inner));
        var amount = new DecisionTrace.TraceStep(
            2, "订单金额 > 500", 12345.67, true, List.of());
        return new DecisionTrace("pricing", "discount",
            List.of(outer, amount), "APPROVED", 42L);
    }

    @Test
    void 关键_骨架结构上不存在_result_字段() {
        // 用反射断言：不是"当前没填值"，而是**字段根本不存在**。
        // 这样即便将来有人想"顺手加回来"，也会先撞到这条测试。
        List<String> comps = Arrays.stream(TraceSkeleton.SkeletonStep.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
        assertEquals(java.util.Set.of("stepId", "expression", "matched", "depth"),
            new java.util.HashSet<>(comps));
        assertFalse(comps.contains("result"), "SkeletonStep 不得有 result 字段");
        assertFalse(comps.contains("value"), "SkeletonStep 不得有 value 字段");
    }

    @Test
    void 关键_序列化后不含任何业务值() throws Exception {
        String json = MAPPER.writeValueAsString(TraceSkeleton.from(traceWithSensitiveValues()));
        // 三个敏感值都不得出现在骨架里
        assertFalse(json.contains("680"), "信用分泄漏: " + json);
        assertFalse(json.contains("张三"), "姓名泄漏: " + json);
        assertFalse(json.contains("12345.67"), "金额泄漏: " + json);
        assertFalse(json.contains("APPROVED"), "业务输出泄漏: " + json);
    }

    @Test
    void 关键_条件原文被保留_这是漏斗视图的基础() throws Exception {
        String json = MAPPER.writeValueAsString(TraceSkeleton.from(traceWithSensitiveValues()));
        // expression 是策略源码片段（作者所写），不是用户数据，必须保留
        assertTrue(json.contains("客户是 VIP"), json);
        assertTrue(json.contains("信用分 >= 700"), json);
    }

    @Test
    void matched_布尔值被保留_漏斗靠它统计() {
        var sk = TraceSkeleton.from(traceWithSensitiveValues());
        assertEquals(List.of(true, false, true),
            sk.steps().stream().map(TraceSkeleton.SkeletonStep::matched).toList());
    }

    @Test
    void 嵌套被展平且_depth_可还原层级() {
        var sk = TraceSkeleton.from(traceWithSensitiveValues());
        assertEquals(3, sk.steps().size());
        assertEquals(List.of(0, 1, 0),
            sk.steps().stream().map(TraceSkeleton.SkeletonStep::depth).toList());
    }

    @Test
    void stepId_跨执行稳定_同一策略可聚合() {
        // 同一策略两次执行（值不同、判定不同）应产出相同的 stepId 序列，
        // 否则聚合时同一个条件会被算成多个，漏斗就散了。
        var run1 = TraceSkeleton.from(traceWithSensitiveValues());
        var alt = new DecisionTrace("pricing", "discount", List.of(
            new DecisionTrace.TraceStep(1, "客户是 VIP", "李四", false,
                List.of(new DecisionTrace.TraceStep(1, "信用分 >= 700", 720, true, List.of()))),
            new DecisionTrace.TraceStep(2, "订单金额 > 500", 10.0, false, List.of())
        ), "REJECTED", 7L);
        var run2 = TraceSkeleton.from(alt);

        assertEquals(
            run1.steps().stream().map(TraceSkeleton.SkeletonStep::stepId).toList(),
            run2.steps().stream().map(TraceSkeleton.SkeletonStep::stepId).toList());
    }

    @Test
    void trace_为_null_时返回_null_而非空骨架() {
        // 空骨架与"确实没有条件"无法区分，会污染聚合分母
        assertNull(TraceSkeleton.from(null));
    }

    @Test
    void 保留模块与函数名_供按策略聚合() {
        var sk = TraceSkeleton.from(traceWithSensitiveValues());
        assertEquals("pricing", sk.moduleName());
        assertEquals("discount", sk.functionName());
        assertEquals("trace-skeleton/v1", sk.schemaVersion());
    }

    @Test
    void 空_steps_与_null_children_不抛异常() {
        var empty = new DecisionTrace("m", "f", List.of(), null, 0L);
        assertTrue(TraceSkeleton.from(empty).steps().isEmpty());

        var nullChildren = new DecisionTrace("m", "f",
            List.of(new DecisionTrace.TraceStep(1, "x", null, true, null)), null, 0L);
        assertEquals(1, TraceSkeleton.from(nullChildren).steps().size());
    }
}
