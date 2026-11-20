# ADR 0059 修订 4（策略包携带显示排版 + `show` / `run --display`）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 带显示排版的已批准版本导出为 `formatVersion 1.1` 的策略包（`policy/display/layout.json` + `display.aster`，签名覆盖、不参与编译）；两个 CLI 能核验排版、`show` 出原诗、`run` 出 `静夜思`，并能从改过的显示文本还原规范源码后运行。

**Architecture:** 规则先在 aster-lang-test 写成真相源（schema 1.1、README、四组向量、参照实现、黄金包），再由 core（Java `aster.core.layout`）与 ts（`./layout` 增量）按同一向量实现；两个 CLI 只做编排；三者随列车 eco-1.0.53 发布。列车之后 aster-api 在签名前用 core 的同一规则校验 cloud 送来的排版，cloud 负责读取、全量 ts 校验与转发。任何一步不过就省略排版、导出 1.0 包。

**Tech Stack:** aster-lang-test（Node ≥ 24 脚本，无 npm 依赖）；aster-lang-core（Java 25、Gradle、JUnit 5）；aster-lang-ts（TypeScript、`pnpm test`）；aster-lang-truffle `cli/`（picocli、Gradle）；aster-api（Quarkus、Gradle）；aster-cloud（Next.js on Workers、vitest、next-intl）。

**Spec:** `docs/adr/0059-amendment-4-display-layout-in-packages.md`（PROPOSED，2026-11-20；下称「修订」，条目 D1–D16、规则 L1–L9）。实施须等产品负责人确认修订；修订 §11 的 Q1–Q5 若被推翻，按推翻后的结论改对应任务。

## 现状（2026-11-20 核对：aster-api `2b63f1bc`、aster-cloud `1db16067`、aster-lang-ts `e4728cb`（3.5.0）、aster-lang-truffle `248d100`（1.0.52）、aster-lang-test `99ef78e`）

| 事实 | 出处 |
|---|---|
| 包格式真相源与生成器 | aster-lang-test `conformance/package-v1/README.md`、`schema/manifest-1.0.schema.json`、`exit-codes.json`、`envelope.schema.json`；生成器 `scripts/build-package-fixtures.mjs`，参照实现 `scripts/lib/package-v1/{reference-check,fixtures,canonical,strict-json,position-free}.mjs`；跨 CLI 门 `scripts/package-conformance.mjs` |
| 断言「1.1 不受支持」的夹具 | `conformance/package-v1/src/tamper.json` 的 `format-version-1.1`、`format-version-1.1-with-new-field`（生成到 `tamper/`） |
| ts 包格式实现 | `src/policy-package/{cli,argv,manifest,obtain-ir,input,evaluate-package,compile-source}.ts`；格式版本判定 `manifest.ts:114-117`；schema 副本 `src/policy-package/schema/` |
| ts 排版库 | `src/layout/{index,types,format,validate,mapping,value-tokens}.ts`；子路径 `./layout`（`package.json:85-88`）；`foldKey` 在 `src/config/lexicons/identifiers/casefold.ts:15` |
| truffle CLI | `cli/src/main/java/aster/cli/{AsterCli,Argv,ManifestCheck,PackagePipeline,Meta,Envelope,ErrorCode,JsonSchemas}.java`；schema 副本 `cli/src/main/resources/aster/cli/`；格式版本判定 `ManifestCheck.java:57-62` |
| core 共用件 | `aster.core.identifier.CaseFold.foldKey`；`aster.core.embed.PolicySourceCompiler`（`vocabularyScopesOf`，`:110`） |
| aster-api 导出 | `src/main/java/io/aster/policy/export/{PackageAssembler,PackageExportService,PackageReadme,ExportRuntimes}.java`（`FORMAT_VERSION` `PackageAssembler.java:31`，载荷 `:86-96`）；请求 `rest/model/PackageExportRequest.java`；资源与审计 `rest/PackageExportResource.java:323`；锁文件 `src/main/resources/export/export-runtimes.lock.json`；README 模板 `src/main/resources/export/readme/{en,zh,de}.md` |
| aster-api 诗的共享输入 | `src/test/java/io/aster/test/JingyesiPolicy.java` |
| cloud 导出 | 路由 `src/app/api/policies/[id]/export/package/route.ts`；输入 `src/lib/package-export-input.ts`、`src/lib/cedar-export-input.ts`；Node 复核 `src/lib/package-node-check.ts`；客户端 `src/services/policy/policy-api.ts:875-905`；面板 `src/components/policy/package-export-panel.tsx`；开关 `src/lib/platform-settings.ts:189-192` |
| cloud 排版 | `src/lib/policy-layout/store.ts`（`prepareLayout` `:75`、`verifyRow` `:136`、`loadVersionLayout` `:152`）；诗 `src/config/jingyesi-poem.ts` |
| cloud ts 依赖 | `package.json:67` `@aster-cloud/aster-lang-ts` `3.5.0` |

修订 §1.1 已实测：今天的 jre-b / node-b 包在 ts 3.5.0 与 aster-cli 1.0.52 上 `verify --recompile --probes` 全过、`run` 得 `静夜思`，无需前置修复。

## Global Constraints

