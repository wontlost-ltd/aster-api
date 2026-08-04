# Strategy Replay 能力差距分析与实施计划

**日期**：2026-08-05
**触发**：产品定位讨论 —— 若 Aster Cloud 定位为 Replayable Strategy Engine，Replay 应是核心而非附加功能。

---

## 一、现状盘点（基于代码实证，非推测）

| 能力 | 现状 | 证据 |
| --- | --- | --- |
| 单次执行可回放性判定 | ✅ 已上线 | `ReplayMetadata`（REPLAYABLE / NON_REPLAYABLE + reasons） |
| canonical hash 三件套 | ✅ 已上线 | `canonicalInputHash` / `canonicalOutputHash` / `traceHash` |
| 历史执行留存 | ✅ 已上线 | `Execution` 表 40+ 字段，含 input/output/policyVersion/aliasSet/vocabSnapshotRef |
| 输入明文留存（回放前提） | ⚠️ 按租户开关 | `replayRetentionEnabled`；未开则 `inputJson=null` |
| 步骤级 trace | ✅ 已上线 | `traceJson` + M2.1b |
| 双引擎 parity | ✅ 已上线 | `runnerParityStatus` |
| **回归用例/报告** | ❌ **仅 schema，零实现** | `RegressionCase`/`RegressionReport`/`DriftApproval`/`UpgradeManifest` 四张表在 `schema.ts`，但 **无迁移、无 API、无 UI、无服务层**（全仓 grep 只命中 schema.ts 自身） |
| 批量回放 | ❌ 不存在 | `/api/**` 无 batch/replay/compare/simulate/diff 路由 |
| 版本对比（A/B diff） | ❌ 不存在 | 同上 |
| What-if 模拟 | ❌ 不存在 | 同上 |
| 优化建议 | ❌ 不存在 | 同上 |

### 关键判断

**地基已经打好，上层建筑没盖。**

`ReplayExecutionRequest(source, context, ...)` 是**单次**重放：给一份源码 + 一份输入，重新执行。
它**不是**"把过去一年的订单重新跑一遍"。四层模型里：

- 第一层 History → **部分具备**（能看单条执行的 trace，不能批量回放）
- 第二层 Compare → **不具备**
- 第三层 Simulation → **不具备**
- 第四层 Optimization → **不具备**

那四张回归表说明这个方向**已被设计过**（ADR 0030），但实现停在了 schema。这是最重要的发现：
不需要从零设计，需要把已冻结的契约落地。

---

## 二、与提案的差异（需要在动工前对齐）

提案把 Replay 描述为**业务指标模拟**："成交率 52% → 55%"、"坏账率变化"、"GMV 变化"。

现有地基做的是**决策一致性验证**：同一输入 + 同一策略 ⇒ 同一输出 hash。

**这两者不是一回事，工程量差一个数量级。**

| 维度 | 决策一致性（现有地基支持） | 业务指标模拟（提案描述） |
| --- | --- | --- |
| 问题 | 改策略有没有改变决策？ | 改策略赚不赚钱？ |
| 需要的数据 | 输入 + 输出 hash | 输入 + 输出 + **业务结果**（成交/坏账/GMV） |
| Aster 是否持有 | ✅ 持有 | ❌ **不持有** —— 平台只记录决策，不知道该决策事后是否成交 |
| 可交付性 | 高 | 需要客户回传结果标签 |

> **这是本次分析最关键的结论**：`Execution` 表里有 `decision`（APPROVED/REJECTED），
> 但**没有 outcome**（这笔贷款后来坏账了吗？这个报价客户接受了吗？）。
> 没有 outcome 就算不出"成交率 52%→55%"。
>
> 提案的第一/二层（History/Compare）用现有数据就能做。
> 第三/四层（Simulation/Optimization）**必须先让客户回传业务结果**，否则做出来只能显示
> "决策变了 12%"，回答不了"收入变了多少"。

---

## 三、分阶段实施计划

### Phase 1 — 批量回放 + 版本对比（Compare）

**目标**：回答"这次改动改变了哪些决策"。这是唯一用现有数据就能诚实交付的层。

