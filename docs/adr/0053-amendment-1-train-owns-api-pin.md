# ADR 0053 修订 A1：列车在 L4 接管 aster-api 的 pin bump

- 状态：**PROPOSED**（2026-11-15，待用户接受）
- 日期：2026-11-15
- 修订对象：[ADR 0053](0053-release-train-artifact-reuse.md) §6（deploy 等待）、§7（stale gate，含「列车不推进任何参与仓 main」的断言）、§13（迁移）
- 相关：ADR 0023（发布列车）、aster-api#499 / #506 / #527（手工 pin PR）、platform#82（列车 dispatch 必须带 trainId）、#337（sibling-ref 与 settings 两处 pin 一致性门禁）
- 实现落点：`aster-lang-platform`：`scripts/release-plan/run-train.sh`、`scripts/release-plan/check-artifact.py`、`.github/workflows/release-train.yml`、`release-plan.json`；`aster-api`：无代码改动（见 §7）

---

## 1. 问题

aster-api 的 platform 版本写在两处，且必须同时 bump（`scripts/ci/verify-sibling-ref-pin.sh` 门禁）：

| 位置 | 内容 |
|---|---|
| `settings.gradle` | `from('cloud.aster-lang:aster-lang-platform:X')` |
| `.github/actions/setup-aster-build/action.yml` | `sibling-ref` 的 `default: "vX"` |

今天的发版顺序是「先合 pin PR，再开车」，原因是 preflight 的 drift gate（`check-artifact.py`，artifact `aster-api:service`）要求 aster-api main 上的 pin **已经等于** `release-plan.platformVersion`。但 pin PR 的 CI（`ci-result`、`Guard bypass suite (#466)`，规则集 `main-protect` 的两个必需检查）要按 `sibling-ref` checkout 七个兄弟仓的 `vX` tag，而这些 tag 要等列车 L0–L3 才打出来。所以这个 PR **在开车前不可能变绿**，形成循环依赖：

- drift gate 要求 pin 先进 main；
- pin 进 main 要求 CI 绿；
- CI 绿要求 tag 已存在；
- tag 由列车创建，而列车要先过 drift gate。

现状的破环办法是组织管理员用规则集的 `OrganizationAdmin` bypass 强行合并红 CI 的 pin PR。#499、#506、#527 都是这样合入的，只有用户本人能做。代价：

1. **每次发版都要人工 bypass 一次**，列车无法无人值守。
2. **pin 合入时的 push deploy 必然失败**。1.0.47 实测：#527 于 22:23:38 合入，push 触发的 deploy run 43647862278 因 `v1.0.47` tag 不存在而失败（main 上留下一个红 deploy）；之后列车 L4 dispatch 的 run 43649110820 才部署成功。
3. **pin PR 的 CI 结果没有意义**：它红是因为 tag 不存在，掩盖了 PR 内容本身的问题；自检注入的假注解（#528 修复）更让人误读这类红。
4. **合入的 commit 从未在 CI 上验证过**：bypass 合入的是未经 `ci-result` 验证的 commit；之后才由 deploy 的 build&test 兜底。

## 2. 决策

列车的 `aster-api:service` step 拆成两个阶段，在 L4 依次执行（与 `ts:npm` 仍并行）：

```
L4 aster-api:service
  ├─ 阶段 P（pin）：main 的 pin 已 == X → 跳过；否则开 pin PR → 等必需检查 → 合并
  └─ 阶段 D（deploy）：跟随 pin 合并 commit 的 push deploy run；P 跳过时按现状 dispatch
```

此时 L0–L3 已经把 `vX` tag 全部打出，pin PR 的 CI 能正常变绿，**不需要任何 bypass**。列车只做一件今天由人做的事：改两行、开 PR、等绿、合并。

### 2.1 阶段 P：pin PR

