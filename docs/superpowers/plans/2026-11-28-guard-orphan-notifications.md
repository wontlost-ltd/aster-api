# Guard 对账 critical 孤儿的租户通知 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 出现 critical 孤儿（疑似 Guard 绕过）时，能确认它的租户管理员（团队 owner / admin、个人租户本人）在约 10 分钟内收到**一条**汇总的站内通知并一键进入治理页；不刷屏、不重复、不含上报方文本；aster-api 或 cloud 短暂不可用时事后补发。邮件作为第二阶段，独立开关。

**Architecture:** aster-api 新增两个跨租户只读内部端点（`POST /api/internal/guard/orphan-feed/tenants`、`…/summary`），只按租户聚合 `guard_orphan_actions` 中 OPEN + `severity='critical'` 的行，v2 HMAC 鉴权（从 `SnapshotPushResource` 抽出共用校验器），无迁移、无状态。cloud 新增 `GuardOrphanNotifyState`（按租户水位）与 `GuardOrphanEmailState`（按收件人邮件限频）两张表、`src/lib/guard-orphan-notify/`（通知源客户端、收件人、载荷、状态存储、每 tick 编排）、cron `guard-orphan-notify`（`2-59/5 * * * *`）、通知类别 `guard.orphans_detected` 与铃铛模板（en / zh / de / hi）、两个平台开关。水位推进与通知行插入同一事务、以「期望旧值」比较后写，保证重试与并发 tick 只产生一组通知。

**Tech Stack:** aster-api：Quarkus、JAX-RS、Hibernate ORM 原生查询、Micrometer、JUnit 5（`@QuarkusTest`、RestAssured）、Gradle。cloud：Next.js（Cloudflare Workers，`runtime = 'nodejs'`）、Drizzle + Postgres（Hyperdrive / `freshDb`）、vitest（unit / integration PG）、React Testing Library、next-intl、Resend（仅 SaaS）。

**Spec:** `docs/adr/0065-guard-orphan-tenant-notifications.md`（ACCEPTED 2026-11-28，§10 Q1–Q7 全部采纳推荐答案）；上游契约 ADR 0054 §10、ADR 0061（治理页、确认权限、不可信文本）；先例 ADR 0042 §5.3。来源 wontlost-ltd/aster-cloud#843。

## 现状（2026-11-28 核对：aster-api `f98b10fb`（含 #624）、aster-cloud `a4438877`）

ADR 0065 §1 的实证表全部成立；计划额外依赖的事实：

| 事实 | 出处 |
|---|---|
| v2 验签逻辑与 nonce 一次性校验私有地内嵌在 `SnapshotPushResource.verify / verifyPreconditions`；nonce 命名空间 `"snapshot-push"`；缺 key → 403 `{"error":"hmac_not_configured"}`；缺头 / 时间戳错 / 过期 → 401 纯文本；签名错 → 401 `{"error":"invalid_signature"}`；重放 → 409 `{"error":"nonce_replay"}`；v1 兼容由 `aster.security.snapshot.accept-legacy-signature`（缺省 true）控制 | aster-api `src/main/java/io/aster/billing/snapshot/SnapshotPushResource.java:135-250` |
| 既有 canonical 契约测试直接调用 `SnapshotPushResource.canonicalV2`（包级静态方法），`SnapshotLegacySignatureRejectedIT` 钉住硬切行为 | aster-api `src/test/java/io/aster/billing/snapshot/SnapshotSignatureCanonicalTest.java:47-75`、`SnapshotLegacySignatureRejectedIT.java:78-150` |
| `NonceService.ensureFresh(scope, nonce, requestHash)` 重放时抛 `WebApplicationException(409)` | aster-api `src/main/java/io/aster/policy/security/NonceService.java:37-47` |
| `/api/internal/*` 被 `RequestSignatureFilter`、`TenantFilter`、`RateLimitFilter` 豁免，`ApiKeyAuthFilter` 不作用于它 | aster-api `RequestSignatureFilter.java:37-42`；`TenantFilter.java:121-149`；`RateLimitFilter.java:120-126`；`ApiKeyAuthFilter.java:23` |
| `public-api-contract.golden` 只锁 `/api/v1/*`，内部端点不入契约文件 | aster-api `src/test/java/io/aster/api/PublicApiContractTest.java:40` |
| 孤儿插入全部是 `INSERT … ON CONFLICT (tenant_id, kind, subject_key) DO NOTHING`，`id BIGSERIAL`；冲突不插入新行；`detected_at` 写本轮逻辑时刻 `:now` | aster-api `GuardOrphanDetector.java:39-97`；`V6.38.0__guard_reconciliation.sql:52-79` |
| 对账锁是 `pg_try_advisory_xact_lock`（事务级，提交才释放） | aster-api `GuardReconciliationService.java:40` |
| 指标约定：Micrometer 点分名 + `tag(...)`，`Counter.builder(...).register(registry).increment()`；Prometheus 名加 `_total` | aster-api `GuardOrphanMetrics.java:34-86` |
| 资源测试夹具：`GuardReconciliationResourceTest.seed(tenant, kind, subject)` / `seedEveryKind`、`ReconcileFixture.tenant(scenario)`；内部签名测试助手 `InternalCallerSigning` | aster-api `src/test/java/io/aster/guard/reconcile/GuardReconciliationResourceTest.java:50-92`；`ReconcileFixture.java:46-60`；`src/test/java/io/aster/security/apikey/InternalCallerSigning.java` |
| 跨仓共享夹具的钉法：两仓各存一份文件，测试断言其 sha256 等于常量 | cloud `src/__tests__/lib/jcs.test.ts:20-23`；aster-api `GuardActionHashVectorsTest.java:46` |
| cloud 出站到 aster-api `/api/internal/*` 的 v2 签名写法（头 `X-Aster-Timestamp / X-Aster-Nonce / X-Aster-Signature`，`node:crypto` 同步实现）只在 `snapshot-pusher.ts` 私有 `callAsterApi` 里；`api-signing.ts` 的 `signInternalV2Headers` 是同一 canonical，但头名为 `X-Internal-Signature`（发往 cloud 自身） | cloud `src/lib/snapshot-pusher.ts:108-150`；`src/lib/api-signing.ts:451-470` |
| cloud 调 aster-api 内部端点的基址 `safeEnv('ASTER_API_INTERNAL_URL') ?? 'http://aster-api:8080'`，key `safeEnv('ASTER_PLAN_GATE_HMAC_KEY')` | cloud `src/lib/snapshot-pusher.ts:18-21`；`src/lib/plan-gate-client.ts:12` |
| cron 路由模板：`requireCronAuth` → `parseCronWindow(req, JOB)` → `runCronOnce(JOB, fn, { acquiredBy, windowStart })`；`runCronOnce` 抢到租约后上报心跳 start / success / fail，`fn` 抛错 → 租约 `failed` 并重抛 | cloud `src/app/api/cron/guard-deploy-sync/route.ts:22-44`；`src/lib/cron-lease.ts:73-166` |
| 新 cron 要同时改四处：`CRON_REGISTRY`、`wrangler.toml [triggers].crons`、`worker.js CRON_DISPATCH`、on-prem 指南 cron 表；三条奇偶测试 | cloud `src/lib/cron-registry.ts:212-218`；`wrangler.toml:72-94`；`worker.js:45-61`；`docs/on-prem/enterprise-deployment-guide.md:174`；`src/__tests__/lib/cron-registry-{worker-sync,onprem-doc}.test.ts`、`cron-registry.test.ts` |
| 平台开关：键常量 + `DEFAULTS` 在 `platform-settings.ts`；管理员路由对「审计布尔开关」要求布尔值并与 `auditLogs` 同事务写（`isAuditedBooleanSetting`）；卡片 `FLAGS` 数组 + `admin.flags` 文案 | cloud `src/lib/platform-settings.ts:27-80`；`src/app/api/admin/platform-settings/route.ts:28-40,127-150`；`src/components/admin/feature-flags-card.tsx:23-54` |
| `NotificationKind` / `NotificationPayloads` 在 `notifications.ts`；铃铛 `renderText` 按 kind 分支，文案在 `notifications` 命名空间（`t.raw` + `fillTemplate`，非 ICU） | cloud `src/lib/notifications.ts:20-80`；`src/components/notifications/notifications-bell.tsx:46-170`；`src/i18n/demo-supplement.ts:1005,3433,5839,8242` |
| `Notification.id` 为 text 主键（无需改表即可用确定化 id） | cloud `src/db/schema.ts:1567-1582` |
| Hyperdrive 缓存非变更查询（60 s），一致性敏感读须用 `freshDb`；`freshDb` 是另一条连接，看不到当前事务的未提交写 | cloud `src/db/fresh-read.ts:1-30` |
| critical kind 表（与 aster-api `OrphanKind` 逐项钉住）与 `severityOf` | cloud `src/lib/guard-reconciliation/kinds.ts:10-55` |
| 有界并发 `createLimiter(limit)` | cloud `src/services/evidence/receipts-client.ts:147` |
| 迁移流程：`drizzle/*.sql` 为准；合并 main 后 CI 构建 `aster-cloud-migrate:<sha>` 镜像并向 k3s 提 migrate 镜像 pin PR，ArgoCD PreSync Job 执行 `drizzle-kit migrate`；**Worker 由 Deploy Worker 工作流独立部署，不等迁移**。CI `check-migration-split` 要求含 `drizzle/*.sql` 的 PR 不含 `src/` 业务代码（`src/db/schema.ts`、`src/__tests__/`、`drizzle/meta/` 除外），否则须 `migration-and-code` 标签与「## 迁移窗口容错」小节 | cloud `docs/migrations.md`；`scripts/ci/check-migration-split.mjs:7-18` |
| 最新迁移 `0069_policy_version_embedded_vocabulary`，下一个编号 0070 | cloud `drizzle/meta/_journal.json` |
| 现有事务邮件均为英文（无用户语言列、无退订表），经 `getResend()`，HTML 以 `escapeHtml` 转义，`CAN_RESEND = IS_SAAS` | cloud `src/lib/resend.ts:29-130`；`src/lib/deployment-mode.ts:75` |

## Global Constraints

