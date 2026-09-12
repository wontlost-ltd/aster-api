# ADR 0037：从「多语言可执行语言」扩展为「可验证语义桥」

- 状态：DRAFT（2026-09-12。由一次方向评审倒逼提出；评审过程中实测发现两处 IR 不确定性缺陷，已先行修复）
- 决策者：待用户拍板
- 相关：ADR 0016（双引擎 IR parity）、ADR 0030（规则集升级回归工具 / canonical JSON）、**ADR 0032（Core IR span 贯通 trace 锚定）**、ADR 0018（统一语言包）

---

## 0. TL;DR

方向本身成立：

> Aster 不需要推翻重做，而是从「多语言可执行语言」向
> 「人类文本与可执行语义之间的**可验证映射层**」自然扩展。

但**实施顺序必须调整**。评审把原提案的三件基础设施逐条对着代码核实后发现：

| 原提案的「三件基础设施」 | 实测状态 |
|---|---|
| OriginMap v1 | **Java 侧已完成大半**（`CoreLowering` 中 52 个 `spanToOrigin` 注入点，深入到字面量层）；**TS 侧为 0** |
| Stable IR Node IDs | **确实是零**——`CoreModel` 无任何 id/hash 字段。唯一全新的一件 |
| Canonical IR Serialization | **已存在且有字节级 parity gate**（`CanonicalJson.java` ↔ `aster-cloud/src/lib/canonical-json.ts`） |

而真正的拦路石**不在这三件里**，共三条（§3）。其中第一条已在本 ADR 提出过程中修复并补上门禁。

---

## 1. 背景：这不是一次「要不要做」的讨论

原提案十节的核心论断——放弃「人类文本必须先变成合法 Aster Source 才能与程序建立关系」这一假设，改为允许
`HumanArtifact ↔ CoreIR` 的可验证映射——评审认为**判断准确**，不再复述。

本 ADR 只记录两件事：

1. 评审对着代码验证后，**哪些论断需要修正**（§2）；
2. 由此得出的**实施顺序**，以及为什么原顺序会在第三阶段才炸（§4）。

---

## 2. 评审发现：需要修正的论断

### 2.1 ✅ 「当前 Aster 是新架构的严格子集」——成立，证据比提案更强

`aster-lang-core/src/main/java/aster/core/lowering/CoreLowering.java` 有 52 个
`spanToOrigin` 调用点，覆盖到字面量与表达式层：

```java
out.origin = spanToOrigin(str.span());   // 字符串字面量
out.origin = spanToOrigin(dec.span());   // Decimal
out.origin = spanToOrigin(call.span());  // 调用
```

ADR 0032 已独立得出同一结论（「位置信息已经存在，只是没贯通」），且它是被**一个已确证的生产缺陷**倒逼的。本 ADR 与 0032 是同一条主线的两个层次：

- **ADR 0032**：把 Java 侧已有的 `origin` 贯通到 trace 锚点（解决 `stepId` 不是源码标识导致的静默错误）。
- **本 ADR**：把 `origin` 提升为**跨引擎契约**，并回答「凭什么相信它是对的」。

**0032 是本 ADR 第 1 步的前置，不是竞争方案。**

### 2.2 🔴 parity gate 主动把 `origin` 剥掉——提案未提及

`aster-lang-test/scripts/parity-tier1.mjs:564`：

```js
const IR_IGNORE_FIELDS = new Set(['origin']);
```

`IR-DIVERGENCE-LEDGER.md` 明写：「Stripped derived-analysis layer … `origin` spans」。

含义：**今天 PR-blocking 的 IR parity 门禁，对 `origin` 完全不设防**——两个引擎的 origin 可以任意分叉而门禁全绿。这与本仓多次记录的「门禁在结构上无法变红」是同一模式。

代码里给出的剥离理由是
*"line/col numbering conventions legitimately differ between engines"*。

**该理由经实测不成立**：两侧都是 1-based
（Java `Lexer.java:69-70` 的 `line=1/col=1`；TS `frontend/lexer.ts:203-204` 同样 `line=1/col=1`）。

