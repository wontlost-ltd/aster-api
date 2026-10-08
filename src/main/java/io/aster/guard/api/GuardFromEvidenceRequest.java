package io.aster.guard.api;

/**
 * 从证据开决策的请求体（ADR 0042 §3.1）。策略模块与函数不由调用方给出，取自评估事件行，
 * 保证决策地址与链上事实一致。
 *
 * @param correlationId 评估事件的相关键（evaluate-source 返回的 {@code evidence.correlationId}）
 * @param action        待审批的提议动作，与 {@link GuardActionRequest.Action} 同一结构与校验
 */
public record GuardFromEvidenceRequest(String correlationId, GuardActionRequest.Action action) {}
