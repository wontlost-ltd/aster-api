# Aster Cloud 能力差距分析与分阶段计划

**日期**：2026-08-05
**触发**：两轮产品定位讨论 ——
(1) 若定位为 Replayable Strategy Engine，Replay 应是核心而非附加功能；
(2) 若核心是**让业务人员用受控自然语言自己写策略**，则竞争对手与 Replay 的形态都要变。

本文合并两轮讨论，以代码实证为准给出可交付的计划。

---

## 〇、结论先行

两轮提案指向同一个方向，且第二轮更准确：

> **CNL 是技术优势，"让业务团队安全地管理策略"才是客户愿意买的价值。**

这一点我同意，且它与现有工程投入是自洽的——canonical hash、trace、双引擎 parity
这些"重"投入，只有在"业务人员不敢改策略"是核心痛点时才划算。

但计划必须建立在一个硬约束上（已代码验证）：

> **★平台不持有客户的业务数据，也默认不保留执行输入。**

这决定了哪些能力能做、哪些必须先解决数据前提。详见第二节。

---

## 一、现状盘点（代码实证，非推测）

| 能力 | 现状 | 证据 |
| --- | --- | --- |
| CNL 编写 + 四语 | ✅ 已上线 | lexicon / alias / 四语言包 |
| 语法校验 | ✅ 已上线 | `POST /validate`（`PolicyEvaluationResource:1009`） |
| 单次执行可回放性判定 | ✅ 已上线 | `ReplayMetadata`（REPLAYABLE / NON_REPLAYABLE + reasons） |
| canonical hash 三件套 | ✅ 已上线 | `canonicalInputHash` / `canonicalOutputHash` / `traceHash` |
| 步骤级 trace（引擎侧） | ✅ 已上线 | `DecisionTrace.TraceStep(expression, result, matched, children)` |
| **trace 落库** | ❌ **列存在但无人写** | `traceJson` 等 4 字段在 aster-cloud 全仓 schema.ts 之外**零引用**；aster-api 侧亦无写入方 |
| **trace 默认开启** | ❌ **默认关** | `/evaluate` 的 `@QueryParam("trace") @DefaultValue("false")` |
| 双引擎 parity | ✅ 已上线 | `runnerParityStatus` |
| 版本 + 审批治理 | ✅ 已上线 | policy versions / approval workflow |
| 历史执行留存 | ✅ 已上线 | `Execution` 表 40+ 字段 |
| **执行输入留存** | ⚠️ **默认关闭** | `replayRetentionEnabled` **default(false)**；未开则 `inputJson=null` |
| **回归用例/报告** | ❌ **仅 schema，零实现** | 四张表在 `schema.ts`，除自身外**全仓 0 引用**（无迁移/API/UI/服务层） |
| 批量回放 | ❌ 不存在 | `/api/**` 无 batch/replay/compare/simulate/diff 路由 |
| 版本对比（A/B diff） | ❌ 不存在 | 同上 |
| What-if 模拟 | ❌ 不存在 | 同上 |
| **规则冲突/歧义/死规则检测** | ❌ **不存在** | grep `conflict`/`overlap`/`unreachable` 命中的全是 nonce 冲突、HTTP 409、API key 校验，无一是规则分析 |
| 业务指标（成交率/利润） | ❌ 不存在 | `Execution` 有 `decision`，**无 outcome** |

### 已有的很扎实，缺的是"业务人员看得懂的那一层"

地基（可信执行链）是完整的。缺的恰恰是第二轮提案强调的东西：
**冲突检测、影响预估、业务语言的漏斗视图**——这三样一个都没有。

---

## 二、★ 硬约束：平台不持有业务数据

这是全文最重要的一节，决定了提案里哪些话能兑现。

**已验证**：50 张表里**没有** order / customer / transaction / dataset 类表。
平台唯一持有的客户数据是 `Execution.input`，而它：

- 默认**不保存**（`replayRetentionEnabled` default false，ADR pii-admission/v1）
- 只有**实际发生过的执行**才有记录

### 这意味着什么

提案里的这类话**目前无法兑现**：

