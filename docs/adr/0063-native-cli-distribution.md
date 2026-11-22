# ADR 0063：`aster` CLI 的 GraalVM 原生可执行文件——只作安装渠道

- 状态：**ACCEPTED**（2026-11-22，产品负责人采纳；§10 Q1–Q7 均按推荐答案裁决）。**采纳不改变发布阻塞**：各平台资产的发布仍须等用户提供 §6.3 列出的凭据（Apple 签名与公证、Windows 签名、Homebrew tap token 等）
- 提出：2026-11-22；来源：用户批准在 native-image spike 之后推进「JRE CLI 的原生发行」
- 相关：[ADR 0059](0059-executable-policy-export.md)（可执行策略包与通用 CLI；§4.3、§4.4、§5.3、§9、§13 P4、§14）、[修订 1](0059-amendment-1-p0-findings.md) A3（jre-a 18 MiB）/ A4（栈与资源）、[修订 4](0059-amendment-4-display-layout-in-packages.md) D4 / D12（锁文件 `packageFormats`）、[ADR 0053](0053-release-train-artifact-reuse.md) 与[修订 A2](0053-amendment-2-release-requires-main-ci.md)（发布列车、发布前要求 main CI 绿）
- 修订：本 ADR 替换 ADR 0059 修订 1 A4 中「JVM CLI 在缺省线程栈上求值，不调 `-Xss`」一句（§7）；其余 ADR 0059 条款不变
- 实现仓：`aster-lang-truffle`（主）；`aster-lang-test`（一致性门的原生侧，§5）；发布阶段另涉 `aster-lang-platform`（列车）与新建的 Homebrew tap
- 实施计划：`docs/superpowers/plans/2026-11-22-adr0063-native-cli-distribution.md`
- 已开的实现 PR（不需要用户输入的部分）：aster-cloud/aster-lang-truffle#201（原生构建 + 原生一致性门，CI 非阻塞）、#202（确定性调用深度上限）

---

## 0. TL;DR

| 主题 | 决定（一句话） |
|---|---|
| 是什么 | 同一个 JVM `aster` CLI（truffle `cli/`）用 GraalVM Native Image 编成单个可执行文件；命令契约、退出码、stdout 与 jar 逐字节相同 |
| 为什么 | 单次调用 25 ms（jar 643 ms、Node 217 ms），峰值 RSS 33–43 MB（jar 200–250 MB），不需要 JRE，没有 `sun.misc.Unsafe` 警告 |
| 定位 | **只是安装渠道**：GitHub Release 资产、Homebrew、（可选）npm 平台包。不进策略包，A 包继续带 jar 或 `aster.mjs`（§8） |
| 平台 | linux-x64、linux-arm64（glibc ≥ 2.34）、macos-arm64、macos-x64（见 Q4）、windows-x64 |
| 发行版 | GraalVM CE 还是 Oracle GraalVM（GFTC）由用户定；**推荐 CE**（§4） |
| 正确性 | 原生产物进同一个 package-v1 一致性门；元数据缺失只能靠这道门发现。先非阻塞，原生资产进入发布时升为必需检查（§5） |
| 发布 | 列车照常，原生资产随 truffle tag；构建不可复现，`.sha256` 在构建时算出并回读校验，来源靠构建证明（§6） |
| 深度 | Truffle 加确定的 `maxCallDepth = 10 000`（与 Node 同值、同计数、同消息），求值线程显式 64 MiB 栈（§7） |
| JIT | 不做镜像内 Truffle JIT，原生只走解释器（§9） |
| 用户要提供 | Apple Developer ID 证书与公证凭据、Windows 签名身份、Homebrew tap 与 token、发行版与平台的选择（§6.3、§10） |

---

## 1. 现状与测量

spike 于 2026-11-22 在 truffle `e5d785d`（生态 1.0.54）上完成，工具链 Oracle GraalVM 25+37.1，linux-arm64 另在 `container-registry.oracle.com/graalvm/native-image:25` 容器里构建。spike 报告原文见 [`docs/superpowers/plans/2026-11-22-adr0063-native-spike-report.md`](../superpowers/plans/2026-11-22-adr0063-native-spike-report.md)（原始计时数据与产物未入库），要点：