- **列车门**：🚂 = 引擎 / CLI 改动，只随列车 **eco-1.0.53** 发布（core、truffle 1.0.53，ts 3.6.0——ts 版本号由列车定，下称 **ts-next**）；aster-api 引擎 pin 只由列车 bump（ADR 0053 修订 1）。⏳ = 依赖列车已发布；合并前在 PR 正文写明核对结果（Maven 坐标与 npm 版本可解析、生产 aster-api 跑在 1.0.53）。
- 包文件路径恰为 `policy/display/layout.json`、`policy/display/display.aster`；`layout.json` = ts `serializeLayout` 字节，`display.aster` = `toDisplay` 的 UTF-8，二者都无 BOM、不追加换行。
- manifest：带排版 `formatVersion:"1.1"` 且 `display:{"layoutVersion":1}`；不带排版 `formatVersion:"1.0"`、无 `display`，与修订前逐字节相同。CLI `formatVersions` = `["1.0","1.1"]`。
- 新输出码与固定 message（逐字）：`PKG_DISPLAY_INVALID`（4）`package display layout is not valid`；`DISPLAY_UNMAPPABLE`（2）`edited display text does not map onto the package layout`；`DISPLAY_AMBIGUOUS`（2）`edited display text maps onto the package layout in more than one way`；`DISPLAY_REJECTED`（2）`edited display text would hide a value from readers`。
- `verify` 检查项名 `display`、`display-ir`；`meta.display = {"canonicalSha256","matchesPackage"}` 只在 `run --display` 出现；`--display` 时 `meta.coreIrSha256 = null`、`irRecompiled = true`。
- 规则 L1–L9 与反向映射的唯一定义是修订 §4 与 D7；三处实现（core、ts、aster-lang-test 参照实现）跑同一组向量，向量文件在 core 与 ts 各存**逐字节**副本并断言同一 sha256。
- `LAYOUT_LIMITS` 数值不改（12 288 / 200 / 400 / 49 152 / 24 576 / 256）。
- 响应头 `X-Aster-Package-Layout: included | absent | omitted`，`X-Aster-Package-Layout-Reason ∈ {layout_inconsistent, layout_invalid, layout_export_disabled, node_display_check_failed, display_rules_failed, cli_unsupported}`。
- cloud 开关 `policy_export.package.layout.enabled`（PlatformSetting，缺行 false）。锁文件每个 target 的 `packageFormats` 缺省按 `["1.0"]`。
- 代码注释简体中文；cloud 新文案 en / zh / de / hi 四语（hi 为印地语）。
- 提交信息结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 正文结尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`。
- 生产与发布动作（列车、开关、生产导出）先问用户。

## Review Focus

1. **编辑器在文末补了换行**（几乎所有编辑器都会）：改过的《静夜思》以 `。\n` 结尾，而显示以 `。` 结尾，必须照常还原（D7 的文末换行规则；T1 向量 `jingyesi-trailing-newline`、T3/T4 黄金用例）。
2. **Windows 编辑器保存成 CRLF**：必须得到 `DISPLAY_UNMAPPABLE`（不是 `AMBIGUOUS`、不是崩溃），stderr 提示 CRLF（T1 向量 `jingyesi-crlf`）。
3. **改过的显示改了入口形参**：输入按派生 IR 校验；按 manifest 的 schema 合格但不合派生 IR 的输入必须 `INPUT_INVALID`，入口改名必须 `ENTRY_NOT_FOUND`（T1 黄金用例 `display-entry-renamed`、`display-param-retyped`）。
4. **导出者没有 `structured_authoring`**：排版照样随包导出（T7 `includes the layout regardless of the exporter's capability`）。
5. **无签名包上 `show`**：照常打印并在 stderr 提示未核验；签名包上被改坏的 `display.aster` 必须 `PKG_DISPLAY_INVALID`（T1 篡改夹具 `display-text-tampered-resigned`）。

## 裁决（修订未定之处；用户可推翻）

| # | 问题 | 裁决 | 理由 |
|---|---|---|---|
| PR1 | conformance 夹具与两个 CLI 互相依赖，不能让 main 变红 | 按 **T1 → T2 → T3 / T4** 顺序合并，每个 PR 合入时 main 全绿：T1 新增的 package-conformance 用例带 `requiresFormat:"1.1"`，`package-conformance.mjs` 先跑两侧 `version`，**两侧** `formatVersions` 都含 `"1.1"` 才运行这些用例，否则记为 skipped 并在报告中列出；T1 自身的单测（`test-package-v1.mjs`）只跑参照实现，不依赖 CLI。T3、T4 合入后用例自动生效。T5 Step 4 把开关改为强制（两侧缺 `"1.1"` 即失败）。`--verify-engines` 固定在已发布 ts，新用例在 `pendingTsRelease` 清单内跳过引擎复算，固定版本 ≥ ts-next 时清单非空即报错（T5 Step 4 删除）。T2–T4 各自 CI 读 aster-lang-test main（T1 已合入） | 不依赖「合并窗口内可能红」的人工操作 |
| PR2 | 黄金包 `b-jingyesi` 的期望值来源 | `run` 的值取 aster-api `/evaluate-source`（ADR 0060 T7 已证 `静夜思`）；`run --display` 的值取 ts 解释器（`source:"ts"`，同 README「期望值来源」对非整数 Decimal 的做法），`--verify-engines` 复算 | 平台没有 display 端点 |
| PR3 | 锁文件 `packageFormats` 谁写 | T6 在写读取代码的同一 PR 写入 `["1.0","1.1"]`（列车已发布支持 1.1 的 CLI）；此后列车 L4 pin bump 维护它，T5 更新列车 runbook | T6 排在列车之后 |
| PR4 | L6(c) 与 L7 的关键词拼写来源 | 每个引擎取自己编译所用的词法表（ts `lexiconForLocale`，Java locales SPI），再与向量 `keyword-spellings.json` 比对；向量由 aster-lang-test 从两侧导出、相同才写入 | 防两侧词法表静默分歧 |

## PR 顺序

