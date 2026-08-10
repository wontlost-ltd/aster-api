package io.aster.policy.parser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DynamicCnlExecutor} 的执行资源上限（ADR 0034 §12.4）。
 *
 * <h2>被修复的缺口</h2>
 *
 * <p>本类的 polyglot Context 做足了沙箱收紧——禁 IO、禁进程、禁线程、
 * 禁类查找、禁跨语言——但<b>没有任何执行上限</b>。
 * 恶意或有 bug 的策略写个无限循环就能耗死 worker 线程。
 *
 * <p>而同仓的 {@code TrufflePolicyRuntime} 早就加了
 * {@code ResourceLimits.statementLimit(10M)}（P1-R22 审计项），
 * 并明确记录了选型理由：<b>用指令数而非 wall-clock 计时器，
 * 因为后者在 JVM GC pause 下不可靠</b>。
 * 两条执行路径的防护不对齐，而 What-If 重跑走的正是没防护的这条。
 *
 * <h2>为什么 What-If 尤其依赖它</h2>
 *
 * <p>单条重跑没有上界时，「段最坏耗时」不可计算，租约取值就失去依据——
 * 那正是前三轮租约取值被退回的根源（83h vs 2h、50min vs 30min、
 * 以及漏掉 fetchWindow 的 25 分钟）。
 */
class DynamicCnlExecutorResourceLimitTest {

    private static String source() throws Exception {
        return Files.readString(Path.of(
            "src/main/java/io/aster/policy/parser/DynamicCnlExecutor.java"));
    }

    /**
     * ★执行上限必须真的挂在 Context 上，而不只是声明一个 ResourceLimits 变量。
     *
     * <p>本仓有前科：{@code PermitLease} 曾整条是死代码、
     * {@code renewLease} 无调用点、租约校验曾写在无人加载的 {@code static} 块里。
     * 「构造了但没接线」是这里最容易犯的错。
     */
    @Test
    void 执行上下文必须挂上statement上限() throws Exception {
        String src = source();

        assertTrue(src.contains("ResourceLimits.newBuilder()")
                && src.contains("statementLimit("),
            "★必须构造 ResourceLimits——沙箱收紧挡不住无限循环");

        assertTrue(src.contains(".resourceLimits(limits)"),
            "★必须**挂到 Context 上**才生效；只声明变量是死代码");

        int build = src.indexOf("ResourceLimits.newBuilder()");
        int attach = src.indexOf(".resourceLimits(limits)");
        assertTrue(build > 0, "找不到 ResourceLimits 构造");
        assertTrue(attach > build, "★构造必须在挂载之前");
    }

    /**
     * 上限取值必须与生产执行路径一致。
     *
     * <p>两条路径取不同值会让「What-If 重跑的资源约束」与
     * 「线上求值的资源约束」出现无人解释的差异。
     */
    @Test
    void 上限取值须与生产执行路径拉齐() throws Exception {
        assertTrue(source().contains("statementLimit(10_000_000L"),
            "★与 TrufflePolicyRuntime 的 P1-R22 取同一数量级（10M statements）");
    }

    /**
     * ★<b>必须写明 statementLimit 在 Aster 上无效</b>，防止它被当成执行上界依赖。
     *
     * <p>实测（limit=1/2/10，同一策略连续执行 100,000 次）：全部成功，从不触发——
     * Aster AST 不产生 Truffle 可计数的 statement。
     *
     * <p>我曾在注释里写「真正的执行上界由 statementLimit 提供」并据此
     * 删掉了 wall-clock 超时，净效果是把上界从「有」变成「没有」。
     * 本用例锁死这条警示，避免下一个人重复这个判断。
     */
    @Test
    void 必须写明statementLimit在Aster上无效() throws Exception {
        String src = source();
        assertTrue(src.contains("实测无效"),
            "★必须写明 statementLimit 实测不触发，否则会被误当作执行上界");
        assertTrue(src.contains("不得") && src.contains("作为执行上界依赖"),
            "★必须明确禁止把它当执行上界——这是我犯过的错，要留住教训");
    }
}
