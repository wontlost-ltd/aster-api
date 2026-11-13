# ADR 0055：Core IR 交换形状 —— 以 Java CoreModel V1 为规范，TS 方言经显式别名被严格读方接受

- 状态：**Proposed**（2026-11-14 草案，待用户确认）
- 提出：2026-11-14；来源 aster-cloud/aster-lang-ts#314（ts `compile()` 产出的 Core IR 不能被 Java `aster.core.ir.CoreModel` 反序列化）
- 相关：ADR 0016（Core IR 字段级 parity；本 ADR 不推翻其 D1 的「parity 比较走归一」，只为**跨引擎交换**另立契约）、ADR 0039 §10（注解 IR 容器形状分歧，「真正统一推迟」）、ADR 0037（contentHash 与 parity 共用 `ir-normalize.ts`）、ADR 0051（`origin.written/role`）、ADR 0046 §11（`inputRefs`）
- 涉及仓：aster-lang-core（主改动）、aster-lang-test（新门禁）、aster-api（升级 core + 回归用例）、aster-cloud（文档）；aster-lang-ts、aster-lang-truffle、aster-lang-runtime **不改**

---

## 0. TL;DR

两引擎的 Core IR 不是「差一两个字段」，而是**两种方言**：TS 多出 `constraints` / `declaredEffects` / `typeInferred`，注解用 `args:[{name,value}]`、导入用 `name/asName`；Java 用 `params:{k:v}`、`path/alias`。parity 门禁一直在 `ir-normalize.ts` 里把它们归一后比较（全语料 425/425 等价），所以「ts IR 能否被 Java 读入」从来没被测过。

实测（§2）：用 aster-api `/evaluate-json` 同款的 vanilla `ObjectMapper` 读 ts IR，**459 份里只有 56 份（12%）能读入**；改用宽松 mapper 虽然全部读入，但有 **65/425 份（15%）静默丢语义**——`@id` / `@control` 规则元数据与 `Import` 路径被吃掉，评估照常返回、证据链却缺了 ruleId 与 controls。

| 决定 | 内容 | 不做什么 |
|---|---|---|
| D1 规范形状 | **Java `CoreModel`（`CoreIrSchemaVersion.V1`）的基础形状 + 登记在案的可选扩展字段**是规范交换形状；持久化、`/evaluate-json`、Truffle 都以它为准 | 不改 Java 的输出（逐字节不变） |
| D2 读方接受 TS 方言 | core `CoreModel` 用**显式**别名 / 折叠 / 校验吸收 5 处 TS 方言（§5.2 表），约 30 行；效应多态（`effectParams` / `EffectVar`）**明确拒收** | 不加 `@JsonIgnoreProperties(ignoreUnknown = true)`，不在 `/evaluate-json` 改用宽松 mapper |
| D3 公开入口保持严格 | `/evaluate-json` 继续拒收未知字段 | 不为「新版本生产者 → 旧读方」静默降级 |
| D4 修订 V1 契约措辞 | 「新增可选字段不算 breaking」改为「对已识别该字段的读方不 breaking；严格读方会拒收」 | 不 bump schema major |
| D5 新 parity 门 | aster-lang-test 增 `--mode=ir-interop`：ts IR 经 Java 严格读入、再序列化后，与 Java 自产 IR 归一等价，且不经归一器逐字段核对别名字段与约束未丢；再加交叉执行 | 不替代既有 `--mode=ir --full` |
| D6 TS 不改形状 | ts 的 IR 产物与内存模型本 ADR 不动；统一命名推迟到 schema v2（与 ADR 0037 节点身份合并） | 不在 v1 内重命名 TS 字段 |

原型已验证（§2.4）：只改 core 读方，ts IR **459/459 严格读入**，回写后与 Java 自产 IR **425/425 归一等价**，Java 自产 IR 对 478 个语料文件**逐字节不变**。不需要任何数据迁移（§7）。

---

## 1. 背景

### 1.1 现象（ts#314）

ts 1.5.0 `compile()` 的 `core` 提交到 `POST /api/v1/policies/evaluate-json`：

```
Unrecognized field "constraints" (class aster.core.ir.CoreModel$Field) …
（删掉后）Unrecognized field "args" (class aster.core.ir.CoreModel$Annotation), known properties: "name", "params"
```

