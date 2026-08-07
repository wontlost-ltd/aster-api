# ADR 0029 — inline-if 连接词全语种本地化（then/else 各语种对应词）

**状态**: IMPLEMENTED（引擎四仓完成，未发版）。Claude 起草 → Codex 设计审 019f319e 通过 82（修表述）→ 用户批准实现 → 双引擎实现（TS+Java）+ 四语补词（en/zh/de/hi = then/那么/dann/तो）+ per-engine 四语等价测试全绿 → Codex 实现交叉审 019f31ce 通过 86（补 Java 参数名软关键字测试、测试文件须 tracked）。
**实现范围**：仅 `then`（新增 `SemanticTokenKind.THEN`）；`else` 复用现有 OTHERWISE（四语 Otherwise/否则/sonst/अन्यथा 早齐，零引擎改动）。改动四仓：aster-lang-ts（THEN kind+四语词+test）、aster-lang-core（THEN kind+en-US builtin+OPTIONAL_KINDS 迁移项+test）、aster-lang-locales（en/zh/de 补词）、aster-lang-hi（hi 补词）。**未发版**——sherlock demo 换 then→那么 依赖 ts 发新版 + aster-dev 升级依赖（已用本地构建实证全中文源码编译运行正确）。
**发版顺序（Codex 审定，ADR 0023 release train）**：platform（bump catalog）→ core → locales(en/zh/de)+hi（同层）→ truffle 等下游 → ts(npm) → 消费方（aster-api pin + aster-dev 升级后换 demo）。**OPTIONAL_KINDS 的 THEN 保留到发版完成**（发布非原子，新 core 可能先遇旧 SPI 包，过早移除=加载 error），完整 release train 发完 + 消费者 pin 新 catalog 后，下次清理型 release 移除。
**已知遗留（非本 ADR 引入，Codex 标记）**：core `build.gradle.kts:242` 的 exportLexicons langPacks 只含 en/zh/de 无 hi + TS verify-lexicon-sync 只校验 en/zh/de → TS 的 `@generated hi-IN.ts` 实际不在生成/校验闭环（本次 तो 手改 TS+hi 包 JSON 双写、值一致，但未来会漂）。待办：把 asterLibs.hi 纳入 exportLexicons + verify 闭环。
**日期**: 2026-07-05
**起因**: 《斑点带子案》demo 把 CNL 关键词都中文化了（`若`/`且`/`凶手即`），但 inline-if 的连接词 `then`/`else` 仍是英文，读起来夹生。用户要求 **`then`/`else` 都要有其它语种的对应词**（如 then→那么、else→否则），四语（en/zh/de/hi）齐全。
**关联**: ADR 0022（关键词别名，安全约束来源）、ADR 0018/0017（统一语言包 / 第四语种）、ADR 0019 G2a（inline-if then 连接词的引入）；[[inline-if-then-else-localization]]（本 ADR 的前置调查）、[[sherlock-interactive-game]]。

---

## 1. 背景与问题

Aster 的 inline-if 表达式形如：
```
若 cond then 凶手即 A else 凶手即 B
（en: If cond then return A else return B）
```
其中 `若`(IF)、`凶手即`(RETURN) 是**可别名的 SemanticTokenKind**——各语种词法包给它们提供本地词，用户源码可全语种书写。但 **`then` 和 `else` 不是**：

- **`else`**：有一个半可用通道——inline-if 也接受 `otherwise`（`else` 的同义词），而 `otherwise` 是可别名的 `SemanticTokenKind.OTHERWISE`，zh-CN 词法包已把它映射成「否则」。所以 `否则` 目前**已可用**（ADR 0029 之前的 [[inline-if-then-else-localization]] 已在 sherlock demo 落地）。但 de/hi 是否有对应词、以及这条「else 借道 otherwise」是否应正式化，需本 ADR 统一。
- **`then`**：**完全没有**对应的 SemanticTokenKind。两个引擎都硬编码英文匹配，无任何本地化通道。

用户诉求：把 inline-if 的两个连接词 `then`/`else` **提升为一等的、全语种本地化的语义关键词**，en/zh/de/hi 都有对应词。

