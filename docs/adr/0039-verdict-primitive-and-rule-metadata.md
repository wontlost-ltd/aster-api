# ADR 0039：Verdict 原语与规则元数据（AI 治理方向·子项目 1）

- 状态：**IMPLEMENTING**（2026-10-07，子项目 1 六仓分支已实现，待 release train）
- 决策者：用户已拍板「内置类型，不动语法」
- 实施计划：`docs/superpowers/plans/2026-10-07-verdict-primitive-and-rule-metadata.md`；六仓分支 `feat/adr-0039-verdict`。验证证据见 §7；逐仓提交明细记在本地操作日志（工作文件，不入库）
- 相关：ADR 0037（可验证语义桥：提出 / 判定 / 复核三段式）、ADR 0032（Core IR span 贯通 trace 锚定）、ADR 0031（错误码单源注册表）、ADR 0034（What-if 的 DecisionInterpreter 三态）、ADR 0009（PII 类型系统一等公民）

---

## 0. TL;DR

Aster 要从「业务规则 Policy-as-Code」走向「AI 推理与真实动作之间的、人可读机器可验证的策略层」。
整条路线拆成六个子项目（§1），本 ADR 只定第一个：

> 让「允许 / 拒绝 / 需人工批准 / 升级」成为语言与运行时的**一等值**（内置类型 `Verdict`），让每条规则有**稳定身份**与**可读理由**，
> 从而让 aster-api 不再靠启发式**猜**一次执行是不是准入决策。

三个决定：

| 决定 | 内容 | 不做什么 |
|---|---|---|
| Verdict 内置模块 | `Verdict.allow()` / `Verdict.deny(reason)` / `Verdict.require_approval(role, reason)` / `Verdict.escalate(reason)`，运行时值沿用 `__type` 约定 | 不改 ANTLR 语法、不改四个词法包、不加新的 Expr 节点 |
| 规则元数据注解 | `@id("…")`、`@reason("…")`、`@control("…")`（可重复） | 不加 `@version`，版本权威仍是 PolicyVersion 行 + contentHash |
| DecisionInterpreter 收缩 | 只认 Verdict 值、Boolean、含布尔 `approved` 字段的记录，其余一律 INDETERMINATE | 删除字符串、`decision` 键、反射三种猜测 |

---

## 1. 背景：整条路线与本 ADR 的位置

2026-10-07 的方向评审结论：Aster 已具备多语种 CNL → Core IR → 双引擎、哈希链审计、确定性重放、
证据包导出、四眼审批、ADR 0037 三段式复核；缺的是**运行期**面向 Agent 的守门面，以及把
「需要人批准」从策略返回的一个字符串升级为语言与运行时原语。

路线图：

| 序 | 子项目 | 产出 | 依赖 |
|---|---|---|---|
| **1** | **Verdict 原语 + 规则元数据（本 ADR）** | 语言与 Core IR 层原语，DecisionInterpreter 退役 | 无 |
| 2 | Action Guard 服务面 | guard 端点、待审批动作实体、决策收据入哈希链、MCP 代理参考实现 | 1 |
| 3 | 证据链补齐 | 执行记录与证据包增加规则 ID / 模型身份 / 复核者 | 1 |
| 4 | Annex III 信贷试点 | 用信贷演示跑通端到端，产出对照 EU AI Act Article 14 的证据包 → **ADR 0044** | 1、2、3 |
| 5 | Control 注册表与影响分析 | 挂在 MappingIR / ProofIR 上 | 1 |
| 6 | Governance Profile 语法糖 + Cedar 编译目标 | 对外形态收窄、与现有网关互操作 | 1 |

子项目 1 是所有后续项的地基，且是**唯一**需要改语言核心并走 release train 的一项，因此单独成 ADR。

### 1.1 今天的问题（实证）

`aster-api/src/main/java/io/aster/policy/replay/batch/DecisionInterpreter.java` 把执行结果解读为
APPROVED / DENIED / INDETERMINATE 的方式：

| 结果形态 | 解读规则 |
|---|---|
| `Boolean` | true → APPROVED |
| `String` | `"approved"/"true"/"approve"` → APPROVED；`"rejected"/"denied"/"false"/"deny"` → DENIED；其余 INDETERMINATE |
| `Map` | 读 `approved` 键或 `decision` 键 |
| 任意对象 | 反射找 `isApproved()` |

它自己的注释写着「★不猜」，却不得不猜。后果：

- `aster-cloud/src/config/credit-risk-demo.ts` 的信贷演示返回**本地化文本档位**（`"转人工审核"`、`"批准 — 标准利率"`），
  在 cloud 的 `ExecutionDecision` 枚举里全部落入 `indeterminate`。演示里最有治理价值的「转人工」在数据层**不存在**。
