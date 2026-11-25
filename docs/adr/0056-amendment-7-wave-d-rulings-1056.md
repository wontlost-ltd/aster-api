# ADR 0056 修订 A7：Wave D 裁决（eco-1.0.56 / 1.0.57 列车拆分、`True` / `Not(…)` 同形词、小写关键词作名字、转义开关、`Return if.` 与大写 Let 名）

- 状态：**ACCEPTED**（2026-11-26，产品负责人采纳 eco-1.0.56 Wave D 计划「待裁决」D1–D6 的推荐方案）
- 日期：2026-11-26
- 修订对象：[ADR 0056](0056-type-inference-parity.md)，接续[修订 A6](0056-amendment-6-keyword-homograph-names.md)；**更正 [A4](0056-amendment-4-parity-rulings-1053.md) §1.1 的「这是 core 的放宽，不需要门禁扫描」**：按 D2、D3，关键词大小写（下称 K）在 `True` / `False` / `Null` 与 `Not(…)` 两处改变今天 core 的结论或 IR，须门禁扫描（§7）
- 相关：aster-cloud/aster-lang-ts#338（第 1 部分，即 A4 §1.1）、#389；aster-cloud/aster-lang-core#312、#313、#314；aster-cloud/aster-lang-test#254；[ADR 0064](0064-name-escape.md)（名字转义，§3.1 N1a、§12、§14 Q2）、[ADR 0059 修订 5](0059-amendment-5-hidden-escape-delimiters.md)（L10）、[ADR 0058 修订 3](0058-amendment-3-editor-name-escape.md)（编辑器自动插入转义）
- 实现落点：
  - eco-1.0.56：ADR 0064 转义（E1–E3）、L10（E4）与遗留项，`aster-lang-locales` / `aster-lang-hi` → `aster-lang-core`、`aster-lang-ts` → `aster-lang-test` → `aster-lang-truffle`；D6 在 `aster-lang-ts`
  - 1.0.56 之后：aster-api pin bump 与 L10b 接线（E6），然后 cloud 编辑器（E5，D5 的开关）
  - eco-1.0.57：K（四个 PR：K-a 声明与句首、K-b 语句 / 表达式与类型、K-c 运算符与字面量、K-d workflow）、core#314、D2、D3（K-c）、D4（ts）

本修订的「现状」取自 2026-11-26 在 `origin/main` 上的探针：core `3dafdd7`（含 core#316 E901 / W802）、ts `267c9ba`（含 ts#392）；已发布的是 eco-1.0.55 / ts 3.7.0。下文 F 编号沿用 Wave D 计划 §1 的探针表。core 的改动在下一次 aster-api pin bump 时进入生产。

---

## 0. TL;DR

| # | 写法 / 事项 | 现状 core / ts | 裁决 | 收紧 / 放宽 | 列车 |
|---|---|---|---|---|---|
| D1 | 列车拆分 | — | **1.0.56** = 名字转义 E1–E4（含 L10）+ 遗留项（ts#389、F13 / F14、core#312、core#313 可选、test#254）；**1.0.57** = K（ts#338 第 1 部分）+ core#314 + D2、D3、D4 | — | — |
| D2 | `True` / `False` / `Null`（小写以外的任意大小写）作类型、枚举成员或字段名（F5） | 接受（枚举成员）/ E055（词法成 BOOL / NULL） | 两引擎都**拒绝**：E055 + 转义帮助行 | core 收紧 | 1.0.57（K-c） |
| D3 | `Not(x)` / `NOT (x)`（F4） | **调用函数 `Not` / `NOT`** / 一元 `not`——**今天就是一处 DIFF** | 两引擎都读作**一元 `not`** | core 改变含义 | 1.0.57（K-c） |
| D4 | 小写关键词作名字：`and`、`or`、`not`、`as`、`with`、`to`、`set`、`use`、`on`、`plus`、`minus`、`times`、`modulo`、`async`、`has`、`produce`；zh `并且` …（F12） | 语法错误 / 接受 | ts **拒绝**：E055 `{keyword}`，帮助行「改写为 `` `and` ``」 | ts 收紧 | 1.0.57 |
| D5 | cloud 编辑器的转义插入 | — | 留在开关 `authoring.name_escape.enabled` 之后，**在 aster-api 的 L10b 接线（E6）上线前保持关闭**，之后按环境打开 | — | 1.0.56 之后 |
| D6 | `Return if.`（形参名 `if`，F13）；大写开头的 Let 名，如 `Let Foo be x`、de `sei Betrag gleich x`（F14） | 接受 / E064、E055 | ts **接受** | ts 放宽 | 1.0.56 |

