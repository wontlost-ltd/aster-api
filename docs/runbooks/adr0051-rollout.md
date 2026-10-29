# ADR 0051 上线 Runbook：领域词汇按位置解析

## 适用范围

本文是 ADR 0051（`docs/adr/0051-position-aware-vocabulary-resolution.md`）§9「不做向后兼容的落地步骤」的执行手册，覆盖：

- 各仓 PR 的合并顺序与每一步的放行条件；
- 发布列车（生态 1.0.36）与镜像 pin；
- 存量重编译 Job 的触发方式与报告解读；
- 上线后的验证项；
- 回滚（列车回退、用 dump 恢复、删除新增列）；
- 发布后要跟进的事项清单。

不做向后兼容：toolchainId 改为 `abi=1.1;…;resolver=1;casefold=15.1`，source envelope 增加第 5 段，旧 envelope 全部失配。所以两库的存量版本都必须由迁移 Job 重编译并重新封印。现有数据均为测试数据，回滚窗口内产生的新数据不保留（ADR §9 第 4 步）。

## 版本与制品

| 制品 | 现版本 | 本次 | 说明 |
|---|---|---|---|
| platform catalog / 生态 Maven（core、runtime、validation、test:jvm、locales、hi、truffle） | 1.0.35 | **1.0.36** | lockstep：runtime、validation、hi 源码无变更，也重新打 tag |
| `@aster-cloud/aster-lang-ts` | 1.0.37 | **1.0.38** | |
| `@aster-cloud/aster-lang-test` | 1.0.3 | **1.1.0** | `normalizeIr` 改为选项对象，签名不兼容，所以升次版本位。tag 为 `npm-v1.1.0`，aster-lang-test#168 的撞 tag 问题已由 `npm-v*` 前缀解决 |
| `@aster-cloud/ui-messages`（-hi） | 1.0.24 | 1.0.24 | 无变更 |
| aster-api | pin 1.0.35 | pin **1.0.36** | settings.gradle、`setup-aster-build` 的 `sibling-ref: v1.0.36` 都要改 |
| aster-cloud | ts 1.0.37 | ts **1.0.38** | 不在列车内；`package.json` 与 `lsp-package.json` 都要改 |

版本计划已提交在 aster-lang-platform 的 `release-plan.json` 与 `build.gradle.kts`（提交 `chore(release): ADR 0051 版本计划`）。

### 发布说明要点（写进各仓 PR / CHANGELOG）

- 删除解析前的全局文本替换。新增诊断 E800–E807、W800/W801，每个诊断附本地化文案（zh/en；de/hi 文案基础设施缺失，见文末跟进清单）。
- core：`VocabularyEntry` record 构造器有变更；词条注册可能抛 `literal_conflict`。
- core #215（若同版发布）：移除 `f with x, y` 调用形式；构造字段只接受 `set to`；`X with f = v` 改为在编译期报错。
- `Number`、`Boolean`、`String` 不再被接受为类型名，请使用 `Float`、`Bool`、`Text`（R27）。
- test npm 1.1.0：`normalizeIr(node, options)`，options 为 `{ originMode?, includeResolution? }`。
- 内置词汇（finance-loan、insurance-auto）中无父枚举的值补上了父枚举：CreditLevel、LoanType、QuoteStatus、RiskLevel。

## 0. 准备（发布窗口前）

1. **确定发布窗口并冻结编辑**。窗口内 aster-api 已是新解析器，cloud 可能仍是旧代码。窗口内不保存、不审批带词汇的策略。另外，cloud 迁移会在编译期间持有行锁，同一策略并发保存会等待。
2. **避开 secret-rotation 的季度轮换**（1、4、7、10 月的 1–7 日 03:00 UTC）。轮换后 ExternalSecret 最长要 1 小时才刷新，Job 拿到旧密码会失败（2026-10-25 的 aster-cloud-migrate 就是这样失败的）。
3. **记录回滚基线**，写进发布记录：
   - k3s `apps/aster-lang/cloud/image-lock.yaml`、`apps/aster-lang/lsp/image-lock.yaml` 当前的 digest 与 sourceSha；
   - `npx wrangler deployments list --name aster-cloud` 当前最新的部署 ID；
   - 各仓当前 tag：`v1.0.35`、ts `1.0.37`、test npm `1.0.3`。
4. 确认 aster-cloud 的 Worker 只由 CI 发布：`ENABLE_CI_WORKER_DEPLOY=true`，Workers Builds 已关闭，见 k3s `docs/runbooks/release-evidence.md`。这样 Worker 部署会等迁移镜像 pin 合入并 Healthy 之后才进行。

## 1. PR 合并顺序与放行条件

所有特性分支都是 `feat/adr0051-position-aware-vocabulary`。每一步都要等对应仓库 CI 全绿后再合并。

