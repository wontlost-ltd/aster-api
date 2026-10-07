# ADR 0040：Action Guard —— AI 行动守门服务面（AI 治理方向·子项目 2）

- 状态：**PROPOSED**（2026-10-07。方向与路径已由用户拍板，实施计划待出）
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
| 人工审批 | 新表 `guard_approvals` + 三个端点，aster-api 强制四眼与角色匹配，过期即 DENY | 不复用策略版本审批状态机；不复用工作流引擎 |
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
| Map 参数进已部署规则 | 部署路径 `TrufflePolicyRuntime.newPooledContext()` 用 `HostAccess.EXPLICIT` 且未开 map / list / array 访问；源码路径 `DynamicCnlExecutor` 显式开启了三者。部署路径传 Map 参数**未经验证** |
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

### 2.3 ★未验证前提：部署路径的 Map 参数

实施计划的**第一个任务**是探针测试：部署上述规则，经 `evaluatePolicy` 传入 `new Object[]{Map}`。若 Truffle 报 `HostObject 不支持成员访问`，修法是在 `TrufflePolicyRuntime.newPooledContext()` 开启 `allowMapAccess / allowListAccess / allowArrayAccess`（与 `DynamicCnlExecutor` 一致），并在 ADR §9 记录。不采用「拆成八个标量参数」的替代方案：它让策略签名僵化且与 Action 扩展冲突。

### 2.4 结果缓存

`PolicyEvaluationService` 的结果缓存按 `(tenant, module, function, versionId, normalizedContext)` 命中。guard 的 Map 参数参与缓存键；同一动作在同一版本上重复请求命中缓存是**正确的**（规则是纯函数），但收据**每次都新写**（每次请求都是一次独立的治理事件）。

---

## 3. 决策二：决策端点

### 3.1 `POST /api/v1/guard/decisions`

- 鉴权：API key（`Authorization: Bearer ak_…`），路径加入 `ApiKeyAuthFilter.shouldProtect`；`@RequireRole(MEMBER)`；经 `enforceApiQuota`。身份经 `RequestIdentityResolver`，`performedBy` 只作标签。
- 请求：`{ policyModule, policyFunction, action, waitSeconds? }`。`waitSeconds`（0–30，默认 0）：REQUIRE_APPROVAL 时服务端最多等待这么久再返回，给同步型 Agent 一次机会拿到最终结果。
- 幂等：`Idempotency-Key` 头。表 `guard_decisions` 以 `(tenant_id, idempotency_key)` 唯一；重放返回**同一响应体**（含同一 decisionId 与收据），不是 409。无头则不去重。
- 响应（始终 200；字段字母序，null / 空不写）：

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
| 评估异常、超时、策略执行失败 | 200 | DENY | `evaluation_failed` |
| 收据写入失败 | 200 | DENY | `receipt_unavailable`（此时**不**创建待审批记录） |
| 策略不存在 / 无活跃版本 | 404 | — | `policy_not_found` |
| 请求体校验失败（缺 principal.id 等、context 超限） | 400 | — | `invalid_action` |
| 配额 / 速率 | 403 / 429 | — | 沿用既有 |

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
| `response_json` JSONB | 首次响应体，幂等重放原样返回 |
| `created_at` | |

### 3.4 `GET /api/v1/guard/decisions/{id}`

返回与 3.1 相同的响应体，`approval.status` 与 `effectiveOutcome` 反映当前状态。跨租户返回 404。

---

## 4. 决策三：待审批

### 4.1 表 `guard_approvals`（Flyway `V6.27.0`）

| 列 | 说明 |
|---|---|
| `id` UUID | 主键，应用生成 |
| `tenant_id` | 租户 |
| `decision_id` | 关联 `guard_decisions.id` |
| `outcome` | `REQUIRE_APPROVAL` 或 `ESCALATE` |
| `required_role` | REQUIRE_APPROVAL 的 role；ESCALATE 为 null（任意复核人） |
| `principal_id` | 发起主体，用于四眼 |
| `status` | `PENDING / APPROVED / REJECTED / EXPIRED`（CHECK 约束） |
| `decided_by`, `decided_at`, `comment` | 审批记录 |
| `expires_at` | 创建时间 + 24 小时（`aster.guard.approval-ttl`，可配置） |
| `receipt_audit_id` | 审批事件在审计链中的行 id |
| `created_at` | |

### 4.2 端点

| 端点 | 角色 | 规则 |
|---|---|---|
| `GET /api/v1/guard/approvals?status=PENDING&page&size` | MEMBER | 租户内列表，默认 PENDING |
| `POST /api/v1/guard/approvals/{id}/approve` | MEMBER | 四眼：`X-User-Id` ≠ `principal_id`；`required_role` 非空时 `X-User-Business-Role` 必须相等；状态必须 PENDING 且未过期；成功后 `effectiveOutcome = ALLOW` |
| `POST /api/v1/guard/approvals/{id}/reject` | MEMBER | 同上校验；`comment` 必填；`effectiveOutcome = DENY` |

违反四眼返回 403 `segregation_of_duties`；角色不符 403 `role_mismatch`；非 PENDING 返回 409 `approval_not_pending`。审批成功 / 拒绝各追加一条 `ACTION_GUARD_APPROVAL` 事件（§5）。

### 4.3 过期

