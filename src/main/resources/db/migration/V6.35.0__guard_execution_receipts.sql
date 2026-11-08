-- ADR 0040 §12 D4 / D6：执行收据与签名密钥配置 revision。只做 expand，不改历史行语义。
-- guard_decisions.action_hash：请求体 action 子树的 jcs-sha256/v1 哈希；旧行 NULL = 无执行收据。
ALTER TABLE guard_decisions ADD COLUMN action_hash VARCHAR(64);

-- 一决策一行：直接 ALLOW 时与决策行同一事务插入，审批 APPROVED 时在审批事务插入；审批前不存在。
-- 本表是 consume 判定的唯一权威，response_json 中的 executionReceipt 只是视图。
--   consumed_at / executor_id / attempt_id / consume_audit_id  consume 成功时写入；consume_audit_id 指向权威的尝试事件
--   result_status / result_at / result_audit_id                结果回写时写入
CREATE TABLE guard_receipts (
    decision_id UUID PRIMARY KEY REFERENCES guard_decisions(id),
    tenant_id VARCHAR(255) NOT NULL,
    action_hash VARCHAR(64) NOT NULL,
    kid VARCHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    signature VARCHAR(128) NOT NULL,
    consumed_at TIMESTAMPTZ,
    executor_id VARCHAR(255),
    attempt_id VARCHAR(64),
    consume_audit_id BIGINT,
    result_status VARCHAR(16),
    result_at TIMESTAMPTZ,
    result_audit_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT guard_receipts_result_status_ck CHECK (result_status IN ('executed','failed','aborted'))
);
-- 对账扫描（#465）：按租户找已消耗未回写结果的行。
CREATE INDEX guard_receipts_tenant_consumed_ix ON guard_receipts (tenant_id, consumed_at);

-- 签名密钥配置的显式单调版本号：启动时配置 revision 小于最大值即失败，等于不写，大于插入一行。
CREATE TABLE guard_signing_key_revisions (
    revision BIGINT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL,
    kids JSONB NOT NULL
);

COMMENT ON TABLE guard_receipts IS 'Action Guard 执行收据（ADR 0040 §12 D4）';
COMMENT ON TABLE guard_signing_key_revisions IS '执行收据签名密钥配置 revision 历史（ADR 0040 §12 D6）';