| PR | 仓 | 任务 | 门 |
|---|---|---|---|
| 0 | aster-api | 修订 4 + 本计划（docs-only） | — |
| 1 | aster-lang-test | T1 真相源（新用例按能力门控，合入即绿） | — |
| 2 | aster-lang-core | T2 `aster.core.layout` | 🚂 |
| 3 | aster-lang-ts | T3 `./layout` 增量 + CLI | 🚂，依赖 PR 1、2（向量 sha 与 core 相同） |
| 4 | aster-lang-truffle | T4 CLI | 🚂，依赖 PR 1、2 |
| — | 列车 eco-1.0.53 | T5 | 🚂 |
| 5 | aster-api | T6 导出带排版 | ⏳ |
| 6 | aster-cloud | T7 读取、校验、转发、面板 | ⏳，PR 5 已在生产 |
| — | 生产 | T8 最终验收 | PR 5、6 已上线 |

---

### Task 1: aster-lang-test 包格式 1.1 真相源（PR 1）

**Files:**
- Create: `conformance/package-v1/schema/manifest-1.1.schema.json`；`conformance/package-v1/vectors/{layout,display-reconstruct,display-padding,keyword-spellings}.json`；`scripts/lib/package-v1/display.mjs`；`conformance/package-v1/src/b-jingyesi/`（`policy.aster`、`fixture.json`、`alias-set.json`、`vocabulary.json`、`layout.json`、`displays/*.txt`、`expected.json`）；`conformance/package-v1/src/b-display-swallow/`（en-US，`Return "a"` 一行里有结构 span `{canonical:" ",display:"·"}`）；`scripts/test-old-cli-rejects-1-1.mjs`
- Modify: `conformance/package-v1/README.md`（新增「显示排版」「show」「run --display」三节，检查顺序表加 display / display-ir 阶段，⑤ 改为「次版本 ≤ 1」）；`exit-codes.json`；`envelope.schema.json`（`Meta.display`、`Inspect.display`、`Check.name`）；`normalize-stdout.mjs`（不动 `display`，补向量）；`src/tamper.json`（新增篡改夹具，`format-version-1.1*` 改为 `1.2*`）；`scripts/lib/package-v1/{reference-check,fixtures,argv}.mjs`；`scripts/build-package-fixtures.mjs`；`scripts/package-conformance.mjs`（`show` 的 stdout 非 JSON，按字节比较）
- Test: `scripts/test-package-v1.mjs`

**Interfaces:**
- Produces（参照实现 `display.mjs`，T3 / T4 照此实现）：`parseLayoutStrict(bytes) → layout`、`serializeLayout(layout) → string`、`checkPackageLayout({layoutBytes, displayBytes, source, layoutVersion, vocabularyLocalized, literalKeywords, operatorSpellings}) → {ok:true,layout} | {ok:false,rule:'L1'…'L7'}`、`reconstructFromDisplay(layout, edited) → {kind:'unique',layout} | {kind:'unmappable',offset} | {kind:'ambiguous',offset}`、`padStructuralSpans(layout) → string`、`structuralNameHits(layout, irNames) → string[]`、`irNameStrings(coreIrJson) → string[]`（IR 中键为 `name` 或 `written` 的全部字符串，去重保序）
- 向量形状：`layout.json` `{version:1, cases:[{id, layoutText, source, displayText?, vocabularyLocalized:[], literalKeywords:[], operatorSpellings:[], expect:'ok'|'L1'…'L7', serialized?}]}`；`display-reconstruct.json` `{version:1, cases:[{id, layout, edited, expect:'unique'|'unmappable'|'ambiguous', canonical?}]}`；`display-padding.json` `{version:1, cases:[{id, layout, padded}]}`；`keyword-spellings.json` `{version:1, locales:{"en-US":{"literal":[…],"operator":[…]},"zh-CN":{…},"de-DE":{…},"hi-IN":{…}}}`（拼写已 `foldKey`、排序去重；`operator` 只含词法表拼写，aliasSet 别名由调用方并入）
- 黄金包 `b-jingyesi`：zh-CN、ADR 0060 §8 的源码 / aliasSet / 词汇（含 `literals`）、cloud `JINGYESI_LAYOUT`，`allowStructural:true`，jre target，`formatVersion 1.1`

- [ ] **Step 1: 写向量**，至少：
  - `layout.json`：《静夜思》原排版 `ok` 且 `serialized` 等于 cloud 存储形态；含 ` `、控制字符、非 BMP 字符的转义；未合并的相邻内容 span → `L1`；键序不同 → `L1`；重复键 → `L1`；拆代理对 → `L1`；每项上限各差 1 字节的超出 → `L2`；往返差一个字 → `L3`；`display.aster` 多一个换行 → `L4`；结构 span 含 `「` → `L5`；结构 span 含数字 `42` → `L6`；结构 span 含词汇 `思故乡` → `L6`；en-US 结构 span `" true "` → `L6`；结构 span 含 `trueish`（非整词）→ `ok`；`x - 1` 中结构 span `{canonical:"-",display:"已获批准"}` → `L7`；`{canonical:" + ",display:" plus "}` → `L7`；`{canonical:" + ",display:" ＋/+ "}` → `ok`；zh 运算符 kind 别名（如 aliasSet `PLUS:["接 着"]`）在结构 span 而 display 为空 → `L7`；组合附加符与全角字母在整词边界上的用例各一。
  - `display-reconstruct.json`：修订 D7 表中六行（`jingyesi-unchanged` unique 且 `canonical` = 原源码、`jingyesi-last-line-literal` unique、`jingyesi-first-line-edit` unique、`jingyesi-both-sides` unmappable、`jingyesi-repeat-prefix` ambiguous、`jingyesi-punct-changed` unmappable）+ `jingyesi-trailing-newline`（原诗 + `\n`，unique）+ `jingyesi-trailing-crlf`（原诗 + `\r\n`，unique）+ `jingyesi-crlf`（全文 `\r\n`，unmappable）+ `hidden-run-at-start`（隐藏 span 在文首，内容任意改，unique）+ `two-adjacent-hidden`（两个相邻隐藏 span 夹在两内容之间，一侧不变，unique）+ `content-emptied`（某内容 span 删空，unmappable）+ `oversize`（24 577 字节，unmappable）。
  - `display-padding.json`：《静夜思》→ `床前   明月光 。\n疑是   地上霜 ， 举头   望明月 ：\n  低头   思故乡 。\n`（修订 §4 实测的同一 `P`）；行首缩进内不补；文首文末不补。
