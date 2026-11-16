# ADR 0059：可执行策略包导出 —— Node / JRE × 自带运行时 / 通用 CLI

- 状态：**PROPOSED**（2026-11-16；§12 的待定项须用户确认）
- 提出：2026-11-16；来源 wontlost-ltd/aster-cloud#797（用户需求原文：「Add the function to export ready to execute policy (node and jre, orgininal policy with dedicated runtime or original policy, metadata and common compiler and run cli)」）
- 相关：ADR 0047（Cedar 导出：只导出可执行版本、探针等价性、`POLICY_EXPORTED` 审计事件，本 ADR 沿用）、ADR 0055（Core IR v2 规范字节）、ADR 0056（双引擎推断统一，豁免表已清空）、ADR 0051 §7（5 段 source envelope）、ADR 0040 §11–§12（Ed25519 + `jcs-sha256/v1` 签名配方、JWKS）、ADR 0054（Guard 执行点）、ADR 0053 / A1（发布列车与 aster-api pin）、ADR 0031（错误码单一来源）
- 范围：只定格式、命令、边界与分期，不含实现；实施计划另写

---

## 0. TL;DR

用户在策略详情页把一个**已批准版本**导出为可在平台外运行的策略包。两个平台 × 两种形态：

| | **A 自带运行时**（下载即跑，只需已装 Node / JRE） | **B 轻量**（源码 + 元数据，用通用 CLI 编译并运行） |
|---|---|---|
| **Node** | `policy/` + `compiled/core.json` + `runtime/aster.mjs`（单文件、零依赖）+ `run.sh` / `run.cmd` | `policy/` + `manifest.json`；`npm i -g @aster-cloud/aster-lang-ts` 后 `aster run` |
| **JRE** | 同上，运行时换成 `runtime/aster-cli.jar`（fat jar，Truffle 回退解释器） | 同上；从 aster-lang-truffle 的 GitHub Release 下载 `aster-cli-<ver>.jar` 后 `java -jar aster-cli.jar run` |

五个决定：

| 决定 | 内容 | 不做什么 |
|---|---|---|
| 一个 CLI，两种形态共用 | A = B + 钉住版本与哈希的 CLI 产物放进 `runtime/`；「专用运行时」就是同一个通用 CLI 的固定版本 | 不为 A 另做一套运行时；不新增代码生成后端（编译到 JS / 字节码） |
| 执行的是平台产物 | A 执行平台编出的规范 Core IR v2 字节；B 在本地重编译，**字节必须与 `manifest.coreIr.sha256` 相同**才运行 | 不在 IR 不一致时静默运行 |
| 只导出纯策略 | 导出时静态检查：IO 类能力、workflow / await、目标引擎不支持的 builtin 一律拒绝（E730–E733） | 不在包里模拟 IO、facts 签发或 Guard 收据 |
| 包不是 Guard 执行点 | 结论按数据返回；`require_approval` / `escalate` 只是返回值；无收据、无审批、无审计链 | 不签发、不伪造执行收据 |
| 可复现 + 可验签 | 确定性 zip；`SHA256SUMS`；manifest 经 `jcs-sha256/v1` 后由平台导出密钥 Ed25519 签名；`aster verify` 离线 / 在线验签并跑包内探针 | 不用 JWT/JWS；manifest 不含时间戳 |

---

## 1. 现状（各仓 `origin/main`，2026-11-16）

aster-lang-ts `54c49c9`、aster-lang-core `622a500`、aster-lang-truffle `bb28d96`、aster-lang-platform `d325125`、aster-lang-test（parity 门）、aster-guard `3d48f86`、aster-api `a7a07513`、aster-cloud `d24f4b2f`。