- 「需要人批准」没有运行时语义，只能靠调用方读字符串再自己处理。
- 规则只有名字没有身份：`decisionTrace` 能锚定到 Core IR span（ADR 0032），但审计方问「哪条规则、哪个版本」时，
  答案只能到 PolicyVersion 行，到不了规则。

---

## 2. 决策一：Verdict 内置模块

### 2.1 为什么是内置模块而不是新表达式节点

评估过三条路径：

| 路径 | 改动面 | 结论 |
|---|---|---|
| **A 内置模块**（采纳） | Truffle `Builtins`、TS `interpreter` 与 `type_system`、两侧符号表签名、新错误码 | 与 `Text.concat` 同机制；不动语法与词法包；运行时值是普通 Map |
| B 专用表达式节点（像 Ok/Err） | 解析器、AST、lowering、Core IR、模式匹配、两套引擎 | 语法更好看，但触点翻倍；Ok/Err 式硬编码恰是 ADR 0037 §2 反复出现的双引擎分叉源 |
| C Prelude 里的用户数据类型 | 几乎为零 | 类型检查无法特殊对待，无法强制 `@id`，解释器仍要猜 |

子项目 6 的 WHEN / THEN / REQUIRE 语法糖**降糖为同一个内置模块调用**，所以路径 A 不是过渡方案而是最终的语义载体。

**为什么叫 Verdict 而不叫 Decision**：实测 `Decision` 已被用户侧占用——22 个文件使用
`Define Decision has approved, reason` 这种自定义记录（parity 语料 `tier1-equivalence/policies/*.aster`、
tier3 fixtures、`aster-cloud/src/data/policy-examples.ts` 的策略模板）。把它收为内置类型名会让这些模块编译报重定义，
租户复制过模板的现网策略也会在升级后变红。`Verdict` 在决策时的全部语料、模板、测试里零命中（现仅本子项目新增的 Verdict 用法），且 aster-api 内部已用
`Verdict` 命名判定结果，语义一致。

### 2.2 签名

| 构造函数 | 参数 | 返回 |
|---|---|---|
| `Verdict.allow()` | 无 | `Verdict` |
| `Verdict.deny(reason)` | `reason: Text` | `Verdict` |
| `Verdict.require_approval(role, reason)` | `role: Text`，`reason: Text` | `Verdict` |
| `Verdict.escalate(reason)` | `reason: Text` | `Verdict` |

`Verdict` 是新的内置类型名（TypeName），**不可**与 Boolean 隐式互转，两道防线同时成立：

- **类型检查**：`Verdict` 出现在布尔上下文——`If` 条件（语句级与表达式级）、`not` 的操作数、`equals to` / `!=` 的任一操作数——
  两侧都报既有 `TYPE_MISMATCH`（expected `Bool`）。只针对 `Verdict`，其它类型的既有宽松行为不变（语料零影响）。
- **运行时 fail-closed**：绕过检查器（如直接提交 Core IR）时，布尔上下文遇到 `__type == "Verdict"` 的值直接失败，
  错误信息两侧同文 `Verdict cannot be used as Bool; compare its outcome field instead`（truffle `Builtins.toBool`、
  TS 解释器 `isTruthy`，覆盖 If / 表达式级 if / not / and / or）。此前两侧都按「非空对象为真」处理，`DENY` 会被当成放行。

reason 对 deny / require_approval / escalate 为**必填**：没有理由的拒绝在审计上等于没有决策（与 ADR 0037 复核路由
「理由非空」的立场一致）。空串与仅空白的 reason / role 两侧运行时都拒绝（Java `isBlank()`、TS `trim() === ''`，
信息 `… must not be empty`），与 `@id` 拒空白同口径。参数类型按 `Text` 比较前先展开别名：`given reason as Text` 的形参
在 Java 侧（`Text` 是 `String` 的内置别名）与 TS 侧都可直接作 reason / role。

两侧类型检查器都没有通用的内置函数签名表，`Verdict.*` 在 Java `BaseTypeChecker` 与 TS `typecheck/expression.ts` 中特判，口径一致：

| 情形 | 诊断 |
|---|---|
| 参数个数与上表不符（如 `Verdict.deny()`、`Verdict.allow("x")`） | `E703 GOV_VERDICT_CALL_ARITY`（参数 `func` / `expected` / `actual`），返回类型仍为 `Verdict` |
| 个数相符但参数不是 Text（如 `Verdict.deny(42)`） | 既有 `TYPE_MISMATCH` |
| 未知成员 `Verdict.xxx` | 未定义变量错误，不回落为 Unknown |
| 用户写 `Define Verdict …` | 既有 `DUPLICATE_SYMBOL`：Java 在符号表中先于用户声明预占 `Verdict`；TS 由治理检查报出 |

