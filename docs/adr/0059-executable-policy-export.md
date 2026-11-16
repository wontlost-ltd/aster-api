# ADR 0059：可执行策略包导出 —— Node / JRE × 自带运行时 / 通用 CLI

- 状态：**ACCEPTED**（2026-11-16，用户授权按推荐方案裁决；许可证一致性待用户裁决，阻塞 P3）
- 提出：2026-11-16；来源 wontlost-ltd/aster-cloud#797（用户需求原文：「Add the function to export ready to execute policy (node and jre, orgininal policy with dedicated runtime or original policy, metadata and common compiler and run cli)」）
- 相关：ADR 0047（Cedar 导出：只导出可执行版本、探针等价性、`POLICY_EXPORTED` 审计事件，本 ADR 沿用）、ADR 0055（Core IR v2 规范字节）、ADR 0056（双引擎推断统一，豁免表已清空）、ADR 0051 §7（5 段 source envelope）、ADR 0040 §11–§12（Ed25519 + `jcs-sha256/v1` 签名配方、JWKS）、ADR 0054（Guard 执行点）、ADR 0053 / A1（发布列车与 aster-api pin）、ADR 0031（错误码单一来源）
- 范围：只定格式、命令、边界与分期，不含实现；实施计划另写

---

## 0. TL;DR

用户在策略详情页把一个**已批准版本**导出为可在平台外运行的策略包。两个平台 × 两种形态：

| | **A 自带运行时**（下载、首次包外验证一次后即可离线运行，只需已装 Node / JRE） | **B 轻量**（源码 + 元数据，用通用 CLI 编译并运行） |
|---|---|---|
| **Node** | `policy/` + `compiled/core.json` + `runtime/aster.mjs`（单文件、零依赖）+ `run.sh` / `run.cmd` | `policy/` + `manifest.json`；`npm i -g @aster-cloud/aster-lang-ts` 后 `aster run` |
| **JRE** | 同上，运行时换成 `runtime/aster-cli.jar`（fat jar，Truffle 回退解释器） | 同上；从 aster-lang-truffle 的 GitHub Release 下载 `aster-cli-<ver>.jar` 后 `java -jar aster-cli.jar run` |

五个决定：

| 决定 | 内容 | 不做什么 |
|---|---|---|
| 一个 CLI，两种形态共用 | A = B + 钉住版本与哈希的 CLI 产物放进 `runtime/`；「专用运行时」就是同一个通用 CLI 的固定版本 | 不为 A 另做一套运行时；不新增代码生成后端（编译到 JS / 字节码） |
| 执行的是平台产物 | A 执行平台编出的规范 Core IR v2 字节；B 在本地重编译，**字节必须与 `manifest.coreIr.sha256` 相同**才运行 | 不提供绕过开关 |
| 只导出纯策略 | 导出时闭世界检查：声明了 IO 类能力、workflow / await、任何解析不到「模块内函数 / 局部绑定 / 目标引擎已实现的纯 builtin」的名字，一律拒绝（E730–E733） | 不在包里模拟 IO、facts 签发或 Guard 收据 |
| 包不是 Guard 执行点 | 结论按数据返回；`require_approval` / `escalate` 只是返回值；无收据、无审批、无审计链 | 不签发、不伪造执行收据 |
| 可复现 + 可验签 | 确定性 zip；manifest 列出全部载荷哈希，其 JCS 字节由平台导出密钥 Ed25519 签名；`run` 与 `verify` 默认都验签（信任锚随 CLI 发布），无签名包须显式 `--allow-unsigned` | 不用 JWT/JWS；manifest 不含时间戳 |

---

## 1. 现状（各仓 `origin/main`，2026-11-16）

aster-lang-ts `af00965`、aster-lang-core `98851d1`、aster-lang-truffle `bb28d96`、aster-lang-platform `d325125`、aster-lang-test（parity 门）、aster-guard `3d48f86`、aster-api `a7a07513`、aster-cloud `d24f4b2f`。

