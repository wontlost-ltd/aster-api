# ADR 0023：多模块发布治理（消除版本漂移）

- 状态：ACCEPTED（用户拍板：诊断+直接改 release 流程，覆盖全 4 层，4 阶段全做）
- 日期：2026-06-27
- 决策者：用户（"如何解决多模块发布的版本漂移，这次 aster 发布太恐怖了"）
- 相关：ADR 0012（Gradle version catalog / aster-lang-platform）、`version-catalog`、`nightly-equivalence-broken-2026-06`、`hindi-full-support`（catalog 级联反噬教训）

---

## 1. 背景与问题

aster-lang 是 8 仓多模块 Gradle/npm 生态（platform / core / truffle / runtime / validation / locales / hi / test，消费方 aster-api、aster-cloud）。本次 Plan-D 别名发布（platform 1.0.6 + 引擎 1.0.4 + ts 1.0.4 npm + cloud/api 上线）暴露了系统性的版本漂移痛点，用户评价"发布太恐怖"。

**问题本质**：ADR 0012 的 version catalog 收敛了"依赖谁的什么版本"（consumer 用 `asterLibs.*`），但**没收敛"这次发布谁、按什么顺序、到什么版本"**——后者仍是**命令式手工流程**：人脑记依赖图、手工同步 pin、merge-all-then-tag。

## 2. 现状实证（2026-06-27，跨 8 仓实读）

- **platform**：self version=1.0.6，catalog 内容版本 `asterLang`=1.0.4（两个版本刻意分离，但易混淆）。
- **5 处硬编码 pin**：aster-api / core / truffle / locales / hi 各自 `from("cloud.aster-lang:aster-lang-platform:1.0.6")`。catalog 是单源，但"哪个 platform 版本"仍是 N 处手工散点。
- **引擎 self version** 全 1.0.4（core/truffle/runtime/validation）。runtime/validation/test 零 platform 依赖。
- **core/release.yml**：tag-push(v*) 触发，有 `tag == build.gradle.kts version` 一致性 gate（#26）；人肉顺序 platform→core→locales→test publishToMavenLocal→publish。
- **checkout-sibling action**：仅 PR 时按 `github.head_ref` 匹配兄弟仓同名分支，tag-push fallback main。
- **latent 遗留**：`truffle/settings.gradle.kts:34` 本地 composite 仍 loop 归档的 `aster-lang-en/zh/de`（被 `if(dir.isDirectory())` 守卫故休眠，应改为 aster-lang-locales）。

## 3. 四类漂移面

| 层 | 现状 | 漂移点 |
|----|------|--------|
| 版本来源 | catalog 单源 ✅ 但 5 处硬编码 platform pin | 手工同步散点；catalog 解耦 core 曾反噬（en/zh/de 硬编码 core pin 强制全级联） |
| 发布顺序 | 人脑依赖图 platform→core→en→zh/de→truffle→api | 错序即 publish 挂 |
| 跨仓 CI（chicken-egg） | checkout-sibling 解 PR-CI；tag-push 走 main | 跨仓变更无法原子发布 → 必须 merge-all-then-tag |
| 一致性 gate | tag==version ✅（单仓） | parity manifest 版本 drift（1.0.6 vs 1.0.3 曾靠 admin override）、pin drift 无自动检测 |

## 4. 决策：4 层联动方案（全用官方机制，禁自研）

> 架构优先级铁律：复用官方 SDK/社区成熟方案，禁自研。每层都选官方工具。