`Verdict` 同时加入两侧解析器的内置类型名集合——Java `AstBuilder.BUILTIN_TYPE_NAMES`、TS `decl-parser` 的 `BUILTINS`
（TS 另加入 `typecheck/generics.ts` 的 `KNOWN_SCALARS`）。两侧解析器会把未知的大写类型名推断为类型参数，
不加入则 `produce Verdict` 被当成泛型 `T`（TS 报 E006）。

CNL 写法（不新增任何关键字；本例在两侧类型检查器均零诊断，TS 解释器实测 `(true, true, false)` → DENY、其余 → ALLOW）：

```
@id("PII-001")
@reason("Personal information requires customer consent.")
@control("GDPR:ART6")
Rule check_pii_export given contains_pii as Bool, is_external as Bool, has_consent as Bool, produce Verdict:
  If has_consent:
    Return Verdict.allow().
  If contains_pii:
    If is_external:
      Return Verdict.deny("Personal information requires customer consent.").
  Return Verdict.allow().
```

写法约束（均为实测）：形参必须带类型——TS 把无类型形参定为 `Text`，`request.contains_pii` 报 E011；
必须写 `produce Verdict`——省略时 Java 按规则名推断返回类型（Bool / Text），报 E003。本例未用 `and` / `not`：
Java 类型检查器对运算符名报未定义变量（见 §10），属既有问题，不在本子项目处理。

### 2.3 运行时值形状

沿用 `aster-lang-truffle/src/main/java/aster/truffle/nodes/ResultNodes.java` 的 `__type` 约定，两侧引擎**逐字节一致**：

```json
{"__type":"Verdict","outcome":"ALLOW"}
{"__type":"Verdict","outcome":"DENY","reason":"…"}
{"__type":"Verdict","outcome":"REQUIRE_APPROVAL","role":"Senior Underwriter","reason":"…"}
{"__type":"Verdict","outcome":"ESCALATE","reason":"…"}
```

键顺序固定为 `__type`、`outcome`、`role`、`reason`（存在者按此序），进 `CanonicalJson` 与 parity 门禁。
未出现的键**不写 null**（与 ADR 0037 §2.2.1 对 `origin.file` 的 `undefined`/`"null"` 分叉教训一致）。

parity 语料为 aster-lang-test `tier1-equivalence/policies/verdict-basic.aster`（四种 outcome × 有无 role，5 例）。
IR 门禁比对前，`packages/js/src/ir-normalize.ts` 把注解参数的容器形状归一（TS `args` 数组折叠为 `params` 映射），
只改形状不改值；两引擎注解 IR 形状本身尚未统一，见 §10。

### 2.4 读取 Verdict

CNL 侧读取用既有字段访问：`d.outcome`、`d.reason`、`d.role`。三个字段在两侧类型检查器中都定型为 `Text`
（Java `BaseTypeChecker.verdictFieldType`、TS `expression.ts` 字段访问路径；其它字段名 TS 仍报 E011，Java 仍为 Unknown）。
判断裁决应比较 `d.outcome equals to "DENY"`，而不是把 `d` 当布尔用（§2.2）。不为 Verdict 增加模式匹配——这是路径 B 的触点，
且本子项目的消费方是运行时与 API，不是策略自身。

parity 语料 `verdict-fields.aster`（3 例）覆盖 `Text` 形参作 reason 与 `d.outcome` / `d.role` 读取。

---

## 3. 决策二：规则元数据注解

复用既有注解语法（`annotation : AT name annotationArgs?`，参数支持字符串字面量），**不动 ANTLR 语法**。

位置参数：Java `AstBuilder` 原本即接受 `@id("X")`，把第一个位置参数命名为 `$0`（Core IR `params:{"$0":"X"}`）；
TS 解析器原本只接受具名参数 `@id(value: "X")`，现改为同样接受位置参数并以 `$0` 命名（Core IR `args:[{name:'$0',value:'X'}]`）。
两侧治理检查都只读键名 `$0`；IR 容器形状的分歧见 §10。

