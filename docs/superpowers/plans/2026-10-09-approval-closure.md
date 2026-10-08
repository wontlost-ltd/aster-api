# ADR 0042 审批闭环 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「需要人批」闭环：审批方业务角色由 aster-cloud 授予并经 verify/HMAC 下发、aster-api 只信这条链路；cloud 执行得到 REQUIRE_APPROVAL/ESCALATE 后以证据链收据为锚在 guard 开出待审批；dashboard 收件箱可审，证据包里的 guard-approval 复核者 `roleVerified=true`。

**Architecture:** aster-api 侧四块：评估事件补 `role/reason` 键；`guard_decisions.evidence_correlation_id` + `POST /guard/decisions/from-evidence`（只读链上事件建决策，不重复评估）；身份链路 `businessRoles`（verify/快照 → `ApiKeyAuthFilter` → `RequestIdentityResolver.businessRoles()`）与 guard 审批集合匹配；内部 HMAC canonical 升 v3（签 query、userId、businessRoles），HMAC 调用者身份只取签过的值。aster-cloud 侧：`TeamMember/User.businessRoles` 与授予 UI、verify/快照下发、`signInternalCallerHeaders` v3、执行路由调 from-evidence 写 `metadata.guardDecisionId`、收件箱页与审批路由、站内通知。

**Tech Stack:** aster-api：Quarkus、Panache、Flyway（下一个 V6.29.0）、RestAssured @QuarkusTest（容器 55433/6380）。aster-cloud：Next.js 16、Drizzle 手写迁移（下一个 0051）、vitest、next-intl `DEMO_SUPPLEMENT`。aster-guard：仅文档与类型。

**Spec:** `docs/adr/0042-approval-closure.md`（aster-api 仓库）。代码摘录参考：`/private/tmp/aster-stack/adr0042-refs.md`（编号 §1–§26，下文引用为「refs §n」）。

## Global Constraints

- 注释一律简体中文、只写意图与约束，禁止「修改说明」式注释；函数缩进 ≤ 3 层；沿用各文件既有 import 顺序。
- 提交信息末尾 trailer：`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。不推送。
- 业务角色词表（ADR §2.1）：每项 trim 后 1–64 字符、仅可打印 ASCII（0x20–0x7E）、去重保首次顺序、最多 16 项；与 Verdict `role` **大小写敏感精确匹配**。
- HMAC canonical v3（ADR §4.1）逐字：`method\npath\nquery\nts\nnonce\nsha256(body)\ntenant\nrole\nuserId\nbusinessRoles`，`businessRoles` 为排序后 `,` 拼接，缺省为空串；`query` 为原始 query 串（无 `?`），缺省空串。api 只认 v3，不与 v2 并存。
- 新增头：`X-User-Business-Roles`（逗号分隔，值与 canonical 一致）。
- from-evidence（ADR §3.1）：404 `evidence_not_found`、409 `evidence_not_approvable`（体含 `outcome`）、Bearer 调用 403 `internal_only`；幂等键 `(tenant_id, evidence_correlation_id)` 唯一索引。
- 角色不匹配 403 `role_mismatch` 响应体增加 `verifiedRoles`。
- cloud 执行：guard 调用失败不阻塞执行记录，`metadata.guardError = <code>`；`evidenceCorrelationId` 为空时 `guardError = 'no_evidence'`。
- 收件箱 `canAct = status==='PENDING' && (requiredRole==null || businessRoles(租户).includes(requiredRole))`；owner/admin 无角色只读；拒绝必须 comment。
- 通知种类 `guard.approval_requested`（角色持有者；ESCALATE 全员；个人租户本人）与 `guard.approval_decided`（发起人）；不发邮件。
- api @QuarkusTest 运行方式：`DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380 ./gradlew --offline :test --tests '...'`；绝不触碰 `aster-*` 容器。cloud 门禁：`export PATH=/opt/homebrew/opt/node@24/bin:$PATH; pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm test:run && pnpm check:locales:strict`。
- 已知基线失败（与本计划无关）：`PolicyGraphQLResourceTest.testBatchEvaluateLargeVolume`、`PrismaDatabaseE2ETest.test0`、`PolicyEvaluationE2ETest.test7`。

## 对 ADR 的修正（执行时裁决，Task 12 回写）

| # | ADR 条文 | 修正 | 理由 |
|---|---|---|---|
| 1 | §2.1 `PATCH /api/teams/[id]/members/[userId]` | 复用既有 `PUT /api/teams/[teamId]/members/[memberId]`（memberId = TeamMember.id），body 增加可选 `businessRoles` | 路由已存在，参数语义不同于 ADR 假设 |
| 2 | §2.1 `PATCH /api/settings/profile` | 新建 `PUT /api/user/business-roles` | 不存在 profile 更新路由 |
| 3 | §4.4 `createPolicyApiClient({ userId, tenantId, businessRoles })` | 保持位置参数，新增第 5 参 `businessRoles: string[] = []`；新增 `createPolicyApiClientForUser(tenantId, userId)` 实时查角色 | 13 个既有调用方按位置传参 |
| 4 | §2.3 `GuardApprovalService.checkRole` | 实际方法为 `checkRoleAndInput`，入口为 `decideApproval(...)`；参数 `String businessRole` 改为 `List<String> businessRoles` | 与代码一致 |
| 5 | §4.1 「版本标记改为 v3」 | 不加版本头；v3 仅体现在 canonical 行数 | 无既有版本头可改 |
| 6 | §3.1 `policy_version_id` 取事件 `policyVersionId` | 同；ADR 0041 本轮未写该键，故为 null，`policyVersion` 视图省略 | 无数据 |
| 8 | §3.1 请求含 `policyModule/policyFunction` | 去掉这两个字段：决策行的 module/function 取自链上评估事件的 `module_name/function_name` 列 | 事件已记录，cloud 无需解析源码 |
| 7 | §4.3 列表项字段 | `GuardApprovalView` 增 `policyModule, policyFunction, actionName, resourceType, resourceId, ruleId, controls, reason, evidenceCorrelationId`，需 join 决策；SDK `GuardApproval` 类型同步为可选字段 | — |

## Review Focus

1. 旧 cloud（未下发 `businessRoles`）→ api 必须按空集合处理，审批返回 `role_mismatch` 而非 500 —— Task 3 测试「缺字段解析为空列表」。
2. HMAC 调用者签了空 `userId` 去调 from-evidence/approve → 必须 400 `user_required`，不得以 `anonymous` 建决策 —— Task 6 测试。
3. `X-User-Business-Roles` 头值与 canonical 不一致（头被改）→ 403 `invalid_signature` —— Task 4 测试。
4. 同一 correlationId 并发两次 from-evidence → 唯一索引冲突时返回既有决策而非 500 —— Task 6 测试（两次串行 + 捕获 PersistenceException 回查）。
5. 收件箱用户在某租户 api 不可达 → 其他租户仍显示、该租户标不可用 —— Task 10 测试。

---

## aster-api（分支 `feat/adr-0042-approval-closure`，基于 `main`）

### Task 1: 评估事件补 `role` / `reason` 键

**Files:**
- Modify: `src/main/java/io/aster/policy/evidence/EvaluationEvidence.java`（refs §10）
- Modify: `src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java` 三处成功调用（refs §10 :246-264, :360-372, :628-650）与两处失败调用
- Modify: `docs/adr/0041-evidence-chain-enrichment.md` §2.2 键表（加两行）
- Test: `src/test/java/io/aster/policy/evidence/EvaluationEvidenceTest.java`、`src/test/java/io/aster/policy/rest/EvaluateEvidenceEventTest.java`

**Interfaces:**
- Produces: `EvaluationEvidence(String correlationId, String outcome, String ruleId, List<String> controls, Long policyVersionId, AgentIdentity agent, String role, String reason)`；`of(outcome, ruleId, controls, policyVersionId, agent, role, reason)`；旧 5 参 `of(...)` 保留并委托 `role=null, reason=null`；`toMetadata()` 新键 `role`（≤255）、`reason`（≤255），null/空不写。`KEY_ROLE = "role"`, `KEY_REASON = "reason"` 常量。

- [ ] **Step 1: 写失败测试**

`EvaluationEvidenceTest` 新增：
```java
  @Test void role与reason进扁平键且截到255() {
    String longReason = "r".repeat(300);
    var e = EvaluationEvidence.of("REQUIRE_APPROVAL", "R1", List.of(), null, null, "Data Protection Officer", longReason);
    Map<String, Object> m = e.toMetadata();
    assertThat(m).containsEntry(EvaluationEvidence.KEY_ROLE, "Data Protection Officer");
    assertThat((String) m.get(EvaluationEvidence.KEY_REASON)).hasSize(255);
  }
  @Test void role与reason为空时不写键() {
    var e = EvaluationEvidence.of("ALLOW", null, null, null, null);
    assertThat(e.toMetadata()).doesNotContainKeys(EvaluationEvidence.KEY_ROLE, EvaluationEvidence.KEY_REASON);
  }
