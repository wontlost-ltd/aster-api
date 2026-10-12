# ADR 0047：Cedar 导出目标（AI 治理方向·子项目 6b）

- 状态：**ACCEPTED**（2026-10-13；验收记录见 §9）
- 实施计划：`docs/superpowers/plans/2026-10-12-cedar-export.md`
- 决策者：用户已拍板「方向为导出到既有 Cedar 执行端；首版产出通用 Cedar 文件（策略文本 + schema），不直推 AWS；require_approval/escalate 编译为带注解的 forbid；输入映射沿用 Action Guard 请求形状；不可翻译即整体拒绝导出；编译器放在 aster-lang-core（Java），由 aster-api 提供端点」
- 相关：ADR 0039（§1 行 6）、ADR 0040（Action Guard 请求形状）、ADR 0045（控制注册表）、ADR 0046（档案、语法糖、E705–E707）、ADR 0031（错误码单一来源）、ADR 0041（证据链）

---

## 0. TL;DR

团队继续在 Aster 里写策略、做治理（多语种、档案、证据、重放）；本 ADR 把**可翻译子集**的 Verdict 规则编译成
**标准 Cedar 策略文本 + Cedar JSON schema**，交给客户已有的 Cedar 执行端（开源 Cedar、网关插件、AWS Verified
Permissions 的导入工具）执行。Aster 仍是唯一事实源与证据链；每次导出都用官方 Cedar 授权器对同一组探针请求
与 Aster 引擎逐一比对，**结论一致才放行**。导出要么整体成功，要么给出全部不可翻译点。

## 1. 现状与问题

| 现状 | 位置 | 后果 |
|---|---|---|
| 策略只能在 Aster 引擎（aster-api / guard）里执行 | — | 已有 Cedar 网关的团队要么双写规则，要么把每次请求都绕到 aster-api |
| guard 请求已规范为 principal / action / resource / context 八字段 | `io.aster.guard.GuardActionMapper` | 与 Cedar 的 PARC 模型天然对应，但没有任何导出路径 |
| Verdict 有四种结论（allow/deny/escalate/require_approval） | ADR 0039 | Cedar 只有 permit/forbid，需明确降维语义 |
| Aster 规则按序首匹配；Cedar 无序、forbid 优先 | — | 直译会改变结论，必须显式编码顺序 |

## 2. 方向与范围

- **方向**：Aster → Cedar 单向导出。不导入 Cedar，也不在 guard 内嵌 Cedar 预检。
- **首版目标**：通用 Cedar 文件，下载 / API 获取；**不**存租户 AWS 凭据、不直推 Verified Permissions（可在后续版本叠加，产物格式无需改变）。
- **编译器位置**：aster-lang-core 新包 `aster.core.cedar`，输入 Core IR，输出策略文本、schema（JSON）与诊断；core 不引入新依赖。
- **校验与等价性**：在 aster-api，用官方 `com.cedarpolicy:cedar-java`（Maven Central）。**风险**：cedar-java 依赖原生库，而 aster-api 生产镜像为 Alpine（musl）/ arm64；实施计划首个任务必须先在该镜像上实测加载，不可用时停下由用户裁决（换 glibc 基础镜像或改用其他运行方式）。

## 3. 可导出形状与请求映射

规则满足以下全部条件才可导出：带 `@id`；只有一个参数且其类型的字段名集合为 guard 请求八字段
（principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model，按字段名匹配）；返回 `Verdict`。

| Aster 字段 | Cedar |
|---|---|
| `principal_id` | `principal` = `Aster::Principal::"<id>"` |
| `principal_type` | `principal.type`（String） |
| `principal_roles` | `principal.roles`（Set<String>）；成员判断 → `principal.roles.contains("…")` |
| `action` | `action` = `Aster::Action::"<name>"`；规则里比较过的每个 action 名都进 schema 的 actions |
| `resource_type` / `resource_id` | `resource` = `Aster::Resource::"<id>"`，属性 `type` |
| `agent_model` | `context.agent_model` |
| `context.<key>` | `context.<key>`；类型按用法推断（与整数比较或参与整数运算 → Long，与文本比较 → String，单独作条件或与布尔比较 → Bool），schema 中声明为可选；只支持一层键（`req.context.a.b` → E721） |

