# Action Guard 实施计划（ADR 0040 · 子项目 2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI Agent 的每个提议动作经 `POST /api/v1/guard/decisions` 由已部署的 Verdict 规则判定为 ALLOW / DENY / PENDING，决策同步写入审计哈希链形成收据，需人工批准的动作进入待审批表并可被批准 / 拒绝 / 过期，并提供 `aster-guard` 包（`GuardClient` SDK + 包裹式 MCP 代理）。

**Architecture:** aster-api 新增 `io.aster.guard` 包：请求 DTO 与 Action 扁平化、`GuardDecisionService`（评估 → Verdict → 版本信息 → 同步收据 → 持久化 → 待审批）、两个 REST 资源、两个 Panache 实体、一条 Flyway 迁移、过期调度器；审计模块抽出 `AuditChainAppender` 同步追加路径（异步监听器改为调用它）。新仓 `aster-guard`（TypeScript，ESM，Node ≥ 24）提供 `GuardClient` 与 `aster-guard-mcp` 可执行文件，代理用 MCP SDK 的低层 `Server` 透传上游工具列表并拦截 `tools/call`。

**Tech Stack:** Quarkus 3 / RESTEasy Reactive / Hibernate Panache / Flyway / PostgreSQL（aster-api）；TypeScript 5 / `@modelcontextprotocol/sdk` 1.32 / zod 4 / vitest 5 / pnpm（aster-guard）。

**Spec:** `docs/adr/0040-action-guard.md`（本仓）。本计划对 ADR 的修正见文末。

## Global Constraints

- 失败语义 fail-closed：规则非 Verdict → `DENY/not_a_verdict`；评估异常或超时 → `DENY/evaluation_failed`；收据写入失败 → `DENY/receipt_unavailable` 且不创建待审批；策略不存在 → 404 `policy_not_found`；请求校验失败 → 400 `invalid_action`。
- 响应字段字母序，null / 空集合不写（`@JsonPropertyOrder(alphabetic = true)` + `@JsonInclude(NON_EMPTY)`）。`effectiveOutcome ∈ ALLOW | DENY | PENDING`。
- Action 扁平化键固定：`principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model`；`principal.id` 与 `action.name` 必填；`context` 深度 ≤ 8，序列化 ≤ 65536 字节。
- 审计 metadata 序列化后 ≤ 4096 字节（监听器超限会截断成非法 JSON），**不存明文 context**，只存 `contextHash`（算法 `sha256-sorted-json/v1`：Jackson `ORDER_MAP_ENTRIES_BY_KEYS` 序列化后 SHA-256 hex）。
- 新事件类型 `ACTION_GUARD_DECISION`、`ACTION_GUARD_APPROVAL`（≤ 50 字符，无需迁移）。
- 新迁移 `V6.27.0__create_guard_tables.sql`，须在 `src/test/resources/db/migration-checksums.golden` 追加 `文件名=sha256`（`shasum -a 256`，LF）。
- 新路径必须加入 `ApiKeyAuthFilter.shouldProtect`，并在 `ApiKeyAuthFilterShouldProtectTest` 加断言。
- 四眼：审批人 `X-User-Id`（API key 解析后的 performedBy）≠ `principal_id`；`required_role` 非空时请求头 `X-User-Business-Role` 必须与之相等。
- 待审批过期默认 24 小时，配置键 `aster.guard.approval-ttl`（Duration）。
- 注释与文档简体中文，描述意图与约束；不写“修改说明”。提交信息末尾空行后加 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。
- aster-api 分支 `feat/adr-0040-action-guard`（从 `adr-0040-action-guard` 切出）；aster-guard 为新本地仓库 `/Users/rpang/IdeaProjects/aster-guard`，分支 `main`；远程仓库创建与推送由用户决定。
- 工具路径：pnpm `$HOME/Library/pnpm/pnpm`；Node 24 在 `/opt/homebrew/opt/node@24/bin`；podman 在 `/opt/podman/bin`。aster-api 的 `@QuarkusTest` 需要 Postgres：`podman run -d --rm --name guard-pg -p 55432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=aster_policy postgres:17-alpine`，运行测试时设 `DB_JDBC_URL=jdbc:postgresql://localhost:55432/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres`，根项目测试用 `./gradlew --offline :test --tests '...'`。macOS 无 `timeout`。

## Review Focus

1. 两个并发请求携带同一 `Idempotency-Key`：必须都返回同一 decisionId，不能第二个 500。测试在 Task 5。
2. 规则返回 `Verdict.require_approval` 但收据写入失败：不能留下孤儿待审批记录。测试在 Task 4。
3. 审批人就是发起主体（`X-User-Id == principal_id`）：必须 403，哪怕角色匹配。测试在 Task 6。
4. 已过期但调度器尚未跑：`GET /decisions/{id}` 必须立即返回 DENY，而不是 PENDING。测试在 Task 6。
5. MCP 代理在 guard 服务不可达（网络错误 / 5xx）时：工具调用必须被拒绝，不能透传到上游。测试在 Task 11。

---

### Task 1: 探针 —— 已部署规则接收 Map 参数（aster-api）

**Files:**
- Create: `aster-api/src/test/java/io/aster/guard/DeployedMapParamProbeTest.java`
- Modify（仅当探针失败）: `aster-api/src/main/java/io/aster/policy/runtime/TrufflePolicyRuntime.java:218-230`

**Interfaces:**
- Produces: 确认 `PolicyEvaluationService.evaluatePolicy(tenant, module, function, new Object[]{Map})` 可让规则读取 `req.action` 等字段；后续任务据此传参。

- [ ] **Step 1: 建分支**

```bash
cd /Users/rpang/IdeaProjects/aster-api && git checkout adr-0040-action-guard && git checkout -b feat/adr-0040-action-guard
```

- [ ] **Step 2: 写探针测试**

```java
package io.aster.guard;

import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0040 §2.3 探针：部署路径的 Truffle 上下文能否让规则读取 Map 参数的成员。
 * 通过本测试，Action Guard 才能把扁平化的 Action 作为单一记录参数传入已部署规则。
 */
@QuarkusTest
class DeployedMapParamProbeTest {
  static final String TENANT = "tenant-guard-probe";
  static final String MODULE = "guard.probe";
  static final String FUNCTION = "decide";
  static final String SOURCE = String.join("\n",
      "Module guard.probe.", "",
      "Define GuardRequest has principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model.", "",
      "@id(\"PROBE-001\")",
      "Rule decide given req as GuardRequest, produce Verdict:",
      "  If req.action equals to \"delete_customer_record\":",
      "    Return Verdict.require_approval(\"Data Protection Officer\", \"customer record deletion\").",
      "  Otherwise:",
      "    Return Verdict.allow().");

  @Inject PolicyEvaluationService evaluationService;
  @Inject CompiledPolicyCache compiledPolicyCache;
  @Inject PolicyVersionResolutionCache versionResolutionCache;

  @BeforeEach @Transactional
  void clean() {
    PolicyCatalog.update("defaultVersionId = null where tenantId = ?1", TENANT);
    PolicyArtifact.delete("policyVersionId in (select id from PolicyVersion where tenantId = ?1)", TENANT);
    PolicyVersion.delete("tenantId = ?1", TENANT);
    PolicyCatalog.delete("tenantId = ?1", TENANT);
    compiledPolicyCache.clear();
    versionResolutionCache.clear();
  }

  static void deploy(String tenant, String module, String function, String source) {
    QuarkusTransaction.requiringNew().run(() -> {
      PolicyCatalog catalog = new PolicyCatalog();
      catalog.id = UUID.randomUUID(); catalog.tenantId = tenant; catalog.moduleName = module;
      catalog.functionName = function; catalog.domain = "guard"; catalog.tags = "{}";
      catalog.createdAt = Instant.now(); catalog.updatedAt = Instant.now();
      catalog.persist();
      PolicyVersion version = new PolicyVersion();
      version.policyId = module + "." + function; version.version = System.currentTimeMillis();
      version.moduleName = module; version.functionName = function; version.content = source;
      version.locale = "en-US"; version.active = true; version.status = VersionStatus.APPROVED;
      version.tenantId = tenant; version.createdAt = Instant.now(); version.createdBy = "probe";
      version.persist();
      catalog.defaultVersionId = version.id; catalog.persist();
    });
  }

  @Test
  void 规则能读取Map参数成员() {
    deploy(TENANT, MODULE, FUNCTION, SOURCE);
    Map<String, Object> req = Map.of(
        "principal_id", "agent-42", "principal_type", "agent", "principal_roles", List.of("ops-bot"),
        "action", "delete_customer_record", "resource_type", "customer", "resource_id", "C-1001",
        "context", Map.of("amount", 250000), "agent_model", "claude-fable-5-1");
    PolicyEvaluationResult result = evaluationService
        .evaluatePolicy(TENANT, MODULE, FUNCTION, new Object[]{req}).await().indefinitely();
    assertThat(result.getDecision()).isNotNull();
    assertThat(result.getDecision().outcome()).isEqualTo("REQUIRE_APPROVAL");
    assertThat(result.getDecision().role()).isEqualTo("Data Protection Officer");
    assertThat(result.getRuleId()).isEqualTo("PROBE-001");
  }
}
```

