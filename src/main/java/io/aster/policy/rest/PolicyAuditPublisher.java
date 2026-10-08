package io.aster.policy.rest;

import io.aster.policy.rest.model.EvaluationRequest;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.evidence.EvaluationEvidence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * R31-1：从 {@code PolicyEvaluationResource} 抽出来的审计发布器。
 *
 * <p>原 resource 类 1136 行里有两段长期稳定的辅助逻辑：
 * <ul>
 *   <li>{@code publishPolicyEvaluationEvent(...)} —— async CDI Event 发布</li>
 *   <li>{@code buildEvaluationMetadata(...)} —— context 抽取 applicantId 等附加字段</li>
 * </ul>
 * 这两段没有任何 evaluator / quota / tenant 依赖，纯函数 + 单一注入点。
 * 抽出后 resource 减少 ~50 行 + 一个 CDI 依赖；本类成为 audit 发布的
 * 唯一入口，方便后续替换为 Kafka producer 或加 sampling。
 *
 * <p>{@code @ApplicationScoped}：CDI 单例，{@code Event<AuditEvent>} 由
 * Quarkus 注入。所有方法线程安全。
 */
@ApplicationScoped
public class PolicyAuditPublisher {

    @Inject
    Event<AuditEvent> auditEventPublisher;

    /**
     * 异步发布一次策略评估审计事件。{@code metadata} 为 null 时按空 map 处理。
     * 调用方负责构造 {@link EvaluationRequest}（含 module / function 名）。
     */
    public void publish(String tenantId,
                        EvaluationRequest request,
                        String performedBy,
                        boolean success,
                        long executionTimeMs,
                        String errorMessage,
                        Map<String, Object> metadata) {
        publish(tenantId, request, performedBy, success, executionTimeMs, errorMessage, metadata, null);
    }

    /**
     * 同上，并把证据键（ADR 0041 §2.2）并入 metadata：同名键以证据为准，证据是本次评估的权威结论。
     * {@code evidence} 为 null 时与 7 参版本行为完全一致（metadata 原样透传）。
     */
    public void publish(String tenantId,
                        EvaluationRequest request,
                        String performedBy,
                        boolean success,
                        long executionTimeMs,
                        String errorMessage,
                        Map<String, Object> metadata,
                        EvaluationEvidence evidence) {
        if (auditEventPublisher == null) {
            return;
        }
        auditEventPublisher.fireAsync(
            AuditEvent.policyEvaluation(
                tenantId,
                request.policyModule(),
                request.policyFunction(),
                performedBy,
                success,
                executionTimeMs,
                errorMessage,
                mergeEvidence(metadata, evidence)
            )
        );
    }

    private static Map<String, Object> mergeEvidence(Map<String, Object> metadata, EvaluationEvidence evidence) {
        if (evidence == null) {
            return metadata == null ? Collections.emptyMap() : metadata;
        }
        Map<String, Object> merged = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        merged.putAll(evidence.toMetadata());
        return merged;
    }

    /**
     * 从 {@link EvaluationRequest#context()} 抽出常用 metadata 字段（如
     * {@code applicantId}），供 audit 事件附带。返回不可修改 map 或空 map；
     * 永不返回 null。
     */
    public Map<String, Object> buildMetadata(EvaluationRequest request) {
        if (request == null || request.context() == null || request.context().length == 0) {
            return Collections.emptyMap();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("contextSize", request.context().length);

        Object firstContext = request.context()[0];
        if (firstContext instanceof Map<?, ?> contextMap) {
            Object applicantId = contextMap.get("applicantId");
            if (applicantId != null) {
                metadata.put("applicantId", applicantId.toString());
            }
        }

        return metadata.isEmpty() ? Collections.emptyMap() : metadata;
    }
}
