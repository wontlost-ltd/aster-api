# ADR 0053：发布列车的产物复用、层内并行与清理

- 状态：**PROPOSED**（2026-11-05；同日按独立审查 review-train-reuse 修订：H1 制品来源校验、M1 签名绑定 key、M2 deploy run 不锁定、L1/L7/L9/L10/L12）
- 日期：2026-11-05
- 相关：ADR 0023（多模块发布治理 / 发布列车）、aster-lang-platform#82 / #85 / #89、aster-lang-test#168
- 实现：aster-cloud/aster-lang-platform PR（分支 `feat/train-artifact-reuse`）、wontlost-ltd/aster-api PR（同名分支）

---

## 1. 背景与实测

发布列车（`aster-lang-platform/.github/workflows/release-train.yml` → `scripts/release-plan/run-train.sh`）按
`release-plan.json` 的 `releaseOrder` 逐层发布：platform → {core, runtime, validation, test} → {locales, hi} → truffle → {ts, aster-api}。
每个 step 都 dispatch 该仓的发布 workflow，等 tag、等 tag-push 发布 run、等制品在 registry 可见。aster-api 的 `deploy.yml` 再完整 build&test 一遍并构建镜像。

### 1.1 最近三次列车（`gh run view --json jobs` 与列车日志）

| 列车 | 层 1 | 层 2 | 层 3 | 层 4 | 合计 | 结局 |
|---|---|---|---|---|---|---|
| 1.0.39（run 41969985783） | 11:37（core 3:50、runtime 3:09、validation 2:08、test:jvm 2:28，**串行**） | 5:20（locales 2:50、hi 2:29） | 2:28 | ts 5:13 **然后** aster-api 32:07 | 56:47 | aster-api deploy 32 分钟后**成功**，列车在第 30 分钟报超时 |
| 1.0.38（run 41151140412） | 11:19 | 8:33（locales 6:24，其中 npm 可见性等了 3:15） | 2:49 | ts 已发布跳过；aster-api 25:46 | 48:28 | 成功 |
| 1.0.37（run 40945596207 + 续跑 40955511016） | — | — | — | ts 17:10（npm 可见性等了 13 分钟）；aster-api 25 分钟后失败；续跑重新部署 42 分钟 | 两次合计 >1h40 | 首次：deploy 期间 main 有新合入，image-pin 的 final stale gate 判过时，pin-closure-guard 变红；续跑：部署 42 分钟成功，列车在第 30 分钟报超时 |

aster-api deploy run 内部（1.0.39，run 41972569723）：

| 阶段 | 耗时 |
|---|---|
| 起 PG/Redis service | 0:22 |
| setup-aster-build（检出 7 个兄弟仓 + JDK + catalog） | 0:54 |
| **Build & Test（`./gradlew build`，含全部单测与集成测试）** | **27:42** |
| deploy（下载制品、构建 arm64 镜像、arch 校验、签名、验签） | 1:26 |
| image-pin-pr + pin-closure-guard | 0:58 |

Java 引擎仓的发布 run（例：core 1.0.39 publish 2:22）已经是「只 assemble，测试在 PR CI 跑过」，其中检出兄弟仓并 `publishToMavenLocal` 约 1:20。ts 的发布 run 是 ci 1:30 + publish 1:17（`prepublishOnly` 又跑一遍测试）。

### 1.2 时间花在哪

