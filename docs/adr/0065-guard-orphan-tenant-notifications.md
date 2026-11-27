# ADR 0065：Guard 对账——critical 孤儿动作的租户通知

- 状态：**PROPOSED**（2026-11-28；待产品负责人裁决 §10 的 Q1–Q7）
- 提出：2026-11-28；来源 wontlost-ltd/aster-cloud#843（[ADR 0061](0061-guard-orphan-governance-ui.md) Q5 的延期项）
- 相关：[ADR 0054](0054-guard-execution-point.md) §10（对账契约：§10.2 查询端点、§10.3 类别与 `GUARD_RECONCILIATION`、§10.4 告警）、[ADR 0061](0061-guard-orphan-governance-ui.md)（治理页、确认权限 §3.1、签真实角色的窄客户端 §3.2、不可信文本 §3.6、Q1 / Q2 / Q5 / Q6）、ADR 0042 §5.3（Guard 站内通知先例：只发站内、fire-and-forget）
- 修订：无。本 ADR **不改** ADR 0054 §10.2 的对外查询契约，只新增一个 cloud 专用的内部只读端点（§3.4.2）
- 实现仓：`aster-api`（内部只读通知源，小）、`aster-cloud`（主：cron、通知状态表、通知类别与文案、开关）；`k3s` 不需要改动
- 列车：不绑定语言列车；aster-api 端点先部署（无调用方时无行为），cloud 随后上线且开关缺省关闭（§4）

---

## 0. TL;DR

| 主题 | 决定（一句话） |
|---|---|
| 哪些类别通知 | 只通知 **critical**（今天 5 类，与 `AsterGuardBypassSuspected` 的正则同一集合）；按 `severity` 列判定，不在 cloud 硬编码 kind 名单。warning / info 永不通知 |
| 收件人 | 与「能确认」同一集合：团队 `owner` / `admin`；个人租户本人。发送时现查（`freshDb`），`member` / `viewer` 不收 |
| 渠道 | 站内通知（现有 `Notification` 表 + 铃铛）为主；邮件作为第二阶段，独立开关、缺省关闭 |
| 去重与限频 | 每租户每轮 cloud 轮询**至多一条**通知（汇总全部新孤儿，不按孤儿逐条发）；每租户站内 1 小时安静期，期间新增的孤儿顺延累计到下一条，不丢；邮件每收件人每租户 24 小时至多一封 |
| 通知内容 | 租户名、按 kind 的新增计数与总数、检出时间范围、链接 `/governance/reconciliation?tenant=<id>&severity=critical`。**不含** `detail`、`executorId`、`source`、资源 id、`decisionId` 等任何上报方可控文本 |
| cloud 怎样得知新孤儿 | **cloud cron 轮询 aster-api 新增的跨租户内部只读通知源**：`…/orphan-feed/tenants` 按租户给出 OPEN critical 孤儿的最大 id，`…/orphan-feed/summary` 给出某租户水位之后的按 kind 汇总；cloud 按租户记水位（已通知到的最大孤儿 id）。不做 aster-api 推送，不读链事件 |
| 与确认的关系 | 通知源只给**读取时仍为 OPEN** 的孤儿：通知前已确认的不通知；已通知后水位越过它，永不重发；确认本身不触发通知 |
| 上线 | 平台开关 `guard.orphan_notifications.enabled`（缺省关）与 `guard.orphan_notifications.email.enabled`（缺省关）；先 api 后 cloud |

---

## 1. 现状实证（aster-api `044adeaf`、aster-cloud `a4438877`、k3s `3df6555`，均为 `origin/main`）

