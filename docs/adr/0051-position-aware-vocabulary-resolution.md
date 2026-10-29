# ADR 0051：领域词汇按位置解析（解析后名称解析替代解析前文本替换）

- 状态：**ACCEPTED**（2026-10-27 用户定稿；经 Codex 四轮审核，第四轮 88 分，剩余两项已在 v5 澄清）
- 日期：2026-10-27
- 决策者：用户（已明确：本项目尚无外部用户，**不考虑向后兼容**）
- 相关：ADR 0014（领域词汇融合）、ADR 0022（关键词别名，方案 D）、ADR 0016（IR 字段级 parity）、aster-lang-ts#243、aster-cloud #626 / #632 / #638

---

## 1. 背景与问题

领域词汇（ADR 0014）让用户用本地化名称编写策略：`Definiere Fahrer hat alter als Zahl.` 等价于 `Define Driver has age as Int.`。当前实现在**解析之前**把源码中所有“像标识符的词”按一张全局表替换为规范名，导致：

| 生产复现 | 结果 |
|---|---|
| struct `Stufe`↔`Level` 与 field `stufe`↔`level` 并存 | 编译失败：`无法解析 'Definelevelas' 附近的语法` |
| 规则 `hoch` 与枚举成员 `Hoch`（`Stufe.High`） | `gib zurück Hoch.` 报 E003 |
| 参数 `fahrer` 与 struct `Fahrer` | 参数被改名为 `Driver` 后解析失败 |
| 同一本地化字段名在不同 struct 中对应不同字段 | 无法表达（全局表后写覆盖先写） |

德语“名词大写 / 字段小写同词”、中文与印地语“类型名 = 字段名”都是自然写法；这是语言层面的限制。

## 2. 现状实证（已核对代码）

- **TS**：`src/frontend/canonicalizer.ts` `translateIdentifiersInSegment` 以 `identifierTokenRegex()` 切词，`index.toCanonical.get(match.toLowerCase())` 命中即替换，仅跳过字符串字面量；字面量宏（`IdentifierKind.LITERAL`）展开为带引号的字符串。发生在 `canonicalize()` 内、词法分析之前。
- **Java**：`aster.core.canonicalizer.Canonicalizer`（构造参数 `IdentifierIndex`，`identifierIndex != null` 时执行 `translateIdentifiers`），同为解析前替换。
- **索引**：两引擎 `IdentifierIndex` 均有 `byKind`、`fieldsByParent`、`literals`，翻译只用扁平 `toCanonical`。
- **类型检查作用于 Core**：TS `typecheckModule(m: Core.Module)`（`src/typecheck/module.ts`），Java `TypeChecker.typecheckModule(CoreModel.Module)`。
- **字段访问不是独立节点**：AST 与 Core 中 `x.f.g` 都是一个 `Name`，其 `name` 为点分字符串；TS `src/typecheck/expression.ts` 以 `name.split('.')` 逐段推断（头段为符号，中段遇 TypeName 走 Data 字段查找）。构造的字段为 `ConstructField{ name, expr }`。
- **Core 节点带 `Origin`**（TS `types.ts` 的 Core 命名空间，Java CoreModel 同构），目前只记录位置，不记录原写法与解析角色。
- **IR parity 对 Origin 的现状**：`aster-lang-test/packages/js/src/ir-normalize.ts` `normalizeOrigin` 默认模式 `file+line` 会比较 `file` 与 `start/end.line`（`strict` 另比 `col`，`off` 才整体丢弃）；它只拷贝 `file/start/end`，其它 Origin 字段被丢弃。归一化结果也是 `contentHash` 的输入。
- **Java AST 节点**（`aster.core.ast`）：Decl = Import、Data、Field、Enum、**TypeAlias**、Func、Parameter；Type = TypeName、TypeVar、TypeApp、Result、Maybe、Option、List、Map、**FuncType**；Expr = Name、字面量、Call、ListLiteral、Construct、ConstructField、Ok、Err、Some、None、Lambda、Await、IfExpr；Stmt = Let、Set、Return、If、Match、Case、Start、Wait、Workflow、WorkflowStep、RetryPolicy、Timeout；Pattern = PatternNull、PatternCtor、PatternName、PatternInt。TS AST 同构（另有 TypePii、EffectVar；TypeAlias/FuncType 以各自节点或 TypeApp 表示）。
- **source envelope**：cloud `computeSourceEnvelope(content, aliasSetJson, locale, toolchainId)`（`src/lib/policy-alias.ts`）与 Java `PolicyVersion.computeSourceEnvelope` 逐字节对齐，长度前缀分帧；**词汇不在其中**。`toolchainId` 由 cloud `cloudToolchainId()`（`"abi=1.0;core=ts;validator=<v>;build=<b>"`）与 Java `ToolchainIdentityProvider.currentToolchainId()` 提供。
- **parity 门禁**：`aster-lang-test/scripts/parity-tier1.mjs` + `corpus/tier1-parity/manifest.json`，当前语料不支持自带词汇（ADR 0014 当时未做）。

## 3. 目标与非目标

