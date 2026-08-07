# ADR 0026 — 等缩进的多行表达式续行

**状态**: ACCEPTED（Claude 起草 → Codex 设计审查 5/10 退回 → 据审查收缩为「仅等缩进」重设计）
**日期**: 2026-06-30
**关联**: ADR 0019/0022；起因为 alias 谣曲 demo（[[alias-poem-story-demo]]）希望运算符链能跨行书写。
**Codex 设计审查**: session 019f157b（实现前审查，抓到两处致命点，见 §6）。

## 1. 背景与问题

Aster 缩进敏感、语句以 `.` 终结。今天一条二元运算链必须写在一行：`sing a then b then c.`。换行书写失败（行首 `then` 被当新语句 → "Unknown statement"）。目标：让**与语句起始行等缩进的、运算符打头/结尾的续行**被识别为上一表达式的延续。

## 2. ★据 Codex 审查收缩范围：仅「等缩进」续行

Codex 审查（§6）指出原设计的致命缺陷：表达式层若吞 `INDENT` 而不吞配对的 `DEDENT`，会让块解析提前闭合（INDENT/DEDENT 栈失衡）→ 结构破坏 + 双引擎不一致。

**故本特性只支持等缩进续行**：续行与语句起始行**同缩进**，词法器只在行间发 `NEWLINE`（**不发 INDENT/DEDENT**）。续行的 `nl` 吞噬器**只吞 NEWLINE，绝不碰 INDENT/DEDENT** → 缩进栈不动，块结构零风险。

```
  sing opening          ← 语句起始, 缩进 2
  then turning          ← 续行, 同缩进 2 (只 NEWLINE, 无 INDENT)
  then heavens.         ← 续行, 同缩进 2, 句号收尾
```

更深缩进续行（`    then`，诗里更想要）**本批不做**——它需要成对 INDENT/DEDENT 消费或 lexer continuation 标记，是布局层重设计（L→XL，高回归风险），记入 backlog。

## 3. 歧义性处理（据 Codex 反例修正）

原「语句永不以运算符开头」**不完全成立**：Aster 有前缀 `operatorCall` 产式 `op(...)`（`+(a, b).`），语句**可**以运算符符号开头。修正后的安全判定：

**续行只在「正在解析一个表达式的二元运算循环内」触发**，且判定为续行的条件是「NEWLINE（等缩进，无 INDENT/DEDENT）后紧跟本层二元运算符 token/词形，且该运算符不是 `operatorCall` 形（即其后不是 `(`）」。
- 在 expr 循环内：我们已在解析一个表达式，遇到「NEWLINE + 运算符」自然续接——这与「block 发起新语句」不冲突，因为新语句的 NEWLINE 后跟的是语句引导（关键词/名称/`op(`），不是裸中缀运算符。
- `op(...)` 前缀调用：续行判定要求运算符后**非 `(`**，故 `+(a,b)` 不被误判为续行（它本就是合法新语句）。
- 词形运算符（plus/minus/times/and/or…）在 TS 是普通 IDENT 由 `ctx.isKeyword()` 识别：续行前瞻同样走 `isKeyword()`，与单行循环判定**完全一致**，不引入新歧义。

## 4. 设计（两引擎共用语义）

### 续行覆盖的二元运算层（据 Codex §6 补全，勿漏）
TS：`parseOr / parseAnd / parseComparison / parseAddition / parseMultiplication`。
Java：`orExpr / andExpr / comparisonExpr / additiveExpr / multiplicativeExpr`。
注意：`modulo` 仅词形无 `%` 符号；`=`/`==`/`!=`/`under..over`/`is at least`/`is at most` 等比较词形都在 comparison 层。**Java comparisonExpr 当前是单比较非链式、TS 是 while 链——这是既有差异，本特性不碰它**（续行只在「确有下一个运算符」时触发，单比较层若无链式则自然不续接，两引擎对「单比较 + 续行」行为一致：都不续）。

