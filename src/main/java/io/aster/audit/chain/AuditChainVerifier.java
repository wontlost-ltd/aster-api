package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 审计哈希链验证服务（Phase 0 Task 3.3）
 *
 * 提供审计链完整性验证功能，检测以下问题：
 * - prev_hash 不匹配：断链（记录被删除或插入）
 * - current_hash 不匹配：篡改（字段被修改）
 *
 * 验证策略：
 * 1. 批量查询审计记录（按时间范围 + 租户）
 * 2. 逐条验证 prev_hash 链接和 current_hash 计算
 * 3. 支持分页验证大量记录（默认 1000 条/页）
 */
@ApplicationScoped
public class AuditChainVerifier {

    private static final int DEFAULT_PAGE_SIZE = 1000;

    /**
     * 验证指定租户在指定时间范围内的审计哈希链完整性
     *
     * @param tenantId  租户 ID
     * @param startTime 开始时间（包含）
     * @param endTime   结束时间（包含）
     * @return 验证结果
     */
    @Transactional
    public ChainVerificationResult verifyChain(String tenantId, Instant startTime, Instant endTime) {
        List<AuditLog> logs = fetchAuditLogs(tenantId, startTime, endTime);
        // issue #118：时间窗可能不含 genesis。用窗外前驱的 hash 作初始 expectedPrevHash，
        // 否则窗口首条（非 genesis）的非 null prev_hash 会与默认 null 比对而误报断链。
        SeedResult seed = resolveInitialExpectedPrevHash(tenantId, logs);
        if (seed.kind == SeedResult.Kind.BROKEN) {
            return seed.brokenChain;
        }
        // 非分页：整窗一次取全，NOT_FOUND（全 legacy）等价于 seed=null（无 hashed 记录需校验）。
        return verifyChainInternal(logs, seed.expectedPrevHash);
    }

    /**
     * 分页验证审计哈希链（用于大量记录）
     *
     * @param tenantId  租户 ID
     * @param startTime 开始时间（包含）
     * @param endTime   结束时间（包含）
     * @param pageSize  每页记录数
     * @return 验证结果
     */
    @Transactional
    public ChainVerificationResult verifyChainPaginated(
        String tenantId,
        Instant startTime,
        Instant endTime,
        int pageSize
    ) {
        int page = 0;
        int totalVerified = 0;
        String expectedPrevHash = null;
        boolean seeded = false;

        while (true) {
            List<AuditLog> logs = fetchAuditLogsPage(tenantId, startTime, endTime, page, pageSize);
            if (logs.isEmpty()) {
                break;
            }

            // issue #118：首个含 hashed 记录的页，若首条非 genesis，用窗外前驱 hash 作初始
            // expectedPrevHash。Codex 审查：若某页全 legacy（NOT_FOUND），不能标记已 seed，
            // 须让后续页继续尝试（否则第二页才出现的非 genesis 首条会用 null seed 误报）。
            if (!seeded) {
                SeedResult seed = resolveInitialExpectedPrevHash(tenantId, logs);
                if (seed.kind == SeedResult.Kind.BROKEN) {
                    return seed.brokenChain;
                }
                if (seed.kind == SeedResult.Kind.SEEDED) {
                    expectedPrevHash = seed.expectedPrevHash;
                    seeded = true;
                }
                // NOT_FOUND：本页全 legacy，不 seed，继续下一页（seeded 仍 false）。
            }

            for (AuditLog log : logs) {
                // 跳过没有哈希值的旧记录
                if (log.currentHash == null) {
                    continue;
                }

                // 检查链接
                if (!Objects.equals(log.prevHash, expectedPrevHash)) {
                    String message = String.format(
                        "prev_hash mismatch at record id=%d: expected=%s, got=%s",
                        log.id,
                        expectedPrevHash != null ? expectedPrevHash.substring(0, 8) + "..." : "null",
                        log.prevHash != null ? log.prevHash.substring(0, 8) + "..." : "null"
                    );
                    return ChainVerificationResult.invalid(log.timestamp, message, totalVerified);
                }

                String computedHash;
                try {
                    computedHash = computeHash(log);
                } catch (IllegalArgumentException unknownVersion) {
                    // 未知 hash_version：无法验证该记录 → 判定链不可信（而非 500）。
                    Log.warnf(unknownVersion, "unverifiable audit record id=%d", log.id);
                    return ChainVerificationResult.invalid(log.timestamp,
                        "unverifiable record id=" + log.id + ": " + unknownVersion.getMessage(),
                        totalVerified);
                }
                if (!computedHash.equals(log.currentHash)) {
                    String message = String.format(
                        "current_hash tampered at record id=%d",
                        log.id
                    );
                    return ChainVerificationResult.invalid(log.timestamp, message, totalVerified);
                }

                expectedPrevHash = log.currentHash;
                totalVerified++;
            }

            page++;
        }

        return ChainVerificationResult.valid(totalVerified);
    }

