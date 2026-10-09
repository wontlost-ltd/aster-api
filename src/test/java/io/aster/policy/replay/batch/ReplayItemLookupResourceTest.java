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
        b.windowKind = "LAST_MONTH"; b.windowLabel = "x"; b.windowFrom = createdAt; b.windowTo = createdAt.plusSeconds(1);
        b.status = ReplayBatchStatus.FAILED; b.toolchainId = "tc"; b.expiresAt = createdAt.plusSeconds(3600); b.createdAt = createdAt;
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
        String many = java.util.stream.IntStream.rangeClosed(0, io.aster.policy.entity.AuditLog.MAX_LOOKUP_IDS)
            .mapToObj(n -> "x" + n).collect(java.util.stream.Collectors.joining(","));
        Response r = get(TENANT, many);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.jsonPath().getString("error")).isEqualTo("too_many_ids");
    }
}
