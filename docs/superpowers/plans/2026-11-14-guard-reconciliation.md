# Guard 对账：孤儿动作检测任务与 `guard_orphan_actions` 指标 实施计划（#465）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** aster-api 定时按租户比对「执行侧观测到的副作用」与「决策 + consume + result 账本」，把对不上的记录为孤儿动作（持久化、入链、`guard_orphan_actions{tenant,executor,kind}` 指标、告警、查询与确认端点），使「不知道的绕过路径」在下一轮对账内可见。

**Architecture:** 两路数据面。① **账本自检**（零依赖，main 上已有）：只读 `guard_decisions / guard_approvals / guard_receipts`，查出「已消耗无结果」「未消耗的 ALLOW」「对非 ALLOW 的消耗」「无消耗的结果」。② **副作用上报**（新）：副作用落地方——工具服务、业务系统、cloud——经 `POST /api/v1/guard/side-effects` 批量上报「我做了什么、凭哪个 decisionId」，对账把每条上报对到收据行；**这是唯一能发现绕过的数据面**，因为绕过者从不触达 Guard 与代理。全部落在新包 `io.aster.guard.reconcile`：三张新表、一个按租户 `pg_try_advisory_xact_lock` 串行的调度、一个纯函数分类器、一个 DB 支撑的 MultiGauge。不依赖 #464：验收测试直接调 REST 端点模拟执行方与上报方。

**Tech Stack:** Quarkus 3 / RESTEasy Reactive / Hibernate Panache / Flyway / PostgreSQL 17 / `quarkus-scheduler` / Micrometer Prometheus；JUnit 5 + RestAssured + `@QuarkusTest`；k3s 侧 `PrometheusRule` + `promtool test rules`。

**Spec（按此顺序读）：**
- wontlost-ltd/aster-api#465（本计划）
- `docs/adr/0054-guard-execution-point.md` §5（对账数据契约）、§7 末段、§8
- `docs/adr/0040-action-guard.md` §12 D4（`guard_receipts`）、D5（consume；「对账只认 `consume_audit_id`」）、D7（结果回写与 `aster.guard.result-grace`）、D10（「有 ALLOW 从未 consume」是 #465 要抓的）
- `docs/adr/0041-evidence-chain-enrichment.md` §12 D1（「读法」：有效消耗只认 `guard_receipts.consume_audit_id`；不数事件、不看 `success`）
- `docs/superpowers/plans/2026-11-08-guard-receipts-and-input-provenance.md` 「收据接口契约」表（本计划只消费其中 `guard_receipts` 行与 `result-grace`，不改其形状）
- 既有模式：`GuardApprovalExpiryScheduler`（guard 调度、`identity` + `SKIP` + `BackgroundSchedulerSkipPredicate`、不加外层 `@Transactional`）、`AuditChainAnchorService`（多副本下靠 `INSERT … SELECT … ON CONFLICT DO NOTHING` 幂等）、`AnomalyDetectionScheduler`（检测 + 保留期清理 + 报实际落库数）；指标：`GuardMetrics`（Counter 工厂）、`WorkflowMetrics`（DB 支撑 Gauge）、`PolicyCacheManager:596`（`tenant` 标签先例）

## 设计裁定（本计划已选默认；需用户确认的列在文末「待用户裁定」）

**D1 业务侧事实来源。** 选项：

| 选项 | 能抓什么 | 抓不到 | 结论 |
|---|---|---|---|
| A. 只读账本（`guard_receipts` + 链事件） | 已消耗无结果、未消耗 ALLOW、对非 ALLOW 消耗、无消耗结果 | **绕过**（绕过者不留任何账本记录） | 必做，但不够 |
| B. 执行方（代理 / SDK）自报执行记录 | 同 A（它的「执行记录」就是 consume / result，ADR 0054 §5） | 绕过——被绕过的正是它 | 不做（与 A 重复） |
| C. **副作用落地方上报**（工具服务、业务系统、cloud）批量端点 | 无 decisionId 的副作用、凭未消耗 / 非 ALLOW 决策落地的副作用、参数或尝试不符、一次决策多次落地 | 落地方本身不上报的副作用 | **选 A + C** |
| D. aster-api 主动拉取业务系统（连接器） | 同 C | — | 不做：每个系统一个连接器，超出本期 |

C 的独立性来自「观测者 ≠ 执行者」：绕过路径能跳过代理，但副作用总要落在某个系统里，由那个系统上报。ADR 0054 §3.3 `auth` 隔离模式下工具侧本就收到 `X-Aster-Guard-Consume: {decisionId, attemptId}`，它是天然的上报方（#464 集成点 I2）。aster-api 自身没有执行方（workflow runner 不接 Guard，`grep -rli guard src/main/java/io/aster/api/workflow` 无相关命中），cloud 执行路径的 consume 尚未落地（ADR 0040 §12.4 ④），所以本期「Aster 自有执行方」的覆盖 = A 对全部带收据决策的自检 + C 的端点就绪。

**D2 孤儿类别（`kind`，固定枚举，snake_case，`severity` 由 kind 决定，不另作标签）：**

| kind | 判定（租户 `t`，`cutoff = now − result-grace`，`since = now − lookback`） | severity | 自动解除 |
|---|---|---|---|
| `side_effect_without_decision` | 上报无 `decisionId`，或该 id 在本租户 `guard_decisions` 不存在 | critical | 否（只能确认） |
| `side_effect_unconsumed` | 上报的决策存在，但无收据行或 `consumed_at IS NULL`（含 DENY / PENDING / 迁移前旧 ALLOW / 未消耗 ALLOW） | critical | 否 |
| `side_effect_mismatch` | 决策已消耗，但上报的 `attemptId` ≠ 行 `attempt_id`、或 `actionHash` ≠ 行 `action_hash`（上报方给了才比）；或同一 `(decision_id, source)` 已有更早的已匹配上报（`detail.reason = replayed`） | critical | 否 |
| `consumed_without_result` | `consumed_at ∈ [since, cutoff)` 且 `result_status IS NULL`（「执行侧丢失」，ADR 0040 §12 D7） | warning | 是：之后补到结果 → `RESOLVED / late_result` |
| `allow_unconsumed` | `consumed_at IS NULL` 且 `expires_at < cutoff` 且 `issued_at ≥ since`（ADR 0040 §12 D10 的治理缺口；无法区分「Agent 决定不做」与「旧 SDK 不 consume 照做」） | info | 是：`detected_at` 超过 `allow-unconsumed-ttl` → `RESOLVED / aged_out` |
| `consume_on_non_allow` | `consumed_at IS NOT NULL` 且决策既非 `outcome='ALLOW'` 也无 `APPROVED` 审批，或收据行与决策行租户不同（服务端已 409 `not_allowed`，出现即 aster-api 自身被绕过） | critical | 否 |
| `result_without_consume` | `result_status IS NOT NULL` 且 `consumed_at IS NULL`（行级；不按 `ACTION_GUARD_RESULT` 事件判：复核失败的结果事件按 ADR 0041 §12 D1 读作未生效，是合法状态） | critical | 否 |

「有效消耗」一律以收据行（`consumed_at` / `consume_audit_id`）为准，不数 `ACTION_GUARD_CONSUME_*` 事件、不看 `success` 列（ADR 0041 §12 D1）。迁移前（`V6.35.0` 之前）的决策没有收据行，账本自检不覆盖它们（不回填，ADR 0040 §12 D10）；上报指向它们时归 `side_effect_unconsumed`。

**D3 租户范围。** 每租户独立一次对账、独立事务、独立失败。候选租户 = `guard_receipts.issued_at ≥ since` ∪ `guard_side_effects.reconciled_at IS NULL` ∪ `guard_orphan_actions.status='OPEN'` 的去重租户。所有查询带 `tenant_id = :t`；端点租户取自 `RequestIdentityResolver`，跨租户一律 404 空体（ADR 0040 §3.4）。

**D4 调度、窗口与回填。** `aster.guard.reconciliation.cron=${ASTER_GUARD_RECONCILIATION_CRON:0 */5 * * * ?}`（每 5 分钟）；账本类按 `[since, cutoff)` 滑动窗口重扫，`lookback=${ASTER_GUARD_RECONCILIATION_LOOKBACK:P7D}`——首次上线即回填 7 天，停机不超过 7 天无遗漏，窗口重叠由 D5 吸收；上报类不按时间窗，处理全部 `reconciled_at IS NULL` 的行（每租户每轮至多 `batch-limit=${ASTER_GUARD_RECONCILIATION_BATCH_LIMIT:5000}` 条，按 `id` 升序；超出留给下一轮并 warn）。上报不需要等宽限：协议上 consume 先于执行先于上报。

