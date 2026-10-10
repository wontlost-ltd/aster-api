# ADR 0045 控制注册表 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `@control` 成为受控词表：注册表在 aster-lang-locales 构建，两个编译器对未登记键报 W704，aster-api 编译端点带出治理诊断，aster-cloud 证据包对照表由注册表驱动并在报告页渲染。

**Architecture:** 真相源 `aster-lang-locales/controls/registry.json`（Gradle `exportControls` 校验后产出 npm 包目录）。发布前 aster-lang-core（classpath JSON）、aster-lang-ts（TS 数据模块）、aster-cloud（`src/config/controls-registry.json`）各持深度相等的副本并有守护测试。core 的 `GovernanceChecker` 与 ts 的 `checkGovernance` 注入 `ControlRegistry`；aster-api 的 `PolicyCompiler` 新增治理扫描；cloud 的 `regulatory-mapping.ts` 按条目声明的证据来源通用判定，manifest 升 `'4'`。

**Tech Stack:** Gradle Kotlin DSL + Groovy JsonSlurper；TypeScript（node --test）；Java 21 + JUnit 5；Quarkus；Next.js 16 + vitest + Playwright。

**Spec:** `docs/adr/0045-control-registry.md`。代码摘录：`/private/tmp/aster-stack/adr0045-refs.md`（下文「refs §n」）。

## Global Constraints

- 注释一律简体中文、只写意图；禁止「修改说明」式注释；缩进 ≤ 3 层；沿用既有 import 顺序；提交 trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`；不推送；aster-cloud 不提交 AGENTS.md/CLAUDE.md。
- 注册表 `version` 逐字 `"1.0.0"`；控制键正则逐字 `^[A-Z0-9_]+:[A-Z0-9_().]+$`，长度 ≤ 64。
- 初始 controls 逐字：`EU_AI_ACT:ART14`、`GDPR:ART6`、`GDPR:ART17`、`GDPR:ART22`、`SOX:404`；frameworks：`EU_AI_ACT`、`GDPR`、`SOX`。
- 证据来源词表逐字：`control`、`outcome.pending`、`outcome.requireApproval`、`reviewers.any`、`reviewers.guard.any`、`reviewers.guard.approved`、`reviewers.guard.rejected`、`reviewers.guard.two`、`whatIf.comparable`、`rule.reason`。
- 判定逐字：`evidenced` 非空且其中每个来源都至少命中一条 entry → evidenced；否则 `partial` 中任一来源命中 → partial；否则 none。证据 = 命中来源引用并集，按 (来源在列表中的顺序, entry 顺序) 排列并去重。
- 错误码逐字：`GOV_CONTROL_UNREGISTERED`，code `W704`，category `governance`，severity `warning`，message `Control '{control}' on rule '{rule}' is not registered (controls registry {version})`，help `Use a registered key such as EU_AI_ACT:ART14, or add it to aster-lang-locales controls/registry.json (format FRAMEWORK:ARTICLE, at most 64 characters).`
- W704 每条规则每个不同键至多一次；永不阻断编译；定位在规则（函数）范围。
- 证据包 `schemaVersion: '4'`；现行 v3 类型复制为 `*V3` 冻结；`bundleHash` 规则不变。
- 测试命令：api `DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380 ./gradlew --offline :test --rerun --tests '...'`；core `./gradlew --offline test --tests '...'`；ts `pnpm build && node --test dist/test/<path>.test.js`（以仓内既有脚本为准）；cloud `export PATH=/opt/homebrew/opt/node@24/bin:$PATH; pnpm exec vitest run <path>`。绝不触碰 `aster-*` 容器（验收任务除外）。

## Review Focus

1. 注册表 JSON 里某条 clause 的 `control` 指向不存在的控制键 → `exportControls` 必须失败并指出该 clause —— Task 1 负向用例。
2. `@control` 写成小写或带空格（`eu_ai_act:art14`）→ 两个编译器都报 W704（形态非法也算未登记），不抛异常 —— Task 3、Task 4 用例。
3. 同一规则重复写同一个未登记键 → 只报一次 W704 —— Task 3、Task 4 用例。
4. 证据包里所有条目都不含 `EU_AI_ACT:ART14`（例如只用 GDPR 控制的策略）→ 对照表不出现 Article 14 组，不报错；entries 为空 → 所有带条款的组全部 none —— Task 7 用例。
5. 报告页打开 v1–v3 的历史导出 → 显示「此版本不含对照」，`/api/reports/{id}/mapping` 返回 `{ mapping: null }` 而非 404/500 —— Task 8 用例。

---

### Task 1: 注册表真相源与 `exportControls`（aster-lang-locales）

**Files:**
- Create: `controls/registry.json`
- Create: `controls/package.json`
- Create: `controls/README.md`
- Create: `controls/.gitignore`
- Create: `controls/src/test-fixtures/bad-unknown-control.json`
- Modify: `build.gradle.kts`（新增 `exportControls` 任务并挂到 `check`）

**Interfaces:**
- Produces: `controls/registry.json`（Task 3/4/6 的副本深度相等于它）；`./gradlew exportControls` 输出 `build/controls/registry.json`；`-PcontrolsSource=<path>` 可指定输入（负向测试用）。

- [ ] **Step 1: 写注册表**

```json
{
  "version": "1.0.0",
  "frameworks": [
    { "id": "EU_AI_ACT", "title": { "en": "EU AI Act", "zh": "欧盟人工智能法", "de": "EU-KI-Verordnung" } },
    { "id": "GDPR", "title": { "en": "General Data Protection Regulation", "zh": "通用数据保护条例", "de": "Datenschutz-Grundverordnung" } },
    { "id": "SOX", "title": { "en": "Sarbanes-Oxley Act", "zh": "萨班斯-奥克斯利法案", "de": "Sarbanes-Oxley Act" } }
  ],
  "controls": [
    { "key": "EU_AI_ACT:ART14", "framework": "EU_AI_ACT", "article": "14", "title": { "en": "Human oversight", "zh": "人工监督", "de": "Menschliche Aufsicht" } },
    { "key": "GDPR:ART6", "framework": "GDPR", "article": "6", "title": { "en": "Lawfulness of processing", "zh": "处理的合法性", "de": "Rechtmäßigkeit der Verarbeitung" } },
    { "key": "GDPR:ART17", "framework": "GDPR", "article": "17", "title": { "en": "Right to erasure", "zh": "删除权", "de": "Recht auf Löschung" } },
    { "key": "GDPR:ART22", "framework": "GDPR", "article": "22", "title": { "en": "Automated individual decision-making", "zh": "自动化个人决策", "de": "Automatisierte Entscheidungen im Einzelfall" } },
    { "key": "SOX:404", "framework": "SOX", "article": "404", "title": { "en": "Internal control over financial reporting", "zh": "财务报告内部控制", "de": "Interne Kontrolle der Finanzberichterstattung" } }
  ],
  "clauses": [
    { "control": "EU_AI_ACT:ART14", "clause": "14(1)", "title": { "en": "Designed for effective human oversight", "zh": "设计上可被有效人工监督", "de": "Auf wirksame menschliche Aufsicht ausgelegt" }, "evidenced": ["control"], "partial": [] },
    { "control": "EU_AI_ACT:ART14", "clause": "14(2)", "title": { "en": "Oversight proportionate to risk", "zh": "监督与风险相称", "de": "Risikoangemessene Aufsicht" }, "evidenced": ["outcome.pending"], "partial": [] },
    { "control": "EU_AI_ACT:ART14", "clause": "14(3)", "title": { "en": "Oversight measures built in", "zh": "内建监督措施", "de": "Eingebaute Aufsichtsmaßnahmen" }, "evidenced": ["control", "reviewers.any"], "partial": ["control", "reviewers.any"] },
    { "control": "EU_AI_ACT:ART14", "clause": "14(4)(a)", "title": { "en": "Overseer can understand capacities and limitations", "zh": "监督者理解系统能力与局限", "de": "Verständnis von Fähigkeiten und Grenzen" }, "evidenced": [], "partial": ["whatIf.comparable"] },
    { "control": "EU_AI_ACT:ART14", "clause": "14(4)(b)", "title": { "en": "Awareness of automation bias", "zh": "意识到自动化偏见", "de": "Bewusstsein für Automatisierungsbias" }, "evidenced": [], "partial": [] },
    { "control": "EU_AI_ACT:ART14", "clause": "14(4)(c)", "title": { "en": "Correctly interpret the output", "zh": "正确解读输出", "de": "Ausgabe korrekt interpretieren" }, "evidenced": ["rule.reason"], "partial": [] },
    { "control": "EU_AI_ACT:ART14", "clause": "14(4)(d)", "title": { "en": "Decide not to use or override the output", "zh": "决定不使用或推翻输出", "de": "Ausgabe nicht verwenden oder übersteuern" }, "evidenced": ["reviewers.guard.rejected"], "partial": ["reviewers.guard.approved"] },
    { "control": "EU_AI_ACT:ART14", "clause": "14(4)(e)", "title": { "en": "Intervene or halt the system", "zh": "干预或停止系统", "de": "Eingreifen oder System anhalten" }, "evidenced": ["reviewers.guard.any"], "partial": ["outcome.requireApproval"] },
    { "control": "EU_AI_ACT:ART14", "clause": "14(5)", "title": { "en": "Verification by at least two persons", "zh": "至少两人核实", "de": "Überprüfung durch mindestens zwei Personen" }, "evidenced": ["reviewers.guard.two"], "partial": [] }
  ]
}
```

- [ ] **Step 2: 负向夹具**

`controls/src/test-fixtures/bad-unknown-control.json`：与 Step 1 相同结构，`controls` 只保留 `EU_AI_ACT:ART14`，`clauses` 只保留一条 `{"control":"GDPR:ART99","clause":"99(1)","title":{"en":"x","zh":"x","de":"x"},"evidenced":["control"],"partial":[]}`。

- [ ] **Step 3: 在根 `build.gradle.kts` 末尾加任务**

```kotlin
// ADR 0045：控制注册表校验与导出。输入可用 -PcontrolsSource 覆盖（负向测试用）。
val controlsSource: java.io.File = (findProperty("controlsSource") as String?)
    ?.let { rootProject.file(it) } ?: rootProject.file("controls/registry.json")

