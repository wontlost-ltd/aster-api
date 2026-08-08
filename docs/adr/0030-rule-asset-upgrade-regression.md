# ADR 0030：规则资产升级回归工具（Rule-Set Upgrade Regression Harness）

- 状态：PROPOSED（2026-07-13。deep-research 就绪度重评产出头号 P0-A blocker；设计经 Codex 对抗审查 67→重设计，本文为重设计后版本）
- 决策者：待用户拍板
- 相关：[[commercial-readiness-roadmap]]（2026-07-13 重评 P0-A）、ADR 0016（IR 字段级 parity）、ADR 0014（vocabulary 快照/版本化）、ADR 0022（source envelope + toolchainId 冻结）、ADR 0023（多模块发布治理）、ADR 0025（Decimal 双引擎字节口径）

---

## 1. 背景与问题

推广就绪度重评（72→78/100）后，剩余唯一**纯工程可解**的 GA 相关 blocker = **规则资产升级回归工具缺失**。

合规原语（Date/Decimal）解决「能不能表达合规规则」。但受监管 buyer（CCO/风控）签字关心的是：

> **平台升级后，去年批准的规则会不会静默改变行为？**

规则引擎的命门。以下任一变化都可能悄改历史决策：lexicon 变更、compiler/runtime 升级（1.0.x）、Decimal 舍入 bugfix、Date 语义。**现状缺口（实证）**：内部有强 parity corpus（217 样本 PR-blocking），但没有把「客户自己的规则集」在升级前后回放对比的工具。术语快照（ADR 0014）只部分缓释 lexicon 侧、针对已发布版本。**内部 217 样本 parity ≠ 客户规则集回放。**

**为什么 P0**：受监管 SaaS GA，「证明已批准规则升级后不漂移」是硬采购门。没有 harness，CCO 只能「相信 vendor 测过」，受监管场景不接受。

---

## 2. 已有基础设施（地基）与 Codex 审查暴露的缺口

### 2.1 可复用地基（实证）
- **规则资产已冻结工具链身份**：`PolicyVersion.sourceEnvelopeSha256 = hash(content + aliasSetJson + locale + toolchainId)`（ADR 0022，`version-manager.ts:174`）+ `sourceToolchainId`。
- **执行历史候选**：`Execution` 表（cloud `schema.ts:626`）存 `{ policyId, policyVersion, input(notNull), output, decision, createdAt }`。
- **编译/求值端点**：`/api/v1/policies/compile`（结构化诊断）+ `/evaluate-source`（HMAC）。
- **内部 harness 思路**：`parity-tier1.mjs` golden-diff。

### 2.2 ★Codex 审查暴露的四块硬缺口（本次重设计针对）
1. **执行历史是 candidate 不是 golden**：`Execution.policyVersion` 只是版本号非不可变 row id；未存 `functionName`/`locale`/`aliasSet`/`reasonCodes`/`traceHash`；output 逐字节比会受 key order/Decimal 表示/null 影响。→ 必须冻结成不可变 `RegressionCase`。
2. **冷启动 + 边界覆盖盲区**：新规则无执行历史→Layer 2 空跑；历史采样偏常见客群，漏拒绝/等待期/金额/日期/null 边界。→ 手写 golden 进 M1，空样本报 `INSUFFICIENT_COVERAGE`（不能「通过」）。
3. **双活 toolchain 不可复验**：「基线=当前生产」升级后消失，报告不可复算。→ `ToolchainRegistry` + 不可变 image digest，old/new 都拉不可变 runner。
4. **逐字节 diff ≠ 监管证据**：output 没变但 reason/trace 变也是监管问题。→ 分层漂移（DECISION/AMOUNT/REASON/TRACE/RAW）+ 受控接受漂移机制。

---

## 3. 设计（重设计后）

### 3.1 证据模型：不可变 RegressionCase（核心）

执行历史只是**候选来源**；真正 golden 是冻结的 `RegressionCase`（进库即不可变，进报告 hash）：

