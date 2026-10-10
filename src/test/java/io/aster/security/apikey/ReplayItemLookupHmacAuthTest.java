package io.aster.security.apikey;

import io.aster.policy.replay.batch.ReplayBatchEntity;
import io.aster.policy.replay.batch.ReplayBatchStatus;
import io.aster.policy.replay.batch.ReplayBatchItemEntity;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * /api/v1/replay/items 的双通道鉴权端到端：HMAC 内部调用者（无 Bearer）与 Bearer API key 均可访问，
 * 无凭证由 ApiKeyAuthFilter 返回 401，租户以签名绑定的身份为准。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(ReplayItemLookupHmacAuthTest.AuthProfile.class)
class ReplayItemLookupHmacAuthTest {

    /** apikey 鉴权与 HMAC 密钥同时打开；signature 关闭以贴近生产。 */
    public static class AuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", HMAC_KEY,
                "aster.security.apikey.enabled", "true",
                "aster.security.signature.enabled", "false",
                "aster.security.evaluate-source.public", "false",
                "aster.security.evaluate-source.trial.enabled", "false");
        }
    }

    static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
    static final String PATH = "/api/v1/replay/items";
    static final String TENANT = "tenant-replay-hmac";
    static final String OTHER = "tenant-replay-hmac-other";
    static final String BEARER_KEY = "ak_test_replay_member";

    @Inject EntityManager em;
    @Inject ApiKeyVerifierService verifier;

    @BeforeEach void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM replay_batch_item").executeUpdate();
            em.createNativeQuery("DELETE FROM replay_batch").executeUpdate();
        });
        completedReplay(TENANT, "e-hmac");
        completedReplay(OTHER, "e-hmac");
        verifier.seedCacheForTest(BEARER_KEY,
            ApiKeyVerifyResult.valid("ak-rp-1", "user-rp", TENANT, "pro", "active", "MEMBER"));
    }

    /** 已完成批次带一条成功条目，同事务落库：查询口径只认 COMPLETED 且 success 的条目，完成计数由提交时的延迟约束对账。 */
    void completedReplay(String tenant, String execId) {
        Instant now = Instant.now();
        ReplayBatchEntity b = new ReplayBatchEntity();
        b.id = UUID.randomUUID();
        b.tenantId = tenant; b.userId = "u"; b.policyId = "p";
        b.baseVersionId = "v1"; b.targetVersionId = "v2";
        b.windowKind = "LAST_MONTH"; b.windowLabel = "x"; b.windowFrom = now; b.windowTo = now.plusSeconds(1);
        b.status = ReplayBatchStatus.COMPLETED; b.toolchainId = "tc"; b.expiresAt = now.plusSeconds(3600); b.createdAt = now;
        // 满足表上按状态的 CHECK：COMPLETED 带结果摘要且全数成功
        b.plannedCount = 1; b.completedCount = 1; b.resultSummary = "{}";
        ReplayBatchItemEntity i = new ReplayBatchItemEntity(b.id, execId, true, "ALLOW", false);
        i.targetOutcome = "ALLOW"; i.success = true;
        QuarkusTransaction.requiringNew().run(() -> { b.persist(); i.persist(); });
    }

    /** 与 cloud signInternalCallerHeaders 一致（canonical v3）：GET 无 body，query/tenant/role/userId 均签入。 */
    static Response signedGet(String tenant, String signKey) {
        return given()
            .headers(InternalCallerSigning.headers(signKey, "GET", PATH, "executionIds=e-hmac", "",
                tenant, "member", "evidence-export", List.of()))
            .queryParam("executionIds", "e-hmac")
            .get(PATH);
    }

    @Test void HMAC内部调用者无Bearer返回本租户条目() throws Exception {
        Response r = signedGet(TENANT, HMAC_KEY);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).hasSize(1);
        assertThat(r.jsonPath().getString("items[0].executionId")).isEqualTo("e-hmac");
    }

    @Test void HMAC签陌生租户返回空() throws Exception {
        Response r = signedGet("tenant-replay-hmac-stranger", HMAC_KEY);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).isEmpty();
    }

    @Test void Bearer通道仍可用() {
        Response r = given().header("Authorization", "Bearer " + BEARER_KEY)
            .queryParam("executionIds", "e-hmac").get(PATH);
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
        assertThat(r.jsonPath().getList("items")).hasSize(1);
    }

    @Test void 无Bearer无HMAC头返回401() {
        given().header("X-Tenant-Id", TENANT).header("X-User-Id", "u").queryParam("executionIds", "e-hmac")
            .get(PATH).then().statusCode(401);
    }
}
