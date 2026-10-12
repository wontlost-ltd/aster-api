# ADR 0047：Cedar 导出目标（AI 治理方向·子项目 6b）

- 状态：**PROPOSED**（2026-10-12）
- 实施计划：待写（`docs/superpowers/plans/2026-10-12-cedar-export.md`）
- 决策者：用户已拍板「方向为导出到既有 Cedar 执行端；首版产出通用 Cedar 文件（策略文本 + schema），不直推 AWS；require_approval/escalate 编译为带注解的 forbid；输入映射沿用 Action Guard 请求形状；不可翻译即整体拒绝导出；编译器放在 aster-lang-core（Java），由 aster-api 提供端点」
- 相关：ADR 0039（§1 行 6）、ADR 0040（Action Guard 请求形状）、ADR 0045（控制注册表）、ADR 0046（档案、语法糖、E705–E707）、ADR 0031（错误码单一来源）、ADR 0041（证据链）

---

## 0. TL;DR

团队继续在 Aster 里写策略、做治理（多语种、档案、证据、重放）；本 ADR 把**可翻译子集**的 Verdict 规则编译成
**标准 Cedar 策略文本 + Cedar JSON schema**，交给客户已有的 Cedar 执行端（开源 Cedar、网关插件、AWS Verified
Permissions 的导入工具）执行。Aster 仍是唯一事实源与证据链；每次导出都用官方 Cedar 授权器对同一组探针请求
与 Aster 引擎逐一比对，**结论一致才放行**。导出要么整体成功，要么给出全部不可翻译点。

## 1. 现状与问题

| 现状 | 位置 | 后果 |
|---|---|---|
| 策略只能在 Aster 引擎（aster-api / guard）里执行 | — | 已有 Cedar 网关的团队要么双写规则，要么把每次请求都绕到 aster-api |
| guard 请求已规范为 principal / action / resource / context 八字段 | `io.aster.guard.GuardActionMapper` | 与 Cedar 的 PARC 模型天然对应，但没有任何导出路径 |
| Verdict 有四种结论（allow/deny/escalate/require_approval） | ADR 0039 | Cedar 只有 permit/forbid，需明确降维语义 |
| Aster 规则按序首匹配；Cedar 无序、forbid 优先 | — | 直译会改变结论，必须显式编码顺序 |

## 2. 方向与范围

- **方向**：Aster → Cedar 单向导出。不导入 Cedar，也不在 guard 内嵌 Cedar 预检。
- **首版目标**：通用 Cedar 文件，下载 / API 获取；**不**存租户 AWS 凭据、不直推 Verified Permissions（可在后续版本叠加，产物格式无需改变）。
- **编译器位置**：aster-lang-core 新包 `aster.core.cedar`，输入 Core IR，输出策略文本、schema（JSON）与诊断；core 不引入新依赖。
- **校验与等价性**：在 aster-api，用官方 `com.cedarpolicy:cedar-java`（Maven Central；版本与原生库打包方式在实施计划首个任务中核实）。

## 3. 可导出形状与请求映射

规则满足以下全部条件才可导出：带 `@id`；只有一个参数且其类型的字段名集合为 guard 请求八字段
（principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model，按字段名匹配）；返回 `Verdict`。

| Aster 字段 | Cedar |
|---|---|
| `principal_id` | `principal` = `Aster::Principal::"<id>"` |
| `principal_type` | `principal.type`（String） |
| `principal_roles` | `principal.roles`（Set<String>）；成员判断 → `principal.roles.contains("…")` |
| `action` | `action` = `Aster::Action::"<name>"`；规则里比较过的每个 action 名都进 schema 的 actions |
| `resource_type` / `resource_id` | `resource` = `Aster::Resource::"<id>"`，属性 `type` |
| `agent_model` | `context.agent_model` |
| `context.<key>` | `context.<key>`；类型按用法推断（与数字比较 → Long，与文本比较 → String），schema 中声明为可选，访问处守卫 `context has <key>` |

实体类型统一在 `Aster` 命名空间；principal/resource 的「类型」作为属性而非独立实体类型（guard 中它们是自由文本）。

## 4. 规则体翻译

### 4.1 顺序

第 k 个分支（`If` 或 `When` 语法糖）的守卫 = 自身条件 ∧ ¬(前面所有分支的条件)；最终的 `Otherwise` / 落空 `Return`
守卫 = ¬(全部前序条件)。因此任一请求恰好命中一条策略，与 Aster 首匹配一致；forbid 优先规则永不介入。

### 4.2 结论

| Verdict | Cedar |
|---|---|
| `allow()` | `permit` |
| `deny(reason)` | `forbid`，`@aster_outcome("deny") @aster_reason("…")` |
| `require_approval(role, reason)` | `forbid`，`@aster_outcome("require_approval") @aster_approver("…") @aster_reason("…")` |
| `escalate(reason)` | `forbid`，`@aster_outcome("escalate") @aster_reason("…")` |

