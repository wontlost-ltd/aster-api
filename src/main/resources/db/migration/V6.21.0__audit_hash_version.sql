-- 审计哈希链版本列（2026-08-17 安全审计）
--
-- 背景：哈希链此前只覆盖 audit_logs 的 17 个业务字段中的 6 个
--   （event_type / timestamp / tenant_id / policy_module / policy_function / success）。
-- 未进链的字段包括 performed_by（谁做的）、reason（为什么）、client_ip、user_agent、
--   metadata、from_version/to_version、error_message、notes、policy_id、execution_time_ms。
-- 也就是说：
--     UPDATE audit_logs SET performed_by = 'x', reason = 'y' WHERE ...;
-- 执行后链验证仍返回 valid —— 「谁批准、为何批准」这两个最关键的问责字段可被
-- 静默改写。而产品对外文案（四语，面向 SOX/HIPAA/PCI 受监管客户）宣称
-- 「hash-chained audit makes it tamper-evident / 哈希链审计让记录不可篡改」。
--
-- 修复方向：把全部业务字段纳入哈希（见 io.aster.audit.chain.AuditHashPayload）。
--
-- 为什么需要本列（而不是直接换公式）：
--   既有行的 current_hash 是用旧公式算出来的。若直接换公式，所有历史行会
--   立刻验证失败（表现为「全链被篡改」的假警报）。
--   而「用新公式重算历史 hash」是更糟的选择——重算等于改写审计记录本身，
--   会销毁「这条链从未被动过」这一属性，合规上无法自圆其说。
--
--   因此按行记录公式版本，验证器据此选择算法：
--     hash_version = 1 → 旧的 6 字段公式（历史行保持可验证）
--     hash_version = 2 → 全字段 canonical 编码（新行获得完整保护）
--
-- DEFAULT 1 让所有既有行自动落到 V1，无需 UPDATE 回填（也就不会触碰历史数据）；
-- 应用层新写入显式置 2（见 AuditLog.hashVersion 字段默认值）。
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS hash_version SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN audit_logs.hash_version IS
    '哈希公式版本：1=历史 6 字段公式，2=全业务字段 canonical。验证器按行选择算法；历史行不重算。';