```text
RegressionCase {
  id
  policyId, policyVersionId (不可变 row id, 非版本号), version
  sourceEnvelopeSha256, sourceToolchainId    // 回放归因锚点
  locale, aliasSetHash, vocabularySnapshotIds // 完整回放上下文（不可缺）
  functionName
  canonicalInputJson                          // canonical 序列化（见 3.5）
  canonicalExpectedOutputJson                 // 基线输出（当前 toolchain 冻结）
  expectedDecision                            // 四态
  expectedReasonCodes?                        // adverse action reason（监管关键）
  traceHash?                                  // 决策路径指纹
  source: execution | manual | synthetic
  coverageTags[]                              // 边界标签：threshold/reject/null/date-boundary/rounding…
  approvedBy, approvedAt                      // 谁冻结的基线
}
```

- **回放完整性铁律**：case 必须携带回放所需**全部**上下文（locale/aliasSet/vocab snapshot）。缺任一 → 该 case 标 `NON_REPLAYABLE`，不参与 pass/fail 判定（不可静默算「通过」）。
- Legacy 版本（无 envelope/toolchainId/aliasSet）→ `NON_REPLAYABLE_LEGACY_VERSION`。

### 3.2 golden 来源（用户拍板：真实历史优先 + 手写补充，二者 M1 都要）

- **主：Execution 候选 → 冻结为 RegressionCase**。分层采样（decision 四态 + 边界 + 最近变更字段，非纯时间窗）+ 去重（相同 canonical input 保一条）+ 每版本上限 N。冻结时 join PolicyVersion 补齐 locale/aliasSet/vocab/envelope。
- **补：作者手写 golden case（M1 就要，非 M3）**：覆盖历史未跑到的边界（`creditScore 恰 680`/`30 天等待期`/`Decimal 0.005 舍入`/`null income`/多币种）。`expectedOutput` 用当前 toolchain 冻结。
- **★覆盖门禁**：每个已批准版本无最低 golden 覆盖（真实+手写）→ 报告输出 `INSUFFICIENT_COVERAGE`，**禁止输出「通过」**（防冷启动假安全）。

### 3.3 双层漂移检测

- **Layer 1 编译漂移**：new toolchain 重编译冻结源码，old 能编 new 不能 → `COMPILE_REGRESSION`；诊断集变 → `DIAGNOSTIC_CHANGE`。
- **Layer 2 语义漂移**：golden 输入在 old/new toolchain 各跑，比对**分层**（非仅逐字节）：
  | 漂移类 | 含义 |
  |---|---|
  | `DECISION_DRIFT` | 四态决策变（最严重） |
  | `AMOUNT_DRIFT` | 金额/额度/保费/免赔变 |
  | `REASON_DRIFT` | adverse action reason 变（监管关键） |
  | `TRACE_DRIFT` | 触发规则路径变（traceHash 变） |
  | `RAW_OUTPUT_DRIFT` | 原始输出字节变（兜底） |

### 3.4 ToolchainRegistry（不可复验→可复验）

不依赖「当前生产」这个会消失的活动目标：
```text
Toolchain {
  id, apiVersion,
  imageDigest,          // aster-api 容器 digest（不可变）
  lexiconBundleHash,    // lexicon 版本指纹
  createdAt
}
```
- regression runner 按 old/new 的 `imageDigest` 拉**不可变 runner**（隔离 pod/env），报告记两个 digest。
- 生产升级后仍可按旧 digest 重跑 → CCO 要的是「可复验」非「当时记得跑过」。
- runner 走 `/evaluate-source`（带冻结 aliasSet+locale+vocab），但目标是不可变 digest 非活动生产。

### 3.5 canonical JSON（逐字节比对的前提）
- 定义 canonical serializer：Map key 排序、Decimal 走字符串（`toPlainString`/decimal.js canonical，同 ADR 0025）、Date 走 epoch-day Int、null/缺失归一。否则 key order/number 表示制造**假漂移**。
- input/output 都过 canonical 后再 hash/比对。

### 3.6 报告 + 受控接受漂移

