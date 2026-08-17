-- 审计表 append-only 强制（2026-08-17 安全审计，第 2 层）
--
-- 背景：哈希链只能**检测**篡改，不能**阻止**。任何持有表写权限的主体
--   （应用连接角色、DBA、拿到凭据的攻击者）执行
--       UPDATE audit_logs SET performed_by = 'x';
--   都会成功；链验证随后会失败，但那是**事后**发现，记录已被改写。
--   而对外文案面向 SOX / HIPAA / PCI 客户，仅有「可检测」是不够的。
--
-- 三层防御中的第 2 层：
--   层 1（权限）：应用改用无 UPDATE/DELETE 权限的低权角色 —— 见 V6.22.1
--   层 2（本迁移）：数据库触发器**硬拒** UPDATE/DELETE，对所有非超级用户生效
--   层 3（锚定）：定期把链尾快照写入独立 anchor 表 —— 见 V6.23.0
--
-- 为什么触发器与权限**都要**做，而不是二选一：
--   - 只有权限：DBA / 超级用户 / 拿到 postgres 凭据者仍可随意改写；
--   - 只有触发器：超级用户可 `ALTER TABLE ... DISABLE TRIGGER ALL` 绕过
--     （但该操作本身会留下 DDL 痕迹，且需要显式意图，不再是「一条 UPDATE 就改了」）。
--   两者叠加后，改写审计记录需要**显式的、可审计的特权操作**，而非顺手一条 SQL。
--
-- 允许的操作：INSERT（追加）。TRUNCATE 一并禁止（否则可整表清空绕过行级触发器）。
--
-- ★保留窗口清理怎么办：审计日志有套餐保留期，到期需要删除历史行。
--   本触发器通过 session 变量 `aster.audit_retention_job` 开一个**显式豁免通道**：
--     SET LOCAL aster.audit_retention_job = 'on';
--   只有明确设置该变量的事务才能 DELETE。这样：
--     1) 常规应用路径（不设该变量）无论如何都删不掉；
--     2) 清理任务必须显式声明意图，且该意图在事务级生效、不会泄漏到其他连接；
--     3) UPDATE **永不放行** —— 保留期清理只需要删除，从不需要修改。

CREATE OR REPLACE FUNCTION reject_audit_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- UPDATE 一律拒绝：审计记录写入后不可修改，没有任何**生产**用例。
    --
    -- ★唯一例外是自动化测试：验证「篡改能被链检出」必须先真的篡改一条记录。
    --   该豁免走独立的 session 变量 aster.audit_tamper_simulation，
    --   **与保留期清理的 audit_retention_job 分开**——两者是不同性质的操作，
    --   合用一个开关会让「清理任务」顺带获得改写权限。
    --   生产代码路径从不设置本变量（全仓仅测试辅助类 BlockingDbTestHelper 设置）。
    IF (TG_OP = 'UPDATE') THEN
        IF current_setting('aster.audit_tamper_simulation', true) IS DISTINCT FROM 'on' THEN
            RAISE EXCEPTION
                'audit_logs 为 append-only：禁止 UPDATE（记录 id=%）。'
                '审计记录一经写入不可修改；如需更正请追加一条新的审计事件。',
                OLD.id
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;

    -- DELETE 仅在保留期清理任务的显式豁免下放行。
    IF (TG_OP = 'DELETE') THEN
        IF current_setting('aster.audit_retention_job', true) IS DISTINCT FROM 'on' THEN
            RAISE EXCEPTION
                'audit_logs 为 append-only：禁止 DELETE（记录 id=%）。'
                '保留期清理必须在事务内显式声明：SET LOCAL aster.audit_retention_job = ''on''。',
                OLD.id
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;

    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION reject_audit_log_mutation() IS
    '审计表 append-only 守卫：拒绝一切 UPDATE；DELETE 仅在 SET LOCAL aster.audit_retention_job=''on'' 的事务内放行。';

DROP TRIGGER IF EXISTS trg_audit_logs_append_only ON audit_logs;
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_log_mutation();

-- 语句级：整表清空同样禁止（TRUNCATE 不触发行级触发器）。
CREATE OR REPLACE FUNCTION reject_audit_log_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'audit_logs 为 append-only：禁止 TRUNCATE。'
        '整表清空会抹除全部审计链；如需按保留期清理请逐行 DELETE 并声明 '
        'SET LOCAL aster.audit_retention_job = ''on''。'
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_logs_no_truncate ON audit_logs;
CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_audit_log_truncate();
