# ADR 0063 `aster` CLI 原生可执行文件——实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 aster-lang-truffle 的 JVM `aster` CLI 用 GraalVM Native Image 编成五个平台的原生可执行文件，作为安装渠道（GitHub Release、Homebrew）随列车发布；原生产物与 jar、Node CLI 进同一个 package-v1 一致性门；Truffle 的调用深度上限与 Node 一致。不改包格式，不进策略包。

**Architecture:** truffle `cli/` 子项目应用 `org.graalvm.buildtools.native`，以 runtimeClasspath 的分开 jar（不是 fat jar）构建；类初始化与运行参数写在各 jar 的 `META-INF/native-image/**/native-image.properties`，IR 模型类的反射元数据由构建任务按 classpath 生成，其余元数据来自 tracing agent。正确性只靠一致性门：truffle CI 的 `native` 矩阵（先非阻塞）跑原生冒烟、`ConformanceCasesTest` 的原生子进程模式与跨 CLI 的 `package-conformance.mjs --strict-formats`。发布阶段在 truffle `release.yml` 加原生矩阵，资产带 `.sha256` 与构建证明；签名 / 公证与 Homebrew 在用户给出凭据后接入。

**Tech Stack:** GraalVM 25（Native Image；CE 或 Oracle GraalVM 由 ADR Q1 定）、Native Build Tools Gradle 插件 0.11.3、Gradle 9 / JDK 25、Truffle 25.0.4（catalog `asterLibs.graalvm.*`）、GitHub Actions（`graalvm/setup-graalvm@v1`、`actions/attest-build-provenance`）、Node 24（aster-lang-test 一致性脚本）。

**Spec（按此顺序读）：**
- `docs/adr/0063-native-cli-distribution.md`（全部设计决定；本计划只写怎么做）
- `docs/superpowers/plans/2026-11-22-adr0063-native-spike-report.md`（spike 报告原文：测量、配置、阻塞项、成本；§4 配置与 §10 复现步骤是 T1 的来源）
- `docs/adr/0059-executable-policy-export.md` §4.3、§4.4、§5.3、§9、§13、§14；`docs/adr/0059-amendment-1-p0-findings.md` A3、A4；`docs/adr/0059-amendment-4-display-layout-in-packages.md` D4、D12
- `docs/adr/0053-amendment-2-release-requires-main-ci.md` §2（`.github/main-ci-gate.json` 与必需检查）
- truffle：`cli/build.gradle.kts`、`build.gradle.kts`、`.github/workflows/ci.yml` 与 `release.yml`、`src/main/java/aster/truffle/embed/SandboxedExecutor.java`、`src/main/java/aster/truffle/runtime/AllocationBudget.java`
- aster-lang-test：`scripts/package-conformance.mjs`、`scripts/build-package-fixtures.mjs`（`tsEval`、`--verify-engines`）、`conformance/package-v1/README.md`
- aster-lang-ts：`src/policy-package/evaluate-package.ts`（`CLI_MAX_CALL_DEPTH`）、`src/core/interpreter.ts`（`invokeFunc`、`applyClosure`）

---

## Global Constraints