**目标**
1. 同一本地化词在不同**角色**（类型 / 字段 / 枚举成员 / 函数 / 源码局部名）下可指向不同规范名。
2. 字段按**所属类型**解析：同一本地化字段名在不同 struct 中可映射到不同规范字段。
3. 源码声明的局部名（参数、Let、模式绑定、lambda 参数、workflow 步骤名）永不被词汇改写。
4. 无法判定时给出确定、可读、带候选的诊断。
5. TS 与 Java 产出逐字段一致的 Core IR，由 tier1 parity 锁定。

**非目标**
- 关键词 / 关键词别名（aliasSet）/ 字面量宏的处理不变：仍在 `canonicalize()`（解析前）完成——它们决定 token 类别与语法结构。
- 不保留旧行为（见 §9）。
- 不改变词汇数据模型（UserDomainTerm / 快照）。

## 4. 决策：两阶段名称解析

```
源码 ─canonicalize（仅关键词/别名/字面量宏/空白）─▶ 词法 ─▶ 语法 ─▶ AST（标识符保持原写法）
   ─▶ 阶段 R1：声明与类型位置解析（AST 上，作用域驱动，不需类型）
   ─▶ lower ─▶ Core（未定点分段名保留原写法，带 ResolutionMark）
   ─▶ 阶段 R2：类型驱动解析（Core 上，独立 pass，自带最小类型推断）
   ─▶ typecheck ─▶ emit Core IR（只含规范名）
```

- **R1（`resolveDeclarations`）**：解析不依赖类型的所有位置：声明名（Data/Enum/Func/字段声明/枚举成员声明）、全部 TYPE 位置（含嵌套类型参数）、函数引用、模式中的构造器名、枚举限定引用 `E.M`、构造表达式 `X with …` 的类型名 X 与其字段名（X 已知）。输出：AST 节点写入 `resolved = { canonical, role, written }`。
- **R2（`resolveTypedReferences`）**：解析依赖类型的三类位置——(a) 点分路径 `x.f.g` 的字段段与 `Set` 目标；(b) 期望类型为枚举时的裸单段名；(c) 主语类型在 R1 未知的 `PatternCtor`。它是**独立的 Core pass**，在 `typecheck` 之前运行，并在 `compile()`（不做类型检查的路径）中**同样运行**，保证任何出口的 IR 都只含规范名。
- **R2 的类型来源（规范，两引擎以此为准，不复用各自类型检查器的推断）**：R2 只使用**显式声明**得到的类型，规则刻意保守，以保证两引擎一致：

  | 表达式 / 位置 | R2 静态类型 |
  |---|---|
  | 函数参数、lambda 参数、带类型注解的 Let | 声明类型 |
  | Let 右侧为 Construct `X with …` | X |
  | Let 右侧为对已解析函数（源码或导入）的直接调用 | 该函数的声明返回类型（无声明返回类型 → 未知） |
  | 点分路径 `x.f`（x 类型已知为 Data S） | S 中字段 f 的声明类型 |
  | 其它一切（字面量、ListLiteral、Some/Ok/Err、IfExpr、Match 表达式、Await、高阶调用、无注解复杂表达式） | **未知** |

  期望类型只来自声明：`Return`（所在函数的声明返回类型）、调用实参（被调函数对应参数的声明类型）、`ConstructField`（所构造 Data 的字段声明类型）、带注解 Let、`Set` 目标（目标字段声明类型）、`Case` 模式（Match 主语按上表推得的类型）。其它位置无期望类型。

- **R2 每类位置的三态结果与最终 IR 行为**（任一 error 均使编译失败、**不产出 IR**，因此“出口 IR 只含规范名”由构造保证）：

  | 位置 | 类型/期望类型已知 | 未知：全局唯一候选 | 未知：多候选或零候选 |
  |---|---|---|---|
  | FIELD_REF（点分后续段、`Set` 目标） | 查 `fields[S]`；无 → `E_UNRESOLVED_FIELD` | 采用 + `W_RESOLVED_WITHOUT_TYPE` | 多 → `E_AMBIGUOUS_FIELD`；零 → `E_UNRESOLVED_FIELD` |
  | 裸单段名，期望类型为枚举 E（Return/实参/ConstructField/注解 Let/Set/Case） | 查 `enumMembers[E]`（与 §6.2 冲突规则合并判定） | 不适用（无期望类型即不进入本行） | 不适用 |
  | 裸单段名，无期望类型 | 按 §6.2 值位置（LOCAL → 模块函数/类型 → 词汇 functions/types → 规范名直写）；枚举成员**不**在此解析 | — | 零 → `E_UNRESOLVED_NAME`；多 → `E_AMBIGUOUS_NAME` |
  | PatternCtor | 查主语类型的成员 / 构造器 | 在全部枚举成员与 Data 构造器中唯一 → 采用 + `W_RESOLVED_WITHOUT_TYPE` | 多 → `E_AMBIGUOUS_PATTERN`；零 → `E_UNRESOLVED_PATTERN` |

  提示文案统一引导两种修复：写限定名（`Stufe.Hoch`）或为主语/绑定加类型注解。
