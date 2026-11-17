# ADR 0059 修订 3：P2（B 形态导出）实施裁决

- 状态：**ACCEPTED**（2026-11-17，用户授权按推荐方案裁决）
- 日期：2026-11-17
- 修订对象：[ADR 0059](0059-executable-policy-export.md) §2、§4.4、§5.1、§5.3、§6.2、§7、§8、§10、§13，以及 [修订 1](0059-amendment-1-p0-findings.md) A5
- 实施计划：`docs/superpowers/plans/2026-11-17-adr0059-p2.md`
- 依据：P1 合入后的 origin/main（aster-lang-test `c7219e2`、aster-lang-ts `c494e96`、aster-lang-truffle `52e96e1`、aster-lang-core `9159444`、aster-cloud `efdbc677`、aster-api `15ba9ca0`）；包格式规则以 aster-lang-test `conformance/package-v1/README.md` 为准，本修订不复述

写 P2 计划时发现 ADR 有若干处与 P1 落地后的代码不符，或留了选择。下列裁决覆盖 ADR 中对应段落，其余不变。

## 已核实的现状（裁决的前提）

- P1 的 core `aster.core.embed.*`、truffle `aster.truffle.embed.*` 与 `cli/` 只在各仓 main，**不在任何 tag 里**（core / truffle / platform 最新 tag `v1.0.49`，ts 最新 tag `v3.2.0`）。aster-api 的 version catalog 与 CI sibling-ref 都钉在 `1.0.49`；aster-cloud 依赖 `@aster-cloud/aster-lang-ts` `3.0.0`。
- ts 没有 `export-builtins.json`（P1 T10 只在 truffle 落地，aster-cloud/aster-lang-truffle#186）；ts 包没有对外导出 `src/policy-package/*`（只有 `./runner` 单文件）。
- E730–E738 在 ADR 0031 单一来源（aster-lang-ts `shared/error_codes.json`）与 core / locales / hi 副本中都**未登记**。
- aster-api `/policies/schema`（`ParameterSchemaExtractor`）产出的是表单用的参数描述，不是 JSON Schema，也不读 aliasSet、词汇与返回类型。
- aster-api#558：`/evaluate-json` 无法传入非整数 Decimal（字符串当 Text，带小数的数字当 Double）。

## 裁决

