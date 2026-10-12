# Cedar 导出目标 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把可翻译子集的 Aster Verdict 规则导出为标准 Cedar 策略文本 + Cedar JSON schema，并在每次导出时用官方 Cedar 授权器逐探针证明与 Aster 结论一致。

**Architecture:** aster-lang-core 新包 `aster.core.cedar` 从 Core IR 生成策略文本、schema、策略元数据与探针（无新依赖）；aster-api 用 `com.cedarpolicy:cedar-java` 做严格校验与等价性比对，提供鉴权端点并写审计；aster-cloud 在策略详情页提供下载。新错误码 E720–E724 经 aster-lang-ts 生成脚本同步到全部副本。

**Tech Stack:** Java 25（core / aster-api，Quarkus 3.39，JUnit 6，AssertJ）、cedar-java（版本在 Task 1 锁定）、TypeScript / Next.js / Vitest（aster-cloud）、Node 24。

**Spec:** `docs/adr/0047-cedar-export.md`（aster-api 仓）

## Global Constraints

- 只导出满足全部条件的规则：带 `@id`；单参数，其类型的 Data 字段名集合恰为 `principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model`（按名匹配，不看类型——IR 中八字段均为 Text）；返回 `Verdict`（复用 `GovernanceChecker.returnsVerdict`）。
- Cedar 命名空间 `Aster`；实体 `Aster::Principal`（属性 `type: String`、`roles: Set<String>`）、`Aster::Resource`（属性 `type: String`）；动作 `Aster::Action::"<名>"`；规则未提及任何 action 时 schema 声明一个以函数名命名的动作。
- 结论映射：allow → `permit`；deny/require_approval/escalate → `forbid`，注解 `@aster_outcome("deny"|"require_approval"|"escalate")`、`@aster_reason`、require_approval 另加 `@aster_approver`（取自 Verdict 的 role）；每条策略带 `@id("<rule-id>#<n>")`（n 从 1 起按源码顺序）、`@aster_rule`、`@aster_module`、`@aster_source_hash`；有 `@control` 时 `@aster_controls("A:B,C:D")`（逗号无空格、源码顺序），有档案时 `@aster_profile`。
- 条件翻译用 ADR §3 的 T/F 双表达式与短路展开；`Let` 在其位置加入 `(T(e) || F(e))`；then/else 块必须以 `Return Verdict.*` 结束，否则 E721。
- 错误码（category `governance`，severity `error`，文本逐字）：
  - E720 `CEDAR_NOT_EXPORTABLE_SHAPE`：message `Rule '{rule}' cannot be exported to Cedar: {reason}`；help `Exportable rules need @id, one parameter with the guard request fields (principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model) and a Verdict result.`
  - E721 `CEDAR_UNSUPPORTED_CONSTRUCT`：message `Rule '{rule}' uses {construct}, which has no Cedar translation`；help `Use comparisons, and/or/not, List.contains on principal_roles and integer arithmetic with literals, or keep this rule Aster-only.`
  - E722 `CEDAR_UNTYPED_CONTEXT_KEY`：message `Context key '{key}' in rule '{rule}' is used as both {first} and {second}`；help `Use each context key with a single type (number, text or boolean).`
  - E723 `CEDAR_EQUIVALENCE_MISMATCH`：message `Cedar export of rule '{rule}' disagrees with Aster on probe {probe}: Aster {aster}, Cedar {cedar}`；help `This is an exporter defect; the export was withheld. Please report it.`
  - E724 `CEDAR_SCHEMA_INVALID`：message `Generated Cedar for rule '{rule}' failed schema validation: {detail}`；help `This is an exporter defect; the export was withheld. Please report it.`
- 探针上限 `MAX_PROBES = 512`。
- 端点：`POST /api/v1/policies/export/cedar`；入参 `{source, locale, aliasSet, functionName?, claimedRef?}` 或 `{versionId}`；出参字段 `exportable, policies, schema, ruleId, profile, controls, probes{count, agreed}, diagnostics, sourceHash`。
- 审计事件类型 `POLICY_EXPORTED`；元数据键 `target`、`sourceHash`、`policiesHash`、`schemaHash`、`ruleId`、`probeCount`，可选 `versionId`、`claimedRef`；三个哈希键加入 `AuditChainAppender` 身份键白名单。
- 注释简体中文、只写意图；缩进 ≤3 层；提交尾行恰为 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`；不 push 到 main 之外的流程，按 PR 合并。

## Review Focus

- 前序条件读取缺失 context 键、后续分支为 allow：Cedar 必须 Deny（无决定性策略），绝不 Allow —— Task 4 测试 `missingKeyInEarlierBranchNeverReachesLaterPermit`。
- 嵌套 `Otherwise:` 块（elseBlock 内再有 If 链）与 `When` 语法糖混用：分支序号与条件必须与首匹配一致 —— Task 4 黄金用例 `nested-otherwise`。
- 字符串字面量含引号、反斜杠、换行、中文：Cedar 文本必须正确转义且可解析 —— Task 3 `CedarTextTest`、Task 6 严格校验用例。
- `agent_model` 为空：`==` 为假、`!=` 为真，两引擎一致 —— Task 5 探针包含 agent_model 缺失，Task 6 等价性覆盖。
- 规则只用 Text 字段、从不读 context：schema context 只含 `agent_model`（可选），且探针数 ≥ 动作数 + 1 —— Task 5 `noContextRuleProbes`。

---

### Task 1: cedar-java 可用性闸门（aster-api）

**Files:**
- Modify: `aster-api/build.gradle`（新增 `implementation 'com.cedarpolicy:cedar-java:<版本>'`，若需平台原生包按官方说明加 classifier）
- Create: `aster-api/src/main/java/io/aster/policy/cedar/CedarEngine.java`
- Create: `aster-api/src/test/java/io/aster/policy/cedar/CedarEngineTest.java`
- Create: `aster-api/scripts/ci/cedar-native-smoke.sh`

**Interfaces:**
- Produces: `CedarEngine`（`@ApplicationScoped`）：
  - `ValidationResult validate(String policiesText, List<String> policyIds, String schemaJson)` → `record ValidationResult(boolean ok, List<String> errors)`
  - `Decision authorize(String policiesText, List<String> policyIds, CedarRequest request)` → `record Decision(boolean allow, List<String> determiningPolicyIds, List<String> errors)`
  - `record CedarRequest(String principalId, String principalType, List<String> roles, String action, String resourceType, String resourceId, Map<String,Object> context)`

- [ ] **Step 1：锁版本。** 在 Maven Central 查 `com.cedarpolicy:cedar-java` 最新稳定版与其原生库打包方式（README / pom：是否 `uber` classifier、支持的 OS/arch/libc），把结论写进 `CedarEngine` 类注释。
- [ ] **Step 2：写失败测试**（`CedarEngineTest`，普通 JUnit，不需 Quarkus）：

```java
@Test void permitAndForbidWithIds() {
  var engine = new CedarEngine();
  String policies = """
      @id("r#1")
      forbid (principal, action, resource) when { action == Aster::Action::"delete" };
      @id("r#2")
      permit (principal, action, resource) when { !(action == Aster::Action::"delete") };
      """;
  var ids = List.of("r#1", "r#2");
  var req = new CedarEngine.CedarRequest("p", "agent", List.of(), "delete", "doc", "d1", Map.of());
  var d = engine.authorize(policies, ids, req);
  assertThat(d.allow()).isFalse();
  assertThat(d.determiningPolicyIds()).containsExactly("r#1");
  var ok = engine.authorize(policies, ids, new CedarEngine.CedarRequest("p", "agent", List.of(), "read", "doc", "d1", Map.of()));
  assertThat(ok.allow()).isTrue();
  assertThat(ok.determiningPolicyIds()).containsExactly("r#2");
}

