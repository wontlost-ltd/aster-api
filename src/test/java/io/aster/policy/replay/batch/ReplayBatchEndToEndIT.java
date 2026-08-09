package io.aster.policy.replay.batch;

import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What-If 批次的<b>端到端</b>集成测试：造执行 → 跑完整批次 → 断言结果数字。
 *
 * <h2>为什么这个文件必须存在</h2>
 *
 * <p>七轮交叉审查（38→26→42→24→31→49）里，所有测试都在验证
 * <b>数据库约束与并发原语</b>——没有<b>任何一条</b>真正跑完
 * {@code claimNextPending → freezeWindow → runOneSegment → finishAtomically}
 * 这条主干。清点结果：
 *
 * <pre>
 * ReplayBatchPlannedCountTest:  读源码断言 6 处，真实调用 0 处
 * ReplayBatchLeaseAndSlotTest:  读源码断言 7 处，真实调用 0 处
 * </pre>
 *
 * <p>于是「批次能不能真的跑完并出正确数字」这件事，七轮里从未被验证过——
 * 靠的一直是我读代码的判断，而那七轮已经反复证明它不可靠。
 *
 * <p>本文件回答的正是这个问题：<b>造 3 条执行、跑完整批次、
 * 断言最终 COMPLETED 且 changed 数值正确。</b>
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ReplayBatchEndToEndIT {

    /** base 版本：金额 ≥ 100 才通过 → 三条输入里 e1(50) 拒、e2(150) 过、e3(200) 过。 */
    private static final String BASE_POLICY =
        "Module M.\n\nRule decide given amount as Int, produce Bool:\n  Return amount is at least 100.\n";

    /** target 版本：门槛抬到 180 → e2(150) 由通过变为拒绝，**恰好 1 条变化**。 */
    private static final String TARGET_POLICY =
        "Module M.\n\nRule decide given amount as Int, produce Bool:\n  Return amount is at least 180.\n";

    @Inject
    EntityManager em;

    @Inject
    ReplayBatchService service;

    /** ★只 mock 上游 HTTP（executions 属 aster-cloud），其余全部走真实实现。 */
    @InjectMock
    ExecutionWindowClient windowClient;

    private Long targetVersionRowId;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM replay_batch_item").executeUpdate();
            em.createNativeQuery("DELETE FROM replay_batch").executeUpdate();
            em.createNativeQuery("DELETE FROM policy_versions WHERE policy_id='p-e2e'")
                .executeUpdate();
        });
        targetVersionRowId = QuarkusTransaction.requiringNew().call(this::seedVersions);

        // 三条历史执行：基线决策来自 BASE_POLICY
        Mockito.when(windowClient.fetchWindow(
                Mockito.anyString(), Mockito.anyString(),
                Mockito.any(Instant.class), Mockito.any(Instant.class)))
            .thenReturn(List.of(
                exec("e1", 50, "denied"),     // base 拒 → target 也拒：未变化
                exec("e2", 150, "approved"),  // base 过 → target 拒：★变化
                exec("e3", 200, "approved")   // base 过 → target 也过：未变化
            ));
        Mockito.when(windowClient.fetchSegment(
                Mockito.anyString(), Mockito.anyString(),
                Mockito.any(Instant.class), Mockito.any(Instant.class),
                Mockito.any(), Mockito.anyInt()))
            .thenReturn(List.of(
                exec("e1", 50, "denied"),
                exec("e2", 150, "approved"),
                exec("e3", 200, "approved")
            ));
    }

    private ExecutionWindowClient.WindowedExecution exec(String id, int amount, String decision) {
        return new ExecutionWindowClient.WindowedExecution(
            id, Map.of("amount", amount), decision, true,
            "decide", "en-US", new JsonObject(), String.valueOf(targetVersionRowId));
    }

    private Long seedVersions() {
        em.createNativeQuery("""
            INSERT INTO policy_versions (policy_id, version, module_name, function_name,
                content, status, is_default, lock_version, created_at)
            VALUES ('p-e2e', 1, 'M', 'decide', ?1, 'APPROVED', false, 0, NOW())
            """).setParameter(1, BASE_POLICY).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO policy_versions (policy_id, version, module_name, function_name,
                content, status, is_default, lock_version, created_at)
            VALUES ('p-e2e', 2, 'M', 'decide', ?1, 'APPROVED', true, 0, NOW())
            """).setParameter(1, TARGET_POLICY).executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT id FROM policy_versions WHERE policy_id='p-e2e' AND version=2")
            .getSingleResult()).longValue();
    }

    private UUID seedPendingBatch() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            INSERT INTO replay_batch (id,tenant_id,user_id,policy_id,base_version_id,
              target_version_id,window_kind,window_label,window_timezone,window_from,
              window_to,planned_count,status,toolchain_id,expires_at,concurrency_slot)
            VALUES (?1,'t-e2e','u-e2e','p-e2e','1',?2,'LAST_MONTH','近一个月','UTC',
              NOW() - INTERVAL '30 day', NOW(), 0, 'PENDING', 'tc',
              NOW() + INTERVAL '30 day', 0)
            """)
            .setParameter(1, id)
            .setParameter(2, String.valueOf(targetVersionRowId))
            .executeUpdate());
        return id;
    }

    /**
     * ★<b>主干端到端</b>：领取 → 冻结 → 分段重跑 → 判定 → 落终态。
     *
     * <p>断言的不是「没报错」，而是<b>数字对不对</b>：
     * 三条执行里恰好 1 条决策翻转（e2: 150 在 base 通过、在 target 拒绝），
     * 故 {@code changed} 必须等于 1。
     */
    @Test
    void 完整批次必须跑完并给出正确的变化条数() {
        UUID batchId = seedPendingBatch();

        ReplayBatchService.Claim claim = service.claimNextPending();
        assertThat(claim).as("★必须能领到刚创建的 PENDING 批次").isNotNull();
        assertThat(claim.batchId()).isEqualTo(batchId);

        service.runBatch(batchId);

        Object[] row = QuarkusTransaction.requiringNew().call(() ->
            (Object[]) em.createNativeQuery("""
                SELECT status, planned_count, item_total, item_success,
                       result_summary::text
                  FROM replay_batch WHERE id = ?1
                """).setParameter(1, batchId).getSingleResult());

        assertThat(String.valueOf(row[0]))
            .as("★全部 3 条成功重跑 → 必须落 COMPLETED；"
                + "落 FAILED 说明主干有缺陷（七轮里从未验证过这条）")
            .isEqualTo("COMPLETED");
        assertThat(((Number) row[1]).intValue())
            .as("plannedCount 必须由冻结窗口派生")
            .isEqualTo(3);
        assertThat(((Number) row[2]).intValue()).as("条目总数").isEqualTo(3);
        assertThat(((Number) row[3]).intValue()).as("成功条目数").isEqualTo(3);

        // ★按 JSON 语义断言，不按字符串拼写：PG jsonb 会重排键并加空格，
        //   `contains("\"changed\":1")` 会因为一个空格而误报（我第一版就是这么错的）。
        io.vertx.core.json.JsonObject summary =
            new io.vertx.core.json.JsonObject(String.valueOf(row[4]));
        assertThat(summary.getInteger("changed"))
            .as("★changed 必须精确等于 1——e2(150) 在 base 通过、target 拒绝，"
                + "e1/e3 决策不变。数字错了说明比较逻辑有问题")
            .isEqualTo(1);
        assertThat(summary.getInteger("newlyRejected"))
            .as("★翻转方向必须是「新增拒绝」——门槛从 100 抬到 180")
            .isEqualTo(1);
        assertThat(summary.getInteger("newlyApproved"))
            .as("抬高门槛不应产生新增通过")
            .isZero();
        assertThat(summary.getInteger("totalSampled"))
            .as("样本必须是总体全量（§1.1）")
            .isEqualTo(3);
    }

    /**
     * ★任一条失败即整批拒答，且<b>不出任何数字</b>（ADR 0034 §1.1）。
     *
     * <p>这是本 ADR 的第一性约束在主干上的验证：让一条执行的输入无法在
     * target 上求值，整批必须 FAILED 且 {@code result_summary} 为空。
     */
    @Test
    void 任一条失败则整批拒答且不出数字() {
        // e2 的输入缺 amount 字段 → 在 target 上求值失败
        Mockito.when(windowClient.fetchSegment(
                Mockito.anyString(), Mockito.anyString(),
                Mockito.any(Instant.class), Mockito.any(Instant.class),
                Mockito.any(), Mockito.anyInt()))
            .thenReturn(List.of(
                exec("e1", 50, "denied"),
                new ExecutionWindowClient.WindowedExecution(
                    "e2", Map.of("wrong_field", 1), "approved", true,
                    "decide", "en-US", new JsonObject(), String.valueOf(targetVersionRowId)),
                exec("e3", 200, "approved")));

        UUID batchId = seedPendingBatch();
        service.claimNextPending();
        service.runBatch(batchId);

        Object[] row = QuarkusTransaction.requiringNew().call(() ->
            (Object[]) em.createNativeQuery(
                "SELECT status, result_summary::text, failure_reasons::text"
                    + " FROM replay_batch WHERE id = ?1")
                .setParameter(1, batchId).getSingleResult());

        assertThat(String.valueOf(row[0]))
            .as("★任一条失败即整批拒答")
            .isEqualTo("FAILED");
        assertThat(row[1])
            .as("★拒答态**不得**有聚合结果——那正是 Phase 4 的死因")
            .isNull();
        assertThat(String.valueOf(row[2]))
            .as("拒答要给失败类别，让用户知道为什么不给数字")
            .isNotEqualTo("null");
    }
}
