package io.aster.audit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.aster.audit.dto.ReceiptLookupResponse;
import io.aster.audit.dto.ReceiptLookupResponse.Approval;
import io.aster.audit.dto.ReceiptLookupResponse.Consume;
import io.aster.audit.dto.ReceiptLookupResponse.Receipt;
import io.aster.audit.dto.ReceiptLookupResponse.Result;
import io.aster.audit.dto.ReceiptLookupResponse.Reveal;
import io.aster.common.JacksonMappers;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.EventType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
        return new ReceiptLookupResponse(List.of(), List.of(), missing(ids, found(receipts, Receipt::correlationId)),
            receipts, List.of(), List.of());
    }

    /**
     * 按决策 ID 查询：receipts 为决策行，approvals 为同决策的审批事件行。
     * 审批只随命中的决策收据返回：一个 id 不会同时出现在 approvals 与 missing 中。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ReceiptLookupResponse byDecisionIds(String tenantId, List<String> ids) {
        List<AuditLog> rows = AuditLog.findByDecisionIds(tenantId, ids);
        Map<String, GuardReceiptEntity> pointers = pointers(tenantId, ids);
        List<Receipt> receipts = rows.stream()
            .filter(r -> EventType.ACTION_GUARD_DECISION.name().equals(r.eventType))
            .map(r -> toReceipt(r, pointers.get(r.decisionId))).toList();
        Set<String> found = found(receipts, Receipt::decisionId);
        List<Approval> approvals = rows.stream()
            .filter(r -> EventType.ACTION_GUARD_APPROVAL.name().equals(r.eventType))
            .filter(r -> found.contains(r.decisionId))
            .map(this::toApproval).toList();
        List<AuditLog> hits = rows.stream().filter(r -> found.contains(r.decisionId)).toList();
        List<Reveal> reveals = hits.stream()
            .filter(r -> EventType.ACTION_GUARD_INPUT_REVEAL.name().equals(r.eventType))
            .map(r -> new Reveal(text(meta(r), "approvalId"), r.id, r.currentHash, r.decisionId, r.performedBy,
                r.timestamp)).toList();
        return new ReceiptLookupResponse(approvals, consumes(hits, pointers), missing(ids, found), receipts,
            results(hits, pointers), reveals);
    }

    /** 命中决策的收据行指针，键为小写 UUID 字符串；非法 UUID 的 id 不可能有收据行，直接跳过。 */
    private static Map<String, GuardReceiptEntity> pointers(String tenantId, List<String> ids) {
        List<UUID> uuids = ids.stream().map(AuditReceiptService::parseUuid).filter(Objects::nonNull).toList();
        Map<String, GuardReceiptEntity> byId = new HashMap<>();
        if (uuids.isEmpty()) {
            return byId;
        }
        GuardReceiptEntity.<GuardReceiptEntity>list("tenantId = ?1 and decisionId in ?2", tenantId, uuids)
            .forEach(e -> byId.put(e.decisionId.toString(), e));
        return byId;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 每条尝试事件一项（按链顺序）；判定事件按 attemptReceiptHash 回指尝试事件配对。 */
    private List<Consume> consumes(List<AuditLog> rows, Map<String, GuardReceiptEntity> pointers) {
        Map<String, String> outcomeByAttemptHash = new HashMap<>();
        for (AuditLog r : rows) {
            if (EventType.ACTION_GUARD_CONSUME_OUTCOME.name().equals(r.eventType)) {
                Map<String, Object> m = meta(r);
                outcomeByAttemptHash.putIfAbsent(text(m, "attemptReceiptHash"), text(m, "outcome"));
            }
        }
        return rows.stream()
            .filter(r -> EventType.ACTION_GUARD_CONSUME_ATTEMPT.name().equals(r.eventType))
            .map(r -> {
                Map<String, Object> m = meta(r);
                GuardReceiptEntity p = pointers.get(r.decisionId);
                boolean authoritative = p != null && r.id.equals(p.consumeAuditId);
                String outcome = outcomeByAttemptHash.getOrDefault(r.currentHash,
                    authoritative ? "crashed-unknown" : "unknown");
                return new Consume(text(m, "actionHash"), r.id, text(m, "attemptId"), authoritative,
                    r.currentHash, r.decisionId, text(m, "executorId"), outcome, r.timestamp);
            }).toList();
    }

    private List<Result> results(List<AuditLog> rows, Map<String, GuardReceiptEntity> pointers) {
        return rows.stream()
            .filter(r -> EventType.ACTION_GUARD_RESULT.name().equals(r.eventType))
            .map(r -> {
                Map<String, Object> m = meta(r);
                GuardReceiptEntity p = pointers.get(r.decisionId);
                boolean authoritative = p != null && r.id.equals(p.resultAuditId);
                return new Result(r.id, text(m, "attemptId"), authoritative, text(m, "consumeReceiptHash"),
                    r.currentHash, r.decisionId, text(m, "errorCode"), text(m, "executorId"),
                    text(m, "finishedAt"), text(m, "resultHash"), text(m, "status"), r.timestamp);
            }).toList();
    }

    private Map<String, Object> meta(AuditLog r) {
        Map<String, Object> m = parseMetadata(r.metadata);
        return m == null ? Map.of() : m;
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
        return toReceipt(r, null);
    }

    private Receipt toReceipt(AuditLog r, GuardReceiptEntity pointer) {
        return new Receipt(r.id, pointer == null ? null : pointer.consumeAuditId,
            pointer == null ? null : pointer.resultAuditId, r.correlationId, r.currentHash, r.decisionId, r.eventType,
            r.hashVersion, parseMetadata(r.metadata), r.prevHash, r.timestamp);
    }

    private Approval toApproval(AuditLog r) {
        Map<String, Object> meta = meta(r);
        return new Approval(r.id, text(meta, "comment"), r.currentHash, r.timestamp, text(meta, "decidedBy"),
            r.decisionId, text(meta, "decisionReceiptHash"), bool(meta, "inputRevealed"),
            text(meta, "inputSnapshotHash"), bool(meta, "inputSnapshotMatched"), text(meta, "inputStatus"),
            text(meta, "outcome"), text(meta, "requiredRole"));
    }

    private static Boolean bool(Map<String, Object> meta, String key) {
        return meta.get(key) instanceof Boolean b ? b : null;
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
