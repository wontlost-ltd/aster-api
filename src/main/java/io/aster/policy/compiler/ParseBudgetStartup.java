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

    void onStart(@Observes StartupEvent ev) {
        ParseBudget.configure(budget);
        FunctionCounter.builder("aster.parse.budget.exceeded", ParseBudget.class, c -> ParseBudget.exceededCount())
            .description("Parses aborted because they exceeded aster.parse.time-budget")
            .register(registry);
        LOG.infof("CNL 解析时间预算: %s", budget.isZero() || budget.isNegative() ? "不限时" : budget);
    }
}