1. **幂等检查**：读 aster-api main 的 `settings.gradle` 与 `action.yml`。两处都已等于 `X` → 记 `pin: skipped`，进入阶段 D（今天的手工流程、续跑都走这条路）。只有一处等于 `X` → 失败（两处不一致说明有人半改，不由列车猜）。
2. **stale gate**：沿用 ADR 0053 §7，main tip 必须等于 preflight 记录的 SHA。pin PR 的 base 就是这个 SHA。
3. **生成改动**：固定分支名 `train/aster-api-pin-X`。在 preflight SHA 上改两处，用 `verify-sibling-ref-pin.sh` 在本地断言改后两处一致（#337 的教训：1.0.29 只改了 settings），并断言 `git diff --name-only` **恰好**是这两个文件。
4. **开 PR 或复用 PR**：同名分支已有 open PR 时（续跑），head 内容与第 3 步结果一致 → 复用；不一致 → force-push 到第 3 步的 commit。标题 `chore(release): 生态 pin bump <old> → X（train <trainId>）`，正文注明 trainId 与列车 run 链接。
5. **等必需检查**：轮询 PR head 上 `ci-result` 与 `Guard bypass suite (#466)` 的 check-run。只认 GitHub Actions（`integration_id` 15368）出的结果，与规则集一致。上限 `PIN_CI_POLL_MAX`，缺省 180 × 20s = 60 分钟（托管构建实测 36–40 分钟，本地 runner 更快）。
6. **最多一次自动重跑，且只针对可证实的超时**：
   - 判定为超时的条件：PR head 上 `build-hosted / build` 的 check-run `conclusion == "timed_out"`，或该 job 的注解含 runner 的固定文案「has exceeded the maximum execution time」。两者都不满足的 `cancelled`（人工取消、并发组取消）、`failure`、查询失败一律**不**重跑，直接停车，PR 留着给人看。
   - 次数记在 GitHub 自己的状态里，不靠列车记忆：只有当该 CI run 的 `run_attempt == 1` 时才 `gh run rerun <run> --failed`。续跑（`fromLayer=4`）看到 `run_attempt >= 2` 就不再重跑。因此同一个 PR head 在整个发版周期内最多被列车自动重跑一次，无论续跑多少次。人工 rerun 不受此限。
7. **合并**：`gh pr merge --squash --match-head-commit <head>`。规则集的 `strict_required_status_checks_policy: true` 要求 PR 与 main 同步：若 CI 期间 main 被别人推进，合并会被拒，列车停车并报「main 前进，按 §5 续跑」，**不**自动 update-branch（update 后要再跑 40 分钟 CI，且新 base 未经 preflight 验证）。
8. **记录**：merge commit SHA 写进 train manifest（`pin: merged`、`pinPr`、`pinCommit`），并把它登记为本列车**自己推进**的 main（见 §3）。

### 2.2 阶段 D：deploy

- **P 合并了 PR**：pin 合并本身会产生一次 `push` 到 main，deploy.yml 照常触发。列车**不再 dispatch**，改为关联这次 push run（理由见 §4）：
  - 查询：`GET /repos/wontlost-ltd/aster-api/actions/workflows/deploy.yml/runs?event=push&head_sha=<pin merge commit>&per_page=100`。`head_sha` 是列车自己合入的 squash commit，一次 push 只产生一个 deploy run；若意外有多个，取 `created_at` 最早的，并在日志中列出全部。结果不分页也装得下（≤100）。
  - 重跑沿用同一个 run ID（`run_attempt` 递增），所以每轮都按 run ID 读最新一次 attempt 的状态。
  - run 在 `SERVICE_FALLBACK_AFTER` 轮（缺省 5 分钟）内仍未出现 → 停车，不回退到「since 后最早的 run」，因为这里没有标题可做二次确认。
  - 等待上限沿用 ADR 0053 §6 的 `SERVICE_POLL_MAX`。
- **P 跳过**（pin 早已在 main：手工 pin、续跑、或列车上一次已合并）：维持今天的行为，带 trainId dispatch deploy.yml，按标题 `train <id>` 关联。dispatch 前照常过 stale gate（main tip 必须等于本次 preflight 记录的 SHA）；关联到 run 后再断言它的 `head_sha` 等于该 SHA，不等则停车。这样 dispatch 部署的就是本次 preflight 验过、且已包含 pin 的那个 commit，虽然 deploy.yml 对 dispatch 不做 stale guard。ADR 0053 §5 的复用键让同一组输入的重部署约 3 分钟完成。

**阶段 D 的完成条件**（两条路径相同）：找到匹配的 run，且以下三项全部成立。job 的 outputs 不经 REST API 暴露，所以列车读 `GET /actions/runs/<id>/jobs` 的 job 与步骤结论：

