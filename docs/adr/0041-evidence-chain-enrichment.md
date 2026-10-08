# ADR 0041：证据链补齐 —— 规则 ID、模型身份、复核者进证据包（AI 治理方向·子项目 3a）

- 状态：**ACCEPTED**（2026-10-08，本地全栈验收通过，见 §9.1）
- 提出：2026-10-08；实施计划 `docs/superpowers/plans/2026-10-08-evidence-chain-enrichment.md`（Task 1–9，aster-api `feat/adr-0041-evidence-chain`、aster-cloud `feat/adr-0041-execution-evidence`）
- 决策者：用户已拍板「A 哈希链为权威、C 证据包引用链收据；模型身份结构化声明并标注来源；复核者统一形状按来源标注；经验证角色来源 / What-if 四态 / 审批收件箱 UI 拆为 3b、3c」
- 相关：ADR 0039（Verdict 与规则元数据，§1 路线图第 3 行）、ADR 0040（Action Guard 收据与四眼）、ADR 0030（canonical JSON 与 canonicalizationVersion）、ADR 0032（trace 锚定 Core IR span）、ADR 0037（三段式复核，PolicyProof）、aster-cloud ADR 0015（团队作用域 API key 身份）

---

## 0. TL;DR

子项目 1 让规则有了身份（`@id` / `@control`）与四态结论，子项目 2 让 AI 行动有了带收据的守门面。但对一次普通策略执行，今天的证据回答不了审计方的三个问题：**哪条规则**判的、**哪个模型**在行动、**谁复核/批准过**。证据包（aster-cloud）是对 `executions` 表的无签名哈希，与 aster-api 的哈希链脱节。

本 ADR：

1. aster-api 的 `POLICY_EVALUATION` 链事件携带 `correlationId / outcome / ruleId / controls` 与扁平的模型身份键（`agentProvider / agentModel / agentVersion / agentSession / agentSource`），响应返回 `evidence.correlationId`；新增只读收据查询接口。
2. 定义结构化 `AgentIdentity {provider, model, version?, session?}`，guard 与 `/evaluate*` 同一形状；进链时扁平为顶层键并标注 `agentSource: "declared"`。
3. aster-cloud 的 `executions` 增可查列并把 `ExecutionDecision` 扩出 `require_approval` / `escalate`；证据包 v2 嵌入链收据、统一的 `reviewers[]`（guard 审批 / PolicyProof / 版本审批）与模型身份，并给出用 aster-api 校验的配方。
4. 不在热路径新增同步写：`/evaluate*` 仍异步落链，导出时按相关键拉取；取不到的收据诚实标注，不伪造。

---

## 1. 背景与问题

### 1.1 证据产物现状（本 ADR 之前）

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

`correlationId`、`outcome`（ALLOW / DENY / REQUIRE_APPROVAL / ESCALATE，非 Verdict 结果不写键）、`ruleId`、`controls`（数组，≤ 16 项、每项 ≤ 64 字符）、`agentProvider / agentModel / agentVersion / agentSession / agentSource`（§3，有 agent 时才写，`agentSource` 恒为 `"declared"`）。文本值 ≤ 255 字符截断，null/空值不写键。既有键（`applicantId`、`contextSize`、`sourceFormat` 等）不动；同名键以证据为准。

本期 `/evaluate*` 事件不写 `policyVersionId`：三个端点的调用点都拿不到版本 id（`PolicyEvaluationResult` 不带版本）。guard 收据照旧携带 `policyVersionId`；`/evaluate*` 的版本回填列入 §10。

