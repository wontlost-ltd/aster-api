# Verdict 原语与规则元数据 实施计划（ADR 0039 · 子项目 1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「允许 / 拒绝 / 需人工批准 / 升级」成为 Aster 语言与双引擎运行时的一等值（内置模块 `Verdict`），让规则带稳定身份（`@id`）与控制项（`@control`），并让 aster-api 以类型驱动的确定性规则解读决策。

**Architecture:** 不动 ANTLR 语法与四个词法包。`Verdict.*` 作为内置 stdlib 调用（与 `Text.concat` 同机制）在 Java 核心、TS 引擎、Truffle 运行时三处镜像实现；运行时值为 `__type` 开头的有序记录，进 parity 语料。规则元数据复用既有注解语法，由新的治理检查器在两侧类型检查器中校验。aster-api 在两条评估路径（部署策略 / `evaluate-source`）上附加结构化 `decision`、`ruleId`、`controls`，`DecisionInterpreter` 收缩为三条类型驱动规则。

**Tech Stack:** Java 25 + Gradle（aster-lang-core、aster-lang-truffle、aster-api/Quarkus）；TypeScript + pnpm + node:test（aster-lang-ts）；Node 脚本（aster-lang-test parity）；Next.js + vitest（aster-cloud，仅结果解析器）。

**Spec:** `docs/adr/0039-verdict-primitive-and-rule-metadata.md`（本仓）。本计划对 ADR 的修正见文末「对 ADR 的修正」，执行前先读。

## Global Constraints

- 双引擎逐字节一致：`Verdict` 运行时值键序固定 `__type`、`outcome`、`role`、`reason`，未出现的键不写（不写 null）。
- `MAX_ALLOCATION_BUDGET`、`CHARGE_MAX_DEPTH` 等既有常量不动。
- 新错误码走 `aster-lang-ts/shared/error_codes.json` 单源；Java 资源副本 `aster-lang-core/src/test/resources/diagnostics/error_codes.json` 必须与之逐字节相同。
- 新错误码：`W700 GOV_VERDICT_RULE_MISSING_ID`、`E701 GOV_DUPLICATE_RULE_ID`、`E702 GOV_ANNOTATION_ARG_INVALID`、`E703 GOV_VERDICT_CALL_ARITY`，类别 `governance`。
- 注释与文档使用简体中文，描述意图与约束，不写“修改说明”。
- 每个仓库在分支 `feat/adr-0039-verdict` 上工作；不直接提交到 main；不创建 PR（由用户决定）。
- 仓库间依赖：aster-api 通过 `settings.gradle` 的 `includeBuild` 直接编译同级目录的 core / truffle，无需 `publishToMavenLocal`；aster-lang-test 的 parity 需要 core `publishToMavenLocal` 与 ts `pnpm build`（见 Task 8）。
- 工具路径：pnpm 在 `$HOME/Library/pnpm/pnpm`，node 在 `/opt/homebrew/bin`。TS 命令统一前缀 `export PATH=$HOME/Library/pnpm:/opt/homebrew/bin:$PATH`。macOS 无 `timeout` 命令。

## Review Focus

1. `Verdict.deny("")` 空理由：两引擎运行时都必须失败（不是静默接受），否则「没有理由的拒绝」会进审计链。测试在 Task 6 与 Task 7。
2. `Verdict.deny(42)` 非 Text 参数：类型检查报 `TYPE_MISMATCH`，运行时两引擎都失败而不是把 42 转成 "42"。测试在 Task 2、Task 4、Task 6、Task 7。
3. 一条 Rule 一个分支 `Return Verdict.allow().`、另一分支 `Return true.`：必须报返回类型不一致，不能靠 Unknown 放过。测试在 Task 2 与 Task 4。
4. 用户写 `Define Verdict has …`：两侧都报 `DUPLICATE_SYMBOL`，不能静默遮蔽内置模块。测试在 Task 3 与 Task 5。
5. 旧模板 `Return Decision with approved set to true, reason set to "OK".` 在 DecisionInterpreter 收缩后仍判 APPROVED（第三条规则），而字符串 `"APPROVED"` 变为 INDETERMINATE。测试在 Task 10。

---

### Task 1: 错误码单源登记（aster-lang-ts + aster-lang-core）

**Files:**
- Modify: `aster-lang-ts/shared/error_codes.json`
- Modify: `aster-lang-ts/scripts/validate_error_codes.ts`（类别集合）
- Modify: `aster-lang-ts/scripts/generate_error_codes.ts`（类别类型）
- Modify: `aster-lang-ts/src/diagnostics/error_codes.ts`
- Modify: `aster-lang-core/src/main/java/aster/core/typecheck/ErrorCode.java`
- Modify: `aster-lang-core/src/test/resources/diagnostics/error_codes.json`（逐字节副本）
- Test: `aster-lang-ts/test/unit/diagnostics/error-codes-parity.test.ts`（既有）、`aster-lang-core/src/test/java/aster/core/typecheck/ErrorCodeParityTest.java`（既有）

**Interfaces:**
- Produces: TS `ErrorCode.GOV_VERDICT_RULE_MISSING_ID = "W700"`、`GOV_DUPLICATE_RULE_ID = "E701"`、`GOV_ANNOTATION_ARG_INVALID = "E702"`、`GOV_VERDICT_CALL_ARITY = "E703"`；Java `ErrorCode.GOV_VERDICT_RULE_MISSING_ID` 等四个枚举常量，`Category.GOVERNANCE`。

- [ ] **Step 1: 建分支**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-ts && git checkout -b feat/adr-0039-verdict
cd /Users/rpang/IdeaProjects/aster-lang-core && git checkout -b feat/adr-0039-verdict
```

- [ ] **Step 2: 在 `shared/error_codes.json` 末尾（最后一个条目之后）加四个条目**

```json
  "GOV_VERDICT_RULE_MISSING_ID": {
    "code": "W700",
    "category": "governance",
    "severity": "warning",
    "message": "Rule '{rule}' returns Verdict but has no @id annotation",
    "help": "Add @id(\"RULE-001\") so every decision can be traced to a stable rule identity."
  },
  "GOV_DUPLICATE_RULE_ID": {
    "code": "E701",
    "category": "governance",
    "severity": "error",
    "message": "Duplicate rule @id '{id}' on rules: {rules}",
    "help": "Rule ids must be unique within a module. Give each rule its own @id."
  },
  "GOV_ANNOTATION_ARG_INVALID": {
    "code": "E702",
    "category": "governance",
    "severity": "error",
    "message": "Annotation @{annotation} on rule '{rule}' expects exactly one string literal argument",
    "help": "Write @id(\"RULE-001\"), @reason(\"...\") or @control(\"EU_AI_ACT:ART14\")."
  },
  "GOV_VERDICT_CALL_ARITY": {
    "code": "E703",
    "category": "governance",
    "severity": "error",
    "message": "{func} expects {expected} argument(s), got {actual}",
    "help": "Verdict.allow() takes no arguments; Verdict.deny(reason) and Verdict.escalate(reason) take one Text; Verdict.require_approval(role, reason) takes two Text."
  }
```

- [ ] **Step 3: 放开类别校验**

`aster-lang-ts/scripts/validate_error_codes.ts`：在允许类别集合（含 `'type','effect','capability','async','scope','pii','syntax','semantic'` 的那一行）加入 `'governance'`。
`aster-lang-ts/scripts/generate_error_codes.ts`：在 category 的 TS 联合类型里加入 `'governance'`。

- [ ] **Step 4: 运行校验脚本（预期先失败于 Java 枚举漂移）**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-ts && export PATH=$HOME/Library/pnpm:/opt/homebrew/bin:$PATH
pnpm exec tsx scripts/validate_error_codes.ts
pnpm exec tsx scripts/generate_error_codes.ts
```
预期：validate 通过；generate 的 `--check` 模式报 TS 与 Java 两侧缺少四个新码。（若 `tsx` 不在依赖中，用 `pnpm dlx tsx` 代替。）

- [ ] **Step 5: 手工同步 TS 生成文件**

在 `src/diagnostics/error_codes.ts` 中按既有格式补：`ErrorCode` const enum 四个成员、`ERROR_MESSAGES` 四条 message、`ERROR_METADATA` 四条（code/category/severity/message/help 与 JSON 逐字相同）。

- [ ] **Step 6: 手工同步 Java 枚举与资源副本**

`ErrorCode.java`：
1. `Category` 枚举加 `GOVERNANCE`。
2. 在最后一个枚举常量之后加：
```java
  // 治理（ADR 0039）：E7xx / W7xx 段
  GOV_VERDICT_RULE_MISSING_ID("W700", Category.GOVERNANCE, Severity.WARNING, "Rule '{rule}' returns Verdict but has no @id annotation", "Add @id(\"RULE-001\") so every decision can be traced to a stable rule identity."),
  GOV_DUPLICATE_RULE_ID("E701", Category.GOVERNANCE, Severity.ERROR, "Duplicate rule @id '{id}' on rules: {rules}", "Rule ids must be unique within a module. Give each rule its own @id."),
  GOV_ANNOTATION_ARG_INVALID("E702", Category.GOVERNANCE, Severity.ERROR, "Annotation @{annotation} on rule '{rule}' expects exactly one string literal argument", "Write @id(\"RULE-001\"), @reason(\"...\") or @control(\"EU_AI_ACT:ART14\")."),
  GOV_VERDICT_CALL_ARITY("E703", Category.GOVERNANCE, Severity.ERROR, "{func} expects {expected} argument(s), got {actual}", "Verdict.allow() takes no arguments; Verdict.deny(reason) and Verdict.escalate(reason) take one Text; Verdict.require_approval(role, reason) takes two Text."),
```
资源副本：
```bash
cp /Users/rpang/IdeaProjects/aster-lang-ts/shared/error_codes.json /Users/rpang/IdeaProjects/aster-lang-core/src/test/resources/diagnostics/error_codes.json
cmp /Users/rpang/IdeaProjects/aster-lang-ts/shared/error_codes.json /Users/rpang/IdeaProjects/aster-lang-core/src/test/resources/diagnostics/error_codes.json && echo identical
```

- [ ] **Step 7: 跑两侧 parity 测试**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-ts && pnpm exec tsx scripts/generate_error_codes.ts && pnpm build && node --test dist/test/unit/diagnostics/error-codes-parity.test.js
cd /Users/rpang/IdeaProjects/aster-lang-core && ./gradlew --offline test --tests 'aster.core.typecheck.ErrorCodeParityTest'
```
预期：generate `--check` 零漂移；两侧 parity 测试通过。

- [ ] **Step 8: 提交（两仓各一次）**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-ts && git add shared/error_codes.json scripts/validate_error_codes.ts scripts/generate_error_codes.ts src/diagnostics/error_codes.ts && git commit -m "feat(diagnostics): 治理错误码 W700/E701/E702/E703（ADR 0039）"
cd /Users/rpang/IdeaProjects/aster-lang-core && git add src/main/java/aster/core/typecheck/ErrorCode.java src/test/resources/diagnostics/error_codes.json && git commit -m "feat(typecheck): 治理错误码 W700/E701/E702/E703 与 GOVERNANCE 类别（ADR 0039）"
```

---

### Task 2: Java 类型检查识别 `Verdict` 类型与 `Verdict.*` 调用（aster-lang-core）