**D5 幂等与多副本。** 每个副本都跑调度；每租户在一个 `QuarkusTransaction.requiringNew()` 里先 `SELECT pg_try_advisory_xact_lock(hashtextextended('guard-reconcile:' || :t, 0))`，拿不到即跳过（计 `skipped_locked`）；拿到后若该租户最近一次 run 的 `finished_at > now − min-interval`（`${ASTER_GUARD_RECONCILIATION_MIN_INTERVAL:PT2M}`）也跳过（计 `skipped_recent`）。孤儿写入是 `INSERT … ON CONFLICT (tenant_id, kind, subject_key) DO NOTHING RETURNING`，同一事实永远一行；上报行的 `reconciled_at` 与孤儿行、run 行、链事件在**同一事务**提交（`AuditChainAppender.appendInCurrentTransaction`），任一步失败整租户回滚、下轮重来。

**D6 指标（低基数）。**
- `guard_orphan_actions{tenant,executor,kind}`（Micrometer `guard.orphan.actions`，MultiGauge）= 当前 `status='OPEN'` 行数，**只发非零序列**；每个副本每 60 s 从库里重读（所有副本报同值，告警用 `max`）。`kind` 7 值；`executor` 由 `ExecutorLabels.of` 归一：取 `executorId`（或上报 `source`）`@` 之前的组件名，小写，在白名单 `aster.guard.reconciliation.executor-labels=${ASTER_GUARD_RECONCILIATION_EXECUTOR_LABELS:aster-guard-mcp,aster-guard-sdk,aster-cloud-exec}` 内取原值，否则 `other`，空为 `unknown`；`tenant` 至多 `metric-max-tenants=${ASTER_GUARD_RECONCILIATION_METRIC_MAX_TENANTS:100}` 个（按 OPEN 总数降序），其余合并为 `tenant="_other"`。原始 `executorId` 只进行与 `detail`，不进标签。
- `guard_orphan_actions_detected_total{kind}`（Counter `guard.orphan.actions.detected`）：新孤儿数，只由跑出它的副本加，`sum` 正确。
- `guard_reconciliation_runs_total{outcome}`（Counter `guard.reconciliation.runs`，`outcome ∈ ok | failed | skipped_locked | skipped_recent`）：按租户计。
- `guard_reconciliation_last_success_seconds`（Gauge `guard.reconciliation.last.success`，`baseUnit("seconds")`）：本副本最近一次完整跑完租户循环（含无租户、含跳过）的 epoch 秒；进程内值，告警取 `max`。

名称按 #465 原文 `guard_orphan_actions`，不加 `aster.` 前缀（与 `GuardMetrics.RULE_METADATA_UNAVAILABLE` 不同，有意为之）。

**D7 告警挂钩（k3s `apps/aster-lang/observability/prometheus-rule.yaml`，Task 10，独立 PR 由用户合并）：** `AsterGuardBypassSuspected`（critical kind 任一 > 0，`for: 0m`）、`AsterGuardExecutionLost`（`consumed_without_result` ≥ 5 持续 30 m，warning）、`AsterGuardReconciliationStale`（`time() − max(last_success) > 1800` 持续 10 m，warning）。`allow_unconsumed` 不告警，只在 cloud 治理页展示。

**D8 审计事件。** `EventType.GUARD_RECONCILIATION`：某租户一次 run 有新孤儿或有解除时写一条（`performedBy=SYSTEM`，`success=true`——「本次对账已记录」的既有语义），metadata `runId, windowStart, windowEnd, newOrphans, resolvedOrphans, openOrphans, newByKind{kind:n}, newOrphanIds[≤64]`（超出写 `newOrphansTruncated: true`）；无变化的 run 只写 run 行不入链（每租户每 5 分钟一条空事件会淹没链）。`EventType.GUARD_ORPHAN_ACKNOWLEDGED`：管理员确认一条孤儿，metadata `orphanId, kind, decisionId?, sideEffectId?, comment`。

**D9 保留期。** 每日 `aster.guard.reconciliation.retention-cron=${ASTER_GUARD_RECONCILIATION_RETENTION_CRON:0 30 3 * * ?}`：删 `reconciled_at < now − P90D` 的上报行（孤儿行 `side_effect_id` 外键 `ON DELETE SET NULL`，`detail` 已复制上报关键字段，孤儿仍自描述）；删 `status <> 'OPEN' AND coalesce(resolved_at, detected_at) < now − P180D` 的孤儿行；删 `finished_at < now − P30D` 的 run 行。**OPEN 孤儿永不删除**；链事件不可删（本就不可变）。三个期限各为配置键（Global Constraints）。

---

## Global Constraints

- **迁移编号**：`V6.38.0__guard_reconciliation.sql`。`V6.37.0` 已由 #460（ADR 0042 §11 R59）预留；生产 `quarkus.flyway.out-of-order=false`，所以**本计划的 PR 不得早于 #460 合并**，除非用户裁定互换编号（见「待用户裁定」第 1 条）。在 `src/test/resources/db/migration-checksums.golden` 追加 `V6.38.0__guard_reconciliation.sql=<sha256>`（`shasum -a 256`，LF）。
- **配置键（全部写入 `src/main/resources/application.properties` 的 guard 段，紧随 `aster.guard.result-grace`）**：
  `aster.guard.reconciliation.cron=${ASTER_GUARD_RECONCILIATION_CRON:0 */5 * * * ?}`、
  `aster.guard.reconciliation.lookback=${ASTER_GUARD_RECONCILIATION_LOOKBACK:P7D}`、
  `aster.guard.reconciliation.min-interval=${ASTER_GUARD_RECONCILIATION_MIN_INTERVAL:PT2M}`、
  `aster.guard.reconciliation.batch-limit=${ASTER_GUARD_RECONCILIATION_BATCH_LIMIT:5000}`、
  `aster.guard.reconciliation.allow-unconsumed-ttl=${ASTER_GUARD_RECONCILIATION_ALLOW_UNCONSUMED_TTL:P7D}`、
  `aster.guard.reconciliation.executor-labels=${ASTER_GUARD_RECONCILIATION_EXECUTOR_LABELS:aster-guard-mcp,aster-guard-sdk,aster-cloud-exec}`、
  `aster.guard.reconciliation.metric-max-tenants=${ASTER_GUARD_RECONCILIATION_METRIC_MAX_TENANTS:100}`、
  `aster.guard.reconciliation.metric-refresh=${ASTER_GUARD_RECONCILIATION_METRIC_REFRESH:60s}`、
  `aster.guard.reconciliation.retention-cron=${ASTER_GUARD_RECONCILIATION_RETENTION_CRON:0 30 3 * * ?}`、
  `aster.guard.reconciliation.side-effect-retention=${ASTER_GUARD_RECONCILIATION_SIDE_EFFECT_RETENTION:P90D}`、
  `aster.guard.reconciliation.orphan-retention=${ASTER_GUARD_RECONCILIATION_ORPHAN_RETENTION:P180D}`、
  `aster.guard.reconciliation.run-retention=${ASTER_GUARD_RECONCILIATION_RUN_RETENTION:P30D}`。
  宽限沿用既有 `aster.guard.result-grace`（`PT15M`），不另设。
