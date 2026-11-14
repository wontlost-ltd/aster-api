# #460 审批输入快照实施计划（ADR 0042 §11 R59 + §12）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让审批人在审批时看到决策输入的脱敏快照：按 `@pii` 字段级打码，持所需角色者可解除原值且按人入链留痕；审批记录绑定所看到视图的哈希；证据包升到 v6，装入审批快照键、逐字段来源（含 `factSources`）与执行收据状态。

**Architecture:** 只有 aster-api 产生和持有输入快照与 PII 标记。标记由 aster-api 从本次评估实际执行的 Core IR 推导：`POST /decisions` 取版本 `core_json`；from-evidence 取评估时写下的分类行，分类行同时带输入哈希，用于绑定校验。视图、解除、审批哈希都在 aster-api 内计算；cloud 与 SDK 只取视图、回传哈希。证据包 v6 只搬运链上的键。全部改动纯增量，不需要引擎列车；只有 14(4)(a) 改判需要列车，另列为后续阶段 R。

**Tech Stack:** aster-api：Java 25 / Quarkus / Hibernate Panache / Flyway / JUnit 5（`replay` 子模块放 IR 推导）；aster-guard：TypeScript SDK + vitest；aster-cloud：Next.js（OpenNext / Cloudflare Workers）、drizzle、vitest、Playwright。

**Spec（按此顺序读）：**
- `docs/adr/0042-approval-closure.md` **§12**（本计划直接实现的定稿条款，D1–D11），以及 §11（未被 §12.3 取代的条款仍有效：打码表、路径语法与正反例、`integrity_failed`、证明边界）
- `docs/adr/0040-action-guard.md` §3.3、§5.2（收据预算与身份键）、§11 D3/D4（`inputSources`、`factsStale`）
- `docs/adr/0041-evidence-chain-enrichment.md` §11 D1/D4、§12 D2/D3（v6 的来源与执行字段）
- `docs/adr/0046-governance-profile.md` §11.7 第 2、3 条
- `docs/adr/0044-annex-iii-credit-pilot.md` §3.1、ADR 0045（条款判定由注册表驱动）

---

## 0. 现状核对（2026-11-14）

| 事实 | 证据 |
|---|---|
| aster-api main `749fa44d`；最新迁移 `V6.36.0__guard_input_provenance.sql`；`V6.37.0` 未占用 | `src/main/resources/db/migration/` |
| 审批端点只有列表与 approve / reject；approve 体为 `Map<String,String>` | `guard/api/GuardApprovalResource.java` |
| `GuardDecisionStore.saveDecision(decision, approvalOrNull, receiptOrNull)` 在一个事务内写决策 + 审批 + 执行收据 | `GuardDecisionStore.java:39`、`GuardDecisionJpaStore` |
| from-evidence 先验签事实、再查既有决策、再取事件；`action` 不与评估输入比对 | `GuardEvidenceDecisionService.createFromEvidence` |
| 执行器在评估时持有 `CoreModel.Module`，`ExecutionResult` 带 `RuleMetadata` 与 `inputKey`（仅第一个参数的匹配键） | `replay/…/parser/DynamicCnlExecutor.java:117-141`；`NamedContextMapper.MappingResult.firstParamKey` |
| `ParameterSchemaExtractor` 已有遍历 `PiiType` / `Data` 字段的逻辑可参照 | `policy/api/schema/ParameterSchemaExtractor.java:269-333` |
| 审批事件构造 `GuardReceiptBuilder.approvalEvent(GuardApprovalEntity, String)`；身份键白名单 `AuditChainAppender.IDENTITY_METADATA_KEYS` | `GuardReceiptBuilder.java:134`、`AuditChainAppender.java:76-82` |
| `PIIResponseFilter` 只改写 `String` 实体 | `policy/filter/PIIResponseFilter.java` |
| 收据接口在 `audit/service/AuditReceiptService.java` + `audit/dto/ReceiptLookupResponse.java` | 同 |
| cloud：证据包 v5（`types.ts:113`），`receipts-client.ts` 不解析 `consumes[] / results[]`；`ChainApproval` 无快照键 | `src/services/evidence/` |
| cloud：审批代理路由 `src/app/api/approvals/[tenantId]/[approvalId]/[verb]/route.ts`；收件箱 `approvals-content.tsx`；`InboxItem` 在 `src/lib/approvals-inbox.ts` | 同 |
| cloud 执行路由把同一个 `engineInput` 同时交给 evaluate-source 与 `registerGuardDecisionWith`（`toGuardAction` 的 `context: input`） | `src/app/api/policies/[id]/execute/route.ts:396-502`、`src/lib/guard-register.ts:48-69` |
| 14(4)(a) 判定在注册表数据里：`evidenced: []`、`partial: ["whatIf.comparable"]` | cloud `src/config/controls-registry.json:19` |
| aster-guard SDK：`src/client.ts`（`listApprovals / approve / reject`）、`src/types.ts`、`test/client.test.ts` | aster-guard main |

## Global Constraints

- **ADR 状态门（硬门）**：ADR 0042 §12 已于 2026-11-14 由用户确认为 ACCEPTED（本计划 §9 七项与 ADR 0042 §12.5 均采用建议默认）。Task 0 的状态翻转 PR 由用户审阅并合并；合并前任何实现 PR 不得合并。
- 迁移号：合并时取下一个可用号，起点 `V6.37.0__guard_approval_input_snapshot.sql`；#464 / #465 若先合则顺延。`src/test/resources/db/migration-checksums.golden` 追加 `文件名=sha256`（`shasum -a 256`，LF）。
- 哈希配方：`context_hash`、分类行 `input_hash`、视图 `inputSnapshotHash` 一律 `sha256-sorted-json/v1`（`GuardActionMapper.contextHash` 同一实现），**不用** `jcs-sha256/v1`。
- 输入快照只为 `outcome ∈ {REQUIRE_APPROVAL, ESCALATE}` 且真正建了审批行的决策写；ALLOW / DENY / 门 DENY / `receipt_unavailable` 不写。
- 打码判定是对**声明类型**的匹配，不是对值的匹配；`unknown` 子树整体替换、键名不展示；任何人都不能解除 `unknown`。
- 解除：只有 `PENDING` 且已验证业务角色 ∋ `required_role` 的人可以解除；先同步入链，再返回原值；入链失败 → 503，且响应里没有原值。
- 视图以对象实体返回（不得预序列化成 `String`），否则 `PIIResponseFilter` 会改写原值。
- 上限：视图 ≤ 1 MiB（超出 → `too_large`）；声明叶子 ≤ 512、类型展开深度 ≤ 8（超出 → `marks_source=none`）；清理每轮每表 ≤ 1000 行。
- 保留：`ASTER_GUARD_INPUT_RETENTION=P90D`（终态起）；`ASTER_GUARD_CLASSIFICATION_RETENTION=P7D`（创建起）；解除记录不清理。
- 错误体沿用 `{ "error": "<code>", "message": … }`；新码：`evidence_input_mismatch`(409)、`reveal_not_permitted`(403，附 `verifiedRoles`)、`input_not_available`(409)、`input_snapshot_mismatch`(409)。非法 UUID 与跨租户一律 404 `approval_not_found`。
- 审批事件键名统一为 `inputSnapshotMatched`（不是 `inputSnapshotAttested`）。
- 注释与文档用简体中文；cloud 文案四语（en / zh / de / hi）。
- 不改引擎、不改注册表（阶段 R 除外）、不碰生产；本地验证用 podman PG / Redis（见用户记忆中的本地验证配方）。

