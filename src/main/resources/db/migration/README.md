# 数据库迁移编写规约

本目录下的每个 `V*.sql` 都由 Flyway 在应用启动时执行（`quarkus.flyway.migrate-at-start=true`）。

## 铁律一：已应用的迁移一律不改

**改动已应用迁移的内容会改变它的 checksum，导致生产启动直接失败。**

Flyway 的 checksum 是**逐行 CRC32**，不是文件哈希——所以哪怕只在末尾追加一行注释、
或修正一个错别字，checksum 同样会变。

本仓已经因此炸过一次：#55 修订了已应用的 `V5.2.0__seed_demo_workflow_data.sql`
（给每条 INSERT 加 placeholder），而生产开着 `validate-on-migrate` →
`FlywayValidateException` → **所有新 pod CrashLoopBackOff**。

事故后的正确做法记录在 `V6.13.0__prod_remove_demo_seed.sql` 文件头：
**V5.2.0 还原为原始内容，需求改由前向补偿迁移满足。**

需要修正一个已应用迁移的效果时，**新加一个迁移**，不要动老文件。

## 铁律二：回滚 SQL 必须整段注释掉

写「回滚」段落时，**每一行都要有 `--` 前缀**。只给标题行加注释是本目录出过的真实缺陷：

```sql
-- 回滚
DROP TABLE IF EXISTS policy_catalog;   -- ← 这一行是可执行 SQL，不是注释！
```

上面这段会让迁移「建完立刻自毁」。

正确写法见 `V4.3.0__add_tenant_id_to_anomaly_reports.sql`：

```sql
-- =============================================
-- Rollback SQL（仅在需要回滚时手动执行）
-- =============================================
-- DROP INDEX IF EXISTS idx_anomaly_reports_tenant_detected;
-- ALTER TABLE anomaly_reports DROP COLUMN IF EXISTS tenant_id;
```

### 已知的历史遗留（勿模仿，也勿修改）

以下三个文件的回滚块是**可执行 SQL**，属历史遗留。按铁律一，它们**不能被修改**：

| 文件 | 自毁内容 |
|---|---|
| `V6.1.0__add_dynamic_policy_fields.sql` | DROP 掉 4 个自己新增的字段 + **`source_hash`（V6.0.0 创建、已回填真实 SHA-256）** + 1 个索引 |
| `V6.2.0__create_policy_catalog.sql` | DROP TABLE 掉自己刚建的 `policy_catalog` |
| `V6.3.0__create_policy_artifacts.sql` | DROP TABLE 掉自己刚建的 `policy_artifacts` |

★ `V6.1.0` 那一项最严重：`source_hash` 并非它新增的列，而是 `V6.0.0__truffle_security.sql`
创建并回填了**真实哈希**、用于构造 `prev_hash` 链式信任的列。删掉它等于抹掉审计链的一环。

该缺陷**已真实发生过一次**——`V6.4.0__recreate_policy_catalog_artifacts.sql`
的标题就是「修复先前脚本中意外回滚导致的缺失」。

**注意 `V6.25.0` 的有效性边界**：它是**防御性 no-op**，不是实际修复。
`V6.1.0` 删掉 `policy_versions.tenant_id` 后，`V6.8.0`/`V6.9.0`/`V6.11.0` 都无保护地
引用该列（`CREATE INDEX IF NOT EXISTS` 只保护索引名，不保护列引用），
迁移链会在 **6.8.0 硬失败**（`42703 column "tenant_id" does not exist`），
版本更高的 `V6.25.0` 根本没机会运行。真正的防护是本文件的编写规约。

## 铁律三：新建表/字段的定义要有唯一权威来源

`policy_catalog` / `policy_artifacts` 的权威定义在 `V6.4.0`。补偿迁移里重复声明时，
必须**逐字段对齐**（含 UUID 主键、外键、唯一约束、索引列顺序）。

若两处不一致，在「只回放到某个中间版本」的路径上会建出与现网**貌合神离**的 schema，
比缺表更难排查。

## 本地验证方式

**必须用真 Flyway 引擎验证，不要用裸 psql 逐个跑。**

裸 psql 循环给出的是「SQL 能不能执行」的弱信号：它不校验 checksum、不走版本序、
不处理 `${...}` placeholder，因此**发现不了版本序类问题**——例如 V6.25.0 那个
「补偿迁移排在 6.8.0 之后所以永远跑不到」的缺陷，就是裸 psql 测不出、
真 Flyway 一跑就暴露的。

```bash
# 克隆一个带真实 flyway_schema_history 的库（勿直接改动 aster_policy）
docker exec aster-postgres psql -U postgres \
  -c "CREATE DATABASE mig_probe TEMPLATE aster_policy;"

# 用生产同款参数跑：validate 开、out-of-order 关
docker run --rm --network host \
  -v "$PWD/src/main/resources/db/migration:/flyway/sql:ro" flyway/flyway:10 \
  -url=jdbc:postgresql://localhost:15432/mig_probe \
  -user=postgres -password=postgres \
  -placeholders.asterDemoSeedEnabled=false \
  -validateOnMigrate=true -outOfOrder=false migrate

docker exec aster-postgres psql -U postgres -c "DROP DATABASE mig_probe;"
```

关键是看输出里的 **`Successfully validated N migrations`** —— 它证明既有迁移的
checksum 全部完好，即你没有动过任何已应用的文件。
