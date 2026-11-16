# ADR 0053 修订 A2：发布 run 改为要求 main CI 已通过——实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从 eco-1.0.49 起，八个引擎仓（core、runtime、validation、test、locales、hi、truffle、ts）与 platform 的 tag-push 发布 run 不再重跑测试，改为确认 main CI 对要发布的 commit 已通过，不满足就 fail-closed；保留构建、打包与制品级冒烟；提供 `forceTests` 逃生口；列车 preflight 在 L0 之前统一检查。aster-api 的 L4 pin PR 测试保持不变。可选：把 aster-api 的 CI 拆成并行 job，缩短 L4。

**Architecture:** 判定逻辑只有一份：platform 的 composite action `require-main-ci-green`（bash + `gh api` + jq），各仓只提供 `.github/main-ci-gate.json`（workflow 名与必需 job 名）。列车 preflight 用同一个脚本并行检查所有参与仓，并对 attempt 1 的红 run 自动重跑一次。`forceTests` 由列车输入经 dispatch 传给各仓 `create-tag`，落成 annotated tag 的消息标记；tag-push 发布 run 从 tag 读出它。jar 冒烟是第二个共享 action `verify-jar-contents`；ts 的发布包冒烟是仓内脚本 `scripts/verify-package.mjs`。

**Tech Stack:** GitHub Actions（composite action、reusable workflow）、bash（兼容 macOS 3.2 与 runner 5.x）、jq、gh CLI、Gradle 9 / JDK 25、Node 24 / pnpm 10、TypeScript compiler API（`ts.preProcessFile`）。

**Spec（按此顺序读）：**
- `docs/adr/0053-amendment-2-release-requires-main-ci.md`（本计划的全部设计决定；计划只写怎么做）
- `docs/adr/0053-release-train-artifact-reuse.md` §2、§5、§7、§11、§13；`docs/adr/0053-amendment-1-train-owns-api-pin.md` §2
- platform：`.github/workflows/release-train.yml`、`scripts/release-plan/run-train.sh`（`stale_gate`、`run_step`）、`.github/actions/checkout-sibling/action.yml`
- 各引擎仓 `origin/main` 的 `.github/workflows/release*.yml` 与 `ci.yml`；ts 的 `package.json` `prepublishOnly`、ts#318 / ts#319

---

## Global Constraints

