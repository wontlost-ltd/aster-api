# 发版列车 1.0.44 执行拆解：Task 16 + 跨仓 X1–X3 + 三个引擎 issue（补充计划）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**性质：** 已批准计划 `docs/superpowers/plans/2026-11-08-guard-receipts-and-input-provenance.md`（下称「主计划」）中 Task 16 与跨仓 Task X1–X3 的**细化**，外加列车顺带的三个引擎 issue 的取舍。本文不改变主计划的目标与范围；与主计划冲突处以本文为准，并在下文「对主计划的更正」逐条列出理由。

**Goal:** 1.0.44 列车交付 registry 1.2.0（`requires.verifiedInputs` 键，全部档案为 `false`）、两引擎 `inputRefs` 与 E708、E708 三语 + hi 译文；aster-api 先落不依赖新引擎的 Task 16 部分，pin 升到 1.0.44 后再落依赖新引擎的部分。**行为不变**：列车上线后没有任何档案要求 verifiedInputs，任何现有策略的诊断、IR（声明档案的 Verdict 规则多一个 `inputRefs` 字段除外）与 guard 结果都不变。

**明确不在本列车：** X4（cloud 签发能力、`fact-signer.ts`、试点签发方）与 registry **1.3.0** 翻转 `eu-ai-act-high-risk.requires.verifiedInputs=true`。1.3.0 **保持阻塞**，解除条件：

1. 本列车上线；
2. aster-api Task 16b（下文 PR 15）已部署。它关闭 from-evidence 的 fail-open，并且让评估事件**自带** `inputRefs`（另写 `verifiedInputsRequired` 快照，仅供审计）。cloud 的 evaluate-source 模块没有已保存版本，from-evidence 只能从事件读 `inputRefs`；要求项一律由事件 `profile` + 当前注册表在消费时重算（见 §1.4）；
3. X4 完成。

**Spec（按此顺序读）：** aster-api `docs/adr/0046-governance-profile.md` §11（R62，尤其 D2 传播规则表与 §11.4 落地顺序）、`docs/adr/0040-action-guard.md` §11（R60，D5 执行点）、主计划 Task 15/16 与「跨仓任务」节。

**Task 15 / 阶段 2 评审转入 Task 16 的事项（原文件 `t16-carryovers.md` 未入库，内容抄录于此）：**

1. 关闭 from-evidence 在 ruleMetadata 读不到（无版本的 evaluateSource 模块）时的 fail-open：用评估事件的 profile 键加注册表判定，要求 verifiedInputs 时给 `input_refs_unavailable`。不关闭的代价：1.3.0 翻转后 from-evidence 门可被绕过。
2. `/decisions` 与 from-evidence 都用按版本固定的 `ruleMetadata(tenant, module, function, versionId)`，并把同一 versionId 交给评估。
3. `verifiedInputsRequired` 是模块级属性，`RuleMetadata.moduleOnly` 也要带上它。
4. `inputRefs` 必须是请求路径（`context.*`、`principal.*`）；规则读的是扁平键（`GuardActionMapper.flatten`），所以要么引擎直接产出请求路径，要么在 aster-api 映射。
5. RuleMetadata 按编译产物 memo：目前每次 `/decisions` 都要多解析一次 Core IR。
6. 元数据读不到时打日志并计数。
7. ADR 注记：gate DENY 会消耗证据的 correlation id。
8. Javadoc 写明：显式 ref 即使请求中缺该字段也计入。
9. 裁定：registry 1.3.0 翻转在 Task 16 关闭 from-evidence fail-open 之前保持阻塞。

---

## 0. 现状核对（2026-11-12，各仓 `origin/main`）

| 事实 | 证据 |
|---|---|
| 生态当前 1.0.43；ts npm 1.4.0；ui-messages 1.0.30；hi npm 1.0.24；test npm 1.2.0 | aster-lang-platform `release-plan.json` |
| aster-api 引擎 pin 在 `settings.gradle:19`（`aster-lang-platform:1.0.43`），CI 兄弟仓 ref 在 `.github/actions/setup-aster-build/action.yml:62`（`v1.0.43`）——**不在** `gradle.properties` | aster-api#476 先例同改这两处 |
| X1 守护测试三仓都**还没有**；`inputRefs` / `verifiedInputs` / `E708` 各仓均不存在 | `git grep` 全空 |
| aster-api 读的注册表是 **core jar 内的副本**（`ControlRegistry.loadDefault()`，资源 `/governance/controls-registry.json`），不是 locales | `ControlRegistryStartup.java:15`、`CompileResponse.java:65` |
| core 副本由 `ControlRegistrySiblingParityTest` 与 locales 深度相等（CI 下缺 locales 即失败）；ts 副本 `src/governance/controls-registry.data.ts` 由 `test/unit/governance/controls-registry-parity.test.ts` 同法守护；cloud 副本 `src/config/controls-registry.json` 的 parity 测试在 CI 中跳过 | 三个测试文件 |
| locales `DiagnosticOverlays` 合同：zh / de overlay 必须**覆盖码表全部错误码**；hi 同法（`HiInDiagnosticOverlayTest`）；locales / hi 的 `error_codes.json` 副本须与 ts `shared/error_codes.json` 逐字节相等（`verifyErrorCodesBaseline`） | locales `test-support/build.gradle.kts`、1.0.41 教训 |
| ts `diagnostic-overlays.generated.ts` 由 `pnpm generate:diagnostic-overlays` 从 core `exportLexicons`（含 locales / hi jar）生成 | 文件头注释 |
| IR parity（`aster-lang-test` `--mode=ir --full`，PR-blocking）**不**剥离新字段；`governance-sugar.aster` / `governance-sugar-long.aster` 声明 `Profile "governed"` 且在 tier1-parity 清单内 | `packages/js/src/ir-normalize.ts:108`、`corpus/tier1-parity/manifest.json:129-130` |
| Truffle 自带 `CoreModel`，`Loader` 关闭 `FAIL_ON_UNKNOWN_PROPERTIES`：新增 `Func.inputRefs` 被忽略，truffle 无需改动 | `aster-lang-truffle Loader.java:21` |
| Core IR schema 契约：新增可选字段不算 breaking，`CoreIrSchemaVersion` 与 `CORE_IR_ABI=1.1` 都**不**升 | `CoreIrSchemaVersion.java` 契约段 |
| aster-api 有 `parse_diagnostics_parity.json` 的逐字节副本（`ParseDiagnosticsParityTest.fixtureIsByteIdenticalToCore`） | aster-api#476 |
| `PolicyCompileResourceTest:421` 硬编码 `controlsRegistryVersion` 为 `"1.1.0"`，pin 升级后必红 | 该行 |

## 对主计划的更正