1. run `conclusion == success`；
2. `image-pin-pr` job 的 `Open/update image-pin PR to k3s` 步骤为 `success`。stale guard 判过时，整个 job 为 `skipped`；final stale gate 判过时，job 为 `success` 但该步骤为 `skipped`。两种情况都说明这次没有部署或没有 pin；
3. `pin-closure-guard` job 为 `success`。

不满足（含 run 缺失、被取消、查询失败、判过时）→ 停车。判过时的提示是「main 在部署期间前进，更新的 commit 的 deploy run 理应接手，但列车不替它担保；请确认该 run 成功后用 fromLayer=4 续跑」。续跑时 preflight 重新取 main tip，P 跳过，D 走 dispatch 并按上面的 SHA 断言收尾，约 3 分钟。

## 3. preflight drift gate 的改动

`check-artifact.py` 对 `aster-api:service` 目前断言 `settings pin == expectedPlatformPin == platformVersion`。改为：

- `release-plan.json` 给该 artifact 增加 `"pinOwner": "train"`（缺省 `"human"`，即保持现行断言）。
- `pinOwner == "train"` 时，pin 允许两种取值：
  - `== platformVersion`：已经 bump 过（手工或上次列车），阶段 P 将跳过；
  - **严格小于** `platformVersion`，并且 `aster-lang-platform` 上存在 `v<pin>` tag（证明是一个真实发布过的旧版本，而不是乱写的值）：阶段 P 将负责 bump。
  - 其它取值（大于目标版本、tag 不存在、无法解析）仍判漂移。
- 比较规则：两边都必须完整匹配 `^\d+\.\d+\.\d+$`（不接受前缀 `v`、预发布后缀或构建元数据），按 (major, minor, patch) 三元组逐位做**整数**比较，不做字符串比较。边界用例写进表驱动测试：`1.0.9 < 1.0.10`、`1.9.0 < 1.10.0`、`1.0.47 == 1.0.47`、`1.0.48 > 1.0.47`（漂移）、`1.0.47-rc1`（无法解析，漂移）。
- tag 校验：`git ls-remote --exit-code https://github.com/aster-cloud/aster-lang-platform refs/tags/v<pin>`。返回码 2（不存在）判漂移；其它非零（网络、鉴权）**同样判失败**（fail-closed），报错信息区分「tag 不存在」与「查询失败」。
- 无论哪种取值，都对 aster-api main 跑一遍 `verify-sibling-ref-pin.sh` 的等价断言（`settings` 与 `sibling-ref` 两处一致）。半改状态在 preflight 就拦下，不拖到 L4 才发现。
- preflight 输出 `api_pin_action=bump|skip`，dry-run 与 job summary 都打印它。

ADR 0053 §7 写的是：「**列车自身不会推进任何参与仓的 main** … 今后若有发布 workflow 在列车途中回写本生态某仓的 main，必须把该仓排除出 stale gate，或者在 preflight 之后重新记录它的 SHA，并在本节登记。」本修订就是这种情况，在此登记：

- aster-api 的 `MAIN_SHAS` 条目在阶段 P 合并后**由列车自己更新**为 pin merge commit。之后阶段 D 若需要 dispatch（续跑路径），stale gate 比对的是这个新值。
- pin PR 的 base 必须等于 preflight SHA（§2.1 第 2 步），所以「列车推进的这一步」是唯一一个未经 preflight 验证就进 main 的 commit。它的内容由第 3 步限定为恰好两个文件、两处版本号，并且已经过 `ci-result` 完整验证，比今天 bypass 合入的未验证 commit 更严格。

## 4. push-deploy 竞态

pin 合入 main 一定会触发 push deploy，这是 deploy.yml 的既定设计（不设 `paths-ignore`，保证每个 main commit 都有自己的 deploy run 接手，见 deploy.yml 头注释）。如果列车照旧再 dispatch 一次，会出现：