| 已有 | 位置 | 缺 |
|---|---|---|
| ts 有 `aster` bin（cac）：`parse / core / jvm / class / jar / truffle / install / …`；`core` 已输出规范 IR | `package.json:90-94`，`scripts/aster.ts` | 无 `run / verify / inspect`；`jvm / class / jar / truffle` 依赖本仓 Gradle，不能对外 |
| ts Core IR 树遍历解释器 `evaluate(core, fn, context)`，Decimal 锁死精度 | `src/core/interpreter.ts:220`（导出于 `src/browser.ts:57`） | 不实现 `IO.*`、`PII.unwrap`、`Text.redact`（`:669`）；`Await` 直接报错（`:1098`） |
| ts 严格读入规范 IR：`readCoreIr` | `src/core/read-core-ir.ts:50`，`src/cli/utils/core-ir-file.ts` | — |
| ts 在 npmjs 公开发布（`publishConfig.access: public`），Node ≥ 24，运行时依赖仅 `semver / decimal.js / ajv` | `package.json:154,163-171,243` | `license` 字段写 `MIT`，`LICENSE` 文件是 Apache-2.0（§11 R6） |
| Truffle 语言 `aster`：输入以 `{` 开头即按 Core IR JSON，否则走 Java `CnlCompiler`（ANTLR → `AstBuilder` → `CoreLowering`） | `aster-lang-truffle/.../AsterLanguage.java:25,104-125`，`CnlCompiler.java:60-117` | — |
| 开发用 `Runner <file> [--func=] [-- args]`，`application` 插件 + native-image 配置 | `Runner.java:8-147`，`build.gradle.kts:78-80,~170-215` | `allowAllAccess(true)`（`:77-79`），与生产 `TrufflePolicyRuntime` 的 `HostAccess.EXPLICIT + IOAccess.NONE` 不同；无 fat jar；无子命令 |
| Truffle 主依赖只有 `truffle-api / graal-sdk`（UPL），runtime / compiler 仅在测试 | `build.gradle.kts:59-61,71-74` | 主依赖还拉了 `quarkus-core`（`:67`），体积与必要性待核（§11 R3）；Java 工具链 25 |
| JVM 构件 `cloud.aster-lang:*` 1.0.48，**只发 GitHub Packages**（读也要 token） | core `build.gradle.kts:10,21-35`，platform `:305,372-382` | 客户无法匿名解析；无 Maven Central |
| 生产执行：aster-api `TrufflePolicyRuntime` 上下文池 + wall-clock 看门狗 | `src/main/java/io/aster/policy/runtime/TrufflePolicyRuntime.java:58-125` | — |
| 运行时效果：`AsterRootNode` 顶层允许效果为空 = 全拒；IO builtin 检查后恒抛 `UnsupportedOperationException` | truffle `AsterRootNode.java:53-58`，`Builtins.java:1164-1192,1498-1504` | 平台内本来就跑不了 IO，导出无需「补」 |
| 能力分类：IO 类 Http/Network/Sql/Time/Files/Secrets/Process/AiModel/Payment/Inventory；CPU 类 Crypto/Cpu | core `capability/CapabilityKind.java` | — |
| 双引擎一致性：IR 逐字节门 `VALUE_EXEMPTIONS = []`；eval 门 960/960 identical（215 个可求值样本 100% 覆盖），CI 执行 | aster-lang-test `scripts/parity-tier1.mjs:742`，`EVAL-EXEMPTIONS.md`，`.github/workflows/ci.yml:489` | 只覆盖语料，不是对任意策略的证明 |
| 编译输入的完整身份：5 段 envelope（content、aliasSet、locale、toolchainId、词汇作用域） | aster-api `PolicyVersion.java:453`；cloud `src/lib/policy-alias.ts:101` | — |
| cloud 版本存在 cloud 库，调用 aster-api 走源码模式；`findDefaultExecutableVersion` + `loadStoredVersionCompileContext`（冻结词汇） | cloud `src/services/policy/default-executable-version.ts:22`，`src/lib/execution-input/index.ts:173` | — |
| Cedar 导出：cloud `POST /api/policies/[id]/export/cedar` → aster-api `exportCedar`；无计划门，只有访问 + 限流；无可执行版本 409 | cloud `src/app/api/policies/[id]/export/cedar/route.ts:111-157` | 导出按钮正由 cloud#786 / PR #790 移入详情页右上按钮组 |
| 计划门：`PlanCapabilities`（`evidenceExport` 等），服务端 `hasFeatureAccess(userId, cap)`；证据包导出是唯一按计划门控的导出 | cloud `src/lib/plans.ts:66-79,135`，`src/lib/usage.ts:223`，`src/app/api/reports/route.ts:64-71` | — |
| Ed25519 + JCS 签名与 JWKS 验签（JDK 内建 `EdDSA`；SDK 侧 `node:crypto`） | ADR 0040 §12；aster-guard `src/hash/jcs.ts`、`src/receipt.ts` | 无「导出」用途的密钥 |
| aster-guard 0.3.0：`GuardClient` + `aster-guard-mcp`，全是平台客户端 | aster-guard `src/client.ts`、`src/mcp/*` | 无本地求值能力；不是运行器 |
| cloud 运行在 Cloudflare Workers（OpenNext） | cloud `wrangler.toml`、`open-next.config.ts` | 不宜在 Worker 内组装数十 MiB 的 zip |

