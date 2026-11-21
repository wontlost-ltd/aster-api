# ADR 0062：Int 为 64 位有符号整数，溢出即运行时错误——两引擎的实现方案

- 状态：**PROPOSED**（2026-11-22，待产品负责人采纳；§9 Q1–Q5 附推荐答案）
- 提出：2026-11-22；来源 aster-cloud/aster-lang-truffle#183，eco-1.0.55 Wave C 计划 D6
- 相关：[ADR 0056 修订 A4](0056-amendment-4-parity-rulings-1053.md) §3.2（语义已裁决：Int 64 位、溢出即错、`toInt` 越界报错、补 int32 / 2⁵³ / 2⁶³ 边界）；[ADR 0059 修订 2](0059-amendment-2-package-format-rulings.md)（包格式 v1 的 Int 入参限定 int32）；ADR 0025（Decimal）；[ADR 0053](0053-release-train-artifact-reuse.md)（发布列车）
- 修订：本 ADR 不改 A4 §3.2 的语义，只定**怎样实现**；§4.5 把同一溢出规则延伸到 `Long`（见 Q2）
- 实现仓：`aster-lang-truffle`（主）、`aster-lang-ts`、`aster-lang-test`、`aster-lang-platform`（新增只读导出）
- 列车：**不在 eco-1.0.55**。实现排在之后的列车（Wave C 计划暂定 1.0.58），另立实施计划；上线前必须完成 §7 的执行记录扫描

---

## 0. TL;DR

| 主题 | 决定（一句话） |
|---|---|
| Truffle（生产求值器） | Int / Long 加、减、乘、取负、整除与求和改用精确运算（`Math.addExact` 等），溢出抛 guest 运行时错误；不环绕，不提升 |
| `toInt` | 改为范围检查（`Math.toIntExact` 语义），越界报 guest 错误，不再截断 |
| ts（编辑器预览、CLI、Workers 包检查） | 方案 A：Int 仍是 JS `number`，Int 运算结果超出 ±(2⁵³−1) 即报错；不改 BigInt |
| 2⁵³ 与 2⁶³ 之间 | 记为**有文档的分歧**：ts 报错，JVM 精确（`DIVERGENT-MANIFEST.md`）。int32 边界与 2⁶³ 是 parity case |
| 字面量与入参范围 | 第一期**不变**：Int 字面量与包格式 v1 的 Int 入参仍是 int32；`IntE.value` 仍是 `int` |
| `Long` | 仍是独立类型；同一溢出规则（JVM 本来共用一条运算路径；ts 的 BigInt 结果超出 int64 报错） |
| 错误形态 | 沿用「除数为 0」的先例：guest 运行时错误 + 双语消息，不新开编译期诊断码 |
| 上线前 | 只读扫描生产执行记录中 \|v\| > 2³¹−1 的整数，预期 0；看不到的中间值靠 tier1 eval 与重放批次在候选引擎上补 |

---

## 1. 现状（各仓 `origin/main`，2026-11-22：truffle `e5d785d`、ts 3.6.0 `21752f9`）

| 事实 | 出处 |
|---|---|
| Truffle 的 Int 字面量节点是 32 位 `int` | truffle `core/CoreModel.java:195` `IntE.value` |
| Truffle 的整数加减乘一律 `toLong(a) op toLong(b)`：超出 int32 时静默变成 long，超出 2⁶³ 时**环绕**，没有 `*Exact` | truffle `runtime/Builtins.java:1815-1831` `numericAdd/Sub/Mul` |
| `toInt` 用 `Number.intValue()`，超出 int32 **静默截断**；全文件十余处调用（下标、计数等） | truffle `runtime/Builtins.java:1550-1555` |
| 除数为 0 是 guest `BuiltinException` + 双语消息，没有诊断码 | truffle `runtime/Builtins.java:126-165`、`runtime/ErrorMessages.java:43-46` |
| ts 的 Int 运行时是 JS `number`（2⁵³ 以上失真），Int 运算没有任何范围检查 | ts `src/core/interpreter.ts:1095`、`:1956-1990` |
| ts 的 Long 运行时是 BigInt，Int 与 Long 混算时整数 `number` 精确提升；BigInt 结果**没有** int64 上界 | ts `src/core/interpreter.ts:1107-1118`、`:1905-1932` |
| ts 运行时不区分 Int 与整数值的 Double：`Double` 字面量也求值为 `number` | ts `src/core/interpreter.ts:1097-1099` |
| 两个编译器都把 Int 字面量限定在 int32（`2147483648` 两侧都拒绝，`2147483647` 两侧都接受） | core `LiteralParsing.java:25-38`；ts `lexer.ts:39-40`、`diagnostics.ts:335` |
| 包格式 v1 的 Int 入参限定 int32，超出即 `INPUT_INVALID` | ts `src/policy-package/input.ts:22-23`；ADR 0059 修订 2 |

