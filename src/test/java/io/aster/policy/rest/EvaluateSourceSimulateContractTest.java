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
            .as("enforceApiQuota 必须在 if (!effectiveSimulate) 内")
            .contains("if (!effectiveSimulate) {\n            enforceApiQuota(");
    }

    @Test
    void simulate必须绑定HMAC验证() throws Exception {
        // ★第九轮 P0-1b：simulate 是**免计费**开关。若信任裸 query boolean，
        //   任何外部调用方加个 ?simulate=true 就能白嫖配额且不留调用记录。
        //   必须与 replayCapture 同门控（InternalCallerFilter.isHmacVerified）。
        assertThat(evaluateSourceBody(source()))
            .as("simulate 必须与 HMAC 验证做与运算，不能直接信任 query 参数")
            .contains("simulate && io.aster.security.apikey.InternalCallerFilter.isHmacVerified(");
    }

    @Test
    void 异常路径的记账同样要被gate() throws Exception {
        // ★第九轮 P0-1：成功路径 gate 了，异常路径（api_error）仍在记账，
        //   于是失败的模拟重跑照样计入 API 调用统计。
        String body = evaluateSourceBody(source());
        int from = 0;
        int count = 0;
        while (true) {
            int idx = body.indexOf("recordApiCall(\"/api/v1/policies/evaluate-source\", \"api_error\"", from);
            if (idx < 0) break;
            count++;
            // 该调用之前 200 字符内应出现 gate
            String near = body.substring(Math.max(0, idx - 200), idx);
            assertThat(near)
                .as("第 " + count + " 处 api_error 记账未被 effectiveSimulate gate")
                .contains("if (!effectiveSimulate)");
            from = idx + 1;
        }
        assertThat(count).as("应存在 api_error 记账点").isGreaterThan(0);
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
                .as(sideEffect + " 必须位于 if (!effectiveSimulate) 之后")
                .contains("if (!effectiveSimulate)");
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

    @Test
    void 所有业务指标调用都必须被simulate_gate() throws Exception {
        // ★第十轮：上一轮只 gate 了 recordApiCall，五处
        //   businessMetrics.endPolicyEvaluation 仍在异常路径无条件执行——
        //   于是「simulate 不污染指标」这句话不成立。
        //
        //   ★用**括号深度**判定而非固定字符窗口：成功路径的调用与它的 gate
        //   隔着一个多行 recordEvaluation(...)，任何固定窗口都会误判。
        assertEveryCallIsGated(evaluateSourceBody(source()),
            "businessMetrics.endPolicyEvaluation(");
    }

    /**
     * 断言 body 中每一处 needle 调用都位于某个 `if (!effectiveSimulate) {` 块内。
     *
     * <p>做法：从方法开头扫到 needle，维护「当前是否处于 gate 块内」的括号深度。
     * 比固定字符窗口可靠——调用与 gate 之间隔多少行都不影响判定。
     */
    private static void assertEveryCallIsGated(String body, String needle) {
        int from = 0;
        int count = 0;
        while (true) {
            int idx = body.indexOf(needle, from);
            if (idx < 0) break;
            count++;
            assertThat(isInsideSimulateGate(body, idx))
                .as("第 " + count + " 处 " + needle + " 未被 effectiveSimulate gate")
                .isTrue();
            from = idx + 1;
        }
        assertThat(count).as("应存在 " + needle + " 调用点").isGreaterThan(0);
    }

    /** 扫描 [0, pos) 的括号，判断 pos 是否落在某个 gate 块内。 */
    private static boolean isInsideSimulateGate(String body, int pos) {
        final String GATE = "if (!effectiveSimulate) {";
        java.util.Deque<Boolean> stack = new java.util.ArrayDeque<>();
        int i = 0;
        while (i < pos) {
            if (body.startsWith(GATE, i)) {
                stack.push(Boolean.TRUE);
                i += GATE.length();
                continue;
            }
            char c = body.charAt(i);
            if (c == '{') stack.push(Boolean.FALSE);
            else if (c == '}' && !stack.isEmpty()) stack.pop();
            i++;
        }
        return stack.contains(Boolean.TRUE);
    }
}
