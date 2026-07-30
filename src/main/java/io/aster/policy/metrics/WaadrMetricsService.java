package io.aster.policy.metrics;

import io.aster.policy.metrics.dto.WaadrPoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * WAADR 视图查询服务
 */
@ApplicationScoped
public class WaadrMetricsService {

    @Inject
    EntityManager entityManager;

    /**
     * 查询某 tenant 最近 N 周的 WAADR 数据
     *
     * <p>★租户谓词无条件拼接（fail-closed）：此前 {@code tenantId == null} 会**省掉**
     * {@code AND tenant_id} 从而退化成跨租户全表聚合，是越权读的下半段。现在 null/空白
     * 直接抛错——宁可 500 也不返回别人的数据。调用方必须传服务端解析出的真实租户 ID。
     *
     * @param tenantId 租户 ID，必需；null 或空白抛 {@link IllegalArgumentException}
     * @param weeks    查询窗口（1–52）
     */
    @SuppressWarnings("unchecked")
    public List<WaadrPoint> fetchWeeklyWaadr(String tenantId, int weeks) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                "tenantId 必需：WAADR 查询不支持跨租户聚合（防越权读）");
        }
        String sql =
            "SELECT week, tenant_id, author_role, waadr FROM pm_weekly_waadr"
            + " WHERE week >= :since AND tenant_id = :tenantId"
            + " ORDER BY week DESC, tenant_id ASC, author_role ASC";

        Instant since = Instant.now().minusSeconds((long) weeks * 7 * 24 * 3600);

        Query q = entityManager.createNativeQuery(sql)
            .setParameter("since", Timestamp.from(since))
            .setParameter("tenantId", tenantId);

        List<Object[]> rows = q.getResultList();
        List<WaadrPoint> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Instant week = toInstant(row[0]);
            String tid = (String) row[1];
            String authorRole = (String) row[2];
            long count = ((Number) row[3]).longValue();
            result.add(new WaadrPoint(week, tid, authorRole, count));
        }
        return result;
    }

    /**
     * 兼容 JDBC 驱动 / Hibernate 不同返回类型：Timestamp / OffsetDateTime / Instant
     */
    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant inst) return inst;
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.time.LocalDateTime ldt) return ldt.toInstant(java.time.ZoneOffset.UTC);
        throw new IllegalStateException("不支持的时间类型: " + value.getClass());
    }
}
