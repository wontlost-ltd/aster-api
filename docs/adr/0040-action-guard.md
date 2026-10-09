# ADR 0040：Action Guard —— AI 行动守门服务面（AI 治理方向·子项目 2）

- 状态：**IMPLEMENTING**（2026-10-08，aster-api 侧子项目 2 已实现，aster-guard 仓待建；2026-10-08 终审修正已入分支：收据身份键豁免脱敏、缩短顺序、审批事件预算、审批列表信封，见 §9）
- 提出：2026-10-07；实施计划 `docs/superpowers/plans/2026-10-07-action-guard.md`；aster-api 分支 `feat/adr-0040-action-guard`
- 决策者：用户已拍板「按策略地址直连；无收据则拒绝；本期只做 API 不做 cloud UI；Agent 接入层新建仓库」
- 相关：ADR 0039（Verdict 原语与规则元数据，本 ADR 的地基）、ADR 0030（canonical JSON）、ADR 0037（提出 / 判定 / 复核三段式）、ADR 0023（多仓发布治理）

---

## 0. TL;DR

子项目 1 让「允许 / 拒绝 / 需人工批准 / 升级」成为语言与运行时的一等值。本 ADR 让它成为**运行期对 AI Agent 的硬约束**：

```
Agent 提议动作 ──► POST /api/v1/guard/decisions ──► 已部署的 Verdict 规则
                                                      │
                     ┌────────────────────────────────┼──────────────────┐
                     ▼                                ▼                  ▼
                   ALLOW                           DENY          REQUIRE_APPROVAL / ESCALATE
                     │                                │                  │
              收据入哈希链                      收据入哈希链      收据 + 待审批记录 ──► 人批准 / 拒绝 / 过期
                     │                                │                  │
                 Agent 执行                       Agent 不执行     Agent 轮询后执行或放弃
```

四个决定：

| 决定 | 内容 | 不做什么 |
|---|---|---|
| 评估绑定 | guard 请求显式指定 `policyModule` / `policyFunction`，整个 Action 作为**一个记录参数**传给已部署规则 | 不做租户级「工具名 → 策略」绑定表；不做内联源码评估 |
| 失败语义 | 全部 fail-closed：规则非 Verdict、评估异常、超时、**收据写入失败**都返回 DENY 并带 `reasonCode` | 不做「尽力写收据、决策照常返回」 |
| 人工审批 | 新表 `guard_approvals` + 三个端点。aster-api 按已解析身份强制四眼（发起主体、提交请求的用户与 API key），按 BFF 传入的业务角色标签匹配 `required_role`（aster-api 不校验该标签），过期即 DENY | 不复用策略版本审批状态机；不复用工作流引擎 |
| Agent 接入 | 新仓 `aster-guard`：`GuardClient` SDK + `aster-guard-mcp` 包裹式 MCP 代理 | 不放进 aster-lang-ts；不做 webhook 通知；不做 cloud 收件箱 UI |

---

## 1. 背景：现状中没有的东西

2026-10-07 对 aster-api / aster-cloud / aster-lang-ts 的实证探查：

| 需要的能力 | 现状 |
|---|---|
| Agent / 工具调用 / MCP 概念 | `src/main`、`replay`、`runner` 零命中；任何仓库都没有 `@modelcontextprotocol/sdk` 依赖 |
| 决策收据 | 审计链只有异步路径：`AuditEventListener.onAuditEvent` 由 `fireAsync` 触发，异常全部吞掉只记指标；调用方拿不到行 id 与哈希 |
| 待审批记录 | `PolicyApproval` 实体绑定 `policy_versions` 外键且无人引用；真正的状态机是 `VersionStatus`，面向策略版本而非运行期动作 |
| 挂起 / 恢复 | `WorkflowRuntime` 接口只有 `schedule`；`PAUSED` 状态没有任何代码写入；REST 没有 start / pause / signal 端点 |
| Map 参数进已部署规则 | 部署路径 `TrufflePolicyRuntime.newPooledContext()` 用 `HostAccess.EXPLICIT` 且未开 map / list / array 访问；源码路径 `DynamicCnlExecutor` 显式开启了三者。部署路径传 Map 参数**未经验证**（探针结论见 §2.3） |
| 幂等 | `InboxGuard` 是「先占键后干活」的去重闸，重试返回 409，不重放首次响应 |
| 鉴权 | `ApiKeyAuthFilter.shouldProtect` 是显式路径白名单；新路径不加进去就只受可伪造的 `X-Tenant-Id` / `X-User-Role` 头保护 |

结论：guard 需要**新表、新事件类型、一条同步审计路径、一个新仓**，但评估内核、版本解析、哈希链、配额、API key 全部复用。

---

## 2. 决策一：评估绑定与 Action 模型

### 2.1 为什么按策略地址直连

| 路径 | 结论 |
|---|---|
| **A 按策略地址直连**（采纳） | 请求显式带 `policyModule` / `policyFunction`，复用 `PolicyEvaluationService.evaluatePolicy(tenant, module, function, Object[])` 及其版本解析与缓存；Agent 侧配置一行 |
| B 租户级绑定表 | 多一张表一套 CRUD，现阶段没有多策略路由需求；有客户要求再做（见 §10） |
| C 内联源码评估 | 规则不受版本与审批管控，违背治理初衷 |

### 2.2 Action 模型（请求体 `action`）

```json
{
  "principal": { "id": "agent-42", "type": "agent", "roles": ["ops-bot"] },
  "action":    { "name": "delete_customer_record" },
  "resource":  { "type": "customer", "id": "C-1001" },
  "context":   { "amount": 250000, "currency": "NZD" },
  "agent":     { "model": "claude-fable-5-1", "session": "s-9f3a" }
}
```

guard 服务把它扁平成**一个 Map** 作为规则的唯一位置参数，键固定：

| 键 | 类型 | 来源 |
|---|---|---|
| `principal_id` | Text | `principal.id`（必填） |
| `principal_type` | Text | `principal.type`（默认 `"agent"`） |
| `principal_roles` | List of Text | `principal.roles`（默认空） |
| `action` | Text | `action.name`（必填） |
| `resource_type` | Text | `resource.type`（默认 `""`） |
| `resource_id` | Text | `resource.id`（默认 `""`） |
| `context` | Map | `context`（默认空 Map，嵌套深度 ≤ 8，序列化后 ≤ 64 KB） |
| `agent_model` | Text | `agent.model`（默认 `""`） |

请求校验（`GuardActionMapper`，违反任一条返回 400 `invalid_action`，发生在评估与写收据之前）：

- `policyModule`、`policyFunction`、`principal.id`、`action.name` 不得为空白。
- 文本字段 `principal.id`、`principal.type`、`action.name`、`resource.type`、`resource.id`、`agent.model` ≤ 255 字符且不含控制字符（`Character.isISOControl`）。上限与 `guard_decisions` 列宽一致，也给收据 metadata 一个确定上界（§5.2）。
- `principal.roles` 至多 16 项，元素非 null，每项同样 ≤ 255 字符且不含控制字符。roles 不入列，但会写进收据 metadata（§5.2）。
- `context`：键必须是字符串，值不得为 null，嵌套深度 ≤ 8（context 自身为第 1 层），键排序序列化后 ≤ 65536 字节。

