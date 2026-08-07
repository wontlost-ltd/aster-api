# ADR 0027 — 无括号函数调用 `apply f to x`

**状态**: IMPLEMENTED（Claude 起草 → Codex 实现前设计审查 6/10 退回 → 据三修正重设计 → 双引擎实现 → Codex 实现审查 3 轮 78→86→**94/100 通过**，三处双引擎分歧全闭合，见 §3.0 + §8 + §9）
**日期**: 2026-06-30
**关联**: ADR 0019/0022/0026；起因为「源码即诗」demo（[[alias-poem-story-demo]] / [[multiline-continuation]]）的唯一破绽——递归调用 `gather(stars less 1)` 的括号藏不掉。

## 1. 背景与问题

Aster 函数调用必须带括号：`f(x)`（ANTLR `postfixSuffix : LPAREN argumentList? RPAREN`；TS parsePrimary 仅在 Name 后见 `(` 才构造 Call）。诗里 `gather(stars less 1)` 的括号是唯一暴露"这是程序"的接缝。目标：一种**无括号单参调用**语法，使该行能写成诗，且仍编译执行、双引擎逐位一致。

## 2. 选型：`apply <fn> to <arg>`（前缀关键词引入）

候选评估（Explore + 用户拍板）：
- juxtaposition `f x`：与词运算符 + 裸名变量灾难性歧义，**排除**。
- pipe `x into f`：歧义最低但函数名到行尾、语序偏"数据流"非叙事，**未选**。
- `f of x`：`of` 已被 given/`ok of`/`List of` 占用，歧义审计风险，**未选**。
- **`apply f to x`（选中）**：`apply` 是全新词无碰撞；`to`（TO_WORD 已存在）作连接词——被前置的 `apply` 消歧（`apply <fn> to` 与 `Set x to`/construction `set to` 不冲突，因后者不以 `apply` 起头、且 applyExpr 在 primaryExpr 内触发）。函数名在前，最贴原诗语序。`apply`/`to` 都是 SemanticTokenKind → **可别名**（诗里把 apply 改成诗词）。

## 3. 设计（单参，零新 AST/Core IR 节点）

### 3.0 ★据 Codex 审查（session 019f1614, 6/10 退回）的三处重设计
1. **apply 放独立 prefix 层（unaryExpr），不塞 primaryExpr**：原放 primaryExpr 会让 postfixExpr 对 apply 结果再接后缀、语义不洁。改为 `unaryExpr : NOT unaryExpr | applyExpr | postfixExpr`。arg 取顶层 `expr`（贪婪）→ `apply f to a plus b` = `Call(f, [a plus b])`（符合诗用例 `apply gather to stars less 1`，arg=`stars less 1` 整体）。
2. **`<fn>` 用专门 `callTarget`，不用 primaryExpr/parsePrimary**：否则 TS parsePrimary 会吃 dotted 链 + `(`-调用 → `apply f(y) to x` TS 接受、Java 不接受 = 分歧。`callTarget` = 裸名/限定名点链，**不含调用后缀/construct/list/lambda/wrap**。Java: `callTarget : (IDENT|TYPE_IDENT|MAP|structKeywordName) (DOT (IDENT|TYPE_IDENT|structKeywordName))* ;`；TS: 抽 `parseCallTargetNameOnly()`（复用 dotted-name 逻辑但不吃 `(`）。
3. **apply 做软关键词（标识符位置放行）**：现有 AstBuilderTest 有 `Rule apply given …`（函数名叫 apply!）。硬保留 apply 破该测试。故 apply 是上下文关键字：ANTLR 硬 token `APPLY` 但加入 `nameIdent`/限定名/成员/primary-var 等标识符位置的软关键字放行集；TS 避免 apply 作普通名时被 KEYWORD 挡住 IDENT 分支。
4. **新 SemanticTokenKind.APPLY 须同步所有 lexicon**（LexiconRegistry 校验枚举完整，否则 locale 插件红）：内置 en-US/zh/de/hi + 任何插件 lexicon 都要加 `keywords[APPLY]`。

`apply f to x` 解析后 **lower 成现有 `Call(f, [x])`**——与运算符 lower 成 `Call{Name op}`、`ok of x` lower 成 Call 同理。零新节点 → parity 基础：`apply f to x` 与 `f(x)` 编译到**结构一致 Core IR**（剥 origin 后 deepEqual）。

