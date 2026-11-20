# ADR 0058 修订 2：类型别名引导词本地化（C4 独立审查 M4）

- 状态：**ACCEPTED**（2026-11-20，产品负责人采纳 eco-1.0.53 Wave B 计划「待裁决」第 4 项的推荐方案）
- 日期：2026-11-20
- 修订对象：[ADR 0058](0058-structured-authoring-capability-and-policy-layout.md) §8.3「新建类型别名」表单（「经 lexicon 生成本地化源码插入，作者不手写关键词」）在类型别名引导词上的落地
- 相关：aster-cloud/aster-lang-ts#346（C4 独立审查 M4）、wontlost-ltd/aster-cloud#804（模型标签页）
- 实现落点：`aster-lang-locales`（en / zh / de）、`aster-lang-hi`、`aster-lang-core`、`aster-lang-ts`、`aster-lang-test` 同名分支 `feat/type-alias-keyword`；发版后 cloud `src/lib/structured-authoring/source-builders.ts` 改取 lexicon 关键词

---

## 1. 问题

`Type X as T` 的引导词在两引擎都没有 lexicon 关键词：ts `decl-parser` 直接匹配英文 `type`，core 文法只认 `type` / `Type`。zh-CN、de-DE、hi-IN 文档里的类型别名只能写英文 `Type`，cloud 模型标签页的「新建类型别名」表单也只好写死 `Type`，与 §8.3「作者不手写关键词」相矛盾。

## 2. 裁决

| # | 裁决 |
|---|---|
| T1 | 新增 lexicon 关键词 kind `TYPE_ALIAS`（类型别名引导词）。拼写：en-US `Type`、zh-CN `类型`、de-DE `Typ`、hi-IN `प्रकार` |
| T2 | 英文 `Type`（不区分大小写，ADR 0056 A2 §2）在**所有** locale 永久保留为类型别名引导词，不随本地化拼写退役：存量源码与已发布的 cloud 表单照常可用 |
| T3 | 本地化拼写与英文 `type` 一样是**声明句首的引导词**：只在顶层声明的开头当关键词，在名字位置（字段、形参、变量、类型名等）照常是名字，不改名 |
| T4 | 发布以 §3 的门禁扫描干净为前提 |

hi-IN 拼写 `प्रकार` 取自 hi-IN 词法表已有的「प्रकार बेमेल」（类型不匹配）用语。

## 3. 门禁扫描（合并前必需）

新拼写成为关键词后可能改变已有源码的含义，或与别名、词汇冲突：

1. 以新拼写开头的顶层声明行：此前是语法错误（两引擎都报错），现在成为类型别名。不会让能编译的源码失败，但要确认没有依赖「报错」的草稿。
2. 新拼写在名字位置出现（如 zh `类型 作为 文本` 的字段、de `Typ` 作参数名）：按 T3 照常是名字；扫描确认生产里这些写法的 Core IR 在新旧引擎相同。
3. aliasSet 与词汇（作者、团队、内嵌）的写法等于新拼写：ADR 0060 §5.3 的「别名 ≠ 规范拼写」与 `embedded_vocabulary_shadows_keyword` 会在**下次保存**时拒绝它们；已存版本按 ADR 0060 修订 1（R16'）关键词优先。

具体的正则与预期见实现 PR。新 kind 改变词法表指纹，编译缓存随 pin bump 失效。