#### 2.2.1 步骤 1 的实测结论（2026-09-12 补）

把 `IR_IGNORE_FIELDS` 临时置空后重跑 `--mode=ir --full`，得到确定答案：

```
::error::tier1-parity (ir field-level) divergence: 150/223 sample(s) not identical
```

**150/223 样本在 origin 上分叉**——这条豁免掩盖的不是「编号习惯的细微差异」，而是一个**系统性、大面积**的分歧。

按字段归类（1325 条 diff）：

| 字段 | 条数 | 性质 |
|---|---|---|
| `origin.file` | **736** | 🟢 纯表示差异：`ts=undefined` vs `java="null"`。台账已有「missing == null」同类规则，可直接归一 |
| `origin.end.col` | **450** | 🔴 真实语义分歧（见下） |
| `origin.start.col` | **115** | 🔴 同上 |
| `origin.start.line` / `end.line` | 各 **12** | 🟡 少量，需逐个 triage |

**行号基本一致**（仅 24 条分歧），**列号是主要战场**。

**根因（已证伪两个先验假设）**：

- ❌ *假设一：固定偏移*。实测列差分布散乱（−8: 89 次、+3: 70 次、+4: 62 次、+8: 58 次、+11: 51 次…），**不是**简单的 base-index 差异。
- ❌ *假设二：Canonicalizer 改写导致坐标偏移*。实测 `greet.aster` 的 `canonicalize(src).equals(src) == true`——该样本canonical 化后**逐字节不变**，故列差与 Canonicalizer 无关。
  （⚠️ 这不推翻 §2.4：对**含注释/制表符**的源码，Canonicalizer 仍会移动坐标。只是说明它不是本次 150 个样本的成因。）
- ✅ *实际成因*：**TS 侧的 `end` 位置很多是占位值而非真实计算结果**。典型：`greet.aster` 的
  `$.origin.end.col`（Module 节点）与 `$.decls[0].origin.end.col`（Rule 声明）
  在 TS 侧**都是 1**，而 Java 分别是 6 和 9（对应 `Module` 与 `Rule greet` 的真实结束列）。
  TS 并非「用了另一套编号」，而是**没有在计算结束位置**。

**对实施顺序的影响**：这条发现把步骤 3（TS 侧补 origin）的性质从「把已有位置接进 IR」
改为「**TS 侧需要真正实现 end 位置的计算**」——工作量上调，但**方向依然可行**，
因为 line 已基本一致、file 只是表示差异。

**建议的收敛路径**（不必一次到位）：
1. 先把 `origin.file` 的 `undefined`/`"null"` 归一（736/1325 条，纯表示，零风险）；
2. 再把 `origin.*.line` 纳入门禁（仅 24 条分歧，先小后大）；
3. `origin.*.col` 待 TS 侧实现真实 end 计算后再纳入。

★关键是：**分阶段收紧豁免，而不是继续整体剥离**。每收紧一格，门禁就多守住一格。

#### 2.2.2 步骤 3.5 的落地结果（2026-09-12 补，`aster-lang-test#137`）

上述路径的第 1、2 阶段已实施：门禁默认口径改为 `file+line`，分歧从
**150/223 降到 9/223**，且余下 9 个的 diff **全部**是 `origin.*.line`。

**顺带查实了一个真实的 TS 缺陷**（已记入 `IR-DIVERGENCE-LEDGER`）：

TS canonicalize 会**吞掉行**，使其后所有 `origin.line` 整体偏移。
实证 `test_claims.aster`（24 行注释头）：

| | 首个 declaration | 文件行数 |
|---|---|---|
| 原文 | line **27** | 115 |
| Java canonicalize 之后 | line **27** | 115（**不变**）|
| TS canonicalize 之后 | line **3** | 91（**少 24**）|
| → Java `origin.start.line` | **27** ✅ | |
| → TS `origin.start.line` | **3** ❌ | |

偏移恒为 **+24**（= 该注释头被折叠掉的行数）。9 个分歧样本中 7 个带注释头，与该成因一致。