**Files:**
- Modify: `aster-lang-core/src/main/java/aster/core/parser/AstBuilder.java:29-31`（`BUILTIN_TYPE_NAMES`）
- Modify: `aster-lang-core/src/main/java/aster/core/typecheck/BuiltinTypes.java`
- Modify: `aster-lang-core/src/main/java/aster/core/typecheck/checkers/BaseTypeChecker.java:282-388`（`checkCall`）
- Create: `aster-lang-core/src/main/java/aster/core/typecheck/governance/VerdictSignatures.java`
- Test: `aster-lang-core/src/test/java/aster/core/typecheck/governance/VerdictTypeCheckTest.java`

**Interfaces:**
- Produces: `VerdictSignatures.TYPE_NAME = "Verdict"`；`VerdictSignatures.arityOf(String qualifiedName)` 返回 `OptionalInt`（allow→0，deny→1，escalate→1，require_approval→2，其他→empty）；`VerdictSignatures.isVerdictCall(String name)`。
- Consumes: Task 1 的 `ErrorCode.GOV_VERDICT_CALL_ARITY`。

- [ ] **Step 1: 写失败测试**

`VerdictTypeCheckTest.java`（照抄 `DecimalMixingTypeCheckTest` 的编译链）：

```java
package aster.core.typecheck.governance;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.TypeChecker;
import aster.core.typecheck.model.Diagnostic;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verdict 内置模块的类型检查（ADR 0039 §2）。 */
class VerdictTypeCheckTest {

  private static List<Diagnostic> diagnose(String body) {
    String src = "Module probe.\n\n" + body + "\n";
    String canonical = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canonical));
    var tokens = new CommonTokenStream(lexer); tokens.fill(); tokens.seek(0);
    var parser = new AsterParser(tokens); parser.removeErrorListeners();
    var ast = new AstBuilder().visitModule(parser.module());
    var core = new CoreLowering().lowerModule(ast);
    return new TypeChecker().typecheckModule(core);
  }

  private static boolean has(List<Diagnostic> d, ErrorCode code) {
    return d.stream().anyMatch(x -> x.code() == code);
  }

  @Test void produceVerdict不被当成类型参数() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertFalse(has(diags, ErrorCode.RETURN_TYPE_MISMATCH), diags.toString());
    assertFalse(diags.stream().anyMatch(x -> x.severity() == aster.core.typecheck.model.Severity.ERROR), diags.toString());
  }

  @Test void produceBool返回Verdict报类型不匹配() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Bool:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.RETURN_TYPE_MISMATCH), diags.toString());
  }

  @Test void 两个分支类型不一致报错() {
    var diags = diagnose("@id(\"R-1\")\nRule main given x, produce Verdict:\n  If x equals to 1:\n    Return Verdict.allow().\n  Otherwise:\n    Return true.");
    assertTrue(has(diags, ErrorCode.RETURN_TYPE_MISMATCH), diags.toString());
  }

  @Test void deny缺参数报E703() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.deny().");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_CALL_ARITY), diags.toString());
  }

  @Test void allow多参数报E703() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow(\"x\").");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_CALL_ARITY), diags.toString());
  }

  @Test void deny非Text参数报类型不匹配() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.deny(42).");
    assertTrue(has(diags, ErrorCode.TYPE_MISMATCH), diags.toString());
  }

  @Test void requireApproval两个Text通过() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.require_approval(\"Senior Underwriter\", \"amount over cap\").");
    assertFalse(diags.stream().anyMatch(x -> x.severity() == aster.core.typecheck.model.Severity.ERROR), diags.toString());
  }
}
```

如果 `Diagnostic` 的 severity 枚举不叫 `aster.core.typecheck.model.Severity`，以 `Diagnostic` record 的实际字段类型为准改 import，不改断言语义。

- [ ] **Step 2: 运行确认失败**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-core && ./gradlew --offline test --tests 'aster.core.typecheck.governance.VerdictTypeCheckTest'
```
预期：`produceBool…`、`两个分支…`、`deny缺参数…`、`allow多参数…`、`deny非Text…` 失败（今天 `Verdict.*` 类型为 Unknown，什么都放过；`produce Verdict` 被当作类型参数）。

- [ ] **Step 3: 新建 `VerdictSignatures`**

```java
package aster.core.typecheck.governance;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Verdict 内置模块的签名表（ADR 0039 §2.2）。
 * 两引擎共享同一份事实：构造函数名、参数个数、参数全部为 Text、返回 Verdict。
 * TS 侧镜像：aster-lang-ts/src/typecheck/verdict_signatures.ts。
 */
public final class VerdictSignatures {
  private VerdictSignatures() {}

  public static final String TYPE_NAME = "Verdict";
  public static final String PREFIX = "Verdict.";

  private static final Map<String, Integer> ARITY = Map.of(
      "Verdict.allow", 0,
      "Verdict.deny", 1,
      "Verdict.require_approval", 2,
      "Verdict.escalate", 1);

  public static boolean isVerdictCall(String qualifiedName) {
    return qualifiedName != null && ARITY.containsKey(qualifiedName);
  }

  public static boolean hasVerdictPrefix(String qualifiedName) {
    return qualifiedName != null && qualifiedName.startsWith(PREFIX);
  }

  public static OptionalInt arityOf(String qualifiedName) {
    Integer n = ARITY.get(qualifiedName);
    return n == null ? OptionalInt.empty() : OptionalInt.of(n);
  }
}
```

- [ ] **Step 4: `Verdict` 进两份内置类型名表**

`AstBuilder.java:29-31`：`BUILTIN_TYPE_NAMES` 加 `"Verdict"`（这样 `produce Verdict` 不再被 `collectTypeParamCandidates` 推成类型参数）。
`BuiltinTypes.java`：加 `public static final String VERDICT = "Verdict";` 并纳入 `isPrimitiveType`。

- [ ] **Step 5: 在 `BaseTypeChecker.checkCall` 加 Verdict 特判**

在 `Decimal.round` / `Decimal.divide` 特判之后、`funcType instanceof FuncType` 判断之前插入：

```java
    // Verdict 内置模块（ADR 0039）：返回 Verdict 名义类型，参数全部 Text，个数固定。
    if (call.target instanceof CoreModel.Name vn && VerdictSignatures.hasVerdictPrefix(vn.name)) {
      var arity = VerdictSignatures.arityOf(vn.name);
      if (arity.isEmpty()) {
        diagnostics.undefinedVariable(vn.name, Optional.ofNullable(call.origin));
        return TypeSystem.unknown();
      }
      int actual = call.args == null ? 0 : call.args.size();
      if (actual != arity.getAsInt()) {
        diagnostics.error(ErrorCode.GOV_VERDICT_CALL_ARITY, Optional.ofNullable(call.origin),
            Map.of("func", vn.name, "expected", arity.getAsInt(), "actual", actual));
      } else {
        var text = TypeSystem.createTypeName("String");
        for (var arg : call.args) {
          var argType = typeOfExpr(arg, ctx);
          if (!TypeSystem.equals(argType, text, false)) {
            diagnostics.typeMismatch(text, argType, Optional.ofNullable(arg.origin));
          }
        }
      }
      return TypeSystem.createTypeName(VerdictSignatures.TYPE_NAME);
    }
```

`typeOfExpr` / `createTypeName` / `TypeSystem.equals` 的实际签名以 `BaseTypeChecker` 既有代码为准（文件里 Decimal 特判就在用它们），只改调用形式不改语义。`Text` 在 Java 侧是 `String` 的别名（`defineBuiltinTypeAliases`），所以这里比较 `"String"`。

- [ ] **Step 6: 运行测试直到通过**

```bash
./gradlew --offline test --tests 'aster.core.typecheck.governance.VerdictTypeCheckTest'
```
预期：7 个全部通过。若 `两个分支类型不一致报错` 仍失败，说明返回类型合并对 Unknown 宽容，检查 `TypeChecker` 对多 Return 的 join 逻辑是否已拿到 `Verdict` 而非 Unknown；修到通过，不要放宽断言。

- [ ] **Step 7: 全量回归 + 黄金测试**

```bash
./gradlew --offline test goldenTest
```
预期：全绿。`goldenTest` 若因 `BUILTIN_TYPE_NAMES` 变化导致某个语料 `Verdict` 名字从 TypeVar 变 TypeName 而失败，说明语料里已有人用 `Verdict` 当泛型名，列出来并在计划末尾「对 ADR 的修正」登记；不改黄金文件。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/aster/core/parser/AstBuilder.java src/main/java/aster/core/typecheck/BuiltinTypes.java src/main/java/aster/core/typecheck/checkers/BaseTypeChecker.java src/main/java/aster/core/typecheck/governance/VerdictSignatures.java src/test/java/aster/core/typecheck/governance/VerdictTypeCheckTest.java
git commit -m "feat(typecheck): Verdict 内置类型与 Verdict.* 调用签名检查（ADR 0039）"
```

---

### Task 3: Java 治理检查器：`@id` / `@reason` / `@control` 与 W700 / E701 / E702（aster-lang-core）

**Files:**
- Create: `aster-lang-core/src/main/java/aster/core/typecheck/governance/GovernanceChecker.java`
- Modify: `aster-lang-core/src/main/java/aster/core/typecheck/TypeChecker.java:49-62`（构造字段）、`:145-158`（接线）、`collectTypeDefinitions`（预占 `Verdict` 符号）
- Test: `aster-lang-core/src/test/java/aster/core/typecheck/governance/GovernanceCheckerTest.java`

**Interfaces:**
- Produces: `GovernanceChecker.checkModule(CoreModel.Module)` → `List<Diagnostic>`；静态工具 `GovernanceChecker.ruleId(CoreModel.Func)` → `Optional<String>`、`GovernanceChecker.controls(CoreModel.Func)` → `List<String>`、`GovernanceChecker.returnsVerdict(CoreModel.Func)` → `boolean`。aster-api（Task 9）复用这三个静态方法。
- Consumes: Task 1 错误码；Task 2 的 `VerdictSignatures`。

- [ ] **Step 1: 写失败测试**

```java
package aster.core.typecheck.governance;

// import 与 VerdictTypeCheckTest 相同的编译链工具
class GovernanceCheckerTest {
  // diagnose(...) / has(...) 与 VerdictTypeCheckTest 相同（复制，不要共享父类）

  @Test void 返回Verdict且无id报W700() {
    var diags = diagnose("Rule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 未声明返回类型但返回Verdict调用也报W700() {
    var diags = diagnose("Rule main produce:\n  Return Verdict.deny(\"no\").");
    assertTrue(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 有id不报W700() {
    var diags = diagnose("@id(\"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertFalse(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 返回Bool无id不报W700() {
    var diags = diagnose("Rule main produce Bool:\n  Return true.");
    assertFalse(has(diags, ErrorCode.GOV_VERDICT_RULE_MISSING_ID), diags.toString());
  }

  @Test void 重复id报E701() {
    var diags = diagnose("@id(\"R-1\")\nRule a produce Verdict:\n  Return Verdict.allow().\n\n@id(\"R-1\")\nRule b produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_DUPLICATE_RULE_ID), diags.toString());
  }

  @Test void id非字符串报E702() {
    var diags = diagnose("@id(42)\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_ANNOTATION_ARG_INVALID), diags.toString());
  }

  @Test void id命名参数也报E702() {
    var diags = diagnose("@id(value: \"R-1\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertTrue(has(diags, ErrorCode.GOV_ANNOTATION_ARG_INVALID), diags.toString());
  }

  @Test void control可重复() {
    var diags = diagnose("@id(\"R-1\")\n@control(\"EU_AI_ACT:ART14\")\n@control(\"GDPR:ART6\")\nRule main produce Verdict:\n  Return Verdict.allow().");
    assertFalse(diags.stream().anyMatch(x -> x.code().category() == ErrorCode.Category.GOVERNANCE), diags.toString());
  }

  @Test void 用户定义Verdict类型报重复符号() {
    var diags = diagnose("Define Verdict has approved.\n\nRule main produce Bool:\n  Return true.");
    assertTrue(has(diags, ErrorCode.DUPLICATE_SYMBOL), diags.toString());
  }

  @Test void 静态工具读取元数据() {
    // 直接构造 Func：annotations = [id("R-9"), control("A"), control("B")]
    var f = new aster.core.ir.CoreModel.Func();
    var id = new aster.core.ir.CoreModel.Annotation(); id.name = "id"; id.params = java.util.Map.of("$0", "R-9");
    var c1 = new aster.core.ir.CoreModel.Annotation(); c1.name = "control"; c1.params = java.util.Map.of("$0", "A");
    var c2 = new aster.core.ir.CoreModel.Annotation(); c2.name = "control"; c2.params = java.util.Map.of("$0", "B");
    f.annotations = java.util.List.of(id, c1, c2);
    assertEquals(java.util.Optional.of("R-9"), GovernanceChecker.ruleId(f));
    assertEquals(java.util.List.of("A", "B"), GovernanceChecker.controls(f));
  }
}
```