`DynamicCnlExecutor.executeCoreIrJson`（`replay/src/main/java/io/aster/policy/parser/DynamicCnlExecutor.java:316`）用 `ReplayMappers.DEFAULT` 反序列化——vanilla `new ObjectMapper()`，`FAIL_ON_UNKNOWN_PROPERTIES=true`。于是 ts IR 只能先用 Java 引擎重编译才能执行。

### 1.2 为什么一直没被发现

ADR 0016 D1 明确选了「**归一比较，而非强行对齐字段名**」：parity 门禁把两侧 IR 都送进 `aster-lang-test/packages/js/src/ir-normalize.ts`，那里有 `IR_FIELD_ALIASES`（`Import.name→path`、`asName→alias`）、`foldAnnotationArgs`（`args` 数组折叠成 `params` 映射，ADR 0039 §10）、`declaredEffects→effects` 小写集、`constraints`/`typeInferred` 作为推断层剥离。归一之后两侧 425/425 一致，门禁全绿——但**没有任何一道门把 ts IR 交给 Java 的反序列化器**。parse / IR / eval 三道 parity 门都是「各引擎吃自己的 IR」。

### 1.3 已有的规范线索

| 线索 | 位置 | 含义 |
|---|---|---|
| `CoreIrSchemaVersion.V1`（1.0，承诺至 2027-12-01） | aster-lang-core `src/main/java/aster/core/ast/CoreIrSchemaVersion.java` | 已发布节点的 JSON 形态「只增不改不删」；改字段名 = breaking。**契约挂在 Java core 上** |
| `Lambda.ret` 上的 `@JsonAlias("retType")`（注释「兼容 TypeScript 使用的 retType 字段名」） | `CoreModel.java` `Lambda` | Java 读方吸收 TS 方言**已有先例** |
| `PatCtor.names`（「遗留字段，保持向后兼容」）与 `args` 并存 | `CoreModel.java` `PatCtor` | 同上 |
| parity 归一的目标命名全是 Java 名（`path`、`alias`、`params`、`effects`） | `ir-normalize.ts` | 比较空间里 Java 名已是事实规范 |
| ADR 0039 §10「真正统一（改 TS lowering 或 Java 输出）牵涉 TS IR 消费者与 aster-cloud」 | 本仓 ADR 0039 | 留了口子，没定方向 |

没有任何 ADR 或契约文档定义「跨引擎交换时以哪一侧为准」。本 ADR 补上这一条。

---

## 2. 实证：全语料形状差异

### 2.1 方法（可复现，脚本未入库）

- **样本**：aster-lang-test `origin/main`（b595d6a）`corpus/` 下除 `tier1-reject/` 外全部 478 个 `.aster`（含 tier1-equivalence 290、tier1-parity 词汇样本、conformance、tier3-fixtures）。带 `.meta.json` 的按其 `lexicon`，带 `.vocab.json` 的按其词汇编译。
- **TS 侧**：aster-lang-ts `origin/main`（eb6e89c，1.6.0）新构建，调用公开的 `browser.compile(source, { lexicon, vocabulary })`（cloud 用的同一出口），取 `result.core`。成功 459 份。
- **Java 侧**：aster-lang-core `origin/main`（a35953f，1.0.45）既有的 `CoreIrFingerprintCli`（`-Dparity.ir.full=true`，与 parity 门禁同一入口）。成功 425 份；失败的 34 份为 31 个 tier3 旧语法 workflow/async 夹具（Java 解析器拒收）与 3 个 hi-IN 样本（本地验证环境未装 hi 语言包），均与形状无关。
- **结构比较**：对两侧都成功的 425 份，按节点类别（有 `kind` 用 `kind`，否则用「父类别.字段」）统计每个字段的出现次数与 JSON 值类型；另做位置对齐的取值比较。
- **反序列化**：一次性 JUnit 草稿（未提交）把 459 份 ts IR 逐份交给 `CoreModel.Module`：严格（vanilla mapper，= `/evaluate-json`）记首个错误；宽松（`DeserializationProblemHandler` 跳过并登记每个未知字段）记全部未知字段，并把读入结果重新序列化，经 `ir-normalize.ts`（parity 同款选项）与 Java 自产 IR 比较。

### 2.2 分岔清单

**A 类：严格读方直接拒收（阻断 `/evaluate-json`）**