**确切机制（已追到最小复现，2026-09-12）**：不是「TS 跳过注释」，而是
**TS canonicalize 把连续空行折叠成一行**，而注释在此之前已被置空：

```
canonicalize('# a\n# b\n# c\nModule x.\n')  →  '\nModule x.\n'   5 行 → 3 行
canonicalize('A.\n\n\n\n\nB.\n')            →  'A.\n\nB.\n'     7 行 → 4 行
canonicalize('A.\n\nB.\n')                  →  'A.\n\nB.\n'     4 行 → 4 行（不变）
```

Java 侧把注释置空但**保留行数**（`test_claims.aster` 115 → 115），故 Java 正确。

★**重要推论：该缺陷不限于注释头。** 声明之间出现连续两个空行就足以让 TS 的
span 与真实源码脱节——不能以为「没有注释头的文件是安全的」。

#### 2.2.3 ✅ 已修复（2026-09-12，`aster-lang-ts#170`）

根因定位到具体一行：`canonicalize` 用 `/^\s+$/gm` 清空「只含空白的行」，
而 `\s` **包含 `\n`**——于是连续空行被当成**一整块**匹配掉。该行紧邻的注释
写着「Do not collapse newlines globally」，**与实现自相矛盾**。

修法：`/^[^\S\n]+$/gm`（空白但排除换行），逐行生效、行数不变。

**效果**：整文件 origin.line 偏移**归零**。`test_claims.aster` 由
「115 → 91 行、首个 decl 跑到第 3 行」恢复为「115 → 115 行、第 27 行」，与 Java 一致。

★**同时修正了三条「锁死该缺陷」的既有用例**。它们的**名字**都写着「保留空行」，
**断言**却要求折叠（例如「应该清理多余空行中的空白字符」输入 5 行却断言
`lines.length === 3`——「清理空白字符」≠「删除行」）。这不是被改坏的测试，
而是当初把缺陷当成了预期行为。

**验证**：TS 全量 1673 单测通过；变异验证——把正则改回 `/^\s+$/gm` 则
**6 条变红**（3 条新契约 + 3 条修正用例），守卫确实能变红。

#### 2.2.4 🔴 修复后暴露出两个更小的残留 TS 缺陷

移除旧豁免后，原先被它遮住的两个**互不相关**的 TS span 缺陷显形（Java 均正确，
影响 5 个样本）：

- **(a) `ts=0`**（10 条，`g2a-inline-if.aster`）：TS 对 inline-if 的
  `thenBlock`/`elseBlock` 报 `line: 0`。**0 不是合法的 1-based 行号**——TS 对
  自己**合成**（而非解析得到）的块发了默认初始化的 span。
- **(b) `end.line` 短 1~4 行**（15 条，4 个样本）：多行声明上 TS 把 span 收在
  最后一个消费掉的 token，而非声明的真实结尾。

已改为窄口径豁免 `divergent-known-ts-span`——**只**吸收 `origin.*.line`，
`origin.file` 仍全程守护，其他字段的新分歧照样变红（两次变异验证：注入非
origin.line 分歧变红；`strict` 模式变红 150/223）。

⚠️ 这两条是**新发现的待办**，不是本次引入的回退。它们比原缺陷小两个数量级，
但仍会让 ADR 0032 的锚点在 inline-if 与多行声明上指偏。

⚠️ **这直接影响 ADR 0032**：0032 要把执行 trace 锚到源码位置。一个**指偏 24 行**
的 span 会让「点击 trace 步骤跳转源码」**静默跳到错误的行**——与 0032 当初要解决的
`stepId` 问题是同一类静默错误。**0032 落地前必须先修 TS 行号。**

**门禁的技术债登记**：为该已知缺陷加了窄口径豁免 `divergent-known-origin-line`——
**只**吸收 `origin.*.line`，任何其他字段、任何新分歧仍然变红（已用两次变异验证：
strict 模式变红 150/223；注入非 origin.line 的假分歧变红）。它是**有主的技术债**
（本 ADR 步骤 3），不是永久规则。