| 步骤 | 仓库 / PR | 放行条件 |
|---|---|---|
| 1 | **aster-cloud PR-A**：`drizzle/0060_policy_version_scopes_json.sql`、`drizzle/0061_resolver_migration_columns.sql`、`drizzle/meta/*` 与 `src/db/schema.ts` 的列定义（必须含 schema.ts，原因见下文）。**推荐先按方案 (a) 在生产手工加列，再合 PR-A** | 方案 (a) 的 DDL 已执行且核实 5 列存在；CI 绿；image-pin PR 合并；ArgoCD PostSync 迁移 Job 成功（drizzle 只补记录）。用下面的 SQL 核实 |
| 2 | **aster-lang-locales** | CI 绿 |
| 3 | **aster-lang-core #215**（`fix/reject-with-call-244`），然后是 **aster-lang-core ADR 0051 PR**（先 rebase 到 #215 之后的 main） | **硬门禁：#215 必须在 1.0.36 列车之前合入 core main**（原因见下文）；两个 PR 的 CI 都绿；rebase 后重跑 tier1 parity |
| 4 | **aster-lang-truffle** | CI 绿。truffle CI 会检出 core main 并 publishToMavenLocal，所以必须在 core 合并之后 |
| 5 | **aster-lang-ts**（含 `package.json` 1.0.37 → 1.0.38 的提交） | CI 绿；parity 测试读取 `../aster-lang-core` 的 error_codes.json，所以必须在 core 之后 |
| 6 | **aster-lang-test**（含 `packages/js/package.json` 1.0.3 → 1.1.0） | CI 绿（tier1 parity：解析、IR、eval 全部一致） |
| 7 | **生态 pin bump PR**：core、runtime、validation、test（packages/jvm）、locales、hi、truffle、aster-api 的 platform pin 1.0.35 → 1.0.36；aster-api 同时把 `setup-aster-build` 的 `sibling-ref` 改为 `v1.0.36` | 沿用 1.0.35 的做法（core#214、api#388 等），在列车前一起合并 |
| 8 | **aster-lang-platform**（`chore(release): ADR 0051 版本计划`） | 本仓 CI：check-artifact、run-train.test.sh、catalog 构建 |
| 9 | **发布列车**（见第 2 节） | 派发前确认 core main 已含 #215（`git log origin/main --oneline \| grep 244`）；preflight 全部通过；所有 tag 与制品都可见 |
| 10 | **aster-api ADR 0051 PR** | **硬门禁：只在列车完成、各兄弟仓 `v1.0.36` tag 与 Maven 制品都已存在之后合并**：api CI 按 `sibling-ref` 检出兄弟仓 tag，tag 存在前 CI 无法变绿。合并后 push 会触发 deploy.yml，经 image-pin 和 ArgoCD 部署；新 pod 启动时由 Flyway 执行 V6.34.0 |
| 11 | **aster-cloud PR-B**（其余全部代码，见第 3 节） | ts 1.0.38 已在 npm 上可见；CI 绿；migrate、lsp 镜像的 image-pin 已合并；Worker 已部署 |
| 12 | **触发迁移 Job**（第 4 节），然后执行 `rollout restart` | api pod 已是新镜像；Worker 是 PR-B；migrate 镜像 digest 已是 PR-B 构建的版本 |

### cloud 迁移列的上线方式（裁决 R41，经 R46 修订）

为什么要先让列落地，再发 PR-B 代码：

- Worker 发布很快，几分钟内就上线；drizzle 迁移要走 migrate 镜像构建 → image-pin PR → ArgoCD PostSync Job 这条链，会晚一些。
- schema.ts 一旦声明了新列，drizzle 对 `"PolicyVersion"` 的整行查询和插入都会带上 `sourceScopesJson` 与 4 个 resolver 列。列不存在时，这些语句全部 500。
- 2026-10-24 起 Worker 由 CI 门控（第 0 节第 4 条），部署会等「Wait for migration applied」。但门控可能被绕过：stalegate 跳过、break-glass 本地部署、`ENABLE_CI_WORKER_DEPLOY` 被关闭。所以不能只依赖门控。

**PR-A 必须包含 `src/db/schema.ts` 的列定义**（R41 原文「PR-A 不含 schema.ts」不可行）：

- cloud CI 的 `build` job 有「Check Drizzle schema drift」检查，比对 schema.ts 与 drizzle/meta 最新快照。PR-A 带 0061 快照而不改 schema.ts，`pnpm db:generate` 会生成删列迁移，build 失败。
- `build-migrate-image` 依赖 `build`。build 失败，迁移镜像不会构建，迁移永远落不了地，形成死锁。
- `scripts/ci/check-migration-split.mjs` 把 `src/db/schema.ts` 与 `drizzle/meta/` 视为迁移的一部分，所以「SQL + meta + schema.ts」仍是纯迁移 PR，不需要 `migration-and-code` 标签。

既然 schema.ts 必须随 PR-A 上线，下面两种方式二选一。

#### 方案 (a)（推荐）：合并 PR-A 之前，先在生产手工执行加列 DDL，零停机

这段 DDL 与 0060、0061 的 SQL 逐句相同，全部是 `ADD COLUMN IF NOT EXISTS`：

- 只改目录（catalog），不重写表：可空列无默认值；`archivedByResolverMigration` 的常量默认值 false 在 PG 11 及以上走 fast default。
- 只短暂持有 ACCESS EXCLUSIVE 锁，所以加了 `lock_timeout`。超时就重试，不要去掉 lock_timeout。

用表 owner 执行。`"PolicyVersion"` 的 owner 是 aster-cloud-db-credentials 的账号，即 drizzle 迁移所用账号：

```sql
\set ON_ERROR_STOP on
SET lock_timeout = '5s';
BEGIN;
-- = drizzle/0060_policy_version_scopes_json.sql
ALTER TABLE "PolicyVersion" ADD COLUMN IF NOT EXISTS "sourceScopesJson" text;
-- = drizzle/0061_resolver_migration_columns.sql
ALTER TABLE "PolicyVersion" ADD COLUMN IF NOT EXISTS "resolverVersion" integer;
ALTER TABLE "PolicyVersion" ADD COLUMN IF NOT EXISTS "resolverMigrationError" text;
ALTER TABLE "PolicyVersion" ADD COLUMN IF NOT EXISTS "archivedByResolverMigration" boolean DEFAULT false NOT NULL;
ALTER TABLE "PolicyVersion" ADD COLUMN IF NOT EXISTS "statusBeforeArchive" "PolicyVersionStatus";
COMMIT;
```

之后合并 PR-A，PostSync migrate Job 会照常运行，结果是空操作加补记录。原因：

- drizzle-orm 的 migrator 只比较 `drizzle.__drizzle_migrations` 中最新一条的 `created_at` 与 journal 的 `when`。0060（1793236549259）、0061（1793242575570）比库里最新的 0059 新，所以会执行这两个文件。
- 执行时每条 `ADD COLUMN IF NOT EXISTS` 只报 NOTICE「already exists, skipping」，不报错。随后写入两条迁移记录，hash 取 SQL 文件内容，与是否手工加过列无关。
- 之后再次 sync 时，库里最新记录已是 0061，不再执行任何迁移。不会失败，也不会重复应用。

