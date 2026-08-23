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

以下三个文件的回滚块是**可执行 SQL**，属历史遗留。按铁律一，它们**不能被修改**，
其影响已由前向补偿迁移 `V6.25.0__ensure_dynamic_policy_objects.sql` 幂等兜底：

| 文件 | 自毁内容 |
|---|---|
| `V6.1.0__add_dynamic_policy_fields.sql` | DROP 掉自己刚加的 5 个字段 + 1 个索引 |
| `V6.2.0__create_policy_catalog.sql` | DROP TABLE 掉自己刚建的 `policy_catalog` |
| `V6.3.0__create_policy_artifacts.sql` | DROP TABLE 掉自己刚建的 `policy_artifacts` |

该缺陷**已真实发生过一次**——`V6.4.0__recreate_policy_catalog_artifacts.sql`
的标题就是「修复先前脚本中意外回滚导致的缺失」。

## 铁律三：新建表/字段的定义要有唯一权威来源

`policy_catalog` / `policy_artifacts` 的权威定义在 `V6.4.0`。补偿迁移里重复声明时，
必须**逐字段对齐**（含 UUID 主键、外键、唯一约束、索引列顺序）。

若两处不一致，在「只回放到某个中间版本」的路径上会建出与现网**貌合神离**的 schema，
比缺表更难排查。

## 本地验证方式

改迁移前后，在临时库上整链回放一遍，确认最终状态符合预期：

```bash
docker exec aster-postgres psql -U postgres -c "DROP DATABASE IF EXISTS mig_probe;" -c "CREATE DATABASE mig_probe;"
cd src/main/resources/db/migration
for f in $(ls V*.sql | sort -V); do
  docker exec -i aster-postgres psql -U postgres -d mig_probe -v ON_ERROR_STOP=1 -q < "$f"
done
```

注意：含 `${...}` placeholder 的迁移（如 `V6.13.0`）在裸 psql 下会报语法错误，
那是 Flyway 变量未被替换所致，**不是迁移本身的缺陷**。
