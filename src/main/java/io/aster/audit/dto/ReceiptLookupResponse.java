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
public record ReceiptLookupResponse(List<Approval> approvals, List<String> missing, List<Receipt> receipts) {

    /** 链上一条审计行的收据；metadata 解析失败时为 {@code {"_raw": 原文}}。 */
    @JsonPropertyOrder(alphabetic = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(Long auditId, String correlationId, String currentHash, String decisionId,
                          String eventType, Short hashVersion, Map<String, Object> metadata,
                          String prevHash, Instant timestamp) {}

    /** Action Guard 人工审批结论，字段取自 ACTION_GUARD_APPROVAL 行的 metadata，decidedAt 取行时间戳。 */
    @JsonPropertyOrder(alphabetic = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Approval(Long auditId, String comment, String currentHash, String decidedAt,
                           String decidedBy, String decisionId, String decisionReceiptHash,
                           String outcome, String requiredRole) {}
}