| 已有 | 位置 | 缺 |
|---|---|---|
| ts 有 `aster` bin（cac）：`parse / core / jvm / class / jar / truffle / install / …`；`core` 已输出规范 IR | `package.json:90-94`，`scripts/aster.ts` | 无 `run / verify / inspect`；`jvm / class / jar / truffle` 依赖本仓 Gradle，不能对外 |
| ts Core IR 树遍历解释器 `evaluate(core, fn, context)`，Decimal 锁死精度 | `src/core/interpreter.ts:220`（导出于 `src/browser.ts:57`） | 不实现 `IO.*`、`PII.unwrap`、`Text.redact`（`:669`）；`Await` 直接报错（`:1098`） |
| ts 严格读入规范 IR：`readCoreIr` | `src/core/read-core-ir.ts:50`，`src/cli/utils/core-ir-file.ts` | — |
| ts 在 npmjs 公开发布（`publishConfig.access: public`），Node ≥ 24；库入口的运行时依赖为 `semver / decimal.js / ajv`，CLI 另需可选 peer 依赖 `cac / ora / …`（所以对外单文件 runner 须打包而非直接依赖 npm 包） | `package.json:154,163-182,243` | `license` 字段写 `MIT`，`LICENSE` 文件是 Apache-2.0（§11 R6） |
| Truffle 语言 `aster`：输入以 `{` 开头即按 Core IR JSON，否则走 Java `CnlCompiler`（ANTLR → `AstBuilder` → `CoreLowering`） | `aster-lang-truffle/.../AsterLanguage.java:25,104-125`，`CnlCompiler.java:60-117` | — |
| 开发用 `Runner <file> [--func=] [-- args]`，`application` 插件 + native-image 配置 | `Runner.java:8-147`，`build.gradle.kts:78-80,~170-215` | `allowAllAccess(true)`（`:77-79`），与生产 `TrufflePolicyRuntime` 的 `HostAccess.EXPLICIT + IOAccess.NONE` 不同；无 fat jar；无子命令 |
| Truffle 主依赖只有 `truffle-api / graal-sdk`（UPL），runtime / compiler 仅在测试 | `build.gradle.kts:59-61,71-74` | 主依赖还拉了 `quarkus-core`（`:67`），体积与必要性待核（§11 R3）；Java 工具链 25 |
| JVM 构件 `cloud.aster-lang:*` 1.0.48，**只发 GitHub Packages**（读也要 token） | core `build.gradle.kts:10,21-35`，platform `:305,372-382` | 客户无法匿名解析；无 Maven Central |
| 生产执行：aster-api `TrufflePolicyRuntime` 上下文池 + wall-clock 看门狗 | `src/main/java/io/aster/policy/runtime/TrufflePolicyRuntime.java:58-125` | — |
| 运行时效果：`AsterRootNode` 顶层允许效果为空 = 全拒；IO builtin 检查后恒抛 `UnsupportedOperationException` | truffle `AsterRootNode.java:53-58`，`Builtins.java:1164-1192,1498-1504` | 平台内本来就跑不了 IO，导出无需「补」 |
| 能力分类：IO 类 Http/Network/Sql/Time/Files/Secrets/Process/AiModel/Payment/Inventory；CPU 类 Crypto/Cpu。推断 `CapabilityInference` 只看 `Call` 目标为 `Name` 的调用名前缀 | core `capability/CapabilityKind.java`、`capability/CapabilityInference.java:43,62,81` | 按名字猜，不是闭世界分析，不能作为导出拒绝的唯一依据（§5.1） |
| ts 解释器步数上限 `MAX_STEPS = 10_000`、调用深度 50；生产 Truffle 的 `statementLimit` 实测不触发，真实上界只有 wall-clock 看门狗 | ts `src/core/interpreter.ts:604-605`；aster-api `TrufflePolicyRuntime.java:86-110` | 两侧资源上限语义不同（§5.3） |
| 双引擎一致性：IR 逐字节门 `VALUE_EXEMPTIONS = []`；eval 门（每 case 两引擎输出一致且等于 golden）PR-blocking；`EVAL-EXEMPTIONS.md` 记载 2026-11-14 实测 960/960 case identical、可求值样本 215/215 有 golden（`ci.yml:478-482` 注释仍是 2026-07 的 255/255，未随之更新） | aster-lang-test `scripts/parity-tier1.mjs:742`，`EVAL-EXEMPTIONS.md`，`.github/workflows/ci.yml:483-488` | 只覆盖语料，不是对任意策略的证明 |
| 编译输入的完整身份：5 段 envelope（content、aliasSet、locale、toolchainId、词汇作用域） | aster-api `PolicyVersion.java:453`；cloud `src/lib/policy-alias.ts:101` | — |
| cloud 版本存在 cloud 库，调用 aster-api 走源码模式；`findDefaultExecutableVersion` + `loadStoredVersionCompileContext`（冻结词汇） | cloud `src/services/policy/default-executable-version.ts:22`，`src/lib/execution-input/index.ts:173` | — |
| Cedar 导出：cloud `POST /api/policies/[id]/export/cedar` → aster-api `exportCedar`；无计划门，只有访问 + 限流；无可执行版本 409 | cloud `src/app/api/policies/[id]/export/cedar/route.ts:111-157` | 导出按钮已由 cloud#786 / PR #790（2026-11-16 合并）移入详情页右上按钮组 |
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
  manifest.sig.json        # §6，平台签名（SaaS 恒有；未配置导出密钥的 on-prem 部署缺省）
  SHA256SUMS               # 除自身与 manifest.sig.json 外所有文件
  README.md                # 生成：如何运行、限制（§5）、验签方法；语言 = 请求的 uiLocale（进入可复现元组，§6.1）
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
- `policy.aster` 为单文件：cloud 版本是单模块；跨模块导入（若将来支持）在 `formatVersion` 升主版本时再定。

