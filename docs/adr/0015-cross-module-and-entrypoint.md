# ADR 0015 — 跨模块引用 + 入口判定（cross-module linking & entry-point）

Status: **全部 IMPLEMENTED** — 2026-06-08.
阶段1+2（入口语义 + @entry 双引擎+Monaco）：aster-api#22/#23、aster-lang-core#3、
aster-lang-ts#8、aster-cloud#29。
阶段3（跨模块运行时）：library_visible aster-api#24；version 子句 aster-lang-core#4 +
aster-lang-ts#9；ModuleGraphLinker aster-lang-core#5；ModuleResolver+执行集成 aster-api#25
（Truffle 跨模块端到端 CI 验证 Score.f=42）；模块目录 endpoint aster-api#26 + 编辑器体验
aster-cloud#30。共 12 个 PR，4 仓库，全部合并 + CI 绿。
源于自治会话对 “Aster Lang 如何跨 module 引用 / 如何确定 main 入口” 的双模型分析
（后端 codex + 前端 codex + 主AI 一手代码查证），三方强一致地发现：两个机制
**语法层已立、运行时半残**，且对 multi-team 协作与编辑器用户都有真实风险。

本 ADR 锁定设计决策，分三阶段实施。实施计划见
`.claude/plan/cross-module-and-entrypoint.md`。

## 进度

- **阶段1（入口语义收紧）— DONE 2026-06-08**（PR aster-api#22）：EntryPointSelector
  纯逻辑（Selected/Ambiguous/NotFound/NoRule）、ParseResult.functionNames、
  DynamicCnlExecutor 接入 + AmbiguousEntryException、SourcePolicyRequest 去
  "evaluate" 默认、EvaluationResponse executedFunction+diagnostics、feature flag
  aster.entry.legacy-evaluate-sentinel（默认 true）。EntryPointSelector 9 测试 +
  端到端 2，parser 包 28 全绿无回归。Codex 生成→Claude 审查（好品味通过）。
- **阶段2-core（@entry grammar/AST/validator）— DONE**（PR aster-lang-core#3）：
  grammar `funcDecl: annotation* RULE nameIdent`、Decl.Func/CoreModel.Func annotations、
  TypeChecker @entry 唯一性。669 测试 0 失败，tier1-parity PASS。
- **阶段2-ts（@entry parse/lowering/validator）— DONE**（PR aster-lang-ts#8）：
  decl-parser 声明级注解、typecheck 唯一性、annotations 空时省略（保 golden 基线）。
  parity 双向 PASS。
- **阶段2-api（@entry 接入入口选择）— DONE**（PR aster-api#23）：extractEntryFunctionName、
  EntryPointSelector annotatedEntry 优先级、端到端验证。
  **@entry 同行或独立行均支持**（grammar `(annotation NEWLINE*)* RULE`，aster-lang-core#9）。
- **阶段2-cloud（Monaco Rule 选择器 + @entry 高亮）— DONE**（PR aster-cloud#29）：
  lib/aster/rules.ts（extractRuleSymbols 三语+CJK+@entry 同行+Monaco range、chooseDefaultRule）、
  rule-selector.tsx、use-entry-rule-decorations.ts、policy-api 去 'evaluate' 默认 +
  executedFunction/diagnostics、[id]/execute route 接受 functionName（DB-resolved source 不破坏
  安全模型）、execute-policy-content 接入 RuleSelector + ENTRY_AMBIGUOUS 候选重跑。
  tsc 0 错误、全量 vitest 3350 通过。**阶段2 全部完成：@entry 从 grammar 到 UI 端到端可用。**
- **阶段3a（library_visible）— DONE**（PR aster-api#24）：Flyway V6.11.0 policy_versions 加
  library_visible BOOLEAN default false + 部分索引、PolicyVersion.findLibraryVersion/
  findLibraryVersions、PolicyVersionService.setLibraryVisible。
- 阶段3b/3c/3d（ModuleResolver 跨模块运行时核心）— 待办，设计见下「阶段3 实施细则」。

## 阶段3 实施细则（PR-3b/3c/3d 接续设计）

