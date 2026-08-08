# ADR 0024 — 声明式集合查询（可读的列表 / 聚合 CNL）

Status: **PROPOSED（本会话规划，未实施）**
Date: 2026-06-28
Author: Claude（语言设计） / 待用户审定范围
Review: Codex 交叉审查 三轮 68→84→**90/100「通过，可定稿、启动阶段 1」**。
- 轮1(68)：采纳全部 7 项必修（"List.* 仅 input"事实错误、§2"任何语言不可读"强断言→"审计点取舍"、
  §3.1 可复现探针附录、C4 绑定域显式、C5 wheel/high-A 写死、§4.1 输入契约、阶段3 builtin 规格+golden、
  工程量"数十"→约20-30、战略定位"通用集合查询为主/poker 为验收样例"）。
- 轮2(84)：修 2 处内部不一致（§4.1 示例同步正式语法、list/map 来源措辞区分）。
- 轮3(90)：确认无残留旧语法歧义（仅 §4.3 两处反例带"（已废）"标注）、无新瑕疵、无阶段1阻塞项。
阶段 2/3 查询语法的 grammar/canonicalizer 细节须在各自实施前单独评审。
缘起: poker demo（`/demos/poker`, ADR 见 cloud#139）当前用 JS 算手牌强度、CNL 只判赢家。
用户要求「给 Aster 补能力，然后**纯 CNL** 写完整扑克评估，且**可读性强**」。本 ADR
是该方向的设计文档——经真引擎实测界定了技术边界与一个根本张力，给出**档位 A** 方案。

---

## 1. 决策摘要（用户已拍板）

- **真正的交付物是通用、可审计、双引擎 parity 的「声明式集合查询」表达能力**——无数业务
  规则（合规清单、风控聚合、资格判定）都受益。**poker 只是触发器 + 验收样例之一**，不是
  目标本身（Codex 审查要点 E：为单个 fun-demo 做 6 仓语言工程 ROI 不足；定位成通用能力
  则 ROI 成立，且阶段 1-3 即便 poker 阶段暂缓也独立有产品价值）。
- **纯度档位 A**：在此能力之上，poker 的牌型判定 + 比牌**全部在 CNL**且读起来像规则书；
  「从 7 张选最佳 5 张」这一步**组合枚举**明确划为**确定性特征提取**（如同信贷 demo 不在
  CNL 里算 FICO 分），由预处理给出每位玩家的 5 张牌作为 CNL 输入。
- **先出本 ADR 设计文档，审定后再分阶段实施**（不闷头改 grammar）。

## 2. 根本张力（审计点取舍，非"任何语言都不可读"）

德州扑克评估内核含**组合数学**：C(7,5)=21 种选法取最优、按点数分组计数、序列检测、
比 kicker。精确的命题是（Codex 审查修正——原"任何语言都不可读是数学事实"是过度断言）：

> **若要求把组合枚举算法本身用通用 CNL 构造展开**（递归组合生成器 + 动态列表增长 +
> 取最优），则**不可读**——那是把递归算法翻译成英文。
> 反之，**若允许领域级 primitive 把枚举封进一个意图**（如
> `Let bestHand be the best 5-card hand from the player's cards and the community.`），
> 则源码仍可读——但代价是**算法审计点从 CNL 规则转移到了 builtin/特征提取的实现**
> （那一步不再是"规则即逻辑"，而是"规则调用一个黑盒计算"）。

所以这不是"能不能写"，而是"审计点放哪"。「既要纯 CNL 完整评估、又要可读」的张力，
本质是**组合枚举那一步无法既"算法展开在 CNL 里"又"可读"**——二者只能取一。

破局 = 区分两类东西（与信贷 demo 同构）：
- **机械事实提取**（不是规则）：7→5 选最佳组合、洗牌发牌。确定性，无判断，不可读也无需可读，
  审计靠确定性 + 与镜像逐手一致（见 §7），而非靠"读规则"。
- **业务规则**（真·规则，Aster 主场）：牌型分级（同花顺 > 四条 > …）、比牌（点数高者胜，
  同点比 kicker）、**给定 5 张牌判定它是什么牌型**。可读、可被监管审视。

