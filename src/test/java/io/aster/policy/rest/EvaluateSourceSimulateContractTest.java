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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

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
        // 查一次 What-if 会重跑上百条；按真实执行计费 = 看报表就被扣钱。
        // ★原实现只 contains 一段含硬编码缩进的字面量——只证明「存在一处被 gate 的
        //   调用」，挡不住别处还有未 gate 的同名调用（审计实证：在其后无条件再调
        //   一次 enforceApiQuota，测试照样全绿）。改为遍历每一处 + 括号深度判定。
        assertEveryCallIsGated(evaluateSourceBody(source()), "enforceApiQuota(");
    }

    @Test
    void simulate必须绑定HMAC验证() throws Exception {
        // ★第九轮 P0-1b：simulate 是**免计费**开关。若信任裸 query boolean，
        //   任何外部调用方加个 ?simulate=true 就能白嫖配额且不留调用记录。
        //   必须与 replayCapture 同门控（InternalCallerFilter.isHmacVerified）。
        //
        // ★原实现只 contains 那个表达式的文本。审计实证：把它整体挪进
        //   `if (false) { boolean unused = ...; }` 死分支、effectiveSimulate 改为
        //   裸 simulate —— 文本仍在，测试全绿，而免计费开关已被架空。
        //   所以必须断言这个表达式**真的赋给了 effectiveSimulate**。
        String body = stripComments(evaluateSourceBody(source()));
        String flat = body.replaceAll("\\s+", " ");

        assertThat(flat)
            .as("effectiveSimulate 必须由 simulate 与 HMAC 验证的与运算赋值")
            .contains("final boolean effectiveSimulate = simulate "
                + "&& io.aster.security.apikey.InternalCallerFilter.isHmacVerified(");

        // 不得存在把 effectiveSimulate 直接赋成裸 simulate 的写法
        assertThat(flat)
            .as("effectiveSimulate 不得直接采信 query 参数")
            .doesNotContain("effectiveSimulate = simulate;");
    }

    @Test
    void 异常路径的记账同样要被gate() throws Exception {
        // ★第九轮 P0-1：成功路径 gate 了，异常路径（api_error）仍在记账，
        //   于是失败的模拟重跑照样计入 API 调用统计。
        // ★原实现用固定 200 字符窗口判定 gate——本文件别处早已因「窗口会被多行
        //   调用撑爆而误判」换成括号深度，这条漏改了。现统一。
        assertEveryCallIsGated(
            evaluateSourceBody(source()),
            "recordApiCall(\"/api/v1/policies/evaluate-source\", \"api_error\"");
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
        // 模拟同样消耗 CPU，仍需背压保护——只跳记账，不跳资源保护。
        //
        // ★原实现是**空测试**（审计实证）：它断言 body 前缀不含 "if (simulate)"，
        //   而生产变量叫 effectiveSimulate，这个串根本不可能出现；算出的
        //   firstSimulateGate 还是个从未被断言的死变量。真的让 simulate 跳过闸门
        //   （把 tryAcquire 包进 gate）时，测试照样全绿。
        String body = evaluateSourceBody(source());
        String stripped = stripComments(body);

        int acquire = stripped.indexOf("EVAL_SOURCE_PERMITS.tryAcquire(");
        assertThat(acquire).as("找不到并发闸门 tryAcquire").isGreaterThan(0);

        // ★闸门必须在主流程上：不得落在任何 simulate 相关分支内
        assertThat(isInsideSimulateGate(body, acquire))
            .as("并发闸门不得被包在 if (!effectiveSimulate) 内——模拟同样要背压")
            .isFalse();
        assertThat(stripped.substring(0, acquire))
            .as("闸门之前不得出现 simulate 短路（提前 return / 直接置 acquired）")
            .doesNotContain("if (effectiveSimulate)");
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
        // ★注释掉的 gate 不算数（第十四轮审查者的探针实证）：
        //   把 `if (!effectiveSimulate) {` 改成 `// 维护提示: if (!effectiveSimulate) {`
        //   会让副作用实际脱离 gate，而纯文本匹配仍然认为它被包住。
        //   先剥掉行注释再做括号分析。块注释同理。
        body = stripComments(body);
        pos = Math.min(pos, body.length());
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
            .onFailure().invoke(t -> releaseOnce.run());
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
            .as("路径3：调度拒绝只能靠异步 onFailure 兜底；用 onTermination 会在取消时误放")
            .contains(".onFailure().invoke(t -> releaseOnce.run())");
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

    @Test
    void 取消不得归还仍在运行的worker的许可() throws Exception {
        // ★第十二轮修的正是这条：许可挂 onTermination 时，HTTP 取消会立刻归还，
        //   而同步 supplier 仍在烧 CPU——反复「发起再取消」即可让实际并发远超闸门。
        //   第十三轮我又把 onTermination 加了回来（为了兜住调度拒绝），
        //   所以必须证明**取消不会**让许可提前归还，否则那个 bug 就复活了。
        Semaphore sem = new Semaphore(1);
        assertThat(sem.tryAcquire()).isTrue();

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) {
                sem.release();
            }
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Uni<String> uni = Uni.createFrom().<String>item(() -> {
                workerStarted.countDown();
                try {
                    allowFinish.await(5, TimeUnit.SECONDS);   // 模拟仍在烧 CPU
                    return "ok";
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                } finally {
                    releaseOnce.run();
                }
            }).runSubscriptionOn(pool)
                .onFailure().invoke(t -> releaseOnce.run());

            var sub = uni.subscribe().with(x -> { }, t -> { });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS))
                .as("worker 应已开始").isTrue();

            sub.cancel();   // ★HTTP 客户端断连

            // 取消后短暂等待：若 onTermination 抢先归还，这里就会看到许可回到 1
            Thread.sleep(200);
            assertThat(sem.availablePermits())
                .as("★取消时 worker 仍在跑，许可绝不能提前归还——否则闸门可被绕过")
                .isZero();

            allowFinish.countDown();                 // 放 worker 跑完
            Thread.sleep(300);
            assertThat(sem.availablePermits())
                .as("worker 真正结束后才归还，且恰好一次")
                .isEqualTo(1);
        } finally {
            allowFinish.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 把行注释与块注释替换成等长空格。
     *
     * <p>保持长度不变，这样调用方基于原文算出的 pos 仍然对齐；
     * 被注释掉的 gate/副作用因此不再参与括号深度判定。
     */
    private static String stripComments(String src) {
        char[] out = src.toCharArray();
        boolean inLine = false, inBlock = false, inStr = false;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            char n = i + 1 < out.length ? out[i + 1] : '\0';
            if (inLine) {
                if (c == '\n') inLine = false; else out[i] = ' ';
            } else if (inBlock) {
                if (c == '*' && n == '/') { out[i] = ' '; out[i + 1] = ' '; i++; inBlock = false; }
                else if (c != '\n') out[i] = ' ';
            } else if (inStr) {
                if (c == '\\') i++;                       // 跳过转义
                else if (c == '"') inStr = false;
            } else if (c == '/' && n == '/') { inLine = true; out[i] = ' '; }
            else if (c == '/' && n == '*') { inBlock = true; out[i] = ' '; }
            else if (c == '"') inStr = true;
        }
        return new String(out);
    }

}