- [ ] **Step 2: 写 `display.mjs` 的单测**（并入 `scripts/test-package-v1.mjs`）：逐条跑三组向量；反向映射另跑一个性能用例（400 span、24 576 字节的最坏输入在 2 s 内给出结论）。运行 `node scripts/test-package-v1.mjs` → FAIL。
- [ ] **Step 3: 实现 `display.mjs`**。反向映射：以 (span 下标, 文本位置, 前一内容 span 是否原样) 为状态的记忆化计数，计数上限 2；内容 span 的候选终点只取「下一个可见锚点在 E 中的出现位置」或「原文出现位置」（隐藏串后接内容时），保证复杂度与 E 长度近线性。运行 → PASS。
- [ ] **Step 4: 写 schema 1.1、README 三节、exit-codes、envelope schema**；README 的检查顺序表：manifest ⑤ 改「主版本 = 1 且次版本 ≤ 1」，按次版本选 schema；integrity 之后新增 display 阶段（`PKG_DISPLAY_INVALID`）；`run --display` 的阶段序：…→ display → reconstruct（`DISPLAY_UNMAPPABLE` / `DISPLAY_AMBIGUOUS`）→ derived（L2/L5/L6，`DISPLAY_REJECTED`）→ compile（`COMPILE_ERROR`）→ L8/L9（`DISPLAY_REJECTED`）→ entry → input → eval；`meta` 取值表补 `display`。
- [ ] **Step 5: 生成黄金包与用例**：`b-jingyesi.cases.json` 至少：`verify`（含 `display`）、`verify --recompile`（含 `display-ir`）、`inspect`（含 `display` 成员）、`show`（stdout = 原诗 + `\n`）、`show --canonical`、`show --from-display displays/last-line-literal.txt`（stdout = 派生源码 + `\n`）、`run` → `静夜思`、`run --display displays/unchanged.txt` → `静夜思` 且 `matchesPackage:true`、`run --display displays/last-line-literal.txt` → `月光` 且 `matchesPackage:false`、`displays/trailing-newline.txt` → `静夜思`、`displays/both-sides.txt` → `DISPLAY_UNMAPPABLE`、`displays/repeat-prefix.txt` → `DISPLAY_AMBIGUOUS`、`show --canonical --from-display x` → `USAGE`；`display-entry-renamed`（把 `地上霜` 改成 `天上霜`）→ `ENTRY_NOT_FOUND`。`b-display-swallow`：`verify` 通过（`·` 显示的空格合规）；`run --display`（显示改成 `Return "x·y"`，还原出 `Return "x y"`，字符串吞进结构 span）→ `DISPLAY_REJECTED`（L9）。另一个带参数的 en-US 黄金包 `b-display-params`：`display-param-retyped`（改形参类型后按旧形态输入）→ `INPUT_INVALID`。篡改夹具（重新签名）：`display-text-tampered-resigned`、`display-layout-not-canonical-resigned`、`display-roundtrip-mismatch-resigned`、`display-missing-file`（manifest 有 `display` 缺文件 → `PKG_INVALID_MANIFEST`）、`display-in-1-0-manifest`（1.0 带 `display` → `PKG_INVALID_MANIFEST`）、`display-unsigned`（`show --allow-unsigned` 成功）。
- [ ] **Step 5a: 能力门控**：`package-conformance.mjs` 读每条用例的 `requiresFormat`，两侧 `version` 的 `formatVersions` 都含该值才运行，否则 skipped（报告列出）；单测覆盖「一侧支持一侧不支持 → skipped」「都支持 → 运行」。
- [ ] **Step 5b: 写 `pendingTsRelease`**（`src/` 下的清单，列出 Step 5 新增的全部用例 id；`--verify-engines` 在固定 ts 版本 < ts-next 时跳过它们，≥ ts-next 时清单非空即失败）。
- [ ] **Step 6: 旧 CLI 兼容脚本** `scripts/test-old-cli-rejects-1-1.mjs`：从 GitHub Release 下载 ts `v3.5.0` 的 `aster.mjs` 与 truffle `v1.0.52` 的 `aster-cli-1.0.52.jar`，核对各自 `.sha256`，对 `b-jingyesi` 跑 `verify` / `run` / `inspect`，断言两侧退出码 4、错误码 `PKG_UNSUPPORTED_FORMAT`；加入 CI（缓存下载）。
- [ ] **Step 7: 运行** `node scripts/test-package-v1.mjs`、`node scripts/build-package-fixtures.mjs --check`、`node scripts/build-package-fixtures.mjs --verify-engines --ts ../aster-lang-ts --guard ../aster-guard`（ts 取同名分支的 T3）、`node scripts/test-old-cli-rejects-1-1.mjs` 全过。
- [ ] **Step 8: 提交并开 PR**，正文写明新用例的能力门控与 `pendingTsRelease`。

### Task 2: 🚂 aster-lang-core `aster.core.layout`（PR 2）

**Files:**
- Create: `src/main/java/aster/core/layout/{PolicyLayout,LayoutSpan,LayoutFormat,LayoutLimits,LayoutException,PackageLayoutRules,DisplayReconstructor}.java`；`src/test/resources/layout/{layout,display-reconstruct,display-padding,keyword-spellings}.json`（T1 向量逐字节副本）；`src/test/java/aster/core/layout/{LayoutVectorsTest,DisplayReconstructorTest,LayoutPaddingTest}.java`

