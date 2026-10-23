# ADR 0050：不停机的增量数据重建与更新（替代整库 DROP/CREATE）

- 状态：**ACCEPTED**（2026-10-24 用户定稿；rev 1 经 Codex 审查 66 分/退回，rev 2 逐条回应，rev 3 并入 aster-cloud#540 的部署顺序后续任务）
- 背景事件：2026-10-24 为启用 Java TypeChecker 强制模式，生产 `aster_api` 与 `aster_cloud` 两库整体 DROP/CREATE 重建
  （见 wontlost-ltd/aster-api#374、wontlost-ltd/aster-cloud#538、`prod-e2e-20261023/findings.md`）
- 第二个背景事件：2026-10-23 22:17Z–22:35Z，aster-cloud#539（新增 `User.aiWorkspaceEnabled` 列）合入后 Workers 先于 k3s 迁移 Job 上线，
  登录态页面全站 500 约 15 分钟（见 wontlost-ltd/aster-cloud#540）。本 ADR 吸收 #540 的三条后续任务。
- 用户已拍板（2026-10-24）：invalid 版本在 enforce 下**拒绝执行**；重验**只在 toolchain 变化时触发**；`aster_cloud` 保留集
  **包含 Execution/AuditLog**；**为 `ai_workspace` schema 补 owner 回调**；**不新增服务**，复用 aster-api / aster-cloud 的 admin 面。
- 相关：ADR 0030（回放地基）、ADR 0046（治理档案）、k3s `apps/infrastructure/postgres-cluster/OWNER-SEPARATION.md`、
  `docs/runbooks/pg-restore-drill.md`、aster-api `MigrationGovernanceTest`、`ToolchainIdentityProvider`、`AuditChainAppender`
  （advisory lock 先例）、aster-cloud `src/lib/admin-auth.ts`、`src/lib/api-signing.ts`、`src/lib/platform-settings.ts`
- 审查记录：`.claude/review-report-adr0050.md`（Codex，2026-10-24）

---

## 0. TL;DR

整库重建把三件本可分开的事绑在一起：**清业务数据**、**修复权限归属**、**让新版本代码对存量数据生效**。它要求停机窗口、
清掉与目标无关的数据（账号、团队、API key、AI 工作台会话），并留下一个实测到的坑：清库前签发的 JWT 仍带旧用户 id，用它创建的
策略归属到不存在的用户，执行报 404。

本 ADR 决定：**不新增服务**。把"重建"拆成四个**彼此独立交付、各自验收**的能力，全部落在现有服务的 admin 面上。
它们不是一个"下一次无需重建"的单一承诺：**B 是本次事件的直接修复，A 是防止再用重建代替迁移的长期治理，C/D 是事故恢复与
确需重造时的运维能力**。

| 能力 | 本质（一句话） | 落点 | 触发者 |
|---|---|---|---|
| A. 数据迁移契约 | 结构变更与数据清理只能通过迁移表达，权限归属由代码维护 | aster-api Flyway、aster-cloud drizzle、aster-ai-workspace sql init | 随版本发布自动执行 |
| B. 存量策略增量重验 | 工具链变化后，为每个版本建立"在该工具链下可执行"的显式证明；没有证明就不执行 | aster-api 后台任务 + admin 端点；aster-cloud admin 代理与徽标 | toolchain 变化时自动排队，admin 可手动 |
| C. 会话纪元 | 数据库身份空间变化时，所有旧 JWT 立即失效 | aster-cloud `auth.ts` | admin bump |
| D. 结构性重造的蓝绿数据库 | 确需重造时以恢复 + 追平 + 切流取代停机 | k3s CNPG 手册 | 运维 |

权限边界：所有写操作只对 cloud `isAdmin === true` 的用户与经 `AdminHmacVerifier` 验签的内部调用开放，复用现有
`/api/admin/*`、`signRequest`（`src/lib/api-signing.ts`）与 `@RequireRole(ADMIN)`；不新增凭据类型。

---

