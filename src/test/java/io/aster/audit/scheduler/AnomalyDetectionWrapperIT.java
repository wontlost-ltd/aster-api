package io.aster.audit.scheduler;

import io.aster.audit.dto.AnomalyReportDTO;
import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.service.AnomalyWorkflowService;
import io.aster.audit.service.PolicyAnalyticsService;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import static java.time.temporal.ChronoUnit.DAYS;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AnomalyDetectionScheduler#detectAndPersistAnomalies()} 的
 * <b>调度包装层</b>测试（issue #311）。
 *
 * <p>★<b>为什么单独测包装层</b>：{@code detectAnomalies} 与
 * {@code deleteOlderThan} 各自都有测试，但把它们串起来的四个决定只存在于
 * 这个方法里，此前测试树对它零引用：
 * <ol>
 *   <li><b>先清理旧数据、再插入新结果</b> —— 顺序反了会把刚写的记录一并删掉；</li>
 *   <li>DTO→实体的字段映射，尤其 <b>{@code tenantId} 必须落库</b>（多租户隔离）；</li>
 *   <li>只对 <b>CRITICAL</b> 提交验证动作，其余不提交；</li>
 *   <li>失败时<b>重新抛出</b>以触发事务回滚 —— 吞掉异常会留下半截数据。</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(AnomalyDetectionWrapperIT.NoSchedulerProfile.class)
class AnomalyDetectionWrapperIT {

    /** 关掉后台调度器：否则它会自己触发一次，污染下面对交互次数的断言。 */
    public static class NoSchedulerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.scheduler.enabled", "false",
                "aster.scheduler.background.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    @Inject
    AnomalyDetectionScheduler scheduler;

    @InjectMock
    PolicyAnalyticsService analyticsService;

    @InjectMock
    AnomalyWorkflowService workflowService;