覆盖范围：`/evaluate`、`/evaluate-json`、`/evaluate-source` 三个端点中**走到策略执行**的路径才发事件并回 `evidence`——`/evaluate` 与 `/evaluate-json` 的执行成功与执行失败分支（失败时无 outcome/ruleId/controls，仍带 `correlationId` 与 agent 键），以及 `/evaluate-source` 的执行成功路径。`/evaluate-source` 执行前的早退异常分支（入口歧义、模块解析失败、CNL 解析失败等）与 `/evaluate-json` 的外层 catch 既不发事件也不回 `evidence`，沿用既有发布范围，有意不改。`/evaluate-source?simulate=true`（HMAC 生效的模拟）不发事件，也不回 `evidence`。

`correlationId` 加入 `AuditChainAppender.isRedactionExempt` 的身份键白名单（与 ADR 0015 修复的 `decisionId` 等同列）：规范 UUID 值本已整串豁免，入白名单是为了将来非 UUID 形态的值也不被信用卡脱敏改写。

键的唯一来源是 `io.aster.policy.evidence.EvaluationEvidence#toMetadata`，经 `PolicyAuditPublisher.publish` 并入事件 metadata。`policy/audit/AuditLogger.logPolicyEvaluation` 在主代码无调用方，不改。

### 2.3 响应

`/evaluate`、`/evaluate-json`、`/evaluate-source` 的 `EvaluationResponse` 新增 `evidence: { correlationId }`（字母序排版、`NON_NULL`，与既有字段规则一致）；未发事件的路径（见 §2.2）不带该字段。`EvaluationResponse` 的字段序测试同步更新。

### 2.4 收据查询接口

`GET /api/v1/audit/receipts?correlationIds=a,b,…` 或 `GET /api/v1/audit/receipts?decisionIds=x,y,…`。两参必须恰给其一，否则 400 `{"error":"exactly_one_of_correlationIds_or_decisionIds"}`；逗号切分、去空白、去重后超过 200 个 → 400 `{"error":"too_many_ids"}`。方法级 `@RequireRole(MEMBER)`，租户一律取自 `RequestIdentityResolver`，只返回该租户自己的行；其他租户的 id 报 `missing`，不泄露存在性。

鉴权接受两种调用方：

- Bearer API key：`ApiKeyAuthFilter.shouldProtect` 已覆盖 `/api/v1/audit`。
- HMAC 内部调用者（aster-cloud 的 `PolicyApiClient` 只发内部调用者头、不带 Bearer）：`InternalCallerFilter.classify` 对该路径仅在携带内部调用者头时判为 `REQUIRE_HMAC`，否则 `NOT_PROTECTED` 交给 `ApiKeyAuthFilter`；`ApiKeyAuthFilter` 仅对这一路径、且仅在 HMAC 已验签时跳过 Bearer 检查。

```json
{ "approvals": [ { "auditId": 124, "comment": "…", "currentHash": "…", "decidedAt": "…", "decidedBy": "…",
    "decisionId": "…", "decisionReceiptHash": "…", "outcome": "APPROVED", "requiredRole": "…" } ],
  "missing": [ "correlationId 或 decisionId" ],
  "receipts": [ { "auditId": 123, "correlationId": "…", "currentHash": "…", "decisionId": "…",
    "eventType": "POLICY_EVALUATION", "hashVersion": 2, "metadata": { … }, "prevHash": "…", "timestamp": "…" } ] }
```

字段字母序，空集合与 null 不输出。`missing` 为请求中未命中的 id，保持请求顺序。`correlationIds` 路径只返回 `receipts` 与 `missing`；`decisionIds` 路径返回 `ACTION_GUARD_DECISION` 收据，并在 `approvals` 中返回同一 `decision_id` 的全部 `ACTION_GUARD_APPROVAL` 事件（`decidedAt` 取行时间戳，其余取该行 metadata）。旧行 metadata 若是被截断的非法 JSON，`metadata` 原样返回为 `{"_raw": "…"}`，不丢信息。接口只读，不触碰链。实现：`io.aster.audit.rest.AuditReceiptResource` + `io.aster.audit.service.AuditReceiptService`，公共 API 契约 golden 收录该路径。

