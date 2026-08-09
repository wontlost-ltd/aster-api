-- ADR 0034：What-If 批次的**崩溃恢复**与**并发上限原子化**（P0-4 / P0-5）
--
-- 两个缺陷都源于同一个错误：把「只有一个副本、进程不会死」当成了前提。
--
-- P0-4（崩溃后不可恢复）：
--   领取逻辑只查 status='PENDING'，而 scheduler 只处理**当前进程捕获到的**异常。
--   进程在 PENDING→RUNNING 提交之后、worker 完成之前崩溃，该批次就永久停在
--   RUNNING：没有 lease、没有心跳、没有超时回收，也没人再看它一眼。
--   后果不止是这一个批次卡住——它还**持续占着租户的并发额度**（pro 档只有 1 个），
--   于是该租户再也发不出任何 What-If 批次。类注释曾声称「进程重启后自然接管」，
--   实现并非如此（又一处「注释声称 ≠ 实现如此」）。
--
-- P0-5（并发上限可被突破）：
--   先 SELECT 活跃批次数、再 INSERT，两步之间没有任何互斥。两个并发请求都读到 0，
--   于是都插入成功——pro 档「1 个并发」形同虚设。原有索引只是**普通**部分索引，
--   不提供唯一性。
--   另一处口径错误：按 user_id 计数，但额度语义是**租户级**（§7.2），
--   同租户多用户各开一个就绕过了上限。

-- ── P0-4：lease ───────────────────────────────────────────────────────────
-- 领取批次时写入 lease_expires_at；worker 定期续租。
-- 超期未续 = 持有者已死，允许其他副本回收重跑。
ALTER TABLE replay_batch
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    -- 已尝试次数：回收不是无限的。反复崩溃说明是这个批次本身有问题
    -- （比如某条输入必然让 worker 挂掉），继续重试只会无限循环占用额度。
    ADD COLUMN attempt_count    INTEGER NOT NULL DEFAULT 0;

-- 回收扫描：找出 lease 已过期的 RUNNING 批次
CREATE INDEX replay_batch_stale_lease_idx
    ON replay_batch (lease_expires_at)
    WHERE status = 'RUNNING';

-- RUNNING 必须持有 lease；没有 lease 的 RUNNING 是不可能状态
-- （那正是崩溃后卡死的形态：状态是 RUNNING 但无人持有）。
ALTER TABLE replay_batch
    ADD CONSTRAINT replay_batch_running_has_lease_ck CHECK (
        status <> 'RUNNING' OR lease_expires_at IS NOT NULL
    );

-- ── P0-5：并发上限交给数据库 ───────────────────────────────────────────────
-- ★不能简单地建「每租户唯一活跃批次」的唯一索引：额度不是恒为 1。
--   free=0、pro=1、**enterprise 可自定义甚至无限**（§7.2，PlanInfo
--   以 concurrentReplayBatches<0 表示无限）。一刀切的唯一索引会把 enterprise
--   租户的合法并发直接打成 500——用一个 bug 换另一个 bug。
--
-- 改用**槽位**建模：批次插入时占据一个 [0, quota) 的槽号，
-- (tenant_id, slot) 唯一。于是：
--   · 上限由应用按 plan 决定要占哪个槽，数据库保证同槽不可重复占用
--   · TOCTOU 消失——并发请求抢同一槽时，输的那个直接被唯一约束拒绝
--   · enterprise 想要 N 并发就有 N 个槽，不受一刀切限制
--
-- 按 tenant_id 而非 user_id：额度是租户级的（§7.2）。
-- 原实现按 user_id 计数，同租户多用户各开一个即可绕过上限。
ALTER TABLE replay_batch
    ADD COLUMN concurrency_slot INTEGER;

-- 活跃批次必须占槽；终态批次释放槽位（置 NULL），否则历史批次会永久占用额度。
ALTER TABLE replay_batch
    ADD CONSTRAINT replay_batch_active_holds_slot_ck CHECK (
        (status IN ('PENDING', 'RUNNING') AND concurrency_slot IS NOT NULL)
        OR (status NOT IN ('PENDING', 'RUNNING') AND concurrency_slot IS NULL)
    );

-- ★真正堵住 TOCTOU 的那一行：同租户同槽位不可并存。
--   无论多少副本、多少并发请求，抢同一槽只有一个能成功。
CREATE UNIQUE INDEX replay_batch_tenant_slot_idx
    ON replay_batch (tenant_id, concurrency_slot)
    WHERE status IN ('PENDING', 'RUNNING');

-- 原按 user_id 的活跃索引口径错误（应为租户级），且已被上面的索引覆盖
DROP INDEX IF EXISTS replay_batch_active_by_user_idx;