```
`EvaluateEvidenceEventTest`：在既有 `/evaluate` 探针用例（PROBE-001 返回 REQUIRE_APPROVAL）的事件断言里加 `assertThat(metadata).containsEntry("role", "Data Protection Officer")` 与 `containsKey("reason")`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew --offline :test --tests 'io.aster.policy.evidence.EvaluationEvidenceTest'`
Expected: 编译失败（7 参 `of` 不存在）

- [ ] **Step 3: 实现**

`EvaluationEvidence`：record 末尾加 `String role, String reason`；
```java
  public static final String KEY_ROLE = "role";
  public static final String KEY_REASON = "reason";

  public static EvaluationEvidence of(String outcome, String ruleId, List<String> controls, Long policyVersionId,
                                      AgentIdentity agent) {
    return of(outcome, ruleId, controls, policyVersionId, agent, null, null);
  }

  /** role/reason 来自 Verdict（ADR 0042 §3.2）：from-evidence 据此建决策，不重复评估。 */
  public static EvaluationEvidence of(String outcome, String ruleId, List<String> controls, Long policyVersionId,
                                      AgentIdentity agent, String role, String reason) {
    return new EvaluationEvidence(UUID.randomUUID().toString(), outcome, ruleId, controls, policyVersionId, agent,
        role, reason);
  }
```
`toMetadata()` 在 `policyVersionId` 之后：`put(m, KEY_ROLE, clip(role)); put(m, KEY_REASON, clip(reason));`。

`PolicyEvaluationResource` 三处成功调用改用 7 参：`/evaluate` 处 `VerdictView v = VerdictView.from(result.getResult()).orElse(null)`（该文件已有 `outcomeOf(...)` 读 decision；若 `result.getDecision()` 已是 `VerdictView`，直接 `decision.role()/decision.reason()`），传 `v == null ? null : v.role(), v == null ? null : v.reason()`；`/evaluate-json` 与 `/evaluate-source` 处已有 `decision` 变量，同样传 `decision.role()/decision.reason()`（null 安全）。失败调用保持 5 参。

- [ ] **Step 4: 运行通过**

Run: `DB_JDBC_URL=... ./gradlew --offline :test --tests 'io.aster.policy.evidence.*' --tests 'io.aster.policy.rest.EvaluateEvidenceEventTest' --tests 'io.aster.policy.rest.PolicyEvaluationReplayOrderingTest'`
Expected: PASS（golden 不受影响：`role/reason` 只在 metadata，不在响应）

- [ ] **Step 5: ADR 0041 §2.2 键表加 `role`、`reason` 两行（标注「ADR 0042 §3.2 补」），提交**

```bash
git add -A && git commit -m "feat(evidence): 评估事件补 role/reason 键（ADR 0042 §3.2）"
```

### Task 2: `guard_decisions.evidence_correlation_id` 与 V6.29.0

**Files:**
- Create: `src/main/resources/db/migration/V6.29.0__guard_decisions_evidence_correlation.sql`
- Modify: `src/test/resources/db/migration-checksums.golden`（追加一行，格式见 refs §9）
- Modify: `src/main/java/io/aster/guard/GuardDecisionEntity.java`（refs §7）、`src/main/java/io/aster/guard/GuardDecisionStore.java`
- Test: `src/test/java/io/aster/guard/GuardDecisionStoreEvidenceTest.java`（新，@QuarkusTest）、`src/test/java/io/aster/db/MigrationGovernanceTest.java`

**Interfaces:**
- Produces: `GuardDecisionEntity.evidenceCorrelationId`（`evidence_correlation_id VARCHAR(64)`）；`Optional<GuardDecisionEntity> GuardDecisionStore.findByEvidenceCorrelationId(String tenantId, String correlationId)`；`GuardDecisionStore.saveDecision` 在唯一索引冲突时抛既有 `GuardIdempotencyRaceException`（查看其现有触发条件，复用）。

- [ ] **Step 1: 写失败测试**

```java
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class GuardDecisionStoreEvidenceTest {
  static final String TENANT = "t-evidence-" + UUID.randomUUID();
  @Inject GuardDecisionStore store;

  @BeforeEach void clean() {
    QuarkusTransaction.requiringNew().run(() -> {
      GuardApprovalEntity.delete("tenantId = ?1", TENANT);
      GuardDecisionEntity.delete("tenantId = ?1", TENANT);
    });
  }

  @Test void 按证据相关键查回决策_跨租户不可见_重复键抛竞争异常() {
    GuardDecisionEntity e = minimal(TENANT, "cid-1");
    store.saveDecision(e, null);
    assertThat(store.findByEvidenceCorrelationId(TENANT, "cid-1")).map(d -> d.id).contains(e.id);
    assertThat(store.findByEvidenceCorrelationId("other-" + TENANT, "cid-1")).isEmpty();
    assertThatThrownBy(() -> store.saveDecision(minimal(TENANT, "cid-1"), null))
        .isInstanceOf(GuardIdempotencyRaceException.class);
  }

  /** 最小合法决策行：response_json 等 NOT NULL 列给占位值。 */
  static GuardDecisionEntity minimal(String tenant, String cid) {
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.id = UUID.randomUUID(); e.tenantId = tenant; e.policyModule = "m"; e.policyFunction = "f";
    e.principalId = "p"; e.actionName = "a"; e.contextHash = "0".repeat(64); e.outcome = "REQUIRE_APPROVAL";
    e.responseJson = "{}"; e.evidenceCorrelationId = cid;
    return e;
  }
}
```

- [ ] **Step 2: 运行确认失败**（编译失败：字段/方法不存在）

- [ ] **Step 3: 实现**

