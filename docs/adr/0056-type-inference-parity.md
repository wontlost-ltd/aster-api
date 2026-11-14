# ADR 0056：推断值统一 —— 清空 Core IR v2 值层豁免表（V1–V4）

- 状态：**Accepted**（2026-11-14 用户确认；§7 五项缺省方案同日全部确认）
- 提出：2026-11-14；来源 ADR 0055 §6 值层分期类别 V1–V4（aster-cloud/aster-lang-test#195 / #196 / #197 / #198）
- 前提：沿用 ADR 0055 的前提——项目尚未上线，没有真实用户，上线前数据可清空。因此本 ADR **不做兼容、不做迁移**，推断结果的变化直接生效
- 相关：ADR 0055（Core IR v2；本 ADR 是其 §6「两引擎推断规则统一（另立 ADR）」与 §7「范围外」一条的落地）、ADR 0045（控制注册表：共享数据放在 aster-lang-locales，副本 + sibling parity 测试）、ADR 0051 R2（类型驱动解析只用显式声明的类型）、ADR 0037（contentHash 不含 origin）
- 目标：go-live 要求豁免表为空（ADR 0055 §6）。本 ADR 给出四个类别各自唯一的规范规则，两引擎按同一规则实现后逐类删除豁免

---

## 0. TL;DR

| 类别 | 门禁计数（manifest 327 份） | 全语料（两侧都能编译的 434 份） | 规范规则（一句话） | 改哪侧 |
|---|---|---|---|---|
| V1 类型推断 | 654 = `Func.ret` 553 + 形参 / 字段类型 101 | 683 + 116 | 推断返回类型一律是占位 `Unknown`；未标注形参 / 字段的类型由 aster-lang-locales 的**一张**命名规则表按「分词 + 词表」判定，与模块词法表无关，再按约束修正 | 两侧（Java 为主） |
| V2 能力推断 | 44 | 55 | 非显式时 `effectCaps` 恒为 `[]`；IR 只记源码写下的能力 | ts |
| V3 闭包捕获 | 0（未登记） | 1 | 只列 lambda 体内引用到、且在 lambda 外层**局部作用域**绑定的名字，按首次引用的源码位置排序 | 两侧（Java 为主） |
| V4 origin 行号 | 16 | 17 | 推断占位类型节点的 origin 锚定在 `produce` 关键词上；其余节点的结束位置是最后一个非布局 token | Java（ts 只改 lambda 锚点的列） |

声明尾部（ADR 0055 记录的 15 处）与多行续行在当前两侧头部均为 0 处差异；V4 只剩推断占位的锚点。V1 的 `Func.ret` 占位与 V4 是同一处代码（Java `AstBuilder` 用整个声明 / lambda 的 span 作占位类型的 origin），一并修。

建议实施顺序：**V2 → V1-ret + V4 → V1-类型 → V3**（§6）。

---

## 1. 实测方法与总量

**环境**（2026-11-14，各仓 `origin/main`）：aster-lang-test `d09c647`、aster-lang-core `bb20259`、aster-lang-ts `9ff4a29`、aster-lang-locales `ac6f3a6`。core 组合构建用本地发布的 platform 1.0.46、aster-lang-test JVM 语料包与 aster-lang-hi，`ASTER_LOCALES_DIR` 指向同级 locales。

**门禁口径**：`node scripts/parity-tier1.mjs --mode=ir --full` → 327/327 identical，豁免掉 V1 654、V2 44、V4 16，stale / expired 均为 0。X1 报告里的 4 处 shape-diff 与 2 处 Float/Double 值差异在当前 main 上已修复。

**全语料口径**：临时把 manifest 换成 `corpus/` 下除 `tier1-reject/` 外的全部 485 个 `.aster`，加 `--report-only --dump-dir`，再对两侧都产出 IR 的样本逐节点比较（仍去掉 `origin.*.col`，但**不做任何豁免**）。剩下的值差异恰好四类：`name` 799 处（全部在推断类型节点上）、`origin.end.line` 700 处（683 处在推断的 `Func.ret` 上，17 处在 lambda 的推断 `ret` 上）、`effectCaps` 55 处、`captures` 1 处。没有第五类。

**模拟口径**（用于给 V1 分类）：对 en-US 样本，另用 ts `parseSource(src, EN_US)` 显式传入 en-US 词法表再编译一次，得到「ts 带 en-US overlay」的推断结果，以区分「ts 默认路径漏了 overlay」与「两侧规则表不同」两种根因；再用 §3.2 的规范算法离线计算一遍，统计改用规范规则后每侧要变多少处。

---

## 2. V1 类型推断：子模式、根因与对错

### 2.1 子模式计数