- **Origin 扩展、parity 与语义哈希分离**：Core `Origin` 增加可选 `{ written?: string; role?: Role }`，仅在 `written !== canonical` 时写入。
  - **parity 比较纳入**：两者由源码与词汇确定性产生，两引擎必须一致。`ir-normalize.ts` 新增选项 `includeResolution`（默认 `false`）；`parity-tier1.mjs` 与 Java runner 以 `true` 调用，此时 `normalizeOrigin` 额外拷贝 `written`、`role`。
  - **`contentHash` 不纳入**：`contentHash`（ADR 0037）沿用默认 `includeResolution=false`，因此“同一规范 IR、仅本地化拼写不同”的两份源码 hash 相同——contentHash 保持“语义身份”含义；拼写与词汇的身份由 source envelope（含 `scopesJson`，§7）承担。现有 contentHash 基线不因本 ADR 改变（除 R1/R2 实际改变了规范 IR 的语料）。

## 5. 位置（角色）全表

以两引擎 AST 节点为基准（TS `src/ast/ast.ts`：Module、Import、Data、Enum、Func、Let、Set、Return、If、Match、Case、Block、Start、Wait、workflow、step、Call、Construct、Lambda、Await、Ok、Err、Some、None、Null、Name、ListLit、各字面量、TypeName、TypeApp、TypeVar、EffectVar、TypePii、Maybe、Option、Result、List、Map、PatternCtor、PatternName、PatternInt、PatternNull；Java `aster.core.ast` 见 §2，含 TypeAlias、FuncType、IfExpr、RetryPolicy、Timeout）：

| 节点 / 位置 | 角色 | 阶段 | 解析规则 |
|---|---|---|---|
| `Module a.b`、`Import`/`use` 路径、限定调用的命名空间段 | MODULE | — | **不本地化**，原样 |
| `Data` 名、`Enum` 名、`TypeAlias` 名 | TYPE_DECL | R1 | 源码写法 → 词汇 types（精确优先），否则原样作为规范名；TypeAlias 的右侧按 TYPE 递归 |
| `Data` 字段声明名 | FIELD_DECL | R1 | 词汇 `fields[该 Data 规范名]` |
| `Enum` 成员声明 | MEMBER_DECL | R1 | 词汇 `enumMembers[该 Enum 规范名]` |
| `Func` 名（规则/函数声明） | FUNC_DECL | R1 | 词汇 functions |
| `TypeName`（含 `TypeApp` 参数、`List`/`Map`/`Maybe`/`Option`/`Result` 的元素与键值类型、`FuncType` 的参数与返回类型、`TypePii` 内层、参数/返回/字段/lambda 参数/Let 注解，全部递归） | TYPE | R1 | 源码 TYPE_DECL（含 TypeAlias）→ 词汇 types → 内置类型（Int/Text/…，按 lexicon 已翻译） |
| `TypeVar`、`EffectVar` | TYPE_PARAM | R1 | 源码局部，**不查词汇** |
| `Func` 参数、`Let` 绑定、`Lambda` 参数、`PatternName` 绑定、`Match`/`Case` 绑定、workflow 步骤名（`step` 声明）、`Start`/`Wait` 引用的步骤名 | LOCAL | R1 | 仅源码作用域，**不查词汇** |
| `Name`（单段，值位置） | VALUE_REF | R1 / R2 | 见 §6.2；可能是 LOCAL、FUNC、或（R2）期望类型下的枚举成员 |
| `Name` 点分路径首段 | VALUE_REF 或 TYPE（`E.M` 形式时为 TYPE） | R1 | 首段先按 LOCAL/FUNC，失败再按 TYPE（枚举/数据名限定） |
| `Name` 点分路径后续段（`x.f.g`） | FIELD_REF | R2 | 按前一段的类型 S 查 `fields[S]` |
| `E.M`（首段为枚举类型） | MEMBER_REF | R1 | `enumMembers[E]` |
| `Call` 目标（`f(…)`、`apply f to …`）、函数值引用 | FUNC_REF | R1 | 源码 FUNC_DECL → 词汇 functions → 内置/导入 |
| `Construct` 类型名 `X with …` | TYPE | R1 | 同 TYPE |
| `ConstructField.name`（嵌套构造递归） | FIELD_REF | R1 | `fields[X]`（X 已在 R1 解析） |
| `Set` 目标（`set x.f to …`） | FIELD_REF | R2 | 同点分路径 |
| `PatternCtor` 构造器名（`Ok`/`Err`/`Some`/`None` 为关键词，不查词汇；用户 Data/枚举成员构造器） | TYPE 或 MEMBER_REF | R1 | 按被匹配值的声明类型（`Match` 主语为参数/已注解 Let 时 R1 可知；否则延至 R2） |
| `PatternInt`/`PatternNull`/字面量 | — | — | 不涉及 |
| 期望类型为枚举 E 时的裸单段名（`Return Hoch`、`Case Hoch`、参数实参等） | MEMBER_REF | R2 | 见 §6.2 第 3 步 |
| `Await`/`Ok`/`Err`/`Some`、`IfExpr` 的条件与分支、`ListLiteral` 元素 | 递归 | — | 按内层节点规则；这些表达式在 R2 中类型为未知（§4） |
| `Workflow` 名、`WorkflowStep` 名、`RetryPolicy`/`Timeout` 的配置键 | LOCAL / 语法键 | — | 工作流与步骤名为源码局部声明，不查词汇；`RetryPolicy`/`Timeout` 的键由 lexicon 关键词决定，值为字面量 |
| 字符串字面量内容 | — | — | 不解析（现状一致） |
| 治理注解（`@id`、`@control`、`@pii` 等） | — | — | 注解**名**属于语法关键词（lexicon 固定，不本地化）；注解**参数**只允许字符串/数字/布尔/null 字面量（TS `AnnotationArg`），不是标识符，故无词汇解析；`@pii` 等引用字段的注解按其所附着的 FIELD_DECL 已解析的规范名生效 |

