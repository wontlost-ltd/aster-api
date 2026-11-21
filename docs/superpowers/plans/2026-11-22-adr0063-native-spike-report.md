> 本文件是 ADR 0063 的证据：2026-11-22 native-image spike 报告原文入库（只把执行会话临时目录的绝对路径换成 `<scratchpad>`）。产物、原始计时数据与脚本未入库。

# Spike：Aster JRE CLI 作为 GraalVM native image 发布——可行性与价值

- 日期：2026-11-22；执行：Claude（只出报告，未打 tag / 未发布 / 未合并 / 未开 PR）
- 被测代码：aster-lang-truffle `origin/main` = `e5d785d`（生态 pin 1.0.54），worktree `~/IdeaProjects/wt-native-spike/aster-lang-truffle`（未提交任何改动；JIT 试验补丁已还原，diff 留在 `scratchpad/native/pe-boundary-attempt.diff`）；aster-lang-test `origin/main` = `97e9eef`，worktree `~/IdeaProjects/wt-native-spike/aster-lang-test`
- 依赖仓：隔离 Maven 仓 `scratchpad/m2-native`（由 `scratchpad/m2-rel1054` 的 1.0.54 产物播种；1.0.54 在 Maven Central 尚不可见）；`~/.m2/repository/cloud` 始终不存在
- 工具链：本机已装的 Oracle GraalVM 25+37.1（`~/Library/Java/JavaVirtualMachines/graalvm-jdk-25+37.1`，自带 `native-image`），只经绝对路径调用，未改系统 JDK 或全局设置；linux 用 `container-registry.oracle.com/graalvm/native-image:25`（25.0.5.1）
- 机器：MacBook arm64，10 核 / 64 GB。**测量期间另一会话在跑 Gradle 测试，1 分钟负载 70–140**；测量用每轮随机交错的方式让四个 CLI 均摊干扰，结果稳定（p90 与中位数差 < 5%），但绝对值偏高于空闲机

所有产物在 `scratchpad/native/`：`b3/aster`（-O2）、`b5/aster`（-Os）、`b6/aster`（-O2 + `-R:StackSize=2m`，推荐配置）、`cfg/`（元数据）、`bench/`（脚本与原始数据 `r1.json`）、`conf-*.md`（一致性报告）。

---

## 1. 结论

**有条件推进（go with conditions）。** 原生可执行文件在 macOS arm64 上通过 652/652 包一致性（`--strict-formats`），455 个源文件的 compile 与 jar 逐字节相同；linux-arm64（容器构建）同样 652/652。linux-x64 在本机 qemu 模拟下太慢（约慢 44 倍），已中止，改为在 CI 用原生 x64 runner 构建；单次调用 20–26 ms，比 jar（320–650 ms）快 25 倍，比 Node CLI（160–220 ms）快 8 倍；峰值 RSS 33–43 MB（jar 200–250 MB）；`sun.misc.Unsafe` 警告消失。代价是二进制 58 MB（gzip 24 MB；-Os 39 / 15 MB）、每平台一份构建、要维护 reachability 元数据，以及 macOS 公证 / Windows 签名需要用户提供证书。

条件：
1. 先修两个与原生无关但被它暴露的问题：CLI 运行时闭包里 `org.graalvm.sdk:graal-sdk / nativeimage / word` 被降到 23.1.2（与 truffle 25.0.4 混用）；Truffle 侧没有确定的调用深度上限（见 §5.3）。
2. 原生产物必须进同一个跨 CLI 一致性门（第三侧，或替换 jre 侧），元数据缺失只能靠这道门发现。
3. 原生只作为**安装渠道**（Release 资产 / Homebrew / npm 平台包），不进 A 包；A 包继续带 jar 或 `aster.mjs`（§7.6）。
4. 不做「镜像内 Graal JIT」：当前节点代码过不了部分求值检查，而且对单次 CLI 没有收益（§4.4）。

---

## 2. 测量（macOS arm64，每项 10 次，交错执行）

