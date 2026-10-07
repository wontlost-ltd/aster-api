package io.aster.policy.event;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.monitoring.BusinessMetrics;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * 审计事件监听器 - 异步处理审计事件并持久化。
 *
 * <p>映射、脱敏、加锁、哈希链与落库全部由 {@link AuditChainAppender} 承担（与同步路径共用）；
 * 本类只负责异步投递、事务边界与成功/失败指标，失败不外抛以免影响主业务流程。
 */
@ApplicationScoped
public class AuditEventListener {

    @Inject
    AuditChainAppender appender;

    @Inject
    BusinessMetrics businessMetrics;

    /**
     * 监听审计事件并持久化。
     *
     * 使用 @ObservesAsync 实现异步处理，避免阻塞主业务流程。
     * 注意：异步事件不支持 TransactionPhase，需要在新事务中处理。
     */
    @Transactional
    public void onAuditEvent(@ObservesAsync AuditEvent event) {
        try {
            var appended = appender.appendInCurrentTransaction(event);
            businessMetrics.recordAuditLogWrite();

            Log.debugf(
                "Audit event persisted: type=%s, tenant=%s, module=%s, hash=%s",
                event.eventType(),
                event.tenantId(),
                event.policyModule(),
                appended.currentHash()
            );
        } catch (Exception e) {
            // 计数后再记日志：审计记录丢失必须在指标上可见，不能只躺在日志里。
            businessMetrics.recordAuditLogWriteFailure();
            Log.errorf(
                e,
                "Failed to persist audit event: type=%s, tenant=%s",
                event.eventType(),
                event.tenantId()
            );
        }
    }

    /** 同租户哈希链锁 ID；实现见 {@link AuditChainAppender#tenantLockId(String)}。 */
    public static long tenantLockId(String tenantId) {
        return AuditChainAppender.tenantLockId(tenantId);
    }
}
