package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * {@code GET /api/v1/guard/decisions/{id}} 的响应：存储的决策响应原样展开，再附执行状态（ADR 0054 §3.3 {@code auth} 模式）。
 *
 * <p>附加字段只出现在回读端点，不进入 {@code response_json}，因此 {@code POST /decisions} 的幂等重放与
 * from-evidence 重放保持逐字节不变。全部可选，按 NON_EMPTY 省略：
 * <ul>
 *   <li>{@code actionHash} 与 {@code consumedBy}：仅收据行已消耗时写（只有 ALLOW 决策才有收据行）；</li>
 *   <li>{@code result}：仅已结果回写时写；缺省即「未写 result」，工具侧核对 consume 票据时据此拒绝重放。</li>
 * </ul>
 *
 * @param decision 存储的决策响应（字段平铺到顶层，与旧响应完全相同）
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record GuardDecisionReadResponse(
    String actionHash,
    ConsumedBy consumedBy,
    @JsonUnwrapped GuardDecisionResponse decision,
    ResultView result) {

  /** 只有决策本身、没有执行状态（无收据行或未消耗）。 */
  public static GuardDecisionReadResponse of(GuardDecisionResponse decision) {
    return new GuardDecisionReadResponse(null, null, decision, null);
  }

  /** 权威消耗者（{@code guard_receipts} 行）：自报的关联字段与加锁后的消耗时刻（ISO-8601）。 */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record ConsumedBy(String attemptId, String consumedAt, String executorId) {}

  /** 结果回写状态：{@code executed | failed | aborted} 与服务端记录时刻（ISO-8601）。 */
  @JsonPropertyOrder(alphabetic = true)
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public record ResultView(String resultAt, String status) {}
}