命令在 `scratchpad/native/w` 下执行，输入 `in.json` = `{}`，`--display` 用 `t8/edit-jre.txt`。两个包（jre / node）结果相差 ≤ 1 ms，下表取 `jingyesi-jre.zip`（两包完整数据见 `bench/r1.json`）。所有调用退出码 0，原生与 jar 的 stdout 逐字节相同。

### 2.1 墙钟（ms，中位数 / p90）

| 命令 | native -O2 | native -Os | jar（`java -jar`） | Node（ts 3.6.0，Node 24.13） |
|---|---|---|---|---|
| `verify --recompile --probes` | **25.1** / 27.1 | 30.8 / 31.3 | 642.7 / 656.3 | 217.2 / 220.5 |
| `run` | **24.4** / 25.1 | 30.2 / 34.4 | 626.4 / 659.7 | 210.8 / 213.2 |
| `show` | **20.0** / 20.5 | 22.4 / 23.0 | 322.2 / 325.7 | 160.0 / 161.5 |
| `run --display` | **25.1** / 25.6 | 30.9 / 33.6 | 645.7 / 658.6 | 217.2 / 226.4 |

jar 数值与今天的基线（620–700 ms）一致；本次 Node 比基线（340–430 ms）低，因为用的是 Node 24 而不是基线时的环境，且计时不含 shell 包装。

### 2.2 峰值 RSS（MB，10 次中的最大值）

| 命令 | native -O2 | native -Os | jar | Node |
|---|---|---|---|---|
| verify | 43.1 | 37.2 | 247.8 | 93.0 |
| run | 42.1 | 36.2 | 244.5 | 90.8 |
| show | 32.7 | 29.2 | 198.8 | 75.9 |
| run --display | 43.2 | 37.3 | 249.1 | 92.4 |

### 2.3 体积

| 产物 | 未压缩 | gzip -9 | zip -9 |
|---|---|---|---|
| native -O2（b3 / b6） | 58.4 MB（58 446 648 B） | 23.6 MB | 23.6 MB |
| native -Os（b5） | 38.7 MB（38 652 600 B） | 14.8 MB | 14.8 MB |
| fat jar（对照，另需 JRE 25） | 12.3 MB | — | — |

镜像组成（-O2）：代码区约 31 MB（java.base 13.2 MB、svm 4.0、truffle 3.1、jackson-databind 2.3、aster-lang-core 2.0、java.net.http 1.3 …），镜像堆 26 MB（319 144 个对象，383 个资源）。原生超出 ADR 0059 修订 1 A3 的 jre-a 18 MiB 预算，若进 A 包需要单独的预算行（§7.6 建议不进）。

### 2.4 构建时间与内存（macOS arm64，10 线程，负载 70–140 下）

| 变体 | 墙钟 | 峰值 RSS | 备注 |
|---|---|---|---|
| -O2（b3） | 75 s | 3.62 GB | 第一次成功构建（b2，负载更高）137 s / 3.28 GB |
| -O2 + StackSize（b6 / b7） | 81 s | 3.76 GB | 推荐配置 |
| -Os（b5） | 43 s | 2.56 GB | |
| linux-arm64（容器 5 vCPU，不经模拟） | 220 s | 3.57 GB | 与模拟 amd64 构建同时进行 |
| linux-amd64（模拟，容器 5 vCPU） | 预计超过 1 h，中止 | — | 只 analysis 一步就用了 1715 s，见 §6 |

不计前置的 `./gradlew :cli:jar`（增量几秒）。两次相同参数的构建（b6 与 b7）**字节不同**（Mach-O UUID 等），原生产物不可逐字节复现：Release 只能发布构建时算出的 `.sha256`，不能靠重建复核。

### 2.5 吞吐 / 热循环

CLI 没有批量模式：一次进程只处理一个包、一个入口、一份输入。用 `a-deep-recursion` 做「求值占比更高」的近似（`run`，10 次中位数）：

| n | native -O2 | native -Os | jar | Node |
|---|---|---|---|---|
| 10 | 21.3 ms | 23.8 ms | 637.4 ms | 263.4 ms |
| 1500 | 23.6 ms（RESOURCE_EXHAUSTED，b3 缺省栈） | 29.0 ms（同） | 620.0 ms（RESOURCE_EXHAUSTED） | 280.5 ms（ok） |

