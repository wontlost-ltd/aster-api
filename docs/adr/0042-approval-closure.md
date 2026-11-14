# ADR 0042：审批闭环 —— 经验证的业务角色、从证据开决策、cloud 审批收件箱（AI 治理方向·子项目 3b）

- 状态：**ACCEPTED**（2026-10-09）；§11 R59 ACCEPTED（2026-11-08）；§12（R59 实施前定稿修订，#460）**PROPOSED**（2026-11-14）
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

## 11. 2026-11 修订：审批视图携带脱敏后的决策输入与输入快照哈希（R59）

状态：**ACCEPTED**（2026-11-08 用户确认；实施计划 docs/superpowers/plans/2026-11-08-guard-receipts-and-input-provenance.md）。定稿后与正文冲突时以本节为准。
相关：wontlost-ltd/aster-api#460（EU AI Act Art.14 人工监督：审批人须能理解输入）；ADR 0040 §3.3 / §5.2（不存明文 context）、ADR 0041 §5.2（`reviewers[]`）、ADR 0044 §3.1（14(4)(a)/(b) 判定）、aster-api ADR 0015「已锁决策」（租户隔离铁律、visibility-safe 不泄露存在性）、ADR 0040 §11 R60（字段来源，与本节同车次评审）。

### 11.1 现状（已核对 origin/main 772855f）

- `src/main/java/io/aster/guard/api/GuardApprovalView.java:18-22`：审批列表项只有 `actionName / comment / controls / createdAt / decidedAt / decidedBy / decisionId / evidenceCorrelationId / expiresAt / id / outcome / policyFunction / policyModule / principalId / reason / requiredRole / resourceId / resourceType / ruleId / status`，没有任何输入值。
- `GuardDecisionEntity.java:35` 只有 `context_hash`；ADR 0040 §3.3（行 207）与 §5.2（行 337）明确「不存明文 context」，`GuardReceiptBuilder.java:29`「明文 context 绝不进入收据」。所以审批人今天**不可能**看到输入：数据根本没有落库，不是视图没暴露。
- `GuardApprovalService.java:77-95` `decideApproval` 只写 `decidedBy / decidedAt / comment`；审批事件 metadata（`GuardReceiptBuilder.java:112-125`）无任何输入引用。审批记录因此证明不了「审批人看过什么」。
- cloud 收件箱 `src/app/[locale]/(dashboard)/approvals/approvals-content.tsx:80-88` 只渲染 `policyModule.policyFunction`、`actionName → resource`、`reason`；`src/lib/approvals-inbox.ts:25` `InboxItem = GuardApprovalItem & {tenantId, tenantName, canAct}`。
- 证据包复核者 `src/services/evidence/reviewers.ts:16-24` 七字段（ADR 0041 §5.2），无输入引用；manifest 当前 `schemaVersion '5'`（`types.ts:113`）。
- 编译期 PII 标记已存在：core `CoreModel.PiiType`（`aster/core/ir/CoreModel.java:252-257`：`baseType / sensitivity(L1–L3) / category`），ts `Core.PiiType`（`src/types.ts:736-742`），源码语法 `@pii(L2, email) Text`（`src/types.ts:486`）；E070/E072/E073、E400–E404 由 `PiiTypeChecker` 报出。已部署版本的 Core IR 存于 `policy_versions.core_json`（`PolicyVersion.java:279-281`），aster-api 可以从中读出规则输入记录各字段的 `PiiType`，不需要引擎改动。
- 运行期脱敏只有模式识别：`AuditChainAppender.java:201-207` 对收据顶层 String 值用 `PIIRedactor`（SSN / 邮箱 / 电话 / 信用卡 / IP 正则），不是字段级，也不知道业务含义。

### 11.2 决策

**D1 存输入（只存需要人看的）。** 新表 `guard_decision_inputs`（Flyway `V6.37.0`）：`decision_id UUID PK → guard_decisions.id`、`tenant_id`、`action_json JSONB`（请求的 `GuardActionRequest.Action` 原样：principal / action / resource / context / agent；上界与 ADR 0040 §2.2 一致，context ≤ 64 KB）、`context_hash`（与 `guard_decisions.context_hash` 同值，读取时复核）、`pii_marks JSONB`（决策时算出的字段标记快照，D2）、`created_at`。

- 只对 `outcome ∈ {REQUIRE_APPROVAL, ESCALATE}` 的决策写入：ALLOW / DENY 没有人要看，ADR 0040 §3.3「决策行不存明文 context」对这两类结论维持原状。
- 与决策行、审批行写在**同一个** `requiringNew` 事务里（ADR 0040 §3.3 行 214 的原则）：不会出现「有待审批却没有输入」的半写状态。`from-evidence` 决策同样写入（`action` 来自 cloud 以 HMAC v3 签过的请求体，§3.1）。
- **不进审计链**：链上仍只有 `contextHash`（4 KB 预算，ADR 0040 §5.2）。输入行的真实性由 `context_hash` 复核：读取时重算 `sha256-sorted-json/v1`（`context_hash` 保持该配方，**不**随 ADR 0040 §12 D2 改用 `jcs-sha256/v1`：后者只用于 `actionHash` 与签名载荷，改旧哈希会让既有决策行全部判为不一致）与决策行比对，不等 → 视图状态 `integrity_failed`，不展示。
- 保留期：审批到达终态（APPROVED / REJECTED / EXPIRED）后 `aster.guard.input-retention=${ASTER_GUARD_INPUT_RETENTION:P90D}` 到期由与 `GuardApprovalExpiryScheduler` 同构的调度器删除输入行（`guard_decisions` 不动）；删除后视图状态 `purged`。PENDING 期间不删。

**D2 字段级脱敏规则（默认打码，按已验证业务角色解除）。**

标记来源：决策时读该版本 `core_json`，取 `policyFunction` 第一个参数的记录类型（ADR 0040 §2.2 的 `GuardRequest`），对其每个字段，以及当 `context` 字段声明为具名记录类型时该记录的每个字段（递归到嵌套记录），收集 `PiiType` 的 `sensitivity / category`，得到 `pii_marks: [{ "path": "context.email", "level": "L2", "category": "email" }, …]`。规则：

| 输入路径 | 处理 |
|---|---|
| `principal.id / principal.type / principal.roles / action.name / resource.type / resource.id / agent.*` | **明文**。它们是身份与结构键，已经明文进收据（ADR 0040 §5.2 行 335），列表项今天就带 `principalId / actionName / resourceId` |
| `context.<path>` 在记录类型里声明且**无** `@pii` | 明文 |
| `context.<path>` 声明为 `@pii(level, category)` | 打码：值替换为 `{ "$pii": { "level": "L2", "category": "email" } }`，不给长度、不给前后缀 |
| `context.<path>` **未在记录类型里声明**（含 `context` 本身只声明为 Map 的现状） | 打码：`{ "$pii": { "level": "unknown" } }`。未经 PII 分析的键不能证明可看，fail-closed；与 E404「分析失败即不可部署」同精神 |
| 任何仍为明文的 String 值（**任意深度**） | 再过一遍 `PIIRedactor`（第二层，与收据同一正则集合；`AuditChainAppender.isRedactionExempt` 的身份键豁免同样适用）。代价：与 ADR 0040 §9 终审条目同类的误伤（约 1/800 的 UUID 形态 id 被信用卡正则改写）——只影响展示，不影响哈希比对 |