结论：两种运行时都已存在（ts 解释器、Truffle），都能直接吃规范 Core IR v2；缺的是**对外的 CLI、包格式、导出端点与分发渠道**。

---

## 2. 决策一：包布局（四种组合）

zip 根目录名为 `<moduleName>-v<version>-<target>-<mode>/`（`target ∈ {node, jre}`，`mode ∈ {a, b}`）。

```
<root>/
  manifest.json            # §3，四种组合同一 schema
  manifest.sig.json        # §6，平台签名（可缺省：未配置导出密钥的部署）
  SHA256SUMS               # 除自身与 manifest.sig.json 外所有文件
  README.md                # 生成：如何运行、限制（§5）、验签方法；四语按导出者界面语言
  policy/
    policy.aster           # 原始源码（字节不变）
    compile/alias-set.json # 版本冻结的 aliasSet（规范 JSON；无则 null）
    compile/vocabulary.json# 冻结词汇（规范 JSON；无则文件缺省）
  schema/input.json        # 各入口参数的 JSON Schema（来自 /policies/schema 同一生成器）
  schema/output.json
  tests/probes.json        # §5.3，导出时由平台引擎算出的期望输出
  # —— 以下仅 A ——
  compiled/core.json       # 平台编出的规范 Core IR v2 字节
  runtime/aster.mjs        # node-a：单文件 CLI（esbuild，零依赖）
  runtime/aster-cli.jar    # jre-a：fat jar
  run.sh / run.cmd         # 薄启动器：node runtime/aster.mjs run . "$@" 或 java -jar …
  LICENSES/                # 仅 A：运行时及其三方依赖的许可证文本
```

- **B 不带 `compiled/`**：B 的语义就是「用通用编译器从原始源码得到同一产物」，IR 只以哈希出现在 manifest；本地编译结果与哈希不同即拒绝（§4.2）。
- **A 也带 `policy/`**：原始源码是审计依据，`aster verify --recompile` 可在 A 包上用自带 CLI 重新编译比对。
- `policy.aster` 为单文件：cloud 版本是单模块；跨模块导入（若将来支持）在 manifest `format` 升主版本时再定。

---

## 3. 决策二：manifest schema（`aster-policy-package/v1`）

