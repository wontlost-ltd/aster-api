# Guard 对账治理页（孤儿动作）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 aster-cloud 治理页按租户列出 Guard 对账的孤儿动作（按 kind / severity 过滤），管理员带说明确认（写 `GUARD_ORPHAN_ACKNOWLEDGED`），每条链到它的决策与收据；en / zh / de / hi 文案。

**Architecture:** aster-api 只做一处向后兼容的查询扩展（`severity` 过滤、列表项 `ackComment`），无迁移。cloud 新增 `src/lib/guard-reconciliation/`（kind 表与查询解析可在客户端用；访问判定、签真实角色的客户端工厂、跨租户摘要、决策上下文只在服务端用）、四个 BFF 路由，以及 `/governance` 的摘要卡与新页 `/governance/reconciliation`。cloud 不存任何对账状态，aster-api 是唯一真相源。

**Tech Stack:** aster-api：Quarkus、JAX-RS、Panache、JUnit（`@QuarkusTest`）、Gradle。cloud：Next.js（Cloudflare Workers）、Drizzle + Postgres、vitest（unit / integration PG）、React Testing Library、Playwright、next-intl。

**Spec:** `docs/adr/0061-guard-orphan-governance-ui.md`（PROPOSED 2026-11-20）；底层契约 ADR 0054 §10。对应 wontlost-ltd/aster-cloud#769（aster-api#465 的跟进）。

## 现状（2026-11-20 核对：aster-api `f34e8584`、aster-cloud `6860eaac`、aster-guard `3d48f86`）

见 ADR 0061 §1 的实证表；计划额外依赖的事实：

| 事实 | 出处 |
|---|---|
| 列表解析在 `GuardReconciliationResource.parse(status, kind, limit, before)`；查询在 `GuardOrphanAdminService.list`（`where` 拼接 + `id desc` + `limit + 1`） | aster-api `GuardReconciliationResource.java:122-152`；`GuardOrphanAdminService.java:68-87` |
| 对外文档 `REST_API.md` 写有三个对账端点 | aster-api `REST_API.md:93-95` |
| 现有资源测试 `GuardReconciliationResourceTest`（`listFiltersAndPages`、`ackIsOnceAndChained`、`ackValidatesComment`、`crossTenantIs404`） | aster-api `src/test/java/io/aster/guard/reconcile/GuardReconciliationResourceTest.java:53-162` |
| `public-api-contract.golden` 只记方法与路径，本计划不改路径 → 不变 | aster-api `src/test/resources/api/public-api-contract.golden:1-3` |
| cloud guard 端点表 `API_ENDPOINTS`；guard 客户端超时 `GUARD_TIMEOUT_MS = 8000`；路径 id 校验 `assertGuardPathId` | cloud `src/config/api-versions.ts:41-63`；`src/services/policy/policy-api.ts:486,492-496` |
| 签发方 BFF 的鉴权与上游错误映射模式（`sessionUserId / unauthorized / forbidden / upstreamErrorResponse`，502 带 requestId） | cloud `src/app/api/fact-issuers/_shared.ts` |
| 上游错误码英文口径表 | cloud `src/lib/upstream-error-messages.ts:9-30` |
| 成员资料批量解析（只解析本租户成员） | cloud `src/lib/user-profiles.ts:35` |
| 跨租户聚合与限流器模式（并发 4） | cloud `src/lib/approvals-inbox.ts:21,74-89`；`src/services/evidence/receipts-client.ts`（`createLimiter`） |
| 时间以 UTC 固定格式渲染以避免水合不一致 | cloud `src/app/[locale]/(dashboard)/approvals/approvals-content.tsx:44-51` |
| 治理页文案在 `governancePage` 命名空间（四种语言各一块） | cloud `src/i18n/demo-supplement.ts:764,2980,5174,7365` |
| Guard 文档 how-to 只有 en / zh / de | cloud `src/app/[locale]/docs/how-to/guard/{en,zh,de}.mdx` |
| 本地栈 e2e 有 Guard 夹具（租户 `credit-pilot`、`CP_API_KEY`、`apiPost`、`psql`） | cloud `src/__tests__/e2e-browser/local-stack/guard-bypass.spec.ts:1-40`、`helpers.ts` |
| 对账调度 cron 可由 `ASTER_GUARD_RECONCILIATION_CRON` 覆盖；`min-interval` 缺省 `PT2M` | aster-api `application.properties:331-333` |

## Global Constraints