## Review Focus

1. **同一审批两人、一人解除**：B 不解除就审批，事件必须 `inputRevealed: false`，且 B 带 `H_masked` 时 `matched: true` → Task A7 测试 `otherApproversRevealDoesNotCountForMe`。
2. **解除后的原值经过全局 PII 响应过滤器**：解除视图里的邮箱、电话必须原样返回 → Task A6 测试 `revealedValuesSurvivePiiResponseFilter`（`@QuarkusTest`，`aster.pii.enforce=true`）。
3. **位置格式的 evaluate-source 输入**（`context` 是数组）：分类行 `input_hash` 为 null，from-evidence 不得因此 409，必须 `unbound` → Task A4 测试 `positionalEvaluationIsUnboundNotMismatch`。
4. **多参数策略**：第二个参数的字段也要按声明打码，不能全部落成 `unknown` 或明文 → Task A1 测试 `classifiesEveryNamedParameter`。
5. **旧 api + 新 cloud / 旧决策**：输入端点 404 或 `status: unavailable` 时，收件箱仍能审批，且不提交哈希 → Task C2 测试 `approvesWithoutHashWhenInputUnavailable`。

---

## 1. PR 清单与合并顺序

| 顺序 | 仓库 | 分支 | 内容 | 依赖 |
|---|---|---|---|---|
| 0 | aster-api | `docs/adr-460-approval-input-snapshot` | 本 ADR 修订 + 本计划（PROPOSED） | — |
| 0′ | aster-api | `docs/adr-460-accepted` | Task 0：§12 状态 → ACCEPTED，回写 0040 / 0041 / 0046 | 用户裁定 §6 |
| 1 | aster-api | `feat/460-input-classification` | Task A1–A4（推导、迁移、分类行、写输入行与绑定） | 0′ |
| 2 | aster-api | `feat/460-approval-input-view` | Task A5–A9（视图、解除、审批哈希、收据接口、清理、契约） | 1 |
| 3 | aster-guard | `feat/460-approval-input` | Task S1 | 2 已部署 |
| 4 | aster-cloud | `feat/460-approval-input-inbox` | Task C1、C2 | 2 已部署 |
| 5 | aster-cloud | `feat/460-evidence-v6` | Task C3 | 2 已部署（与 4 互不依赖） |
| 6 | — | — | Task V1 本地栈验收（4、5 合并前完成） | 1–5 |
| 后续 | locales / core / ts / cloud | `feat/registry-140`（同名分支组） | 阶段 R：14(4)(a) 改判，随下一次列车 | 5 已部署 |

PR 1 单独合并部署也安全：只写新表、新列，没有读方。from-evidence 新增的 409 只在 `context` 不一致时出现。

---

## 2. Task 0：ADR 定稿（docs PR）

**Files:** Modify `docs/adr/0042-approval-closure.md`（头部状态行、§12 状态行）、`docs/adr/0040-action-guard.md`（§3.3 与 §5.2 脚注：「决策行与审计链不存明文 context；明文只存于独立、限期、按审批可达的 `guard_decision_inputs`（ADR 0042 §11/§12）」）、`docs/adr/0041-evidence-chain-enrichment.md`（§11 D3 键名改 `inputSnapshotMatched`；§11 D4 与 §12 D3 各加一句「v6 一次升版，内容见 ADR 0042 §12 D9」）、`docs/adr/0046-governance-profile.md`（§11.7 第 2 条末加「已撤销，见 ADR 0042 §12 D3」）。

- [ ] **Step 1:** 按用户在 §6 的裁定改写 §12.5 中被改动的默认项（未改动的保持原文）。
- [ ] **Step 2:** 校验：`git grep -n "inputSnapshotAttested" docs/adr` 无命中；`git grep -n "catalog.piiMark" docs/adr` 的每一处都指向「撤销」或 ADR 0042 §12 D3。
- [ ] **Step 3:** 提交 `docs(adr): ADR 0042 §12 定稿（#460）`，由用户合并。

---

## 3. aster-api

### Task A1：输入分类推导器 `InputClassifier`

**Files:**
- Create: `replay/src/main/java/io/aster/policy/governance/InputClassifier.java`、`PiiMark.java`、`InputClassification.java`（同包）
- Modify: `replay/src/main/java/io/aster/policy/api/convert/NamedContextMapper.java`（`MappingResult` 增 `List<String> paramKeys`，每个参数实际匹配到的键，未匹配为 null；`firstParamKey` 保留，等于 `paramKeys.get(0)`）
- Modify: `replay/src/main/java/io/aster/policy/parser/DynamicCnlExecutor.java`（`ExecutionResult` 增 `InputClassification classification`；`of(...)` 用执行时的 module + mapping 计算；推导异常记 DEBUG 并置 null，不影响执行）
- Modify: `src/main/java/io/aster/policy/replay/ReplayExecutorAdapter.java`、`replay/…/ReplayExecutorResult`（透传）
- Test: `replay/src/test/java/io/aster/policy/governance/InputClassifierTest.java`

