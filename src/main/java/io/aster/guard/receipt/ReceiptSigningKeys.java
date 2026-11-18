package io.aster.guard.receipt;

import io.aster.common.signing.Ed25519KeyRing;
import io.aster.common.signing.Ed25519KeyRing.SigningKey;
import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.KeyPair;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 执行收据签名密钥配置（ADR 0040 §12 D1）：解析 {@code aster.guard.receipt.signing-keys} 并选出
 * {@code aster.guard.receipt.active-kid}。解析、校验与 RFC 8032 公钥推导都在共用的 {@link Ed25519KeyRing}
 * （与策略包导出密钥同一份），本类只保留 CDI 外壳与配置属性名。任何配置错误都在启动时抛
 * {@link IllegalStateException}，消息绝不包含种子。
 *
 * <p>{@link Startup}：即使暂无注入方也在启动时实例化，配置错误立即让启动失败而不是推迟到首次签发。
 */
@Startup
@Singleton
public class ReceiptSigningKeys {
  static final String KEYS_PROPERTY = "aster.guard.receipt.signing-keys";
  static final String ACTIVE_KID_PROPERTY = "aster.guard.receipt.active-kid";
  /** 启动自检签名的固定字节。 */
  static final byte[] SELF_CHECK_BYTES = Ed25519KeyRing.SELF_CHECK_BYTES;
  static final int MAX_KID_LENGTH = Ed25519KeyRing.MAX_KID_LENGTH;

  private final Ed25519KeyRing ring;

  /** CDI 入口：从配置解析并在启动时校验（配置缺失由 MicroProfile Config 直接报错）。 */
  @Inject
  ReceiptSigningKeys(@ConfigProperty(name = KEYS_PROPERTY) String json,
                     @ConfigProperty(name = ACTIVE_KID_PROPERTY) String activeKid,
                     Clock clock) {
    this(Ed25519KeyRing.parse(json, activeKid, clock, KEYS_PROPERTY));
  }

  private ReceiptSigningKeys(Ed25519KeyRing ring) {
    this.ring = ring;
  }

  /**
   * 解析并校验密钥配置；{@code clock} 只用于判断 active key 的 {@code notAfter}。
   *
   * @throws IllegalStateException 配置无效（消息不含种子）
   */
  public static ReceiptSigningKeys parse(String json, String activeKid, Clock clock) {
    return new ReceiptSigningKeys(Ed25519KeyRing.parse(json, activeKid, clock, KEYS_PROPERTY));
  }

  /** 当前用于签发的 key。 */
  public SigningKey active() {
    return ring.active();
  }

  /** 按 kid 查找（含 revoked 与已过 notAfter 的 key）。 */
  public Optional<SigningKey> find(String kid) {
    return ring.find(kid);
  }

  /** 全部 key，按配置顺序，含 revoked。 */
  public List<SigningKey> all() {
    return ring.all();
  }

  /** 配置修订号，启动时须单调不减（ADR 0040 §12 D1）。 */
  public long revision() {
    return ring.revision();
  }

  /** kid 是否被紧急撤销；不在列表中的 kid（已退役）不算撤销。 */
  public boolean isRevoked(String kid) {
    return ring.isRevoked(kid);
  }

  /**
   * 启动自检：用私钥签 {@link #SELF_CHECK_BYTES}，再用（推导出的）公钥验签。
   *
   * @throws IllegalStateException 私钥与公钥不配对或签名失败
   */
  static void selfCheck(String kid, KeyPair keyPair) {
    Ed25519KeyRing.selfCheck(kid, keyPair, KEYS_PROPERTY);
  }
}
