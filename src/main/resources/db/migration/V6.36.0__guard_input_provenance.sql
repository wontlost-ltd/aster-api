-- ADR 0040 §11 D3 / D4：输入逐字段来源与签名事实签发方。只做 expand，不改历史行语义。
-- guard_decisions：旧行三列 NULL = 全部 declared、无签名事实。
--   verified_fields  verified 叶子路径（JSON 字符串数组，字典序）
--   fact_issuers     本决策引用的签发方 "issuer#kid"（JSON 字符串数组，字典序）；吊销联动按 @> 包含查询
--   fact_expires_at  本次请求全部 facts 中最早的 expiresAt；重放据此标注 factsStale
ALTER TABLE guard_decisions ADD COLUMN verified_fields JSONB;
ALTER TABLE guard_decisions ADD COLUMN fact_issuers JSONB;
ALTER TABLE guard_decisions ADD COLUMN fact_expires_at TIMESTAMPTZ;

-- 审批转 EXPIRED 的原因：自然过期为 NULL，签发方吊销为 fact_issuer_revoked。
ALTER TABLE guard_approvals ADD COLUMN expired_reason VARCHAR(32);
-- 吊销事件按库列出受影响决策（tenant_id + status='EXPIRED' + expired_reason = ?）：部分索引只收非自然过期的行，
-- 不随本租户自然过期的历史 EXPIRED 累积；该查询在持有租户链锁与签发方行锁时执行。
CREATE INDEX guard_approvals_expired_reason_ix ON guard_approvals (tenant_id, expired_reason) WHERE expired_reason IS NOT NULL;

-- 租户级签名事实签发方公钥。吊销 = enabled=false，不删行（保留审计）；轮换 = 同一 issuer 多个 kid 同时 enabled。
-- 验签按 (tenant_id, issuer, kid) 主键直查，不缓存。public_key 为 base64url（无填充）的 32 字节 Ed25519 原始公钥。
CREATE TABLE guard_fact_issuers (
    tenant_id VARCHAR(255) NOT NULL,
    issuer VARCHAR(255) NOT NULL,
    kid VARCHAR(64) NOT NULL,
    alg VARCHAR(16) NOT NULL,
    public_key VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    not_after TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    -- 吊销（启用 → 禁用，ADR 0040 §11 D4）的时刻与操作者（not_after 到期为 SYSTEM）；新建即禁用的行两列为 NULL。
    revoked_at TIMESTAMPTZ,
    revoked_by VARCHAR(255),
    -- 该次吊销的 GUARD_FACT_ISSUER_REVOKED 事件 audit_logs.id；revoked_at 非空而本列为 NULL = 联动事件尚未入链，由调度与重复请求补写。
    revocation_audit_id BIGINT,
    CONSTRAINT guard_fact_issuers_pk PRIMARY KEY (tenant_id, issuer, kid),
    CONSTRAINT guard_fact_issuers_alg_ck CHECK (alg = 'Ed25519')
);

COMMENT ON TABLE guard_fact_issuers IS 'Action Guard 签名事实签发方公钥（ADR 0040 §11 D3）';