**Interfaces:**
- Produces:
  - `record PiiMark(String path, String level, String category, String source)`：`level ∈ {L1,L2,L3}`，`source` 恒 `"ir"`
  - `record InputClassification(List<String> declared, List<PiiMark> marks)`：两者按路径字典序；`declared ⊇ marks.path`；`toJson()` / `fromJson(String)` 用于 JSONB 存取
  - `static InputClassification InputClassifier.guardShape(CoreModel.Module m, String function)`：第一个参数记录，字段名经 `GuardInputRefPaths` 常量表映射
  - `static InputClassification InputClassifier.evidenceShape(CoreModel.Module m, String function, List<String> paramKeys)`：参数 i 的前缀为 `context.<paramKeys[i]>`
  - 两者在超上限（声明叶子 > 512、展开深度 > 8）时返回 `null`

- [ ] **Step 1: 写失败测试**（源码经 `InProcessCnlParser` 编译出 Module；用例名与断言）：
  - `declaredFieldsAndMarks`：`Define LineItem has sku as Text, note as @pii(L1, free-text) Text.`、`Define Applicant has amount as Decimal, email as @pii(L2, email) Text, items as List of LineItem.`，规则 `Rule decide given applicant as Applicant …`；`evidenceShape(m, "decide", List.of("applicant"))` → `declared == [context.applicant.amount, context.applicant.email, context.applicant.items[].note, context.applicant.items[].sku]`，`marks == [PiiMark("context.applicant.email","L2","email","ir"), PiiMark("context.applicant.items[].note","L1","free-text","ir")]`
  - `classifiesEveryNamedParameter`：两参数 `given applicant as Applicant, guarantor as Applicant`，`paramKeys = [applicant, guarantor]` → `context.guarantor.email` 也在 marks 中
  - `unmatchedParameterYieldsNoDeclaredPaths`：`paramKeys = [applicant, null]` → 无 `context.guarantor.*` / `context.null.*` 路径
  - `guardShapeMapsContextRecord`：`GuardRequest has principal_id as Text, context as GuardContext`、`GuardContext has email as @pii(L2, email) Text` → `guardShape` 声明含 `principal.id`、`context.email`，marks 只含 `context.email`
  - `mapTypedContextHasNoDeclaredChildren`：`context as Map of Text to Text` → `declared` 不含任何 `context.` 前缀路径
  - `recursiveTypeTerminates` 与 `overLimitReturnsNull`（构造 600 个字段的记录 → `null`）
  - `optionAndMaybeUnwrapToBaseType`：`email as Maybe @pii(L2, email) Text` 仍得 L2 标记
- [ ] **Step 2:** 运行 `./gradlew :replay:test --tests '*InputClassifierTest'`，预期 FAIL（类不存在）。
- [ ] **Step 3:** 实现。按类型名查 `Data` 声明并递归，`PiiType` / `Option` / `Maybe` 逐层解包；`ListT` 的元素若是记录则加 `[]` 继续展开，否则作为叶子；`MapT` 与未知类型名都是叶子，但**不**进 `declared`（相当于未声明）。用「已展开类型名」集合防环。
- [ ] **Step 4:** 加 `NamedContextMapperTest.paramKeysListsEveryMatchedKey`，并让执行器带出 classification：`DynamicCnlExecutorCoreIrJsonTest.executionResultCarriesClassification`。运行 `./gradlew :replay:test test --tests '*NamedContextMapperTest' --tests '*DynamicCnlExecutor*'`，预期 PASS。
- [ ] **Step 5:** 提交 `feat(guard): 输入分类推导器，从执行 IR 得出声明树与 PII 标记（ADR 0042 §12 D2）`。

### Task A2：迁移与实体

**Files:**
- Create: `src/main/resources/db/migration/V6.37.0__guard_approval_input_snapshot.sql`
- Create: `src/main/java/io/aster/guard/snapshot/GuardDecisionInputEntity.java`、`GuardEvaluationClassificationEntity.java`、`GuardInputRevealEntity.java`
- Modify: `src/main/java/io/aster/guard/GuardApprovalEntity.java`（四列）、`src/test/resources/db/migration-checksums.golden`
- Test: `src/test/java/io/aster/guard/GuardEntitiesPersistenceTest.java`（增用例）、`MigrationGovernanceTest`

迁移内容（列名即契约）：
- `guard_decision_inputs(decision_id UUID PK REFERENCES guard_decisions(id), tenant_id VARCHAR(64) NOT NULL, action_json JSONB NOT NULL, context_hash VARCHAR(64) NOT NULL, classification JSONB NULL, marks_source VARCHAR(16) NOT NULL, input_binding VARCHAR(16) NOT NULL, created_at TIMESTAMPTZ NOT NULL)`；索引 `(tenant_id)`
- `guard_evaluation_classifications(tenant_id VARCHAR(64), correlation_id VARCHAR(64), input_hash VARCHAR(64) NULL, classification JSONB NULL, created_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (tenant_id, correlation_id))`；索引 `(created_at)`
- `guard_input_reveals(approval_id UUID REFERENCES guard_approvals(id), revealed_by VARCHAR(255), tenant_id VARCHAR(64) NOT NULL, revealed_at TIMESTAMPTZ NOT NULL, audit_id BIGINT NOT NULL, PRIMARY KEY (approval_id, revealed_by))`
- `guard_approvals` 加 `input_snapshot_hash VARCHAR(64)`、`input_snapshot_matched BOOLEAN`、`input_revealed BOOLEAN`、`input_status VARCHAR(16)`

- [ ] **Step 1:** 写 `GuardEntitiesPersistenceTest.decisionInputRoundTrip / evaluationClassificationRoundTrip / revealPrimaryKeyRejectsDuplicate` 三个失败用例。
- [ ] **Step 2:** 运行 `./gradlew test --tests '*GuardEntitiesPersistenceTest' --tests '*MigrationGovernanceTest'`，预期 FAIL。
- [ ] **Step 3:** 写迁移与实体，追加 checksum。
- [ ] **Step 4:** 同一命令，预期 PASS。
- [ ] **Step 5:** 提交 `feat(guard): V6.37.0 审批输入快照存储（ADR 0042 §12 D1/D5/D6）`。

### Task A3：评估期分类行

