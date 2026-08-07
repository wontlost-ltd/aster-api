# ADR 0033：What-if 的回放对照模型（按需重求值，不落对照表）

- 状态：**SUPERSEDED / 实现已撤下**（2026-08-07 第十二轮结论）。模型论证与 S0 实测保留供重做参考；当前实现回 409。原 PROPOSED（2026-08-06。由 Phase 4 What-if 撤下后的接回需求提出；七轮交叉审查的收尾项）
- 决策者：用户已拍板「按需重求值，不落新表」
- 相关：ADR 0030（P0-A 决策级持久层 / 回放地基）、`.claude/review-report-round{4,5,6,7}.md`、`docs/api/outcome-ingestion.md`

---

## 0. 第十二轮结论：实现撤下，模型待重做

十二轮独立交叉审查后，Phase 4 的**实现**被撤下（route 回 409、UI 入口移除）。
Phase 4 四轮评分 **52 → 43 → 58 → 48**，无收敛趋势——每轮修完都冒出同级新问题，
这不是实现疏漏而是**路线在持续制造问题**。

**决定性的一条：成功子集带选择偏差。**
门槛允许「200 条发起、30 条成功」就对这 30 条出完整业务数字。但重跑失败
往往与输入/词汇/策略路径相关——若 80% 因某类输入失败，剩下 20% 就是被筛选过
的子群，不是随机样本。据此算出的正面率与金额可能方向正确而幅度全错，甚至
方向本身有偏。**这不是加 caveat 能解决的：它是用非随机样本冒充总体影响。**

其余结构性问题：route 同时承担授权、查询、词汇还原、并发、deadline、计数与估算；
`estimateWhatIf` 是为「逐条对齐」设计的纯函数，用它消费「部分成功的重跑」语义错位。

**保留的资产**：`estimateWhatIf` 纯函数（逻辑正确、测试完备）、en/zh/de/hi 四语文案、
下文的模型论证与 S0 性能实测；完整实现存于 `phase4-attempt-archive` 分支。

**重做方向**：需要独立的 replay run 模型——异步 `ReplayBatch`（运行与查询分离、
状态可持久化），或严格有界的同步形态（任何失败即 fail-closed，不对部分子集估算）。
两者都需重新送审。

---

## 1. 背景：Phase 4 为什么被撤下

Phase 4（What-if 影响估算）要回答「把策略从 v1 换成 v2，业务指标会怎样」。
原实现的做法是：查 v1 的执行、查 v2 的执行，按 `executionId` 逐条对齐。

**这个对齐在合法数据上不可能成立**（第四轮交叉审查发现，已用真 PostgreSQL 复核）：

```
Execution.id 是主键，policyVersion 是普通列 —— 一行只属于一个版本。
实测：同一 id 插两个版本 → duplicate key value violates unique constraint
```

于是 `newDecisions.get(executionId)` 恒为 miss，估算输出
`changed=0 / newlyRejected=0 / delta=0` —— **自信地宣称「改这个版本毫无影响」**。
比报错糟得多：它看起来是个结论。

更糟的是当时那条「成功路径」测试之所以绿，是把同一个 `executionId` 塞进
base 与 target 两个 mock 结果集——制造了数据库产生不出来的状态，属于自证。

**当前状态**：`/api/policies/:id/whatif` 一律返回 `409 REPLAY_REQUIRED`，
UI 入口撤下，面板组件已删除。纯函数 `estimateWhatIf` 与 en/zh/de/hi 四语文案保留
（逻辑正确且有测试覆盖，等数据模型定了直接复用）。

---

## 2. 关键发现：所需能力**已经存在**，不必等 M2

调研发现三件事，都已在真实 schema 上核实：

### 2.1 `Execution` 已持久化重放所需的全部输入

实测 migration 后的列（真 PostgreSQL 16 查 `information_schema`）：

```
input, locale, aliasSetJson, functionName,
policyVersionRowId, canonicalInputHash, replayabilityStatus
```

### 2.2 `PolicyVersion` 已持久化任意版本的源码

```
version, content, source, aliasSet
```

即「拿历史执行的输入，去跑另一个版本的源码」所需的两半，**都已落库**。

### 2.3 M1 的「重求值」回放路径已经可用

`src/lib/policy-execution-log.ts:11-13` 明确写着：