`ErrorCode.category()` 访问器名以 `ErrorCode.java` 实际为准。

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew --offline test --tests 'aster.core.typecheck.governance.GovernanceCheckerTest'
```
预期：编译失败（`GovernanceChecker` 不存在）。

- [ ] **Step 3: 实现 `GovernanceChecker`**

```java
package aster.core.typecheck.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import aster.core.typecheck.model.Severity;

import java.util.*;

/**
 * 治理检查器（ADR 0039 §3/§4）：规则元数据注解与 Verdict 规则身份。
 * 与 TS 侧 aster-lang-ts/src/typecheck/governance.ts 逐条对等。
 *
 * 判定「规则返回 Verdict」的口径，两引擎一致：
 *   声明返回类型为 TypeName Verdict，或函数体任一 Return 的表达式是 Verdict.* 的直接调用。
 */
public final class GovernanceChecker {
  private static final Set<String> STRING_ARG_ANNOTATIONS = Set.of("id", "reason", "control");

  public List<Diagnostic> checkModule(CoreModel.Module module) {
    List<Diagnostic> out = new ArrayList<>();
    if (module == null || module.decls == null) return out;
    Map<String, List<String>> idOwners = new LinkedHashMap<>();
    for (CoreModel.Decl decl : module.decls) {
      if (!(decl instanceof CoreModel.Func func)) continue;
      for (CoreModel.Annotation a : annotationsOf(func)) {
        if (!STRING_ARG_ANNOTATIONS.contains(a.name)) continue;
        if (!isSingleStringArg(a)) {
          out.add(diag(ErrorCode.GOV_ANNOTATION_ARG_INVALID, func.origin,
              Map.of("annotation", a.name, "rule", String.valueOf(func.name))));
        }
      }
      Optional<String> id = ruleId(func);
      if (id.isPresent()) {
        idOwners.computeIfAbsent(id.get(), k -> new ArrayList<>()).add(func.name);
      } else if (returnsVerdict(func)) {
        out.add(diag(ErrorCode.GOV_VERDICT_RULE_MISSING_ID, func.origin, Map.of("rule", String.valueOf(func.name))));
      }
    }
    for (var e : idOwners.entrySet()) {
      if (e.getValue().size() > 1) {
        out.add(diag(ErrorCode.GOV_DUPLICATE_RULE_ID, firstOrigin(module, e.getValue().get(0)),
            Map.of("id", e.getKey(), "rules", String.join(", ", e.getValue()))));
      }
    }
    return out;
  }

  /** 规则 @id：恰好一个位置字符串参数时返回其值；否则 empty（E702 另行报告）。 */
  public static Optional<String> ruleId(CoreModel.Func func) {
    for (CoreModel.Annotation a : annotationsOf(func)) {
      if ("id".equals(a.name) && isSingleStringArg(a)) return Optional.of((String) a.params.get("$0"));
    }
    return Optional.empty();
  }

  /** 规则 @control 列表，按源码顺序；非法参数的条目跳过。 */
  public static List<String> controls(CoreModel.Func func) {
    List<String> out = new ArrayList<>();
    for (CoreModel.Annotation a : annotationsOf(func)) {
      if ("control".equals(a.name) && isSingleStringArg(a)) out.add((String) a.params.get("$0"));
    }
    return out;
  }

  public static boolean returnsVerdict(CoreModel.Func func) {
    if (func.ret instanceof CoreModel.TypeName tn && VerdictSignatures.TYPE_NAME.equals(tn.name)) return true;
    return func.body != null && anyReturnIsVerdictCall(func.body);
  }

  private static boolean anyReturnIsVerdictCall(CoreModel.Stmt stmt) {
    if (stmt instanceof CoreModel.Return r) {
      return r.expr instanceof CoreModel.Call c && c.target instanceof CoreModel.Name n
          && VerdictSignatures.isVerdictCall(n.name);
    }
    if (stmt instanceof CoreModel.Block b && b.statements != null) {
      for (var s : b.statements) if (anyReturnIsVerdictCall(s)) return true;
      return false;
    }
    if (stmt instanceof CoreModel.If i) {
      return (i.thenBlock != null && anyReturnIsVerdictCall(i.thenBlock))
          || (i.elseBlock != null && anyReturnIsVerdictCall(i.elseBlock));
    }
    if (stmt instanceof CoreModel.Match m && m.cases != null) {
      for (var cs : m.cases) if (cs.body != null && anyReturnIsVerdictCall(cs.body)) return true;
    }
    return false;
  }

  private static boolean isSingleStringArg(CoreModel.Annotation a) {
    return a.params != null && a.params.size() == 1 && a.params.get("$0") instanceof String;
  }

  private static List<CoreModel.Annotation> annotationsOf(CoreModel.Func f) {
    return f.annotations == null ? List.of() : f.annotations;
  }

  private static CoreModel.Origin firstOrigin(CoreModel.Module module, String funcName) {
    for (var d : module.decls) if (d instanceof CoreModel.Func f && funcName.equals(f.name)) return f.origin;
    return null;
  }

  private static Diagnostic diag(ErrorCode code, CoreModel.Origin origin, Map<String, Object> data) {
    Severity sev = code.severity();
    return new Diagnostic(sev, code, code.render(data), Optional.ofNullable(origin), Optional.ofNullable(code.help()), Map.copyOf(data));
  }
}
```

`CoreModel` 中 `Stmt` / `Block` / `If` / `Match` / `Return` 的字段名（`statements`、`thenBlock`、`elseBlock`、`cases`、`body`、`expr`）以 `CoreModel.java` 实际定义为准调整；`Diagnostic` 构造参数顺序照 `CapabilityChecker.emit`。

- [ ] **Step 4: 接线与 `Verdict` 符号预占**

`TypeChecker.java`：
1. 字段 `private final GovernanceChecker governanceChecker = new GovernanceChecker();`
2. 在 `baseDiagnostics.addAll(piiChecker.checkModule(funcs));` 之后加 `baseDiagnostics.addAll(governanceChecker.checkModule(module));`
3. 在 `collectTypeDefinitions`（或 `defineBuiltinTypeAliases`）里，在遍历用户 `Data`/`Enum` 声明**之前**，把 `Verdict` 以 DATA_TYPE 身份 `defineOrReportDuplicate` 进符号表（origin 为 null）。这样用户 `Define Verdict …` 走既有 `DUPLICATE_SYMBOL` 路径。

- [ ] **Step 5: 运行测试直到通过**

```bash
./gradlew --offline test --tests 'aster.core.typecheck.governance.*'
```
预期：全部通过。

- [ ] **Step 6: 全量回归**

```bash
./gradlew --offline test goldenTest
```
预期：全绿。特别注意既有语料是否出现新的 W700（返回 `Verdict.*` 的语料此前不存在，应为零）。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/aster/core/typecheck/governance/GovernanceChecker.java src/main/java/aster/core/typecheck/TypeChecker.java src/test/java/aster/core/typecheck/governance/GovernanceCheckerTest.java
git commit -m "feat(typecheck): 治理检查器 @id/@reason/@control 与 W700/E701/E702（ADR 0039）"
```

---

### Task 4: TS 解析器与类型检查识别 `Verdict`、位置参数注解（aster-lang-ts）

**Files:**
- Modify: `aster-lang-ts/src/parser/decl-parser.ts:99-125`（`parseAnnotationArgs`）、`:465-512`（`BUILTINS`）
- Create: `aster-lang-ts/src/typecheck/verdict_signatures.ts`
- Modify: `aster-lang-ts/src/typecheck/expression.ts:385-400`
- Modify: `aster-lang-ts/src/typecheck/type_system.ts:532-551`
- Modify: `aster-lang-ts/src/effects/effect_inference.ts:40-50`
- Modify: `aster-lang-ts/src/lsp/completion.ts:232`、`aster-lang-ts/src/lsp/utils.ts:33-37`
- Test: `aster-lang-ts/test/unit/governance/verdict-typecheck.test.ts`、`aster-lang-ts/test/unit/parser/positional-annotation-args.test.ts`

**Interfaces:**
- Produces: `verdict_signatures.ts` 导出 `VERDICT_TYPE_NAME = 'Verdict'`、`VERDICT_PREFIX = 'Verdict.'`、`VERDICT_ARITY: ReadonlyMap<string, number>`、`isVerdictCall(name)`、`hasVerdictPrefix(name)`、`verdictArity(name): number | undefined`。
- 位置参数注解在 TS AST 中为 `args: [{ name: '$0', value: 'PII-001' }]`，与 Java `params: {"$0": "PII-001"}` 键名一致。

- [ ] **Step 1: 写失败测试（解析器）**

`test/unit/parser/positional-annotation-args.test.ts`：

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonicalize } from '../../../src/frontend/canonicalizer.js';
import { lex } from '../../../src/frontend/lexer.js';
import { parse } from '../../../src/parser.js';
import { lowerModule } from '../../../src/lower_to_core.js';
import type { Module as AstModule } from '../../../src/ast.js';

function lower(src: string) {
  const ast = parse(lex(canonicalize(src))).ast as AstModule;
  return lowerModule(ast);
}

test('位置字符串参数注解降为 $0 键', () => {
  const core = lower('Module probe.\n@id("PII-001")\nRule main produce Bool:\n  Return true.\n');
  const fn = core.decls.find((d) => d.kind === 'Func') as { annotations?: { name: string; args?: { name: string; value: unknown }[] }[] };
  assert.deepEqual(fn.annotations, [{ name: 'id', args: [{ name: '$0', value: 'PII-001' }] }]);
});

test('多个位置参数依次编号', () => {
  const core = lower('Module probe.\n@tag("a", "b")\nRule main produce Bool:\n  Return true.\n');
  const fn = core.decls.find((d) => d.kind === 'Func') as { annotations?: { args?: { name: string }[] }[] };
  assert.deepEqual(fn.annotations![0]!.args!.map((a) => a.name), ['$0', '$1']);
});