- **kind 字面量**（Java 枚举 `OrphanKind` 的 `wire()` 值、DB CHECK、指标标签、REST 字段、告警正则五处一致）：`side_effect_without_decision, side_effect_unconsumed, side_effect_mismatch, consumed_without_result, allow_unconsumed, consume_on_non_allow, result_without_consume`。severity：`critical`（`side_effect_*`、`consume_on_non_allow`、`result_without_consume`）/ `warning`（`consumed_without_result`）/ `info`（`allow_unconsumed`）。
- **状态与解除**：`status ∈ OPEN | RESOLVED | ACKNOWLEDGED`；`resolution ∈ late_result | aged_out | acknowledged`。
- **上报端点**：`POST /api/v1/guard/side-effects`，请求 `{ "effects": [ { "effectId", "source", "occurredAt", "action": { "name" }, "resource"?: { "type", "id" }, "decisionId"?, "attemptId"?, "actionHash"? } ] }`；1–500 条；`effectId` 1–128 字符无控制字符；`source` 匹配 `[a-z0-9][a-z0-9._-]{0,63}`；`occurredAt` ISO-8601 且 `≤ now + PT5M`；`action.name` 1–255；`resource.type/id` 各 ≤ 255；`decisionId` / `attemptId` 为 UUID 形态（同 `GuardReceiptService.UUID_SHAPE`）；`actionHash` 64 位小写 hex。任一条不合法 → 400 `{ "error": "invalid_side_effect", "index": <i>, "field": "<name>" }`，整批不写。重复键 / 非法 JSON → 400 `invalid_side_effect`（无 index）。幂等键 `(tenant_id, source, effect_id)`：重复上报计入 `duplicates`、不改原行。200 `{ "accepted": n, "duplicates": m }`。不计量配额（它是账本输入，不含评估）。
- **查询 / 确认端点**：`GET /api/v1/guard/reconciliation`（摘要）、`GET /api/v1/guard/reconciliation/orphans`（列表）、`POST /api/v1/guard/reconciliation/orphans/{id}/ack`（`@RequireRole(Role.ADMIN)`）；形状见 Task 8。
- **鉴权通道**：新路径都在 `/api/v1/guard/*`，`ApiKeyAuthFilter.shouldProtect` 已覆盖；cloud 走 HMAC，SDK / 工具服务 / 业务系统走 Bearer——加入 `InternalCallerFilter.isGuardDualChannel` 与 `ApiKeyAuthFilter.HMAC_BYPASS_PATHS`（`/api/v1/guard/side-effects`、`/api/v1/guard/reconciliation`）/ `HMAC_BYPASS_PREFIXES`（`/api/v1/guard/reconciliation/`）。
- **时间**：服务注入 `java.time.Clock`（已有 `GuardClockProducer`）；对账入口签名带显式 `Instant now`，调度传 `clock.instant()`，测试直接传未来时刻——不新增 `Clock.system*` / `UUID.randomUUID` 使用点（三张表主键用 `BIGSERIAL`），`NonDeterminismSourceTest` 白名单不变。
- **事件类型名**（≤ 50 字符）：`GUARD_RECONCILIATION`、`GUARD_ORPHAN_ACKNOWLEDGED`；不新增身份键（`decisionId` 已在 `IDENTITY_METADATA_KEYS`；`orphanId / runId / sideEffectId` 是整数，不会被卡号正则误伤——`AuditIdentifierRedactionTest` 加一条断言锁住）。
- 错误体沿用 guard 形状 `{ "error": "<code>", ... }`。新路径进 `public-api-contract.golden`。`REST_API.md` §5 增「对账」小节；`docs/metrics/MetricsCatalog.md` 增四个指标。
- 不新增依赖；不连生产库、不读 k8s Secret；k3s 改动是独立 PR，由用户审阅合并。
- 代码注释、提交信息简体中文，不写「修改说明」式注释；提交信息末尾空行后加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 描述末尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`，`Refs #465`。
- **本地测试**：podman 起 PG/Redis（端口避开 :55432 / :6379 的本地全栈），`DB_JDBC_URL=jdbc:postgresql://localhost:<pg>/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:<redis>`。每个任务 Step 4 后再跑 guard 套件：`./gradlew --offline :test --tests 'io.aster.guard.*' --tests 'io.aster.audit.*' --tests 'io.aster.security.apikey.*' --tests 'io.aster.api.PublicApiContractTest' --tests 'io.aster.db.MigrationGovernanceTest' --tests 'io.aster.api.workflow.NonDeterminismSourceTest' --tests 'io.aster.policy.common.AuditIdentifierRedactionTest'`；开 PR 前跑全量 `./gradlew --offline :replay:cleanTest :cleanTest :replay:test :test --continue`，已知基线失败（`PolicyGraphQLResourceTest.testBatchEvaluateLargeVolume`、`PrismaDatabaseE2ETest`）在 PR 注明。确认 `@QuarkusTest` 用例实际执行（测试 profile 下 `quarkus.scheduler.enabled=false`，调度方法由测试直接调用）。

## Review Focus

1. **多副本同时对账同一租户**：两个副本并发调 `reconcileTenant` → 恰一个写孤儿 / run / 链事件，另一个 `skipped_locked`；孤儿行不重复、链上只一条 `GUARD_RECONCILIATION`。测试在 Task 6（`concurrentReplicasReconcileTenantOnce`）。
2. **同一事实跨多轮**：窗口重叠的连续两轮对同一 `consumed_without_result` 只产生一行、一次 `detected_total` 增量、第二轮无链事件；结果补写后下一轮解除且写一条 `resolvedOrphans=1` 的事件。测试在 Task 6（`rerunIsIdempotentAndLateResultResolves`）。
3. **上报伪造他租户 decisionId**：租户 B 上报租户 A 的真实 decisionId → B 侧 `side_effect_without_decision`（不泄露 A 的存在，也不在 A 侧产生任何行）。测试在 Task 5（`foreignTenantDecisionIdIsWithoutDecision`）。
4. **审批转 ALLOW 后正常执行不得误报**：`REQUIRE_APPROVAL → APPROVED → consume → result → 上报` 对账为零；`outcome='REQUIRE_APPROVAL'` 但审批 `APPROVED` 的行不得判 `consume_on_non_allow`。测试在 Task 5（`approvedDecisionConsumeIsNotNonAllow`）。
5. **指标标签爆炸**：`executorId` 为 `my-bot@pod-7f9c:3`、上报 `source` 为任意租户自定义名 → 标签只出现 `other` / 白名单值；超过 `metric-max-tenants` 的租户合并进 `_other`，值等于被合并租户之和。测试在 Task 7（`labelsStayBounded`）。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `src/main/resources/db/migration/V6.38.0__guard_reconciliation.sql` | 三张表 + `guard_receipts` 未消耗过期部分索引 |
| `src/main/java/io/aster/guard/reconcile/OrphanKind.java` | kind 枚举：`wire()`、`severity()`、`autoResolvable()` |
| `src/main/java/io/aster/guard/reconcile/ExecutorLabels.java` | `executorId` / `source` → 低基数标签 |
| `src/main/java/io/aster/guard/reconcile/GuardSideEffectEntity.java` / `GuardOrphanActionEntity.java` / `GuardReconciliationRunEntity.java` | 三张表的 Panache 实体 |
| `src/main/java/io/aster/guard/reconcile/SideEffectReport.java` | 上报请求 record 与校验（纯函数） |
| `src/main/java/io/aster/guard/reconcile/SideEffectIngestService.java` | 批量幂等写入 |
| `src/main/java/io/aster/guard/reconcile/SideEffectClassifier.java` | 一条上报 + 其收据快照 → `Optional<OrphanKind>`（纯函数） |
| `src/main/java/io/aster/guard/reconcile/GuardOrphanDetector.java` | 单租户检测 SQL（账本四类 + 上报批次）与自动解除，只在调用方事务内执行 |
| `src/main/java/io/aster/guard/reconcile/GuardReconciliationService.java` | 租户枚举、按租户加锁事务、run 行、链事件、计数器 |
| `src/main/java/io/aster/guard/reconcile/GuardReconciliationScheduler.java` | 两个 `@Scheduled`：对账、保留期清理 |
| `src/main/java/io/aster/guard/reconcile/GuardOrphanMetrics.java` | MultiGauge 刷新、计数器、最近成功 Gauge |
| `src/main/java/io/aster/guard/api/GuardSideEffectResource.java` | `POST /api/v1/guard/side-effects` |
| `src/main/java/io/aster/guard/api/GuardReconciliationResource.java` | 摘要、列表、确认 |
| `src/main/java/io/aster/guard/GuardReceiptBuilder.java`（改） | `reconciliationEvent(...)`、`orphanAcknowledgedEvent(...)` |
| `src/main/java/io/aster/policy/event/EventType.java`（改） | 两个事件类型 |
| `src/main/java/io/aster/security/apikey/InternalCallerFilter.java` / `ApiKeyAuthFilter.java`（改） | 双通道路径 |
| `docs/adr/0054-guard-execution-point.md`（改） | §10 对账契约（R65） |
| k3s：`apps/aster-lang/observability/prometheus-rule.yaml`、`tests/guard-reconciliation-rules-test.yaml` | 告警与 promtool 测试（独立仓、独立 PR） |

---

### Task 1: ADR 0054 §10「对账契约（R65）」补记

把 D1–D9 中**外部可见**的契约写进 ADR，使 #464 / #466 / cloud 有单一引用来源；实现细节（SQL、类名）不进 ADR。

**Files:**
- Modify: `docs/adr/0054-guard-execution-point.md`（文末新增 §10；§5 末条「外部业务系统的批量上报端点是 #465 的范围」后加「→ §10」）

**Interfaces:**
- Produces: §10 中的上报请求 / 响应、kind 表（D2）、指标名与标签（D6）、`executorId` 组件名白名单（`aster-guard-mcp / aster-guard-sdk / aster-cloud-exec`）、查询端点形状（Task 8）。

- [ ] **Step 1:** 写 §10，状态行 `PROPOSED（2026-11-14；实施计划 docs/superpowers/plans/2026-11-14-guard-reconciliation.md）`；小节：10.1 事实来源（D1 表）、10.2 上报端点（Global Constraints 原文）、10.3 kind 表（D2 原文）、10.4 指标与告警（D6、D7）、10.5 #464 集成点（本计划「与 #464 的集成点」原文）。
- [ ] **Step 2:** 校验：`grep -c "side_effect_without_decision" docs/adr/0054-guard-execution-point.md` ≥ 1；`grep -n "→ §10" docs/adr/0054-guard-execution-point.md` 命中 §5。
- [ ] **Step 3:** 提交 `docs(adr): ADR 0054 §10 补记 Guard 对账契约（R65，#465）`。**此 PR 由用户审阅合并；状态转 ACCEPTED 前实现 PR 不合并。**