所以今天超出 int32 的 Int 值**只**来自运算。2⁵³ 以内两引擎结果相同；超出 2⁵³ 后 ts 失真、JVM 精确；超出 2⁶³ 后 JVM 环绕、ts 失真；经 `toInt` 的 builtin 在 JVM 上截断、ts 不截断。

## 2. 目标与非目标

**目标**：A4 §3.2 的语义在两引擎上可验证地成立；任何溢出都是响亮失败，不是错答案；两引擎在 int32 与 2⁶³ 两个边界上行为一致。

**非目标**：放宽 Int 字面量或入参到 int64（另立，随包格式 v2）；合并 `Int` 与 `Long` 两个类型；改变 Double / Decimal 的语义；ts 在 2⁵³ 以上给出精确结果。

## 3. 考虑过的方案

### 3.1 ts 的 Int 表示

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A（推荐）** | 保留 `number`；Int 运算结果超出 ±(2⁵³−1) 即报错 | 只改 Int 运算点与 `toInt` 类 builtin；fail-closed；不影响下标、`Date` 运算、JSON 序列化等所有消费 Int 的地方 | 2⁵³ 到 2⁶³ 之间 ts 报错而 JVM 精确，是一条有文档的分歧 |
| B | 所有 Int 值改为 BigInt | 与 JVM 逐位一致 | 每个 Int 消费点都要改（下标、`Date`、JSON 序列化、`Number.isSafeInteger` 检查等），而策略几乎用不到这么大的值；BigInt 与 `number` 不能混算，回归面很大 |
| C | 混合：`number` 快路径，结果不安全时用 BigInt 精确重算，超出 int64 才报错 | 结果与 JVM 一致 | 两种表示并存，B 的消费点问题照样存在；复杂度与问题不匹配 |

ts 不是生产求值器（Truffle 才是）。用 A，分歧只出现在 2⁵³ 以上、且方向是 ts 报错（fail-closed），不会出现「编辑器给出一个数、生产给出另一个数」。

### 3.2 Truffle 的溢出处理

- **精确运算并报错（推荐）**：`Math.addExact` / `subtractExact` / `multiplyExact` / `negateExact` / `divideExact`（`divideExact(long, long)` 自 JDK 18 起提供，唯一会溢出的情形是 `Long.MIN_VALUE / -1`），捕获 `ArithmeticException` 转成 guest 错误。加、减、乘、取负的 `*Exact` 是 JIT 内建，正常路径几乎没有开销。
- 提升为 `BigInteger`：违反 A4 §3.2「不静默提升」，否决。
- 保持环绕：违反 A4 §3.2，否决。

### 3.3 字面量与入参范围

- **第一期保持 int32（推荐）**：不动源码语法、不动包格式，`IntE.value` 保持 `int`；64 位 Int 值只由运算产生。
- 同步放宽到 int64：要同时改两个词法器、`IntE`、包格式 v1 的入参校验与已导出包的兼容承诺，是另一件事（放宽，随包格式 v2），不和求值语义的变化捆在一起。

### 3.4 错误形态

- **guest 运行时错误 + 双语消息（推荐）**：与「除数为 0」完全同构（`ErrorMessages` 新增一个构造方法，ts `InterpreterError` 用同一英文文本），调用方已有的运行时错误处理路径不用改。
- 新开一个诊断码：现有运行时错误都没有码，E9xx 段已有编译期诊断（如 E900）在用；单为这一个错误引入运行时码体系，不值得。

## 4. 决定（PROPOSED）

### 4.1 Truffle

