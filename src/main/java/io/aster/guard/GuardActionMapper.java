package io.aster.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aster.guard.api.GuardActionRequest;
import io.aster.guard.receipt.JcsCanonicalizer;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.aster.guard.GuardText.isBlank;

/**
 * 把请求中的 Action 校验并扁平化为规则的唯一位置参数（ADR 0040 §2.2），并计算 context 摘要。
 *
 * <p>约束：
 * <ul>
 *   <li>键序固定：principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model；</li>
 *   <li>文本字段 ≤ 255 字符且不含控制字符——与 guard_decisions 列宽一致，并保证收据 metadata 有确定上界；</li>
 *   <li>principal.roles ≤ 16 项，每项同文本字段规则（roles 进入收据 metadata）；</li>
 *   <li>context 深度 ≤ 8、序列化 ≤ 65536 字节；null 叶子原样保留（ADR 0054 §3.2：null、缺键与空串是三个不同文档，
 *       规则读到 null 与读到缺键的行为由规则作者负责）；</li>
 *   <li>context 中的数字按 I-JSON 校验（非有限数、整数值超出 ±(2^53−1) 拒绝），与 {@code jcs-sha256/v1} 同一规则；</li>
 *   <li>context 与 roles 深拷贝为不可变集合：规则运行时开启了 Map/List 访问，可变集合会被 interop 写入（Task 1 裁定）。</li>
 * </ul>
 */
@ApplicationScoped
public class GuardActionMapper {
  static final int MAX_CONTEXT_DEPTH = 8;
  static final int MAX_CONTEXT_BYTES = 65_536;
  static final int MAX_TEXT_LENGTH = 255;
  static final int MAX_ROLES = 16;
  /** sha256-sorted-json/v1：Map 键排序后序列化，摘要与 context 的键写入顺序无关。 */
  private static final ObjectMapper SORTED = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  /**
   * flatten 产出的键与它们对应的 guard 请求路径：规则参数键表与 inputRefs 的 guard 形状映射
   * （{@code GuardInputRefPaths}，ADR 0046 §11.6 第 3 条）共用这一张常量表，声明顺序即 flatten 的键序。
   */
  public enum FlatField {
    PRINCIPAL_ID("principal_id", "principal.id", Shape.SCALAR),
    PRINCIPAL_TYPE("principal_type", "principal.type", Shape.SCALAR),
    PRINCIPAL_ROLES("principal_roles", "principal.roles", Shape.LIST),
    ACTION("action", "action.name", Shape.SCALAR),
    RESOURCE_TYPE("resource_type", "resource.type", Shape.SCALAR),
    RESOURCE_ID("resource_id", "resource.id", Shape.SCALAR),
    CONTEXT("context", "context", Shape.OBJECT),
    AGENT_MODEL("agent_model", "agent.model", Shape.SCALAR);

    /**
     * 值的形状决定子路径如何映射：标量没有子路径；列表是一个整叶子（ADR 0040 §11 D1），子路径并入列表本身；
     * 对象的子路径原样接在请求路径之后。
     */
    public enum Shape { SCALAR, LIST, OBJECT }

    private final String key;
    private final String requestPath;
    private final Shape shape;

    FlatField(String key, String requestPath, Shape shape) {
      this.key = key;
      this.requestPath = requestPath;
      this.shape = shape;
    }

    /** 规则参数记录中的键。 */
    public String key() {
      return key;
    }

    /** 对应的 guard 请求路径（与 {@code VerifiedInputsGate.presentLeafPaths} 同一路径空间）。 */
    public String requestPath() {
      return requestPath;
    }

    public Shape shape() {
      return shape;
    }
  }

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
    m.put(FlatField.PRINCIPAL_ID.key(), text("principal.id", a.principal().id(), ""));
    m.put(FlatField.PRINCIPAL_TYPE.key(), text("principal.type", a.principal().type(), "agent"));
    m.put(FlatField.PRINCIPAL_ROLES.key(), roles(a.principal().roles()));
    m.put(FlatField.ACTION.key(), text("action.name", a.action().name(), ""));
    m.put(FlatField.RESOURCE_TYPE.key(), text("resource.type", a.resource() == null ? null : a.resource().type(), ""));
    m.put(FlatField.RESOURCE_ID.key(), text("resource.id", a.resource() == null ? null : a.resource().id(), ""));
    m.put(FlatField.CONTEXT.key(), context);
    m.put(FlatField.AGENT_MODEL.key(), text("agent.model", a.agent() == null ? null : a.agent().model(), ""));
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

  /** 至多 {@link #MAX_ROLES} 项，每项按文本字段校验；返回不可变副本。 */
  private static List<String> roles(List<String> roles) {
    if (roles == null) {
      return List.of();
    }
    if (roles.size() > MAX_ROLES) {
      throw new GuardInvalidActionException("principal.roles 超过 " + MAX_ROLES + " 项");
    }
    if (roles.stream().anyMatch(Objects::isNull)) {
      throw new GuardInvalidActionException("principal.roles 不允许 null 元素");
    }
    roles.forEach(role -> text("principal.roles", role, ""));
    return List.copyOf(roles);
  }

  /** 深拷贝为不可变 Map（保留键序与 null 值），同时校验深度（context 自身为第 1 层）。 */
  private static Map<String, Object> freezeMap(Map<?, ?> source, int depth) {
    checkDepth(depth);
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : source.entrySet()) {
      if (!(e.getKey() instanceof String key)) {
        throw new GuardInvalidActionException("context 的键必须是字符串");
      }
      copy.put(key, freeze(e.getValue(), depth));
    }
    return Collections.unmodifiableMap(copy);
  }

  private static List<Object> freezeList(List<?> source, int depth) {
    checkDepth(depth);
    List<Object> copy = new ArrayList<>(source.size());
    for (Object item : source) {
      copy.add(freeze(item, depth));
    }
    return Collections.unmodifiableList(copy);
  }

  private static Object freeze(Object value, int parentDepth) {
    if (value instanceof Number n) {
      requireIJsonNumber(n);
      return value;
    }
    if (value instanceof Map<?, ?> m) {
      return freezeMap(m, parentDepth + 1);
    }
    if (value instanceof List<?> l) {
      return freezeList(l, parentDepth + 1);
    }
    return value;
  }

  private static void requireIJsonNumber(Number n) {
    try {
      JcsCanonicalizer.requireIJsonNumber(n);
    } catch (IllegalArgumentException e) {
      throw new GuardInvalidActionException("context " + e.getMessage());
    }
  }

  private static void checkDepth(int depth) {
    if (depth > MAX_CONTEXT_DEPTH) {
      throw new GuardInvalidActionException("context 嵌套深度超过 " + MAX_CONTEXT_DEPTH);
    }
  }
}