```json
{
  "format": "aster-policy-package/v1",
  "target": "node" | "jre",
  "mode": "a" | "b",
  "policy": {
    "moduleName": "credit.limit",
    "entries": [{ "function": "decide", "default": true, "inputSchema": "#/…", "outputSchema": "#/…" }],
    "ruleId": "…", "profile": "…", "controls": ["…"]
  },
  "source": {
    "claimedRef": { "policyId": "…", "version": 7, "status": "APPROVED", "approvedAt": "…" },
    "sha256": "<policy.aster>",
    "locale": "zh-CN",
    "allowStructural": false,
    "envelopeSha256": "<ADR 0051 §7 五段>"
  },
  "coreIr": { "version": 2, "sha256": "<规范字节>", "toolchainId": "…" },
  "effects": { "declared": ["Cpu"], "io": false },
  "governance": { "enforced": false, "verdict": true, "factSources": ["…"] },
  "runtime": {
    "engine": "aster-lang-ts-interpreter" | "aster-lang-truffle",
    "cli": { "name": "@aster-cloud/aster-lang-ts" | "cloud.aster-lang:aster-cli", "version": "…", "sha256": "<仅 A>" },
    "requires": { "node": ">=24" } | { "java": ">=25" },
    "ecosystem": "1.0.48"
  },
  "tests": { "probes": 42, "probesSha256": "…", "nodeChecked": true },
  "files": [{ "path": "policy/policy.aster", "sha256": "…", "size": 1234 }],
  "exportedBy": { "issuer": "aster-cloud", "tenantId": "…" }
}
```

- **不含时间戳与随机量**：导出时间只进审计事件（§8），保证同一输入的 manifest 与 zip 逐字节相同（§6.1）。
- `claimedRef` 沿用 ADR 0047 §6 语义：cloud 自述、aster-api 未核验；其真实性靠签名（签名方是平台）。
- `governance.factSources` 仅提示：档案若声明了签名事实来源，包内**不**验证（§5.2）。
- JSON Schema 文件随 CLI 一起发布（ts 与 JVM 各一份副本 + sibling parity 测试，ADR 0045 模式），CLI 读入 manifest 时严格校验。

---

## 4. 决策三：通用 CLI

### 4.1 命令（两平台同名同义同退出码）

| 命令 | 作用 |
|---|---|
| `aster compile <pkg\|file.aster> [--locale] [--alias-set] [--vocabulary] [--out]` | 源码 → 规范 Core IR v2 字节；给包目录时编译输入一律取自 manifest |
| `aster run <pkg> [--entry <fn>] --input <file\|-> [--timeout 5s]` | 校验 `SHA256SUMS` → 取 IR（A 读 `compiled/core.json`；B 本地编译并比对 `coreIr.sha256`）→ 按 `schema/input.json` 校验输入 → 求值 → stdout 输出 `{"ok":true,"value":…}` 或 `{"ok":false,"error":{code,message}}`（值序列化与 aster-api `/evaluate-json` 同一形态） |
| `aster verify <pkg> [--jwks <url>\|--pubkey <file>] [--recompile] [--probes]` | 哈希、签名（§6.2）、manifest schema、运行时兼容；`--recompile` 重编译比对；`--probes` 跑 `tests/probes.json` |
| `aster inspect <pkg>` | 打印入口、输入输出 schema、效果、治理提示、运行时要求 |
| `aster version` | CLI 版本、引擎、支持的 `format` / Core IR 版本、toolchainId |

退出码：0 成功；1 策略求值错误（`ok:false`）；2 输入不合 schema；3 完整性 / 签名失败；4 包或 IR 不受支持（格式、版本、效果）；5 超时。`run` 不联网。

### 4.2 IR 一致才运行

B 在本地编译后字节与 `coreIr.sha256` 不同 → 退出码 4、错误码 `PKG_IR_DRIFT`，提示改用 A 包或安装 manifest 记录的 CLI 版本。`--allow-ir-drift` 可强制运行，但输出中带 `"irDrift": true`。理由：B 换了编译器版本仍「看起来能跑」是最危险的失败形态；ADR 0055 §5 的规范字节与 0056 清空的豁免表让「逐字节相等」成为可执行的判据。

### 4.3 沙箱与资源

JVM CLI 用生产同一配置（`HostAccess.EXPLICIT`、`IOAccess.NONE`、禁线程 / 进程 / native），**不**沿用 `Runner` 的 `allowAllAccess`；两侧都设 wall-clock 超时（默认 5 s，与平台看门狗同向）。顶层允许效果为空（全拒），与 `AsterRootNode` 现状一致。

### 4.4 实现落点与分发