**性能**（macOS arm64，10 核，每项 10 次交错执行，中位数；测量期间机器有其他负载，绝对值偏高，相对关系稳定）：

| 命令 | 原生 -O2 | 原生 -Os | jar（`java -jar`） | Node（ts 3.6.0，Node 24） |
|---|---|---|---|---|
| `verify --recompile --probes` | **25.1 ms** | 30.8 | 642.7 | 217.2 |
| `run` | **24.4 ms** | 30.2 | 626.4 | 210.8 |
| `show` | **20.0 ms** | 22.4 | 322.2 | 160.0 |
| 峰值 RSS（`run`） | 42 MB | 36 MB | 245 MB | 91 MB |

**体积**：-O2 58.4 MB（gzip 23.6 MB），-Os 38.7 MB（gzip 14.8 MB）；fat jar 12.3 MB 另需 JRE 25。truffle#201 去掉旧元数据的宽资源匹配后 -O2 为 56.9 MB（macOS）/ 57.7 MB（linux-x64）。

**正确性**：`package-conformance.mjs --strict-formats` 652/652（-O2、-Os、-O2 + 2 MiB 栈三个构建，以及 linux-arm64 容器构建）；455 个多语言源文件的 `compile` 与 jar 逐字节相同；签名验证（JDK Ed25519、内置 JWKS、`--jwks https://…`）、四个词法表、超时与取消都正常。

**CI 实测**（truffle#201，GitHub 托管 runner；明细见实施计划「CI 基线」）：

| 目标 | 发行版 | `nativeCompile` | 体积 | 最低 glibc | 原生一致性 / 跨 CLI |
|---|---|---|---|---|---|
| linux-x64（`ubuntu-24.04`） | Oracle GraalVM 25 | 174 s | 57.7 MB | 2.34 | 652/652 / 652/652 |
| linux-arm64（`ubuntu-24.04-arm`） | Oracle GraalVM 25 | 218 s | 58.8 MB | 2.34 | 652/652 / 652/652 |
| macos-arm64（`macos-15`） | Oracle GraalVM 25 | 434 s | 56.8 MB | — | 652/652 / 652/652 |
| linux-x64（CE 对照） | GraalVM CE 25 | 167 s | 58.0 MB | 2.34 | 652/652 / 652/652 |

**spike 暴露的问题**（均已有 PR）：
1. `:cli` 运行时闭包里 `org.graalvm.sdk:graal-sdk / nativeimage / word` 被 aster-lang-runtime 泄漏的 `enforcedPlatform(quarkus-bom)` 压到 23.1.2，与 Truffle 25.0.4 混用。JVM 上暂未出事，原生构建直接失败。truffle#201 在本仓对齐并加版本门；根因在 runtime（计划 T3）。
2. 根工程 `META-INF/native-image/` 过时：登记的 `CoreModel` 清单停在 ADR 0055 之前（缺 11 个 DTO），资源匹配 `.*\.json$` / `META-INF/.*` 把所有 jar 的 JSON 拉进镜像，序列化条目指向已不存在的类。truffle#201 改为按 classpath 生成 IR 模型元数据。
3. Truffle 没有确定的调用深度上限：jar 缺省栈约 1 000 层、原生缺省栈约 460 层、Node 恒为 10 000。truffle#202 修复（§7）。

**原生做不到的**：运行时加载额外的词法表 jar（ServiceLoader 与资源在构建期定型；fat jar 也只带四个 locale，用户层面无差别）；镜像内 JIT（§9）。

## 2. 目标与非目标

**目标**：给不想装 JRE 的用户一个与 jar 行为相同、启动快一个数量级的 `aster`；同一个一致性门守住三个发行（Node、jar、原生）；发布进现有列车，不新增版本号。

**非目标**：改变包格式或 manifest；把原生二进制放进策略包；镜像内 JIT；替代 jar（jar 仍是 JVM 平台与 A 包的运行时）；交叉编译（GraalVM 不支持，每个目标在对应 OS / 架构的 runner 上构建）。

## 3. 决策一：原生只是安装渠道

