-- ADR 0034 §10：What-If 批次的**崩溃恢复**与**并发上限原子化**（P0-4 / P0-5）
--
-- 两个缺陷都源于同一个错误：把「只有一个副本、进程不会死」当成了前提。
--
-- P0-4（崩溃后不可恢复）：
--   领取逻辑只查 status='PENDING'，而 scheduler 只处理**当前进程捕获到的**异常。
--   进程在 PENDING→RUNNING 提交之后、worker 完成之前崩溃，该批次就永久停在
--   RUNNING：没有 lease、没有超时回收，也没人再看它一眼。
--   后果不止是这一个批次卡住——它还**持续占着租户的并发额度**（pro 档只有 1 个），
--   于是该租户再也发不出任何 What-If 批次。
--
-- P0-5（并发上限可被突破）：
--   先 SELECT 活跃批次数、再 INSERT，两步之间没有互斥。两个并发请求都读到 0，
--   于是都插入成功——pro 档「1 个并发」形同虚设。
--   另一处口径错误：按 user_id 计数，但额度语义是**租户级**（§7.2）。
--
-- ★★ 本文件的第一版会在**任何有历史活跃批次的库上直接失败**（已实测）：
--     ERROR: check constraint "replay_batch_running_has_lease_ck" is violated by some row
--     ERROR: check constraint "replay_batch_active_holds_slot_ck" is violated by some row
--   原因是「加可空列 → 立刻加要求活跃行非空的 CHECK」。
--   What-If 后端已随 #230 合入 main 且端点**无 feature flag**，
--   线上表不能假设为空。故本版改为「先处置历史行 → 再加约束」。
--   ★教训：验证「约束能拒绝坏数据」与验证「有历史数据时能否升级」是两件事。

-- ── 1. 加列（全部可空，不带约束）────────────────────────────────────────────
ALTER TABLE replay_batch
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    -- 租约持有者标识。★终态写必须带 `AND lease_owner = ?` 条件更新——
    -- 即便 lease 判断出错、旧 worker 仍在跑，它也**写不进去**（ADR 0034 §10.3）。
    -- 把「两个 worker 互相覆盖」从「尽量不发生」变成「结构上不可能」。
    ADD COLUMN lease_owner      VARCHAR(64),
    -- 已尝试次数：回收不是无限的。反复崩溃说明是这个批次本身有问题
    -- （比如某条输入必然让 worker 挂掉），继续重试只会无限循环占用额度。
    ADD COLUMN attempt_count    INTEGER NOT NULL DEFAULT 0,
    -- 并发槽位 [0, quota)。活跃时非空、终态时为空。
    ADD COLUMN concurrency_slot INTEGER;

-- ── 2. ★处置历史活跃行（必须在加约束**之前**）──────────────────────────────
--
-- 迁移前就存在的 PENDING/RUNNING 批次既没有 lease 也没有 slot。
-- 它们**不能被回填成"正在正常运行"**：那个进程早就不在了，
-- 回填 lease 等于谎称有人在跑，回收逻辑要等到 lease 过期才处理它。
--
-- 诚实的处置是**终止它们**：标记为 FAILED 并释放槽位。
-- 用户重新发起即可——批次是不可变快照（§3.2），重跑一次没有语义损失。
-- failure_reasons 留空对象：它们不是「用户数据有问题」，
-- 谎称某个失败 kind 会把用户支去排查并不存在的故障。
UPDATE replay_batch
   SET status           = 'FAILED',
       finished_at      = COALESCE(finished_at, NOW()),
       failure_reasons  = '{}'::jsonb,
       result_summary   = NULL,
       lease_expires_at = NULL,
       lease_owner      = NULL,
       concurrency_slot = NULL
 WHERE status IN ('PENDING', 'RUNNING');

-- ── 3. 加约束（此时全表已满足）──────────────────────────────────────────────
--
-- RUNNING 必须持有 lease；没有 lease 的 RUNNING 是不可能状态
-- （那正是崩溃后卡死的形态：状态是 RUNNING 但无人持有）。
-- 用 NOT VALID + VALIDATE 分两步：VALIDATE 只取 SHARE UPDATE EXCLUSIVE，
-- 不阻塞读写，避免大表上长时间持锁。
ALTER TABLE replay_batch
    ADD CONSTRAINT replay_batch_running_has_lease_ck CHECK (
        status <> 'RUNNING' OR (lease_expires_at IS NOT NULL AND lease_owner IS NOT NULL)
    ) NOT VALID;
ALTER TABLE replay_batch VALIDATE CONSTRAINT replay_batch_running_has_lease_ck;

-- 活跃批次必须占槽；终态批次释放槽位，否则历史批次会永久占用租户额度。
ALTER TABLE replay_batch
    ADD CONSTRAINT replay_batch_active_holds_slot_ck CHECK (
        (status IN ('PENDING', 'RUNNING') AND concurrency_slot IS NOT NULL)
        OR (status NOT IN ('PENDING', 'RUNNING') AND concurrency_slot IS NULL)
    ) NOT VALID;
ALTER TABLE replay_batch VALIDATE CONSTRAINT replay_batch_active_holds_slot_ck;

-- ── 4. 索引 ────────────────────────────────────────────────────────────────
-- 回收扫描：找出 lease 已过期的 RUNNING 批次
CREATE INDEX replay_batch_stale_lease_idx
    ON replay_batch (lease_expires_at)
    WHERE status = 'RUNNING';

-- ★真正堵住 TOCTOU 的那一行：同租户同槽位不可并存。
--   无论多少副本、多少并发请求，抢同一槽只有一个能成功。
--
--   不能简单建「每租户唯一活跃批次」——额度不恒为 1
--   （free=0 / pro=1 / enterprise 可自定义甚至无限，§7.2），
--   一刀切会把 enterprise 的合法并发打成 500，用一个 bug 换另一个。
--   槽位建模让上限由应用按 plan 决定，互斥由数据库保证。
--
--   按 tenant_id 而非 user_id：额度是租户级的，
--   原实现按 user_id 统计，同租户多用户各开一个即可绕过。
--
--   ★步骤 2 已把历史活跃行全部终止，故此处不会有重复键冲突。
CREATE UNIQUE INDEX replay_batch_tenant_slot_idx
    ON replay_batch (tenant_id, concurrency_slot)
    WHERE status IN ('PENDING', 'RUNNING');

-- 原按 user_id 的活跃索引口径错误（应为租户级），且已被上面的索引覆盖
DROP INDEX IF EXISTS replay_batch_active_by_user_idx;
