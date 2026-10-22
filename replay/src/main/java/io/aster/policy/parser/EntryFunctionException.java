package io.aster.policy.parser;

import java.util.List;

/**
 * 无法让执行引擎执行所请求的函数。
 *
 * <p>两种情形：请求的函数不在 Core IR 中；或模块里有会被 Truffle 按名优先选为入口的函数
 * （DEFAULT_FUNCTION / "main"）且它不是目标。两者若不拦截，引擎都会**静默执行另一个函数**。
 *
 * <p>消息只含函数名，不含 IR 或内部状态，可以原样回给调用方（REST 层据此类型透出真实原因）。
 */
public final class EntryFunctionException extends IllegalArgumentException {

    /** 「函数不存在」消息的固定前缀；缓存只保留消息文本，重建后靠它区分两种情形。 */
    private static final String MISSING_PREFIX = "未找到函数 '";

    private EntryFunctionException(String message) {
        super(message);
    }

    /** 由缓存的确定性失败重建（每次抛新实例，避免跨线程共享同一个异常对象）。 */
    public static EntryFunctionException cached(String message) {
        return new EntryFunctionException(message);
    }

    static EntryFunctionException missing(String requested, List<String> available) {
        return new EntryFunctionException(
            MISSING_PREFIX + requested + "'，模块中可用的函数: " + available);
    }

    /** 请求的函数不在模块中（而非被 main 等入口抢占）。 */
    public boolean isMissingFunction() {
        return getMessage() != null && getMessage().startsWith(MISSING_PREFIX);
    }

    static EntryFunctionException shadowed(String requested, String preferred) {
        return new EntryFunctionException(
            "无法执行函数 '" + requested + "'：模块中定义了函数 '" + preferred
                + "'，执行引擎会优先选它作为入口。请重命名 '" + preferred + "'，或直接执行 '" + preferred + "'。");
    }
}
