# ADR 0056 修订 A8：D4 之后余下的一侧接受（for / one / of / function / performs 作名字、限定名里的小写关键词段、`H.not(x)`；core 独有的 `and` / `has` 写法；第 3 条「处处照 core」）

- 状态：**ACCEPTED**（2026-11-28，产品负责人；第 3 条同日追加，产品负责人：「follow core everywhere」——处处照 core）
- 日期：2026-11-28
- 修订对象：[ADR 0056](0056-type-inference-parity.md)，接续[修订 A7](0056-amendment-7-wave-d-rulings-1056.md)；第 1 条扩展 A7 D4 的范围，并取代 D4 实现里「成员调用 `v.not(x)` 照旧」的例外
- 相关：aster-cloud/aster-lang-ts#338（第 1 部分，关键词大小写 K）、#411、#414（D4）、#418（本修订第 1 条）、#417（第 2 条）、#420（第 3 条）；aster-cloud/aster-lang-core#335、aster-cloud/aster-lang-test#276、#277（第 3 条）
- 实现落点：
  - 第 1 条：`aster-lang-ts`、`aster-lang-test` 同名分支 `fix/one-sided-keyword-names`
  - 第 2 条：`aster-lang-ts` 分支 `fix/411-keyword-case-ts-positions`（与 #411 的大小写部分同一分支）
  - 第 3 条：`aster-lang-ts`、`aster-lang-core`、`aster-lang-test` 同名分支 `fix/follow-core-one-sided`（core 只加共享夹具与测试）

三条裁决都只改 ts 的行为（第 3 条另在 core 加共享夹具与测试、在 aster-lang-test 加样本，不改 core 的行为）。生产编译走 core，core 的行为不变。

---

## 0. TL;DR

| # | 写法 | 现状 core / ts | 裁决 | 收紧 / 放宽 |
|---|---|---|---|---|
| 1a | `for`、`one`、`of`、`function`、`performs` 作名字（形参、字段、Let 名、成员名等，D4 的全部位置） | 语法错误 / 接受 | ts **拒绝**：E055 + 转义提示（`` `of` ``） | ts 收紧 |
| 1b | `Use io.to as H.`、`Module a.of.`：Module / Use 限定名的段是小写关键词 | 语法错误（`and` / `or` / `not` / `with` 除外）/ 接受 | ts **拒绝**同一批段：E055 + 转义提示 | ts 收紧 |
| 1c | `H.not(x)`、`v.not(1)`：点之后的 `not`（以及 `set`） | 点是语句结尾 / 成员调用 | ts **照 core 读**：`Return v.not(1).` = `Return v.` + `not(1).`，Core IR 相同；`H.not(x)` 两引擎都报 E802 | ts 改变含义（与 core 对齐） |
| 2 | 变体之间的 `and`、lambda 形参之间的 `and`、字段列表里换行起头的 `and`、`has` 构造（`S has p set to 1`） | 接受 / 拒绝 | ts **接受** | ts 放宽 |
| 3a | 构造里首字母大写的字段名（`S with p set to 1 and Q set to 2`、首个字段 `S with P set to 1`） | 接受 / 拒绝 | ts **接受**，同 Core IR | ts 放宽 |
| 3b | 顶层省略 Define 的数据类型（`S has x as Int.` / `S with x as Int.`） | 接受 / E064 | ts **接受**，同 Core IR | ts 放宽 |
| 3c | 字段列表里换行起头的逗号（`a as Int` ⏎ `, b as Int`） | 接受 / 拒绝 | ts **接受**，同 Core IR | ts 放宽 |
| 3d | 点之后首字母大写的 core 记号 `.Set` / `.Use` / `.On` / `.It` / `.Map` / `.Module` | 点是语句结尾 / 成员 | ts **照 core 读**：`Return v.Set y to 2.` 收；`v.Map(1)` 同 IR；`v.On`、`Return On(1).` 拒 | ts 放宽 + 改变含义 + 收紧 |
| 3e | 裸表达式语句（`v.`、`1.`、`ok of 1.`），连带非名字表达式之后的点 | 降为 `Let _` / lowerStmt 报错 | ts **照 core**：降为 `Let _ be …`；`f(1).g(2)` 方法调用、`f(1).x` E064 | ts 放宽 + 改变含义 + 收紧 |
| 3f | 规则头里 with 引导的形参（`Rule f with a as Int, …`） | E062 / 接受 | ts **拒绝**：E062，与 core 同码、同位置、同参数 | ts 收紧 |

