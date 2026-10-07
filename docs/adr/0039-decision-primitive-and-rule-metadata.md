# ADR 0039：Decision 原语与规则元数据（AI 治理方向·子项目 1）

- 状态：**PROPOSED**（2026-10-07。方向与路径已由用户拍板，实施计划待出）
- 决策者：用户已拍板「内置类型，不动语法」
- 相关：ADR 0037（可验证语义桥：提出 / 判定 / 复核三段式）、ADR 0032（Core IR span 贯通 trace 锚定）、ADR 0031（错误码单源注册表）、ADR 0034（What-if 的 DecisionInterpreter 三态）、ADR 0009（PII 类型系统一等公民）

---

## 0. TL;DR

Aster 要从「业务规则 Policy-as-Code」走向「AI 推理与真实动作之间的、人可读机器可验证的策略层」。
整条路线拆成六个子项目（§1），本 ADR 只定第一个：

> 让「允许 / 拒绝 / 需人工批准 / 升级」成为语言与运行时的**一等值**，让每条规则有**稳定身份**与**可读理由**，
> 从而让 aster-api 不再靠启发式**猜**一次执行是不是准入决策。

三个决定：

| 决定 | 内容 | 不做什么 |
|---|---|---|
| Decision 内置模块 | `Decision.allow()` / `Decision.deny(reason)` / `Decision.require_approval(role, reason)` / `Decision.escalate(reason)`，运行时值沿用 `__type` 约定 | 不改 ANTLR 语法、不改四个词法包、不加新的 Expr 节点 |
| 规则元数据注解 | `@id("…")`、`@reason("…")`、`@control("…")`（可重复） | 不加 `@version`，版本权威仍是 PolicyVersion 行 + contentHash |
| DecisionInterpreter 收缩 | 只认 Decision 值与 Boolean，其余一律 INDETERMINATE | 删除字符串、Map 键、反射三种猜测 |

---

## 1. 背景：整条路线与本 ADR 的位置

2026-10-07 的方向评审结论：Aster 已具备多语种 CNL → Core IR → 双引擎、哈希链审计、确定性重放、
证据包导出、四眼审批、ADR 0037 三段式复核；缺的是**运行期**面向 Agent 的守门面，以及把
「需要人批准」从策略返回的一个字符串升级为语言与运行时原语。

路线图：

| 序 | 子项目 | 产出 | 依赖 |
|---|---|---|---|
| **1** | **Decision 原语 + 规则元数据（本 ADR）** | 语言与 Core IR 层原语，DecisionInterpreter 退役 | 无 |
| 2 | Action Guard 服务面 | guard 端点、待审批动作实体、决策收据入哈希链、MCP 代理参考实现 | 1 |
| 3 | 证据链补齐 | 执行记录与证据包增加规则 ID / 模型身份 / 复核者 | 1 |
| 4 | Annex III 信贷试点 | 用信贷演示跑通端到端，产出对照 EU AI Act Article 14 的证据包 | 1、2、3 |
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

## 2. 决策一：Decision 内置模块

### 2.1 为什么是内置模块而不是新表达式节点

评估过三条路径：

| 路径 | 改动面 | 结论 |
|---|---|---|
| **A 内置模块**（采纳） | Truffle `Builtins`、TS `interpreter` 与 `type_system`、两侧符号表签名、新错误码 | 与 `Text.concat` 同机制；不动语法与词法包；运行时值是普通 Map |
| B 专用表达式节点（像 Ok/Err） | 解析器、AST、lowering、Core IR、模式匹配、两套引擎 | 语法更好看，但触点翻倍；Ok/Err 式硬编码恰是 ADR 0037 §2 反复出现的双引擎分叉源 |
| C Prelude 里的用户数据类型 | 几乎为零 | 类型检查无法特殊对待，无法强制 `@id`，解释器仍要猜 |

子项目 6 的 WHEN / THEN / REQUIRE 语法糖**降糖为同一个内置模块调用**，所以路径 A 不是过渡方案而是最终的语义载体。

### 2.2 签名

| 构造函数 | 参数 | 返回 |
|---|---|---|
| `Decision.allow()` | 无 | `Decision` |
| `Decision.deny(reason)` | `reason: Text` | `Decision` |
| `Decision.require_approval(role, reason)` | `role: Text`，`reason: Text` | `Decision` |
| `Decision.escalate(reason)` | `reason: Text` | `Decision` |

`Decision` 是新的内置类型名（TypeName），**不可**与 Boolean 隐式互转，两侧类型检查按既有 `TYPE_MISMATCH` 报错。
reason 对 deny / require_approval / escalate 为**必填**：没有理由的拒绝在审计上等于没有决策（与 ADR 0037 复核路由
「理由非空」的立场一致）。

