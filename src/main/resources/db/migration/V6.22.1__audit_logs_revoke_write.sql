-- 审计表写权限收缴（2026-08-17 安全审计，第 1 层）
--
-- 三层防御中的第 1 层。层 2（触发器 V6.22.0）已能拒绝一切 UPDATE/DELETE/TRUNCATE，
-- 但触发器可被有 ALTER 权限者 DISABLE。
-- 权限层与之互补：即便触发器被关，无 UPDATE/DELETE 权限的角色依然写不动。
-- 反过来，权限层挡不住超级用户，那由触发器兜住。
-- 两层叠加后，对**非 owner 的普通角色**而言，改写审计记录需要显式的、
-- 可审计的特权操作，而非顺手一条 SQL。
--
-- 生产事实（已核实 k3s 清单）：
--   应用连接角色 = aster_api_user（CNPG managed role，非超级用户：
--   managed.roles 下没有 superuser/createdb/createrole）。
--   application.properties 的 DB_USERNAME 默认值 postgres 在生产不生效，
--   被 Deployment 的 QUARKUS_DATASOURCE_USERNAME（来自 Vault）覆盖。
--   同一角色也跑 Flyway migration，故只收表级 DML 权限，不动 DDL 能力。
--
-- 实测边界（PG 16，开发时逐条验证）：
--   1) 对非超级用户显式 REVOKE 有效——该角色 UPDATE 会得到
--      ERROR: permission denied for table。
--   2) 对超级用户 REVOKE 无效——superuser 绕过一切权限检查。
--      这正是必须同时有触发器（层 2）的原因：实测超级用户在触发器下 UPDATE 仍被拒绝。
--   3) 反向验证：ALTER TABLE ... DISABLE TRIGGER 关掉层 2 后，层 1 依然拦住
--      aster_api_user（permission denied），而 INSERT 正常——两层互补，缺一不可。

-- 用一个具名函数承载逻辑，避免匿名 DO 块（Flyway 的 SQL 解析器对本文件的匿名块
-- 报 "Unable to parse statement"；具名函数与 V6.22.0 的写法一致，实测可解析）。
CREATE OR REPLACE FUNCTION apply_audit_logs_write_revoke()
RETURNS void
LANGUAGE plpgsql
AS $audit_revoke$
DECLARE
    app_role CONSTANT TEXT := 'aster_api_user';
    seq_name TEXT;
BEGIN
    -- 幂等：本地 dev / CI 通常没有该角色，此时整体 no-op。
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE NOTICE 'audit_logs: 角色 % 不存在，跳过权限收缴（本地/CI 环境预期如此）', app_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM %I', app_role);
    -- 保留 INSERT/SELECT：审计写入与查询必须继续工作
    EXECUTE format('GRANT INSERT, SELECT ON audit_logs TO %I', app_role);

    -- 序列权限：INSERT 需要取 id（BIGSERIAL）
    SELECT pg_get_serial_sequence('audit_logs', 'id') INTO seq_name;
    IF seq_name IS NOT NULL THEN
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I', seq_name, app_role);
    END IF;

    RAISE NOTICE 'audit_logs: 已收缴 % 的 UPDATE/DELETE/TRUNCATE 权限，保留 INSERT/SELECT', app_role;
END;
$audit_revoke$;

SELECT apply_audit_logs_write_revoke();

-- 函数用完即弃：它只在本次迁移执行一次，留着反而给了「再调一次改权限」的入口。
DROP FUNCTION apply_audit_logs_write_revoke();

-- ============================================================================
-- ★★ 生产部署前置条件（当前**尚不满足**，本迁移的效果因此受限）
-- ============================================================================
--
-- 本层要真正成立，audit_logs 的 **owner 必须不是应用连接角色**。
--
-- 实测（PG 16，全程非超级用户）：表 owner 只需两条 DDL 即可同时解除层 1 与层 2：
--     GRANT UPDATE ON audit_logs TO <self>;             -- owner 可给自己重新授权
--     ALTER TABLE audit_logs DISABLE TRIGGER trg_...;   -- owner 可关掉自己的触发器
--     UPDATE audit_logs SET performed_by = '...';       -- 成功
-- owner 还可直接 CREATE OR REPLACE 守卫函数为空实现（更隐蔽，无需两步）。
--
-- 而生产当前让 **同一个 aster_api_user 既跑 Flyway 迁移、又作应用运行时连接**，
-- 因此它就是表 owner —— 层 1 与层 2 对它形同虚设，实际防线只剩层 3（锚定）
-- 能在事后发现改动。
--
-- 收口方案（属部署侧改动，需单独一轮）：
--   1. 新建 DDL 专用角色 aster_migrator，由它跑 Flyway 并持有各表 owner；
--   2. aster_api_user 降为纯运行时角色：仅 INSERT/SELECT，非 owner、无 ALTER；
--   3. 两者密码分别管理，运行时凭据泄露不再等于可改写审计。
--
-- 在此之前，对外能力声明应表述为「任何改动可被检测」，
-- 而非「常规路径无法改动」——后者需要上述 owner 分离才成立。