---

## 1. 第 1 条：ts 拒绝，同 D4（扩展 A7 D4）

### 1.1 `for` / `one` / `of` / `function` / `performs` 作名字

- **现状**：这五个词在 core 词法里是关键词记号，不在 `structKeywordName`（软关键字集）里，任何名字位置都是语法错误；ts 收作名字。D4（ts#414）的 PR 已把它们列为「仍未收紧的一侧接受」。
- **裁决**：ts 拒绝，与 D4 同一个诊断：**E055**，消息「`'of' is a keyword and cannot be used as a name; write it in backticks (`` `of` ``) to use it as a name`」，参数沿用该位置原有的 `token`。位置与 D4 相同：形参（含 lambda）、字段、Let / Set / Start / Wait 名、规则名、类型参数、类型别名名、导入别名、构造字段、成员名、名字引用、Match 绑定名、注解名、注解参数名与标识符形式的参数值。de / zh 源码里直接写的英文拼写同样拒绝（core 同样拒绝）；这五个词在 de / zh 都是多词短语，没有单词翻译。
- **结果**：D4 的名单加上这五个词，恰好是 core 词法里「不在 `structKeywordName` 的小写单词记号」的全集（21 个）。大写开头与混合大小写拼写照旧是同形词；名字转义永远是名字（ADR 0064 N3）。

### 1.2 Module / Use 限定名的段

- **现状**：`Use io.to as H.` 里 core 拒绝的是**路径段** `to`（别名位置的 `as to` 已由 D4 拒绝）。core `qualifiedSegment` 只放行 `IDENT`、`TYPE_IDENT`、`AND`、`OR`、`NOT`、`WITH` 与 `structKeywordName`；其余 17 个小写关键词（`to`、`produce`、`has`、`set`、`as`、`one`、`of`、`use`、`for`、`async`、`on`、`function`、`performs`、`times`、`modulo`、`plus`、`minus`）作首段、中段、末段，在 Module 头与 Use（带不带 `version` / `as`）里一律是语法错误。ts 全部接受。
- **裁决**：ts 拒绝同一批段，E055 + 转义提示。de / zh 翻译成这些关键词的拼写（de `auf`、`als`、`mal`，zh `设为`、`基于`、`异步` …）同样拒绝；`and` / `or` / `not` / `with` 及其翻译（de `und`、`nicht`，zh `并且`）与转义段（`` Module a.`to`. ``）两引擎照旧接受。

### 1.3 点之后的 `not` / `set`：ts 照 core 读

- **现状**：core 的成员后缀是 `DOT (IDENT | TYPE_IDENT | structKeywordName)`，不收 `NOT` / `SET` 记号，点因此是**语句结尾**，同一行可以接着写下一条语句：
  - `Return v.not(1).` 读作 `Return v.` 加表达式语句 `not(1).`（IR：`Return v`、`Let _ = not(1)`）；
  - `Return v.set y to 2.` 读作 `Return v.` 加 `set y to 2.`；
  - `Return H.not(x).`（H 是导入别名）读作 `Return H.` → **E802**（H 不是值）。

  ts 此前读作成员调用：`v.not(1)` 两引擎都能编译但 Core IR 不同，`H.not(x)` ts 收、core 拒；D4 的 ts 实现特意保留了这一例外。
- **裁决**：ts 跟 core。点之后未转义的 `not` / `set` 不是成员名，点是语句结尾；语句位置的 `v.not(1).` 拆出的裸名字 `v.` 照 core 降为 `Let _ be v`。由此 `v.not(1)` 两引擎 Core IR 相同，`H.not(x)` 两引擎都报 E802，`g(v.not(1))`、`[v.not(1)]`、`If v.not(true)` 两引擎都拒绝。其余 19 个小写关键词开不了语句，core 怎么读都报错，ts 照 D4 在成员名处报 E055。确实要访问名为 `not` 的成员时写 `` v.`not`(1) ``（两引擎同为成员调用）。

### 1.4 门禁