每条策略另带 `@id("<rule-id>#<branch>")`、`@aster_rule`、`@aster_module`、`@aster_source_hash`；规则有 `@control` 时带
`@aster_controls("A:B,…")`，模块声明档案时带 `@aster_profile("…")`。纯 Cedar 执行端遇到非 allow 结论一律 fail-closed；
读取决定性策略注解的网关可把 require_approval/escalate 路由到 Aster 审批流。Cedar 默认拒绝，与「未命中 allow 分支」一致。

### 4.3 可翻译子集

- 支持：Int / Text 上的 `==` `!=` `<` `<=` `>` `>=`；`and` / `or` / `not`（自 1.0.32 起两引擎短路一致）；Bool 字段；角色成员判断；
  `Let`（在使用处内联）；Int 上仅含字面量操作数的 `plus` / `minus` / `times`。
- 不支持（报错）：Decimal / Float 值（Cedar `decimal` 固定 4 位且舍入不同）；调用其他函数或规则；List / Map / Text 库调用；
  `Match`；副作用与 workflow；除法与取模；两个类型未知的 context 键之间的比较。

### 4.4 诊断（ADR 0031 单一来源，同步全部副本）

| 码 | 名称 | 含义 |
|---|---|---|
| E720 | CEDAR_NOT_EXPORTABLE_SHAPE | 缺 `@id`、参数不是 guard 请求形状、或非 Verdict 规则 |
| E721 | CEDAR_UNSUPPORTED_CONSTRUCT | 不可翻译的构造（带位置与构造名） |
| E722 | CEDAR_UNTYPED_CONTEXT_KEY | 同一 context 键被以冲突类型使用 |
| E723 | CEDAR_EQUIVALENCE_MISMATCH | 等价性探针不一致（编译器缺陷，带失败探针） |
| E724 | CEDAR_SCHEMA_INVALID | Cedar 严格校验不通过（编译器缺陷） |

整体导出：存在任一错误即只返回诊断、不返回策略文本。声明档案且违反档案（E705/E706）的模块同样不可导出。

## 5. 等价性（每次导出都执行）

编译器按规则内容生成探针请求：规则提到的每个 action、角色、principal 类型各一，外加一个未提及值；每个数值比较取
字面量及其 ±1；每个 context 键取存在与缺失两态。每个探针同时交给 Aster 引擎与 Cedar 授权器（带生成的 schema）：
Aster `allow` ⇔ Cedar `Allow`；其余结论 ⇔ Cedar `Deny` 且决定性策略恰为一条 forbid，其 `@aster_outcome` / `@aster_reason`
与 Aster 一致。任何不一致 → E723。探针数量有上限（初值 512），响应中报告实际条数。另以 Cedar 校验器严格模式校验
策略与 schema（E724）。

## 6. 接口与证据

- **API**：`POST /api/v1/policies/export/cedar`，入参二选一：`{policyId, versionId}`（已保存版本）或 `{source, locale, aliasSet}`（与编译端点一致）。
  出参：`{exportable, policies, schema, ruleId, profile, controls, probes:{count, agreed}, diagnostics}`；不可导出时 HTTP 200 + `exportable:false` + 诊断。
- **审计**：已保存版本导出成功时，审计哈希链追加 `POLICY_EXPORTED` 事件：版本 id、`target:"cedar"`、策略文本与 schema 各自 SHA-256、探针条数。临时源码导出不记录。
- **aster-cloud**：策略详情页在激活版本可导出时提供「导出为 Cedar」，下载 `<module>.cedar` 与 `<module>.cedarschema.json`，诊断在编译诊断同一面板展示；四语文案（hi 用英文）。不读取任何租户凭据。

## 7. 测试与发版

- core：源码 → Cedar 文本 + schema 的黄金测试；E720–E722 各用例；首匹配顺序用例。
- aster-api：基于 `cedar-java` 的等价性测试；故意破坏的 emitter 必须被 E723 捕获；E724 用例；端点与审计事件测试。
- 样本：guard 探针策略与新样本 `guard-cedar-sample`（角色、context 数值、四种结论、Let）均须导出且探针全部一致。
- aster-cloud：按钮与下载路径单测；本地栈 Playwright 一次。
- 发版：core 改动与新错误码随下一次发版列车；ts 只同步错误码表。

## 8. 不做 / 后续

- 直推 AWS Verified Permissions（凭据、部署状态、漂移）。
- Cedar 导入、guard 内嵌 Cedar 预检。
- Decimal 支持（需要对齐 Cedar decimal 语义）、跨规则调用的内联、参数注解式映射。
- 编辑器内实时 Cedar 预览（需 TS 实现或 WASM）。
