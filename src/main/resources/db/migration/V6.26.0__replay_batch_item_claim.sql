-- replay_batch_item 认领标记（issue #302 C1 的前置）
--
-- ★为什么需要：ReplayBatchService.runOneSegment 目前用**单个事务**包住
--   「读待办 → 两次出站 HTTP → 最多 10 次策略执行 → 写回」，全程钉住一条
--   JDBC 连接。按本类 SEGMENT_WORST_CASE 的算式，最坏耗时为
--     SEGMENT_FETCH_TIMEOUT_MS(15s) + SEGMENT_SIZE(10) × (PERMIT_WAIT_MS(30s)
--     + EXEC_TIMEOUT(60s)) = 915s ≈ 15.25 分钟。
--   而 jdbc.max-size=8，8 个并发 segment 即可耗尽整个 pod 的连接池。
--
--   修法是把事务拆成「短事务读 → 无事务跑 → 短事务写」。但直接拆会引入
--   **重复处理**：原实现的 read-then-write 原子性由事务隔离隐式保证，
--   拆开后中间是分钟级的无事务窗口，并发 worker 会读到同一批待办。
--   ★这是**拆分实现上的预期失败**，不是当前代码的状态：
--     ReplayBatchConcurrencyIT 断言每条 execution 恰好被拉取 1 次，
--     在当前单事务实现上是绿的。第二步拆事务时，若不先落地认领标记，
--     该断言就会红——认领字段正是为让它保持绿而加。
--
--   ★行锁救不了：PESSIMISTIC_WRITE 的锁在读事务提交时就释放，
--     **恰恰不覆盖那个无事务窗口**。必须有持久化的认领标记。
--
-- 本迁移只加列与索引，不改任何现有行为；拆分事务是**后续独立改动**。
--
-- ★锁影响（评估过，判定可接受）：
--   ADD COLUMN 取 AccessExclusiveLock、CREATE INDEX（非 CONCURRENTLY）取
--   ShareLock 阻塞写。判定影响低的三点依据：两个新列都**可空且无 DEFAULT**，
--   PG 11+ 不重写表，只是毫秒级元数据操作；新索引是**部分索引**，
--   只覆盖少数可认领行；本表按批次写入（MAX_BATCH_SIZE=10000）而非持续高频写。
--   遵循本仓惯例用普通 CREATE INDEX（见 V6.16.0 的同款说明）——
--   生产若已积压大量行，由 DBA 在低峰窗口用 CONCURRENTLY 单独建后再对齐
--   本迁移的 IF NOT EXISTS。
ALTER TABLE replay_batch_item
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN replay_batch_item.claimed_by IS
    '认领该条目的 worker（= replay_batch.lease_owner）。NULL 表示未被认领。';
COMMENT ON COLUMN replay_batch_item.claimed_at IS
    '认领时刻。与 claimed_by 一同写入；用于识别认领后崩溃的僵尸条目。';

-- ★认领查询的索引：(batch_id, execution_id) WHERE 可认领。
--
--   既有 replay_batch_item_pending_idx 是 (batch_id) WHERE success IS NULL，
--   服务于当前 ReplayBatchService:414 的 `batchId = ?1 and success is null
--   order by executionId`。**本迁移不动它** —— 拆分事务是后续独立改动，
--   在那之前该查询仍是活的，收紧它的谓词会让现存查询当场失去索引，
--   把一个纯增量迁移变成性能回归。
--
--   ★为什么带上 execution_id（实测结论，非推理）：认领查询按 execution_id
--     排序取前 SEGMENT_SIZE 条。把它放进索引，规划器可直接按索引序取够 10 条
--     即停，无需排序。在 20000 行 / 19990 条已被他人认领的探针库上实测：
--
--       (batch_id)                → Sort + 186 buffers, 1.308 ms
--       (batch_id, execution_id)  →  无排序 +   6 buffers, 0.026 ms
--
--     即 50 倍差距。「已被他人认领的占绝大多数」正是拆事务后的常态
--     （多 worker 并发扫同一批次），故这是要优化的主场景而非边角。
--
--   ★谓词里的 claimed_by IS NULL 是关键：它让索引只含「尚可认领」的少数行，
--     已认领/已完成的行不进索引。故索引体积随待办数而非总行数增长。
CREATE INDEX IF NOT EXISTS idx_replay_item_claimable
    ON replay_batch_item (batch_id, execution_id)
    WHERE success IS NULL AND claimed_by IS NULL;

-- ★回收路径（worker 崩溃后释放其认领）**刻意不建索引**。
--
--   曾考虑建 (batch_id) WHERE claimed_by IS NOT NULL，实测证明不划算：
--   表上已有 replay_batch_item_batch_idx（(batch_id)，无谓词，V6.20.2），
--   回收查询 `batch_id = ? AND claimed_by IS NOT NULL` 用它即可定位。
--   在 45000 行 / 本批 10000 行全部已认领的探针库上实测：
--
--     仅既有索引  → Bitmap Index Scan on replay_batch_item_batch_idx, 54.6 ms
--     再加新索引  → Bitmap Index Scan on idx_replay_item_claimed,     54.9 ms
--
--   索引扫描本身只占 0.1 ms，UPDATE 的成本在写堆页上，换索引不改变量级。
--   而多一个索引就要在**每次条目写入**时维护 —— 批次运行期间每段都写。
--   故这里承担写放大却换不到可测收益，不建。
