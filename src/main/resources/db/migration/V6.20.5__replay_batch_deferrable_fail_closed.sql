-- ADR 0034 §12.3：fail-closed 改由 **DEFERRABLE 约束 + 汇总列** 表达
--
-- ★为什么推翻 V6.20.3/V6.20.4 的触发器方案：
--   即时行级触发器读的是**本事务的 MVCC 快照**，而「全部条目都成功」
--   是一个跨行、跨事务的断言。用前者表达后者，在并发下必然漏。
--
--   审查双会话实测（我已复现）：
--     A: UPDATE item success true→false     （不提交）
--     B: UPDATE parent → COMPLETED          （看不到 A 的未提交版本 → 放行，提交）
--     A: COMMIT                             （item 触发器基于自己的快照 → 放行）
--     最终: parent=COMPLETED + item=false   ← 违规状态落库
--
--   另有三条实测绕过：
--     · DELETE / TRUNCATE replay_batch_item → planned=1 item数=0
--     · UPDATE item SET batch_id=<新父>     → 旧父 item数=0（触发器只看 NEW，不看 OLD）
--     · COMPLETED 后追加 success=true       → planned=1 item数=2
--
-- 本迁移的思路：**不再让触发器做判定**，而是让它只维护两个计数列；
-- 判定交给 DEFERRABLE INITIALLY DEFERRED 约束，在**提交时**统一校验。
-- 提交时校验意味着上面那种「两个快照互不可见」的交错不再能通过——
-- 无论谁先提交，最后一个提交者都要面对已落库的真实计数。

-- ── 1. 汇总列 ────────────────────────────────────────────────────────────
ALTER TABLE replay_batch
    ADD COLUMN item_total   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN item_success INTEGER NOT NULL DEFAULT 0;

-- 回填既有数据（本迁移之前创建的批次）
UPDATE replay_batch b
   SET item_total   = COALESCE(c.total, 0),
       item_success = COALESCE(c.ok, 0)
  FROM (SELECT batch_id,
               count(*)                                   AS total,
               count(*) FILTER (WHERE success IS TRUE)     AS ok
          FROM replay_batch_item GROUP BY batch_id) c
 WHERE b.id = c.batch_id;

-- ── 2. 汇总维护：必须覆盖**四类事件** ────────────────────────────────────
--
-- ★第 3 条绕过（UPDATE 改挂父）正是因为只看 NEW.batch_id。
--   这里 UPDATE 分支同时对 OLD 父减、对 NEW 父加。
CREATE OR REPLACE FUNCTION replay_batch_item_maintain_counts()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE replay_batch
           SET item_total   = item_total + 1,
               item_success = item_success + (CASE WHEN NEW.success IS TRUE THEN 1 ELSE 0 END)
         WHERE id = NEW.batch_id;
        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN
        UPDATE replay_batch
           SET item_total   = item_total - 1,
               item_success = item_success - (CASE WHEN OLD.success IS TRUE THEN 1 ELSE 0 END)
         WHERE id = OLD.batch_id;
        RETURN OLD;

    ELSE  -- UPDATE
        IF OLD.batch_id IS DISTINCT FROM NEW.batch_id THEN
            -- ★改挂父：旧父减、新父加。只看 NEW 会让旧父的计数永远偏高
            UPDATE replay_batch
               SET item_total   = item_total - 1,
                   item_success = item_success - (CASE WHEN OLD.success IS TRUE THEN 1 ELSE 0 END)
             WHERE id = OLD.batch_id;
            UPDATE replay_batch
               SET item_total   = item_total + 1,
                   item_success = item_success + (CASE WHEN NEW.success IS TRUE THEN 1 ELSE 0 END)
             WHERE id = NEW.batch_id;
        ELSIF OLD.success IS DISTINCT FROM NEW.success THEN
            UPDATE replay_batch
               SET item_success = item_success
                   - (CASE WHEN OLD.success IS TRUE THEN 1 ELSE 0 END)
                   + (CASE WHEN NEW.success IS TRUE THEN 1 ELSE 0 END)
             WHERE id = NEW.batch_id;
        END IF;
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS replay_batch_item_guard_completed_trg ON replay_batch_item;

CREATE TRIGGER replay_batch_item_counts_trg
    AFTER INSERT OR UPDATE OR DELETE ON replay_batch_item
    FOR EACH ROW
    EXECUTE FUNCTION replay_batch_item_maintain_counts();