- [ ] **Step 3: 启动 Postgres 并运行**

```bash
export PATH=/opt/podman/bin:$PATH; podman run -d --rm --name guard-pg -p 55432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=aster_policy postgres:17-alpine; sleep 6
cd /Users/rpang/IdeaProjects/aster-api && DB_JDBC_URL=jdbc:postgresql://localhost:55432/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres ./gradlew --offline :test --tests 'io.aster.guard.DeployedMapParamProbeTest'
```
预期两种结果之一：通过（跳到 Step 5）；或失败且报错含 `HostObject` / `不支持成员访问`（进入 Step 4）。其它失败形态（编译错误、`policy_not_found`）说明部署夹具有问题，先修夹具。

- [ ] **Step 4（条件）: 为池化上下文开启 Map / List / 数组访问**

`TrufflePolicyRuntime.newPooledContext()` 把 `.allowHostAccess(HostAccess.EXPLICIT)` 改为：

```java
            // Action Guard（ADR 0040）把扁平化的 Action 作为 Map 参数传入规则；
            // 与 replay 模块 DynamicCnlExecutor 的宿主访问口径一致，仅放开成员读取，不放开类查找。
            .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowMapAccess(true)
                .build())
```
重跑 Step 3 至通过，再跑 `./gradlew --offline :test --tests 'io.aster.policy.runtime.*' --tests 'io.aster.policy.integration.DynamicPolicyExecutionTest'`。

- [ ] **Step 5: 提交**

```bash
git add src/test/java/io/aster/guard/DeployedMapParamProbeTest.java src/main/java/io/aster/policy/runtime/TrufflePolicyRuntime.java
git commit -m "test(guard): 部署路径 Map 参数探针（ADR 0040 §2.3）"
```

---

### Task 2: 迁移与实体（aster-api）

**Files:**
- Create: `aster-api/src/main/resources/db/migration/V6.27.0__create_guard_tables.sql`
- Create: `aster-api/src/main/java/io/aster/guard/GuardDecisionEntity.java`
- Create: `aster-api/src/main/java/io/aster/guard/GuardApprovalEntity.java`
- Create: `aster-api/src/main/java/io/aster/guard/GuardApprovalStatus.java`
- Modify: `aster-api/src/test/resources/db/migration-checksums.golden`
- Test: `aster-api/src/test/java/io/aster/guard/GuardEntitiesPersistenceTest.java`

**Interfaces:**
- Produces: `GuardDecisionEntity`（字段见迁移）含静态 `findByIdempotencyKey(tenantId, key)` 与 `findByIdAndTenant(UUID, tenantId)`；`GuardApprovalEntity` 含 `findPendingByDecision(UUID decisionId)`、`findByIdAndTenant(UUID, tenantId)`、`listByTenantAndStatus(tenantId, status, page, size)`、`isExpired(Instant now)`；枚举 `GuardApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED }`。

- [ ] **Step 1: 写迁移**

```sql
-- Action Guard（ADR 0040）：决策记录与待审批记录。
-- guard_decisions 存每次 guard 请求的结论摘要与首次响应体（幂等重放），不存明文 context。
-- guard_approvals 存 REQUIRE_APPROVAL / ESCALATE 的人工审批状态；审批事实本身在 audit_logs 哈希链里。
CREATE TABLE guard_decisions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255),
    policy_module VARCHAR(200) NOT NULL,
    policy_function VARCHAR(200) NOT NULL,
    policy_version_id BIGINT,
    source_hash VARCHAR(64),
    principal_id VARCHAR(255) NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    resource_type VARCHAR(255) NOT NULL DEFAULT '',
    resource_id VARCHAR(255) NOT NULL DEFAULT '',
    context_hash VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    reason VARCHAR(1000),
    required_role VARCHAR(255),
    reason_code VARCHAR(64),
    rule_id VARCHAR(255),
    controls JSONB,
    receipt_audit_id BIGINT,
    receipt_hash VARCHAR(64),
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT guard_decisions_outcome_ck CHECK (outcome IN ('ALLOW','DENY','REQUIRE_APPROVAL','ESCALATE'))
);
CREATE UNIQUE INDEX guard_decisions_idem_uq ON guard_decisions (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX guard_decisions_tenant_created_ix ON guard_decisions (tenant_id, created_at DESC);

CREATE TABLE guard_approvals (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    decision_id UUID NOT NULL REFERENCES guard_decisions(id),
    outcome VARCHAR(32) NOT NULL,
    required_role VARCHAR(255),
    principal_id VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decided_by VARCHAR(255),
    decided_at TIMESTAMPTZ,
    comment VARCHAR(1000),
    expires_at TIMESTAMPTZ NOT NULL,
    receipt_audit_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT guard_approvals_outcome_ck CHECK (outcome IN ('REQUIRE_APPROVAL','ESCALATE')),
    CONSTRAINT guard_approvals_status_ck CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED'))
);
CREATE UNIQUE INDEX guard_approvals_decision_uq ON guard_approvals (decision_id);
CREATE INDEX guard_approvals_tenant_status_ix ON guard_approvals (tenant_id, status, created_at DESC);
CREATE INDEX guard_approvals_pending_expiry_ix ON guard_approvals (expires_at) WHERE status = 'PENDING';

COMMENT ON TABLE guard_decisions IS 'Action Guard 决策记录（ADR 0040 §3.3）';
COMMENT ON TABLE guard_approvals IS 'Action Guard 待审批记录（ADR 0040 §4.1）';
```

```bash
cd /Users/rpang/IdeaProjects/aster-api && printf 'V6.27.0__create_guard_tables.sql=%s\n' "$(shasum -a 256 src/main/resources/db/migration/V6.27.0__create_guard_tables.sql | cut -d' ' -f1)" >> src/test/resources/db/migration-checksums.golden
./gradlew --offline :test --tests 'io.aster.db.MigrationGovernanceTest'
```
预期通过（若 `migrationChecksumStable` 因换行归一化报不一致，用它输出的哈希替换追加行）。

- [ ] **Step 2: 写实体**

`GuardApprovalStatus.java`：`public enum GuardApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED }`。

`GuardDecisionEntity.java`（照 `ReplayBatchEntity` 风格，公共字段）：