```text
UpgradeRegressionReport {
  reportHash,                         // 覆盖 toolchain digests + case ids + inputs/outputs hash + runner version
  fromToolchain, toToolchain,         // 不可变 digest
  runAt,
  summary: { total, passed, insufficientCoverage, nonReplayable,
             compileRegressions, decisionDrift, amountDrift, reasonDrift, traceDrift },
  findings: [{ policyId, policyVersionId, kind, case?{name,input,oldOutput,newOutput,coverageTags} }]
}
```
- **门禁四态（非 pass/fail 二值）**：
  | 状态 | 语义 |
  |---|---|
  | `PASS` | 有足够覆盖且无漂移 |
  | `FAIL_REGRESSION` | 有语义/编译漂移 → 阻断升级 |
  | `FAIL_INSUFFICIENT_COVERAGE` | 覆盖不足 → 阻断（不假通过） |
  | `ACCEPTED_DRIFT_WITH_APPROVAL` | 漂移是**有意 bugfix**（如 Decimal 舍入纠错），经业务 owner + CCO 签核 + 变更说明 + 影响清单 + 生效日期 + rollback plan 后受控接受 |
- 报告进 hash 链审计（升级动作可追溯：谁批、基于哪份报告）。有意 bugfix**不能绕过报告**，只能通过审批接受。

### 3.7 架构落点
- **执行**：aster-cloud 后端 `RuleRegressionRunner`（DB 访问 executions/policyVersions + 冻结 RegressionCase + 调不可变 toolchain runner）+ `POST /api/admin/rule-regression`。
- **隔离队列**：regression 求值用 **dedicated runner pool**，不打线上 preview/AI 共享的 `/evaluate-source` 资源（防影响生产）。
- **触发**：升级前预发 gate + nightly（对活跃客户规则早发现漂移）。

---

## 4. 分阶段（按 Codex 重设计意见）

- **M0（证据模型）**：`RegressionCase`（不可变）+ canonical JSON serializer + `ToolchainRegistry` + report schema + reportHash。先把证据模型立住。
- **M1（单租户离线 MVP）**：runner 做 **compile + semantic 两层**；golden 支持真实执行冻结 + **手写 case（M1 就要）**；canonical diff；报告输出 `PASS/FAIL_REGRESSION/FAIL_INSUFFICIENT_COVERAGE/NON_REPLAYABLE`。单测覆盖 pass/各漂移类/覆盖不足/legacy 不可回放。
- **M2（覆盖报告）**：decision/trace/reason/边界标签分层漂移 + coverage 证明（每版本覆盖哪些分支）。
- **M3（审计链 + 受控接受）**：报告进 hash 链 + `ACCEPTED_DRIFT_WITH_APPROVAL` 审批流。CCO 可签字。
- **M4（产品化）**：CI/nightly/升级前 gate 接线 + 报告 UI（复用 DecisionTracePanel 思路展示 diff）。

### 最小可信 MVP（Codex 建议，≈4-6 周）
一客户 + 一 policy family + 固定 old/new toolchain digest + Execution 取真实样本 + 每版本强制补 10-30 手写边界 case + compile+semantic 两层 + canonical diff + 报告 `PASS/REGRESSION/INSUFFICIENT_COVERAGE` + 落审计链。给设计伙伴试点用，非 SaaS GA。

## 5. v1 明确不做
自动迁移/修复（漂移须人工裁决）、跨大版本（1.x→2.x）等价（大版本本就 breaking）、性能回归（属 SLA/容量）、客户自助跑（v1 只管理员/流水线触发）。

## 5.1 实施时须硬化（Codex 复审 81/100 标注，含 2 个「M1 前必须」）

1. **覆盖阈值具体化（policy-family 可配）**：每 decision 四态 ≥ N 条 / 每 adverse reason ≥ 1 条 / 每金额·日期·rounding·null 边界 ≥ 1 条 / 新版本无历史执行时手写 case 是激活前硬门禁。
2. **NON_REPLAYABLE 门禁语义统一**：case 级 = `NON_REPLAYABLE`；若影响覆盖阈值 → report 级归 `FAIL_INSUFFICIENT_COVERAGE`（不让「不可回放但不影响 pass/fail」被误读为通过）。
3. **★M1 前必须：PII 数据准入策略**：M1 就要把 Execution 提升为 RegressionCase = 长期留存真实金融输入。须先定脱敏/最小化/retention/客户授权，或支持客户托管 golden。**这是 M1 数据准入门，非 M4 产品化问题。**
4. **★依赖模块 envelope 进证据模型**：规则 `Use` 其它 module 时，回放须冻结依赖模块版本/envelope，否则主规则不变、依赖库变也漂移且归因不清。RegressionCase 加：
   ```text
   dependencyEnvelopes[] = { module, versionId, sourceEnvelopeSha256, toolchainId }
   ```
