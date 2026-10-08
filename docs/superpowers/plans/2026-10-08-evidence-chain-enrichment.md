# 证据链补齐（ADR 0041，子项目 3a）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每一次策略执行的证据能回答「哪条规则、哪个模型、谁复核」：aster-api 的评估事件携带规则与模型身份并可按相关键查询收据；aster-cloud 的执行记录可查、决策五态、证据包 v2 嵌入链收据与统一复核者。

**Architecture:** A 侧新增 `AgentIdentity` 与 `EvaluationEvidence` 值对象，三条 `/evaluate*` 端点生成 `correlationId`、把证据键写入异步落链的 `POLICY_EVALUATION` 事件并在响应返回 `evidence.correlationId`；追加器把 `correlationId`/`decisionId` 冗余到 `audit_logs` 新列供索引查询；新只读接口 `GET /api/v1/audit/receipts` 按相关键或决策 id 返回收据与审批事件。C 侧 `executions` 增五态 `outcome` 与可查列，执行路由透传 `agent` 并落新列，证据包 v2 导出时批量拉取收据并拼装 `reviewers[]`（guard 审批 / PolicyProof / 版本审批），A 不可达时标 `unavailable`。

**Tech Stack:** aster-api：Quarkus、Panache、Flyway、Vert.x JsonObject、JUnit 5 + RestAssured；aster-cloud：Next.js 16、Drizzle（手写 SQL 迁移）、vitest、next-intl。

**Spec:** `/Users/rpang/IdeaProjects/aster-api/docs/adr/0041-evidence-chain-enrichment.md`（分支 `adr-0041-evidence-chain`，2820152）。本计划末尾「对 ADR 的修正」表列出执行前已裁决的 ADR 与代码事实差异；Task 10 回写 ADR。

## Global Constraints

