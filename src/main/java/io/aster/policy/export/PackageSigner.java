package io.aster.policy.export;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.common.signing.Ed25519KeyRing;
import io.aster.guard.receipt.JcsCanonicalizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

/**
 * 策略包 manifest 签名（ADR 0059 §6.2，修订 3 P2-R2 / P2-R3）。被签字节 = {@code manifest.json} 文件字节本身；
 * {@code manifest.sig.json} = 规范 JSON {@code {"alg":"Ed25519","issuer":"aster-cloud","kid":…,"sig":<base64url 无填充>}}。
 *
 * <p>判定顺序（都发生在组装 zip 之前，调用方据 {@link Refused} 返回 503、不写审计）：
 * <ol>
 *   <li>target 在锁文件中为 {@code null} → {@code export_runtime_unavailable}；</li>
 *   <li>未配置导出密钥：{@code allow-unsigned=true}（仅 on-prem）→ {@link Unsigned}，否则 {@code export_signing_unavailable}；</li>
 *   <li>active key 此刻已过 notAfter → {@code export_signing_unavailable}（启动后才过期的情形）；</li>
 *   <li>active kid 不在该 target 的 {@code trustAnchorKids} → {@code export_signing_kid_unanchored}（先发布锚，再签名）。</li>
 * </ol>
 * 已配置密钥时 {@code allow-unsigned} 不起作用：有密钥就必须签。
 */
@ApplicationScoped
public class PackageSigner {
  public static final String ISSUER = "aster-cloud";
  public static final String SIGNING_UNAVAILABLE = "export_signing_unavailable";
  public static final String KID_UNANCHORED = "export_signing_kid_unanchored";
  public static final String RUNTIME_UNAVAILABLE = "export_runtime_unavailable";

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  /** 签名结论。 */
  public sealed interface SignOutcome permits Signed, Unsigned, Refused {
  }

  /** 已签名：{@code sigJson} 为 {@code manifest.sig.json} 的完整字节。 */
  public record Signed(byte[] sigJson, String kid) implements SignOutcome {
  }

  /** 无签名导出（仅 on-prem 显式允许且未配置密钥）。 */
  public record Unsigned() implements SignOutcome {
  }

  /** 拒绝导出；{@code errorCode} 为对外 503 错误码。 */
  public record Refused(String errorCode) implements SignOutcome {
  }

  private final ExportSigningKeys keys;
  private final ExportRuntimes runtimes;
  private final boolean allowUnsigned;
  private final Clock clock;

  @Inject
  public PackageSigner(ExportSigningKeys keys, ExportRuntimes runtimes,
                       @ConfigProperty(name = "aster.export.package.allow-unsigned", defaultValue = "false")
                       boolean allowUnsigned,
                       Clock clock) {
    this.keys = keys;
    this.runtimes = runtimes;
    this.allowUnsigned = allowUnsigned;
    this.clock = clock;
  }

  /** 对 manifest 文件字节签名，或给出无签名 / 拒绝的结论。 */
  public SignOutcome sign(byte[] manifestBytes, String target) {
    return decide(manifestBytes, keys.ring(), runtimes.target(target), allowUnsigned, clock);
  }

  static SignOutcome decide(byte[] manifestBytes, Optional<Ed25519KeyRing> ring,
                            Optional<ExportRuntimes.Target> target, boolean allowUnsigned, Clock clock) {
    if (target.isEmpty()) {
      return new Refused(RUNTIME_UNAVAILABLE);
    }
    if (ring.isEmpty()) {
      return allowUnsigned ? new Unsigned() : new Refused(SIGNING_UNAVAILABLE);
    }
    Ed25519KeyRing.SigningKey active = ring.get().active();
    if (active.expiredAt(clock.instant())) {
      return new Refused(SIGNING_UNAVAILABLE);
    }
    if (!target.get().trustAnchorKids().contains(active.kid())) {
      return new Refused(KID_UNANCHORED);
    }
    byte[] sig = ed25519(active.keyPair().getPrivate(), manifestBytes);
    return new Signed(sigJson(active.kid(), sig), active.kid());
  }

  /** {@code manifest.sig.json} 的规范 JSON 字节。 */
  static byte[] sigJson(String kid, byte[] signature) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("alg", "Ed25519");
    node.put("issuer", ISSUER);
    node.put("kid", kid);
    node.put("sig", B64.encodeToString(signature));
    return JcsCanonicalizer.canonicalize(node);
  }

  /** Ed25519 对字节签名（RFC 8032，确定性）。 */
  static byte[] ed25519(PrivateKey key, byte[] message) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key);
      signer.update(message);
      return signer.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 签名失败", e);
    }
  }
}