递归与路径规则（视图层自行实现，**不**复用 `AuditChainAppender`：后者只处理 metadata 顶层 String，`AuditChainAppender.java:201-207`）：

- 视图层对 `action_json` 做一次深度优先遍历（深度 ≤ 8，与 §2.2 上限一致），每个叶子按「JSON 实际路径」判定：对象键以 `.` 连接，数组元素以 `[]` 表示（不带下标）——`context.items[].email` 匹配 `items` 数组里**每个**对象元素的 `email`；标量数组（`context.tags[]`）作为一个叶子整体判定。`pii_marks` 的路径由 Core IR 的类型结构按同一语法生成：记录字段 → `.`，`List of <记录>` → `[]`，`List of <标量>` → 叶子。因此标记路径与值路径的语法一致，匹配是精确字符串比较，不做通配。
- 判定是对**声明类型**的匹配，不是对值的匹配：值路径在 `pii_marks` 所对应的声明树中不存在（多出的键、数组里出现了非对象元素、声明为标量却传了对象）→ 该子树整体打码 `level: unknown`；值缺失（声明了但请求没传）→ 不出现在视图里，不补空值。
- 打码作用于叶子，对象与数组的**结构**保留（审批人能看到有几项、有哪些键），只是值被 `$pii` 替换；`unknown` 子树则整体替换为一个 `$pii` 对象，连键名也不展示（未声明的键名本身可能是 PII，如以邮箱作键的 Map）。
- 第二层 `PIIRedactor` 对遍历到的每个仍为明文的 String 叶子执行，包括数组元素与嵌套对象内的字符串；非 String 叶子（数字、布尔）不经正则。

正反例（声明 `GuardContext has amount as Decimal, email as @pii(L2, email) Text, items as List of LineItem`，`LineItem has sku as Text, note as @pii(L1, free-text) Text`）：

| 请求值路径 | 结果 |
|---|---|
| `context.amount = 250000` | 明文 |
| `context.email = "a@b.cc"` | `{"$pii":{"level":"L2","category":"email"}}` |
| `context.items[0].sku = "SKU-1"`、`context.items[1].sku = "SKU-2"` | 两项均明文，数组长度可见 |
| `context.items[*].note` | 每项 `{"$pii":{"level":"L1","category":"free-text"}}` |
| `context.items[1] = "not-an-object"` | 该元素整体 `{"$pii":{"level":"unknown"}}`（与声明不符） |
| `context.extra = {"k@x.io": 1}` | 整个 `extra` 子树替换为 `{"$pii":{"level":"unknown"}}`，键名不展示 |
| `context.items[0].sku = "call 021-555-0100"` | 声明无 PII 但第二层正则命中电话 → 显示为脱敏后的字符串 |
| `principal.id = "a@b.cc"` | 明文（身份键豁免，与收据一致） |

解除打码（`reveal=true`）：仅当审批行 `status = PENDING` 且审批人**已验证**业务角色（`RequestIdentityResolver.businessRoles()`，§2.2–§2.3）包含审批行 `required_role`；ESCALATE 无所需角色 → 不可解除；owner/admin 无角色 → 不可解除（与 §5.2「管理员只读」一致）。解除只还原**已声明**的 `@pii` 字段；`level: unknown` 的键任何人都看不到原值。解除写 `guard_approvals.input_revealed_at`（首次解除时刻，幂等）。403 `reveal_not_permitted`（响应体附 `verifiedRoles`，与 `role_mismatch` 同形）。

与 ADR 0015 对齐：输入端点与审批端点同一租户隔离与 404 口径（非法 UUID 与跨租户一律 `approval_not_found`，不泄露存在性，ADR 0040 §4.2）；输入只经审批行可达，没有「按 decisionId 直接拉输入」的路径；`from-evidence` 的 HMAC 通道与 Bearer 通道规则沿用 §4.2 的旁路集合（前缀 `/api/v1/guard/approvals/` 已覆盖新路径）。

**D3 审批记录「提交时所对应的视图」快照哈希。**

`inputSnapshotHash = sha256-sorted-json/v1`（ADR 0040 §5.2 的同一配方；与 `context_hash` 一样保持该配方，不随 ADR 0040 §12 D2 改用 `jcs-sha256/v1`——它是服务端自算自比的视图哈希，不跨语言复算）作用于服务端**实际返回**的视图体 `{ decisionId, contextHash, input, piiMarks, maskRules: "v1", revealed }`；视图自带该哈希。审批时：

1. approve / reject 请求体增加可选 `inputSnapshotHash`。服务端按「审批人角色 + `input_revealed_at` 是否已置」重算当前视图哈希：相等 → `input_snapshot_matched = true`；不等 → 409 `input_snapshot_mismatch`（输入不可变，只会在脱敏规则版本变化或解除状态变化时出现，UI 提示刷新）；未提供（旧 SDK）→ 记服务端算出的哈希，`matched = false`。
2. `guard_approvals` 新列（同 `V6.37.0`）：`input_snapshot_hash VARCHAR(64)`、`input_snapshot_matched BOOLEAN`、`input_revealed_at`。
3. 审批事件 metadata 新键 `inputSnapshotHash`（身份键：加入 `AuditChainAppender.isRedactionExempt` 白名单，永不缩短）、`inputSnapshotMatched`、`inputRevealed`、`inputStatus`（`available | unavailable | purged | integrity_failed`）；`comment` 仍是唯一可缩短的键（ADR 0040 §5.2）。过期事件（SYSTEM）不写这些键。
4. **证明边界（写清，不夸大）。** `inputSnapshotHash` 证明的是：服务端在审批提交时刻、按该审批人角色与解除状态算出的视图哈希是 H；`inputSnapshotMatched = true` 进一步证明：提交审批的客户端送来了同一个 H，即**客户端持有与当前视图一致的哈希**。它**不**证明审批人本人实际阅读过该视图，也不证明提交哈希的客户端就是渲染视图给审批人的那个客户端（同一会话的脚本可以先 GET 再 POST）。审计意义上它排除的是「审批时视图与审批人所能获得的视图不一致」与「旧 SDK 根本没取过视图」两类情形；「人确实看了」属于流程与 UI 层证据（如收件箱在未展开输入面板时禁用按钮），不由本哈希承担。更强的证明（如客户端对视图签名、浏览器端留痕）需另行立项。它也不是明文输入的哈希（明文由 `contextHash` 负责）；两者并列保存，审计方可核对：输入未变（contextHash）、审批所对应的是该输入的某个脱敏投影（inputSnapshotHash + maskRules + revealed）。