### TS 引擎（hand-written parser）
- `src/parser/context.ts`：加 `isContinuationAhead(isOp)` ——前瞻：当前是 `NEWLINE` 且其后（跳过连续 NEWLINE/COMMENT、**遇到 INDENT/DEDENT 即返回 false**）第一个有效 token 满足 `isOp(tok)` → 返回该位置；否则 false（不移动游标）。配套 `skipContinuationNewlines()` 真正消费这些 NEWLINE。
- 每个二元运算循环（or/and/comparison/addition/multiplication）：
  - **运算符前**：原「`ctx.at(op)`」判定改为「`ctx.at(op) || isContinuationAhead(isThisLevelOp)`」；若靠续行命中，先 `skipContinuationNewlines()`。
  - **运算符后取右操作数前**：同样 `skipContinuationNewlines()`（支持行尾运算符续行 `a then\n b`）。
- 关键不变式：见到 INDENT/DEDENT 即判非续行、**一个 token 都不消费**——等缩进之外的所有场景行为完全不变（零回归面）。

### Java 引擎（ANTLR）
- `nl` 只吞 NEWLINE：`nlOpt : NEWLINE* ;`（**不含 INDENT/DEDENT**）。
- 二元产式插入：`additiveExpr : multiplicativeExpr ( nlOpt op=(PLUS|MINUS|PLUS_WORD|MINUS_WORD) nlOpt multiplicativeExpr )* ;`（or/and/comparison/multiplicative 同构）。
- 因 `nlOpt` 只吞 NEWLINE，`block` 的 INDENT/DEDENT 配对完全不受影响；ALL(*) 在「NEWLINE 后是运算符（续行）」与「NEWLINE 后是 DEDENT/语句引导（块边界）」间靠 first-set 区分（运算符 first-set ∩ 语句引导 first-set = ∅，除前缀 `op(` 但那需 `(` 跟随，lookahead 可分）。
- `AstBuilder.java` 无需改：`nlOpt` 是纯语法噪音不产 AST 节点，visitor 只读 `op` + 两侧 expr。

### 零新 AST/Core IR 节点
等缩进续行 `a\nthen b\nthen c.` 与单行 `a then b then c.` 解析到**完全相同** Core IR（`Call(Call(+,a,b),c)`）。parity 基础同 ADR 0022 别名：表层书写差异在解析层抹平。

## 5. parity fixture（须专门）
- 单行 vs 等缩进多行**同一表达式** → 结构一致 Core IR（剥 origin/span 后 deepEqual；golden runner pruneCore 已剥 origin）。
- 各运算层都测：`+ then`/`*`/比较/`and or` 的等缩进续行。
- 行首运算符续行 + 行尾运算符续行两种。
- **负面**：①更深缩进续行须仍报错（本批不支持，确保不被误接）；②`+(a,b).` 前缀调用仍作合法语句（不被续行误判）；③无 `.` 仍报缺终结符；④续行跨到更浅缩进（DEDENT）须停止、不误吞下一块。
- eval 不变（IR 相同）。

## 6. Codex 设计审查纪要（session 019f157b, 5/10 退回 → 已据此重设计）
- **致命点 1（已采纳）**：表达式层吞 INDENT 破坏 INDENT/DEDENT 配对 → 块提前闭合。**修：只做等缩进、`nl` 只吞 NEWLINE**。
- **致命点 2（已采纳）**：「语句永不以运算符开头」有反例——前缀 `operatorCall` `op(...)`。**修：续行判定要求运算符后非 `(`，且只在 expr 循环内触发**。
- **补全（已采纳）**：运算符层须含 or/and/comparison/additive/multiplicative 全部；`modulo` 仅词形、`=` 也是比较；Java 单比较非链式是既有差异本批不碰。
- **确认 OK**：eval-parity 不受 origin 行列变化影响（pruneCore 剥 origin）。

## 7. `be` 诗意化（附带，S，纯别名）
谣曲 demo 把 `SemanticTokenKind.BE` 在 Bard lexicon 别名成 `become`（`let earlier become …`）。不动 canonical、零 parity 风险。仅 demo。

## 8. 回滚
纯解析层加法（运算符循环续行判定 + ANTLR 产式可选 `nlOpt`），不改 Core IR/AST/类型/求值，不碰 INDENT/DEDENT 栈。单行写法行为完全不变。parity red 可整批回退。