**已确认决策**：被引模块 Core IR = ModuleResolver 从 PolicyVersion.content（CNL 源码）
**加载时重编译**（request-scope cache，键 module+version+sourceHash），不存 Core IR JSON。

### PR-3b（aster-lang-core，纯数据不碰 DB，最复杂）
- `ModuleKey`：(moduleName, version) 值对象。`mangle()` → `risk_Scoring_v2__` 前缀。
- `ModuleGraph`：{ModuleKey → CoreModel.Module} + edges(from→to, alias)。aster-api 构建后传入。
- `ModuleGraphLinker.link(graph, rootKey, entryRef)`：
  1. 拓扑序遍历（含 cycle 防御，虽 aster-api 已检测）。
  2. 每个非 root 模块的 decls 符号**前缀重命名**（func name / Data/Enum typeName）。
  3. **深度改写所有跨模块引用**：CoreModel.Call.target(Name)、Construct.typeName、Name（含
     dotted alias `H.get`→`risk_Scoring_v2__.get`）→ rename map 解析。这是硬核部分，需遍历
     整个 Expr/Statement 树改写。
  4. 合并成单 CoreModel.Module（root 名），entry 解析到重命名后的名。
  5. 保留 trace 名映射（原 module/function → mangled，供 trace/诊断还原）。
- `checkImport`（TypeChecker.java:286 空实现替换）：导入符号入 SymbolTable（define），
  冲突 → IMPORT_SYMBOL_CONFLICT diagnostic。
- 验证：产物必须能被 GraalVM Truffle 执行（起 truffle 跑 linked program）；conformance golden。
- **风险点**：符号重命名遗漏某种引用节点 → 执行错误。务必枚举所有引用 Expr 类型
  （Call/Construct/Name/Match/FieldAccess 等），dump linked Core JSON 验证。

### PR-3c（aster-api ModuleResolver + 集成）
- `ModuleResolver.resolveGraph(tenantId, rootModule, rootImports)`：
  DFS 解析 Import → parsePinnedRef（path + version from alias `as vN`）；无版本 →
  IMPORT_VERSION_REQUIRED；PolicyVersion.findLibraryVersion(tenant, module, version)；
  null → MODULE_VERSION_NOT_FOUND(候选=findLibraryVersions)；跨 tenant/不可见 →
  MODULE_NOT_VISIBLE(visibility-safe，不泄露模块名)；循环 → MODULE_CYCLE。
  被引模块 content → InProcessCnlParser+CoreLowering 编译（cache）。
- SourcePolicyRequest 加 entry:{module,function}（EntryRef，优先 functionName）。
- 集成：parse→EntryPointSelector→ModuleResolver.resolveGraph→ModuleGraphLinker.link→
  单 Core JSON→GraalVM。响应加 resolvedEntry。
- feature flag `aster.modules.enabled`（默认 false，灰度）。

### PR-3d（ts conformance + cloud 模块目录）
- ts：等价 import symbol resolution + alias rewrite，同 conformance fixture。
- cloud：/api/aster/modules/catalog BFF（session 派生 tenant）+ useAsterModuleCatalog +
  Monaco Use 补全/hover/diagnostics + extractUseRefs。

---

## 背景：现状（一手代码查证）

### 跨模块引用
- 语法支持：`Use qualifiedName (as alias).`（`AsterParser.g4:198` importDecl），
  `Module app.` 模块头。AST 为 `Decl.Import{path, alias, span}`，降级保留到 Core IR。
- **运行时断链**：
  - `aster-lang-core` `TypeChecker.checkImport`（:286）是**空实现**：
    `// 导入检查暂时简化：仅记录模块路径 // 完整实现需要模块解析和符号导入`。
  - `aster-lang-ts` `resolveAlias`（alias.ts:24）只做**前缀重写**
    （`HttpClient.get`→`io.Http.get`），服务于 effect 推断。
  - 全仓 **0 个 ModuleLoader / resolveModule / moduleRegistry**。
  - `aster-api` `/evaluate-source` 只传**单个 source 字符串** → InProcessCnlParser →
    CoreLowering → 单个 Core JSON → GraalVM polyglot。被 `Use` 的外部模块**无来源**。
  - `io.Http` 等不是用户模块，是引擎内置 **effect/capability 命名空间**
    （`EffectConfig` 前缀匹配 `Http.`/`IO.`）。