    // 链遍历必须按 id ASC（BIGSERIAL=真实追加顺序）排序，与写入侧 findLatestHash 的 id-desc
    // 取链尾一致（issue #115）。并发追加下事件 wall-clock timestamp 与 id 顺序可能不一致，
    // 若按 timestamp 遍历会把合法链误判为 prev_hash 断裂（假篡改告警）。timestamp 仅用于
    // 时间窗筛选（选哪些记录参与验证），不参与链顺序。

    /**
     * 查询审计日志
     */
    private List<AuditLog> fetchAuditLogs(String tenantId, Instant startTime, Instant endTime) {
        return AuditLog.find(
            "tenantId = ?1 AND timestamp >= ?2 AND timestamp <= ?3 ORDER BY id ASC",
            tenantId, startTime, endTime
        ).list();
    }

    /**
     * 分页查询审计日志
     */
    private List<AuditLog> fetchAuditLogsPage(
        String tenantId,
        Instant startTime,
        Instant endTime,
        int page,
        int pageSize
    ) {
        return AuditLog.find(
            "tenantId = ?1 AND timestamp >= ?2 AND timestamp <= ?3 ORDER BY id ASC",
            tenantId, startTime, endTime
        ).page(page, pageSize).list();
    }

    /**
     * 解析初始 expectedPrevHash（issue #118）。给定按 id 升序的窗口记录：
     * 找第一个非 legacy（有 hash）记录，若其 prev_hash 非 null（即窗口首条不是 genesis），
     * 说明它的前驱在时间窗外——查该前驱（tenant 内 current_hash == 首条 prev_hash）：
     * <ul>
     *   <li>前驱存在 → 返回首条 prev_hash 作初始 expectedPrevHash（继续正常验证，合法链切片不再误报）；</li>
     *   <li>前驱不存在 → 返回 brokenChain（prev_hash 指向不存在的记录=前驱被删/伪造=真断链）。</li>
     * </ul>
     * 首条是 genesis（prev_hash=null）或窗口全为 legacy → 返回 null seed（沿用原语义）。
     */
    private SeedResult resolveInitialExpectedPrevHash(String tenantId, List<AuditLog> logs) {
        AuditLog firstHashed = null;
        for (AuditLog log : logs) {
            if (log.currentHash != null) {
                firstHashed = log;
                break;
            }
        }
        if (firstHashed == null) {
            // 这批记录全是 legacy（无 hash）→ 尚未遇到需 seed 的记录（Codex #118 审查：
            // 分页时首页全 legacy 不能标记为已 seed，须让后续页继续尝试 seed）。
            return SeedResult.notFound();
        }
        if (firstHashed.prevHash == null) {
            // 首条即 genesis → 初始 null（原语义）。
            return SeedResult.of(null);
        }
        // 首条非 genesis：其前驱在窗外，确认真实存在【且 id 更小=链序更早】。
        AuditLog predecessor = AuditLog.findPredecessorByCurrentHash(tenantId, firstHashed.prevHash, firstHashed.id);
        if (predecessor == null) {
            String message = String.format(
                "prev_hash mismatch at record id=%d: predecessor (current_hash=%s) not found — chain broken",
                firstHashed.id,
                firstHashed.prevHash.substring(0, Math.min(8, firstHashed.prevHash.length())) + "...");
            Log.warnf(message);
            return SeedResult.broken(ChainVerificationResult.invalid(firstHashed.timestamp, message, 0));
        }
        // 前驱存在 → 用其 hash（=首条 prev_hash）作初始 expectedPrevHash。
        return SeedResult.of(firstHashed.prevHash);
    }

