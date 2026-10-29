# ADR 0051 领域词汇按位置解析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把领域词汇从“解析前全局文本替换”改为“解析后按语法位置的两阶段名称解析（R1 AST / R2 Core）”，两引擎一致，并贯通 aster-api 与 aster-cloud。

**Architecture:** canonicalize 只保留关键词/别名/字面量宏；新增 R1（AST 上解析声明与类型位置）与 R2（Core 上按显式声明类型解析字段、期望枚举成员、模式构造器）；词汇以按角色/作用域的 `VocabularyScopes` 提供，大小写比较用共享的 Unicode 15.1 简单折叠表；envelope 增加词汇段、toolchainId 增加 `resolver`/`casefold`。

**Tech Stack:** TypeScript（aster-lang-ts，node:test）、Java 25 + Gradle（aster-lang-core、aster-api/Quarkus）、Node 24 + vitest（aster-cloud）、aster-lang-test parity 脚本、PostgreSQL 17。

**Spec:** `aster-api/docs/adr/0051-position-aware-vocabulary-resolution.md`（ACCEPTED，§14 已决）

## Global Constraints

- 不做向后兼容：删除 `translateIdentifiers*`（TS 与 Java），不保留开关或双轨。
- 大小写比较：NFC 后，Unicode **15.1** CaseFolding.txt 的 **C + S** 简单折叠；禁止使用宿主 `toLowerCase()` 做名称比较。
- `toolchainId` 格式：`abi=1.1;core=<engine>;validator=<v>;resolver=1;casefold=15.1;build=<b>`。
- envelope 固定 5 段：`content, aliasSetJson, locale, toolchainId, scopesJson`；无词汇 `scopesJson = ""`；删除 4 参签名。
- `canonicalScopesJson()`：键按码点排序、数组按 `(canonical, written)` 排序、所有字符串 NFC、含 `"schemaVersion":1`；TS/Java/cloud 输出逐字节一致。
- 诊断码（两引擎同号）：E800 `UNRESOLVED_TYPE`、E801 `UNRESOLVED_FIELD`、E802 `UNRESOLVED_NAME`、E803 `UNRESOLVED_PATTERN`、E804 `AMBIGUOUS_NAME`、E805 `AMBIGUOUS_FIELD`、E806 `AMBIGUOUS_PATTERN`、E807 `DUPLICATE_DECLARATION`、W800 `SHADOWED_VOCABULARY`、W801 `RESOLVED_WITHOUT_TYPE`。执行前先确认 E800–E807/W800–W801 在两引擎未被占用。
- 诊断文案 zh/en/de/hi 四语齐全。
- field 词条必须有 parent；模块路径不本地化；枚举成员与函数/类型同名且同时命中 → E804。
- 任一 E8xx 使编译失败、**不产出 IR**；出口 IR 只含规范名。
- `Origin.written/role` 仅在 `written !== canonical` 时写入；进 parity（`includeResolution=true`），不进 `contentHash`。
- 代码注释简体中文、描述意图；函数缩进 ≤3 层；提交尾行 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。
- 测试库端口：只用自建临时容器（如 55470+ / 6400+），禁止 55432、55433、6379、6380 与本地 `aster-pg` 容器。

## Review Focus

1. **语法不完整的源码（编辑中）**：LSP/编辑器不应崩溃或清空高亮——R1 只对可解析片段运行，R2 跳过；在 Task 8 加测试。
2. **同一 struct 中两个字段本地化写法仅大小写不同（`Alter`/`alter` → 不同规范字段）**：精确写法命中各自字段，不报歧义；在 Task 4 加测试。
3. **词汇为空或只含字面量宏**：行为与无词汇完全一致（IR、envelope 第 5 段为空串）；在 Task 6 与 Task 15 加测试。
4. **导入模块中声明的类型/函数与本模块词汇同名**：导入声明属于“模块声明”层，优先于词汇；在 Task 4 加测试。
5. **执行输入中出现 R2 无法确定类型的嵌套对象**：cloud 透传不映射、不报错（与 #626 规则一致）；在 Task 19 加测试。

---

## Phase 0 — aster-lang-locales

### Task 1: Unicode 15.1 简单折叠表与生成器