1. **X3 不能晚于 X2。** 主计划说 X3（E708 译文）「不阻塞 X2」。但 E708 一进码表，locales / hi 的 overlay 合同就要求译文同时存在，否则 L2 构建失败（同 1.0.41 E05x 事故）。所以 X3 并进 X2 的同名分支组，同批合并。ADR 0051 §16.1 R56 的英文回退仍然有效，但它只说明**运行时**缺译文时怎么显示，不能当作**构建**可以缺译文的理由。
2. **ADR 0046 §11.4 的 ② 与 ③ 合并为同一批同名分支 PR。** 原因：core / ts 的注册表副本 parity 测试要求「locales 与副本同时变」。D1 要的安全性（旧解析器忽略新键）由 X1 证明（PR 3/4 在 `v1.0.43` tag 上也跑一遍），不靠 ② 先于 ③ 的时间差。
3. **Task 16 的 pin 位置**：改 `settings.gradle` 与 `setup-aster-build/action.yml`，不是 `gradle.properties`。另外「locales pin ≥ 1.2.0」实际等价于「core pin ≥ 1.0.44」，因为 aster-api 从 core jar 读注册表。
4. **`GovernanceDiagnostics.isProfileCode` 在 `:84-86`**（调用处 `:74`），不在 `:66-79`。类 Javadoc 的码表清单在 `:15`，档案码说明在 `:19`，两处都要补 E708。
5. **ts X1 用例落在 `test/unit/governance/controls-registry-profiles.test.ts`**，主计划写的 `controls.test.ts` 不存在。
6. **X4 的 1.3.0 翻转需要引擎列车**（ADR 0046 §11.2 D5 第 3 项说「不需要」）。原因：aster-api 读 core jar 内的注册表副本，而该副本受 parity 测试约束必须与 locales 相等；翻转因此需要 core 发版，再由 aster-api 升 pin。在 PR 1 的 ADR 补注里写明，X4 计划据此排期。
7. **`inputRefs` 的路径空间**：ADR 表里的 `principal.roles`、`action.name` 是 guard 请求路径。引擎能看到的只有规则第一个参数的字段名，即 `GuardActionMapper.flatten` 产出的 `principal_roles`、`action`、`context.amount`。
   - 决定：引擎产出**源码字段路径**，与 guard 无关，编辑器展示的也是作者写的名字。
   - aster-api 按**入口形状**映射，见 §1.4：
     - `POST /decisions` 用 guard 形状，`GuardInputRefPaths` 与 `flatten` 共用同一张常量键表；
     - from-evidence 用证据形状，评估输入是 `context`，参数名进入路径前缀。
   - PR 1 改写 ADR 表述。

---

## 1. 接口（列车内固定，之后不改形）

### 1.1 registry 1.2.0

```json
{ "id": "eu-ai-act-high-risk", "title": { … },
  "requires": { "ruleId": true, "registeredControls": true, "frameworks": ["EU_AI_ACT"], "verifiedInputs": false } }
```

- `version` → `"1.2.0"`；**两个**现有档案（`governed`、`eu-ai-act-high-risk`）都显式写 `"verifiedInputs": false`。
- locales 校验（`build.gradle.kts` `profileRequiresProblems`）：键**可缺省**（缺省 = `false`，兼容注入的旧形态注册表），出现时必须是布尔，否则报 `档案 <id> 的 requires.verifiedInputs 须为布尔`。
- 两引擎解析（core `ControlRegistry.readProfile`、ts `controls.ts readProfile`）与上面同口径：
  - 缺省 → `false`；
  - 出现但不是布尔 → 该档案不登记（与 `ruleId` 非布尔一致），声明它的模块得到 E705；
  - 其他未知键忽略。
- core：`record Profile(String id, boolean ruleId, boolean registeredControls, List<String> frameworks, boolean verifiedInputs)`。保留 4 参构造，委托 `verifiedInputs=false`，避免既有调用方编译断裂。
- ts：`ProfileDef.verifiedInputs: boolean`。
- cloud `RegistryProfile.requires` 类型加可选 `verifiedInputs?: boolean`，属 X4 / ⑥，不在本列车。

### 1.2 `Func.inputRefs`（Core IR，两引擎）

- 类型：`List<String>` / `readonly inputRefs?: readonly string[]`。core 字段加 `@JsonInclude(NON_NULL)`。
- **写入条件**：模块声明了档案（`module.profile != null`，不论注册表里是否有该档案、要求项是什么），**且**该函数「返回 Verdict」（与 `GovernanceChecker.returnsVerdict` 同口径）。其余函数不写该键，未声明档案的模块 IR 逐字节不变。
- **计算位置**：在 **R2（TypedReferenceResolver）之后**，不在 lowering 末尾。原因：R2 负责把本地化名称规范化，并判定 `x.f` 是记录字段还是 Map 键；在 R2 之前计算，zh / de 源码会产出本地化路径。
  - core：在 `aster.core.resolve.NameResolution.resolveAndLower`（`resolveAndLowerChecked` 经它）取得 R2 结果之后调用；
  - ts：在 `src/compile-pipeline.ts:80` 的 `resolveTypedReferences(lowerModule(...))` 之后调用；
  - 分析器是纯函数，不读注册表：core 为新类 `aster.core.typecheck.governance.InputRefsAnalyzer`，ts 为新文件 `src/governance/input-refs.ts`。
  - R2 之后仍会复制 Func 的通道必须保留该字段：
    - core：`resolve/CoreCopies.java:232` 附近的 Func 复制（`TypedReferenceResolver` 在分析之前运行，不需要改）；
    - ts：`src/frontend/resolve-policy-types.ts`、`src/nodeid/node-id-map.ts`、`src/mapping/mapping-ir.ts`、`scripts/core-ir-json.ts`。
  - `DeclarationResolver` 工作在 AST 层，不涉及该字段。
  - 实施时用 `git grep retTypeInferred` 逐处核对：凡在 R2 之后复制 `retTypeInferred` 的地方都要复制 `inputRefs`。
- **取值**：
  - 按 ADR 0046 §11.2 D2 第 1 条传播规则表；路径用点连接，最多 8 段；按 UTF-16 码元字典序去重（Java `String.compareTo` 与 JS 默认 `sort` 同序）。
  - `"*"` 为顶元。出现 `"*"` 时列表**只含** `["*"]`，因为 `*` 已覆盖其余路径；这样两引擎不需要对「`*` 与具体路径并存」的排序达成一致。
  - 规则不读输入时为 `[]`，与 null（未提供）区分。
- **补充规则**（PR 1 写入 ADR 0046 §11.6，两引擎按此实现）：
  - (a) 直接消费第一个参数本身（标量参数如 `given amount`，或整条记录参与比较）→ 路径 `""`（根）。整体转文本 / 序列化仍为 `"*"`。
  - (b) 字面量 Map 键为空串或含 `.` → `"*"`（点路径无法无歧义表示）。
  - (c) 函数第一个参数之外的参数不追踪（ADR 只定义第一个参数）。
- **被调函数分类**（I2）：按**模块限定名**判定。R2 之后的 Call 目标名是规范全名；它等于本模块某个 Func（`<module>.<name>`），或者是未限定的本模块名，才按同模块调用内联。经 `Use … as Alias` 导入的函数，即使别名与本模块函数同名，也按跨模块调用处理（→ `*`）。
- E708 的 `construct` 取**先序遍历中第一个产生 `*` 的节点**的类别。遍历顺序：
  - 规则体语句按源码顺序；
  - 遇到同模块调用时，先遍历实参，再**进入被内联的被调函数体**（深度优先），然后回到调用点之后继续；
  - 同一表达式的子节点按 Core IR 字段声明顺序。
  - construct 闭集如下：`dynamic-map-key`、`map-iteration`、`whole-input-serialized`、`cross-module-call`、`function-value`、`inline-depth`、`recursion`、`path-too-long`、`unrepresentable-key`。

### 1.3 E708