val exportControls by tasks.registering {
    group = "aster"
    description = "校验并导出控制注册表（ADR 0045）"
    inputs.file(controlsSource)
    val outDir = layout.buildDirectory.dir("controls")
    outputs.dir(outDir)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val reg = groovy.json.JsonSlurper().parse(controlsSource) as Map<String, Any?>
        val problems = validateControlsRegistry(reg)
        require(problems.isEmpty()) { "控制注册表不合法（${controlsSource}）：\n" + problems.joinToString("\n") }
        val out = outDir.get().asFile
        out.mkdirs()
        controlsSource.copyTo(out.resolve("registry.json"), overwrite = true)
        logger.lifecycle("controls registry ${reg["version"]} 已导出")
    }
}

val CONTROL_KEY = Regex("^[A-Z0-9_]+:[A-Z0-9_().]+$")
val EVIDENCE_SOURCES = setOf(
    "control", "outcome.pending", "outcome.requireApproval", "reviewers.any", "reviewers.guard.any",
    "reviewers.guard.approved", "reviewers.guard.rejected", "reviewers.guard.two", "whatIf.comparable", "rule.reason",
)
val LOCALES = listOf("en", "zh", "de")

/** 逐项检查注册表；返回问题列表（空 = 合法）。 */
fun validateControlsRegistry(reg: Map<String, Any?>): List<String> {
    val problems = mutableListOf<String>()
    if ((reg["version"] as? String).isNullOrBlank()) problems += "缺 version"
    @Suppress("UNCHECKED_CAST") val frameworks = reg["frameworks"] as? List<Map<String, Any?>> ?: emptyList()
    @Suppress("UNCHECKED_CAST") val controls = reg["controls"] as? List<Map<String, Any?>> ?: emptyList()
    @Suppress("UNCHECKED_CAST") val clauses = reg["clauses"] as? List<Map<String, Any?>> ?: emptyList()
    val frameworkIds = frameworks.map { it["id"] as? String }.toSet()
    val keys = mutableSetOf<String>()
    for (c in controls) {
        val key = c["key"] as? String ?: "".also { problems += "控制点缺 key: $c" }
        if (!CONTROL_KEY.matches(key) || key.length > 64) problems += "控制键形态非法: $key"
        if (!keys.add(key)) problems += "控制键重复: $key"
        if (c["framework"] !in frameworkIds) problems += "控制键 $key 的 framework 未登记: ${c["framework"]}"
        problems += missingTitles("控制键 $key", c["title"])
    }
    for (cl in clauses) {
        val label = "条款 ${cl["clause"]}"
        if (cl["control"] !in keys) problems += "$label 引用未登记控制键: ${cl["control"]}"
        problems += missingTitles(label, cl["title"])
        for (field in listOf("evidenced", "partial")) {
            @Suppress("UNCHECKED_CAST") val list = cl[field] as? List<String>
            if (list == null) { problems += "$label 缺 $field 数组"; continue }
            list.filter { it !in EVIDENCE_SOURCES }.forEach { problems += "$label 的 $field 含未知证据来源: $it" }
        }
    }
    return problems
}

fun missingTitles(label: String, title: Any?): List<String> {
    @Suppress("UNCHECKED_CAST") val t = title as? Map<String, Any?> ?: return listOf("$label 缺 title")
    return LOCALES.filter { (t[it] as? String).isNullOrBlank() }.map { "$label 缺 $it 标题" }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(exportControls) }
}
```

若该文件已有同名顶层 `LOCALES` 等符号冲突，改名为 `CONTROL_TITLE_LOCALES` 并全文一致。

- [ ] **Step 4: 正向运行**

Run: `./gradlew exportControls`
Expected: `controls registry 1.0.0 已导出`，`build/controls/registry.json` 存在。

- [ ] **Step 5: 负向运行**

Run: `./gradlew exportControls -PcontrolsSource=controls/src/test-fixtures/bad-unknown-control.json`
Expected: FAIL，消息含 `条款 99(1) 引用未登记控制键: GDPR:ART99`。

- [ ] **Step 6: npm 包目录**

`controls/package.json`：

```json
{
  "name": "@aster-cloud/controls",
  "version": "1.0.0",
  "description": "Aster 控制注册表（ADR 0045）：@control 受控词表与法规对照条款，供编译器与证据导出共用。",
  "license": "Apache-2.0",
  "repository": { "type": "git", "url": "https://github.com/aster-cloud/aster-lang-locales.git", "directory": "controls" },
  "publishConfig": { "access": "public" },
  "main": "registry.json",
  "files": ["registry.json"],
  "scripts": { "prepack": "cd .. && ./gradlew exportControls" }
}
```

`controls/.gitignore` 为空文件（registry.json 是源文件，必须入库，不同于 ui-messages）。`controls/README.md`：三段中文——用途、如何新增控制点（改 registry.json → `./gradlew exportControls` → 同步三处副本，见 ADR 0045 §2.2）、发布待 `release-plan.json` 登记（ADR 0045 §7）。

- [ ] **Step 7: 跑既有 check 确认未破坏**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: 提交**

```bash
git add controls build.gradle.kts
git commit -m "feat(controls): 控制注册表真相源与 exportControls 校验（ADR 0045 §2）"
```

---

### Task 2: 错误码 W704（aster-lang-ts + aster-lang-core）

**Files:**
- Modify: `aster-lang-ts/shared/error_codes.json`（在 `GOV_VERDICT_CALL_ARITY` 之后）
- Modify: `aster-lang-ts/src/diagnostics/error_codes.ts`（enum、`ERROR_MESSAGES`、`ERROR_METADATA` 三处，紧随 E703）
- Modify: `aster-lang-core/src/main/java/aster/core/typecheck/ErrorCode.java:109` 之后
- Modify: `aster-lang-core/src/test/resources/diagnostics/error_codes.json`（与 ts `shared/error_codes.json` 字节一致）

**Interfaces:**
- Produces: TS `ErrorCode.GOV_CONTROL_UNREGISTERED`、Java `ErrorCode.GOV_CONTROL_UNREGISTERED`（Task 3/4 使用）。

- [ ] **Step 1: json 条目**

```json
  "GOV_CONTROL_UNREGISTERED": {
    "code": "W704",
    "category": "governance",
    "severity": "warning",
    "message": "Control '{control}' on rule '{rule}' is not registered (controls registry {version})",
    "help": "Use a registered key such as EU_AI_ACT:ART14, or add it to aster-lang-locales controls/registry.json (format FRAMEWORK:ARTICLE, at most 64 characters)."
  }
```

- [ ] **Step 2: TS 三处，照 E703 的写法逐字复制字段值**

- [ ] **Step 3: Java 枚举**

```java
  GOV_CONTROL_UNREGISTERED("W704", Category.GOVERNANCE, Severity.WARNING, "Control '{control}' on rule '{rule}' is not registered (controls registry {version})", "Use a registered key such as EU_AI_ACT:ART14, or add it to aster-lang-locales controls/registry.json (format FRAMEWORK:ARTICLE, at most 64 characters)."),
```

- [ ] **Step 4: 复制 json 到 core 测试资源**

Run: `cp ../aster-lang-ts/shared/error_codes.json src/test/resources/diagnostics/error_codes.json`（在 aster-lang-core 目录）

- [ ] **Step 5: 跑两侧 parity**

Run（ts）：`pnpm build && node --test dist/test/unit/diagnostics/error-codes-parity.test.js`
Run（core）：`./gradlew --offline test --tests '*ErrorCodeParityTest*'`
Expected: 均 PASS。

- [ ] **Step 6: 两仓各自提交**

```bash
# aster-lang-ts
git add shared/error_codes.json src/diagnostics/error_codes.ts
git commit -m "feat(diagnostics): W704 GOV_CONTROL_UNREGISTERED（ADR 0045 §3）"
# aster-lang-core
git add src/main/java/aster/core/typecheck/ErrorCode.java src/test/resources/diagnostics/error_codes.json
git commit -m "feat(diagnostics): W704 GOV_CONTROL_UNREGISTERED（ADR 0045 §3）"
```

---

### Task 3: core `ControlRegistry` 与 `GovernanceChecker` W704（aster-lang-core）

**Files:**
- Create: `src/main/resources/governance/controls-registry.json`（逐字复制 aster-lang-locales `controls/registry.json`）
- Create: `src/main/java/aster/core/typecheck/governance/ControlRegistry.java`
- Modify: `src/main/java/aster/core/typecheck/governance/GovernanceChecker.java:25-63`
- Test: `src/test/java/aster/core/typecheck/governance/ControlRegistryTest.java`
- Test: `src/test/java/aster/core/typecheck/governance/GovernanceCheckerTest.java`（追加）
- Test: `src/test/java/aster/core/typecheck/governance/ControlRegistrySiblingParityTest.java`

**Interfaces:**
- Consumes: `ErrorCode.GOV_CONTROL_UNREGISTERED`（Task 2）。
- Produces: `public record ControlRegistry(String version, Set<String> keys)`，`static ControlRegistry loadDefault()`，`static ControlRegistry fromJson(InputStream)`，`boolean has(String key)`，`static boolean isWellFormed(String key)`；`GovernanceChecker(ControlRegistry)` 构造器（无参构造器 = `loadDefault()`）。Task 5 使用。

- [ ] **Step 1: 失败测试**

`ControlRegistryTest`：

```java
package aster.core.typecheck.governance;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 控制注册表（ADR 0045 §2）：默认副本可加载，键形态与登记判定。 */
class ControlRegistryTest {
  @Test void 默认注册表含五个初始控制点() {
    var r = ControlRegistry.loadDefault();
    assertEquals("1.0.0", r.version());
    assertTrue(r.has("EU_AI_ACT:ART14"));
    assertTrue(r.has("SOX:404"));
    assertEquals(5, r.keys().size());
  }