**Files:**
- Create: `casefold/CaseFolding-15.1.0.txt`（官方原文件，提交入库）
- Create: `casefold/generate.mjs`
- Create: `casefold/casefold-15.1.json`（生成物：`{"version":"15.1","map":{"<hex>":"<hex>",…}}`，仅 C 与 S 行）
- Create: `casefold/test-vectors.json`（ADR §6.3 测试向量表，含期望）
- Test: `casefold/generate.test.mjs`

**Interfaces:**
- Produces: `casefold-15.1.json`（码点 → 码点，单码点映射）、`test-vectors.json`（`[{a, b, equal, note}]`），随 locales 发布为资源，供 Task 2 与 Task 9 生成各自常量表。

- [ ] **Step 1: 写失败测试**：`generate.test.mjs` 断言生成物只含状态 C/S、`map["1E9E"] === "00DF"`、`map["03C2"] === "03C3"`、`map["01C5"] === "01C6"`、不含 `"0130"` 键、条目数等于 txt 中 C+S 行数。
- [ ] **Step 2: 运行确认失败**：`node --test casefold/generate.test.mjs` → FAIL（生成器不存在）。
- [ ] **Step 3: 实现 `generate.mjs`**：解析 txt（忽略注释与 F/T 行），输出排序后的 JSON；写入 `test-vectors.json`（ADR §6.3 表的 8 行原样）。
- [ ] **Step 4: 运行确认通过**：同上命令 → PASS；`git diff --exit-code casefold/casefold-15.1.json` 在重复生成后无变化。
- [ ] **Step 5: 提交**：`feat(casefold): Unicode 15.1 简单大小写折叠表与测试向量（ADR 0051）`。

---

## Phase 1 — aster-lang-ts

### Task 2: 折叠函数与 VocabularyScopes

**Files:**
- Create: `scripts/generate-casefold.ts`（读取 locales 的 `casefold-15.1.json` → 生成下行常量文件）
- Create: `src/config/lexicons/identifiers/casefold-table.generated.ts`
- Create: `src/config/lexicons/identifiers/casefold.ts`
- Create: `src/config/lexicons/identifiers/scopes.ts`
- Modify: `src/config/lexicons/identifiers/types.ts`（删除 `IdentifierIndex.toCanonical` 作为翻译入口；保留 `toLocalized`、`literals`）
- Test: `test/unit/identifiers/casefold.test.ts`、`test/unit/identifiers/scopes.test.ts`

**Interfaces:**
- Produces:
  - `foldKey(s: string): string`（NFC → 逐码点简单折叠）
  - `nfc(s: string): string`
  - `type Role = 'TYPE' | 'FIELD' | 'MEMBER' | 'FUNCTION'`
  - `interface Candidate { written: string; canonical: string; kind: IdentifierKind; parent?: string; source: 'term' | 'synonym' }`
  - `interface VocabularyScopes { schemaVersion: 1; types: ReadonlyMap<string, Candidate[]>; fields: ReadonlyMap<string, ReadonlyMap<string, Candidate[]>>; enumMembers: ReadonlyMap<string, ReadonlyMap<string, Candidate[]>>; functions: ReadonlyMap<string, Candidate[]>; literals: ReadonlyMap<string, string> }`（外层键为 `foldKey(written)`；`fields`/`enumMembers` 的一级键为规范父名）
  - `buildVocabularyScopes(vocab: DomainVocabulary): VocabularyScopes`
  - `canonicalScopesJson(scopes: VocabularyScopes): string`
  - `lookup(map, written): { exact: Candidate[]; folded: Candidate[] }`