| | Node | JVM |
|---|---|---|
| 仓 | aster-lang-ts：`aster` bin 增加 `run / verify / inspect`，`compile` 为 `core` 的别名；同一实现经 esbuild 打成 `dist/runner/aster.mjs`（零依赖单文件，随 npm 包发布） | aster-lang-truffle 新 Gradle 子项目 `aster-cli`（picocli，Apache-2.0）：fat jar，含 core 编译器、truffle、locales；不含 Graal compiler（回退解释器） |
| 分发 | npmjs 公开包 `@aster-cloud/aster-lang-ts`（已有 trusted publishing） | aster-lang-truffle GitHub Release 资产 `aster-cli-<ver>.jar` + `.sha256`（公开仓，匿名可下载）；同时发 GitHub Packages 供内部 |
| 版本 | 跟随 aster-lang-ts 版本（当前 3.x） | 跟随 truffle 版本（列车 lockstep，当前 1.0.48） |

- **版本关系**：manifest `runtime.ecosystem` 记录平台目录版本（`asterLang`）；列车 `release-plan.json` 已把 ts 版本与 JVM 版本绑在同一列车上，CLI 不另立版本号。
- **兼容规则**：CLI 接受 `format` 主版本相同、Core IR 版本受支持的包；A 包永远用自带 CLI，无兼容问题。
- **aster-api 内置运行时产物**：A 包所需的 `aster.mjs` 与 `aster-cli.jar` 在 aster-api 镜像构建时按 `export-runtimes.lock.json`（版本 + sha256）取入，导出时不联网；锁文件由列车 L4 pin bump 一并更新（ADR 0053 A1）。

---

## 5. 决策四：平台外支持什么、导出时拒绝什么

### 5.1 导出时静态拒绝（整体拒绝，给出全部诊断；ADR 0031 单一来源，同步全部副本）

| 码 | 条件（在入口函数及其可达函数的 Core IR 上判定） | 理由 |
|---|---|---|
| E730 | 声明或推得 IO 类能力（Http/Network/Sql/Time/Files/Secrets/Process/AiModel/Payment/Inventory） | 两引擎都无真实 IO；`Time` 还破坏可复现。CPU 类（Cpu、Crypto）允许 |
| E731 | workflow、`Async`、`await` | ts 解释器不支持 await；Truffle workflow 依赖 EventStore |
| E732 | 使用目标引擎未实现的 builtin（Node：ts `BUILTIN_ARITY` 表之外，如 `PII.unwrap`、`Text.redact`；JRE：Truffle `Builtins` 之外） | 包能导出就必须能跑完 |
| E733 | 入口参数或返回类型无法生成 JSON Schema（函数类型等） | `run --input` 无法表达 |

另：无可执行版本 → cloud 409（同 ADR 0047 §6，只导出 secure-execute 默认会执行的 APPROVED 版本；不导出草稿，否则等于绕过审批）。超限 → E734（§9）。

### 5.2 平台外行为

- **Guard / 治理**：包不是执行点（ADR 0054）。`Verdict` 四种结论按数据返回，调用方自行处理 `require_approval` / `escalate`；没有审批闭环、没有执行收据、没有证据链。`manifest.governance.enforced=false` 与 README 明示。
- **facts**：签名事实（ADR 0040 §11）在包内不验签、不解析来源；输入按调用方给出的值信任。`factSources` 非空时 `inspect` / `run` 在 stderr 打一行提示。
- **收据**：CLI 不产出任何形似收据的签名对象。需要可审计执行的调用方应调用平台 API。
- **撤销**：导出后平台撤销 / 弃用版本对已下发的包无效（离线无法强制）。见 §12 Q7。

### 5.3 双引擎结果一致性：能承诺什么

