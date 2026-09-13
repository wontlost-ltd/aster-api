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
| OriginMap v1 | **两侧均已完成大半**（Java `CoreLowering` 52 处 `spanToOrigin`；TS `lower_to_core.ts` 54 处 `withOrigin`）。★「TS 侧为 0」是初稿的误判，因只 grep 了 `core_ir.ts`——见 §2.3.1 |
| Stable IR Node IDs | **确实是零**——`CoreModel` 无任何 id/hash 字段。唯一全新的一件 |
| Canonical IR Serialization | **已存在且有字节级 parity gate**（`CanonicalJson.java` ↔ `aster-cloud/src/lib/canonical-json.ts`） |

而真正的拦路石**不在这三件里**，共三条（§3）。其中第一条已在本 ADR 提出过程中修复并补上门禁。

### 0.1 二次复核结论（2026-09-12）

方向判断维持不变；**距离判断需大幅上修**。自初稿以来落地 9 个 PR 后复核：

| 初稿判断 | 复核结论 |
|---|---|
| TS 侧 origin「从零」 | ❌ **误判**（只 grep 了 `core_ir.ts`）。实为 54 处注入，且设计优于 Java —— §2.3.1 |
| col 对齐需「Canonicalizer 全链路 OffsetMap」（137 个调用点）| ❌ **前提被推翻**。词形运算符翻译是**纯冗余**，正解是删掉它而非给它加偏移映射 —— §4.0.2 |
| Provenance-preserving Canonicalizer 是 OriginMap 的**前置条件** | 🟡 **降级**。全语料 368 文件实测：行数 **100%** 保持，真正移动代码列的仅 **6** 个 `ugly*` 压力样本 —— §4.0.3 |
| `origin` 跨引擎分歧 150/223 | 🟡 数字未变，但**成因已查清且高度集中**：两个**口径**问题（Module 占位 48 条 + 限定名范围 130 条），非语义分歧 —— §4.0.2 |

★**净效果**：§10 的第一块基础设施 OriginMap v1，其剩余距离从「新建一套
offset 基础设施 + TS 从零实现」缩小为「**统一两个 col 口径**」。
`origin.file` / `start.line` / `end.line` 已**零豁免**跨引擎守护。

★**未变的判断**：Stable IR Node IDs 仍是**真正从零**的一件（`CoreModel`
无任何 id/hash 字段，已复核），它才是下一步的实际工作量所在。

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

#### 2.3.1 ⚠️ 本节结论已于 2026-09-12 复核推翻

上述判断**当时正确、现已过期**。步骤 3a/3b 落地后复核：

| | Java | TS |
|---|---|---|
| origin 注入点 | `CoreLowering` 中 **52** 处 `spanToOrigin` | `lower_to_core.ts` 中 **54** 处 `withOrigin(...)` |
| 实现风格 | 52 处**逐个手写**注入 | **1 个泛型包装器** `withOrigin<T>` + 1 个 `spanToOrigin`，54 处复用 |

★原判断错在**只 grep 了 `core_ir.ts`**（该文件确实 0 命中），而 TS 的 origin
注入根本不在那里，在 `lower_to_core.ts`。这是「按文件名猜位置」导致的误判——
与本仓记录的「按名字 grep 判鉴权 54 个误报」同一类错误。

**修正后的结论**：两侧 origin 覆盖面**基本对等**（52 vs 54），且 TS 的
单一包装器设计**优于** Java 的 52 处手写注入（后者每加一种节点就多一个漏注入
的机会）。`origin.file`/`start.line`/`end.line` 现已**零豁免**跨引擎对齐，即是明证。

这把步骤 3 的剩余工作从「TS 侧从零实现」缩小为「**两个口径问题**」（见 §4.0.2）。

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
| `origin.*.col` | 🟡 150 样本分歧，但**成因已全部查清且高度集中**，见 §4.0.2 |

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

### 4.0.1 步骤 2 的前提被实测推翻（2026-09-12）

原文把步骤 2 写成「Canonicalizer 会 tab→空格、删注释、折叠多空格，所以坐标会偏」。
**逐项实测后，这个归因是错的**：

| 假设的成因 | 实测 |
|---|---|
| 删注释 | 只把注释行**置空**，行数与代码行的列都不变 |
| tab→2 空格 | 语料中未触发 |
| 折叠多空格 | 语料中未触发 |

全语料 368 个 `.aster`：228 个 canonicalize 后**逐字节不变**；140 个有改动，
其中 **139 个只是注释被置空**（不影响代码列），仅 19 个代码行真有变化。

**真正的成因是另一件事：Java 的 Canonicalizer 把词形运算符翻译成符号。**

```
原文      "  Return x plus y."     y 在第 17 列
Java 规范 "  Return x + y."        行缩短 3 字符 → Java 报 y 在第 14 列
TS  规范  "  Return x plus y."     不翻译 → TS 报 17 ✅
```

实证：50 个 col 分歧样本中，**全部**含词形运算符（`plus`/`minus`/`and`/`or`/
`modulo`/`at least` 等）。即 150 条分歧几乎来自**这一个**行为差异，
而非原文列举的那几项。

#### 尝试过但行不通的简化方案

试过「翻译时右补空格到等长」以保住列位（改动极小，无需 OffsetMap）。
实测：补空格后文本**照常解析**，且 `y` 的 `start.col` 正确回到 17。

**但该方案与既有设计冲突**：`finalWhitespaceNormalization`（canonicalize 的最后
一步）会调用 `normalizeWhitespaceWithState` 把连续空格折叠掉，padding 当场被抹平。
实测 `[PAD] src=plus tgt=+ consumed=4 emitted=1` 确实执行了，但最终输出仍是
`Return x + y.`。

绕开它需要让空白归一化「认得」padding 并放过——那等于在两个相互矛盾的不变量
（「列位保持」vs「空白归一化」）之间打补丁，比老老实实做 OffsetMap 更脆弱。

#### 结论：步骤 2 的正确范围

- **不是**「给 canonicalize 全链路加 OffsetMap」（137 个调用点）；
- **是**「在**运算符翻译**这一处记录 canonical↔原文的列偏移，并在生成 origin 时回映」。
  作用点收窄到 `translateSegment` 一个函数 + origin 生成侧。
- ⚠️ 仍需回答：TS 侧**不翻译**运算符，所以两侧的 canonical 文本本就不同。
  要对齐 col，要么 TS 也翻译（则两侧 origin 都偏离原文，等于把问题推后），
  要么两侧都做「翻译 + 回映」。**这是需要先定的设计问题。**

### 4.0.2 步骤 2 的设计问题已由实测解答；col 分歧只剩两种机械成因（2026-09-12 复核）

§4.0.1 末尾留了一个「需要先定的设计问题」：TS 不翻译运算符，两侧 canonical 文本
本就不同，是**两侧都翻译**还是**两侧都做翻译+回映**？

**这个问题已经不必回答了——正确解是第三条路：两侧都不翻译。**

理由是实测出来的，不是选出来的：grammar 本就认词形 token（`AsterParser.g4:643`
的 `PLUS_WORD`、`:647` 的 `TIMES_WORD`/`DIVIDED_BY_WORD` 等）。逐个比对
8 个运算符「词形 vs 符号」的 Core IR **逐字节相同**——翻译不影响能否解析，
也不影响语义，**唯一效果就是缩短行、移动列**。即它是纯冗余。

于是步骤 2 根本不需要 OffsetMap：把冗余翻译删掉即可。已落地三处：

| 修复 | 成因 | PR |
|---|---|---|
| 英语规范拼写运算符不再翻成符号 | `x plus y` → `x + y`，缩 3 字符 | `core#162` |
| 移除冗余的 `is-comparator` 变换器 | `x is at least y` → `x at least y`，缩 3 字符（lexer 本就吸收可选 `is`） | `core#163` |
| 标点归一化不吃 `!=` 前的空格 | `x != y` → `x!= y`，缩 1 字符（`!` 被当成句末感叹号） | `core#164` + `ts#172` |

**结果：tier1 语料 223/223 的 canonical 文本与源文本逐行等长——列位零偏移。**
即「canonicalize 改列」这一整类成因**已经消失**，步骤 2 实质完成，且代价是
**删代码**而非新增 OffsetMap 基础设施。

#### 剩余的 col 分歧：150 样本，但只有两种机械成因

剩下的 150 是**跨引擎**分歧（TS 的 col vs Java 的 col），与上面那类
（canonical vs 源文本）是不同的轴。逐条归类后：

| 成因 | 条数 | 性质 |
|---|---|---|
| Module 节点 `end.col`：`ts=1` vs `java=6` | 48 | TS 侧占位值，未真实计算 |
| 限定名 `target` 节点 `end.col` | 130 | 口径分歧，见下 |
| 其余（`args`/`expr` 少量） | 26 | 同源，待逐条确认 |

限定名那一类是**完全机械**的：`java − ts` 恰好等于限定前缀长度。

```
Return Text.concat("Hello, ", name).
       └──┘                             "Text." = 5 字符
ts=19  java=24   → 差 5
```

差值直方图 **5(45×) / 3(38×) / 6(32×) / 9 / 13 / 37** 与语料中实际出现的前缀
长度（`Text.`/`Http.`/`Date.`=5，`Ai.`/`Db.`/`IO.`=3，`Files.`/`Admin.`=6，
`Resource.`=9…）**逐一对应**。

即：**TS 报的是方法名 `concat` 的范围，Java 报的是整个 `Text.concat` 的范围。**
这是两侧对「`target` 节点指什么」的口径不同，不是任何一侧算错，也不是语义分歧——
定一个口径、改一侧即可。

★**对本 ADR 的意义**：§10 的第一块基础设施 OriginMap v1 要求
「普通 Aster source 做到 100% deterministic traceability」。该目标现在的距离是
**两个口径问题**（Module 占位 + 限定名范围），而不是原文假设的
「Canonicalizer 全链路 OffsetMap」。**这是数量级的差别。**

#### 4.0.3 §2.4「Canonicalizer 丢弃 offset 映射」的实际影响面（2026-09-12 全语料实测）

§2.4 据此把「Provenance-preserving Canonicalizer」定为 OriginMap 的前置条件。
对**全语料 368 个 `.aster`** 逐行实测后，影响面远小于该判断：

| 指标 | 数值 |
|---|---|
| canonicalize 后**逐字节不变** | **298 / 368**（81%）★三处冗余翻译删除后由 228 升至 298 |
| **行数不变** | **368 / 368（100%）** |
| 行数 + 代码行列位均不变 | 329 / 368（89%）|

其余 47 个逐行归类（判据：把原行的「注释及其前导空白」去掉后是否与 canonical 行等长）：

| 成因 | 文件数 | 是否影响 origin 正确性 |
|---|---|---|
| **仅注释被去除**（行内 `//` / `#`） | **41** | ❌ **不影响**——注释不产生任何 IR 节点，其所占列上没有可映射的 token |
| **真正移动了代码 token 的列** | **6** | ✅ 影响，但全部是 `ugly*.aster` 压力样本（连续双空格、行尾空白）|

★**结论**：在**真实语料**上，「canonicalize 移动代码列位」只剩 6 个刻意构造的
压力样本；Provenance-preserving Canonicalizer 因此**不是 OriginMap v1 的前置
条件**，可降级为「处理 ugly 输入的健壮性增强」。