- [ ] **Step 1: 写失败测试**：`casefold.test.ts` 逐条跑 `test-vectors.json`，断言 `foldKey(a) === foldKey(b)` 与 `equal` 一致；`scopes.test.ts` 断言：struct `Stufe` 与 field `stufe`（parent `Level`）分别进入 `types` 与 `fields["Level"]`；同 struct 下 `Alter`/`alter` 两个字段条目都保留（不覆盖）；field 无 parent 时抛 `VocabularyScopeError('field_parent_required')`；`canonicalScopesJson` 对同一词汇两次构建输出相同字符串，且等于 `test/fixtures/scopes/sample.canonical.json`。
- [ ] **Step 2: 运行确认失败**：`pnpm vitest run test/unit/identifiers` 或仓库测试命令 → FAIL。
- [ ] **Step 3: 实现**：生成常量表（`Record<number, number>`）；`foldKey` 用 `String.prototype.normalize('NFC')` 后按码点查表；`buildVocabularyScopes` 按 kind 分桶、保留全部候选；`canonicalScopesJson` 按 Global Constraints 排序与 NFC。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(identifiers): VocabularyScopes 与 Unicode 15.1 折叠（ADR 0051）`。

### Task 3: 诊断码 E800–E807 / W800–W801

**Files:**
- Modify: `src/diagnostics/error_codes.ts`
- Modify: 四语诊断文案文件（与现有 E7xx 同处）
- Test: `test/unit/diagnostics/resolution-codes.test.ts`

**Interfaces:**
- Produces: `ErrorCode.UNRESOLVED_TYPE = 'E800'` … `ErrorCode.DUPLICATE_DECLARATION = 'E807'`，`WarningCode.SHADOWED_VOCABULARY = 'W800'`，`WarningCode.RESOLVED_WITHOUT_TYPE = 'W801'`；消息参数：`{ written, role, candidates?: Array<{canonical, source, parent?}> }`。

- [ ] **Step 1: 写失败测试**：断言 10 个码存在、四语文案均非空、en/de/hi 文案不含 CJK、每条消息引用 `{written}`。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**：按现有码的定义方式新增；E804/E806 文案包含“写限定名（如 `Stufe.Hoch`）或加类型注解”的提示。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(diagnostics): 名称解析诊断码 E800–E807/W800–W801`。

### Task 4: R1 声明与类型位置解析

**Files:**
- Create: `src/frontend/resolve-declarations.ts`
- Create: `src/frontend/resolution-context.ts`
- Modify: `src/ast/ast.ts`（标识符承载节点增加可选 `resolved?: { canonical: string; role: ResolvedRole; written: string }`）
- Test: `test/unit/frontend/resolve-declarations.test.ts`

**Interfaces:**
- Consumes: Task 2 `VocabularyScopes`、`lookup`、`foldKey`；Task 3 码。
- Produces:
  - `type ResolvedRole = 'TYPE_DECL' | 'FIELD_DECL' | 'MEMBER_DECL' | 'FUNC_DECL' | 'TYPE' | 'FIELD_REF' | 'MEMBER_REF' | 'FUNC_REF' | 'LOCAL'`
  - `interface ResolutionContext { scopes?: VocabularyScopes; imports: ImportedDeclarations }`
  - `resolveDeclarations(module: AST.Module, ctx: ResolutionContext): { module: AST.Module; diagnostics: Diagnostic[] }`

- [ ] **Step 1: 写失败测试**（每条一个 test，期望写入 `resolved.canonical`）：
  - `Definiere Fahrer hat alter` + 词汇 Driver/Fahrer、age/alter(parent Driver) → Data `Driver`，字段 `age`；
  - struct `Stufe`↔`Level` 与 field `stufe`↔`level`(parent `Fahrer`/`Driver`) 并存 → 类型位置 `Level`、字段声明 `level`；
  - 参数 `fahrer als Fahrer` → 参数名保持 `fahrer`（LOCAL），类型 `Driver`；
  - `Liste von Fahrer`、`Ergebnis von Fahrer`、FuncType 参数与返回、`TypeAlias` 右侧 → 全部 `Driver`；
  - 同 struct 字段 `Alter`/`alter` 映射到不同规范字段 → 各自精确命中（Review Focus 2）；
  - 导入模块声明 `Fahrer` 与词汇同名 → 取导入声明（Review Focus 4）；
  - `Stufe.Hoch` → MEMBER_REF `Level.High`；
  - 两个声明解析到同一规范名 → E807；折叠后多候选且无精确 → E804；
  - `Module` 路径、`TypeVar`、workflow/step 名、注解参数不被改写。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**：穷尽式 visitor（`switch (node.kind)` + `never` 检查）；作用域栈处理 LOCAL；声明按 ADR §6.1，引用按 §6.2 第 1、2、4、5 步；R1 不处理点分后续段、期望枚举裸名、主语未知的 PatternCtor（留给 Task 5）。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(resolve): R1 声明与类型位置名称解析（ADR 0051）`。

### Task 5: R2 类型驱动解析与 Origin 扩展

**Files:**
- Create: `src/core/resolve-typed-references.ts`
- Modify: `src/types.ts`（`Origin` 增加 `written?: string; role?: ResolvedRole`）
- Modify: `src/lower_to_core.ts`（把 R1 的 `resolved` 落为规范名；未定点段保留原写法并带 `ResolutionMark`）
- Test: `test/unit/core/resolve-typed-references.test.ts`

**Interfaces:**
- Consumes: Task 4 `ResolutionContext`、`ResolvedRole`。
- Produces: `resolveTypedReferences(core: Core.Module, ctx: ResolutionContext): { module: Core.Module; diagnostics: Diagnostic[] }`

- [ ] **Step 1: 写失败测试**（ADR §4 三态表逐格一条）：
  - `x.alter` 且 `x als Fahrer` → `x.age`；`Set x.alter` 同理；
  - 期望类型为 `Stufe` 的 `Return Hoch` → `Level.High`；规则 `hoch` 同时存在 → E804；
  - Let 无注解复杂表达式后 `y.alter`：全局唯一候选 → 采用 + W801；多候选 → E805；零 → E801；
  - Match 主语为参数 → Case 构造器按其类型解析；主语未知且唯一 → W801；多 → E806；
  - 只用显式声明类型：Let 右侧为字面量 / ListLiteral / Some / IfExpr 时类型为未知；
  - 解析后 Core 中无本地化名；`origin.written/role` 只在 `written !== canonical` 时出现；
  - 任一 E8xx 时返回 diagnostics 且调用方不得发出 IR（由 Task 6 断言）。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**：独立 pass，按 ADR §4 类型来源表推断；不调用 `typecheckModule`。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(resolve): R2 类型驱动解析与 Origin written/role（ADR 0051）`。