查询走冗余列而非 metadata：迁移 V6.28.0 给 `audit_logs` 增 `correlation_id VARCHAR(64)`、`decision_id VARCHAR(64)` 两列及按 `(tenant_id, 列)` 的部分索引（`WHERE 列 IS NOT NULL`，Flyway checksum golden 同步）。两列由 `AuditChainAppender.toAuditLog` 从事件 metadata 的同名字符串键投影，超过 64 字符截断，**不进哈希载荷**；迁移前的旧行为 NULL，查询报 `missing`。不用 metadata 表达式索引：metadata 超限时追加器写入的是截断后的非法 JSON，按 JSON 解析的索引表达式会让该行插入失败。

---

## 3. 决策二：结构化模型身份，标注来源

```java
public record AgentIdentity(String provider, String model, String version, String session) {}
```

- 每字段 ≤ 255 字符、无控制字符；`provider` 与 `model` 必填（有 agent 时），`version`/`session` 可选。缺省整体为 null，不影响评估。
- `/evaluate*`：三个请求记录 `EvaluationRequest`、`JsonPolicyRequest`、`SourcePolicyRequest` 都新增可选 `agent`，经 §2.2 进事件 metadata。非法 `agent` 在端点入口即返回 400 `invalid_agent`（`EvaluationResponse.diagnostic` 形态），早于配额与并发闸门。
- guard：`GuardActionRequest.Action.agent` 改为 `AgentIdentity`；只给 `model`（旧 `{model, session}` 形状）时 `provider` 取 `"unknown"`，非法值按 guard 既有口径拒绝。规则输入不变：`GuardActionMapper` 仍只传 `agent_model`（键序被 `GuardActionMapperTest` 钉死，已部署规则声明固定字段），结构化身份只进收据。
- 进链形状是**扁平顶层键** `agentProvider / agentModel / agentVersion / agentSession / agentSource`，不是嵌套对象：追加器的脱敏与 guard 的预算收缩只处理顶层 String/List。guard 收据保留原顶层 `agentModel` 键，新增其余四键。
- `agentSource` 本期恒为 `"declared"`，诚实表明是调用方自报；未来可增 `"key-bound"`（与 ADR 0015 的 key 身份绑定）而不改形状。
- guard 收据 3800 字符预算的收缩顺序：`reason → agentSession → agentVersion → principalRoles → agentModel → agentProvider → principalType → resourceType → resourceId → actionName → principalId`；`decisionId / approvalId / contextHash / outcome / effectiveOutcome / reasonCode / ruleId / policyVersionId / sourceHash / idempotencyKey` 永不收缩，自由文本全部清空仍超限时最后才删 `controls`。

---

## 4. 决策三：aster-cloud 执行记录可查化

- `Execution` 新列（drizzle 迁移 0050，`IF NOT EXISTS`）：`outcome text`（ALLOW/DENY/REQUIRE_APPROVAL/ESCALATE/INDETERMINATE/ERROR，由 `deriveExecutionOutcome` 从 decision 派生）、`ruleId text`、`controls jsonb`、`agent jsonb`、`evidenceCorrelationId text`（索引）。
- `ExecutionDecision` pgEnum 增 `require_approval`、`escalate`（共六值）；`deriveExecutionDecision` 与 `parseApprovalFromResult` 改为专属态（去掉临时映射到 indeterminate 的做法）；`DecisionTally`、执行日志统计（两态不计入失败）、报表与文案跟进。旧行不回填：无 `evidenceCorrelationId` 的行在证据包标 `legacy`。
- `PolicyEvaluateResponse` 接口补 `decision / ruleId / controls / evidence`；C 实际调用的是 `evaluateSource`，`buildCNLResult` 透传 `ruleId / controls / evidenceCorrelationId`；两条执行路由经 `buildEvidenceColumns` 写新列。
- `agent` 来源：v1 执行路由从请求体 `agent` 读（同 §3 形状，`parseAgentIdentity` 校验，非法 → 400，`agent: null` 视为缺省），经 `evaluateSource(options.agent)` 透传给 A；dashboard 执行固定 `{provider: "aster-cloud", model: "dashboard"}`。落库时附 `source: "declared"`。