| 渠道 | 内容 | 前提 |
|---|---|---|
| GitHub Release 资产 | `aster-<ver>-<os>-<arch>.tar.gz`（windows 为 `.zip`），各带 `.sha256` 与构建证明 | 无（签名 / 公证见 §6.3） |
| Homebrew | 自有 tap `aster-cloud/homebrew-tap` 的 `aster` formula，`on_macos` / `on_linux` × `on_arm` / `on_intel` 指向 Release 资产与 sha256，列车最后一步自动发 bump PR | 用户建 tap 仓并提供 token（Q5） |
| npm 平台包（可选） | 参照 esbuild：壳包 + 每平台一个 `optionalDependencies`，按 `os` / `cpu` 选择；找不到平台包时回退到 `aster-lang-ts` 的 `aster.mjs` | 用户决定是否要（Q6）；命名须与 ts 包的 `aster` bin 协调 |
| `install.sh`（零成本补充） | curl 下载并按 `.sha256` 校验 | 无 |

`aster version` 的输出与 jar 完全相同（`toolchainId` 的 `build=aster-cli-<ver>`）。区分来源的 `;dist=native-<os>-<arch>` 会改 `version` 契约，本 ADR 不做；需要时另立修订。

## 4. 决策二：GraalVM 发行版（**开放决策 Q1**，推荐 CE）

| | GraalVM Community Edition | Oracle GraalVM（GFTC） |
|---|---|---|
| 许可 | 镜像里链接的 JDK / SubstrateVM 部分为 GPLv2 + Classpath Exception，Truffle API 等为 UPL；与本项目 Apache-2.0 分发兼容，条款不随时间变化 | GraalVM Free Terms and Conditions：允许免费生产使用与**不收费的再分发**；同一 LTS 的免费更新只到下一个 LTS 发布后一年（JDK 25 约到 2028 年秋），之后要么升级 JDK、要么买订阅。条款以 Oracle 当期文本为准，须法务确认 |
| 功能差异 | 无 PGO、无 ML 剖析推断、原生镜像只有 Serial GC | PGO、ML-PGO、G1 等 |
| 对本 CLI 的影响 | 单次调用 20–30 ms，几乎全是启动与解释器，Oracle 独有的峰值优化测不出收益 | spike 全部数字取自 Oracle GraalVM 25+37.1 |
| CI | `graalvm/setup-graalvm@v1` `distribution: graalvm-community` | `distribution: graalvm` |

**推荐 CE**：许可简单且永久，社区开源 CLI 的惯常做法；本 CLI 由启动主导，Oracle 独有优化没有可测收益；不受 GFTC 的更新期限约束。CE 的数据已有一部分：truffle#201 的 CE 对照（`native (linux-x64-ce)`）构建 167 s、58.0 MB（Oracle 57.7 MB）、原生一致性与跨 CLI 一致性都是 652/652；启动时间尚未在 CE 上单独测量（计划 T6 Step 0 补测）。若启动有实质差距，再回到 Oracle GraalVM，并由法务确认 GFTC 下的再分发。

## 5. 决策三：原生产物进同一个一致性门

reachability 元数据（反射、资源）缺一条，原生产物就在运行到那条路径时才失败，单元测试和 jar 门都发现不了。所以：

1. **truffle CI**（truffle#201 已实现）：`native` 矩阵 job（linux-x64 `ubuntu-24.04`、linux-arm64 `ubuntu-24.04-arm`、macos-arm64 `macos-15`，外加 CE 对照）依次跑 `:cli:nativeSmoke`（原生 `version` 与 jar 逐字节相同）、`:cli:nativeConformanceTest`（`ConformanceCasesTest` 以子进程跑原生可执行文件，全部用例对期望）、aster-lang-test `package-conformance.mjs --strict-formats`（与 Node CLI 逐用例跨 CLI 比较）、`test-package-v1.mjs`（夹具生成器自测，不经 CLI）。
2. **先非阻塞**：分支保护与 `.github/main-ci-gate.json` 仍只要求 `build`。原因：原生还没有发布渠道；依赖升级（列车的 pin bump）后元数据漂移不应在原生上线前阻塞 JVM 制品的列车；托管 runner 上的时长与稳定性需要几周基线。
3. **升为必需**（计划 T6）：原生资产进入 `release.yml` 的同一个 PR 里，把 `native (linux-x64)` 加进 truffle 分支保护与 `main-ci-gate.json`（ADR 0053 A2 的门禁随之覆盖原生），其余平台在发布 run 的原生矩阵里各自跑冒烟与一致性，任一目标失败则整个发布失败（不发布残缺的资产集）。
4. **aster-lang-test 侧**：`package-conformance.mjs` 的 jre 一侧今天固定为 `java -jar <jar>`，truffle CI 用 PATH 上的 `java` 垫片替换（与 spike 相同）。计划 T4 给脚本加原生侧参数（`--native <exe>` 作为第三侧，或 `--jre-cmd`），之后去掉垫片，并在 aster-lang-test 自己的 `package-conformance` job 里加原生侧。
5. **元数据维护**：IR 模型类（`aster.truffle.core.CoreModel$*`、`aster.core.ir.CoreModel$*`、`VocabularyLoader$*`）由构建任务按 classpath 生成，不手写；其余元数据由 tracing agent 跑一致性门采集，依赖升级后门变红时按 truffle README 的步骤重采。