> 实现要求：两引擎各自以**穷举式 visitor**实现（TS 用 `switch (node.kind)` 的 `never` 穷尽检查，Java 用 sealed 接口 + switch 模式匹配），新增 AST 节点类型时编译失败而非静默遗漏。

## 6. 解析算法

### 6.1 声明位置（R1）

声明的**写法**决定其规范名：
1. 若写法精确命中该角色词汇（TYPE_DECL→types，FIELD_DECL→`fields[父]`，MEMBER_DECL→`enumMembers[父]`，FUNC_DECL→functions），规范名取词汇条目；
2. 否则若折叠后唯一命中，取之；折叠后多候选 → `E_AMBIGUOUS_NAME`；
3. 否则规范名 = 写法本身（作者直接写规范名或词汇未覆盖的名字）。

声明之间冲突（同角色同作用域两个声明解析到同一规范名）→ `E_DUPLICATE_DECLARATION`（附两处原写法）。

### 6.2 引用位置

按顺序，首个成立即采用：
1. **LOCAL**（仅值位置单段名 / 点分首段）：当前词法作用域内源码声明的局部名，精确匹配 → 再折叠唯一匹配。
2. **模块声明**：本模块已解析的声明（FUNC_DECL / TYPE_DECL / 其成员），按**原写法与规范名**双向精确匹配 → 再折叠唯一匹配。
3. **期望类型驱动（R2）**：期望类型为枚举 E → `enumMembers[E]`；FIELD_REF → `fields[S]`。
4. **词汇（无期望类型）**：按该位置允许的角色查 types / functions。
5. **规范名直写**：写法本身即某候选的规范名时接受。
6. 全部失败 → `E_UNRESOLVED_<ROLE>`。

**期望类型为枚举 E 的位置（Return / 调用实参 / ConstructField / 注解 Let / Set / Case）上的裸单段名，跨层裁决规则（完整）**：

| 命中情况 | 结果 |
|---|---|
| LOCAL 命中 | 取 LOCAL；若 `enumMembers[E]` 也命中 → `W_SHADOWED_VOCABULARY`（提示可写 `E.M`） |
| 无 LOCAL；`enumMembers[E]` 命中，且模块声明（函数 / 类型 / 其它枚举的成员）或词汇 functions / types 中**任一**也命中 | `E_AMBIGUOUS_NAME`（列出全部候选及来源，提示写 `E.M` 或 `f()`） |
| 无 LOCAL；仅 `enumMembers[E]` 命中 | 取该成员 |
| 无 LOCAL；`enumMembers[E]` 未命中 | 按无期望类型的值位置规则（§6.2 第 2、4、5 步）解析；成功后由类型检查报告类型不符 |

**其它位置同层多候选一律报错，不按“更高层优先”静默选择以外的方式取舍**：
- 单段值名同时匹配 FUNC（第 2 步）与期望类型下的枚举成员（第 3 步）：因第 2 步先成立会被选中——这正是 `hoch`/`Hoch` 场景。为避免产生错误策略，规定：**当第 2 步命中 FUNC、且存在期望枚举类型 E 而 `enumMembers[E]` 也命中时，报 `E_AMBIGUOUS_NAME`**，提示写 `Stufe.Hoch` 或 `hoch()`（采纳 Codex 建议：不静默、不只 warning）。
- 源码局部名与词汇条目同写法时局部名胜出，发 `W_SHADOWED_VOCABULARY`。

### 6.3 大小写折叠（跨语言一致）

- **规范化**：比较前对写法与词汇条目统一做 **NFC**。
- **精确匹配**：NFC 后逐码点相等。
- **折叠匹配**：`fold(s)`（实现中的 foldKey 为 `NFC(逐码点 C+S 折叠(NFC(s)))`，见 §15 R8）= 对 NFC 后的每个码点应用 **Unicode 简单大小写折叠（CaseFolding.txt 中状态 C + S 的映射，不含 F/T）**，Unicode 版本固定为 **15.1**。不使用宿主语言的 `toLowerCase()`（JS 与 Java 的特殊规则不一致，且依赖 locale）。
- **实现**：折叠表由单一来源生成（`aster-lang-locales` 新增 `casefold/casefold-15.1.json`（见 §15 R6），构建时分别生成 TS 与 Java 常量表），两引擎共享同一份数据。
- **测试向量**（两引擎同一组，均标明期望；向量文件由 Unicode 15.1 官方 CaseFolding.txt 生成并校验）：
  | 输入对 | 折叠后相等？ | 说明 |
  |---|---|---|
  | `Stufe` / `stufe` | 是 | 基本大小写 |
  | `Straße` / `STRASSE` | **否** | 简单折叠不把 ß 展开为 ss（全折叠才会） |
  | `ẞ`（U+1E9E）/ `ß` | 是 | CaseFolding.txt S 映射 |
  | `İ`（U+0130）/ `i̇`（i + U+0307） | **否** | U+0130 在 CaseFolding.txt 仅有 F（→ i + U+0307）与 T（→ i）映射；简单折叠只用 C+S，故 U+0130 保持不变，而右侧是两个码点（NFC 不会合成为 U+0130） |
  | `Σ` / `σ` / `ς` | 是（三者两两相等） | ς 的 C 映射为 σ |
  | `ǅ` / `ǆ` | 是 | 标题大小写 |
  | `é`（U+00E9）/ `é`（e + U+0301） | 是 | 先 NFC 再折叠 |
  | 天城文 / CJK 任意词 | 仅与自身相等 | 无大小写 |