档位 A 把**全部业务规则**放进 CNL，仅把 7→5 枚举留在确定性边界（不封装成 CNL 里的
"黑盒意图 builtin"——那会把审计点偷偷搬进规则却不展开，比诚实地放在特征提取层更糟）。

## 3. 技术现实（真引擎实测，2026-06-28）

实测探针结论（compile + evaluate 真跑）：

| 能力 | 现状 |
|------|------|
| 递归（规则自调用） | ✅ `fact(5)=120`（见附录探针 P1）|
| 内联 lambda `function with x, produce:` | ✅ `=42`（P2）|
| List.reduce/filter/map/length、Map.* builtin | ✅ 已注册可用——但 **list/map 只能来自 runtime builtin（list：`List.empty`/`append`/`concat`/`Text.split`；map：`Map.empty`/`put`）或 host input**；**源码无法用字面量构造列表**（P3）|
| **列表字面量 `[a,b,c]`（源码构造列表）** | ❌ **整条 Core IR 契约缺 `ListLit` 节点**（P4）|
| List.sort / groupBy / min / max / range | ❌ 不存在（Builtins.java 未注册）|

> Codex 审查修正：原写"List.* 仅当列表经 input 传入时可用"不准确——runtime builtin
> 也能产生可被 List.* 消费的列表。准确边界是：**源码 list literal 不可用**；
> host input + runtime builtin 都可产生 list/map。本 ADR 要补的正是"源码可读地构造/
> 查询列表"这一缺口。

**列表字面量缺口的精确深度**（4 仓 + Core IR 契约）：

| 层 | 状态 | 证据 |
|----|------|------|
| core grammar + **AST** | ✅ `listLiteral` 规则 + **AST 层** `Expr.ListLiteral`（AsterParser.g4:656, AstBuilder:1322）|
| core lowering | ⚠️ 降成 `Construct("List",{0:..,1:..})`（CoreLowering:438）——非原生节点 |
| **Core IR schema** | ❌ **Core IR 层无 `ListLit` 节点**（CoreModel.java:432 Expr union / ts types.ts:337,638 Expression union 都没有；注意：AST 层有 `Expr.ListLiteral`，缺的是 **Core IR** 层）|
| Truffle 运行时 | ⚠️ **会崩**：`Construct("List",…)`→`buildConstruct`（Loader:512）→`requireDataDefinition("List")`（Loader:598）→查不到 Data 定义抛 `未定义的数据类型：List`（Loader:779-788）|
| ts parser | ❌ 无 `[` primary 分支（parsePrimary 落到 "Unexpected expression"，expr-stmt-parser:1067-1379）|
| ts lower_to_core | ❌ 无 `ListLiteral` case（lower_to_core.ts:487-525）|

**关键教训：连 Java「看起来支持」的列表字面量，运行时也是坏的**（AST 层有、Core IR 层缺、
Truffle 执行崩）。这不是「TS 补根线」，而是**Core IR 契约缺一个 `ListLit` 节点**，须 4 仓
协同 + 双引擎 parity。以上 core/truffle/ts 行号经 Codex read-only 沙箱复核成立（2026-06-28）。

### 3.1 可复现实测附录（探针）

实测在 aster-cloud 仓内用 vitest 跑 `compile`+`evaluate`（`@aster-cloud/aster-lang-ts/browser`，
该 npm 包 = 部署后端同版 ts 引擎），探针测试**已删除（一次性诊断，未入库）**，原始源码与输出：

- **P1 递归**：`Rule fact given n as Int, produce Int: If n at most 1: Return 1. Otherwise: Return n * fact(n - 1).` + `evaluate(core,'fact',{n:5})` → `OK=120`
- **P2 内联 lambda**：`Let f be function with x, produce: Return x * 2. Return f(21).` → `OK=42`
- **P3 列表经 input + reduce**：`Rule plus given acc,x: Return acc + x. Rule sumIn given nums: Return List.reduce(nums, 0, plus).` + `evaluate(core,'sumIn',{nums:[10,20,30,5]})` → `OK=65`；`List.length(nums)`（nums=[1..7]）→ `OK=7`
- **P4 列表字面量**：`Let xs be [10, 20, 30]. Return List.length(xs).` → **COMPILE FAIL** `Unexpected expression`（line:col 指向 `[`）；作 call 参数 `List.reduce([…],0,plus)` 同样 `Unexpected expression`

