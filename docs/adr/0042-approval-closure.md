# ADR 0042：审批闭环 —— 经验证的业务角色、从证据开决策、cloud 审批收件箱（AI 治理方向·子项目 3b）

- 状态：**ACCEPTED**（2026-10-09）
- 提出：2026-10-09；实施计划 `docs/superpowers/plans/2026-10-09-approval-closure.md`；栈验收见 §9.1
- 决策者：用户已拍板「业务角色挂在团队成员上由 owner/admin 授予；cloud 以证据链收据为锚开决策，不重复评估；收件箱走内部 HMAC v3 通道并签正用户与角色；角色持有者可审、管理员只读、站内通知不发邮件」
- 相关：ADR 0040（Action Guard：决策、待审批、四眼、§10 开放问题第一条）、ADR 0041（证据链补齐：correlationId 列、收据接口、复核者 `guard-approval`）、aster-cloud ADR 0015（团队作用域 API key 身份：verify 响应与快照）、ADR 0039（Verdict 四态与 `role`）

---

## 0. TL;DR

ADR 0040 的审批有两处没闭上：审批方的业务角色是调用方自报的 `X-User-Business-Role` 头，aster-api 不校验，任何持 key 者可以冒充 DPO；而 aster-cloud 的策略执行走 `/evaluate-source`，得到 `REQUIRE_APPROVAL` 后无处可审，`executions.metadata.guardDecisionId`（ADR 0041 §5.2 已预留读取）没有写入方。

本 ADR 做三件事，把「需要人批」变成「有人能批、批的人身份可验、批了之后证据包能看到」：

1. **经验证的业务角色**：角色存在 aster-cloud 的团队成员（个人租户存在用户）上，随 API key verify 响应与快照下发；aster-api 只信这条链路，`X-User-Business-Roles` / `X-User-Business-Role` 头被覆盖。guard 审批匹配改为「所需角色 ∈ 已验证角色集合」。
2. **从证据开决策**：新端点 `POST /api/v1/guard/decisions/from-evidence` 以 `/evaluate-source` 返回的 `evidence.correlationId` 为锚，从哈希链上的评估事件建决策与待审批，不重复评估；决策收据就是那条评估事件。
3. **cloud 审批收件箱**：dashboard 新页面汇总用户所在全部租户的待审批，持有所需角色者可审，owner/admin 只读；通过内部 HMAC v3 通道调用 guard，canonical 签入 query、用户 id 与业务角色，顺带关闭 ADR 0041 §7.1 的「query 未签名」遗留。

ADR 0041 §5.2 的 `guard-approval` 复核者随之从 `roleVerified=false` 改为 `true`。

## 1. 背景与问题

| 现状 | 位置 | 后果 |
|---|---|---|
| 审批方角色 = 原始 `X-User-Business-Role` 头，trim 后与 `required_role` 字符串相等 | `GuardApprovalService` 审批角色校验 | 任何持有效 key 的成员可自称任何角色；ADR 0040 §10 第一条 |
| `ApiKeyAuthFilter` 覆盖 X-Tenant-Id/X-User-Id/X-Api-Key-Id/X-User-Role，不覆盖业务角色头 | `ApiKeyAuthFilter` | 身份过滤器知道「是谁」，不知道「能批什么」 |
| cloud 无业务角色概念；`PolicyReviewer.subjectKind` 只有 domain_expert/engineer 且按策略授予 | aster-cloud schema | 没有可下发的角色来源 |
| verify 响应 `{valid, apiKeyId, userId, tenantId, quotaOwnerId, plan, subscriptionStatus, role}`，快照同形 | ADR 0015 | 下发链路存在，缺字段 |
| cloud 执行走 `/evaluate-source`（源码），guard 走已部署 module/function | 两条执行路由、`GuardDecisionService` | 同一策略两条路，`REQUIRE_APPROVAL` 在 cloud 侧止于一个琥珀标签 |
| `executions.metadata` 列存在但无人写；`guardDecisionId` 无写入方 | ADR 0041 §7.1(3) | 证据包永远没有 guard-approval 复核者 |
| 内部 HMAC v2 canonical 不含 query、X-User-Id、业务角色 | `signInternalCallerHeaders` / `InternalCallerFilter` | HMAC 调用者的 `performedBy` 回退到未签名头；四眼不可信 |
| dashboard 没有收件箱；`Notification` 表与 `NotificationsBell` 存在，种类只有团队邀请与策略分享 | aster-cloud | 有通知基座，没有审批入口 |