| 注解 | 参数 | 约束 | 用途 |
|---|---|---|---|
| `@id("PII-001")` | 一个字符串字面量 | 模块内唯一 | 规则稳定身份，进 trace 与执行记录 |
| `@reason("…")` | 一个字符串字面量 | 可选 | 规则级人类可读理由；与 Verdict 的实例级 reason 分层 |
| `@control("EU_AI_ACT:ART14")` | 一个字符串字面量 | 可重复 | 挂到法规 / 控制项；子项目 5 的 Control 注册表以此为键 |

两引擎必须一致的校验口径：

- `@id` 每条规则**恰好一个**：出现多个 `@id` 报 E702；空串或仅空白的 `@id` 视为无效，报 E702（返回 Verdict 的规则因此同时报 W700）。
- 注解名为 null 的注解直接跳过，不得抛异常。
- 「规则返回 Verdict」的判定：声明返回类型为 TypeName `Verdict`，**或**函数体内任一 `Return` 的表达式是 `Verdict.*` 的直接调用。
  扫描进入 `Block`、`Scope`、`If` 两支与 `Match` 各分支；**不**追踪变量（`Let d = Verdict.allow(). Return d.` 只靠声明返回类型识别）。
  在 Java 侧，函数体扫描这一支实际总与 E003 同现：省略 `produce:` 时 Java 按规则名推断返回类型（Bool / Text），
  返回 Verdict 即报 `RETURN_TYPE_MISMATCH`。因此「省略返回类型、靠函数体识别」在 Java 上只起到让 W700 不漏报的作用，
  可编译的 Verdict 规则必须写 `produce Verdict`。
- 规则名不能是冠词 `a` / `an` / `the`：canonicalizer 会去掉冠词，用作规则名会使声明消失。

### 3.1 为什么不加 `@version`

规则版本在源码里手写必然漂移（改了规则忘了改号）。Aster 已有不可变 PolicyVersion 行与 contentHash（ADR 0030 / 0037），
那才是「哪个版本」的权威答案。决策记录引用「规则 `@id` + PolicyVersion 行 ID + contentHash」即可完整回答审计问题，
不需要第二个版本号。

### 3.2 Core IR

`CoreModel.Func.annotations` 已存在，**无结构改动**，lowering 不做校验。`@id` / `@reason` / `@control` 的参数必须是
恰好一个字符串字面量，由两侧类型检查器中的治理检查（Java `GovernanceChecker`、TS `typecheck/governance.ts`）报 E702 / W700（见 §4）。

---

## 4. 决策三：新诊断与错误码单源

| 码 | 类别 | 严重度 | 触发 |
|---|---|---|---|
| `W700` | governance | warning | 返回类型为 `Verdict` 的 Rule 缺少 `@id` |
| `E701` | governance | error | 同一模块内 `@id` 重复 |
| `E702` | governance | error | `@id` / `@reason` / `@control` 参数不是恰好一个字符串字面量；`@id` 为空白或同一规则出现多个 `@id` |
| `E703` | governance | error | `Verdict.*` 调用参数个数不符（`GOV_VERDICT_CALL_ARITY`，见 §2.2） |

走 `aster-lang-ts/shared/error_codes.json` 单源 + generator 同时产出 TS 与 Java 常量（ADR 0031），
**不**重蹈 W600 / W601 在各仓硬编码的覆辙。`governance` 是新类别；E7xx / W7xx 为治理段，W6xx 仍留给诊断类告警。

W700 为 warning 而非 error：现有策略库里返回 Boolean 的规则不受影响，新写 Verdict 规则的人会立即看到提示。

返回类型不一致（一支 `Return Verdict.allow().`、另一支 `Return true.`）不新增码：两侧 `checkIf` 在分支汇合处报既有
`IF_BRANCH_MISMATCH`（E008）并以 then 支类型继续，测试接受 E008 或 `RETURN_TYPE_MISMATCH`（E003）任一出现。
要求是「必须报类型错误、不能靠 Unknown 放过」，不要求特定码。

---

## 5. aster-api 的变化

### 5.1 两条评估路径增加结构化字段

aster-api 有两条评估路径，都附加 `decision` / `ruleId` / `controls`：

| 路径 | 入口 | 携带方式 |
|---|---|---|
| 源码即时评估 | `POST /evaluate-source`；`POST /evaluate-json`；runner `StandaloneReplayExecutor` | `DynamicCnlExecutor.ExecutionResult` → `EvaluationResponse` |
| 部署策略 | `POST /evaluate`、`POST /evaluate/batch` | `PolicyEvaluationResult`（含缓存副本）→ 复制进 `EvaluationResponse` |

