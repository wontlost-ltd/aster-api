# ADR 0056 修订 A3：#328 之后余下七处解析分歧的双引擎裁决（aster-lang-ts#334）

- 状态：**ACCEPTED**（2026-11-16，用户授权按推荐方案裁决）
- 日期：2026-11-16
- 修订对象：[ADR 0056](0056-type-inference-parity.md)，接续[修订 A2](0056-amendment-2-parity-signature-syntax.md) §4 留下的分歧
- 相关：aster-lang-ts#334
- 实现落点：`aster-lang-core`、`aster-lang-ts`、`aster-lang-test` 同名分支 `fix/parity334`

---

## 1. 裁决

| # | 写法 | 修复前 core / ts | 裁决 | 诊断 |
|---|---|---|---|---|
| 1 | `Define Order with Id as Text.`（大写字段名） | 接受 / E055 | 两引擎都拒绝，判据同 A2 第 2 项（首字符 A–Z；CJK、Latin 扩展、天城文、本地化关键词还原的名字不受影响） | **E055** `Expected field name`（`{token: token.fieldName}`），报在字段名上 |
| 2 | `has @pii(L1, email) email as Text`（字段前置注解） | 接受 / E055 | 两引擎都接受，与 A2 第 3 项的形参注解同法。`Field.annotations` 先列前置注解、再列类型最外层的 `@pii`；字段位置从字段名起 | — |
| 3 | `Rule Approve given …`（大写规则名） | 接受 / E055 | 两引擎都拒绝，与第 1 项、A2 第 2 项一致 | **E055** `Expected identifier`，报在规则名上 |
| 4 | `USE lib.x.`（全大写导入关键词） | 拒绝（E063）/ 接受 | 两引擎都接受，见 §2 | — |
| 5 | `_x`（下划线开头的标识符） | 接受 / E050 | 两引擎都拒绝，见 §3 | **E050** `{char: '_'}`，词法报在 `_` 上 |
| 6 | `Let f be function given x as Int, produce Int: Return x.`（lambda 单行体） | 接受 / E055 | 两引擎都接受，见 §4 | — |
| 7 | `given x as Pair of Int, y, produce …` | 拒绝 / 把 `y` 读成类型参数 | 两引擎都拒绝，见 §5 | **E055** `Expected type name: 'y' would be read as another type argument; …`（`{token: token.typeName}`），报在 `y` 上 |

注：issue 表把第 4 项的 core / ts 写反了；A2 §4 的记录（ts 接受、core 拒绝）与实测一致。

## 2. 导入关键词大小写（第 4 项）

A2 §2 的原则：两个词法器对声明关键词都不区分大小写。core 的 `USE` 记号只认 `Use` / `use`，与 A2 处理 `type` 时的情形相同。按同法补齐：`importDecl` 句首在 `USE` 之外，用语义谓词判定 `TYPE_IDENT` / `IDENT` 的文本等于 `use`（不区分大小写）。**不**把这些拼写升为硬关键词，`USE` 作 Data 名、形参类型时照旧可用（ts 同样如此）。

其余关键词（`MODULE`、`SET`、`GIVEN`、`AS` 等句中关键词）在 ts 同样不区分大小写，而 core 多数只认小写或首字母大写。这是更大的一类分歧，不在本轮范围，见 §6。

## 3. 下划线开头的标识符（第 5 项）

依据：

- 四个 lexicon（en-US / zh-CN / de-DE / hi-IN）、`docs`、aster-lang-test 语料（tier1 / tier2 / tier3）以及 aster-api、aster-cloud、template、truffle、guard、ai-workspace 的 `.aster` 与内嵌源码里，没有任何以 `_` 开头的 Aster 标识符。
- core 的 `AstBuilder.visitExprStmt` 把裸表达式语句降成 `Let "_" be expr`，注释写明「`_` 不是合法标识符，不会与用户变量冲突」。但 core 词法的 `IDENT: [a-z_] …` 实际收 `_`，用户写的 `Let _ be …` 会与这个丢弃绑定同名。ts 一直不收。

所以按「没有证据就拒绝」的规则，core 词法改为 `IDENT: [a-z] IdentContinue*`，`_` 只能出现在首字符之后（`a_b`、`x_`、`Box_A` 不变），两引擎都在 `_` 上报 E050。