5. **MVP/M3 审计链阶段拆清**：M1 = 报告文件自身 hash + 存库；M3 = 正式进审计 hash chain + 审批流。
6. **traceHash 对 decision policies 不长期 optional**：信贷/保险 reason/trace 漂移常等同监管漂移；生产 GA 目标下 trace/reason 应进 coverage gate（decision 类规则）。

## 6. 隐藏复杂度（Codex 挖出，须在实施前解决）
1. **PII 与数据留存**：Execution input 是真实客户数据，提升为 golden = 长期留存测试数据。需脱敏/最小化/retention/客户授权，或支持客户托管 golden。
2. **input schema 版本**：规则没变、输入 schema 变也漂移。case 记 input schema version，归因才干净。
   **★2026-07-14 Codex 复审定论：M1 不加。** M1 的 `inputSchemaFingerprint(inputJson)` 在 run 时是**死代码**——run 回放的是**同一份 frozen inputJson**，freeze/run 两次 fingerprint 必然相等，永不触发有意义的 `INPUT_SCHEMA_DRIFT`。且「样本形状 fingerprint」≠「输入契约 schema」：单样本无法表达 required/optional/nullable，`{x:null}` 不能简化成字段类型=null，Decimal 必须来自权威 typeCtx 不能从 JS value 猜。M1 现有 `INPUT_HASH_MISMATCH` 已覆盖「回放输入≠frozen input」的正确性。**真正的实现（M2/M3）**：改名 input **contract** snapshot；来源是 aster-api `getSchema(source,{functionName,locale})` 权威 descriptor（非 inputJson）；freeze 记契约 fingerprint（绑 policyVersionRowId+functionName+locale+sourceEnvelopeSha256+schemaToolchainId）；run 用当前后端对同一 frozen source 重取 schema 比对——schema 变→INPUT_SCHEMA_DRIFT，schema 不变但 output 变→engine drift。fingerprint 算法：{path→{type,required,nullable}} 排序 canonicalHash，数组 items.type 来自 schema 不从样本猜，空数组不推 array&lt;unknown&gt;。**在此之前加会形成死代码 + 给 CCO「系统能区分契约漂移与引擎漂移」的错觉。**
3. **legacy 不可回放**：旧版本可能无 envelope/toolchainId/aliasSet/locale → 标 `NON_REPLAYABLE_LEGACY_VERSION`，不算通过。
4. **隔离队列**：regression 大量求值吃动态编译资源，须 dedicated pool 不共享线上。
5. **canonical JSON**：逐字节只在 canonical serializer 下有意义（Decimal/Date/Map key/null）。
6. **采样偏差**：500 上限不是问题，抽样策略是——须覆盖四态/边界/最近变更字段/拒绝原因，非纯时间窗。

## 7. 回滚
M0-M4 全是**新增**（新 service/端点/表/registry/流水线 gate），不改现有 evaluate/compile/version 逻辑。gate 可 feature-flag 关闭（退回现状不破坏发布）。已跑报告是只读审计数据。

## 8. 为什么这个设计对 CCO 可信（重设计后回答 Codex 的四问）
1. **哪些版本被覆盖/哪些没有** → RegressionCase 冻结 + `INSUFFICIENT_COVERAGE`/`NON_REPLAYABLE` 显式状态，不假通过。
2. **每版本覆盖哪些业务分支/拒绝原因/金额日期边界** → coverageTags + M2 覆盖报告。
3. **old/new toolchain 是否不可变可复验** → ToolchainRegistry 不可变 digest，升级后仍可复算。
4. **结果变了是 regression / 故意修复 / 覆盖不足** → 门禁四态 + 分层漂移 + `ACCEPTED_DRIFT_WITH_APPROVAL`。

golden = 客户真实决策（冻结）+ 边界补充；双不可变 toolchain 逐字节（canonical）+ 分层比对；报告进审计链；确定性铁律兑现（回放可信的地基正是 Aster 立身卖点）。这个 harness 把「可回放」从单次决策提升到**版本升级级的资产保护**。

---

## 附录 A：M0 证据模型实现规格（2026-07-14，Codex 设计，Phase 1）

Phase 1 = 扩展 aster-cloud `Execution` 表为决策级持久层（回放地基）。以下为可实施规格。