### 单参 only（本批）
demo 的 gather 是单参递归。多参（`apply f to x and y`）本批不做（语义/歧义更复杂），记 backlog。`x` 是完整表达式（`apply gather to stars less 1` 里 `stars less 1` 是 arg 表达式）。

### Java 引擎（ANTLR）— 如实实现
- 新 token `APPLY: [Aa][Pp][Pp][Ll][Yy];`（AsterLexer.g4，大小写不敏感，与其它结构关键词一致）。复用现有 `TO_WORD: 'to'`。
- `applyExpr` 放 **unaryExpr 层**（非 primaryExpr，避免 postfixExpr 对 apply 结果接后缀）：
  ```
  unaryExpr  : NOT unaryExpr # NotExpr | applyExpr # ApplyCallExpr | postfixExpr # PostfixUnary ;
  applyExpr  : APPLY callTarget TO_WORD expr ;
  callTarget : (IDENT | TYPE_IDENT | MAP | structKeywordName) (DOT (IDENT | TYPE_IDENT | structKeywordName))* ;
  ```
  `<arg>` 用顶层 `expr`（贪婪 → `apply f to a plus b` = `Call(f,[a plus b])`）。
- **软关键词**：`APPLY` 加进 `structKeywordName`（已被 `nameIdent`/成员/construct 字段/primary-var 引用），故 `Rule apply given …`（函数名叫 apply）不破。applyExpr 仅在表达式位置以 APPLY 起头触发。
- `AstBuilder.java`：`visitApplyCallExpr` → `new Expr.Call(visitCallTargetName(...), List.of(visit(arg)), span)`；`visitCallTargetName` 把点链拼成单个 `Expr.Name`（复用 combineName 口径，与后缀调用 `Math.abs(x)` 一致）。**无新 AST 节点**。

### TS 引擎（hand-written parser）— 如实实现
- `src/config/token-kind.ts`：加 `APPLY = 'APPLY'`（SemanticTokenKind，可别名）。
- `src/config/semantic.ts`：`KW.APPLY: 'apply'`（小写值，tokLowerAt 比较）。
- 5 个内置 lexicon（en/zh/de/hi/template）+ 任何插件：`keywords[APPLY]`（en `apply`/zh `应用`/de `wende an`/hi `लागू करें`）。
- `parseApplyOrPrimary`（在 parsePrimary 之上一层，与 Java unaryExpr 同层）：`applyLooksLikeCall` 软关键词形态前瞻（apply 后须 `名(.名)* to` 才当调用引入词，只 peek 不移游标 → `apply(x)` 后缀调用、`Rule apply given` 函数名都不被拦）；`parseCallTargetName` 取裸名/限定名点链不吃 `(`；arg 用顶层 `parseExpr`。parseMultiplication 的 7 处 parsePrimary 调用改走 parseApplyOrPrimary。

### 歧义/边界
- `apply` 是新保留词：行首/表达式位见 `apply` 即进 applyExpr。会不会撞名为 `apply` 的标识符？——加词即占用该词为关键词（与所有关键词同）；诗 demo 不需要 `apply` 当变量名。**风险点交 Codex 审查**：`apply` 作为常见英文词被保留，是否破坏现有 corpus（grep 现有 .aster 有无 `apply` 标识符）。
- `to` 复用：`apply f to x` 的 `to` 由前置 apply 限定，不与 `Set x to y` / construction 冲突（不同起头 + applyExpr 在 primaryExpr 内）。**交 Codex 确认无歧义**。
- arg 是完整 expr：`apply f to a plus b` → arg=`a plus b`（Call(f, [a+b]))，还是 `(apply f to a) plus b`？——须定义优先级。提案：applyExpr 在 primaryExpr 层，arg 取顶层 expr 会"贪婪"吞到语句末——这可能与"applyExpr 是 primaryExpr（高优先级）"矛盾。**这是最大设计风险，Codex 重点审**：arg 该取 `expr`（贪婪，apply 像低优先级前缀）还是更窄的层（如 additiveExpr）？单参 + 诗用例 `apply gather to stars less 1`（arg=`stars less 1` 整个）倾向贪婪到语句末/到下一个语句边界。