test('命名参数仍按原名保留', () => {
  const core = lower('Module probe.\n@id(value: "X")\nRule main produce Bool:\n  Return true.\n');
  const fn = core.decls.find((d) => d.kind === 'Func') as { annotations?: { args?: { name: string }[] }[] };
  assert.deepEqual(fn.annotations![0]!.args!.map((a) => a.name), ['value']);
});
```

import 路径以 `test/unit/decimal-mixing-typecheck.test.ts` 的实际写法为准。

- [ ] **Step 2: 写失败测试（类型检查）**

`test/unit/governance/verdict-typecheck.test.ts`：

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonicalize } from '../../../src/frontend/canonicalizer.js';
import { lex } from '../../../src/frontend/lexer.js';
import { parse } from '../../../src/parser.js';
import { lowerModule } from '../../../src/lower_to_core.js';
import { typecheckModule } from '../../../src/typecheck.js';
import { ErrorCode } from '../../../src/diagnostics/error_codes.js';
import type { Module as AstModule } from '../../../src/ast.js';

function diagnose(body: string): string[] {
  const src = `Module probe.\n${body}\n`;
  const ast = parse(lex(canonicalize(src))).ast as AstModule;
  const core = lowerModule(ast);
  return typecheckModule(core).map((d) => String(d.code));
}

test('produce Verdict 不被当成类型参数，allow 通过', () => {
  const codes = diagnose('@id("R-1")\nRule main produce Verdict:\n  Return Verdict.allow().');
  assert.ok(!codes.includes(ErrorCode.RETURN_TYPE_MISMATCH), codes.join(','));
  assert.ok(!codes.includes(ErrorCode.EFF_INFER_UNKNOWN_BUILTIN), codes.join(','));
});

test('produce Bool 返回 Verdict 报 E003', () => {
  const codes = diagnose('@id("R-1")\nRule main produce Bool:\n  Return Verdict.allow().');
  assert.ok(codes.includes(ErrorCode.RETURN_TYPE_MISMATCH), codes.join(','));
});

test('两个分支类型不一致报错', () => {
  const codes = diagnose('@id("R-1")\nRule main given x, produce Verdict:\n  If x equals to 1:\n    Return Verdict.allow().\n  Otherwise:\n    Return true.');
  assert.ok(codes.includes(ErrorCode.RETURN_TYPE_MISMATCH), codes.join(','));
});

test('deny 缺参数报 E703', () => {
  const codes = diagnose('@id("R-1")\nRule main produce Verdict:\n  Return Verdict.deny().');
  assert.ok(codes.includes(ErrorCode.GOV_VERDICT_CALL_ARITY), codes.join(','));
});

test('deny 非 Text 参数报类型不匹配', () => {
  const codes = diagnose('@id("R-1")\nRule main produce Verdict:\n  Return Verdict.deny(42).');
  assert.ok(codes.includes(ErrorCode.TYPE_MISMATCH), codes.join(','));
});

test('require_approval 两个 Text 通过', () => {
  const codes = diagnose('@id("R-1")\nRule main produce Verdict:\n  Return Verdict.require_approval("Senior Underwriter", "over cap").');
  assert.equal(codes.filter((c) => c.startsWith('E')).length, 0, codes.join(','));
});
```

- [ ] **Step 3: 运行确认失败**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-ts && export PATH=$HOME/Library/pnpm:/opt/homebrew/bin:$PATH
pnpm build && node --test dist/test/unit/parser/positional-annotation-args.test.js dist/test/unit/governance/verdict-typecheck.test.js
```
预期：解析测试报 `Expected annotation argument name`；类型测试多数失败。

- [ ] **Step 4: 解析器接受位置参数**

`decl-parser.ts` `parseAnnotationArgs`：当当前 token 不是 `IDENT` 后跟 `COLON` 时，按位置参数解析：`value = parseAnnotationValue()`，`name = '$' + positionalIndex++`。命名参数路径不变。允许混用（与 Java `visitAnnotation` 一致）。

`decl-parser.ts:467` `BUILTINS` 加 `'Verdict'`。

- [ ] **Step 5: 新建 `verdict_signatures.ts`**

```ts
/**
 * Verdict 内置模块签名表（ADR 0039 §2.2）。
 * Java 侧镜像：aster-lang-core/.../typecheck/governance/VerdictSignatures.java，两份必须同值。
 */
export const VERDICT_TYPE_NAME = 'Verdict';
export const VERDICT_PREFIX = 'Verdict.';

export const VERDICT_ARITY: ReadonlyMap<string, number> = new Map([
  ['Verdict.allow', 0],
  ['Verdict.deny', 1],
  ['Verdict.require_approval', 2],
  ['Verdict.escalate', 1],
]);

export function isVerdictCall(name: string): boolean { return VERDICT_ARITY.has(name); }
export function hasVerdictPrefix(name: string): boolean { return name.startsWith(VERDICT_PREFIX); }
export function verdictArity(name: string): number | undefined { return VERDICT_ARITY.get(name); }
```

- [ ] **Step 6: 类型检查特判**

`expression.ts` 在 `Decimal.round` 特判之后、`module.funcSignatures.get` 之前：

```ts
      if (hasVerdictPrefix(expression.target.name)) {
        const expected = verdictArity(expression.target.name);
        if (expected === undefined) {
          diagnostics.undefinedVariable(expression.target.name, expression.origin);
          this.result = unknownType(); this.handled = true; return;
        }
        const actual = expression.args.length;
        if (actual !== expected) {
          diagnostics.error(ErrorCode.GOV_VERDICT_CALL_ARITY, expression.origin,
            { func: expression.target.name, expected, actual });
        } else {
          const text = { kind: 'TypeName', name: 'Text' } as Core.TypeName;
          for (const arg of expression.args) {
            const t = typeOfExpr(module, symbols, arg, diagnostics);
            if (!isUnknown(t) && !TypeSystem.equals(t, text)) diagnostics.typeMismatch(text, t, arg.origin);
          }
        }
        this.result = { kind: 'TypeName', name: VERDICT_TYPE_NAME } as Core.TypeName;
        this.handled = true; return;
      }
```

`diagnostics` 在该文件里的实际类型若是数组而非 `DiagnosticBuilder`，改用该文件既有的 push 写法（照 `AMBIGUOUS_INTEROP_NUMERIC` 那段）。

`type_system.ts` `inferStaticType` 的 Call switch 前加：`if (hasVerdictPrefix(expr.target.name)) return { kind: 'TypeName', name: 'Verdict' };`

`effect_inference.ts` `PURE_STDLIB_NAMESPACES` 加 `'Verdict'`。

`lsp/completion.ts:232` types 数组加 `'Verdict'`；`lsp/utils.ts:33-37` `exprTypeText` 对 `Verdict.` 前缀返回 `'Verdict'`。

- [ ] **Step 7: 运行测试直到通过，再全量**

```bash
pnpm typecheck && pnpm lint && pnpm test:unit
```
预期：新测试通过；既有 `test/unit/parser/entry-annotations.test.ts` 不受影响（命名参数路径未变）。

- [ ] **Step 8: 提交**

```bash
git add src/parser/decl-parser.ts src/typecheck/verdict_signatures.ts src/typecheck/expression.ts src/typecheck/type_system.ts src/effects/effect_inference.ts src/lsp/completion.ts src/lsp/utils.ts test/unit/parser/positional-annotation-args.test.ts test/unit/governance/verdict-typecheck.test.ts
git commit -m "feat(typecheck): Verdict 内置类型、Verdict.* 签名检查、位置参数注解（ADR 0039）"
```

---

### Task 5: TS 治理检查：W700 / E701 / E702 与 `Define Verdict` 冲突（aster-lang-ts）

**Files:**
- Create: `aster-lang-ts/src/typecheck/governance.ts`
- Modify: `aster-lang-ts/src/typecheck/module.ts:134-135`、`aster-lang-ts/src/typecheck/browser.ts:317`（两条路径都接线）
- Test: `aster-lang-ts/test/unit/governance/governance-check.test.ts`

**Interfaces:**
- Produces: `checkGovernance(decls: readonly Core.Decl[]): TypecheckDiagnostic[]`；`ruleId(func: Core.Func): string | undefined`；`controls(func: Core.Func): string[]`；`returnsVerdict(func: Core.Func): boolean`。
- Consumes: Task 4 的 `verdict_signatures.ts`。

- [ ] **Step 1: 写失败测试**

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
// diagnose(body) 与 verdict-typecheck.test.ts 相同（复制）
import { ErrorCode } from '../../../src/diagnostics/error_codes.js';

test('返回 Verdict 且无 @id 报 W700', () => {
  assert.ok(diagnose('Rule main produce Verdict:\n  Return Verdict.allow().').includes(ErrorCode.GOV_VERDICT_RULE_MISSING_ID));
});
test('未声明返回类型但返回 Verdict 调用也报 W700', () => {
  assert.ok(diagnose('Rule main produce:\n  Return Verdict.deny("no").').includes(ErrorCode.GOV_VERDICT_RULE_MISSING_ID));
});
test('有 @id 不报 W700', () => {
  assert.ok(!diagnose('@id("R-1")\nRule main produce Verdict:\n  Return Verdict.allow().').includes(ErrorCode.GOV_VERDICT_RULE_MISSING_ID));
});
test('返回 Bool 不报 W700', () => {
  assert.ok(!diagnose('Rule main produce Bool:\n  Return true.').includes(ErrorCode.GOV_VERDICT_RULE_MISSING_ID));
});
test('重复 @id 报 E701', () => {
  assert.ok(diagnose('@id("R-1")\nRule a produce Verdict:\n  Return Verdict.allow().\n\n@id("R-1")\nRule b produce Verdict:\n  Return Verdict.allow().').includes(ErrorCode.GOV_DUPLICATE_RULE_ID));
});
test('@id 非字符串报 E702', () => {
  assert.ok(diagnose('@id(42)\nRule main produce Verdict:\n  Return Verdict.allow().').includes(ErrorCode.GOV_ANNOTATION_ARG_INVALID));
});
test('@id 命名参数报 E702', () => {
  assert.ok(diagnose('@id(value: "R-1")\nRule main produce Verdict:\n  Return Verdict.allow().').includes(ErrorCode.GOV_ANNOTATION_ARG_INVALID));
});
test('@control 可重复', () => {
  const codes = diagnose('@id("R-1")\n@control("EU_AI_ACT:ART14")\n@control("GDPR:ART6")\nRule main produce Verdict:\n  Return Verdict.allow().');
  assert.equal(codes.filter((c) => c === 'W700' || c === 'E701' || c === 'E702').length, 0, codes.join(','));
});
test('用户 Define Verdict 报 DUPLICATE_SYMBOL', () => {
  assert.ok(diagnose('Define Verdict has approved.\n\nRule main produce Bool:\n  Return true.').includes(ErrorCode.DUPLICATE_SYMBOL));
});
```

- [ ] **Step 2: 运行确认失败**

```bash
pnpm build && node --test dist/test/unit/governance/governance-check.test.js
```

- [ ] **Step 3: 实现 `governance.ts`**