1. **同层串行**。release-plan 的契约是「同一层无依赖、可并行」，`run-train.sh` 却逐个 step 等完再下一个。层 1 的四个发布串行 11:37，最长的一个才 3:50。层 4 的 ts 与 aster-api 也串行，ts 的 5 分钟（1.0.37 是 17 分钟，大半在等 npm 可见性）全部加在关键路径上。
2. **aster-api 的 28 分钟 build&test 无法复用**。同一个 aster-api commit 加同一组兄弟仓 tag 的部署，每次重跑（续跑、重新部署）都重新跑 28 分钟测试。quarkus-app 制品名固定为 `quarkus-app`、retention 1 天，只在同一个 run 内传递。
3. **超时判定错误**。service step 与 Java 发布共用 `POLL_MAX=90 × 20s = 30min`。aster-api deploy 实测 26–42 分钟，所以 1.0.37 续跑和 1.0.39 都在部署成功前被判超时。另外关联方式是「`created>=since` 的**最新**一个 dispatch run」，这期间任何人手工 dispatch 一次 deploy，列车就会等错 run。
4. **列车途中合入**。preflight 的 drift gate 验的是开车那一刻各仓 main 的 tip，而每个发布 workflow 都是 `--ref main` 打 tag。中途有 PR 合入时，tag 会打在没验过的 commit 上；aster-api 的 deploy 会被自己的 stale gate 拦下，列车这边只看到一个失败或超时（1.0.37）。
5. **可见性检查有 SIGPIPE 缺陷**（本次 dry-run 时发现）。`gh api … | grep -qx "$ver"` 在 `pipefail` 下，grep 命中后立刻退出，gh 再写管道会收到 SIGPIPE，整条管道被判失败，结果是「已发布却判不可见」。另外它只读第一页（30 个版本）。
6. **临时物不回收**。每次部署留下约 114MB 的 `quarkus-app` 和一个 `*.dockerbuild` 构建记录制品；aster-api 当前有 22 个 Actions cache，共 6.3GB，接近 10GB 配额。

## 2. 决策一：同层 step 并行

`run-train.sh` 让每层的 step 各在一个子 shell 中并发执行（GH_TOKEN 和 REPO_ORG 按 step 切换，互不干扰），层末统一 `wait`：

- 任一 step 失败时，等同层其他 step 收尾后再停车，不中途杀掉正在发布的仓（forward-only 语义不变），并输出 `::error::Layer N 失败：<列表>`。
- 日志带 `[repo/workflow]` 前缀；`::error::` 仍然写在行首（#89）。
- 同仓的两个独立 workflow（test 的 release-maven / release-npm）本来就可以并行；coalesce 规则不变，locales/hi 仍然只 dispatch 一次。

## 3. 决策二：列车清单（train manifest）

每个 step 结束时记录 `{repo, org, workflow, artifactIds, tag, commit, run, result}`，result 取值为 published / skipped / deployed / dry-run。每层结束时合并成 `train-manifest.json`，所以失败时清单也能反映车开到了哪一层。清单写进 job summary（永久可查），同时作为 `train-<id>-manifest` 制品上传（retention 7 天，由 cleanup 删除）。对 skipped 的 step 也解析出 tag 指向的 commit，保证清单里每个制品都能按 SHA 溯源。

清单是「下游复用上游输出」的事实源：下游发布 run 检出兄弟仓时已经按同名 tag 解析（`checkout-sibling` 规则 1），清单再给出这个 tag 在发布那一刻对应的 commit，可用于事后核对。

## 4. 决策三：aster-api 的构建复用键

`scripts/ci/build-reuse-key.sh` 把一次构建的全部输入压成一个 key：

```
format 1
api <aster-api commit>
java <JDK 主版本，取自 setup-aster-build/action.yml 的 java-version 缺省值>
sibling-ref <vX>
aster-lang-<repo> <该 tag 指向的 commit>   × 7（清单从 setup-aster-build/action.yml 读取，不重复维护）
```

key 取上面清单 sha256 的前 32 位。有两种模式，算出的 key 必须一致（有测试）：

- `remote`：`git ls-remote` 解析 tag（annotated tag 取 peel 后的 commit），不检出源码，几秒钟完成。新增的 `reuse-probe` job 用这个模式。
- `local`：读已检出兄弟仓的 HEAD。`build-and-test` 在构建前用它断言「实际构建的源码就是 key 声明的那份」，tag 在两步之间被移动时直接失败。

任何一个仓解析不出 commit 都会让脚本非零退出，不产出 key（fail-closed）。

