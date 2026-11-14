-- ADR 0054 §10（#465）：Guard 对账。只做 expand，不改既有表的行语义。

-- 副作用落地方的批量上报（POST /api/v1/guard/side-effects）。幂等键 (tenant_id, source, effect_id)：重复上报不改原行。
--   decision_id / attempt_id / action_hash  上报方声称的凭据；decision_id 不加外键——上报方可能给出不存在或他租户的 id，正是要抓的
--   reported_by                             上报请求的已解析身份
--   reconciled_at / reconcile_outcome       对账处理时刻与结论（matched 或孤儿 kind）；NULL = 待对账
CREATE TABLE guard_side_effects (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    source VARCHAR(64) NOT NULL,
    effect_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    resource_type VARCHAR(255),
    resource_id VARCHAR(255),
    decision_id UUID,
    attempt_id VARCHAR(64),
    action_hash VARCHAR(64),
    reported_by VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reconciled_at TIMESTAMPTZ,
    reconcile_outcome VARCHAR(40),
    CONSTRAINT guard_side_effects_uq UNIQUE (tenant_id, source, effect_id)
);
CREATE INDEX guard_side_effects_pending_ix ON guard_side_effects (tenant_id, id) WHERE reconciled_at IS NULL;
CREATE INDEX guard_side_effects_decision_ix ON guard_side_effects (tenant_id, decision_id) WHERE decision_id IS NOT NULL;

-- 每租户每次实际执行的对账一行（跳过的不写）。window_start / window_end 为账本类扫描窗口 [since, cutoff)；
-- audit_id 为本次写入的 GUARD_RECONCILIATION 事件，无变化的对账不入链时为 NULL。
CREATE TABLE guard_reconciliation_runs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    side_effects_checked INT NOT NULL,
    new_orphans INT NOT NULL,
    resolved_orphans INT NOT NULL,
    open_orphans INT NOT NULL,
    new_by_kind JSONB NOT NULL,
    audit_id BIGINT
);
CREATE INDEX guard_reconciliation_runs_tenant_ix ON guard_reconciliation_runs (tenant_id, finished_at DESC);

-- 孤儿动作：同一事实 (tenant_id, kind, subject_key) 永远一行，跨多轮对账不重复。
--   subject_key     决策类（账本四类、orphan_receipt）为 decision_id 文本；上报类为 'se:' || 上报行 id
--   side_effect_id  上报行被保留期清理后置 NULL，detail 已复制上报关键字段，孤儿仍自描述
--   executor_label  归一后的低基数标签（指标 executor）；executor_id 为原始自报值
--   status / resolution  OPEN → RESOLVED（late_result | aged_out）或 ACKNOWLEDGED（acknowledged）
--   first_run_id    检出它的对账 run
CREATE TABLE guard_orphan_actions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    subject_key VARCHAR(80) NOT NULL,
    decision_id UUID,
    side_effect_id BIGINT REFERENCES guard_side_effects(id) ON DELETE SET NULL,
    executor_label VARCHAR(40) NOT NULL,
    executor_id VARCHAR(255),
    detail JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    first_run_id BIGINT NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolution VARCHAR(32),
    acknowledged_by VARCHAR(255),
    ack_comment VARCHAR(1000),
    ack_audit_id BIGINT,
    CONSTRAINT guard_orphan_actions_uq UNIQUE (tenant_id, kind, subject_key),
    CONSTRAINT guard_orphan_actions_kind_ck CHECK (kind IN ('side_effect_without_decision', 'side_effect_unconsumed',
        'side_effect_mismatch', 'consumed_without_result', 'allow_unconsumed', 'consume_on_non_allow',
        'result_without_consume', 'orphan_receipt')),
    CONSTRAINT guard_orphan_actions_status_ck CHECK (status IN ('OPEN', 'RESOLVED', 'ACKNOWLEDGED')),
    CONSTRAINT guard_orphan_actions_resolution_ck CHECK (resolution IS NULL OR resolution IN ('late_result', 'aged_out',
        'acknowledged'))
);
CREATE INDEX guard_orphan_actions_open_ix ON guard_orphan_actions (tenant_id, kind) WHERE status = 'OPEN';
CREATE INDEX guard_orphan_actions_side_effect_ix ON guard_orphan_actions (side_effect_id) WHERE side_effect_id IS NOT NULL;

-- allow_unconsumed 扫描：按租户找未消耗且已过期的收据。
CREATE INDEX guard_receipts_unconsumed_expiry_ix ON guard_receipts (tenant_id, expires_at) WHERE consume_audit_id IS NULL;
-- 候选租户枚举：窗口内有决策的租户（含只有待审批决策、没有收据行的租户——orphan_receipt 要扫它们）。
CREATE INDEX guard_decisions_created_ix ON guard_decisions (created_at);
-- orphan_receipt 扫描（#522）：按租户、时间取决策事件，不扫同租户的评估等其他事件。
CREATE INDEX audit_logs_guard_decision_time_ix ON audit_logs (tenant_id, timestamp) WHERE event_type = 'ACTION_GUARD_DECISION';

COMMENT ON TABLE guard_side_effects IS 'Guard 对账：副作用落地方上报（ADR 0054 §10.2）';
COMMENT ON TABLE guard_reconciliation_runs IS 'Guard 对账：每租户每次对账记录（ADR 0054 §10）';
COMMENT ON TABLE guard_orphan_actions IS 'Guard 对账：孤儿动作（ADR 0054 §10.3）';
