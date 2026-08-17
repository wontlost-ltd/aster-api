package io.aster.audit;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.aster.test.BlockingDbTestHelper;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Phase 0 Task 3.4 - 审计哈希链验证 REST API 集成测试
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
public class TamperDetectionIntegrationTest {

    @jakarta.inject.Inject
    io.aster.audit.chain.AuditChainAnchorService anchorService;

    @Inject
    BlockingDbTestHelper db;

    @Inject
    Event<AuditEvent> auditEventProducer;

    @BeforeEach
    void cleanup() {
        // 顺序不可颠倒：锚点触发器要求锚定点对应的审计记录已不存在才允许删锚点
        db.executeAsAuditMaintenance("DELETE FROM audit_logs");
        db.executeAsAnchorRetention("DELETE FROM audit_chain_anchors");
    }

    @Test
    void testVerifyValidChain() throws Exception {
        // 创建有效的哈希链
        String tenantId = "tenant-api-valid";
        Instant start = Instant.now().minusSeconds(3600); // 1 hour ago

        for (int i = 0; i < 3; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50);
        }

        Instant end = Instant.now().plusSeconds(60); // +1 minute buffer

        // ★必须先锚定：verify-chain 自 V6.23.0 起把「有审计记录却零锚点」判为
        //   不可信（锚定从未成功执行，最常见成因是应用角色缺锚点表写权限）。
        //   此前该场景静默返回 valid=true，使「层 3 从未工作」与「确已完好」
        //   不可区分，属 fail-open。生产由每小时的定时任务锚定，此处显式触发。
        anchorService.anchorAllTenants();

        // 调用 API 验证链
        given()
            .header("X-Tenant-Id", tenantId)
            .queryParam("start", start.toString())
            .queryParam("end", end.toString())
            .when()
            .get("/api/v1/audit/verify-chain")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("valid", equalTo(true))
            .body("brokenAt", nullValue())
            .body("reason", nullValue())
            .body("recordsVerified", equalTo(3));
    }

    @Test
    void testDetectTamperedRecord() throws Exception {
        // 创建链并篡改记录
        String tenantId = "tenant-api-tampered";
        Instant start = Instant.now().minusSeconds(3600); // 1 hour ago

        for (int i = 0; i < 3; i++) {
            AuditEvent event = createEvent(tenantId, "POLICY_EVALUATION", "test.module", "func" + i);
            auditEventProducer.fireAsync(event);
            waitForAuditRecord(tenantId, i + 1);
            Thread.sleep(50);
        }

        // 篡改中间记录
        db.executeAsAuditTamper("UPDATE audit_logs SET policy_module = 'hacked.module' WHERE id IN (SELECT id FROM audit_logs WHERE tenant_id = ? ORDER BY timestamp LIMIT 1 OFFSET 1)", tenantId);

        Instant end = Instant.now().plusSeconds(60); // +1 minute buffer

        // 调用 API - 应该检测到篡改
        given()
            .header("X-Tenant-Id", tenantId)
            .queryParam("start", start.toString())
            .queryParam("end", end.toString())
            .when()
            .get("/api/v1/audit/verify-chain")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("valid", equalTo(false))
            .body("brokenAt", notNullValue())
            .body("reason", containsString("current_hash tampered"))
            .body("recordsVerified", equalTo(1));
    }

    @Test
    void testMissingParameters() {
        // 测试缺少必需参数
        given()
            .header("X-Tenant-Id", "tenant-test")
            .queryParam("start", "2025-01-15T10:00:00Z")
            // 缺少 end 参数
            .when()
            .get("/api/v1/audit/verify-chain")
            .then()
            .statusCode(400)
            .body("error", containsString("Missing required parameters"));

        // 测试无效时间格式
        given()
            .header("X-Tenant-Id", "tenant-test")
            .queryParam("start", "invalid-time")
            .queryParam("end", "2025-01-15T10:00:00Z")
            .when()
            .get("/api/v1/audit/verify-chain")
            .then()
            .statusCode(400)
            .body("error", containsString("Invalid time format"));

        // 测试时间范围过大（超过 30 天）
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = Instant.parse("2025-02-15T00:00:00Z"); // 45 天

        given()
            .header("X-Tenant-Id", "tenant-test")
            .queryParam("start", start.toString())
            .queryParam("end", end.toString())
            .when()
            .get("/api/v1/audit/verify-chain")
            .then()
            .statusCode(400)
            .body("error", containsString("Time range too large"));
    }

    private AuditEvent createEvent(String tenantId, String eventType, String module, String function) {
        return new AuditEvent(
            EventType.valueOf(eventType),
            Instant.now(),
            tenantId,
            module,
            function,
            null,
            null,
            null,
            "test-user",
            true,
            50L,
            null,
            Map.of(),
            null, null, null, null  // Phase 3.7 fields
        );
    }

    private void waitForAuditRecord(String tenantId, int expectedCount) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            long count = AuditLog.count("tenantId = ?1", tenantId);
            if (count >= expectedCount) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timeout waiting for audit record: tenant=" + tenantId + ", expected=" + expectedCount);
    }
}
