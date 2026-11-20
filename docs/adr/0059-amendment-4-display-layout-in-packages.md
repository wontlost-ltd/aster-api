# ADR 0059 修订 4：策略包携带显示排版，本地查看并运行改过的显示源码

- 状态：**PROPOSED**（2026-11-20，待产品负责人确认）
- 日期：2026-11-20
- 修订对象：[ADR 0059](0059-executable-policy-export.md) §2、§3、§4.1、§6.1、§7、§8，[修订 2](0059-amendment-2-package-format-rulings.md) R3（格式版本检查）；同时修订 [ADR 0058](0058-structured-authoring-capability-and-policy-layout.md) §7.2 读取契约（导出成为 `loadVersionLayout` 的调用方）、§7.4（本地 CLI 的反向映射）、§7.10（出现 Java 版本）
- 实施计划：`docs/superpowers/plans/2026-11-20-adr0059-a4-display-layout-export.md`
- 列车：引擎与 CLI 改动随 **eco-1.0.53**（aster-lang-core、aster-lang-truffle、aster-lang-ts）
- 来源：产品负责人 2026-11-20：「除 Guard 外，像《静夜思》这种如何导出修改了布局的源码，并使之能够本地化运行」。负责人选定的方向：包里带 `policy/display/layout.json` 与渲染好的 `display.aster`；二者在签名与 `SHA256SUMS` 覆盖之内，但不是编译输入（ADR 0058 §7.2：layout 不进 envelope）；`verify` 检查往返并按包的 locale、aliasSet、词汇校验；`show` 打印显示文本；可选的 `run --display <file>` 从改过的显示文本还原规范源码，按 ADR 0058 的防显示欺骗规则校验后编译运行

本修订的条目编号为 D1–D16（修订内编号，同修订 1 的 A0–A5）。规则正文最终以 aster-lang-test `conformance/package-v1/README.md` 为准（同修订 2）；本修订定方向与边界。

---

## 0. TL;DR

| 主题 | 决定 |
|---|---|
| 现状能不能跑《静夜思》 | **能**（§1.1 实测）：今天的导出已带内嵌词汇（含字面量宏 `思故乡 → "静夜思"`）与四个汉字单词结构词别名，ts 3.5.0 与 aster-cli 1.0.52 两个 CLI 的 `verify --recompile --probes` 与 `run` 都得到 `静夜思`。缺的只是**显示排版**：包里只有带空格的规范源码 |
| 格式版本 | 带排版的包 `formatVersion` 为 **`1.1`**，manifest 新增可选成员 `display:{"layoutVersion":1}`；不带排版的包仍是 `1.0`，逐字节不变 |
| 旧 CLI | 对 `1.1` 包按既有规则**明确失败**：`PKG_UNSUPPORTED_FORMAT`、退出码 4（§1.1 实测两侧皆然）；不会静默忽略排版 |
| 新文件 | `policy/display/layout.json`（ADR 0058 §7.3 的确定性序列化）与 `policy/display/display.aster`（`toDisplay` 的 UTF-8 字节）；都是普通载荷，经 `files[]` 进入签名、进入 `SHA256SUMS`；不进 `envelopeSha256`、不参与编译 |
| CLI 的排版校验 | 一套**不依赖词法器**的规则 L1–L9（§4），两侧 CLI 与 aster-api 用同一份规范、同一组向量实现；值是否被切进结构 span 用**补空格不变性**判定（编译器本身作判官，两引擎 IR 已逐字节对齐）；纯文本没有界面上的结构样式，所以另加 L7：运算符不得被藏或改写 |
| Java 版本 | 在 aster-lang-core 新增纯 Java 的 `aster.core.layout`（格式、上限、L1–L9 的字符串部分、反向映射、补空格），按 ADR 0045「副本 + 共享向量」；**不**移植 ts 的 587 行词法启发式 `value-tokens.ts` |
| 新命令 | `aster show <pkg> [--canonical \| --from-display <file>]`；`run --display <file>`；`verify` 增加 `display`（及 `--recompile` 下的 `display-ir`）检查项；`inspect` 原样带出 manifest 的 `display` |
| `run --display` 的反向映射 | 结构 span（标点、空白、结构关键词）固定不动，只有内容 span 可改——改标点、改别名不支持；隐藏（display 为空）的结构 span 两侧至少一个内容 span 原样不变才可定位；解恰好一个才运行，零个 `DISPLAY_UNMAPPABLE`、多个 `DISPLAY_AMBIGUOUS`——**从不替用户猜** |
| 运行改过的源码 | 不在签名覆盖内：`meta.display` 标明、`coreIrSha256 = null`、还原出的规范源码先写到 stderr；沙箱不变 |
| 导出 | cloud 读 `loadVersionLayout` 并跑完整的 ts `validatePolicyLayout`，通过才随请求发送；aster-api 在签名前用 Java 再跑 L1–L9；任何一步不过就**省略排版**照常导出 `1.0` 包，并在响应头与界面说明原因 |
| 门控 | 沿用：只导出 APPROVED、计划门 `policyExport`、两个包导出开关；新增锁文件 `packageFormats` 机械门（列车把 CLI 升到支持 1.1 之前 aster-api 不会产出 1.1）与 cloud 开关 `policy_export.package.layout.enabled` |

---

## 1. 现状（各仓 `origin/main`，2026-11-20）

aster-api `2b63f1bc`、aster-cloud `1db16067`、aster-lang-ts `e4728cb`（3.5.0）、aster-lang-truffle `248d100`（1.0.52）、aster-lang-core `origin/main`、aster-lang-test `99ef78e`。