- **JRE**：与生产同一引擎（Truffle）、同一 IR 字节；差别只在 JIT（回退解释器只影响速度）。承诺结果一致。
- **Node**：引擎是 ts 解释器，不是生产引擎。承诺的边界是：①IR 逐字节相同（A 由构造保证，B 由 §4.2 保证）；②语料级 eval 门（960/960，CI 阻塞）；③**每个导出包**的探针：导出时按 ADR 0047 §5 同一思路从输入 schema 生成探针（每个枚举值、数值字面量 ±1、可选字段存在 / 缺失，上限 256 条），由 aster-api Truffle 求出期望输出写入 `tests/probes.json`。aster-api 运行镜像没有 Node（`Dockerfile` 末段为 ubi-micro，Node 只在构建阶段），因此 Node 侧比对放在 cloud：cloud 先调 `check`（§7）拿到规范 IR 与探针，在 Worker 里用自身依赖的 `@aster-cloud/aster-lang-ts/browser`（`readCoreIr` + `evaluate`）逐条求值，任一不一致 → E735 拒绝导出；cloud 依赖的 ts 版本必须等于 `runtime.cli.version`，否则 503 `export_runtime_skew`（只在列车换版的窗口出现）。cloud 把探针集的 SHA-256 与比对结果交给 `export/package`，aster-api 重算哈希确认是同一批探针后写入审计（`nodeProbes.checkedBy:"aster-cloud"`，与 `claimedRef` 同为调用方自述）。直接用 API Key 调 aster-api 的 Node 导出不做此比对，manifest `tests.nodeChecked=false`。用户侧 `aster verify --probes` 随时可复核。探针是抽样不是证明，README 写明。
- 探针**不取**真实执行记录的输入（避免把客户数据写进可外传的包）。

---

## 6. 决策五：完整性与签名

### 6.1 可复现

zip 条目按路径字节序；mtime 固定 1980-01-01T00:00:00；权限 0644（`run.sh` 0755）；无 extra field；deflate 级别固定。同一（版本、target、mode、运行时锁文件、导出密钥）→ 同一 zip 字节。测试：同一输入导出两次 SHA-256 相同。

### 6.2 签名

- `SHA256SUMS`（GNU 格式，LF）覆盖除自身与签名文件外所有文件；manifest `files[]` 与之一致。
- `manifest.sig.json`：`{ alg:"Ed25519", issuer, kid, sig }`，签名作用于 manifest 经 `jcs-sha256/v1`（RFC 8785）得到的字节串，不二次哈希——与 ADR 0040 §12 同一配方，aster-guard 的 `jcs.ts` 与 JDK `EdDSA` 可复用。
- 密钥：aster-api 持有**专用导出密钥**（与 Guard 收据密钥分离），公钥经 `GET /api/v1/exports/keys`（JWKS，RFC 8037）发布。未配置密钥的部署（on-prem 未启用）产出无签名包，`verify` 报告 `unsigned`，不视为失败，除非 `--require-signature`。
- `verify` 默认离线：`--pubkey` 或包外 JWKS 文件；`--jwks <url>` 在线取。

---

## 7. 决策六：接口

- **aster-api**：`POST /api/v1/policies/export/package`，入参同 Cedar 源码模式（`source, locale, aliasSet, allowStructural, vocabulary, functionName, claimedRef`）+ `target, mode, uiLocale`。成功返回 `application/zip`（流式，`Content-Disposition` 含根目录名）；不可导出返回 200 JSON `{exportable:false, diagnostics}`（与 Cedar 一致）。另提供 `POST …/export/package/check`（与 aster-api#548 的 Cedar check 同形）：返回 `{exportable, diagnostics, coreIr, probes, probesSha256, runtime}`，供按钮显示与 §5.3 的 Node 比对。`export/package` 对 `target=node` 额外接受 `{nodeProbes:{probesSha256, count, agreed, tsVersion}}`。鉴权与 Cedar 相同：HMAC 内部调用或 API Key。
- **aster-cloud**：`POST /api/policies/[id]/export/package`：会话 → 限流（复用 `EVALUATE_SOURCE` 桶）→ `loadPolicyForAction(…, 'view')` → 计划门（§10）→ `findDefaultExecutableVersion` → `loadStoredVersionCompileContext` → `PolicyApiClient.exportPackageCheck` →（Node）§5.3 探针比对 → `PolicyApiClient.exportPackage` → 字节流原样转发。cloud 不组装 zip（Workers 内存 / CPU 限制）。
- **UI**：在 PR #790 确立的右上按钮组里，把「导出为 Cedar」扩成「导出」下拉：Cedar / Node 包（A、B）/ JRE 包（A、B），每项一行说明；不可导出时显示诊断。四语文案（hi 用英文）。