## 2. 现状实证（双引擎源码 + 真编译）

### 2.1 `then`/`else` 当前机制

**TS 引擎**（aster-lang-ts）：inline-if 在 `src/parser/expr-stmt-parser.ts`（parseInlineIf ~229、parseIfExpr ~725、peekInlineThen ~203）。判定用 `ctx.isKeyword(KW.THEN)` / `ctx.isKeyword(KW.ELSE)`，而 `context.ts:231` 的 `isKeyword(kw) = (tokLowerAt(...) === kw)`——**直接把 token 小写文本与常量 `'then'`/`'else'` 比较，不查 lexicon**。`else` 判定是 `isKeyword(KW.ELSE) || isKeyword(KW.OTHERWISE)`。`token-kind.ts` 枚举有 IF/OTHERWISE/RETURN/AND，但**无 THEN、无 ELSE**（仅 OTHERWISE）。

**Java 引擎**（aster-lang-core）：`AsterLexer.g4:155-158`：
```antlr
IF: [Ii][Ff];
THEN: [Tt][Hh][Ee][Nn];
ELSE: [Ee][Ll][Ss][Ee] | [Oo][Tt][Hh][Ee][Rr][Ww][Ii][Ss][Ee];   // else 与 otherwise 同一 token
```
是硬编码英文字母 token。`SemanticTokenKind.java` 有 IF/OTHERWISE 但**无 THEN/无 ELSE**。canonicalizer 关键词翻译表只处理以 SemanticTokenKind 为 key 的 keywords/aliases，故方言词无法翻成 then/else。

**双引擎 parity**：一致（都硬编码英文 then、都借 otherwise 支持 else 同义、都无 THEN kind）。当前未破坏。

### 2.2 ★关键安全事实：then/else 是**软关键字**（位置感知），不是硬保留字

实测（真编译）：`then`/`else`/`otherwise` 在**字段名/标识符位置目前都是合法标识符**（`Define Account has then.` 编译成功）。两个引擎都把它们列入软关键字：
- Java：`AsterCustomLexer.java:301` 明确把 `AsterParser.ELSE`/`AsterParser.THEN` 列入 structKeywordName（"结构软关键词在表达式位置可当变量名/方法名"）。
- TS：lexer 把 then/else 当普通 IDENT 发出，parser 仅在 inline-if 上下文靠文本命中，标识符位置不拦。

**这是本 ADR 可行性的基石**：把 `那么`/`否则`（及 de/hi 对应词）加成本地化连接词，只要保持**软关键字 + 位置感知**语义（仅在 inline-if 的 then/else 位置识别，标识符位置放行），就**不会破坏用户空间**——与 ADR 0022 的「单词别名占标识符命名空间→破坏用户空间」教训不同（那是无位置感知的准保留字）。

## 3. 核心设计原则（决定一切的不变式）

1. **软关键字铁律**：本地化连接词必须保持位置感知——只在 inline-if 的 then/else 语法位置识别，标识符位置（字段/参数/变量名）一律放行。禁止把它们变成无条件保留字（否则重蹈 ADR 0022 覆辙）。
2. **全语种齐全**：THEN 和 ELSE 两个 SemanticTokenKind，en/zh/de/hi 四语种词法包都必须提供对应词。若某语种缺词 → parity 校验必须报错（不允许某语种 inline-if 只能写英文）。
3. **双引擎逐字节 parity**：四语种的 inline-if 源码必须编译成**相同的 Core IR**。
4. **向后兼容**：英文 `then`/`else`/`otherwise` 继续有效；现存 en/zh/de/hi 源码不受影响。本地化连接词是**新增**能力，不移除旧拼写。
5. **canonical 唯一性**：不管源码用哪个语种的连接词，canonicalize 后归一到同一规范 token（THEN/ELSE），进 parser 的是规范形式。

## 4. 决策（提案，待审）

**`then` 和 `else` 的处理不对称**——因为一个已有全语种词、一个完全没有：

