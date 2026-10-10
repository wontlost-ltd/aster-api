# ADR 0046 Governance Profile 与 When/Otherwise 语法糖 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 模块可声明 `Profile "<id>".` 并受注册表中的档案约束（违者 E705/E706 错误、阻止保存）；规则体可用 `When … / Otherwise …` 简写，降糖为既有 `If … Return Verdict.*`；档案沿评估证据进入证据包 v5。

**Architecture:** 档案定义作为 `profiles` 数据加入控制注册表（aster-lang-locales，版本 1.1.0，三份副本同步）。六个新 `SemanticTokenKind`（PROFILE、ALLOW、DENY、ESCALATE、REQUIRE_APPROVAL_BY、BECAUSE）进 core 枚举与四语词法包，Java 侧经 Canonicalizer 规范化后以软关键字/TYPE_IDENT 识别，不新增硬词法记号。两个前端在解析阶段降糖并为 Module 增可选 `profile` 字段（缺省不序列化）。两个治理检查器读注册表档案报 E705/E706；aster-api 编译通道对这两码保留 error，并把 `profile` 沿评估路径写入证据；aster-cloud 加 `Execution.profile` 列与证据包 v5。

**Tech Stack:** ANTLR4 + Java 21（aster-lang-core）；TypeScript 手写解析器（aster-lang-ts）；Gradle Kotlin DSL（aster-lang-locales、aster-lang-hi）；Quarkus（aster-api）；Next.js 16 + drizzle + vitest + Playwright（aster-cloud）。

**Spec:** `docs/adr/0046-governance-profile.md`。代码摘录：`/private/tmp/aster-stack/adr0046-refs.md`（下文「refs §n」，§0 为发现汇总，务必先读）。

## Global Constraints

- 注释一律简体中文、只写意图；禁止「修改说明」式注释；缩进 ≤ 3 层；沿用既有 import 顺序；提交 trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`；不推送；aster-cloud 不提交 AGENTS.md/CLAUDE.md。
- 档案 id 形态逐字：`^[a-z][a-z0-9-]{0,63}$`；声明语法逐字：`Profile "<id>".`，紧跟 `Module` 行，至多一条。
- 初始档案逐字：`eu-ai-act-high-risk`（requires `ruleId: true, registeredControls: true, frameworks: ["EU_AI_ACT"]`）、`governed`（requires `ruleId: true, registeredControls: true, frameworks: []`）。注册表 `version` 逐字 `"1.1.0"`。
- 新词法 kind 与用词逐字（en / zh / de / hi）：PROFILE `Profile`/`档案`/`Profil`/`प्रोफ़ाइल`；ALLOW `allow`/`允许`/`erlauben`/`अनुमति`；DENY `deny`/`拒绝`/`ablehnen`/`अस्वीकार`；ESCALATE `escalate`/`升级`/`eskalieren`/`आगे बढ़ाएँ`；REQUIRE_APPROVAL_BY `require approval by`/`需审批人`/`Genehmigung durch`/`अनुमोदक`；BECAUSE `because`/`因为`/`weil`/`क्योंकि`。`When`/`Otherwise` 复用既有 WHEN/OTHERWISE。六个新 kind 列入 Java `OPTIONAL_KINDS` 与 TS `CI_OPTIONAL_KINDS`。
- 语法糖逐字：`When <cond>, allow.` | `When <cond>, deny <text>.` | `When <cond>, escalate <text>.` | `When <cond>, require approval by <text> because <text>.`；`Otherwise <outcome>.` 同四种结论。降糖：`When` → `If <cond>:` 块内单条 `Return Verdict.<op>(…)`；`Otherwise` → `Return Verdict.<op>(…)`；`<op>` 为 `allow`/`deny`/`escalate`/`require_approval`，`require_approval` 参数顺序为 (role, reason)。
- `Otherwise` 后还有语句 → 解析错误，消息逐字 `Otherwise must be the last statement of its block`。
- 错误码逐字：E705 `GOV_PROFILE_UNKNOWN`（error，governance）message `Module '{module}' declares unknown profile '{profile}' (controls registry {version})`，help `Use a profile defined in aster-lang-locales controls/registry.json, such as governed or eu-ai-act-high-risk.`；E706 `GOV_PROFILE_VIOLATION`（error，governance）message `Rule '{rule}' violates profile '{profile}': {requirement}`，help `Satisfy the profile requirement or remove the Profile declaration.`；`{requirement}` 取值逐字 `missing @id`、`unregistered control {control}`、`no registered control from framework {frameworks}`（多个框架以 `, ` 连接）。
- E705/E706 只对声明了档案的模块、且只对返回 Verdict 的规则（E706）生效；同一规则同一要求只报一次；W700/W704 照常。aster-api 编译通道：E705/E706 severity `error`，其余治理码仍 `warning`；`success` 不变。
- 未声明档案、未用语法糖的模块：IR 逐字节不变（`profile` 字段缺省不输出），诊断不变。
- 证据包 `schemaVersion: '5'`，manifest 新增 `profilesUsed: Array<{ id: string; title: { en; zh; de } }>`；条目新增 `profile: string | null`；现行 v4 复制为 `*V4` 冻结。
- 测试命令：core `./gradlew --offline test --tests '...'`；ts 按 package.json 脚本（`pnpm build` 后 `node --test dist/test/...`，全量 `pnpm test`）；api `DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380 ./gradlew --offline :test --rerun --tests '...'`；cloud `export PATH=/opt/homebrew/opt/node@24/bin:$PATH; pnpm exec vitest run <path>`；locales `./gradlew --offline check`。绝不触碰 `aster-*` 容器（验收任务除外）。

## Review Focus

1. 既有策略里小写 `profile` 作参数名、或 `Verdict.allow()` 成员调用 → 解析与 IR 完全不变 —— Task 3、Task 4 回归用例。
2. `If c:` 块之后紧跟 `Otherwise deny "x".`（语法糖）与 `Otherwise:` 换行块（else 分支）→ 两个前端都正确区分 —— Task 3、Task 4 用例。
3. 声明了档案但规则不返回 Verdict（辅助函数）→ 不报 E706 —— Task 5、Task 6 用例。
4. `Profile "unknown-x".` 的模块在 aster-cloud 保存 → 400（E705 为 error），而未声明档案、只缺 `@id` 的模块照常保存 —— Task 7、Task 8 用例。
5. 已存的 v1–v4 证据包下载与对照视图不受 v5 影响；v5 包 `profilesUsed` 只列条目中实际出现的档案 —— Task 8 用例。

---

### Task 1: 注册表 profiles 数据与校验（aster-lang-locales）

**Files:**
- Modify: `controls/registry.json`（`version` → `"1.1.0"`，新增 `profiles`）
- Modify: `build.gradle.kts`（`validateControlsRegistry` 校验 profiles）
- Create: `controls/src/test-fixtures/bad-profile-unknown-framework.json`、`controls/src/test-fixtures/bad-profile-duplicate-id.json`
- Modify: `controls/README.md`（负向夹具清单加两条）

**Interfaces:**
- Produces: `profiles: Array<{ id, title: {en,zh,de}, requires: { ruleId: boolean, registeredControls: boolean, frameworks: string[] } }>`（Task 5/6/8 的副本深度相等于它）。

- [ ] **Step 1: 数据**

在 `registry.json` 顶层（`clauses` 之后）加入：

```json
  "profiles": [
    { "id": "governed",
      "title": { "en": "Governed rules", "zh": "受治理规则", "de": "Gesteuerte Regeln" },
      "requires": { "ruleId": true, "registeredControls": true, "frameworks": [] } },
    { "id": "eu-ai-act-high-risk",
      "title": { "en": "EU AI Act high-risk system", "zh": "欧盟人工智能法高风险系统", "de": "Hochrisiko-KI-System nach EU-KI-Verordnung" },
      "requires": { "ruleId": true, "registeredControls": true, "frameworks": ["EU_AI_ACT"] } }
  ]