## 8. 审计

每次成功导出在 aster-api 审计哈希链追加 `POLICY_EXPORTED`（沿用 ADR 0047 §6 事件），`target` 取 `package:node-a` 等四值，附 manifest SHA-256、zip SHA-256、`coreIr.sha256`、运行时版本、探针条数、`claimedRef`。

## 9. 体积上限

| 组合 | 上限 | 依据 |
|---|---|---|
| B（两平台） | 1 MiB | 源码 + 词汇 + schema + 探针；源码本身沿用编译端点的上限 |
| node-a | 8 MiB | `aster.mjs` 预计数 MiB（ts 包 unpacked 9 MB，含 LSP 等不进 runner）；P0 实测后定稿 |
| jre-a | 48 MiB | fat jar 未实测，估 10–20 MiB（truffle-api、ANTLR、Jackson、locales、可能的 quarkus-core）；P0 实测后定稿 |

运行时产物各自在其仓 CI 设体积预算门；导出时整包超限 → E734。

---

## 10. 计划门

cloud 新增 `PlanCapabilities.policyExport`（服务端 `hasFeatureAccess`；团队策略按团队 owner 的计划，同证据包导出）。缺省建议见 §12 Q1。Cedar 导出保持现状（不门控）。on-prem 部署由 license 门（`license-runtime-gate.ts`）控制，与 SaaS 计划门互不替代。

---

## 11. 风险

| # | 风险 | 处理 |
|---|---|---|
| R1 | Node 包跑的不是生产引擎，罕见路径可能与 Truffle 不同 | §5.3 三道：IR 逐字节、语料 eval 门、每包探针；E735 拒绝 |
| R2 | 无 JIT 的回退解释器慢；GraalVM 在 stock JRE 上会打印「fallback runtime」警告 | 策略小、单次求值；CLI 设 `polyglot.engine.WarnInterpreterOnly=false`；JIT 版另议（Graal compiler 为 GPLv2+CPE，不进默认包） |
| R3 | truffle 主依赖 `quarkus-core` 让 fat jar 变大，且可能不需要 | P0 核实并移出 CLI 依赖闭包 |
| R4 | JVM 构件只在 GitHub Packages（需 token） | CLI jar 走公开 Release 资产；Maven Central 见 Q5 |
| R5 | 要求 Java 25 / Node 24，客户环境未必有 | manifest `requires` + README；`run.sh` 启动前检查版本并给出清晰错误 |
| R6 | ts `package.json` `license: MIT` 与 `LICENSE`（Apache-2.0）不一致，A 包需附正确许可证 | 实施前在 aster-lang-ts 修正元数据；A 包 `LICENSES/` 由构建生成（Apache-2.0、UPL-1.0、ANTLR BSD、Jackson Apache-2.0、decimal.js MIT 等） |
| R7 | 包离线运行后平台无法撤销、无法审计 | 明示（§5.2）；Q7 |
| R8 | B 包换 CLI 版本后 IR 漂移 | §4.2 默认拒绝 |
| R9 | 导出密钥泄露可伪造「平台签发」的包 | 专用密钥、`kid` 轮换、JWKS revision；与收据密钥隔离 |
| R10 | aster-api 镜像增大（两份运行时约 20–30 MiB）；cloud 依赖的 ts 版本须与锁文件同步，否则 Node 导出 503 | 可接受；替代是导出时从 Release 拉取，引入联网与可用性依赖，不取。cloud 的 ts 依赖 bump 须与 aster-api 锁文件在同一列车步骤更新（P2 任务，现列车未覆盖 cloud 依赖） |

---

