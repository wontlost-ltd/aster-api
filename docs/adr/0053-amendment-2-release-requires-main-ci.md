# ADR 0053 修订 A2：发布 run 不再重跑测试，改为要求 main CI 已对发布的 commit 通过

- 状态：**PROPOSED**（2026-11-16）
- 日期：2026-11-16
- 修订对象：[ADR 0053](0053-release-train-artifact-reuse.md) §1.1（引擎仓发布 run 的现状）、§11 第 2 条（「ts 发布复用 main CI 结果」被否决）、§13（迁移）；[修订 A1](0053-amendment-1-train-owns-api-pin.md) 不变
- 相关：ADR 0023（发布列车）、aster-lang-ts#318 / #319（ts 2.0.0 发布 run 因测试环境两次失败）、eco-1.0.47（run 43648121415）、eco-1.0.48（run 43803509463）
- 实现落点：`aster-lang-platform`：新增 `.github/actions/require-main-ci-green`、`.github/actions/verify-jar-contents`、`scripts/release-plan/preflight-main-ci.sh`，改 `release-train.yml`、`run-train.sh`、`release.yml`；八个引擎仓（core、runtime、validation、test、locales、hi、truffle、ts）：改 release workflow，各加 `.github/main-ci-gate.json`；`aster-api`：本文档；可选的 CI 并行拆分见 §8

---

## 1. 问题与实测

ADR 0053 §1.1 记录过：「Java 引擎仓的发布 run 已经是只 assemble，测试在 PR CI 跑过」。逐仓核对后，这句话只对 core 成立，而且只是约定，没有任何东西强制它。

### 1.1 发布 run 里的测试（eco-1.0.47 / 1.0.48，`gh api …/actions/runs/<id>/jobs` 的步骤时长）

| 仓 | 发布 run 里的测试 | 1.0.47 | 1.0.48 | publish job 合计（1.0.48） |
|---|---|---|---|---|
| platform | 无（catalog） | — | — | 37s |
| core | 无（`build -x test`，hi 前向依赖） | — | — | 120s |
| runtime | `./gradlew build` | 71s | 69s | 120s |
| validation | `./gradlew build` | 6s | 9s | 64s |
| test（JVM / npm） | 无 | — | — | 67s / 30s |
| locales | `./gradlew build`（三个模块） | 15s | 15s | 126s |
| hi | `./gradlew build verifyLexiconKeywordParity` | 5s | 5s | 62s |
| truffle | `./gradlew build -PexcludeBenchmarks=true` | 10s | 9s | 92s |
| ts | `ci` job（typecheck、lint、单测、golden）+ `prepublishOnly` 再跑一遍单测与集成测试 | ci 75s；publish 中约 50s | ci 105s；publish 中约 55s | ci 105s + publish 129s |

validation、hi 的测试本来就少（Gradle 日志 `7 actionable tasks: 7 executed`，`:test` 两秒内结束）。真正花时间的只有 runtime 与 ts。

### 1.2 比时长更要紧的两件事

1. **发出去的 commit，main CI 是红的。** 查发布 tag 指向的 commit 在 main 上的 `ci.yml` push run：

   | 列车 | core | test | 其余引擎仓 |
   |---|---|---|---|
   | 1.0.46 | cancelled（attempt 2） | success | success（truffle 为 attempt 2） |
   | 1.0.47 | **failure** | **failure** | success |
   | 1.0.48 | **failure** | **failure**（parity job） | success |

   失败原因都是同一个：生态 lockstep 的 pin PR 逐仓依次合并，先合的仓的 main CI 检出后合仓的旧 main，于是找不到旧版本的 catalog（core 1.0.48 run 43802427901：`Could not find cloud.aster-lang:aster-lang-platform:1.0.47`，因为 locales main 的 pin 在 33 秒后才 bump）。所有仓都合完之后重跑一次就会绿，但没有人重跑，列车也不检查，core 与 test 就这样用红 CI 的 commit 连续发了两版。core 发布 run 的注释写着「测试已在 PR CI 阶段跑过」，这个前提从未被验证过。
2. **发布 run 自己的测试环境会坏，而且只在发版时坏。** ts 2.0.0（eco-1.0.46）的发布 run 两次失败：第一次单测读不到 aster-lang-test 的语料 loader（#318），第二次读不到 aster-lang-locales 的 Core IR schema（#319）。两次都是 `ci.yml` 已经补上、`release.yml` 漏改；每次都要删 tag、修 workflow、`fromLayer=4` 续跑。发布 run 里维护一份测试环境副本，本身就是故障源。

