-- Action Guard（ADR 0040）：决策记录与待审批记录。
-- guard_decisions 存每次 guard 请求的结论摘要与首次响应体（幂等重放），不存明文 context。
-- guard_approvals 存 REQUIRE_APPROVAL / ESCALATE 的人工审批状态；审批事实本身在 audit_logs 哈希链里。
CREATE TABLE guard_decisions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255),
    policy_module VARCHAR(200) NOT NULL,
    policy_function VARCHAR(200) NOT NULL,
    policy_version_id BIGINT,
    source_hash VARCHAR(64),
    principal_id VARCHAR(255) NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    resource_type VARCHAR(255) NOT NULL DEFAULT '',
    resource_id VARCHAR(255) NOT NULL DEFAULT '',
    context_hash VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    reason VARCHAR(1000),
    required_role VARCHAR(255),
    reason_code VARCHAR(64),
    rule_id VARCHAR(255),
    controls JSONB,
    receipt_audit_id BIGINT,
    receipt_hash VARCHAR(64),
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT guard_decisions_outcome_ck CHECK (outcome IN ('ALLOW','DENY','REQUIRE_APPROVAL','ESCALATE'))
);
CREATE UNIQUE INDEX guard_decisions_idem_uq ON guard_decisions (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX guard_decisions_tenant_created_ix ON guard_decisions (tenant_id, created_at DESC);

CREATE TABLE guard_approvals (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    decision_id UUID NOT NULL REFERENCES guard_decisions(id),
    outcome VARCHAR(32) NOT NULL,
    required_role VARCHAR(255),
    principal_id VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decided_by VARCHAR(255),
    decided_at TIMESTAMPTZ,
    comment VARCHAR(1000),
    expires_at TIMESTAMPTZ NOT NULL,
    receipt_audit_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT guard_approvals_outcome_ck CHECK (outcome IN ('REQUIRE_APPROVAL','ESCALATE')),
    CONSTRAINT guard_approvals_status_ck CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED'))
);
CREATE UNIQUE INDEX guard_approvals_decision_uq ON guard_approvals (decision_id);
CREATE INDEX guard_approvals_tenant_status_ix ON guard_approvals (tenant_id, status, created_at DESC);
CREATE INDEX guard_approvals_pending_expiry_ix ON guard_approvals (expires_at) WHERE status = 'PENDING';

COMMENT ON TABLE guard_decisions IS 'Action Guard 决策记录（ADR 0040 §3.3）';
COMMENT ON TABLE guard_approvals IS 'Action Guard 待审批记录（ADR 0040 §4.1）';