### 1.1 现有导出已能本地运行《静夜思》（实测）

| 步骤 | 证据 |
|---|---|
| 导出输入 | cloud 导出路由取 `findDefaultExecutableVersion`（只 APPROVED）→ `loadPackageExportInput`（`src/app/api/policies/[id]/export/package/route.ts:166-181`）→ `loadCedarExportInput` 带冻结 aliasSet、`allowStructural:true`（有别名时）与词汇（`src/lib/cedar-export-input.ts:40-56`）；词汇经 `loadStoredVersionCompileContext` → `withEmbeddedVocabulary` 并入内嵌词条（`src/lib/execution-input/index.ts:183,235-251`，ADR 0060 §7.3）。请求体**没有** layout |
| 组装 | aster-api `PackageAssembler` 写 `policy/policy.aster`、`policy/compile/alias-set.json`、`policy/compile/vocabulary.json`（`PackageAssembler.java:89-92`；`FORMAT_VERSION = "1.0"`，`:31`）；词汇是请求原样的规范 JSON（`PackageExportService.java:204-205`），所以 `literals` 原样进包 |
| 实测导出 | 在 aster-api 测试环境用 `PackageExportService.export` 按 cloud 同形输入（`io.aster.test.JingyesiPolicy`：源码、§8 aliasSet、合并词汇含 `literals:[{思故乡→静夜思}]`、`allowStructural=true`）导出 jre-b 与 node-b 两个包（测试签名密钥 `test-only-export`）。包内 `vocabulary.json` = `{"enumValues":[],"fields":[],"functions":[],"id":"policy.embedded","literals":[{"canonical":"静夜思","kind":"literal","localized":"思故乡"}],…,"structs":[{"canonical":"Text","kind":"struct","localized":"望明月"}],"version":"1"}`；`coreIr.positionFreeSha256 = 55512170…0ec6`（与 ADR 0060 三侧证明同值） |
| 两个 CLI | 发布产物：`aster.mjs`（ts 3.5.0，sha256 `cc36b85c…050f`）与 `aster-cli-1.0.52.jar`（sha256 `fbe136bf…e8a7`），都与各自 GitHub Release 的 `.sha256` 相符。对两个包分别执行 `verify --recompile --probes` → `schema / signature / integrity / recompile / probes` 全 `ok`、退出码 0；`run --input -`（`{}`）→ `{"ok":true,"value":"静夜思"}`、退出码 0。四种组合（2 包 × 2 CLI）结果一致 |
| 旧 CLI 遇到 1.1 | 两个 CLI 对 conformance 夹具 `tamper/format-version-1.1-with-new-field` 都输出 `checks:[{"code":"PKG_UNSUPPORTED_FORMAT","name":"schema","ok":false}]`、退出码 4（`manifest.ts:117`；truffle `ManifestCheck.java:61-62`） |

**结论**：编译与运行《静夜思》所需的一切（内嵌词汇含字面量宏、汉字单词别名、`allowStructural`）今天的包已经带齐，两个 CLI 也都处理正确，本修订**不需要**为此修任何东西。缺的只有显示层：包里的 `policy.aster` 是 `床前 明月光。\n疑是 地上霜，…`，读者看不到原诗。

未实测的一段：生产 cloud → aster-api 的完整链路（会话、计划门、node 目标的 Worker 复核）。代码路径如上，留给计划的最终验收任务在生产上跑一遍。

### 1.2 排版的现状

- 存储：`PolicyVersionLayout`（ADR 0058 §7.2，cloud `drizzle/0068_policy_version_layout.sql`），只插入不更新；读取唯一入口 `loadVersionLayout`（`src/lib/policy-layout/store.ts:152`）复验 layout 哈希、源码哈希、可解析、往返（`verifyRow`，`:136-150`），**不**复跑 `validatePolicyLayout`。
- 保存校验：`prepareLayout`（`store.ts:75-80`）调 ts `validatePolicyLayout(layout, source, getLexicon(locale), aliasSet)`。
- ts `./layout`（`package.json:85-88`）：`parseLayout / serializeLayout / toCanonical / toDisplay / verifyContentParity / validatePolicyLayout / canonicalToDisplay / displayToCanonical / LAYOUT_LIMITS`。`layout_structural_hides_value` 依赖 `src/layout/value-tokens.ts`（587 行）：生产 canonicalize → lex、按行找回 token、对关键词候选做 AST 角色探测（`ROLE_PROBE_BUDGET = 16`），是与 ts 词法器、解析器绑定的启发式。
- Java 侧没有任何 layout 代码（ADR 0058 §7.10「不做 Java 版本」）。
- 《静夜思》排版（cloud `src/config/jingyesi-poem.ts`）：16 个 span，结构 span 是四个 `{canonical:" ",display:""}`（隐去别名后的空格）与四个换标点的 span（`。\n→，\n`、`，→。\n`、`：\n␠␠→，\n`、`。\n→。`）。**隐藏的空格**正是反向映射的难点（§5）。

---

## 2. 包格式（D1–D4）

### D1 文件

```
policy/display/layout.json     # serializeLayout(layout) 的 UTF-8 字节（ADR 0058 §7.3），无 BOM、无末尾换行
policy/display/display.aster   # toDisplay(layout) 的 UTF-8 字节，不加任何字节
```

