# ADR 0022：关键词别名（官方同义词扩充）

- 状态：IMPLEMENTED（en-US，双引擎；非英语数据待母语者审核）
- 日期：2026-06-24（提案）/ 2026-06-25（实现）
- 决策者：用户已拍板（§9 三问：①触碰语义冻结=接受，加法兼容；②先做 corpus 调研=已完成；③运算符纳入=是）
- 相关：ADR 0014（领域词汇翻译）、ADR 0018（统一语言包）、ADR 0016（IR 字段级 parity）、`keyword-identifier-disambiguation`、`ai-positioning-and-rollback-gap`

---

## 1. 背景与问题

当前每个语义（`SemanticTokenKind`）在每种语言里只有**唯一一个**钦定拼写：

- Java：`Lexicon.getKeywords()` 返回 `Map<SemanticTokenKind, String>`
- TS：`Lexicon.keywords` 是 `Record<SemanticTokenKind, string>`

后果：用户合理的同义表达被解析器拒绝。例如 `Rule` 不能写成 `Policy`/`Function`，中文 `规则` 不能写成 `条款`，`given` 不能写成 `taking`。对目标用户（核保员、合规官、产品经理等**非程序员**），"我写的合规语言被机器拒绝"是真实的采用阻力，与产品"用你的语言写规则"的定位相悖。

**问题陈述**：是否引入"关键词别名"机制，让一个语义可由多个拼写识别，从而贴合不同用户的语言习惯？

## 2. 现状实证（两引擎源码）

- **无任何关键词别名机制**。代码库中的 "alias" 全部是 import 别名（`Use … as X`，`AsterParser.g4:255`）与 type alias，与关键词无关。
- **领域词汇翻译（ADR 0014）不覆盖关键词**：`IdentifierKind` 枚举仅 `STRUCT/FIELD/FUNCTION/ENUM_VALUE`。无法借用它给关键词起别名。
- **反向索引天然多对一**：`Lexicon.buildKeywordIndex()` 返回 `Map<String, SemanticTokenKind>`，canonicalizer 走 `toLowerCase` 匹配。**别名的识别侧成本极低——只是让 index 多几个 key 指向同一个 Kind**。
- **但存在大量"kind → 唯一规范拼写"的反向依赖**（不可破坏）：
  - `Canonicalizer.java:377/385/435`：翻译时用 `getKeywords().get(kind)` 取**目标语言规范拼写**作为输出
  - `Lexicon.getKeyword(kind)`、`getMultiWordKeywords()`、`LexiconExporter:130`、`FallbackLexicon` 合并、`LexiconValidator:97`（按 `keywords.size()` 校验键集完整性）
  - `getKeywords()` 被 13+ 处依赖为 1:1 契约 Map

## 3. 核心设计原则（决定一切的不变式）

> **别名只存在于"识别侧"（输入）；规范拼写是唯一的"产出侧"（输出）。**
> Canonicalizer 永远把别名归一成该 kind 的规范拼写后再进入下游。

由此推出的不变式：

1. **IR 结构级一致零损**：别名在 canonicalize/translate 阶段即被归一成规范拼写，lowering 看到的永远是规范形。`Rule`/`Policy`/`条款` 写的同一逻辑 → 同一 token 流 → **结构一致的 Core IR**。唯一差异是 `origin`（源码位置元数据）随关键词长度偏移（如 `Whenever` 8 字符 vs `If` 2 字符列号不同）——这是派生层，ADR 0016 的 `normalizeIr` 本就剥离 `origin`。**已实测验证**（core KeywordAliasTest + ts keyword-aliases.test）：剥离 origin 后别名版与规范版 IR 逐字节相同。故 ADR 0016 的 parity 契约**不受影响**。
2. **可信执行链零损**：审计、回放、版本固化记录的都是规范形（或源码原文 + 规范化产物），别名不引入新的运行时状态。
3. **向后兼容**：别名是纯增量。不写别名的 lexicon 行为与今日**逐字节相同**。

## 4. 决策

采纳**方案 A：官方关键词同义词扩充**。

- **官方维护、locale 级、全租户一致**的别名集，随 lexicon 包发布。
- **不做**租户级用户自定义别名（方案 C）——它会让同一源码在不同租户编译结果不同，破坏可移植性与审计面，侵蚀护城河。
- **行业方言（方案 B）暂不在本 ADR 内**：若需要，走"派生 lexicon（如 `en-US-banking`）+ 现有 SPI/治理"，仍是 locale id 级而非用户级，留待独立 ADR。

### 4.1 数据结构（Java）

