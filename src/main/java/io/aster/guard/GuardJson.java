package io.aster.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.api.GuardDecisionResponse;

import java.util.List;

/**
 * guard 包内的 JSON 读写：决策服务与审批服务共用同一个（Quarkus 托管的）ObjectMapper 与同一套失败文案。
 * 失败只会来自已落库数据损坏或序列化器缺陷，属于不变量被破坏，统一抛 {@link IllegalStateException}。
 */
final class GuardJson {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

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

  /** 读取 {@code guard_decisions.controls}（jsonb 字符串数组）；列为空或数组为空时返回 null，供视图省略该字段。 */
  List<String> readControls(GuardDecisionEntity e) {
    if (e.controls == null || e.controls.isBlank()) {
      return null;
    }
    try {
      List<String> controls = mapper.readValue(e.controls, STRING_LIST);
      return controls.isEmpty() ? null : List.copyOf(controls);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("guard_decisions.controls 无法解析: " + e.id, ex);
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