-- ★TRUNCATE 是**语句级**事件，行级触发器收不到——第 2 条绕过的一半。
CREATE OR REPLACE FUNCTION replay_batch_item_truncate_guard()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'replay_batch_item 不允许 TRUNCATE：会让所有批次的条目计数与实际脱节（ADR 0034 §12）';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER replay_batch_item_no_truncate_trg
    BEFORE TRUNCATE ON replay_batch_item
    FOR EACH STATEMENT
    EXECUTE FUNCTION replay_batch_item_truncate_guard();

-- ── 3. ★判定改为 DEFERRABLE 约束：提交时校验 ────────────────────────────
--
-- 这是本迁移的核心。约束在**事务提交时**求值，看到的是所有并发写落库后的
-- 真实计数，因此 §12.0 那种「两个快照互不可见」的交错不再能通过。
--
-- 旧的父表触发器（V6.20.3/V6.20.4）随之删除：它做的判定现在由约束承担，
-- 留着只会形成两套规则、且触发器那套在并发下是错的。
DROP TRIGGER IF EXISTS replay_batch_completed_all_success_trg ON replay_batch;

-- ★★ 不能用 `CHECK ... DEFERRABLE`：PostgreSQL 明确不支持
--     （实测：ERROR: CHECK constraints cannot be marked DEFERRABLE）。
--     我第一版就是这么写的，而 psql 的错误没被我的 grep 统计到，
--     于是「0 错误」的假信号让违规状态照样落库——
--     又一次「我以为验过了，其实没验到」。
--
--     可延迟的机制是 **CONSTRAINT TRIGGER**：它支持
--     DEFERRABLE INITIALLY DEFERRED，在**提交时**触发，
--     此时看到的是所有并发写落库后的真实计数。
CREATE OR REPLACE FUNCTION replay_batch_assert_totality()
RETURNS TRIGGER AS $$
DECLARE
    b RECORD;
BEGIN
    SELECT status, planned_count, item_total, item_success INTO b
      FROM replay_batch WHERE id = NEW.id;

    -- 行可能已被同事务删除
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF b.item_total < 0 OR b.item_success < 0 OR b.item_success > b.item_total THEN
        RAISE EXCEPTION
            'batch % 条目计数非法（total=%, success=%）——汇总维护有 bug（ADR 0034 §12）',
            NEW.id, b.item_total, b.item_success;
    END IF;

    IF b.status = 'COMPLETED'
       AND (b.item_total <> b.planned_count OR b.item_success <> b.planned_count) THEN
        RAISE EXCEPTION
            'batch % 标记为 COMPLETED，但条目 total=%/success=% 与计划 % 不符，'
            '样本不是总体全量（ADR 0034 §1.1）',
            NEW.id, b.item_total, b.item_success, b.planned_count;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ★INSERT OR UPDATE 都要挂：直接 INSERT 一个 COMPLETED 批次是已证实的绕过之一。
CREATE CONSTRAINT TRIGGER replay_batch_totality_trg
    AFTER INSERT OR UPDATE ON replay_batch
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION replay_batch_assert_totality();

-- item 侧也要挂：只改 item 而不碰父行时，父行的 AFTER 触发器不会触发，
-- 但汇总列已被维护函数改动——必须在提交时同样校验。
CREATE OR REPLACE FUNCTION replay_batch_item_assert_parent_totality()
RETURNS TRIGGER AS $$
DECLARE
    pid UUID;
    b   RECORD;
BEGIN
    pid := COALESCE(NEW.batch_id, OLD.batch_id);
    SELECT status, planned_count, item_total, item_success INTO b
      FROM replay_batch WHERE id = pid;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF b.status = 'COMPLETED'
       AND (b.item_total <> b.planned_count OR b.item_success <> b.planned_count) THEN
        RAISE EXCEPTION
            'batch % 已是 COMPLETED，条目变更后 total=%/success=% 与计划 % 不符（ADR 0034 §1.1）',
            pid, b.item_total, b.item_success, b.planned_count;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER replay_batch_item_parent_totality_trg
    AFTER INSERT OR UPDATE OR DELETE ON replay_batch_item
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION replay_batch_item_assert_parent_totality();

-- ── 4. 历史违规行：迁移成功 ≠ 历史不变量成立 ────────────────────────────
--
-- 审查指出：只给未来写加约束，不扫既存违规行，等于假设历史是干净的。
-- 把已经违反「COMPLETED ⇒ 全量成功」的历史行降级为 FAILED，
-- 并如实标注原因——它们本就不该以 COMPLETED 呈现数字（§1.1）。
UPDATE replay_batch
   SET status          = 'FAILED',
       failure_reasons = '["UNKNOWN"]'::jsonb,
       result_summary  = NULL,
       finished_at     = COALESCE(finished_at, NOW())
 WHERE status = 'COMPLETED'
   AND (item_total <> planned_count OR item_success <> planned_count);
