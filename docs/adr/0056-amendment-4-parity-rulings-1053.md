# ADR 0056 修订 A4：eco-1.0.53 的解析分歧裁决（ts#338、ts#357）与两项延后裁决（ts#339 列号、truffle#183 Int 宽度）

- 状态：**ACCEPTED**（2026-11-20，产品负责人采纳 eco-1.0.53 Wave B 计划「待裁决」第 2、3、5、6 项的推荐方案）
- 日期：2026-11-20
- 修订对象：[ADR 0056](0056-type-inference-parity.md)，接续[修订 A3](0056-amendment-3-parity-declaration-syntax.md) §7 列出的未处理分歧
- 相关：aster-cloud/aster-lang-ts#338、#357、#339；aster-cloud/aster-lang-truffle#183
- 被修订：[修订 A5](0056-amendment-5-parity-rulings-1055.md)（ACCEPTED，2026-11-22）D3 **取代**本文 §2 第 2 项，并更正其中「Match / When / lambda / workflow 两引擎此前都不收空行」的前提（lambda、When 块与 Step 当时已是 ts 接受、core 拒绝）；§3.2 的实现方案见 [ADR 0062](0062-int-64-bit-semantics.md)（ACCEPTED）
- 实现落点：第 1、2 节 → `aster-lang-core`、`aster-lang-ts`、`aster-lang-test` 同名分支 `fix/parity-sweep-1053`（随 eco-1.0.53；§1.1 的 core 一侧工作量较大，另行跟进，见 aster-lang-ts#338）；第 3 节 → 1.0.54 或之后，另立计划

---

## 1. ts#338：关键词大小写、裸小写类型名、歧义逗号

| # | 写法 | 修复前 core / ts | 裁决 |
|---|---|---|---|
| 1 | 任意关键词的任意大小写（`MODULE m.`、`Rule f GIVEN x AS Int, PRODUCE Int:`、`Return x TIMES 2.`、`Return TRUE.`、`x IS GREATER THAN 1`） | 只认部分拼写 / 一律不区分大小写 | 两引擎都**不区分大小写**（core 放宽），见 §1.1 |
| 2 | 裸小写类型名（`given x as text`、`given x as foo`） | 拒绝 / 接受（`foo` 报 E800） | 两引擎都在名字上报 **E055** `Expected type name`（ts 收紧），见 §1.2 |
| 3 | `Pair of Int, foo of Int`（逗号之后的小写泛型） | 读成 Pair 的第二个类型参数（名称解析报 E800）/ E056 | 两引擎都在 `foo` 上报 **E055**（core 收紧），见 §1.3 |
| 4 | `Pair of Int, map Int to Int`（逗号之后的小写 map） | 拒绝（小写 `map` 在 core 不是关键词）/ 读成第二个类型参数 | 两引擎都在 `map` 上报 **E055**（ts 收紧），见 §1.3 |

### 1.1 关键词大小写（第 1 项）

A2 §2、A3 §2 已对 `type` / `use` 按「不区分大小写、不升为硬关键词」处理。本修订把同一原则推到全部关键词：

- 关键词位置上，名字记号（`TYPE_IDENT` / `IDENT`）的文本按 `Locale.ROOT` 小写后等于该位置的关键词拼写即视为该关键词。core 在这些位置用语义谓词判定，**不**把大写拼写升为硬关键词：`Define Given with major as Int.`、`given v as Version` 这类以关键词同形词作名字的写法照常可用（ts 同样如此）。
- 范围：单词关键词、`true` / `false` / `null`、单词运算符（`times`、`plus`、`minus`、`modulo`）、多词运算符（`is greater than` 等）与 `Module` / `It performs` 等句首关键词。
- 非英语词法表：core 的关键词翻译本来就不区分大小写，zh-CN、hi-IN 没有大小写，结果不变。

这是 core 的放宽，不需要门禁扫描。core 的实现要逐处改写关键词位置的文法（约 40 个关键词记号），与 §1.2–§2 分开落地。

### 1.2 裸小写类型名（第 2 项）

类型名以大写字母开头，与 A2 第 2 项「形参名不以 A–Z 开头」互为对照。类型位置上，以小写拉丁字母（`a`–`z`）开头、其后不是 `of` / `<` 的名字报 E055（`{token: token.typeName}`），报在该名字上。不受影响的写法：

- 带参数的类型构造：`list of Int`、`option of Text`、`result of Int`、`map Text to Int`、`maybe Text`；
- 无大小写文字（汉字、天城文）与 Latin 扩展的类型名；
- 本地化类型关键词还原出的名字。

### 1.3 歧义逗号（第 3、4 项）

