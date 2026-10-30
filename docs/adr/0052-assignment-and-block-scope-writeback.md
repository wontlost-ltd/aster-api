# ADR 0052：赋值语句的可赋值目标与块作用域写回

- 状态：**ACCEPTED**（2026-10-31，按 aster-cloud/aster-lang-truffle#151 的裁决定稿）
- 日期：2026-10-31
- 相关：ADR 0016（IR 字段级 parity）、ADR 0051（领域词汇按位置解析）、aster-cloud/aster-lang-truffle#151

---

## 1. 背景

`Set x to <expr>.` 是两引擎 AST / Core IR 都有的语句（ADR 0051 §2、§5），但此前没有任何 ADR 或语言规范规定它的语义。
#151 复核时发现四处行为不一致：

| 位置 | 现状（2026-10-31 origin/main） |
|---|---|
| aster-lang-truffle | `Set` 写到最近定义该名字的那层：形参在 frame slot，外层 Let 在 frame slot 或父 `Env`（`Env.set` 写冒泡）。在 case 体或 Scope 里给外层变量赋值，离开后仍可见。 |
| aster-lang-ts 解释器 | Scope 与每个 case 都用 `new Map(env)` 拷贝子环境，`Set` 只写进副本，离开块后丢失。 |
| aster-lang-ts 类型检查 | Let 与模式绑定都记为可变，**没有任何地方检查 `mutable`**，`Set` 形参也能通过。 |
| aster-lang-core | ANTLR 语法没有 `Set` 语句；en-US 词法表的 `set-to` 规范化变换器把 `Set x to` 改写成 `Let x be`（TS en-US 词法表没有这个变换器）。类型检查把所有 Let 记为不可变，对 `Set` 报 E001，但 Java 前端根本到不了这一步。 |

后果：同一份源码，TS 解析出 `Set`，Java 解析出 `Let`；同一份 Core IR，TS 解释器与 truffle 的求值结果不同。
生产执行引擎是 truffle；能到达 truffle `Set` 节点的只有 TS 产出的 Core IR（aster-api 的 Core IR 直接执行路径）。

## 2. 决策

### 2.1 写回语义

`Set x to e.` 求值 `e`，写到 **`x` 在词法作用域链上最近的那层绑定**。

- case 体、Scope、If / Otherwise 分支里给外层 Let 赋值，离开该块后外层看到的是新值。
- 块内的 `Let` 只在该块内可见。在 case 体或 Scope 里 `Let` 一个外层已有的名字会遮蔽外层；之后的 `Set` 写的是块内那个绑定，离开块后外层绑定不受影响。
  If 分支与函数体共享作用域（与 aster-lang-ts#231 的 E104 规则一致），在分支里 `Let` 外层已有的名字报 E104。
- 闭包（lambda）在创建时按值捕获外层变量，之后对外层的 `Set` 不影响已创建的闭包。lambda 体内 `Set` 捕获变量的语义本 ADR 不定义（见第 5 节）。

这就是 truffle 的现有行为，**truffle 不需要改动**，生产语义不变。

### 2.2 可赋值目标

只有 **Let 绑定**可以被 `Set` 赋值。以下绑定不可变：

| 绑定 | 诊断 |
|---|---|
| 规则形参 | E001 `TYPE_MISMATCH`：expected `a Let binding`，actual `parameter '<name>'` |
| lambda 形参 | 同上（`parameter`） |
| match 模式绑定（`When other,`、`Ok(v)` 等） | E001：actual `pattern binding '<name>'` |
| 未定义的名字 | E101 `UNDEFINED_VARIABLE` |
| 值类型既不等于也不是目标类型的子类型 | E002 `TYPE_MISMATCH_ASSIGN` |

两引擎的类型检查必须报相同的码与行号。

### 2.3 语法

两引擎都把 `Set <name> to <expr>.` 解析为 `Set` 语句。aster-lang-core 在 ANTLR 语法中增加 `setStmt`，
并把 `set-to` 从 en-US 内置词法表的 `postTranslationTransformers` 中移除（变换器实现保留在注册表里）。
`Set` 的目标为点分路径时（`Set x.f to …`）仍按 ADR 0051 §5 作为 FIELD_REF 解析，本 ADR 不改变这一点。

## 3. 与 ADR 0051 名称解析的关系

ADR 0051 §6 的 LOCAL 层定义为「当前词法作用域内源码声明的局部名」，R2 对单段 `Set` 目标不做解析，保留原写法。
本 ADR 的写回语义与这一模型一致：名称解析只决定 `x` 指向哪个**名字**，运行时按同一条词法作用域链决定写到哪一**层**。
领域词汇不会把局部名改写成别的名字（ADR 0051 R1 不替换局部名），因此本 ADR 不需要新增解析规则。

## 4. Parity 要求

- aster-lang-test tier1-parity 新增 eval 样本，覆盖 case 体写回、If / Otherwise 写回、多个 case 累加写回，
  只对 Let 绑定赋值。两引擎的 parse、IR（`--mode=ir --full`）、eval 结果都必须一致。
- 不新增「Set 形参」的接受样本。形参赋值只在两引擎的类型检查单测中作为必拒用例出现。
- TS 解释器单测覆盖 Scope / 嵌套 Scope（表层语法写不出 Scope，只能用 Core IR 直接构造）。

## 5. 已知遗留

1. **case 体内 `Let` 遮蔽外层同名 Let**：类型检查允许（case 开新作用域）。TS 解释器按本 ADR 遮蔽；truffle 把函数体内
   （含 case 体）的 Let 都放进同一组 frame slot，遮蔽变成了覆盖，离开 case 后外层值已被改掉。嵌套两层 Scope 里同名 Let
   也有同样的问题（`Env.set` 写冒泡）。这是 truffle 的缺陷，单独开 issue 修复；修复前 tier1 样本不覆盖这种写法。
2. **专用错误码**：「赋值给不可变绑定」目前借用 E001。新增专用码（如 `E105 ASSIGN_TO_IMMUTABLE`）需要先在
   aster-lang-locales（zh / de）与 aster-lang-hi 补齐诊断译文，否则 TS 的 overlay 覆盖测试会失败。另开 issue 跟进。
3. **lambda 体内 `Set` 捕获的外层变量**：两侧类型检查目前都接受；TS 解释器只改 lambda 调用时的副本，不写回外层。
   truffle 的行为未逐项核对。需要另行决定是拒绝（推荐：与按值捕获一致）还是写回，再补两引擎测试。
4. 其他语言包（zh / de / hi）若在 `postTranslationTransformers` 中列有 `set-to`，需要同样移除；由语言包仓自查。

## 6. 落地与发版顺序

1. 本 ADR（aster-api）合入。
2. aster-lang-core（语法、AstBuilder、类型检查、en-US 词法表）与 aster-lang-ts（解释器作用域链、类型检查）合入，随生态发版。
3. aster-lang-test 的 tier1 样本在 core / ts 发版并更新 CI 引用后合入；在此之前 parity 门对新样本会因 Java 侧解析为 `Let`（E104）而变红。
4. truffle 无需发版；第 5 节第 1 项单独跟进。
