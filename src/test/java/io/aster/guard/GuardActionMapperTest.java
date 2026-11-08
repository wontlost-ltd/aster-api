package io.aster.guard;

import io.aster.guard.api.GuardActionRequest;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import io.aster.policy.evidence.AgentIdentity;
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
        new ActionName("refund"), new Resource("customer", "C-1001"), ctx, new AgentIdentity("unknown", "claude-fable-5-1", null, "s-1"));

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

  private static Action withRoles(List<String> roles) {
    return new Action(new Principal("agent-42", null, roles), new ActionName("x"), null, null, null);
  }

  @Test
  void roles超过16项或单项超长或含控制字符报invalid_action() {
    List<String> seventeen = java.util.Collections.nCopies(17, "r");
    assertThatThrownBy(() -> mapper.flatten(withRoles(seventeen))).isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> mapper.flatten(withRoles(List.of("ok", "r".repeat(256)))))
        .isInstanceOf(GuardInvalidActionException.class);
    assertThatThrownBy(() -> mapper.flatten(withRoles(List.of("ops\nbot"))))
        .isInstanceOf(GuardInvalidActionException.class);
  }

  @Test
  void roles恰为16项且每项255字符可用() {
    List<String> roles = java.util.Collections.nCopies(16, "r".repeat(255));

    assertThat(mapper.flatten(withRoles(roles))).containsEntry("principal_roles", roles);
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

  /** ADR 0054 §3.2 不变式 1：context 的 null 叶子原样保留（null、缺键、空串是三个不同文档）。 */
  @Test
  void nullLeafInContextIsAccepted() {
    Map<String, Object> inner = new HashMap<>();
    inner.put("b", null);
    Map<String, Object> ctx = new HashMap<>();
    ctx.put("k", null);
    ctx.put("a", inner);
    ctx.put("l", Arrays.asList(1, null));
    Action a = new Action(new Principal("agent-42", null, null), new ActionName("x"), null, ctx, null);

    @SuppressWarnings("unchecked")
    Map<String, Object> frozen = (Map<String, Object>) mapper.flatten(a).get("context");

    assertThat(frozen).containsEntry("k", null).containsKey("k");
    @SuppressWarnings("unchecked")
    Map<String, Object> frozenInner = (Map<String, Object>) frozen.get("a");
    assertThat(frozenInner).containsKey("b").containsEntry("b", null);
    @SuppressWarnings("unchecked")
    List<Object> frozenList = (List<Object>) frozen.get("l");
    assertThat(frozenList).containsExactly(1, null);
    assertThatThrownBy(() -> frozenInner.put("c", 1)).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> frozenList.add(2)).isInstanceOf(UnsupportedOperationException.class);
    assertThat(mapper.contextHash(ctx)).isNotEqualTo(mapper.contextHash(Map.of("a", Map.of(), "l", List.of(1))));
  }

  /** contextHash 仍是 sha256-sorted-json/v1：既有输入的摘要逐字节不变（旧收据语义不变）。 */
  @Test
  void contextHashUnchangedForExistingInputs() {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("tags", List.of("a", "b"));
    ctx.put("amount", 250000);
    ctx.put("n", Map.of("y", 1.5, "x", true));
    String sorted = "{\"amount\":250000,\"n\":{\"x\":true,\"y\":1.5},\"tags\":[\"a\",\"b\"]}";

    assertThat(mapper.contextHash(ctx)).isEqualTo(DigestUtils.sha256Hex(sorted))
        .isEqualTo("3af251d856251db3143ce31d4d29eb488e847999176a0d830f78c834273c5d8b");
    assertThat(mapper.contextHash(Map.of("amount", 250000))).isEqualTo(DigestUtils.sha256Hex("{\"amount\":250000}"));
    Map<String, Object> withNull = new HashMap<>();
    withNull.put("cc", null);
    assertThat(mapper.contextHash(withNull)).isEqualTo(DigestUtils.sha256Hex("{\"cc\":null}"));
  }

  /** I-JSON：context 中整数值超过 2^53−1 或非有限数 → invalid_action；同值以字符串发送可通过。 */
  @Test
  void unsafeNumberInContextIsInvalidAction() {
    for (Object bad : List.<Object>of(9_007_199_254_740_992L, -9_007_199_254_740_992L, new java.math.BigInteger("123456789012345680000"),
        1e20, new java.math.BigDecimal("9007199254740992"), Double.NaN, Double.POSITIVE_INFINITY)) {
      Action a = new Action(new Principal("agent-42", null, null), new ActionName("x"), null,
          Map.of("n", Map.of("amount", List.of(bad))), null);
      assertThatThrownBy(() -> mapper.flatten(a)).as(String.valueOf(bad)).isInstanceOf(GuardInvalidActionException.class);
    }
    Action safe = new Action(new Principal("agent-42", null, null), new ActionName("x"), null,
        Map.of("max", 9_007_199_254_740_991L, "big", "9007199254740992", "f", 0.5), null);
    assertThat(mapper.flatten(safe)).containsKey("context");
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