`Lexicon` 接口**新增** default 方法，**不动** `getKeywords()`：

```java
/** kind → 别名拼写列表（识别侧）。默认空 = 无别名，行为与今日一致。 */
default Map<SemanticTokenKind, List<String>> getAliases() {
    return Map.of();
}
```

- `buildKeywordIndex()` 改为：先放 `getKeywords()`（规范），再放 `getAliases()` 的每个别名 → 同一 kind。**别名与规范拼写撞 key 时规范拼写胜出**（规范优先，且禁止别名遮蔽其它 kind 的规范拼写，见 §5 校验）。
- `getKeyword(kind)`、`getMultiWordKeywords()`、Exporter、Canonicalizer 的**输出路径全部继续只读 `getKeywords()`** → 规范拼写唯一性天然保持，零改动。
- `getMultiWordKeywords()` 需把多词**别名**也纳入最长匹配集（否则多词别名 `as one of` 类的别名无法被分词识别）→ 改为同时遍历 `getKeywords().values()` 与 `getAliases()` 各 list。

### 4.2 数据结构（TS）

`types.ts` 的 `Lexicon` 接口新增可选字段（与 Java 对齐）：

```ts
/** kind → 别名拼写列表（识别侧）。缺省 = 无别名。 */
readonly aliases?: Readonly<Partial<Record<SemanticTokenKind, readonly string[]>>>;
```

- `registry.ts` 的 `buildKeywordIndex` / canonicalizer 关键词词集（`canonicalizer.ts:85` 的 `keywordWords` set）追加别名。
- 翻译/lowering 输出侧不变（继续用 `keywords[kind]`）。

### 4.3 别名数据来源

别名以 overlay 形式随语言包发布（复用 ADR 0018 的 overlay 机制，类 `overlays/diagnostic-help.json`）：新增 `overlays/keyword-aliases.json`，形如：

```json
{ "FUNC_TO": ["Policy", "Function"], "IF": ["Whenever"] }
```

- en/zh/de/hi 各自维护，**键集是 `SemanticTokenKind` 子集**（只给需要别名的 kind 写）。
- 经 `LexiconExporter` 导出到统一 lexicon JSON 的新 `aliases` 段（供 TS 消费 + cloud 展示）。

## 5. 校验与冲突防护（必须随实现落地）

1. **别名唯一性 / 不遮蔽**：任一别名（lowercase）**不得**等于本 lexicon 中任何 kind 的规范拼写或其它别名。`LexiconValidator` 新增此检查，重复即 **error**（拒绝注册）。
2. **键集完整性不变**：`LexiconValidator:97` 仍按 `getKeywords().size()`（规范集）校验完整性；别名是**附加**，不计入完整性要求。
3. **标识符碰撞**：别名扩大了"关键词词集"，会加剧 `keyword-identifier-disambiguation` 里处理过的"关键词词当标识符"问题。**铁律**：
   - 别名候选必须先过一遍现有 corpus，确认不与高频标识符同形；
   - 别名只在与规范关键词**相同的语法位置**被识别（消歧逻辑天然复用，无需新增位置判定）；
   - 多词别名优先于单词别名（最长匹配），避免子串误吞（参考 `modulo-intdiv-operators` 的 OPERATOR_SYMBOL_MAP 最长优先教训）。
4. **运算符别名**：运算符（如 `divided by`）的别名若含已有运算符子串，必须进 Canonicalizer 的最长匹配映射（同 [[modulo-intdiv-operators]]），否则子串先被翻译留下游离 token。

## 6. Parity 与测试（双引擎对齐）

- **新增 parity fixture**：同一逻辑分别用「规范拼写」与「别名」编写两份，断言两者 lower 到**同一 Core IR**（进 tier1-parity manifest，PR-blocking）。这把"别名零损 IR"变成 CI 契约。
- Java：`getAliases()` 默认空，已有 lexicon 不写别名 → 全部既有测试不变。
- TS：`aliases?` 可选 → 既有 golden 不变。
- 两引擎别名集**必须一致**（同 export manifest，sha256 校验，复用 ADR 0018 的 `verifyLexiconParity`/`verifyUiMessagesParity` 模式）。

## 7. 影响面（实现时的改动清单，按仓）