这个包的求值本身只占几毫秒，进程启动主导一切。长时间求值（秒级）下 JVM 的 C2 会把 Truffle 解释器本身编译得比 AOT 更好，原生可能反而慢；没有批量模式无法测，现有策略都在 5 s 超时内的毫秒级，判定为不影响结论。

### 2.6 JIT（镜像内 Truffle 运行时编译器）vs 仅解释器

只有仅解释器版本能构建。加入 `truffle-runtime / truffle-compiler / jniutils / nativebridge` 25.0.4 后，Oracle GraalVM 启用 `EnterpriseTruffleCompilerFeature`，但部分求值（PE）检查失败：

- 24 处黑名单违例：`AsterRootNode.execute / bindArgumentsToFrame`、`LambdaRootNode.execute`、`AsterMapValue.readMember`、`AsterInteropAdapter.adapt` 等在可编译路径上直接调 `HashMap / ConcurrentHashMap / Set.copyOf / Collection.stream / BigDecimal.toPlainString / String.valueOf`，没有 `@TruffleBoundary`；
- `CompilerAsserts.neverPartOfCompilation` 可达：`context.getEnv().asGuestValue(...)`、`PiiSupport.wrapValue` 里经 host 代理调 polyglot 等。

用 `-H:-TruffleCheckBlockListMethods` 关掉黑名单检查并给 3 个方法加 `@TruffleBoundary` 后，违例从 5 处增至 10 处（`bindArgumentsToFrame` 等）。要做成需要系统梳理节点的 PE 卫生，估 2–4 人日。而单次 CLI 求值只有几毫秒，JIT 来不及编译就结束了，加上 Graal 编译器会显著增大镜像（未实测）。**体积 vs 速度的结论只能给出 -O2 与 -Os 两个点：-Os 小 34%（gzip 小 37%），慢 10–25%（约 +3–6 ms）。**

---

## 3. 正确性

| 检查 | 原生 b3（-O2） | 原生 b5（-Os） | 原生 b6（-O2 + 2m 栈） | jar（对照） |
|---|---|---|---|---|
| `package-conformance.mjs --strict-formats`（652 例，Node 侧为 ts 3.6.0） | **652/652** | **652/652** | **652/652** | 652/652 |
| 原生 vs jar `compile`：tier1-parity + tier1-equivalence + tier1-reject 共 455 个源（en 392 / zh 38 / de 20 / hi 5，含 96 个拒绝用例） | — | — | **455/455 逐字节相同**（stdout + 退出码） | — |
| `test-package-v1.mjs`（生成器自测，不经 CLI） | 302 passed / 0 failed | | | |

一致性门的跑法：给 `package-conformance.mjs` 的 `--jar` 仍传 jar 路径，PATH 前置一个 `java` 垫片（`scratchpad/native/shim-native/java`），把 `java -jar <jar> …` 换成原生可执行文件，脚本本身未改。原生一侧耗时中位数 25 ms / p95 43 ms（jar 421 / 1170 ms，Node 160–179 / 266–361 ms）。

**失败与调查**：
- 采集元数据时，带 tracing agent 的 jar 跑一致性门为 651/652：`a-deep-recursion/depth-20000` 退出码 99 而不是 5。不带 agent 的 jar 与全部原生构建都是 652/652，所以这是 agent 在栈溢出路径上的干扰，不是产品问题。
- 第一次原生构建（只用 truffle 自带元数据）在 `verify --recompile` 失败：`PKG_IR_DRIFT … Cannot construct instance of aster.core.identifier.VocabularyLoader$JsonVocabulary … this appears to be a native image`。补上 agent 元数据后修复。

---

## 4. 需要的配置

### 4.1 classpath 而不是 fat jar