| 子模式 | 门禁 | 全语料 | 例子（文件，JSON 路径：ts / Java） |
|---|---|---|---|
| **R1** 推断的 `Func.ret`：ts 恒为 `Unknown`，Java 按规则名猜 | 553 | 683 | `tier1-equivalence/policies/test_life.aster`，`$.decls[3].ret`（`calculateBaseRate`）：`{"name":"Unknown", origin 行 35–35}` / `{"name":"Int", origin 行 35–53}` |
| **T1** ts 的 en-US 默认路径没有挂 en-US overlay 规则 | 98 | 112 | `test_life.aster`，`$.decls[2].fields[0].type.name`（`approved`）：`Text` / `Bool`；`lambda_cnl_match_maybe.aster`，`$.decls[0].body.statements[0].expr.params[0].type.name`（`candidate`）：`Text` / `DateTime` |
| **T2** Java 时间单位规则的大小写缺陷 | 3 | 3 | `risk.aster`，`$.decls[0].fields[3].type.name`（`monthlyExpenses`）：`Text` / `Int`；`25-if-multi-branch.aster` 的 `monthly_spend`；`30-business-gdpr-retention.aster` 的 `days_since_consent` |
| **T3** Java 不读语言 overlay（zh / de） | 0 | 1 | `conformance/cjk-v2/04-identifier-no-collision.aster`，`$.decls[0].fields[2].type.name`（`是否成年`）：`Bool` / `Text` |
| 合计 | 654 | 799 | |

**R1 再细分**（全语料 683，Java `inferReturnTypeName` 的分支）：默认 `Text` 542；`calculate/compute/count/sum/total*` → `Int` 54；`is/has/can/…/check/validate*` → `Bool` 46；`generate/create/build/make/new/get/fetch/load*` → 取后缀作类型名 39（其中 29 个是 `_data`、`_email`、`_hipaa_report` 这类以下划线开头的非法类型名，来自 snake_case 规则名）；`find/search/lookup*` → `Text` 2。前缀匹配没有词边界，`hashText` 被判成 `Bool`。

**潜伏差异**（今天被 T1 掩盖，只修 T1 就会冒出来）：全语料 55 处、门禁 52 处。ts 的 en-US overlay 里有、Java 内建表里没有的规则，例如 `value`、`requestedLimit`、`safetyRating`、`interestRateBps`、`debt` → ts `Int`、Java `Text`。所以 T1 和「规则表不一致」必须一起修，否则豁免清不掉。

### 2.2 根因（代码位置）

- **R1，Java**：`aster-lang-core/src/main/java/aster/core/parser/AstBuilder.java:209–216` 在没有返回类型时调用 `inferReturnTypeName(name)`（`:357–407`，按规则名前缀猜），并用 `spanFrom(ctx)`（整个声明）作 origin。Lambda 在 `:1679–1681` 同样用整个 lambda 的 span。
- **R1，ts**：`aster-lang-ts/src/parser/decl-parser.ts:413–434` 产出 `TypeName("Unknown")`，origin 是 `produce` 关键词（省略 `produce` 时取其后第一个 token）；lambda 在 `src/parser/expr-stmt-parser.ts:431–434`、`:1539–1542` 锚在 `produce` 之后的 `:`。
- **T1，ts**：`src/compile-pipeline.ts:49` 只有调用方传了 lexicon 才 `attachTypeInferenceRules`；`src/parser.ts:97–99` 对 en-US 走 `parse(tokens)`，同样不挂。于是同一份 en-US 源码，`compile(src)` 与 `compile(src, {lexicon: EN_US})` 推断不同（实测 `test_life.aster` 的 `LifeQuote.approved`：`Text` 与 `Bool`）。parity 门禁对 en-US 样本不传 lexicon（`aster-lang-test/scripts/parity-tier1.mjs:379` `tsLexiconFor`），所以测到的是缺 overlay 的那条路径。
- **T2，Java**：`aster-lang-core/src/main/java/aster/core/inference/TypeInference.java:75` 的 `(?:Years?|…|Seconds?)(?:[A-Z]|$)` 带 `CASE_INSENSITIVE`，边界 `[A-Z]` 因此也匹配小写，`monthly`、`days_s…` 都被判成时间单位。ts 的对应规则（`src/parser/type-inference.ts:51` 起的 `BASE_NAMING_RULES` 最后一条）用显式字符类、不带 `/i`，边界区分大小写。
- **T3 与潜伏差异，Java**：Java 只有 `TypeInference.NAMING_RULES` 一张写死的表（相当于 ts 的 BASE 加上 en-US overlay 的一部分），不读 locales 的 `overlays/<locale>/type-inference-rules.json`。ts 也不读这些 JSON（`registerOverlayTypeInferenceRules` 没有调用方），用的是 `src/config/lexicons/type-inference-rules.ts:18` 起的内联常量。结果是同一份规则数据有三份：Java 内建表、ts 内联常量、locales JSON，彼此已经漂移（例如 locales JSON 的布尔前缀规则没有 ts 内联版的词边界）。
- **约束修正（语料未覆盖，读码发现）**：ts 对推断类型按约束修正（`type-inference.ts:152` `refineInferredType`：`Range` → `Int`/`Float`，`Pattern` → `Text`），Java `AstBuilder.visitField` / `visitParam` 不做。语料里没有「未标注类型 + 约束」的字段，所以今天没有计数。

### 2.3 哪一侧对