| 提案表述 | 需要什么 | 平台是否持有 |
| --- | --- | --- |
| "符合规则：8,432 个订单" | 客户**全量订单**数据 | ❌ 只有执行过的样本 |
| "预计少收入 $18,240" | 订单**金额 + 成本** | ❌ |
| "预计复购率提升 3.2%" | **业务结果**（复购发生了吗） | ❌ |
| "符合人数 2,300 → 5,600" | 客户**人群**数据 | ❌ |
| "投诉风险无明显变化" | 投诉数据 | ❌ |

**"12,431 个订单 → 2,038 进入 VIP 判断"这种漏斗图，只能基于"平台见过的执行"，
不是客户的全量业务。** 若不加说明地展示，会让业务人员误以为是全量分析——
这在风控/信贷场景下是危险的误导。

### 三条可能的出路（需要产品决策，不是技术决策）

1. **样本外推**：基于已执行样本估算，UI 必须显著标注"基于 N 条历史执行，非全量"
2. **客户上传数据集**：让客户传一批历史订单做 dry-run（新增数据面，涉及 PII 合规）
3. **业务结果回传**：客户在决策落地后回传 outcome（见 Phase 3）

**建议先做 1，因为它不需要客户配合。** 但必须诚实标注口径。

---

## 二·五、★ trace-only 路线（PII 零成本的主干）

### 核心洞察

引擎侧的 `DecisionTrace.TraceStep` 已经携带：

```java
record TraceStep(String expression, Object result, boolean matched, List<TraceStep> children)
```

只要有 `expression` + `matched`，就能算出业务人员要的漏斗：

```
3,412 条执行
  → 条件「客户是 VIP」命中     2,038 (59.7%)
  → 条件「连续购买 > 3 次」命中 1,642 (48.1%)
  → 条件「30 天无退款」命中     1,104 (32.4%)  ← 最终给折扣
```

**这一层不需要任何明文输入**——只统计"每个条件被判定为真/假的条数"，是布尔聚合，
不碰字段值。因此它：

- ✅ 不受 `replayRetentionEnabled`（default false）限制，对**全部租户**可用
- ✅ 零明文 PII 留存
- ✅ 输出天然是业务语言（条件原文 + 命中数），不是 AST/trace 树

### ★ 但前提不成立：trace 现在没落库

**必须诚实记录**：本文初稿曾断言"trace 数据已有，只缺聚合层"，**该结论是错的**。
实测：

- `traceJson` / `replayabilityStatus` / `canonicalInputHash` / `runnerParityStatus`
  四个字段在 aster-cloud 全仓（含 `src/services/`）**schema.ts 之外零引用**
- aster-api 侧无写入方
- `/evaluate` 的 `trace` 参数 `@DefaultValue("false")`

即：**列已建好，但没有任何代码写入。** 所以 trace-only 路线的第一步不是写聚合查询，
而是先把 trace 采集打通。工作量比初判大，但路线本身仍成立且仍是 PII 成本最低的一条。

### PII 风险点（必须处理）

`TraceStep.result` 是 `Object`——**它会带业务值**（例如 `result=680` 是信用分）。
所以**不能整棵 trace 原样落库**，否则等于绕过 `replayRetentionEnabled` 偷偷存了明文。

**设计约束**：落库的是**脱敏后的聚合友好结构**，只保留
`{expression, matched, stepId}`，**丢弃 `result` 值**。
需要看具体值时，走现有的 `replayPayloadCiphertext`（已加密、受 retention 门控）路径。

---

## 三、分阶段计划（trace-only 为主干）

排序原则：**PII 成本低、不依赖客户配合的先做**。

| Phase | 内容 | PII 成本 | 依赖客户配合 | 覆盖租户 |
| --- | --- | --- | --- | --- |
| **0** | trace 采集打通 | 零（脱敏后落库） | 否 | 全部 |
| **1** | 条件漏斗 + 死分支 | 零 | 否 | 全部 |
| **2** | 规则冲突静态检测 | 零 | 否 | 全部 |
| 3 | 决策翻转对比（A/B） | 中（需明文重放） | 需开 retention | 开关已开的 |
| 4 | outcome 回传 | 高 | **是** | 愿回传的 |
| 5 | What-if 模拟 | 高 | 是 | 同上 |

---

## 四、详细实施计划

