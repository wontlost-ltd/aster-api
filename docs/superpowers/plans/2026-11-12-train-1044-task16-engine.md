# 发版列车 1.0.44 执行拆解：Task 16 + 跨仓 X1–X3 + 三个引擎 issue（补充计划）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**性质：** 已批准计划 `docs/superpowers/plans/2026-11-08-guard-receipts-and-input-provenance.md`（下称「主计划」）中 Task 16 与跨仓 Task X1–X3 的**细化**，外加列车顺带的三个引擎 issue 的取舍。本文不改变主计划的目标与范围；与主计划冲突处以本文为准，并在下文「对主计划的更正」逐条列出理由。

**Goal:** 1.0.44 列车交付 registry 1.2.0（`requires.verifiedInputs` 键，全部档案为 `false`）、两引擎 `inputRefs` 与 E708、E708 三语 + hi 译文；aster-api 先落不依赖新引擎的 Task 16 部分，pin 升到 1.0.44 后再落依赖新引擎的部分。**行为不变**：列车上线后没有任何档案要求 verifiedInputs，任何现有策略的诊断、IR（声明档案的 Verdict 规则多一个 `inputRefs` 字段除外）与 guard 结果都不变。

**明确不在本列车：** X4（cloud 签发能力、`fact-signer.ts`、试点签发方）与 registry **1.3.0** 翻转 `eu-ai-act-high-risk.requires.verifiedInputs=true`。1.3.0 **保持阻塞**，解除条件：本列车上线、**并且** aster-api Task 16b（下文 PR 15）已部署（它关闭 from-evidence 的 fail-open），**并且** X4 完成。

**Spec（按此顺序读）：** aster-api `docs/adr/0046-governance-profile.md` §11（R62，尤其 D2 传播规则表与 §11.4 落地顺序）、`docs/adr/0040-action-guard.md` §11（R60，D5 执行点）、主计划 Task 15/16 与「跨仓任务」节、`.superpowers/sdd/2026-11-08-guard-receipts-and-input-provenance/t16-carryovers.md`。

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
4. **`GovernanceDiagnostics.isProfileCode` 在 `:93-95`**，不在 `:66-79`。
5. **ts X1 用例落在 `test/unit/governance/controls-registry-profiles.test.ts`**，主计划写的 `controls.test.ts` 不存在。
6. **X4 的 1.3.0 翻转需要引擎列车**（ADR 0046 §11.2 D5 第 3 项说「不需要」）。原因：aster-api 读 core jar 内的注册表副本，而该副本受 parity 测试约束必须与 locales 相等；翻转因此需要 core 发版，再由 aster-api 升 pin。在 PR 1 的 ADR 补注里写明，X4 计划据此排期。
7. **`inputRefs` 的路径空间**：ADR 表里的 `principal.roles`、`action.name` 是 guard 请求路径。引擎能看到的只有规则第一个参数的字段名，即 `GuardActionMapper.flatten` 产出的 `principal_roles`、`action`、`context.amount`。
   - 决定：引擎产出**源码字段路径**，与 guard 无关，编辑器展示的也是作者写的名字。
   - aster-api 由 `GuardInputRefPaths` 映射到请求路径，该类与 `flatten` 同包、同一张键表。
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
- **计算位置**：lowering 末尾，即 core `CoreLowering.lowerModule` 返回前、ts `lowerModule` 返回前。分析器是纯函数，不读注册表：core 为新类 `aster.core.typecheck.governance.InputRefsAnalyzer`，ts 为新文件 `src/governance/input-refs.ts`。lowering 之后的复制 / 改写通道必须保留该字段：
  - core：`resolve/CoreCopies.java:232` 附近的 Func 复制、`DeclarationResolver`、`TypedReferenceResolver`；
  - ts：`src/core/resolve-typed-references.ts`、`src/frontend/resolve-policy-types.ts`、`src/nodeid/node-id-map.ts`、`src/mapping/mapping-ir.ts`、`scripts/core-ir-json.ts`。
  - 实施时用 `git grep retTypeInferred` 逐处核对。新字段跟着 `retTypeInferred` 走：凡复制 `retTypeInferred` 的地方都要复制 `inputRefs`。
- **取值**：
  - 按 ADR 0046 §11.2 D2 第 1 条传播规则表；路径用点连接，最多 8 段；按 UTF-16 码元字典序去重（Java `String.compareTo` 与 JS 默认 `sort` 同序）。
  - `"*"` 为顶元。出现 `"*"` 时列表**只含** `["*"]`，因为 `*` 已覆盖其余路径；这样两引擎不需要对「`*` 与具体路径并存」的排序达成一致。
  - 规则不读输入时为 `[]`，与 null（未提供）区分。