## 12. 待定项（需用户决定；括号内为建议缺省）

1. **计划门**：哪些计划能导出？（trial / pro / enterprise 可导出全部四种；free 不可。理由：导出是平台外执行，属付费价值；Cedar 不变）
2. **签名密钥**：新建专用导出密钥，还是复用 Guard 收据密钥？（专用密钥，同一 JWKS 机制，独立端点）
3. **JRE 的 A 是否内含 JRE**（jlink 按 OS / 架构出镜像，真正零安装）？（v1 不含，只要求已装 JRE 25；jlink / Node SEA / native-image 放 P4）
4. **Node 下限**：沿用 ts 的 `>=24`？（是；不为导出单独降级）
5. **JVM CLI 是否上 Maven Central**？（v1 只发 Release 资产；Central 与「消除 GitHub Packages 读 token」一并另立任务）
6. **是否允许导出未批准版本**（如草稿，用于测试）？（不允许，与 Cedar 一致）
7. **包是否带有效期**（`notAfter`，CLI 过期拒跑）？（不带：离线无法可靠强制，反而给人「可撤销」的错觉；用版本号与 `claimedRef.status` 表达）
8. **E735 探针不一致时**：拒绝导出，还是降级为警告？（拒绝；与 Cedar E723 一致）
9. **Node 探针比对放在哪**：cloud Worker 两步（§5.3），还是给 aster-api 运行镜像加 Node 由服务端独立完成？（cloud 两步：不增大 aster-api 镜像与攻击面；代价是比对结果对 aster-api 而言是调用方自述，已在审计中标注）
10. **CLI 名称**：Node 侧复用 `aster` bin（与现有开发命令同处），还是另起 `aster-run`？（复用 `aster`，用户示例即此；开发用的 `jvm / class / jar / truffle` 在帮助中标为 dev-only）

---

## 13. 分期

| 期 | 内容 | 仓 |
|---|---|---|
| P0 实测 | fat jar 体积与 stock JRE 回退运行；`quarkus-core` 去留；esbuild 单文件 runner 体积；ts `evaluate(readCoreIr(…))` 与 aster-api `/evaluate-json` 值序列化一致性 | truffle、ts |
| P1 CLI | 包格式 v1 schema（双副本 + parity）；两侧 `compile / run / verify / inspect / version`；aster-lang-test 增加「同一包两 CLI 结果一致」黄金用例并入 CI；truffle Release 资产；ts license 元数据修正 | ts、truffle、test、locales（错误码） |
| P2 B 导出 | aster-api `export/package`（B）、check 端点、E730–E735、签名与 JWKS、审计；cloud 路由、计划门、下拉菜单、四语 | aster-api、cloud、core（静态检查若放 core） |
| P3 A 导出 | 运行时锁文件与镜像内置、`run.sh / run.cmd`、`LICENSES/`、可复现性测试；本地全栈 Playwright 一次 | aster-api、cloud |
| P4 后续 | jlink / Node SEA / native-image 单文件；Maven Central；JIT 版 JRE 包；容器镜像形态 | — |

## 14. 不选的方案

| 方案 | 不选的理由 |
|---|---|
| 编译到 JS / Java 源码或字节码（ts `jvm / class / jar` 路径） | 等于第三个引擎，parity 门没有覆盖；现有 emitter 不是生产路径 |
| GraalVM native-image 二进制作为 v1 | 按 OS / 架构出包，构建慢；需求明确要 Node 与 JRE |
| 容器镜像 | 需要 Docker，且不满足「Node / JRE」；可作 P4 |
| 只导出 Core IR、不带源码 | 失去审计依据与 B 形态 |
| 在 cloud Worker 内组装 zip | 内存 / CPU 限制；签名密钥与运行时产物都在 aster-api |
| 扩展 aster-guard 为运行器 | aster-guard 是平台客户端，职责是执行点而非求值；混入会模糊「包不是执行点」 |
| 复用 Cedar 导出 | 只覆盖可翻译子集与 Verdict 形状，不是通用执行 |