另记一条实现约束（§6）：zh 的全角 `｀`（U+FF40）**不得**成为转义定界符；两个规范化器今天都把它折叠成 `` ` ``，E1 必须把它排除在全角折叠之外。

---

## 1. D1：列车拆分

- **裁决**：
  - **eco-1.0.56**：ADR 0064 的转义词法、规范化不透明片段与解析器排除（E1）、别名 / 词汇 / 字面量触发词（E2）、格式化器与 LSP（E3）、L10（E4，ADR 0059 修订 5 §5 要求与转义同一列车）；遗留项 ts#389（ts 采用 core 的诊断码与位置）、D6、core#312（R1 / R2 平方复杂度）、core#313（W801 回退，可选）、test#254（深度夹具）。
  - **1.0.56 之后**：aster-api pin bump 与 `PackageLayoutGate` 的 L10b 校验回调（E6），然后 cloud 编辑器（E5），开关见 D5。
  - **eco-1.0.57**：K（A4 §1.1，ts#338 第 1 部分）分四个 PR 落地，core#314（小写 `map` 即 Map 类型，A4 §1.1 / §1.3 的同一裁决）并入 K-b，D2、D3 并入 K-c，D4 在 ts。
- **理由**：
  - E1 + E4 单独就涉及规范化器、两个词法器、三份 L 规则实现与两个 CLI，已是一整趟列车。
  - K 是全文法的改写，有性能风险，还带两处含义变化（D2、D3）。
  - 二者都改 `AsterParser.g4` 的成员；先后进行避免冲突。
  - K 落在转义之后，可以把转义作为每一处 K 冲突的出路：K 的关键词判定谓词就是 E1 的辅助函数，转义 token 在任何 K 位置都不是关键词（ADR 0064 N3）；新增的 E055 `{keyword}` 与 D3、D4 的帮助行都建议 `` `x` ``，LSP 快速修复（E3）适用。
- **K 的机制**沿用 A4 §1.1 的裁决：保留词法 token，每个关键词加一条辅助规则（如 `kwGiven : GIVEN | {isKwText(_input.LT(1),"given")}? (IDENT|TYPE_IDENT) ;`）；多词运算符的词法规则改为不区分大小写；`true` / `false` / `null` 只在字面量位置判定。TYPE / VERSION / REQUIRED / BETWEEN / GIVEN / BE / MODULE 留在 `structKeywordName`，大写开头的同形词照旧是名字。E901 仍按精确拼写判定（`If` → E901；`IF`、`iF`、`if` 维持今天的结果），K 不碰它。

## 2. D2：`True` / `False` / `Null` 作名字

- **现状**（F5）：`Define Flag as one of True, Other.` 加 `Return True.`（同理 `Null`、`Flag.False`）core 接受为枚举成员；ts 把 `True` 词法成 BOOL，报 E055 变体名。`Return TRUE.` core 报 E802、ts 接受为布尔（F6）。小写 `true` / `false` / `null` 在两引擎都是字面量，不在本条范围。
- **裁决**：小写以外任意大小写的 `True` / `False` / `Null` 作**声明的**类型名、枚举成员名或字段名时，两引擎都报 **E055**，帮助行「改写为 `` `True` ``」。表达式位置按 K 读作字面量。
- **理由**：ts 已经拒绝。若不拒绝，K 会把 core 里的 `Return True.` 从枚举成员**静默**变成布尔值——比报错更危险。转义后的 `` `True` `` 是名字，IR 名字仍是 `True`，迁移不改输入键。
- **落地**：core 收紧，随 K-c。ts 不改行为，只补共享夹具。
- **门禁**：RK1（§7），并跑 java-gate 与 ts scan。命中处理见 §8。

## 3. D3：`Not(x)` / `NOT (x)`

- **现状**（F4）：`Return NOT (x > 1).`、`Return Not(x).` 在 core 是**对函数 `NOT` / `Not` 的调用**，在 ts 是一元 `not`。这是**今天已经存在的 DIFF**，不是 K 引入的。
- **裁决**：两引擎都读作一元 `not`。确实要调用名为 `Not` 的函数时写 `` `Not`(x) ``。声明位置（如 `Rule Not given …`）是名字位置，K 不改变它（F8）；变化只在调用处。
- **理由**：ts 与 A4 §1.1 都把它读作关键词；core 的读法是今天唯一的例外，且两引擎对同一源码给出不同程序。
- **落地**：core 改变含义，随 K-c。ts 不改行为，只补共享夹具。
- **门禁**：RK2（§7），并跑 java-gate 与 ts scan；`ir-changed-*` 必须为 0 或逐条裁决。命中处理见 §8。

## 4. D4：小写关键词作名字

- **现状**（F12）：`and`、`with`、`as`、`to`、`produce`、`has` 等小写关键词作字段、形参、Let 名或成员时 core 报语法错误、ts 接受；zh `并且` 同。生产走 core，所以生产不受影响。
- **裁决**：ts 拒绝，报 **E055** `{keyword}`，帮助行「改写为 `` `and` ``」。范围：`and`、`or`、`not`、`as`、`with`、`to`、`set`、`use`、`on`、`plus`、`minus`、`times`、`modulo`、`async`、`has`、`produce`，以及各 locale 的对应拼写（zh `并且` …）。
- **理由**：core 已经拒绝；其中有的词本身就有歧义——`Rule f given x as Int, produce Int:` 里逗号之后的 `produce` 既可读作下一个形参名，也可读作返回类型引导词；转义上线后作者有出路。**未采纳**的备选：core 接受一个安全子集（`has`、`to`、`on`、`use`），需要逐词的文法工作。
- **落地**：ts 收紧，1.0.57。不能早于转义上线：转义是唯一出路。
- **门禁**：RK4（§7，仅用于 D4）+ ts scan。ts 侧新增的失败只应出现在 core 本来就拒绝的源码上。

## 5. D5：cloud 编辑器的转义开关

- **裁决**：cloud 编辑器的自动转义、快速修复与隐藏定界符（ADR 0058 修订 3）留在开关 `authoring.name_escape.enabled`（缺行 false）之后，**在 aster-api 的 L10b 接线（E6）上线前保持关闭**；E6 上线后按环境逐个打开。
- **理由**：锚先于使用（ADR 0064 §12.2）。E4 的失败封闭钩子让上线顺序无论如何都是安全的：没有 L10b 校验回调的调用方会拒绝任何带隐藏转义三元组的排版（aster-api 省略排版，原因 `display_rules_failed`；ts `validatePolicyLayout` 不带 `compileContext` 时报 `layout_hidden_escape_changes_program`）。开关关闭的作用是不让作者在这段时间里做出注定被拒绝的保存。
- **前提**：cloud 先升到 ts ≥ 3.8.0（今天固定在 3.6.0）。开关关闭时编辑器输出必须与今天逐字节相同（E5 的测试项）。

## 6. 全角 `｀` 不是转义定界符

- **探针**（F2）：zh `｀版本｀` 在两引擎都先被全角折叠成 `` `版本` ``——core `Canonicalizer.java:1438`（`FF01–FF5E − 0xFEE0`）、ts `canonicalizer.ts:695`（`[　！-～]`）——错误报在折叠后的 `` ` `` 上（core 第 15 列，ts E050 第 10 列）。
- **约束**：ADR 0064 Q2 已裁决「只用 ASCII 反引号 U+0060，全角 `｀` 保持 E050」（N1a）。在今天的折叠下，E1 一旦让反引号成为定界符，zh `｀版本｀` 就会**静默**变成合法转义，违反 Q2；若只修一侧，还会造成两引擎分歧。因此 E1 必须在**两个**规范化器的全角折叠中排除 U+FF40；`｀` 保持 E050，帮助行「名字转义用 ASCII `` ` ``」。
- **验证**：共享夹具 F1 含 `｀` 一行，两引擎同码同位置；门禁 G1a（§7）把 U+FF40 与 U+0060 一并列出。

## 7. 门禁扫描

按 `aster-gate-scan` 规则执行：只读导出，只打印 id / kind / lexicon / 规则名，不打印源码，用后删除导出。正则规则交给 `regex-scan.mjs`，每条先在语料与合成源（Wave D 探针 `c1.jsonl`、`c2.jsonl` 的 F4、F5、F12 源）上自测。

| 事项 | ts scan | java-gate | 正则 | 预期 |
|---|---|---|---|---|
| D2 | 是 | **是** | RK1 | 0，或按 §8 迁移 |
| D3 | 是 | **是** | RK2 | 0，或按 §8 迁移 |
| K 整体（含 D2、D3） | 是 | **是** | RK3（分诊预筛） | `ir-changed-*` 为 0 或逐条裁决 |
| D4 | **是**（ts 收紧） | 否（core 已拒绝） | RK4 | 新失败只在 core 已拒绝的源码上 |
| D6 | 是，只看 newly-passing | 否（core 不变） | — | 只有放宽 |
| E1 | 是（不变） | **是**（规范化器） | G1a、G1b | G1b = 0 |
| E4（L10） | 否 | 否（排版不是编译输入） | — | G5 = 0（`export --with-layouts` + `layout-check.mjs`） |

风险写法（PCRE，与 Wave D 计划 §3 逐字相同）：

```
# RK1 literal-homograph  非小写的 true / false / null 用作名字（D2）
(?<![\p{L}\p{M}\p{N}_])(?!(?:true|false|null)(?![\p{L}\p{M}\p{N}_]))(?i:true|false|null)(?![\p{L}\p{M}\p{N}_])
# RK2 not-call  非小写的 not 后接 (：今天 core 是调用，K 之后是 not 运算（D3）
(?<![\p{L}\p{M}\p{N}_.])(?!not[ \t]*\()(?i:not)[ \t]*\(
# RK3 capitalised-homograph  任何大写 / 全大写的关键词拼写（java-gate 分诊用的预筛）
(?<![\p{L}\p{M}\p{N}_.])(?=\p{Lu})(?i:given|with|and|or|produce|has|as|one|of|use|version|be|set|to|not|for|function|map|async|times|plus|minus|modulo|workflow|step|retry|timeout|depends|on|compensate|max|attempts|backoff|seconds?|required|between|matching|performs|module|is|less|greater|more|at|equals?|divided|integer)(?![\p{L}\p{M}\p{N}_])
# RK4 lowercase-kw-name  （仅 D4）列举的小写关键词用作形参 / 字段 / Let / 成员名（ts 收紧）
(?m)(?:(?:given|with|has|,|and)[ \t]+|^[ \t]*(?i:let)[ \t]+|\.)(?:and|or|not|as|with|to|set|use|on|plus|minus|times|modulo|async|has|produce)(?=[ \t]+(?:as|be)\b|[ \t]*[.,)])
# G1a any-backtick  任何位置的反引号或全角反引号（报告，按行分诊）
[\x{60}\x{FF40}]
# G1b backtick-in-code  "…" / 「…」 / “…” 字符串之外、# 注释之前的反引号
(?m)^(?:[^"「“#\x{60}\x{FF40}\n]|"(?:[^"\\\n]|\\.)*"|「[^」\n]*」|“[^”\n]*”)*[\x{60}\x{FF40}]
```

规则的覆盖范围：

- RK3 覆盖 K 改变的每一种非小写拼写。K 只在两处改变小写拼写：模块头的 `module` 与类型位置的 `map`（core#314）；这两处今天在 core 都是语法错误，不需要预筛，由 java-gate 覆盖。其余小写关键词今天已是硬 token，K 不改变。
- RK4 只用于 D4。
- 计划阶段已在探针源上用 `rg -P` 预检：RK1 命中全部五行 `True` / `Null` / `False`；RK2 命中 `NOT (`、`Not(` 与名为 `Not` 的规则；RK4 命中全部六行 F12、不命中任何 K 行；G1b 只命中两行代码里的反引号，不命中字符串与注释行。
- D6 不需要正则：生产（core）今天已接受这两种写法，ts 只是放宽。

## 8. 门禁命中时的处理

1. 扫描报告只给 id、kind、lexicon 与规则名，不打印源码。
2. **RK1 / RK2 / java-gate 的 `ir-changed-*`**：必须在 1.0.57 的 aster-api pin bump 之前为 0 或已迁移。迁移方式是转义：`True` → `` `True` ``、`Not(x)` → `` `Not`(x) ``。转义名的 IR 名字就是转义内容（ADR 0064 N4），所以迁移不改变 IR 与运行时输入键；仍须逐条与作者确认后改写。未迁移完之前，K-c 的 core 收紧（D2）与含义变化（D3）不进 pin。
3. **RK4 / ts scan 的新失败（D4）**：命中的源码在 core 本来就失败，生产行为不变；逐条确认后照常上线，作者在编辑器里得到 E055 与转义提示。
4. **RK3** 只是 java-gate 分诊的预筛，命中本身不阻塞；以 java-gate 的 IR 比较为准。

## 9. D6：`Return if.` 与大写开头的 Let 名

- **现状**：
  - F13：`Return if.`（形参名 `if`）core 接受、ts 报 E064。
  - F14：大写开头的 Let 名（`Let Foo be x`、de `sei Betrag gleich x`）core 接受、ts 报 E055。
- **裁决**：ts 接受两者。
  - F13：小写结构词在初等表达式位置、其后紧跟 `.` / `,` / `)` 时作名字。
  - F14：Let 名接受 TYPE_IDENT；E901 照旧作用于 `If` 等精确拼写。
- **理由**：生产（core）已接受；ADR 0019 G1；德语名词首字母大写，de 作者自然写 `sei Betrag`。**未采纳**的备选：core 拒绝 F14——那是生产收紧，de 很可能命中。
- **落地**：ts 放宽，1.0.56。
- **门禁**：ts scan，只看 newly-passing；不需要正则，java-gate 不跑（core 不变）。

## 10. 共享夹具

aster-lang-test：

- `parse_diagnostics_parity.json` 的「keyword-case」一节：D2、D3、D4 各位置的预期，K 的混合大小写样例；ts#389 的两行（`of` 之后换行 E062；`It` ⏎ `performs` E055 `{token: kw.performs}`）。
- `keyword_name_parity.json`：同形词行（保留 `Define Given with major as Int.`）；E901 行 `IF` / `iF` → E055 维持今天的结果。
- tier1 `keyword-case-{decl,stmt,ops,workflow}.aster`。
- **名字位置回归矩阵**（K-a 建立，此后每个 K PR 重跑）：`type`、`version`、`required`、`between`、`given`、`be`、`module` 各以小写、首字母大写、全大写出现在字段、形参、Let 名、成员 `s.x`、变量引用、类型别名名、枚举成员（大写形式）、导入别名位置，结果在 K 前后完全相同。
- D6：F13、F14 各一行（含 de `sei Betrag gleich x`），两引擎都接受。
- §6：F1 的 `｀` 一行，两引擎同为 E050、同位置。