| 情况 | 后果 |
|---|---|
| push run 与 dispatch run 同属 `deploy-refs/heads/main` 并发组（`cancel-in-progress: false`） | 串行：push run 完整构建约 30 分钟，dispatch run 排队后按复用键晋升同一 digest（约 3 分钟），image-pin PR 开两次（同一 digest，第二次幂等） |
| 排队期间又有第三个 run 进组 | GitHub 只保留最新一个 pending，**排队中的 dispatch run 被取消**，列车看到 `cancelled` 而停车 |
| dispatch run 不经 stale guard（deploy.yml 只对 push 把关） | 若期间 main 前进，dispatch 部署的是 dispatch 时刻的 main，而不是 pin commit |

所以 §2.2 选择「P 合并了就跟随 push run，不再 dispatch」：

- 一次部署、一次 image-pin PR，没有重复构建，也没有排队被取消的问题；
- push run 走 stale guard 与 final stale gate，main 前进时由更新的 commit 接手，语义与日常合入一致；
- 关联键是 `head_sha` 等于 pin merge commit 且 `event=push`。这个 commit 由列车自己合入，SHA 唯一，不会关联错。

代价与处理：

- push run 的标题是 commit message，不带 `train <id>`。cleanup（ADR 0053 §9）按标题识别「本列车 deploy run 的中间制品」，这次识别不到。影响很小：deploy.yml 的 `cleanup-build-artifact` 在部署闭环成功后自己会删 `quarkus-app-<key>`，失败时制品保留供续跑复用，3 天 retention 兜底。cleanup-train.sh 额外按 manifest 中记录的 `run` ID 清理，补上这一类。
- push run 的 image-pin 判定走 `github.event_name == 'push'` 分支（deploy.yml `image-pin-pr` 的 `if:`），与 trainId 无关，闭环语义不变；pin-closure-guard 照常把关。

被否决的方案：

- **合并 commit 带 `[skip ci]`，再由列车 dispatch**：`[skip ci]` 会同时跳过 ci.yml 的 push 构建，并在 main 上制造一个没有 deploy run 的 commit，正是 deploy.yml 头注释所说的「过时死角」。
- **deploy.yml 识别 pin commit 并跳过 push deploy**：要给 deploy.yml 加一个按 commit message 判断的特殊分支，违反「每个 main commit 都有自己的 deploy run」的不变量，收益只是保留标题关联。

## 5. 失败与续跑（`fromLayer=4`）

续跑时 preflight 照常重跑（drift gate 与 `MAIN_SHAS` 都会重新取值），阶段 P、D 各自幂等：

| 停在哪里 | main 状态 | 续跑时的行为 |
|---|---|---|
| P 开 PR 前（stale gate、diff 断言、token 失败） | pin 未变 | P 重新开 PR |
| P 等 CI 时失败或超时 | pin 未变，PR open | 人工 rerun CI 也可以；续跑时 P 复用同名 PR：head 一致且检查已绿 → 直接合并；正在跑 → 继续等；已失败 → 按 §2.1 第 6 步（`run_attempt == 1` 且可证实超时才自动重跑一次），否则再停车 |
| P 合并被拒（strict：main 前进） | pin 未变，PR open 且落后 | preflight 取新 tip；P 发现 head 的 base 已不是新 tip → 在新 tip 上重建 commit 并 force-push，重新等 CI |
| P 合并成功，D 失败、超时或判过时 | pin == X | P 跳过；D 走 dispatch（标题关联），复用键晋升约 3 分钟 |
| D 成功，`ts:npm` 失败 | pin == X，已部署 | P 跳过；D dispatch 一次幂等重部署（与今天续跑 L4 的行为相同）。若要省掉这次重部署，可另行给 service step 加「当前 main 已部署」的跳过判定，不在本修订范围 |

列车不会关闭或删除自己开的 pin PR，也不会回滚已合入的 pin（forward-only，与 ADR 0023 一致）。若决定放弃这一版，由人关闭 PR 并删分支 `train/aster-api-pin-X`。

## 6. 谁来开 PR 和合并：凭据

需要的权限只针对 `wontlost-ltd/aster-api`：`contents: write`（推分支）、`pull_requests: write`（开 PR、合并）、`checks: read` / `actions: write`（读检查、超时重跑一次）。**不需要**规则集 bypass：L4 时 CI 能正常变绿，合并走正常的必需检查。也**不需要** `workflows` 权限：两处改动都不在 `.github/workflows/` 下（`action.yml` 位于 `.github/actions/`，GitHub 只对 `.github/workflows/` 下的文件要求该权限）。

