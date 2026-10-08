# ADR 0042：审批闭环 —— 经验证的业务角色、从证据开决策、cloud 审批收件箱（AI 治理方向·子项目 3b）

- 状态：**PROPOSED**（2026-10-09）
- 提出：2026-10-09；实施计划待写（`docs/superpowers/plans/2026-10-09-approval-closure.md`）
- 决策者：用户已拍板「业务角色挂在团队成员上由 owner/admin 授予；cloud 以证据链收据为锚开决策，不重复评估；收件箱走内部 HMAC v3 通道并签正用户与角色；角色持有者可审、管理员只读、站内通知不发邮件」
- 相关：ADR 0040（Action Guard：决策、待审批、四眼、§10 开放问题第一条）、ADR 0041（证据链补齐：correlationId 列、收据接口、复核者 `guard-approval`）、aster-cloud ADR 0015（团队作用域 API key 身份：verify 响应与快照）、ADR 0039（Verdict 四态与 `role`）

---

## 0. TL;DR

ADR 0040 的审批有两处没闭上：审批方的业务角色是调用方自报的 `X-User-Business-Role` 头，aster-api 不校验，任何持 key 者可以冒充 DPO；而 aster-cloud 的策略执行走 `/evaluate-source`，得到 `REQUIRE_APPROVAL` 后无处可审，`executions.metadata.guardDecisionId`（ADR 0041 §5.2 已预留读取）没有写入方。

本 ADR 做三件事，把「需要人批」变成「有人能批、批的人身份可验、批了之后证据包能看到」：

1. **经验证的业务角色**：角色存在 aster-cloud 的团队成员（个人租户存在用户）上，随 API key verify 响应与快照下发；aster-api 只信这条链路，`X-User-Business-Role` 头被覆盖。guard 审批匹配改为「所需角色 ∈ 已验证角色集合」。
2. **从证据开决策**：新端点 `POST /api/v1/guard/decisions/from-evidence` 以 `/evaluate-source` 返回的 `evidence.correlationId` 为锚，从哈希链上的评估事件建决策与待审批，不重复评估；决策收据就是那条评估事件。
3. **cloud 审批收件箱**：dashboard 新页面汇总用户所在全部租户的待审批，持有所需角色者可审，owner/admin 只读；通过内部 HMAC v3 通道调用 guard，canonical 签入 query、用户 id 与业务角色，顺带关闭 ADR 0041 §7.1 的「query 未签名」遗留。

ADR 0041 §5.2 的 `guard-approval` 复核者随之从 `roleVerified=false` 改为 `true`。

## 1. 背景与问题

| 现状 | 位置 | 后果 |
|---|---|---|
| 审批方角色 = 原始 `X-User-Business-Role` 头，trim 后与 `required_role` 字符串相等 | `GuardApprovalService.checkRole` | 任何持有效 key 的成员可自称任何角色；ADR 0040 §10 第一条 |
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
- 词表：自由文本。入库校验 `normalizeBusinessRoles(input: string[]): string[]`：每项 trim；1–64 字符；仅可打印 ASCII（0x20–0x7E）；去重保持首次出现顺序；最多 16 项。与 Verdict 的 `role` 字符串按原样精确匹配（大小写敏感，不做模糊匹配），这是 ADR 0040 已有语义，本 ADR 不改。
- 授予：团队设置页成员列表新增「业务角色」编辑，`PATCH /api/teams/[id]/members/[userId]`（新增 body 字段 `businessRoles`），权限 `checkTeamPermission(..., 'manage_members')`（沿用现有常量名）。个人：`PATCH /api/settings/profile` 增加 `businessRoles`。

### 2.2 下发

