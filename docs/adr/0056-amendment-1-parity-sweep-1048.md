# ADR 0056 修订 A1：1.0.48 双引擎 parity 清扫的语法裁决

- 状态：**PROPOSED**（2026-11-16）
- 日期：2026-11-16
- 修订对象：[ADR 0056](0056-type-inference-parity.md) §4.1（manifest 能力检查）；补充两引擎共同接受的语法边界（规则头、`X of` 参数、类型表里的逗号）
- 相关：aster-lang-core#266、#245；aster-lang-ts#324、#304、#314、#320、#310、#309；aster-lang-test#174；Batch B（core#270 / ts#326 / test#208）遗留的 `X of` 分隔符分歧
- 实现落点：`aster-lang-core`、`aster-lang-ts`、`aster-lang-test` 同名分支 `fix/parity-sweep-1048`

---

## 1. 背景

1.0.48 列车前对 Java（ANTLR）与 ts（手写解析器）做了一轮差异清扫。裁决原则：

1. 保持既有有效源码的含义不变。生产编译走 core，所以「core 接受、ts 拒绝」一般按 core 修 ts；「ts 接受、core 拒绝」的写法从未在生产编译通过，收紧 ts 不改变任何已部署策略。
2. 真有两种读法的写法，两引擎都拒绝，并给出改写提示。
3. 每条裁决都用 aster-lang-test 的 tier1 样本（正例或 `mustReject`）锁住。

## 2. 改变语言行为的裁决

### 2.1 规则头写在一行（core#266）

规则头从 `Rule` 到结尾的 `:` / `.`（含 `It performs …`）必须写在一行。core 文法 `funcDecl` 从来不收 NEWLINE；ts 此前在规则头里跳过 NEWLINE / INDENT / DEDENT，接受「逗号后换行再写 produce」「given 后逐行列参数（函数体与参数同缩进）」「`produce T.` 换行再写 `It performs`」等写法。

裁决：两引擎都拒绝。理由：这些写法从未在生产编译通过；续行比语句缩进更深时要吞掉 INDENT，正是 [ADR 0026](0026-multiline-expression-continuation.md) §2 排除的布局歧义（续行只允许等缩进）。如果以后要支持多行规则头，需要单独的 ADR 设计 INDENT / DEDENT 配对。

影响：ts 编辑器 / LSP 对这些写法改报解析错误。core 行为不变，存量策略不受影响。

### 2.2 `X of` 参数与下一个形参 / 字段的边界（Batch B 遗留）

`X of A` 之后的 `and` / `,`：

- 后面是「名字 as」时，分隔符属于外层，开始下一个形参或字段（`given x as Pair of Int, y as Text`、`given x as result of Int and y as Text`）；
- 后面的记号不能开始类型（如 `produce`）时，同样属于外层；
- 否则在元数允许时属于 X（list / option 恰一个参数，result 至多一个失败类型，自定义泛型不限），并归最内层还能接参数的 X（core#256 已定）。

`result of A, B` 与 `result of A and B` 同义。这是 core 一直以来的行为；ts 此前贪婪吞掉分隔符后报 E056 / E055，现在与 core 一致。

### 2.3 括号类型表里的歧义逗号报 E064

在函数类型的参数表 `( … ) -> R`（以及 core 独有的尖括号泛型 `X<…>`）里，`X of A, B` 的逗号既可以是 X 的下一个参数，也可以是类型表的下一项。此前 core 读作前者（`(result of Int, Text) -> Bool` = 一参函数），ts 读作后者（二参函数），两端 Core IR 不同。

裁决：只要 X 还能接参数，这个逗号两引擎都报 **E064** `Unexpected ','`，提示写 `and`，或给 X 加括号：`(result of Int and Text) -> Bool`、`((Pair of Int), Text) -> Bool`。元数已满的 X（list、option、已写失败类型的 result）后的逗号不歧义，仍归类型表。

影响：core 上 `(result of A, B) -> R` 这类源码从「接受」变为 E064。语料与生产扫描未见此写法；1.0.48 门禁扫描应按 §4 的正则复核。

ts 格式化器同步修正：类型参数后面紧跟分隔符、且自身以开放元数的 `X of …` 结尾时加括号；`Result` 用 `and` 输出（core 不收 `or`）。此前格式化器会把 `((Pair of Int), Text) -> Bool` 打印成 `(Pair of Int, Text) -> Bool`，静默改变含义。

### 2.4 manifest 能力检查（修订 ADR 0056 §4.1 的「范围外」条目，ts#320）

按 §4.1 给出的方向统一：

- 受 manifest 约束的能力 = 显式声明的能力 ∪ 函数体实际用到的能力。裸 `It performs io` / `cpu` 不再在 ts 侧展开成预置能力。
- manifest 的 `io` / `cpu` 类别键按 `shared/capabilities.json` 的 `class` 展开，两引擎相同：`io` 含 Http、Network、Sql、Time、Files、Secrets、Process、AiModel、Payment、Inventory；`cpu` 含 Cpu、Crypto。core 的 `ManifestReader` 此前不认 `io`（报未知能力），把 `cpu` 当作只有 Cpu；ts 的 io 是漏了四项的硬编码表。同一张 manifest 表里，具体能力键覆盖类别键，结果与键顺序无关。
- deny 优先于 allow（两引擎原本一致）。

两引擎的 manifest 文件格式仍不同（ts 按能力列模块 / 函数模式，core 是能力名列表），统一格式不在本修订范围内。manifest 只用于 CLI / LSP 与测试，aster-api、aster-cloud 不读取，不影响生产编译。

## 3. 只锁定、不改行为的裁决

| 写法 | 裁决 | 来源 |
|---|---|---|
| `Return x is High.` | 两引擎都拒绝。相等写 `equals to` / `is equal to`；`is` 只出现在 `is under` / `is over` / `is less than` 等多词比较里。issue 提出时 ts 接受，当前 main 上 ts 已拒绝（E059） | core#245 |
| `not(y equals k)` | 两引擎都拒绝：`equals` 单独不是运算符。core 挂错误监听器后同样拒绝（原 Java 测试没挂监听器） | ts#324 |
| `It performs teleport` | 两引擎都接受，检查阶段按 pure 处理（ts#308 已修，`effect-unknown-name` 样本已锁） | ts#304 |
| E601 | 保留码：两引擎都没有发出点；码表 help、parity 夹具说明同步更新 | ts#309 |

## 4. 1.0.48 门禁扫描

只有 §2.3 会让 core 上原本能编译的源码失败（报 E064），其余裁决不改变 core 的接受集合与 Core IR。权威检查是列车门禁扫描本身：用新旧 core 编译全部生产策略源码，新出现的编译失败即命中。预筛正则（策略源码，会有少量误报，命中后人工确认逗号是否在函数类型参数表内、前面的 `X of` 是否还能接参数）：

```
\(\s*[^()]*\b(?:result|[A-Z]\w*)\s+of\s+[^(),]+,
```

命中的策略需要作者改写为 `and` 或加括号后重新发布。

## 5. 未在本修订处理（另开 issue）

清扫中发现、超出本轮范围的分歧：ts 不支持尖括号泛型 `Pair<Int, Text>`；ts 形参名不能大写（`given Customer as Text`）；ts 不支持形参注解（`given @pii(L1, email) y as Text`）；ts 接受 `result of A or B` 而 core 拒绝。