实体类型统一在 `Aster` 命名空间；principal/resource 的「类型」作为属性而非独立实体类型（guard 中它们是自由文本）。

**缺失键语义（实施前核实后的裁决）**：Aster 读不存在的 context 键会抛错，guard 把求值异常判为 DENY（`EVALUATION_FAILED`），且其后分支不再求值。
若在 Cedar 侧用 `context has k` 守卫后落空到下一分支，后续 permit 可能放行 Aster 拒绝的请求。因此每个条件 c 翻译为两个 Cedar 表达式：
T(c)「无错求值且为真」与 F(c)「无错求值且为假」；读 context 键的原子比较为 `T = context has k && (比较)`、`F = context has k && !(比较)`，
复合式按短路展开：`T(a and b)=T(a)&&T(b)`，`F(a and b)=F(a)||(T(a)&&F(b))`，`T(a or b)=T(a)||(F(a)&&T(b))`，`F(a or b)=F(a)&&F(b)`，`T(not a)=F(a)`，`F(not a)=T(a)`。
`Let` 在其位置加入「已定义」要求 `T(e)||F(e)`。任何一个前序条件会抛错时，所有后续策略的 F(前序) 均不成立 → 无策略适用 → Cedar Deny，与 Aster 一致；且每处键访问都在同一合取中先经 `has` 守卫，满足 Cedar 严格校验。
`agent_model` 可为空：空值等同缺失，`==` 为假、`!=` 为真（与 Aster 的空值比较一致）。角色成员判断写作 `List.contains(req.principal_roles, "…")`（语言无 `in` 运算符）。

## 4. 规则体翻译

### 4.1 顺序

第 k 个分支（`If` 或 `When` 语法糖）的守卫 = 自身条件 ∧ ¬(前面所有分支的条件)；最终的 `Otherwise` / 落空 `Return`
守卫 = ¬(全部前序条件)。因此任一请求恰好命中一条策略，与 Aster 首匹配一致；forbid 优先规则永不介入。

### 4.2 结论

| Verdict | Cedar |
|---|---|
| `allow()` | `permit` |
| `deny(reason)` | `forbid`，`@aster_outcome("deny") @aster_reason("…")` |
| `require_approval(role, reason)` | `forbid`，`@aster_outcome("require_approval") @aster_approver("…") @aster_reason("…")` |
| `escalate(reason)` | `forbid`，`@aster_outcome("escalate") @aster_reason("…")` |

每条策略另带 `@id("<rule-id>#<branch>")`、`@aster_rule`、`@aster_module`、`@aster_source_hash`；规则有 `@control` 时带
`@aster_controls("A:B,…")`，模块声明档案时带 `@aster_profile("…")`。纯 Cedar 执行端遇到非 allow 结论一律 fail-closed；
读取决定性策略注解的网关可把 require_approval/escalate 路由到 Aster 审批流。Cedar 默认拒绝，与「未命中 allow 分支」一致。

### 4.3 可翻译子集

- 支持：Int / Long / Text 上的 `==` `!=` `<` `<=` `>` `>=`（Text 只支持 `==` `!=`）；`List.contains(req.principal_roles, "<文本字面量>")`（唯一放行的库调用）；`and` / `or` / `not`（自 1.0.32 起两引擎短路一致）；Bool 字段；角色成员判断；
  `Let`（在使用处内联）；Int 上的 `plus` / `minus` / `times`（操作数为 context 键或整数字面量）。
- 整数数值域：Aster 的数值比较经 double，只有 ±(2^53−1) 内的整数能与 Cedar 的精确 Long 比较一致。因此整数字面量须满足
  |v| < 2^53（否则 E721 `integer literal outside ±2^53`）；Long 比较中的每个 context 键与每个算术项（包括只由字面量组成的算术项）在 T 与 F 中都带范围守卫
  `(-9007199254740991 <= x && x <= 9007199254740991)`，超界时 T、F 皆不成立 → 无策略适用 → Cedar Deny。Cedar 的 Long 运算溢出求值报错，同样落为 Deny。
  两种情形下 Cedar 都只会比 Aster 更严，不会更松。