1. **版本来源单一化 → Renovate（共享 preset + 每仓 extends）**：`customManagers`(regex) 识别各仓 `from('...platform:X')`（**单双引号都要匹配**：aster-api 是 Groovy 单引号，truffle 是 Kotlin 双引号）。配置集中在一个**共享 preset**（org 级或 `.github` 仓），5 个 pin 仓（api/core/truffle/locales/hi）只 `extends` 它——避免把治理配置本身复制 5 份再漂移。platform 发新版 → Renovate 给**每个** pin 仓各开一个同组 PR（`groupName` 只在单仓内合并，GitHub PR 不能跨仓；真正的跨仓批次由阶段3 的 release train 统一处理）。承认 multi-repo 天然 N pins，但把 N 次人工同步变成自动 PR。GH Packages 私有 catalog artifact 的读认证**由 Renovate App/org bot 的 platform token 自动提供**（`*.pkg.github.com` 自动生成 hostRules），不在 repo 配置放 token；若包不可见则在 org/bot 层配 hostRules+token。
2. **发布顺序编排 → GitHub Actions orchestrator DAG**：platform 仓新建 `release-train.yml`（workflow_dispatch，读 release-plan.json），按 `releaseOrder`（artifact 依赖序）串联各仓 release workflow。**执行层须按 `(repo, releaseWorkflow)` coalesce**：同仓多 artifact 若共用一个 release.yml（locales/hi 的 Maven+npm 同 tag 发），orchestrator 只 dispatch 该 workflow 一次（传 artifactIds 列表），绝不按 artifact 逐个 dispatch（否则重复 publish）；test 例外（release-maven.yml / release-npm.yml 两个独立 workflow，可真正并行）。顺序由 DAG 固化，每仓保留自己的 tag==version gate。
3. **跨仓原子发布 → release train + version plan 先行**：先合并同名分支 PR（PR-CI 用 checkout-sibling 验证），合并后 orchestrator 在同一 train 打 tag/publish → tag 不再是各仓手工事件，而是 train 产物，解 fallback-main chicken-egg。**短中期不 monorepo**（问题是发布治理不是源码组织）。
4. **防漂移 gate → release-plan 单一事实源 + pin-consistency gate + parity manifest 断言**：`release-plan.json`（platform 仓）= 唯一"本次应发布版本"事实源（artifact 粒度，双制品仓如 aster-lang-test 拆 JVM+npm 分别建模）。各仓 CI 校验 self version / platform pin / catalog asterLang / npm manifest 是否符合 plan。**catalog-derived 仓（locales/hi）的 gate 须同时断言两点**：①version 确实 `findVersion("asterLang")` 派生而非字面量，②`expectedPlatformPin == platformVersion`——否则 pin 到旧 platform 会稳定派生出错误版本（构造上"不可能偏离"只在 pin 正确时成立）。tag==version 保留作单仓最后防线。Gradle dependency locking 留作未来第二阶段。

## 5. 落地阶段（风险递增，每阶段独立交付+可回滚）

- **阶段 0**（low）：本 ADR + 定义 `release-plan.json` 格式（platform 仓）+ 清理 truffle 归档 composite 遗留。
- **阶段 1**（low-med）：Renovate grouped PR。aster-api 已有 dependabot → Renovate 只管 platform pin，dependabot 管其余（最小冲突）。
- **阶段 2**（med）：各仓 release.yml 加 workflow_dispatch/repository_dispatch 入口，**保留** tag-push 兼容与 tag==version gate。
- **阶段 3**（med-high）：orchestrator `release-train.yml` + pin-consistency gate + parity manifest 版本断言。

## 6. release-plan.json 格式（单一事实源，artifact 粒度）

实际文件见 `aster-lang-platform/release-plan.json`。关键：**以 artifact（非 repo）粒度建模**——多个仓同时发 Maven + npm（locales/hi 各发 Maven lexicon jar + npm ui-messages；test 发 JVM + npm），repo 粒度会让 `v1.0.4` tag 在 Maven 过、npm 挂。每个 artifact 有唯一 `id`，`releaseOrder` 用 id。

顶层版本字段：
- **platformVersion**：catalog artifact 自身版本（catalog 内容变更才 bump）= 1.0.6。
- **ecosystemVersion**：catalog 里 `asterLang` 的值 = 引擎 Maven 制品内容版本 = 1.0.4。
- **tsNpmVersion**：aster-lang-ts npm 版本 = 1.0.4。
- **uiMessagesNpmVersion**：locales/hi 的前端文案 npm 包版本（独立 cadence）= 1.0.6。

每个 artifact 字段：`id`(唯一,如 `locales:npm`) / `repo` / `kind`(catalog|maven|npm|service) / `versionSource`(literal|catalog-derived|none) / `expectedVersion` / `expectedPlatformPin`(null=零依赖) / `artifactPath`(多制品仓的子路径) / `releaseWorkflow` / `npmName`。

**实测的双/多制品仓**（Codex 交叉审查揪出）：aster-lang-locales（Maven + `@aster-cloud/ui-messages`）、aster-lang-hi（Maven + `@aster-cloud/ui-messages-hi`）、aster-lang-test（`packages/jvm` Maven + `packages/js` `@aster-cloud/aster-lang-test`）。它们的 npm 版本与 Maven 版本独立（如 ui-messages 1.0.6 vs 引擎 1.0.4）。

