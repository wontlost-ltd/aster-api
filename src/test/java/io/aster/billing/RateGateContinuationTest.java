package io.aster.billing;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * 限流裁决异步完成后，evaluate 主体在续体中执行：必须仍在请求的 event-loop 上下文里，
 * routingContext、JAX-RS 请求属性与请求作用域 bean（TenantContext）都可读（否则 captureIdentity
 * 会读到 null，计费串号）。
 */
@QuarkusTest
class RateGateContinuationTest {

    @Test
    void continuationAfterAsyncGate_keepsRequestContext() {
        given().header("X-Probe", "hdr").header("X-Tenant-Id", "probe-tenant")
            .when().get("/test-393/rate-gate-probe")
            .then().statusCode(200)
            .body(equalTo("hdr|captured|probe-tenant|true"));
    }
}