| 仓 | 改动 |
|---|---|
| aster-lang-core | `Lexicon.getAliases()` + `buildKeywordIndex`/`getMultiWordKeywords` 纳入别名；`LexiconExporter` 导出 `aliases` 段；`LexiconValidator` 加唯一性/不遮蔽校验；overlay loader 读 `keyword-aliases.json` |
| aster-lang-ts | `types.ts` 加 `aliases?`；`registry.ts`/`canonicalizer.ts` 词集纳入别名；overlay-loader 读别名 |
| aster-lang-locales (en/zh/de) + aster-lang-hi | 各加 `overlays/keyword-aliases.json`（初始可空，逐步填候选） |
| aster-lang-test | 别名↔规范 parity fixture（tier1，PR-blocking） |
| aster-cloud / aster-dev | 可选：Monaco 高亮/自动补全识别别名（消费 export 的 aliases 段）；文档说明 |
| aster-api | 无代码改动（includeBuild core 源码 + SPI 自动获别名；export 端点自动含 aliases 段） |

## 8. 备选方案（已否决）

- **B. 派生/行业 lexicon**：能力更强但范围更大，且本问题（"贴合个人语言习惯"）用 A 已解决 80%。留独立 ADR，可与 credit-risk PoC（[[credit-risk-poc]]）联动做信贷词表。
- **C. 租户级 UI 偏好别名（外部可变配置）**：别名定义游离在版本之外、UI 随手改即生效 → 破坏可移植性/审计/可信执行链，**否决**。**但"自定义别名"本身不是问题**——见 §11 方案 D：把别名收进版本快照即可两全。
- **复用 ADR 0014 领域词汇翻译给关键词起别名**：`IdentifierKind` 不含关键词，机制不匹配，且把"语言皮肤"与"领域术语"两个正交概念耦合，**否决**。

## 9. 未决问题（需用户在推进实现前拍板）

1. **是否触碰语义冻结单向门**：扩充关键词识别集是"加法"（旧源码全兼容），理论上不破坏冻结，但仍扩大了"合法源码"集合——需确认是否计入 Spec 1.0 的兼容承诺。
2. **首批别名清单**：哪些 kind 最该加别名、各 locale 候选拼写（建议先做一次跨语言同义词需求调研再定，避免拍脑袋引入碰撞）。
3. **运算符是否纳入首批**：运算符别名碰撞风险高于声明关键词，建议首批只做声明/控制流关键词（`FUNC_TO`/`IF`/`RETURN` 等），运算符延后。

---

## 10. 实现状态（2026-06-25）

**已实现并双引擎验证（en-US）**：

| 仓 | 分支 | 改动 | 验证 |
|---|---|---|---|
| aster-lang-core | `feat/keyword-aliases` | `Lexicon.getAliases()` 默认空 + buildKeywordIndex/getMultiWordKeywords/findSemanticTokenKind/isKeyword 纳入别名；DynamicLexicon 解析 `aliases` 段；FallbackLexicon 透传 target 别名；Canonicalizer.buildKeywordTranslationMap 三处别名映射（运算符→符号 §1、英文别名→英文规范 §2、非英文别名→英文规范 §4）；LexiconExporter 导出 `aliases` 段；LexiconValidator 拒绝遮蔽/重复；builtin en-US.json 注入 11 个 kind 的英文别名 | `KeywordAliasTest`（6 测试，含**端到端 lowered-IR**：别名版与规范版走完整 ANTLR 管线降到结构一致 Core IR）+ 全量 test BUILD SUCCESSFUL |
| aster-lang-ts | `feat/keyword-aliases` | `Lexicon.aliases?` + 同名 4 helper 纳入别名；keyword-translator `buildFullTranslationIndex` 阶段3 别名→规范 + `needsKeywordTranslation` 带别名的英文也翻；generate-lexicons 支持 aliases 段；en-US.ts 注入同一份英文别名 | `keyword-aliases.test`（5 测试，含**别名版 vs 规范版 origin-剥离后 IR 逐字节相同** + 内置 EN_US 别名直接编译）+ 全量 1160/0 |

**核心不变式已实测**：别名 canonicalize→规范拼写，剥离 origin 后两引擎 IR 各自与规范版一致。export 链已验证携带 aliases（lexicons.json 含 en-US.aliases）。

**★自审关键修正（用户空间铁律）**：初版注入 11 个 kind 含大量**单词**别名（Policy/Whenever/above/type…）。自审对抗测试发现：这些单词机检不撞**规范拼写**，但**占用标识符命名空间**——用户用同名字段/参数（`Define Account has policy`、`given above`）在无别名时合法（eval 正常），加别名后被当关键词→编译失败=**破坏用户空间**（实测 15/15 候选单词作标识符本都合法）。根因：解析器对这些位置的关键词识别优先于标识符，单词别名一旦注册即"准保留字"。**修正=首批只收多词别名**（`multiplied by`→TIMES、`split by`→DIVIDED_BY）：含空格故天然安全（仅相邻序列匹配，子词 `multiplied`/`split`/`by` 仍可作标识符，已实测）。已加回归测试守护（policy/above 仍可作字段/参数名）。

