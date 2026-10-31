# ADR 0052：赋值语句的可赋值目标与块作用域写回

- 状态：**ACCEPTED**（2026-10-31，按 aster-cloud/aster-lang-truffle#151 的裁决定稿；同日按独立审查 review-151 两轮修订：迁移步骤改为升级前改写、语言包、lambda 捕获、函数名诊断、扫描不按 locale 分流）
- 日期：2026-10-31
- 相关：ADR 0016（IR 字段级 parity）、ADR 0050（增量重验）、ADR 0051（领域词汇按位置解析）、aster-cloud/aster-lang-truffle#151、#156
- Runbook：`docs/runbooks/adr0052-set-writeback-rollout.md`

---

## 1. 背景

`Set x to <expr>.` 是两引擎 AST / Core IR 都有的语句（ADR 0051 §2、§5），但此前没有任何 ADR 或语言规范规定它的语义。
#151 复核时发现以下行为不一致：

| 位置 | 现状（2026-10-31 origin/main） |
|---|---|
| aster-lang-truffle | `Set` 写到最近定义该名字的那层：形参在 frame slot，外层 Let 在 frame slot 或父 `Env`（`Env.set` 写冒泡）。在 case 体或 Scope 里给外层变量赋值，离开后仍可见。 |
| aster-lang-ts 解释器 | Scope 与每个 case 都用 `new Map(env)` 拷贝子环境，`Set` 只写进副本，离开块后丢失。 |
| aster-lang-ts 类型检查 | Let 与模式绑定都记为可变，**没有任何地方检查 `mutable`**，`Set` 形参也能通过。 |
| aster-lang-core | ANTLR 语法没有 `Set` 语句；en-US 内置词法表的 `set-to` 变换器把 `Set x to`（不区分大小写）改写成 `Let x be`。类型检查把所有 Let 记为不可变，对 `Set` 报 E001，但 Java 前端根本到不了这一步。 |
| aster-lang-locales | zh-CN 的 `postTranslationTransformers` 列有 `set-to` 与 `chinese-set-to`（`将 X 设为 Y` → `Let X be Y`），de-DE 与 en-US 副本列有 `set-to`。hi-IN 没有，旧版 Java 解析 hi 的 Set 直接失败。TS 的四种语言都没有这类变换器。 |

后果：同一份源码，TS 解析出 `Set`，Java 解析出 `Let`；同一份 Core IR，TS 解释器与 truffle 的求值结果不同。
生产执行引擎是 truffle；能到达 truffle `Set` 节点的只有 TS 产出的 Core IR（aster-api 的 Core IR 直接执行路径）。

## 2. 决策

### 2.1 写回语义

`Set x to e.` 求值 `e`，写到 **`x` 在词法作用域链上最近的那层绑定**。

- case 体、Scope、If / Otherwise 分支里给外层 Let 赋值，离开该块后外层看到的是新值。
- 块内的 `Let` 只在该块内可见。在 case 体或 Scope 里 `Let` 一个外层已有的名字会遮蔽外层；之后的 `Set` 写的是块内那个绑定，离开块后外层绑定不受影响。
  If 分支与函数体共享作用域（与 aster-lang-ts#231 的 E104 规则一致），在分支里 `Let` 外层已有的名字报 E104。
- 闭包（lambda）在创建时按值捕获外层变量，之后对外层的 `Set` 不影响已创建的闭包（tier1 样本 `closureSnapshot` 两引擎实测一致）。

写回本身就是 truffle 的现有行为，truffle 的 `Set` 不需要改动。truffle 的遮蔽语义另有缺陷，见 §5-1（truffle#156）。

### 2.2 可赋值目标

只有 **Let 绑定**可以被 `Set` 赋值，其余一律拒绝：

| 目标 | 诊断 |
|---|---|
| 规则形参、lambda 形参 | E001 `TYPE_MISMATCH`：expected `a Let binding`，actual `parameter '<name>'` |
| match 模式绑定（`When other,`、`Ok(v)` 等） | E001：actual `pattern binding '<name>'` |
| lambda 体内引用的捕获外层变量（含外层 Let） | E001：actual `captured variable '<name>'` |
| 未定义的名字、函数名、类型名 | E101 `UNDEFINED_VARIABLE` |
| 值类型既不等于也不是目标类型的子类型 | E002 `TYPE_MISMATCH_ASSIGN` |