### Task 6: 流水线接线、删除全局替换、导出 resolvePolicyTypes

**Files:**
- Modify: `src/frontend/canonicalizer.ts`（删除 `translateIdentifiers` 与 `translateIdentifiersInSegment`；`CanonicalizerOptions` 去掉词汇翻译，保留字面量宏）
- Modify: `src/browser.ts`（`compile`、`compileAndTypecheck`、emit-core 依次跑 R1 → lower → R2 → typecheck；`CompileOptions.vocabulary?: DomainVocabulary`）
- Create: `src/frontend/resolve-policy-types.ts`
- Modify: `src/config/lexicons/identifiers/registry.ts`（`getWithCustom` 改为返回 `VocabularyScopes`）
- Test: `test/integration/vocabulary-resolution.test.ts`、更新受影响的既有 canonicalizer 测试

**Interfaces:**
- Consumes: Tasks 2–5。
- Produces（从包入口与 `/browser` 导出）：
  - `buildVocabularyScopes`、`canonicalScopesJson`、`foldKey`
  - `resolvePolicyTypes(source: string, opts: { locale: string; vocabulary?: DomainVocabulary; aliasSet?: Record<string, string[]> }): { ok: true; types: PolicyTypes } | { ok: false; diagnostics: Diagnostic[] }`
  - `interface PolicyTypes { structs: Array<{ canonical: string; written: string; fields: Array<{ canonical: string; written: string; type: TypeRef }> }>; enums: Array<{ canonical: string; written: string; members: Array<{ canonical: string; written: string }> }>; functions: Array<{ canonical: string; written: string; params: Array<{ name: string; type: TypeRef }>; returnType?: TypeRef }> }`

- [ ] **Step 1: 写失败测试**：ADR §1 四个生产复现端到端编译成功并执行出正确结果（Stufe/stufe、hoch/Hoch 用 `Stufe.Hoch`、参数 `fahrer`、跨 struct 同名字段）；`grep translateIdentifiers` 在 `src/` 为 0；词汇为空或只含字面量宏时 IR 与无词汇逐字节相同（Review Focus 3）；含 E8xx 时 `compile` 返回 `success:false` 且无 `core`。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现接线**；`resolvePolicyTypes` 只跑 R1 + R2（不做完整 typecheck），从已解析声明组装 `PolicyTypes`。
- [ ] **Step 4: 运行全量测试**：`pnpm test`（unit + integration + golden）→ 全部 PASS；有意改变的 golden 逐例在提交说明中列出。
- [ ] **Step 5: 提交**：`feat(compile): 名称解析接入编译流水线并删除解析前标识符替换（ADR 0051）`。

### Task 7: toolchainId 分量常量

**Files:**
- Modify: `src/version.ts`（或现有导出构建/ABI 版本的位置）
- Test: `test/unit/version.test.ts`