## 2. 决策一：业务角色挂在团队成员上，随 verify 下发

### 2.1 数据

- aster-cloud 迁移 0051：`TeamMember.businessRoles text[] NOT NULL DEFAULT '{}'`，`User.businessRoles text[] NOT NULL DEFAULT '{}'`（个人租户）。
- 词表：自由文本。入库校验 `normalizeBusinessRoles(input: string[]): string[]`（`src/lib/business-roles.ts`）：每项 trim；1–64 字符；仅可打印 ASCII 且不含逗号（`/^[\x20-\x2B\x2D-\x7E]{1,64}$/`）——已验证角色在 `X-User-Business-Roles` 头与 HMAC canonical 中以逗号拼接，含逗号会被拆错；去重保持首次出现顺序；最多 16 项；任一项不合法整体 400 `invalid_business_role`。与 Verdict 的 `role` 字符串按原样精确匹配（大小写敏感，不做模糊匹配），这是 ADR 0040 已有语义，本 ADR 不改。
- 授予（团队）：团队设置页成员列表的「业务角色」编辑，复用既有 `PUT /api/teams/[teamId]/members/[memberId]`（`memberId` 为 `TeamMember.id`），body 增加可选 `businessRoles`（与 `role` 至少给一个）。权限沿用 `TeamPermission.MEMBER_UPDATE_ROLE`，不新增权限常量；只改业务角色时不走成员层级校验，owner/admin 可给任何成员（含 owner 行与自己）授予业务角色——职责分离由 api 的四眼规则保证，而不是由授予权限保证。
- 授予（个人）：新建 `PUT /api/user/business-roles`（body `{ businessRoles }`），写 `User.businessRoles`。仓库无 profile 更新路由可扩展。
- 角色变更后重推该成员 key 的快照（团队 `refreshTeamKeySnapshots(teamId, userId)`，个人 `refreshPersonalKeySnapshots(userId)`）；推送失败只记 warn，不回滚已提交的变更。

### 2.2 下发

- `/api/internal/apikey/verify` 响应与 `toApiKeySnapshotBody` 增加 `businessRoles: string[]`：团队 key 取 `TeamMember.businessRoles`，个人 key 取 `User.businessRoles`。cloud 迁移 0051 必须先于新镜像执行（verify、快照、成员查询都会 select 该列，见 §8）。
- aster-api `ApiKeyVerifyResult` / `ApiKeySnapshot` 增加 `List<String> businessRoles`，缺省为空列表（旧 pod、旧快照兼容；快照推送方未升级时字段缺失即空）；归一只在 `ApiKeySnapshot.normalizeRoles` 一处实现。
- `ApiKeyAuthFilter` 写请求属性 `aster.identity.businessRoles`（不可变 `List<String>`），并**覆盖** `X-User-Business-Roles` 为已验证角色的逗号拼接、单数 `X-User-Business-Role` 为首项（无则空串），与覆盖 `X-User-Role` 同法。单数头只为旧读者保留，guard 不再读它。
- `RequestIdentityResolver.businessRoles()`：读该属性；属性不存在时回退 `X-User-Business-Roles` 头（逗号切分、trim、去空项），与现有 `performedBy()` 回退一致。
- 部署约束：生产必须开启 API key 认证（`aster.security.apikey.enabled=true`）或走 HMAC 通道。两者都不在时上面的回退读的是调用方自带的 `X-User-Business-Roles` 头，只允许出现在测试 profile。

### 2.3 匹配

`GuardApprovalService.decideApproval(tenantId, approvalId, decidedBy, businessRoles, …)` 为入口，校验在 `checkRoleAndInput`：`requiredRole == null`（ESCALATE）→ 通过；否则 `businessRoles.contains(requiredRole)`，`businessRoles` 取自 `identityResolver.businessRoles()`，不再读请求头。403 `role_mismatch` 响应体附 `verifiedRoles: [...]` 便于 UI 提示。审批人（签名内 userId 或 key 持有者）为空白或 `anonymous` → 400 `user_required`，不以兜底身份落库。

