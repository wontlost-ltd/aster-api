package io.aster.policy.rest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `/evaluate-source?simulate=true` 的副作用隔离契约（第八轮 P0-1）。
 *
 * <p><b>为什么用源码断言而不是 REST 调用</b>：这条契约的本质是
 * 「哪些语句被 `if (!simulate)` 包住」——一个**结构性**约束。用 REST 测只能
 * 观察到「响应正常」，观察不到「配额没被扣、指标没被写」（那需要 mock 掉
 * 整条计量链，反而把测试变成对 mock 的断言）。
 *
 * <p>本测试锁住的是：将来有人往这个方法里加新的副作用时，如果忘了 gate，
 * 断言会失败并提醒他 —— 这正是 P0-1 当初被漏掉的原因。
 */
class EvaluateSourceSimulateContractTest {

    private static String source() throws Exception {
        return Files.readString(
            Path.of("src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java"));
    }

    /** 截取 evaluateSource 方法体（到下一个 @POST 之前）。 */
    private static String evaluateSourceBody(String src) {
        int start = src.indexOf("public Uni<EvaluationResponse> evaluateSource(");
        assertThat(start).as("找不到 evaluateSource 方法").isGreaterThan(0);
        int next = src.indexOf("@POST", start);
        return next > 0 ? src.substring(start, next) : src.substring(start);
    }

    @Test
    void simulate参数存在且默认false() throws Exception {
        assertThat(source())
            .as("simulate 必须是可选参数，默认 false —— 不能改变既有调用方行为")
            .contains("@QueryParam(\"simulate\") @DefaultValue(\"false\") boolean simulate");
    }

    @Test
    void 配额必须被simulate_gate() throws Exception {
        // 查一次 What-if 会重跑上百条；按真实执行计费 = 看报表就被扣钱
        assertThat(evaluateSourceBody(source()))
            .as("enforceApiQuota 必须在 if (!simulate) 内")
            .contains("if (!simulate) {\n            enforceApiQuota(");
    }

    @Test
    void 业务指标与审计事件必须被simulate_gate() throws Exception {
        String body = evaluateSourceBody(source());
        // 模拟结果混进 KPI 会污染真实经营数据；审计链记的是真实决策
        for (String sideEffect : new String[] {
            "policyMetrics.recordEvaluation(",
            "businessMetrics.recordPolicyEvaluation()",
            "recordLoanDecision(",
            "publishPolicyEvaluationEvent(",
            "recordApiCall(",
        }) {
            int idx = body.indexOf(sideEffect);
            assertThat(idx).as("找不到副作用: " + sideEffect).isGreaterThan(0);
            // 该副作用之前最近的 gate 应是 if (!simulate)
            String before = body.substring(0, idx);
            assertThat(before)
                .as(sideEffect + " 必须位于 if (!simulate) 之后")
                .contains("if (!simulate)");
        }
    }

    @Test
    void 并发闸门不得被simulate跳过() throws Exception {
        // 模拟同样消耗 CPU，仍需背压保护——只跳记账，不跳资源保护
        String body = evaluateSourceBody(source());
        int gate = body.indexOf("acquired");
        int firstSimulateGate = body.indexOf("if (!simulate)");
        assertThat(gate).as("找不到并发闸门").isGreaterThan(0);
        // 闸门代码不应包在 simulate 分支里：它在第一个 gate 之后但属于主流程
        assertThat(body.substring(0, gate))
            .as("并发闸门不得被 simulate 跳过")
            .doesNotContain("if (simulate)");
    }
}