`native-image -jar aster-cli.jar` 直接失败：shadow 合并后的 `META-INF/native-image/org.graalvm.word/native-image.properties` 声明 `ForceOnModulePath`，而 fat jar 里没有模块描述符。必须用分开的 runtimeClasspath jar（Truffle / polyglot 由 driver 自动放到 module path）。同时要去掉被降级的 `graal-sdk-23.1.2.jar`、`nativeimage-23.1.2.jar`、`word-23.1.2.jar`（由 builder 提供 25.x 版本）。这次用 init 脚本导出 `:cli` 的 runtimeClasspath（`scratchpad/native/printcp.init.gradle`）。正式做法是在 `cli/` 用 `org.graalvm.buildtools.native` 插件（它直接用 `runtimeClasspath`），并在 `configurations.runtimeClasspath` 上排除或对齐这三个 23.1.2 坐标。

### 4.2 类初始化

```
--initialize-at-build-time=aster.truffle
--initialize-at-run-time=aster.truffle.Loader,aster.truffle.embed
```

Truffle DSL 生成的类（`AsterLanguageProvider`、`*Gen$InteropLibraryExports` …）必须在构建期初始化，否则报 `object of type … found in the image heap`。整个 `aster.truffle` 构建期初始化会把 `Loader` / `CoreIrEntrySelector` 的静态 Jackson `ObjectMapper` 拉进镜像堆，再连带 jackson-yaml / snakeyaml 失败，所以这两处（`Loader` 与 `aster.truffle.embed` 包）改回运行期。

### 4.3 其他选项

```
--no-fallback
--enable-url-protocols=https     # --jwks https://… 取锚
-R:StackSize=2m                  # 求值线程栈与 JVM 缺省一致，见 §5.3
-march=compatibility             # 仅 linux/windows x64 发布用（-O2 缺省目标为本机 CPU 特性）
```

### 4.4 反射 / 资源元数据

- 用 `-agentlib:native-image-agent=config-output-dir=…/{pid}` 跑一遍一致性门（每个 jar 进程一个目录，`java` 垫片在 `scratchpad/native/shim-agent/java`），再 `native-image-configure generate` 合并。被 SIGKILL 的进程会留下 `.lock`，合并前要删掉（这次有 1 个）。
- 合并结果：155 条 reflection、42 条 resource。aster 相关：`aster.core.ir.CoreModel$*`（40）、`aster.core.identifier.VocabularyLoader$Json*`、4 个 `LexiconPlugin`、`lexicons/{en-US,zh-CN,de-DE,hi-IN}.json`、`syntax-diagnostics/*`、`overlays/*`、`core-ir/core-ir.v2.schema.json`、`aster/cli/*.json`、`version.properties`，以及 Ed25519 / TLS 的 `sun.security.*` 提供者类。
- agent 只记录走到的成员。为了不让一致性门没覆盖到的 IR 节点在运行时才失败，额外把 aster-lang-core jar 里**全部** `aster/core/ir/CoreModel$*`（71 个类型）与 `VocabularyLoader$*` 按全成员注册（生成脚本内联在构建步骤里，结果为 187 条 reflection）。
- 现有 `src/main/resources/META-INF/native-image/reachability-metadata.json` 登记的是旧包名 `aster.truffle.core.CoreModel`，对 CLI 无效（IR 类型已在 `aster.core.ir`），另外它触发 `Could not resolve aster.core.lexer.Position for serialization`、`proxy-config.json` 已弃用等告警。正式落地应在 `cli/src/main/resources/META-INF/native-image/cloud.aster-lang/aster-cli/` 放生成的元数据与 `native-image.properties`（Args = §4.2 与 §4.3），并清理旧文件。
- 维护：依赖升级后重跑 agent 生成元数据；靠原生一致性门兜底。

---

## 5. 评估

### 5.1 Unsafe 警告

**已消失。** jar 在 `verify / run / run --display` 上每次向 stderr 打 4 行 `sun.misc.Unsafe::objectFieldOffset … TStringUnsafe`（`show` 不打）；原生全部 0 行（包括 652 例一致性门）。原因是 Truffle 在镜像内用 SubstrateVM 自己的实现，不经 JDK 的 `sun.misc.Unsafe` 访问检查。#188 的 `aster` / `aster.cmd` 启动脚本对原生用户不再需要。

### 5.2 签名、语言、TLS