`@Scheduled` 每分钟把 `PENDING AND expires_at < now()` 标为 `EXPIRED` 并追加 `ACTION_GUARD_APPROVAL`（outcome `EXPIRED`）。`GET /decisions/{id}` 对 EXPIRED 返回 `effectiveOutcome = DENY`。读取时也做同样判断，不依赖调度器及时。

### 4.4 为什么不用工作流引擎

`WorkflowRuntime` 没有信号语义，`PAUSED` 无人写入，REST 无 start / resume；接进去要碰事件溯源与确定性回放规则。一张状态表加两个端点能完整表达「等人」，且审计链已经提供不可改写的记录。

---

## 5. 决策四：决策收据

### 5.1 同步追加

把 `AuditEventListener.onAuditEvent` 的持久化主体抽成 `io.aster.audit.chain.AuditChainAppender.appendSync(AuditEvent) → AppendedRecord(id, currentHash, prevHash)`，在 `QuarkusTransaction.requiringNew()` 中执行，保留每租户 `pg_advisory_xact_lock`。异步监听器改为调用它（行为不变）。guard 调同步版本；任何异常上抛，由 guard 映射为 DENY `receipt_unavailable`。

### 5.2 事件

新增 `EventType.ACTION_GUARD_DECISION`、`ACTION_GUARD_APPROVAL`（列是 VARCHAR(50)，无需迁移）。字段映射：

| 审计列 | 值 |
|---|---|
| `policy_module` / `policy_function` | 被评估的策略 |
| `to_version` | 策略版本号 |
| `success` | outcome 为 ALLOW 时 true，其余 false |
| `reason` | Verdict 的 reason（经既有 PII 脱敏） |
| `performed_by` | API key 解析出的 userId |
| `metadata` | `decisionId, outcome, effectiveOutcome, reasonCode, ruleId, controls, principalId, principalType, actionName, resourceType, resourceId, contextHash, agentModel, policyVersionId, sourceHash, approvalId, idempotencyKey` |

`contextHash` 是 `context` 的 canonical JSON SHA-256（复用 `CanonicalJson`），**不存明文 context**。metadata 必须 ≤ 4 KB（监听器超限会截断成非法 JSON）；上表字段总量远低于上限，实施时加断言测试。

审批事件 metadata：`decisionId, approvalId, outcome(APPROVED/REJECTED/EXPIRED), decidedBy, requiredRole, comment, decisionReceiptHash`，让第二条事件指回第一条的哈希。

### 5.3 策略版本信息

`PolicyEvaluationResult` 不带版本 id 与 sourceHash。guard 服务在同一调用里解析活跃 `PolicyVersion`（经既有 `PolicyVersionResolutionCache`）取 `id / version / sourceHash` 写进响应与收据。

---

## 6. 决策五：`aster-guard` 仓库

- 组织 `aster-cloud`，npm 包 `@aster-cloud/aster-guard`，Node ≥ 24，ESM，vitest，发布流程照 `aster-lang-ts/.github/workflows/release.yml`（tag-push、trusted publisher、provenance）。独立版本号，不进 ADR 0023 的 lockstep。
- **`GuardClient`**：`check(action, opts) → Decision`、`get(decisionId)`、`waitFor(decisionId, {timeoutMs, intervalMs})`、`listApprovals()`、`approve(id, comment?)`、`reject(id, comment)`。配置：`baseUrl`、`apiKey`、`policyModule`、`policyFunction`、`principal`。任何非 200 响应或网络错误解析为 `effectiveOutcome = DENY`（fail-closed 在客户端也成立）。
- **`aster-guard-mcp`**（bin）：stdio MCP 服务器，用 `@modelcontextprotocol/sdk` 启动，连接一个上游 MCP 服务器（`--upstream "<cmd> [args]"`），转发 `tools/list`，拦截每次 `tools/call`：构造 Action（`action.name` = 工具名，`context` = 工具参数，`resource` 由可选的 `--resource-from <jsonpath>` 规则抽取，`principal` 来自配置），调用 `GuardClient.check`；ALLOW 转发；DENY 返回 MCP 工具错误（含 reason 与 decisionId）；PENDING 则 `waitFor` 到 `--approval-timeout`（默认 300 秒）后按最终结果转发或拒绝。无上游时只暴露 `guard_check` / `guard_status` / `guard_list_approvals` / `guard_approve` / `guard_reject` 五个工具，供人机协作场景。
- 测试：单元（mock fetch）、代理集成（内存 MCP 上游 + mock guard HTTP），对本地 aster-api 的端到端留作可选脚本。

---

## 7. 迁移与兼容

全部为加法：新表、新事件类型、新端点、新仓。`AuditEventListener` 内部重构不改行为（现有 `TamperDetectionIntegrationTest` 等守门）。`ApiKeyAuthFilter.shouldProtect` 新增一条前缀。无破坏性变更。

回滚：删除 guard 包与过滤器白名单条目即可；`guard_*` 两表与新事件类型留存无害。

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

## 10. 尚未回答的问题

- `required_role` 与 `X-User-Business-Role` 的匹配目前是字符串相等；受控词表（与 cloud 的团队角色对齐）留到子项目 3。
- 租户级「工具名 → 策略」绑定（路径 B）：有多策略路由需求时再做。
- webhook / 推送通知审批人：本期只轮询。
- Verdict `obligations`（如「执行前脱敏」）：guard 端点有真实消费方后再以追加键方式扩展。
- 审计链 metadata 4 KB 上限与「非法 JSON 截断」：本 ADR 用断言规避，根治（截断为合法 JSON 或拒绝）留给审计模块。
