package io.aster.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.api.GuardDecisionResponse;

/**
 * guard 包内的 JSON 读写：决策服务与审批服务共用同一个（Quarkus 托管的）ObjectMapper 与同一套失败文案。
 * 失败只会来自已落库数据损坏或序列化器缺陷，属于不变量被破坏，统一抛 {@link IllegalStateException}。
 */
final class GuardJson {
  private final ObjectMapper mapper;

  GuardJson(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 读取 {@code guard_decisions.response_json}：决策的当前响应。 */
  GuardDecisionResponse readResponse(GuardDecisionEntity e) {
    try {
      return mapper.readValue(e.responseJson, GuardDecisionResponse.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("guard_decisions.response_json 无法解析: " + e.id, ex);
    }
  }

  String writeJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Action Guard 序列化失败", ex);
    }
  }
}