- ts 收紧（1.1、1.2）加 ts 改变含义（1.3），跑 **ts scan**（旧 ts 与新 ts 的 compile / `ir-diff`）：新失败只应出现在 core 本来就拒绝的源码上；IR 变化只应是改为等于 core 的那些。core（生产）不变，不跑 java-gate。
- 正则预筛 RK8a–RK8i（全文见 aster-lang-ts#418）：RK8a–RK8g 是 D4 的 RK4a–RK4g 换成这五个词（另加调用目标 `Return for(x)`），RK8h 是限定名段，RK8i 是名字之后的 `.not` / `.set`（含 de `nicht` / `setze`、zh `不是` / `将`）。
- 实现 PR 的自测：三方探针（core main / ts main / 新 ts）15 095 份去重源，「新 ts 拒、core 收」新增为 0；ts main 收、新 ts 拒的 509 份 core 全部拒绝；Core IR 改变的 17 份全部改为等于 core。正则对这些正例 559 / 559 命中，对 7 080 份行为不变的源 0 命中。
- 命中处理同 A7 §8 第 3 项：命中的源码在 core 本来就失败（或改为与 core 同一程序），生产行为不变，逐条确认后照常上线；作者在编辑器里得到 E055 与转义提示。

## 2. 第 2 条：ts 接受 core 独有的写法

- **现状**（aster-lang-ts#411）：以下写法 core 接受、ts 报错（连小写都拒绝）：
  - 变体之间的 `and`（`Define S as one of A and B.`）；
  - lambda 形参之间的 `and`；
  - 字段列表里换行起头的 `and`；
  - `has` 构造（`S has p set to 1`）。

  #411 的另一半是大小写问题：规则头形参之间的 `And`、变体之间的 `Or`、`apply … To`、构造里 `and` 之后的 `SET`，ts 只认小写开头的拼写。它属于 A4 §1.1 / ts#338 的关键词大小写，不在本条；本条的四种写法 ts 连小写都拒绝。
- **裁决**：ts 接受这四种写法，Core IR 与 core 相同。
- **理由**：生产（core）已经接受；这些是 ts 解析器的缺口，不是有歧义的写法。按 ADR 0056 的 parity 规则（A1 §1 原则 1），「core 接受、ts 拒绝」按 core 修 ts。
- **落地**：ts 放宽，与 #411 的大小写部分同一分支。
- **门禁**：ts scan，只看 newly-passing（应全部与 core 同 IR）；core 不变，不需要正则，不跑 java-gate。

## 3. 第 3 条：处处照 core（2026-11-28 追加）

- **裁决**（产品负责人，2026-11-28）：**「follow core everywhere」——处处照 core。** aster-lang-core 是参照：core 接受的写法 ts 同样接受且 Core IR 相同；core 拒绝的写法 ts 同样拒绝（能同码的同码）；core 的读法与 ts 不同时 ts 改照 core 读。本条落实 ts#417 / ts#418 实现时记下的六项一侧差异（其中 3d、3e 是本修订原 §3 的两项）。
- **理由**：生产编译走 core，作者在编辑器（ts）里看到的结论必须与上线结果一致；逐项再议的成本高于直接对齐。本条六项都没有「ts 的写法更好、应改 core」的论据。

### 3.1 3a：构造里首字母大写的字段名（ts 放宽）

- **现状**：core `constructField` 收 `IDENT | TYPE_IDENT | structKeywordName`，构造的后续字段由 `kwGateAnd` / `isConstructFieldAt` 判定——and / 逗号之后第二个记号是 set 即是下一个字段，不论字段名是什么记号；字段名再由 R2 按字段解析（`Q` 解析到声明的 `q`）。ts 只收小写开头的字段名，`and Q set to 2` 里的 and 读成逻辑与。
- **裁决**：ts 照 core：字段名收首字母大写的名字（首个字段、and 与逗号之后），and 之后第二个记号是 set 即是分隔符。core 的大写关键词记号（`Set` / `Use` / `On` / `It` / `Map` / `Module`）不是字段名，两引擎都报缺 set to（E057，同码同位置）；结构关键词（`Let`）照 A6 K1 报 E901。

### 3.2 3b：顶层省略 Define 的数据类型（ts 放宽）

- **现状**：core `dataDecl` 第二个分支 `article? TYPE_IDENT (kwHas | kwWith) fieldList DOT` 收 `S has x as Int.`；ts 报 E064（文法注释登记为 Java 独有）。
- **裁决**：ts 接受，Core IR 与 `Define S has x as Int.` 相同。类型名按 core 的 TYPE_IDENT 口径：ASCII 大写、Latin-1 大写、Latin Extended-A 与 CJK 开头（`Über`、`Āb`、zh `用户`），小写开头（`s`、`über`、天城文）与 core 的关键词记号（`Start`、`On`、`Let` …）两引擎都拒；`Type has …`、`Profile has …` 在 core 同样是数据类型，ts 照收（`Type Score as Int.` 仍是别名）。前向引用（规则在前、省略 Define 的定义在后）与 `Define` 同样登记。

