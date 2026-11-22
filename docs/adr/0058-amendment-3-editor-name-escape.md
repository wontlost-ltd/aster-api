# ADR 0058 修订 3：编辑器自动插入名字转义；结构 span 如何隐藏转义符

- 状态：**ACCEPTED**（2026-11-22，随 [ADR 0064](0064-name-escape.md) 一起经产品负责人采纳）
- 日期：2026-11-22
- 修订对象：[ADR 0058](0058-structured-authoring-capability-and-policy-layout.md) §7.4（怎样创建）、§7.5（保存规则表）、§7.7 第 3、4 条（防显示欺骗）、§8.3（编辑器）
- 来源：产品负责人 2026-11-22 裁决 Q1 的 (b)「结构化编写编辑器自动插入转义，本地化关键词照旧经结构 span 显示在英文规范关键词之上」与 Q3「显示层可以隐藏转义定界符」
- 相关：[ADR 0059 修订 5](0059-amendment-5-hidden-escape-delimiters.md)（L10，隐藏转义符的规则与论证）、[ADR 0058 修订 1](0058-amendment-1-reading-views-display-first.md)（R15 审批视图 canonical 优先；详情页默认显示排版）、[修订 2](0058-amendment-2-localized-type-alias-keyword.md)（§8.3 表单经 lexicon 生成源码）
- 实现落点：`aster-cloud`（`src/lib/structured-authoring/source-builders.ts`、`name-validation.ts`、Monaco 集成、排版视图与 span 编辑器）、`aster-lang-ts`（`./layout` 的 `validatePolicyLayout`、LSP 快速修复）；cloud 改动在 aster-api pin 支持转义之后上线，由开关 `authoring.name_escape.enabled`（缺行 false）控制

---

## 1. 裁决

| # | 裁决 |
|---|---|
| E1 | **表单自动转义**：模型标签页的「新建结构体」「添加字段」「新建类型别名」等表单（§8.3），在 `fieldNameRoundTrips` / `structNameRoundTrips` 因「该词在版本的 locale 与 aliasSet 下是关键词、别名、字面量触发词或冠词」而失败时，生成 `` `版本` `` 而不是拒绝；界面原因从「保留字」改为「已自动转义」并就地显示。因其他原因（非法字符、空白等）失败的照旧拒绝 |
| E2 | **快速修复**：Monaco 在 ADR 0064 §9 的关键词作名字错误、W802 静默改名警告与 E808 上提供「用作名字」，把该词改为转义形式；一次修复同一名字的全部出现 |
| E3 | **关键词一侧不变**：作者想让一个有歧义的本地化词读作关键词时，编辑器写 en-US 规范关键词（ADR 0060 修订 1 §2.2 第 4 项：它在所有 locale 都是关键词），并建一个结构 span，`display` 为本地化词。这是 §7.5 已允许的能力（guyong 演示 `是 → ，是那盏`），本修订把它写成推荐做法 |
| E4 | **排版从不携带 token 角色**：layout 格式（§7.3）不变，没有 role 字段；编译器、执行、证据、导出照旧不读 layout（§7.1、§7.7 第 5 条） |
| E5 | **反引号缺省可见**：转义整个留在内容 span 里，display 原样显示；排版视图可以把内容 span 中的反引号渲染为淡化样式——这是样式，不是隐藏，`toDisplay` 的文本里反引号仍在 |
| E6 | **可以隐藏一对转义符**：span 编辑器在选中一个转义名时提供「隐藏转义符」，生成 [ADR 0059 修订 5](0059-amendment-5-hidden-escape-delimiters.md) L10a 规定的**转义三元组**（两个 `` {canonical:"`",display:""} `` 结构 span 夹一个内容 span），并当场跑 L10 预检，不通过则显示原因且不应用。不提供把反引号与其他字合进一个结构 span、或给反引号换显示文字的操作 |
| E7 | **被隐藏的转义名必须可辨认**（落实 §7.7 第 3 条）：display 视图里，三元组的内容 span 用「转义名字」专门样式渲染（与结构 span 样式不同，也与普通内容不同），悬停显示 canonical 原文 `` `当` `` 与「名字，不是关键词」。这是必需项，不是可选样式 |
| E8 | **保存校验**（§7.5 表增补）：`validatePolicyLayout` 增加 `layout_escape_delimiter_invalid`（L10a）与 `layout_hidden_escape_changes_program`（L10b）；`layout_structural_hides_value` 对转义 token 的处理见 ADR 0059 修订 5 §4.2。cloud `prepareLayout` 把版本的编译上下文传给 `validatePolicyLayout`，否则含三元组的排版被拒 |

## 2. 说明

### 2.1 为什么编辑器能写出转义，而编译器不读排版

编辑器知道作者的意图（「这是字段名」），这正是备忘录方案 (b) 想用的信息。把意图落到 canonical 的转义里，而不是落到 layout 的旁注里，canonical 自己就说清楚了：编译器、签名、CLI、Guard 与审批者读到的是同一份文字（§7.1、§7.2 与修订 1 R15 原样成立）。

### 2.2 隐藏转义符对各视图的影响

| 视图 | 反引号 |
|---|---|
| 审批视图（`VersionDetailPanel`，修订 1 R15） | 可见（canonical 优先） |
| 评审面板、版本对比（§7.7 第 1、2 条） | 可见；diff 以 canonical 为准，「隐藏转义符」只改 layout，单列「显示排版已变更」 |
| 策略详情页（修订 1 A1 默认显示排版） | 被隐藏，转义名按 E7 样式标出，悬停见原文 |
| 编辑器并排视图 | canonical 一侧可见，display 一侧按 E7 |
| 策略包 `display.aster` / `aster show` | 被隐藏；`show` 在 stderr 列出每个被隐藏的转义（ADR 0059 修订 5 §4.3） |

### 2.3 哪些词能隐藏

由 L10b 决定，编辑器只是预检。缺省情况下：结构关键词作名字（如诗句里的 `当`、`如果`）在 ADR 0056 修订 A6 落地后可以隐藏；今天会被静默改名的词（如字段 `版本`）不能隐藏，反引号保持可见——去掉反引号它会编译成字段 `version`，是另一个程序。编辑器对后者的提示是「此处去掉反引号会读作关键词 IMPORT_VERSION，转义符必须可见」。

## 3. 测试与验收

- 表单生成：四个 locale 下每类关键词（结构类、非结构类、别名、触发词、de 冠词）各一例，生成的源码两引擎编译得到原词作名字。
- 快速修复：E808、W802、关键词作名字错误各一例，修复后 IR 名字为原词。
- 隐藏转义符：ADR 0059 修订 5 §6 的 E1–E19 在 `validatePolicyLayout` 上与 CLI 结论一致（通过 / 拒绝一致，码按 §2.4 的映射）。
- E7 的样式与悬停有快照测试；开关为 false 时编辑器与今天逐字节一致。