| 项 | 值 |
|---|---|
| 枚举 | core `ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE("E708", Category.GOVERNANCE, Severity.ERROR, …)`；ts `ErrorCode.GOV_PROFILE_INPUT_UNRESOLVABLE = "E708"`（生成物） |
| 英文 message | `Rule '{rule}' reads inputs that cannot be resolved statically ({construct}); profile '{profile}' requires verified inputs` |
| 英文 help | `Read input fields by literal path (for example req.context.amount), or move the dynamic access out of this rule, so its input references are known at compile time.` |
| 触发 | 档案存在于注册表且 `verifiedInputs=true`，且 Verdict 规则的 `inputRefs` 为 `["*"]`；span = 规则 origin；每条规则至多一条 |
| 不触发 | 未声明档案；档案未知（已报 E705）；`verifiedInputs=false`（1.2.0 下恒为此情形） |
| 译文键 | `diagnostic-messages.json` / `diagnostic-help.json` 的 `"E708"`，四语：zh-CN、de-DE（locales），hi-IN（hi），en-US 来自码表。占位符集合 `{rule} {profile} {construct}` 必须与英文一致，`construct` 值不翻译 |
| aster-api 级别 | `GovernanceDiagnostics.isProfileCode` 纳入 E708 → `"error"`，编译 `success: true`（同 E706） |

### 1.4 aster-api 侧

- `RuleMetadata(ruleId, controls, profile, inputRefs, verifiedInputsRequired)`：形状不变（T15 已加）。
  - PR 15 起，`of(module, fn, registry)` 读 `Func.inputRefs`；`verifiedInputsRequired` 由 replay 中**唯一的**静态 helper `ProfileRequirementsLookup.verifiedInputsRequired(registry, profileId)` 计算。
  - `RuleMetadata.of` 与 aster-api 的 `ProfileRequirements` 都调用这个 helper，不各写一份。
  - 模块级值写入 `moduleOnly`。
- **未知档案在运行期按 `false`**：编译期已报 E705 且无法保存。代价：如果 D5 第 4 项的 `…-verified` 自定义档案在某个注册表版本里被回滚删除，声明它的模块会悄悄变成不要求。见 §4 第 3 条。
- **评估事件自带输入引用**（I1，PR 15）：
  - `EvaluationEvidence` 在 `KEY_PROFILE` 旁新增两个键：
    - `KEY_INPUT_REFS = "inputRefs"`：**写事件时就已映射好的请求路径**（N2），不是引擎原样路径。from-evidence 拿不到 IR（evaluate-source 不落版本），也无法事后推断评估输入里第一个参数对应哪个键，所以映射必须在写事件时完成。
    - `KEY_VERIFIED_INPUTS_REQUIRED = "verifiedInputsRequired"`。
  - 只在模块声明档案时写入；无档案事件的 metadata 逐字节不变。
  - **映射发生在写事件处**（证据形状）：
    - 评估输入经 `NamedContextMapper.mapContext`（`replay/.../api/convert/NamedContextMapper.java`，由 `DynamicCnlExecutor.java:325-326` 调用）匹配第一个参数。这个映射接受命名 Map（键可能是本地化写法）或位置数组。
    - 只有当第一个参数**由命名 Map 中的某个键实际匹配**时，才把路径映射为 `context.<实际匹配到的输入键>.<path>`；`""` 映射为 `context.<该键>`。
    - 位置数组、无法确定匹配键、或路径含 `*` → `["*"]`。
    - `MappingResult` 新增「第一个参数实际匹配到的输入键」（可为 null），供写事件处使用。
  - **数据从哪里来**（更正：`PolicyEvaluationResult` 现在没有 RuleMetadata，见 `PolicyEvaluationResult.java:31-40`；evaluate-source 的证据由 `phase.execResult()` 构造，见 `PolicyEvaluationResource.java:654-666`）：
    - 在 `PolicyEvaluationResult` 和 evaluate-source 的执行结果（`phase.execResult()` 的类型）上新增 `@JsonInclude(NON_NULL) List<String> inputRefs`（已映射）与 `@JsonInclude(NON_NULL) Boolean verifiedInputsRequired`（无档案时为 null）。
    - 两个字段都是 NON_NULL：`/evaluate` 的响应体、`PolicyEvaluationResultJacksonTest` 的序列化结果对无档案模块不变；缓存中的旧结果缺这两个键，反序列化为 null，按「旧事件」处理。
  - **大小上限**（N1）：`AuditChainAppender.MAX_METADATA_SIZE = 4096`（`AuditChainAppender.java:44`）。超长 metadata 会被截断成 `substring + "...truncated"`（`:221-228`），变成非法 JSON；`GuardEvidenceDecisionService.metadataOf` 解析失败后返回 `Map.of()`，导致今天的流量也会得到 409 `evidence_not_approvable`。因此：
    - `inputRefs` 序列化后 **≤ 512 字节，且 ≤ 32 项**（原定 1024，实测下调，见下）；超过任一上限时整体写 `["*"]`。
    - 取值理由：路径受 8 段、`context` 深度 ≤ 8 和 255 字符文本限制约束，正常规则的引用集远小于 32 项。原定 1 KB 给现有键（correlationId、ruleId、controls、reason、role、profile、版本与两个哈希、agent 三键、inputSource、locale、dynamicExecution）在各自校验上限下留出约 3 KB。写 `["*"]` 在 1.2.0 下无害（没有档案要求 verifiedInputs），1.3.0 之后是 fail-closed。
    - 测试 `EvaluationEvidenceSizeTest`：把每个既有键填到其校验上限，再加上上限内的 `inputRefs` 和 `verifiedInputsRequired`，序列化长度必须 < 4096。若实测超出，下调 `inputRefs` 上限并在本节更新数值，**不得**提高 `MAX_METADATA_SIZE`，因为那属于审计链改动。
    - **实测结果（PR 15 / aster-api#492）**：其余键填到校验上限后约占 3524 字符，只剩约 570，因此上限定为 **512 字节**；ADR 0041 与 ADR 0046 §11.6 第 4 条已同步。
  - from-evidence 优先读事件里的这两项。这与「版本列取事件行」的既有做法一致，也是 evaluate-source 模块唯一可靠的来源，因为它们没有已保存版本，`ruleMetadata` 必然抛错。
  - **要求项永远在消费时重算**（替代审查裁定 1）：是否要求 verifiedInputs 由事件 `profile` + **当前**注册表判定（按事件版本取到的元数据若显示要求，同样算要求）；事件里的 `verifiedInputsRequired` 是写事件时的快照，只供审计，**不参与判定**。否则 1.3.0 翻转前写下、`verifiedInputsRequired=false` 的事件在翻转后会放行未验证输入。事件自带的 `inputRefs` 是编译事实，照常使用。
  - from-evidence 引用集的优先级：事件 `inputRefs` → 按事件 `policyVersionId` 取的 IR `inputRefs`（引擎原样路径，缺写事件时的匹配键，一律按 `*`）→ 都没有且要求 → DENY `input_refs_unavailable`。
  - 映射后的上限按 JSON 数组的 UTF-8 字节数计；`[]` 照写；IR 没有 `inputRefs`（旧引擎编译的档案模块）时事件不写该键，消费方回退到「profile + 注册表」。
- **路径映射按入口形状二分**（`GuardInputRefPaths`）：
  - **guard 形状**（仅 `POST /decisions`，规则参数是 `flatten` 记录）：
    - 映射表：`principal_id→principal.id`、`principal_type→principal.type`、`principal_roles[.…]→principal.roles`、`action→action.name`、`resource_type→resource.type`、`resource_id→resource.id`、`agent_model→agent.model`、`context→context`、`context.<p>→context.<p>`。
    - 标量字段带子路径（`action.x`、`principal_id.x`、`principal_type.x`、`resource_type.x`、`resource_id.x`、`agent_model.x`）→ `*`。
    - `""`、`"*"`、未知首段 → `*`。
    - 映射表是 `GuardActionMapper` 与 `GuardInputRefPaths` 共用的**常量表**，同一个来源。
  - **证据形状**（from-evidence）：**不在 from-evidence 时映射**。事件里的 `inputRefs` 在写事件时已经映射为 `context.<实际匹配键>.<path>`（见上），from-evidence 直接与请求的输入来源比对。
  - 「待用户确认」第 1 条按此落地：`""` 在 guard 形状下为 `*`；在证据形状下为 `context.<实际匹配键>`，没有匹配键时为 `*`。
