package io.aster.guard;

import io.aster.guard.api.GuardActionRequest;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Agent;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR 0040 §2.2：Action 扁平化键序、缺省值、校验与 contextHash。 */
class GuardActionMapperTest {
  private final GuardActionMapper mapper = new GuardActionMapper();

  private static Action minimal() {
    return new Action(new Principal("agent-42", null, null), new ActionName("delete_customer_record"), null, null, null);
  }

  private static Map<String, Object> nested(int levels) {
    Map<String, Object> m = Map.of("leaf", "v");
    for (int i = 1; i < levels; i++) {
      m = Map.of("k", m);
    }
    return m;
  }

  @Test
  void 扁平化键序与缺省值() {
    Map<String, Object> m = mapper.flatten(minimal());

    assertThat(m).isInstanceOf(LinkedHashMap.class);
    assertThat(new ArrayList<>(m.keySet())).containsExactly(
        "principal_id", "principal_type", "principal_roles", "action",
        "resource_type", "resource_id", "context", "agent_model");
    assertThat(m.get("principal_id")).isEqualTo("agent-42");
    assertThat(m.get("principal_type")).isEqualTo("agent");
    assertThat(m.get("principal_roles")).isEqualTo(List.of());
    assertThat(m.get("action")).isEqualTo("delete_customer_record");
    assertThat(m.get("resource_type")).isEqualTo("");
    assertThat(m.get("resource_id")).isEqualTo("");
    assertThat(m.get("context")).isEqualTo(Map.of());
    assertThat(m.get("agent_model")).isEqualTo("");
  }

  @Test
  void 完整Action原样映射且context与roles不可变() {
    Map<String, Object> ctx = new HashMap<>();
    ctx.put("amount", 250000);
    ctx.put("tags", new ArrayList<>(List.of("a", "b")));
    Action a = new Action(new Principal("agent-42", "service", new ArrayList<>(List.of("ops-bot"))),
        new ActionName("refund"), new Resource("customer", "C-1001"), ctx, new Agent("claude-fable-5-1", "s-1"));

    Map<String, Object> m = mapper.flatten(a);

    assertThat(m).containsEntry("principal_type", "service").containsEntry("resource_type", "customer")
        .containsEntry("resource_id", "C-1001").containsEntry("agent_model", "claude-fable-5-1")
        .containsEntry("principal_roles", List.of("ops-bot"));
    @SuppressWarnings("unchecked")
    Map<String, Object> frozen = (Map<String, Object>) m.get("context");
    assertThat(frozen).isEqualTo(Map.of("amount", 250000, "tags", List.of("a", "b")));
    assertThatThrownBy(() -> frozen.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    @SuppressWarnings("unchecked")
    List<Object> tags = (List<Object>) frozen.get("tags");
    assertThatThrownBy(() -> tags.add("c")).isInstanceOf(UnsupportedOperationException.class);
    @SuppressWarnings("unchecked")
    List<Object> roles = (List<Object>) m.get("principal_roles");
    assertThatThrownBy(() -> roles.add("x")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void 缺principal_id报invalid_action() {
    Action a = new Action(new Principal(" ", null, null), new ActionName("x"), null, null, null);
    assertThatThrownBy(() -> mapper.flatten(a)).isInstanceOf(GuardInvalidActionException.class)
        .extracting(e -> ((GuardInvalidActionException) e).reasonCode()).isEqualTo(GuardReasonCode.INVALID_ACTION);
    assertThatThrownBy(() -> mapper.flatten(new Action(null, new ActionName("x"), null, null, null)))
        .isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> mapper.flatten(null)).isInstanceOf(GuardInvalidActionException.class);
  }

  @Test
  void 缺action_name报invalid_action() {
    Action a = new Action(new Principal("agent-42", null, null), new ActionName(""), null, null, null);
    assertThatThrownBy(() -> mapper.flatten(a)).isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> mapper.flatten(new Action(new Principal("agent-42", null, null), null, null, null, null)))
        .isInstanceOf(GuardInvalidActionException.class);
  }

  @Test
  void 文本字段超长或含控制字符报invalid_action() {
    Action longId = new Action(new Principal("p".repeat(256), null, null), new ActionName("x"), null, null, null);
    assertThatThrownBy(() -> mapper.flatten(longId)).isInstanceOf(GuardInvalidActionException.class);
    Action ctrl = new Action(new Principal("agent-42", null, null), new ActionName("x"), new Resource("t", "a\u0000b"), null, null);
    assertThatThrownBy(() -> mapper.flatten(ctrl)).isInstanceOf(GuardInvalidActionException.class);
    Action nullRole = new Action(new Principal("agent-42", null, Arrays.asList("a", null)), new ActionName("x"), null, null, null);
    assertThatThrownBy(() -> mapper.flatten(nullRole)).isInstanceOf(GuardInvalidActionException.class);
  }

  @Test
  void context超深度报invalid_action() {
    Action ok = new Action(new Principal("agent-42", null, null), new ActionName("x"), null, nested(8), null);
    assertThat(mapper.flatten(ok)).containsKey("context");
    Action deep = new Action(new Principal("agent-42", null, null), new ActionName("x"), null, nested(9), null);
    assertThatThrownBy(() -> mapper.flatten(deep)).isInstanceOf(GuardInvalidActionException.class);
  }

  @Test
  void context超大小报invalid_action() {
    Action big = new Action(new Principal("agent-42", null, null), new ActionName("x"), null,
        Map.of("blob", "x".repeat(70_000)), null);
    assertThatThrownBy(() -> mapper.flatten(big)).isInstanceOf(GuardInvalidActionException.class);
  }

  @Test
  void context含null值报invalid_action() {
    Map<String, Object> ctx = new HashMap<>();
    ctx.put("k", null);
    Action a = new Action(new Principal("agent-42", null, null), new ActionName("x"), null, ctx, null);
    assertThatThrownBy(() -> mapper.flatten(a)).isInstanceOf(GuardInvalidActionException.class);
  }

  @Test
  void contextHash与键序无关() {
    LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
    ordered.put("a", 2);
    ordered.put("b", 1);
    assertThat(mapper.contextHash(Map.of("b", 1, "a", 2))).isEqualTo(mapper.contextHash(ordered));
    assertThat(mapper.contextHash(Map.of())).isEqualTo(DigestUtils.sha256Hex("{}"));
    assertThat(mapper.contextHash(null)).isEqualTo(DigestUtils.sha256Hex("{}"));
    assertThat(mapper.contextHash(Map.of("a", 1))).isNotEqualTo(mapper.contextHash(Map.of("a", 2)));
  }

  @Test
  void contextHash对嵌套Map同样排序() {
    LinkedHashMap<String, Object> inner = new LinkedHashMap<>();
    inner.put("y", 1);
    inner.put("x", 2);
    assertThat(mapper.contextHash(Map.of("n", inner))).isEqualTo(mapper.contextHash(Map.of("n", Map.of("x", 2, "y", 1))));
    assertThat(mapper.contextHash(Map.of("n", inner))).isEqualTo(DigestUtils.sha256Hex("{\"n\":{\"x\":2,\"y\":1}}"));
  }

  @Test
  void 请求record可构造() {
    GuardActionRequest req = new GuardActionRequest("guard.customer", "decide", minimal(), 0);
    assertThat(req.action().principal().id()).isEqualTo("agent-42");
  }
}