| 方案 | 说明 | 评估 |
|---|---|---|
| A. 复用 `CROSS_REPO_TOKEN_WONTLOST`（fine-grained PAT） | 列车已持有，今天用于 dispatch deploy；需追加 Contents: write 与 Pull requests: write | 最省事。但 PAT 绑定用户个人账号、长期有效，PR 作者显示为用户本人；扩权后，这个令牌在 wontlost-ltd 下的写权限覆盖所有被授权的仓 |
| **B. 专用 GitHub App，仿 image-pin App（推荐）** | 新建 App（如 `aster-train-pin`），只装在 `wontlost-ltd/aster-api`；`aster-lang-platform` 存 `vars.TRAIN_PIN_APP_CLIENT_ID` 与 `secrets.TRAIN_PIN_APP_PRIVATE_KEY`；L4 用 `actions/create-github-app-token`（`owner: wontlost-ltd`、`repositories: aster-api`、按上表最小权限）签发 1 小时令牌 | 与 deploy.yml 的 image-pin-pr 同一模式（App 4253193，跨 org、短时、按仓与权限收窄）。PR 作者是 bot，可审计；令牌不随个人账号变化 |
| C. 带规则集 bypass 的 bot | 让 bot 进 `main-protect` 的 bypass 列表，可以合并红 CI | **否决**。L4 时 CI 本来就能绿，bypass 没有必要；而一旦放开，列车（以及任何能触发它的人）就能把未验证的 commit 合进 main，正好拆掉本修订要恢复的那道门 |

方案 B 的安全含义（如实列出，供决策）：

- **谁能用这个令牌**：任何能在 `aster-lang-platform` 上 dispatch Release Train 的人（该仓 write 权限）都能间接让 App 往 aster-api 推分支并合并 PR。合并仍受必需检查约束，绕不过 CI；但这些人由此获得了「无需 aster-api 写权限也能把通过 CI 的改动合进 aster-api main」的能力。缓解：列车代码只允许改那两个文件的版本号（§2.1 第 3 步的 diff 断言），分支名固定 `train/aster-api-pin-*`，合并带 `--match-head-commit`。
- **App 令牌能做到什么**（信任边界，按「令牌落到恶意方手里」评估）：
  - 能推任意分支、开任意 PR。同仓 PR 不是 fork PR，它的 CI 会执行 **PR 自带的**构建配置：`build.gradle`、`gradlew`、本地 composite action（`.github/actions/*`，例如 ci.yml 的 `pick-runner` 拿到 `secrets.RUNNER_PROBE_TOKEN`）以及 `.github/actions/setup-aster-build`。所以令牌持有人可以在托管 runner 和**自建 ARM64 runner**上执行任意代码，并读到同仓 PR 能拿到的 secret。缺少 `workflows` 权限只能阻止修改 `.github/workflows/*.yml` 本身，**不能**隔离上述执行面：workflow 调用的 action 与脚本都在它可写的范围内。
  - 能合并任何通过必需检查的 PR。合并后 main 上的 push workflow（deploy.yml、两个 runner deploy workflow）带着 `CROSS_REPO_TOKEN`、cosign 签名身份、镜像仓库推送凭据和 image-pin App 运行，结果是部署到生产。也就是说，令牌持有人的能力上限约等于「一个 aster-api 维护者」：可以把任何能通过 CI 的代码部署到生产。它做不到的只有：bypass 规则集、force-push 或删除 main（`deletion` 与 `non_fast_forward` 规则）、直接修改 workflow 文件。
- **谁能间接使用这个令牌**：任何能在 `aster-lang-platform` 上 dispatch Release Train 的人（该仓 write 权限），以及能修改 platform 的 `run-train.sh` / `release-train.yml` 并让它在 main 上运行的人。列车代码自身的约束（§2.1 第 3 步只允许改两个文件的版本号、固定分支名 `train/aster-api-pin-*`、`--match-head-commit`）只约束**正常运行的列车**，不约束改了列车代码的人。所以 platform 仓的写权限实际上等于 aster-api 的维护权限。接受本修订前需确认两仓有写权限的人员一致。
- **与方案 A 比较**：PAT 泄露时影响面是 PAT 覆盖的**所有** wontlost-ltd 仓，且长期有效；App 令牌只覆盖 aster-api，1 小时过期，私钥只在 platform 仓的 secret 中。
- **合并即部署**：App 合并 → push deploy → 生产。这与今天「用户 bypass 合并 → push deploy」的后果相同，区别在于发起者是列车，且合并前 CI 已经绿。生产部署的门（stale guard、cosign 验签、image-pin 的 verify-image-pin）不变。