---

## 3. 决策二：manifest schema（`aster-policy-package/v1`）

```json
{
  "format": "aster-policy-package",
  "formatVersion": "1.0",
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
  "tests": { "probes": 42, "probesSha256": "…",
             "nodeCheck": { "checkedBy": "aster-cloud", "tsVersion": "3.1.0", "agreed": 42 } | null },
  "files": [{ "path": "policy/policy.aster", "sha256": "…", "size": 1234 }],
  "exportedBy": { "issuer": "aster-cloud", "tenantId": "…" }
}
```

- **不含时间戳与随机量**：导出时间只进审计事件（§8），保证同一输入的 manifest 与 zip 逐字节相同（§6.1）。
- `claimedRef` 沿用 ADR 0047 §6 语义：cloud 自述、aster-api 未核验；其真实性靠签名（签名方是平台）。
- `governance.factSources` 仅提示：档案若声明了签名事实来源，包内**不**验证（§5.2）。
- JSON Schema 文件随 CLI 一起发布（ts 与 JVM 各一份副本 + sibling parity 测试，ADR 0045 模式），CLI 读入 manifest 时严格校验。上面的示例只示意字段；规范 schema 在 P1 落地时须定死：`additionalProperties:false`（未知字段拒收，`formatVersion` 为 `主.次`：次版本只能新增可选字段，每个次版本有自己的严格 schema；CLI 接受主版本相同且次版本 ≤ 自身支持的最高次版本的包，更高次版本按「不受支持」拒绝（退出码 4），不做「忽略未知字段」的宽松读取；主版本变化即不兼容）；`compiled/` 与 `runtime.cli.sha256` 在 A 必填、B 禁止；`entries` 恰有一个 `default:true`，`run` 未给 `--entry` 时取它；`files[].path` 只能是包根下的相对路径，禁止 `..`、绝对路径、反斜杠与符号链接（zip-slip）。

---

## 4. 决策三：通用 CLI

### 4.1 命令（两平台同名同义同退出码）

| 命令 | 作用 |
|---|---|
| `aster compile <pkg\|file.aster> [--locale] [--alias-set] [--vocabulary] [--out]` | 源码 → 规范 Core IR v2 字节；给包目录时编译输入一律取自 manifest |
| `aster run <pkg> [--entry <fn>] --input <file\|-> [--timeout 5s] [--pubkey <file>\|--jwks <file\|url>] [--allow-unsigned]` | 完整性与签名检查（同 `verify` 的前两步，§6.2）→ 取 IR（A 读 `compiled/core.json`；B 本地编译并比对 `coreIr.sha256`）→ 按 `schema/input.json` 校验输入 → 求值 → stdout 输出固定信封（见下） |
| `aster verify <pkg> [--jwks <url>\|--pubkey <file>] [--allow-unsigned] [--recompile] [--probes]` | 哈希、签名（§6.2）、manifest schema、运行时兼容；`--recompile` 重编译比对；`--probes` 跑 `tests/probes.json` |
| `aster inspect <pkg>` | 打印入口、输入输出 schema、效果、治理提示、运行时要求 |
| `aster version` | CLI 版本、引擎、支持的 `formatVersion` / Core IR 版本、toolchainId、内置信任锚的 `kid` 列表 |

`run` 的 stdout 恒为同一信封，签名与否不改变形状：`{"ok":true,"value":<V>,"meta":M}` 或 `{"ok":false,"error":{"code":…,"message":…},"meta":M}`；`<V>` 的序列化与 aster-api `/evaluate-json` 结果里的值逐字节相同（§5.3 的共享编解码）；`M = {signed, entry, coreIrSha256, engine, cliVersion, irRecompiled}` 字段恒在。诊断与提示只写 stderr。

退出码：0 成功；1 策略求值错误（`ok:false`）；2 输入不合 schema；3 完整性 / 签名失败；4 包或 IR 不受支持（格式、版本、效果）；5 超时或资源耗尽（`TIMEOUT` / `RESOURCE_EXHAUSTED`）。`run` 不联网，除非显式给出 `--jwks <url>`。

### 4.2 IR 一致才运行