与 A1（括号类型表里的歧义逗号 E064）、A3 第 7 项（`X of A, y`）同一原则：可变元数的 `X of A` 之后，分隔符（`,`，以及 A3 第 7 项同样处理的 `and`）既可以分隔类型参数，也可以分隔形参或字段。下一项以小写拉丁字母开头时，它也可能是下一个形参 / 字段的名字，两种读法都说得通，不靠猜，直接报错：

- 第 3 项：小写名字后跟 `of`（`foo of Int`）。内建的 `list of` / `option of` / `result of` 不在此列，照旧是类型参数。
- 第 4 项：小写 `map … to …`。大写 `Map … to …` 不可能是形参名（形参名小写开头，A2 第 2 项），照旧是类型参数。

两引擎都在该名字上报 E055，参数与 A3 第 7 项相同。以下照常接受：`Pair of Int, Text`、`Pair of Int, Map Int to Int`、`Pair of Int, list of Int`、`(Pair of Int), m as map Int to Int`、`m as map Int to Int`（不在 `X of` 参数里的小写 `map` 按 §1.1 是 Map 类型）。

### 1.4 门禁扫描

第 3 项让 core 上原本能编译的源码失败；第 2、4 项让 ts（编辑器、CLI、Workers 包检查）上原本能编译的源码失败。合并前由负责人跑门禁扫描：java-gate（第 3 项）与 ts scan（第 2、4 项）。具体正则与预期见实现 PR。

## 2. ts#357：换行

| # | 写法 | 修复前 core / ts | 裁决 |
|---|---|---|---|
| 1 | 字符串字面量里的原始换行 | E051 / 接受 | 两引擎都报 **E051**（ts 收紧）；多行文本须写转义 `\n` |
| 2 | 块头 `:` 之后紧跟空行 | 报错 / 接受 | 两引擎都**接受**（core 放宽）：空行不影响缩进。适用于 ts 已接受的块头（规则体、If / Otherwise）；Match / When / lambda / workflow 的块头两引擎此前都不收空行，维持原状 |
| 3 | 声明头里位置不对的换行（如 `Rule f given x as Int,` 换行后写 `produce Int:`） | E062 / E055 | 两引擎都报 **E062**（ts 改用 core 的码），报在换行处 |

第 1 项要跑 ts scan（字符串跨行）；第 2 项是放宽；第 3 项只改诊断码。具体正则见实现 PR。

> 第 2 项已由[修订 A5](0056-amendment-5-parity-rulings-1055.md) D3 取代（2026-11-22）：任何块头之后、块体第一行之前的空行两引擎都接受。本表第 2 项关于 Match / When / lambda / workflow 的说法不准确，以 A5 §3.1 的实测表为准。语句内部（声明头之外）的换行见 A5 D4。

## 3. 延后实现的两项裁决

### 3.1 Core IR 列号约定（ts#339）

- **约定**：列号从 1 起算，单位是 UTF-16 码元，坐标是**原始源码**（不是规范化之后的文本）。与 ts 及 LSP 的约定相同。
- **适用范围**：Core IR `origin.*.col`，以及两引擎的诊断位置。
- **实现**（1.0.54 或之后）：core 的规范化器建立「规范化文本 → 原始源码」的偏移映射，Core IR 与诊断的列号经它换算回原始源码。aster-lang-test 的 `ir --full` 改为比较列号。这也修正 zh-CN / de-DE / hi-IN 在 aster-api 响应里错位的诊断列号（今天报的是规范化后英文文本的列）。
- **影响**：所有 Core IR 的列号会变，`positionFreeSha256` 不变；ADR 0059 包里的 `coreIr.sha256` 在重新导出时变化，已导出的包自洽不受影响。上线前跑 java-gate，比较位置无关哈希。

### 3.2 Int 宽度与溢出（truffle#183）

- **语义**：`Int` 是 64 位有符号整数，溢出即**运行时错误**（fail-closed）：不环绕，不静默提升为别的类型。
- **两引擎**：Truffle 的 Int 运算改用精确运算（`Math.multiplyExact` 等）；`toInt` 一类 builtin 越界时报错，不截断。ts 的 Int 运算结果超出 ±2⁵³ 时报错，或改为 BigInt 实现；具体选哪一种随实现计划定。
- **验收**：tier1 eval 门补 int32、2⁵³、2⁶³ 三个边界的 case。
- **实现方案**：见 [ADR 0062](0062-int-64-bit-semantics.md)（ACCEPTED，2026-11-22）。
- **实现**：1.0.54 或之后，另立计划。这是**求值**行为的变化（Truffle 是生产求值器），上线前须扫描生产执行记录中超出 int32 的整数结果。