ADR 0053 §11 第 2 条否决「ts 发布复用 main CI 结果」，理由是墙钟收益为 0（ts 被 L4 的 aster-api 遮住）。这个理由今天仍然成立（§7），但它只算了时间。上面两件事说明，按 commit 校验 main CI 是正确性要求，不是提速手段。

## 2. 决策：发布前要求 main CI 对该 commit 已通过

每个引擎仓的 tag-push 发布 job 在构建前调用共享 action `aster-cloud/aster-lang-platform/.github/actions/require-main-ci-green@main`。判定逻辑只有一份（`require-main-ci-green.sh`），各仓只提供配置 `.github/main-ci-gate.json`：

```json
{ "workflow": "ci.yml", "requiredJobs": ["build"] }
```

### 2.1 「main CI 对 SHA X 通过」的定义

全部成立才放行，任何一条不满足就失败（fail-closed）：

1. **找 run**：`GET /repos/{repo}/actions/workflows/{workflow}/runs?head_sha=X&event=push&branch=main`，再在本地过滤 `head_sha == X`、`event == push`、`head_branch == main`、`path == .github/workflows/{workflow}`、`head_repository == 本仓`。PR run、fork、别的分支、别的 workflow 一律不算。
2. **只看最新的 run**：多个匹配时取 id 最大的那个，不在历史 run 里挑一个绿的。
3. **run 已结束**：`status == completed`。未结束（包括正在重跑）就等，有界（缺省 30 分钟，每 20 秒查一次）。
4. **只看最新 attempt 的 job**：`GET /actions/runs/{id}/jobs?filter=latest`。配置里的每个必需 job 必须**恰好出现一次**且 `conclusion == success`。`skipped`、`neutral`、`cancelled`、`failure`、`timed_out`、缺失、重名都不算通过。
5. **必需 job 等于分支保护的必需检查**：

   | 仓 | requiredJobs |
   |---|---|
   | platform、core、runtime、validation、locales、hi、truffle | `build` |
   | test | `Build & test (Node)`、`Build & test (JDK)` |
   | ts | `test (24)` |

   核对方式：`gh api repos/aster-cloud/<repo>/branches/main/protection/required_status_checks -q .contexts`。分支保护之外的 job（core、test、ts 的 `Strict tier1 parity`、test 的 `Trigger consumer regression`）失败只告警，不拦发布，因为它们本来也不拦合并。改 `ci.yml` 的 job 名时必须同步改这个文件；漏改的后果是下一次列车在 preflight 报「缺失」，而不是发出错误制品。
6. **SHA 不在 main 上立即拦**：找不到 run 时先查 `compare/main...X`。`ahead` 或 `diverged`（不是 main 的祖先）→ 立即失败；`identical` 或 `behind` → 等 run 出现（缺省 5 分钟）；compare 查询失败 → 按「还没出现」处理，同样有界。
7. **重跑与 flake**：GitHub 的重跑沿用同一个 run id，`run_attempt` 递增，所以第 4 条「最新 attempt」自然覆盖两种情况：flake 后重跑变绿就放行，重跑变红就拦。发布 job 里的门禁只读，不会自己重跑。

令牌：发布 job 用自带的 `GITHUB_TOKEN`，权限加 `actions: read`（读 run 与 job），`contents: read`（读 tag 与 compare）本来就有。不需要新的 secret。

### 2.2 列车 preflight 提前统一检查，并自动重跑一次

发布 job 里的门禁是最后一道线，但它在 L1 才生效，那时 platform 已经发布。所以 `release-train.yml` 的 preflight 在 drift gate 之后增加一步 `preflight-main-ci.sh`：对每个有发布 step 的仓（service 除外），用 preflight 记录的 main SHA 调同一个门禁脚本，各仓并行。任一仓不绿，列车停在 L0 之前，一个制品都不发。

非 dryRun 时，preflight 对必需 job 未通过、且 `run_attempt == 1` 的 run 执行一次 `gh run rerun --failed`，然后继续等。理由就是 §1.2 第 1 条的 pin 竞态：开车时各仓 main 都已就位，重跑失败的 job 即可变绿，真正的问题会再红一次。次数记在 GitHub 上（只重跑 attempt 1），所以 `fromLayer` 续跑不会重复重跑。preflight 用的是 `CROSS_REPO_TOKEN`，它已有 Actions 读写权限。dryRun 只读，红了就报。

