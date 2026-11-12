package io.aster.guard.provenance;

import io.aster.guard.GuardReasonCode;
import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.api.GuardActionRequest.Resource;
import io.aster.guard.provenance.VerifiedInputsGate.GateDenial;
import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.governance.RuleMetadata;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0040 §11 D5 / ADR 0046 §11 D2：档案要求 verifiedInputs 时评估前的来源门（纯 JUnit）。 */
class VerifiedInputsGateTest {
  private static final Set<String> LEAVES = Set.of("principal.id", "action.name", "context.amount");

  private static RuleMetadata required(List<String> inputRefs) {
    return new RuleMetadata("R-1", List.of(), "eu-ai-act-high-risk", inputRefs, true);
  }

  private static InputSources verified(String... paths) {
    return new InputSources(new TreeSet<>(List.of(paths)), null, null, null, null);
  }

  @Test
  void notRequiredIsNoop() {
    RuleMetadata notRequired = new RuleMetadata("R-1", List.of(), "governed", List.of("context.amount"), false);

    assertThat(VerifiedInputsGate.check(notRequired, InputSources.NONE, LEAVES)).isEmpty();
    assertThat(VerifiedInputsGate.check(RuleMetadata.NONE, InputSources.NONE, LEAVES)).isEmpty();
  }

  @Test
  void nullInputRefsDeniesInputRefsUnavailable() {
    assertThat(VerifiedInputsGate.check(required(null), verified("context.amount"), LEAVES))
        .contains(new GateDenial(GuardReasonCode.INPUT_REFS_UNAVAILABLE, List.of()));
  }

  @Test
  void declaredReferencedFieldDenies() {
    RuleMetadata meta = required(List.of("principal_id", "context.tier", "context.amount"));

    assertThat(VerifiedInputsGate.check(meta, verified("principal.id"), LEAVES))
        .contains(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of("context.amount", "context.tier")));
  }

  @Test
  void allReferencedVerifiedPasses() {
    RuleMetadata meta = required(List.of("context.customer.tier", "principal_id"));

    // 祖先语义：覆盖 context.customer 的事实同时证实 context.customer.tier（verifiedFields 可含非叶子路径）
    assertThat(VerifiedInputsGate.check(meta, verified("context.customer", "principal.id"), LEAVES)).isEmpty();
    assertThat(VerifiedInputsGate.check(required(List.of()), InputSources.NONE, LEAVES)).isEmpty();
  }

  @Test
  void childVerifiedDoesNotVerifyReferencedParent() {
    RuleMetadata meta = required(List.of("context.customer"));

    assertThat(VerifiedInputsGate.check(meta, verified("context.customer.tier"), LEAVES))
        .contains(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of("context.customer")));
  }

  @Test
  void starWithAnyDeclaredLeafDeniesWithStar() {
    RuleMetadata meta = required(List.of("*", "context.amount"));

    assertThat(VerifiedInputsGate.check(meta, verified("principal.id", "context.amount"), LEAVES))
        .contains(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of("*")));
  }

  @Test
  void starWithAllLeavesVerifiedPasses() {
    RuleMetadata meta = required(List.of("*"));

    assertThat(VerifiedInputsGate.check(meta, verified("principal.id", "action.name", "context"), LEAVES)).isEmpty();
  }

  /** IR 的 inputRefs 是引擎源码字段路径（flatten 的键），比对前先映射为 guard 请求路径（ADR 0046 §11.6 第 3 条）。 */
  @Test
  void engineFieldPathsAreMappedBeforeCompare() {
    RuleMetadata meta = required(List.of("principal_roles", "action", "context.amount"));

    assertThat(VerifiedInputsGate.check(meta, verified("context.amount"), LEAVES))
        .contains(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of("action.name", "principal.roles")));
    assertThat(VerifiedInputsGate.check(meta, verified("principal.roles", "action.name", "context.amount"), LEAVES))
        .isEmpty();
    // 无法映射的引擎路径（根 ""、标量带子路径）按「*」处理：任一出现的叶子为 declared 即拒绝
    assertThat(VerifiedInputsGate.check(required(List.of("")), verified("principal.id"), LEAVES))
        .contains(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of("*")));
  }

  /** 显式引用的路径即使本请求里没有该字段也计入：缺字段 = 未验证 → DENY（ADR 0040 §11 D5 实施澄清）。 */
  @Test
  void explicitRefAbsentFromRequestDenies() {
    RuleMetadata meta = required(List.of("context.limit"));

    assertThat(LEAVES).doesNotContain("context.limit");
    assertThat(VerifiedInputsGate.check(meta, verified("principal.id", "action.name", "context.amount"), LEAVES))
        .contains(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of("context.limit")));
  }

  @Test
  void presentLeafPathsFollowRequestShape() {
    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("tier", "gold");
    customer.put("note", null);
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("amount", 250000);
    context.put("customer", customer);
    context.put("tags", List.of("a", "b"));
    context.put("empty", Map.of());
    Action a = new Action(new Principal("agent-42", null, List.of("ops-bot")), new ActionName("refund"),
        new Resource("customer", null), context, new AgentIdentity("openai", "gpt-5", null, null));

    // 规则只读 flatten 产出的键：principal.* / action.name / resource.* / context 叶子 / agent.model；
    // 请求未给的字段（缺省值）不算出现，数组整体是一个叶子，空对象自身算一个叶子
    assertThat(VerifiedInputsGate.presentLeafPaths(a)).containsExactly("action.name", "agent.model",
        "context.amount", "context.customer.note", "context.customer.tier", "context.empty", "context.tags",
        "principal.id", "principal.roles", "resource.type");
  }

  /** 前缀规则（ADR 0046 §11.6 第 9 条）：非叶子引用在其下出现的叶子全部 verified 时满足，任一 declared 则不满足。 */
  @Test
  void nonLeafRefSatisfiedOnlyWhenAllPresentLeavesUnderItVerified() {
    Set<String> leaves = Set.of("context.customer.tier", "context.customer.note", "context.amount");
    RuleMetadata customer = required(List.of("context.customer"));

    assertThat(VerifiedInputsGate.check(customer, verified("context.customer.tier", "context.customer.note"), leaves))
        .isEmpty();
    assertThat(VerifiedInputsGate.check(customer, verified("context.customer.tier"), leaves))
        .contains(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of("context.customer")));
    // 裸 context：其下全部出现的叶子都须 verified
    assertThat(VerifiedInputsGate.check(required(List.of("context")),
        verified("context.customer.tier", "context.customer.note"), leaves))
        .contains(new GateDenial(GuardReasonCode.UNVERIFIED_INPUT, List.of("context")));
    assertThat(VerifiedInputsGate.check(required(List.of("context")),
        verified("context.customer", "context.amount"), leaves)).isEmpty();
  }
}