## 6. 决策四：发布与列车

### 6.1 构建与资产

- truffle `release.yml` 现有 `publish` job（jar、启动脚本、`.sha256`）不变。新增 `native` 矩阵 job，与 `publish` 同在 tag-push 的发布 run 里、同受 ADR 0053 A2 门禁约束。每个目标：检出兄弟仓 → `./gradlew :cli:nativeCompile`（x64 加 `-PnativeMarch=compatibility`）→ 冒烟（`version`、jingyesi `verify --recompile --probes`）→ 原生一致性 → 签名 / 公证（§6.3）→ 打包 → 写 `.sha256` → 构建证明 → 存为 workflow artifact。全部目标都成功后，由一个汇总 job 一次性 `gh release upload --clobber` 并下载回读 `sha256sum -c`（照抄 jar 的回读步骤）；任一目标失败则一个原生资产都不上传，Release 上不会出现残缺的原生资产集。
- 资产命名：`aster-<ver>-linux-x64.tar.gz`、`-linux-arm64.tar.gz`、`-macos-arm64.tar.gz`、`-macos-x64.tar.gz`、`-windows-x64.zip`；包内只有 `aster`（或 `aster.exe`）、`LICENSE`、`NOTICE` 与第三方许可文件。
- 列车：L4 pin bump 不变，原生资产跟随 truffle tag；列车 preflight 不需要新步骤（门禁已覆盖）。

### 6.2 `.sha256` 与不可复现

同一提交、同一参数的两次原生构建**字节不同**（spike：macOS 上 Mach-O UUID 等），所以无法靠重建复核资产。规则：

- `.sha256` 在构建它的 job 里算出（GNU 格式，与 jar 一致），上传后回读校验——它证明下载完整，不证明来源；
- 来源证明用 GitHub 构建证明（`actions/attest-build-provenance`，Sigstore 无密钥签名，用户可 `gh attestation verify`），所有平台都做；它不需要用户提供任何证书；
- macOS / Windows 另有平台签名（§6.3）。jar 仍是唯一可复现的 JVM 发行物。

### 6.3 签名与公证：用户必须提供的东西