### 2.4 已知延迟

角色变更会重推成员 key 的快照（§2.1），aster-api 优先读 Redis 快照，故 Bearer 调用者通常立即按新角色鉴权；快照推送失败、只剩 verify 路径时，受 verify 结果 60 秒缓存影响，最多 60 秒后生效。HMAC 通道（§4）由 cloud 每次请求实时查库签入，无延迟。

## 3. 决策二：从证据开决策

### 3.1 端点

`POST /api/v1/guard/decisions/from-evidence`，仅内部 HMAC 通道（`InternalCallerFilter` 分类 REQUIRE_HMAC；Bearer 调用 403 `internal_only`）。

请求：
```json
{
  "correlationId": "<evaluate-source 返回的 evidence.correlationId>",
  "action": { "principal": {"id","type","roles"}, "action": {"name"}, "resource": {"type","id"}, "context": {}, "agent": {...} }
}
```
`action` 与 `GuardActionRequest.Action` 同一记录类型与校验。策略 module/function 不由调用方给出，取自链上评估事件行的 `module_name` / `function_name` 列，保证决策地址与链上事实一致，cloud 也无需解析源码。`correlationId` 必填且 ≤64 字符，否则 400 `invalid_action`。

处理（不开外层事务：存储层每步自开独立事务，唯一索引冲突时才能干净地回读先到者）：
1. 签名内用户为空白或 `anonymous` → 400 `user_required`。
2. 幂等：`guard_decisions` 新列 `evidence_correlation_id VARCHAR(64)`，唯一索引 `(tenant_id, evidence_correlation_id) WHERE evidence_correlation_id IS NOT NULL`；已存在 → 200 回放既有决策的**当前**审批状态（与 Idempotency-Key 重放同一语义，`action` 不比对）。
3. `AuditLog.findByCorrelationIds(tenant, [correlationId])` 取事件，只认 `event_type = POLICY_EVALUATION`；无（含跨租户、非评估事件）→ 404 `evidence_not_found`。
4. 解析 metadata；metadata 缺失或无法解析、无 `outcome`、或 `outcome ∉ {REQUIRE_APPROVAL, ESCALATE}` → 409 `evidence_not_approvable`（响应体 `outcome` 为字符串，取不到时为 `"null"`）。
5. 建 `guard_decisions`：outcome/reason/required_role/rule_id/controls 取自事件 metadata（`role`、`reason` 键见 §3.2）；事件无 `role` → `required_role = null`；`receipt_audit_id = 事件 id`、`receipt_hash = current_hash`；`requested_by = performedBy()`（签过的用户）、`requested_api_key_id = null`；`policy_version_id` 取事件 metadata 的 `policyVersionId`（ADR 0041 本轮未写该键，实际为 null），响应的 `policyVersion` 恒为 null。
6. 同一保存内建 `guard_approvals`（PENDING，TTL 同 ADR 0040 `aster.guard.approval-ttl`）。并发同一 correlationId 时唯一索引让后写者失败，捕获后回读先到者的决策返回。
7. 响应 `GuardDecisionResponse`（同 `POST /guard/decisions`，`receipt` 指向决策收据行）。

from-evidence 决策同样写 ACTION_GUARD_DECISION 收据行（metadata 含 evidenceCorrelationId），以便收据查询与证据导出按 decisionId 取回审批（ADR 0044 验收发现）；`receipt_audit_id` / `receipt_hash` 与响应 `receipt` 指向该决策行，写入失败按 DENY `receipt_unavailable`。审批事件（ACTION_GUARD_APPROVAL）照旧发布，`decision_id` 列照旧投影。

### 3.2 评估事件补两个键

`EvaluationEvidence` 增加 `role`（Verdict 的角色，可空）与 `reason`（Verdict 的原因，截断 255），扁平键 `role` / `reason`，三条 `/evaluate*` 路径都带。guard 收据的 `reason` 键已存在，保持。ADR 0041 §2.2 的键表随本 ADR 修订。