- **类别**：只通知 `severity = 'critical'` 的孤儿（今天 5 类）；判定只在 aster-api 通知源的 SQL 里（`severity = 'critical'`），cloud 不维护 kind 白名单做过滤。warning / info 永不通知（Q1）。cloud 渲染时未知的 critical kind 归入 `other` 计数。
- **收件人** = 确认集合：团队 `teamMembers` 中 `getRoleLevel(role) ≥ getRoleLevel('admin')` 且用户未删除；个人租户 `users.id = tenantId` 且 `deletedAt IS NULL`。发送时经 `freshDb` 现查；`member` / `viewer` / 非成员永不收（Q2）。既非团队也非用户的租户：不发、记 `console.warn`、推进水位。
- **开关**（`platform-settings`，缺省都 `false`）：`guard.orphan_notifications.enabled`（站内）、`guard.orphan_notifications.email.enabled`（邮件，第二阶段；仅在站内开关也开且 `CAN_RESEND` 时生效）。两键都是「审计布尔开关」。
- **cron**：`jobName = 'guard-orphan-notify'`，`cron = '2-59/5 * * * *'`，`routePath = '/api/cron/guard-orphan-notify'`，窗口 = 以 `:02` 为起点的 5 分钟桶，`healthcheckEnv = 'HEALTHCHECKS_GUARD_ORPHAN_NOTIFY_URL'`。站内开关关闭时 `fn` 正常返回 `{ ran: false }`（心跳照常 success），不调用 aster-api、不读写状态表。
- **回看窗口**：`NOTIFY_LOOKBACK = 7 天`（与 aster-api `aster.guard.reconciliation.lookback` 缺省 P7D 一致）。首次打开开关时 7 天内的 OPEN critical 每租户至多一条汇总，更早的不通知（Q6）；不周期性提醒（Q5）。
- **限频**：站内每租户安静期 `PT1H`（以 `lastNoticeAt` 计，首条不受限）；安静期内**不推进水位**、不调 ②，到期后合并发出。邮件每收件人每租户 `PT24H` 至多一封（Q4）；邮件不提供退订（Q3）。
- **每 tick 上限**：至多对 50 个租户调 ②，并发 4；余下的水位不动、计入 `deferredBacklog`。
- **幂等**：通知行 id = `guard-orphans:<tenantId>:<maxOrphanId>:<userId>`，`ON CONFLICT (id) DO NOTHING`；水位以**期望旧值**比较后写（`WHERE notifiedThroughId = :afterId`，见 R3），与通知行插入同一事务，比较失败整事务回滚。
- **内容白名单**：载荷只含 `tenantId, tenantName, newTotal, byKind, firstDetectedAt, lastDetectedAt, maxOrphanId, runIds(≤8)`。**绝不**含 `detail`、`executorId`、`executor`、`source`、`actionName`、资源 id、`decisionId`、`subjectKey`（ADR 0061 §3.6）。链接 `/governance/reconciliation?tenant=<encodeURIComponent(tenantId)>&severity=critical`。
- **鉴权（aster-api）**：v2 canonical `method\npath\nts\nnonce\nsha256(body)`，头 `X-Aster-Timestamp / X-Aster-Nonce / X-Aster-Signature`，key `aster.plan-gate.hmac-key`；缺 key → 403（fail-closed）、签名错 / 缺头 / 时间戳过期 → 401、**v1 一律 401**（不设迁移开关）、nonce 重放 → 409；nonce 命名空间 `guard-orphan-feed`。Bearer API key 与 `InternalCallerFilter` 的 v3 头都不能通过。
- **鉴权（cloud）**：`ASTER_PLAN_GATE_HMAC_KEY` 缺失时通知源客户端在发请求前抛 `OrphanFeedError(0, 'hmac_not_configured')`（tick 失败、心跳 fail），**绝不**发未签名请求。
- **通知源契约**：见 ADR 0065 §3.4.2，本计划 T-A2 钉字段；aster-api 严格按 ADR 校验 `detectedSince ≥ now − lookback`；cloud 发送 `now − 7d + 5min`（R2）；`limit` 1..500 缺省 500；`afterId ≥ 0`；非法 → 400 `{"error":"invalid_query"}`。响应时间一律 `Instant.toString()`（UTC ISO-8601）。
- **aster-api 无迁移**；cloud 一个迁移（0070，两张表），单独 PR 先行（见「顺序与 PR 划分」）。
- 新 cloud 文案进 `src/i18n/demo-supplement.ts`（en / zh / de / hi）；两仓代码注释用简体中文；不写「修改说明」式注释。
- 提交信息结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 正文结尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`，引用 `wontlost-ltd/aster-cloud#843`；aster-cloud PR 正文用简体中文。跨仓改动两仓用同一分支名 `feat/guard-orphan-notify`。

## Review Focus

1. **重试 / 并发导致重复通知**：Cloudflare 重试 + 人工 curl 落在同一窗口 → 租约去重；跨窗口的两个 tick 读到同一旧水位 → 只有一个事务的比较后写成功，另一个整事务回滚、`casLost += 1`，通知行只有一组（T-C6 `concurrent commits advance once`，T-C7 `rerun of same batch creates no second row`）。注意 ADR §3.3 的「`< maxOrphanId` 时更新」不足以防止「第二个 tick 以更大的 maxId 重复通知同一批」，计划按 R3 改为期望旧值。
2. **水位竞态**：② 的计数与 `maxId` 必须出自同一条 SQL（同一快照）；cloud 只用 ② 的 `maxId` 推进，不用 ① 的；② 之后才提交的同租户孤儿 id 必然更大，下一 tick 取到（T-A2 `summaryCountsAndMaxIdFromOneStatement`，T-C7 `rows committed after summary are picked up next tick`）。状态读用 `freshDb`，Hyperdrive 缓存的旧水位只会导致比较失败，不会重复。
3. **通知前已确认 / 已解除**：通知源只看读取时仍 OPEN 的行；全部被确认 → ② `total = 0`、`maxId = afterId` → 不写通知、不动水位、清 `pendingSince`；之后 ① 也不再列出该租户（T-A2 `acknowledgedRowsExcluded`，T-C7 `all acknowledged before summary sends nothing`）。
4. **HMAC key 缺失 / 不一致**：aster-api 缺 key → 403、cloud 缺 key → 不发请求即失败；两种都让 tick 抛错 → 租约 `failed`、心跳 fail、状态表不变；不得退化为未签名或 v1（T-A1、T-A3 `missingKeyIs403`、`v1Is401`，T-C2 `refuses to call without key`，T-C8 `feed 403 marks lease failed`）。
5. **积压公平与 7 天窗口**：每 tick 至多 50 个租户；积压按 `pendingSince` 先进先出（T-C7 `backlog is FIFO…`），因此只要持续有新增的租户数长期 ≤ 50 / 5 min，任何租户都会在下一 tick 被处理。若某租户在 `pendingSince` 之后 7 天仍未推进（例如它的 ② 持续失败），超出 7 天的孤儿会落出 `detectedSince` 而**不再通知**——这与 ADR §3.4.4「恢复在 7 天内就不丢」一致；runbook（T-C9）把 `oldestPendingSeconds > 86400` 列为需人工排查的信号。
6. **没有管理员的租户**：团队只剩 member / viewer、个人租户用户已删、租户 id 在 cloud 不存在 → 不写任何通知行、推进水位、不写 `lastNoticeAt`、`console.warn`；不得在每个 tick 重复查询或重复记日志（T-C5、T-C7 `tenant without admins advances watermark only`）。
7. **不可信文本外泄**：上报方可控字段即便出现在 ② 响应（契约违约）也进不了载荷（白名单构造 + 快照测试，T-C5 `payload is whitelisted`）。
8. **Cloudflare 是否接受 `2-59/5 * * * *`**：T-C8 Step 4 用 `wrangler deploy --dry-run` 之外，还须在 PR 描述中注明需在预发 Worker 上确认触发器已注册；若 Cloudflare 拒绝该写法，**停下**回到 ADR（改用等价列表属于 ADR 偏离）。

---

## 产品决策（ADR 0065 §10，2026-11-28 采纳）

| # | 裁决 | 落点 |
|---|---|---|
| Q1 | warning / info 不通知 | T-A2 SQL `severity = 'critical'`；`warningAndInfoExcluded` |
| Q2 | 收件人只有 admin / owner 与个人租户本人 | T-C5 `resolveOrphanRecipients` 与测试 |
| Q3 | 首期只站内；邮件第二阶段、独立开关、仅 SaaS、无退订、24 h 上限 | T-C4 开关；T-C10 / T-C11（PR-C3） |
| Q4 | 安静期站内 1 h、邮件 24 h | T-C7 `QUIET_PERIOD_MS`；T-C11 `EMAIL_CAP_MS` |
| Q5 | 不周期性提醒 | 水位越过即永不重发（T-C7 `notified orphan never re-sent`） |
| Q6 | 首开 7 天存量每租户一条 | T-C7 `NOTIFY_LOOKBACK_MS`、`first enable sends one summary per tenant` |
| Q7 | 采用 D（cloud 轮询跨租户通知源），接受新的跨租户读 | T-A2 / T-A3；T-A5 密钥轮换说明 |

## 裁决（实施细节；用户可推翻）

| # | 问题 | 裁决 | 理由 |
|---|---|---|---|
| R1 | 共用 v2 校验器的形状 | `io.aster.security.internal.InternalV2SignatureVerifier`（`@ApplicationScoped`），`Optional<Response> verify(Request, Policy)`；拒绝响应的状态码与 body **逐字节沿用** `SnapshotPushResource` 现状；`SnapshotPushResource.canonicalV2 / canonicalV1 / sha256Hex` 保留为委托，既有测试不改 | ADR §6「SnapshotPushResource 既有测试全部不变通过」 |
| R2 | `detectedSince` 下限的时钟偏差 | aster-api **严格**按 ADR：`detectedSince ≥ now − lookback`，否则 400。cloud 发送 `now − NOTIFY_LOOKBACK + DETECTED_SINCE_SKEW`（`DETECTED_SINCE_SKEW = 5 min`，与签名时间戳窗口 300 s 同） | 两侧各取自己的 `now`，cloud 若发恰好 `now − 7d`，秒级偏差会让每 tick 400；把余量放在 cloud 侧只会少看窗口边缘至多 5 分钟，不会放宽 Q6「更早的不通知」 |
| R3 | 比较后写的条件 | `WHERE notifiedThroughId = :afterId`（本 tick 读到并用于 ② 的值），而非 ADR §3.3 字面的 `< maxOrphanId` | 后者在「tick1 推进到 10 后，持旧水位 5 的 tick2 以 maxId 12 再写」时会成功，造成 6–10 被二次通知；期望旧值才能兑现 ADR「并发两个 tick 只有一个能推进」 |
| R4 | 邮件限频的存储 | 单独表 `GuardOrphanEmailState(tenantId, userId, lastEmailAt)`，PK `(tenantId, userId)`；与水位表在**同一迁移**（0070）里建 | ADR 的 `lastEmailAt` 列在按租户一行的表里无法表达「每收件人每租户」；同迁移避免第二阶段再走一次迁移窗口 |
| R5 | 「仍未通知的最老新增」怎么测 | 状态表加 `pendingSince`：tenant 在 ① 中超过水位但被安静期 / 租户上限推迟时 `COALESCE(pendingSince, now)`，推进水位时清空；报 `oldestPendingSeconds = now − min(pendingSince)` | 被推迟的租户不调 ②，拿不到 `firstDetectedAt`；首次观察到的时刻比真实检出最多晚 5 分钟，作为观测量足够 |
| R6 | `total = 0` 时「推进到 ② 的 maxId」 | 契约保证此时 `maxId = afterId`，故不写水位、不开事务，只清 `pendingSince` | 与 ADR 等价，少一次写 |
| R7 | 收件人为空时 `lastNoticeAt` | 不写；只推进水位、清 `pendingSince` | 没发通知就不该开启安静期 |
| R8 | 邮件语言 | 英文（与现有全部事务邮件一致：无用户语言列） | 不新增偏好存储（ADR §1 无偏好表） |
| R9 | 铃铛文案措辞 | 不用复数变化：`New critical Guard findings in {tenantName}: {count}`；个人租户单独一键 | 铃铛 `fillTemplate` 不支持 ICU 复数 |
| R10 | 通知源客户端签名助手 | `api-signing.ts` 新增 `signAsterApiV2Headers`（头名 `X-Aster-*`，WebCrypto），`snapshot-pusher.ts` 的 `callAsterApi` 改用它（canonical 不变，`snapshot-pusher.test.ts` 回归） | ADR C2「`signInternalV2Headers` 同法签名」：同法 = 同 canonical；现有助手头名是 `X-Internal-Signature`，aster-api 不认 |
| R11 | 邮件开关在 on-prem 的显示 | 卡片照常显示两行，邮件行说明写「仅 SaaS」 | 少一条部署模式分支；关时无行为 |
| R12 | 部分索引 `(tenant_id, id) WHERE status='OPEN' AND severity='critical'` | 不在本计划；T-A2 Step 5 用本地合成数据跑 `EXPLAIN`，记录到 PR；生产规模 `EXPLAIN` 需用户（本计划无生产访问） | ADR §3.4.2：另立任务 |

---

## 命令约定