## 1. 现状与问题

### 1.1 本次重建暴露的事实

- **数据不在一个库里**：策略同时存在于 `aster_cloud`（`Policy`/`PolicyVersion`，用户编辑的事实来源）与 `aster_api`
  （`policy_catalog`/`policy_versions`，运行时形态）。`aster_cloud` 还承载账号、团队、API key、执行记录与 `ai_workspace` schema。
- **数据面不止两个库**（rev 2 补全）：api 的 API key 快照在 Redis（`ApiKeyVerifierService` 读 snapshot）、各 Pod 的 Caffeine
  缓存（含 `policy-compile-by-version`）、Cloudflare Hyperdrive 的连接池、`ai_workspace` 的对话/审计表。"数据库无数据丢失"不等于
  这些派生状态一致。
- **权限归属靠手工 SQL**：`aster_api` 的 schema/表 owner 必须是 `aster_migrator`，运行时角色 `aster_api_user` 只拿 DML、审计表只 `ar`。
  `V6.24.0` 只处理审计对象；`ALTER ... OWNER` 要求执行者是当前 owner 或超级用户，权限不足时迁移仅 `NOTICE` 跳过，启动照常成功。
- **会话与库版本脱钩**：cloud 用 JWT 会话（`strategy: 'jwt'`），`jwt()` 回调只在初次登录（`user` 存在）时查库；之后 token 自洽，
  不随数据库变化失效。`PlatformSetting` 读取按 Worker isolate 缓存 60 秒，读失败回默认值。
- **运行时编译并不总是走类型检查**（rev 2 修正）：`PolicyCompiler.compile(Long versionId)`
  （`src/main/java/io/aster/policy/compiler/PolicyCompiler.java`）按 versionId `@CacheResult`，且先读 `PolicyArtifact`、再读
  `PolicyVersion.coreJson`，**只有两者都没有时才进入带 TypeChecker 的 `compile(String, locale, aliasSet)`**。
  因此"对每个版本调用现有 compile"不能作为重验入口，运行时也不能靠它来拒绝 invalid 版本。
- **工具链身份已有实现**：`ToolchainIdentityProvider.currentToolchainId()` 产出 `abi=…;core=…;validator=…;build=…` 四段串，
  `build` 来自 `aster.runtime.build`（镜像构建注入 `ASTER_RUNTIME_BUILD`），`core` 来自 JAR `Implementation-Version`，缺失退化为 `dev`。
  `PolicyVersion.source_toolchain_id` 记录的是**创建时**的工具链，不是"最后通过验证的工具链"。
- **两条发布链路没有顺序约束**（#540）：Cloudflare Workers Builds 在 main 合入后数分钟内部署；drizzle 迁移则要经 CI
  `build-migrate-image` → k3s `image-pin/aster-cloud-migrate` bot 分支 PR → verify-image-pin 自动合并 → ArgoCD PostSync Job。
  任何在热路径读取新列的代码都会在这个窗口里把登录态页面打成 500；bot 分支一旦落后 main 还会卡在 BEHIND，需人工重跑源仓 job。
  本地人工补列也不可行：`image-pin/*` 分支受仓库规则保护，生产 DDL 又受会话权限限制。
- **真正的目标只需要"重验"**：启用类型检查后，问题只是"存量版本里有不满足新检查的"。它们应被判定、标记并提示修复，而不是连同用户一起删除。

### 1.2 两种候选形态

**形态 1：新增独立"重建服务"**——需要第三份数据库凭据与 owner 权限、第三套部署与 image-pin 流水线、第二份 Java 编译器依赖并再保
双引擎一致；违反准则"标准化 + 生态复用、禁止新增自研方案"。重验逻辑本质就是 aster-api 的编译器，搬出去只会制造第二个事实来源。