**D4 接口与下游字段变化。**

| 产物 | 变化 |
|---|---|
| aster-api `GET /api/v1/guard/approvals/{id}/input?reveal=false` | 新端点（MEMBER；HMAC 或 Bearer）。响应 `GuardApprovalInputView`（字母序、null 不写）：`approvalId, contextHash, decisionId, input{principal,action,resource,context,agent}, inputSnapshotHash, maskRules, piiMarks[], revealed, status`。`status ≠ available` 时无 `input / piiMarks`。写入 `public-api-contract.golden` |
| `GuardApprovalView`（列表项） | 只增 `inputStatus`、`inputSnapshotHash`（已决且有记录时）、`inputRevealed`；**不**内嵌输入（一页 200 项 × 64 KB） |
| approve / reject 请求体 | `{ comment?, inputSnapshotHash? }` |
| 审批事件 metadata | D3 第 3 项 |
| `GET /api/v1/audit/receipts?decisionIds=` 的 `approvals[]` | 增 `inputSnapshotHash`、`inputSnapshotMatched`、`inputRevealed`、`inputStatus`（取自该事件 metadata） |
| aster-guard SDK `GuardApproval` | 增可选 `inputStatus / inputSnapshotHash / inputRevealed`；新增 `getApprovalInput(id, { reveal? })`；`approve / reject` 选项增 `inputSnapshotHash` |
| aster-cloud `PolicyApiClient` | 增 `getGuardApprovalInput(id, reveal)`（v3 签名，8 s 超时）；新路由 `GET /api/approvals/[tenantId]/[approvalId]/input?reveal=` 以当前用户身份转发 |
| cloud 收件箱 | 行展开面板显示输入：声明字段明文，`$pii` 以徽标（级别 / 类别）呈现；「显示原值」按钮仅在 `canAct && requiredRole != null` 出现，点击即调用 `reveal=true`（一次解除即留痕）；批准 / 拒绝对话框把当前展示的 `inputSnapshotHash` 一并提交；`status ≠ available` 显示对应文案（旧决策无输入 / 已清理 / 完整性失败），仍允许审批 |
| 证据包 | `Reviewer` 增 `inputSnapshotHash: string \| null`、`inputRevealed: boolean \| null`（`guard-approval` 来源取收据接口值；`policy-proof / version-approval` 为 null）。两字段进 `bundleHash`；manifest `schemaVersion: '6'`，v5 冻结只读（与 v1–v4 同法）。ADR 0044 §3.1 的 14(4)(a)：存在 `inputSnapshotHash` 非空的 `guard-approval` 复核者 → `evidenced`（原 `partial` 条件保留为次选）；14(4)(b) 仍恒 `none`——有快照不等于没有偏见 |

**D5 迁移与兼容（旧审批无快照时如何显示）。**

- 纯增量：一张新表、三个新列、一个新端点、若干可选字段；既有端点与响应形状不变，旧 SDK / 旧 cloud 可继续审批。
- 修订前的决策没有输入行 → `inputStatus: unavailable`，输入端点返回 `status: "unavailable"`（200，不是 404），收件箱显示「本决策早于输入快照功能」；审批照常允许，审批事件记 `inputStatus: unavailable`、`inputSnapshotHash` 缺省（不写键）。证据包 Reviewer `inputSnapshotHash: null`。不回填。
- 修订前已导出的 v1–v5 证据包按原样下载、不重算（ADR 0041 §5.3 既有规则）。
- `context` 仍声明为 Map 的策略：全部 context 键按 `level: unknown` 打码，审批人只看到键名与「不可见」徽标。要让字段可见或按级别打码，策略作者把 `context` 声明为具名记录（`Define GuardContext has amount as Decimal, email as @pii(L2, email) Text.` 并在 `GuardRequest` 中 `context as GuardContext`）。运行期仍传 Map（ADR 0040 §2.3 的 `allowMapAccess` 机制对嵌套记录同样成立），**此点须由探针测试先行证实**（与 §2.3 `DeployedMapParamProbeTest` 同法）；探针失败则本修订退回设计。
- 回滚：删端点与写入路径；新表、新列、新事件键留存无害。

### 11.3 验收

1. 部署带 `@pii` 字段的 guard 策略，提交 REQUIRE_APPROVAL 决策 → 输入端点：无角色成员看到 `$pii` 徽标与未声明键的 `unknown`；持 `requiredRole` 者 `reveal=true` 得到声明字段原值、`unknown` 键仍不可见、`input_revealed_at` 落库。
2. approve 带正确 `inputSnapshotHash` → `matched = true`，审批事件 metadata 含 `inputSnapshotHash / inputSnapshotMatched / inputRevealed`；带错误哈希 → 409；不带 → `matched = false`。
3. 修订前决策（测试里直接删除输入行模拟）→ `status: unavailable`，审批可完成，事件记 `inputStatus: unavailable`。
4. 篡改 `action_json`（SQL）→ `status: integrity_failed`。
5. 跨租户 / 非法 id → 404 `approval_not_found`，响应体与审批端点一致。
6. `GET /api/v1/audit/receipts?decisionIds=` 的 `approvals[]` 带新键；cloud 证据导出 `schemaVersion '6'`，`reviewers[].inputSnapshotHash` 与收据一致，14(4)(a) 为 `evidenced`。
7. 列表端点一页 200 项响应体不含输入；`PublicApiContractTest`、`MigrationGovernanceTest`、`AuditIdentifierRedactionTest`（新白名单键）通过。
8. cloud 收件箱 Playwright：无角色成员无「显示原值」按钮；DPO 解除后批准，日志页与证据包可追溯。

### 11.4 分阶段落地顺序

① 本节定稿（与 ADR 0040 §11 R60 同车次评审，字段来源徽标与 PII 徽标在收件箱同一面板）→ ② **aster-api**（探针 → `V6.37.0` → 输入落库与标记计算 → 输入端点 → approve 快照 → 事件键与收据接口 → 契约 golden）；纯 aster-api 改动，**不需要引擎发版列车**（`PiiType` 已在 Core IR 里）→ ③ aster-guard SDK（可选字段与新方法，独立版本）→ ④ aster-cloud（client、路由、收件箱面板、证据包 v6、Article 14 判定）。② 与 ④ 反序不坏：旧 cloud 不读新字段；新 cloud 对旧 api 的输入端点 404 → 收件箱按「不可用」显示。

### 11.5 已选默认与可改项