⚠️ 因此这条豁免要么已经过期，要么在掩盖**别的**真实分歧。**动手前必须查清它到底在挡什么**——否则会在一个来历不明的豁免之上盖房子。

> 同类豁免不止一处：`CrossCompilerCoreIRTest` 的剪枝字段里同样包含
> `origin, span, file, nameSpan, variantSpans`。

### 2.3 🔴 TS 在 lowering 边界丢弃位置

| 层 | Java | TS |
|---|---|---|
| Lexer 产出 line/col | ✅ | ✅（同为 1-based） |
| Core IR 携带 origin | ✅ | ❌ **`src/core/core_ir.ts` 中 origin/span 命中 = 0** |

TS 有完整前端（`frontend/lexer.ts`、`parser/`、`lower_to_core.ts`，位置相关提及 32 处），但 `core_ir.ts` 的 Core 构造函数**一个都不接位置参数**——位置在 lowering 那一步被丢弃。

**结论：工作量不是「两边各加一点」，而是 Java ≈ 已完成、TS ≈ 从零。**

### 2.4 🔴 Canonicalizer 丢弃原文 offset 映射

`Canonicalizer.canonicalize(String input) → String`（`Canonicalizer.java:628`）

它会 tab→2 空格、**删注释**、智能引号→直引号、规范多词关键词大小写，然后返回一个**裸字符串**。原文→canonical 的 offset 映射当场丢弃。

**后果**：Java 的 `origin.start/end` 指向的是 **canonical 文本坐标，不是用户原文坐标**。提案 §4 要的「`"$10,000"` ↔ `Money(10000)` 双向导航」，一旦原文含注释或制表符，坐标就会偏移。

提案 §5 把它列为 "Provenance-preserving Canonicalizer" 是对的，但**它不该排在第三阶段——它是 OriginMap 正确性的前置条件**。否则会先做出一个「对 canonical 文本正确、对人类原文错位」的 OriginMap。

### 2.5 ⚠️ 提案 §5 对 LayoutMap 的定位偏高

`aster-cloud/src/lib/layout-map.ts` 是 **45 行的展示层 helper**，住在**产品仓而非语言仓**，结构是 `{text}` 或 `{canonical, display}` 的 span 序列。其文件头写明不变式：

> 编译永远吃规范 Aster，显示层按映射渲染……display 仅供展示；编译永远走 canonical

它**不是** text↔IR 映射，而是 **text↔text 排版映射**，且刻意与语义解耦。

把它「升级为 Transformation/Origin Map」不是重命名，而是换一个方向的对象：
现在是 `display ↔ canonical`，目标是 `canonical ↔ IRNode`。两者可以串联
（`display ↔ canonical ↔ IRNode`），但那是**新写一层**。

诗歌 demo 作为 PoC 的价值成立，但应说清它证明的是链路的**前半段**。

### 2.6 ✅ 无异议的论断

- §3「AI 可以提出映射，但不能定义什么叫正确」——整个设计里最值钱的一条，与本仓既有纪律一致（verifier 必须能独立变红）。
- §4 局部而非整篇映射；change impact 是真差异化点。
- §6 放弃「人类文本必须先成为合法 Aster Source」——唯一该丢的假设。
- §9 诗歌当极限演示、Policy/SOP 当主战场。

---

## 3. 评审过程中实测发现的两处缺陷（已修复）

这两处不在原提案的视野内，但它们**否定了「IR 可复现」这一所有 traceability 工作的地基假设**。

### 3.1 注解参数顺序随 JVM 启动漂移

`AstBuilder.java:530`（修复前）：

```java
Map<String, Object> finalParams = params.isEmpty() ? Map.of() : Map.copyOf(params);
```

上游用 `LinkedHashMap` 按源码顺序正确收集，在此被 `Map.copyOf` 打乱——JDK 不可变 Map 的迭代序按启动期 SALT 随机化。

**独立实测复现**（完整链路 `LinkedHashMap → Map.copyOf → LinkedHashMap`，6 次独立 JVM）：

```
[level, note, category, $0, $1]
[category, $0, $1, level, note]
[$1, level, note, category, $0]
[category, $0, $1, level, note]
[level, $1, $0, category, note]
[category, $0, $1, level, note]
```