@Test void strictValidationRejectsUnguardedOptionalAccess() {
  var engine = new CedarEngine();
  String schema = """
      {"Aster":{"entityTypes":{"Principal":{"shape":{"type":"Record","attributes":{"type":{"type":"String"},"roles":{"type":"Set","element":{"type":"String"}}}}},
       "Resource":{"shape":{"type":"Record","attributes":{"type":{"type":"String"}}}}},
       "actions":{"read":{"appliesTo":{"principalTypes":["Principal"],"resourceTypes":["Resource"],
         "context":{"type":"Record","attributes":{"amount":{"type":"Long","required":false}}}}}}}}
      """;
  String bad = "@id(\"x#1\")\npermit (principal, action, resource) when { context.amount > 1 };\n";
  String good = "@id(\"x#1\")\npermit (principal, action, resource) when { context has amount && context.amount > 1 };\n";
  assertThat(engine.validate(bad, List.of("x#1"), schema).ok()).isFalse();
  assertThat(engine.validate(good, List.of("x#1"), schema).ok()).isTrue();
}

@Test void missingAttributeIsErrorNotMatch() {
  var engine = new CedarEngine();
  String policies = "@id(\"x#1\")\npermit (principal, action, resource) when { context.amount > 1 };\n";
  var d = engine.authorize(policies, List.of("x#1"), new CedarEngine.CedarRequest("p", "agent", List.of(), "read", "doc", "d", Map.of()));
  assertThat(d.allow()).isFalse();
  assertThat(d.determiningPolicyIds()).isEmpty();
}
```

- [ ] **Step 3：跑测试确认失败**：`./gradlew --offline :test --tests 'io.aster.policy.cedar.CedarEngineTest'` → 编译失败（类不存在）。去掉 `--offline` 首次拉依赖。
- [ ] **Step 4：实现 `CedarEngine`**：用 cedar-java 的 `BasicAuthorizationEngine`、`PolicySet`（用显式 id 构造每条 `Policy`，按 `policyIds` 顺序与文本中 `@id` 注解一一对应——若库只能整体解析文本，则按解析顺序映射 id）、`Schema`、`ValidationRequest`（严格模式）。实体：principal `Aster::Principal::"<id>"` 属性 `{type, roles:Set}`；resource `Aster::Resource::"<id>"` 属性 `{type}`；action `Aster::Action::"<名>"`。context 值：`Long/Integer → long`，`String`，`Boolean`；其他类型报错由调用方处理。授权**不传 schema**（请求不做 schema 校验）。类注释写明 Step 1 结论。
- [ ] **Step 5：跑测试通过**。
- [ ] **Step 6：生产镜像闸门。** 写 `scripts/ci/cedar-native-smoke.sh`：`./gradlew --offline quarkusBuild -x test` 后，在 `Dockerfile.jvm` 同一基础镜像（`azul/zulu-openjdk-alpine:25-jre`，`--platform linux/arm64`）里运行一个只调用 `CedarEngine.authorize` 的主类（`io.aster.policy.cedar.CedarNativeSmoke`，放 `src/test/java` 由脚本以 test classpath 运行即可，或放 main 并在类注释说明仅供冒烟），输出 `CEDAR_OK`。本地用 `podman run --platform linux/arm64` 执行。
  - **通过** → 继续。
  - **失败**（musl 无法加载原生库）→ **停止整个计划**，在 ledger 记录错误输出，交用户裁决（换 glibc 基础镜像，或其他运行方式）。不要自行修改 Dockerfile。
- [ ] **Step 7：提交**：`git add build.gradle src/main/java/io/aster/policy/cedar/CedarEngine.java src/test/java/io/aster/policy/cedar scripts/ci/cedar-native-smoke.sh && git commit -m "feat(cedar): 引入 cedar-java 并验证生产镜像可加载（ADR 0047 §2）"`。

---

### Task 2: 错误码 E720–E724（aster-lang-ts 生成 → 全部副本）

**Files:**
- Modify: `aster-lang-ts/shared/error_codes.json`（在 `GOV_CHECK_UNAVAILABLE` 之后追加五项，文本逐字取自 Global Constraints）
- Regenerate: `aster-lang-ts/src/diagnostics/error_codes.ts`、`aster-lang-core/src/main/java/aster/core/typecheck/ErrorCode.java`（经 `aster-lang-ts/scripts/generate_error_codes.ts`）
- Copy: `aster-lang-core/src/test/resources/diagnostics/error_codes.json`（与 ts `shared/error_codes.json` 逐字节相同）
- Test: 既有 `aster-lang-core/src/test/java/aster/core/typecheck/ErrorCodeParityTest.java`、ts 的 error-codes 单源测试

**Interfaces:**
- Produces: `ErrorCode.CEDAR_NOT_EXPORTABLE_SHAPE`、`CEDAR_UNSUPPORTED_CONSTRUCT`、`CEDAR_UNTYPED_CONTEXT_KEY`、`CEDAR_EQUIVALENCE_MISMATCH`、`CEDAR_SCHEMA_INVALID`（Java），TS 同名枚举。

- [ ] **Step 1：写失败测试**（core，`aster-lang-core/src/test/java/aster/core/cedar/CedarErrorCodesTest.java`）：

```java
@Test void cedarCodesExistWithVerbatimTemplates() {
  assertThat(ErrorCode.CEDAR_NOT_EXPORTABLE_SHAPE.code()).isEqualTo("E720");
  assertThat(ErrorCode.CEDAR_UNSUPPORTED_CONSTRUCT.code()).isEqualTo("E721");
  assertThat(ErrorCode.CEDAR_UNTYPED_CONTEXT_KEY.code()).isEqualTo("E722");
  assertThat(ErrorCode.CEDAR_EQUIVALENCE_MISMATCH.code()).isEqualTo("E723");
  assertThat(ErrorCode.CEDAR_SCHEMA_INVALID.code()).isEqualTo("E724");
  assertThat(ErrorCode.CEDAR_UNSUPPORTED_CONSTRUCT.render(Map.of("rule", "R-1", "construct", "Match")))
      .isEqualTo("Rule 'R-1' uses Match, which has no Cedar translation");
}
```

- [ ] **Step 2：运行确认失败**：`cd aster-lang-core && ./gradlew test --tests 'aster.core.cedar.CedarErrorCodesTest'` → 编译失败。
- [ ] **Step 3：改 JSON 并生成**：按 `aster-api/docs/superpowers/plans/2026-10-10-governance-profile.md` 第 439–471 行的同步配方（编辑 `shared/error_codes.json` → 运行生成脚本 → 复制 JSON 到 core 测试资源 → `cmp` 确认逐字节相同）。
- [ ] **Step 4：运行通过**：core 新测试与 `ErrorCodeParityTest`；ts `pnpm test`（Node 24：`export PATH=/opt/homebrew/opt/node@24/bin:$PATH`）。
- [ ] **Step 5：提交**（两仓各一提交）：ts `feat(diagnostics): 新增 Cedar 导出错误码 E720–E724（ADR 0047 §4.4）`；core 同标题。

---

### Task 3: 形状检查、Cedar 文本工具与 schema 生成（aster-lang-core）

**Files:**
- Create: `aster-lang-core/src/main/java/aster/core/cedar/CedarText.java`（字符串字面量转义、实体引用）
- Create: `aster-lang-core/src/main/java/aster/core/cedar/GuardShape.java`（E720）
- Create: `aster-lang-core/src/main/java/aster/core/cedar/ContextType.java`（`enum ContextType { LONG, STRING, BOOL }`）
- Create: `aster-lang-core/src/main/java/aster/core/cedar/SchemaEmitter.java`
- Create: `aster-lang-core/src/test/java/aster/core/cedar/CedarTestSupport.java`（复制 `GovernanceSugarParseTest` 的 `lower(String)`：Canonicalizer → AsterCustomLexer → AsterParser → AstBuilder → CoreLowering；语法错误抛 `IllegalStateException`）
- Test: `CedarTextTest.java`、`GuardShapeTest.java`、`SchemaEmitterTest.java`

**Interfaces:**
- Produces:
  - `CedarText.quote(String s)`：返回带双引号的 Cedar 字符串字面量，转义 `\\` `\"` `\n` `\r` `\t` `\0`，其他控制字符用 `\u{XX}`；`CedarText.entity(String type, String id)` → `Aster::<type>::"<转义 id>"`。
  - `GuardShape.check(CoreModel.Module m, CoreModel.Func fn, List<Diagnostic> out)` → `Optional<String>`（参数名；不合格时向 `out` 写 E720 并返回 empty）。
  - `SchemaEmitter.emit(List<String> actions, Map<String, ContextType> contextKeys, boolean agentModelUsed, String fallbackAction)` → `String`（紧凑 JSON，键按插入顺序；`actions` 为空时用 `fallbackAction`）。

