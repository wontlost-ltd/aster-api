-- 【仅测试】审计表篡改模拟通道
--
-- ★本文件位于 db/migration-test，**只在 %test profile 下加载**
--   （见 application.properties 的 %test.quarkus.flyway.locations）。
--   生产库永远不会执行它，因此生产的 audit_logs 对 UPDATE 是**无条件拒绝**、
--   不存在任何 session 变量后门。
--
-- 为什么需要它：验证「篡改能被哈希链检出」这类用例，必须先真的改写一条审计记录。
-- 而 V6.22.0 的生产守卫对 UPDATE 无条件 RAISE EXCEPTION。
--
-- 为什么不直接在生产迁移里加一个豁免开关：那等于在生产库留后门——
-- 拿到应用数据库连接的攻击者（SQL 注入 / 凭据泄露）只要自己
--   SET LOCAL aster.audit_tamper_simulation = 'on';
-- 就能改写审计记录，整层保护形同虚设。
--
-- 版本号取 V9000.0.0：远高于任何真实迁移，确保它总是最后应用，
-- 覆盖 V6.22.0 定义的同名函数；且不会与未来的业务迁移版本号冲突。
--
-- 与保留期通道 aster.audit_retention_job **分开**是刻意的：
-- 两者性质不同，合用一个开关会让「清理任务」顺带获得改写审计记录的能力。

CREATE OR REPLACE FUNCTION reject_audit_log_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- 【测试专用】UPDATE 仅在显式声明篡改模拟的事务内放行
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

    -- DELETE 与生产口径一致：仅保留期清理通道放行
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
    '【测试环境】append-only 守卫：UPDATE 需 aster.audit_tamper_simulation=on；DELETE 需 aster.audit_retention_job=on。生产版本（V6.22.0）对 UPDATE 无条件拒绝。';