| # | 节点 | TS | Java | 出现 | 语义 |
|---|---|---|---|---|---|
| A1 | `Param`（Func 与 Lambda 形参） | 恒有 `constraints`（920 个形参中 919 为 `[]`） | 无此字段 | 938 处 / 323 份 | 形参约束（`given score between 300 and 850`） |
| A2 | `Field`（Data 字段） | 恒有 `constraints`（999 个中 989 为 `[]`，10 个为 `Range{min,max}` / `Required`） | 无此字段；**Java 解析器接受约束语法但不入 IR** | 1013 处 / 130 份 | 字段约束（仅元数据，两侧运行时都不执行） |
| A3 | `Func` | `declaredEffects`（与 `effects` 同内容，另可含 `EffectVar`） | 无 | 137 处 / 98 份 | 与 `effects` 冗余 |
| A4 | `Annotation` | `args: [{name:"$0", value:"R-1"}]`；无参时省略 | `params: {"$0":"R-1"}`；无参时 `{}` | 119 处 / 60 份 | **`@id` / `@control` 规则元数据**（ADR 0039 §3） |
| A5 | `Import` | `name` / `asName`；省略 `version` | `path` / `alias` / `version:null` | 12 处 / 10 份 | 导入路径与别名 |
| A6 | `Func` | 效应多态函数带 `effectParams:[…]`，`declaredEffects` 里含 `{kind:"EffectVar", name}` | 无效应多态 | **语料 0 份**（源码审读发现：`lower_to_core.ts` 效应循环、`core/core_ir.ts` `Func` 构造器） | 效应变量；Java 执行路径不支持 |

严格读入 459 份：**通过 56（12.2%）**；首个错误分布 A1 189、A2 127、A3 70、A5 10、A4 7。宽松读入时登记到的未知字段恰为 A1–A5，无其它。A6 是语料盲区：tier1 语料没有效应多态样本，只能从 ts 源码确认它会出现。

节点 `kind` 集合两侧一致（对照 ts `core/core_ir.ts` 构造器与 Java `@JsonSubTypes`），分岔全部在字段层。

**B 类：Java 读方已静默容忍（不报错）**

| # | 节点 | 差异 | Java 现状 |
|---|---|---|---|
| B1 | `Param` | TS 带 `typeInferred`（675/920） | `@JsonIgnore`，读入即丢（R2 旁路信息，契约上本不进 JSON） |
| B2 | `Lambda` | TS `retType`，Java `ret` | 已 `@JsonAlias("retType")` |
| B3 | `PatCtor` | TS `names:[…]`，Java `args:[PatName…]` | 两字段并存，Truffle 两者都读 |
| B4 | 多处 | TS 省略空 `annotations` / `retAnnotations`、`piiLevel`、`piiCategories`、`Import.version`、`origin.file` | 读入时取 Java 缺省值，无语义差 |

**C 类：同字段、取值不同（能读入，属推断层或 lowering 选择）**

| # | 位置 | TS | Java | 出现 | 说明 |
|---|---|---|---|---|---|
| C1 | `Func.effects[]` | `IO`（保留源码大小写） | `io` | 116 值 / 79 份 | Java 消费方大小写不敏感（`CapabilityChecker.normalizeEffects` 小写化）；Truffle 为大小写敏感比较，但其 IO 内建本就不支持 |
| C2 | `Func.ret` / `typeParams` | 省略返回类型时 `TypeVar 'Unknown'` + `typeParams:["Unknown"]` | 推断出具体 `TypeName`，`typeParams:[]` | 686 / 678 处，约 320 份 | ADR 0016 §B 推断层 |
| C3 | `TypeName.name` | 未标注形参 / 字段的推断类型 | 推断策略不同（如 `Bool` vs `Text`） | 118 处 / 50 份 | 同上；`NamedContextMapper` 按形参 `TypeName` 找 Data 声明，**交叉执行时可能影响上下文映射**（§10） |
| C4 | `Func.effectCaps` | 按 stdlib 命名空间预置 | 另行推导 | 56 处 / 36 份 | 能力推断层 |
| C5 | `Case.pattern` | 0 参枚举变体 `PatName{"Red"}` | `PatCtor{typeName:"Red", args:[]}` | 12 处 / 7 份 | Truffle `PatNameNode` 按首字母大写区分变体，两形都能执行 |
| C6 | `origin` | `TypeName` 只在 1133/2262 处带 origin；`FuncType` 不带；`end.line` 个别不同 | 全部带 | — | ADR 0037 / ledger 已记录 |

C 类不阻断反序列化，也不属于「形状」；列出是因为它们决定了「ts IR 交给 Java 运行时执行」是否等价，由 D5 的交叉执行覆盖。