- **无迁移**：aster-api 与 cloud 都不建表、不改列。（若实施中发现需要迁移，停下回到 ADR；迁移须单独 PR 先行。）
- aster-api 改动只限 ADR 0061 §3.4：`severity`（`critical | warning | info`，逗号多选，不得重复或空项，与 `kind` 取交集，非法 → 400 `{"error":"invalid_query"}`）与可选字段 `ackComment`。其余响应字节不变（既有测试原样通过）。
- 角色映射（ADR 0061 §3.2）：只有对账客户端工厂签真实角色（个人租户 `owner`，团队取 `getTeamMemberRole` 的现查值）；**其他所有 cloud→aster-api 调用仍签 `member`**，以测试钉住。角色永不取自请求。
- 权限：看 = 个人租户本人或团队角色 ≥ `member`；确认 = 个人租户本人或团队角色 ≥ `admin`；判定用 `getRoleLevel`，不新增 `TeamPermission` 常量。
- 只在当前租户内查决策、链收据与执行，绝不尝试其他租户；决策 404 只表示「本工作区无此决策行」，按 kind 措辞（ADR 0061 §3.5），不断言该 id 在别处不存在。
- 签非 `member` 角色的客户端只经 `ReconciliationApi` 窄接口可达，每请求新建、不缓存（ADR 0061 §3.2）。
- 上报方可控文本（`detail.*`、`executorId`、`executor`、`source`、`actionName`、`resource*`）只作 React 文本节点；单值超过 256 字符折叠显示；不渲染为链接。
- BFF 在把上游结果交给 UI 前**总是**按请求的 severity 再过滤一次（旧 api 会忽略 `severity`）；检测到旧 api 时按 T3 `fillFilteredPage` 续取，至多 5 次上游调用。
- 确认说明：去首尾空白后 1–1000 字符（cloud 先校验，aster-api 再校验）。
- 错误响应形状同签发方：`{ error, message, requestId? }`；上游 408 / 5xx / 网络错误 → 502 `upstream_unavailable` 带 `requestId`；上游 400 / 409 按原状态与错误码透传英文文案；上游 404（确认）→ 404 `orphan_not_found`。
- 跨租户摘要：并发 4、单租户超时 `GUARD_TIMEOUT_MS`，单租户失败只进 `unavailableTenants`；摘要卡在客户端加载，`/governance` 的 SSR 不增加任何 aster-api 调用。
- 时间一律按 UTC 固定格式渲染并标注 `UTC`。
- 新文案进 `src/i18n/demo-supplement.ts`（en / zh / de / hi）；代码注释用简体中文。
- 提交信息结尾 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 正文结尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`，并引用 cloud#769。

## Review Focus

1. **旧 aster-api 忽略 `severity`**：cloud 先于 A1 部署时，选「critical」不得出现 info 行——BFF 二次过滤并续取（T3 `filters by severity even when upstream ignores it`、`stops after 5 upstream pages`）。
2. **会话中被降级的管理员**：页面仍显示「确认」按钮，点下去 BFF 现查角色 → 403，且不调用 aster-api；UI 提示并刷新权限（T3 `ack re-reads the role per request`，T7 `ack 403 hides the button`）。
3. **`side_effect_without_decision` 带他租户的真实 decisionId**：决策上下文只以当前租户签名查询，得到 404 → `decision.state === 'not_found'`，措辞为「上报方声称的决策 id 在本工作区不存在」，无其他租户请求（T4 `never queries another tenant`）。
4. **确认与自动解除竞态**：对账把行改成 `RESOLVED` 后管理员再确认 → 409 `not_open`，UI 用响应里的 `status` 更新该行并提示，不报通用错误（T3、T7）。
5. **上报方注入的文本**（`<img onerror>`、`javascript:` 资源 id、10 KB 的 `actionName`）：原样作文本显示、折叠，DOM 中无对应元素或链接（T6 `renders reporter text inertly`）。

---

## 裁决（ADR 未定或实施细节；用户可推翻）

| # | 问题 | 裁决 | 理由 |
|---|---|---|---|
| R1 | 要不要 ADR | 要（ADR 0061）：授权映射（cloud 首次签非 `member` 角色）+ 对外查询契约扩展 | 任务规则：授权 / API 变化需 ADR |
| R2 | severity 计数从哪来 | cloud 由摘要的 `open`（按 kind）与本地 kind 表计算；不加 `openBySeverity` | 少改契约；kind 表两侧测试同时钉住 8 个字面量 |
| R3 | severity 多选的写法 | 逗号分隔单参数 `severity=critical,warning` | 与 `kind` 单参数风格一致；cloud 端 URL 也用同一写法 |
| R4 | 列表缺省过滤 | OPEN + `critical,warning`（ADR 0061 Q3） | ④ 上线前 from-evidence 的 ALLOW 会大量成为 info |
| R5 | 页面放哪 | `/governance` 摘要卡 + 新页 `/governance/reconciliation`；侧栏不加入口 | 治理页是跨租户的控制视图，孤儿是按租户的运维视图 |
| R6 | 租户切换 | 新页 `?tenant=` 缺省取摘要中 critical 最多的租户，平手取个人租户；无可见租户 → 空状态 | 打开即见最需要处理的 |
| R7 | 分页 | 游标「加载更多」（`before = next`），每页 50；不做页码 | aster-api 只给游标 |
| R8 | 执行链接粒度 | 链到 `/policies/{policyId}/logs`（日志页没有单条执行的深链，不在本计划新增） | YAGNI |
| R9 | 审批链接 | 收据里有审批时链 `/approvals?status=<APPROVED|REJECTED|EXPIRED|PENDING>&decisionId=<id>` | 审批页按状态列、按 decisionId 高亮（现行为） |
| R10 | 确认人显示 | `acknowledgedBy`（cloud 用户 id）经 `loadTenantMemberProfiles` 解析；离队成员显示「未知用户」 | 同签发方的 `createdByUser` |
| R11 | 文档 | how-to/guard 的 en / zh / de 各加一节「对账与孤儿动作」；hi 没有该文档，不新建 | 与现有文档覆盖一致 |
| R12 | 发布次序 | A1 随下一次 aster-api 发布上线；C1 不依赖 A1（二次过滤）；**C2 在 A1 已部署生产后合并**（发布 / 部署动作先问用户） | 用户可见的 severity 过滤需要服务端支持才分页正确 |

---

## 命令约定

| 记号 | 完整命令（工作目录：`AT` / `AG` 在 aster-api 仓库根；`CU` / `CI` / `CT` / `CALL` 在 aster-cloud 仓库根） |
|---|---|
| `AT <tests>` | aster-api：`./gradlew --offline :test --tests '<tests>'`（PG / Redis 用 podman 起，端口避开 :55432 / :6379，环境变量同 `2026-11-14-guard-reconciliation.md` 的「本地测试」） |
| `AG` | aster-api guard 套件：`./gradlew --offline :test --tests 'io.aster.guard.*' --tests 'io.aster.security.apikey.*' --tests 'io.aster.api.PublicApiContractTest'` |
| `CU <files…>` | cloud：`pnpm exec vitest run <files…>` |
| `CI <files…>` | cloud：`LICENSE_E2E=1 DATABASE_URL=postgresql://postgres:postgres@localhost:55433/aster_cloud pnpm exec vitest run --config vitest.integration.config.ts <files…>`（测试库 :55433，**不得**指向 :55432） |
| `CT` | cloud：`pnpm exec tsc --noEmit` |
| `CALL` | cloud：`CT && pnpm lint && pnpm test:run` |