  @Test void 形态非法即使在集合中也不算登记() {
    assertFalse(ControlRegistry.isWellFormed("eu_ai_act:art14"));
    assertFalse(ControlRegistry.isWellFormed("EU_AI_ACT ART14"));
    assertFalse(ControlRegistry.isWellFormed("A:" + "B".repeat(63)));
    assertTrue(ControlRegistry.isWellFormed("EU_AI_ACT:ART14"));
  }

  @Test void 不合法的json抛出() {
    var in = new ByteArrayInputStream("{\"controls\":[]}".getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalStateException.class, () -> ControlRegistry.fromJson(in));
  }
}
```

`GovernanceCheckerTest` 追加（沿用 `GovernanceTestSupport.diagnose`，它走 `new TypeChecker()` → 默认注册表）：

```java
  private static long count(java.util.List<?> diags, ErrorCode code) {
    return diags.stream().filter(d -> d.toString().contains(code.code())).count();
  }

  @Test void 已登记控制点无W704() {
    var diags = diagnose("@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertFalse(has(diags, ErrorCode.GOV_CONTROL_UNREGISTERED), diags.toString());
  }

  @Test void 未登记与形态非法都报W704且同键只报一次() {
    var diags = diagnose("@id(\"R-1\")\n@control(\"ACME:ART1\")\n@control(\"ACME:ART1\")\n@control(\"eu_ai_act:art14\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertEquals(2, count(diags, ErrorCode.GOV_CONTROL_UNREGISTERED), diags.toString());
  }

  @Test void 注入空注册表时全部报W704() {
    var empty = new ControlRegistry("0.0.0", java.util.Set.of());
    var core = GovernanceTestSupport.lower("@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    var diags = new GovernanceChecker(empty).checkModule(core);
    assertTrue(has(diags, ErrorCode.GOV_CONTROL_UNREGISTERED), diags.toString());
  }
```

在 `GovernanceTestSupport` 增 `static CoreModel.Module lower(String body)`（把 `diagnose` 中 lowering 之前的部分抽出来，`diagnose` 改为调用它，行为不变）。若 `has`/`Diagnostic` 的 code 访问方式与 `d.toString().contains` 不同，改用 `has` 同款判定写 `count`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --offline test --tests '*governance*'`
Expected: 编译失败（`ControlRegistry` 不存在）。

- [ ] **Step 3: 复制注册表副本**

Run: `mkdir -p src/main/resources/governance && cp ../aster-lang-locales/controls/registry.json src/main/resources/governance/controls-registry.json`

- [ ] **Step 4: 实现 `ControlRegistry`**

```java
package aster.core.typecheck.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 控制注册表（ADR 0045 §2）：@control 的受控词表。
 *
 * <p>只关心「版本 + 已登记键」，条款与证据来源由证据导出侧消费。默认副本随 core 打包在
 * classpath（与 aster-lang-locales 真相源深度相等，见 ControlRegistrySiblingParityTest）。
 */
public record ControlRegistry(String version, Set<String> keys) {
  private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z0-9_]+:[A-Z0-9_().]+$");
  private static final int MAX_KEY_LENGTH = 64;
  private static final String RESOURCE = "/governance/controls-registry.json";

  public ControlRegistry {
    keys = Set.copyOf(keys);
  }

  /** 与审计写入的 64 字符截断上限一致：超长或形态不符的键不可能被登记。 */
  public static boolean isWellFormed(String key) {
    return key != null && key.length() <= MAX_KEY_LENGTH && KEY_PATTERN.matcher(key).matches();
  }

  public boolean has(String key) {
    return isWellFormed(key) && keys.contains(key);
  }

  /** classpath 默认注册表；缺失或不合法直接抛错（词表是治理检查的输入，不能静默为空）。 */
  public static ControlRegistry loadDefault() {
    return Holder.DEFAULT;
  }

  public static ControlRegistry fromJson(InputStream in) {
    try {
      JsonNode root = new ObjectMapper().readTree(in);
      String version = root.path("version").asText("");
      JsonNode controls = root.path("controls");
      if (version.isBlank() || !controls.isArray()) {
        throw new IllegalStateException("控制注册表缺 version 或 controls");
      }
      Set<String> keys = new LinkedHashSet<>();
      controls.forEach(c -> keys.add(c.path("key").asText()));
      return new ControlRegistry(version, keys);
    } catch (IOException e) {
      throw new UncheckedIOException("控制注册表解析失败", e);
    }
  }

  private static final class Holder {
    static final ControlRegistry DEFAULT = load();

    private static ControlRegistry load() {
      try (InputStream in = ControlRegistry.class.getResourceAsStream(RESOURCE)) {
        if (in == null) throw new IllegalStateException("classpath 缺少 " + RESOURCE);
        return fromJson(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
```

- [ ] **Step 5: `GovernanceChecker` 注入与 W704**

把 `checkFunc` 从 `static` 改为实例方法，新增字段与构造器，并在 `checkFunc` 末尾（W700 判定之后）追加：

```java
  private final ControlRegistry registry;

  /** 默认使用 classpath 注册表（ADR 0045）。 */
  public GovernanceChecker() {
    this(ControlRegistry.loadDefault());
  }

  public GovernanceChecker(ControlRegistry registry) {
    this.registry = registry;
  }
```

```java
    // ADR 0045：未登记或形态非法的控制键给 W704；同一规则同键只报一次，永不阻断
    for (String key : new java.util.LinkedHashSet<>(controls(func))) {
      if (registry.has(key)) continue;
      out.add(diag(ErrorCode.GOV_CONTROL_UNREGISTERED, func.origin,
          Map.of("control", key, "rule", rule, "version", registry.version())));
    }
```

`controls(func)` 是既有 public static 方法，保持不变。

- [ ] **Step 6: 兄弟仓守护测试**

```java
package aster.core.typecheck.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** classpath 副本与 aster-lang-locales 真相源深度相等（兄弟仓缺失时跳过）。 */
class ControlRegistrySiblingParityTest {
  @Test void 副本与真相源一致() throws Exception {
    Path source = Path.of("..", "aster-lang-locales", "controls", "registry.json");
    assumeTrue(Files.exists(source), "兄弟仓 aster-lang-locales 不在，跳过");
    var mapper = new ObjectMapper();
    var expected = mapper.readTree(source.toFile());
    try (var in = ControlRegistry.class.getResourceAsStream("/governance/controls-registry.json")) {
      assertEquals(expected, mapper.readTree(in));
    }
  }
}
```

- [ ] **Step 7: 运行**

Run: `./gradlew --offline test --tests '*governance*' --tests '*ErrorCodeParityTest*' --tests '*TypeChecker*'`
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add src/main/resources/governance src/main/java/aster/core/typecheck/governance src/test/java/aster/core/typecheck/governance
git commit -m "feat(governance): 控制注册表与未登记控制点 W704（ADR 0045 §2/§3）"
```

---

### Task 4: ts 注册表数据、`checkGovernance` 注入与诊断黄金（aster-lang-ts + aster-lang-test）

**Files:**
- Create: `aster-lang-ts/src/governance/controls-registry.data.ts`
- Create: `aster-lang-ts/src/governance/controls.ts`
- Modify: `aster-lang-ts/src/typecheck/governance.ts:86-128`
- Modify: 透传链：`src/browser.ts:166-178`（`CompileOptions.controls`）、`src/browser.ts:428-459`（`compileAndTypecheck`）、`src/typecheck/browser.ts:141`（`BrowserTypecheckOptions.controls`）、Node 路径 `TypecheckOptions`（`src/typecheck/context.ts:50`）与 `src/typecheck/module.ts:137`
- Test: `aster-lang-ts/test/unit/governance/governance-check.test.ts`（追加）
- Test: `aster-lang-ts/test/unit/governance/controls-registry-parity.test.ts`
- Test: 诊断黄金——照 `test/type-checker/` 下既有 fixture（如 `async_duplicate_start`）的同一布局新增 `governance_control_unregistered.aster` 与 `expected/governance_control_unregistered.errors.json`；若该 fixture 的 `.aster` 实际存放在 aster-lang-test `corpus/tier3-fixtures/type-checker/`，则新文件放在同处并写同款 `.meta.json`（engines `["ts"]`）。

**Interfaces:**
- Consumes: `ErrorCode.GOV_CONTROL_UNREGISTERED`（Task 2）。
- Produces: `export interface ControlRegistry { readonly version: string; has(key: string): boolean }`；`export const defaultControlRegistry: ControlRegistry`；`export function controlRegistryFrom(data: ControlsRegistryData): ControlRegistry`；`export const CONTROLS_REGISTRY: ControlsRegistryData`；`checkGovernance(decls, opts?: { controls?: ControlRegistry })`；`CompileOptions.controls?: ControlRegistry`。Task 5 使用 `CONTROLS_REGISTRY`。

- [ ] **Step 1: 失败测试（追加到 governance-check.test.ts）**

```ts
test('已登记控制点无 W704', () => {
  const codes = diagnose(`@id("R-1")\n@control("EU_AI_ACT:ART14")\n${VERDICT_RULE}`);
  assert.equal(codes.filter((c) => c === 'W704').length, 0, codes.join(','));
});

test('未登记与形态非法都报 W704，同键只报一次', () => {
  const codes = diagnose(`@id("R-1")\n@control("ACME:ART1")\n@control("ACME:ART1")\n@control("eu_ai_act:art14")\n${VERDICT_RULE}`);
  assert.equal(codes.filter((c) => c === 'W704').length, 2, codes.join(','));
});

test('注入空注册表时已登记键也报 W704', () => {
  const empty = { version: '0.0.0', has: () => false };
  const fn = handFunc('main', Core.Block([]), [
    { name: 'id', args: [{ name: '$0', value: 'R-1' }] },
    { name: 'control', args: [{ name: '$0', value: 'EU_AI_ACT:ART14' }] },
  ]);
  const diags = checkGovernance([fn], { controls: empty });
  assert.ok(diags.some((d) => d.code === 'W704'), JSON.stringify(diags));
});
```

`diagnose` 与 `handFunc` 是该文件既有辅助；若 `Core.Block([])` 的构造签名不同，照文件中其它 `handFunc` 调用的 body 写法。`checkGovernance` 需从 `../../../src/typecheck/governance.js` 导入（照文件现有 import 风格）。

`controls-registry-parity.test.ts`：

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { CONTROLS_REGISTRY } from '../../../src/governance/controls-registry.data.js';

// 内置副本与 aster-lang-locales 真相源深度相等（ADR 0045 §2.2）；兄弟仓缺失时跳过
test('controls 副本与真相源一致', (t) => {
  const source = join(process.cwd(), '..', 'aster-lang-locales', 'controls', 'registry.json');
  if (!existsSync(source)) return t.skip('兄弟仓 aster-lang-locales 不在');
  assert.deepEqual(CONTROLS_REGISTRY, JSON.parse(readFileSync(source, 'utf8')));
});
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm build`
Expected: 编译失败（模块不存在）。

- [ ] **Step 3: 数据模块**

`src/governance/controls-registry.data.ts`：

```ts
/**
 * 控制注册表内置副本（ADR 0045 §2.2）。与 aster-lang-locales controls/registry.json 深度相等，
 * 由 controls-registry-parity 测试守护；用 TS 模块而非 JSON 导入，避免浏览器与 Node ESM 的 JSON 导入差异。
 */
export interface LocalizedTitle { readonly en: string; readonly zh: string; readonly de: string }
export interface ControlsRegistryData {
  readonly version: string;
  readonly frameworks: ReadonlyArray<{ readonly id: string; readonly title: LocalizedTitle }>;
  readonly controls: ReadonlyArray<{ readonly key: string; readonly framework: string; readonly article: string; readonly title: LocalizedTitle }>;
  readonly clauses: ReadonlyArray<{
    readonly control: string; readonly clause: string; readonly title: LocalizedTitle;
    readonly evidenced: readonly string[]; readonly partial: readonly string[];
  }>;
}

export const CONTROLS_REGISTRY: ControlsRegistryData = /* 粘贴 Task 1 registry.json 的完整内容（JSON 即合法 TS 字面量） */ {
  version: '1.0.0',
  frameworks: [],
  controls: [],
  clauses: [],
};
```

把注释所在处的空数组替换为 Task 1 Step 1 的完整内容（逐字），并删除该行内注释；parity 测试会校验。

`src/governance/controls.ts`：

```ts
import { CONTROLS_REGISTRY, type ControlsRegistryData } from './controls-registry.data.js';

/** 编译期只需「版本 + 是否登记」（ADR 0045 §3）。 */
export interface ControlRegistry {
  readonly version: string;
  has(key: string): boolean;
}

const KEY_PATTERN = /^[A-Z0-9_]+:[A-Z0-9_().]+$/;
const MAX_KEY_LENGTH = 64;

/** 与审计写入的 64 字符截断上限一致：超长或形态不符的键不可能被登记。 */
export function isWellFormedControlKey(key: string): boolean {
  return key.length <= MAX_KEY_LENGTH && KEY_PATTERN.test(key);
}

export function controlRegistryFrom(data: ControlsRegistryData): ControlRegistry {
  const keys = new Set(data.controls.map((c) => c.key));
  return { version: data.version, has: (key) => isWellFormedControlKey(key) && keys.has(key) };
}

export const defaultControlRegistry: ControlRegistry = controlRegistryFrom(CONTROLS_REGISTRY);
```

- [ ] **Step 4: `checkGovernance` 注入**

`governance.ts`：签名改为 `export function checkGovernance(decls: readonly Core.Declaration[], opts: { controls?: ControlRegistry } = {}): TypecheckDiagnostic[]`，`const registry = opts.controls ?? defaultControlRegistry;` 传入 `checkFunc(decl, idOwners, b, registry)`；`checkFunc` 末尾追加：

```ts
  // ADR 0045：未登记或形态非法的控制键给 W704；同一规则同键只报一次，永不阻断
  for (const key of new Set(controls(func))) {
    if (registry.has(key)) continue;
    b.warning(ErrorCode.GOV_CONTROL_UNREGISTERED, span, { control: key, rule, version: registry.version });
  }
```

（`controls(func)` 是该文件既有辅助，若名称不同用其实际名称。）

- [ ] **Step 5: 透传 `controls`**

- `CompileOptions` 增 `/** 控制注册表（ADR 0045）；缺省用内置副本 */ controls?: ControlRegistry;`
- `compileAndTypecheck`：`typecheckOptions` 类型改为 `{ enforcePii?: boolean; controls?: ControlRegistry }`，`if (options?.controls) typecheckOptions.controls = options.controls;`
- `BrowserTypecheckOptions` 与 Node `TypecheckOptions` 各增 `controls?: ControlRegistry`；`module.ts:137` 改为 `checkGovernance(m.decls, { controls: <当前作用域中的 options>.controls })`——沿调用链找到持有 options 的变量名，逐层传入；没有 options 的调用点保持缺省。
- 在包入口（`src/index.ts` 或既有导出 typecheck 的入口，以及 `src/browser.ts`）导出 `ControlRegistry`、`defaultControlRegistry`、`controlRegistryFrom`、`CONTROLS_REGISTRY` 类型与值。

- [ ] **Step 6: 诊断黄金**

源文 `governance_control_unregistered.aster`：

```
Module governance.control.unregistered.

@id("GC-001")
@control("EU_AI_ACT:ART14")
@control("ACME:ART1")
Rule decide given amount, produce Verdict:
  If amount at least 100:
    Return Verdict.require_approval("Reviewer", "large").
  Return Verdict.allow().
```

期望文件只含一条 `W704`，message `Control 'ACME:ART1' on rule 'decide' is not registered (controls registry 1.0.0)`，格式照邻近 expected 文件。并在 aster-lang-core `GovernanceCheckerTest` 追加同一源文的用例断言恰好一条 W704（Java 侧守同一结论，ADR 0045 §3）。

- [ ] **Step 7: 运行**

Run: `pnpm build && node --test dist/test/unit/governance/governance-check.test.js dist/test/unit/governance/controls-registry-parity.test.js dist/test/unit/diagnostics/error-codes-parity.test.js`，再跑仓内类型检查黄金的既有脚本（`package.json` 中含 `type-checker` 的 script）。
Expected: PASS。core：`./gradlew --offline test --tests '*GovernanceCheckerTest*'` PASS。

- [ ] **Step 8: 提交**

```bash
# aster-lang-ts
git add src/governance src/typecheck src/browser.ts src/index.ts test
git commit -m "feat(governance): 内置控制注册表与未登记控制点 W704，可注入（ADR 0045 §3）"
# aster-lang-core（黄金同源用例）
git add src/test/java/aster/core/typecheck/governance/GovernanceCheckerTest.java
git commit -m "test(governance): 与 ts 诊断黄金同源的 W704 用例（ADR 0045 §3）"
# aster-lang-test（仅当黄金源文放在 corpus 时）
git add corpus/tier3-fixtures
git commit -m "corpus: W704 诊断黄金源文（ADR 0045 §3）"
```

---

### Task 5: LSP `@control("` 补全（aster-lang-ts）

**Files:**
- Modify: `src/lsp/completion.ts:241-253`
- Test: `test/unit/lsp/control-completion.test.ts`

**Interfaces:**
- Consumes: `CONTROLS_REGISTRY`（Task 4）。
- Produces: `export function controlCompletions(linePrefix: string): CompletionItem[] | null`（非 `@control("` 上下文返回 null）。

- [ ] **Step 1: 失败测试**

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { controlCompletions } from '../../../src/lsp/completion.js';

test('@control(" 处补全全部注册表键', () => {
  const items = controlCompletions('@control("');
  assert.ok(items);
  assert.deepEqual(items!.map((i) => i.label).sort(), ['EU_AI_ACT:ART14', 'GDPR:ART17', 'GDPR:ART22', 'GDPR:ART6', 'SOX:404']);
});

test('按已输入前缀过滤，detail 为英文标题', () => {
  const items = controlCompletions('  @control( "GDPR:ART2');
  assert.deepEqual(items!.map((i) => i.label), ['GDPR:ART22']);
  assert.equal(items![0]!.detail, 'Automated individual decision-making');
});

test('非 @control 字符串上下文返回 null', () => {
  assert.equal(controlCompletions('@id("'), null);
  assert.equal(controlCompletions('Rule main'), null);
});
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm build`
Expected: 编译失败（未导出）。

- [ ] **Step 3: 实现**

```ts
const CONTROL_ARG = /@control\(\s*"([^"]*)$/;

/** `@control("` 字符串内补全注册表键（ADR 0045 §3）；其它位置返回 null。 */
export function controlCompletions(linePrefix: string): CompletionItem[] | null {
  const m = CONTROL_ARG.exec(linePrefix);
  if (!m) return null;
  const typed = m[1] ?? '';
  const frameworks = new Map(CONTROLS_REGISTRY.frameworks.map((f) => [f.id, f.title]));
  return CONTROLS_REGISTRY.controls
    .filter((c) => c.key.startsWith(typed))
    .map((c) => ({
      label: c.key,
      kind: CompletionItemKind.Value,
      detail: c.title.en,
      documentation: `${frameworks.get(c.framework)?.en ?? c.framework} · ${c.title.en} / ${c.title.zh} / ${c.title.de}`,
    }));
}
```

在 completion 处理器开头（取到 `params.position` 与文档之后、生成其它补全之前）：

```ts
    const doc = documents.get(params.textDocument.uri);
    if (doc) {
      const linePrefix = doc.getText({ start: { line: params.position.line, character: 0 }, end: params.position });
      const controls = controlCompletions(linePrefix);
      if (controls) return controls;
    }
```

`documents` 用该文件中既有的 TextDocuments 实例名。

- [ ] **Step 4: 运行与提交**

Run: `pnpm build && node --test dist/test/unit/lsp/control-completion.test.js` 并跑既有 lsp 单测目录。
Expected: PASS。

```bash
git add src/lsp/completion.ts test/unit/lsp/control-completion.test.ts
git commit -m "feat(lsp): @control 键补全取自控制注册表（ADR 0045 §3）"
```

---

### Task 6: aster-api 编译端点治理扫描与注册表版本（aster-api）

**Files:**
- Modify: `src/main/java/io/aster/policy/compiler/PolicyCompiler.java:99-120`
- Create: `src/main/java/io/aster/policy/compiler/GovernanceDiagnostics.java`
- Create: `src/main/java/io/aster/policy/compiler/ControlRegistryStartup.java`
- Modify: `src/main/java/io/aster/policy/rest/model/CompileResponse.java:14-27` 及其 `ok`/`fail` 工厂
- Modify: `src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java:937-960`
- Test: `src/test/java/io/aster/policy/compiler/PolicyCompilerGovernanceTest.java`
- Test: 编译端点资源测试（找到断言 `POST /api/v1/policies/compile` 的既有测试类追加一例）

**Interfaces:**
- Consumes: core `GovernanceChecker`、`ControlRegistry`（Task 3，经 composite build）。
- Produces: `CompileResponse.controlsRegistryVersion`（JSON `controlsRegistryVersion`）；编译诊断含 W700/E701/E702/W704。

- [ ] **Step 1: 失败测试**

```java
package io.aster.policy.compiler;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 编译端点带出治理诊断（ADR 0045 §3）：W704 为 warning，不改变编译成功。 */
@QuarkusTest
class PolicyCompilerGovernanceTest {
  @Inject PolicyCompiler compiler;

  static final String SRC = String.join("\n",
      "Module governance.probe.", "",
      "@id(\"GP-001\")", "@control(\"EU_AI_ACT:ART14\")", "@control(\"ACME:ART1\")",
      "Rule decide given amount, produce Verdict:",
      "  If amount at least 100:", "    Return Verdict.require_approval(\"Reviewer\", \"large\").",
      "  Return Verdict.allow().", "");

  @Test void 未登记控制点给W704且编译成功() {
    var result = compiler.compile(SRC);
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getDiagnostics()).anySatisfy(d -> {
      assertThat(d.toString()).contains("W704").contains("ACME:ART1");
    });
    assertThat(result.getDiagnostics().stream().filter(d -> d.toString().contains("W704")).count()).isEqualTo(1);
  }
}
```

调用方式以 `PolicyCompiler` 实际公开方法为准（参数可能含 locale/租户，照该类既有测试）；`d.toString()` 若不含 code，改为断言 `CompilationResult.Diagnostic` 的 code 访问器。

资源测试追加：`POST /api/v1/policies/compile` 发同一源文 → 200，`success == true`，`controlsRegistryVersion == "1.0.0"`，`diagnostics` 中有 `code == "W704"`。

- [ ] **Step 2: 运行确认失败**

Run: `... --tests 'io.aster.policy.compiler.PolicyCompilerGovernanceTest'`
Expected: FAIL（无 W704）。

- [ ] **Step 3: 诊断映射**

`GovernanceDiagnostics`：一个静态方法 `static List<CompilationResult.Diagnostic> scan(CoreModel.Module core, GovernanceChecker checker)`，把 core `Diagnostic` 逐条转为 `CompilationResult.Diagnostic`：code、severity（WARNING/ERROR 照 core 枚举映射到该类型既有取值）、message（core 已格式化的消息）、行列取 core span 的起点（1-based；缺失时 1/1）。转换写法照 `StabilityEnforcement.scan` 生成 `CompilationResult.Diagnostic` 的方式。类注释说明：编译端点此前不跑治理检查，ADR 0045 起与稳定性、规则冲突诊断同通道合流。

- [ ] **Step 4: 接入 `PolicyCompiler`**

字段 `private final GovernanceChecker governanceChecker = new GovernanceChecker();`；在 `allDiagnostics.addAll(conflicts);` 之后加 `allDiagnostics.addAll(GovernanceDiagnostics.scan(coreModule, governanceChecker));`，并在 3c 段后加一行注释「3d. 治理检查（ADR 0039/0045）：W700/E701/E702/W704，恒随诊断返回，不改变成功与否」。

- [ ] **Step 5: 响应带注册表版本**

`CompileResponse` 末尾增 `@JsonProperty("controlsRegistryVersion") String controlsRegistryVersion`；`ok(...)`/`fail(...)` 工厂各补一个参数或在工厂内取 `ControlRegistry.loadDefault().version()`（选后者，调用点不变）。所有 `new CompileResponse(` 直接构造点同步补参。

- [ ] **Step 6: 启动校验**

```java
package io.aster.policy.compiler;

import aster.core.typecheck.governance.ControlRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/** 启动即加载控制注册表：缺失或不合法让启动失败，而不是让治理检查静默失效（ADR 0045 §2.2）。 */
@ApplicationScoped
public class ControlRegistryStartup {
  private static final Logger LOG = Logger.getLogger(ControlRegistryStartup.class);

  void onStart(@Observes StartupEvent event) {
    ControlRegistry registry = ControlRegistry.loadDefault();
    LOG.infof("控制注册表 %s 已加载（%d 个控制点）", registry.version(), registry.keys().size());
  }
}
```

- [ ] **Step 7: 运行**

Run: `... --tests 'io.aster.policy.compiler.*' --tests '*Compile*' --tests '*Stability*' --tests '*RuleConflict*' --tests 'io.aster.api.PublicApiContractTest' --tests 'io.aster.api.workflow.NonDeterminismSourceTest'`
Expected: PASS。若既有测试断言了编译诊断的精确条数而因新增 W700/E702 变化，逐条判断：断言的是「稳定性/冲突诊断」的就把过滤条件收紧到对应 code，不得删除断言。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/io/aster/policy/compiler src/main/java/io/aster/policy/rest src/test/java
git commit -m "feat(compile): 编译端点合流治理诊断（含 W704）并返回控制注册表版本（ADR 0045 §3）"
```

---

### Task 7: cloud 注册表副本与注册表驱动的对照表、证据包 v4（aster-cloud）

**Files:**
- Create: `src/config/controls-registry.json`（逐字复制 aster-lang-locales `controls/registry.json`）
- Create: `src/services/evidence/control-registry.ts`
- Create: `src/services/evidence/regulatory-mapping.ts`
- Delete: `src/services/evidence/article14.ts`；`src/__tests__/services/evidence/article14.test.ts`（用例迁移后删除）
- Modify: `src/services/evidence/types.ts`（v3 复制为 `EvidenceManifestV3`/`EvidenceBundleV3` 冻结；现行改 `'4'` 与新 `regulatoryMapping`）
- Modify: `src/services/evidence/bundle.ts:185-207`
- Modify: `src/services/evidence/stored.ts:20,41-50`
- Modify: `scripts/lib/credit-pilot-expectations.ts`、`scripts/run-credit-pilot.ts:132-150`、`src/__tests__/e2e-browser/local-stack/credit-pilot.spec.ts:112-117`、`src/__tests__/scripts/credit-pilot-expectations.test.ts`
- Test: `src/__tests__/services/evidence/regulatory-mapping.test.ts`、`src/__tests__/services/evidence/control-registry-parity.test.ts`、`bundle.test.ts`/`stored.test.ts` 追加

**Interfaces:**
- Produces:
  - `control-registry.ts`：`EvidenceSource`（Global Constraints 的十个字面量联合）、`LocalizedTitle`、`RegistryClause`、`ControlRegistryData`、`defaultControlRegistry: ControlRegistryData`。
  - `regulatory-mapping.ts`：`ClauseStatus`、`EvidenceRef { executionId; field }`、`RegulatoryClause { clause; title: LocalizedTitle; status; evidence }`、`FrameworkMapping { framework; article; control; clauses }`、`RegulatoryMapping { registryVersion; frameworks }`、`mapRegulatory(entries, registry = defaultControlRegistry): RegulatoryMapping`。
  - `credit-pilot-expectations.ts`：`articleClauses(mapping: RegulatoryMappingLike, control = 'EU_AI_ACT:ART14'): ClauseMappingLike`。
  Task 8 使用 `RegulatoryMapping` 与 `LocalizedTitle`。

- [ ] **Step 1: 注册表副本与类型**

`src/services/evidence/control-registry.ts`：

```ts
// 控制注册表（ADR 0045）：发布前读仓内副本，与 aster-lang-locales 真相源深度相等（parity 测试守护）。
import raw from '@/config/controls-registry.json';

export type EvidenceSource =
  | 'control' | 'outcome.pending' | 'outcome.requireApproval' | 'reviewers.any' | 'reviewers.guard.any'
  | 'reviewers.guard.approved' | 'reviewers.guard.rejected' | 'reviewers.guard.two' | 'whatIf.comparable' | 'rule.reason';

export interface LocalizedTitle { en: string; zh: string; de: string }
export interface RegistryClause {
  control: string; clause: string; title: LocalizedTitle; evidenced: EvidenceSource[]; partial: EvidenceSource[];
}
export interface ControlRegistryData {
  version: string;
  frameworks: Array<{ id: string; title: LocalizedTitle }>;
  controls: Array<{ key: string; framework: string; article: string; title: LocalizedTitle }>;
  clauses: RegistryClause[];
}

export const defaultControlRegistry = raw as ControlRegistryData;
```

parity 测试：

```ts
import { describe, it, expect } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { defaultControlRegistry } from '@/services/evidence/control-registry';

describe('controls 副本', () => {
  const source = join(process.cwd(), '..', 'aster-lang-locales', 'controls', 'registry.json');
  it.skipIf(!existsSync(source))('与 aster-lang-locales 真相源深度相等', () => {
    expect(defaultControlRegistry).toEqual(JSON.parse(readFileSync(source, 'utf8')));
  });
});
```

- [ ] **Step 2: 对照表失败测试**

`regulatory-mapping.test.ts`：把 `article14.test.ts` 的九款用例全部迁入，断言改为读 `mapRegulatory(entries).frameworks.find((f) => f.control === 'EU_AI_ACT:ART14')!.clauses`，并以 `e()`/`rv()` 辅助（照 article14.test.ts 现有写法，entries 默认 `controls: ['EU_AI_ACT:ART14']`）。期望状态保持原测试的值，以下三处按 ADR 0045 §4.1 调整并在用例名写明：
- 14(4)(e)：REQUIRE_APPROVAL 条目无复核者、另一条目有 roleVerified guard 复核者 → evidenced（跨条目）；guard 复核者 `roleVerified: false` → 只能 partial。
- 14(5)：同一条目两个 guard 复核者但其一 `roleVerified: false` → none。
- 14(4)(c)：evidence 恰为 `[{executionId, field: 'ruleId'}, {executionId, field: 'reasonCodes'}]`。

新增：

```ts
it('entries 为空：所有带条款的组全部 none，并写注册表版本', () => {
  const m = mapRegulatory([]);
  expect(m.registryVersion).toBe('1.0.0');
  expect(m.frameworks.map((f) => f.control)).toEqual(['EU_AI_ACT:ART14']);
  expect(m.frameworks[0]!.clauses.every((c) => c.status === 'none' && c.evidence.length === 0)).toBe(true);
});

it('条目都不含该控制点：不生成该组', () => {
  const m = mapRegulatory([e({ controls: ['GDPR:ART17'] })]);
  expect(m.frameworks).toEqual([]);
});

it('标题为三语对象', () => {
  const c = mapRegulatory([]).frameworks[0]!.clauses.find((x) => x.clause === '14(4)(d)')!;
  expect(c.title).toEqual({ en: 'Decide not to use or override the output', zh: '决定不使用或推翻输出', de: 'Ausgabe nicht verwenden oder übersteuern' });
});

it('注入注册表：新增框架只改数据', () => {
  const registry = {
    version: '9.9.9', frameworks: [{ id: 'X', title: { en: 'X', zh: 'X', de: 'X' } }],
    controls: [{ key: 'X:1', framework: 'X', article: '1', title: { en: 'x', zh: 'x', de: 'x' } }],
    clauses: [{ control: 'X:1', clause: '1(1)', title: { en: 'a', zh: 'a', de: 'a' }, evidenced: ['outcome.pending' as const], partial: [] }],
  };
  const m = mapRegulatory([e({ controls: ['X:1'], outcome: 'ESCALATE' })], registry);
  expect(m.frameworks[0]).toMatchObject({ framework: 'X', article: '1', control: 'X:1' });
  expect(m.frameworks[0]!.clauses[0]!.status).toBe('evidenced');
});
```

- [ ] **Step 3: 运行确认失败**

Run: `pnpm exec vitest run src/__tests__/services/evidence/regulatory-mapping.test.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 4: 实现 `regulatory-mapping.ts`**

```ts
/**
 * 法规对照（ADR 0045 §4）：由控制注册表的条款数据驱动，判定规则统一。
 * 只陈述证据包内已有的字段，不打分、不给建议；无来源可证的条款如实为 none。
 */
import { defaultControlRegistry, type ControlRegistryData, type EvidenceSource, type LocalizedTitle } from './control-registry';
import type { EvidenceEntry } from './types';

export type ClauseStatus = 'evidenced' | 'partial' | 'none';
export interface EvidenceRef { executionId: string; field: string }
export interface RegulatoryClause { clause: string; title: LocalizedTitle; status: ClauseStatus; evidence: EvidenceRef[] }
export interface FrameworkMapping { framework: string; article: string; control: string; clauses: RegulatoryClause[] }
export interface RegulatoryMapping { registryVersion: string; frameworks: FrameworkMapping[] }

type SourceFn = (entries: readonly EvidenceEntry[], control: string) => EvidenceRef[];

const ref = (e: EvidenceEntry, field: string): EvidenceRef => ({ executionId: e.executionId, field });
const pick = (field: string, test: (e: EvidenceEntry, control: string) => boolean): SourceFn =>
  (entries, control) => entries.filter((e) => test(e, control)).map((e) => ref(e, field));

// 执行级干预只认已验证角色的 guard-approval：版本级 proof/approval 挂在该版本每条执行上，不代表有人复核过这条输出
const guardReviewers = (e: EvidenceEntry) => e.reviewers.filter((r) => r.source === 'guard-approval' && r.roleVerified);

const SOURCES: Record<EvidenceSource, SourceFn> = {
  control: pick('controls', (e, control) => (e.controls ?? []).includes(control)),
  'outcome.pending': pick('outcome', (e) => e.outcome === 'REQUIRE_APPROVAL' || e.outcome === 'ESCALATE'),
  'outcome.requireApproval': pick('outcome', (e) => e.outcome === 'REQUIRE_APPROVAL'),
  'reviewers.any': pick('reviewers', (e) => e.reviewers.length > 0),
  'reviewers.guard.any': pick('reviewers', (e) => guardReviewers(e).length > 0),
  'reviewers.guard.approved': pick('reviewers', (e) => guardReviewers(e).some((r) => r.outcome === 'APPROVED')),
  'reviewers.guard.rejected': pick('reviewers', (e) => guardReviewers(e).some((r) => r.outcome === 'REJECTED')),
  'reviewers.guard.two': pick('reviewers', (e) => guardReviewers(e).length >= 2),
  // 在途批次（targetOutcome 为 null）与失败项（ERROR）不是完成的比对结论
  'whatIf.comparable': pick('whatIf', (e) => e.whatIf !== null && e.whatIf.targetOutcome !== null && e.whatIf.targetOutcome !== 'ERROR'),
  'rule.reason': (entries) => entries
    .filter((e) => e.ruleId !== null && Array.isArray(e.reasonCodes) && e.reasonCodes.length > 0)
    .flatMap((e) => [ref(e, 'ruleId'), ref(e, 'reasonCodes')]),
};

function dedupe(refs: EvidenceRef[]): EvidenceRef[] {
  const seen = new Set<string>();
  return refs.filter((r) => {
    const k = `${r.executionId}\u0000${r.field}`;
    return seen.has(k) ? false : (seen.add(k), true);
  });
}

/** evidenced 非空且每个来源都命中 → evidenced；否则 partial 中任一命中 → partial；否则 none。 */
function judge(entries: readonly EvidenceEntry[], control: string, evidenced: EvidenceSource[], partial: EvidenceSource[]) {
  const hits = (sources: EvidenceSource[]) => sources.map((s) => SOURCES[s](entries, control));
  const full = hits(evidenced);
  if (full.length > 0 && full.every((h) => h.length > 0)) {
    return { status: 'evidenced' as const, evidence: dedupe(full.flat()) };
  }
  const some = hits(partial).filter((h) => h.length > 0);
  if (some.length > 0) return { status: 'partial' as const, evidence: dedupe(some.flat()) };
  return { status: 'none' as const, evidence: [] };
}

export function mapRegulatory(entries: readonly EvidenceEntry[], registry: ControlRegistryData = defaultControlRegistry): RegulatoryMapping {
  const used = new Set(entries.flatMap((e) => e.controls ?? []));
  const groups = registry.controls.filter((c) =>
    registry.clauses.some((cl) => cl.control === c.key) && (entries.length === 0 || used.has(c.key)));
  return {
    registryVersion: registry.version,
    frameworks: groups.map((c) => ({
      framework: c.framework,
      article: c.article,
      control: c.key,
      clauses: registry.clauses
        .filter((cl) => cl.control === c.key)
        .map((cl) => ({ clause: cl.clause, title: cl.title, ...judge(entries, c.key, cl.evidenced, cl.partial) })),
    })),
  };
}
```

- [ ] **Step 5: types / bundle / stored**

- `types.ts`：把现行 `EvidenceManifest`（`schemaVersion: '3'`，`regulatoryMapping: Article14Mapping`）与 `EvidenceBundle` 复制为 `EvidenceManifestV3`/`EvidenceBundleV3` 放入冻结区，其中 `regulatoryMapping` 类型改为本文件内联的冻结形状 `{ framework: 'EU_AI_ACT'; article: '14'; clauses: Array<{ clause: string; title: string; status: 'evidenced' | 'partial' | 'none'; evidence: Array<{ executionId: string; field: string }> }> }`（不再引用将被删除的 article14.ts）。现行 `EvidenceManifest.schemaVersion: '4'`，注释「v4 起 regulatoryMapping 由控制注册表驱动、按控制点分组并带 registryVersion」，`regulatoryMapping: RegulatoryMapping`。`StoredEvidenceManifest`/`StoredEvidenceBundle` 加入 V3。
- `bundle.ts`：`schemaVersion: '4'`，`regulatoryMapping: mapRegulatory(sorted)`；`notes.verification` 文案中的版本号同步为 4。
- `stored.ts`：`KNOWN_SCHEMA_VERSIONS` 加 `'4'`；`summarizeManifest` 加 `case '4'`。
- 删除 `article14.ts` 与 `article14.test.ts`；`grep -rn "article14" src scripts` 结果为空。
- `bundle.test.ts` 的 `describe('v3 manifest')` 改为 v4 并断言 `schemaVersion '4'` 与 `regulatoryMapping.registryVersion '1.0.0'`；`stored.test.ts` 加 v4 可读、`'5'` 不可读。

- [ ] **Step 6: 试点期望与脚本/规格**

`credit-pilot-expectations.ts`：`ClauseStatus` 改从 `regulatory-mapping` 导入；新增

```ts
export interface RegulatoryMappingLike {
  frameworks: ReadonlyArray<{ control: string; clauses: ReadonlyArray<{ clause: string; status: string }> }>;
}

// 取某控制点的条款组；缺失时返回空组，由 diffClauses 报出每款 missing
export function articleClauses(mapping: RegulatoryMappingLike, control = 'EU_AI_ACT:ART14'): ClauseMappingLike {
  return { clauses: mapping.frameworks.find((f) => f.control === control)?.clauses ?? [] };
}
```

`run-credit-pilot.ts` 的 `console.table` 与 `diffClauses` 改用 `articleClauses(mapping)`；类型 `{ manifest: { regulatoryMapping: RegulatoryMappingLike } }`。`credit-pilot.spec.ts:115-116` 改为 `toBe('4')` 与 `diffClauses(articleClauses(bundle.manifest.regulatoryMapping), EXPECTED_CLAUSE_STATUS)`。expectations 测试加一例：`articleClauses({ frameworks: [] })` 经 `diffClauses` 报九条 missing。

- [ ] **Step 7: 运行**

Run: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm exec vitest run src/__tests__/services/evidence src/__tests__/lib src/__tests__/scripts`
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add src/config/controls-registry.json src/services/evidence src/__tests__/services/evidence scripts src/__tests__/scripts src/__tests__/e2e-browser/local-stack/credit-pilot.spec.ts
git commit -m "feat(evidence): 对照表由控制注册表驱动，证据包 v4（ADR 0045 §4）"
```

---

### Task 8: 报告页渲染对照表与试点指南（aster-cloud）

**Files:**
- Create: `src/app/api/reports/[id]/mapping/route.ts`
- Create: `src/components/evidence/regulatory-mapping-table.tsx`
- Modify: `src/app/[locale]/(dashboard)/reports/page.tsx:31-50`（行数据加 `schemaVersion`）
- Modify: `src/app/[locale]/(dashboard)/reports/reports-content.tsx:118-136,300-336`
- Modify: `src/i18n/demo-supplement.ts`（en/zh/de/hi 的 `evidenceExport` 下加 `mapping`）
- Modify: `src/app/[locale]/docs/guides/credit-pilot/{en,zh,de}.mdx`
- Test: `src/__tests__/api/reports-mapping.route.test.ts`、`src/__tests__/components/regulatory-mapping-table.test.tsx`
- Modify: `src/__tests__/e2e-browser/local-stack/credit-pilot.spec.ts`（导出后展开对照）

**Interfaces:**
- Consumes: `RegulatoryMapping`、`LocalizedTitle`（Task 7）；`readStoredEvidenceExport`（既有）。
- Produces: `GET /api/reports/{id}/mapping` → 200 `{ mapping: RegulatoryMapping | null }`，404 `{ error: 'not_found' }`，401 未登录。

- [ ] **Step 1: 路由失败测试**

照 `src/app/api/reports/[id]/download/route.ts` 的测试（若无，照同目录其它 route 测试的 mock 方式）写三例：v4 报告返回 `mapping.registryVersion === '1.0.0'`；v3 报告返回 `{ mapping: null }`；他人或不存在的 id → 404。

- [ ] **Step 2: 路由实现**

复制 download 路由的鉴权与按 `userId` 查询报告的代码，替换返回部分：

```ts
  const stored = readStoredEvidenceExport(report.data);
  // v1–v3 不含注册表驱动的对照：如实返回 null，由 UI 显示「此版本不含对照」
  const mapping = stored && stored.manifest.schemaVersion === '4' ? stored.manifest.regulatoryMapping : null;
  return NextResponse.json({ mapping });
```

- [ ] **Step 3: 组件失败测试**

照 `business-roles-panel.test.tsx` 的做法，组件经 `labels` prop 取文案、不直接依赖 next-intl：

```tsx
// 法规对照表（ADR 0045 §5）：状态徽标、按 locale 取条款标题、证据条数与空态。
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { RegulatoryMappingTable, type RegulatoryMappingLabels } from '@/components/evidence/regulatory-mapping-table';
import type { RegulatoryMapping } from '@/services/evidence/regulatory-mapping';

const labels: RegulatoryMappingLabels = {
  notAvailable: 'not-available', registryVersion: 'registry {version}', disclaimer: 'disclaimer',
  columns: { clause: 'clause', title: 'title', status: 'status', evidence: 'evidence' },
  status: { evidenced: 'S-evidenced', partial: 'S-partial', none: 'S-none' },
};

const t = (en: string, zh: string) => ({ en, zh, de: en });
const mapping: RegulatoryMapping = {
  registryVersion: '1.0.0',
  frameworks: [{
    framework: 'EU_AI_ACT', article: '14', control: 'EU_AI_ACT:ART14',
    clauses: [
      { clause: '14(1)', title: t('Designed for effective human oversight', '设计上可被有效人工监督'), status: 'evidenced',
        evidence: [{ executionId: 'e1', field: 'controls' }, { executionId: 'e2', field: 'controls' }] },
      { clause: '14(4)(b)', title: t('Awareness of automation bias', '意识到自动化偏见'), status: 'none', evidence: [] },
      { clause: '14(4)(d)', title: t('Decide not to use or override the output', '决定不使用或推翻输出'), status: 'partial',
        evidence: [{ executionId: 'e3', field: 'reviewers' }] },
    ],
  }],
};

afterEach(cleanup);

describe('RegulatoryMappingTable', () => {
  it('按 locale 渲染标题、三种状态与证据条数', () => {
    render(<RegulatoryMappingTable mapping={mapping} locale="zh" labels={labels} />);
    expect(screen.getByText('决定不使用或推翻输出')).toBeTruthy();
    expect(screen.getByText('S-evidenced')).toBeTruthy();
    expect(screen.getByText('S-partial')).toBeTruthy();
    expect(screen.getByText('S-none')).toBeTruthy();
    expect(screen.getByText('registry 1.0.0')).toBeTruthy();
    expect(screen.getByTestId('evidence-count-14(1)').textContent).toBe('2');
  });

  it('hi 等未覆盖的 locale 回退英文标题', () => {
    render(<RegulatoryMappingTable mapping={mapping} locale="hi" labels={labels} />);
    expect(screen.getByText('Awareness of automation bias')).toBeTruthy();
  });

  it('mapping 为 null 显示不含对照', () => {
    render(<RegulatoryMappingTable mapping={null} locale="en" labels={labels} />);
    expect(screen.getByText('not-available')).toBeTruthy();
  });
});
```

- [ ] **Step 4: 组件实现**

```tsx
'use client';

// 法规对照表（ADR 0045 §5）：只呈现证据包内注册表驱动的对照结果，不做任何判定。
import { Badge } from '@/components/ui/badge';
import type { ClauseStatus, RegulatoryMapping } from '@/services/evidence/regulatory-mapping';

export interface RegulatoryMappingLabels {
  notAvailable: string;
  registryVersion: string;
  disclaimer: string;
  columns: { clause: string; title: string; status: string; evidence: string };
  status: Record<ClauseStatus, string>;
}

const BADGE: Record<ClauseStatus, 'success' | 'warning' | 'neutral'> = {
  evidenced: 'success', partial: 'warning', none: 'neutral',
};

type TitleLocale = 'en' | 'zh' | 'de';
const titleLocale = (locale: string): TitleLocale => (locale === 'zh' || locale === 'de' ? locale : 'en');

export function RegulatoryMappingTable({ mapping, locale, labels }: {
  mapping: RegulatoryMapping | null; locale: string; labels: RegulatoryMappingLabels;
}) {
  if (!mapping) return <p className="text-sm text-fg-muted">{labels.notAvailable}</p>;
  const lang = titleLocale(locale);
  return (
    <div className="space-y-4">
      <p className="text-xs text-fg-muted">
        {labels.registryVersion.replace('{version}', mapping.registryVersion)} · {labels.disclaimer}
      </p>
      {mapping.frameworks.map((f) => (
        <table key={f.control} className="w-full text-sm">
          <caption className="text-left font-medium">{f.control}</caption>
          <thead>
            <tr>
              <th className="px-2 py-1 text-left">{labels.columns.clause}</th>
              <th className="px-2 py-1 text-left">{labels.columns.title}</th>
              <th className="px-2 py-1 text-left">{labels.columns.status}</th>
              <th className="px-2 py-1 text-left">{labels.columns.evidence}</th>
            </tr>
          </thead>
          <tbody>
            {f.clauses.map((c) => (
              <tr key={c.clause} className="border-b border-border">
                <td className="px-2 py-1">{c.clause}</td>
                <td className="px-2 py-1">{c.title[lang]}</td>
                <td className="px-2 py-1"><Badge variant={BADGE[c.status]}>{labels.status[c.status]}</Badge></td>
                <td className="px-2 py-1">
                  <details>
                    <summary data-testid={`evidence-count-${c.clause}`}>{c.evidence.length}</summary>
                    <ul>{c.evidence.map((r) => <li key={`${r.executionId}:${r.field}`}>{r.executionId} · {r.field}</li>)}</ul>
                  </details>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </div>
  );
}
```

`Badge` 的导入路径与 `variant` 取值以 `reports-content.tsx` 中既有用法为准（该文件已用 `success`/`danger`/`neutral`；若无 `warning` 变体，partial 用该组件已有的中性以外的一个变体并在注释说明）。`reports-content.tsx` 从 `useTranslations('evidenceExport')` 组装 `labels` 传入。

- [ ] **Step 5: 接入报告列表**

`page.tsx` 行数据加 `schemaVersion: summary?.schemaVersion ?? null`。`reports-content.tsx`：每个已完成行加「对照」按钮；`schemaVersion === '4'` 时点击后 `fetch(/api/reports/${id}/mapping)` 并在该行下方展开 `RegulatoryMappingTable`；其它版本按钮禁用并 `title` 为 notAvailable 文案。

- [ ] **Step 6: 文案（四语，hi 用英文）**

```ts
      mapping: {
        toggle: 'Regulatory mapping',
        loading: 'Loading mapping…',
        notAvailable: 'This export has no regulatory mapping (created before schema 4).',
        registryVersion: 'Controls registry {version}',
        disclaimer: 'Describes only fields present in this bundle; not a compliance conclusion.',
        columns: { clause: 'Clause', title: 'Requirement', status: 'Status', evidence: 'Evidence' },
        status: { evidenced: 'Evidenced', partial: 'Partial', none: 'No evidence' },
      },
```

zh：`法规对照` / `正在加载对照…` / `此导出不含法规对照（早于第 4 版格式）。` / `控制注册表 {version}` / `仅陈述证据包内已有字段，不构成合规结论。` / 列 `条款`/`要求`/`状态`/`证据` / 状态 `已证实`/`部分`/`无证据`。de：`Regulatorische Zuordnung` / `Zuordnung wird geladen…` / `Dieser Export enthält keine regulatorische Zuordnung (vor Schema 4 erstellt).` / `Kontrollregister {version}` / `Beschreibt nur Felder dieses Pakets; keine Compliance-Aussage.` / 列 `Klausel`/`Anforderung`/`Status`/`Nachweis` / 状态 `Belegt`/`Teilweise`/`Kein Nachweis`。

- [ ] **Step 7: 指南与 E2E**

三语指南中「对照表怎么读」一节首段改为说明对照由控制注册表（`@aster-cloud/controls` 1.0.0，ADR 0045）驱动、报告页可直接展开查看；删除「静态表」措辞。`credit-pilot.spec.ts` 导出步骤后：在 `/en/reports` 第一行点 `Regulatory mapping`，断言出现 `14(4)(d)` 行且其状态单元为 `Partial`、`14(5)` 行为 `No evidence`。

- [ ] **Step 8: 运行与提交**

Run: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm exec vitest run src/__tests__/api src/__tests__/components src/__tests__/services/evidence && pnpm check:locales:strict && pnpm docs:index:build && pnpm check:docs-index && pnpm docs:descriptions && pnpm check:docs-descriptions && pnpm exec playwright test --list src/__tests__/e2e-browser/local-stack --project=chromium-desktop`
Expected: PASS（`routes:check` 与 `check:message-keys` 的既有失败不在本任务范围；若新路由需登记到 `config/routes.yaml` 以免新增失败，按该文件邻近条目格式补上）。

```bash
git add src/app src/components/evidence src/i18n/demo-supplement.ts src/__tests__ config/routes.yaml public src/generated 2>/dev/null
git commit -m "feat(reports): 报告页渲染注册表驱动的法规对照（ADR 0045 §5）"
```

---

### Task 9: 全栈验收与 ADR 定稿

**Files:**
- Modify: aster-api `docs/adr/0045-control-registry.md`（ACCEPTED，§9.1 验收记录）
- Modify: aster-api `docs/adr/0039-verdict-primitive-and-rule-metadata.md`（§1 行 5 指向 ADR 0045）
- Modify: aster-api `docs/adr/0044-annex-iii-credit-pilot.md`（§10 中「对照表改由注册表驱动」标记已做）

- [ ] **Step 1: 重建 api 并滚动栈**

```bash
cd aster-api && ./gradlew --offline quarkusBuild -x test && podman build -f Dockerfile.jvm -t localhost/aster/policy-api:stack . \
  && podman rm -f aster-api && podman run -d --name aster-api --network aster-stack -p 8080:8080 --env-file /private/tmp/aster-stack/api.env localhost/aster/policy-api:stack
podman logs aster-api 2>&1 | grep "控制注册表"      # 期望：控制注册表 1.0.0 已加载（5 个控制点）
podman restart aster-cloud
```

- [ ] **Step 2: 编译端点**

```bash
curl -s -X POST http://localhost:8080/api/v1/policies/compile -H 'Content-Type: application/json' \
  -d '{"source":"Module p.\n\n@id(\"X-1\")\n@control(\"ACME:ART1\")\nRule decide given amount, produce Verdict:\n  Return Verdict.allow().\n"}'
```

期望：`success: true`，`controlsRegistryVersion: "1.0.0"`，`diagnostics` 含 `W704`。请求体字段名以该端点的既有请求 DTO 为准。

- [ ] **Step 3: 试点脚本**

清理 `pol-credit-pilot` 的 Execution 后运行 run 脚本（命令见记忆文件 aster-local-verification-env）。期望 exit 0，证据包 `schemaVersion '4'`，`regulatoryMapping.registryVersion '1.0.0'`，九款与期望一致。

- [ ] **Step 4: Playwright 全套**

`BASE_CLOUD=http://localhost:3100 CP_API_KEY=… pnpm exec playwright test src/__tests__/e2e-browser/local-stack --project=chromium-desktop --workers=1`，期望全部通过（既有 1 skip 除外）。

- [ ] **Step 5: 全量门禁**

core `./gradlew --offline test`；ts 仓内完整测试脚本；api 全量（对照已知基线）；cloud 完整门禁（`tsc`、`lint`、`test:run`、`check:locales:strict`、docs 检查）；locales `./gradlew check`。

- [ ] **Step 6: ADR 写回与提交**

ADR 0045 状态改 ACCEPTED 并加 §9.1（命令、结果、日志路径）；0039 §1 行 5 加「→ **ADR 0045**」；0044 §10 对应条目加「已做（ADR 0045）」。

```bash
git add docs/adr/0045-control-registry.md docs/adr/0039-verdict-primitive-and-rule-metadata.md docs/adr/0044-annex-iii-credit-pilot.md
git commit -m "docs(adr): ADR 0045 ACCEPTED，0039/0044 回写"
```

- [ ] **Step 7: 更新记忆文件**

`aster-governance-roadmap-state.md`：子项目 5 状态、各仓分支与提交、发版待办（`@aster-cloud/controls`、aster-lang-ts 新版）。