迁移：
```sql
-- ADR 0042 §3.1：从证据开决策时记录锚定的评估事件相关键；同租户同相关键只能开一条决策。
ALTER TABLE guard_decisions ADD COLUMN evidence_correlation_id VARCHAR(64);
CREATE UNIQUE INDEX guard_decisions_evidence_uq ON guard_decisions (tenant_id, evidence_correlation_id)
    WHERE evidence_correlation_id IS NOT NULL;
```
实体：`@Column(name = "evidence_correlation_id") public String evidenceCorrelationId;`
Store：
```java
  /** 按租户 + 证据相关键取决策（from-evidence 幂等重放）。 */
  public Optional<GuardDecisionEntity> findByEvidenceCorrelationId(String tenantId, String correlationId) {
    return GuardDecisionEntity.find("tenantId = ?1 and evidenceCorrelationId = ?2", tenantId, correlationId)
        .firstResultOptional();
  }
```
`saveDecision` 的唯一约束捕获：读现有实现——若它已按 `ConstraintViolationException` 转 `GuardIdempotencyRaceException`，无需改；若只按索引名匹配 `guard_decisions_idem_uq`，把 `guard_decisions_evidence_uq` 加进同一判定。

- [ ] **Step 4: 跑 `MigrationGovernanceTest`（首跑给出 golden 行）、新测试、`io.aster.guard.*`，全部 PASS；提交**

```bash
git commit -am "feat(guard): guard_decisions.evidence_correlation_id 与唯一索引（ADR 0042 §3.1）"
```

### Task 3: 身份链路携带 `businessRoles`

**Files:**
- Modify: `src/main/java/io/aster/security/apikey/ApiKeyVerifyResult.java`（refs §3）、`src/main/java/io/aster/billing/snapshot/ApiKeySnapshot.java`、`src/main/java/io/aster/security/apikey/ApiKeyVerifierService.java`（fetchFromCloud :405-426、快照转换 :244-252）、`src/main/java/io/aster/security/apikey/ApiKeyAuthFilter.java`（:143-162）、`src/main/java/io/aster/policy/rest/RequestIdentityResolver.java`（refs §4）
- Test: `src/test/java/io/aster/security/apikey/ApiKeyVerifyResultTest.java`、`src/test/java/io/aster/billing/snapshot/ApiKeySnapshotTest.java`、`src/test/java/io/aster/security/apikey/ApiKeyAuthFilterTenantOverwriteTest.java`、`src/test/java/io/aster/policy/rest/RequestIdentityResolverTest.java`（若无则新建单测）

**Interfaces:**
- Produces: `ApiKeyVerifyResult` 末尾组件 `List<String> businessRoles`（不可变，缺省空）；`valid(apiKeyId,userId,tenantId,plan,subscriptionStatus,role,quotaOwnerId,businessRoles)` 8 参；7 参/6 参重载委托空列表。`ApiKeySnapshot` 末尾 `@JsonInclude(NON_EMPTY) List<String> businessRoles`（compact ctor 归一为不可变、null→空）；`fromJson` 读 `businessRoles` 数组（缺省空）。`RequestIdentityResolver.PROP_BUSINESS_ROLES = "aster.identity.businessRoles"`，`List<String> businessRoles()`：读属性；无属性时读头 `X-User-Business-Roles`（逗号切分 trim 去空）；无则空列表。`ApiKeyAuthFilter` 写该属性并 `putSingle("X-User-Business-Roles", String.join(",", roles))`，`X-User-Business-Role` 覆盖为首项或空串。

- [ ] **Step 1: 写失败测试**

`ApiKeyVerifyResultTest`：
```java
  @Test void 七参valid的businessRoles为空列表_八参保留顺序去null() {
    assertThat(ApiKeyVerifyResult.valid("k","u","t","pro","active","MEMBER","u").businessRoles()).isEmpty();
    var r = ApiKeyVerifyResult.valid("k","u","t","pro","active","MEMBER","u", Arrays.asList("DPO", null, "CISO"));
    assertThat(r.businessRoles()).containsExactly("DPO", "CISO");
    assertThatThrownBy(() -> r.businessRoles().add("x")).isInstanceOf(UnsupportedOperationException.class);
  }
```
`ApiKeySnapshotTest`：`fromJson` 带 `"businessRoles":["DPO"]` → 列表；不带 → 空；序列化个人 key 快照（空列表）JSON **不含** `businessRoles` 键（与旧 pod 逐字节兼容）。
`ApiKeyAuthFilterTenantOverwriteTest` 新增：seed 的 result 带 `List.of("Data Protection Officer")`，请求自带 `X-User-Business-Role: CEO` 与 `X-User-Business-Roles: CEO,CFO` → 下游看到的头分别为 `Data Protection Officer` 与 `Data Protection Officer`，属性 `aster.identity.businessRoles` 为该列表（该测试类已有读取下游头的探针资源，沿用）。
`RequestIdentityResolverTest`（单测，mock `ContainerRequestContext`）：属性优先；无属性时头 `" DPO , ,CISO"` → `["DPO","CISO"]`；无头 → 空。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**（`ApiKeyVerifierService.fetchFromCloud` 读 `json.getJsonArray("businessRoles")` 转 `List<String>`，非字符串元素跳过；快照→result 转换传 `s.businessRoles()`；`ApiKeyAuthFilter` 在覆盖 `X-User-Role` 之后：
```java
        // 业务角色（ADR 0042 §2.2）：只信 verify/快照下发的集合；调用方自报的头一律覆盖。
        List<String> roles = result.businessRoles();
        ctx.setProperty(RequestIdentityResolver.PROP_BUSINESS_ROLES, roles);
        ctx.getHeaders().putSingle("X-User-Business-Roles", String.join(",", roles));
        ctx.getHeaders().putSingle("X-User-Business-Role", roles.isEmpty() ? "" : roles.get(0));
```
`PROP_BUSINESS_ROLES` 改为 `public static final`。）

- [ ] **Step 4: 跑 `io.aster.security.apikey.*`、`io.aster.billing.snapshot.*`、`io.aster.policy.rest.RequestIdentityResolverTest`，PASS；提交**

```bash
git commit -am "feat(identity): verify/快照下发 businessRoles，过滤器写入已验证角色集合（ADR 0042 §2.2）"
```

### Task 4: 内部 HMAC canonical v3（query、userId、businessRoles）

**Files:**
- Modify: `src/main/java/io/aster/security/apikey/InternalCallerFilter.java`（refs §1 :322-360）
- Modify: `src/main/java/io/aster/policy/rest/RequestIdentityResolver.java`（`performedBy()`、新 `PROP_IDENTITY_USER_ID`）
- Test: `src/test/java/io/aster/security/apikey/InternalCallerFilterTest.java`、所有自签 v2 canonical 的测试助手（`grep -rln '"\\n" + tenant' src/test` 与 `grep -rln 'X-Internal-Signature' src/test`：至少 `AuditReceiptHmacAuthTest.signedGet`、`EvaluateEvidenceEventTest`、`EvaluateSourceSimulateContractTest`、`PolicyEvaluationReplayOrderingTest`、What-If 相关测试）——统一改为调用一个新的测试助手 `src/test/java/io/aster/security/apikey/InternalCallerSigning.java`（`static Map<String,String> headers(String key, String method, String path, String query, String body, String tenant, String role, String userId, List<String> roles)`）。

**Interfaces:**
- Produces: canonical v3（Global Constraints）；验签通过后设置属性 `RequestIdentityResolver.PROP_IDENTITY_USER_ID = "aster.identity.userId"`（签过的 `X-User-Id`，可为空串）与 `PROP_BUSINESS_ROLES`（签过的角色列表）。`RequestIdentityResolver.performedBy()` 优先级：`aster.apikey.userId` → `aster.identity.userId`（非空）→ 头 → anonymous；`businessRoles()` 读 `PROP_BUSINESS_ROLES`（两条通道写同一属性）。