| 记号 | 完整命令（工作目录：`AT` / `AG` / `AFULL` 在 aster-api 仓库根；`CU` / `CI` / `CT` / `CALL` 在 aster-cloud 仓库根） |
|---|---|
| `AT <tests>` | aster-api：`./gradlew --offline -Dmaven.repo.local=$M2 :test --tests '<tests>'`，`M2=<执行会话的 scratchpad>/m2-guard-orphan-notify`（按 agent 规则，`~/.m2/repository/cloud/aster-lang` 必须不存在）；环境变量 `DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380`（podman `sdd-pg` / `sdd-redis`，**不得**指向 :55432 / :6379）。确认 `@QuarkusTest` 用例实际执行（看用例计数，不只看 BUILD SUCCESSFUL） |
| `AG` | aster-api guard 套件：`AT` 的前缀加 `--tests 'io.aster.guard.*' --tests 'io.aster.billing.snapshot.*' --tests 'io.aster.security.*' --tests 'io.aster.api.PublicApiContractTest'` |
| `AFULL` | `./gradlew --offline -Dmaven.repo.local=$M2 :replay:cleanTest :cleanTest :replay:test :test --continue`（同上环境变量）；已知基线失败 `PolicyGraphQLResourceTest.testBatchEvaluateLargeVolume`、`PrismaDatabaseE2ETest` 在 PR 注明 |
| `CU <files…>` | cloud：`export PATH=/opt/homebrew/opt/node@24/bin:$PATH; corepack pnpm exec vitest run <files…>` |
| `CI <files…>` | cloud：`LICENSE_E2E=1 DATABASE_URL=postgresql://postgres:postgres@localhost:55433/aster_cloud corepack pnpm exec vitest run --config vitest.integration.config.ts <files…>`（`sdd-pg` 的 `aster_cloud` 库，先 `corepack pnpm db:migrate`） |
| `CT` | cloud：`corepack pnpm exec tsc --noEmit` |
| `CALL` | cloud：`CT && corepack pnpm lint && corepack pnpm test:run` |

「→ FAIL」指新增用例失败、其余不变；「→ PASS」指退出码 0。

## 顺序与 PR 划分

```
aster-api  PR-A  (T-A1–T-A5) ──合并 = 自动部署（image-pin bot → Argo）──────────────┐
cloud      PR-C1 (T-C1，仅迁移) ──合并 → migrate 镜像 pin PR（k3s）合并 → PreSync 迁移成功 ─┤
                                                                                       ▼
cloud      PR-C2 (T-C2–T-C9，开关缺省关) ──合并 = Deploy Worker ──▶ 预发验证（ADR §4 第 3 步）──▶ 用户开站内开关
cloud      PR-C3 (T-C10–T-C11，邮件第二阶段) ── PR-C2 之后任何时候可合（开关关）──▶ Q3 条件满足后用户开邮件开关
```

| 任务 | 仓库 | PR | 迁移 |
|---|---|---|---|
| T-A1 共用 v2 内部签名校验器 | wontlost-ltd/aster-api | A | 否 |
| T-A2 通知源查询服务 | wontlost-ltd/aster-api | A | 否 |
| T-A3 `GuardOrphanFeedResource`（两个端点 + 鉴权） | wontlost-ltd/aster-api | A | 否 |
| T-A4 指标 `guard_orphan_feed_requests_total{outcome}` | wontlost-ltd/aster-api | A | 否 |
| T-A5 共享契约夹具、ADR 0054 指针、密钥轮换说明 | wontlost-ltd/aster-api | A | 否 |
| T-C1 迁移 0070：`GuardOrphanNotifyState`、`GuardOrphanEmailState` | wontlost-ltd/aster-cloud | **C1** | **是** |
| T-C2 v2 签名助手与通知源客户端 | wontlost-ltd/aster-cloud | C2 | 否（不触表） |
| T-C3 通知类别、载荷类型、铃铛模板、四语文案 | wontlost-ltd/aster-cloud | C2 | 否 |
| T-C4 两个平台开关与管理员卡片 | wontlost-ltd/aster-cloud | C2 | 否 |
| T-C5 收件人与载荷构造 | wontlost-ltd/aster-cloud | C2 | 否（读既有表） |
| T-C6 通知状态存储（比较后写事务） | wontlost-ltd/aster-cloud | C2 | **依赖 0070 已应用** |
| T-C7 每 tick 编排 `notifyOrphansDetected` | wontlost-ltd/aster-cloud | C2 | **依赖 0070 已应用** |
| T-C8 cron 路由、注册表、`wrangler.toml`、`worker.js`、on-prem 指南、心跳 | wontlost-ltd/aster-cloud | C2 | 否 |
| T-C9 本地栈端到端与预发验证清单 | wontlost-ltd/aster-cloud | C2 | 依赖 0070 |
| T-C10 邮件模板与发送函数（第二阶段） | wontlost-ltd/aster-cloud | C3 | 否 |
| T-C11 邮件接入 tick、24 h 上限、邮件开关（第二阶段） | wontlost-ltd/aster-cloud | C3 | **依赖 0070 已应用**（`GuardOrphanEmailState`） |

**迁移与上线次序（硬门）**

1. PR-A 合并即部署 aster-api（无调用方时无行为）；PR-C2 合并前确认生产 aster-api 已含 T-A3（`POST /api/internal/guard/orphan-feed/tenants` 未签名返回 401 而非 404 即可，由用户 / 控制者核对，本计划无生产访问）。
2. PR-C1 只含 `src/db/schema.ts`、`drizzle/0070_*.sql`、`drizzle/meta/*`、`src/__tests__/**`，满足 `check-migration-split`，不加 `migration-and-code` 标签。合并后 Worker 照常部署（无代码变化），迁移随 migrate 镜像 pin PR 经 ArgoCD PreSync 执行——**该 k3s pin PR 由用户合并**；待 PreSync Job 成功（`__drizzle_migrations` 有 0070）再合 PR-C2。理由：Worker 部署不等迁移；虽然 PR-C2 在开关关闭时不读写新表，但管理员一旦开开关就会触表，先迁移后代码是本仓惯例（ADR 0058 C1 → C2 同法）。
3. PR-C2 合并后开关仍关；做 T-C9 的预发验证（需用户 / 控制者在预发环境执行），再由用户在 `/admin` 打开 `guard.orphan_notifications.enabled`，观察一周（ADR §4 第 4 步）。
4. PR-C3 不含迁移（表已在 0070 建好）；邮件开关由用户在 Q3 条件满足后打开。
5. 回滚：关开关即停（tick 第一步返回）；两张表与已发通知留存无害；不需要 down 迁移。若必须删表，新写 `00XX_revert` 迁移走同一流程。

## 文件结构

**aster-api**

| 文件 | 职责 |
|---|---|
| `src/main/java/io/aster/security/internal/InternalV2SignatureVerifier.java` | 新：v2 HMAC 校验（fail-closed、nonce 一次性、可选 v1 兼容），canonical 唯一构造点 |
| `src/main/java/io/aster/billing/snapshot/SnapshotPushResource.java` | 改：`verify` 委托共用校验器；静态 canonical 方法委托 |
| `src/main/java/io/aster/guard/reconcile/GuardOrphanFeedService.java` | 新：两条聚合 SQL 与参数校验 |
| `src/main/java/io/aster/guard/api/GuardOrphanFeedResource.java` | 新：`/api/internal/guard/orphan-feed/{tenants,summary}` |
| `src/main/java/io/aster/guard/reconcile/GuardOrphanMetrics.java` | 改：`recordFeedRequest(FeedOutcome)` |
| `src/test/resources/guard/orphan-feed-contract.json` | 新：两仓共享契约夹具 |

**aster-cloud**

| 文件 | 职责 |
|---|---|
| `src/db/schema.ts`、`drizzle/0070_guard_orphan_notify_state.sql`、`drizzle/meta/*` | 两张新表（PR-C1） |
| `src/lib/api-signing.ts` | 新增 `signAsterApiV2Headers` |
| `src/lib/guard-orphan-notify/feed-client.ts` | 通知源客户端与严格解析 |
| `src/lib/guard-orphan-notify/recipients.ts` | `resolveOrphanRecipients` |
| `src/lib/guard-orphan-notify/payload.ts` | `buildOrphanNotice`、`orphanNoticeId`、`orphanNoticeHref` |
| `src/lib/guard-orphan-notify/state.ts` | 读状态、记 pending、比较后写事务 |
| `src/lib/guard-orphan-notify/tick.ts` | `runOrphanNotifyTick(deps, now)` 编排 |
| `src/lib/guard-orphan-notify/email.ts` | 第二阶段：邮件模板与发送 |
| `src/lib/guard-notifications.ts` | 新增 `notifyOrphansDetected()`（以缺省依赖调用 tick） |
| `src/app/api/cron/guard-orphan-notify/route.ts` | cron 路由 |
| `src/__tests__/fixtures/guard-orphan-feed-contract.json` | 与 aster-api 逐字节一致的契约夹具 |

---

### Task A1: 共用 v2 内部签名校验器（aster-api，PR-A）

**Files:**
- Create: `src/main/java/io/aster/security/internal/InternalV2SignatureVerifier.java`
- Modify: `src/main/java/io/aster/billing/snapshot/SnapshotPushResource.java`（删 `verify / verifyPreconditions / sign / constantTimeEquals` 私有实现，`verify` 改为一行委托；`canonicalV2 / canonicalV1 / sha256Hex` 保留签名、改为委托校验器的同名静态方法）
- Test: `src/test/java/io/aster/security/internal/InternalV2SignatureVerifierTest.java`（纯单元，`NonceService` 用手写桩）

**Interfaces:**
- Produces:
  - `public record Request(String method, String path, String timestamp, String signature, String nonce, String body)`（嵌套于校验器）
  - `public record Policy(String nonceScope, boolean acceptLegacyV1, String logLabel)`
  - `public Optional<Response> verify(Request r, Policy p)`：空 = 通过；否则为拒绝响应。顺序与 body 逐字节沿用现状：key 缺 / 空白 → 403 `{"error":"hmac_not_configured"}`；`timestamp == null || signature == null` → 401 `missing signature headers`；时间戳非整数 → 401 `invalid timestamp`；`|now − ts| > 300` → 401 `stale timestamp`；有 nonce → v2 比较（常量时间），错 → 401 `{"error":"invalid_signature"}`，对 → `nonceService.ensureFresh(p.nonceScope(), nonce, bodySha)`，重放 → 409 `{"error":"nonce_replay"}`；无 nonce 且 `!acceptLegacyV1` → 401 `{"error":"missing_nonce","detail":"v2 signature required"}`；无 nonce 且接受 v1 → v1 比较，命中打 `LOG.warnf`（文案含 `logLabel` 与 path）。
  - `public static String canonicalV2(String method, String path, long ts, String nonce, String bodySha)`、`canonicalV1(...)`、`sha256Hex(String)`、`static String sign(String key, String msg)`（包级，供测试）。
  - key 来源：`@ConfigProperty(name = "aster.plan-gate.hmac-key") Optional<String> hmacKey`；`@Inject NonceService nonceService`。

- [ ] **Step 1: 写失败的测试**（`InternalV2SignatureVerifierTest`，构造校验器后直接赋字段）：
  - `missingKeyIs403`：key `Optional.empty()` 与 `"  "` → 403 `hmac_not_configured`，nonce 桩未被调用。
  - `missingHeadersIs401` / `badTimestampIs401` / `staleTimestampIs401`（ts = now − 301）。
  - `validV2Passes`：用 `canonicalV2` + `sign` 自签 → `Optional.empty()`，nonce 桩收到 `(scope, nonce, sha256(body))`。
  - `tamperedBodyIs401`：签名后改 body → 401 `invalid_signature`，nonce 桩未被调用（先验签后消费）。
  - `replayIs409`：nonce 桩第二次抛 `WebApplicationException(409)` → 409 `nonce_replay`。
  - `v1RejectedWhenLegacyOff`：无 nonce、合法 v1 签名、`acceptLegacyV1=false` → 401 `missing_nonce`。
  - `v1AcceptedWhenLegacyOn`：同上、`true` → 通过。
  - `canonicalIsFiveLines`：`canonicalV2` 恰 5 行。
- [ ] **Step 2:** `AT 'io.aster.security.internal.InternalV2SignatureVerifierTest'` → FAIL（类不存在）。
- [ ] **Step 3:** 实现校验器；`SnapshotPushResource.verify(...)` 改为 `return verifier.verify(new Request(...), new Policy("snapshot-push", acceptLegacySignature, "snapshot push")).orElse(null);`，v1 告警文案保持现有句子（含 `aster.security.snapshot.accept-legacy-signature=false` 提示）。
- [ ] **Step 4:** `AT 'io.aster.security.internal.*'` → PASS；`AT 'io.aster.billing.snapshot.*'` → PASS（`SnapshotSignatureCanonicalTest`、`SnapshotLegacySignatureRejectedIT` **不改一行**即通过）。
- [ ] **Step 5:** Commit `refactor(security): 抽出共用 v2 内部签名校验器（ADR 0065 A1）`。