| 平台 | 需要什么 | 以什么形式交给 CI | 不提供的后果 |
|---|---|---|---|
| macOS 签名 | Apple Developer Program（组织账号，99 美元 / 年）下的 **Developer ID Application** 证书（导出为带密码的 `.p12`）；Team ID | secret `APPLE_DEVELOPER_ID_P12_BASE64`、`APPLE_DEVELOPER_ID_P12_PASSWORD`；variable `APPLE_TEAM_ID` | macOS 资产不发布（见下方规则） |
| macOS 公证 | App Store Connect API key（Developer 角色即可）：issuer ID、key ID、`.p8` 私钥 | secret `APPLE_NOTARY_KEY_P8_BASE64`；variable `APPLE_NOTARY_ISSUER_ID`、`APPLE_NOTARY_KEY_ID` | 同上。裸可执行文件不能 staple：提交 zip 公证，首次运行时 Gatekeeper 在线核验 |
| Windows 签名（推荐 Azure Trusted Signing） | Trusted Signing 账号（需实体身份验证，约 10 美元 / 月）、证书 profile，以及一个被授予该账号签名角色的 service principal | secret `AZURE_CLIENT_SECRET`；variable `AZURE_TENANT_ID`、`AZURE_CLIENT_ID`、`AZURE_TRUSTED_SIGNING_ENDPOINT`（账号所在区域的端点，如 `https://eus.codesigning.azure.net`）、`AZURE_TRUSTED_SIGNING_ACCOUNT`、`AZURE_TRUSTED_SIGNING_PROFILE` | Windows 资产不发布 |
| Windows 签名（替代：OV / EV 证书） | 现行规则下私钥必须在 HSM 或云签名服务里；凭据形态取决于服务商 | 选这条时由计划 T8 按服务商另定变量名，本 ADR 不预设 | 同上 |
| Linux | 不需要 | — | — |
| 全部 | 构建证明：只需 workflow 权限 `id-token: write`、`attestations: write` | 无 secret | — |
| Homebrew | tap 仓 `aster-cloud/homebrew-tap`；对它有 `contents: write` 的 fine-grained token | `HOMEBREW_TAP_TOKEN` | 无 brew 渠道，用户从 Release 下载 |
| npm（若 Q6 选是） | `@aster-cloud` scope 下新包名的发布权（现有 org `NPM_TOKEN`，或为新包配置 trusted publishing） | 既有 secret 或 npm 端配置 | 无 npm 渠道 |

**签名是发布该平台资产的硬前提**：仓库变量 `NATIVE_RELEASE_TARGETS`（由用户随凭据一起设置）列出要发布的目标。所有目标的构建、冒烟与一致性都是发布门，任一失败则原生资产一个都不上传；未列入的目标跳过签名，job 照常成功但不产出上传 artifact；列入的目标缺凭据或签名失败即 job 失败、发布失败，不静默降级为未签名。Linux 没有平台签名，只有构建证明，凭据方面不受限制。

macOS 签名使用 `codesign --options runtime --timestamp`（hardened runtime）。原生镜像不在运行时生成代码，预计不需要任何 entitlement；计划在第一次签名时实测签名后的二进制仍通过一致性门。

## 7. 决策五：调用深度与栈（替换修订 1 A4 的 JVM 一句）

修订 1 A4 写的是「JVM CLI 在缺省线程栈上求值，不调 `-Xss`」，于是 Truffle 的深递归结果取决于栈大小与帧布局：jar 约 1 000 层、原生约 460 层、Node 恒为 10 000。原生发行把这个差异变成了 jar 与原生之间的静默分歧，一致性门（只测 10 层与 20 000 层）发现不了。新条款：

- Truffle 有确定的调用深度上限 **10 000**，与 aster-lang-ts 包格式 CLI 的 `CLI_MAX_CALL_DEPTH` **同值、同计数口径**（入口算第 1 层；每进入一次函数体或 lambda 体加 1，经 `List.map` 等 builtin 回调的也算；程序外壳与 workflow 帧不算）、**同消息**（`Maximum call depth (10000) exceeded`），超限为 `RESOURCE_EXHAUSTED`、退出码 5。实现：truffle#202，边界值逐个对照 ts 3.6.0 实测。
- CLI 的求值线程（jar 与原生同一份代码）显式使用 **64 MiB** 栈（只是保留的虚拟地址，按需提交），使上限先于真实栈溢出生效。求值深度只由这两项决定。
- 原生可执行文件另带 `-R:StackSize=2m`：它只设定**未显式给栈的其他线程**（看门狗、关闭、取锚等）的缺省栈字节数，与 JVM 缺省同为 2 MiB；原生帧比 JVM 小，同样字节数下原生能递归更深（spike：约 2 000 层 vs jar 约 1 080 层），所以它不是深度一致性的手段，只是避免这些线程用上原生更浅的缺省值。
- 效果（实测：macOS arm64 的 jar 与原生、Linux x64 的 jar；Linux / Windows 原生由 T7 的共享夹具补证——夹具进 `a-deep-recursion.cases.json` 后，truffle `native` 矩阵与发布矩阵的 `nativeConformanceTest` 在每个原生目标上都会跑到它，后者是发布门）：1 000–9 999 层的递归由失败变为成功，与 Node 一致；10 000 层起三方一致地失败。`Thread` 的 stackSize 按 JDK 规范只是提示，若某平台忽略它，结果退回到真实栈溢出、仍是 `RESOURCE_EXHAUSTED`，由原生门发现。单帧很大的函数仍可能先撞真实栈，那仍是资源类结果，ADR 0059 §5.3 的承诺边界不变。
- aster-api 平台经同一个库得到同一上限，但请求线程保持缺省栈，约 1 000 层先溢出，平台结果不变。
- 共享夹具：aster-lang-test 的 `a-deep-recursion` 宜补 `depth-9999`（成功）与 `depth-10000`（`RESOURCE_EXHAUSTED`）。夹具生成器的 `--verify-engines` 目前在主线程以解释器缺省 `maxCallDepth = 50` 复算成功用例，要先改为按 CLI 上限在 Worker 里复算（计划 T7）。