**Interfaces:**
- Consumes: T1 向量；`aster.core.identifier.CaseFold.foldKey(String)`
- Produces:
  - `record PolicyLayout(List<LayoutSpan> spans)`；`sealed interface LayoutSpan permits LayoutSpan.Content, LayoutSpan.Structural`，`record Content(String text)`、`record Structural(String canonical, String display)`
  - `LayoutFormat.parseStrict(byte[] utf8) → PolicyLayout`（L1，含规范字节比较）、`serialize(PolicyLayout) → String`、`toCanonical(PolicyLayout) → String`、`toDisplay(PolicyLayout) → String`
  - `LayoutLimits.check(PolicyLayout, String source)`（L2；常量 `SOURCE_BYTES=12288, SOURCE_LINES=200, SPANS=400, SERIALIZED_BYTES=49152, DISPLAY_BYTES=24576, STRUCTURAL_DISPLAY_CHARS=256`）
  - `PackageLayoutRules.checkPackage(byte[] layoutJson, byte[] displayAster, String source, int layoutVersion, Collection<String> vocabularyLocalized, Collection<String> literalKeywords, Collection<String> operatorSpellings) → PolicyLayout`（L1–L7）；`checkDerived(PolicyLayout, Collection<String> vocabularyLocalized, Collection<String> literalKeywords, Collection<String> operatorSpellings)`（L2、L5、L6、L7）；`structuralNameHits(PolicyLayout, Collection<String> irNames) → List<String>`（L8）；`padStructural(PolicyLayout) → String`（L9 的 `P`）；`irNameStrings(String coreIrJson) → List<String>`
  - `DisplayReconstructor.reconstruct(PolicyLayout, String edited) → Reconstruction`；`sealed interface Reconstruction`：`Unique(PolicyLayout derived)`、`Unmappable(int offset, boolean crlfHint)`、`Ambiguous(int offset)`
  - `LayoutException(String rule /* "L1"…"L9" */, String message)`（unchecked）

- [ ] **Step 1: 复制 T1 向量**，写 `LayoutVectorsTest`：逐条断言 `expect` 与规则号；`serialized` 与 `serialize` 逐字节相同；断言四个向量文件的 sha256 等于 T1 合入后的值（写死）。`DisplayReconstructorTest` 跑 `display-reconstruct.json`（含 `canonical`）与 T1 的性能用例（`assertTimeoutPreemptively(Duration.ofSeconds(2))`）。`LayoutPaddingTest` 跑 `display-padding.json`。
- [ ] **Step 2: 运行确认失败**：`./gradlew test --tests 'aster.core.layout.*'` → FAIL（类不存在）。
- [ ] **Step 3: 实现**。字符串转义按 ECMAScript `JSON.stringify`（`"`、`\\`、`\b\f\n\r\t`、其余 U+0000–U+001F 与孤立代理为小写 `\uXXXX`，其余原样）；UTF-16 偏移语义与 ts 相同（Java `String` 下标即 UTF-16）；`trim` 判空白按 ECMAScript `String.prototype.trim` 的字符集（向量覆盖 U+3000、U+FEFF）；整词判定用 `[\p{L}\p{M}\p{Nd}_]`（`Character.isLetter` 等不足以覆盖 `\p{M}`，用 `Pattern` `\p{L}\p{M}\p{Nd}_`）。
- [ ] **Step 4: 运行** `./gradlew test` 全绿（记执行的测试数）。
- [ ] **Step 5: 提交并开 PR**（🚂，随 eco-1.0.53）。

### Task 3: 🚂 aster-lang-ts `./layout` 增量与 CLI（PR 3）

**Files:**
- Create: `src/layout/package-rules.ts`、`src/layout/reconstruct.ts`；`shared/layout-vectors/{layout,display-reconstruct,display-padding,keyword-spellings}.json`（T1 逐字节副本）；`src/policy-package/display.ts`（编排）；`test/unit/layout/{package-rules,reconstruct}.test.ts`；`test/unit/policy-package/display-cli.test.ts`
- Modify: `src/layout/index.ts`（导出新函数）；`src/layout/types.ts`（`LayoutErrorCode` 加 `layout_display_mismatch`、`layout_structural_hides_operator`）；`src/policy-package/{manifest,cli,argv,errors}.ts`；`src/policy-package/schema/`（加 1.1 schema 副本，及 `exit-codes`、`envelope` 副本若有）

**Interfaces:**
- Consumes: T1 向量与参照实现语义
- Produces（`./layout`）：`checkPackageLayout(opts: {layoutBytes: Uint8Array; displayBytes: Uint8Array; source: string; layoutVersion: number; vocabularyLocalized: readonly string[]; literalKeywords: readonly string[]; operatorSpellings: readonly string[]}): PolicyLayout`（失败抛 `LayoutError`，`code` 映射：L1→`layout_invalid_format`、L2→`layout_too_large`、L3→`layout_roundtrip_mismatch`、L4→`layout_display_mismatch`、L5→`layout_content_parity`、L6/L8/L9→`layout_structural_hides_value`、L7→`layout_structural_hides_operator`）；`checkDerivedLayout(layout, vocabularyLocalized, literalKeywords, operatorSpellings): void`；`structuralNameHits(layout, irNames: Iterable<string>): string[]`；`padStructuralSpans(layout): string`；`irNameStrings(coreIrJson: string): string[]`；`reconstructFromDisplay(layout, edited: string): {kind:'unique'; layout: PolicyLayout} | {kind:'unmappable'; offset: number; crlf: boolean} | {kind:'ambiguous'; offset: number}`
- Produces（CLI）：子命令 `show`；选项 `run --display <file>`、`show --canonical`、`show --from-display <file>`；`verify` 检查项 `display`、`display-ir`；`version.formatVersions = ["1.0","1.1"]`
- 不改：`validatePolicyLayout` 与 `value-tokens.ts`

