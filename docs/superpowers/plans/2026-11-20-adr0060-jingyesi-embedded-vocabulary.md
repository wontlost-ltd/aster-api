# ADR 0060（汉字单词别名 + 策略内嵌词汇 + 原诗模板）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让《静夜思》原诗（zh-CN + 四个汉字单词结构词别名 + 两条内嵌词条 + LayoutMap）成为可保存、可审批、可执行的真实策略，运行输出 `静夜思`，并在 ts 与 Java core（经 aster-api）两侧得到相同 Core IR。

**Architecture:** 别名校验器 v2 在 core、ts、cloud 三处同步改（共享夹具钉住），core / ts 随列车 eco-1.0.52 发布、列车 bump aster-api pin，cloud 放宽排在 aster-api 上线之后。内嵌词汇是 cloud `PolicyVersion` 上的新列 `embeddedVocabulary text`（迁移先行），所有读版本编译输入的路径经同一个合并函数并入有效词汇；aster-api 只收到合并后的 `DomainVocabulary`，协议不变；envelope 不加段。

**Tech Stack:** aster-lang-core（Java 21、Gradle、JUnit 5）；aster-lang-ts（TypeScript、`node --test`）；aster-lang-test（Node 脚本）；aster-api（Quarkus、Gradle）；aster-cloud（Next.js on Workers、Drizzle + Postgres、vitest、Playwright、next-intl）。依赖：cloud 当前 `@aster-cloud/aster-lang-ts` 3.4.0（`package.json:67`），列车后升到列车发布的 ts 版本（下称 **ts-next**）。

**Spec:** `docs/adr/0060-cjk-keyword-aliases-and-policy-embedded-vocabulary.md`（PROPOSED 2026-11-20）。对应 wontlost-ltd/aster-cloud#830。

## 现状（2026-11-20 核对：aster-api `ae8e4bc8`、aster-cloud `f3877466`、aster-lang-core `d9b7254`、aster-lang-ts `9f2268f`、aster-lang-test `dd87f30`）

ADR §1 已列出全部出处；下表只补任务定位需要的路径。

| 事实 | 出处 |
|---|---|
| core 别名校验器与测试 | `src/main/java/aster/core/embed/UserAliasValidator.java`；`src/test/java/aster/core/embed/UserAliasValidatorTest.java` |
| core / ts 共享 parity 夹具的先例：逐字节相同的两份 JSON | core `src/test/resources/diagnostics/parse_diagnostics_parity.json` + `src/test/java/aster/core/parser/ParseDiagnosticsParityTest.java`；ts `shared/parse_diagnostics_parity.json` |
| ts 别名校验器与测试 | `src/policy-package/alias-validate.ts:18,138`；`test/unit/policy-package/alias-validate.test.ts`；入口 `src/policy-package/compile-source.ts:81-96` |
| ts 的诗示例（绕过校验器） | `examples/jingyesi/jingyesi.mjs`、`jingyesi.layout.mjs`；`test/unit/layout/fixtures.ts:23-48` |
| envelope 共享向量的源头与副本 | aster-lang-test `corpus/shared-vectors/envelope-vectors.json` + `scripts/test-envelope-vectors.mjs`；副本 core `src/test/resources/shared-vectors/envelope-vectors.json`、aster-api `src/test/resources/shared-vectors/envelope-vectors.json`（哈希钉 `src/test/java/io/aster/policy/entity/PolicyVersionEnvelopeTest.java:23-29`）、cloud `src/lib/__fixtures__/envelope-vectors.json` |
| aster-api 批量 What-If 丢 aliasSet | `src/main/java/io/aster/policy/replay/batch/ReplayBatchService.java:896-902`；`PolicyVersionSourceClient.java:61-62`（`VersionSource` 无 aliasSet）；cloud 回源已返回 `aliasSet`（`src/app/api/internal/policy-versions/route.ts:104-131`） |
| aster-api 引擎 pin 两处，由列车 bump | `settings.gradle:17-20`（platform 1.0.51）；`.github/actions/setup-aster-build/action.yml:69`（`v1.0.51`）；ADR 0053 修订 1 |
| cloud 最新迁移 | `drizzle/0068_policy_version_layout.sql` |
| cloud `PolicyVersion` 编译输入列 | `src/db/schema.ts:745-754` |
| cloud 建版本 | `src/services/policy/version-manager.ts:356-405`（参数）、`:520-656`（实现）、`:543`（别名校验）、`:584-601`（封印） |
| cloud 审批冻结 | `version-manager.ts:144-178`（`freezeApprovalVocabulary`）、`:783-806` |
| cloud 四条建版本路由 | `src/app/api/policies/route.ts:286,299`；`src/app/api/policies/[id]/route.ts:161,192-255,312`；`src/app/api/v1/policies/[id]/versions/route.ts:167,179`；`src/app/api/teams/[teamId]/policies/route.ts:158`（不读 aliasSet，本计划不改） |
| cloud 保存词汇 | `src/lib/policy-compile-validator.ts:60-94` |
| cloud 别名占用集（与编译词汇不一致） | `src/lib/structural-alias-grants.ts:22-55` |
| cloud 读版本词汇的唯一层 | `src/lib/execution-input/index.ts:105-180`；resolver 迁移 `src/services/policy/resolver-migration.ts:257-259` |
| cloud 词汇合并工具 | `src/lib/domain-vocabulary-scope.ts:225-259`；团队合并键 `src/lib/domain-vocabulary-team-merge.ts:22-63` |
| cloud 编辑器 | 词汇标签 `src/components/policy/policy-form/vocabulary-tab.tsx`（literal 分组 `:39-45`）；侧栏 `side-panel.tsx:106,280-282,364-369`；本地编译 `use-compile.ts`；LSP 词汇注册 `src/hooks/useUserVocabularyRegistration.ts:93,108-121`；表单 `policy-form/index.tsx:399-402,553-557,665` |
| cloud 审批视图 | `src/components/policy/version-detail-panel.tsx:31-64,309-328`；对比 `version-compare-panel.tsx` |
| cloud 模板 | `src/data/policy-example-showcase.ts`、`src/data/policy-examples.ts:59-65,1123-1127`、测试 `src/__tests__/data/policy-example-showcase.test.ts` |
| cloud 演示（诗的源头） | `src/config/poem-demo.ts:187-235` |

## Global Constraints