**英文首批别名清单（收窄后）**：TIMES=[multiplied by]、DIVIDED_BY=[split by]。单词别名（Policy/Whenever 等）**需位置感知识别**（复用 keyword-identifier-disambiguation 的 OF 家族机制：仅在特定语法位置展开）才能不破坏用户空间——列为后续，alias-candidates-research.md 的单词候选全部归此类。

**未完成（明确推迟，非遗漏）**：
- 非英语 zh/de/hi 别名数据：候选已机检（research 文档），但需**母语者审核地道性**后再入包；export 链已就绪，添加后零引擎改动。
- 跨引擎 tier1-parity manifest fixture：当前双引擎各自单测已证 alias→同IR；接入 aster-lang-test PR-blocking manifest 需多仓发布编排（core/ts 先发版），属发布阶段工作。
- 多仓发版与 PR：core/ts 改动在本地分支未推送（grammar/lexicon 改动触发 PR-blocking gate，需按 [[hindi-full-support]] 的发版顺序 core→en/zh/de→truffle→api 协调）。

**降级记录**：本实现按 CLAUDE.md 路径 B（Claude 生成 → Codex 审查），但 Codex MCP 配额耗尽（恢复 2026-06-25 14:06），改为 Claude 自审 + 双引擎实测替代。配额恢复后应补 Codex 交叉审查再发版。

---

## 11. 方案 D：版本固化的用户自定义别名（既保一致性又允许自定义）

> 用户追问："有没有既保证一致性、又能让用户自定义关键字别名的方案？"
> 答：**有**。方案 C 当初被否，否的是"别名游离在版本之外"，不是"自定义"本身。

### 11.1 一致性锚点的精确事实（实证 aster-api PolicyVersion）

一致性是**双层哈希**，不是单一文本：

| 层 | 字段 | 哈希什么 | 用途 | 别名是否影响 |
|---|---|---|---|---|
| 文本层 | `sourceHash`（+ `prevHash` 哈希链）| 源码 `content` 文本 | 版本身份 / 篡改证据 | 影响（文本不同）|
| 产物层 | `artifactSha256` | 编译产物 Core IR | **执行 / 回放 / 跨租户重编译的真相源** | **不影响** |

别名在 canonicalize 期归一成规范拼写 → **只动文本层，不动产物层**。

### 11.2 Spike 实证（spike-plan-d-hash-consistency.md）

同一逻辑、两套不同用户别名（含用户自创的 `scaled by`，非官方清单）+ 规范版：
- 三者 **Core IR sha256 完全相同**（`4d8cfbf9988dfa1a…`）→ 执行/回放一致性与别名**正交**。
- 文本层 sourceHash 不同（预期）。

→ **"用户自定义别名破坏一致性"是伪命题**：一致性锚在 IR 层，别名 compile 期被归一掉。

### 11.3 方案 D 设计：别名 = 编译输入（随版本固化），而非配置（外部可变）

把别名沿用 `Use…version N`（跨模块钉版本）/ vocabulary（ADR 0014）**已验证的架构**——把可变性收进版本快照：

1. **别名定义随策略源码一起提交、编译、快照**：提交时附 `aliasSet`（或源码头 `Alias "条款" as Rule.`）。编译时喂进 `getAliases()`（本 ADR 已实现机制，零新增引擎代码）。
2. **aliasSet 进哈希覆盖范围**：别名成为"编译当时用了什么"的一部分 → 审计完全可见、哈希链锁定、可复现。文本层 sourceHash 应覆盖 (content + aliasSet)。
3. **谁能定义 = 治理问题**：别名提交走与源码相同的 submit→approve→activate 审批流（含 rollback 须 APPROVED，见 [[ai-positioning-and-rollback-gap]] G5）。别名不是"UI 随手改的偏好"，是"经审批进入版本的编译输入"。
4. **跨租户搬运编译版本（Core IR + aliasSet 快照）而非裸源码** → 在哪都能重编出同一 IR（aliasSet 跟着走），可移植性反而强于裸源码搬运。
5. **runtime 永远零别名**：`/evaluate` 跑 Core IR，不碰 lexicon/别名（向 CCO 披露的铁证不变）。

### 11.4 三方案终对照