## 5. 决策四：aster-api 一次构建、按 digest 晋升

`deploy.yml` 新增 `reuse-probe` job，按以下优先级选路径：

1. **晋升已签名镜像**：`wontlost/aster-api:build-<key>` 存在，且它的 digest 能通过 `cosign verify -a build-key=<key>`（身份限定为本仓 `deploy.yml@refs/heads/main`，与部署前的验签一致）。签名时用 `cosign sign -a build-key=<key>` 把 key 写进签名载荷：即便有人把 `build-<key>` tag 改指到一个本 workflow 签过、但属于别的 key 的旧 digest，注解也对不上，不会晋升。本次改动之前签的镜像没有这个注解，不会被晋升，会安全地回落到重建。此时跳过 build&test、镜像构建、arch 校验和签名，`docker buildx imagetools create` 把同一个 digest 重新打上 `<sha7>` 和 `jvm-latest` tag，deploy job 部署前再验一次签。签名绑定 digest，晋升不产生新 digest，签名依然有效。
2. **复用已验证制品**：存在未过期的 `quarkus-app-<key>` 制品，**且产出它的 run 可信**。可信的标准由 `scripts/ci/verify-artifact-run.sh` 判定：`path == .github/workflows/deploy.yml`、`head_branch == main`、`head_repository` 是本仓、event 为 push 或 workflow_dispatch。制品名谁都能起，PR、fork、其他 workflow 都能上传同名制品，所以「存在即已验证」只对可信来源成立；deploy.yml 只在 Build & Test **通过之后**上传它。reuse-probe 校验一次，deploy job 下载前再校验一次（两个 job 之间不互信），不可信就回落完整构建（有负例测试）。此时跳过 build&test，deploy 用 `download-artifact` 的 `run-id` 从产出它的那次 run 下载，逐文件核对 `SHA256SUMS` 后再构建镜像。
3. **完整构建**（缺省路径，向后兼容）：与原来相同，另外给镜像加打 `build-<key>` tag，并上传带校验和的 `quarkus-app-<key>`（retention 3 天）。

任何探测失败（API 错误、tag 不存在、验签不符）都落到路径 3：复用只用来加速，不放宽任何门禁。手工 dispatch 时可以传 `forceRebuild: true` 关闭复用。stale guard、arch 校验、cosign 签名与验签、image-pin、pin-closure-guard 的语义都不变。

收益：同一个 commit 的续跑或重新部署从 26–42 分钟降到约 3 分钟（probe 0.5 分钟 + 晋升与验签约 1 分钟 + pin 约 1 分钟）。1.0.39 那种「部署其实成功、列车误判超时」的情况，续跑一次只需要约 3 分钟收尾。

## 6. 决策五：deploy 等待用对信号、设有界上限

- `deploy.yml` 加上 `run-name`：带 trainId 的 dispatch，标题为 `Deploy · train <id>`。列车按标题后缀精确关联自己触发的 run，不再取「最新一个 dispatch run」。
- **不锁定**：每一轮都重新按标题查。只有**连续** `SERVICE_FALLBACK_AFTER` 轮（缺省 15 × 20s = 5 分钟）都没有标题匹配时，才判定 deploy.yml 缺少 run-name，回退为 `created>=since` 的最早一个 dispatch run，并在日志里写明。这样，dispatch 后的延迟窗口里插进来的手工 dispatch 不会被当成本列车的 run（有测试）。
- service step 使用独立上限 `SERVICE_POLL_MAX`，缺省 180 × 20s = 60 分钟。这仍是有界等待，覆盖实测最大值 42 分钟，余量约 40%。最后一轮之后再查一次终态（#82）。
- 超时时报出 run 的 URL 和最后一次状态，并说明「它成功即部署成功，用 fromLayer 续跑收尾即可」。有了 §5，续跑只需约 3 分钟。

## 7. 决策六：stale gate（防列车途中合入）