**连带修正（core 规范化器）**：core 去冠词的正则把冠词两侧的 `_` 当作标点（`_` 属于 `\p{Punct}`），于是 `a_b` 被剥成 `_b`、`the_total` 被剥成 `_total`、`Box_A has` 被剥成 `Box_has`。词法收 `_` 时这是静默改名（Core IR 里的名字与源码不同，ts 不改名），词法拒收 `_` 之后它会变成 E050。现在冠词两侧同样排除数字与下划线（`(?<![\p{L}\p{N}_.])…(?![\p{L}\p{N}_.])`），与 ts 的 `\b…\b` 一致：这些名字原样保留。

## 4. lambda 单行体（第 6 项）

core 文法 `lambdaExpr: FUNCTION lambdaParamList? COMMA? PRODUCE annotatedType? COLON (returnStmt | NEWLINE block)` 明确把 `: Return x.` 列为 lambda 体的一种形式，规则注释给出的例子正是 `function given x: Text, produce Text: Return x.`；`AstBuilder.visitLambdaExpr` 把单条 Return 包成一个块，块的位置与整个 lambda 相同。ts 只实现了块形。所以按文法意图，ts 接受：冒号后同一行只能是一条 `Return expr.`（其它语句仍要求换行），Let 右侧与表达式位置都适用，降成与块形相同的 Core IR。

issue 里的例子 `function given x as Int: Return x.` 少了 `produce`，两引擎都拒绝；裁决针对带 `produce` 的写法。

## 5. `X of A` 之后的裸小写名字（第 7 项）

`given x as Pair of Int, y, produce …` 的 `y` 有两种读法：Pair 的下一个类型参数，或下一个无类型形参。与 A1 处理括号类型表里歧义逗号（E064）的做法一致，不靠猜，直接报错。

- ts 此前读成类型参数。现在在分隔符（`,` / `and`）及可选的前置注解之后，遇到小写拉丁字母开头、后面不是 `of` / `<`、本身也不是内建类型关键词（`text` / `int` / `float` / `bool` / `maybe` / `map`）的名字时报 E055。
- core 一直拒绝：循环左边缘的谓词把分隔符判给 Pair，而类型名只收大写开头，于是在 ANTLR 错误恢复里失败。但首条非 E063 诊断取决于预测缓存的冷热（冷启动是下一行的 E052，热身后是本行的 E055），共享夹具无法稳定断言。core 现在在进入类型参数之前，用文法动作 `rejectBareLowercaseTypeArg()` 直接报出与 ts 相同的码、参数、文案和位置。动作只在真正解析时执行，不参与预测，不改变接受集合。
- list / option 只收一个参数，`list of Int, y` 的 `y` 本来就是形参，不受影响。`y as Text`、`(Pair of Int), y`、`Pair of Int, Text` 照常接受。

ts 格式化器随之修正：下一项没有写出类型时，以开放元数的 `X of …` 结尾的类型加括号输出（`(Pair of Int), y`）。此前它会输出 `Pair of Int, y`，重新解析时会改变含义。

## 6. 门禁扫描（发版前必需）

第 1、3、5 项会让 core 上原本能编译的源码失败。§3 的规范化器修正会让 core 上原本能编译的源码换一个名字（`a_b` 原来在 IR 里叫 `_b`，现在叫 `a_b`；字段名变化会影响请求输入的字段匹配）。第 7 项只收紧 ts，core 从未接受，但 ts 编辑器里保存的草稿可能含有它。第 2、4、6 项只放宽。

权威检查是列车门禁扫描：导出生产策略源码，用 aster-lang-platform `tools/gate-scan` 分别以新旧 core 编译。新出现的失败即为命中；§3 的改名需要对比新旧 Core IR 的名字。第 7 项要对 ts 草稿用新旧 ts 编译。下面的正则（PCRE，`grep -P` / `perl`）只用来预筛 en-US 源码（R5b 同时覆盖 de-DE 的冠词）。它们会有误报，本地化源码以门禁扫描为准。