```json
{
  "result": {"__type":"Verdict","outcome":"REQUIRE_APPROVAL","role":"Senior Underwriter","reason":"…"},
  "decision": {"outcome":"REQUIRE_APPROVAL","reason":"…","role":"Senior Underwriter"},
  "ruleId": "LOAN-017",
  "controls": ["EU_AI_ACT:ART14"],
  "executionTimeMs": 12
}
```

- `result` 原样保留，不破坏现有调用方。
- `ruleId` / `controls` 反映**被执行规则**的 `@id` / `@control` 注解，与结果类型无关（Boolean 规则同样带出）；无注解时为 null / 空列表。
- `decision`（`VerdictView`）**仅**在结果是 `__type == "Verdict"` 的值时非 null，不做任何猜测。
- 序列化：`decision` 内不写 null 键、按字母序；`EvaluationResponse` 中三个字段缺省时（null / 空列表）不输出，非治理策略的响应字节不变
  （`PolicyEvaluationReplayOrderingTest` 逐字节 golden 通过）。
- 元数据来源：部署路径在每次实际执行后把 `coreJson` 解析为 `CoreModel.Module`、按入口函数名读注解（`RuleMetadata.of`）；
  源码路径直接读已编译的 `CoreModel.Module`。**不**经 `CompilationResult.getMetadata()`，不在运行时反查源码。
  `coreJson` 解析失败记 warn 并按无元数据处理，绝不让评估因元数据失败。元数据随已编译策略一起缓存为后续工作（§10）。

### 5.2 DecisionInterpreter 收缩为三条确定性规则

| 结果形态 | 解读 |
|---|---|
| `Map` 且 `__type == "Verdict"` | 按 `outcome` 直接映射：ALLOW → APPROVED，DENY → DENIED，REQUIRE_APPROVAL → REQUIRE_APPROVAL，ESCALATE → ESCALATE |
| `Boolean` | true → APPROVED，false → DENIED（无损，不是猜） |
| `Map` 且 `approved` 键为 `Boolean` | true → APPROVED，false → DENIED（模板约定 `Define Decision has approved, reason`，类型确定，无损） |
| 其余 | INDETERMINATE |

三条规则都是**类型驱动**的确定性映射，不再有字符串语义猜测。`DecisionInterpreter.Verdict` 枚举为五值：
APPROVED、DENIED、REQUIRE_APPROVAL、ESCALATE、INDETERMINATE。字符串、`decision` 键、反射三条路径**删除**
（已实现，`DecisionInterpreterTest` 15 例）。规则按上表顺序判定：Verdict 值优先于 `approved` 字段。
What-if 回放（ADR 0033 / 0034）的 base / target 比较随之获得新两态：`ReplayBatchService` 把 REQUIRE_APPROVAL / ESCALATE
当作**真实的非批准决策**参与比较（target 记为未通过），而不是 INDETERMINATE 式的「无法判定」。
已知粒度限制：What-if 只区分「批准 / 非批准」，baseline 为 REQUIRE_APPROVAL、target 为 DENY 时两者都是非批准，计为「无变化」；
按 outcome 细分的比较随子项目 3 一并细化。
**已解决（ADR 0043）**：What-if 逐条落 base/target 四态结论码并按转移对汇总，REQUIRE_APPROVAL→DENY 现计为 changed。

### 5.3 不在本子项目范围

- 待审批动作的挂起 / 恢复实体与 API（子项目 2）
- guard 端点与 MCP 代理（子项目 2）
- aster-cloud `ExecutionDecision` 枚举扩展与证据包字段（子项目 3）
- `policy-examples.ts` 模板改写为 Verdict 推荐写法、`credit-risk-demo.ts` 信贷演示迁移（子项目 4，见 §6.1）
- WHEN / THEN 语法糖（子项目 6）

本子项目对 aster-cloud 只做一处兼容：`cnl-executor.ts` 的 `parseApprovalFromResult` 识别 Verdict 值——
ALLOW → approved；DENY → denied 并带 reason；REQUIRE_APPROVAL / ESCALATE（及未知 outcome）→ `approved:false, indeterminate:true`，
直到子项目 3 扩展 cloud 枚举。此前 cloud 把 Verdict 对象判为 `Unknown result format` → denied。

---

## 6. 迁移

### 6.1 破坏性变更清单

