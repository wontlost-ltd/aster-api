# ADR 0055：Core IR v2 —— 单一 JSON Schema、两引擎同形、严格读方、逐字节 parity

- 状态：**Proposed**（2026-11-14 草案；审查后由用户确认）
- 提出：2026-11-14；来源 aster-cloud/aster-lang-ts#314（ts `compile()` 产出的 Core IR 不能被 Java `aster.core.ir.CoreModel` 反序列化）
- 前提（用户 2026-11-14 确认）：项目尚未上线，没有真实用户；prod aster-api `policy_versions` 为 0 行，aster-cloud 不存 IR；上线前所有数据可清空。因此**不做 V1 兼容、不做迁移、不保留方言别名**
- 取代：ADR 0016 的 D1（「归一比较，而非强行对齐字段名」）与 D2（规范字段集 / 忽略字段集）；ADR 0039 §10「注解 IR 容器形状分歧」一条随本 ADR 关闭；`CoreIrSchemaVersion.V1` 的「保证到 2027-12-01」承诺作废
- 相关：ADR 0037（contentHash）、ADR 0045（控制注册表：共享数据放在 aster-lang-locales 的先例）、ADR 0046 §11（`inputRefs`）、ADR 0051（`origin.written/role`）、ADR 0030（`CanonicalJson`）
- 实施计划：`docs/superpowers/plans/2026-11-14-core-ir-v2.md`

---

## 0. TL;DR

今天两个引擎各产一种 Core IR 方言，再由 aster-lang-test 的 `ir-normalize.ts` 抹平差异后比较。parity 门禁报告 425/425 等价，但 ts IR 交给 Java 时，严格读入只成功 56/459；宽松读入则有 65/425 份静默丢掉 `@id` / `@control`（§2）。归一器掩盖了差异，并没有让两边真正一致。

v2 的做法是**只保留一种形状**：

| 决定 | 内容 |
|---|---|
| D1 唯一真相源 | `aster-lang-locales/core-ir/core-ir.v2.schema.json`（JSON Schema 2020-12），所有字段必填、`additionalProperties:false`；core 与 ts 各持一份副本，由 sibling parity 测试守护（ADR 0045 模式） |
| D2 两引擎同形 | 每一类分岔都按其本身取舍定下一种写法（§4 表），由落后的一侧改；两引擎都**恒输出所有字段**（无值用 `null` / `[]` / `false`） |
| D3 版本 | `Module.schemaVersion: 2`；任何读方遇到缺失或其它值即拒收 |
| D4 严格读方 | 所有 Jackson 读方 `FAIL_ON_UNKNOWN_PROPERTIES=true`（含 Truffle `Loader`、aster-api 全部 `core_json` 读方、core `TypeCheckCli`）；删除全部别名与宽松 mapper |
| D5 规范序列化 | 两引擎都输出 core `CanonicalJson` 规则（ADR 0030）下的字节；非整数数值一律用**规范十进制串**（§5 定义语法，lowering 时从源码字面量精确得出，不经二进制浮点） |
| D6 parity | 每个语料文件的两引擎规范 IR **逐字节相同**，只排除 `origin.*.col`；归一器退役。名称 / 形状一致从第一天起是硬门禁，值层差异按类别分期收紧（§6） |
| D7 发布 | 引擎列车 1.0.46 + ts 2.0.0（major），aster-cloud 升级并重建数据；aster-api#501 搭同一班车 |

---

## 1. 背景

### 1.1 现象（ts#314）

`POST /api/v1/policies/evaluate-json` 用 vanilla `ObjectMapper` 读 ts IR，读到 `Field.constraints` 时报 `Unrecognized field "constraints"`，删掉它再试又在 `Annotation.args` 上报错。

### 1.2 为什么归一化不够

ADR 0016 D1 选择「归一比较」，是为了不改任一引擎的 IR 契约。代价是：parity 门禁只证明「抹平之后一样」，而真正的消费方（`/evaluate-json`、Truffle、Cedar 导出、规则元数据）读的是**没有抹平**的 IR。V1 兼容承诺本是保留方言的唯一理由，在「无用户、可清库」的前提下它不再成立。

---

