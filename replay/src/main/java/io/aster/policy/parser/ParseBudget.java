package io.aster.policy.parser;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次解析的时间预算（aster-cloud/aster-lang-core#254 兜底）。
 *
 * <p>core 的 ANTLR 文法在某些类型嵌套写法上预测耗时随层数指数增长（LL 模式下 {@code result of}
 * 成功 / 失败分量交替嵌套 32 层约 8.6s；本仓库使用的 SLL 模式下，混合 {@code result of} / {@code Map}
 * / 函数类型的嵌套同样能让 1–5KB 源码解析数秒到数十秒），均远低于嵌套上限 64。线程中断对 ANTLR
 * 无效（预测循环从不检查中断标志），因此预算由 {@link BudgetedParserATNSimulator} 在预测的 closure
 * 递归里协作式检查，超时抛 {@link Exceeded}，由 {@link InProcessCnlParser} 归为带码 E054。
 *
 * <p>进程级默认值由宿主在启动时经 {@link #configure(Duration)} 设定（aster-api 读
 * {@code aster.parse.time-budget}）；未配置时为 {@link #DEFAULT_BUDGET}。预算 ≤ 0 表示不限时。
 * 每个实例只属于一次解析调用（单线程使用），不跨线程共享。
 */
public final class ParseBudget {

    /** 默认预算 2 秒：正常策略解析在毫秒级（实测见 PR 说明），留三个数量级余量，又让病态输入最多占用一个线程 2 秒。 */
    public static final Duration DEFAULT_BUDGET = Duration.ofSeconds(2);

    private static volatile long configuredNanos = DEFAULT_BUDGET.toNanos();

    /** 进程启动以来因超出预算而中止的解析次数（宿主据此注册指标）。 */
    private static final AtomicLong EXCEEDED = new AtomicLong();

    private final long budgetNanos;
    private final long deadline;

    private ParseBudget(long budgetNanos) {
        this.budgetNanos = budgetNanos;
        this.deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : Long.MAX_VALUE;
    }

    /** 设定进程级默认预算；null 恢复默认值，≤ 0 表示不限时。 */
    public static void configure(Duration budget) {
        configuredNanos = budget == null ? DEFAULT_BUDGET.toNanos() : budget.toNanos();
    }

    /** 当前进程级默认预算。 */
    public static Duration configured() {
        return Duration.ofNanos(configuredNanos);
    }

    /** 超出预算而中止的解析累计次数。 */
    public static long exceededCount() {
        return EXCEEDED.get();
    }

    /** 按进程级默认预算从此刻开始计时。 */
    static ParseBudget startDefault() {
        return start(Duration.ofNanos(configuredNanos));
    }

    /** 按指定预算从此刻开始计时（测试与需要单独限时的调用方使用）；≤ 0 表示不限时。 */
    static ParseBudget start(Duration budget) {
        return new ParseBudget(budget.toNanos());
    }

    /** 预算毫秒数（诊断参数用）；不限时为 0。 */
    long budgetMillis() {
        return budgetNanos > 0 ? Duration.ofNanos(budgetNanos).toMillis() : 0;
    }

    /** 已超时则抛 {@link Exceeded}。调用方负责控制调用频率（nanoTime 本身约数十纳秒）。 */
    void check() {
        if (System.nanoTime() > deadline) {
            throw new Exceeded(this);
        }
    }

    /** 记一次超时中止（只在解析边界计一次，不在每个检查点计）。 */
    static void recordExceeded() {
        EXCEEDED.incrementAndGet();
    }

    /**
     * 预算耗尽的内部信号。继承 {@link RuntimeException} 而非 ANTLR 的 {@code RecognitionException}，
     * 生成的规则方法不会把它当语法错误吞掉做错误恢复，而是直接穿出 {@code parser.module()}。
     * 不填堆栈：它在深递归里抛出，只作控制流使用。
     */
    static final class Exceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient ParseBudget budget;

        Exceeded(ParseBudget budget) {
            super("解析超出时间预算 " + budget.budgetMillis() + "ms", null, false, false);
            this.budget = budget;
        }

        ParseBudget budget() {
            return budget;
        }
    }
}