传给规则的 Map 及其中的 `context` / `principal_roles` 是**深度不可变**的副本（`Map.copyOf` / `List.copyOf`），原因见 §2.3。

规则侧：Verdict 的 `outcome` 不在 `ALLOW / DENY / REQUIRE_APPROVAL / ESCALATE` 之内，或 `role` / `reason` 超过 255 字符，一律按 DENY `not_a_verdict` 处理（fail-closed）。不截断：截断后的 `role` 会让审批时的角色比对失真。`ruleId` 来自注解，只按列宽截到 255。

策略作者侧：

```
Module guard.customer.

Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.

@id("CUST-DEL-001")
@control("GDPR:ART17")
Rule decide given req as GuardRequest, produce Verdict:
  If req.action equals to "delete_customer_record":
    Return Verdict.require_approval("Data Protection Officer", "customer record deletion").
  Otherwise:
    Return Verdict.allow().
```

### 2.3 部署路径的 Map 参数（探针结论：需要开启）

实施计划的第一个任务是探针 `DeployedMapParamProbeTest`：部署上述规则，经 `evaluatePolicy` 传入 `new Object[]{Map}`。首次运行失败，报 `HostObject 不支持成员访问，成员：action`，说明部署路径原先**读不了** Map 参数。

修法：`TrufflePolicyRuntime.newPooledContext()` 的 `allowHostAccess(HostAccess.EXPLICIT)` 改为

```java
HostAccess.newBuilder(HostAccess.EXPLICIT)
    .allowArrayAccess(true).allowListAccess(true).allowMapAccess(true)
    .build()
```

口径与 `DynamicCnlExecutor` 一致。基线仍是 `EXPLICIT`：只放开数组 / List / Map 的元素读取，不放开宿主方法调用与类查找；其余沙箱配置（IO NONE、无 native、`allowHostClassLookup(name -> false)`、PolyglotAccess NONE、禁止建进程、资源限制）不变。修复后探针通过：规则读到 `req.action`，返回 `REQUIRE_APPROVAL`，role 为 `Data Protection Officer`。

影响面：池化 Context 被**全部已部署规则**共用，所以此后任何以 Map / List / 数组传入的宿主参数，其元素都能被规则读取。`allowMapAccess` 同时把宿主 Map 作为可写哈希暴露给 interop，因此凡是传给规则的 Map / List 都必须不可变：guard 传入的参数用 `Map.copyOf` / `List.copyOf` 深度冻结（§2.2）。Aster 规则语言目前未见写成员的语法，但这一点未经穷尽核实，不能作为唯一防线。

覆盖情况：默认配置下 `aster.truffle.tests.skip=true`，`TrufflePolicyRuntimeSandboxTest` 的 R21 happy path 与 `TrufflePolicyRuntimeTest` 都会跳过。所以「真实解释器 + 新 HostAccess」只由本探针与 `DynamicPolicyExecutionTest` 覆盖。

不采用「拆成八个标量参数」的替代方案：它让策略签名僵化，且与 Action 扩展冲突。

### 2.4 结果缓存

`PolicyEvaluationService` 的结果缓存按 `(tenant, module, function, versionId, normalizedContext)` 命中。guard 的 Map 参数参与缓存键；同一动作在同一版本上重复请求命中缓存是**正确的**（规则是纯函数），但收据**每次都新写**（每次请求都是一次独立的治理事件）。

---

## 3. 决策二：决策端点

### 3.1 `POST /api/v1/guard/decisions`

- 鉴权：API key（`Authorization: Bearer ak_…`），`ApiKeyAuthFilter.shouldProtect` 新增 `/api/v1/guard` 前缀（`/api/v1/guard/*` 全部要求 API key）；`@RequireRole(MEMBER)`。身份经 `RequestIdentityResolver`，在请求线程上一次取齐 `tenantId` / `performedBy` / `apiKeyId`，`performedBy` 只作标签。
- 配额（与 `/evaluate` 同一口径，先于请求体校验执行）：
  1. `ApiQuotaGuard.check(tenantId, userId)`：FORBIDDEN → 403 `api_access_denied`；RATE_LIMITED → 429 `api_quota_hard_exceeded`。
  2. 有已验证的 `apiKeyId` 时再做每 key 每秒限流 `checkRate`：超限 → 429 `rate_limit_exceeded`，带 `Retry-After: 1`。
  3. 返回决策（200）后调用 `recordAsync(userId, tenantId, apiKeyId, "/api/v1/guard/decisions", "success", latencyMs)` 计量一次。400、404、422 和配额拒绝都不计量。

  三种拒绝的响应体与 `PolicyEvaluationResource.enforceApiQuota` 逐字一致。没有抽出共享组件，而是在 `GuardDecisionResource.quotaRejection` 里镜像实现（约 30 行重复）。`X-Quota-*` / `X-RateLimit-*` 响应头**不输出**（§10）。每次 guard 调用都含一次同步审计追加，不限速、不计量，这个端点就会成为免费且可放大的写入面。
- 请求：`{ policyModule, policyFunction, action, waitSeconds? }`。`waitSeconds`：null 或负数按 0，超过 30 按 30，默认 0。结果为 PENDING 时，服务端每 500 ms 回读一次，直到不再 PENDING 或到时，然后返回最新响应，给同步型 Agent 一次机会拿到最终结果。等待期间占用一个 worker 线程（§10）。
- 幂等：`Idempotency-Key` 头，由服务层校验。
  - 空白视为未提供（不去重）；超过 255 字符或含控制字符 → 400 `invalid_action`，在查重、评估与写收据之前拒绝。
  - 表 `guard_decisions` 以 `(tenant_id, idempotency_key)` 部分唯一（索引 `guard_decisions_idem_uq`）。
  - **幂等指纹**：请求体先按 §2.2 校验并扁平化，再查键。键已存在时，比对存储行与本次请求的 `policy_module`、`policy_function`、`principal_id`、`action_name`、`resource_type`、`resource_id`、`context_hash`。任何一项不同 → 422 `idempotency_mismatch`：一个键只能重放同一个提议动作，不能把一个动作的结论借给另一个动作。`principal.type`、`principal.roles`、`agent.model` 不参与比对，它们不改变「对什么做什么」。
  - **重放语义**：指纹一致时不再评估、不再写收据。返回的是**同一决策**：同一 `decisionId`、同一 `receipt`、同一 `approval.id`。但 `approval.status` 与 `effectiveOutcome` 反映**当前**状态：批准后为 ALLOW，拒绝或过期后为 DENY。它**不是**首次响应体的逐字节原样。原因有二：审批迁移本就会改写 `response_json`（§3.3）；Agent 重试时应直接拿到当前结论，而不是依据过时的 PENDING 行事。重放不返回 409。
  - **并发同键**：先写入者胜（唯一索引）。后到者在落库时捕获该索引的唯一冲突，回读先到者的决策，同样比对指纹：一致则返回该决策，不一致则返回 422。后到者已写入的收据留在链上，没有对应的决策行（§5.2）。
  - **瞬时 DENY 同样入库**：`evaluation_failed` 与 `receipt_unavailable` 的 DENY 也以该键存储，同键重放返回同一个 DENY。想在故障恢复后重试，必须换一个新键。
