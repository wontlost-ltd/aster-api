package io.aster.audit.service;

import io.aster.audit.dto.*;
import io.aster.policy.tenant.TenantContext;
import io.quarkus.cache.CacheResult;
import io.quarkus.cache.CacheKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 策略分析服务（Phase 3.3）
 *
 * 提供实时审计仪表板功能：
 * - 聚合统计（按时间粒度聚合版本使用情况）
 * - 异常检测（高失败率、僵尸版本、性能劣化）
 * - 版本对比（对比两个版本的性能指标）
 */
@ApplicationScoped
public class PolicyAnalyticsService {

    @Inject
    EntityManager em;

    @Inject
    TenantContext tenantContext;

    @ConfigProperty(name = "quarkus.datasource.db-kind", defaultValue = "postgresql")
    String dbKind;

    /**
     * 获取版本使用统计（按时间粒度聚合）
     *
     * 使用索引：idx_workflow_state_started_at, idx_workflow_state_policy_version
     *
     * Phase 3.4: 添加缓存层，TTL 15 分钟，减少重复查询的数据库负载
     * Phase 4.3: 强制租户过滤。
     *
     * <p>安全审计 C2：{@code tenantId} 必须是显式 {@code @CacheKey} 参数。历史缺陷是
     * tenantId 从 {@link io.aster.policy.tenant.TenantContext} 隐式读取、**不进 cache key**，
     * 而结果是租户过滤的——租户 B 先缓存某 versionId 的统计，租户 A 用相同
     * (versionId, granularity, from, to) 命中缓存即拿到 B 的数据（跨租户泄漏）。
     * 调用方（resource）负责传入已鉴权的租户 ID；不在缓存方法内隐式读上下文。
     *
     * @param tenantId   租户 ID（已鉴权，显式传入并进入 cache key）
     * @param versionId  策略版本 ID
     * @param granularity 时间粒度（hour, day, week, month）
     * @param from       开始时间
     * @param to         结束时间
     * @return 按时间桶聚合的统计数据
     */
    @CacheResult(cacheName = "version-usage-stats")
    public List<VersionUsageStatsDTO> getVersionUsageStats(
        @CacheKey String tenantId,
        @CacheKey Long versionId,
        @CacheKey String granularity,
        @CacheKey Instant from,
        @CacheKey Instant to
    ) {
        // 1. 构建 DATE_TRUNC 表达式（支持 H2 兼容）
        String dateTruncExpr = buildDateTruncExpression(granularity, "started_at");

        // 2. 构建 SQL（强制租户过滤）
        String sql = String.format("""
            SELECT
                %s AS time_bucket,
                COUNT(*) AS total_count,
                SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_count,
                SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END) AS running_count,
                AVG(CASE WHEN status = 'COMPLETED' THEN duration_ms ELSE NULL END) AS avg_duration_ms
            FROM workflow_state
            WHERE policy_version_id = ?1
              AND started_at BETWEEN ?2 AND ?3
              AND tenant_id = ?4
            GROUP BY time_bucket
            ORDER BY time_bucket ASC
            """, dateTruncExpr);

        // 3. 执行原生查询
        jakarta.persistence.Query query = em.createNativeQuery(sql)
            .setParameter(1, versionId)
            .setParameter(2, from)
            .setParameter(3, to)
            .setParameter(4, tenantId);

        // 4. 映射结果到 DTO
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        return results.stream()
            .map(row -> {
                VersionUsageStatsDTO dto = new VersionUsageStatsDTO();
                dto.versionId = versionId;
                dto.timeBucket = toInstant(row[0]);
                dto.totalCount = ((Number) row[1]).intValue();
                dto.completedCount = ((Number) row[2]).intValue();
                dto.failedCount = ((Number) row[3]).intValue();
                dto.runningCount = ((Number) row[4]).intValue();
                dto.tenantId = tenantId;

                // Bug Fix: 修复 avgDurationMs 为 null 时前端显示 NaN 的问题
                // - 当有完成记录时：avgDurationMs = 实际平均值，hasAvgDuration = true
                // - 当无完成记录时：avgDurationMs = 0.0（避免 NaN），hasAvgDuration = false
                if (row[5] != null) {
                    dto.avgDurationMs = ((Number) row[5]).doubleValue();
                    dto.hasAvgDuration = true;
                } else {
                    dto.avgDurationMs = 0.0;
                    dto.hasAvgDuration = false;
                }

                // 计算成功率（仅统计已完成的 workflow）
                int totalFinished = dto.completedCount + dto.failedCount;
                dto.successRate = totalFinished > 0
                    ? (double) dto.completedCount / totalFinished * 100.0
                    : 0.0;

                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * 检测异常情况
     *
     * 使用索引：idx_workflow_state_started_at, idx_workflow_state_completed_status
     *
     * @param threshold 失败率阈值（0-1，如 0.3 表示 30%）
     * @param days      检测时间窗口（天）
     * @return 异常报告列表
     */
    public List<AnomalyReportDTO> detectAnomalies(double threshold, int days) {
        // Phase 4.3: 检查租户上下文是否可用（HTTP请求 vs 后台调度器）
        String tenantId = tenantContext.isInitialized() ? tenantContext.getCurrentTenant() : null;
        boolean filterByTenant = (tenantId != null);

        List<AnomalyReportDTO> anomalies = new ArrayList<>();

        // 2.1 高失败率检测（Phase 3.8 扩展：捕获 sampleWorkflowId；Phase 4.3：条件租户过滤）
        String failureRateExpr = "CAST(SUM(CASE WHEN ws.status = 'FAILED' THEN 1 ELSE 0 END) AS DOUBLE PRECISION) / NULLIF(COUNT(*), 0)";

        // 构建条件SQL：调度器模式（tenantId=null）检测所有租户，HTTP模式（tenantId!=null）仅检测当前租户
        String tenantFilterClause = filterByTenant ? "AND ws.tenant_id = ?2" : "";
        String subqueryTenantFilter = filterByTenant ? "AND tenant_id = ?2" : "";

        String sqlHighFailure = String.format("""
            SELECT
                pv.id, pv.policy_id,
                COUNT(*) AS total_count,
                SUM(CASE WHEN ws.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                %s AS failure_rate,
                (SELECT workflow_id
                 FROM workflow_state
                 WHERE policy_version_id = pv.id
                   AND status = 'FAILED'
                   %s
                   AND started_at >= NOW() - INTERVAL '%d days'
                 ORDER BY started_at DESC
                 LIMIT 1) AS sample_workflow_id,
                MAX(ws.tenant_id) AS tenant_id
            FROM workflow_state ws
            JOIN policy_versions pv ON ws.policy_version_id = pv.id
            WHERE ws.started_at >= NOW() - INTERVAL '%d days'
              %s
            GROUP BY pv.id, pv.policy_id
            HAVING %s > ?1
            ORDER BY failure_rate DESC
            """, failureRateExpr, subqueryTenantFilter, days, days, tenantFilterClause, failureRateExpr);

        jakarta.persistence.Query query = em.createNativeQuery(sqlHighFailure)
            .setParameter(1, threshold);
        if (filterByTenant) {
            query.setParameter(2, tenantId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> highFailureResults = query.getResultList();

        for (Object[] row : highFailureResults) {
            AnomalyReportDTO anomaly = new AnomalyReportDTO();
            anomaly.anomalyType = "HIGH_FAILURE_RATE";
            anomaly.versionId = ((Number) row[0]).longValue();
            anomaly.policyId = (String) row[1];
            anomaly.metricValue = ((Number) row[4]).doubleValue();
            anomaly.threshold = threshold;
            anomaly.detectedAt = Instant.now();

            // Phase 3.8: 捕获代表性失败 workflow ID
            if (row[5] != null) {
                // PostgreSQL UUID 列可能返回 UUID 对象或 String，需兼容处理
                Object sampleWorkflowIdObj = row[5];
                if (sampleWorkflowIdObj instanceof UUID) {
                    anomaly.sampleWorkflowId = (UUID) sampleWorkflowIdObj;
                } else if (sampleWorkflowIdObj instanceof String) {
                    anomaly.sampleWorkflowId = UUID.fromString((String) sampleWorkflowIdObj);
                }
            }

            // Phase 4.3: 捕获租户 ID（用于后台调度器写入）
            if (row[6] != null) {
                anomaly.tenantId = (String) row[6];
            }

            // 计算严重程度
            if (anomaly.metricValue >= 0.5) {
                anomaly.severity = "CRITICAL";
            } else if (anomaly.metricValue >= 0.3) {
                anomaly.severity = "WARNING";
            } else {
                anomaly.severity = "INFO";
            }

            anomaly.description = String.format(
                "策略版本 %s 失败率达到 %.1f%%（阈值 %.1f%%），共 %d 次执行，其中 %d 次失败",
                anomaly.policyId,
                anomaly.metricValue * 100,
                anomaly.threshold * 100,
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue()
            );
            anomaly.recommendation = "建议立即回滚到上一个稳定版本，并检查策略逻辑错误";

            anomalies.add(anomaly);
        }

        // 2.2 僵尸版本检测（Phase 3.8 扩展：捕获 sampleWorkflowId；Phase 4.3：条件租户过滤）
        // 注意：此查询独立于第一个查询，需要使用 ?1 作为租户参数
        String zombieSubqueryTenantFilter = filterByTenant ? "AND tenant_id = ?1" : "";
        String zombieTenantFilterClause = filterByTenant ? "AND ws.tenant_id = ?1" : "";

        // ★tenant_id 必须 COALESCE 到 pv.tenant_id（issue #315）：
        //   本查询是 LEFT JOIN + `HAVING ... OR MAX(ws.started_at) IS NULL`，
        //   **刻意包含「从未跑过」的版本**——僵尸检测的本意正是找出这些。
        //   而那些行上每个 ws.* 都是 NULL，`MAX(ws.tenant_id)` 自然也是 NULL，
        //   写进 anomaly_reports 就撞 NOT NULL 约束、整个事务回滚。
        //
        //   生产实测：aster_api 库里该查询出 54 行、**全部** tenant 为 NULL，
        //   与日志「检测到 54 个异常」逐一对应；该表因此从未有过任何一行数据。
        //
        //   ★用 pv.tenant_id 而非放宽约束：一个从未跑过的版本，它的归属就是
        //   **版本自己的租户**，本来就不该靠 workflow 反推。放宽 NOT NULL 会让
        //   异常报告失去租户归属，别的租户就会看到不属于自己的异常。
        //   （实测 policy_versions.tenant_id 全非空：0 null / 54 行。）
        String sqlZombieVersions = String.format("""
            SELECT
                pv.id, pv.policy_id,
                MAX(ws.started_at) AS last_used_at,
                (SELECT workflow_id
                 FROM workflow_state
                 WHERE policy_version_id = pv.id
                   AND status = 'FAILED'
                   %s
                 ORDER BY started_at DESC
                 LIMIT 1) AS sample_workflow_id,
                COALESCE(MAX(ws.tenant_id), pv.tenant_id) AS tenant_id
            FROM policy_versions pv
            LEFT JOIN workflow_state ws ON pv.id = ws.policy_version_id
            WHERE 1=1 %s
            GROUP BY pv.id, pv.policy_id, pv.tenant_id
            HAVING MAX(ws.started_at) < NOW() - INTERVAL '%d days' OR MAX(ws.started_at) IS NULL
            """, zombieSubqueryTenantFilter, zombieTenantFilterClause, days);

        jakarta.persistence.Query zombieQuery = em.createNativeQuery(sqlZombieVersions);
        if (filterByTenant) {
            zombieQuery.setParameter(1, tenantId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> zombieResults = zombieQuery.getResultList();

        for (Object[] row : zombieResults) {
            AnomalyReportDTO anomaly = new AnomalyReportDTO();
            anomaly.anomalyType = "ZOMBIE_VERSION";
            anomaly.versionId = ((Number) row[0]).longValue();
            anomaly.policyId = (String) row[1];
            anomaly.severity = "INFO";
            anomaly.detectedAt = Instant.now();

            // Phase 3.8: 捕获代表性失败 workflow ID（如果存在）
            if (row[3] != null) {
                // PostgreSQL UUID 列可能返回 UUID 对象或 String，需兼容处理
                Object sampleWorkflowIdObj = row[3];
                if (sampleWorkflowIdObj instanceof UUID) {
                    anomaly.sampleWorkflowId = (UUID) sampleWorkflowIdObj;
                } else if (sampleWorkflowIdObj instanceof String) {
                    anomaly.sampleWorkflowId = UUID.fromString((String) sampleWorkflowIdObj);
                }
            }

            // Phase 4.3: 捕获租户 ID（用于后台调度器写入）
            if (row[4] != null) {
                anomaly.tenantId = (String) row[4];
            }

            Instant lastUsedAt = toInstant(row[2]);
            if (lastUsedAt != null) {
                long daysSinceLastUse = java.time.Duration.between(lastUsedAt, Instant.now()).toDays();
                anomaly.description = String.format(
                    "策略版本 %s 已 %d 天未使用，可能为僵尸版本",
                    anomaly.policyId,
                    daysSinceLastUse
                );
            } else {
                anomaly.description = String.format(
                    "策略版本 %s 从未被使用过",
                    anomaly.policyId
                );
            }
            anomaly.recommendation = "建议清理或归档该版本，释放存储空间";

            anomalies.add(anomaly);
        }

        // 2.3 性能劣化检测（Phase 3.4 + Phase 3.8 扩展：捕获 sampleWorkflowId；Phase 4.3：条件租户过滤）
        // 注意：此查询独立于前两个查询，需要使用 ?1 作为租户参数
        String degradationSubqueryTenantFilter = filterByTenant ? "AND tenant_id = ?1" : "";
        String degradationTenantFilterClause = filterByTenant ? "AND ws.tenant_id = ?1" : "";

        String sqlPerformanceDegradation = String.format("""
            SELECT
                pv.id, pv.policy_id,
                AVG(CASE WHEN ws.started_at >= NOW() - INTERVAL '7 days' THEN ws.duration_ms END) AS recent_avg_ms,
                AVG(CASE WHEN ws.started_at < NOW() - INTERVAL '7 days' AND ws.started_at >= NOW() - INTERVAL '30 days' THEN ws.duration_ms END) AS historical_avg_ms,
                COUNT(CASE WHEN ws.started_at >= NOW() - INTERVAL '7 days' THEN 1 END) AS recent_count,
                COUNT(CASE WHEN ws.started_at < NOW() - INTERVAL '7 days' AND ws.started_at >= NOW() - INTERVAL '30 days' THEN 1 END) AS historical_count,
                (SELECT workflow_id
                 FROM workflow_state
                 WHERE policy_version_id = pv.id
                   AND status = 'COMPLETED'
                   AND started_at >= NOW() - INTERVAL '1 days'
                   %s
                 ORDER BY started_at DESC
                 LIMIT 1) AS sample_workflow_id,
                MAX(ws.tenant_id) AS tenant_id
            FROM policy_versions pv
            JOIN workflow_state ws ON pv.id = ws.policy_version_id
            WHERE ws.started_at >= NOW() - INTERVAL '%d days'
              AND ws.status = 'COMPLETED'
              AND ws.duration_ms IS NOT NULL
              %s
            GROUP BY pv.id, pv.policy_id
            HAVING COUNT(CASE WHEN ws.started_at >= NOW() - INTERVAL '7 days' THEN 1 END) >= 10
               AND COUNT(CASE WHEN ws.started_at < NOW() - INTERVAL '7 days' AND ws.started_at >= NOW() - INTERVAL '30 days' THEN 1 END) >= 10
               AND AVG(CASE WHEN ws.started_at >= NOW() - INTERVAL '7 days' THEN ws.duration_ms END) > AVG(CASE WHEN ws.started_at < NOW() - INTERVAL '7 days' AND ws.started_at >= NOW() - INTERVAL '30 days' THEN ws.duration_ms END) * 1.5
            ORDER BY (AVG(CASE WHEN ws.started_at >= NOW() - INTERVAL '7 days' THEN ws.duration_ms END) - AVG(CASE WHEN ws.started_at < NOW() - INTERVAL '7 days' AND ws.started_at >= NOW() - INTERVAL '30 days' THEN ws.duration_ms END)) / AVG(CASE WHEN ws.started_at < NOW() - INTERVAL '7 days' AND ws.started_at >= NOW() - INTERVAL '30 days' THEN ws.duration_ms END) DESC
            """, degradationSubqueryTenantFilter, days, degradationTenantFilterClause);

        jakarta.persistence.Query degradationQuery = em.createNativeQuery(sqlPerformanceDegradation);
        if (filterByTenant) {
            degradationQuery.setParameter(1, tenantId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> degradationResults = degradationQuery.getResultList();

        for (Object[] row : degradationResults) {
            AnomalyReportDTO anomaly = new AnomalyReportDTO();
            anomaly.anomalyType = "PERFORMANCE_DEGRADATION";
            anomaly.versionId = ((Number) row[0]).longValue();
            anomaly.policyId = (String) row[1];
            anomaly.detectedAt = Instant.now();

            double recentAvgMs = ((Number) row[2]).doubleValue();
            double historicalAvgMs = ((Number) row[3]).doubleValue();
            int recentCount = ((Number) row[4]).intValue();
            int historicalCount = ((Number) row[5]).intValue();

            // Phase 3.8: 捕获最近完成的 workflow ID 作为样本
            if (row[6] != null) {
                // PostgreSQL UUID 列可能返回 UUID 对象或 String，需兼容处理
                Object sampleWorkflowIdObj = row[6];
                if (sampleWorkflowIdObj instanceof UUID) {
                    anomaly.sampleWorkflowId = (UUID) sampleWorkflowIdObj;
                } else if (sampleWorkflowIdObj instanceof String) {
                    anomaly.sampleWorkflowId = UUID.fromString((String) sampleWorkflowIdObj);
                }
            }

            // Phase 4.3: 捕获租户 ID（用于后台调度器写入）
            if (row[7] != null) {
                anomaly.tenantId = (String) row[7];
            }

            double degradationRatio = (recentAvgMs - historicalAvgMs) / historicalAvgMs;
            anomaly.metricValue = degradationRatio;  // 如 1.2 表示劣化 120%
            anomaly.threshold = 0.5;  // 阈值 50%

            // 计算严重程度
            if (degradationRatio >= 1.0) {
                anomaly.severity = "CRITICAL";  // 劣化 ≥100%
            } else if (degradationRatio >= 0.5) {
                anomaly.severity = "WARNING";   // 劣化 50-100%
            } else {
                anomaly.severity = "INFO";
            }

            anomaly.description = String.format(
                "策略版本 %s 近 7 天平均执行时长劣化 %.1f%%（从 %.1fms 增长到 %.1fms），共 %d 次执行（历史 %d 次）",
                anomaly.policyId,
                degradationRatio * 100,
                historicalAvgMs,
                recentAvgMs,
                recentCount,
                historicalCount
            );
            anomaly.recommendation = "建议立即分析性能瓶颈：检查数据库查询优化、外部服务调用延迟、代码逻辑复杂度";

            anomalies.add(anomaly);
        }

        return anomalies;
    }

    /**
     * 对比两个版本的性能指标
     *
     * 使用索引：idx_workflow_state_policy_version, idx_workflow_state_started_at
     *
     * @param versionAId 版本 A ID
     * @param versionBId 版本 B ID
     * @param days       对比时间窗口（天）
     * @return 版本对比结果
     */
    public VersionComparisonDTO compareVersions(Long versionAId, Long versionBId, int days) {
        // Phase 4.3: 强制租户过滤，从 TenantContext 自动获取
        String tenantId = tenantContext.getCurrentTenant();

        VersionComparisonDTO dto = new VersionComparisonDTO();
        dto.versionAId = versionAId;
        dto.versionBId = versionBId;

        String sql = String.format("""
            SELECT
                COUNT(*) AS workflow_count,
                SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_count,
                SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                AVG(CASE WHEN status = 'COMPLETED' THEN duration_ms ELSE NULL END) AS avg_duration_ms
            FROM workflow_state
            WHERE policy_version_id = ?1
              AND tenant_id = ?2
              AND started_at >= NOW() - INTERVAL '%d days'
            """, days);

        // 查询版本 A 统计数据
        Object[] resultA = (Object[]) em.createNativeQuery(sql)
            .setParameter(1, versionAId)
            .setParameter(2, tenantId)
            .getSingleResult();

        // 查询版本 B 统计数据
        Object[] resultB = (Object[]) em.createNativeQuery(sql)
            .setParameter(1, versionBId)
            .setParameter(2, tenantId)
            .getSingleResult();

        // 填充版本 A 数据
        dto.versionAWorkflowCount = ((Number) resultA[0]).intValue();
        int versionACompleted = ((Number) resultA[1]).intValue();
        int versionAFailed = ((Number) resultA[2]).intValue();
        int versionATotalFinished = versionACompleted + versionAFailed;
        dto.versionASuccessRate = versionATotalFinished > 0
            ? (double) versionACompleted / versionATotalFinished * 100.0
            : 0.0;
        // Bug Fix: 修复 avgDurationMs 为 null 时前端显示 NaN 的问题
        if (resultA[3] != null) {
            dto.versionAAvgDurationMs = ((Number) resultA[3]).doubleValue();
            dto.hasVersionAAvgDuration = true;
        } else {
            dto.versionAAvgDurationMs = 0.0;
            dto.hasVersionAAvgDuration = false;
        }

        // 填充版本 B 数据
        dto.versionBWorkflowCount = ((Number) resultB[0]).intValue();
        int versionBCompleted = ((Number) resultB[1]).intValue();
        int versionBFailed = ((Number) resultB[2]).intValue();
        int versionBTotalFinished = versionBCompleted + versionBFailed;
        dto.versionBSuccessRate = versionBTotalFinished > 0
            ? (double) versionBCompleted / versionBTotalFinished * 100.0
            : 0.0;
        // Bug Fix: 修复 avgDurationMs 为 null 时前端显示 NaN 的问题
        if (resultB[3] != null) {
            dto.versionBAvgDurationMs = ((Number) resultB[3]).doubleValue();
            dto.hasVersionBAvgDuration = true;
        } else {
            dto.versionBAvgDurationMs = 0.0;
            dto.hasVersionBAvgDuration = false;
        }

        // 计算胜出版本
        double successRateDiff = Math.abs(dto.versionASuccessRate - dto.versionBSuccessRate);

        if (successRateDiff > 5.0) {
            // 成功率差异 > 5%，成功率高者获胜
            if (dto.versionASuccessRate > dto.versionBSuccessRate) {
                dto.winner = "A";
                dto.recommendation = String.format(
                    "版本 A 成功率更高（%.1f%% vs %.1f%%），建议使用版本 A",
                    dto.versionASuccessRate, dto.versionBSuccessRate
                );
            } else {
                dto.winner = "B";
                dto.recommendation = String.format(
                    "版本 B 成功率更高（%.1f%% vs %.1f%%），建议使用版本 B",
                    dto.versionBSuccessRate, dto.versionASuccessRate
                );
            }
        } else if (dto.hasVersionAAvgDuration && dto.hasVersionBAvgDuration) {
            // 成功率相近，比较平均执行时长（仅当两个版本都有有效数据时）
            double durationDiffPercent = Math.abs(dto.versionAAvgDurationMs - dto.versionBAvgDurationMs)
                / Math.min(dto.versionAAvgDurationMs, dto.versionBAvgDurationMs);

            if (durationDiffPercent > 0.2) {
                // 时长差异 > 20%，时长短者获胜
                if (dto.versionAAvgDurationMs < dto.versionBAvgDurationMs) {
                    dto.winner = "A";
                    dto.recommendation = String.format(
                        "版本 A 执行更快（%.1fms vs %.1fms），建议使用版本 A",
                        dto.versionAAvgDurationMs, dto.versionBAvgDurationMs
                    );
                } else {
                    dto.winner = "B";
                    dto.recommendation = String.format(
                        "版本 B 执行更快（%.1fms vs %.1fms），建议使用版本 B",
                        dto.versionBAvgDurationMs, dto.versionAAvgDurationMs
                    );
                }
            } else {
                dto.winner = "TIE";
                dto.recommendation = "两个版本性能表现相近，可根据其他因素（如功能特性）选择";
            }
        } else {
            dto.winner = "TIE";
            dto.recommendation = "两个版本性能表现相近，可根据其他因素（如功能特性）选择";
        }

        return dto;
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneOffset.UTC).toInstant();
        }
        throw new IllegalArgumentException("Unsupported time bucket type: " + value.getClass());
    }

    /**
     * 构建 DATE_TRUNC 表达式（支持 H2 兼容性）
     *
     * PostgreSQL 使用 DATE_TRUNC 函数，H2 使用 FORMATDATETIME 函数模拟
     *
     * @param granularity 时间粒度（hour, day, week, month）
     * @param column      列名
     * @return DATE_TRUNC 表达式
     */
    private String buildDateTruncExpression(String granularity, String column) {
        if ("h2".equalsIgnoreCase(dbKind)) {
            return switch (granularity) {
                case "hour" -> "PARSEDATETIME(FORMATDATETIME(" + column + ", 'yyyy-MM-dd HH:00:00'), 'yyyy-MM-dd HH:mm:ss')";
                case "day" -> "PARSEDATETIME(FORMATDATETIME(" + column + ", 'yyyy-MM-dd'), 'yyyy-MM-dd')";
                case "week" -> "PARSEDATETIME(FORMATDATETIME(DATEADD('day', -(DAYOFWEEK(" + column + ")-1), " + column + "), 'yyyy-MM-dd'), 'yyyy-MM-dd')";
                case "month" -> "PARSEDATETIME(FORMATDATETIME(" + column + ", 'yyyy-MM-01'), 'yyyy-MM-dd')";
                default -> throw new IllegalArgumentException("Invalid granularity: " + granularity);
            };
        } else {
            return "DATE_TRUNC('" + granularity + "', " + column + ")";
        }
    }
}