- **返回类型（R1）：ts 对。** ADR 0055 #9 与 ADR 0051 R2 把推断返回类型定义为**占位**：R2 不用它（`TypedReferenceResolver` 与 ts `resolve-typed-references.ts:306` 都在 `retTypeInferred` 时返回未知），两侧类型检查也跳过它（Java `TypeChecker.java:413–415`，ts 同样以 `retTypeInferred` 跳过），真正的返回类型由检查器从函数体推断，不进 IR。Java 按名字猜出来的值没有任何读方依赖，还会产出 `_data` 这种不是合法类型名的东西，以及 X1 记录过的「被当成类型参数」的连带缺陷。
- **形参 / 字段类型（T1–T3）：架构上 ts 对，实现上两侧都有缺陷。** 规则数据应当是「语言无关的基线 + 语言 overlay」，数据放在 locales（这也是 locales 已有 `overlays/*/type-inference-rules.json` 的原因）。Java 把一份英文子集写死在 core 里，既缺 overlay 又有 T2 的正则缺陷；ts 有完整规则，却让默认路径漏掉 overlay（T1），而且自带的是内联副本，没有接上 locales 数据。两侧共同的问题是**用正则表达规则**：Java 与 JS 的正则方言在 `CASE_INSENSITIVE` / 字符类 / 后行断言上语义不同（T2 就是这样产生的），同一张表在两侧不能保证同义。
- **后缀匹配没有词边界（两侧共有）**：`(?:Date|Time|At|…)$/i` 让 `candidate` 推成 `DateTime`，`(?:…|Line|…)$/i` 让 `timeline` 推成 `Int`。这不是两侧分岔，但统一规则时一并修掉。

---

## 3. V1 规范规则

### 3.1 推断返回类型

1. 源码没有写返回类型（`produce` 后无类型，或整个省略 `produce`）的 `Func` 与 `Lambda`：`retTypeInferred = true`（仅 `Func` 有此字段），返回类型节点固定为 `{"kind":"TypeName","name":"Unknown"}`，不随规则名、函数体或词法表变化。
2. 这个名字只是占位，不参与名称解析，也不进入 `typeParams`（ADR 0055 #10）；源码写了 `produce Unknown` 的显式返回类型照常按类型名处理，两者靠 `retTypeInferred` 区分。
3. 删除 Java `AstBuilder.inferReturnTypeName`。函数体推断仍只在类型检查器内部进行，**不写回 IR**。

### 3.2 未标注形参 / 字段的推断类型

**数据与更新契约**：在 aster-lang-locales 新增 `type-inference/naming-rules.json`，它是规则的**唯一真相源**，与 `controls/registry.json` 同级，按 ADR 0045 §2.2 的副本模式分发：core 放 `src/main/resources/type-inference/naming-rules.json`，由 `NamingRulesSiblingParityTest` 与同级检出的 locales 做深度相等比较；ts 放 `shared/type-inference-naming-rules.json`，由 `test/unit/parser/naming-rules-parity.test.ts` 同法守护。CI 缺少同级 locales 即失败。改规则的唯一顺序是：先改 locales 的源文件（含测试向量），再在同名分支组里把副本逐字节复制到 core 与 ts；副本与源不等时两侧 parity 测试失败，因此副本不可能单独漂移。副本随引擎编译进制品，运行时不读 locales，不存在版本协商；`version` 字段只在格式变化时递增。同时删除 `locales/*/src/main/resources/overlays/*/type-inference-rules.json` 与 `LexiconPlugin.getOverlayResources()` 中的 `typeInferenceRules` 键、ts `BASE_NAMING_RULES` 与 `type-inference-rules.ts` 的内联常量（以及 `Lexicon.typeInferenceRules` 字段）、Java `TypeInference.NAMING_RULES`。

文件格式：`{"version":1, "default":"Text", "rules":[{ "type", "priority", "match", "words" | "strings" }]}`，`rules` 的数组顺序有意义（见下文第 3 步）。

**规则全表**（规范内容，按文件顺序；由现有 ts `BASE_NAMING_RULES` 与 en-US、zh-CN、de-DE 三份 overlay 逐条转写，顺序与 ts 现行合并顺序一致）：