B 在本地编译后字节与 `coreIr.sha256` 不同 → 退出码 4、错误码 `PKG_IR_DRIFT`，提示改用 A 包或安装 manifest `runtime.cli.version` 记录的 CLI 版本。**没有绕过开关**；需要排查时用 `aster compile <pkg> --out` 取出本地 IR 自行比对，`compile` 不求值。判据是 IR 字节而不是 CLI 版本号：版本不同但字节相同的编译器产出的就是同一程序，可以运行；版本相同而字节不同（如本地 locales 被改）必须拒绝。理由：B 换了编译器版本仍「看起来能跑」是最危险的失败形态；ADR 0055 §5 的规范字节与 0056 清空的豁免表让「逐字节相等」成为可执行的判据。

### 4.3 沙箱与资源

JVM CLI 用生产同一配置（`HostAccess.EXPLICIT`、`IOAccess.NONE`、禁线程 / 进程 / native），**不**沿用 `Runner` 的 `allowAllAccess`。wall-clock 超时（默认 5 s）：JVM 由看门狗线程 `Context.close(true)` 取消；Node 的 `evaluate` 是同步调用，同线程计时器无法打断，所以求值放进 `worker_threads` Worker，主线程超时后 `worker.terminate()`。ts 解释器的步数上限在 CLI 中设为不限（`maxSteps` 取 `Number.MAX_SAFE_INTEGER`），与 Truffle 一样只以 wall-clock 为界，避免两侧因内部计数口径不同而一边报错一边成功。ts 另有写死的调用深度上限 `MAX_CALL_DEPTH = 50`（`interpreter.ts:605`），Truffle 没有对应上限（只受 JVM 栈约束）：P1 把它改为 `evaluate` 选项，CLI 设为 10 000，资源耗尽在解释器内部归类：超过深度上限与在函数调用边界捕获到的 JS 栈溢出（V8 的 `RangeError: Maximum call stack size exceeded`，只认这一种，由解释器在调用边界识别）统一抛类型化的 `ResourceExhaustedError`，Worker 据此报 `error.code = "RESOURCE_EXHAUSTED"`、退出码 5；其他 `RangeError` 及一切求值错误仍是退出码 1；浏览器与 cloud 现有调用不变。深度递归仍可能在两侧于不同深度耗尽资源，这属于 §5.3 明确排除在承诺之外的资源类结果。顶层允许效果为空（全拒），与 `AsterRootNode` 现状一致。

### 4.4 实现落点与分发

| | Node | JVM |
|---|---|---|
| 仓 | aster-lang-ts：`aster` bin 增加 `run / verify / inspect`，`compile` 为 `core` 的别名；同一实现经 esbuild 打成 `dist/runner/aster.mjs`（零依赖单文件，随 npm 包发布） | aster-lang-truffle 新 Gradle 子项目 `aster-cli`（picocli，Apache-2.0）：fat jar，含 core 编译器、truffle、locales；不含 Graal compiler（回退解释器） |
| 分发 | npmjs 公开包 `@aster-cloud/aster-lang-ts`（已有 trusted publishing） | aster-lang-truffle GitHub Release 资产 `aster-cli-<ver>.jar` + `.sha256`（公开仓，匿名可下载）；同时发 GitHub Packages 供内部 |
| 版本 | 跟随 aster-lang-ts 版本（当前 3.x） | 跟随 truffle 版本（列车 lockstep，当前 1.0.48） |

- **版本关系**：manifest `runtime.ecosystem` 记录平台目录版本（`asterLang`）；列车 `release-plan.json` 已把 ts 版本与 JVM 版本绑在同一列车上，CLI 不另立版本号。
- **兼容规则**：CLI 接受 `formatVersion` 规则（§3）允许、Core IR 版本受支持的包；A 包永远用自带 CLI，无兼容问题。
- **aster-api 内置运行时产物**：A 包所需的 `aster.mjs` 与 `aster-cli.jar` 在 aster-api 镜像构建时按 `export-runtimes.lock.json`（版本 + sha256）取入，导出时不联网；锁文件由列车 L4 pin bump 一并更新（ADR 0053 A1）。

---

## 5. 决策四：平台外支持什么、导出时拒绝什么

### 5.1 导出时静态拒绝（整体拒绝，给出全部诊断；ADR 0031 单一来源，同步全部副本）

判定范围是**整个模块**而不是「入口可达的函数」：不做可达性分析，也就没有函数值、别名、间接调用漏判的问题；代价是模块里有一个不可导出的辅助函数也会整体拒绝，诊断会指出它。判定不依赖 `CapabilityInference` 的名字前缀猜测（§1），而是闭世界名字解析：

