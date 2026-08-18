-- 审计表 owner 分离（2026-08-17 安全审计，让层 1/2 真正生效）
--
-- 背景：此前 Flyway 与运行时共用同一个 aster_api_user（migrate-at-start=true），
-- 因此该角色是各表的 **owner**。实测（PG 16，全程非超级用户）表 owner 只需两条 DDL
-- 即可同时解除审计表的 append-only 触发器与权限收缴：
--
--     GRANT UPDATE ON audit_logs TO <self>;            -- owner 可给自己重新授权
--     ALTER TABLE audit_logs DISABLE TRIGGER trg_...;  -- owner 可关掉自己的触发器
--     UPDATE audit_logs SET performed_by = '...';      -- 成功
--
-- 甚至可直接 CREATE OR REPLACE 守卫函数为空实现（一步且更隐蔽）。
-- 即：**运行时凭据泄露 == 可静默改写审计记录**，V6.22.0/V6.22.1 两层形同虚设。
-- 生产已核实命中该形态（audit_logs 的 tableowner = aster_api_user）。
--
-- 本迁移把审计相关对象的 owner 交给专用的 aster_migrator 角色。
-- 分离后实测三条攻击路径全部被拒：
--     自授权限     → WARNING: no privileges were granted（no-op）
--     关闭触发器   → ERROR: must be owner of table audit_logs
--     替换守卫函数 → ERROR: permission denied for schema public
-- 而应用角色的 INSERT/SELECT 正常，审计写入不受影响。
--
-- ★幂等且可跳过：本迁移在 aster_migrator 角色不存在时（本地 dev / CI）整体 no-op。
--   生产的角色由 CNPG managed.roles 创建（见 k3s cluster.yaml）。
--
-- ★执行身份：本迁移由 Flyway 运行。切换 QUARKUS_FLYWAY_USERNAME 后，
--   Flyway 本身就以 aster_migrator 连接——但**首次**部署时它尚未拥有旧表，
--   故 ALTER TABLE ... OWNER TO 需要当前 owner 或超级用户权限。
--   部署顺序见 k3s PR 说明：先由运维用超级用户执行一次 owner 移交脚本，
--   本迁移作为幂等兜底 + 新建表的归属声明。

CREATE OR REPLACE FUNCTION apply_audit_owner_separation()
RETURNS void
LANGUAGE plpgsql
AS $owner_sep$
DECLARE
    migrator_role CONSTANT TEXT := 'aster_migrator';
    app_role      CONSTANT TEXT := 'aster_api_user';
    obj           TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = migrator_role) THEN
        RAISE NOTICE 'owner 分离：角色 % 不存在，跳过（本地/CI 预期如此）', migrator_role;
        RETURN;
    END IF;

    -- 只在**当前执行者有权移交**时才做；否则留给运维脚本，本迁移不报错阻塞启动。
    -- （ALTER TABLE ... OWNER TO 要求执行者是当前 owner 或超级用户。）
    FOREACH obj IN ARRAY ARRAY['audit_logs', 'audit_chain_anchors']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_tables WHERE tablename = obj) THEN
            BEGIN
                EXECUTE format('ALTER TABLE %I OWNER TO %I', obj, migrator_role);
                RAISE NOTICE 'owner 分离：% 的 owner 已交给 %', obj, migrator_role;
            EXCEPTION WHEN insufficient_privilege THEN
                RAISE NOTICE 'owner 分离：无权移交 %（需由超级用户执行一次），已跳过', obj;
            END;
        END IF;
    END LOOP;

    -- 序列的 owner 同样要移交，否则应用角色仍可通过序列侧信道做手脚
    FOREACH obj IN ARRAY ARRAY['audit_logs_id_seq', 'audit_chain_anchors_id_seq']
    LOOP
        IF EXISTS (SELECT 1 FROM pg_class WHERE relname = obj AND relkind = 'S') THEN
            BEGIN
                EXECUTE format('ALTER SEQUENCE %I OWNER TO %I', obj, migrator_role);
            EXCEPTION WHEN insufficient_privilege THEN
                RAISE NOTICE 'owner 分离：无权移交序列 %，已跳过', obj;
            END;
        END IF;
    END LOOP;

    -- 移交 owner 后必须**重新授予**应用角色的运行时权限：
    -- owner 变更会带走「owner 隐式全权」，但显式 GRANT 仍然保留；
    -- 这里无条件重授一次，保证审计写入与查询在任何执行顺序下都不中断。
    EXECUTE format('GRANT INSERT, SELECT ON audit_logs TO %I', app_role);
    IF EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'audit_chain_anchors') THEN
        EXECUTE format('GRANT INSERT, SELECT, UPDATE ON audit_chain_anchors TO %I', app_role);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'audit_logs_id_seq' AND relkind = 'S') THEN
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE audit_logs_id_seq TO %I', app_role);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'audit_chain_anchors_id_seq' AND relkind = 'S') THEN
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE audit_chain_anchors_id_seq TO %I', app_role);
    END IF;

    -- ★再次收缴写权限：V6.22.1 执行时 app_role 还是 owner，REVOKE 对它无实际约束力
    --   （owner 隐式全权）。owner 移交后这次 REVOKE 才真正生效。
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM %I', app_role);
    IF EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'audit_chain_anchors') THEN
        EXECUTE format('REVOKE DELETE, TRUNCATE ON audit_chain_anchors FROM %I', app_role);
    END IF;

    RAISE NOTICE 'owner 分离完成：% 仅保留 INSERT/SELECT（锚点表另有受限 UPDATE）', app_role;
END;
$owner_sep$;

SELECT apply_audit_owner_separation();
DROP FUNCTION apply_audit_owner_separation();