- 响应（决策始终 200；字段字母序，null / 空不写）：

```json
{
  "decisionId": "d-7c1e…",
  "outcome": "REQUIRE_APPROVAL",
  "reason": "customer record deletion",
  "role": "Data Protection Officer",
  "ruleId": "CUST-DEL-001",
  "controls": ["GDPR:ART17"],
  "policyVersion": { "id": 1730890123456, "version": 3, "sourceHash": "…" },
  "receipt": { "auditId": 88213, "hash": "…64hex…", "prevHash": "…64hex…" },
  "approval": { "id": "a-91b0…", "status": "PENDING", "requiredRole": "Data Protection Officer", "expiresAt": "2026-10-08T05:12:00Z" },
  "effectiveOutcome": "PENDING"
}
```

`effectiveOutcome` ∈ `ALLOW | DENY | PENDING`，是 Agent 唯一需要看的字段：ALLOW 执行；DENY 不执行；PENDING 轮询 `GET /decisions/{id}`。

### 3.2 fail-closed 映射

| 情况 | HTTP | `outcome` / `effectiveOutcome` | `reasonCode` |
|---|---|---|---|
| 规则返回 Verdict | 200 | 按 Verdict | null |
| 规则返回非 Verdict（Boolean / 记录 / 文本） | 200 | DENY | `not_a_verdict` |
| Verdict 的 `role` / `reason` 超过 255 字符，或 outcome 不在四值内 | 200 | DENY | `not_a_verdict`（不截断） |
| 评估异常、超时（10 秒）、策略执行失败 | 200 | DENY | `evaluation_failed`（reason 为 null，异常只记日志） |
| 收据写入失败 | 200 | DENY | `receipt_unavailable`（此时**不**创建待审批记录） |
| 策略不存在 / 无活跃版本 | 404 | — | `policy_not_found` |
| 请求体校验失败（缺 principal.id 等、文本超长或含控制字符、roles 超限、context 超限）或 `Idempotency-Key` 非法 | 400 | — | `invalid_action` |
| `Idempotency-Key` 已绑定另一个提议动作（指纹不符，§3.1） | 422 | — | `idempotency_mismatch` |
| 配额 / 速率 | 403 / 429 | — | `api_access_denied` / `api_quota_hard_exceeded` / `rate_limit_exceeded`（与 `/evaluate` 同体，§3.1） |

`invalid_action` 只覆盖业务校验失败（由 `GuardActionMapper` 与服务层抛出）。框架级的 JSON 解析错误与类型错误（请求体不是合法 JSON、`waitSeconds` 不是整数等）在进入资源方法之前就被 RESTEasy / Jackson 拒绝，沿用框架自身的 400 响应体，不带 `error: invalid_action`。两者都是 400，客户端都应按 DENY 处理。

非 Verdict 结果判 DENY 而不是沿用 `DecisionInterpreter` 的 Boolean 映射：guard 是治理面，必须由带 `@id` 的 Verdict 规则守门，Boolean 规则没有理由与身份。

### 3.3 表 `guard_decisions`（Flyway `V6.27.0`，与 `guard_approvals` 同一迁移）

| 列 | 说明 |
|---|---|
| `id` UUID | 决策 id，应用生成 |
| `tenant_id` | 租户 |
| `idempotency_key` | 可空；`(tenant_id, idempotency_key)` 部分唯一索引 |
| `policy_module`, `policy_function`, `policy_version_id`, `source_hash` | 被评估的策略与版本 |
| `principal_id`, `action_name`, `resource_type`, `resource_id`, `context_hash` | Action 摘要（不存明文 context） |
| `outcome`, `reason`, `required_role`, `reason_code`, `rule_id`, `controls` JSONB | 规则结论 |
| `receipt_audit_id`, `receipt_hash` | 决策事件在审计链中的行 id 与哈希 |
| `response_json` JSONB | **当前**响应体：落库时写入决策响应；审批迁移（APPROVED / REJECTED / EXPIRED）时，与审批行在同一事务内改写为重算后的响应（§4.2）。幂等重放与 `GET` 都从它读取 |
| `requested_by`, `requested_api_key_id` | 提交决策请求的已解析身份：`RequestIdentityResolver.performedBy()` 与已验证的 `apiKeyId()`，按列宽截到 255。用于审批四眼（§4.2）。Flyway `V6.27.1` 新增；之前写入的行为 NULL |
| `created_at` | |

文本列宽：`principal_id` / `action_name` / `resource_type` / `resource_id` / `idempotency_key` / `required_role` / `rule_id` 为 255（与 §2.2 上限一致），`reason` 为 1000。决策行与待审批行在**同一个** `requiringNew` 事务中写入：先写决策行，再写审批行（外键）。不会出现「决策显示 PENDING 却没有审批行」的半写状态。

索引：`guard_decisions_idem_uq`（幂等查重，§3.1）；`guard_decisions_tenant_created_ix (tenant_id, created_at DESC)` 目前没有查询使用，预期消费者是未来的租户决策列表端点（按创建时间倒序分页）。该端点上线前它只增加写入开销；若最终不做列表端点，在下次改动本表的迁移中删除。

`V6.27.0` 迁移 SQL 里写着 `response_json` 存「首次响应体（幂等重放）」，这句注释已过时，以本节为准。已发布的迁移受 checksum 锁定，不再修改。

### 3.4 `GET /api/v1/guard/decisions/{id}`

返回与 3.1 相同的响应体，`approval.status` 与 `effectiveOutcome` 反映当前状态。实现：读取 `response_json`；存储的响应仍为 PENDING 时，再查审批行，已过期按 EXPIRED → DENY 返回（只读，不写库）。审批行缺失同样按 EXPIRED 处理（fail-closed）。非法 UUID 与跨租户一律返回 404（空体），不泄露决策是否存在。

---

## 4. 决策三：待审批

### 4.1 表 `guard_approvals`（Flyway `V6.27.0`）

| 列 | 说明 |
|---|---|
| `id` UUID | 主键，应用生成 |
| `tenant_id` | 租户 |
| `decision_id` | 关联 `guard_decisions.id`（外键，唯一：一条决策至多一条待审批） |
| `outcome` | `REQUIRE_APPROVAL` 或 `ESCALATE` |
| `required_role` | REQUIRE_APPROVAL 的 role；ESCALATE 为 null（任意复核人） |
| `principal_id` | 发起主体，用于四眼 |
| `status` | `PENDING / APPROVED / REJECTED / EXPIRED`（CHECK 约束） |
| `decided_by`, `decided_at`, `comment` | 审批记录 |
| `expires_at` | 创建时间 + 24 小时（`aster.guard.approval-ttl=${ASTER_GUARD_APPROVAL_TTL:PT24H}`） |
| `receipt_audit_id` | 审批事件在审计链中的行 id。该行是这次迁移的**权威**事件（§4.2） |
| `created_at` | |

### 4.2 端点

