package io.aster.policy.parser;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import io.aster.replay.core.parser.ReplayMappers;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core IR JSON 直接执行（issue #172）。
 *
 * <p>{@code /evaluate-json} 端点收到的 policy 本就是 Core IR JSON，此前却要 fork
 * {@code node aster-convert} 转成 CNL 再解析回来；而生产运行镜像不含 Node，端点因此
 * 100% 不可用。这些用例验证进程内直接执行 Core IR 的路径，**不依赖任何外部进程**。
 */
class DynamicCnlExecutorCoreIrJsonTest {

    private static final ObjectMapper MAPPER = ReplayMappers.DEFAULT;

    /** 把 CNL 编译成 Core IR JSON —— 模拟客户端提交的 policy 载荷。 */
    private static String coreIrJsonOf(String cnl) throws Exception {
        var parseResult = InProcessCnlParser.parse(cnl, null, null);
        CoreModel.Module coreModule = new CoreLowering().lowerModule(parseResult.module());
        return MAPPER.writeValueAsString(coreModule);
    }

    private static final String SOURCE = """
        Module json.eval.

        Define Driver has age as Int.

        Rule main given driver as Driver, produce Int:
          Return driver.age.
        """;

    @Test
    void executesCoreIrJsonWithNamedContext() throws Exception {
        String coreIr = coreIrJsonOf(SOURCE);

        ExecutionResult result = DynamicCnlExecutor.executeCoreIrJson(
            coreIr, Map.of("driver", Map.of("age", 42)), null);

        assertThat(result.result()).isEqualTo(42);
        assertThat(result.moduleName()).isEqualTo("json.eval");
        assertThat(result.functionName()).isEqualTo("main");
    }

    @Test
    void executesCoreIrJsonWithExplicitFunctionName() throws Exception {
        String coreIr = coreIrJsonOf("""
            Module json.multi.

            Define Driver has age as Int.

            Rule helper given driver as Driver, produce Int:
              Return driver.age.

            Rule main given driver as Driver, produce Int:
              Return driver.age.
            """);

        ExecutionResult result = DynamicCnlExecutor.executeCoreIrJson(
            coreIr, Map.of("driver", Map.of("age", 7)), "helper");

        assertThat(result.functionName()).isEqualTo("helper");
        assertThat(result.result()).isEqualTo(7);
    }

    @Test
    void producesSameResultAsCnlPath() throws Exception {
        // 两条路径必须同源：同一策略、同一上下文，Core IR 直执行与 CNL 执行结果一致
        Object viaCnl = DynamicCnlExecutor
            .executeWithContext(SOURCE, Map.of("driver", Map.of("age", 30)), null, null)
            .result();
        Object viaJson = DynamicCnlExecutor
            .executeCoreIrJson(coreIrJsonOf(SOURCE), Map.of("driver", Map.of("age", 30)), null)
            .result();

        assertThat(viaJson).isEqualTo(viaCnl);
    }

    @Test
    void rejectsMalformedCoreIrJson() {
        assertThatThrownBy(() -> DynamicCnlExecutor.executeCoreIrJson(
                "{ not valid json", Map.of(), null))
            .isInstanceOf(DynamicCnlExecutor.DynamicExecutionException.class)
            .hasMessageContaining("Core IR JSON 解析失败");
    }

    @Test
    void rejectsCoreIrWithoutDeclarations() {
        assertThatThrownBy(() -> DynamicCnlExecutor.executeCoreIrJson(
                "{\"name\":\"empty\"}", Map.of(), null))
            .isInstanceOf(DynamicCnlExecutor.DynamicExecutionException.class)
            .hasMessageContaining("不含任何声明");
    }

    @Test
    void rejectsUnknownFunctionName() throws Exception {
        String coreIr = coreIrJsonOf(SOURCE);

        assertThatThrownBy(() -> DynamicCnlExecutor.executeCoreIrJson(
                coreIr, Map.of("driver", Map.of("age", 1)), "noSuchRule"))
            .isInstanceOf(RuntimeException.class);
    }
}