- [ ] **Step 1: 写失败测试**

`InternalCallerFilterTest`（现有 v2 用例改 v3）新增：
```java
  @Test void v3签名绑定query_userId_角色_任一被改则403() {
    // 基准：GET /api/v1/audit/receipts?correlationIds=c1，userId=u-1，roles=[DPO,CISO]
    // 1) 原样 → 200；2) query 改成 correlationIds=c2 → 403 invalid_signature；
    // 3) X-User-Id 改成 u-2 → 403；4) X-User-Business-Roles 改成 CISO → 403；
    // 5) 头角色顺序 "CISO,DPO" 但 canonical 用排序后 "CISO,DPO" → 200（头顺序无关，canonical 固定排序）
  }
```
（按该测试类既有的 `classify`/filter 调用方式实现；每个分支一个断言。）
`AuditReceiptHmacAuthTest.signedGet` 改用 `InternalCallerSigning.headers(..., "correlationIds=c-hmac", "", tenant, "member", "evidence-export", List.of())`。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

```java
        final String query = ctx.getUriInfo().getRequestUri().getRawQuery();
        final String userId = headerOrEmpty(ctx, "X-User-Id");
        final List<String> roles = parseRoles(ctx.getHeaderString("X-User-Business-Roles"));
        ...
                String canonical = String.join("\n", method, p, query == null ? "" : query, ts, nonce, bodySha,
                    tenant, role, userId, String.join(",", roles));
        ...
                ctx.setProperty(HMAC_VERIFIED_PROP, Boolean.TRUE);
                ctx.setProperty(VERIFIED_BODY_PROP, body);
                // 身份只取签过的值（ADR 0042 §4.1）：HMAC 调用者的用户与业务角色不再从未签头回退。
                ctx.setProperty(RequestIdentityResolver.PROP_IDENTITY_USER_ID, userId);
                ctx.setProperty(RequestIdentityResolver.PROP_BUSINESS_ROLES, roles);
```
`parseRoles`：null/空 → `List.of()`；逗号切分、trim、去空、`sorted()`、去重、不可变。
`RequestIdentityResolver.performedBy()`：在 `jaxrsProperty(PROP_USER_ID)` 之后加 `String signed = jaxrsProperty(PROP_IDENTITY_USER_ID); if (signed != null && !signed.isBlank()) return signed;`。

- [ ] **Step 4: 跑 `io.aster.security.apikey.*` 与所有改过助手的测试类，PASS；提交**

```bash
git commit -am "feat(security): 内部 HMAC canonical v3 签 query/userId/businessRoles，身份只取签过的值（ADR 0042 §4.1）"
```

### Task 5: guard 审批按已验证角色集合匹配 + 列表项扩展

**Files:**
- Modify: `src/main/java/io/aster/guard/GuardApprovalService.java`（refs §5）、`src/main/java/io/aster/guard/api/GuardApprovalResource.java`、`src/main/java/io/aster/guard/api/GuardApprovalView.java`（refs §6）、`src/main/java/io/aster/guard/GuardDecisionStore.java`（新 `findDecisions(String tenantId, Collection<UUID> ids)`）
- Test: `src/test/java/io/aster/guard/GuardApprovalServiceTest.java`、`src/test/java/io/aster/guard/api/GuardApprovalResourceTest.java`（refs §12）

**Interfaces:**
- Produces: `decideApproval(String tenantId, UUID approvalId, String decidedBy, String approverApiKeyId, List<String> businessRoles, boolean approve, String comment)`；`GuardApprovalConflictException` 带可选 `Map<String,Object> details`（`role_mismatch` 时 `{"verifiedRoles": [...]}`，资源层并入错误体）；`GuardApprovalView` 新增可空字段 `policyModule, policyFunction, actionName, resourceType, resourceId, ruleId, List<String> controls, reason, evidenceCorrelationId`，静态 `of(GuardApprovalEntity a, GuardDecisionEntity d)`（`d` 可为 null）；`service.list(...)` 返回 `List<GuardApprovalView>`（内部一次 `findDecisions` 批量 join）。

- [ ] **Step 1: 写失败测试**

`GuardApprovalResourceTest`：
- 既有「DPO 批准」用例改为用 `X-User-Business-Roles: Data Protection Officer`（测试配置 apikey 关闭，resolver 读头）→ 200；
- 新增 `自报单值头不再放行`：只带 `X-User-Business-Role: Data Protection Officer`、不带复数头 → 403 `role_mismatch`，体含 `verifiedRoles: []`；
- 新增 `多角色集合含所需角色即可`：`X-User-Business-Roles: CISO,Data Protection Officer` → 200；
- 新增 `列表项含决策字段`：`items[0].actionName == "delete_customer_record"`、`ruleId == "CUST-DEL-001"`、`controls == ["GDPR:ART17"]`、`policyModule == MODULE`。
`GuardApprovalServiceTest`：ESCALATE（requiredRole null）空集合也通过；`comment_required` 不变。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

```java
  /** 角色集合匹配（ADR 0042 §2.3）：所需角色须在已验证集合内；ESCALATE 无所需角色，任何已验证成员可审。 */
  private static void checkRoleAndInput(GuardApprovalEntity a, String decidedBy, List<String> businessRoles,
                                        boolean approve, String comment) {
    if (a.requiredRole != null && !businessRoles.contains(a.requiredRole)) {
      throw conflict("role_mismatch", "该审批需要业务角色: " + a.requiredRole, Map.of("verifiedRoles", businessRoles));
    }
    ...
```
资源层 `decide(...)` 调 `identityResolver.businessRoles()`，删除 `@HeaderParam("X-User-Business-Role")`；`error(...)` 合并 `e.details()`。`list` 改为 `service.list(...)` 直接返回视图：service 取审批页 → `store.findDecisions(tenant, decisionIds)` → `GuardApprovalView.of(a, byId.get(a.decisionId))`。

- [ ] **Step 4: 跑 `io.aster.guard.*`，PASS；提交**

```bash
git commit -am "feat(guard): 审批按已验证业务角色集合匹配，列表项带决策字段（ADR 0042 §2.3/§4.3）"
```

### Task 6: `POST /api/v1/guard/decisions/from-evidence` 与通道开放

**Files:**
- Create: `src/main/java/io/aster/guard/api/GuardFromEvidenceRequest.java`、`src/main/java/io/aster/guard/GuardEvidenceDecisionService.java`
- Modify: `src/main/java/io/aster/guard/api/GuardDecisionResource.java`（新方法）、`src/main/java/io/aster/security/apikey/InternalCallerFilter.java`（classify：guard 内部路径集合）、`src/main/java/io/aster/security/apikey/ApiKeyAuthFilter.java`（Bearer 旁路改为路径集合 `HMAC_BYPASS_PATHS`）、`src/test/resources/api/public-api-contract.golden`
- Test: `src/test/java/io/aster/guard/api/GuardFromEvidenceResourceTest.java`（新，@QuarkusTest，`AuditReceiptHmacAuthTest.AuthProfile` 同款 profile 开 apikey + HMAC）、`InternalCallerFilterTest`（classify 用例）

