package io.aster.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 站内助手（RAG 问答）请求。
 *
 * <p>助手在浏览器端先做**站内检索**（文档索引 + 导航动作），把命中条目作为
 * {@code groundingHits} 一并送来；模型只依据这些条目作答并给出引用，
 * 不自由发挥——这是"答案可溯源、不产生幻觉"的前提。
 *
 * <p><b>为什么每个字段都有上限</b>：groundingHits 完全来自客户端，是无界输入。
 * 不设上限就能绕过 query 的长度限制制造超大 prompt（内存 + LLM 成本放大，
 * 同时扩大注入面）。与 {@link SuggestRequest} 的取舍一致。
 *
 * @param query          用户原始问句
 * @param groundingHits  站内检索命中（作为唯一事实依据）
 * @param locale         回答所用语言
 * @param model          LLM 模型覆盖（可选）
 */
// Phase 2 BYOK：忽略顶层 _byok envelope（见 GeneratePolicyRequest 注释）。
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantRequest(
    @NotBlank(message = "query 不能为空")
    @Size(max = 2_048, message = "query 长度超过上限（最多 2048 字符）")
    String query,

    // 检索侧默认只回 8 条；这里留 16 的余量但硬性封顶，避免客户端塞任意多条。
    @Valid
    @Size(max = 16, message = "groundingHits 数量超过上限（最多 16 条）")
    List<GroundingHit> groundingHits,

    String locale,

    String model
) {
    public String getLocaleOrDefault() {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }

    /** 空安全：便于 prompt 组装时直接遍历。 */
    public List<GroundingHit> hitsOrEmpty() {
        return groundingHits == null ? List.of() : groundingHits;
    }

    /**
     * 一条站内检索命中。
     *
     * @param title    条目标题
     * @param snippet  摘要（文档 description 或分组名）
     * @param href     站内链接，模型引用时原样给出
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroundingHit(
        @Size(max = 512, message = "title 长度超过上限（最多 512 字符）")
        String title,

        @Size(max = 2_048, message = "snippet 长度超过上限（最多 2048 字符）")
        String snippet,

        @Size(max = 1_024, message = "href 长度超过上限（最多 1024 字符）")
        String href
    ) {}
}