- 两者都是**载荷**：列入 `files[]`（路径、sha256、size），因而经 manifest 被 Ed25519 签名覆盖，也进入 `SHA256SUMS`（ADR 0059 §6.2，配方不变）。
- 都**不是编译输入**：`compile`、B 包本地重编译、`positionFreeSha256`、`source.envelopeSha256` 一律不读它们（ADR 0058 §7.2 原样适用）。
- `display.aster` 是冗余的：它可由 `layout.json` 算出。保留它是为了不装 CLI 的读者能直接打开原诗；CLI 必须核对它与 `toDisplay(layout)` 逐字节相同（§4 L4），所以它不会成为第二个真相源。

### D2 `formatVersion` 1.1

- manifest schema `manifest-1.1.schema.json` = 1.0 + 可选顶层成员 `display`，形状恰为 `{"layoutVersion":1}`（`additionalProperties:false`；`layoutVersion` 为整数常量 1，对应 ADR 0058 §7.3 的 `v`）。路径固定为 D1 两个，不在 manifest 里另写。
- 语义规则（schema 之后，`PKG_INVALID_MANIFEST`）：`display` 存在 ⇔ `files[]` 同时含 D1 两个路径。`display` 不存在时，`policy/display/*` 若出现在 `files[]` 中只是普通载荷，CLI 不解释（避免给 1.0 包追加含义）。
- **两个版本号的分工**：`formatVersion` 管包级字段与 CLI 行为；`display.layoutVersion` 只是 layout 载荷语法（ADR 0058 §7.3 的 `v`）的副本，L1 要求二者相等。1.1 的 schema 把它定为常量 1；将来 layout 语法若出 v2，随包格式次版本（1.2）一起引入，所以支持某个包格式次版本的 CLI 永远认得该次版本允许的全部 `layoutVersion`，不存在「格式支持、排版版本不支持」的组合，也就不需要单独的错误码。
- 导出：版本带一致且通过校验的排版 → `1.1` + `display`；否则 `1.0`，与今天**逐字节相同**（§6.1 可复现性不受影响）。
- CLI 支持的格式版本变为 `["1.0","1.1"]`（`version` 子命令输出）；按修订 2 R3 的顺序，`formatVersion` 先于 schema 判定，主版本 1、次版本 ≤ 1，再按**该次版本自己的** schema 严格校验（1.0 包用 1.0 schema：1.0 包里出现 `display` 仍是 `PKG_INVALID_MANIFEST`）。
- conformance 中断言「1.1 不受支持」的夹具（`format-version-1.1`、`format-version-1.1-with-new-field`）改为 1.2，继续守住「更高次版本按不受支持拒绝」。

### D3 为什么是次版本而不是「1.0 + 约定路径」

| 方案 | 不选 / 选的理由 |
|---|---|
| 1.0 不改 manifest，只多两个文件 | 旧 CLI 照常 `verify ok`，但从未看过排版；而「1.0 包里的 `policy/display/*` 有特定含义」是给已发布格式追加语义，违背 ADR 0059 §3「不做宽松读取」。读者手里的 `display.aster` 是否与源码一致，旧 CLI 什么也没说 |
| **1.1 + 可选 `display`（选）** | 符合 §3「次版本只能新增可选字段」；旧 CLI 以固定文案 `package format version is not supported` 退出 4，用户知道该升级；不带排版的包仍是 1.0，旧 CLI 照常可用 |
| 2.0 | 没有不兼容变化 |

### D4 旧 CLI 与新 CLI

| | 1.0 包 | 1.1 包 |
|---|---|---|
| 旧 CLI（ts ≤ 3.5.x，aster-cli ≤ 1.0.52） | 不变 | 全部子命令 `PKG_UNSUPPORTED_FORMAT`，退出码 4（已实测） |
| 新 CLI | 输出逐字节不变（`inspect` 无 `display` 成员，`verify` 无新检查项）；`show` 打印规范源码并在 stderr 说明「无显示排版」 | 见 §3 |

A 包（P3 尚未实施）带自己的 CLI；P3 实施时其 CLI 来自锁文件，锁文件的 `packageFormats` 门（D12）保证它支持包的格式。

---

## 3. CLI 行为（D5–D8）

### D5 `verify`

检查项顺序：`schema`、`signature`、`integrity`、`ir`（A 包）、**`display`**（仅 1.1 且有 `display`）、`recompile`、**`display-ir`**（仅 `--recompile` 且有 `display`）、`probes`。第一项失败即停（修订 2 R4 不变）。

- `display`：L1–L7（§4），不编译，失败码 `PKG_DISPLAY_INVALID`（新，退出码 4）。
- `display-ir`：L8–L9，需要编译（与 `recompile` 同一组编译输入），失败码同上。普通 `verify` 不编译 B 包的原则（修订 2 补记 R4）保持不变。

### D6 `inspect` 与 `show`

- `inspect`：manifest 有 `display` 时，`inspect` 对象多一个成员 `display`，原样取自 manifest（与另外五个成员同一规则）；不做排版检查。1.0 包的输出不变。
- `aster show <pkg> [--canonical | --from-display <file>] [--jwks|--pubkey|--allow-unsigned]`（新子命令，两个 CLI 同名同义）：
  - 阶段 usage → read → manifest → signature → integrity →（有 `display` 时）display（L1–L7）。
  - stdout：有排版时为 `display.aster` 的字节加一个 `\n`；无排版或给了 `--canonical` 时为 `policy/policy.aster` 的字节加一个 `\n`（与 `compile` 的「字节 + `\n`」同一约定）。不是 JSON。
  - `--from-display <file>`：按 D7 的还原、L1–L9 全部检查（含编译），stdout 为还原出的规范源码加 `\n`，不求值——`run --display` 的演练。
  - 失败时 stdout 为与 `run` 相同的错误信封（与 `compile` 一致）。
  - stderr：有排版时先打印**结构映射**——每个 `display ≠ canonical` 的结构 span 一行 `display ⇐ canonical`（JSON 字符串形式，去重保序），这是纯文本里对 ADR 0058 §7.7 第 3 条「结构文字可辨认、悬停显示 canonical」的替代；词汇含字面量宏时列出「触发词 → 展开值」（ADR 0058 §7.7 第 7 条：显示的是 `思故乡`，编译的是 `"静夜思"`）；包无签名（`--allow-unsigned`）时提示「显示排版未经签名核验」。