| 变更 | 受影响方 | 处理 |
|---|---|---|
| DecisionInterpreter 不再识别 `"APPROVED"` 等字符串 | 返回字符串档位的策略（已知：`credit-risk-demo.ts` 信贷演示） | What-if 历史比较对这类策略的结论从 APPROVED/DENIED 变为 INDETERMINATE，属**纠正**而非回归；演示策略改为返回 Verdict（档位文本放进 reason）随子项目 4 进行 |
| DecisionInterpreter 不再读 Map 的 `decision` 字符串键 | 语料、模板、测试中**零命中** | 无需迁移；留痕 |
| DecisionInterpreter 不再反射找 `isApproved()` | 仅宿主对象可能命中，CNL 策略不可能产生 | 无需迁移；留痕 |
| `Verdict` 成为保留的内置类型名 | 用户自定义 `Define Verdict …` 的模块 | 语料、模板、测试中零命中；编译期报既有重定义错误 |

**不破坏**：`Define Decision has approved, reason` 模板约定继续被 §5.2 第三条规则识别；现网策略无需改动。
`policy-examples.ts` 模板改为推荐写法 `Verdict.allow()` / `Verdict.deny(reason)` **推迟到子项目 4**：多语言模板使用本地化关键字，
改写涉及 zh / de 词法，与信贷演示迁移一并处理；旧写法仍合法。本子项目只让 cloud 结果解析器正确识别 Verdict（§5.3）。
（ADR 0044：用研 seed 模板已迁 Verdict；`policy-examples.ts` 仍待迁，列入 ADR 0044 §10。）

### 6.2 回滚

本子项目全部为**加法 + 一处收缩**。回滚即恢复 DecisionInterpreter 的三条启发式分支；内置模块与注解留在语言里不会伤害任何现有策略。

---

## 7. 测试与验证

| 层 | 验证 |
|---|---|
| aster-lang-core | 单测：四个构造函数签名、`Verdict` 不可与 Boolean 互转、W700 / E701 / E702 / E703 触发与不触发；golden：注解 lowering |
| aster-lang-ts | 同上镜像；interpreter 产出的值形状逐键断言 |
| aster-lang-truffle | Builtins 单测：四种 outcome 的 Map 形状与键序 |
| aster-lang-test | parity 语料新增 Verdict 用例（四种 outcome × 有无 role），`--mode=ir --full` 与执行 parity 均须零分歧 |
| aster-api | DecisionInterpreter 三条路径单测（`DecisionInterpreterTest`）；REST 端到端 `PolicyEvaluationVerdictRestTest`（`@QuarkusTest`，HMAC profile，真实 Postgres）：带 `@id` / `@control` 的 Verdict 策略响应含 `decision` / `ruleId` / `controls`，Bool 策略响应三者均不出现。What-if 回放对 Verdict 策略的比较**尚无测试**（见 §10） |
| 错误码 | `validate_error_codes.ts` 通过；generator 产物两侧一致 |

本地验证顺序：core `./gradlew --offline test` 与 `goldenTest` 分开运行 → ts `pnpm typecheck && pnpm lint && pnpm test:unit` → truffle `./gradlew --offline test`
→ core `publishToMavenLocal -x test` + ts `pnpm build` → aster-lang-test 三道门禁 → api `:replay:test` 与 `:test` → cloud `pnpm vitest run src/__tests__/services`。

实测结果（2026-10-07 终审修复后，各仓 `feat/adr-0039-verdict`）：

| 仓 | 命令 | 结果 |
|---|---|---|
| aster-lang-core | `./gradlew --offline test --rerun-tasks` | 1732 tests，0 失败，7 跳过（既有）；governance 30/30（`VerdictTypeCheckTest` 15、`GovernanceCheckerTest` 15）。计时型 `CanonicalizerReDoSTest.gateItselfCanFail` 曾单次偶发失败，重跑通过，与本改动无关 |
| aster-lang-core | `./gradlew --offline goldenTest --rerun-tasks` | 11 tests，0 失败，5 跳过（既有）。`test goldenTest` 组合运行因 goldenTest 未声明对 `:jar` 的依赖被 Gradle 9.6.1 拒绝，属既有构建问题 |
| aster-lang-ts | `pnpm typecheck && pnpm lint && pnpm test:unit` | typecheck 通过；lint 0 error（1 条既有 warning）；unit 1917/1917 |
| aster-lang-ts | 错误码 generator `--check`、`validate_error_codes`、parity 测试 | 一致；76 个码；8/8（终审修复前实测，本轮未改错误码） |
| aster-lang-truffle | `./gradlew --offline test` | 869 tests，0 失败，3 跳过（既有）；`VerdictBuiltinsTest` 11/11 |
| aster-lang-test | `parity-tier1.mjs --mode=ir --full` | 225/225 identical |
| aster-lang-test | `parity-tier1.mjs --mode=eval` | 721/721 identical（verdict-basic 5 例 + verdict-fields 3 例全部一致） |
| aster-lang-test | `test-eval-guard.mjs`；`tag-eval-exempt.mjs --check` | 22 过 / 0 失败；计数与豁免章一致 |
| aster-api | `./gradlew --offline :replay:test` | 97/97 |
| aster-api | `:test --tests 'io.aster.policy.rest.PolicyEvaluationVerdictRestTest'`（Podman `postgres:17-alpine` 临时库） | 2/2 |
| aster-api | `:test --tests 'io.aster.policy.replay.batch.*' --tests 'io.aster.policy.api.PolicyQueryServiceTest'`（同上临时库） | 9 个类 109/109（`DecisionInterpreterTest` 15） |
| aster-api | `:test --tests 'io.aster.policy.rest.*' --tests 'io.aster.policy.api.*'`（终审修复前实测，临时库） | 30 个类 229/229 |
| aster-cloud | `pnpm vitest run`（policy + cnl-result-parser） | 12 files / 172 passed；`tsc --noEmit` 通过（终审修复前实测，本轮未改 cloud） |