| 端点 | 角色 | 规则 |
|---|---|---|
| `GET /api/v1/guard/approvals?status=PENDING&page&size` | MEMBER | 租户内列表，默认 PENDING，按 `created_at` 倒序；`status` 非四值之一 → 400 `invalid_status`；`page` 从 0 起，`size` 夹到 1–200。`status=PENDING` 只返回 `expires_at >= now` 的行（§4.3）。响应为字母序信封 `{ items, page, size }`（`GuardApprovalListResponse`）：`items` 为空时仍写 `[]`，`page` / `size` 为夹取后的实际值；不返回总数 |
| `POST /api/v1/guard/approvals/{id}/approve` | MEMBER | 四眼：审批人不是发起主体，也不是提交该决策的用户或 API key（见下）；`required_role` 非空时 `X-User-Business-Role` 必须相等；状态必须 PENDING 且未过期；`comment` 可选；成功后 `effectiveOutcome = ALLOW` |
| `POST /api/v1/guard/approvals/{id}/reject` | MEMBER | 同上校验；`comment` 必填；`effectiveOutcome = DENY` |

approve / reject 的请求体是 JSON 对象，形如 `{ "comment": "…" }`；不带 comment 时也要发送 `{}`，并带 `Content-Type: application/json`（资源类声明了 `@Consumes(APPLICATION_JSON)`，不带请求体与该头会得到 415）。SDK 始终发送请求体。

审批人身份取 `RequestIdentityResolver.performedBy()`：API key 鉴权时是已验证的用户，否则是 `X-User-Id`。审批请求所用的 API key 取 `RequestIdentityResolver.apiKeyId()`，只信任过滤器验证过的值。

四眼按已解析身份做三项比对，任一相等即 403 `segregation_of_duties`：

1. 审批人 = 审批行的 `principal_id`（发起主体）；
2. 审批人 = 决策行的 `requested_by`（提交该决策请求的用户）；
3. 审批请求的 API key = 决策行的 `requested_api_key_id`（提交该决策请求所用的 key）。

只比对双方都非空白的值。所以 `V6.27.1` 之前写入、没有请求方身份的决策行，只按第 1 项比对。第 2、3 项堵住的是「同一个调用方换一个 `principal.id` 就能批准自己提交的动作」。

`X-User-Business-Role` 此前 aster-api **从未读取**。本子项目只在审批端点以 `@HeaderParam` 读取它，把它当作**可信 BFF 传入的标签**：值由调用方声明，aster-api 不校验其真实性，去掉首尾空白后与 `required_role` 做字符串精确比对（大小写敏感）。所以角色匹配的强度取决于 BFF。经过验证的角色来源与受控词表留给子项目 3（§10）。

校验顺序：审批人身份（空白或 `anonymous` → `user_required`）→ 存在（取审批行与决策行）→ PENDING 且未过期 → 四眼 → 角色 → 拒绝须带 comment → 列宽。错误码：

| 错误码 | HTTP | 情况 |
|---|---|---|
| `approval_not_found` | 404 | 非法 UUID 或跨租户（不泄露审批是否存在） |
| `approval_not_pending` | 409 | 非 PENDING、已过期，或 tx2 复核失败 |
| `segregation_of_duties` | 403 | 审批人等于发起主体或提交请求的用户，或与提交请求共用同一 API key |
| `role_mismatch` | 403 | `X-User-Business-Role` 与 `required_role` 不等 |
| `user_required` | 400 | 审批人标识为空白或为兜底值 `anonymous`（如 HMAC 签了空 `X-User-Id`，ADR 0042 §4.2） |
| `comment_required` | 400 | 拒绝未带 comment |
| `invalid_approval` | 400 | 审批人标识超过 255 字符，或 comment 超过 1000 字符（按列宽拒绝，不静默截断） |
| `invalid_status` | 400 | 列表的 `status` 非法 |
| `receipt_unavailable` | 503 | 审批事件写入审计链失败；审批状态不变 |

approve / reject 的事务顺序（`GuardApprovalService` + `GuardDecisionJpaStore.completeApproval`）。审计追加不在任何持有审批行锁的事务里进行：

1. **读取与校验（tx1）**：在短事务里分别读出审批行与决策行（四眼要用决策行的请求方身份），事务外按上述顺序校验，把脱管实体设为目标状态（status / decidedBy / decidedAt / comment）。`decidedAt` 记录这一刻。
2. **追加审批事件（自身事务）**：`appendSync` 写一条 `ACTION_GUARD_APPROVAL`，metadata 的 `decisionReceiptHash` 指回决策收据。失败返回 503 `receipt_unavailable`，审批状态不变。
3. **迁移（tx2，`requiringNew`）**：以 `PESSIMISTIC_WRITE` 重读审批行。复核两点：行仍为 PENDING；批准 / 拒绝时，用**加锁之后取的 `Instant.now()`** 判定尚未过期。通过后在同一事务内写审批终态与 `receipt_audit_id`，并把 `guard_decisions.response_json` 改写为重算后的响应。

不能用 tx1 的 `decidedAt` 做复核：tx1 校验之后行可能已经到期，读取方此时已按过期得到 DENY，再提交 APPROVED 就会让同一决策从 DENY 翻成 ALLOW。

如果 tx2 发现行已不可迁移（他人先处理，或已到期），返回 409 `approval_not_pending`。第 2 步写入的事件作为历史事实留在链上，表示一次未生效的审批尝试。某次迁移的**权威事件**是 `guard_approvals.receipt_audit_id` 指向的那一条。审批成功 / 拒绝 / 过期各追加一条 `ACTION_GUARD_APPROVAL` 事件（§5）。

### 4.3 过期

`GuardApprovalExpiryScheduler` 的调度配置：

- cron `${aster.guard.approval-expiry-cron:0 * * * * ?}`，即每分钟；环境变量 `ASTER_GUARD_APPROVAL_EXPIRY_CRON`。
- identity `guard-approval-expiry`，`concurrentExecution = SKIP`，`skipExecutionIf = BackgroundSchedulerSkipPredicate`。
- 调度方法不加 `@Transactional`。

每轮跨租户取至多 500 条 `PENDING AND expires_at < now`（按 `expires_at` 升序），逐条走与人工审批相同的「追加事件 → tx2 加锁迁移」：outcome 为 `EXPIRED`，performedBy 为 `SYSTEM`。tx2 对 EXPIRED 只复核「仍为 PENDING」。单条失败只记日志，下一轮重试。

读取路径不依赖调度器及时：`GET /decisions/{id}` 与幂等重放对已过期的 PENDING 直接返回 `effectiveOutcome = DENY`（只读判定）。边界：`expires_at` 当刻仍可审批，判定式为 `expiresAt.isBefore(now)`。

列表：`status=PENDING` 只列 `expires_at >= now` 的行。所以「已过期、但调度器还没落库」的行**既不在 PENDING 也不在 EXPIRED 列表里**，直到下一次调度把它写成 EXPIRED。

多副本：`SKIP` 只在单个 JVM 内互斥。多个副本可能同时扫到同一行，各写一条 EXPIRED 事件，tx2 只让其中一次生效，链上会出现重复的 EXPIRED 事件（§10）。

### 4.4 为什么不用工作流引擎