重跑还有一个附带效果：重跑发生在 preflight 记录 main SHA 之后，stale gate 保证这些 SHA 在列车途中不变，所以重跑的 job 检出的兄弟仓 main 正是随后要打 tag 发布的那些 commit。

### 2.3 tag-push 与列车 dispatch 走同一道门

列车 dispatch 的 `create-tag` job 只建 tag，真正发布的永远是 tag-push 触发的 publish job（ADR 0023 阶段 2）。门禁放在 publish job 里，所以人工推 tag 与列车建 tag 经过的是同一段代码，没有第二条路径。人工给一个不在 main 上的 commit 打 tag，会被 §2.1 第 6 条立即拦下。

门禁先解析本次的 tag：它必须指向要发布的 commit（`github.sha` 等于 tag 指向的 commit 或 annotated tag 对象本身，统一归一到 commit），否则拦下。

### 2.4 逃生口 forceTests

仿照 ADR 0053 §5 的 `forceRebuild`：

- `release-train.yml` 新增输入 `forceTests`（boolean，缺省 false）。为 true 时，`run-train.sh` 给各仓 dispatch 加 `-f forceTests=true`。只在 true 时才传，因为没有这个输入的老 workflow 会拒绝 dispatch。
- 各仓 `create-tag` 收到 `forceTests=true` 时打 **annotated tag**，消息含一行 `release-gate: force-tests`；否则仍打 lightweight tag，与今天相同。
- publish job 的门禁读到这个标记（或 action 输入 `force-tests: true`）就跳过 main CI 检查，输出 `force-tests=true`，本 run 跑完整测试（§3 的「force-tests 时」一列）。人工发版用 `git tag -a vX -m 'release-gate: force-tests'`。
- preflight 在 `forceTests=true` 时不查 main CI，但要求每个参与仓都已经有 `.github/main-ci-gate.json`，即已经接入、workflow 认得 `forceTests` 输入；否则在 L0 之前拦下。

forceTests 是给「main CI 因外部原因暂时无法变绿、但必须发版」准备的，不是常规路径。每次使用都会在 job summary 与 `::warning::` 里留痕。

## 3. 发布时还跑什么

| 仓 | 去掉 | 保留 | 新增 | force-tests 时 |
|---|---|---|---|---|
| platform | — | 版本 ↔ tag 守卫、`publish` | 门禁 | 只跳过门禁（catalog 没有测试） |
| core | —（本来就 `-x test`） | 兄弟仓 `publishToMavenLocal`、版本守卫、`build -x test`、`publish` | 门禁、jar 冒烟 | 检出 hi、`publishToMavenLocal`，再 `./gradlew test`（与 main CI 的 build job 相同） |
| runtime、validation | `build` 里的 `test` | 版本守卫、`build -x test`（check 中其余任务照跑）、`publish` | 门禁、jar 冒烟 | `./gradlew build` |
| test JVM | — | 版本匹配判定（不符即跳过）、`publish` | 门禁、jar 冒烟 | `./gradlew test` |
| test npm | — | 版本匹配判定、`pnpm build`、`publish` | 门禁 | `pnpm test` |
| locales | 三个模块的 `test` | 版本守卫、`build -x test`：`verifyLexiconParity`、`verifyLexiconKeywordParity`、`verifyUiMessagesParity`、`verifyControlsFixtures`、`verifyCoreIrSchema`、`verifyTypeInferenceRules`、`verifyErrorCodesBaseline` 照跑，它们针对本次 tag 的兄弟仓源码校验制品内容；逐包 `publish`；ui-messages npm | 门禁、三个主 jar 冒烟 | `./gradlew build` |
| hi | `test` | `build verifyLexiconKeywordParity -x test`（`verifyUiMessagesParity`、`verifyErrorCodesBaseline` 照跑）、`publish`、ui-messages-hi npm | 门禁、版本 ↔ tag 守卫（此前 tag-push 路径没有）、jar 冒烟 | 含 `test` 的原命令 |
| truffle | `test` | `build -PexcludeBenchmarks=true -x test`、`publish` | 门禁、版本 ↔ tag 守卫（此前没有）、jar 冒烟 | 含 `test` 的原命令 |
| ts | `ci` job 的 typecheck / lint / 单测 / golden；`prepublishOnly` 里的 `pnpm run test`；publish job 中只为测试服务的 core / test / locales 检出与语料 loader 构建 | `clean → build`、版本 ↔ tag 守卫、`verify-browser-entry.mjs`（browser 入口不含 Node 内置模块）、Trusted Publisher 发布、GitHub Release | 门禁（独立 `gate` job）、`scripts/verify-package.mjs` 发布包冒烟（`prepublishOnly` 与 `ci.yml` 都跑） | `full-tests` job，内容即原 `ci` job |