「→ FAIL」指新增用例失败、其余不变；「→ PASS」指退出码 0。

## 顺序与 PR 划分

```
aster-api  PR-A1 (T1)  ──发布并部署生产（问用户）──┐
cloud      PR-C1 (T2–T4, BFF)  ── 任何时候可合 ──┴──▶ PR-C2 (T5–T8, UI + 文案 + 文档 + e2e)
```

| 任务 | 仓库 | PR |
|---|---|---|
| T1 `severity` 过滤与 `ackComment` | wontlost-ltd/aster-api | A1 |
| T2 kind 表、查询解析、访问判定与签角色的客户端工厂 | wontlost-ltd/aster-cloud | C1 |
| T3 客户端方法与租户级 BFF 路由（列表、确认）、跨租户摘要路由 | wontlost-ltd/aster-cloud | C1 |
| T4 决策上下文 | wontlost-ltd/aster-cloud | C1 |
| T5 `/governance` 摘要卡 | wontlost-ltd/aster-cloud | C2 |
| T6 `/governance/reconciliation` 列表、过滤、分页、行详情 | wontlost-ltd/aster-cloud | C2 |
| T7 确认对话框、文案四语、文档 | wontlost-ltd/aster-cloud | C2 |
| T8 本地栈端到端 | wontlost-ltd/aster-cloud | C2 |

## 文件结构（cloud 新增）

| 文件 | 职责 |
|---|---|
| `src/lib/guard-reconciliation/kinds.ts` | 客户端可用：8 个 kind 的 severity / 是否自动解除；`severityOf`、`countsBySeverity` |
| `src/lib/guard-reconciliation/query.ts` | 客户端可用：`parseOrphanQuery`、`orphanQueryToSearch`（路由与页面共用） |
| `src/lib/guard-reconciliation/access.ts` | 服务端：`reconciliationAccess`、`listReconciliationTenants`、`createReconciliationClient` |
| `src/lib/guard-reconciliation/overview.ts` | 服务端：跨租户摘要 |
| `src/lib/guard-reconciliation/decision-context.ts` | 服务端：决策 + 链收据 + 执行链接 |
| `src/app/api/guard-reconciliation/_shared.ts` | BFF 鉴权与上游错误映射 |
| `src/app/api/guard-reconciliation/route.ts` | `GET` 跨租户摘要 |
| `src/app/api/guard-reconciliation/[tenantId]/route.ts` | `GET` 摘要 + 一页孤儿 |
| `src/app/api/guard-reconciliation/[tenantId]/orphans/[orphanId]/ack/route.ts` | `POST` 确认 |
| `src/app/api/guard-reconciliation/[tenantId]/decisions/[decisionId]/route.ts` | `GET` 决策上下文 |
| `src/components/guard/reconciliation-summary-card.tsx` | 治理页摘要卡 |
| `src/app/[locale]/(dashboard)/governance/reconciliation/{page.tsx,reconciliation-content.tsx,orphan-row.tsx,ack-dialog.tsx}` | 新页 |

---

### Task 1: `severity` 过滤与 `ackComment`（aster-api，PR-A1）

**Files:**
- Modify: `src/main/java/io/aster/guard/api/GuardReconciliationResource.java`（`list` 加 `@QueryParam("severity")`；`parse` 加参数）、`src/main/java/io/aster/guard/reconcile/GuardOrphanAdminService.java`（`ListQuery` 加 `Set<String> severities`；`list` 加 `and severity in :sev`；`view` 加 `ackComment`）、`src/main/java/io/aster/guard/reconcile/OrphanKind.java`（`static final Set<String> SEVERITIES = Set.of("critical","warning","info")`）、`REST_API.md:94`、`docs/adr/0054-guard-execution-point.md`（§10.2 查询段末加一行「扩展见 ADR 0061 §3.4」）
- Test: `src/test/java/io/aster/guard/reconcile/GuardReconciliationResourceTest.java`

**Interfaces:**
- Produces: `GET /api/v1/guard/reconciliation/orphans?severity=critical[,warning[,info]]`；列表项与确认响应的可选 `ackComment`。`ListQuery(String status, OrphanKind kind, Set<String> severities /* 空集 = 不过滤 */, int limit, Long before)`。

- [ ] **Step 1: 写失败的测试**（加到 `GuardReconciliationResourceTest`，夹具沿用 `orphansFor` / `ReconcileFixture`，每个 kind 各造一行）：
  - `severityFilterSingle`：`severity=critical` → 只有 4 个 critical kind 的行。
  - `severityFilterMulti`：`severity=critical,warning` → critical + `consumed_without_result`，无 info。
  - `severityAndKindIntersect`：`severity=info&kind=side_effect_mismatch` → 200 `items: []`。
  - `severityInvalid`：`severity=high`、`severity=`、`severity=critical,`、`severity=critical,critical`、`severity=CRITICAL` → 400 `{"error":"invalid_query"}`。
  - `severityPagesWithCursor`：3 条 critical、`limit=2` → 第一页 2 条带 `next`，第二页 `before=next` 1 条无 `next`，跨页无 info 行。
  - `ackCommentReturned`：确认后的 200 响应与 `status=ACKNOWLEDGED` 列表项都有 `ackComment` 等于提交值；OPEN 行无该键。
  - 既有四个用例不改、仍通过（响应字节不变的证据）。