- 不支持（报错）：Decimal / Float 值（Cedar `decimal` 固定 4 位且舍入不同）；调用其他函数或规则；List / Map / Text 库调用；
  `Match`；副作用与 workflow；除法与取模；两个类型未知的 context 键之间的比较；then/else 块可能不以 `Return Verdict.*` 结束（落空）；Verdict 的理由或审批人不是文本字面量。

### 4.4 诊断（ADR 0031 单一来源，同步全部副本）

| 码 | 名称 | 含义 |
|---|---|---|
| E720 | CEDAR_NOT_EXPORTABLE_SHAPE | 缺 `@id`、参数不是 guard 请求形状、或非 Verdict 规则 |
| E721 | CEDAR_UNSUPPORTED_CONSTRUCT | 不可翻译的构造（带位置与构造名） |
| E722 | CEDAR_UNTYPED_CONTEXT_KEY | 同一 context 键被以冲突类型使用 |
| E723 | CEDAR_EQUIVALENCE_MISMATCH | 等价性探针不一致（编译器缺陷，带失败探针） |
| E724 | CEDAR_SCHEMA_INVALID | Cedar 严格校验不通过（编译器缺陷） |

整体导出：存在任一错误即只返回诊断、不返回策略文本。声明档案且违反档案（E705/E706）的模块同样不可导出。

## 5. 等价性（每次导出都执行）

编译器按规则内容生成探针请求：规则提到的每个 action、角色、principal 类型各一，外加一个未提及值；每个数值比较取
字面量及其 ±1；每个 context 键取存在与缺失两态。每个探针同时交给 Aster 引擎与 Cedar 授权器（带生成的 schema）：
Aster `allow` ⇔ Cedar `Allow`；其余结论 ⇔ Cedar `Deny` 且决定性策略恰为一条 forbid，其 `@aster_outcome` / `@aster_reason`
与 Aster 一致；Aster 求值抛错 ⇔ Cedar `Deny` 且无决定性策略。数值域：探针取值限于 ±(2^53−1)；超出该域的输入按 §4.3 由范围守卫判为 Cedar Deny（只会更严），不作为一致性要求。探针在 Cedar 侧**不做请求 schema 校验**（与不校验请求的网关一致）；网关若按 schema 校验请求，schema 外的请求（未提及的 action、非整数数值等）会被 Cedar 拒绝——这是有意的「对外形态收窄」，只会更严、不会更松。任何不一致 → E723。探针数量有上限（初值 512），响应中报告实际条数。另以 Cedar 校验器严格模式校验
策略与 schema（E724）。

## 6. 接口与证据

- **API**：`POST /api/v1/policies/export/cedar`，入参二选一：`{policyId, versionId}`（已保存版本）或 `{source, locale, aliasSet}`（与编译端点一致）。
  出参：`{exportable, policies, schema, ruleId, profile, controls, probes:{count, agreed}, diagnostics}`；不可导出时 HTTP 200 + `exportable:false` + 诊断。
- **鉴权**：端点要求可验证身份（aster-cloud 的 HMAC 内部调用或 API Key），纳入 `ApiKeyAuthFilter` 与 `InternalCallerFilter` 的双通道保护；不接受匿名或仅凭 `X-Tenant-Id` 的调用。
- **审计**：每次成功导出都在审计哈希链追加 `POLICY_EXPORTED` 事件：源码 SHA-256、`target:"cedar"`、策略文本与 schema 各自 SHA-256、规则 id、探针条数；入参为已保存版本时附版本 id，调用方另可附自述引用（如 aster-cloud 的策略 id 与版本号，标为 `claimedRef`，未经 aster-api 核验）。哈希键加入审计元数据的身份键白名单，避免被 PII 脱敏误改。
- **aster-cloud 版本不在 aster-api**：cloud 的策略版本存在 cloud 自己的库里，详情页导出走源码模式（源码、别名集、按内容检测的语言）并附 `claimedRef`。
- **aster-cloud**：策略详情页在激活版本可导出时提供「导出为 Cedar」，下载 `<module>.cedar` 与 `<module>.cedarschema.json`，诊断在编译诊断同一面板展示；四语文案（hi 用英文）。不读取任何租户凭据。

