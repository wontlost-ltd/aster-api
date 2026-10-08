-- 证据链补齐（ADR 0041 §2.4）：把 metadata 中的 correlationId / decisionId 冗余成列以便索引查询。
-- 不用 metadata::jsonb 表达式索引：metadata 超限时追加器会写入非法 JSON（…truncated），表达式索引会让插入失败。
-- 两列不进哈希载荷（AuditHashPayload），只是 metadata 的投影；旧行为 NULL，收据接口对其返回 missing。
ALTER TABLE audit_logs
    ADD COLUMN correlation_id VARCHAR(64),
    ADD COLUMN decision_id VARCHAR(64);
CREATE INDEX audit_logs_tenant_correlation_ix ON audit_logs (tenant_id, correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX audit_logs_tenant_decision_ix ON audit_logs (tenant_id, decision_id) WHERE decision_id IS NOT NULL;
