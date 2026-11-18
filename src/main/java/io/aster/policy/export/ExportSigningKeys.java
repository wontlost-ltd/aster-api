package io.aster.policy.export;

import io.aster.common.signing.Ed25519KeyRing;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 策略包导出签名密钥（ADR 0059 修订 3 P2-R2）：{@code aster.export.signing-keys} 与 {@code aster.export.active-kid}，
 * 形状与 Guard 收据密钥相同，解析共用 {@link Ed25519KeyRing}。
 *
 * <p>与收据密钥不同，导出是可选功能：两项都未配置 → {@link #ring()} 为空，Pod 照常启动，导出端点在签名前
 * 503 {@code export_signing_unavailable}（或在 on-prem 显式 {@code allow-unsigned} 时导出无签名包）。
 * 只配了其中一项、配置畸形、或在 {@code prod} profile 下出现 {@code test-} 开头的 kid（conformance
 * {@code keys/README.md} M4）→ 启动失败，消息不含种子。{@code allow-unsigned=true} 只在 on-prem
 * （{@code aster.plan-gate.enabled=false}）被接受，云上部署同时打开即启动失败。
 *
 * <p>{@link Startup}：配置错误在启动时暴露，而不是推迟到第一次导出。
 */
@Startup
@Singleton
public class ExportSigningKeys {
  static final String KEYS_PROPERTY = "aster.export.signing-keys";
  static final String ACTIVE_KID_PROPERTY = "aster.export.active-kid";
  static final String TEST_KID_PREFIX = "test-";

  private final Optional<Ed25519KeyRing> ring;

  static final String ALLOW_UNSIGNED_PROPERTY = "aster.export.package.allow-unsigned";
  static final String PLAN_GATE_PROPERTY = "aster.plan-gate.enabled";

  @Inject
  ExportSigningKeys(@ConfigProperty(name = KEYS_PROPERTY) Optional<String> json,
                    @ConfigProperty(name = ACTIVE_KID_PROPERTY) Optional<String> activeKid,
                    @ConfigProperty(name = ALLOW_UNSIGNED_PROPERTY, defaultValue = "false") boolean allowUnsigned,
                    @ConfigProperty(name = PLAN_GATE_PROPERTY, defaultValue = "false") boolean planGateEnabled,
                    Clock clock) {
    requireAllowUnsignedOnPremOnly(allowUnsigned, planGateEnabled);
    this.ring = load(json, activeKid, clock, ConfigUtils.getProfiles());
  }

  /**
   * {@code allow-unsigned} 只供 on-prem：云上部署由 aster-cloud BFF 计划门守护（{@code aster.plan-gate.enabled=true}），
   * 二者同时为 true 即启动失败，使生产不可能因误配而导出无签名包（缺密钥时只能 503）。
   *
   * @throws IllegalStateException 云上部署打开了 allow-unsigned
   */
  static void requireAllowUnsignedOnPremOnly(boolean allowUnsigned, boolean planGateEnabled) {
    if (allowUnsigned && planGateEnabled) {
      throw new IllegalStateException(ALLOW_UNSIGNED_PROPERTY + "=true 只供 on-prem（" + PLAN_GATE_PROPERTY
          + "=false）；云上部署缺导出密钥时必须 503 export_signing_unavailable");
    }
  }

  /**
   * 解析导出密钥配置。
   *
   * @throws IllegalStateException 只配了一项、配置无效、或 prod 下出现测试 kid
   */
  static Optional<Ed25519KeyRing> load(Optional<String> json, Optional<String> activeKid, Clock clock,
                                       List<String> profiles) {
    boolean hasKeys = json.filter(s -> !s.isBlank()).isPresent();
    boolean hasKid = activeKid.filter(s -> !s.isBlank()).isPresent();
    if (!hasKeys && !hasKid) {
      return Optional.empty();
    }
    if (hasKeys != hasKid) {
      throw new IllegalStateException(KEYS_PROPERTY + " 与 " + ACTIVE_KID_PROPERTY
          + " 必须同时配置或同时缺省（ASTER_EXPORT_SIGNING_KEYS / ASTER_EXPORT_ACTIVE_KID）");
    }
    Ed25519KeyRing parsed = Ed25519KeyRing.parse(json.get(), activeKid.get(), clock, KEYS_PROPERTY);
    if (profiles.contains("prod")) {
      parsed.all().stream().filter(k -> k.kid().startsWith(TEST_KID_PREFIX)).findFirst().ifPresent(k -> {
        throw new IllegalStateException(KEYS_PROPERTY + " 无效: kid " + k.kid()
            + " 以 " + TEST_KID_PREFIX + " 开头，测试密钥不得用于生产导出");
      });
    }
    return Optional.of(parsed);
  }

  /** 已配置的导出密钥环；未配置为空。 */
  public Optional<Ed25519KeyRing> ring() {
    return ring;
  }
}