**jar 冒烟**（共享 action `verify-jar-contents`，几秒钟）：主 jar 存在（忽略 `-sources`、`-javadoc`、`-plain`）、文件名以 `-<tag 版本>.jar` 结尾、含必需条目。各仓的条目：core `aster/core/**.class`、`governance/controls-registry.json`、`type-inference/naming-rules.json`；runtime `aster/runtime/**.class`、`META-INF/beans.xml`（Quarkus bean 发现）；validation `io/aster/**.class`；locales 与 hi `META-INF/services/`、`aster/lang/**.class`；truffle `aster/truffle/**.class`、`META-INF/services/`、`META-INF/native-image/`；test `corpus/tier1-equivalence/`、`cloud/aster/**.class`。测试跑的是 class 目录，从不检查 jar，所以这是新增的覆盖，不是替代。

**ts 发布包冒烟**（`scripts/verify-package.mjs`）：`npm pack --ignore-scripts` 打出与发布相同的 tarball，检查四件事：`main`、`types`、`bin` 与每个 `exports` 条件指向的文件都在包里；包内每个 `.js` 的相对 import（静态与动态）都解析到包内文件，不允许引用包外路径（ts 2.0.0 那类「依赖兄弟仓的语料 loader」若进了制品会在这里失败）；在仓外临时目录解开 tarball，逐个 `import()` 无副作用的 `exports` 入口；`./cli`、`./core`、`./lsp/server` 是可执行入口，只查文件存在与相对 import。新的 `prepublishOnly` 在无兄弟仓的隔离副本里实测约 12 秒，原来的 `clean → build → test` 约 60 秒。

## 4. 剩余风险

- **兄弟仓输入不同**。main CI 检出的是它运行时各兄弟仓的 main；发布 run 按 `checkout-sibling` 规则 1 检出兄弟仓的同名 tag。两者只有在兄弟仓自那以后没有新合入时才一致。preflight 的重跑（§2.2）在不一致最常见的场景（pin 竞态）下消除了差异，但一个早就绿了的 run 不会被重跑。被否决的「新鲜度」规则见 §9。兜底有三层：locales / hi 的 `verify*` 任务在发布 run 里仍按 tag 源码校验；L4 的 aster-api pin PR 用全部七个引擎仓的 `vX` tag 跑完整 `ci-result` 与 `Guard bypass suite (#466)`，它是 aster-api 对新引擎版本的唯一测试，也是规则集的必需检查，本修订不动它；各仓的 tier1 parity job 在 push main 时全量运行。
- **发现问题的时间点后移**。过去 runtime 的测试若在发布 run 里失败，runtime 不会发布（但同层的 core 已经发了）。现在同类问题要么在 preflight 就被拦下（main CI 红），要么只能在 L4 被发现，而那时引擎制品已全部发布（forward-only，ADR 0023）。过去发布 run 里的失败同样只能停车、不能回收，所以这不是新增的不可逆性。
- **preflight 变慢**。需要重跑时，preflight 要多等最慢那个仓的重跑（core 的 build job 约 1–2 分钟，ts 的 test (24) 约 3–5 分钟）。在 1.0.47 与 1.0.48 中都会发生。
- **配置与 job 名漂移**。改了 `ci.yml` 的 job 名却没改 `.github/main-ci-gate.json`，preflight 会报「缺失」并停车。失败方向是安全的。

## 5. 与 ADR 0053 其余部分的关系

- §7 stale gate 不变。它保证发布的 commit 就是 preflight 验过的 main tip，本修订再要求这个 tip 的 main CI 是绿的。两者合起来才构成「发布的是已验证的 commit」。
- §11 第 2 条改为：ts 的发布 run 按 commit 复用 main CI 结果。理由不再是墙钟时间，而是 §1.2 的正确性问题。墙钟时间的判断仍然成立（§7）。
- 修订 A1 不变：aster-api 仍是 service，L4 的 pin PR 与 deploy 照旧，不经过本门禁。

## 6. 实现落点与测试