- **签名验证**：可用。JDK 自带的 `Ed25519`（`KeyFactory("Ed25519")` + `EdECPublicKeySpec`，没有第三方加密库）、内置 JWKS 快照 `export-jwks.json`、`--pubkey`、`--jwks <file>`、`--jwks https://…`（一致性门里的 HTTPS 重定向 / 1 MiB 响应 / 自签 CA 用例全过）都正常。另外实测：真实公网 HTTPS 握手成功；过期证书 `expired.badssl.com` 被拒，报错文本与 jar 相同。注意 CA 信任库在构建时固化（与随 JRE 版本固化等价）。
- **4 个词法表**：en-US / zh-CN / de-DE / hi-IN 都可用：`LexiconPlugin` 由 ServiceLoader 在构建时解析，词法表 JSON 作为资源打入镜像。证据是 455 个多语言源的 compile 与 jar 逐字节相同，未知 locale `xx-XX` 的报错也相同。
- **超时 / 取消**：`a-timeout`、`a-probe-timeout` 通过；看门狗线程加 `Context.close(true)` 在原生上工作正常。

### 5.3 递归深度：原生缺省栈比 JVM 浅

`run a-deep-recursion` 二分出的最大成功 n：

| | 最大 n |
|---|---|
| jar（JVM 缺省 `ThreadStackSize` 2 MB） | ≈ 1083 |
| 原生 b3 / b5（缺省线程栈） | ≈ 459 / 498 |
| 原生 `-Xss1m` / `-Xss2m`（运行时） | ≈ 1005 / 2001 |
| 原生 b6（构建时 `-R:StackSize=2m`） | ≈ 2001 |
| Node（`maxCallDepth` = 10 000） | > 1500（已测） |

不加 `-R:StackSize`，深度 460–1083 的策略在 jar 上成功、在原生上返回 `RESOURCE_EXHAUSTED`，这是一个静默的跨发行分歧，一致性门（只测 10 与 20 000）发现不了。b6 修复了这一点（仍 652/652）。更根本的问题是：Truffle 没有确定的深度上限，结果取决于栈大小和帧布局。ADR 0059 §5.3 已把资源类结果排除在承诺之外，但建议在 `SandboxedExecutor` 给求值线程显式 `stackSize`，并在 Truffle 加 `maxCallDepth`（与 Node 一致），这样三方结果才确定。另外 a-deep-recursion 夹具描述写的是「JVM 约 2 000 层溢出」，实测约 1 083。

### 5.4 原生做不到的

- **热插拔词法表 jar**：不行。ServiceLoader 与资源在构建期定型，运行时加 jar 无效。现状的 fat jar 也固定带 4 个 locale，用户层面无回退；只是 `java -cp aster-cli.jar:extra.jar aster.cli.AsterCli` 这条非官方后门在原生上不存在。
- **动态类加载 / 其他 Truffle 语言或 instrument**：不可用（CLI 本来就不用；`ASTER_TRUFFLE_FUNC` 已被拒绝）。
- **镜像内 JIT**：见 §2.6，当前不可行；今天 stock JRE 上的 jar 也是回退解释器，二者持平。
- **Windows / linux-x64 / macOS x64**：本次没有实测（见 §6、§7）。linux-arm64 已实测通过。

### 5.5 顺带发现

- `:cli` 的 runtimeClasspath 里 `org.graalvm.sdk:graal-sdk / nativeimage / word` 被冲突解析降到 23.1.2，而 `truffle-api / polyglot / collections` 是 25.0.4。JVM 上暂时无害，但阻断原生构建，也是潜在的 API 不匹配。需要查清是哪个 BOM / 约束（aster-lang-runtime 或平台 catalog）把它们压下去的。
- 根工程 `META-INF/native-image/reachability-metadata.json` 已过时（见 §4.4）。
- 一致性门 jre 侧的 p95：jar 1.2 s，原生 43 ms。本机并发 4 下整门墙钟从 117 s 降到 31–33 s，剩下的时间主要花在 Node 侧。

---

## 6. linux-x64

**linux-x64 在本机模拟下不现实。** 在 podman（libkrun VM，5 vCPU / 20 GiB）里以 `--platform linux/amd64` 运行 `container-registry.oracle.com/graalvm/native-image:25`（Oracle GraalVM 25.0.5.1），用 `-march=compatibility -R:StackSize=2m`：
- `[1/8] Initializing` 66.8 s（原生 arm64 为 7.2 s）；
- `[2/8] Performing analysis` 1714.8 s（原生 arm64 为 39.0 s），约慢 44 倍；
- `[3/8] Building universe` 57.8 s。