### A.1 Execution 新增列（DB 全 nullable 兼容历史；「新写路径必填」= 应用层 invariant，`replayCaptureVersion='p0a.v1'` 时必须有，否则写 `NON_REPLAYABLE`）
| 列 | 类型 | 新写必填 | 说明 |
|---|---|---|---|
| `policyVersionRowId` | text FK PolicyVersion(id) | ✓ | 不可变版本行引用（旧 `policyVersion int` 保留仅显示） |
| `functionName` | text | ✓ | 执行的 rule/function；缺→`NON_REPLAYABLE:MISSING_FUNCTION_NAME` |
| `locale` | text | ✓ | 执行时实际 locale，非运行时猜测 |
| `aliasSetJson` | jsonb | ✓（空写 `{}`） | 冻结 alias set |
| `vocabSnapshotRef` | jsonb | ✓（可空数组） | 从 PolicyVersion.vocabularySnapshotIds 复制，引用对象须不可变 |
| `sourceToolchainId` | text | ✓ | 源码/envelope 编译工具链（PolicyVersion.sourceToolchainId） |
| `runtimeToolchainId` | text | ✓ | 实际执行引擎 toolchain（aster-api 须返回；本地 simple engine 用固定 id） |
| `reasonCodes` | jsonb | ✓（可空数组） | machine-readable code，非自然语言 |
| `traceJson` | jsonb | 可选 | **PII-redacted** 结构 trace，不含原值 |
| `traceHash` | text | ✓ | sha256(canonical full trace)；无→`NON_REPLAYABLE:MISSING_TRACE_HASH` |
| `canonicalInputHash` | text | ✓ | 见 A.2 |
| `canonicalOutputHash` | text | ✓ | 对 canonical output（错误/无输出对 canonical `null`）取 hash |
| `canonicalizationVersion` | text | ✓ | 如 `aster-canonical-json/v1` |
| `replayCaptureVersion` | text | ✓ | 如 `p0a.v1`；历史行 NULL |
| `replayabilityStatus` | text | ✓ | `REPLAYABLE`/`NON_REPLAYABLE`；历史迁移后 NON_REPLAYABLE |
| `replayabilityReasons` | jsonb | ✓（可空数组） | 如 `["LEGACY_EXECUTION"]` |
| `replayPayload{Ciphertext,Alg,KeyId,Nonce,Hash}` | text | PII opt-in 必填 | envelope 加密的完整 replay 真值（原始 input/output/full trace） |
| `piiRetentionUntil` | timestamp | PII opt-in | 到期 crypto-erasure（销毁 DEK），不改行 |
| `piiPolicyVersion` | text | ✓ | 如 `pii-admission/v1` |

replay-captured 行的旧 `input/output` 列改存 redacted 摘要（`{_redacted:true, canonicalInputHash}`），真值只在加密 payload。

### A.2 Canonical JSON / hash 算法（§3.5 细化）
`sha256(canonicalizationVersion + "\n" + canonicalJson(value, typeCtx))`。要点：
- object key 按 Unicode code point 升序；array 保序。
- `null` 显式输出；**missing ≠ null**；不丢空对象/空数组/false/0/""。
- string 原值不 trim/case-fold。
- number 须 finite；NaN/Infinity 拒绝→`NON_REPLAYABLE:NON_CANONICAL_NUMBER`。
- Decimal **类型感知**：仅 schema/vocab/trace 声明为 Decimal 的值做 decimal canonical（无 exponent/无前导+/无无意义前导零/无 trailing zero/`-0`→`0`/整数无 `.0`）。
- JSON `1` ≠ string `"1"`（不全局转换）。
- output hash 剔除非决定性字段（executionTimeMs/durationMs/evaluatedAt/requestId）。

### A.3 PII 数据准入策略（`pii-admission/v1`）
- 默认**不留**长期 replay 真值。tenant（=userId）级开关 `replayRetentionEnabled`。
- 未开：只写 redacted + hashes + 上下文，`replayPayloadCiphertext=NULL`，状态 `NON_REPLAYABLE:PII_REPLAY_NOT_ENABLED`。
- 开启：完整真值进 `replayPayloadCiphertext`，应用层 envelope 加密，per-tenant DEK 由 KMS/HSM 包装，`replayPayloadKeyId` 记 key version。DB/日志/analytics 禁明文。
- 回放 worker 单独解密授权 + audit（谁/何时/哪条/用途）。
- 默认保留 180 天（可 30/90，合同可升）。到期 crypto-erasure（销毁 DEK），Execution 行不删不改，保留 hashes + redacted 摘要。