**形态 2：复用现有服务，仅对 admin 开放**（采纳，但有前提）。
- aster-api 已有：Flyway、`PolicyCompiler`（含 TypeChecker）、`@Scheduled` 调度器、admin 资源、`AdminHmacVerifier`、
  `ApiKeyAuthFilter` 对 `X-User-Role` 的权威化（仅 API key 路径）、`pg_advisory_xact_lock` 先例（`AuditChainAppender`、`PostgresEventStore`）。
- aster-cloud 已有：`requireAdmin()`、`/api/admin/*`（如 `byok-allowlist` 的 "gate admin → `signByokAllowlistHeaders` → 代理" 模式）、
  `PlatformSetting` 与 `/api/admin/platform-settings`（key 白名单）、drizzle 迁移镜像与 ArgoCD PostSync Job、`AuditLog` 写入样例。
- **前提**（Codex 第五层结论）：重验的并发租约与任务队列必须在现有服务内以 Postgres 原语实现，且不干扰在线请求；若任何一步需要独立
  worker 部署或跨服务编排，本论证需重算。本 ADR 2.2 给出满足前提的规格。

---

## 2. 决策

### 2.1 A. 数据迁移契约

1. **结构变更一律 expand/contract**：先加列/表并双写，回填，再切读，最后一个独立版本删旧对象。每个版本单独可回滚。
2. **数据清理用迁移表达**：需要清空的业务表以 `TRUNCATE`/按租户 `DELETE` 的 Flyway/drizzle 迁移发布，随滚动更新执行，连接不断。
   审计表（`audit_logs`、`audit_chain_anchors`）不在此列：清理走"归档到对象存储 → 由 `aster_migrator` 截断 → 链锚点表写断链记录"
   的独立手册步骤。**这是不可逆操作**，第 6 节单列。
3. **权限归属进代码，但分两段**（rev 2 回应"权限前置条件"阻塞项）：
   - **首轮移交是上线前置步骤，由超级用户执行一次**（`OWNER-SEPARATION.md` 步骤 2 的脚本，对象范围扩到 schema、所有表、序列、视图、
     `flyway_schema_history`），写进发布清单并由 `verify-sibling-ref-pin.sh` 同类的 CI 门禁校验"手册版本号已确认"。
   - 之后 aster-api 新增 Flyway `afterMigrate` 回调（SQL，幂等）：以 `aster_migrator` 身份对**它拥有的对象**维护
     `ALTER DEFAULT PRIVILEGES ... TO aster_api_user`、审计表 `REVOKE UPDATE, DELETE, TRUNCATE`。
   - **跳过不再静默**：回调结束时执行一次自检查询（schema owner、`pg_tables`/`pg_sequences`/`pg_views` owner、审计表 ACL、
     业务表 ACL、默认权限），任一不符 → `RAISE EXCEPTION`，Flyway 失败，Pod 不就绪。角色不存在（本地/CI）时整段跳过并 `NOTICE`，
     由 `%prod` 配置项 `aster.db.owner-check=strict` 决定是否允许跳过。
4. **`ai_workspace` schema 同样进代码**：aster-ai-workspace 的 `schema-aiw.sql`（每次启动执行）追加 `DO` 块：当前角色是 schema owner
   时执行 `ALTER DEFAULT PRIVILEGES IN SCHEMA ai_workspace ...`，否则 `RAISE EXCEPTION`（生产）/`NOTICE`（本地，按 profile）。
   生产 `ai_workspace` 当前 owner 已是 `aster_ai_user`（2026-10-24 重建时 `CREATE SCHEMA ... AUTHORIZATION aster_ai_user`）。
5. **治理测试加码，用结构化元数据而非正则**（rev 2 回应 Codex 改进项 6）：
   - 含 `DROP TABLE`/`DROP COLUMN` 的迁移文件头必须带 `-- aster-migration: {"contractOf":"Vx.y.z","objects":["t.c"]}` JSON 元数据；
     `MigrationGovernanceTest` 解析该 JSON，校验 `contractOf` 指向的迁移存在且版本号小于当前、`objects` 与 DROP 目标一致。
     元数据缺失/不合法/歧义 → 测试失败。SQL 文本只做"是否出现 DROP"的宽匹配触发，不做语义解析。
   - 任何迁移不得含 `DROP DATABASE`/`DROP SCHEMA public`。
   - 正反例夹具覆盖：注释内的 DROP、大小写、`IF EXISTS`、schema 限定名、多条 DROP。