## 8. 决策六：与 ADR 0059 A 包（P3、计划后续 F2）的关系

- A 包继续带 jar（jre-a）或 `aster.mjs`（node-a），原生二进制**不进** A 包，也不进 `export-runtimes.lock.json`。理由：A 包是「单个 zip、自带运行时、离线可跑」；原生是平台相关的，换成它要么按 OS / 架构出五种 A 包，要么一个包塞五个二进制（gzip 后约 75–120 MB），都远超修订 1 A3 的 jre-a 18 MiB 预算，aster-api 镜像也要内置全部五个二进制。
- F2（锁文件的 `packageFormats` 同样约束随包 CLI，修订 4 D12）不受影响：原生 `version` 的 `formatVersions` 与 jar 相同，A 包信任自举（ADR 0059 §6.2）不变。原生签名 / 公证只证明「是 Aster 发布的 CLI」，不证明 A 包未被整体替换。
- 原生 CLI 是 **B 包**的推荐运行方式：B 包 README 增加「`brew install aster-cloud/tap/aster` 或从 Release 下载」的指引（计划 T10，导出端 README 模板改动在 aster-api）。
- 若将来客户明确要「无 JRE、无 Node 的单平台离线包」，另立 ADR 0059 修订：manifest `target: "native-<os>-<arch>"`、单独的体积预算（参考 -Os gzip 约 15 MB）、锁文件按平台列出。
- ADR 0059 §13 P4 的「native-image 单文件」由本 ADR 承接（形态是安装渠道而非包内运行时）；§14「GraalVM native-image 二进制作为 v1」的否决理由（按平台出包、构建慢）对包格式仍成立，本 ADR 不推翻它。

## 9. 决策七：镜像内 JIT 不做

加入 `truffle-runtime / truffle-compiler` 后 Oracle GraalVM 启用运行时编译，但部分求值检查失败：24 处黑名单违例（`AsterRootNode.execute`、`LambdaRootNode.execute`、`AsterMapValue.readMember` 等在可编译路径上直接调 `HashMap` / `Collection.stream` / `BigDecimal.toPlainString`，没有 `@TruffleBoundary`），以及 `neverPartOfCompilation` 可达。系统性的 PE 卫生估 2–4 人日；而单次 CLI 求值只有几毫秒，JIT 来不及编译进程就结束了，镜像还会显著变大。原生保持仅解释器，与今天 stock JRE 上的 jar（回退解释器）持平。若将来出现长时间求值或批量模式，再单独评估。

## 10. 决策（产品负责人 2026-11-22 采纳，Q1–Q7 均按推荐答案）

采纳不等于可发布：每个平台的原生资产仍被阻塞，直到用户提供 §6.3 列出的凭据；凭据到位前，对应目标只在 CI 构建与测试（§6.3 `NATIVE_RELEASE_TARGETS` 规则不变）。