| 码 | 条件（在整个模块的 Core IR 上判定） | 理由 |
|---|---|---|
| E730 | 任一函数**声明**了 IO 类能力（Http/Network/Sql/Time/Files/Secrets/Process/AiModel/Payment/Inventory）或 IO 效果 | 两引擎都无真实 IO；`Time` 还破坏可复现。CPU 类（Cpu、Crypto）允许 |
| E731 | 存在 workflow、`Async` 效果或 `Await` 表达式 | ts 解释器不支持 await；Truffle workflow 依赖 EventStore |
| E732 | 任一 `Name`（不论出现在调用目标、实参还是其他位置）解析不到：模块内函数、局部 / 参数 / lambda 绑定、类型构造器、或**目标引擎导出白名单**中的 builtin。白名单 = 目标引擎已实现的纯 builtin，含以 `Call(Name(op))` 出现的运算符（Node：ts `BUILTIN_ARITY` 键 ∪ `BUILTIN_OPS` ∪ `NAMED_BUILTIN_OPS` 中值非 null 的键；JRE：Truffle `Builtins` 注册名去掉 `IO.*`），名字先按引擎自身的别名归一（ts `callTargetBuiltin`、truffle `Builtins.canonicalName`）再查表，以生成文件随引擎发布，并由 parity 测试保证两份表与实现同步 | 闭世界、失败即拒：解析不了的名字一律视为不可导出；包能导出就必须能跑完 |
| E733 | 入口参数或返回类型无法生成 JSON Schema（函数类型等） | `run --input` 无法表达 |

另：无可执行版本 → cloud 409（§12.1 Q5 定案：同 ADR 0047 §6，只导出 secure-execute 默认会执行的 APPROVED 版本；不导出草稿，否则等于绕过审批）。超限 → E734（§9）。

### 5.2 平台外行为

- **Guard / 治理**：包不是执行点（ADR 0054）。`Verdict` 四种结论按数据返回，调用方自行处理 `require_approval` / `escalate`；没有审批闭环、没有执行收据、没有证据链。`manifest.governance.enforced=false` 与 README 明示。
- **facts**：签名事实（ADR 0040 §11）在包内不验签、不解析来源；输入按调用方给出的值信任。`factSources` 非空时 `inspect` / `run` 在 stderr 打一行提示。
- **收据**：CLI 不产出任何形似收据的签名对象。需要可审计执行的调用方应调用平台 API。
- **撤销**：导出后平台撤销 / 弃用版本对已下发的包无效（离线无法强制）。见 §12 Q7。

### 5.3 双引擎结果一致性：能承诺什么

- **JRE**：与生产同一引擎（Truffle）、同一 IR 字节，但宿主不同：输入 JSON → guest 值的转换与结果序列化在 aster-api 里（`TrufflePolicyRuntime` 的入参转换、`/evaluate-json` 的输出形态），CLI 必须**复用同一份代码**（P1 把它从 aster-api 抽到 truffle 的一个公共类，aster-api 改为依赖它），并以「同一批探针在 aster-api 与 jre CLI 上输出逐字节相同」的黄金测试守住。在此前提下承诺：正常完成的求值结果与平台一致；超时与资源耗尽（含递归深度）不在承诺内（机器与 JIT 不同）。Node 同此口径：承诺只覆盖两侧都正常完成的求值。
- **Node**：引擎是 ts 解释器，不是生产引擎。承诺的边界是：①IR 逐字节相同（A 由构造保证，B 由 §4.2 保证）；②语料级 eval 门（PR-blocking，最近一次记载 960/960 case）；③**每个导出包**的探针：导出时按 ADR 0047 §5 同一思路从输入 schema 生成探针（每个枚举值、数值字面量 ±1、可选字段存在 / 缺失，上限 256 条），由 aster-api Truffle 求出期望输出写入 `tests/probes.json`。aster-api 运行镜像没有 Node（`Dockerfile` 末段为 ubi-micro，Node 只在构建阶段），因此 Node 侧比对放在 cloud：cloud 先调 `check`（§7）拿到规范 IR 与探针，在 Worker 里用自身依赖的 `@aster-cloud/aster-lang-ts/browser`（`readCoreIr` + `evaluate`）逐条求值，任一不一致 → E735 拒绝导出；cloud 依赖的 ts 版本必须等于 `runtime.cli.version`，否则 503 `export_runtime_skew`（只在列车换版的窗口出现）。cloud 把探针集的 SHA-256 与比对结果交给 `export/package`，aster-api 重算哈希确认是同一批探针后写入审计（`nodeProbes.checkedBy:"aster-cloud"`，与 `claimedRef` 同为调用方自述）。`nodeProbes` 只接受 HMAC 已验签的内部调用方（cloud）；API Key 调用方传入一律忽略，manifest `tests.nodeCheck=null`。因此签名 manifest 中的 `nodeCheck` 只可能来自 cloud，且记录了比对方与 ts 版本，验签者可据此决定是否信任。用户侧 `aster verify --probes` 随时可复核。探针是抽样不是证明，README 写明。
- 探针**不取**真实执行记录的输入（避免把客户数据写进可外传的包）。

