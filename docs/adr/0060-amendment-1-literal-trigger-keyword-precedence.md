# ADR 0060 修订 1：字面量触发词与关键词同形时关键词优先；字面量内容放开 `\`、`"` 与弯引号

- 状态：**ACCEPTED**（2026-11-20，产品负责人采纳 eco-1.0.53 Wave B 计划「待裁决」第 1、7 项的推荐方案）
- 日期：2026-11-20
- 修订对象：[ADR 0060](0060-cjk-keyword-aliases-and-policy-embedded-vocabulary.md) §4.2 T3 与 T8、§11 最后一行、§12 R16（「另开 issue 统一」改为本修订的规则）；§6.4 的字面量内容规则
- 相关：aster-cloud/aster-lang-core#290、aster-cloud/aster-lang-ts#368（F1）；aster-cloud/aster-lang-ts#362 第 5 项；ts#345 / ts#361 / core#287（字面量宏值的转义）
- 实现落点：`aster-lang-core`、`aster-lang-ts`、`aster-lang-test` 同名分支 `fix/adr0060-f1-literal-keyword`（R16'）与 `fix/vocab-literal-value-chars`（R18）

---

## 1. 裁决

| # | 裁决 |
|---|---|
| R16'（取代 R16） | 字面量宏触发词与关键词同形时**关键词优先**，按数据规则实现，不调换两引擎的处理顺序（§2） |
| R18 | 字面量宏的内容允许 `\`、`"` 与弯引号 `“ ” ‘ ’`；`「」『』«»`、控制字符与空内容照旧拒绝（§3） |

## 2. R16'：关键词优先（core#290 / ts#368）

### 2.1 现状

| 触发词 | locale | core | ts |
|---|---|---|---|
| `文本`（类型的规范拼写） | zh-CN | 关键词优先，编译通过 | 先展开成字符串，E055 |
| `返回`（语句关键词） | zh-CN | 关键词优先，编译通过 | E064 |
| `Text` | en-US | 先展开成字符串，失败 | 同 core |
| `Return`（英文规范拼写，词汇在 zh-CN） | zh-CN | 关键词先译成 `Return` 再被展开，失败 | 不展开，编译通过 |

core 并不是处处「关键词优先」：en-US 没有关键词翻译这一步，所以 en 是字面量优先；zh 的 `返回` 先译成 `Return`，于是触发词 `Return` 又会把译出的关键词展开掉。两引擎之间、core 的各 locale 之间都不一致。

### 2.2 规则

建作用域时，丢弃（不报错）折叠键（ADR 0051 §6.3 `foldKey`）等于下列任一写法的字面量触发词：

1. 当前词法表（叠加本版本 aliasSet 之后的有效词法表）每个 kind 的规范拼写；
2. 当前词法表的官方别名；
3. 本版本 aliasSet 中的别名；
4. en-US 词法表的规范拼写。core 把非英语关键词译成这些拼写之后才展开字面量，ts 在 zh-CN 等词法表下也把英文拼写当关键词读，所以它们在所有 locale 都是关键词。

比较的是**整个拼写**。多词关键词（`greater than`）中的单个词不在其内；触发词只匹配单个标识符 token，这类情形两引擎今天结果一致，不改。

被丢弃的触发词在源码里照常按关键词读；其余触发词照常展开。规则写在两引擎的 `VocabularyScopes` 上（core `VocabularyScopes#literalsVisibleUnder(Lexicon)`，ts `literalsVisibleUnder(scopes, lexicon)`），由规范化器在构造时取用：

- 规则不改变 `VocabularyScopes.build` 的签名与 `canonicalJson()`。aster-api `ReplayExecutionCore.buildVocabularyScopes`、core `InProcessCnlParser.parse(source, locale, scopes)` 等入口在知道 aliasSet 之前就建好作用域，有效词法表要到解析器里才确定；放在规范化器取用处，所有入口（含 aster-api 自己的解析器副本、ts `compile()` 直调、LSP 规范预览）在 pin bump 时一起生效，无需改调用方。
- envelope 第 5 段 `scopesJson` 不变：它记录的是编译输入，触发词照旧在内；同一输入在两引擎得到同一 IR。

### 2.3 保留的登记期拒绝

- 别名校验器 v2（core `UserAliasValidator`、ts `alias-validate.ts`、cloud）照旧拒绝「别名 = 字面量触发词」（§5.3）。
- cloud 内嵌词汇照旧拒绝 `embedded_vocabulary_shadows_keyword`（§6.4）。

这两条让作者在保存时就知道词条无效；R16' 只是让绕过校验的入口（冻结词汇 JSON、`compile()` 直调、aliasSet 为空的分支）在两引擎得到同一结果。

### 2.4 门禁扫描（合并前必需）

这是行为变化：命中的策略此前在 en-US 编译失败或得到字符串，在 zh-CN 的 core 侧与 ts 侧结果互异；修订后两引擎都按关键词读。IR 会变、诊断不一定变，所以除导出扫描外须跑 java-gate 补充（新旧 core 比较 Core IR）。

扫描对象：生产全部策略版本的有效词汇（冻结快照、内嵌词汇、作者 / 团队词汇）里 `kind = literal` 的 `localized` 与 `aliases`，按策略的 locale 与该版本的 aliasSet 判定 §2.2 四类写法。具体的正则与预期见实现 PR。预期 0 命中：cloud 内嵌词汇已在保存时拒绝同形触发词（§6.4），用户 / 团队词汇没有字面量（R15）。

## 3. R18：字面量内容的字符（ts#362 第 5 项）

两引擎的词汇校验器（core `IdentifierMapping.isValidLiteralContent`、ts `validateVocabulary`）此前拒绝 `"`、`\` 与 `「」『』«»“”‘’`。

- `\` 与 `"`：ts#361 / core#287 起展开时转义（`\` → `\\`，`"` → `\"`），词法器读回的就是原值。拒绝已无必要。
- `“ ” ‘ ’`：自 ts#347 起规范化器只改写**充当字符串定界符**的弯引号，字符串内容原样保留；展开发生在引号归一之后，展开出的字符串里的弯引号是内容，重新规范化也不变（幂等）。
- `「」『』«»` 照旧拒绝：它们是 zh-CN / 其他词法表的字符串定界符，词法器没有对应的转义（`\」` 不是合法转义），展开期的 `isSafeLiteralContent` / `isExpandableLiteralContent` 本来也拒绝。控制字符与空内容照旧拒绝。

这是放宽，不需要门禁扫描。§4.2 T8「字面量内容单行、无引号与反斜杠」改读为「字面量内容单行、无控制字符，引号与反斜杠在展开时转义」：内嵌词汇仍不能写出源码本身写不出的东西。cloud 的 `embedded_vocabulary_invalid` 调用引擎 `validateVocabulary`，随 ts 升级自动放宽。