- 选 `show` 而不是 `inspect --display`：`inspect` 的 stdout 是一行 JSON 信封，显示文本是给人读的多行原文；把它塞进 JSON 字符串既不好读也改变 `inspect` 的形状。

### D7 `run --display <file>`：从改过的显示文本还原规范源码

输入：显示文本文件 `E`（严格 UTF-8，去掉至多一个开头 BOM；不存在、不可读、非法 UTF-8 为 `USAGE`；UTF-8 长度 > `LAYOUT_LIMITS.displayBytes`（24 576）为 `DISPLAY_UNMAPPABLE`）。**编辑器补的文末换行**：若 `toDisplay(L)` 不以 `\n` 结尾而 `E` 以 `\n` 结尾，去掉 `E` 末尾的一个 `\n`（及其前紧邻的一个 `\r`）——几乎所有编辑器保存时都会补它，《静夜思》的显示以 `。` 结尾。除此之外不做换行归一：`\r\n` 换行的文件对不上以 `\n` 为界的结构 span，结果为 `DISPLAY_UNMAPPABLE`，stderr 提示「文件使用 CRLF 换行」。包的排版 `L`（合并形态，内容 span 之间必隔着结构 span）先通过 L1–L7。

**一个解** = 给每个内容 span `j` 一个**非空**新文本 `t_j`，使得按 span 顺序拼接（内容 span 取 `t_j`，结构 span 取原 `display`）恰为 `E`，并满足**锚定条件**：每一段「隐藏串」——连续的、`display` 全为空串的结构 span——若两侧都是内容 span，则这两个内容 span 至少有一个 `t_j` 与原文逐字节相同。隐藏串位于文首、文末或与非空 display 的结构 span 相邻时不加条件（它的位置已由文本边界或可见锚点确定）。

解集合由上面的定义唯一确定，与实现算法无关；三处实现只需给出同一个「0 / 1 / ≥ 2」结论与唯一解时的 `L'`。用于诊断的偏移只写 stderr，不进信封、不做跨 CLI 比较。

**判定**：解的个数为 1 → 得到派生排版 `L'`（结构 span 原样，内容换成 `t_j`）与派生规范源码 `C' = toCanonical(L')`；个数为 0 → `DISPLAY_UNMAPPABLE`；≥ 2 → `DISPLAY_AMBIGUOUS`（两者退出码 2，固定文案见 D10；stderr 给出第一个无法定位的位置或前两个解的分歧点）。实现以 (span 下标, 文本位置, 前一内容 span 是否原样) 为状态计数，计到 2 即停；状态数不超过 span 数 × (|E|+1) × 2，即 ≤ 400 × 24 577 × 2。参照实现与向量（连续隐藏串、同一可见锚点在文本中重复出现、相邻多个可见锚点、文首文末隐藏串、内容删空）在 aster-lang-test。

这条规则的含义：结构 span（标点、空白、关键词别名）**不能改**，改了就对不上；隐藏的空格靠「旁边那句没动」定位。以《静夜思》为例：

| 改后的显示 | 结果 |
|---|---|
| 原诗不改 | 1 个解，`C' = C` |
| 末句 `低头思故乡。` → `低头「月光」。` | `低头` 原样 → 1 个解，`C'` 末行 `␠␠低头 「月光」。`，求值 `月光`（两引擎均已实测可编译、补空格不变，§4 L9） |
| 首句 `床前明月光，` → `床前明月照，` | `床前` 原样 → 1 个解 |
| `床后明月照，` | 隐藏空格两侧都改了 → 0 个解，`DISPLAY_UNMAPPABLE` |
| `床前床前明月光，` | 「`床前` 原样」与「`明月光` 原样」各给一个解 → `DISPLAY_AMBIGUOUS` |
| 删掉某个标点、或把 `，` 改成 `、` | 可见锚点对不上 → `DISPLAY_UNMAPPABLE` |

**与 ADR 0058 §7.4 的关系**：§7.4 拒绝「编辑 display、由系统反推 span」，理由是对齐有歧义、由算法替作者选一处。本条不选：有歧义就拒绝，且结构 span 不随编辑移动或增删。§7.4 仍约束平台上**保存**排版的方式；本条只用于本地一次性运行，结果不回写、不签名。

`show --from-display` 与 `run --display` 在还原之前同样先过包排版的 L1–L7（失败 `PKG_DISPLAY_INVALID`）。还原之后依次：派生排版 `L'` 过 L2、L5、L6、L7（上限、内容 span 规则、字符串层面的值检查）→ 用 manifest 的编译输入（locale、aliasSet、allowStructural、词汇）编译 `C'`（失败 `COMPILE_ERROR`）→ L8–L9（失败 `DISPLAY_REJECTED`）→ entry（`--entry` 或 manifest 缺省入口，须存在于**派生模块**，否则 `ENTRY_NOT_FOUND`）→ input（按派生 IR 的形参类型校验，CLI 的输入校验本来就基于 IR 类型，`input.ts:141-168`）→ 沙箱求值，超时与资源规则不变。

