-- ADR 0059 修订 3 P2-R2：策略包导出签名密钥配置的 revision 历史。只做 expand，新增独立表，不改任何既有对象。
-- 与 guard_signing_key_revisions 同形：启动时配置 revision 小于最大值即失败，等于不写，大于插入一行。
CREATE TABLE export_signing_key_revisions (
    revision BIGINT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL,
    kids JSONB NOT NULL
);

COMMENT ON TABLE export_signing_key_revisions IS '策略包导出签名密钥配置 revision 历史（ADR 0059 修订 3 P2-R2）';
