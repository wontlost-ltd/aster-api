# ADR 0056 修订 A5：eco-1.0.55 的解析分歧裁决（ts#380、core#298、块体前空行、语句内换行、ts#362 第 1–2 项）

- 状态：**ACCEPTED**（2026-11-22，产品负责人采纳 eco-1.0.55 Wave C 计划「待裁决」D1–D5 的推荐方案）
- 日期：2026-11-22
- 修订对象：[ADR 0056](0056-type-inference-parity.md)，接续[修订 A4](0056-amendment-4-parity-rulings-1053.md)；**D3 取代 A4 §2 第 2 项**（并更正该项的前提，见 §3.1）
- 相关：aster-cloud/aster-lang-ts#380、#357、#362；aster-cloud/aster-lang-core#298
- 实现落点：D1–D4 → `aster-lang-core`、`aster-lang-ts`、`aster-lang-test` 同名分支，随 **eco-1.0.55**（D1 分支 `fix/parity-380-alias-annotations`，其余分支名见实施 PR）；D5 只留档，无代码
- 同期另立：truffle#183（Int 64 位）的实现方案见 [ADR 0062](0062-int-64-bit-semantics.md)（PROPOSED），不在本修订

本修订的条目编号沿用 Wave C 计划的 D1–D5。所有「修复前」一栏都是 2026-11-22 在**已发布**产物上实测的结果：core / locales / hi 1.0.54、`@aster-cloud/aster-lang-ts` 3.6.0。自 aster-api#601 起 aster-api 不再有解析器副本，core 的改动在下一次 aster-api pin bump 时进入生产。

---

## 0. TL;DR

| # | 写法 | 修复前 core / ts | 裁决 | 收紧 / 放宽 |
|---|---|---|---|---|
| D1a | 声明注解写在类型别名前（`@pii(L1, email) Type Email as Text.`，zh `类型` 同） | 接受（注解无效果）/ E056 | 两引擎都报 **E056** | core 收紧 |
| D1b | 顶层声明行首缩进（`  Type …`、`  Define …`、`  Rule …`） | E063 / 接受 | 两引擎都报 **E063** `token.indent`，报在缩进处 | ts 收紧 |
| D2 | `the result is` / `Ergebnis ist` / `结果为` 之后的分隔符 | 任意 ASCII 空白（含 VT、FF、CR、LF）/ 只认空格、制表符（zh 加 U+3000） | 所有 locale 都是 **`[ \t]+`**（zh 另加 U+3000）；VT、FF、换行一律不收 | core 收紧 |
| D3 | 任何块头之后、块体第一行之前的空行 | 部分块头拒绝（见 §3） | 两引擎都**接受** | 放宽（两侧） |
| D4 | 语句内部位置不对的换行（`Let y be` ⏎、`Return g(` ⏎、`Return` ⏎） | E063 / E064 | 两引擎都报 **E063** `{token: token.newline}`，位置与 core 相同 | 只改 ts 诊断码 |
| D5 | ts#362 第 1–2 项：规范化中间文本里 ts 保留 「」 与 ‘’ | 内部表示不同，token / IR / 诊断相同 | **按设计关闭**，不改 | 无 |

---

## 1. D1：ts#380 声明注解与顶层缩进

### 1.1 D1a 类型别名前的声明注解

- **现状**：core 的 `typeDecl` 是 `annotation* typeAliasKeyword …`，AstBuilder 把注解挂到别名上，但参数被丢弃，lowering 也不用它：`@pii(L1, email) Type Email as Text.`、`Type Email as Text.`、`Type Email as @pii(L1, email) Text.` 三者 IR 的位置无关哈希完全相同。也就是说，core 接受了一个**静默无效**的注解，作者以为加了 `@pii`，实际没有。ts 的注解分支只接 Rule，报 E056 `Expected 'Rule' after declaration annotations`。
- **裁决**：两引擎都拒绝，用 ts 现在的 E056 与同样的参数，报在别名引导词上（位置以共享夹具为准）；提示语建议把注解写到字段或形参的类型上（`Type Email as @pii(L1, email) Text.` 这一写法照常接受，语义不变）。
- **理由**：静默无效比报错更危险，会掩盖缺失的 PII 标注；ts（编辑器、CLI、Workers 包检查）已经拒绝，生产里出现的可能很小。
- **落地**：core 从 `typeDecl` 去掉 `annotation*`，或在 AstBuilder 拒绝，二者择一，以诊断码与位置和 ts 一致为准。ts 不改代码，只补共享夹具。
- **回退方案**：若门禁扫描命中，改为两引擎都接受并都报警告，另行留档。

### 1.2 D1b 顶层声明缩进

