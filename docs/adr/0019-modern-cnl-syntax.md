# ADR 0019 — 现代 CNL 语法（小写关键词 + 内联 if/then）

Status: **全特性已实现（G0+G1+G2a+G2b 全合并）。G0+G1+G2a 已发版上生产（2026-06-19，
生态 1.0.3）；G2b 2026-06-21 合并 main（core/truffle/ts/test 四仓 PR #32/#18/#29/#36），
双引擎 parse-parity 213/213 + eval 一致锁定，待 JVM 生态级联发版 + aster-api 部署**
Date: 2026-06-18（G2b 更新 2026-06-21）
Review: Codex 审查 72/100→退回修订（见 `.claude/review-report.md`）；本版已修正
2 处事实错误（TS 不支持完全省略 produce；小写 module 表述过度）+ 采纳 G2 分阶段、
negative fixture、DAG 发版建议。

## ✅ 交付状态（2026-06-18）

| 特性 | 范围 | 状态 | Codex 审查 |
|------|------|------|-----------|
| **G0** 省略 produce | TS-only | ✅ 合并 main + 发版 `ts@1.0.2` | — |
| **G1** 小写关键词 | core(主)+test | ✅ 合并 main | 68→82→94 通过（抓出真回归：硬保留 token 吞标识符→`structKeywordName` 软关键字补全 ~11 位置） |
| **G2a** 语句级内联 if | core+ts+test 双引擎 | ✅ 合并 main | 92/100 一轮通过 |
| **G2b** 表达式级 if | core+truffle+ts+test 三引擎 | ✅ 合并 main（2026-06-21，PR #32/#18/#29/#36） | 7.5/100→修订（抓出 TS typecheck-pii + jvm/emitter 静默漏 IfExpr，已全修） |

**epic 实际目标已达成**：文档/playground 的现代 CNL 写法（小写关键词 + 内联
`if X then return Y else return Z` 含 else-if 链、then 换行缩进）现在**双引擎端到端
可解析**——4 个真实文档例（classify/score/discount/quote）在 core(ANTLR) 和
ts 两引擎都解析通过。**全部未发版**（等批量 JVM 级联：platform→core→truffle→api）。
下游文档/playground 暂不改（无实际用户）。

## 背景

aster-lang.dev 文档站（ADR 0018 Phase 3）重写时，所有 `aster` 代码示例用了一套
"理想中的"现代简化语法，例如：

```aster
Module Greeting.

Rule greet given name:
  return "Hello, " followed by name.

Rule classify given amount:
  if amount is greater than 10000 then return "large"
  else return "small".
```

但这套语法**部署后端（/api/v1/policies/evaluate-source，Truffle/ANTLR 路径）拒绝
解析**——文档示例、playground 样例全部 CNL 解析失败。

用户决策（2026-06-18）：**不降级文档**（无实际用户，文档体现 Aster 应有的样子），
而是**推动语言核心支持现代语法**，让引擎追上文档。本 ADR 把这件事完整规划为可逐个
推进的特性 epic。

## 事实基础（已实测 + 查 grammar 核实，非猜测）

逐特性隔离测试（live 后端）+ 核对 `aster-lang-core` 的 `AsterLexer.g4`/`AsterParser.g4`：

| 特性 | core (ANTLR) | TS 引擎 | 结论 |
|------|------|------|------|
| 无类型参数 `given n`（省略 `as T`） | ✅ `param: nameIdent (AS annotatedType)?` | ✅ | 非缺口 |
| `at least`/`greater than`/`is at least`（is 可选） | ✅ lexer `('is' [ \t]+)?` 吸收 | ✅ 吸收 is 前缀 | 非缺口（实测 True） |
| **完全省略 `produce` 子句**（`Rule greet given name:`） | ✅ `(PRODUCE annotatedType?)?` 整段可选 | ❌ **不支持** `decl-parser.ts` 无条件 `expectKeyword(KW.PRODUCE)` | **G0：TS 缺口** |
| **小写 `return`/`rule`/`if`/`else`** | ❌ lexer 只 `RETURN:'Return'`/`RULE:'Rule'`/`IF:'If'`（单 token） | ✅ `tokLowerAt` 大小写不敏感 + `KW.*` 小写 | **G1：core 缺口** |
| **内联 `if X then Y else Z`** | ❌ `ifStmt` 只缩进块、无 `THEN` token | ❌ 也无 `if`-expression | **G2：双引擎都缺** |
| 小写 `module` header | ⚠️ lexer 有 `MODULE:'module'` token，**但 `moduleHeader` 只接受 `MODULE_KW`（'Module'）** | ✅ 大小写匹配 | 端到端是否接受取决于 canonicalizer，**待端到端核实**（归入 G1） |