本地已用 PG 16 加上 PR 版本的 migrate 镜像完整演练过一遍：

1. 迁移到 0059；
2. 手工执行上面的 DDL；
3. 跑完整的 drizzle 迁移：5 条 NOTICE 跳过，记录从 61 条增加到 63 条，max(created_at) = 1793242575570；
4. 再跑一次：记录数不变，成功。

#### 方案 (b)：不手工加列，接受一个只由 db-bootstrap 冷启动补列兜底的短窗口

- 窗口：PR-A 的 Worker 已部署、PostSync 迁移尚未落地的这段时间。CI 门控生效时，这个窗口接近 0；门控被绕过时，可能是几分钟到几十分钟。
- 兜底范围：db-bootstrap 的 `ensureSchemaApplied` 只在 dashboard 布局渲染（`src/app/[locale]/(dashboard)/layout.tsx`）和登录路径（`src/auth.ts`）里被调用。只要窗口内任何用户打开一次控制台或登录一次，它就会对全库执行同样的 `ADD COLUMN IF NOT EXISTS`，窗口随即结束。
- 但 PR-A 只含迁移，**补列代码要到 PR-B 才上线**。若选 (b)，要么把 `src/lib/db-bootstrap.ts` 的补列一并放进 PR-A（这样 PR-A 就成了迁移加代码的 PR，必须打 `migration-and-code` 标签并写 `## 迁移窗口容错` 小节），要么接受窗口内完全没有兜底。
- 窗口内会 500 的是所有不经 dashboard、直接读写 `"PolicyVersion"` 的路由：
  - `/api/v1/policies/[id]/execute`、`/api/v1/policies/[id]/versions/[version]/approve`（API key 客户端）；
  - `/api/internal/policy-versions`：aster-api 的 What-If、Cedar 导出、runner 都经它取版本；
  - `/api/policies/[id]`（含版本读写）、`/api/policies/[id]/execute`、`/api/policies/[id]/executions/[execId]/verify-parity`；
  - 以及调用 version-manager 的服务端路径，例如定时回归。
- 页面路由会在渲染 dashboard 布局时先触发补列，所以基本不受影响。

**建议用方案 (a)**：一次 DDL、几秒钟，换来零 500 窗口，而且不依赖门控或兜底是否生效。

PR-A 落地后的核实 SQL（aster_cloud，任何只读账号均可）：

```sql
SELECT column_name FROM information_schema.columns
WHERE table_name = 'PolicyVersion'
  AND column_name IN ('sourceScopesJson','resolverVersion','resolverMigrationError',
                      'archivedByResolverMigration','statusBeforeArchive');
-- 期望 5 行
SELECT id, created_at FROM drizzle.__drizzle_migrations ORDER BY id DESC LIMIT 3;
-- 期望最新两条 created_at = 1793242575570（0061）、1793236549259（0060）
```

### aster-lang-core #215 与本次发布的关系

- #215 修复的问题：Java 引擎把 `X with f = v`（de `mit`，aster-lang-ts#244）回退解析为普通调用。结果是编译通过，运行时报 `Unknown call target`。TS 引擎一直拒绝这种写法。
- 两次迁移都用 Java 编译：aster-api 端点在进程内编译；cloud 脚本则经 aster-api `/api/v1/policies/compile` 编译。所以：
  - **#215 已合入**：这类版本编译失败，被迁移归档，进入分诊；cloud 归入 `triage.invalidConstructForm`，api 归入 `other`。
  - **#215 未合入**：这类版本迁移「成功」并被重新封印，但执行时仍然失败。同时 cloud 保存门禁（TS）会拒绝它们，结果两边不一致。
- 因此 **#215 必须在 1.0.36 列车派发之前合入 core main**，与 ADR 0051 同在 1.0.36 发布，并且先于 core ADR PR 合并。若列车前 #215 还没合入，就推迟列车，不要先发 ADR 0051、再补 #215。本地试合并（ADR 分支 + #215）没有文本冲突。rebase 后要重跑 `./gradlew check` 与 tier1 parity。
- #215 的诊断文案是 "Expected 'set to' in construction"。cloud 分诊的 `invalidConstructForm` 是启发式判断（P005 + 源码正则）。#215 合入后，用一条 `mit f = v` 样例确认 aster-api 返回的诊断码；如果不是 P005，这些行会落到 `otherCompile`，人工归类即可。

## 2. 发布列车

列车执行顺序以 `release-plan.json` 的 `releaseOrder` 为准，同一层可并行：

1. platform
2. core:maven、runtime:maven、validation:maven、test:jvm、test:npm
3. locales:maven、locales:npm、hi:maven、hi:npm
4. truffle:maven
5. ts:npm、aster-api:service

ADR §12 写的是 locales → core/ts → test → api → cloud。这里以仓库实际的 DAG 为准，两者在依赖上不冲突：

- 折叠表生成的产物已提交在 ts 与 core 里（R1），core 发布时不依赖 locales 制品；
- 内置词汇的父枚举修正，测试在 locales 仓自身。

cloud 不在列车内，在第 11 步单独发布。

**本地预检**（纯本地，不发布、不调用 GitHub）：

```bash
cd aster-lang-platform
for id in platform core:maven runtime:maven validation:maven test:jvm test:npm \
          locales:maven locales:npm hi:maven hi:npm truffle:maven ts:npm aster-api:service; do
  python3.13 scripts/release-plan/check-artifact.py --plan release-plan.json \
    --artifact "$id" --repo-root "../<该 artifact 对应的仓库>"
done
python3.13 scripts/release-plan/coalesce.py --plan release-plan.json
bash scripts/release-plan/run-train.test.sh
```