`WorkflowRuntime` 没有信号语义，`PAUSED` 无人写入，REST 无 start / resume；接进去要碰事件溯源与确定性回放规则。一张状态表加两个端点能完整表达「等人」，且审计链已经提供不可改写的记录。

---

## 5. 决策四：决策收据

### 5.1 同步追加

`AuditEventListener.onAuditEvent` 的持久化主体抽成 `io.aster.audit.chain.AuditChainAppender`，同步与异步两条路径共用同一套逻辑：脱敏、metadata 序列化、每租户 `pg_advisory_xact_lock`、哈希计算（`AuditHashPayload` 版本与算法不变）。两个入口：

- `appendSync(AuditEvent) → AppendedRecord(auditId, currentHash, prevHash)`：在 `QuarkusTransaction.requiringNew()` 中落库，提交后才返回收据。任何失败都抛 `AuditAppendException`，事务回滚，**表中不留行**。guard 的决策收据与审批事件都走它，异常由 guard 映射为 DENY `receipt_unavailable`（决策），或 503 `receipt_unavailable`（审批）。
- `appendInCurrentTransaction(AuditEvent)`：在调用方当前事务内追加，供自带 `@Transactional` 的异步监听器使用。改用 `persistAndFlush`：约束、列长等列级写入错误在方法内暴露，被监听器捕获，计入 `recordAuditLogWriteFailure` 并记 error 日志。此前只 `persist()`，这类错误推迟到事务提交时才抛，落在 try/catch 之外，静默回滚且不计数。哈希值与成功路径不变。

约束：`appendSync` **不得**在已持有同租户链锁的事务内调用。`requiringNew` 会在另一条连接上等待这把锁，形成自死锁，而 PostgreSQL 检测不到这种跨连接等待。guard 服务只在非事务的 worker 线程上调用它；资源方法不加 `@Transactional`。

### 5.2 事件

新增 `EventType.ACTION_GUARD_DECISION`、`ACTION_GUARD_APPROVAL`（列是 VARCHAR(50)，无需迁移）。字段映射：

| 审计列 | 值 |
|---|---|
| `policy_module` / `policy_function` | 被评估的策略 |
| `to_version` | 策略版本号 |
| `success` | outcome 为 ALLOW 时 true，其余 false |
| `reason` | Verdict 的 reason：追加器从 `metadata.reason` 提取，经既有 PII 脱敏后写入 |
| `error_message` | reasonCode |
| `performed_by` | API key 解析出的 userId（截到列宽 100） |
| `metadata` | `decisionId, outcome, effectiveOutcome, reasonCode, reason, ruleId, controls, principalId, principalType, principalRoles, actionName, resourceType, resourceId, contextHash, agentModel, policyVersionId, sourceHash, approvalId, idempotencyKey`；null 与空列表不写；controls 至多 16 条、每条至多 64 字符；`principalRoles` 是 List of Text，至多 16 项、每项至多 255 字符 |

`contextHash` 的算法记为 `sha256-sorted-json/v1`：`context` 用 Jackson 序列化，开启 `ORDER_MAP_ENTRIES_BY_KEYS`（键排序），再取 SHA-256 hex；null 视同 `{}`。摘要与键的写入顺序无关。**不用** `CanonicalJson`：它对非整数数字要求类型上下文，而 Action context 是自由 Map。**不存明文 context**。