```
# R1a 大写字段名：Define 行的第一个字段
^\s*[Dd][Ee][Ff][Ii][Nn][Ee]\s+(?:(?:[Aa]|[Aa]n|[Tt]he)\s+)?\S+\s+(?:has|with)\s+(?:@\w+(?:\([^)]*\))?\s+)*[A-Z]
# R1b 大写字段名：Define 行里后续带类型的字段
^\s*[Dd][Ee][Ff][Ii][Nn][Ee]\b(?!.*\bone\s+of\b).*(?:,|\band)\s+(?:@\w+(?:\([^)]*\))?\s+)*[A-Z]\w*\s+as\b
# R1c 大写字段名：多行字段表的续行
^\s+(?:@\w+(?:\([^)]*\))?\s+)*[A-Z]\w*(?:\s+as\b|\s*,\s*$|\s*\.\s*$)
# R1d 大写字段名：Define 行里后续无类型的字段（类型参数 `Pair of Int, Text` 也会命中）
^\s*[Dd][Ee][Ff][Ii][Nn][Ee]\b(?!.*\bone\s+of\b).*(?:,|\band)\s+(?:@\w+(?:\([^)]*\))?\s+)*[A-Z]\w*\s*(?:,|\.\s*$|\band\b)
# R3 大写规则名
^\s*(?:@\w+(?:\([^)]*\))?\s+)*[Rr][Uu][Ll][Ee]\s+[A-Z]
# R5 下划线开头的标识符（字符串字面量里的也会命中）
(?<![\p{L}\p{M}\p{N}_])_
# R5b 旧 core 去冠词误伤的标识符（改名，不是报错）
(?i)(?<![\p{L}.])(?:a|an|the|der|die|das|ein|eine|einen|einem|einer|eines)_|(?<=[\p{N}_])(?:a|an|the|der|die|das|ein|eine|einen|einem|einer|eines)(?![\p{L}.])(?![ \t]+(?:as|be|in|of|and|or|not|equals|is|at|greater|less|more|than|to|plus|minus|times|multiplied|divided|modulo)\b)(?![ \t]*[-+*/<>=%,.:)\]])(?![ \t]*$)(?!\()(?=[ \t]|\p{P}|\p{S})
# R7 X of A 之后的裸小写名字（仅 ts 草稿；单行的规则头 / Define / type 行与 lambda 头）
^(?=\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:[Rr][Uu][Ll][Ee]|[Dd][Ee][Ff][Ii][Nn][Ee]|[Tt][Yy][Pp][Ee])\b|.*\bfunction\s+(?:given|with)\b).*\b(?![Ll]ist\b|[Oo]ption\b)\w+\s+of\s+(?:(?!\bas\b)[^,:.()\n])*?(?:,|\band)\s+(?:@\w+(?:\([^)]*\))?\s+)*(?!(?:[Tt]ext|[Ii]nt|[Ff]loat|[Bb]ool|[Mm]aybe|[Mm]ap|produce)\b)\p{Ll}\w*+(?!\s*(?:of\b|as\b|<))
# R7b 同上，多行字段表的续行（`  x as Pair of Int, y.`）
^\s+(?:@\w+(?:\([^)]*\))?\s+)*\w+\s+as\b.*\b(?![Ll]ist\b|[Oo]ption\b)\w+\s+of\s+(?:(?!\bas\b)[^,:.()\n])*?(?:,|\band)\s+(?:@\w+(?:\([^)]*\))?\s+)*(?!(?:[Tt]ext|[Ii]nt|[Ff]loat|[Bb]ool|[Mm]aybe|[Mm]ap|produce)\b)\p{Ll}\w*+(?!\s*(?:of\b|as\b|<))
```

这些正则在本地样例上全部命中，在对应的正式写法上不误报，唯一已知的误报是字符串字面量里的 `"…the_x…"`（R5 / R5b）。生态仓库（aster-api / aster-cloud / template / truffle / guard / ai-workspace / aster-lang-test / ts / core 的 `.aster`，以及 Java / TS 源码里内嵌的 CNL）中，除本轮新增的必拒样本外只有已知误报（R1d 命中类型参数，R5b 命中一处字符串）。

命中的策略需要作者改写后重新发布：
- 第 1 项：字段名改成小写开头。
- 第 3 项：规则名改成小写开头。
- 第 5 项：去掉开头的 `_`。
- 第 5b 项：核对输入数据里的字段名。
- 第 7 项：写成 `y as <Type>`，或改成 `(Pair of Int), y`。

## 7. 未处理（另开 issue）

本轮核对中发现、不在 #334 范围内的分歧：

- **句中关键词的大小写**：ts 对所有关键词都不区分大小写（`MODULE m.`、`SET y to 2.`、`Rule f GIVEN x`），core 只认部分拼写。
- **裸小写类型名**：`given x as foo`、`given x as text`，ts 接受，core 拒绝。第 7 项只处理其中有歧义的 `X of A, y`。
- **小写泛型名**：`Pair of Int, foo of Int` 中的 `foo of Int`，core 接受，ts 报 E056。
- **`Map` 作类型参数**：`Pair of Int, map Int to Int`，ts 接受，core 拒绝。