- 结论：`Use` 当前 = 别名重写 + effect 归类，**不是**跨文件符号链接。

### 入口判定
- `DynamicCnlExecutor.executeInternal`（:146）：`targetFunction = functionName`；
  若 `null/blank/"evaluate"` → `firstFunctionName()`（`extractFirstFunctionName` =
  `decls.stream().filter(Decl.Func).findFirst()`，**声明顺序第一个 Rule**）。
- `/evaluate-source`（草稿源码）走 first-rule 默认；`/evaluate`（已发布策略）
  必须显式 `policyModule + policyFunction`，**不走** first-rule。
- **两个风险**：
  1. `"evaluate"` 是**魔法哨兵**（REST 文档把默认写成 `functionName:"evaluate"`，
     实现却把它当 “取第一个 Rule”）→ 用户若真有 `Rule evaluate` 被语义劫持。
  2. first-rule 默认**反直觉 + 静默执行错 Rule**（文本顺序因整理/复制/AI 生成而变）。
- TS interpreter `evaluate(core, functionName, context)` **要求显式函数名**，无
  first-rule 语义 → 双引擎不一致。

---

## 已锁决策（2026-06-08，用户确认）

| 决策 | 选择 | 理由 |
|------|------|------|
| **D1 入口标记语法** | **`@entry` 注解** | 复用现有 `Annotation` AST 节点，parse 改动最小，不新增关键字，tier1-parity 语法对齐风险低，不侵入自然语言句式 |
| **D2 模块来源** | **DB 已发布策略互引用** | ModuleResolver 从 `policyVersions` catalog 按 `policyModule` 加载已发布模块的 Core IR。最符合 multi-team 语义：发布=对外提供能力 |
| **D3 版本策略** | **钉版本 `@v2`** | `Use risk.Scoring as v2` 钉具体已发布版本，需版本锁。team A 发新版不影响 team B 除非显式升级。隔离性优先 |

### 我方决定（技术约束/安全铁律，非选项）
- **编译时合并 module graph**：链接发生在 lowering 前，合并成单个 Core program
  （GraalVM 执行单元），因现有执行是单 Core JSON。
- **tenant 隔离是安全铁律**：ModuleResolver 必须按 `tenantId` 过滤可见模块。
  `Use` 不得成为跨租户数据泄露通道。跨 team 可见性 = 同 tenant 内 + 显式发布为
  “可被引用”（library 可见性标记）。
- **循环依赖检测必须有**：module graph 构建时检测 cycle，明确诊断。
- **feature flag 过渡哨兵**：`aster.entry.legacy-evaluate-sentinel`（默认 true
  过渡期 → false），区分 “未指定(null/blank)=auto” vs “显式 evaluate=找名为
  evaluate 的 Rule”。

---

## 三阶段方案

### 阶段 1 — 入口语义收紧（低风险、可独立交付、不动 grammar）

**仓库**：aster-api（+ REST 文档）。

1. `DynamicCnlExecutor` 入口选择改造：
   - `functionName` 缺失/blank → auto；显式 `"evaluate"` → 找名为 evaluate 的
     Rule（不存在则报错），由 feature flag `legacy-evaluate-sentinel` 控制过渡。
   - auto 规则：仅 1 个 Rule → 选它；多 Rule 无入口 → **返回可恢复诊断**
     “请指定入口 Rule（可用：A/B/C）”，不静默执行。
2. `ParseResult` 扩展：携带全部 Rule 名列表（供诊断与响应回传）。
3. evaluate-source 响应回传**实际执行的 Rule 名**（消除静默）。
4. REST 文档修正：`functionName` 可选，不再把默认写成 `"evaluate"`。
5. 测试矩阵：单 Rule 无 functionName / 多 Rule 无入口 / 显式 evaluate /
   evaluate 非首 Rule / 无 Rule / helper+main。