- [ ] **Step 2:** `AT 'io.aster.guard.reconcile.GuardReconciliationResourceTest'` → FAIL。
- [ ] **Step 3:** 实现：`parse` 把 `severity` 按 `,` 切分（`split(",", -1)`），每项必须 ∈ `OrphanKind.SEVERITIES` 且不重复，否则 empty；`list` 在 `severities` 非空时追加 `and severity in :sev`；`view` 在 `ackComment != null` 时写入。改 `REST_API.md:94` 与 ADR 0054 指针。
- [ ] **Step 4:** `AT 'io.aster.guard.reconcile.*'` → PASS；`AG` → PASS（`PublicApiContractTest` 不变）。
- [ ] **Step 5:** Commit；开 PR-A1（`Refs wontlost-ltd/aster-cloud#769`）。合并后在发布前问用户是否随下一次 aster-api 发布上线（R12）。

### Task 2: kind 表、查询解析、访问判定与客户端工厂（cloud，PR-C1）

**Files:**
- Create: `src/lib/guard-reconciliation/kinds.ts`、`query.ts`、`access.ts`
- Modify: `src/services/policy/guard-types.ts`（新增下列类型）、`src/config/api-versions.ts`（`guardReconciliation`、`guardReconciliationOrphans`、`guardOrphanAck(id: number)`）、`src/services/policy/policy-api.ts`（三个方法）
- Test: `src/__tests__/lib/guard-reconciliation/kinds.test.ts`、`query.test.ts`、`access.test.ts`、`role-scope.test.ts`、`src/__tests__/services/policy-api-guard-reconciliation.test.ts`

**Interfaces:**
- Produces（`guard-types.ts`）：
  - `type GuardOrphanKind = 'side_effect_without_decision' | 'side_effect_unconsumed' | 'side_effect_mismatch' | 'consumed_without_result' | 'allow_unconsumed' | 'consume_on_non_allow' | 'result_without_consume' | 'orphan_receipt'`
  - `type GuardOrphanSeverity = 'critical' | 'warning' | 'info'`；`type GuardOrphanStatus = 'OPEN' | 'RESOLVED' | 'ACKNOWLEDGED'`
  - `interface GuardOrphan { id: number; kind: GuardOrphanKind; severity: GuardOrphanSeverity; status: GuardOrphanStatus; decisionId?: string; sideEffectId?: number; executor: string; executorId?: string; detectedAt: string; resolvedAt?: string; resolution?: 'late_result' | 'aged_out' | 'acknowledged'; acknowledgedBy?: string; ackComment?: string; detail: Record<string, unknown> }`
  - `interface GuardOrphanPage { items: GuardOrphan[]; next?: number }`
  - `interface GuardReconciliationSummary { lastRun: { runId: number; finishedAt: string; windowStart: string; windowEnd: string; newOrphans: number; resolvedOrphans: number; openOrphans: number } | null; open: Partial<Record<GuardOrphanKind, number>>; openTotal: number; resultGrace: string; lookback: string }`
  - `interface GuardOrphanQuery { status: GuardOrphanStatus; severities: GuardOrphanSeverity[]; kind?: GuardOrphanKind; before?: number; limit: number }`
- Produces（`kinds.ts`）：`ORPHAN_KINDS: readonly { kind: GuardOrphanKind; severity: GuardOrphanSeverity; autoResolvable: boolean }[]`（顺序同 `OrphanKind`）；`severityOf(kind): GuardOrphanSeverity`；`countsBySeverity(open: GuardReconciliationSummary['open']): Record<GuardOrphanSeverity, number>`（未知 kind 忽略）。
- Produces（`query.ts`）：`DEFAULT_ORPHAN_QUERY: GuardOrphanQuery = { status: 'OPEN', severities: ['critical', 'warning'], limit: 50 }`；`parseOrphanQuery(params: URLSearchParams): GuardOrphanQuery | null`（缺省值同上；`severity=all` 表示空数组 = 不过滤；`limit` 1–200；`before` 正整数；非法 → null）；`orphanQueryToSearch(q): URLSearchParams`（发往 aster-api：`severity` 空数组时省略）。
- Produces（`access.ts`，`import 'server-only'`）：
  - `interface ReconciliationAccess { role: TeamRole; canView: boolean; canAck: boolean }`
  - `reconciliationAccess(userId: string, tenantId: string): Promise<ReconciliationAccess | null>`（个人租户 → `owner`；团队 → `getTeamMemberRole`；非成员 → null；`canView = level ≥ member`，`canAck = level ≥ admin`）
  - `listReconciliationTenants(userId: string): Promise<{ id: string; name: string; access: ReconciliationAccess }[]>`（个人租户 + `freshDb` 查 `teamMembers`（含 `role` 与团队名），只返回 `canView`）
  - `interface ReconciliationApi { summary(): Promise<GuardReconciliationSummary>; listOrphans(q: GuardOrphanQuery): Promise<GuardOrphanPage>; ackOrphan(id: number, comment: string): Promise<GuardOrphan>; getDecision(id: string): Promise<GuardDecision> }`——**窄接口**：带非 `member` 角色签名的 `PolicyApiClient` 闭包在内，不对外暴露（ADR 0061 §3.2 第 4 条）。
  - `createReconciliationClient(tenantId: string, userId: string): Promise<{ api: ReconciliationApi; access: ReconciliationAccess } | null>`（`canView` 为假 → null；否则以 `createPolicyApiClientForUser(tenantId, userId, access.role)` 建客户端并包装）。**每个请求在路由处理函数内调用一次，不缓存、不放模块作用域。**
