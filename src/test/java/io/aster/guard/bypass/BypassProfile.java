package io.aster.guard.bypass;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * 绕过套件的 Quarkus profile（#466）：apikey 鉴权、RBAC 与 HMAC 全部打开，红队请求必须穿过真实过滤器，
 * 身份只能来自 {@code Authorization: Bearer}（不接受 {@code X-Tenant-Id} 直通）。
 *
 * <p>与 {@code GuardReceiptResourceAuthTest.AuthProfile} 同一组鉴权键，另把 RBAC 打开（VIEWER 403 用例需要）；
 * 执行收据签名密钥沿用默认测试配置（{@code test-a} active、{@code test-b} 未撤销、{@code revision} 1）——
 * {@code revision} 不得改动：启动校验拒绝低于已持久化最大值的 revision。撤销 kid 场景复用 AuthProfile，
 * 不新建第三套 profile。
 */
public class BypassProfile implements QuarkusTestProfile {
  /** 与 {@code GuardReceiptResourceAuthTest} 相同的测试 HMAC 密钥（仅测试）。 */
  public static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "aster.plan-gate.hmac-key", HMAC_KEY,
        "aster.security.apikey.enabled", "true",
        "aster.security.rbac.enabled", "true",
        "aster.security.signature.enabled", "false",
        "aster.security.evaluate-source.public", "false",
        "aster.security.evaluate-source.trial.enabled", "false");
  }
}
