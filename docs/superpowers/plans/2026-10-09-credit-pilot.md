# ADR 0044 Annex III 信贷试点 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新写 Verdict 版信贷策略，用脚本与浏览器两条路跑通「执行 → 审批 → What-If → 导出」，证据包 manifest 内嵌只陈述事实的 Article 14 对照表。

**Architecture:** aster-lang-test 增语料 `credit-pilot`；aster-api 增双通道端点 `GET /api/v1/replay/items?executionIds=`；aster-cloud 证据包升 v3（条目 `whatIf`、manifest `regulatoryMapping`、`notes.whatIfUnavailable`），对照判定为纯函数 `article14.ts`；seed/run 脚本、Playwright 规格、docs 指南三语。

**Tech Stack:** aster-lang-test gen-cases（双引擎交叉）；Quarkus/Panache + @QuarkusTest；Next.js 16 + vitest + Playwright；`@aster-cloud/aster-lang-ts/browser` 三语编译。

**Spec:** `docs/adr/0044-annex-iii-credit-pilot.md`。代码摘录：`/private/tmp/aster-stack/adr0044-refs.md`（下文「refs §n」）。

## Global Constraints

- 注释一律简体中文、只写意图；禁止「修改说明」式注释；缩进 ≤ 3 层；沿用既有 import 顺序；提交 trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`；不推送；不提交 AGENTS.md/CLAUDE.md。
- 结论码集合逐字：`ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE, INDETERMINATE, ERROR`。
- 对照状态逐字：`evidenced | partial | none`；条款键逐字：`14(1), 14(2), 14(3), 14(4)(a), 14(4)(b), 14(4)(c), 14(4)(d), 14(4)(e), 14(5)`；对照不含评分、不含建议文案。
- 策略 `aster.pilot.credit` 只有一条入口规则 `decide`；`@id("CP-DECIDE")`、`@control("EU_AI_ACT:ART14")`、`@control("GDPR:ART22")`；reason 逐字：`active_default, dti_exceeds_limit, large_exposure, borderline_credit_score`；审批角色逐字 `Credit Officer`；v1 阈值 50000、v2 阈值 80000。
- 证据包 `schemaVersion: '3'`；v1/v2 类型冻结只读；`bundleHash` 仍只覆盖有序 entries。
- api 端点：路径 `/api/v1/replay/items`，参数 `executionIds`（csv），上限 `AuditLog.MAX_LOOKUP_IDS`，超限 400 `{"error":"too_many_ids"}`，缺参 400 `{"error":"executionIds_required"}`；响应 `{"items":[{executionId,batchId,baseOutcome,targetOutcome,baseLegacy}]}`，同 executionId 多批取 `replay_batch.created_at` 最新；只返回本租户。
- 试点租户常量逐字：团队 `credit-pilot`、用户 `cp-owner`（plan team）/`cp-officer`（plan pro，businessRoles `['Credit Officer']`）/`cp-analyst`（plan pro），策略 `pol-credit-pilot`，版本行 `pv-credit-pilot-1`、`pv-credit-pilot-2`，邮箱 `<id>@stack.test`。
- api 测试命令：`DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380 ./gradlew --offline :test --tests '...'`。cloud 门禁：`pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm test:run && pnpm check:locales:strict && pnpm check:docs-index && pnpm check:docs-descriptions`。语料：`node scripts/gen-cases.mjs scripts/cases-spec-credit-pilot.json --write` 与 `node scripts/parity-tier1.mjs --mode=eval`。绝不触碰 `aster-*` 容器（验收阶段除外，且只读/只调 HTTP）。

## Review Focus

1. 同一 executionId 跑过多次 What-If → 端点必须只返回最新一条，不能返回两条 —— Task 2 资源测试。
2. 回放端点整体不可用 → 导出仍完成，所有 `whatIf` 为 null、`notes.whatIfUnavailable = entries.length`、14(4)(a) 为 none —— Task 5 集成测试。
3. 已存 v2 包的读取与下载不受影响（`summarizeManifest` 三分支） —— Task 3 单测。
4. `reviewers` 只有 `roleVerified:false` 的 REJECTED → 14(4)(d) 不得 evidenced（只认已验证角色） —— Task 4 单测。
5. seed 幂等：重复运行不得新建第二把 API key，也不得翻倍 PolicyVersion 行 —— Task 6 脚本内以 id 查存在后更新，并在验收 §9 跑两遍核对计数。

---

### Task 1: 语料 `credit-pilot`（aster-lang-test）

**Files:**
- Create: `corpus/tier1-equivalence/policies/credit-pilot.aster`
- Create: `corpus/tier1-equivalence/policies/credit-pilot.meta.json`
- Create: `scripts/cases-spec-credit-pilot.json`
- Generated: `corpus/tier1-equivalence/inputs/credit-pilot.cases.json`

**Interfaces:**
- Produces: 英文源文（Task 6 的 seed 以其为 en 版本；zh/de 版本在 Task 6 的 `credit-pilot-source.ts` 中由同一结构翻译）。

- [ ] **Step 1: 写策略源文**

```
Module aster.pilot.credit.

Define Applicant has
  creditScore as Int,
  monthlyIncome as Decimal,
  monthlyDebt as Decimal,
  requestedAmount as Decimal,
  activeDefaults as Int.

@id("CP-DECIDE")
@control("EU_AI_ACT:ART14")
@control("GDPR:ART22")
Rule decide given applicant as Applicant, produce Verdict:
  If applicant.activeDefaults at least 1:
    Return Verdict.deny("active_default").
  If applicant.monthlyDebt greater than applicant.monthlyIncome times 0.45m:
    Return Verdict.deny("dti_exceeds_limit").
  If applicant.requestedAmount at least 50000m:
    Return Verdict.require_approval("Credit Officer", "large_exposure").
  If applicant.creditScore at least 620 and applicant.creditScore at most 679:
    Return Verdict.escalate("borderline_credit_score").
  Return Verdict.allow().