```ts
/**
 * 治理检查（ADR 0039 §3/§4）。Java 镜像：aster-lang-core/.../governance/GovernanceChecker.java。
 * 「规则返回 Verdict」口径：声明返回类型为 TypeName Verdict，或任一 Return 表达式是 Verdict.* 直接调用。
 */
import type * as Core from '../core/core_ir_types.js'; // 以 pure.ts 的实际 import 为准
import { ErrorCode } from '../diagnostics/error_codes.js';
import { DiagnosticBuilder } from './diagnostics.js';
import type { TypecheckDiagnostic } from './diagnostics.js';
import { VERDICT_TYPE_NAME, isVerdictCall } from './verdict_signatures.js';

const STRING_ARG_ANNOTATIONS = new Set(['id', 'reason', 'control']);

function annotationsOf(f: Core.Func) { return f.annotations ?? []; }

function isSingleStringArg(a: { args?: readonly { name: string; value: unknown }[] }): boolean {
  return !!a.args && a.args.length === 1 && a.args[0]!.name === '$0' && typeof a.args[0]!.value === 'string';
}

export function ruleId(f: Core.Func): string | undefined {
  for (const a of annotationsOf(f)) if (a.name === 'id' && isSingleStringArg(a)) return a.args![0]!.value as string;
  return undefined;
}

export function controls(f: Core.Func): string[] {
  const out: string[] = [];
  for (const a of annotationsOf(f)) if (a.name === 'control' && isSingleStringArg(a)) out.push(a.args![0]!.value as string);
  return out;
}

function anyReturnIsVerdictCall(stmt: Core.Statement): boolean {
  switch (stmt.kind) {
    case 'Return': return stmt.expr.kind === 'Call' && stmt.expr.target.kind === 'Name' && isVerdictCall(stmt.expr.target.name);
    case 'Block': return stmt.statements.some(anyReturnIsVerdictCall);
    case 'If': return anyReturnIsVerdictCall(stmt.then) || (stmt.else ? anyReturnIsVerdictCall(stmt.else) : false);
    case 'Match': return stmt.cases.some((c) => anyReturnIsVerdictCall(c.body));
    default: return false;
  }
}

export function returnsVerdict(f: Core.Func): boolean {
  if (f.ret && f.ret.kind === 'TypeName' && f.ret.name === VERDICT_TYPE_NAME) return true;
  return !!f.body && anyReturnIsVerdictCall(f.body);
}

export function checkGovernance(decls: readonly Core.Decl[]): TypecheckDiagnostic[] {
  const b = new DiagnosticBuilder();
  const owners = new Map<string, Core.Func[]>();
  for (const d of decls) {
    if (d.kind === 'Data' || d.kind === 'Enum') {
      if (d.name === VERDICT_TYPE_NAME) b.error(ErrorCode.DUPLICATE_SYMBOL, d.origin, { name: VERDICT_TYPE_NAME });
      continue;
    }
    if (d.kind !== 'Func') continue;
    for (const a of annotationsOf(d)) {
      if (STRING_ARG_ANNOTATIONS.has(a.name) && !isSingleStringArg(a)) {
        b.error(ErrorCode.GOV_ANNOTATION_ARG_INVALID, d.origin, { annotation: a.name, rule: d.name });
      }
    }
    const id = ruleId(d);
    if (id !== undefined) {
      const list = owners.get(id) ?? []; list.push(d); owners.set(id, list);
    } else if (returnsVerdict(d)) {
      b.warning(ErrorCode.GOV_VERDICT_RULE_MISSING_ID, d.origin, { rule: d.name });
    }
  }
  for (const [id, funcs] of owners) {
    if (funcs.length > 1) b.error(ErrorCode.GOV_DUPLICATE_RULE_ID, funcs[0]!.origin, { id, rules: funcs.map((f) => f.name).join(', ') });
  }
  return b.getDiagnostics();
}
```

Core IR 语句字段名（`then`/`else`/`cases`/`body`/`statements`）以 `src/core/core_ir.ts` 类型定义为准。

- [ ] **Step 4: 两条路径接线**

`module.ts:134` 的 `checkEntryRuleUniqueness(m.decls)` 旁加 `...checkGovernance(m.decls)` 合并进结果；`browser.ts:317` 同样加。

- [ ] **Step 5: 测试直到通过，全量**

```bash
pnpm typecheck && pnpm lint && pnpm test:unit
```

- [ ] **Step 6: 提交**

```bash
git add src/typecheck/governance.ts src/typecheck/module.ts src/typecheck/browser.ts test/unit/governance/governance-check.test.ts
git commit -m "feat(typecheck): 治理检查 W700/E701/E702 与 Verdict 符号预占（ADR 0039）"
```

---

### Task 6: TS 解释器实现 `Verdict.*`（aster-lang-ts）

**Files:**
- Modify: `aster-lang-ts/src/core/interpreter.ts:610-680`（`BUILTIN_ARITY`）、`:1202-1658`（`evalStdlibCall` switch）
- Test: `aster-lang-ts/test/unit/interpreter/verdict-builtins.test.ts`

**Interfaces:**
- Produces: 运行时值 `{ __type: 'Verdict', outcome: 'ALLOW' }`、`{ __type: 'Verdict', outcome: 'DENY', reason }`、`{ __type: 'Verdict', outcome: 'REQUIRE_APPROVAL', role, reason }`、`{ __type: 'Verdict', outcome: 'ESCALATE', reason }`。对象属性按此顺序插入。
- 失败语义：reason / role 非字符串或为空串 → `InterpreterError`，消息以 `Verdict.<fn>:` 开头。

- [ ] **Step 1: 写失败测试**

```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compile } from '../../../src/browser.js';
import { evaluate } from '../../../src/core/interpreter.js';

function run(body: string) {
  const m = compile(`Module probe.\n@id("R-1")\n${body}\n`);
  assert.ok(m.core, JSON.stringify(m.diagnostics ?? m));
  return evaluate(m.core!, 'main', { seed: 0 }, { maxSteps: 10_000 });
}

test('allow 形状与键序', () => {
  const ev = run('Rule main produce Verdict:\n  Return Verdict.allow().');
  assert.equal(ev.success, true);
  assert.equal(JSON.stringify(ev.value), '{"__type":"Verdict","outcome":"ALLOW"}');
});
test('deny 带 reason', () => {
  const ev = run('Rule main produce Verdict:\n  Return Verdict.deny("no consent").');
  assert.equal(JSON.stringify(ev.value), '{"__type":"Verdict","outcome":"DENY","reason":"no consent"}');
});
test('require_approval 键序 role 在 reason 前', () => {
  const ev = run('Rule main produce Verdict:\n  Return Verdict.require_approval("Senior Underwriter", "over cap").');
  assert.equal(JSON.stringify(ev.value), '{"__type":"Verdict","outcome":"REQUIRE_APPROVAL","role":"Senior Underwriter","reason":"over cap"}');
});
test('escalate', () => {
  const ev = run('Rule main produce Verdict:\n  Return Verdict.escalate("low confidence").');
  assert.equal(JSON.stringify(ev.value), '{"__type":"Verdict","outcome":"ESCALATE","reason":"low confidence"}');
});
test('空 reason 运行时失败', () => {
  const ev = run('Rule main produce Verdict:\n  Return Verdict.deny("").');
  assert.equal(ev.success, false);
  assert.match(String(ev.error), /Verdict\.deny/);
});
test('reason 为非 Text 变量时运行时失败', () => {
  const ev = run('Rule main given n, produce Verdict:\n  Return Verdict.deny(n).');
  // 通过 evaluate 传 Int 入参
  const m = compile('Module probe.\n@id("R-1")\nRule main given n, produce Verdict:\n  Return Verdict.deny(n).\n');
  const ev2 = evaluate(m.core!, 'main', { seed: 0 }, { maxSteps: 10_000, args: [42] } as never);
  assert.equal(ev2.success, false);
  void ev;
});
```

`evaluate` 传位置参数的实际方式以 `test/unit/interpreter/interpreter.test.ts` 为准，改写最后一个用例的调用形式，不改断言。

- [ ] **Step 2: 运行确认失败**

```bash
pnpm build && node --test dist/test/unit/interpreter/verdict-builtins.test.js
```
预期：`Undefined function 'Verdict.allow'`。

- [ ] **Step 3: 实现**

`BUILTIN_ARITY` 加：`'Verdict.allow': [0, 0], 'Verdict.deny': [1, 1], 'Verdict.require_approval': [2, 2], 'Verdict.escalate': [1, 1]`。

switch 内、`default` 之前加：

```ts
      // === Verdict（ADR 0039）：与 truffle Builtins 同键序；reason/role 必须是非空 Text ===
      case 'Verdict.allow': return { __type: 'Verdict', outcome: 'ALLOW' };
      case 'Verdict.deny': { const [r] = a(); return { __type: 'Verdict', outcome: 'DENY', reason: verdictText(r, 'Verdict.deny', 'reason') }; }
      case 'Verdict.require_approval': {
        const [role, r] = a();
        return { __type: 'Verdict', outcome: 'REQUIRE_APPROVAL', role: verdictText(role, 'Verdict.require_approval', 'role'), reason: verdictText(r, 'Verdict.require_approval', 'reason') };
      }
      case 'Verdict.escalate': { const [r] = a(); return { __type: 'Verdict', outcome: 'ESCALATE', reason: verdictText(r, 'Verdict.escalate', 'reason') }; }
```

文件顶层（`toNum` 附近）加：

```ts
/** Verdict 构造参数必须是非空 Text：空理由等于没有决策，非 Text 不做隐式转换（与 truffle 一致）。 */
function verdictText(v: unknown, fn: string, field: string): string {
  if (typeof v !== 'string') throw new InterpreterError(`${fn}: ${field} must be Text, got ${Array.isArray(v) ? 'List' : typeof v}`);
  if (v.length === 0) throw new InterpreterError(`${fn}: ${field} must not be empty`);
  return v;
}
```

- [ ] **Step 4: 测试直到通过，全量**

```bash
pnpm typecheck && pnpm lint && pnpm test:unit
```

- [ ] **Step 5: 提交**

```bash
git add src/core/interpreter.ts test/unit/interpreter/verdict-builtins.test.ts
git commit -m "feat(interpreter): Verdict.allow/deny/require_approval/escalate 内置（ADR 0039）"
```

---

### Task 7: Truffle 运行时实现 `Verdict.*`（aster-lang-truffle）

**Files:**
- Modify: `aster-lang-truffle/src/main/java/aster/truffle/runtime/Builtins.java`（static 块，`Result.tapError` 之后、`// === IO Operations` 之前）
- Test: `aster-lang-truffle/src/test/java/aster/truffle/runtime/VerdictBuiltinsTest.java`
- Test fixture: `aster-lang-truffle/src/test/resources/verdict/verdict-require-approval_core.json`

**Interfaces:**
- Produces: `Builtins.call("Verdict.allow", new Object[0])` 等返回 `LinkedHashMap`，键序 `__type`、`outcome`、`role`、`reason`。参数非 `String` 或空串抛 `BuiltinException`。

- [ ] **Step 1: 建分支，写失败测试**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-truffle && git checkout -b feat/adr-0039-verdict
```

```java
package aster.truffle.runtime;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verdict 内置（ADR 0039 §2.3）：形状、键序、参数校验。 */
class VerdictBuiltinsTest {

  @Test void allow形状() throws Exception {
    Object r = Builtins.call("Verdict.allow", new Object[0]);
    assertEquals(java.util.List.of("__type", "outcome"), new ArrayList<>(((Map<?, ?>) r).keySet()));
    assertEquals("Verdict", ((Map<?, ?>) r).get("__type"));
    assertEquals("ALLOW", ((Map<?, ?>) r).get("outcome"));
  }

  @Test void requireApproval键序() throws Exception {
    Object r = Builtins.call("Verdict.require_approval", new Object[]{"Senior Underwriter", "over cap"});
    assertEquals(java.util.List.of("__type", "outcome", "role", "reason"), new ArrayList<>(((Map<?, ?>) r).keySet()));
    assertEquals("REQUIRE_APPROVAL", ((Map<?, ?>) r).get("outcome"));
  }