### Task A2: 通知源查询服务（aster-api，PR-A）

**Files:**
- Create: `src/main/java/io/aster/guard/reconcile/GuardOrphanFeedService.java`
- Test: `src/test/java/io/aster/guard/reconcile/GuardOrphanFeedServiceTest.java`（`@QuarkusTest`，PG；夹具：把 `GuardReconciliationResourceTest.seed` 的造行逻辑上移为 `ReconcileFixture.seedOrphan(String tenant, OrphanKind kind, String subject, String status, Instant detectedAt, long firstRunId)` 并让原测试改调它）

**Interfaces:**
- Produces:
  - `public record TenantIndexQuery(Instant detectedSince, String afterTenant /* nullable */, int limit)`；`public record TenantMax(String tenantId, long maxId)`；`public record TenantIndexPage(List<TenantMax> items, String next /* nullable */)`
  - `public record SummaryQuery(String tenantId, long afterId, Instant detectedSince)`；`public record Summary(String tenantId, long maxId, long total, Map<String, Long> byKind /* TreeMap */, Instant firstDetectedAt /* nullable */, Instant lastDetectedAt /* nullable */, List<Long> runIds /* desc, ≤ 8 */)`
  - `public Optional<TenantIndexQuery> parseIndex(JsonObject body, Instant now)`、`public Optional<SummaryQuery> parseSummary(JsonObject body, Instant now)`：`detectedSince` 必填 ISO-8601 instant 且 `≥ now − lookback`（R2，严格）且 `≤ now + PT5M`；`afterTenant` 可选非空白 ≤ 255；`limit` 可选整数 1..500（缺省 500）；`tenantId` 必填非空白 ≤ 255；`afterId` 必填整数 ≥ 0；未知字段忽略；任何违例 → `Optional.empty()`。`lookback` 注入自 `aster.guard.reconciliation.lookback`。
  - `public TenantIndexPage tenants(TenantIndexQuery q)`：
    ```sql
    SELECT tenant_id, MAX(id) FROM guard_orphan_actions
    WHERE status = 'OPEN' AND severity = 'critical' AND detected_at >= :since
      AND (CAST(:after AS varchar) IS NULL OR tenant_id > :after)
    GROUP BY tenant_id ORDER BY tenant_id LIMIT :limitPlusOne
    ```
    取前 `limit` 行；有第 `limit+1` 行时 `next` = 第 `limit` 行的 `tenantId`。
  - `public Summary summary(SummaryQuery q)`：**一条**语句：
    ```sql
    SELECT kind, COUNT(*), MIN(detected_at), MAX(detected_at), MAX(id),
           (ARRAY_AGG(DISTINCT first_run_id ORDER BY first_run_id DESC))[1:8]
    FROM guard_orphan_actions
    WHERE tenant_id = :t AND status = 'OPEN' AND severity = 'critical'
      AND detected_at >= :since AND id > :after
    GROUP BY kind
    ```
    Java 侧合并：`total = Σcount`、`maxId = max(MAX(id))`（无行 → `afterId`）、`first/last` 取极值、`runIds` = 各组数组并集降序取前 8。读事务风格同 `GuardOrphanAdminService.list`。

- [ ] **Step 1: 写失败的测试**（每例用新租户 `ReconcileFixture.tenant("feed-…")`）：
  - `indexOnlyOpenCritical`：每个 kind 各一行 OPEN，另加 ACKNOWLEDGED / RESOLVED 的 critical 各一行 → ① 该租户 `maxId` = OPEN critical 行的最大 id。
  - `warningAndInfoExcluded`：只有 warning + info 的租户不出现在 ①；② `total = 0`。
  - `indexOneRowPerTenantAndPages`：3 个租户（字典序 a < b < c）、`limit=2` → 第一页 `[a,b]`、`next=b`；`afterTenant=b` → `[c]`、无 `next`。（断言只看本例租户，用 `afterTenant` 起点隔离他例数据。）
  - `detectedSinceFilters`：`detected_at = now − 8d` 的行在 `since = now − 7d` 时不计。
  - `summaryAfterIdAndEmpty`：行 id 为 x<y<z，`afterId=x` → `total=2`、`maxId=z`、`byKind` 正确、`first/lastDetectedAt` 正确；`afterId=z` → `total=0`、`maxId=z`、`byKind={}`、时间为 null、`runIds=[]`。
  - `runIdsDistinctDescCapped`：10 行分属 10 个 run → `runIds` 为最大的 8 个、降序、无重复；同 run 多行只出现一次。
  - `acknowledgedRowsExcluded`：OPEN 行被改成 ACKNOWLEDGED 后 ② 不计、① 不列。
  - `backlogDoesNotStarve`：某租户 600 条 `id ≤ afterId` 的 OPEN critical + 1 条新行 → ② `total=1`；① 中该租户恰一行。
  - `summaryCountsAndMaxIdFromOneStatement`：断言 `GuardOrphanFeedService.SUMMARY_SQL` 常量只含一个 `SELECT … FROM guard_orphan_actions`（防止拆成两条查询）。
  - `parse*`：`detectedSince` 缺失 / 非 ISO / `now − lookback − 1s` / `now + 6m` → empty；`now − lookback + 1s` → 通过；`limit` 0、501、`"5"`、1.5 → empty；`afterId` −1、缺失 → empty；`tenantId` 空白 / 256 字符 → empty。
- [ ] **Step 2:** `AT 'io.aster.guard.reconcile.GuardOrphanFeedServiceTest'` → FAIL。
- [ ] **Step 3:** 实现；SQL 以 `static final String INDEX_SQL / SUMMARY_SQL` 常量给出（包级可见）。
- [ ] **Step 4:** `AT 'io.aster.guard.reconcile.*'` → PASS（`GuardReconciliationResourceTest` 改用上移的夹具后不变通过）。
- [ ] **Step 5:** 本地 `EXPLAIN (ANALYZE, BUFFERS)`：在 `sdd-pg` 造 1 000 租户 × 200 行（10 % OPEN critical）后对两条 SQL 执行，输出贴进 PR 描述；若 ② 出现对 `guard_orphan_actions` 的顺序扫描，在 PR 中注明并按 R12 另立任务，不在本 PR 加索引。Commit `feat(guard): 孤儿通知源聚合查询（ADR 0065 §3.4.2）`。

### Task A3: `GuardOrphanFeedResource`（aster-api，PR-A）

**Files:**
- Create: `src/main/java/io/aster/guard/api/GuardOrphanFeedResource.java`
- Test: `src/test/java/io/aster/guard/reconcile/GuardOrphanFeedResourceTest.java`（`@QuarkusTest`；签名用 `InternalV2SignatureVerifier.canonicalV2 / sign` 与测试 profile 的 key）

**Interfaces:**
- Consumes: T-A1 `InternalV2SignatureVerifier`；T-A2 `GuardOrphanFeedService`。
- Produces:
  - `@Path("/api/internal/guard/orphan-feed")`，两个方法 `@POST @Path("/tenants")`、`@POST @Path("/summary")`，`@Consumes/@Produces(APPLICATION_JSON)`、`@Blocking`，入参 `@HeaderParam("X-Aster-Timestamp"/"X-Aster-Signature"/"X-Aster-Nonce")` + `String body`。
  - 流程：`verifier.verify(new Request("POST", "/api/internal/guard/orphan-feed/<tenants|summary>", …), new Policy("guard-orphan-feed", false, "guard orphan feed"))` → 拒绝即返回；body 非 JSON 对象或 `parse*` 为空 → 400 `{"error":"invalid_query"}`；否则 200。
  - ① 响应：`{"items":[{"tenantId":"…","maxId":123}],"next":"…"}`（无下一页省略 `next`）。
  - ② 响应：`{"tenantId","maxId","total","byKind":{…},"firstDetectedAt","lastDetectedAt","runIds":[…]}`（`total=0` 时省略两个时间字段）。字段集合以外不得出现任何键。

- [ ] **Step 1: 写失败的测试**：
  - `missingKeyIs403`：`@TestProfile` 置 `aster.plan-gate.hmac-key=` → 两端点 403。
  - `badSignatureIs401`、`missingHeadersIs401`、`v1Is401`（无 nonce、合法 v1 签名）→ 401。
  - `nonceReplayIs409`：同一组头发两次 → 第二次 409。
  - `bearerApiKeyNotAccepted`：带有效 API key 的 `Authorization: Bearer …`、无签名头 → 401。
  - `internalCallerV3HeadersNotAccepted`：用 `InternalCallerSigning` 生成 cloud-bff v3 头（含 `X-Tenant-Id`）→ 401。
  - `signatureBindsBody`：签 `{"tenantId":"a",…}`、发 `{"tenantId":"b",…}` → 401。
  - `invalidQueryIs400`：`detectedSince` 早于下限、`limit: 0`、body `[]`、body 非 JSON → 400 `{"error":"invalid_query"}`。
  - `tenantsHappyPath` / `summaryHappyPath`：造行后 200，字段值同 T-A2；`responseKeysPinned`：① 项的键集恰为 `{tenantId,maxId}`，② 的键集恰为上面 7 个（`total=0` 时 5 个）；响应文本不含 seed 行 `detail` 里的标记串与 `executor_id`。
  - `noChainWriteNoQuota`：调用前后 `audit_logs` 行数不变。
- [ ] **Step 2:** `AT 'io.aster.guard.reconcile.GuardOrphanFeedResourceTest'` → FAIL。
- [ ] **Step 3:** 实现（JSON 用 `JsonObject` 构造，键序固定为上述顺序）。
- [ ] **Step 4:** `AG` → PASS（`PublicApiContractTest` 不变：内部路径不入契约）。
- [ ] **Step 5:** Commit `feat(guard): 孤儿通知源内部端点（ADR 0065 §3.4.2）`。

### Task A4: 通知源请求指标（aster-api，PR-A）

**Files:**
- Modify: `src/main/java/io/aster/guard/reconcile/GuardOrphanMetrics.java`（`public enum FeedOutcome { OK("ok"), INVALID("invalid"), UNAUTHORIZED("unauthorized"), REPLAYED("replayed") }`；`public void recordFeedRequest(FeedOutcome o)` → `Counter guard.orphan.feed.requests{outcome}`）、`GuardOrphanFeedResource.java`（每个返回点按状态码记一次：200→OK、400→INVALID、401 / 403→UNAUTHORIZED、409→REPLAYED；未捕获异常不计）
- Test: `src/test/java/io/aster/guard/reconcile/GuardOrphanMetricsCountersTest.java`（追加）、`GuardOrphanFeedResourceTest.java`（追加）

- [ ] **Step 1: 写失败的测试**：`feedOutcomeLabelsAreFixed`（四个字面量）；资源测试 `metricsCountEachOutcome`：依次触发 200 / 400 / 401 / 409，`/q/metrics` 中 `guard_orphan_feed_requests_total{outcome="ok"|"invalid"|"unauthorized"|"replayed"}` 各增 1；标签只有 `outcome`（无 tenant）。
- [ ] **Step 2:** `AT 'io.aster.guard.reconcile.GuardOrphanMetrics*' --tests 'io.aster.guard.reconcile.GuardOrphanFeedResourceTest'` → FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同 Step 2 → PASS；`AG` → PASS。
- [ ] **Step 5:** Commit `feat(guard): 通知源请求计数（ADR 0065 §5）`。

### Task A5: 共享契约夹具、ADR 0054 指针、密钥轮换说明（aster-api，PR-A）