- [ ] **Step 1：写失败测试**：

```java
// CedarTextTest
@Test void quoteEscapes() {
  assertThat(CedarText.quote("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\c\\nd\"");
  assertThat(CedarText.quote("审批人")).isEqualTo("\"审批人\"");
  assertThat(CedarText.quote("x\u0001")).isEqualTo("\"x\\u{1}\"");
  assertThat(CedarText.entity("Action", "read")).isEqualTo("Aster::Action::\"read\"");
}

// GuardShapeTest（源码模板见下）
static final String GUARD = "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.\n\n";
@Test void acceptsGuardShape() {
  var m = CedarTestSupport.lower("Module m.\n\n" + GUARD
      + "@id(\"R-1\")\nRule decide given req as GuardRequest, produce Verdict:\n  Return Verdict.allow().\n");
  var out = new ArrayList<Diagnostic>();
  assertThat(GuardShape.check(m, CedarTestSupport.func(m, "decide"), out)).contains("req");
  assertThat(out).isEmpty();
}
@Test void rejectsMissingId()        { assertE720("Rule decide given req as GuardRequest, produce Verdict:\n  Return Verdict.allow().\n", "missing @id"); }
@Test void rejectsWrongFields()      { /* Define Other has a, b. 作参数类型 */ assertE720WithType("Define Other has a, b.\n\n", "Other", "parameter type Other is not the guard request shape"); }
@Test void rejectsNonVerdict()       { assertE720("@id(\"R-1\")\nRule decide given req as GuardRequest, produce Bool:\n  Return true.\n", "result is not Verdict"); }
@Test void rejectsTwoParams()        { assertE720("@id(\"R-1\")\nRule decide given req as GuardRequest, x as Int, produce Verdict:\n  Return Verdict.allow().\n", "expects exactly one parameter"); }
```

`assertE720(body, reason)` 断言恰一条 E720，其 `data.reason` 等于第二个参数、`data.rule` 为函数名；`reason` 取值固定为：`missing @id`、`expects exactly one parameter`、`parameter type <T> is not the guard request shape`、`result is not Verdict`、`function <name> not found`。

```java
// SchemaEmitterTest
@Test void emitsActionsAndOptionalContext() throws Exception {
  String json = SchemaEmitter.emit(List.of("read", "delete"),
      new LinkedHashMap<>(Map.of("amount", ContextType.LONG)), true, "decide");
  JSONAssert.assertEquals("""
    {"Aster":{"entityTypes":{
      "Principal":{"shape":{"type":"Record","attributes":{"type":{"type":"String"},"roles":{"type":"Set","element":{"type":"String"}}}}},
      "Resource":{"shape":{"type":"Record","attributes":{"type":{"type":"String"}}}}},
     "actions":{
      "read":{"appliesTo":{"principalTypes":["Principal"],"resourceTypes":["Resource"],"context":{"type":"Record","attributes":{"agent_model":{"type":"String","required":false},"amount":{"type":"Long","required":false}}}}},
      "delete":{"appliesTo":{"principalTypes":["Principal"],"resourceTypes":["Resource"],"context":{"type":"Record","attributes":{"agent_model":{"type":"String","required":false},"amount":{"type":"Long","required":false}}}}}}}}
    """, json, true);
}
@Test void fallbackActionWhenNoneMentioned() throws Exception {
  String json = SchemaEmitter.emit(List.of(), new LinkedHashMap<>(), false, "decide");
  assertThat(new ObjectMapper().readTree(json).at("/Aster/actions").fieldNames()).toIterable().containsExactly("decide");
}
```

`ContextType` 到 schema：`LONG → {"type":"Long"}`，`STRING → {"type":"String"}`，`BOOL → {"type":"Boolean"}`；所有 context 键 `"required":false`；`agentModelUsed` 为真时加入 `agent_model`（String，可选）。

- [ ] **Step 2：运行确认失败**：`./gradlew test --tests 'aster.core.cedar.*'`。
- [ ] **Step 3：实现。** `GuardShape`：用 `GovernanceChecker.ruleId(fn)`、`GovernanceChecker.returnsVerdict(fn)`；参数类型名从 `fn.params.get(0).type`（`TypeName`）取，在 `m.decls` 找同名 `Data`，比较字段名集合（`Set.of(八个名字)`）。诊断构造沿用 `GovernanceChecker.diag` 模式：`new Diagnostic(Severity.ERROR, code, code.render(data), Optional.ofNullable(fn.origin), Optional.ofNullable(code.help()), Map.copyOf(data))`。`SchemaEmitter` 用 Jackson `ObjectNode`（core 已依赖 Jackson），键序为插入序。
- [ ] **Step 4：运行通过。**
- [ ] **Step 5：提交**：`feat(cedar): 导出形状检查、Cedar 文本转义与 schema 生成（ADR 0047 §3）`。

---

### Task 4: 条件翻译与策略生成（aster-lang-core）

**Files:**
- Create: `aster-lang-core/src/main/java/aster/core/cedar/Cond.java`（`record Cond(String t, String f)`）
- Create: `aster-lang-core/src/main/java/aster/core/cedar/ConditionTranslator.java`
- Create: `aster-lang-core/src/main/java/aster/core/cedar/PathWalker.java`
- Create: `aster-lang-core/src/main/java/aster/core/cedar/EmittedPolicy.java`（`record EmittedPolicy(String id, String effect, String outcome, String reason, String approver, String condition)`）
- Create: `aster-lang-core/src/main/java/aster/core/cedar/PolicyEmitter.java`
- Create: `aster-lang-core/src/main/java/aster/core/cedar/CedarExport.java`
- Create: `aster-lang-core/src/main/java/aster/core/cedar/CedarExporter.java`
- Create: `aster-lang-core/src/main/java/aster/core/cedar/RuleFacts.java`（翻译时收集的事实：actions、roles、principalTypes、resourceTypes、principalIds、resourceIds、agentModels、context 键类型与字面量）
- Test: `ConditionTranslatorTest.java`、`CedarExporterGoldenTest.java`、黄金文件 `src/test/resources/cedar/<名>.aster` / `.cedar` / `.cedarschema.json`