- **补充规则**（PR 1 写入 ADR 0046 §11.6，两引擎按此实现）：
  - (a) 直接消费第一个参数本身（标量参数如 `given amount`，或整条记录参与比较）→ 路径 `""`（根）。整体转文本 / 序列化仍为 `"*"`。
  - (b) 字面量 Map 键为空串或含 `.` → `"*"`（点路径无法无歧义表示）。
  - (c) 函数第一个参数之外的参数不追踪（ADR 只定义第一个参数）。
- E708 的 `construct` 取**先序遍历中第一个产生 `*` 的节点**的类别，取值为闭集：`dynamic-map-key`、`map-iteration`、`whole-input-serialized`、`cross-module-call`、`function-value`、`inline-depth`、`recursion`、`path-too-long`、`unrepresentable-key`。

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

- `RuleMetadata(ruleId, controls, profile, inputRefs, verifiedInputsRequired)`：形状不变（T15 已加）。PR 15 起 `of(module, fn, registry)` 读 `Func.inputRefs`；`verifiedInputsRequired = registry.profile(module.profile).map(Profile::verifiedInputs).orElse(false)`。模块级值写入 `moduleOnly`。
- **未知档案在运行期按 `false`**：编译期已报 E705 且无法保存，见「待用户确认」第 3 条。
- `GuardInputRefPaths.toRequestPaths(List<String>)`：
  - 映射表：`principal_id→principal.id`、`principal_type→principal.type`、`principal_roles[.…]→principal.roles`、`action→action.name`、`resource_type→resource.type`、`resource_id→resource.id`、`agent_model→agent.model`、`context[.…]→context[.…]`。
  - `""`、`"*"` 或其他首段 → `"*"`（fail-closed）。
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
| 5 | aster-cloud `test/r62-registry-unknown-keys` | X1：新增 `src/__tests__/services/evidence/control-registry.test.ts`（带 `requires.verifiedInputs` / `futureKey` 的注册表经 `profileTitle` 与证据导出读取不抛错、结果不变） | 本文件；随 cloud 下一次常规部署，不进列车 |
| 6a | aster-lang-core `fix/304-effect-name-parse` | ts#304 的 core 侧：`src/test/resources/diagnostics/lowering_diagnostics_parity.json` 加「源码触发」用例 `It performs teleport.` → E601（en / zh / de 各一）；`LoweringDiagnosticsParityTest` 支持无 AST 改写的 `source` 触发 | core 侧该夹具全部通过（core 本来就在 lowering 报 E601） |
| 6b | aster-lang-ts `fix/304-effect-name-parse` | ts#304：`src/parser/type-parser.ts` `parseEffectList` 接受任意小写 IDENT 基效果；未知效果交给 lowering 报 E601，与 core 同码同参。`shared/lowering_diagnostics_parity.json` 与 6a 逐字节相同 | `test/unit/diagnostics/lowering-diagnostics-parity.test.ts` 新增 `source` 触发；`test/unit/parser/` 新增「未知效果名解析成功」；`It performs io [Banana]` 维持现状（另案） |
| 7a | aster-lang-core `fix/250-type-nesting-guard` | core#250，见下文 | 见下文 |
| 7b | aster-lang-ts `fix/250-type-nesting-guard` | `shared/parse_diagnostics_parity.json` 与 7a 逐字节同步；如阈值下调，`src/parser/context.ts` `MAX_TYPE_RECURSION_DEPTH` 同值 | ts parse parity 夹具全部通过 |

- 6a→6b、7a→7b 各在**同一次操作内**连续合并：先 core，再 ts。
- PR 3/4 必须先于阶段 B 合并。
- 阶段 A 中任一 ts PR 都不碰 overlay，所以不受「overlay 过期」规则约束。

**PR 1（aster-api 文档，ADR 补注）。** Files：`docs/adr/0046-governance-profile.md` 新增 §11.6「实施澄清（2026-11-12）」，`docs/adr/0040-action-guard.md` §11 D5 末尾补一段。内容：