| 事实 | 出处 |
|---|---|
| 对账每 5 分钟一轮（Quartz `0 */5 * * * ?`），每个副本都跑，同一租户靠 advisory 锁与 `min-interval`（PT2M）只处理一次 | aster-api `application.properties:340,342`；`GuardReconciliationScheduler.java:26-31`；`GuardReconciliationService.java:133-141` |
| 每租户一个事务：先取租户对账锁，再检测、解除、计数、写链、回填 run 行；孤儿行只在这个事务里插入。因此**同一租户**的孤儿 id 按提交顺序单调递增（下一轮在上一轮提交前拿不到锁） | `GuardReconciliationService.java:29-36,119-167` |
| 一次实际执行的对账都会写 `guard_reconciliation_runs` 行（无变化也写），保留 `P30D`；只有「有新孤儿或有解除」时才写链事件 `GUARD_RECONCILIATION`（metadata `newByKind`、`newOrphanIds[≤64]`，超出加 `newOrphansTruncated`） | `GuardReconciliationService.java:152-164`；`GuardReceiptBuilder.java:251-267`；`application.properties:351` |
| 孤儿行 `first_run_id` 指向检出它的 run；`status` / `severity` 落列；OPEN 行不随保留期删除；有 `(tenant_id, kind) WHERE status = 'OPEN'` 部分索引 | `V6.38.0__guard_reconciliation.sql:52-79`；`GuardReconciliationService.java:56-57` |
| critical 共 5 类，均不可自动解除（只能确认）；warning 1 类、info 2 类均会自动解除 | `OrphanKind.java:13-30` |
| `GET /api/v1/guard/reconciliation` 只给**最近一次** run（`lastRun`），不给 run 历史；轮询间隔内跑过多轮时，前几轮的新增从摘要里看不出来 | `GuardReconciliationResource.java:71-81`；`GuardOrphanAdminService.java:64-74` |
| 列表 `GET …/orphans` 按租户、`id desc`、`before` 游标；cloud 的对账调用全部经 `createReconciliationClient(tenantId, userId)` 按**用户**现查角色签名 | `GuardReconciliationResource.java:83-99`；cloud `src/lib/guard-reconciliation/access.ts:95-109` |
| 链事件按类型读取只有 `GET /api/v1/audit/type/{eventType}`：类级 `ADMIN`、按租户、`page/size` 偏移分页 | aster-api `AuditLogResource.java:33-35,85-91` |
| cloud 现有 Guard 通知只发站内（`guard.approval_requested` / `guard.approval_decided`），收件人现查，`createNotification` 失败只记日志、不抛 | cloud `src/lib/guard-notifications.ts:1-43`；`src/lib/notifications.ts:86-113`；ADR 0042 §5.3 |
| `Notification` 表：`id text PK, userId, kind, data jsonb, readAt, createdAt`，无去重键；铃铛按 `kind` 模板渲染并轮询未读数 | cloud `src/db/schema.ts:1567-1582`；`src/components/notifications/notifications-bell.tsx:91-171,182-197` |
| cloud 已有每 5 分钟调用 aster-api 的 cron（`guard-deploy-sync`），以系统身份 `system:guard-deploy-sync` 签名；cron 由 `worker.js scheduled()` 按 `cron-registry.ts` 派发，`runCronOnce` 用 `CronJobLease` 按窗口去重；注册表以 cron 表达式为键，**一个表达式只能对应一个任务** | cloud `src/app/api/cron/guard-deploy-sync/route.ts:22-44`；`src/lib/guard-policy-deploy.ts:30,241-259`；`src/lib/cron-registry.ts:212-218,236`；`wrangler.toml:72-94` |
| cloud → aster-api 的 `/api/internal/*` 由各资源自行验签，共享 plan-gate HMAC key，缺 key 即拒（fail-closed）；协议不统一：快照推送用 v2（`method\npath\nts\nnonce\nbodyHash`，nonce 一次性，仍保留可配置的 v1 兼容），`apikey-cache` 失效只验 v1（`method\npath\nts`，无 nonce） | cloud `src/lib/snapshot-pusher.ts:63-64`、`src/lib/api-signing.ts:446-451`；aster-api `SnapshotPushResource.java:139-188`、`ApiKeyCacheResource.java:59-90` |
| aster-api → cloud 也有推送先例，但都是 fire-and-forget、**无重试**；生产里 `ASTER_CLOUD_INTERNAL_URL=https://aster-lang.cloud`（经 Cloudflare 边缘） | aster-api `AiUsageReporter.java:48-98`；k3s `apps/aster-lang/cloud/deployment.yaml:292-305` |
| 邮件经 Resend，只在 SaaS 构建可用（on-prem 无 SDK）；没有用户级通知偏好 / 退订表 | cloud `src/lib/resend.ts:1-60`；`src/db/schema.ts`（无偏好表） |
| 平台开关由 `platform-settings` 存储、管理员卡片切换，各 isolate 约 60 s 内生效 | cloud `src/components/admin/feature-flags-card.tsx:8-54`；`src/lib/platform-settings.ts` |
| 运维告警：`AsterGuardBypassSuspected`（5 个 critical kind 任一 > 0，`for: 0m`）、`AsterGuardExecutionLost`、`AsterGuardReconciliationStale`；info 不告警 | k3s `apps/aster-lang/observability/prometheus-rule.yaml:186-224` |
| 确认权限：团队 `admin` / `owner`、个人租户本人（`SOLO_TENANT_ROLE = owner`）；`viewer` 连看都不能看 | cloud `access.ts:60-71`；`src/lib/team-permissions.ts:29`；ADR 0061 §3.1、Q1 / Q2 |