**Files:**
- Create: `src/test/resources/guard/orphan-feed-contract.json`（形如 `{"tenantsRequest":{…},"tenantsResponse":{…},"summaryRequest":{…},"summaryResponse":{…},"summaryEmptyResponse":{…},"signing":{"key":"test-key","method":"POST","path":"/api/internal/guard/orphan-feed/summary","ts":1795000000,"nonce":"00000000-0000-4000-8000-000000000001","body":"…","canonical":"…","signature":"…"}}`，LF 结尾、无 BOM）
- Test: `src/test/java/io/aster/guard/reconcile/GuardOrphanFeedContractTest.java`
- Modify: `docs/adr/0054-guard-execution-point.md`（§10.2 末尾加一行「cloud 专用的跨租户只读通知源见 ADR 0065 §3.4.2，不属于本节对外契约」）、`src/main/resources/application.properties`（`aster.plan-gate.hmac-key` 注释块加两行：该 key 也保护跨租户只读通知源 `/api/internal/guard/orphan-feed/*`；泄露时可一次枚举全部租户的 critical 计数，轮换须两侧同时进行）

- [ ] **Step 1: 写失败的测试**：
  - `fixtureSha256Pinned`：文件 sha256 等于常量 `FIXTURE_SHA256`（cloud T-C2 钉同一常量）。
  - `signingVectorMatchesProduction`：`InternalV2SignatureVerifier.canonicalV2(...)` 对夹具 `signing` 各字段的结果等于 `canonical`，`sign(key, canonical)` 等于 `signature`。
  - `responsesMatchResourceOutput`：夹具的三个 `*Response` 是**具体样例值**（id、时间都是固定字面量），本测试钉**结构**：造数据后真实请求两个端点，断言响应的键集、键序与每个键的 JSON 类型（`string` / `integer` / `object` / `array<integer>`）与夹具对应样例完全一致（`total=0` 时与 `summaryEmptyResponse` 比）。值不比（id 由序列生成）；值的正确性已由 T-A2 / T-A3 钉住。cloud T-C2 把同一批样例当作解析器输入。
- [ ] **Step 2:** `AT 'io.aster.guard.reconcile.GuardOrphanFeedContractTest'` → FAIL。
- [ ] **Step 3:** 写夹具（由生产代码算出 `canonical` / `signature` 后固化）与文档改动。
- [ ] **Step 4:** `AFULL`（已知基线失败除外）→ PASS。
- [ ] **Step 5:** Commit；开 PR-A（`Refs wontlost-ltd/aster-cloud#843`；正文附 T-A2 Step 5 的 EXPLAIN 输出、`@QuarkusTest` 执行计数）；独立 Codex 审查 ≥ 90；CI 绿后按合并权限规则合并（合并即部署）。

---

### Task C1: 迁移 0070 —— 通知状态表（cloud，PR-C1，**迁移**）

**Files:**
- Modify: `src/db/schema.ts`（`guardPolicyDeployments` 之后新增两表并在 `@/lib/prisma` 的再导出中可见）
- Create: `drizzle/0070_guard_orphan_notify_state.sql`（`corepack pnpm db:generate --name guard_orphan_notify_state` 生成后核对）、`drizzle/meta/0070_snapshot.json`、`drizzle/meta/_journal.json`（追加）
- Test: `src/__tests__/integration/guard-orphan-notify-schema.integration.test.ts`

**Interfaces:**
- Produces（`schema.ts`）：
  ```ts
  /** Guard 孤儿通知水位（ADR 0065 §3.4.3）：每个出现过 critical 孤儿的租户一行；不设外键（租户可能已删）。 */
  export const guardOrphanNotifyState = pgTable('GuardOrphanNotifyState', {
    tenantId: text('tenantId').primaryKey().notNull(),
    notifiedThroughId: bigint('notifiedThroughId', { mode: 'number' }).notNull().default(0),
    lastNoticeAt: timestamp('lastNoticeAt', { mode: 'date', precision: 3 }),
    pendingSince: timestamp('pendingSince', { mode: 'date', precision: 3 }),
    updatedAt: timestamp('updatedAt', { mode: 'date', precision: 3 }).defaultNow().notNull().$onUpdate(() => new Date()),
  });
  /** 邮件限频（ADR 0065 Q4，R4）：每收件人每租户 24 小时至多一封。 */
  export const guardOrphanEmailState = pgTable('GuardOrphanEmailState', {
    tenantId: text('tenantId').notNull(),
    userId: text('userId').notNull(),
    lastEmailAt: timestamp('lastEmailAt', { mode: 'date', precision: 3 }).notNull(),
  }, (t) => [primaryKey({ columns: [t.tenantId, t.userId] })]);
  ```
- SQL 只含两条 `CREATE TABLE`（与 PK 约束），无其他表的改动；文件可重复检查：不得含 `DROP` / `ALTER` 他表。

- [ ] **Step 1: 写失败的测试**（集成，PG）：两表存在；`notifiedThroughId` 缺省 0；重复 `tenantId` 插入违反主键；`GuardOrphanEmailState` 的 `(tenantId, userId)` 唯一；`INSERT … ON CONFLICT ("tenantId") DO UPDATE … WHERE "notifiedThroughId" = 5 RETURNING` 在值为 7 时返回 0 行（T-C6 依赖的 SQL 能力）。
- [ ] **Step 2:** `CI src/__tests__/integration/guard-orphan-notify-schema.integration.test.ts` → FAIL。
- [ ] **Step 3:** 改 schema、生成迁移、`corepack pnpm db:migrate`（`sdd-pg` 的 `aster_cloud`）。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS；`node scripts/ci/check-migration-split.mjs`（`PR_FILES` 设为本 PR 文件列表）→ 退出 0。
- [ ] **Step 5:** Commit `feat(db): Guard 孤儿通知状态表（ADR 0065 C1）`；开 PR-C1（中文正文，写明「合并后需合并 k3s 的 cloud-migrate 镜像 pin PR，PreSync 成功后才合 PR-C2」）。

### Task C2: v2 签名助手与通知源客户端（cloud，PR-C2）

**Files:**
- Modify: `src/lib/api-signing.ts`（新增导出）、`src/lib/snapshot-pusher.ts`（`callAsterApi` 改用助手）
- Create: `src/lib/guard-orphan-notify/feed-client.ts`、`src/__tests__/fixtures/guard-orphan-feed-contract.json`（与 aster-api 逐字节一致，从 aster-api `origin/main` 拷贝）
- Test: `src/__tests__/lib/api-signing-aster-api-v2.test.ts`、`src/__tests__/lib/guard-orphan-notify/feed-client.test.ts`、`src/__tests__/lib/snapshot-pusher.test.ts`（不改，回归）

**Interfaces:**
- Produces（`api-signing.ts`）：`signAsterApiV2Headers(method: string, path: string, body: string, secret: string): Promise<{ 'X-Aster-Timestamp': string; 'X-Aster-Nonce': string; 'X-Aster-Signature': string }>`（canonical 同 aster-api `InternalV2SignatureVerifier.canonicalV2`，`path` 不含 query；内部可注入 `now` / `nonce` 供测试：`opts?: { nowSec?: number; nonce?: string }`）。
- Produces（`feed-client.ts`，`import 'server-only'`）：
  - `interface OrphanFeedTenantsPage { items: { tenantId: string; maxId: number }[]; next?: string }`
  - `interface OrphanFeedSummary { tenantId: string; maxId: number; total: number; byKind: Record<string, number>; firstDetectedAt?: string; lastDetectedAt?: string; runIds: number[] }`
  - `interface OrphanFeed { tenants(q: { detectedSince: string; afterTenant?: string; limit?: number }): Promise<OrphanFeedTenantsPage>; summary(q: { tenantId: string; afterId: number; detectedSince: string }): Promise<OrphanFeedSummary> }`
  - `class OrphanFeedError extends Error { constructor(readonly status: number /* 0 = 未发出 / 网络 */, readonly code: string) }`
  - `createOrphanFeedClient(opts?: { baseUrl?: string; hmacKey?: string | null; fetchImpl?: typeof fetch; timeoutMs?: number }): OrphanFeed`：缺省 `baseUrl = safeEnv('ASTER_API_INTERNAL_URL') ?? 'http://aster-api:8080'`、`hmacKey = safeEnv('ASTER_PLAN_GATE_HMAC_KEY')`、`timeoutMs = 8000`；body 为 `JSON.stringify(q)`，头含 `Content-Type: application/json` 与 `traceparent`。
  - 严格解析：`maxId` / `total` / `runIds[]` 为非负安全整数，`tenantId` 非空字符串，`byKind` 值为非负整数，时间为可 `Date.parse` 的字符串；任何不符 → `OrphanFeedError(200, 'malformed_response')`。非 2xx → `OrphanFeedError(status, body.error ?? 'http_<status>')`；超时 / 网络 → `OrphanFeedError(0, 'unreachable')`。

- [ ] **Step 1: 写失败的测试**：
  - 签名：对夹具 `signing` 的 `method/path/ts/nonce/body/key`，`signAsterApiV2Headers` 产出的 `X-Aster-Signature` 等于夹具 `signature`；`fixture sha256 pinned`（常量与 aster-api T-A5 相同）。
  - `refuses to call without key`：`hmacKey: null` → 抛 `OrphanFeedError(0, 'hmac_not_configured')`，`fetchImpl` 未被调用。
  - `tenants posts signed body`：URL `…/api/internal/guard/orphan-feed/tenants`，方法 POST，三个 `X-Aster-*` 头存在，body 解析等于入参。
  - `parses contract fixture`：`summaryResponse` / `summaryEmptyResponse` / `tenantsResponse` 原样喂入 → 解析结果深等于期望对象。
  - `malformed responses throw`：`maxId: "1"`、`runIds: [-1]`、缺 `items`、`byKind: { x: 1.5 }` → `malformed_response`。
  - `maps http errors`：403 `{error:'hmac_not_configured'}` → `OrphanFeedError(403,'hmac_not_configured')`；400 → `invalid_query`；fetch 拒绝 / 超时 → `(0,'unreachable')`。
  - snapshot-pusher 既有用例不改、通过（签名头名与 canonical 未变）。
- [ ] **Step 2:** `CU src/__tests__/lib/api-signing-aster-api-v2.test.ts src/__tests__/lib/guard-orphan-notify/feed-client.test.ts src/__tests__/lib/snapshot-pusher.test.ts` → FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task C3: 通知类别、载荷类型、铃铛模板与四语文案（cloud，PR-C2）

**Files:**
- Modify: `src/lib/notifications.ts`（`NotificationKind` 加 `'guard.orphans_detected'`；`NotificationPayloads` 加 `'guard.orphans_detected': GuardOrphansDetectedNotification`）、`src/components/notifications/notifications-bell.tsx`（`useLabels` 加两键；`renderText` 加分支）、`src/i18n/demo-supplement.ts`（`notifications` 命名空间四种语言各加两键）
- Test: `src/__tests__/components/notifications-bell-render.test.ts`（追加）、`src/__tests__/i18n/guard-orphan-notify-messages.test.ts`

**Interfaces:**
- Produces（`notifications.ts`）：
  ```ts
  /** Guard 对账新增 critical 孤儿的租户汇总（ADR 0065 §3.3）。只含计数、kind、时间与 id，不含任何上报方文本。 */
  export interface GuardOrphansDetectedNotification {
    tenantId: string;
    /** 团队名；个人租户为空串（铃铛显示本地化「个人工作区」）。 */
    tenantName: string;
    newTotal: number;
    /** 已知 critical kind → 计数；未知 kind 合并为 other。 */
    byKind: Record<string, number>;
    firstDetectedAt: string;
    lastDetectedAt: string;
    maxOrphanId: number;
    /** 至多 8 个 first_run_id，只用于与 GUARD_RECONCILIATION 链事件对照，不展示。 */
    runIds: number[];
  }
  ```
- 文案（`notifications.guardOrphansDetected` / `guardOrphansDetectedPersonal`）：
  - en：`New critical Guard findings in {tenantName}: {count}` / `New critical Guard findings in your personal workspace: {count}`
  - zh：`{tenantName} 出现新的 Guard 严重发现：{count} 条` / `个人工作区出现新的 Guard 严重发现：{count} 条`
  - de：`Neue kritische Guard-Befunde in {tenantName}: {count}` / `Neue kritische Guard-Befunde in Ihrem persönlichen Arbeitsbereich: {count}`
  - hi：`{tenantName} में नए गंभीर Guard निष्कर्ष: {count}` / `आपके व्यक्तिगत कार्यक्षेत्र में नए गंभीर Guard निष्कर्ष: {count}`