- Produces（`PolicyApiClient`，只供 `ReconciliationApi` 包装）：`getGuardReconciliation(): Promise<GuardReconciliationSummary>`；`listGuardOrphans(q: GuardOrphanQuery): Promise<GuardOrphanPage>`（查询串 = `orphanQueryToSearch(q)`）；`ackGuardOrphan(id: number, comment: string): Promise<GuardOrphan>`（`id` 非正整数时本地抛 `PolicyApiError(…, 400, 'invalid_id')`）。均用 `GUARD_TIMEOUT_MS`。

- [ ] **Step 1: 写失败的测试**：
  - `kinds.test.ts`：`ORPHAN_KINDS` 恰为 8 项，字面量与 severity 逐一等于 aster-api `OrphanKind`（critical：前三项 + `consume_on_non_allow` + `result_without_consume`；warning：`consumed_without_result`；info：`allow_unconsumed`、`orphan_receipt`）；`autoResolvable` 恰为 warning 与两个 info；`countsBySeverity({ side_effect_mismatch: 2, allow_unconsumed: 5, bogus: 9 } as never)` → `{ critical: 2, warning: 0, info: 5 }`。
  - `query.test.ts`：空参数 → `DEFAULT_ORPHAN_QUERY`；`severity=all` → `severities: []` 且 `orphanQueryToSearch` 无 `severity`；`severity=info,critical` → 保持给定顺序；非法 `status=open`、`severity=high`、`kind=x`、`limit=0`、`limit=201`、`before=-1`、`before=1e3` → null。
  - `access.test.ts`（mock `getTeamMemberRole`、`createPolicyApiClientForUser`）：个人租户 → `{ role: 'owner', canView: true, canAck: true }`；团队 `viewer` → `canView: false` 且 `createReconciliationClient` 返回 null、不建客户端；`member` → 可看不可确认；`admin` / `owner` → 都可；非成员 → null；`createReconciliationClient` 把 `access.role` 作第三参数传给 `createPolicyApiClientForUser`；`createReconciliationClient` 的返回值键集恰为 `api`、`access`，`api` 的键集恰为 `summary / listOrphans / ackOrphan / getDecision`；**回归**：`createPolicyApiClientForUser` 的缺省第三参数仍是 `'member'`。
  - `role-scope.test.ts`（源码扫描，ADR 0061 §3.2 范围钉）：遍历 `src/**/*.{ts,tsx}`（不含 `__tests__`），以非缺省角色调用 `createPolicyApiClientForUser(`（三个及以上实参）或 `new PolicyApiClient(`（第三实参不是字面量 `'member'`）的文件，恰好只有 `src/lib/guard-reconciliation/access.ts`（扫描前先断言能在已知现存调用点上正确数出实参个数，防止正则失效而假通过）。
  - 客户端：`listGuardOrphans` 发出 `GET /api/v1/guard/reconciliation/orphans?status=OPEN&severity=critical%2Cwarning&limit=50`，签名的 `X-User-Role` 为构造时的角色；`ackGuardOrphan(0, 'x')` 本地 400 不发请求。
- [ ] **Step 2:** `CU src/__tests__/lib/guard-reconciliation/ src/__tests__/services/policy-api-guard-reconciliation.test.ts` → FAIL。
- [ ] **Step 3:** 按 Interfaces 实现三文件、类型与客户端方法。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task 3: BFF 路由（列表、确认、跨租户摘要）（cloud，PR-C1）

**Files:**
- Modify: `src/lib/upstream-error-messages.ts`（`invalid_query`、`invalid_ack`、`not_open`、`orphan_not_found`）
- Create: `src/lib/guard-reconciliation/overview.ts`、`src/app/api/guard-reconciliation/_shared.ts`、`route.ts`、`[tenantId]/route.ts`、`[tenantId]/orphans/[orphanId]/ack/route.ts`
- Test: `src/__tests__/api/guard-reconciliation-routes.test.ts`、`src/__tests__/lib/guard-reconciliation/overview.test.ts`

**Interfaces:**
- Consumes: T2 全部（路由只经 `ReconciliationApi` 访问 aster-api）。
- Produces（`[tenantId]/route.ts` 内的 `fillFilteredPage(api: ReconciliationApi, q: GuardOrphanQuery): Promise<GuardOrphanPage>`）：上游结果按 `q.severities` 再过滤；**仅当**本页出现了未请求的 severity（说明上游是忽略 `severity` 的旧 api）且过滤后不足 `q.limit` 条、上游仍有 `next` 时，以 `before = next` 继续取下一原始页，至多共 5 次上游调用。凑满 `q.limit` 时停在当前原始页内，返回的 `next` = 最后一条**返回项**的 `id`（aster-api 的 `before` 是 `id <` 游标，故不漏行）；未凑满时 `next` = 最后一次上游调用的 `next`（无则省略）。新 api 下恰好 1 次调用、`next` 原样。
- Produces（`overview.ts`）：`interface ReconciliationTenantSummary { tenantId: string; tenantName: string; canAck: boolean; lastRunAt: string | null; openBySeverity: Record<GuardOrphanSeverity, number>; openTotal: number }`；`listReconciliationOverview(userId: string): Promise<{ tenants: ReconciliationTenantSummary[]; unavailableTenants: string[] }>`（按 critical 降序、再按 openTotal 降序、个人租户优先）。
- Produces（路由）：
  - `GET /api/guard-reconciliation` → `listReconciliationOverview` 的结果。
  - `GET /api/guard-reconciliation/[tenantId]?status&severity&kind&before&limit` → `{ summary: GuardReconciliationSummary; page: { items: (GuardOrphan & { acknowledgedByUser: UserProfile | null })[]; next?: number }; canAck: boolean }`；查询非法 → 400 `invalid_query`（不调用上游）。
  - `POST /api/guard-reconciliation/[tenantId]/orphans/[orphanId]/ack`，体 `{ comment }` → 200 `GuardOrphan`；`canAck` 假 → 403；上游 409 → 409 `{ error: 'not_open', status }`。

