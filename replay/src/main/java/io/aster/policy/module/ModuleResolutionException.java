package io.aster.policy.module;

import java.util.List;
import java.util.Map;

/**
 * Structured module resolution failure surfaced to evaluate-source diagnostics.
 *
 * <p>{@link Code} 是对外的稳定契约（HTTP 400 的 {@code code}）；同一个 code 有多种原因，故另带
 * {@link Reason}（稳定 key）与命名参数，供按请求语言本地化（aster-api#452）。{@link #getMessage()}
 * 保持英文原文不变：日志、runner 的 {@code ErrorClassification} 与回放记录依赖它。
 */
public class ModuleResolutionException extends RuntimeException {

    public enum Code {
        IMPORT_VERSION_REQUIRED,
        MODULE_VERSION_NOT_FOUND,
        MODULE_NOT_VISIBLE,
        MODULE_CYCLE
    }

    /**
     * 具体原因：每个原因只属于一个 {@link Code}。文案 key 为 {@code module.<原因名>}
     * （aster-lang-locales {@code evaluation-diagnostics/}）。
     *
     * <p>不可见类原因（{@code MODULE_NOT_VISIBLE} 下的全部）不带任何参数：文案与参数都不得暴露模块名或版本
     * （ADR 0015 跨租户 visibility-safe）。
     */
    public enum Reason {
        /** 导入未写版本。 */
        VERSION_REQUIRED(Code.IMPORT_VERSION_REQUIRED),
        /** 导入路径为空。 */
        EMPTY_PATH(Code.MODULE_VERSION_NOT_FOUND),
        /** 本租户没有该模块版本（候选版本见 candidates）。 */
        VERSION_NOT_FOUND(Code.MODULE_VERSION_NOT_FOUND),
        /** 没有租户上下文。 */
        TENANT_REQUIRED(Code.MODULE_NOT_VISIBLE),
        /** 模块存在但对调用方不可见。 */
        NOT_VISIBLE(Code.MODULE_NOT_VISIBLE),
        /** 本租户没有该模块的任何版本；对外与 NOT_VISIBLE 不可区分（ADR 0015 不泄露存在性），仅日志区分。 */
        NOT_FOUND(Code.MODULE_NOT_VISIBLE),
        /** 被导入的版本带用户别名，跨模块解析暂不支持（fail-closed）。 */
        ALIASES_UNSUPPORTED(Code.MODULE_NOT_VISIBLE),
        /** 被导入的模块无法解析。 */
        PARSE_FAILED(Code.MODULE_NOT_VISIBLE),
        /** 模块解析器不可用。 */
        RESOLVER_UNAVAILABLE(Code.MODULE_NOT_VISIBLE),
        /** 导入成环；参数 {@code cycle}（如 {@code a@1 -> b@2 -> a@1}）。 */
        IMPORT_CYCLE(Code.MODULE_CYCLE),
        /** 引擎链接失败；参数 {@code detail} 为引擎原文（不翻译）。 */
        LINK_FAILED(Code.MODULE_CYCLE);

        private final Code code;

        Reason(Code code) {
            this.code = code;
        }

        public Code code() {
            return code;
        }

        /** 旧构造器（只给 code）的默认原因：每个 code 下最常见的那个。 */
        static Reason defaultFor(Code code) {
            return switch (code) {
                case IMPORT_VERSION_REQUIRED -> VERSION_REQUIRED;
                case MODULE_VERSION_NOT_FOUND -> VERSION_NOT_FOUND;
                case MODULE_NOT_VISIBLE -> NOT_VISIBLE;
                case MODULE_CYCLE -> IMPORT_CYCLE;
            };
        }
    }

    private final Code code;
    private final Reason reason;
    private final Map<String, String> params;
    private final List<String> candidates;

    public ModuleResolutionException(Code code, String message) {
        this(code, message, List.of(), null);
    }

    public ModuleResolutionException(Code code, String message, List<String> candidates) {
        this(code, message, candidates, null);
    }

    public ModuleResolutionException(Code code, String message, Throwable cause) {
        this(code, message, List.of(), cause);
    }

    public ModuleResolutionException(Code code, String message, List<String> candidates, Throwable cause) {
        this(Reason.defaultFor(code), message, Map.of(), candidates, cause);
    }

    public ModuleResolutionException(Reason reason, String message) {
        this(reason, message, Map.of(), List.of(), null);
    }

    public ModuleResolutionException(Reason reason, String message, Throwable cause) {
        this(reason, message, Map.of(), List.of(), cause);
    }

    /**
     * @param reason     具体原因（决定 {@link #code()}）
     * @param message    英文原文（{@link #getMessage()}，不随语言变化）
     * @param params     文案命名参数；不可见类原因必须为空
     * @param candidates 候选版本（仅 {@link Reason#VERSION_NOT_FOUND}）
     */
    public ModuleResolutionException(Reason reason, String message, Map<String, String> params,
                                     List<String> candidates, Throwable cause) {
        super(message, cause);
        if (reason == null) {
            throw new IllegalArgumentException("reason is required");
        }
        Map<String, String> safeParams = params == null ? Map.of() : Map.copyOf(params);
        if (reason.code() == Code.MODULE_NOT_VISIBLE && !safeParams.isEmpty()) {
            // ADR 0015：不可见类诊断不得携带任何可能含模块名 / 版本的参数
            throw new IllegalArgumentException("MODULE_NOT_VISIBLE reasons must not carry params");
        }
        this.code = reason.code();
        this.reason = reason;
        this.params = safeParams;
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public Code code() {
        return code;
    }

    public Reason reason() {
        return reason;
    }

    public Map<String, String> params() {
        return params;
    }

    public List<String> candidates() {
        return candidates;
    }
}