### A.4 写路径改造（4 处统一）
`/api/policies/[id]/execute`、`/api/v1/policies/[id]/execute`、`src/lib/policy-execution-log.ts:createExecutionLog`、`/api/v1/policies/[id]/secure-execute`（当前不写 Execution，须统一写或明确排除）。执行前 resolve 不可变 PolicyVersion 行（非仅 policy.content）。**aster-api 响应契约须扩展返回** `runtimeToolchainId/reasonCodes/trace/traceHash`（引擎当前不产出——引擎层子任务）；cloud 自算 canonical hashes + 校验 traceHash + 加密 payload。缺引擎字段→写行但标 NON_REPLAYABLE，不降级为通过。

### A.5 不可变性 + 迁移
- migration：全 nullable ADD COLUMN + FK(ON DELETE NO ACTION) + 索引（policyVersionRowId/replayabilityStatus/traceHash/canonical*Hash/piiRetentionUntil）；历史行 backfill `NON_REPLAYABLE`/`["LEGACY_EXECUTION"]`（**不**用旧 int 反查补 replayable）。
- 不可变：M1 应用层 append-only（Execution 创建后禁业务 update）；更强建议 DB trigger 禁 UPDATE/DELETE（M1 用应用层控制也可，ADR 声明风险）。PII 到期用 crypto-erasure 不 update 行。

### A.6 M1 必需 vs M2 延后
- **M1**：上表全部核心列 + 加密 payload + 4 写路径统一 + 缺字段即 NON_REPLAYABLE。
- **M2**：traceJson 可视化、独立 RegressionCase 表/物化视图、reason code ontology、分区表 + 自动 retention job + KMS rotation、DB trigger 全量硬封禁。

**Phase 1 首刀（2026-07-14 已交付）**：canonical JSON serializer（A.2）**双引擎完成**——
- TS 侧 `aster-cloud/src/lib/canonical-json.ts`（cloud PR#239，Codex 86 通过）。
- Java 权威侧 `aster-lang-core/.../canonical/CanonicalJson.java`（core PR#71，Codex 95 通过）。
- **TS↔Java parity gate**：共享 fixture（37 正向 + 6 负向，TS 生成）+ 两侧断言 == fixture ⟹ TS==Java 字节级；负向证明扰动 version→27 fail。**★Codex 三轮抓 5 个 fixture 全绿也没暴露的 Java≠TS 分歧**（Decimal safe-integer / lone surrogate / exponent 长度 / parseInt 溢出 / 负向 reason）——对抗式交叉审是双引擎字节一致的必要手段。
- 关键决策：非 Decimal number 只允许 safe integer（跨引擎浮点表示不一致），精确小数走 Decimal string + typeCtx。

**Phase 1 剩余（后续会话）**：Execution schema 迁移（22 列）/ 4 写路径改造 / aster-api 引擎响应契约扩展（返回 runtimeToolchainId/reasonCodes/trace/traceHash，引擎当前不产出）/ PII envelope 加密（KMS/DEK）。

---

## 附录 B：M1 缩版 runner 实现规格（2026-07-14，Codex 设计 8.5/10，Phase 2）

M0 证据模型（附录 A）已上线（Execution 回放列 + canonical 双引擎 + BFF 写路径全合 main）。
M1 = 消费该持久层的**回归 runner 缩版**（用户拍板最快到第一付费试点路径）。

### B.1 硬约束裁决（Explore + Codex 实证）
- **无 per-call toolchain 定向**（policy-api.ts 单 baseUrl）→ **裁决：M1 走 same-backend「冻结前/回放后」**，不做真 old/new 双后端实时对跑。升级前 freeze `expectedOutputHash`，升级后同后端 replay 比对。这是试点实际操作方式（freeze case→部署新版→run gate），单后端可实现，且诚实（不假装实时重算 old toolchain）。报告恒标 `comparisonMode=FROZEN_BASELINE_VS_CURRENT_BACKEND` + `baselineSemantics`（"expectedOutputHash 冻结时捕获，M1 不实时重跑 old backend"）。
- **replayabilityStatus M1 恒 NON_REPLAYABLE**（行级缺 trace payload）→ 候选选择**不筛** `replayabilityStatus='REPLAYABLE'`（返回空），筛非空 canonicalizationVersion + canonicalInputHash + canonicalOutputHash。
- **迁移手写**（schema.ts drift）→ 同 0032 风格 CREATE TABLE IF NOT EXISTS + 索引。