| | A 官方同义词 | **D 版本固化的用户别名** | C UI 偏好别名（否） |
|---|---|---|---|
| 谁定义 | Aster 团队 | 用户，经审批+随版本快照 | 用户，UI 随手改 |
| 别名存哪 | lexicon 包 | **策略版本快照（哈希覆盖）** | 租户配置（外部可变）|
| 一致性 | ✅ | ✅（IR 锚点不变，实证）| ❌ |
| 审计/可移植 | ✅ | ✅（别名进哈希链/搬编译版本）| ❌ |
| 自定义 | ❌ | ✅ | ✅ |

### 11.5 硬约束 + Codex 对抗复核挖出的 5 个强制前置控制点

**（A）已知约束（与 §10 自审同源）**：
- 用户**单词**别名占标识符命名空间 → 破坏用户空间。用户别名**默认只允许多词**（实证安全），单词别名需位置感知识别才放开。
- 编译期 `LexiconValidator` 不遮蔽校验对用户别名同样跑 → 撞规范拼写/已知标识符则**编译失败而非静默歧义**。

**（B）Codex（CCO/风控视角）复核结论**（详见 `.claude/review-report-plan-d.md`）：方案 D 当前是**纸面承诺、未实现**——`PolicyVersion` 无 aliasSet 字段、`computeSourceHash` 只哈希 `content`、审计 DTO 不暴露编译输入。生产推进前**必须先堵 5 个控制点**，否则有 Critical 审计完整性缺口：

1. **🔴 sourceEnvelopeSha256（替代 content-only sourceHash）**：用规范 JSON 字节（RFC 8785/JCS）覆盖 `content + aliasSet + locale + compilerVersion + canonicalizerVersion + lexiconPackageSha256 + aliasValidatorVersion`。否则"源码哈希对得上、别名被替换"的篡改窗口存在。
2. **🔴 不可变 aliasSetSnapshot + rollback 复制完整 envelope**：v2 改别名定义、源码文本不变时，rollback 必须取**目标版本快照**而非当前别名配置，否则旧源码被新别名重解释（直接关联 G5 rollback 漏洞 [[ai-positioning-and-rollback-gap]]）。缓存键含 envelope hash + 工具链身份。
3. **🟠 敏感 kind 黑名单（最关键的范围收窄）**：别名能映射到哪个 kind 是**用户定的** → 用户可把 `approve` 别名成 RETURN、误导语义短语映射到控制流/授权/拒绝 kind，**审批者看别名源码、实际批准归一后语义**（社会工程绕过审批，Claude 自审漏掉的攻击面）。→ 用户自定义**只放开低风险多词运算符白名单**，**禁止** 控制流/授权/拒绝/RETURN/IMPORT/外部调用等敏感 kind。
4. **🟠 规范化审批视图**：审批对象 = `别名源码 + 规范化源码 + Core IR 摘要 + alias legend`；aliasSet diff 单独确认；高风险双签；记审批者看过的规范化视图 hash。
5. **🟠 跨租户 fail-closed + 工具链身份锁定**：aliasSet 版本局部不写目标租户全局命名空间；导入 fail-closed 并校验 artifactSha256 相等；envelope 锁 compiler/canonicalizer/lexicon/validator 版本+hash（`runtimeBuild` 不够，引擎升级会让旧 aliasSet 重编出不同 IR）。

**范围收敛**：经此复核，方案 D 的"自定义"从"任意 kind 别名"收窄到"**白名单内低风险多词别名**"——同时堵 #3（语义滥用）与单词别名标识符碰撞。

### 11.6 实现增量（相对已完成的别名层）

底层机制（`getAliases()` + Canonicalizer 归一 + Validator 校验）**已实现可直接复用**。方案 D 增量：
- aster-api：策略提交 DTO 加 `aliasSet` → 编译时构造带别名的 lexicon（DynamicLexicon/FallbackLexicon 注入）→ 持久化**不可变 aliasSetSnapshot** → **sourceEnvelopeSha256** 覆盖 (content+aliasSet+工具链身份) → rollback 复制 envelope → 审批流接线 → **敏感 kind 黑名单校验**。
- 治理/UI：别名提交走审批；**规范化审批视图**（别名源码+规范化源码+IR 摘要）；多词限制 + 白名单校验；跨租户导入 fail-closed。
- **状态**：方案 D 为 **PROPOSED（设计+spike 实证完成，Codex 对抗复核完成）**，**未实现**。Codex 判定：当前是纸面控制点，生产推进前必须先堵 §11.5(B) 的 5 个控制点（2 Critical + 3 High）。需用户拍板是否推进 aster-api 管道实现。