⚠️ **但不可据此认为该问题不存在**：`ugly*.aster` 正是为「用户会写出难看代码」
而准备的。一旦对外开放任意人类文本（本 ADR §6 的核心主张），这类输入的比例
会远高于当前语料。**准确表述是「不阻塞 v1，但在开放任意 artifact 之前必须解决」。**

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
| **2** | ~~运算符翻译处记录列偏移并回映~~ → **删除冗余翻译** | ★**归因两次修正**（§4.0.1 推翻原归因，§4.0.2 推翻「需要 OffsetMap」这一前提）。终局结论：词形运算符翻译是**纯冗余**——grammar 本就认 `PLUS_WORD` 等词形 token，8 个运算符「词形 vs 符号」的 Core IR **逐字节相同**，翻译唯一效果就是缩短行、移动列。故正解是**删掉它**，不是给它加偏移映射。同类另两处（`is-comparator` 变换器、`!=` 被当句末感叹号）一并删除 | ✅ **已完成**（core#162/#163/#164、ts#172）|
| **3a** | ✅ **修 TS canonicalize 吞行**（`aster-lang-ts#170`） | 整文件 origin.line 偏移归零；ADR 0032 的前置已解除 | ✅ **已完成** |
| **3b-a** | ✅ **修合成块 `line: 0`**（`aster-lang-ts#170`） | inline-if 的 thenBlock/elseBlock/If 从未赋 span，带着 `createEmptySpan()` 的 line 0 进 Core IR。用既有 `spanFromSources` 从子节点推导。**跨引擎 `origin.*.line` 分歧归零** | ✅ **已完成** |
| **3b-b1** | ✅ **两侧「span 吞尾随布局 token」均已修** | Java `aster-lang-core#160`（`lastNonLayoutToken`）+ TS `aster-lang-ts#171`（同名 helper，应用于 decl 3 处 + statement 22 处）。★**认知两次翻转**：先判「Java 错」→ 修 Java 后发现 TS 也错 → 实为**两边都错、方向一致而互相抵消**，门禁因此长期假绿。end.line 分歧 **0（假绿）→ 40（Java 修好）→ 1（两侧修好）** | ✅ **已完成** |
| **3b-b2** | ✅ **修二元表达式内层 span**（`aster-lang-core#161`） | ★**原判「未定语义」是错的**。打印两侧 IR 结构后确认：两引擎结构一致，`args[0]` 都是内层 `Call`（`"Hello, " plus name`），真实范围 L4–L5，**TS 对、Java 错**——`visitAdditiveExpr`/`visitMultiplicativeExpr` 给每个中间节点用 `spanFrom(ctx)`（整条表达式），内层因此继承外层结尾。改用 `mergeSpans(left, right)`。★教训：不该停在「两边数字不同 ⇒ 语义未定」，应先打印结构核对 | ✅ **已完成** |
| **3c** | 对齐 `origin.*.col` | ★**已大幅推进，见 §4.0.2**。「canonicalize 改列」这一整类成因**已消除**（core#162/#163/#164 + ts#172，删冗余翻译而非加 OffsetMap）：**223/223 语料 canonical 与源文本逐行等长**。跨引擎残余 150 样本归为**两个口径问题**：Module `end.col` 占位（`ts=1` vs `java=6`，48 条）+ 限定名 `target` 范围口径（`java−ts` 恰等于 `Text.` 等前缀长度，130 条）。均为机械分歧，非语义 | 🟡 **部分完成**（步骤 2 部分已实质完成；余两个口径待定） |
| **3.5** | **分阶段收紧 origin 豁免**（file → line → col） | 每收紧一格门禁就多守一格；避免「等全部对齐再启用」导致长期零守护 | ✅ **已完成并收尾**：`#137` 先收紧到 `file+line`（150/223 → 9/223），随 3b 各项修复逐步归零，最终 `aster-lang-test#140` **移除全部豁免**。现 parity gate 对 file / start.line / end.line **零豁免**守护 |
| **4** | Stable IR Node IDs ✅ | 唯一全新的一件。★前置问题（是否真要做跨版本 change impact）**用户已拍板：要做**；ADR 0032 §6.1 同步修订 | 🟡 **Java 侧已完成**（`core#166`：复合键 `nodeId` + `contentHash`，5 变异验证 + 跨 JVM 确定性）；**TS 侧已完成**（`ts#173` + `test#141` 共享归一化，全语料 **223/223 样本、100% 节点一致**，见 §8.7/§8.8）|
| **5** | Canonical IR serialization | **复用**已有 `CanonicalJson`，不要重写 | 复用 |
| **6** | 接 LayoutMap（诗歌 PoC） | 注意它是新写一层 `canonical ↔ IRNode`，非升级现有 45 行 | 待办 |
| **7** | MappingIR / ProofIR + 双引擎 verifier | 见 §5 的硬约束 | 🟡 **第一刀已落地**（`ts#176/#177` + `core#169/#170`）：确定性 verifier 与 ProofIR 数据模型两侧对等，15 组输入跨引擎同判。**未做**：候选映射的自动生成（§3 的 LLM 部分，按 ADR 应最后做）、主体优先级仲裁（产品决策）。见 §9 |
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

### 5.1 实测：verifier 的合法输入面到底是什么（2026-09-13）

§5 写于「202/207 field-identical」的年代。现在跨引擎 **223/223 identical**——
但那是**归一化之后**的数字。归一化（`ir-normalize.ts`，两引擎与 parity 门禁
共用的单一真相源）剥掉的正是 derived analysis：

```
type, ret, retType, typeParams, typeInferred, retTypeInferred,
constraints, piiCategories, piiLevel, effectCaps, effectCapsExplicit, captures
```

即 §5 的约束**依然成立**，只是边界现在被这份清单**精确划定**了。

#### ★ADR §7 自己举的例子不可验证

原文举例 `"$10,000" ↔ Money(10000)`。实测：

| 要读的东西 | 归一化后是否存在 | 可否用于 verifier |
|---|---|---|
| `param.type` / `ret` | ❌ **被剥掉** | 不可 |
| 节点 `kind`（`Int`/`Decimal`/`Call`…）| ✅ 保留 | **可** |
| 字面量 `value` | ✅ 保留 | **可** |
| `origin`（file/line/col）| ✅ 保留 | **可** |
| `name`（函数名/变量名）| ✅ 保留 | **可** |

实测输出（`params[0]`）：

```
原始:     name, type, constraints, typeInferred
归一化后: name, annotations, retAnnotations, effects     ← type 已不在
```

所以：

- `"$10,000" ↔ Money(10000)` **不可验证**——`Money` 是**类型**，而类型层是
  合法分叉层，两引擎本就不一致。
- `"$10,000" ↔ Decimal("10000")` **可验证**——`Decimal` 是**节点 kind**，
  归一化后保留（实测 `{"kind":"Decimal","value":"100"}`）。

★这不是措辞问题：按原例实现，verifier 会在第三阶段才炸，而且炸在
「两引擎给出不同答案」这种最难排查的形态上。**MappingIR 的目标节点标识必须
用 `kind` + `value` + `nodeId`，不得用类型名。**

#### ★第二个坑：canonical 形态 ≠ 源码文本

`100.00m` 在 IR 里是 `{"kind":"Decimal","value":"100"}`——**尾随零被规范化掉**。
数值相同，文本不同。

对「文本 ↔ IR」的 verifier 意味着：**不能做字符串相等比较**，必须按值比较
（Decimal 按十进制数值、Int 按整数）。否则 `"$10,000.00"` 会验不过一个
数值上完全正确的 `Decimal("10000")`。

---

## 6. 尚未回答的问题

1. ~~`IR_IGNORE_FIELDS=['origin']` 的真实原因~~ → ✅ **已查清并解决**（§2.2.1 查明是 150/223 系统性分歧，§4.0/§8.7 逐项修完）。现 `origin` 的 file/line/col **零豁免**跨引擎守护，「跨引擎 origin 一致性可达」已由 223/223 实证。
2. ~~Stable Node ID 的生成规则~~ → **已实测，见 §8**。三种候选方案在六种真实编辑下的存活率矩阵已量化；结论是**没有单一方案能通吃**，必须做复合键。
3. ~~`ProofIR` 的失效语义~~ → **已有可落地的答案，见 §6.1**。

---

---

## 8. 步骤 4（Stable IR Node IDs）选型实测（2026-09-12）

§6 第 2 条原本是个「尚未展开设计的真实权衡」。现已用可复跑的实测回答
（`aster-lang-core` 的 `NodeIdSchemeSpikeTest`）。

### 8.1 ⚠️ 先解决一个与 ADR 0032 的表面冲突

ADR 0032「§6 不做的事」明写：

> **不引入独立的节点 ID 体系**（如给每个 AST 节点发 UUID）。位置信息已经有了，
> 再造一套是重复建设，且 UUID 跨编译不稳定

**这条不构成对本步骤的否决，但边界必须写清楚**：

| | ADR 0032 的 anchor | 本 ADR 的 Stable Node ID |
|---|---|---|
| 形态 | `"L21C5-L21C22"`（位置派生）| 结构派生（非位置）|
| 稳定范围 | **同一策略版本内** | **跨版本**（这正是 change-impact 的前提）|
| 服务对象 | Phase 1 条件漏斗的跨执行聚合 | §4 的双向导航 + change impact |
| 0032 反对的 | **UUID**（随机、跨编译不稳定）| 不是 UUID |

0032 反对的是「随机发号」，而非「结构派生的稳定标识」。且 0032 自己承认
其 anchor「改一行则下面全变」——**这恰恰是 change-impact 不能用它的原因**。

★**但必须承认 0032 的实质论点仍然成立**：若只需要同版本内聚合，anchor 就够了，
不该引入第二套。**Stable Node ID 的正当性完全取决于「是否真要做跨版本 change
impact」**。若该能力被砍，本步骤应当一并砍掉，而不是作为「基础设施」保留。

### 8.2 实测矩阵：节点身份存活率

基线 16 个节点（一条带 if/else 的规则），六种编辑，三种方案：

| 编辑 | A 结构 hash | B 结构路径 | C 命名作用域路径 |
|---|---|---|---|
| 改阈值 `10000→20000` | **10/16** 🔴 | 16/16 | 16/16 |
| 前面插入一条无关规则 | 16/16 | **5/16** 🔴 | 16/16 |
| **重命名规则** `approve→assess` | 14/16 | 16/16 | **1/16** 🔴 |
| 改分支内字面量 | **9/16** 🔴 | 16/16 | 16/16 |
| 删掉 else 分支（16→13 节点）| 9/16 | 13/13 | 13/13 |
| 中间插入一个新分支（16→24）| 15/16 | 13/16 | 13/16 |

三种方案的定义：

- **A 结构 hash**：节点子树内容（剥掉 origin）的 SHA-256。
- **B 结构路径**：根到节点的 JSON 路径，如 `$.decls[0].body.statements[0].cond`。
- **C 命名作用域路径**：同 B，但数组元素**优先用 `name` 作路径段**（`decls{approve}`），
  无名时才退回下标。

### 8.3 结论：没有单一方案能通吃

每种方案都有一个**灾难性**场景，且三者的失效场景**互不重叠**：

- **A 对「改内容」极度敏感** —— 而 change-impact 恰恰要问「改了阈值，谁受影响」。
  若节点自身 ID 随内容变，就无法表达「还是那个节点，值变了」。
- **B 对「重排」极度敏感** —— 前面插一条规则，后面所有 ID 全变（5/16）。
- **C 对「重命名」极度敏感** —— 改个规则名，整棵子树身份归零（**1/16**，最差）。

★**因此步骤 4 的正解是复合键，不是三选一**：用 C 做主键（对重排稳健、对改值稳健），
**重命名时必须显式声明**（rename 是一次有意的身份迁移，本就该记录，而不是指望
算法猜出来）。A 的结构 hash 则退居为**内容指纹**——用于回答「这个节点变了没有」，
而不是用于**标识**节点。两者职责分离：

```
nodeId   = 命名作用域路径   ← 回答「是哪个节点」（跨版本稳定）
contentHash = 结构 hash     ← 回答「它变了没有」（change impact 的信号）
```