| # | 问题 | 决定 |
|---|---|---|
| Q1 | GraalVM CE 还是 Oracle GraalVM（GFTC） | **决定：GraalVM Community Edition**（§4）；CE 对照已通过一致性、体积相当，发布前补测启动时间，若有实质差距再议 |
| Q2 | Linux 最低 glibc | **决定：最低 glibc 2.34**（`ubuntu-24.04` 构建的实测下限；覆盖 Ubuntu 22.04+、Debian 12+、RHEL / OL 9+）。更老的发行版（RHEL 8、Amazon Linux 2）用 jar；若必须支持，在 `oraclelinux:8` 容器里构建（glibc 2.28），或 x64 用 `--static --libc=musl`（需重测） |
| Q3 | macOS 是否签名 + 公证（§6.3 的 Apple 证书与 API key） | **决定：是，签名与公证为必需**。签名是发布 macOS 资产的硬前提：凭据到位前 macOS 目标只在 CI 构建与测试，不发布（Homebrew formula 先只含 Linux） |
| Q4 | 是否发 macos-x64 | **决定：发 macos-x64**，只要 GitHub 还提供 x64 macOS runner（`macos-15-intel`）；runner 退役时停发，不做交叉方案 |
| Q5 | Homebrew tap | **决定：建 `aster-cloud/homebrew-tap`，列车最后一步自动发 bump PR** |
| Q6 | npm 平台包 | **决定：暂不做**：Homebrew + Release + `install.sh` 已覆盖；npm 上已有 `aster.mjs`。有明确需求时再做（约 1.5–2 人日，每次列车多发 6 个包） |
| Q7 | Windows 是否发布、用哪种签名 | **决定：发 windows-x64（`windows-2025`），用 Azure Trusted Signing**。签名是发布 Windows 资产的硬前提；到位前 CI 非阻塞实测（计划 T5），不发布 |

## 11. 风险

| 风险 | 缓解 |
|---|---|
| 元数据漏一条，原生在某条路径上运行时失败 | 原生一致性门（§5）；IR 模型类按 classpath 生成；升为必需检查后漂移即挡发布 |
| GraalVM 与 Truffle 版本错配（builder 25.0.x，Truffle 25.0.4 来自 Maven） | catalog 单一版本源 + `checkGraalvmVersions` 门；CI 的 GraalVM 版本随 catalog 升级 |
| 构建慢、占内存（本机 70–80 s / 3.6–3.9 GB，托管 runner 约 3.5 min） | 每 PR 只在三个平台跑一次；发布矩阵并行，墙钟约 10 min |
| 不可复现 | `.sha256` + 构建证明 + 平台签名（§6.2） |
| GFTC 条款变化（若选 Oracle） | 推荐 CE；若选 Oracle，法务确认并在列车文档里记下当期条款版本 |
| CA 信任库在构建时固化 | 与 jar 随 JRE 固化等价；每次列车重建即刷新 |

## 12. 分期

| 期 | 内容 | 依赖用户 |
|---|---|---|
| N1 构建与门（进行中） | truffle#201（SDK 对齐、元数据、`nativeCompile`、原生一致性、非阻塞 CI）、truffle#202（深度上限） | 否 |
| N2 一致性门原生侧 | aster-lang-test `package-conformance.mjs` 原生侧参数、test 仓 gate 加原生；夹具 `depth-9999` / `depth-10000` 与生成器 Worker 复算；runtime 不再泄漏 enforcedPlatform | 否 |
| N3 发布 | `release.yml` 原生矩阵（五个目标）、打包、`.sha256`、构建证明、回读；原生 job 升为必需检查 | Q1、Q2、Q4、Q7 |
| N4 签名与渠道 | macOS 签名 + 公证、Windows 签名、Homebrew tap 与自动 bump、`install.sh`；B 包 README 指引 | Q3、Q5、Q7（Q6 若做） |

## 13. 不选的方案

| 方案 | 不选的理由 |
|---|---|
| 原生二进制进 A 包 | 平台相关，五倍体积或五种包，远超 18 MiB 预算（§8） |
| 镜像内 JIT | PE 卫生 2–4 人日，单次 CLI 无收益（§9） |
| 用 fat jar 做 native-image 输入 | shadow 合并后 `org.graalvm.word` 的 `ForceOnModulePath` 失效，构建失败；用 runtimeClasspath 的分开 jar |
| jlink 裁剪的 JRE + jar | 仍要约 40–60 MB 运行时、启动仍是 JVM 量级（约 600 ms），收益只是「不用装 JRE」 |
| 按 Truffle 栈大小而非固定上限截深度 | 结果随机器与发行变化，违背 AllocationBudget 的确定性铁律 |
| 在 qemu 模拟下构建 linux-x64 | 实测 analysis 一步就慢 44 倍（1 715 s），用原生 x64 runner |