```java
package io.aster.guard;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Action Guard 决策记录（ADR 0040 §3.3）。response_json 保存首次响应，幂等重放原样返回。 */
@RegisterForReflection
@Entity
@Table(name = "guard_decisions")
public class GuardDecisionEntity extends PanacheEntityBase {
  @Id @Column(name = "id") public UUID id;
  @Column(name = "tenant_id", nullable = false) public String tenantId;
  @Column(name = "idempotency_key") public String idempotencyKey;
  @Column(name = "policy_module", nullable = false) public String policyModule;
  @Column(name = "policy_function", nullable = false) public String policyFunction;
  @Column(name = "policy_version_id") public Long policyVersionId;
  @Column(name = "source_hash") public String sourceHash;
  @Column(name = "principal_id", nullable = false) public String principalId;
  @Column(name = "action_name", nullable = false) public String actionName;
  @Column(name = "resource_type", nullable = false) public String resourceType = "";
  @Column(name = "resource_id", nullable = false) public String resourceId = "";
  @Column(name = "context_hash", nullable = false) public String contextHash;
  @Column(name = "outcome", nullable = false) public String outcome;
  @Column(name = "reason") public String reason;
  @Column(name = "required_role") public String requiredRole;
  @Column(name = "reason_code") public String reasonCode;
  @Column(name = "rule_id") public String ruleId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "controls", columnDefinition = "jsonb") public String controls;
  @Column(name = "receipt_audit_id") public Long receiptAuditId;
  @Column(name = "receipt_hash") public String receiptHash;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_json", columnDefinition = "jsonb", nullable = false) public String responseJson;
  @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();

  public static Optional<GuardDecisionEntity> findByIdempotencyKey(String tenantId, String key) {
    return find("tenantId = ?1 and idempotencyKey = ?2", tenantId, key).firstResultOptional();
  }
  public static Optional<GuardDecisionEntity> findByIdAndTenant(UUID id, String tenantId) {
    return find("id = ?1 and tenantId = ?2", id, tenantId).firstResultOptional();
  }
}
```

`GuardApprovalEntity.java`：同风格，字段对应迁移；`status` 用 `@Enumerated(EnumType.STRING) GuardApprovalStatus status = PENDING`；静态方法：

```java
  public static Optional<GuardApprovalEntity> findByDecision(UUID decisionId) {
    return find("decisionId = ?1", decisionId).firstResultOptional();
  }
  public static Optional<GuardApprovalEntity> findByIdAndTenant(UUID id, String tenantId) {
    return find("id = ?1 and tenantId = ?2", id, tenantId).firstResultOptional();
  }
  public static java.util.List<GuardApprovalEntity> listByTenantAndStatus(String tenantId, GuardApprovalStatus status, int page, int size) {
    return find("tenantId = ?1 and status = ?2 order by createdAt desc", tenantId, status).page(page, Math.min(size, 200)).list();
  }
  /** 过期判定在读取时也执行，不依赖调度器及时（ADR 0040 §4.3）。 */
  public boolean isExpired(Instant now) { return status == GuardApprovalStatus.PENDING && expiresAt.isBefore(now); }
```

- [ ] **Step 3: 写持久化测试并运行**

`GuardEntitiesPersistenceTest`（`@QuarkusTest`，`@Transactional` 方法）：插入一条决策 + 一条审批，断言 `findByIdempotencyKey`、`findByDecision`、`listByTenantAndStatus` 返回；再插入同租户同幂等键第二条决策，断言 `persistAndFlush` 抛 `jakarta.persistence.PersistenceException`（唯一索引生效）；`isExpired` 对 `expiresAt` 过去 / 未来各一例。

```bash
DB_JDBC_URL=jdbc:postgresql://localhost:55432/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres ./gradlew --offline :test --tests 'io.aster.guard.GuardEntitiesPersistenceTest' --tests 'io.aster.db.MigrationGovernanceTest'
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/db/migration/V6.27.0__create_guard_tables.sql src/test/resources/db/migration-checksums.golden src/main/java/io/aster/guard src/test/java/io/aster/guard/GuardEntitiesPersistenceTest.java
git commit -m "feat(guard): guard_decisions / guard_approvals 迁移与实体（ADR 0040）"
```

---

### Task 3: 同步审计追加 `AuditChainAppender`（aster-api）

**Files:**
- Create: `aster-api/src/main/java/io/aster/audit/chain/AuditChainAppender.java`
- Modify: `aster-api/src/main/java/io/aster/policy/event/AuditEventListener.java`
- Modify: `aster-api/src/main/java/io/aster/policy/event/EventType.java`
- Test: `aster-api/src/test/java/io/aster/audit/chain/AuditChainAppenderTest.java`

**Interfaces:**
- Produces: `@ApplicationScoped AuditChainAppender` with `public record AppendedRecord(Long auditId, String currentHash, String prevHash)` and `public AppendedRecord appendSync(AuditEvent event)`（在 `QuarkusTransaction.requiringNew()` 中持久化；任何失败抛 `AuditAppendException extends RuntimeException`，**不写行**）；`public AuditLog toAuditLog(AuditEvent event)`（纯映射，含脱敏与 metadata 序列化，供监听器复用）。
- `EventType` 新增 `ACTION_GUARD_DECISION`、`ACTION_GUARD_APPROVAL`。

- [ ] **Step 1: 写失败测试**

```java
package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.test.BlockingDbTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 同步审计追加（ADR 0040 §5.1）：返回行 id 与哈希；与异步路径同算法；失败不留行。 */
@QuarkusTest
class AuditChainAppenderTest {
  static final String TENANT = "tenant-guard-appender";
  @Inject AuditChainAppender appender;
  @Inject BlockingDbTestHelper db;

  @BeforeEach void clean() { db.executeAsAuditMaintenance("DELETE FROM audit_logs WHERE tenant_id = '" + TENANT + "'"); }

  static AuditEvent event(Map<String, Object> metadata) {
    return new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), TENANT, "guard.probe", "decide", null,
        null, 3L, "agent-42", false, 12L, null, metadata, null, null, null, null);
  }

  @Test void 同步追加返回链哈希且与持久化行一致() {
    var first = appender.appendSync(event(Map.of("decisionId", "d-1", "outcome", "DENY")));
    var second = appender.appendSync(event(Map.of("decisionId", "d-2", "outcome", "ALLOW")));
    assertThat(first.auditId()).isNotNull();
    assertThat(first.currentHash()).hasSize(64);
    assertThat(second.prevHash()).isEqualTo(first.currentHash());
    AuditLog row = AuditLog.findById(second.auditId());
    assertThat(row.currentHash).isEqualTo(second.currentHash());
    assertThat(row.eventType).isEqualTo("ACTION_GUARD_DECISION");
    assertThat(row.metadata).contains("\"decisionId\":\"d-2\"");
  }

  @Test void 哈希与异步监听器算法一致() {
    var appended = appender.appendSync(event(Map.of("decisionId", "d-3")));
    AuditLog row = AuditLog.findById(appended.auditId());
    assertThat(AuditHashPayload.digest(row, row.hashVersion)).isEqualTo(row.currentHash);
  }

  @Test void 失败时不写行() {
    // tenantId 超过列长 100 → 持久化失败 → 抛异常且表中无该租户行
    String longTenant = "t".repeat(150);
    AuditEvent bad = new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), longTenant, "m", "f", null,
        null, null, "u", false, null, null, Map.of(), null, null, null, null);
    assertThatThrownBy(() -> appender.appendSync(bad)).isInstanceOf(AuditChainAppender.AuditAppendException.class);
    assertThat(AuditLog.<AuditLog>find("tenantId = ?1", longTenant).list()).isEmpty();
  }
}
```

- [ ] **Step 2: 运行确认失败（编译错误：类不存在）**

```bash
DB_JDBC_URL=... ./gradlew --offline :test --tests 'io.aster.audit.chain.AuditChainAppenderTest'
```

- [ ] **Step 3: 实现 `AuditChainAppender`，监听器改为复用**

把 `AuditEventListener.onAuditEvent` 中「构造 `AuditLog` + 脱敏 + `serializeMetadata` + `acquireTenantChainLock` + `computeHashChain` + `persist`」整体移到 `AuditChainAppender`：