| # | 来源 | type | priority | match | words / strings |
|---|---|---|---|---|---|
| 1 | 基线 | Text | 11 | lastWord | message, dosage |
| 2 | 基线 | Text | 10 | lastWordMulti | id, identifier |
| 3 | 基线 | Text | 8 | lastWord | code, key, token, uuid, guid, vin |
| 4 | 基线 | Float | 10 | lastWord | amount, price, cost, fee, total, balance, salary, income, payment, percentage, ratio |
| 5 | 基线 | Float | 9 | lastWord | rate, interest |
| 6 | 基线 | Int | 10 | lastWord | count, number, qty, quantity, score, level, rank, index, size, length, width, height, age |
| 7 | 基线 | Int | 9 | anyWord | year, years, month, months, week, weeks, day, days, hour, hours, minute, minutes, second, seconds |
| 8 | en-US | Bool | 11 | firstWord | is, has, can, should, was, will, did, does, allow, enable, disable, active, valid, require 这 14 个词本身，加上每个词各配 `s`、`es`、`d`、`ed` 四个后缀的形式，共 14 × 5 = 70 个词，在文件中逐个展开 |
| 9 | en-US | Bool | 8 | lastWord | flag, enabled, disabled, active, valid, approved, rejected, completed, confirmed, sufficient, success, passed, verified |
| 10 | en-US | DateTime | 10 | lastWord | date, time, at, timestamp, created, updated, modified, expired, birthday, anniversary |
| 11 | en-US | Text | 8 | lastWord | type, status, category, kind, mode |
| 12 | en-US | Text | 7 | lastWord | make, model, brand, manufacturer |
| 13 | en-US | Text | 7 | lastWord | name, title, description, comment, note, remark, address, email, phone, url, path, reason, recommendation, factors, purpose |
| 14 | en-US | Int | 9 | lastWord | rating, limit, premium, deductible, multiplier, deposit, line, utilization, inquiries, rent, debt, cards, value, payments |
| 15 | en-US | Int | 8 | lastWord | licensed, employed, job, experience |
| 16 | en-US | Int | 9 | lastWord | bps, apr, apy |
| 17 | en-US | Int | 8 | lastWord | checked |
| 18 | zh-CN | Bool | 11 | textPrefix | 是否, 有无, 能否, 可否, 允许, 启用, 禁用 |
| 19 | zh-CN | Bool | 8 | textSuffix | 批准, 通过, 有效, 合格, 可疑, 确认, 验证 |
| 20 | zh-CN | Int | 10 | textSuffix | 评分, 年龄, 数量, 次数, 额度, 金额, 保费, 免赔额, 账龄, 卡数 |
| 21 | zh-CN | Float | 9 | textSuffix | 利率, 费率, 比率, 百分比 |
| 22 | de-DE | Bool | 8 | textSuffix | genehmigt, berechtigt, verdaechtig, aktiviert, deaktiviert, aktiv, gueltig, erfolgreich, bestanden, bestaetigt, verifiziert, abgelehnt, abgeschlossen, erforderlich |

转写带来的语义变化只有三处，都是有意的：后缀 / 时间单位改为按整词匹配（`candidate`、`timeline`、`monthlyExpenses` 不再误中）；`snake_case` 名字的 `_` 也算词边界（`days_since_consent` 命中时间单位，`user_age` 命中 `age`）；`Age` 合并进第 6 行。

**匹配算法**（两引擎逐步相同；除第 1 步的切词式外不使用正则）：

1. **分词**。名字只含 ASCII 字母、数字与 `_` 时（标识符本来就不含 `-`）：先按 `_` 切段（空段丢弃），每段再从左到右反复取 `[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+` 的最左匹配切成词，所有词做 ASCII 小写。这个切词式不带任何标志、只用 ASCII 字符类与先行断言，在 Java 与 JS 中语义相同。例：`interestRateAPR` → `interest, rate, apr`；`userID` → `user, id`；`days_since_consent` → `days, since, consent`；`monthlyPremium` → `monthly, premium`；`pre_auth__Code` → `pre, auth, code`。名字含其它字符（中文、印地文、带变音符的德文等）时词列表为空，只有 `text*` 规则可能命中，例如 `是否成年`、`kundeGültig`（含 `ü`，`*Word*` 规则不看它；`textSuffix` 的 `gueltig` 也不命中，按缺省得 `Text`）。
2. **逐条判定**。`firstWord` / `lastWord`：首词 / 末词在 `words` 中；`anyWord`：任一词在 `words` 中；`lastWordMulti`：末词在 `words` 中且词数 ≥ 2（`userId` 命中，单独的 `id` 不命中）；`textPrefix` / `textSuffix`：对整个名字做 ASCII 小写（非 ASCII 字符不变）后，以 `strings` 中某一项开头 / 结尾。所有比较都是码点精确比较。词列表为空时，`*Word*` 规则一律不命中。
3. **取胜**。命中规则中 `priority` 最大者胜；同优先级取数组中靠前者。都不命中时取 `default`（`Text`）。
4. **与词法表无关**。全部规则对所有模块生效，不按模块的 lexicon 挑选 overlay。标识符的语言与关键词的语言无关（英文关键词的模块里可以有中文字段名，反之亦然），而三份 overlay 的词表互不相交，合并没有冲突。这同时消掉了 T1 那种「调用方传没传 lexicon」造成的分岔。
5. **约束修正**（只对推断类型生效，与约束的书写顺序无关）。先由约束得出 `c`：存在 `Range` 时，任一 `Range` 的任一非空端点（ADR 0055 §5 的规范十进制串）含 `.` 则 `c = Float`，否则 `c = Int`；不存在 `Range` 而存在 `Pattern` 时 `c = Text`；其余（只有 `Required` 或没有约束）`c` 为空。`Range` 优先于 `Pattern`，因为范围只对数值有意义。再与名字推断结果 `n` 合并：`c` 为空或 `c = n` 时取 `n`；`n = Text` 且 `c ∈ {Int, Float}` 时取 `c`；`n = Int` 且 `c = Float` 时取 `Float`；其余取 `n`。这与 ts 现行 `refineInferredType` 在单条约束、以及只有若干 `Range` 时结果相同（ts `inferTypeFromConstraints` 取第一个非空结果，之后只做 `Int → Float` 提升）。不同之处只在 `Range` 与 `Pattern` 混写：ts 现行结果依赖书写顺序，`Pattern` 在前得 `Text`、`Range` 在前得 `Int` / `Float`；规范规则两种顺序都得 `Int` / `Float`。语料中没有这种写法，测试向量须把两种顺序都列上。Java 现在不做约束修正，需要补上。

