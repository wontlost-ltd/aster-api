# ADR 0016 — Core IR 字段级 parity（field-level IR equivalence）

Status: **全部 IMPLEMENTED** — 2026-06-09。
- 阶段1（归一比较器 + Ledger）：aster-lang-test#19 / aster-lang-core#17，197/207。
- 阶段2（消解分歧）：加 PatVariant（0参枚举）、Ok/Err/Some call-form、@entry params
  归一规则 + eval-exempt 边界 → **非 exempt 语料字段级全一致（202/207），剩 5 个
  effect/workflow/interop 派生分歧标 `divergent-exempt`（不算失败）**。
- 阶段3（升 PR-blocking）：CI `--mode=ir --full` 去 report-only/continue-on-error，
  对可执行树的新结构分歧现在会 block。
零未分类结构分歧。后续若做语言设计统一（namespace-call vs method-call）可消除
最后的 exempt 项，但不在本 ADR 范围。

## 背景

aster-lang 的双引擎等价保证目前覆盖两层：

- **parse-parity（Phase A，PR-blocking）**：两引擎都接受同一份源码。
- **eval-parity（Phase C，report-only→已达 100% eval-able 覆盖）**：两引擎对同一
  输入产出逐字节相同的运行时输出。

中间还有一层 **IR-parity（Phase B）**，当前实现是 **结构指纹**
（`scripts/parity-tier1.mjs --mode=ir` + `CoreIrFingerprintCli`）：只比对
`moduleName / declCount / declKinds / declNames` 四项，**不做字段级比较**。

指纹层存在的原因（见 `CoreIrFingerprintCli` 注释）：两引擎的 Core IR 在若干
字段上**命名/表示不一致**，逐字段对齐需要 ADR 级的跨仓协调。本 ADR 即承接该
“Phase B v2”，把 IR-parity 从指纹深化到字段级。

### 为什么需要字段级 parity？

parse-parity 只证明“都能解析”，eval-parity 只证明“跑出来一样”——但**两者之间
的 IR 可能形状不同却恰好跑出相同结果**（例如一侧把 `x plus y` 降为
`Call{Name '+'}`、另一侧降为 `BinOp{op:'+'}`，求值器各自处理后输出一致）。
IR 是“可信执行链”的中枢制品（[[commercial-readiness]] 把可信执行链定为护城河），
其跨引擎一致性应当被**独立**证明，而不是仅靠输出反推。字段级 parity 让我们在
**编译期**就锁定两引擎语义对齐，而非等到运行时分歧。

## 已知字段分歧（实证）

对一组样本 dump 两引擎完整 Core IR JSON 后，分歧归为三类：

### A. 字段命名不一致（须裁决：对齐 or 归一）

| 节点 | TS 字段 | Java 字段 | 处理 |
|---|---|---|---|
| `Import` | `name` / `asName` | `path` / `alias` | 别名表（IR_FIELD_ALIASES） |
| `Import` | （省略 version） | `version: null` | 归一 missing==null |
| `Func` | `declaredEffects: ["IO"]` | `effects: ["io"]` | 别名 + 小写排序集 |
| `Func`/`Field` | （省略空 `annotations`） | `annotations: []` | 归一 missing==空数组 |
| `PatCtor` | `names: ["id","name"]` | `args: [{kind:PatName,name}]` | 归一为 `binds: [...]` |

**实测后归一掉的“推断/元数据层”（ADR §B/§C，已剥离）**：`type`/`ret`/`typeParams`/
`typeInferred`/`retTypeInferred`/`constraints`（类型推断，两引擎策略不同）、`effectCaps`/
`effectCapsExplicit`（能力推断）、`captures`（闭包捕获，TS 捕获整个外层环境、Java 仅引用
子集）、`piiLevel`/`piiCategories`（PII 聚合）、`origin`（源码 span）。这些是派生分析
状态，非源码结构，剥离后比较聚焦“可执行树”。

### B. 表示差异（语义等价、形状不同 — 须归一规则）

- **类型推断噪声**：未标注参数的 `type`，TS 默认推成 `TypeName 'Text'`、`ret`
  推成 `TypeVar 'Unknown'`；Java 侧推断策略可能不同。这是**推断默认值**而非源码
  语义，应在比较时归一（剥离 `typeInferred:true` 的推断类型）或双方统一默认。
- **注解表示**：Java `Func` 有 `annotations` / `retAnnotations`（`@entry`/`@pii`），
  TS 在注解为空时**省略字段**以保 golden 基线。比较须按“缺失 == 空数组”归一。
- **effect 表示**：`effects` / `effectCaps` / `effectCapsExplicit` 双方都有，但
  顺序/默认值需核对。