---

## 2. 目标与非目标

**目标**：出现 critical 孤儿（疑似 Guard 绕过）时，能处置它的租户管理员在合理延迟内（正常负载下的目标 ≤ 10 分钟；对账按候选租户串行，一轮没有硬性完成时限，因此这不是上限）收到一条汇总通知并一键进入治理页；不刷屏、不重复、不泄露上报方文本；aster-api 或 cloud 短暂不可用时事后补发而不是丢失。

**非目标**：warning / info 通知；按孤儿逐条通知；用户自定义规则或订阅；Slack / webhook 等外部渠道；从通知里直接确认；改对账判定、告警或 ADR 0054 §10.2 的对外契约；改变 ADR 0061 §3.2 的「只有 `access.ts` 签非 `member` 角色」不变式。

---

## 3. 决定

### 3.1 D1 哪些类别通知

| 选项 | 说明 | 取舍 |
|---|---|---|
| **A. 只通知 critical** | 5 类：`side_effect_without_decision`、`side_effect_unconsumed`、`side_effect_mismatch`、`consume_on_non_allow`、`result_without_consume` | 与运维告警同一口径；全部只能人工确认，正是需要管理员动手的那类 |
| B. critical + warning | 加 `consumed_without_result` | 它会在补到结果后自动解除，且运维侧已有「≥ 5 持续 30 m」的阈值告警；逐次通知是噪声 |
| C. 全部 | 加 info（`allow_unconsumed`、`orphan_receipt`） | 旧 SDK 0.1.0 与 from-evidence 的 ALLOW 会大量产生 `allow_unconsumed`（ADR 0061 Q3），必然淹没 critical |

**推荐 A**。判定用孤儿行的 `severity = 'critical'`（由 `OrphanKind` 唯一决定并落列），通知源在 aster-api 侧过滤，cloud 不维护第二份 kind 名单；aster-api 新增 critical kind 时自动纳入通知，cloud 渲染未知 kind 时归入「其他」计数（`kinds.ts` 的测试仍会按 ADR 0061 §4 提醒同步）。

### 3.2 D2 收件人

| 选项 | 说明 | 取舍 |
|---|---|---|
| **A. 能确认的人** | 团队 `owner` / `admin`；个人租户本人 | 收到即能处置；与 ADR 0061 Q2 一致 |
| B. 能看的人（≥ `member`） | 加团队 `member` | `member` 不能确认，只能转告管理员；人数大的团队刷屏 |
| C. 租户可配置 | 自选收件人 / 角色 | 需要新的设置 UI 与存储，YAGNI |

**推荐 A**。发送时以 `freshDb` 现查（被降级的管理员下一条就收不到）；团队租户取 `teamMembers` 中角色等级 ≥ `admin` 的成员，判定沿用 `getRoleLevel`；个人租户取 `users.id = tenantId` 且未删除的用户；cloud 里既不是团队也不是用户的租户 id（已删团队等）不发、只记日志并推进水位。`viewer`、非成员永远不收。

### 3.3 D3 渠道、去重、限频与内容

**渠道**

| 选项 | 取舍 |
|---|---|
| 只站内 | 零新基础设施（`Notification` 表、铃铛、四语文案）；但管理员不在线就看不到，而 critical 恰是要尽快处理的 |
| 只邮件 | 离线可达；但 on-prem 没有发信能力，cloud 也没有用户级偏好 / 退订，且邮件是不可撤回的外发 |
| **两者，分阶段** | 第一阶段站内；第二阶段邮件，独立开关、仅 SaaS（`CAN_RESEND`） |

**推荐「两者，分阶段」**：站内是事实来源（与 ADR 0042 §5.3 一致），邮件只是同一条通知的外发副本，等站内跑稳、Q3 裁决后再开。邮件属于安全事件的事务性通知，不提供退订，但每收件人每租户 24 小时至多一封（见下）。

