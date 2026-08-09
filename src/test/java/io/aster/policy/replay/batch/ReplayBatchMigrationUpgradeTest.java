package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6.20.1 迁移对**历史数据**的可升级性（ADR 0034 §10.4）。
 *
 * <h2>被修复的 bug</h2>
 *
 * <p>第一版迁移「加可空列 → 立刻加要求活跃行非空的 CHECK」，
 * 在任何有历史 PENDING/RUNNING 批次的库上<b>确定性失败</b>（已实测）：
 *
 * <pre>
 * ERROR: check constraint "replay_batch_running_has_lease_ck" is violated by some row
 * ERROR: check constraint "replay_batch_active_holds_slot_ck" is violated by some row
 * </pre>
 *
 * <p>What-If 后端已随 #230 合入 main 且端点<b>无 feature flag</b>，
 * 线上表不能假设为空——这会是一次失败的生产部署。
 *
 * <h2>★教训</h2>
 *
 * <p>上一轮我实测了「约束能拒绝坏数据」，就以为迁移验过了。
 * 但那验的是<b>约束的正确性</b>，不是<b>迁移的可用性</b>——
 * 两者是不同的事，而后者才是部署时会炸的那个。
 *
 * <p>本用例锁住迁移的**结构顺序**：处置历史行必须在加约束之前。
 * 真实的升级路径由本地实测覆盖（见 PR #234 描述），
 * 这里防的是「后续有人把 UPDATE 挪走或删掉」。
 */
class ReplayBatchMigrationUpgradeTest {

    private static String migration() throws Exception {
        return Files.readString(Path.of(
            "src/main/resources/db/migration/V6.20.1__replay_batch_lease_and_concurrency.sql"));
    }

    private static String frozenWindowMigration() throws Exception {
        return Files.readString(Path.of(
            "src/main/resources/db/migration/V6.20.2__replay_batch_frozen_window.sql"));
    }

    /**
     * ★V6.20.2 也必须先处置存量再加约束——<b>我在同一个分支里把这个错误犯了两次</b>。
     *
     * <p>V6.20.1 修的正是「加可空列后立刻加要求非空的 CHECK」，
     * V6.20.2 却原样又写了一遍：新增 {@code window_frozen_at} 后立即要求
     * RUNNING 行非空。两个迁移之间存在时间窗——多副本部署时调度器可能
     * 刚好在这期间领走一个批次，那行的 {@code window_frozen_at} 是 NULL。
     *
     * <p>实测确认：V6.20.1 之后插入一行 RUNNING，再跑 V6.20.2 →
     * <pre>ERROR: check constraint "replay_batch_running_is_frozen_ck" is violated by some row</pre>
     *
     * <p>教训：「先处置存量、再加约束」必须当成<b>加约束的固定前置</b>，
     * 而不是某一次的特例修补。本用例因此对 V6.20.2 独立断言一遍。
     */
    @Test
    void 冻结窗口迁移也必须先处置未冻结的活跃行() throws Exception {
        String sql = frozenWindowMigration();

        int disposal = sql.indexOf("UPDATE replay_batch");
        assertThat(disposal)
            .as("★V6.20.2 同样需要处置存量：两个迁移之间新产生的 RUNNING 行"
                + "没有 window_frozen_at，会让 CHECK 直接失败")
            .isGreaterThan(0);

        int frozenCk = sql.indexOf("ADD CONSTRAINT replay_batch_running_is_frozen_ck");
        assertThat(frozenCk).isGreaterThan(0);
        assertThat(disposal)
            .as("★处置必须在 CHECK 之前")
            .isLessThan(frozenCk);

        // 处置范围要精确到「尚未冻结的活跃行」，别把已冻结的正常批次也杀掉
        String stmt = sql.substring(disposal, frozenCk);
        assertThat(stmt)
            .as("★只处置**未冻结**的活跃行——已冻结的批次是正常状态，不该被终止")
            .contains("window_frozen_at IS NULL");
    }