## 2. 实证（2026-11-14，各仓 `origin/main`）

方法：取 aster-lang-test `corpus/` 下除 `tier1-reject/` 外的全部 478 个 `.aster`（test b595d6a）。ts 侧（eb6e89c，1.6.0）调用 `browser.compile()`，459 份成功；Java 侧（core a35953f，1.0.45）调用 `CoreIrFingerprintCli --full`，425 份成功。Java 失败的 34 份是 31 个 tier3 旧语法夹具加 3 个 hi-IN 样本（本地环境缺语言包）。之后对两侧都成功的 425 份做逐节点字段统计和位置对齐比较，再用一次性 JUnit 把 459 份 ts IR 交给 `CoreModel` 严格读入和宽松读入（脚本未入库，属单次实验）。

| 结果 | 数值 |
|---|---|
| ts IR 被 vanilla mapper 读入 | 56/459 |
| 严格读方的未知字段类别 | 5 类：`Param.constraints`、`Field.constraints`、`Func.declaredEffects`、`Annotation.args`、`Import.name/asName` |
| 宽松读入后与 Java IR 归一等价 | 360/425；其余 65 份丢 `@id` / `@control` 或 Import 路径 |
| 归一器判两侧等价 | 425/425 |
| 节点 `kind` 集合 | 两侧一致；分岔全部在字段层与取值层 |

另外，两处语料没有覆盖的分岔是读源码发现的：ts 的效应多态（`effectParams`，以及 `declaredEffects` 中的 `EffectVar`），Java 语法不支持；ts 的 `matching "…"` 约束，Java 语法没有。

取值层（能读入，但值不同）的计数：`Func.ret` 推断出 `TypeVar` 还是 `TypeName` 686 处；`typeParams` 被 ts 补了一个幻影 `"Unknown"` 678 处；未标注形参 / 字段的推断类型名 118 处；`effects` 大小写 116 处；推断出的 `effectCaps` 56 处；0 参枚举变体模式 12 处；`TypeName.origin.end.line` 17 处；`Lambda.captures` 1 处；`origin` 在 ts 的 `TypeName` / `FuncType` 上缺失 1129 / 7 处。

---

## 3. D1：唯一真相源 —— JSON Schema 放在 aster-lang-locales

**位置**：源文件为 `aster-lang-locales/core-ir/core-ir.v2.schema.json`，与 `controls/registry.json` 同级，并按 ADR 0045 §2.2 的副本模式分发：
- core 在 `src/main/resources/core-ir/core-ir.v2.schema.json` 放一份副本，由 `CoreIrSchemaSiblingParityTest` 与同级检出的 locales 做深度相等比较，CI 缺少 locales 即失败；这是 `ControlRegistrySiblingParityTest` 的同一做法；
- ts 在 `shared/core-ir.v2.schema.json` 放一份副本，由 `test/unit/core/core-ir-schema-parity.test.ts` 同法守护。

不新增 Maven / npm 制品。

**为什么不放 core**：schema 是两个引擎之间的契约。放进任何一个引擎仓，那个引擎在评审上就天然占上风，v1 就是这样变成「Java 说了算」的。aster-lang-locales 已经是两引擎共享数据的中立仓：控制注册表（ADR 0045）、casefold 表、诊断目录都在这里，副本加 sibling parity 测试的模式已经跑通。代价是改 IR 须先改 locales；IR 的任何改动本来就要走列车，可以接受。

**字段全集**：§4 只列有分岔的字段。没有分岔的节点和字段沿用 Java `CoreModel` 现有的名称与 JSON 类型，并套用 §4 #19 的恒输出规则。schema 草案连同由它生成的字段清单 `core-ir/FIELDS.md`（每个 `$defs` 的属性、类型、可空性、缺省值）作为 locales PR 的评审对象，这份清单就是完整的字段契约。