### Task 2: 迁移 `V6.38.0`、实体、`OrphanKind` 与 `ExecutorLabels`

**Files:**
- Create: `src/main/resources/db/migration/V6.38.0__guard_reconciliation.sql`
- Create: `src/main/java/io/aster/guard/reconcile/{OrphanKind,ExecutorLabels,GuardSideEffectEntity,GuardOrphanActionEntity,GuardReconciliationRunEntity}.java`
- Modify: `src/test/resources/db/migration-checksums.golden`、`src/main/resources/application.properties`（全部配置键）
- Test: `src/test/java/io/aster/guard/reconcile/OrphanKindTest.java`、`ExecutorLabelsTest.java`、`GuardReconcilePersistenceTest.java`

**Interfaces:**
- Produces:
  - `enum OrphanKind { SIDE_EFFECT_WITHOUT_DECISION, SIDE_EFFECT_UNCONSUMED, SIDE_EFFECT_MISMATCH, CONSUMED_WITHOUT_RESULT, ALLOW_UNCONSUMED, CONSUME_ON_NON_ALLOW, RESULT_WITHOUT_CONSUME; String wire(); String severity(); boolean autoResolvable(); static Optional<OrphanKind> fromWire(String) }`
  - `@ApplicationScoped class ExecutorLabels { String of(String executorIdOrSource) }`（读 `executor-labels`）
  - 实体字段与下列 DDL 列一一对应（Java 名为列名 camelCase）。

迁移（列与约束即契约；注释用中文写清每列含义）：

```sql
CREATE TABLE guard_side_effects (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    source VARCHAR(64) NOT NULL,
    effect_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    resource_type VARCHAR(255),
    resource_id VARCHAR(255),
    decision_id UUID,                -- 不加外键：上报方可能给出不存在或他租户的 id，正是要抓的
    attempt_id VARCHAR(64),
    action_hash VARCHAR(64),
    reported_by VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reconciled_at TIMESTAMPTZ,
    reconcile_outcome VARCHAR(40),   -- matched 或 kind
    CONSTRAINT guard_side_effects_uq UNIQUE (tenant_id, source, effect_id)
);
CREATE INDEX guard_side_effects_pending_ix ON guard_side_effects (tenant_id, id) WHERE reconciled_at IS NULL;
CREATE INDEX guard_side_effects_decision_ix ON guard_side_effects (tenant_id, decision_id) WHERE decision_id IS NOT NULL;

CREATE TABLE guard_reconciliation_runs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    side_effects_checked INT NOT NULL,
    new_orphans INT NOT NULL,
    resolved_orphans INT NOT NULL,
    open_orphans INT NOT NULL,
    new_by_kind JSONB NOT NULL,
    audit_id BIGINT
);
CREATE INDEX guard_reconciliation_runs_tenant_ix ON guard_reconciliation_runs (tenant_id, finished_at DESC);

CREATE TABLE guard_orphan_actions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    subject_key VARCHAR(80) NOT NULL,     -- 决策类为 decision_id 文本；上报类为 'se:' || side_effect id
    decision_id UUID,
    side_effect_id BIGINT REFERENCES guard_side_effects(id) ON DELETE SET NULL,
    executor_label VARCHAR(40) NOT NULL,
    executor_id VARCHAR(255),
    detail JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    first_run_id BIGINT NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolution VARCHAR(32),
    acknowledged_by VARCHAR(255),
    ack_comment VARCHAR(1000),
    ack_audit_id BIGINT,
    CONSTRAINT guard_orphan_actions_uq UNIQUE (tenant_id, kind, subject_key),
    CONSTRAINT guard_orphan_actions_kind_ck CHECK (kind IN ('side_effect_without_decision','side_effect_unconsumed',
        'side_effect_mismatch','consumed_without_result','allow_unconsumed','consume_on_non_allow','result_without_consume')),
    CONSTRAINT guard_orphan_actions_status_ck CHECK (status IN ('OPEN','RESOLVED','ACKNOWLEDGED')),
    CONSTRAINT guard_orphan_actions_resolution_ck CHECK (resolution IS NULL OR resolution IN ('late_result','aged_out','acknowledged'))
);
CREATE INDEX guard_orphan_actions_open_ix ON guard_orphan_actions (tenant_id, kind) WHERE status = 'OPEN';
CREATE INDEX guard_receipts_unconsumed_expiry_ix ON guard_receipts (tenant_id, expires_at) WHERE consumed_at IS NULL;
```

- [ ] **Step 1: 写失败测试**
  - `OrphanKindTest.wireValuesMatchSpec`：`Arrays.stream(values()).map(OrphanKind::wire)` 等于 Global Constraints 的 7 个字面量（同序）；`severity()` 按 Global Constraints；`autoResolvable()` 仅 `CONSUMED_WITHOUT_RESULT`、`ALLOW_UNCONSUMED` 为 true；`fromWire("nope")` 为 empty。
  - `ExecutorLabelsTest.normalizes`：`of("aster-guard-mcp@agent-host-1")=="aster-guard-mcp"`、`of("ASTER-CLOUD-EXEC@cloud-7f9c")=="aster-cloud-exec"`、`of("my-bot@pod-7f9c:3")=="other"`、`of("billing-db")=="other"`、`of(null)==of("")==of("  ")=="unknown"`、`of("aster-guard-sdk")=="aster-guard-sdk"`（无 `@`）。
  - `GuardReconcilePersistenceTest`（`@QuarkusTest`）：三个实体往返；重复 `(tenant_id, source, effect_id)` 抛约束异常；`kind='bogus'` 违反 CHECK；删除上报行后引用它的孤儿行 `side_effect_id` 变 null。
- [ ] **Step 2:** 运行 `./gradlew --offline :test --tests 'io.aster.guard.reconcile.*'`，预期编译失败 / FAIL。
- [ ] **Step 3:** 实现迁移、实体、枚举、`ExecutorLabels`；配置键全部入 `application.properties`；golden 追加 checksum。
- [ ] **Step 4:** 本任务测试 + `MigrationGovernanceTest` PASS；再跑 guard 套件。
- [ ] **Step 5:** 提交 `feat(guard): 对账三表迁移 V6.38.0 与孤儿类别枚举（#465）`。

### Task 3: 副作用上报端点 `POST /api/v1/guard/side-effects`

**Files:**
- Create: `src/main/java/io/aster/guard/reconcile/SideEffectReport.java`、`SideEffectIngestService.java`、`src/main/java/io/aster/guard/api/GuardSideEffectResource.java`
- Modify: `InternalCallerFilter.isGuardDualChannel`、`ApiKeyAuthFilter.HMAC_BYPASS_PATHS`、`src/test/resources/api/public-api-contract.golden`、`REST_API.md` §5
- Test: `src/test/java/io/aster/guard/reconcile/SideEffectReportTest.java`、`src/test/java/io/aster/guard/api/GuardSideEffectResourceTest.java`、`src/test/java/io/aster/security/apikey/ApiKeyAuthFilterShouldProtectTest.java`（加断言）

**Interfaces:**
- Consumes: `GuardSideEffectEntity`（Task 2）、`GuardRequestBodyReader`（重复键检测，既有）、`RequestIdentityResolver`。
- Produces:
  - `record SideEffectReport(List<Effect> effects)`，`record Effect(String effectId, String source, String occurredAt, ActionRef action, ResourceRef resource, String decisionId, String attemptId, String actionHash)`；`static void validate(SideEffectReport r, Instant now)` 抛 `InvalidSideEffectException(int index, String field)`（`index=-1` 表示整体）。
  - `SideEffectIngestService.ingest(String tenantId, String reportedBy, SideEffectReport r) → IngestResult(int accepted, int duplicates)`：单事务 `INSERT … ON CONFLICT (tenant_id, source, effect_id) DO NOTHING`，按返回行数计 accepted。

- [ ] **Step 1: 写失败测试**
  - `SideEffectReportTest`：每条规则一个用例，断言 `index` 与 `field`——空数组 / 501 条（`index=-1, field="effects"`）、`effectId` 含 `\n`、`source="Billing"`（大写不合法）、`occurredAt` 为 `now+PT6M`（`now+PT5M` 合法，边界）、`decisionId="123"`、`actionHash` 大写 hex、`action` 缺失；全部合法时不抛。
  - `GuardSideEffectResourceTest`（`X-Tenant-Id` / `X-User-Id` 头，同 `GuardReceiptResourceTest`）：
    - `acceptsBatchAndIsIdempotent`：两条 → `{accepted:2, duplicates:0}`；原样重发 → `{accepted:0, duplicates:2}`；库里 2 行，`reported_by` 为 `X-User-Id`。
    - `rejectsWholeBatchOnOneInvalid`：第 2 条 `source` 非法 → 400 `{error:"invalid_side_effect", index:1, field:"source"}`，库里 0 行。
    - `duplicateJsonKeyRejected`：`{"effects":[...],"effects":[...]}` → 400 `invalid_side_effect`。
    - `sameEffectIdDifferentTenantsAreDistinct`：两租户同 `(source, effectId)` 各写 1 行。
  - `ApiKeyAuthFilterShouldProtectTest`：`/api/v1/guard/side-effects` 受保护；带内部调用头时 `InternalCallerFilter` 分类为 `REQUIRE_HMAC`。