### 3.3 3c：字段列表里换行起头的逗号（ts 放宽）

- **现状**：core `fieldList` 的分隔符是 `NEWLINE* (kwAnd | COMMA) NEWLINE*`；第 2 条已让 ts 收换行起头的 and，逗号仍拒。
- **裁决**：与换行起头的 and 同法接受（不缩进；缩进的续行两引擎都拒）。

### 3.4 3d：点之后首字母大写的 core 记号（ts 照 core 读）

- **现状**：core 词法里 `Set`（及 `set`）、`Use`、`On`、`It`、`Map`、`Module` 是关键词记号，不在 `structKeywordName`，成员后缀 `DOT (IDENT | TYPE_IDENT | structKeywordName)` 不收，点因此是语句结尾；除 `Map`（`MapIdentExpr`）外它们也不能开启表达式。ts 把它们读作普通 TYPE_IDENT。
- **裁决**：ts 照 core：
  - `Return v.Set y to 2.` = `Return v.` 加 `Set y to 2.`（两引擎都收，同 IR）；
  - `Return v.Map(1).` = `Return v.` 加 `Map(1).`（此前两引擎都收但 Core IR 不同，现在相同）；
  - `Return v.On.`、`v.It(1)`、`Let y be v.Use.`、`Return Http.Map(1).`（E802：`Http` 不是值）两引擎都拒；
  - `Return On(1).` 之类以这些记号开头的表达式 ts 报 E055（「是关键词，不能开启表达式」+ 转义提示 `` `On` ``）；转义名照旧是名字（ADR 0064 N3）。

### 3.5 3e：裸表达式语句（ts 照 core 降级）

- **现状**：core `exprStmt: {!isStatementHead}? expr DOT`，`AstBuilder.visitExprStmt` 一律降为 `Let _ be <expr>`。ts 只降调用（且绕过 lowerExpr：`h.g(1).` 不做 UFCS 拆分，`Some(1).` 不归一为 Some 节点，与 core IR 不同），其余（`v.`、`1.`、`ok of 1.`、列表、浮点 / 十进制字面量、前缀运算符调用）报 lowerStmt 错误或 E064。修订第 1.3 条只处理了 `.not` / `.set` 拆出的裸名字。
- **裁决**：ts 照 core，任何能开启表达式的记号开头的语句都降为 `Let _ be <expr>`，表达式整体走 lowerExpr（第 1.3 条的特例由此取代）。连带照 core 处理「非名字表达式之后的点」（core `postfixExpr` 的成员后缀，ts 此前一律读作语句结尾）：
  - `f(1).g(2)`、`(v).g(1)`、同行的 `Let a be 1. g(2).` 是方法调用 `g(<接收者>, 2)`（core `applyCallSuffix`）；`(v).x` 是限定名 `v.x`；
  - 字面量 / 调用 / 列表之后的 `.x`（`Return f(v).x.`、`Return true.x.`）两引擎都报 E064（core `applyTrailingMembers`）；
  - 成员读法走不通时点是语句结尾，同行的下一条语句照旧（`Let a be 1. Return a.`、`Let a be 1. ok of a.`）；但 core 的预测在 `Let` / `If` / `Start` / `Match` / `When` / `Define` / `apply` / `Otherwise` / `Within` / `SET` 上贪婪取成员读法（`Let a be 1. Let x be 2.` 在 core 是 E063），ts 照拒；
  - 行首字符串开头的语句（`  "a" + v.`）：core 规范化时丢掉这种行的缩进，报 E061，ts 同码同位置（第 1 列）报错。

### 3.6 3f：规则头里 with 引导的形参（ts 收紧）

- **现状**：core `funcDecl` 的形参只有 `givenParamList`，`Rule f with a as Int, …` 在 with 上报 E062；ts 把 with 当 given 的同义词。
- **裁决**：ts 报 **E062**，`token`（小写拼写与本地化关键词为 `kw.with`，其余拼写为原文 `'With'`）、`expected`（core 在该处的期望集合：规则名之后 `'.' | ':' | ',' | '<' | kw.given | kw.produce | kw.of`，类型参数之后去掉 `kw.of`，`of T,` 之后 `'.' | ':' | kw.produce`，`<E>` 之后去掉 `'<'`）、位置与英文消息都与 core 相同。de / zh 源码同码同参数，列号 core 按规范化后的文本报（`Regel f mit` 报在第 8 列），ts 报源码位置。lambda 的 `function with x as Int, …` 不受影响（core `lambdaParamList` 收 with）。