### B.2 表：RegressionCase（不可变 golden）+ RegressionReport（审计 artifact）
- **RegressionCase**：id/policyId/policyVersionRowId/policyVersion/functionName/locale/aliasSetJson(默认{})/vocabSnapshotRef(默认[])/inputJson/canonicalInputHash/expectedOutputHash/expectedDecision/canonicalizationVersion/sourceKind(execution|handwritten CHECK)/sourceExecutionId/coverageTags(默认[])/baselineRuntimeToolchainId/sourceToolchainId/sourceEnvelopeSha256/caseHash(UNIQUE, canonicalHash 覆盖核心字段防篡改)/createdBy/createdAt。UNIQUE(policyVersionRowId,functionName,locale,canonicalInputHash)。服务层无 update/delete。
- **RegressionReport**：id/policyId/policyVersionRowId/status(PASS|FAIL_REGRESSION|FAIL_INSUFFICIENT_COVERAGE|NON_REPLAYABLE CHECK)/comparisonMode/caseCount/runnableCaseCount/passedCaseCount/failedCaseCount/nonReplayableCaseCount/coverageJson/reportJson/reportHash(UNIQUE)/currentRuntimeToolchainId/createdBy/createdAt。落库非仅返回（gate/audit artifact，可追溯当时按什么 case/toolchain/hash 判定）。
- **★PII 数据准入**（ADR §5.1 #3）：inputJson 是 Execution 明文金融输入长期留存 → 绑 tenant opt-in（pii-admission/v1 的 replayRetentionEnabled）。未开→只冻 hash 不存 inputJson（case 标 replay-limited 无法 semantic run）；开→存 inputJson 可 run。**这是数据准入门，不硬编码存明文**。

### B.3 候选谓词（冻结时从 Execution 选）
`WHERE policyId=$1 AND (versionRowId IS NULL OR =$2) AND policyVersionRowId/functionName/locale/canonicalizationVersion/canonicalInputHash/canonicalOutputHash/input NOT NULL AND error IS NULL`，`DISTINCT ON (policyVersionRowId,functionName,locale,canonicalInputHash) ORDER BY ...createdAt DESC`（同 canonical input 保最新一条）。insert `ON CONFLICT(...) DO NOTHING` 幂等。

### B.4 覆盖门禁（M1 简化但不假通过）
默认阈值 minRunnableCases=4/minApproved=1/minDenied=1/minHandwrittenBoundary=1。状态优先级：①无 case 或全不可运行→NON_REPLAYABLE ②覆盖不达标→FAIL_INSUFFICIENT_COVERAGE ③编译失败或任一 runnable hash mismatch→FAIL_REGRESSION ④否则 PASS。即使全 match 覆盖不足也不 PASS。

### B.5 endpoint：POST /api/admin/rule-regression（一端点两 action）
- **freeze**：{action,policyId,policyVersionRowId?,limit?,handwrittenCases?[]}。admin+license write gate。从 Execution 冻候选 + handwritten case 用当前 backend 评估一次取 hash 入库。返回 frozen/duplicate/skipped 统计。audit `rule_regression.freeze`。
- **run**：{action,policyId,policyVersionRowId,thresholds?}。对冻结 case 用当前 backend replay，比 expectedOutputHash，落 RegressionReport 返回四态报告。

### B.6 M1 缩版能力边界（诚实一句话）
**M1 = 「冻结 golden hash 对当前后端的升级回归门」，不是一次性 old/new 双 toolchain replay，也不是完整 trace/payload 级可回放系统。** 不做：per-call 双 backend 实时对跑 / 筛 REPLAYABLE / trace 明文·replay payload·PII envelope·KMS / step-level trace diff / reason-code 完整覆盖门禁 / 全局 policy×version 矩阵 / NON_REPLAYABLE case 算 pass。

