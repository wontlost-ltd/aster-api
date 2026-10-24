-- 重验任务的 report（只报告）模式与 advisory 判定（ADR 0050 §2.2 rev 6）：
--   policy_revalidation_run.mode        enforce = 判定立即对门禁生效（原行为）；report = 只记录、不拦截
--   policy_version_validation.advisory  true = 来自 report 任务，门禁视同无行（首访时同步验证并提升为 false）
-- 只做 expand：两列均带默认值，历史行语义不变。
ALTER TABLE policy_revalidation_run ADD COLUMN mode text NOT NULL DEFAULT 'enforce' CHECK (mode IN ('enforce', 'report'));
ALTER TABLE policy_version_validation ADD COLUMN advisory boolean NOT NULL DEFAULT false;
