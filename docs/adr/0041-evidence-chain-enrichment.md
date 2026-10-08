# ADR 0041：证据链补齐 —— 规则 ID、模型身份、复核者进证据包（AI 治理方向·子项目 3a）

- 状态：**PROPOSED**（2026-10-08，设计已与用户逐节确认，待实施计划）
- 提出：2026-10-08；实施计划待写（`docs/superpowers/plans/`）
- 决策者：用户已拍板「A 哈希链为权威、C 证据包引用链收据；模型身份结构化声明并标注来源；复核者统一形状按来源标注；经验证角色来源 / What-if 四态 / 审批收件箱 UI 拆为 3b、3c」
- 相关：ADR 0039（Verdict 与规则元数据，§1 路线图第 3 行）、ADR 0040（Action Guard 收据与四眼）、ADR 0030（canonical JSON 与 canonicalizationVersion）、ADR 0032（trace 锚定 Core IR span）、ADR 0037（三段式复核，PolicyProof）、aster-cloud ADR 0015（团队作用域 API key 身份）

---

## 0. TL;DR

子项目 1 让规则有了身份（`@id` / `@control`）与四态结论，子项目 2 让 AI 行动有了带收据的守门面。但对一次普通策略执行，今天的证据回答不了审计方的三个问题：**哪条规则**判的、**哪个模型**在行动、**谁复核/批准过**。证据包（aster-cloud）是对 `executions` 表的无签名哈希，与 aster-api 的哈希链脱节。

本 ADR：

1. aster-api 的 `POLICY_EVALUATION` 链事件携带 `correlationId / outcome / ruleId / controls / policyVersionId / agent`，响应返回 `evidence.correlationId`；新增只读收据查询接口。
2. 定义结构化 `AgentIdentity {provider, model, version?, session?}`，guard 与 `/evaluate*` 同一形状，收据标注 `source: "declared"`。
3. aster-cloud 的 `executions` 增可查列并把 `ExecutionDecision` 扩为五态；证据包 v2 嵌入链收据、统一的 `reviewers[]`（guard 审批 / PolicyProof / 版本审批）与模型身份，并给出用 aster-api 校验的配方。
4. 不在热路径新增同步写：`/evaluate*` 仍异步落链，导出时按相关键拉取；取不到的收据诚实标注，不伪造。

---

## 1. 背景与问题

### 1.1 证据产物现状

| 产物 | 生产者 | 存储 | 已有 | 缺 |
|---|---|---|---|---|
| 证据包（EvidenceEntry/Manifest，schemaVersion 1） | C `lib/evidence.ts` | C `ComplianceReport.data` | 四态 decision、规范化哈希、traceHash、toolchain、可重放性 | ruleId、controls、模型、复核者；无链收据、无签名 |
| `executions` 行 | C 两条执行路由 | C `Execution` | decision、哈希、traceSkeletonJson、apiKeyId（v1） | ruleId、controls、模型、复核者；REQUIRE_APPROVAL/ESCALATE 落成 indeterminate |
| `POLICY_EVALUATION` 事件 | A `PolicyAuditPublisher`（`fireAsync`） | A `audit_logs`（哈希链） | 租户、模块、函数、success、performedBy、耗时 | ruleId、controls、outcome、模型、任何可被 C 引用的键 |
| `ACTION_GUARD_DECISION` 收据 | A `GuardReceiptBuilder` | A `audit_logs.metadata` + `guard_decisions` | outcome、ruleId、controls、principal、agentModel（自由文本）、contextHash | 结构化模型身份；`agent.session` 被丢 |
| `ACTION_GUARD_APPROVAL` 事件 | A `GuardApprovalService` | A `audit_logs` + `guard_approvals` | decidedBy、requiredRole（自报头）、decisionReceiptHash | 与证据包无连接 |
| PolicyProof | C review 路由 | C `PolicyProof`（append-only） | subjectUserId、subjectKind、contentHash、verdict | 无人导出；其 `ruleId` 是验证器 id，不是策略 `@id` |

### 1.2 三个脱节