- `renderText` 分支：`text` = `tenantName` 为空串用 Personal 模板，否则替换 `{tenantName}`；`{count}` = `String(newTotal ?? 0)`；`href = '/governance/reconciliation?tenant=' + encodeURIComponent(tenantId) + '&severity=critical'`（无 `tenantId` 时 `/governance/reconciliation`）。

- [ ] **Step 1: 写失败的测试**：团队载荷 → 文本含团队名与计数、`href` 精确等于上式；个人载荷（`tenantName: ''`）→ Personal 模板；`tenantId` 含 `/` 与空格 → 正确编码；载荷里混入 `detail: '<img onerror>'` 也不出现在文本中；四种语言两键都存在、非空、都含 `{count}`，团队键含 `{tenantName}`。
- [ ] **Step 2:** `CU src/__tests__/components/notifications-bell-render.test.ts src/__tests__/i18n/guard-orphan-notify-messages.test.ts` → FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task C4: 两个平台开关与管理员卡片（cloud，PR-C2）

**Files:**
- Modify: `src/lib/platform-settings.ts`（键 `GUARD_ORPHAN_NOTIFICATIONS_ENABLED: 'guard.orphan_notifications.enabled'`、`GUARD_ORPHAN_NOTIFICATIONS_EMAIL_ENABLED: 'guard.orphan_notifications.email.enabled'`；`DEFAULTS` 均 `false`，注释写明 fail-OFF 与 ADR 0065）、`src/app/api/admin/platform-settings/route.ts`（`isAuditedBooleanSetting` 加 `|| key.startsWith('guard.orphan_notifications.')`，注释同步）、`src/components/admin/feature-flags-card.tsx`（`FLAGS` 追加两行，头注释的列举同步）、`src/i18n/demo-supplement.ts`（`admin.flags` 四种语言各加四键）
- Test: `src/__tests__/api/admin-platform-settings-guard-orphan.test.ts`、`src/__tests__/components/feature-flags-card.test.tsx`（新建；现无该组件测试）

**Interfaces / 文案：**
- `admin.flags.guardOrphanNotifyTitle / guardOrphanNotifyDesc / guardOrphanEmailTitle / guardOrphanEmailDesc`：
  - en：`Guard orphan notifications (in-app)` / `Notify team owners and admins in-app when Guard reconciliation finds new critical orphan actions. At most one summary per workspace per hour.` / `Guard orphan notifications (email)` / `Also email the same summary (SaaS only; at most one per recipient per workspace per 24 hours). Takes effect only when the in-app switch is on.`
  - zh：`Guard 孤儿通知（站内）` / `Guard 对账发现新的 critical 孤儿动作时，给团队 owner 与 admin 发站内通知；每个工作区每小时至多一条汇总。` / `Guard 孤儿通知（邮件）` / `同一条汇总另发邮件（仅 SaaS；每位收件人每个工作区 24 小时至多一封）。仅在站内开关打开时生效。`
  - de：`Guard-Orphan-Benachrichtigungen (In-App)` / `Benachrichtigt Team-Owner und -Admins in der App, wenn der Guard-Abgleich neue kritische verwaiste Aktionen findet. Höchstens eine Zusammenfassung pro Arbeitsbereich und Stunde.` / `Guard-Orphan-Benachrichtigungen (E-Mail)` / `Sendet dieselbe Zusammenfassung zusätzlich per E-Mail (nur SaaS; höchstens eine pro Empfänger und Arbeitsbereich in 24 Stunden). Wirkt nur, wenn der In-App-Schalter aktiv ist.`
  - hi：`Guard अनाथ सूचनाएँ (इन-ऐप)` / `Guard मिलान में नई गंभीर अनाथ क्रियाएँ मिलने पर टीम owner और admin को इन-ऐप सूचना भेजें। प्रति कार्यक्षेत्र प्रति घंटा अधिकतम एक सारांश।` / `Guard अनाथ सूचनाएँ (ईमेल)` / `वही सारांश ईमेल से भी भेजें (केवल SaaS; प्रति प्राप्तकर्ता प्रति कार्यक्षेत्र 24 घंटे में अधिकतम एक)। केवल तब प्रभावी जब इन-ऐप स्विच चालू हो।`

- [ ] **Step 1: 写失败的测试**：
  - 路由（照 `admin-platform-settings-capability.test.ts` 的 mock 结构）：非管理员 403；`value: "true"` → 400 `invalid_capability_setting`；`true` → 设置与 `auditLogs`（`action: 'platform_setting.update'`、`metadata: { old: false, new: true }`）在同一事务写入；GET 返回两个新键且缺省 `false`。
  - `getSetting` 对两键的缺省值为 `false`（行缺失 / DB 报错都返回 `false`）。
  - 卡片：渲染出两行新开关（按 `key` 查 DOM），点击发出 `POST /api/admin/platform-settings` `{ key, value: true }`，失败回滚。
- [ ] **Step 2:** `CU src/__tests__/api/admin-platform-settings-guard-orphan.test.ts src/__tests__/components/feature-flags-card.test.tsx` → FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同 Step 2 → PASS；`CU src/__tests__/api/admin-platform-settings-*.test.ts` → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task C5: 收件人与载荷构造（cloud，PR-C2）

**Files:**
- Create: `src/lib/guard-orphan-notify/recipients.ts`、`src/lib/guard-orphan-notify/payload.ts`
- Test: `src/__tests__/lib/guard-orphan-notify/recipients.test.ts`、`payload.test.ts`、`src/__tests__/integration/guard-orphan-notify-recipients.integration.test.ts`

**Interfaces:**
- Produces（`recipients.ts`，`import 'server-only'`）：
  - `type OrphanRecipients = { kind: 'team'; tenantName: string; userIds: string[] } | { kind: 'personal'; userIds: string[] } | { kind: 'unknown' }`
  - `resolveOrphanRecipients(tenantId: string): Promise<OrphanRecipients>`：全部经 `freshDb`。先查 `teams.id = tenantId`：命中 → 该团队 `teamMembers` inner join `users`（`users.deletedAt IS NULL`），保留 `getRoleLevel(role) ≥ getRoleLevel('admin')`（未知角色字符串丢弃），`userIds` 去重、按 id 排序；未命中 → 查 `users.id = tenantId AND deletedAt IS NULL`：命中 → `personal`（`[tenantId]`）；否则 `unknown`。团队存在但用户已删的个人租户、无管理员的团队都返回空 `userIds`（不是 `unknown`）。
- Produces（`payload.ts`，客户端安全）：
  - `orphanNoticeId(tenantId: string, maxOrphanId: number, userId: string): string` → `` `guard-orphans:${tenantId}:${maxOrphanId}:${userId}` ``
  - `orphanNoticeHref(tenantId: string): string`（同 T-C3 的 href；T-C3 的 `renderText` 改为调用它）
  - `buildOrphanNotice(summary: OrphanFeedSummary, tenantName: string): GuardOrphansDetectedNotification`：逐字段**显式**拷贝白名单；`byKind` 中 `ORPHAN_KINDS` 里 severity 为 critical 的键原样保留，其余键（含未知与非 critical）累加进 `other`（为 0 时不写 `other`）；`newTotal = summary.total`；`maxOrphanId = summary.maxId`；`runIds = summary.runIds.slice(0, 8)`；`firstDetectedAt / lastDetectedAt` 必填（`total > 0` 时调用，缺失则抛错）。

- [ ] **Step 1: 写失败的测试**：
  - 单元（mock `freshDb`）：team 角色 `owner`、`admin` 收，`member`、`viewer`、`bogus` 不收；个人租户本人收；已删个人用户 → `{kind:'personal', userIds: []}`；都查不到 → `unknown`；每次调用都重新查询（两次调用间改 mock 角色 → 结果随之变化，无缓存）。
  - `payload is whitelisted`：输入对象额外带 `detail`、`executorId`、`decisionId`、`source`、`actionName` → 输出键集恰为 8 个白名单键（快照测试 `toMatchInlineSnapshot`）。
  - `unknown critical kind counted as other`：`byKind: { side_effect_mismatch: 2, future_kind: 3 }` → `{ side_effect_mismatch: 2, other: 3 }`。
  - `orphanNoticeId` 格式；`runIds` 截断到 8。
  - 集成（PG）：真实 `Team` / `TeamMember` / `User` 行，含已软删的 admin → 不收。
- [ ] **Step 2:** `CU src/__tests__/lib/guard-orphan-notify/recipients.test.ts src/__tests__/lib/guard-orphan-notify/payload.test.ts` 与 `CI src/__tests__/integration/guard-orphan-notify-recipients.integration.test.ts` → FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task C6: 通知状态存储（比较后写事务）（cloud，PR-C2，**依赖 0070 已应用**）

**Files:**
- Create: `src/lib/guard-orphan-notify/state.ts`
- Test: `src/__tests__/integration/guard-orphan-notify-state.integration.test.ts`

**Interfaces:**
- Produces（`import 'server-only'`）：
  - `interface NotifyState { tenantId: string; notifiedThroughId: number; lastNoticeAt: Date | null; pendingSince: Date | null }`
  - `loadNotifyStates(tenantIds: string[]): Promise<Map<string, NotifyState>>`：`freshDb`、`inArray`，按 200 个一批；缺行的租户不在 Map 中（调用方视为 `notifiedThroughId = 0`）。
  - `markPending(tenantIds: string[], now: Date): Promise<void>`：`INSERT … ("tenantId","notifiedThroughId","pendingSince") VALUES (…, 0, now) ON CONFLICT ("tenantId") DO UPDATE SET "pendingSince" = COALESCE("GuardOrphanNotifyState"."pendingSince", EXCLUDED."pendingSince")`。
  - `clearPending(tenantId: string): Promise<void>`。
  - `commitTenantNotice(p: { tenantId: string; expectedThroughId: number; newThroughId: number; notices: Array<{ id: string; userId: string; data: GuardOrphansDetectedNotification }>; now: Date }): Promise<'committed' | 'cas_lost'>`：一个 `db.transaction`：
    1. `INSERT INTO "GuardOrphanNotifyState" ("tenantId","notifiedThroughId","lastNoticeAt","pendingSince","updatedAt") VALUES (:t, :new, :noticeAt, NULL, :now) ON CONFLICT ("tenantId") DO UPDATE SET "notifiedThroughId" = EXCLUDED."notifiedThroughId", "lastNoticeAt" = COALESCE(EXCLUDED."lastNoticeAt", "GuardOrphanNotifyState"."lastNoticeAt"), "pendingSince" = NULL, "updatedAt" = EXCLUDED."updatedAt" WHERE "GuardOrphanNotifyState"."notifiedThroughId" = :expected RETURNING "tenantId"`，其中 `:noticeAt = notices.length > 0 ? now : null`（R7）。前置断言 `newThroughId > expectedThroughId`，否则抛错。
       - 无行返回 → 抛内部哨兵错误使事务回滚，函数返回 `'cas_lost'`。
       - 注：无状态行且 `expectedThroughId = 0` 时走 INSERT 分支；已有行时 `WHERE` 校验旧值。**无状态行但 `expectedThroughId ≠ 0`** 不会发生（调用方从缺行推得 0）。
    2. `notices` 非空 → `tx.insert(notifications).values(rows).onConflictDoNothing({ target: notifications.id })`，`kind: 'guard.orphans_detected'`、`readAt: null`、`createdAt: now`。
    3. 提交 → `'committed'`。

- [ ] **Step 1: 写失败的测试**（集成，PG，每例新租户 id）：
  - `first commit inserts state and notices`：无行、`expected 0 → new 10`、两个收件人 → 状态行 `(10, lastNoticeAt=now, pendingSince=null)`，两条通知行 id 正确。
  - `stale expectation loses`：状态为 10 时以 `expected 5 → new 12` 提交 → `'cas_lost'`，状态仍 10，无新通知行。
  - `concurrent commits advance once`：两次 `commitTenantNotice`（`expected 0`，`new` 分别为 10 与 12、不同收件人集合）`Promise.all` 并发 → 恰一个 `'committed'`、一个 `'cas_lost'`；通知行只属于胜者。
  - `notice insert failure rolls back watermark`：通知 `data` 构造成违反 `NOT NULL`（`userId: null as never`）→ 抛错，状态行不存在 / 不变。
  - `empty recipients advance only`：`notices: []` → 水位推进、`lastNoticeAt` 保持原值（null 仍 null）、无通知行。
  - `duplicate notice id ignored`：预先插入同 id 的通知行 → 提交成功，行数不变。
  - `markPending keeps the earliest`：两次 `markPending`（t1 < t2）→ `pendingSince = t1`；随后 `commitTenantNotice` 清空。
  - `loadNotifyStates` 返回已存在的行、忽略缺行。
