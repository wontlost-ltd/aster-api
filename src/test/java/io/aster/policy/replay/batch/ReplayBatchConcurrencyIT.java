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

    @Inject
    ReplayBatchService service;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM replay_batch_item").executeUpdate();
            em.createNativeQuery("DELETE FROM replay_batch").executeUpdate();
        });
    }

    /**
     * 断言批次**没有**停在 COMPLETED。
     *
     * <p>★为什么不只断言「抛异常」：延迟约束在**提交时**触发，
     * JTA 会把它包成 {@code RollbackException: Could not commit transaction}，
     * 原始的 PostgreSQL 消息不在最外层——按 {@code hasMessageContaining("COMPLETED")}
     * 断言会失败，而按 {@code isInstanceOf(Exception.class)} 又太宽
     * （任何异常都能满足，包括测试自身写错）。
     *
     * <p>所以在「拒绝」之外**再查一次落库状态**：违规状态没落库才是真正要保的东西。
     */
    private void assertNotCompleted(UUID id) {
        String st = QuarkusTransaction.requiringNew().call(() ->
            String.valueOf(em.createNativeQuery(
                "SELECT status FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult()));
        assertThat(st)
            .as("★违规事务必须整体回滚——批次不得停在 COMPLETED")
            .isNotEqualTo("COMPLETED");
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
            .isInstanceOf(Exception.class);
        assertNotCompleted(id);
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
            .isInstanceOf(Exception.class);
        // INSERT 被回滚后该行根本不存在，比「不是 COMPLETED」更强
        Long cnt = QuarkusTransaction.requiringNew().call(() ->
            ((Number) em.createNativeQuery(
                "SELECT count(*) FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult()).longValue());
        assertThat(cnt).as("★违规 INSERT 必须整体回滚，该批次不得存在").isZero();
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
            .isInstanceOf(Exception.class);
        assertNotCompleted(id);
    }

    // ── §10.1 契约形状：failureReasons 必须是数组 ────────────────────────

    /**
     * ★历史行归一化：{@code {}} 必须被转成 {@code []}。
     *
     * <p>V6.20.1 最初把历史活跃行处置成 FAILED 时写的是空**对象**，
     * 而 §10.1 之后的 API 契约是 {@code failureKinds: [类别]} 数组。
     * cloud 侧按数组读会拿到对象——<b>迁移能过 ≠ 历史数据符合契约</b>。
     *
     * <p>本用例直接构造一个对象形状的行，验证 V6.20.4 的归一化确实生效。
     * 注意它模拟的是「已部署过旧版 V6.20.1 的环境」——
     * 现在 V6.20.1 本身已改为直接写 {@code []}，但归一化仍需保留。
     */
    @Test
    void 历史对象形状的failureReasons必须被归一化成数组() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            INSERT INTO replay_batch (id,tenant_id,user_id,policy_id,base_version_id,
              target_version_id,window_kind,window_label,window_timezone,window_from,
              window_to,planned_count,status,failure_reasons,toolchain_id,expires_at)
            VALUES (?1,'t-norm','u','p','1','2','LAST_MONTH','m','UTC',
              NOW() - INTERVAL '30 day', NOW(), 0, 'FAILED',
              '{}'::jsonb, 'tc', NOW() + INTERVAL '30 day')
            """).setParameter(1, id).executeUpdate());

        // 重放 V6.20.4 的归一化语句（迁移已在库上跑过，这里验证语义本身）
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            UPDATE replay_batch SET failure_reasons = '[]'::jsonb
             WHERE failure_reasons IS NOT NULL
               AND jsonb_typeof(failure_reasons) = 'object'
            """).executeUpdate());

        String shape = QuarkusTransaction.requiringNew().call(() ->
            (String) em.createNativeQuery(
                "SELECT jsonb_typeof(failure_reasons) FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult());

        assertThat(shape)
            .as("★归一化后必须是 array——cloud 侧按数组读，拿到对象会渲染出乱码")
            .isEqualTo("array");
    }

    /**
     * ★<b>归一化不得丢弃历史失败信息</b>——这是我写出过的最严重的一条。
     *
     * <p>V6.20.4 最初写的是「所有 object 一律转 {@code []}」，
     * 实测把 <code>{"INPUT_INCOMPATIBLE":170,"TIMEOUT":30}</code> 直接清成 {@code []}。
     * 我给它起名叫「归一化」，WHERE 条件却匹配了<b>任意</b>对象——
     * 那不是改形状，是**删数据**，而且是生产环境的历史失败分布。
     *
     * <p>正确做法：非空 object 取 key 列表转数组（保留类别、按 §10.1 有意丢掉条数），
     * 只有真正的空 {@code {}} 才变成 {@code []}。
     */
    @Test
    void 归一化不得清空非空的历史失败分布() {
        UUID withData = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            insertFailedWithReasons(withData, "loss-a",
                "{\"INPUT_INCOMPATIBLE\":170,\"TIMEOUT\":30}");
            insertFailedWithReasons(empty, "loss-b", "{}");
        });

        // 重放 V6.20.4 的两条归一化语句
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                UPDATE replay_batch SET failure_reasons = '[]'::jsonb
                 WHERE failure_reasons IS NOT NULL
                   AND jsonb_typeof(failure_reasons) = 'object'
                   AND failure_reasons = '{}'::jsonb
                """).executeUpdate();
            em.createNativeQuery("""
                UPDATE replay_batch SET failure_reasons = (
                     SELECT COALESCE(jsonb_agg(k ORDER BY k), '[]'::jsonb)
                       FROM jsonb_object_keys(failure_reasons) AS k)
                 WHERE failure_reasons IS NOT NULL
                   AND jsonb_typeof(failure_reasons) = 'object'
                   AND failure_reasons <> '{}'::jsonb
                """).executeUpdate();
        });

        String kept = readReasons(withData);
        assertThat(kept)
            .as("★非空失败分布必须**保留类别**，不得被清成空数组——那是删数据不是归一化")
            .contains("INPUT_INCOMPATIBLE")
            .contains("TIMEOUT");
        assertThat(readReasons(empty))
            .as("真正的空对象才转空数组")
            .isEqualTo("[]");
    }

    private void insertFailedWithReasons(UUID id, String tenant, String reasonsJson) {
        em.createNativeQuery("""
            INSERT INTO replay_batch (id,tenant_id,user_id,policy_id,base_version_id,
              target_version_id,window_kind,window_label,window_timezone,window_from,
              window_to,planned_count,status,failure_reasons,toolchain_id,expires_at)
            VALUES (?1,?2,'u','p','1','2','LAST_MONTH','m','UTC',
              NOW() - INTERVAL '30 day', NOW(), 0, 'FAILED', CAST(?3 AS jsonb),
              'tc', NOW() + INTERVAL '30 day')
            """).setParameter(1, id).setParameter(2, tenant)
            .setParameter(3, reasonsJson).executeUpdate();
    }

    private String readReasons(UUID id) {
        return QuarkusTransaction.requiringNew().call(() ->
            String.valueOf(em.createNativeQuery(
                "SELECT failure_reasons::text FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult()));
    }

    /**
     * ★迁移**自己**写入的处置行就必须是数组，不能依赖后续补丁纠正。
     *
     * <p>V6.20.1 曾写 {@code '{}'}，靠 V6.20.4 再转成 {@code '[]'}——
     * 中间存在一段「数据违反契约」的窗口。现已改为直接写数组。
     * 本用例扫迁移文件锁住这一点：**不得再有迁移写出对象形状的 failure_reasons**。
     */
    @Test
    void 迁移不得写出对象形状的failureReasons() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("src/main/resources/db/migration");
        try (var files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path f : files.filter(x -> x.getFileName().toString()
                    .startsWith("V6.20.")).toList()) {
                String sql = java.nio.file.Files.readString(f);
                // ★只看 SET 子句（写入），不看 WHERE 子句（比较）：
                //   V6.20.4 的归一化必须用 `failure_reasons = '{}'::jsonb` 做
                //   **条件判断**来区分空/非空对象——那是正当的读，
                //   按裸串断言会把它误判成写入（我第一版就这么错了）。
                for (String line : sql.lines().toList()) {
                    String t = line.trim();
                    if (t.startsWith("--") || t.toUpperCase(java.util.Locale.ROOT)
                            .startsWith("AND") || t.toUpperCase(java.util.Locale.ROOT)
                            .startsWith("WHERE")) {
                        continue;   // 注释与条件子句不算写入
                    }
                    assertThat(t.replaceAll("\\s+", " "))
                        .as("★%s 不得把 failure_reasons **写成**对象——契约是数组（§10.1）",
                            f.getFileName())
                        .doesNotContain("SET failure_reasons = '{}'::jsonb")
                        .doesNotContain("failure_reasons = '{}'::jsonb,");
                }
            }
        }
    }

    // ── §12.3 DEFERRABLE 约束触发器：五条绕过全部封闭 ────────────────────

    /**
     * ★{@code DELETE} item 后标 COMPLETED——V6.20.4 的即时触发器放行过这条。
     *
     * <p>现在由 {@code CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED}
     * 在**提交时**校验汇总列，DELETE 与状态变更在同一事务里都会被回滚。
     */
    @Test
    void 删除条目后标COMPLETED必须在提交时被拒() {
        UUID id = seedRunning("t-del", "o", 1);
        QuarkusTransaction.requiringNew().run(() -> persistItem(id, "e1", true, true, null));

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM replay_batch_item WHERE batch_id = ?1")
                .setParameter(1, id).executeUpdate();
            em.createNativeQuery("""
                UPDATE replay_batch SET status='COMPLETED', completed_count=1, failed_count=0,
                       result_summary='{"changed":0}'::jsonb, concurrency_slot=NULL,
                       lease_expires_at=NULL, lease_owner=NULL
                 WHERE id = ?1
                """).setParameter(1, id).executeUpdate();
        }))
            .as("★删掉条目再标 COMPLETED，样本不是总体全量，必须在提交时被拒")
            .isInstanceOf(Exception.class);
    }

    /**
     * ★把 item 改挂到另一个父——审查发现的**第四条写入路径**。
     *
     * <p>V6.20.4 的 item 触发器只查 {@code NEW.batch_id}，
     * 于是旧父静默失去一条 item（实测 {@code planned=1 item数=0}）。
     * 汇总维护函数现在对 {@code OLD} 与 {@code NEW} 双向增减。
     */
    @Test
    void 把条目改挂到别的父必须被拒() {
        UUID oldParent = seedRunning("t-rp1", "o1", 1);
        QuarkusTransaction.requiringNew().run(() -> persistItem(oldParent, "e1", true, true, null));
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            UPDATE replay_batch SET status='COMPLETED', completed_count=1, failed_count=0,
                   result_summary='{"changed":0}'::jsonb, concurrency_slot=NULL,
                   lease_expires_at=NULL, lease_owner=NULL
             WHERE id = ?1
            """).setParameter(1, oldParent).executeUpdate());

        UUID newParent = seedRunning("t-rp2", "o2", 1);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery("UPDATE replay_batch_item SET batch_id = ?1 WHERE batch_id = ?2")
                .setParameter(1, newParent).setParameter(2, oldParent).executeUpdate()))
            .as("★改挂父会让旧父失去条目——只看 NEW.batch_id 时这条被放行过")
            .isInstanceOf(Exception.class);
    }

    /** ★COMPLETED 后追加 success=true 条目：会让 item_total > planned_count。 */
    @Test
    void COMPLETED后追加成功条目必须被拒() {
        UUID id = seedRunning("t-add", "o", 1);
        QuarkusTransaction.requiringNew().run(() -> persistItem(id, "e1", true, true, null));
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            UPDATE replay_batch SET status='COMPLETED', completed_count=1, failed_count=0,
                   result_summary='{"changed":0}'::jsonb, concurrency_slot=NULL,
                   lease_expires_at=NULL, lease_owner=NULL
             WHERE id = ?1
            """).setParameter(1, id).executeUpdate());

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
            persistItem(id, "e2", true, true, null)))
            .as("★追加条目让 item_total 超过 planned_count，同样违反「样本即总体全量」")
            .isInstanceOf(Exception.class);
    }

    /** ★TRUNCATE 是语句级事件，行级触发器收不到——必须单独挡。 */
    @Test
    void TRUNCATE条目表必须被拒() {
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery("TRUNCATE replay_batch_item").executeUpdate()))
            .as("★TRUNCATE 会让所有批次的计数与实际脱节")
            .isInstanceOf(Exception.class);
    }

    /** 正例：合法的全量成功仍必须放行——护栏要能区分，不能一律拒。 */
    @Test
    void 合法的全量成功必须放行() {
        UUID id = seedRunning("t-ok", "o", 2);
        QuarkusTransaction.requiringNew().run(() -> {
            persistItem(id, "e1", true, true, null);
            persistItem(id, "e2", false, true, null);
        });
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            UPDATE replay_batch SET status='COMPLETED', completed_count=2, failed_count=0,
                   result_summary='{"changed":1}'::jsonb, concurrency_slot=NULL,
                   lease_expires_at=NULL, lease_owner=NULL
             WHERE id = ?1
            """).setParameter(1, id).executeUpdate());

        String st = QuarkusTransaction.requiringNew().call(() ->
            String.valueOf(em.createNativeQuery(
                "SELECT status || ' ' || item_total || '/' || planned_count"
                    + " FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult()));
        assertThat(st).as("全量成功必须能落 COMPLETED").isEqualTo("COMPLETED 2/2");
    }

    /**
     * ★<b>汇总列不是真值</b>——第六轮审查抓到的致命绕过。
     *
     * <p>{@code item_total}/{@code item_success} 是普通可写列，
     * 应用 SQL、运维脚本、ORM 都能直接写。上一版的约束触发器只比较这三个列
     * 彼此，从不读真实 item——于是「零 item + 手写 total=success=planned=5」
     * 就能提交 COMPLETED（实测：真实 item=0 而声称 5/5，零错误）。
     *
     * <p>审查原话：<i>「DEFERRABLE 解决的是『何时检查』，
     * 没有解决『检查的数据是否权威』。」</i>
     * 现在 COMPLETED 时回表数一遍真实条目，汇总列只作快路径。
     */
    @Test
    void 伪造汇总列不得让零条目批次标COMPLETED() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery("""
                INSERT INTO replay_batch (id,tenant_id,user_id,policy_id,base_version_id,
                  target_version_id,window_kind,window_label,window_timezone,window_from,
                  window_to,planned_count,status,completed_count,failed_count,result_summary,
                  toolchain_id,expires_at,window_frozen_at,item_total,item_success)
                VALUES (?1,'t-forge','u','p','1','2','LAST_MONTH','m','UTC',
                  NOW() - INTERVAL '30 day', NOW(), 5, 'COMPLETED', 5, 0,
                  '{"changed":3}'::jsonb, 'tc', NOW() + INTERVAL '30 day', NOW(), 5, 5)
                """).setParameter(1, id).executeUpdate()))
            .as("★零条目却手写汇总列声称全量成功——必须被拒，"
                + "否则「跑了 0 条」可以伪装成「5 条全成功」（§1.1）")
            .isInstanceOf(Exception.class);

        Long cnt = QuarkusTransaction.requiringNew().call(() ->
            ((Number) em.createNativeQuery(
                "SELECT count(*) FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult()).longValue());
        assertThat(cnt).as("★违规 INSERT 必须整体回滚").isZero();
    }

    // ── §12 真实并发：两个 worker 同时推进同一批次 ───────────────────────

    /**
     * ★<b>真实并发</b>：两个 worker 各持不同 owner，同时对同一批次做段首租约续期。
     *
     * <p>前六轮的 IT 都只验证「单线程顺序执行下的规则」，
     * 审查因此指出「没有真实并发 {@code runOneSegment} 覆盖」。
     * 本用例开两条线程同时打段首那条 owner CAS：
     * <b>只有当前 owner 能续租成功，另一个必须拿到 0 行并让位</b>。
     *
     * <p>这条不变量若破了，两个 worker 会同时重跑同一批条目——
     * 结果互相覆盖，而「谁的结果」不可知。
     */
    @Test
    void 两个worker并发推进同一批次只有当前owner能续租() throws Exception {
        UUID id = seedRunning("t-race", "owner-current", 4);
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 1; i <= 4; i++) {
                persistItem(id, "e" + i, true, null, null);
            }
        });

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start =
            new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger renewedByCurrent =
            new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger renewedByStale =
            new java.util.concurrent.atomic.AtomicInteger();

        try {
            java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                // 一半用当前 owner，一半用已失效的旧 owner
                String owner = (i % 2 == 0) ? "owner-current" : "owner-stale";
                fs.add(pool.submit(() -> {
                    start.await();
                    long n = QuarkusTransaction.requiringNew().call(() ->
                        ReplayBatchEntity.update(
                            "leaseExpiresAt = ?1 where id = ?2 and leaseOwner = ?3 and status = ?4",
                            Instant.now().plusSeconds(3600), id, owner,
                            ReplayBatchStatus.RUNNING));
                    if (n > 0) {
                        ("owner-current".equals(owner) ? renewedByCurrent : renewedByStale)
                            .incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : fs) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(renewedByStale.get())
            .as("★失效 owner 在任何并发交错下都不得续租成功——"
                + "否则两个 worker 会同时重跑同一批条目并互相覆盖")
            .isZero();
        assertThat(renewedByCurrent.get())
            .as("当前 owner 必须能续租（护栏要能区分，不是一律拒）")
            .isPositive();
    }

    /**
     * ★并发插入同一批次的条目：汇总列不得因丢失更新而漂移。
     *
     * <p>{@code item_total = item_total + 1} 是读-改-写，
     * 若无行锁串行化就会丢失更新，汇总列与真实条目脱节——
     * 而 §12 的整个 fail-closed 建立在汇总列可信之上（回表校验是第二道）。
     */
    @Test
    void 并发插入条目时汇总列不得丢失更新() throws Exception {
        UUID id = seedRunning("t-cnt", "o", 16);
        int n = 16;
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start =
            new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                final String execId = String.format("e%02d", i);
                fs.add(pool.submit(() -> {
                    start.await();
                    QuarkusTransaction.requiringNew().run(() ->
                        persistItem(id, execId, true, true, null));
                    return null;
                }));
            }
            start.countDown();
            for (var f : fs) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        String counts = QuarkusTransaction.requiringNew().call(() ->
            String.valueOf(em.createNativeQuery(
                "SELECT item_total || '/' || item_success FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult()));
        assertThat(counts)
            .as("★%d 条并发插入后汇总列必须精确等于 %d/%d——丢失更新会让它偏低", n, n, n)
            .isEqualTo(n + "/" + n);
    }

    /**
     * ★<b>真正并发调用 {@link ReplayBatchService#runOneSegment}</b>，
     * 而不是复制它的第一条 SQL。
     *
     * <p>上一版的「并发 IT」只手写了段首那条 owner CAS——审查因此指出
     * 「没有并发调用 runOneSegment，整体并发覆盖仍未完成」。属实：
     * 手写 SQL 测的是我对那条语句的理解，不是方法的真实行为
     * （方法里还有冻结集合读取、上游拉取、逐条落标记等）。
     *
     * <p>本用例开 6 条线程同时调真实方法：
     * <ul>
     *   <li>持当前 owner 的线程可以推进（返回 &gt;= 0）</li>
     *   <li>持失效 owner 的线程<b>必须</b>拿到 -1 让位，且不得写入任何条目标记</li>
     *   <li>无论交错如何，同一条目<b>不得被跑两次</b>——
     *       否则两个 worker 的结果互相覆盖，「谁的结果」不可知</li>
     * </ul>
     */
    @Test
    void 并发调用runOneSegment时失效owner必须让位且条目不重复处理() throws Exception {
        UUID id = seedRunning("t-seg", "owner-live", 3);
        QuarkusTransaction.requiringNew().run(() -> {
            persistItem(id, "s1", true, null, null);
            persistItem(id, "s2", true, null, null);
            persistItem(id, "s3", true, null, null);
        });

        int threads = 6;
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start =
            new java.util.concurrent.CountDownLatch(1);
        java.util.List<Integer> staleResults =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        try {
            java.util.List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String owner = (i % 2 == 0) ? "owner-live" : "owner-stale";
                fs.add(pool.submit(() -> {
                    start.await();
                    // ★不得把异常当成「让位」：那会让「去掉 owner 条件」这种
                    //   破坏护栏的变异也表现为 -1（实测过——变异存活）。
                    //   异常单独记录，失效 owner 的**返回值**必须真的是 -1。
                    int r;
                    try {
                        r = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                            .call(() -> service.runOneSegment(id, owner));
                    } catch (RuntimeException ex) {
                        r = Integer.MIN_VALUE;   // 哨兵：抛异常 ≠ 让位
                    }
                    if ("owner-stale".equals(owner)) {
                        staleResults.add(r);
                    }
                    return r;
                }));
            }
            start.countDown();
            for (var f : fs) {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(staleResults)
            .as("★失效 owner 的每次调用都必须**返回 -1** 让位——"
                + "抛异常（MIN_VALUE）或推进（>=0）都不算，"
                + "前者说明护栏没生效只是恰好炸了")
            .isNotEmpty()
            .allMatch(r -> r == -1);

        // ★同一条目不得被处理两次：success 已置位的条目数不得超过 plannedCount
        Long processed = QuarkusTransaction.requiringNew().call(() ->
            ((Number) em.createNativeQuery(
                "SELECT count(*) FROM replay_batch_item"
                    + " WHERE batch_id = ?1 AND success IS NOT NULL")
                .setParameter(1, id).getSingleResult()).longValue());
        assertThat(processed)
            .as("★已处理条目数不得超过计划数——超出说明同一条目被多个 worker 重复跑")
            .isLessThanOrEqualTo(3L);

        // 租约仍应属于当前 owner（失效 owner 不得改写）
        String owner = QuarkusTransaction.requiringNew().call(() ->
            String.valueOf(em.createNativeQuery(
                "SELECT lease_owner FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult()));
        assertThat(owner)
            .as("★失效 owner 不得夺取租约")
            .isEqualTo("owner-live");
    }

    /**
     * ★<b>真实 PostgreSQL 上重放 V6.20.5 的关键顺序</b>（第八轮 P0-1）。
     *
     * <p>审查要求「新增真实 PostgreSQL 连续 .0→.5 自动测试，种入合法 pre-.3
     * COMPLETED + item 历史行并断言升级成功、违规历史降级结果正确」。
     * Flyway 已在测试容器启动时跑完 .0→.5（空库），故本用例补的是
     * <b>有历史数据时的语义</b>：种入一条「条目数 ≠ planned_count」的
     * COMPLETED 行，重放回填与降级两步，断言计数被正确重算。
     *
     * <p>修复前的现象：回填后 {@code item_total=0}，而 item 表里明明有 1 行
     * ——旧 V6.20.4 触发器拒绝了那条 UPDATE，使回填静默失效。
     */
    @Test
    void 历史违规COMPLETED行必须被据实重算并降级() {
        UUID id = UUID.randomUUID();

        // ★禁用与恢复必须各自独立提交：CONSTRAINT TRIGGER 在**提交时**触发，
        //   若与 seed 同事务，禁用尚未生效就已到 COMMIT（实测 RollbackException）。
        // ★禁用必须配 finally 恢复：测试中途失败若留下禁用状态，
        //   后续所有用例的护栏都失效——一个坏用例会让整个套件变成假绿。
        try {
        runSql("ALTER TABLE replay_batch DISABLE TRIGGER replay_batch_totality_trg");
        runSql("ALTER TABLE replay_batch_item"
            + " DISABLE TRIGGER replay_batch_item_parent_totality_trg");
        // ★连汇总维护触发器也要禁：否则插 item 时它会把 item_total 从 0 加到 1，
        //   汇总列不再停留在「.5 之前的形态」，回填就成了 1→1 的空转——
        //   实测：删掉回填语句后测试仍绿（变异存活），因为值是维护触发器给的。
        runSql("ALTER TABLE replay_batch_item DISABLE TRIGGER replay_batch_item_counts_trg");

        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            INSERT INTO replay_batch (id,tenant_id,user_id,policy_id,base_version_id,
              target_version_id,window_kind,window_label,window_timezone,window_from,
              window_to,planned_count,status,completed_count,failed_count,result_summary,
              toolchain_id,expires_at,window_frozen_at,item_total,item_success)
            VALUES (?1,'t-legacy','u','p','1','2','LAST_MONTH','m','UTC',
              NOW() - INTERVAL '30 day', NOW(), 2, 'COMPLETED', 2, 0,
              '{"changed":0}'::jsonb, 'tc', NOW() + INTERVAL '30 day', NOW(), 0, 0)
            """).setParameter(1, id).executeUpdate());
        // 只有 1 条 item，而 planned_count=2 —— 这就是「历史违规行」
        QuarkusTransaction.requiringNew().run(() -> persistItem(id, "L1", true, true, null));

        } finally {
            restoreGuards();
        }

        // ★从**真实迁移文件**里抽出回填与降级语句执行，而不是在测试里复制一份 SQL。
        //   复制版只能验证我的转写，验证不了迁移本身——
        //   实测：删掉真实 V6.20.5 的回填语句后，复制版测试**仍然全绿**。
        // ★两条必须在**同一事务**里执行——迁移本身就是单事务。
        //   分开提交的话，回填提交时该行仍违反延迟约束（还没降级），
        //   会在 COMMIT 被拒（实测 RollbackException）。
        java.util.List<String> stmts = extractBackfillAndDowngrade();
        QuarkusTransaction.requiringNew().run(() -> {
            for (String stmt : stmts) {
                em.createNativeQuery(stmt).executeUpdate();
            }
        });

        Object[] row = QuarkusTransaction.requiringNew().call(() ->
            (Object[]) em.createNativeQuery(
                "SELECT status, item_total, item_success FROM replay_batch WHERE id = ?1")
                .setParameter(1, id).getSingleResult());

        assertThat(((Number) row[1]).intValue())
            .as("★回填必须真的生效——修复前这里是 0，而 item 表有 1 行"
                + "（旧 V6.20.4 触发器拒绝了该 UPDATE，使回填静默失效）")
            .isEqualTo(1);
        assertThat(((Number) row[2]).intValue()).isEqualTo(1);
        assertThat(String.valueOf(row[0]))
            .as("★条目数(1) ≠ planned(2) 的历史 COMPLETED 行必须据实降级")
            .isEqualTo("FAILED");
    }

    /**
     * 从真实的 V6.20.5 迁移文件里抽出「回填」与「历史降级」两条语句。
     *
     * <p>★<b>必须读文件，不能在测试里复制 SQL</b>：复制版验证的是我的转写，
     * 迁移本身坏掉时它照样绿（第九轮审查实测确认）。
     */
    private static java.util.List<String> extractBackfillAndDowngrade() {
        String sql;
        try {
            sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/"
                    + "V6.20.5__replay_batch_deferrable_fail_closed.sql"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读不到 V6.20.5 迁移文件", e);
        }
        // ★按**用途**识别，不按前缀：触发器函数体里也有 `UPDATE replay_batch`
        //   （汇总维护），只按前缀会多抽一条（实测抽到 3 条）。
        //   回填 = 带 `FROM (SELECT ... replay_batch_item` 的那条；
        //   降级 = SET status='FAILED' 的那条。
        String backfill = null;
        String downgrade = null;
        for (String raw : sql.split(";")) {
            String stmt = raw.lines()
                .filter(l -> !l.trim().startsWith("--"))
                .reduce("", (x, y) -> x + "\n" + y).trim();
            if (!stmt.startsWith("UPDATE replay_batch")) {
                continue;
            }
            if (stmt.contains("FROM (SELECT") && stmt.contains("replay_batch_item")) {
                backfill = stmt;
            } else if (stmt.contains("status          = 'FAILED'")
                || stmt.contains("SET status='FAILED'")
                || stmt.contains("SET status = 'FAILED'")) {
                downgrade = stmt;
            }
        }
        assertThat(backfill)
            .as("★必须从迁移文件里抽到**回填**语句——抽不到说明迁移结构变了")
            .isNotNull();
        assertThat(downgrade)
            .as("★必须从迁移文件里抽到**历史降级**语句")
            .isNotNull();
        return java.util.List.of(backfill, downgrade);
    }

    /**
     * 恢复被测试临时禁用的护栏触发器。
     *
     * <p>★放在 {@code finally} 里：中途失败若留下禁用状态，
     * 后续所有用例的 fail-closed 护栏都失效——一个坏用例会让整套测试变成假绿。
     * 逐条独立执行并吞掉异常，确保一条失败不阻断其余恢复。
     */
    private void restoreGuards() {
        for (String sql : new String[] {
            "ALTER TABLE replay_batch ENABLE TRIGGER replay_batch_totality_trg",
            "ALTER TABLE replay_batch_item ENABLE TRIGGER replay_batch_item_parent_totality_trg",
            "ALTER TABLE replay_batch_item ENABLE TRIGGER replay_batch_item_counts_trg",
        }) {
            try {
                runSql(sql);
            } catch (RuntimeException ignored) {
                // 尽力恢复：某一条失败不应阻断其余
            }
        }
    }

    /** 独立事务执行一条 DDL/SQL。 */
    private void runSql(String sql) {
        QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery(sql).executeUpdate());
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