## 7. 测试与发版

- core：源码 → Cedar 文本 + schema 的黄金测试；E720–E722 各用例；首匹配顺序用例。
- aster-api：基于 `cedar-java` 的等价性测试；故意破坏的 emitter 必须被 E723 捕获；E724 用例；端点与审计事件测试。
- 样本：guard 探针策略与新样本 `guard-cedar-sample`（角色、context 数值、四种结论、Let）均须导出且探针全部一致。
- aster-cloud：按钮与下载路径单测；本地栈 Playwright 一次。
- 发版：core 改动与新错误码随下一次发版列车；ts 只同步错误码表。

## 8. 不做 / 后续

- 直推 AWS Verified Permissions（凭据、部署状态、漂移）。
- Cedar 导入、guard 内嵌 Cedar 预检。
- Decimal 支持（需要对齐 Cedar decimal 语义）、跨规则调用的内联、参数注解式映射。
- 编辑器内实时 Cedar 预览（需 TS 实现或 WASM）。

## 9. 写回与验收

- ADR 0039 §1 行 6 已回写为「6a → ADR 0046；6b → ADR 0047」。

### 9.1 实施中的决定

- **运行时镜像改为 glibc（用户决定，2026-10-12）**：`cedar-java` 4.10.0 只随附 glibc 原生库，原 Alpine（musl）JRE 无法加载（强制加载 SIGSEGV）。aster-api `Dockerfile.jvm` 改用 `azul/zulu-openjdk-debian:25-jre`（同厂商 glibc 版），镜像约增大 100 MB；Truffle 运行时与 Cedar 原生库均在新镜像上验证。`runner/Dockerfile`、`Dockerfile.pluggable`、`Dockerfile.e2e` 仍为 Alpine，不加载 Cedar，未改。
- **导出并发闸（用户批准，2026-10-13）**：导出含逐探针双引擎求值，属重计算；仿 `/evaluate-source` 的信号量模式，同时进行的导出数受 `aster.cedar.export.max-concurrent` 限制（默认 2），占满时立即返回 503 + `Retry-After: 1`，错误码 `cedar_export_busy`；aster-cloud 路由原样透传 503，面板显示服务端提示。限流只计请求数、不限并发工作量，故二者并存。
- **下载件与审计对账**：面板原样下载策略文本，schema 以紧凑 JSON 下载，两个文件的 SHA-256 分别等于审计 `policiesHash` / `schemaHash`（见 §9.2 修复）。

### 9.2 栈验收记录（2026-10-13，本地 podman 全栈）

分支 `feat/adr0047-cedar-export`：aster-lang-core bec2ac4、aster-lang-ts efad5a5、aster-api 44ec451（镜像构建点）、aster-cloud d0e55f97（worktree `aster-cloud-adr0047`，含验收中发现的修复，见下）。aster-api 经 composite build 使用本地 core。