### Phase 0 — trace 采集打通（前置，约 1 个迭代）

**目标**：让每次执行都留下**脱敏的**条件判定记录，为 Phase 1 提供数据。

#### 0.1 定义脱敏 trace 结构（aster-api）

新增 `TraceSkeleton`——只保留聚合所需，**丢弃所有值**：

```java
record TraceSkeleton(
    String schemaVersion,          // "trace-skeleton/v1"
    List<SkeletonStep> steps
) {
    record SkeletonStep(
        String stepId,             // 稳定 id，跨版本可对齐
        String expression,         // 条件原文（CNL 片段，非用户数据）
        boolean matched,           // ★只留布尔，不留 result
        int depth
    ) {}
}
```

★**安全边界**：`expression` 是**策略源码片段**（作者写的规则），不是用户输入，
故不含 PII。`result` 值一律丢弃。这条必须写进代码注释并加测试锁死——
一旦有人"顺手"把 `result` 加回来，就等于绕过 `replayRetentionEnabled` 存了明文。

#### 0.2 落库

- `Execution.traceSkeletonJson`（新列，jsonb）—— **不复用 `traceJson`**：
  那一列的语义是"完整 trace（含值）"，混用会让 PII 边界含糊
- 写入方：aster-api 执行路径，**默认开启**（与 `trace` 查询参数解耦——
  后者控制是否**返回**给调用方，前者控制是否**记录**骨架）

#### 0.3 体积与留存

- 单条骨架预估 < 2KB（10-30 个 step × ~60B）
- 复用现有 `piiRetentionUntil` 清理机制？**不复用**——骨架无 PII，
  可独立设更长留存（分析价值随样本量上升）。需产品定留存期

**验收**：跑 100 次执行，DB 里能查到 100 条骨架，且 `grep` 不到任何业务值。

**风险**：写入放大。每次执行多一次 jsonb 写。需压测确认对 p99 延迟的影响，
超阈值则改异步落库（走现有 outbox 模式）。

---

### Phase 1 — 条件漏斗 + 死分支（约 1-2 个迭代）

**目标**：回答"这条策略实际是怎么走的"，用业务语言。

#### 1.1 聚合 API

`GET /api/policies/:id/funnel?version=N&from=...&to=...`

```json
{
  "sampleSize": 3412,
  "sampleNote": "基于平台记录的执行，非客户全量业务数据",
  "steps": [
    { "stepId": "s1", "expression": "客户是 VIP", "matched": 2038, "total": 3412 },
    { "stepId": "s2", "expression": "连续购买超过 3 次", "matched": 1642, "total": 2038 }
  ],
  "deadBranches": [
    { "stepId": "s7", "expression": "订单金额 > 1000000", "matched": 0, "total": 3412 }
  ]
}
```

#### 1.2 UI

- 漏斗图（每步条件原文 + 命中数 + 转化率）
- **死分支高亮**："这个条件在 3,412 条执行里从未命中过"——
  这是业务人员最容易理解的价值：*你写的规则可能根本没生效*
- ★**样本口径必须常驻显示**，不是脚注

#### 1.3 与发布流程结合

在策略发布确认页展示新版本的漏斗预览。

**验收**：拿一条真实策略，能画出漏斗并指出至少一个从未命中的条件。

---

### Phase 2 — 规则冲突静态检测（约 2 个迭代）

**目标**：不跑数据也能发现问题。起点是 Core IR，不是文本。

- **重叠且矛盾**：两条规则条件有交集但结论相反
- **死规则**：条件恒假（如 `x > 100 且 x < 50`）
- **区间空洞**：`>500` 与 `<300` 之间无覆盖
- **遮蔽**：前一条规则完全覆盖后一条

实现路径：Core IR → 条件正规化 → 区间/集合求解。数值区间可用简单
interval arithmetic 起步，不必上 SMT。

**验收**：构造 4 类问题各一例，检测器全部报出且无误报。

---

### Phase 3-5

见第三节表格。Phase 3 依赖 retention 开关，Phase 4/5 依赖客户回传 outcome——
**在与真实客户确认前不投入开发**。

---

## 五、对定位建议的看法

### 同意