> `REPLAYABLE` 行的诊断（**非**不可回放原因）：本行有可信「重求值」回放路径
> （P0-A M1 用），但尚无 M2 完整自包含加密 capture（`replayPayload*`）。

且 `ReplayExecutionRequest`（`replay/.../ReplayExecutionRequest.java:20-27`）的签名是：

```java
public record ReplayExecutionRequest(
        String tenantId, String source, Object context,
        String functionName, String locale,
        Map<String, Object> vocabulary, Map<String, List<String>> aliasSet, ...)
```

`source` 是**参数**——它本来就支持「用同一批 context 跑不同源码」。

> **修正一个此前的说法**：撤下 Phase 4 时我在 route 注释里写「需要真回放（M2），
> 回放能力尚未上线」。更准确的说法是：**M2 的完整加密 capture 未上线，
> 但 M1 的重求值回放可用**，而 What-if 需要的恰恰是后者——
> 它要的是「同样的输入在新版本下判成什么」，不是「逐字节复现历史执行」。

---

## 3. 决策：按需重求值，不落对照表

### 3.1 模型

```
Execution(REPLAYABLE)                PolicyVersion(targetVersion)
  input / locale / aliasSetJson  +     source / aliasSet
                    ↓
            ReplayExecutor 现场重跑
                    ↓
          targetDecision（仅内存，不落库）
                    ↓
      estimateWhatIf(samples, newDecisions)
```

**关联键不是 `executionId`，而是「同一条 input」**。这是本 ADR 的核心：
不去伪造一个跨版本共享的 id，而是承认对照来自**重跑**，
关联关系是 `(sourceExecutionId → 用它的 input 在 targetVersion 上重跑的结果)`。

### 3.2 为什么不落 `ReplayComparison` 表

考虑过新建 `(sourceExecutionId, targetPolicyVersionRowId) → targetDecision` 表
并由后台任务预计算。放弃，理由：

- **失效语义复杂**：策略版本的 `source` 一旦变动，全部相关行要失效重算；
  vocabulary / aliasSet / toolchain 变动同理。维护成本远大于收益。
- **口径不如重跑诚实**：预计算表回答的是「上次算的时候是这样」，
  而重跑回答的是「现在用这批输入跑新版本就是这样」。后者更接近用户的问题。
- **零 schema 变更**：不新增表、不新增列、不需要迁移与回填。

代价是**每次查询有计算开销**，见 §4 的护栏。

### 3.3 样本口径（必须诚实回报）

分母是「平台记录到的 **REPLAYABLE** 执行」，比 Phase 1 漏斗的分母更窄：

| 层 | 含义 |
|---|---|
| `sampleSize` | 该版本下平台记录到的执行数 |
| `replayable` | 其中 `replayabilityStatus = REPLAYABLE` 的条数 |
| `replayed` | 实际重跑成功的条数 |
| `replayFailed` | 重跑失败的条数（编译失败/超时/运行时异常） |

**`replayed` 才是估算的真实分母。** 三个数字都必须随响应返回并在 UI 常驻——
与 Phase 1 的 `truncated` 同理：不标注地展示会让人以为是全量分析。

### 3.4 门槛：绝对条数与**代表性比例**双判（用户拍板）

只用绝对条数（如「≥30 条就给数字」）会漏掉一类危险情形：
某版本有 5000 条执行，但只有 40 条 REPLAYABLE 且重跑成功。
绝对数够了，可它只代表 0.8% 的样本——结论毫无代表性，却会显示成正常估算。

故改为**两个条件同时满足**才给数字：

| 判据 | 阈值 | 理由 |
|---|---|---|
| 绝对条数 | `replayed >= MIN_REPLAYED`（30） | 太少时统计本身没意义，沿用 `estimateWhatIf` 既有阈值 |
| 代表性比例 | `replayed / sampleSize >= MIN_COVERAGE`（0.2） | 防「大分母 + 小样本」——占比过低时结论不可外推 |

任一不满足 → `comparable:false` + 机器可读 `reason`：
- `INSUFFICIENT_REPLAYED`（条数不够）
- `INSUFFICIENT_COVERAGE`（占比过低）

两者分开而不是合成一个码：调用方与 UI 需要区分「再攒些数据就行」
和「这批执行大多不可回放，攒也没用」——后者要去开 `replayRetentionEnabled`，
是完全不同的动作。

**响应始终回报 `coverage`**（= `replayed / sampleSize`），即便可比也照报——
与 Phase 1 的 `truncated` 同理，口径必须常驻可见。