```

- [ ] **Step 2: 写 meta**

```json
{
  "tier": 1,
  "engines": ["java", "ts"],
  "lexicon": "en-US",
  "source": "ADR 0044 Annex III 信贷试点",
  "capabilities": ["cases-golden"]
}
```

- [ ] **Step 3: 写用例 spec**

```json
{"samples":[{"name":"credit-pilot","entry":"decide","cases":[
  {"name":"allow","input":[{"creditScore":760,"monthlyIncome":9000,"monthlyDebt":2000,"requestedAmount":20000,"activeDefaults":0}],
   "expectedOutput":{"__type":"Verdict","outcome":"ALLOW"}},
  {"name":"deny active default","input":[{"creditScore":760,"monthlyIncome":9000,"monthlyDebt":2000,"requestedAmount":20000,"activeDefaults":1}],
   "expectedOutput":{"__type":"Verdict","outcome":"DENY","reason":"active_default"}},
  {"name":"deny dti","input":[{"creditScore":760,"monthlyIncome":5000,"monthlyDebt":2400,"requestedAmount":20000,"activeDefaults":0}],
   "expectedOutput":{"__type":"Verdict","outcome":"DENY","reason":"dti_exceeds_limit"}},
  {"name":"require approval large amount","input":[{"creditScore":760,"monthlyIncome":9000,"monthlyDebt":2000,"requestedAmount":60000,"activeDefaults":0}],
   "expectedOutput":{"__type":"Verdict","outcome":"REQUIRE_APPROVAL","role":"Credit Officer","reason":"large_exposure"}},
  {"name":"escalate borderline score","input":[{"creditScore":650,"monthlyIncome":9000,"monthlyDebt":2000,"requestedAmount":20000,"activeDefaults":0}],
   "expectedOutput":{"__type":"Verdict","outcome":"ESCALATE","reason":"borderline_credit_score"}},
  {"name":"dti exactly at limit allows","input":[{"creditScore":760,"monthlyIncome":10000,"monthlyDebt":4500,"requestedAmount":20000,"activeDefaults":0}],
   "expectedOutput":{"__type":"Verdict","outcome":"ALLOW"}}
]}]}
```

- [ ] **Step 4: 干跑生成，确认两引擎一致**

Run: `node scripts/gen-cases.mjs scripts/cases-spec-credit-pilot.json`
Expected: 六条全部 agree；若 Java/TS 对 `Decimal` 字面量比较有分歧，改用 `Decimal.compare` 前先把分歧原样贴到报告，不得改期望值迁就引擎。

- [ ] **Step 5: 写入并跑 eval parity**

Run: `node scripts/gen-cases.mjs scripts/cases-spec-credit-pilot.json --write && node scripts/parity-tier1.mjs --mode=eval`
Expected: 生成 `inputs/credit-pilot.cases.json`；parity 全绿。

- [ ] **Step 6: 提交**

```bash
git add corpus/tier1-equivalence/policies/credit-pilot.* corpus/tier1-equivalence/inputs/credit-pilot.cases.json scripts/cases-spec-credit-pilot.json
git commit -m "corpus: ADR 0044 信贷试点 Verdict 策略与黄金用例"
```

---

### Task 2: 回放逐项查询端点（aster-api）

**Files:**
- Create: `src/main/java/io/aster/policy/replay/batch/ReplayItemLookupResource.java`
- Create: `src/main/java/io/aster/policy/replay/batch/ReplayItemLookupService.java`
- Create: `src/main/java/io/aster/policy/replay/batch/dto/ReplayItemLookupResponse.java`
- Modify: `src/main/java/io/aster/security/apikey/InternalCallerFilter.java:184-190`（双通道分支加本路径）
- Modify: `src/main/java/io/aster/security/apikey/ApiKeyAuthFilter.java:47-50`（`HMAC_BYPASS_PATHS` 加本路径）
- Test: `src/test/java/io/aster/policy/replay/batch/ReplayItemLookupResourceTest.java`
- Test: `src/test/java/io/aster/security/apikey/ReplayItemLookupHmacAuthTest.java`

**Interfaces:**
- Produces: `GET /api/v1/replay/items?executionIds=a,b` → `{"items":[{"executionId","batchId","baseOutcome","targetOutcome","baseLegacy"}]}`（Task 3 客户端解析）。

- [ ] **Step 1: 写资源测试（先红）**

```java
package io.aster.policy.replay.batch;

import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** 回放逐项查询（ADR 0044 §4）：按 executionId 取最新一次回放结论，租户隔离，上限与缺参校验。 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ReplayItemLookupResourceTest {
    static final String PATH = "/api/v1/replay/items";
    static final String TENANT = "tenant-replay-items";
    static final String OTHER = "tenant-replay-items-other";

    @Inject EntityManager em;

    @BeforeEach void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM replay_batch_item").executeUpdate();
            em.createNativeQuery("DELETE FROM replay_batch").executeUpdate();
        });
    }

    UUID batch(String tenant, Instant createdAt) {
        ReplayBatchEntity b = new ReplayBatchEntity();
        b.id = UUID.randomUUID();
        b.tenantId = tenant; b.userId = "u"; b.policyId = "p";
        b.baseVersionId = "v1"; b.targetVersionId = "v2";
        b.windowKind = "LAST_MONTH"; b.windowLabel = "x"; b.windowFrom = createdAt; b.windowTo = createdAt;
        b.toolchainId = "tc"; b.expiresAt = createdAt.plusSeconds(3600); b.createdAt = createdAt;
        b.status = ReplayBatchStatus.COMPLETED;
        QuarkusTransaction.requiringNew().run(b::persist);
        return b.id;
    }

    void item(UUID batchId, String execId, String base, String target, boolean legacy) {
        ReplayBatchItemEntity i = new ReplayBatchItemEntity(batchId, execId, "ALLOW".equals(base), base, legacy);
        i.targetOutcome = target; i.success = true;
        QuarkusTransaction.requiringNew().run(i::persist);
    }

    Response get(String tenant, String ids) {
        return given().header("X-Tenant-Id", tenant).header("X-User-Id", "u").header("X-User-Role", "member")
            .queryParam("executionIds", ids).get(PATH);
    }

    @Test void 同执行多批只返回最新一批() {
        UUID old = batch(TENANT, Instant.parse("2026-10-01T00:00:00Z"));
        UUID latest = batch(TENANT, Instant.parse("2026-10-05T00:00:00Z"));
        item(old, "e1", "REQUIRE_APPROVAL", "ESCALATE", false);
        item(latest, "e1", "REQUIRE_APPROVAL", "ALLOW", false);
        Response r = get(TENANT, "e1");
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).hasSize(1);
        assertThat(r.jsonPath().getString("items[0].batchId")).isEqualTo(latest.toString());
        assertThat(r.jsonPath().getString("items[0].targetOutcome")).isEqualTo("ALLOW");
        assertThat(r.jsonPath().getBoolean("items[0].baseLegacy")).isFalse();
    }

    @Test void 跨租户与无回放的id静默不返回() {
        item(batch(OTHER, Instant.now()), "e-other", "ALLOW", "ALLOW", false);
        Response r = get(TENANT, "e-other,e-none");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).isEmpty();
    }

    @Test void 缺参与超限返回400() {
        assertThat(given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u").header("X-User-Role", "member")
            .get(PATH).statusCode()).isEqualTo(400);
        String many = String.join(",", java.util.Collections.nCopies(io.aster.policy.entity.AuditLog.MAX_LOOKUP_IDS + 1, "x"));
        Response r = get(TENANT, many);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.jsonPath().getString("error")).isEqualTo("too_many_ids");
    }
}
```

默认测试 profile 下 apikey/hmac 均关闭，身份来自 `X-Tenant-Id` 等头（与 `AuditReceiptResourceTest` 相同）；若该测试类用的是别的头/profile，照抄它的做法。

- [ ] **Step 2: 跑测试确认失败（404）**

Run: `... ./gradlew --offline :test --tests 'io.aster.policy.replay.batch.ReplayItemLookupResourceTest'`
Expected: FAIL，状态码 404。

- [ ] **Step 3: DTO**

```java
package io.aster.policy.replay.batch.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** 回放逐项查询响应（ADR 0044 §4）：只含结论码，不含结论体。 */
@JsonPropertyOrder(alphabetic = true)
public record ReplayItemLookupResponse(List<Item> items) {
    @JsonPropertyOrder(alphabetic = true)
    public record Item(String executionId, String batchId, String baseOutcome, String targetOutcome, boolean baseLegacy) {}
}
```

- [ ] **Step 4: Service（JPQL 关联 batch 过滤租户，内存取最新）**

```java
package io.aster.policy.replay.batch;

import io.aster.policy.replay.batch.dto.ReplayItemLookupResponse;
import io.aster.policy.replay.batch.dto.ReplayItemLookupResponse.Item;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 executionId 取该租户最新一次回放的结论码。
 *
 * <p>replay_batch_item 无租户列，经 replay_batch.tenant_id 过滤；跨租户与无回放的 id 不出现在结果里。
 * 按 created_at 倒序读出后按 executionId 取首条，上限由资源层把住，内存量有界。
 */
@ApplicationScoped
public class ReplayItemLookupService {

    @Inject EntityManager em;