**结论（经 Codex 修订）：现代语法与现状的差距是 3 个特性 —— G0（TS 放宽 produce）+
G1（core 小写单词关键词）+ G2（双引擎内联 if/then）。** 无类型 given / is-comparator
本已支持。

> **关键修正**：原 ADR 把"省略 produce"算成已支持是错的——**core 支持但 TS 不支持**，
> 双引擎 parity 不成立。`Rule greet given name:` 这种完全省略 `produce` 的文档写法在
> TS 引擎会失败。**这是与 G1 并列的第三个差距 G0。**
> 同时 **"小写 module 已支持"表述过度**：lexer 有 `MODULE` token ≠ parser 接受，
> `moduleHeader` 只消费 `MODULE_KW`；端到端是否接受需另测（归 G1）。

## 受影响仓库（grammar 改动连锁，铁律：双引擎对齐）

每个特性都跨多仓，PR-blocking 的 tier1-parity 是硬约束：

- **aster-lang-core**：`AsterLexer.g4`（关键词 token）+ `AsterParser.g4`（ifStmt/then）+
  `AstBuilder`（lowering）+ 4 个 lexicon json（en/zh/de/hi 的关键词变体，若涉及）
- **aster-lang-ts**：手写/PEG parser 必须同步支持（**双引擎 parse-parity 铁律**）
- **aster-lang-test**：`tier1-parity` manifest 加 fixture（parse PR-blocking；ir/eval report-only）。
  **不只加 positive fixture，必须加 negative/edge fixture**（Codex 建议）：`returnValue`/
  字段名 `if`/参数名 `return`/module header 小写/省略 produce —— 锁住"现代写法接受"且
  "同名标识符不被吞"两个方向。
- **aster-lang-truffle**：若 lowering 形态变化需对齐（G2 表达式级 if 影响最大）。
- **发版顺序：按依赖 DAG，不是固定链**（Codex 修正）。实际顺序：先 test/corpus PR 准备
  预期 fixture → core + ts 各自实现并发布本地/包版本 → truffle 若依赖 core model 跟进 →
  最后 api bump runtime 依赖并 k3s 重部署（见 [[prod-sim-pre-v1-release]]）。

**memory 血泪教训**（[[logical-and-or-operators]] / [[modulo-intdiv-operators]] /
[[cross-module-entrypoint]]）：
- 改 grammar 极易引回归（block 规则、gen/.tokens 是生成产物别手改、软关键词）。
- 加 SemanticTokenKind 会触发 zh/de/hi lang-pack 完整性校验（须发布所有包到 mavenLocal）。
- 必须**同时**跑 `--mode=parse` 和 `--mode=ir` 两个 parity，别只跑一个就推。
- Canonicalizer `removeArticles` 会吞 a/an/the 标识符——命名避开。

## 特性拆解（逐个独立 PR）

### G0 — TS parser 放宽 `produce` 子句（TS-only，最先做）

> **✅ 已完成（2026-06-18）**：aster-lang-ts#27（实现）+ aster-lang-test#33（parity
> fixture）均已合并 main；发布 `@aster-cloud/aster-lang-ts@1.0.2`（CHANGELOG G0 条目）。
> TS 1127 单测全过，双引擎 parse-parity 209/209 identical（含 `g0-omit-produce`）。
> 实现细节：`decl-parser.ts` produce 改可选（缺省→`retType=Unknown` 推断），带
> produce 的两种写法不变；负向测试错误位置从 "Expected 'produce'" 后移到 "'.'/':'  检查"。
> 踩坑：fixture module 名用 `dual.engine.implicit.returntype` 避开 `produce` 关键词撞名；
> test 仓 PR 分支须与 ts PR **同名**（`checkout-sibling` 分支匹配破 chicken-egg，否则
> parity gate fallback 到旧 ts main）。

**目标**：TS 引擎接受**完全省略 `produce`** 的规则声明（`Rule greet given name:`），
与 core 已有的 `(PRODUCE annotatedType?)?` 对齐。