- [ ] **Step 1: 写失败的测试**：
  - 路由（mock `access` 与客户端）：
    - `401 without session`；`403 for viewer and non-member, upstream not called`。
    - `400 invalid_query for severity=high, upstream not called`。
    - `filters by severity even when upstream ignores it`：上游（模拟旧 api）第一页 2 critical + 48 info 带 `next`，第二页 50 critical 带 `next`；请求 `severity=critical&limit=50` → 回 50 条全为 critical（第一页 2 条 + 第二页前 48 条），上游调用 2 次，`next` = 第 50 条返回项的 `id`；再以该 `next` 请求 → 第二页剩下的 2 条 critical 出现（不漏行）。
    - `stops after 5 upstream pages`：上游每页全为 info 且一直有 `next` → 恰 5 次调用，`items: []`，`next` = 第 5 页的上游 `next`（UI 可继续加载）。
    - `new api: single upstream call`：上游只返回请求的 severity → 1 次调用，`next` 原样。
    - `resolves acknowledgedBy via loadTenantMemberProfiles; profile lookup failure degrades to null`。
    - `member cannot ack (403, upstream not called)`；`admin ack passes trimmed comment`；`blank or 1001-char comment → 400 invalid_ack, upstream not called`。
    - `ack re-reads the role per request`：同一用户两次请求之间角色由 `admin` 改为 `member` → 第二次 403、不调用上游；`createReconciliationClient` 被调用 2 次（无缓存）。
    - `list re-reads the role per request`：两次列表请求之间角色由 `member` 改为 `viewer` → 第二次 403、不调用上游。
    - `upstream 409 not_open → 409 with current status`；`upstream 404 → 404 orphan_not_found`；`upstream 503 / timeout → 502 upstream_unavailable with requestId`。
  - 概览：三个租户，其中一个上游抛错 → 它进 `unavailableTenants`、其余正常；`viewer` 团队不出现；排序符合 Interfaces；并发不超过 4（计数在途 promise）。
- [ ] **Step 2:** `CU src/__tests__/api/guard-reconciliation-routes.test.ts src/__tests__/lib/guard-reconciliation/overview.test.ts` → FAIL。
- [ ] **Step 3:** 实现；`_shared.ts` 照 `src/app/api/fact-issuers/_shared.ts` 的结构（错误文案改为对账语境），不复制其签发方专用分支。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task 4: 决策上下文（cloud，PR-C1）

**Files:**
- Create: `src/lib/guard-reconciliation/decision-context.ts`、`src/app/api/guard-reconciliation/[tenantId]/decisions/[decisionId]/route.ts`
- Test: `src/__tests__/lib/guard-reconciliation/decision-context.test.ts`、`src/__tests__/integration/guard-reconciliation-execution-link.integration.test.ts`

**Interfaces:**
- Consumes: T2 `createReconciliationClient` / `ReconciliationApi.getDecision`；既有 `fetchDecisionReceipts`（它自签 `member`，不经窄接口）、`policyTenantId`。
- Produces：
  - `type Part<T> = { state: 'ok'; value: T } | { state: 'not_found' } | { state: 'unavailable' }`
  - `interface DecisionContext { decisionId: string; decision: Part<GuardDecision>; chain: Part<{ decision: ChainReceipt | null; consumes: ChainConsume[]; results: ChainResult[]; approvals: ChainApproval[] }>; execution: { policyId: string; executionId: string } | null; approvalLink: { status: GuardApprovalStatus } | null }`
  - `loadDecisionContext(tenantId: string, api: ReconciliationApi, decisionId: string): Promise<DecisionContext>`（三个来源并发，互不影响）；`findExecutionForDecision(tenantId: string, decisionId: string): Promise<{ policyId: string; executionId: string } | null>`（按 `metadata->>'guardDecisionId'` 查最新一条，且其策略的 `policyTenantId` = `tenantId`）。
  - 路由 `GET …/decisions/[decisionId]` → `DecisionContext`；非 UUID → 400 `invalid_decision_id` 不调用上游（孤儿的 `decisionId` 来自 UUID 列，正常不会发生；UI 按 `unavailable` 显示）；无 `canView` → 403。

- [ ] **Step 1: 写失败的测试**：
  - `decision 404 → decision.state 'not_found', chain still loaded`。
  - `getGuardDecision throws 503 → decision 'unavailable', others ok`。
  - `chain lookup reports missing → chain 'not_found'; unavailable → 'unavailable'`；`fetchDecisionReceipts rejects → chain 'unavailable', decision and execution still returned`。
  - `orphan_receipt shape`：决策 404 + 链上有决策事件 → `decision.state === 'not_found'` 且 `chain.state === 'ok'`、`chain.value.decision` 非空。
  - `execution lookup throws → execution null, others ok`。
  - `approvalLink from latest chain approval outcome`（`APPROVED` → `{ status: 'APPROVED' }`；无审批 → null）。
  - `never queries another tenant`：`fetchDecisionReceipts` 与客户端只以入参 `tenantId` 被调用一次。
  - 集成（PG）：两条执行带同一 `guardDecisionId`，一条属本租户策略、一条属他租户 → 只返回本租户那条；无匹配 → null。
- [ ] **Step 2:** `CU src/__tests__/lib/guard-reconciliation/decision-context.test.ts` 与 `CI src/__tests__/integration/guard-reconciliation-execution-link.integration.test.ts` → FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同 Step 2 → PASS；`CALL` → PASS。
- [ ] **Step 5:** Commit；开 PR-C1（T2–T4，`Refs #769`）。

### Task 5: `/governance` 摘要卡（cloud，PR-C2）