**Interfaces:**
- Consumes: Task 3 的 `GuardShape`、`SchemaEmitter`、`CedarText`、`ContextType`；Task 2 的错误码。
- Produces:
  - `CedarExporter.export(CoreModel.Module m, String functionName, String sourceHash)` → `CedarExport`
  - `record CedarExport(boolean exportable, String ruleId, String functionName, String policies, List<String> policyIds, List<EmittedPolicy> emitted, String schemaJson, String profile, List<String> controls, RuleFacts facts, List<Diagnostic> diagnostics)`；`exportable=false` 时 `policies`/`schemaJson` 为 `null`、`emitted` 为空。
  - `RuleFacts` 为可变收集器，提供只读访问：`List<String> actions()`、`List<String> roles()`、`List<String> principalTypes()`、`List<String> resourceTypes()`、`List<String> principalIds()`、`List<String> resourceIds()`、`List<String> agentModels()`、`Map<String, ContextType> contextTypes()`、`Map<String, List<Object>> contextLiterals()`、`boolean agentModelUsed()`（均按首次出现顺序去重）。

**翻译规则（实现必须逐条满足）：**
- `Name` 按 `.` 切分；首段为参数名 → guard 字段；首段为 `Let` 名（且只有一段）→ 内联；其他 → E721（construct `reference '<name>'`）。
- 永远有定义的原子（F = `!(T)`）：
  - `req.action ==/!= "v"` → `action == Aster::Action::"v"`（!= 取反），记录 action。
  - `req.principal_id ==/!= "v"` → `principal == Aster::Principal::"v"`；`req.resource_id` → `resource == Aster::Resource::"v"`。
  - `req.principal_type ==/!= "v"` → `principal.type == "v"`；`req.resource_type` → `resource.type == "v"`。
  - `List.contains(req.principal_roles, "v")` → `principal.roles.contains("v")`，记录 role。
  - `Bool` 字面量 → `true` / `false`。
- `req.agent_model == "v"`：T = `(context has agent_model && context.agent_model == "v")`，F = `!(T)`；`!=` 时 T/F 互换。
- context 原子：`req.context.k <op> 字面量`（两侧皆可）或含 `+ - *` 的整数项（操作数为 context 键或字面量）：设所读键集合为 K，T = `(context has k1 && … && (表达式))`，F = `(context has k1 && … && !(表达式))`；单独作条件的 `req.context.k` 视为 Bool 键：T = `(context has k && context.k)`，F = `(context has k && !context.k)`。
- 类型推断先行一遍：与 Int/Long 字面量比较或参与算术 → LONG；与 String 比较 → STRING（只允许 `==`/`!=`，否则 E721 construct `ordering on text`）；Bool 用法 → BOOL；同一键两种类型 → E722（`first`/`second` 取 `number`/`text`/`boolean`，按首次出现顺序）。两个 context 键直接比较：二者类型均已知且相同才允许，否则 E721 construct `comparison between untyped context keys`。
- 组合：`and`/`or`/`not` 按 ADR §3 公式；`IfE`、`Match`、`Lambda`、其他 `Call` → E721（construct 取 `if-expression`、`Match`、`lambda`、`call to '<name>'`）；`/` `//` `%` → E721 construct `division`；`DecimalE`/`DoubleE` → E721 construct `decimal value`。
- 路径遍历（`PathWalker`）：从函数体开始维护「路径条件列表」与 Let 绑定表（块内复制、不外泄）：
  - `Let x be e`：翻译 e；若 e 读 context 键，把 `(T(e) || F(e))` 加入路径；绑定 x。
  - `If c`：then 块必须「必然返回」（最后一条为 `Return`，或为两分支都必然返回的 If），否则 E721 construct `branch that can end without a Verdict`；then 分支路径追加 `T(c)`；有 else 块时同样要求必然返回、路径追加 `F(c)` 并结束本块遍历；无 else 时当前路径追加 `F(c)` 继续。
  - `Return e`：e 必须是 `Verdict.*` 调用且参数为 `StringE`（否则 E721 construct `non-literal verdict argument`）→ 产生分支（序号递增）。
  - 其他语句（`Set`、`Scope`、`Start`、`Wait`、`Workflow`、`Match`）→ E721。
  - 块结束仍未返回 → E721 construct `branch that can end without a Verdict`。
- 策略文本：每分支一条，`condition` 为路径条件以 ` && ` 连接（各项已括号包裹；空路径为 `true`），格式：

```
@id("R-1#1")
@aster_rule("R-1")
@aster_module("m")
@aster_source_hash("<sha>")
@aster_outcome("require_approval")
@aster_approver("A")
@aster_reason("B")
@aster_controls("X:Y")
@aster_profile("governed")
forbid (principal, action, resource)
when { <condition> };
```
  注解顺序固定如上（无值的注解省略）；allow 分支 `@aster_outcome("allow")`，无 reason/approver；策略之间空一行；文本末尾一个换行。

- [ ] **Step 1：写失败测试 —— 翻译单元**（`ConditionTranslatorTest`，用 `CedarTestSupport.lower` 生成含单条 If 的规则，取 cond 表达式翻译）：

```java
@Test void actionEquality() {
  var c = translate("req.action equals to \"delete\"");
  assertThat(c.t()).isEqualTo("(action == Aster::Action::\"delete\")");
  assertThat(c.f()).isEqualTo("!(action == Aster::Action::\"delete\")");
}
@Test void contextComparisonGuardsHas() {
  var c = translate("req.context.amount at least 1000");
  assertThat(c.t()).isEqualTo("(context has amount && (context.amount >= 1000))");
  assertThat(c.f()).isEqualTo("(context has amount && !(context.amount >= 1000))");
}
@Test void andShortCircuit() {
  var c = translate("req.context.a at least 1 and req.context.b at least 2");
  assertThat(c.t()).isEqualTo("((context has a && (context.a >= 1)) && (context has b && (context.b >= 2)))");
  assertThat(c.f()).isEqualTo("((context has a && !(context.a >= 1)) || ((context has a && (context.a >= 1)) && (context has b && !(context.b >= 2))))");
}
@Test void rolesContains() {
  assertThat(translate("List.contains(req.principal_roles, \"ops\")").t()).isEqualTo("(principal.roles.contains(\"ops\"))");
}
@Test void agentModelNullSafe() {
  var c = translate("req.agent_model not equal to \"m\"");
  assertThat(c.t()).isEqualTo("!(context has agent_model && context.agent_model == \"m\")");
  assertThat(c.f()).isEqualTo("(context has agent_model && context.agent_model == \"m\")");
}
@Test void textOrderingRejected()      { assertE721("req.context.name less than \"b\"", "ordering on text"); }
@Test void conflictingTypesRejected()  { assertE722("req.context.k at least 1 or req.context.k equals to \"x\"", "k", "number", "text"); }
@Test void otherCallRejected()         { assertE721("Text.length(req.action) at least 1", "call to 'Text.length'"); }
@Test void nestedContextRejected()     { assertE721("req.context.a.b at least 1", "nested context key 'a.b'"); }
```

- [ ] **Step 2：写失败测试 —— 端到端黄金**（`CedarExporterGoldenTest`）：对 `src/test/resources/cedar/` 下每个 `<名>.aster`，导出后比较 `<名>.cedar` 与 `<名>.cedarschema.json`（schema 用 JSONAssert 严格比较）。黄金样本：
  - `probe.aster`：`DeployedMapParamProbeTest.SOURCE` 同款（action 判断 → require_approval，否则 allow）。期望 `.cedar`：