    @Transactional(Transactional.TxType.SUPPORTS)
    public ReplayItemLookupResponse latestByExecutionIds(String tenantId, List<String> executionIds) {
        List<Object[]> rows = em.createQuery(
                "select i.executionId, b.id, i.baseOutcome, i.targetOutcome, i.baseLegacy "
                + "from ReplayBatchItemEntity i, ReplayBatchEntity b "
                + "where i.batchId = b.id and b.tenantId = :tenant and i.executionId in :ids "
                + "order by b.createdAt desc", Object[].class)
            .setParameter("tenant", tenantId)
            .setParameter("ids", executionIds)
            .getResultList();
        Map<String, Item> latest = new LinkedHashMap<>();
        for (Object[] r : rows) {
            latest.putIfAbsent((String) r[0], new Item((String) r[0], String.valueOf(r[1]),
                (String) r[2], (String) r[3], Boolean.TRUE.equals(r[4])));
        }
        return new ReplayItemLookupResponse(List.copyOf(latest.values()));
    }
}
```

- [ ] **Step 5: Resource（镜像 `AuditReceiptResource`）**

```java
package io.aster.policy.replay.batch;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 回放逐项查询（ADR 0044 §4）：供 aster-cloud 证据导出把 What-If 结论挂到条目上。
 *
 * <p>与 /api/v1/audit/receipts 同为双通道：HMAC 内部调用者或 Bearer API key；租户取自 RequestIdentityResolver。
 */
@Path("/api/v1/replay/items")
@Produces(MediaType.APPLICATION_JSON)
public class ReplayItemLookupResource {

    static final int MAX_IDS = AuditLog.MAX_LOOKUP_IDS;

    @Inject RequestIdentityResolver identityResolver;
    @Inject ReplayItemLookupService service;

    @GET
    @Blocking
    @RequireRole(Role.MEMBER)
    public Response lookup(@QueryParam("executionIds") String executionIds) {
        if (executionIds == null) return badRequest("executionIds_required");
        List<String> ids = Arrays.stream(executionIds.split(",")).map(String::trim)
            .filter(s -> !s.isEmpty()).distinct().toList();
        if (ids.size() > MAX_IDS) return badRequest("too_many_ids");
        if (ids.isEmpty()) return badRequest("executionIds_required");
        return Response.ok(service.latestByExecutionIds(identityResolver.tenantId(), ids)).build();
    }

    private static Response badRequest(String error) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", error)).build();
    }
}
```

- [ ] **Step 6: 两个 filter 加路径**

`InternalCallerFilter.classify`：在 `isAuditReceipts` 旁加 `boolean isReplayItems = normalizedPath.equals("/api/v1/replay/items");`，并把 `if (isAuditReceipts || isGuardDualChannel(normalizedPath))` 改为 `if (isAuditReceipts || isReplayItems || isGuardDualChannel(normalizedPath))`。`ApiKeyAuthFilter.HMAC_BYPASS_PATHS` 加 `"/api/v1/replay/items"`。

- [ ] **Step 7: 跑资源测试转绿**

Run: 同 Step 2。Expected: PASS 3/3。

- [ ] **Step 8: 双通道鉴权测试（照 `AuditReceiptHmacAuthTest` 写）**

复制 `AuditReceiptHmacAuthTest` 为 `ReplayItemLookupHmacAuthTest`：`PATH = "/api/v1/replay/items"`，`setUp` 改为用 Step 1 的 `batch/item` 方式各给 TENANT/OTHER 一条 `executionId="e-hmac"`；签名 `InternalCallerSigning.headers(signKey, "GET", PATH, "executionIds=e-hmac", "", tenant, "member", "evidence-export", List.of())`；三个用例：HMAC 返回本租户 1 条；HMAC 签陌生租户返回 0 条；Bearer `ak_test_replay_member` 返回 1 条；无凭证 401。

Run: `... --tests 'io.aster.security.apikey.ReplayItemLookupHmacAuthTest'`
Expected: PASS。

- [ ] **Step 9: 跑相邻回归并提交**

Run: `... --tests 'io.aster.audit.rest.*' --tests 'io.aster.security.apikey.*' --tests 'io.aster.api.workflow.NonDeterminismSourceTest'`
Expected: PASS（新类不含 UUID.randomUUID/nanoTime，允许表无需改）。

```bash
git add src/main/java/io/aster/policy/replay/batch/ReplayItemLookup*.java src/main/java/io/aster/policy/replay/batch/dto/ReplayItemLookupResponse.java src/main/java/io/aster/security/apikey/InternalCallerFilter.java src/main/java/io/aster/security/apikey/ApiKeyAuthFilter.java src/test/java/io/aster/policy/replay/batch/ReplayItemLookupResourceTest.java src/test/java/io/aster/security/apikey/ReplayItemLookupHmacAuthTest.java
git commit -m "feat(replay): 回放逐项查询端点，供证据导出挂 What-If 结论（ADR 0044 §4）"
```

---

### Task 3: 证据包 v3 类型、回放客户端与存储读取（aster-cloud）

**Files:**
- Modify: `src/services/evidence/types.ts`
- Modify: `src/services/evidence/bundle.ts`
- Modify: `src/services/evidence/stored.ts`
- Create: `src/services/evidence/replay-items-client.ts`
- Test: `src/__tests__/services/evidence/bundle.test.ts`（追加）
- Test: `src/__tests__/services/evidence/replay-items-client.test.ts`
- Test: `src/__tests__/services/evidence/stored.test.ts`（若已存在则追加，否则新建三分支用例）

**Interfaces:**
- Produces: `EvidenceWhatIf = { batchId: string; baseOutcome: string; targetOutcome: string | null; baseLegacy: boolean }`；`EvidenceEntry.whatIf: EvidenceWhatIf | null`；`EvidenceManifest.schemaVersion: '3'`、`regulatoryMapping: Article14Mapping`（类型来自 Task 4，本任务先以 `import type { Article14Mapping } from './article14'` 引用——Task 4 之前先在 `article14.ts` 放最小类型与 `mapArticle14` 返回全 none 的实现，Task 4 再补判定）、`notes.whatIfUnavailable: number`；`fetchReplayItems(tenantId, executionIds, fetchImpl?, limiter?) => Promise<ReplayItemLookup>`，`ReplayItemLookup = { items: Map<string, EvidenceWhatIf>; unavailable: Set<string> }`；`buildEvidenceEntry(row, receipt, reviewers, whatIf)` 第四参数；`EvidenceRow` 不变。

- [ ] **Step 1: 冻结 v2**

在 `types.ts` 的 v1 冻结块后追加：`EvidenceEntryV2`（= 现 `EvidenceEntry` 字段逐字复制）、`EvidenceManifestV2`（= 现 `EvidenceManifest`，`schemaVersion: '2'`）、`EvidenceBundleV2`；`StoredEvidenceManifest = EvidenceManifestV1 | EvidenceManifestV2 | EvidenceManifest`，`StoredEvidenceBundle` 同理。注释：`// ---- schemaVersion '2' 冻结类型：ADR 0044 起只读。`

- [ ] **Step 2: 升 v3**

`EvidenceEntry` 加 `whatIf: EvidenceWhatIf | null`（注释：最近一次 What-If 回放的结论码；无回放或取不到为 null）。`EvidenceManifest.schemaVersion: '3'`，加 `regulatoryMapping: Article14Mapping`，`notes.whatIfUnavailable: number`。`EvidenceWhatIf` 类型如上。

- [ ] **Step 3: 回放客户端测试（先红）**

```ts
import { describe, it, expect, vi } from 'vitest';
import { fetchReplayItems } from '@/services/evidence/replay-items-client';

const ok = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));

describe('fetchReplayItems', () => {
  it('解析 items 为 map，缺失的 id 不出现也不算 unavailable', async () => {
    const fetchImpl = vi.fn(() => ok({ items: [
      { executionId: 'e1', batchId: 'b1', baseOutcome: 'REQUIRE_APPROVAL', targetOutcome: 'ALLOW', baseLegacy: false },
    ] }));
    const r = await fetchReplayItems('t', ['e1', 'e2'], fetchImpl as unknown as typeof fetch);
    expect(r.items.get('e1')).toEqual({ batchId: 'b1', baseOutcome: 'REQUIRE_APPROVAL', targetOutcome: 'ALLOW', baseLegacy: false });
    expect(r.items.has('e2')).toBe(false);
    expect(r.unavailable.size).toBe(0);
  });
  it('非 2xx 整批 unavailable', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response('x', { status: 503 })));
    const r = await fetchReplayItems('t', ['e1', 'e2'], fetchImpl as unknown as typeof fetch);
    expect([...r.unavailable]).toEqual(['e1', 'e2']);
  });
  it('targetOutcome 缺省归一为 null', async () => {
    const fetchImpl = vi.fn(() => ok({ items: [{ executionId: 'e1', batchId: 'b1', baseOutcome: 'ALLOW', baseLegacy: true }] }));
    const r = await fetchReplayItems('t', ['e1'], fetchImpl as unknown as typeof fetch);
    expect(r.items.get('e1')?.targetOutcome).toBeNull();
  });
});
```