## 7. 回滚

保留所有现有 tag-push release workflows 不删 → orchestrator 失败即回退单仓手工 tag。已发布 Maven/npm/Docker 版本不回收，只补 patch 或标 deprecated。Renovate 可单仓禁用。

## 8. 不做（明确排除）

- **Monorepo 化**：原子性最高但迁移成本巨大，问题是发布治理不是源码组织。
- **Gradle composite 一次性发布**：破坏"跨仓走已发布制品"边界。
- **catalog 自引用**：bootstrap chicken-egg，近自研。
- **强制 npm 与 JVM 版本统一**：npm 可独立 cadence（ui-messages 1.0.6 vs 引擎 1.0.4），gate 只校验 manifest 声明而非强行对齐。
- **aster-cloud 不进 release train**：它是 Cloudflare Workers 独立部署，consume 已发布的 npm/Maven 制品，自有部署路径（opennextjs + ArgoCD 管的 aster-api 后端），不由 aster-lang train 发布。

## 9. 复盘：ADR 0024 发版（ecosystem 1.0.6 / platform 1.0.8 / ts 1.0.5）

机制本身有效——`release-train.yml` 的 **preflight drift gate（`check-artifact.py` 跨 13 制品）准确拦截了两处漂移**，但拦截发生在我已手工开了一批 bump PR 之后，过程仍"恐怖"。根因是**流程顺序错误（人）**，不是机制缺失：

- **漂移 A：`aster-lang-test/jvm` 被整仓遗漏**。它是 catalog 成员（`asterLibs.test → asterLang` 版本），core 用 `testImplementation(asterLibs.test)` 自动期望 ecosystem 版本，但其 `packages/jvm/build.gradle.kts` 自带 `version = "..."` 字面量（`versionSource=literal`），**不从 catalog 派生**→生态 bump 时凭记忆手列仓库就会漏掉它。表现为 core/truffle/locales/hi 的 PR CI `build`/`parity` 全红（解析不到 `aster-lang-test:1.0.6` corpus jar）——是 chicken-egg 噪声不是真回归（release publish 走 `-x test` 不消费已发布 corpus）。
- **漂移 B：`aster-api` 的 `settings.gradle` platform pin 仍 1.0.7**。同形：手维护的 `from('...platform:X')` 字面量字符串，bump 时漏改。

**两处都是 `versionSource=literal` / 手维护 pin 字符串**。而 `locales:maven`/`hi:maven`（`versionSource=catalog-derived`，`findVersion("asterLang")`）**零漂移**——它们的版本由 catalog 自动派生，构造上不可能落后。这印证了 §4.4 的设计：**catalog-derived 是消除漂移的正解，literal 是漂移的来源**。

### 立即流程修正（零代码，本次起执行）
1. **任何级联第一步先 `release-train.yml dryRun=true`**：preflight 会输出全 13 制品的 `OK / 漂移` 权威清单（versionSource + expected + pin）。**以这份清单为待 bump 仓的事实源**，而非凭记忆手列。本次正是 dry-run 在真发版前抓出了漂移 B（并由此回查出漂移 A 已补），证明"先 dry-run"足以把"恐怖"降为"机械照单 bump"。
2. **bump PR 的 `build`/`parity` 红**在级联期是预期 chicken-egg（依赖未发布制品），**唯一权威 gate 是 release-train preflight**；这些仓未开 branch protection，确认 diff 仅版本文件后 admin-merge 即可。

### 第二个失败：test 仓 release workflow 不可 dispatch（已修）
真发车（dryRun=false）发到 Layer 1 的 test 步骤报 **HTTP 422 `Workflow does not have 'workflow_dispatch' trigger`**（platform 1.0.8 + core/runtime/validation 1.0.6 已 forward-only 发布）。根因是 **run-train.sh `run_step` 的硬契约**：dispatch 的 workflow 文件 **必须等于** tag-push publish 的 workflow 文件（`wait_publish_run` 查 `workflows/$workflow/runs` 的 `event=push` run）。`core` 等用**单文件 `release.yml`**（`workflow_dispatch` 建 tag + `push:tags` publish 双触发）满足契约；但 **test 仓拆成三文件**（§5 阶段2 为双制品设计）：`release-tag.yml`(dispatch 建 tag) + `release-maven.yml`/`release-npm.yml`(仅 `push:tags` publish，**无 `workflow_dispatch`**)——train dispatch `release-maven.yml` 即 422。