metadata 预算：追加器在 metadata 超过 4 KB 时会截断成非法 JSON，所以决策事件在构造后强制 ≤ 3800 字符，为脱敏膨胀留出余量。测量口径与追加器一致：取「原样序列化」与「String 值经 PII 脱敏后再序列化」两者中较长的一个（脱敏可能变长，如 `a@b.cc` → `***@***.***`；`"` 与 `\` 转义后长度翻倍）。脱敏的豁免判定与追加器共用同一个静态方法 `AuditChainAppender.isRedactionExempt`，测量不会与实际写入漂移。超出时按固定顺序缩短：`reason → principalRoles → agentModel → principalType → resourceType → resourceId → actionName → principalId`，最后才动「谁对什么做了什么」。文本字段每轮缩到一半（不切开代理对）；`principalRoles` 每轮删掉最后一项，保留下来的项不截断。直到达标或该字段为空，再处理下一个。自由文本全部清空仍超限时，最后删除 `controls`。身份与结构键（`decisionId`、`approvalId`、`contextHash`、`outcome`、`effectiveOutcome`、`reasonCode`、`ruleId`、`policyVersionId`、`sourceHash`、`idempotencyKey`）永不改动。`reason` 被缩短时，`audit_logs.reason` 随之变短；决策行的 `reason` 列不受影响。追加器只对顶层 String 值做 PII 脱敏，`principalRoles` 与 `controls` 的列表元素原样入链。但身份键（`decisionId`、`approvalId`、`idempotencyKey`、`decisionReceiptHash`、`contextHash`、`sourceHash`）的值，以及任何整串为规范 UUID 形态（8-4-4-4-12 十六进制）的顶层 String 值，豁免脱敏。原因：`PIIRedactor.CREDIT_CARD_PATTERN` 会把含「4 组 4 位数字」片段的 UUID 改写为 `****-****-****-****-…`，随机 v4 UUID 约 1/800 命中，被改写的收据就再也对不上它指向的决策行与审批行。这与 `tenantId` 不脱敏是同一类问题（`AuditIdentifierRedactionTest`）。

写入顺序：决策收据在决策行落库**之前**追加，所以没有收据就不会有放行。代价是：如果落库失败，或在幂等并发中落败（§3.1），链上会留下一条收据事件，其 `decisionId` / `approvalId` 没有对应的行。链本身仍然有效；这类孤儿事件与进程在追加后、落库前崩溃产生的事件同类，审计时以表中存在的行为准。

审批事件 metadata：`decisionId, approvalId, outcome(APPROVED/REJECTED/EXPIRED), decidedBy, requiredRole, comment, decisionReceiptHash`，让第二条事件指回第一条的哈希；comment 截到 1000。审批事件同样按 3800 预算测量与缩短，`comment` 是唯一可缩短的键，其余键永不改动。按现有列宽（comment ≤ 1000，decidedBy / requiredRole ≤ 255），最坏转义约 3.3 KB，实际不会触发；走预算是为了日后列宽放宽时不会退回非法 JSON 截断。过期事件的 performedBy 为 `SYSTEM`；`success` 只在 APPROVED 时为 true。审批事件同样先于加锁迁移写入（§4.2）：同一审批可能对应多条事件，以 `guard_approvals.receipt_audit_id` 指向的那条为准。

### 5.3 策略版本信息

`PolicyEvaluationResult` 不带版本 id 与 sourceHash。guard 服务在评估之前自行解析活跃 `PolicyVersion`，取 `id / version / sourceHash` 写进响应、决策行与收据；查不到时返回 404 `policy_not_found`。解析方式是在 `requiringNew` 事务中直接查仓库（`PolicySourceRepository.findActiveCatalog` + `findActiveVersion`），**不经** `PolicyVersionResolutionCache`。

评估服务内部仍经 `PolicyVersionResolutionCache` 解析版本，该缓存的 TTL 为 `aster.policy.version-cache.ttl-ms`，默认 5000 ms。所以在激活版本切换后至多约 5 秒内，收据记录的版本可能与实际评估的版本不一致。统一两者的版本来源列入 §10。

---

## 6. 决策五：`aster-guard` 仓库

- 组织 `aster-cloud`，npm 包 `@aster-cloud/aster-guard`，Node ≥ 24，ESM，vitest，发布流程照 `aster-lang-ts/.github/workflows/release.yml`（tag-push、trusted publisher、provenance）。独立版本号，不进 ADR 0023 的 lockstep。
- **`GuardClient`**：`check(action, opts) → Decision`、`get(decisionId)`、`waitFor(id, { timeoutMs, intervalMs, signal })`、`listApprovals(status, page, size)`、`approve(id, { comment?, businessRole? })`、`reject(id, { comment, businessRole? })`。配置：`baseUrl`、`apiKey`、`policyModule`、`policyFunction`、`principal`。任何非 200 响应或网络错误解析为 `effectiveOutcome = DENY`（fail-closed 在客户端也成立）。
- **`aster-guard-mcp`**（bin）：stdio MCP 服务器，用 `@modelcontextprotocol/sdk` 启动，连接一个上游 MCP 服务器（`--upstream "<cmd> [args]"`），转发 `tools/list`，拦截每次 `tools/call`：构造 Action（`action.name` = 工具名；`context` = 工具参数，check 之前剔除其中的 null 值，服务端不接受 null；`resource` 由可选的 `--resource-from <argName>` 给出：取上游工具参数中该顶层参数名的值作 `resource.id`，`resource.type` 为 `arg`，对象 / 数组值以 JSON 序列化作 id，超过 255 字符（或含控制字符）改为 `sha256:<hex>`；`principal` 来自配置），调用 `GuardClient.check`；ALLOW 转发；DENY 返回 MCP 工具错误（含 reason 与 decisionId）；PENDING 则 `waitFor` 到 `--approval-timeout`（默认 300 秒）后按最终结果转发或拒绝。放行后等待上游工具返回最长 `--upstream-timeout <秒>`（默认 600），超时下游收到 MCP 协议错误；下游请求带 `progressToken` 时，代理把上游的 progress 通知原样转发给下游，且每收到一次 progress 重新计时。无上游时只暴露 `guard_check` / `guard_status` / `guard_list_approvals` / `guard_approve` / `guard_reject` 五个工具，供人机协作场景。
- **幂等键策略**（SDK 与代理一致，对应 §3.1）：
  - 每一次动作尝试生成一个唯一的 `Idempotency-Key`（如 UUID），只在同一次尝试的网络重试之间复用；
  - **绝不**由 MCP JSON-RPC 的请求 id 派生：该 id 只在单个连接内唯一，重连或换会话后会重复，会让不同动作撞键，触发 422 或错误重放；
  - 收到瞬时 DENY（`evaluation_failed`、`receipt_unavailable`）后，若要重试，必须换新键，因为原键已绑定那次 DENY；
  - 收到 422 `idempotency_mismatch` 说明键生成有缺陷（同一个键被用在了别的动作上），按 DENY 处理。
- 测试：单元（mock fetch）、代理集成（内存 MCP 上游 + mock guard HTTP），对本地 aster-api 的端到端留作可选脚本。

---

## 7. 迁移与兼容

主体是加法：

- Flyway `V6.27.0__create_guard_tables.sql` 新建两张表，`V6.27.1__guard_decisions_requester.sql` 给 `guard_decisions` 加两个可空列（`requested_by`、`requested_api_key_id`）；`migration-checksums.golden` 按字典序各插入一行。
- 新增事件类型 `ACTION_GUARD_DECISION` / `ACTION_GUARD_APPROVAL`。
- 新增五个端点，全部写入 `public-api-contract.golden`，受公开 API 契约守卫锁定。契约扫描器只识别字符串字面量的 `@Path`，所以 `GuardDecisionResource` 的类级注解写成字面量，常量 `PATH` 只留给计量。
- `ApiKeyAuthFilter.shouldProtect` 新增 `/api/v1/guard` 前缀。
- `X-User-Business-Role` 只在审批端点读取。

对既有代码有两处行为变化，都不破坏既有调用方：

1. **池化 Truffle Context 的宿主访问放宽**（§2.3）：对全部已部署规则生效，Map / List / 数组参数的元素从「不可读」变为「可读」。原先能跑的规则不受影响。
2. **异步审计监听器的失败计量更严格**（§5.1）：列级写入错误现在计入 `recordAuditLogWriteFailure`，指标数值可能上升，因为此前这类错误漏计。哈希与成功路径不变，既有哈希链、篡改检测与并发测试全部通过，未作修改。

`AuditIdentifierRedactionTest` 读取生产源码断言脱敏映射，映射代码迁出后该测试改指 `AuditChainAppender.java`，断言内容不变。

回滚：删除 guard 包与过滤器前缀条目即可；`guard_*` 两表与新事件类型留存无害。HostAccess 放宽可以单独回退，但回退前须确认没有其他已部署规则依赖 Map / List 参数。

实施结果见 §8.1。

---

## 8. 测试与验证

| 层 | 验证 |
|---|---|
| 探针 | `@QuarkusTest`：部署 `GuardRequest` 规则，`evaluatePolicy` 传 Map，断言能读 `req.action`（§2.3） |
| guard 服务 | 单测：Action 扁平化、fail-closed 映射全部分支、metadata 大小断言、四眼与角色校验、过期判定 |
| REST | `@QuarkusTest` + Podman Postgres：决策 ALLOW / DENY / REQUIRE_APPROVAL 三条路径、幂等重放、审批 approve / reject / 四眼 403 / 过期 DENY、`verify-chain` 对含 guard 事件的区间返回 valid |
| 审计 | `AuditChainAppender` 同步路径与异步路径产生相同哈希；失败时无行写入 |
| aster-guard | vitest：客户端 fail-closed、轮询、MCP 代理三条路径 |

本地验证顺序：api `./gradlew --offline :replay:test` → `:test`（带 PG + Redis 容器）→ aster-guard `pnpm test`。

### 8.1 实施结果（aster-api，2026-10-08）

各任务的定向验证。数字取自各任务最终一轮；重试插件会把失败用例计两次，下表不含重试：

| 任务 | 测试类与用例数 | 结果 |
|---|---|---|
| 1 探针与 HostAccess | `DeployedMapParamProbeTest` 1；回归 `io.aster.policy.runtime.*` + `DynamicPolicyExecutionTest` 共 16（6 个按 `aster.truffle.tests.skip` 既有跳过） | 0 失败 |
| 2 迁移与实体 | `GuardEntitiesPersistenceTest` 5、`MigrationGovernanceTest` 6 | 0 失败 |
| 3 `AuditChainAppender` | 审计相关一次运行共 164（含新 `AuditChainAppenderTest` 3；哈希链、篡改检测、并发等既有测试未改动，`AuditIdentifierRedactionTest` 只改了源码路径，见 §7） | 0 失败 |
| 4 领域层 | `GuardActionMapperTest` 11、`GuardDecisionJpaStoreTest` 3、`GuardDecisionServiceTest` 24、`GuardReceiptBuilderTest` 9 | 0 失败 |
| 5 决策 REST | `GuardDecisionResourceTest` 9、`GuardDecisionResourceQuotaTest` 5、`ApiKeyAuthFilterShouldProtectTest` 31 | 0 失败 |
| 6 审批 REST、过期、waitSeconds | `io.aster.guard.*` + `ApiKeyAuthFilterShouldProtectTest` 共 134（`GuardApprovalServiceTest` 15、`GuardDecisionJpaStoreTest` 6、`GuardDecisionServiceTest` 31、`GuardApprovalResourceTest` 11 等） | 0 失败 |

全量回归命令（Postgres 17 容器 :55432 + Redis 7 容器 :6379）：

```bash
DB_JDBC_URL=jdbc:postgresql://localhost:55432/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres \
  ./gradlew --offline :replay:cleanTest :cleanTest :replay:test :test --continue