### 3.3 不做

- 不按源码重新评估（用户否决：双事件、双 correlationId）。
- 不接受 cloud 断言的 Verdict（用户否决：链上无可校验来源）。

## 4. 决策三：内部 HMAC v3 与 cloud 调用面

### 4.1 canonical v3

```
method \n path \n query \n ts \n nonce \n sha256(body) \n tenant \n role \n userId \n businessRoles
```
- `query`：原始查询串（不含 `?`，按发送时原样，不重排不解码），无则空串。
- `userId`：`X-User-Id` 原值，无则空串。
- `businessRoles`：`X-User-Business-Roles` 逗号切分、trim、去空、去重后按 UTF-16 码元排序（cloud 用无比较器的 `sort()`，api 用 `String` 自然序），以 `,` 拼接，无则空串；头里的顺序不影响签名。
- 不加版本头：v3 只体现在 canonical 的行数上，旧签名一律 `invalid_signature`。
- 新增头 `X-User-Business-Roles`（逗号分隔）。

api 侧 `InternalCallerFilter` 验签与 nonce 校验都通过后写请求属性 `aster.identity.userId`（签过的 userId，可为空串）与 `aster.identity.businessRoles`。租户已由 `X-Tenant-Id` 签入并经既有租户链路生效，不另设身份属性。HMAC 调用者的未签头一律忽略：`performedBy()` 顺序为 API key 属性 userId → 签名 userId（属性存在即终态，空白返回 `anonymous`，不再回退读头）→ 头；`businessRoles()` 只取属性。

参考向量（两仓测试共同钉住：api `InternalCallerFilterTest`、cloud `api-signing-internal.test.ts`）：key `s2-1a-0-characterization-key-32b!`，`GET /api/v1/guard/approvals`，query `status=PENDING&page=0&size=50`，ts `1760000000`，nonce `n-fixed-1`，tenant `team1`，role `member`，userId `u-1`，roles `DPO,CISO` → `1cc07890ca7a3034865add962bb84dd1a993a33f1cd6593669e0f7b4928937ba`。

v2 调用方（`/evaluate-source`、`/whatif-batches`、`/api/v1/audit/receipts`）一次切到 v3，不并存：cloud 与 api 同窗口发布（顺序见 §8）。cloud → runner-launcher 的签名是独立协议（独立 key、7 行 canonical），不在本次升级范围。

### 4.2 对 HMAC 开放的 guard 路径

`ApiKeyAuthFilter` 的 Bearer 旁路从单一路径改为集合：精确路径 `/api/v1/audit/receipts`、`/api/v1/guard/decisions/from-evidence`、`/api/v1/guard/approvals`，前缀 `/api/v1/guard/approvals/`、`/api/v1/guard/decisions/`；不含 `POST /api/v1/guard/decisions` 本身（Agent 提交决策只走 Bearer）。命中路径本身不放行任何请求，始终以 `InternalCallerFilter.isHmacVerified` 为前提。from-evidence 仅限 HMAC，Bearer 调用 403 reason `internal_only`；其余 guard 读与审批路径 HMAC 或 Bearer 均可。

### 4.3 approvals 列表项扩展

列表项 `GuardApprovalView` 字段（null 不写出）：`id, decisionId, status, outcome, requiredRole, principalId, createdAt, expiresAt, decidedAt, decidedBy, comment`，并从关联决策合入 `policyModule, policyFunction, actionName, resourceType, resourceId, ruleId, controls, reason, evidenceCorrelationId`（列表查询 join 决策；决策行缺失时这些字段全空；空串的 resource 字段省略）。`controls` 防御性解析：单行 JSON 损坏记 warn 并省略该字段，不让整页 500。SDK `GuardApproval` 类型同步为可选字段，旧字段不变。

### 4.4 cloud `PolicyApiClient`