### 2.3 宽松读入不是解法

把 ts IR 宽松读入再写回，与 Java 自产 IR 归一比较：**360/425 等价，65 份不等**。65 份全部是 A4（`@id` / `@control` 被丢，`params` 变成 `{}`）或 A5（`Import.path` 为 null）。而 parity 归一器认为原始 ts IR 与 Java IR **425/425 等价**——差的正是读方丢掉的那部分。

这条路径今天已经在跑：core 的 `TypeCheckCli`（读 ts `emit-core` 输出）以及 aster-api 的 `PolicyEvaluationService.parseCoreModule`、`CedarExportService.readModule`、`StabilityEnforcement` 都用 `FAIL_ON_UNKNOWN_PROPERTIES=false`。后三者目前只读 Java 自产的 `core_json`，所以没出事；一旦它们读到 ts IR，规则元数据就会无声消失。

### 2.4 原型验证（只改 core 读方，未提交）

在 core 的一次性 worktree 里给 `CoreModel` 加 §5.2 的别名 / 折叠 / `constraints` 字段（简化版：`declaredEffects` 直接忽略、`args` 不做校验，约 15 行；语料无 A6 样本，校验与拒收分支不影响下表），重跑：

| 检查 | 结果 |
|---|---|
| ts IR 严格读入（vanilla mapper） | **459/459** |
| 读入 → 再序列化 → 与 Java 自产 IR 归一比较 | **425/425** 等价（宽松读入为 360/425） |
| Java 自产 IR（478 个语料文件，`CoreIrFingerprintCli --full`）改动前后 | **逐字节一致**（另见 §10 的 origin.col 不确定性，与本改动无关） |

---

## 3. 消费方清单

| 仓 | 组件 | 产 / 读 | 假定形状 | 读方严格度 |
|---|---|---|---|---|
| aster-lang-core | `CoreLowering` + `NameResolution` → `CoreModel` → Jackson | 产 | Java（V1 契约所有者） | — |
| aster-lang-core | `TypeCheckCli`（读 ts `emit-core` 输出） | 读 ts IR | 按 Java 读 | 宽松 → **A4/A5 静默丢失** |
| aster-lang-core | `CoreIrFingerprintCli`（测试源） | 产，供 parity | Java | — |
| aster-lang-ts | `lower_to_core.ts` / `core/core_ir.ts` | 产 | TS | — |
| aster-lang-ts | 类型检查、治理检查（读 `args` 的 `$0`）、效应推断（读 `declaredEffects`）、`core/interpreter.ts` 的 `evaluate()`、`pretty_core` | 读，**内存对象** | TS | 不经 JSON |
| aster-lang-ts | CLI `aster truffle` / `class`、`emit-core` | 把 ts IR 写成 JSON 交给 Truffle runner / ASM emitter / core `TypeCheckCli` | TS | 见各读方 |
| aster-lang-truffle | `Loader` + 自有 `aster.truffle.core.CoreModel` DTO | 读 | 并集：`PatCtor.names`+`args`，`Import.path`（导入已展开，不读） | 宽松 |
| aster-lang-runtime | — | 不涉及 Core IR | — | — |
| aster-api | `PolicyCompiler` → `policy_versions.core_json`（JSONB，**持久化**） | 产 | Java | — |
| aster-api | `DynamicCnlExecutor.executeCoreIrJson`（`/evaluate-json`；replay / runner 共用） | 读外部输入 → 用 Java mapper 重新序列化 → Truffle | Java | **严格**（vanilla） |
| aster-api | `PolicyEvaluationService.parseCoreModule`、`CedarExportService.readModule`、`StabilityEnforcement` | 读 `core_json` | Java | 宽松 |
| aster-api | `ModuleResolver`、`ParameterSchemaExtractor` | 读进程内编译结果（`parseResult.core()`），不经 JSON | Java | — |
| aster-cloud | 浏览器 `compile()`；demo 页 `evaluate(core)`；`core-module-summary.ts`（只读 `name`、`decls[].kind/name`、`profile`、`inputRefs`） | 产 + 读，**内存** | TS（只用两侧共有子集） | — |
| aster-cloud | `policy-control-references.ts` | 读 `parseSource` 的 **AST** 注解 `args`，不是 Core IR | — | — |
| aster-cloud | 数据库 | **不存 IR**（`PolicyVersion` 只存源码；见 `src/lib/policy-control-references.ts` 文件头） | — | — |
| aster-cloud | 文档 `docs/api/policies/evaluate-json/*.mdx` | 示例 payload 是虚构结构（`moduleName` / `functions` / `rules`），**两种方言都不是** | — | — |
| aster-lang-test | `ir-normalize.ts`（parity `--mode=ir --full` 与 ADR 0037 contentHash 共用） | 读两侧 | 把 TS 名归一成 Java 名 | 归一，不反序列化 |