- **现状**：core 对**所有**顶层声明（`Type`、`Define`、`Rule`，不只类型别名）行首缩进报 E063 `token.indent`；ts 的声明解析跳过 INDENT，全部接受。
- **裁决**：core 的行为为准。ts 在 INDENT 处报 E063 `{token: token.indent}`，与 core 同位置。
- **理由**：生产已经拒绝；只保留一条布局规则（顶层声明从第 1 列开始）。

## 2. D2：core#298 `result-is` 的分隔符

- **现状**：core `ResultIsTransformer` 的行首形式是 `^([ \t]*)The result is\s+`、行内形式是 `(,\s*)the result is\s+`（不区分大小写）。Java 的 `\s` 在没有 `UNICODE_CHARACTER_CLASS` 时只匹配 **ASCII** 空白：空格、制表符、LF、VT、FF、CR。zh 的 `结果为` 先翻译成 `the result is`，所以 zh 同样接受 VT、FF、LF、CRLF（包括 `结果为` 之后换行再写值）。ts 只认空格、制表符（zh 另加 U+3000），按 ts#348 的规则。
- **更正 core#298 的描述**：issue 说 core 还接受 U+0085、U+2028、U+2029，**不对**。这些字符、NBSP 与 U+3000 在 core 里两引擎都报 E050；真正的差异只在 VT、FF、CR、LF。
- **裁决**：所有 locale（en `the result is`、de `Ergebnis ist`、zh `结果为`）之后的分隔符都是 **`[ \t]+`**，zh 另加 U+3000；行内形式的前缀是 `,[ \t]*`。VT、FF、CR、LF 一律不收。
- **理由**：与 ts#348（ASCII 空格 / 制表符，zh 加 U+3000）和 A4 §2（语句头内的换行是错误）同一条规则，两引擎一个模式。`the result is` 之后换行属于偶然，不是有意的写法。`When 1,` ⏎ `the result is 2.` 这种分行写法不受影响：它走行首形式。
- **落地**：core `ResultIsTransformer` 改为 `^([ \t]*)The result is[ \t]+` 与 `(,[ \t]*)the result is[ \t]+`。locales 的 `ChineseResultIsTransformer` 已经是严格的，不改。ts 侧 en-US、de-DE 目前根本没有 `result-is` 与 `english-possessive` 转换器（Wave C 计划 N1，单独修，属于 ts 放宽），补的时候直接采用本条的分隔符。
- **夹具**：`shared/result_is_parity.json` 按字符逐行覆盖（制表符、U+3000、VT、FF、LF、CRLF、U+0085、U+2028、NBSP）× en / de / zh；tier1-reject `reject-result-is-newline{,-zh,-de}`。

## 3. D3：块体之前的空行

### 3.1 更正 A4 §2 第 2 项的前提

A4 §2 第 2 项写道：「Match / When / lambda / workflow 的块头两引擎此前都不收空行，维持原状」。2026-11-22 在已发布产物上复核，这句话**对 lambda、When 块与 Step 不成立**：ts 接受，core 拒绝，是 A4 时已经存在、而 A4 没有看到的单侧分歧。

| 块头（之后紧跟一个空行） | core | ts |
|---|---|---|
| 规则体、If / Otherwise | 接受（A4 已落地） | 接受 |
| lambda：`Let g be function …, produce Int:` | E063 | **接受** |
| `When 1,` 的块体 | E061 | **接受** |
| `Step a:`（Compensate 同一文法形态） | E061 | **接受** |
| `Match x:` | E061 | E057 |
| `Workflow:`（Retry 同一文法形态） | E061 | E056 |

### 3.2 裁决

**任何块头之后、块体第一行之前的空行，两引擎都接受**：空行不影响缩进。不再按块头列清单。本条**取代** A4 §2 第 2 项。

- core：上表各块头的文法由 `NEWLINE` 改为 `NEWLINE+`（Workflow、Step、Compensate、Retry、Match、When、lambda）。
- ts：Match 与 Workflow 在检查 INDENT 之前先跳过换行，与其他块头一致。
- **理由**：规则一条，比逐个块头列举简单；两侧都是放宽，不会让任何已能编译的源码失败。

## 4. D4：语句内部位置不对的换行

A4 §2 第 3 项只管**声明头**里的换行（E062）。语句内部的换行不在其字面范围内，今天两引擎接受与否相同，只有诊断码不同：

| 写法 | core | ts |
|---|---|---|
| `Let y be` ⏎ `x.` | E063，报在换行处 | E064，同位置 |
| `Return g(` ⏎ `x)` | E063，报在换行处 | E064，同位置 |
| `Return` ⏎ `x.` | E063，4:3 | E064，4:9 |