---

## 6. 决策五：完整性与签名

### 6.1 可复现

zip 只由 aster-api 一处实现（`java.util.zip`，JDK 版本随镜像固定）：条目按路径字节序；mtime 固定 1980-01-01T00:00:00；权限 0644（`run.sh` 0755）；无 extra field；deflate 级别固定。影响字节的输入全集 = （源码 envelope 五段、版本 `claimedRef`、target、mode、uiLocale〔README 语言〕、运行时锁文件、探针生成器版本、`nodeCheck`〔cloud 导出与 API Key 导出不同〕、导出密钥 `kid`、导出端构建〔aster-api 镜像 digest，决定 JDK 与 zip 实现〕）；Ed25519 签名是确定性的，签名文件不含时间。元组相同 → zip 字节相同。测试分两条：①元组完全相同 → 两次导出的 zip SHA-256 相同；②元组中**体现在 manifest 里**的项（envelope、`claimedRef`、target、mode、运行时版本与哈希、`probesSha256`、`nodeCheck`、载荷哈希）任一改变 → manifest 哈希改变。`kid` 只影响 `manifest.sig.json`；uiLocale 只影响 README（经 `files[]` 间接改变 manifest）；探针生成器版本只在探针内容变了时才改变字节，不单独断言。

### 6.2 签名

- 两个覆盖集，不自引用：**载荷** = 除 `manifest.json`、`manifest.sig.json`、`SHA256SUMS` 之外的全部文件，由 `manifest.files[]` 逐个列出（路径、sha256、size），签名经 manifest 间接覆盖它们；`SHA256SUMS`（GNU 格式，LF）= 载荷 + `manifest.json`，只为不装 CLI 的人用 `sha256sum -c` 自查。`verify` / `run` 的顺序：验签 manifest → 按 `files[]` 校验每个载荷文件且包内不得有 `files[]` 以外的文件（三个元文件除外）→ 校验 `SHA256SUMS` 与前两者一致，不一致按损坏处理（退出码 3）。
- `manifest.sig.json`：`{ alg:"Ed25519", issuer, kid, sig }`。被签字节 = `manifest.json` 按 RFC 8785 JCS 规范化后的 **UTF-8 字节串本身**（不是其 SHA-256 摘要，不二次哈希），`sig` 为 64 字节签名的 base64url（无填充）——与 ADR 0040 §12 和 aster-guard `src/receipt.ts` 现行验签一致，`jcs.ts` 与 JDK `EdDSA` 可复用。`manifest.json` 文件本身也以 JCS 字节写出，验签时先确认文件字节等于其 JCS 形，杜绝「签名对、文件被重排」。P1 提供 ts / Java 共用的签名向量（含篡改用例）。
- 密钥：aster-api 持有**专用导出密钥**（与 Guard 收据密钥分离），公钥经 `GET /api/v1/exports/keys`（JWKS，RFC 8037）发布。
- **信任锚**：包内不放公钥（自带公钥证明不了任何事）。CLI 发布时内置 SaaS 导出 JWKS 快照；`--pubkey <file>` / `--jwks <url>` 覆盖（on-prem 用自己的密钥）。`kid` 不在锚中 → 退出码 3。`run` 与 `verify` 都接受 `--pubkey` / `--jwks <file|url>`。**轮换**：新 `kid` 先进 JWKS 并随至少一个 CLI 版本（一次列车）发布，之后 aster-api 才开始用它签名；旧 `kid` 在 JWKS 中保留到其签过的包不再需要验证为止（不删除，只停用签名）。A 包自带 CLI，aster-api 导出时断言「签名 `kid` ∈ 所带 CLI 的内置锚」，不满足即中止导出，所以 A 包离线永远可验；B 包遇到旧 CLI 不认识的新 `kid`，提示升级 CLI 或用 `--jwks` 提供公钥。
- **默认失败即拒**：`run` 与 `verify` 都要求签名有效；签名缺失只有在显式 `--allow-unsigned` 时继续，且输出带 `"signed": false`。SaaS 导出恒签名；未配置密钥的 on-prem 部署导出时 UI 明示「无签名包」。
- 只有 `SHA256SUMS` 不构成来源证明（可被整体改写），它只用于定位损坏的文件。
- **A 包的信任自举**：A 包的 `run.sh` 执行的是包内的 CLI，被篡改的包可以连 CLI 一起换掉，所以**包内 CLI 的自检只防意外损坏，不证明来源**。来源证明必须来自包外的验证者，二选一（README 首段写明）：
  1. 用独立安装的 CLI 验证：`npx @aster-cloud/aster-lang-ts verify <pkg>`，或从 GitHub Release 下载并核对 `.sha256` 的 `aster-cli.jar`。独立验证者除验签外还检查 `runtime/*` 的哈希等于签名 manifest 中的 `runtime.cli.sha256`，且该值等于公开发布的同一文件的哈希——即包里带的运行时就是公开发布的那一个。JRE：直接比对 Release 资产 `aster-cli-<ver>.jar.sha256`。Node：npm `dist.integrity` 是整个 tarball 的哈希，不能直接比；验证者先按 `dist.integrity` 校验下载的 tarball，再对解包出的 `dist/runner/aster.mjs` 求 SHA-256 与 `runtime.cli.sha256` 比对；另外 aster-lang-ts 在 GitHub Release 附 `aster.mjs.sha256`，供不想解包的人直接比对（P1 任务）。
  2. 核对整个 zip 的 SHA-256：cloud 下载时在界面与响应头 `X-Aster-Package-Sha256` 给出，并写入审计事件（§8）；用户可在平台内随时查回。
  验证通过后，包内 CLI 才可视为可信，此后离线运行无需再联网。