- [ ] **Step 4: 回放客户端实现**

新建 `replay-items-client.ts`，结构镜像 `receipts-client.ts`（分批 `RECEIPT_BATCH`、超时 `RECEIPT_TIMEOUT_MS`、限流 `createLimiter`，均从 receipts-client 导入复用；路径 `/api/v1/replay/items`，参数 `executionIds`，签名头同 `buildHeaders` 的做法，`CALLER_USER_ID='evidence-export'`）。解析：`items` 必须为数组；每项 `executionId/batchId/baseOutcome` 必填字符串，`targetOutcome` 可空字符串归一 null，`baseLegacy` 布尔；任一畸形整批 unavailable。导出 `fetchReplayItems`、`mergeReplayLookups`、类型 `ReplayItemLookup`。

- [ ] **Step 5: bundle.ts**

`buildEvidenceEntry(row, receipt, reviewers, whatIf: EvidenceWhatIf | null = null)` 把 `whatIf` 放在 `reviewers` 之后；`buildManifest` 设 `schemaVersion: '3'`、`regulatoryMapping: mapArticle14(sorted)`、`notes.whatIfUnavailable: input.whatIfUnavailable ?? 0`（`BuildManifestInput` 加可选 `whatIfUnavailable?: number`）。`sortEntries`/`computeBundleHash` 不变。

- [ ] **Step 6: stored.ts**

`KNOWN_SCHEMA_VERSIONS` 加 `'3'`；`summarizeManifest` 增 `case '3'`。

- [ ] **Step 7: bundle 测试追加**

```ts
it('v3：entry 带 whatIf、manifest 带 regulatoryMapping 与 whatIfUnavailable', () => {
  const e = entry({}, LEGACY, [], null);
  expect(e.whatIf).toBeNull();
  const wi = { batchId: 'b1', baseOutcome: 'REQUIRE_APPROVAL', targetOutcome: 'ALLOW', baseLegacy: false };
  const e2 = buildEvidenceEntry(row({ id: 'exec-2' }), LEGACY, [], wi);
  expect(e2.whatIf).toEqual(wi);
  const m = buildManifest({ policy, range: { start: null, end: null }, entries: [e, e2], generatedAt: gen, whatIfUnavailable: 1 });
  expect(m.schemaVersion).toBe('3');
  expect(m.notes.whatIfUnavailable).toBe(1);
  expect(m.regulatoryMapping.framework).toBe('EU_AI_ACT');
  expect(m.regulatoryMapping.clauses.map((c) => c.clause)).toEqual(['14(1)', '14(2)', '14(3)', '14(4)(a)', '14(4)(b)', '14(4)(c)', '14(4)(d)', '14(4)(e)', '14(5)']);
});
it('whatIf 参与 bundleHash（entries 字段）', () => {
  const a = computeBundleHash([entry({}, LEGACY, [], null)]);
  const b = computeBundleHash([buildEvidenceEntry(row(), LEGACY, [], { batchId: 'b', baseOutcome: 'ALLOW', targetOutcome: 'ALLOW', baseLegacy: false })]);
  expect(a).not.toBe(b);
});
```

现有 `entry()` 辅助增第四参数默认 null。stored 测试：`summarizeStoredExport({ kind:'evidence-export', manifest:{ schemaVersion:'3', totals:{count:1}, bundleHash:'h' } })` 返回 `{schemaVersion:'3',count:1,bundleHash:'h'}`，v2 与 v1 同样可读，`'4'` 返回 null。

- [ ] **Step 8: 门禁与提交**

Run: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm test:run -- src/__tests__/services/evidence`
Expected: PASS（Task 4 最小 `article14.ts` 已在场）。

```bash
git add src/services/evidence src/__tests__/services/evidence
git commit -m "feat(evidence): 证据包 v3——条目挂 What-If 结论，manifest 预留法规对照（ADR 0044 §3）"
```

---

### Task 4: Article 14 对照纯函数（aster-cloud）

**Files:**
- Create/Modify: `src/services/evidence/article14.ts`
- Test: `src/__tests__/services/evidence/article14.test.ts`

**Interfaces:**
- Produces: `type ClauseStatus = 'evidenced' | 'partial' | 'none'`；`EvidenceRef = { executionId: string; field: string }`；`Article14Clause = { clause: string; title: string; status: ClauseStatus; evidence: EvidenceRef[] }`；`Article14Mapping = { framework: 'EU_AI_ACT'; article: '14'; clauses: Article14Clause[] }`；`mapArticle14(entries: readonly EvidenceEntry[]): Article14Mapping`；`ARTICLE14_CLAUSES` 顺序固定九条。

- [ ] **Step 1: 测试（先红）**

```ts
import { describe, it, expect } from 'vitest';
import { mapArticle14 } from '@/services/evidence/article14';
import type { EvidenceEntry } from '@/services/evidence/types';
import type { Reviewer } from '@/services/evidence/reviewers';

function e(over: Partial<EvidenceEntry>): EvidenceEntry {
  return {
    executionId: 'x', policyId: 'p', policyVersion: 1, policyVersionRowId: 'pv', decision: 'approved',
    canonicalInputHash: 'i', canonicalOutputHash: 'o', traceHash: 't', canonicalizationVersion: 'v',
    toolchain: { source: null, runtime: null }, replayabilityStatus: null, replayabilityReasons: null,
    reasonCodes: null, source: 'api', durationMs: 1, createdAt: '2026-10-09T00:00:00.000Z',
    outcome: 'ALLOW', ruleId: null, controls: null, agent: null, evidenceCorrelationId: null,
    receipt: { status: 'legacy' }, reviewers: [], whatIf: null, ...over,
  };
}
const rv = (over: Partial<Reviewer>): Reviewer => ({
  userId: 'u', role: 'Credit Officer', source: 'guard-approval', outcome: 'APPROVED',
  decidedAt: '2026-10-09T00:00:00.000Z', ref: 'r', roleVerified: true, ...over,
});
const status = (entries: EvidenceEntry[]) => Object.fromEntries(mapArticle14(entries).clauses.map((c) => [c.clause, c.status]));