lambda 体内自己 `Let` 的变量可以照常赋值。

**拒绝 lambda 体内 Set 捕获变量的理由**：lambda 按值捕获，体内的 `Set` 只能改捕获副本，作者几乎总是想改外层，结果却静默无效；
truffle 的捕获副本是否与 TS 完全同构也没有逐项核对。在语义定下来之前先拒绝，可以避免静默分歧；以后若决定改为写回，放开是兼容的变更。

**「同码同行」只针对类型检查阶段**：两引擎的类型检查对上表各项报相同的码与起始行。名称解析阶段的差异是既有管线差异，不在本 ADR 范围内。
例如首次声明用 `Set x`：
- 之后读 `x`（最常见的写法）：两端都在名称解析阶段报 E802，落在**读 `x` 的那一行**（如 `Return total.`），不报 E101。生产（ecosystem 1.0.38）已验证 Java 与 TS 一致。
- 之后不读 `x`：名称解析不触发，两端都在类型检查阶段报 E101，落在 **`Set` 所在行**，同码同行。

### 2.3 语法

`Set <name> to <expr>.` 是赋值语句，关键词按语言包本地化，大小写不敏感（`set x to …` 同样是 Set）：

| 语言 | 写法 |
|---|---|
| en-US | `Set total to total plus 1.` |
| zh-CN | `将 合计 设为 合计 加上 1。` |
| de-DE | `setze summe auf summe plus 1.` |

- aster-lang-core 在 ANTLR 语法中增加 `setStmt`（`SET nameIdent TO_WORD expr DOT`），并从 en-US 内置词法表移除 `set-to`。
- aster-lang-locales 从 zh-CN 移除 `set-to` 与 `chinese-set-to`，从 de-DE 与 en-US 副本移除 `set-to`。zh / de 的关键词经翻译后得到 `Set … to`，由同一条 `setStmt` 解析。
- 变换器实现仍注册在 `TransformerRegistry`，供自定义词法表使用；内置与官方语言包不再引用。
- **点分目标**（`Set x.f to …`）目前两引擎都在解析阶段拒绝：Java 报 `mismatched input '.' expecting 'to'`，TS 报 `Use 'to' in assignments`。
  ADR 0051 §5 表中 Set 目标的 FIELD_REF 行暂不适用；若以后支持字段赋值，另立 ADR。

## 3. 与 ADR 0051 名称解析的关系

ADR 0051 §6 的 LOCAL 层定义为「当前词法作用域内源码声明的局部名」，R2 对单段 `Set` 目标不做解析，保留原写法。
本 ADR 的写回语义与这一模型一致：名称解析只决定 `x` 指向哪个**名字**，运行时按同一条词法作用域链决定写到哪一**层**。
领域词汇不会把局部名改写成别的名字（ADR 0051 R1 不替换局部名），因此本 ADR 不需要新增解析规则。

## 4. Parity 要求

- aster-lang-test tier1-parity 的 eval 样本只给 Let 绑定赋值，两引擎的 parse、IR（`--mode=ir --full`）、eval 结果都必须一致：
  - `tier1-equivalence/policies/set-writeback.aster`（en）：case 体写回、同一 case 体连续写回、If / Otherwise 写回、If 内 case 体写回、
    闭包创建后外层 Set（闭包看到创建时的值）、小写 `set`。
  - `tier1-parity/vocab-zh/set-writeback.aster`、`tier1-parity/vocab-de/set-writeback.aster`：本地化关键词的 case 体与 If / Otherwise 写回。
- 不新增「Set 形参」或「lambda 内 Set 捕获变量」的接受样本；这些只在两引擎类型检查单测中作为必拒用例出现。
- TS 解释器单测覆盖 Scope / 嵌套 Scope（表层语法写不出 Scope，只能用 Core IR 直接构造）。

## 5. 已知遗留

1. **case 体 / 嵌套 Scope 内 `Let` 遮蔽外层同名变量**（aster-cloud/aster-lang-truffle#156）：类型检查允许，TS 解释器按本 ADR 遮蔽；
   truffle 把函数体内（含 case 体）的 Let 放进同一组 frame slot，嵌套 Scope 走 `Env.set` 写冒泡，遮蔽都变成了覆盖。修复前 tier1 样本不覆盖这种写法。