6. **部署顺序契约（吸收 #540）**——expand/contract 不只约束 SQL，也约束发布：
   - **迁移先行**：aster-cloud 任何含 `drizzle/*.sql` 且有代码读取新对象的改动，拆成两个 PR：先合迁移（只含 `drizzle/`、`src/db/schema.ts`
     以及不读取新列的代码），待 ArgoCD migrate Job 成功后再合读取代码。CI 新增检查：同一 PR 同时改动 `drizzle/*.sql` 与 `src/**`（schema.ts 除外）
     时失败，提示拆分；紧急修复可用 PR 标签 `migration-and-code` 显式豁免并在描述中给出容错说明。
   - **Worker 部署门禁**：`ci.yml` 的 main 分支流程在 `build-migrate-image` 之后增加「等待 k3s migrate Job 成功」步骤（查询 ArgoCD 应用的
     `operationState` 或 Job 状态），只有迁移落库后才允许触发 Workers 生产部署；Workers Builds 的自动部署改为由该流程显式触发
     （或在 Cloudflare 侧关闭 main 自动构建，改用 `wrangler deploy` 步骤）。
   - **热路径读取容错**：`(dashboard)/layout.tsx`、`auth.ts` 等每请求都执行的查询，对迁移期新增列的读取包一层 `safeColumnRead`
     （捕获 `42703 undefined_column`，按列默认值降级并记一次告警），确保迁移窗口内站点可用而不是 500。容错只允许存在一个版本周期，
     迁移稳定后删除。
   - **image-pin bot 自愈**：源仓 `image-pin-pr` job 在检测到 bot 分支相对 main BEHIND 时自动以 main 为基重建分支再推送；
     verify-image-pin 通过后由现有 auto-merge 合入，不再依赖人工重跑。aster-api 的 image-pin 流程同样适用。

### 2.2 B. 存量策略增量重验

**本质**：工具链变化后，为每个 `(version_id, toolchain_id)` 建立显式验证证明；运行时**只执行有证明的版本**。

- **数据结构**（aster-api）
  - 表 `policy_version_validation`：`version_id`、`toolchain_id`、`status` ∈ {valid, invalid}、`diagnostics` jsonb、`diagnostics_schema` int、
    `run_id`、`checked_at`；**唯一键 `(version_id, toolchain_id)`**，保留跨工具链历史。不存在"pending"行——未验证就是没有行。
  - 表 `policy_revalidation_run`：`id`、`toolchain_id`、`scope`（all / tenant）、`status` ∈ {queued, running, done, failed}、
    `cursor`（最后处理的 version_id，断点续跑）、`total`/`checked`/`invalid_count`、`started_at`/`finished_at`、`error`；
    **唯一部分索引 `(toolchain_id, scope) WHERE status IN ('queued','running')`**，保证同一工具链同一范围最多一个活动 run。
- **重验入口契约**（rev 2 回应首要阻塞项）：新增 `PolicyCompiler.typecheckVersion(PolicyVersion v)`：读取版本冻结的
  `content`/`locale`/`aliasSet`，**直接调用 `compile(String, locale, aliasSet)` 的字符串路径**，不读 artifact/coreJson，不经
  `policy-compile-by-version` 缓存。这是唯一允许写验证表的入口。
- **任务**：`PolicyRevalidationJob`（`@Scheduled` 每 30 秒轮询 + 启动时一次）。每次轮询在事务内
  `SELECT ... FROM policy_revalidation_run WHERE status IN ('queued','running') FOR UPDATE SKIP LOCKED LIMIT 1`，拿到即处理一批
  （默认 50 条，批间休眠可配），写 `cursor` 后提交。多副本靠 `SKIP LOCKED` 互斥，崩溃后 `running` 行由下一个副本按 `cursor` 续跑
  （`running` 超过租期阈值视为可接管）。不引入新调度框架。