  @Test void deny与escalate() throws Exception {
    assertEquals("DENY", ((Map<?, ?>) Builtins.call("Verdict.deny", new Object[]{"no"})).get("outcome"));
    assertEquals("ESCALATE", ((Map<?, ?>) Builtins.call("Verdict.escalate", new Object[]{"low"})).get("outcome"));
  }

  @Test void 空reason抛异常() {
    assertThrows(BuiltinException.class, () -> Builtins.call("Verdict.deny", new Object[]{""}));
  }

  @Test void 非Text抛异常() {
    assertThrows(BuiltinException.class, () -> Builtins.call("Verdict.deny", new Object[]{42L}));
  }

  @Test void 参数个数错误抛异常() {
    assertThrows(BuiltinException.class, () -> Builtins.call("Verdict.allow", new Object[]{"x"}));
  }

  @Test void 命名空间被识别() {
    assertTrue(Builtins.hasNamespace("Verdict"));
  }

  @Test void 端到端CoreIR求值() throws Exception {
    String json = Files.readString(Path.of("src/test/resources/verdict/verdict-require-approval_core.json"));
    try (Context ctx = Context.newBuilder("aster").allowAllAccess(true).build()) {
      Value r = ctx.eval(Source.newBuilder("aster", json, "verdict").build());
      Value v = r.canExecute() ? r.execute() : r;
      Map<?, ?> m = v.as(Map.class);
      assertEquals("Verdict", String.valueOf(m.get("__type")));
      assertEquals("REQUIRE_APPROVAL", String.valueOf(m.get("outcome")));
      assertEquals("Senior Underwriter", String.valueOf(m.get("role")));
    }
  }
}
```

Fixture `verdict-require-approval_core.json`（照 `src/test/resources/tagparity/tagp-ok.json` 的 Module/Func 外壳，body 为一条 Return）：

```json
{"kind":"Module","name":"probe","decls":[{"kind":"Func","name":"main","typeParams":[],"params":[],"ret":{"kind":"TypeName","name":"Verdict"},"effects":[],
 "body":{"kind":"Block","statements":[{"kind":"Return","expr":{"kind":"Call","target":{"kind":"Name","name":"Verdict.require_approval"},
 "args":[{"kind":"String","value":"Senior Underwriter"},{"kind":"String","value":"over cap"}]}}]}}]}
```

字段名以 `tagp-ok.json` 为准对齐（例如 `String` 字面量节点 kind）。

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew --offline test --tests 'aster.truffle.runtime.VerdictBuiltinsTest'
```
预期：`call` 返回 null → NPE/断言失败；`hasNamespace` 为 false。

- [ ] **Step 3: 注册四个内置**

在 static 块中加：

```java
    // === Verdict（ADR 0039）===
    // 运行时值形状与 aster-lang-ts interpreter 逐键一致：__type, outcome, [role], reason。
    // reason/role 必须是非空 Text：空理由等于没有决策；非 Text 不做隐式转换。
    register("Verdict.allow", new BuiltinDef(args -> {
      checkArity("Verdict.allow", args, 0);
      return verdict("ALLOW", null, null);
    }));
    register("Verdict.deny", new BuiltinDef(args -> {
      checkArity("Verdict.deny", args, 1);
      return verdict("DENY", null, verdictText(args[0], "Verdict.deny", "reason"));
    }));
    register("Verdict.require_approval", new BuiltinDef(args -> {
      checkArity("Verdict.require_approval", args, 2);
      return verdict("REQUIRE_APPROVAL", verdictText(args[0], "Verdict.require_approval", "role"),
          verdictText(args[1], "Verdict.require_approval", "reason"));
    }));
    register("Verdict.escalate", new BuiltinDef(args -> {
      checkArity("Verdict.escalate", args, 1);
      return verdict("ESCALATE", null, verdictText(args[0], "Verdict.escalate", "reason"));
    }));
```

私有辅助（放在 `checkArity` 附近）：

```java
  private static java.util.Map<String, Object> verdict(String outcome, String role, String reason) {
    java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("__type", "Verdict");
    m.put("outcome", outcome);
    if (role != null) m.put("role", role);
    if (reason != null) m.put("reason", reason);
    return m;
  }

  private static String verdictText(Object v, String fn, String field) throws BuiltinException {
    if (!(v instanceof String s)) throw new BuiltinException(fn + ": " + field + " must be Text, got " + typeName(v));
    if (s.isEmpty()) throw new BuiltinException(fn + ": " + field + " must not be empty");
    return s;
  }
```

`BuiltinException` 构造函数与 `typeName` 以文件既有用法为准。

- [ ] **Step 4: 测试直到通过，全量**

```bash
./gradlew --offline test --tests 'aster.truffle.runtime.VerdictBuiltinsTest'
./gradlew --offline test
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/aster/truffle/runtime/Builtins.java src/test/java/aster/truffle/runtime/VerdictBuiltinsTest.java src/test/resources/verdict/verdict-require-approval_core.json
git commit -m "feat(runtime): Verdict.allow/deny/require_approval/escalate 内置（ADR 0039）"
```

---

### Task 8: 双引擎 parity 语料（aster-lang-test）

**Files:**
- Create: `aster-lang-test/corpus/tier1-equivalence/policies/verdict-basic.aster`、`verdict-basic.meta.json`
- Modify: `aster-lang-test/corpus/tier1-parity/manifest.json`
- Create: `aster-lang-test/scripts/cases-spec-verdict.json`
- Generated: `aster-lang-test/corpus/tier1-equivalence/inputs/verdict-basic.cases.json`
- Modify: `aster-lang-test/scripts/tag-eval-exempt.mjs:93`（`STDLIB_NAMESPACES` 加 `'Verdict'`）
- Modify: `aster-lang-test/EVAL-EXEMPTIONS.md`（Total 与 Eval-able 各 +1）

**Interfaces:**
- Consumes: Task 2–7 全部完成；core 已 `publishToMavenLocal`，ts 已 `pnpm build`。

- [ ] **Step 1: 建分支，发布本地依赖**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-test && git checkout -b feat/adr-0039-verdict
cd /Users/rpang/IdeaProjects/aster-lang-core && ./gradlew --offline publishToMavenLocal -x test
cd /Users/rpang/IdeaProjects/aster-lang-ts && export PATH=$HOME/Library/pnpm:/opt/homebrew/bin:$PATH && pnpm build
```

- [ ] **Step 2: 写语料**

`verdict-basic.aster`：

```
Module verdict.basic.

@id("VB-001")
@control("EU_AI_ACT:ART14")
Rule decide given amount, produce Verdict:
  If amount at least 100000:
    Return Verdict.require_approval("Senior Underwriter", "amount over cap").
  Otherwise:
    If amount at least 0:
      Return Verdict.allow().
    Otherwise:
      Return Verdict.deny("negative amount").

@id("VB-002")
Rule escalate_low given confidence, produce Verdict:
  If confidence at least 85:
    Return Verdict.allow().
  Otherwise:
    Return Verdict.escalate("confidence below threshold").
