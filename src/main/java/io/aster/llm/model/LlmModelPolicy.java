package io.aster.llm.model;

import io.aster.llm.config.LlmConfig;

import java.util.Locale;

/**
 * 「本次实际发给 provider 的 model」的唯一算法（ADR 0048 §3.3）。
 *
 * <p>优先级：BYOK 指定的 model（原样，不做任何替换）> 请求里的 model > 平台 {@code aster.llm.model}。
 * Anthropic 兜底（非 claude 系名字换成 {@code anthropic-default-model}）只对「平台凭证」与「BYOK 未指定
 * model」两种情况生效——用户在自定义网关上指定的别名必须原样发出（用户决策）。
 *
 * <p>客户端拼 body 与 service 记账/回传 cloud 都必须经由本类，保证日志、provider 请求、用量三处一致。
 */
public final class LlmModelPolicy {

    private LlmModelPolicy() {
    }

    public static String wireModel(String requestModel, LlmRuntimeOptions options, LlmConfig config) {
        if (options != null && options.hasModel()) {
            return options.model();
        }
        String model = requestModel != null && !requestModel.isBlank() ? requestModel : config.model();
        boolean anthropic = options != null && "anthropic".equals(options.provider());
        if (anthropic && !model.toLowerCase(Locale.ROOT).startsWith("claude")) {
            return config.anthropicDefaultModel();
        }
        return model;
    }
}
