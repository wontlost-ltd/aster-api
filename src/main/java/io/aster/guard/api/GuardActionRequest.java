package io.aster.guard.api;

import io.aster.policy.evidence.AgentIdentity;

import java.util.List;
import java.util.Map;

/**
 * Action Guard 决策请求体（ADR 0040 §2.2 / §3.1）。
 *
 * <p>只做结构承载，不做业务校验：必填项、长度、context 深度与大小由
 * {@link io.aster.guard.GuardActionMapper#flatten} 统一校验并映射为 {@code invalid_action}。
 *
 * @param policyModule   被评估策略的模块名
 * @param policyFunction 被评估策略的函数名
 * @param action         Agent 提议执行的动作
 * @param waitSeconds    REQUIRE_APPROVAL 时服务端最多等待的秒数（0–30，缺省 0）
 */
public record GuardActionRequest(String policyModule, String policyFunction, Action action, Integer waitSeconds) {

  /** 提议动作：谁（principal）对什么（resource）做什么（action），附带业务上下文与 Agent 信息。 */
  public record Action(Principal principal, ActionName action, Resource resource, Map<String, Object> context, AgentIdentity agent) {}

  /** 发起主体；{@code id} 必填，{@code type} 缺省 {@code "agent"}。 */
  public record Principal(String id, String type, List<String> roles) {}

  /** 动作名；{@code name} 必填。 */
  public record ActionName(String name) {}

  /** 动作作用的资源；缺省为空串。 */
  public record Resource(String type, String id) {}
}