### 3.7 落地与门禁

- **落地**（aster-lang-ts#420）：parser 与 lower_to_core 照上文改；共享夹具 `follow_core_parity.json`（108 例：66 例两引擎同一 positionFreeSha256，42 例两引擎都拒，其中 23 例第一条诊断的码 / 行 / 列及 params、message 逐项相同）在 ts 与 core（aster-lang-core#335，`FollowCoreParityTest`）逐字节相同；aster-lang-test#277 加 tier1 样本 `syntax/follow-core-one-sided` 与 6 个必拒样本，tier3 的 6 份 zh 样本规则头 `包含` 改为 `给定`。
- **证据**：三方探针（core main / ts main / 新 ts）34 386 份去重源（第 1 条的 15 095 份、#411 的语料与矩阵、各仓 `.aster` 与夹具 source、本条按六项生成的 15 868 份）：新 ts 相对 ts main 拒 → 收 1 322 例全部 core 收且同 IR；收 → 拒 620 例全部 core 拒；IR 改变 616 例全部改为等于 core；真实语料 7 980 份 0 例 IR 改变。「新 ts 收、core 拒」新增 3 例，都是 3b 写法继承了 `Define` 已有的一侧差异（§4 第 1、2 项），不是新类别。
- **门禁**：ts 收紧（3d、3e、3f）加 ts 改变含义（3d、3e），跑 **ts scan**（旧 ts 与新 ts 的 compile / `ir-diff`）：新失败只应出现在 core 本来就拒绝的源码上，IR 变化只应是改为等于 core 的那些。core（生产）不变，不跑 java-gate。正则预筛 RF3a–RF3i（全文见 aster-lang-ts#420）：a 规则头 with 形参（含 de `mit`、zh `包含`），b 名字点链里的 `.Set` / `.Use` / `.On` / `.It` / `.Map` / `.Module`，c 作值或调用目标的 `Use` / `On` / `It` / `Module` / `Set`，d 行首字符串开头的行，e 字面量 / 调用之后同行的贪婪语句头，f 句首的限定名调用与 `Some` / `Ok` / `Err` / `None` 调用语句，g 非名字表达式之后的 `.名字(…)`，h 非名字表达式之后的 `.名字` 紧跟运算符或句点，i 非名字表达式之后的 `.Use` / `.On` / `.It` / `.Module` / `.Set`。自测：正例 1 236 / 1 236 命中；10 208 份行为不变的源 1 命中（RF3d 多行表达式里以字符串开头的续行，预筛误报）；aster-lang-test 语料 origin/main 快照 6 命中，全是 tier3 的 6 份 zh 样本（真收紧）。
- 命中处理同 A7 §8 第 3 项：命中的源码在 core 本来就失败（或改为与 core 同一程序），生产行为不变，逐条确认后照常上线。

## 4. 仍未裁决的一侧差异（记录）

本修订原 §3 的两项（首字母大写的 core 记号在成员位置、裸表达式语句）已由第 3 条（3d、3e）裁决。第 3 条的实现探针另记下以下差异，不在本修订范围：

1. 字段列表里分隔符之后缩进的续行（`Define S has a as Int,` ⏎ `  b as Int.`）：ts 收、core E052（3b 的省略 Define 写法同样继承）。
2. 没有 Module 头也没有 Rule 的源（只有数据定义）：ts 收、core 嵌入式 API 报「无法提取模块名称或函数名称」。
3. Return 之后的效果句 `Return "ok". It performs io.`（含 `Return f(1).It performs io.`）：ts 收（有单测）、core 拒。
4. 返回类型之后的 `with IO` 效果（`…, produce Int with IO:`）：ts 收、core E062。
5. core 关键词记号的首字母大写拼写作声明名：`Define Workflow / Step / Retry / On / Set … with …`、枚举成员 `On`、`Let On be …`、`Type On as Int.`、`Use io.Http as On.`、模式 `When On,`：ts 收、core 拒。
6. core 收、ts 拒：`x of 1`（小写名字 + of 的 wrap 表达式）、`g(1)(2)`（调用结果再调用）、`Let a be v. Return a.`（名字点链之后同行的 Return）、`A S has x as Int.`（顶层行首冠词）、`SET(1).`（ts 读作 Set 语句）；`.True` / `.Null` 作成员 core 收、ts 照 K-c D2 报 E055。