| 项 | 已选默认（定稿即生效） | 可改为 | 改动影响 |
|---|---|---|---|
| 输入快照保留期 | 终态后 `P90D`（`ASTER_GUARD_INPUT_RETENTION`） | 任意 ISO 时长，含 `P0D`（终态即删） | 只改配置；证据包不受影响（快照哈希已在链上） |
| 解除原值的角色 | 仅审批行 `required_role`；ESCALATE 不可解除 | 增加租户级「PII 查看角色」集合 | 需 cloud 团队设置与快照下发（同 businessRoles 通道），本节不做 |
| ALLOW / DENY 决策 | 不存输入 | 全量存储 | 存储量约 × 决策总数 / 审批数，须重估保留期；`guard_decision_inputs` 结构不变 |
| 审批事件 `inputSnapshotMatched` | 只表示客户端提交哈希与当前视图一致（D3 第 4 项） | — | 更强证明需另立 ADR |

另：ADR 0040 §3.3「不存明文 context」在本节后的准确表述是「决策行与审计链不存明文 context；明文只存于独立、限期、按审批可达的 `guard_decision_inputs`」，定稿时回写 ADR 0040 §3.3 行 207 与 §5.2 行 337 的脚注。

## 12. 2026-11 修订：R59 实施前定稿 —— 审批输入快照落地口径、`catalog.piiMark` 再定义、证据包 v6（#460）

状态：**PROPOSED**（2026-11-14）。实施计划 `docs/superpowers/plans/2026-11-14-approval-input-snapshot.md`。定稿后与 §11 冲突时以本节为准；被取代的 §11 条款逐条列在 §12.3，未列出的 §11 条款不变。
相关：wontlost-ltd/aster-api#460；ADR 0046 §11.7 第 2、3 条（`catalog.piiMark` 与证据包 v6 `factSources` 推迟到 #460）；ADR 0041 §11 D3/D4、§12 D3（证据包 v6 的其余内容）；ADR 0040 §3.3 / §5.2（决策行与审计链不存明文 context）、§11 D4（审批视图并列 `inputSources` 与 `factsStale`）；ADR 0044 §3.1 与 ADR 0045（Article 14 对照由控制注册表驱动）；aster-api ADR 0015「已锁决策」（租户隔离、visibility-safe 不泄露存在性）；ADR 0055（Core IR v2 保留 `PiiType`，只删 `Func.piiLevel / piiCategories`）。

### 12.0 为什么是本 ADR 的新节，而不是新 ADR

#460 的设计主体（存什么、怎么打码、审批记录什么）已经是本 ADR §11（R59，用户 2026-11-08 确认），ADR 0041 / 0046 也都以「ADR 0042 §11（#460）」作为这几项的唯一指向。本节只做三件事：按今天的 main 复核 §11 并修正走不通的条款，给两项推迟事项（`catalog.piiMark`、证据包 v6）下定义，补上 §11 没写的尺寸、存储与 UI 细节。新开 ADR 会让同一个决策分在两份文档里，各方引用要全部改写；做法与 ADR 0046 §11.6 / §11.7 相同。

### 12.1 复核结论（origin/main `749fa44d`）

| # | 发现 | 证据 | 影响 |
|---|---|---|---|
| F1 | §11 已 ACCEPTED，一行代码都还没写；`V6.37.0` 仍空（最新迁移 `V6.36.0__guard_input_provenance.sql`） | `src/main/resources/db/migration/` | 迁移号仍可用；须与 #464/#465 按合并顺序取号 |
| F2 | 生产中唯一产生审批的路径是 cloud 的 `/evaluate-source` + from-evidence；aster-api 生产 `policy_versions` 为 0 行（运维观测：2026-11-13 1.0.44 列车 gate scan 与上线冒烟记录，非仓内可证事实）。§11 D2「决策时读该版本 `core_json`」对生产的每一个审批都取不到标记 → 全部 `context` 键按 `unknown` 打码 → 审批人看不到任何业务值，#460 的目标落空 | from-evidence 决策 `policyVersionId` 来自事件，evaluate-source 事件不写（`EvaluationEvidence` 类注释）；执行器在评估时持有编译出的 `CoreModel.Module`（`DynamicCnlExecutor` 内 `CompiledCoreIr.coreModule`，`replay/…/DynamicCnlExecutor.java:143-150`），并在 `ExecutionResult.of(…)`（`:135-141`）由它算出 `RuleMetadata` 的各值；`ExecutionResult` 记录本身只带这些值，不带 Module——标记须在同一处算出并加入该记录（计划 Task A1） | 标记必须在**评估时**由 aster-api 推导并保存（D2） |
| F3 | from-evidence 的 `action.context` 由 cloud 给出，与被评估的输入没有绑定：§3.1 第 2 步「`action` 不比对」，评估事件也没有输入哈希 | `GuardEvidenceDecisionService.createFromEvidence`；`EvaluationEvidence` 键表 | 审批人看到的「输入」可能不是策略实际评估的输入（D1 绑定校验） |
| F4 | §11 D2 把解除记在审批行的单列 `input_revealed_at`，D3 按它算视图哈希：A 解除后，没解除的 B 审批也会按「已解除」记录；而「谁看过原值」根本没有记录 | §11 D2 末段、D3 第 1 项 | 解除改为按人记录并入链（D5） |
| F5 | §11 D4 用 `GET …/input?reveal=true` 解除——有写副作用（留痕）的 GET | §11 D4 首行 | 改为 POST（D8） |
| F6 | §11 D4 把 14(4)(a) 改判写成 cloud 代码改动，但条款判定由控制注册表**数据**驱动（`regulatory-mapping.ts` `judge`；`controls-registry.json:19` 中 14(4)(a) 为 `evidenced: []`、`partial: ["whatIf.comparable"]`）。改判需要新的证据来源名，再加一次注册表升版（locales 真相源 + core / ts / cloud 副本，走 ADR 0053 列车）。§11 还按「`inputSnapshotHash` 非空」判 evidenced；旧 SDK 不取视图时服务端照样记哈希（`matched=false`），这样会把没看过输入的审批算成证据 | cloud `src/services/evidence/regulatory-mapping.ts`、`control-registry.ts` | 判定条件改为 `matched === true`；注册表改判与 #460 解耦（D9） |
| F7 | 键名分歧：ADR 0041 §11 D3 写 `inputSnapshotAttested`，本 ADR §11 写 `inputSnapshotMatched` | 两处原文 | 以 `inputSnapshotMatched` 为准，定稿时回写 0041 |
| F8 | cloud 证据包仍为 v5（`types.ts:113`），ADR 0041 §11 D4（`inputSource` / `guardInputSources`）与 §12 D3（`execution`）都没进包，三者都约定与 #460 一次升版 | cloud `src/services/evidence/` | v6 一次装齐（D9） |
| F9 | cloud 执行日志页对能看到该策略的成员明文展示 `Execution.input` | cloud `policies/[id]/logs/logs-content.tsx:826` | 只在收件箱打码，兑现不了「无权角色看不到原值」；本节限定验收面并另开 issue（D11） |
| F10 | `AuditChainAppender` 已豁免规范 UUID（`AuditChainAppender.java:84,212`），§11 D2「约 1/800 UUID 被信用卡正则误伤」已不成立；全局 `PIIResponseFilter` 只改写 `String` 实体 | `PIIResponseFilter.java` | 视图必须以对象实体返回，否则解除后的原值会被全局正则再改写一遍（D8 测试钉住） |