```

`verdict-basic.meta.json`：`{"tier":1,"engines":["java","ts"],"lexicon":"en-US","source":"ADR 0039 Verdict 内置模块","capabilities":["cases-golden"]}`（字段照 `01-arithmetic-add.meta.json`）。

`manifest.json` 的 `samples` 加 `"tier1-equivalence/policies/verdict-basic.aster"`。

`scripts/cases-spec-verdict.json`：

```json
{"samples":[{"name":"verdict-basic","entry":"decide","cases":[
  {"name":"allow","input":[500],"expectedOutput":{"__type":"Verdict","outcome":"ALLOW"}},
  {"name":"require approval","input":[250000],"expectedOutput":{"__type":"Verdict","outcome":"REQUIRE_APPROVAL","role":"Senior Underwriter","reason":"amount over cap"}},
  {"name":"deny","input":[-1],"expectedOutput":{"__type":"Verdict","outcome":"DENY","reason":"negative amount"}},
  {"name":"escalate","entry":"escalate_low","input":[40],"expectedOutput":{"__type":"Verdict","outcome":"ESCALATE","reason":"confidence below threshold"}},
  {"name":"escalate path allow","entry":"escalate_low","input":[90],"expectedOutput":{"__type":"Verdict","outcome":"ALLOW"}}
]}]}
```

- [ ] **Step 3: 生成黄金（两引擎须一致才会写入）**

```bash
cd /Users/rpang/IdeaProjects/aster-lang-test
node scripts/gen-cases.mjs scripts/cases-spec-verdict.json
node scripts/gen-cases.mjs scripts/cases-spec-verdict.json --write --only=verdict-basic
```
预期：5 个用例两引擎输出与期望逐字一致，写出 `inputs/verdict-basic.cases.json`。若任一引擎输出不同，这是真实分叉，回到 Task 6/7 修引擎，不改期望值。

- [ ] **Step 4: 豁免分类与计数**

`tag-eval-exempt.mjs:93` 加 `'Verdict'`。

```bash
node scripts/tag-eval-exempt.mjs --write
node scripts/tag-eval-exempt.mjs --check
```
按 `--check` 的输出把 `EVAL-EXEMPTIONS.md` 的 Total 与 Eval-able 各加 1，再跑一次 `--check` 至通过。

- [ ] **Step 5: 跑三道门禁**

```bash
node scripts/parity-tier1.mjs --mode=ir --full
node scripts/parity-tier1.mjs --mode=eval
node scripts/test-eval-guard.mjs
```
预期：IR 零分歧；eval 对 `verdict-basic` 5 个用例 `identical`；guard 通过。

- [ ] **Step 6: 提交**

```bash
git add corpus/tier1-equivalence/policies/verdict-basic.aster corpus/tier1-equivalence/policies/verdict-basic.meta.json corpus/tier1-equivalence/inputs/verdict-basic.cases.json corpus/tier1-parity/manifest.json scripts/cases-spec-verdict.json scripts/tag-eval-exempt.mjs EVAL-EXEMPTIONS.md
git commit -m "test(corpus): Verdict 内置双引擎 parity 语料（ADR 0039）"
```

---

### Task 9: aster-api 两条评估路径附加 `decision` / `ruleId` / `controls`

**Files:**
- Create: `aster-api/replay/src/main/java/io/aster/policy/governance/VerdictView.java`
- Create: `aster-api/replay/src/main/java/io/aster/policy/governance/RuleMetadata.java`
- Modify: `aster-api/replay/src/main/java/io/aster/policy/parser/DynamicCnlExecutor.java:104-109`（`ExecutionResult` 加字段）、`:329`、`:498`（构造处）
- Modify: `aster-api/replay/src/main/java/io/aster/replay/core/ReplayExecutorResult.java`
- Modify: `aster-api/src/main/java/io/aster/policy/replay/ReplayExecutorAdapter.java:37`
- Modify: `aster-api/src/main/java/io/aster/policy/rest/model/EvaluationResponse.java`（新增三字段与 success 重载）
- Modify: `aster-api/src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java:630-634`
- Modify: `aster-api/src/main/java/io/aster/policy/api/model/PolicyEvaluationResult.java`、`aster-api/src/main/java/io/aster/policy/api/PolicyEvaluationService.java:210-222`
- Test: `aster-api/replay/src/test/java/io/aster/policy/governance/VerdictViewTest.java`、`RuleMetadataTest.java`

**Interfaces:**
- Produces: `record VerdictView(String outcome, String role, String reason)` + `static Optional<VerdictView> from(Object result)`（仅 `Map` 且 `__type == "Verdict"`）；`record RuleMetadata(String ruleId, List<String> controls)` + `static RuleMetadata of(CoreModel.Module module, String functionName)`（复用 Task 3 的 `GovernanceChecker.ruleId/controls`；找不到函数时 `ruleId = null, controls = []`）。
- `ExecutionResult` 与 `ReplayExecutorResult` 新增 `String ruleId, List<String> controls`；`EvaluationResponse` 新增 `decision`（`VerdictView`，NON_NULL）、`ruleId`（NON_NULL）、`controls`（NON_EMPTY）。
- `PolicyEvaluationResult` 新增 `VerdictView decision`、`String ruleId`、`List<String> controls` 及对应 getter；旧三参构造保留（新字段 null / 空）。

- [ ] **Step 1: 建分支，写失败测试（纯 JUnit，无 Quarkus）**

```bash
cd /Users/rpang/IdeaProjects/aster-api && git checkout feat/adr-0039-verdict 2>/dev/null || git checkout -b feat/adr-0039-verdict adr-0039-decision-primitive
```

`VerdictViewTest.java`：

```java
package io.aster.policy.governance;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class VerdictViewTest {
  @Test void 识别Verdict值() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("__type", "Verdict"); m.put("outcome", "REQUIRE_APPROVAL"); m.put("role", "Senior Underwriter"); m.put("reason", "over cap");
    assertThat(VerdictView.from(m)).contains(new VerdictView("REQUIRE_APPROVAL", "Senior Underwriter", "over cap"));
  }
  @Test void allow无role无reason() {
    assertThat(VerdictView.from(Map.of("__type", "Verdict", "outcome", "ALLOW"))).contains(new VerdictView("ALLOW", null, null));
  }
  @Test void 非Verdict返回empty() {
    assertThat(VerdictView.from(Map.of("__type", "Decision", "approved", true))).isEmpty();
    assertThat(VerdictView.from(true)).isEmpty();
    assertThat(VerdictView.from("APPROVED")).isEmpty();
    assertThat(VerdictView.from(null)).isEmpty();
  }
}
```

`RuleMetadataTest.java`：

```java
package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RuleMetadataTest {
  private static CoreModel.Func func(String name, CoreModel.Annotation... anns) {
    var f = new CoreModel.Func(); f.name = name; f.annotations = List.of(anns); return f;
  }
  private static CoreModel.Annotation ann(String name, String value) {
    var a = new CoreModel.Annotation(); a.name = name; a.params = Map.of("$0", value); return a;
  }
  @Test void 读取被执行函数的id与controls() {
    var mod = new CoreModel.Module();
    mod.decls = List.of(func("other", ann("id", "X")), func("decide", ann("id", "VB-001"), ann("control", "EU_AI_ACT:ART14"), ann("control", "GDPR:ART6")));
    var meta = RuleMetadata.of(mod, "decide");
    assertThat(meta.ruleId()).isEqualTo("VB-001");
    assertThat(meta.controls()).containsExactly("EU_AI_ACT:ART14", "GDPR:ART6");
  }
  @Test void 无注解或函数不存在() {
    var mod = new CoreModel.Module(); mod.decls = List.of(func("decide"));
    assertThat(RuleMetadata.of(mod, "decide")).isEqualTo(new RuleMetadata(null, List.of()));
    assertThat(RuleMetadata.of(mod, "nope")).isEqualTo(new RuleMetadata(null, List.of()));
    assertThat(RuleMetadata.of(null, "decide")).isEqualTo(new RuleMetadata(null, List.of()));
  }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew --offline :replay:test --tests 'io.aster.policy.governance.*'
```

- [ ] **Step 3: 实现两个 record**

```java
package io.aster.policy.governance;

import java.util.Map;
import java.util.Optional;

/** 运行时 Verdict 值的结构化视图（ADR 0039 §5.1）。只认 `__type == "Verdict"` 的 Map，不做任何猜测。 */
public record VerdictView(String outcome, String role, String reason) {
  public static Optional<VerdictView> from(Object result) {
    if (!(result instanceof Map<?, ?> m) || !"Verdict".equals(m.get("__type"))) return Optional.empty();
    Object outcome = m.get("outcome");
    if (!(outcome instanceof String o)) return Optional.empty();
    return Optional.of(new VerdictView(o, m.get("role") instanceof String r ? r : null, m.get("reason") instanceof String s ? s : null));
  }
}
```

```java
package io.aster.policy.governance;

import aster.core.ir.CoreModel;
import aster.core.typecheck.governance.GovernanceChecker;
import java.util.List;

/** 被执行规则的元数据（ADR 0039 §3）：来自 Core IR 注解，不在运行时反查源码。 */
public record RuleMetadata(String ruleId, List<String> controls) {
  public static final RuleMetadata NONE = new RuleMetadata(null, List.of());

  public static RuleMetadata of(CoreModel.Module module, String functionName) {
    if (module == null || module.decls == null || functionName == null) return NONE;
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f && functionName.equals(f.name)) {
        return new RuleMetadata(GovernanceChecker.ruleId(f).orElse(null), List.copyOf(GovernanceChecker.controls(f)));
      }
    }
    return NONE;
  }
}
```

- [ ] **Step 4: 贯穿 `evaluate-source` 路径**

1. `DynamicCnlExecutor.ExecutionResult` 加 `String ruleId, List<String> controls`；`:329` 处用 `RuleMetadata.of(coreModule, targetFunction)`，`:498` 处用 `RuleMetadata.of(compiled.coreModule(), targetFunction)` 填充。
2. `ReplayExecutorResult` 加同名两字段；`ReplayExecutorAdapter.java:37` 透传。
3. `EvaluationResponse` 加三个字段（`@JsonProperty("decision") @JsonInclude(NON_NULL) VerdictView decision`、`@JsonProperty("ruleId") @JsonInclude(NON_NULL) String ruleId`、`@JsonProperty("controls") @JsonInclude(NON_EMPTY) List<String> controls`），所有既有工厂方法补 `null, null, List.of()`，新增重载 `success(Object result, long ms, DecisionTrace trace, String executedFunction, VerdictView decision, String ruleId, List<String> controls)`。
4. `PolicyEvaluationResource.java:630-634` 改用新重载：`VerdictView.from(phase.execResult().result()).orElse(null)`、`phase.execResult().ruleId()`、`phase.execResult().controls()`。
5. 检查 `ReplayExecutionCore.computeReplayMetadata` 的 traceHash 输入：它哈希 `DecisionTrace`，本任务未改 `DecisionTrace`，哈希不变。用 `./gradlew --offline :replay:test` 里既有的 `ReplayMetadataTest` 确认。

- [ ] **Step 5: 贯穿部署策略路径**

`PolicyEvaluationResult` 加字段与 getter，保留三参构造；`PolicyEvaluationService.executeCompiled`：

```java
        CoreModel.Module module = parseCoreModule(coreJson);   // Jackson 读 CoreModel.Module，失败返回 null 并记 warn，不影响评估
        RuleMetadata meta = RuleMetadata.of(module, cacheKey.getPolicyFunction());
        return new PolicyEvaluationResult(executionResult.result(), durationNanos / 1_000_000.0, false,
            VerdictView.from(executionResult.result()).orElse(null), meta.ruleId(), meta.controls());
```

`adjustFromCacheFlag` 同步复制新字段。`PolicyEvaluationResource` 把 `PolicyEvaluationResult` 复制进 `EvaluationResponse` 的两处（`:258`、`:1076-1077`）改用新重载传入三字段。

- [ ] **Step 6: 编译与测试**

```bash
./gradlew --offline :replay:test --tests 'io.aster.policy.governance.*' --tests 'io.aster.replay.core.ReplayExecutionCoreTest' --tests 'io.aster.policy.replay.ReplayMetadataTest'
./gradlew --offline test --tests 'io.aster.policy.api.PolicyQueryServiceTest'
```
预期：全绿（`PolicyQueryServiceTest` 用三参构造，必须仍编译通过）。

若本机有 Podman/Docker，再跑 REST 集成：
```bash
./gradlew --offline test --tests 'io.aster.policy.rest.PolicyEvaluationReplayOrderingTest'
```
无容器运行时则在 `operations-log.md` 记录“REST 集成测试未在本机执行，由 CI 覆盖”。

- [ ] **Step 7: 提交**

```bash
git add replay/src/main/java/io/aster/policy/governance replay/src/main/java/io/aster/policy/parser/DynamicCnlExecutor.java replay/src/main/java/io/aster/replay/core/ReplayExecutorResult.java src/main/java/io/aster/policy/replay/ReplayExecutorAdapter.java src/main/java/io/aster/policy/rest/model/EvaluationResponse.java src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java src/main/java/io/aster/policy/api/model/PolicyEvaluationResult.java src/main/java/io/aster/policy/api/PolicyEvaluationService.java replay/src/test/java/io/aster/policy/governance
git commit -m "feat(api): 评估结果附加 decision/ruleId/controls（ADR 0039）"
```

---

### Task 10: `DecisionInterpreter` 收缩为三条类型驱动规则（aster-api）

**Files:**
- Modify: `aster-api/src/main/java/io/aster/policy/replay/batch/DecisionInterpreter.java`
- Modify: `aster-api/src/main/java/io/aster/policy/replay/batch/ReplayBatchService.java:973-983`
- Test: `aster-api/src/test/java/io/aster/policy/replay/batch/DecisionInterpreterTest.java`

**Interfaces:**
- Produces: `enum Verdict { APPROVED, DENIED, REQUIRE_APPROVAL, ESCALATE, INDETERMINATE }`；`interpret(Object)` 规则：Verdict 值按 outcome 直映；Boolean 直映；Map 含 Boolean `approved` 键直映；其余 INDETERMINATE。

- [ ] **Step 1: 改写测试（先红）**

替换 `DecisionInterpreterTest` 中字符串、`decision` 键、反射三类用例为：

```java
  @Test void Verdict值按outcome直映() {
    assertThat(DecisionInterpreter.interpret(verdict("ALLOW"))).isEqualTo(DecisionInterpreter.Verdict.APPROVED);
    assertThat(DecisionInterpreter.interpret(verdict("DENY"))).isEqualTo(DecisionInterpreter.Verdict.DENIED);
    assertThat(DecisionInterpreter.interpret(verdict("REQUIRE_APPROVAL"))).isEqualTo(DecisionInterpreter.Verdict.REQUIRE_APPROVAL);
    assertThat(DecisionInterpreter.interpret(verdict("ESCALATE"))).isEqualTo(DecisionInterpreter.Verdict.ESCALATE);
  }
  @Test void 未知outcome为INDETERMINATE() {
    assertThat(DecisionInterpreter.interpret(verdict("MAYBE"))).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
  }
  @Test void 模板约定approved布尔键仍直映() {
    assertThat(DecisionInterpreter.interpret(Map.of("__type", "Decision", "approved", true, "reason", "OK"))).isEqualTo(DecisionInterpreter.Verdict.APPROVED);
    assertThat(DecisionInterpreter.interpret(Map.of("approved", false))).isEqualTo(DecisionInterpreter.Verdict.DENIED);
  }
  @ParameterizedTest @ValueSource(strings = {"APPROVED", "approved", "true", "REJECTED", "denied", "false", "Hello"})
  void 字符串一律INDETERMINATE(String s) {
    assertThat(DecisionInterpreter.interpret(s)).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
  }
  @Test void decision字符串键不再识别() {
    assertThat(DecisionInterpreter.interpret(Map.of("decision", "APPROVED"))).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
  }
  @Test void approved键非布尔不识别() {
    assertThat(DecisionInterpreter.interpret(Map.of("approved", "yes"))).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
  }
  @Test void 反射isApproved不再识别() {
    Object host = new Object() { public boolean isApproved() { return true; } };
    assertThat(DecisionInterpreter.interpret(host)).isEqualTo(DecisionInterpreter.Verdict.INDETERMINATE);
  }
  private static Map<String, Object> verdict(String outcome) {
    var m = new java.util.LinkedHashMap<String, Object>(); m.put("__type", "Verdict"); m.put("outcome", outcome); return m;
  }