**schema 规则**：
- 每个节点类型一个 `$defs`，用 `kind` 的 `const` 区分；`properties` 全部列入 `required`，`additionalProperties: false`。
- 可空字段写成 `"type": ["string","null"]`。列表字段不允许 `null`，唯一例外是 `Func.inputRefs`（#12）。
- 所有 JSON 整数（`schemaVersion`、`origin` 行列号、`Int` 字面量、`PatInt.value`、`Import.version`、重试与超时参数、注解整数参数）引用同一个 `$defs/SafeInt`（`integer`，范围 ±9007199254740991），与 `CanonicalJson` 只接受 safe integer 的规则一致。`Long` 已经用字符串承载。
- schema 表达不了的约束（注解参数名唯一、`effects` 里的变量必须已在 `effectParams` 中声明）由两引擎各自实现的 `validateCoreIrInvariants` 补齐，两种实现共用 locales `core-ir/test-fixtures/` 的 `bad-*.json` 负例与 `expected-problems.json`（同 controls 做法）。

**合规检查**：
- **core（Java）**：`CoreIrSchemaConformanceTest` 用 networknt `json-schema-validator` 校验语料全部 Java 输出；`CoreModelSchemaShapeTest` 用反射核对每个 `CoreModel` 类的 Jackson 属性集与 schema 中对应 `$defs` 的 `properties` 完全相等，多一个、少一个都失败。
- **ts**：由 `json-schema-to-typescript` 生成 `src/core/core-ir-v2.generated.ts`，再做类型级测试（`expectTypeOf`），断言 `Core.Module` 与生成类型双向可赋值；`ajv` 校验语料全部 ts 输出。生成文件入库，CI 重新生成并比对 diff。
- **aster-lang-test**：parity 门禁对两侧输出各跑一次 schema 校验（§6）。

---

## 4. D2：逐类分岔的取舍

列「改哪侧」的意思是哪个引擎需要改代码。凡是标「两侧」的，两侧都要改。