**共用测试向量**：locales 同目录放 `type-inference/test-vectors.json`（`[{ "name": …, "constraints": […], "expected": … }]`，`constraints` 用 IR v2 形状），两引擎单测逐条跑。必须覆盖：本节所有例子；§2.1 与 §3.2 离线模拟中出现的全部名字；每条规则至少一个命中与一个近似但不命中的名字；同优先级取前者；`Range` 与 `Pattern` 的各种组合与两种书写顺序；`Range` 只有一端非空的情况。

**离线模拟**（§1 模拟口径，en-US 样本的 1303 个推断形参 / 字段）：与「ts 带 en-US overlay」相比只变 2 处，即 `timeline` `Int → Text` 和 `days_since_consent` `Text → Int`，另有 4 处 lambda 形参 `candidate` `DateTime → Text`；与 Java 现状相比变 60 处：潜伏差异中的 54 处（`timeline` 在规范规则下与 Java 同为 `Text`，不变）、T2 的 `monthly_spend` 与 `monthlyExpenses`（`days_since_consent` 规范结果为 `Int`，与 Java 相同），以及 4 处 `candidate`。与 ts 默认路径（今天门禁测到的路径）相比，变化就是 T1 的 112 处加上述 6 处。

### 3.3 推断类型节点的 origin

- 推断返回类型（`Func.ret`、`Lambda.ret`）：origin 是 `produce` 关键词。若该关键词在当前词法表下由多个词组成，从首词起至末词止。源码省略 `produce` 时，取形参列表及其后可选逗号之后的第一个非布局 token。
- 推断的形参 / 字段类型：origin 是该形参 / 字段的**名字 token**（ts 现状）。
- 以上节点 `written` 与 `role` 恒为 `null`。

---

## 4. V2–V4 裁定

### 4.1 V2 能力推断

**实测**：44 / 55 处差异全部是「ts 填了预置、Java 为 `[]`」，`effectCapsExplicit` 两侧都是 `false`。例：`tier1-equivalence/policies/soc2-audit-demo.aster`，`$.decls[3].effectCaps`：ts `["Cpu"]` / Java `[]`；`stdlib_io.aster`，`$.decls[0].effectCaps`（`print`，`It performs io`）：ts `["Http","Sql","Time","Files","Secrets","AiModel"]` / Java `[]`。

**根因**：ts `src/parser/type-parser.ts:225–232`（`separateEffectsAndCaps` 的 else 分支）在没有显式能力时用 `parseLegacyCapability`（`src/effects/capabilities.ts:21–33`）展开 `io` / `cpu`。这张「io 预置」是硬编码的，而且与单一真相源 `shared/capabilities.json` 的 `class` 字段不一致：io 类能力里漏了 `Network`、`Process`、`Payment`、`Inventory`。Java `AstBuilder.java:228–236` 只记显式能力。

**对错**：Java 对。IR 记录的是源码语义，派生值不进 IR（ADR 0055 #8 删除 `piiLevel` 的同一理由）。`effectCapsExplicit = false` 本身已经说明「只声明了效应」，读方需要时自己展开。两侧检查器对非显式函数本来就跳过逐能力的声明检查（Java `CapabilityChecker.java:128–135`，ts `typecheck/capabilities.ts:15–16`）。

**规范规则**：`It performs` 子句列出至少一个能力时，`effectCaps` 为这些能力（源码顺序、按首次出现去重），`effectCapsExplicit = true`；否则 `effectCaps = []`、`effectCapsExplicit = false`。不需要新的数据表：能力名、调用前缀与 io/cpu 分类已经由 `shared/capabilities.json` 单源，并有 `CapabilityParityTest` 守护。

**随之对齐的检查器行为**（不在 IR 上，但因读的是同一字段，必须同一个 PR 改）：
- 工作流步骤的能力检查只在 `effectCapsExplicit` 为真时进行。ts `typecheck/effects.ts:84–85` 目前读到的是预置列表，Java `CapabilityChecker.java:181` 读到的是 `[]`。同一段「裸 `It performs io` + 步骤里调用 `Http.get`」的源码，按读码结论 Java 报 `WORKFLOW_UNDECLARED_CAPABILITY`、ts 不报。实施 PR 先补一个 tier1 样本证实，再统一为「不报」。
- 范围外，单列 issue：ts `typecheck/module.ts:229–236` 在 manifest 检查里把裸 `io` 展开为预置能力，Java `checkManifestConstraints` 只查「显式声明 ∪ 函数体用到的」能力；manifest 文件里 `io` / `cpu` 键的展开（ts `capabilities.ts:44–74`）Java `ManifestConfig` 不支持。这些是诊断分岔，不影响 IR，按「显式声明 ∪ 实际用到」与「按 `capabilities.json` 的 class 展开」两条统一。