parity 脚本需 Node ≥ 23.6（加载 `.ts` 归一器；本机默认 22.13 报 `ERR_UNKNOWN_FILE_EXTENSION`），CI 用 Node 24。

---

## 8. 发布顺序

语言级改动走 release train（ADR 0023）：platform → {core, runtime, validation, test} → {locales, hi} → truffle → {ts, aster-api}。
本子项目**不动** locales / hi（无新关键字），但 lockstep 版本号仍需 bump。注解与 Verdict **一次发车**，避免两次跨仓链。

---

## 9. 决策记录

| 日期 | 决定 | 决策者 |
|---|---|---|
| 2026-10-07 | 方向：从「多语种编程语言」转向「AI 行动的可审计策略层」，拆六个子项目 | 用户 |
| 2026-10-07 | 子项目 1 采用路径 A（内置模块，不动语法） | 用户 |
| 2026-10-07 | 不加 `@version`，版本权威保持 PolicyVersion + contentHash | Claude 提议，用户随设计定稿 |
| 2026-10-07 | 错误码走单源注册表，新类别 governance，E7xx / W7xx | Claude 提议，用户随设计定稿 |
| 2026-10-07 | 内置名改为 `Verdict`（`Decision` 已被 22 个用户侧文件占用） | 用户 |
| 2026-10-07 | DecisionInterpreter 保留「记录含布尔 `approved` 字段」为第三条确定性规则 | 用户 |
| 2026-10-07 | 新增 `E703 GOV_VERDICT_CALL_ARITY`：两侧类型检查器没有内置签名表，`Verdict.*` 参数个数原本无人检查 | Claude（实施计划） |
| 2026-10-07 | TS 解析器接受位置参数注解并以 `$0` 命名；ANTLR 语法不动。理由：Java `AstBuilder` 本已接受 `@id("X")` 并命名为 `$0`，TS 只接受具名参数会让同一源码在两侧一边通过一边报错；统一为 `$0` 后两侧治理检查读同一键名 | Claude（实施计划） |
| 2026-10-07 | `Verdict` 加入两侧解析器内置类型名集合（及 TS `KNOWN_SCALARS`），否则 `produce Verdict` 被推断为类型参数 | Claude（实施计划；TS `KNOWN_SCALARS` 为实施中发现，评审确认必要） |
| 2026-10-07 | 「两分支返回类型不一致」接受 E008 或 E003 任一：要求的是必须报类型错误；两侧 `checkIf` 已在分支层报 E008，改为逐条校验 Return 会给既有语料新增诊断且超出范围。风险：Match 分支等结构可能仍放过，由 TS 镜像用例与终审覆盖 | Claude（执行控制） |
| 2026-10-07 | `returnsVerdict` 的 Return 扫描进入 `CoreModel.Scope`（独立语句类型，不扫会漏报 W700）；TS 同口径，避免两引擎 W700 分叉 | Claude（执行控制） |
| 2026-10-07 | 空白 `@id`、同一规则多个 `@id` 均报 E702（「恰好一个」）；null 注解名不得抛异常。先在 Java 定口径，TS 镜像照此；若过严可放宽 | Claude（执行控制） |
| 2026-10-07 | IR 门禁对注解参数形状分叉只在 aster-lang-test `ir-normalize.ts` 归一（TS `args` 折叠为 `params`），不改任一引擎 IR 形状：值逐条相同、eval 718/718 一致，形状统一牵涉 TS IR 消费者与 cloud。归一只改形状不改值，值分叉仍会被抓到 | Claude（执行控制） |
| 2026-10-07 | `ruleId` / `controls` 反映被执行规则的注解，不限于 Verdict 结果（规则身份对任何决策记录都有用）；`decision` 仅对 Verdict 结果非空。代价：非 Verdict 策略的响应多两个可空字段（缺省不输出） | Claude（执行控制） |
| 2026-10-07 | `/evaluate-json` 与 runner `StandaloneReplayExecutor` 一并透传新字段：同一路径的不同入口，不透传会出现同一结果不同形状 | Claude（执行控制） |
| 2026-10-07 | `policy-examples.ts` 模板改写与信贷演示迁移推迟到子项目 4；本子项目只让 cloud 结果解析器识别 Verdict（此前判为 Unknown → denied） | Claude（实施计划） |
| 2026-10-07 | Verdict 在布尔上下文（If 条件、`not`、相等比较操作数）两侧类型检查器报 `TYPE_MISMATCH`，两侧运行时直接失败（fail-closed）。终审发现 TS 检查器全部放行、两侧运行时都把任何 Verdict（含 DENY）当真值 | 终审裁定 |
| 2026-10-07 | 空白 reason / role 两侧运行时拒绝（Java `isBlank()`、TS `trim() === ''`），与 `@id` 拒空白同口径 | 终审裁定 |
| 2026-10-07 | Verdict 字段 `outcome` / `role` / `reason` 两侧定型为 `Text`；Java 比较 reason / role 参数前先展开 `Text`→`String` 别名，个数不符时仍逐个推断参数类型 | 终审裁定 |

