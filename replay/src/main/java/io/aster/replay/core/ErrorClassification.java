package io.aster.replay.core;

import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.InProcessCnlParser;

/**
 * 执行异常 → 错误类别的**单一事实源**（issue #173）。
 *
 * <p>此前 runner（{@code RunnerMain.mapError}）与 API（{@code PolicyEvaluationResource}
 * 的 catch 链）各自手写一份分类，靠注释里的行号引用保持同步——而它们**已经漂移**：
 * API 侧把 {@link DynamicCnlExecutor.AmbiguousEntryException} 单独映射为 400 +
 * {@code EvaluationResponse.ambiguous()}，runner 侧没有该分支、把它并进了 EXECUTION。
 * runner 的 javadoc 甚至写明了"含 AmbiguousEntryException"，即漂移是**已知且被默认**的。
 *
 * <p>本类只负责"这是哪一类错误"。两侧各自的**响应构造**（HTTP status / envelope 形状）
 * 仍留在各自代码里——那部分本就应当不同，不该强行统一。
 *
 * <p>★分类顺序有意义：{@link DynamicCnlExecutor.ModuleExecutionException} 与
 * {@link DynamicCnlExecutor.AmbiguousEntryException} 都是
 * {@link DynamicCnlExecutor.DynamicExecutionException} 的子类，必须先于父类判定，
 * 否则会被误分类为 EXECUTION。
 */
public final class ErrorClassification {

    /** 错误类别。 */
    public enum Kind {
        /** 入口函数有多个候选，调用方需显式指定。 */
        AMBIGUOUS,
        /** 跨模块 import 解析/链接失败。 */
        MODULE,
        /** CNL 解析失败。 */
        PARSE,
        /** 执行期失败。 */
        EXECUTION,
        /** 其余未预期异常。 */
        INTERNAL
    }

    private final Kind kind;
    private final String message;

    private ErrorClassification(Kind kind, String message) {
        this.kind = kind;
        this.message = message;
    }

    public Kind kind() {
        return kind;
    }

    /** 已解包的诊断消息（MODULE 类含 error code 前缀）。 */
    public String message() {
        return message;
    }

    /**
     * 分类一个执行异常。
     *
     * @param e 待分类异常
     * @return 分类结果，永不为 null
     */
    public static ErrorClassification classify(Exception e) {
        if (e instanceof DynamicCnlExecutor.AmbiguousEntryException) {
            return new ErrorClassification(Kind.AMBIGUOUS, String.valueOf(e.getMessage()));
        }
        if (e instanceof DynamicCnlExecutor.ModuleExecutionException moduleEx) {
            var moduleError = moduleEx.resolutionException();
            return new ErrorClassification(
                Kind.MODULE, moduleError.code() + ": " + moduleError.getMessage());
        }
        if (e instanceof DynamicCnlExecutor.DynamicExecutionException) {
            return new ErrorClassification(Kind.EXECUTION, String.valueOf(e.getMessage()));
        }
        if (e instanceof InProcessCnlParser.CnlParseException) {
            return new ErrorClassification(Kind.PARSE, String.valueOf(e.getMessage()));
        }
        return new ErrorClassification(Kind.INTERNAL, String.valueOf(e.getMessage()));
    }
}