这正好对应 §4 的需求：`$10,000 → $20,000` 时，`nodeId` 不变（还是那个阈值节点）、
`contentHash` 变（所以下游 `PaymentApproval.threshold` 标 stale）。

### 8.4 ★关于这份数据本身的一个教训

首轮测量里「删掉 else 分支」一行是 **16→16 节点、三方案全 16/16** ——
看起来是最漂亮的一行，实际是**假数据**：该编辑的 `replace` 根本没匹配上
（Java 文本块会去掉公共缩进，实际缩进与我写死的不符），测的是「未编辑」。

因此测试里加了一条断言**守测量装置本身**（每个编辑必须真的改变源码），
并做了变异验证：把缩进改回不匹配 → 断言变红。

★**这是「量具自身的缺陷」的又一例**：在拿到任何「100% / 全绿 / 无变化」的
漂亮结果时，先问「我的测量装置真的在测我以为的东西吗」。

### 8.5 未决

- 上述矩阵基于**一个**合成样本（16 节点）。推广到全语料前应扩样本。
- ~~重命名的「显式声明」机制尚未设计~~ → ✅ **已落地**（`core#168` + `ts#175`），见 §8.9。
- ~~**前置问题仍是 §8.1**~~ → ✅ **用户已拍板：要做**（2026-09-12）。ADR 0032 §6.1 已同步修订。

### 8.6 落地（2026-09-12，`aster-lang-core#166`）

复合键方案已实现，**未改 `CoreModel`**——ID 是从 Core IR JSON 派生的独立产物，
故不触动 IR parity 基线。hash 复用既有 `CanonicalJson.canonicalHash`
（已有版本前缀 + 已做 TS↔Java parity），不自造。

| 类 | 职责 |
|---|---|
| `NodeIdMap` | 计算 `nodeId`（命名作用域路径）+ `contentHash`（子树指纹，剥 origin）|
| `ChangeImpact` | 跨版本 diff → `MODIFIED` / `ADDED` / `REMOVED` + `staleAncestors` |

两条非显然的设计决定：

- **stale 只向上传播**。改了 if 里的阈值，是**包含它的规则**需要重审；
  把后代也标 stale 会把影响面夸大到整棵子树。
- **重命名如实暴露为 `REMOVED` + `ADDED`，不猜测**。猜错会把两个不同节点
  当成同一个——那比「识别为新节点」更危险。

验证：Java 全量 **1603 passing**；**5 个变异全部变红**且由预期用例捕获；
**跨 JVM 确定性**（三次独立启动，24 个节点 id+hash 逐字节相同——专防
`Map.copyOf` SALT 那类随启动漂移的缺陷，否则 change impact 会全量报 stale）。

**仍未做**：显式 rename 声明机制。

### 8.7 TS 侧对等实现与全语料实证（2026-09-12）

`aster-lang-ts#173` 补齐 TS 侧，`aster-lang-test#141` 抽出两侧共用的归一化规则。

#### 一个必须先解决的约束

两引擎的**原始** Core IR 字段本就不同，且这是 ADR 0016 §B/§C 认定的**合法分岔**：

| | 独有字段 |
|---|---|
| Java `Func` | `annotations` / `retAnnotations` / `piiLevel` / `piiCategories` |
| TS `Func` | `retTypeInferred` |
| Java `Param` | `annotations` |
| TS `Param` | `constraints` / `typeInferred` |

所以 **contentHash 必须对「归一化后」的 IR 取 hash**。而归一化规则此前只存在于
`parity-tier1.mjs` 内部（未导出）。若在 TS 侧另写一份，就会出现本仓反复记录过的
**单源漂移**：两处规则各自演进后，门禁说「一致」而 contentHash 说「不一致」，
**且两边都不报错**。故先把规则原样抽到 `packages/js/src/ir-normalize.ts`（`#141`），
门禁改为 import；抽取前后 parity 结果逐字节一致。

#### 全语料实证（补上 §8.5 的「全语料推广」）

两侧走**同一份**归一化规则与**各自**的 nodeId 实现，对 tier1 全部 223 个样本比对：

| 指标 | 初次测得 | **修完 5 个缺陷后** |
|---|---|---|
| 完全一致的样本 | 218 / 223 | **223 / 223** |
| 节点级一致率 | 8600 / 8661（99.3%） | **8645 / 8645（100%）** |
| TS 解析失败 | 0 | 0 |

初次测得的 5 个分歧样本（`eff_valid_all_caps` / `fetch_dashboard` /
`interop_overload` / `interop_sum` / `login`）与 parity 门禁报告的
`divergent-exempt` 样本逐一对应。**当时判为「既有技术债」，随后逐个排查发现
这个判断过于宽容——它们是 5 个互不相同的真缺陷**，详见 §8.8。

另有两条独立证据：

- **未归一化时 21/24 一致**，不一致的 3 个恰好是携带推导层字段的节点
  （`ret` 及其祖先）——这从反面印证了「必须归一化」的判断是对的，而不是
  一个为了让数字好看而加的步骤。
- **canonical hash 黄金向量**：5 组输入的期望值取自 Java `CanonicalJson`
  实跑，TS 侧逐字节相同。★期望值**不是**从 TS 自产输出回填的——那会让测试
  退化成「TS 和它自己一致」，恒绿且毫无意义。

**结论**：ADR 0037 §7 的 `Verify_TS == Verify_Java` 在 Stable Node ID 这一层
**已有实证基础**。

### 8.8 那 5 个分歧样本：5 个真缺陷，0 个合法差异（2026-09-12）

§8.7 初测时把它们归为「既有技术债」。逐个追到**运行时**后，这个判断被推翻：

| 样本 | 真实缺陷 | 性质 |
|---|---|---|
| `interop_sum` / `interop_overload` | `Long` 序列化成 JSON number → JS 侧 `9007199254740993` 静默变 `…992`；且超范围 Long 让 `canonicalHash` 抛错 → **本 ADR 的 Stable Node ID 在这类程序上完全不可用** | 高 |
| `eff_valid_all_caps` | 裸表达式语句被降成 `Return` → **函数提前返回**，其后的写文件/写库永不执行且不报错 | **最高（执行期行为错误）** |
| `login` | TS 缺 UFCS → 两引擎**实际传参个数不同**（2 vs 3） | 高 |
| `fetch_dashboard` | `as async` 被当成函数调用，而运行时没有 `async` 这个函数 | 中（该形态从未被执行，属潜伏） |

修复：`aster-lang-core#167`、`aster-lang-ts#174`。

#### ★门禁为什么没发现：两个不相干的概念被混为一谈

`divergent-exempt` 此前被排除在失败集合之外，注释称其为「推导分析层差异，
仅供参考」。**实测不成立**——该标记并非人工确认，而是**自动**由样本元数据里的
`evalExempt` 推导（见 `classifyIr`）：

```
evalExempt 的本意 = 这个样本不参与 eval-parity（不执行它）
被当成的含义      = 这个样本的 IR 分歧可以接受
```

73 个样本因为「不执行」而顺带获得了「IR 可以随便分叉」的通行证。

变异验证（注入同一个 async 缺陷）：

| | 退出码 |
|---|---|
| 保留豁免 | **0（绿——看不见）** |
| 移除豁免 | **1（红）** |

即门禁**结构上就不会**因这类缺陷变红。豁免已移除（`aster-lang-test#142`），
移除后 main 上 `--mode=ir --full` 与 `parse` 均退出 0。

#### 对本 ADR 的意义

- `origin` 的 **file / start.line / end.line / col** 与 **Stable Node ID**
  现在都在**零豁免**的门禁守护下。
- §7 的 `Verify_TS == Verify_Java` 在 Stable Node ID 这一层达到 **100%**。
- ★教训：**豁免清单里的条目默认应假设是待办，而不是已确认无害。**
  「已知分歧」这个标签本身不含任何证据，时间越久越像结论。


### 8.9 显式 rename 声明（2026-09-12，`core#168` + `ts#175`）

§8.5 的最后一项未决。

#### 问题

命名作用域路径对重命名敏感。实测 16 节点样本：改一条规则的名字 →
**31 条变更**（15 REMOVED + 15 ADDED + 1 MODIFIED），而其中
**14/16 个节点的 contentHash 逐字节未变**。

#### ★为什么必须显式声明，不能自动推断

直觉方案是「按 contentHash 自动配对」。**实测不成立——contentHash 不唯一**：

```
Rule alpha, produce:      Rule beta, produce:
  Return 1.                 Return 1.

→ $.decls{alpha}.body 与 $.decls{beta}.body 的 contentHash **完全相同**
```

把 `alpha` 改名为 `gamma` 后，`beta.body` 的 hash 在新版能匹配到**两个**候选，
无法判定谁是谁——自动配对会把两条规则的身份**互换**，而且**不报错**。

★**把两个不同的节点当成同一个，比「识别为新节点」危险得多**：前者给出
**错误**的溯源答案，后者只是丢失历史关联。故定为：**宁可少认，不可错认**。

该判断由专门用例钉住（`contentHashIsNotUniqueSoAutoMatchingIsUnsound`）——
若将来 hash 变得唯一，它会变红，届时可重新评估自动方案。

#### 实现

「路径段替换」：把旧版 `$.decls{oldName}…` 改写成 `$.decls{newName}…` 再做
常规 diff，**整棵子树一次性迁移**。两个边界：只替换完整 `{name}` 段
（否则 `approve` 误伤 `approveAll`）；冲突声明直接拒绝，不静默取其一。

#### 效果

| | 变更条数 |
|---|---|
| 无声明 | **31** |
| 有声明 | **2**（`Func` 自身 + `Module` 根，均 MODIFIED）|

★剩下这 2 条**不是**残留噪声：`name` 是节点内容的一部分，改名本身就是真实
变更。初稿断言「零变更」被测试打回——那是把「消解改名噪声」错当成了
「假装什么都没发生」。

#### 验证

core 1601 passing、TS 1698+87 passing + golden 0 FAIL；两侧各 3 个变异全红；
**跨引擎一致**：Java 与 TS 对同一输入均 `31 → 2`，nodeId 与 kind 逐条相同。

★**至此 §8.5 三项未决全部清空**，Stable IR Node ID（步骤 4）完成。
下一步是 §7 的 MappingIR / ProofIR 与双引擎 verifier。


### 6.1 ProofIR 失效语义：沿用本仓既有的「不可变 + 水位线」（2026-09-13）

原问题：「原文修改后，既有 proof 应当标 stale 还是直接失效？」

**答案：都不删，只加水位线。** 这不是新发明——本仓已有成熟先例，且两处机制
均已在当前代码中核实存在：

| 既有机制 | 位置 | 语义 |
|---|---|---|
| BYOK 额度重置 | `users.byokQuotaResetAt` | 重置 **≠** 删用量记录；只盖水位线，此后只统计 `createdAt >= max(当月初, resetAt)` 的行 |
| 审计日志 | `lib/audit-log.ts` 的 `logAuditEvent` | 只追加，不修改、不删除 |

映射到 ProofIR：

```
proof 记录本身      不可变、只追加      ← 它是「当时确实验证过」的历史事实
proof 的有效性      由水位线判定        ← 不改写历史，只标注「自某版本起不再适用」
```

具体规则：

- **不标 stale，也不删**。proof 带 `verifiedAgainst: {nodeId, contentHash}`。
- **有效性是算出来的，不是存出来的**：当前 IR 里该 `nodeId` 的 `contentHash`
  若与 proof 记录的不同 → 该 proof **对当前版本不适用**（但它对当时那个版本
  依然是真的，历史不被否定）。
- 判定直接复用**已落地**的 `ChangeImpact.diff`（§8.6/§8.9）：`MODIFIED` 即
  「内容变了 → 旧 proof 不再适用」；`staleAncestors` 即「哪些上层结论需要重审」。