- **列车门**：标 🚂 的任务改的是 core / ts 引擎，只能随列车 eco-1.0.52 发布；aster-api 的引擎 pin 只由列车 bump（ADR 0053 修订 1），本计划不手改 pin。标 ⏳ 的任务依赖列车已上线（aster-api 生产跑在 1.0.52、npm 上有 ts-next），合并前在 PR 正文写明核对结果。
- 别名规则 v2 的唯一定义是 ADR §5：结构词加 `FUNC_PRODUCE`；单词别名仅当 locale 主子标签为 `zh`、每个码点在 U+4E00–U+9FFF 或 U+3400–U+4DBF、码点数 2–8；别名不得等于任何字面量触发词；`USER_ALIAS_VALIDATOR_VERSION` / `VERSION` = `"2"`。错误文案「必须是多词短语，或 zh 下 2–8 个汉字的单词」，码 `singleWord` 不变；三处文案逐字相同。
- 共享夹具 `alias_validation_v2_parity.json` 在 core `src/test/resources/diagnostics/` 与 ts `shared/` 两份**逐字节相同**；cloud 测试读 ts 包外的同一份副本 `src/lib/__fixtures__/alias_validation_v2_parity.json`（同样逐字节相同）。
- envelope：`computeSourceEnvelope` 签名、段序与既有 3 条向量不改；只新增向量 `envelope.literal-macro`。
- cloud 迁移先行：`drizzle/*.sql` 只出现在只含迁移的 PR（`schema.ts`、`drizzle/meta/`、`src/__tests__/` 可同 PR）；不用 `migration-and-code` 标签；生产确认应用后才合使用它的代码 PR。
- 内嵌词汇格式、序列化、上限、错误码、三态、合并优先级以 ADR §6 为准：上限 32 条 / `localized` ≤ 32 字符 / 标识符 `canonical`、`parent` ≤ 64 字符 / 字面量内容 ≤ 256 字符 / 序列化 ≤ 8192 字节；错误码 400 `embedded_vocabulary_invalid_format`、`embedded_vocabulary_too_large`、`embedded_vocabulary_invalid`、`embedded_vocabulary_duplicate`、`embedded_vocabulary_shadows_keyword`；执行时封印不一致 409 `vocabulary_seal_mismatch`。
- 内嵌词汇的保存不设能力门、不设套餐门；编辑入口随 `structured_authoring`。
- 模板永不写用户 / 团队词汇（`UserDomainTerm`、`DomainTerm` 在模板应用路径上零写入）。
- 新文案进 `src/i18n/demo-supplement.ts`（en / zh / de / hi）；代码注释简体中文。
- 提交信息结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 正文结尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)` 并引用 wontlost-ltd/aster-cloud#830。

## Review Focus

1. **只改源码、省略 `embeddedVocabulary` 的 PUT**（无能力的队友编辑团队策略）：必须沿用活跃版本的内嵌词条，新版本仍能编译执行（T9 `carries embedded vocabulary forward when the field is omitted`）。
2. **作者事后新增与内嵌词条同形的个人词条**：这条策略的草稿含义不变（内嵌优先），保存响应列出被遮蔽的个人词条；审批后执行用冻结快照，结果仍是 `静夜思`（T8 `embedded term shadows a later author term`、T10 `approved version executes with embedded vocabulary after the author adds a colliding term`）。
3. **库里直接改了 `embeddedVocabulary` 列**：审批与执行 APPROVED 版本时复算的 `scopesJson` 与封印不等，fail-closed（T10 `rejects execution when the embedded column no longer matches the seal`）。
4. **别名与源码里的名字同形**（如参数、字段叫 `低头`，又把 `低头` 设为 `RETURN` 别名）：结构词在标识符位置是软关键字，若无守卫会被一致改名为 `return` 照常编译；必须报 `alias_identifier_collision`，ts 与 core 一致（T3、T4 的位置矩阵）。
5. **cloud 先放宽、aster-api 仍是 1.0.51**：保存被 aster-api 编译门禁拒绝（4xx），不落库（T12 的部署前核对步骤与 `rejected by an upstream v1 validator is surfaced as a compile error` 测试）。

---

## 裁决（ADR 未定之处；用户可推翻）

| # | 问题 | 裁决 | 理由 |
|---|---|---|---|
| PR1 | 跨引擎证明放哪里 | ts 端在 ts 仓（T4）；Java 端在 aster-api（T7，列车后），因为 core 仓没有 zh-CN 词法表（zh-CN 来自 locales-zh SPI jar，aster-api 的类路径才有）；两侧比较 `positionFreeSha256`，ts 端把算出的值写进 aster-api 测试作期望值 | 不在 core 测试里引入 locales 依赖 |
| PR2 | ts 发版号 | 由列车决定（ts-next）；本计划不预设 | ADR 0053 |
| PR3 | 内嵌词汇的工具代码放 cloud 还是 ts 包 | cloud `src/lib/policy-embedded-vocabulary.ts` | 只有 cloud 使用；不必为它多等一班列车 |
| PR4 | 引擎里「字面量先于 / 后于关键词」的顺序统一 | 不在本计划；T3 / T4 在校验器入口拦截，另开 issue（T3 Step 7） | ADR §4.2 T3 |

## PR 顺序

| PR | 仓 | 任务 | 类型 | 门 |
|---|---|---|---|---|
| 1 | cloud | T1 | **只含迁移** | — |
| 2 | aster-lang-test | T2 | 测试资源 | — |
| 3 | aster-api | T5 | 代码（批量 What-If 补 aliasSet + 向量副本） | — |
| 4 | aster-lang-core | T3 | 代码 | 🚂 |
| 5 | aster-lang-ts | T4 | 代码 | 🚂 |
| — | 列车 eco-1.0.52 | T6 | 发布（含 aster-api pin bump） | 🚂 |
| 6 | aster-api | T7 | 测试 | ⏳ |
| 7 | cloud | T8 + T10 | 代码（内嵌词汇库 + 读路径；还没有写入方） | PR 1 已在生产应用 |
| 8 | cloud | T9 | 代码（写入：建版本与路由） | PR 7 |
| 9 | cloud | T11 | 代码（编辑器、审批视图） | PR 8 |
| 10 | cloud | T12 | 代码（别名 v2、ts-next） | ⏳ |
| 11 | cloud | T13 | 代码（原诗模板） | PR 9、PR 10 |

PR 7 先于 PR 8：读路径先能处理非空列，写入方后上；反过来会出现能写不能读的窗口。PR 2、3、7–9 与列车互不依赖，可以并行推进。

---

### Task 1: cloud 迁移 `PolicyVersion.embeddedVocabulary`（PR 1，只含迁移）

**Files:**
- Create: `drizzle/0069_policy_version_embedded_vocabulary.sql`
- Modify: `src/db/schema.ts:745-754`（`aliasSet` 旁加列）、`drizzle/meta/_journal.json` 与快照
- Test: `src/__tests__/db/policy-version-embedded-vocabulary.integration.test.ts`

**Interfaces:**
- Produces: 列 `PolicyVersion."embeddedVocabulary" text NULL`，CHECK `PolicyVersion_embeddedVocabulary_size_check`：`"embeddedVocabulary" IS NULL OR octet_length("embeddedVocabulary") <= 8192`；drizzle 字段 `embeddedVocabulary: text('embeddedVocabulary')`

- [ ] **Step 1: 写失败的集成测试** `accepts NULL and an 8192-byte value, rejects 8193 bytes`：插一行版本，`embeddedVocabulary` 分别为 NULL、`'x'.repeat(8192)` 成功，`'x'.repeat(8193)` 抛出约束名 `PolicyVersion_embeddedVocabulary_size_check`。
- [ ] **Step 2: 运行确认失败**：`pnpm vitest run --config vitest.integration.config.ts src/__tests__/db/policy-version-embedded-vocabulary.integration.test.ts` → FAIL（列不存在）。
- [ ] **Step 3: 写迁移与 schema**（`ALTER TABLE "PolicyVersion" ADD COLUMN "embeddedVocabulary" text;` + `ADD CONSTRAINT … CHECK …`），`pnpm db:generate` 核对快照无多余差异。注释写明「ADR 0060 §6.2：版本内嵌词汇，确定性序列化，建版本时写入、之后不改」。
- [ ] **Step 4: 运行确认通过**；`pnpm exec tsc --noEmit` 无错。`node scripts/ci/check-migration-split.mjs` 通过。
- [ ] **Step 5: 提交并开 PR**（正文写「纯新增可空列，旧代码不读，无迁移窗口问题」）。

### Task 2: aster-lang-test 新增 envelope 向量 `envelope.literal-macro`（PR 2）

**Files:**
- Modify: `corpus/shared-vectors/envelope-vectors.json`、`corpus/shared-vectors/README.md`
- Test: `scripts/test-envelope-vectors.mjs`

**Interfaces:**
- Produces: 向量 `{ id: "envelope.literal-macro", content: <§1.6 源码>, aliasSetJson: <ADR §8 的 aliasSet 规范 JSON>, locale: "zh-CN", toolchainId: <沿用既有向量的固定串>, vocabulary: { structs:[{localized:"望明月",canonical:"Text",kind:"struct"}], literals:[{localized:"思故乡",canonical:"静夜思",kind:"literal"}], fields:[], functions:[], enumValues:[] }, scopesJson, envelopeSha256 }`

- [ ] **Step 1: 加向量**，`scopesJson` 用 ts `canonicalScopesJson(buildVocabularyScopes(vocabulary))` 生成，`envelopeSha256` 用 ts 与 Java 各算一次，两值相同才写入（Java 用 aster-api `PolicyVersion.computeSourceEnvelope` 的 jshell 或临时测试）。
- [ ] **Step 2: 运行** `node scripts/test-envelope-vectors.mjs` → 4 条全过。
- [ ] **Step 3: 提交并开 PR**。下游副本由 T3（core）、T5（aster-api）、T8（cloud）各自同步，并更新各自的哈希钉。

### Task 3: 🚂 core `UserAliasValidator` v2（PR 4）

**Files:**
- Modify: `src/main/java/aster/core/embed/UserAliasValidator.java:42,85-93,210-214,234-243`
- Create: `src/test/resources/diagnostics/alias_validation_v2_parity.json`、`src/test/java/aster/core/embed/AliasValidationV2ParityTest.java`
- Modify: `src/test/java/aster/core/embed/UserAliasValidatorTest.java`、`src/test/resources/shared-vectors/envelope-vectors.json`（T2 副本）

**Interfaces:**
- Produces: `VERSION = "2"`；`STRUCTURAL_KINDS` 含 `FUNC_PRODUCE`；`static boolean isHanSingleToken(String norm, String locale)`（包内可见，供测试）；`vocabularyHasWritten` 含 `scopes.literals()`；IR 名字守卫 `AliasIdentifierGuard.check(Module ast, Map<SemanticTokenKind,List<String>> aliasSet, String locale)`（新文件 `src/main/java/aster/core/embed/AliasIdentifierGuard.java`，在 `InProcessCnlParser.parseWithUserAliases` 解析后、降级前调用，命中抛 `CnlParseException`，诊断码 `alias_identifier_collision`）；该码按 ADR 0031 登记（core `ErrorCode` + locales 副本与 zh / de / hi 译文；号码与 T4 在 ts `shared/error_codes.json` 登记的同号）
- 夹具形状（T4、T12 同用）：`{ "version": 2, "cases": [ { "id": string, "locale": string, "allowStructural": boolean, "aliasSet": {kind: [alias]}, "vocabulary": DomainVocabulary | null, "valid": boolean, "codes": string[] } ] }`，`codes` 取 cloud `AliasIssueCode` 名（`singleWord`、`structuralNotGranted`、`kindNotAllowed`、`collidesVocabulary`、`shadowsKeyword`…）；core 按错误消息前缀映射到码

- [ ] **Step 1: 写夹具**，至少这些用例（`id`：期望）：
  `zh-han-2`（zh-CN，`RETURN:["低头"]`，授权）valid；`zh-han-1`（`"头"`）`singleWord`；`zh-han-9`（9 个汉字）`singleWord`；`zh-ext-b`（含 U+20000）`singleWord`；`zh-kana`（`"かな"`）`singleWord`；`en-han`（en-US + `"低头"`）`singleWord`；`zh-latin-single`（zh-CN + `"answer"`）`singleWord`；`zh-mixed`（`"低头x"`）`singleWord`；`func-produce-granted` valid；`func-produce-ungranted` `structuralNotGranted`；`poem`（ADR §8 aliasSet，授权，词汇为 §8 两条）valid；`alias-eq-literal`（`RETURN:["思故乡"]` + 字面量 `思故乡`）`collidesVocabulary`；`alias-eq-struct`（`RETURN:["望明月"]` + struct `望明月`）`collidesVocabulary`；`latin-multi` 不变 valid；`latin-single`（`TIMES:["scaled"]`）`singleWord`；`logic-kind`（`AND:["并且 也"]`，授权）`kindNotAllowed`。
- [ ] **Step 2: 写 `AliasValidationV2ParityTest`**：逐条用 `UserAliasValidator.validate(aliasSet, locale, scopesOf(vocabulary), allowStructural)` 断言 `valid` 与码集合；并断言 `VERSION.equals("2")`。另在 `UserAliasValidatorTest` 加 `hanSingleTokenCountsCodePointsNotChars`（`"𠀀𠀁"` 两个码点但为扩展 B → 拒）。
- [ ] **Step 2b: 写 IR 名字守卫的位置矩阵测试** `AliasIdentifierGuardTest`（共享源码放进同一夹具的 `guardCases` 数组，T4 复用）：zh-CN，aliasSet `RETURN:["低头"]`（授权），分别把 `低头` 写在参数名、`令 … 为` 局部绑定、`定义 … 包含` 字段名、成员访问 `x.低头`、构造字段、规则名、模块名段、类型别名名、导入别名、调用目标、枚举成员——每例都断言编译失败且码为 `alias_identifier_collision`；对照例：同一源码去掉 aliasSet 编译成功；诗本身通过；多词别名（`RETURN:["answer it"]`）不触发守卫；源码里出现英文名字 `return` 且 aliasSet 有汉字 `RETURN` 别名 → 拒绝（已知代价，ADR §5.3）。
- [ ] **Step 3: 运行确认失败**：`./gradlew test --tests 'aster.core.embed.*'` → FAIL。
- [ ] **Step 4: 实现** ADR §5.1–5.3（含 IR 名字守卫：遍历 AST 收集名字，折叠后与 K 求交；K 取 en-US lexicon 中该 kind 的规范拼写与官方别名）；单词判定改为 `!(norm.indexOf(' ') >= 0 || isHanSingleToken(norm, locale))`；locale 判定 `locale != null && locale.toLowerCase(Locale.ROOT).replace('_','-').matches("zh(-.*)?")`；码点遍历用 `norm.codePoints()`。更新 `VERSION` 的注释与类注释里的「铁律 2」描述（引用 ADR 0060 §5.2）。
- [ ] **Step 5: 同步 T2 的 envelope 向量副本**并更新 core 内读取它的测试（若有哈希钉一并更新）。
- [ ] **Step 6: 运行** `./gradlew test` 全绿（记执行的测试数）。
- [ ] **Step 7: 提交并开 PR**；另开 issue「ts 与 core 对字面量触发词 / 关键词的处理顺序不一致」（引用 ADR §4.2 T3、`Canonicalizer.java:732-741`、ts `canonicalizer.ts:601-604`），PR 正文引用它。

### Task 4: 🚂 ts `alias-validate.ts` v2 与 ts 端诗证明（PR 5）

**Files:**
- Modify: `src/policy-package/alias-validate.ts:18,40,138`（及碰撞检查处）
- Create: `shared/alias_validation_v2_parity.json`（T3 夹具的逐字节副本）、`test/unit/policy-package/alias-validation-v2-parity.test.ts`、`test/unit/policy-package/jingyesi-policy.test.ts`

**Interfaces:**
- Consumes: T3 夹具
- Produces: `USER_ALIAS_VALIDATOR_VERSION = '2'`；`isHanSingleToken(norm: string, locale: string): boolean`（导出）；`checkAliasIdentifiers(ast: Module, aliasSet: AliasSet | null, locale: string): string[]`（新文件 `src/policy-package/alias-identifier-guard.ts`，`compileSource` 在解析后调用，非空即 `CompileFailure`）；`shared/error_codes.json` 新登记 `alias_identifier_collision`；常量 `JINGYESI_POSITION_FREE_SHA256`（写在测试里，T7 复用其值）

- [ ] **Step 1: 写 parity 测试**：读 `shared/alias_validation_v2_parity.json`，逐条 `validateAliasSet(aliasSet, { base: lexiconForLocale(locale), allowStructural, vocabulary: vocabulary && buildVocabularyScopes(vocabulary) })`，断言 `errors.length === 0` 与 `valid` 一致、每个期望码对应的消息前缀出现；另测试断言夹具文件与 core 副本的 sha256（写死在测试里，T3 合入后的值）相同。
- [ ] **Step 2: 写诗测试** `jingyesi-policy.test.ts`：
  - `compiles with zh-CN, the v2 aliasSet and the embedded vocabulary`：`compileSource(SOURCE, { locale: 'zh-CN', aliasSet: ALIASES, allowStructural: true, vocabulary: VOCAB })` 不抛；
  - `evaluates 地上霜 to 静夜思`；
  - `is rejected without the structural grant`（`allowStructural: false` → `CompileFailure`，消息含 `FUNC_PRODUCE`）；
  - `alias equal to a name in the source fails with alias_identifier_collision`：跑 T3 夹具 `guardCases` 全部用例，结果与 core 一致；
  - `positionFreeSha256 is stable`：断言等于 `JINGYESI_POSITION_FREE_SHA256`（首次运行取值写入）。
  - `SOURCE`、`ALIASES`、`VOCAB` 取 ADR §8 的值，`VOCAB` 为 `DomainVocabulary` 形状（`id: 'policy.embedded'`、`locale: 'zh-CN'`）。
- [ ] **Step 3: 运行确认失败**：`pnpm run test:unit` → FAIL。
- [ ] **Step 4: 实现**，与 T3 逐条对应；文案与 core 逐字相同。
- [ ] **Step 5: 运行** `pnpm test` 全绿（含 package-v1）。
- [ ] **Step 6: 提交并开 PR**。

### Task 5: aster-api 批量 What-If 带 aliasSet + 向量副本（PR 3）

**Files:**
- Modify: `src/main/java/io/aster/policy/replay/batch/PolicyVersionSourceClient.java:55-70`（`VersionSource` 加 `String aliasSetJson`，解析 cloud 回源的 `aliasSet`）、`ReplayBatchService.java:896-902`
- Modify: `src/test/resources/shared-vectors/envelope-vectors.json`、`src/test/java/io/aster/policy/entity/PolicyVersionEnvelopeTest.java:23-29`
- Test: `src/test/java/io/aster/policy/replay/batch/ReplayBatchServiceAliasTest.java`、`PolicyVersionSourceClientTest`（既有则扩展）

**Interfaces:**
- Produces: `record VersionSource(String versionId, String policyId, int version, String content, Map<String,Object> vocabulary, Map<String,List<String>> aliasSet)`；保留 4 参与 5 参构造（aliasSet 为空 Map）

- [ ] **Step 1: 写失败测试** `batchReplayCompilesAVersionThatNeedsItsAliasSet`：桩回源返回运算符别名源码（`PLUS: ["接 着"]`，取 cloud #829 模板源码）与其 aliasSet，批量回放结果为成功而非编译错误；`sourceClientParsesAliasSet`：回源 JSON 带 `aliasSet` 时解析进 record，缺省时为空 Map。
- [ ] **Step 2: 运行确认失败**：`./gradlew --offline :test --tests '*ReplayBatchServiceAlias*' --tests '*PolicyVersionSourceClient*'` → FAIL。
- [ ] **Step 3: 实现**：`ReplayBatchService` 把 `target.aliasSet()` 传给 `replayExecutor.execute`，`aliasesTrusted` 仍为 `true`（回源走 HMAC 签名的内部接口，冻结快照）。
- [ ] **Step 4: 同步向量副本**，更新 `VECTORS_SHA256`；`PolicyVersionEnvelopeTest` 4 条全过。
- [ ] **Step 5: 运行** aster-api 本地全量（`aster-local-verification-env` 的 podman PG/Redis 配方），记执行的测试数；已知基线失败照旧。
- [ ] **Step 6: 提交并开 PR**。

### Task 6: 🚂 列车 eco-1.0.52

**Files:** 无代码改动（运维任务）。

- [ ] **Step 1: gate scan**（skill `aster-gate-scan`）：导出生产策略源码，用候选引擎（含 T3）重编译；另外专门查「aliasSet 中任一别名 = 该版本词汇的字面量触发词」的版本（cloud 版本与 aster-api `policy_versions.vocabulary_json`）。期望：0 条。非 0 → 停，回到 ADR §5.3。
- [ ] **Step 2: 跑列车**（platform `scripts/release-plan/run-train.sh`），发布 core / ts，列车 bump aster-api pin 到 1.0.52 并部署。
- [ ] **Step 3: 列车 runbook 记录 ADR §5.5 第 5 条**：cloud T12 合入后 aster-api 不得回退到 1.0.51 以下。
- [ ] **Step 4: 核对**：生产 aster-api 的工具链身份含 `validator=2`；`npm view @aster-cloud/aster-lang-ts version` = ts-next。

### Task 7: ⏳ aster-api 诗的 Java 端证明（PR 6）

**Files:**
- Test: `src/test/java/io/aster/policy/rest/JingyesiPolicyCompileTest.java`
- Modify: `scripts/ci/verify-sibling-ref-pin.sh`（引擎下限 `MIN_ENGINE_VERSION=1.0.52`，pin 低于它即失败，ADR §5.5 第 5 条）

**Interfaces:**
- Consumes: T4 的 `JINGYESI_POSITION_FREE_SHA256` 值；ADR §8 的源码、aliasSet、词汇

- [ ] **Step 1: 写测试**（`@QuarkusTest`，HMAC 签名请求，沿用 `CompileStructuralAliasHmacTest` 的签名夹具）：
  - `compileReturnsNoErrors`：`POST /api/v1/policies/compile`，`{source, locale:"zh-CN", aliasSet, allowStructural:true, vocabulary}` → 无 error 诊断；
  - `evaluateSourceReturnsJingyesi`：`POST /api/v1/policies/evaluate-source`，`functionName:"地上霜"` → 结果 `"静夜思"`；
  - `unsignedCallerCannotUseTheStructuralAliases`：不带 HMAC → 编译失败，消息含 `FUNC_PRODUCE` 或结构词授权文案；
  - `coreIrMatchesTs`：取编译得到的 Core IR 算 position-free sha256，等于 T4 的值。
- [ ] **Step 2: 运行** `./gradlew --offline :test --tests '*JingyesiPolicyCompileTest*'`（列车后的 pin）→ PASS；在 1.0.51 上应 FAIL（PR 正文记录）。
- [ ] **Step 3: 下限检查**：把 `settings.gradle` 的 pin 临时改为 1.0.51 运行 `scripts/ci/verify-sibling-ref-pin.sh` → 非零退出、消息含 `1.0.52`；改回后 → 0。
- [ ] **Step 4: 提交并开 PR**。

### Task 8: cloud 内嵌词汇库（PR 7）

**Files:**
- Create: `src/lib/policy-embedded-vocabulary.ts`
- Test: `src/__tests__/lib/policy-embedded-vocabulary.test.ts`
- Modify: `src/lib/__fixtures__/envelope-vectors.json`（T2 副本）及读取它的测试

**Interfaces:**
- Produces:
  - `type EmbeddedTermKind = 'struct' | 'field' | 'function' | 'enum_value' | 'literal'`
  - `interface EmbeddedTerm { kind: EmbeddedTermKind; localized: string; canonical: string; parent?: string }`
  - `interface EmbeddedVocabulary { v: 1; terms: EmbeddedTerm[] }`
  - `const EMBEDDED_VOCABULARY_LIMITS = { maxTerms: 32, maxLocalized: 32, maxCanonical: 64, maxLiteral: 256, maxBytes: 8192 } as const`
  - `class EmbeddedVocabularyError extends Error { code: EmbeddedVocabularyErrorCode; detail?: string }`，码为 ADR §6.4 五个
  - `parseEmbeddedVocabulary(input: unknown): EmbeddedVocabulary`（格式错抛 `embedded_vocabulary_invalid_format`）
  - `serializeEmbeddedVocabulary(ev: EmbeddedVocabulary): string | null`（空 → null）
  - `toDomainVocabulary(ev: EmbeddedVocabulary, locale: string): DomainVocabulary`
  - `validateEmbeddedVocabulary(ev: EmbeddedVocabulary, ctx: { locale: string; aliasSet: AliasSet | null }): void`（按 §6.4 顺序：too_large → duplicate → invalid（引擎 `validateVocabulary`）→ shadows_keyword（`extractReservedAliasSets(getLexicon(locale))` 加本版本 aliasSet 的归一别名））
  - `mergeEmbeddedVocabulary(embedded: DomainVocabulary | null, lower: DomainVocabulary | null, sources?: Map<IdentifierMapping, 'team' | 'author'>): { vocabulary: DomainVocabulary | null; shadowed: ShadowedTerm[] }`，`ShadowedTerm = { kind: string; localized: string; canonical: string; source: 'team' | 'author' }`
  - `withEmbeddedVocabulary(lower: DomainVocabulary | null, embeddedText: string | null, locale: string): DomainVocabulary | null`（读路径用：解析已存文本 + 合并；存量文本解析失败抛 `EmbeddedVocabularyError`，不退化）

- [ ] **Step 1: 写失败测试**：
  - `serializes deterministically regardless of input order and NFC form`（两种顺序、`'思故乡'` 的 NFD 输入 → 同一字节串，等于 ADR §8 的 `embeddedVocabulary` 字面值）；
  - `empty terms serialize to null`；
  - `rejects unknown keys, unknown kinds, missing parent on field, parent on struct` → `embedded_vocabulary_invalid_format`；
  - `enforces every limit`（33 条、33 字符写法、65 字符规范名、257 字符字面量、序列化 8193 字节）→ `embedded_vocabulary_too_large`；
  - `rejects a literal containing a quote or a newline` → `embedded_vocabulary_invalid`；`rejects a non-ASCII canonical for struct` → 同；
  - `rejects two terms with the same role key, and a duplicate trigger` → `embedded_vocabulary_duplicate`；
  - `rejects a written form equal to 返回, to a base alias, or to an alias in the version aliasSet` → `embedded_vocabulary_shadows_keyword`；
  - `embedded term shadows a later author term`：下层有作者 struct `望明月 → Moon`，合并后只剩内嵌 `Text`，`shadowed` 含 `{localized:'望明月', canonical:'Moon', source:'author'}`；
  - `an embedded literal trigger drops a lower term of any role with the same written form`；
  - `identical lower term is deduplicated, not reported`；
  - `vocabularyScopesJson of the poem vocabulary equals the envelope.literal-macro vector scopesJson`（读 T2 副本）。
- [ ] **Step 2: 运行确认失败**：`pnpm vitest run src/__tests__/lib/policy-embedded-vocabulary.test.ts` → FAIL。
- [ ] **Step 3: 实现**。合并键复用 `domain-vocabulary-team-merge.ts` 的 `writtenKeys` 口径（把它从文件内导出，不复制）；字面量与任何角色冲突。合并结果沿用 `mergeVocabularies` 输出 locale 的约定。
- [ ] **Step 4: 运行通过**；`pnpm exec tsc --noEmit`。
- [ ] **Step 5: 提交**（与 T10 同一 PR）。

### Task 9: cloud 写入：建版本、路由三态、别名占用集（PR 8）

**Files:**
- Modify: `src/services/policy/version-manager.ts:356-405,520-656`；`src/lib/policy-compile-validator.ts:60-94`；`src/lib/structural-alias-grants.ts:22-55`；`src/app/api/policies/route.ts`；`src/app/api/policies/[id]/route.ts:161-255`；`src/app/api/v1/policies/[id]/versions/route.ts`；`src/scripts/seed-agent-guard-demo.ts:169`、`src/scripts/seed-credit-pilot.ts:170`（只适配新签名）
- Test: `src/__tests__/services/version-manager-embedded-vocabulary.integration.test.ts`、`src/__tests__/api/policies-embedded-vocabulary.test.ts`

**Interfaces:**
- Consumes: T8 全部导出；T1 的列
- Produces:
  - `loadSaveVocabulary(userId, policy, opts: { teamId?: string | null; embedded?: EmbeddedVocabulary | null })` 返回 `{ vocabulary, scopesJson, shadowed: ShadowedTerm[] }`（`vocabulary` 为合并后的有效词汇，编译门禁与封印共用）
  - `createVersion` 新参数 `embeddedVocabulary?: EmbeddedVocabulary | null`，写入 `serializeEmbeddedVocabulary` 的结果；返回值加 `vocabularyShadowed: ShadowedTerm[]`
  - `buildAliasReservedFromVocabulary(locale: string, vocabulary: DomainVocabulary | null): ReservedSets`（取代 `buildAliasReservedForUser`；`vocabularyTermsLower` 含所有角色写法、别名与字面量触发词，均经 `normalizeAliasToken`）
  - 路由响应字段 `vocabularyShadowed`

- [ ] **Step 1: 写失败测试**（集成，PG）：
  - `stores the serialized embedded vocabulary on the version`；
  - `seals scopesJson from author vocabulary plus embedded terms`（等于 `vocabularyScopesJson(mergeEmbeddedVocabulary(...).vocabulary)`）；
  - `envelope is byte-identical to a version whose user vocabulary has the same effective terms`（同源码 / 别名 / 有效词汇 → 同 `sourceEnvelopeSha256`，证明不加段）；
  - `carries embedded vocabulary forward when the field is omitted`（PUT 只改 content）；`null clears it`；`changing only embedded vocabulary creates a new version`；`same embedded vocabulary creates no version`；
  - `POST and v1 versions treat omission as none`；
  - `rejects each embedded_vocabulary_* code with 400`；
  - `alias reserved set contains embedded literal triggers`（单元：`buildAliasReservedFromVocabulary('zh-CN', 诗的有效词汇).vocabularyTermsLower` 含 `思故乡` 与 `望明月`）。v1 下别名必含空格而触发词是单个 token，二者不可能同形，端到端的「别名 = 触发词」用例在 T12；
  - `alias reserved set uses team terms of a team policy and ignores author terms of other locales`；
  - `saving needs neither structured_authoring nor customLexicon`；
  - `response lists vocabularyShadowed`。
- [ ] **Step 2: 运行确认失败**：`pnpm vitest run --config vitest.integration.config.ts src/__tests__/services/version-manager-embedded-vocabulary.integration.test.ts` 与单元测试 → FAIL。
- [ ] **Step 3: 实现**：路由按 ADR §6.5 三态解析（PUT 的「省略」读活跃版本的 `embeddedVocabulary` 文本再 `parseEmbeddedVocabulary`），在事务外先 `parseEmbeddedVocabulary` + `validateEmbeddedVocabulary`（错误 → 400 `{ error, code, detail }`），再 `loadSaveVocabulary(…, { embedded })`；PUT 的变化判定加入 `serializeEmbeddedVocabulary` 比较；`aliasReserved` 改为 `buildAliasReservedFromVocabulary(locale, saveVocabulary.vocabulary)`；删除 `buildAliasReservedForUser` 及其调用。
- [ ] **Step 4: 运行通过**；`pnpm exec tsc --noEmit`；`pnpm test:run` 中涉及版本创建的既有测试全绿。
- [ ] **Step 5: 提交并开 PR**（合并前确认 PR 7 已在生产）。

### Task 10: cloud 读路径与封印复核（PR 7，随 T8）

**Files:**
- Modify: `src/lib/execution-input/index.ts:105-180`（`loadVersionVocabularyStrict`、`loadVersionExecutionVocabulary` 的 `target` 加 `embeddedVocabulary: string | null`、`sourceScopesJson`、`status`）；所有构造 `target` 的调用方（`policies/[id]/execute/route.ts:380`、`v1/policies/[id]/execute/route.ts:333`、`executions/[execId]/verify-parity/route.ts:110`、`rule-regression-runner.ts:965`、`execution-input/masked-log-input.ts:86`、`loadStoredVersionCompileContext` 的调用方 `internal/policy-versions/route.ts:106`、`cedar-export-input.ts:42`、`guard-policy-deploy.ts:297`）；`src/services/policy/version-manager.ts:144-178`；`src/services/policy/resolver-migration.ts:257-259`
- Test: `src/__tests__/lib/execution-input-embedded.integration.test.ts`、`src/__tests__/services/version-manager-approve.test.ts`（扩展）

**Interfaces:**
- Consumes: T8 `withEmbeddedVocabulary`
- Produces: `class VocabularySealMismatchError extends Error`（路由映射 409 `vocabulary_seal_mismatch`）

- [ ] **Step 1: 写失败测试**：
  - `execution vocabulary of a draft includes embedded terms`；
  - `approved version executes with embedded vocabulary after the author adds a colliding term`（审批后作者新增 `望明月 → Moon`，执行仍得 `静夜思` 的词汇，即合并结果含 `Text`）；
  - `approval freeze compares frozen lower vocabulary plus embedded with the seal`（相等通过；把内嵌列直接改库后审批 → `VocabularyChangedSinceSaveError`）；
  - `rejects execution when the embedded column no longer matches the seal`（APPROVED 版本改库 → 409 `vocabulary_seal_mismatch`）；
  - `versions without embedded vocabulary behave exactly as before`（既有执行与审批测试不改即通过）；
  - `package export, cedar export, guard deploy and the internal What-If source receive the merged vocabulary`（桩 `policy-api` 客户端，断言请求体 `vocabulary.literals` 含 `思故乡`）；
  - `resolver migration compiles with embedded terms`。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**：在 `execution-input/index.ts` 两个函数返回前调用 `withEmbeddedVocabulary`；APPROVED / DEPRECATED 且内嵌非空时比较 `vocabularyScopesJson(结果)` 与 `sourceScopesJson`（`sourceScopesJson` 为 NULL 的旧行不比较），不等抛 `VocabularySealMismatchError`；`freezeApprovalVocabulary` 比较前合并；resolver 迁移两条分支都合并。用 `grep -rn "loadVocabularyForExecution\|loadLiveVocabularyForPolicy" src` 确认除这两层与保存外没有别的读取点。
- [ ] **Step 4: 运行通过**；`pnpm exec tsc --noEmit`；`pnpm test:run`、`pnpm test:integration` 全绿。
- [ ] **Step 5: 提交并开 PR 7**（T8 + T10；正文写「此 PR 后列仍恒为 NULL，读路径对 NULL 无行为变化」）。

### Task 11: cloud 编辑器与审批视图（PR 9）

**Files:**
- Modify: `src/components/policy/policy-form/vocabulary-tab.tsx`、`side-panel.tsx`、`policy-form/index.tsx`（草稿状态与保存载荷）、`use-policy-draft.ts`（草稿 schema 加字段，版本号 +1）、`use-compile.ts`、`src/hooks/useUserVocabularyRegistration.ts`、`src/components/policy/version-detail-panel.tsx`、`version-compare-panel.tsx`、`src/app/api/policies/[id]/route.ts`（GET 返回活跃版本 `embeddedVocabulary`）、版本详情接口、`src/i18n/demo-supplement.ts`
- Create: `src/components/policy/policy-form/embedded-vocabulary-editor.tsx`、`src/components/policy/embedded-vocabulary-map.tsx`
- Test: 同名 `*.test.tsx`；`src/__tests__/e2e-browser/embedded-vocabulary.spec.ts`

**Interfaces:**
- Consumes: T8 类型与 `parseEmbeddedVocabulary` / `validateEmbeddedVocabulary`（客户端只从 `@/lib/policy-embedded-vocabulary` 导入，该文件不得导入 Node 内建与服务端模块）
- Produces: `<EmbeddedVocabularyEditor value onChange locale aliasSet readOnly />`；`<EmbeddedVocabularyMap embeddedVocabularyJson />`

- [ ] **Step 1: 写失败的组件测试**：
  - 编辑器：增删改五种 kind；field / enum_value 必须选 parent；literal 行显示「展开为字符串」与展开值；本地校验错误按码显示本地化文案；`readOnly` 时无输入控件；
  - 词汇标签页：有能力时出现「本策略」分组；被内嵌遮蔽的下层词条带「被本策略覆盖」标记；
  - 无能力：编辑器 DOM 与今天逐字节一致（既有快照不变），仅当策略有内嵌词条时在别名面板上方显示只读列表；
  - `use-compile` 与 LSP 注册使用合并后的有效词汇（桩断言传入的 `literals`）；
  - 版本详情：`EmbeddedVocabularyMap` 在别名映射下方，字面量行有标记；无内嵌词汇时 DOM 与今天一致（快照）；
  - 版本对比：内嵌词汇变化单列一行「内嵌词汇已变更」。
- [ ] **Step 2: 运行确认失败**：`pnpm vitest run src/components/policy` → FAIL。
- [ ] **Step 3: 实现**；保存载荷：有能力且编辑过 → 发对象；未编辑 → 省略（PUT 沿用）；用户清空 → 发 `null`。保存响应的 `vocabularyShadowed` 以提示条显示。
- [ ] **Step 4: 写并运行 e2e**（本地栈）：建策略 → 加内嵌字面量 `思故乡 → 静夜思` 与运算符别名 → 保存 → 版本详情显示映射 → 执行结果含 `静夜思`。
- [ ] **Step 5: 运行** `pnpm exec tsc --noEmit`、`pnpm test:run` 全绿。
- [ ] **Step 6: 提交并开 PR**。

### Task 12: ⏳ cloud 别名规则 v2（PR 10）

**Files:**
- Modify: `package.json:67`（→ ts-next）、`pnpm-lock.yaml`、`src/lib/policy-alias-shared.ts:42-53,228,265-266`、`src/lib/policy-alias.ts:55-61`（toolchainId 随常量自动变化，核对即可）、`src/components/policy/policy-alias-panel.tsx`（提示文案）、`src/i18n/demo-supplement.ts`
- Create: `src/lib/__fixtures__/alias_validation_v2_parity.json`（T3 夹具逐字节副本）、`src/__tests__/lib/policy-alias-v2-parity.test.ts`
- Modify: `src/__tests__/lib/policy-alias.test.ts`、`policy-alias-structural.test.ts`、`policy-alias-issues.test.ts`（版本号与单词用例）

**Interfaces:**
- Consumes: T3 夹具；T9 `buildAliasReservedFromVocabulary`
- Produces: `USER_ALIAS_VALIDATOR_VERSION = '2'`；`validateUserAliases(aliasSet, reserved, options: { allowStructural?: boolean; locale?: string })`（新增 `locale`，缺省视为非 zh——调用方必须传）；`isHanSingleToken(norm: string, locale: string): boolean`

- [ ] **Step 1: 部署前核对**（写进 PR 正文）：生产 aster-api 工具链身份含 `validator=2`；ts-next 已在 npm。任一不满足不得合并。
- [ ] **Step 2: 写失败测试**：parity 测试逐条跑夹具（码集合一致）；`fixture is byte-identical to the core copy`（sha256 写死）；`toolchainId contains validator=2`；`alias equal to an embedded literal trigger is rejected as collidesVocabulary`（zh-CN，`RETURN:["思故乡"]` + 内嵌字面量 `思故乡`，经 `createVersion` → 400）；`rejected by an upstream v1 validator is surfaced as a compile error`（桩 aster-api 返回 4xx 别名错误 → `PolicyCompileError`，不落库）。
- [ ] **Step 3: 运行确认失败** → FAIL。
- [ ] **Step 4: 实现**，与 T3 逐条对应；三个调用点（`version-manager.ts:543`、`policy-alias-panel.tsx:58`、`policy-form/index.tsx:665`）传 `locale`；前端占用集也改用当前有效词汇（编辑器已有，T11）。`STRUCTURAL_KINDS` 的注释更新为 ADR 0060 §5.1。
- [ ] **Step 5: 运行** `pnpm exec tsc --noEmit`、`pnpm test:run`、`pnpm test:integration` 全绿。
- [ ] **Step 6: 提交并开 PR**。

### Task 13: cloud 原诗模板替换 #829（PR 11）

**Files:**
- Modify: `src/data/policy-example-showcase.ts`（整体替换）、`src/data/policy-examples.ts:59-65`（`PolicyExample` 加 `embeddedVocabulary?: EmbeddedVocabulary`）、`policy-form/index.tsx:553-557`（应用模板时设置内嵌词汇）
- Modify: `src/__tests__/data/policy-example-showcase.test.ts`（整体改写）
- Create: `src/__tests__/e2e/jingyesi-template.spec.ts`

**Interfaces:**
- Consumes: T8–T12
- Produces: 模板 `showcase-jingyesi`（id 不变）：`cnlLocale: 'zh-CN'`；`source` = `poem-demo.ts` 的 `POEM_ZH.source`；`aliasSet` = ADR §8；`embeddedVocabulary` = ADR §8；`layout` = `JINGYESI_LAYOUT`（从 `poem-demo.ts` 导出共用，不复制）；`requires: { structuredAuthoring: true, structuralAliases: true }`

- [ ] **Step 1: 改写测试**（替换现有 9 条）：
  - `source is the original poem, byte-identical to the demo`（`=== POEM_ZH.source`）；
  - `display reads as the poem`（`toDisplay(layout) === '床前明月光，\n疑是地上霜。\n举头望明月，\n低头思故乡。'`）；
  - `requires structured authoring and the structural alias grant`；
  - `aliasSet passes validateUserAliases v2 for zh-CN with the grant, and fails without it`（无授权码 `structuralNotGranted`，含 `FUNC_PRODUCE`）；
  - `embedded vocabulary is valid and serializes to the ADR value`；
  - `compiles with zh-CN plus aliasSet plus embedded vocabulary, and fails if either is removed`（ts `compileSource`）；
  - `evaluates 地上霜 to 静夜思`；
  - `layout passes validatePolicyLayout with zh-CN plus the frozen aliasSet`；
  - `applying the template writes no user or team vocabulary`（桩 db，断言对 `UserDomainTerm` / `DomainTerm` 零写入）。
- [ ] **Step 2: 运行确认失败** → FAIL。
- [ ] **Step 3: 实现**；文件头注释改写：说明原诗即源码、别名为结构词需授权、内嵌词汇两条、layout 为何仍需要（ADR §8）；删掉「不含领域词汇」那段。
- [ ] **Step 4: e2e**（本地栈：cloud + aster-api 1.0.52 镜像，不桩 aster-api）：
  - `template saves, approves and executes to 静夜思`：有两项能力的用户应用模板 → 保存 → 审批（另一用户）→ 审批后作者新增个人词条 `望明月 → Moon` → 经 `POST /api/policies/[id]/execute`（真实转发到 aster-api `evaluate-source`）执行 → 输出 `静夜思`；
  - `batch What-If on the approved version returns 静夜思`：以该版本为目标发起批量 What-If，aster-api 回源拿到 aliasSet（T5）与合并词汇（T10），结果为 `静夜思`；
  - `review shows aliases and embedded terms`：版本详情显示四条别名映射与两条内嵌词条（字面量行有标记）；
  - `locked without the structural alias grant`：无结构词授权的用户看到模板锁定。
- [ ] **Step 5: 运行** `pnpm exec tsc --noEmit`、`pnpm test:run` 全绿；e2e 通过。
- [ ] **Step 6: 提交并开 PR**（关闭 cloud#830）。

---

## 本地验证命令汇总

| 仓 | 命令 |
|---|---|
| aster-lang-core | `./gradlew test` |
| aster-lang-ts | `pnpm test` |
| aster-lang-test | `node scripts/test-envelope-vectors.mjs` |
| aster-api | `aster-local-verification-env` 的 podman PG/Redis 配方 + `./gradlew --offline :replay:test :test --continue`（记执行数） |
| aster-cloud | `pnpm exec tsc --noEmit`、`pnpm test:run`、`pnpm test:integration`、相关 e2e |