2. **专用错误码**：「赋值给不可变绑定」目前借用 E001。新增专用码（如 `E105 ASSIGN_TO_IMMUTABLE`）需要先在
   aster-lang-locales（zh / de）与 aster-lang-hi 补齐诊断译文，否则 TS 的 overlay 覆盖测试会失败。另开 issue 跟进。
3. **中文模式绑定名**：`当 其他，` 这类 CJK 名字作模式绑定时，Java 判为枚举变体，TS 判为绑定（既有分歧，与 Set 无关）。
   zh 样本的模式绑定改用 ASCII 名，另开 issue 跟进。

## 6. 落地、迁移与发版顺序

这是**有意的破坏性变更**：旧版 Java 管线把 `Set x to …` 改写成 `Let x be …`，首次声明用 `Set` 也能编译；升级后它报错：`Set` 目标之后被读取时为 E802（落在读取行），不被读取时为 E101（落在 Set 行），见 §2.2。
升级后，执行门（`PolicyExecutionGate`）对新 toolchain 下还没有校验行的版本会在请求里同步做类型检查，enforce 模式下直接拒绝（422）。
因此关闭启动期重验（`ASTER_REVALIDATION_ENABLED=false`）**不能**提供缓冲期，命中版本必须在升级**之前**改写。改写后的 `Let` 写法在新旧工具链下都能编译。
具体命令见 runbook。

**发版门禁**：发版列车的最后一层会自动部署 aster-api（`deploy.yml` → k3s image-pin PR 自动合并 → ArgoCD 滚动更新），
**运行一次列车就等于升级生产 aster-api**。core#225 已在 core main，此后任何一次列车都会带上本变更，包括不相关的 hotfix 列车。因此：
(1) 在扫描清零、或命中版本全部改写发布之前，不得运行任何发版列车；
(2) 必须先发版时，先把 `ASTER_COMPILE_TYPECHECK=warn` 合入 k3s 的 aster-api 部署（`apps/aster-lang/cloud/deployment.yaml`，补丁见 runbook §3）并确认生效，再运行列车；
(3) 列车跑完后检查重验的 invalid 列表，命中清零后从 k3s 删除该变量，恢复 enforce。

1. **合入顺序**：本 ADR（aster-api）→ aster-lang-core → aster-lang-locales（其 en-US 副本须与 core backbone 逐字节一致）
   → aster-lang-ts → aster-lang-test。ts 的格式化往返白名单已登记新的 zh 样本，并跳过语料仓中尚不存在的文件，所以 ts 与 test 可以任意先后合并。
2. **发版顺序**（沿用生态 release train）：locales → core → truffle（无源码变更，lockstep 重新打 tag）→ ts npm → test。
3. **发版前扫描**：对生产 `policy_versions.content` 运行只读扫描 `scripts/ops/adr0052-scan-set-statements.sql`。
   en / de / zh 三条模式对每个版本都扫（不按 `locale` 列分流），列出 Set 目标名及文中是否存在同名 Let。结果为空即可直接发版。
4. **发版前改写**：作者把命中的首次声明改为 Let（`Set x to` → `Let x be`，`将 … 设为` → `令 … 定义为`，`setze … auf` → `sei … gleich`），
   lambda 体内对捕获变量的 Set 改为由外层赋值；走正常审批并发布新版本。改写完成后重跑扫描确认。
5. **升级 aster-api（= 运行发版列车）**：满足门禁后运行列车，启动期 enforce 重验保持开启；完成后在 `/api/admin/revalidation/invalid` 确认没有落在 Set 行上的 E101 / E001，也没有读取行上由首次声明 Set 引起的 E802（见 §2.2）。
6. **兜底**（来不及在升级前改完时）：运行列车**之前**在 k3s 的 aster-api 部署中临时设 `ASTER_COMPILE_TYPECHECK=warn`，以 report 模式重验找出命中版本并改写，
   清零后恢复 `enforce`。窗口内所有类型错误都只告警，须尽量缩短并通知作者。
7. **回滚**：aster-api 回退 core / locales / truffle 的 pin 到上一生态版本，重新部署；toolchain id 随之变回，启动期按旧工具链重验。
   已改写为 `Let` 的版本在新旧工具链下都能编译，无需回退；用过兜底的确认 `ASTER_COMPILE_TYPECHECK` 已恢复为 `enforce`。
8. truffle 的 `Set` 无需改动，遮蔽缺陷由 truffle#156 跟进。