**Interfaces:**
- Produces: `RESOLVER_VERSION = 1`、`CASEFOLD_VERSION = '15.1'`、`CORE_IR_ABI = '1.1'`（导出供 cloud 组装 `toolchainId`）。

- [ ] **Step 1: 写失败测试**：断言三个常量值。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**常量导出。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(version): resolver/casefold/abi 版本常量`。

### Task 8: LSP 按角色悬停/补全与降级

**Files:**
- Modify: `src/lsp/canonicalize-options.ts`（改为构造 `ResolutionContext`）
- Modify: LSP hover / completion 处理器（现有 `src/lsp/` 下对应文件）
- Test: `test/unit/lsp/resolution-hover.test.ts`、`test/unit/lsp/resolution-completion.test.ts`

**Interfaces:**
- Consumes: Task 4 `resolveDeclarations`、Task 5 `Origin.written/role`。

- [ ] **Step 1: 写失败测试**：悬停 `alter` 显示 `alter → age（字段 · Driver）`；`x.` 后补全只列 `x` 所属 struct 的字段（本地化写法）；源码有语法错误时 hover/completion 不抛异常、R2 跳过、仍返回 R1 可得信息（Review Focus 1）。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(lsp): 按位置角色的悬停与补全（ADR 0051）`。

---

## Phase 2 — aster-lang-core（Java，与 Phase 1 同构）

### Task 9: CaseFold 与 VocabularyScopes（Java）

**Files:**
- Create: `src/main/java/aster/core/identifier/CaseFold.java`（构建期由 locales 的 `casefold-15.1.json` 生成常量，Gradle 任务 `generateCaseFold`）
- Create: `src/main/java/aster/core/identifier/VocabularyScopes.java`、`Candidate.java`
- Modify: `src/main/java/aster/core/identifier/IdentifierIndex.java`（删除扁平翻译入口）
- Test: `src/test/java/aster/core/identifier/CaseFoldTest.java`、`VocabularyScopesTest.java`

**Interfaces:**
- Produces: `CaseFold.foldKey(String): String`；`VocabularyScopes.build(DomainVocabulary): VocabularyScopes`；`VocabularyScopes.canonicalJson(): String`；结构与 Task 2 一致。

