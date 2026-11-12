package io.aster.policy.compiler;

import io.aster.policy.parser.ParseBudget;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * 启动时把 {@code aster.parse.time-budget} 交给解析器，并注册超时计数指标（aster-cloud/aster-lang-core#254 兜底）。
 *
 * <p>所有编译用户源码的入口（编译 / 校验 / 按源码求值 / guard / LLM 生成校验 / 模块解析 / 稳定性扫描 /
 * 参数 schema 提取 / 审批视图）都经同一个 {@code InProcessCnlParser}，因此在这里设一次进程级预算即可覆盖全部入口。
 * 指标 {@code aster.parse.budget.exceeded}（Prometheus：{@code aster_parse_budget_exceeded_total}）读取解析器的累计计数。
 */
@ApplicationScoped
public class ParseBudgetStartup {

    private static final Logger LOG = Logger.getLogger(ParseBudgetStartup.class);

    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "aster.parse.time-budget", defaultValue = "2s")
    Duration budget;

    /** 预算上限：超过它等于没有兜底（一个请求可占线程数十秒），配置错误应在启动时暴露。 */
    static final Duration MAX_BUDGET = Duration.ofSeconds(30);

    void onStart(@Observes StartupEvent ev) {
        ParseBudget.configure(validate(budget));
        FunctionCounter.builder("aster.parse.budget.exceeded", ParseBudget.class, c -> ParseBudget.exceededCount())
            .description("Parses aborted because they exceeded aster.parse.time-budget")
            .register(registry);
        LOG.infof("CNL 解析时间预算: %s", budget.isZero() ? "不限时" : budget);
    }

    /**
     * 启动期校验：负值与超过 {@link #MAX_BUDGET} 的值拒绝启动（fail-fast），0 是显式的「不限时」应急开关。
     * 须带单位书写（{@code 2s}、{@code 500ms}）；不带单位的纯数字按 Quarkus 约定解释为秒。
     */
    static Duration validate(Duration budget) {
        if (budget == null || budget.isNegative() || budget.compareTo(MAX_BUDGET) > 0) {
            throw new IllegalStateException("aster.parse.time-budget 必须在 0（不限时）到 " + MAX_BUDGET.toSeconds()
                + "s 之间，带单位书写（如 2s、500ms），实际: " + budget);
        }
        return budget;
    }
}