describe('mapArticle14', () => {
  it('空 entries 全部 none，九条顺序固定', () => {
    const m = mapArticle14([]);
    expect(m.clauses.map((c) => c.clause)).toEqual(['14(1)', '14(2)', '14(3)', '14(4)(a)', '14(4)(b)', '14(4)(c)', '14(4)(d)', '14(4)(e)', '14(5)']);
    expect(m.clauses.every((c) => c.status === 'none' && c.evidence.length === 0)).toBe(true);
  });
  it('14(1) 由 controls 含 EU_AI_ACT:ART14 证实并指向字段', () => {
    const m = mapArticle14([e({ executionId: 'a', controls: ['EU_AI_ACT:ART14'] })]);
    const c = m.clauses.find((x) => x.clause === '14(1)')!;
    expect(c.status).toBe('evidenced');
    expect(c.evidence).toEqual([{ executionId: 'a', field: 'controls' }]);
  });
  it('14(2)/14(4)(e)：REQUIRE_APPROVAL 有复核者 evidenced，无复核者 partial', () => {
    expect(status([e({ outcome: 'REQUIRE_APPROVAL' })])).toMatchObject({ '14(2)': 'evidenced', '14(4)(e)': 'partial' });
    expect(status([e({ outcome: 'REQUIRE_APPROVAL', reviewers: [rv({})] })])['14(4)(e)']).toBe('evidenced');
  });
  it('14(3)：控制与复核者都在 evidenced，只有其一 partial', () => {
    expect(status([e({ controls: ['EU_AI_ACT:ART14'] })])['14(3)']).toBe('partial');
    expect(status([e({ controls: ['EU_AI_ACT:ART14'], reviewers: [rv({})] })])['14(3)']).toBe('evidenced');
  });
  it('14(4)(a)：有 whatIf 为 partial，否则 none', () => {
    expect(status([e({ whatIf: { batchId: 'b', baseOutcome: 'ALLOW', targetOutcome: 'ALLOW', baseLegacy: false } })])['14(4)(a)']).toBe('partial');
    expect(status([e({})])['14(4)(a)']).toBe('none');
  });
  it('14(4)(b) 恒 none', () => {
    expect(status([e({ controls: ['EU_AI_ACT:ART14'], reviewers: [rv({})] })])['14(4)(b)']).toBe('none');
  });
  it('14(4)(c)：ruleId 与 reasonCodes 均非空', () => {
    expect(status([e({ ruleId: 'CP-DECIDE', reasonCodes: ['large_exposure'] })])['14(4)(c)']).toBe('evidenced');
    expect(status([e({ ruleId: 'CP-DECIDE', reasonCodes: [] })])['14(4)(c)']).toBe('none');
  });
  it('14(4)(d)：已验证角色 REJECTED evidenced；APPROVED partial；未验证角色的 REJECTED 不算', () => {
    expect(status([e({ reviewers: [rv({ outcome: 'REJECTED' })] })])['14(4)(d)']).toBe('evidenced');
    expect(status([e({ reviewers: [rv({ outcome: 'APPROVED' })] })])['14(4)(d)']).toBe('partial');
    expect(status([e({ reviewers: [rv({ outcome: 'REJECTED', roleVerified: false })] })])['14(4)(d)']).toBe('none');
  });
  it('14(5)：复核者 ≥ 2 才 evidenced', () => {
    expect(status([e({ reviewers: [rv({}), rv({ userId: 'u2' })] })])['14(5)']).toBe('evidenced');
    expect(status([e({ reviewers: [rv({})] })])['14(5)']).toBe('none');
  });
});
```

- [ ] **Step 2: 实现**

```ts
/**
 * EU AI Act Article 14（人工监督）对照（ADR 0044 §3.1）。
 *
 * 纯函数：只读证据包条目字段，给每款一个 evidenced / partial / none 与指向的 (executionId, field)。
 * 不打分、不给建议；无字段可证的款项如实标 none。子项目 5 控制注册表就绪后由注册表驱动替换本表。
 */
import type { EvidenceEntry } from './types';

export type ClauseStatus = 'evidenced' | 'partial' | 'none';
export interface EvidenceRef { executionId: string; field: string }
export interface Article14Clause { clause: string; title: string; status: ClauseStatus; evidence: EvidenceRef[] }
export interface Article14Mapping { framework: 'EU_AI_ACT'; article: '14'; clauses: Article14Clause[] }

const ART14_CONTROL = 'EU_AI_ACT:ART14';

type Judge = (entries: readonly EvidenceEntry[]) => { status: ClauseStatus; evidence: EvidenceRef[] };

const refs = (entries: readonly EvidenceEntry[], field: string, pick: (e: EvidenceEntry) => boolean): EvidenceRef[] =>
  entries.filter(pick).map((e) => ({ executionId: e.executionId, field }));

const hasControl = (e: EvidenceEntry) => (e.controls ?? []).includes(ART14_CONTROL);
const isPending = (e: EvidenceEntry) => e.outcome === 'REQUIRE_APPROVAL' || e.outcome === 'ESCALATE';
const hasReviewer = (e: EvidenceEntry) => e.reviewers.length > 0;
const verified = (e: EvidenceEntry, outcome: string) => e.reviewers.some((r) => r.roleVerified && r.outcome === outcome);

/** 两组证据：都有 evidenced、其一 partial、全无 none。 */
function both(a: EvidenceRef[], b: EvidenceRef[]): { status: ClauseStatus; evidence: EvidenceRef[] } {
  const evidence = [...a, ...b];
  if (a.length > 0 && b.length > 0) return { status: 'evidenced', evidence };
  return { status: evidence.length > 0 ? 'partial' : 'none', evidence };
}

function some(evidence: EvidenceRef[], status: ClauseStatus = 'evidenced') {
  return { status: evidence.length > 0 ? status : 'none', evidence };
}

const JUDGES: ReadonlyArray<[string, string, Judge]> = [
  ['14(1)', 'Designed for effective human oversight', (es) => some(refs(es, 'controls', hasControl))],
  ['14(2)', 'Oversight proportionate to risk', (es) => some(refs(es, 'outcome', isPending))],
  ['14(3)', 'Oversight measures built in', (es) => both(refs(es, 'controls', hasControl), refs(es, 'reviewers', hasReviewer))],
  ['14(4)(a)', 'Overseer can understand capacities and limitations', (es) => some(refs(es, 'whatIf', (e) => e.whatIf !== null), 'partial')],
  ['14(4)(b)', 'Awareness of automation bias', () => ({ status: 'none', evidence: [] })],
  ['14(4)(c)', 'Correctly interpret the output', (es) =>
    some(refs(es, 'ruleId,reasonCodes', (e) => e.ruleId !== null && Array.isArray(e.reasonCodes) && e.reasonCodes.length > 0))],
  ['14(4)(d)', 'Decide not to use or override the output', (es) => {
    const rejected = refs(es, 'reviewers', (e) => verified(e, 'REJECTED'));
    if (rejected.length > 0) return { status: 'evidenced', evidence: rejected };
    return some(refs(es, 'reviewers', (e) => verified(e, 'APPROVED')), 'partial');
  }],
  ['14(4)(e)', 'Intervene or halt the system', (es) => {
    const blocked = refs(es, 'outcome', (e) => e.outcome === 'REQUIRE_APPROVAL');
    const reviewed = refs(es, 'reviewers', (e) => e.outcome === 'REQUIRE_APPROVAL' && hasReviewer(e));
    if (reviewed.length > 0) return { status: 'evidenced', evidence: [...blocked, ...reviewed] };
    return some(blocked, 'partial');
  }],
  ['14(5)', 'Verification by at least two persons', (es) => some(refs(es, 'reviewers', (e) => e.reviewers.length >= 2))],
];

export const ARTICLE14_CLAUSES: ReadonlyArray<{ clause: string; title: string }> = JUDGES.map(([clause, title]) => ({ clause, title }));