| # | 字段 / 节点 | v2 形状 | 理由 | 改哪侧 |
|---|---|---|---|---|
| 1 | `Module` | 新增 `schemaVersion: 2`（整数 const） | D3 | 两侧 |
| 2 | `Import` | `path`（点分模块路径）、`alias: string\|null`、`version: integer\|null` | `name` 与其它 decl 的 `name`（声明出的名字）含义冲突，Import 并不声明 `Http` 这个名字；`alias` 才是绑定名 | ts |
| 3 | `Annotation` 参数 | `args: [{name, value}]`，**有序数组**，恒输出（无参为 `[]`）。位置参数按出现顺序命名为 `$0`、`$1`…（序号只在位置参数之间计数），具名参数用其名字，两引擎相同。`value` 只能是 string / safe integer / boolean | 规范序列化会对对象键排序，映射一旦排序就丢了源码顺序，而位置参数 `$0`、`$1` 的顺序有意义。同名参数在**解析阶段**报错（两引擎同一新诊断码，码号在 locales 分配），schema 不变式再兜底 | ts（`AnnotationValue` 去掉普通 number 与 null，对这两种写法给出解析期诊断）；Java（`CoreModel`、`CoreLowering.annotationValue`、`GovernanceChecker`、`BaseTypeChecker`、`PiiTypeChecker`、`TypeSystem`、`ModuleGraphLinker`、`DefaultCoreVisitor`） |
| 4 | `Field.constraints` / `Param.constraints` | 恒输出数组，元素为 `{kind:"Required"}`、`{kind:"Range", min:string\|null, max:string\|null}`（至少一个非 null，规范十进制串，见 §5）、`{kind:"Pattern", regexp:string}` | 约束是源码语义。Java 现在接受 `required` / `between` 语法却把它丢掉（`AsterParser.g4` 注释写着「当前仅 parse 接受」），这是缺陷。`min` / `max` 用字符串，以满足 D5 不传浮点数的规则 | Java（语法补上 `matching`，`AstBuilder` 与 `CoreLowering` 带上约束）；ts（`Range` 改为字符串） |
| 5 | `Func.effects` | **只有这一个字段**：`effects: string[]`，保持源码顺序；具体效应名一律小写；与 `effectParams` 中某个名字相等的条目就是效应变量，原样保留 | 消除 `effects` 与 `declaredEffects` 的冗余，同时保住效应变量。`effects` 在 Java 里仍是 `List<String>`，消费方类型不变。规则：效应参数名不得与内建效应名（大小写不敏感）相同，否则报诊断 | ts（删除 `declaredEffects`，具体效应名小写）；Java（语法、AST、lowering 支持 `<E>` 效应参数，并接入现有 E210 / E211）——**见 §8 待确认 1** |
| 6 | `Func.effectParams` | 恒输出 `string[]` | 随 #5 | 两侧 |
| 7 | `Func.effectCaps` / `effectCapsExplicit` | 保留，恒输出 | 显式声明的能力是源码语义；推断出来的能力值属于值层，分期收紧（§6 V2） | 无形状变化 |
| 8 | `Func.piiLevel` / `piiCategories` | **删除** | 这是派生聚合值：core 内部只有 `CoreLowering` / `CoreCopies` 写它，各仓 `origin/main` 都没有读方，ts 也不产出 | Java |
| 9 | `Param.typeInferred` / `Field.typeInferred` / `Func.retTypeInferred` | 恒输出布尔 | 读方需要知道类型只是占位（ADR 0051 R2），值层分期也以这三个标记划定豁免子树（§6 V1）。Java 去掉 `@JsonIgnore` 和 `NON_DEFAULT` | 两侧 |
| 10 | `Func.typeParams` | 只列源码声明的类型参数 | ts 在推断返回类型时补的 `"Unknown"` 是 lowering 产物，不是源码内容 | ts |
| 11 | `annotations`（Func / Field / Param）、`Func.retAnnotations` | 恒输出数组 | 统一按「恒输出」处理 | ts（ts 目前根本没有 `retAnnotations`：AST 若支持返回类型注解就降级它，否则输出 `[]`，并补语料样本） |
| 12 | `Module.profile`、`Func.inputRefs` | 恒输出。`profile` 未声明时为 `null`。`inputRefs` 是 v2 **唯一的可空列表**：`null` 表示未计算（非档案模块，或不返回 Verdict 的规则），`[]` 表示已计算且不读任何输入（ADR 0046 §11 D2） | 「未计算」与「什么都不读」语义不同，`[]` 无法同时表达两者 | 两侧 |
| 13 | `Lambda` 返回类型 | `ret` | 与 `Func.ret` 一致 | ts；Java 删除 `@JsonAlias("retType")` |
| 14 | `Lambda.captures` | 恒输出 | 值层分期（§6 V3） | 无形状变化 |
| 15 | `PatCtor` | `typeName` + `args: Pattern[]`（嵌套模式）；**删除 `names`** | `args` 的表达力严格大于 `names` | ts（lowering 改为输出 `args`）；Java 与 Truffle 删除 `names` |
| 16 | 0 参枚举变体模式 | `PatCtor{typeName, args:[]}`；`PatName` 只用于绑定 | ts 靠「首字母大写」判断变体，对中文、印地文标识符失效，因为它们没有大小写；名称解析已经知道它是变体，应当显式表达。Truffle 的大写启发式随之删除 | ts、Truffle |
| 17 | `origin` | **每个节点都必带**（含 `Module`、所有类型节点、`FuncType`）：`{file: string\|null, start:{line,col}, end:{line,col}, written: string\|null, role: string\|null}` | 恒输出；ts 现在在 `TypeName` / `FuncType` 上缺 origin | ts（补 origin）；两侧（`written` / `role` 改为恒输出） |
| 18 | `Double.value` | 规范十进制串（§5），从源码字面量精确得出 | core `CanonicalJson` 只允许 safe integer（跨引擎浮点格式不一致）；`Long` 已经是字符串 | 两侧（Java `DoubleE.value` 改为 `String`，Truffle 读方跟着改） |
| 19 | 空值 / 缺省 | **全部恒输出**：标量与对象用 `null`，列表用 `[]`，布尔用 `false` | 只有一条规则，没有例外，schema 才能「全部必填」，逐字节比较才简单。代价是 IR 体积变大（每个 origin 多两个键）。唯一例外是 `inputRefs` 可为 `null`（#12），见 §8 待确认 3 | 两侧，主要是 ts |

---

## 5. D3–D5：版本、严格读方、规范序列化