    /**
     * resolveInitialExpectedPrevHash 的三态返回（Codex #118 审查）：
     * SEEDED（含 seed 值，可能为 null=genesis）、NOT_FOUND（本批全 legacy，尚未确定 seed，
     * 分页时后续页应继续尝试）、BROKEN（前驱不存在=真断链，提前判定）。
     */
    private static final class SeedResult {
        enum Kind { SEEDED, NOT_FOUND, BROKEN }

        final Kind kind;
        final String expectedPrevHash;
        final ChainVerificationResult brokenChain;

        private SeedResult(Kind kind, String expectedPrevHash, ChainVerificationResult brokenChain) {
            this.kind = kind;
            this.expectedPrevHash = expectedPrevHash;
            this.brokenChain = brokenChain;
        }

        static SeedResult of(String hash) {
            return new SeedResult(Kind.SEEDED, hash, null);
        }

        static SeedResult notFound() {
            return new SeedResult(Kind.NOT_FOUND, null, null);
        }

        static SeedResult broken(ChainVerificationResult result) {
            return new SeedResult(Kind.BROKEN, null, result);
        }
    }

    /**
     * 验证哈希链内部逻辑。initialExpectedPrevHash 为窗口首条应链接的前驱 hash
     * （issue #118：时间窗不含 genesis 时由 resolveInitialExpectedPrevHash 解析得来；
     * 首条是 genesis 时为 null）。
     */
    private ChainVerificationResult verifyChainInternal(List<AuditLog> logs, String initialExpectedPrevHash) {
        if (logs.isEmpty()) {
            return ChainVerificationResult.valid(0);
        }

        String expectedPrevHash = initialExpectedPrevHash;
        int recordsVerified = 0;

        for (AuditLog log : logs) {
            // 跳过没有哈希值的旧记录（向后兼容）
            if (log.currentHash == null) {
                Log.debugf("Skipping legacy record without hash: id=%d, timestamp=%s", log.id, log.timestamp);
                continue;
            }

            // 检查 prev_hash 链接
            if (!Objects.equals(log.prevHash, expectedPrevHash)) {
                String message = String.format(
                    "prev_hash mismatch at record id=%d: expected=%s, got=%s (chain broken)",
                    log.id,
                    expectedPrevHash != null ? expectedPrevHash.substring(0, 8) + "..." : "null",
                    log.prevHash != null ? log.prevHash.substring(0, 8) + "..." : "null"
                );
                Log.warnf(message);
                return ChainVerificationResult.invalid(log.timestamp, message, recordsVerified);
            }

            // 检查 current_hash 计算
            String computedHash;
            try {
                computedHash = computeHash(log);
            } catch (IllegalArgumentException unknownVersion) {
                // 未知 hash_version：无法验证该记录 → 判定链不可信（而非 500）。
                Log.warnf(unknownVersion, "unverifiable audit record id=%d", log.id);
                return ChainVerificationResult.invalid(log.timestamp,
                    "unverifiable record id=" + log.id + ": " + unknownVersion.getMessage(),
                    recordsVerified);
            }
            if (!computedHash.equals(log.currentHash)) {
                String message = String.format(
                    "current_hash tampered at record id=%d: expected=%s, got=%s (record modified)",
                    log.id,
                    computedHash.substring(0, 8) + "...",
                    log.currentHash.substring(0, 8) + "..."
                );
                Log.warnf(message);
                return ChainVerificationResult.invalid(log.timestamp, message, recordsVerified);
            }

            expectedPrevHash = log.currentHash;
            recordsVerified++;
        }

        Log.debugf("Chain verification succeeded: tenant=%s, recordsVerified=%d", logs.get(0).tenantId, recordsVerified);
        return ChainVerificationResult.valid(recordsVerified);
    }

    /**
     * 计算审计记录的哈希值（必须与 AuditEventListener.computeHashChain 保持一致）
     */
    private String computeHash(AuditLog log) {
        // ★委托给 AuditHashPayload —— 与写侧（AuditEventListener）共用同一份公式。
        //   此前这里是写侧实现的第二份拷贝，两份之间没有一致性测试；
        //   任一处漂移都会静默产生全链验签失败或篡改漏检。
        //
        // ★按记录自身的 hash_version 选择算法：
        //   1 = 历史 6 字段公式（既有行，保持可验证）
        //   2 = 全业务字段 canonical（新行，performedBy / reason / clientIp
        //       / metadata / from-toVersion 等问责字段全部进链）
        return AuditHashPayload.digest(log, log.hashVersion);
    }
}