**去重**：一条通知 = 「某租户在一轮 cloud 轮询里看到的、水位之后的全部新 critical 孤儿」的汇总，不按孤儿、也不按 aster-api 的 run 发（一轮轮询可能覆盖多个 run）。幂等靠两层：

1. 通知行 id 确定化：`guard-orphans:<tenantId>:<maxOrphanId>:<userId>`，插入 `ON CONFLICT (id) DO NOTHING`，同一批次重复执行不会多出第二行（`Notification.id` 本就是 text 主键，无需改表）。
2. 水位推进用比较后写：`notifiedThroughId` 只在 `< maxOrphanId` 时更新，与通知行插入在**同一事务**；并发的两个 tick 只有一个能推进。

**限频（安静期）**：每租户站内通知间隔至少 `PT1H`。安静期内看到新孤儿时**不推进水位**，到期后的第一轮把期间累积的新孤儿合成一条发出——不丢、只延后。首条通知不受安静期影响（从无到有立即发）。邮件另有每收件人每租户 `PT24H` 的上限，期间站内照发。

**内容**（`kind = 'guard.orphans_detected'`）：

```
{ tenantId, tenantName, newTotal, byKind: { "<critical kind>": n }, firstDetectedAt, lastDetectedAt,
  maxOrphanId, runIds: [≤ 8 个 first_run_id] }
```

链接 `/governance/reconciliation?tenant=<tenantId>&severity=critical`（页面既有参数，ADR 0061 §3.7）。`tenantName` 取 cloud 侧团队名（个人租户显示本地化「个人」）。**不放**孤儿 `detail`、`executorId`、`source`、`actionName`、资源 id、`decisionId`——这些是上报方可控文本（ADR 0061 §3.6），且 detail 含主机名与资源 id（ADR 0061 Q1 的理由），通知会被转发、截图、外发邮件，范围比治理页大。`runIds` 只用于与链事件 `GUARD_RECONCILIATION` 对照，不展示给用户。

### 3.4 D4 cloud 怎样得知新孤儿

#### 3.4.1 选项

| 选项 | 做法 | 优点 | 问题 |
|---|---|---|---|
| A. cloud 逐租户轮询现有端点 | cron 对每个候选租户以系统身份调 `GET …/orphans?status=OPEN&severity=critical`，按租户水位取新增 | aster-api 零改动 | ① cloud 不知道哪些租户会产生孤儿：只上报副作用、从未部署策略的租户正是 `side_effect_without_decision` 的来源，用 `GuardPolicyDeployment.tenantId` 作候选会漏；枚举全部团队 + 用户则每 5 分钟 O(租户数) 个签名子请求 ② 摘要只有 `lastRun`，不能代替列表 ③ 每次调用都占该租户的内部限流桶 |
| B. aster-api 推送 | 对账事务提交后，有新 critical 孤儿时签名 POST cloud `/api/internal/guard/orphans`（`{tenantId, runId, byKind, orphanIds}`），cloud 按 `(tenantId, runId)` 幂等 | 延迟最低（秒级） | 现有推送都是 fire-and-forget、无重试：cloud / Cloudflare 边缘不可用即丢；要可靠就得在 aster-api 加发件箱表 + 重试调度 + 多副本认领，是新基础设施；新增一条写入 cloud 的通道 |
| C. cloud 读链事件 | 逐租户调 `GET /api/v1/audit/type/GUARD_RECONCILIATION` | 事件已存在 | 端点类级 `ADMIN`：cloud 要在 `access.ts` 之外签 admin，破坏 ADR 0061 §3.2 的不变式；偏移分页无游标；`newOrphanIds` 截断到 64；事件也在「只有解除」时写；仍要枚举租户 |
| **D. cloud 轮询跨租户内部通知源** | aster-api 新增两个只读内部端点：按租户的 OPEN critical 最大 id（①），与某租户水位之后的按 kind 汇总（②），只含计数、kind、时间、id；cloud cron 每 5 分钟拉一次 ①，只对超过水位的租户调 ② | 子请求数 = ① 的页数 + 本轮有新增的租户数，与租户总数无关；复用现有 cron 注册表 / 租约 / v2 签名 / plan-gate key；aster-api 无状态（水位在 cloud）；失败即下轮按水位补发 | aster-api 要加两个小端点，且是一项新的跨租户读能力（§3.4.2「信任面」）；正常负载下延迟约为对账周期 + 轮询周期（≈ 10 分钟），不是硬上限 |