```

保留既有 `布尔直接映射`。

- [ ] **Step 2: 运行确认失败**

```bash
./gradlew --offline test --tests 'io.aster.policy.replay.batch.DecisionInterpreterTest'
```

- [ ] **Step 3: 重写 `interpret`**

```java
    public enum Verdict { APPROVED, DENIED, REQUIRE_APPROVAL, ESCALATE, INDETERMINATE }

    /**
     * 三条类型驱动的确定性规则（ADR 0039 §5.2），不再有字符串语义猜测：
     *  1. Verdict 值（__type == "Verdict"）按 outcome 直映；
     *  2. Boolean 直映；
     *  3. Map 含 Boolean 类型的 approved 键直映（Define Decision has approved, reason 模板约定）；
     *  其余一律 INDETERMINATE。
     */
    public static Verdict interpret(Object result) {
        var view = io.aster.policy.governance.VerdictView.from(result);
        if (view.isPresent()) {
            return switch (view.get().outcome()) {
                case "ALLOW" -> Verdict.APPROVED;
                case "DENY" -> Verdict.DENIED;
                case "REQUIRE_APPROVAL" -> Verdict.REQUIRE_APPROVAL;
                case "ESCALATE" -> Verdict.ESCALATE;
                default -> Verdict.INDETERMINATE;
            };
        }
        if (result instanceof Boolean b) return b ? Verdict.APPROVED : Verdict.DENIED;
        if (result instanceof Map<?, ?> m && m.get("approved") instanceof Boolean b) return b ? Verdict.APPROVED : Verdict.DENIED;
        return Verdict.INDETERMINATE;
    }
```

删除 `fromString` 与反射分支。类注释同步改为上述三条规则的说明。

- [ ] **Step 4: `ReplayBatchService` 处理新两态**

`:978` 的 INDETERMINATE 分支不变；`:983` `targetApproved = targetVerdict == APPROVED` 不变（REQUIRE_APPROVAL / ESCALATE 是真实的非批准决策，参与比较）。在该处补一行中文注释说明口径。

- [ ] **Step 5: 测试直到通过，全量单测**

```bash
./gradlew --offline test --tests 'io.aster.policy.replay.batch.*'
./gradlew --offline test
```
全量若因容器运行时缺失而跳过 `@QuarkusTest`，在日志中记录。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/aster/policy/replay/batch/DecisionInterpreter.java src/main/java/io/aster/policy/replay/batch/ReplayBatchService.java src/test/java/io/aster/policy/replay/batch/DecisionInterpreterTest.java
git commit -m "refactor(replay): DecisionInterpreter 收缩为三条类型驱动规则，新增 REQUIRE_APPROVAL/ESCALATE（ADR 0039）"
```

---

### Task 11: aster-cloud 结果解析器识别 Verdict 值

**Files:**
- Modify: `aster-cloud/src/services/policy/cnl-executor.ts:356-411`（`parseApprovalFromResult` 对象分支最前）
- Test: `aster-cloud/src/__tests__/services/cnl-result-parser.test.ts`

**Interfaces:**
- 行为：`{__type:'Verdict', outcome:'ALLOW'}` → `{approved:true, message:'Approved'}`；`DENY` → `{approved:false, message: reason}`；`REQUIRE_APPROVAL` / `ESCALATE` → `{approved:false, indeterminate:true, message: reason}`（cloud 的 `ExecutionDecision` 枚举尚无对应态，子项目 3 扩展枚举后改为专属态；这里先保证不会被判成 approved 或 denied）。

- [ ] **Step 1: 写失败测试**

在 `cnl-result-parser.test.ts` 末尾加：

```ts
describe('Verdict 内置值（ADR 0039）', () => {
  it('ALLOW → approved', () => {
    expect(parseApprovalFromResult({ __type: 'Verdict', outcome: 'ALLOW' })).toEqual({ approved: true, message: 'Approved' });
  });
  it('DENY → denied，带 reason', () => {
    expect(parseApprovalFromResult({ __type: 'Verdict', outcome: 'DENY', reason: 'no consent' })).toEqual({ approved: false, message: 'no consent' });
  });
  it('REQUIRE_APPROVAL → 不批准且 indeterminate', () => {
    const r = parseApprovalFromResult({ __type: 'Verdict', outcome: 'REQUIRE_APPROVAL', role: 'Senior Underwriter', reason: 'over cap' });
    expect(r.approved).toBe(false); expect(r.indeterminate).toBe(true); expect(r.message).toBe('over cap');
  });
  it('ESCALATE → 不批准且 indeterminate', () => {
    const r = parseApprovalFromResult({ __type: 'Verdict', outcome: 'ESCALATE', reason: 'low confidence' });
    expect(r.approved).toBe(false); expect(r.indeterminate).toBe(true);
  });
  it('value 模式下同样按 outcome 解读', () => {
    expect(parseApprovalFromResult({ __type: 'Verdict', outcome: 'DENY', reason: 'x' }, 'value')).toEqual({ approved: false, message: 'x' });
  });
});
```

`describe`/`it`/`expect` 以该文件既有导入为准。

- [ ] **Step 2: 运行确认失败**

```bash
cd /Users/rpang/IdeaProjects/aster-cloud && export PATH=$HOME/Library/pnpm:/opt/homebrew/bin:$PATH && git checkout -b feat/adr-0039-verdict
pnpm vitest run src/__tests__/services/cnl-result-parser.test.ts
```
预期：ALLOW 用例今天返回 `{approved:false, message:'Unknown result format'}`，失败。

- [ ] **Step 3: 实现**

在对象分支开头（`const obj = result as Record<string, unknown>;` 之后）加：

```ts
    // Verdict 内置值（ADR 0039）：结构化决策，不走任何字段名启发式。
    if (obj.__type === 'Verdict' && typeof obj.outcome === 'string') {
      const reason = typeof obj.reason === 'string' ? obj.reason : '';
      switch (obj.outcome) {
        case 'ALLOW': return { approved: true, message: 'Approved' };
        case 'DENY': return { approved: false, message: reason || 'Denied' };
        // 需人工批准 / 升级：当前 ExecutionDecision 枚举无对应态，先按 indeterminate 记录，
        // 子项目 3 扩展枚举后改为专属态。绝不能落入 approved 或 denied。
        case 'REQUIRE_APPROVAL':
        case 'ESCALATE': return { approved: false, indeterminate: true, message: reason };
        default: return { approved: false, indeterminate: true, message: `Unknown verdict outcome: ${obj.outcome}` };
      }
    }
```

- [ ] **Step 4: 测试直到通过，相关套件全跑**

```bash
pnpm vitest run src/__tests__/services/cnl-result-parser.test.ts src/__tests__/services/policy
pnpm typecheck 2>/dev/null || pnpm tsc --noEmit
```

- [ ] **Step 5: 提交**

```bash
git add src/services/policy/cnl-executor.ts src/__tests__/services/cnl-result-parser.test.ts
git commit -m "feat(policy): 结果解析器识别 Verdict 内置值（ADR 0039）"
```

---

### Task 12: ADR 状态与修正、操作日志

**Files:**
- Modify: `aster-api/docs/adr/0039-verdict-primitive-and-rule-metadata.md`
- Modify: `aster-api/.claude/operations-log.md`（不存在则新建）

- [ ] **Step 1: 把「对 ADR 的修正」一节的内容写进 ADR**（§2.2 签名表加 E703；§3 加「TS 位置参数注解」与「两侧 BUILTIN_TYPE_NAMES」；§4 加 E703；§5.1 改为两条评估路径；§6.1 模板改写推迟到子项目 4；§10 加注解 IR 形状分歧与预算计量不对称两条未决），状态改为 `IMPLEMENTING`。

- [ ] **Step 2: 操作日志**记录每个仓库的分支名、最后提交、本地验证命令与结果（含未能在本机运行的测试及原因）。

- [ ] **Step 3: 提交**

```bash
cd /Users/rpang/IdeaProjects/aster-api && git add docs/adr/0039-verdict-primitive-and-rule-metadata.md .claude/operations-log.md && git commit -m "docs(adr): ADR 0039 实施期修正与操作日志"
```

---

## 对 ADR 的修正（执行前必读）

| ADR 原文 | 代码事实 | 本计划的处理 |
|---|---|---|
| §3「复用既有注解语法，不动语法」 | TS 解析器不接受位置参数（`@id("X")` 报错），只接受 `@id(value: "X")`；Java 两种都接受 | Task 4 让 TS 接受位置参数并以 `$0` 命名，与 Java 对齐。ANTLR 语法确实不动 |
| §3.2「Core IR 无结构改动」 | TS 注解 IR 是 `args:[{name,value}]`，Java 是 `params:{key:value}`；分歧被 cross-compiler 对 `annotations` 的剪枝掩盖 | 本子项目不统一形状（两侧治理检查各读自己的形状，键名 `$0` 一致）；登记为 ADR §10 未决，与 ADR 0037 节点身份工作合并处理 |
| §2「不动语法」隐含 `produce Verdict` 可用 | 两侧解析器把未知大写名推成类型参数（Java `AstBuilder.BUILTIN_TYPE_NAMES`，TS `decl-parser.BUILTINS`） | Task 2 / Task 4 把 `Verdict` 加入两份集合 |
| §4 三个错误码 | 两侧类型检查器都没有内置签名表，`Verdict.*` 参数个数无人检查 | 新增 `E703 GOV_VERDICT_CALL_ARITY`，两侧特判实现 |
| §5.1 只提 `PolicyEvaluationResult` | aster-api 有两条路径：部署策略返回 `PolicyEvaluationResult`，`evaluate-source` 返回 `EvaluationResponse` | Task 9 两条都贯穿 |
| §6.1「模板在本子项目改为推荐写法」 | cloud 解析器今天把 Verdict 对象判成 `Unknown result format` → denied；多语言模板用本地化关键字，改写涉及 zh/de 词法 | Task 11 先让 cloud 正确识别 Verdict；模板改写与信贷演示迁移推迟到子项目 4 |
| §2.3 两引擎逐字节一致 | TS 的 `chargeAllocation` 对普通对象计 0，Truffle 对 Map 计 size + 字符串长度；该不对称对 Ok/Err 等既有值早已存在 | 不在本子项目处理；登记 ADR §10 |

## 本地验证总顺序

1. core：`./gradlew --offline test goldenTest`
2. ts：`pnpm typecheck && pnpm lint && pnpm test:unit`
3. truffle：`./gradlew --offline test`
4. core `publishToMavenLocal -x test` → ts `pnpm build` → aster-lang-test 三道门禁
5. api：`./gradlew --offline :replay:test && ./gradlew --offline test`
6. cloud：`pnpm vitest run src/__tests__/services`

发布（release train 按 ADR 0023 bump lockstep 版本）不在本计划内，由用户决定时机。