```

- `:replay:test`：9 个类、97 个用例，0 失败。
- `:test`：219 个类，Gradle 报 `1551 tests completed, 4 failed, 14 skipped`。4 次失败是 2 个用例各被重试一次，都是本分支之前就存在的失败：
  - `PolicyGraphQLResourceTest.testBatchEvaluateLargeVolume`：5 秒性能断言，本机抛 `TimeoutException`；
  - `PrismaDatabaseE2ETest.test0_cleanupStaleData`：硬编码 `localhost:5432`，连接被拒，Quarkus 启动失败，同类其余 7 个用例跳过。
- guard 相关：`io.aster.guard.*` 共 103 个用例、`ApiKeyAuthFilterShouldProtectTest` 31、`io.aster.audit.chain.*` 49，全部通过。

首轮全量回归还暴露了两处由本分支引起、各任务定向验证未覆盖的守门失败，已在随后的测试修正提交中处理（「契约 golden 与非确定性来源白名单纳入 Action Guard」）：

- `PublicApiContractTest`：三个审批端点需要写入契约 golden。
- `NonDeterminismSourceTest`：`GuardDecisionService` 的 `UUID.randomUUID`（决策 / 审批主键）与 `System.nanoTime`（waitSeconds 截止时钟）需要登记进白名单并写明理由。

全量回归之后的评审修正轮（四眼按已解析身份比对、幂等指纹、roles 校验与入收据、决策端点纳入契约），覆盖测试一次运行（guard-pg :55432）：`io.aster.guard.*`、`io.aster.audit.chain.*`、`ApiKeyAuthFilterShouldProtectTest`、`PublicApiContractTest`、`NonDeterminismSourceTest`、`MigrationGovernanceTest`，共 206 个用例，0 失败、0 跳过。其中 guard 共 115 个：`GuardActionMapperTest` 13、`GuardApprovalServiceTest` 19、`GuardDecisionServiceTest` 34、`GuardReceiptBuilderTest` 10、`GuardApprovalResourceTest` 12、`GuardDecisionResourceTest` 10，其余不变。

终审修正轮（§9 2026-10-08 终审条目），定向运行一次（Postgres 17 容器 :55432 + Redis 7 容器 :6379，`DB_JDBC_URL` 指向前者）：`io.aster.guard.*`、`io.aster.audit.chain.*`、`io.aster.*Contract*`、`io.aster.*NonDeterminism*`、`io.aster.*Migration*`、`AuditIdentifierRedactionTest`。guard 共 123 个用例，0 失败、0 跳过：新增 `GuardReceiptPersistenceTest` 1，`GuardReceiptBuilderTest` 15、`GuardDecisionServiceTest` 35、`GuardApprovalServiceTest` 20，其余不变；`io.aster.audit.chain.*` 54 个（新增纯 JUnit `AuditChainAppenderRedactionTest` 5），0 失败。唯一失败是被 `*Migration*` 过滤选中的 `PrismaDatabaseE2ETest.test2_flywayMigrationSuccessful`（硬编码 `localhost:5432`），与本分支无关。

---

## 9. 决策记录

| 日期 | 决定 | 决策者 |
|---|---|---|
| 2026-10-07 | Agent 接入层新建仓库 `aster-guard`，不进 aster-lang-ts | 用户 |
| 2026-10-07 | 收据写入失败 → DENY（无收据则拒绝） | 用户 |
| 2026-10-07 | 本期只做 API，cloud 收件箱 UI 归子项目 3 | 用户 |
| 2026-10-07 | 评估绑定按策略地址直连，Action 作为单一记录参数 | 用户随设计定稿 |
| 2026-10-07 | 非 Verdict 结果一律 DENY `not_a_verdict`，不沿用 Boolean 映射 | Claude 提议，用户随设计定稿 |
| 2026-10-07 | 待审批用新表而非工作流引擎；过期默认 24 小时 | Claude 提议，用户随设计定稿 |
| 2026-10-07 | aster-api 在原目录建分支 `feat/adr-0040-action-guard`，因为 `includeBuild` 依赖同级目录；aster-guard 建为新本地仓库 `/Users/rpang/IdeaProjects/aster-guard`，远程创建与推送留给用户 | Claude 实施期裁定 |
| 2026-10-07 | 配额允许在 guard 资源中直接注入 `ApiQuotaGuard`，不强制重构 `PolicyEvaluationResource.enforceApiQuota`（私有且耦合 trial 旁路与响应头）。代价：两处配额逻辑轻微重复。范围后由 2026-10-08 的限速与计量裁定补足 | Claude 实施期裁定 |
| 2026-10-07 | `contextHash` 用 `sha256-sorted-json/v1`（Jackson 键排序 + SHA-256），不用 `CanonicalJson`：后者对非整数数字要求类型上下文，而 Action context 是自由 Map（§5.2） | 实施计划（Claude） |
| 2026-10-07 | 探针证实部署路径读不了 Map 参数（`HostObject 不支持成员访问，成员：action`），按 §2.3 预案在 `newPooledContext()` 开启 array / list / map 访问，`EXPLICIT` 基线不变，对全部已部署规则生效 | Claude 实施期裁定 |
| 2026-10-07 | 传给规则的 Map / List 一律不可变（`Map.copyOf` / `List.copyOf`），因为 `allowMapAccess` 同时暴露 interop 写入 | Claude 实施期裁定 |
| 2026-10-07 | 异步审计监听器改用 `persistAndFlush`：列级 DB 错误不再在事务提交时静默回滚，而是在监听器内被捕获、计入 `recordAuditLogWriteFailure` 并记日志。这让失败计量更诚实，哈希与成功路径不变。代价：失败指标数值上升（原本漏计） | Claude 实施期裁定 |
| 2026-10-07 | `appendSync` 不得在已持有同租户链锁的事务内调用：`requiringNew` 在另一条连接上等锁会自死锁，且 PostgreSQL 检测不到。guard 服务只在非事务 worker 线程调用，约束写入 Javadoc | Claude 实施期裁定 |
| 2026-10-08 | 决策行与待审批行在同一 `requiringNew` 事务写入，避免「PENDING 却没有审批行」。Verdict 的 role / reason 超过 255 字符 → DENY `not_a_verdict`，不静默截断，因为截断会让角色比对失真。`Idempotency-Key` 由服务层校验（空白视为无；超过 255 或含控制字符 → `invalid_action`），避免收据写入后才因列宽落库失败 | Claude 实施期裁定 |
| 2026-10-08 | 收据 metadata 由构造器按序列化长度强制 ≤ 3800，为脱敏膨胀留余量；超出时按固定优先级截短自由文本，`decisionId` / `approvalId` / `contextHash` 等标识字段永不截断。代价：极端输入下收据里的自由文本会被截短 | Claude 实施期裁定 |
| 2026-10-08 | guard 端点必须与 `/evaluate` 同等限速（`checkRate` → 429 `rate_limit_exceeded` + `Retry-After`），并以同一底层（`recordAsync`）计量：每次 guard 调用都含一次同步审计追加，不限速、不计量就是免费且可放大的写入面。实现采用镜像，未抽共享组件。代价：与 `PolicyEvaluationResource` 轻微重复 | Claude 实施期裁定 |
| 2026-10-08 | 幂等重放返回同一决策（同 `decisionId`、同收据），但 `approval.status` / `effectiveOutcome` 反映当前状态，而不是首次响应体原样。理由：两阶段写入本就改写 `response_json`，且 Agent 重试时直接看到当前结论更安全。代价：要求「逐字节相同重放」的客户端会看到状态字段变化 | Claude 实施期裁定 |
| 2026-10-08 | 审批事件先于行锁写入可以接受，它与崩溃、竞争产生的孤儿事件同类；以 `guard_approvals.receipt_audit_id` 指向的事件为权威。代价：链上可能存在未生效的 APPROVED / REJECTED / EXPIRED 事件 | Claude 实施期裁定 |
| 2026-10-08 | 四眼按已解析身份比对：审批人不得等于 `principal_id`、决策行的 `requested_by`，审批所用 API key 不得等于 `requested_api_key_id`（新迁移 `V6.27.1` 记录请求方身份）。原因：`principal.id` 由请求体自报，只比对它时，同一调用方换一个 principal 就能批准自己提交的动作。`X-User-Business-Role` 仍是可信 BFF 标签，经过验证的角色来源留后续 | Claude 评审裁定 |
| 2026-10-08 | 幂等键加指纹：键命中时比对策略地址、主体、动作、资源与 context 摘要，不符 → 422 `idempotency_mismatch`。原因：重放语义下，复用键会把一个动作的结论（甚至已批准的 ALLOW）借给另一个动作。SDK 与代理每次动作尝试用唯一键，不从 MCP JSON-RPC id 派生（§6） | Claude 评审裁定 |
| 2026-10-08 | `principal.roles` 纳入校验（≤ 16 项，每项 ≤ 255 字符、无控制字符）并写入收据 metadata 的 `principalRoles`，超预算时排在 `principalType` 之后、从末尾逐项删除。原因：roles 是规则输入，应与其他请求字段同样受约束且可追溯 | Claude 评审裁定 |
| 2026-10-08 | 终审修正：①追加器对身份键（`decisionId`、`approvalId`、`idempotencyKey`、`decisionReceiptHash`、`contextHash`、`sourceHash`）与规范 UUID 形态的值豁免 PII 脱敏，`CREDIT_CARD_PATTERN` 会改写约 1/800 的 v4 UUID，使收据对不上决策行；收据构造器的预算测量共用同一判定。②决策事件缩短顺序改为 `reason → principalRoles → agentModel → principalType → resourceType → resourceId → actionName → principalId`（取代上一条 roles 排在 `principalType` 之后的安排）：先缩体积最大、追溯价值最低的字段，主体标识最后。③审批事件也走 3800 预算，`comment` 是唯一可缩短的键。④审批列表响应改为字母序信封 `GuardApprovalListResponse { items, page, size }`。代价：身份键若被调用方塞入 PII（如把邮箱当 `Idempotency-Key`）会原样入链 | Claude 终审裁定 |

## 10. 尚未回答的问题

- ~~**经过验证的角色来源**~~（已由 ADR 0042 解决）：业务角色挂在 cloud 团队成员/用户上，随 API key verify 与快照或 HMAC v3 签名下发，`required_role` 与已验证角色集合匹配，调用方自报的 `X-User-Business-Role` 头不再被信任（ADR 0042 §2）。
- 租户级「工具名 → 策略」绑定（路径 B）：有多策略路由需求时再做。
- webhook / 推送通知审批人：本期只轮询。
- Verdict `obligations`（如「执行前脱敏」）：guard 端点有真实消费方后再以追加键方式扩展。
- 审计链 metadata 4 KB 上限与「非法 JSON 截断」：guard 用 3800 字符预算加固定缩短顺序规避（§5.2）。根治（截断为合法 JSON 或拒绝）留给审计模块。

实施期遗留的后续项：

- **抽出 `ApiQuotaEnforcer`**：`GuardDecisionResource.quotaRejection` 与 `PolicyEvaluationResource.enforceApiQuota` 约有 30 行重复（套餐裁决映射 + RPS 映射）。第三个端点需要配额时，抽成 `io.aster.billing.ApiQuotaEnforcer` 共用。
- **`X-Quota-*` / `X-RateLimit-*` 响应头**：guard 端点目前不输出。
- **500 不计量**：`service.decide` 抛出未预期异常（500）时不记 `api_error`；`/evaluate` 在失败时会记。
- **`waitSeconds` 占用 worker 线程**：每个请求最多 30 秒，生产 `quarkus.thread-pool.max-threads=32`，大量带 wait 的请求可能耗尽线程池；配额只按一次调用计量。需要加并发上限，或改为异步挂起。
- **多副本过期产生重复事件**：`SKIP` 只在单 JVM 内互斥，多副本可能各写一条 EXPIRED 事件（tx2 只让一次生效）。如需去重，改用集群锁或 advisory lock。
- **tx2 复核到提交之间的残余窗口**：tx2 在加锁后取 `now` 复核未过期，到提交之间仍有毫秒级窗口。若行恰在此间到期、且读取方已按过期得到 DENY，提交后同一决策会变为 ALLOW。
- **统一版本来源**：guard 直查仓库，评估服务走 `PolicyVersionResolutionCache`，激活切换后约 5 秒内两者可能不一致（§5.3）。
- **过期队列头部的毒行**：每轮按 `expires_at` 升序只取前 500 条。持续失败的行（如关联决策缺失、追加一直失败）每轮都会重试并占住批次头部；达到 500 条时会阻塞后面的行落库为 EXPIRED。读取路径仍按过期返回 DENY，fail-closed 不受影响。需要失败退避或隔离。
- **MCP 宿主请求超时与审批等待**：MCP 宿主默认 60 s 请求超时，而审批等待默认 300 s（`--approval-timeout`）。代理已在下游请求带 `progressToken` 时转发上游 progress，宿主侧 keepalive 仍依赖宿主配置（审批等待期间没有上游 progress 可转发）；README 已提示宿主把超时调到不小于 `--approval-timeout`。
- **metadata 缩短无记录**：`fitBudget` 缩短字段或删除 `controls` 时既不记日志也不计指标，应补上，便于发现被截短的收据。