- 代码注释与文档用简体中文描述意图；禁止「修改说明」式注释；函数缩进 ≤ 3 层。
- aster-api：`export PATH=/opt/homebrew/opt/node@24/bin:/opt/podman/bin:$PATH`；纯 JUnit `./gradlew --offline :test --tests '<类名>'`；`@QuarkusTest` 需 Postgres（本地栈占用 55432/6379/8080/3100）：`podman run -d --name sdd-pg -p 55433:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=aster_policy postgres:17-alpine postgres -c fsync=off -c synchronous_commit=off -c max_connections=200`、`podman run -d --name sdd-redis -p 6380:6379 redis:7-alpine`，运行时 `DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380`，结束 `podman rm -f sdd-pg sdd-redis`。禁止触碰名为 `aster-*` 的容器。已知与本计划无关的失败：`PolicyGraphQLResourceTest.testBatchEvaluateLargeVolume`、`PrismaDatabaseE2ETest`。
- aster-api 测试 profile 下 apikey/rbac/签名过滤器关闭，身份只靠 `X-Tenant-Id` 与 `X-User-Id` 头（见 `GuardDecisionResourceTest`）。
- Flyway 新迁移 `V6.28.0__audit_logs_evidence_columns.sql`，`src/test/resources/db/migration-checksums.golden` 追加 `文件名=SHA256`（`shasum -a 256 <file>`，先把 CRLF 归一为 LF）；不改既有迁移。
- aster-cloud：`export PATH=/opt/homebrew/opt/node@24/bin:$PATH`；门禁 `pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm test:run && pnpm check:locales:strict`。测试用手写 mock（`@/lib/prisma` 的 `db.query.*` 与 `drizzle-orm` 算子）；路由/库新 import 必须同步进相关测试 mock。
- Drizzle 迁移手写 `drizzle/0050_execution_evidence.sql`，`IF NOT EXISTS`，`--> statement-breakpoint` 分隔；`_journal.json` 追加 `{ "idx": 50, "version": "7", "when": 1789500000000, "tag": "0050_execution_evidence", "breakpoints": true }`。
- 哈希链 append-only 不动；`/evaluate*` 不新增同步 DB 写；证据缺失诚实标注（`missing` / `legacy` / `unavailable`），绝不伪造哈希。
- 向后兼容：不传 `agent` 的客户端、不读 `evidence` 的旧 C、不返回 `evidence` 的旧 A 互相兼容，所有新字段可选追加。
- 提交信息后缀空行 + `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。

## Review Focus

1. 旧链行（无 `correlation_id` 列值）与 `/evaluate-source` simulate 模式（不落链）：收据接口必须把它们报为 `missing`，证据包条目必须标 `missing`，而不是 500 或空对象（Task 5 接口测试 + Task 9 bundle 测试）。
2. `agent` 字段含控制字符或超 255 字符：三条 `/evaluate*` 与 guard 都应 400，且不得先执行策略再报错（Task 1 校验测试 + Task 3 端点测试）。
3. 追加器 metadata 超 4096 时会把 JSON 截断成非法 JSON：证据键在最坏情况（4×255 的 agent + 255 ruleId + 16×64 controls + 既有 applicantId）下总长必须 < 3800，由 `EvaluationEvidence` 的裁剪保证（Task 1 最坏情况测试）。
4. 收据接口的租户隔离：用另一租户的相关键查询必须得到 `missing`，不得泄露任何字段（Task 5）。
5. 证据包导出时 A 整批超时：导出仍成功，条目 `unavailable`，manifest 计数正确，且 `bundleHash` 对同一数据确定（Task 8/9）。

---

## 文件结构

**aster-api（分支 `feat/adr-0041-evidence-chain`，基于 `adr-0041-evidence-chain`）**

| 文件 | 职责 |
|---|---|
| `src/main/java/io/aster/policy/evidence/AgentIdentity.java`（新） | 模型身份记录 + 校验 |
| `src/main/java/io/aster/policy/evidence/EvaluationEvidence.java`（新） | 证据键值对象，统一产出 metadata 扁平键 |
| `src/main/java/io/aster/policy/rest/model/{EvaluationRequest,JsonPolicyRequest,SourcePolicyRequest,EvaluationResponse}.java` | `agent` 入参、`evidence` 出参 |
| `src/main/java/io/aster/policy/rest/{PolicyAuditPublisher,PolicyEvaluationResource}.java` | 三端点生成 correlationId、发布证据键 |
| `src/main/java/io/aster/audit/chain/AuditChainAppender.java`、`src/main/java/io/aster/policy/entity/AuditLog.java`、`V6.28.0` | 冗余列 `correlation_id`/`decision_id` + 豁免键 |
| `src/main/java/io/aster/audit/rest/AuditReceiptResource.java`（新）、`src/main/java/io/aster/audit/service/AuditReceiptService.java`（新）、`src/main/java/io/aster/policy/security/InternalCallerFilter.java` | 收据查询接口 |
| `src/main/java/io/aster/guard/{api/GuardActionRequest,GuardReceiptBuilder,GuardDecisionDraft}.java` | guard 的结构化模型身份进收据 |

**aster-cloud（分支 `feat/adr-0041-execution-evidence`，基于 `main`）**

| 文件 | 职责 |
|---|---|
| `drizzle/0050_execution_evidence.sql`、`src/db/schema.ts` | 五态枚举 + `executions` 新列 |
| `src/services/policy/{policy-api,cnl-executor}.ts`、两条执行路由 | 透传 `agent`，落 `outcome/ruleId/controls/agent/evidenceCorrelationId` |
| `src/services/evidence/{types,bundle}.ts`、`src/services/evidence/receipts-client.ts`（新）、`src/services/evidence/reviewers.ts`（新）、`src/lib/{evidence,evidence-export}.ts` | 证据包 v2 |
| `src/app/api/policies/[id]/logs/route.ts`、`logs-content.tsx`、`reports-content.tsx`、`src/i18n/demo-supplement.ts` | 五态消费者 |

---

### Task 1: `AgentIdentity` 与 `EvaluationEvidence` 值对象（aster-api）

**Files:**
- Create: `src/main/java/io/aster/policy/evidence/AgentIdentity.java`
- Create: `src/main/java/io/aster/policy/evidence/EvaluationEvidence.java`
- Test: `src/test/java/io/aster/policy/evidence/AgentIdentityTest.java`、`src/test/java/io/aster/policy/evidence/EvaluationEvidenceTest.java`

**Interfaces:**
- Produces:

```java
package io.aster.policy.evidence;
public record AgentIdentity(String provider, String model, String version, String session) {
  public static final int MAX_TEXT = 255;
  /** 校验并归一：provider/model 必填（有 agent 时），四字段 ≤255 且无控制字符；非法抛 IllegalArgumentException（消息为中文字段名）。 */
  public static AgentIdentity validated(AgentIdentity raw);           // raw == null → null
  /** 仅有 model（guard 旧请求 Agent(model, session)）：provider 取 "unknown"。 */
  public static AgentIdentity ofModel(String model, String session);  // model 空白 → null
}
public record EvaluationEvidence(String correlationId, String outcome, String ruleId, List<String> controls,
                                 Long policyVersionId, AgentIdentity agent) {
  public static EvaluationEvidence of(String outcome, String ruleId, List<String> controls, Long policyVersionId, AgentIdentity agent); // correlationId = UUID.randomUUID()
  /** 扁平 metadata 键：correlationId, outcome, ruleId, controls, policyVersionId, agentProvider, agentModel, agentVersion, agentSession, agentSource("declared")；null/空不写键。 */
  public Map<String, Object> toMetadata();
  public static final int MAX_CONTROLS = 16, MAX_CONTROL_LENGTH = 64, MAX_TEXT = 255;
}
```

- [ ] **Step 1: 写失败测试**

```java
// AgentIdentityTest.java（纯 JUnit）
class AgentIdentityTest {
  @Test void 合法四字段原样保留() {
    AgentIdentity a = AgentIdentity.validated(new AgentIdentity("anthropic", "claude-fable-5-1", "2026-10", "s-1"));
    assertThat(a).isEqualTo(new AgentIdentity("anthropic", "claude-fable-5-1", "2026-10", "s-1"));
  }
  @Test void null入参返回null() { assertThat(AgentIdentity.validated(null)).isNull(); }
  @Test void provider或model缺失报错() {
    assertThatThrownBy(() -> AgentIdentity.validated(new AgentIdentity(null, "m", null, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agent.provider");
    assertThatThrownBy(() -> AgentIdentity.validated(new AgentIdentity("p", " ", null, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agent.model");
  }
  @Test void 超长与控制字符报错() {
    assertThatThrownBy(() -> AgentIdentity.validated(new AgentIdentity("p", "m".repeat(256), null, null)))
        .hasMessageContaining("agent.model");
    assertThatThrownBy(() -> AgentIdentity.validated(new AgentIdentity("p", "m", "v\u0007", null)))
        .hasMessageContaining("agent.version");
  }
  @Test void ofModel缺省provider为unknown且空model为null() {
    assertThat(AgentIdentity.ofModel("claude", "s")).isEqualTo(new AgentIdentity("unknown", "claude", null, "s"));
    assertThat(AgentIdentity.ofModel(" ", "s")).isNull();
  }
}

// EvaluationEvidenceTest.java（纯 JUnit）
class EvaluationEvidenceTest {
  @Test void toMetadata扁平键且null不写键() {
    EvaluationEvidence e = new EvaluationEvidence("c-1", "ALLOW", "R-1", List.of("GDPR:ART17"), null,
        new AgentIdentity("anthropic", "claude", null, "s-1"));
    assertThat(e.toMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
        "correlationId", "c-1", "outcome", "ALLOW", "ruleId", "R-1", "controls", List.of("GDPR:ART17"),
        "agentProvider", "anthropic", "agentModel", "claude", "agentSession", "s-1", "agentSource", "declared"));
  }
  @Test void 无agent无规则时只剩correlationId() {
    EvaluationEvidence e = new EvaluationEvidence("c-2", null, null, List.of(), null, null);
    assertThat(e.toMetadata()).containsOnlyKeys("correlationId");
  }
  @Test void of生成UUID相关键() {
    EvaluationEvidence e = EvaluationEvidence.of("DENY", null, null, 7L, null);
    assertThat(e.correlationId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    assertThat(e.toMetadata()).containsEntry("policyVersionId", 7L);
  }
  @Test void 裁剪保证最坏情况序列化长度小于3800() throws Exception {
    List<String> controls = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "C".repeat(100) + i).toList();
    EvaluationEvidence e = new EvaluationEvidence("c-3", "REQUIRE_APPROVAL", "R".repeat(500), controls, 1L,
        new AgentIdentity("p".repeat(500), "m".repeat(500), "v".repeat(500), "s".repeat(500)));
    Map<String, Object> m = e.toMetadata();
    assertThat(((List<?>) m.get("controls"))).hasSize(16);
    assertThat(((List<?>) m.get("controls")).get(0).toString()).hasSize(64);
    assertThat(m.get("ruleId").toString()).hasSize(255);
    assertThat(m.get("agentProvider").toString()).hasSize(255);
    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
    assertThat(json.length()).isLessThan(3800);
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --offline :test --tests 'io.aster.policy.evidence.*'`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

```java
// AgentIdentity.java
package io.aster.policy.evidence;

/** 行动中的模型身份（ADR 0041 §3）：调用方自报，收据标注 source=declared；provider/model 必填。 */
public record AgentIdentity(String provider, String model, String version, String session) {
  public static final int MAX_TEXT = 255;
  public static final String UNKNOWN_PROVIDER = "unknown";

  public static AgentIdentity validated(AgentIdentity raw) {
    if (raw == null) return null;
    return new AgentIdentity(
        required("agent.provider", raw.provider()),
        required("agent.model", raw.model()),
        optional("agent.version", raw.version()),
        optional("agent.session", raw.session()));
  }

  public static AgentIdentity ofModel(String model, String session) {
    if (model == null || model.isBlank()) return null;
    return validated(new AgentIdentity(UNKNOWN_PROVIDER, model, null, session));
  }

  private static String required(String field, String v) {
    if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " 必填");
    return optional(field, v);
  }

  private static String optional(String field, String v) {
    if (v == null) return null;
    if (v.length() > MAX_TEXT) throw new IllegalArgumentException(field + " 超过 " + MAX_TEXT + " 字符");
    if (v.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + " 含控制字符");
    return v;
  }
}
```

```java
// EvaluationEvidence.java
package io.aster.policy.evidence;

import java.util.*;

/** 一次评估的证据键（ADR 0041 §2.2）：进入 POLICY_EVALUATION 事件 metadata，并把 correlationId 回给调用方。 */
public record EvaluationEvidence(String correlationId, String outcome, String ruleId, List<String> controls,
                                 Long policyVersionId, AgentIdentity agent) {
  public static final int MAX_TEXT = 255;
  public static final int MAX_CONTROLS = 16;
  public static final int MAX_CONTROL_LENGTH = 64;
  public static final String SOURCE_DECLARED = "declared";

  public static EvaluationEvidence of(String outcome, String ruleId, List<String> controls, Long policyVersionId, AgentIdentity agent) {
    return new EvaluationEvidence(UUID.randomUUID().toString(), outcome, ruleId, controls, policyVersionId, agent);
  }

  /** 扁平键而非嵌套对象：追加器的脱敏与 guard 的预算收缩只处理顶层 String/List。 */
  public Map<String, Object> toMetadata() {
    Map<String, Object> m = new LinkedHashMap<>();
    put(m, "correlationId", correlationId);
    put(m, "outcome", clip(outcome));
    put(m, "ruleId", clip(ruleId));
    List<String> c = clipControls(controls);
    if (!c.isEmpty()) m.put("controls", c);
    if (policyVersionId != null) m.put("policyVersionId", policyVersionId);
    if (agent != null) {
      put(m, "agentProvider", clip(agent.provider()));
      put(m, "agentModel", clip(agent.model()));
      put(m, "agentVersion", clip(agent.version()));
      put(m, "agentSession", clip(agent.session()));
      m.put("agentSource", SOURCE_DECLARED);
    }
    return m;
  }

  private static void put(Map<String, Object> m, String k, String v) {
    if (v != null && !v.isEmpty()) m.put(k, v);
  }

  private static String clip(String s) {
    return s == null || s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT);
  }

  private static List<String> clipControls(List<String> controls) {
    if (controls == null) return List.of();
    return controls.stream().filter(Objects::nonNull).limit(MAX_CONTROLS)
        .map(c -> c.length() <= MAX_CONTROL_LENGTH ? c : c.substring(0, MAX_CONTROL_LENGTH)).toList();
  }
}
```

- [ ] **Step 4: 运行测试通过**

Run: `./gradlew --offline :test --tests 'io.aster.policy.evidence.*'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/policy/evidence src/test/java/io/aster/policy/evidence
git commit -m "feat(evidence): AgentIdentity 与 EvaluationEvidence 值对象（ADR 0041 §2-§3）"
```

---

### Task 2: 请求/响应模型携带 `agent` 与 `evidence`（aster-api）

**Files:**
- Modify: `src/main/java/io/aster/policy/rest/model/EvaluationRequest.java`、`JsonPolicyRequest.java`、`SourcePolicyRequest.java`
- Modify: `src/main/java/io/aster/policy/rest/model/EvaluationResponse.java`
- Test: `src/test/java/io/aster/policy/rest/model/EvaluationResponseEvidenceTest.java`（新）、`src/test/java/io/aster/policy/rest/PolicyEvaluationReplayOrderingTest.java:195-260`

**Interfaces:**
- Produces: 三个请求记录各增 `@JsonProperty("agent") AgentIdentity agent`（可选，末位组件）；`EvaluationResponse` 增末位组件 `@JsonProperty("evidence") @JsonInclude(NON_NULL) Evidence evidence`，嵌套 `public record Evidence(@JsonProperty("correlationId") String correlationId)`，新增 `public EvaluationResponse withEvidence(String correlationId)`；既有 `success/error/ambiguous/diagnostic/withReplayMetadata/withTraceSkeleton` 全部补传 `null`/透传。

- [ ] **Step 1: 写失败测试**

```java
// EvaluationResponseEvidenceTest.java（纯 JUnit）
class EvaluationResponseEvidenceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  @Test void withEvidence输出evidence对象且字母序位置正确() throws Exception {
    EvaluationResponse r = EvaluationResponse.success(42, 3L).withEvidence("c-1");
    String json = mapper.writeValueAsString(r);
    assertThat(json).contains("\"evidence\":{\"correlationId\":\"c-1\"}");
    assertThat(json.indexOf("\"error\"")).isLessThan(json.indexOf("\"evidence\"")); // 字母序：error < evidence < executionTimeMs
    assertThat(json.indexOf("\"evidence\"")).isLessThan(json.indexOf("\"executionTimeMs\""));
  }
  @Test void 无evidence时不输出键() throws Exception {
    assertThat(mapper.writeValueAsString(EvaluationResponse.success(42, 3L))).doesNotContain("evidence");
  }
  @Test void withReplayMetadata与withTraceSkeleton保留evidence() {
    EvaluationResponse r = EvaluationResponse.success(1, 1L).withEvidence("c-9").withReplayMetadata(null).withTraceSkeleton(null);
    assertThat(r.evidence().correlationId()).isEqualTo("c-9");
  }
  @Test void 请求记录接受agent字段() throws Exception {
    EvaluationRequest req = mapper.readValue(
        "{\"policyModule\":\"m\",\"policyFunction\":\"f\",\"context\":[{}],\"agent\":{\"provider\":\"anthropic\",\"model\":\"claude\"}}",
        EvaluationRequest.class);
    assertThat(req.agent()).isEqualTo(new AgentIdentity("anthropic", "claude", null, null));
    assertThat(mapper.readValue("{\"policy\":\"{}\",\"context\":{}}", JsonPolicyRequest.class).agent()).isNull();
  }
}
```

`PolicyEvaluationReplayOrderingTest`：响应现在带 `evidence`（Task 3 落地后），在该测试的归一化步骤把 `evidence.correlationId` 替换为常量 `"<correlationId>"`（与 `executionTimeMs`/`runtimeToolchainId` 同法），`expected` ObjectNode 加 `expected.set("evidence", mapper.createObjectNode().put("correlationId", "<correlationId>"))`（放在 `error` 与 `executedFunction` 之间，字母序）。本任务先加断言准备，Task 3 使其通过。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --offline :test --tests 'io.aster.policy.rest.model.EvaluationResponseEvidenceTest'`
Expected: 编译失败

- [ ] **Step 3: 实现**

`EvaluationRequest`（`JsonPolicyRequest`、`SourcePolicyRequest` 同样在末尾加）：

```java
    // 行动中的模型身份（ADR 0041 §3）；可选，由调用方自报
    @JsonProperty("agent")
    AgentIdentity agent
```

`EvaluationResponse`：末位组件

```java
    // 证据相关键（ADR 0041 §2.3）：用它向 GET /api/v1/audit/receipts 取链收据；simulate 模式不输出
    @JsonProperty("evidence")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Evidence evidence
) {
    public record Evidence(@JsonProperty("correlationId") String correlationId) {}

    public EvaluationResponse withEvidence(String correlationId) {
        return new EvaluationResponse(result, executionTimeMs, error, decisionTrace, executedFunction,
            diagnostics, replayMetadata, traceSkeleton, decision, ruleId, controls,
            correlationId == null ? null : new Evidence(correlationId));
    }
```

所有 `new EvaluationResponse(...)` 调用补第 12 个实参：工厂传 `null`，`withReplayMetadata`/`withTraceSkeleton` 透传 `evidence`。全仓 grep `new EvaluationResponse(` 确认无遗漏（含测试）。

- [ ] **Step 4: 运行测试通过**

Run: `./gradlew --offline :test --tests 'io.aster.policy.rest.model.*' --tests 'io.aster.policy.rest.PolicyEvaluationReplayOrderingTest'`（后者在 Task 3 前仍会因缺 evidence 失败，记录为预期；`@QuarkusTest` 需容器）
Expected: 新测试 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/policy/rest/model src/test/java/io/aster/policy/rest
git commit -m "feat(api): 评估请求携带 agent、响应携带 evidence.correlationId（ADR 0041 §2.3/§3）"
```

---

### Task 3: 三条 `/evaluate*` 端点生成相关键并发布证据键（aster-api）

**Files:**
- Modify: `src/main/java/io/aster/policy/rest/PolicyAuditPublisher.java`
- Modify: `src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java`（`/evaluate` 213-262、`/evaluate-json` 332-366、`/evaluate-source` 585-645、helper 1223-1242）
- Test: `src/test/java/io/aster/policy/rest/PolicyAuditPublisherTest.java`（新，纯 JUnit）、`src/test/java/io/aster/policy/rest/EvaluateEvidenceEventTest.java`（新，@QuarkusTest）

**Interfaces:**
- Consumes: Task 1 `EvaluationEvidence`、`AgentIdentity`；Task 2 `agent()` / `withEvidence`。
- Produces: `PolicyAuditPublisher.publish(tenantId, request, performedBy, success, executionTimeMs, errorMessage, metadata, EvaluationEvidence evidence)`——`evidence == null` 时与旧行为一致；否则把 `evidence.toMetadata()` 合并进 metadata（证据键覆盖同名旧键）。旧 7 参 `publish` 保留并委托。

- [ ] **Step 1: 写失败测试**

```java
// PolicyAuditPublisherTest.java（纯 JUnit，用 Mockito mock Event<AuditEvent> 捕获 fireAsync 的事件）
class PolicyAuditPublisherTest {
  @Test void 证据键并入metadata且覆盖同名键() {
    PolicyAuditPublisher p = new PolicyAuditPublisher();
    @SuppressWarnings("unchecked") Event<AuditEvent> ev = mock(Event.class);
    p.auditEventPublisher = ev;
    EvaluationEvidence e = new EvaluationEvidence("c-1", "ALLOW", "R-1", List.of("X"), null, new AgentIdentity("p", "m", null, null));
    p.publish("t", new EvaluationRequest("mod", "fn", new Object[]{Map.of("applicantId", "A")}, null), "u", true, 5L, null,
        Map.of("contextSize", 1, "outcome", "stale"), e);
    ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
    verify(ev).fireAsync(cap.capture());
    assertThat(cap.getValue().metadata()).containsEntry("correlationId", "c-1").containsEntry("outcome", "ALLOW")
        .containsEntry("contextSize", 1).containsEntry("agentSource", "declared");
  }
  @Test void evidence为null时行为不变() { /* 7 参 publish：metadata 原样 */ }
}
```

```java
// EvaluateEvidenceEventTest.java（@QuarkusTest；部署一条 Verdict 策略后调用三端点，等待异步落链，读 AuditLog）
@QuarkusTest
class EvaluateEvidenceEventTest {
  private static final String TENANT = "tenant-evidence-" + UUID.randomUUID().toString().substring(0, 8);
  // 用 DeployedMapParamProbeTest.deploy(TENANT, "guard.probe", "decide", DeployedMapParamProbeTest.SOURCE) 部署（SOURCE 为 ADR 0040 §2.2 示例，模块名 guard.probe）
  @Test void evaluate响应带correlationId且链事件含证据键() {
    var resp = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u-ev").contentType(JSON)
        .body(Map.of("policyModule", "guard.probe", "policyFunction", "decide",
            "context", List.of(Map.of("principal_id", "x", "principal_type", "agent", "principal_roles", List.of(),
                "action", "read_customer_record", "resource_type", "", "resource_id", "", "context", Map.of(), "agent_model", "")),
            "agent", Map.of("provider", "anthropic", "model", "claude-fable-5-1", "session", "s-1")))
        .post("/api/v1/policies/evaluate");
    resp.then().statusCode(200);
    String cid = resp.jsonPath().getString("evidence.correlationId");
    assertThat(cid).isNotBlank();
    AuditLog row = awaitEvent(cid); // 轮询 ≤ 5 s：AuditLog.find("tenantId = ?1 and correlationId = ?2", TENANT, cid)
    Map<String, Object> md = JacksonMappers.DEFAULT.readValue(row.metadata, Map.class);
    assertThat(md).containsEntry("correlationId", cid).containsEntry("outcome", "ALLOW").containsEntry("ruleId", "CUST-DEL-001")
        .containsEntry("agentProvider", "anthropic").containsEntry("agentModel", "claude-fable-5-1").containsEntry("agentSession", "s-1")
        .containsEntry("agentSource", "declared");
    assertThat(md).doesNotContainKey("policyVersionId"); // /evaluate 本期不写版本（见对 ADR 的修正 #2）
  }
  @Test void agent非法则400且不落链() { /* provider 缺失 → 400；等 1 s 后按 tenant 查无新 POLICY_EVALUATION 行 */ }
  @Test void evaluateSource的simulate模式不输出evidence也不落链() { /* ?simulate=true：响应无 evidence 键；无新事件 */ }
  @Test void evaluateSource非simulate输出evidence并落链() { /* 同第一条，走 /evaluate-source 与 /evaluate-json */ }
}
```

（`AuditLog.correlationId` 字段由 Task 4 提供；本任务先按 `metadata like '%cid%'` 查询，Task 4 落地后改为列查询。）

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --offline :test --tests 'io.aster.policy.rest.PolicyAuditPublisherTest'`
Expected: 编译失败（8 参 publish 不存在）

- [ ] **Step 3: 实现**

`PolicyAuditPublisher`：

```java
    public void publish(String tenantId, EvaluationRequest request, String performedBy, boolean success,
                        long executionTimeMs, String errorMessage, Map<String, Object> metadata) {
        publish(tenantId, request, performedBy, success, executionTimeMs, errorMessage, metadata, null);
    }

    /** 证据键（ADR 0041 §2.2）并入 metadata，同名键以证据为准；evidence 为 null 时与旧行为完全一致。 */
    public void publish(String tenantId, EvaluationRequest request, String performedBy, boolean success,
                        long executionTimeMs, String errorMessage, Map<String, Object> metadata, EvaluationEvidence evidence) {
        if (auditEventPublisher == null) return;
        Map<String, Object> merged = new HashMap<>(metadata == null ? Map.of() : metadata);
        if (evidence != null) merged.putAll(evidence.toMetadata());
        auditEventPublisher.fireAsync(AuditEvent.policyEvaluation(tenantId, request.policyModule(), request.policyFunction(),
            performedBy, success, executionTimeMs, errorMessage, merged.isEmpty() ? Collections.emptyMap() : merged));
    }
```

`PolicyEvaluationResource`（三端点共同模式）：

1. 请求进入后、执行前：`AgentIdentity agent; try { agent = AgentIdentity.validated(request.agent()); } catch (IllegalArgumentException e) { return Uni.createFrom().item(Response-equivalent 400) }`——该资源的 400 形态沿用现有 `EvaluationResponse.diagnostic("invalid_agent", e.getMessage(), List.of())` + 状态码 400（查看资源内既有 400 返回方式并沿用）。
2. 成功分支：`EvaluationEvidence evidence = EvaluationEvidence.of(outcomeOf(result.getDecision()), result.getRuleId(), result.getControls(), null, agent);`（`outcomeOf(VerdictView v)` = `v == null ? null : v.outcome()`）；`publishPolicyEvaluationEvent(..., metadata, evidence)`；响应 `.withEvidence(evidence.correlationId())`。
3. 失败分支：`EvaluationEvidence.of(null, null, null, null, agent)`，同样发布并在 `error` 响应上 `withEvidence`（失败也落链，证据可追）。
4. `/evaluate-json`：`request.agent()` 同上；outcome 取 `VerdictView.from(result.result())`。
5. `/evaluate-source`：`effectiveSimulate` 为 true 时**不**生成 evidence、不 `withEvidence`；否则同上，`ruleId/controls` 来自 `phase.execResult()`。
6. helper `publishPolicyEvaluationEvent` 增 8 参重载透传 evidence。

每个端点加一行中文注释说明 correlationId 的作用与 simulate 例外。

- [ ] **Step 4: 运行测试通过**

Run（纯）：`./gradlew --offline :test --tests 'io.aster.policy.rest.PolicyAuditPublisherTest' --tests 'io.aster.policy.rest.model.*'`
Run（容器）：`… ./gradlew --offline :test --tests 'io.aster.policy.rest.EvaluateEvidenceEventTest' --tests 'io.aster.policy.rest.PolicyEvaluationReplayOrderingTest' --tests 'io.aster.policy.rest.*Simulate*'`
Expected: PASS（含 golden 更新后的字节一致）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/policy/rest src/test/java/io/aster/policy/rest
git commit -m "feat(api): /evaluate* 生成相关键、证据键异步落链、响应返回 evidence（ADR 0041 §2）"
```

---

### Task 4: 追加器冗余列与豁免键 + V6.28.0（aster-api）

**Files:**
- Create: `src/main/resources/db/migration/V6.28.0__audit_logs_evidence_columns.sql`
- Modify: `src/test/resources/db/migration-checksums.golden`
- Modify: `src/main/java/io/aster/policy/entity/AuditLog.java`、`src/main/java/io/aster/audit/chain/AuditChainAppender.java:44-53,120-150`
- Test: `src/test/java/io/aster/audit/chain/AuditChainAppenderRedactionTest.java`、`src/test/java/io/aster/audit/chain/AuditChainAppenderEvidenceColumnsTest.java`（新，@QuarkusTest）、`src/test/java/io/aster/db/MigrationGovernanceTest.java`

**Interfaces:**
- Produces: `AuditLog.correlationId`（`correlation_id VARCHAR(64)`）、`AuditLog.decisionId`（`decision_id VARCHAR(64)`），由 `toAuditLog` 从 metadata 的 `correlationId`/`decisionId` 字符串值填充（非字符串或缺失 → null）；finder `AuditLog.findByCorrelationIds(tenantId, List<String>)`、`AuditLog.findByDecisionIds(tenantId, List<String>)`（各 ≤ 200，按 id 升序）。`IDENTITY_METADATA_KEYS` 加 `correlationId`。哈希载荷不变（新列是 metadata 的冗余投影，不进 `AuditHashPayload`）。

- [ ] **Step 1: 写失败测试**

`AuditChainAppenderRedactionTest` 新增：

```java
  @Test void correlationId非UUID值也逐字入链() {
    String json = persistedMetadata(Map.of("correlationId", "4111-1111-1111-1111-x", "comment", CARD));
    assertThat(json).contains("\"correlationId\":\"4111-1111-1111-1111-x\"").contains("\"comment\":\"****-****-****-****\"");
  }
  @Test void toAuditLog把correlationId与decisionId投影到列() {
    AuditEvent event = new AuditEvent(EventType.POLICY_EVALUATION, Instant.now(), "t", "m", "f", null, null, null, "u", true, null, null,
        Map.of("correlationId", "c-1", "decisionId", "d-1", "x", 1), null, null, null, null);
    AuditLog log = appender.toAuditLog(event);
    assertThat(log.correlationId).isEqualTo("c-1");
    assertThat(log.decisionId).isEqualTo("d-1");
  }
  @Test void 列投影缺失或非字符串时为null() { /* metadata 无键 → null；decisionId 为 Integer → null */ }
```

`AuditChainAppenderEvidenceColumnsTest`（@QuarkusTest）：`appendSync` 一条含 `correlationId` 的事件后，`AuditLog.findByCorrelationIds(tenant, List.of(cid))` 返回该行且 `currentHash` 非空；另一租户查询返回空；201 个 id 抛 `IllegalArgumentException`。

`MigrationGovernanceTest`：新文件登记 golden（`newMigrationsRegistered` 必须通过）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --offline :test --tests 'io.aster.audit.chain.AuditChainAppenderRedactionTest'`
Expected: 编译失败（字段不存在）

- [ ] **Step 3: 实现**

```sql
-- V6.28.0__audit_logs_evidence_columns.sql
-- 证据链补齐（ADR 0041 §2.4）：把 metadata 中的 correlationId / decisionId 冗余成列以便索引查询。
-- 不用 metadata::jsonb 表达式索引：metadata 超限时追加器会写入非法 JSON（…truncated），表达式索引会让插入失败。
-- 两列不进哈希载荷（AuditHashPayload），只是 metadata 的投影；旧行为 NULL，收据接口对其返回 missing。
ALTER TABLE audit_logs
    ADD COLUMN correlation_id VARCHAR(64),
    ADD COLUMN decision_id VARCHAR(64);
CREATE INDEX audit_logs_tenant_correlation_ix ON audit_logs (tenant_id, correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX audit_logs_tenant_decision_ix ON audit_logs (tenant_id, decision_id) WHERE decision_id IS NOT NULL;
```

`AuditLog`：两个 `@Column` 字段 + finders：

```java
    private static final int MAX_LOOKUP_IDS = 200;
    public static java.util.List<AuditLog> findByCorrelationIds(String tenantId, java.util.List<String> ids) {
        return lookup("correlationId", tenantId, ids);
    }
    public static java.util.List<AuditLog> findByDecisionIds(String tenantId, java.util.List<String> ids) {
        return lookup("decisionId", tenantId, ids);
    }
    private static java.util.List<AuditLog> lookup(String column, String tenantId, java.util.List<String> ids) {
        if (ids.size() > MAX_LOOKUP_IDS) throw new IllegalArgumentException("最多 " + MAX_LOOKUP_IDS + " 个 id");
        if (ids.isEmpty()) return java.util.List.of();
        return find("tenantId = ?1 and " + column + " in ?2 order by id asc", tenantId, ids).list();
    }
```

`AuditChainAppender.toAuditLog`：`log.correlationId = stringValue(event.metadata(), "correlationId"); log.decisionId = stringValue(event.metadata(), "decisionId");`，`stringValue` 返回 `v instanceof String s && !s.isBlank() ? clip(s, 64) : null`。`IDENTITY_METADATA_KEYS` 加 `"correlationId"`。append-only 触发器（V6.22.0）只禁 UPDATE/DELETE，ALTER 不受影响——实施者在 scratch 库上实跑迁移验证。

golden：`shasum -a 256 src/main/resources/db/migration/V6.28.0__audit_logs_evidence_columns.sql` 追加一行。

- [ ] **Step 4: 运行测试通过**

Run（纯）：`./gradlew --offline :test --tests 'io.aster.audit.chain.AuditChainAppenderRedactionTest' --tests 'io.aster.db.MigrationGovernanceTest'`
Run（容器）：`… --tests 'io.aster.audit.chain.*' --tests 'io.aster.audit.*Verifier*'`（链校验不受新列影响）
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/db/migration/V6.28.0__audit_logs_evidence_columns.sql src/test/resources/db/migration-checksums.golden src/main/java/io/aster/policy/entity/AuditLog.java src/main/java/io/aster/audit/chain/AuditChainAppender.java src/test
git commit -m "feat(audit): audit_logs 冗余 correlation_id/decision_id 列与豁免键（ADR 0041 §2.4）"
```

---

### Task 5: 收据查询接口 `GET /api/v1/audit/receipts`（aster-api）

**Files:**
- Create: `src/main/java/io/aster/audit/service/AuditReceiptService.java`、`src/main/java/io/aster/audit/rest/AuditReceiptResource.java`、`src/main/java/io/aster/audit/dto/ReceiptLookupResponse.java`
- Modify: `src/main/java/io/aster/policy/security/InternalCallerFilter.java`（`classify`：`/api/v1/audit/receipts` 归入 HMAC 内部调用者类别，与 evaluate-source 同口径）
- Modify: `src/test/resources/api/public-api-contract.golden`（首次运行自动追加 `GET /api/v1/audit/receipts`，提交它）
- Test: `src/test/java/io/aster/audit/rest/AuditReceiptResourceTest.java`（@QuarkusTest）

**Interfaces:**
- Consumes: Task 4 finders。
- Produces（字母序、NON_NULL/NON_EMPTY，与 `EvaluationResponse` 规则一致）:

```java
public record ReceiptLookupResponse(List<Approval> approvals, List<String> missing, List<Receipt> receipts) {
  public record Receipt(Long auditId, String correlationId, String currentHash, String decisionId, String eventType,
                        Short hashVersion, Map<String, Object> metadata, String prevHash, Instant timestamp) {}
  public record Approval(Long auditId, String comment, String currentHash, String decidedAt, String decidedBy,
                         String decisionId, String decisionReceiptHash, String outcome, String requiredRole) {}
}
```

- `GET /api/v1/audit/receipts?correlationIds=a,b` 或 `?decisionIds=x,y`：两参互斥（同时给 → 400 `{"error":"exactly_one_of_correlationIds_or_decisionIds"}`），各 ≤ 200（超限 400）；`@RequireRole(Role.MEMBER)`（方法级，类不加 ADMIN）；租户取 `RequestIdentityResolver.tenantId()`；`metadata` 解析失败（旧截断行）时返回 `{"_raw": "<原文>"}`。
- `decisionIds`：`receipts` 为 `ACTION_GUARD_DECISION` 行；`approvals` 为同租户、`decision_id` 相同且 `event_type = ACTION_GUARD_APPROVAL` 的行（`outcome/decidedBy/requiredRole/comment/decisionReceiptHash` 取自 metadata，`decidedAt` 取行 timestamp）。guard 审批事件的 metadata 含 `decisionId`，Task 4 的投影列因此覆盖它。
- `missing`：请求中未命中的 id，保持请求顺序。

- [ ] **Step 1: 写失败测试**

```java
@QuarkusTest
class AuditReceiptResourceTest {
  // setUp：用 AuditChainAppender.appendSync 写 3 条事件到 TENANT：POLICY_EVALUATION(correlationId=c1)、ACTION_GUARD_DECISION(decisionId=d1)、ACTION_GUARD_APPROVAL(decisionId=d1, outcome=APPROVED, decidedBy=u3, requiredRole=R, decisionReceiptHash=<d1 行 currentHash>)；OTHER_TENANT 写一条 correlationId=c1 的事件
  @Test void 按相关键返回收据与missing() {
    var r = given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u").queryParam("correlationIds", "c1,c-none")
        .get("/api/v1/audit/receipts").then().statusCode(200).extract().jsonPath();
    assertThat(r.getList("receipts")).hasSize(1);
    assertThat(r.getString("receipts[0].correlationId")).isEqualTo("c1");
    assertThat(r.getString("receipts[0].currentHash")).hasSize(64);
    assertThat(r.getList("missing")).containsExactly("c-none");
  }
  @Test void 按决策id返回决策收据与审批事件() { /* receipts[0].decisionId==d1；approvals[0].decidedBy==u3、outcome==APPROVED、decisionReceiptHash==receipts[0].currentHash */ }
  @Test void 其他租户的相关键报missing不泄露() { /* OTHER_TENANT 身份查 c1 → receipts 空、missing=[c1] */ }
  @Test void 两参同时给400_超过200个400() {}
  @Test void 旧截断metadata返回_raw() { /* 直接 persist 一条 metadata="{\"a\":1...truncated" 且 correlation_id=c-old 的 AuditLog → metadata._raw 存在 */ }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `… ./gradlew --offline :test --tests 'io.aster.audit.rest.AuditReceiptResourceTest'`
Expected: 404（资源不存在）

- [ ] **Step 3: 实现**

`AuditReceiptService`（`@ApplicationScoped`，`@Transactional` 只读）：

```java
    public ReceiptLookupResponse byCorrelationIds(String tenantId, List<String> ids) {
        List<AuditLog> rows = AuditLog.findByCorrelationIds(tenantId, ids);
        Set<String> found = rows.stream().map(r -> r.correlationId).collect(Collectors.toSet());
        return new ReceiptLookupResponse(List.of(), missing(ids, found), rows.stream().map(this::toReceipt).toList());
    }

    public ReceiptLookupResponse byDecisionIds(String tenantId, List<String> ids) {
        List<AuditLog> rows = AuditLog.findByDecisionIds(tenantId, ids);
        List<Receipt> receipts = rows.stream().filter(r -> EventType.ACTION_GUARD_DECISION.name().equals(r.eventType)).map(this::toReceipt).toList();
        List<Approval> approvals = rows.stream().filter(r -> EventType.ACTION_GUARD_APPROVAL.name().equals(r.eventType)).map(this::toApproval).toList();
        Set<String> found = receipts.stream().map(Receipt::decisionId).collect(Collectors.toSet());
        return new ReceiptLookupResponse(approvals, missing(ids, found), receipts);
    }
    // toReceipt/toApproval：metadata 用 JacksonMappers.DEFAULT 解析为 Map，失败 → Map.of("_raw", raw)
```

`AuditReceiptResource`：`@Path("/api/v1/audit/receipts")`、`@GET @Blocking @RequireRole(Role.MEMBER)`，参数解析：按逗号切分、去空白、去重、保持顺序；两参互斥与上限校验 → `BadRequestException`（映射为 400 JSON，沿用资源包内既有异常映射）。

`InternalCallerFilter.classify`：把 `/api/v1/audit/receipts` 加入与 `evaluate-source` 相同的内部调用者类别，使 C 的 `signInternalCallerHeaders` 可用（Task 8 的客户端走此路径）。确认 `ApiKeyAuthFilter` 对内部调用者（HMAC 已验证）与 Bearer key 两种方式均放行——阅读 `ApiKeyAuthFilter` 对 `InternalCallerFilter` 已验证请求的跳过逻辑；若 Bearer 缺失且无内部签名则 401（保持）。

- [ ] **Step 4: 运行测试通过**

Run（容器）：`… ./gradlew --offline :test --tests 'io.aster.audit.rest.*' --tests 'io.aster.*PublicApiContract*' --tests 'io.aster.*NonDeterminism*' --tests 'io.aster.security.*InternalCaller*'`
Expected: PASS；golden 新增一行 `GET /api/v1/audit/receipts`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/audit src/main/java/io/aster/policy/security/InternalCallerFilter.java src/test/resources/api/public-api-contract.golden src/test/java/io/aster/audit/rest
git commit -m "feat(audit): GET /api/v1/audit/receipts 按相关键/决策 id 返回链收据与审批事件（ADR 0041 §2.4）"
```

---

### Task 6: guard 收据携带结构化模型身份（aster-api）

**Files:**
- Modify: `src/main/java/io/aster/guard/api/GuardActionRequest.java:32`（`Agent(model, session)` → `AgentIdentity`）
- Modify: `src/main/java/io/aster/guard/GuardActionMapper.java:55`（`agent_model` 取 `a.agent().model()`，键序与规则输入不变）
- Modify: `src/main/java/io/aster/guard/GuardDecisionDraft.java`（新增 `AgentIdentity agent` 组件，最后一位）、`GuardDecisionService`（构造 draft 时传 `AgentIdentity.validated(req.action().agent())`，非法 → `GuardInvalidActionException`）
- Modify: `src/main/java/io/aster/guard/GuardReceiptBuilder.java:36-91`
- Test: `src/test/java/io/aster/guard/GuardReceiptBuilderTest.java`、`src/test/java/io/aster/guard/GuardActionMapperTest.java`、`src/test/java/io/aster/guard/api/GuardDecisionResourceTest.java`

**Interfaces:**
- Produces: 收据 metadata 用扁平键 `agentProvider, agentModel, agentVersion, agentSession, agentSource`（`agentModel` 键名不变、值同前）；`SHRINK_ORDER = ["reason", "agentSession", "agentVersion", "principalRoles", "agentModel", "agentProvider", "principalType", "resourceType", "resourceId", "actionName", "principalId"]`；规则输入 Map 键序**不变**（仍只有 `agent_model`，见修正 #9）。旧请求体 `{"agent":{"model":"x","session":"s"}}` 仍可反序列化（`provider` 缺失 → `AgentIdentity.ofModel` 补 `unknown`）。

- [ ] **Step 1: 写失败测试**

`GuardReceiptBuilderTest`：`缩短顺序固定_principalId最后` 改为新顺序；新增 `agent四字段进收据且agentSource为declared`（draft 带 `new AgentIdentity("anthropic","claude","2026","s-1")` → metadata 含五键）；`agentSession先于agentModel被缩短`。`GuardActionMapperTest` L45 键序断言保持通过（不新增键）。`GuardDecisionResourceTest` 新增：请求 `agent: {model:"claude", session:"s"}`（无 provider）→ 200，`GuardDecisionEntity.response_json`/收据 metadata 的 `agentProvider == "unknown"`；`agent.model` 含控制字符 → 400 `invalid_action`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --offline :test --tests 'io.aster.guard.GuardReceiptBuilderTest'`
Expected: FAIL（顺序断言）

- [ ] **Step 3: 实现**

`GuardActionRequest.Action.agent` 类型改为 `io.aster.policy.evidence.AgentIdentity`（Jackson 按字段名反序列化，`provider` 可缺）。`GuardDecisionService` 构造 draft：`AgentIdentity agent = req.action().agent() == null ? null : (req.action().agent().provider() == null ? AgentIdentity.ofModel(req.action().agent().model(), req.action().agent().session()) : AgentIdentity.validated(req.action().agent()))`，捕获 `IllegalArgumentException` 转 `GuardInvalidActionException`。`GuardReceiptBuilder.decisionEvent`：删除 `put(m, "agentModel", clip(draft.agentModel(), MAX_TEXT))`，改为

```java
    if (draft.agent() != null) {
      put(m, "agentProvider", clip(draft.agent().provider(), MAX_TEXT));
      put(m, "agentModel", clip(draft.agent().model(), MAX_TEXT));
      put(m, "agentVersion", clip(draft.agent().version(), MAX_TEXT));
      put(m, "agentSession", clip(draft.agent().session(), MAX_TEXT));
      m.put("agentSource", EvaluationEvidence.SOURCE_DECLARED);
    }
```

`GuardDecisionDraft.agentModel()` 删除（改读 `agent()`），全仓 grep 调用点。

- [ ] **Step 4: 运行测试通过**

Run（容器）：`… ./gradlew --offline :test --tests 'io.aster.guard.*'`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/guard src/test/java/io/aster/guard
git commit -m "feat(guard): 收据携带结构化模型身份，收缩顺序先丢 session/version（ADR 0041 §3）"
```

---

### Task 7: aster-cloud 执行记录可查化与五态决策

**Files:**
- Create: `drizzle/0050_execution_evidence.sql`；Modify: `drizzle/meta/_journal.json`、`src/db/schema.ts:85-95,807-910`
- Modify: `src/services/policy/policy-api.ts:25-33,87-108,442-488`、`src/services/policy/cnl-executor.ts:99-151,155-229,356-390,474-541,580-593`
- Modify: `src/app/api/policies/[id]/execute/route.ts:325-370`、`src/app/api/v1/policies/[id]/execute/route.ts:53-71,240-290`
- Modify: `src/app/api/policies/[id]/logs/route.ts:41-46`、`src/app/[locale]/(dashboard)/policies/[id]/logs/logs-content.tsx:14`、`src/lib/policy-execution-log.ts`（若有 `indeterminateCount` 类统计加两态）
- Test: `src/__tests__/db/execution-evidence-schema.test.ts`（新）、`src/__tests__/services/policy/cnl-executor-verdict.test.ts`（新）、两条执行路由既有测试

**Interfaces:**
- Produces:
  - 迁移：`ALTER TYPE "public"."ExecutionDecision" ADD VALUE IF NOT EXISTS 'require_approval';` / `'escalate'`；`ALTER TABLE "Execution" ADD COLUMN IF NOT EXISTS "outcome" text / "ruleId" text / "controls" jsonb / "agent" jsonb / "evidenceCorrelationId" text`；索引 `Execution_evidenceCorrelationId_idx`。
  - `executionDecisionEnum` 六值；`ExecutionDecision` 类型从 schema 推导（删除 cnl-executor 内重复联合）。
  - `PolicyEvaluateResponse` 增 `decision?: { outcome: string; role?: string; reason?: string }`、`ruleId?: string`、`controls?: string[]`、`evidence?: { correlationId: string }`。
  - `PolicyApiClient.evaluateSource(source, context, options)` 的 `options.agent?: AgentIdentity`（`{provider, model, version?, session?}`），有则放入请求体 `agent`。
  - `ExecutePolicyOptions.agent?: AgentIdentity` → `executeWithAsterEngine` → `evaluateSource`。
  - `PolicyExecutionResult.metadata` 增 `outcome?: 'ALLOW'|'DENY'|'REQUIRE_APPROVAL'|'ESCALATE'`、`ruleId?`、`controls?`、`evidenceCorrelationId?`；`parseApprovalFromResult` 返回增 `outcome?`。
  - `deriveExecutionDecision`：`engineError → 'error'`；`outcome === 'REQUIRE_APPROVAL' → 'require_approval'`；`'ESCALATE' → 'escalate'`；`indeterminate → 'indeterminate'`；否则 `allowed ? 'approved' : 'denied'`。
  - 新导出 `deriveExecutionOutcome(result): string`（`ALLOW/DENY/REQUIRE_APPROVAL/ESCALATE/INDETERMINATE/ERROR`）。
  - 两条执行路由写 `outcome, ruleId, controls, agent, evidenceCorrelationId`；dashboard 的 agent 固定 `{provider:'aster-cloud', model:'dashboard', source:'declared'}`；v1 路由从请求体 `agent` 读（非法形状 → 400 `{ error: 'Invalid agent' }`），写库时加 `source:'declared'`。

- [ ] **Step 1: 写失败测试**

```ts
// execution-evidence-schema.test.ts
it('ExecutionDecision 六值且 Execution 有证据列', () => {
  expect(executionDecisionEnum.enumValues).toEqual(['approved','denied','indeterminate','error','require_approval','escalate']);
  const cols = getTableColumns(executions);
  for (const c of ['outcome','ruleId','controls','agent','evidenceCorrelationId']) expect(cols[c as keyof typeof cols]).toBeDefined();
  const journal = JSON.parse(readFileSync('drizzle/meta/_journal.json','utf8'));
  expect(journal.entries.at(-1).tag).toBe('0050_execution_evidence');
  const sql = readFileSync('drizzle/0050_execution_evidence.sql','utf8');
  expect(sql).toContain(`ALTER TYPE "public"."ExecutionDecision" ADD VALUE IF NOT EXISTS 'require_approval'`);
});

// cnl-executor-verdict.test.ts
it('REQUIRE_APPROVAL 派生 require_approval 并保留 outcome/ruleId/controls/evidence', () => {
  const resp = { result: { __type:'Verdict', outcome:'REQUIRE_APPROVAL', role:'DPO', reason:'r' }, executionTimeMs: 3, error: null,
    decision: { outcome:'REQUIRE_APPROVAL', role:'DPO', reason:'r' }, ruleId:'CUST-DEL-001', controls:['GDPR:ART17'], evidence:{ correlationId:'c-1' } };
  const r = buildCNLResult(policy, resp as PolicyEvaluateResponse);
  expect(r.metadata.outcome).toBe('REQUIRE_APPROVAL'); expect(r.metadata.ruleId).toBe('CUST-DEL-001');
  expect(r.metadata.controls).toEqual(['GDPR:ART17']); expect(r.metadata.evidenceCorrelationId).toBe('c-1');
  expect(r.allowed).toBe(false); expect(r.metadata.decision).toBeUndefined();
  expect(deriveExecutionDecision(r)).toBe('require_approval'); expect(deriveExecutionOutcome(r)).toBe('REQUIRE_APPROVAL');
});
it('ESCALATE → escalate；非 Verdict 非布尔 → indeterminate；engineError → error', () => {
  const esc = buildCNLResult(policy, { result: { __type:'Verdict', outcome:'ESCALATE' }, executionTimeMs: 1, error: null } as PolicyEvaluateResponse);
  expect(deriveExecutionDecision(esc)).toBe('escalate'); expect(deriveExecutionOutcome(esc)).toBe('ESCALATE');
  const vague = buildCNLResult(policy, { result: { tier: '转人工审核' }, executionTimeMs: 1, error: null } as PolicyEvaluateResponse);
  expect(deriveExecutionDecision(vague)).toBe('indeterminate'); expect(deriveExecutionOutcome(vague)).toBe('INDETERMINATE');
  const err = { ...vague, metadata: { ...vague.metadata, engineError: true } };
  expect(deriveExecutionDecision(err)).toBe('error'); expect(deriveExecutionOutcome(err)).toBe('ERROR');
  const allow = buildCNLResult(policy, { result: { __type:'Verdict', outcome:'ALLOW' }, executionTimeMs: 1, error: null } as PolicyEvaluateResponse);
  expect(deriveExecutionDecision(allow)).toBe('approved'); expect(allow.allowed).toBe(true);
});
it('evaluateSource 透传 agent 到请求体，无 agent 时不带键', async () => {
  const calls: Array<{ body: Record<string, unknown> }> = [];
  global.fetch = vi.fn(async (_url, init) => { calls.push({ body: JSON.parse(String(init?.body)) }); return new Response(JSON.stringify({ result: 1, executionTimeMs: 1, error: null }), { status: 200 }); }) as never;
  const client = new PolicyApiClient('t1', 'u1');
  await client.evaluateSource('Module M.', {}, { agent: { provider: 'anthropic', model: 'claude', session: 's' } });
  await client.evaluateSource('Module M.', {});
  expect(calls[0].body.agent).toEqual({ provider: 'anthropic', model: 'claude', session: 's' });
  expect(calls[1].body).not.toHaveProperty('agent');
});
```

（`buildCNLResult` 目前未导出：导出它。两条路由测试：mock `executePolicyUnified` 返回带 metadata.outcome 的结果，断言 `db.insert(executions).values` 收到 `outcome:'REQUIRE_APPROVAL', decision:'require_approval', ruleId, controls, agent:{provider:'aster-cloud',model:'dashboard',source:'declared'}, evidenceCorrelationId`；v1 路由带 `agent:{provider:'anthropic',model:'claude'}` 透传且落库带 `source:'declared'`，`agent: 'x'` → 400。）

- [ ] **Step 2: 运行确认失败**

Run: `pnpm exec vitest run --project saas src/__tests__/db/execution-evidence-schema.test.ts src/__tests__/services/policy/cnl-executor-verdict.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现**

迁移 `0050_execution_evidence.sql`：

```sql
-- 证据链补齐（ADR 0041 §4）：决策五态与可查证据列；旧行不回填（证据包标 legacy）。
-- ★ADD VALUE 必须单独成语句且不能在同一事务内被使用；本文件只加值不用值。
ALTER TYPE "public"."ExecutionDecision" ADD VALUE IF NOT EXISTS 'require_approval';
--> statement-breakpoint
ALTER TYPE "public"."ExecutionDecision" ADD VALUE IF NOT EXISTS 'escalate';
--> statement-breakpoint
ALTER TABLE "Execution" ADD COLUMN IF NOT EXISTS "outcome" text;
--> statement-breakpoint
ALTER TABLE "Execution" ADD COLUMN IF NOT EXISTS "ruleId" text;
--> statement-breakpoint
ALTER TABLE "Execution" ADD COLUMN IF NOT EXISTS "controls" jsonb;
--> statement-breakpoint
ALTER TABLE "Execution" ADD COLUMN IF NOT EXISTS "agent" jsonb;
--> statement-breakpoint
ALTER TABLE "Execution" ADD COLUMN IF NOT EXISTS "evidenceCorrelationId" text;
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS "Execution_evidenceCorrelationId_idx" ON "Execution" USING btree ("evidenceCorrelationId");
```

`parseApprovalFromResult` 的 Verdict 分支返回 `outcome: obj.outcome`（四态），REQUIRE_APPROVAL/ESCALATE 不再设 `indeterminate`（改为 `{ approved:false, outcome, message: reason }`）；`buildCNLResult` 把 `apiResponse.decision?.outcome ?? parsed.outcome`、`ruleId`、`controls`、`evidence?.correlationId` 写进 `metadata`；`deriveExecutionDecision`/`deriveExecutionOutcome` 如接口所述。`ExecutionDecision` 类型改为 `(typeof executionDecisionEnum.enumValues)[number]`。`logs/route.ts` 白名单与 `logs-content.tsx` 联合加两值；`policy-execution-log.ts` 若按态计数，加 `requireApprovalCount/escalateCount`。`agent` 校验（v1 路由）：对象且 `provider`、`model` 为非空字符串、可选 `version`/`session` 为字符串、每项 ≤ 255。

- [ ] **Step 4: 运行测试通过**

Run: `pnpm exec vitest run --project saas src/__tests__/db src/__tests__/services/policy src/__tests__/api && pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint`
Expected: PASS。另在本地栈的 scratch 库上实跑 `0050`（照 Task 1 ADR 0015 的 scratch 重放法）确认 `ADD VALUE IF NOT EXISTS` 在 drizzle migrate 事务内可执行。

- [ ] **Step 5: 提交**

```bash
git add drizzle src/db/schema.ts src/services/policy src/app/api/policies src/app/api/v1/policies src/app/\[locale\] src/lib/policy-execution-log.ts src/__tests__
git commit -m "feat(executions): 决策五态、证据列与 agent 透传（ADR 0041 §4）"
```

---

### Task 8: 收据客户端与复核者拼装（aster-cloud）

**Files:**
- Create: `src/services/evidence/receipts-client.ts`、`src/services/evidence/reviewers.ts`
- Test: `src/__tests__/services/evidence/receipts-client.test.ts`、`src/__tests__/services/evidence/reviewers.test.ts`

**Interfaces:**
- Produces:

```ts
// receipts-client.ts
export type ChainReceipt = { auditId: number; currentHash: string; prevHash: string | null; hashVersion: number; eventType: string; timestamp: string; metadata: Record<string, unknown> };
export type ChainApproval = { auditId: number; decisionId: string; outcome: string; decidedBy: string; requiredRole: string | null; comment: string | null; decidedAt: string; currentHash: string; decisionReceiptHash: string | null };
export type ReceiptLookup = { receipts: Map<string, ChainReceipt>; approvals: Map<string, ChainApproval[]>; missing: Set<string>; unavailable: Set<string> };
export const RECEIPT_BATCH = 200; export const RECEIPT_TIMEOUT_MS = 2000; export const RECEIPT_CONCURRENCY = 4;
export async function fetchReceipts(tenantId: string, correlationIds: string[], fetchImpl?: typeof fetch): Promise<ReceiptLookup>;
export async function fetchDecisionReceipts(tenantId: string, decisionIds: string[], fetchImpl?: typeof fetch): Promise<ReceiptLookup>;
```

  - 实现：按 200 分批，`Promise.all` 以 4 并发；每批 `AbortSignal.timeout(2000)`；URL `${ASTER_POLICY_API_INTERNAL_URL}/api/v1/audit/receipts?correlationIds=…`；头 `X-Tenant-Id/X-User-Id/X-User-Role` + `signInternalCallerHeaders('GET', pathname, '', tenantId, 'member')`（复用 `src/lib/api-signing.ts`）；非 2xx、超时、网络错 ⇒ 该批 id 全部进 `unavailable`；不重试。
  
```ts
// reviewers.ts
export type Reviewer = { userId: string; role: string; source: 'guard-approval' | 'policy-proof' | 'version-approval'; outcome: string; decidedAt: string; ref: string; roleVerified: boolean };
export async function loadProofReviewers(policyVersionRowIds: string[]): Promise<Map<string, Reviewer[]>>;    // key = policyVersionRowId；每 (policyId,nodeId) 取最新 proof；role = subjectKind；outcome = verdict；roleVerified = true（PolicyReviewer 授权是经验证的）
export async function loadVersionApprovalReviewers(policyVersionRowIds: string[]): Promise<Map<string, Reviewer[]>>; // policyApprovals.versionId；role = 'approver'；outcome = decision；roleVerified = true
export function guardApprovalReviewers(approvals: ChainApproval[]): Reviewer[];                              // role = requiredRole ?? 'unknown'；outcome；roleVerified = false（自报头，3b 改 true）
```

- [ ] **Step 1: 写失败测试**

`receipts-client.test.ts`：mock `fetch`：(a) 450 个 id → 3 次调用、每次 ≤ 200、并发 ≤ 4（计数在途）；(b) 一批 503 → 该批 id 全 `unavailable`、其余正常；(c) 超时（fetch 永不 resolve + 假定时器）→ `unavailable`；(d) 响应 `missing` 归入 `missing`；(e) 请求头含 `X-Aster-Signature`（mock `@/lib/api-signing`）。
`reviewers.test.ts`：mock `db.query.policyProofs.findMany` 返回同 node 两条（旧 REJECTED、新 VERIFIED）→ 只取最新；`policyApprovals` 两条 → 两个 reviewer；`guardApprovalReviewers` 映射字段与 `roleVerified:false`。

- [ ] **Step 2: 运行确认失败**

Run: `pnpm exec vitest run --project saas src/__tests__/services/evidence/receipts-client.test.ts src/__tests__/services/evidence/reviewers.test.ts`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现**

```ts
// src/services/evidence/receipts-client.ts
/** 向 aster-api 批量取链收据（ADR 0041 §2.4）：分批 200、并发 4、每批 2 s；失败整批记 unavailable，不重试、不伪造。 */
import { signInternalCallerHeaders } from '@/lib/api-signing';
import { getApiConfig } from '@/services/policy/policy-api';
export const RECEIPT_BATCH = 200; export const RECEIPT_TIMEOUT_MS = 2000; export const RECEIPT_CONCURRENCY = 4;
const PATH = '/api/v1/audit/receipts';

export async function fetchReceipts(tenantId: string, correlationIds: string[], fetchImpl: typeof fetch = fetch): Promise<ReceiptLookup> {
  return lookup(tenantId, 'correlationIds', correlationIds, fetchImpl);
}
export async function fetchDecisionReceipts(tenantId: string, decisionIds: string[], fetchImpl: typeof fetch = fetch): Promise<ReceiptLookup> {
  return lookup(tenantId, 'decisionIds', decisionIds, fetchImpl);
}

async function lookup(tenantId: string, param: 'correlationIds' | 'decisionIds', ids: string[], fetchImpl: typeof fetch): Promise<ReceiptLookup> {
  const out: ReceiptLookup = { receipts: new Map(), approvals: new Map(), missing: new Set(), unavailable: new Set() };
  const unique = [...new Set(ids.filter(Boolean))];
  const batches: string[][] = [];
  for (let i = 0; i < unique.length; i += RECEIPT_BATCH) batches.push(unique.slice(i, i + RECEIPT_BATCH));
  await runLimited(batches.map((batch) => () => fetchBatch(tenantId, param, batch, fetchImpl, out)), RECEIPT_CONCURRENCY);
  return out;
}

async function fetchBatch(tenantId: string, param: string, batch: string[], fetchImpl: typeof fetch, out: ReceiptLookup): Promise<void> {
  const key = param === 'correlationIds' ? 'correlationId' : 'decisionId';
  try {
    const base = getApiConfig().baseUrl;
    const headers = { 'X-Tenant-Id': tenantId, 'X-User-Id': 'evidence-export', 'X-User-Role': 'member',
      ...(process.env.ASTER_PLAN_GATE_HMAC_KEY ? await signInternalCallerHeaders('GET', PATH, '', tenantId, 'member') : {}) };
    const res = await fetchImpl(`${base}${PATH}?${param}=${encodeURIComponent(batch.join(','))}`, { headers, signal: AbortSignal.timeout(RECEIPT_TIMEOUT_MS) });
    if (!res.ok) { batch.forEach((id) => out.unavailable.add(id)); return; }
    const body = (await res.json()) as { receipts?: ChainReceipt[]; approvals?: ChainApproval[]; missing?: string[] };
    for (const r of body.receipts ?? []) out.receipts.set(String((r as unknown as Record<string, unknown>)[key]), r);
    for (const a of body.approvals ?? []) out.approvals.set(a.decisionId, [...(out.approvals.get(a.decisionId) ?? []), a]);
    for (const m of body.missing ?? []) out.missing.add(m);
  } catch {
    batch.forEach((id) => out.unavailable.add(id));
  }
}

/** 有界并发：同一时刻最多 limit 个任务在跑；任务自身永不抛错。 */
async function runLimited(tasks: Array<() => Promise<void>>, limit: number): Promise<void> {
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, tasks.length) }, async () => {
    while (next < tasks.length) { const t = tasks[next++]; await t(); }
  });
  await Promise.all(workers);
}
```

```ts
// src/services/evidence/reviewers.ts
/** 统一复核者形状（ADR 0041 §5.2）：三来源按 source 标注；guard 角色为自报头故 roleVerified=false。 */
import { db, policyProofs, policyApprovals } from '@/lib/prisma';
import { inArray } from 'drizzle-orm';
import type { ChainApproval } from './receipts-client';

export async function loadProofReviewers(policyVersionRowIds: string[]): Promise<Map<string, Reviewer[]>> {
  const out = new Map<string, Reviewer[]>();
  if (policyVersionRowIds.length === 0) return out;
  const rows = await db.query.policyProofs.findMany({
    where: inArray(policyProofs.policyVersionId, policyVersionRowIds),
    columns: { id: true, policyId: true, policyVersionId: true, nodeId: true, verdict: true, subjectKind: true, subjectUserId: true, createdAt: true },
  });
  // 追加覆盖：每 (policyId, nodeId) 只取 createdAt 最新的一条
  const latest = new Map<string, (typeof rows)[number]>();
  for (const r of rows) {
    const k = `${r.policyId}\u0000${r.nodeId}`;
    const cur = latest.get(k);
    if (!cur || r.createdAt > cur.createdAt) latest.set(k, r);
  }
  for (const r of latest.values()) {
    const list = out.get(r.policyVersionId!) ?? [];
    list.push({ userId: r.subjectUserId, role: r.subjectKind, source: 'policy-proof', outcome: r.verdict, decidedAt: r.createdAt.toISOString(), ref: r.id, roleVerified: true });
    out.set(r.policyVersionId!, list);
  }
  return out;
}

export async function loadVersionApprovalReviewers(policyVersionRowIds: string[]): Promise<Map<string, Reviewer[]>> {
  const out = new Map<string, Reviewer[]>();
  if (policyVersionRowIds.length === 0) return out;
  const rows = await db.query.policyApprovals.findMany({
    where: inArray(policyApprovals.versionId, policyVersionRowIds),
    columns: { id: true, versionId: true, approverId: true, decision: true, createdAt: true },
  });
  for (const r of rows) {
    const list = out.get(r.versionId) ?? [];
    list.push({ userId: r.approverId, role: 'approver', source: 'version-approval', outcome: r.decision, decidedAt: r.createdAt.toISOString(), ref: r.id, roleVerified: true });
    out.set(r.versionId, list);
  }
  return out;
}

export function guardApprovalReviewers(approvals: ChainApproval[]): Reviewer[] {
  return approvals.map((a) => ({ userId: a.decidedBy, role: a.requiredRole ?? 'unknown', source: 'guard-approval', outcome: a.outcome,
    decidedAt: a.decidedAt, ref: String(a.auditId), roleVerified: false }));
}
```

（`getApiConfig` 若未导出则导出它；`policyApprovals.versionId` 与 `executions.policyVersionRowId` 是否同一 id 空间由实施者核实——若不同，按 `policyVersions` 表做一次映射并在报告写明。）

- [ ] **Step 4: 运行测试通过**

Run: 同上 `&& pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/services/evidence/receipts-client.ts src/services/evidence/reviewers.ts src/__tests__/services/evidence
git commit -m "feat(evidence): 链收据客户端（分批/超时/unavailable）与统一复核者拼装（ADR 0041 §2.4/§5.2）"
```

---

### Task 9: 证据包 v2（aster-cloud）

**Files:**
- Modify: `src/services/evidence/types.ts`、`src/services/evidence/bundle.ts`、`src/lib/evidence-export.ts:65-120,148-162`、`src/lib/evidence.ts:35-120`
- Modify: `src/app/[locale]/(dashboard)/reports/reports-content.tsx`（`DECISION_KEYS` 加 `require_approval`、`escalate`，`decisionVariant` 两者 `warning`）、`src/i18n/demo-supplement.ts`（`reports.decision.require_approval/escalate` 四语）
- Test: `src/__tests__/services/evidence/bundle.test.ts`、`src/__tests__/lib/evidence-v2.test.ts`（新，mock 查询 + fetch）、`src/__tests__/integration/evidence-export.integration.test.ts`（`setup-postgres.ts` 手建表若有则加新列）

**Interfaces:**
- Produces:

```ts
export type EvidenceDecision = 'approved'|'denied'|'indeterminate'|'error'|'require_approval'|'escalate';
export interface DecisionTally { approved; denied; indeterminate; error; require_approval; escalate; unknown }
export type ReceiptRef = { auditId: number; currentHash: string; prevHash: string | null; hashVersion: number } | { status: 'missing' | 'legacy' | 'unavailable' };
export interface EvidenceEntry { …既有…; outcome: string | null; ruleId: string | null; controls: string[] | null;
  agent: { provider: string; model: string; version?: string; session?: string; source: 'declared' } | null;
  evidenceCorrelationId: string | null; receipt: ReceiptRef; reviewers: Reviewer[] }
export interface EvidenceManifest { …; schemaVersion: '2'; receiptSource: { kind: 'aster-api-hash-chain'; verifier: 'GET /api/v1/audit/receipts' };
  agentTally: Record<string, number>; /* key = `${provider}/${model}` */ reviewerTally: Record<Reviewer['source'], number>; legacyEntries: number;
  notes: { legacyRowsWithoutHashes: number; receiptsUnavailable: number; receiptsMissing: number; verification: string } }
```

  - `EvidenceRow` 增 `outcome, ruleId, controls, agent, evidenceCorrelationId, policyTenantId`（查询层 join `policies` 取 `teamId ?? userId`）与 `guardDecisionId`（`metadata.guardDecisionId`，无则 null）。
  - `buildEvidenceEntry(row, receipt: ReceiptRef, reviewers: Reviewer[])`；`receipt` 规则：`evidenceCorrelationId == null` → `legacy`；在 `unavailable` 集 → `unavailable`；在 `missing` 集或未命中 → `missing`；否则引用。
  - `createEvidenceExport`：rows → 按 `policyTenantId` 分组调 `fetchReceipts`；对 `guardDecisionId` 非空的行调 `fetchDecisionReceipts` 取审批；`loadProofReviewers`/`loadVersionApprovalReviewers` 按 `policyVersionRowId` 批量；用 `buildEvidenceEntry` 替换 L77-96 的内联复制；`verification` 文案：`'bundleHash = canonicalHash(entries sorted by [createdAt, executionId]) over schemaVersion 2 entries (includes outcome/ruleId/controls/agent/receipt/reviewers); receipts verifiable via GET /api/v1/audit/receipts; v1 bundles use their own recipe.'`
  - `getEvidenceExportBundle` 不变（按存储原样返回，v1 可下载）；`getEvidencePreview` 的 tally 字面量改用 `EMPTY_TALLY`（导出它）。

- [ ] **Step 1: 写失败测试**

`bundle.test.ts`：`row()` 助手补新字段；`buildEvidenceEntry(row, {status:'legacy'}, [])` 输出含 `receipt/reviewers/agent/outcome`；`computeBundleHash` 对同数据确定且加字段后与 v1 不同；`buildManifest` 的 `schemaVersion==='2'`、`agentTally`、`reviewerTally`、`legacyEntries`、`notes.receiptsUnavailable`。
`evidence-v2.test.ts`：mock `queryEvidenceExecutions`（3 行：正常 / `evidenceCorrelationId:null` legacy / 带 guardDecisionId）、mock `fetchReceipts`（第 1 行命中，第 3 行 unavailable）、`fetchDecisionReceipts`（返回一条 APPROVED）、`loadProofReviewers`（第 1 行版本有 VERIFIED proof）→ 断言三条目的 `receipt.status`/引用、`reviewers` 来源组合、manifest 计数；A 全部 unavailable 时导出仍 `completed`。

- [ ] **Step 2: 运行确认失败**

Run: `pnpm exec vitest run --project saas src/__tests__/services/evidence src/__tests__/lib/evidence-v2.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现**

bundle.ts 新增/修改的纯函数：

```ts
export const EMPTY_TALLY: DecisionTally = { approved: 0, denied: 0, indeterminate: 0, error: 0, require_approval: 0, escalate: 0, unknown: 0 };

export function receiptFor(row: Pick<EvidenceRow, 'evidenceCorrelationId'>, lookup: ReceiptLookup): ReceiptRef {
  const cid = row.evidenceCorrelationId;
  if (cid == null) return { status: 'legacy' };
  if (lookup.unavailable.has(cid)) return { status: 'unavailable' };
  const r = lookup.receipts.get(cid);
  if (!r) return { status: 'missing' };
  return { auditId: r.auditId, currentHash: r.currentHash, prevHash: r.prevHash, hashVersion: r.hashVersion };
}

export function buildEvidenceEntry(row: EvidenceRow, receipt: ReceiptRef, reviewers: Reviewer[]): EvidenceEntry {
  return { ...既有 14 个字段…, outcome: row.outcome, ruleId: row.ruleId, controls: row.controls ?? null, agent: row.agent ?? null,
    evidenceCorrelationId: row.evidenceCorrelationId, receipt, reviewers: [...reviewers].sort((a, b) => a.decidedAt.localeCompare(b.decidedAt) || a.ref.localeCompare(b.ref)) };
}

export function tallyAgents(entries: readonly EvidenceEntry[]): Record<string, number> {
  const t: Record<string, number> = {};
  for (const e of entries) { const k = e.agent ? `${e.agent.provider}/${e.agent.model}` : 'unknown'; t[k] = (t[k] ?? 0) + 1; }
  return t;
}
export function tallyReviewers(entries: readonly EvidenceEntry[]): Record<Reviewer['source'], number> {
  const t = { 'guard-approval': 0, 'policy-proof': 0, 'version-approval': 0 };
  for (const e of entries) for (const r of e.reviewers) t[r.source] += 1;
  return t;
}
```

`buildManifest`：`schemaVersion: '2'`、`receiptSource`、`agentTally: tallyAgents(sorted)`、`reviewerTally: tallyReviewers(sorted)`、`legacyEntries = sorted.filter(e => 'status' in e.receipt && e.receipt.status === 'legacy').length`、`notes.receiptsUnavailable/receiptsMissing` 同法计数。

`createEvidenceExport` 的拼装段：

```ts
    const rows = await queryEvidenceExecutions({ … });
    const byTenant = new Map<string, string[]>();
    for (const r of rows) if (r.evidenceCorrelationId) byTenant.set(r.policyTenantId, [...(byTenant.get(r.policyTenantId) ?? []), r.evidenceCorrelationId]);
    const lookups = await Promise.all([...byTenant].map(([tenant, ids]) => fetchReceipts(tenant, ids)));
    const lookup = mergeLookups(lookups);  // 合并三个 Map/Set（小助手，放 receipts-client.ts 导出）
    const guardRows = rows.filter((r) => r.guardDecisionId);
    const guardLookup = mergeLookups(await Promise.all([...groupBy(guardRows, (r) => r.policyTenantId)].map(([tenant, rs]) => fetchDecisionReceipts(tenant, rs.map((r) => r.guardDecisionId!)))));
    const versionIds = [...new Set(rows.map((r) => r.policyVersionRowId).filter((v): v is string => !!v))];
    const [proofs, approvals] = await Promise.all([loadProofReviewers(versionIds), loadVersionApprovalReviewers(versionIds)]);
    const entries = rows.map((r) => buildEvidenceEntry(r, receiptFor(r, lookup), [
      ...(r.policyVersionRowId ? [...(proofs.get(r.policyVersionRowId) ?? []), ...(approvals.get(r.policyVersionRowId) ?? [])] : []),
      ...(r.guardDecisionId ? guardApprovalReviewers(guardLookup.approvals.get(r.guardDecisionId) ?? []) : []),
    ]));
    const bundle = buildBundle({ policy: policySnapshot, range: …, entries, generatedAt: now });
```

四语文案（`src/i18n/demo-supplement.ts` 的 `reports.decision` 子树）：`require_approval: 'Needs approval' / '需人工批准' / 'Freigabe nötig' / 'अनुमोदन आवश्यक'`，`escalate: 'Escalated' / '已升级' / 'Eskaliert' / 'एस्केलेट किया गया'`。`reports-content.tsx` 的 `DECISION_KEYS` 与 `decisionVariant`（两者 `'warning'`）、`Preview.decisionTally` 接口同步。`queryEvidenceExecutions` 的 `columns` 增 `outcome, ruleId, controls, agent, evidenceCorrelationId, metadata`，并 `with: { policy: { columns: { teamId: true, userId: true } } }`（或等价 join）得 `policyTenantId = policy.teamId ?? policy.userId`、`guardDecisionId = (metadata as any)?.guardDecisionId ?? null`。

- [ ] **Step 4: 运行测试通过**

Run: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm test:run 2>&1 | tail -6 && pnpm check:locales:strict | tail -2`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/services/evidence src/lib/evidence.ts src/lib/evidence-export.ts src/app/\[locale\]/\(dashboard\)/reports src/i18n/demo-supplement.ts src/__tests__
git commit -m "feat(evidence): 证据包 v2——链收据、统一复核者、模型身份与五态统计（ADR 0041 §5）"
```

---

### Task 10: 全栈验收与 ADR 定稿

**Files:**
- Modify: `aster-api/docs/adr/0041-evidence-chain-enrichment.md`（状态 → ACCEPTED；按「对 ADR 的修正」表回写 §2.2、§2.4、§3、§4、§5.2、§7、§9；§9 追加「验收结果」）

- [ ] **Step 1: 重建并重启**（栈已运行；env 在 `/private/tmp/aster-stack/env`）

```bash
export PATH=/opt/homebrew/opt/node@24/bin:/opt/podman/bin:$PATH; . /private/tmp/aster-stack/env
cd /Users/rpang/IdeaProjects/aster-api && git checkout feat/adr-0041-evidence-chain && ./gradlew --offline quarkusBuild -x test -q && podman build --platform linux/arm64 -f Dockerfile.jvm -t aster/policy-api:stack . >/dev/null
podman rm -f aster-api && podman run -d --name aster-api --network aster-stack -p $API_PORT:8080 -e DB_JDBC_URL=jdbc:postgresql://aster-pg:5432/aster_policy -e DB_USERNAME=postgres -e DB_PASSWORD=postgres -e QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://aster-pg:5432/aster_policy -e QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://aster-pg:5432/aster_policy -e QUARKUS_REDIS_HOSTS=redis://aster-redis:6379 -e ASTER_SECURITY_SIGNATURE_ENABLED=false -e ASTER_CLOUD_INTERNAL_URL=http://aster-cloud:3000 -e ASTER_PLAN_GATE_HMAC_KEY=$HMAC_KEY -e ASTER_PLAN_GATE_ENABLED=true -e JAVA_OPTS="-Xmx1g -Xms256m" localhost/aster/policy-api:stack
cd /Users/rpang/IdeaProjects/aster-cloud && git checkout feat/adr-0041-execution-evidence && podman restart aster-cloud   # 迁移 0050 自动执行
until curl -sf http://localhost:$API_PORT/q/health >/dev/null; do sleep 2; done; until podman logs aster-cloud 2>&1 | grep -q 'Ready in'; do sleep 3; done
```

- [ ] **Step 2: 验收 1 —— `/evaluate` 带 agent，收据可查**

```bash
R=$(curl -s -X POST http://localhost:$API_PORT/api/v1/policies/evaluate -H "Authorization: Bearer $KEY1" -H 'content-type: application/json' -d '{"policyModule":"guard.customer","policyFunction":"decide","context":[{"principal_id":"acc-1","principal_type":"agent","principal_roles":[],"action":"delete_customer_record","resource_type":"","resource_id":"","context":{},"agent_model":""}],"agent":{"provider":"anthropic","model":"claude-fable-5-1","session":"s-acc"}}')
CID=$(echo "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["evidence"]["correlationId"])'); sleep 2
curl -s "http://localhost:$API_PORT/api/v1/audit/receipts?correlationIds=$CID" -H "Authorization: Bearer $KEY1" | python3 -m json.tool | head -30
podman exec aster-pg psql -U postgres -d aster_policy -Atc "select correlation_id, current_hash, metadata from audit_logs where correlation_id='$CID'"
```
Expected: 响应 `evidence.correlationId`；收据 `currentHash` 与表中 `current_hash` 相等；metadata 含 `ruleId=CUST-DEL-001`、`outcome=REQUIRE_APPROVAL`、`agentProvider=anthropic`、`agentSource=declared`。

- [ ] **Step 3: 验收 2 —— C dashboard 执行 + 证据包 v2**

在 aster-cloud 本地栈（http://localhost:$CLOUD_PORT）用种子用户 `t1`（SQL 建一个 `Policy`，内容为示例策略，`userId='t1'`）通过 `POST /api/v1/policies/{id}/execute`（KEY1，body `{input:{…delete_customer_record…}, agent:{provider:'anthropic',model:'claude'}}`）执行两次；SQL 写一条该版本的 `PolicyProof`（VERIFIED，subjectUserId='u3'，subjectKind='domain_expert'，先插 `PolicyReviewer` 授权行）；调用 `POST /api/reports`（需会话；若无会话用 SQL 调用 `createEvidenceExport` 的等价脚本 `tsx` 在容器内执行）导出 v2；下载并检查：条目 `outcome=REQUIRE_APPROVAL`、`ruleId`、`controls`、`agent.provider=anthropic`、`receipt.currentHash` 与 A 表一致、`reviewers` 含 `policy-proof`（u3）；`manifest.schemaVersion='2'`。
Expected: 全部满足。

- [ ] **Step 4: 验收 3 —— guard 审批进 reviewers**

用 KEY_FREE/KEY_DPO 跑一次 `e2e-local.mjs` 得到 decisionId；SQL 把该 decisionId 写入某条 `executions.metadata = {"guardDecisionId": "<id>"}`；重新导出 → 该条目 `reviewers` 含 `{source:'guard-approval', userId:'m-dpo', outcome:'APPROVED', roleVerified:false}`。

- [ ] **Step 5: 验收 4 —— legacy 与 unavailable**

迁移前的旧执行行导出 `receipt.status='legacy'`；`podman stop aster-api` 后导出 → 新条目 `unavailable`、`notes.receiptsUnavailable` ≥ 1、导出 `completed`；`podman start aster-api`。

- [ ] **Step 6: ADR 定稿与提交**

ADR 0041 状态改 `ACCEPTED（2026-10-08 本地全栈验收通过）`，按修正表回写，§9 追加四项实测输出。提交 `docs(adr): ADR 0041 定稿——代码事实修正与本地全栈验收结果`。

---

## 对 ADR 的修正（执行前已裁决）

| ADR 原文 | 代码事实 | 处理 |
|---|---|---|
| §2.2/§3 `EvaluationRequest` 增 `agent` | `/evaluate-json` 用 `JsonPolicyRequest`，`/evaluate-source` 用 `SourcePolicyRequest`；C 走的是 `evaluateSource` | 三个请求记录都加 `agent`；C 侧改 `evaluateSource(options.agent)`（Task 2/7） |
| §2.2 `policyVersionId`（仅 evaluate-source 为 null） | 三端点调用点都拿不到版本 id（`PolicyEvaluationResult` 无版本） | 本期 `/evaluate*` 事件不写 `policyVersionId`；guard 收据保留；版本回填列入 §10 后续 |
| §2.4 C 用 API key 调收据接口 | C 的 `PolicyApiClient` 只发 HMAC 内部调用者头，无 Bearer | 接口同时接受 Bearer 与 HMAC 内部调用者（`InternalCallerFilter.classify` 加路径），角色 `MEMBER`（Task 5/8） |
| §2.2 把 `correlationId` 加豁免白名单 | 规范 UUID 值已整串豁免 | 仍加入白名单（防未来非 UUID 值），测试用非 UUID 值证明（Task 4） |
| §3 收据 metadata 嵌套 `agent` 对象 | 追加器脱敏与 guard 预算收缩只处理顶层 String/List | 改为扁平键 `agentProvider/agentModel/agentVersion/agentSession/agentSource`（Task 1/6） |
| §2.4 `metadata::jsonb` 表达式索引 | metadata 超限时是非法 JSON，表达式索引会让插入失败 | 改为冗余列 `correlation_id`/`decision_id` + 部分索引（Task 4） |
| §2.2 两个发布者都经 `EvaluationEvidence` | `AuditLogger.logPolicyEvaluation` 在主代码无调用方 | 只改 `PolicyAuditPublisher`；`AuditLogger` 不动 |
| §7 simulate 模式 | simulate 不发事件 | simulate 不输出 `evidence`（Task 3） |
| §3 guard 扁平化四个 agent 字段给规则 | 规则输入 Map 的键序被 `GuardActionMapperTest` 钉死且部署规则声明固定字段 | 规则输入不变（仍只有 `agent_model`），结构化身份只进收据（Task 6） |
| §5.2 PolicyProof「有效结论」 | proof 按 (policyId, nodeId) 追加覆盖 | 取每 node 最新一条；`Reviewer` 增 `outcome` 字段；版本审批 role 固定 `approver`（Task 8） |
| §5.1 `EvidenceEntry.reviewers` 无核验标记 | guard 角色是自报头 | `Reviewer.roleVerified`：proof/approval 为 true，guard 为 false（3b 改） |
| §5.4 超时 2 s/批、无并发说明 | 50 000 行上限 → 250 批 | 并发 4、每批 2 s、不重试（Task 8） |
| §4 C 的 `evaluate(...)` | 实际路径 `evaluateSource` | 同第一行 |
| §5.2 guard 与 C 执行的关联 | `executions.metadata` 无写入方 | 本期只读 `metadata.guardDecisionId`（SQL 模拟），3b 接线 |

## 本地验证总顺序

1. aster-api：纯 JUnit 定向 → 带容器 `io.aster.policy.rest.* / io.aster.audit.* / io.aster.guard.*` → 全量 `:replay:test :test`。
2. aster-cloud：`tsc && lint && test:run && check:locales:strict`。
3. Task 10 的 podman 全栈四项验收。
