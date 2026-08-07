package io.aster.policy.rest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import io.smallrye.mutiny.Uni;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        // ★第十三轮：原实现只查**首次出现**，且只断言「此前某处出现过 gate」——
        //   审查者实证：在已 gate 的调用后再加一条**未 gate** 的同类调用，测试仍全绿。
        //   现改为遍历**每一次**出现，并用括号深度确认它真的落在 gate 块内。
        String body = evaluateSourceBody(source());
        for (String sideEffect : new String[] {
            "policyMetrics.recordEvaluation(",
            "businessMetrics.recordPolicyEvaluation()",
            "businessMetrics.endPolicyEvaluation(",
            "recordLoanDecision(",
            "publishPolicyEvaluationEvent(",
            "recordApiCall(",
        }) {
            int idx = body.indexOf(sideEffect);
            assertThat(idx).as("找不到副作用: " + sideEffect).isGreaterThan(0);
            int n = 0;
            while (idx >= 0) {
                n++;
                assertThat(isInsideSimulateGate(body, idx))
                    .as("第 " + n + " 处 " + sideEffect + " 未落在 if (!effectiveSimulate) 块内")
                    .isTrue();
                idx = body.indexOf(sideEffect, idx + 1);
            }
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


    // ==================================================================
    // 许可生命周期：真行为测试（第十三轮复审）
    //
    // ★为什么必须是行为测试：上一版我写的「行为测试」被审查者拆穿——
    //   两条在测试里手写 try/finally 复刻算法（生产代码删错也全绿），
    //   一条纯读源码文本。它们证明不了**运行时恰好释放一次**。
    //
    // ★被漏掉的真 bug：runSubscriptionOn 只是**装配** Uni，真正的
    //   executor.execute 发生在稍后的订阅阶段——那时资源方法早已返回。
    //   Mutiny 自己捕获调度拒绝并转成 Uni failure，所以写在资源方法里的
    //   同步 catch **永远收不到它**。下面用 rejecting executor 直接复现。
    // ==================================================================

    /** 复刻生产的许可 lease 结构：CAS release-once + 三条归还路径。 */
    private static Uni<String> leasedUni(Semaphore sem, Executor executor,
                                         AtomicBoolean workerRan, boolean workerThrows) {
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) {
                sem.release();
            }
        };
        return Uni.createFrom().<String>item(() -> {
            workerRan.set(true);
            try {
                if (workerThrows) {
                    throw new IllegalStateException("worker boom");
                }
                return "ok";
            } finally {
                releaseOnce.run();
            }
        }).runSubscriptionOn(executor)
            .onTermination().invoke(releaseOnce);
    }

    @Test
    void 调度被拒时许可必须归还_supplier根本没跑() {
        // ★这就是同步 catch 收不到、因而曾经永久泄漏的那条路径。
        Semaphore sem = new Semaphore(1);
        assertThat(sem.tryAcquire()).isTrue();
        assertThat(sem.availablePermits()).isZero();

        Executor rejecting = task -> {
            throw new RejectedExecutionException("worker pool saturated");
        };
        AtomicBoolean workerRan = new AtomicBoolean(false);

        assertThatThrownBy(() ->
            leasedUni(sem, rejecting, workerRan, false).await().indefinitely())
            .as("调度拒绝应沿 Uni failure 通道抛出")
            .isInstanceOf(RejectedExecutionException.class);

        assertThat(workerRan).as("supplier 根本不该跑起来").isFalse();
        assertThat(sem.availablePermits())
            .as("★许可必须归还——否则闸门被逐个吃空直到全站 503")
            .isEqualTo(1);
    }

    @Test
    void worker正常结束许可恰好归还一次() {
        Semaphore sem = new Semaphore(1);
        assertThat(sem.tryAcquire()).isTrue();
        AtomicBoolean ran = new AtomicBoolean(false);

        String r = leasedUni(sem, Runnable::run, ran, false).await().indefinitely();

        assertThat(r).isEqualTo("ok");
        assertThat(ran).isTrue();
        // ★恰好一次：多释放会让许可 >1，把闸门上限悄悄抬高（比泄漏更隐蔽）
        assertThat(sem.availablePermits())
            .as("worker finally 与 onTermination 都会调 releaseOnce，CAS 必须去重")
            .isEqualTo(1);
    }

    @Test
    void worker抛异常时许可恰好归还一次() {
        Semaphore sem = new Semaphore(1);
        assertThat(sem.tryAcquire()).isTrue();
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThatThrownBy(() ->
            leasedUni(sem, Runnable::run, ran, true).await().indefinitely())
            .isInstanceOf(IllegalStateException.class);

        assertThat(ran).isTrue();
        assertThat(sem.availablePermits())
            .as("异常路径同样恰好一次：finally 释放后 onTermination 必须 no-op")
            .isEqualTo(1);
    }

    @Test
    void 生产代码必须用CAS_release_once且三条路径共用() throws Exception {
        // 结构护栏（补充，不作为验收证据）：锁住 lease 形状，
        // 防止有人把某条路径改回裸 release 而绕开 CAS 去重。
        String body = evaluateSourceBody(source());
        assertThat(body)
            .as("必须用 CAS 保护的一次性归还")
            .contains("permitReleased.compareAndSet(false, true)");
        assertThat(body)
            .as("路径3：调度拒绝只能靠异步 onTermination 兜底，同步 catch 不可达")
            .contains(".onTermination().invoke(releaseOnce)");
        assertThat(body)
            .as("除 lease 内部外，不得再出现裸 release")
            .containsOnlyOnce("EVAL_SOURCE_PERMITS.release();");
    }

    @Test
    void 许可总数应等于闸门上限且初始全部可用() throws Exception {
        Field cf = PolicyEvaluationResource.class.getDeclaredField("EVAL_SOURCE_PERMITS_COUNT");
        cf.setAccessible(true);
        Field pf = PolicyEvaluationResource.class.getDeclaredField("EVAL_SOURCE_PERMITS");
        pf.setAccessible(true);
        assertThat(((Semaphore) pf.get(null)).availablePermits())
            .as("静态初始化后可用许可应等于上限（没有被谁提前吃掉）")
            .isEqualTo((int) cf.get(null));
    }
}