| 位置 | 内容 | 测试 |
|---|---|---|
| platform `.github/actions/require-main-ci-green/` | `action.yml` + `require-main-ci-green.sh`（§2.1、§2.3、§2.4） | `require-main-ci-green.test.sh`：假 `gh` 驱动 30 多个用例，覆盖必需 job skipped / 缺失 / 重名、非必需 job 只告警、等待与超时、SHA 不在 main（ahead / diverged）、最新 run 优先、过滤 PR / 分支 / fork / 别的 workflow、重跑一次及重跑后 API 仍返回旧 attempt 的竞态、annotated / lightweight tag、tag 指向别的 commit、配置 fail-closed |
| platform `.github/actions/verify-jar-contents/` | jar 冒烟 | `verify-jar-contents.test.sh`（python zipfile 造 jar） |
| platform `scripts/release-plan/preflight-main-ci.sh` | §2.2 | `preflight-main-ci.test.sh`：service 不查、未接入仓不拦、任一仓红即停、非 dryRun 才重跑、竞态重跑后变绿、forceTests 遇未接入仓拦、缺 SHA / 非法 LAYERS fail-closed |
| platform `release-train.yml`、`run-train.sh` | `forceTests` 输入与透传 | `train-reuse.test.sh` 新增：只在开启时 dispatch 带 `-f forceTests=true` |
| 各引擎仓 release workflow | §3 | 本地构建 `-x test` 并对产物跑 jar 冒烟；ts 在隔离副本跑新 `prepublishOnly`，并做变异验证（删 exports 目标、加包外 import、入口抛错各自被拦） |

## 7. 预期收益（按 1.0.47 / 1.0.48 实测估算）

各层 step 并行（ADR 0053 §2），墙钟由每层最慢的 step 决定。门禁本身约 5 秒（两到四次 API 调用），jar 冒烟约 2 秒。

| 层 | 1.0.48 关键路径 | 本修订之后 | 墙钟变化 |
|---|---|---|---|
| L0 platform | 37s | +5s（门禁） | +5s |
| L1 | core 120s ≈ runtime 120s | runtime 降到约 60s（69s 测试换成约 10s 的 `build -x test`），关键路径变成 core 约 127s | 约 +7s |
| L2 | locales 126s | 约 −15s 测试，+7s 门禁与冒烟 | 约 −8s |
| L3 | truffle 92s | 约 −9s，+7s | 约 −2s |
| L4 | aster-api（pin PR CI 约 36–40 分钟 + deploy） | ts 从约 234s 降到约 90s，但仍被 aster-api 遮住 | 0 |
| preflight | 16s | 全绿时 +5s；需要重跑时 +1.5–5 分钟 | +5s 至 +5 分钟 |

结论：**L0–L3 的墙钟基本不变**（±10 秒），runner 用量每趟列车约省 3.5 分钟（ts 约 2.4 分钟、runtime 约 1 分钟，减去十个门禁步骤约 1 分钟）。墙钟上真正有分量的是：

1. 不再出现 ts 2.0.0 那样只在发布 run 里坏的测试环境。那一次两轮修复加续跑，耗时以小时计。
2. 列车不再发出 main CI 是红的 commit（1.0.47、1.0.48 的 core 与 test）。

若要缩短墙钟，应缩短 L4 的关键路径，见 §8 与 §10。

## 8. 可选：aster-api CI 拆成并行 job（单独 PR，不改语义）

L4 关键路径的第一段是 pin PR 的 CI。`ci-build.yml` 的唯一 job 串行运行三套测试，托管 runner 实测：单元 `test` 12–15 分钟，`integrationTest` 10–12 分钟，`guardBypassTest`（含首次编译）8–10 分钟，合计 24–38.6 分钟（ci-build.yml 的 timeout 注释）。拆成并行 job：

- `checks`：pin 一致性、image-pin 门控测试、actionlint、Quarkus plugin/BOM 锁步（原来的前几步，秒级）；
- `guard-bypass`、`unit`、`integration` 三个 job 并行，各自 `setup-aster-build`；`unit` 与 `integration` 把 jacoco `*.exec` 作为短期制品上传；
- `verify`（needs `unit`、`integration`）：下载 exec，按原样做覆盖率聚合与校验，再 `build -x test -x integrationTest -x guardBypassTest`。

reusable workflow 的输出 `guard-bypass` 改为取 `guard-bypass` job 的步骤 outcome。调用方 `ci.yml` 的 `build-local` / `build-hosted` 的 `result` 仍是被调 workflow 里全部 job 的结论，所以 `ci-result` 与 `Guard bypass suite (#466)` 两个汇总 job、`guard-bypass-verdict.sh` 不用改，两个必需检查名不变。