照此推算，编译阶段还要 1 小时以上，因此在 analysis 之后停止。容器与镜像已删除。

**改为在容器中实测 linux-arm64**（同一镜像、同一 classpath / 元数据 / 选项，不加 `-march=compatibility`，不经模拟）：

| 项 | linux-arm64 |
|---|---|
| 构建 | EXIT 0，墙钟 220 s（与模拟 amd64 构建共享 5 vCPU），峰值 RSS 3.57 GB |
| 体积 | 58.2 MB，gzip -9 23.2 MB |
| 动态依赖 | `libz.so.1`、`libc.so.6`；需要的最高 glibc 符号版本 `GLIBC_2.34`（Oracle Linux 9 基础镜像决定） |
| 一致性门 | 在 `node:24`（Debian bookworm）容器里，`--strict-formats` **652/652**，原生侧中位数 88 ms / p95 208 ms（VM 5 核，含 virtiofs 开销） |

linux-x64 的 CI 方案：在 `ubuntu-24.04` 原生 x64 runner 上用 `graalvm/setup-graalvm@v1` 构建，产物与 arm64 只差架构。要支持更老的发行版，就在最老目标的容器里构建（例如 glibc 2.28 的 `oraclelinux:8`），或者用 `--static --libc=musl` 出全静态二进制（需要 musl 工具链，体积与性能需复测）。PR 上的原生一致性门放在这个 runner 上跑。

---

## 7. 发布方案

### 7.1 平台矩阵

| 目标 | GitHub 托管 runner | 备注 |
|---|---|---|
| darwin-arm64 | `macos-15`（或 `macos-14`） | 本次实测平台 |
| darwin-x64 | `macos-15-intel` | x64 macOS runner 即将退役；用户量小的话可以只发 arm64 |
| linux-x64 | `ubuntu-24.04` | `-march=compatibility`；glibc 动态链接，要定最低 glibc（在最老的支持发行版的容器里构建）；可选 `--static --libc=musl` 做全静态 |
| linux-arm64 | `ubuntu-24.04-arm` | 原生 arm runner，无需模拟 |
| windows-x64 | `windows-2025` | 需要 MSVC 工具链（`graalvm/setup-graalvm` 会配置）；未实测 |

GraalVM 不支持交叉编译，每个目标都要在对应 OS / 架构上构建。建议用 `graalvm/setup-graalvm@v1`（`java-version: 25`，`distribution: graalvm`，即 Oracle GraalVM，与本次实测一致，带 -Os 与 ML-PGO；GFTC 许可下允许免费分发，需要法务确认；也可用 `graalvm-community`，体积与性能需复测）。

### 7.2 CI 时长

- 单目标：本机 10 核 75–81 s。GitHub 标准 runner（linux 4 vCPU / 16 GB，macOS 3–4 核）估 3–6 min 构建（3.7 GB 峰值内存可以放下），加 checkout、Gradle 和元数据约 2 min。
- 每次发布：5 个目标 × 约 6–8 min ≈ 35–40 runner 分钟（其中 macOS 约 15 分钟，公证再加 2–5 分钟 / 次）。仓库是 PUBLIC，标准 runner 免费，所以实际代价只是墙钟：矩阵并行约 10 min，现有 publish job 约 5 min。若按私有仓计费，macOS 乘 10 倍，约 150–200 等效分钟 / 次。
- 每个 PR：建议只在 linux-x64 上构建原生并跑原生一致性门（约 8–10 min，可以和现有 jar 门并行），其他平台只在 release 时构建。

### 7.3 签名与公证（证书须由用户提供）

