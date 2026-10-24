-- 记录重验任务的发起者，供审计对账（ADR 0050 §2.2 治理元数据）：
--   admin:<cloud 用户 id>          管理员经 cloud 后台发起
--   startup:toolchain-change       服务启动时检测到工具链变化自动发起
-- 只做 expand（可空列，无回填），旧行 triggered_by 为 NULL 表示本列上线前的历史任务。
ALTER TABLE policy_revalidation_run ADD COLUMN triggered_by text;