- **链与包脱节**：不可篡改的真相在 A，导出的证据在 C，两者没有任何键相连。
- **执行与规则脱节**：`/evaluate` 的链事件不知道 ruleId；只有 guard 收据知道。
- **复核者三套**：设计期 PolicyProof、运行期 guard 审批人、版本审批，互不相连，且运行期角色是未验证的 BFF 自报头。

---

## 2. 决策一：A 哈希链为权威，C 证据包引用链收据

### 2.1 相关键而非同步收据

`/evaluate*` 每次执行生成 `correlationId`（UUID v4），同时写入响应与异步落链的事件 metadata。证据包导出时按相关键批量向 A 拉取收据。

不选「`/evaluate` 同步落链返回 `{auditId, hash}`」：`/evaluate` 是 500 RPS 热路径，每次多一次带 advisory lock 的同步写；ADR 0040 §5.1 当初只为 guard 接受这个代价。guard 决策维持现有同步收据不变。

### 2.2 `POLICY_EVALUATION` 事件 metadata 新增键

`correlationId`、`outcome`（ALLOW / DENY / REQUIRE_APPROVAL / ESCALATE，非 Verdict 结果为 `null` 不写键）、`ruleId`、`controls`（数组）、`policyVersionId`（`/evaluate-source` 为 null 不写键）、`agent`（§3 的结构化对象，含 `source`）。既有键（`applicantId`、`contextSize` 等）不动。

`correlationId` 加入 `AuditChainAppender.isRedactionExempt` 的身份键白名单（与 ADR 0015 修复的 `decisionId` 等同列）——否则约 1/800 的 UUID 会被信用卡脱敏改写。

两个发布者（`PolicyAuditPublisher`、`policy/audit/AuditLogger.logPolicyEvaluation`）都经同一个 `EvaluationEvidence` 值对象取这些键，不允许各写一份。

### 2.3 响应

`EvaluationResponse` 新增 `evidence: { correlationId }`（字母序排版、`NON_NULL`，与既有字段规则一致）。`PublicApiContractTest` golden 只认 `@Path`，不受影响；`EvaluationResponse` 的字段序测试同步更新。

### 2.4 收据查询接口

`GET /api/v1/audit/receipts?correlationIds=a,b,…` 与 `GET /api/v1/audit/receipts?decisionIds=x,y,…`（两参互斥，各 ≤ 200 个），API key 保护（`ApiKeyAuthFilter.shouldProtect` 已覆盖 `/api/v1/audit`），只返回已验证租户自己的事件。

```json
{ "receipts": [ { "correlationId": "…", "auditId": 123, "eventType": "POLICY_EVALUATION",
    "timestamp": "…", "prevHash": "…", "currentHash": "…", "hashVersion": 2, "metadata": { … } } ],
  "approvals": [ { "decisionId": "…", "auditId": 124, "outcome": "APPROVED", "decidedBy": "u3",
    "requiredRole": "…", "decidedAt": "…", "currentHash": "…", "decisionReceiptHash": "…" } ],
  "missing": [ "correlationId 或 decisionId" ] }
```

`decisionIds` 路径返回 `ACTION_GUARD_DECISION` 收据与其全部 `ACTION_GUARD_APPROVAL` 事件（按 metadata 的 `decisionId` 关联）。接口只读，不触碰链。实现放 `io.aster.audit.rest.AuditReceiptResource`，查询走 `AuditLog` 实体按 `tenant_id` + metadata 中的键过滤——`metadata` 是 TEXT 列，V6.28.0 增加表达式索引 `((metadata::jsonb->>'correlationId'))` 与 `((metadata::jsonb->>'decisionId'))`（Flyway，`CREATE INDEX IF NOT EXISTS`，golden 同步）。

---

## 3. 决策二：结构化模型身份，标注来源

```java
public record AgentIdentity(String provider, String model, String version, String session) {}
```

