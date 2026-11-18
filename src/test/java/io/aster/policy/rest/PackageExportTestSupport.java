package io.aster.policy.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.entity.AuditLog;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * 策略包导出端点测试的共用请求构造与审计查询。全部测试类沿用 {@link CedarExportResourceTest.AuthProfile}：
 * 每多一个 profile 就多一次 Quarkus 重启，全量测试在同一 JVM 内累积内存；开关、签名结论与 Node 白名单改用测试钩子与 mock。
 */
final class PackageExportTestSupport {
  /** 复用 Cedar 导出测试的 profile（同一套 HMAC 密钥），不为包导出新增 Quarkus 重启。 */
  static final String HMAC_KEY = CedarExportResourceTest.HMAC_KEY;
  static final String PATH = PackageExportResource.PATH;
  static final ObjectMapper JSON = new ObjectMapper();
  static final String SOURCE = String.join("\n",
      "Module aster.pilot.credit.",
      "",
      "Define Applicant has creditScore as Int, monthlyIncome as Decimal, monthlyDebt as Decimal.",
      "",
      "@id(\"CP-DECIDE\")",
      "@control(\"EU_AI_ACT:ART14\")",
      "Rule decide given applicant as Applicant, produce Verdict:",
      "  If applicant.monthlyDebt greater than applicant.monthlyIncome times 0.45m:",
      "    Return Verdict.deny(\"dti_exceeds_limit\").",
      "  If applicant.creditScore at least 620:",
      "    Return Verdict.allow().",
      "  Return Verdict.escalate(\"thin_file\").",
      "");

  private PackageExportTestSupport() {
  }

  static Map<String, Object> request(String target, String source) {
    Map<String, Object> claimed = new LinkedHashMap<>();
    claimed.put("policyId", "pol-credit");
    claimed.put("version", 3);
    claimed.put("status", "APPROVED");
    claimed.put("approvedAt", "2026-11-01T00:00:00Z");
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("source", source);
    r.put("locale", "en-US");
    r.put("target", target);
    r.put("mode", "b");
    r.put("uiLocale", "en");
    r.put("claimedRef", claimed);
    return r;
  }

  static Response hmacPost(String path, Object body, String tenant) {
    String json;
    try {
      json = JSON.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return given().headers(InternalCallerSigning.headers(HMAC_KEY, "POST", path, "", json, tenant, "MEMBER", "u-1",
            List.of()))
        .contentType(ContentType.JSON).body(json).when().post(path);
  }

  static long exportEvents(String tenant) {
    return QuarkusTransaction.requiringNew().call(() ->
        AuditLog.count("tenantId = ?1 and eventType = 'POLICY_EXPORTED'", tenant));
  }

  static AuditLog exportEvent(String tenant) throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      List<AuditLog> rows = QuarkusTransaction.requiringNew().call(() ->
          AuditLog.<AuditLog>find("tenantId = ?1 and eventType = 'POLICY_EXPORTED'", tenant).list());
      if (!rows.isEmpty()) {
        return rows.getFirst();
      }
      Thread.sleep(100);
    }
    throw new AssertionError("POLICY_EXPORTED 未入链: " + tenant);
  }
}