要点：**持久化的 IR 只有 aster-api 的 `core_json`，且全部由 Java 产出**；ts IR 只活在内存、临时文件（`build/last-core.json`）和 `/evaluate-json` 的请求体里。

---

## 4. 约束

1. 不改任何一侧的 IR **输出**形状（本 ADR 的前提；输出变化牵动 parity 基线、golden、contentHash 与 `core_json`）。
2. 不能用「宽松读入」解决（§2.3：静默丢规则元数据比报错更糟，治理证据链会缺 ruleId / controls 而无人察觉）。
3. V1 契约承诺「改字段名 = breaking」至 2027-12-01：v1 内任何一侧重命名都违约。
4. 改动应落在**一个**地方，并有门禁防止该处与 parity 归一器各自漂移（`ir-normalize.ts` 文件头记录过多次「单源漂移」事故）。

---

## 5. 决策

### 5.1 D1：规范交换形状 = Java `CoreModel`（V1）

理由：V1 契约挂在 core；唯一持久化的 IR（`core_json`）是 Java 形状；`/evaluate-json`、Cedar 导出、规则元数据、Truffle 执行链全部以 `CoreModel` 为入口；parity 归一的目标命名本来就是 Java 名。选 TS 为规范则要么重写 Java 输出（违反约束 1、3，且要迁移 `core_json`），要么所有 Java 读方都得懂 TS 名——等于没有规范。

规范形状 = `aster.core.ir.CoreModel` 的 Jackson 序列化结果（**基础形状**），加本 ADR 登记的**可选扩展字段**。扩展字段的含义是：读方必须接受并在回写时保留，Java 生产者可以不输出。目前只有一项：`Field.constraints` / `Param.constraints`（来源 TS，Java lowering 暂不产出，见 §10）。新增扩展字段须修订本 ADR。本 ADR 不另写 JSON Schema（见 §10 未决）。

注意「Java 能读入 ts IR」不等于「两侧产出相同的规范输出」：ts 仍产出 TS 方言，只是它被规范读方接受。

### 5.2 D2：core 读方显式接受 TS 方言

在 `CoreModel` 上逐条声明（**只影响反序列化**，Java 输出不变）：

| 分岔 | 读方处理 | 理由 |
|---|---|---|
| A5 `Import.name` / `asName` | `path` 加 `@JsonAlias("name")`，`alias` 加 `@JsonAlias("asName")` | 与既有 `Lambda` 的 `@JsonAlias("retType")` 同一做法 |
| A4 `Annotation.args` | 加 `@JsonSetter("args")`，把 `[{name,value}]` **按原顺序**折叠进 `params`（`LinkedHashMap`）；只改容器形状、不改值。每项必须有字符串 `name` 且带 `value` 键，否则抛 `JsonMappingException`；同名参数重复即拒收（不做静默覆盖）；同一注解同时出现 `args` 与 `params` 也拒收 | 与 `ir-normalize.ts` 的 `foldAnnotationArgs` 同一规则；丢掉它会丢规则元数据 |
| A1/A2 `constraints` | `Field` / `Param` 增可选字段 `constraints`（`@JsonInclude(NON_EMPTY)`，缺省 null） | 约束是源码语义而非推断噪声，读入后经 `executeCoreIrJson` 回写时应保留；Java 自身不填，输出逐字节不变 |
| A3 `Func.declaredEffects` | 加 `@JsonSetter("declaredEffects")`：元素全为字符串时丢弃（TS lowering 中它就是 `effects` 加效应变量，无效应变量时内容与 `effects` 相同）；出现任何 `EffectVar` 对象即抛错 | 只对 Java 反序列化 / 执行路径成立，不承诺回写给 TS 读方时保真 |
| A6 `Func.effectParams` / `EffectVar` | 不加别名，保持未知字段 → 严格读方拒收（错误信息写明「效应多态不受 Java 执行路径支持」） | Java 与 Truffle 都没有效应变量；忽略它会把多态函数当作只有具体效应执行，属于静默改语义 |
| C1 `effects` 大小写 | 可选：`effects` 的 setter 规范为小写 | Java 自产已是小写；读入 ts IR 后与 Java 自产同形，下游无需各自小写化 |