```java
package io.aster.audit.chain;

/** 审计链追加器：同步与异步两条路径共用同一套脱敏、序列化与哈希逻辑（ADR 0040 §5.1）。 */
@ApplicationScoped
public class AuditChainAppender {
  public record AppendedRecord(Long auditId, String currentHash, String prevHash) {}
  public static final class AuditAppendException extends RuntimeException {
    public AuditAppendException(String message, Throwable cause) { super(message, cause); }
  }
  // 原 AuditEventListener 的字段：MAX_METADATA_SIZE、PIIRedactor、ObjectMapper、EntityManager、dbKind

  /** 纯映射：事件 → 实体（脱敏、metadata 序列化），不触库。 */
  public AuditLog toAuditLog(AuditEvent event) { /* 原 onAuditEvent 的前半段 */ }

  /** 在调用方当前事务内追加（供异步监听器使用，监听器自带 @Transactional）。 */
  public AppendedRecord appendInCurrentTransaction(AuditEvent event) {
    AuditLog log = toAuditLog(event);
    acquireTenantChainLock(log.tenantId);
    computeHashChain(log);
    log.persist();
    log.flush();
    return new AppendedRecord(log.id, log.currentHash, log.prevHash);
  }

  /** 独立事务同步追加：成功返回收据，任何失败抛 AuditAppendException 且事务回滚（不留行）。 */
  public AppendedRecord appendSync(AuditEvent event) {
    try {
      return QuarkusTransaction.requiringNew().call(() -> appendInCurrentTransaction(event));
    } catch (Exception e) {
      throw new AuditAppendException("审计链同步追加失败: " + event.eventType(), e);
    }
  }
}
```

`AuditEventListener.onAuditEvent` 改为：

```java
  @Transactional
  public void onAuditEvent(@ObservesAsync AuditEvent event) {
    try {
      var appended = appender.appendInCurrentTransaction(event);
      businessMetrics.recordAuditLogWrite();
      Log.debugf("Audit event persisted: type=%s, tenant=%s, hash=%s", event.eventType(), event.tenantId(), appended.currentHash());
    } catch (Exception e) {
      businessMetrics.recordAuditLogWriteFailure();
      Log.errorf(e, "Failed to persist audit event: type=%s, tenant=%s", event.eventType(), event.tenantId());
    }
  }
```
`tenantLockId` 保持 `public static` 并留在原类（或移到 appender 并在原类保留转发），`TamperDetectionIntegrationTest` 等既有测试不得改动。

- [ ] **Step 4: 运行新测试与既有审计测试**

```bash
DB_JDBC_URL=... ./gradlew --offline :test --tests 'io.aster.audit.chain.*' --tests 'io.aster.audit.*'
```
预期全绿（含 `TamperDetectionIntegrationTest`、`AuditHashChainTest`、`AuditHashChainSchemaTest`）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/audit/chain/AuditChainAppender.java src/main/java/io/aster/policy/event/AuditEventListener.java src/main/java/io/aster/policy/event/EventType.java src/test/java/io/aster/audit/chain/AuditChainAppenderTest.java
git commit -m "feat(audit): 审计链同步追加器与 ACTION_GUARD 事件类型（ADR 0040）"
```

---

### Task 4: Guard 领域层 —— DTO、扁平化、决策服务（aster-api）

**Files:**
- Create: `aster-api/src/main/java/io/aster/guard/api/GuardActionRequest.java`（record：`policyModule, policyFunction, Action action, Integer waitSeconds`；嵌套 record `Action(Principal principal, ActionName action, Resource resource, Map<String,Object> context, Agent agent)`，`Principal(String id, String type, List<String> roles)`，`ActionName(String name)`，`Resource(String type, String id)`，`Agent(String model, String session)`）
- Create: `aster-api/src/main/java/io/aster/guard/api/GuardDecisionResponse.java`（record，`@JsonPropertyOrder(alphabetic = true)` `@JsonInclude(NON_EMPTY)`：`approval(ApprovalView), controls, decisionId, effectiveOutcome, outcome, policyVersion(PolicyVersionView), reason, reasonCode, receipt(ReceiptView), role, ruleId`）
- Create: `aster-api/src/main/java/io/aster/guard/GuardReasonCode.java`（`enum { NOT_A_VERDICT("not_a_verdict"), EVALUATION_FAILED("evaluation_failed"), RECEIPT_UNAVAILABLE("receipt_unavailable"), POLICY_NOT_FOUND("policy_not_found"), INVALID_ACTION("invalid_action") }` 带 `code()`）
- Create: `aster-api/src/main/java/io/aster/guard/GuardActionMapper.java`（校验 + 扁平化 + contextHash）
- Create: `aster-api/src/main/java/io/aster/guard/GuardDecisionService.java`
- Create: `aster-api/src/main/java/io/aster/guard/GuardReceiptBuilder.java`（构造 `AuditEvent` 与 metadata）
- Create: `aster-api/src/main/java/io/aster/guard/GuardPolicyNotFoundException.java`、`GuardInvalidActionException.java`（RuntimeException，携带 reasonCode）
- Test: `aster-api/src/test/java/io/aster/guard/GuardActionMapperTest.java`、`GuardReceiptBuilderTest.java`、`GuardDecisionServiceTest.java`

**Interfaces:**
- `GuardActionMapper.flatten(Action a) → Map<String,Object>`（键序：principal_id, principal_type, principal_roles, action, resource_type, resource_id, context, agent_model；缺省值见 Global Constraints；校验失败抛 `GuardInvalidActionException`）；`GuardActionMapper.contextHash(Map<String,Object> context) → String`（sha256-sorted-json/v1）。
- `GuardReceiptBuilder.decisionEvent(GuardDecisionDraft draft, UUID decisionId, UUID approvalId, String performedBy) → AuditEvent`；`approvalEvent(GuardApprovalEntity a, String decisionReceiptHash) → AuditEvent`；metadata 序列化 ≤ 4096 字节由测试断言。
- `GuardDecisionService.decide(String tenantId, String performedBy, GuardActionRequest req, String idempotencyKey) → GuardDecisionResponse`（阻塞方法，在 worker 线程调用）；`GuardDecisionService.get(String tenantId, UUID decisionId) → Optional<GuardDecisionResponse>`。
- 内部 record `GuardDecisionDraft`（评估结果 + 版本信息 + 扁平 Action + reasonCode）。

- [ ] **Step 1: 写失败测试（纯 JUnit，不启动 Quarkus）**

`GuardActionMapperTest`：
- `扁平化键序与缺省值`：最小 Action（只有 principal.id 与 action.name）→ `flatten` 返回 LinkedHashMap，`new ArrayList<>(m.keySet())` 等于固定键序；`principal_type == "agent"`、`principal_roles` 空 List、`resource_type/resource_id/agent_model == ""`、`context` 空 Map。
- `缺principal id报invalid_action`、`缺action name报invalid_action`。
- `context超深度报invalid_action`（嵌套 9 层）；`context超大小报invalid_action`（一个 70000 字符的字符串值）。
- `contextHash与键序无关`：`Map.of("b",1,"a",2)` 与 `LinkedHashMap{a,b}` 相同哈希；空 context 的哈希等于 `sha256("{}")` 的 hex。

`GuardReceiptBuilderTest`：
- 构造 draft（outcome REQUIRE_APPROVAL、ruleId、controls 两个、contextHash、版本 id/sourceHash、decisionId、approvalId），断言 `event.eventType()==ACTION_GUARD_DECISION`，`success()==false`，`metadata` 含全部键，`toVersion()` 等于版本号，`new ObjectMapper().writeValueAsString(event.metadata()).length() < 4096`；ALLOW 时 `success()==true`。
- 用 255 字符的 reason、10 个 controls、255 字符的 principal/resource id 构造「最坏情况」draft，断言 metadata 序列化 < 4096。

`GuardDecisionServiceTest`（Mockito mock `PolicyEvaluationService`、`AuditChainAppender`、`PolicySourceRepository`；实体用 Mockito 的静态 mock 不便，故把持久化封装成可注入的 `GuardDecisionStore` 接口并 mock）：
- `Verdict allow → ALLOW 且写收据与决策`。
- `Verdict require_approval → PENDING 且创建待审批`：断言 `store.saveApproval` 被调用，`approval.requiredRole` 正确。
- `非Verdict结果 → DENY not_a_verdict`：`PolicyEvaluationResult.getDecision()` 返回 null。
- `评估抛异常 → DENY evaluation_failed`。
- `收据写入失败 → DENY receipt_unavailable 且不创建待审批`：`appender.appendSync` 抛 `AuditAppendException`；断言 `store.saveApproval` 从未调用，但 `store.saveDecision` 被调用（决策本身仍记录为 DENY，`receipt` 为空）。
- `幂等键命中返回存储响应`：`store.findByIdempotencyKey` 返回已有实体 → 不调用评估。
- `策略不存在 → GuardPolicyNotFoundException`：`policySourceRepository.findActiveCatalog` 返回 empty。

- [ ] **Step 2: 运行确认失败（编译错误）**

```bash
./gradlew --offline :test --tests 'io.aster.guard.GuardActionMapperTest' --tests 'io.aster.guard.GuardReceiptBuilderTest' --tests 'io.aster.guard.GuardDecisionServiceTest'
```

- [ ] **Step 3: 实现**

`GuardActionMapper`（`@ApplicationScoped`）：

```java
  static final int MAX_CONTEXT_DEPTH = 8;
  static final int MAX_CONTEXT_BYTES = 65_536;
  private static final ObjectMapper SORTED = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  public Map<String, Object> flatten(GuardActionRequest.Action a) {
    if (a == null || a.principal() == null || isBlank(a.principal().id())) throw new GuardInvalidActionException("principal.id 必填");
    if (a.action() == null || isBlank(a.action().name())) throw new GuardInvalidActionException("action.name 必填");
    Map<String, Object> context = a.context() == null ? Map.of() : a.context();
    checkDepth(context, 1);
    if (serializedSize(context) > MAX_CONTEXT_BYTES) throw new GuardInvalidActionException("context 超过 65536 字节");
    LinkedHashMap<String, Object> m = new LinkedHashMap<>();
    m.put("principal_id", a.principal().id());
    m.put("principal_type", orDefault(a.principal().type(), "agent"));
    m.put("principal_roles", a.principal().roles() == null ? List.of() : List.copyOf(a.principal().roles()));
    m.put("action", a.action().name());
    m.put("resource_type", a.resource() == null ? "" : orDefault(a.resource().type(), ""));
    m.put("resource_id", a.resource() == null ? "" : orDefault(a.resource().id(), ""));
    m.put("context", context);
    m.put("agent_model", a.agent() == null ? "" : orDefault(a.agent().model(), ""));
    return m;
  }

  /** sha256-sorted-json/v1：键排序后的 Jackson 序列化再 SHA-256，与 context 的键写入顺序无关。 */
  public String contextHash(Map<String, Object> context) {
    try {
      byte[] json = SORTED.writeValueAsBytes(context == null ? Map.of() : context);
      return org.apache.commons.codec.digest.DigestUtils.sha256Hex(json);
    } catch (JsonProcessingException e) { throw new GuardInvalidActionException("context 无法序列化"); }
  }