**Files:**
- Create: `src/main/java/io/aster/guard/snapshot/EvaluationClassificationWriter.java`
- Modify: `src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java`（evaluate-source 约 `:686-704`、evaluate-json 约 `:412` 的事件发布之后）、`src/main/java/io/aster/policy/api/PolicyEvaluationService.java`（`/evaluate` 事件发布之后；该路径输入是位置数组，`paramKeys` 全为 null，因而写入空声明树、`input_hash` 为 null，from-evidence 得 `unbound / none`）
- Modify: `src/main/java/io/aster/guard/GuardMetrics.java`（counter `aster_guard_classification_write_failed_total`）
- Test: `src/test/java/io/aster/guard/snapshot/EvaluationClassificationWriterTest.java`、`src/test/java/io/aster/policy/rest/EvaluateSourceClassificationTest.java`

**Interfaces:**
- Produces: `void EvaluationClassificationWriter.record(String tenantId, String correlationId, Object requestContext, String outcome, InputClassification classification, boolean simulate)`：outcome 不可审批或 simulate → 不写；`requestContext instanceof Map` → `input_hash = GuardActionMapper.contextHash(map)`，否则 null；`requiringNew` 事务；`INSERT … ON CONFLICT DO NOTHING`（先写者为准，冲突计 counter）；任何异常记 WARN 并加 counter，不上抛。
- Produces: `Optional<GuardEvaluationClassificationEntity> EvaluationClassificationWriter.find(String tenantId, String correlationId)`

- [ ] **Step 1:** 失败测试：`writesOnlyForApprovableOutcomes`（ALLOW / DENY 不写）、`simulateDoesNotWrite`、`namedContextGetsInputHash`（与 `GuardActionMapper.contextHash` 同值）、`positionalContextHasNullHash`、`writeFailureDoesNotThrow`（store 抛异常 → counter +1）、`secondWriteSameCorrelationIdKeepsFirst`（第二次写不同 hash / 标记 → 行不变，counter +1）、`EvaluateSourceClassificationTest.requireApprovalEvaluationLeavesClassificationRow`（`@QuarkusTest`，HmacProfile，带 `@pii` 的 REQUIRE_APPROVAL 源码 → 行的 marks 含该字段）。
- [ ] **Step 2:** 运行 `./gradlew test --tests '*EvaluationClassification*' --tests '*EvaluateSourceClassificationTest'`，预期 FAIL。
- [ ] **Step 3:** 实现并接入三条路径（在 `evidence` 非 null 的分支里调用）。
- [ ] **Step 4:** 同一命令，预期 PASS；再跑 `./gradlew test --tests '*PolicyEvaluationResource*' --tests '*EvaluationEvidence*'`，确认没有回归。
- [ ] **Step 5:** 提交 `feat(guard): 可审批评估写分类行与输入哈希（ADR 0042 §12 D1）`。

### Task A4：决策时写输入行与 from-evidence 绑定校验

**Files:**
- Create: `src/main/java/io/aster/guard/snapshot/DecisionInputFactory.java`
- Modify: `src/main/java/io/aster/guard/GuardDecisionStore.java` / `GuardDecisionJpaStore.java`（`saveDecision` 增第四参 `GuardDecisionInputEntity inputOrNull`，同一事务持久化；旧三参重载委托传 null）
- Modify: `GuardDecisionService.java`（`POST /decisions`：审批行非空时用版本 module 的 `guardShape` 建输入行，`marks_source=version`、`input_binding=direct`；classification 与 `RuleMetadata` 同一版本键 memo）
- Modify: `GuardEvidenceDecisionService.java`（取事件之后、`gate` 之前做绑定校验；建输入行时 `marks_source = evaluation | none`、`input_binding = evaluated | unbound`）
- Modify: `guard/api/GuardDecisionResource.java`（409 `evidence_input_mismatch` 映射）
- Test: `GuardEvidenceDecisionServiceTest`、`GuardDecisionServiceTest`、`GuardDecisionJpaStoreTest`、`GuardFromEvidenceResourceTest`

**Interfaces:**
- Produces: `GuardDecisionInputEntity DecisionInputFactory.direct(UUID decisionId, String tenantId, GuardActionRequest.Action action, String contextHash, InputClassification c)` 与 `…fromEvidence(UUID decisionId, String tenantId, Action action, String contextHash, Optional<GuardEvaluationClassificationEntity> row)`。`row` 为空 → `unbound/none`；`row.input_hash` 为 null → `unbound`，有分类则 `evaluation`，否则 `none`。`action_json` 序列化时**去掉** `facts`（事实已在收据中，签名不是给审批人看的输入）。
- Produces: `GuardApprovalConflictException("evidence_input_mismatch", …)`，由 `GuardEvidenceDecisionService` 在 `row.input_hash != null && !row.input_hash.equals(contextHash(action.context))` 时抛出

- [ ] **Step 1:** 失败测试：
  - `GuardEvidenceDecisionServiceTest.mismatchedContextIs409AndCreatesNothing`（之后用同一 correlationId 带正确 context 成功 → 证明没有消耗证据）
  - `positionalEvaluationIsUnboundNotMismatch`（分类行 `input_hash=null`）
  - `missingClassificationRowIsUnboundNone`
  - `GuardFromEvidenceResourceTest.bindingHashEqualAcrossHttpPaths`（`@QuarkusTest`：同一 JSON 体——含 `1.10`、`9007199254740993`、`null` 值、嵌套对象、数组、非 ASCII 键——经真实 HTTP 先打 `/evaluate-source` 再打 from-evidence，得 `input_binding = evaluated`；两条反序列化路径的数字类型若不一致，此用例必失败）
  - `gateDenyWritesNoInputRow`（档案要求 verifiedInputs 而未验证 → DENY，无输入行）
  - `GuardDecisionServiceTest.requireApprovalWritesDirectInputWithVersionMarks`、`allowWritesNoInputRow`、`receiptUnavailableWritesNoInputRow`
  - `GuardDecisionJpaStoreTest.inputRowRollsBackWithDecision`（审批行插入失败 → 输入行也不存在）
- [ ] **Step 2:** 运行 `./gradlew test --tests '*GuardEvidenceDecisionServiceTest' --tests '*GuardDecisionServiceTest' --tests '*GuardDecisionJpaStoreTest' --tests '*GuardFromEvidenceResourceTest'`，预期 FAIL。
- [ ] **Step 3:** 实现。绑定校验放在 `evidence_not_approvable` 判定之后：不可审批的证据仍按原来的 409 返回。
- [ ] **Step 4:** 同一命令，预期 PASS；再跑 `./gradlew test --tests 'io.aster.guard.*'`。
- [ ] **Step 5:** 提交 `feat(guard): 待审批决策落输入快照，from-evidence 核对评估输入（ADR 0042 §12 D1/D2）`。PR 1 在此结束：开 PR、本地 Codex 审查、修复后交用户合并。