6 次 4 种顺序。落点是 `CoreModel.java:149` 的 `Annotation.params`，挂在
`Func.annotations` / `Func.retAnnotations` / `Field.annotations` / `Param.annotations`
四处 IR 字段上，Jackson 按迭代序输出 → **同一份源码两次编译产出字节不同的 IR JSON**。

★ 下游 `CoreLowering.java:736` 的 `new LinkedHashMap<>(...)` 拷贝**救不回来**——
它忠实保存的是一个**已经乱掉**的顺序。这是「看起来在保序，实际无效」的典型：
只看容器类型会判它无隐患，正确判据是**回溯数据来源并实跑**。

### 3.2 多模块拓扑序不确定

`ModuleGraph.java:62` 的 `topologicalOrder()` 以 `for (var key : modules.keySet())` 为遍历起点，而 `modules` 是 `Map.copyOf`（同样的随机迭代序）。拓扑排序在**等价解**之间的选择由起点顺序决定 → 合并后 `decls` 漂移。

### 3.3 为什么从未被发现

**不是「测试通过了」，而是结构上不存在能触发它的样本。**
实测全语料（不只 tier1）中带 ≥2 个参数的注解命中文件数 = **0**。

这与本仓记忆里「门禁在结构上无法变红」完全同构：一处靠语料恰好不覆盖而潜伏，
一处靠拓扑等价解恰好稳定而潜伏。

### 3.4 已实施的修复与门禁

- `AstBuilder`：改用 `Collections.unmodifiableMap(new LinkedHashMap<>(params))`——保持不可变语义且保住插入序。
- `ModuleGraph`：遍历起点先按 `(moduleName, version)` 排序。不改变拓扑正确性，只是固定在等价解中选哪一个。
- 新增 `IrDeterminismTest`（`aster-lang-core`）：
  - 同一 JVM 内重复编译 20 次，IR JSON 必须**逐字节**相同；
  - **核心守卫**：注解参数顺序必须等于**源码书写顺序**。样本刻意用 `zeta→alpha→mid`（与字典序不同），这样「改成按键排序」的实现**也会变红**——要的是源码序本身，不是任何一种稳定顺序。

**比较口径（关键，易踩坑）**：必须用 Jackson 原生序列化逐字节比，**不能**用

- `JSONAssert`（宽松模式忽略字段顺序——恰好忽略掉本缺陷）；
- `CanonicalJson`（它对 object 键**排序**，会把乱序的 `params` 重新排齐 → **掩盖**缺陷）。

> canonical 化是「让不同来源可比」的工具；这里要验的是「同一来源是否自我一致」。**两者口径相反。**

**验证**：修复前门禁实测变红（IR 实际输出 `{"alpha":2,"mid":3,"zeta":1}`，源码序是 `zeta,alpha,mid`）；变异验证——撤掉修复重新变红，恢复变绿；全量 **1590 测试 0 失败**。

---

## 4. 决策：修正后的实施顺序

### 4.1 原提案顺序

OriginMap → Stable IDs → Canonical Serialization → 接 LayoutMap → MappingIR/ProofIR → Solver

### 4.2 修正后顺序


### 4.0 里程碑：`origin` 的行级映射已完全对齐、零豁免（2026-09-12）

| 字段 | 状态 |
|---|---|
| `origin.file` | ✅ 对齐 |
| `origin.start.line` | ✅ 对齐 |
| `origin.end.line` | ✅ 对齐 |
| `origin.*.col` | 🔴 仍 150 样本分歧（步骤 2 + 3c）|

`end.line` 分歧的完整轨迹，值得作为「假绿」案例留档：

```
0   ← 两侧都错、方向一致而互相抵消（假绿）
40  ← 仅 Java 修好 lastNonLayoutToken，TS 的错显形
1   ← 两侧都修好 lastNonLayoutToken
0   ← Java 再修二元表达式内层 span（真绿，且无豁免）
```