export function mapArticle14(entries: readonly EvidenceEntry[]): Article14Mapping {
  return {
    framework: 'EU_AI_ACT',
    article: '14',
    clauses: JUDGES.map(([clause, title, judge]) => ({ clause, title, ...judge(entries) })),
  };
}
```

- [ ] **Step 3: 跑测试、提交**

Run: `pnpm test:run -- src/__tests__/services/evidence/article14.test.ts`
Expected: PASS。

```bash
git add src/services/evidence/article14.ts src/__tests__/services/evidence/article14.test.ts
git commit -m "feat(evidence): Article 14 对照纯函数——逐款 evidenced/partial/none 与证据指向（ADR 0044 §3.1）"
```

---

### Task 5: 导出接线 What-If 查询（aster-cloud）

**Files:**
- Modify: `src/lib/evidence.ts:83-103`（`enrichEntries`）与 `createEvidenceExport` 的 `buildBundle` 调用
- Test: `src/__tests__/integration/evidence-export.integration.test.ts`（追加两个用例）
- Test: `src/__tests__/lib/evidence-enrich.test.ts`（若 `enrichEntries` 未导出，导出为 `enrichEntriesForTest` 或把 fetch 注入点抽成参数后单测）

**Interfaces:**
- Consumes: `fetchReplayItems`、`mergeReplayLookups`（Task 3）；`buildEvidenceEntry` 第四参数。
- Produces: `enrichEntries(rows, deps = { fetchReceipts, fetchDecisionReceipts, fetchReplayItems })` 返回 `{ entries, whatIfUnavailable }`。

- [ ] **Step 1: 测试（先红）**

在集成测试里追加（沿用文件现有的 seed 行辅助与 `LICENSE_E2E` 门）：

```ts
it('回放端点不可用：导出完成，whatIf 全 null，notes.whatIfUnavailable = 条目数，14(4)(a) 为 none', async () => {
  // 让 aster-api 基址指向无人监听端口，收据与回放都 unavailable
  process.env.ASTER_API_URL = 'http://127.0.0.1:1';
  await seedExecution({ id: 'exec-w1', outcome: 'REQUIRE_APPROVAL' });
  const { id } = await createEvidenceExport('u1', { format: 'json' });
  const bundle = await getEvidenceExportBundle('u1', id);
  expect(bundle!.manifest.schemaVersion).toBe('3');
  expect(bundle!.entries.every((e) => e.whatIf === null)).toBe(true);
  expect(bundle!.manifest.notes.whatIfUnavailable).toBe(bundle!.entries.length);
  expect(bundle!.manifest.regulatoryMapping.clauses.find((c) => c.clause === '14(4)(a)')!.status).toBe('none');
});
```

单测（注入 fetchReplayItems 桩）：rows 两行，桩返回 `items` 命中 `exec-1` → `entries[0].whatIf` 等于桩值、`entries[1].whatIf` null、`whatIfUnavailable 0`；桩把两行都放进 `unavailable` → 两条 null、`whatIfUnavailable 2`。

- [ ] **Step 2: 实现**

`enrichEntries` 增第四路查找：`lookupReplayByTenant(idsByTenant(rows, (r) => r.id), deps.fetchReplayItems, limiter)`（按 `policyTenantId` 分组，id 为执行行 id）；`buildEvidenceEntry(r, receipt, reviewers, replay.items.get(r.id) ?? null)`；`whatIfUnavailable = rows.filter((r) => replay.unavailable.has(r.id)).length`。`createEvidenceExport` 把 `whatIfUnavailable` 传给 `buildBundle`。注释说明：What-If 取不到不阻断导出，与收据同口径。

- [ ] **Step 3: 跑测试、提交**

Run: `pnpm test:run -- src/__tests__/lib/evidence-enrich.test.ts && LICENSE_E2E=1 DATABASE_URL=postgresql://postgres:postgres@localhost:55433/aster_cloud pnpm test:integration:saas -- evidence-export`
Expected: PASS（集成库沿用 `sdd-pg`，不碰 `aster-*`）。

```bash
git add src/lib/evidence.ts src/__tests__/lib/evidence-enrich.test.ts src/__tests__/integration/evidence-export.integration.test.ts
git commit -m "feat(evidence): 导出时按执行 id 取最新 What-If 结论挂到条目（ADR 0044 §3.3）"
```

---

### Task 6: 试点策略源（三语）、seed 脚本、用研模板迁移（aster-cloud）

**Files:**
- Create: `src/config/credit-pilot-source.ts`
- Create: `src/scripts/seed-credit-pilot.ts`
- Modify: `src/scripts/seed-usability-tenant.ts:48-73`（half/complete 模板内容）
- Modify: `package.json`（`"seed:credit-pilot": "tsx src/scripts/seed-credit-pilot.ts"`）
- Test: `src/__tests__/credit-pilot-source.compile.test.ts`

**Interfaces:**
- Produces: `creditPilotSource(locale: 'en'|'zh'|'de', threshold: 50000|80000): string`；`CREDIT_PILOT = { teamId:'credit-pilot', ownerId:'cp-owner', officerId:'cp-officer', analystId:'cp-analyst', policyId:'pol-credit-pilot', versionIds:['pv-credit-pilot-1','pv-credit-pilot-2'], role:'Credit Officer' }`；`PILOT_APPLICANTS` 四组（allow/deny/requireApproval/escalate，字段同 Task 1 用例的 allow/deny active default/require approval/escalate）。

- [ ] **Step 1: 编译测试（先红）**

照 `credit-risk-demo.compile.test.ts` 写：三语 × 两阈值各编译无 error 诊断；对 `PILOT_APPLICANTS` 四组用 `evaluate(core, 'decide', { applicant })` 断言 `ev.value.outcome` 分别为 `ALLOW / DENY / REQUIRE_APPROVAL / ESCALATE`，且 80000 阈值下 requireApproval 组（金额 60000）变为 `ALLOW`。zh/de 的规则名与参数名保持 ASCII（`decide`、`applicant`、字段名同英文），只翻译关键字，这样 execute 的输入键跨语言一致。

- [ ] **Step 2: 源文模块**

en 版逐字等于 Task 1 的 `.aster`（阈值参数化为 `${threshold}m`）。zh 版：`模块 aster.pilot.credit。` / `定义 Applicant 包含 creditScore 作为 整数，monthlyIncome 作为 Decimal，…` / `@id("CP-DECIDE")` `@control(...)` / `规则 decide 给定 applicant 作为 Applicant 产出 Verdict：` / `如果 applicant.activeDefaults 至少 1：` `返回 Verdict.deny("active_default")。` / `如果 applicant.monthlyDebt 大于 applicant.monthlyIncome 乘以 0.45m：` / `如果 applicant.requestedAmount 至少 ${threshold}m：` / `如果 applicant.creditScore 至少 620 并且 applicant.creditScore 至多 679：` / `返回 Verdict.allow()。`。de 版：`Modul …。` `Definiere Applicant hat … als Ganzzahl, … als Decimal.` `Regel decide gegeben applicant als Applicant liefert Verdict:` `wenn … mindestens 1:` `gib zurück Verdict.deny("active_default").` `größer als` → 若 de 词法用别的比较词，以 `credit-risk-demo.ts` 的 de 分支与 `DE_DE` 词法为准，编译测试会抓出。

- [ ] **Step 3: 跑编译测试转绿**

Run: `pnpm test:run -- src/__tests__/credit-pilot-source.compile.test.ts`
Expected: PASS 三语 × 两阈值。

- [ ] **Step 4: seed 脚本**

照 `seed-usability-tenant.ts` 的 upsert 结构（`postgres(url,{max:1,prepare:false})`、按 id 查有则 update 否则 insert）：
- users：`cp-owner` plan `team`，`cp-officer`/`cp-analyst` plan `pro`，email `<id>@stack.test`，密码哈希同用研脚本（`CP_SEED_PASSWORD` 默认 `Aster2026!`）。
- team `credit-pilot`（slug 同名，owner cp-owner）；teamMembers：cp-owner `owner`，cp-officer `member` + `businessRoles: ['Credit Officer']`，cp-analyst `member`。
- policy `pol-credit-pilot`：`name: 'Credit pilot (ADR 0044)'`，`content = creditPilotSource('en', 80000)`，`version: 2`，`teamId: 'credit-pilot'`，`userId: 'cp-owner'`。
- policyVersions：`pv-credit-pilot-1`（version 1，content en 50000）、`pv-credit-pilot-2`（version 2，content en 80000，`isDefault: true`），均 `status: 'APPROVED'`、`createdBy: 'cp-owner'`、`sourceHash: sha256(content)`、`sourceToolchainId: 'abi=1.0;core=1.0.30;validator=1;build=dev'`（与栈里 `pv-adr0041-team` 同值；若栈当前值不同，以 `psql aster_cloud "select \"sourceToolchainId\" from \"PolicyVersion\" where id='pv-adr0041-team-1'"` 为准）。
- API key：按 name `credit-pilot-analyst` 查 `apiKeys`（userId cp-analyst、teamId credit-pilot、未吊销）；无则 `createApiKey('cp-analyst','credit-pilot-analyst','credit-pilot')` 并打印明文一次；有则打印「已存在，明文不可再取」。
- 最后 `refreshTeamKeySnapshots('credit-pilot')`。
- 输出每步 `[seed-credit-pilot] …`。