1. 整数加、减、乘（`numericAdd/Sub/Mul`）、一元取负、`intdiv`（用 `Math.divideExact`；普通 `/` 在 `Long.MIN_VALUE / -1` 时环绕，必须换掉，且这一边界单独配测试）、`List.sum` 及其他对整数求和 / 累乘的 builtin 一律用 `Math.*Exact`；溢出抛 `BuiltinException(ErrorMessages.arithmeticIntegerOverflow(op))`。完整 builtin 清单由实施计划逐个列出并各配一条测试。
2. `toInt` 改为：数值先取 long，超出 int32 抛 guest 错误（`Math.toIntExact` 语义），不截断。各调用点的报错文本带上 builtin 名。
3. `IntE.value` 保持 `int`（§4.4）。
4. `mod` 的整数路径（long `%`）不会溢出，不改。

### 4.2 ts

1. Int 运算（`+`、`-`、`*`、`//`、取负，以及 `toInt` 类 builtin 的结果）超出 ±(2⁵³−1) 时抛 `InterpreterError`，英文文本与 Truffle 相同。
2. **Int 与整数值 Double 必须区分**：ts 运行时二者都是 `number`（§1），只看「操作数是安全整数、结果不是」会把 `1e10 * 1e10` 这类 Double 运算误判为溢出。实施计划必须先确定 ts 怎样知道一次运算是 Int 运算（例如由类型检查结果给 IR 二元运算标注操作数类型，或给 Double 值加运行时标记），再落地本条；在没有可靠类型信息的位置不得报溢出。见 Q4。
3. 2⁵³ < |v| ≤ 2⁶³−1 的结果：ts 报错，JVM 精确。登记到 aster-lang-test `DIVERGENT-MANIFEST.md`（方向：ts fail-closed）。

### 4.3 两引擎一致的边界

| 情形 | Truffle | ts |
|---|---|---|
| 结果在 int32 内或刚超出 int32（`2147483647 * 2`） | 精确 `4294967294` | 精确 `4294967294` |
| 结果在 2⁵³ 与 2⁶³ 之间（`x * x * x`） | 精确 | **报错**（有文档的分歧） |
| 结果超出 2⁶³（`x * x`，x 足够大） | 报错 | 报错 |
| `toInt` 作用于 2³¹ | 报错 | 报错 |

### 4.4 字面量与入参

第一期不变：Int 字面量 int32（两个编译器），包格式 v1 的 Int 入参 int32。放宽到 int64 另立 ADR，随包格式 v2。

### 4.5 Long（Q2）

`Long` 仍是独立类型，不影响任何源码。JVM 上 Int 与 Long 共用 `numericAdd/Sub/Mul`，§4.1 的精确运算自然覆盖 Long；为保持两引擎一致，ts 的 BigInt Long 运算结果超出 int64 时同样报错（今天 ts 的 BigInt 没有上界，JVM 环绕，本来就不一致）。

### 4.6 错误

guest 运行时错误，消息双语，形如「算术错误：整数溢出（{op}）/ integer overflow ({op})」，附提示「需要更大的范围时改用 Decimal」。不新增编译期诊断码，不改 locales 的诊断表。

## 5. 后果

- **生产求值行为改变**：超出 2⁶³ 的环绕与 `toInt` 截断从「静默错答案」变成运行时错误。受影响的只有真正溢出的执行；§7 的扫描用来证明生产里没有（或逐条解释）。
- **ts**：2⁵³ 以上的 Int 结果在编辑器预览、CLI 与 Workers 包检查里报错，生产照常精确计算。作者在预览里看到的错误会提示改用 Decimal。
- **性能**：`Math.*Exact` 是 JIT 内建；ts 每次 Int 运算多一次 `Number.isSafeInteger` 判断。实施计划在 Truffle 求值基准与 ts eval 套件上量一次前后差异。
- **重放**：历史执行若曾依赖环绕或截断，在新引擎上重放会从「有结果」变成「错误」，表现为重放差异；§7 的扫描与重放批次正是为此。
- **编译与 IR 不变**：位置无关哈希、`CompileIdentity` 之外的编译产物都不变；`toolchainId` 随引擎版本照常变化。

## 6. 迁移与回滚