- 本文 §1.2 的补充规则 (a)–(c)，以及 `construct` 闭集；
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
    - 新增 `evaluatePolicyAtVersion(tenant, module, function, versionId, context)`：公开现有的 `evaluatePolicyWithVersionId`，不改其语义。
    - `executeCompiled` 与 `ruleMetadata` 都改走 memo。
  - `src/main/java/io/aster/policy/cache/CompiledPolicy.java`：加 `private final ConcurrentHashMap<String, RuleMetadata> ruleMetadataByFunction` 与 `ruleMetadata(fn, Function<…>)`，每个编译产物只解析一次 Core IR。
  - `src/main/java/io/aster/guard/GuardDecisionService.java`：用已解析的 `versionId` 先取元数据、再按同一 `versionId` 评估，消除 gate 与评估之间版本漂移的窗口。
  - `src/main/java/io/aster/guard/GuardEvidenceDecisionService.java`：
    - 事件 metadata 有 `policyVersionId` 时按该版本取元数据。
    - 元数据读不到时改用事件的 `profile`，经 `ProfileRequirements` 判断：要求 verifiedInputs → DENY `input_refs_unavailable`；否则放行。
    - 两种情况都 `LOG.warnf`，并对 counter `aster_guard_rule_metadata_unavailable_total{path="decisions|from_evidence"}` 加一。
  - 新 `src/main/java/io/aster/guard/provenance/ProfileRequirements.java`：`boolean verifiedInputsRequired(String profileId)`。1.0.43 上恒为 `false`（core 尚无该字段），PR 15 改读注册表。
  - 新 `src/main/java/io/aster/guard/provenance/GuardInputRefPaths.java`（§1.4）。
  - `VerifiedInputsGate.check`：先把 `meta.inputRefs()` 经 `GuardInputRefPaths` 映射，再比对。Javadoc 写明显式 ref 缺字段也计入。
  - `src/test/java/io/aster/policy/rest/PolicyCompileResourceTest.java:421`：改为断言 `ControlRegistry.loadDefault().version()`，让 pin 升级 PR 不必改测试。
- Tests：
  - 新建 `src/test/java/io/aster/policy/api/PolicyEvaluationServiceRuleMetadataTest.java`（`@QuarkusTest`，真实缓存与版本表）：`ruleMetadataIsVersionPinned`（激活版本已移动时仍读旧版本）、`ruleMetadataMemoizedPerCompiledArtifact`（同一产物两次调用只解析一次，用计数 spy）。
  - `GuardDecisionServiceTest`：`gateAndEvaluationUseSameVersion`。
  - `GuardEvidenceDecisionServiceTest`：
    - `unreadableMetadataWithRequiringProfileDeniesInputRefsUnavailable`（`ProfileRequirements` 打桩为 true）；
    - `unreadableMetadataWithoutRequirementPassesAndCounts`（断言 counter +1）；
    - `metadataPinnedToEventPolicyVersionId`。
  - `GuardInputRefPathsTest`：全表、`""`/`"*"`/未知首段 → `*`，以及与 `GuardActionMapper.flatten` 键集合一致的反射断言（flatten 新增键而映射表漏改时失败）。
  - `VerifiedInputsGateTest`：`engineFieldPathsAreMappedBeforeCompare`、`explicitRefAbsentFromRequestDenies`。
  - 全量 guard 套件（主计划 Global Constraints 命令）。
  - decision goldens 不变。

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
  - `src/main/java/aster/core/lowering/CoreLowering.java`（`lowerModule` 末尾调用）；
  - `src/main/java/aster/core/resolve/CoreCopies.java`、`DeclarationResolver.java`、`TypedReferenceResolver.java`（保留字段）；
  - `src/main/java/aster/core/typecheck/governance/GovernanceChecker.java`（E708；`inputRefs` 为 null 时即时调 `InputRefsAnalyzer` 计算，兼容旧 IR）；
  - `src/main/java/aster/core/typecheck/ErrorCode.java`；
  - `src/test/resources/diagnostics/error_codes.json`（逐字节同 PR 11 的 `shared/error_codes.json`）；
  - 新 `src/test/resources/diagnostics/governance_input_refs_parity.json`（与 PR 11 同名夹具逐字节相同）。