★中间那次 **0 → 40** 是关键教训：**等价性门禁绿 ≠ 两侧都对，也可能是两侧一起错。**
它只能证明「一致」，不能证明「正确」——后者必须与第三方事实（源码文本本身）对照。
若当时按「分歧变多就回滚」处理，会把正确的修复撤掉、把缺陷留在两边。

**这意味着 ADR 0032 的前置条件现已全部解除**：trace 步骤可以按行锚定到源码，
且两引擎给出同一答案。

### 4.1 优先级的一次重要变化（2026-09-12）

步骤 3a（修 TS canonicalize 吞行）落地后，**步骤 2 的紧迫性显著下降**：

- **行级映射已经成立**：canonicalize 现在恒等保持行数（实测四类改写——删行内
  注释、tab→空格、智能引号、折叠多空格——行数全部不变）。ADR 0032 需要的
  「trace 步骤 ↔ 源码行」锚定**现在就能做**，不必等 OffsetMap。
- **列级映射仍缺**：同样四类改写**都会移动列**（`Return    x.` → `Return x.`）。
  所以「点击 `"$10,000"` 高亮到精确字符」这类能力仍需 OffsetMap。

★因此建议把步骤 2 从「OriginMap 正确性的前置」降级为「列级精度的前置」，
排在 3b/3c 之后。理由：它涉及 **137 个调用点**（Java 116 / TS 19 / cloud 2），
是三个步骤里爆炸半径最大的一个，而它现在解锁的增量只是列精度。

| # | 步骤 | 为什么在这个位置 | 状态 |
|---|---|---|---|
| **0** | **IR 确定性门禁** | 所有 traceability 建立在「IR 可复现」上，而该假设**已知为假**。成本极低（编译两次比字节），收益是立刻抓住 §3 两处缺陷 | ✅ **已完成** |
| **1** | 查清 `IR_IGNORE_FIELDS=['origin']` 到底在挡什么 | 其注释理由已被证伪（两侧都 1-based）。不查清就加 OriginMap = 在来历不明的豁免上盖房子 | ✅ **已完成**，见 §2.2.1：**150/223 样本分叉**；file 736 条纯表示、col 565 条真实分歧、line 仅 24 条 |
| **2** | Canonicalizer 保留 offset 映射（`String → (String, OffsetMap)`） | **行级已不再需要**（3a 修复后行数恒等），**列级仍需要**：实测 canonicalize 仍会改列（`Return    x.` → `Return x.`、tab→2 空格、智能引号→直引号）。⚠️ 涉及 **137 个调用点**（Java 116 / TS 19 / cloud 2），是一次真正的 API 变更 | 待办（优先级已下调，见 §4.1） |
| **3a** | ✅ **修 TS canonicalize 吞行**（`aster-lang-ts#170`） | 整文件 origin.line 偏移归零；ADR 0032 的前置已解除 | ✅ **已完成** |
| **3b-a** | ✅ **修合成块 `line: 0`**（`aster-lang-ts#170`） | inline-if 的 thenBlock/elseBlock/If 从未赋 span，带着 `createEmptySpan()` 的 line 0 进 Core IR。用既有 `spanFromSources` 从子节点推导。**跨引擎 `origin.*.line` 分歧归零** | ✅ **已完成** |
| **3b-b1** | ✅ **两侧「span 吞尾随布局 token」均已修** | Java `aster-lang-core#160`（`lastNonLayoutToken`）+ TS `aster-lang-ts#171`（同名 helper，应用于 decl 3 处 + statement 22 处）。★**认知两次翻转**：先判「Java 错」→ 修 Java 后发现 TS 也错 → 实为**两边都错、方向一致而互相抵消**，门禁因此长期假绿。end.line 分歧 **0（假绿）→ 40（Java 修好）→ 1（两侧修好）** | ✅ **已完成** |
| **3b-b2** | ✅ **修二元表达式内层 span**（`aster-lang-core#161`） | ★**原判「未定语义」是错的**。打印两侧 IR 结构后确认：两引擎结构一致，`args[0]` 都是内层 `Call`（`"Hello, " plus name`），真实范围 L4–L5，**TS 对、Java 错**——`visitAdditiveExpr`/`visitMultiplicativeExpr` 给每个中间节点用 `spanFrom(ctx)`（整条表达式），内层因此继承外层结尾。改用 `mergeSpans(left, right)`。★教训：不该停在「两边数字不同 ⇒ 语义未定」，应先打印结构核对 | ✅ **已完成** |
| **3c** | 对齐 `origin.*.col` | ★**与步骤 2 纠缠，不能独立完成**。实测 727 条 col 分歧中：163 条是 `end.col=1` 的占位值（集中在 decls 79 / statements 29 / body 8），其余大量是 `start.col` 偏移（如 `ts=17 java=14`、`ts=19 java=16`）——后者正是 Canonicalizer 改列（折叠多空格、tab→2 空格）造成的，属**步骤 2** 的范畴。建议与步骤 2 合并规划 | 待办（应与步骤 2 合并） |
| **3.5** | **分阶段收紧 origin 豁免**（file → line → col） | 每收紧一格门禁就多守一格；避免「等全部对齐再启用」导致长期零守护 | ✅ **已完成并收尾**：`#137` 先收紧到 `file+line`（150/223 → 9/223），随 3b 各项修复逐步归零，最终 `aster-lang-test#140` **移除全部豁免**。现 parity gate 对 file / start.line / end.line **零豁免**守护 |
| **4** | Stable IR Node IDs | 唯一全新的一件 | 待办 |
| **5** | Canonical IR serialization | **复用**已有 `CanonicalJson`，不要重写 | 复用 |
| **6** | 接 LayoutMap（诗歌 PoC） | 注意它是新写一层 `canonical ↔ IRNode`，非升级现有 45 行 | 待办 |
| **7** | MappingIR / ProofIR + 双引擎 verifier | 见 §5 的硬约束 | 待办 |
| **8** | 自动 Solver / LLM | 维持提案的「最后才做」判断 | 待办 |

