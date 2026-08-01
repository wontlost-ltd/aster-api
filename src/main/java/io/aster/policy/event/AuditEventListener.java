package io.aster.policy.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.common.PIIRedactor;
import io.aster.monitoring.BusinessMetrics;
import io.aster.policy.entity.AuditLog;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * 审计事件监听器 - 异步处理审计事件并持久化。
 */
@ApplicationScoped
public class AuditEventListener {

    private static final int MAX_METADATA_SIZE = 4096;
    private final PIIRedactor piiRedactor = new PIIRedactor();
    @Inject
    ObjectMapper objectMapper;

    @Inject
    BusinessMetrics businessMetrics;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "postgresql")
    String dbKind;

    /**
     * 监听审计事件并持久化。
     *
     * 使用 @ObservesAsync 实现异步处理，避免阻塞主业务流程。
     * 注意：异步事件不支持 TransactionPhase，需要在新事务中处理。
     */
    @Transactional
    public void onAuditEvent(@ObservesAsync AuditEvent event) {
        try {
            AuditLog log = new AuditLog();
            log.eventType = event.eventType().name();
            log.timestamp = event.timestamp().truncatedTo(ChronoUnit.MICROS);
            String tenant = event.tenantId();
            if (tenant == null || tenant.isBlank()) {
                tenant = "system";
            }
            // ★tenantId 与 performedBy 是**系统标识符，不是用户 PII**，绝不能脱敏：
            //   1) tenantId 同时是哈希链的**分区键**（见下方 acquireTenantChainLock /
            //      findLatestHash / 哈希输入），写入侧改写而读取侧
            //      （RequestIdentityResolver）不改写 → 键永久不匹配；
            //   2) 后果是 verifyChain 查到 0 条记录却返回 valid=true，
            //      即"防篡改链完好"的**假绿**，且审计列表对该租户返回空。
            //   实测受损：CREDIT_CARD_PATTERN 会吃掉任何「4 组 4 位数字用 - 分隔」的串，
            //   形如 `…-1234-5678-9012-3456` 的 UUID 尾部被替成 `****-****-****-****`。
            //   生产 3430 条中已有 1272 条（37%）键被改写。
            log.tenantId = tenant;
            log.policyModule = redact(event.policyModule());
            log.policyFunction = redact(event.policyFunction());
            log.policyId = redact(event.policyId());
            log.fromVersion = event.fromVersion();
            log.toVersion = event.toVersion();
            log.performedBy = event.performedBy();
            log.success = event.success();
            log.executionTimeMs = event.executionTimeMs();
            log.errorMessage = event.errorMessage();
            log.reason = extractReason(event.metadata());
            log.metadata = serializeMetadata(event.metadata());

            // 串行化同租户的哈希链追加，消除 TOCTOU 分叉（issue #115）。
            // @ObservesAsync 是并发多线程投递：同租户两条事件并发进入时，若各自独立
            // read-latest-then-persist，会都读到同一 prev_hash → 链分叉（同一 prev_hash
            // 挂两条后继），削弱防篡改语义。用 per-tenant PostgreSQL advisory lock 在本
            // 事务内串行化「读最新→算哈希→持久化」，锁在事务提交时自动释放，不同租户仍并行。
            // 复用 PostgresEventStore 的 pg_advisory_xact_lock 先例（同仓既有模式）。
            acquireTenantChainLock(log.tenantId);

            // 计算哈希链（Phase 0 Task 3.2）
            computeHashChain(log);

            log.persist();
            businessMetrics.recordAuditLogWrite();

            Log.debugf(
                "Audit event persisted: type=%s, tenant=%s, module=%s, hash=%s",
                event.eventType(),
                event.tenantId(),
                event.policyModule(),
                log.currentHash
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

    private String redact(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return piiRedactor.redact(value);
    }

    private String extractReason(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object reason = metadata.get("reason");
        return reason != null ? redact(reason.toString()) : null;
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Map<String, Object> redactedMetadata = new HashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if (value instanceof String stringValue) {
                redactedMetadata.put(key, redact(stringValue));
            } else {
                redactedMetadata.put(key, value);
            }
        });
        if (redactedMetadata.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(redactedMetadata);
            if (json.length() > MAX_METADATA_SIZE) {
                Log.warnf(
                    "Metadata too large (%d bytes), truncating to %d bytes",
                    json.length(),
                    MAX_METADATA_SIZE
                );
                int safeLength = Math.max(0, MAX_METADATA_SIZE - 13);
                json = json.substring(0, safeLength) + "...truncated";
            }
            return json;
        } catch (Exception e) {
            Log.errorf(e, "Failed to serialize metadata");
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    /**
     * 获取 per-tenant 的哈希链追加锁（PostgreSQL advisory lock），串行化同租户的
     * 「读最新哈希→算当前哈希→持久化」，消除 @ObservesAsync 并发下的链分叉（issue #115）。
     *
     * <p>{@code pg_advisory_xact_lock} 在当前事务提交/回滚时自动释放；同租户第二个线程会
     * 阻塞到第一个提交后才继续，从而读到刚写入的最新哈希，保证链无分叉。锁按 tenantId 隔离，
     * 不同租户并行不受影响。H2（部分单测后端）不支持 advisory lock → 跳过（H2 场景不测并发）。
     */
    private void acquireTenantChainLock(String tenantId) {
        if ("h2".equalsIgnoreCase(dbKind) || tenantId == null) {
            return;
        }
        long lockId = tenantLockId(tenantId);
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockId)")
                .setParameter("lockId", lockId)
                .getSingleResult();
    }

    /**
     * 把 tenantId 稳定映射到一个 64 位锁 ID（取 SHA-256 前 8 字节）。用密码学哈希而非
     * {@code String.hashCode()} 以降低不同租户碰撞到同一锁 ID 的概率；即便偶发碰撞也只是
     * 让两个租户短暂串行化（纯性能影响，不损正确性——每个租户仍读自己的链最新值）。
     */
    public static long tenantLockId(String tenantId) {
        byte[] digest = DigestUtils.sha256(tenantId);
        long id = 0L;
        for (int i = 0; i < 8; i++) {
            id = (id << 8) | (digest[i] & 0xffL);
        }
        return id;
    }

    /**
     * 计算审计记录的哈希链（Phase 0 Task 3.2）
     *
     * 实现 per-tenant 哈希链，避免全局竞争。
     * 每条记录包含：
     * - prevHash: 前一条记录的哈希值（genesis block 为 null）
     * - currentHash: 当前记录的哈希值
     *
     * 哈希计算规则：SHA256(prev_hash + event_type + timestamp + tenant_id + policy_module + policy_function + success)
     */
    private void computeHashChain(AuditLog log) {
        try {
            // 查询该租户的最新哈希值（per-tenant chain）
            String prevHash = AuditLog.findLatestHash(log.tenantId);
            log.prevHash = prevHash;

            // 计算当前哈希
            StringBuilder content = new StringBuilder();
            if (prevHash != null) {
                content.append(prevHash);
            }
            content.append(log.eventType != null ? log.eventType : "");
            content.append(log.timestamp != null ? log.timestamp.toString() : "");
            content.append(log.tenantId != null ? log.tenantId : "");
            content.append(log.policyModule != null ? log.policyModule : "");
            content.append(log.policyFunction != null ? log.policyFunction : "");
            content.append(log.success != null ? log.success.toString() : "");

            log.currentHash = DigestUtils.sha256Hex(content.toString());

            Log.debugf(
                "Hash chain computed: tenant=%s, prevHash=%s, currentHash=%s",
                log.tenantId,
                prevHash != null ? prevHash.substring(0, 8) + "..." : "null",
                log.currentHash.substring(0, 8) + "..."
            );
        } catch (Exception e) {
            // ★不能把 hash 置 null 后照常持久化（2026-07-29 审计修复）。
            //
            // 原实现在任何异常（连接抖动、锁超时、死锁牺牲者）下都把 prevHash/currentHash
            // 置 null 再 persist。而验证器对 currentHash==null 的行是 `continue` 跳过
            // （AuditChainVerifier:95,247，本意是兼容哈希链上线前的 legacy 记录），
            // 同时**下一条记录的 prevHash 仍指向最后一条有哈希的行**——于是链校验照常
            // 报 VALID，那条记录却完全游离在完整性检查之外。
            //
            // 后果：只要制造一次数据库抖动，就能得到一条「可写入且不被任何完整性检查
            // 覆盖」的审计记录。这正是哈希链本身要防的事。
            //
            // 验证器无法区分「legacy 无哈希」与「新记录算哈希失败」，所以必须在写入侧
            // 保证不变量：**新记录要么带完整哈希，要么不进表**。抛出后由外层 catch
            // 记 error 日志并丢弃该条——记录丢失会体现在审计写入指标上，是可观测的失败；
            // 而无哈希记录是不可观测的失败，后者严重得多。
            throw new IllegalStateException(
                "Hash chain computation failed for tenant=" + log.tenantId
                    + "; refusing to persist an unverifiable audit record", e);
        }
    }
}