- Tests：
  - `ControlRegistryTest`：`verifiedInputs` 缺省为 false、为 true、非布尔则不登记。
  - 新 `InputRefsAnalyzerTest`：ADR §11.2 正反例表 13 行逐行一例，含 8 层 / 9 层内联与互递归，外加补充规则 (a)–(c)。
  - `ProfileCheckTest`：E708 触发与三种不触发（未声明、未知、false），用注入的注册表。
  - 新 `GovernanceInputRefsParityTest`：读共享夹具，对每例断言 `inputRefs` 与 E708 `{rule, profile, construct}`。
  - `InputRefsSurviveResolutionTest`：经完整编译通道产出的 JSON 含 `inputRefs`。
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
  - `src/lower_to_core.ts`；§1.2 列出的复制通道；
  - `src/typecheck/governance.ts`（E708）；
  - `shared/error_codes.json`（真源）→ `npx tsx scripts/generate_error_codes.ts` 重新生成 `src/diagnostics/error_codes.ts`；`docs/error-codes.md` 重新生成；
  - `src/config/lexicons/diagnostic-overlays.generated.ts`：在 core / locales / hi 同名分支都检出的工作区里运行 `pnpm generate:diagnostic-overlays` 重新生成；
  - 新 `shared/governance_input_refs_parity.json`；
  - `CHANGELOG.md`（1.5.0：新增 `Core.Func.inputRefs` 与 E708、registry 1.2.0）。
- Tests：
  - `test/unit/governance/controls-registry-profiles.test.ts`（与 core 逐条对应）；
  - 新 `test/unit/governance/input-refs.test.ts`（与 `InputRefsAnalyzerTest` 逐例对应）；
  - `test/unit/governance/profile-check.test.ts`（E708）；
  - 新 `test/unit/governance/input-refs-parity.test.ts`（共享夹具）；
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
- 只合进一部分时，各仓 main 的 parity / baseline 步骤会红；合完 ts 后，在 core main 重跑 CI 确认恢复绿色。
- 中途发现问题：**不要**回滚已合并的仓，在同名分支补修复 PR 继续推进。

### 阶段 C：列车 1.0.44

| # | 仓 / 分支 | 内容 |
|---|---|---|
| 13 | aster-lang-platform `chore/release-1.0.44` | `release-plan.json`：`platformVersion` / `ecosystemVersion` → `1.0.44`，`tsNpmVersion` → `1.5.0`，`uiMessagesNpmVersion` 保持 `1.0.30`，hi npm `1.0.24` 与 test npm `1.2.0` 不变（本列车不改 ui-messages / packages/js）。`build.gradle.kts`：`asterLang` → `1.0.44` 并补版本说明注释 |
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
   - **诊断差异 0**。可能的来源只有三处，预期出现次数都是 0：#304 让 ts 对未知效果名改报 E601（这类源码在 core 侧本来就编译失败）；#250 的类型深度超过新上限；E708（1.2.0 下不可能出现）。
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
  - `src/main/java/io/aster/guard/provenance/ProfileRequirements.java`：改读 `ControlRegistry.loadDefault()`。
  - `src/main/java/io/aster/policy/compiler/GovernanceDiagnostics.java:93-95`：`isProfileCode` 纳入 `GOV_PROFILE_INPUT_UNRESOLVABLE`；类注释的码表清单加 E708。
  - `src/main/java/io/aster/policy/rest/model/CompileResponse.java`，以及组装处 `src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java:1026`（`CompileResponse.ok(extractModuleInfo(core), warnings, extractProfile(core))`）。
  - 新 `src/test/java/io/aster/policy/EnginePinGateTest.java`。
- Tests：
  - `EnginePinGateTest`：反射断言 `CoreModel.Func` 有 `inputRefs`、`ControlRegistry.Profile` 有 `verifiedInputs`，且 `ControlRegistry.loadDefault().version()` ≥ `1.2.0`。
  - `RuleMetadataTest`（replay）：`ruleMetadataReadsInputRefsFromIr`、`noProfileModuleHasNullInputRefs`、`moduleOnlyCarriesVerifiedInputsRequired`、`unknownProfileIsNotRequired`。
  - 新建 `src/test/java/io/aster/policy/compiler/GovernanceDiagnosticsTest.java`：`e708IsErrorSeverityWithSuccessTrue`。主计划要求「高风险档案 + `meta[k]`」，但默认注册表下 1.2.0 不会触发 E708，所以构造 `GovernanceChecker` 时注入 `verifiedInputs=true` 的注册表。
  - `PolicyCompileResourceTest`：`compileResponseCarriesInputRefs`（档案模块）、`noProfileCompileResponseUnchanged`（键不存在）。
  - `GuardEvidenceDecisionServiceTest`：用真实 `ProfileRequirements` 与注入注册表，重跑 PR 2 的 fail-open 关闭用例。
  - 全量 guard 套件与全量 `:replay:test :test`。
- 提交信息：`feat(guard): 读取引擎 inputRefs 与 verifiedInputs，E708 定为 error（ADR 0046 §11 D2/D4）`。
- 部署后，Task 16 与 X1–X3 关闭，X4 计划可以开工；1.3.0 仍要等 X4 完成。

---

## 3. 三个引擎 issue 的取舍

