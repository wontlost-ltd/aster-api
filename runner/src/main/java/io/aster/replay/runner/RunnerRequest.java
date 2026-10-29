package io.aster.replay.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * runner 请求（spec schema ②）。字段与 launcher 透传的执行元组对应；
 * vocabulary 为 cloud 下发的原始词汇 JSON（可缺省/null=无词汇），与 API 侧同一 Map 形态。
 * aliasSet 是 raw（未建 index），index/aliasSet 在 :replay 内建以保 byte-parity 单源。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunnerRequest(
        String tenantId,
        String source,
        Object input,
        String locale,
        String functionName,
        Map<String, List<String>> aliasSet,
        Map<String, Object> vocabulary
) {}