### D8 `run --display` 的输出

- stdout 信封形状不变，`meta` 多一个成员 `display: {"canonicalSha256": sha256(C' 的 UTF-8), "matchesPackage": bool}`，`matchesPackage` = `positionFreeSha256(IR(C')) == manifest.coreIr.positionFreeSha256`。
- `coreIrSha256` 为 `null`（执行的不是 manifest 记录的 IR），`irRecompiled` 为 `true`，`signed` 只表示**包**的签名已核验。`meta.display` 只在 `--display` 时出现，所以不带 `--display` 的全部既有输出与夹具逐字节不变。
- 求值之前，stderr 先打印一行说明「正在运行由显示文本派生的程序，不在包签名覆盖之内」，再原样打印 `C'`（`show --from-display` 打印的同一文本）。
- 两个 CLI 归一后 stdout 逐字节相同（修订 1 A0 的归一只动 `engine` / `cliVersion`，`display` 成员两侧本来相同）。跨 CLI 承诺只覆盖 conformance 语料；任意改写的源码两引擎是否给出同一 IR 不在承诺内（同 ADR 0059 §5.3「语料级」口径）。

---

## 4. 排版校验规则 L1–L9（CLI 与 aster-api 共用）

两个 CLI 必须给出同一结论，所以规则只用两侧都已有、且已被 parity 门对齐的能力：字符串运算、`foldKey`（两侧同一 casefold 15.1，见两侧 `toolchainId` 的 `casefold=15.1`）、标识符字符类 `[\p{L}\p{M}\p{Nd}_]`（manifest 规则已在用，修订 2 R6）、编译器与 `positionFreeSha256`（修订 1 A2，ADR 0060 已证《静夜思》两侧同值）。

**规则要守的性质**与 ADR 0058 §7.5 相同：会影响判定结果的**值与名字**只能出现在内容 span 里。ADR 0058 允许关键词、标点与空白作结构 span（guyong 演示的 `是 → ，是那盏`、`记着 → 空`），并以界面上的独立样式与悬停显示 canonical 作补偿（§7.7 第 3 条）。纯文本的 `display.aster` 没有样式，所以本修订在包里**另加一条更严的规则 L7**（运算符不得被藏或改写）并要求 `show` 在 stderr 给出结构映射（D6），用来代替 §7.7 第 3 条。

**共同约定**（三处实现与向量一律按此）：文本是严格 UTF-8 解码后的字符串，偏移单位为 UTF-16 code unit（ADR 0058 §7.8 同一单位）；换行符指 `\n` 与 `\r`，不归一；`foldKey` 先 NFC 再 casefold 15.1（两侧现有实现）；「整词出现」= 在经 `foldKey` 的被查文本中出现，且该出现的前一字符与后一字符（若有）都不属标识符字符类，字符类按各运行时的 Unicode 属性（JDK 25 / Node 24 均为 Unicode 16），向量覆盖组合附加符、代理对、全角与大小写折叠。

| # | 规则 | 需编译 |
|---|---|---|
| L1 | `layout.json` 严格 JSON（包格式的严格 JSON 规则）；结构同 ts `readLayout`（恰为 `{v:1,spans}`、span 恰为两种形态、`text` 与结构 `canonical` 非空、任一视图不拆代理对）；`v` 等于 manifest `display.layoutVersion`；文件字节 = `serializeLayout` 的 UTF-8（规范形态：相邻内容 span 已合并、键序固定、字符串按 ECMAScript `JSON.stringify` 转义） | 否 |
| L2 | `LAYOUT_LIMITS` 六项（源码 12 288 字节 / 200 行 / 400 span / 序列化 49 152 字节 / display 24 576 字节 / 单个结构 display 256 码点），口径同 ts `assertWithinLimits` | 否 |
| L3 | `toCanonical(layout)` = `policy/policy.aster` 的严格 UTF-8 解码（不去 BOM，逐字符相等） | 否 |
| L4 | `display.aster` 字节 = `toDisplay(layout)` 的 UTF-8 | 否 |
| L5 | `verifyContentParity`：至少一个内容 span 在去掉 ECMAScript `String.prototype.trim` 所认的空白后非空；结构 `canonical` 不含 `"“”「」『』` | 否 |
| L6 | 每个结构 span 的 `canonical`：(a) 不含 `\p{Nd}`；(b) 不整词出现词汇任一条目的 `localized`（全部 kind，含字面量触发词）；(c) 不整词出现包 locale 与 en-US 词法表的 TRUE / FALSE / NULL / NONE 拼写 | 否 |
| L7 | **运算符不得被藏或改写**：设 O = ASCII 运算符字符 `+ - * / % < > = ! & \| ^ ~` ∪ 包 locale 与 en-US 词法表中 11 个运算符 kind（core `UserAliasValidator` 的运算符档，ADR 0060 §1.1）的全部拼写 ∪ aliasSet 中这些 kind 的别名。对每个结构 span，`canonical` 中每处 O 的出现（字符按出现、拼写按整词出现）都必须在该 span 的 `display` 中以同一折叠形态出现，且出现次数不少于 canonical 中的次数 | 否 |
| L8 | 编译 `C`（或 `C'`）得到 IR；IR 中键名为 `name` 或 `written` 的全部字符串值不得整词出现在任一结构 span 的 `canonical` 中 | 是 |
| L9 | **补空格不变性**：设 B = 全部结构 span 的起点与终点偏移构成的集合（相邻 span 的公共边界只算一次）。对 B 中每个偏移 p，满足下列全部条件时在 p 处插入一个 U+0020：0 < p < \|C\|；`C[p-1]` 不是换行符；`C[0..p)` 中最后一个换行符（或文首）到 p 之间不全是空格与制表符（即 p 不在行首缩进内）。按 p 从大到小插入得到 `P`；`P` 必须编译成功且 `positionFreeSha256(P) = positionFreeSha256(C)` | 是 |