> 实施时阶段 1 应把这些探针固化为**入库的 parity fixture**（而非一次性脚本），并记录所用
> ts npm 版本 + core/truffle commit，补足审计性。

## 4. 设计：可读集合查询语法（自然语言版 LINQ）

不是「把算法翻译成英文」，而是给 Aster 加一类**天然可读的声明式集合查询**。每条都通用
（不只为扑克，无数业务规则受益）、可双引擎实现、可 parity、有 en/zh/de 三语关键词。

### 4.1 目标态 poker 规则（用拟议语法，展示可读性）

**输入契约**（Codex 审查要求显式声明）：`hand` 是**恰好 5 张、无重复**的 `Card`（每张
`rank` 2..14 整数、`suit` 枚举）。这是档位 A 的核心切分点——7→5 选最佳 5 张由特征提取
预处理完成，`classify` 只对**定长 5 张**判定（故无需枚举/排序，全是 count/where/highest，
顺序判定才正确）。若传 7 张，"某点数出现 3 次"等判定会与"最佳 5 张"语义混淆——
所以引擎/类型层应约束 `Hand` 为 5 张（见 §4.3）。

```
Module poker.handrank.

# hand: exactly 5 distinct cards.
Rule classify given hand as Hand, produce Text:
  If every card in hand shares suit and the ranks in hand form a run:
    Return "straight flush".
  If any value of rank in hand appears 4 times:
    Return "four of a kind".
  If any value of rank in hand appears 3 times and any value of rank in hand appears 2 times:
    Return "full house".
  If every card in hand shares suit:
    Return "flush".
  If the ranks in hand form a run:
    Return "straight".
  If any value of rank in hand appears 3 times:
    Return "three of a kind".
  If the count of values of rank in hand appearing 2 times is 2:
    Return "two pair".
  If any value of rank in hand appears 2 times:
    Return "pair".
  Otherwise:
    Return "high card".
```

> 上例严格使用 §4.2/§4.3 的正式语法（`shares suit` / `any value of rank … appears N` /
> `the ranks … form a run` / `the count of values of rank … appearing N is M`），与语法表一致。

比牌（决定赢家）规则同样可读——先比牌型档次，平则比关键牌：

```
Rule winner given table as Showdown, produce Text:
  Let oneRank be classify(table.playerOne).
  Let twoRank be classify(table.playerTwo).
  If categoryStrength(oneRank) greater than categoryStrength(twoRank):
    Return "player1".
  ... (kicker 比较：the highest rank in playerOne vs playerTwo) ...
```

### 4.2 语法构造清单（每条 = 一个语言能力增量）

| # | 可读语法（en） | zh | de | 语义 / Core IR 映射 |
|---|---------------|-----|-----|---------------------|
| C0 | `[a, b, c]` 列表字面量 | `[a，b，c]` | `[a, b, c]` | **新 Core IR `ListLit` 节点**（基础，4 仓）|
| C1 | `the count of <list> where <pred>` | `<list> 中满足 <pred> 的数量` | `Anzahl der … wo …` | `List.length(List.filter(list, pred))` |
| C2 | `any <x> in <list> has <pred>` / `every <x> in <list> has <pred>` | `<list> 中任一/每个 …` | `irgendein/jedes …` | some / all（reduce-or / reduce-and）|
| C3 | `the highest/lowest <field> in <list>` | `<list> 中最高/最低的 <field>` | `höchste/niedrigste … in …` | reduce-max / reduce-min by key |
| C3b | `every <x> in <list> shares <field>`（同值，非"same as 什么"）| `<list> 中每个 <x> 的 <field> 相同` | `… teilen <field>` | `allEqual(project(list, field))`——见 §4.3 关于"same suit"歧义 |
| C4 | `any value of <field> in <list> appears N times` | `<list> 中有 <field> 取值出现 N 次` | `ein Wert von <field> … N mal` | group-count by field + any==N——`<field>` 取值域=`distinct project(list,field)`，**非全局枚举**（见 §4.3）|
| C5 | `the <field>s in <list> form a run`（顺子，含 wheel）| `<list> 中 <field> 构成顺子` | `… bilden eine Folge` | 序列谓词，**扑克语义**（A 可作 14 或 1，见 §4.3）|
| C6（可选）| `<list> joined with <list>` | `<list> 接上 <list>` | `… verbunden mit …` | `List.concat` |