- **触发**（用户拍板：只在 toolchain 变化时）：api 启动时读取 `currentToolchainId()`，与 `policy_revalidation_run` 中最新 `done` 的
  `toolchain_id` 比较，不一致则 `INSERT ... ON CONFLICT DO NOTHING` 一条 `queued` run（唯一部分索引保证多副本只插一条）。
  `build=dev` 或 `core=dev` 的身份串在 `%prod` 下视为配置错误，启动失败（避免工具链变化被 `dev` 掩盖）。admin 端点可手动插入 run。
- **运行时门禁**（rev 2 回应"pending 漏放"）：`compile(Long)` 不变，仍负责产出编译物。新增 `PolicyExecutionGate.check(versionId)`
  在 evaluate 决策路径上、`compile(Long)` 之前调用：
  - 查 `(versionId, currentToolchainId)`：`valid` → 放行；`invalid` → enforce 拒绝并返回诊断 / warn 告警后放行；
  - **无行 → 同步调用 `typecheckVersion` 并写入结果，再按上述规则处理**（fail-closed：同步验证失败或读表失败一律拒绝，warn 模式亦然）。
    因此部署后第一次执行某版本会多一次编译，之后命中验证表；后台 run 只是把这笔开销提前。
  - 门禁结果缓存键为 `(versionId, toolchainId)`，与编译缓存分离；TTL 5 分钟，admin 手动重跑 run 完成时主动失效。
  - `aster.compile.typecheck=warn` 保留为紧急降级，但只影响 `invalid` 的处置，不影响"无行必验"。
- **admin 端点**（aster-api，经 `AdminHmacVerifier` 验签 HMAC+时间戳+nonce，body 参与签名；`X-User-Role` 不作为授权依据）：
  `POST /api/v1/admin/revalidation/runs`（body: scope, tenantId?）、`GET /api/v1/admin/revalidation/runs/{id}`、
  `GET /api/v1/admin/revalidation/invalid?toolchainId=`。
- **aster-cloud**：`/api/admin/revalidation/*` 按 `byok-allowlist` 模式——`requireAdmin()` → `signRequest` → 代理；admin 页面展示
  run 进度与失败清单；策略详情页读到 `invalid` 显示"需修复"徽标与诊断。保存门不变（已强制）。
- **审计**：run 创建/完成/失败、手动重跑写 api `audit_logs`（action `policy.revalidation.*`）；cloud 侧 admin 操作写 `AuditLog`
  （现有 `byok-allowlist` 写法）。

### 2.3 C. 会话纪元与设备信任

- **存储与一致性**（rev 2 回应 60 秒缓存冲突）：`auth.dbEpoch` 存 `PlatformSetting`，但**鉴权路径不走 60 秒缓存**：
  `jwt()` 回调在**每次**被调用时（Auth.js 对每个受保护请求都会调用 `jwt()` 解码并可刷新 token）用 `getSettingUncached` 直接读库，
  读失败 → **fail-closed**（视为纪元不匹配，返回 null 使会话失效）。每请求多一次主键查询，Hyperdrive 内可接受；若实测成本过高，
  改为 Workers KV 单键，同样不走 isolate 缓存。
- **写入**：`/api/admin/platform-settings` 的 key 白名单加入 `auth.dbEpoch`，类型为正整数、只允许单调递增、body 必须带
  `confirm: "logout-all"`；写入前后记 `AuditLog`（操作者、旧/新 epoch）。
- **覆盖矩阵**（验收用）：Credentials 初次登录、OAuth 初次登录、既有 JWT 后续请求、`session.update`、并发 bump。
  初次登录在 `jwt({user})` 分支写入当前 epoch；后续请求在 `jwt({token})` 分支比对。
- **设备信任迁移**：`TrustedDevice` 列入保留集（见 2.5），不随任何重建清空。