**Files:**
- Create: `src/components/guard/reconciliation-summary-card.tsx`
- Modify: `src/app/[locale]/(dashboard)/governance/governance-content.tsx`（在 `registry-version` 段落之前插入卡片）、`src/i18n/demo-supplement.ts`（`governancePage.reconciliation.*` 四语）
- Test: `src/components/guard/reconciliation-summary-card.test.tsx`、`src/app/[locale]/(dashboard)/governance/governance-content.test.tsx`

**Interfaces:**
- Consumes: T3 `GET /api/guard-reconciliation`、`ReconciliationTenantSummary`。
- Produces: `<ReconciliationSummaryCard />`（无 props；挂载后 fetch；`tenants` 为空且无 `unavailableTenants` → 渲染 `null`）。每行：租户名（个人租户显示本地化的「个人」）、三个 severity 计数徽章（critical > 0 用 `danger`，warning > 0 用 `warning`，info 用 `neutral`）、最近对账时间（UTC）或「尚未对账」、链接 `/governance/reconciliation?tenant=<id>`。

- [ ] **Step 1: 写失败的测试**：加载中骨架；两个租户正常渲染、徽章变体正确；`unavailableTenants` 非空时出现提示；空结果不渲染任何节点；fetch 失败显示可重试的错误条。`governance-content.test.tsx` 既有快照只多出卡片挂载点，其余 DOM 不变。
- [ ] **Step 2:** `CU src/components/guard/reconciliation-summary-card.test.tsx "src/app/[locale]/(dashboard)/governance/governance-content.test.tsx"` → FAIL。
- [ ] **Step 3:** 实现与四语文案。
- [ ] **Step 4:** 同 Step 2 → PASS。
- [ ] **Step 5:** Commit。

### Task 6: `/governance/reconciliation` 列表、过滤、分页、行详情（cloud，PR-C2）

**Files:**
- Create: `src/app/[locale]/(dashboard)/governance/reconciliation/page.tsx`、`reconciliation-content.tsx`、`orphan-row.tsx`
- Modify: `src/i18n/demo-supplement.ts`（`guardReconciliation.*`：标题、8 个 kind 的名称与一句「这是什么 / 该怎么查」、3 个 severity、3 个状态、3 个解除方式、detail 字段名、决策上下文各状态、空状态）
- Test: `reconciliation-content.test.tsx`、`orphan-row.test.tsx`（同目录）

**Interfaces:**
- Consumes: T2 `parseOrphanQuery`、`orphanQueryToSearch`、`ORPHAN_KINDS`、`listReconciliationTenants`；T3 租户级路由；T4 决策上下文路由。
- Produces: `page.tsx`（服务端）：会话校验；`listReconciliationTenants`；按 R6 选租户（`?tenant=` 不在可见列表 → 用缺省并替换 URL）；把 `tenants`、`initialTenantId`、`initialQuery` 交给 `ReconciliationContent`（首屏列表在客户端取，避免 SSR 阻塞）。`<OrphanRow orphan canAck tenantId onAcknowledged(orphan: GuardOrphan) />`。
- 行：severity 徽章、kind 名、`detectedAt`（UTC）、`executor`、状态；展开后：kind 说明、detail 键值表（纯文本、>256 字符折叠）、`executorId`、确认信息（`acknowledgedByUser` / `ackComment` / `resolvedAt`）、决策上下文（展开时才请求；无 `decisionId` 的行不请求并说明「上报未带决策」）。
- 过滤器状态写回 URL（`router.replace`，不新增历史）；「加载更多」追加下一页；切换租户或过滤条件重置列表。

- [ ] **Step 1: 写失败的测试**：
  - `default query is OPEN + critical,warning`，请求 URL 与之相符。
  - `switching status to ACKNOWLEDGED shows ackComment and acknowledger`。
  - `load more appends with before=next and hides button when next is absent`。
  - `tenant without canAck shows no ack button`。
  - `decision context states`：ok（outcome、策略地址、consumedBy、result、执行链接、审批链接）/ not_found 按 kind 措辞（`orphan_receipt`：「链上有决策事件，本工作区无决策行」并显示链事件；`side_effect_without_decision`：「上报方声称的决策 id 在本工作区不存在」；其他 kind：「本工作区无此决策行（异常）」）/ unavailable（可重试）。
  - `renders reporter text inertly`：`detail.actionName = '<img src=x onerror=alert(1)>'`、`detail.resourceId = 'javascript:alert(1)'`、10 KB 字符串 → 文本可见（折叠）、`container.querySelector('img')` 为 null、无 `href` 含 `javascript:`。
  - `empty state per filter`；`upstream 502 shows error with requestId`。
- [ ] **Step 2:** `CU "src/app/[locale]/(dashboard)/governance/reconciliation/"` → FAIL。
- [ ] **Step 3:** 实现与文案。
- [ ] **Step 4:** 同 Step 2 → PASS；`CT` → PASS。
- [ ] **Step 5:** Commit。

### Task 7: 确认对话框、四语文案收尾、文档（cloud，PR-C2）

**Files:**
- Create: `src/app/[locale]/(dashboard)/governance/reconciliation/ack-dialog.tsx`
- Modify: `orphan-row.tsx`（接入对话框）、`src/i18n/demo-supplement.ts`、`src/app/[locale]/docs/how-to/guard/{en,zh,de}.mdx`（新节「对账与孤儿动作」：8 个 kind、谁能看 / 确认、确认不可撤销、指向治理页）
- Test: `ack-dialog.test.tsx`、`src/__tests__/i18n/guard-reconciliation-messages.test.ts`

**Interfaces:**
- Consumes: T3 确认路由。
- Produces: `<AckDialog orphan onDone(result: { kind: 'acknowledged'; orphan: GuardOrphan } | { kind: 'not_open'; status: GuardOrphanStatus } | { kind: 'forbidden' }) />`：文本框（字数计数，去空白后 1–1000 才可提交）、不可撤销警示；可自动解除的 kind 额外提示「此类会自动解除」。