---

## 5. 决策四：证据包 v2 与统一复核者

### 5.1 entry 新字段

`outcome`、`ruleId`、`controls`、`agent`、`evidenceCorrelationId`、
`receipt: { auditId, currentHash, prevHash, hashVersion } | { status: "missing" | "legacy" | "unavailable" }`、
`reviewers: Reviewer[]`（按 `(decidedAt, ref)` 码点序排列，保证 bundleHash 确定）。

`receipt` 判定：无 `evidenceCorrelationId` → `legacy`；所在批取收据失败 → `unavailable`；A 未返回收据 → `missing`。

`agent` 读取时由 `agentOf`（`lib/evidence-export.ts`）校验：须为对象，`provider`/`model` 为字符串，`version`/`session` 可选且须为字符串，只重建已知字段并附 `source: "declared"`；否则为 null。畸形历史行既不会污染 `agentTally`，也不会让 canonical 哈希失败而中断导出。

### 5.2 Reviewer 形状

```ts
type Reviewer = {
  userId: string; role: string; source: 'guard-approval' | 'policy-proof' | 'version-approval';
  outcome: string; decidedAt: string; ref: string; roleVerified: boolean;
};
```

- `guard-approval`：§2.4 `decisionIds` 路径的 `approvals`，`role = requiredRole`，`outcome` 为审批结论，`ref = auditId`。`role` 是 BFF 自报头，`roleVerified: false`；3b 落地经验证角色后改 true。
- `policy-proof`：该执行 `policyVersionRowId` 对应的 `PolicyProof`。proof 是 append-only、按节点追加覆盖，故按 `(policyVersionId, policyId, nodeId)` 只取 `createdAt` 最新的一条；`role = subjectKind`，`outcome = verdict`，`ref = proof.id`，`roleVerified: true`（身份来自 PolicyReviewer 授权记录）。
- `version-approval`：`PolicyApproval` 中该版本的审批人，`role` 固定 `approver`，`outcome = decision`，`ref = approval.id`，`roleVerified: true`。
- `PolicyProof` 与 `PolicyApproval` 表没有租户列，加载函数不做租户过滤；版本 id 只取自已按租户过滤的执行行，以此保证不越租户。

guard 决策与 C 执行的关联：读 `executions.metadata.guardDecisionId`（非空字符串）。本期 C 尚无路由调用 guard、该键无写入方（见 §7 开放问题），故 `guard-approval` 只对 `metadata.guardDecisionId` 非空的条目取数，其余为空数组；ACTION_GUARD_DECISION 收据本身不进 entry，只取其审批人。

### 5.3 manifest

`schemaVersion: '2'`；新增 `receiptSource: { kind: 'aster-api-hash-chain', verifier: 'GET /api/v1/audit/receipts' }`、`agentTally`（按 `provider/model` 计数，无 agent 计入 `unknown`）、`reviewerTally`（三来源恒出现）、`legacyEntries`、`notes.receiptsUnavailable`、`notes.receiptsMissing`。`decisionTally` 增 `require_approval` / `escalate` 桶。

`bundleHash` 仍只覆盖按 `(createdAt, executionId)` 排序的 entries（含新字段），`notes.verification` 写明配方与「v1 包用其自身配方」，v1 与 v2 哈希不可互比；已存的 v1 报告按原样下载，不重算、不升级。`receipt` 的 `unavailable` 状态也在 entries 内、因而进 `bundleHash`：A 恢复后重新导出同一范围会得到不同的 `bundleHash`。这是有意的——包如实反映导出当时取到的证据，两次导出是两份不同的证据快照。

### 5.4 收据拉取与 A 不可达