比例阈值取 0.2 是保守起点，不是推导出来的最优值；应做成可配置，
并在真实数据上校准后再固化。

---

## 4. 护栏（这些不是可选项）

1. **上限**：单次最多重跑 N 条（建议 200，低于漏斗的 2000——重跑比读库贵得多），
   超出即截断并回报 `truncated`。
2. **超时**：单条重跑设超时；整体设预算，超预算即停止并如实回报已跑条数。
3. **只跑 REPLAYABLE**：`NON_REPLAYABLE` 行直接跳过并计入 `replayFailed`，
   不做「尽力而为」的猜测重跑。
4. **租户隔离**：重跑读 `Execution.input` 与 `PolicyVersion.source` 两处，
   **两处都必须带 userId 过滤**（本仓多次出现同类跨租户读）。
5. **PII 边界**：重跑要读 `Execution.input`（**含业务数据**）。
   这是 Phase 4 与 Phase 1/2 的本质区别——漏斗和静态分析都是零 PII 的，
   What-if 不是。因此：
   - 重跑结果**只取 decision**，不落库、不进日志、不进 trace
   - 重跑结果只取 decision，不落库、不进日志、不进 trace
   - **★必须由用户显式开关授权**（用户拍板）：复用既有的
     `User.replayRetentionEnabled`（`schema.ts:298`，默认 `false`，
     其文档已写明「默认关=不留明文，须显式授权」）。
     未开启时端点返回 `403 REPLAY_RETENTION_DISABLED` 并说明如何开启——
     **不是**静默降级成空结果，那会让用户以为功能坏了。
   - ⚠️ **修正（第八轮审查）**：上面这段论证**是错的**。`Execution.input` 是
     **无条件写入**的（见 dashboard/v1 两条 execute 路径），
     `replayRetentionEnabled` 只约束回归工具冻结的 `inputJson`，
     并不控制「是否保存明文」。「没开的租户物理上也无法重跑」不成立。
   - 修正后的理由：仍复用它，但语义是**「是否授权把已存在的明文用于重跑分析」**
     ——一个**使用授权**，不是存储开关。它是仓内唯一表达「用户对明文回放的
     显式许可」的字段，新建第二个会造成两个真相源。
   - **遗留待办**：该字段当前没有用户可达的设置入口（UI/API 均无写入口），
     且语义扩展需要明确：关闭后如何处理历史输入、开启是否追溯授权既有数据。
   - 文档必须写明「本端点会读取历史执行的输入数据」
6. **失败不静默**：重跑失败必须计入 `replayFailed` 并可分类，不能当成「决策未变」——
   那会系统性低估 `changed`。

---

## 5. 分阶段交付

| 阶段 | 内容 | 出口判据 |
|---|---|---|
| **S0 Spike** | ✅ **已完成**（见 §5.1） | 单条 1.35ms、成功率 100%，远优于判据 |
| **S1 后端** | `/whatif` 从 409 改为真实实现；护栏 §4 全部到位；`comparable` 改为严格判别联合 | route→真库 E2E（含跨租户、截断、重跑失败三类）；变异验证 |
| **S2 前端** | 重建面板（原组件已删除，因其 `?? 0` 兜底会把字段缺失渲染成 0）；四语文案复用 | 三条硬约束：assumption 与数字同屏、两档置信度分列、无金额基线显示「无法估算」而非 0 |
| **S3 收口** | 更新 `docs/api/`；ADR 转 ACCEPTED | 独立交叉审查通过 |

### 5.1 S0 实测结果（2026-08-06）

`WhatIfReplaySpikeTest` 用真实 `DynamicCnlExecutor` 跨版本重放
（v1 门槛 600 → v2 门槛 700），预热 20 次后测 200 条：

```
N=200  ok=200  failed=0  changed=100
avg=1.35ms   max=5.44ms   200 条总耗时=270ms
判据(<100ms) = PASS
```

**结论：§3.2「不落对照表」的前提成立，且余量极大**（快出判据 74 倍）。
200 条重跑总计 0.27 秒，与一次普通 DB 查询同量级；
预计算表在这个量级上属于过度设计，其失效语义复杂度换不来任何收益。

`changed=100/200` 符合预期——门槛从 600 提到 700，恰好一半样本翻转，
说明重放确实反映了版本差异而不是空跑。