- [ ] **Step 1: 写失败测试**：跑同一份 `test-vectors.json`；`canonicalJson()` 与 TS 共享 fixture `sample.canonical.json` 逐字节相等；与 Task 2 相同的分桶断言。
- [ ] **Step 2: 运行确认失败**：`./gradlew test --tests 'aster.core.identifier.*'` → FAIL。
- [ ] **Step 3: 实现**（NFC 用 `java.text.Normalizer`；禁止 `toLowerCase`）。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(identifier): VocabularyScopes 与 Unicode 15.1 折叠（ADR 0051）`。

### Task 10: DeclarationResolver（R1，Java）

**Files:**
- Create: `src/main/java/aster/core/resolve/DeclarationResolver.java`、`ResolutionContext.java`、`ResolvedRole.java`
- Modify: `src/main/java/aster/core/typecheck/ErrorCode.java`（E800–E807、W800–W801，四语文案）
- Modify: `src/main/java/aster/core/ast/*`（标识符节点增加 `resolved` 字段，sealed 接口穷尽）
- Test: `src/test/java/aster/core/resolve/DeclarationResolverTest.java`

**Interfaces:**
- Produces: `DeclarationResolver.resolve(Module ast, ResolutionContext ctx): ResolutionResult<Module>`（`ResolutionResult<T>(T value, List<Diagnostic> diagnostics)`）。

- [ ] **Step 1: 写失败测试**：与 Task 4 相同的用例清单（同名同输入），另加 Java 独有节点 `TypeAlias`、`FuncType`、`RetryPolicy`/`Timeout` 不被改写。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**（sealed 接口 + switch 模式匹配穷尽）。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(resolve): R1 DeclarationResolver（ADR 0051）`。

### Task 11: TypedReferenceResolver（R2，Java）与接线

**Files:**
- Create: `src/main/java/aster/core/resolve/TypedReferenceResolver.java`
- Modify: `src/main/java/aster/core/ir/CoreModel.java`（Origin 增加 `written`、`role`）
- Modify: `src/main/java/aster/core/lowering/CoreLowering.java`
- Modify: `src/main/java/aster/core/canonicalizer/Canonicalizer.java`（删除 `translateIdentifiers`，保留字面量宏；构造器不再接受用于翻译的索引）
- Test: `src/test/java/aster/core/resolve/TypedReferenceResolverTest.java`、更新受影响的 Canonicalizer 测试

**Interfaces:**
- Produces: `TypedReferenceResolver.resolve(CoreModel.Module core, ResolutionContext ctx): ResolutionResult<CoreModel.Module>`；编译入口顺序 R1 → lower → R2 → typecheck。

- [ ] **Step 1: 写失败测试**：与 Task 5 相同用例；ADR §1 四个复现经 Java 管线编译成功；`grep translateIdentifiers` 为 0。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 运行全量**：`./gradlew test` → PASS。
- [ ] **Step 5: 提交**：`feat(resolve): R2 TypedReferenceResolver 并删除解析前标识符替换（ADR 0051）`。

---

## Phase 3 — aster-lang-test

### Task 12: ir-normalize `includeResolution`

**Files:**
- Modify: `packages/js/src/ir-normalize.ts`
- Modify: `scripts/parity-tier1.mjs`、Java parity runner（传 `includeResolution: true`）
- Test: `packages/js/test/ir-normalize.test.ts`

**Interfaces:**
- Produces: `normalizeIr(ir, { originMode, includeResolution = false })`；`includeResolution=true` 时 `normalizeOrigin` 额外保留 `written`、`role`。

- [ ] **Step 1: 写失败测试**：默认选项下含 `written/role` 的 IR 与不含的归一化结果相同（contentHash 不变）；`includeResolution:true` 时不同。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 运行确认通过**；`contentHash` 现有基线测试仍 PASS。
- [ ] **Step 5: 提交**：`feat(parity): includeResolution 选项（ADR 0051）`。

### Task 13: 语料自带词汇与新增 parity 语料

**Files:**
- Modify: `corpus/tier1-parity/manifest.json`（条目增加 `"vocab": true`）
- Create: `corpus/tier1-parity/vocab-*/<case>.aster` 与同名 `<case>.vocab.json`（≥ 16 例，覆盖 de/zh/hi：ADR §1 四个复现、跨 struct 同名字段、同 struct 仅大小写不同字段、导入声明遮蔽、W800、E804 函数/成员冲突、E805、E806、E807、嵌套类型参数、FuncType、TypeAlias、模式构造器、workflow 步骤名与词条同名）
- Create: `corpus/shared-vectors/envelope-vectors.json`（envelope 共享向量：无词汇、单 struct、含同义词与 NFC 组合字符）
- Modify: `scripts/parity-tier1.mjs`、Java runner（读取 `.vocab.json` 并传入两引擎）
- Test: 门禁本身

**Interfaces:**
- Produces: `envelope-vectors.json`：`[{ content, aliasSetJson, locale, toolchainId, scopesJson, expectedSha256 }]`，供 Task 15 与 Task 18 共用。

- [ ] **Step 1: 加语料与 runner 改动**；负例（E80x）使用 `mustReject` 机制并带 `expectCode`。
- [ ] **Step 2: 运行门禁**：`node scripts/parity-tier1.mjs --mode=ir --full`、`--mode=eval`、`--mode=parse`（Node ≥ 23.6，两引擎为本计划分支的同名分支）→ 新增样例两引擎一致；负例双拒并给出同一码。
- [ ] **Step 3: 提交**：`test(parity): 按位置解析语料与语料自带词汇（ADR 0051）`。

---

## Phase 4 — aster-api

### Task 14: VocabularyScopes 入口与缓存键

**Files:**
- Modify: `replay/src/main/java/io/aster/policy/parser/InProcessCnlParser.java`（`parse(src, scopes)` → R1/R2）
- Modify: `replay/src/main/java/io/aster/replay/core/ReplayExecutionCore.java`（`buildVocabularyIndex` 改为 `buildVocabularyScopes`）
- Modify: `src/main/java/io/aster/policy/compiler/PolicyCompiler.java`、`replay/.../DynamicCnlExecutor.java`（编译缓存键加入 `toolchainId` 与 `scopesHash`）、`src/main/java/io/aster/policy/cedar/CedarExportService.java`
- Test: `PolicyCompilerVocabularyTest`、`VocabularyExecutionTest`、`CedarExportService` 带词汇用例、`ReplayExecutionCoreTest`

**Interfaces:**
- Consumes: Phase 2 `VocabularyScopes`、`DeclarationResolver`、`TypedReferenceResolver`。

- [ ] **Step 1: 写失败测试**：ADR §1 四个复现经 `/policies/compile`、evaluate-source、What-If、Cedar 导出各一条成功；同源码不同词汇不共用编译缓存。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**；`VocabularyBounds` 不变，仍先于构建执行。
- [ ] **Step 4: 运行**：`QUARKUS_HTTP_TEST_PORT=0 … ./gradlew --offline :test :replay:test --continue`（自建容器）→ 仅已知 `PrismaDatabaseE2ETest` 失败。
- [ ] **Step 5: 提交**：`feat(api): 编译/执行/回放/Cedar 切换到按位置解析（ADR 0051）`。

### Task 15: envelope 第 5 段与 toolchainId

**Files:**
- Modify: `src/main/java/io/aster/policy/entity/PolicyVersion.java`（`computeSourceEnvelope(String content, String aliasSetJson, String locale, String toolchainId, String scopesJson)`，删除 4 参）
- Modify: `src/main/java/io/aster/policy/stability/ToolchainIdentityProvider.java`
- Test: `PolicyVersionEnvelopeTest`（读取 `envelope-vectors.json`）、`ToolchainIdentityProviderTest`

- [ ] **Step 1: 写失败测试**：全部共享向量 sha256 相等；无词汇第 5 段为 `0:|`；`currentToolchainId()` 匹配 `^abi=1\.1;core=java;validator=[^;]+;resolver=1;casefold=15\.1;build=[^;]+$`（Review Focus 3 的 envelope 部分）。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**；全部调用点改为传入 `scopesJson`（已审批取冻结快照，草稿取实时词汇）。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(api): source envelope 纳入词汇段，toolchainId 增加 resolver/casefold（ADR 0051）`。