- `CompileResponse` 新增 `@JsonInclude(NON_NULL) Map<String, List<String>> inputRefs`：键为函数名，值为**引擎原样路径**（作者视角），只有至少一个函数带 `inputRefs` 时才出现。无档案模块的响应逐字节不变。

---

## 2. PR 清单（按合并顺序）

分支命名规则：需要联合验证的跨仓 PR 用**同名分支**，checkout-sibling 才能对齐；互不相关的 PR **禁止**同名。合并后立即删除分支，避免陈旧同名分支被后续 CI 选中（platform#114 教训）。

### 阶段 A：列车前，各自独立合并（顺序即下表）

| # | 仓 / 分支 | 内容 | 测试 |
|---|---|---|---|
| 1 | aster-api `docs/r62-input-refs-clarify` | 见下文 PR 1 | 文档 PR，用户审阅合并（硬门：阶段 B 的 PR 8–12 合并前须先合并） |
| 2 | aster-api `feat/guard-t16a-pre-pin` | 见下文 PR 2（Task 16a，不依赖新引擎） | 见下文 |
| 3 | aster-lang-core `test/r62-registry-unknown-keys` | X1：`ControlRegistryTest` 加 `requires含未知键时加载成功且忽略`（`verifiedInputs: true` 与 `futureKey: {}` 同在 → 档案登记成功、四个已知字段值不变） | 本用例；另在 `v1.0.43` tag 上 cherry-pick 本用例运行一次，结果贴 PR 描述（证明线上引擎忽略新键） |
| 4 | aster-lang-ts `test/r62-registry-unknown-keys` | X1：`test/unit/governance/controls-registry-profiles.test.ts` 同名用例 | 同上，`v1.4.0` tag 上跑一次 |
| 5 | aster-cloud `test/r62-registry-unknown-keys` | X1：扩展现有 `src/__tests__/services/evidence/control-registry-sources.test.ts`（带 `requires.verifiedInputs` / `futureKey` 的注册表经 `profileTitle` 与证据导出读取不抛错、结果不变） | 本文件；随 cloud 下一次常规部署，不进列车 |
| 6 | aster-lang-ts `fix/304-effect-name-parse` | ts#304，裁定 (a)：`src/parser/type-parser.ts` `parseEffectList` 接受任意基效果名；`src/lower_to_core.ts:342` 不再对未知效果报 E601，改为在**检查阶段按 pure 处理**，与 core 一致。效果名**仍原样保留**在 IR 的 `effects` 中：core 保留 `effects:["teleport"]`，aster-lang-test `packages/js/src/ir-normalize.ts:233-240` 会比较声明的效果。core 的现状：`CoreLowering.java:133` 原样透传效果，`EffectChecker.Effect.fromString`（`EffectChecker.java:55-61`）把未知名映射为 PURE，`ErrorCode.LOWER_UNKNOWN_EFFECT`（`ErrorCode.java:125`）未被使用。core 不改；不新增 E601 源码触发夹具，因为 lowering parity 夹具已写明 lowering 错误不可由源码触发 | `test/unit/parser/` 新增：`It performs teleport.` 解析成功；lowering 后不报 E601；类型检查结果与 `It performs`（无效果）相同，与 core 同口径。IR 断言 `effects` 为 `["teleport"]`。aster-lang-test 另加一个 tier1-parity 样例 `effect-unknown-name.aster`，用 `--mode=ir` 锁定两端的 `effects` 一致；这个样例随 PR 6 单独提交，不进阶段 B 分支组。`It performs io [Banana]` 维持现状（另案） |
| 7a | aster-lang-core `fix/250-type-nesting-guard` | core#250，见下文 | 见下文 |
| 7b | aster-lang-ts `fix/250-type-nesting-guard` | `shared/parse_diagnostics_parity.json` 与 7a 逐字节同步。**按「ts 会降到 300 以下」做计划**：`src/parser/context.ts` 的 `MAX_TYPE_RECURSION_DEPTH` 改为实测值，CHANGELOG 记为行为变化。计数单位两端相同：ts 现在每进入一次 `parseType` 计 1，包括 Map 的键和值、`result of` 的每个分量、`list of` 的元素；core 按同一单位计数（见 §3） | ts parse parity 夹具全部通过；新增「Map 键 / 值与 `result of` 分量都计入深度」用例，两端在上限与上限+1 上结论相同 |

- 7a→7b 在**同一次操作内**连续合并：先 core，再 ts。PR 6 只改 ts，单独合并。
- PR 3/4 必须先于阶段 B 合并。
- 阶段 A 中任一 ts PR 都不碰 overlay，所以不受「overlay 过期」规则约束。

**PR 1（aster-api 文档，ADR 补注）。** Files：`docs/adr/0046-governance-profile.md` 新增 §11.6「实施澄清（2026-11-12）」，`docs/adr/0040-action-guard.md` §11 D5 末尾补一段。内容：

- 本文 §1.2 的补充规则 (a)–(c)、`construct` 闭集与遍历顺序、按模块限定名分类被调函数、分析在 R2 之后进行；
- §1.4 的两种映射形状（guard / 证据），以及评估事件自带 `inputRefs` / `verifiedInputsRequired`（ADR 0041 §11 收据键旁登记）；
- 引擎路径 vs 请求路径（更正第 7 条），并把 §11.2 正反例表中 `principal.roles` / `action.name` 两行改写成「引擎 `principal_roles` / `action` → guard 映射为 …」；
- `"*"` 独占规则；
- 未知档案运行期按 `false`；
- 1.3.0 翻转需要引擎列车（更正第 6 条），§11.4 ⑦ 相应改写；
- ADR 0040 §11 D5：gate DENY 会消耗该证据的 `evidenceCorrelationId`（同一 correlationId 的重放返回该 DENY；重新申请须用新的评估事件）；
- 显式 `inputRefs` 中的路径即使请求里没有该字段也计入（缺字段 = 未验证 → DENY）。

**PR 2（aster-api Task 16a，pin 不变也能合并）。**