**改动**：`aster-lang-ts/src/parser/decl-parser.ts` ~352 行的无条件
`expectKeyword(KW.PRODUCE, ...)` 改为**可选**：若下一 token 是 `:`/`.` 等块/结束符，
跳过 produce 子句（返回类型走推断，TS 已有 `retTypeInferred` 路径）。

**范围**：TS-only（core 本已支持）。这是 G1 之外的**第二个 core/ts 不对称**，且是
文档现代写法的基础（几乎每个示例都省略 produce）。**最先做**，否则 G1 修好后双引擎
仍因 produce 缺口 parity 不过。

**验证**：tier1-parity 加"省略 produce"fixture（先暴露 TS 缺口，修后两引擎 identical）。

### G1 — core 小写单词关键词（`return`/`rule`/`if`/`else` 等）

> **✅ 已完成（2026-06-18）**：方案 A（lexer 逐字母字符集）。aster-lang-core#28
> （grammar+AstBuilder）已合并 main；aster-lang-test#34（parity fixture）CI 绿待
> 合并。core 全量 1203 测试 0 fail，双引擎 parse-parity 211/211 identical。
>
> **★关键教训（Codex 三轮交叉审查 68→82→94 通过）**：方案 A 把 10 个结构关键词
> 改成大小写不敏感硬 token（`RULE: [Rr][Uu][Ll][Ee]` 等）后，**小写形式不再能作
> 普通标识符**——改动前小写 `let`/`if`/`return` 是 `IDENT`，可作变量名/参数名/字段名/
> 成员名/模块路径段/match 绑定名/类型参数/导入别名/类型别名/注解名键值；改动后被硬
> token 拦住，而 TS（上下文关键字模型）+ main 都接受 → 真回归（实证：JavaParseHelper
> 对比 main vs 分支）。**修复 = `structKeywordName` 软关键字规则**（沿用现有
> `AND/OR/NOT/WITH` 在 `qualifiedSegment` 的软关键字先例），加到**所有**标识符位置：
> `nameIdent`/`primaryExpr`(StructKeywordVarExpr)/`postfixSuffix`(成员)/`constructField`/
> `qualifiedSegment`/`pattern`(PatternStructKeywordName)/`typeParam`/`importAlias`/
> `typeDecl`/`annotation`(名+键+值)。AstBuilder 每处加 `structKeywordName()` 取文本分支
> （否则 `IDENT()`/`TYPE_IDENT()` 均 null → NPE）。**教训：lexer 硬保留 ≠ TS 上下文
> 关键字；放宽关键词大小写必同步在所有标识符位置当软关键字放行，否则吞标识符**。

**目标**：单词结构关键词大小写不敏感（`Return`/`return`/`RETURN` 都接受），与
`MODULE` 已有的双 token 模式一致。

**方案**（两选一，实现时定）：
- **A. lexer fragment 大小写不敏感**：把 `RETURN: 'Return';` 改为
  `RETURN: [Rr][Ee][Tt][Uu][Rr][Nn];`（ANTLR 无全局 caseInsensitive option 时的惯用法），
  对 `RULE`/`IF`/`ELSE`/`GIVEN`/`DEFINE` 等同样处理。**风险**：与标识符/lexicon 关键词
  的边界，软关键词冲突。
- **B. Canonicalizer 层归一**：在喂 ANTLR 前把句首单词关键词规范成 PascalCase。
  复用现有"多词关键词大小写归一"（`CASE_INSENSITIVE`）机制扩到单词关键词。**风险**：
  误伤同名标识符（如变量名 `if`/`return`）——需词性/位置感知。

**倾向 A**（lexer 层最干净，标识符边界由 ANTLR 词法天然区分），但需验证不破坏现有 fixture。

**★关键事实（已核实）**：**TS 引擎已支持小写关键词**（`tokLowerAt` context.ts:103
`toLowerCase()` + `KW.RETURN='return'`/`RULE='rule'`/`IF='if'`）。所以 G1 的实现主体
**在 core**（追上 TS）。但 Codex 修正：**不等于"core-only 到不碰 TS"**——TS 侧不需改
小写关键词，但 **parity fixture + G0 的 produce 修复仍在同一 epic**，G1 不能脱离它们单独验证。

**待定范围（Codex 提出）**：哪些词做大小写不敏感 hard token、哪些保持软关键词，需明确
界定：`RULE/RETURN/IF/ELSE/LET/DEFINE/USE/MATCH/WHEN/START/WAIT/WORKFLOW/...`。注意
`Else: 'Else'|'Otherwise'`——要决定是否接受小写 `else`/`otherwise`。