### 4.1 `else`：复用现有 `OTHERWISE`，四语种零新增（实证）

`else` 在 inline-if 里本已接受 `otherwise` 作同义词，而 `OTHERWISE` 是可别名的 SemanticTokenKind，**四语种词法包都已提供对应词**（实证）：

| Kind | en | zh | de | hi |
|---|---|---|---|---|
| OTHERWISE | Otherwise | 否则 | sonst | अन्यथा |

（TS: `src/config/lexicons/{en-US,zh-CN,de-DE,hi-IN}.ts:25`；Java: core `builtin/en-US.json:22` + locales 包 zh/de/hi 的 `lexicons/*.json`。）

所以 **`else` 的全语种本地化其实已经完成**——四语的用户现在就能用 `否则`/`sonst`/`अन्यथा` 替代 else（走 otherwise 通道）。**本 ADR 对 else 不需要任何引擎改动**，只需：
1. 文档化「inline-if 的 else 各语种写法」，让用户知道这条通道；
2. 加四语种 inline-if parity 测试，钉住四语 else（即 otherwise 本地词）都编译成相同 Core IR。

> 不新增独立 `ELSE` kind：else↔otherwise 语义等价，复用 OTHERWISE 零风险、零新增词。见 §8。

### 4.2 `then`：新增一等可本地化 `SemanticTokenKind.THEN`，四语补词

`then` 是**唯一真缺口**——无对应 SemanticTokenKind，四语全无本地词。提案：新增 `THEN` kind，纳入既有 lexicon 关键词机制（与 IF 同一路径），四语种各补一个词。

| Kind | en | zh | de | hi |
|---|---|---|---|---|
| THEN | then | 那么 | dann | तो |

（de/hi 词为提案初值，需母语审校 + 按各语种词法包既有风格确认；hi 用 Devanagari，注意 [[devanagari-identifier-fix-gitanjali]] 的组合记号切分事实。）

**★强制齐全机制（实证，两引擎不同——Codex 设计审修正）**：
- **TS**（`registry.ts:174-197`）：**en-US 缺 kind = error**（backbone 契约），其它语种缺 = warning + FallbackLexicon 兜底回 en-US。含义：新增 THEN 后 en-US 必须补 `then`；zh/de/hi 若不主动补词会**静默 fallback 成英文 `then`**（不报错但等于没本地化）。
- **Java**（`LexiconRegistry.java:39`）：普通缺 keyword = **error**（不是 TS 那种 warning+fallback），只有 `OPTIONAL_KINDS` 迁移白名单例外。含义：新增 THEN 后，**要么一次性同步 core + locales 三语补全**，要么先把 THEN 临时列入 Java `OPTIONAL_KINDS` 并定移除点，否则 Java 侧直接编译不过。
- **验收要点**：两引擎机制不同不能混用结论。要真正满足用户「全语种都有对应词」，**必须主动给 zh/de/hi 补 那么/dann/तो**（TS 侧防静默 fallback、Java 侧防 error），且发版时 TS 与 Java 的四语词要一致。

### 4.3 数据结构与改动面（按仓，实现清单）

**aster-lang-ts**：
- `src/config/token-kind.ts`：`SemanticTokenKind` 新增 `THEN`（当前 78 个成员）。
- `src/config/semantic.ts`：`KW.THEN` 已存在（`'then'`），保留作 en 规范拼写来源。
- 四语种 `src/config/lexicons/{en-US,zh-CN,de-DE,hi-IN}.ts`：各补 `[SemanticTokenKind.THEN]: '...'`。
- `src/parser/expr-stmt-parser.ts`：`peekInlineThen`(~203)、`parseInlineIf`(~229)、`parseIfExpr`(~725) 里对 then 的判定用 `isKeyword('then')` 硬文本比较。**关键（Codex 设计审修正）**：TS 的 `canonicalize()` 本身**不翻关键词**——关键词翻译发生在 `compile()`/`parseWithLexicon()`（`browser.ts:220` / `parser.ts:88`）的 **token translation 阶段**（`keyword-translator` 遍历 SemanticTokenKind 把非英语词翻成英语 token）。所以正确做法：**把 THEN 纳入 TS 的 token translator**——新增 THEN kind + 四语补词后，token translation 会把 `那么`/`dann`/`तो` 翻成英语 `then` token，再喂 parser，`peekInlineThen`/`parseInlineIf`/`parseIfExpr` 的 `isKeyword('then')` **不用改**（它比较的已是翻译后的英语 token）。软关键字语义（仅 inline-if 位置识别，标识符位置放行）天然保持。**测试必须覆盖 `compile()` 与 `parseWithLexicon()` 两条入口**（确保两条路径都经 token translation）。