CNL 写法（不新增任何关键字）：

```
@id("PII-001")
@reason("Personal information requires customer consent.")
@control("GDPR:ART6")
Rule check_pii_export given request, provider, customer, produce:
  If request.contains_pii and provider.is_external and not customer.has_consent:
    Return Decision.deny("Personal information requires customer consent.").
  Otherwise:
    Return Decision.allow().
```

### 2.3 运行时值形状

沿用 `aster-lang-truffle/src/main/java/aster/truffle/nodes/ResultNodes.java` 的 `__type` 约定，两侧引擎**逐字节一致**：

```json
{"__type":"Decision","outcome":"ALLOW"}
{"__type":"Decision","outcome":"DENY","reason":"…"}
{"__type":"Decision","outcome":"REQUIRE_APPROVAL","role":"Senior Underwriter","reason":"…"}
{"__type":"Decision","outcome":"ESCALATE","reason":"…"}
```

键顺序固定为 `__type`、`outcome`、`role`、`reason`（存在者按此序），进 `CanonicalJson` 与 parity 门禁。
未出现的键**不写 null**（与 ADR 0037 §2.2.1 对 `origin.file` 的 `undefined`/`"null"` 分叉教训一致）。

### 2.4 读取 Decision

CNL 侧读取用既有字段访问：`d.outcome`、`d.reason`、`d.role`。不为 Decision 增加模式匹配——这是路径 B 的触点，
且本子项目的消费方是运行时与 API，不是策略自身。

---

## 3. 决策二：规则元数据注解

复用既有注解语法（`annotation : AT name annotationArgs?`，参数支持字符串字面量），**不动语法**。

| 注解 | 参数 | 约束 | 用途 |
|---|---|---|---|
| `@id("PII-001")` | 一个字符串字面量 | 模块内唯一 | 规则稳定身份，进 trace 与执行记录 |
| `@reason("…")` | 一个字符串字面量 | 可选 | 规则级人类可读理由；与 Decision 的实例级 reason 分层 |
| `@control("EU_AI_ACT:ART14")` | 一个字符串字面量 | 可重复 | 挂到法规 / 控制项；子项目 5 的 Control 注册表以此为键 |

### 3.1 为什么不加 `@version`

规则版本在源码里手写必然漂移（改了规则忘了改号）。Aster 已有不可变 PolicyVersion 行与 contentHash（ADR 0030 / 0037），
那才是「哪个版本」的权威答案。决策记录引用「规则 `@id` + PolicyVersion 行 ID + contentHash」即可完整回答审计问题，
不需要第二个版本号。

### 3.2 Core IR

`CoreModel.Func.annotations` 已存在，**无结构改动**。lowering 增加校验：`@id` / `@reason` / `@control` 的参数必须是
恰好一个字符串字面量，否则报错（见 §4）。

---

## 4. 决策三：新诊断与错误码单源

| 码 | 类别 | 严重度 | 触发 |
|---|---|---|---|
| `W700` | governance | warning | 返回类型为 `Decision` 的 Rule 缺少 `@id` |
| `E701` | governance | error | 同一模块内 `@id` 重复 |
| `E702` | governance | error | `@id` / `@reason` / `@control` 参数不是恰好一个字符串字面量 |

走 `aster-lang-ts/shared/error_codes.json` 单源 + generator 同时产出 TS 与 Java 常量（ADR 0031），
**不**重蹈 W600 / W601 在各仓硬编码的覆辙。`governance` 是新类别；E7xx / W7xx 为治理段，W6xx 仍留给诊断类告警。

W700 为 warning 而非 error：现有策略库里返回 Boolean 的规则不受影响，新写 Decision 规则的人会立即看到提示。

---

## 5. aster-api 的变化

### 5.1 `PolicyEvaluationResult` 增加结构化字段

```json
{
  "result": {"__type":"Decision","outcome":"REQUIRE_APPROVAL","role":"Senior Underwriter","reason":"…"},
  "decision": {"outcome":"REQUIRE_APPROVAL","role":"Senior Underwriter","reason":"…"},
  "ruleId": "LOAN-017",
  "controls": ["EU_AI_ACT:ART14"],
  "executionTimeMs": 12,
  "error": null
}
```

`result` 原样保留（不破坏现有调用方）；`decision` / `ruleId` / `controls` 为新增字段，非 Decision 结果时为 null / 空数组。
`ruleId` 与 `controls` 来自被执行入口函数的 Core IR 注解，经 `CompilationResult.getMetadata()` 带出，不在运行时反查源码。