**裁决**：ts 改用 core 的 **E063** `{token: token.newline}`，位置与 core 相同（包括 `Return` ⏎ 一行报在 4:3）。只改 ts 的诊断码与列号，接受范围不变。共享夹具 `shared/parse_diagnostics_parity.json` 锁定码与位置。

## 5. D5：ts#362 第 1–2 项按设计关闭

ts#362 第 1 项（zh 规范化文本里 ts 保留 「」、Java 换成 `"`）与第 2 项（字符串外的 ‘ ’ ts 不变、Java 换成 `'`）只是引擎内部的中间文本不同；token、IR 与诊断两引擎一致（IR parity 全部相同）。两引擎之间的 parity 契约是 token、IR 与诊断，规范化文本不在其内，也没有用户界面展示 Java 的规范化文本；ts#339（列号按原始源码，A4 §3.1）落地之后，也没有任何位置再依赖它。

**裁决**：按设计关闭，不改代码。若 ts#339 的实现需要两侧规范化文本一致，在那里再议。（第 3、4 项另有处理；第 5 项已由 ADR 0060 修订 1 R18 裁决。）

---

## 6. 门禁扫描

收紧项在合并 core / ts 的实现 PR 之前，由负责人用 `aster-gate-scan` 跑扫描（只读导出生产策略源码，结果只报 id、码与 IR 路径，不打印源码，导出用后删除）。每条正则规则先在语料与一份合成源码上自测能命中。下表的正则是 Wave C 计划给出的**草案**，最终版本与扫描结果写在各实现 PR 里。

| 裁决 | 方向 | ts scan | java-gate | 正则规则（草案） | 预期 |
|---|---|---|---|---|---|
| D1a | core 收紧 | 否（ts 不变） | **是** | `(?m)^[ \t\x{3000}]*(?:@[\p{L}_][\p{L}\p{N}_]*(?:\([^)\n]*\))?[ \t\x{3000}]*)+(?:(?i:type)\|类型\|Typ\|प्रकार)[ \t\x{3000}]` | 0 命中、`newly-failing` 0；有命中按 §1.1 回退方案处理 |
| D1b | ts 收紧 | **是** | 否 | `(?m)^[ \t\x{3000}]+(?:@\S+[ \t]+)*(?:Module\|Rule\|Define\|Type\|Use\|模块\|规则\|定义\|类型\|引用\|Modul\|Regel\|Definiere\|Typ\|verwende\|मॉड्यूल\|नियम\|परिभाषित\|प्रकार\|उपयोग)(?![\p{L}\p{N}_])`（块体语句不会以这些词开头） | 0 命中（生产已由 core 拒绝） |
| D2 | core 收紧（en、de、zh） | 是 | **是** | `(?i)(?:the result is\|ergebnis ist)[ \t]*[\x0B\x0C\r\n]`；`结果为[ \t\x{3000}]*[\x0B\x0C\r\n]`；`(?i),[ \t]*[\x0B\x0C][\s]*the result is` | 三条都 0 命中、`newly-failing` 0 |
| D3 | 两侧放宽 | 可选 | 可选 | 无 | 只有 `newly-passing` |
| D4 | 只改 ts 诊断码 | 是 | 否 | 无 | 只有 `changed-codes`（E064 → E063），`newly-failing` 0 |
| D5 | 无变化 | 否 | 否 | 无 | — |

D2 第三条只查逗号与短语之间的 VT / FF，不查 CR / LF：逗号之后换行、下一行以 `the result is` 开头的写法（`When 1,` ⏎ `the result is 2.`）由行首形式 `^([ \t]*)The result is` 匹配，收紧前后都接受，不会变成失败；只有夹在中间的 VT / FF 会让两种形式都匹配不上。短语**之后**的 CR / LF 由第一、二条覆盖。

表中正则里的 `\|` 是 Markdown 表格转义，实际规则写 `|`。D1–D4 都不改关键词、别名与字面量触发词，不需要 vocab-check 与 `--aliases`。

## 7. 落地

- ADR 修订先合（本文件）。实现随 eco-1.0.55：core（D1a、D2、D3）与 ts（D1b、D3、D4）同名分支并行，aster-lang-test 最后合（tier1 正例 / 负例与共享夹具，夹具在 ts `shared/` 与 core `src/test/resources/diagnostics/` 两处逐字节相同）；platform pin 1.0.55 之后跑 §6 的扫描，通过后再 bump aster-api 的 pin。
- 每个实现 PR 都把 Wave C 计划里对应的复现行（`both.sh` 的 `ONE-SIDED` / `DIFF`）翻成 `SAME` 或码相同的 `both-fail`。
