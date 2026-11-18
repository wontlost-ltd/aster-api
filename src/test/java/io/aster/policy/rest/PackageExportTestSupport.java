package io.aster.policy.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.entity.AuditLog;
import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/** 策略包导出端点测试的共用 profile、请求构造与审计查询。 */
final class PackageExportTestSupport {
  static final String HMAC_KEY = "adr0059-package-export-test-key-32b!";
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

  /** 基础：HMAC 内部调用可验签、API key 与 RBAC 照常开启；node 白名单指向测试副本，使 Node 导出路径可测。 */
  public static class PkgProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return base();
    }

    static Map<String, String> base() {
      Map<String, String> m = new HashMap<>();
      m.put("aster.plan-gate.hmac-key", HMAC_KEY);
      m.put("aster.plan-gate.enabled", "false");
      m.put("aster.security.apikey.enabled", "true");
      m.put("aster.security.rbac.enabled", "true");
      m.put("aster.security.signature.enabled", "false");
      m.put("aster.security.evaluate-source.public", "false");
      m.put("aster.security.evaluate-source.trial.enabled", "false");
      m.put("aster.export.package.enabled", "true");
      m.put("aster.export.node-builtins-resource", "export/export-builtins-node.test.json");
      return m;
    }
  }

  /** 开关关闭。 */
  public static class DisabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> m = PkgProfile.base();
      m.put("aster.export.package.enabled", "false");
      return m;
    }
  }

  /** 并发许可为 1，验证 prepare 与导出共用同一个闸。 */
  public static class BusyProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> m = PkgProfile.base();
      m.put("aster.export.package.max-concurrent", "1");
      return m;
    }
  }

  /** 仓内真实锁文件（jre 1.0.49、trustAnchorKids []、node null）：首次上线的 fail-closed 形态。 */
  public static class ShippedLockProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> m = PkgProfile.base();
      m.put("aster.export.runtimes-lock", "export/export-runtimes.lock.json");
      m.remove("aster.export.node-builtins-resource");
      return m;
    }
  }

  /** 未配置导出密钥。 */
  public static class NoKeysProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> m = PkgProfile.base();
      m.put("aster.export.signing-keys", "");
      m.put("aster.export.active-kid", "");
      return m;
    }
  }

  /** on-prem：未配置密钥且显式 allow-unsigned。 */
  public static class UnsignedProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> m = PkgProfile.base();
      m.put("aster.export.signing-keys", "");
      m.put("aster.export.active-kid", "");
      m.put("aster.export.package.allow-unsigned", "true");
      return m;
    }
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
