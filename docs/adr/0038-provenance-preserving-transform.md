# ADR 0038：SourceIR 的可选 transform 层（Provenance-preserving Canonicalizer）

- 状态：**DEFERRED**（2026-09-13。方案已定，**但现在不实施**——触发条件见 §6）
- 决策者：用户已拍板「开放任意人类文本之后才需要」
- 相关：**ADR 0037（可验证语义桥，§5/§10）**、ADR 0032（Core IR span 贯通 trace 锚定）

---

## 0. TL;DR

ADR 0037 §10 落地 SourceIR 后，出现一个自然的追问：

> **LayoutMap 能不能用 SourceIR 取代，从而不必再维护它？**

**不能**——两者正交（§2 有实测）。但这个问题指向一个**真实存在**的缺口：

```
今天    人类文档 ──直接──→ SourceIR ──→ MappingIR
        （前提：文档本身就是 canonical，或与 canonical 逐字对齐）

缺的    人类文档 ──transform──→ canonical ──→ SourceIR ──→ MappingIR
                      │
                      └─ offset 映射（原文位置 ↔ canonical 位置）
```

本 ADR **定下这一层的设计**，但**明确不现在做**：它的价值只在「对外开放任意
人类文本」之后才兑现，提前做是在换取一个 41 行文件的维护成本。

---

## 1. 背景：为什么会问「能否取代 LayoutMap」

ADR 0037 §5 原文写：

> ```
> LayoutMap
>     ↓
> Transformation / Origin Map
> ```

这个箭头**措辞有误导性**——它读起来像「LayoutMap 会被升级成 Origin Map」，
于是自然产生「那 LayoutMap 是不是就可以删了」的推论。

实测表明两者是**并存关系**，不是升级关系。ADR 0037 §10.1 已澄清，本 ADR 补上
被那句措辞掩盖的**真正缺口**。

---

## 2. 实测：LayoutMap 与 SourceIR 正交

### 2.1 SourceIR 结构上做不到 LayoutMap 的事

LayoutMap 的核心是**同一位置有两种文本**：

```
canonical: "床前 明月光。\n疑是 地上霜，"   15 字符
display  : "床前 明月光，\n疑是 地上霜。\n"   16 字符
```

**两者长度不同**，一套字符偏移无法同时指向两个文本。

实测：把一棵 SourceIR 树指向 display 文本，它**自己的** `verifyCoverage`
立刻报 2 处违规——因为 SourceIR 的硬约束就是「span 必须能逐字节切回**那一个**
文档」（ADR 0037 §10.2）。这不是实现缺陷，是设计前提。

### 2.2 LayoutMap 也做不到 SourceIR 的事

| | LayoutMap | SourceIR |
|---|---|---|
| 层级 | ❌ `LayoutSpan = {text} \| {canonical,display}`，平铺数组无 `children` | ✅ 嵌套（标题层级）|
| 构造 | 手写，**逐字符全覆盖** | 机械推导 |
| 规模 | 《静夜思》12 条手写 span 可行 | 真实 Policy 5206 字符 / 27 个内容块 |

### 2.3 维护成本本来就低

| 指标 | 数值 |
|---|---|
| 代码量 | **41 行** |
| 使用方 | 5 个文件 |
| 近半年改动 | **1 次**（即当时的重写本身）|

**结论**：LayoutMap 不是负担，也无法被取代。

---

## 3. 真正的缺口：变换会移动/删除字符，而 offset 映射没被记录

SourceIR 要求「span 能逐字节切回原文」。今天这条成立，是因为
**canonicalize 对本仓语料几乎不改列位**（ADR 0037 §4.0.3 的一系列修复之功）：

```
tier1 语料 223 个：行数不变 223/223，代码列位不偏 217/223
```

但这只对**已经是 Aster 源码**的文本成立。对任意人类文本，canonicalize 会做
四类变换，**其中两类直接改变长度**：

| 变换类别 | 实例 | 长度 |
|---|---|---|
| **删除**（冠词）| `Return the answer.` → `Return answer.` | **18 → 14** |
| **折叠**（多空格）| `x    y` → `x y` | **6 → 3** |
| 替换（智能引号）| `Say “hi”.` → `Say "hi".`（弯引号→直引号）| 9 → 9（等长但**内容变**）|
| 替换（制表符）| `x\ty` → `x y` | 等长（本例；tab 宽度不同则不等长）|

★**冠词移除是最强的例子**：`the` 三个字符**整个消失**，没有任何简单规则能从
canonical 位置反推回原文位置。这不是缺陷——它是 `removeArticles` 的既定行为。

**后果**：一旦文档不是 canonical，`SourceIR.span` 指向的位置与用户看到的原文
**错位**，而且**不报错**——「点击原文 ↔ 高亮 IR 节点」静默指错字符。

---

## 4. 方案：可选的 transform 层 + offset 映射