### 12.2 决策

**D1 快照什么、何时（修订 §11 D1）。**

- 存储表、写入时点与范围不变：`guard_decision_inputs`，只对 `outcome ∈ {REQUIRE_APPROVAL, ESCALATE}` 的决策写，与决策行、审批行同一 `requiringNew` 事务写入，写入后不可变（无 UPDATE 路径）；`context_hash` 在读取时复核（§11 D1）。门 DENY（`unverified_input` / `input_refs_unavailable`）与 `receipt_unavailable` 不建审批，也不写输入行。
- §11 D1 的 `pii_marks` 列改名为 `classification JSONB NULL`，存 D2 的声明树与标记（`{declared, marks}`）；新增两列：`marks_source VARCHAR(16) NOT NULL`（`version | evaluation | none`，D2）、`input_binding VARCHAR(16) NOT NULL`（`direct | evaluated | unbound`）。`direct` 用于 `POST /decisions`：请求的 `action` 就是被评估的输入。`evaluated` 用于 from-evidence：已与评估时的输入哈希核对一致。`unbound` 用于 from-evidence 但无从核对的情况（见下）。
- **评估期分类行（新表，同一迁移）** `guard_evaluation_classifications`：`tenant_id VARCHAR(64)`、`correlation_id VARCHAR(64)`、`input_hash VARCHAR(64) NULL`、`classification JSONB NULL`、`created_at`，主键 `(tenant_id, correlation_id)`。三条 `/evaluate*` 路径在**非 simulate** 且结论 ∈ `{REQUIRE_APPROVAL, ESCALATE}` 时，于评估事件发布之后写入：
  - `input_hash` = `sha256-sorted-json/v1`（与 `context_hash` 同一配方，`GuardActionMapper.contextHash`）作用于请求的 `context`，仅当 `context` 是 JSON 对象（命名格式）时写；位置格式（数组）为 null。
  - `classification` = 用本次评估实际使用的 `CoreModel.Module` 推导的声明树与标记（D2）；推导失败或超上限为 null。
  - 写入语义：`INSERT … ON CONFLICT DO NOTHING`，**先写者为准、绝不覆盖**。correlationId 是每次评估新生成的随机 UUID，同键第二次写入只可能是缺陷或重放攻击，不能让它改写 from-evidence 依据的绑定与标记；冲突计入同一 counter。
  - 写入失败只记 WARN 与 counter `aster_guard_classification_write_failed_total`，评估响应与事件不变——分类行不是评估结果的一部分，缺了只会让后面按 fail-closed 降级。
  - 保留：`created_at` 起 `ASTER_GUARD_CLASSIFICATION_RETENTION`（缺省 `P7D`），到期无条件删除。from-evidence 在执行后立即调用；「重新登记」晚于保留期时按缺行处理。
- **from-evidence 绑定校验**（插在 §3.1 第 4 步之后、建决策之前）：读本租户该 `correlationId` 的分类行。行存在且 `input_hash` 非空 → 要求 `contextHash(action.context) == input_hash`，否则 409 `evidence_input_mismatch`（不建决策、不写收据、**不消耗**该证据，修正后可用同一 correlationId 重试）。行不存在或 `input_hash` 为 null → `input_binding = unbound`，照常建决策。审批人在视图里看得到「未核对」（D10）。
- 不另存「评估输入」副本：绑定是对哈希的核对，输入明文只存一份（`guard_decision_inputs.action_json`）。

**D2 PII 标记的来源（取代 §11 D2「标记来源」段）。**

唯一来源是 aster-api 从**本次评估实际执行的** Core IR 推导，按决策的产生路径取：

| 决策路径 | 推导时点与来源 | `marks_source` |
|---|---|---|
| `POST /api/v1/guard/decisions` | 决策时，用与评估同一个已解析版本的 `core_json`；与 `RuleMetadata` 同一版本键、同一 memo（每版本只推导一次） | `version` |
| from-evidence（锚定任何 `/evaluate*` 事件） | 评估时，由执行器持有的 `CoreModel.Module` 推导，经 D1 分类行带到 from-evidence | `evaluation` |
| 以上都取不到（分类行缺失 / 已过保留期 / `classification` 为 null / 位置格式输入） | — | `none`：全部 `context.*` 叶子按 `level: unknown` 打码 |

推导器（新类 `InputClassifier`，与 `RuleMetadata` 同在 `replay` 模块的 `io.aster.policy.governance` 包）：

- 输入：`CoreModel.Module`、被执行函数名，以及路径形状。guard 形状用第一个参数的记录类型，字段名按 `GuardInputRefPaths` 的常量键表映射成请求路径（`context` 字段 → `context.*`）。证据形状对**每个**已匹配到命名键的参数取 `context.<匹配键>` 前缀。未匹配到键的参数不产生标记，它下面的值按 `unknown` 处理。
- 输出：`List<PiiMark>`，`PiiMark(path, level, category, source)`，`level ∈ {L1, L2, L3}`；`source` 本期恒为 `"ir"`（为 D3 的预留扩展保留取值 `"catalog"`）。路径语法与 §11 D2 相同（记录字段 `.`，`List of 记录` `[]`，`List of 标量` 为叶子）。
- 无标记的已声明字段也要记录，否则「已声明无 PII」和「未声明」无法区分。所以推导器同时输出**声明树**：每个已声明叶子路径，加上它的 `PiiMark`（可无）。存储形状为 `{ "declared": ["context.applicant.amount", …], "marks": [PiiMark…] }`，`declared ⊇ marks.path`。
- 上限：声明叶子 ≤ 512 条，类型展开深度 ≤ 8（与 `MAX_CONTEXT_DEPTH` 一致）；超出任一上限整体返回 null（→ `none`，fail-closed）。递归类型按引擎类型嵌套上限 64 已在编译期拒绝；推导器另以「已展开类型名集合」防环。
- `/evaluate-json`（调用方提交 IR）同样推导。`@pii` 注解在三条路径里都是作者声明的，作者本就掌控源码，所以不比 `/evaluate-source` 更不可信；`RuleMetadata.inputRefs` 不信 IR（ADR 0046 §11.6 第 19 条），是因为它可以从规则体重算，而类型注解没有第二来源。

**D3 `catalog.piiMark` 再定义（落实 ADR 0046 §11.7 第 2 条）。**

