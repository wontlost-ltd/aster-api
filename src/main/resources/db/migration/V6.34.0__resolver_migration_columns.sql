-- ADR 0051 §9：存量重编译（resolver 迁移任务）的逐行状态。
--   resolver_version               仅迁移成功（或按新规则创建）的行为 1；NULL = 尚未迁移或迁移失败，重跑会再试
--   resolver_migration_error       最近一次迁移失败的原因，成功时清空
--   archived_by_resolver_migration 仅本任务归档的行为 true；人工/业务归档的行恒为 false，成功时不会被恢复
--   status_before_archive          本任务归档前的状态，迁移成功时据此恢复
-- 只做 expand：nullable 列与带默认值的布尔列，不改历史行语义。
ALTER TABLE policy_versions
    ADD COLUMN resolver_version INTEGER,
    ADD COLUMN resolver_migration_error TEXT,
    ADD COLUMN archived_by_resolver_migration BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN status_before_archive VARCHAR(32);

-- 任务按主键升序扫描 resolver_version IS DISTINCT FROM 1 的行；部分索引只覆盖待迁移行，迁移完成后几乎为空。
CREATE INDEX idx_policy_versions_resolver_pending
    ON policy_versions (id) WHERE resolver_version IS DISTINCT FROM 1;