- **"CNL 是技术优势，'安全地管理策略'才是客户买的价值"** —— 这是两轮讨论里最准确的一句
- **Replay 要用业务语言而非 Execution Trace / AST 呈现** —— Phase 1 按此设计
- **Before Publish 门禁** —— 与现有审批流天然契合，Phase 1 直接挂进去
- **What-if 比 Replay 更受欢迎** —— 认同，但它依赖 outcome，排序上必须在后面

### 需要谨慎

- **"Business Strategy Operating System"**：叙事很强，但 OS 意味着承接业务数据。
  现在平台**刻意不持有**这些数据（PII 合规上是优势）。要做 OS 就得重新决定这条边界，
  这是战略取舍，不是加个功能。
- **所有带金额/人数的预估文案**（$18,240 / 2,300→5,600 / 复购率 +3.2%）：
  在 Phase 3 落地前不能出现在对外材料里。
- **不要丢掉"可信"这条线**：第二轮提案完全在讲易用性，但 Aster 已投入的
  hash 链 / 双引擎 parity / 审批治理是**银行客户唯一会为之付溢价**的东西。
  建议叙事是"业务人员能自己写 + 每个决策可审计可回放"，
  而不是用易用性**替换**可信——那会掉进一堆低代码工具的红海。

---

## 五·五、实施进展（2026-08-15 更新）

> **本节是后补的进度追加，第一节的现状表格保持 2026-08-05 原样不改。**
>
> 原表是**当时做决策的依据**，改掉它就看不出"为什么当初这么排 Phase"。
> 但原样合并又会让读者误以为那是今天的状态——本仓最高产的 bug 模式正是
> 「文档声称 ≠ 实现如此」。故用本节做时间戳分层：**读现状看这里，读决策依据看第一节。**

### 第一节表格中已被追平的行（逐条代码核对）

| 第一节写的 | 2026-08-15 实测 | 交付 |
| --- | --- | --- |
| trace 落库 ❌ 列存在但无人写 | ✅ `Execution.traceSkeletonJson` 已写入并被消费 | api#219 / cloud#369 |
| 回归用例/报告 ❌ 仅 schema 零实现 | ✅ `/api/policies/[id]/funnel` + `condition-funnel.ts` | cloud#371 |
| **规则冲突/歧义/死规则检测 ❌ 不存在** | ✅ `RuleConflictAnalyzer`（372 行 + 测试），接进编译诊断链 | api#220 |
| 批量回放 ❌ 不存在 | ✅ `ReplayBatch*`（service/scheduler/entity 等） | api#229，P0 修复 api#234 |
| 版本对比 A/B diff ❌ 不存在 | ✅ What-If 面板下发 `changed` / `newlyApproved` | cloud#377 |
| What-if 模拟 ❌ 不存在 | ✅ 同上 | cloud#377 |

即 **Phase 0 / 1 / 2 / 3 均已落地**；第三节表格里只有 Phase 4（outcome 回传）与
Phase 5（完整 What-if 模拟）仍未开工——它们依赖客户配合，按原计划**在与真实客户
确认前不投入开发**，这条判断至今未变。

### 两条硬约束仍然成立（本节最重要的部分）

第二节的结论是全文地基，重新核对**依然有效**：

| 断言 | 2026-08-15 实测 |
| --- | --- |
| `replayRetentionEnabled` **default(false)** | ✅ `schema.ts:298` 原样未动 |
| 平台无 order / customer / transaction 类表 | ✅ 现 **51** 张表，业务数据表命中 **0** |

所以第二节那张"提案里无法兑现的表述"清单——「符合规则：8,432 个订单」
「预计少收入 $18,240」「预计复购率提升 3.2%」——**至今仍然无法兑现**，
不因 Phase 0-3 已交付而改变。对外材料引用本文时，这一节才是需要遵守的约束。

### 补充：留存期本身此前也只是「文档承诺」（cloud#396，已修）

第三节把 Phase 3 标为「依赖 retention 开关」。这句话当时是对的，但**遗漏了一件事**：
`plans.ts` 里 `audit7days`（free）/ `audit90days`（pro 等）**没有任何代码执行它**——
`cleanupOldExecutionLogs` 全仓零调用方，所有档位实际留存都是**永久**。