验收：入口选择测试矩阵全绿；多 Rule 无入口不再静默执行。

### 阶段 2 — `@entry` 注解（双引擎 + Monaco）

**仓库**：aster-lang-core（Java）、aster-lang-ts（TS）、aster-cloud（前端）、aster-api。

1. **双引擎 parse**：`@entry` 注解挂到 Rule。复用 `Decl.Func.annotations`
   （Java）/ AST annotation（TS）。grammar 改动最小（注解机制若已存在则零 grammar 改动）。
2. **validator**：`@entry` 唯一性——一个模块**最多一个** `@entry` Rule，多于一个
   → 编译错误。
3. **入口优先级**（aster-api，接阶段 1）：显式 functionName > `@entry` Rule >
   单 Rule > 诊断。**不**再特殊化 `main`/`evaluate` 名字（避免再造魔法值）。
4. **Monaco（aster-cloud）**：解析 Rule 列表，运行控件改 “选择 Rule 后执行”，
   API 传真实 functionName；`@entry` Rule 在 UI 高亮为主入口。
5. **tier1-parity**：`@entry` 注解 fixture 双引擎 parse 对齐（PR-blocking gate）。

验收：双引擎 `@entry` parse 字节对齐；多 `@entry` 编译报错；Monaco Rule 选择器可用。

### 阶段 3 — 跨模块运行时 ModuleResolver（multi-team 核心）

**仓库**：aster-lang-core（checkImport 实现 + module graph linker）、aster-api
（ModuleResolver + DB catalog 加载 + 请求模型扩展）、aster-cloud（模块目录补全）。

1. **请求模型扩展**：`SourcePolicyRequest` 加 `entry:{module,function}` 与
   隐式依赖（`Use ... as vN` 在源码里钉版本，无需请求体显式列依赖；ModuleResolver
   从源码 Import 声明解析版本）。
2. **ModuleResolver（aster-api）**：
   - 输入：当前模块的 `Decl.Import` 列表（含 path + 钉版本）+ tenantId。
   - 来源：`policyVersions` catalog（按 `policyModule` + 版本 + tenant 可见性）。
   - 输出：被引用模块的 Core IR（已发布版冻结的 Core JSON）。
   - 安全：tenant 过滤 + library 可见性标记；循环依赖检测；版本不存在/不可见 →
     结构化错误（模块名/版本/可用候选）。
3. **module graph linker（aster-lang-core）**：实现 `checkImport` 真符号导入——
   把被引用模块的 func/Data/Enum/effect signature 合并进当前 typecheck 符号表；
   lowering 阶段把 module graph 合并成单个 Core program（别名解析 + 符号重命名避免
   冲突）。
4. **DB schema**：已发布模块需标记 library 可见性 + 版本可被 `Use`。
5. **双引擎 conformance**：同一组 import/alias/版本/entry 用例在 Java core、TS
   typecheck、aster-api runtime 得到一致结果。

验收：team A 发布 `risk.Scoring@v2` → team B `Use risk.Scoring as v2` 在
evaluate-source 真实调用其函数；跨 tenant 不可见；循环依赖报错；版本不存在报错。

---

## 风险与边界

- **阶段 3 是新子系统**，工程量最大。先做阶段 1（独立交付），阶段 2/3 逐阶段
  独立 PR + parity gate，不一次性改 4 仓库再测。
- **安全**：ModuleResolver 是潜在跨租户泄露面，tenant 隔离 + 可见性标记必须先于
  功能。复用 `ApiKeySnapshot.tenantId`（见 security-audit-2026-06）。
- **向后兼容**：`"evaluate"` 哨兵用 feature flag 过渡，避免破坏现有客户端。
- **tier1-parity**：阶段 2 grammar/注解改动必须双引擎 parse 对齐；先跑 parse+ir
  再推（见 parity-gate 教训）。

## 后续

实施计划见 `.claude/structured-request.json` + shrimp WBS。逐阶段推进，每阶段
独立 PR、独立 CI、独立 parity 验证。