**版本**：`schemaVersion` 是 JSON **整数** `2`（schema 中写 `{"const": 2}`），字符串 `"2"` 属于类型错误，同样拒收。删除 `CoreIrSchemaVersion.V1`，只保留 `V2(2)`，`isCompatible(int)` 只接受 `2`。

**读方流程（两引擎相同，顺序固定）**：① 解析为 JSON 树，并先检查 `/schemaVersion`：缺失、类型不对或值不为整数 2，都直接以「版本错误」拒收，不依赖校验器报出的错误路径；② 对**输入**跑 JSON Schema 2020-12 校验，缺字段、类型错误、可空性错误、未知字段都在这一步拒收；③ 严格绑定到模型（`FAIL_ON_UNKNOWN_PROPERTIES=true`，作为第二道防线）；④ 执行 `validateCoreIrInvariants`。Java 侧入口为 `CoreIrJson.read`，ts 侧为 `readCoreIr`。光靠严格反序列化不等于符合 schema，所以第 ② 步不能省。拒收的表现：
- aster-api `/evaluate-json`：`schemaVersion` 错误返回 400 `code=unsupported_schema_version`，其余 schema / 不变式错误返回 400 `code=invalid_request`；
- Truffle `Loader`：先调用 core 的 `CoreIrJson.validate(JsonNode)`（即第 ② 步和第 ④ 步，Truffle 依赖 core），再严格绑定到自己的 DTO；失败抛 `IllegalArgumentException`；
- ts 侧读 IR 的入口（CLI 读取 `.json`）报错。

**严格读方清单**（全部改为 `FAIL_ON_UNKNOWN_PROPERTIES=true`，并删除对应的宽松 mapper）：

| 位置 | 现状 |
|---|---|
| aster-api `JacksonMappers.LENIENT`（`PolicyEvaluationService.parseCoreModule`、`CedarExportService.readModule` 读 `core_json`） | 宽松 |
| aster-api `StabilityEnforcement.LENIENT_MAPPER` | 宽松 |
| aster-lang-truffle `Loader.mapper` | 宽松 |
| aster-lang-core `TypeCheckCli.MAPPER` | 宽松 |

另有两处以 `readTree` 读 `core_json` 的路径，同样属于 IR 读方：`PolicyEvaluationResource.parseCoreJson`（编译响应里抽取模块信息、档案与 `inputRefs`）和 `PolicyEvaluationService` 中的 `mapper.readTree(coreJson)`。`JacksonMappers.LENIENT` 若还有非 IR 用途，只把 IR 读方迁走。所有 IR 读方，不论是读成模型还是读成树，统一走 core 新增的 `CoreIrJson.read` / `CoreIrJson.readTree`（版本检查 + schema 校验 + 不变式检查）。

**规范十进制串**（`Double.value`、`Range.min`、`Range.max`）：语法为 `-?(0|[1-9][0-9]*)(\.[0-9]*[1-9])?`，即没有指数、没有前导零、没有尾随零，小数部分全零时省略小数点，`-0` 写成 `0`；schema 用 `pattern` 强制。值取自**源码字面量的原文**，这需要在词法 / AST 层保留字面量：Java 的 `Expr.Double` 新增 `String lexeme`，`AstBuilder` 从 token 文本填入；ts lexer 的数值 token 新增 `raw`（`lexer.ts` 目前在 `parseFloat(num)` 处就丢掉了原文），AST 数值节点同样带上 `raw`；`between` 的两端同理。随后lowering 时按字面量构造任意精度十进制数（Java `BigDecimal`，ts `decimal.js`），去掉尾随零后输出无指数形式，不经二进制浮点。因此 `1e-7` 得到 `0.0000001`，`0.30000000000000004` 原样保留，`1.50` 得到 `1.5`，`1E3` 得到 `1000`，`123456789012345678901.5` 原样保留。如果中途经过 double，最后一例会变成 `123456789012345680000`，回归测试靠它来区分。运行时需要浮点数时，再由读方把字符串解析为 double。`CanonicalJson` 只负责转义这些字符串，规范化由 lowering 负责，校验由 schema 负责。

