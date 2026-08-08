-- ADR 0034：What-If 重做——异步 ReplayBatch（S1 建表）
--
-- 背景：
--   What-If 回答「把策略从 v1 换成 v2，业务指标会怎样」。上一版（Phase 4）用
--   **同步按需重跑 + 部分成功估算**，因**选择偏差**在五轮交叉审查后被撤下：
--   允许「200 条发起、30 条成功」就出完整业务数字，而重跑失败与输入/词汇/
--   策略路径相关——剩下的成功样本不是随机子集，据此算出的数字可能方向对而
--   幅度全错。这不是加 caveat 能解决的。
--
-- 本表承载的第一性约束（ADR 0034 §1.1）：
--   「任何被呈现的数字，其样本必须是**某个用户能理解的总体的全量**，
--     而非该总体的成功子集。」
--
--   落地为：窗口内**全量**跑、**全部成功**才出数字，任一条失败即整批拒答。
--
-- 为什么落表（推翻 ADR 0033 §3.2 的「不落表」结论）：
--   0033 拒绝落表的理由（失效语义复杂 / 口径不如重跑诚实 / 零 schema 变更）
--   在**同步**模型下成立，异步下不成立——异步的本质就是运行与查询分离，
--   中间状态必须可持久化，否则进程重启即丢失。
--   ★注意本表只存 **batch 元数据与聚合结果**，**不存逐条 targetDecision**——
--   那才是 0033 真正要避免的东西（PII 面扩大 + 失效语义复杂）。

CREATE TABLE replay_batch (
    id                        UUID         PRIMARY KEY,

    -- 租户隔离：所有读写一律带 user_id 条件（ADR 0034 §4.3）
    tenant_id                 VARCHAR(255) NOT NULL,
    user_id                   VARCHAR(255) NOT NULL,

    policy_id                 VARCHAR(255) NOT NULL,
    -- 版本行 id 而非版本号字符串：版本号可能重复/可变，行 id 唯一且不可变
    base_version_id           VARCHAR(255) NOT NULL,
    target_version_id         VARCHAR(255) NOT NULL,

    -- ── 窗口口径（ADR 0034 §3.3）─────────────────────────────────────────
    -- window_kind 是**显式口径**，不是隐形抽样。它会随数字一起呈现给用户，
    -- 让用户知道「我看的是哪个总体」——这与「从 200 条里挑出成功的 30 条」
    -- 有本质区别。
    window_kind               VARCHAR(32)  NOT NULL,
    -- 呈现用文案（如「最近一个季度」），与数字同屏显示
    window_label              VARCHAR(128) NOT NULL,
    -- 解析「当天」所用的租户时区；未配置时为 UTC 并需在结果里标注
    window_timezone           VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    -- ★创建时固化的**绝对时刻**，左闭右开 [window_from, window_to)
    --   右边界取**当天 00:00**（不含当天）：边界指向已封闭的过去，
    --   正在写入的数据天然落在窗口外。
    --   不存「近 30 天」这种相对表达——跨零点会让左边界前移一天
    --   （每天必然出现一次的窗口），且窗口边界是**结果的一部分**，
    --   不固化则结果不可复现。
    window_from               TIMESTAMPTZ  NOT NULL,
    window_to                 TIMESTAMPTZ  NOT NULL,

    -- ── 进度 ────────────────────────────────────────────────────────────
    -- 窗口内可重跑执行总数，创建时确定且此后不变（窗口已固化，故不会漂移）
    planned_count             INTEGER      NOT NULL,
    completed_count           INTEGER      NOT NULL DEFAULT 0,
    failed_count              INTEGER      NOT NULL DEFAULT 0,

    status                    VARCHAR(32)  NOT NULL,

    -- 失败原因分布（JSON）。拒答时回报给用户——
    -- 「失败了」不够，用户需要知道是脏数据还是版本不兼容。
    failure_reasons           JSONB,

    -- 聚合结果。★仅在 status='COMPLETED' 时非空；FAILED 批次必须为 NULL，
    -- 由 CHECK 约束在**数据库层**强制（不依赖应用层自觉）。
    result_summary            JSONB,

    -- 创建时的工具链身份。批次完成后若工具链已变，结果标 STALE 而非静默呈现。
    toolchain_id              VARCHAR(512) NOT NULL,

    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at                TIMESTAMPTZ,
    finished_at               TIMESTAMPTZ,
    -- 结果保留 30 天后转 EXPIRED 并清空 result_summary（ADR 0034 §7.3）
    expires_at                TIMESTAMPTZ  NOT NULL,

    CONSTRAINT replay_batch_status_ck CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'EXPIRED')
    ),
    CONSTRAINT replay_batch_window_ck CHECK (window_from < window_to),
    CONSTRAINT replay_batch_counts_ck CHECK (
        planned_count   >= 0 AND
        completed_count >= 0 AND
        failed_count    >= 0 AND
        completed_count + failed_count <= planned_count
    ),
    -- ★第一性约束的数据库层兜底：只有 COMPLETED 才允许有数字。
    --   应用层再怎么写错，FAILED/EXPIRED 批次也拿不出 result_summary。
    CONSTRAINT replay_batch_result_only_when_completed_ck CHECK (
        (status = 'COMPLETED' AND result_summary IS NOT NULL)
        OR (status <> 'COMPLETED' AND result_summary IS NULL)
    ),
    -- ★全成功才算 COMPLETED：一条失败都不允许（ADR 0034 §1.1）
    CONSTRAINT replay_batch_completed_is_total_ck CHECK (
        status <> 'COMPLETED'
        OR (failed_count = 0 AND completed_count = planned_count)
    )
);

-- 并发上限判定：查「本租户当前有几个 PENDING/RUNNING」。
-- 部分索引只覆盖活跃状态，避免历史批次拖慢这个高频查询。
CREATE INDEX replay_batch_active_by_user_idx
    ON replay_batch (user_id)
    WHERE status IN ('PENDING', 'RUNNING');

-- 列表页：某策略的批次历史，按时间倒序
CREATE INDEX replay_batch_policy_created_idx
    ON replay_batch (policy_id, created_at DESC);

-- 过期清理定时任务扫描用
CREATE INDEX replay_batch_expires_idx
    ON replay_batch (expires_at)
    WHERE status IN ('COMPLETED', 'FAILED');

COMMENT ON TABLE replay_batch IS
    'What-If 影响估算的批次账本（ADR 0034）。窗口内全量重跑、全部成功才出数字；'
    '任一条失败即整批拒答。只存元数据与聚合结果，不存逐条 targetDecision。';

COMMENT ON COLUMN replay_batch.window_from IS
    '窗口起点（绝对时刻，含）。创建时按租户时区解析并固化，不存相对表达。';

COMMENT ON COLUMN replay_batch.window_to IS
    '窗口终点（绝对时刻，不含）。取当天 00:00——不含当天，边界指向已封闭的过去。';

COMMENT ON COLUMN replay_batch.result_summary IS
    '聚合结果。仅 COMPLETED 时非空，由 CHECK 约束强制——'
    '拒答的批次在数据库层就拿不出任何会被读成结论的数字。';