**改动**：只改 ts（删掉 else 分支与 `effects.ts` 的读法），约 0.5 天。

### 4.2 V3 闭包捕获

**实测**：门禁 0 处；全语料 1 处，`tier3-fixtures/type-checker/lambda_with_effects.aster`，`$.decls[1].body.statements[0].expr.captures`：ts `[]` / Java `["get","io"]`。lambda 体是 `Return io.get(endpoint).`。

**根因**：两侧的 lambda 体 IR 相同，都是 `Call(target=Name "get", args=[Name "io", Name "endpoint"])`，即方法调用被脱糖为「方法名作函数、接收者作首参」。差别在于捕获是在**哪一层**算的：Java `CoreLowering.collectCaptures`（`:775` 起）在脱糖之后的 AST 上收集，`get` 与 `io` 都是不带点的 `Name`、在 lambda 内未绑定，于是被收进去；ts `lower_to_core.ts:586–665` 在脱糖之前的 AST 上收集，看到的是带点的 `io.get`，按「含 `.` 不收」跳过。两侧共同的问题是判据只有「在 lambda 内未绑定」，没有要求「在 lambda 外绑定为局部」。运算符在 AST 中也是 `Name`（`plus` → `+`），所以两侧都会把运算符名、模块内规则名收进 captures：全语料 23 个 lambda 中有 9 个文件、11 处捕获了 `>`、`+`、`*`（例如 `lambda_cnl_match_bind.aster`，`$.decls[2].body.statements[0].expr.captures` 两侧都是 `[">"]`）；ts 对「lambda 内 `Return helper(y) plus k.`」实测得 `["+","helper","k"]`，Java 读码可知结果相同（`collectCaptures` 同样访问调用目标）。这些不产生跨引擎差异，所以门禁看不到。

**对错**：这一处 ts 的结果对，但两侧判据都不对。ADR 0055 V3 的收紧条件是「只列引用到的外层名」。`get` 是方法名，`io` 是效应关键词，`>` 是运算符，`helper` 是模块声明，都不是外层名字。

**规范规则**：名字 `n` 进入某个 lambda `L` 的 `captures`，当且仅当：
1. `n` 作为 `Name` 节点（值位置或调用目标）出现在 `L` 体内，包括 `L` 内嵌套 lambda 的体内；
2. 在该出现处，`n` 没有被 `L` 内部的绑定遮蔽（`L` 的形参、`L` 体内在此之前的 `Let`、`Match` 分支的模式绑定、嵌套 lambda 的形参）；
3. 按 R1 名称解析，`n` 解析到 `L` **外层的局部绑定**：外层函数的形参、外层块里在 `L` 之前的 `Let`、外层 `Match` 的模式绑定，或外层 lambda 的形参。解析到模块声明、导入、内建函数或效应名的不算。

判定在**方法调用脱糖之后**进行（两侧的 lambda 体 IR 本来就相同，以 IR 层的 `Name` 为准，不依赖解析器是否带点）。顺序按**首次引用的源码位置**（行、列）升序，去重。`L` 本身是嵌套 lambda 时，外层 lambda 的 captures 也要包含内层穿透引用的外层名（两侧现有行为）。

**排序的选择**：两侧现在都按插入顺序（`LinkedHashSet` / `Set`），在单个样本上已经一致。改用源码位置是因为它不依赖两侧 visitor 遍历子节点的先后，更不容易随重构漂移。不选字典序：Truffle `Loader` 按 captures 的位置建帧槽、传实参，任何确定的顺序都可以，源码顺序读起来更直观。

**改动**：Java `collectCaptures` 移到脱糖后的同一位置并加判据 3（读 R1 的 `resolved`）；ts 同样加判据 3，并改为对 IR 层的 Name 判定。上述 11 处运算符捕获会从两侧同时消失（门禁仍 identical，但 IR 与 Truffle 帧布局变化）。估计 1 天，主要是补测试。先把 `lambda_with_effects` 的写法与「lambda 内调用模块规则」各做一个 tier1 样本，与修复放进同一组分支。

### 4.3 V4 origin 行号

**实测**：门禁 16 处、全语料 17 处，全部是 lambda 推断返回类型的 `origin.end.line`。例：`lambda_cnl_match_bind.aster`，`$.decls[1].body.statements[0].expr.ret.origin.end.line`：ts `6` / Java `9`（Java 算到 lambda 体末行）。另有 683 处同类差异落在推断的 `Func.ret` 上，今天被 V1 整棵子树豁免遮住。ADR 0055 记录的「声明尾部 15 处」在当前头部为 0：Java `AstBuilder.spanFrom` 已改为跳过尾随布局 token（`lastNonLayoutToken`，`:1789` 起）。多行续行在语料中有 11 个文件（例如 `hipaa-validation-demo.aster` 的多行 `Define`），两侧结束行一致。显式 `TypeName` 的结束行没有差异。