```
@id("PROBE-001#1")
@aster_rule("PROBE-001")
@aster_module("guard.probe")
@aster_source_hash("test")
@aster_outcome("require_approval")
@aster_approver("Data Protection Officer")
@aster_reason("customer record deletion")
forbid (principal, action, resource)
when { (action == Aster::Action::"delete_customer_record") };

@id("PROBE-001#2")
@aster_rule("PROBE-001")
@aster_module("guard.probe")
@aster_source_hash("test")
@aster_outcome("allow")
permit (principal, action, resource)
when { !(action == Aster::Action::"delete_customer_record") };
```
  - `when-sugar.aster`：四种结论 + `When`/`Otherwise` + `Let big be req.context.amount at least 1000.` + 角色 + agent_model，`@control("EU_AI_ACT:ART14")`、`Profile "governed".`。
  - `nested-otherwise.aster`：`If … : Return deny. Otherwise:` 块内再有 If 链与最终 Return。
  - 期望文件由实现产出后**人工逐行核对**再落盘（核对点：分支序号、每条路径的 T/F、Let 的已定义要求、注解顺序），并在测试类注释里写明「黄金文件经人工核对」。
- [ ] **Step 3：写失败测试 —— 缺失键语义**（`CedarExporterGoldenTest`）：

```java
@Test void missingKeyInEarlierBranchNeverReachesLaterPermit() {
  var exp = export("""
      @id("R-9")
      Rule decide given req as GuardRequest, produce Verdict:
        If req.context.amount at least 10:
          Return Verdict.deny("big").
        Return Verdict.allow().
      """);
  var permit = exp.emitted().stream().filter(p -> p.effect().equals("permit")).findFirst().orElseThrow();
  assertThat(permit.condition()).isEqualTo("(context has amount && !(context.amount >= 10))");
}
@Test void exportIsAllOrNothing() {
  var exp = export("""
      @id("R-8")
      Rule decide given req as GuardRequest, produce Verdict:
        If Text.length(req.action) at least 1:
          Return Verdict.deny("x").
        Match req.action:
          When "a", Return Verdict.allow().
        Return Verdict.allow().
      """);
  assertThat(exp.exportable()).isFalse();
  assertThat(exp.policies()).isNull();
  assertThat(exp.diagnostics()).extracting(d -> d.code().code()).containsExactly("E721", "E721");
}
```

（第二个用例的 `Match` 语法以 core 语法为准，若写法不同按实际语法调整，保持「两处不可翻译 → 两条 E721」。）
- [ ] **Step 4：运行确认失败。**
- [ ] **Step 5：实现** `ConditionTranslator`、`PathWalker`、`PolicyEmitter`、`CedarExporter`（`export` 流程：找函数（无 → E720 `function <name> not found`）→ `GuardShape` → 类型推断遍历 → 翻译与路径遍历 → 有错误则返回 `exportable=false` 与全部诊断 → `PolicyEmitter` → `SchemaEmitter.emit(facts.actions(), facts.contextTypes(), facts.agentModelUsed(), fn.name)`）。`ruleId`/`controls`/`profile` 取自 `GovernanceChecker.ruleId`、`GovernanceChecker.controls`、`m.profile`。
- [ ] **Step 6：运行通过**：`./gradlew test --tests 'aster.core.cedar.*'`；再跑 core 全量 `./gradlew test`。
- [ ] **Step 7：提交**：`feat(cedar): 首匹配保持的条件翻译与 Cedar 策略生成（ADR 0047 §4）`。

---

### Task 5: 探针生成（aster-lang-core）

**Files:**
- Create: `aster-lang-core/src/main/java/aster/core/cedar/Probe.java`
- Create: `aster-lang-core/src/main/java/aster/core/cedar/ProbeGenerator.java`
- Test: `ProbeGeneratorTest.java`

**Interfaces:**
- Consumes: `RuleFacts`、`CedarExport`（Task 4）。
- Produces:
  - `record Probe(String principalId, String principalType, List<String> roles, String action, String resourceType, String resourceId, Map<String,Object> context, String agentModel)`，`Map<String,Object> toGuardArgs()` 返回与 `GuardActionMapper.flatten` 同键序的 `LinkedHashMap`（`principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model`；`agent_model` 可为 null，`context` 为不可变 Map）。
  - `ProbeGenerator.generate(RuleFacts facts, String fallbackAction, int max)` → `List<Probe>`（确定性顺序、去重、≤ max）。

**维度：** action（每个提及值 + `__aster_other__`；无提及时只用 `fallbackAction`）；roles（空集、每个提及角色单独、全部提及角色）；principal_type、resource_type、principal_id、resource_id（各：提及值 + `__aster_other__`，无提及时固定 `agent` / `res` / `p-probe` / `r-probe`）；agent_model（缺失 + 每个提及值 + `__aster_other__`，仅 `agentModelUsed` 时变化）；每个 context 键（缺失 + LONG：每个字面量 n-1、n、n+1，越界则省略；STRING：每个字面量 + `__aster_other__`；BOOL：true/false）。

**组合：** 若各维度大小之积 ≤ max，取全笛卡尔积（按维度声明顺序的混合进制递增）；否则先取「基准探针 + 逐一改变单个维度」的全部探针，再按混合进制序号以固定步长 `ceil(total / (max - 已取数))` 补足到 max。基准探针：每维取第一个「存在」值（context 键取第一个字面量）。

- [ ] **Step 1：写失败测试：**

```java
@Test void cartesianWhenSmall() {
  var facts = factsOf("""
      @id("R-1")
      Rule decide given req as GuardRequest, produce Verdict:
        If req.action equals to "delete":
          Return Verdict.deny("no").
        Return Verdict.allow().
      """);
  var probes = ProbeGenerator.generate(facts, "decide", 512);
  assertThat(probes).extracting(Probe::action).containsExactlyInAnyOrder("delete", "__aster_other__");
}
@Test void numericNeighbours() {
  var facts = factsOf("""
      @id("R-2")
      Rule decide given req as GuardRequest, produce Verdict:
        If req.context.amount at least 1000:
          Return Verdict.escalate("big").
        Return Verdict.allow().
      """);
  var values = ProbeGenerator.generate(facts, "decide", 512).stream()
      .map(p -> p.context().get("amount")).toList();
  assertThat(values).contains(999L, 1000L, 1001L).containsNull();
}
@Test void capRespectedAndDeterministic() {
  var facts = wideFacts(); // 8 个 context 键、各 3 个字面量
  var a = ProbeGenerator.generate(facts, "decide", 512);
  var b = ProbeGenerator.generate(facts, "decide", 512);
  assertThat(a).hasSizeLessThanOrEqualTo(512).isEqualTo(b);
}
@Test void noContextRuleProbes() {
  var facts = factsOf("""
      @id("R-3")
      Rule decide given req as GuardRequest, produce Verdict:
        If req.action equals to "a":
          Return Verdict.deny("x").
        If req.action equals to "b":
          Return Verdict.deny("y").
        Return Verdict.allow().
      """);
  assertThat(ProbeGenerator.generate(facts, "decide", 512)).hasSizeGreaterThanOrEqualTo(3);
}
```

（缺失键在 `context()` 中表现为「不存在该键」；测试里 `.get("amount")` 为 null 即代表缺失。`factsOf` 用 `CedarExporter.export(...).facts()`。）
- [ ] **Step 2：运行确认失败。**
- [ ] **Step 3：实现。**
- [ ] **Step 4：运行通过**，并跑 core 全量。
- [ ] **Step 5：提交**：`feat(cedar): 确定性等价性探针生成（ADR 0047 §5）`。

