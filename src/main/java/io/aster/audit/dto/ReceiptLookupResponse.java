package io.aster.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 审计链收据查询结果（ADR 0041 §2.4）。
 *
 * <p>字段序钉成字母序，空集合与 null 不输出，与 EvaluationResponse 的协议口径一致。
 * {@code missing} 为请求中未命中的 id，保持请求顺序。
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ReceiptLookupResponse(List<Approval> approvals, List<Consume> consumes, List<String> missing,
                                    List<Receipt> receipts, List<Result> results) {

    /** 链上一条审计行的收据；metadata 解析失败时为 {@code {"_raw": 原文}}。 */
    @JsonPropertyOrder(alphabetic = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(Long auditId, Long authoritativeConsumeAuditId, Long authoritativeResultAuditId,
                          String correlationId, String currentHash, String decisionId,
                          String eventType, Short hashVersion, Map<String, Object> metadata,
                          String prevHash, Instant timestamp) {}

    /**
     * 一次 consume 尝试（ACTION_GUARD_CONSUME_ATTEMPT 行）及其判定。并发下同一决策可有多条尝试，
     * 只有 {@code guard_receipts.consume_audit_id} 指向的那条 {@code authoritative=true}。
     * outcome 取判定事件的 {@code consumed | rejected:<code>}；缺判定事件时权威尝试为
     * {@code crashed-unknown}（行已提交、判定事件未写成），非权威尝试为 {@code unknown}。
     */
    @JsonPropertyOrder(alphabetic = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Consume(String actionHash, Long auditId, String attemptId, boolean authoritative,
                          String currentHash, String decisionId, String executorId, String outcome,
                          Instant timestamp) {}

    /** 一次执行结果回写（ACTION_GUARD_RESULT 行）；{@code authoritative} 表示是否为 {@code result_audit_id} 所指。 */
    @JsonPropertyOrder(alphabetic = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(Long auditId, String attemptId, boolean authoritative, String consumeReceiptHash,
                         String currentHash, String decisionId, String errorCode, String executorId,
                         String finishedAt, String resultHash, String status, Instant timestamp) {}

    /** Action Guard 人工审批结论，字段取自 ACTION_GUARD_APPROVAL 行的 metadata，decidedAt 取行时间戳。 */
    @JsonPropertyOrder(alphabetic = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Approval(Long auditId, String comment, String currentHash, Instant decidedAt,
                           String decidedBy, String decisionId, String decisionReceiptHash,
                           String outcome, String requiredRole) {}
}