**推荐 D**。它是「轮询」的形态（可靠性来自重读，而不是来自投递），但把「哪些租户有新孤儿」这个 cloud 回答不了的问题交给唯一知道答案的 aster-api，用按租户聚合的跨租户查询取代 O(租户数) 次调用。B 的秒级延迟对一个本来 5 分钟一轮的检测器意义不大，却要求新建发件箱；以后若需要更低延迟，可以让 aster-api 发一个**无载荷的唤醒提示**，cron 仍是事实来源。A 保留为 aster-api 端点无法上线时的退路（候选租户取 `GuardPolicyDeployment.tenantId`，并在文档中写明漏报面）。

#### 3.4.2 通知源契约（aster-api，两个内部只读端点）

通知源分两步：先按租户给出「OPEN critical 孤儿的最大 id」，cloud 只对超过自己水位的租户再要「水位之后」的按 kind 汇总。两步都按租户聚合、不返回逐行列表，因此已通知却仍 OPEN 的存量（OPEN 不随保留期删除）再多，也不会占满分页、饿死后来的新孤儿。

**① 租户索引** `POST /api/internal/guard/orphan-feed/tenants`，请求体 `{ "detectedSince": "<ISO-8601>", "afterTenant"?: "<tenantId>", "limit"?: 1..500 }`：

```
{ "items": [ { "tenantId", "maxId" } ], "next"?: "<tenantId>" }
```

条件 `status = 'OPEN' AND severity = 'critical' AND detected_at >= :detectedSince`，`GROUP BY tenant_id`，按 `tenant_id` 升序以 `afterTenant` 翻页，`limit` 缺省 500。每个租户在结果里恰好一行，页数只随「有 OPEN critical 的租户数」增长。

**② 租户增量** `POST /api/internal/guard/orphan-feed/summary`，请求体 `{ "tenantId", "afterId": <≥ 0>, "detectedSince" }`：

```
{ "tenantId", "maxId", "total", "byKind": { "<critical kind>": n }, "firstDetectedAt", "lastDetectedAt", "runIds": [≤ 8] }
```

条件同上，另加 `tenant_id = :tenantId AND id > :afterId`；`runIds` 为这些行的 `first_run_id` 去重后最新的至多 8 个；无匹配时 `total = 0`、`maxId = afterId`。计数与 `maxId` 必须出自**同一条**查询（同一快照、同一过滤条件），水位才能无缝推进（§3.4.3）。单行响应，不分页。

两个端点共同的约束：

- 用 POST 是为了让参数进签名：快照推送使用的 v2 canonical 只绑 `bodyHash`、不绑查询串（aster-api `SnapshotPushResource.java:147-160`）。
- `detectedSince` 不得早于 `now − aster.guard.reconciliation.lookback`（缺省 P7D），否则 400 `invalid_query`；其他非法参数同样 400。
- 鉴权：`X-Aster-Timestamp / X-Aster-Nonce / X-Aster-Signature` 的 v2 签名，共享 `aster.plan-gate.hmac-key`；缺 key 403（fail-closed）、签名错 401、nonce 重放 409；**不接受 v1**（不设 `SnapshotPushResource` 那样的迁移开关）；不对 Bearer 开放（路径在 `/api/internal/` 下，不进 `InternalCallerFilter` 的双通道集合）。验签逻辑从 `SnapshotPushResource` 抽成共用的 v2 校验器复用，不再复制第三份。
- 只读、不写链、不计配额；响应不含任何上报方文本，只有租户、kind、计数、时间、id。
- **信任面**：这是一项新的**跨租户读**能力（既有 `/api/internal/*` 资源都是按路径参数作用于单个租户或用户的写 / 失效操作）。接受它的依据：同一把 `aster.plan-gate.hmac-key` 也是 `InternalCallerFilter` 的 cloud-bff 通道密钥（`application.properties:436-437`；`InternalCallerFilter.java:71-72,465-476`），持 key 方今天已能以任意签名的 `X-Tenant-Id` 调 `GET /api/v1/guard/reconciliation/orphans` 读到含 `detail` 的完整孤儿；通知源暴露的是其严格子集。代价是该 key 泄露时可一次枚举全部租户的 critical 计数而不必逐租户猜 id——这不改变 key 泄露本就是全租户事故的性质，但应写进该 key 的轮换说明。若产品负责人不接受跨租户读，退回选项 A（Q7）。
- 查询走 `(tenant_id, kind) WHERE status = 'OPEN'` 部分索引。上线前用生产规模数据 `EXPLAIN` 确认；若 ② 的 `id > :afterId` 出现大范围扫描，再加部分索引 `(tenant_id, id) WHERE status = 'OPEN' AND severity = 'critical'`（迁移另立任务，不在本 ADR 的必做范围）。