新增 `guardFromEvidence(req)`, `getGuardDecision(id)`, `listGuardApprovals(status, page, size)`, `approveGuard(id, comment?)`, `rejectGuard(id, comment)`，全部走 v3 签名，统一 8 秒超时（`GUARD_TIMEOUT_MS`），路径 id 在本地先校验再拼 URL。`createPolicyApiClient` 保持位置参数（13 个既有调用方），新增第 5 参 `businessRoles: string[] = []`；新增 `createPolicyApiClientForUser(tenantId, userId)` 在服务端实时 `loadBusinessRoles` 后装配，不缓存。`PolicyApiError.code` 取响应体 `code`，缺省回退到响应体 `error`（api guard 错误体只有 `error`）。

## 5. 决策四：cloud 执行写 guardDecisionId 与收件箱

### 5.1 执行路径

两条执行路由（dashboard 与 v1）在 `deriveExecutionDecision ∈ {require_approval, escalate}` 时：
1. 执行行先落库（`metadata` 为 null），再登记 guard，最后回写 `metadata`：api 挂起或实例被回收都不会丢执行行及其链锚，最坏只是 metadata 仍为 null。插入失败则不登记。
2. 登记前校验执行人属于策略租户：个人策略只有所有者本人，团队策略须有该团队的成员行；否则 `guardError: not_tenant_member`，不开决策、不发通知（公开/共享策略的外部执行人不得向他人租户写待审批）。
3. 以当前身份（dashboard：会话用户与其在该策略租户的业务角色；v1：key 持有者）调 `guardFromEvidence({ correlationId: metadata.evidenceCorrelationId, action })`。映射：`principal.id` 恒为执行用户（api 四眼同时拦 `decidedBy == principalId`，若允许输入指定会让执行人可以锁死某位审批人；输入的 `principal_id` 仍随整个输入留在 `context`）；`principal.type/roles` = 输入 `principal_type`（缺省 `user`）/ `principal_roles`；`action.name` = 输入 `action` 或策略函数名；`resource` = 输入 `resource_type/resource_id` 或空串；`context` = 输入对象；`agent` = 执行声明的 agent。
4. 成功 → `executions.metadata = { guardDecisionId, guardApprovalId }`；失败（网络、4xx、5xx）→ `metadata = { guardError: <code> }`（api 错误码优先，其次 HTTP 状态，其余 `client_error`），执行行照常，不抛错。
5. 日志页对 `guardError` 行提供「重新登记」按钮：`POST /api/policies/[id]/executions/[execId]/guard-register`，同一映射再调一次并更新 metadata；不需要审批的行 409 `not_approvable`，无证据锚的行 409 `no_evidence`（重试永远不会成功，不改写行）。

`evidenceCorrelationId` 为空（simulate、旧 api）时不调 guard，记 `guardError: no_evidence`。

### 5.2 收件箱

- 页面 `/[locale]/(dashboard)/approvals`；侧边栏项「审批」；`?status=`（默认 PENDING）、`?decisionId=` 定位高亮。
- 服务端 `listUserApprovals(userId, status)`：租户集合 = `{userId} ∪ 用户所有 teamId`；每租户只查一次业务角色，以该用户身份（含该角色集合）调 `listGuardApprovals`，并发 4、单租户 8 秒超时，单租户失败标「不可用」不影响其他；合并按 `createdAt` 倒序；每项附 `canAct`：`status=PENDING` 且（`requiredRole == null` 或 `requiredRole ∈ 该租户业务角色`），与签名下发的角色出自同一快照。
- 可见性：成员可见所在租户全部项；owner/admin 无角色时 `canAct=false` 只读。
- 操作：`POST /api/approvals/[tenantId]/[approvalId]/approve|reject`（body `{comment}`，reject 必填）→ `approveGuard/rejectGuard`；api 的 403 `segregation_of_duties` / `role_mismatch`（附 `verifiedRoles`）、409 `approval_not_pending` 原样映射到 UI 文案；api 408/5xx、客户端超时或网络错误统一 502 `upstream_unavailable`。
- 四眼不在 cloud 重复判定。
- cloud 迁移 0052：`Execution` 上 `metadata->>'guardDecisionId'` 的表达式部分索引，供审批已决通知按决策反查执行行。

### 5.3 通知