第 7 步的 pin bump 合并之前，除 platform 以外都会报 pin 或 version 漂移，这是预期结果。

**dry run 与正式发布**（会读写 GitHub，由人执行）：

```bash
gh workflow run release-train.yml --repo aster-cloud/aster-lang-platform --ref main -f dryRun=true
# preflight（全仓 check-artifact）与计划打印都正常后：
gh workflow run release-train.yml --repo aster-cloud/aster-lang-platform --ref main -f dryRun=false
```

**放行条件**：

- 每仓都有 `v1.0.36` tag；
- GitHub Packages 中 `cloud.aster-lang.*` 都有 1.0.36；
- npm `@aster-cloud/aster-lang-ts@1.0.38` 可见；
- npm `@aster-cloud/aster-lang-test@1.1.0` 可见，tag 为 `npm-v1.1.0`。

**aster-api 特性 PR 的门禁**：只在本次列车完成、各兄弟仓 `v1.0.36` tag 与 Maven 制品都已存在之后合并（第 1 节第 10 步）。列车 preflight 只要求 api main 的 pin 已是 1.0.36，这由第 7 步的 pin bump PR 满足，不需要特性代码先进 main。

列车第 5 层的 aster-api:service 部署的是当时 api main 上的代码。按上表，那时 ADR PR 还没合并，部署的是旧代码配新 pin，这是无害的重复部署。第 10 步合并 ADR PR 后，会再走一次 deploy.yml → image-pin → ArgoCD。

## 3. cloud PR-B：依赖切回正式版本（裁决 R3）

开发期 cloud 用的是本地包 `file:.e2e-local/aster-lang-ts-local.tgz`。PR-B 合并前要完成以下几项：

1. `package.json`、`lsp-package.json`、`migrate-package.json` 三处都改为 `"@aster-cloud/aster-lang-ts": "1.0.38"`，写精确版本。`scripts/check-lsp-pin.mjs` 逐字比较这三处，auto-upgrade 的 bump-pins 也会一并改写它们。
2. 执行 `pnpm install` 重新生成 `pnpm-lock.yaml`，确认锁文件里不再有 `file:.e2e-local`。
3. `node scripts/check-lsp-pin.mjs` 退出码为 0。开发期已知的 4 个失败用例是 check-lsp-pin 的 lsp 与 migrate 两项，各在 on-prem 和 saas 下失败一次，切换后应当全部消失。`pnpm test:run` 应全绿。
4. **迁移镜像已包含 resolver 迁移脚本**（cloud 提交 `chore(migrate-image): 迁移镜像包含 resolver 迁移脚本（ADR 0051）`，随 PR-B 合入）：
   - `Dockerfile.migrate` 按 `migrate-package.json` 精确钉版、用 npm 安装依赖：aster-lang-ts、dotenv、prom-client、drizzle-orm、postgres、tsx 等；
   - 拷入 `tsconfig.json`（tsx 靠它解析 `@/` 别名）、`src/` 和 `scripts/resolver-migration.ts`；
   - drizzle 迁移的 CMD 不变。

   k3s Job 第 3 步的命令与镜像内入口逐字一致：`node --conditions=import --import tsx scripts/resolver-migration.ts`（workingDir `/app`）。

   本地已用 podman 构建验证，tag 只打在本地。构建时把 aster-lang-ts 临时指向开发期的 tgz，因为 1.0.38 还没发布。在只读根文件系统、uid 1000、`/tmp` 为 tmpfs 的条件下：
   - `--help` 正常输出；
   - 缺少环境变量时以退出码 2 拒绝运行；
   - 对本地 PG 先用该镜像的默认 CMD 跑完全部 drizzle 迁移，再跑 resolver 迁移：空表时 processed=0，退出码 0；
   - 插入一行后，脚本向桩服务发出带签名的 `/api/v1/policies/compile`，桩返回 503，该行被判为 infrastructure，退出码 1，行未被改动。

   镜像约 640 MB。
   触发 Job 前，核对 k3s image-lock 里 aster-cloud-migrate 的 sourceSha 是含该提交的 main。
5. **关掉 auto-upgrade 自动开出的升级 PR**。ts 1.0.38 发布后，`auto-upgrade.yml` 会按 npm latest 自动开 `chore(deps): upgrade @aster-cloud/aster-lang-ts to 1.0.38` 之类的 PR。旧 main 搭配新引擎会失败（全局替换 API 已删除），不能合并。依赖升级由 PR-B 承担。aster-dev 也用同一份升级脚本，那边也要拦住或同步适配。

## 4. 存量重编译 Job

### 4.1 时机

满足以下全部条件后立即触发：

- aster-api 的 pod 都已是新镜像，`kubectl -n aster-cloud rollout status deploy/aster-api` 完成；
- Worker 已部署 PR-B；
- migrate 镜像 digest 是 PR-B 的构建，并且已合入 k3s。

Job 结束后执行：

```bash
kubectl -n aster-cloud rollout restart deploy/aster-api
kubectl -n aster-cloud rollout status deploy/aster-api
```

原因：迁移端点只失效处理请求那个 pod 的缓存（`policy-compile-by-version`、`CompiledPolicyCache`），其它 pod 的缓存条目最长保留约 1 小时。迁移前有 core_json 的行，可能已被其它 pod 以旧 IR 缓存。rollout restart 会清空所有 pod 的缓存。

cloud 侧：Job 在 k8s 中运行，无法访问 Workers KV（`getKV()` 返回 null），因此 `policy:v4:<id>` 条目不会被主动失效，最长 1 小时后自然过期。Job 结束后 1 小时内，执行页可能还显示旧状态，例如被归档的版本仍可执行。验证第 5 节的复现时，请用新建的策略，或者等满 1 小时。

### 4.2 触发

资源位于 k3s `apps/aster-lang/cloud/resolver-migration-cronjob.yaml`，是一个 suspend 的 CronJob，ArgoCD sync 不会运行它。