---

### Task 6: 导出服务、严格校验与等价性（aster-api）

**Files:**
- Create: `aster-api/src/main/java/io/aster/policy/cedar/CedarExportService.java`
- Create: `aster-api/src/main/java/io/aster/policy/cedar/CedarExportOutcome.java`
- Test: `aster-api/src/test/java/io/aster/policy/cedar/CedarExportServiceTest.java`（`@QuarkusTest`）
- Test resources: `aster-api/src/test/resources/cedar/guard-cedar-sample.aster`

**Interfaces:**
- Consumes: Task 1 `CedarEngine`；Task 4/5 `CedarExporter`、`CedarExport`、`ProbeGenerator`、`Probe`；`PolicyCompiler.compile(String source, String locale, String aliasSetJson)`；`JacksonMappers.LENIENT`；`DynamicCnlExecutor.executeCoreIrJson(String coreIrJson, Object context, String functionName)`；`VerdictView.from(Object)`。
- Produces:
  - `CedarExportOutcome exportSource(String source, String locale, String aliasSetJson, String functionName)`
  - `record CedarExportOutcome(boolean exportable, String ruleId, String functionName, String policies, String schemaJson, String profile, List<String> controls, int probeCount, int probesAgreed, String sourceHash, List<CompileDiagnostic> diagnostics)`
  - 包可见的 `CedarExportOutcome verify(CedarExport exp, String coreJson, String sourceHash)`（供测试注入被破坏的导出）。

**流程：** `sourceHash` = 源码 UTF-8 的 SHA-256（小写十六进制）→ `PolicyCompiler.compile`；编译失败或有 error 级诊断（含 E705–E707）→ 返回 `exportable=false` 与这些诊断 → 解析 `CoreModel.Module` → `functionName` 缺省时取模块中唯一的、可通过 `GuardShape` 的函数（零个或多个 → E720 `reason` 为 `function name required`）→ `CedarExporter.export` → 不可导出则映射诊断返回 → `CedarEngine.validate`（失败 → E724，`detail` 取第一条错误）→ 探针循环：
  - Aster：`executeCoreIrJson(coreJson, new Object[]{probe.toGuardArgs()}, fn)`；异常 → 期望「Deny 且无决定性策略」；正常 → `VerdictView.from` 得 outcome（大写）。
  - Cedar：`CedarEngine.authorize`（principal/resource/action/context 来自探针；`agent_model` 非空时放入 context）。
  - 判定：Aster `ALLOW` ⇔ `allow=true`；Aster 其他结论 ⇔ `allow=false` 且决定性策略恰一条、该策略在 `exp.emitted()` 中的 `outcome`（大写比较）、`reason`、`approver`（对应 Verdict role）与 Aster 一致；Aster 抛错 ⇔ `allow=false` 且无决定性策略。
  - 首个不一致 → E723（`probe` 为探针 JSON，`aster`/`cedar` 为两侧摘要），`exportable=false`。
- 诊断转换为 `CompileDiagnostic`（core `Diagnostic` 的 span 有则用其行列，否则 1/1；`severity` 小写 `error`）。

- [ ] **Step 1：写样本** `guard-cedar-sample.aster`：

```
Module guard.cedar.sample.

Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.

@id("GCS-001")
@control("EU_AI_ACT:ART14")
Rule decide given req as GuardRequest, produce Verdict:
  Let large be req.context.amount at least 10000.
  When req.action equals to "delete_customer_record", require approval by "Data Protection Officer" because "customer record deletion".
  If List.contains(req.principal_roles, "suspended"):
    Return Verdict.deny("suspended principal").
  If large and req.action equals to "refund":
    Return Verdict.escalate("large refund").
  Otherwise allow.
```

- [ ] **Step 2：写失败测试：**

```java
@Inject CedarExportService service;

@Test void sampleExportsAndAllProbesAgree() {
  var out = service.exportSource(read("cedar/guard-cedar-sample.aster"), "en-US", null, null);
  assertThat(out.exportable()).as("%s", out.diagnostics()).isTrue();
  assertThat(out.ruleId()).isEqualTo("GCS-001");
  assertThat(out.probeCount()).isPositive().isEqualTo(out.probesAgreed());
  assertThat(out.policies()).contains("@aster_outcome(\"escalate\")").contains("@aster_controls(\"EU_AI_ACT:ART14\")");
}

@Test void probePolicyFromGuardTestExports() {
  var out = service.exportSource(DeployedMapParamProbeTest.SOURCE, "en-US", null, "decide");
  assertThat(out.exportable()).isTrue();
  assertThat(out.probeCount()).isEqualTo(out.probesAgreed());
}

@Test void brokenEmitterIsCaughtAsE723() {
  String src = read("cedar/guard-cedar-sample.aster");
  var compiled = compileToModule(src); // 测试辅助：PolicyCompiler + LENIENT 解析
  var exp = new CedarExporter().export(compiled.module(), "decide", "h");
  // 故意把第一条 forbid 改为 permit，制造与 Aster 不一致的导出
  var broken = withFirstForbidAsPermit(exp);
  var out = service.verify(broken, compiled.coreJson(), "h");
  assertThat(out.exportable()).isFalse();
  assertThat(out.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E723");
}

@Test void invalidSchemaIsCaughtAsE724() {
  var compiled = compileToModule(read("cedar/guard-cedar-sample.aster"));
  var exp = new CedarExporter().export(compiled.module(), "decide", "h");
  var broken = withSchema(exp, exp.schemaJson().replace("\"required\":false", "\"required\":true").replace("\"Long\"", "\"String\""));
  var out = service.verify(broken, compiled.coreJson(), "h");
  assertThat(out.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E724");
}

@Test void profileViolationBlocksExport() {
  String src = "Module m.\nProfile \"governed\".\n\n" + GUARD_DEFINE
      + "Rule decide given req as GuardRequest, produce Verdict:\n  Return Verdict.allow().\n";
  var out = service.exportSource(src, "en-US", null, "decide");
  assertThat(out.exportable()).isFalse();
  assertThat(out.diagnostics()).extracting(CompileDiagnostic::code).contains("E706");
}

@Test void missingContextKeyAgreesAsDeny() {
  // 样本第一条读 context.amount 的 Let：探针中 amount 缺失时 Aster 抛错，Cedar 必须 Deny 且无决定性策略
  var out = service.exportSource(read("cedar/guard-cedar-sample.aster"), "en-US", null, null);
  assertThat(out.probesAgreed()).isEqualTo(out.probeCount());
}
```

（`withFirstForbidAsPermit` / `withSchema` 是测试类里的私有辅助，返回字段替换后的新 `CedarExport` 记录；`GUARD_DEFINE` 为 Define 行常量。）
- [ ] **Step 3：运行确认失败**：`DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380 ./gradlew --offline :test --tests 'io.aster.policy.cedar.*'`。
- [ ] **Step 4：实现** `CedarExportService`（`@ApplicationScoped`，注入 `PolicyCompiler`、`CedarEngine`）。
- [ ] **Step 5：运行通过。**
- [ ] **Step 6：提交**：`feat(cedar): 导出服务、严格校验与逐探针等价性（ADR 0047 §5）`。

---

### Task 7: 端点、鉴权与审计（aster-api）

