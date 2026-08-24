package io.aster.policy.runtime;

import io.aster.policy.compiler.CompilationMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TrufflePolicyRuntime 单元测试
 *
 * 注意：这些测试需要 Aster 语言运行时正确注册到 GraalVM。
 * 在没有完整语言运行时的环境中，测试会被跳过。
 */
@org.junit.jupiter.api.condition.DisabledIfSystemProperty(
    named = "aster.truffle.tests.skip",
    matches = "true",
    disabledReason = "Aster 语言运行时未完全配置"
)
class TrufflePolicyRuntimeTest {

    private TrufflePolicyRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new TrufflePolicyRuntime();
        runtime.init();
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.cleanup();
        }
    }

    @Test
    void shouldInitializeContextPool() {
        // Context 池应该初始化成功
        assertThat(runtime).isNotNull();
    }

    @Test
    void shouldExecuteSimplePolicy() {
        // 简单的 Core JSON 示例（返回常量）
        String coreJson = """
            {
              "module": "test",
              "functions": [{
                "name": "evaluate",
                "params": [],
                "body": { "kind": "IntLiteral", "value": 42 }
              }]
            }
            """;

        CompilationMetadata metadata = new CompilationMetadata(
            "evaluate",
            "[]",
            "Int"
        );

        ExecutionResult result = runtime.execute(coreJson, new Object[0], metadata);

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isNull();
    }

    @Test
    void shouldHandleExecutionError() {
        // 无效的 Core JSON
        String invalidJson = "{ invalid json }";

        CompilationMetadata metadata = CompilationMetadata.empty();

        ExecutionResult result = runtime.execute(invalidJson, new Object[0], metadata);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void shouldHandleConcurrentExecution() throws Exception {
        // 准备测试数据
        String coreJson = """
            {
              "module": "test",
              "functions": [{
                "name": "evaluate",
                "params": [],
                "body": { "kind": "IntLiteral", "value": 42 }
              }]
            }
            """;

        CompilationMetadata metadata = new CompilationMetadata("evaluate", "[]", "Int");

        // 创建线程池，执行 10 个并发请求
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<ExecutionResult>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Future<ExecutionResult> future = executor.submit(() ->
                runtime.execute(coreJson, new Object[0], metadata)
            );
            futures.add(future);
        }

        // 等待所有任务完成
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(finished).isTrue();

        // 验证所有请求都成功
        for (Future<ExecutionResult> future : futures) {
            ExecutionResult result = future.get();
            assertThat(result.success()).isTrue();
        }
    }

    /**
     * 回归：返回 {@code Err <value>} 的策略，其结果 {@code {_type:"Err", value:...}}
     * 跨 Polyglot 边界回宿主时，convertValue 会遍历成员逐个 readMember。
     *
     * <p>当 Err 的内部表达式缺失/为 null（如下方 Core IR 用 {@code "value"} 键而
     * Loader 的 Err 模型字段是 {@code expr}，反序列化后内部表达式为 null），结果成员
     * {@code value} 是 Java null。修复前 AsterMapValue.readMember 透传裸 null 触发
     * Truffle 后置断言 "Post-condition contract violation for receiver
     * {_type=Err, value=null} … and return value null"，整个执行崩溃（生产 PR #77/#78
     * CI 阻塞根因）。修复后内部 null 包成 guest-null，执行不再崩溃。
     */
    @Test
    void shouldHandleErrWithNullInnerValue() {
        // 复刻生产 seed（V99.0.0 第 45 条）的 Core IR：Err 用 "value" 键 → Loader 的
        // Err.expr 反序列化为 null → 结果 {_type:"Err", value:null}。
        String coreJson = """
            {
              "name": "aster.test.failure",
              "decls": [{
                "kind": "Func",
                "name": "failingPolicy",
                "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}],
                "ret": {"kind": "TypeName", "name": "Int"},
                "body": {"statements": [{
                  "kind": "Return",
                  "expr": {"kind": "Err", "value": {"kind": "String", "value": "intentional"}}
                }]}
              }]
            }
            """;

        CompilationMetadata metadata = new CompilationMetadata("failingPolicy", "[\"Int\"]", "Int");

        // 关键断言：不论成功与否，都不得抛出 interop 契约违例的 AssertionError。
        ExecutionResult result = runtime.execute(coreJson, new Object[]{0}, metadata);

        // 修复后：执行完成（返回 Err 结果 map，其 value 成员被还原为 null）。
        assertThat(result).isNotNull();
        assertThat(result.result()).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) result.result();
        // 标签已统一为 __type（aster-lang-ts#137）：TS 侧一直是 __type，
        // 等价性语料的 cases 也全部以 __type 为准，故 Truffle 侧对齐过来。
        assertThat(map).containsEntry("__type", "Err");
        assertThat(map).containsKey("value");
        assertThat(map.get("value")).isNull();
    }
}