### Task 16: 存量重编译（aster-api 侧）

**Files:**
- Create: Flyway 迁移 `V6.xx.0__resolver_migration_columns.sql`（`policy_versions` 增加 `resolver_version`、`resolver_migration_error`、`archived_by_resolver_migration boolean default false`、`status_before_archive`）
- Create: `src/main/java/io/aster/policy/admin/ResolverMigrationResource.java`（`POST /api/admin/resolver-migration/run?batchSize=50`，内部调用方认证）
- Test: `ResolverMigrationResourceTest`

**Interfaces:**
- Produces: 端点返回 `{ processed, succeeded, failed, failures: [{ id, error }] }`。

- [ ] **Step 1: 写失败测试**：成功行写新 IR/envelope/`resolver_version=1`；失败行旧产物不变、`status=ARCHIVED`、`archived_by_resolver_migration=true`、`resolver_version` 为 NULL；重跑跳过成功行、重试失败行；修复后成功则恢复 `status_before_archive` 并清标记；人工归档行状态不被改动；中途中断后重跑结果一致。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**（每行一个事务，主键升序分批）。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(api): resolver 迁移任务（ADR 0051 §9）`。

---

## Phase 5 — aster-cloud

### Task 17: envelope 与 cloudToolchainId

**Files:**
- Modify: `src/lib/policy-alias.ts`（`computeSourceEnvelope(content, aliasSetJson, locale, toolchainId, scopesJson)`；`cloudToolchainId()` 使用 Task 7 常量）
- Modify: 全部调用点
- Test: `src/__tests__/lib/source-envelope.test.ts`（读取 `envelope-vectors.json`）

- [ ] **Step 1: 写失败测试**：共享向量全部相等；`cloudToolchainId()` 匹配 `^abi=1\.1;core=ts;validator=[^;]+;resolver=1;casefold=15\.1;build=[^;]+$`；无词汇第 5 段为空串。
- [ ] **Step 2: 运行确认失败**：`corepack pnpm exec vitest run src/__tests__/lib/source-envelope.test.ts` → FAIL。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(policy): envelope 纳入词汇段与新 toolchainId（ADR 0051）`。

### Task 18: 执行输入映射复用引擎 resolvePolicyTypes

**Files:**
- Modify: `src/lib/execution-input/policy-types.ts`（改为调用 `@aster-cloud/aster-lang-ts` 的 `resolvePolicyTypes`；删除签名桩回退 `bodyIndentAfter`/`stubFunctionBodies` 与临时注册词汇逻辑）
- Modify: `src/lib/execution-input/mapper.ts`（拼写表来自 `PolicyTypes`；删除 cloud 自建大小写冲突表，改为直接用引擎候选）
- Test: 现有 `execution-input-*.test.ts` 全部改为基于引擎；新增 ADR §1 复现与 Review Focus 5