**各规则抓什么**：`layout_structural_hides_value` 要防的「值 token 与结构 span 重叠」只有两种形态——结构 span 落在一个 token 内部（字符串、标识符、数字被切开），或整个包含一个值 token。前者由 L9 抓：在 token 内部补一个空格必然改变字符串的值或把一个名字切成两个，`positionFreeSha256` 随之改变或编译失败；在 token 边界补空格对 IR 毫无影响。判官是编译器本身，两引擎已逐字节对齐，所以结论两侧一致。后者由 L6（数字、字面量关键词、词汇条目）与 L8（IR 里出现的全部名字）抓；字符串字面量因 L5 禁引号不可能整个落在结构 span 里。L9 **不**判断运算符：`x - 1` 中把 `-` 整个标为结构 span，补空格后 IR 不变，这正是 L7 存在的原因（独立评审给出的反例，已列入向量）。

实测（ts 3.5.0 / aster-cli 1.0.52 的 `compile`，zh-CN + §8 aliasSet + 词汇）：《静夜思》原文与补空格后都得 `55512170…`；改成 `低头 「月光」` 后原文与补空格都得同一哈希（两引擎相同）；en-US 构造例 `Return "a + b".`（结构 span `+`、display `plus`，读者看到 `"a plus b"`）补空格后哈希改变（两引擎相同）——L9 拒绝（同时 L7 也拒绝：`+` 未出现在 display 中）。《静夜思》的结构 span 只有空格与 `。，：` 和换行，L6–L9 都通过；guyong 演示的结构 span（`我曾问`、`心里`、`记着`、`是`、`答一句` 与标点）不含运算符与 IR 名字，同样通过（它用自定义词法表与单字别名，本来就不能作为策略保存，这里只作误拒检查）。

**残余风险（接受）**：①结构 span 里的非运算符关键词（如 `如果`、`否则`、`返回`）可以显示成别的文字——这是 ADR 0058 允许的排版能力，包里以 `show` 的结构映射（D6）补偿；②一个只含关键词的结构 span 在**改过的**源码里被解析器读成名字（软关键字角色变化）：L8 抓住它成为 IR 名字的情形，抓不住的只剩不改变 IR 的情形。平台保存时仍跑完整的 ts `validatePolicyLayout`（ADR 0058 §7.5 不变），签名包里的排版两套规则都过了（D11）；本地 `run --display` 的结果不签名、先打印 `C'`。

**与 ts `validatePolicyLayout` 的关系**：平台保存与导出（cloud）继续用它，它的词法规则更细；CLI 与 aster-api 用 L1–L9。两套规则都必须通过排版才进包（D11）。CLI 不调用 ts 的 `layout_structural_hides_value`：否则 Node CLI 会拒绝 JRE CLI 接受的包，违背两个 CLI 同义同退出码。

---

## 5. Java 版本（D9）

- **新增 aster-lang-core `aster.core.layout`**（纯 Java，无新依赖）：`PolicyLayout` / `LayoutSpan`、`parse`（严格）/ `serialize` / `toCanonical` / `toDisplay`、`LayoutLimits`、`checkFile`（L1–L7）、`reconstruct`（D7）、`padStructural`（L9 的 `P`）、`structuralNameHits`（L8 的字符串部分）。truffle CLI 与 aster-api 都依赖 core，各自只写编排。随列车 eco-1.0.53。
- **ts** 在 `./layout` 增加同名函数（`checkPackageLayout`、`reconstructFromDisplay`、`padStructuralSpans`、`structuralNameHits`），CLI 在 `src/policy-package/` 编排；`validatePolicyLayout` 不变。
- **共享真相源**：aster-lang-test `conformance/package-v1/vectors/layout.json`（序列化与转义、解析拒绝、上限、L3–L6）、`display-reconstruct.json`（D7 的解个数与 `C'`）、`display-padding.json`（L9 的 `P`）、`keyword-spellings.json`（L6(c) 与 L7 用到的各 locale 字面量关键词与运算符拼写）。core 与 ts 各存逐字节副本并断言 sha256（ADR 0045 / ADR 0060 T3 同法）。
- **不移植 `value-tokens.ts`**：它依赖 ts 的 canonicalize 改写回找、行内宽松回退、AST 角色探测与探测预算；Java 侧的 ANTLR 解析器给不出同一组中间结果，移植后两侧在边界输入上必然分歧，且分歧无法用向量穷举。L9 把同一问题交给已对齐的编译器。
- ADR 0058 §7.10「不做 Java 版本」据此修订：Java 版本限于 L1–L9 与反向映射，`validatePolicyLayout` 仍只在 ts。

---

## 6. 错误码与输出（D10）

`exit-codes.json` 新增（固定 message，修订 2 R1）：

| 码 | 退出码 | message |
|---|---|---|
| `PKG_DISPLAY_INVALID` | 4 | `package display layout is not valid` |
| `DISPLAY_UNMAPPABLE` | 2 | `edited display text does not map onto the package layout` |
| `DISPLAY_AMBIGUOUS` | 2 | `edited display text maps onto the package layout in more than one way` |
| `DISPLAY_REJECTED` | 2 | `edited display text would hide a value from readers` |