- Files：
  - `src/main/java/io/aster/policy/api/PolicyEvaluationService.java`：
    - 新增 `ruleMetadata(tenant, module, function, String versionId)`；原 3 参版改为「先解析版本再委托」。
    - 新增 `evaluatePolicyAtVersion(tenant, module, function, versionId, context)`：公开现有的 `evaluatePolicyWithVersionId`，**保留 `normalizeContext` 步骤**，不改语义。动态加载关闭时 `versionId` 为 null，行为与现状相同。
    - `executeCompiled` 与 `ruleMetadata` 都改走 memo。
  - `src/main/java/io/aster/policy/cache/CompiledPolicy.java`：加 `private final ConcurrentHashMap<String, RuleMetadata> ruleMetadataByFunction` 与 `ruleMetadata(fn, Function<…>)`，每个编译产物只解析一次 Core IR。
    - memo 挂在编译产物上，随缓存淘汰一并释放，大小不超过该模块的函数数，所以不需要单独的容量上限。
    - PR 15 起 `RuleMetadata` 依赖注册表，而注册表随 jar 固定，进程内不会变，memo 不需要失效。
  - `src/main/java/io/aster/guard/GuardDecisionService.java`：用已解析的 `versionId` 先取元数据、再按同一 `versionId` 评估，消除 gate 与评估之间版本漂移的窗口。
  - `src/main/java/io/aster/guard/GuardEvidenceDecisionService.java`：
    - 事件 metadata 有 `policyVersionId` 时按该版本取元数据。
    - 元数据读不到时改用事件的 `profile`，经 `ProfileRequirements` 判断：要求 verifiedInputs → DENY `input_refs_unavailable`；否则放行。
    - 两种情况都对 counter `aster_guard_rule_metadata_unavailable_total{path="decisions|from_evidence"}` 加一。
    - 日志默认 `DEBUG`，因为 evaluate-source 锚定是常态；只有事件带非空 `profile` 时才升为 `WARN`。
  - 新 `src/main/java/io/aster/guard/provenance/ProfileRequirements.java`：`boolean verifiedInputsRequired(String profileId)`。1.0.43 上恒为 `false`（core 尚无该字段）；PR 15 改为委托 replay 的 `ProfileRequirementsLookup`（§1.4）。
  - 新 `src/main/java/io/aster/guard/provenance/GuardInputRefPaths.java`（§1.4 的两种形状）。
  - `GuardActionMapper`：把 `flatten` 的键提取成共享常量表。
  - `VerifiedInputsGate.check`：先把 `meta.inputRefs()` 经 `GuardInputRefPaths` 映射，再比对。Javadoc 写明显式 ref 缺字段也计入。
  - `src/test/java/io/aster/policy/rest/PolicyCompileResourceTest.java:421`：改为断言等于 `ControlRegistry.loadDefault().version()`，并匹配 `\d+\.\d+\.\d+`，让 pin 升级 PR 不必改测试。
- Tests：
  - 新建 `src/test/java/io/aster/policy/api/PolicyEvaluationServiceRuleMetadataTest.java`（`@QuarkusTest`，真实缓存与版本表）：
    - `ruleMetadataIsVersionPinned`：激活版本已移动时仍读旧版本；
    - `ruleMetadataMemoizedPerCompiledArtifact`：同一产物两次调用只解析一次，用计数 spy；
    - `evaluatePolicyAtVersionNormalizesContext`；
    - `dynamicLoadingDisabledUsesNullVersion`：通过 test profile 关闭动态加载，ruleMetadata 与评估都走无版本路径，结果与现状相同。
  - `GuardDecisionServiceTest`：`gateAndEvaluationUseSameVersion`。
  - `GuardEvidenceDecisionServiceTest`：
    - `unreadableMetadataWithRequiringProfileDeniesInputRefsUnavailable`（`ProfileRequirements` 打桩为 true）；
    - `unreadableMetadataWithoutRequirementPassesAndCounts`：断言 counter +1；日志在无 profile 时为 DEBUG，有 profile 时为 WARN；
    - `metadataPinnedToEventPolicyVersionId`。
  - `GuardInputRefPathsTest`：
    - guard 形状：
      - 全表；
      - `action.x`、`principal_id.x`、`agent_model.x` 等标量子路径 → `*`；
      - 裸 `context` → `context`；
      - `""`、`"*"`、未知首段 → `*`。
    - 映射表的键集合等于 `flatten(<全部字段都填满的 action>).keySet()`；flatten 新增键而映射表漏改时失败。
    - 证据形状映射放在写事件处，由 PR 15 的 `EvidenceInputRefPathsTest` 覆盖，见下文。
    - 对 `VerifiedInputsGate.presentLeafPaths` 的输出断言：每个 guard 形状的映射结果都能与之对齐（同一请求路径空间）。
  - `VerifiedInputsGateTest`：`engineFieldPathsAreMappedBeforeCompare`、`explicitRefAbsentFromRequestDenies`。
  - 全量 guard 套件（主计划 Global Constraints 命令）。
  - decision goldens 与 from-evidence goldens（`GuardFromEvidenceResourceTest` 16 例）不变。

### 阶段 B：X2 + X3 同名分支组 `feat/r62-input-refs`（一次性连续合并：core → locales → hi → ts → test）

| # | 仓 | Files | 测试 |
|---|---|---|---|
| 8 | aster-lang-core | 见下文 PR 8 | 见下文 |
| 9 | aster-lang-locales | 见下文 PR 9 | `./gradlew check`（含 `exportControls`、`verifyControlsFixtures`、`verifyErrorCodesBaseline`、overlay 合同）；`DiagnosticOverlaysTest` 覆盖 E708 占位符 |
| 10 | aster-lang-hi | `src/main/resources/overlays/hi-IN/diagnostic-{messages,help}.json` 加 E708；`src/test/resources/diagnostics/error_codes.json` 同步；`src/test/java/aster/lang/hi/HiInDiagnosticOverlayTest.java` 加 E708 断言 | `./gradlew check` |
| 11 | aster-lang-ts | 见下文 PR 11 | 见下文 |
| 12 | aster-lang-test | 见下文 PR 12 | `parity-tier1.mjs --mode=parse` / `--mode=ir --full` / `--mode=eval` 全绿，历史 csv 照常追加 |

**PR 8（aster-lang-core）。**

- Files：
  - `src/main/java/aster/core/typecheck/governance/ControlRegistry.java`（§1.1）；
  - `src/main/resources/governance/controls-registry.json`（逐字节拷 PR 9 的 `registry.json`）；
  - `src/main/java/aster/core/ir/CoreModel.java`（`Func.inputRefs`）；
  - 新 `src/main/java/aster/core/typecheck/governance/InputRefsAnalyzer.java`；
  - `src/main/java/aster/core/resolve/NameResolution.java`（R2 之后调用分析器）；
  - `src/main/java/aster/core/resolve/CoreCopies.java`（保留字段）；
  - `src/main/java/aster/core/typecheck/governance/GovernanceChecker.java`（E708；`inputRefs` 为 null 时即时调 `InputRefsAnalyzer` 计算，兼容旧 IR）；
  - `src/main/java/aster/core/typecheck/ErrorCode.java`；
  - `src/test/resources/diagnostics/error_codes.json`（逐字节同 PR 11 的 `shared/error_codes.json`）；
  - 新 `src/test/resources/diagnostics/governance_input_refs_parity.json`（与 PR 11 同名夹具逐字节相同）。用例包括：
    - ADR 表全部行；
    - zh / de 本地化源码；
    - `Use … as Alias`；
    - 两处 `*` 的遍历顺序；
    - 非 Verdict 函数。
- Tests：
  - `ControlRegistryTest`：`verifiedInputs` 缺省为 false、为 true、非布尔则不登记。
  - 新 `InputRefsAnalyzerTest`：
    - ADR §11.2 正反例表 13 行逐行一例，含 8 层 / 9 层内联与互递归；
    - 补充规则 (a)–(c)；
    - **非 Verdict 函数不写 `inputRefs`**（同一档案模块中的辅助函数）；
    - `Use … as Alias` 导入的函数即使与本模块函数同名，也按跨模块调用 → `*`；
    - 两处 `*` 的规则：第一处在被内联的被调函数体内，第二处在调用点之后的规则体内。`construct` 必须取被调函数体内那一处，以锁定遍历顺序。
  - `ProfileCheckTest`：E708 触发与三种不触发（未声明、未知、false），用注入的注册表。
  - 新 `GovernanceInputRefsParityTest`：读共享夹具，对每例断言 `inputRefs` 与 E708 `{rule, profile, construct}`。
  - `InputRefsSurviveResolutionTest`：经完整编译通道（`resolveAndLowerChecked`）产出的 JSON 含 `inputRefs`。
  - **本地化规范路径**：同一规则分别用 zh-CN、de-DE 源码编写（字段名为本地化写法），`inputRefs` 必须等于 en-US 版本的规范路径。
  - 新 `NoProfileIrUnchangedTest`：对 `aster-lang-test` tier1-parity 中无 `Profile` 的模块编译，断言整棵 IR 不含 `inputRefs` 键。
  - `ErrorCodeParityTest` 自动覆盖 E708。
  - `ControlRegistrySiblingParityTest` 在同名分支下与 PR 9 一致。