- [ ] **Step 2:** `CI src/__tests__/integration/guard-orphan-notify-state.integration.test.ts` → FAIL。
- [ ] **Step 3:** 实现（原生 SQL 用 drizzle `sql` 模板，标识符加双引号）。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task C7: 每 tick 编排 `notifyOrphansDetected`（cloud，PR-C2，**依赖 0070 已应用**）

**Files:**
- Create: `src/lib/guard-orphan-notify/tick.ts`
- Modify: `src/lib/guard-notifications.ts`（新增 `notifyOrphansDetected(now?: Date): Promise<OrphanNotifyReport>`，以缺省依赖调用 `runOrphanNotifyTick`；文件头注释补一句 ADR 0065）
- Test: `src/__tests__/lib/guard-orphan-notify/tick.test.ts`（全部依赖注入假实现）、`src/__tests__/integration/guard-orphan-notify-tick.integration.test.ts`（真实 PG + 假通知源）

**Interfaces:**
- Produces（`tick.ts`）：
  ```ts
  export const NOTIFY_LOOKBACK_MS = 7 * 24 * 3600_000;
  /** cloud 与 aster-api 时钟偏差余量（R2）：detectedSince 取 now − 7d + 5min，避免 aster-api 严格下界 400。 */
  export const DETECTED_SINCE_SKEW_MS = 5 * 60_000;
  export const QUIET_PERIOD_MS = 3600_000;
  export const MAX_TENANTS_PER_TICK = 50;
  export const SUMMARY_CONCURRENCY = 4;
  export interface OrphanNotifyDeps {
    inAppEnabled(): Promise<boolean>;            // getSetting(GUARD_ORPHAN_NOTIFICATIONS_ENABLED)
    feed: OrphanFeed;
    loadStates: typeof loadNotifyStates;
    markPending: typeof markPending;
    clearPending: typeof clearPending;
    commit: typeof commitTenantNotice;
    recipients: typeof resolveOrphanRecipients;
    afterCommit?(c: { tenantId: string; notice: GuardOrphansDetectedNotification; userIds: string[] }): Promise<void>; // 第二阶段邮件挂点；PR-C2 中为 undefined
  }
  export interface OrphanNotifyReport {
    ran: boolean; tenantsIndexed: number; pages: number; tenantsWithNew: number;
    noticesCreated: number; recipients: number; deferredQuiet: number; deferredBacklog: number;
    summaryFailed: number; casLost: number; noRecipients: number; emailed: number;
    maxLagSeconds: number | null; oldestPendingSeconds: number | null;
  }
  export async function runOrphanNotifyTick(deps: OrphanNotifyDeps, now: Date): Promise<OrphanNotifyReport>;
  ```
- 算法（ADR §3.4.3，含 R3 / R5–R7）：
  1. `inAppEnabled()` 假 → 返回 `ran: false`（其余计数 0、两个秒数 null），不调用任何其他依赖。
  2. `detectedSince = new Date(now − NOTIFY_LOOKBACK_MS + DETECTED_SINCE_SKEW_MS).toISOString()`；循环 `feed.tenants({ detectedSince, afterTenant: next })` 直到无 `next`，`pages` 计页数，`tenantsIndexed` 计项数。任一页抛错 → **整个 tick 抛出**（不改状态）。
  3. `states = loadStates(ids)`；`candidates = items.filter(i => i.maxId > (states.get(i.tenantId)?.notifiedThroughId ?? 0))`；`tenantsWithNew = candidates.length`。
  4. 安静期：`lastNoticeAt && now − lastNoticeAt < QUIET_PERIOD_MS` → 进 `quiet`；其余**按 `(pendingSince ?? now)` 升序、再按 `tenantId` 升序**（先进先出：被推迟过的租户 `pendingSince` 更早，下一 tick 必然排在新出现的租户之前，不会因租户 id 字典序被持续挤到 50 名之外）取前 50 个进 `work`，剩下进 `backlog`。`markPending([...quiet, ...backlog])`；`deferredQuiet` / `deferredBacklog` 计数；`oldestPendingSeconds` = 这些租户的 `pendingSince ?? now` 的最小值到 `now` 的秒数（无则 null）。
  5. `work` 经 `createLimiter(SUMMARY_CONCURRENCY)` 并发处理每个租户（单租户内部失败不影响其他）：
     - `afterId = state?.notifiedThroughId ?? 0`；`s = feed.summary({ tenantId, afterId, detectedSince })`；抛错 → `summaryFailed++`、`console.error`、跳过（水位不动）。
     - `s.total === 0` → `clearPending(tenantId)`，跳过（R6）。
     - `r = recipients(tenantId)`；`unknown` → `console.warn('[guard-orphan-notify] unknown tenant', { tenantId })`，`userIds = []`；`tenantName = r.kind === 'team' ? r.tenantName : ''`。
     - `userIds` 空 → `noRecipients++`。
     - `notice = buildOrphanNotice(s, tenantName)`；`notices = userIds.map(u => ({ id: orphanNoticeId(tenantId, s.maxId, u), userId: u, data: notice }))`。
     - `res = commit({ tenantId, expectedThroughId: afterId, newThroughId: s.maxId, notices, now })`；`'cas_lost'` → `casLost++`、跳过；`'committed'` → `noticesCreated += notices.length > 0 ? 1 : 0`、`recipients += notices.length`、更新 `maxLagSeconds = max(…, (now − firstDetectedAt)/1000)`（仅 `notices.length > 0` 时）；`deps.afterCommit` 存在且 `notices.length > 0` 时 `await` 它，其抛错只记 `console.error`、不计入失败。
     - commit 抛出非 CAS 的错误 → `summaryFailed++`、`console.error`、跳过。
  6. 返回报告。
- `notifyOrphansDetected(now = new Date())` 组装缺省依赖：`createOrphanFeedClient()`、`getSetting(...)`、T-C5 / T-C6 函数、`afterCommit: undefined`。

- [ ] **Step 1: 写失败的测试**（单元，假依赖记录调用）：
  - `disabled does nothing`：开关关 → `ran:false`，`feed.tenants` / `loadStates` 均未调用。
  - `first enable sends one summary per tenant`：3 个租户无状态行，各有若干 OPEN critical → 每租户 `commit` 一次、`expectedThroughId 0`；之后同样的 ① 再跑一 tick（状态已推进）→ 无 `summary` 调用。
  - `notified orphan never re-sent`：水位 = ① 的 `maxId` → 不调 ②。
  - `quiet period defers without advancing`：`lastNoticeAt = now − 30min`、有新孤儿 → 不调 ②、不 commit、`markPending` 收到该租户、`deferredQuiet=1`；`now − 61min` → 调 ② 并 commit（合并期间所有新增：`afterId` 仍是旧水位）。
  - `first notice ignores quiet period`：`lastNoticeAt = null` → 立即发。
  - `cap 50 and backlog`：60 个候选（均无 `pendingSince`）→ `summary` 恰 50 次（按 tenantId 升序前 50）、`deferredBacklog=10`、其余 10 个 `markPending`；`summary` 在途峰值 ≤ 4。
  - `backlog is FIFO, not starved by smaller tenant ids`：第一 tick 60 个候选，被推迟的 10 个（id 最大）获得 `pendingSince = t1`；第二 tick 另有 50 个 id 更小的新候选出现 → 第二 tick 的 `work` 含全部那 10 个旧候选（再加 40 个新候选），`summary` 调用顺序以它们开头。
  - `detectedSince has skew margin`：`feed.tenants` / `feed.summary` 收到的 `detectedSince` = `now − 7d + 5min`。
  - `index failure changes nothing`：① 第二页抛错 → tick 抛错；`summary`、`commit`、`markPending` 均未调用。
  - `single summary failure skips that tenant`：3 个租户中一个 ② 抛错 → 其他两个照常 commit，`summaryFailed=1`。
  - `all acknowledged before summary sends nothing`：② `total=0` → 不 commit，`clearPending` 被调用。
  - `tenant without admins advances watermark only`：`recipients` 返回 `{kind:'team', userIds:[]}` → `commit` 以 `notices: []` 调用、`noRecipients=1`、`noticesCreated=0`；`unknown` 同样只推进并 `console.warn` 一次。
  - `uses summary maxId, not index maxId`：① 报 10、② 报 12 → `newThroughId = 12`，通知 id 用 12。
  - `cas lost is counted not thrown`：`commit` 返回 `'cas_lost'` → `casLost=1`，tick 正常返回。
  - `report fields`：`maxLagSeconds`、`oldestPendingSeconds` 按定义计算。
  - 集成（PG + 假通知源 + 真实 T-C5 / T-C6）：
    - `rerun of same batch creates no second row`：同一假通知源结果跑两 tick（第二次手动把状态改回旧水位以模拟重放，`commit` 以同一 `maxId` 写）→ 每收件人仍只有一条通知行。
    - `rows committed after summary are picked up next tick`：tick1 时 ② 返回 `maxId=10`，随后假通知源新增 11、12 → tick2 以 `afterId=10` 调 ②，只计 11、12，产生第二条通知（tick2 的 `now` 设在安静期后）。
    - `two concurrent ticks notify once`：同一状态、两个 tick `Promise.all` → 每收件人一条通知行、`casLost` 之和为 1。
- [ ] **Step 2:** `CU src/__tests__/lib/guard-orphan-notify/tick.test.ts` 与 `CI src/__tests__/integration/guard-orphan-notify-tick.integration.test.ts` → FAIL。
- [ ] **Step 3:** 实现（`runOrphanNotifyTick` 按上述 6 步拆成小函数：`indexTenants`、`partition`、`processTenant`，每个缩进 ≤ 3 层）。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task C8: cron 路由、注册表、触发器与心跳（cloud，PR-C2）

**Files:**
- Create: `src/app/api/cron/guard-orphan-notify/route.ts`
- Modify: `src/lib/cron-registry.ts`（`guard-deploy-sync` 之后追加条目）、`wrangler.toml`（`[triggers].crons` 追加 `"2-59/5 * * * *"`；注释列表加一行，「当前 12 条」改为 13 条）、`worker.js`（`CRON_DISPATCH` 加 `"2-59/5 * * * *": "/api/cron/guard-orphan-notify"`）、`docs/on-prem/enterprise-deployment-guide.md`（cron 表加 `` | `2-59/5 * * * *` | `/api/cron/guard-orphan-notify` | 是 | Guard 对账 critical 孤儿的租户站内通知（aster-api ADR 0065）；开关缺省关 | ``）
- Test: `src/__tests__/api/cron-guard-orphan-notify.test.ts`、既有 `src/__tests__/lib/cron-registry*.test.ts`（不改，自动覆盖新条目）、`src/__tests__/lib/cron-registry.test.ts`（追加窗口用例）

**Interfaces:**
- Produces（注册表条目）：
  ```ts
  {
    jobName: 'guard-orphan-notify',
    cron: '2-59/5 * * * *',
    routePath: '/api/cron/guard-orphan-notify',
    // 每 5 分钟、错开 2 分钟（aster-api ADR 0065 §3.4.3）：aster-api 对账在整 5 分钟处开跑；'*/5' 已被 guard-deploy-sync 占用。
    windowStartFor: (now) => new Date(Math.floor((now.getTime() - 120_000) / 300_000) * 300_000 + 120_000),
    healthcheckEnv: 'HEALTHCHECKS_GUARD_ORPHAN_NOTIFY_URL',
  }
  ```