**Interfaces:**
- Produces: 请求记录 `GuardFromEvidenceRequest(String correlationId, GuardActionRequest.Action action)`（module/function 取自事件行，修正 #8）；服务 `GuardDecisionResponse createFromEvidence(String tenantId, String performedBy, GuardFromEvidenceRequest req)`；错误经既有 `GuardApprovalConflictException` 码：`evidence_not_found`(404)、`evidence_not_approvable`(409, details `{outcome}`)、`user_required`(400)、`invalid_action`(400)。classify：`/api/v1/guard/decisions/from-evidence` 恒 REQUIRE_HMAC；`/api/v1/guard/approvals`、`/api/v1/guard/approvals/{id}/approve|reject`、`/api/v1/guard/decisions/{id}` 带内部调用头时 REQUIRE_HMAC、否则 NOT_PROTECTED（同 receipts 先例）。`ApiKeyAuthFilter.HMAC_BYPASS_PATHS`：精确路径集合 + 前缀 `/api/v1/guard/approvals/`、`/api/v1/guard/decisions/`（排除 `POST /api/v1/guard/decisions` 本身）。

- [ ] **Step 1: 写失败测试**

```java
  @Test void HMAC从证据开决策_响应收据指向评估事件_重复调用同一决策() {
    String cid = appendEvaluation(TENANT, "REQUIRE_APPROVAL", "Data Protection Officer");   // 用 AuditChainAppender 写一条含 role 的 POLICY_EVALUATION
    Response r1 = signedPost("/api/v1/guard/decisions/from-evidence", body(cid), TENANT, "u-req", List.of());
    assertThat(r1.statusCode()).isEqualTo(200);
    assertThat(r1.jsonPath().getString("outcome")).isEqualTo("REQUIRE_APPROVAL");
    assertThat(r1.jsonPath().getString("approval.requiredRole")).isEqualTo("Data Protection Officer");
    assertThat(r1.jsonPath().getLong("receipt.auditId")).isEqualTo(auditIdOf(cid));
    Response r2 = signedPost(...同 body...);
    assertThat(r2.jsonPath().getString("decisionId")).isEqualTo(r1.jsonPath().getString("decisionId"));
  }
  @Test void 非可审批事件409_不存在404_跨租户404_Bearer403_空userId400() {
    String allow = appendEvaluation(TENANT, "ALLOW", null);
    assertThat(signedPost(FROM_EVIDENCE, body(allow), TENANT, "u-req", List.of()).statusCode()).isEqualTo(409);
    assertThat(signedPost(FROM_EVIDENCE, body(allow), TENANT, "u-req", List.of()).jsonPath().getString("outcome")).isEqualTo("ALLOW");
    assertThat(signedPost(FROM_EVIDENCE, body("no-such-cid"), TENANT, "u-req", List.of()).statusCode()).isEqualTo(404);
    String cid = appendEvaluation(TENANT, "REQUIRE_APPROVAL", "Data Protection Officer");
    assertThat(signedPost(FROM_EVIDENCE, body(cid), OTHER, "u-req", List.of()).statusCode()).isEqualTo(404);
    assertThat(given().header("Authorization", "Bearer " + BEARER_KEY).contentType("application/json").body(body(cid))
        .post(FROM_EVIDENCE).statusCode()).isEqualTo(403);
    assertThat(signedPost(FROM_EVIDENCE, body(cid), TENANT, "", List.of()).statusCode()).isEqualTo(400);
  }
  @Test void 开出的待审批可由持角色的HMAC用户批准_发起人自批403() {
    // approve: signedPost("/api/v1/guard/approvals/{id}/approve", "{}", TENANT, "u-dpo", List.of("Data Protection Officer")) → 200 effectiveOutcome ALLOW
    // 自批: userId=u-req → 403 segregation_of_duties
  }
```

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

```java
@ApplicationScoped
public class GuardEvidenceDecisionService {
  static final Set<String> APPROVABLE = Set.of("REQUIRE_APPROVAL", "ESCALATE");

  @Transactional
  public GuardDecisionResponse createFromEvidence(String tenantId, String performedBy, GuardFromEvidenceRequest req) {
    if (performedBy == null || performedBy.isBlank() || "anonymous".equals(performedBy)) {
      throw conflict("user_required", "from-evidence 需要签名内的用户身份");
    }
    Optional<GuardDecisionEntity> existing = store.findByEvidenceCorrelationId(tenantId, req.correlationId());
    if (existing.isPresent()) return json.readResponse(existing.get().responseJson);
    AuditLog event = AuditLog.findByCorrelationIds(tenantId, List.of(req.correlationId())).stream()
        .filter(r -> EventType.POLICY_EVALUATION.name().equals(r.eventType)).findFirst()
        .orElseThrow(() -> conflict("evidence_not_found", "链上无此评估事件"));
    Map<String, Object> m = metadataOf(event);           // 解析失败 → evidence_not_approvable
    String outcome = str(m.get("outcome"));
    if (!APPROVABLE.contains(outcome)) {
      throw conflict("evidence_not_approvable", "评估结论不需审批", Map.of("outcome", String.valueOf(outcome)));
    }
    GuardDecisionDraft draft = draftOf(tenantId, event.policyModule, event.policyFunction, req, m, outcome);   // module/function 取事件列；复用 GuardActionMapper.flatten + AgentIdentity 校验（invalid_action）
    UUID decisionId = UUID.randomUUID(); UUID approvalId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plus(approvalTtl);
    AppendedRecord receipt = new AppendedRecord(event.id, event.currentHash, event.prevHash);  // 若 AppendedRecord 不可直接构造，用 ReceiptView 三元组
    GuardDecisionResponse response = buildResponse(decisionId, draft, receipt, approvalId, expiresAt);
    GuardDecisionEntity e = toEntity(decisionId, draft, receipt, response, performedBy, null);
    e.evidenceCorrelationId = req.correlationId();
    try {
      store.saveDecision(e, toApproval(approvalId, decisionId, draft, expiresAt));
    } catch (GuardIdempotencyRaceException race) {
      return store.findByEvidenceCorrelationId(tenantId, req.correlationId()).map(d -> json.readResponse(d.responseJson))
          .orElseThrow(() -> race);
    }
    return response;
  }
}
```
`buildResponse/toEntity/toApproval` 从 `GuardDecisionService` 抽成包级静态（或移到新 `GuardDecisionAssembler`）供两处共用，不复制。`draftOf`：`policyVersion` 为 null（修正 #6）；`role = m.get("role")`、`reason = m.get("reason")`、`ruleId`、`controls`（List）从 metadata 取；`agent` 用 `AgentIdentity.validated/ofModel` 同 `GuardDecisionService.declaredAgent`（提成共享静态）。
资源：
```java
  /** 从证据开决策（ADR 0042 §3.1）：仅内部 HMAC 通道；收据即评估事件。 */
  @POST @Path("/from-evidence")
  public Response fromEvidence(@Valid GuardFromEvidenceRequest req) {
    if (!InternalCallerFilter.isHmacVerified(ctx)) return error(FORBIDDEN, "internal_only", "仅限内部调用者");
    try {
      return Response.ok(evidenceService.createFromEvidence(identityResolver.tenantId(), identityResolver.performedBy(), req)).build();
    } catch (GuardApprovalConflictException e) { return error(statusOf(e.code()), e); }
  }
```
`statusOf` 扩展：`evidence_not_found → 404`、`evidence_not_approvable → 409`、`user_required/invalid_action → 400`。无配额扣减（内部调用）。golden 首跑追加 `POST /api/v1/guard/decisions/from-evidence`，提交。

- [ ] **Step 4: 跑 `io.aster.guard.*`、`io.aster.security.apikey.*`、`PublicApiContractTest`，PASS；提交**

