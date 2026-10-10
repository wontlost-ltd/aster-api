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

    /** 一个批次带一条条目，同事务落库：已完成批次的计数由提交时的延迟约束对账。 */
    UUID replay(String tenant, Instant createdAt, ReplayBatchStatus status, String execId, String base, String target) {
        ReplayBatchEntity b = new ReplayBatchEntity();
        b.id = UUID.randomUUID();
        b.tenantId = tenant; b.userId = "u"; b.policyId = "p";
        b.baseVersionId = "v1"; b.targetVersionId = "v2";
        b.windowKind = "LAST_MONTH"; b.windowLabel = "x"; b.windowFrom = createdAt; b.windowTo = createdAt.plusSeconds(1);
        b.status = status; b.toolchainId = "tc"; b.expiresAt = createdAt.plusSeconds(3600); b.createdAt = createdAt;
        b.plannedCount = 1;
        // 满足表上按状态的 CHECK：COMPLETED 带结果摘要且全数成功，RUNNING 已冻结、持 lease 并占槽
        if (status == ReplayBatchStatus.COMPLETED) { b.resultSummary = "{}"; b.completedCount = 1; }
        if (status == ReplayBatchStatus.RUNNING) {
            b.windowFrozenAt = createdAt; b.leaseExpiresAt = createdAt.plusSeconds(600);
            b.leaseOwner = "w"; b.concurrencySlot = 0;
        }
        ReplayBatchItemEntity i = new ReplayBatchItemEntity(b.id, execId, "ALLOW".equals(base), base, false);
        i.targetOutcome = target; i.success = true;
        QuarkusTransaction.requiringNew().run(() -> { b.persist(); i.persist(); });
        return b.id;
    }

    UUID replay(String tenant, Instant createdAt, String execId, String base, String target) {
        return replay(tenant, createdAt, ReplayBatchStatus.COMPLETED, execId, base, target);
    }

    Response get(String tenant, String ids) {
        return given().header("X-Tenant-Id", tenant).header("X-User-Id", "u").header("X-User-Role", "member")
            .queryParam("executionIds", ids).get(PATH);
    }

    @Test void 同执行多批只返回最新一批() {
        replay(TENANT, Instant.parse("2026-10-01T00:00:00Z"), "e1", "REQUIRE_APPROVAL", "ESCALATE");
        UUID latest = replay(TENANT, Instant.parse("2026-10-05T00:00:00Z"), "e1", "REQUIRE_APPROVAL", "ALLOW");
        Response r = get(TENANT, "e1");
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).hasSize(1);
        assertThat(r.jsonPath().getString("items[0].batchId")).isEqualTo(latest.toString());
        assertThat(r.jsonPath().getString("items[0].targetOutcome")).isEqualTo("ALLOW");
        assertThat(r.jsonPath().getBoolean("items[0].baseLegacy")).isFalse();
    }

    @Test void 最新批次在途或失败时回退到上一个已完成批次() {
        UUID done = replay(TENANT, Instant.parse("2026-10-01T00:00:00Z"), "e1", "REQUIRE_APPROVAL", "ALLOW");
        replay(TENANT, Instant.parse("2026-10-03T00:00:00Z"), ReplayBatchStatus.FAILED, "e1", "REQUIRE_APPROVAL", "ERROR");
        replay(TENANT, Instant.parse("2026-10-05T00:00:00Z"), ReplayBatchStatus.RUNNING, "e1", "REQUIRE_APPROVAL", null);
        Response r = get(TENANT, "e1");
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).hasSize(1);
        assertThat(r.jsonPath().getString("items[0].batchId")).isEqualTo(done.toString());
        assertThat(r.jsonPath().getString("items[0].targetOutcome")).isEqualTo("ALLOW");
    }

    @Test void 仅有失败批次时返回空() {
        replay(TENANT, Instant.now(), ReplayBatchStatus.FAILED, "e1", "ALLOW", "ERROR");
        Response r = get(TENANT, "e1");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).isEmpty();
    }

    @Test void 跨租户与无回放的id静默不返回() {
        replay(OTHER, Instant.now(), "e-other", "ALLOW", "ALLOW");
        Response r = get(TENANT, "e-other,e-none");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).isEmpty();
    }

    @Test void 缺参与超限返回400() {
        assertThat(given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u").header("X-User-Role", "member")
            .get(PATH).statusCode()).isEqualTo(400);
        String many = java.util.stream.IntStream.rangeClosed(0, io.aster.policy.entity.AuditLog.MAX_LOOKUP_IDS)
            .mapToObj(n -> "x" + n).collect(java.util.stream.Collectors.joining(","));
        Response r = get(TENANT, many);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.jsonPath().getString("error")).isEqualTo("too_many_ids");
    }
}