A3 / A6 的接受范围因此是：**不含效应多态的 ts IR**。这与 Java 前端的能力边界一致（Java 解析器不接受效应参数），不是新增限制。

**不**加类级 `@JsonIgnoreProperties(ignoreUnknown = true)`：新的未知字段必须继续在严格读方处报错，这是两种方言再次漂移时唯一的报警。

别名表是**规范性**的：它与 `ir-normalize.ts` 的 `IR_FIELD_ALIASES` / `foldAnnotationArgs` 必须描述同一组对应关系，由 D5 的门禁机械保证。

### 5.3 D3：公开入口保持严格

`/evaluate-json` 继续用 vanilla mapper。D2 之后 ts IR 能读入，未知字段仍返回 400 `invalid_request`（`Core IR JSON 解析失败: Unrecognized field …`）。显式失败优于静默执行一个缺了治理元数据的策略。

### 5.4 D4：修订 `CoreIrSchemaVersion` 的契约措辞

现文：「新增可选字段不算 breaking（旧消费方忽略未知字段）」。与事实不符：`/evaluate-json` 不忽略未知字段。改为：

> 新增可选字段对**已识别该字段**的读方不算 breaking；严格读方（如 `/evaluate-json`）会拒收含未知字段的 IR，生产者应先于读方升级 core。

只改注释与本 ADR，不 bump major。

### 5.5 D5：aster-lang-test 新增 `--mode=ir-interop`

见 §8。

### 5.6 D6：TS 本期不改形状

TS 的内存 Core 模型被类型检查、治理检查、效应推断、解释器直接使用（§3），重命名的波及远大于 core 读方的几十行注解，且违反 V1 的「不改名」。两种方言的真正统一（单一命名、去掉冗余的 `declaredEffects`、TS 空数组省略规则）放进 schema v2，与 ADR 0037 节点身份工作合并，届时另立 ADR。

---

## 6. 备选方案

| 方案 | 做法 | 否决理由 |
|---|---|---|
| 甲：TS 输出改成 Java 名 | 改 `lower_to_core` / `core_ir.ts` | 内存模型的读方遍布 ts（§3）；违反 V1「不改名」；ts golden 全量更新；仍需 Java 侧兼容旧 ts 版本产物 |
| 乙：TS 只在序列化边界投影 | 新增 `toCanonicalCoreJson(core)`，`compile()` 可选输出 Java 形状 | 多一层投影、多一个「哪个出口是规范的」问题；调用方必须记得用它；已发布的 ts 版本照样不能用。可作为 v2 的一部分重新评估 |
| 丙：Java 全局宽松 | `/evaluate-json` 改用宽松 mapper 或类级 `ignoreUnknown` | §2.3：65/425 份静默丢 `@id` / `@control` / 导入路径 |
| 丁：IR 带版本 / 方言标记 | 模块加 `schemaVersion` / `dialect`，读方分支处理 | 现有 IR（含 `core_json`）都没有标记，仍需缺省分支；两种方言的差异只有 5 处，用别名表表达足够 |

---

## 7. 兼容与迁移

- **持久化 IR**：aster-api `policy_versions.core_json` 全部由 Java 产出（写入点 `PolicyCompiler`，列由 `V6.1.0` / `V6.4.0` / `V6.25.0` 迁移建立）；D2 只改读方、输出逐字节不变（§2.4 已验证 478 个文件），**无需数据迁移**。aster-cloud 不存 IR。在已检查范围内未发现持久化 ts IR：aster-cloud `src/db/schema.ts` 全部 `jsonb` 列、aster-api `db/migration` 中所有 IR 相关列、ts / truffle / runtime 的写盘点（ts CLI 只写临时文件 `build/last-core.json` 等），均为各仓 2026-11-14 的 `origin/main`。
- **旧版 ts 产物**：D2 让 core 读方接受所有现存 ts 版本的 IR，不依赖 ts 升级。
- **新 core 读到含 `constraints` 的 IR 再写回**：只发生在 `executeCoreIrJson` 的内存路径（回写给 Truffle），不落库；Truffle 的 DTO 是宽松读方，忽略该字段。
- **回滚**：回退 core 的注解即可；没有以新形状写下的数据。
- **发布顺序**：core（D2 + 单测）→ 平台 pin bump → aster-api 升级 core（+ 回归用例）→ aster-lang-test 开 interop 门（先 report-only，全绿后 PR-blocking）。