**规范序列化**：两引擎各提供一个序列化出口——Java 为 `CoreIrJson.serialize(Module)`，ts 为 `serializeCoreIr(module)`，两者是同一契约的两种语言实现，输出 core `aster.core.canonical.CanonicalJson` 规则下的字节：键按码点升序，数组保序，`null` 显式输出，不允许非 safe integer 的数值。Java 直接复用该类；ts 新增 `src/core/canonical-json.ts`，从 aster-cloud `src/lib/canonical-json.ts` 移植，并共用 `CanonicalJsonParityTest` 的 golden 夹具。凡是给持久化、哈希或 parity 用的 IR，一律经这个出口产出。

---

## 6. D6：parity —— 逐字节门禁与值层分期

门禁 `parity-tier1.mjs --mode=ir --full` 改为：

1. 两引擎对同一语料文件各自经 `serializeCoreIr` 输出；
2. 两侧输出各自通过 schema 校验与 `validateCoreIrInvariants`，任一侧不通过即失败（`schema-invalid`）；
3. 两侧做同一个**比较投影**：删除全部 `origin.start.col` / `origin.end.col`（全局投影，不属于豁免类别，不参与 stale 统计）；再把**当前仍在豁免期**的值层位置替换为 `{"$exempt":"<类别>"}`；
4. 逐字节比较，不等即失败。失败分两类：键集合、`kind` 或数组长度不同的报 `shape-diff`，其余报 `value-diff`；两类都输出第一个不同的 JSON 路径及两侧的值。

`ir-normalize.ts` 及其 `scripts/lib/ir-normalize-parity.mjs`、`IR-DIVERGENCE-LEDGER.md` 退役。packages/js 删除 `normalizeIr` 导出（该包 major bump）。ADR 0037 的 contentHash 改为对「删除 origin 之后的规范 IR 字节」取 sha256。

**排除 `origin.*.col` 的理由**：ts 的 `end.col` 多为占位值，ts 尚未计算真实的结束位置（ADR 0037 §2.2.1 记录在案）。另外，本次调研中三轮 `CoreIrFingerprintCli` 里有一轮，Java 在 4 个 vocab-zh 样本上的 col 偏移了 1 列，说明 Java 的列号也不确定。把 col 纳入 v2 首发门禁，会让门禁卡在与形状无关的问题上。col 收紧另立工作（见 §7）：等 ts 能算出真实的结束位置、Java 列号也确定之后，门禁加 `--origin-col=strict` 并删掉这一投影。

**第一天起就是硬门禁的部分**：豁免子树之外的每一个字节，包括所有字段名、`kind`、字段是否出现、数组长度，以及表 §4 的全部形状决定。效应名小写（#5）和枚举变体模式（#16）虽然出现在取值层计数里，但它们改变的是 `kind` 或字段内容，修起来是一行级别，所以**不进分期**，第一天起必须一致（§8 待确认 2）。

**值层分期类别**（显式清单；每修完一类就从豁免中删除，此后必须一致）：

| 类别 | 豁免子树 | 当前计数 | 收紧条件 | owner 角色 | 截止 |
|---|---|---|---|---|---|
| V1 类型推断 | **仅类型节点**：`retTypeInferred:true` 的 `Func.ret` 整棵类型子树；`typeInferred:true` 的 `Param.type` / `Field.type` 整棵类型子树。类型子树里全部是推断输出，没有需要硬比较的字段；匹配器若命中的不是类型节点，直接报错 | 686 + 118 | 两引擎推断规则统一（另立 ADR） | 两引擎类型检查维护者 | 2027-01-31 |
| V2 能力推断 | `effectCapsExplicit:false` 的 `Func.effectCaps` 这一个数组值（`effectCapsExplicit` 本身仍硬比较） | 56 | 统一 stdlib 能力预置规则 | 效应 / 能力检查维护者 | 2027-01-31 |
| V3 闭包捕获 | `Lambda.captures` 这一个数组值 | 1 | 统一为「只列引用到的外层名」 | ts lowering 维护者 | 2027-01-31 |
| V4 origin 行号 | **逐项登记**的 `(语料文件, JSON 路径)` 列表，只豁免该位置的 `origin.end.line` 标量：声明尾部（Java 错）、多行续行（未定）、`TypeName` 结束行。不在列表里的行号差异一律失败 | 15 + 17 | 修 Java `AstBuilder.spanFrom` 并定续行规则 | Java parser 维护者 | 2027-01-31 |