C 的 `services/evidence/receipts-client.ts` 按策略所属租户（`teamId ?? userId`，与 A 的 `X-Tenant-Id` 口径一致）分组，以 HMAC 内部调用者身份（角色 `member`）拉取：

- 每批 50 个 id：200 个 UUID 拼成的查询串会超出 Quarkus 默认 4096 字节请求行上限。
- 每批超时 2 s、并发 4、不重试（导出上限 50 000 行）。
- 超时、网络错、非 2xx 或响应畸形：该批的 id 整批记为 `unavailable`，其他批不受影响，导出仍成功，manifest 计数。
- `missing` = 本批请求的 id 中未收到收据的那些（不采信服务端 `missing` 列表），`receipts / missing / unavailable` 三者互斥。
- `fetchReceipts`（按 correlationId）从不产出审批；审批只经 `fetchDecisionReceipts`（按 `guardDecisionId`）取得。

证据包诚实标注而不是伪造或中断。

---

## 6. 数据流

执行：客户端（v1 可带 `agent`）→ C 执行路由 → A `/evaluate-source`（返回 decision/ruleId/controls/evidence.correlationId）→ C 写 `Execution` 新列 → A 异步落链（metadata 含 correlationId 与 agent 键，`correlation_id` 列同步投影）。

导出：C 查 `executions` 窗口 → 按租户批量 `GET /api/v1/audit/receipts?correlationIds` → 对带 `guardDecisionId` 的条目再查 `decisionIds` → 拼 `reviewers`（guard 审批 / PolicyProof / 版本审批）→ 组 v2 包 → 存 `ComplianceReport`。

校验：拿到 v2 包的人用 `receiptSource.verifier` 逐条比对 `currentHash`，再用 A 既有 `AuditChainVerifier` 校验链连续性。

---

## 7. 失败与边界

- `agent` 不合法 → A 返回 400 `invalid_agent`（早于配额与并发闸门），C 的 v1 路由返回 400；缺省不影响评估。
- 相关键只做身份，不含业务数据；guard 收据预算收缩顺序见 §3。
- 只有走到策略执行的路径发事件、回 `evidence`；`/evaluate-source` 早退异常分支、`/evaluate-json` 外层 catch 与 simulate 模式都不发、不回（§2.2）。
- `/evaluate*` 事件本期不写 `policyVersionId`（§2.2）。
- 哈希链 append-only 不动；冗余列不进哈希载荷；不新增同步写入热路径；收据接口只读。
- 旧 C（不传 `agent`、不读 `evidence`）与旧 A（不返回 `evidence`）互相兼容：字段均为可选追加。

### 7.1 开放问题

1. 内部调用者 HMAC 签名不覆盖查询串：C→A 的 `/api/v1/audit/receipts` 请求在 5 分钟时间窗内可被替换 id 重放。影响限于只读、同租户。后续两端同时把查询串纳入签名。
2. 收据路径 HMAC 验签失败时，拒绝响应体仍是 `POST /evaluate-source is internal-only…` 的文案，需改为按路径给出。
3. `executions.metadata.guardDecisionId` 在 3b（C 调用 guard）之前没有写入方，`guard-approval` 复核者在生产数据中暂为空。

---

## 8. 兼容性与发布

### 8.1 guard 收据 metadata 键变化

顶层 `agentModel` 键保留，新增 `agentProvider / agentVersion / agentSession / agentSource` 四个顶层键（旧请求只给 model 时 `agentProvider = "unknown"`）。已落链的旧收据不变（链不可改）。`GuardReceiptBuilder` 的预算测试与 `GuardReceiptPersistenceTest` 同步；规则输入（`agent_model`）不变，aster-guard SDK 与 README 不受影响。

### 8.2 顺序

1. A 发布（新增键、接口、迁移 V6.28.0）。
2. C 迁移 0050 → C 发布（枚举扩展、新列、证据包 v2）。
反序也不坏：C 读不到 `evidence` 则 `evidenceCorrelationId` 为 null，导出标 `legacy`；A 尚无收据接口时该批记 `unavailable`。