- **结论：撤销 `catalog.piiMark` 作为签名事实来源函数。** PII 分类描述的是输入 schema，不是输入值。按 ADR 0040 §11 D3，事实是「签发方对 `action` 中某个**值**的担保」，`claims` 路径必须在 `action` 里存在且值深度相等；分类没有这样的值可比，原写法因此必然 `claim_path_absent`。分类的权威来源是策略类型注解，而做评估的 aster-api 自己就能从它实际执行的 IR 推导（D2）。让 cloud 签一份验签方在同一次评估中自己算得出的东西，不增加任何来源保证，还会让标记依赖租户是否启用了 cloud 签发方（ADR 0046 §11.7 第 4 条）——没启用就全部 `unknown`。ADR 0046 §11 D5 第一期来源函数清单维持三项（`hmac.userId`、`idp.businessRoles`、`fixture.creditPilot`），`catalog.piiMark` 从「推迟」改为「撤销」；cloud `src/config/fact-sources.ts` 的注释同步改写。
- **预留扩展（本期不实施，写清以便将来实现）。** 将来出现真实的外部数据目录（如企业数据目录对字段的分级）时，用签名事实信封的新可选成员承载：`classifications: { "<schema 路径>": { "level": "L1|L2|L3", "category": "<≤32 字符>" } }`。它进签名载荷（JCS 规范化，与 `claims` 并列）；路径只允许 `context.` 前缀，按 D2 路径语法写，**不**与 `action` 的值比对；≤ 64 条；`sources` 的键集合等于 `claims` 叶子路径 ∪ `classifications` 路径。与 D4 打码的合并规则：**只升不降**。对同一路径取 IR 标记与目录标记中较高的级别，`source` 记 `catalog`；已声明为无 PII 的字段，目录标为 PII → 打码，视图标 `markConflict: true`。`unknown` 不会因为目录说「无 PII」而变得可见——要让字段可见只有一条路：策略作者把它声明进记录类型。启用条件：有数据源和消费方同时存在，另走一次 ADR 修订。
- 备选（非默认，见 §12.5）：cloud 用 aster-lang-ts 对已保存版本的编译结果签 `classifications`，做双引擎标记交叉核对。不推荐：两引擎类型检查的一致性已由 aster-lang-test parity 锁定，再签一遍是重复保证。

**D4 打码规则（§11 D2 的表格、递归与路径规则、正反例全部沿用，只改以下几处）。**

- 「已声明无 `@pii`」以 D2 的声明树判定；`marks_source = none` 时声明树为空，所有 `context.*` 叶子为 `unknown`。
- 第二层 `PIIRedactor` 的代价说明改为：规范 UUID 形态的值已整串豁免（F10），残余误伤只发生在非 UUID 的长数字串上，只影响展示，不影响哈希核对。
- `piiMarks` 在视图里的每项增 `source`（本期恒 `ir`）。

**D5 解除原值与留痕（取代 §11 D2「解除打码」段与 `input_revealed_at`）。**

- 端点：`POST /api/v1/guard/approvals/{id}/input/reveal`（空体），返回 `revealed: true` 的视图（D8）。谁能解除：沿用 §11——审批 `PENDING`，且审批人**已验证**业务角色包含 `required_role`；ESCALATE 与无角色的 owner/admin 不可解除；只还原已声明的 `@pii` 字段，`unknown` 任何人都看不到。不满足 → 403 `reveal_not_permitted`（附 `verifiedRoles`）；非 PENDING → 409 `approval_not_pending`；`status ≠ available` → 409 `input_not_available`。
- **按人留痕。** 新表 `guard_input_reveals`：`approval_id UUID`、`revealed_by VARCHAR(255)`、`tenant_id`、`revealed_at`、`audit_id BIGINT`，主键 `(approval_id, revealed_by)`。每人首次解除时，先**同步**追加新事件类型 `ACTION_GUARD_INPUT_REVEAL`：`performed_by` = 解除人，`decision_id` 列投影；metadata 为 `decisionId, approvalId, revealedFields`（已还原叶子数）、`maskRules, inputSnapshotHash`（该人解除后视图的哈希）。追加成功后再插行；追加失败 → 503 `receipt_unavailable`，**不返回原值**（没有留痕就不解除）。同一人再次解除不追加事件，直接返回解除视图。**并发串行化**：整个解除在一个事务内先对审批行 `SELECT … FOR UPDATE`（与审批 / 过期迁移同一把行锁），在锁内查解除行、追加事件、插行，所以同一人并发首次解除恰好一条事件；`guard_input_reveals.audit_id` 即该事件，`reveals[]` 与表一一对应。代价是解除与该审批的审批 / 过期互斥，持锁时长为一次同步入链。
- `GET …/input` 永远返回打码视图，视图带 `revealedByMe: boolean`。原值只经 POST 返回，任何读路径都不产生副作用。
- 解除记录不随输入行清理（D7）：它不含 PII，只含审批人 id，是访问审计的一部分。

**D6 快照哈希与审批记录（修订 §11 D3）。**

- 视图哈希配方不变（`sha256-sorted-json/v1`，服务端自算自比），被哈希的视图体扩为：`{ decisionId, contextHash, input, piiMarks, maskRules: "v1", revealed, marksSource, inputBinding, inputSources }`。`inputSources` 取决策行的 `verified / factIssuers / factSources`，**不含**随时间变化的 `factsStale`。
- approve / reject 时，服务端对**该审批人**计算 `H_masked`，该人有解除记录时再计算 `H_revealed`。请求体带的 `inputSnapshotHash` 等于其中之一 → `input_snapshot_matched = true`，`input_revealed` = 是否等于 `H_revealed`；两者都不等 → 409 `input_snapshot_mismatch`；未带（旧 SDK）→ 记 `H_masked`、`matched = false`、`input_revealed` = 该人是否有解除记录。别人的解除不影响本人（修正 F4）。
- `guard_approvals` 新列：`input_snapshot_hash VARCHAR(64)`、`input_snapshot_matched BOOLEAN`、`input_revealed BOOLEAN`、`input_status VARCHAR(16)`（不建 §11 的 `input_revealed_at`）。
- 审批事件 metadata 键：`inputSnapshotHash`（加入 `AuditChainAppender.IDENTITY_METADATA_KEYS`）、`inputSnapshotMatched`、`inputRevealed`、`inputStatus ∈ {available, unavailable, purged, integrity_failed, too_large}`；`status ≠ available` 时不写哈希键。过期事件（SYSTEM）不写这四个键。键名统一为 `inputSnapshotMatched`，ADR 0041 §11 D3 的 `inputSnapshotAttested` 是笔误（F7）。
- §11 D3 第 4 项「证明边界」原文保留：`matched = true` 只证明提交审批的客户端持有与当前视图一致的哈希，不证明人读过。

**D7 尺寸、存储与保留。**

