-- ADR 0042 §11 / §12（#460）：审批输入快照。只做 expand，不改历史行语义。

-- 待审批决策的明文 Action（不含 facts）：只为建了审批行的 REQUIRE_APPROVAL / ESCALATE 决策写，与决策行、审批行同一事务写入，
-- 写入后不可变（无 UPDATE 路径）；审批终态后按 ASTER_GUARD_INPUT_RETENTION 清理。读取时用 context_hash 复核完整性。
--   classification  声明树与 PII 标记 {"declared": [...], "marks": [...]}；NULL = 无分类（marks_source = none）
--   marks_source    version | evaluation | none
--   input_binding   direct | evaluated | unbound
CREATE TABLE guard_decision_inputs (
    decision_id UUID PRIMARY KEY REFERENCES guard_decisions(id),
    tenant_id VARCHAR(64) NOT NULL,
    action_json JSONB NOT NULL,
    context_hash VARCHAR(64) NOT NULL,
    classification JSONB,
    marks_source VARCHAR(16) NOT NULL,
    input_binding VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT guard_decision_inputs_marks_source_ck CHECK (marks_source IN ('version', 'evaluation', 'none')),
    CONSTRAINT guard_decision_inputs_input_binding_ck CHECK (input_binding IN ('direct', 'evaluated', 'unbound'))
);
CREATE INDEX guard_decision_inputs_tenant_ix ON guard_decision_inputs (tenant_id);

-- 可审批评估（非 simulate、结论 REQUIRE_APPROVAL / ESCALATE）的分类行：from-evidence 据此核对输入并取标记。
-- 先写者为准（INSERT … ON CONFLICT DO NOTHING），从不 UPDATE；created_at 起 ASTER_GUARD_CLASSIFICATION_RETENTION 后删除。
--   input_hash      请求 context 的 sha256-sorted-json/v1；位置格式（数组）输入为 NULL
--   classification  评估时执行 IR 推导的声明树与标记；推导失败、超上限或位置输入为 NULL
CREATE TABLE guard_evaluation_classifications (
    tenant_id VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    input_hash VARCHAR(64),
    classification JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT guard_evaluation_classifications_pk PRIMARY KEY (tenant_id, correlation_id)
);
CREATE INDEX guard_evaluation_classifications_created_ix ON guard_evaluation_classifications (created_at);

-- 按人留痕的原值解除：每人每审批一行，对应链上恰好一条 ACTION_GUARD_INPUT_REVEAL 事件（audit_id）。不随输入行清理。
CREATE TABLE guard_input_reveals (
    approval_id UUID NOT NULL REFERENCES guard_approvals(id),
    revealed_by VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    revealed_at TIMESTAMPTZ NOT NULL,
    audit_id BIGINT NOT NULL,
    CONSTRAINT guard_input_reveals_pk PRIMARY KEY (approval_id, revealed_by)
);

-- 审批时所见输入视图的快照哈希（ADR 0042 §12 D6）；旧行与未决行为 NULL。
--   input_status  available | unavailable | purged | integrity_failed | too_large
ALTER TABLE guard_approvals ADD COLUMN input_snapshot_hash VARCHAR(64);
ALTER TABLE guard_approvals ADD COLUMN input_snapshot_matched BOOLEAN;
ALTER TABLE guard_approvals ADD COLUMN input_revealed BOOLEAN;
ALTER TABLE guard_approvals ADD COLUMN input_status VARCHAR(16);

COMMENT ON TABLE guard_decision_inputs IS 'Action Guard 待审批决策的输入快照（ADR 0042 §12 D1）';
COMMENT ON TABLE guard_evaluation_classifications IS '可审批评估的输入哈希与分类（ADR 0042 §12 D1/D2）';
COMMENT ON TABLE guard_input_reveals IS '审批输入原值解除记录（ADR 0042 §12 D5）';