- [ ] **Step 1: 写失败的测试**：
  - `submit disabled for blank and 1001 chars`；`success replaces the row with ACKNOWLEDGED`（在 OPEN 视图中移出并提示）。
  - `409 not_open updates row to the returned status and explains`。
  - `ack 403 hides the button`（并显示「权限已变化」）。
  - `auto-resolvable kinds show the hint`（`allow_unconsumed` 有，`side_effect_mismatch` 无）。
  - i18n：`guardReconciliation.*` 与 `governancePage.reconciliation.*` 在 en / zh / de / hi 键集完全一致、无空串；8 个 kind 各有名称与说明。
- [ ] **Step 2:** `CU "src/app/[locale]/(dashboard)/governance/reconciliation/ack-dialog.test.tsx" src/__tests__/i18n/guard-reconciliation-messages.test.ts` → FAIL。
- [ ] **Step 3:** 实现、补齐文案与文档。
- [ ] **Step 4:** 同 Step 2 → PASS；`CALL` → PASS；`pnpm build` → PASS。
- [ ] **Step 5:** Commit。

### Task 8: 本地栈端到端（cloud，PR-C2）

**Files:**
- Create: `src/__tests__/e2e-browser/local-stack/guard-reconciliation.spec.ts`
- Modify: `src/__tests__/e2e-browser/local-stack/README.md`（前置：aster-api 容器设 `ASTER_GUARD_RECONCILIATION_CRON='*/10 * * * * ?'`、`ASTER_GUARD_RECONCILIATION_MIN_INTERVAL=PT5S`，且为含 A1 的镜像）

**Interfaces:**
- Consumes: `helpers.ts` 的 `apiPost`、`psql`、`stateFor`；租户 `credit-pilot` 与 `CP_API_KEY`（同 `guard-bypass.spec.ts`）。

- [ ] **Step 1:** 写场景（`test.describe.configure({ mode: 'serial' })`；非本地或缺 `CP_API_KEY` 时 skip）：
  1. 以 `CP_API_KEY` `POST /api/v1/guard/side-effects` 一条无 `decisionId` 的上报（`effectId` 随机）；轮询 `psql` 至 `guard_orphan_actions` 出现对应 `side_effect_without_decision` 行（≤ 60 s）。
  2. 成员（非管理员）会话打开 `/governance`：摘要卡里 `credit-pilot` 的 critical ≥ 1；进入明细，看到该行，没有「确认」按钮；展开显示「上报未带决策」。
  3. 管理员（cp-owner）会话：确认该行（说明 `e2e ack`）→ 行离开 OPEN 视图；切到 ACKNOWLEDGED 看到说明与确认人；`psql` 断言链上有 `GUARD_ORPHAN_ACKNOWLEDGED` 事件，`metadata.comment = 'e2e ack'`、`performed_by` = cp-owner 的用户 id。
  4. 同一行再次确认（直接调 BFF）→ 409 `not_open`。
- [ ] **Step 2:** cloud 容器以 `-e E2E_LOCAL_STACK=1` 重建；运行 `BASE_CLOUD=http://localhost:3100 pnpm exec playwright test src/__tests__/e2e-browser/local-stack/guard-reconciliation.spec.ts --project=chromium-desktop --workers=1` → PASS。
- [ ] **Step 3:** Commit；在 A1 已部署生产后开 PR-C2（T5–T8，`Closes #769`）；合并后在 aster-api#465 留言并由用户决定是否关闭。

---

## 自查记录

- 覆盖（cloud#769 四条 + ADR 0061）：列 OPEN、按 kind / severity 过滤 → T1、T2、T3、T6；管理员带说明确认并入链 → T1（`ackComment`）、T3、T7、T8；链到决策与收据 → T4、T6；四语文案 → T5、T6、T7；ADR §3.1 权限 → T2、T3、T7；§3.2 角色签名与范围钉 → T2、T3；§3.3 破坏性边界 → T7；§3.4 → T1，兼容 → T3 二次过滤与 R12；§3.5 → T4；§3.6 → T6；§3.7 → T5、T6。
- 不在本计划：批量确认、撤销确认、通知、cloud 作为上报方、viewer 可见（ADR 0061 §2）。
- 类型一致性：`GuardOrphan*`、`GuardReconciliationSummary`（T2）用于 T3–T7；`ReconciliationAccess` / `ReconciliationApi` / `createReconciliationClient`（T2）用于 T3、T4、T6；`ReconciliationTenantSummary`（T3）用于 T5；`DecisionContext` / `Part`（T4）用于 T6；`parseOrphanQuery` / `orphanQueryToSearch` / `DEFAULT_ORPHAN_QUERY`（T2）用于 T3、T6。
- 车次门：无引擎改动；A1 随常规 aster-api 发布（发布动作先问用户）。
- Codex 评审记录：第 1 轮 76 分（退回）——H1 角色「每次现查」没有落到接口（客户端可被缓存）、H2 带 admin 签名的客户端暴露完整 `PolicyApiClient`（aster-api 另有 ADMIN 端点）、H3 决策 404 与「无证据」混为一谈（`orphan_receipt` 有链事件）、M1 旧 api 下二次过滤后的分页游标会漏行、M2 收据失败用例不足、M3 非法 id 的语义、L2 命令工作目录；已逐条修订（每请求新建 + 降级用例、`ReconciliationApi` 窄接口 + 源码扫描测试、按 kind 的 404 措辞、`fillFilteredPage` 续取至多 5 页且凑满时以最后返回项为游标、补三条降级用例、400 `invalid_decision_id`、命令表标明仓库）。第 2 轮 91 分（通过），一条 Low（Review Focus 第 3 条的旧响应形状）已改。