```
`checkDepth` 递归 Map / List，超过 `MAX_CONTEXT_DEPTH` 抛异常。

`GuardDecisionStore`（接口 + `@ApplicationScoped` Panache 实现 `GuardDecisionJpaStore`）：`Optional<GuardDecisionEntity> findByIdempotencyKey(tenant, key)`、`Optional<GuardDecisionEntity> findDecision(tenant, UUID)`、`Optional<GuardApprovalEntity> findApprovalByDecision(UUID)`、`void saveDecision(GuardDecisionEntity)`、`void saveApproval(GuardApprovalEntity)`——实现用 `QuarkusTransaction.requiringNew().run(...)`，`saveDecision` 捕获唯一索引冲突（`isUniqueViolation`，照 `ReplayBatchResource`）并抛 `GuardIdempotencyRaceException`。

`GuardDecisionService.decide` 流程：

```java
  public GuardDecisionResponse decide(String tenantId, String performedBy, GuardActionRequest req, String idempotencyKey) {
    if (idempotencyKey != null) {
      var existing = store.findByIdempotencyKey(tenantId, idempotencyKey);
      if (existing.isPresent()) return readResponse(existing.get());
    }
    Map<String, Object> flat = mapper.flatten(req.action());
    String contextHash = mapper.contextHash(req.action().context());
    PolicyVersionView version = resolveVersion(tenantId, req.policyModule(), req.policyFunction()); // empty → GuardPolicyNotFoundException
    GuardDecisionDraft draft = evaluate(tenantId, req, flat, contextHash, version);   // 评估异常 → DENY evaluation_failed；非 Verdict → DENY not_a_verdict
    UUID decisionId = UUID.randomUUID();
    UUID approvalId = draft.needsApproval() ? UUID.randomUUID() : null;
    AppendedRecord receipt;
    try {
      receipt = appender.appendSync(receiptBuilder.decisionEvent(draft, decisionId, approvalId, performedBy));
    } catch (AuditChainAppender.AuditAppendException e) {
      draft = draft.deny(GuardReasonCode.RECEIPT_UNAVAILABLE);   // 不创建待审批
      receipt = null; approvalId = null;
    }
    GuardDecisionResponse response = buildResponse(decisionId, draft, version, receipt, approvalId, Instant.now().plus(approvalTtl));
    try {
      store.saveDecision(toEntity(decisionId, tenantId, idempotencyKey, draft, receipt, response));
    } catch (GuardIdempotencyRaceException race) {
      return readResponse(store.findByIdempotencyKey(tenantId, idempotencyKey).orElseThrow());   // 并发同键：返回先到者
    }
    if (approvalId != null) store.saveApproval(toApproval(approvalId, tenantId, decisionId, draft, receipt));
    return response;
  }
```

`effectiveOutcome`：ALLOW→ALLOW；DENY→DENY；REQUIRE_APPROVAL / ESCALATE→PENDING（有 approvalId）。`evaluate` 用 `evaluationService.evaluatePolicy(tenant, module, function, new Object[]{flat}).await().atMost(Duration.ofSeconds(10))`，`VerdictView.from(result.getResult())` 为空 → NOT_A_VERDICT。`resolveVersion` 用 `policySourceRepository.findActiveCatalog` + `findActiveVersion`，在 `QuarkusTransaction.requiringNew().call`。

- [ ] **Step 4: 测试直到通过**

```bash
./gradlew --offline :test --tests 'io.aster.guard.*Test'
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/guard src/test/java/io/aster/guard
git commit -m "feat(guard): Action 扁平化、决策服务与收据构造（ADR 0040 §2-§5）"
```

---

### Task 5: 决策 REST 端点与 API key 保护（aster-api）

**Files:**
- Create: `aster-api/src/main/java/io/aster/guard/api/GuardDecisionResource.java`
- Modify: `aster-api/src/main/java/io/aster/security/apikey/ApiKeyAuthFilter.java:178-212`（`shouldProtect`）
- Modify: `aster-api/src/test/java/io/aster/security/apikey/ApiKeyAuthFilterShouldProtectTest.java`
- Test: `aster-api/src/test/java/io/aster/guard/api/GuardDecisionResourceTest.java`

**Interfaces:**
- `POST /api/v1/guard/decisions`（body `GuardActionRequest`，头 `Idempotency-Key` 可选）→ 200 `GuardDecisionResponse`；404 `{"error":"policy_not_found"}`；400 `{"error":"invalid_action","message":…}`。
- `GET /api/v1/guard/decisions/{id}` → 200 / 404。

- [ ] **Step 1: shouldProtect 测试先红**

在 `ApiKeyAuthFilterShouldProtectTest` 加：`/api/v1/guard/decisions`、`/api/v1/guard/decisions/abc`、`/api/v1/guard/approvals`、`/api/v1/guard/approvals/x/approve` 均应为 true；`/api/v1/guardian` 应为 false。运行确认失败，然后在 `shouldProtect` 的 workflows 判断之后加：

```java
        // Action Guard（ADR 0040）：Agent 直连端点，全部走 API key。
        if (p.equals("/api/v1/guard") || p.startsWith("/api/v1/guard/")) return true;