preflight 在 drift gate 通过后，把各仓 clone 下来的 main HEAD 记成 `main_shas`（JSON：repo → sha），交给 train。每次 dispatch 前，`stale_gate` 比对该仓当前的 main tip：已经前进就停车，不 dispatch，报出 `::error::<repo> main 已从 preflight 的 A 前进到 B`。处理办法是重跑列车（preflight 会重新验证新 tip），或用 fromLayer 从本层续跑。没有传 `MAIN_SHAS` 时不设防，保持向后兼容。

deploy 期间（dispatch 之后）的合入仍由 aster-api 自己的 stale guard 与 final stale gate 拦截；列车这边会如实报告失败。

**列车自身不会推进任何参与仓的 main**（2026-11-05 核对各仓 release*.yml）：发布 workflow 只 `git push origin <tag>`，aster-api 的 image-pin-pr 推到的是 wontlost-ltd/k3s（不在 release-plan 中）。所以 stale gate 不需要排除任何仓。今后若有发布 workflow 在列车途中回写本生态某仓的 main（例如自动 bump 版本），必须把该仓排除出 stale gate，或者在 preflight 之后重新记录它的 SHA，并在本节登记。

## 8. 完整性与溯源

| 产物 | 完整性 | 溯源 |
|---|---|---|
| quarkus-app-<key> | 来源 run 校验（deploy.yml@main，本仓，push/dispatch，两处各校验一次）；`SHA256SUMS` 逐文件校验（下载方 `sha256sum -c`）；key 绑定全部源码 commit；build-and-test 断言检出源码与 key 一致 | 制品所属 run（`workflow_run.id`），job summary 里有 key 的输入清单 |
| 镜像 | cosign keyless 签名，附 `build-key=<key>` 注解；晋升前与部署前各用 `-a build-key=<key>` 验一次签，身份限定为 deploy.yml@main；arch 校验只在构建时做（晋升不产生新 digest） | `build-<key>` / `<sha7>` tag；`ASTER_RUNTIME_BUILD=<sha>` 烘进镜像 |
| 引擎制品 | registry 是事实源（可见性检查已修 SIGPIPE 并支持翻页） | train manifest：tag → commit → 发布 run |

## 9. 决策七：清理

| 对象 | 谁删 | 什么时候 | 兜底 |
|---|---|---|---|
| `quarkus-app-<key>`（~114MB/个） | deploy.yml 的 `cleanup-build-artifact` | 部署闭环成功（pin-closure-guard 绿）后立即删。之后同 key 的复用走 `build-<key>` 镜像 | 闭环失败时保留，供重跑复用；retention 3 天 |
| `*.dockerbuild` 构建记录 | 不再产生（`DOCKER_BUILD_RECORD_UPLOAD=false`） | — | — |
| 列车自身的 `train-<id>-*` 制品 | release-train.yml 的 `cleanup` job（`!cancelled` 前提下 always 运行） | 列车结束，成功或失败都删（内容已写入 job summary） | retention 7 天 |
| 本列车 deploy run 的中间制品（按标题 `train <id>` 识别） | 同上 | 列车成功时删；**失败时保留**（`KEEP_RESUME`），它们是续跑的复用源 | retention 3 天 |
| 各仓 `refs/tags/<tag>` 作用域的 Actions cache | 同上 | 列车结束 | GitHub 7 天未访问即淘汰 |

- dryRun 列车只做 dry-run 清理（只列，不删）。
- 清理失败（包括 LAYERS 解析失败）只输出 `::warning::`，不会让一次成功的列车变红。
- 手工清理 workflow 使用独立的 concurrency 组 `release-train-cleanup`：不排在正在跑的列车后面，也不挡下一趟列车。
- 两个仓的 CI 都加了钉死版本（v1.7.7）的 actionlint 步骤。
- 手工补救用 `aster-lang-platform` 的 `Cleanup Release Train` workflow（缺省 dry-run），与列车共用 `scripts/release-plan/cleanup-train.sh`。