**PR 9（aster-lang-locales）。**

- Files：
  - `controls/registry.json`（1.2.0）；
  - `build.gradle.kts` `profileRequiresProblems`；
  - 新 `controls/src/test-fixtures/bad-profile-verified-inputs-not-bool.json` 及 `expected-problems.json` 对应条目；
  - `controls/README.md` 夹具说明；
  - `test-support/src/main/resources/diagnostics/error_codes.json`（同步 E708）；
  - `locales/zh/src/main/resources/overlays/zh-CN/diagnostic-{messages,help}.json`、`locales/de/src/main/resources/overlays/de-DE/diagnostic-{messages,help}.json` 加 E708。

**PR 11（aster-lang-ts）。**

- Files：
  - `src/governance/controls.ts`、`src/governance/controls-registry.data.ts`（1.2.0）；
  - `src/types.ts`（`Core.Func.inputRefs`）；
  - 新 `src/governance/input-refs.ts`；
  - `src/compile-pipeline.ts`（在 `resolveTypedReferences` 之后调用分析器）；§1.2 列出的复制通道；
  - `src/typecheck/governance.ts`（E708）；
  - `shared/error_codes.json`（真源）→ `npx tsx scripts/generate_error_codes.ts` 重新生成 `src/diagnostics/error_codes.ts`；`docs/error-codes.md` 重新生成；
  - `src/config/lexicons/diagnostic-overlays.generated.ts`：在 core / locales / hi 同名分支都检出的工作区里运行 `pnpm generate:diagnostic-overlays` 重新生成；
  - 新 `shared/governance_input_refs_parity.json`；
  - `CHANGELOG.md`（1.5.0：新增 `Core.Func.inputRefs` 与 E708、registry 1.2.0）。
- Tests：
  - `test/unit/governance/controls-registry-profiles.test.ts`（与 core 逐条对应）；
  - 新 `test/unit/governance/input-refs.test.ts`（与 `InputRefsAnalyzerTest` 逐例对应）；
  - `test/unit/governance/profile-check.test.ts`（E708）；
  - 新 `test/unit/governance/input-refs-parity.test.ts`（共享夹具，含本地化、别名、遍历顺序用例）；
  - 新 `test/unit/governance/no-profile-ir-unchanged.test.ts`：对 `test/cnl/programs` 下无 `Profile` 的样例编译，断言整棵 IR 不含 `inputRefs` 键（core `NoProfileIrUnchangedTest` 的对应物）；
  - `test/unit/diagnostics/diagnostic-overlays.test.ts`、`test/unit/config/lexicons/hi-IN.test.ts`（E708 覆盖）；
  - `test/unit/governance/controls-registry-parity.test.ts`（自动）。

**PR 12（aster-lang-test）。**

- Files：
  - `corpus/tier1-equivalence/policies/governance-input-refs-*.aster` 与同名 `.meta.json`：ADR §11.2 每行一份，声明 `Profile "eu-ai-act-high-risk"`，带 `@id` / `@control("EU_AI_ACT:ART14")` 以满足档案要求。1.2.0 下这些文件全部编译成功（不触发 E708）。
  - `corpus/tier1-parity/manifest.json` 登记上述文件。
  - 有输入的规则补 `corpus/tier1-equivalence/inputs/governance-input-refs-*.cases.json`，用 `scripts/gen-cases.mjs` 双引擎交叉生成。
  - `ir-history.csv` 等由 CI 追加。
- 已知 IR 变化：`governance-sugar(.long).aster` 的 `decide` 多出 `inputRefs: [""]`（补充规则 (a)，标量参数）。两引擎都产出该字段，所以 IR parity 不受影响。

阶段 B 合并纪律（1.0.43 教训）：

- 五个 PR 的 CI 在同名分支下全绿后，**同一次操作内**按 core → locales → hi → ts → test 连续合并，中间不夹其他 core / locales / hi / ts 的合并。
- 只合进一部分时，各仓 main 的 parity / baseline 步骤会红。
- 合完最后一个（test）后，在 **core、locales、hi、ts、test 五个仓的 main** 都重跑一次 CI，全部恢复绿色后才进入阶段 C。
- 任何分支要重跑 CI 之前，先把 main 合进该分支（PR 的 CI 重跑会复用旧的 merge ref，1.0.41 教训）。
- 中途发现问题：**不要**回滚已合并的仓，在同名分支补修复 PR 继续推进。

### 阶段 C：列车 1.0.44

| # | 仓 / 分支 | 内容 |
|---|---|---|
| 13 | aster-lang-platform `chore/release-1.0.44` | `release-plan.json`：`platformVersion` / `ecosystemVersion` → `1.0.44`，`tsNpmVersion` → `1.5.0`，`uiMessagesNpmVersion` 保持 `1.0.30`，hi npm `1.0.24` 与 test npm `1.2.0` 不变（本列车不改 ui-messages / packages/js）。`artifacts[].expectedVersion` 中所有 catalog 派生的制品改为 `1.0.44`，`ts:npm` 改为 `1.5.0`；各制品的 `expectedPlatformPin` 改为 `1.0.44`（platform#115 先例）。`build.gradle.kts`：`asterLang` → `1.0.44`，并补版本说明注释 |
| 13′ | 各引擎仓 `chore/release-1.0.44` | 由 `run-train.sh` / 惯例产生的 pin bump PR（core、runtime、validation、test、locales、hi、truffle、ts `package.json` 1.5.0），同 1.0.43 的 core#251 |
| 14 | aster-api `chore/release-1.0.44` | 见下文 PR 14 |

**PR 14（aster-api pin bump）。**

- Files：
  - `settings.gradle:19` → `1.0.44`；
  - `.github/actions/setup-aster-build/action.yml:62` 默认 → `v1.0.44`；
  - `src/test/resources/diagnostics/parse_diagnostics_parity.json` 逐字节同步 core（#250 新增用例）；
  - 本地 composite 全量暴露的其他引擎跟进（同 #476 第 2 个提交的处理方式）。
- **不含** Task 16b 代码。
- CI 会一直红，直到 `v1.0.44` tag 存在，这是预期（同 #457 / #476）。
- 本地验证：各仓 main composite，跑 `:replay:test :runner:test :test`；已知基线失败写进 PR 描述。

**列车前 gate scan（必做，结果贴 platform PR）：**

1. 按惯例导出生产策略源码（scratchpad `ops/export-policy-sources.sql`）。用 ts 1.4.0 与本地 1.5.0 build 各编译一遍并 diff（`ops/scan-1041.mjs` 同法），扫描后删除导出文件。
2. 期望：
   - **诊断差异 0**。可能的来源只有三处，预期出现次数都是 0：
     - #304：未知效果名在 ts 1.4.0 上是解析错误，1.5.0 上接受并按 pure 处理。这类源码在 cloud（ts 保存门）里本来无法保存，但 core 侧一直接受。若扫描出现这类差异，逐条确认它们是「ts 旧报错 → 新通过」，**不是**新增错误。
     - #250：类型深度在新上限与 300 之间的源码，会从通过变为 E054。按 §4 第 6 条，只有扫描结果为 0 时才降低 ts 上限。
     - E708：1.2.0 下不可能出现。
   - **IR 差异只允许**「声明档案模块中 Verdict 规则新增 `inputRefs`」。任何其他 IR 差异 → 停车排查。