- [ ] **Step 2:** 运行，预期 FAIL。
- [ ] **Step 3:** 实现；资源类 `@Path("/api/v1/guard/side-effects")`、`@RequireRole(Role.MEMBER)`、阻塞签名（同 `GuardReceiptResource`）；`PublicApiContractTest` 跑一次追加 golden 行 `POST /api/v1/guard/side-effects`；`REST_API.md` §5 写请求 / 响应 / 错误码。
- [ ] **Step 4:** 本任务测试与 `PublicApiContractTest` PASS；guard 套件。
- [ ] **Step 5:** 提交 `feat(guard): 副作用批量上报端点（#465）`。

### Task 4: 上报分类器（纯函数）

**Files:**
- Create: `src/main/java/io/aster/guard/reconcile/SideEffectClassifier.java`
- Test: `src/test/java/io/aster/guard/reconcile/SideEffectClassifierTest.java`

**Interfaces:**
- Produces:
  - `record ReceiptSnapshot(boolean decisionExists, boolean hasReceipt, Instant consumedAt, String attemptId, String actionHash)`（决策在本租户不存在时 `decisionExists=false`，其余字段无意义）
  - `record Classification(Optional<OrphanKind> kind, String reason)`（`reason ∈ no_decision_id | unknown_decision | not_consumed | attempt_mismatch | action_mismatch | replayed | matched`）
  - `static Classification classify(GuardSideEffectEntity e, ReceiptSnapshot s, boolean earlierMatchedSameSource)`

判定顺序固定（第一条命中即返回）：`decisionId == null` → `WITHOUT_DECISION/no_decision_id`；`!decisionExists` → `WITHOUT_DECISION/unknown_decision`；`!hasReceipt || consumedAt == null` → `UNCONSUMED/not_consumed`；`e.attemptId != null && !e.attemptId.equals(s.attemptId)` → `MISMATCH/attempt_mismatch`；`e.actionHash != null && !e.actionHash.equals(s.actionHash)` → `MISMATCH/action_mismatch`；`earlierMatchedSameSource` → `MISMATCH/replayed`；否则 `empty/matched`。

- [ ] **Step 1: 写失败测试** `SideEffectClassifierTest`：上面 7 个分支各一例，断言 `kind` 与 `reason`；另 `attemptIdOmittedStillMatches`（上报不带 `attemptId / actionHash` 且已消耗 → matched）；`replayCheckedAfterMismatch`（`attemptId` 不符且 `earlierMatchedSameSource=true` → `attempt_mismatch`，不是 `replayed`）。
- [ ] **Step 2:** 运行，FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** PASS。
- [ ] **Step 5:** 提交 `feat(guard): 副作用上报分类器（#465）`。

### Task 5: 单租户检测器 `GuardOrphanDetector`

**Files:**
- Create: `src/main/java/io/aster/guard/reconcile/GuardOrphanDetector.java`
- Test: `src/test/java/io/aster/guard/reconcile/GuardOrphanDetectorTest.java`（`@QuarkusTest`，真实 PG）

**Interfaces:**
- Consumes: `SideEffectClassifier`（Task 4）、`ExecutorLabels`、实体（Task 2）、`EntityManager`。
- Produces（全部要求调用方已开事务；不自开事务、不入链）：
  - `record Window(Instant now, Instant since, Instant cutoff)`；`static Window of(Instant now, Duration lookback, Duration grace)`
  - `record Detected(long orphanId, OrphanKind kind, String executorLabel)`
  - `List<Detected> detectLedger(String tenantId, Window w, long runId)`：账本四类，每类一条 `INSERT INTO guard_orphan_actions (...) SELECT ... ON CONFLICT (tenant_id, kind, subject_key) DO NOTHING RETURNING id, kind, executor_label`，各带 `LIMIT :batchLimit`；`executor_label` 在 Java 侧算不了（INSERT…SELECT 内），因此 SQL 取 `split_part(lower(r.executor_id), '@', 1)` 后以 `CASE WHEN … = ANY(:labels) THEN … WHEN r.executor_id IS NULL OR btrim(r.executor_id) = '' THEN 'unknown' ELSE 'other' END` 归一——与 `ExecutorLabels.of` 同语义，由 `executorLabelSqlMatchesJava` 测试锁定。`detail` 写 `{decisionId, executorId?, attemptId?, consumedAt?, expiresAt?, outcome?, approvalStatus?}`。
  - `SideEffectBatch detectSideEffects(String tenantId, Window w, long runId)` → `record SideEffectBatch(int checked, List<Detected> detected)`：取 `reconciled_at IS NULL` 的上报（`ORDER BY id LIMIT :batchLimit`），一条 SQL 带出每条的 `ReceiptSnapshot`（`LEFT JOIN guard_decisions d ON d.id = s.decision_id AND d.tenant_id = s.tenant_id LEFT JOIN guard_receipts r ON r.decision_id = d.id AND r.tenant_id = s.tenant_id`）与 `earlierMatchedSameSource`（`EXISTS` 同租户、同 `decision_id`、同 `source`、`id < s.id`、`reconcile_outcome = 'matched'`）；**同批内**按 `id` 升序逐条分类，前一条判 matched 后对后一条即生效（批内维护 `Set<(decisionId, source)>`）；孤儿 `INSERT … ON CONFLICT DO NOTHING`（`subject_key = 'se:' || id`，`executor_label = ExecutorLabels.of(source)`，`detail` 复制 `effectId, source, actionName, resourceType, resourceId, occurredAt, decisionId, attemptId, reason`）；每条写回 `reconciled_at = w.now, reconcile_outcome = kind.wire() 或 'matched'`。
  - `int autoResolve(String tenantId, Window w, Duration allowUnconsumedTtl)`：`consumed_without_result` 且收据行 `result_status IS NOT NULL` → `RESOLVED / late_result`；`allow_unconsumed` 且 `detected_at < now − ttl` → `RESOLVED / aged_out`；返回解除行数。
  - `int countOpen(String tenantId)`

账本四类的 WHERE（`:t`、`:since`、`:cutoff`；`d` = `guard_decisions`，`a` = `guard_approvals`，`r` = `guard_receipts`）：

| kind | 条件 |
|---|---|
| `consumed_without_result` | `r.tenant_id=:t AND r.consumed_at >= :since AND r.consumed_at < :cutoff AND r.result_status IS NULL` |
| `allow_unconsumed` | `r.tenant_id=:t AND r.consumed_at IS NULL AND r.expires_at < :cutoff AND r.issued_at >= :since` |
| `consume_on_non_allow` | `r.tenant_id=:t AND r.consumed_at IS NOT NULL AND r.issued_at >= :since AND (d.id IS NULL OR d.tenant_id <> r.tenant_id OR NOT (d.outcome = 'ALLOW' OR a.status = 'APPROVED'))`，`LEFT JOIN guard_decisions d ON d.id = r.decision_id LEFT JOIN guard_approvals a ON a.decision_id = r.decision_id` |
| `result_without_consume` | `r.tenant_id=:t AND r.result_status IS NOT NULL AND r.consumed_at IS NULL AND r.issued_at >= :since` |