★**为什么不能标 stale（原地改写）**：proof 的价值恰恰在于「在某个确定的版本上、
由某个确定的主体、按某条确定的规则验证过」。原地把它改成 stale 会**销毁这条
历史事实**——下次审计时无法回答「那次到底验没验过」。这与本仓审计链
不可改写的既有立场一致。

★**为什么 `ChangeImpact` 已经够用**：它回答的正是「这个节点变了没有」，而
proof 的失效判定就是这个问题。不需要为 ProofIR 另造一套失效检测——那会变成
第二套规则，与 `ChangeImpact` 必然漂移（本仓已有多起单源漂移事故）。

#### 端到端实测（2026-09-13）

不是纸面推演，已用真实 IR 跑通：

```
proof 锚定  $.decls{approve}.body.statements[0].cond.args[1]  hash=33234ccc…
改阈值 10000 → 20000
新版同 nodeId  hash=04ad2d19…
→ proof 对当前版本仍适用? false          ← 正确判定为不适用
→ ChangeImpact 判定:      MODIFIED       ← 不是 REMOVED+ADDED，身份稳住了
→ 需重审的上层:  cond → statements[0] → body → decls{approve} → $
```

最后一行正是 §4 要的「谁已经 stale」：由内向外的完整祖先链，**无需新增任何
机制**。

**仍未决**：proof 的**主体与规则**如何编码（谁验的、按什么规则），以及
多 proof 冲突时的仲裁。这属于 MappingIR/ProofIR 的数据模型设计，不在本条
（失效语义）范围内。

---

## 7. 决策记录

- 本 ADR 的**步骤 0 已实施**（`aster-lang-core` 分支 `feat/ir-determinism-gate`）。
- 步骤 1–8 **待用户拍板**后再逐步展开。
- 方向本身（§0 的一句话）经评审**予以确认**，不需要推翻重做。

---

## 9. 步骤 7 第一刀：MappingIR verifier + ProofIR（2026-09-13）

`aster-lang-ts#176/#177` + `aster-lang-core#169/#170`，两侧对等。

### 9.1 只做「机器能证明的那一类」

按 §3 的分工——**AI 可以提出映射，但不能定义什么叫正确**——本次实现的是那个
**确定性 verifier**，三态判定：

| 判定 | 含义 |
|---|---|
| `VERIFIED` | 机器已证明：文本与目标节点的值精确对应 |
| `REVIEW_REQUIRED` | 机器证不了，交人——**不是**「错」 |
| `REJECTED` | 机器已证伪：文本与节点值**矛盾** |

带业务含义的映射（`"requires approval"` ↔ 某个 `If` 节点）一律
`REVIEW_REQUIRED`——**不猜**。这正是 §3 的
「Human reviews only what machines cannot prove」。

### 9.2 跨引擎同判（§7 的核心要求）

15 组输入逐条比对，两引擎判定 **15/15 完全一致**，覆盖：人类数字装饰
（`$`/千分位/空白）、值矛盾、非精确值节点、`Double` 不可逆、尾随零规范化、
超安全整数精度、布尔、负数、解析失败。

★期望值取自**对侧引擎实跑**，不是从本侧输出回填——后者会让测试退化成
「自己和自己一致」。

### 9.3 三个实测得出的设计边界

1. **`Double` 不参与机械验证**：源码文本与 IR 值不可逆（`1.0` → `1`、
   `1e3` → `1000`）。机器无法证明「这段文本就是这个 Double」，故交人，
   而不是用近似规则假装能证。
2. **按值比较，不按文本**：`100.00m` 在 IR 里是 `value:"100"`。字符串相等
   会让 `$10,000.00` 验不过数值上完全正确的 `Decimal("10000")`。
3. **全程字符串运算，不过浮点**：超安全整数的 `Long` 一旦过一次浮点就丢精度。
   测试钉了「相差 1 必须能证伪」。

### 9.4 ProofIR：不可变 + 水位线

按 §6.1 落地。`subject`（谁验的）与 `rule`（按什么规则，带 version）**分开**
记录——同一主体可按不同规则判定，而「按哪条规则」恰是审计最需要的。

仲裁规则：**只考虑仍适用的 → 取最新**。★**不按主体优先级**（如「专家 > 机器」）
——那需要先定义跨主体的权威序，是**产品/合规决策**。并存的分歧由 `conflicts`
字段**如实暴露**。

### 9.5 仍未做

- **候选映射的自动生成**（§3 里 LLM/启发式的部分）——按 ADR §10 应当**最后**才做。
- **主体优先级仲裁**——需要产品决策：机器判 `REJECTED` 而专家判 `VERIFIED` 时谁赢？
- ~~**SourceIR**~~ → ✅ **已落地**（`ts#179`），见 §10。

---

## 10. SourceIR：人类文档的结构化表示（2026-09-13，`ts#179`）

§9.5 的最后一项。

### 10.1 它与 LayoutMap 不是同一个问题

| | LayoutMap（`aster-dev/src/lib/layout-map.ts`）| SourceIR |
|---|---|---|
| 对象 | **本来就是 Aster 源码**的文本（《静夜思》）| **从未是 Aster** 的人类文档 |
| 构造 | **手写** span 列表 | **机械推导** |
| 形状 | 平铺、全文档逐字符覆盖 | **嵌套**（标题层级）|
| 规模 | 20 字的诗可以 | 50 页 Policy 也可以 |

实测 `JINGYESI_LAYOUT` 是 12 条手写 span、每个字符都要列出——该模型在真实
Policy 上不成立。

★这澄清了 ADR §5 原文「LayoutMap → Transformation/Origin Map」的升级路径：
LayoutMap **不是** SourceIR 的前身，两者并存、各管一段。

★但那句措辞掩盖了一个**真实存在**的缺口——「原文 → 变换 → canonical」这一步
的 offset 映射从未被记录。该缺口已单独立项：**ADR 0038（DEFERRED）**，
触发条件是「对外开放任意人类文本」。

### 10.2 唯一硬约束：offset 必须可回切原文

`MappingIR.TextSpan` 是**字符偏移**的，故 SourceIR 每个节点都必须携带能逐字节
切回原文的 span。本模块**不做任何文本改写**——不 trim、不规范化、不转义。

`verifyCoverage` 机械验证三条不变式：

1. `text` 逐字节等于 `document.slice(span)`
2. 叶子 span 互不重叠
3. **父的 span 覆盖子**

### 10.3 ★第三条是实测踩出来的

`HEADING` 原本只覆盖标题那一行：

```
doc.h[0]             [0,8)      ← 标题行
  doc.h[0].h[0].p[0] [17,42)    ← 子段落，在父之外！
```

→ `nodeAtOffset` 无法下降 → 「点击 `$10,000` 定位所在节点」返回 `DOCUMENT`
而不是那个段落，**双向导航直接失效**。撤掉修复后 `verifyCoverage` 报 26 处违规。

### 10.4 真实文档验证

`aster-cloud/docs/p0a-signability-policy.md`（8KB、8 标题、8 列表项、19 段落块）：
解析出 **28 个节点，覆盖违规 0**。

### 10.5 仍未做

- ~~**Java 侧对等实现**~~ → ✅ **已落地**（`core#172`）：真实 Policy 文档上
  两引擎各解析出 28 个节点、**逐行一致 28/28**（nodeId + kind + span 全同），
  覆盖违规均为 0。至此 §7 的 SourceIR / MappingIR / ProofIR **三层均双引擎对等**。
- **Entity/Quantity 层** → 🟡 **Quantity 已落地**（`ts#180`），**Entity 仍未做**。
  ★原文把两者并列、统称「LLM 真正该上场的地方」——**实测后这句话只对一半**，
  见 §11。
- **非 Markdown 载体**（docx/pdf）——应当先转 Markdown 再进本模块，而不是在
  这里堆解析器。
- **transform 层（原文 ↔ canonical 的 offset 映射）**——见 **ADR 0038**，
  状态 DEFERRED。今天不需要是因为本仓所有实际输入要么就是 canonical、要么与
  canonical 逐行对齐（223/223 行、217/223 列）。

---

## 11. Quantity 可机械抽取，Entity 才需要 LLM（2026-09-13，`ts#180`）

§10.5 原文把 Entity/Quantity 并列，统称「LLM 真正该上场的地方」。
**实测后发现这句话只对一半。**

### 11.1 实测：两者的可抽取性完全不同

一份典型付款政策（含金额/百分比/时长/日期/角色）：

| 类别 | 数量 | 可机械抽取？ |
|---|---|---|
| 金额 `$10,000` / `$50,000` / `$25` | 4 | ✅ 有形态特征，正则 **100%** |
| 百分比 `1.5%` | 1 | ✅ |
| 时长 `24 小时` | 1 | ✅ |
| 日期 `2026-01-01` / `2026-12-31` | 2 | ✅ |
| **角色**「财务经理」「部门主管」 | 2 | ❌ **无任何可靠形态特征** |

「财务经理」与「财务报表」在字符层面无从区分——**只能靠语义识别**。

### 11.2 结论：分开做，各用各的手段

```
Quantity  有形态特征  →  机械抽取，零 AI        ← 本次已落地
Entity    无形态特征  →  LLM 提出 + 人复核      ← 仍未做
```

`EntityCandidate` 接口已声明但**不提供实现**——按 §3，那是 LLM 提出候选、
由确定性 verifier 判定（对 Entity 只能给 `REVIEW_REQUIRED`）的部分。
★把边界**写进类型系统**：调用方一看就知道 Entity 必须外部传入，而不是
指望本模块变出来。

### 11.3 全链路打通

```
SourceIR 定位 → Quantity 抽取 → MappingIR 判定
  $10,000  在 PARAGRAPH  → VERIFIED
  24 小时   在 PARAGRAPH  → REVIEW_REQUIRED   ← 机器证不了「时长」对应裸数字
```

`value` 用**十进制字符串**而非 number，与 MappingIR 同口径（大额不丢精度）。

### 11.4 ★三条测试第一版是假门禁

| 守卫 | 为什么假 | 怎么修 |
|---|---|---|
| 重叠检查 | 样本里四类模式**互斥**，重叠永不发生 → 删掉检查照样绿 | 改用 `$1.5%`（MONEY 匹配 `$1.5`、PERCENT 匹配 `1.5%`，**真重叠**）|
| MONEY 优先级 | `$10,000` 下**没有竞争者** → 挪动顺序照样绿 | 同上 |
| 规范化失败 | `$ 待定` 连**模式**都匹配不上，测不到那道门 | 改为如实记录当前行为（不做语义校验）|

★并修正一条**不准确的注释**：初稿写「MONEY 必须排在纯数字之前，否则
`$10,000` 会被拆成 `10` 和 `000`」——**那是错的**，本模块根本没有「纯数字」
模式。注释若声称一个并不存在的保护，会误导后来者以为某处有约束而不敢动。

### 11.5 仍未做

- ~~**Entity 抽取**~~ → ✅ **已落地**（`ts#181`），见 §12。
- ~~**Java 侧 Quantity 对等**~~ → ✅ **已落地**（`core#173`）：同一份付款政策，
  两引擎输出 **`diff` 无差异**（8 个数量的 kind/text/value/unit/span 全同，
  含重叠判别用例 `$1.5%`）。
- **更多 Quantity 类别**（重量/长度/温度…）——按真实文档需求增量加，
  不预先堆砌。

---

## 12. Entity 提出器：三段式落地（2026-09-13，`ts#181`）

ADR 链上**唯一**需要 AI 的位置。用户拍板：**用当前的 LLM 机制**，
**类别体系走三段式**（不预先定义）。

### 12.1 复用既有机制，不新建

`aster-lang-ts/src/ai/llm-provider.ts` 已有 `LLMProvider` 抽象与两个实现
（openai / anthropic）。本次直接复用，没有引入新的 SDK 或配置层。