### 2.4 D. 结构性重造的蓝绿数据库（手册，不入服务）

仅当必须重造（PG 大版本、编码/排序规则变更）时使用，手册需包含（rev 2 补全）：
1. CNPG 从最近备份起第二个 `Cluster`（`externalClusters[].barmanObjectStore.serverName` 指向生产；演练已验证路径）。
2. 在新集群执行重造与 A 的迁移/回填。
3. 逻辑复制追平增量；**明确不覆盖项**：DDL、序列当前值（切换前用 `setval` 对齐）、large objects；切换前冻结写入（api 置维护模式、
   cloud 置只读开关）并校验行数/校验和。
4. 切流：k3s `shared-postgres-rw` Service 指向新集群 **与** Hyperdrive 配置更新是两个独立动作，分别有传播时间；切换后主动
   `rollout restart` api/ai-workspace 以淘汰旧池连接，Hyperdrive 侧以新配置 id 替换 binding 并重新部署 Worker。
5. 回切条件：切流后新集群出现数据面错误且未产生新写入（写入冻结窗口内）→ 反向切流；一旦解除冻结即不可回切，只能前向修复。
6. 旧集群保留 7 天后下线。

### 2.5 数据面清单（rev 2 新增）

| 数据面 | 权威源 | 重建/切换后动作 | 校验探针 |
|---|---|---|---|
| `aster_cloud` 保留集：`User`、`Account`、`Session`、`TrustedDevice`、`TotpCredential`、`Team`、`TeamMember`、`TeamInvitation`、`ApiKey`、`Execution`、`ExecutionOutcome`、`AuditLog` | aster_cloud | 只能迁移，不得 DROP | 行数与 `max(createdAt)` 前后一致 |
| `aster_api` `policy_catalog`/`policy_versions` ↔ `aster_cloud` `Policy`/`PolicyVersion` | cloud 为编辑事实源，api 为运行时形态 | 版本 id 稳定映射由部署流程保证；新增校验脚本比对两侧 `sourceHash` | 不一致清单为空 |
| API key 快照（Redis） | cloud `ApiKey` | 切换后 `FLUSH` 对应前缀，api 按需回源 cloud | 用种子 key 调 evaluate 成功 |
| Caffeine `policy-compile-by-version`、门禁缓存 | 版本内容不可变 | 滚动重启自然失效；门禁缓存按 toolchain 隔离 | 无 |
| Hyperdrive 连接池 | Cloudflare 配置 | 切流时按 2.4 第 4 步 | Worker 健康检查 |
| `ai_workspace` schema | aster-ai-workspace | 启动 sql init 幂等建表；对话数据属保留集 | `pg_tables` 计数 |

---

## 3. 权限模型

| 操作 | 谁可以 | 机制 |
|---|---|---|
| 发起/查看重验 | cloud `isAdmin` 用户 | `requireAdmin()` → `signRequest` HMAC（含 body、时间戳、nonce）→ api `AdminHmacVerifier` |
| bump `dbEpoch` | cloud `isAdmin` 用户 | `/api/admin/platform-settings` 白名单 key + `confirm` 字段 + 审计 |
| 数据清理迁移、owner 回调 | 无人工触发 | 随发布由 Flyway/drizzle 以迁移角色执行；首轮 owner 移交由超级用户按手册执行 |
| 蓝绿切流 | 运维（kubectl / wrangler） | 手册 |

`isAdmin` 仍只能由 DBA 手工授予；API key 路径的 `X-User-Role` 权威化不扩展到 admin 端点。

---

## 4. 验收标准（按测试落点）

**aster-api 单测**
- `PolicyExecutionGateTest`：三态 × enforce/warn 矩阵；无行时同步验证并落表；读表异常 → 拒绝；缓存键含 toolchain。
- `PolicyCompilerTypecheckVersionTest`：存在 artifact/coreJson 的版本仍产生 TypeChecker 诊断（证明绕过快路径）。
- `ToolchainIdentityProviderTest` 扩展：`%prod` 下 `dev` 段启动失败。
- `MigrationGovernanceTest`：元数据缺失/不合法/`contractOf` 不存在/对象不一致各一例变红；注释内 DROP、`IF EXISTS`、限定名、多 DROP 正例通过。

