package io.aster.audit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.aster.audit.dto.ReceiptLookupResponse;
import io.aster.audit.dto.ReceiptLookupResponse.Approval;
import io.aster.audit.dto.ReceiptLookupResponse.Receipt;
import io.aster.common.JacksonMappers;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.EventType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 审计链收据查询：把 {@link AuditLog} 的相关键 / 决策 ID 投影列转成对外收据。
 *
 * <p>租户由调用方传入并下推到查询条件，跨租户的 id 一律报 missing，不泄露存在性。
 */
@ApplicationScoped
public class AuditReceiptService {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

    /** 按相关键查询：receipts 为命中行，missing 为未命中的相关键。 */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ReceiptLookupResponse byCorrelationIds(String tenantId, List<String> ids) {
        List<AuditLog> rows = AuditLog.findByCorrelationIds(tenantId, ids);
        List<Receipt> receipts = rows.stream().map(this::toReceipt).toList();
        return new ReceiptLookupResponse(List.of(), missing(ids, found(receipts, Receipt::correlationId)), receipts);
    }

    /**
     * 按决策 ID 查询：receipts 为决策行，approvals 为同决策的审批事件行。
     * 审批只随命中的决策收据返回：一个 id 不会同时出现在 approvals 与 missing 中。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ReceiptLookupResponse byDecisionIds(String tenantId, List<String> ids) {
        List<AuditLog> rows = AuditLog.findByDecisionIds(tenantId, ids);
        List<Receipt> receipts = rows.stream()
            .filter(r -> EventType.ACTION_GUARD_DECISION.name().equals(r.eventType))
            .map(this::toReceipt).toList();
        Set<String> found = found(receipts, Receipt::decisionId);
        List<Approval> approvals = rows.stream()
            .filter(r -> EventType.ACTION_GUARD_APPROVAL.name().equals(r.eventType))
            .filter(r -> found.contains(r.decisionId))
            .map(this::toApproval).toList();
        return new ReceiptLookupResponse(approvals, missing(ids, found), receipts);
    }

    private static Set<String> found(List<Receipt> receipts, Function<Receipt, String> key) {
        Set<String> found = new HashSet<>();
        receipts.forEach(r -> found.add(key.apply(r)));
        return found;
    }

    /** 请求中未命中的 id，保持请求顺序。 */
    private static List<String> missing(List<String> ids, Set<String> found) {
        return ids.stream().filter(id -> !found.contains(id)).toList();
    }

    private Receipt toReceipt(AuditLog r) {
        return new Receipt(r.id, r.correlationId, r.currentHash, r.decisionId, r.eventType,
            r.hashVersion, parseMetadata(r.metadata), r.prevHash, r.timestamp);
    }

    private Approval toApproval(AuditLog r) {
        Map<String, Object> m = parseMetadata(r.metadata);
        Map<String, Object> meta = m == null ? Map.of() : m;
        return new Approval(r.id, text(meta, "comment"), r.currentHash, r.timestamp, text(meta, "decidedBy"),
            r.decisionId, text(meta, "decisionReceiptHash"), text(meta, "outcome"),
            text(meta, "requiredRole"));
    }

    private static String text(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v == null ? null : v.toString();
    }

    /** 旧行的 metadata 可能被截断成非法 JSON：解析失败时保留原文，不丢信息。 */
    private Map<String, Object> parseMetadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JacksonMappers.DEFAULT.readValue(raw, METADATA_TYPE);
        } catch (Exception e) {
            return Map.of("_raw", raw);
        }
    }
}