**Files:**
- Create: `aster-api/src/main/java/io/aster/policy/rest/CedarExportResource.java`
- Create: `aster-api/src/main/java/io/aster/policy/rest/model/CedarExportRequest.java`、`CedarExportResponse.java`
- Modify: `aster-api/src/main/java/io/aster/policy/event/EventType.java`（追加 `POLICY_EXPORTED`）
- Modify: `aster-api/src/main/java/io/aster/audit/chain/AuditChainAppender.java`（`IDENTITY_METADATA_KEYS` 加 `policiesHash`、`schemaHash`；`sourceHash` 已在）
- Modify: `aster-api/src/main/java/io/aster/security/apikey/ApiKeyAuthFilter.java:215-262`（`shouldProtect` 纳入 `/api/v1/policies/export/cedar`）
- Modify: `aster-api/src/main/java/io/aster/security/.../InternalCallerFilter.java:160-200`（`classify` 按 `isGuardDualChannel` 模式加入该路径的双通道）
- Modify: `aster-api/src/test/resources/api/public-api-contract.golden`（追加 `POST /api/v1/policies/export/cedar`）
- Test: `aster-api/src/test/java/io/aster/policy/rest/CedarExportResourceTest.java`

**Interfaces:**
- Consumes: Task 6 `CedarExportService.exportSource`；`PolicyVersion.findByIdInTenant(Long id, String tenantId)`；`TenantContext#getCurrentTenant()`；`AuditChainAppender.appendSync(AuditEvent)`；`InternalCallerSigning.headers(...)`（测试）。
- Produces: REST `POST /api/v1/policies/export/cedar`：
  - `CedarExportRequest(String source, String locale, Map<String,List<String>> aliasSet, String functionName, Long versionId, Map<String,String> claimedRef)`；`source` 与 `versionId` 恰给其一（否则 400 `{"error":"exactly one of source or versionId"}`）；`source` 长度上限复用 `CompileRequest.MAX_ANON_SOURCE_LENGTH`；`claimedRef` 最多 4 项、键值各 ≤ 128 字符。
  - `CedarExportResponse(boolean exportable, String policies, JsonNode schema, String ruleId, String profile, List<String> controls, Probes probes, List<CompileDiagnostic> diagnostics, String sourceHash)`，`record Probes(int count, int agreed)`；不可导出 HTTP 200。
  - `versionId` 模式：`PolicyVersion.findByIdInTenant`（找不到 → 404），用其 `content`、语言（实体上的语言字段；无则 `en-US`）、`aliasSet`、`functionName`。
  - 成功导出时在**工作线程**（非事务）`appendSync(new AuditEvent(EventType.POLICY_EXPORTED, Instant.now(), tenant, module, function, null, null, versionId 文本或 null, performedBy, true, null, null, metadata, null, null, null, null))`，`metadata` 键见 Global Constraints（`claimedRef` 以 JSON 字符串存入）。

- [ ] **Step 1：写失败测试**（REST-assured；HMAC 档案沿用 `PolicyEvaluationReplayOrderingTest.HmacProfile`）：

```java
@Test void anonymousIsRejected() {
  given().contentType(ContentType.JSON).body(Map.of("source", SAMPLE))
      .post("/api/v1/policies/export/cedar").then().statusCode(401);
}
@Test void hmacCallExportsAndAudits() {
  String body = json(Map.of("source", SAMPLE, "locale", "en-US",
      "claimedRef", Map.of("cloudPolicyId", "pol-1", "version", "3")));
  var resp = given().headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, "", body, TENANT, "MEMBER", "u-1", List.of()))
      .contentType(ContentType.JSON).body(body).post(PATH);
  resp.then().statusCode(200).body("exportable", equalTo(true)).body("ruleId", equalTo("GCS-001"));
  var row = awaitExportEvent(TENANT);
  var meta = metadataOf(row);
  assertThat(meta).containsEntry("target", "cedar").containsEntry("ruleId", "GCS-001")
      .containsKeys("sourceHash", "policiesHash", "schemaHash", "probeCount", "claimedRef");
  assertThat((String) meta.get("policiesHash")).matches("[0-9a-f]{64}");
}
@Test void notExportableReturns200WithDiagnosticsAndNoAudit() {
  String src = GUARD_MODULE + "@id(\"R-X\")\nRule decide given req as GuardRequest, produce Verdict:\n"
      + "  If Text.length(req.action) at least 1:\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n";
  String body = json(Map.of("source", src, "locale", "en-US"));
  signed(body).post(PATH).then().statusCode(200)
      .body("exportable", equalTo(false))
      .body("diagnostics.code", hasItem("E721"))
      .body("policies", nullValue());
  assertThat(exportEventCount(TENANT)).isZero();
}
@Test void exactlyOneOfSourceOrVersionId() {
  String both = json(Map.of("source", SAMPLE, "versionId", 1));
  signed(both).post(PATH).then().statusCode(400).body("error", equalTo("exactly one of source or versionId"));
  String neither = json(Map.of("locale", "en-US"));
  signed(neither).post(PATH).then().statusCode(400).body("error", equalTo("exactly one of source or versionId"));
}
@Test void versionIdIsTenantScoped() {
  Long versionId = DeployedMapParamProbeTest.deploy("tenant-a-cedar", "guard.cedar.sample", "decide", SAMPLE);
  String body = json(Map.of("versionId", versionId));
  signedAs("tenant-b-cedar", body).post(PATH).then().statusCode(404);
  signedAs("tenant-a-cedar", body).post(PATH).then().statusCode(200).body("exportable", equalTo(true));
}
```

辅助：`signed(body)` / `signedAs(tenant, body)` = `given().headers(InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, "", body, tenant, "MEMBER", "u-1", List.of())).contentType(ContentType.JSON).body(body)`；`awaitExportEvent` 照 `EvaluateEvidenceEventTest.awaitEvent` 轮询 `QuarkusTransaction.requiringNew().call(() -> AuditLog.find("tenantId = ?1 and eventType = 'POLICY_EXPORTED'", tenant).list())`，50 次 × 100ms；`exportEventCount` 为同一查询的计数（先 `Thread.sleep(300)` 留出异步写入窗口）。`DeployedMapParamProbeTest.deploy` 若当前不返回版本 id，在本任务中改为返回新建 `PolicyVersion.id`（同时保持现有调用点编译通过）。
- [ ] **Step 2：运行确认失败**（同 Task 6 环境变量）。
- [ ] **Step 3：实现** resource（类级 `@RequireRole(Role.MEMBER)`，与 `PolicyEvaluationResource` 一致）、DTO、过滤器、审计、事件类型、契约黄金行。
- [ ] **Step 4：运行通过**；再跑 `'*PublicApiContractTest*'`、`'io.aster.audit.*'`、`'*InternalCallerFilter*'`、`'*ApiKeyAuthFilter*'`。
- [ ] **Step 5：aster-api 全量**：`./gradlew --offline :replay:cleanTest :cleanTest :replay:test :test --continue`（已知基线仅 `PrismaDatabaseE2ETest`）。
- [ ] **Step 6：提交**：`feat(cedar): 导出端点、双通道鉴权与 POLICY_EXPORTED 审计（ADR 0047 §6）`。

---

### Task 8: 策略详情页「导出为 Cedar」（aster-cloud）