```bash
git commit -am "feat(guard): POST /guard/decisions/from-evidence 以评估事件为锚开决策；guard 审批对内部 HMAC 通道开放（ADR 0042 §3/§4.2）"
```

---

## aster-cloud（分支 `feat/adr-0042-approval-inbox`，基于 `main`）

### Task 7: 业务角色数据、授予入口与下发

**Files:**
- Create: `drizzle/0051_business_roles.sql`、`src/lib/business-roles.ts`、`src/app/api/user/business-roles/route.ts`、`src/components/settings/business-roles-panel.tsx`
- Modify: `drizzle/meta/_journal.json`（idx 51，when 1789600000000，tag `0051_business_roles`）、`src/db/schema.ts`（users、teamMembers）、`src/app/api/teams/[teamId]/members/[memberId]/route.ts`（refs §17）、`src/app/[locale]/(dashboard)/teams/[teamId]/members/page.tsx`（成员行增加角色编辑）、`src/app/[locale]/(dashboard)/settings/page.tsx`（挂 panel）、`src/lib/api-key-identity.ts`（refs §15）、`src/app/api/internal/apikey/verify/route.ts`、`src/i18n/demo-supplement.ts`（四语 `businessRoles.*`、`teams.members.businessRoles*`）
- Test: `src/__tests__/lib/business-roles.test.ts`、`src/__tests__/api/team-members-business-roles.test.ts`、`src/__tests__/api/user-business-roles-route.test.ts`、`src/__tests__/lib/api-key-identity.test.ts`（加角色断言）、`src/__tests__/app/api/internal/apikey-verify-route.test.ts`

**Interfaces:**
- Produces: `users.businessRoles: text('businessRoles').array().notNull().default([])`、`teamMembers.businessRoles` 同；`normalizeBusinessRoles(input: unknown): string[]`（非数组/非字符串项 → 抛 `BusinessRoleError('invalid_business_role')`；规则见 Global Constraints）；`ApiKeyIdentity`(valid) 增 `businessRoles: string[]`；`ApiKeySnapshotBody`(valid) 增 `businessRoles: string[]`（空数组时**省略键**，与 api 快照 NON_EMPTY 对称）；verify 响应增 `businessRoles`；`loadBusinessRoles(userId: string, tenantId: string): Promise<string[]>`（tenantId===userId → users 表；否则 teamMembers 行；无行 → []）导出自 `src/lib/business-roles.ts`。

- [ ] **Step 1: 写失败测试**

```ts
describe('normalizeBusinessRoles', () => {
  it('trim、去重保序、上限 16', () => {
    expect(normalizeBusinessRoles([' DPO ', 'DPO', 'CISO'])).toEqual(['DPO', 'CISO']);
    expect(() => normalizeBusinessRoles(Array.from({ length: 17 }, (_, i) => `r${i}`))).toThrow('invalid_business_role');
  });
  it('非 ASCII、空串、超 64 字符、非字符串全部拒绝', () => {
    for (const bad of [['数据保护官'], [''], ['x'.repeat(65)], [1 as unknown as string], 'DPO' as unknown as string[]]) {
      expect(() => normalizeBusinessRoles(bad)).toThrow('invalid_business_role');
    }
  });
});
```
路由测试（hand-mock 模式同 `whatif-batches-route.test.ts`）：PUT 成员 `{ businessRoles: ['DPO'] }` 由 admin → 200 且 `db.update` 收到 `{ businessRoles: ['DPO'] }` 且不改 role；member 调用 → 403；非法角色 → 400 `invalid_business_role`；`PUT /api/user/business-roles` 未登录 401、合法 200。verify 路由测试：团队 key 响应含 `businessRoles` 取自 TeamMember；个人 key 取自 users。`toApiKeySnapshotBody` 空角色不含键。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**

迁移：
```sql
-- ADR 0042 §2.1：业务角色挂在团队成员上（个人租户挂在用户上），默认空。
ALTER TABLE "TeamMember" ADD COLUMN IF NOT EXISTS "businessRoles" text[] NOT NULL DEFAULT '{}';
--> statement-breakpoint
ALTER TABLE "User" ADD COLUMN IF NOT EXISTS "businessRoles" text[] NOT NULL DEFAULT '{}';
```
`business-roles.ts`：
```ts
const ROLE_RE = /^[\x20-\x7E]{1,64}$/;
export const MAX_BUSINESS_ROLES = 16;
export class BusinessRoleError extends Error { constructor() { super('invalid_business_role'); } }
/** 词表归一（ADR 0042 §2.1）：trim、可打印 ASCII 1–64、去重保序、≤16；任何一项不合法整体拒绝。 */
export function normalizeBusinessRoles(input: unknown): string[] { ... }
export async function loadBusinessRoles(userId: string, tenantId: string): Promise<string[]> { ... }
```
成员 PUT：body 解析 `{ role?, businessRoles? }`；两者至少其一；`businessRoles` 存在时 `normalizeBusinessRoles` 后 `set({ businessRoles })`；权限沿用 `MEMBER_UPDATE_ROLE`；更新后沿用既有「重推该成员 key 快照」逻辑（角色随快照下发）。个人路由 `PUT /api/user/business-roles`：session 用户，body `{ businessRoles }`，更新 users，并重推该用户个人 key 快照（沿用同一 helper）。`identityOf`：个人 `businessRoles: holder.businessRoles`，团队 `businessRoles: membership.businessRoles`（`roleByMembership` 改为存整行）。UI：成员行加「业务角色」chip 编辑（逗号输入框 + 保存，owner/admin 可见）；设置页 panel 同款。i18n 四语。

- [ ] **Step 4: 跑相关测试 + 全门禁，PASS；提交**

```bash
git commit -am "feat(teams): 团队成员/个人业务角色，授予入口与 verify/快照下发（ADR 0042 §2）"
```

### Task 8: `signInternalCallerHeaders` v3 与 `PolicyApiClient` guard 方法

**Files:**
- Modify: `src/lib/api-signing.ts`（refs §14）、`src/services/policy/policy-api.ts`、所有 `signInternalCallerHeaders` 调用方（refs §14 NOTE：`llm/complete`、`playground/evaluate-source`、`llm-sse-proxy`、`backend-version`、`receipts-client`、`policy-api`；`signRunnerLauncherHeaders` 同步 v3）
- Create: `src/lib/policy-api-identity.ts`（`createPolicyApiClientForUser`）
- Test: `src/__tests__/lib/api-signing-internal.test.ts`、`src/__tests__/services/policy-api-guard.test.ts`（新）、受影响的既有签名测试

**Interfaces:**
- Produces: `signInternalCallerHeaders(method, path, body?, tenantId?, role?, identity?: { query?: string; userId?: string; businessRoles?: string[] })`，返回头增加 `X-User-Business-Roles`（排序后逗号拼接；无则不加头但 canonical 用空串）；canonical v3 逐字见 Global Constraints。`PolicyApiClient` 第 5 参 `businessRoles: string[] = []`；`request()` 对 `/api/v1/guard/*` 路径也签名（`needsInternalCaller`），query 从 `url` 取 `search.slice(1)`；新方法：
  - `guardFromEvidence(req: GuardFromEvidenceRequest): Promise<GuardDecision>`（POST `/api/v1/guard/decisions/from-evidence`）
  - `getGuardDecision(id: string): Promise<GuardDecision>`
  - `listGuardApprovals(status: GuardApprovalStatus, page = 0, size = 50): Promise<GuardApprovalPage>`
  - `approveGuard(id: string, comment?: string): Promise<GuardDecision>`；`rejectGuard(id: string, comment: string): Promise<GuardDecision>`
  - 类型在 `src/services/policy/guard-types.ts`：`GuardDecision`（同 api `GuardDecisionResponse` 字段）、`GuardApprovalItem`（含 Task 5 新字段）、`GuardApprovalPage`、`GuardFromEvidenceRequest = { correlationId: string; action: GuardAction }`。非 2xx 抛 `PolicyApiError(message, status, code, undefined, details?)`（`PolicyApiError` 增可选 `details`）。
  - `createPolicyApiClientForUser(tenantId, userId, userRole = 'member')`：`loadBusinessRoles(userId, tenantId)` 后 `new PolicyApiClient(tenantId, userId, userRole, 'unknown', roles)`。