豁免清单作为代码常量放在 `parity-tier1.mjs`，每一项都附 issue 链接。豁免只能覆盖上表列出的值位置，不能覆盖字段名、`kind` 或字段是否出现。`origin` 在 v2 中对每个节点必填且非 null，所以第 3 步删除 col 不依赖节点形状。**stale 判定**：V1 / V2 / V3 按类别判定，一轮全语料运行中该类别一处差异都没有被豁免掉，即报 `stale-exemption`；V4 按登记项判定，某个 `(文件, 路径)` 不再有差异即报。两种情况都使门禁失败，要求删掉该项，以防豁免永久残留。**到期**：每个类别都有 tracking issue、owner 角色和截止日（上线之前，最晚 2027-01-31），截止日以 `expires` 写在豁免表里。运行日期晚于 `expires` 时，门禁报 `expired-exemption` 并失败，即使差异仍在。go-live 要求豁免表为空。

---

## 7. D7：发布与范围

- **列车**：引擎仓与 aster-lang-test 使用同名分支组 `feat/core-ir-v2`，CI 同名分支优先。aster-api 不用同名分支：它的 CI 只认与 catalog pin 一致的单一 `sibling-ref` tag（`scripts/ci/verify-sibling-ref-pin.sh`），所以沿用列车先例（#476 / #491 / #499，#501 同样处理），在本地组合构建下验证，PR 的 CI 在 `v1.0.46` 发布前为红，等引擎制品发布后与 pin bump 一起合入。合并顺序、制品时序与组合构建配方见实施计划。
- **数据**：上线前清空。prod `policy_versions` 为 0 行（用户确认），本地栈与测试库按既有流程重建（reseed），不写迁移脚本。
- **aster-api#501**（E054 时间预算文案）：独立小改动，排进同一列车的 aster-api 发布，与 v2 互不依赖。
- **范围内**：Truffle 效应名改成小写比较（`Builtins.requireEffect("IO.print","IO")` 等改为 `"io"`），否则 v2 的小写 `effects` 会让内建效应检查失效。
- **范围外，另立 issue**：Java origin 列号不确定（col 投影删除的前提）、两引擎推断规则统一（V1 / V2 / V3）、`workflow` / `step` 的小写 kind 命名（两侧一致，无分岔）。

---

## 8. 已定与待确认

**已定（用户指示）**：两引擎都支持效应变量（#5，Java 补语法、AST、lowering 和类型检查接线）；schema 放在 aster-lang-locales，理由见 §3；不做兼容、不做迁移。

**待用户确认**（截至 2026-11-14 用户**尚未答复**以下三项；在 core PR 开工前由用户决定，未答复时实施计划按默认推进，不阻塞）：

1. **效应名大小写与枚举变体模式提前到第一天**：指示把它们列为分期类别，本 ADR 认为它们改的是 `kind` 或字段内容，而且一行就能修，所以第一天强制一致。默认：第一天。
2. **恒输出的体积代价**：每个 origin 多 `written:null, role:null`，每个函数多若干空数组；粗估规范 IR 体积增加 15–25%（未实测，core PR 合并前在语料上实测）。默认：接受。备选是让 origin 里这两个键成为唯一的「缺省即省略」例外。
3. **注解参数值类型**限定为 string / safe integer / boolean（#3）；现有语料只用到 string。默认：如此。

## 9. 关联

- aster-cloud/aster-lang-ts#314；aster-api#501
- 取代 ADR 0016 D1 / D2；关闭 ADR 0039 §10 一条
- aster-lang-core `src/main/java/aster/core/ir/CoreModel.java`、`src/main/java/aster/core/ast/CoreIrSchemaVersion.java`、`src/main/java/aster/core/canonical/CanonicalJson.java`、`src/main/antlr/AsterParser.g4`
- aster-lang-ts `src/lower_to_core.ts`、`src/core/core_ir.ts`、`src/types.ts`
- aster-lang-test `scripts/parity-tier1.mjs`、`packages/js/src/ir-normalize.ts`
