# ADR 0060：汉字单词关键词别名与策略内嵌词汇（《静夜思》原诗即策略）

- 状态：**ACCEPTED**（2026-11-20，产品负责人采纳，§10 的开放问题按推荐值裁决）
- 提出：2026-11-20；来源 wontlost-ltd/aster-cloud#830 及其「产品决策（2026-11-20）」；前序 cloud#791、#829
- 决策者：产品负责人已拍板两项（§3）；本 ADR 补齐威胁模型、规则细节、存储、合并、envelope、执行与分期
- 相关：ADR 0022（关键词别名，§10「用户空间铁律」、§11.5 Codex 对抗复核 H3）、ADR 0051（按位置解析词汇；§7 envelope 第 5 段）、ADR 0053 及修订 1（发布列车拥有 aster-api 的引擎 pin）、ADR 0058（结构化编写能力与 LayoutMap）、ADR 0059（可执行策略导出包）
- 修订：本 ADR **修订 ADR 0022 §11.5(A) 的「仅多词」规则**（§5）与 **ADR 0022 的结构词白名单**（加入 `FUNC_PRODUCE`）；其余 ADR 0022 规则不变。不修订 ADR 0058（LayoutMap 规则原样适用）
- 被修订：[修订 1 字面量触发词与关键词同形时关键词优先；字面量内容放开 `\`、`"` 与弯引号](0060-amendment-1-literal-trigger-keyword-precedence.md)（ACCEPTED，2026-11-20；R16' 取代 R16，新增 R18）；[修订 2 名字转义与别名、E808、字面量触发词](0060-amendment-2-name-escape-aliases-literals.md)（PROPOSED，2026-11-22，随 [ADR 0064](0064-name-escape.md)；X1 E808 跳过转义名、X2 别名与词汇不得含反引号、X3 转义的触发词拼写是名字、X4 转义不解决触发词与关键词的冲突，R16' 不变）
- 实现仓：`aster-lang-core`（别名校验器，**列车门**）、`aster-lang-ts`（别名校验器移植，**列车门**）、`aster-lang-test`（envelope 共享向量）、`aster-api`（批量 What-If 补别名；列车后的端到端测试）、`aster-cloud`（主：内嵌词汇存储、合并、编辑器、模板）
- 实施计划：`docs/superpowers/plans/2026-11-20-adr0060-jingyesi-embedded-vocabulary.md`

---

## 0. TL;DR

| 主题 | 决定（一句话） |
|---|---|
| 为什么是新 ADR | 改的是 ADR 0022 的安全规则与引擎校验器，并新增一类编译输入；ADR 0058 讲能力授权与显示排版，二者都不改。放进 0058 的修订会把引擎规则藏在一份 cloud UI 的 ADR 里 |
| 汉字单词别名 | 不含空格的别名，当且仅当 locale 为 `zh-*`、**每个字符都是汉字**（U+4E00–9FFF 或 U+3400–4DBF）、长 2–8 个字时允许；拉丁、天城文等单词别名仍拒绝 |
| `FUNC_PRODUCE` | 加入**结构词**白名单，需结构词别名授权（与 `FUNC_TO` 同类：函数签名关键词） |
| 新增碰撞规则 | 别名不得等于任何字面量宏触发词（今天 ts 与 core 在这种情况下结果不同，§4.2 T3）；带汉字单词别名时，解析后任何名字不得折叠成被别名化 kind 的英文拼写（IR 名字守卫，防软关键字把同形名字静默改名，§5.3） |
| 校验器版本 | core / ts / cloud 的 `USER_ALIAS_VALIDATOR_VERSION` 一起从 `"1"` 升到 `"2"`；新版本的 toolchainId 随之变化，旧版本的 envelope 不受影响（各自存了 `sourceToolchainId`） |
| 列车门 | core 与 ts 的校验器改动必须随列车发布（eco-1.0.52）并由列车 bump aster-api pin；cloud 放宽在 aster-api 1.0.52 上线后才合 |
| 内嵌词汇存储 | cloud `PolicyVersion` 新列 `embeddedVocabulary text NULL`，存确定性序列化的 JSON；与 `aliasSet` 同生命周期：建版本时写入，之后不改 |
| 支持的种类 | `struct`、`field`、`function`、`enum_value`、`literal`（**字面量宏只在内嵌词汇里有**，用户 / 团队词汇不加） |
| 合并优先级 | 内嵌 > 团队 > 作者（与 `mergeAliasSets` 的 policy > team > tenant 同向）；下层词条与内嵌词条冲突时整条丢弃并在保存响应里列出 |
| envelope | **不加段**。内嵌词条并入有效词汇后进入第 5 段 `scopesJson`；两侧 envelope 算法与既有向量逐字节不变，只新增一条含字面量宏的向量 |
| 冻结 | 内嵌词汇在建版本时就属于版本行，不需要审批快照；审批时的一致性复核改为比较「冻结快照 + 内嵌」与封印的 `sourceScopesJson` |
| 执行 / 导出 / 回放 | 所有读版本编译输入的路径都经同一个合并函数；aster-api 只看到合并后的 `DomainVocabulary`，**无需改协议**（core 已支持 `literals`） |
| 门禁 | 内嵌词汇是编译输入，保存**不设能力门**（同 `aliasSet`）；编辑入口在「词汇」标签页（随 `structured_authoring`）；上限 32 条 |
| 模板 | 模板只写内嵌词汇，永不写用户词汇；#829 的模板换成原诗：zh-CN + 四个结构词别名 + 两条内嵌词条 + LayoutMap，运行输出 `静夜思` |

---

## 1. 现状实证（aster-api `ae8e4bc8`、aster-cloud `f3877466`、aster-lang-core `d9b7254`、aster-lang-ts `9f2268f`，均为 `origin/main`）

### 1.1 别名校验在四处，规则相同

| 位置 | 规则 | 出处 |
|---|---|---|
| core `UserAliasValidator`（aster-api 自 ADR 0059 P2 A6 起直接用它） | 运算符 11 个恒允许；结构词 7 个（`MODULE_DECL`、`FUNC_TO`、`IF`、`OTHERWISE`、`MATCH`、`WHEN`、`RETURN`）需 `allowStructural`；其余恒拒；`if (norm.indexOf(' ') < 0)` 即拒「必须是多词短语」；不遮蔽规范拼写与官方别名；跨 kind 不重复；不撞词汇写法（**字面量宏除外**：「字面量宏不算标识符，不参与」）；`VERSION = "1"` 且注释要求规则变更时必须 bump | `src/main/java/aster/core/embed/UserAliasValidator.java:42,64-76,85-93,210-214,234-243`；aster-api 引用 `replay/src/main/java/io/aster/policy/parser/InProcessCnlParser.java:162`、`CompileIdentity.java:37`；A6 = aster-api#579（`9f4b30af`） |
| ts `alias-validate.ts`（包导出与 Node 重编译用的逐行移植） | 同上；`if (!norm.includes(' '))` | `aster-lang-ts src/policy-package/alias-validate.ts:18,138`；入口 `src/policy-package/compile-source.ts:81-96` |
| cloud `policy-alias-shared.ts`（前端预校验 + 服务端权威校验） | 同上；`singleWord` 码 | `src/lib/policy-alias-shared.ts:16-50,228`；服务端唯一调用点 `src/services/policy/version-manager.ts:543`；前端 `src/components/policy/policy-alias-panel.tsx:58`、`src/components/policy/policy-form/index.tsx:665` |
| aster-api 自己的入口 | 编译端点 `allowStructural = 请求值 && HMAC 已验证`；`evaluate-source` 的 `aliasesTrusted = HMAC 已验证`；**每次执行都按当前校验器重新校验冻结的 aliasSet** | `src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java:981-982,632-646`；`InProcessCnlParser.java:158-167` |

`FUNC_PRODUCE` 不在任何一档：既不在运算符也不在结构词集合里，所以即使有结构词授权也被拒。aster-api 测试里没有任何针对 `FUNC_PRODUCE` 别名的用例（只出现在错误本地化测试）；单词拒绝由 `UserAliasValidatorTest.java:51-58`（`TIMES → scaled`）与 `:222-229`（`RETURN → answer`，已授权仍拒）钉住。

### 1.2 两条规则的来历

- **「仅多词」**：ADR 0022 §10「自审关键修正（用户空间铁律）」（`docs/adr/0022-keyword-aliases.md:141`）：首批 11 个英文**单词**别名（Policy / Whenever / above …）机检不撞规范拼写，但实测 15/15 个候选词作字段 / 参数名本来合法，加别名后被当关键词、编译失败——「破坏用户空间」。根因是解析器在这些位置优先认关键词。修正为只收多词，因为「仅相邻序列匹配，子词仍可作标识符」。§11.5(A)（`:203`）把它定为硬约束，并写明「单词别名需位置感知识别才放开」。
- **结构词与 H3**：ADR 0022 §11.5(B) 第 3 条（`:210`），Codex 对抗复核：别名映射到哪个 kind 是用户定的，用户可把 `approve` 别名成 `RETURN`，「审批者看别名源码、实际批准归一后语义」。当时的结论是只放开运算符。后来 aster-api#97 / cloud#162（2026-07-03）在三重护栏下放开 7 个结构词：①多词 ②审批 UI 显示规范化结构 ③审计留痕，且只对管理员按用户授予的人开放（`policy-alias-shared.ts:33-41` 的注释）。`FUNC_PRODUCE` 当时没有进入「声明 + 控制流 + 返回」这个分类，找不到刻意排除它的记录。

两条规则针对的是**不同**的威胁：「仅多词」防标识符命名空间被占；结构词白名单与授权防审批欺骗（H3）。本 ADR 分开处理（§4）。

### 1.3 引擎怎样匹配汉字别名：整词，不是子串

- **ts**：先词法分析、再逐 token 翻译关键词（`src/compile-pipeline.ts:41-46`；`src/frontend/keyword-translator.ts:283` 起按整个 IDENT 的值查索引；用户别名在 `:247-274` 入索引）。zh 模式下一个 token 是汉字（U+4E00–9FFF、U+3400–4DBF）、ASCII 字母数字与 `_` 的最长连续串（`src/frontend/lexer.ts:91`）。`低头思故乡`、`抬低头` 都是一个 IDENT，`低头` 不会在其中被匹配。
- **core**：在 ANTLR 之前按正则翻译文本，但对每处命中做边界检查：前一字符不得是标识符字符或无空格文字，zh 模式下后一字符不得是标识符字符（`aster.core.canonicalizer.Canonicalizer.java:1029-1085,1101-1135`；`isIdentifierChar` 在 `:1142-1185`，含汉字、假名、谚文与组合符号）。用户别名在 zh 第 4 步入翻译表（`:544-551`）。
- 两侧的边界只在假名、谚文、汉字扩展 B 上不同：core 视为标识符字符，ts 的 zh 词法不视为。**只取汉字基本区与扩展 A** 正是两侧一致的交集。
- core 其余部分不假定别名含空格：`Lexicon.getMultiWordKeywords`（`lexicon/Lexicon.java:135-160`）只是不把单词放进多词表；`AliasOverlayLexicon.wrap` 只合并；`LexiconValidator`（`:126-153`）没有单词规则。ts 示例 `examples/jingyesi/jingyesi.mjs`（绕过校验器直接造 lexicon）编译并求值得到 `静夜思`。**放宽校验器即可，无需改词法或规范化。**

### 1.4 字面量宏：两个引擎都有，cloud 存储没有

- 引擎：ts `IdentifierKind.LITERAL`（`src/config/lexicons/identifiers/types.ts:27-37`，`DomainVocabulary.literals` 在 `:95`）；core `VocabularyScopes.literals()`（`aster/core/identifier/VocabularyScopes.java:32,97,144-150`），`VocabularyLoader` 读 JSON 的 `literals`（`VocabularyLoader.java:157`）。内容校验两侧一致：非空、单行、无控制字符、无 `"` `\` 与各类引号（ts `types.ts:210-235`；core `IdentifierMapping.java:105-135`）。触发词须全局唯一（ts `types.ts:272-281`；core `DomainVocabulary.java:155-165`）。
- core#287（`7f72f73`）/ ts#361（`9284a60`）：引号归一只改定界符；宏值中的 `\` 与 `"` 改为转义后展开（之前 core 拒绝、ts 原样拼接）。注册时的校验仍拒这两个字符，转义只对未经校验的冻结词汇有意义。
- aster-api：编译端点、`evaluate-source`、包导出、Guard 部署都接受带 `literals` 的 `DomainVocabulary` JSON（`ReplayExecutionCore.java:148-160`；上限 `replay/src/main/java/io/aster/policy/rest/model/VocabularyBounds.java:20-36`）。`CompileIdentity.hasEntries` 把只有字面量的词汇也算作有词汇（`CompileIdentity.java:46-63`；ADR 0051 R37）。
- cloud：`DomainTerm.kind` 的 CHECK 只允许 `struct / field / function / enum_value`（`src/db/schema.ts:2538-2543`），路由、批量导入、组装器同样（`src/lib/domain-vocabulary-assemble.ts:21,49-62,104-114`）。但 `vocabularyScopesJson` 与 `mergeVocabularies` 已经处理 `literals`（`src/lib/policy-alias.ts:68-78`、`src/lib/domain-vocabulary-scope.ts:225-246`），编辑器「词汇」标签页也已有 literal 分组（`src/components/policy/policy-form/vocabulary-tab.tsx:39-45`），只是永远是空的。

### 1.5 cloud 的词汇进编译路径的方式

- 保存：`loadSaveVocabulary` 只加载一次作者现行词汇，同一份既给编译门禁（`makeCompileValidator` 以 `DomainVocabulary` 对象发给 `POST /api/v1/policies/compile`）又算 envelope 第 5 段（`src/lib/policy-compile-validator.ts:60-94,119-135`）。作用域：同语种、且源码里出现过该组的某个写法（`domain-vocabulary-scope.ts:49-52,109-134`）；团队策略为团队 + 未被遮蔽的作者词条（`domain-vocabulary-team-merge.ts:44-63`，团队优先，冲突的作者词条整条丢弃）。
- 封印：`PolicyVersion.aliasSet`、`sourceEnvelopeSha256`、`sourceToolchainId`、`sourceScopesJson`（`schema.ts:745-754`），在 `createVersion`（`version-manager.ts:584-601`）写入。
- 冻结：只在审批通过时把作者 / 团队词汇写成快照 `vocabularySnapshotIds`（`schema.ts:741-744`），写前比较 `vocabularyScopesJson(冻结) === sourceScopesJson`，不等则抛 `VocabularyChangedSinceSaveError`（`version-manager.ts:144-178,783-806`）。
- 执行：APPROVED / DEPRECATED 读冻结快照，其他状态读作者现行词汇（`src/lib/execution-input/index.ts:55-57,105-155,173-180`）；执行、校验一致性、回归、脱敏日志、Cedar、包导出、Guard 部署、内部 What-If 回源（`src/app/api/internal/policy-versions/route.ts:104-131`）都经这两个函数；resolver 迁移另有一处（`src/services/policy/resolver-migration.ts:257-259`）。
- envelope：`computeSourceEnvelope(content, aliasSetJson, locale, toolchainId, scopesJson)`，两侧逐字节一致（cloud `policy-alias.ts:106-114`；aster-api `PolicyVersion.java:453-456`；共享向量 `src/test/resources/shared-vectors/envelope-vectors.json`，3 条，**没有一条含字面量宏**）。
- 导出包（ADR 0059）：载荷 `policy/compile/vocabulary.json` 就是 cloud 发来的合并词汇（`PackageAssembler.java:89-93`），manifest 不单列 scopes。
- 批量 What-If：aster-api 回源拿到 `content` 与 `vocabulary`，但 `PolicyVersionSourceClient.VersionSource` 没有 `aliasSet` 字段，回放时传空别名（`ReplayBatchService.java:896-902`、`PolicyVersionSourceClient.java:61-62`）。**今天任何带别名的策略在批量 What-If 下都编译不过**；cloud 回源接口其实已返回 `aliasSet`。

### 1.6 现有模板与演示

- 演示 `/zh/demos/poem`：自定义 lexicon `jingyesi-zh`，四个单字别名，内联词汇（`望明月` → struct `Text`，`思故乡` → 字面量 `静夜思`），`JINGYESI_LAYOUT` 让显示为原诗（`src/config/poem-demo.ts:187-235`）。源码：

  ```
  床前 明月光。
  疑是 地上霜，举头 望明月：
    低头 思故乡。
  ```
- #829 模板 `showcase-jingyesi`（`src/data/policy-example-showcase.ts`）按今天的规则**重写**了诗：只用运算符别名 `接 着`，不含词汇（文件头 `:15-17`：模板不得替用户写词条）。产品负责人认为它不满足「原诗即源码」。
- `PolicyExample` 有 `aliasSet` 与 `layout` 字段，没有词汇字段（`src/data/policy-examples.ts:59-65`）。

---

## 2. 目标与非目标

**目标**

1. 《静夜思》原诗（§1.6 的源码 + LayoutMap）能作为真实策略保存、审批、执行，输出 `静夜思`；在 ts 与 Java core（经 aster-api）两侧编译出相同 Core IR、求值结果相同。
2. 放宽别名规则，同时保住 ADR 0022 两条规则的安全意图。
3. 策略版本可携带自己的词汇（含字面量宏），像 `aliasSet` 一样随版本冻结；模板不写用户词汇。

**非目标**：用户 / 团队词汇增加字面量种类（§10 Q2）；拉丁、天城文、假名、谚文的单词别名；`FUNC_GIVEN`、`LET`、`BE` 等其他 kind 的可别名化（de / hi 演示用到，但不在本次产品决策内）；Guard 部署放开结构词别名（今天 `PolicyDeploymentService.java:118` 固定 `allowStructural=false`）；租户 / 团队层别名（ADR 0022 Phase B/C，见 §4.2 的复审条件）。

---

## 3. 已定产品决策（2026-11-20，见 cloud#830）

| # | 决策 | 本 ADR 的落点 |
|---|---|---|
| P1 | 放宽关键词别名规则：允许汉字等 CJK 单词别名；`FUNC_PRODUCE` 可别名；结构词仍需结构词别名授权 | §4、§5 |
| P2 | 策略内嵌词汇：版本携带自己的词条（含新的 `literal` 种类），像 aliasSet 一样随版本冻结；模板不写用户词汇 | §6、§7 |

---

## 4. 威胁模型

### 4.1 资产、参与者、信任边界

| 资产 | 含义 |
|---|---|
| A1 审批真实性 | 审批者批准的就是将要执行的语义（ADR 0022 H3） |
| A2 用户空间 | 不用别名时合法的标识符，加了别名后仍按原意解析，或者至少编译失败，**绝不静默改义** |
| A3 隔离 | 一个策略的别名 / 词汇不影响别的策略、别的用户、别的租户 |
| A4 引擎一致 | 同一编译输入在 ts 与 core 得到同一 Core IR（ADR 0022 §6 parity、ADR 0059 E739） |
| A5 编译身份 | envelope 覆盖完整编译输入；在数据库可信的前提下，任一编译输入列与封印不一致都能被发现（不抵御同时改写多列的库写权限者，见下表） |

| 参与者 | 能力 |
|---|---|
| 作者（无结构词授权） | 可提交运算符别名、内嵌词汇 |
| 作者（有结构词授权） | 另可提交结构词别名 |
| 直连调用者（API key、trial） | aster-api 视为不可信：`allowStructural=false`，不进回放捕获 |
| 评审 / 审批者 | 读版本详情：源码、别名规范映射（`src/components/policy/version-detail-panel.tsx:309-314`）、LayoutMap（canonical 优先，ADR 0058 §7.7） |
| 有库写权限的人 | 超出本模型；一致性检测只能发现单列篡改（同 ADR 0058 §7.2） |

信任边界不变：别名与词汇的权威校验在服务端（cloud `createVersion` + aster-api 编译门禁）；冻结版本「冻结即信任」，但 aster-api 每次执行仍按当前校验器复验（§1.1）。

### 4.2 威胁与控制

| # | 威胁 | 今天的控制 | 放宽后的控制 |
|---|---|---|---|
| T1 | **审批欺骗（H3）**：用像「批准」「拒绝」的词作结构词别名，让审批者误读结构 | kind 白名单（逻辑、效果、导入、布尔永不可别名）；结构词需管理员授权；版本详情显示别名→规范映射并标出结构词；审计 | **不变**。单字别名不比多词别名更危险：`approve it` → `RETURN` 今天就合法。`FUNC_PRODUCE` 只引出返回类型，类型名本身仍在内容中可见，它改不了控制流，归入结构词档（需授权） |
| T2 | **标识符命名空间被占 / 同形名字被静默改名**（「仅多词」的理由） | 只收多词 | 拉丁等有空格的文字**维持只收多词**（ADR 0022 §10 的 15/15 实证仍成立）。汉字单词放开，理由与约束：①两引擎都按整词匹配（§1.3），`低头` 不会吃掉 `低头思故乡`；②别名只属于一个版本（`aliasSet` 版本局部，没有团队 / 租户层），被占的命名空间只在作者自己这条策略里；③撞上源码里的标识符时**不能**指望编译失败：结构词在所有标识符位置都是软关键字（core `AsterParser.g4:466-500` 的 `structKeywordName`，用于名字声明、变量引用、成员、构造字段、类型别名名、导入别名；ts `parser/expr-stmt-parser.ts:1313-1332` 对齐），把参数 `低头` 别名为 `RETURN` 会让它被一致地改名为 `return` 而照常编译——字段名变了，输出记录的键也随之改变，这正是 A2 要防的静默改义。因此新增引擎侧的 **IR 名字守卫**（§5.3 第 2 条）：解析后任何名字折叠后等于「被汉字单词别名化的 kind」的规范拼写即拒绝；双引擎实现，按位置矩阵测试；④仍禁撞规范拼写、官方别名、词汇写法（含内嵌词汇与字面量触发词）；⑤长度 2–8 个汉字，排除单字（单字最容易撞日常标识符，且诗与政策写作都不需要） |
| T3 | **引擎分歧**：字面量触发词与别名同形时，core 先译关键词（`Canonicalizer.java:732-741`），ts 先展开字面量（`canonicalizer.ts:601-604`），结果不同 | 无（`UserAliasValidator.java:234` 明确不查字面量） | 新规则：别名不得等于任何字面量触发词（三处校验器都加）；内嵌词汇另禁触发词等于该 locale 的规范拼写、官方别名与本版本别名（§6.4）。引擎执行顺序的统一另开 issue，不在本 ADR |
| T4 | **边界分歧**：假名、谚文、扩展 B 在两引擎的 token 边界不同 | — | 汉字单词别名只允许 U+4E00–9FFF 与 U+3400–4DBF，且只在 `zh-*` locale |
| T5 | **直连绕过授权** | 不可信调用 `allowStructural=false` | 不变；`FUNC_PRODUCE` 是结构词，直连调用同样被拒 |
| T6 | **DoS** | `MAX_ALIAS_KINDS 32 / PER_KIND 8 / LENGTH 64`；aster-api 的 `VocabularyBounds` | 不变；汉字别名另限 8 字；内嵌词汇另有上限（§6.6） |
| T7 | **显示欺骗（内嵌字面量宏）**：`思故乡` 读起来是个名字，执行时是字符串 `"静夜思"` | 字面量宏今天只在演示里 | 版本详情新增「内嵌词汇」映射表，字面量行标为「展开为字符串」并显示展开值；LayoutMap 的 `layout_structural_hides_value` 让触发词只能出现在内容 span（显示层改不了它）；ADR 0058 §8.2 的规范预览显示展开后的源码 |
| T8 | **新执行能力**：内嵌词汇能否引入源码本来写不出的东西 | — | 不能。词条只把一个本地化写法映射为规范名或一段受校验的字符串；规范名的解析、类型检查、效果检查都在解析后的 Core IR 上照常进行（ADR 0051）；字面量内容单行、无引号与反斜杠。内嵌词汇能做的，作者把规范名或字符串直接写进源码同样能做 |
| T9 | **编译身份**：内嵌词汇被单独篡改 | — | 内嵌词条并入有效词汇后进 envelope 第 5 段；审批与执行时复算有效 `scopesJson` 与封印值比较（§7.2），不等即拒 |

**复审条件**：若将来实现团队 / 租户层别名（ADR 0022 Phase B/C），汉字单词别名会跨策略生效，T2 的论证②失效，必须重新评审本节。

---

## 5. 别名规则 v2（修订 ADR 0022）

### 5.1 kind 白名单

| 档 | kind | 条件 |
|---|---|---|
| 运算符 | `PLUS`、`MINUS_WORD`、`TIMES`、`DIVIDED_BY`、`INTEGER_DIVIDED_BY`、`MODULO`、`LESS_THAN`、`GREATER_THAN`、`EQUALS_TO`、`AT_LEAST`、`AT_MOST`（不变） | 恒允许 |
| 结构词 | `MODULE_DECL`、`FUNC_TO`、**`FUNC_PRODUCE`（新增）**、`IF`、`OTHERWISE`、`MATCH`、`WHEN`、`RETURN` | `allowStructural=true` |
| 其余 | — | 恒拒（不变） |

`MAX_ALIAS_KINDS = 32` 不变（可别名 kind 由 18 变 19）。

### 5.2 单词规则

```
isMultiWord(norm)            = norm 含 ASCII 空格
isHanSingleToken(norm, loc)  = loc 的主语言子标签为 "zh"
                               且 norm 的每个码点 ∈ [U+4E00, U+9FFF] ∪ [U+3400, U+4DBF]
                               且 2 ≤ 码点数 ≤ 8
接受 ⇔ isMultiWord(norm) ∨ isHanSingleToken(norm, loc)
```

- 不满足时的错误码仍是 `singleWord`，消息改为「必须是多词短语，或 zh 下 2–8 个汉字的单词」。
- 码点数按 Unicode 码点（Java `codePointCount`、JS `[...s].length`），两侧一致。
- locale 前缀判定与 `UserAliasValidator.resolveBase` 的 zh 回退同口径（`UserAliasValidator.java:299-301`）。

### 5.3 碰撞规则

在既有的「不遮蔽规范拼写 / 官方别名、跨 kind 不重复、不撞词汇写法」之外，新增两条：

1. **别名（折叠键）不得等于有效词汇中任何字面量触发词或其别名**。
2. **IR 名字守卫**（只在 aliasSet 含汉字单词别名时启用）：令 K = 每个带汉字单词别名的 kind 在 en-US lexicon 中的全部拼写（规范拼写 + 官方别名）加上这些汉字别名本身，均取折叠键。包含别名本身是因为两引擎在名字位置的处理不必相同：core 在文本层把别名译成英文关键词再按软关键字当名字，ts 的上下文关键词模型可能保留原写法；两种结果都要命中。解析后，若任一名字——模块名各段、声明名（规则、Data、枚举、类型别名、导入别名）、参数、局部绑定、字段、成员访问、构造字段、调用目标、枚举成员——折叠后属于 K，编译失败，报新诊断 `alias_identifier_collision`（按 ADR 0031 在 ts `shared/error_codes.json` 登记下一个空号，core / locales 同步，消息「别名 'X'（KIND）在源码中被当作名字使用」）。守卫放在 core `InProcessCnlParser.parseWithUserAliases` 与 ts `compileSource` 的解析之后、降级之前，因此保存门禁、每次执行的复验、包导出的 Node 重编译都会经过它。多词别名不需要它（含空格，不可能是名字）。代价：作者在带汉字单词别名的策略里不能把名字写成 `return`、`module` 等英文拼写，报错时说明原因。

第 1 条的落点：core 的 `vocabularyHasWritten`（`UserAliasValidator.java:235-243`）加入 `scopes.literals()`；ts `alias-validate.ts` 同；cloud 的 `ReservedSets.vocabularyTermsLower` 改由**本次保存的有效词汇**生成，含字面量触发词（今天由 `buildAliasReservedForUser` 读作者全部个人词条，不含团队词条、不按作用域，`src/lib/structural-alias-grants.ts:22-55`，与编译所用词汇不一致）。

第 1 条是本 ADR 对既有输入的唯一**收紧**（第 2 条只作用于汉字单词别名，存量版本不可能有）：执行时会复验冻结 aliasSet（§1.1），因此列车前须用 gate scan 确认生产没有「别名 = 字面量触发词」的版本（cloud 用户词汇不存字面量，风险只在 Guard 部署带来的 `vocabulary_json`）。

### 5.4 版本与 parity

- core `UserAliasValidator.VERSION`、ts `USER_ALIAS_VALIDATOR_VERSION`、cloud `USER_ALIAS_VALIDATOR_VERSION` 同时改为 `"2"`。
- toolchainId 含 `validator=`（`CompileIdentity.java:37`、cloud `policy-alias.ts:55-61`），新版本的 envelope 带 `validator=2`；已存版本各有 `sourceToolchainId`，重算不受影响。
- 新增跨引擎共享夹具 `alias_validation_v2_parity.json`（core `src/test/resources/diagnostics/`、ts `shared/`、cloud `src/lib/__fixtures__/` 三份逐字节相同的副本，同 parse-diagnostics parity 夹具的做法）：每条 `{aliasSet, locale, allowStructural, vocabulary?, expectValid, expectCodes}`，覆盖 §5.1–5.3 每条规则的正反例；另含 `guardCases`（源码 + aliasSet + 期望 `ok` / `alias_identifier_collision`），对每个可别名结构词与一个运算符 kind、每类名字位置各至少一例，跑完整编译；cloud `policy-alias-shared` 的测试读同一夹具。

### 5.5 发布顺序（列车门）

1. core 与 ts 校验器 v2 合入各自 main。
2. 列车 eco-1.0.52 发布 core / ts，并按 ADR 0053 修订 1 由列车 bump aster-api pin；列车前跑 gate scan（§5.3）。
3. aster-api 1.0.52 上线后，cloud 才合「放宽」PR（升级 ts 依赖、`policy-alias-shared` v2）。
4. 在此之前，即使 cloud 前端放宽了，aster-api 的编译门禁仍按 v1 拒绝，保存失败而不是落库——顺序错了只会 fail-closed。
5. **回退约束**：cloud 放宽之后，aster-api 不得回退到 1.0.52 以下的引擎，否则已存的汉字单词别名版本在执行时复验失败。后果是执行报错（fail-closed），不会算出错误结果。机械防线：aster-api 的 `scripts/ci/verify-sibling-ref-pin.sh` 增加引擎下限 1.0.52，低于下限的 pin PR CI 失败；**剩余风险**：集群层面的镜像回滚（`kubectl rollout undo`）不经过 CI，只能靠 runbook。回退 cloud 无此问题。

---

## 6. 策略内嵌词汇

### 6.1 格式

```ts
type EmbeddedTermKind = 'struct' | 'field' | 'function' | 'enum_value' | 'literal';
interface EmbeddedTerm {
  kind: EmbeddedTermKind;
  localized: string;   // 源码里的写法（触发词）
  canonical: string;   // 规范名；literal 为字符串内容（不含引号）
  parent?: string;     // field / enum_value 必填（所属结构体 / 枚举的规范名），其余禁止
}
interface EmbeddedVocabulary { v: 1; terms: EmbeddedTerm[] }
```

- 每条只有一个写法（不支持 `aliases`、`description`）：内嵌词汇服务于一条策略，同义词没有用处，还会扩大碰撞面。
- 确定性序列化 `serializeEmbeddedVocabulary`：`localized`、`canonical`、`parent` 先做 NFC；词条按 `(kind, localized, parent ?? '')` 的码点序排序；键序固定 `kind, localized, canonical, parent`；`JSON.stringify` 转义、无空白；`terms` 为空 → 存 NULL。同一内容只有一种字节表示。
- 转换为引擎形状 `toDomainVocabulary(ev, locale)`：`{ id: 'policy.embedded', name: 'policy', locale, version: '1', structs, fields, functions, enumValues, literals }`，`kind` 映射到 `IdentifierKind`。

### 6.2 存储

cloud `PolicyVersion` 新列 `embeddedVocabulary text NULL`，CHECK `octet_length("embeddedVocabulary") <= 8192`。

- 与 `aliasSet` 同一行、同一生命周期：`createVersion` 插入时写，没有修改接口；想改就建新版本。
- **为什么是列，不是独立表**（对照 ADR 0058 的 `PolicyVersionLayout`）：layout 不是编译输入，执行路径从不读它，放独立表让版本查询不背大字段；内嵌词汇是编译输入，每次执行、导出、回放都要读，且上限 8 KiB，放在版本行上省一次联查，也和 `aliasSet` 的读取点完全重合。
- **为什么是 text 不是 jsonb**：jsonb 重排键，序列化不可复现（ADR 0058 §7.2 同理）。
- **为什么不复用用户词汇快照表**：`UserVocabularySnapshot` 按 owner 去重计数、`DomainTerm.kind` 的 CHECK 不含 literal，而且快照只在审批时写；把策略词条塞进去要改两张表的约束与冻结时序，换不来任何东西。
- 迁移先行：只含该列的迁移 PR 先合、生产确认应用后再合代码（纯新增可空列，旧代码不读，无窗口问题）。aster-api 的 `policy_versions` 表不变（Guard 部署已有自己的 `vocabulary_json`，直接收合并后的词汇）。

### 6.3 合并

```
effective = mergeEmbeddedVocabulary(embedded, lower)
lower     = 今天的结果：个人策略 = 作者词汇；团队策略 = 团队 + 未被遮蔽的作者词汇
```

优先级 **内嵌 > 团队 > 作者**。下层词条（整条）被丢弃，当且仅当它的任一写法（NFC + 折叠键）与某内嵌词条：

1. 角色键相同（角色 + 字段 / 枚举成员的 parent + 写法，同 `domain-vocabulary-team-merge.ts` 的键）且规范名不同；或
2. 一方是字面量触发词（字面量与任何角色都冲突，因为触发词须全局唯一）。

规范名相同的同键词条去重。被丢弃的下层词条在保存响应里以 `vocabularyShadowed: [{kind, localized, canonical, source: 'team' | 'author'}]` 返回，编辑器提示。

**理由**：内嵌词条写在策略里、随策略评审，是作者对这条策略的明确声明；让它输给事后新增的个人词条，等于别人改词汇就能改变这条策略草稿的含义。与 `mergeAliasSets` 的 policy > team > tenant 同向，也沿用团队合并「冲突即整条丢弃」的既有机制，不另造规则。

内嵌词条**不经过作用域筛选**（§1.5 的「源码里出现过才生效」只用于从大词汇里挑相关组）：它们本来就属于这条策略。

### 6.4 校验（保存时，`createVersion` 内，失败 400）

| 码 | 规则 |
|---|---|
| `embedded_vocabulary_invalid_format` | 形状不符 §6.1（未知键、未知 kind、parent 缺失或多余、非字符串） |
| `embedded_vocabulary_too_large` | 超出 §6.6 任一上限 |
| `embedded_vocabulary_invalid` | 引擎 `validateVocabulary` 不通过（标识符种类的规范名须是 ASCII 标识符；字面量内容规则；触发词全局唯一），附引擎消息 |
| `embedded_vocabulary_duplicate` | 同一角色键出现两次，或同一触发词出现两次 |
| `embedded_vocabulary_shadows_keyword` | 写法（折叠键）等于该 locale 的规范拼写、官方别名或本版本 aliasSet 中的别名（T3） |

别名一侧的碰撞（§5.3）用合并后的有效词汇校验，所以「别名撞内嵌词条」由别名校验报出（`collidesVocabulary`）。

### 6.5 请求字段与三态

请求体新增 `embeddedVocabulary`，三态与同一路由的 `aliasSet` **完全一致**：

| 路由 | 省略 | `null` | 对象 |
|---|---|---|---|
| `PUT /api/policies/[id]` | 沿用活跃版本的值 | 清空 | 校验后使用 |
| `POST /api/policies`、`POST /api/v1/policies/[id]/versions` | 无 | 无 | 校验后使用 |

两者成对出现（诗需要二者同时成立），规则若不同，就会出现一个被沿用、一个被丢掉的半截版本。PUT 在 `embeddedVocabulary` 序列化结果与活跃版本不同时建新版本（同 aliasSet 的变化判定）。`teams/[teamId]/policies` 不读 aliasSet，也不读本字段。

### 6.6 上限

| 上限 | 值 | 理由 |
|---|---|---|
| 词条数 | ≤ 32 | 《静夜思》2 条；guyong 演示 1 条（`src/config/guyong-demo.ts:142-153`）。内嵌词汇是一条策略的点缀，不是付费词汇（`customLexiconMaxTerms` 500 起，`src/lib/plans.ts:161`）的免费替代 |
| `localized` | ≤ 32 字符 | 写法是一个词 |
| 标识符种类的 `canonical` / `parent` | ≤ 64 字符 | 规范标识符 |
| 字面量内容 | ≤ 256 字符 | 一句标准文案 |
| 序列化后 | ≤ 8192 字节（DB CHECK 同值） | 32 × 约 256 字节 |

### 6.7 门禁

- 保存**不设能力门、不设套餐门**：内嵌词汇与 `aliasSet` 一样是编译输入。设门会让无权队友改不了团队策略——而且与 layout 不同，它不能被丢弃（丢掉会改变语义），只能拒绝保存（ADR 0058 §5.2、§6 的同一理由）。
- 编辑入口在「词汇」标签页的「本策略」分组，随 `structured_authoring` 出现（ADR 0058 §8.3）；没有该能力的作者在编辑器里看到只读的内嵌词条列表，保存时省略字段即沿用。
- 结构词别名授权仍按今天的规则判定，与内嵌词汇无关。

---

## 7. 编译身份、冻结、执行

### 7.1 envelope：不加段

内嵌词条并入有效词汇（§6.3）后，与今天一样由 `vocabularyScopesJson(effective)` 算出第 5 段 `scopesJson`。两侧 `computeSourceEnvelope` 的签名、段序、既有向量全部不变。

- **为什么不加第 6 段**：envelope 的定义是「完整编译输入」（ADR 0051 §7、ADR 0058 §7.2），而引擎看到的编译输入就是合并后的词汇；内嵌与否是 cloud 的存储来源，不是编译输入。加段要改 cloud 与 Java 的 envelope、全部共享向量、包导出与离线验证器，换来的只是「来源可区分」，而这点由 `embeddedVocabulary` 列本身提供。
- **字面量覆盖**：`scopesJson` 已包含 `literals`（ADR 0051 R37），宏内容的任何改动都会改变 envelope。aster-lang-test 共享向量新增 `envelope.literal-macro`（`思故乡 → 静夜思` 加一条 struct），复制进 aster-api 与 cloud 并更新两侧的向量哈希钉。

### 7.2 冻结与一致性复核

- 内嵌词汇在建版本时写入版本行，**不需要**审批快照；审批只冻结下层（团队 / 作者）词汇，与今天相同。
- 审批复核（`freezeApprovalVocabulary`）改为比较 `vocabularyScopesJson(mergeEmbeddedVocabulary(embedded, 冻结的下层))` 与 `sourceScopesJson`。
- 执行 APPROVED / DEPRECATED 版本且 `embeddedVocabulary` 非空时，同样复算并比较；不等即拒绝执行（`vocabulary_seal_mismatch`，fail-closed）。草稿用现行词汇，本来就可能与封印不同，不比较（与今天一致）。

### 7.3 所有读版本编译输入的路径

全部经过一个函数：`withEmbeddedVocabulary(lower, version)`，接入点：

| 接入点 | 覆盖的调用方 |
|---|---|
| `loadSaveVocabulary`（新增参数 `embedded`） | 四条建版本路由、两个种子脚本 |
| `execution-input/index.ts` 的 `loadVersionVocabularyStrict` 与 `loadVersionExecutionVocabulary` | 两个 execute 路由、verify-parity、规则回归、脱敏日志、`loadStoredVersionCompileContext`（→ Cedar、包导出、Guard 部署、内部 What-If 回源） |
| `freezeApprovalVocabulary` | 审批 |
| `resolver-migration.ts:257-259` | ADR 0051 resolver 迁移 |

aster-api 只看到合并后的 `DomainVocabulary`：编译、`evaluate-source`、包导出（`compile/vocabulary.json` 自然包含内嵌词条，离线包因而自足）、Guard 部署都**无需改协议**。

### 7.4 aster-api 的改动

| 改动 | 列车门 |
|---|---|
| 批量 What-If 带上目标版本的 `aliasSet`（`VersionSource` 加字段，`ReplayBatchService` 传入，`aliasesTrusted=true` 不变）。这是既有缺陷（§1.5），不修则诗在批量 What-If 下编译失败 | 否 |
| envelope 共享向量更新 | 否（仅测试资源） |
| 列车 bump 引擎 pin 到 1.0.52 | 由列车完成 |
| 列车后：诗的端到端测试（HMAC 签名的 `/compile` 与 `/evaluate-source`，zh-CN + aliasSet + 合并词汇 → `静夜思`）；`verify-sibling-ref-pin.sh` 引擎下限 1.0.52（§5.5 第 5 条） | 依赖列车 |

### 7.5 编辑器与评审

- **词汇标签页**：新增「本策略」分组（增、删、改五种 kind；literal 行显示「展开为字符串」）；下层被遮蔽的词条在其原分组里标「被本策略覆盖」。
- **浏览器编译与 LSP**：编辑器本地编译（`use-compile.ts`）与 LSP 词汇注册用有效词汇（含内嵌），诊断与保存门禁一致。
- **版本详情（审批视图）**：在别名映射表下新增「内嵌词汇」映射表：写法 → 规范名 / 展开值，字面量行加标记（T7）。版本对比把 `embeddedVocabulary` 的变化单列一行（同 ADR 0058 §7.7 对 layout 的处理）。
- 文案 en / zh / de / hi。

---

## 8. 《静夜思》策略

| 项 | 值 |
|---|---|
| locale | `zh-CN` |
| 源码 | §1.6（与演示 `POEM_ZH.source` 逐字节相同） |
| aliasSet | `{"FUNC_PRODUCE":["举头"],"FUNC_TO":["疑是"],"MODULE_DECL":["床前"],"RETURN":["低头"]}`（全是结构词，需授权） |
| embeddedVocabulary | `{"v":1,"terms":[{"kind":"literal","localized":"思故乡","canonical":"静夜思"},{"kind":"struct","localized":"望明月","canonical":"Text"}]}` |
| layout | 演示的 `JINGYESI_LAYOUT`（结构 span 只有空格与标点，满足 ADR 0058 §7.5 全部规则；ts `test/unit/layout/validate.test.ts:47-50` 已验证 zh-CN + 这四个别名时通过） |
| 入口 | 规则 `地上霜`，无参数，返回 `Text` |
| 输出 | `静夜思` |
| 模板要求 | `requires: { structuredAuthoring: true, structuralAliases: true }`（layout 需前者，四个结构词别名需后者）；能力不满足时模板已有锁定显示（`src/data/policy-examples.ts:1123-1127`、`src/components/policy/policy-form/templates-tab.tsx:102-105`） |

**为什么仍需要 layout**：zh 词法把相邻汉字连成一个 token（§1.3），`床前明月光` 是一个标识符，别名必须用空格隔开；让源码本身无空格需要关键词的位置感知分词，那是 ADR 0022 §10 留下的另一件事，不在本 ADR。layout 让显示与原诗一致，编译的仍是带空格的 canonical。

**证明**（计划最后一个任务的验收）：

1. ts：`compileSource(source, { locale: 'zh-CN', aliasSet, allowStructural: true, vocabulary })` 零诊断，求值 `地上霜` 得 `静夜思`。
2. Java core 经 aster-api：本地栈中带 HMAC 的 `POST /api/v1/policies/compile` 零 error 诊断；`POST /api/v1/policies/evaluate-source` 得 `静夜思`。
3. 两侧 Core IR 的 `positionFreeSha256` 相同（ADR 0059 P2-R5 的同一判据，否则 Node 导出会报 E739）。
4. cloud + aster-api 1.0.52 本地栈：模板应用 → 保存 → 审批 → 经真实 `evaluate-source` 执行，输出 `静夜思`（执行使用冻结的下层词汇 + 内嵌词汇）；批量 What-If 以该版本为目标同样输出 `静夜思`；版本详情显示别名映射与内嵌词汇映射。

---

## 9. 备选方案（已否决）

| 方案 | 否决理由 |
|---|---|
| 作为 ADR 0058 的修订 1 | 0058 讲能力授权与显示排版；本 ADR 改的是 ADR 0022 的安全规则、三处引擎校验器与一类新的编译输入。放在 0058 下，读 0022 的人找不到规则已改 |
| 单词别名全面放开（任何文字） | ADR 0022 §10 的实证对拉丁文字仍成立：常见英文单词就是常见字段名 |
| 汉字单词别名按「位置感知识别」才放开（ADR 0022 §10 的原设想） | 要在两个引擎各实现一套关键词位置判定，复杂度远超问题；整词匹配 + 版本局部 + 编译门禁已满足 T2 |
| 允许单个汉字别名 | 单字最容易撞日常标识符；诗与政策都不需要 |
| 把 CJK 扩展到假名、谚文 | 两引擎边界不一致（T4）；谚文本来用空格分词，多词规则对它有效 |
| `FUNC_PRODUCE` 放进运算符档（无需授权） | 它是签名关键词，与 `FUNC_TO` 同类；产品决策保留结构词授权 |
| 模板把词条写进用户词汇 | 产品决策明确禁止；还会影响用户的其他策略（A3） |
| 给用户词汇加 literal 种类代替内嵌词汇 | 字面量宏是一条策略里的文案，不是领域术语；放进用户词汇会进入作者其他策略的作用域判断。另议（§10 Q2） |
| 内嵌词汇独立表（仿 `PolicyVersionLayout`） | §6.2：编译输入每次执行都要读，多一次联查；体量小 |
| 内嵌词汇用 jsonb | 键重排，序列化不可复现 |
| 下层词条优先，或冲突即拒绝保存 | 前者让事后新增的个人词条改变策略草稿含义；后者让别人新增词条就使这条策略无法再保存 |
| envelope 加第 6 段 | §7.1 |
| 内嵌词汇按能力或套餐设门 | §6.7：不能丢弃，只能拒绝保存，会锁死团队策略；32 条上限已防止替代付费词汇 |
| 内嵌词条支持同义词（`aliases`） | 没有需求，扩大碰撞面 |

---

## 10. 已决问题（2026-11-20，产品负责人采纳全部推荐值）

1. **免费套餐可以使用内嵌词汇**，上限 32 条（§6.6、§6.7；T9）。
2. **用户 / 团队词汇不加 literal 种类**；如有需要另开 issue（§2 非目标）。
3. **冲突时内嵌词条优先**，不拒绝保存；被遮蔽的下层词条在 `vocabularyShadowed` 中回报（§6.3；T8）。
4. **Guard 部署维持 `allowStructural=false`**，诗暂不能部署到 Guard（§2 非目标）。
5. **没有结构词别名授权的用户看到的诗模板是锁定的**，不设例外（§8；T13）。
6. **汉字单词别名长度 2–8 个码点**（§5.2；T3、T4、T12）。
7. **规范源码在别名后带空格**，由 LayoutMap 在显示时隐去，与 `/zh/demos/poem` 相同（§8）。

## 11. 风险

| 风险 | 缓解 |
|---|---|
| 字面量碰撞规则是收紧，执行时复验可能让存量版本失败 | 列车前 gate scan（§5.3）；cloud 用户词汇没有字面量 |
| cloud 先于 aster-api 放宽 | aster-api 编译门禁 fail-closed（§5.5 第 4 条）；计划把放宽 PR 排在列车之后 |
| aster-api 回退到旧引擎 | §5.5 第 5 条：pin 下限 CI 检查 + runbook；镜像级回滚是剩余风险，后果 fail-closed |
| 汉字单词别名把同形名字静默改名（软关键字） | §5.3 第 2 条 IR 名字守卫，双引擎按位置矩阵测试 |
| 内嵌词条与下层词条冲突，作者没注意到自己的个人词条在这条策略里失效 | `vocabularyShadowed` 响应 + 编辑器标记 |
| 三处校验器漂移 | 共享夹具 `alias_validation_v2_parity.json` 三处同跑 |
| 字面量宏误导审批者 | 版本详情的内嵌词汇映射表与字面量标记；LayoutMap 不能把触发词藏进结构 span |
| ts 与 core 对「触发词 = 关键词」的处理顺序不同 | 校验器与内嵌词汇校验在入口拦截；引擎顺序统一另开 issue |

---

## 12. 裁决

| # | 裁决 |
|---|---|
| R1 | 新 ADR 0060，不作 ADR 0058 的修订（§9 第 1 行） |
| R2 | 汉字单词别名：`zh-*`、汉字基本区 + 扩展 A、2–8 个码点（§5.2） |
| R3 | `FUNC_PRODUCE` 入结构词档（§5.1） |
| R4 | 新增「别名 ≠ 字面量触发词」，并承认这是收紧，以 gate scan 兜底（§5.3） |
| R5 | 三处 `USER_ALIAS_VALIDATOR_VERSION` 升到 `"2"`；core / ts 改动列车门；cloud 放宽排在 aster-api 1.0.52 上线之后（§5.5） |
| R6 | 内嵌词汇存为 `PolicyVersion.embeddedVocabulary text`，迁移先行（§6.2） |
| R7 | 合并优先级内嵌 > 团队 > 作者，冲突时下层整条丢弃并回报（§6.3） |
| R8 | envelope 不加段；新增含字面量宏的共享向量（§7.1） |
| R9 | 三态与同路由的 `aliasSet` 一致（§6.5） |
| R10 | 保存不设门，编辑入口随 `structured_authoring`（§6.7） |
| R11 | 批量 What-If 补 aliasSet，作为本 ADR 的前置修复（§7.4） |
| R12 | 模板只写内嵌词汇与别名，永不写用户词汇；#829 模板整体替换（§8） |
| R13 | 汉字单词别名必须配 IR 名字守卫 `alias_identifier_collision`，双引擎实现，属列车门（§5.3 第 2 条） |
| R14 | aster-api 引擎 pin 下限 1.0.52 进 CI 检查（§5.5 第 5 条） |
| R15 | §10 七项由产品负责人于 2026-11-20 确认：免费可用内嵌词汇（32 条）、用户词汇不加 literal、内嵌优先不拒绝、Guard 维持不支持结构词别名、无授权则模板锁定、汉字别名 2–8 字、规范源码带空格由 layout 隐去 |
| R16 | ts 与 core 对字面量展开 / 关键词翻译的先后顺序不一致，另开 issue 统一（§4.2 T3）；本 ADR 只在校验入口拦截。**已由[修订 1](0060-amendment-1-literal-trigger-keyword-precedence.md) R16' 取代**：关键词优先，作为数据规则实现 |
