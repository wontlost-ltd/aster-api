package io.aster.policy.replay.batch;

import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReplayBatchScheduler#expireOldBatches()} 的<b>调度包装层</b>测试（issue #311）。
 *
 * <p>★<b>为什么单独测包装层</b>：{@code markExpired()} 本身有实体层测试，
 * 但「<b>选谁</b>过期」「过期时<b>清空什么</b>」这两个决定只存在于调度方法里，
 * 此前测试树对它零引用。这类逻辑一旦写错（比如把 RUNNING 也选进去），
 * 内层测试一个都不会红。
 *
 * <p>本用例锁住三条包装层独有的决策：
 * <ol>
 *   <li>只选<b>终态且已过期</b>的批次（COMPLETED/FAILED + expiresAt 已过）；</li>
 *   <li><b>不碰</b>未到期的、以及仍在跑的（PENDING/RUNNING）；</li>
 *   <li>过期时<b>清空聚合数字但保留元数据</b> —— 陈旧的数字比没有更危险。</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ReplayBatchExpiryIT {

    @Inject
    EntityManager em;

    @Inject
    ReplayBatchScheduler scheduler;

    @BeforeEach
    @AfterEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM replay_batch_item").executeUpdate();
            em.createNativeQuery("DELETE FROM replay_batch").executeUpdate();
        });
    }

    /**
     * ★核心：终态且已过期的批次必须转 EXPIRED，且聚合结果被清空。
     *
     * <p>「保留元数据、清空数字」是刻意的设计（源码注释：「数字有时效性，
     * 留着陈旧的比删掉更危险」）—— 故这里既断言状态，也断言 resultSummary 被清。
     */
    @Test
    @DisplayName("终态且已过期的批次转 EXPIRED，聚合数字被清空、元数据保留")
    void expiredTerminalBatchesAreClearedButMetadataKept() {
        UUID id = seed("t-exp", ReplayBatchStatus.COMPLETED,
            Instant.now().minusSeconds(3600), "{\"changed\":3}");

        scheduler.expireOldBatches();

        Object[] row = QuarkusTransaction.requiringNew().call(() ->
            (Object[]) em.createNativeQuery(
                    "SELECT status, result_summary, policy_id, planned_count"
                        + " FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult());

        assertThat(String.valueOf(row[0]))
            .as("★已过期的终态批次必须转 EXPIRED")
            .isEqualTo("EXPIRED");
        assertThat(row[1])
            .as("★聚合结果必须清空——陈旧的数字比没有更危险（源码注释所述的设计意图）")
            .isNull();
        assertThat(String.valueOf(row[2]))
            .as("★元数据必须保留：清空的只是有时效性的数字")
            .isEqualTo("p");
        assertThat(((Number) row[3]).intValue())
            .as("★planned_count 属元数据，不该被清")
            .isEqualTo(PLANNED);
    }

    /**
     * ★反向守卫一：<b>未到期</b>的终态批次不得被动。
     *
     * <p>没有这条，「无条件把所有 COMPLETED 转 EXPIRED」也能让上一条变绿，
     * 却会把用户刚跑完、还在看的结果当场清掉。
     */
    @Test
    @DisplayName("未到期的终态批次不得被清")
    void notYetExpiredBatchUntouched() {
        UUID id = seed("t-fresh", ReplayBatchStatus.COMPLETED,
            Instant.now().plusSeconds(3600), "{\"changed\":1}");

        scheduler.expireOldBatches();

        Object[] row = QuarkusTransaction.requiringNew().call(() ->
            (Object[]) em.createNativeQuery(
                    "SELECT status, result_summary FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult());

        assertThat(String.valueOf(row[0]))
            .as("★还没到期就不能动——否则用户刚跑完的结果会被当场清掉")
            .isEqualTo("COMPLETED");
        assertThat(row[1])
            .as("★未到期批次的聚合结果必须原样保留")
            .isNotNull();
    }

    /**
     * ★反向守卫二：<b>非终态</b>（PENDING）批次即便 expiresAt 已过也不得被清。
     *
     * <p>正在排队/运行的批次被转成 EXPIRED，等于把跑到一半的任务判死，
     * 且会释放它占着的并发槽位造成超发。
     */
    @Test
    @DisplayName("非终态批次即便已过 expiresAt 也不得被清")
    void nonTerminalBatchUntouchedEvenIfPastExpiry() {
        UUID id = seed("t-pending", ReplayBatchStatus.PENDING,
            Instant.now().minusSeconds(3600), null);

        scheduler.expireOldBatches();

        String status = QuarkusTransaction.requiringNew().call(() ->
            (String) em.createNativeQuery("SELECT status FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult());

        assertThat(status)
            .as("★非终态批次不得被过期清理——那等于把在跑的任务判死，"
                + "还会释放它占着的并发槽位造成超发")
            .isEqualTo("PENDING");
    }

    /** 计划条目数：故意用小数值，方便下面按 §1.1 补齐真实条目。 */
    private static final int PLANNED = 2;

    /**
     * 建一个批次（含真实条目）。
     *
     * <p>★<b>必须补齐条目</b>：{@code replay_batch_assert_totality()} 触发器强制
     * 「终态批次的真实条目数必须等于 plannedCount」（ADR 0034 §1.1）。
     * 只插 batch 行会被拒。这条不变量是本仓刻意设的护栏，
     * 测试要顺应它而不是绕过它 —— 关掉触发器来建数据会让别的用例失去保护。
     *
     * <p>{@code concurrency_slot} 按状态给：PENDING/RUNNING 必须持槽位、
     * 终态必须不持（{@code replay_batch_active_holds_slot_ck}）；
     * 租户名各用例不同，避开 {@code (tenant_id, concurrency_slot)} 唯一索引。
     */
    private UUID seed(String tenant, ReplayBatchStatus status, Instant expiresAt, String summary) {
        UUID id = UUID.randomUUID();
        boolean active = status == ReplayBatchStatus.PENDING || status == ReplayBatchStatus.RUNNING;
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                INSERT INTO replay_batch (id,tenant_id,user_id,policy_id,base_version_id,
                  target_version_id,window_kind,window_label,window_timezone,window_from,
                  window_to,planned_count,status,completed_count,failed_count,result_summary,
                  toolchain_id,expires_at,window_frozen_at,concurrency_slot)
                VALUES (?1,?2,'u','p','1','2','LAST_MONTH','m','UTC',
                  NOW() - INTERVAL '30 day', NOW(), ?7, ?3, ?8, 0, CAST(?4 AS jsonb),
                  'tc', ?5, NOW(), ?6)
                """)
                .setParameter(1, id)
                .setParameter(2, tenant)
                .setParameter(3, status.name())
                .setParameter(4, summary)
                .setParameter(5, expiresAt)
                .setParameter(6, active ? 0 : null)
                .setParameter(7, PLANNED)
                .setParameter(8, active ? 0 : PLANNED)
                .executeUpdate();

            // 补齐真实条目：终态批次必须条条有成败标记，否则触发器拒绝
            for (int i = 0; i < PLANNED; i++) {
                em.createNativeQuery("""
                    INSERT INTO replay_batch_item
                      (batch_id, execution_id, base_approved, success, target_approved)
                    VALUES (?1, ?2, true, ?3, ?3)
                    """)
                    .setParameter(1, id)
                    .setParameter(2, "e" + i)
                    .setParameter(3, active ? null : Boolean.TRUE)
                    .executeUpdate();
            }
        });
        return id;
    }
}