3. 附带产出 X4 的输入清单：每个声明 `eu-ai-act-high-risk` 的生产模块，列出其 Verdict 规则的 `inputRefs`，标出含 `"*"`（1.3.0 后会报 E708）或 `""` 的规则。结果只贴 PR 描述，不入库。

**列车运行：**

- 先合 PR 13，按 ADR 0053 / `run-train.sh` 触发 trainId `eco-1.0.44`。层序：L0 platform → L1 core / runtime / validation / test → L2 locales / hi → L3 truffle → L4 ts npm 1.5.0 + aster-api 部署。
- 跑前删除已合并的陈旧 `chore/release-*` 与本计划用过的同名分支。
- L2 失败按 1.0.41 恢复流程处理：合入修复 → 删除相关仓的 `v1.0.44` tag → `fromLayer=2` 续跑。

**上线后冒烟（用户执行，需要 API key）：**

- `POST /api/v1/policies/compile`，对声明档案的源码：`controlsRegistryVersion: "1.2.0"`，无 E708。
- 信贷试点 `/evaluate-source` + from-evidence 仍然得到 PENDING，Credit Officer 可以批准。
- 现有 guard decision golden 抽样，结果不变。

### 阶段 D：列车后

| # | 仓 / 分支 | 内容 |
|---|---|---|
| 15 | aster-api `feat/guard-t16b-input-refs` | 见下文 PR 15（Task 16b，依赖 1.0.44） |
| 16 | aster-cloud（另案，⑥） | 升级 ts 1.5.0 / 注册表副本 1.2.0；编辑器 Decision 页签展示 `inputRefs`。属 X4 计划，本文只登记依赖 |

**PR 15（aster-api Task 16b）。**

- Files：
  - `replay/src/main/java/io/aster/policy/governance/RuleMetadata.java`：`of(module, fn, registry)` 读 `Func.inputRefs` 与 `Profile.verifiedInputs`；`moduleOnly` 带 `verifiedInputsRequired`。
  - 新 `replay/src/main/java/io/aster/policy/governance/ProfileRequirementsLookup.java`（唯一的静态 helper）。
  - `src/main/java/io/aster/guard/provenance/ProfileRequirements.java`：委托该 helper，读 `ControlRegistry.loadDefault()`。
  - `src/main/java/io/aster/policy/compiler/GovernanceDiagnostics.java`：
    - `:84-86` 的 `isProfileCode` 纳入 `GOV_PROFILE_INPUT_UNRESOLVABLE`（调用处 `:74`）；
    - 类 Javadoc `:15` 的码表清单与 `:19` 的档案码说明加 E708。
  - `src/main/java/io/aster/policy/evidence/EvaluationEvidence.java`：新增 `KEY_INPUT_REFS`、`KEY_VERIFIED_INPUTS_REQUIRED`，只在档案模块写入，并执行 512 字节 / 32 项上限（§1.4）。
  - `src/main/java/io/aster/policy/api/model/PolicyEvaluationResult.java`、evaluate-source 执行结果类型：新增两个 `@JsonInclude(NON_NULL)` 字段（§1.4）。
  - `replay/src/main/java/io/aster/policy/api/convert/NamedContextMapper.java`：`MappingResult` 新增第一个参数实际匹配的输入键。
  - 写事件处：`/evaluate` 与 `/evaluate-source` 两个分支（`PolicyEvaluationResource.java:654-666` 一带）。
  - 新 `src/main/java/io/aster/guard/provenance/EvidenceInputRefPaths.java`：写事件时的证据形状映射。
  - `src/main/java/io/aster/guard/GuardEvidenceDecisionService.java`：优先使用事件自带的、已映射的 `inputRefs`；要求项仍由事件 `profile` + 当前注册表重算（`ProfileRequirements`），**不读**事件的 `verifiedInputsRequired`。
  - `src/main/java/io/aster/policy/rest/model/CompileResponse.java`，以及组装处 `src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java:1026`（`CompileResponse.ok(extractModuleInfo(core), warnings, extractProfile(core))`）。
  - 新 `src/test/java/io/aster/policy/EnginePinGateTest.java`。
- Tests：
  - `EnginePinGateTest`：反射断言 `CoreModel.Func` 有 `inputRefs`、`ControlRegistry.Profile` 有 `verifiedInputs`，且 `ControlRegistry.loadDefault().version()` ≥ `1.2.0`。
  - `RuleMetadataTest`（replay）：`ruleMetadataReadsInputRefsFromIr`、`noProfileModuleHasNullInputRefs`、`moduleOnlyCarriesVerifiedInputsRequired`、`unknownProfileIsNotRequired`。
  - 新建 `src/test/java/io/aster/policy/compiler/GovernanceDiagnosticsTest.java`：`e708IsErrorSeverityWithSuccessTrue`。主计划要求「高风险档案 + `meta[k]`」，但默认注册表下 1.2.0 不会触发 E708，所以构造 `GovernanceChecker` 时注入 `verifiedInputs=true` 的注册表。
  - `PolicyCompileResourceTest`：`compileResponseCarriesInputRefs`（档案模块）、`noProfileCompileResponseUnchanged`（键不存在）。
  - `GuardEvidenceDecisionServiceTest`：
    - 用真实 `ProfileRequirements` 与注入注册表，重跑 PR 2 的 fail-open 关闭用例；
    - `eventCarriedInputRefsUsedForEvaluateSourceModule`：无已保存版本，直接使用事件里已映射的 `context.<键>.…`；
    - `eventWithoutInputRefsFallsBackToProfile`：旧事件。
    - `eventRequirementSnapshotIgnored`：事件写 `verifiedInputsRequired=false`、而当前注册表下其 `profile` 要求 → 按要求判定（模拟 1.3.0 翻转前写下的事件）。
  - `EvidenceInputRefPathsTest`：
    - 命名 Map 输入，键为英文写法与本地化写法各一 → `context.<实际键>.<path>`；
    - `""` → `context.<键>`；
    - 位置数组输入 → `["*"]`；
    - 匹配键缺失 → `["*"]`；
    - 超过 32 项或 512 字节 → `["*"]`。
  - `EvaluationEvidenceSizeTest`（§1.4）：最大合法 metadata 加上限内的 `inputRefs` < 4096。
  - 需要更新的 golden 与测试：
    - `src/test/java/io/aster/policy/evidence/EvaluationEvidenceTest.java`：档案模块的事件含两新键；无档案模块逐字节不变。
    - `src/test/java/io/aster/policy/rest/EvaluateEvidenceEventTest.java`：声明档案的用例要更新期望的 metadata 键集合，无档案用例不变。
    - `src/test/java/io/aster/policy/api/model/PolicyEvaluationResultJacksonTest.java`：保持不变，作为 NON_NULL 的回归测试；新增一例，验证缺少两个新键的旧 JSON 能正常反序列化。
    - aster-api 中没有导出证据包时枚举 metadata 键的代码；证据包导出在 aster-cloud，只按已知键读取，新增键不影响它（cloud 的展示属 ⑥）。
  - `GuardFromEvidenceResourceTest` **不需要改**：已核对，它的夹具事件都不带 `profile`。`appendEvaluation`（`:92-96`）手写的 metadata 没有 profile 键；`appendEvaluateSourceEvaluation`（`:218-221`）构造 `EvaluationEvidence` 时 profile 参数为 `null`。因此新键不会写入，16 例结果不变。
  - 全量 guard 套件与全量 `:replay:test :test`。
