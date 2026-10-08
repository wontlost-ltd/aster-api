-- ADR 0042 §3.1：从证据开决策时记录锚定的评估事件相关键；同租户同相关键只能开一条决策。
ALTER TABLE guard_decisions ADD COLUMN evidence_correlation_id VARCHAR(64);
CREATE UNIQUE INDEX guard_decisions_evidence_uq ON guard_decisions (tenant_id, evidence_correlation_id)
    WHERE evidence_correlation_id IS NOT NULL;
