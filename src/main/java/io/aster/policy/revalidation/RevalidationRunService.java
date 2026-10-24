package io.aster.policy.revalidation;

import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.compiler.ValidationRepository;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.stability.ToolchainIdentityProvider;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RevalidationRunService {
    private static final Logger LOG = Logger.getLogger(RevalidationRunService.class);
    @Inject EntityManager entityManager;
    @Inject ToolchainIdentityProvider toolchains;
    @Inject PolicyCompiler compiler;
    @Inject ValidationRepository validations;
    @Inject Event<AuditEvent> auditEvents;

    @Transactional
    public Optional<Long> enqueueIfToolchainChanged() {
        String toolchain = toolchains.currentToolchainId();
        List<?> latest = entityManager.createNativeQuery("SELECT toolchain_id FROM policy_revalidation_run WHERE status='done' ORDER BY id DESC LIMIT 1").getResultList();
        if (!latest.isEmpty() && toolchain.equals(latest.getFirst())) return Optional.empty();
        return enqueue("all", null, toolchain, STARTUP_TRIGGER);
    }

    /** 启动期自动重验的发起者标识；管理员发起的由 REST 层传入 admin:<用户 id>。 */
    public static final String STARTUP_TRIGGER = "startup:toolchain-change";

    /**
     * 入队一次重验；triggeredBy 落到 policy_revalidation_run.triggered_by 并作为审计事件的 performedBy，
     * 让「谁在什么时候让全平台策略重新过门禁」可追溯。
     */
    @Transactional
    public Optional<Long> enqueue(String scope, String tenantId, String triggeredBy) {
        return enqueue(scope, tenantId, toolchains.currentToolchainId(), triggeredBy);
    }

    private Optional<Long> enqueue(String scope, String tenantId, String toolchain, String triggeredBy) {
        var maxQuery = entityManager.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM policy_versions" +
            ("tenant".equals(scope) ? " WHERE tenant_id = :tenant" : ""));
        if ("tenant".equals(scope)) maxQuery.setParameter("tenant", tenantId);
        Number max = (Number) maxQuery.getSingleResult();
        List<?> rows = entityManager.createNativeQuery("""
            INSERT INTO policy_revalidation_run(toolchain_id, scope, scope_tenant, status, scope_max_version_id, total, triggered_by)
            VALUES (:toolchain, :scope, :tenant, 'queued', :maxId,
                (SELECT COUNT(*) FROM policy_versions WHERE id <= :maxId AND (:scope <> 'tenant' OR tenant_id = :tenant)), :triggeredBy)
            ON CONFLICT DO NOTHING RETURNING id
            """).setParameter("toolchain", toolchain).setParameter("scope", scope)
            .setParameter("tenant", tenantId).setParameter("maxId", max.longValue())
            .setParameter("triggeredBy", triggeredBy).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        Long runId = ((Number) rows.getFirst()).longValue();
        audit("policy.revalidation.start", runId, scope.equals("tenant") ? tenantId : "system", triggeredBy);
        return Optional.of(runId);
    }

    @Transactional
    public boolean claimAndProcessBatch(int batchSize) {
        List<?> rows = entityManager.createNativeQuery("""
            SELECT id, toolchain_id, scope, scope_tenant, cursor, scope_max_version_id, checked, invalid_count
            FROM policy_revalidation_run
            WHERE status='queued' OR (status='running' AND heartbeat_at < now() - interval '2 minutes')
            ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """).getResultList();
        if (rows.isEmpty()) return false;
        Object[] run = (Object[]) rows.getFirst();
        long runId = ((Number) run[0]).longValue();
        String toolchain = (String) run[1];
        String scope = (String) run[2];
        String tenant = (String) run[3];
        long cursor = ((Number) run[4]).longValue();
        long maxId = ((Number) run[5]).longValue();
        int checked = ((Number) run[6]).intValue();
        int invalid = ((Number) run[7]).intValue();
        Instant now = Instant.now();
        entityManager.createNativeQuery("UPDATE policy_revalidation_run SET status='running', started_at=COALESCE(started_at,:now), heartbeat_at=:now WHERE id=:id")
            .setParameter("now", now).setParameter("id", runId).executeUpdate();
        try {
            List<PolicyVersion> batch = entityManager.createQuery("SELECT v FROM PolicyVersion v WHERE v.id > :cursor AND v.id <= :maxId AND (:scope <> 'tenant' OR v.tenantId = :tenant) ORDER BY v.id", PolicyVersion.class)
                .setParameter("cursor", cursor).setParameter("maxId", maxId).setParameter("scope", scope)
                .setParameter("tenant", tenant).setMaxResults(Math.max(1, batchSize)).getResultList();
            for (PolicyVersion version : batch) {
                var result = compiler.typecheckVersion(version);
                boolean valid = ValidationRepository.isValid(result);
                validations.insertIfAbsent(version.id, toolchain, valid ? "valid" : "invalid", ValidationRepository.diagnosticsOf(result), runId);
                cursor = version.id;
                checked++;
                if (!valid) invalid++;
            }
            boolean done = cursor >= maxId || batch.isEmpty();
            entityManager.createNativeQuery("UPDATE policy_revalidation_run SET cursor=:cursor, heartbeat_at=:now, checked=:checked, invalid_count=:invalid, status=:status, finished_at=:finished WHERE id=:id")
                .setParameter("cursor", done ? maxId : cursor).setParameter("now", Instant.now()).setParameter("checked", checked)
                .setParameter("invalid", invalid).setParameter("status", done ? "done" : "running")
                .setParameter("finished", done ? Instant.now() : null).setParameter("id", runId).executeUpdate();
            if (done) {
                LOG.infof("策略版本重验完成: runId=%d checked=%d invalid=%d", runId, checked, invalid);
                audit("policy.revalidation.done", runId, tenant == null ? "system" : tenant, "system");
            }
            return true;
        } catch (Exception e) {
            entityManager.createNativeQuery("UPDATE policy_revalidation_run SET status='failed', finished_at=:now, error=:error WHERE id=:id")
                .setParameter("now", Instant.now()).setParameter("error", e.getMessage()).setParameter("id", runId).executeUpdate();
            LOG.errorf(e, "策略版本重验失败: runId=%d", runId);
            audit("policy.revalidation.failed", runId, tenant == null ? "system" : tenant, "system");
            return true;
        }
    }

    private void audit(String action, Long runId, String tenant, String performedBy) {
        auditEvents.fireAsync(new AuditEvent(EventType.POLICY_REVALIDATION, Instant.now(), tenant,
            null, null, null, null, null, performedBy, !action.endsWith("failed"), null, null,
            java.util.Map.of("action", action, "runId", runId), null, null, null, null));
    }
}