- [ ] **Step 1: 写失败测试**（造数用真实端点：`POST /decisions` → `consume` → `result`，同 `GuardReceiptResourceTest` 的策略部署夹具；篡改类用 `QuarkusTransaction.requiringNew()` 直接改行；每个用例新建租户；`now` 显式传 `Instant.now().plus(grace).plusSeconds(60)`）
  - `consumedWithoutResultPastGrace`：消耗未回写 → 1 行 `consumed_without_result`，`executor_label="aster-guard-mcp"`（`executorId="aster-guard-mcp@h1"`）；同一数据以 `now = consumed_at + grace − 1s` 跑 → 0 行（宽限边界：`cutoff` 排他）。
  - `allowUnconsumedAfterExpiry`：ALLOW 未 consume、`now` 越过 `expires_at + grace` → 1 行 `allow_unconsumed`，`executor_label="unknown"`。
  - `consumeOnDenyIsCritical`：DENY 决策手工插入已消耗收据行 → `consume_on_non_allow`，`severity="critical"`。
  - `approvedDecisionConsumeIsNotNonAllow`（Review Focus 4）：`REQUIRE_APPROVAL → approve → consume → result` → 账本四类 0 行。
  - `resultWithoutConsume`：手工把行改为 `result_status='executed', consumed_at=NULL` → 1 行。
  - `sideEffectsClassifiedAndMarked`：同批上报 5 条——无 decisionId、DENY 决策 id、已消耗且 attempt 正确、同一决策同一 source 第二条、已消耗但 `actionHash` 错 → 依次 `without_decision / unconsumed / matched / mismatch(replayed) / mismatch(action_mismatch)`；5 条 `reconciled_at` 均非空，`reconcile_outcome` 对应。
  - `foreignTenantDecisionIdIsWithoutDecision`（Review Focus 3）：B 上报 A 的已消耗 decisionId → B 侧 `side_effect_without_decision/unknown_decision`；A 租户孤儿表 0 行。
  - `detectionIsIdempotent`：同一 `Window` 跑两次 → 第二次 `detectLedger` 返回空，表内行数不变；上报第二次 `checked=0`。
  - `lateResultAutoResolves`：先检出 `consumed_without_result`，再回写结果，`autoResolve` 返回 1，行 `RESOLVED / late_result`；`allow_unconsumed` 以 `now + ttl + 1s` 解除为 `aged_out`；`side_effect_*` 永不被 `autoResolve` 触及。
  - `executorLabelSqlMatchesJava`：对 `["aster-guard-mcp@h", "ASTER-GUARD-SDK@x:1", "my-bot@p", "", null, "aster-cloud-exec"]` 逐个造 `consumed_without_result`，断言 SQL 归一结果 == `ExecutorLabels.of(...)`。
- [ ] **Step 2:** 运行，FAIL。
- [ ] **Step 3:** 实现（`EntityManager.createNativeQuery`；`:labels` 以 `String[]` 绑定 `= ANY(CAST(:labels AS text[]))`）。
- [ ] **Step 4:** 本任务测试 PASS；guard 套件。
- [ ] **Step 5:** 提交 `feat(guard): 单租户孤儿检测器（账本四类 + 副作用上报，#465）`。

### Task 6: 对账服务、调度与链事件

**Files:**
- Create: `src/main/java/io/aster/guard/reconcile/GuardReconciliationService.java`、`GuardReconciliationScheduler.java`
- Modify: `src/main/java/io/aster/policy/event/EventType.java`（`GUARD_RECONCILIATION`）、`src/main/java/io/aster/guard/GuardReceiptBuilder.java`（`reconciliationEvent`）
- Test: `src/test/java/io/aster/guard/reconcile/GuardReconciliationServiceTest.java`（`@QuarkusTest`）、`src/test/java/io/aster/guard/GuardReceiptBuilderTest.java`（加用例）、`src/test/java/io/aster/policy/common/AuditIdentifierRedactionTest.java`（加用例）

**Interfaces:**
- Consumes: `GuardOrphanDetector`（Task 5）、`AuditChainAppender.appendInCurrentTransaction`、`GuardOrphanMetrics.recordDetected(OrphanKind)` / `recordRun(TenantOutcome)` / `markLoopSucceeded(Instant)`（Task 7 产出；本任务先以接口方法调用、Task 7 前用空实现的同名 bean，见 Step 3）。
- Produces:
  - `enum TenantOutcome { OK, FAILED, SKIPPED_LOCKED, SKIPPED_RECENT }`（`wire()` 小写，与 `guard_reconciliation_runs_total{outcome}` 一致）
  - `record TenantRun(TenantOutcome outcome, Long runId, int newOrphans, int resolved, int open, Long auditId)`
  - `GuardReconciliationService.reconcileTenant(String tenantId, Instant now) → TenantRun`（自开 `QuarkusTransaction.requiringNew()`；顺序：try-lock → 最近 run 检查 → 插 run 行占位取 `runId`（计数为 0、`finished_at = started_at = now`，末步回填） → `detectLedger` → `detectSideEffects` → `autoResolve` → `countOpen` → 有变化则 `appendInCurrentTransaction(reconciliationEvent(...))` → 回填 run 行计数与 `audit_id`；异常 → 回滚、`FAILED`、日志带租户）
  - `GuardReconciliationService.reconcileAll(Instant now) → List<TenantRun>`（租户枚举 D3；逐租户调用，单租户失败不影响后续；循环结束调 `markLoopSucceeded(now)`）
  - `List<String> candidateTenants(Instant since)`
  - `GuardReceiptBuilder.reconciliationEvent(String tenantId, long runId, Instant windowStart, Instant windowEnd, int newOrphans, int resolved, int open, Map<String,Integer> newByKind, List<Long> newOrphanIds) → AuditEvent`（`performedBy="SYSTEM"`，`success=true`，`newOrphanIds` 至多 64 项，超出加 `newOrphansTruncated:true`；走既有 `fitBudget`）
  - `GuardReconciliationScheduler.reconcile()`：`@Scheduled(cron = "${aster.guard.reconciliation.cron}", identity = "guard-reconciliation", concurrentExecution = SKIP, skipExecutionIf = BackgroundSchedulerSkipPredicate.class)`，不加 `@Transactional`，调用 `reconcileAll(clock.instant())`，有新孤儿时 `Log.warnf` 报每租户计数。

- [ ] **Step 1: 写失败测试**
  - `normalPathReconcilesToZero`：ALLOW → consume → result → 带 decisionId/attemptId/actionHash 上报 → `reconcileTenant` → `OK`、`newOrphans=0`、`open=0`、`auditId=null`（无变化不入链）、run 行 `side_effects_checked=1`。
  - `newOrphanWritesOneChainEvent`：注入无 decisionId 上报 → `newOrphans=1`、链上恰一条 `GUARD_RECONCILIATION`，metadata `newByKind={"side_effect_without_decision":1}`、`newOrphanIds` 含该行 id、`performed_by="SYSTEM"`；`verify-chain`（`AuditChainVerifier`）对该租户 valid。
  - `rerunIsIdempotentAndLateResultResolves`（Review Focus 2）：`consumed_without_result` 检出 → 以 `now + min-interval + 1s` 再跑：`newOrphans=0`、无新链事件；回写结果后再跑：`resolved=1`、一条 `resolvedOrphans=1` 的事件、`open=0`。
  - `concurrentReplicasReconcileTenantOnce`（Review Focus 1）：两个线程 `CountDownLatch` 同时 `reconcileTenant(t, now)` → 结果集合恰为 `{OK, SKIPPED_LOCKED}` 或 `{OK, SKIPPED_RECENT}`；孤儿行数 = 1；链事件 = 1。
  - `oneTenantFailureDoesNotStopOthers`：`QuarkusMock.installMockForType` 让 `GuardOrphanDetector.detectLedger` 对租户 A 抛异常 → A `FAILED` 且 A 无 run 行、上报 `reconciled_at` 仍为 null（整租户回滚）；B `OK`。
  - `candidateTenantsCoverThreeSources`：仅有旧 OPEN 孤儿的租户、仅有未对账上报的租户、仅有窗口内收据的租户都在候选内；窗口外且无上报无 OPEN 的租户不在。
  - `GuardReceiptBuilderTest.reconciliationEventShape`：键集与 64 项截断。
  - `AuditIdentifierRedactionTest.reconciliationIdsNotRedacted`：`newOrphanIds=[4111111111111111]` 形态的长整数原样入链（若被脱敏则把 `newOrphanIds`、`runId`、`orphanId`、`sideEffectId` 加入 `IDENTITY_METADATA_KEYS`，并在此测试写明原因）。
- [ ] **Step 2:** 运行，FAIL。
- [ ] **Step 3:** 实现。`GuardOrphanMetrics` 在本任务先建类骨架（三个方法 + 计数器，MultiGauge 留给 Task 7），使本任务可独立编译测试。
- [ ] **Step 4:** PASS；guard 套件（含 `NonDeterminismSourceTest`：确认无新增 `Clock.system` / `UUID.randomUUID` / `System.nanoTime` 命中）。
- [ ] **Step 5:** 提交 `feat(guard): 定时对账服务与 GUARD_RECONCILIATION 事件（#465）`。

### Task 7: 指标 `guard_orphan_actions` 与运行指标

**Files:**
- Modify: `src/main/java/io/aster/guard/reconcile/GuardOrphanMetrics.java`（补全）、`docs/metrics/MetricsCatalog.md`
- Test: `src/test/java/io/aster/guard/reconcile/GuardOrphanMetricsTest.java`（`@QuarkusTest`）