- **源码迁移**：无。没有语法或类型变化。命中溢出的策略由作者改用 `Decimal`（ADR 0025）或调整运算，重新发布。
- **发布顺序**：truffle 与 ts 的实现、aster-lang-test 的 case 同列车合入；platform 新增的只读导出（§7）先于 aster-api 的 pin bump 合入并跑过。
- **回滚**：aster-api 的 pin 退回上一列车版本即恢复旧求值行为（ADR 0053 的列车产物可复用）；ts 侧退回上一版本。两者都不涉及数据迁移。

## 7. 门禁与扫描计划

这是**求值**变化，不是编译变化：源码门禁扫描（ts scan / java-gate）看不到它。上线（aster-api pin bump）之前必须完成下面三步，结果写在实现 PR 与发版记录里。

1. **新增只读导出** `aster-lang-platform/scripts/gate-scan/export-int-range.sql`，与现有 `export-*.sql` 同样的规则：`READ ONLY` 事务、同一快照的 `{"_count":N}` 完成标记、只经 `gate-scan.sh export` 运行、导出目录 `700` / 文件 `600`、用后删除。查询找出 JSON 数值中**整数且 |v| > 2³¹−1** 的记录，只输出记录 id、表名、JSON 路径与量级档（> 2³¹、> 2⁵³、> 2⁶³），**不输出值与其他内容**。覆盖的表（列名以实施时的 schema 为准）：
   - aster-api：`guard_decision_inputs`（`action_json` 等 JSONB 列）、`guard_decisions`、`replay_batch_item`；
   - aster-cloud：`"Execution"`（`input` / `output`）、`"ExecutionOutcome"`、`"DemoExecution"`。
   预期 0 命中。命中时按 id 逐条判断：属于 `Long` 字段（不受影响，除非超出 2⁶³）、Double 字段（不受影响），还是 Int 运算结果（需要作者处理）。
2. **中间值**：导出只看得到入参与最终输出，看不到运算中间值。用候选引擎跑 tier1 eval 全量与生产策略的重放批次，`newly-failing` 必须为 0，或逐条解释。
3. **源码 sanity**：java-gate 与 ts scan 各跑一次，预期编译结果无变化（本 ADR 不改编译）。

命中未处理完不得发版。扫描只报 id 与量级，不在聊天、日志、提交或 PR 中打印任何策略源码或执行数据。

## 8. 测试

- aster-lang-test 新增 `scripts/cases-spec-int-width.json`（`expectError` 行）：§4.3 表中四行，外加 `toInt` 在 −2³¹−1、`Long` 在 2⁶³ 的边界，以及 `intdiv(Long.MIN_VALUE, -1)`。2⁵³ 那一行在 `DIVERGENT-MANIFEST.md` 登记。
- truffle：每个改为精确运算的 builtin 一条溢出测试、一条边界内测试。
- ts：Int 溢出、Double 的大整数值不误报（Q4 的关键回归）、Long 超出 int64。

## 9. 待产品负责人裁决（附推荐）

| # | 问题 | 推荐 |
|---|---|---|
| Q1 | ts 的 Int 表示 | 方案 A：`number` + 超出 ±(2⁵³−1) 报错（§3.1） |
| Q2 | 溢出规则是否同样适用于 `Long` | 是（§4.5）；JVM 本来共用运算路径，ts 对 BigInt 补 int64 上界 |
| Q3 | 错误形态 | guest 运行时错误 + 双语消息，同「除数为 0」，不新开诊断码（§3.4） |
| Q4 | ts 怎样区分 Int 运算与整数值 Double 运算 | 由实施计划先做一次调研给出方案（IR 类型标注优先）；在此之前 §4.2 第 1 条不落地 |
| Q5 | 字面量与入参范围 | 第一期保持 int32（§3.3），放宽另立 |

## 10. 与其他 ADR 的关系

- ADR 0056 修订 A4 §3.2：本 ADR 是其实现方案，语义不变；§4.5 把规则延伸到 `Long`（Q2 采纳后生效）。
- ADR 0059 修订 2：包格式 v1 的 Int 入参范围不变。
- ADR 0025：Decimal 不受影响，并作为大数值的推荐替代。
- ADR 0053：实现随后续列车发布，aster-api pin bump 以 §7 扫描通过为前提。