---

## 9. 测试与验收

A：`EvaluationEvidence` / `AgentIdentity` 值对象（截断、长度、控制字符、provider 缺省）；`PolicyAuditPublisher` 证据并入；`EvaluateEvidenceEventTest`（三端点事件键与响应 `evidence`、simulate 不发）；`AuditChainAppenderRedactionTest` 以非 UUID 值证明 correlationId 豁免；`AuditChainAppenderEvidenceColumnsTest`（投影与 64 截断）；`AuditReceiptResourceTest`（missing、decisionIds 返回审批、跨租户报 missing、两参/超 200 个 400、`_raw`）；`AuditReceiptHmacAuthTest` 与 `InternalCallerFilterTest`（HMAC/Bearer 双通道）；`GuardReceiptBuilderTest` 新收缩顺序；`EvaluationResponse` 字段序。

C：迁移 0050 与枚举扩展测试；`parseApprovalFromResult` / `deriveExecutionDecision` 新态；两条执行路由写新列与 v1 `agent` 校验；收据客户端（分批 50、超时、并发、整批 unavailable、三集合互斥）；复核者三来源；evidence v2 单测（entry/manifest/hash、legacy、unavailable）；`agentOf`；集成测试走假 A 的 receipts 接口。

验收（本地 podman 全栈）：
1. dashboard 执行一条 Verdict 策略 → 导出证据包 v2 → 条目带 `ruleId/controls/agent/receipt`，`receipt.currentHash` 与 A `audit_logs.current_hash` 相等。
2. 对该版本写一条 PolicyProof → 重新导出 → `reviewers[]` 含 `policy-proof`。
3. guard 四眼一次（KEY_FREE/KEY_DPO）→ 对带 `guardDecisionId` 的条目（本期用 SQL 写入 metadata 模拟 C 调 guard）导出 → `reviewers[]` 含 `guard-approval` 且 `userId` 为审批人。
4. 旧行（迁移前的执行）导出标 `legacy`；停掉 A 再导出 → `unavailable` 计数正确、导出成功。

### 9.1 验收结果

1. 带 `agent` 调用 `/evaluate` 返回 `evidence.correlationId`；收据接口返回的 `currentHash` 与 `audit_logs.current_hash` 相等；metadata 带 `ruleId/outcome/agentProvider/agentSource`。
2. 四条 v1 dashboard-API 执行导出为 schemaVersion 2，收据成链（`prevHash` 前后相接），并含 `roleVerified=true` 的 `policy-proof` 复核者。
3. 一次 guard 审批（团队租户）经 `metadata.guardDecisionId` 关联，作为 `guard-approval` 复核者出现（outcome APPROVED，`roleVerified=false`）。
4. 迁移前的旧行导出为 `receipt.status=legacy`；停掉 aster-api 后导出仍完成，`receiptsUnavailable=4`。

---

## 10. 后续（不在本 ADR）

- **3b 审批闭环**：四眼 `required_role` 匹配改用 ADR 0015 已验证团队角色 / PolicyReviewer 授权（`roleVerified: true`）；cloud 审批收件箱 UI；C 执行路由真正调用 guard 并记录 `guardDecisionId`。
- **3c What-if 四态**：`replay_batch_item` 的 `base_outcome / target_outcome`、C 窗口接口下发 `outcome`、runner 按转移对计数（消除 ADR 0039 §5.2 限制）。
- `/evaluate*` 事件的 `policyVersionId` 回填（需评估结果携带版本 id）。
- 内部调用者 HMAC 签名覆盖查询串（§7.1）。
- `source: "key-bound"` 模型身份；证据包签名或链锚定引用；`agent.session` 的跨请求关联。
- `/evaluate` 事件异步落链的丢失率监控（missing 计数指标）。