**aster-lang-core**：
- `SemanticTokenKind.java`：新增 `THEN`。
- `AsterLexer.g4:157` 的 `THEN` 硬 token 保留（英文兜底），但需让 Canonicalizer 把方言 then 词归一成规范 `then` 再喂 ANTLR——即把 THEN 纳入 `buildKeywordTranslationMap` 的翻译表（该表遍历 SemanticTokenKind 为 key 的 keywords/aliases，故新增 THEN kind 后自动纳入）。`AsterParser.g4` 的 `inlineThen`/`ifExpr` 规则消费 THEN token，不变。
- `AsterCustomLexer.java:301` 的 structKeywordName 已含 THEN（软关键字），保持。
- core `builtin/en-US.json` 补 `"THEN": "then"`；locales 包 zh/de/hi 的 `lexicons/*.json` 补对应词。

**aster-lang-locales**：zh/de/hi 三个语言包补 THEN 词（JVM 侧词法来源）。

**aster-cloud / aster-dev**：display-only lexicon 副本若含关键词表需同步；sherlock demo 可把 canonical 的 then 也换成 `那么`（本地化完成后）。

### 4.4 Parity 与校验（必须随实现落地）

1. **词法包完整性**：新增 THEN 后跑 registry 校验，确认 en-US 有 `then`、四语种都补词（TS 不留 fallback 到英文、Java 不报 error）。
2. **双引擎 Core IR parity**：四语种（en/zh/de/hi）各写一段等价 inline-if（`if c then A else B` 的本地化形式），编译成**逐字节相同的 Core IR**——TS 与 Java 各自编译，剪自然差异字段后 JSONAssert 比对。参照 ADR 0028 的 cross-engine parity 测试模式（`ExplicitBlockCrossCompilerTest`）与 [[fourth-language-feasibility]] 的 parse-parity 配对测试。**入口覆盖（Codex 修正）**：TS 必须同时测 `compile()` 与 `parseWithLexicon()` 两条入口（确保都经 token translation）、Java 测 canonicalizer→ANTLR 全链路。
3. **软关键字回归（翻译后仍软——Codex 修正）**：测试不能只测英文 `then`。必须钉住**四语本地词** `那么`/`否则`、`dann`/`sonst`、`तो`/`अन्यथा` 在**标识符位置仍合法**（字段名 `Define X has 那么.`、参数名、变量名、成员名），因为 TS token translator 与 Java canonicalizer 都会把本地词翻成英语 token，风险恰恰发生在**翻译后**。防止本地化把它们变成硬保留字 → 破坏用户空间（ADR 0022 教训）。
4. **边界/负用例（Codex 修正）**：
   - 词边界：`那么值`（那么+值）不应被切成 `then + 值`；`तोX`/`अन्यथाX` 不应误匹配（天城文注意 [[devanagari-identifier-fix-gitanjali]] 的组合记号切分）。
   - 字符串字面量里的 `那么`/`तो`/`dann` 不应被翻译（同 ADR 0028 StringSegmenter 保护）。
   - 唯一性：de `dann`/`sonst` 与现有关键词无撞名，仍由词法唯一性校验钉住（防未来撞名）。
5. **向后兼容回归**：英文 then/else/otherwise 继续有效。

## 5. 安全分析（对照 ADR 0022 红队 H3）

ADR 0022 的核心教训：**无位置感知的单词别名占用标识符命名空间 → 破坏用户空间**（用户用同名字段/参数会突然编译失败）。本 ADR 是否重蹈？