**设计原则（语言大师视角）**：
- 全部**声明式查询**（"count of … where …"），非命令式循环（"for each … do …"）。前者可读、
  后者永远不可读——这是 Aster 区别于通用语言的核心审美，必须坚持。
- 复用既有 builtin（List.filter/reduce/length/concat 已注册并实测可用）做底层；新语法只是
  **可读的表面糖** + lowering 到 builtin call。**唯一真·新增的底层是 C0 的 `ListLit` Core IR
  节点** + C4/C5 的分组计数/序列谓词薄 builtin。
- C4/C5 倾向**加薄 builtin**（`List.countByField` / `List.formsRun`）而非纯 reduce+Map 组合：
  更易双引擎 parity、更可控。**但 builtin ≠ 黑盒**（Codex 审查要点）——审计性靠：①CNL 语法
  领域无关（通用集合查询，非"poker 专用"）②builtin 名/语义稳定且**有独立规格 + 双引擎
  golden 边界用例**（见 §4.3 + 阶段 3 验收）③poker 规则源码旁链接 builtin 规格。否则会从
  "规则即逻辑"滑向"规则调黑盒"。

### 4.3 语义精化（Codex 审查必修项——这几条藏了复杂度，须写死规格）

- **C3b "同花" 的歧义**：旧写法 `every card has the same suit`（已废）自然但"same as 什么"
  不明。精确语义=**该集合内所有 suit 相等**（`allEqual(project(hand, suit))`），非与某外部值
  比较。**正式语法**定为 `every card in hand shares suit`。
- **C4 量词 `rank` 的绑定域**：正式语法 `any value of rank in hand appears N times` 里 `rank`
  **不是**绑定变量、也**不是**全局 `Rank` 枚举（2..14 全域）——其取值域是
  **`distinct project(hand, rank)`**（这手牌里实际出现的点数）。引擎 lowering 必须按"分组计数"
  实现（`List.countByField(hand, rank)` 返回 `值→次数`，再问"是否有次数==N"），不能误解为
  遍历全局枚举。旧写法 `any rank appears N times`（已废）表面看不出这层，故改用正式语法让
  绑定域显式。
- **C5 顺子的扑克语义**：`max-min==4 && distinct==5` **会漏 wheel**（A-2-3-4-5：A=14 时
  max-min=12）。须写死：`formsRun` 接受 ① 5 张 distinct 且连续，含 ② **wheel**（A 作 1：
  {14,2,3,4,5}）③ **high-A**（A 作 14：{10,11,12,13,14}）；A 不能同时作 14 和 1 参与同一序列。
  这是有限的特例集，builtin 内写死，规格进 golden。
- **classify 顺序正确性**：§4.1 的 If 链按牌型从强到弱顺序判定（同花顺先于同花先于顺子…），
  对**恰好 5 张**输入正确；"两对"用 `the count of (values of rank appearing 2 times) is 2`
  （即 distinct rank 中恰好 2 个出现 2 次）。**前提是输入契约（5 张无重复）成立**——故 §4.1
  顶部声明的输入契约是正确性的一部分，非可选。

## 5. 阶段拆分（每阶段独立可发布、可 parity）

> 经验：grammar 改动是 PR-blocking gate 且易引回归（ADR 0015/0019 教训），故**逐特性**推进，
> 每个特性走完「双引擎 parse-parity + eval 一致 + Codex 审查」再下一个。

