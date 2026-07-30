package io.aster.policy.metrics;

import io.aster.audit.PostgresAnalyticsTestProfile;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * WAADR 物化视图 + REST 端点集成测试
 *
 * 用 Testcontainers Postgres 跑真实 V6.7 / V6.8 Flyway 迁移，
 * 插入 policy_versions 数据后 REFRESH 物化视图，再调 REST 验证。
 *
 * 视图 SQL 详见 db/migration/V6.8.0__create_pm_weekly_waadr.sql
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PostgresAnalyticsTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WaadrMetricsResourceIT {

    @Inject
    EntityManager em;

    @Inject
    WaadrMetricsService service;

    @BeforeEach
    @Transactional
    void cleanData() {
        em.createNativeQuery("DELETE FROM policy_versions WHERE policy_id LIKE 'it-waadr-%'").executeUpdate();
        // 确保物化视图为空状态（使用 CONCURRENTLY 需要存在数据；NON-CONCURRENT 总能跑）
        em.createNativeQuery("REFRESH MATERIALIZED VIEW pm_weekly_waadr").executeUpdate();
    }

    @Test
    @Order(1)
    @Transactional
    void insertAdoptedDraftAndRefresh_yieldsWaadrRow() {
        // 插入 3 条满足 WAADR 条件的版本（同 tenant，同周）。
        // ★日期必须相对 now：service.fetchWeeklyWaadr(weeks=12) 过滤 week >= now-84天，
        // 硬编码绝对日期会随时间滚出 12 周窗口导致测试确定性失败（原 2026-04-27 已于运行日
        // 越过窗口边界）。取本周一（UTC，与视图 date_trunc('week') 的周一分桶对齐），
        // 稳定落在窗口内。
        Instant week = LocalDate.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
        for (int i = 0; i < 3; i++) {
            PolicyVersion v = new PolicyVersion(
                "it-waadr-policy-" + i,
                "aster.test",
                "fn" + i,
                "Module aster.test.\nRule fn" + i + " given x: Return x.",
                "tester",
                null
            );
            v.tenantId = "tenant-it";
            v.sourceKind = "ai_draft_edited";
            v.authorRole = "business_expert";
            v.status = VersionStatus.APPROVED;
            v.activatedAt = week.plusSeconds(3600L * (i + 1));
            v.activatedBy = "approver";
            v.approvedBy = "approver";
            v.approvedAt = v.activatedAt;
            v.locale = "zh";
            v.persist();
        }

        // 物化视图刷新
        em.createNativeQuery("REFRESH MATERIALIZED VIEW pm_weekly_waadr").executeUpdate();

        // 通过 service 查询验证
        var rows = service.fetchWeeklyWaadr("tenant-it", 12);
        org.junit.jupiter.api.Assertions.assertFalse(rows.isEmpty(), "应至少有一行 WAADR 数据");
        long sum = rows.stream().mapToLong(r -> r.waadr()).sum();
        org.junit.jupiter.api.Assertions.assertTrue(sum >= 3, "三条 ai_draft_edited 应被聚合到至少 3");
    }

    @Test
    @Order(2)
    @Transactional
    void manualVersion_doesNotCountAsWaadr() {
        // manual 版本不应进入 WAADR
        PolicyVersion v = new PolicyVersion(
            "it-waadr-manual",
            "aster.test",
            "manual_fn",
            "Module aster.test.\nRule manual_fn given x: Return x.",
            "tester",
            null
        );
        v.tenantId = "tenant-manual";
        v.sourceKind = "manual";
        v.authorRole = "business_expert"; // 即便业务角色，manual 仍不计 WAADR
        v.status = VersionStatus.APPROVED;
        v.activatedAt = Instant.now();
        v.locale = "zh";
        v.persist();

        em.createNativeQuery("REFRESH MATERIALIZED VIEW pm_weekly_waadr").executeUpdate();
        var rows = service.fetchWeeklyWaadr("tenant-manual", 12);
        org.junit.jupiter.api.Assertions.assertTrue(rows.isEmpty(),
            "manual 版本不应被计入 WAADR（仅 ai_draft_edited 计入）");
    }

    @Test
    @Order(3)
    void restEndpoint_returnsWaadrPoints() {
        // 端点带 RBAC：要求 ADMIN 角色 + tenant 头
        given()
            .header("X-Tenant-Id", "tenant-it")
            .header("X-User-Id", "tester-admin")
            .header("X-Role", "ADMIN")
            .queryParam("weeks", "12")
            .when()
            .get("/api/v1/metrics/waadr")
            .then()
            .statusCode(200);
        // body 内容由前面测试的状态决定，这里仅断言端点返回 200
    }

    /**
     * 越权读回归：service 层拒绝空租户。
     *
     * <p>此前 tenantId==null 会省掉 {@code AND tenant_id} 谓词退化为跨租户全表聚合。
     * 现在 fail-closed 抛错。
     */
    @Test
    @Order(4)
    void nullOrBlankTenant_isRejected_notSilentlyCrossTenant() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.fetchWeeklyWaadr(null, 12),
            "tenantId=null 必须抛错，绝不能退化成跨租户聚合");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.fetchWeeklyWaadr("  ", 12),
            "tenantId 空白必须抛错");
    }

    /**
     * 越权读回归（端点层）：tenant=* 不再是提权后门。
     *
     * <p>此前 {@code @RequireRole(ADMIN)} + {@code tenant=*} 意在"仅平台管理员跨租户"，
     * 但 Role 只有租户内四级（无 PLATFORM_ADMIN），故任意租户 admin 都能拉全平台数据。
     * 现在该参数已删除：即便显式传入也必须只看到自己租户的数据。
     *
     * <p>本测试种入两个租户各自的 WAADR 行，然后以 tenant-a 身份带 {@code tenant=*}
     * 请求，断言响应中**不含** tenant-b。
     */
    @Test
    @Order(5)
    void tenantWildcardParam_cannotEscalateToCrossTenantRead() {
        seedWaadrRow("tenant-a", "it-waadr-iso-a");
        seedWaadrRow("tenant-b", "it-waadr-iso-b");
        refreshView();

        // 前提校验：两个租户各自都真的有数据（否则下面的"看不到 b"会假通过）
        org.junit.jupiter.api.Assertions.assertFalse(
            service.fetchWeeklyWaadr("tenant-b", 12).isEmpty(),
            "前提：tenant-b 必须有数据，否则跨租户断言无意义（防假通过）");

        given()
            .header("X-Tenant-Id", "tenant-a")
            .header("X-User-Id", "tester-admin")
            .header("X-Role", "ADMIN")
            .queryParam("weeks", "12")
            .queryParam("tenant", "*")   // 曾经的提权后门
            .when()
            .get("/api/v1/metrics/waadr")
            .then()
            .statusCode(200)
            .body("findAll { it.tenantId == 'tenant-b' }", hasSize(0))
            .body("findAll { it.tenantId != 'tenant-a' }", hasSize(0));
    }

    @Transactional
    void seedWaadrRow(String tenantId, String policyId) {
        Instant week = LocalDate.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
        PolicyVersion v = new PolicyVersion(
            policyId,
            "aster.test",
            "iso_fn",
            "Module aster.test.\nRule iso_fn given x: Return x.",
            "tester",
            null
        );
        v.tenantId = tenantId;
        v.sourceKind = "ai_draft_edited";
        v.authorRole = "business_expert";
        v.status = VersionStatus.APPROVED;
        v.activatedAt = week.plusSeconds(3600L);
        v.activatedBy = "approver";
        v.approvedBy = "approver";
        v.approvedAt = v.activatedAt;
        v.locale = "zh";
        v.persist();
    }

    @Transactional
    void refreshView() {
        em.createNativeQuery("REFRESH MATERIALIZED VIEW pm_weekly_waadr").executeUpdate();
    }
}