#### 3.4.3 cloud cron 算法

新任务 `guard-orphan-notify`，cron `2-59/5 * * * *`（每 5 分钟、错开 2 分钟：aster-api 在整 5 分钟处开跑；且注册表要求表达式唯一，`*/5` 已被 `guard-deploy-sync` 占用）；`runCronOnce` 租约按 5 分钟窗口去重。每个 tick：

1. 开关 `guard.orphan_notifications.enabled` 关 → `ran: false`，不调用 aster-api。
2. 以 `detectedSince = now − NOTIFY_LOOKBACK`（缺省 P7D，与 aster-api lookback 一致）翻页拉取 ①。
3. 对每个 `maxId > notifiedThroughId` 的租户（`GuardOrphanNotifyState(tenantId PK, notifiedThroughId, lastNoticeAt, lastEmailAt…)`，无行视为 0）：安静期未过则跳过（不推进水位）；否则以 `afterId = notifiedThroughId` 调 ②；`total = 0`（期间全被确认）则把水位推进到 ② 的 `maxId` 后跳过。每个 tick 至多处理 50 个租户、子请求并发 4（同审批收件箱与治理页摘要卡），余下的下一轮继续——跳过的租户水位不动，不会丢。
4. 现查收件人（§3.2）；在一个事务里插入每个收件人的通知行（确定化 id 用 ② 的 `maxId`，`ON CONFLICT DO NOTHING`），并以比较后写把水位推进到 ② 的 `maxId`、写 `lastNoticeAt`。收件人为空时只推进水位。
5. 第二阶段：事务提交后按收件人发邮件，成功后写 `lastEmailAt`；失败只记日志，不重试、不回滚站内通知（邮件尽力而为，站内是事实来源）。

**为什么按租户水位是安全的**：同一租户的孤儿只在持有该租户对账锁的事务里插入（`GuardOrphanDetector` 只由 `reconcileLocked` 调用），下一轮在上一轮提交前拿不到锁，所以同一租户里先可见的 id 永远小于后可见的 id（§1）。② 在一次查询里读到 `id > afterId` 的全部已提交行，并返回其中的 `maxId`；此后才提交的同租户行 id 必然更大，下一轮照样取到。跨租户的提交先后无关紧要：cloud 不使用跨租户的全局游标，① 每轮都重新按租户聚合。

#### 3.4.4 失败模式

| 情形 | 结果 |
|---|---|
| aster-api 不可用 / 通知源 5xx / 超时 | ① 失败则本 tick 不改任何状态；某租户的 ② 失败只跳过该租户、水位不动。下一轮按水位补发，延迟 ≈ 故障时长 + ≤ 5 分钟 |
| cloud cron 漏跑（Worker 故障、部署窗口） | 同上；只要恢复在 `NOTIFY_LOOKBACK`（7 天）内就不丢 |
| 重复触发（Cloudflare 重试 + 人工 curl） | 租约去重；即使两次都执行，确定化 id + 比较后写只产生一组通知行 |
| 插入通知后进程崩溃 | 通知行与水位同事务，要么都在、要么都不在 |
| 通知前孤儿已被确认 / 已解除 | 通知源只统计读取时仍 OPEN 的行，不通知；全部已确认时 `total = 0`，只推进水位 |
| 通知后孤儿被确认 | 通知保留（链接打开的是实时状态）；水位已越过，永不重发；确认不可撤销（ADR 0061 Q6），不存在「重开再通知」 |
| 长期不处置的 OPEN 孤儿 | 只通知一次；之后由治理页摘要卡与运维告警持续可见，不做周期性提醒（Q5） |
| 收件人在检出与发送之间变化 | 以发送时现查为准 |
| 租户在 cloud 中已不存在 | 不发，推进水位，记日志 |
| HMAC key 未配置 / 不一致 | 403 / 401，tick 失败并记 error；心跳上报失败（§5） |
| 上线时已有大量 OPEN critical | 每个租户至多一条汇总通知（7 天窗口内的存量），不会逐条刷屏（Q6） |
| 已通知但长期 OPEN 的存量很多 | ① 按租户聚合、② 从水位之后起算，存量不进入任何分页，不会挤掉新孤儿 |
| 一轮内有新增的租户超过 50 个 | 余下租户水位不动，下一轮继续；`deferredBacklog` 计数可见（§5） |