tag-ref cache 按 ref 隔离，永远不会被 main 或 PR 构建命中。本次 dry-run 实测当前各引擎仓没有残留（tag-push 发布 run 目前没有写入 cache），清理器仍然保留这一类，作为防线。

## 10. Gradle / pnpm / Maven 缓存

现状已经合理：各发布 workflow 与 setup-aster-build 都用 `setup-java cache: gradle` 或 `setup-node cache: pnpm`，从 main 的 cache 恢复（tag ref 可以读取默认分支的 cache）。本 ADR 不新增缓存层。新增缓存不会缩短关键路径：aster-api 的 28 分钟是测试执行时间，不是依赖下载时间。真正的问题是 aster-api 有 22 个 gradle cache 共 6.3GB，旧 key 靠 LRU 淘汰。这一项列为后续跟进：在 cleanup-old-runs 中加入「每个 ref 每个 key 前缀只保留最新 N 个」。

## 11. 不做的事（及理由）

- **引擎仓改为从 registry 解析兄弟仓，不再从源码重建**。实测 Java 发布 run 中兄弟仓本地构建约 1:20/仓。层内并行之后，它只影响每层最长的那一个 step，三层合计不到 4 分钟。代价是改动 8 个仓的 settings 与发布 workflow，并改变「发 vX 用的是兄弟仓 vX 源码」这一经过审计的语义（checkout-sibling 规则 1）。收益与风险不匹配，留到 ADR 0023 的后续阶段评估。
- **ts 发布复用 main CI 结果（跳过 ci job）**。ts 与 aster-api 同在层 4，并行后 ts 的 5 分钟完全被 aster-api 的 30 分钟遮住，墙钟收益为 0。
- **「按 main CI 已验证的同 SHA 产物发布」用于 aster-api**。main push 时兄弟仓的新 tag 往往还不存在（例如 1.0.39 开车前 aster-api 的 push deploy 失败），push 时的产物与列车要部署的不是同一组输入。key 机制让「同输入才复用」自动成立：输入相同（续跑、重新部署）就复用，输入不同就重建。

## 12. 预期收益

| 场景 | 现状 | 本 ADR 之后 | 节省 |
|---|---|---|---|
| 完整列车（按 1.0.39 实测重放） | 56:47 | 3:50 + 2:50 + 2:28 + 32:07 ≈ 41:15 | **≈15.5 分钟（27%）** |
| 完整列车（按 1.0.38 实测重放） | 48:28 | 3:31 + 6:24 + 2:49 + 25:46 ≈ 38:30 | ≈10 分钟（21%） |
| deploy 成功但列车误判超时（1.0.37 续跑、1.0.39） | 列车红，人工核实 | 60 分钟有界上限覆盖实测 42 分钟；按标题精确关联 run | 消除误报 |
| 同 commit 续跑或重新部署 aster-api | 26–42 分钟 | ≈3 分钟（晋升已签名镜像） | ≈25–40 分钟/次 |
| deploy job 失败后重跑 | 26–42 分钟 | ≈4 分钟（复用 quarkus-app-<key>） | ≈23–38 分钟/次 |
| 配额 | 每次部署留下 114MB 制品和构建记录 | 闭环成功即删 | — |

## 13. 迁移与回滚

- **向后兼容**：没有 `MAIN_SHAS` 时不设 stale gate；deploy.yml 没有 run-name 时回退到旧的关联方式（取最早而非最新）；找不到复用产物时走原来的完整构建；`forceRebuild` 可以强制关闭复用。
- **合并顺序**：先合 aster-api PR（run-name 与复用），再合 platform PR。顺序反过来也能工作，只是会落到回退关联。
- **回滚**：分别 revert 两个 PR 即可。已经推送的 `build-<key>` tag 无害，可以保留。
- **需要用户配置**：见 PR 描述「需用户操作」一节（CROSS_REPO_TOKEN / CROSS_REPO_TOKEN_WONTLOST 需要 Actions: write，用于删除 cache 和制品）。