- Produces（路由，`runtime = 'nodejs'`、`dynamic = 'force-dynamic'`）：`POST` → `requireCronAuth` → `parseCronWindow(req, JOB)` → `runCronOnce(JOB, () => notifyOrphansDetected(), …)`；未执行 → `{ ok: true, ran: false, reason: skippedReason, leaseId }`；执行但开关关 → `{ ok: true, ran: false, reason: 'disabled', leaseId }`；执行 → `{ ok: report.summaryFailed === 0, ran: true, leaseId, ...report }`。`fn` 抛错由 `runCronOnce` 标失败并重抛，路由不吞（Next 返回 500）。`summaryFailed > 0` 时 `console.error`。

- [ ] **Step 1: 写失败的测试**：
  - 路由（mock `notifyOrphansDetected` 与 `runCronOnce`，或 `BYPASS_CRON_LEASE=1`）：无 cron 鉴权 → 401；开关关 → `ran:false, reason:'disabled'`；正常 → 报告字段透出；`feed 403 marks lease failed`：`notifyOrphansDetected` 抛 `OrphanFeedError(403,…)` → 响应 5xx、`runCronOnce` 走失败分支（mock 断言）。
  - 注册表：`windowStartFor(2026-11-28T10:07:30Z)` = `10:07:00Z`；`10:01:59Z` → `09:57:00Z`；`10:02:00Z` → `10:02:00Z`；`getCronByExpression('2-59/5 * * * *')?.jobName === 'guard-orphan-notify'`；表达式在注册表中唯一。
  - 既有三条奇偶测试（worker / wrangler / on-prem 指南）在加条目前先 FAIL（只改注册表时），四处都改后 PASS。
- [ ] **Step 2:** `CU src/__tests__/api/cron-guard-orphan-notify.test.ts src/__tests__/lib/cron-registry.test.ts src/__tests__/lib/cron-registry-worker-sync.test.ts src/__tests__/lib/cron-registry-onprem-doc.test.ts` → FAIL。
- [ ] **Step 3:** 实现四处改动。
- [ ] **Step 4:** 同 Step 2 → PASS；`CALL` → PASS；`corepack pnpm exec wrangler deploy --dry-run --outdir <scratchpad>/wrangler-dry` 不报触发器语法错误（见 Review Focus 7：最终以预发 Worker 的触发器列表为准）。
- [ ] **Step 5:** Commit。

### Task C9: 本地栈端到端与预发验证清单（cloud，PR-C2）

**Files:**
- Create: `src/__tests__/integration/guard-orphan-notify-e2e.integration.test.ts`（真实 aster-api 可达时运行，否则 `describe.skipIf(!process.env.E2E_ASTER_API_URL)`）、`docs/runbooks/guard-orphan-notifications.md`（中文运维说明：开关、cron、心跳 env、回滚、观测字段（`oldestPendingSeconds > 86400` 或 `summaryFailed` 连续非 0 需排查：超过 7 天的积压会落出回看窗口而不再通知）、预发验证步骤）
- Modify: 无

**内容：**
- 端到端（本地全栈：aster-api 含 PR-A 镜像 + cloud 含 PR-C1 迁移，共用 `ASTER_PLAN_GATE_HMAC_KEY`）：
  1. 以 `MEMBER` key 对测试团队 `POST /api/v1/guard/side-effects` 上报一条无 `decisionId` 的副作用；等待下一轮对账产生 `side_effect_without_decision`（本地栈 aster-api 设 `ASTER_GUARD_RECONCILIATION_CRON='0 * * * * ?'`、`ASTER_GUARD_RECONCILIATION_MIN_INTERVAL=PT0S`，用例轮询 `GET /api/v1/guard/reconciliation/orphans` 至多 120 s）。
  2. 打开站内开关（直接 `setSetting`）后调用 `notifyOrphansDetected()` → 团队 owner / admin 各一条 `guard.orphans_detected`，member 无；载荷 `newTotal = 1`。
  3. 确认该孤儿后再上报第二条、再对账、把 `lastNoticeAt` 调到 61 分钟前、再 tick → 第二条通知 `newTotal = 1`、`maxOrphanId` 更大。
  4. 安静期内连报两条 → tick 不发；把 `lastNoticeAt` 调到期满 → 一条 `newTotal = 2`。
  5. 改错 cloud 侧 key → tick 抛错、状态表不变。
- 预发验证（ADR §4 第 3 步，需用户 / 控制者在预发执行，计划执行者只交付 runbook 与命令）：同上 1–4 步，以真实 cron 驱动，验收「10 分钟内收到」「member 不收」「第二条只计新增」「安静期合并」。

- [ ] **Step 1:** 写端到端用例与 runbook。
- [ ] **Step 2:** 按 `aster-local-verification-env` 的全栈配方起栈，`E2E_ASTER_API_URL=http://localhost:8080 CI src/__tests__/integration/guard-orphan-notify-e2e.integration.test.ts` → PASS；结果（含截图或查询输出）贴进 PR-C2。
- [ ] **Step 3:** `CALL` → PASS；`corepack pnpm test:integration` → PASS。
- [ ] **Step 4:** Commit；开 PR-C2（中文正文：`Refs #843`、依赖 PR-A 已部署与 0070 已应用、开关缺省关、预发验证待执行）；独立 Codex 审查 ≥ 90；CI 绿；**合并前**核对「迁移与上线次序」第 1、2 条。

---

### Task C10: 邮件模板与发送函数（cloud，PR-C3，第二阶段）

**Files:**
- Create: `src/lib/guard-orphan-notify/email.ts`
- Test: `src/__tests__/lib/guard-orphan-notify/email.test.ts`

**Interfaces:**
- Produces（`import 'server-only'`）：
  - `renderOrphanNoticeEmail(notice: GuardOrphansDetectedNotification, appUrl: string): { subject: string; html: string; text: string }`：英文（R8）。`subject = tenantName ? `[Aster] New critical Guard findings in ${tenantName}` : '[Aster] New critical Guard findings in your personal workspace'`（主题中的团队名去掉 CR / LF、截断 80 字符）；正文：总数、按 kind 计数（kind 以固定英文标签映射表渲染，`other` → `Other`）、检出时间范围（UTC）、按钮链接 `appUrl + orphanNoticeHref(tenantId)`、一句「This is a security notification and cannot be unsubscribed.」；HTML 中所有插值经 `escapeHtml`。**不得**引用载荷以外的任何字段。
  - `sendOrphanNoticeEmail(to: string, notice: GuardOrphansDetectedNotification): Promise<boolean>`：`!CAN_RESEND` → `false`；`getResend()` 为 null → `false`；`resend.emails.send({ from: 'Aster Cloud <' + fromEmail() + '>', to, subject, html, text })`，Resend 返回 `error` 或抛错 → `console.error` 并 `false`；成功 → `true`。`appUrl` 取与 `sendPasswordResetEmail` 相同的应用基址来源。

- [ ] **Step 1: 写失败的测试**：主题 / 正文含计数与链接；团队名 `<script>` 被转义、含 `\r\n` 的团队名不进主题换行；`other` 渲染；渲染结果不含夹具里刻意混入的 `detail` 串；on-prem（`CAN_RESEND=false`）不调用 Resend 并返回 false；Resend 报错返回 false 且不抛。
- [ ] **Step 2:** `CU src/__tests__/lib/guard-orphan-notify/email.test.ts` → FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task C11: 邮件接入 tick、24 h 上限与邮件开关（cloud，PR-C3，第二阶段，**依赖 0070 已应用**）

**Files:**
- Modify: `src/lib/guard-orphan-notify/state.ts`（新增 `loadEmailStates(tenantId: string, userIds: string[]): Promise<Map<string, Date>>`（`freshDb`）、`recordEmailSent(tenantId: string, userId: string, at: Date): Promise<void>`（upsert））、`src/lib/guard-notifications.ts`（缺省依赖的 `afterCommit` 指向 `emailAfterCommit`）、`src/lib/guard-orphan-notify/tick.ts`（导出 `EMAIL_CAP_MS = 24 * 3600_000` 与 `emailAfterCommit(deps)` 工厂；`report.emailed` 累加）
- Test: `src/__tests__/lib/guard-orphan-notify/email-after-commit.test.ts`、`src/__tests__/integration/guard-orphan-notify-email-state.integration.test.ts`

**Interfaces:**
- Produces：`emailAfterCommit(deps: { emailEnabled(): Promise<boolean>; canResend: boolean; loadEmailStates; recordEmailSent; userEmails(userIds: string[]): Promise<Map<string, string>> /* freshDb，users.email 非空且未删除 */; send: typeof sendOrphanNoticeEmail; now: Date; onSent(): void })`，返回 `afterCommit` 函数：
  1. `!canResend || !(await emailEnabled())` → 直接返回（站内开关已由 tick 第一步保证）。
  2. `last = loadEmailStates(tenantId, userIds)`；对每个 `userId`：`last.get(u)` 存在且 `now − last < EMAIL_CAP_MS` → 跳过；无邮箱 → 跳过；`send(email, notice)` 为 `true` → `recordEmailSent(tenantId, u, now)`、`onSent()`；`false` / 抛错 → `console.error`，不重试、不回滚站内通知。
  3. 顺序发送（收件人数小），总耗时受 Worker 时限约束：单 tick 至多 50 租户 × 管理员数。
- 只有 `commit` 返回 `'committed'` 且有通知行的租户才会触发（T-C7 第 5 步），因此 CAS 落败的 tick 不会发邮件。

- [ ] **Step 1: 写失败的测试**：
  - `email off sends nothing`；`on-prem sends nothing`（`canResend=false`）。
  - `cap 24h per recipient per tenant`：`lastEmailAt = now − 23h` → 不发；`now − 25h` → 发并 `recordEmailSent`；同一用户在另一租户 → 照发（按租户计）。
  - `send failure does not throw or record`：`send` 返回 false / 抛错 → 不写 `lastEmailAt`，`afterCommit` 正常返回。
  - `in-app still sent when email throws`：tick 集成——`send` 抛错时通知行与水位照常提交。
  - `cas lost tick sends no email`：T-C7 的并发集成用例接上邮件依赖 → 邮件发送次数 = 收件人数（只胜者发）。
  - 集成：`recordEmailSent` upsert 覆盖旧值；`loadEmailStates` 只返回给定用户。
- [ ] **Step 2:** `CU src/__tests__/lib/guard-orphan-notify/email-after-commit.test.ts` 与 `CI src/__tests__/integration/guard-orphan-notify-email-state.integration.test.ts src/__tests__/integration/guard-orphan-notify-tick.integration.test.ts` → FAIL。
- [ ] **Step 3:** 实现；runbook 追加「邮件阶段」一节（开关、上限、无退订、仅 SaaS）。
- [ ] **Step 4:** 同 Step 2 → PASS；`CALL` → PASS。
- [ ] **Step 5:** Commit；开 PR-C3（中文正文，`Refs #843`，写明「邮件开关由用户在站内阶段观察一周且 Q3 条件满足后打开」）；独立 Codex 审查 ≥ 90；CI 绿。

---

## 验收（对应 ADR §6）

| ADR §6 测试项 | 任务 / 用例 |
|---|---|
| 通知源鉴权（403 / 401 / v1 401 / 409 / Bearer 不可达） | T-A1、T-A3 |
| 过滤、翻页、`total=0`、`detectedSince` / `limit` 400 | T-A2、T-A3 |
| 防饥饿 | T-A2 `backlogDoesNotStarve` |
| 响应字段钉住、无上报方文本 | T-A3 `responseKeysPinned`、T-A5 夹具 |
| 共用校验器后 Snapshot 测试不变 | T-A1 Step 4 |
| 按水位判新增、重复执行一组、安静期、首条、① / ② 失败、`total=0`、开关关、50 上限 | T-C7 |
| 收件人规则与现查 | T-C5 |
| 载荷白名单、未知 kind → other | T-C5 |
| 铃铛四语、链接参数 | T-C3 |
| 注册表 / wrangler 同步、不与 `*/5` 冲突 | T-C8 |
| 两仓共享夹具逐字节一致 | T-A5、T-C2（sha256 常量相同） |
| PG：同事务、并发只推进一次 | T-C6、T-C7 集成 |