```
原文档 ──transform──→ canonical ──→ SourceIR ──→ MappingIR
   │                      │
   └──── OffsetMap ───────┘
        （双向：原文位置 ↔ canonical 位置）
```

### 4.1 核心数据

```
TransformResult {
  canonical: string          // 变换后的文本
  offsetMap: OffsetMap       // 双向位置映射
  transforms: TransformRecord[]  // 每一步做了什么（可审计）
}
```

`OffsetMap` 至少要支持两个方向：

```
toCanonical(originalOffset) → canonicalOffset | DELETED
toOriginal(canonicalOffset) → originalOffset
```

★`DELETED` 必须是**显式返回值**而不是抛错或返回 `-1`：冠词被删掉是**正常且
预期**的，调用方需要区分「这段原文没有对应的 canonical 位置」与「查询出错」。

### 4.2 三条硬约束

1. **变换必须可枚举**。每一步（删注释 / 去冠词 / 折叠空白 / 规范引号）单独记录，
   不允许「一个大正则一次改完」——那样无法生成映射，也无法审计。
2. **映射必须机械可验**。参照 ADR 0037 §10.2 的 `verifyCoverage`：
   对每个原文位置，`toOriginal(toCanonical(p)) == p`（未被删除者），
   这条不变式要能被测试直接检查。
3. **LayoutMap 是它的一个手写特例**，不是被它取代。届时可表述为
   「LayoutMap = 人工给定的 transform，其 offsetMap 由 span 列表直接导出」。

### 4.3 与既有模块的接口

- `SourceIR.parse(canonical)` **不变**——它继续只认一个文档。
- 新增 `SourceIR.parseWithTransform(original, transform)`：内部先变换再解析，
  返回的节点 span **仍指向 canonical**，但附带 `originalSpan`。
- `MappingIR` **不变**——它吃的是 `TextSpan`，由调用方决定传哪一套。

★这样分层的好处：**今天的代码一行都不用改**。transform 层是纯增量。

---

## 5. 为什么现在不做

| 理由 | 依据 |
|---|---|
| **今天不需要** | 本仓所有实际输入（`.aster` 源码、`.md` Policy）要么就是 canonical，要么与 canonical 逐行对齐（223/223 行、217/223 列）|
| **收益为零** | 在没有「任意人类文本」输入之前，offsetMap 的每次查询都是恒等映射 |
| **成本非零** | 要把 canonicalizer 的每一步变换拆开并逐步记录位置。★注：ADR 0037 §4.1 提到的「137 个调用点」指的是**跨仓调用 `canonicalize()` 的地方**（Java 116 / TS 19 / cloud 2），不是内部变换步数——若 transform 层作为**新增 API**（§4.3）而不改既有签名，这 137 处**不必动**。真正的成本在 canonicalizer 内部的变换拆分，具体规模未实测 |
| **ADR 0037 §10.5 有更高优先级的缺口** | **Entity/Quantity 层**——那是整条链上唯一还需要 LLM 的位置 |

★本仓反复出现的教训是「过早建基础设施 → 建成时需求已变」。transform 层的
形状**取决于**真实人类文档长什么样，而我们现在只有 1 份真实 Policy 样本
（`p0a-signability-policy.md`）。

---

## 6. 触发条件（满足任一即启动）

1. **产品决定接受非 Aster 的人类文档作为一等输入**——即 ADR 0037 §9 的
   `Policy ↔ Code` 真正对外，而不只是 demo。
2. **出现「原文与 canonical 不对齐」的真实用户报告**——例如用户在 Policy 里
   写了 `the`，而高亮跳到了错误的字符。
3. **`ugly*` 类输入的比例上升**——ADR 0037 §4.0.3 实测：当前 223 个样本里
   只有 6 个会移动代码列位，且全是刻意构造的压力样本。若真实输入里这类
   比例显著上升，说明前提已不成立。

★**反向信号**（说明**不该**启动）：只是想「统一 LayoutMap 与 SourceIR」。
§2 已实测两者正交，为形式上的统一而建 transform 层是纯粹的复杂度增加。

---

## 7. 不做的事

- **不删除 LayoutMap**。它 41 行、5 个使用方、半年改 1 次，且 SourceIR
  结构上无法替代它（§2.1）。
- **不在 SourceIR 里塞第二套文本**。「一个 SourceIR 树绑定一个文档」是它的
  不变式基础（ADR 0037 §10.2），破坏它等于让 `verifyCoverage` 失去意义。
- **不现在改 canonicalizer**。拆散它的变换步骤是 transform 层的实现代价，
  在触发条件满足前不付这个成本。

---

## 8. 决策记录

- 用户已拍板：**方案记录在案，开放任意人类文本之后才需要**。
- 本 ADR 状态为 `DEFERRED` 而非 `PROPOSED`——它不等待评审，等待的是 §6 的
  触发条件。
- ADR 0037 §10.5 的「仍未做」清单中，**Entity/Quantity 层**优先级高于本 ADR。
