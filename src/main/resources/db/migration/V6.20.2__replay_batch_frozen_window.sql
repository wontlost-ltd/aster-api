-- ADR 0034 §10.2：冻结**总体**本身，而不只是它的数量
--
-- 上一版只把 window.size() 写进 planned_count 就算「冻结」，有两个问题：
--
-- 1. ★那次写入在 runBatch 的**长事务内**（replayAll 无独立事务标注），
--    Panache 的 persist() 不提交；worker 崩溃后事务回滚，库里仍是创建时的 0。
--    我在 commit message 里声称「在任何一条重跑开始前落库」——**那是不实陈述**。
--
-- 2. 更深一层：**只存了数量，没存成员**。回收重跑时重新拉窗口，
--    上游 executions 若已变化（迟到写入、删除、replayable 状态变更），
--    即使 planned 重新派生也**不是同一个总体**。
--    而「样本即某个总体的全量」正是 §1.1 的前提——总体本身会漂移，前提就不成立。
--
-- 故把窗口内的 execution id 列表**落表冻结**：
--   · planned_count 由本表行数派生，不再是一个可能过期的独立计数
--   · 回收重跑读的是**冻结集合**，而非重新拉窗口 → 总体真正可复现
--
-- ★与 §3.1「只存 batch 元数据与聚合结果，不存逐条 targetDecision」不冲突：
--   那条禁的是**决策结果**（PII 面扩大 + 失效语义复杂），
--   这里存的是**输入标识**（execution id），不含决策、不含 PII。

CREATE TABLE replay_batch_item (
    batch_id      UUID         NOT NULL
        REFERENCES replay_batch (id) ON DELETE CASCADE,

    -- 上游 aster-cloud 的 execution 主键。冻结后不再变化。
    execution_id  VARCHAR(255) NOT NULL,

    -- 基线是否「通过」。★在冻结时一并存下，而不是重跑时再问上游——
    -- 上游的 decision 可能因数据订正而变，那会让「变化了多少条」这个
    -- 结论随时间漂移。基线必须与总体一起冻结。
    base_approved BOOLEAN      NOT NULL,

    PRIMARY KEY (batch_id, execution_id)
);

-- 按批次取冻结集合（worker 重跑时全量读）
CREATE INDEX replay_batch_item_batch_idx
    ON replay_batch_item (batch_id);

-- ★冻结完成标记：区分「还没冻结」与「冻结完成但窗口为空」。
--   没有这个标记，plannedCount=0 有歧义——
--   究竟是「这段时间没有执行」（正当结果），
--   还是「冻结事务还没跑/崩了」（系统故障）？
--   两者对用户的含义完全不同，不能混为一谈。
ALTER TABLE replay_batch
    ADD COLUMN window_frozen_at TIMESTAMPTZ;

-- RUNNING 必须已完成冻结：worker 开跑的前提就是总体已确定。
ALTER TABLE replay_batch
    ADD CONSTRAINT replay_batch_running_is_frozen_ck CHECK (
        status <> 'RUNNING' OR window_frozen_at IS NOT NULL
    ) NOT VALID;
ALTER TABLE replay_batch VALIDATE CONSTRAINT replay_batch_running_is_frozen_ck;