### Task A5：视图渲染器（纯函数）

**Files:**
- Create: `src/main/java/io/aster/guard/snapshot/InputViewRenderer.java`、`src/main/java/io/aster/guard/api/GuardApprovalInputView.java`
- Test: `src/test/java/io/aster/guard/snapshot/InputViewRendererTest.java`

**Interfaces:**
- Produces: `record RenderedView(Map<String,Object> input, int revealedFields, String hash, int sizeBytes)`；`static RenderedView InputViewRenderer.render(UUID decisionId, String contextHash, Map<String,Object> actionJson, InputClassification c, String marksSource, String inputBinding, Map<String,Object> inputSources, boolean revealed, PIIRedactor redactor)`
  - `hash` = `sha256-sorted-json/v1` 作用于 `{decisionId, contextHash, input, piiMarks, maskRules:"v1", revealed, marksSource, inputBinding, inputSources}`
  - `piiMarks` 进视图时每项为 `{path, level, category, source}`
- Produces: `GuardApprovalInputView`（`@JsonPropertyOrder(alphabetic=true)`、`NON_NULL`）字段：`approvalId, contextHash, decisionId, factsStale, input, inputBinding, inputSnapshotHash, inputSources, marksSource, maskRules, piiMarks, revealed, revealedByMe, status`

- [ ] **Step 1:** 失败测试——ADR 0042 §11 D2 正反例表的 8 行逐行一个用例（`plainDeclaredAmount`、`declaredEmailMasked`、`arrayLengthVisible`、`arrayElementPiiMasked`、`nonObjectElementUnknown`、`undeclaredSubtreeHidesKeys`、`secondLayerRegexOnDeclaredText`、`principalIdExempt`），再加：
  - `revealRestoresOnlyDeclaredPii`（`unknown` 仍是 `{"$pii":{"level":"unknown"}}`，`revealedFields == 2`）
  - `marksSourceNoneHidesAllContext`
  - `hashStableAcrossKeyOrder`、`hashDiffersMaskedVsRevealed`、`hashIgnoresFactsStale`（`inputSources` 中不出现 `factsStale`）
  - `depthBeyondEightIsUnknown`
- [ ] **Step 2:** 运行 `./gradlew test --tests '*InputViewRendererTest'`，预期 FAIL。
- [ ] **Step 3:** 实现（深度优先遍历按 §11 D2 路径语法；匹配是对声明树做精确字符串比较）。
- [ ] **Step 4:** 同一命令，预期 PASS。
- [ ] **Step 5:** 提交 `feat(guard): 审批输入视图渲染与快照哈希（ADR 0042 §11 D2、§12 D4/D6）`。

### Task A6：输入端点与解除留痕

**Files:**
- Create: `src/main/java/io/aster/guard/snapshot/InputSnapshotService.java`
- Modify: `guard/api/GuardApprovalResource.java`（`GET /{id}/input`、`POST /{id}/input/reveal`；`statusOf` 增 `reveal_not_permitted→403`、`input_not_available→409`）
- Modify: `src/main/java/io/aster/policy/event/EventType.java`（`ACTION_GUARD_INPUT_REVEAL`）、`GuardReceiptBuilder.java`（`inputRevealEvent(GuardApprovalEntity a, String performedBy, int revealedFields, String viewHash)`，`decision_id` 投影）、`AuditChainAppender.IDENTITY_METADATA_KEYS`（加 `inputSnapshotHash`）
- Test: `src/test/java/io/aster/guard/snapshot/InputSnapshotServiceTest.java`、`src/test/java/io/aster/guard/api/GuardApprovalInputResourceTest.java`（`@QuarkusTest`）、`AuditIdentifierRedactionTest`

**Interfaces:**
- Consumes: `InputViewRenderer.render`（A5）、`GuardDecisionInputEntity`（A2）
- Produces: `GuardApprovalInputView InputSnapshotService.view(String tenantId, UUID approvalId, String user)`、`GuardApprovalInputView reveal(String tenantId, UUID approvalId, String user, Set<String> verifiedRoles)`；`String status(GuardApprovalEntity a)` ∈ `available | unavailable | purged | integrity_failed | too_large`（`purged`：审批终态且输入行已不存在；`unavailable`：其余无行情形；`integrity_failed`：重算 `context_hash` 不等；`too_large`：渲染后 > 1 MiB）；`RenderedView renderFor(GuardApprovalEntity a, boolean revealed)` 供 A7 使用

- [ ] **Step 1:** 失败测试：
  - `InputSnapshotServiceTest`：`statusUnavailableForLegacyDecision`、`statusPurgedAfterTerminalDelete`、`tamperedActionJsonIsIntegrityFailed`、`oversizeViewIsTooLarge`
  - reveal：`roleHolderRevealAppendsOneEventFirstTimeOnly`（两次调用 → 一条事件、一行记录）、`concurrentFirstRevealsAppendExactlyOneEvent`（两线程同时首次解除 → 一条事件、`audit_id` 等于该事件）、`noRoleIs403WithVerifiedRoles`、`escalateIsNotRevealable`、`terminalApprovalIs409`、`appendFailureReturns503WithoutRawValues`（appender 抛 `AuditAppendException` → 抛 503，且没有插记录行）
  - `GuardApprovalInputResourceTest`：`crossTenantIs404SameBodyAsApprove`、`invalidUuidIs404`、`getHasNoSideEffects`（GET 后事件数不变）、`revealedValuesSurvivePiiResponseFilter`（`aster.pii.enforce=true`，邮箱原样返回）
  - `AuditIdentifierRedactionTest.inputSnapshotHashNotRedacted`
- [ ] **Step 2:** 运行 `./gradlew test --tests '*InputSnapshotServiceTest' --tests '*GuardApprovalInputResourceTest' --tests '*AuditIdentifierRedactionTest'`，预期 FAIL。
- [ ] **Step 3:** 实现。一个事务内：`SELECT … FOR UPDATE` 审批行 → 判权 → 查本人解除行（有则直接渲染返回）→ 渲染解除视图 → `appendSync(inputRevealEvent)` → 插解除行 → 提交 → 返回。行锁使同一人并发首次解除串行，恰好一条事件。
- [ ] **Step 4:** 同一命令，预期 PASS。
- [ ] **Step 5:** 提交 `feat(guard): 审批输入端点与按人入链的原值解除（ADR 0042 §12 D5/D8）`。