- `/api/internal/apikey/verify` 响应与 `toApiKeySnapshotBody` 增加 `businessRoles: string[]`：团队 key 取 `TeamMember.businessRoles`，个人 key 取 `User.businessRoles`。
- aster-api `ApiKeyVerifyResult` / `ApiKeySnapshot` 增加 `List<String> businessRoles`，Jackson 缺省为空列表（旧 pod、旧快照兼容；快照推送方未升级时字段缺失即空）。
- `ApiKeyAuthFilter` 写请求属性 `aster.identity.businessRoles`（不可变 `List<String>`），并**覆盖** `X-User-Business-Role` 头为已验证角色的首项（无则空串），与覆盖 `X-User-Role` 同法。
- `RequestIdentityResolver.businessRoles()`：只读该属性；无过滤器（测试配置 `aster.security.apikey.enabled=false`）时读 `X-User-Business-Roles` 测试头（逗号分隔），与现有 `performedBy()` 回退一致。

### 2.3 匹配

`GuardApprovalService.checkRole`：`requiredRole == null`（ESCALATE）→ 通过；否则 `identityResolver.businessRoles().contains(requiredRole)`，不再读请求头。403 `role_mismatch` 响应体新增 `verifiedRoles: [...]` 便于 UI 提示。

### 2.4 已知延迟

verify 结果缓存 60 秒，角色变更最多 60 秒后对 Bearer 调用者生效；HMAC 通道（§4）由 cloud 实时查库签入，无延迟。

## 3. 决策二：从证据开决策

### 3.1 端点

`POST /api/v1/guard/decisions/from-evidence`，仅内部 HMAC 通道（`InternalCallerFilter` 分类 REQUIRE_HMAC；Bearer 调用 403 `internal_only`）。

请求：
```json
{
  "correlationId": "<evaluate-source 返回的 evidence.correlationId>",
  "policyModule": "guard.customer", "policyFunction": "decide",
  "action": { "principal": {"id","type","roles"}, "action": {"name"}, "resource": {"type","id"}, "context": {}, "agent": {...} }
}
```
`action` 与 `GuardActionRequest.Action` 同一记录类型。

处理（单事务）：
1. `AuditLog.findByCorrelationIds(tenant, [correlationId])` 取事件；无 → 404 `evidence_not_found`。
2. 校验 `event_type = POLICY_EVALUATION` 且 metadata `outcome ∈ {REQUIRE_APPROVAL, ESCALATE}`；否则 409 `evidence_not_approvable`（响应体带 `outcome`）。
3. 幂等：`guard_decisions` 新列 `evidence_correlation_id VARCHAR(64)`，唯一索引 `(tenant_id, evidence_correlation_id) WHERE evidence_correlation_id IS NOT NULL`；已存在 → 200 返回既有决策（与 Idempotency-Key 语义一致，`action` 不比对）。
4. 建 `guard_decisions`：outcome/reason/required_role/rule_id/controls 取自事件 metadata（`role`、`reason` 键见 §3.2）；`receipt_audit_id = 事件 id`、`receipt_hash = current_hash`；`requested_by = performedBy()`（签过的用户）、`requested_api_key_id = null`；`policy_version_id` 取事件 metadata 的 `policyVersionId`（ADR 0041 未写则 null）。
5. 建 `guard_approvals`（PENDING，TTL 同 ADR 0040）。
6. 响应 `GuardDecisionResponse`（同 `POST /guard/decisions`，`receipt` 指向评估事件）。

不发布新的 ACTION_GUARD_DECISION 审计事件：决策本身就是评估事件 + 账本行。审批事件（ACTION_GUARD_APPROVAL）照旧发布，`decision_id` 列照旧投影。

### 3.2 评估事件补两个键

`EvaluationEvidence` 增加 `role`（Verdict 的角色，可空）与 `reason`（Verdict 的原因，截断 255），扁平键 `role` / `reason`，三条 `/evaluate*` 路径都带。guard 收据的 `reason` 键已存在，保持。ADR 0041 §2.2 的键表随本 ADR 修订。

### 3.3 不做

- 不按源码重新评估（用户否决：双事件、双 correlationId）。
- 不接受 cloud 断言的 Verdict（用户否决：链上无可校验来源）。

## 4. 决策三：内部 HMAC v3 与 cloud 调用面

### 4.1 canonical v3