```bash
# 先确认没有正在跑的同类 Job（两侧迁移都幂等，但重复跑会产生两份备份）
kubectl -n aster-cloud get jobs -l app=aster-adr0051-resolver-migration
JOB=aster-adr0051-resolver-migration-$(date +%s)
kubectl -n aster-cloud create job "$JOB" --from=cronjob/aster-adr0051-resolver-migration
kubectl -n aster-cloud logs -f "job/$JOB" --all-containers --prefix
```

Pod 内按以下顺序执行，任何一步失败 Pod 即失败，不自动重试：

1. **dump**（PG 16.4 客户端）：
   - 先确认两库的新列已存在，缺列时以退出码 2 拒绝运行；
   - 对 aster_api 的 `policy_versions`、`policy_artifacts` 和 aster_cloud 的 `"PolicyVersion"`、`"UserVocabularySnapshot"` 做 `pg_dump -Fc`，同时导出逐行 CSV；
   - 写入 PVC `aster-adr0051-migration-backup` 下的 `/backup/adr0051/<UTC 时间戳>/`，附带 `pending-before.txt` 和 `SHA256SUMS`。
   - aster_api 用 `aster-api-migrator-credentials`（aster_migrator，也就是 Flyway 用的表 owner）导出，只读使用。原因：`pg_dump -t policy_versions` 会一并导出表自带序列 `policy_versions_id_seq` 的状态，执行 `SELECT last_value FROM …_seq`，这需要序列的 SELECT 权限。运行时账号 aster_api_user 只有表的增删改查和序列 USAGE 权限，pg_dump 会报 `permission denied for sequence`。
     - 本地 PG 16 用同样的权限布局验证过：运行时类角色失败；owner 类角色成功。
     - 不改用 `--data-only` 加补授权，是为了不为一次备份去改生产权限。
   - aster_cloud 的表 owner 就是 aster-cloud-db-credentials 的账号，直接使用。
2. **api-migration**：
   - 调用 `POST /api/v1/admin/resolver-migration/run?batchSize=50`。签名用 admin HMAC，密钥是 `aster-api-plan-gate-credentials/hmac-key`；
   - 直连集群内地址 `http://aster-api.aster-cloud.svc.cluster.local`；
   - 端点是同步的，一次处理完所有待迁移行。Job 按轮调用，`processed == 0` 或 `succeeded == 0` 时停止，最多 5 轮；
   - 结果写入 `api-report.json`。
3. **cloud-migration**：
   - 执行 cloud 迁移脚本，`--report cloud-report.json`；
   - 然后打印汇总，并写入 `SUMMARY.txt`。

### 4.3 读报告

日志末尾的汇总示例：

```
aster-api policy_versions: rounds=2 succeeded=… remainingFailed=… (E8xx=… integrityMismatch=… other=…)
aster-cloud "PolicyVersion": processed=… succeeded=… failed=… exit=…
  triage: e8xx=… invalidConstructForm=… untypedFieldAccess=… vocabularyScopeByUser=… integrityMismatch=… otherCompile=… infrastructure=…
```

**aster-api 侧**（`api-report.json` 的 `triage`）：

| 分组 | 含义 | 处理 |
|---|---|---|
| `e8xx` | 依赖旧全局替换的本地化策略。api 版本不带词汇，按设计编译失败并被归档（`archived_by_resolver_migration = true`） | 这是设计内结果。用户改写，或在 cloud 侧用词汇重建 |
| `integrityMismatch` | `pre-migration integrity mismatch`：旧封印对不上（篡改证据，或 envelope 有值但工具链串为空） | **行没有被归档，也没有被改动**。每次重跑都会再报，必须人工调查 |
| `other` | 其它编译失败，例如 #215 之后的 `with f = v`，以及基础设施错误（数据库异常等，这类行不会被归档） | 逐条看 `error`。基础设施类在修复后重跑即可 |

**cloud 侧**（`cloud-report.json` 的 `triage`，脚本 `--help` 里有同样的说明）：

| 分组 | 含义 | 处理 |
|---|---|---|
| `e8xx` | 除 E801 以外的 E8xx | 归档；用户修词汇或改写策略 |
| `invalidConstructForm` | `X mit f = v`（P005 + 源码正则，启发式） | 归档；改写成 `setze … auf` |
| `untypedFieldAccess` | E801：未标类型的参数上访问本地化字段（R42 第 4 条） | 归档；给参数加类型注解 |
| `vocabularyScopeByUser` | 按用户分组的非法词条，例如字段或枚举值缺少 parent（`field_parent_required`） | 通知词条所有者补上 parent，然后重跑 |
| `integrityMismatch` | 旧封印对不上。**已知误报**有两类：一是创建后用 `updateVersionSource` 改过源码的草稿（envelope 仍是创建时的值）；二是 locale 不在候选集合里的行 | 行没有被改动；逐条人工复核 |
| `otherCompile` | 其余编译失败 | 逐条看 |
| `infrastructure` | 401、403、404、413、5xx、传输错误、词汇加载失败等，行没有被改动 | 修好配置后重跑。脚本以退出码 1 结束，Job 标记为失败 |

**退出码与重跑**：

- cloud 脚本的退出码：0 = 每一行都有结论；1 = 存在基础设施故障；2 = 配置缺失或脚本错误。
- **重跑是幂等的**：成功行会被跳过；失败行（`resolverVersion` 为 NULL）会被重试；行在加锁后会复查。按 4.2 重新 `create job` 即可，每次都会生成一份新的备份目录。
- **如果多次重跑仍以退出码 1 结束，而且 `infrastructure` 里一直是同一批行，说明不是瞬时故障**，必须人工排查（R45 遗留项），不要无限重跑。
- 结构上可预期的失败（E8xx 等）不会让 Job 失败，Job 的成功与否只反映基础设施状态。