- [ ] **Step 1: 写单测** `package-rules.test.ts`、`reconstruct.test.ts` 跑 `shared/layout-vectors/*`，并断言四个文件的 sha256 与 T2 写死的相同；`display-cli.test.ts` 以 T1 黄金包 `b-jingyesi`（取 aster-lang-test main，沿用既有 conformance 单测的取法）断言：`show` stdout 为 `床前明月光，\n疑是地上霜。\n举头望明月，\n低头思故乡。\n`；stderr 的结构映射含 `"，\n" ⇐ "。\n"` 与 `"" ⇐ " "`，并列出 `思故乡 → 静夜思`；`run --display displays/last-line-literal.txt` 的 `value` 为 `"月光"`、`meta.display.matchesPackage === false`、`meta.coreIrSha256 === null`；stderr 先含派生源码再有结果；1.0 黄金包的 `inspect` / `verify` stdout 与修改前逐字节相同。
- [ ] **Step 2: 运行确认失败**：`pnpm run test:unit` → FAIL。
- [ ] **Step 3: 实现 `./layout` 增量**（纯函数、无 Node 依赖，Workers 可用——T7 的 Node 复核在 Worker 内调用它们）。
- [ ] **Step 4: 实现 CLI 编排** `src/policy-package/display.ts`：`literalKeywords(locale)` 取 `lexiconForLocale(locale)` 与 en-US 的 TRUE/FALSE/NULL/NONE 拼写经 `foldKey`；`operatorSpellings(locale, aliasSet)` 取两表中 11 个运算符 kind 的拼写并入 aliasSet 中这些 kind 的别名（kind 清单与 core `UserAliasValidator` 的运算符档同一份，测试断言相等）；L8/L9 用 `compileSource` 与包的编译输入、`positionFreeSha256` 比较；manifest 次版本 ≤ 1 并按次版本选 schema；`run --display` 求值仍在 Worker 内、超时规则不变；`show` 的 stderr 列出词汇 `literals`（`触发词 → 展开值`）。
- [ ] **Step 5: 运行** `pnpm test` 全绿；`node ../aster-lang-test/scripts/package-conformance.mjs --node dist/runner/aster.mjs --jar <T4 同名分支构建的 jar>` 全部用例两侧一致。
- [ ] **Step 6: 提交并开 PR**（🚂）。

### Task 4: 🚂 aster-lang-truffle CLI（PR 4）

**Files:**
- Create: `cli/src/main/java/aster/cli/{DisplayCommand,DisplayStage}.java`；`cli/src/main/resources/aster/cli/manifest-1.1.schema.json`（T1 副本）；`cli/src/test/java/aster/cli/DisplayCliTest.java`
- Modify: `cli/src/main/java/aster/cli/{AsterCli,Argv,ManifestCheck,PackagePipeline,Meta,Envelope,ErrorCode,JsonSchemas,Version}.java`；schema parity 测试（新副本纳入）

**Interfaces:**
- Consumes: T2 `aster.core.layout.*`（core 1.0.53-SNAPSHOT / composite build）；T1 黄金包
- Produces: 与 T3 同名同义的 `show`、`run --display`、`verify` 的 `display` / `display-ir`、四个新码；`Meta` 增加可空 `display`（`record DisplayMeta(String canonicalSha256, boolean matchesPackage)`）

- [ ] **Step 1: 写 `DisplayCliTest`**：与 T3 Step 1 的 CLI 断言逐条对应（同一黄金包与 displays 文件），另断言 `ErrorCode` 四个新码的退出码与 message 与 `exit-codes.json` 一致。
- [ ] **Step 2: 运行确认失败**：`./gradlew :cli:test --tests 'aster.cli.DisplayCliTest'` → FAIL。
- [ ] **Step 3: 实现**：`DisplayStage` 调 `PackageLayoutRules` / `DisplayReconstructor`；L6(c) 与 L7 的拼写取 locales SPI 的 lexicon（运算符另并入 aliasSet 别名）；L8/L9 用 `PolicySourceCompiler` 与包编译输入、按 `positionFreeSha256` 比较；`run --display` 的求值沿用现有沙箱与看门狗。
- [ ] **Step 4: 运行** `./gradlew :cli:test` 全绿；`package-conformance.mjs`（两侧）全部一致；`java -jar cli/build/libs/aster-cli-*.jar version` 的 `formatVersions` 为 `["1.0","1.1"]`。
- [ ] **Step 5: 提交并开 PR**（🚂）。

### Task 5: 🚂 列车 eco-1.0.53

- [ ] **Step 1:** 确认 PR 1–4 已合并、conformance 门在 main 绿；向用户确认发车。
- [ ] **Step 2:** 按 ADR 0053 / 现行列车流程发布 core、truffle 1.0.53 与 ts-next（Release 附 `aster-cli-1.0.53.jar.sha256`、`aster.mjs.sha256`）；aster-api 引擎 pin bump 到 1.0.53；cloud `@aster-cloud/aster-lang-ts` 升到 ts-next（ADR 0059 R10：与锁文件同一列车步骤）。
- [ ] **Step 3:** 锁文件 `node.cliVersion` / `jre.cliVersion` 升到 ts-next / 1.0.53（同 aster-api#586 的做法）；列车 runbook 增加「`packageFormats` 随 CLI 支持的格式版本更新」一行。
- [ ] **Step 4:** 在 aster-lang-test 把 CI 固定的兄弟仓版本升到新 tag（README「CI 中的兄弟仓固定在被测版本」），删除 T1 的 `pendingTsRelease` 清单，并把 `requiresFormat` 门控改为强制（任一 CLI 的 `formatVersions` 缺 `"1.1"` 即失败）。

