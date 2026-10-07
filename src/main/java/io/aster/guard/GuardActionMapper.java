package io.aster.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aster.guard.api.GuardActionRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把请求中的 Action 校验并扁平化为规则的唯一位置参数（ADR 0040 §2.2），并计算 context 摘要。
 *
 * <p>约束：
 * <ul>
 *   <li>键序固定：principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model；</li>
 *   <li>文本字段 ≤ 255 字符且不含控制字符——与 guard_decisions 列宽一致，并保证收据 metadata 有确定上界；</li>
 *   <li>context 深度 ≤ 8、序列化 ≤ 65536 字节、不允许 null 值；</li>
 *   <li>context 与 roles 深拷贝为不可变集合：规则运行时开启了 Map/List 访问，可变集合会被 interop 写入（Task 1 裁定）。</li>
 * </ul>
 */
@ApplicationScoped
public class GuardActionMapper {
  static final int MAX_CONTEXT_DEPTH = 8;
  static final int MAX_CONTEXT_BYTES = 65_536;
  static final int MAX_TEXT_LENGTH = 255;
  /** sha256-sorted-json/v1：Map 键排序后序列化，摘要与 context 的键写入顺序无关。 */
  private static final ObjectMapper SORTED = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  public Map<String, Object> flatten(GuardActionRequest.Action a) {
    if (a == null || a.principal() == null || isBlank(a.principal().id())) {
      throw new GuardInvalidActionException("principal.id 必填");
    }
    if (a.action() == null || isBlank(a.action().name())) {
      throw new GuardInvalidActionException("action.name 必填");
    }
    Map<String, Object> context = a.context() == null ? Map.of() : freezeMap(a.context(), 1);
    if (serialize(context).length > MAX_CONTEXT_BYTES) {
      throw new GuardInvalidActionException("context 超过 " + MAX_CONTEXT_BYTES + " 字节");
    }
    LinkedHashMap<String, Object> m = new LinkedHashMap<>();
    m.put("principal_id", text("principal.id", a.principal().id(), ""));
    m.put("principal_type", text("principal.type", a.principal().type(), "agent"));
    m.put("principal_roles", roles(a.principal().roles()));
    m.put("action", text("action.name", a.action().name(), ""));
    m.put("resource_type", text("resource.type", a.resource() == null ? null : a.resource().type(), ""));
    m.put("resource_id", text("resource.id", a.resource() == null ? null : a.resource().id(), ""));
    m.put("context", context);
    m.put("agent_model", text("agent.model", a.agent() == null ? null : a.agent().model(), ""));
    return m;
  }

  /** sha256-sorted-json/v1：键排序后的 Jackson 序列化再 SHA-256 hex；null 视同空 Map。 */
  public String contextHash(Map<String, Object> context) {
    return DigestUtils.sha256Hex(serialize(context == null ? Map.of() : context));
  }

  private static byte[] serialize(Map<String, Object> context) {
    try {
      return SORTED.writeValueAsBytes(context);
    } catch (JsonProcessingException e) {
      throw new GuardInvalidActionException("context 无法序列化");
    }
  }

  /** 校验文本字段；空值取缺省值。 */
  private static String text(String field, String value, String defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value.length() > MAX_TEXT_LENGTH) {
      throw new GuardInvalidActionException(field + " 超过 " + MAX_TEXT_LENGTH + " 字符");
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new GuardInvalidActionException(field + " 含控制字符");
    }
    return value;
  }

  private static List<String> roles(List<String> roles) {
    if (roles == null) {
      return List.of();
    }
    if (roles.stream().anyMatch(Objects::isNull)) {
      throw new GuardInvalidActionException("principal.roles 不允许 null 元素");
    }
    return List.copyOf(roles);
  }

  /** 深拷贝为不可变 Map，同时校验深度（context 自身为第 1 层）与 null 值。 */
  private static Map<String, Object> freezeMap(Map<?, ?> source, int depth) {
    checkDepth(depth);
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : source.entrySet()) {
      if (!(e.getKey() instanceof String key)) {
        throw new GuardInvalidActionException("context 的键必须是字符串");
      }
      copy.put(key, freeze(e.getValue(), depth));
    }
    return Map.copyOf(copy);
  }

  private static List<Object> freezeList(List<?> source, int depth) {
    checkDepth(depth);
    List<Object> copy = new ArrayList<>(source.size());
    for (Object item : source) {
      copy.add(freeze(item, depth));
    }
    return List.copyOf(copy);
  }

  private static Object freeze(Object value, int parentDepth) {
    if (value == null) {
      throw new GuardInvalidActionException("context 不允许 null 值");
    }
    if (value instanceof Map<?, ?> m) {
      return freezeMap(m, parentDepth + 1);
    }
    if (value instanceof List<?> l) {
      return freezeList(l, parentDepth + 1);
    }
    return value;
  }

  private static void checkDepth(int depth) {
    if (depth > MAX_CONTEXT_DEPTH) {
      throw new GuardInvalidActionException("context 嵌套深度超过 " + MAX_CONTEXT_DEPTH);
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