### B.7 实施顺序（每步可验证）
1. 手写迁移建 RegressionCase/RegressionReport + 索引 → 本地 migrate 后 `\d`。
2. schema.ts 增表 + relations + Infer 类型 → tsc。
3. RuleRegressionRunner：freezeFromExecutions/freezeHandwritten/run/buildReportHash。
4. 候选 SQL/Drizzle 查询 + 单测（不依赖 replayabilityStatus）。
5. admin route：requireAdmin + requireLicenseWriteOk + audit。
6. runner 单测：覆盖不足失败/hash mismatch 失败/全 match pass/全不可运行 NON_REPLAYABLE。
7. 手工试点：freeze→部署新版→run，验报告落库 + hash 稳定。

---

## 附录 C：M1 试点运行手册（operator runbook，2026-07-14）

M1 代码完成（证据模型表 cloud#242 + runner cloud#243 + 报告 UI cloud#244）。以下是**操作员
在真实试点跑升级回归门**的步骤（ADR §B.7 步骤 7 的可执行部分；这是运维流程非代码）。

### C.1 前置
- 试点租户已开 `replayRetentionEnabled`（否则只能冻 hash，无法 semantic replay）——需数据准入授权。
- 试点策略已有一批 authenticated execute 历史（Execution 回放列有 canonical hash 地基），或准备手写边界 case。
- 操作员是平台管理员（`users.isAdmin=true`，DBA 授权）。

### C.2 升级**前**：冻结 golden
```
POST /api/admin/rule-regression
{ "action": "freeze",
  "policyId": "<policyId>",
  "policyVersionRowId": "<不可变版本行 id，可选>",
  "limit": 200,
  "handwrittenCases": [
    { "policyVersionRowId": "<版本行>", "functionName": "approveLoan",
      "input": { "creditScore": 680, ... }, "coverageTags": ["boundary","threshold"] },
    ... 覆盖 approve/deny/边界/null/Decimal/Date ...
  ]
}
```
- 返回 `fromExecutions`/`handwritten` 的 frozen/duplicate/skipped 计数 + **outputConflicts**。
- ★**outputConflicts 非空 = 冻结时就发现同 input 历史产不同 output = 已有漂移信号**，需先人工查因。
- 冻结即不可变。确认覆盖达标（≥ approve+deny+boundary，否则 run 会 FAIL_INSUFFICIENT_COVERAGE）。

### C.3 部署新版本（平台升级 / 新引擎 toolchain）
按正常发布流程部署。**注意**：runtime toolchain 变了，同 golden input 的 output hash 若变即漂移。

### C.4 升级**后**：跑门
```
POST /api/admin/rule-regression
{ "action": "run", "policyId": "<policyId>", "policyVersionRowId": "<版本行>" }
```
返回四态报告（落 RegressionReport，reportHash 防篡改）：
- **PASS**：覆盖达标 + 全 case output/input hash 与冻结一致 → 升级无未授权漂移。
- **FAIL_REGRESSION**：至少一 case output hash 变（漂移）或 input hash 不符（回放前提破坏）或编译失败。**阻断升级**，逐 case 查 `cases[].reason`（OUTPUT_HASH_MISMATCH/INPUT_HASH_MISMATCH/EVALUATE_FAILED）。
- **FAIL_INSUFFICIENT_COVERAGE**：覆盖不足，**不假通过**。补手写 case 重冻。
- **NON_REPLAYABLE**：无可运行 case（全 replay-limited/空）。查 PII opt-in + 冻结。

### C.5 CCO 审阅
打开 `/admin/rule-regression`（管理员），输入 policyId 查报告列表 + case 概览。CCO 据报告
（含 reportHash + toolchain digests + case ids）签字：「本报告覆盖的 version/cases/toolchain
范围内本次升级无未授权漂移」。**不签**「全客户全规则全未来绝对不漂移」（ADR §5.1）。

### C.6 M1 边界提醒（操作员须知）
- comparisonMode=FROZEN_BASELINE_VS_CURRENT_BACKEND：基线是**冻结时快照 hash**，非实时重跑 old toolchain。故**必须升级前冻结**（冻晚了基线已是新版）。
- 非确定性策略（依赖时间/随机）会产回归噪音（保守报 FAIL）——M1 不适合此类。
- replay-limited case（未开 PII 留存）只有 hash 无法 semantic replay，run 时计 NON_REPLAYABLE 不算通过。