### Task 6: ⏳ aster-api 导出带排版（PR 5）

**Files:**
- Create: `src/main/java/io/aster/policy/export/PackageLayoutGate.java`；`src/test/java/io/aster/policy/export/{PackageLayoutGateTest,PackageExportLayoutTest}.java`；`src/test/resources/export/jingyesi-layout.json`（cloud `JINGYESI_LAYOUT` 的序列化）
- Modify: `rest/model/PackageExportRequest.java`（`String layout`）；`rest/PackageExportResource.java`（长度上限 400 `layout_too_large`、响应头、审计字段）；`export/PackageExportService.java`（调用闸门、把结果交给组装）；`export/PackageAssembler.java`（`PackageInputs` 加 `LayoutOutcome`；有排版写两文件、`formatVersion:"1.1"`、`display`）；`export/ExportRuntimes.java`（读 `packageFormats`，缺省 `["1.0"]`）；`src/main/resources/export/export-runtimes.lock.json`（两 target 加 `"packageFormats":["1.0","1.1"]`）；`export/PackageReadme.java` 与 `src/main/resources/export/readme/{en,zh,de}.md`（有排版时的一节）；`src/test/java/io/aster/test/JingyesiPolicy.java`（加 `LAYOUT_JSON` 常量）

**Interfaces:**
- Consumes: core `aster.core.layout.*`（1.0.53）；现有 `PackageExportService.ExportSpec`、编译结果
- Produces: `record LayoutOutcome(byte[] layoutJson, byte[] displayAster, String status /* included|absent|omitted */, String reason /* null 或 Global Constraints 中的码 */)`；`PackageLayoutGate.evaluate(String layoutJsonOrNull, PackageExportService.ExportSpec spec, String platformCoreIrJson, String positionFreeSha256) → LayoutOutcome`；响应头 `X-Aster-Package-Layout`、`X-Aster-Package-Layout-Reason`；审计 `layoutSha256`、`layoutOutcome`

- [ ] **Step 1: 写失败测试** `PackageLayoutGateTest`：`null` → `absent`；诗的排版 → `included` 且 `displayAster` = `床前明月光，\n疑是地上霜。\n举头望明月，\n低头思故乡。` 的 UTF-8；往返不符 → `omitted`/`display_rules_failed`；锁文件该 target 不含 `1.1`（测试用替身）→ `omitted`/`cli_unsupported`。`ExportRuntimesTest` 加：缺 `packageFormats` → `["1.0"]`；`packageFormats:[]`、`["1.9"]`、`"1.1"`（非数组）→ 加载抛异常。`PackageExportLayoutTest`（`@QuarkusTest`）：用 `JingyesiPolicy` + `LAYOUT_JSON` 导出 jre 与 node：manifest `formatVersion` 为 `1.1`、`display` 为 `{"layoutVersion":1}`、`files[]` 含两路径；同一输入不带排版导出 → `1.0` 且 `source.envelopeSha256`、`coreIr.*` 与带排版时相同、整包与修订前同一测试的期望逐字节相同；两次带排版导出 zip 逐字节相同；`JreCliGoldenGateTest` 新增用例：带排版的诗包经 `java -jar aster-cli.jar verify --recompile` 全部检查项（含 `display`、`display-ir`）`ok`，`show` 输出原诗。
- [ ] **Step 2: 运行确认失败**（podman PG/Redis 配方，见「本地验证命令汇总」）。
- [ ] **Step 3: 实现**。闸门顺序：长度 → `PackageLayoutRules.checkPackage`（`displayAster` 用 `toDisplay` 自己生成，`vocabularyLocalized` 取 spec 词汇全部 kind 的 `localized`，`literalKeywords` / `operatorSpellings` 取 locales SPI 与 spec 的 aliasSet）→ `structuralNameHits`（平台 IR）→ 编译 `padStructural` 结果并比 `positionFreeSha256` → 锁文件 `packageFormats`。异常一律转 `omitted`，不抛出。
- [ ] **Step 4: 运行** `./gradlew --offline :replay:test :test --continue` 全绿（记执行数；已知基线失败见 `aster-local-verification-env`）。
- [ ] **Step 5: 提交并开 PR**（⏳，正文写列车核对结果）。

### Task 7: ⏳ aster-cloud 读取、校验、转发与面板（PR 6）

**Files:**
- Create: `src/lib/package-export-layout.ts`；`src/__tests__/lib/package-export-layout.test.ts`
- Modify: `src/app/api/policies/[id]/export/package/route.ts`（取排版、Node 复核结果、转发两个头）；`src/lib/package-node-check.ts`（ts 跑 L1–L9）；`src/services/policy/policy-api.ts`（`PackageExportRequest.layout?: string`，`PackageExportResult` 加 `layout: {status, reason}` 读自响应头）；`src/lib/platform-settings.ts`（键 `POLICY_PACKAGE_LAYOUT_EXPORT_ENABLED = 'policy_export.package.layout.enabled'` 与 `isPolicyPackageLayoutExportEnabled()`）；`src/components/policy/package-export-panel.tsx`（下载后一行说明）；四语文案；路由与面板的既有测试