- **macOS**：Apple Developer Program（99 美元 / 年）的 Developer ID Application 证书（p12 加密码，存 secrets），`codesign --options runtime --timestamp`（hardened runtime；目前产物只有 linker 的 ad-hoc 签名）。再加 `notarytool submit --wait`，凭 App Store Connect API key（issuer id / key id / p8）或 Apple ID + app 专用密码。裸可执行文件不能 staple 公证票据，要放进 zip / pkg 提交，用户首次运行时 Gatekeeper 在线核验；Homebrew 安装的不受 quarantine 影响。
- **Windows**：Authenticode 证书。可以用 OV / EV（现在通常要求 HSM 或云签名），也可以用 Azure Trusted Signing（约 10 美元 / 月，需要实体验证）。签 `aster.exe` 加时间戳；没签的会被 SmartScreen 拦。
- **linux**：不需要。可以用 Sigstore / cosign 无密钥签名或 GitHub artifact attestation（`actions/attest-build-provenance`），二者都不需要证书。建议所有平台都加，以补足不可复现构建（§2.4）带来的空缺。

### 7.4 接入 `release.yml` 与列车

- 现有 `publish` job（ubuntu）保持不变，继续出 jar、启动脚本和 `.sha256`。新增 `native` 矩阵 job，`needs: publish`（或与它并行，`needs: create-tag` / 门禁同一 commit）。每个目标：checkout 兄弟仓（core 等，同现有步骤）→ `./gradlew :cli:nativeCompile`（buildtools 插件）→ 冒烟（`aster version` 加一个 jingyesi `verify --recompile --probes`）→ 签名 / 公证 → 打包 `aster-<ver>-<os>-<arch>.tar.gz`（windows 为 `.zip`）→ 用与 shadowJar 相同的 GNU 格式写 `.sha256` → `gh release upload --clobber` → 下载回读并 `sha256sum -c`（照抄现有 jar 步骤）。
- 门禁：ADR 0053 A2 的「main CI 绿」要求 `.github/main-ci-gate.json` 里加入原生一致性 job（在 aster-lang-test 的 `package-conformance` 中加第三侧，或用 `--jre-cmd` 参数替换 jre 侧）。
- 列车：L4 pin bump 不变，原生资产跟随 truffle tag。`aster version` 输出的 `toolchainId` 与 jar 相同（`build=aster-cli-<ver>`），可以考虑加 `;dist=native-<os>-<arch>` 区分来源（会改契约，要走 ADR 修订）。
- 有任一目标失败时：建议整个 release 失败，而不是部分资产缺失，与现有回读校验风格一致。

### 7.5 npm 与 Homebrew

- **Homebrew**：最省事。在自有 tap（`aster-cloud/homebrew-tap`）里写一个 formula，`on_macos / on_linux` 和 `on_arm / on_intel` 各指向 Release tar.gz 与 sha256，由列车最后一步自动发 PR 更新（`brew bump-formula-pr` 或脚本）。Homebrew 不需要公证（不加 quarantine），但仍建议签名。
- **npm**：参照 esbuild / biome 的做法。`@aster-cloud/aster-cli`（壳包，带 JS 启动器）加上 5 个 `@aster-cloud/aster-cli-<os>-<arch>` 平台包，壳包通过 `optionalDependencies` 加 `os` / `cpu` 字段挑选平台包，启动器找不到平台二进制时回退到 `aster-lang-ts` 的 `aster.mjs` 并给出提示。代价是每次列车多发 6 个 npm 包，并且要与 ts 包的 `aster` bin 协调命名，避免冲突。
- 另有一个零成本选项：只发 Release 资产，加一个 `install.sh`（curl 安装并按 `.sha256` 校验）。

### 7.6 与 ADR 0059「A 包」（P3，aster-api 计划 F2）的关系