预计托管 runner 上 pin PR 的 CI 从 24–38.6 分钟降到「最慢的一套测试 + 约 3 分钟的检出与编译」，约 16–18 分钟，L4 墙钟省 8–20 分钟。代价：每个 job 各自检出与编译，总 runner 用量增加约 6–9 分钟；本地 self-hosted runner 只有一台，三个 job 排队串行，与今天基本持平。jacoco 报告要求 exec 与本 job 编译的 class 一致：同源码、同 JDK 编译出的 class 字节一致；万一不一致，覆盖率会偏低、校验失败，是 fail-closed，不会假过。

## 9. 被否决的方案

- **按 check-runs 判定**。check-run 没有「属于哪个 workflow 文件、哪个 event」的可靠字段，PR 与 push 的同名 check 会混在一起。workflow run 有 `event`、`head_branch`、`path` 可过滤，job 列表又能取最新 attempt。
- **只看 run 的 conclusion，不看 job**。条件 job 跳过时 run 仍是 success（「skipped 不是 pass」），而 parity 这类非必需 job 失败会让 run 变红。按必需 job 判定，与分支保护的语义一致。
- **新鲜度规则：要求通过的 attempt 晚于所有兄弟仓 preflight SHA 的合入时间**。它能保证 main CI 用的就是要发布的兄弟仓输入，但几乎每趟列车都要重跑大部分仓的 CI，preflight 多出 3–5 分钟，抵消了本修订的全部收益。§4 的三层兜底已覆盖这类差异。
- **在发布 run 里自动重跑红的 main CI**。发布 job 的 `GITHUB_TOKEN` 只读；给它 `actions: write`，任何能推 tag 的人就能让 CI 反复重跑直到 flake 变绿。重跑只放在 preflight，且只重跑一次。
- **forceTests 用仓库变量或 `workflow_dispatch` 重放传递**。tag-push run 没有输入。仓库变量是全局状态，会影响同一时刻的其它发布；annotated tag 的消息跟着这一个 tag 走，可审计，人工发版也能用。
- **对 aster-api 做同样的事**。aster-api 的 pin PR 是它对新引擎版本的唯一测试，用户明确要求保留，这里不动。

## 10. 后续（不在本修订范围）

- **pin 合并后的 deploy 又跑一遍 build&test**。修订 A1 让列车在 L4 合并 pin PR 后跟随 push deploy run。ADR 0053 §4 的复用键包含 aster-api commit，squash 合并产生了新 commit，所以 deploy 不能复用 pin PR 刚验证过的结果，要再跑约 28 分钟的 build&test。合并后的 tree 与 PR head 的 tree 相同（规则集要求 PR 与 main 同步），可以考虑把复用键改为按 tree 计算，或让 deploy 认可「同 tree、同 sibling-ref、`ci-result` 已绿」的 PR 结果。这是 L4 剩余最大的冗余，建议作为修订 A3 单独评估。
- core 发布 run 检出 locales、test 并 `publishToMavenLocal`，大约用 1 分钟，可能只有测试需要。核实之后可以去掉，core 是 L1 的关键路径。

## 11. 迁移、合并顺序与回滚

- **前提**：eco-1.0.48（run 43803509463）结束之前，下列 PR 一律不合并。
- **合并顺序**：
  1. platform PR：共享 action、preflight、`forceTests`。未接入的仓（没有 `.github/main-ci-gate.json`）preflight 不查，发布 run 照旧跑测试，所以单独合并也安全。`forceTests` 缺省关闭，dispatch 参数不变。
  2. 八个引擎仓 PR，顺序不限。它们引用 `require-main-ci-green@main` 与 `verify-jar-contents@main`，所以必须在第 1 步之后合并。
  3. 可选：aster-api CI 拆分 PR，与上面无依赖。
- **第一次使用**：eco-1.0.49 先跑一次 dryRun，确认 preflight 的 main CI 门禁对每个仓都给出结论；需要重跑的仓在 dryRun 里会显示红，正式开车时 preflight 会自动重跑。
- **回滚**：revert 各引擎仓 PR，发布 run 即恢复自己跑测试。preflight 遇到没有配置文件的仓自动跳过，所以 platform PR 可以保留，也可以一并 revert。已经打出的 annotated tag 无害。
- **用户需要做的**：无新 secret。`CROSS_REPO_TOKEN` 已有 Actions 读写，preflight 的重跑用它；各仓发布 job 只多要 `actions: read`，写在 workflow 里。