| 项 | 上限 / 规则 |
|---|---|
| 存储的 `action_json` | 沿用请求校验：`context` ≤ 64 KB、深度 ≤ 8、文本字段 ≤ 255、roles ≤ 16（`GuardActionMapper`）；不另设存储上限 |
| 视图响应 | 渲染后 UTF-8 ≤ 1 MiB，超出 → `status: too_large`，不给 `input`，仍可审批（同 `unavailable`）；打码对象会让体积膨胀，64 KB 输入在极端情况下（大量极短叶子）可超过 1 MiB |
| 声明树 | ≤ 512 叶子（D2），超出 → `none` |
| 列表项 | 不内嵌输入（§11 D4，一页 200 项） |
| 保留：`guard_decision_inputs` | 审批终态后 `P90D`（§11 D1，`ASTER_GUARD_INPUT_RETENTION`），PENDING 不删；删除后视图 `purged` |
| 保留：`guard_evaluation_classifications` | `P7D`（D1） |
| 保留：`guard_input_reveals` | 不清理（D5） |
| 清理调度 | 与 `GuardApprovalExpiryScheduler` 同构的新调度器，每轮每表至多删 1000 行，`SKIP` 并发 |

**D8 API 形状（取代 §11 D4 中 aster-api 各行）。**

| 端点 / 产物 | 形状 |
|---|---|
| `GET /api/v1/guard/approvals/{id}/input` | MEMBER；HMAC 或 Bearer（§4.2 前缀已覆盖）。200 `GuardApprovalInputView`（字母序、null 不写）：`approvalId, contextHash, decisionId, factsStale?, input?, inputBinding, inputSnapshotHash?, inputSources?, marksSource, maskRules, piiMarks?, revealed, revealedByMe, status`。`status ≠ available` 时无 `input / piiMarks / inputSnapshotHash`；修订前的决策（无输入行）返回 200 `status: unavailable`。非法 UUID 与跨租户一律 404 `approval_not_found`（与审批端点同体）。以对象实体返回（F10） |
| `POST /api/v1/guard/approvals/{id}/input/reveal` | 同上视图，`revealed: true`；错误码见 D5 |
| `POST …/approve`、`…/reject` 请求体 | `{ comment?, inputSnapshotHash? }`；新增 409 `input_snapshot_mismatch` |
| `GuardApprovalView`（列表项） | 增 `inputStatus`、`inputSnapshotHash`、`inputSnapshotMatched`、`inputRevealed`（后三者仅已决且有记录时） |
| `POST /guard/decisions/from-evidence` | 新增 409 `evidence_input_mismatch`（D1） |
| `GET /api/v1/audit/receipts?decisionIds=` | `approvals[]` 每项增四键（取自审批事件 metadata）；新增 `reveals[]`：`{ auditId, currentHash, decisionId, approvalId, performedBy, timestamp }`（空不输出） |
| 契约 | `public-api-contract.golden` 登记两个新端点与新字段 |
| aster-guard SDK | `getApprovalInput(id)`、`revealApprovalInput(id)`；`approve / reject` 选项增 `inputSnapshotHash`；`GuardApproval` 增四个可选字段 |

**D9 证据包 v6（一次升版：R59 + ADR 0041 §11 D4 + ADR 0041 §12 D3）。**

- `Reviewer` 增 `inputSnapshotHash: string | null`、`inputSnapshotMatched: boolean | null`、`inputRevealed: boolean | null`、`inputStatus: string | null`。`guard-approval` 来源取收据接口 `approvals[]` 的值，其余来源为 null。
- `EvidenceEntry` 增：
  - `inputSource: 'declared' | 'mixed' | 'legacy'`（ADR 0041 §11 D4）；
  - `guardInputSources: { verified: string[]; factIssuers: string[]; factSources: string[]; unverifiedFields?: string[]; truncated?: boolean } | null`：取决策收据 metadata 的 `verifiedFields / factIssuers / factSources / unverifiedFields / inputSourcesTruncated`。`factSources` 按链上原样保留 `<path>=<sourceId>` 字符串并按字典序排列，不改写，审计方可逐字核对。这一项落实 ADR 0046 §11.7 第 3 条；
  - `execution`（ADR 0041 §12 D3 原形状）。
- 以上字段全部进 `bundleHash`。manifest 增 `inputSourceTally`、`verifiedInputsUsed`、`executionTally`（ADR 0041 原定义），以及 `inputSnapshotTally: { matched, unmatched, unavailable }`（按 guard-approval 复核者计数）；manifest 不进 `bundleHash`。`notes.verification` 改写为 v6 配方。
- `schemaVersion: '6'`；v5 类型冻结只读，`stored.ts` 认 1–6，已存包按原样下载（与 v1–v4 同法）。
- **Article 14。** v6 本身不改任何条款判定。cloud `SOURCES` 新增证据来源名 `reviewers.guard.inputSnapshot`：存在 `source = guard-approval`、`roleVerified`、`inputSnapshotMatched === true` 且 `inputStatus === 'available'` 的复核者。注册表未引用该名之前，它不影响任何判定。14(4)(a) 改为 `evidenced: ["reviewers.guard.inputSnapshot"]`、`partial: ["whatIf.comparable"]`，随下一次注册表升版（1.4.0，locales 真相源 + core / ts / cloud 副本，ADR 0053 列车）生效，不阻塞 #460；cloud 必须先认得这个来源名，注册表才能引用它（F6）。14(4)(b) 仍恒 `none`。
- 不进包：明文或打码后的输入、`reveals[]`。证据包只搬运哈希与结论（`types.ts` 类注释的既有原则）；解除记录在链上，经收据接口可查。

**D10 cloud 收件箱（取代 §11 D4 中 cloud 收件箱一行）。**

- 每行可展开「决策输入」面板，展开时才请求 `GET /api/approvals/[tenantId]/[approvalId]/input`（以当前用户身份转发，v3 签名，8 s 超时）。
- 渲染：按树展示，结构保留。已声明字段显示明文。`$pii` 显示为徽标（级别 + 类别），`unknown` 显示为「未声明类型，不可见」徽标。每个叶子旁显示来源徽标：路径 ∈ `inputSources.verified` 为「已验证」，悬停显示对应的 `factSources` 条目，其余为「自报」。
- 横幅：`inputBinding = unbound` →「输入未与评估记录核对」；`marksSource = none` →「策略未声明输入类型，业务字段全部隐藏」；`factsStale`；`status` 为 `unavailable / purged / integrity_failed / too_large` 时显示对应文案。
- 「显示原值」按钮出现条件：`canAct && requiredRole != null && status == available` 且存在已声明的 `@pii` 字段。点击后先弹确认框（「此次查看会以你的身份记入审计链」），确认后调用 `POST …/input/reveal`。
- 批准 / 拒绝：`status == available` 时，面板加载出视图之前按钮禁用，提交时带上当前展示视图的 `inputSnapshotHash`；`status ≠ available` 时直接可用、不带哈希。409 `input_snapshot_mismatch` →「输入视图已变化，请刷新后重新审阅」；403 `reveal_not_permitted`、409 `input_not_available`、503 `receipt_unavailable` 各有文案。对 api 的 404 / 未知错误（旧 api）一律按「输入不可用」显示，审批照常可用。
- 文案四语（en / zh / de / hi）。