`envelope.schema.json`：`Meta` 增加可选 `display`（D8）；`Inspect` 增加可选 `display`（D6）；`Check.name` 增加 `display`、`display-ir`。`show` 成功时 stdout 非 JSON，与 `compile` 同列。命令行：`show` 认 `--canonical`、`--from-display`（二者互斥，同给为 `USAGE`）与信任锚选项；`run` 认 `--display`。都是 CLI 输出码，不是编译诊断码，不进 ADR 0031 的 E 码表（同 `PROBE_MISMATCH`）。

---

## 7. 导出（D11–D13）

### D11 校验链与「省略而非拒绝」

1. **cloud**（`POST /api/policies/[id]/export/package`）：`loadVersionLayout(version.id)`；`status:'ok'` 时再用 `prepareLayout` 同一校验（`validatePolicyLayout`，版本 locale 的词法表 + 冻结 aliasSet）；通过且开关 `policy_export.package.layout.enabled` 为真 → 请求体带 `layout`（序列化文本）。target=node 时 Worker 复核（`runNodeCheck`）另用 ts 跑 L1–L9，不过则不带 layout。
2. **aster-api**（签名方）：收到 `layout` → 用 core `aster.core.layout` 跑 L1–L9（L8/L9 用平台编译器与本次导出相同的编译输入）→ 锁文件该 target 的 `packageFormats` 含 `"1.1"` → 写 D1 两个文件、`formatVersion:"1.1"`、`display`。任何一步不过 → 不写排版、`1.0`。**签名的包一定通过 CLI 的 `verify`**：这是本节的不变式，由 aster-api 在签名前跑同一规则保证。
3. **省略而非拒绝**：排版不是编译输入，排版问题不应阻止导出可执行策略。响应头 `X-Aster-Package-Layout: included | absent | omitted`，`omitted` 时另有 `X-Aster-Package-Layout-Reason`（`layout_inconsistent`、`layout_invalid`、`layout_export_disabled`、`node_display_check_failed`、`display_rules_failed`、`cli_unsupported`）；cloud 原样转发，导出面板在下载完成后显示一行说明。（产品负责人可改为拒绝，见 §11 Q1。）
4. 排版随版本导出，**与导出者有没有 `structured_authoring` 能力无关**：显示排版对所有有读权限的人可见（ADR 0058 修订 1 A1），导出只是另一种读。

### D12 接口与锁文件

- aster-api `PackageExportRequest` 增加可选 `layout: string`（≤ 49 152 字节，超出 400 `layout_too_large`）；`check` / `prepare` 不接受它（排版不影响可导出性与探针）。
- `src/main/resources/export/export-runtimes.lock.json` 每个 target 增加 `packageFormats`（字符串数组），由列车 L4 pin bump 维护；字段缺失按 `["1.0"]` 读，字段存在但不是由已知格式版本组成的非空数组 → aster-api 启动失败（同修订 3 P2-R2「配置畸形 → 启动失败」）。列车把 CLI 升到 eco-1.0.53 / ts 3.6.0 时同时写入 `"1.1"`——在此之前 aster-api 产出的永远是 1.0，B 包 README 指向的 CLI 版本必然认得包格式（同修订 3 P2-R3 的「锚先于签名」思路）。
- 审计 `POLICY_EXPORTED` 增加 `layoutSha256`（或 null）与 `layoutOutcome`（`included` / `absent` / `omitted:<reason>`）。
- 包内 `README.md`（en / zh / de 模板）在有排版时增加一节：`display.aster` 是什么、不参与编译、`aster show` 与 `aster run --display` 的用法与限制。

### D13 门控（不变 + 两项新增）

| 门 | 状态 |
|---|---|
| 只导出 APPROVED（`findDefaultExecutableVersion`，无则 409） | 不变；排版取自**同一版本**的 `PolicyVersionLayout` 行 |
| 计划门 `PlanCapabilities.policyExport`（free 不可；trial / pro / team / enterprise 可） | 不变，排版不另设计划门 |
| aster-api `aster.export.package.enabled`、cloud `policy_export.package.enabled` | 不变 |
| **新** cloud PlatformSetting `policy_export.package.layout.enabled`（缺行 false） | 关时不发送 `layout`，原因 `layout_export_disabled` |
| **新** 锁文件 `packageFormats` | D12，机械门，随列车 |
| Cedar 导出 | 不变，不带排版（Cedar 不是源码） |

---

## 8. 可复现与签名覆盖（D14）

- §6.1 的可复现元组增加「排版序列化字节（或无）」与锁文件 `packageFormats`。排版进入 `files[]`，所以任何改动都改变 manifest 哈希；`envelopeSha256` 不变（ADR 0058 §7.2 的「附与不附 layout，envelope 相同」原样成立）。测试：同一版本附与不附排版导出，`source.envelopeSha256` 与 `coreIr.*` 相同、manifest 不同；不带排版的导出与本修订之前逐字节相同。
- 签名配方、信任锚、JWKS、`kid` 轮换全部不变；新文件只是多两个载荷。

---

## 9. 安全（D15）