- 每字段 Text ≤ 255、无控制字符；`provider` 与 `model` 必填（有 agent 时），`version`/`session` 可选。缺省整体为 null，不影响评估。
- guard：`GuardActionRequest.Action.agent` 由 `Agent(model, session)` 升级为本记录（`model` 单独出现仍兼容：`provider` 缺省为 `"unknown"`）。`GuardActionMapper` 把四字段扁平为 `agent_provider / agent_model / agent_version / agent_session` 传给规则（规则可按 provider 判）；收据 metadata 以结构化 `agent: {provider, model, version, session, source: "declared"}` 写入哈希，取代原顶层 `agentModel` 键（§8.1 兼容说明）。
- `/evaluate*`：`EvaluationRequest` 新增可选 `agent`，经 §2.2 进事件 metadata。
- `source` 本期恒为 `"declared"`，诚实表明是调用方自报；未来可增 `"key-bound"`（与 ADR 0015 的 key 身份绑定）而不改形状。
- 收据 3800 字符预算的收缩顺序改为：`reason → agent.session → agent.version → principalRoles → agent.model → agent.provider → principalType → resourceType → resourceId → actionName → principalId`；`correlationId / decisionId / ruleId / outcome` 永不收缩。

---

## 4. 决策三：aster-cloud 执行记录可查化

- `executions` 新列（drizzle 迁移 0050，`IF NOT EXISTS`）：`outcome text`（ALLOW/DENY/REQUIRE_APPROVAL/ESCALATE/INDETERMINATE/ERROR）、`ruleId text`、`controls jsonb`、`agent jsonb`、`evidenceCorrelationId text`（索引）。
- `ExecutionDecision` pgEnum 增 `require_approval`、`escalate`；`deriveExecutionDecision` 与 `parseApprovalFromResult` 改为专属态（去掉「子项目 3 扩展枚举后改为专属态」的临时映射）；`DecisionTally`、报表与四语文案跟进。旧行不回填：`outcome` 为 null 的行在证据包标 `legacy`。
- `PolicyEvaluateResponse` 接口补 `decision / ruleId / controls / evidence`；`buildCNLResult` 透传；两条执行路由写新列。
- `agent` 来源：v1 执行路由从请求体 `agent` 读（同 §3 形状，透传给 A）；dashboard 执行固定 `{provider: "aster-cloud", model: "dashboard", source: "declared"}`。

---

## 5. 决策四：证据包 v2 与统一复核者

### 5.1 entry 新字段

`outcome`、`ruleId`、`controls`、`agent`、`evidenceCorrelationId`、
`receipt: { auditId, currentHash, prevHash, hashVersion } | { status: "missing" | "legacy" | "unavailable" }`、
`reviewers: Reviewer[]`。

### 5.2 Reviewer 形状

```ts
type Reviewer = { userId: string; role: string; source: 'guard-approval' | 'policy-proof' | 'version-approval'; decidedAt: string; ref: string };
```

- `guard-approval`：§2.4 的 `approvals`，`role = requiredRole`，`ref = auditId`。本期 `role` 仍是自报标签，entry 内 `roleVerified: false`；3b 落地经验证角色后改 true。
- `policy-proof`：该执行 `policyVersionRowId` 对应 `PolicyProof` 的有效结论（append-only 取最后一条 VERIFIED/REJECTED），`role = subjectKind`，`ref = proof.id`。
- `version-approval`：`policyApprovals` 中该版本的审批人，`ref = approval.id`。

guard 决策与 C 执行的关联：v1 执行路由在调用 guard 时（若调用）记录 `guardDecisionId` 到 `executions.metadata.guardDecisionId`；本期 C 尚无路由调用 guard，故 `guard-approval` 仅对 `metadata.guardDecisionId` 非空的条目取数，其余为空数组。

### 5.3 manifest

`schemaVersion: '2'`；新增 `receiptSource: { kind: 'aster-api-hash-chain', verifier: 'GET /api/v1/audit/receipts' }`、`agentTally`、`reviewerTally`、`legacyEntries`、`notes.receiptsUnavailable`。`bundleHash` 仍只覆盖 entries（含新字段），文档写明 v1 与 v2 哈希不可互比；v1 已导出报告照旧可下载与校验（按其 `schemaVersion` 选校验器）。

### 5.4 导出时 A 不可达

批量拉取分页 ≤ 200/次、超时 2 s/批、失败不重试；该批条目 `receipt.status = "unavailable"`，manifest 计数，导出仍成功。证据包诚实标注而不是伪造或中断。

---

## 6. 数据流

