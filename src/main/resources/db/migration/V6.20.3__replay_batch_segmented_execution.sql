-- ADR 0034 §11：租约/owner 协议重做——分段执行 + 逐条成败标记
--
-- 第三轮审查（26/100）的根因（§11.0）：**护栏写在了能被绕过的层**。
-- 三轮里同一个错误换了三种形态，最贵的一次是「终态写带 AND lease_owner=?」——
-- 而 `batch` 是 managed 实体，Hibernate 在提交时照样把脏实体 flush 回去，
-- 条件更新形同虚设。commit message 里却写着「结构上不可能」。
--
-- 本迁移的目标：让「谁能写这一行」由**数据库**回答，而不是由应用层自觉回答。

-- ── 1. 逐条成败标记（§11.5）────────────────────────────────────────────────
--
-- 分段执行拆掉了「整批一个事务」，而那原本是 §1.1 的实现基础
-- （要么全成功、要么全拒答）。改由本表保：
--   · 每条重跑完立即落标记，崩溃只丢当前段
--   · 只有**全部条目都成功**才允许迁移到 COMPLETED（下方 CHECK 兜底）
--
-- ★与 §3.1「不存逐条 targetDecision」不冲突：
--   那条要避免的是**决策结果**（PII 面扩大 + 失效语义复杂），
--   这里存的是成败标记与失败类别，既不含 PII 也不含决策内容——
--   与冻结 execution id 集合是同一类边界。
ALTER TABLE replay_batch_item
    -- NULL = 尚未重跑（分段执行的天然中间态）
    ADD COLUMN success      BOOLEAN,
    -- 仅 success=false 时非空；取值来自 ReplayFailureKind
    ADD COLUMN failure_kind VARCHAR(64),
    -- 重跑后目标版本是否「通过」。★只存布尔判定，不存 decision 内容
    ADD COLUMN target_approved BOOLEAN;

-- 成败与失败类别必须自洽：成功不得带失败类别，失败必须带
ALTER TABLE replay_batch_item
    ADD CONSTRAINT replay_batch_item_outcome_ck CHECK (
        success IS NULL
        OR (success = TRUE  AND failure_kind IS NULL)
        OR (success = FALSE AND failure_kind IS NOT NULL)
    );

-- 分段推进用：找出本批次里「还没跑」的条目
CREATE INDEX replay_batch_item_pending_idx
    ON replay_batch_item (batch_id)
    WHERE success IS NULL;

-- ── 2. 分段进度（§11.2）────────────────────────────────────────────────────
--
-- 租约不再按「整批」取值。最坏耗时 10000 × 30s = 83 小时，
-- 而我上一版把租约定成 2 小时并注释「可覆盖最坏情况」——那个算术从没做过。
-- 分段后租约只需覆盖**一段**（SEGMENT_SIZE 条），量级可控。
ALTER TABLE replay_batch
    -- 已推进到的位置：worker 每段提交时更新，崩溃后从这里继续
    ADD COLUMN segment_cursor INTEGER NOT NULL DEFAULT 0;

-- ── 3. ★COMPLETED 必须无失败条目（DB 层兜底 §1.1）──────────────────────────
--
-- 应用层的 decide() 仍是主判定，但**不能只靠它**：
-- 分段执行让「批次已完成」这个判断分散到多次提交里，
-- 任何一次写错都可能让有失败条目的批次被标成 COMPLETED。
-- 触发器在数据库层堵死这条路——这正是 §11.0 说的「让数据库回答」。
CREATE OR REPLACE FUNCTION replay_batch_completed_requires_all_success()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'COMPLETED' THEN
        IF EXISTS (
            SELECT 1 FROM replay_batch_item
             WHERE batch_id = NEW.id
               AND (success IS DISTINCT FROM TRUE)
        ) THEN
            RAISE EXCEPTION
                'batch % 存在未成功条目，不得标记为 COMPLETED（ADR 0034 §1.1）', NEW.id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER replay_batch_completed_all_success_trg
    BEFORE UPDATE ON replay_batch
    FOR EACH ROW
    WHEN (NEW.status = 'COMPLETED')
    EXECUTE FUNCTION replay_batch_completed_requires_all_success();