## 10. 尚未回答的问题

- **What-if 回放对 Verdict 策略的比较未测**：`ReplayBatchService` 对 REQUIRE_APPROVAL / ESCALATE 的处理只有 `DecisionInterpreter` 单测间接覆盖，
  没有以 Verdict 策略为 base / target 跑一次 What-if 的测试；连同 §5.2 的「批准 / 非批准」粒度限制在子项目 3 补齐。
- **Java 类型检查器对运算符名报未定义变量**：经治理测试同一条编译链（canonicalize → parse → lower → `TypeChecker`），
  `x plus 2`、`x equals to 1`、`not x`、`x and y` 都报 `E101 Undefined variable: +/==/not/and`（`BaseTypeChecker.checkCall` 先对
  `call.target` 求类型）。基线分支即如此，与 Verdict 无关；本子项目的 Verdict 布尔上下文用例因此在 Java 侧同时出现 E101 与 `TYPE_MISMATCH`，
  断言只针对后者。修正属独立工作。

- `REQUIRE_APPROVAL` 的 `role` 是自由文本还是受控词表？本子项目按 Text 处理；子项目 2 定义待审批实体时若需要受控词表，在 Verdict 之外用 `@control` 或 manifest 约束，不改本 ADR 的签名。
- `Verdict` 是否应携带 `obligations`（如「执行前脱敏」）？YAGNI：没有消费方之前不加；子项目 2 的 guard 端点若需要，再以追加键的方式扩展，不破坏既有四键。
- **注解 IR 容器形状分歧**：TS 注解 IR 为 `args:[{name,value}]`，Java 为 `params:{key:value}`，原本被 cross-compiler 对 `annotations` 的剪枝掩盖。
  本子项目两侧治理检查各读自己的形状（键名 `$0` 一致），parity IR 门禁只在 aster-lang-test `ir-normalize.ts` 归一。
  真正统一（改 TS lowering 或 Java 输出）牵涉 TS IR 消费者与 aster-cloud，与 ADR 0037 节点身份工作合并处理。
- **分配预算计量不对称**：TS `chargeAllocation` 对普通对象计 0，Truffle 对 Map 计 size + 字符串长度；Verdict 值随之继承该不对称。
  该不对称对 Ok/Err 等既有值早已存在，两引擎输出仍逐字节一致，只影响逼近 `MAX_ALLOCATION_BUDGET` 时的失败时机，本子项目不处理。
- **热路径 `coreJson` 解析**：部署路径每次实际执行都把 `coreJson` 解析为 `CoreModel.Module` 以读注解，多一次 JSON 反序列化。
  应在编译缓存中与已编译策略一起保存 `RuleMetadata`。
- **休眠的 Redis 缓存值类型**：`application.properties` 声明 `quarkus.cache.redis.policy-results.value-type=PolicyEvaluationResult`，
  但当前缓存类型为 caffeine（存对象引用，不序列化）。若切到 Redis，新字段会被写成 `"decision":null,"ruleId":null,"controls":[]`，
  且该类没有无参构造或 `@JsonCreator`，Redis 模式在本子项目之前即无法反序列化——属既有潜在问题，切换缓存前须一并修正。