| issue | 结论 | 理由 |
|---|---|---|
| aster-lang-core#250 类型嵌套守卫 | **进本列车**（PR 7a/7b，aster-api 副本随 PR 14） | 与 #249 同一套文件与测试范式（`AsterCustomLexer` 词法期预闸 + `NestingGuardStackSafetyTest` 显式栈线程），和治理改动不共享文件。阈值规则同 #249：在 512K / 1M 栈、JIT / `-Xint` 下实测 `list of …` 链首次溢出深度，取 1M `-Xint` 值的一半以下，并与 ts `MAX_TYPE_RECURSION_DEPTH` **同值**。实测若 ≥ 600 则保持 300，ts 不改；否则两端同降，ts 改常量并在 CHANGELOG 记一笔。夹具加「上限放行 / 上限+1 E054」两例，三份副本（ts / core / aster-api）逐字节相同。线上已有 aster-api#474 兜底（StackOverflowError → E054），所以风险低、收益是去掉兜底依赖 |
| aster-lang-ts#304 效果名 TS 比 Java 严 | **进本列车**（PR 6a/6b） | E601 `LOWER_UNKNOWN_EFFECT` 在 1.0.43 已进共享码表，core 的行为是「解析接受、lowering 报 E601」，所以修复只需放宽 ts 解析器，外加一条共享 lowering parity 用例。不动码表和 overlay，与阶段 B 不冲突。`performs io [Banana]` 的能力名问题另案 |
| aster-lang-core#245 枚举 `is` 比较 | **推迟到 1.0.45** | 要改 ANTLR 文法（`softComparator` 谓词），需要两引擎先约定 IR 形状（issue 自述 TS 运行时语义未验证），还要 truffle eval parity（L3）。新构造也要在 `inputRefs` 传播表里定义规则，与 X2 的分析器同车会把两类 parity 风险叠在一起。它是「Java 更严」（拒绝正确代码），不会产生错误结果，没有线上事故压力 |

## 4. 待用户确认（默认值已写入本计划，确认前按默认执行）

1. **根消费路径 `""`**（标量参数、整记录比较）。默认：`""`；aster-api 运行期按 `*` 处理。可改为编译期直接记 `*`，但那样会让所有标量参数的档案规则在 1.3.0 后报 E708。
2. **引擎产出源码字段路径，aster-api 负责映射**（更正第 7 条）。默认：采用。备选是由引擎认识 guard 形状，但那会把 guard 契约耦合进编译器。
3. **未知档案在运行期**。默认：不要求 verifiedInputs，只计 WARN / counter。可改为 fail-closed，但那样用更新版编译器声明新档案的模块会被 DENY。
4. **1.3.0 翻转需要引擎列车**（更正第 6 条）：请确认 X4 计划按「locales + core 副本 + 列车 + aster-api pin」排期。
5. **Task 16b 不进 pin bump PR，列车后单独 PR**。默认：分开。代价：上线到 PR 15 部署之间，编译响应没有 `inputRefs`，E708 映射为 warning（1.2.0 下不可能出现）。
6. **#250 阈值**：实测后若需要把 ts 从 300 下调，属于 ts 行为变化（更严但与 core 对齐），合并前请确认。

## 5. 自审记录

- 覆盖：
  - Task 16 → PR 2 + PR 15；X1 → PR 3/4/5；X2 → PR 8/9/11/12；X3 → PR 9/10/11；
  - carry-over：fail-open 关闭、版本固定、模块级 `verifiedInputsRequired`、请求路径映射、memo、WARN / counter、ADR gate DENY 注记、Javadoc，分别落在 PR 1 / PR 2 / PR 15；
  - 三个 issue → §3。
- 行为不变的论据：
  - 1.2.0 下所有档案 `verifiedInputs=false`，E708 与 gate 都不会拒绝；
  - 无档案模块 IR 不变（`NoProfileIrUnchangedTest` + gate scan）；
  - truffle 忽略新字段；
  - from-evidence 只在档案要求时才 DENY。
- 风险最高的环节是阶段 B 合并到一半时停住，处理方式见该节纪律；其次是 PR 14 在 tag 存在前 CI 必红，这是预期。
- 外部审查：2026-11-12 按流程以 `codex exec -m gpt-5.6-sol -s read-only` 审查本文，Codex 无法连上服务端（Responses WebSocket / HTTPS 均 `Connection refused`，进入 `waiting for network` 后被终止），**未得到审查结论**；本文文件路径与行号已逐条对照各仓 `origin/main` 核对。合并前须补一次独立审查。
