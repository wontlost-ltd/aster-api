# PR #234 第四轮独立审查报告（ADR 0034 §11）

**审查基线**：`fix/whatif-p0-batch` / `edd9eb8f7ec1465a09c324e6335f453976e0291e`，`git diff main...HEAD`  
**审查人**：Codex（独立审查者；生成者为 Claude）  
**综合评分**：**31/100**  
**建议**：**退回，不得合入 main**  
**品味评分**：需改进

## 执行摘要

本轮确实修复了 batch 行终态 CAS、兜底 owner 条件、reclaim CAS、历史迁移和缺失 source 的分类，但核心协议仍未闭环：`SEGMENT_SIZE=100` 的已声明最坏耗时是 50 分钟，lease 却只有 30 分钟；`runOneSegment` 在段首只续租一次，段内通过 managed `ReplayBatchItemEntity` 自动 flush 写结果，完全没有 owner/status 条件。真实 PG 交错实验得到：旧 worker 的 batch 终态 CAS 为 0 行，但其迟到 item UPDATE 仍成功，并能把 worker B 已完成的 `COMPLETED` 批次污染成含失败条目。

V6.20.3 的触发器同样不是 fail-closed：它只挂在 `replay_batch BEFORE UPDATE`，既不覆盖 batch INSERT，也不覆盖后续 item INSERT/UPDATE，而且“零 item”时 `NOT EXISTS` 仍允许 COMPLETED。故顶层设计声称“数据库回答谁能写/全部成功才 COMPLETED”，实际抓手只覆盖了 batch 行的一次状态更新。

## 一、上一轮八项逐条结论

1. **P0 owner 条件更新被 managed entity flush 绕过：未闭环（仍为 P0）**。batch 终态路径已经 detached/CAS（`ReplayBatchService.java:233-299, 387-400`），但 item 路径仍加载 managed entity并直接改字段（`ReplayBatchService.java:317-320,343-358`）。lease 在段首 CAS 一次（`309-315`），段执行期间过期后旧 worker 仍会在事务提交时无 owner 条件 flush item。真实双 owner 实验见下文：旧 A 的 batch CAS `UPDATE 0`，item 迟到写 `UPDATE 1`，最终 `COMPLETED + success=false`。
2. **P0 异常兜底不校验 owner：已修复**。调度器传 `claim.owner()`（`ReplayBatchScheduler.java:57-70`），兜底 UPDATE 同时限制 `id + leaseOwner + RUNNING`（`ReplayBatchService.java:409-424`）。缺口是没有真实 PG 行为测试。
3. **P0 reclaim 非原子的先查后写：batch 行层面已修复**。虽然仍先查（`171-184`），写条件包含读到的 owner、expiry、RUNNING（`187-205`），续租/改派后旧快照会写 0 行。缺口是源码测试只检查字面存在，没有数据库竞态测试。
4. **P0 2 小时 lease 不覆盖 83 小时：未闭环且形成新的确定性窗口（P0）**。改成分段方向正确，但代码自己算出 `100 × 30s = 50min`，随后却设 `LEASE_DURATION=30min`（`ReplayBatchService.java:52-66`）。段内没有心跳；第 31 分钟起可被 reclaim，而旧 worker 仍可 flush item。
5. **P0 V6.20.2 被已有 RUNNING 行阻断：已修复并实测通过**。迁移先将 `window_frozen_at IS NULL` 的 PENDING/RUNNING 置 FAILED，再加/验证约束（`V6.20.2:61-77`）。在 V6.20.1 与 V6.20.2 间插入 RUNNING 行后，V6.20.2 成功且该行变 FAILED。
6. **P1 freezeWindow 双副本竞态：基本闭环，但缺行为回归**。冻结表 PK `(batch_id, execution_id)`（`V6.20.2:22-35`）使两个并发冻结不可能各自提交不同集合； loser 事务会唯一冲突回滚，随后领取仍由 PENDING CAS 决胜（`ReplayBatchService.java:99-153,516-553`）。当前没有真实数据库双冻结测试，因此保留 P1 测试缺口。
7. **P1 unlimited 重试处在已失败事务里：原问题已修复**。失败事务内不再 persist 重试，而是 503 交调用方重试（`ReplayBatchResource.java:159-186`）。但“精确识别”仍不精确：任意 cause 的 SQLSTATE 23505 都返回 true，而不要求约束名为 tenant slot（`296-305`），可把别的唯一冲突误报为 slot contention，P1。
8. **P1 INPUT_INCOMPATIBLE 归类不诚实：指定场景已修复，通用分类仍有诚实性风险**。冻结成员在上游消失时改为 `SOURCE_EXECUTION_UNAVAILABLE`（`ReplayBatchService.java:343-351`）。然而其他未识别异常仍默认归为用户输入不兼容（`607-621`），缺少证据仍会把基础设施/编程错误归咎用户，保留 P1。