---

## 7. 决策六：接口

- **aster-api**：`POST /api/v1/policies/export/package`，入参同 Cedar 源码模式（`source, locale, aliasSet, allowStructural, vocabulary, functionName, claimedRef`）+ `target, mode, uiLocale`。成功返回 `application/zip`（`Content-Disposition` 含根目录名；响应头 `X-Aster-Package-Sha256` 为整个 zip 的 SHA-256。因哈希要先于正文发出，aster-api 先在内存组装完整 zip 再发送，上限见 §9；cloud 原样转发正文与该头）；不可导出返回 200 JSON `{exportable:false, diagnostics}`（与 Cedar 一致）。另提供 `POST …/export/package/check`（与 aster-api#548 的 Cedar check 同形）：返回 `{exportable, diagnostics, coreIr, probes, probesSha256, runtime}`，供按钮显示与 §5.3 的 Node 比对。`export/package` 对 `target=node` 额外接受 `{nodeProbes:{probesSha256, count, agreed, tsVersion}}`。鉴权与 Cedar 相同：HMAC 内部调用或 API Key。
- **aster-cloud**：`POST /api/policies/[id]/export/package`：会话 → 限流（复用 `EVALUATE_SOURCE` 桶）→ `loadPolicyForAction(…, 'view')` → 计划门（§10）→ `findDefaultExecutableVersion` → `loadStoredVersionCompileContext` → `PolicyApiClient.exportPackageCheck` →（Node）§5.3 探针比对 → `PolicyApiClient.exportPackage` → 字节流原样转发。cloud 不组装 zip（Workers 内存 / CPU 限制）。
- **UI**：在 PR #790（已合并）确立的右上按钮组里，把「导出为 Cedar」扩成「导出」下拉：Cedar / Node 包（A、B）/ JRE 包（A、B），每项一行说明；不可导出时显示诊断。四语文案（hi 用英文）。

## 8. 审计

每次成功导出在 aster-api 审计哈希链追加 `POLICY_EXPORTED`（沿用 ADR 0047 §6 事件），`target` 取 `package:node-a` 等四值，附 manifest SHA-256、zip SHA-256、`coreIr.sha256`、运行时版本、探针条数、`claimedRef`。

## 9. 体积上限

| 组合 | 上限 | 依据 |
|---|---|---|
| B（两平台） | 1 MiB | 源码 + 词汇 + schema + 探针；源码本身沿用编译端点的上限 |
| node-a | 8 MiB | `aster.mjs` 预计数 MiB（ts 包 unpacked 9 MB，含 LSP 等不进 runner）；P0 实测后定稿 |
| jre-a | 48 MiB | fat jar 未实测，估 10–20 MiB（truffle-api、ANTLR、Jackson、locales、可能的 quarkus-core）；P0 实测后定稿 |