---

## 8. parity 门设计：`--mode=ir-interop`

**Java 侧**：在 aster-lang-core 测试源增加 `CoreIrInteropCli`（与 `CoreIrFingerprintCli` 同模式：JUnit 测试体，系统属性未设时空操作；gradle 的 `parity.ir.*` 精确键转发里加一个 `parity.ir.tsIr`）。输入为 JSONL（`{path, ir}`），对每条：

1. `new ObjectMapper().readValue(ir, CoreModel.Module.class)`——必须与 `ReplayMappers.DEFAULT` 同配置；
2. 成功则 `valueToTree` 回写，输出 `{path, ok:true, roundTrip}`；失败输出 `{path, ok:false, error}`。

**脚本侧**（`scripts/parity-tier1.mjs`）：

1. 复用 `runTsIr` 得到 ts IR、`runJavaIr({full:true})` 得到 Java IR；
2. 把 ts IR 交给 `CoreIrInteropCli`；
3. 判定：读入失败 = `interop-reject`（失败）；`normalizeForParity(roundTrip)` 与 `normalizeForParity(javaIr)` 用既有 `walkDiff`（键序无关）比较，不等 = `interop-lossy`（失败，列出路径）；
4. 额外断言 `normalizeForParity(roundTrip)` 等于 `normalizeForParity(tsIr)`——这条把「读方别名表」与「归一器别名表」钉在一起（D2 末段），任一侧单独改动都会变红；
5. **不经归一器的保留断言**（`interop-dropped`，单独计数）：归一器把 `constraints` 当推断层剥离，第 3、4 步看不到它，所以对 roundTrip 与原始 ts IR 直接逐项核对——每个 `Field` / `Param` 的 `constraints` 深度相等（缺省视同 `[]`，覆盖 `Range` 与 `Required`）；每个注解的 `params` 等于其 `args` 的有序折叠；每个 `Import` 的 `path` / `alias` 等于 `name` / `asName`；`effects` 小写化后相等。
6. 负例夹具（随 core 单测，不进语料）：带 `effectParams` / `EffectVar`、`args` 项缺 `name`、`args` 重名、同时含 `args` 与 `params`、任意未知字段——都必须被严格读方拒收。另向语料补一个带 `Range` / `Required` 形参约束的样本（现有 `loan.aster` 只有 1 处形参约束）。

**交叉执行**（第二阶段）：对带 eval 用例的样本，把 ts IR 走 aster-api 同款路径（`CoreModel` 严格读入 → 回写 → Truffle）执行，结果与 golden 比较。覆盖 C2/C3/C5：形状能读入不等于语义等价，`NamedContextMapper` 依赖形参 `TypeName` 映射上下文，而两侧对未标注形参的推断不同。

样本集与现有 `--mode=ir --full` 相同（manifest），先 report-only，全绿后与 parse / ir 并列 PR-blocking。

---

## 9. 工作量

| 仓 | 内容 | 估算 |
|---|---|---|
| aster-lang-core | `CoreModel` 读方改动（§5.2）；单测：每类 TS 方言一条（含 `args` 顺序、非字符串值、`constraints` 回写），以及 §8 第 6 步的全部负例；`CoreIrSchemaVersion` 注释（D4）；`CoreIrInteropCli` | 2 人日 + 一次发布列车 |
| aster-lang-test | `--mode=ir-interop` 判定、报告、CI job；第二阶段交叉执行 | 1.5 人日（交叉执行另 1–2 人日） |
| aster-api | 升级 core；`DynamicCnlExecutorCoreIrJsonTest` 增「ts 产出的 IR 带 `@id`/`@control` → 响应与证据含 ruleId / controls」与「未知字段仍 400」 | 0.5 人日 |
| aster-cloud | 修 `evaluate-json` 四种语言文档的示例 payload（换成真实 Core IR，说明以 Java 形状为准、ts `compile().core` 可直接提交） | 0.5 人日 |
| aster-lang-ts / truffle / runtime | 无 | 0 |

合计约 4.5 人日（不含交叉执行）。

---

## 10. 风险与未决问题