## 二、本轮新增风险

### P0：item 写不受 owner 协议保护，且触发器可被后写绕过

- `runOneSegment` 只在段首续租并验证 owner；随后最多 100 条串行执行（`ReplayBatchService.java:306-359`）。
- 每个 item 是 managed entity，字段赋值最终由 Hibernate 普通 UPDATE flush，无 `leaseOwner/status` 条件（`343-358`）。
- 旧 worker 可以在失租、B 领取乃至 B COMPLETED 后继续写 item。触发器只在 batch UPDATE 时检查一次，后续 item UPDATE 不触发它（`V6.20.3:57-78`）。

### P0：V6.20.3 触发器不是完整的 fail-closed

- 只定义 `BEFORE UPDATE ON replay_batch`，因此直接 INSERT COMPLETED 不检查（`V6.20.3:74-78`）。
- COMPLETED 后 INSERT/UPDATE 非成功 item 不检查。
- 没有 item 时 EXISTS 为 false，所以 planned_count>0、completed_count=planned_count 的 COMPLETED 行可在零 item 情况通过。
- 实测 `COMPLETED` 后把 success 从 true 改 false、再 INSERT failure item，均成功。

### P1：未知 failureKind 可使批次走异常兜底，丢失真实失败类别

`collectResults` 直接 `ReplayFailureKind.valueOf(...)`（`ReplayBatchService.java:362-376`）。历史值/回滚后枚举漂移会抛 `IllegalArgumentException`；scheduler 随即用 UNKNOWN 将整批标 FAILED（`ReplayBatchScheduler.java:63-70`）。这至少应被显式作为未知持久化协议处理，而不是异常旁路覆盖聚合结果。

### 嵌套事务结论

`runBatch` 对 `runOneSegment(REQUIRES_NEW)` 的同 bean 调用在本项目的 Quarkus ArC 下会触发 self-interception；Quarkus 官方 CDI reference §5.21 明确把 `@Transactional` 自调用列为受支持的非标准扩展（https://quarkus.io/guides/cdi-reference#intercepted-self-invocation）。因此 `runOneSegment` 会开启独立事务，其内部调用 `loadSnapshot(@Transactional REQUIRED)` 加入这个新事务，嵌套语义正确（`ReplayBatchService.java:306,325,286-299`），**不构成缺陷**。现有测试仍没有启动 Quarkus 容器验证分段提交，但这属于集成回归缺口，不能反向推断注解失效。

### attemptCount 结论

分段本身不会累加 attemptCount；只在 `markRunning` 的 PENDING→RUNNING CAS 中 `attemptCount + 1`（`ReplayBatchService.java:145-151`）。正常长批次多段不会误触 MAX_ATTEMPTS；只有失租并重新领取才增加。真正问题是当前 lease/segment 配置会让正常慢段频繁失租，从而间接消耗 attempts。

## 三、迁移验证

在独立 PG 库按 V6.20.0→V6.20.1→V6.20.2→V6.20.3 执行：空库四迁移均成功。另做历史数据升级：

```text
V6.20.0 后插入 PENDING 与 COMPLETED；V6.20.1：
...0001 | FAILED    | {}
...0002 | COMPLETED | null

V6.20.1 后插入 RUNNING(window_frozen_at 尚不存在)；V6.20.2 + V6.20.3：
...0001 | FAILED    | window_frozen_at null | {}
...0002 | COMPLETED | window_frozen_at null | null
...0003 | FAILED    | window_frozen_at null | []
```

四迁移连续通过；已有 COMPLETED 行不在创建触发器时被检查。迁移后对该历史 COMPLETED 做普通 UPDATE 也成功（它没有 item，触发器的 EXISTS 为 false），这反而验证了触发器“零 item 可通过”的漏洞。

## 四、实际双 worker / DB 实验

在独立 `review_check_agent` 应用四迁移后：建立 RUNNING(owner=A)+pending item；模拟回收/重领为 owner=B；执行 A 终态 CAS、B item 成功+B COMPLETED、最后 A 的迟到 item flush。输出：