| 步骤 | 命令/路径 | 结果 |
|---|---|---|
| 重建 api | `./gradlew --offline quarkusBuild -x test` → `podman build -f Dockerfile.jvm -t localhost/aster/policy-api:stack .` → 以原环境变量重建 `aster-api` | 镜像基于 `azul/zulu-openjdk-debian:25-jre`；`/q/health/ready` UP（数据库、Redis、truffle-runtime）；Cedar 原生库在容器内加载并完成导出 |
| cloud | 独立容器 `cedar-cloud`（:3101，同 `aster-stack` 网络与 `aster_cloud` 库，bind-mount 特性分支 worktree，`.env*` 以空文件覆盖，`E2E_LOCAL_STACK=1`，与 api 共用 `ASTER_PLAN_GATE_HMAC_KEY`）；容器内 `pnpm db:migrate` | 迁移成功；导出经 HMAC 内部通道到达 aster-api |
| 浏览器 E2E（新用例） | `BASE_CLOUD=http://localhost:3101 pnpm exec playwright test src/__tests__/e2e-browser/local-stack/cedar-export.spec.ts --project=chromium-desktop --workers=1` | 2/2 通过：源码与 `guard-cedar-sample.aster` 逐字一致的策略显示 `Verified against Cedar on <n> probes`（n 即响应 `probes.count`，审计记录为 30），下载 `guard.cedar.sample.cedar` 与 `guard.cedar.sample.cedarschema.json`，前者含 `@aster_rule("GCS-001")`；`Text.length` 策略列出 E721 且无下载、无审计事件 |
| 审计 | `aster_policy.audit_logs`（只读查询） | `POLICY_EXPORTED`，tenant `t1`、模块 `guard.cedar.sample`、`ruleId GCS-001`、`probeCount 30`、`target cedar`、`claimedRef {"cloudPolicyId":"88b03087-4648-4265-ad8c-81e7b4e59e64","version":"1"}`；`policiesHash 6e04385b…cd629`、`schemaHash 46ed9d43…3975a` 与下载文件 SHA-256 逐一相等（用例内断言） |
| Cedar CLI 交叉核对 | `cargo install cedar-policy-cli --locked`（4.13.0，约 60 s）；`cedar validate --schema guard.cedar.sample.cedarschema.json --policies guard.cedar.sample.cedar --schema-format json` | `policy set validation passed, no errors or warnings`；另以 `cedar authorize` 抽查 4 个请求：`delete_customer_record` → Deny（GCS-001#1，require_approval）、`refund` amount 20000 → Deny（#3，escalate）、`refund` amount 5 → Allow（#4）、suspended 角色 → Deny（#2，deny），与 Aster 结论一致 |
| local-stack 全套 | 同上目录，`--workers=1`，带 `E2E_GUARD_API_KEY`（m-free）与 `CP_API_KEY` | 11 个中 5 通过（cedar-export ×2、credit-pilot、team-business-roles、whatif-matrix），3 失败、3 未运行（同文件串行跳过），失败均为环境原因，见下 |

验收中发现并修复（aster-cloud 1b757382，独立提交，附单测）：面板把 schema 以缩进格式写入 `.cedarschema.json`，文件 SHA-256 与审计 `schemaHash`（aster-api 对紧凑 JSON 取哈希）不符，下载件无法按哈希与审计链对账；改为紧凑输出。新浏览器用例同时断言两个下载文件哈希与审计记录一致。

环境记录：

- 栈库 `aster_policy` 在 2026-10-12 被一次 `@QuarkusTest` 运行重建：`flyway_schema_history` 68 条记录的安装时间落在同一秒内，并含仅测试加载的 `db/seed/V99.0.0` 与 `db/migration-test/V9000.0.0`，与测试配置 `quarkus.flyway.clean-at-start=true` 及其 locations 完全吻合；栈 `aster-pg` 占用宿主 55432 端口（`sdd-pg` 已改为 55433），按旧配方以 `localhost:55432` 跑测试即会清空栈库。后果：生产配置下 Flyway 校验因上述两条「本地未解析」的迁移拒绝启动——本次以 `QUARKUS_FLYWAY_VALIDATE_ON_MIGRATE=false` 启动 api（未改动库内数据）；guard 种子策略 `guard.customer` 与原有 guard 决策随之丢失，`approvals-inbox`（补待审批 404 `policy_not_found: guard.customer.decide`）、`evidence-export`（证据包无 guard-approval 审阅人）、`policy-logs-guard-link`（cloud 执行记录指向的决策已不在 api 库）因此失败，与本 ADR 改动无关。恢复需重新播种栈库（`guard.customer` 策略与待审批），本次按约束未触碰栈数据。
- 首次试跑新用例时 `POST /api/policies` 因未带 `Origin` 被 CSRF 校验拒绝（403 `csrf_forbidden`），属用例问题，已在用例中补同源 `Origin` 头。