**D11 兼容、发布与验收边界。**

- 纯增量：两张新表、两张表的新列、两个新端点、一种新事件类型、若干可选字段，既有响应形状不变。迁移取合并时的下一个可用号（`V6.37.0` 起，#464 / #465 若先合则顺延），`migration-checksums.golden` 追加。
- 发布顺序：aster-api → aster-guard SDK → aster-cloud，反序也不坏：旧 cloud 不读新字段；新 cloud 遇到旧 api 的 404 显示「不可用」；旧 cloud 的 from-evidence 不带新东西，只会多出 409 `evidence_input_mismatch`——只有 cloud 传入的 `context` 与评估时不同才会触发，按现有映射（`toGuardAction` 的 `context: input` 与 evaluate-source 的输入同一对象）不应发生。计划 Task A4 以测试钉住，并在上线后统计该错误码。
- 注册表 1.4.0（14(4)(a)）在 #460 之后，随下一次列车。
- **验收边界（F9）。** 「无权限角色看不到 PII 原值」本节只对审批面兑现：aster-api 视图、aster-guard SDK、cloud 收件箱。cloud 执行日志页对策略可见者明文展示 `Execution.input`，属于 cloud 侧的数据展示策略，另开 issue（计划「后续」），不在 #460 内修。
- 回滚：cloud 先回滚（隐藏面板）、api 后回滚（删端点与写入路径）；新表、新列、新事件留存无害。

### 12.3 被本节取代的 §11 条款

| §11 原文 | 处置 |
|---|---|
| D1 `guard_decision_inputs.pii_marks` 列 | 改名 `classification`，增 `marks_source / input_binding` 两列（D1） |
| D2「标记来源：决策时读该版本 `core_json`…」 | 由 D2 取代（按路径取来源，增 `marks_source`、声明树） |
| D2「解除打码」段中 `reveal=true` 与 `guard_approvals.input_revealed_at` | 由 D5 取代（POST、按人留痕、入链） |
| D2 第二层正则的「约 1/800 UUID 误伤」 | 由 D4 更正 |
| D3 第 1、2 项（按 `input_revealed_at` 重算、三列） | 由 D6 取代 |
| D4 表中 aster-api 端点、列表项、approve 体、收据接口、SDK、cloud 收件箱、证据包各行 | 由 D8、D9、D10 取代 |
| D4 证据包行「存在 `inputSnapshotHash` 非空…→ 14(4)(a) evidenced」 | 由 D9 取代（`matched === true`，随注册表 1.4.0） |
| §11.4 ②「纯 aster-api 改动，不需要引擎发版列车」 | 对 #460 本体仍成立；14(4)(a) 改判需要列车（D9） |
| §11.5 表「解除原值的角色」可改项 | 保留；D5 的按人留痕对扩展角色集合同样适用 |

### 12.4 验收（在 §11.3 基础上增补或替换）

1. `POST /decisions`（已部署版本带 `@pii` 记录）→ 视图 `marksSource: version`、`inputBinding: direct`，声明字段明文、`@pii` 字段打码、未声明键 `unknown`。
2. cloud 执行（evaluate-source → from-evidence）→ 视图 `marksSource: evaluation`、`inputBinding: evaluated`；删除分类行后再登记新证据 → `unbound` + `none`，全部 `context.*` 为 `unknown`。
3. from-evidence 的 `context` 与评估时不同 → 409 `evidence_input_mismatch`，不建决策；用同一 correlationId 带正确 `context` 重试成功。
4. 持角色者 A 解除 → 链上一条 `ACTION_GUARD_INPUT_REVEAL`（performed_by = A），再次解除不新增；B 未解除就审批 → 事件 `inputRevealed: false`；无角色成员解除 → 403 `reveal_not_permitted`；审批链写入失败时解除 → 503 且响应不含原值。
5. 带 `H_masked` 或 `H_revealed` 审批 → `matched = true`，`inputRevealed` 与所带哈希一致；带错误哈希 → 409；不带 → `matched = false`。
6. 解除视图经 HTTP 返回时原值未被 `PIIResponseFilter` 改写（`aster.pii.enforce=true`）。
7. 收据接口 `approvals[]` 四键与 `reveals[]`；cloud 导出 `schemaVersion '6'`：reviewer 四字段、`guardInputSources.factSources` 与链上收据逐字一致、`execution`、manifest 四个 tally；v5 包仍可下载。
8. 收件箱 Playwright：无角色成员无「显示原值」；面板未加载时批准按钮禁用；DPO 确认解除后批准，事件 `inputRevealed: true`。
9. 保留：输入行终态 + `P90D` 后视图 `purged`；分类行 `P7D` 后删除。

### 12.5 已选默认（定稿即生效）与可改项

| 项 | 已选默认 | 可改为 | 影响 |
|---|---|---|---|
| `catalog.piiMark` | 撤销；标记由 aster-api 从执行 IR 推导；`classifications` 只作为预留格式 | 备选：cloud 签 ts 编译结果做双引擎交叉核对 | 需 FactVerifier 载荷扩展 + cloud 来源函数；收益仅为 parity 的重复保证 |
| from-evidence 输入不一致 | 409 `evidence_input_mismatch`，不消耗证据 | 照常建决策，只标 `unbound` | 更宽松；审批人可能看到非评估输入 |
| 分类行保留期 | `P7D` | 任意 ISO 时长 | 只影响晚于保留期的「重新登记」降级为 `none` |
| 解除留痕粒度 | 每人每审批首次一条事件 | 每次解除一条 | 链上事件数随查看次数增长 |
| 14(4)(a) 改判 | 随注册表 1.4.0（下一次列车），条件 `matched === true` | 不改判（仍 partial） | 不改则注册表无需升版 |
| ESCALATE 解除 | 不可解除（§11） | 允许任一成员解除 | 扩大原值可见范围 |
| 执行日志页明文输入（F9） | 另开 issue，不在 #460 | 并入 #460，日志页用同一打码 | cloud 需复刻打码规则或改调 api 视图 |
| 视图上限 | 1 MiB → `too_large` | 更大 / 更小 | 仅影响极端输入的展示 |

定稿时回写：ADR 0040 §3.3 / §5.2 脚注（§11 末段已约定）；ADR 0041 §11 D3 键名 `inputSnapshotAttested` → `inputSnapshotMatched`，§11 D4 / §12 D3 注明 v6 一次升版；ADR 0046 §11.7 第 2 条注明「撤销（ADR 0042 §12 D3）」。