## 7. 词汇索引数据结构

`IdentifierIndex` 改为按角色与作用域的只读视图；**删除 `toCanonical` 作为翻译入口**：

```
VocabularyScopes {
  types:       Map<foldKey, Candidate[]>                       // struct + enum
  fields:      Map<canonicalStruct, Map<foldKey, Candidate[]>>
  enumMembers: Map<canonicalEnum,  Map<foldKey, Candidate[]>>
  functions:   Map<foldKey, Candidate[]>
  literals:    （保持，仅供 canonicalize 字面量宏）
}
Candidate { written(NFC), canonical, kind, parent?, source: 'term' | 'synonym' }
```

- 构建时不覆盖：同键多候选全部保留，由 §6 判定。
- field 条目必须有 parent（规范 struct 名）；不支持全局字段（采纳 Codex 建议）。
- **序列化契约**：`VocabularyScopes` 有稳定 JSON 形状（键按码点排序、数组按 `(canonical, written)` 排序、NFC、`schemaVersion: 1`），两引擎与 cloud 各自实现的 `canonicalScopesJson()` 必须逐字节一致（parity 测试覆盖）。`scopesHash = sha256(canonicalScopesJson)`；无词汇时为空串。
- **进入 source envelope（破坏性协议变更）**：解析结果依赖词汇，envelope 固定为 **5 段**：`computeSourceEnvelope(content, aliasSetJson, locale, toolchainId, scopesJson)`，分帧规则不变（`<byteLen>:<utf8>|`）。
  - 旧的 4 参数签名**删除**（TS 与 Java 均为必填第 5 参数，调用点编译期报错，不存在“缺省第 5 段”的运行时分支）。
  - **无词汇时 `scopesJson` 为空串 `""`**（不调用 `canonicalScopesJson()`，不序列化空 scopes 与 `schemaVersion`）；有词汇时为 `canonicalScopesJson()`。
  - 共享测试向量（cloud 与 Java 同一份）至少覆盖：无词汇（第 5 段 `0:|`）、单 struct 词汇、含同义词与 NFC 组合字符的词汇。
  - 已审批版本的 `scopesJson` 来自其冻结快照（ADR 0014 / #610 的作用域冻结），草稿来自作者实时词汇。
- cloud 执行输入映射复用该结构（§8）；编译/类型提取缓存键使用 `scopesHash`。

## 8. 下游接口契约

| 组件 | 改动与契约 |
|---|---|
| **aster-lang-ts** | 删除 `translateIdentifiers*`；新增 `src/frontend/resolve-declarations.ts`（R1）、`src/core/resolve-typed-references.ts`（R2）；`compile` / `compileAndTypecheck` / `emit-core` 均执行 R1+R2；导出 `buildVocabularyScopes(vocab)` 与 `resolvePolicyTypes(source, ctx)`（返回已解析的 Data/Enum 声明及其原写法，供 cloud 输入映射）。 |
| **aster-lang-core** | `Canonicalizer` 去掉标识符翻译（保留字面量宏）；新增 `aster.core.resolve.DeclarationResolver`（R1，作用于 AST）与 `TypedReferenceResolver`（R2，作用于 CoreModel）；`InProcessCnlParser.parse(src, scopes)` 依序执行。 |
| **aster-lang-locales** | 新增 Unicode 15.1 简单折叠表与生成脚本。 |
| **aster-lang-test** | 语料格式扩展：`<case>.vocab.json`（VocabularyScopes 的输入形态，即 DomainVocabulary JSON）与样例同名；`manifest.json` 中 `vocab: true` 标记；`parity-tier1.mjs` 与 Java runner 读取并传入；PR-blocking 门禁覆盖。新增 ≥ 16 例：§1 全部复现、跨 struct 同名字段、遮蔽 warning、各歧义 error、折叠测试向量、嵌套类型参数、模式匹配构造器、workflow 步骤名与词条同名。 |
| **LSP** | 悬停/跳转用 Origin `{written, role}`；补全按位置角色过滤（FIELD_REF 按接收者类型）；语法不完整时仅运行 R1 于可解析片段，R2 跳过并降级为不提供字段补全。 |
| **aster-api** | `CompileRequest.vocabulary` / `SourcePolicyRequest.vocabulary` → `buildVocabularyScopes`（`VocabularyBounds` 不变）；`PolicyCompiler`、`DynamicCnlExecutor`（其编译缓存键加入 `scopesHash` 与 resolver 版本）、`ReplayExecutionCore`、`CedarExportService` 统一走新入口；Cedar 导出基于 R2 后的 Core IR。 |
| **aster-cloud** | 编辑器注册词汇改为传 `VocabularyScopes`；执行输入映射（`src/lib/execution-input/`）改为调用引擎 `resolvePolicyTypes`，**删除 cloud 自建拼写表**（消除双实现漂移，并从根本上解决 #638）；词条保存校验仅拒绝“同角色同作用域、NFC 后写法完全相同但规范名不同”的条目（仅大小写不同的条目允许，使用点按 §6 判定——采纳 Codex 建议）。 |
| **AI 生成 / Suggest 提示词** | 更新“词汇在编译前替换”的说明为按位置解析；提示模型在可能歧义时使用限定写法（`Stufe.Hoch`）。 |