**Interfaces:**
- Consumes: Task 6 `resolvePolicyTypes`、`PolicyTypes`。

- [ ] **Step 1: 写失败测试**：Stufe/stufe 策略本地化输入执行成功；R2 无法确定类型的嵌套对象原样透传（Review Focus 5）；`resolvePolicyTypes` 失败时仍返回 422 `input_type_extraction_failed`；#626/#632/#638 既有用例保持通过。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**；依赖升级到包含 Phase 1 的 aster-lang-ts 版本（本地开发用 `file:` tgz，合并前切回发布版本）。
- [ ] **Step 4: 运行**：`corepack pnpm exec tsc --noEmit && corepack pnpm lint && corepack pnpm test:run` → PASS。
- [ ] **Step 5: 提交**：`refactor(execution-input): 复用引擎按位置解析的类型表（ADR 0051）`。

### Task 19: 编辑器、词条校验、提示词与文档

**Files:**
- Modify: `src/hooks/useUserVocabularyRegistration.ts`、`src/components/policy/monaco-policy-editor.tsx`（传 `VocabularyScopes`；同时更新陈旧的 “SSE” 注释）
- Modify: `src/lib/domain-vocabulary-validation.ts`（仅拒绝同角色同作用域、NFC 后写法完全相同但规范名不同的条目；仅大小写不同允许）
- Modify: AI 生成 / Suggest 提示词（说明按位置解析与限定写法 `Stufe.Hoch`）
- Modify: `docs/api/policy-execute-input.md`（删除“参数名与词条同名需用规范 key”约定）
- Test: `useUserVocabularyRegistration.test.ts`、`domain-vocabulary-validation.test.ts`、提示词快照测试

- [ ] **Step 1: 写失败测试**：`Stufe`(struct)+`stufe`(field) 可保存；同 parent 下两个 field 写法完全相同但规范名不同被拒（本地化错误码）；编辑器注册的 scopes 与服务端 `canonicalScopesJson` 一致。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(vocabulary): 编辑器与词条校验适配按位置解析（ADR 0051）`。

### Task 20: 存量重编译（cloud 侧）

**Files:**
- Create: drizzle 迁移 `drizzle/00xx_resolver_migration_columns.sql`（`"PolicyVersion"` 增加 `resolverVersion`、`resolverMigrationError`、`archivedByResolverMigration boolean default false`、`statusBeforeArchive`；PR 带 `migration-and-code` 标签与 `## 迁移窗口容错`）
- Create: `scripts/resolver-migration.ts`（逐行事务、主键升序每批 50、编译经 aster-api 签名接口）
- Test: `src/__tests__/integration/resolver-migration.integration.test.ts`（真实 PG）

- [ ] **Step 1: 写失败测试**：与 Task 16 相同的状态机断言（成功/失败/重试/恢复/人工归档不动/中断重跑）。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**；`drizzle-kit generate` 无漂移。
- [ ] **Step 4: 运行确认通过** → PASS。
- [ ] **Step 5: 提交**：`feat(cloud): resolver 迁移脚本（ADR 0051 §9）`。

---

## Phase 6 — 发布与上线

### Task 21: 发布列车与上线 Job

**Files:**
- Modify: `aster-lang-platform/release-plan.json`（locales、core、ts、test、api、cloud 版本）
- Create: k3s `apps/aster-lang/jobs/resolver-migration-job.yaml`（依次调用 aster-api 端点与 cloud 脚本，输出汇总；运行前 `pg_dump` 两库对应表到持久卷）
- Create: `aster-api/docs/runbooks/adr0051-rollout.md`（执行顺序、验证命令、回滚：列车回上一 tag + 用 dump 恢复 + 回滚迁移删除新增列）

- [ ] **Step 1: 列车 dry run**：按 locales → core/ts → test → api → cloud 顺序，`check-artifact.py`（python3.13）全部 OK。
- [ ] **Step 2: 发布后在生产执行 Job**；验证：迁移报告失败数与预期一致（仅刻意冲突样例）；ADR §1 四个复现在生产编辑器与执行页通过。
- [ ] **Step 3: 提交 runbook 与 Job**：`chore(rollout): ADR 0051 上线 Job 与 runbook`。