### C. 不可比字段（应忽略）

- **`origin` 源码 span**（line/col）：两引擎位置编号约定不同（见 LSP 0-based
  备忘），且与 IR 语义无关，比较时整体剥离。
- 任何纯诊断/调试附加字段。

## 决策

### D1. 归一比较，而非强行对齐字段名（首选）

**不**把一侧的 IR JSON 字段名改成另一侧（那是对至少一个引擎 IR 契约的破坏性
变更，且 IR JSON 是 [[adr-0009-pii-cross-runtime]] 四层 CI 契约的一环，牵动
producer/consumer 制品）。而是在 `parity-tier1.mjs` 引入一个**归一化比较器**：

1. 按 `kind` 对齐节点；
2. 对 A 类已知重命名字段建一张**字段别名表**（`Import.path↔name` 等）；
3. 对 B 类按归一规则处理（缺失==空、剥离推断类型、排序无序集合）；
4. 对 C 类字段（origin 等）整体剥离；
5. 递归逐字段比较剩余“规范字段集”，输出 `{sample, path, ts, java}` 差异列表。

理由：归一器是**单点**、可演进、不污染任一引擎的 IR 产物；别名表把“已知可接受
的命名分歧”显式记录在案（可审计），新出现的分歧自动 surface 为 diff。

### D2. 规范字段集（normative）vs 忽略字段集（ignored）显式声明

在归一器顶部维护两张白/黑名单，每个条目附**理由注释**。规范集变更须在本 ADR
追加记录。原则：**凡影响求值语义的字段都是规范字段**（target/args/op/name/
body/statements/params 结构/ret 的标注部分/effects）；凡纯位置/推断默认/诊断的
字段都忽略。

### D3. 分阶段落地，先 report-only 再 PR-blocking

字段级 diff 先以 `--mode=ir --report-only` 跑全 manifest，把当前真实分歧清单
落进 `IR-DIVERGENCE-LEDGER.md`（仿 `DIVERGENT-MANIFEST.md`）。逐条消解（要么
进别名表=已知可接受，要么修引擎=真分歧）到**零未分类分歧**后，再把 IR-mode
升为 PR-blocking（与 parse-mode 并列）。避免一上来就 block 卡住所有 PR。

### D4. 不阻塞已达成的 eval-parity

IR-parity 是**补充**保证，不替代 eval-parity。两者正交：IR 比形状，eval 比输出。
即使 IR 字段级未全绿，eval-parity 100% 仍独立有效。

## 非目标

- 不追求两引擎 IR JSON **逐字节**相同（origin/字段序天然不同）。
- 不重构任一引擎的 lowering 以迁就对方（除非 surface 出**真**语义分歧）。
- 不把 IR-parity 做成运行时检查（纯编译期/CI 制品比对）。

## 实施计划（三阶段）

- **阶段1（归一器 + Ledger，report-only）**：`parity-tier1.mjs` 的 `runTsIr`/
  `runJavaIr` 从只回指纹改为回**完整 IR JSON**；新增 `normalizeIr()` + `diffIr()`；
  Java 侧 `CoreIrFingerprintCli` 增加一个 `parity.ir.full=true` 开关输出完整
  `coreJson`（复用 `MAPPER.writeValueAsString(coreModule)`，已存在于 EvalCli）。
  产出 `IR-DIVERGENCE-LEDGER.md` 初始清单。**交付物**：归一器 + ledger + 全样本
  跑通（report-only，不 block）。
- **阶段2（消解分歧）**：逐条处理 ledger——A 类命名进别名表（附理由）、B 类补归一
  规则、C 类进忽略集；真语义分歧开 issue 修引擎。目标：零未分类分歧。
- **阶段3（升 PR-blocking + nightly）**：`--mode=ir` 纳入 PR gate（与 parse 并列）；
  nightly 增 IR-parity 报告制品（仿 feature-coverage）。更新本 ADR Status→IMPLEMENTED。

验证：每阶段 `node scripts/parity-tier1.mjs --mode=ir` 全样本跑通；不得回退
parse(207/207)/eval(236/236) 现状。

## 关联

- 当前指纹实现：`aster-lang-test/scripts/parity-tier1.mjs`、
  `aster-lang-core/src/test/java/aster/core/dualengine/CoreIrFingerprintCli.java`
- eval-parity 与覆盖：[[eval-coverage-collections-gap]]（已达 100% eval-able）
- 特性覆盖盲点视角：[[feature-coverage-instrument]]
- IR 作为 CI 契约一环：[[adr-0009-pii-cross-runtime]]
- 商用定位（可信执行链=护城河）：[[commercial-readiness]]