## 9. 不做向后兼容的落地步骤

1. **工具链标识**：cloud `cloudToolchainId()` 与 Java `ToolchainIdentityProvider.currentToolchainId()` 同步改为 `abi=1.1;core=<engine>;validator=<v>;resolver=1;casefold=15.1;build=<b>`。`resolver` 随名称解析算法变更递增；`casefold` 为 §6.3 折叠表的 Unicode 版本，升级折叠数据必须同时改变它。envelope 已覆盖 `toolchainId`（第 4 段，已核对实现），加上 §7 新增的第 5 段，旧 envelope 全部失配。
2. **缓存失效**：aster-api `DynamicCnlExecutor` / `PolicyCompiler` 编译缓存、cloud 类型提取 LRU 与 KV 元数据缓存的键均纳入 `toolchainId` 与 `scopesHash`；发布即全部失效。
3. **存量数据（均为测试数据）重编译**——两库的版本是各自独立的产物（aster-api `policy_versions` 与 cloud `"PolicyVersion"` 不共享事务），因此按**每库独立、逐行幂等**执行：
   - 先 `pg_dump` 两库对应表（含 `UserVocabularySnapshot`）作为回滚点；
   - 两张表各加四列（迁移）：`resolverVersion`（默认 NULL，**仅成功时**写入 1）、`resolverMigrationError`（失败原因，成功时清空）、`archivedByResolverMigration`（布尔，默认 false：**仅本任务归档的行**为 true）、`statusBeforeArchive`（本任务归档前的状态）；
   - 任务处理 `resolverVersion IS DISTINCT FROM 1` 的全部行，按主键升序分批（每批 50），**每行一个事务**：
     - 成功：写新 Core IR / envelope / `resolverVersion=1`，清空 `resolverMigrationError`；**仅当 `archivedByResolverMigration = true`** 时恢复 `status = statusBeforeArchive` 并把标记置回 false；其它已归档行（人工或业务归档）状态不动；
     - 失败：**不改动**旧 Core IR / envelope；记录 `resolverMigrationError`；若当前状态不是 ARCHIVED，则 `statusBeforeArchive = status`、`status = ARCHIVED`、`archivedByResolverMigration = true`；若已是 ARCHIVED（无论来源）则只更新错误、不改标记；`resolverVersion` 保持 NULL；
   - 因失败行保持 `resolverVersion` 为 NULL，修复原因后重跑会再次尝试；成功行被跳过。任务可随时中断与重跑；两库互不影响，报告分别列出成功/失败；
   - 运行方式：aster-api 管理端点（处理 `policy_versions`）与 cloud 管理脚本（处理 `"PolicyVersion"`，编译经 aster-api 签名接口），均由同一 k8s Job 依次调用，Job 输出汇总报告。
4. **回滚方案**：发布列车整体回滚到上一 tag（locales/core/ts/test/api/cloud 同一列车），并用第 3 步的 dump 恢复两库对应表（`resolverVersion` 列在回滚迁移中删除）。因均为测试数据，回滚窗口内新增的数据不保留。
5. 删除 cloud 文档中“参数名与词条同名需用规范 key”的约定。

## 10. 备选方案（已否决）

| 方案 | 否决理由 |
|---|---|
| A. 解析前按前一 token 启发式判断角色 | 构造、限定名、模式、嵌套类型参数等上下文远超前一 token；无法按接收者类型解析字段。 |
| B. 保持全局替换，仅禁止词汇中出现大小写冲突 | 把语言限制转嫁给用户，不提供自由度。 |
| C. 只在 cloud 做映射，引擎不变 | 源码编译本身失败（Stufe/stufe），cloud 无法补救。 |
| D. 把全部解析放进完整类型检查器 | `compile()` 不做类型检查也必须产出规范 IR；且两引擎检查器结构差异大，独立 R2 pass 更易对齐。 |

## 11. 测试与验收