**破坏面（Codex 补充）**：`return`/`rule`/`if` 小写在 core 当前可作 IDENT 出现，改成
保留词后，裸 `return`/`if` 作参数名/字段名会受影响。ANTLR 最长匹配能保护 `returnValue`
（IDENT 更长），但裸词不行——**必须加 negative fixture 锁住**。

**验证**：tier1-parity 加小写关键词 positive fixture（暴露 TS-only 不对称→core 修后
identical）+ negative fixture（`returnValue`/参数名 `return` 仍当标识符）。

**`module` 端到端核实（Codex 要求）**：raw ANTLR parser 是否接受小写 `module`、
canonicalized 后端是否接受、TS 是否接受——三者分别测，别只凭 lexer 有 token 下结论。

### G2 — 内联 `if … then … else …`（拆 G2a / G2b，Codex 建议）

文档需要两种内联 if：
- 语句级：`if amount greater than 10000 then return "large" else return "small"`
- 表达式级：`return if a then b else c`

ADR 原方案一次性做表达式级（影响 AST/typecheck/eval/lowering），Codex 指出**过重**，
应拆两步：

**G2a — 语句级 inline if（先做，较简单）**

> **✅ 已完成（2026-06-18，Codex 审查 92/100 通过）**：core#29（grammar+AstBuilder）+
> ts#28（parser）+ test#35（parity fixture）CI 待合并。**双引擎** 都实现内联 if，
> 降级为与块式相同的 `Stmt.If`（单语句 return 包 Block，else-if 链右递归成嵌套 If），
> **不引入新 Core 节点**。core 1211 测试 / ts 1133 测试 0 fail；parse 212/212、ir
> identical、**eval 两引擎一致**（engines disagree=0，5 case 三分支+边界全对）。
> **实现要点**：①lexer 加 `THEN` token（大小写不敏感）+ 进 structKeywordName 软关键字
> （`.then(handle)` 方法名，否则 testChainedMethodCall 回归）；②`inlineThen: NEWLINE?
> INDENT? THEN` + `inlineElseSep: NEWLINE? DEDENT? ELSE` 手动吸收 then 换行缩进的
> INDENT/DEDENT（文档 overview/deployment/reference 都用 then 换行）；③中间分支
> `inlineReturn`（无 DOT）vs 末尾 `returnStmt`（带 DOT）；④**TS 顺带补 `else` 关键词**
> （此前 TS 块式 if 只认 `otherwise`，与 core `ELSE: Else|Otherwise` 不对称，文档用
> `else`）。**eval-parity 本地验证需先把 runtime/validation/truffle 发 1.0.2 到
> mavenLocal**（core 之外的 JVM 制品，否则 CoreIrEvalCli 缺依赖）。

- 语法：`IF expr THEN returnStmt (ELSE returnStmt)?`（或更一般的 simpleStmt），**不进
  通用 expr 优先级链**，避免歧义。
- lexer 加 `THEN` token（PascalCase + 小写，配合 G1）。
- lower 成与现有块式 `ifStmt` 相同的 Core IR —— **复用现有 statement-If 路径，不新增
  Core 表达式节点**。
- 解决文档里 `if … then return … else return …` 的写法。

**G2b — 表达式级 if（已实现 2026-06-21）**

> **✅ 已实现（2026-06-21，用户后来决定要做）**：跨 core/truffle/ts/test 四仓
> （PR #32/#18/#29/#36 全合并 main）。**新增 Core IR `IfExpr` 节点**（用户选最干净
> 语义路线，非 IIFE 降糖）。双引擎 parse-parity 213/213 identical，eval 逐字节一致
> （真引擎 `if score>90 then "A" else if score>70 then "B" else "C"` → 95→A,80→B,50→C）。
> core 1223 / ts 1133 / truffle 全套测试绿。Codex 审查 7.5/100 退回抓出 2 个 PR-blocking
> 遗漏（TS typecheck-pii.ts inferExprPii + jvm/emitter.ts 静默漏 IfExpr）+ 3 非阻断，已全修。