**aster-api Postgres 集成**
- 两副本并发启动只产生一条 `queued` run；两 worker 并发轮询只有一个拿到 run；worker 中途退出后另一副本按 `cursor` 续跑且不重复写行。
- 空库与存量库各跑一次 Flyway：owner 自检通过；人为改坏一个表 owner → 启动失败并打印不符项。

**aster-cloud CI 与部署顺序**
- 同一 PR 同时含 `drizzle/*.sql` 与 `src/**` 代码改动且无 `migration-and-code` 标签 → CI 失败并给出拆分提示；只含迁移或只含代码 → 通过。
- staging 演练：先合一条加列迁移，确认 Job 成功后再合读取代码；全程登录态页面 200。反向演练（故意先合代码）在容错层生效时页面仍 200 并产生告警。
- image-pin bot 分支人为落后 main 一个提交：源仓 job 自动重建分支并自动合并，无人工介入。

**aster-cloud Vitest**
- `dbEpoch`：Credentials/OAuth 初次登录写入；旧 token 在 bump 后下一次 `jwt()` 返回 null；读库失败返回 null；非单调/缺 `confirm` 写入被拒；审计行存在。
- admin 代理：非 admin 403；签名缺 body 哈希被 api 拒绝（契约测试）。

**e2e（本地栈 + e2e-cloud）**
- 不重建库：升级 core 版本镜像 → 启动自动排队 run → 含 E012 的种子版本进入 `invalid` 清单 → 执行被拒并返回诊断 → 修复后通过。
- 同版本重启不排队。
- bump epoch 后旧会话页面被登出；用户、团队、Execution、AuditLog、AI 会话行数不变。

**运维演练**
- 蓝绿：按 2.4 在 staging 完整走一遍，记录切流总时长（含传播），与"连接重置窗口"分开报告。

---

## 5. 风险与缓解

- **门禁首访延迟**：无行版本首次执行多一次编译（本地实测 CNL 编译 ms 级到百 ms 级）。后台 run 在启动后即开始消化，热点版本很快命中。
- **重验 CPU**：分批 + 批间休眠；`SKIP LOCKED` 保证单 worker；可用 admin 端点暂停（把 run 置 `failed` 后重开）。
- **`dev` 身份串**：生产启动失败是有意 fail-fast；镜像构建已注入 `ASTER_RUNTIME_BUILD`，core JAR 带 `Implementation-Version`。
- **epoch 误 bump**：全员登出。`confirm` 字段 + 审计 + 单调递增；无法"撤销"，只能重新登录。
- **owner 自检误报**：新对象类型（物化视图等）需同步更新自检；自检失败会阻断发布，属有意 fail-closed，手册给出修复 SQL。
- **每请求读 epoch**：每个受保护请求 +1 次主键查询；超预算时切 KV 单键，不回退到 60 秒缓存。
- **部署门禁拖慢发布**：Worker 部署等待 migrate Job 通常增加 5–10 分钟；只在 main 含迁移时等待，纯代码改动不受影响。
- **容错层掩盖问题**：`safeColumnRead` 可能让缺列长期不被发现；要求降级时必须打告警并在下个版本删除容错。
- **双库映射漂移**：校验脚本列为发布后探针；不一致时以 cloud 为准重新部署到 api。

---

## 6. 迁移与回滚

- **加法部分**（可随时回滚）：验证表与 run 表、`typecheckVersion`、`PolicyExecutionGate`（可用配置 `aster.compile.gate=off` 关闭回到旧行为）、
  admin 端点与页面、`dbEpoch`（不 bump 即无影响）、治理测试、owner 自检（`aster.db.owner-check=lenient` 回到 NOTICE）。