1. 两引擎单测：§5 每一行、§6 每一步与每个诊断（含 i18n 文案 zh/en/de/hi）、§6.3 全部测试向量。
2. tier1 parity：§8 所列 ≥ 16 例，两引擎 IR（去 origin）逐字段一致，`--mode=eval` 结果一致。
3. 回归：现有 parity / golden / eval 语料通过；因删除全局替换而**有意**改变的期望逐例在 PR 中说明。
4. aster-api：编译/执行/What-If/Cedar 导出各一条带词汇的端到端测试。
5. cloud：#626/#632/#638 全部测试改为基于引擎 `resolvePolicyTypes` 后通过；本地栈端到端复现 §1 全部场景成功。
6. LSP：按角色悬停/补全单测；语法不完整时的降级测试。

## 12. 实施阶段

1. aster-lang-locales：Unicode 15.1 简单折叠表（`casefold/casefold-15.1.json`）+ 生成脚本。
2. aster-lang-ts：R1 + R2 + 诊断 + `buildVocabularyScopes` / `resolvePolicyTypes`；删除文本替换。
3. aster-lang-core：同构实现。
4. aster-lang-test：语料自带词汇格式、runner 与门禁改造、新增语料。
5. aster-api：入口切换、缓存键、`toolchainId`。
6. aster-cloud：编辑器、执行输入映射复用引擎、词条校验、`toolchainId`。
7. 发布列车：locales → core/ts → test → api → cloud；上线后执行 §9 第 3 步。

## 13. 风险

- **R2 最小类型推断的覆盖面**：无注解复杂表达式的接收者类型无法确定时依赖“全局唯一候选”回退；歧义会以 error 暴露（而非静默错误），可通过加注解解决。
- **LSP 降级**：未完成源码只有 R1 结果。
- **两引擎实现成本**：中到大；以穷举 visitor + 共享折叠表 + parity 语料控制漂移。

## 14. 已决问题（2026-10-27 用户定稿）

1. 裸名同时命中源码函数与期望枚举成员 → **`E_AMBIGUOUS_NAME`，要求限定写法**（`Stufe.Hoch` 或 `hoch()`）。理由：静默择一可能把 `Return Hoch` 变成调用规则 `hoch`，产生错误决策且无信号；修复成本只是一个限定。
2. 仅大小写不同的词汇条目 → **允许保存，使用点按 §6 判定**（精确优先，折叠匹配仅在唯一时采用）。理由：这正是本 ADR 要提供的自由度（`Stufe` 类型 / `stufe` 字段并存）；真正歧义仍会报错。
3. 全局字段（无 parent 的 field 词条）→ **不支持**。理由：按接收者类型解析字段是跨 struct 同名字段的基础，全局字段会与之竞争并引入额外优先级问题；cloud 已要求 field 有 parent。
4. 模块路径本地化 → **不支持**。理由：模块路径是身份标识（导入、包解析、Cedar 导出、审计均以其为键），多语言别名会破坏跨策略引用与团队共享。
5. Unicode 折叠版本 → **以 `casefold=` 分量进入 `toolchainId`；升级即递增 `abi` 并全量重编译**。理由：升级可能改变哪些名字折叠为同一键，必须在每个 envelope 中显式可见，避免静默改变已存版本语义。

注意：第 1 条是唯一会让“今天能编译的源码”在新规则下报错的决定；在不做向后兼容的前提下可接受，§9 迁移任务会在报告中列出受影响的测试策略。

## 15. 实施修订（Implementation amendments）

以下按实现过程中的裁决（R 编号）修订本 ADR；与正文冲突时以本节为准。状态仍为 ACCEPTED。

### 15.1 折叠与序列化
- **R6**：折叠表文件为 aster-lang-locales 的 `casefold/casefold-15.1.json`（原文 `case-folding-15.1.json` 作废）。
- **R8**：`foldKey(s) = NFC(逐码点 C+S 折叠(NFC(s)))`，折叠键对 NFC 封闭（如 `Ϊ́ ≡ ΐ`），序列化器对键再做 NFC 为空操作；两引擎一致。
- **R9**：canonical JSON 字节契约：字符串转义取 `JSON.stringify` 形式（`\"` `\\` `\b` `\f` `\n` `\r` `\t`，其余 < U+0020 为小写 `\u00xx`；U+007F/U+2028/U+2029 原样输出）；数组排序键依次为 `(canonical, written, kind, source, parent)`；`literals` 为 map 形状。`literal_conflict` 与 `member_parent_required` 作为有意新增的校验保留。
- **R27**：内建类型集合不含 `Number`/`String`（Java 向 TS 对齐），含 `Decimal`、`DateTime`。推断返回类型占位符静默探测，命中即盖章规范名。
- **R33**：normalizer 会剥离 type/ret/retType，故函数签名的 written/role 不参与 parity 比较（由引擎单测各自覆盖）。