**实现要点（与下方原设计的差异）**：
- 语法：`expr : ifExpr | orExpr`；`ifExpr : IF cond=orExpr THEN thenE=expr ELSE elseE=expr`。
  **锚在 expr 顶层不进 primaryExpr/运算符优先级链**（与原设计"进 primaryExpr"不同）——
  if-expr 自带边界（then/else 划定子表达式），不参与结合，从根本避开 dangling-else +
  优先级歧义。**else 必需**（表达式两方向都要有值）。cond=orExpr（不含 if-expr 避 `if if`）；
  then/else=expr 支持嵌套 + else-if 链。ANTLR 重生成无冲突警告。与 G2a 语句级 inline-if
  在 statement 位无歧义（`return if` RETURN 起 vs `if` IF 起）。
- **新 Core IR IfExpr 节点**（kind="IfExpr"，字段 cond/thenE/elseE；AST 用 thenExpr/elseExpr
  lowering 到 IR thenE/elseE）。**core/truffle 各有独立 CoreModel**（truffle 的 Await 叫
  AwaitE！）——两份都加。truffle 新建 IfExprNode（@NodeChild cond 特化 + executeGeneric 选分支）。
- **★穷尽覆盖所有 Expr 遍历点是关键**（漏一个=分析失真/eval 错）：sealed switch（tsc/javac
  会报）好抓，但**手写 if-chain/instanceof + default 的遍历点编译器不报**，必须 grep
  `case 'Await'`/`instanceof.*Await` 逐处核对——Codex 正是抓了 TS 两个这类遗漏。

**双引擎**：G2a/G2b 都需 TS parser 同步（TS 当前也无 if-expression）。

**验证**：G2a parity 覆盖语句 inline if（parse+ir+eval，含 else 缺省/嵌套）；G2b 另补
表达式级 fixture。

## 推进顺序（Codex 修订后）

1. **G0 先行**（TS-only，最简单，且是基础）：TS 放宽 produce，加省略-produce parity
   fixture。否则后面 G1 修好双引擎仍因 produce 卡 parity。
2. **G1**（core 小写关键词，风险中低）：先界定 hard-keyword 范围，core lexer 改大小写
   不敏感，加 positive + **negative** fixture，TS 已支持只需对齐 fixture，发版。
   **有先例**：[[adoption-work]] 加 `is equal to` 自然英语等价的双引擎 parity-locked 流程。
3. **G2a**（语句级 inline if，中等）：复用 statement-If 路径，不新增 Core 节点。
4. **G2b**（表达式级 if，最难）：单独设计 Core AST/typecheck/eval，充分验证歧义。

每个特性 = 完整小工程（按 DAG：test fixture → core + ts → truffle → api），不混做。

## 已核实（2026-06-18，含 Codex 修订）

- ✅ **TS 已支持小写关键词** → G1 主体在 core；但**不是纯 core-only**（G0 + parity fixture 同 epic）。
- ✅ **TS 不支持完全省略 produce** → 新增 **G0**（TS-only），是文档现代写法的基础。
- ✅ **小写 `module` 表述过度** → lexer 有 token ≠ parser 接受（`moduleHeader` 只收 `MODULE_KW`），归 G1 端到端核实。
- ✅ **TS/core 都无内联 if/then** → G2 双引擎都加，拆 G2a（语句级，复用现有 If）+ G2b（表达式级，最重）。

## 待实现时定

- [ ] G1 选 lexer-fragment 大小写不敏感（A）还是 Canonicalizer 归一（B）—— 倾向 A（Codex 同意）。
- [ ] G1 的 hard-keyword 精确范围（哪些词保留、哪些保持软关键词 / 标识符可用）。
- [ ] G2b 是否需重构现有 `ifStmt`/新增 Core 表达式节点避 dangling-else 歧义。
- [ ] 是否给 de lexicon 加首字母大小写变体（zh/hi 无大小写问题）。
- [ ] 是否纳入 Spec 1.0 冻结范围（[[commercial-readiness-2026-06]] 头号 blocker=语义冻结）。

## 关联

- [[unified-language-pack]] — ADR 0018，文档站重写引出本问题（playground/docs 示例方言）。
- [[logical-and-or-operators]] / [[modulo-intdiv-operators]] / [[cross-module-entrypoint]]
  — grammar 改动的双引擎 parity 流程 + 踩坑。
- [[adoption-work]] — `is equal to` 自然英语等价的先例（G1 可借鉴）。
- [[commercial-readiness-2026-06]] — 语言语义冻结是头号 blocker；本 ADR 是语法表层
  扩展，需评估是否纳入 Spec 1.0 冻结范围。