**收尾**：

- 把整个运行目录拷出集群留档。PVC 用的是 local-path，属于节点本地盘：

  ```bash
  # Job 的 Pod 已结束，不能 exec；临时起一个挂同一 PVC 的 Pod（RWO，Job 结束后才能挂）
  kubectl -n aster-cloud run adr0051-backup-reader --restart=Never --image=docker.io/library/busybox:1.37 \
    --overrides='{"spec":{"volumes":[{"name":"b","persistentVolumeClaim":{"claimName":"aster-adr0051-migration-backup"}}],"containers":[{"name":"r","image":"docker.io/library/busybox:1.37","command":["sleep","3600"],"volumeMounts":[{"name":"b","mountPath":"/backup"}]}]}}'
  kubectl -n aster-cloud wait --for=condition=Ready pod/adr0051-backup-reader
  kubectl -n aster-cloud cp adr0051-backup-reader:/backup/adr0051 ./adr0051-backup
  kubectl -n aster-cloud delete pod adr0051-backup-reader
  (cd ./adr0051-backup/<时间戳> && sha256sum -c SHA256SUMS)
  ```

- 预期：失败数与分诊列表一致，只包含刻意构造的冲突样例和已知的本地化测试策略。
- 副作用提醒：relink 会改写后继行的 `prevHash`、`sourceHash`。如果有 secure-execute 客户端固定了旧的 sourceHash，它会收到 `HASH_MISMATCH`，需要刷新。

## 5. 验证

### 5.1 ADR §1 四个复现（生产编辑器与执行页）

复现样例在 aster-lang-test 的 `corpus/tier1-parity/vocab-de/` 下。每个样例的 `*.vocab.json` 是对应词条，`inputs/*.cases.json` 是执行输入与期望输出。在 cloud 词汇页按 vocab.json 录入词条（德语），新建策略并粘贴 `.aster` 源码：

| §1 复现 | 样例 | 期望 |
|---|---|---|
| struct `Stufe` 与 field `stufe` 并存 | `level-type-and-field` | 保存成功，不出现 `无法解析 'Definelevelas'`；执行结果与 cases 一致 |
| 规则 `hoch` 与枚举成员 `Hoch` | `enum-member-vs-rule` | `gib zurueck Stufe.Hoch.` 不报 E003；入口 `einstufen` 输入 11 返回 `Level.High`，输入 3 返回 `Level.Low` |
| 参数 `fahrer` 与 struct `Fahrer` | `level-type-and-field`（`fahrer als Fahrer`） | 参数不会被改名，解析成功 |
| 同名本地化字段对应不同 struct 的不同字段 | `same-field-two-structs` | `fahrer.alter` 与 `wagen.alter` 各自解析；执行结果与 cases 一致 |

另外检查：

- 编辑器悬停与补全按角色显示；
- 写一个故意的歧义（例如枚举成员与规则同名，且没有期望类型）会给出 E804，并带出词条与词汇页链接。

### 5.2 工具链与 envelope

aster_api（只读）：

```sql
SELECT source_toolchain_id, resolver_version, count(*) FROM policy_versions GROUP BY 1, 2 ORDER BY 3 DESC;
-- 成功行：abi=1.1;core=1.0.36;…;resolver=1;casefold=15.1，resolver_version=1
SELECT id, status, resolver_migration_error FROM policy_versions WHERE resolver_version IS DISTINCT FROM 1;
-- 应与 api-report.json 的剩余失败一致
```

aster_cloud（只读）：

```sql
SELECT "sourceToolchainId", "resolverVersion", count(*) FROM "PolicyVersion" GROUP BY 1, 2 ORDER BY 3 DESC;
-- 成功行：abi=1.1;core=ts;…;resolver=1;casefold=15.1
SELECT count(*) FROM "PolicyVersion" WHERE "resolverVersion" = 1 AND "sourceScopesJson" IS NULL;
-- 期望 0：成功行都已回填 scopesJson，无词汇时为 ""
```

其它检查项：

- 新保存一个带词汇的版本：`sourceScopesJson` 非空，`resolverVersion = 1`，envelope 为 5 段；
- 审批之后修改词条，执行结果不变（执行用的是冻结快照）；
- What-If、Cedar 导出、规则回归各跑一条带词汇的用例，都能通过。

### 5.3 parity

- aster-lang-test 的定时 parity 记录（`ir-history.csv`、`eval-history.csv`）在 1.0.36 之后仍为 `parse=1.0000 eval=1.0000 ir=1.0000`；
- 如需本地复核，按 aster-lang-test README 用 1.0.36 tag 跑 tier1 parity：期望解析 246、拒绝 37、IR 246、eval 782 全部一致。

## 6. 回滚

ADR §9 第 4 步：列车整体回到上一 tag，用第 4 步的 dump 恢复两库对应表，再删除新增列。

已发布的 Maven、npm 制品无法收回。「回到上一 tag」的含义是：消费方的 pin 回到 1.0.35 / ts 1.0.37，然后重新部署。

**必须按以下顺序执行**：先回滚代码再删列。否则新代码遇到缺列会 500；cloud 的 db-bootstrap 冷启动补列和 migrate PostSync 也会把列重新加回来。

### 6.1 回滚代码

1. **aster-api**：在 main 上 revert ADR 0051 PR 和 pin bump PR，platform pin 与 `sibling-ref` 回到 1.0.35 / v1.0.35。deploy.yml 会重新构建，image-pin 合入后 ArgoCD 部署。
   - 不要在 k3s 里手工把 digest 改回旧值：verify-image-pin 的 freshness 检查要求 sourceSha 等于 aster-api main 的 HEAD，手工 pin 不会通过。
   - 旧镜像里没有 V6.34.0。对 Flyway 来说，这个版本号高于本地最新的迁移，属于 future migration，默认会被忽略，所以 pod 能正常启动。