即：本文写作时，「retention 开关」控制的是**是否记录**，而「留存多久」这一侧
根本没有执行者。与本文第二·五节自己揭示的「trace 列已建好但没人写」是**同一形态**——
schema/配置到位，执行缺位。

已修（cloud#396 → #397/#398/#399）：

| | |
| --- | --- |
| 留存 GC 自执行 | `/api/cron/execution-retention-gc`，按租户 plan 取天数 |
| enterprise | 加 `auditUnlimited` featureKey——从「无法判定」变成**显式承诺** |
| 决策骨架 | 独立留存 **365 天**，与 plan 解耦（骨架无 PII，且需覆盖最长分析窗口） |
| 真实删除行为 | 真 Postgres 集成测试，4 条用例 + 3 轮变异验证 |

**对本文结论的影响**：

- Phase 3「依赖 retention 开关」→ 现在还要加一句**依赖留存期覆盖所选窗口**。
  What-If 要真回放就得读 `Execution.input`，而它随执行日志按 plan 被 GC 删除。
  已配套把 What-If 的可选窗口按 plan 裁剪（free 7d 只给「最近一个月」），
  并对自定义区间如实标注实际覆盖天数。
- 第二节的两条硬约束**不受影响**：平台仍不持有业务数据，`replayRetentionEnabled`
  仍 default(false)。留存 GC 只是让「保留多久」这一侧变得可执行。

### 设计约束被实现照单执行（可核查）

第二·五节要求「落库结构必须丢弃 `result` 值，否则等于绕过 `replayRetentionEnabled`
偷偷存明文」。实现比文档要求的更强——不是靠注释和测试，而是**结构上不存在该字段**：

```java
// replay/src/main/java/io/aster/policy/replay/TraceSkeleton.java
// <p>本类型通过**结构上不存在 result 字段**来保证这件事做不到——
```

`traceSkeletonJson` 也按文档要求**独立成列**而非复用 `traceJson`
（cloud `schema.ts` 注释："混用一列会让 PII 边界含糊"）。

另：第二节警告过"漏斗若不标注口径会让业务人员误以为是全量分析，在风控/信贷场景
下是危险的误导"。实现中对应字段命名为 **`neverMatchedInSample`**，代码注释写着
"这只是样本内的事实陈述，不等于死分支，命名与文档都不得暗示后者"——顾虑落到了命名上。

### 一处需要留意的偏差

第四节 Phase 2 的验收标准写的是「构造 4 类问题各一例，检测器全部报出且无误报」，
而 `RuleConflictAnalyzer` 当前只检测**两类**（`ALWAYS_FALSE` 恒假条件、
`REDUNDANT` 恒真嵌套），且显式声明「宁可漏报，不可误报」、不做跨 Rule 检测。

这是**有意收窄**而非未完成（该类注释给了理由：业务人员对静态检查的信任极其脆弱，
一次误报就会让整个功能被忽略）。但与本文写的验收标准不一致，**以实现为准**。

---

## 六、建议的下一步

### 可以立即开工（无需任何产品/客户决策）

1. **Phase 0：trace 采集打通** —— 前置，其余分析能力都依赖它
2. **Phase 2：规则冲突静态检测** —— 与 Phase 0 完全独立，可并行

这两项都是**零 PII 成本、全租户可用、不需客户配合**。

### 需要你先拍板

3. **骨架 trace 的留存期** —— 它无 PII，是否可比 `piiRetentionUntil` 更长？
   留得越久分析价值越高
4. **样本口径的呈现方式** —— 漏斗分母是"平台见过的执行"而非客户全量。
   接受这个口径并显著标注？还是要走"客户上传数据集"另开一条路？

### 需要客户验证后再决定

5. **outcome 是否可获得** —— 决定 Phase 4/5 是否留在路线图。
   建议先问 2-3 个真实客户，再投开发

### 对外文案（收敛到能兑现的范围）

- ✅ "Test every strategy before it reaches production"
- ✅ "让业务人员用自然语言编写、验证策略"
- ✅ Phase 1 后可加："看到你的策略实际怎么走的"（漏斗/死分支）
- ❌ "Replay one million decisions in minutes"（批量回放未实现、性能未测）
- ❌ 任何带具体金额/人数的影响预估（需 Phase 4）