### 3.5 与确认、治理页的关系

- 通知不改变任何孤儿状态，也不提供确认入口：确认仍只在治理页、带说明、二次确认（ADR 0061 §3.3）。
- 通知源不经 `createReconciliationClient`，不以用户身份签任何角色；ADR 0061 §3.2 的源码扫描测试不受影响。
- 铃铛点击打开治理页并预选该租户与 `severity=critical`；用户无权查看该租户时（刚被降级）页面按既有规则回退到缺省租户。

---

## 4. 上线与回滚

1. **aster-api**：两个通知源端点 + 共用 v2 校验器 + 测试，随常规部署上线；没有调用方时没有任何行为。
2. **cloud**：迁移新增 `GuardOrphanNotifyState`；新通知类别与四语文案；cron 路由、注册表条目与 `wrangler.toml` 的 `[triggers].crons` 同步加一条；平台开关 `guard.orphan_notifications.enabled` 与 `guard.orphan_notifications.email.enabled` 加入管理员卡片，**缺省都关**。
3. **预发验证**：在测试租户用一个 `MEMBER` key 调 `POST /api/v1/guard/side-effects` 上报一条不带 `decisionId` 的副作用 → 下一轮对账产生 `side_effect_without_decision` → 10 分钟内该租户 admin / owner 收到一条站内通知，`member` 不收；确认后再上报第二条 → 第二条通知只计新增的那一条；在安静期内连报 → 只在期满后合成一条。
4. **生产**：先开站内开关，观察一周（误报率、通知量、延迟）；邮件开关等 Q3 裁决后再开。
5. **回滚**：关开关即停（cron 第一步返回）；状态表与已发通知无害，留存。aster-api 端点可保留或下线，互不依赖。

## 5. 可观测性

- cloud cron 返回 `{ ran, tenantsIndexed, pages, tenantsWithNew, noticesCreated, recipients, deferredQuiet, deferredBacklog, summaryFailed, emailed, maxLagSeconds }`，`maxLagSeconds` = 发送时刻 − 已发通知中最早的 `firstDetectedAt`；另记「仍未通知的最老新增」（被安静期或租户上限推迟的租户里最早的 `firstDetectedAt`），用来观察实际延迟而不是推导值；失败记 `console.error`（Worker 日志）。
- 心跳：注册表条目带 `healthcheckEnv: 'HEALTHCHECKS_GUARD_ORPHAN_NOTIFY_URL'`，`runCronOnce` 前后上报（与既有 cron 同法）；开关关闭时照常上报成功，避免心跳误报。
- aster-api：通知源计数 `guard_orphan_feed_requests_total{outcome}`（`ok | invalid | unauthorized | replayed`）。`unauthorized` 持续出现通常是 key 不一致，可按需在 k3s 加 warning 告警（不是本 ADR 的必做项）。
- 运维告警不变：`AsterGuardBypassSuspected` 仍是运维侧的第一信号，租户通知不替代它。

## 6. 需要的测试

**aster-api**

- 通知源鉴权：缺 key → 403；签名错 → 401；v1 签名 → 401；nonce 重放 → 409；带 Bearer 不可达。
- 过滤：只统计 OPEN + critical；ACKNOWLEDGED / RESOLVED、warning、info 不计；多租户混合；① 每租户一行、`afterTenant` 翻页；② `id > afterId`、无匹配时 `total = 0` 且 `maxId = afterId`；`detectedSince` 早于 lookback → 400；`limit` 越界 → 400。
- 防饥饿：某租户有大量 `id ≤ afterId` 的 OPEN critical 存量时，② 只统计水位之后的新行、① 仍只占一行。
- 响应字段钉住（① `tenantId / maxId`；② 计数、kind、时间、id），不含 detail 或任何上报方文本。
- 共用 v2 校验器重构后，`SnapshotPushResource` 既有测试全部不变通过。

**aster-cloud**