1. **落地已冻结的回归契约**（四张表已有 schema，补迁移 + 服务层 + API）
   - `RegressionCase` 冻结：从 `Execution` 选候选（需 `replayabilityStatus=REPLAYABLE`）
   - `RegressionReport`：跑一批 case，对比 `expectedOutputHash` vs 实际
   - 复用已有的 `ReplayExecutionCore`，不新写执行路径
2. **批量回放端点** `POST /api/policies/:id/replay-batch`
   - 输入：版本 A、版本 B、case 集合（或时间范围筛 Execution）
   - 输出：`{ total, identical, changed, nonReplayable, diffs[] }`
3. **对比 UI**：决策翻转矩阵（APPROVED→REJECTED 有多少条）+ 逐条 diff 下钻

**验收**：拿一条真实策略的两个版本，跑 1000 条历史执行，能列出决策发生翻转的具体条目。

**风险**：
- `replayRetentionEnabled` 未开的租户 `inputJson=null` ⇒ 无法语义回放，只能比 hash。
  必须在 UI 上**显式标注**"N 条因未留存输入不可回放"，不能静默跳过（否则是假覆盖率）。
- 批量回放是重计算。需要限流 + 异步任务，不能同步阻塞。

### Phase 2 — 业务结果回传（Outcome Ingestion）

**目标**：补上做 Simulation 的前提数据。**没有这一步，第三/四层做不了。**

1. `POST /api/v1/executions/:id/outcome` —— 客户在决策落地后回传结果
   （`{ outcome: 'converted'|'defaulted'|..., value?: number, at: timestamp }`）
2. `ExecutionOutcome` 表 + 与 Execution 的关联
3. 报表：按策略版本聚合 outcome，得出真实转化率/坏账率基线

**验收**：能对一条策略输出"版本 3 的历史成交率 = X%"，且 X 来自客户回传而非推测。

### Phase 3 — What-if 模拟（Simulation）

有了 Phase 1（批量回放）+ Phase 2（outcome），才能诚实回答"距离改成 50km 收入变多少"：
对历史样本回放新策略 → 决策变化 → 按历史 outcome 加权 → 估算指标变化。

**必须明确标注这是估算**：基于"决策相同则结果相同"的假设，不考虑市场反馈。
这个假设在风控（坏账）比在广告（竞价会反馈）更成立，宣传时不能一概而论。

### Phase 4 — 优化建议（Optimization）

暂不规划。等 Phase 1-3 有真实使用数据后再评估——
在没有 outcome 数据的情况下讨论"AI 自动建议更好的策略"是空中楼阁。

---

## 四、对定位建议的看法

**赞同的部分**：
- "Safe Change" 比 "Replay" 更接近客户真实付费动机，这个洞察是对的
- 把 Replay 定位为策略生命周期的核心节点，而非附加功能，与现有地基投入一致
  （canonical hash、trace、parity 这些已经做的事，只有在 Replay 是核心时才划算）

**需要谨慎的部分**：
- **"Replay 一百万条历史决策"**：现在做不到，也不该现在宣传。批量回放尚未实现，
  且性能上限未测。宣传领先于能力会透支信任——尤其对银行客户。
- **"自动告诉你改这里成功率增加 7%"**：这需要 outcome 数据，而平台目前拿不到。
  在 Phase 2 落地前，这句话不能出现在任何对外材料里。
- **Drools/OPA 竞争分析**：它们确实没做好 Replay，但它们也不主打可审计。
  Aster 真正的差异化是**可信执行链**（hash 链 + 双引擎 parity + 审批治理），
  Replay 是这条链的自然延伸，不是独立卖点。建议叙事上让 Replay 服务于"可信"，
  而不是把"可信"换成"Replay"。

---

## 五、建议的下一步

按顺序，不建议并行：

1. **确认 outcome 数据可获得性** —— 这是 Phase 2/3 的生死问题，且答案不在代码里，
   在客户那里。如果客户不愿意回传业务结果，第三/四层就该从路线图里去掉。
2. **落地 Phase 1** —— 四张表的 schema 已冻结，是最低垂的果实，且独立于 outcome。
3. 定位与文案对齐到 Phase 1 能兑现的范围："Test every strategy before it reaches production"
   是诚实的；"Replay one million decisions in minutes" 目前不是。