- **不可逆部分**（必须单列、需手册与确认）：审计表归档后截断；蓝绿切流解除写入冻结之后；epoch bump。
- 本次（2026-10-24）已完成的整库重建不回滚；本 ADR 的目标是下一次按能力选择，而不是整库重建。

---

## 7. 已决问题（2026-10-24 用户拍板）

1. `invalid` 版本在 enforce 模式下**拒绝执行**，不回退到上一版 valid。rev 2 进一步规定：**无验证记录也不放行**，同步验证后再定。
2. 重验**只在 toolchain 变化时触发**（启动时比对自动排队 + admin 手动），不设每日 cron。
3. `aster_cloud` 保留集**包含 `Execution` 与 `AuditLog`**，其清理只走按保留期的归档迁移。
4. **为 `ai_workspace` schema 补 owner 回调**，落在 aster-ai-workspace 的启动 SQL 中，生产 profile 下不符即失败。

---

## 8. 实施拆分（供 writing-plans 使用，按依赖排序）

1. aster-api：`ToolchainIdentityProvider` 的 `%prod` fail-fast；Flyway `afterMigrate` owner 自检回调；`MigrationGovernanceTest` 元数据规则（小，先行）。
2. aster-api：验证表 + run 表迁移、`typecheckVersion`、`PolicyRevalidationJob`（`SKIP LOCKED`）、`PolicyExecutionGate` 接入 evaluate、三个 admin 端点、审计。
3. aster-cloud：`dbEpoch`（uncached 读、白名单、confirm、审计、`jwt()` 比对）、admin 代理路由与页面、"需修复"徽标。
4. aster-ai-workspace：`schema-aiw.sql` owner/默认权限 `DO` 块（按 profile 决定 EXCEPTION/NOTICE）。
5. 校验脚本：cloud ↔ api 版本 `sourceHash` 比对。
6. k3s：OWNER-SEPARATION 手册改为"首轮超级用户移交 + 回调自检"；新增蓝绿数据库手册（含 2.4 的不覆盖项与回切条件）。
7. aster-cloud：部署顺序契约——PR 拆分检查、Worker 部署等待迁移 Job 的门禁、`safeColumnRead` 容错；源仓 image-pin job 的 BEHIND 自愈（aster-cloud 与 aster-api 两处）。
8. 本地栈演练与 e2e-cloud 用例（第 4 节）。

---

## 9. rev 2 对 Codex 审查的回应摘要

| Codex 阻塞/建议 | 处置 |
|---|---|
| `compile(Long)` 快路径跳过 TypeChecker，不能作重验入口 | 新增 `typecheckVersion` 字符串路径专用入口；运行时门禁独立于编译缓存（2.2） |
| pending 放行造成 enforce 漏放 | 取消 pending 概念：无行即同步验证，失败/异常一律拒绝（2.2） |
| owner 回调权限前置条件不可自动满足 | 首轮超级用户移交列为上线前置；回调只维护默认权限并以 `RAISE EXCEPTION` 自检（2.1.3） |
| 多副本重复排队、无租约 | run 表唯一部分索引 + `FOR UPDATE SKIP LOCKED` + `cursor` 续跑（2.2） |
| epoch 与 60 秒 isolate 缓存冲突 | 鉴权路径 uncached 读 + fail-closed；白名单/单调/confirm/审计（2.3） |
| 数据面遗漏（Redis 快照、Caffeine、Hyperdrive、ai_workspace） | 2.5 数据面清单 |
| 治理规则靠正则不可靠 | 文件头 JSON 元数据 + 正反例夹具（2.1.5） |
| 验收无测试落点、回滚风险遗漏 | 第 4 节按测试类/场景列出；第 6 节分加法与不可逆 |
| 文件路径写错（`compile/` → `compiler/`） | 已更正 |
| #540 事故三条后续（迁移先行、读取容错、bot 自愈） | 并入 2.1.6 部署顺序契约与第 4、5、8 节 |