- A 包的设计是**单个 zip 自带运行时、离线可跑**：`runtime/aster.mjs`（node-a）或 `runtime/aster-cli.jar`（jre-a）加 `run.sh / run.cmd`，运行时在 aster-api 镜像构建时按 `export-runtimes.lock.json`（版本 + sha256）取入。原生二进制是**平台相关**的，换成它就意味着按 OS / 架构出 5 种 A 包，或者在一个包里塞 5 个二进制（gzip 后合计约 75–120 MB，远超 jre-a 18 MiB 预算）。aster-api 镜像还要内置全部 5 个二进制。
- F2（锁文件的 `packageFormats` 同样约束随包 CLI）对原生同样适用：原生 `aster version` 的 `formatVersions` 与 jar 相同，校验逻辑不用改；A 包的信任自举（ADR §6.2，只能由包外验证者证明来源）也不变。原生签名 / 公证只能证明「是 Aster 发布的 CLI」，不能证明 A 包没被整体替换。
- **建议**：P3 的 A 包继续用 jar（jre-a）或 `aster.mjs`（node-a）。原生 CLI 作为 B 包的推荐运行方式（用户装一次 `brew install aster`，之后 `aster run pkg.zip`），在 B 包 README 里给出安装指引。只有当客户明确要求「无 JRE、无 Node 的单平台离线包」时，再加 `target: "native-<os>-<arch>"`（会改 manifest schema，需要 ADR 修订和单独的体积预算，例如 -Os gzip 后约 15 MB）。

---

## 8. 成本估算

| 工作项 | 估计 |
|---|---|
| `cli/` 接入 `org.graalvm.buildtools.native`，对齐 / 排除 23.1.2 SDK 坐标，落地 `META-INF/native-image/cloud.aster-lang/aster-cli/`（元数据、`native-image.properties`），清理过时的根元数据，加 `-R:StackSize=2m` | 1–1.5 人日 |
| 元数据生成脚本（agent 跑一致性门、合并、补全 CoreModel 全成员），文档化 | 0.5 人日 |
| aster-lang-test：`package-conformance.mjs` 支持原生侧（`--native <bin>` 或第三侧），CI 加 linux 原生 job，进入 main-ci-gate | 1 人日 |
| `release.yml` 原生矩阵（5 目标）、打包、`.sha256`、回读、冒烟 | 1.5–2 人日 |
| macOS 签名 + 公证、Windows Authenticode（证书到位后） | 1 人日 |
| Homebrew tap 与列车自动 bump；（可选）npm 平台包 | 1 人日；npm 再加 1.5–2 人日 |
| 确定性深度上限（Truffle `maxCallDepth` + 显式求值线程栈），与 Node 对齐的夹具 | 1–1.5 人日（与原生无关，建议无论如何都做） |
| **合计** | **约 6–8 人日**（不含 npm 与 JIT）；外部成本：Apple 99 美元 / 年，Windows 签名约 120 美元 / 年起 |
| 持续 | 每次依赖升级重跑元数据（约 0.5 h，由门兜底）；每次发布多约 10 min 墙钟 |
| 不建议：镜像内 JIT 的 PE 卫生 | 2–4 人日，单次 CLI 无收益 |

## 9. 阻塞项

- 无技术硬阻塞：macOS arm64 已端到端通过。
- 需要用户提供：Apple Developer ID 证书与公证凭据、Windows 签名证书或 Azure Trusted Signing 账号、Homebrew tap 仓库与发布 token（如要 npm，还需要 `@aster-cloud` scope 的发布权限，已有 org NPM_TOKEN）。
- 需要决策：Oracle GraalVM（GFTC）还是 CE；最低 glibc / 是否用 musl 静态；是否发 darwin-x64。
- 未实测：windows-x64、linux-x64（模拟太慢而中止）、darwin-x64，以及镜像内 JIT。linux-arm64 已实测（652/652）。

## 10. 复现

```sh
S=<scratchpad>
# 1 classpath：scratchpad/native/printcp.init.gradle → native/cp.txt；去掉 *-23.1.2 → native/cp-ni.txt
# 2 元数据：PATH=$S/native/shim-agent:$PATH node scripts/package-conformance.mjs …；native-image-configure generate → native/agent-merged；补 CoreModel → native/cfg
# 3 构建：$S/native/b2/build3.sh <outdir> [-R:StackSize=2m | -Os]
# 4 一致性：NATIVE_ASTER=<bin> PATH=$S/native/shim-native:/opt/homebrew/opt/node@24/bin:$PATH \
#     node scripts/package-conformance.mjs --node <ts aster.mjs> --jar $S/native/aster-cli-1.0.54.jar --concurrency 4 --strict-formats
# 5 测量：cd $S/native/w && python3 -I $S/native/bench/bench.py $S 10 out.json
```