```

- [ ] **Step 2: 写 REST 测试（`@QuarkusTest`，默认测试配置下 apikey / rbac 关闭，只需 `X-Tenant-Id` 与 `X-User-Id`）**

复用 Task 1 的 `DeployedMapParamProbeTest.deploy(...)` 部署三个规则：ALLOW（`Return Verdict.allow().`）、REQUIRE_APPROVAL（探针源码）、Bool（`produce Bool: Return true.`）。用例：
- `ALLOW 响应完整`：`effectiveOutcome=="ALLOW"`，`receipt.hash` 64 hex，`policyVersion.sourceHash` 非空，无 `approval`、无 `reasonCode` 键。
- `REQUIRE_APPROVAL 返回 PENDING 与 approval`：`approval.status=="PENDING"`，`approval.requiredRole=="Data Protection Officer"`，`approval.expiresAt` 在 23–25 小时后。
- `Bool 规则 → DENY not_a_verdict`。
- `策略不存在 → 404 policy_not_found`；`缺 principal.id → 400 invalid_action`。
- `幂等重放`：同一 `Idempotency-Key` 两次请求，响应体字节相同；不同租户同键互不影响。
- `并发同键`：`ExecutorService` 8 线程同时发同键请求，收集所有 200 响应，`decisionId` 集合大小为 1（Review Focus 1）。
- `GET 回读`：`GET /api/v1/guard/decisions/{id}` 等于 POST 响应；跨租户 404。
- `verify-chain 含 guard 事件仍 valid`：调用 `GET /api/v1/audit/verify-chain?start=…&end=…`（头 `X-Tenant-Id`，`X-User-Role: ADMIN`）断言 `valid==true` 且 `recordsVerified>=1`。

- [ ] **Step 3: 实现资源**

```java
@Path("/api/v1/guard/decisions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardDecisionResource {
  @Inject RequestIdentityResolver identityResolver;
  @Inject GuardDecisionService service;

  @POST
  public Response decide(@Valid GuardActionRequest req, @HeaderParam("Idempotency-Key") String idempotencyKey) {
    String tenantId = identityResolver.tenantId();
    String performedBy = identityResolver.performedBy();
    try {
      return Response.ok(service.decide(tenantId, performedBy, req, blankToNull(idempotencyKey))).build();
    } catch (GuardInvalidActionException bad) {
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "invalid_action", "message", bad.getMessage())).build();
    } catch (GuardPolicyNotFoundException nf) {
      return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "policy_not_found", "message", nf.getMessage())).build();
    }
  }

  @GET @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    UUID uuid; try { uuid = UUID.fromString(id); } catch (IllegalArgumentException e) { return Response.status(Response.Status.NOT_FOUND).build(); }
    return service.get(identityResolver.tenantId(), uuid).map(r -> Response.ok(r).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }
}
```
方法返回 `Response`（阻塞签名），RESTEasy Reactive 自动在 worker 线程执行；不加 `@Transactional`（服务内部各自 `requiringNew`）。配额：在 `decide` 开头调用与 `PolicyEvaluationResource.enforceApiQuota` 等价的检查——若该方法为私有，把它抽到 `io.aster.billing.ApiQuotaEnforcer`（公开 `enforce(String path, RequestIdentityResolver)`）并让两处共用；若抽取影响面大，本任务先注入 `ApiQuotaGuard` 直接调用 `check(tenantId, userId)` 并在报告中说明。

- [ ] **Step 4: 运行测试直到通过**

```bash
DB_JDBC_URL=... ./gradlew --offline :test --tests 'io.aster.guard.api.GuardDecisionResourceTest' --tests 'io.aster.security.apikey.ApiKeyAuthFilterShouldProtectTest'
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/guard/api/GuardDecisionResource.java src/main/java/io/aster/security/apikey/ApiKeyAuthFilter.java src/test/java/io/aster/security/apikey/ApiKeyAuthFilterShouldProtectTest.java src/test/java/io/aster/guard/api/GuardDecisionResourceTest.java
git commit -m "feat(guard): POST/GET /api/v1/guard/decisions 与 API key 保护（ADR 0040 §3）"
```

---

### Task 6: 待审批端点、四眼、过期与 `waitSeconds`（aster-api）

**Files:**
- Create: `aster-api/src/main/java/io/aster/guard/GuardApprovalService.java`
- Create: `aster-api/src/main/java/io/aster/guard/api/GuardApprovalResource.java`
- Create: `aster-api/src/main/java/io/aster/guard/GuardApprovalExpiryScheduler.java`
- Modify: `aster-api/src/main/java/io/aster/guard/GuardDecisionService.java`（`waitSeconds`、`get` 的过期判定）
- Modify: `aster-api/src/main/resources/application.properties`（`aster.guard.approval-ttl=${ASTER_GUARD_APPROVAL_TTL:PT24H}`、`aster.guard.approval-expiry-cron=${ASTER_GUARD_APPROVAL_EXPIRY_CRON:0 * * * * ?}`）
- Test: `aster-api/src/test/java/io/aster/guard/api/GuardApprovalResourceTest.java`、`aster-api/src/test/java/io/aster/guard/GuardApprovalServiceTest.java`

**Interfaces:**
- `GuardApprovalService.decideApproval(tenantId, UUID approvalId, String decidedBy, String businessRole, boolean approve, String comment) → GuardDecisionResponse`；抛 `GuardApprovalConflictException(code)`，code ∈ `segregation_of_duties`(403) / `role_mismatch`(403) / `comment_required`(400) / `approval_not_pending`(409)。
- `GuardApprovalService.expireDue(Instant now) → int`（标 EXPIRED 并写审批事件，返回条数）。
- 端点：`GET /api/v1/guard/approvals?status=PENDING&page=0&size=20` → `{"items":[ApprovalView…],"page","size"}`；`POST /api/v1/guard/approvals/{id}/approve` body `{"comment"?}`；`POST /api/v1/guard/approvals/{id}/reject` body `{"comment"}`。审批人身份：`X-User-Id`（经 `RequestIdentityResolver.performedBy()`）；业务角色：`@HeaderParam("X-User-Business-Role")`。
- `GuardDecisionResponse.effectiveOutcome` 对 APPROVED → ALLOW，REJECTED / EXPIRED → DENY，PENDING（未过期）→ PENDING，PENDING 且 `expiresAt < now` → DENY。

- [ ] **Step 1: 写失败测试**

`GuardApprovalServiceTest`（纯 JUnit + Mockito，mock `GuardDecisionStore` 与 `AuditChainAppender`）：
- `发起主体自批 → segregation_of_duties`（Review Focus 3）。
- `角色不符 → role_mismatch`；`required_role 为空（ESCALATE）任意角色可批`。
- `拒绝必须有 comment → comment_required`。
- `非 PENDING → approval_not_pending`；`已过期（expiresAt 过去）→ approval_not_pending`。
- `批准成功写 ACTION_GUARD_APPROVAL 事件且 metadata.decisionReceiptHash 等于决策收据哈希`。
- `expireDue 只处理过期 PENDING 并逐条写事件`。

`GuardApprovalResourceTest`（`@QuarkusTest`，部署 REQUIRE_APPROVAL 规则，用 Task 5 的 POST 产生 PENDING 决策）：
- 列表含该审批；`approve` 用 `X-User-Id: dpo-1`、`X-User-Business-Role: Data Protection Officer` → 200，`effectiveOutcome=="ALLOW"`；再 GET decision 为 ALLOW。
- `X-User-Id: agent-42`（等于 principal）→ 403 `segregation_of_duties`。
- 角色头缺失 → 403 `role_mismatch`。
- `reject` 无 comment → 400；有 comment → 200 DENY。
- 过期：直接用 `@Transactional` 把 `expiresAt` 改为过去 → `GET /decisions/{id}` 返回 `effectiveOutcome=="DENY"`（Review Focus 4）；再调用 `GuardApprovalService.expireDue(now)` 返回 1，状态 EXPIRED。
- `waitSeconds=3` 且另一线程 1 秒后批准 → POST 在 3 秒内返回 ALLOW。
- `verify-chain` 对含决策与审批两类事件的区间 `valid==true`。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

`GuardApprovalService.decideApproval` 要点：在 `requiringNew` 中读取并加锁（`find(...).withLock(LockModeType.PESSIMISTIC_WRITE)`）审批行；校验顺序：存在 → PENDING 且未过期 → 四眼 → 角色 → comment；写审批事件 `appender.appendSync(receiptBuilder.approvalEvent(a, decision.receiptHash))`，失败则抛 `GuardApprovalConflictException("receipt_unavailable")`（503）且不改状态；成功后更新状态、`decidedBy/At/comment/receiptAuditId`，并更新决策行的 `responseJson`（重算 `effectiveOutcome`）。

`GuardDecisionService.decide` 的 `waitSeconds`：当结果为 PENDING 且 `waitSeconds > 0`，循环 `Thread.sleep(500)` 读取审批状态，直到非 PENDING 或超时（上限 30 秒），返回最新响应。`get` 对 PENDING 且过期的审批返回 DENY（不改库）。

`GuardApprovalExpiryScheduler`：

```java
@ApplicationScoped
public class GuardApprovalExpiryScheduler {
  @Inject GuardApprovalService service;
  @Scheduled(cron = "${aster.guard.approval-expiry-cron:0 * * * * ?}", identity = "guard-approval-expiry",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP, skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
  void expire() { int n = service.expireDue(Instant.now()); if (n > 0) Log.infof("Action Guard 待审批过期 %d 条", n); }
}
```

资源：

```java
@Path("/api/v1/guard/approvals") @Produces(JSON) @Consumes(JSON) @RequireRole(Role.MEMBER)
public class GuardApprovalResource {
  @GET public Response list(@QueryParam("status") @DefaultValue("PENDING") String status, @QueryParam("page") @DefaultValue("0") int page, @QueryParam("size") @DefaultValue("20") int size) {...}
  @POST @Path("/{id}/approve") public Response approve(@PathParam("id") String id, Map<String,String> body, @HeaderParam("X-User-Business-Role") String role) { return decide(id, role, true, body); }
  @POST @Path("/{id}/reject")  public Response reject(@PathParam("id") String id, Map<String,String> body, @HeaderParam("X-User-Business-Role") String role) { return decide(id, role, false, body); }
  // decide(): GuardApprovalConflictException → 按 code 映射 400/403/409/503，body Map.of("error", code, "message", ...)
}
```

- [ ] **Step 4: 测试直到通过**

```bash
DB_JDBC_URL=... ./gradlew --offline :test --tests 'io.aster.guard.*'
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/guard src/main/resources/application.properties src/test/java/io/aster/guard
git commit -m "feat(guard): 待审批端点、四眼与角色校验、过期调度、waitSeconds（ADR 0040 §4）"
```

---

### Task 7: aster-api 全量回归与 ADR 修正

**Files:**
- Modify: `aster-api/docs/adr/0040-action-guard.md`

- [ ] **Step 1: 全量**

```bash
export PATH=/opt/podman/bin:$PATH; podman run -d --rm --name guard-redis -p 6379:6379 docker.io/library/redis:7-alpine
cd /Users/rpang/IdeaProjects/aster-api && DB_JDBC_URL=... ./gradlew --offline :replay:test :test
```
预期：仅既有的 `PolicyGraphQLResourceTest.testBatchEvaluateLargeVolume`（5 秒性能断言）与 `PrismaDatabaseE2ETest`（硬编码 5432）可能失败；其余全绿。记录结果。

- [ ] **Step 2: ADR 修正**

- §2.3：写明探针结论（是否需要开启 Map 访问）。
- §4.2：`X-User-Business-Role` 此前 aster-api 不读取，本子项目在审批端点以 `@HeaderParam` 读取，属可信 BFF 标签；受控词表留子项目 3。
- §5.2：`contextHash` 算法改为 `sha256-sorted-json/v1`（Jackson 键排序 + SHA-256），不用 `CanonicalJson`（其对非整数数字有类型上下文要求，Action context 是自由 Map）。
- §3.1：幂等并发同键的处理（先到者胜，后到者回读）。
- 状态改为 `IMPLEMENTING`；§9 加本计划执行期裁决。

```bash
git add docs/adr/0040-action-guard.md && git commit -m "docs(adr): ADR 0040 实施期修正"
```

---

### Task 8: `aster-guard` 仓库脚手架（新仓）

**Files:**
- Create: `/Users/rpang/IdeaProjects/aster-guard/{package.json, tsconfig.json, vitest.config.ts, eslint.config.js, .gitignore, LICENSE, NOTICE, README.md, CODE_OF_CONDUCT.md, CONTRIBUTING.md, SECURITY.md, renovate.json, .github/workflows/ci.yml, .github/workflows/release.yml, src/index.ts}`

**Interfaces:**
- 包名 `@aster-cloud/aster-guard` 0.1.0，`type: module`，`exports: { ".": "./dist/index.js" }`，`bin: { "aster-guard-mcp": "./dist/mcp/cli.js" }`，`engines.node >= 24`，`publishConfig.access: public`，license Apache-2.0（与 Java 生态一致）。
- deps：`@modelcontextprotocol/sdk ^1.32.1`、`zod ^4.0.0`；devDeps：`typescript ^5.6`、`vitest ^5`、`@types/node ^24`、`eslint ^9`、`typescript-eslint ^8`。
- scripts：`build: tsc`、`test: vitest run`、`lint: eslint src test`、`typecheck: tsc --noEmit`。

- [ ] **Step 1: 初始化**

```bash
export PATH=$HOME/Library/pnpm:/opt/homebrew/opt/node@24/bin:$PATH
mkdir -p /Users/rpang/IdeaProjects/aster-guard && cd /Users/rpang/IdeaProjects/aster-guard && git init -b main
```
`package.json`、`tsconfig.json`（照 `aster-lang-test/packages/js/tsconfig.json`，`rootDir ./src`、`outDir ./dist`，`include ["src/**/*"]`；测试目录 `test/` 由 vitest 直接跑 TS）、`vitest.config.ts`（`test.include: ['test/**/*.test.ts']`）、eslint flat config（typescript-eslint recommended）。LICENSE / NOTICE / 行为准则 / 贡献 / 安全 文件从 `/Users/rpang/IdeaProjects/aster-lang-ts` 复制并把包名替换为 aster-guard。README 中文：定位、安装、`GuardClient` 用法、MCP 代理用法（含 Claude Desktop 配置示例）、fail-closed 语义、与 aster-api 的鉴权（API key）。
`ci.yml`：Node 24 + pnpm，`pnpm install --frozen-lockfile && pnpm typecheck && pnpm lint && pnpm test && pnpm build`。`release.yml`：照 aster-lang-ts 的 tag-push + `pnpm publish --no-git-checks --provenance`，去掉 checkout-sibling 与错误码门禁步骤。

- [ ] **Step 2: 占位导出与首次验证**

`src/index.ts` 导出 `export const VERSION = '0.1.0';`；`test/smoke.test.ts` 断言导入成功。

```bash
pnpm install && pnpm typecheck && pnpm lint && pnpm test && pnpm build
git add -A && git commit -m "chore: aster-guard 仓库脚手架（ADR 0040 §6）"
```

---

### Task 9: `GuardClient` SDK（aster-guard）

**Files:**
- Create: `src/types.ts`、`src/client.ts`、`src/index.ts`（替换占位）
- Test: `test/client.test.ts`

**Interfaces:**
- `types.ts`：`GuardAction`（与 aster-api 请求体 `action` 同形）、`GuardDecision`（与 `GuardDecisionResponse` 同形，`effectiveOutcome: 'ALLOW'|'DENY'|'PENDING'`）、`GuardClientOptions { baseUrl, apiKey, policyModule, policyFunction, principal?: GuardAction['principal'], fetch?: typeof fetch, timeoutMs?: number(默认 10000) }`、`GuardApproval`。
- `GuardClient`：`check(action, opts?: { idempotencyKey?, waitSeconds? }) → Promise<GuardDecision>`；`get(decisionId)`；`waitFor(decisionId, { timeoutMs = 300000, intervalMs = 2000 }) → Promise<GuardDecision>`（PENDING 轮询直到非 PENDING 或超时；超时返回 `effectiveOutcome:'DENY', reasonCode:'wait_timeout'`）；`listApprovals(status='PENDING')`；`approve(id, comment?, businessRole?)`；`reject(id, comment, businessRole?)`。
- fail-closed：任何非 2xx、网络错误、JSON 解析失败、超时 → 返回 `{ decisionId: '', outcome: 'DENY', effectiveOutcome: 'DENY', reasonCode: 'client_error' | 'http_<status>' , reason: <message> }`，**不抛异常**（抛异常会让调用方 catch 后误放行）。

- [ ] **Step 1: 写失败测试（vitest，`vi.fn()` 注入 `fetch`）**

用例：`check 发送正确路径、头（Authorization Bearer、Idempotency-Key、Content-Type）与请求体`；`200 ALLOW 原样返回`；`500 → DENY http_500`；`网络错误 → DENY client_error`；`超时（fetch 永不 resolve，timeoutMs=50）→ DENY client_error`；`waitFor：前两次 PENDING 第三次 ALLOW → ALLOW，调用 3 次`；`waitFor 超时 → DENY wait_timeout`；`approve 携带 X-User-Business-Role 头`。

- [ ] **Step 2: 运行确认失败；Step 3 实现；Step 4 GREEN + `pnpm typecheck && pnpm lint`**

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(client): GuardClient SDK，fail-closed（ADR 0040 §6）"
```

---

### Task 10: MCP 代理 `aster-guard-mcp`（aster-guard）

**Files:**
- Create: `src/mcp/proxy.ts`（`createGuardProxyServer(options) → { server: Server, close() }`）
- Create: `src/mcp/standalone-tools.ts`（五个治理工具）
- Create: `src/mcp/config.ts`（CLI 参数 / 环境变量解析，zod 校验）
- Create: `src/mcp/cli.ts`（`#!/usr/bin/env node`，`StdioServerTransport`）
- Test: `test/mcp-proxy.test.ts`、`test/mcp-standalone.test.ts`

**Interfaces:**
- `createGuardProxyServer({ client: GuardClient, upstream?: Client, principal, approvalTimeoutMs = 300000, resourceFromArg?: string })`：用 `@modelcontextprotocol/sdk/server/index.js` 的低层 `Server`，`setRequestHandler(ListToolsRequestSchema, …)` 透传 `upstream.listTools()`（无上游时返回五个治理工具）；`setRequestHandler(CallToolRequestSchema, …)`：构造 `GuardAction { principal, action: { name: tool }, resource: resourceFromArg ? { type: 'arg', id: String(args[resourceFromArg]) } : undefined, context: args }`，`client.check(action, { idempotencyKey: randomUUID() })`；ALLOW → `upstream.callTool({ name, arguments })` 原样返回；DENY → `{ isError: true, content: [{ type: 'text', text: 'Blocked by Aster Guard: <reason> (decision <id>)' }] }`；PENDING → `client.waitFor(decisionId, { timeoutMs: approvalTimeoutMs })` 后按结果转发或拒绝。
- 治理工具（zod 输入 schema 转 JSON schema）：`guard_check(action)`、`guard_status(decisionId)`、`guard_list_approvals(status?)`、`guard_approve(approvalId, comment?, businessRole?)`、`guard_reject(approvalId, comment, businessRole?)`。
- CLI：`aster-guard-mcp --upstream "<cmd> [args…]" --base-url … --api-key-env ASTER_GUARD_API_KEY --policy guard.customer/decide --principal-id agent-42 [--principal-roles a,b] [--approval-timeout 300] [--resource-from <argName>]`；缺必填项时打印用法并以非零退出；上游用 `StdioClientTransport({ command, args })`。

- [ ] **Step 1: 写失败测试**

`mcp-proxy.test.ts`：用 `InMemoryTransport.createLinkedPair()` 搭「假上游」（`McpServer` 注册一个 `echo` 工具返回输入）与「代理」（`createGuardProxyServer` 连接到另一对 in-memory transport），再用 SDK `Client` 连接代理：
- `tools/list 透传上游工具`。
- `ALLOW 时转发并返回上游结果`（`client.check` 用 mock GuardClient 返回 ALLOW）。
- `DENY 时返回 isError 且上游未被调用`。
- `PENDING→ALLOW：waitFor 返回 ALLOW 后转发`。
- `PENDING→超时：拒绝`。
- `guard 服务不可达（check 返回 client_error DENY）→ 拒绝，上游未被调用`（Review Focus 5）。
- `context 等于工具参数，action.name 等于工具名，resourceFromArg 抽取 resource.id`。

`mcp-standalone.test.ts`：无上游时 `tools/list` 返回五个工具；`guard_check` 调用 `client.check` 并把响应 JSON 作为文本返回。

- [ ] **Step 2: 运行确认失败；Step 3 实现；Step 4 GREEN + typecheck + lint + build；手动烟测：**

```bash
node dist/mcp/cli.js --help   # 打印用法，退出码 0
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(mcp): aster-guard-mcp 包裹式 MCP 代理与治理工具（ADR 0040 §6）"
```

---

### Task 11: 端到端脚本与 README 完善（aster-guard）

**Files:**
- Create: `scripts/e2e-local.mjs`（对本地 aster-api：部署规则需 DB 直写，脚本只做「已部署策略 + API key」前提下的 check / wait / approve 流程，参数从环境变量读取）
- Modify: `README.md`

- [ ] **Step 1**：README 加「本地端到端」一节与 Claude Desktop / Cursor 的 `mcpServers` 配置示例（`command: "npx"`, `args: ["-y", "@aster-cloud/aster-guard", "aster-guard-mcp", "--upstream", "...", …]`）。
- [ ] **Step 2**：`pnpm typecheck && pnpm lint && pnpm test && pnpm build`；提交 `docs: README 端到端与客户端配置示例`。

---

## 对 ADR 的修正（执行前必读）

| ADR 原文 | 代码事实 | 处理 |
|---|---|---|
| §4.2 用 `X-User-Business-Role` 匹配角色 | aster-api 没有任何代码读取该头，只有 cloud 在发 | Task 6 在审批端点以 `@HeaderParam` 读取；Task 7 在 ADR 注明其为可信 BFF 标签 |
| §5.2 `contextHash` 用 `CanonicalJson` | `CanonicalJson` 对非整数数字要求 `TypeContext`，Action context 是自由 Map | 改为 `sha256-sorted-json/v1`；Task 7 修正 ADR |
| §3.1 幂等 | 并发同键会撞唯一索引 | 先到者胜，后到者回读；Task 4/5 实现与测试 |
| §3.1 配额 | `enforceApiQuota` 是 `PolicyEvaluationResource` 私有方法 | Task 5 抽取为共享组件或直接注入 `ApiQuotaGuard`，报告中说明 |
| §6 `aster-guard` 远程仓库 | 创建 GitHub 仓库与推送是对外动作 | 计划只建本地仓库；创建远程与首发由用户决定 |

## 本地验证总顺序

1. aster-api：`./gradlew --offline :replay:test :test`（带 PG + Redis 容器）。
2. aster-guard：`pnpm typecheck && pnpm lint && pnpm test && pnpm build`。
3. 可选端到端：aster-api 本地启动 + `scripts/e2e-local.mjs`。