**Files:**
- Modify: `aster-cloud/src/config/api-versions.ts`（`API_ENDPOINTS.exportCedar = \`${prefix}/policies/export/cedar\``）
- Modify: `aster-cloud/src/services/policy/policy-api.ts`（`exportCedar(request: PolicyCedarExportRequest): Promise<PolicyCedarExportResponse>`，签名沿用 `request()` 的自动 HMAC）
- Create: `aster-cloud/src/app/api/policies/[id]/export/cedar/route.ts`
- Create: `aster-cloud/src/components/policy/cedar-export-panel.tsx`
- Modify: `aster-cloud/src/app/[locale]/(dashboard)/policies/[id]/policy-detail-content.tsx`（操作区加入面板）
- Modify: `aster-cloud/src/i18n/demo-supplement.ts`（en/zh/de/hi：`policies.cedarExport.*`）
- Test: `aster-cloud/src/__tests__/api/policies-cedar-export-route.test.ts`、`aster-cloud/src/__tests__/components/policy/cedar-export-panel.test.tsx`

**Interfaces:**
- Consumes: Task 7 端点契约。
- Produces:
  - 路由 `POST /api/policies/[id]/export/cedar`：`getSession()`（无 → 401）→ `checkRateLimit(\`policy-cedar-export:${userId}\`, RateLimitPresets.EVALUATE_SOURCE)`（超限 429 + 限流头）→ 按 `db.query.policies.findFirst({ where: and(eq(policies.id, id), eq(policies.userId, session.user.id)) })` 取策略（无 → 404）→ 取激活版本 `aliasSet`（同 `edit/page.tsx:41-53` 查询）→ `detectCNLLocale(policy.content)` → `createPolicyApiClientForUser(...)` 调 `exportCedar({source: policy.content, locale, aliasSet, claimedRef: {cloudPolicyId: id, version: String(policy.version)}})` → 原样返回上游 JSON；上游错误 → 502 `{success:false, error}`。
  - `CedarExportPanel({ policyId, moduleName, labels })`：按钮触发 POST；`exportable` 时下载 `<moduleName>.cedar`（`text/plain`）与 `<moduleName>.cedarschema.json`（`application/json`，2 空格缩进），并显示「已比对 N 个探针」；否则列出诊断（码 + 行号 + 消息）。
  - 文案键：`policies.cedarExport.button`、`policies.cedarExport.exporting`、`policies.cedarExport.verified`（含 `{count}`）、`policies.cedarExport.notExportable`、`policies.cedarExport.failed`。en：`Export to Cedar` / `Exporting…` / `Verified against Cedar on {count} probes` / `This policy cannot be exported to Cedar` / `Export failed`；zh：`导出为 Cedar` / `导出中…` / `已用 Cedar 比对 {count} 个探针` / `该策略无法导出为 Cedar` / `导出失败`；de：`Nach Cedar exportieren` / `Wird exportiert…` / `Mit Cedar an {count} Prüffällen abgeglichen` / `Diese Richtlinie kann nicht nach Cedar exportiert werden` / `Export fehlgeschlagen`；hi 用英文。

- [ ] **Step 1：写失败测试**（路由，照 `policies-compile-route.test.ts` 与 `guard-register-route.test.ts` 的 mock 方式）：

```ts
it('401 without session', async () => { getSession.mockResolvedValue(null); expect((await POST(req(), ctx('p1'))).status).toBe(401); });
it('404 when policy not owned', async () => { findFirst.mockResolvedValue(undefined); expect((await POST(req(), ctx('p1'))).status).toBe(404); });
it('forwards source, detected locale, aliasSet and claimedRef', async () => {
  findFirst.mockResolvedValue({ id: 'p1', userId: 'u1', content: 'Module m.\n…', version: 3 });
  versionFindFirst.mockResolvedValue({ aliasSet: JSON.stringify({ IF: ['when'] }) });
  exportCedar.mockResolvedValue({ exportable: true, policies: 'permit…', schema: {}, probes: { count: 4, agreed: 4 }, diagnostics: [] });
  const res = await POST(req(), ctx('p1'));
  expect(res.status).toBe(200);
  expect(exportCedar).toHaveBeenCalledWith(expect.objectContaining({
    source: 'Module m.\n…', aliasSet: { IF: ['when'] }, claimedRef: { cloudPolicyId: 'p1', version: '3' } }));
});
it('502 on upstream error', async () => {
  findFirst.mockResolvedValue({ id: 'p1', userId: 'u1', content: 'Module m.', version: 1 });
  versionFindFirst.mockResolvedValue({ aliasSet: null });
  exportCedar.mockRejectedValue(new PolicyApiError('upstream down', 503));
  const res = await POST(req(), ctx('p1'));
  expect(res.status).toBe(502);
  expect(await res.json()).toEqual({ success: false, error: 'upstream down' });
});
```

面板测试（`@testing-library/react`，mock `global.fetch` 与 `URL.createObjectURL`）：可导出时点击按钮触发两个下载（断言 `createObjectURL` 调用两次、两个文件名）、显示 `Verified against Cedar on 4 probes`；不可导出时列出诊断码 `E721`。
- [ ] **Step 2：运行确认失败**：`export PATH=/opt/homebrew/opt/node@24/bin:$PATH && pnpm exec vitest run src/__tests__/api/policies-cedar-export-route.test.ts src/__tests__/components/policy/cedar-export-panel.test.tsx`。
- [ ] **Step 3：实现**客户端方法、路由、面板、详情页接入、四语文案。
- [ ] **Step 4：运行通过**；再跑 `pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm exec vitest run && pnpm check:locales:strict && pnpm check:message-keys && pnpm routes:check`。
- [ ] **Step 5：提交**（不提交 AGENTS.md / CLAUDE.md）：`feat(cedar): 策略详情页导出为 Cedar（ADR 0047 §6）`。

---

### Task 9: 本地栈验收、ADR 定稿与发布

**Files:**
- Modify: `aster-api/docs/adr/0047-cedar-export.md`（状态 ACCEPTED，追加 §9 验收记录）
- Modify: `aster-api/docs/adr/0039-verdict-primitive-and-rule-metadata.md`（§1 行 6 改为「6a → ADR 0046；6b → ADR 0047」）
- Create: `aster-cloud/src/__tests__/e2e-browser/local-stack/cedar-export.spec.ts`

- [ ] **Step 1：本地栈**：按记忆文件 `aster-local-verification-env` 的配方重建 aster-api 镜像与容器、重启 aster-cloud；在 cloud 中创建一个使用 `guard-cedar-sample.aster` 源码的策略。
- [ ] **Step 2：Playwright**：新 spec 打开该策略详情页，点击「Export to Cedar」，断言出现 `Verified against Cedar on` 文案并捕获两个下载（文件名与内容非空，`.cedar` 含 `@aster_rule("GCS-001")`）；再用一条含 `Text.length` 的策略断言出现 `E721`。串行运行整个 local-stack 套件。
- [ ] **Step 3：审计核对**：在 aster-api 审计表查到对应 `POLICY_EXPORTED` 事件，`claimedRef` 含 cloud 策略 id。
- [ ] **Step 4：外部工具交叉核对**：若本机可安装 Cedar CLI（`cargo install cedar-policy-cli`），对下载的两个文件跑 `cedar validate --schema <schema> --policies <cedar> --schema-format json`，结果写入验收记录；不可安装则记录原因（CI 中已由 cedar-java 严格校验覆盖）。
- [ ] **Step 5：ADR 定稿与 0039 回写**，提交 `docs(adr): ADR 0047 ACCEPTED，0039 回写`。
- [ ] **Step 6：发布**：core（新包与错误码）与 ts（错误码）需进入下一次发版列车；按既有 1.0.32 流程（platform bump → 各仓 pin bump PR → dryRun → 正式列车 → aster-cloud/aster-dev 自动升级 PR）。是否立即发版由用户决定，计划不自动触发。