### 与 ADR 0026 续行
`apply f to x` 的 `x` 是 expr，可含等缩进续行（`apply gather to stars\n less 1`）。正交，无冲突（续行在二元循环内，apply 在 primaryExpr 外层）。**交 Codex 确认**。

## 4. parity fixture（须专门）
- `apply f to x` 与 `f(x)` 编译到结构一致 Core IR（剥 origin deepEqual）——核心不变式。
- 递归：`apply gather to stars less 1` 双引擎 parse + ir + eval 逐位一致。
- arg 含运算符链：`apply f to a plus b` 优先级两引擎一致。
- 负面：`apply` 后非名 / 缺 `to` / `to` 后非 expr 报错；`Set x to y` 仍正常（apply 不影响）。

## 5. 分阶段
- 本批：单参 `apply f to x`，双引擎 + 三 parity + demo 改写（nightfall 那行去括号）。
- 不做：多参（`apply f to x and y`）；保留 `f(x)` 经典形（不删，向后兼容）。

## 6. 回滚
新增 token + 一条 primaryExpr 产式 + parsePrimary 一分支，lower 到现有 Call，**零 Core IR/AST/类型/求值改动**。经典 `f(x)` 不变。parity red 可整批回退。

## 7. Codex 设计审查重点（实现前）
1. **arg 优先级**：applyExpr 的 `<arg>` 取 `expr`（贪婪）vs 更窄层——`apply f to a plus b` 该解析成 `f(a+b)` 还是 `(f a) plus b`？哪个无歧义且符合诗用例？ANTLR primaryExpr 内嵌 expr 会不会左递归/歧义？
2. **`apply` 保留词冲突**：grep 现有 corpus/.aster 有无 `apply` 当标识符/字段名；保留它破坏什么。
3. **`to` 复用消歧**：`apply f to x` 的 to vs `Set x to y` / construction `set to`——ANTLR ALL(*) 与 TS 手写 parser 能否干净区分。
4. **fn 位置**：`<fn>` 用 primaryExpr 会不会把 `apply gather to` 的 `gather` 后误吞（如 `gather` 后跟 `.method`）。
5. 双引擎 lower 一致（都 Call(fn,[arg])）；与 ADR 0026 续行正交。

## 8. 验证结果（三引擎全绿）

- **Java 单测** `ApplyCallTest` 13/13：`apply f to x` ≡ `f(x)` 结构一致 Core IR（裸名/限定名/贪婪 arg/递归/二元右操作数）；`apply Some to x` ≡ `Some(x)`、`apply Ok to x` ≡ `Ok(x)`（复用 createCallExpression）；`apply Map.get to m` ≡ `Map.get(m)` + `apply Foo.Map to x` 不报错；软关键词 `apply if/return to x` 接受、硬关键词 `apply and/with to x` 拒绝；`Rule apply given`/`apply(x)`/`Set x to y` 不破；缺 `to` 报错。core 全量 1300/0。
- **TS 单测** `apply-call.test.ts` 10/10（裸名/限定名/Map/wrapper Some·Ok/贪婪 arg·二元右操作数/递归 eval/软关键词接受·硬关键词 8 词全拒/缺 to 报错）+ `alias-poem-story.test.ts` 别名不变式（`echoing gather to` ≡ `apply gather to`）。TS 全量 1309/0 + 集成 87/0，tsc 干净。
- **三 parity**（aster-lang-test，新增 `g3-apply-call.aster`（7 rule：twice/countdown 递归/run/wrap=`apply Some to x`/rightOperand=`a plus apply twice to b plus 1`/return=软关键词函数名/viaSoftKeyword=`apply return to x`）+ `.cases.json`，进 manifest 第 217 个样本）：
  - parse（PR-blocking）217/217，0 divergent。
  - ir：g3-apply 两引擎结构一致（仅 2 个**既有** effect-alias 样本 divergent，与本改动无关）。
  - eval（驱动 Truffle 真执行）255/255 引擎一致且匹配 golden（`run(4)=10`、`run(0)=2`）。