- 提交信息：`feat(guard): 读取引擎 inputRefs 与 verifiedInputs，E708 定为 error（ADR 0046 §11 D2/D4）`。
- 部署后，Task 16 与 X1–X3 关闭，X4 计划可以开工。1.3.0 仍要等 X4 完成。翻转前写下的评估事件在翻转后照样受约束：要求项按事件 `profile` + 当前注册表重算，所以它们的 `verifiedInputsRequired=false` 快照不会让未验证输入通过——带 `inputRefs` 的事件按引用比对（未附事实 → DENY `unverified_input`），16b 之前的旧事件没有 `inputRefs` → DENY `input_refs_unavailable`。

---

## 3. 三个引擎 issue 的取舍

| issue | 结论 | 理由 |
|---|---|---|
| aster-lang-core#250 类型嵌套守卫 | **进本列车**（PR 7a/7b，aster-api 副本随 PR 14） | 与 #249 同一套文件与测试范式（`AsterCustomLexer` 词法期预闸 + `NestingGuardStackSafetyTest` 显式栈线程），和治理改动不共享文件。阈值：在 512K / 1M 栈、JIT / `-Xint` 下实测首次溢出深度，取 1M `-Xint` 值的一半以下；按 issue 给出的 1M JIT 约 525 层估算，结果会**低于 300**，所以计划按两端同降执行，ts 改常量并在 CHANGELOG 记为行为变化。**计数单位两端统一**：以 ts 现行口径为准，每进入一次类型解析计 1，包括 `list of` 的元素、`map of` 的键和值、`result of` 的每个分量、`option of`。core 的词法期预闸按同一组类型构造词计数，不能只数 `list of` 链。夹具加「上限放行 / 上限+1 E054」，并分别覆盖 list 链、map 键 / 值交替、result 嵌套三种形态，三份副本（ts / core / aster-api）逐字节相同。线上已有 aster-api#474 兜底（StackOverflowError → E054），所以风险低，收益是去掉对兜底的依赖 |
| aster-lang-ts#304 效果名 TS 比 Java 严 | **进本列车**（PR 6，仅 ts） | 裁定 (a)：core 并不报 E601。`ErrorCode.LOWER_UNKNOWN_EFFECT` 未被使用，`CoreLowering.java:133` 透传效果，`EffectChecker.Effect.fromString`（`:55-61`）把未知名视为 PURE；报 E601 的只有 ts（`lower_to_core.ts:342`）。因此 ts 改为接受未知效果名：效果名保留在 IR 的 `effects` 中，只在检查阶段按 pure 处理，与 core 对齐。这对生产行为没有影响：aster-api 走 core，一直接受这类源码。不动码表、overlay 与共享 parity 夹具，与阶段 B 不冲突。`performs io [Banana]` 的能力名问题另案 |
| aster-lang-core#245 枚举 `is` 比较 | **推迟到 1.0.45** | 要改 ANTLR 文法（`softComparator` 谓词），需要两引擎先约定 IR 形状（issue 自述 TS 运行时语义未验证），还要 truffle eval parity（L3）。新构造也要在 `inputRefs` 传播表里定义规则，与 X2 的分析器同车会把两类 parity 风险叠在一起。它是「Java 更严」（拒绝正确代码），不会产生错误结果，没有线上事故压力 |

## 4. 待用户确认（替代审查给出的建议裁定，状态均为「**proposed — pending user confirmation**」；确认前按此执行）

1. **根消费路径 `""`**（标量参数、整记录比较）——proposed：保留 `""`，aster-api 按入口形状映射：guard 形状 → `*`；证据形状在写事件时映射为 `context.<实际匹配键>`，没有匹配键时为 `*`。备选是编译期直接记 `*`，但那样所有标量参数的档案规则在 1.3.0 后都会报 E708。
2. **引擎产出源码字段路径，aster-api 负责映射**（更正第 7 条）——proposed：采用，并按 I1 分两种形状映射（§1.4）。备选是由引擎认识 guard 形状，但那会把 guard 契约耦合进编译器。
3. **未知档案在运行期**——proposed：不要求 verifiedInputs，计 WARN 与 counter。已知风险：D5 第 4 项的 `…-verified` 自定义档案如果在注册表回滚中被删除，声明它的模块会悄悄降为不要求；回滚注册表前须先扫描声明该档案的策略。
4. **1.3.0 翻转需要引擎列车**（更正第 6 条）——proposed：确认。X4 按「locales + core 副本 + 列车 + aster-api pin」排期，前置条件中加入「评估事件自带 `inputRefs` 的 PR 15 已部署」。
5. **Task 16b 不进 pin bump PR，列车后单独 PR**——proposed：分开。代价：上线到 PR 15 部署之间，编译响应没有 `inputRefs`；E708 被映射为 warning，但 1.2.0 下不可能出现。
6. **#250 阈值**——proposed：预先批准把 ts 降到实测值，条件是 gate scan 发现受影响的生产源码为 0；否则停下来请用户决定。

## 5. 自审记录

- 覆盖：
  - Task 16 → PR 2 + PR 15；X1 → PR 3/4/5；X2 → PR 8/9/11/12；X3 → PR 9/10/11；
  - carry-over（见文首抄录的 1–9 条）：fail-open 关闭、版本固定、模块级 `verifiedInputsRequired`、请求路径映射（两种形状）、事件自带 inputRefs、memo、日志与 counter、ADR gate DENY 注记、Javadoc，分别落在 PR 1 / PR 2 / PR 15；
  - 三个 issue → §3。
- 行为不变的论据：
  - 1.2.0 下所有档案 `verifiedInputs=false`，E708 与 gate 都不会拒绝；
  - 无档案模块 IR 不变（`NoProfileIrUnchangedTest` + gate scan）；
  - truffle 忽略新字段；
  - from-evidence 只在档案要求时才 DENY；
  - ts#304 改为接受未知效果名（ts 侧只放宽，不新增报错）；
  - #250 降低上限的前提是 gate scan 结果为 0。
- 风险最高的环节是阶段 B 合并到一半时停住，处理方式见该节纪律；其次是 PR 14 在 tag 存在前 CI 必红，这是预期。
- 外部审查：
  - 2026-11-12 第一次以 `codex exec -m gpt-5.6-sol -s read-only` 审查本文，Codex 无法连上服务端（`Connection refused` / `waiting for network`），没有得到结论。
  - 随后由替代审查给出裁定 C1、C2、I1–I5 及若干 minor 项，本版已全部采纳。改动：
    - #304 改为 ts 按 pure 处理；
    - inputRefs 改在 R2 之后计算；
    - 评估事件自带 inputRefs，映射分两种形状；
    - 被调函数按模块限定名分类；
    - 类型嵌套计数单位统一；
    - 五仓 main 重跑 CI；
    - 行号更正；
    - 其他测试补充。
  - 复审又提出 N1–N3，本版已全部采纳：
    - 事件 `inputRefs` 加大小上限；
    - 证据形状路径改为写事件时映射，并更正 `PolicyEvaluationResult` 的描述；
    - #304 保留 IR 中的效果名；
    - 列出 PR 15 需要更新的 golden。