- [ ] **Step 1: 写失败测试**

`api-signing-internal.test.ts`：固定 `ASTER_PLAN_GATE_HMAC_KEY`、固定 nonce/时间（mock `generateNonce`/`Date.now`），断言 canonical 为 `"GET\n/api/v1/guard/approvals\nstatus=PENDING&page=0&size=50\n<ts>\n<nonce>\n<sha256('')>\nteam1\nmember\nu-1\nCISO,DPO"` 的 HMAC（用同一 `hmacSha256` 计算期望值），且头 `X-User-Business-Roles === 'CISO,DPO'`（输入 `['DPO','CISO']`）。
`policy-api-guard.test.ts`：mock `fetch`，断言 `listGuardApprovals('PENDING')` 请求 URL、带 `X-Internal-Caller` 与 `X-User-Business-Roles`；403 `{error:'role_mismatch', verifiedRoles:[]}` → `PolicyApiError.code==='role_mismatch'` 且 `details.verifiedRoles` 为 `[]`。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**（canonical：`const roles = [...new Set((identity?.businessRoles ?? []).map(r => r.trim()).filter(Boolean))].sort(); const canonical = [method, path, identity?.query ?? '', timestamp, nonce, bodyHash, tenant, roleStr, identity?.userId ?? '', roles.join(',')].join('\n');`。既有调用方全部补 `identity` 参数（多数传 `{ query: '', userId: '' }`，`receipts-client` 传实际 query）。）

- [ ] **Step 4: 跑全门禁，PASS；提交**

```bash
git commit -am "feat(policy-api): 内部签名 v3（query/userId/businessRoles）与 guard 客户端方法（ADR 0042 §4）"
```

### Task 9: 执行路由开决策、登记重试、日志链接、通知种类

**Files:**
- Create: `src/lib/guard-register.ts`、`src/app/api/policies/[id]/executions/[execId]/guard-register/route.ts`
- Modify: `src/app/api/policies/[id]/execute/route.ts`（refs §20 :330-392）、`src/app/api/v1/policies/[id]/execute/route.ts`（:246-310）、`src/lib/notifications.ts`（refs §19）、`src/app/[locale]/(dashboard)/policies/[id]/logs/logs-content.tsx`（refs §21）、`src/lib/policy-execution-log.ts`（`metadata` 类型收窄为 `ExecutionMetadata | null`）、`src/i18n/demo-supplement.ts`
- Test: `src/__tests__/lib/guard-register.test.ts`、`src/__tests__/api/policies-execute-guard.test.ts`、`src/__tests__/api/guard-register-route.test.ts`、`src/app/[locale]/(dashboard)/policies/[id]/logs/logs-content.guard-link.test.tsx`

**Interfaces:**
- Produces: `ExecutionMetadata = { guardDecisionId?: string; guardApprovalId?: string; guardError?: string }`；`registerGuardDecision(args: { client: PolicyApiClient; policy: { id, name, teamId, userId }; functionName: string; input: Record<string, unknown>; agent: EvidenceAgent | null; result: PolicyExecutionResult; requesterUserId: string }): Promise<ExecutionMetadata>`（纯函数式：成功 `{guardDecisionId, guardApprovalId}`，失败 `{guardError}`，永不抛）；动作映射 `toGuardAction(input, functionName, agent)`：`principal.id = input.principal_id ?? requesterUserId`，`principal.type = input.principal_type ?? 'user'`，`principal.roles = input.principal_roles ?? []`，`action.name = input.action ?? functionName`，`resource = { type: input.resource_type ?? '', id: input.resource_id ?? '' }`，`context = input`；from-evidence 请求只含 `correlationId` 与 `action`（修正 #8），cloud 不解析模块名。通知：`NotificationKind` 增 `'guard.approval_requested' | 'guard.approval_decided'`，payload `{ tenantId, decisionId, approvalId, policyId, policyName, requiredRole: string | null, outcome?: 'APPROVED' | 'REJECTED' }`；`notifyApprovalRequested(tenantId, payload)`：查该租户持有 `requiredRole` 的成员（`requiredRole==null` 全员；个人租户本人）逐个 `createNotification`。

- [ ] **Step 1: 写失败测试**

`guard-register.test.ts`：client mock `guardFromEvidence` 返回 `{ decisionId:'d1', approval:{ id:'a1' } }` → `{guardDecisionId:'d1', guardApprovalId:'a1'}`；抛 `PolicyApiError(409,'evidence_not_approvable')` → `{guardError:'evidence_not_approvable'}`；`result.metadata.evidenceCorrelationId` 缺失 → `{guardError:'no_evidence'}` 且不调用 client；动作映射断言。
`policies-execute-guard.test.ts`：执行结果 outcome REQUIRE_APPROVAL → 插入值含 `metadata: { guardDecisionId: 'd1', guardApprovalId: 'a1' }`，并调用 `notifyApprovalRequested`；outcome ALLOW → 不调 guard、`metadata` 不含 guard 键。
`guard-register-route.test.ts`：未登录 401；执行行 `guardError` 存在 → 重试成功后 `db.update(executions).set({ metadata })`。
logs 组件测试：有 `metadata.guardDecisionId` 的行渲染链接 `href` 含 `/approvals?decisionId=d1`；`guardError` 行渲染「重新登记」按钮。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**（两条路由：在 `deriveExecutionDecision(executionResult)` 为 `require_approval|escalate` 时 `const guardMeta = await registerGuardDecision({...})`，dashboard 用 `createPolicyApiClientForUser(policyTenantId(policy), userId)`，v1 用 `createPolicyApiClientForUser(policyTenantId(policy), userId)`（key 持有者）；插入值加 `metadata: Object.keys(guardMeta).length ? guardMeta : null`；成功后 `void notifyApprovalRequested(...)`。logs：状态徽章旁按 metadata 渲染链接/按钮；i18n 四语 `logs.viewApproval`、`logs.registerGuard`、`logs.guardError`。）

- [ ] **Step 4: 全门禁 PASS；提交**

```bash
git commit -am "feat(executions): 需审批执行以证据锚定开 guard 决策并记录 guardDecisionId，登记重试与站内通知（ADR 0042 §5.1/§5.3）"
```

### Task 10: 审批收件箱

