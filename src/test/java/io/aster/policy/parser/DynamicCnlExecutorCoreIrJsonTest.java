package io.aster.policy.parser;

import aster.core.ir.CoreIrJson;
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

    /** 把 CNL 编译成 Core IR v2 规范字节 —— 模拟客户端提交的 policy 载荷（名称解析后的 IR）。 */
    private static String coreIrJsonOf(String cnl) {
        return CoreIrJson.serialize(InProcessCnlParser.parse(cnl, null, null).core());
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

    // ★两个 Rule 的结果必须不同：此前两者都返回 driver.age，执行了错的函数也照样绿。
    //   目标刻意不放在第一个，且模块里不能有 main（Truffle 会按名优先选 main）。
    @Test
    void executesCoreIrJsonWithExplicitFunctionName() throws Exception {
        String coreIr = coreIrJsonOf("""
            Module json.multi.

            Define Driver has age as Int.

            Rule other given driver as Driver, produce Int:
              Return driver.age.

            Rule helper given driver as Driver, produce Int:
              Return driver.age plus 1.
            """);

        ExecutionResult result = DynamicCnlExecutor.executeCoreIrJson(
            coreIr, Map.of("driver", Map.of("age", 7)), "helper");

        assertThat(result.functionName()).isEqualTo("helper");
        assertThat(result.result()).isEqualTo(8);
    }

    @Test
    void explicitNonMainFunctionIsRejectedWhenModuleDefinesMain() throws Exception {
        String coreIr = coreIrJsonOf("""
            Module json.multi.

            Define Driver has age as Int.

            Rule helper given driver as Driver, produce Int:
              Return driver.age plus 1.

            Rule main given driver as Driver, produce Int:
              Return driver.age.
            """);

        assertThatThrownBy(() -> DynamicCnlExecutor.executeCoreIrJson(
            coreIr, Map.of("driver", Map.of("age", 7)), "helper"))
            .hasMessageContaining("'helper'")
            .hasMessageContaining("'main'");
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
        assertRejected("{ not valid json", "invalid_request");
    }

    // ---- ADR 0055 §5：/evaluate-json 严格读入 Core IR v2（版本预检 → schema → 严格绑定 → 不变式） ----

    /** v1 形状（没有 schemaVersion）：按版本错误拒收，不执行。 */
    @Test
    void v1IrWithoutSchemaVersionIsRejectedAsUnsupportedSchemaVersion() {
        assertRejected(V1_IR, "unsupported_schema_version");
        assertRejected("{\"name\":\"empty\"}", "unsupported_schema_version");
    }

    /** schemaVersion 必须是 JSON 整数 2：字符串 "2"、整数 1 都是版本错误。 */
    @Test
    void schemaVersionOtherThanInteger2IsRejected() throws Exception {
        assertRejected(withField("schemaVersion", "2"), "unsupported_schema_version");
        assertRejected(withField("schemaVersion", 1), "unsupported_schema_version");
    }

    @Test
    void unknownFieldIsRejectedAsInvalidRequest() throws Exception {
        assertRejected(withField("extra", true), "invalid_request");
    }

    @Test
    void missingFieldIsRejectedAsInvalidRequest() throws Exception {
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(coreIrJsonOf(SOURCE));
        tree.remove("profile");
        assertRejected(MAPPER.writeValueAsString(tree), "invalid_request");
    }

    /** 手写的 v1 IR：没有 schemaVersion、origin 与 v2 恒输出字段，1.0.45 之前的读方能执行它。 */
    static final String V1_IR = "{\"name\":\"json.v1\",\"decls\":[{\"kind\":\"Func\",\"name\":\"main\","
        + "\"params\":[],\"ret\":{\"kind\":\"TypeName\",\"name\":\"Int\"},\"body\":{\"kind\":\"Block\","
        + "\"statements\":[{\"kind\":\"Return\",\"expr\":{\"kind\":\"Int\",\"value\":1}}]}}]}";

    private static String withField(String name, Object value) throws Exception {
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(coreIrJsonOf(SOURCE));
        tree.set(name, MAPPER.valueToTree(value));
        return MAPPER.writeValueAsString(tree);
    }

    private static void assertRejected(String coreIr, String code) {
        assertThatThrownBy(() -> DynamicCnlExecutor.executeCoreIrJson(coreIr, Map.of("driver", Map.of("age", 1)), null))
            .isInstanceOfSatisfying(DynamicCnlExecutor.DynamicExecutionException.class, e -> {
                assertThat(e.kind()).isEqualTo(DynamicCnlExecutor.DynamicExecutionException.Kind.INVALID_INPUT);
                assertThat(e.code()).isEqualTo(code);
            })
            .hasMessageContaining("Core IR JSON 解析失败");
    }

    @Test
    void rejectsUnknownFunctionName() throws Exception {
        String coreIr = coreIrJsonOf(SOURCE);

        assertThatThrownBy(() -> DynamicCnlExecutor.executeCoreIrJson(
                coreIr, Map.of("driver", Map.of("age", 1)), "noSuchRule"))
            .isInstanceOf(RuntimeException.class);
    }

    // ---- Task 16b：不可信 IR 的 inputRefs 一律重算（ADR 0046 §11.6 第 19 条） ----

    private static final String PROFILED = """
        Module json.profiled.
        Profile "governed".

        @id("JP-1")
        Rule main given amount as Int, produce Verdict:
          If amount greater than 10:
            Return Verdict.deny("too much").
          Return Verdict.allow().
        """;

    /** 伪造 inputRefs=[]：执行结果带的是按函数体重算的引擎路径，连同第一个参数实际匹配的输入键。 */
    @Test
    void forgedInputRefsInSubmittedIrAreRecomputed() throws Exception {
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(coreIrJsonOf(PROFILED));
        for (var decl : tree.withArray("decls")) {
            if ("main".equals(decl.path("name").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) decl).putArray("inputRefs");
            }
        }

        ExecutionResult result = DynamicCnlExecutor.executeCoreIrJson(
            MAPPER.writeValueAsString(tree), Map.of("amount", 1), null);

        assertThat(result.profile()).isEqualTo("governed");
        assertThat(result.inputRefs()).containsExactly("");
        assertThat(result.inputKey()).isEqualTo("amount");
        assertThat(result.verifiedInputsRequired()).isFalse();
    }

    @Test
    void noProfileIrCarriesNoInputRefs() throws Exception {
        ExecutionResult result = DynamicCnlExecutor.executeCoreIrJson(
            coreIrJsonOf(SOURCE), Map.of("driver", Map.of("age", 42)), null);
        assertThat(result.inputRefs()).isNull();
        assertThat(result.inputKey()).isEqualTo("driver");
    }
}
