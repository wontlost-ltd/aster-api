package io.aster.policy.api;

import io.aster.policy.parser.DynamicCnlExecutor.DynamicExecutionException;
import io.aster.policy.parser.EntryFunctionException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估失败 → HTTP 状态 / 错误码的映射（生产 E2E F11：此前一律 200 + "Policy evaluation failed"，
 * 没有可执行版本这类可处理的原因被吞掉）。
 */
class EvaluationFailureTest {

    @Test
    void 无可执行版本_409_且被包装多层也能识别() {
        Throwable wrapped = new CompletionException(new RuntimeException("Policy evaluation failed",
            PolicyNotExecutableException.noExecutableVersion("m", "f")));
        EvaluationFailure f = EvaluationFailure.classify(wrapped);
        assertThat(f.status()).isEqualTo(409);
        assertThat(f.code()).isEqualTo("no_executable_version");
        assertThat(f.message()).contains("m.f").contains("no executable version");
    }

    @Test
    void 策略不存在_404() {
        EvaluationFailure f = EvaluationFailure.classify(PolicyNotExecutableException.policyNotFound("m", "f"));
        assertThat(f.status()).isEqualTo(404);
        assertThat(f.code()).isEqualTo("policy_not_found");
    }

    @Test
    void 函数不存在_404_被入口抢占_400() {
        EvaluationFailure missing = EvaluationFailure.classify(
            EntryFunctionException.cached("未找到函数 'nosuch'，模块中可用的函数: [add2]"));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("function_not_found");
        assertThat(missing.message()).contains("'nosuch'");

        EvaluationFailure shadowed = EvaluationFailure.classify(
            EntryFunctionException.cached("无法执行函数 'helper'：模块中定义了函数 'main'，执行引擎会优先选它作为入口。"));
        assertThat(shadowed.status()).isEqualTo(400);
        assertThat(shadowed.code()).isEqualTo("function_not_executable");
    }

    @Test
    void Core_IR_输入不合法_400_目标函数缺失_404() {
        assertThat(EvaluationFailure.classify(new DynamicExecutionException("Core IR JSON 解析失败: x",
            DynamicExecutionException.Kind.INVALID_INPUT)))
            .isEqualTo(new EvaluationFailure(400, "invalid_request", "Core IR JSON 解析失败: x"));
        assertThat(EvaluationFailure.classify(new DynamicExecutionException("未找到指定函数 'g'",
            DynamicExecutionException.Kind.FUNCTION_NOT_FOUND)).status()).isEqualTo(404);
    }

    @Test
    void 其它失败_500_固定文案_不透出内部消息() {
        EvaluationFailure f = EvaluationFailure.classify(new DynamicExecutionException(
            "Core IR 执行失败: at org.graalvm.Internal.secret(Foo.java:1)", new IllegalStateException("db password=x")));
        assertThat(f).isEqualTo(new EvaluationFailure(500, "evaluation_failed", "Policy evaluation failed"));
        assertThat(EvaluationFailure.classify(new NullPointerException()).status()).isEqualTo(500);
        assertThat(EvaluationFailure.classify(null).status()).isEqualTo(500);
        assertThat(f.clientError()).isFalse();
    }
}