`provenance.ts` 的 `ProvenanceMetadata`（model/provider/timestamp）与
`ProofIR.ProofSubject` 天然对应——`proposedBy` 形如
`llm:anthropic/claude-...`，可直接落进 proof 记录。

### 12.2 三段式在代码里可见

| 段 | 由谁 | 产物 |
|---|---|---|
| ① 提出 | LLM（`proposeEntities`）| `EntityCandidate` —— **候选**，不是结论 |
| ② 判定 | `verifyEntityCandidate()` | **恒 `REVIEW_REQUIRED`** |
| ③ 复核 | 人 | `ProofIR`（`subject=DOMAIN_EXPERT`）|

★§3 的原话是「AI 可以提出映射，但**不能定义什么叫正确**」。本次把它写进
**类型系统**：返回类型是 `EntityCandidate` 而非 `Proof`，调用方拿不到任何
「已验证」的东西。

★第②段写成**显式函数**而非省略——为了让「谁也不能跳过它」在代码里可见。
它恒返回 `REVIEW_REQUIRED` 不是偷懒，是 §5.1 的直接结论：verifier 只能读
`kind`/`value`/`name`/`origin`/`nodeId`，而 Entity 的目标是**语义**对应
（「财务经理」↔ `Role.FinanceManager`），类型层不在可依赖范围内。

### 12.3 ★幻觉闸门是确定性的，不依赖模型自觉

LLM 声称的每条候选都机械校验 `document.slice(start, end) === text`，
对不上直接丢弃，**不做纠正**——「猜一下它想说什么」比丢弃更危险。

拦下的两类最常见幻觉：

- **文本对但位置错** → 会让双向导航指向错误的地方
- **文档里根本没有的文本** → 凭空编造的实体

被丢弃的条目**如实报告**（`rejected` 字段）：静默吞掉会让「LLM 产出了不合规
内容」这件事消失，而那恰恰是评估模型可靠性最重要的信号。

### 12.4 类别体系：刻意不定义

`proposedKind` 是 LLM 给的**自由字符串**，本模块不校验、不归一化、不映射到枚举。

★理由：类别体系是**领域决策**，由第③段的人确定。过早把它固化成枚举，会让
LLM 的输出被硬塞进错误的格子里——而那种错误比「类别不统一」难发现得多。

### 12.5 其他设计约束

- **`temperature=0`**：同一文档反复提出应**可复现**。否则「上次为什么提了这条」
  永远说不清，审计无从谈起。
- **不重试 / 不自我修正**：格式不合规直接丢弃并报告。
- **不碰 Quantity**：systemPrompt 明确要求不输出金额/日期等——那是 §11 的
  确定性模块的职责，两边都抽会产生重复候选。

### 12.6 验证

全量 1753 + 87 passing，golden 0 FAIL。**测试用假 provider 驱动，不打真实
LLM**——判定逻辑必须可离线验证。变异 5 个全红（撤幻觉闸门 3 红 / 静默丢弃
3 红 / temperature 改 0.7 / scope 偏移不平移 / verifier 改判 VERIFIED）。

### 12.7 仍未做

- ~~**Java 侧对等**~~ → ✅ **已落地**（`core#174`）：按 §7 的区分**只移植判定**
  （`verifyCandidate` 恒 REVIEW_REQUIRED + `validateProposals` 幻觉闸门），
  **不移植 LLM 调用**——「LLM 生成的 candidate 不要求一致，Verifier 的结果
  必须一致」。跨引擎测试钉的是**判定**：四种类别（含「完全没见过的类别」）
  两侧都给 REVIEW_REQUIRED。
- ~~**真实 LLM 的端到端验证**~~ → ✅ **已完成**（2026-09-13 手工验收），见 §12.8。
  仍**不进 CI**——需凭据与配额，单次调用 ~60s，且结果不可复现。

### 12.8 真实 LLM 端到端验收（2026-09-13）

用一个兼容 OpenAI 协议的测试端点（模型 `grok-4.6`）实跑三段式。

#### 结果：每条设计都经住了真实模型

```
① 提出：接受 5 条，丢弃 0 条            耗时 60.7s
   ✓ "单笔付款"        Subject     @10  在 PARAGRAPH
   ✓ "需要财务经理审批"  Obligation  @26  在 PARAGRAPH
   ✓ "财务经理"        Role        @28  在 PARAGRAPH
   ✓ "由部门主管批准"   Obligation  @41  在 PARAGRAPH
   ✓ "部门主管"        Role        @42  在 PARAGRAPH
② 判定集合: REVIEW_REQUIRED             ← 机器从不声称结论
③ 人复核：本模块不产出结论
Quantity: $10,000                       ← 确定性模块独占，Entity 未碰
```

- **类别体系**（`Subject`/`Obligation`/`Role`）由 LLM 自由给出，未被硬塞进枚举
  ——印证 §12.4「刻意不定义」的判断。
- `proposedBy` 记为 `llm:openai/grok-4.6`，可直接落进 ProofIR。
- **分工不重叠**：`$10,000` 只由 §11 的确定性模块抽取。

#### ★幻觉闸门是真起作用的，不是理论上的

同一模型在**较短提示**下给出过一组偏移（`$10,000` 声称 `start=7`）。
把那组偏移喂进闸门：

```
接受 0 条，丢弃 3 条
  ✗ 位置 0 上的实际文本是 "# 付款"，与声称的 "单笔付款" 不符
  ✗ 位置 7 上的实际文本是 "策\n\n单笔付款"，与声称的 "$10,000" 不符
  ✗ 位置 18 上的实际文本是 "10,0"，与声称的 "财务经理" 不符
```

**全部拦下。** 这正是「文本对但位置错」——LLM 最常见、也最危险的幻觉：
它会让双向导航**静默**指向错误字符。★这类错误若不在入口拦住，下游任何
verifier 都发现不了（文本本身是合法的，只是位置错了）。

#### 顺带修的一个缺口

`OpenAIProvider` 原本把基址硬绑官方域名，接不上任何自建/代理端点。
已加可选 `baseURL`（`ts#182`）。

#### 为什么仍不进 CI

单次调用 ~60s、需凭据与配额、且结果**不可复现**（同一提示不同上下文长度下
偏移都不一样，见上）。作为**手工验收**保留。

---

## §12.9 ReDoS 全面复查：从「新代码」扩到「既有代码」

§12.6 只对本 ADR 新增的 6 个模块做了 ReDoS 加固。本节记录把同等深度的扫描
推广到**两个引擎的既有代码**之后的结果——**又找出 5 处二次回溯，其中 1 处
可端到端复现 7 秒**。

### 结论先行

共 **8 处**二次回溯，跨两个引擎：

| 引擎 | 位置 | 形态 | 改前（n=40000） | 改后 |
|---|---|---|---|---|
| TS | `transformers.ts` `POSSESSIVE_RE` | `[\p{L}]…` 无左锚 | 2552ms | 0ms |
| TS | `transformers.ts` `SET_TO_RE` | multiline `^(\s*)` | 2594ms | 0ms |
| TS | `transformers.ts` `RESULT_IS_RE` | 同上 | 同上 | 0ms |
| TS | `formatter.ts` `/[ \t]+$/` | 行尾空白 | 2424ms | 0ms（改线性扫描） |
| Java | `EnglishPossessiveTransformer.POSSESSIVE` | 同 TS | 同 TS | 线性 |
| Java | `SetToTransformer.SET_TO` | multiline `^(\s*)` | 1361ms | 1ms |
| Java | `ResultIsTransformer.RESULT_IS_LINE_START` | 同上 | 同上 | 1ms |
| Java | `Canonicalizer` 三条空白正则 | `\s+` 无左锚 | 87717ms | 2ms |

**端到端可达性（★两侧结论不同，必须分开说）**：

| 引擎 | 全空行源文件 n=40000 | 撤修后增长率 | 端到端可达？ |
|---|---|---|---|
| Java | 6906ms → 14ms | 3.9× | **是** |
| TS | 5.2ms → 5.5ms | 1.8×（撤修后仍线性） | **否** |

★TS 侧 `set-to`/`result-is` **单点是二次的**（直接打 transformer：
10000→166ms、20000→641ms、40000→2605ms，×3.9），但**端到端打不穿**——
默认 `canonicalize()` 根本不启用这两个 transformer（实测：输入
`Set x to 1` 输出原样不变）。Java 侧 `builtin/en-US.json` 的
`postTranslationTransformers` 里有它们，TS 侧默认词典没有。

★**这是一处既有的跨引擎装配分叉**（`main` 上行为相同，非本次引入），
与 ReDoS 无关，但它意味着：同一份源码在两个引擎里走的 transformer 链不同。
`tier1-parity` 223/223 绿说明现有语料没覆盖到 `Set x to` 这类写法。
**建议单开 issue 跟进**，不在本次修复范围内。

故 TS 侧这两处修复应如实定性为「**纵深防御**：单点二次已消除，
但当前装配下不可达」——一旦哪天 TS 词典补上这两个 transformer，
修复即刻生效。`POSSESSIVE_RE` 与 `formatter` 两处则是**真实可达**的
（`english-possessive` 在 en/zh/de 三个生产词典里都启用）。

★两侧的所有格 / set-to / result-is 模式必须**逐字一致**——它们都在
canonicalize 链路上，两引擎输出要字节相同（tier1-parity 门禁把这条钉住了）。

### ★三个值得记住的教训

**① 「快了很多」≠「复杂度变了」。**
Canonicalizer 那三条我第一版只把 `\s+` 改成占有量词 `\s++`，耗时降到 1/15，
看起来像修好了。继续测 20000→40000 发现**仍是 4×**——二次项根本没消掉。
真凶不是 `\s+` 内部的回退，而是 `find()` 会从**每个**空白位置重新起跑。
只有左锚 `(?<!\s)` 才把 n 次扫描降为 1 次。**判据必须是增长率，不是绝对耗时。**

**② 守卫会被上游环节遮蔽。**
Canonicalizer 那三条单独测是二次的，但端到端**打不穿**——上游 `SPACE_RUN_RE`
先把 `[ \t]` 折叠成单个空格了。我因此一度以为「修了个不可达的问题」。
换成 `\r`（不被折叠，且会被规范化成 `\n`）才暴露出真正的 7 秒路径——
而那条路径的真凶**是另外两个文件**。

**③ 凭「哪条正则看起来危险」构造载荷会找错方向；必须实测 + 采样。**
我前后猜错四次（CRLF 规范化、`segmentString`、`finalWhitespaceNormalization`、
五个 canonicalize 子阶段），逐个计时全是线性。最后用**栈采样**一次定位：
2951/2966 个样本落在 `StringSegmenter.replaceOutsideStrings`，顺着调用方
才找到 MULTILINE 下的 `^(\s*)`。

### 门禁：量增长率而非绝对耗时

新增 `redos-budget.test.ts`（TS）与 `CanonicalizerReDoSTest`（Java），
都遵循两条原则：

- **量增长率**：绝对毫秒阈值在共享 CI 上要么抖动成 flaky、要么定得太松而
  形同虚设。灾难性回溯的特征是**超线性增长**，而增长率对机器速度不敏感。
  门槛取 3.0×（线性=2 有 50% 余量，二次=4 被稳稳挡住）。
- **门禁自身必须可变红**：两边都有一条自检用例，把**已知二次**的函数喂给
  同一套判定逻辑；若判定通过，说明量具没连上被测对象。

### ★这两个门禁的第一版都是假绿，值得单独记

- **Java 版**：`PATTERNS` 数组把生产正则**抄了一份**。撤掉生产代码的全部
  三处修复重跑，**4/4 依然全绿**——测试量的自始至终是那份副本。
  改为**反射读取生产字段**后，撤修即变红（已实证）。
- **Java 自检用例**：空的双重循环被 JIT **整段消除**，耗时恒为 0，自检
  反而先红了——这次红得对，它准确报告了「量具没连上」。改成写 `volatile`
  字段后才真的接上。