- **时间门**：eco-1.0.48（aster-cloud/aster-lang-platform run 43803509463）结束之前，不合并任何 PR，不 dispatch、不打 tag、不改它依赖的任何东西。所有 PR 一律 draft，正文首段写明「eco-1.0.48 结束后才可合并」。
- **aster-api main 冻结**，直到 1.0.48 的 L4 完成；aster-api 的 docs PR（本计划 + ADR）与 CI 拆分 PR 保持 draft、不合并。
- **ADR 硬门**：修订 A2 为 PROPOSED，用户接受之前，实现 PR 可以评审，不得合并。
- **合并顺序**：① platform PR → ② 八个引擎仓 PR（彼此无序）→ ③ eco-1.0.49 先跑 dryRun。可选的 aster-api CI 拆分 PR 与①②无依赖。
- **分支与工作树**：每仓一个分支 `feat/release-main-ci-gate`（aster-api docs：`docs/adr0053-a2-release-main-ci-gate`；CI 拆分：`ci/parallel-test-jobs`），从 `origin/main` 新开，不堆叠；工作树在 `/Users/rpang/IdeaProjects/wt-train-1049/<repo>`，不碰用户的主 checkout。
- **共享 action 引用**：引擎仓用 `aster-cloud/aster-lang-platform/.github/actions/<name>@main`（与既有 `checkout-sibling@main` 一致）；platform 自身用本地路径 `./.github/actions/require-main-ci-green`。
- **命名（全文一致）**：配置文件 `.github/main-ci-gate.json`（键 `workflow`、`requiredJobs`）；门禁步骤 id `ci-gate`，输出 `force-tests` / `run-id` / `run-url`；tag 标记行 `release-gate: force-tests`；dispatch 输入 `forceTests`（boolean）；列车 env `FORCE_TESTS`；preflight 脚本 `scripts/release-plan/preflight-main-ci.sh`。
- **必需 job = 分支保护必需检查**：platform、core、runtime、validation、locales、hi、truffle `build`；test `Build & test (Node)`、`Build & test (JDK)`；ts `test (24)`。以 `gh api repos/aster-cloud/<repo>/branches/main/protection/required_status_checks -q .contexts` 为准。
- **fail-closed**：查询失败、配置缺失、必需 job skipped / 缺失 / 重名、tag 指向别的 commit、SHA 不在 main 上，一律拦截；等待都有上限。
- **bash 约束**：变量后紧跟中文字符时写成 `${var}`（bash 3.2 在 UTF-8 下会把多字节字符并进变量名）；空数组展开写成 `${a[@]+"${a[@]}"}`；不用 `mapfile`；参与 `$(( ))` 的输入先校验为整数。
- **提交**：中文提交说明，结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 正文结尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`。不合并、不打 tag、不发布、不 dispatch；不跑 `./gradlew --stop`；不用裸 `git stash`。
- **评审**：每个 PR 用 Codex（`codeagent-wrapper --backend codex`，ROLE_FILE reviewer.md）审查，迭代到综合评分 ≥ 90。

## Review Focus

1. **门禁定义是否严格**：只认 `event=push`、`head_branch=main`、本仓、本 workflow 的最新 run 的最新 attempt；必需 job 必须恰好一个且为 success；skipped 不算通过。
2. **tag-push 与 dispatch 同路**：门禁只在 tag-push 的 publish job；dispatch 只建 tag。人工推 tag 不能绕过。
3. **forceTests 不会被静默忽略**：老 workflow 不认这个输入（preflight 在 L0 前拦）、已有 lightweight tag（create-tag 明确失败）、annotated tag 指向别的 commit（门禁拦）。
4. **去掉的只是测试**：每仓发布 run 仍做构建、打包、`check` 中的非 test 任务、tag ↔ 版本守卫；hi、truffle 补上此前缺的版本守卫。
5. **force-tests 路径与原来等价**：core 需从 hi 源码补依赖；ts 的 `full-tests` 即原 `ci` job；publish 的 `if` 只放行两种合法组合。
6. **兼容与合并顺序**：platform 单独合并安全（未接入仓 preflight 跳过）；引擎仓 PR 依赖 platform 的 `@main`。

---

### Task 1：共享门禁 action `require-main-ci-green`（platform）

**Files:**
- Create: `.github/actions/require-main-ci-green/action.yml`
- Create: `.github/actions/require-main-ci-green/require-main-ci-green.sh`
- Create: `.github/actions/require-main-ci-green/require-main-ci-green.test.sh`
- Modify: `.github/workflows/ci.yml`（运行测试）

**Interfaces:**
- action 输入：`config`（缺省 `.github/main-ci-gate.json`）、`sha`（缺省 `github.sha`）、`repository`、`branch`（`main`）、`tag`（缺省：tag 构建时为 `github.ref_name`）、`force-tests`（`'false'`）、`token`（`github.token`）、`timeout-minutes`（30）、`appear-timeout-minutes`（5）。
- action 输出：`force-tests`（`'true'|'false'`）、`run-id`、`run-url`。
- 脚本 env：`GATE_REPO`、`GATE_SHA`、`GATE_CONFIG` 或 `GATE_WORKFLOW`+`GATE_REQUIRED_JOBS`、`GATE_BRANCH`、`GATE_TAG`、`GATE_FORCE_TESTS`、`GATE_POLL`、`GATE_TIMEOUT`、`GATE_APPEAR_TIMEOUT`、`GATE_MAX_POLLS`、`GATE_APPEAR_MAX_POLLS`、`GATE_RERUN_ONCE`；退出码 0 放行、1 拦截。
- 调用的 API：`GET repos/{r}/git/ref/tags/{tag}`、`GET repos/{r}/git/tags/{sha}`、`GET repos/{r}/actions/workflows/{wf}/runs?head_sha&event=push&branch`、`GET repos/{r}/actions/runs/{id}/jobs?filter=latest&per_page=100`、`GET repos/{r}/compare/{branch}...{sha}`；`gh run rerun {id} --failed -R {r}`（仅 `GATE_RERUN_ONCE=true`）。

- [ ] 写测试：假 `gh` 按 endpoint 读 fixture，支持 `.seq.N` 按读取次数切换；覆盖 ADR §2.1 每一条与 §2.4 的 tag 情形（30 多个用例，见 ADR §6）
- [ ] 跑测试确认失败
- [ ] 实现脚本：参数校验 → 解析 tag（归一 SHA、读标记）→ force 直接放行 → 主循环（找 run / 判 main / 等结束 / 判 job / 重跑一次）
- [ ] 跑测试确认通过；做变异检查（skipped 当通过、取最早 run、去掉 event 过滤、去掉 attempt 判定、标记子串匹配、去掉 tag 指向校验、去掉重名判定，各自被某个用例杀死）
- [ ] 在 macOS `/bin/bash`（3.2）与 bash 5 下各跑一遍
- [ ] 提交

### Task 2：jar 冒烟 action `verify-jar-contents`（platform）

**Files:**
- Create: `.github/actions/verify-jar-contents/{action.yml,verify-jar-contents.sh,verify-jar-contents.test.sh}`
- Modify: `.github/workflows/ci.yml`

**Interfaces:** 输入 `jars`（换行分隔 glob）、`entries`（换行分隔 ERE，缺省 `\.class$`）、`version`（可传 `vX`，去掉一个前导 v）、`working-directory`。忽略 `-sources`、`-javadoc`、`-plain`；每个 glob 至少匹配一个主 jar；空输入失败。

- [ ] 写测试（python3 zipfile 造 jar）：通过、缺条目、只有 javadoc、版本不符、tag 形式版本、多 jar 中一个不合格、空输入
- [ ] 实现并通过；用本机 `~/.m2` 中 1.0.47 的真实 jar 核对每仓的条目模式
- [ ] 提交

### Task 3：列车 preflight 与 forceTests 透传（platform）

**Files:**
- Create: `scripts/release-plan/preflight-main-ci.sh`、`scripts/release-plan/preflight-main-ci.test.sh`
- Modify: `.github/workflows/release-train.yml`（输入 `forceTests`；preflight 新步骤；两段 train 的 env 加 `FORCE_TESTS`）
- Modify: `scripts/release-plan/run-train.sh`（`FORCE_ARGS`，只在 true 时加 `-f forceTests=true`）
- Modify: `scripts/release-plan/train-reuse.test.sh`（新增 force-tests 用例；manifest 用例断言默认不带该输入）
- Modify: `.github/workflows/release.yml`（本仓发布接入门禁；create-tag 的 annotated tag 与已有 tag 校验）
- Create: `.github/main-ci-gate.json`

**Interfaces:** preflight env：`LAYERS`、`MAIN_SHAS`、`CLONE_ROOT`（`/tmp`，即 drift gate 的克隆）、`DRY_RUN`、`FORCE_TESTS`、`GH_TOKEN`（`CROSS_REPO_TOKEN`）、`WONTLOST_TOKEN`。

- [ ] 写测试：全绿（service 不查、同仓两个 step 只查一次）、未接入仓不拦、任一红即停且非 dryRun 重跑一次、pin 竞态重跑后变绿、dryRun 不重跑、forceTests 遇未接入仓拦 / 全接入则跳过、缺 SHA 与非法 LAYERS fail-closed
- [ ] 实现 preflight（各仓后台并行，日志按仓分组，`::error::` 在组外重报）
- [ ] 改 release-train.yml 与 run-train.sh；跑全部既有测试（`run-train`、`train-reuse`、`train-api-pin`、`check-artifact`）
- [ ] 提交

### Task 4：Java 引擎仓接入（core、runtime、validation、locales、hi、truffle）

**Files（每仓）:** Modify `.github/workflows/release.yml`；Create `.github/main-ci-gate.json`

**Interfaces（每仓 release.yml）:**
- `workflow_dispatch.inputs.forceTests`（boolean，缺省 false）
- `create-tag`：env `FORCE_TESTS`；新建 tag 时 forceTests → `git tag -a "$TAG" -m "release-gate: force-tests"`；tag 已在 HEAD 且 forceTests 而已有 tag 无标记 → 失败并给出恢复办法
- `publish`：`permissions.actions: read`；checkout 之后 `id: ci-gate` 门禁；构建命令按 `steps.ci-gate.outputs.force-tests` 选择是否 `-x test`；构建之后 `verify-jar-contents`

| 仓 | 构建命令（缺省 / force） | 额外 |
|---|---|---|
| core | 不变（`build -x test`） / 先检出 hi 并 `publishToMavenLocal`，再 `./gradlew test` | 注释改为「由门禁强制」 |
| runtime、validation | `build -x test` / `build` | — |
| locales | `build -x test` / `build`（`working-directory: aster-lang-locales`，门禁 `config` 指向子目录） | 三个模块的 jar |
| hi | `build verifyLexiconKeywordParity -x test` / 去掉 `-x test` | 补 tag ↔ catalog 版本守卫 |
| truffle | `build -PexcludeBenchmarks=true -x test` / 去掉 `-x test` | 补 tag ↔ catalog 版本守卫 |

- [ ] 逐仓改动，`python3 -c 'import yaml'` 解析通过
- [ ] 本地（`--offline`，必要时先把 platform / core / runtime / locales / hi 的 1.0.48 `publishToMavenLocal`）跑缺省构建命令，再对 `build/libs` 跑 jar 冒烟
- [ ] 每仓一个提交

### Task 5：test 仓接入（JVM 与 npm 两个发布 workflow）

**Files:** Modify `.github/workflows/release-maven.yml`、`.github/workflows/release-npm.yml`；Create `.github/main-ci-gate.json`

**Interfaces:** 门禁放在既有「Resolve publish gate」之后，`if: steps.gate.outputs.match == 'true'`；npm job 因 `defaults.run.working-directory: packages/js`，门禁 `config` 用 `${{ github.workspace }}/.github/main-ci-gate.json`。force：JVM `./gradlew test`，npm `pnpm test`。JVM 增加 `./gradlew jar` 与 jar 冒烟（`corpus/tier1-equivalence/`、`cloud/aster/**.class`）。

- [ ] 改动、YAML 解析、本地 `./gradlew jar` 加 jar 冒烟
- [ ] 提交

### Task 6：ts 接入

**Files:**
- Modify: `.github/workflows/release.yml`（`ci` → `gate` + `full-tests`；publish 的 `needs` 与 `if`；publish 去掉 core / test / locales 检出与 loader 构建）
- Modify: `package.json`（`prepublishOnly` = `clean && build && node scripts/verify-browser-entry.mjs && node scripts/verify-package.mjs`）
- Modify: `.github/workflows/ci.yml`（test job 加 `node scripts/verify-package.mjs`）
- Create: `scripts/verify-package.mjs`、`.github/main-ci-gate.json`

**Interfaces:**
- `gate` job：`permissions: {contents: read, actions: read}`，输出 `force-tests`
- `full-tests` job：`needs: gate`，`if: needs.gate.outputs.force-tests == 'true'`，步骤即原 `ci` job
- `publish`：`needs: [gate, full-tests]`，`if: !cancelled() && gate 成功 && ((force && full-tests 成功) || (!force && full-tests skipped))`
- `verify-package.mjs`：退出码 0/1；检查入口存在、相对 import 不出包、无副作用 exports 可 `import()`（`./cli`、`./core`、`./lsp/server` 只查存在）

- [ ] 在无兄弟仓的隔离副本（`git archive`）里验证 `pnpm run build` 与新 `prepublishOnly`
- [ ] 变异验证 `verify-package.mjs`：删 exports 目标、加包外相对 import、入口模块抛错，各自失败
- [ ] 提交

### Task 7：ADR 修订 A2 与本计划（aster-api docs PR）

**Files:** Create `docs/adr/0053-amendment-2-release-requires-main-ci.md`、`docs/superpowers/plans/2026-11-16-release-requires-main-ci.md`；Modify `docs/adr/0053-release-train-artifact-reuse.md`（头部「修订」一行加 A2）

- [ ] 写 ADR（PROPOSED）与计划；ADR 中的历史时长全部来自 `gh api …/jobs` 实测，§7 的未来时长标为估算；「main CI 红」按必需 job 而不是 run 结论描述（test 1.0.47/1.0.48 只是非必需的 parity 红）
- [ ] draft PR，不合并

### Task 8（可选）：aster-api CI 拆成并行 job

**Files:** Modify `.github/workflows/ci-build.yml`（不改 `ci.yml` 的汇总 job 与必需检查名）

**Interfaces:**
- reusable workflow 输入不变（`runs-on`）；输出 `guard-bypass` 改为 `jobs.guard-bypass.outputs.guard-bypass`
- job：`checks`（pin 一致性、image-pin 门控测试、actionlint、Quarkus plugin/BOM 锁步）；`guard-bypass`、`unit`、`integration` 并行，各自 `setup-aster-build`、PG / Redis service；`unit`、`integration` 上传 `build/jacoco/*.exec`（retention 1 天）；`verify`（needs `unit`、`integration`）下载 exec 后做覆盖率聚合与校验、`build -x test -x integrationTest -x guardBypassTest`、失败测试摘要
- 每个 test job 上传自己的 `test-results-<job>`

- [ ] 改动；`ci.yml` 的 `build-local` / `build-hosted` 的 `result` 语义（被调 workflow 全部 job 成功才 success）不变，`ci-result` 与 `Guard bypass suite (#466)` 无需改
- [ ] draft PR 自身的 CI 即验证；对比拆分前后 hosted 时长
- [ ] 提交

### Task 9：评审、PR 与上线

- [ ] 每个 PR Codex 审查至 ≥ 90
- [ ] 开 draft PR（正文：eco-1.0.48 之后才可合并、合并顺序、测试证据、Codex 评分）
- [ ] 用户接受 ADR 后按顺序合并；eco-1.0.49 先 dryRun，确认 preflight 的 main CI 门禁对每个仓给出结论