```

`"version": "1.0.0"` 改 `"1.1.0"`。

- [ ] **Step 2: 校验**

`validateControlsRegistry` 末尾追加（变量名沿用该函数已有的 `frameworkIds`、`missingTitles`）：

```kotlin
    // 档案：id 形态与唯一、三语标题、requires 三个字段齐全、frameworks 只引用已登记框架
    @Suppress("UNCHECKED_CAST") val profiles = reg["profiles"] as? List<Map<String, Any?>> ?: emptyList()
    val profileIds = mutableSetOf<String>()
    for (p in profiles) {
        val id = p["id"] as? String ?: "".also { problems += "档案缺 id: $p" }
        if (!PROFILE_ID.matches(id)) problems += "档案 id 形态非法: $id"
        if (!profileIds.add(id)) problems += "档案 id 重复: $id"
        problems += missingTitles("档案 $id", p["title"])
        problems += profileRequiresProblems(id, p["requires"], frameworkIds)
    }
```

并在文件中与 `CONTROL_KEY` 相邻处定义：

```kotlin
val PROFILE_ID = Regex("^[a-z][a-z0-9-]{0,63}$")

fun profileRequiresProblems(id: String, requires: Any?, frameworkIds: Set<String>): List<String> {
    @Suppress("UNCHECKED_CAST") val r = requires as? Map<String, Any?> ?: return listOf("档案 $id 缺 requires")
    val out = mutableListOf<String>()
    if (r["ruleId"] !is Boolean) out += "档案 $id 的 requires.ruleId 须为布尔"
    if (r["registeredControls"] !is Boolean) out += "档案 $id 的 requires.registeredControls 须为布尔"
    @Suppress("UNCHECKED_CAST") val fws = r["frameworks"] as? List<Any?>
    if (fws == null) return out + "档案 $id 的 requires.frameworks 须为数组"
    fws.filter { it !in frameworkIds }.forEach { out += "档案 $id 引用未登记框架: $it" }
    return out
}
```

- [ ] **Step 3: 负向夹具**

两个夹具均为完整注册表（复制 Step 1 后的 `registry.json`），分别改为：`bad-profile-unknown-framework.json` 中 `eu-ai-act-high-risk` 的 `frameworks` 改为 `["NOPE"]`；`bad-profile-duplicate-id.json` 中两条档案 id 都为 `governed`。

- [ ] **Step 4: 运行**

Run: `./gradlew --offline exportControls`（期望 `controls registry 1.1.0 已导出`）；`./gradlew --offline exportControls -PcontrolsSource=controls/src/test-fixtures/bad-profile-unknown-framework.json`（期望失败，含 `档案 eu-ai-act-high-risk 引用未登记框架: NOPE`）；`-PcontrolsSource=…/bad-profile-duplicate-id.json`（期望失败，含 `档案 id 重复: governed`）；既有三个 bad 夹具仍失败；`./gradlew --offline check` 通过。

- [ ] **Step 5: 提交**

```bash
git add controls build.gradle.kts
git commit -m "feat(controls): 注册表增 profiles 并校验（ADR 0046 §3）"
```

---

### Task 2: 六个新词法 kind（aster-lang-core + aster-lang-locales + aster-lang-hi + aster-lang-ts）

**Files:**
- Modify: `aster-lang-core/src/main/java/aster/core/lexicon/SemanticTokenKind.java`（在 RETURN 附近新增六项，带中文注释）与其分类表（refs §1 `Map.entry("control", …)` 处把六项归入 `control` 组）
- Modify: `aster-lang-core/src/main/java/aster/core/lexicon/LexiconRegistry.java:42-43`（OPTIONAL_KINDS 加六项）
- Modify: `aster-lang-core/src/main/resources/builtin/en-US.json`
- Modify: `aster-lang-locales/locales/{en,zh,de}/src/main/resources/lexicons/*.json`
- Modify: `aster-lang-hi/src/main/resources/lexicons/hi-IN.json`
- Modify: `aster-lang-ts/src/config/semantic.ts`（KW 常量）、`aster-lang-ts/scripts/verify-lexicon-sync.ts:225`（CI_OPTIONAL_KINDS 加六项）
- Regenerate: `aster-lang-ts/src/config/lexicons/*.ts`（经官方脚本）

**Interfaces:**
- Produces: `SemanticTokenKind.{PROFILE, ALLOW, DENY, ESCALATE, REQUIRE_APPROVAL_BY, BECAUSE}`；TS `KW.PROFILE`、`KW.ALLOW`、`KW.DENY`、`KW.ESCALATE`、`KW.REQUIRE_APPROVAL_BY`、`KW.BECAUSE`（值为英文小写规范词：`profile`、`allow`、`deny`、`escalate`、`require approval by`、`because`，与 semantic.ts 中既有 KW 的书写一致）。

- [ ] **Step 1: 冲突复核（先做，结果写进报告）**

对 aster-lang-test 语料、aster-lang-ts 测试夹具、aster-cloud `src/data`、`src/config` 中的 `.aster` 与策略模板字符串，按 Global Constraints 的四语用词逐一 grep（Canonicalizer 关键字匹配大小写不敏感，见 refs §0），列出命中。命中只允许出现在：成员名 `Verdict.allow/deny/escalate`、字符串字面量、注释。若有标识符命中，停止并报告。

- [ ] **Step 2: core 枚举、OPTIONAL_KINDS、en 内置词表**

六项依 Global Constraints 用词写入 `en-US.json`；`LexiconRegistry` 的 `OPTIONAL_KINDS` 加六项并加注释「ADR 0046 新 kind，发版列车完成前保留」。core 单测：既有词法相关测试全绿（`./gradlew --offline test --tests '*Lexicon*'`）。

- [ ] **Step 3: locales 三语与 hi 用词**

zh/de/en 词表 JSON 与 hi-IN.json 按 Global Constraints 逐字写入。`./gradlew --offline check`（locales）与 aster-lang-hi 的 `./gradlew --offline check`（若有）通过。

- [ ] **Step 4: 本地发版并重生成 TS 词表**

```bash
cd ../aster-lang-locales && ./gradlew --offline publishToMavenLocal
cd ../aster-lang-hi && ./gradlew --offline publishToMavenLocal
cd ../aster-lang-core && ./gradlew --offline exportLexicons
cd ../aster-lang-ts && npx tsx scripts/generate-lexicons.ts && pnpm verify:lexicon-sync
```

core 的仓库解析把 `mavenLocal()` 放在前面（refs：core settings.gradle.kts），故 `exportLexicons` 读到本地刚发布的 1.0.30 词法包。若解析仍取到旧包（导出的 JSON 不含新 kind），停止并报告解析路径，不得手改 `@generated` 文件。报告中写明：`~/.m2/repository/cloud/aster-lang/aster-lang-locales-*/1.0.30` 与 `aster-lang-hi` 对应目录现为本地构建版本。

- [ ] **Step 5: TS KW 与 CI_OPTIONAL_KINDS**

`semantic.ts` 增六个 KW 常量；`verify-lexicon-sync.ts` 的 `CI_OPTIONAL_KINDS` 加六项（注释同 Step 2）。`pnpm build && pnpm verify:lexicon-sync` 通过；ts 全量 `pnpm test` 通过（新词尚未被解析器使用，行为不变）。

- [ ] **Step 6: 各仓提交**

```bash
# core
git add src/main/java/aster/core/lexicon src/main/resources/builtin/en-US.json
git commit -m "feat(lexicon): Profile 与语法糖结论词六个 kind（ADR 0046 §2/§5）"
# locales
git add locales
git commit -m "feat(lexicon): 三语补 Profile 与语法糖结论词（ADR 0046）"
# hi
git add src/main/resources/lexicons/hi-IN.json
git commit -m "feat(lexicon): hi 补 Profile 与语法糖结论词（ADR 0046，用词待母语审校）"
# ts
git add src/config scripts/verify-lexicon-sync.ts
git commit -m "feat(lexicon): 重生成词表并登记 ADR 0046 新 kind"
```

---

### Task 3: core 解析 Profile 声明与 When/Otherwise 降糖（aster-lang-core）

**Files:**
- Modify: `src/main/antlr/AsterParser.g4`（`module`、`stmt`，新增 `profileDecl`、`whenStmt`、`otherwiseStmt`、`sugarOutcome`）
- Modify: `src/main/java/aster/core/parser/AstBuilder.java`（`visitModule`、新增三个 visit 方法、Otherwise 末尾检查）
- Modify: `src/main/java/aster/core/ast/Module.java`（增 `profile`）及所有 `new Module(` 构造点（refs §0：15 处，main 仅 AstBuilder）
- Modify: `src/main/java/aster/core/ir/CoreModel.java:44-49`（`Module.profile` + 字段级 `@JsonInclude(JsonInclude.Include.NON_NULL)`）
- Modify: `src/main/java/aster/core/lowering/CoreLowering.java:31-45`（复制 profile）
- Test: `src/test/java/aster/core/parser/GovernanceSugarParseTest.java`

**Interfaces:**
- Produces: `aster.core.ast.Module(String name, String profile, List<Decl> decls, Span span)`（profile 可为 null）；`CoreModel.Module.profile`（String，null 时不序列化）。Task 5、Task 7 使用。

- [ ] **Step 1: 失败测试**

用 `GovernanceTestSupport` 同款管线（Canonicalizer → AsterCustomLexer → AsterParser → AstBuilder → CoreLowering），再用 `new ObjectMapper()` 序列化 Core 模块：

```java
package aster.core.parser;

/** ADR 0046：Profile 声明与 When/Otherwise 语法糖在 core 前端的解析与降糖。 */
class GovernanceSugarParseTest {
  // 辅助：lower(src) 返回 CoreModel.Module；json(core) 返回去掉所有 origin 键后的 JsonNode

  @Test void 声明档案写入模块并序列化() {
    var core = lower("Module m.\nProfile \"governed\".\n\nRule r produce Int:\n  Return 1.\n");
    assertEquals("governed", core.profile);
    assertEquals("governed", json(core).path("profile").asText());
  }

  @Test void 未声明档案时不输出profile字段() {
    var core = lower("Module m.\n\nRule r produce Int:\n  Return 1.\n");
    assertFalse(json(core).has("profile"));
  }

  @Test void 小写profile参数名不受影响() {
    var core = lower("Module m.\n\nRule r given profile, produce Int:\n  Return 1.\n");
    assertNull(core.profile);
  }

  @Test void 档案id形态非法是解析错误() {
    assertThrows(RuntimeException.class, () -> lower("Module m.\nProfile \"Bad_Id\".\n"));
  }

  @Test void 语法糖与长写法IR相等() {
    String sugar = String.join("\n", "Module m.", "",
      "Rule decide given amount, produce Verdict:",
      "  When amount at least 100, require approval by \"Officer\" because \"large\".",
      "  When amount at most 0, deny \"negative\".",
      "  When amount at least 50, escalate \"review\".",
      "  Otherwise allow.", "");
    String longForm = String.join("\n", "Module m.", "",
      "Rule decide given amount, produce Verdict:",
      "  If amount at least 100:",
      "    Return Verdict.require_approval(\"Officer\", \"large\").",
      "  If amount at most 0:",
      "    Return Verdict.deny(\"negative\").",
      "  If amount at least 50:",
      "    Return Verdict.escalate(\"review\").",
      "  Return Verdict.allow().", "");
    assertEquals(json(lower(longForm)), json(lower(sugar)));
  }

  @Test void Otherwise之后还有语句是解析错误() {
    var ex = assertThrows(RuntimeException.class, () -> lower(String.join("\n", "Module m.", "",
      "Rule r produce Verdict:", "  Otherwise allow.", "  Return Verdict.deny(\"x\").", "")));
    assertTrue(ex.getMessage().contains("Otherwise must be the last statement of its block"), ex.getMessage());
  }

  @Test void If块后的Otherwise换行块仍是else分支() {
    var core = lower(String.join("\n", "Module m.", "", "Rule r given a, produce Int:",
      "  If a at least 1:", "    Return 1.", "  Otherwise:", "    Return 2.", ""));
    // 断言首条语句为带 else 块的 If（按 CoreModel 的 Stmt/If 字段名读取）
  }

  @Test void Verdict成员调用不受新词影响() {
    var core = lower("Module m.\n\nRule r produce Verdict:\n  Return Verdict.allow().\n");
    assertNotNull(core);
  }

  @Test void 中文与德文语法糖同样降糖() {
    // 用 zh-CN / de-DE 词法包各写一条 When…拒绝/ablehnen 与 Otherwise 允许/erlauben，断言与英文长写法 IR 相等
  }
}
```

最后两个用例中注释处写出具体断言：读取首个 Func 的 body 第一条语句，断言其 `kind` 为 `If` 且 else 块非空；中文/德文用例使用该类测试中已有的多语言解析辅助（若无，照 `CanonicalizerTest` 的 lexicon 选择方式），源文为
zh：`模块 m。\n\n规则 r 给定 a 产出 Verdict：\n  当 a 至少 1，拒绝 "x"。\n  否则 允许。\n`；
de：`Modul m.\n\nRegel r gegeben a liefert Verdict:\n  bei a mindestens 1, ablehnen "x".\n  sonst erlauben.\n`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --offline test --tests '*GovernanceSugarParseTest*'`
Expected: 编译失败或断言失败。

- [ ] **Step 3: 语法**

```antlr
module
    : NEWLINE* moduleHeader? NEWLINE* profileDecl? NEWLINE* (topLevelDecl NEWLINE*)* EOF
    ;

// ADR 0046：Profile 声明。不引入硬词法记号——TYPE_IDENT 文本须为 Profile（AstBuilder 校验）
profileDecl
    : TYPE_IDENT STRING_LITERAL DOT
    ;
```

`stmt` 在 `ifStmt` 之前加入 `whenStmt | otherwiseStmt`：

```antlr
// ADR 0046：语句级 When 简写，降糖为 If + Return Verdict.*
whenStmt
    : WHEN expr COMMA sugarOutcome DOT
    ;

// ADR 0046：Otherwise 简写；与 ifStmt 的 else 分支区别在于其后直接是结论词（else 分支后须换行）
otherwiseStmt
    : ELSE sugarOutcome DOT
    ;

// 结论词经 Canonicalizer 规范化为英文后以软关键字出现；文本由 AstBuilder 校验
sugarOutcome
    : nameIdent+ (STRING_LITERAL (nameIdent+ STRING_LITERAL)?)?
    ;
```

记号名（`STRING_LITERAL`、`WHEN`、`ELSE`、`COMMA`、`DOT`）以 `AsterLexer.g4` 实际名称为准。

- [ ] **Step 4: AstBuilder**

- `visitModule`：若 `ctx.profileDecl()` 非空，取 TYPE_IDENT 文本，非 `Profile` 抛 `IllegalStateException("Expected 'Profile' declaration after Module header")`；字符串去引号后不匹配 `^[a-z][a-z0-9-]{0,63}$` 抛 `IllegalStateException("Profile id must match ^[a-z][a-z0-9-]{0,63}$: <id>")`；传入新 `Module(name, profile, decls, span)`。
- `visitWhenStmt`：构造 `Stmt.If(cond, singleStmtBlock(returnOf(outcome)), null, span)`；`visitOtherwiseStmt`：返回 `returnOf(outcome)`。`returnOf` 把 `sugarOutcome` 按词序列（小写拼接，单空格）映射：`allow` 无参 → `Verdict.allow`；`deny`/`escalate` + 1 个字符串；`require approval by` + 字符串 + `because` + 字符串 → `Verdict.require_approval(role, reason)`；其它组合抛 `IllegalStateException("Expected allow, deny, escalate or require approval by … because … after When/Otherwise")`。目标 Name 用与 `Verdict.deny(...)` 解析相同的构造（refs §2：`Expr.Name("Verdict.deny")` + `createCallExpression` 同款 `Expr.Call`），span 取 sugarOutcome 的 span。
- 块构建处（`visitBlock` 或其调用的语句列表组装处）：若某个 `otherwiseStmt` 不是块内最后一条语句，抛 `IllegalStateException("Otherwise must be the last statement of its block")`。

所有异常走既有的解析失败通道（与现有 AstBuilder 抛错一致）。

- [ ] **Step 5: AST / IR / lowering**

`ast.Module` 增 `@JsonProperty("profile") String profile`（第二个组件）；所有 `new Module(` 构造点补 `null`。`CoreModel.Module` 增 `@JsonInclude(JsonInclude.Include.NON_NULL) public String profile;`（中文注释：缺省不输出，保证未声明档案的模块 IR 逐字节不变）。`CoreLowering.lowerModule` 加 `module.profile = ast.profile();`。

- [ ] **Step 6: 运行**

Run: `./gradlew --offline test --tests '*GovernanceSugarParseTest*'` 然后全量 `./gradlew --offline test`。
Expected: 全部通过。

- [ ] **Step 7: 提交**

```bash
git add src/main/antlr src/main/java/aster/core src/test/java/aster/core/parser/GovernanceSugarParseTest.java
git commit -m "feat(parser): Profile 声明与 When/Otherwise 降糖（ADR 0046 §2/§5）"
```

---

### Task 4: ts 解析 Profile 声明与 When/Otherwise 降糖（aster-lang-ts）

**Files:**
- Modify: `src/parser/decl-parser.ts:674-690`（顶层循环在模块头之后识别 Profile）、`src/parser/import-parser.ts`（新增 `parseProfileDecl`）、解析上下文类型（增 `moduleProfile: string | null`）
- Modify: `src/parser/expr-stmt-parser.ts:288-405`（`parseStatement` 增 When/Otherwise 语法糖分支；If 的 else 分支加前瞻）
- Modify: `src/parser.ts:57`（`Node.Module(ctx.moduleName, ctx.moduleProfile, decls)`）
- Modify: `src/ast/ast.ts:12-17`、`src/types/base.ts:91-95`（`profile?: string`）、`src/core/core_ir.ts:10-14`（条件展开 `...(profile ? { profile } : {})`）、`src/lower_to_core.ts:262-266`
- Modify: `src/formatter.ts:477-480`（模块头后输出 `Profile "<id>".`）
- Test: `test/unit/parser/governance-sugar.test.ts`

**Interfaces:**
- Produces: `Node.Module(name, profile, decls)`；`Core.Module(name, decls, profile?)`（profile 缺省时对象不含该键）。Task 6 使用 `module.profile`。

- [ ] **Step 1: 失败测试**

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
// 沿用仓内既有的「源码 → Core」测试辅助（如 compile/lowerSource）；stripOrigins 递归删除 origin 键

test('Profile 声明进入 Core.Module.profile', () => {
  const core = toCore('Module m.\nProfile "governed".\n\nRule r produce Int:\n  Return 1.\n');
  assert.equal(core.profile, 'governed');
});

test('未声明档案时 Core.Module 不含 profile 键', () => {
  const core = toCore('Module m.\n\nRule r produce Int:\n  Return 1.\n');
  assert.equal('profile' in core, false);
});

test('小写 profile 参数名不受影响', () => {
  const core = toCore('Module m.\n\nRule r given profile, produce Int:\n  Return 1.\n');
  assert.equal('profile' in core, false);
});

test('档案 id 形态非法抛错', () => {
  assert.throws(() => toCore('Module m.\nProfile "Bad_Id".\n'), /Profile id must match/);
});

test('语法糖与长写法的 Core 去 origin 后相等', () => {
  const sugar = ['Module m.', '', 'Rule decide given amount, produce Verdict:',
    '  When amount at least 100, require approval by "Officer" because "large".',
    '  When amount at most 0, deny "negative".',
    '  When amount at least 50, escalate "review".',
    '  Otherwise allow.', ''].join('\n');
  const long = ['Module m.', '', 'Rule decide given amount, produce Verdict:',
    '  If amount at least 100:', '    Return Verdict.require_approval("Officer", "large").',
    '  If amount at most 0:', '    Return Verdict.deny("negative").',
    '  If amount at least 50:', '    Return Verdict.escalate("review").',
    '  Return Verdict.allow().', ''].join('\n');
  assert.deepEqual(stripOrigins(toCore(sugar)), stripOrigins(toCore(long)));
});

test('Otherwise 之后还有语句抛错', () => {
  assert.throws(() => toCore(['Module m.', '', 'Rule r produce Verdict:', '  Otherwise allow.',
    '  Return Verdict.deny("x").', ''].join('\n')), /Otherwise must be the last statement of its block/);
});

test('If 块后的 Otherwise 换行块仍是 else 分支，后接语法糖 Otherwise 不被吞', () => {
  const a = toCore(['Module m.', '', 'Rule r given a, produce Int:', '  If a at least 1:', '    Return 1.',
    '  Otherwise:', '    Return 2.', ''].join('\n'));
  // 断言首条语句是带 else 块的 If
  const b = toCore(['Module m.', '', 'Rule r given a, produce Verdict:', '  If a at least 1:',
    '    Return Verdict.deny("x").', '  Otherwise allow.', ''].join('\n'));
  // 断言 body 有两条语句：无 else 的 If 与 Return Verdict.allow()
});

test('中文与德文语法糖同样降糖', () => {
  // 以 ZH_CN / DE_DE 词法编译 Task 3 Step 1 同样的中、德文源文，断言与英文长写法去 origin 后相等
});

test('格式化器保留 Profile 行', () => {
  const out = format('Module m.\nProfile "governed".\n\nRule r produce Int:\n  Return 1.\n');
  assert.match(out, /^Module m\.\nProfile "governed"\./);
});
```

把注释处的断言写成具体代码（按 Core 语句的 `kind`、`thenBlock`、`elseBlock` 字段）。`toCore`、`format`、多语言编译辅助取自仓内既有测试的导入方式。

- [ ] **Step 2: 运行确认失败**

Run: `pnpm build`（预期编译或测试失败）。

- [ ] **Step 3: 实现**

- 顶层循环：模块头之后，若 `ctx.isKeyword(KW.PROFILE)`，调用 `parseProfileDecl(ctx, error, expectDot)`：吃掉关键字，期望字符串记号，校验形态（同 Task 3 消息），写 `ctx.moduleProfile`；重复声明或出现在其它位置报 `Profile must follow the Module header and appear at most once`。
- `parseStatement`：在 If 分支之前，`ctx.isKeyword(KW.WHEN)` → 解析 `expr`、期望逗号、`parseSugarOutcome`、期望句点，构造 `Node.If(cond, block([returnOf]), null)` 并按 `parseInlineIf` 的方式为合成节点赋 span（refs §0：未赋 span 会在 IR 中成为第 0 行）；`ctx.isKeyword(KW.OTHERWISE)` 且下一个记号是结论词（`KW.ALLOW`/`KW.DENY`/`KW.ESCALATE`/`KW.REQUIRE_APPROVAL_BY` 的首词）→ 解析结论与句点，返回 `Node.Return(call)`。
- If 解析 `expr-stmt-parser.ts:395`：仅当 `Otherwise`/`else` 之后是 `,`、`:` 或换行时才作为 else 分支，否则不消费（留给语法糖分支）。
- 块解析：Otherwise 语法糖不是块内最后一条语句时抛 `Otherwise must be the last statement of its block`。
- `parseSugarOutcome` 与 Task 3 相同的四种组合与错误消息；目标 Name 与 `Verdict.deny(...)` 正常解析得到的 Call/Name 形状一致。
- AST/Core/lowering/formatter 按 Files 所列修改。

- [ ] **Step 4: 运行**

Run: `pnpm build && node --test dist/test/unit/parser/governance-sugar.test.js`，再 `pnpm test`（全量）、`pnpm typecheck`、`pnpm lint`。

- [ ] **Step 5: 提交**

```bash
git add src test/unit/parser/governance-sugar.test.ts
git commit -m "feat(parser): Profile 声明与 When/Otherwise 降糖（ADR 0046 §2/§5）"
```

---

### Task 5: core 档案检查 E705/E706 与注册表 1.1.0 副本（aster-lang-core + aster-lang-ts 错误码）

**Files:**
- Modify: `aster-lang-ts/shared/error_codes.json`、`aster-lang-ts/src/diagnostics/error_codes.ts`（E705/E706，形状同 W704）
- Modify: `aster-lang-core/src/main/java/aster/core/typecheck/ErrorCode.java`、`src/test/resources/diagnostics/error_codes.json`（与 ts 字节一致）
- Modify: `aster-lang-core/src/main/resources/governance/controls-registry.json`（复制 Task 1 的 1.1.0）
- Modify: `aster-lang-core/src/main/java/aster/core/typecheck/governance/ControlRegistry.java`
- Modify: `aster-lang-core/src/main/java/aster/core/typecheck/governance/GovernanceChecker.java`
- Test: `aster-lang-core/src/test/java/aster/core/typecheck/governance/ProfileCheckTest.java`、`ControlRegistryTest`（追加）

**Interfaces:**
- Consumes: `CoreModel.Module.profile`（Task 3）。
- Produces: Java `ControlRegistry(String version, Set<String> keys, Map<String,String> frameworkOf, Map<String, Profile> profiles)`，`record Profile(String id, boolean ruleId, boolean registeredControls, List<String> frameworks)`，`Optional<Profile> profile(String id)`；`GovernanceChecker.checkModule` 产出 E705/E706。Task 7 使用。

- [ ] **Step 1: 错误码（两仓）**

json 条目：

```json
  "GOV_PROFILE_UNKNOWN": {
    "code": "E705",
    "category": "governance",
    "severity": "error",
    "message": "Module '{module}' declares unknown profile '{profile}' (controls registry {version})",
    "help": "Use a profile defined in aster-lang-locales controls/registry.json, such as governed or eu-ai-act-high-risk."
  },
  "GOV_PROFILE_VIOLATION": {
    "code": "E706",
    "category": "governance",
    "severity": "error",
    "message": "Rule '{rule}' violates profile '{profile}': {requirement}",
    "help": "Satisfy the profile requirement or remove the Profile declaration."
  }
```

TS 三处与 Java 枚举照 W704 的形状逐字复制；core 测试资源 `cp ../aster-lang-ts/shared/error_codes.json src/test/resources/diagnostics/error_codes.json`。两侧 parity 测试通过后各自提交（`feat(diagnostics): E705/E706 档案码（ADR 0046 §4）`）。

- [ ] **Step 2: 失败测试**

```java
/** ADR 0046 §4：档案检查。 */
class ProfileCheckTest {
  @Test void 未知档案报E705() {
    var diags = GovernanceTestSupport.diagnoseSource("Module m.\nProfile \"nope\".\n\nRule r produce Int:\n  Return 1.\n");
    assertEquals(1, GovernanceTestSupport.count(diags, ErrorCode.GOV_PROFILE_UNKNOWN), diags.toString());
  }
  @Test void 缺id报E706且W700照常() {
    var diags = diagnoseSource(profiled("governed", "Rule r produce Verdict:\n  Return Verdict.allow()."));
    assertEquals(1, count(diags, ErrorCode.GOV_PROFILE_VIOLATION));
    assertEquals(1, count(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID));
  }
  @Test void 未登记控制点报E706() {
    var diags = diagnoseSource(profiled("governed",
      "@id(\"R-1\")\n@control(\"ACME:ART1\")\nRule r produce Verdict:\n  Return Verdict.allow()."));
    assertTrue(messages(diags, ErrorCode.GOV_PROFILE_VIOLATION).contains("unregistered control ACME:ART1"));
  }
  @Test void 缺所需框架控制点报E706() {
    var diags = diagnoseSource(profiled("eu-ai-act-high-risk",
      "@id(\"R-1\")\n@control(\"GDPR:ART6\")\nRule r produce Verdict:\n  Return Verdict.allow()."));
    assertTrue(messages(diags, ErrorCode.GOV_PROFILE_VIOLATION).contains("no registered control from framework EU_AI_ACT"));
  }
  @Test void 合规规则零E70x() {
    var diags = diagnoseSource(profiled("eu-ai-act-high-risk",
      "@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\nRule r produce Verdict:\n  Return Verdict.allow()."));
    assertEquals(0, count(diags, ErrorCode.GOV_PROFILE_VIOLATION) + count(diags, ErrorCode.GOV_PROFILE_UNKNOWN));
  }
  @Test void 非Verdict规则不受档案约束() {
    var diags = diagnoseSource(profiled("governed", "Rule helper produce Int:\n  Return 1."));
    assertEquals(0, count(diags, ErrorCode.GOV_PROFILE_VIOLATION));
  }
  @Test void 未声明档案的模块零E70x() {
    var diags = diagnoseSource("Module m.\n\nRule r produce Verdict:\n  Return Verdict.allow().\n");
    assertEquals(0, count(diags, ErrorCode.GOV_PROFILE_VIOLATION) + count(diags, ErrorCode.GOV_PROFILE_UNKNOWN));
  }
}
```

在 `GovernanceTestSupport` 增 `diagnoseSource(String fullSource)`（不再自动加 `Module probe.` 前缀；原 `diagnose(body)` 改为调用它）、`profiled(id, body)`（返回 `"Module probe.\nProfile \"" + id + "\".\n\n" + body + "\n"`）、`messages(diags, code)`（拼接该码所有消息）。`ControlRegistryTest` 追加：默认注册表 `version()` 为 `1.1.0`，`profile("governed")` 存在且 `frameworks` 为空，`frameworkOf().get("EU_AI_ACT:ART14")` 为 `EU_AI_ACT`。

- [ ] **Step 3: 运行确认失败**（`./gradlew --offline test --tests '*ProfileCheckTest*' --tests '*ControlRegistryTest*'`）

- [ ] **Step 4: 注册表副本与 ControlRegistry**

复制 Task 1 的 `registry.json` 到 classpath 副本（`ControlRegistrySiblingParityTest` 守护）。`ControlRegistry` 记录增 `Map<String,String> frameworkOf` 与 `Map<String, Profile> profiles`（canonical 构造器 `Map.copyOf`），`fromJson` 读取 `controls[].framework` 与 `profiles`（缺 `profiles` 视为空，以兼容只含控制点的输入）；新增：

```java
  /** 档案定义（ADR 0046 §3）。 */
  public record Profile(String id, boolean ruleId, boolean registeredControls, List<String> frameworks) {
    public Profile { frameworks = List.copyOf(frameworks); }
  }

  public Optional<Profile> profile(String id) {
    return Optional.ofNullable(profiles.get(id));
  }
```

保留两参构造器 `ControlRegistry(String version, Set<String> keys)`（委托四参，后两者为空 Map），既有调用点与测试不变。

- [ ] **Step 5: 检查器**

`checkModule` 开头：

```java
    // ADR 0046：声明了档案的模块按档案要求检查；未知档案报 E705 后不再做档案检查
    Optional<ControlRegistry.Profile> profile = Optional.empty();
    if (module.profile != null) {
      profile = registry.profile(module.profile);
      if (profile.isEmpty()) {
        out.add(diag(ErrorCode.GOV_PROFILE_UNKNOWN, module.origin,
            Map.of("module", String.valueOf(module.name), "profile", module.profile, "version", registry.version())));
      }
    }
```

`checkFunc` 增参数 `Optional<ControlRegistry.Profile> profile`，在末尾调用：

```java
  /** ADR 0046 §4：只对 Verdict 规则检查档案要求；同一规则同一要求只报一次。 */
  private void checkProfile(CoreModel.Func func, ControlRegistry.Profile p, List<Diagnostic> out) {
    if (!returnsVerdict(func)) return;
    String rule = String.valueOf(func.name);
    if (p.ruleId() && ruleId(func).isEmpty()) out.add(violation(rule, p, "missing @id"));
    List<String> keys = List.copyOf(new LinkedHashSet<>(controls(func)));
    if (p.registeredControls()) {
      keys.stream().filter(k -> !registry.has(k))
          .forEach(k -> out.add(violation(rule, p, "unregistered control " + k)));
    }
    if (!p.frameworks().isEmpty() && keys.stream().noneMatch(k -> registry.has(k)
        && p.frameworks().contains(registry.frameworkOf().get(k)))) {
      out.add(violation(rule, p, "no registered control from framework " + String.join(", ", p.frameworks())));
    }
  }

  private Diagnostic violation(String rule, ControlRegistry.Profile p, String requirement) {
    return diag(ErrorCode.GOV_PROFILE_VIOLATION, null,
        Map.of("rule", rule, "profile", p.id(), "requirement", requirement));
  }
```

`violation` 的 origin 参数改为传入 `func.origin`（为此把 `func.origin` 作为参数传入 `violation`）。

- [ ] **Step 6: 运行**：上述测试 + 全量 `./gradlew --offline test`。

- [ ] **Step 7: 提交**

```bash
git add src/main/resources/governance src/main/java/aster/core/typecheck/governance src/test/java/aster/core/typecheck/governance
git commit -m "feat(governance): 档案检查 E705/E706 与注册表 1.1.0（ADR 0046 §3/§4）"
```

---

### Task 6: ts 档案检查与注册表 1.1.0（aster-lang-ts + aster-lang-test）

**Files:**
- Modify: `src/governance/controls-registry.data.ts`（类型增 `profiles`，数据同步 1.1.0）
- Modify: `src/governance/controls.ts`（`ControlRegistry` 增 `frameworkOf(key)`、`profile(id)`）
- Modify: `src/typecheck/governance.ts`（`checkGovernance` 签名改为接收模块：`checkGovernance(module: { name: string | null; profile?: string; decls: readonly Core.Declaration[]; origin?: Origin }, opts)`）
- Modify: `src/typecheck/module.ts:137`、`src/typecheck/browser.ts:324`（传入模块）
- Test: `test/unit/governance/profile-check.test.ts`
- aster-lang-test: Create `corpus/tier1-equivalence/policies/governance-sugar.aster` + `.meta.json`、`corpus/tier1-equivalence/policies/governance-sugar-long.aster` + `.meta.json`、对应 `inputs/*.cases.json`（经 `scripts/gen-cases.mjs`），并登记到 `corpus/tier1-parity/manifest.json`

**Interfaces:**
- Consumes: Task 4 的 `Core.Module.profile`、Task 5 的错误码与注册表数据。
- Produces: TS `ControlRegistry { version; has(key); frameworkOf(key): string | undefined; profile(id): ProfileDef | undefined }`，`ProfileDef { id; ruleId: boolean; registeredControls: boolean; frameworks: readonly string[] }`。

- [ ] **Step 1: 失败测试**：与 Task 5 Step 2 同一组七个场景的 TS 版本（用 `typecheckModule` 源码驱动），断言码与消息子串逐字一致；再加「注入 `controlRegistryFrom` 自定义档案数据」一例。

- [ ] **Step 2: 实现**：`controlRegistryFrom` 构建 `frameworkOf` 与 `profile` 查找；`checkGovernance` 的模块级 E705 定位用模块 origin（无则空 span），`checkProfile` 逻辑与 Task 5 Step 5 逐条对应（消息参数同名同值）；`module.ts`/`browser.ts` 传入 `m`。注入的注册表仍经 `isWellFormedControlKey` 把关（ADR 0045 已有约束保持）。

- [ ] **Step 3: 数据副本**：`controls-registry.data.ts` 粘贴 Task 1 的完整 1.1.0 数据；`controls-registry-parity.test.ts` 通过。

- [ ] **Step 4: parity 语料**

`governance-sugar.aster`（语法糖）与 `governance-sugar-long.aster`（长写法）为同一策略（模块名分别为 `governance.sugar` 与 `governance.sugar.long`），内容取 Task 3 Step 1 的 `decide` 规则并加 `Profile "governed".`、`@id("GS-1")`、`@control("EU_AI_ACT:ART14")`；meta 照 `credit-pilot.meta.json`；cases 覆盖四种结论（amount 150 / -1 / 60 / 10）。运行 `node scripts/gen-cases.mjs <spec> --write` 与 `node scripts/parity-tier1.mjs --mode=parse`、`--mode=ir`、`--mode=eval`（Node 24），全部一致。

- [ ] **Step 5: 运行与提交**：`pnpm test`、`pnpm typecheck`、`pnpm lint`；各仓提交（ts：`feat(governance): 档案检查 E705/E706 与注册表 1.1.0（ADR 0046 §4）`；test：`corpus: 语法糖与长写法成对样本（ADR 0046 §5）`）。

---

### Task 7: aster-api 编译通道与评估证据（aster-api）

**Files:**
- Modify: `src/main/java/io/aster/policy/compiler/GovernanceDiagnostics.java:49-59`（E705/E706 保留 error）
- Modify: `src/main/java/io/aster/policy/rest/model/CompileResponse.java`（增 `profile`，NON_NULL）与 `PolicyEvaluationResource.toCompileResponse`（从 coreJson 读 `profile`）
- Modify: `replay/src/main/java/io/aster/policy/governance/RuleMetadata.java`（增 `profile`）
- Modify: `src/main/java/io/aster/policy/api/PolicyEvaluationService.java:225-230`、`PolicyEvaluationResult`、`replay/.../DynamicCnlExecutor.ExecutionResult`（两处构造）、`replay/.../ReplayExecutorResult` 与 `ReplayExecutorAdapter`
- Modify: `src/main/java/io/aster/policy/evidence/EvaluationEvidence.java`（增 `profile` 与 `toMetadata` 键 `profile`）、`PolicyEvaluationResource` 三处 `EvaluationEvidence.of`（254/370/642）
- Modify: `src/main/java/io/aster/policy/rest/model/EvaluationResponse.java`（增 `profile`，NON_NULL）
- Test: `src/test/java/io/aster/policy/compiler/PolicyCompilerGovernanceTest.java`（追加）、`PolicyCompileResourceTest`（追加）、评估资源测试（找到断言 `ruleId`/`controls` 的测试类追加）

**Interfaces:**
- Consumes: core `CoreModel.Module.profile`、E705/E706（经 composite build）。
- Produces: 编译响应 `profile`；评估响应 `profile`；评估证据元数据键 `profile`。Task 8 使用评估响应 `profile`。

- [ ] **Step 1: 失败测试**

- 编译：`Module m.\nProfile "nope".\n…` → 诊断含 `E705` 且 `severity == "error"`、`success == true`；`Profile "governed".` + 缺 `@id` 的 Verdict 规则 → `E706` error、`W700` warning；不声明档案 → 无 error 级诊断（保留 ADR 0045 的 warning 行为）；响应 `profile == "governed"`。
- 资源层：`POST /api/v1/policies/compile` 上述两例；`profile` 字段只在声明时出现。
- 评估：用声明了 `Profile "governed".` 的合规策略走 evaluate-source，响应 `profile == "governed"`；审计链上的评估事件 metadata 含 `profile`；未声明档案的策略响应与 metadata 无该键。

- [ ] **Step 2: 实现**

`GovernanceDiagnostics.toCompilationDiagnostic`：

```java
        // ADR 0046：档案违规（E705/E706）是模块作者自愿声明的约束，须阻止保存，保留 error；
        // 其余治理码按 ADR 0045 在编译通道恒为 warning
        String severity = isProfileCode(d.code()) ? "error" : "warning";
```

`isProfileCode` 判断 `ErrorCode.GOV_PROFILE_UNKNOWN` / `GOV_PROFILE_VIOLATION`；`blocking` 仍为 false（保存由 cloud 按 severity 拦截）。`RuleMetadata` 增第三组件 `String profile`，`of(module, fn)` 取 `module.profile`，`NONE` 的 profile 为 null；沿 Files 所列逐层透传到三个 `EvaluationEvidence.of` 与 `EvaluationResponse.success`。`EvaluationEvidence.toMetadata` 用既有 `put(m, "profile", clip(profile))`。

- [ ] **Step 3: 运行**：新增与追加的测试类 + `'io.aster.policy.compiler.*' '*PolicyCompileResourceTest*' 'io.aster.audit.*' 'io.aster.api.PublicApiContractTest' 'io.aster.api.workflow.NonDeterminismSourceTest'` 与 replay 模块测试（`:replay:test`）。

- [ ] **Step 4: 提交**

```bash
git add src replay
git commit -m "feat(governance): 编译通道档案违规为 error，档案沿评估证据传播（ADR 0046 §4/§6）"
```

---

### Task 8: aster-cloud 执行列、证据包 v5 与界面（aster-cloud）

**Files:**
- Create: `drizzle/0053_execution_profile.sql`（`ALTER TABLE "Execution" ADD COLUMN IF NOT EXISTS "profile" text;`，并更新 `drizzle/meta/_journal.json`，照 0050/0052 的格式）
- Modify: `src/db/schema.ts:900-908`（`profile: text('profile')`）
- Modify: `src/services/policy/policy-api.ts:116-119`（评估响应 `profile?: string`；编译响应 `profile?: string`）、`src/services/policy/cnl-executor.ts:165-168,526-532`、`src/lib/policy-execution-log.ts:282-292`
- Modify: `src/lib/evidence-export.ts:97-131`、`src/services/evidence/bundle.ts`、`src/services/evidence/types.ts`（v4 冻结为 `EvidenceManifestV4`/`EvidenceBundleV4`/`EvidenceEntryV4`，现行 v5）、`src/services/evidence/stored.ts`、`src/app/api/reports/[id]/mapping/route.ts:26`、`src/app/[locale]/(dashboard)/reports/reports-content.tsx:379-384`（`'4'` 与 `'5'` 都可展开对照）
- Modify: `src/config/controls-registry.json`、`src/services/evidence/control-registry.ts`（类型增 `profiles`）
- Modify: `src/components/evidence/regulatory-mapping-table.tsx`（对照视图顶部列出档案，标签按 locale）、策略详情页 `src/app/[locale]/(dashboard)/policies/[id]/policy-detail-content.tsx`（编译响应带 `profile` 时显示徽标）
- Modify: `src/i18n/demo-supplement.ts`（en/zh/de/hi：`evidenceExport.mapping.profiles`、`policies.detail.profile`）
- Test: `src/__tests__/services/evidence/bundle.test.ts`、`stored.test.ts`、`control-registry-*.test.ts`（追加）、`src/__tests__/lib/policy-execution-log.test.ts`（或现有 buildEvidenceColumns 测试，追加）、`src/__tests__/services/policy/version-manager*.test.ts`（追加）、`src/__tests__/components/regulatory-mapping-table.test.tsx`（追加）

**Interfaces:**
- Consumes: 评估响应 `profile`（Task 7）。
- Produces: `EvidenceEntry.profile: string | null`；`EvidenceManifest.schemaVersion: '5'`、`profilesUsed: Array<{ id: string; title: LocalizedTitle }>`。

- [ ] **Step 1: 失败测试**

- `buildEvidenceColumns({ profile: 'governed' }, null).profile === 'governed'`；缺省为 null。
- `buildManifest` 输出 `schemaVersion '5'`；条目 `profile` 为 `governed` 与 `eu-ai-act-high-risk` 各一时，`profilesUsed` 恰为这两项（按注册表顺序），标题取自注册表；全部条目 `profile` 为 null 时 `profilesUsed` 为 `[]`；条目引用注册表中不存在的档案 id 时该项 `title` 三语均为该 id。
- `stored.test`：v1–v5 可读，`'6'` 不可读；`summarizeManifest` 有 `case '5'`。
- 映射路由：v4 与 v5 包均返回 mapping；v3 返回 null。
- 版本管理：校验器返回 E705（severity error）→ `assertCompilable` 抛 `PolicyCompileError`；只含 W700（warning）→ 不抛（既有行为回归）。
- 组件：mapping 带 `profilesUsed` 时显示档案标题（zh 下显示中文标题）。

- [ ] **Step 2: 实现**：按 Files 逐项；`profilesUsed` 由 `bundle.ts` 从排序后的条目收集去重 id，再按注册表 `profiles` 顺序（未知 id 排最后，按字母序）映射标题。注册表副本粘贴 Task 1 的 1.1.0，parity 与来源校验测试通过。

- [ ] **Step 3: 文案（四语，hi 用英文）**：`evidenceExport.mapping.profiles`：en `Profiles`、zh `治理档案`、de `Profile`；`policies.detail.profile`：en `Profile`、zh `治理档案`、de `Profil`。`check:message-keys` 与 `check:locales:strict` 通过。

- [ ] **Step 4: 运行**：`pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm exec vitest run src/__tests__/services src/__tests__/lib src/__tests__/components src/__tests__/api && pnpm check:locales:strict && pnpm check:message-keys && pnpm routes:check`；集成测试 `LICENSE_E2E=1 DATABASE_URL=postgresql://postgres:postgres@localhost:55433/aster_cloud pnpm exec vitest run --config vitest.integration.config.ts evidence-export`（先对 sdd-pg 的 aster_cloud 跑 `pnpm db:migrate`）。

- [ ] **Step 5: 提交**

```bash
git add drizzle src
git commit -m "feat(governance): 执行记录档案、证据包 v5 与档案展示（ADR 0046 §6）"
```

---

### Task 9: 信贷试点 v3、脚本与指南（aster-cloud）

**Files:**
- Modify: `src/config/credit-pilot-source.ts`（新增 `creditPilotSugarSource(locale)`：v1 五个分支逐条用语法糖改写并声明 `Profile "eu-ai-act-high-risk".`，阈值 50000；`CREDIT_PILOT.versionIds` 增 `'pv-credit-pilot-3'`）
- Modify: `src/scripts/seed-credit-pilot.ts`（v3 为在线默认版本：`Policy.content` = v3 en 源、`version: 3`；v1、v2 保持 APPROVED 且 `isDefault: false`）
- Modify: `scripts/run-credit-pilot.ts`（What-If 仍取 `versionIds[0]`/`[1]`）
- Modify: `src/__tests__/credit-pilot-source.compile.test.ts`（v3 三语四种结论与 v1 一致；引擎无 Verdict/语法糖支持时随既有探测跳过）
- Modify: `src/__tests__/e2e-browser/local-stack/credit-pilot.spec.ts`（断言 `schemaVersion '5'`、`profilesUsed` 含 `eu-ai-act-high-risk`）
- Modify: `src/app/[locale]/docs/guides/credit-pilot/{en,zh,de}.mdx`（新增一节：档案声明与语法糖写法、违规无法保存；附 v3 源文）

**Interfaces:**
- Consumes: Task 3/4 的语法、Task 7/8 的证据字段。

- [ ] **Step 1: 源文**

en（阈值 `${threshold}m`，五个分支与 v1 同序同边界）：

```
Module aster.pilot.credit.
Profile "eu-ai-act-high-risk".

Define Applicant has
  creditScore as Int,
  monthlyIncome as Decimal,
  monthlyDebt as Decimal,
  requestedAmount as Decimal,
  activeDefaults as Int.

@id("CP-DECIDE")
@control("EU_AI_ACT:ART14")
@control("GDPR:ART22")
Rule decide given applicant as Applicant, produce Verdict:
  When applicant.activeDefaults at least 1, deny "active_default".
  When applicant.monthlyDebt greater than applicant.monthlyIncome times 0.45m, deny "dti_exceeds_limit".
  When applicant.requestedAmount at least ${threshold}m, require approval by "Credit Officer" because "large_exposure".
  When applicant.creditScore at least 620 and applicant.creditScore at most 679, escalate "borderline_credit_score".
  Otherwise allow.
```

zh/de 版本以 Global Constraints 用词改写，标识符保持 ASCII（同 v1 的约定）。

- [ ] **Step 2: 测试**：编译测试对 v3 三语断言四种结论与 v1 相同；`run-credit-pilot` 的期望表不变；`check:docs-index`、`check:docs-descriptions` 通过。

- [ ] **Step 3: 提交**

```bash
git add src scripts
git commit -m "feat(pilot): 信贷试点 v3 以语法糖书写并声明 eu-ai-act-high-risk 档案（ADR 0046 §6）"
```

---

### Task 10: 全栈验收与 ADR 定稿

- [ ] **Step 1**：重建 aster-api 镜像并重建容器（命令见记忆文件 aster-local-verification-env 的 Credit pilot 配方）；对 aster_cloud 运行 `pnpm db:migrate`（容器内）；重启 aster-cloud；重新播种 `seed-credit-pilot`。
- [ ] **Step 2**：编译端点：`Profile "nope".` → E705 error；`Profile "governed".` + 缺 `@id` → E706 error + W700 warning；合规模块无 error。
- [ ] **Step 3**：经 aster-cloud 保存一个违规的档案化模块 → 400；未声明档案的同一模块 → 保存成功。
- [ ] **Step 4**：`run-credit-pilot.ts` exit 0：九款与期望一致，证据包 `schemaVersion '5'`，`profilesUsed` 含 `eu-ai-act-high-risk`，条目 `profile` 为该 id。
- [ ] **Step 5**：Playwright 本地栈全套通过（既有 skip 除外）。
- [ ] **Step 6**：全量门禁：locales/hi `check`、core 全量、ts `pnpm test`、aster-lang-test parity（parse/ir/eval）、api 全量（对照已知基线）、cloud 完整门禁。
- [ ] **Step 7**：ADR 0046 状态改 ACCEPTED，§9 写入最终用词、冲突扫描结论与验收记录；ADR 0039 §1 行 6 写「6a → ADR 0046；6b 待定」。提交：`docs(adr): ADR 0046 ACCEPTED，0039 回写`。
- [ ] **Step 8**：更新记忆文件（路线图状态、`~/.m2` 中本地构建的 1.0.30 词法包、发版待办）。