- [ ] **Step 5: 用研模板迁移**

`tpl-half-loan.content` 改为 zh 源文只含 CP-001 与默认 allow 两分支（`模块 aster.finance.loan。` 保持模块名不变，规则名仍 `evaluateLoanEligibility`，返回 `Verdict.deny("active_default")` / `Verdict.allow()`），描述改「含违约判断与默认通过，缺负债、金额与分数分支」；`tpl-complete-loan.content` 改为完整五分支 zh 源文（阈值 50000），描述改「Verdict 四态完整版（任务 3 修改基线）」。`tpl-blank-loan` 不动。

- [ ] **Step 6: 本地验证 seed 幂等（sdd-pg）**

Run: `DATABASE_URL=postgresql://postgres:postgres@localhost:55433/aster_cloud pnpm seed:credit-pilot && DATABASE_URL=... pnpm seed:credit-pilot && psql postgresql://postgres:postgres@localhost:55433/aster_cloud -Atc 'select (select count(*) from "ApiKey" where "userId"='"'"'cp-analyst'"'"'), (select count(*) from "PolicyVersion" where "policyId"='"'"'pol-credit-pilot'"'"')'`
Expected: `1|2`。（`refreshTeamKeySnapshots` 对不可达 api 只打 warn 不抛错；若抛错，脚本捕获后打印并继续。）

- [ ] **Step 7: 门禁与提交**

Run: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm test:run -- credit`
Expected: PASS。

```bash
git add src/config/credit-pilot-source.ts src/scripts/seed-credit-pilot.ts src/scripts/seed-usability-tenant.ts package.json src/__tests__/credit-pilot-source.compile.test.ts
git commit -m "feat(pilot): 信贷试点三语 Verdict 策略、租户播种脚本、用研模板迁 Verdict（ADR 0044 §2/§5）"
```

---

### Task 7: 跑通脚本 `run-credit-pilot.ts`（aster-cloud）

**Files:**
- Create: `scripts/run-credit-pilot.ts`
- Create: `scripts/lib/credit-pilot-expectations.ts`
- Test: `src/__tests__/scripts/credit-pilot-expectations.test.ts`

**Interfaces:**
- Consumes: `CREDIT_PILOT`、`PILOT_APPLICANTS`（Task 6）；HTTP 面见 refs §7。
- Produces: `EXPECTED_CLAUSE_STATUS: Record<string, ClauseStatus>` = `{ '14(1)':'evidenced','14(2)':'evidenced','14(3)':'evidenced','14(4)(a)':'partial','14(4)(b)':'none','14(4)(c)':'evidenced','14(4)(d)':'partial','14(4)(e)':'evidenced','14(5)':'none' }`；`diffClauses(mapping, expected): string[]`（不符项描述，空数组即通过）。Task 9 的 spec 复用。

- [ ] **Step 1: 期望表与 diff 单测（先红）**

```ts
import { describe, it, expect } from 'vitest';
import { EXPECTED_CLAUSE_STATUS, diffClauses } from '../../../scripts/lib/credit-pilot-expectations';
describe('diffClauses', () => {
  it('完全一致为空', () => {
    const mapping = { clauses: Object.entries(EXPECTED_CLAUSE_STATUS).map(([clause, status]) => ({ clause, status })) };
    expect(diffClauses(mapping, EXPECTED_CLAUSE_STATUS)).toEqual([]);
  });
  it('不符与缺款各报一条', () => {
    const mapping = { clauses: [{ clause: '14(1)', status: 'none' }] };
    const d = diffClauses(mapping, { '14(1)': 'evidenced', '14(5)': 'none' });
    expect(d).toEqual(['14(1): expected evidenced, got none', '14(5): missing']);
  });
});
```

实现放 `scripts/lib/credit-pilot-expectations.ts`（纯函数，无 I/O）。

- [ ] **Step 2: 脚本**

环境：`BASE_CLOUD`（默认 `http://localhost:3100`）、`CP_API_KEY`（cp-analyst 的 Bearer）、`NEXTAUTH_SECRET`（伪造 session cookie，复用 `scripts/e2e-local-session.ts` 的 encode 逻辑——把 cookie 编码抽到 `scripts/lib/session-cookie.ts` 并让两处共用）、`OUT`（默认 `./credit-pilot-bundle.json`）。步骤，每步打印 `[pilot] step N …`，失败抛错退出 1：
1. `POST /api/v1/policies/pol-credit-pilot/execute`，Bearer，body `{ input: { applicant }, agent: { provider: 'pilot', model: 'credit-pilot-script' } }`，四组各一次；记录 `data.metadata.evidenceCorrelationId` 与 `data.outcome`，断言四种结论各一。
2. 以 cp-officer cookie `GET /api/approvals?status=PENDING`，找 `evidenceCorrelationId` 等于 requireApproval 那次的项，`POST /api/approvals/credit-pilot/<id>/approve` body `{ comment: 'pilot: approved by Credit Officer' }`，期望 200。
3. 以 cp-analyst cookie `POST /api/v1/policies/pol-credit-pilot/whatif-batches` body `{ baseVersionId: 'pv-credit-pilot-1', targetVersionId: 'pv-credit-pilot-2', windowKind: 'LAST_MONTH', includeToday: true }`，轮询 `GET …/whatif-batches/<id>` 每 2 s 至 `status === 'COMPLETED'`（上限 120 s）。
4. 以 cp-owner cookie `POST /api/reports` body `{ policyId: 'pol-credit-pilot', format: 'json' }` → `GET /api/reports/<id>/download` 写到 `OUT`。
5. `diffClauses(bundle.manifest.regulatoryMapping, EXPECTED_CLAUSE_STATUS)`；打印九款状态表；非空则退出 2。

注意 `POST /api/reports` 按 `policies.userId === session user` 查策略，故第 4 步必须用 cp-owner。

- [ ] **Step 3: 本地 tsc + 单测、提交**

Run: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm test:run -- credit-pilot-expectations`
Expected: PASS。栈上实跑放到 Task 10。

```bash
git add scripts/run-credit-pilot.ts scripts/lib scripts/e2e-local-session.ts src/__tests__/scripts/credit-pilot-expectations.test.ts
git commit -m "feat(pilot): 信贷试点端到端驱动脚本——执行、审批、What-If、导出并核对 Article 14 对照（ADR 0044 §5）"
```

---

### Task 8: 文档指南三语与侧栏（aster-cloud）

**Files:**
- Create: `src/app/[locale]/docs/guides/credit-pilot/page.tsx`（复制 `getting-started/errors/page.tsx`，`ROUTE_SLUG='/docs/guides/credit-pilot'`，Path 注释同改）
- Create: `src/app/[locale]/docs/guides/credit-pilot/{en,zh,de}.mdx`
- Modify: `src/lib/docs/sidebar.ts:84-91`（新节 `docs.sidebar.guides.title`，项 `docs.sidebar.guides.creditPilot` → `guides/credit-pilot`）
- Modify: `src/lib/docs/page-actions.ts:156`（`'guides/credit-pilot': { primary: openDashboard(), secondary: [viewMyTraces()] }`，若 `viewMyTraces` 需登录能力，照既有条目取用）
- Modify: `src/i18n/demo-supplement.ts`（en/zh/de/hi 四处 `docs` 下加 `sidebar: { guides: { title, creditPilot } }`）

- [ ] **Step 1: MDX 内容（en 为底，zh/de 全译，不设 fallback）**

frontmatter：`title: "Credit pilot: Article 14 evidence"`，`description`，`updated: 2026-10-09`，`apiVersion: v1`。正文章节：
1. 试点做什么（策略五分支表，`@control` 两条）。
2. 跑通步骤（`pnpm seed:credit-pilot` → `CP_API_KEY=… pnpm tsx scripts/run-credit-pilot.ts`，以及浏览器路径：执行 → `/approvals` 批准 → 版本对比 → `/reports` 导出）。
3. 对照表怎么读：九款各一行，列「状态 | 依据的字段 | 为什么只是 partial/none」，逐字引用 ADR 0044 §3.1 的判定；明确「本表只陈述证据包内已有的字段，不是合规结论」。
4. 独立核验：`GET /api/v1/audit/receipts?correlationIds=…` 复核收据；`notes.verification` 重算 bundleHash。

- [ ] **Step 2: 侧栏、动作登记、文案**

en：`title: 'Guides'`, `creditPilot: 'Credit pilot (Article 14)'`；zh：`'指南'`, `'信贷试点（第 14 条）'`；de：`'Leitfäden'`, `'Kreditpilot (Artikel 14)'`；hi 给英文同文（DEMO_SUPPLEMENT 缺 key 由 en 兜底，但 `check:message-keys` 若要求四语齐全则补）。

- [ ] **Step 3: 门禁**

Run: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm check:locales:strict && pnpm docs:index:build && pnpm check:docs-index && pnpm docs:descriptions && pnpm check:docs-descriptions && pnpm routes:check`
Expected: 全部通过；`docs:index:build`/`docs:descriptions` 产生的生成文件一并提交。