- **阶段 1 — C0 列表字面量（基础，独立有价值）** ✅ **已完成并合并 main（2026-06-28）**：
  Core IR 加 `ListLit` 节点（core#44）；ts parser+lower+interpreter（ts#37）；Truffle
  `ListLiteralNode`（truffle#27）。三协调 PR CI 双引擎 parse-parity 绿 → 合并。**顺带修复
  现存崩溃 bug**（list literal 旧降成 `Construct("List")` 在 Truffle 运行时崩）。**未发版**
  （留到引擎层阶段全做完一次性级联）。
- **阶段 2 — C1/C2/C3 查询糖**（count-where / any-every-has / highest-lowest）：纯 grammar +
  canonicalizer + lowering 到既有 builtin，无新 Core IR 节点。三语关键词 + parity。
- **阶段 3 — C4/C5 分组计数 + 序列谓词**：加薄 builtin（双引擎）+ 语法糖。这是扑克牌型判定
  的最后拼图。**builtin 须带独立规格 + golden 边界用例**（非"~15 行"一笔带过）：
  - `List.countByField(list, field) → Map<value,count>`：golden 覆盖空表、全同值、全异值。
  - `List.formsRun(ranks) → Bool`：golden **必须**覆盖 wheel(A-2-3-4-5)、high-A(10-J-Q-K-A)、
    非连续、含重复（应 false）、distinct<5。规格写进 fixture，双引擎逐位一致进 tier1-parity。
- **阶段 4 — poker demo 重写**：用新语法把 `src/config/poker.ts` 的 JS 牌型评估替换为纯 CNL
  `classify` + `winner` 规则（三语）；7→5 枚举保留为「特征提取」预处理并在 UI/注释明确标注；
  `poker.compile.test` 扩展为「三语 classify 规则对所有 9 类牌型 + wheel/high-A 边界判定正确，
  且 winner 比牌与现 JS `evaluateBoard` 镜像逐手一致」。

每阶段跨仓清单（典型）：aster-lang-core（grammar+lowering）、aster-lang-ts（parser+lower+
interpreter）、aster-lang-truffle（evaluator node/builtin）、aster-lang-locales（zh/de 关键词）、
aster-lang-test（parity fixture + corpus）、aster-cloud（仅阶段 4 改 demo）。发版走 ADR 0023
release-train 级联。

## 6. 风险 / 边界

- **工程量**（Codex 审查修正"数十 PR"偏夸张）：**多仓、多阶段、至少数日**。现实拆法 ≈ 每阶段
  1-2 个主 PR/仓 × 涉及的仓（core/ts/truffle/locales/test，阶段 4 加 cloud）+ 一次 release-train
  级联。即每阶段约 5-8 个 PR、跨 5-6 仓，4 阶段累计约 20-30 PR——量级真实存在但非"无边数十"。
  绝非一个回合。每阶段是一次完整的「双引擎 parity + 审查 + 发版」循环。
- **可读性守门**：任何阶段若发现某条语法写出来不可读（尤其 C4/C5），停下重设计——宁可
  让那部分留在特征提取，也不引入不可读的递归。可读性是本 ADR 的硬验收标准，非"能跑就行"。
- **parity 风险**：新 Core IR 节点 + 新 builtin 须双引擎逐字节一致（进 tier1-parity manifest）。
  序列谓词/分组计数的整数语义（distinct 计数、向零截断等）须对齐。
- **不做**：命令式循环（for/while）、动态列表增长 + 递归组合生成器（档位 B，违背可读性）、
  在 CNL 里洗牌/发牌（随机性不属于规则）。

## 7. 验收标准

- 阶段 1：`Let xs be [1,2,3]. Return List.length(xs).` 双引擎 eval 一致（修复现存崩溃）。
- 阶段 4：§4.1 的 `classify` 规则三语（en/zh/de）真编译真执行，对 9 类牌型的代表样本判定
  全部正确，且 `winner` 比牌（含 kicker）与现 JS `evaluateBoard` 镜像逐手一致；规则源码
  通体可读（非工程，读者能当扑克规则书读）。
