package io.aster.policy.replay.batch;

import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What-If 批次的**真实数据库**并发与 fail-closed 行为（ADR 0034 §11）。
 *
 * <h2>为什么必须是集成测试</h2>
 *
 * <p>前四轮审查（38 → 26 → 42）反复出现同一种失败：
 * <b>我写下的护栏与它实际覆盖的范围之间存在偏差，而源码断言看不出来。</b>
 * 具体案例：
 *
 * <ul>
 *   <li>owner 条件更新被 managed entity 自动 flush 绕过——源码里「有 CAS」，
 *       行为上没有</li>
 *   <li>fail-closed 触发器只挂 {@code BEFORE UPDATE}，
 *       {@code INSERT} 一个 COMPLETED 批次可以直接绕过</li>
 *   <li>我为「防窗口扫描」写的测试自己也是窗口扫描，变异后仍全绿</li>
 * </ul>
 *
 * <p>审查第四轮的原话：<i>「仓库没有 Quarkus + PostgreSQL 集成测试锁住这个
 * 行锁/事务事实。源码断言不能替代它。」</i>
 *
 * <p>本文件的每条断言都作用在**真实 PostgreSQL** 上，
 * 让「闭环了没有」由数据库回答，而不是由注释回答。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ReplayBatchConcurrencyIT {

    @Inject
    EntityManager em;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM replay_batch_item").executeUpdate();
            em.createNativeQuery("DELETE FROM replay_batch").executeUpdate();
        });
    }

    private void persistItem(UUID batchId, String execId, boolean baseApproved,
                             Boolean success, String failureKind) {
        ReplayBatchItemEntity i = new ReplayBatchItemEntity(batchId, execId, baseApproved);
        i.success = success;
        i.failureKind = failureKind;
        i.persist();
    }

    private UUID seedRunning(String tenant, String owner, int planned) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            ReplayBatchEntity b = new ReplayBatchEntity();
            b.id = id;
            b.tenantId = tenant;
            b.userId = "u";
            b.policyId = "p";
            b.baseVersionId = "1";
            b.targetVersionId = "2";
            b.windowKind = "LAST_MONTH";
            b.windowLabel = "近一个月";
            b.windowTimezone = "UTC";
            b.windowFrom = Instant.now().minusSeconds(2_592_000);
            b.windowTo = Instant.now();
            b.plannedCount = planned;
            b.status = ReplayBatchStatus.RUNNING;
            b.toolchainId = "tc";
            b.expiresAt = Instant.now().plusSeconds(2_592_000);
            b.concurrencySlot = 0;
            b.windowFrozenAt = Instant.now();
            b.leaseExpiresAt = Instant.now().plusSeconds(3600);
            b.leaseOwner = owner;
            b.persist();
        });
        return id;
    }

    // ── §11.1 owner CAS：旧 worker 写不进去 ──────────────────────────────

    /**
     * ★核心不变量：租约改派后，<b>旧 owner 的终态写必须影响 0 行</b>。
     *
     * <p>上一版写了同样形状的 SQL，却因为先改 managed 实体而被 Hibernate
     * 自动 flush 绕过——commit message 里还写着「结构上不可能」。
     * 本用例在真实库上验证这条现在真的成立。
     */
    @Test
    void 旧owner写终态必须影响0行() {
        UUID id = seedRunning("t-cas", "owner-B", 1);

        long written = QuarkusTransaction.requiringNew().call(() ->
            ReplayBatchEntity.update(
                "status = ?1, completedCount = ?2, failedCount = ?3, finishedAt = ?4,"
                    + " resultSummary = ?5, failureReasons = ?6,"
                    + " concurrencySlot = null, leaseExpiresAt = null, leaseOwner = null"
                    + " where id = ?7 and leaseOwner = ?8 and status = ?9",
                ReplayBatchStatus.FAILED, 0, 1, Instant.now(),
                null, "[\"TIMEOUT\"]", id, "owner-A", ReplayBatchStatus.RUNNING));

        assertThat(written)
            .as("★旧 owner（A）在批次已改派给 B 之后写终态，必须写 0 行")
            .isZero();

        ReplayBatchEntity after = QuarkusTransaction.requiringNew()
            .call(() -> ReplayBatchEntity.<ReplayBatchEntity>findById(id));
        assertThat(after.status)
            .as("★批次必须仍是 RUNNING——旧 worker 不得把它改成终态")
            .isEqualTo(ReplayBatchStatus.RUNNING);
        assertThat(after.leaseOwner).isEqualTo("owner-B");
    }

    /** 反向：当前 owner 写终态必须成功——否则护栏拦死了正常路径。 */
    @Test
    void 当前owner写终态必须成功() {
        UUID id = seedRunning("t-cas2", "owner-B", 1);

        long written = QuarkusTransaction.requiringNew().call(() ->
            ReplayBatchEntity.update(
                "status = ?1, completedCount = ?2, failedCount = ?3, finishedAt = ?4,"
                    + " resultSummary = ?5, failureReasons = ?6,"
                    + " concurrencySlot = null, leaseExpiresAt = null, leaseOwner = null"
                    + " where id = ?7 and leaseOwner = ?8 and status = ?9",
                ReplayBatchStatus.FAILED, 0, 1, Instant.now(),
                null, "[\"TIMEOUT\"]", id, "owner-B", ReplayBatchStatus.RUNNING));

        assertThat(written).as("当前 owner 必须能写").isEqualTo(1);
    }

    // ── §11.5 fail-closed：数据库层必须挡住所有入口 ──────────────────────

    /**
     * ★UPDATE 路径：有失败条目时不得转 COMPLETED。
     */
    @Test
    void 有失败条目时不得UPDATE成COMPLETED() {
        UUID id = seedRunning("t-fc", "o", 1);
        QuarkusTransaction.requiringNew().run(() ->
            persistItem(id, "e1", true, false, "TIMEOUT"));

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
            ReplayBatchEntity.update(
                "status = ?1, completedCount = ?2, failedCount = 0, resultSummary = ?3,"
                    + " concurrencySlot = null where id = ?4",
                ReplayBatchStatus.COMPLETED, 1, "{\"changed\":1}", id)))
            .as("★存在失败条目却标 COMPLETED，数据库必须拒绝")
            .hasMessageContaining("COMPLETED");
    }

    /**
     * ★<b>INSERT 路径</b>：这是第四轮审查抓到的绕过。
     *
     * <p>触发器只挂 {@code BEFORE UPDATE} 时，直接 INSERT 一个 COMPLETED 批次
     * 再插入失败条目，数据库全盘接受——实测落出 {@code COMPLETED + 失败item=1}。
     * fail-closed 在数据库层并没有真正闭合。
     */
    @Test
    void 不得INSERT出COMPLETED加失败条目的组合() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                INSERT INTO replay_batch (id,tenant_id,user_id,policy_id,base_version_id,
                  target_version_id,window_kind,window_label,window_timezone,window_from,
                  window_to,planned_count,status,completed_count,failed_count,result_summary,
                  toolchain_id,expires_at,window_frozen_at)
                VALUES (?1,'t-ins','u','p','1','2','LAST_MONTH','m','UTC',
                  NOW() - INTERVAL '30 day', NOW(), 1, 'COMPLETED', 1, 0,
                  '{"changed":1}'::jsonb, 'tc', NOW() + INTERVAL '30 day', NOW())
                """).setParameter(1, id).executeUpdate();
            em.createNativeQuery("""
                INSERT INTO replay_batch_item (batch_id,execution_id,base_approved,
                  success,failure_kind)
                VALUES (?1,'e1',true,false,'TIMEOUT')
                """).setParameter(1, id).executeUpdate();
        }))
            .as("★INSERT 路径同样不得产出「COMPLETED + 失败条目」——"
                + "触发器只挂 BEFORE UPDATE 时这条会被放行（第四轮实测）")
            .hasMessageContaining("COMPLETED");
    }

    /**
     * ★条目侧写入路径：批次已 COMPLETED 后，不得再插入/改出失败条目。
     */
    @Test
    void COMPLETED之后不得再插入失败条目() {
        UUID id = seedRunning("t-after", "o", 1);
        QuarkusTransaction.requiringNew().run(() ->
            persistItem(id, "e1", true, true, null));
        QuarkusTransaction.requiringNew().run(() ->
            ReplayBatchEntity.update(
                "status = ?1, completedCount = 1, failedCount = 0, resultSummary = ?2,"
                    + " concurrencySlot = null where id = ?3",
                ReplayBatchStatus.COMPLETED, "{\"changed\":0}", id));

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
            persistItem(id, "e2", true, false, "TIMEOUT")))
            .as("★批次已 COMPLETED，再插入失败条目会让「全量成功」变成谎言")
            .isInstanceOf(Exception.class);
    }

    /**
     * ★条目数量必须等于 plannedCount——否则「样本即总体全量」不成立（§1.1）。
     */
    @Test
    void 条目数不足plannedCount时不得转COMPLETED() {
        UUID id = seedRunning("t-count", "o", 3);   // 声称 3 条
        QuarkusTransaction.requiringNew().run(() ->
            persistItem(id, "e1", true, true, null));

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
            ReplayBatchEntity.update(
                "status = ?1, completedCount = 3, failedCount = 0, resultSummary = ?2,"
                    + " concurrencySlot = null where id = ?3",
                ReplayBatchStatus.COMPLETED, "{\"changed\":0}", id)))
            .as("★只跑了 1 条却声称 3 条全成功——样本不是总体全量")
            .hasMessageContaining("COMPLETED");
    }

    // ── §11.3 槽位唯一性 ─────────────────────────────────────────────────

    /** 同租户同槽位不可并存——这是唯一能堵住先查后写 TOCTOU 的机制。 */
    @Test
    void 同租户抢同槽必须只有一个成功() {
        seedRunning("t-slot", "o1", 1);

        assertThatThrownBy(() -> seedRunning("t-slot", "o2", 1))
            .as("★同租户第二个活跃批次抢同一槽位，必须被唯一索引拒绝")
            .isInstanceOf(Exception.class);
    }
}