**不会，因为两点本质不同**：
1. **固定语种翻译，非用户自定义别名**：THEN/ELSE 的本地词由官方词法包提供（那么/否则），不是租户可任意注册的别名。攻击面是官方审校过的固定词表，不是用户输入。ADR 0022 H3 禁止的是「用户/租户给结构关键词配别名」（怕误导审批），而语种官方词法本就是可信的一等公民（IF/RETURN 早就全语种本地化了，THEN/ELSE 只是补齐同类）。
2. **软关键字 + 位置感知（实证）**：§2.2 证明 then/else 当前就是软关键字（标识符位置放行）。实现铁律（§3.1）要求本地词沿用此语义——只在 inline-if 的 then/else 语法位置识别。故 `Define Account has 那么.`（那么作字段名）仍须合法。这与 ADR 0022 「单词别名变准保留字」正相反。§4.4.3 的回归测试强制守护这一点。

**结论**：本地化 inline-if 连接词属于「补齐官方语种词法」（同 IF/OTHERWISE 已做的），不落入 ADR 0022 H3 的「用户别名结构词」禁区，且软关键字语义保证不破坏用户空间。

## 6. 影响面小结

| 改动类型 | else | then |
|---|---|---|
| 新增 SemanticTokenKind | 否（复用 OTHERWISE） | **是（THEN）** |
| 四语种补词 | 否（已齐） | **是（en 强制，zh/de/hi 主动补）** |
| 引擎 parser/grammar 改动 | 否 | 是（TS 判定改归一、Java 纳入 canonicalizer 翻译表） |
| 跨仓发版 | 否 | 是（core + locales + ts，级联，见 [[release-cascade-adr0024]]） |

**工作量**：else ≈ 文档 + 测试（小）；then ≈ 双引擎 + 四语补词 + parity + 跨仓发版（中，同 ADR 0022/0026 量级）。

## 7. 建议推进路径

1. **先落 else 的文档 + 四语 parity 测试**（零引擎改动，立即可做，让「否则/sonst/अन्यथा」正式成为已支持写法）。
2. **then 走完整语言特性流程**：本 ADR → Codex 设计审查（尤其 §4.3 的「canonicalizer 归一 vs isKeyword 查 lexicon」二选一、de/hi 词审校）→ 用户批准 → 双引擎实现 + parity + 跨仓发版。

## 8. 备选方案（已否决 / 待议）

- **ELSE 新增独立 kind**（不复用 OTHERWISE）：否决——else↔otherwise 语义等价，四语 OTHERWISE 已齐，独立 ELSE 纯属重复，还要四语再补一遍词，零收益。
- **THEN 也走 canonicalizer 硬翻译不进 lexicon 体系**（像 ADR 0028 的 BLOCK_END sentinel）：否决——BLOCK_END 是可选关闭的特性且无「全语种对应词」诉求；THEN 要的是与 IF 同级的一等本地化，进 SemanticTokenKind 才能被 registry 校验强制四语齐全、被 parity 覆盖。
- **不做，保持 then/else 英文**：否决——用户明确要求全语种对应词。

## 9. 未决问题（推进实现前请用户 / Codex 拍板）

1. **§4.3 的实现路径二选一**：THEN 判定改「canonicalizer 归一」还是「isKeyword 查 lexicon」？（倾向前者，与 IF/RETURN 一致。）
2. **de/hi 的 THEN 词**：`dann` / `तो` 是否准确？需母语 + 词法风格审校（de 是否与既有 wenn/sonst 风格一致；hi Devanagari 拼写）。
3. **ELSE 是否也让 else 字面 token 支持四语**：目前 else 借 otherwise。是否需要让四语各自的「else 直译词」（如 de `andernfalls`）也接受？还是统一走 otherwise 词即可（`sonst` 已是 de 的 else）？（倾向后者，otherwise 词已够。）
4. **sherlock demo 是否等 then 落地后一并把 canonical 的 then 换成 那么**（让推理链全中文），还是本 ADR 只立项、demo 保持现状。