2. **aster-cloud**：
   - 先 revert PR-B（代码与依赖）。CI 通过后 Worker 自动部署旧代码。紧急时可按 cloud `docs/RELEASE.md` 的 break-glass 流程执行 `wrangler rollback`，并登记。
   - 再 revert PR-A（0060、0061、meta、schema.ts）。revert PR-A 是纯迁移 PR，CI 漂移检查一致；新 migrate 镜像不再包含 0060/0061，PostSync 不会补列。
3. **引擎仓**：revert 各仓的 pin bump，以及 ts、test 的版本提交。cloud 的依赖回到 1.0.37（revert PR-B 已经包含这一步）。

### 6.2 用 dump 恢复数据

在**删列之前**执行：备份 CSV 包含新列，临时表的列序必须与当前表一致。

这些表被大量外键引用，不能整表 DROP 或 TRUNCATE，只能按 id 回写迁移改过的列。先把备份目录从 PVC 拷到能连库的位置，在该目录下执行 psql。

aster_api（用 aster_migrator 账号）：

```sql
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE pv_backup (LIKE policy_versions) ON COMMIT DROP;
\copy pv_backup FROM 'api_policy_versions.csv' WITH (FORMAT csv, HEADER)
UPDATE policy_versions p SET
  core_json              = b.core_json,
  source_envelope_sha256 = b.source_envelope_sha256,
  source_toolchain_id    = b.source_toolchain_id,
  prev_hash              = b.prev_hash,
  status                 = b.status,
  lock_version           = p.lock_version + 1
FROM pv_backup b
WHERE p.id = b.id;
CREATE TEMP TABLE pa_backup (LIKE policy_artifacts) ON COMMIT DROP;
\copy pa_backup FROM 'api_policy_artifacts.csv' WITH (FORMAT csv, HEADER)
UPDATE policy_artifacts a SET content = b.content, content_sha256 = b.content_sha256
FROM pa_backup b
WHERE a.id = b.id;
-- 窗口内新建、备份里没有的行：只列出，人工决定（ADR §9 第 4 步：回滚窗口内的新数据不保留）
SELECT id, policy_id, version, status, created_at FROM policy_versions
WHERE id NOT IN (SELECT id FROM pv_backup) ORDER BY id;
COMMIT;
```

aster_cloud（用 aster-cloud-db-credentials 的账号，即表 owner）：

```sql
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE pv_backup (LIKE "PolicyVersion") ON COMMIT DROP;
\copy pv_backup FROM 'cloud_PolicyVersion.csv' WITH (FORMAT csv, HEADER)
UPDATE "PolicyVersion" p SET
  "sourceEnvelopeSha256" = b."sourceEnvelopeSha256",
  "sourceToolchainId"    = b."sourceToolchainId",
  "prevHash"             = b."prevHash",
  "sourceHash"           = b."sourceHash",
  status                 = b.status
FROM pv_backup b
WHERE p.id = b.id;
SELECT id, "policyId", version, status FROM "PolicyVersion"
WHERE id NOT IN (SELECT id FROM pv_backup) ORDER BY "policyId", version;
COMMIT;
```

几点说明：

- 迁移不会改写 `"UserVocabularySnapshot"`，它只作为回滚点留档。只有窗口内的审批产生了需要丢弃的快照时，才对照 `cloud_UserVocabularySnapshot.csv` 处理。
- `aster_api.dump`、`aster_cloud.dump`（`-Fc`）是完整存档，可以用 `pg_restore --list` 或 `pg_restore -f -` 查看内容。
- 恢复前用 `sha256sum -c SHA256SUMS` 校验文件。

### 6.3 删除新增列（回滚迁移）

在 6.1、6.2 都完成之后执行。

aster_api（aster_migrator，表 owner）：

```sql
\set ON_ERROR_STOP on
BEGIN;
DROP INDEX IF EXISTS idx_policy_versions_resolver_pending;
ALTER TABLE policy_versions
  DROP COLUMN IF EXISTS resolver_version,
  DROP COLUMN IF EXISTS resolver_migration_error,
  DROP COLUMN IF EXISTS archived_by_resolver_migration,
  DROP COLUMN IF EXISTS status_before_archive;
-- 让下次正向发布重新执行 V6.34.0（否则 Flyway 认为已应用，列不会再加回来）
DELETE FROM flyway_schema_history WHERE version = '6.34.0';
COMMIT;
```

aster_cloud（表 owner）：

```sql
\set ON_ERROR_STOP on
BEGIN;
ALTER TABLE "PolicyVersion"
  DROP COLUMN IF EXISTS "resolverVersion",
  DROP COLUMN IF EXISTS "resolverMigrationError",
  DROP COLUMN IF EXISTS "archivedByResolverMigration",
  DROP COLUMN IF EXISTS "statusBeforeArchive",
  DROP COLUMN IF EXISTS "sourceScopesJson";
-- drizzle 迁移记录：0060（1793236549259）与 0061（1793242575570），删掉后下次正向发布会重新执行
DELETE FROM drizzle.__drizzle_migrations WHERE created_at IN (1793236549259, 1793242575570);
COMMIT;
```

执行 DELETE 之前，先用 `SELECT id, hash, created_at FROM drizzle.__drizzle_migrations ORDER BY id DESC LIMIT 3` 核对 created_at。6.2、6.3 的 SQL 已在本地 PG 16 上演练过：恢复后的值与备份逐列一致，删列后 Flyway 与 drizzle 的记录只剩旧版本。

### 6.4 收尾

- 回滚完成后，PVC `aster-adr0051-migration-backup` 带有 `Prune=false`，从 git 移除时不会被删除。确认不再需要后，手工执行 `kubectl delete pvc`。
- 同样从 git 移除 CronJob 与 PVC 的清单。

## 7. 发布后跟进清单（来自 SDD 账本）

### 需要开 issue 的后续项