**保留的保守性**：护栏 §4 的 200 条上限与超时不因此放宽。
上述测量用的是**单条规则**；真实策略更复杂（多模块、跨模块引用、
大 context），单条成本会更高。S1 落地时应对真实策略再测一次。

---

**S0 曾是硬前置**：§3.2 的取舍建立在「重跑足够快」这个假设上。
现已用真实数字证实，可以往下走。

---

## 6. 不做的事

- **不新建对照表**（§3.2）。
- **不伪造跨版本共享 id**——那正是被撤下的原实现的错误。
- **不做 A/B 分流口径**：那是比较两个 cohort 的率与分布，是另一种产品，
  需要另建 experiment/cohort 端点，不能沿用本 ADR 的逐条对照契约。
- **不在 `replayed` 不足时给数字**（§3.3）。

---

## 7. 待拍板

1. ~~S0 的判据阈值~~ → 已完成，实测 1.35ms 远优于判据（§5.1）。
2. ~~`replayed` 的最低门槛~~ → **已定：绝对条数 30 + 代表性比例 20% 双判**（§3.4）。
   比例阈值 0.2 是保守起点，需在真实数据上校准。
3. ~~授权层级~~ → **已定：复用 `User.replayRetentionEnabled` 显式开关，
   未开返回 403**（§4.5）。

**第八/九轮修订记录**：
- ~~vocabulary 无需解析~~ → **该判断是错的（第九轮纠正）**。我第八轮只查了
  dashboard 与 v1 两条普通 execute 路径，漏了
  `secure-execute → loadVocabularyForExecution → evaluateSource(vocabulary)`
  这条真实生产链路。现已接 `loadVocabularyForExecution`，与生产一致。
- ~~`replayRetentionEnabled` 无写入口~~ → **已补**
  `GET/PATCH /api/user/replay-retention` + 设置页开关卡片。
- **覆盖率口径修正（第九轮 P0-9）**：分母从「全量可重跑数」改为
  「本次计划重跑数」。用全量做分母存在数学冲突——`MAX_REPLAY=200` 时
  `replayableTotal>1000` 的策略 coverage 恒 ≤20%，门槛结构上达不到，
  **越大的客户越用不了**。新口径衡量的是**重跑成功率**；样本代表性由
  `sampleSize / replayable / limit` 三个数字如实呈现，交用户判断。
- **`simulate` 必须绑 HMAC（第九轮）**：它是免计费开关，信任裸 query boolean
  等于让任何外部调用方白嫖配额。已与 `replayCapture` 同门控。

**第十一轮修订（最致命的一条）**：
- ★**重跑失败的行曾进入估算基线**。它们没有对照决策，`estimateWhatIf` 当成
  「决策未变」——对 `changed` 无贡献，却把自己的 outcome/value 算进基线。
  实测 40 成功 + 10 失败时 `estimatedValueDelta=+4800`，只用成功的 40 条则是
  `-4000`，**方向直接翻转**。这不是精度问题，是把「不知道」当成「没变化」。
  已改为只用 `newDecisions.has(id)` 的成功行。
- **统计契约守恒**：拆出 `planned / started / succeeded / failed / notStarted`，
  满足 `planned = started + notStarted`、`started = succeeded + failed`。
  `replaySuccessRate = succeeded / started`（分母不含未启动的，
  否则「跑得慢」会被误判成「跑得不对」）。
- **taxonomy fail-fast**：移到任何重查询与重跑之前，缺词汇时零 `evaluateSource` 调用。
- **vocabulary fail-closed**：refs 非空却加载失败时返回 503，不降级内置词汇——
  降级会让重跑用与真实执行不同的术语解析，产出不可信的对照决策。
- **贯通取消**：`AbortSignal` 从 route 的 deadline 一路传到 `fetch`，
  deadline 到时真正断开在途请求，而不是只让 route 提前返回。
- **UI 两个比率分行展示**：代表性（占全量可重跑数）与可靠性（已发起的成功率）
  分母不同，拼成一句话会被误读。
- **PATCH 用 returning 回读**：零行更新返回 404 而不是假装成功。

**新的待定项**：
- `MIN_COVERAGE=0.2` 需要真实数据校准；上线后应观察实际 REPLAYABLE 占比分布再固化。
- 是否要让租户自定义这两个阈值？倾向**不要**——阈值可调等于让用户把
  「不可信的结论」调成可信，与本 ADR 的诚实口径原则冲突。