- 全程双引擎 parse-parity（PR-blocking）+ eval 一致 + 每阶段 Codex 审查 ≥ 通过线。

## 8. 与现状关系

现 poker demo（cloud#139）保持运行；本 ADR 实施后阶段 4 才替换其 JS 评估为纯 CNL。
在阶段 1-3 落地前，demo 不变。list 字面量（阶段 1）独立修复一个现存运行时 bug，本身值得做。

## 9. 阶段 2 详细设计（C1/C2/C3 集合查询语法糖）

实测前提（2026-06-28，真引擎）：①阶段 1 列表字面量已合并 → 源码可构造列表；②**高阶
builtin 可用**——`List.filter(list, namedRule)`/`List.reduce(list, init, namedRule)`/
`List.map` 在两引擎实测通过（具名规则或内联 lambda 作 fn 参数）。故 C1/C2/C3 **无需新
Core IR 节点**，是纯 grammar 表面糖 + lowering 合成 lambda + 既有 List.* builtin call。

### 9.1 语法（显式元素绑定 + 软关键字，零新 lexer token）

采用**显式绑定** `<var> in <list>`（非隐式 `it`）——可读、无歧义、且 C1/C2/C3 形式统一。
全部用**语义谓词软关键字**（match IDENT 文本，仅在查询位置当关键词，其余位置仍是普通
标识符），与现有 `softComparator`（under/over）同机制，**不引入新硬保留 token**（避免
历史上"硬 token 吞标识符"回归）。查询构造作为 `primaryExpr` 的新 alternative。

| 构造 | en 语法 | 绑定语义 |
|------|---------|---------|
| C1 计数 | `the count of <var> in <list> where <pred>` | `<var>` 绑定列表元素，`<pred>` 是关于 `<var>` 的布尔表达式 |
| C2 全称/存在 | `every <var> in <list> has <pred>` / `any <var> in <list> has <pred>` | 同上；`every`→全真，`any`→存在真 |
| C3 极值 | `the highest <field> of <var> in <list>` / `the lowest …` | 取 `<var>.<field>` 最大/最小值 |

zh/de 关键词（进 lexicon，软关键字）：count=数量/Anzahl、where=满足/wo、in=在…中/in、
every=每个/jedes、any=任一/irgendein、has=满足/erfüllt、highest=最高/höchste、
lowest=最低/niedrigste、of=的/von。`the` 在 zh/de 省略。

### 9.2 lowering（合成内联 lambda + 既有 builtin，两引擎一致）

- **C1** `the count of v in L where P` →
  `List.length(List.filter(L, function with v, produce: Return P))`
- **C2** `every v in L has P` → `List.reduce(L, true, function with acc, v, produce: Return acc and P)`
  `any v in L has P` → `List.reduce(L, false, function with acc, v, produce: Return acc or P)`
- **C3** `the highest f of v in L` →
  `List.reduce(L, <min哨兵>, function with acc, v, produce: Return if v.f greater than acc then v.f else acc)`
  （空列表语义：返回哨兵；poker 用例列表恒非空，哨兵取一个安全下界并在 §3 阶段验收覆盖空表）

合成的 lambda 节点 + List.* call 都是阶段 1 已验证可跑的 Core IR 形态——**lowering 在
parser/lower 层做（AST → 既有节点组合），不新增 Core IR 节点**，故 IR-parity 契约不变，
两引擎只要 parse 出相同的 AST 形状即自动 eval 一致。

### 9.3 实施顺序（每条独立 parity + Codex 循环）

1. **先做 C1 count-where**（最常用、lowering 最直接=filter+length）跑通整套机制
   （软关键字 grammar + 元素绑定 + 合成 lambda + 双引擎 parse-parity + eval）作模板。
2. C2 any/every-has（reduce + and/or）照搬机制。
3. C3 highest/lowest（reduce-max-by-field + 空列表哨兵）。

每条：core .g4 + AstBuilder + lowering、ts parser + lower、lexicon 三语软关键字、
两引擎本地验证 + CI parity、Codex 审查。**任一条 parity red 或 grammar 回归 → 停在
干净状态记录，不 thrash**。