- **TS 版**：property 断言写的是「只减不增」+「减掉的是伪片段」。拆掉锚点后
  `now === before`，**两个方向都空洞成立**，依然全绿。我只断言了变化的
  **方向**，没断言变化**发生过**。补上「必须存在使新旧结果不同的见证输入」
  后才变红。

### 顺带修掉的一个真缺陷

`QuantityIR` 的左锚不只是性能修复。property-based 测试给出反例 `"1.51.5%"`：

```
旧模式 → ["51.5%"]   ← 从第 4 位起跑，抽出一个**凭空造出来的值**
新模式 → []          ← 左锚看到前面是 "."，拒绝起跑
```

旧模式在畸形数字上会**伪造数量**（`12.34.56%` → `34.56%`、`..5%` → `5%`），
而这些值随后会被 MappingIR 拿去与 IR 节点比对。**左锚顺带修掉了一个
「静默伪造数量」的缺陷。**

我原先基于 9 组人工样本断言「加锚语义完全不变」，那个结论是**错的**；
property 测试在几千组输入内就把它推翻了。这也是 §12.6 的方法论补丁：
**改了正则就不能用人工样本论证等价性。**

### 语义等价性的证据

| 改动 | 证据 |
|---|---|
| Canonicalizer 三条（左锚+占有量词） | 固定种子随机 **600000 组**比对，其中 **197366 组确有替换发生**（证明样本非空洞），逐字节零分歧 |
| `SET_TO` / `RESULT_IS_LINE_START` | 16 组人工样本（15 组确有替换）逐字节一致；且 `[ \t]*` 在语义上**更准确**——该捕获组的用途是保留**行缩进**，缩进按定义只含空格与制表符，跨行吃换行本来就是错的 |
| `POSSESSIVE_RE` 左锚 | 见下方「④ 左锚的宽度」——随机 300000 组、16715 组确有替换，`(?<!\p{L})` 分歧 0 |
| `SET_TO_RE` / `RESULT_IS_RE`（TS） | 随机 200000 组，其中 **95956 组确有替换**，逐字节零分歧 |
| `formatter.ts` 线性扫描 | 随机 200000 组零分歧 |

### ★④ 左锚的宽度：多排一个字符就是误伤

`POSSESSIVE_RE` 我第一版写的是 `(?<![\p{L}0-9_])`，看着更"严密"，实际**错了**：

```
_x's y    旧模式 → _x.y      ← 从 `x` 起跑的合法匹配
          我那版 → _x's y    ← 被整个挡掉
```

模式首字符是 `[\p{L}]`——**不含**数字与下划线，所以左锚也不该排除它们。
**左锚的正确宽度 = 「该模式首字符能取的字符集」，多一个字符都是误伤。**

实证（随机 300000 组、16715 组确有替换）：
`(?<!\p{L})` 分歧 **0**；`(?<![\p{L}0-9_])` 分歧 **1895**。

★这个错误是**等价性检查**抓出来的——我手写的前 10 组人工样本**全绿**。
再次印证：改了正则就不能用人工样本论证等价性。

### ★⑤ 「样本零替换」会让等价性结论完全落空

第一次跑所有格的随机等价性检查，两种锚宽**都是 0 分歧**，我差点据此收工。
但同一份输出里写着「**0 组确有替换**」——随机字符串几乎不可能凑出
`'s ` 这个 token，600000 次比对全是「空串 vs 空串」。

改用**片段拼装**生成器（把 `"'s "` 当成高频 token）后，替换率升到 16715 组，
两种锚宽的差异立刻显形（1895 组分歧）。

★**「零分歧」必须与「替换确实发生过」一起看**，否则是空洞结论。
本仓的每一处等价性验证都同时报告这两个数字。

### 回归验证

- Java 1662 tests / TS 1857 tests 全绿
- **双引擎 IR parity：223/223 identical，0 divergent**（改的是两侧
  canonicalizer，必须确认字节级仍然一致）

  ★**但这句话不能当作本节三条正则的安全背书**。独立审查者逐样本扫了
  `corpus/tier1-parity/manifest.json` 的全部 223 个样本：

  ```
  含 `Set X to`       的样本 = 0
  含 `The result is`  的样本 = 0
  含所有格 `'s `       的样本 = 0
  含 exotic 空白       的样本 = 0
  ```

  **对这三条正则的覆盖率是零。** 「223/223 identical」为真，但它对本节的
  改动**不构成证据**——真正的证据是各自的随机等价性比对（见上表）与
  变异验证。★把无关门禁的绿当成背书，是本仓记过的「度量用前缀 glob 替
  门禁背书」的同类错误，此处记录以免再犯。

  **建议**：给 tier1-parity 语料补 `Set X to` / `The result is` /
  所有格 / exotic 空白 四类样本，否则这三条模式在两引擎间永远无人守护。
- 每一处修复都做了**变异验证**：撤掉修复→门禁变红→还原→变绿

---

## §12.10 独立审查发现的漏网之鱼

§12.9 的扫描由**代码作者本人**执行，覆盖面有系统性盲区。交由**独立审查者**
重扫后，又找出下列缺陷。以下每一条我都**独立复现过**（贴的是我自己的实测数据，
不是审查者的）。

### ★① TS `canonicalizer.ts` marker 还原循环 —— 本轮最高危，且非正则

```ts
for (const [marker, keyword] of keywordMarkers) marked = marked.replace(marker, keyword);
```

`.replace(string, string)` 每次**从偏移 0 重扫并重建整串**。多词关键词
（`greater than` / `at least` 等）每出现一次就造一个 marker，M 随输入线性增长
→ **O(M·n)**。

我的独立复现（含**对照基线**，证明归因是 marker 而非"文件大"）：

| 输入 | 含多词关键词 | 对照（等长无关键词） |
|---|---|---|
| n=1000 | 5ms | 3ms |
| n=2000 | 11ms (2.10×) | 6ms (2.06×) |
| n=4000 | 29ms (2.64×) | 12ms (1.90×) |
| n=8000 | **278ms (9.69×)** | 24ms (1.94×) |

规模化后（`If a is greater than b.` 重复）：94KB→31ms、188KB→291ms、
375KB→1049ms、**750KB→3368ms**，每翻倍 ×3.2〜3.6。

**★跨引擎不对称**：同一 750KB 载荷 Java 侧仅 **156ms** 且增长 ×2.27（线性）
——Java 未采用 marker 方案。**21× 分歧**，TS 独有。

**可达性**：`canonicalize()` 是 TS 侧**每一条编译路径**的第一步。

**修法**：单趟回调替换
`marked.replace(/\x00KW(\d+)\x00/g, m => keywordMarkers.get(m) ?? m)`。

### ★② `cst/cst_printer.ts` —— §12.9 修过的形态未传播到的副本

| 模式 | n=10000 | 20000 | 40000 | 增长 |
|---|---|---|---|---|
| `/\s+([.,:!?;])/g` | 162ms | 668ms | 2571ms | ×4.0 |
| `/[ \t]+(?=\n)/g` | 224ms | 895ms | 3599ms | ×4.0 |
| `/\.\s*:/g` | 0ms | 0ms | 0ms | 线性（**不是缺陷**） |

前两条与 Java `Canonicalizer.PUNCT_*`、`formatter.ts` 行尾空白**是同一形态**，
§12.9 只修了其中一份。可达路径：`lsp/formatting.ts` → `printCNLFromCst`。

★一处与审查者的分歧：审查者称 `reflow` 默认 true，实读
`lsp/formatting.ts:29,57` 是 `!!settings.format?.reflow`，**默认 false**。
仍属真实攻击面（用户可开启），但严重度应下调。

**修法**：加左锚 `(?<!\s)` / `(?<![ \t])`。等价性实证：随机 200000 组，
**113013 / 15976 组确有替换**，逐字节零分歧。

### ★③ `formatter.ts` 两条 `^\s*...` multiline —— 真实可达，但**无法靠改正则修复**

```ts
.replace(/^\s*Return\s+<expr>\s*\./gm, ...)
.replace(/^\s*Return\s+<[^>]+>\s*\./gm, 'Return none.')
```

单点实测 5000→40ms、10000→160ms、20000→641ms、40000→2561ms（×4.0）；
**端到端 `formatCNL` 亦为二次**：5000→85ms、10000→326ms、20000→1319ms（×3.8〜4.0）。

★**与 §12.9 的 `SET_TO` 不同，这里不能照搬 `[ \t]*`**。原因：`SET_TO` 的
`(\s*)` 只是**捕获缩进**，而这里的 `^\s*` 是**被消费掉的匹配的一部分**——
它会把前导空行一并吞掉删除：

```
"\n\nReturn <expr>."   旧 → "X"        ← 空行被删
                       [ \t]* → "\n\nX"  ← 空行保留