```
method \n path \n query（原样，无则空）\n ts \n nonce \n sha256(body) \n tenant \n role \n userId \n businessRoles（排序后逗号拼接，无则空）
```
头新增 `X-User-Business-Roles`（逗号分隔，值同 canonical），`X-Internal-Signature` 版本标记改为 `v3`。api 侧 `InternalCallerFilter` 校验后写 `aster.identity.{tenantId,userId,businessRoles}` 请求属性；HMAC 调用者的 `performedBy()` 与 `businessRoles()` 只取这些属性，未签头一律忽略。

v2 调用方（`/evaluate-source`、`/whatif-batches`、`/api/v1/audit/receipts`）一次切到 v3，不并存：cloud 与 api 同次发布（发布顺序见 §8）。

### 4.2 对 HMAC 开放的 guard 路径

`/api/v1/guard/decisions/from-evidence`（仅 HMAC）、`GET /api/v1/guard/decisions/{id}`、`GET /api/v1/guard/approvals`、`POST /api/v1/guard/approvals/{id}/approve|reject`（HMAC 或 Bearer）。`ApiKeyAuthFilter` 的 Bearer 旁路规则从单一路径改为路径集合，仍要求 `InternalCallerFilter.isHmacVerified`。

### 4.3 approvals 列表项扩展

`GuardApprovalItem` 新增 `policyModule, policyFunction, actionName, resourceType, resourceId, ruleId, controls, reason, evidenceCorrelationId`（取自关联决策）。SDK `listApprovals` 类型同步，旧字段不变。

### 4.4 cloud `PolicyApiClient`

新增 `guardFromEvidence(req)`, `getGuardDecision(id)`, `listGuardApprovals(status, page, size)`, `approveGuard(id, comment?)`, `rejectGuard(id, comment)`。构造参数 `businessRole` 改为 `businessRoles: string[]`，由 `createPolicyApiClient({ userId, tenantId, businessRoles })` 调用方在请求时实时查 `TeamMember`/`User` 后传入；全部走 v3 签名。

## 5. 决策四：cloud 执行写 guardDecisionId 与收件箱

### 5.1 执行路径

两条执行路由在 `deriveExecutionDecision ∈ {require_approval, escalate}` 时：
1. 以当前身份（dashboard：会话用户与其在该策略租户的业务角色；v1：key 持有者）调 `guardFromEvidence({ correlationId: metadata.evidenceCorrelationId, policyModule/Function: 策略模块名/函数名, action: 由执行输入映射 })`。映射：`principal.id` = 输入 `principal_id` 或用户 id；`action.name` = 输入 `action` 或策略函数名；`resource` = 输入 `resource_type/resource_id` 或空；`context` = 输入对象；`agent` = 执行声明的 agent。
2. 成功 → `executions.metadata = { guardDecisionId }`；失败（网络、4xx、5xx）→ `metadata = { guardError: <code> }`，执行行照常写入，不抛错。
3. 日志页对 `guardError` 行提供「重新登记」按钮（`POST /api/policies/[id]/executions/[execId]/guard-register`，同一映射再调一次并更新 metadata）。

`evidenceCorrelationId` 为空（simulate、旧 api）时不调 guard，记 `guardError: no_evidence`。

### 5.2 收件箱

- 页面 `/[locale]/(dashboard)/approvals`；侧边栏项「审批」；`?status=`（默认 PENDING）、`?decisionId=` 定位高亮。
- 服务端 `listUserApprovals(userId, status)`：租户集合 = `{userId} ∪ 用户所有 teamId`；对每个租户以该用户身份（含该租户的业务角色）调 `listGuardApprovals`，并发 4、单租户失败标「不可用」不影响其他；合并按 `createdAt` 倒序；每项附 `canAct`：`status=PENDING` 且（`requiredRole == null` 或 `requiredRole ∈ 该租户业务角色`）。
- 可见性：成员可见所在租户全部项；owner/admin 无角色时 `canAct=false` 只读。
- 操作：`POST /api/approvals/[tenantId]/[approvalId]/approve|reject`（body `{comment}`，reject 必填）→ `approveGuard/rejectGuard`；api 的 403 `segregation_of_duties` / `role_mismatch`、409 `approval_not_pending` 原样映射到 UI 文案。
- 四眼不在 cloud 重复判定。