- `NotificationKind` 新增 `guard.approval_requested`（给该租户持有 requiredRole 的成员；ESCALATE 给全部成员；个人租户给本人）与 `guard.approval_decided`（给发起执行的用户：按 `guardDecisionId` 反查执行行，且校验该执行的策略租户与审批租户一致）。payload：`{ tenantId, decisionId, approvalId, policyName, requiredRole, outcome? }`。
- 触发点：`from-evidence` 成功后、approve/reject 成功后；经 `after()` 在响应后发送，请求作用域外（单测、脚本）退化为 fire-and-forget；失败只记日志（沿用 `createNotification`）。
- 不发邮件。

### 5.4 日志页

带 `metadata.guardDecisionId` 的行显示「待审批 · 查看」链接到 `/approvals?decisionId=`；不实时拉状态。

## 6. 数据流

```
dashboard 执行 ──/evaluate-source(v3)──▶ api：评估事件(correlationId, outcome, role, …) 上链
      │ 执行行落库(metadata=null)；outcome ∈ {REQUIRE_APPROVAL, ESCALATE} 且执行人属于策略租户
      └─/guard/decisions/from-evidence(v3, userId, roles)──▶ api：读链上事件 → guard_decisions(+evidence_correlation_id) + guard_approvals(PENDING)
                                                      ◀─ decisionId ─┘
executions.metadata.guardDecisionId ← ；通知 guard.approval_requested → 角色持有者
收件箱 ──/guard/approvals(v3)──▶ 列表（canAct 由 cloud 按角色计算）
      ──/guard/approvals/{id}/approve(v3, userId, roles)──▶ api：角色 ∈ 已验证集合、四眼 → APPROVED → ACTION_GUARD_APPROVAL 上链
证据导出（ADR 0041）──/audit/receipts?decisionIds=──▶ approvals → reviewers[guard-approval, roleVerified=true]
```

## 7. 失败与边界

| 情形 | 行为 |
|---|---|
| from-evidence 时链上事件缺失 / 非评估事件 | 404 `evidence_not_found`，cloud 记 `guardError`，执行行照常 |
| 评估事件 metadata 缺失、无法解析或结论不需审批 | 409 `evidence_not_approvable`（`outcome` 为字符串），cloud 记 `guardError` |
| 同一 correlationId 重复 / 并发 from-evidence | 返回既有决策的当前状态（唯一索引保证，冲突时回读） |
| 执行人不属于策略租户 | 不调 guard，`guardError: not_tenant_member`，不发通知 |
| HMAC 签名内 userId 为空白或 `anonymous`（from-evidence / approve / reject） | 400 `user_required` |
| 角色变更后 Bearer 审批 | 快照重推成功即时生效；推送失败时 verify 缓存内最多 60 秒可能 `role_mismatch`，UI 提示刷新 |
| HMAC 调用者伪造 X-User-Id/角色头/query | canonical 校验失败 403 `invalid_signature` |
| 发起人自批 | 403 `segregation_of_duties`（requestedBy 为签过的用户） |
| owner/admin 无角色点击 | UI 无按钮；直连 API → 403 `role_mismatch`（`verifiedRoles: []`） |
| 某租户 api 不可达 | 收件箱该租户分组标「不可用」，其他租户正常 |
| 审批操作时 api 408/5xx/超时 | cloud 502 `upstream_unavailable` |
| 业务角色含非 ASCII 或逗号 | 入库 400 `invalid_business_role` |

## 8. 兼容性与发布

- 破坏性：HMAC v2 → v3 一次切换；SDK 的 `businessRole` 选项继续发送头但不再被信任（README 更新）；`GuardApprovalView` 只增字段。
- 迁移：api V6.29.0（`guard_decisions.evidence_correlation_id` + 唯一索引，纯增量）；cloud 0051（两列 `businessRoles`）、0052（`Execution` 表达式索引）。
- 发布顺序：
  1. cloud 迁移 0051/0052 先于新 cloud 镜像执行——verify、快照与成员查询都会 select `businessRoles` 列，列不存在时新镜像会失败。迁移本身对旧镜像无害。
  2. api 与 cloud 在同一维护窗口发布，先 api（V6.29.0 随启动执行）后 cloud。旧 cloud 不下发 `businessRoles` 时 api 按空集合处理（审批返回 `role_mismatch` 而非 500）。
  3. api 发布后即只校验 v3：窗口期间旧 cloud 的 v2 签名一律 403 `invalid_signature`，`/evaluate-source` 与 guard 调用都 fail-closed（dashboard 执行失败、不写脏数据），Bearer 路径不受影响。窗口按分钟计，可接受；不做 v2/v3 并存（用户已拍板）。