### Task A7：审批时的快照哈希与事件键

**Files:**
- Modify: `GuardApprovalService.java`（`decideApproval` 增参 `String inputSnapshotHash`）、`guard/api/GuardApprovalResource.java`（读 body `inputSnapshotHash`；`input_snapshot_mismatch→409`）、`GuardReceiptBuilder.approvalEvent`（四键；`status ≠ available` 不写哈希键；过期事件不写）、`guard/api/GuardApprovalView.java`（四个新字段）
- Test: `GuardApprovalServiceTest`、`GuardReceiptBuilderTest`、`GuardApprovalResourceTest`（或现有 approve 资源测试）

**Interfaces:**
- Consumes: `InputSnapshotService.renderFor`、`status`（A6）
- Produces: 审批事件 metadata 键 `inputSnapshotHash / inputSnapshotMatched / inputRevealed / inputStatus`；`GuardApprovalView` 字段 `inputSnapshotHash, inputSnapshotMatched, inputRevealed, inputStatus`

- [ ] **Step 1:** 失败测试：`maskedHashMatches`、`revealedHashMatchesOnlyAfterOwnReveal`、`otherApproversRevealDoesNotCountForMe`、`wrongHashIs409AndApprovalStaysPending`、`missingHashRecordsMaskedUnmatched`、`unavailableInputApprovesWithoutHashKeys`、`expiryEventHasNoSnapshotKeys`、`approvalEventFitsBudgetWithSnapshotKeys`（comment 撑满 + 四键 → 3800 预算内，只缩短 comment）、`listItemCarriesSnapshotFields`。
- [ ] **Step 2:** 运行 `./gradlew test --tests '*GuardApprovalServiceTest' --tests '*GuardReceiptBuilderTest'`，预期 FAIL。
- [ ] **Step 3:** 实现。快照哈希在审批事务的加锁读之后计算，与写行在同一次判定内完成。
- [ ] **Step 4:** 同一命令，预期 PASS。
- [ ] **Step 5:** 提交 `feat(guard): 审批记录所见输入视图的快照哈希（ADR 0042 §12 D6）`。

### Task A8：收据接口与契约

**Files:**
- Modify: `src/main/java/io/aster/audit/service/AuditReceiptService.java`、`audit/dto/ReceiptLookupResponse.java`（`approvals[]` 四键；新增 `reveals[]`，按 `decision_id` 聚合 `ACTION_GUARD_INPUT_REVEAL` 事件）
- Modify: `src/test/resources/api/public-api-contract.golden`
- Test: 现有收据接口测试类（`AuditReceiptServiceTest` 或 `AuditReceiptResourceTest`，以仓内实际类名为准），以及 `PublicApiContractTest`

- [ ] **Step 1:** 失败测试：`approvalsCarrySnapshotKeys`、`revealsListedByDecision`、`emptyRevealsOmitted`；契约 golden 加 `GET /api/v1/guard/approvals/{id}/input`、`POST /api/v1/guard/approvals/{id}/input/reveal` 与新字段。
- [ ] **Step 2:** 运行 `./gradlew test --tests '*AuditReceipt*' --tests '*PublicApiContractTest'`，预期 FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同一命令，预期 PASS。
- [ ] **Step 5:** 提交 `feat(audit): 收据接口带审批快照键与解除记录（ADR 0042 §12 D8）`。

### Task A9：保留期清理

**Files:**
- Create: `src/main/java/io/aster/guard/snapshot/InputRetentionScheduler.java`（`@Scheduled(every="${aster.guard.input-retention-sweep:PT1H}", concurrentExecution=SKIP)`）
- Modify: `src/main/resources/application.properties`（`aster.guard.input-retention=${ASTER_GUARD_INPUT_RETENTION:P90D}`、`aster.guard.classification-retention=${ASTER_GUARD_CLASSIFICATION_RETENTION:P7D}`）
- Test: `src/test/java/io/aster/guard/snapshot/InputRetentionSchedulerTest.java`

- [ ] **Step 1:** 失败测试：`deletesInputsOfTerminalApprovalsPastRetention`、`keepsPendingInputsForever`、`deletesClassificationsPastRetention`、`batchCappedAt1000`、`neverDeletesReveals`、`p0dDeletesAtTerminal`。
- [ ] **Step 2:** 运行 `./gradlew test --tests '*InputRetentionSchedulerTest'`，预期 FAIL。
- [ ] **Step 3:** 实现：每表一条带 `LIMIT 1000` 的 `DELETE … WHERE ctid IN (SELECT …)`；终态时间取 `decided_at`，EXPIRED 无 `decided_at` 时取 `expires_at`。
- [ ] **Step 4:** 同一命令，预期 PASS；然后跑全量 `./gradlew test`（已知基线失败按用户记忆中的基线清单排除），再跑 `./gradlew :replay:test`。
- [ ] **Step 5:** 提交 `feat(guard): 输入快照与分类行的保留期清理（ADR 0042 §12 D7）`。PR 2 在此结束。

---

## 4. aster-guard SDK

### Task S1：输入视图、解除与审批哈希

**Files:** Modify `src/types.ts`、`src/client.ts`、`README.md`（审批输入一节）；Test `test/client.test.ts`

**Interfaces:**
- Produces: `type GuardApprovalInput = { approvalId; decisionId; contextHash; status: 'available'|'unavailable'|'purged'|'integrity_failed'|'too_large'; marksSource: 'version'|'evaluation'|'none'; inputBinding: 'direct'|'evaluated'|'unbound'; maskRules: string; revealed: boolean; revealedByMe: boolean; input?: Record<string, unknown>; piiMarks?: Array<{path; level; category; source}>; inputSnapshotHash?: string; inputSources?: {...}; factsStale?: boolean }`
- Produces: `getApprovalInput(id): Promise<{ok:true; input: GuardApprovalInput} | {ok:false; code; status}>`、`revealApprovalInput(id)`（同返回形状）；`GuardApproveOptions.inputSnapshotHash?`、`GuardRejectOptions.inputSnapshotHash?`；`GuardApproval` 增 `inputStatus? / inputSnapshotHash? / inputSnapshotMatched? / inputRevealed?`