    /**
     * failureReasons 现在是**类别数组**（§10.1），迁移里的处置语句也必须写数组。
     *
     * <p>写成 {@code '{}'::jsonb}（空对象）会与 {@code failureKinds} 的数组契约不符，
     * cloud 侧按数组读时拿到对象。
     */
    @Test
    void 迁移写入的失败原因必须是数组形态() throws Exception {
        assertThat(frozenWindowMigration())
            .as("★§10.1 后 failureReasons 存的是类别数组，不是 {类别:条数} 对象")
            .contains("'[]'::jsonb");
    }

    @Test
    void 必须先处置历史活跃行再加约束() throws Exception {
        String sql = migration();

        int disposal = sql.indexOf("UPDATE replay_batch");
        assertThat(disposal)
            .as("★必须存在「处置历史活跃行」的 UPDATE——"
                + "否则任何一行历史 PENDING/RUNNING 都会让迁移失败")
            .isGreaterThan(0);

        // ★锚定 `ADD CONSTRAINT` 而不是裸约束名：约束名也出现在文件头的
        //   注释里（我在那里引用了第一版失败时的报错原文）。
        //   只找约束名会匹配到注释，得出「顺序错了」的假结论——
        //   这正是本仓反复踩的「扫文本时边界是人选的」那个坑。
        int leaseCk = sql.indexOf("ADD CONSTRAINT replay_batch_running_has_lease_ck");
        int slotCk = sql.indexOf("ADD CONSTRAINT replay_batch_active_holds_slot_ck");
        assertThat(leaseCk).isGreaterThan(0);
        assertThat(slotCk).isGreaterThan(0);

        assertThat(disposal)
            .as("★处置必须在 lease CHECK **之前**")
            .isLessThan(leaseCk);
        assertThat(disposal)
            .as("★处置必须在 slot CHECK **之前**")
            .isLessThan(slotCk);
    }

    /**
     * 历史活跃行必须被**终止**，而不是回填成「正在正常运行」。
     *
     * <p>回填 lease 等于谎称有人在跑：那个进程早就不在了，
     * 回收逻辑还要等到 lease 过期才处理它。
     */
    @Test
    void 历史活跃行必须被终止而非回填成运行中() throws Exception {
        String sql = migration();
        int disposal = sql.indexOf("UPDATE replay_batch");
        int nextStmt = sql.indexOf("ALTER TABLE", disposal);
        String stmt = sql.substring(disposal, nextStmt > 0 ? nextStmt : sql.length());

        assertThat(stmt)
            .as("★历史活跃行终止为 FAILED——用户重发即可，批次是不可变快照（§3.2）")
            .contains("status           = 'FAILED'");
        assertThat(stmt)
            .as("★必须释放槽位，否则历史行永久占着租户额度")
            .contains("concurrency_slot = NULL");
        assertThat(stmt)
            .as("处置范围就是活跃状态")
            .contains("WHERE status IN ('PENDING', 'RUNNING')");
    }

    /**
     * 加约束用 NOT VALID + VALIDATE 两步。
     *
     * <p>直接 ADD CONSTRAINT 会取 ACCESS EXCLUSIVE 并全表扫描，大表上阻塞读写；
     * VALIDATE 只取 SHARE UPDATE EXCLUSIVE。
     */
    @Test
    void 加约束必须分两步避免长时间持锁() throws Exception {
        String sql = migration();

        assertThat(sql)
            .as("★NOT VALID + VALIDATE 分两步，避免大表上长时间阻塞读写")
            .contains("NOT VALID")
            .contains("VALIDATE CONSTRAINT replay_batch_running_has_lease_ck")
            .contains("VALIDATE CONSTRAINT replay_batch_active_holds_slot_ck");
    }

    /**
     * ★lease_owner 是 §10.3 的核心：终态写带 owner 条件更新，
     * 被误回收的旧 worker <b>写不进去</b>。
     *
     * <p>把「两个 worker 互相覆盖」从「尽量不发生」变成「结构上不可能」。
     */
    @Test
    void 必须有lease_owner列且RUNNING时非空() throws Exception {
        String sql = migration();

        assertThat(sql)
            .as("★owner token 是防双 worker 覆盖的根本手段，不是可选优化")
            .contains("ADD COLUMN lease_owner");
        assertThat(sql)
            .as("★RUNNING 必须同时有 lease 与 owner——只有 lease 挡不住旧 worker 写入")
            .contains("lease_expires_at IS NOT NULL AND lease_owner IS NOT NULL");
    }
}