```text
UPDATE 1   -- owner A -> owner B
UPDATE 0   -- A 的 batch 终态 CAS 被拒绝（这一层正确）
UPDATE 1   -- B 写 item success
UPDATE 1   -- B 写 COMPLETED
UPDATE 1   -- A 无 owner 条件的迟到 item 写
 status    | lease_owner | success | failure_kind
-----------+-------------+---------+-------------
 COMPLETED |             | f       | TIMEOUT
```

触发器绕过实验还得到：COMPLETED 后 `UPDATE replay_batch_item ... success=false` 为 `UPDATE 1`，`INSERT` 新失败 item 为 `INSERT 0 1`；最终 planned_count=1 的 COMPLETED 批次有两条失败 item。

## 五、测试真实性

实际运行：

```text
ReplayBatchLeaseAndSlotTest       tests=8 failed=0
ReplayBatchMigrationUpgradeTest  tests=6 failed=0
ReplayBatchPlannedCountTest       tests=6 failed=0
ReplayBatchResponseContractTest  tests=8 failed=0
```

这些全绿不能证明 owner 协议。`ReplayBatchLeaseAndSlotTest:88-165` 基本都 `Files.readString/contains/indexOf`；`ReplayBatchMigrationUpgradeTest:38-181` 全部扫描 SQL 文本；`ReplayBatchPlannedCountTest:25-27,60-165` 除两条纯 runner 行为外均扫源码。ResponseContractTest 直接调用 `describe`，属于真实纯函数行为测试，但不覆盖事务/DB。

必须补的真实 PG/Quarkus 集成测试：

1. A 在 segment 中阻塞超过 lease，reclaim+B 领取，验证 A 无法写 batch **也无法写任何 item**。
2. reclaim 与续租并发，验证只有匹配 owner+expiry 的 CAS 生效。
3. finishAtomically：错误 owner/非 RUNNING 更新 0，正确 owner 仅在全 item 成功时提交。
4. DB 约束覆盖 INSERT COMPLETED、COMPLETED 后 item INSERT/UPDATE、零 item 三条绕过。
5. freezeWindow 两副本并发，验证只提交一个一致冻结集合。
6. 真实事务集成测试确认 self-invocation 下 REQUIRES_NEW 每段提交，而非仅扫描注解字面。

## 审查五层法

### 第一层：数据结构（25%）— 8/25

batch owner 放在父表，却让子 item 的可变结果不携带 owner/generation，也没有 DB 关联约束。所有权模型只覆盖父行，不覆盖本协议真正的大量写入对象。

### 第二层：特殊情况（20%）— 7/20

终态 UPDATE 被保护，但 INSERT、终态后 item 后写、零 item、未知枚举均成为旁路。触发器的边界不是业务不变量的边界。

### 第三层：复杂度（25%）— 8/25

分段方向简化崩溃恢复，但同时引入 lease、managed item flush、父表触发器之间的时序耦合；且 50min/30min 的矛盾直接写在相邻注释与常量中。

### 第四层：破坏性（15%）— 3/15

可落出对外声称 COMPLETED、内部却含失败 item 的不可恢复矛盾状态；旧 worker 能污染新 owner 结果，属于协议级破坏。

### 第五层：可行性（15%）— 5/15

问题真实且已由 PG 实验复现。当前方案复杂度与目标匹配，但护栏位置错误；需要把 owner/generation 校验下沉到 item 写或以不可绕过的 DB 模型保证。

## 致命问题与最小阻断项

**致命问题**：正常合法耗时可超过 lease，失租旧 worker 的 managed item flush 不受 owner/status 保护；V6.20.3 又允许 COMPLETED 后 item 后写，真实产生 `COMPLETED + failure item`。

**最小阻断项**：

1. 让每段最坏耗时严格小于 lease（或段内可靠续租），并把 item 结果写改成受当前 owner/generation 约束的原子写；写 0 行必须整体让位。
2. DB fail-closed 必须覆盖 batch INSERT 以及 COMPLETED 前后 item INSERT/UPDATE，且验证 item 数量等于 planned_count、全部 success=true；不能只做 batch BEFORE UPDATE 的一次 EXISTS。
3. 用真实 PG 双 worker 集成测试锁住上述两项，再谈合入。

在这些阻断项闭环前，**明确建议退回**。