**Interfaces:**
- Consumes: `guard_orphan_actions` 表；`MeterRegistry`。
- Produces:
  - `record OpenCount(String tenant, String executor, String kind, long count)`；`static List<OpenCount> bound(List<OpenCount> rows, int maxTenants)`（纯函数：按租户 OPEN 总数降序、同数按租户名升序取前 `maxTenants` 个租户，其余行按 `(executor, kind)` 累加到 `tenant="_other"`）
  - `void refresh()`：`SELECT tenant_id, executor_label, kind, count(*) FROM guard_orphan_actions WHERE status='OPEN' GROUP BY 1,2,3` → `bound(...)` →`MultiGauge.register(rows, true)`（覆盖旧序列，已归零的序列消失）。`@Scheduled(every = "${aster.guard.reconciliation.metric-refresh}", identity = "guard-orphan-metrics", concurrentExecution = SKIP, skipExecutionIf = BackgroundSchedulerSkipPredicate.class)`。
  - `void recordDetected(OrphanKind k)`、`void recordRun(TenantOutcome o)`、`void markLoopSucceeded(Instant at)`（Gauge 读 `AtomicLong` epoch 秒）。

- [ ] **Step 1: 写失败测试**（`bound` 与计数器用纯单测 + `SimpleMeterRegistry`；读库用例只按本用例租户过滤断言——共享测试库里有其它用例的 OPEN 行）
  - `gaugeReflectsOpenRowsOnly`：插 2 OPEN `side_effect_without_decision`（`other`）+ 1 RESOLVED → `refresh()` 后 `registry.find("guard.orphan.actions").tags("tenant",t,"executor","other","kind","side_effect_without_decision").gauge().value()==2`；把两行改 ACKNOWLEDGED 后 `refresh()` → 该序列不存在。
  - `labelsStayBounded`（Review Focus 5）：`bound([a:1, b:2, c:3 条], 2)` → 只含 `c`、`b` 与 `_other`（值 1）；`bound` 输入两行同 `(executor, kind)` 的被合并租户 → `_other` 一行、值为和。读库用例：`executorId="my-bot@pod-7f9c:3"` 与上报 `source="tenant-crm"` 产生的孤儿，本租户序列的 `executor` 只为 `other`。
  - `countersAndLastSuccess`：`recordDetected(SIDE_EFFECT_MISMATCH)` 两次 → `guard.orphan.actions.detected{kind=side_effect_mismatch}` = 2；`markLoopSucceeded(Instant.ofEpochSecond(1_700_000_000))` → `guard.reconciliation.last.success` = 1.7e9。
  - `prometheusNames`：从 `/q/metrics` 抓取文本含 `guard_orphan_actions{`、`guard_orphan_actions_detected_total{`、`guard_reconciliation_runs_total{`、`guard_reconciliation_last_success_seconds`。
- [ ] **Step 2:** 运行，FAIL。
- [ ] **Step 3:** 实现；`MetricsCatalog.md` 新增「Guard 对账」节：四个指标的名称、类型、标签取值、多副本读法（`max` vs `sum`）。
- [ ] **Step 4:** PASS；guard 套件。
- [ ] **Step 5:** 提交 `feat(guard): guard_orphan_actions 指标与对账运行指标（#465）`。

### Task 8: 对账查询与确认端点

**Files:**
- Create: `src/main/java/io/aster/guard/api/GuardReconciliationResource.java`
- Modify: `EventType.java`（`GUARD_ORPHAN_ACKNOWLEDGED`）、`GuardReceiptBuilder.java`（`orphanAcknowledgedEvent`）、`InternalCallerFilter.isGuardDualChannel`、`ApiKeyAuthFilter.HMAC_BYPASS_PATHS / PREFIXES`、`public-api-contract.golden`、`REST_API.md` §5
- Test: `src/test/java/io/aster/guard/api/GuardReconciliationResourceTest.java`、`ApiKeyAuthFilterShouldProtectTest.java`（加断言）

**Interfaces:**
- Consumes: 实体（Task 2）、`GuardOrphanDetector.countOpen`、`AuditChainAppender.appendInCurrentTransaction`。
- Produces（类级 `@RequireRole(Role.MEMBER)`，`ack` 方法级 `@RequireRole(Role.ADMIN)`；租户取 `RequestIdentityResolver`）：
  - `GET /api/v1/guard/reconciliation` → `{ "lastRun": { "runId", "finishedAt", "windowStart", "windowEnd", "newOrphans", "resolvedOrphans", "openOrphans" } | null, "open": { "<kind>": n }（只列非零）, "openTotal": n, "resultGrace": "PT15M", "lookback": "P7D" }`
  - `GET /api/v1/guard/reconciliation/orphans?status=OPEN|RESOLVED|ACKNOWLEDGED&kind=<wire>&limit=1..200(默认 50)&before=<id>` → `{ "items": [ { "id", "kind", "severity", "status", "decisionId"?, "sideEffectId"?, "executor", "executorId"?, "detectedAt", "resolvedAt"?, "resolution"?, "acknowledgedBy"?, "detail" } ], "next": <id>? }`（`id` 降序；`status` 缺省 `OPEN`；非法 `status / kind / limit` → 400 `invalid_query`）
  - `POST /api/v1/guard/reconciliation/orphans/{id}/ack`，体 `{ "comment": "<1–1000 字符>" }` → 200 同列表项形状；非数字 id / 他租户 / 不存在 → 404 空体；非 `OPEN` → 409 `{ "error": "not_open", "status": "<当前>" }`；`comment` 不合法 → 400 `invalid_ack`。单事务：`SELECT … FOR UPDATE` → `appendInCurrentTransaction(orphanAcknowledgedEvent)` → 写 `status='ACKNOWLEDGED', resolution='acknowledged', resolved_at, acknowledged_by, ack_comment, ack_audit_id`。

- [ ] **Step 1: 写失败测试** `GuardReconciliationResourceTest`：
  - `summaryBeforeAnyRunIsEmpty`：`lastRun=null, openTotal=0, open={}`。
  - `summaryAfterRun`：跑一次带 1 个孤儿的对账 → `lastRun.newOrphans=1`、`open={"side_effect_without_decision":1}`。
  - `listFiltersAndPages`：3 条 OPEN → `limit=2` 返回 2 条与 `next`，`before=next` 返回第 3 条；`kind=bogus` → 400 `invalid_query`。
  - `ackRequiresAdminAndIsOnce`：`X-User-Role: MEMBER` → 403；ADMIN → 200、链上一条 `GUARD_ORPHAN_ACKNOWLEDGED`（metadata `orphanId`、`kind`、`comment`）；再次 → 409 `not_open`；之后 `GuardOrphanMetrics.refresh()` 该序列消失。
  - `crossTenantIs404`：租户 B 读 / 确认 A 的孤儿 → 404 空体。
  - `ApiKeyAuthFilterShouldProtectTest`：两条新路径受保护且属双通道。
- [ ] **Step 2:** 运行，FAIL。
- [ ] **Step 3:** 实现；golden 追加三行；`REST_API.md` §5「对账」小节写三个端点与错误码。
- [ ] **Step 4:** PASS；guard 套件。
- [ ] **Step 5:** 提交 `feat(guard): 对账摘要 / 孤儿列表 / 管理员确认端点（#465）`。

### Task 9: 保留期清理

**Files:**
- Modify: `GuardReconciliationService.java`（`purgeExpired`）、`GuardReconciliationScheduler.java`（`retention()`）
- Test: `GuardReconciliationServiceTest.java`（加用例）

**Interfaces:**
- Produces: `record PurgeResult(int sideEffects, int orphans, int runs)`；`PurgeResult purgeExpired(Instant now)`（三条 `DELETE`，各自 `requiringNew` 事务、各 `LIMIT` 经 `ctid IN (SELECT ctid … LIMIT 10000)` 分批循环至 0）；调度 `@Scheduled(cron = "${aster.guard.reconciliation.retention-cron}", identity = "guard-reconciliation-retention", concurrentExecution = SKIP, skipExecutionIf = BackgroundSchedulerSkipPredicate.class)`，有删除时 `Log.infof` 报三项实际删除数。

- [ ] **Step 1: 写失败测试** `purgeRespectsRetentionAndKeepsOpen`：造 `reconciled_at = now − P91D` 的上报（被一条 OPEN 孤儿引用）、`now − P89D` 的上报、`resolved_at = now − P181D` 的 RESOLVED 孤儿、`detected_at = now − P400D` 的 OPEN 孤儿、`finished_at = now − P31D` 的 run → 结果 `(1, 1, 1)`；OPEN 孤儿仍在且 `side_effect_id` 为 null、`detail.effectId` 仍在；未对账（`reconciled_at IS NULL`）的上报无论多旧都不删。
- [ ] **Step 2:** 运行，FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** PASS；guard 套件。
- [ ] **Step 5:** 提交 `feat(guard): 对账数据保留期清理（#465）`。

### Task 10: 告警规则（k3s 仓，独立 PR）

**Files（仓 `/Users/rpang/IdeaProjects/k3s`，新分支 `feat/guard-reconciliation-alerts`）:**
- Modify: `apps/aster-lang/observability/prometheus-rule.yaml`（新 group `aster.guard.reconciliation`）
- Create: `apps/aster-lang/observability/tests/guard-reconciliation-rules-test.yaml`

**Interfaces:**
- Consumes: Task 7 的指标名与标签。

规则（`interval: 1m`；`labels.team: backend`）：