- **Java 丢约束**：Java 解析器接受 `between … and …` / `required` 却不把约束写进 IR（A2）。两侧运行时都不执行约束，所以今天不影响结果；但 Java 产出的 `core_json` 没有这份信息。是否让 Java lowering 也产出 `constraints` 属于 parity 工作，另开 issue。
- **推断层不同导致交叉执行差异**：C2/C3 在「ts IR 由 Java 执行」时可能改变上下文映射或类型强转。§8 交叉执行之前，不应对外承诺「ts IR 与源码在 `/evaluate-json` 上结果相同」。
- **Truffle 效应名大小写敏感**：`AsterContext.isEffectAllowed` 按原串比较，内建要求 `"IO"`，Java 自产 IR 为 `"io"`。IO 内建在 Truffle 本就不支持，暂无可观测后果；若将来有大小写相关的效应门控，需统一。D2 的小写化 setter 让 core 侧口径一致，Truffle 侧另行核实。
- **Java IR origin 列号不确定**：本次三轮 `CoreIrFingerprintCli`，4 个 vocab-zh 样本（`governance-input-refs`、`same-field-two-structs`、`synonyms`、`type-equals-field`）的 `origin.*.col` 在第二轮偏移 1 列，第一、三轮一致，与 D2 改动无关。parity 只比 `line`，故未被门禁发现。另开 issue 调查（疑似词汇规范化的跨样本状态）。
- **是否需要 JSON Schema**：规范形状目前由 Java 类隐式定义。若外部集成方增多（`/evaluate-json` 面向自带编译链的客户），应从 `CoreModel` 生成并发布 JSON Schema，列为 v2 议题。
- **v2 统一的时间点**：V1 承诺至 2027-12-01；v2 的命名统一需提前 6 个月通告（契约原文），即最晚 2027-06 前立项。

---

## 11. 附录：复现步骤（一次性实验）

§2 的数字来自一次性脚本与一个未提交的 JUnit 草稿，证据等级是「可复现的单次实验」，不是持续门禁；D5 落地后由门禁取代。复现：

1. 各仓取 2026-11-14 的 `origin/main`（ts eb6e89c、core a35953f、test b595d6a），ts `pnpm install && pnpm build`。
2. TS 侧：遍历 `corpus/**/*.aster`（排除 `tier1-reject/`），按 `.meta.json` 的 `lexicon` 与同名 `.vocab.json` 调 `dist/src/browser.js` 的 `compile()`，成功的 `core` 各写一个 JSON；同时写出 `CoreIrFingerprintCli` 的输入清单（每行 `{path, lexicon?, vocabulary?}`）。
3. Java 侧：core 目录下 `./gradlew test --tests CoreIrFingerprintCli --rerun-tasks -Dparity.ir.input=<清单> -Dparity.ir.output=<out.jsonl> -Dparity.ir.full=true`（本地需 `ASTER_LOCALES_DIR` 指向 aster-lang-locales，且 mavenLocal 有平台 1.0.45 与 aster-lang-test 1.0.45）。
4. 结构比较：对两侧都成功的样本，按节点类别统计字段出现次数与值类型，并做位置对齐的取值比较。
5. 反序列化：在 core 测试源临时放一个 JUnit 测试，对每份 ts IR 先用 `new ObjectMapper()` 严格读入记首个错误，再用带 `DeserializationProblemHandler` 的 mapper 宽松读入、登记全部未知字段、`valueToTree` 回写；回写结果与 Java IR 经 `ir-normalize.ts`（`originMode:'file+line'`、`includeResolution:true`，键序无关）比较。
6. 原型：在 core 的 `CoreModel` 加 §5.2 注解后重跑第 3、5 步。

## 12. 关联

- aster-cloud/aster-lang-ts#314
- ADR 0016 §D1（归一比较）、ADR 0039 §3 / §10（注解容器形状）、ADR 0037 §2.2.1（origin 归一）、ADR 0051（`written/role`）
- aster-lang-core `src/main/java/aster/core/ir/CoreModel.java`、`src/main/java/aster/core/ast/CoreIrSchemaVersion.java`、`src/test/java/aster/core/dualengine/CoreIrFingerprintCli.java`
- aster-lang-test `packages/js/src/ir-normalize.ts`、`scripts/parity-tier1.mjs`、`IR-DIVERGENCE-LEDGER.md`
- aster-api `replay/src/main/java/io/aster/policy/parser/DynamicCnlExecutor.java`、`replay/src/main/java/io/aster/replay/core/parser/ReplayMappers.java`