- [ ] de、hi 诊断文案缺少基础设施，所有错误码都没有这两种语言的文案（R10e）。E8xx 目前只有 en、zh。
- [ ] ADR 文本对齐：
  - [ ] 折叠表文件名 `casefold/casefold-15.1.json`（R6）；
  - [ ] foldKey 的 NFC 闭包写法（§6.3，R8）；
  - [ ] 调用目标完全无候选时不报 E802（R19）；
  - [ ] What-If 版本缓存与 §9.2 的偏离（R35）；
  - [ ] §9 第 5 步：删除 cloud 文档中「参数名与词条同名需用规范 key」的约定。
- [ ] Java 语法拒绝 workflow 第一步之后的元素。
- [ ] 单步 workflow 的 IR 中，retry、timeout、compensate 两引擎表示不同（一边为 null，一边省略）。
- [ ] TS 格式化器丢弃 `type X as T.`。
- [ ] TS 解析器不支持 FuncType 语法。
- [ ] Java 词法器把天城文只当 IDENT 处理。
- [ ] 同一策略并发创建首个版本时，两行的 prevHash 都是 null，需要锁 PolicyCatalog 行（已有问题，api 与 cloud 都存在）。
- [ ] cloud `updateVersionSource` 只更新 sourceHash，不重算 envelope（已有问题，会造成迁移分诊里草稿的误报）；它与 relink 之间也存在竞态窗口。
- [ ] cloud `mergeVocabularies` 丢弃 literals（已有问题）。
- [ ] runner 的 ConfigMap 有 1 MiB 上限，词汇过大时会报 runner-unavailable（R43 后续）。
- [ ] 已知 parity 缺口：
  - [ ] R2 拿不到导入函数的签名和导入字段的类型；
  - [ ] Java AST 没有逐项 span，导致 item 的 span 与 TS 不同；
  - [ ] 签名上的 written/role 不参与 parity 比较（R33）；
  - [ ] 未知 subject 下，同一 canonical 成员出现在两个枚举里时，两引擎都不报 E806（T11 M2）；
  - [ ] alias 的右侧不做解析（R11）。
- [ ] cloud 迁移：带 4xx 且只有 COMPILE_INTERNAL_ERROR 诊断的行会被归档（应像 hasSourceError 那样过滤）。
- [ ] 本 Job：cloud 迁移在 k8s 中无法失效 Workers KV（见 4.1）。下次类似迁移可以考虑经 cloud 内部端点失效。

### 已延期的小项（minor）

- [ ] locales：generate.mjs 在模块顶层就写文件；没有「重新生成 vs 已提交」的一致性测试，CI 也没有 casefold 测试步骤；hex 键按字典序排列（消费方要按数值排序）；三个 locale 的 Java 测试各自重复实现 resolve。
- [ ] ts：
  - [ ] resolution-codes 测试的名称检查几乎不起作用，占位符只检查 `{written}`；
  - [ ] 只在函数体出现的显式 TypeVar 可能被丢弃；alias 的 E807 span 是 0:0；
  - [ ] TypeVar 上的字段在 `compile()` 中按原文透传；未知 subject 下只有折叠命中的 PatternName 会变成绑定；
  - [ ] `scripts/update-all-core-golden.js` 用的是裸 lowerModule；emit-core.js、golden.js 是死代码；typecheck-cli、repl 没有集成测试；
  - [ ] 枚举名与 import 别名冲突（M1）；lexicon 缓存（M5）；
  - [ ] CORE_IR_ABI 的注释措辞；
  - [ ] LSP：带词汇的文档有语法错误时只显示语法错误；字段补全只在点号之后触发；未闭合字符串会让 parseDocumentText 抛异常；尚未实现基于 Origin 的跳转到定义。
- [ ] core：
  - [ ] 缺少 U+2029、键中原样 U+007F 的测试；
  - [ ] 内置函数的占位返回类型没有打 stamp；typeParams 占位为 `<Quote>`，TS 为 `<Unknown>`；
  - [ ] DeclarationResolverCorpusTest 里有一条多余的词汇豁免；
  - [ ] `scripts/test-*.mjs` 没有接入；删掉的 workflow 样例没有保留。
- [ ] truffle：
  - [ ] README 的复合构建说明已过时；CnlCompiler 的 javadoc 有空 `<pre>`；
  - [ ] 缺少未知 `E.X` 的测试；
  - [ ] CoreIrEvalCli 的词汇为 null，§8 词汇语料落地后需要补词汇通道；
  - [ ] CoreModel 无法反序列化 Block case body；buildScope 的 Env 与 slot 不一致（已有问题）。
- [ ] test：ir-normalize.ts 约 182–194 行有孤立的 JSDoc；源码正则门禁测试偏弱。
- [ ] api：
  - [ ] 语法错误 WARN 量大，执行路径重复记录 WARN；evaluate-source 仍以 ERROR 级别带堆栈记录；
  - [ ] PolicyVersionService:53 的 toolchain 格式 Javadoc 已过时；ToolchainIdentityProvider 的 Javadoc 是历史说明式写法；
  - [ ] runner 侧：import 顺序；bounds 失败的 phase 被标为 "parse"。
- [ ] cloud：
  - [ ] db-bootstrap 的兜底不覆盖只走 API 的路由；
  - [ ] 缺少 PUT 和 team 路由在 vocabulary_scope_invalid、503 时的路由测试；API 消息对所有 locale 都是中文；
  - [ ] entryFlags 重复解析；缓存键使用原始 locale（de 与 de-DE 不同）；mapper 自己重写了 exact→fold 规则；RF5 测试标题；歧义提示的措辞；
  - [ ] modify、restore 的作用域检查在 advisory lock 之外；冲突预览会显示无关的旧错误；缺 parent 与 literal 冲突用的是同一段通用文案；
  - [ ] What-If、Cedar、回归在词汇加载失败时静默回退；
  - [ ] prompt 第 12 条只覆盖成员与规则冲突。