修复（test#46）：镜像 core 单文件双触发，给 `release-maven.yml`/`release-npm.yml` 各加 `workflow_dispatch`（create-tag job 推**自己版本**的 tag）+ 保留 `push:tags`。`test:jvm`(v1.0.6)/`test:npm`(v1.0.3) 各自独立 tag，不被 `coalesce`(按 `repo+releaseWorkflow` 分组)合并。skip-on-mismatch 保留，删冗余 `release-tag.yml`。**结构性边界**（Codex 指出）：若某天两制品同版本→单 tag 同发两制品，第二 step 见 tag 已存在触发 fail-fast。
- **ADR 启示**：§5 阶段2 "各仓 release.yml 加 workflow_dispatch" **必须包含双制品仓的两个 publish workflow**，或 orchestrator 升级支持 dispatch/publish workflow 分离（`dispatchWorkflow`/`publishWorkflow` 字段，保留 release-tag.yml 单 tag 入口）——后者更干净但改全局契约+coalesce 语义，本次取 test 仓 yml 小改。

### 根治：literal 引擎制品迁 catalog-derived（**已实施 2026-06-29**）
把 `versionSource=literal` 的 Maven 引擎制品迁到 `catalog-derived`（`version = extensions.getByType<VersionCatalogsExtension>().named("asterLibs").findVersion("asterLang").get().requiredVersion`），使版本随 catalog `asterLang` 自动派生——漂移面从"N 个跨仓手编字符串"收敛到"catalog 里 1 个 `asterLang`"。**迁移后全 7 个 Maven 引擎制品（core/runtime/validation/test:jvm/truffle/locales/hi）均 catalog-derived = 零漂移**；剩余 literal 仅 platform（catalog 自身=源，必须 literal）+ npm 包（独立 cadence，by design）+ aster-api（service 无版本）。

分两 tier 实施（风险递增）：
- **Tier A（core/truffle）**：catalog 已在其 CI 可解析（checkout-sibling platform + publishToMavenLocal）。改 build.gradle.kts version 行 + release.yml 的版本一致性 gate 从 `sed 字面量` 改 `./gradlew -q properties` Gradle 解析（**非零 CI 改动**——Codex 揪出 release.yml 仍 sed）。core#46/truffle#31/platform#22 合并。
- **Tier B（runtime/validation/test:jvm，零 aster 依赖）**：catalog 原不在其 CI 可解析（validation issue #6 deferred 注释警告"加 catalog 会破 CI"）。解法 = settings 加 `versionCatalogs { from(platform pin) }` + **同 PR 给 ci.yml/release.yml 加 `checkout platform + publishToMavenLocal` bootstrap**（deferred 顾虑随 bootstrap 消解，用 sibling+mavenLocal 路线而非 GH Packages settings 直拉——后者把失败点放最脆的配置期）。test:jvm 特殊：子目录制品（packages/jvm），release-maven.yml 的 create-tag + skip-on-mismatch publish gate 两处 sed 都改 Gradle 解析（publish job 重排=bootstrap 先于 gate，因 skip 路径也要解析版本）。runtime#13/validation#13/test#47/platform#23 合并。
- **check-artifact.py 配套**：①迁移的 artifact `versionSource` literal→catalog-derived + 加 `expectedPlatformPin`；②`settings_platform_pin` 加 `artifactPath` fallback（先查子目录 settings 回退仓根）——test:jvm pin 在 `packages/jvm/settings.gradle.kts`，原逻辑只查仓根读不到（Codex B-fallback 形式：locales:npm/hi:npm 的 artifactPath=ui-messages 子目录无 settings → 回退仓根，行为不变）。
- **合并顺序**：platform 的 plan+脚本 PR **必须先合**（runtime/validation/test CI 用 plain checkout 读 platform main 的 plan；core/truffle 用 checkout-sibling 可同名分支并行，但仍以 plan-first 为准）。先合前各仓 CI 会因 stale plan（仍 literal 却读到 catalog-derived build）报 `gradle literal version None`——合后 rerun 即绿。

pin 字符串（aster-api 的 settings pin、各仓 settings 的 platform pin）仍是手编，继续靠 §4.1 Renovate 自动 PR 兜底——但 pin 漂移会被 catalog-derived gate 的 `expectedPlatformPin==platformVersion` 断言拦截。