    @BeforeEach
    @AfterEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery("DELETE FROM anomaly_reports").executeUpdate());
    }

    /**
     * ★<b>顺序</b>：清理必须发生在插入<b>之前</b>。
     *
     * <p>{@code deleteOlderThan} 按 {@code detectedAt < now - retentionDays} 删，
     * 而新记录的 {@code detectedAt} <b>来自 DTO</b>（不是插入时刻）。
     * 故当检测结果本身指向一个较早的时刻时（回溯分析、补跑历史窗口），
     * 顺序反转会把<b>刚插入的记录当场删掉</b> ——
     * 表现是「任务跑了、日志说持久化了 N 条，但表里什么都没有」。
     *
     * <p>★<b>用例设计的关键</b>：DTO 的 {@code detectedAt} 必须<b>落在保留期外</b>，
     * 否则顺序反转也删不掉它，这条断言就是恒真的。
     * 我第一版用了 {@code Instant.now()}，实测<b>顺序反转变异存活</b> ——
     * 那是个假绿：它只验证了「陈旧记录被清」，没验证顺序本身。
     */
    @Test
    @DisplayName("先清理旧记录再插入新结果（新记录 detectedAt 落在保留期外时才见真章）")
    void cleansBeforeInserting() {
        Mockito.when(analyticsService.detectAnomalies(Mockito.anyDouble(), Mockito.anyInt()))
            .thenReturn(List.of(
                // detectedAt = 40 天前，超出默认 30 天保留期
                dtoAt("BACKFILLED", "WARNING", "t-new", Instant.now().minus(40, DAYS))));

        scheduler.detectAndPersistAnomalies();

        Long kept = QuarkusTransaction.requiringNew().call(() ->
            ((Number) em.createNativeQuery(
                "SELECT count(*) FROM anomaly_reports WHERE anomaly_type = 'BACKFILLED'")
                .getSingleResult()).longValue());

        assertThat(kept)
            .as("★本轮插入的记录必须留下。若清理排在插入之后，"
                + "这条 detectedAt=40 天前的新记录会被当场删掉——"
                + "任务日志说「持久化了 1 条」，表里却是空的")
            .isEqualTo(1L);
    }

    /**
     * ★字段映射：{@code tenantId} 必须从 DTO 落到实体。
     *
     * <p>丢了就是多租户隔离被破坏 —— 别的租户会在自己的列表里看到这条异常。
     */
    @Test
    @DisplayName("tenantId 必须从 DTO 落库（多租户隔离）")
    void persistsTenantId() {
        Mockito.when(analyticsService.detectAnomalies(Mockito.anyDouble(), Mockito.anyInt()))
            .thenReturn(List.of(dto("HIGH_FAILURE_RATE", "WARNING", "t-new")));

        scheduler.detectAndPersistAnomalies();

        String tenant = QuarkusTransaction.requiringNew().call(() ->
            (String) em.createNativeQuery(
                "SELECT tenant_id FROM anomaly_reports WHERE anomaly_type = 'HIGH_FAILURE_RATE'")
                .getSingleResult());

        assertThat(tenant)
            .as("★tenantId 丢失 = 多租户隔离被破坏，别的租户会看到这条异常")
            .isEqualTo("t-new");
    }

    /**
     * ★保留期确实生效：超出保留期的<b>既有</b>记录必须被清掉。
     *
     * <p>与上面的顺序用例互补 —— 那条守「别删刚插的」，这条守「该删的要删」。
     * 没有这条，一个「根本不清理」的实现也能让顺序用例变绿。
     */
    @Test
    @DisplayName("超出保留期的既有记录必须被清理")
    void deletesRecordsBeyondRetention() {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            INSERT INTO anomaly_reports
              (anomaly_type, policy_id, metric_value, threshold, severity,
               description, detected_at, tenant_id)
            VALUES ('STALE','p-old',0.5,0.1,'INFO','旧记录',
                    NOW() - INTERVAL '40 day','t-old')
            """).executeUpdate());

        Mockito.when(analyticsService.detectAnomalies(Mockito.anyDouble(), Mockito.anyInt()))
            .thenReturn(List.of());

        scheduler.detectAndPersistAnomalies();

        Long stale = QuarkusTransaction.requiringNew().call(() ->
            ((Number) em.createNativeQuery(
                "SELECT count(*) FROM anomaly_reports WHERE anomaly_type = 'STALE'")
                .getSingleResult()).longValue());

        assertThat(stale)
            .as("★超出保留期的记录必须被清 —— 不清理会让表无限膨胀")
            .isZero();
    }

    /**
     * ★<b>上游返回 tenantId=null 时，任务不得整体失败</b>（issue #315）。
     *
     * <p>生产故障复现：僵尸检测对「从未跑过的版本」用 {@code MAX(ws.tenant_id)}
     * 回填租户，那些行上每个 {@code ws.*} 都是 NULL，于是 DTO 的 tenantId 是 null；
     * 插入撞 {@code NOT NULL} 约束 → <b>整个事务回滚</b> → 54 条一条都没落库。
     * 该表因此从未有过任何一行数据，而日志仍打印「检测到 54 个异常」。
     *
     * <p>★<b>为什么此前没测出来</b>：#311 补的用例全部用<b>自己构造的</b> DTO，
     * tenantId 恒非空，恰好整片避开了这条真实路径。
     * 「测试写得再仔细，只要输入是自己造的，就可能避开生产实际会遇到的形状」。
     *
     * <p>断言两件事：坏条目被跳过、<b>其余条目照常落库</b>。
     */
    @Test
    @DisplayName("上游返回 null 租户时跳过该条，其余必须照常落库")
    void nullTenantEntryIsSkippedWithoutFailingWholeRun() {
        Mockito.when(analyticsService.detectAnomalies(Mockito.anyDouble(), Mockito.anyInt()))
            .thenReturn(List.of(
                dto("GOOD_A", "WARNING", "t1"),
                dto("BAD_NULL", "WARNING", null),     // ← 生产里那 54 条的形状
                dto("GOOD_B", "WARNING", "t2")));

        // 不得抛出——抛出就意味着整批回滚
        scheduler.detectAndPersistAnomalies();

        List<?> types = QuarkusTransaction.requiringNew().call(() ->
            em.createNativeQuery("SELECT anomaly_type FROM anomaly_reports ORDER BY anomaly_type")
                .getResultList());

        assertThat(types.stream().map(String::valueOf).toList())
            .as("★坏条目跳过、好条目照常落库。整批为空 = 事务回滚了 = 故障复现；"
                + "含 BAD_NULL = NOT NULL 约束没起作用")
            .containsExactly("GOOD_A", "GOOD_B");
    }

    /**
     * ★全部条目都缺租户时也不得抛出（整批跳过，但任务算完成）。
     *
     * <p>这正是生产上那 54 条的实际形态。修复前它让整个小时的检测归零。
     */
    @Test
    @DisplayName("全部条目缺租户时任务仍须正常结束而非整批失败")
    void allNullTenantsDoNotBreakTheRun() {
        Mockito.when(analyticsService.detectAnomalies(Mockito.anyDouble(), Mockito.anyInt()))
            .thenReturn(List.of(
                dto("Z1", "INFO", null),
                dto("Z2", "INFO", null)));

        assertThatCode(() -> scheduler.detectAndPersistAnomalies())
            .as("★不得抛出——抛出会触发回滚，让同批次的好条目也一起没了")
            .doesNotThrowAnyException();

        Long n = QuarkusTransaction.requiringNew().call(() ->
            ((Number) em.createNativeQuery("SELECT count(*) FROM anomaly_reports")
                .getSingleResult()).longValue());
        assertThat(n).as("缺租户的条目不落库").isZero();
    }

    /**
     * ★只有 {@code CRITICAL} 才提交验证动作。
     *
     * <p>对每条异常都提交会让动作队列被 INFO/WARNING 淹没；
     * 一条都不提交则 CRITICAL 异常无人跟进。
     */
    @Test
    @DisplayName("只对 CRITICAL 异常提交验证动作")
    void submitsVerificationOnlyForCritical() {
        Mockito.when(analyticsService.detectAnomalies(Mockito.anyDouble(), Mockito.anyInt()))
            .thenReturn(List.of(
                dto("A", "CRITICAL", "t1"),
                dto("B", "WARNING", "t1"),
                dto("C", "INFO", "t1")));
        Mockito.when(workflowService.submitVerificationAction(Mockito.anyLong()))
            .thenReturn(Uni.createFrom().item(1L));

        scheduler.detectAndPersistAnomalies();

        // 三条都该落库
        Long total = QuarkusTransaction.requiringNew().call(() ->
            ((Number) em.createNativeQuery("SELECT count(*) FROM anomaly_reports")
                .getSingleResult()).longValue());
        assertThat(total).as("三条异常都应持久化").isEqualTo(3L);

        // 但只有 CRITICAL 那条提交了验证动作
        Mockito.verify(workflowService, Mockito.times(1))
            .submitVerificationAction(Mockito.anyLong());
    }

    /**
     * ★<b>失败必须重新抛出</b>以触发事务回滚。
     *
     * <p>吞掉异常会留下「清理已生效、插入未完成」的半截状态：
     * 旧记录没了、新记录也没有，而任务看起来是成功的。
     */
    @Test
    @DisplayName("检测失败必须抛出以触发回滚，不得留下半截数据")
    void failurePropagatesToTriggerRollback() {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            INSERT INTO anomaly_reports
              (anomaly_type, policy_id, metric_value, threshold, severity,
               description, detected_at, tenant_id)
            VALUES ('KEEP','p',0.5,0.1,'INFO','应当保留',NOW(),'t')
            """).executeUpdate());

        Mockito.when(analyticsService.detectAnomalies(Mockito.anyDouble(), Mockito.anyInt()))
            .thenThrow(new RuntimeException("检测炸了"));

        assertThatThrownBy(() -> scheduler.detectAndPersistAnomalies())
            .as("★必须抛出——吞掉异常就没有回滚，会留下半截状态")
            .isInstanceOf(RuntimeException.class);

        Long kept = QuarkusTransaction.requiringNew().call(() ->
            ((Number) em.createNativeQuery(
                "SELECT count(*) FROM anomaly_reports WHERE anomaly_type = 'KEEP'")
                .getSingleResult()).longValue());
        assertThat(kept)
            .as("★回滚后既有记录必须原样保留")
            .isEqualTo(1L);
    }

    private AnomalyReportDTO dto(String type, String severity, String tenantId) {
        return dtoAt(type, severity, tenantId, Instant.now());
    }

    private AnomalyReportDTO dtoAt(String type, String severity, String tenantId, Instant at) {
        AnomalyReportDTO d = new AnomalyReportDTO();
        d.anomalyType = type;
        d.policyId = "p";
        d.metricValue = 0.45;
        d.threshold = 0.10;
        d.severity = severity;
        d.description = "desc";
        d.recommendation = "rec";
        d.detectedAt = at;
        d.tenantId = tenantId;
        return d;
    }
}