**规范规则**：
1. 每个由源码 token 构成的节点，`origin.start` 是属于该节点的第一个非布局 token 的起点，`origin.end` 是属于该节点的最后一个非布局 token 的终点。布局 token（NEWLINE / INDENT / DEDENT）与注释不属于任何节点。节点跨行（续行）时，续行上属于它的 token 照算。这就是两侧现行的规则，写下来作为契约。
2. 推断出来的类型节点没有自己的 token，按 §3.3 锚定。

**改动**：Java `AstBuilder` 中推断 `Func.ret` 与 `Lambda.ret` 的 span 改为 `produce` token；ts lambda 的锚点从 `:` 改为 `produce`（同一行，只影响列，今天不进门禁）。与 V1-ret 同一个 PR。

---

## 5. 影响与风险（IR 之外）

| 变化 | 读方 | 影响 | 验证 |
|---|---|---|---|
| Java 推断返回类型变为 `Unknown` | Java 类型检查（已跳过占位）、R2（已跳过）、aster-api | aster-api 的返回类型元数据不读 IR：`PolicyCompiler.java:175` 构造 `CompilationMetadata` 时返回类型恒为 `null`，`PolicyEvaluationService.java:949` 再回落为字符串 `"unknown"`，所以 API 响应不变。cloud 若直接展示 IR 的 `Func.ret`，按 `retTypeInferred` 显示为「推断」 | core golden 重新生成；eval 门 |
| 推断类型表统一 | 两侧类型检查把推断形参当未检查 / 动态（Java `TypeChecker.java:274`、ts `typecheck/module.ts:266` 带 `typeInferred` 登记）；aster-api `PolicyEvaluationService.extractParametersFromCoreJson`（`:1001`）把 IR 形参的 `type` 子树原样作为 `ParameterInfo.type` 返回；ts `browser/schema.ts` 与 cloud 的输入表单 / 示例生成读推断类型 | Java 约 60 处推断类型改变；ts 相对带 en-US overlay 的路径约 6 处，相对默认路径另有 T1 的 112 处。API 契约不变（字段与形状不变，`type` 仍是 IR 类型子树，取值随规范规则变化，不新增 `typeInferred`）。若某条检查仍对推断类型做运算符检查，可能出现新诊断；cloud 生成的示例输入类型随之改变 | 全语料两侧类型检查诊断码集合前后对比，变化逐项登记；aster-api 加一例未标注形参的 `ParameterInfo.type` 断言（随 pin bump）；eval 门；ts `schema` 单测 |
| ts 不再填能力预置 | ts 工作流能力检查（改为只查显式）、stability gate（只看显式，无变化） | 裸 `io` 函数的工作流步骤不再按预置判定 | 新增 tier1 样本覆盖工作流 + 裸 `io` + `Http` 调用；两侧诊断一致 |
| captures 不再收非局部名 | Truffle `Loader` 按 captures 建帧槽 | 被收进 captures 的运算符名（语料 11 处）、模块规则名 / 内建名不再占槽，Truffle 在 lambda 内必须按全局查找它们。现有路径对「未捕获的全局名」是否正确回落需在 truffle PR 中确认 | truffle 单测 + eval 门（含 lambda 调用模块规则的新样本） |
| origin 锚点 | ADR 0037 contentHash 不含 origin，无影响；LSP / trace 定位 | 推断占位定位到 `produce` 而非整条声明 | parity 门 |

parity 与测试的走向：每个类别修完，门禁对该类别报 `stale-exemption`，迫使同一组分支里的 aster-lang-test PR 删除该类别（V4 按登记项删除）。core / ts 中断言旧推断值的单测与 golden 随实现 PR 更新；aster-lang-test 的 eval golden 不受影响（推断值不改变求值结果），若有变化即视为缺陷。

---

## 6. 实施计划（大纲）

沿用 ADR 0055 的列车做法：引擎仓与 aster-lang-test 用同名分支组 `feat/inference-parity`，CI 同名分支优先；每个 PR 都要过 `parity-tier1 --mode=ir --full`、`--mode=eval`、`test-eval-guard` 与 `tag-eval-exempt --check`。