B 的上限是定值。A 的两项是**暂定上限**：P0 实测后以「实测 × 1.5，向上取整到 MiB」写回本 ADR，之后改动须修订 ADR。执行顺序是：①运行时产物在其仓 CI 设同一预算门，超限则该版本不能发布，也就进不了 aster-api 锁文件；②导出时整包仍超限（只可能来自异常大的源码 / 词汇 / 探针）→ E734，诊断给出实际大小与上限，用户可改选 B。

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
| R6 | ts `package.json` `license: MIT` 与 `LICENSE`（Apache-2.0）不一致，A 包需附正确许可证 | 待用户裁决（§12.1a L1，阻塞 P3）；裁决后在 aster-lang-ts 修正元数据；A 包 `LICENSES/` 由构建生成（Apache-2.0、UPL-1.0、ANTLR BSD、Jackson Apache-2.0、decimal.js MIT 等） |
| R7 | 包离线运行后平台无法撤销、无法审计 | 明示（§5.2）；Q7 |
| R8 | B 包换 CLI 版本后 IR 漂移 | §4.2 默认拒绝 |
| R8b | A 包被整体替换（含 CLI）后自检必然通过 | §6.2「A 包的信任自举」：来源只由包外验证者或 zip 哈希证明，README 首段写明 |
| R9 | 导出密钥泄露可伪造「平台签发」的包 | 专用密钥、`kid` 轮换、JWKS revision；与收据密钥隔离 |
| R10 | aster-api 镜像增大（两份运行时约 20–30 MiB）；cloud 依赖的 ts 版本须与锁文件同步，否则 Node 导出 503 | 可接受；替代是导出时从 Release 拉取，引入联网与可用性依赖，不取。cloud 的 ts 依赖 bump 须与 aster-api 锁文件在同一列车步骤更新（P2 任务，现列车未覆盖 cloud 依赖） |

---

## 12. 裁决记录

### 12.1 已裁决（2026-11-16，用户授权按推荐方案裁决）

| # | 问题 | 裁决 |
|---|---|---|
| Q1 | 计划门 | trial / pro / enterprise 可导出全部四种组合；free 不可；Cedar 导出保持不门控 |
| Q2 | JRE 的 A 是否内含 JRE | v1 不含，要求已装 JRE；jlink / Node SEA / native-image 放 P4 |
| Q3 | Node / Java 下限 | Node ≥ 24，Java ≥ 25 |
| Q4 | JVM CLI 分发 | 先发 GitHub Release 资产；Maven Central 后续另立任务 |
| Q5 | 未批准版本 | 只导出 APPROVED 版本（§5.1 的规则由建议缺省转为定案） |
| Q6 | 有效期 | 不带 `notAfter` |
| Q7 | 无签名包 | on-prem 未配置导出密钥时允许导出；运行须 `--allow-unsigned` |
| Q8 | 依赖签名事实的策略 | 允许导出，manifest 与 README 标明「事实未验证」（facts unverified）；E736 不启用 |

### 12.1a 待用户裁决（阻塞 P3）

- **L1 许可证一致性**：aster-lang-ts `package.json` 声明 `license: MIT`，仓库 `LICENSE` 文件是 Apache-2.0（§11 R6）。以哪一个为准是法律决定，由用户裁决。**只阻塞 P3**（A 包把 `aster.mjs` 与 `LICENSES/` 一并分发）；P0–P2 不分发捆绑运行时，可照常推进。P1 只在 ts 仓记录该不一致，不改 `license` 字段。

### 12.2 本 ADR 自定缺省（用户 2026-11-16 确认维持）

| 项 | 已定 | 位置 |
|---|---|---|
| 签名密钥 | 专用导出密钥，不复用 Guard 收据密钥 | §6.2 |
| 探针不一致 | E735 拒绝导出，不降级为警告（同 Cedar E723） | §5.3 |
| Node 探针比对位置 | cloud Worker 两步；不给 aster-api 运行镜像加 Node | §5.3、§7 |
| CLI 名称 | Node 复用 `aster` bin；开发用 `jvm / class / jar / truffle` 在帮助中标为 dev-only | §4.4 |

---

## 13. 分期

| 期 | 内容 | 仓 |
|---|---|---|
| P0 实测 | fat jar 体积与 stock JRE 回退运行；`quarkus-core` 去留；esbuild 单文件 runner 体积；ts `evaluate(readCoreIr(…))` 与 aster-api `/evaluate-json` 值序列化一致性 | truffle、ts |
| P1 CLI | 包格式 v1 规范 schema（双副本 + parity）；签名向量；导出 builtin 白名单生成文件；把 aster-api 的入参转换 / 结果序列化抽到 truffle 公共类；两侧 `compile / run / verify / inspect / version`（Node 求值在 Worker 线程）；aster-lang-ts Release 附 `aster.mjs.sha256`、truffle Release 附 `aster-cli-<ver>.jar.sha256`，发布 workflow 生成并回读校验；aster-lang-test 增加「同一包两 CLI 结果一致」黄金用例并入 CI；truffle Release 资产；记录 ts license 不一致（修正待 §12.1a L1） | ts、truffle、test、locales（错误码） |
| P2 B 导出 | aster-api `export/package`（B）、check 端点、E730–E735、签名与 JWKS、审计；cloud 路由、计划门、下拉菜单、四语 | aster-api、cloud、core（静态检查若放 core） |
| P3 A 导出（前置：§12.1a L1 已裁决） | 运行时锁文件与镜像内置、`run.sh / run.cmd`、`LICENSES/`、可复现性测试；本地全栈 Playwright 一次 | aster-api、cloud |
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