执行：客户端（可带 `agent`）→ C 执行路由 → A `/evaluate`（返回 decision/ruleId/controls/evidence.correlationId）→ C 写 `executions` 新列 → A 异步落链（metadata 含 correlationId）。

导出：C 查 `executions` 窗口 → 批量 `GET /api/v1/audit/receipts?correlationIds` → 对带 guardDecisionId 的条目再查 `decisionIds` → 拼 `reviewers`（guard 审批 / PolicyProof / 版本审批）→ 组 v2 包 → 存 `ComplianceReport`。

校验：拿到 v2 包的人用 `receiptSource.verifier` 逐条比对 `currentHash`，再用 A 既有 `AuditChainVerifier` 校验链连续性。

---

## 7. 失败与边界

- `agent` 不合法 → 400（与现有请求体校验同口径）；缺省不影响评估。
- 相关键只做身份，不含业务数据；metadata 预算收缩顺序见 §3。
- `/evaluate-source`（trial）同样产生 correlationId 与 outcome，`policyVersionId` 不写键。
- 哈希链 append-only 不动；不新增同步写入热路径；收据接口只读。
- 旧 C（不传 `agent`、不读 `evidence`）与旧 A（不返回 `evidence`）互相兼容：字段均为可选追加。

---

## 8. 兼容性与发布

### 8.1 guard 收据 metadata 键变化

`agentModel` 顶层键改为结构化 `agent` 对象。已落链的旧收据不变（链不可改）；`GuardReceiptBuilder` 的预算测试、`GuardReceiptPersistenceTest` 与 aster-guard README 中的收据示例同步。消费者（aster-guard SDK）不读该键。

### 8.2 顺序

1. A 发布（新增键、接口、索引迁移 V6.28.0）。
2. C 迁移 0050 → C 发布（枚举扩展、新列、证据包 v2）。
反序也不坏：C 读不到 `evidence` 则 `evidenceCorrelationId` 为 null，导出标 `missing`。

---

## 9. 测试与验收

A：`EvaluationEvidence` 值对象与两个发布者的 metadata 快照测试；`AuditChainAppenderRedactionTest` 增 correlationId 豁免；`AgentIdentity` 校验（长度、控制字符、provider 缺省）；`AuditReceiptResource` @QuarkusTest（租户隔离 404/空、missing、decisionIds 返回审批事件、≤200 上限 400）；`GuardReceiptBuilderTest` 新收缩顺序；`EvaluationResponse` 字段序。

C：迁移 0050 与枚举扩展测试；`parseApprovalFromResult` / `deriveExecutionDecision` 五态；两条执行路由写新列；evidence v2 单测（entry/manifest/hash、legacy、unavailable、reviewers 三来源）；集成测试走假 A 的 receipts 接口。

验收（本地 podman 全栈）：
1. dashboard 执行一条 Verdict 策略 → 导出证据包 v2 → 条目带 `ruleId/controls/agent/receipt`，`receipt.currentHash` 与 A `audit_logs.current_hash` 相等。
2. 对该版本写一条 PolicyProof → 重新导出 → `reviewers[]` 含 `policy-proof`。
3. guard 四眼一次（KEY_FREE/KEY_DPO）→ 对带 `guardDecisionId` 的条目（本期用 SQL 写入 metadata 模拟 C 调 guard）导出 → `reviewers[]` 含 `guard-approval` 且 `decidedBy` 为审批人。
4. 旧行（迁移前的执行）导出标 `legacy`；停掉 A 再导出 → `unavailable` 计数正确、导出成功。

---

## 10. 后续（不在本 ADR）

- **3b 审批闭环**：四眼 `required_role` 匹配改用 ADR 0015 已验证团队角色 / PolicyReviewer 授权（`roleVerified: true`）；cloud 审批收件箱 UI；C 执行路由真正调用 guard 并记录 `guardDecisionId`。
- **3c What-if 四态**：`replay_batch_item` 的 `base_outcome / target_outcome`、C 窗口接口下发 `outcome`、runner 按转移对计数（消除 ADR 0039 §5.2 限制）。
- `source: "key-bound"` 模型身份；证据包签名或链锚定引用；`agent.session` 的跨请求关联。
- `/evaluate` 事件异步落链的丢失率监控（missing 计数指标）。
