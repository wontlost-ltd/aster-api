# ADR 0056 修订 A8：D4 之后余下的一侧接受（for / one / of / function / performs 作名字、限定名里的小写关键词段、`H.not(x)`；core 独有的 `and` / `has` 写法）

- 状态：**ACCEPTED**（2026-11-28，产品负责人）
- 日期：2026-11-28
- 修订对象：[ADR 0056](0056-type-inference-parity.md)，接续[修订 A7](0056-amendment-7-wave-d-rulings-1056.md)；第 1 条扩展 A7 D4 的范围，并取代 D4 实现里「成员调用 `v.not(x)` 照旧」的例外
- 相关：aster-cloud/aster-lang-ts#338（第 1 部分，关键词大小写 K）、#411、#414（D4）、#418（本修订第 1 条）；aster-cloud/aster-lang-test#276
- 实现落点：
  - 第 1 条：`aster-lang-ts`、`aster-lang-test` 同名分支 `fix/one-sided-keyword-names`
  - 第 2 条：`aster-lang-ts` 分支 `fix/411-keyword-case-ts-positions`（与 #411 的大小写部分同一分支）

两条裁决都只改 ts。生产编译走 core，core 的行为不变。

---

## 0. TL;DR

| # | 写法 | 现状 core / ts | 裁决 | 收紧 / 放宽 |
|---|---|---|---|---|
| 1a | `for`、`one`、`of`、`function`、`performs` 作名字（形参、字段、Let 名、成员名等，D4 的全部位置） | 语法错误 / 接受 | ts **拒绝**：E055 + 转义提示（`` `of` ``） | ts 收紧 |
| 1b | `Use io.to as H.`、`Module a.of.`：Module / Use 限定名的段是小写关键词 | 语法错误（`and` / `or` / `not` / `with` 除外）/ 接受 | ts **拒绝**同一批段：E055 + 转义提示 | ts 收紧 |
| 1c | `H.not(x)`、`v.not(1)`：点之后的 `not`（以及 `set`） | 点是语句结尾 / 成员调用 | ts **照 core 读**：`Return v.not(1).` = `Return v.` + `not(1).`，Core IR 相同；`H.not(x)` 两引擎都报 E802 | ts 改变含义（与 core 对齐） |
| 2 | 变体之间的 `and`、lambda 形参之间的 `and`、字段列表里换行起头的 `and`、`has` 构造（`S has p set to 1`） | 接受 / 拒绝 | ts **接受** | ts 放宽 |

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

## 3. 仍未裁决的一侧差异（记录）

第 1 条的实现探针同时记下了以下差异，不在本修订范围：

- 首字母大写的 core 记号 `Set` / `Use` / `On` / `It` / `Map` 在成员位置：core 同样把点读作语句结尾（`Return v.Set y to 2.` core 收、ts 拒；`Return v.Set.` 反之）。
- 裸表达式语句（`v.`、`1.`、`ok of 1.` 单独成句）：core 降为 `Let _ be …`，ts 报 lowerStmt 错误（第 1.3 条只处理 `.not` / `.set` 拆出的那一种）。
