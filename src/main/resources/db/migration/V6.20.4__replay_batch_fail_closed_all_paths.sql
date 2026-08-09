-- ADR 0034 §11.5：fail-closed 必须覆盖**所有**写入路径
--
-- V6.20.3 的触发器只挂 `BEFORE UPDATE ON replay_batch`，于是三条路径可以绕过：
--
--   1. 直接 INSERT 一个 status='COMPLETED' 的批次（不触发 UPDATE 触发器）
--   2. 批次已 COMPLETED 之后再 INSERT/UPDATE 出失败条目（触发器不看 item 表）
--   3. 条目数 < planned_count 却标 COMPLETED（触发器只查「有没有失败条目」，
--      不查「条目够不够」）——样本不是总体全量，正是 §1.1 要防的
--
-- ★真实 PostgreSQL 实测（第四轮审查发现，我已独立复现）：
--     INSERT batch(status='COMPLETED') → INSERT item(success=false)
--     错误数 0，最终落出 COMPLETED + 失败item=1
--
-- 我写 V6.20.3 时说「让数据库回答谁能写这一行」，
-- 但只回答了四种写法里的一种。本迁移把另外三条补齐。

-- ── 1. 批次侧：INSERT 与 UPDATE 都要校验 ──────────────────────────────────
CREATE OR REPLACE FUNCTION replay_batch_completed_requires_all_success()
RETURNS TRIGGER AS $$
DECLARE
    bad_items   BIGINT;
    total_items BIGINT;
BEGIN
    IF NEW.status <> 'COMPLETED' THEN
        RETURN NEW;
    END IF;

    SELECT count(*) FILTER (WHERE success IS DISTINCT FROM TRUE), count(*)
      INTO bad_items, total_items
      FROM replay_batch_item
     WHERE batch_id = NEW.id;

    IF bad_items > 0 THEN
        RAISE EXCEPTION
            'batch % 存在 % 条未成功条目，不得标记为 COMPLETED（ADR 0034 §1.1）',
            NEW.id, bad_items;
    END IF;

    -- ★条目数必须等于 planned_count：少一条就说明样本不是总体的全量。
    --   只查「有没有失败」挡不住「只跑了 1 条却声称 3 条全成功」。
    IF total_items <> NEW.planned_count THEN
        RAISE EXCEPTION
            'batch % 只有 % 条结果、计划 % 条，样本不是总体全量，不得标记为 COMPLETED（ADR 0034 §1.1）',
            NEW.id, total_items, NEW.planned_count;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS replay_batch_completed_all_success_trg ON replay_batch;

-- ★INSERT OR UPDATE：V6.20.3 只挂了 UPDATE，直接 INSERT 一个 COMPLETED 即可绕过
CREATE TRIGGER replay_batch_completed_all_success_trg
    BEFORE INSERT OR UPDATE ON replay_batch
    FOR EACH ROW
    WHEN (NEW.status = 'COMPLETED')
    EXECUTE FUNCTION replay_batch_completed_requires_all_success();

-- ── 2. 条目侧：批次已 COMPLETED 后不得再写出非成功条目 ────────────────────
--
-- 没有这一条，「先把批次标成 COMPLETED、再插一条失败 item」就能让
-- 「全量成功」变成谎言——而父表触发器此时已经跑完了。
CREATE OR REPLACE FUNCTION replay_batch_item_guard_completed_batch()
RETURNS TRIGGER AS $$
DECLARE
    batch_status VARCHAR(32);
BEGIN
    SELECT status INTO batch_status FROM replay_batch WHERE id = NEW.batch_id;

    IF batch_status = 'COMPLETED' AND (NEW.success IS DISTINCT FROM TRUE) THEN
        RAISE EXCEPTION
            'batch % 已标记为 COMPLETED，不得再写入未成功条目（ADR 0034 §1.1）',
            NEW.batch_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER replay_batch_item_guard_completed_trg
    BEFORE INSERT OR UPDATE ON replay_batch_item
    FOR EACH ROW
    EXECUTE FUNCTION replay_batch_item_guard_completed_batch();

-- ── 3. 归一化历史 failure_reasons ────────────────────────────────────────
--
-- V6.20.1/V6.20.2 把历史活跃行处置成 FAILED 时写的是 `{}`（空对象），
-- 而 §10.1 之后的 API 契约是**数组** failureKinds。
-- 迁移能过 ≠ 历史数据符合契约：cloud 侧按数组读会拿到对象。
-- ★★ 只转**空**对象。上一版写的是「所有 object 一律转 []」——
--   实测把 {"INPUT_INCOMPATIBLE":170,"TIMEOUT":30} 直接清成 []，
--   那不是归一化，是**丢弃历史失败信息**。
--   我给它起名叫「归一化」，WHERE 条件却匹配了任意对象。
--
--   非空 object 是**真实的历史失败分布**（§10.1 之前的契约就是 {类别:条数}），
--   它们要保留信息、只改形状：取 key 列表转成数组，与新契约一致。
UPDATE replay_batch
   SET failure_reasons = '[]'::jsonb
 WHERE failure_reasons IS NOT NULL
   AND jsonb_typeof(failure_reasons) = 'object'
   AND failure_reasons = '{}'::jsonb;

-- 非空 object：{类别:条数} → [类别]，保留类别、丢掉条数
-- （丢条数是 §10.1 的**有意**决定：条数可与总体量相减推出成功数）
UPDATE replay_batch
   SET failure_reasons = (
        SELECT COALESCE(jsonb_agg(k ORDER BY k), '[]'::jsonb)
          FROM jsonb_object_keys(failure_reasons) AS k)
 WHERE failure_reasons IS NOT NULL
   AND jsonb_typeof(failure_reasons) = 'object'
   AND failure_reasons <> '{}'::jsonb;