- [ ] **Step 1:** 失败测试：`getApprovalInput hits GET /input`、`revealApprovalInput POSTs empty body`、`approve forwards inputSnapshotHash`、`403 reveal_not_permitted → ok:false code`、`404 → ok:false status 404`（与审批方法同一错误口径）。
- [ ] **Step 2:** 运行 `npm test -- client`，预期 FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 运行 `npm test && npm run typecheck`，预期 PASS。
- [ ] **Step 5:** 提交 `feat(sdk): 审批输入视图、解除与快照哈希（aster-api#460）`；独立发版（minor）。

---

## 5. aster-cloud

### Task C1：客户端方法与代理路由

**Files:**
- Modify: `src/services/policy/guard-types.ts`（与 S1 同形状的 `GuardApprovalInput`；`GuardApprovalItem` 四个可选字段）、`src/services/policy/policy-api.ts`（`getGuardApprovalInput(id)`、`revealGuardApprovalInput(id)`；`approveGuard(id, comment?, inputSnapshotHash?)`、`rejectGuard(id, comment, inputSnapshotHash?)`；v3 签名，`GUARD_TIMEOUT_MS`，路径 id 本地校验）
- Create: `src/app/api/approvals/[tenantId]/[approvalId]/input/route.ts`（GET）、`…/input/reveal/route.ts`（POST）——静态段与既有 `[verb]` 并存；鉴权、租户成员校验与上游错误映射复用 `[verb]/route.ts` 的帮助函数（先抽到 `src/lib/approvals-proxy.ts`，不复制）
- Modify: `src/app/api/approvals/[tenantId]/[approvalId]/[verb]/route.ts`（body 透传 `inputSnapshotHash`，格式 `^[0-9a-f]{64}$`，否则 400 `invalid_approval`）
- Test: `src/services/policy/__tests__/policy-api.guard-input.test.ts`、`src/app/api/approvals/__tests__/input-route.test.ts`

- [ ] **Step 1:** 失败测试：GET 转发以当前用户身份签名（canonical 含 userId 与该租户业务角色）；非成员 403；上游 403 `reveal_not_permitted`、409 `input_snapshot_mismatch` / `input_not_available`、503 原样映射；上游 404 → 200 `{ status: 'unavailable' }`（旧 api 兼容）；上游 408 / 5xx / 超时 → 502 `upstream_unavailable`；approve 带非法哈希 → 400。
- [ ] **Step 2:** 运行 `pnpm vitest run src/services/policy/__tests__/policy-api.guard-input.test.ts src/app/api/approvals`，预期 FAIL。
- [ ] **Step 3:** 实现。
- [ ] **Step 4:** 同一命令，预期 PASS。
- [ ] **Step 5:** 提交 `feat(approvals): 审批输入与解除代理路由（aster-api#460）`。

### Task C2：收件箱输入面板

**Files:**
- Create: `src/app/[locale]/(dashboard)/approvals/approval-input-panel.tsx`（树渲染、PII 与来源徽标、横幅、「显示原值」确认框）
- Modify: `approvals-content.tsx`（行展开；批准 / 拒绝门控与提交哈希；新错误码文案）、`src/i18n/demo-supplement.ts`（或收件箱现有文案所在处：en / zh / de / hi）
- Modify: `src/config/fact-sources.ts`（注释：`catalog.piiMark` 已撤销，见 ADR 0042 §12 D3）
- Test: `approval-input-panel.test.tsx`、`approvals-content.test.tsx`（增用例）

**Interfaces:**
- Consumes: C1 路由；`GuardApprovalInput`
- Produces: `<ApprovalInputPanel tenantId approvalId canReveal onViewLoaded(view: GuardApprovalInput | null) />`；`canReveal = item.canAct && item.requiredRole != null`

- [ ] **Step 1:** 失败测试：
  - 面板：`rendersMaskedBadgeWithLevelAndCategory`、`unknownShowsNotVisibleBadge`、`verifiedLeafShowsSourceBadgeWithFactSource`、`unboundBanner`、`marksSourceNoneBanner`、`factsStaleBanner`、`statusTexts`（4 种）、`revealButtonHiddenWithoutRole`、`revealButtonHiddenWhenNoDeclaredPii`、`revealRequiresConfirm`
  - 收件箱：`approveDisabledUntilViewLoaded`、`approveSubmitsDisplayedHash`、`approveAfterRevealSubmitsRevealedHash`、`approvesWithoutHashWhenInputUnavailable`、`snapshotMismatchShowsRefreshMessage`
- [ ] **Step 2:** 运行 `pnpm vitest run "src/app/[locale]/(dashboard)/approvals"`，预期 FAIL。
- [ ] **Step 3:** 实现（输入只在展开时拉取，不随列表首屏加载）。
- [ ] **Step 4:** 同一命令，预期 PASS；然后跑 `pnpm typecheck && pnpm lint && pnpm vitest run`。
- [ ] **Step 5:** 提交 `feat(approvals): 收件箱展示脱敏决策输入并提交快照哈希（aster-api#460）`。PR 4 在此结束。

### Task C3：证据包 v6

**Files:**
- Modify: `src/services/evidence/types.ts`（v6 `EvidenceEntry` / `EvidenceManifest`；v5 冻结为 `EvidenceManifestV5` 等只读类型）、`reviewers.ts`（`Reviewer` 四字段；`guardApprovalReviewers` 取值，其他来源 null）、`receipts-client.ts`（`ChainApproval` 四键；解析 `consumes[] / results[] / authoritativeConsumeAuditId`）、`bundle.ts`（entry `inputSource / guardInputSources / execution`；manifest 四个 tally；`schemaVersion: '6'`；`notes.verification`）、`stored.ts`（认 `'6'`）、`regulatory-mapping.ts` 与 `control-registry.ts`（`SOURCES` / `EvidenceSource` 加 `reviewers.guard.inputSnapshot`）
- Test: `src/services/evidence/__tests__/bundle.v6.test.ts`、`reviewers.test.ts`、`stored.test.ts`、`regulatory-mapping.test.ts`

**Interfaces:**
- Produces: `guardInputSources.factSources: string[]` = 决策收据 metadata `factSources` 原样字典序；`inputSource` = 评估事件 `inputSource` 或 `'legacy'`；`execution` 按 ADR 0041 §12 D3；`inputSnapshotTally: { matched, unmatched, unavailable }`