### 5.2 DecisionInterpreter 收缩为两条确定性规则

| 结果形态 | 解读 |
|---|---|
| `Map` 且 `__type == "Decision"` | 按 `outcome` 直接映射：ALLOW → APPROVED，DENY → DENIED，REQUIRE_APPROVAL → REQUIRE_APPROVAL，ESCALATE → ESCALATE |
| `Boolean` | true → APPROVED，false → DENIED（无损，不是猜） |
| 其余 | INDETERMINATE |

`Verdict` 枚举增加 `REQUIRE_APPROVAL` 与 `ESCALATE`。字符串、`approved` 键、反射三条路径**删除**。
What-if 回放（ADR 0033 / 0034）的 base / target 比较随之自动获得新两态。

### 5.3 不在本子项目范围

- 待审批动作的挂起 / 恢复实体与 API（子项目 2）
- guard 端点与 MCP 代理（子项目 2）
- aster-cloud `ExecutionDecision` 枚举扩展与证据包字段（子项目 3）
- WHEN / THEN 语法糖（子项目 6）

---

## 6. 迁移

### 6.1 破坏性变更清单

| 变更 | 受影响方 | 处理 |
|---|---|---|
| DecisionInterpreter 不再识别 `"APPROVED"` 等字符串 | 返回字符串档位的策略（已知：信贷演示） | 演示策略改为返回 Decision，档位文本放进 reason；What-if 历史比较对这类策略的结论从 APPROVED/DENIED 变为 INDETERMINATE，属**纠正**而非回归 |
| DecisionInterpreter 不再读 Map 的 `approved` / `decision` 键 | 语料与生产中**未发现**使用者（`aster-lang-test/corpus` 零命中） | 无需迁移；在 ADR 中留痕 |
| `Decision` 成为保留的内置类型名 | 用户自定义 `Define Decision …` 的模块 | 编译期报既有的重定义错误；语料零命中 |

### 6.2 回滚

本子项目全部为**加法 + 一处收缩**。回滚即恢复 DecisionInterpreter 的三条启发式分支；内置模块与注解留在语言里不会伤害任何现有策略。

---

## 7. 测试与验证

| 层 | 验证 |
|---|---|
| aster-lang-core | 单测：四个构造函数签名、`Decision` 不可与 Boolean 互转、W700 / E701 / E702 触发与不触发；golden：注解 lowering |
| aster-lang-ts | 同上镜像；interpreter 产出的值形状逐键断言 |
| aster-lang-truffle | Builtins 单测：四种 outcome 的 Map 形状与键序 |
| aster-lang-test | parity 语料新增 Decision 用例（四种 outcome × 有无 role），`--mode=ir --full` 与执行 parity 均须零分歧 |
| aster-api | DecisionInterpreter 三条路径单测；`evaluate-source` 集成测试断言 `decision` / `ruleId` / `controls`；What-if 回放对 Decision 策略的比较 |
| 错误码 | `validate_error_codes.ts` 通过；generator 产物两侧一致 |

本地验证顺序：core `./gradlew build goldenTest` → ts `pnpm test:unit` → truffle `./gradlew test` → aster-lang-test parity → api `./gradlew test`。

---

## 8. 发布顺序

语言级改动走 release train（ADR 0023）：platform → {core, runtime, validation, test} → {locales, hi} → truffle → {ts, aster-api}。
本子项目**不动** locales / hi（无新关键字），但 lockstep 版本号仍需 bump。注解与 Decision **一次发车**，避免两次跨仓链。

---

## 9. 决策记录

| 日期 | 决定 | 决策者 |
|---|---|---|
| 2026-10-07 | 方向：从「多语种编程语言」转向「AI 行动的可审计策略层」，拆六个子项目 | 用户 |
| 2026-10-07 | 子项目 1 采用路径 A（内置模块，不动语法） | 用户 |
| 2026-10-07 | 不加 `@version`，版本权威保持 PolicyVersion + contentHash | Claude 提议，用户随设计定稿 |
| 2026-10-07 | 错误码走单源注册表，新类别 governance，E7xx / W7xx | Claude 提议，用户随设计定稿 |

## 10. 尚未回答的问题

- `REQUIRE_APPROVAL` 的 `role` 是自由文本还是受控词表？本子项目按 Text 处理；子项目 2 定义待审批实体时若需要受控词表，在 Decision 之外用 `@control` 或 manifest 约束，不改本 ADR 的签名。
- `Decision` 是否应携带 `obligations`（如「执行前脱敏」）？YAGNI：没有消费方之前不加；子项目 2 的 guard 端点若需要，再以追加键的方式扩展，不破坏既有四键。
