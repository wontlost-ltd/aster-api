-- Phase 6.25: 幂等补建动态策略相关对象（前向补偿迁移）
-- 作者: Claude Code
-- 日期: 2026-08-23
-- 目的: 消除 V6.1.0 / V6.2.0 / V6.3.0 三个迁移「回滚块是可执行 SQL」留下的隐患。
--
-- 背景（第三轮全量审计发现，本地 Postgres 实测非静态推理）：
--   上述三个文件末尾都有一段以 `-- 回滚` 起头的 DDL，但**只有那一行是注释**，
--   下面的 DROP 语句是**可执行 SQL**。于是每个文件都「建完立刻自毁」：
--     V6.1.0：给 policy_versions 加 5 个动态字段 + 1 个索引，然后全部 DROP
--     V6.2.0：建 policy_catalog + 索引，然后 DROP TABLE
--     V6.3.0：建 policy_artifacts + 索引，然后 DROP TABLE
--   同目录的 V4.3.0 是**正确写法**——整段回滚 SQL 都用 `--` 注释掉。
--
--   该缺陷**已经真实发生过一次**：V6.4.0 的标题即「修复先前脚本中意外回滚导致的缺失」，
--   靠一个后补迁移把两张表救了回来。也就是说现网数据是好的，但那是侥幸自愈，不是设计如此。
--
-- 为什么不直接改那三个文件（**迁移不可变铁律**）：
--   改已应用迁移的内容会改变其 SHA-256，而生产 Flyway 开着 validate-on-migrate。
--   本仓已经因此炸过一次——#55 修订已应用的 V5.2.0，导致 FlywayValidateException →
--   所有新 pod CrashLoopBackOff（详见 V6.13.0 文件头）。事故后确立的规矩是：
--   **已应用的迁移一律不改，需求用前向补偿迁移满足**。本文件即遵循该规矩。
--
-- 本迁移做什么：
--   把 V6.1.0/V6.2.0/V6.3.0 本应留下的对象**幂等**补建一遍，使任何回放路径都收敛到同一状态：
--     - 全新部署：V6.4.0 已补回，全部 IF NOT EXISTS 跳过 → no-op
--     - 只回放到 6.3.0 的库：把缺失的表/字段/索引补齐
--     - 现网：对象俱在 → no-op
--   全部使用 IF NOT EXISTS，无数据变更、无破坏性操作，重复执行安全。
--
-- ★ 表结构定义**逐字段对齐 V6.4.0**（该文件是这两张表的权威定义），
--   包括 UUID 主键、外键、唯一约束与索引列顺序。若两处不一致，
--   在「只回放到 6.3.0」的路径上会建出与现网**貌合神离**的 schema，
--   比缺表更难排查——故此处不做任何「看起来更合理」的改良。
--
-- ★ 本文件不含任何「回滚」DDL。若确需回滚，请另加前向迁移，不要在此处写 DROP。

-- 1) policy_versions 的动态加载字段（V6.1.0 本应留下）
--    source_hash 的 DEFAULT + NOT NULL 与 V6.4.0 一致：
--    对已有行需要一个确定性的占位 hash，否则 NOT NULL 无法加上。
ALTER TABLE policy_versions
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64) DEFAULT '0000000000000000000000000000000000000000000000000000000000000000' NOT NULL,
    ADD COLUMN IF NOT EXISTS core_json JSONB,
    ADD COLUMN IF NOT EXISTS compiler_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS locale VARCHAR(10);

CREATE INDEX IF NOT EXISTS idx_tenant_module_function
    ON policy_versions(tenant_id, module_name, function_name);

-- 2) policy_catalog（V6.2.0 本应留下；V6.4.0 已补建，此处兜底）
CREATE TABLE IF NOT EXISTS policy_catalog
(
    id                  UUID PRIMARY KEY,
    tenant_id           VARCHAR(100)  NOT NULL,
    module_name         VARCHAR(200)  NOT NULL,
    function_name       VARCHAR(200)  NOT NULL,
    domain              VARCHAR(50),
    tags                JSONB,
    default_version_id  BIGINT,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    CONSTRAINT policy_catalog_unique_per_tenant UNIQUE (tenant_id, module_name, function_name),
    CONSTRAINT policy_catalog_default_version_fk FOREIGN KEY (default_version_id) REFERENCES policy_versions (id)
);

CREATE INDEX IF NOT EXISTS idx_catalog_tenant
    ON policy_catalog (tenant_id);

-- 3) policy_artifacts（V6.3.0 本应留下；V6.4.0 已补建，此处兜底）
CREATE TABLE IF NOT EXISTS policy_artifacts
(
    id                UUID PRIMARY KEY,
    policy_version_id BIGINT       NOT NULL,
    artifact_type     VARCHAR(50)  NOT NULL,
    content           BYTEA,
    content_sha256    VARCHAR(64),
    compiler_opts     JSONB,
    created_at        TIMESTAMP    NOT NULL,
    CONSTRAINT policy_artifacts_version_fk FOREIGN KEY (policy_version_id) REFERENCES policy_versions (id)
);

CREATE INDEX IF NOT EXISTS idx_artifacts_version
    ON policy_artifacts (policy_version_id, artifact_type);