```yaml
- alert: AsterGuardBypassSuspected
  expr: max by (tenant, kind) (guard_orphan_actions{kind=~"side_effect_without_decision|side_effect_unconsumed|side_effect_mismatch|consume_on_non_allow|result_without_consume"}) > 0
  for: 0m
  labels: { severity: critical }
- alert: AsterGuardExecutionLost
  expr: max by (tenant) (guard_orphan_actions{kind="consumed_without_result"}) >= 5
  for: 30m
  labels: { severity: warning }
- alert: AsterGuardReconciliationStale
  expr: time() - max(guard_reconciliation_last_success_seconds) > 1800
  for: 10m
  labels: { severity: warning }
```

annotations 用中文写 summary / description，description 指向 `GET /api/v1/guard/reconciliation/orphans` 与 cloud 治理页。

- [ ] **Step 1: 写失败测试** `guard-reconciliation-rules-test.yaml`（格式同 `sla-rules-test.yaml`）：① 两个 pod 同报 `guard_orphan_actions{tenant="t1",kind="side_effect_without_decision",executor="other"} 1` → 只产生**一条** `AsterGuardBypassSuspected`（`max by` 去重）；② `allow_unconsumed` 为 50 → 无告警；③ `consumed_without_result` 为 5 持续 29 m 无告警、31 m 有；④ `last_success` 停在 0、`time()` 推进 41 m → `Stale` 触发，一个 pod 正常推进则不触发。
- [ ] **Step 2:** 按 `sla-rules-test.yaml` 头注释的命令生成 `.tmp-rules.yaml` 后 `promtool test rules apps/aster-lang/observability/tests/guard-reconciliation-rules-test.yaml`，预期 FAIL（规则不存在）。
- [ ] **Step 3:** 写规则。
- [ ] **Step 4:** 同命令 PASS；`rm .tmp-rules.yaml`。
- [ ] **Step 5:** 提交 `feat(observability): Guard 对账告警规则（aster-api#465）`；推送、开 PR，**等 aster-api 实现部署后由用户合并**（指标未上线前规则无序列，不会误报，但 `Stale` 会——见 PR 描述）。

### Task 11: 验收测试与全量回归

**Files:**
- Create: `src/test/java/io/aster/guard/reconcile/GuardReconciliationAcceptanceTest.java`（`@QuarkusTest`，只经 REST 与 `reconcileAll` 入口）

**Interfaces:**
- Consumes: 全部前序任务的公开端点、`GuardReconciliationService.reconcileAll`、`GuardOrphanMetrics.refresh`、`/q/metrics`。

- [ ] **Step 1: 写测试**（#465 验收原文两条 + ADR 0054 §5 三类）
  - `injectedBypassProducesOrphanAndMetric`：租户 T 先跑一次正常路径（决策 → consume（`executorId="aster-guard-mcp@e2e"`）→ result → 带 decisionId/attemptId/actionHash 上报 `source="aster-guard-mcp"`）；再**注入绕过**：上报一条 `source="aster-guard-mcp"`、无 decisionId 的副作用（模拟工具在未经 Guard 的情况下落地）→ `reconcileAll(now)` → `GET /reconciliation/orphans` 恰 1 条 `side_effect_without_decision`；`refresh()` 后 `/q/metrics` 含 `guard_orphan_actions{executor="aster-guard-mcp",kind="side_effect_without_decision",tenant="T"} 1.0`；链上一条 `GUARD_RECONCILIATION`。
  - `normalPathReconcilesToZero`：另一租户 U 只跑正常路径（含 `REQUIRE_APPROVAL → approve` 一条）→ `GET /reconciliation` `openTotal=0`；`/q/metrics` 无 `tenant="U"` 的 `guard_orphan_actions` 序列；U 无 `GUARD_RECONCILIATION` 事件。
  - `adr0054ThreeAnomalies`：同一租户 V：消耗未回写（`now` 越过宽限）→ `consumed_without_result`；DENY 决策被直接改行为已消耗 → `consume_on_non_allow`；上报指向 DENY 决策 → `side_effect_unconsumed`；三者 severity 分别 `warning / critical / critical`。
- [ ] **Step 2:** 运行本测试，PASS（前序任务已实现；若 FAIL，回到对应任务修复，不在此处补丁）。
- [ ] **Step 3:** 全量 `./gradlew --offline :replay:cleanTest :cleanTest :replay:test :test --continue`；记录执行用例数与基线失败。
- [ ] **Step 4:** 提交 `test(guard): 对账验收——注入绕过产生孤儿与指标，正常路径为零（#465）`；开 PR（`Refs #465`），PR 描述含：新配置键表、指标与告警、k3s PR 链接、「与 #464 的集成点」、「待用户裁定」。

---

## 与 #464（aster-guard 执行点，计划并行编写于 `docs/plan-464-guard-execution-point`）的集成点

本计划不依赖 #464 的任何交付即可实现与验收；下列是双方需对齐的接口（写入 ADR 0054 §10.5，Task 1）：

- **I1 `executorId` 组件名**：代理用 `aster-guard-mcp@<host>[:<inst>]`，SDK `run` 缺省用 `aster-guard-sdk@<host>`（ADR 0054 §5 形态），cloud 执行路径用 `aster-cloud-exec@<pod>`——三者正是 `executor-labels` 缺省白名单；改名须同时改 `ASTER_GUARD_RECONCILIATION_EXECUTOR_LABELS`。
- **I2 工具侧上报**：ADR 0054 §3.3 `auth` 隔离模式的工具在核对 consume 票据后落地副作用，是本计划上报端点的首选上报方——建议 #464 在 SDK 中提供 `reportSideEffects(effects[])`（同一 API key，`source` = 工具名），并在 README 的「工具侧接入」写明「执行后上报，带票据里的 `decisionId / attemptId`」。不提供该助手时工具可直接调 REST。
- **I3 结果回写解除「执行侧丢失」**：代理 / SDK 的 `reportResult` 失败只记日志（ADR 0054 §2.1 第 6 步）→ 对账会在宽限后报 `consumed_without_result`，补写后自动解除；consume 响应丢失时报 `aborted`（ADR 0040 §12 D5）不会形成孤儿。
- **I4 #466 部署层**：绕过套件第 1 条（直连原始工具）若工具侧照常上报，下一轮对账必须出现 `side_effect_without_decision`——这是「对账是第二道」（ADR 0054 §7 末段）的端到端证据，由 #466 断言。
- **I5 旧代理 / 旧 SDK**：0.1.0 不 consume → 它放行的每个 ALLOW 都会成为 `allow_unconsumed`（info，不告警），cloud 治理页据此列出「未消耗 ALLOW」（ADR 0040 §12 D10）。

## 不在本计划（后续，需另开 issue）

- aster-cloud 治理页「最近对账摘要」与孤儿列表 / 确认 UI（消费 Task 8 端点，HMAC 通道）。
- aster-cloud from-evidence 执行路径 consume / 回写（ADR 0040 §12.4 ④）与 cloud 作为上报方。
- workflow runner 接入 Guard（今天不接，无执行记录可对）。
- 「已消耗但从未上报副作用」类别：只对**确定会上报**的执行方有意义，需要按执行方登记上报承诺，等首个上报方上线后再定。

## 待用户裁定（已选默认，改动影响见右列）

| # | 项 | 已选默认 | 可改为 | 影响 |
|---|---|---|---|---|
| 1 | 迁移编号与 #460 先后 | `V6.38.0`，PR 等 #460（`V6.37.0`）先合并 | 本计划取 `V6.37.0`、#460 顺延 `V6.38.0` | 需改 #460 计划与 ADR 0042 三处引用 |
| 2 | 指标 `tenant` 标签 | 保留（#465 原文），上限 100 + `_other` | 去掉 `tenant`，租户维度只在 REST 摘要 | 告警无法按租户路由 |
| 3 | `allow_unconsumed` | 记为孤儿（info，不告警，`P7D` 后 `aged_out`） | 只计入 run 统计、不落孤儿行 | 治理页无法逐条列「未消耗 ALLOW」；cloud ④ 上线前所有 from-evidence ALLOW 都会出现在此类 |
| 4 | 无变化的 run 是否入链 | 不入链，只写 run 行 | 每租户每轮一条事件 | 链长度每租户每天 +288 |
| 5 | 上报端点配额 | 不计量 | 按 `/decisions` 口径计量 | 上报方会消耗租户配额 |
| 6 | `AsterGuardExecutionLost` 阈值 | ≥ 5 持续 30 m | 任一 > 0 | 单次网络抖动即告警 |
| 7 | 上报 `source` 是否进标签 | 只有白名单内的名字进 `executor` 标签，其余 `other` | 每租户可登记自己的 source 白名单 | 多一张配置表 |
| 8 | ADR 形式 | ADR 0054 §10 补记（R65） | 独立 ADR 0056 | 无功能差异 |