**与 ADR 0032 的关系**：0032 属于步骤 1–3 这一段（Java 侧 origin 贯通 + 锚点稳定化），应先于本 ADR 的步骤 4 落地。

---

## 5. 硬约束：MappingIR verifier 不得依赖 derived analysis

提案 §7 要求 `Verify_TS(mapping) == Verify_Java(mapping)`。

但 `IR-DIVERGENCE-LEDGER.md` 明确记录：双引擎在 **derived analysis 上本来就合法地不一致**——类型推断（TS 留 `TypeVar 'Unknown'`，Java 急切推断具体 `TypeName`）、`effectCaps`、lambda `captures`、`piiLevel/piiCategories`，两边策略不同且各自自洽。

因此：

> **MappingIR 的 verifier 必须被设计成只依赖「两引擎已经一致的那部分结构」**
> （即台账中 field-identical 的 202/207），**不得依赖 derived analysis**。

举例：判定 `"$10,000" ↔ Money(10000)` 时，若 verifier 需要确认目标节点类型是 `Money`，就把一个**已知分叉的层**拉进了必须字节一致的契约里——这会在第三阶段才炸。

**本约束必须现在写进 ADR，而不是等实现时发现。**

---

## 6. 尚未回答的问题

1. `IR_IGNORE_FIELDS=['origin']` 的真实原因（步骤 1 的产出）。在此之前，**跨引擎 origin 一致性是否可达**仍是未知数。
2. Stable Node ID 的生成规则：基于结构 hash（改一处则 ID 变，不利于 change impact）还是基于路径/序号（对重排不稳定）？这是一个**尚未展开设计**的真实权衡。
3. `ProofIR` 的失效语义：原文修改后，既有 proof 应当标 stale 还是直接失效？与本仓既有的「不可变审计 + 水位线」模式如何对齐？

---

## 7. 决策记录

- 本 ADR 的**步骤 0 已实施**（`aster-lang-core` 分支 `feat/ir-determinism-gate`）。
- 步骤 1–8 **待用户拍板**后再逐步展开。
- 方向本身（§0 的一句话）经评审**予以确认**，不需要推翻重做。
