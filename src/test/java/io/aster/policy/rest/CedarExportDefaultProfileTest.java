package io.aster.policy.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * ADR 0047 §6：默认测试配置关闭了 API key 过滤器，导出端点仍须拒绝没有已验证身份的调用，
 * 不能退回到客户端自述的 X-Tenant-Id。
 */
@QuarkusTest
class CedarExportDefaultProfileTest {

  /** 完全匿名（连 X-Tenant-Id 都没有）的请求先被 TenantFilter 以 400 拒绝，到不了端点。 */
  @Test
  void anonymousWithoutTenantIsRejectedByTenantFilter() {
    given().contentType(ContentType.JSON).body(Map.of("source", "Module m."))
        .post(CedarExportResourceTest.PATH).then().statusCode(400);
  }

  @Test
  void selfDeclaredTenantIsRejectedEvenWithApiKeyAuthDisabled() {
    given().header("X-Tenant-Id", "tenant-cedar-default").header("X-User-Role", "MEMBER")
        .contentType(ContentType.JSON).body(Map.of("source", "Module m."))
        .post(CedarExportResourceTest.PATH).then().statusCode(401)
        .body("error", equalTo("unauthenticated"));
  }
}