**Interfaces:**
- Consumes: ts-next 的 `./layout`（`checkPackageLayout`、`padStructuralSpans`、`structuralNameHits`、`irNameStrings`）与 `./package-check`（`compileSource`、`positionFreeSha256`）；`loadVersionLayout`、`prepareLayout`
- Produces: `type ExportLayout = { kind: 'included'; layout: string } | { kind: 'absent' } | { kind: 'omitted'; reason: 'layout_inconsistent' | 'layout_invalid' | 'layout_export_disabled' }`；`loadExportLayout(version: {id: string; source: string; locale: string; aliasSet: Record<string, string[]> | null}): Promise<ExportLayout>`（版本无排版为 `absent`）；`runNodeCheck(prepared, request, layout?: string)` 的结果加 `layoutOk: boolean`（未给 `layout` 时为 `true`）

- [ ] **Step 1: 写失败测试**：`package-export-layout.test.ts`：开关关 → `layout_export_disabled`；`loadVersionLayout` 为 `inconsistent` → `layout_inconsistent`；`prepareLayout` 抛 → `layout_invalid`；诗 → `{layout}` 且等于存储文本；`includes the layout regardless of the exporter's capability`（导出者无 `structured_authoring`）。路由测试：带排版时请求体含 `layout`；node target 且 `layoutOk:false` → 不带 `layout`、最终头为 `omitted`/`node_display_check_failed`；上游头原样转发。面板测试：`included` 显示「包含显示排版」，`omitted` 显示原因文案。
- [ ] **Step 2: 运行确认失败**：`pnpm exec vitest run src/__tests__/lib/package-export-layout.test.ts <路由与面板测试>` → FAIL。
- [ ] **Step 3: 实现**；省略时 cloud 侧原因优先于上游原因（cloud 根本没发送时上游返回 `absent`，路由改写为 cloud 的 `omitted` 原因）。
- [ ] **Step 4: 运行** `pnpm exec tsc --noEmit`、`pnpm test:run` 全绿。
- [ ] **Step 5: 提交并开 PR**（⏳，PR 5 已在生产）。

### Task 8: 生产最终验收（无代码；结果写回本计划「验收结果」一节，docs-only PR）

前置（每项向用户确认后再动）：生产 aster-api 跑 1.0.53 且含 PR 5；cloud 含 PR 6；`policy_export.package.enabled` 与 `policy_export.package.layout.enabled` 为真；导出签名密钥已配置；验收账号的计划有 `policyExport`；《静夜思》模板策略已保存并**审批**（APPROVED、带排版）。

- [ ] **Step 1: 导出**：在生产详情页分别导出 JRE 与 Node B 包；记录 `X-Aster-Package-Sha256`、`X-Aster-Package-Layout: included`；与审计 `POLICY_EXPORTED`（`layoutOutcome:included`）中的 zip 哈希核对。
- [ ] **Step 2: 两个 CLI 各自核验**（发布产物：`npx @aster-cloud/aster-lang-ts@<ts-next>` 与 Release 的 `aster-cli-1.0.53.jar`，先核 `.sha256`），对两个包：`aster verify <pkg> --recompile --probes` → 全部检查项（含 `display`、`display-ir`）`ok`、退出码 0（内置 SaaS 锚，不加 `--jwks`）。
- [ ] **Step 3: `show`**：`aster show <pkg>` stdout = `床前明月光，\n疑是地上霜。\n举头望明月，\n低头思故乡。\n`，stderr 列出 `思故乡 → 静夜思`；`aster show <pkg> --canonical` = 带空格的规范源码。
- [ ] **Step 4: `run`**：`echo '{}' | aster run <pkg> --input -` → `{"ok":true,"value":"静夜思",…}`，两个 CLI 归一后 stdout 相同。
- [ ] **Step 5: `run --display`**：把 `show` 的输出存为 `poem.txt`（编辑器保存，带文末换行），末句改成 `低头「月光」。` → 两个 CLI 均输出 `value:"月光"`、`meta.display.matchesPackage:false`，stderr 先打印派生规范源码；不改的 `poem.txt` → `静夜思`、`matchesPackage:true`；`床前床前明月光` 一版 → `DISPLAY_AMBIGUOUS`、退出码 2。
- [ ] **Step 6: 旧 CLI**：ts 3.5.0 与 aster-cli 1.0.52 对同一包 → `PKG_UNSUPPORTED_FORMAT`、退出码 4。
- [ ] **Step 7:** 把命令、哈希、输出摘录写进本计划「验收结果」，开 docs-only PR。

## 后续（不在本计划内）

| # | 事项 | 依据 |
|---|---|---|
| F1 | aster-lang.cloud / aster-lang.dev 文档补 `show` 与 `run --display` | 修订 D6–D8 |
| F2 | P3（A 包）：锁文件 `packageFormats` 同样约束随包 CLI | 修订 D4 |
| F3 | 若 L1–L9 在生产版本上出现与 ts `validatePolicyLayout` 不一致的省略，按 aster-gate-scan 扫生产排版后再议规则 | 修订 D11 |

## 本地验证命令汇总

| 仓 | 命令 |
|---|---|
| aster-lang-test | `node scripts/test-package-v1.mjs`；`node scripts/build-package-fixtures.mjs --check`；`--verify-engines`；`node scripts/package-conformance.mjs --node … --jar …`；`node scripts/test-old-cli-rejects-1-1.mjs` |
| aster-lang-core | `./gradlew test` |
| aster-lang-ts | `pnpm test` |
| aster-lang-truffle | `./gradlew :cli:test` |
| aster-api | podman `postgres:17-alpine` / `redis:7-alpine` 起在独立端口，`DB_JDBC_URL=… QUARKUS_REDIS_HOSTS=… ./gradlew --offline :replay:test :test --continue`（记执行数）；本地无 GitHub Packages 时用 `-Dmaven.repo.local=<隔离目录>` 放列车产物 |
| aster-cloud | `pnpm exec tsc --noEmit`、`pnpm test:run` |

## 验收结果

（T8 完成后填写。）
