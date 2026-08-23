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
--   改已应用迁移的内容会改变其 checksum（Flyway 用**逐行 CRC32**，不是文件哈希，
--   所以哪怕只追加一行注释也会变），而生产 Flyway 开着 validate-on-migrate。
--   本仓已经因此炸过一次——#55 修订已应用的 V5.2.0，导致 FlywayValidateException →
--   所有新 pod CrashLoopBackOff（详见 V6.13.0 文件头）。事故后确立的规矩是：
--   **已应用的迁移一律不改，需求用前向补偿迁移满足**。本文件即遵循该规矩。
--
-- 本迁移做什么（**请先读下面这段「有效性边界」，不要高估它**）：
--   把 V6.1.0/V6.2.0/V6.3.0 本应留下的对象幂等声明一遍，全部 IF NOT EXISTS。
--
-- ★★ 有效性边界：本迁移在**所有可达路径上都是 no-op**，是防御性声明而非实际修复。
--   交叉审查（2026-08-23）实测证伪了「能补齐只回放到 6.3.0 的库」这一说法：
--     V6.1.0 的自毁块删掉 policy_versions.tenant_id，而 V6.8.0 / V6.9.0 / V6.11.0
--     都**无保护地引用该列**（`CREATE INDEX IF NOT EXISTS` 只保护索引名，不保护列引用）。
--     真 Flyway 实测：链条在 6.8.0 硬失败
--       ERROR: Migration to version 6.8.0 failed — SQL State 42703
--       Message: column "tenant_id" does not exist
--     因 6.8.0 < 6.25.0，按版本序执行时**本迁移根本没机会运行**。
--
--   即真实收敛场景只有两个，且都本就成立：
--     - 全新部署：V6.4.0 已补回 → 全部 skipping，no-op
--     - 现网：对象俱在 → no-op
--
--   那为什么还留着它？两点价值：
--     1. 若将来 6.8/6.9/6.11 补上列存在性守卫，本迁移即成为真正的兜底
--     2. 把「这三个文件有自毁块」这件事钉在迁移链里，配合同目录 README.md 防止重蹈覆辙
--   真正的防护是 README.md 里的编写规约，不是这个文件。
--
--   全部使用 IF NOT EXISTS，无 DROP、无 DML，重复执行安全。
--
-- ★ 表结构定义**逐字段对齐 V6.4.0**（该文件是这两张表的权威定义），
--   包括 UUID 主键、外键、唯一约束与索引列顺序。若两处不一致，
--   在「只回放到 6.3.0」的路径上会建出与现网**貌合神离**的 schema，
--   比缺表更难排查——故此处不做任何「看起来更合理」的改良。
--
-- ★ 本文件不含任何「回滚」DDL。若确需回滚，请另加前向迁移，不要在此处写 DROP。

-- 1) policy_versions 的动态加载字段（V6.1.0 本应留下）
--
-- ★ 这里**故意不声明 source_hash**，与 V6.4.0 的写法不同，理由如下：
--   source_hash 不是 V6.1.0 新增的列——它由 V6.0.0__truffle_security.sql 创建，
--   并在那里回填了**真实 SHA-256**（`encode(digest(content,'sha256'),'hex')`），
--   还用 LAG 构造了 prev_hash 形成**链式信任**（见 V6.0.0 第 19-20、76-92 行注释）。
--   V6.1.0 的自毁块把这个**已带真实数据的列**一并 DROP 掉了。
--
--   V6.4.0 用 `DEFAULT '000…0' NOT NULL` 把它加回来——这会给所有历史行填入全零占位，
--   而 prev_hash 仍指向前驱的**真实**哈希，于是哈希链静默断裂且不再可验证：
--   「hash 对得上」会变成假信号。对以可信审计为核心卖点的系统，这比缺列更危险。
--
--   本迁移不重复这个动作：若某天真需要补这一列，应由专门的迁移决定占位语义
--   （NULL 表示"未知/不可信"，或显式 'UNKNOWN' 哨兵），并同步处理 prev_hash 链，
--   而不是在一个「幂等补建对象」的迁移里顺手伪造一个长度合法的 hash。
--   注：在列已存在的正常路径上，ADD COLUMN IF NOT EXISTS 本就是 no-op、连 DEFAULT
--   都不会应用，所以省略它不改变任何可达路径的行为。
ALTER TABLE policy_versions
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100),
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