- 部署约束：生产 api 必须开启 API key 认证或走 HMAC（§2.2）。
- 旧 `guard_approvals` 行无 `evidence_correlation_id`，不受影响。

## 9. 测试与验收

- api：`InternalCallerFilterTest` v3 canonical 与跨仓参考向量；篡改 query / userId / roles 各 403；`GuardFromEvidenceResourceTest`（HmacProfile）：建决策、409、404、幂等、并发回读、`user_required`、Bearer 403；`GuardApprovalServiceTest` 集合匹配、头伪造无效、ESCALATE 任意成员、`user_required`；`ApiKeyVerifierServiceTest` 解析 `businessRoles` 与缺省；`EvaluationEvidenceTest` 新键。
- cloud：`normalizeBusinessRoles` 单测；成员 PUT 路由与 `PUT /api/user/business-roles` 权限、校验与快照重推；verify/snapshot 带角色；执行路由调 from-evidence、租户成员校验与失败降级；重新登记路由；`listUserApprovals` 合并、超时与 `canAct`；approve/reject 路由错误映射；收件箱组件三种身份按钮可见性；通知写入；全门禁。
- 栈验收（Task 末）：m-dpo 授予 "Data Protection Officer"；m-free 执行示例策略 → 日志待审批带链接；m-dpo 收件箱批准 → `guard_approvals` APPROVED、decidedBy=m-dpo；导出证据包 reviewers 含 `guard-approval` 且 `roleVerified=true`；m-free 自批 403 四眼；无角色成员无按钮；SDK 以自报头审批 → 403 `role_mismatch`。

### 9.1 验收结果

2026-10-09 本地栈（aster-api + aster-cloud + aster-guard）：

1. 在 cloud 为 DPO 授予业务角色并重推快照 → aster-guard e2e 4/4 通过，审批方脚本自报的业务角色头被忽略；撤销该角色后审批 → 403 `role_mismatch`，`verifiedRoles: []`。
2. v1 执行团队策略 → 经 from-evidence 写入 `Execution.metadata.guardDecisionId` / `guardApprovalId`；收件箱：角色持有者 `canAct=true`，发起人与无角色 owner `canAct=false`；发起人自批 403 `segregation_of_duties`；owner 审批 403 `role_mismatch`；DPO 批准 → APPROVED，`guard.approval_requested` 通知已送达。本条通过服务端 helper 驱动，未经浏览器会话。
3. 停掉 api 后执行 → 执行记为 `error`（评估本身失败，无需审批）；guard 登记失败的降级路径由单元测试覆盖。
4. 以 v2 canonical 签名调用 `/evaluate-source` → 403 `invalid_signature`。

## 10. 后续（不在本 ADR）

- 邮件/webhook 通知（ADR 0040 §10）。
- 审批通过后自动重放执行。
- MCP 端把 `businessRole` 提示改为读取 verify 角色。
- waitSeconds 线程占用、MCP 60 s 超时（ADR 0040 §10）。

实施期遗留：

- `guardError: not_tenant_member` 的执行行在日志页仍显示「重新登记」按钮，重试必然再次失败；应按错误码隐藏。
- 收件箱 UI 对 502 `upstream_unavailable` 没有专门文案，落入通用错误提示。
- 通知依赖的 `after()` 在 OpenNext/Cloudflare 运行时的行为未验证（请求作用域外的回退已有单测，生产运行时未实测）。
- `InternalCallerFilter` 在 nonce 校验之前就写入 `HMAC_VERIFIED_PROP`：无害——重放请求随即被 nonce 校验以 409 拒绝，且身份属性只在 nonce 通过后写入。
- api `PolicyGraphQLResourceTest` 的 LargeVolume 用例引发的连带超时是既有测试套件问题，与本 ADR 无关。