## 7. dry-run 行为

`dryRun: true` 时：

- preflight 打印 `api_pin_action`。为 `bump` 时，同时打印两处的「旧 → 新」和本地生成的 diff（只在 runner 的临时 clone 里改，不推送）。
- 阶段 P：执行 §2.1 的第 1–3 步（读状态、stale gate、生成改动、跑 `verify-sibling-ref-pin.sh` 与 diff 断言），只在 runner 的临时 clone 里改，不推分支、不开 PR、不合并、不重跑 CI。
- 凭据验证：train job 在真发与 dry-run 时**都**用 `actions/create-github-app-token` 签发令牌，权限由 `inputs.dryRun` 决定。dry-run 只申请 `contents: read`、`pull-requests: read`、`checks: read`，真发才申请写权限。签发成功本身证明 App 已安装在 aster-api、私钥有效；脚本再用它做三个只读调用：`GET /repos/wontlost-ltd/aster-api`、`GET /repos/wontlost-ltd/aster-api/pulls?head=wontlost-ltd:train/aster-api-pin-X&state=open`（报告是否已有同名 PR），以及读 main 上两个文件的内容。dry-run 证明不了写权限确实可用：App 安装的权限上限低于申请值时，签发会直接失败，而 dry-run 只申请读权限，看不到这种失败。所以每次 App 权限变更后，第一趟真发列车要关注 L4 的签发步骤。
- 阶段 D：沿用现有 dry-run 日志（`would dispatch+wait` / `would follow push run of <merge sha>`）。
- manifest 记 `result: dry-run`，附 `pinAction`。

aster-api 侧不需要改动：deploy.yml 的 push 路径、trainId 路径、stale guard 都保持原样。

## 8. 实现清单（接受后另开实现 PR）

| 仓 | 改动 | 测试 |
|---|---|---|
| aster-lang-platform | `check-artifact.py`：`pinOwner: train` 的放宽断言 + 两处一致断言 | 表驱动：等于新版本 / 已发布旧版本 / 未发布旧版本 / 更高版本 / 半改 |
| aster-lang-platform | `run-train.sh`：service step 拆 P/D；`MAIN_SHAS` 由列车更新；push run 关联与过时判定；超时重跑一次 | `run-train.test.sh` 增用例：skip、开 PR、复用 PR、CI 失败、超时重跑、strict 拒绝合并、push run 判过时、续跑 dispatch、dry-run 不写 |
| aster-lang-platform | `release-train.yml`：train job 签 App 令牌（`WONTLOST_PIN_TOKEN`）；preflight 输出 `api_pin_action` | actionlint |
| aster-lang-platform | `release-plan.json`：`aster-api:service` 加 `"pinOwner": "train"`；`cleanup-train.sh` 按 manifest 的 run ID 补清 | `train-reuse.test.sh` |
| 用户 | 建 App、装到 aster-api、在 platform 配 var/secret | 一次 dry-run 列车验证令牌与 diff |

## 9. 迁移与回滚

- **向后兼容**：`pinOwner` 缺省为 `human`，行为与今天完全一致。手工先合 pin 的流程在新逻辑下也能工作（阶段 P 跳过）。
- **启用顺序**：先合实现 PR（此时 `pinOwner` 仍缺省）→ 用户配好 App → 改 `release-plan.json` 设 `pinOwner: train` → 下一趟列车先 dry-run。
- **回滚**：把 `pinOwner` 改回 `human`（或删掉该字段）即恢复手工流程，不需要 revert 代码。
- **不再需要的人工步骤**：每次发版的 aster-api pin PR 与 org-admin bypass 合并。各引擎仓自己的 platform pin PR（如 core#264）不在本修订范围。