| 顺序 | 仓 | 内容 | 门槛 |
|---|---|---|---|
| 1 | aster-lang-ts | **V2**：删除 `separateEffectsAndCaps` 的预置分支；工作流能力检查只看显式能力。aster-lang-test 同分支：删除 V2 条目，补「工作流 + 裸 io」样本 | V2 计数 0，`stale-exemption` 迫使删条目；两侧该样本诊断一致 |
| 2 | aster-lang-core（+ ts 小改） | **V1-ret + V4**：推断返回类型改为 `Unknown`，删除 `inferReturnTypeName`；`Func` / `Lambda` 占位锚定 `produce`；推断形参 / 字段类型的 origin 改为名字 token。ts 改 lambda 锚点。aster-lang-test：删除全部 V4 条目 | V4 条目全部 stale 后删除；V1 计数降到约 101（门禁）/ 116（全语料） |
| 3 | aster-lang-locales | 新增 `type-inference/naming-rules.json` 与 `test-vectors.json`（由三份 overlay + ts BASE 转写），附 JSON Schema；删除 `overlays/*/type-inference-rules.json` 与 plugin 注册 | locales 单测校验向量自洽；先于第 4 步合并 |
| 4 | aster-lang-core + aster-lang-ts | **V1-类型**：两侧实现 §3.2 算法（分词 + 词表 + 约束修正），读各自副本，加 sibling parity 测试与共用向量测试；删除 Java `TypeInference.NAMING_RULES`、ts `BASE_NAMING_RULES` 与内联 overlay；ts 默认路径不再依赖 lexicon。aster-lang-test：删除 V1 条目与 `V1_FILES` | V1 计数 0；两侧类型检查诊断前后对比无未登记变化 |
| 5 | aster-lang-core + aster-lang-ts + aster-lang-truffle | **V3**：先在 aster-lang-test 同分支加 tier1 样本（`lambda_with_effects` 写法、lambda 调用模块规则、嵌套 lambda 穿透），再按 §4.2 改两侧捕获；truffle 确认全局名回落 | 新样本两侧 captures 相同；eval 门含新样本；V3 未登记过豁免，所以以「新样本 identical」为准 |
| 6 | aster-lang-test | 确认 `VALUE_EXEMPTIONS` 为空数组；关闭 #195–#198 | ADR 0055 go-live 条件 |
| 7 | 发布 | platform 版本计划与生态 pin bump（下一班列车）；aster-api 元数据测试随 pin bump 更新 | 列车既有门槛 |

工作量粗估：V2 0.5 天；V1-ret + V4 1 天（主要是 core golden 重生成）；locales 数据转写 0.5–1 天；V1-类型两侧各约 1 天；V3 1 天（含 truffle 确认）。合计约 5–6 个工作日，可在 2027-01-31 截止日前完成。

顺序理由：V2 只改 ts 一处，最快清掉一个类别；V1-ret 与 V4 共用 Java `AstBuilder` 的同一处代码，一次清掉 553 + 16 处；V1-类型跨三个仓，需要 locales 先合并；V3 当前门禁为 0，不阻塞清表，但要先补样本，否则门禁看不到它。

---

## 7. 用户确认（2026-11-14：五项均取缺省）

以下五项原为开放决定，每项都给出了本 ADR 推荐的缺省方案，§3–§4 按缺省方案书写。**2026-11-14 用户确认全部采用缺省方案**，§6 各步骤据此实施。

1. **推断规则与词法表无关**（§3.2 第 4 步）：全部 overlay 规则对所有模块生效。备选：沿用 ts 现状，按模块 lexicon 只挂对应 overlay（Java 需要在 `AstBuilder` 拿到 lexicon id）。默认：与词法表无关。
2. **分词 + 词表取代正则**（§3.2）：会改变少量现有推断，`candidate` 由 `DateTime` 变为 `Text`，`timeline` 由 `Int` 变为 `Text`，`days_since_consent` 由 `Text` 变为 `Int`。备选：保留正则，限定为两引擎同义的子集（不用 `i` 标志，大小写写成显式字符类）。默认：分词 + 词表。
3. **规则表内容以 ts en-US overlay（即 locales JSON）为准**：`value`、`limit`、`debt`、`line` 等推断为 `Int` 的规则保留，Java 侧随之改变 54 处。备选：删掉这些争议条目，例如裸 `value` 推为 `Int` 并不自然。默认：保留，内容整理另开 issue。
4. **推断返回类型只用占位 `Unknown`**，不把函数体推断写进 IR。备选：两引擎统一一套函数体返回类型推断并写入 IR，代价是要先统一两侧类型检查器的推断算法，远超本 ADR 范围。默认：占位。
5. **captures 只收外层局部名，按源码位置排序**（§4.2）：lambda 内引用的模块规则名不再进 captures，需 truffle 确认全局名回落。默认：如此。

## 8. 关联

- aster-cloud/aster-lang-test#195、#196、#197、#198；ADR 0055 §4 #7、#9、#10、#14、#17，§6，§7
- aster-lang-core `src/main/java/aster/core/parser/AstBuilder.java`、`src/main/java/aster/core/inference/TypeInference.java`、`src/main/java/aster/core/lowering/CoreLowering.java`、`src/main/java/aster/core/typecheck/checkers/CapabilityChecker.java`
- aster-lang-ts `src/parser/type-inference.ts`、`src/config/lexicons/type-inference-rules.ts`、`src/compile-pipeline.ts`、`src/parser.ts`、`src/parser/decl-parser.ts`、`src/parser/expr-stmt-parser.ts`、`src/parser/type-parser.ts`、`src/typecheck/effects.ts`、`src/lower_to_core.ts`、`shared/capabilities.json`
- aster-lang-locales `locales/*/src/main/resources/overlays/*/type-inference-rules.json`
- aster-lang-test `scripts/parity-tier1.mjs`（`VALUE_EXEMPTIONS`）