- [ ] **Step 1:** 失败测试：`v6EntryHasGuardInputSourcesWithFactSourcesVerbatim`（与 fixture 收据逐字相等）、`legacyEventIsLegacyInputSource`、`unconsumedAllowCounted`、`reviewerSnapshotFieldsFromApprovals`、`nonGuardReviewersHaveNullSnapshotFields`、`bundleHashCoversNewEntryFields`（改任一新字段 → 哈希变）、`manifestTalliesNotInBundleHash`、`storedReadsV5AndV6`、`inputSnapshotSourceRequiresMatchedAndAvailable`（matched=false 或 status≠available 不计）、`registryWithoutNewSourceKeepsArticle14Unchanged`（默认注册表下 14(4)(a) 仍按 whatIf 判定）。
- [ ] **Step 2:** 运行 `pnpm vitest run src/services/evidence`，预期 FAIL。
- [ ] **Step 3:** 实现；收据接口未返回新键时（旧 api）字段为 null，不报错。
- [ ] **Step 4:** 同一命令，预期 PASS；然后跑 `pnpm typecheck && pnpm vitest run`。
- [ ] **Step 5:** 提交 `feat(evidence): 证据包 v6——审批快照键、逐字段来源与执行收据（ADR 0042 §12 D9）`。

---

## 6. Task V1：本地栈验收（PR 4、5 合并前）

podman 全栈（aster-api PR 2 分支 + cloud PR 4/5 分支 + aster-guard PR 3），按 ADR 0042 §12.4 第 1–9 条逐条执行，结果记入 PR 4 描述：
- [ ] 信贷试点（`seed-credit-pilot.ts`）：cp-owner 执行大额申请 → 收件箱展开输入，看到 `context.applicant.*` 的声明字段，`marksSource: evaluation`、`inputBinding: evaluated`。
- [ ] cp-officer 解除 → 链上 `ACTION_GUARD_INPUT_REVEAL` 一条；批准 → 事件 `inputSnapshotMatched: true, inputRevealed: true`。
- [ ] 另一成员（无角色）展开 → 无「显示原值」；API 直连 reveal → 403。
- [ ] 改写 Execution 输入后点「重新登记」→ 409 `evidence_input_mismatch`（guardError 记码）。
- [ ] 导出证据包 → `schemaVersion '6'`，reviewer 四字段、`guardInputSources.factSources` 与 `GET /api/v1/audit/receipts?decisionIds=` 逐字一致。
- [ ] Playwright：扩展 `src/__tests__/e2e-browser/local-stack/credit-pilot.spec.ts` 增加「展开输入 → 解除 → 批准」三步与断言；预期表中 14(4)(a) 不变（注册表未升版）。

## 7. 阶段 R（后续，随下一次列车）：14(4)(a) 改判

不在 #460 验收内，C3 部署后再排期：locales 注册表 1.4.0，14(4)(a) 改为 `evidenced: ["reviewers.guard.inputSnapshot"]`、`partial: ["whatIf.comparable"]`；core / ts / cloud 副本同值；先确认 core / ts 的注册表校验器是否有证据来源词表（有则同车次加入该名）；按 ADR 0053 列车发布，cloud 升副本，`credit-pilot.spec.ts` 预期表改为 14(4)(a) evidenced。单独写一份列车计划。

## 8. 后续 issue（本计划只负责开 issue）

- aster-cloud：执行日志页对策略可见者明文展示 `Execution.input`（ADR 0042 §12 F9），与审批面的打码口径不一致。已开：wontlost-ltd/aster-cloud#754。
- aster-api：from-evidence `evidence_input_mismatch` 的线上计数（上线后一周看 0 次即关）。

## 9. 待用户裁定（Task 0 前）

| # | 问题 | 建议（默认） | 备选 |
|---|---|---|---|
| 1 | `catalog.piiMark` 的处置 | 撤销；标记由 aster-api 从执行 IR 推导；`classifications` 作为预留格式写进 ADR，不实施 | cloud 签 ts 编译结果做双引擎交叉核对（需 FactVerifier 扩展） |
| 2 | from-evidence 输入与评估输入不一致 | 409，不消耗证据 | 只标 `unbound` 照常建决策 |
| 3 | 证据包 v6 是否同时装入 ADR 0041 §12 D3 的 `execution` | 装入（一次升版） | 留给 v7 |
| 4 | 14(4)(a) 改判时点 | 阶段 R，随下一次列车 | 不改判 |
| 5 | 执行日志页明文输入 | 另开 issue | 并入 #460 |
| 6 | 解除留痕粒度 | 每人每审批首次一条事件 | 每次一条 |
| 7 | 分类行保留期 / 视图上限 | `P7D` / 1 MiB | 其他值 |

## 10. 风险

- **分类行写在评估热路径上**：只有可审批结论才写（生产中占比小），写失败不影响评估；如有压测顾虑，可改为异步写（代价是 from-evidence 偶尔读不到 → `unbound`）。
- **cloud 输入在两次调用之间被规范化**：若 evaluate-source 的输入与 guard `context` 不是同一个对象，A4 会让所有登记 409。V1 第 4 条之前先跑一次正常执行确认 `evaluated`；cloud 侧以 `toGuardAction` 单测钉住「`context` 与 `engineInput` 引用相同」。
- **`InputClassifier` 与 Core IR v2**：ADR 0055 刚上线，`PiiType` 形状不变；推导器只读 Java `CoreModel`，不解析 JSON，不受 IR 交换格式影响。
- **迁移号冲突**：#464 / #465 同期开发，按合并顺序顺延，并在 checksum golden 中核对。

## 11. 自审记录

- 规格覆盖：§12 D1 → A2/A3/A4；D2 → A1/A4；D3 → Task 0 + C2（注释）；D4 → A5；D5 → A6；D6 → A7；D7 → A2/A5/A9；D8 → A6/A7/A8/S1；D9 → C3（+ 阶段 R）；D10 → C1/C2；D11 → §1 顺序、§8；§12.4 → V1。
- 类型一致：`InputClassification`（A1）在 A2 列 `classification`、A3 / A4 / A5 中同名；视图字段在 A5、S1、C1 中同名同序；`inputSnapshotMatched` 全文唯一拼写。
- 列名：ADR §12 D1 与本计划统一为 `classification`（声明树 + 标记），取代 §11 D1 的 `pii_marks`。