### 5.3 通知

- `NotificationKind` 新增 `guard.approval_requested`（给该租户持有 requiredRole 的成员；ESCALATE 给全部成员；个人租户给本人）与 `guard.approval_decided`（给发起人）。payload：`{ tenantId, decisionId, approvalId, policyName, requiredRole, outcome? }`。
- 触发点：`from-evidence` 成功后、approve/reject 成功后，fire-and-forget（沿用 `createNotification`）。
- 不发邮件。

### 5.4 日志页

带 `metadata.guardDecisionId` 的行显示「待审批 · 查看」链接到 `/approvals?decisionId=`；不实时拉状态。

## 6. 数据流

```
dashboard 执行 ──/evaluate-source(v3)──▶ api：评估事件(correlationId, outcome, role, …) 上链
      │ outcome ∈ {REQUIRE_APPROVAL, ESCALATE}
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
| from-evidence 时链上事件缺失/不可审批 | 404 / 409，cloud 记 `guardError`，执行行照常 |
| 同一 correlationId 重复 from-evidence | 返回既有决策（唯一索引保证） |
| 角色变更后 60 秒内 Bearer 审批 | 可能 `role_mismatch`，UI 提示刷新 |
| HMAC 调用者伪造 X-User-Id/角色头 | canonical 校验失败 403 `invalid_signature` |
| 发起人自批 | 403 `segregation_of_duties`（requestedBy 为签过的用户） |
| owner/admin 无角色点击 | UI 无按钮；直连 API → 403 `role_mismatch` |
| 某租户 api 不可达 | 收件箱该租户分组标「不可用」，其他租户正常 |
| 业务角色含非 ASCII | 入库 400 `invalid_business_role` |

## 8. 兼容性与发布

- 破坏性：HMAC v2 → v3 一次切换；SDK 的 `businessRole` 选项继续发送头但不再被信任（README 更新）；`GuardApprovalItem` 只增字段。
- 发布顺序：先 aster-api（同时接受 v2/v3？——**否**，为避免双版本，api 与 cloud 必须同一维护窗口发布；期间 cloud→api 内部调用失败即 fail-closed，执行记 `guardError`，评估本身走 Bearer 不受影响——注意 `/evaluate-source` 也走 HMAC，窗口期间 dashboard 执行会失败，窗口需短）。
- 迁移：api V6.29.0（`guard_decisions.evidence_correlation_id` + 唯一索引）；cloud 0051（两列 + 无需枚举）。
- 旧 `guard_approvals` 行无 `evidence_correlation_id`，不受影响。

## 9. 测试与验收

- api：`InternalCallerFilterTest` v3 canonical；篡改 query / userId / roles 各 403；`GuardFromEvidenceResourceTest`（HmacProfile）：建决策、409、404、幂等、Bearer 403；`GuardApprovalServiceTest` 集合匹配、头伪造无效、ESCALATE 任意成员；`ApiKeyVerifierServiceTest` 解析 `businessRoles` 与缺省；`EvaluationEvidenceTest` 新键。
- cloud：`normalizeBusinessRoles` 单测；成员 PATCH 路由权限与校验；verify/snapshot 带角色；执行路由调 from-evidence 与失败降级；`listUserApprovals` 合并与 `canAct`；approve/reject 路由错误映射；收件箱组件三种身份按钮可见性；通知写入；全门禁。
- 栈验收（Task 末）：m-dpo 授予 "Data Protection Officer"；m-free dashboard 执行示例策略 → 日志待审批带链接；m-dpo 收件箱批准 → `guard_approvals` APPROVED、decidedBy=m-dpo；导出证据包 reviewers 含 `guard-approval` 且 `roleVerified=true`；m-free 自批 403 四眼；无角色成员无按钮；SDK 以自报头审批 → 403 `role_mismatch`。

## 10. 后续（不在本 ADR）

- 邮件/webhook 通知（ADR 0040 §10）。
- 审批通过后自动重放执行。
- MCP 端把 `businessRole` 提示改为读取 verify 角色。
- waitSeconds 线程占用、MCP 60 s 超时（ADR 0040 §10）。
