# ADR 0056 修订 A6：本地化关键词作名字的解析分歧裁决（结构关键词两引擎都拒绝）

- 状态：**PROPOSED**（2026-11-22，随 [ADR 0064](0064-name-escape.md) 一起待产品负责人采纳）。其中 K1 的方向已由产品负责人于 2026-11-22 裁决（ADR 0064 Q5）；K2、K3 是同一代码任务的推荐裁决
- 日期：2026-11-22
- 修订对象：[ADR 0056](0056-type-inference-parity.md)，接续[修订 A5](0056-amendment-5-parity-rulings-1055.md)
- 相关：ADR 0064 §1.2 类别 1、2 与附录 A；core `AsterParser.g4:497-535`（`structKeywordName`）、`AsterLexer.g4:146`（`VERSION` 硬 token）、`Canonicalizer.java:553-575`；ts `keyword-translator.ts:529-561`
- 实现落点：`aster-lang-core`、`aster-lang-ts`、`aster-lang-test`、`aster-lang-locales` / `aster-lang-hi`；这是 ADR 0064 的 (d) 部分，作为单独的代码任务进行，**不等** ADR 0064 的转义语法，但 K1 的 core 收紧以 G3 门禁扫描干净为前提

本修订的「现状」取自 2026-11-22 的探针：已发布的 `@aster-cloud/aster-lang-ts@3.6.0` 与本地构建的 `aster-cli-1.0.54.jar`（ADR 0064 附录 A）。jar 不是当前 `main` 的构建，实现 PR 须先在 `main` 上用共享夹具复核每一行。

---

## 0. TL;DR

| # | 写法 | 现状 core / ts | 裁决 | 收紧 / 放宽 |
|---|---|---|---|---|
| K1 | 非英语 locale 下，**结构关键词**出现在名字位置：zh `返回`、`如果`；de `bei`、`wenn`、`sei`；hi `जब`、`नियम` | 先翻译再经 `structKeywordName` 接受，名字静默变成 `Return`、`If`、`When`、`Let`、`Rule` / E055 | 两引擎都**拒绝**，报清楚的错误；转义上线前提示改名，上线后提示 `` `wenn` `` | core 收紧（G3 先行） |
| K2 | `version`（及 zh `版本`、de `version`）、en `type`、`given` 作字段或成员 | 语法错误 / 接受 | 两引擎都**接受**为名字 | core 放宽 |
| K3 | 非结构类本地化关键词作名字（zh `版本`、`允许`；de `erlauben`、`gleich`；hi `अनुमति`） | 两侧多数接受并改名为英文关键词，部分只在 core 失败 | IR 不变（不改运行时输入键）；两引擎都报警告 W802；de `gleich` 等 core 独有的失败按 K2 同法对齐 | 只加警告 + core 放宽个别位置 |

---

## 1. K1：结构关键词作名字

- **现状**：ts 报 E055（字段位置「Expected field name」，形参位置「Expected identifier」）。core 的规范化器先把 `wenn` 译成 `If`，解析器再经 `structKeywordName` 软关键词接受它为名字，IR 名字是 `If`。英文 `return`、`when` 在两引擎都照常是名字（en 没有翻译步骤），不在本条范围。
- **裁决**：两引擎都拒绝。错误报在该词上，参数 `{keyword, kind}`（如 `{keyword: "wenn", kind: "IF"}`），文案说明「`wenn` 是关键词 IF，不能直接作名字」；ADR 0064 上线前帮助行为「请改名」，上线后为「改写为 `` `wenn` ``」。错误码由实现 PR 定：沿用 E055 并加参数，或新开一个专门的码（按 ADR 0031 分配）；两引擎必须同码、同参数、同位置。
- **理由**：静默改名比报错更危险——作者与评审者读到的是 `wenn`，运行时输入键却是 `If`。ts（编辑器、CLI、Workers 包检查）今天已经拒绝；英文软关键词的既有行为不变。
- **落地**：core 在 `structKeywordName` 的接受路径上区分「源码就是英文拼写」与「由本地化拼写翻译而来」（后者可从规范化器的源映射或私有区标记协议得知），后者报错。ts 只改诊断参数与帮助行。
- **门禁**：G3（ADR 0064 §12.1）——在生产源码中找结构关键词同形词出现在名字位置的情形，用 java-gate 比较新旧 core 与 ts 的 IR。预期 0。

## 2. K2：`version` / `type` / `given` 作成员

- **现状**：core 把 `VERSION` 作为硬 token（`AsterLexer.g4:146`），en `type`、`given` 也只在关键词位置可用，所以 zh `s.版本`、de / en `version`、en `type`、`given` 作字段或成员时 core 报语法错误；ts 接受为名字。
- **裁决**：两引擎都接受为名字，core 把这几个 token 加入软关键词集合，与 ts 的上下文关键词模型一致。名字是源码写法（en）或其翻译（zh `版本` 仍按 K3 改名为 `version` 并报 W802）。
- **理由**：放宽，不会让能编译的源码失败；ts 的行为已经上线于编辑器与 CLI。

## 3. K3：非结构类本地化关键词作名字

- **现状**：两引擎都把它全局翻译，IR 名字成了英文关键词（zh 字段 `版本` → `version`，de `erlauben` → `allow`）；de `gleich`（BE）作字段 ts 接受为 `be`、core 报语法错误。
- **裁决**：
  - **IR 不变**：保留今天的改名结果，不改为「IR 保留源码写法」——那会改变已存策略的运行时输入键。
  - **两引擎都报警告 W802** `KEYWORD_RENAMED_AS_NAME`（码暂定，按 ADR 0031 分配）：凡名字的源码写法因关键词翻译而与 IR 名字不同即报；ADR 0064 上线后帮助行建议 `` `版本` ``。是否以后升为错误是 ADR 0064 的开放问题 Q4，须以 G2 为前提另立修订。
  - core 独有的失败（`gleich` 等）按 K2 同法对齐为接受。
- **理由**：警告让今天看不见的改名变得可见，同时不改变任何 IR。

## 4. G3 命中时的处理

K1 是收紧：G3 若命中，命中的生产版本在新 core 上会编译失败。处理顺序：

1. 扫描报告只给数量、locale 与 kind，不打印源码（`aster-gate-scan` 规则）。
2. 命中数为 0：K1 随该代码任务的列车上线。
3. 命中数 > 0：core 的收紧推迟；ts 维持拒绝（它本来就拒绝）；core 对这些写法先报 W802 并在 IR 中保持今天的改名。ADR 0064 转义上线后，由作者或运维把命中的版本改写为 `` `wenn` ``——注意转义后 IR 名字变成 `wenn` 而不是 `If`，属于输入键变化，须逐条与作者确认。全部迁移后再收紧 core，另记一条修订。

## 5. 共享夹具

aster-lang-test tier1：ADR 0064 附录 A 中两引擎结果不同的每一行各一个夹具（字段、形参、成员三种位置），预期按 K1–K3；英文 `return`、`when` 作名字的对照夹具；W802 的出现条件与参数；K1 错误的 `{keyword, kind}` 与位置。