### 15.2 解析规则修订
- **R16**：写法等于某既有候选的规范名（任意模块/导入枚举的成员、Data 字段、内建/stdlib 函数、已声明函数）时**静默接受**，不报 W801/E801/E802；W801 仅在 `written ≠ canonical` 且经唯一全局候选解析时出现。无期望类型的裸枚举成员：当 written 恰为唯一一个成员的规范名时接受；仅有本地化名则 E802。内建/stdlib 调用目标与 lambda 局部变量永不报 E802。
- **R18**：解析**保持写法形状**：裸名→裸规范名（`Hoch`→`High`），限定→限定（`Stufe.Hoch`→`Level.High`）。类型检查器与运行时（TS、Java）均接受限定写法 `E.M`（E804/E806 的 help 指向该写法）。
- **R19**（偏离正文）：FUNC_REF 调用目标在所有层均无候选时保持原写法，R2 **不**报 E802，由类型检查器的未定义函数诊断负责。
- **R20**：点号尾段的“不透明”判定为白名单：dynamic/未类型化、TypeVar、内建标量、Map/Json/Any；别名、包装类型（Option/Maybe/List/TypeApp）与未声明类型名视为 UNKNOWN（走全局候选规则）。`Let y be x` 继承 x 的声明类型。**R30/R31**：无类型注解的函数参数与 lambda 参数视为 UNKNOWN（两引擎一致）。
- **R21**：期望类型为枚举 E 时，跨层冲突集仅含 LOCAL、模块函数/类型、词汇函数/类型；其他枚举的成员不构成冲突。LOCAL 精确命中优先于成员折叠歧义。§14.1 的“枚举成员 vs 函数/类型”冲突仍为 **E804**（不因精确优先而豁免）。
- **R49**：相等/不等比较中，若另一操作数为已知枚举，则本操作数以该枚举为期望类型（本地化裸成员可解析）；E802 对值操作数使用非 FUNC_REF 的角色标签；E802/E804 的 help 提示 `Stufe.Hoch` 写法；E804 候选去重。

### 15.3 内建词汇与注册表
- **R22/R26**：内建词汇的枚举值须带 parent；已在 aster-lang-locales 为 zh/en/de 的 finance-loan、insurance-auto 补全（CreditLevel、LoanType、QuoteStatus、RiskLevel）。TS/Java 注册表对无 parent 的 field/枚举值条目拒绝或跳过（显式注释与测试），作为守卫保留。
- **R49**：Java `DomainVocabulary.validate` 与 TS 的按作用域规则对齐（parent 必填，角色+作用域冲突）。

### 15.4 工具链与信封
- **R36**：`toolchainId` 的 core 分量在 Java 为核心引擎版本（如 `core=1.0.35`），cloud 为 `core=ts`（正文 §9.1 的 `core=<engine>` 据此理解）。
- **R37**：无词汇或无条目时 `scopesJson` 为 `""`；仅有字面量的词汇仍取完整 `canonicalScopesJson()`（字面量会改变编译输入）。cloud 同。
- **R40**：cloud 在封存时持久化 `sourceScopesJson`（`""` 表示无），重算信封与 `recordedCompileLocale` 一律使用该存值，不再由实时/快照词汇推导。**R49**：审批时若冻结快照推得的作用域与封存值不一致，则拒绝（本地化文案“词汇自保存后已变更”）。NULL 的存量行先尝试 4 段旧信封摘要。
- **R35**（偏离 §9.2）：aster-api 的 `PolicyCompiler` versionId 缓存不含词汇作用域，因 api 侧存储的版本不携带词汇。

### 15.5 迁移语义（实际实现）
- 迁移前先做完整性校验（信封校验/`source_hash`）：不一致记为失败（“pre-migration integrity mismatch”），不重新封存；信封存在而 toolchain 为空亦视为不一致。
- 在 `createVersion` 中对链尾版本加悲观写锁（api 与 cloud 均按 `(version, id)` 降序），迁移时重链（chain relink）；`core_json` 仅在原值非 NULL 时写入。
- 失败分类：仅**编译诊断**（含源码/别名输入错误的 400/422 且带诊断）才算解析器失败并归档；其余 4xx/5xx/传输/异常一律为**基础设施失败**（重抛、不归档、退出码非零）。迁移脚本在 `ASTER_POLICY_API_INTERNAL_URL`/签名密钥缺失时 fail closed。

### 15.6 发布与部署顺序
- **R41/R46**：cloud 拆为两个 PR：PR-A 含 drizzle 迁移（0060/0061）与 schema，PR-B 为其余改动；漂移检查要求 schema 进入 PR-A，零停机做法是用户在合并 PR-A **之前**于生产执行 cloud 的加法 DDL（`IF NOT EXISTS`）；备选为接受短窗口并依赖 db-bootstrap 冷启动的 `ALTER … ADD COLUMN IF NOT EXISTS "sourceScopesJson"` 兜底。core 的前置 PR 须先于 1.0.36 列车合并；api PR 在列车打 tag 之后合并；KV 缓存陈旧窗口 ≤ 1h（文档化）。
- **R46/R47**：迁移使用**专用镜像** `Dockerfile.resolver-migration`（窄路径过滤、由 image-lock 固定，不属于 PostSync/migrate 门禁）；Job 资源 requests ≤ 25m CPU/128Mi 并设 limits；api 备份用具备序列读权限的凭据。
- **R48**：迁移 Job 目录置于任何 ApplicationSet glob 之外（`apps/aster-lang-ops/resolver-migration/`），以逐个 generator 的 `git ls-files` 仿真验证；镜像构建作业独立成 workflow，不阻塞 Worker 部署。
