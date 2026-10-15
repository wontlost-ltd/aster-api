package io.aster.llm.api.dto;

import java.util.List;

/**
 * {@code POST /api/v1/ai/models} 响应（ADR 0048 §3.3）。
 * models=null 表示 provider/网关不提供模型列表（404/405），不算错误；非空时已按形状过滤、去重、排序、≤200 条。
 */
public record ModelsResponse(String provider, List<String> models, String defaultModel, String fetchedAt) {
}