| 编号 | 裁决 | 理由 |
|---|---|---|
| P2-R1 | **#558 在 P2 内修复，先于黄金门**：`/evaluate-json` 的入参按入口形参的声明类型转换（Decimal 接受十进制字符串与 JSON 数字，Long 接受安全整数与十进制字符串，其余按 conformance `README.md`「输入」表），嵌套 Data 字段与 List 元素同样处理。导出时探针期望值走同一条路径 | 探针按 schema 生成，必然含非整数 Decimal；不修，探针期望值与 §5.3 黄金门都无从计算 |
| P2-R2 | **导出密钥**：配置 `aster.export.signing-keys` / `aster.export.active-kid`（env `ASTER_EXPORT_SIGNING_KEYS` / `ASTER_EXPORT_ACTIVE_KID`），形状与 Guard 收据密钥相同，解析器从 `ReceiptSigningKeys` 抽出共用；Vault `apps/aster-api-export-signing`，ExternalSecret `aster-api-export-signing-keys`（secretKeyRef `optional: true`）；revision 单调表 `export_signing_key_revisions`（Flyway V6.40.0，单独的只含迁移的 PR）。**配置畸形 → 启动失败；缺配置 → 导出 503 `export_signing_unavailable`**；`aster.export.package.allow-unsigned`（缺省 false，只供 on-prem）为 true 时缺配置改为导出无签名包。JWKS 在 `GET /api/v1/exports/keys`（形状同 `/api/v1/guard/keys`，但匿名可读，供 CLI `--jwks <url>` 直接取用） | 测试与本地无需密钥；生产缺密钥时 fail-closed，又不会像收据密钥那样让整个 Pod 起不来（导出是可选功能，aster-api 镜像可以先于 Vault 配置上线） |
| P2-R3 | **轮换规则由代码强制**：P2 引入 `src/main/resources/export/export-runtimes.lock.json`（P3 再加运行时产物与 sha256），每个 target 一项 `{cliName, cliVersion, trustAnchorKids}`，`node` 一项可为 `null`。签名用的 `active-kid` 不在该 target 的 `trustAnchorKids` 中 → 503 `export_signing_kid_unanchored`；target 一项为 `null` → 503 `export_runtime_unavailable`。锁文件由列车 L4 pin bump 更新（ADR 0053 A1） | ADR §6.2「先发布锚，再签名」原本只是流程；锁文件把它变成机械检查，也让 manifest `runtime.cli.version` 有唯一来源 |
| P2-R4 | **`schema/input.json` / `schema/output.json` 由新的、基于 Core IR 类型的生成器产出**（aster-api `io.aster.policy.export.EntrySchemaGenerator`），映射与 conformance `README.md`「输入」表及 aster-lang-test `scripts/lib/package-v1/fixtures.mjs` 的参照实现逐字节相同；E733、E738 与它共用同一类型遍历。ADR §2「来自 /policies/schema 同一生成器」作废，`/policies/schema` 不变 | 现有生成器既不是 JSON Schema，也看不到别名与词汇后的规范类型 |
| P2-R5 | **新增 E739（Node 重编译漂移）**：导出 `target=node` 时，cloud 在 Worker 内用自身依赖的 ts 编译器按同一编译输入重编译，`positionFreeSha256` 与平台不等即拒绝导出（E739，不降级）。与 E735 一样只由 cloud 判定 | B 包在用户机器上本地重编译（§4.2）；平台 IR 与 ts 编译结果不同的包导出后 100% `PKG_IR_DRIFT`，必须在导出时拦下 |
| P2-R6 | **检查放在 aster-api `io.aster.policy.export.*`**，白名单读 P1 T10 的生成文件（jre：truffle `aster/truffle/export-builtins.json`；node：ts 新增的同形文件，见 P2-R7）。错误码 E730–E735、E737–E739 按 ADR 0031 登记到 ts `shared/error_codes.json` 及 core `ErrorCode`、core / locales 的副本与 zh / de / hi 译文（E736 仍保留不启用）。在这些登记随列车 eco-1.0.50 发布之前，aster-api 用本仓 `ExportDiagnosticCode` 产出同码同文的诊断，列车后改用 core `ErrorCode` 并删除本地类 | 检查要读平台 IR 与生产编译路径，aster-api 是唯一同时持有二者的地方；放 core 会让全部检查都等列车 |
| P2-R7 | ts 在 P2 补齐：`src/policy-package/export-builtins.json`（T10 的 ts 一半）与新的子路径导出 `./package-check`（Worker 可用、无 `node:worker_threads` / `node:fs`）：`compileSource`、`positionFreeSha256`、`checkProbes`、`TS_VERSION` | cloud Worker 做 §5.3 探针比对与 P2-R5 重编译需要这些函数，目前只在 runner 单文件里 |
| P2-R8 | **接口三分**：`POST /api/v1/policies/export/package/check`（廉价：编译 + 静态检查，按 target 分别给结论，供详情页决定显示）；`POST …/export/package/prepare`（编译 + 静态检查 + 探针生成与平台期望值，返回 `coreIr`、`probes`、`probesSha256`、`runtime`；供 Node 比对）；`POST …/export/package`（组装、签名、审计、返回 zip）。三者入参相同（另加 `target`、`mode`、`uiLocale`、`entry`）。ADR §7 的单个 `check` 作废 | 探针最多 256 次 Truffle 求值，不能放进详情页渲染路径（Cedar check 2 s 超时） |
| P2-R9 | **v1 平台导出恰有一个入口**：`entry` 缺省时按 aster-api `EntryPointSelector`（显式名 > `@entry` > 单 Rule）确定；确定不了 → `check` / `prepare` 返回 `defaultEntry:null` 与 `entryCandidates`，`export/package` 返回 400 `entry_required`。UI 与 Cedar 的「选择规则」同法让用户选 | 包格式允许多入口，但平台没有「哪些函数对外」的声明；多入口会让辅助函数的类型拖垮整个导出 |
| P2-R10 | **P2 只接受 HMAC 内部调用（cloud）**；API Key 调用方 403 `export_requires_cloud`。ADR §7「鉴权与 Cedar 相同」推迟到另立任务（需要 aster-api 侧计划门） | 计划门（§10）只在 cloud；API Key 直连会绕过 `policyExport` 门 |
| P2-R11 | `claimedRef` 在包端点是有类型的 `{policyId, version, status, approvedAt}`（manifest schema 的形状），cloud 填 `status:"APPROVED"` 与该版本的批准时间；不合规整次 400（不同于 Cedar 的「不合规就不带」） | 签名 manifest 里的来源字段不能静默缺失 |
| P2-R12 | **探针生成**（`ProbeGenerator`，版本号 `"1"` 进可复现元组）：基准输入 = 每个类型的基准值（Int / Long / Double / Decimal 为 0，Text 为 `""`，Bool `false`，List `[]`，Map `{}`，Maybe `null`，Enum 首变体，Data 递归）；变化按叶子路径逐个、一次改一处：Bool 取 `true`，Enum 取每个变体，Maybe 取非空基准值，数值取模块 IR 中出现的每个同类数值字面量及其 ±1（Int 截到 int32、Long 截到 ±(2⁵³−1)、Decimal 以字符串写出），Text 取模块中出现的每个字符串字面量；按生成顺序去重，取前 256 条。每条在平台上 1 s 超时，`TIMEOUT` / `RESOURCE_EXHAUSTED` 丢弃，其余按 `{"ok":true,"value":V}` 或 `{"code":"EVAL_ERROR","ok":false}` 写入 | ADR §5.3 只给了思路；可复现要求定死算法 |
| P2-R13 | **cloud Worker 内的探针比对**：ts `evaluate` 取 `maxCallDepth: 10000`、`maxSteps: 5_000_000`、全部探针合计墙钟 20 s；超出步数、栈溢出、超时都按不一致处理（E735），不降级。cloud 依赖的 ts 版本 ≠ 锁文件 `node.cliVersion` → 503 `export_runtime_skew`，UI 不显示 Node 选项 | Worker 里没有 `worker_threads`，无法像 CLI 那样 `terminate()`；宁可拒绝也不导出未经比对的包 |
| P2-R14 | **zip 写法**：`java.util.zip.ZipOutputStream`，只写文件条目（不写目录条目），条目名 `<root>/<path>`，按路径 UTF-8 字节序；每条 `setTimeLocal(1980-01-01T00:00)`、`DEFLATED`、`Deflater` 级别 9、无 extra field、无注释；根目录名 `<moduleName>-v<claimedRef.version>-<target>-b`。B 包没有可执行文件，§6.1 的 0644 / 0755 权限位留给 P3 | 与 conformance 的 `b-zh.zip`（ZipOutputStream 形态）同一写法，已被两个 CLI 接受；目录条目在 deflate 下会带 2 字节空流，正好撞上「目录条目不得带数据」 |
| P2-R15 | 包内 `README.md` 按 `uiLocale` 取 en / zh / de 模板，hi 用 en 模板。cloud 的 UI 文案四语都译（hi 为印地语，与现有 `cedarExport` 一致）；aster-lang.cloud 与 aster-lang.dev 文档 hi 回落英文。ADR §7「四语文案（hi 用英文）」据此改读 | 与 cloud 现行 i18n 与文档回落机制一致 |
| P2-R16 | **开关**：aster-api `aster.export.package.enabled`（env `ASTER_EXPORT_PACKAGE_ENABLED`，缺省 false，关时三个端点 404）；cloud PlatformSetting `policy_export.package.enabled`（缺行 false）。计划门 `PlanCapabilities.policyExport`：free = false，trial / pro / team / enterprise = true（`team` 已下线但 enum 仍在，同 `evidenceExport`） | 两仓可各自先合入、生产保持关闭，直到列车与密钥就绪 |
| P2-R17 | 审计 `POLICY_EXPORTED` 的 `target` 取 `package:jre-b` / `package:node-b`；另记 `manifestSha256`、`zipSha256`、`coreIrSha256`、`positionFreeSha256`、`cliVersion`、`probeCount`、`nodeCheck`（或 null）、`kid`（或 null）、`claimedRef`。cloud 不新增表或用量指标（无 drizzle 迁移），只记 product event `policy_package_exported` | ADR §6.2 的「平台内可查回 zip 哈希」由 aster-api 审计链满足 |

## 影响

- **包格式 v1**：不变。
- **发布**：所有依赖 P1 引擎代码的 aster-api / cloud 任务（去重 replay 副本、黄金门、Node 比对、ts 依赖升级）等列车 eco-1.0.50 与 ts ≥ 3.3.0；列车由用户发起。truffle 下一次打 tag 才产生第一个 `aster-cli-<ver>.jar` Release 资产。
- **未改变**：签名配方、信任锚语义、E734 / 1 MiB 上限、「只导出 APPROVED 版本」、Cedar 导出不门控。