| 威胁 | 控制 |
|---|---|
| 篡改 `display.aster` 让读者看到与执行不同的诗 | 签名覆盖（`files[]`）+ `verify` 的 `display` 检查（L3、L4）+ `show` 先过同样检查；无签名包 `show` 在 stderr 提示未核验 |
| 签名包里排版本身有欺骗（平台漏判） | 保存时 ts `validatePolicyLayout`、导出时 cloud 再跑一次、aster-api 签名前跑 L1–L9，三道；L7 比平台规则更严（运算符不得藏或改写）|
| 结构关键词显示成别的词（ADR 0058 允许的排版能力） | `show` 在 stderr 打印结构映射（D6）；README 说明 `display.aster` 中哪些文字只是排版 |
| `run --display` 借排版夹带改写 | 结构 span 不可改；反向映射唯一才运行；派生排版过 L5–L9；`C'` 先打印；`meta.display` 与 `coreIrSha256:null` 标明不在签名内 |
| 把 `run --display` 当成「运行任意源码」的后门 | 它本就是本地用户在自己机器上运行自己写的文本；能力与 `compile` + 无签名包 `--allow-unsigned` 相同。沙箱不变：JVM `HostAccess.EXPLICIT`、`IOAccess.NONE`；Node 在 Worker 内、无 IO builtin；导出时的闭世界检查 E730–E733 不在 CLI 重跑，IO / workflow 在两引擎中都只能以 `EVAL_ERROR` 失败（ADR 0059 §1、§5.1） |
| 资源耗尽 | 显示文本 ≤ 24 576 字节、span ≤ 400、解计数到 2 即停（状态数有界，见 D7）；`layout.json` 先按 49 152 字节判大小再解析；派生源码受 L2 上限；L9 只多一次编译，编译本身沿用编译端点的解析预算；求值超时规则不变 |
| 错误信息泄露 | 信封 message 是固定文案（修订 2 R1）；诊断只写本地 stderr。aster-api 省略排版时只回原因码，不回排版内容 |
| zip-slip / 路径 | 两个固定 ASCII 路径，既有路径规则覆盖 |
| 字面量宏误导（ADR 0058 §7.7 第 7 条） | `show` 在 stderr 列出触发词与展开值 |
| 隐私 | 排版只含作者写的文本，与源码同一受众；探针仍不取真实输入（§5.3 不变） |

---

## 10. 对其他 ADR 的修订（D16）

- **ADR 0058 §7.2 读取契约**：「版本列表、执行、导出接口不调用它」改为「执行不调用它；策略包导出调用它，只为把排版作为非编译载荷放进包」。§7.7 第 5 条「下游不读 layout」对编译、执行、证据、Cedar、What-If、回放、Guard 不变；导出读它但不编译它。
- **ADR 0058 §7.4**：平台保存规则不变；本地 CLI 增加 D7 的唯一解反向映射（不回写、不签名）。
- **ADR 0058 §7.10**：见 D9。
- **ADR 0059 §2 / §3 / §4.1 / §6.1 / §7 / §8**：分别见 D1、D2、D5–D8、D14、D11–D12、D12。

---

## 11. 待产品负责人确认

| # | 问题 | 推荐 |
|---|---|---|
| Q1 | 排版过不了校验时：省略排版照常导出，还是整次拒绝 | **省略**并提示原因（D11）。排版不改执行内容，拒绝会让一条已批准、可执行的策略因显示层问题导不出 |
| Q2 | 导出面板是否提供「不含排版」选项（给仍用旧 CLI 的人得到 1.0 包） | **不提供**。旧 CLI 已明确失败并提示升级；多一个选项多一个可复现维度。真有需要再加 |
| Q3 | `run --display` 是否随 eco-1.0.53 一起交付 | **是**。它是本需求「改了布局的源码本地运行」的直接答案，也是最终验收的一部分；L9 与反向映射已有实测与明确规则 |
| Q4 | `show` 在无排版的包上打印什么 | **规范源码**，stderr 注明无排版（与 ADR 0058 修订 1「有排版先显示排版」同向） |
| Q5 | 文件名 `display.aster` | **保留**负责人的命名。代价：有人对它跑 `aster compile display.aster` 会得到编译错误；README 与 `show` 的 stderr 说明它不是编译输入 |

## 12. 裁决（PROPOSED）

| # | 裁决 |
|---|---|
| D1 | 包带 `policy/display/layout.json` 与 `policy/display/display.aster`，均为签名覆盖的载荷，均非编译输入 |
| D2 | 带排版的包 `formatVersion 1.1` + `display:{"layoutVersion":1}`；不带排版仍为 1.0 且逐字节不变 |
| D3 | 不采用「1.0 + 约定路径」 |
| D4 | 旧 CLI 对 1.1 以 `PKG_UNSUPPORTED_FORMAT` 退出 4（既有行为，已实测） |
| D5 | `verify` 增加 `display`、`display-ir` 检查项与 `PKG_DISPLAY_INVALID` |
| D6 | `inspect` 原样带出 `display`；新子命令 `show`（`--canonical`、`--from-display`） |
| D7 | `run --display` 的唯一解反向映射与锚定条件 |
| D8 | `run --display` 的 `meta.display`、`coreIrSha256:null`、stderr 先打印 `C'` |
| D9 | Java 版本放 aster-lang-core `aster.core.layout`；不移植 `value-tokens.ts`；四组共享向量 |
| D10 | 四个新输出码 |
| D11 | cloud 全量 ts 校验 + aster-api 签名前 L1–L9；不过则省略排版 |
| D12 | 请求字段 `layout`；锁文件 `packageFormats`；审计与 README |
| D13 | 门控不变，新增 cloud 开关与锁文件门 |
| D14 | 可复现元组加排版；签名配方不变 |
| D15 | §9 安全控制 |
| D16 | 修订 ADR 0058 §7.2、§7.4、§7.10 |