**Files:**
- Create: `src/lib/approvals-inbox.ts`、`src/app/[locale]/(dashboard)/approvals/page.tsx`、`src/app/[locale]/(dashboard)/approvals/approvals-content.tsx`、`src/app/api/approvals/route.ts`（GET 列表）、`src/app/api/approvals/[tenantId]/[approvalId]/[verb]/route.ts`（POST approve|reject）
- Modify: `src/components/dashboard-sidebar.tsx`（refs §22）、`src/components/dashboard/command-palette-commands.ts`、`src/app/[locale]/(dashboard)/layout.tsx`（label）、`src/components/notifications/notifications-bell.tsx`（两种新 kind 的文案与 href）、`src/i18n/demo-supplement.ts`（`approvals.*`、`dashboardNav.approvals`、`notifications.guardApprovalRequested/guardApprovalDecided` 四语）
- Test: `src/__tests__/lib/approvals-inbox.test.ts`、`src/__tests__/api/approvals-routes.test.ts`、`src/app/[locale]/(dashboard)/approvals/approvals-content.test.tsx`

**Interfaces:**
- Produces: `listUserApprovals(userId: string, status: GuardApprovalStatus): Promise<{ items: InboxItem[]; unavailableTenants: string[] }>`，`InboxItem = GuardApprovalItem & { tenantId: string; tenantName: string; canAct: boolean }`；租户集合 = `[userId, ...teamIds]`；每租户 `createPolicyApiClientForUser(tenantId, userId)` → `listGuardApprovals(status, 0, 200)`，并发 4（复用 `createLimiter`），失败进 `unavailableTenants`；`canAct` 规则见 Global Constraints（角色来自 `loadBusinessRoles`）。路由：`GET /api/approvals?status=`、`POST /api/approvals/[tenantId]/[approvalId]/approve|reject`（校验用户属于该租户：`tenantId===userId` 或 `checkTeamAccess`），api 错误 `{error, message, verifiedRoles?}` 原状态码透传；审批成功后给发起人写 `guard.approval_decided`：发起人 = `executions` 中 `metadata->>'guardDecisionId' = decisionId` 的 `userId`（查询 helper `findExecutionByGuardDecision(decisionId)`），查不到则不通知。

- [ ] **Step 1: 写失败测试**

`approvals-inbox.test.ts`：两租户（个人 + team1），team1 的 client 抛错 → `unavailableTenants=['team1']`、个人项仍在；`canAct`：PENDING + 角色命中 true、角色不命中 false、`requiredRole` null true、APPROVED false；排序 `createdAt` 倒序。
`approvals-routes.test.ts`：未登录 401；不属于租户 403；api 403 `role_mismatch` 透传 403 与 `verifiedRoles`；reject 无 comment → 400（本地校验）。
组件测试：三种身份（可审 / 只读 / 空列表）按钮可见性；`?decisionId=` 高亮行。

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 实现**（页面服务端取 session → `listUserApprovals` → 客户端组件渲染；status 过滤 tab；拒绝弹出 comment 输入；侧边栏在 `reports` 后加 `{ href: '/approvals', label: labels.approvals, icon: ClipboardCheck }`；命令面板加 `approvals`；bell 两种 kind：requested → `href: '/approvals?decisionId=…'`，decided → `href: '/policies/{policyId}/logs'`。）

- [ ] **Step 4: 全门禁 PASS；提交**

```bash
git commit -am "feat(approvals): 审批收件箱——跨租户待审批、按已验证角色可审、通知入口（ADR 0042 §5.2）"
```

---

## aster-guard（分支 `docs/adr-0042-business-roles`）

### Task 11: SDK 类型与 README 对齐

**Files:**
- Modify: `src/types.ts`（refs §26：`GuardApproval` 增可选 `policyModule, policyFunction, actionName, resourceType, resourceId, ruleId, controls, reason, evidenceCorrelationId`；`businessRole` 文档改为「仅作提示，服务端以 API key 所属成员的已验证业务角色集合为准（ADR 0042）」）、`README.md`「本地端到端」前提补「审批方成员须在 aster-cloud 团队成员页被授予 Data Protection Officer」、`scripts/e2e-local.mjs` 注释同步
- Test: `pnpm build && pnpm test`（既有）

- [ ] **Step 1: 改 `src/types.ts`**：`GuardApproval` 追加可选字段（全部 `?: string`，`controls?: string[]`）；`GuardApproveOptions.businessRole` 文档改为「仅作提示：aster-api 以 API key 所属成员的已验证业务角色集合判定（ADR 0042 §2.3），自报值不再放行」。
- [ ] **Step 2: 改 README「本地端到端」前提**：加第 4 条「审批方用户须在 aster-cloud 团队成员页被授予 `Data Protection Officer`；`ASTER_GUARD_APPROVER_ROLE` 仅为提示」；`scripts/e2e-local.mjs` 头注释同步。
- [ ] **Step 3: 运行** `pnpm build && pnpm test`，Expected: PASS（类型只增可选字段）。
- [ ] **Step 4: 提交** `docs(guard): 审批角色以服务端已验证集合为准，列表项新增决策字段（ADR 0042）`。

---

### Task 12: 全栈验收与 ADR 定稿

**Files:**
- Modify: `aster-api/docs/adr/0042-approval-closure.md`（状态 → ACCEPTED；按「对 ADR 的修正」表回写；§9 追加「验收结果」）、`aster-api/docs/adr/0041-evidence-chain-enrichment.md` §5.2（guard-approval `roleVerified` 改为 true，Task 9 已改代码则此处同步；若 cloud `reviewers.ts` 的 `guardApprovalReviewers` 仍写 false，在 Task 10 一并改为 true 并更新其测试）

- [ ] **Step 1: 重建并重启**（同 ADR 0041 Task 10 Step 1；aster-cloud 容器须带 `WRANGLER_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE`，见记忆 aster-local-verification-env）。api 与 cloud 同窗口切 v3：先起 api，再 `podman restart aster-cloud`。
- [ ] **Step 2: 验收 1 —— 角色授予与下发**：SQL 或 UI 给 team1 的 m-dpo 授予 `Data Protection Officer`；`podman exec aster-pg psql -d aster_cloud -c "select \"businessRoles\" from \"TeamMember\" where \"userId\"='m-dpo'"`；用 KEY_DPO 调 `/api/v1/guard/approvals`（任意）后查 api 日志无 500；aster-guard `e2e-local.mjs` 以 KEY_FREE/KEY_DPO 跑通（审批方角色由 verify 下发，脚本自报头被忽略）。再把 m-dpo 角色清空、等 61 s、用 KEY_DPO 审批 → 403 `role_mismatch` 且 `verifiedRoles: []`；恢复角色。
- [ ] **Step 3: 验收 2 —— dashboard 执行 → 收件箱 → 证据包**：m-free 登录 dashboard 执行 `pol-adr0041-team`（delete_customer_record）→ 日志行「待审批 · 查看」；`Execution.metadata.guardDecisionId` 非空；m-dpo 收件箱看到该项且有按钮，批准 → `guard_approvals.status=APPROVED, decided_by='m-dpo'`；m-free 自己打开收件箱无按钮（无角色）；owner1 只读；m-free 导出证据包 → `reviewers` 含 `{source:'guard-approval', userId:'m-dpo', roleVerified:true}`。
- [ ] **Step 4: 验收 3 —— 失败降级**：`podman stop aster-api` 后 dashboard 执行同策略 → 执行成功落库、`metadata.guardError` 非空、日志有「重新登记」；`podman start aster-api` 后点击重新登记 → `guardDecisionId` 写入。
- [ ] **Step 5: 验收 4 —— 旧通道拒绝**：用 v2 canonical 的脚本（临时）调 `/evaluate-source` → 403 `invalid_signature`；SDK 自报 `businessRole` 但成员无角色 → 403。
- [ ] **Step 6: ADR 定稿与提交**（`docs(adr): ADR 0042 定稿——按实现回写修正并记录栈验收结果`）。