- **demo**：nightfall 的 `gather(stars less 1)` → `echoing gather to stars less 1`（NIGHTFALL_EN 把 APPLY 别名成诗词 `echoing`），递归正确执行（1/2/3 颗星逐一聚拢）。源码即诗的最后一处「程序痕」（括号）已藏。

### ★实现期发现（与 apply 无关的既有 TS 缺口）
写 corpus 样本时误用 `stars less 1`，parity 报 `g3-apply` TS 拒/Java 收。二分隔离证明：**`less` 在默认 en-US 词典根本不是合法运算符**——`Return stars less 1`（无 apply/调用）TS 也拒，Java 收（"Expected '.' at end of statement" / 调用内 "Expected ')'"）。canonical 减法词是 `minus`，`less than`=LESS_THAN（比较），裸 `less` 两者都不是。诗 demo 的 `stars less 1` 能编译纯因 NIGHTFALL_EN 别名把 `less`→MINUS_WORD。教训：①写 parity 样本只用默认词典合法语法（别把方言别名词当通用）；②这是又一处**既有双引擎运算符词分歧**（Java 比 TS 宽容），记 backlog，与本 ADR 无关。样本改用 `minus` 后 217/217 全绿。

## 9. ★Codex 实现审查 3 轮（生成者=Claude ≠ 审查者=Codex，session 019f1639）

实现完成后交 Codex 深度审查，3 轮逐步逼近：78 → 86 → 94 通过。Codex 抓出 **3 处真实双引擎分歧**（均为 PR-blocking parse parity 风险，本会 demo 主路径不触发但语法面真实存在），逐一修复：

1. **`Map` 点链后续段（78 分轮）**：Java `callTarget` 原写 `(...|MAP|...) (DOT (IDENT|TYPE_IDENT|structKeywordName))*` —— MAP 只在首段放行；TS 无 MAP token，`Map` 处处是 TYPE_IDENT → `apply Foo.Map to x` TS 收 Java 拒。修：拆 `callTargetSegment : IDENT|TYPE_IDENT|MAP|structKeywordName` 子规则，MAP 每段放行；visitCallTargetName 改遍历 `callTargetSegment()` accessor（弃脆弱的 ctx.children 跳 DOT）。
2. **apply 绕过普通调用构造路径（78 分轮）**：`visitApplyCallExpr` 原直接 `new Expr.Call(...)`，但普通后缀调用走 `createCallExpression`（对 Ok/Err/Some/None 规范成 Expr.Some 等专用节点）→ Java 内部 `apply Some to x`（裸 Call）≠ `Some(x)`（Expr.Some），破「等价 fn(arg)」不变式。修：visitApplyCallExpr 改调 `createCallExpression`。cross-engine 论证（Codex 核验成立）：TS 两形态都 lower 成 `Call(Name "Some")`，parity `normalizeIr()` 把它规范成 `{kind:"Some"}` → 与 Java Expr.Some 等价；g3 wrap rule 的 IR-identical 证明 gate 口径下闭合。
3. **硬关键词作 callTarget 名（86 分轮）**：Java `callTargetSegment` 只放行 `IDENT|TYPE_IDENT|MAP|structKeywordName`；TS 上下文关键词模型把 `and/or/not/with/to/given/produce/set` lex 成 IDENT → `apply and to x` TS 收 Java 拒。修：TS `applyLooksLikeCall` 对首段 + 每点链段调 `isValidCallTargetWord`（`!ALL_KEYWORDS.has(w) || APPLY_TARGET_SOFT_KEYWORDS.has(w)`，软关键词集逐一对齐 Java structKeywordName + MAP）。token 进 parser 前已 canonicalize 成英文 KW，故 `ALL_KEYWORDS = Object.values(KW)` 无需 lexicon 感知（中文 `和`→`and` 经 canonicalize 后同样被拦）。

**教训**：①grammar 改动哪怕主路径全绿，Codex 仍能从「新增语法面的接受集」挖出 demo 不触发的真实分歧——对抗式审查在 parity 铁律下不可省；②Java（硬 token + 显式放行集）与 TS（上下文关键词，词性靠位置）两套关键词模型，每加一处「名字位置」都要显式对齐放行集，否则 TS 默认更宽松；③apply/调用等「lower 成现有节点」的语法，必须复用现有构造 helper（createCallExpression），不能旁路重建——否则继承不了既有特例规范化。