- **ADR 硬门**：ADR 0063 为 PROPOSED。用户采纳前，T1、T2 的 PR 可评审、不合并；T6–T10 依赖 §10 的开放决策与用户提供的凭据，决策与凭据到位前不动。
- **不发布**：本计划的任何 PR 都不打 tag、不 dispatch 列车、不发 Release / npm / Homebrew。发布只经正常列车，且须用户确认（发布与部署动作一律先问）。
- **分支与工作树**：每个任务一个分支，从 `origin/main` 新开、不堆叠（aster-api 的堆叠 PR 陷阱见团队记录）；工作树放 `~/IdeaProjects/wt-native/<repo>`，不碰用户的主 checkout；不用裸 `git stash`；不跑 `./gradlew --stop`。
- **本地 Maven**：`~/.m2/repository/cloud/aster-lang` 必须保持不存在；未发布的生态制品放隔离仓，经 `GRADLE_OPTS=-Dmaven.repo.local=<隔离目录>` 使用。
- **GraalVM**：本地只经绝对路径或 `GRAALVM_HOME` 使用已装的 GraalVM 25，不改系统 JDK 设置。
- **一致性门是唯一判据**：任何影响原生产物的改动（依赖升级、元数据、构建参数、签名）都要在原生上跑 `nativeConformanceTest` 与 `package-conformance.mjs --strict-formats`，结果写进 PR。
- **协调**：Wave C parity 工作正在 core、ts、test 上进行。本计划不碰 parser 与 canonicalizer；test 仓只改 `package-conformance.mjs`、夹具生成器的复算部分与 `a-deep-recursion` 夹具。
- **提交**：提交说明结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 正文结尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`。代码注释与文档用简体中文。
- **评审**：每个 PR 用 Codex（`/Users/rpang/.claude/bin/codeagent-wrapper --backend codex`，ROLE_FILE `/Users/rpang/.claude/.ccg/prompts/codex/reviewer.md`）审查，迭代到综合评分 ≥ 90，且 CI 绿。

## Review Focus

1. **jar 不受影响**：SDK 对齐只把 `org.graalvm.sdk` 组从 23.1.2 拉回 catalog 版本；`./gradlew build` 的测试数与 fat jar 冒烟不变；jar 体积门（18 MiB）照旧。
2. **元数据完整**：IR 模型类按 classpath 生成，新增 IR 节点不需要手改；资源条目覆盖词法表、诊断、IR schema、`aster/cli/*.json`、`jsv-messages` bundle；没有宽匹配把整个依赖树的 JSON 拉进镜像。
3. **一致性门真的在跑原生**：`nativeConformanceTest` 的用例数下限（≥ 400、1.1 用例 ≥ 80）与 jar 门相同；跨 CLI 门的 jre 一侧确实是原生可执行文件（垫片或 T4 的参数）。
4. **深度上限与 Node 逐边界一致**：入口算第 1 层，函数与 lambda 各加 1，builtin 回调计入，程序外壳与 workflow 帧不计；超限消息相同；计数在异常路径上平衡。
5. **发布 fail-closed**：任一目标构建、冒烟、一致性、签名或回读失败，整个发布失败，不留残缺资产集。

---

## 已完成的任务（PR 已开，不依赖用户）

### Task 1：原生构建与原生一致性门（aster-cloud/aster-lang-truffle#201，分支 `feat/native-image-build`）

**Files:** `build.gradle.kts`、`cli/build.gradle.kts`、`cli/src/test/java/aster/cli/Fixtures.java`、`src/main/resources/META-INF/native-image/cloud.aster-lang/aster-lang-truffle/{native-image.properties,reachability-metadata.json}`（删除旧的五个文件）、`cli/src/main/resources/META-INF/native-image/cloud.aster-lang/aster-cli/{native-image.properties,reachability-metadata.json}`、`.github/workflows/ci.yml`、`README.md`

- [x] **Step 1: SDK 对齐**：根工程 `allprojects { configurations.configureEach { resolutionStrategy.eachDependency { … } } }` 把 `org.graalvm.sdk` 组统一到 catalog `graalvm`；`checkGraalvmVersions`（每个 java 项目一个，挂进 `check`）遍历 runtimeClasspath 的解析图，任一 `org.graalvm.*` 版本 ≠ catalog 即失败。反向验证：去掉规则后报出 `graal-sdk / nativeimage / word:23.1.2`。
- [x] **Step 2: 元数据**：删除根工程旧的 `reachability-metadata.json`、`resource-config.json`、`serialization-config.json`、`jni-config.json`、`proxy-config.json`；新建 `cloud.aster-lang/aster-lang-truffle/`（基础设施反射条目、Truffle 与 core 资源、类初始化策略 `--initialize-at-build-time=aster.truffle --initialize-at-run-time=aster.truffle.Loader,aster.truffle.embed`）；`generateNativeModelMetadata` 扫描 classpath，生成 67 + 68 + 3 个模型类型的全成员反射条目。`generateNativeConfig` 的 agent 输出改到 `build/native-agent`。
- [x] **Step 3: `cli` 原生构建**：`id("org.graalvm.buildtools.native")`；`graalvmNative`（`toolchainDetection=false` 取 `GRAALVM_HOME`、不用元数据仓库、`imageName=aster`、`--no-fallback`、`-PnativeMarch=…`）；CLI 元数据（agent 采集，去掉模型类与根工程已有条目）与 `native-image.properties`（`--enable-url-protocols=https -R:StackSize=2m`）；`nativeImageClasspath` 与 runtimeClasspath 同一组排除（不排除时拉进 Quarkus / netty，构建因 `org.jboss.logmanager.Logger` 进镜像堆而失败）。
- [x] **Step 4: 原生测试任务**：`Fixtures.run` 支持系统属性 `aster.cli.native`（子进程运行原生可执行文件）；`nativeSmoke`（原生 `version` 与 jar 逐字节相同）；`nativeConformanceTest`（只跑 `ConformanceCasesTest`，并发 4）。两者都不进 `check`。
- [x] **Step 5: CI**：`native` 矩阵 job（linux-x64 `ubuntu-24.04` + `-PnativeMarch=compatibility`、linux-arm64 `ubuntu-24.04-arm`、macos-arm64 `macos-15`、CE 对照 linux-x64-ce），步骤见 ADR §5；第二个 Gradle 调用加 `-x :cli:nativeCompile`（插件任务在第二次调用时不判 up-to-date，会整段重编）。非阻塞：不改分支保护与 `main-ci-gate.json`。
- [x] **Step 6: 本地验证**：`./gradlew build -PexcludeBenchmarks=true`（根 669、`:cli:test` 711 / 10 跳过、`shadowJarSmokeTest` 662，0 失败）；`nativeCompile` 70 s / 56.9 MB；`nativeSmoke` 通过；`nativeConformanceTest` 652/652；`package-conformance.mjs --strict-formats`（Node = ts 3.6.0，jre 侧经垫片为原生）652/652。

### Task 2：确定性调用深度上限（aster-cloud/aster-lang-truffle#202，分支 `feat/max-call-depth`）

**Files:** `src/main/java/aster/truffle/runtime/AllocationBudget.java`、`src/main/java/aster/truffle/runtime/CallDepthExceededError.java`（新）、`src/main/java/aster/truffle/nodes/LambdaRootNode.java`、`src/main/java/aster/truffle/embed/SandboxedExecutor.java`、测试 `CallDepthLimitTest`（新）、`SandboxedExecutorTest`、`cli/…/ShadowJarSmokeTest`

- [x] **Step 1:** `AllocationBudget` 的深度 ThreadLocal 增加调用帧槽位；`enterCallFrame` / `exitCallFrame`（`LambdaRootNode` 用）在已达 `MAX_CALL_DEPTH = 10_000` 时先抛 `CallDepthExceededError`、不改计数；`enterFrame` / `exitFrame`（`AsterRootNode`、`WorkflowNode`、`StartNode`）不计调用深度；最外层退出时两个计数一起清零。
- [x] **Step 2:** `CallDepthExceededError extends StackOverflowError`，消息 `Maximum call depth (10000) exceeded`，不填栈；`SandboxedExecutor` 的求值线程栈 `EVAL_THREAD_STACK_BYTES = 64 MiB`，`StackOverflowError` 路径对它保留原消息。
- [x] **Step 3:** 边界测试逐个对照 ts 3.6.0 实测（Worker `stackSizeMb: 32`、`maxCallDepth: 10000`）：`deep` 9 999 / 10 000，lambda 递归 4 999 / 5 000，`List.map` 回调递归 4 999 / 5 000，无参入口 `deep(9998)` / `deep(9999)`；`java -jar`（缺省 `-Xss`）9 999 成功、10 000 退出码 5 且 stderr 含消息。
- [x] **Step 4:** 与 T1 合并后本地构建原生：9 999 成功、10 000 `RESOURCE_EXHAUSTED`（同消息），原生一致性 652/652。

---

## 待办任务（不依赖用户）

### Task 3：aster-lang-runtime 不再在发布元数据里泄漏 `enforcedPlatform(quarkus-bom)`

**Files:** aster-lang-runtime `build.gradle.kts`（`enforcedPlatform` 改为只作用于本仓自己的编译 / 测试配置，或改 `platform(…)`）；truffle 无改动（T1 的对齐规则随之成为空操作，保留为防线）。

- [ ] **Step 1:** 在 runtime 发布的 `.module` / `.pom` 里确认 `quarkus-bom` 的 `enforced-platform` 依赖（truffle `:cli:dependencyInsight --dependency org.graalvm.sdk:graal-sdk` 显示 `By constraint, Forced`）。
- [ ] **Step 2:** 改为不随发布元数据传递；`./gradlew publishToMavenLocal`（隔离仓）后在 truffle 去掉对齐规则跑 `checkGraalvmVersions`，应通过；恢复规则。
- [ ] **Step 3:** aster-api 的依赖图对比（`./gradlew dependencies --configuration runtimeClasspath` 前后 diff），确认只有预期的版本变化。PR 随下一趟列车发布。

### Task 4：`package-conformance.mjs` 的原生侧（aster-lang-test）

**Files:** `scripts/package-conformance.mjs`、`.github/workflows/ci.yml`（`package-conformance` job）；truffle `.github/workflows/ci.yml`（去掉垫片）

- [ ] **Step 1:** 新增 `--native <exe>`：作为第三侧运行（`SIDES.native = (args) => [exe, args]`），比较规则与 jre 侧相同：三侧归一后 stdout 与退出码两两相同，且各自等于期望；`knownEngineDivergence` 的 `jre` 标记同样适用于原生（同一引擎）。不给 `--native` 时行为逐字节不变。
- [ ] **Step 2:** 报告增加原生侧耗时列；用例数下限不变。
- [ ] **Step 3:** test 仓 `package-conformance` job 在 linux-x64 上构建原生（`graalvm/setup-graalvm@v1` + `./gradlew :cli:nativeCompile`）并传 `--native`；先不进 required check（与 truffle 一致），T7 时一起升级。
- [ ] **Step 4:** truffle `native` job 改用 `--native`，删除 `java` 垫片。

### Task 5：Windows x64 的非阻塞 CI 实测

**Files:** truffle `.github/workflows/ci.yml`

- [ ] **Step 1:** 矩阵加 `windows-x64`（`windows-2025`）：`nativeCompile` 产出 `aster.exe`；shell 用 `bash`（Git Bash）运行现有步骤；垫片换成 T4 的 `--native`（T4 之前此任务不做）。
- [ ] **Step 2:** 记录时长、体积、一致性；Windows 特有失败（路径分隔、CRLF、临时文件锁）逐条修，不加平台例外。

### Task 7：`a-deep-recursion` 边界夹具与生成器复算（aster-lang-test）

**Files:** `conformance/package-v1/src/a-deep-recursion/{fixture.json,expected.json}`、`scripts/build-package-fixtures.mjs`（`tsEval`）、生成的 `packages/a-deep-recursion*`

- [ ] **Step 1:** `tsEval` 改为与 CLI 同口径：`maxCallDepth: 10_000`，深递归用例在 `worker_threads` Worker（`resourceLimits.stackSizeMb = 32`）里求值（与 ts `evaluate-package.ts` 相同）；其余用例结果不变（`--verify-engines` 全绿）。
- [ ] **Step 2:** `fixture.json` 增加 `depth-9999`（`expect: "ts"`，期望 `9999`）与 `depth-10000`（`expect: {code: "RESOURCE_EXHAUSTED"}`）；修正描述中「JVM 在缺省线程栈约 2 000 层溢出」为 ADR 0063 §7 的现行规则；`node scripts/build-package-fixtures.mjs` 重新生成，`--check` 与 `--verify-engines` 通过。
- [ ] **Step 3: 原生覆盖**：夹具进 `packages/a-deep-recursion.cases.json` 后由 `ConformanceCasesTest` 读取，所以 truffle `native` 矩阵（linux-x64、linux-arm64、macos-arm64；T5 之后含 windows-x64）的 `nativeConformanceTest` 与 T6 发布矩阵（发布门）都会在每个原生目标上跑到 `depth-9999` / `depth-10000`；在 PR 里贴出各目标的结果，作为 ADR §7 中 Linux / Windows 原生深度一致性的证据。
- [ ] **Step 4:** 合并顺序：truffle#202 先合入并随列车发布，再合本任务（test 仓的跨 CLI 门用 truffle main 的 jar；在此之前 `depth-9999` 在旧 jar 上会失败）。同名分支 `feat/max-call-depth` 让两边 PR 的 CI 互相取到对方。

---

## 依赖用户决策或凭据的任务

### Task 6：发布矩阵与必需检查（需要 ADR Q1、Q2、Q4、Q7；签名平台另需 T8）

**Files:** truffle `.github/workflows/release.yml`、`.github/main-ci-gate.json`；分支保护（用户在 GitHub 设置，或授权 `gh api` 修改）

- [ ] **Step 0（尚未完成，是 Q1 的决策输入）:** 在 CI 里对 CE 与 Oracle GraalVM 的 linux-x64 产物各测 10 次 `run`（jingyesi 包）的中位数，写回「CI 基线」，供 Q1 定稿。现有 CE 数据只有构建时长、体积与一致性。
- [ ] **Step 1:** `release.yml` 新增 `native` 矩阵 job（五个目标；x64 加 `-PnativeMarch=compatibility`；发行版按 Q1），与 `publish` 同受 `require-main-ci-green` 门禁。每个目标：`nativeCompile` → `nativeSmoke` → `nativeConformanceTest` → jingyesi `verify --recompile --probes` 冒烟 → 打包 `aster-<ver>-<os>-<arch>.tar.gz` / `.zip`（含 `LICENSE`、`NOTICE`、第三方许可）→ GNU 格式 `.sha256` → `actions/attest-build-provenance`（`permissions: id-token: write, attestations: write`）→ `actions/upload-artifact`（只存为 workflow artifact，不碰 Release）。
- [ ] **Step 2: 汇总上传（fail-closed）**：每个原生目标 job 的构建、冒烟、一致性对所有目标都是门；签名与「产出上传 artifact」两步只对 `NATIVE_RELEASE_TARGETS`（仓库变量，列出要发布的目标；签名平台须同时有凭据，见 T8）里的目标执行，未列入的目标在一致性通过后即以成功结束。新 job `publish-native`，`needs` 全部原生目标与 `publish`；只在它们都成功时运行，下载 artifact，核对恰好覆盖 `NATIVE_RELEASE_TARGETS`，一次性 `gh release upload --clobber`，再下载回读 `sha256sum -c`。任一目标失败则一个原生资产都不上传；发布 run 的结论以 `publish` 与 `publish-native` 都成功为准。jar 的 `publish` 先于原生上传完成这一点不变（jar 是既有渠道，原生失败不回滚 jar，但 run 判失败并告警）。
- [ ] **Step 3:** 把 `native (linux-x64)` 加进 truffle 分支保护必需检查与 `.github/main-ci-gate.json` 的 `requiredJobs`（ADR 0053 A2：两者必须一致）；其余平台只在发布 run 里跑。
- [ ] **Step 4:** 用 `dryRun` 列车验证一次（不发布）；需用户确认后才跑真实列车。

### Task 8：macOS 签名与公证、Windows 签名（需要 ADR Q3、Q7 与 §6.3 的凭据）

**Files:** truffle `.github/workflows/release.yml`

- [ ] **Step 1（macOS）:** 用 `APPLE_DEVELOPER_ID_P12_BASE64` / `_PASSWORD` 建临时 keychain；`codesign --force --options runtime --timestamp --sign "Developer ID Application: … (TEAM_ID)" aster`；zip 后 `xcrun notarytool submit --wait --key <p8> --key-id … --issuer …`；`spctl --assess --type execute`（裸可执行文件不能 staple，记录公证 ID）。签名后的二进制重跑 `nativeConformanceTest`。
- [ ] **Step 2（Windows）:** Azure Trusted Signing（`azure/trusted-signing-action`，输入取 ADR §6.3 的 `AZURE_*` 变量与 secret）签 `aster.exe` 并加时间戳；`signtool verify /pa`。若用户选 OV / EV 证书，先按服务商定变量名并写回 ADR §6.3，再实现。
- [ ] **Step 3: 签名是硬前提**：目标在 `NATIVE_RELEASE_TARGETS` 里而凭据缺失或签名失败 → 该目标 job 失败 → `publish-native` 不运行，整个原生资产集不发布。目标不在 `NATIVE_RELEASE_TARGETS` 里 → 照常构建与测试，跳过签名步骤，不进上传集合。没有「未签名发布」的分支。

### Task 9：Homebrew tap 与 `install.sh`（需要 ADR Q5）

**Files:** 新仓 `aster-cloud/homebrew-tap`（`Formula/aster.rb`）；aster-lang-platform 列车脚本（最后一步发 bump PR）；truffle `scripts/install.sh`（随 Release 上传）

- [ ] **Step 1:** formula：`on_macos` / `on_linux` × `on_arm` / `on_intel` 指向 Release 资产与 `.sha256`；`test do` 跑 `aster version`。
- [ ] **Step 2:** 列车在 L4 之后用 `HOMEBREW_TAP_TOKEN` 开 bump PR（或 `brew bump-formula-pr`），CI 在 tap 仓跑 `brew install --build-from-source` 与 `brew test`。
- [ ] **Step 3:** `install.sh`：检测 OS / 架构，下载资产与 `.sha256` 校验后安装到 `~/.local/bin` 或 `/usr/local/bin`；`shellcheck` 通过。

### Task 10：B 包 README 的安装指引（aster-api）

**Files:** aster-api 导出 README 模板（`policy_export` 的 README 生成处）、对应单元测试与可复现性测试的期望

- [ ] **Step 1:** JRE B 包 README 增加「或安装原生 `aster`：`brew install aster-cloud/tap/aster`，或从 Release 下载对应平台的资产并核对 `.sha256`」；Node B 包不变。四语（hi 用英文）。
- [ ] **Step 2:** README 进 `files[]` 与签名覆盖，改动会改变 manifest 字节：同步可复现性测试的期望，随列车发布。

### Task 11（可选，ADR Q6）：npm 平台包

不在本计划范围，Q6 选「做」时另写计划。

---

## CI 基线（truffle#201，GitHub 托管 runner）

| 目标 | runner | 发行版 | `nativeCompile` | 体积 | 最低 glibc | `nativeConformanceTest` | 跨 CLI（Node ↔ 原生） | job 总时长 |
|---|---|---|---|---|---|---|---|---|
| linux-x64 | `ubuntu-24.04` | Oracle GraalVM 25 | 174 s（`Finished generating` 2 m 44 s，峰值 RSS 3.63 GB） | 57.7 MB | 2.34 | 652/652 | 652/652 | 5 m 36 s |
| linux-arm64 | `ubuntu-24.04-arm` | Oracle GraalVM 25 | 218 s（3 m 22 s，3.15 GB） | 58.8 MB | 2.34 | 652/652 | 652/652 | 6 m 44 s |
| macos-arm64 | `macos-15` | Oracle GraalVM 25 | 434 s（6 m 10 s，2.25 GB） | 56.8 MB | — | 652/652 | 652/652 | 13 m 31 s |
| linux-x64（CE 对照） | `ubuntu-24.04` | GraalVM CE 25 | 167 s（2 m 31 s，2.69 GB） | 58.0 MB | 2.34 | 652/652 | 652/652 | 6 m 38 s |

数据来自 2026-11-21 的 run 44947933068（truffle#201 第三次推送 `7cae5d8`）；`nativeCompile` 含 Gradle 配置与依赖编译，括号内为 native-image 自身的生成时长与峰值 RSS。全部四条腿都是 `success`；`build` job 同一 run 绿。
首次推送（`febda5d`）的 linux-x64 为 210 s / 57.7 MB / 652/652，linux-arm64 同样通过；`macos-14` 标签已不受支持，改为 `macos-15`。
CE 与 Oracle GraalVM 在 linux-x64 上体积相差 +0.3 MB、构建时长相当、一致性相同；CE 的启动时间尚未单独测量（T6 Step 0）。

本地（macOS arm64，10 核，Oracle GraalVM 25+37.1）：`nativeCompile` 70–80 s、峰值 RSS 3.4–3.9 GB、56.9 MB。

## 后续（不在本计划内）

| # | 事项 | 依据 |
|---|---|---|
| F1 | 若客户要「无 JRE、无 Node 的单平台离线包」：ADR 0059 修订，manifest `target: native-<os>-<arch>`、单独体积预算、锁文件按平台 | ADR 0063 §8 |
| F2 | `aster version` 增加 `;dist=native-<os>-<arch>` 区分来源（改 `version` 契约，需修订） | ADR 0063 §3 |
| F3 | 镜像内 JIT 的 PE 卫生（24 处黑名单违例） | ADR 0063 §9，只在出现长时间求值 / 批量模式时再评估 |
| F4 | aster-lang-core 在自己的 jar 里发布 `aster.core.ir.CoreModel$*` 的原生元数据（今天由 truffle 生成） | ADR 0063 §5 |

## 本地验证命令汇总

| 仓 | 命令 |
|---|---|
| aster-lang-truffle | `GRADLE_OPTS=-Dmaven.repo.local=<隔离仓> ./gradlew build -PexcludeBenchmarks=true`（记执行数）；`GRAALVM_HOME=<graalvm-25> ./gradlew :cli:nativeCompile :cli:nativeSmoke :cli:nativeConformanceTest` |
| aster-lang-test（T4 之前） | `PATH=<java 垫片目录>:$PATH NATIVE_ASTER=<exe> node scripts/package-conformance.mjs --node <aster.mjs> --jar <aster-cli.jar> --concurrency 4 --strict-formats`（垫片把 `java -jar <jar> …` 换成原生可执行文件，见 truffle CI） |
| aster-lang-test（T4 之后） | `node scripts/package-conformance.mjs --node <aster.mjs> --jar <aster-cli.jar> --native <exe> --concurrency 4 --strict-formats` |
| aster-lang-test（两者都要） | `node scripts/test-package-v1.mjs`；`node scripts/build-package-fixtures.mjs --check`、`--verify-engines` |
| aster-lang-ts | 深度边界对照：Worker（`resourceLimits.stackSizeMb = 32`）里 `evaluate(readCoreIr(core), entry, ctx, {maxSteps: Number.MAX_SAFE_INTEGER, maxCallDepth: 10000})` |
| aster-lang-runtime | `./gradlew publishToMavenLocal`（隔离仓）后在 truffle 跑 `checkGraalvmVersions` |
