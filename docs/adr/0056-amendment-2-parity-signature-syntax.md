# ADR 0056 修订 A2：签名与类型别名语法的双引擎裁决（aster-lang-ts#328）

- 状态：**PROPOSED**（裁决经用户确认；落地后改 ACCEPTED）
- 日期：2026-11-16
- 修订对象：[ADR 0056](0056-type-inference-parity.md)，接续[修订 A1](0056-amendment-1-parity-sweep-1048.md) §5 留下的分歧
- 相关：aster-lang-ts#328
- 实现落点：`aster-lang-core`、`aster-lang-ts`、`aster-lang-test` 同名分支 `fix/parity328`

---

## 1. 裁决

| # | 写法 | 修复前 core / ts | 裁决 | 诊断 |
|---|---|---|---|---|
| 1 | `given x as Pair<Int, Text>`（尖括号泛型，任何类型位置） | 接受 / E056 | 两引擎都拒绝；正式写法 `Pair of Int and Text` | **E056** `Expected 'of' after type name …`，报在 `<` 上 |
| 2 | `given Customer as Text`；`list of Int and Text`（core 曾把 Text 读成无类型形参名） | 接受 / E055 | 两引擎都拒绝：形参名不能以 A–Z 开头（与 ts 词法的 TYPE_IDENT 判据一致；CJK、Latin 扩展字母、本地化关键词还原出的名字不受影响） | **E055** `Expected identifier`，报在名字上 |
| 3 | `given x as Pair of Int, @pii(L1, email) y as Text`（形参前置注解） | 接受 / E056 | 两引擎都接受。`Param.annotations` 先列前置注解，再列类型最外层的 `@pii`；字段类型最外层的 `@pii` 同样记入 `Field.annotations`（core 一直如此，ts 此前为 `[]`） | — |
| 4 | `given x as result of Int or Text` | 拒绝 / 接受 | 两引擎都拒绝：失败类型只用 `and` / `,` 引出 | **E062**，报在 `or` 上 |
| 5 | `Type Amount as Int.`（首字母大写的别名关键词） | 拒绝 / 接受 | 两引擎都接受，见 §2 | — |
| 6 | `Maybe @pii(L1, name) Text`（前缀可空） | 拒绝 / 接受 | 两引擎都接受，`maybe T` ≡ `T?`；`maybe of T` 两引擎都拒绝 | `maybe of`：**E061** `Unexpected 'of' …`，报在 `of` 上 |

## 2. 一致性原则（第 5、6 项）

**关键词大小写。** 两个词法器对声明关键词都不区分大小写：ts 一律按小写比较关键词；core 的 `Define` / `Rule` / `Let` / `Return` / `If` 等用逐字母字符集，`Use` / `Set` 接受首字母大写和全小写两种。唯独 `type` 在 core 只认小写，而 ts 格式化器输出的正是 `Type X as …`。所以 core 改为接受：在 `typeDecl` 句首用语义谓词判定 `TYPE_IDENT` 的文本等于 `type`（不区分大小写），**不**把 `Type` 升为硬关键词，`Type` 作 Data 名、字段类型或形参类型时照旧可用（ts 同样如此）。

**`maybe`。** `MAYBE` 是两引擎词法表共有的类型构造关键词（`SemanticTokenKind` 的 `typeConstruct` 组，与 `OPTION_OF`、`RESULT_OF` 并列），四种语言都有译文（maybe / vielleicht / 可选 / शायद），但 core 文法从没有消费它的规则。`option of` 和 `result of` 两引擎都支持，所以按同组关键词的处理补齐 core：类型位置的 `maybe`（按文本判定，不夺走 `maybe` 作名字的用法）后接一个可带注解的类型。`maybe` 的关键词写法里没有 `of`，因此 `maybe of T` 两引擎都报 E061；core 此前把它读成名为 `maybe` 的泛型 `TypeApp`。

ts 格式化器随之修正：只有内层是名字（或另一个 `?`）时才输出 `T?`，其余输出 `maybe T`。此前 `Maybe @pii(…) Text` 会被打印成 `@pii(…) Text?`（注解包到了 Maybe 外面），`maybe list of Text` 会被打印成 `list of Text?`，两者都静默改变了含义。

## 3. 门禁扫描（发版前必需）

第 1、2、6 项会让 core 上原本能编译的源码失败。第 4 项只收紧 ts，core 从未接受这种写法，但 ts 编辑器里保存的草稿可能含有它。权威检查是列车门禁扫描：导出生产策略源码，用 aster-lang-platform `tools/gate-scan` 分别以新旧 core 编译，新出现的失败即为命中。下面的正则只用来预筛 en-US 源码，会有误报；本地化源码以门禁扫描为准。

```
# 1 尖括号泛型（规则头 / 字段 / 别名 / lambda 形参行）
^\s*(?:@\S+\s+)*(?:Rule|Define|[Tt][Yy][Pp][Ee])\b.*\b\w+\s*<|function\s+(?:given|with)\b.*\b\w+\s*<
# 2a 大写形参名（Rule / function 行内）
\b(?:given|with)\s+(?:@\S+\s+)*[A-Z]\w*\b|(?:,|\band)\s+(?:@\S+\s+)*[A-Z]\w*\s+as\b
# 2b list / option 之后被读成形参名的大写名字
\b[Ll]ist\s+of\s+\S+\s+and\s+[A-Z]\w*\s*(?:,|:|\.|$)|\b[Oo]ption\s+of\s+\S+\s+and\s+[A-Z]\w*\s*(?:,|:|\.|$)
# 4 result … or（仅 ts 草稿）
(?i)\bresult\s+of\b[^,.:\n]*\bor\b
# 6 maybe of
(?i)\bmaybe\s+of\b
```

命中的策略需要作者改写后重新发布：1 改为 `X of A and B`，2 把形参名改为小写开头，4 改为 `and`，6 改为 `maybe T` 或 `T?`。

## 4. 未处理（另开 issue）

本轮核对中发现的其余分歧：字段名大写（core 接受，ts E055）；字段前置注解（core 接受，ts E055）；规则名大写（core 接受，ts E055）；`USE` 全大写（ts 接受，core 拒绝）；以下划线开头的标识符（core 接受，ts 词法 E050）；`Let` 里内联的 lambda 体 `function …: Return x.`（core 接受，ts E055）；`given x as Pair of Int, y, produce`（core 拒绝，ts 把 `y` 读成类型参数）。