```

我试了三种改法，**全部不等价**（随机 200000 组）：

| 候选 | 分歧数 |
|---|---|
| `^[ \t]*` | 22407 / 42848 |
| `(?<!\s)^\s*` | 18722 / 36131 |
| 去掉 `m` flag | 21121 |

**结论**：这两条需要**代码层重写**（先定位 `Return <expr>.` 再一次性左吞空白），
不属于「改正则」范畴。**本次不修，单独立项**——在没有等价性证据的情况下动它，
风险高于收益。

### ★④ `QuantityIr` 的 `claimed` 列表 O(m²) —— 两引擎都有，但当前不可达

`claimed.some(...)` 在每个匹配上线性扫已占位区间 → O(m²)。
审查者实测 Java 47→70→225→841→**6288ms**、TS 25→41→153→581→2362ms（均 ×4）。

**可达性（如实说明）**：该类目前在两仓内**均无生产调用方**（Java 仅测试引用，
TS 未从 `index.ts` 导出）。按「不可达不算缺陷」的判据归为观察项，
**一旦接线即升为高危**。

★**附带发现：§12.6 的 ReDoS 门禁对此结构性失明。** 现有载荷
（`"$" + "1".repeat(40000)`）**只有一个匹配**，`claimed` 恒为 1，O(m²) 项
永不激活。审查者实测该载荷 21ms 稳过断言，而**多匹配**载荷比率 6.17 立刻变红。
**门禁量的是对的东西（增长率），但语料有盲区**——这是「样本无从区分」的又一例。

### ★⑤ `RegexGuard` 可被**相邻量词**绕过（两引擎）

守卫只检查「被量词修饰的**分组**」`(a+)+`，但歧义**无需分组**即可产生：

| 模式 | 守卫 | 表现 |
|---|---|---|
| `a*a*a*a*a*a*a*a*a*a*b` | **ACCEPTED** | 指数，24 字符 = 1759ms |
| `(a+)+b` | REJECTED | — |

**严重度分层**：Java 侧 `replaceAllWithTimeout` 看门狗**确实兜底**
（实测 2006ms 抛 `RegexTimeoutException`）→ 降级为 2 秒 DoS；
**TS 侧无兜底**——其自称四层防御中第 4 层 `MAX_MATCH_INPUT` **零调用方**，
单线程 JS 下无限挂死。

### 明确**未**发现缺陷的（实测线性，不凑数）

- `StringSegmenter` **自身**：`segment()` / `isEscaped` / `replaceOutsideStrings`
  四类载荷全部 ×2。★§12.9「只修调用方传入的模式」的判断**是对的**。
- Java `Canonicalizer` 端到端四类载荷、`SourceIr.parse`、`CanonicalJson`
  （深嵌套被 Jackson `maxNestingDepth=1000` 上游遮蔽）
- Java 全树无循环内字符串 `+=`；`contains()` 全部落在 `Set`

### ★方法论：作者自扫的三个系统性盲区

1. **只扫正则**。①④ 都是**非正则**的二次算法，且 ① 是本轮最高危。
2. **只扫自己改过的文件**。② 是同一形态在**另一份副本**里的漏网。
3. **载荷构造受自己的假设支配**。④ 的门禁用单匹配载荷，
   结构性地测不到 O(m²)；③ 必须用**换行** run 而非空格 run 才能打穿。

★**独立审查者的价值正在于不共享作者的盲区**，这三类靠自审都发现不了。

---

## §12.11 立项项清零：4 项 vuln 全部修复

§12.10 列了 4 项「已立项未修」。本节记录把它们清零的过程，以及**两处我自己
先前下错的结论**。

### ★① 「无等价改法」这个结论下早了

§12.10 我写 formatter.ts 两条 `^\s*` multiline「**无法靠改正则修复**」，
依据是三种朴素改写都不等价（22407 / 18722 / 21121 组分歧）。

**那个结论对一半。** 把两条**分开**看，各自都有解：

| 模式 | 替换体 | 结论 |
|---|---|---|
| `^\s*Return\s+<expr>\s*\.` | `match.replace(...)`，**保留**匹配到的空白 | 缩进无需被 `\s*` 消费 → `(?<=^\|\n)([ \t]*)` **完全等价**（200000 组 / 92489 组确有替换，零分歧），2570ms→0ms |
| `^\s*Return\s+<[^>]+>\s*\.` | 常量 `'Return none.'`，**会吞掉**前导空行 | 正则层确实无解 → 代码层线性扫描 |

**教训：把两个形态相似但语义不同的东西当成一类，会得出过宽的否定结论。**

### ★② 「找到等价写法」≠「修好了」

模式2 我找到了一个**逐字节等价**的写法 `^(?:[ \t]*\n)*[ \t]*`
（200000 组 / 135236 组确有替换，零分歧）——**但它仍是二次，而且慢 3 倍**：

```
原式    5000:40ms   10000:161ms  20000:643ms   40000:2571ms  (×4.0)
等价式  5000:134ms  10000:545ms  20000:2173ms  40000:8721ms  (×4.0)
```

根因是结构性的：`m` 标志下 `^` **合法地**落在全部 N 个行首，每次都要向后扫。
任何保留 `^...m` 的写法都逃不掉。

**等价性与线性必须同时满足，缺一不可。** 最终改为代码层线性扫描
（正则只在**本行内**工作，跨行的空白由代码一次性左吞）：2571ms → 0.0ms。

### ③ `cst_printer.ts` 第三处是**补测**出来的

| 模式 | 改前 | 改后 |
|---|---|---|
| `/\s+([.,:!?;])/g` | 2571ms | 0.3ms |
| `/[ \t]+(?=\n)/g` | 3599ms | 0.3ms |
| **`/\n+$/g`** | **2297ms** | 0.3ms |

★第三条**我和独立审查者上轮都漏了**。它只在「**失配后缀**」载荷上暴露
（`'x' + '\n'.repeat(n) + 'y'`——结尾不是行尾）；用「匹配成功」的载荷
（结尾就是换行）完全看不见。这正是 §12.10 ① 那条教训的再次印证。

### ④ `RegexGuard` 相邻量词：误伤比漏网更糟

`a*a*a*...b` 通过筛查，24 字符输入 1705ms（每 +2 字符翻倍）。
TS 侧**无看门狗兜底** → 单线程 JS 下无限挂死。

判据刻意保守：**只认文本完全相同的相邻原子**，不做字符集交集分析。
理由是守卫收得过紧会**静默丢掉**用户的合法 overlay 规则，比漏一个 ReDoS
更难发现。验证口径也据此设计：

```
攻击模式   5/5 拒绝
合法模式  15/15 通过
生产词典  79 条正则  零误伤   ← 这条比前两条更有说服力
```

### ⑤ `QuantityIr` O(m²)：门禁语料的盲区

多匹配载荷 `'1% '.repeat(n)`：16000→183ms（×5.2）→ 7ms，32000 仍线性。

★该缺陷**逃过了 §12.6 的 ReDoS 门禁**：那里的载荷是
`'$' + '1'.repeat(40000)`——**只有一个匹配**，`claimed` 恒为 1，
O(m²) 项永不激活。**门禁量的是对的东西（增长率），但语料有盲区。**

#### ★两处如实记录，不编造覆盖率

1. **`higher` 那半边重叠判定在当前四条 RULES 下不可达**——50 万组随机输入
   实测 **0/500000** 次改变结果（同一模式的匹配互不重叠且自左向右扫，
   后来的匹配不可能起点落在某个已占位区间之前）。它是为将来新增模式保留的
   防御。我变异掉它测试照样全绿，**那是因为变异本身是 no-op，不是测试无力**
   ——这个区分很重要，已写进代码注释，不为它编造覆盖。

2. **对照基准的第一版是循环论证**：调 `QuantityIr.extract` 再从它自己的输出
   重算，2/2 全绿却什么都没证明。已改为**独立复刻整条流水线**，并用
   「去重完全失效」这个真变异验证它确实会变红（报出具体反例
   `"$1.5%3 天3 天.3 天"`）。

### ⑥ 一处 flaky：写死阈值扛不住并发

marker 门禁写死 `ratio < 3.0`，**单独跑 2.0× 稳过**，但**全量套件并发跑**时
涨到 3.40× 直接变红。写死的绝对阈值在共享 CI 上必然演变成 flaky，
最后被人加 skip。

改为判**相对对照基线的倍率**：对照组与被测组**同时**受调度抖动影响，
比值把机器负载约掉了。缺陷态信号本就极强（×9.69 vs 对照 ×1.94），
取 2.0 倍余量既挡得住回归、又扛得住噪声。连跑 5 次 1877/1877 稳定。

### 回归验证

- Java **1667** tests / TS **1877** tests 全绿（TS 连跑 5 次）
- 双引擎 IR parity **223/223 identical，0 divergent**
- 每处修复均做变异验证，且**先 grep 构建产物确认变异落地**再判定
  （本轮至少两次因「变异没落地」险些误判）

---

## §12.12 立项：formatter sanitize 的两条二次正则（不修，附缓解依据）

### 问题

`src/formatter.ts` 的 sanitize 阶段有两条模式：

```ts
.replace(/^\s*Return\s+<expr>\s*\./gm, match => match.replace(/<expr>/, 'none'))
// 以及
input.replace(/^\s*Return\s+<[^>]+>\s*\./gm, 'Return none.')
```

二者都呈**二次**增长：5000→40ms、10000→161ms、20000→643ms、40000→2571ms（×4.0）。

### 为什么不修

**二次是语义固有的。** `m` 标志下 `^` **合法地**落在全部 N 个行首，而 `\s*`
跨行向后扫 —— 总功必然是 O(行数 × 剩余长度)。我与独立审查者合计试过 6 种改法：

| 改法 | 等价？ | 线性？ |
|---|---|---|
| `(?<=^\|\n)([ \t]*)` | ✖ 窄字母表零分歧、**宽字母表全分歧** | ✓ |
| `(?<!\s)\s*`（去 `^`） | ✖ 84063/300000 | ✓ |
| `(?<!\s)^\s*`（保 `^`） | ✖ 30133/300000 | ✓ |
| `^(?:[ \t]*\n)*[ \t]*` | ✓ 零分歧 | ✖ 仍二次且**慢 3 倍** |
| `(?<=^\|[\n\r…])\s*`（审查者独立找的） | ✓ **500000 组、全 25 个 `\s` 成员，零分歧** | ✖ 641ms，**与原式完全一样** |
| 代码层左扫（两版） | ✖ 136116 / 121272 | ✓ |

**正确性 > 性能**：这两条决定用户代码被改写成什么。宁可慢也不能错。

### ★缓解依据（我第一版写错过，此处为更正后的版本）

我原先写「二次只在**恰好含 `<expr>` 占位符**时触发」。**那是错的**：

```
无占位符 n=40000 → 2572ms
含占位符 n=40000 → 2572ms      ← 完全一样
```

`^\s*` 在**每个行首**回溯，与后面有没有 `Return <...>` 毫无关系。
**我陈述的那个缓解因素提供零保护。**

真正的缓解有两条，都已实证：

1. **可达路径受限**：sanitize 链在 `mode !== 'lossless'` 时执行
   ——★**包括不传 opts 的默认调用**。我第一版写成「只在
   `{ mode: 'normalize' }` 下执行」，**漏了默认那一档**。实测：

   | 调用形态 | n=40000 |
   |---|---|
   | `formatCNL(s)`（默认，不传 opts） | **5232ms** ← 我原措辞未涵盖 |
   | `formatCNL(s, { mode: 'normalize' })` | 5233ms |
   | `formatCNL(s, { mode: 'lossless' })` | 4ms |

   **LSP 侧确实不可达**（`src/lsp/formatting.ts:34,60` 默认走 `'lossless'`，
   且是**直接调** `buildCstLossless` 而非经 `formatCNL`；构建抛异常时
   被外层 `catch` 接住返回 `[]`，实测 0.8ms 快速失败）。
   **内部调用方清单（全仓穷举，已实测核对）**：

   | 调用点 | mode | 可达？ |
   |---|---|---|
   | `src/lsp/formatting.ts:41,65` | `normalize` | 仅 LSP 显式选 normalize 时 |
   | `scripts/format-examples.ts:31` | **无 opts（=默认）** | ✔ |
   | `scripts/test-comments-golden.ts:21` | `normalize` | ✔ |
   | `scripts/test-lossless*.ts` | `lossless` | ✘ 不可达 |

   后两个脚本都**未挂任何 npm script**、只读仓库自有 fixture，严重度低。

   ★**最强的一条缓解**（审查者补充，我已核实）：`formatCNL`
   **不在 `src/index.ts` 的公开导出面内**（`dist/src/index.js` 零命中）
   → **第三方消费者无法直接调到**。

   ★另注：`formatCNL` 的 lossless 分支在 `buildCstLossless` 抛异常时会
   **fall through 到 normalize**（用 `U+FFFF` 可触发，实测 5212ms）。
   即「传了 `lossless` 也不等于绝对安全」。

2. **触发面窄**：全仓 `.aster`/`.cnl` 语料中 `Return <` **零命中**，
   占位符确属历史遗留格式。

★但第 2 条只说明「很少被触发」，**不减轻单次调用的二次代价**——
攻击者仍可构造一份全空行文件走 normalize 路径。这是立项而非忽略的理由。

### 门禁处置

**刻意不为这两条设增长率门禁**。设一条「明知会红」的断言只会变成 flaky 噪音，
最后被人加 skip，反而掩盖真问题。风险以本节记录，修复时再补门禁。

---

## §12.13 三项必修的收尾

独立审查者二轮给 82/100（有条件通过），三项必修已全部落实：

### (a) 字符类里的括号骗过深度扫描 —— **我上一轮新引入的回归**

`([)])*([)])*b` / `([(])*([(])*b` 被放行（22 字符 794ms）。
加分组原子支持时，深度配对扫描没跳过字符类，`[)]` 里的括号被当成分组括号。

★**上一版遇 `(` 直接 `return null`，反而不会误算深度**——这个缺陷完全是
我"修复"出来的。教训：**扩大解析器的识别范围，就等于扩大它出错的面**。

修复后：攻击 11/11 拒绝，合法 12/12 通过，审查者扫 **5121 条唯一合法正则零误伤**。

同时记录一处**已知边界**：判据「文本完全相同」挡不住 `(?<n>a)*(?<n2>a)*b`
这类语义等价但文本不同的相邻原子（796ms，ACCEPT）。要覆盖需字符集/结构
等价分析，会显著抬高误伤风险——而误伤**静默丢掉**用户的合法 overlay 规则，
比漏一个 ReDoS 更难发现。故刻意停在保守判据，把缺口写明。

### (b) 死函数带着假绿测试 —— 同一个坑换了地方

回退后 `replaceLegacyPlaceholderReturn` 成了未使用的导出，但它带着：
- 仍声称「与原正则逐字节零分歧」的文档注释（实际 71754/300000 分歧）
- 一条**仍然绿**的等价性断言（5 条 fixture 全用 `space`/`\t`/`\n`）

审查者用我**新字母表仍未覆盖**的 16 个字符打它：**16/16 全部产生分歧**，
那条测试照样绿。★**「fixture 语料决定结论」这个假绿只是搬了家。**
函数与测试块已一并删除。

### (c) 缓解论证错误 —— 见 §12.12

### (d) 一条「装饰性」用例被我记成了守卫

我给字符类修复补的反向用例 `([)])(a)*b`，审查者指出它**没有证明力**。
我复刻「跳过 / 不跳过字符类」两版逐条对拍，证实了：

```
([)])(a)*b        跳过=false  不跳过=false   ← 两态相同，变异时照样绿
([)])*([)])*b     跳过=true   不跳过=false   ★能区分
([)]a)*([)]a)*b   跳过=true   不跳过=false   ★能区分
```

**所有能区分两态的样本都在「攻击」方向**；误伤方向**结构上不存在**可区分样本
——因为字符类跳过只会让**更多**模式被识别为原子，不会让合法模式被拒。

处置：保留该用例（它仍是有效的**非回归**样本），但在注释里写明
「不得计入反向守卫证据」，并另补 `([)]a)*([)]a)*b`（原子内字符类与普通字符
混排）作为真正能区分的攻击样本。两仓同步。

★**教训：「我加了一条反向用例」不等于「反向有守卫」。**
判据仍是那一条——**撤掉被守的修复，它会不会红**。

### ★本轮最该记住的一条

我上一轮"修复"守卫时**新引入了两处漏判**，而那两处恰恰是上一版
（更笨的写法）天然免疫的。**每一次让代码"更聪明"，都要问一句
「它现在能出什么新的错」。**

---

## §12.14 ★更正：parity 口径不实（223/223 实为 221/223）

独立审查者在最终一轮跑了我一直没跑的那条命令，发现**我报的 parity 数字是错的**。

### 事实

| 命令 | 结果 |
|---|---|
| `parity-tier1.mjs --mode=ir --full` | **223/223 identical** ← 我一直跑的 |
| `parity-tier1.mjs --mode=ir`（浅层指纹） | **221/223，divergent 2** ← 我从未跑过 |

两处分歧：

```
tier1-equivalence/policies/eff_alias_import.aster    declNames.tsOnly: ["Db","Http","Time"]
tier1-equivalence/policies/eff_alias_unmapped.aster  declNames.tsOnly: ["Http"]
```

且**均未登记**在 `IR-DIVERGENCE-LEDGER.md` / `DIVERGENT-MANIFEST.md`
（我实测确认：两文件里 grep `eff_alias` 零命中，它们只出现在语料 manifest 里）。

### 归因：既有分歧，非本轮引入

切到本轮工作之前的 `main` 重跑浅层模式，得到**完全相同的 221/223 与同样那 2 个样本**。
→ 与本轮 ReDoS 工作**无关**，不是回归。

### ★我错在哪

`--full` 与不带 `--full` **量的不是同一件事**：前者是 ADR 0016 的
**字段级归一化 IR diff**，后者是**浅层结构指纹**。两者都真实，但我：

1. **只跑了其中一条**，却把结论写成无限定词的「双引擎 IR parity 223/223」；
2. 十几轮里反复引用这个数字为自己的改动背书，**从未交叉验证另一条**。

这与本轮反复抓到的假绿同源——**单一量具 + 不交叉验证 = 结论比证据强**。
审查者前两轮都如实写了「parity 未验证（需跨仓 runner）」，我却默认自己那条就够了。

### 正确口径

> **双引擎 IR parity：字段级（`--full`）223/223 identical；
> 浅层指纹（`--mode=ir`）221/223，2 处既有分歧待登记。**

本 ADR 前文 §12.9〜§12.13 中所有「223/223 identical」均指**字段级**口径，
此处统一更正说明；**不回改历史小节**（那些记录本身没错，只是缺限定词）。

### 待办（独立事项，非本轮范围）

- 把 `eff_alias_import` / `eff_alias_unmapped` 登记进 ledger，或修复该分歧
- 发版/汇报口径统一为「221/223 + 2 处已登记分歧」

---

## §12.15 收尾：最终评审 95/100 的剩余扣分

### (a) `src/formatter.ts:69` 注释与 ADR 互相矛盾（已修）

我花 4 分在 ADR 里改正了「只在 `mode: 'normalize'` 下执行」这个窄措辞，
但**源码注释里还留着一份旧的**。审查者指出：同一个点，两处文档互相矛盾。

成因：我本轮刻意不碰生产代码（这个决定本身是对的），导致注释没同步。
→ 已补改。

### (b) `scripts/test-comments-golden.ts:21` 未列入可达路径清单（已补）

与 `format-examples.ts` 同类（未挂 npm script、只读仓库自有 golden fixture），
ADR 的定性覆盖它，但按名单完整性该列出。

### ★(c) 审查者补充的一条**对结论有利**的事实

`formatCNL` **不在 `src/index.ts` 的公开导出面内**（`dist/src/index.js` 零命中）
→ **第三方消费者无法直接调到**。这比 ADR 现有的两条缓解都更强，已补记。


---

## §12.16 两项立项的收尾（2026-09-13）

### ① `eff_alias` parity 分歧 —— **量具缺陷，非引擎分叉**

根因查清：`CoreIrFingerprintCli` 的 `declNames` 只读 `decl.name`，
而两引擎的 Import **字段名本就不同**（该 CLI 的类注释里早已写明）：

| 引擎 | 字段 |
|---|---|
| TS | `name` / `asName` |
| Java | `path` / `alias` |

→ Java 侧 Import **恒不入 declNames**，浅层指纹凭空报出
`declNames.tsOnly: ["Db","Http","Time"]`。

★**底层 IR 其实一致**：字段级口径（`--full`）一直是 223/223，
因为 `ir-normalize.ts` 早有 `Import.name→path` / `Import.asName→alias`
别名表。**是浅层量具没跟上字段级口径。**

修法：`declNames` 取名字时两种字段名都认。

**★这不是「把红的调绿」**——已变异验证：给 TS 侧 Import 名字加后缀制造
**真分歧**，量具立刻报出 `["Db_MUTANT",...]` → 221/223。
修好的是量具的**盲区**，不是把它的**眼睛蒙上**。

结果：**浅层 221/223 → 223/223**，与字段级一致。§12.14 的口径更正随之作废
（那节保留作为「单一量具不交叉验证」的教训记录）。

### ② formatter 两条二次正则 —— 纵深防御，**立项继续有效**

★**前六种改法都在改正则；这次改输入。**

两条模式都**必须**匹配到 `<`，故无 `<` 时必然零匹配，可整体短路。
**这条等价性是可证明的，不依赖语料**——正好避开 §12.14 那个
「fuzz 字母表决定结论」的坑。

| | 结果 |
|---|---|
| 无 `<`（绝大多数真实源码） | 端到端 **1319ms → 26ms**，线性 |
| 等价性 | 宽字母表 300000 组、126933 组确有替换，**零分歧** |
| **放一个 `<` 就绕过** | n=40000 仍 **5143ms（×4.0）** |

**故本条仍是「已立项未修」，不得据此宣称已修复。**

#### ★我第一版把守卫作用域写错了

第一版短路了**整条 sanitize 链**，但链里还有 `produce…:` 与 `.{2,}→.`
两条**不需要 `<`** 的清理，被一并跳过 → 语义改坏。
反例 `"produce a. : b"`、`"a..b"` 被我自己写的语义对拍测试当场抓红。

★**教训：短路守卫的作用域必须精确到「真正依赖该前提的那几步」。**
「这个前提能让整块逻辑失效」是个很容易过度推广的直觉。

### 回归

- Java **1667** / TS **1878**（×3）全绿
- 双口径 parity **均 223/223**
- 两处修复均变异验证（Import 名字加后缀 / `hasPlaceholder = true`）


---

## §12.17 ★立项撤销：formatter 两条二次正则**已修复**

§12.12 把这两条列为「不修，二次是语义固有的」，§12.16 又降级为「纵深防御」。
**第八次尝试成功了，立项可以撤销。**

### 为什么前七次全败

| # | 改法 | 等价？ | 线性？ |
|---|---|---|---|
| 1 | `(?<=^\|\n)([ \t]*)` | ✖ 窄字母表零分歧、宽字母表全分歧 | ✓ |
| 2 | `(?<!\s)\s*`（去 `^`） | ✖ 84063/300000 | ✓ |
| 3 | `(?<!\s)^\s*`（保 `^`） | ✖ 30133/300000 | ✓ |
| 4 | `^(?:[ \t]*\n)*[ \t]*` | ✓ | ✖ 仍二次且慢 3 倍 |
| 5 | `(?<=^\|[\n\r…])\s*`（审查者找的） | ✓ 全 25 个 `\s` 成员零分歧 | ✖ 与原式**耗时完全一样** |
| 6 | 代码层左扫 v1 | ✖ 136116 | ✓ |
| 7 | 代码层左扫 v2 | ✖ 121272 | ✓ |

★**七次的共同根因：我一直凭直觉猜 `^\s*`（配 `m`）的语义，从没测过它。**

### 第八次：先做刻画实验

```
"a\n\n\n\n\nReturn <expr>."   → Return 在 idx 6，匹配起点却在 @2，吃掉 "\n\n\n\n"
"a\n\n  Return <expr>."       → "a\nX"（起点在第 2 行首，吞掉一个空行）
"aReturn <expr>."             → 不匹配（前面不是行首）
```

三条性质由此确定：

1. 起点**必然是某个行首**，`\s*` 只向**后**吃，绝不越过起点向前；
2. 但 `\s*` **可跨行**——起点行首可以在 `Return` 之前若干行；
3. 全局替换取**最靠前**那个能成功的行首。

### 等价且线性的算法

用**不含前导 `\s*`** 的 core 线性定位 `Return\s+<...>\s*\.`，
再从 core 起点向左跨连续 `\s`、沿途记录**最靠前的行首**作为真正起点；
若跨完没遇到任何行首则该处不匹配（对应原式 `^` 失配）。
每个字符最多被访问常数次 → **O(n)**。

### 结果

| 载荷 | 改前 | 改后 |
|---|---|---|
| 含 `<`（绕过短路守卫的**真实攻击面**） | **5143ms**（×4.0） | **23.4ms**（线性） |
| 无 `<`（常见路径） | 已由短路守卫覆盖 | 21ms |

**§12.12 的立项与 §12.16 的「纵深防御」定性均予撤销——漏洞已关闭。**

### 等价性实证

两条模式各跑随机 **400000 组**，字母表含 `\r \v \f` NBSP 全角空格
`\u2028` `\u2029`（★即 §12.14 里把我的错误结论打掉的那批字符），
其中 **180649 / 180609 组确有替换**，逐字节零分歧。

### 门禁（两条，互补，均变异验证）

| 门禁 | 变异 | 结果 |
|---|---|---|
| 攻击载荷线性（含 `<`） | 退回原二次正则 | 5244ms vs 300ms 预算 → 红 |
| 宽字母表等价对拍 | 「最靠前行首」改「最靠后行首」（语义错但仍线性） | 红 |

★一条守**性能**、一条守**语义**，缺任一条都会漏掉一类回归。
测试钩子**直接调生产函数**、不复制逻辑，避免对照基准各自漂移。

### ★本节最该记住的一条

**七次失败的代价，本可以用一次十分钟的刻画实验避免。**
「我以为这个正则是这么工作的」——那个「以为」值得先花十分钟证实。