- [ ] **Step 4: 提交**

```bash
git add "src/app/[locale]/docs/guides" src/lib/docs src/i18n/demo-supplement.ts $(git status --porcelain | awk '{print $2}' | grep -E 'docs-index|descriptions' )
git commit -m "docs(guides): 信贷试点指南三语——跑通步骤、Article 14 对照解读、独立核验（ADR 0044 §5）"
```

---

### Task 9: 本地栈 Playwright 规格（aster-cloud）

**Files:**
- Modify: `scripts/e2e-local-session.ts`（`USERS` 加 `cp-owner` team / `cp-officer` pro / `cp-analyst` pro，email `<id>@stack.test`）
- Modify: `src/__tests__/e2e-browser/local-stack/helpers.ts:15`（`stateFor` 联合类型加三者）
- Modify: `src/__tests__/e2e-browser/local-stack/global-setup.ts`（`WARM_PAGES` 加 `['/en/policies/pol-credit-pilot','cp-analyst']`、`['/en/approvals','cp-officer']`、`['/en/reports','cp-owner']`；`WARM_APIS` 加 `/api/v1/policies/pol-credit-pilot/versions`、`/api/v1/policies/pol-credit-pilot/whatif-batches`、`/api/policies/pol-credit-pilot/logs`）
- Create: `src/__tests__/e2e-browser/local-stack/credit-pilot.spec.ts`
- Modify: `src/__tests__/e2e-browser/local-stack/README.md`（前置：`pnpm seed:credit-pilot`，并用 `CP_API_KEY` 先跑一次 run 脚本的第 1 步，或由 spec 自己通过页面执行）

- [ ] **Step 1: spec**

串行，`test.setTimeout(180_000)`：
1. `test.use({ storageState: stateFor('cp-analyst') })` 进入 `/en/policies/pol-credit-pilot`，用页面执行面板（选择器照 `policy-logs-guard-link.spec.ts` 里执行与日志的做法；若页面无执行面板，则在 `test.beforeAll` 用 `page.request.post('/api/v1/policies/pol-credit-pilot/execute', { headers: { Authorization: 'Bearer ' + process.env.CP_API_KEY }, data: { input: { applicant: PILOT_APPLICANTS.requireApproval } } })` 执行，缺 `CP_API_KEY` 则 `test.skip`）。
2. 日志页 `/en/policies/pol-credit-pilot/logs` 最新一行含 guard 链接（照 `policy-logs-guard-link.spec.ts` 的断言）。
3. 切 `cp-officer` 上下文（`browser.newContext({ storageState: stateFor('cp-officer') })`）打开 `/en/approvals`，按 `psql('aster_policy', "select decision_id from guard_approvals where tenant_id='credit-pilot' and status='PENDING' order by created_at desc limit 1")` 定位行，Approve → 对话框 → `Confirm approval`，断言 psql 状态 `APPROVED`。
4. `cp-analyst` 打开策略页 `Compare versions`，选 v1/v2，勾 `Include today`，`Run analysis`，等 `Decision transitions`，断言存在 `Needs approval → Allow` 行且计数 ≥ 1。
5. `cp-owner` 打开 `/en/reports`，Policy 选 `Credit pilot (ADR 0044)`，Format JSON，Export，下载解析：`manifest.schemaVersion === '3'`；`diffClauses(manifest.regulatoryMapping, EXPECTED_CLAUSE_STATUS)` 为空；至少一条 `entries[].whatIf` 非 null。

- [ ] **Step 2: tsc/lint、提交**

Run: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint`
Expected: PASS。实跑在 Task 10。

```bash
git add scripts/e2e-local-session.ts src/__tests__/e2e-browser/local-stack
git commit -m "test(e2e): 信贷试点本地栈规格——执行、审批、What-If、导出与 Article 14 对照断言（ADR 0044 §5）"
```

---

### Task 10: 全栈验收与 ADR 定稿（aster-api + aster-cloud + aster-lang-test）

**Files:**
- Modify: `docs/adr/0044-annex-iii-credit-pilot.md`（状态 ACCEPTED，§9.1 验收记录）
- Modify: `docs/adr/0039-verdict-primitive-and-rule-metadata.md`（§1 行 4 指向 ADR 0044；行 304 段落标记「已在 ADR 0044 完成」）
- Modify: `docs/adr/0043-whatif-four-state-replay.md:145`（第一条改为「已做：ADR 0044 §4 `GET /api/v1/replay/items`」）

- [ ] **Step 1: 重建 api 镜像并滚动栈 api**

按记忆文件 `aster-local-verification-env.md` 的配方：`./gradlew --offline quarkusBuild -x test` → 构建 `localhost/aster/policy-api:stack` → 以同样 env 重建 `aster-api` 容器。cloud 容器挂载源码，`podman restart aster-cloud`。

- [ ] **Step 2: 播种与会话夹具**

`podman exec -w /app aster-cloud sh -c 'DATABASE_URL=postgresql://postgres:postgres@aster-pg:5432/aster_cloud npx tsx src/scripts/seed-credit-pilot.ts'`（记录打印的 `CP_API_KEY`），再 `podman exec -w /app aster-cloud sh -c 'npx tsx scripts/e2e-local-session.ts'`。

- [ ] **Step 3: run 脚本**

`CP_API_KEY=… NEXTAUTH_SECRET=$(grep NEXTAUTH_SECRET /private/tmp/aster-stack/env | cut -d= -f2) pnpm tsx scripts/run-credit-pilot.ts`
Expected: 退出码 0，打印九款状态与预期表一致，`credit-pilot-bundle.json` 生成；把输出贴进 `/private/tmp/aster-stack/sub4-acceptance.md`。

- [ ] **Step 4: Playwright**

`BASE_CLOUD=http://localhost:3100 CP_API_KEY=… pnpm exec playwright test src/__tests__/e2e-browser/local-stack --project=chromium-desktop --workers=1`
Expected: 全部通过（含既有五个规格）。

- [ ] **Step 5: 全量门禁**

api：`./gradlew --offline test`（对照基线：GraphQL LargeVolume 及其连带、PrismaDatabaseE2ETest.test0、WorkflowConcurrency 已知项）；cloud：完整门禁命令；aster-lang-test：`node scripts/parity-tier1.mjs --mode=parse && --mode=ir --full && --mode=eval`。

- [ ] **Step 6: ADR 写回与提交**

```bash
git add docs/adr/0044-annex-iii-credit-pilot.md docs/adr/0039-verdict-primitive-and-rule-metadata.md docs/adr/0043-whatif-four-state-replay.md
git commit -m "docs(adr): ADR 0044 ACCEPTED，0039/0043 回写"
```

- [ ] **Step 7: 更新记忆文件**

`aster-governance-roadmap-state.md`：子项目 4 完成状态、分支名与 commit；`aster-local-verification-env.md`：seed:credit-pilot 与 run 脚本用法。