- 通知逻辑（注入假通知源）：按租户水位判新增；同一批次执行两次只有一组通知行；安静期内不推进水位、期满合并发出；首条不受安静期影响；① 失败不改状态、单租户 ② 失败只跳过该租户；`total = 0` 只推进水位；开关关闭不调用通知源；每 tick 50 个租户的上限与顺延。
- 收件人：团队 owner / admin 收，member / viewer 不收；个人租户本人；已删除用户、未知租户不发但推进水位；角色变更即时生效。
- 内容：payload 不含任何 detail / executor / resource / decisionId 字段（快照测试）；未知 critical kind 计入「其他」。
- 铃铛渲染新类别（en / zh / de / hi），链接带 `tenant` 与 `severity=critical`。
- 注册表与 `wrangler.toml` 同步（既有检查扩展一条）；cron 表达式不与 `guard-deploy-sync` 冲突。
- 两仓共用通知源响应夹具（逐字节一致），钉住契约。
- 集成测试（PG）：通知行插入与水位推进同事务；并发两个 tick 只推进一次。

## 7. 实施计划大纲（不在本 ADR 实施）

| 仓 | 任务 |
|---|---|
| aster-api | A1 抽出共用 v2 内部签名校验器（`SnapshotPushResource` 改用它）；A2 `GuardOrphanFeedResource`（`/tenants` 与 `/summary` 两个聚合查询，复用 `guard_orphan_actions`）；A3 指标；A4 测试与共享夹具；A5 ADR 0054 §10.2 加一行指向本 ADR |
| aster-cloud | C1 迁移 `GuardOrphanNotifyState`；C2 通知源客户端（`signInternalV2Headers` 同法签名）；C3 `guard-notifications.ts` 新增 `notifyOrphansDetected`（分组、水位、安静期、收件人、事务）；C4 cron 路由 + 注册表 + `wrangler.toml` + 心跳；C5 `NotificationKind` / payload / 铃铛模板 / 四语文案；C6 两个平台开关与管理员卡片；C7（第二阶段）邮件模板与发送、24 小时上限 |
| k3s | 无必做项；可选：通知源 `unauthorized` 告警 |

顺序：A1–A4 合并并部署 → C1–C6 合并（开关关）→ 预发验证（§4 第 3 步）→ 开站内 → Q3 裁决后 C7。

## 8. 后果

- aster-api 多两个跨租户只读内部端点；它们只暴露租户、kind、计数、时间与 id，受与 cloud-bff 通道同一把 key 保护（§3.4.2「信任面」）。
- cloud 多一个 5 分钟 cron（cron 总数 12 → 13）、一张小状态表（每个出现过 critical 孤儿的租户一行）、一个通知类别与约 8 个文案键 × 4 种语言。
- 正常负载下通知延迟约为对账周期 + 轮询周期（≈ 10 分钟，非硬上限，实际值见 §5 的观测项），安静期内的新增最多再延后 1 小时。
- `Notification` 表行数随「租户 × 管理员 × 每小时至多一条」增长，量级远小于审批通知。

## 9. 被否决的做法

- 按孤儿逐条通知：一次绕过往往同时产生多条，逐条通知等于刷屏。
- 用 `GET /api/v1/guard/reconciliation` 的 `lastRun.newOrphans` 判新增：只有最近一轮，轮询间隔内的早期 run 会被覆盖；也不区分 severity。
- 在 aster-api 记录「已通知」：把 cloud 的产品状态放进账本服务，且多副本要协调；水位放在 cloud 更简单。
- 每次确认后撤回 / 更新已发通知：通知是时点事实，治理页才是实时状态。

---

## 10. 待产品负责人裁决

| # | 问题 | 推荐 |
|---|---|---|
| Q1 | warning（`consumed_without_result`）是否通知 | 否（会自动解除；运维已有阈值告警） |
| Q2 | 收件人是否包括团队 `member` | 否（只发给能确认的 admin / owner 与个人租户本人） |
| Q3 | 邮件是否进入首期 | 否：首期只站内，邮件第二阶段独立开关、仅 SaaS、不提供退订、每收件人每租户 24 小时至多一封 |
| Q4 | 安静期长度 | 站内 1 小时、邮件 24 小时 |
| Q5 | 长期不处置的 OPEN critical 是否周期性提醒 | 否（只通知一次；治理页摘要卡与运维告警持续可见） |
| Q6 | 首次打开开关时，7 天内已存在的 OPEN critical 是否通知 | 是，每租户一条汇总（这些就是需要处置的事件）；更早的不通知 |
| Q7 | 采用 D4 的哪个投递机制；是否接受通知源这项新的跨租户读能力 | D，接受（持同一把 key 今天已能逐租户读到完整孤儿，§3.4.2）；不接受时退回 A |
