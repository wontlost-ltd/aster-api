package io.aster.guard.provenance;

import io.aster.guard.GuardActionMapper;
import io.aster.guard.GuardActionMapper.FlatField;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import io.aster.policy.evidence.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import static org.assertj.core.api.Assertions.assertThat;

/** guard 形状的 inputRefs 映射（ADR 0046 §11.6 第 3 条）：引擎源码字段路径 → guard 请求路径（纯 JUnit）。 */
class GuardInputRefPathsTest {

  /** 全部字段都填满的请求：flatten 的键集与 presentLeafPaths 都由它产出。 */
  private static Action fullAction() {
    return new Action(new Principal("agent-42", "agent", List.of("ops-bot")), new ActionName("refund"),
        new Resource("customer", "C-1001"), Map.of("amount", 250000, "customer", Map.of("tier", "gold")),
        new AgentIdentity("openai", "gpt-5", null, null));
  }

  @Test
  void guardShapeFullTable() {
    assertThat(GuardInputRefPaths.guardShape(List.of("principal_id"))).containsExactly("principal.id");
    assertThat(GuardInputRefPaths.guardShape(List.of("principal_type"))).containsExactly("principal.type");
    assertThat(GuardInputRefPaths.guardShape(List.of("principal_roles"))).containsExactly("principal.roles");
    assertThat(GuardInputRefPaths.guardShape(List.of("principal_roles.0"))).containsExactly("principal.roles");
    assertThat(GuardInputRefPaths.guardShape(List.of("action"))).containsExactly("action.name");
    assertThat(GuardInputRefPaths.guardShape(List.of("resource_type"))).containsExactly("resource.type");
    assertThat(GuardInputRefPaths.guardShape(List.of("resource_id"))).containsExactly("resource.id");
    assertThat(GuardInputRefPaths.guardShape(List.of("agent_model"))).containsExactly("agent.model");
    assertThat(GuardInputRefPaths.guardShape(List.of("context.amount"))).containsExactly("context.amount");
    assertThat(GuardInputRefPaths.guardShape(List.of("context.customer.tier"))).containsExactly("context.customer.tier");
  }

  @Test
  void bareContextMapsToContext() {
    assertThat(GuardInputRefPaths.guardShape(List.of("context"))).containsExactly("context");
  }

  @Test
  void scalarFieldWithSubPathIsStar() {
    for (String ref : List.of("action.x", "principal_id.x", "principal_type.x", "resource_type.x", "resource_id.x",
        "agent_model.x")) {
      assertThat(GuardInputRefPaths.guardShape(List.of(ref))).as(ref).containsExactly("*");
    }
  }

  @Test
  void rootStarAndUnknownHeadAreStar() {
    assertThat(GuardInputRefPaths.guardShape(List.of(""))).containsExactly("*");
    assertThat(GuardInputRefPaths.guardShape(List.of("*"))).containsExactly("*");
    assertThat(GuardInputRefPaths.guardShape(List.of("amount"))).containsExactly("*");
    // 请求路径的首段（principal / resource / agent）不是 flatten 的键：按未知首段处理
    assertThat(GuardInputRefPaths.guardShape(List.of("principal.id"))).containsExactly("*");
    assertThat(GuardInputRefPaths.guardShape(List.of("context."))).containsExactly("*");
  }

  @Test
  void resultIsSortedDeduplicatedAndNullStaysNull() {
    assertThat(GuardInputRefPaths.guardShape(null)).isNull();
    assertThat(GuardInputRefPaths.guardShape(List.of())).isEmpty();
    assertThat(GuardInputRefPaths.guardShape(List.of("principal_roles.1", "context.b", "principal_roles", "action",
        "context.a"))).containsExactly("action.name", "context.a", "context.b", "principal.roles");
    // 「*」与具体路径并存时都保留：具体路径即使请求里缺失也要计入（缺字段 = 未验证）
    assertThat(GuardInputRefPaths.guardShape(List.of("context.amount", ""))).containsExactly("*", "context.amount");
  }

  /** 证据形状的路径（写事件时已映射好的 context.* 与「*」）是 guard 形状映射的不动点。 */
  @Test
  void evidenceShapePathsAreFixedPoints() {
    List<String> evidence = List.of("*", "context.applicant", "context.applicant.income");
    assertThat(GuardInputRefPaths.guardShape(evidence)).containsExactlyElementsOf(evidence);
  }

  /** 映射表与 flatten 共用一张常量表：flatten 新增键而映射表漏改时失败。 */
  @Test
  void tableKeysEqualFlattenKeys() {
    List<String> tableKeys = Arrays.stream(FlatField.values()).map(FlatField::key).toList();

    assertThat(new GuardActionMapper().flatten(fullAction()).keySet()).containsExactlyElementsOf(tableKeys);
  }

  /** 每个 guard 形状的映射结果都落在 presentLeafPaths 的请求路径空间内（同一路径空间）。 */
  @Test
  void mappedPathsAlignWithPresentLeafPaths() {
    SortedSet<String> leaves = VerifiedInputsGate.presentLeafPaths(fullAction());
    for (FlatField field : FlatField.values()) {
      String mapped = GuardInputRefPaths.guardShape(List.of(field.key())).get(0);
      if (field.shape() == FlatField.Shape.OBJECT) {
        // context 整体：其下每个叶子都在路径空间内，且子路径同样对齐
        assertThat(leaves).anyMatch(l -> l.startsWith(mapped + "."));
        assertThat(leaves).contains(GuardInputRefPaths.guardShape(List.of("context.amount")).get(0));
      } else {
        assertThat(leaves).as(field.key()).contains(mapped);
      }
    }
  }
}
