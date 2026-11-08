package io.aster.guard.receipt;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 执行收据签名密钥配置（ADR 0040 §12 D1）：解析 {@code aster.guard.receipt.signing-keys} 并选出
 * {@code aster.guard.receipt.active-kid}。任何配置错误都在启动时抛 {@link IllegalStateException}，
 * 消息说明原因但<strong>绝不包含种子</strong>（也不挂带 Jackson / Base64 的原始异常，因其消息可能回显输入片段）。
 *
 * <p>配置形状：{@code { "revision": <非负整数>, "keys": [{ "kid", "privateKey": "<base64url 32 字节种子>",
 * "notAfter"?: "<ISO-8601>", "revoked"?: true }] }}。未知字段与重复键一律拒绝，避免拼错的
 * {@code "revoked"} 被静默忽略。
 *
 * <p>启动失败条件：缺配置 / keys 为空、kid 重复或超过 64 字符、种子非 32 字节、{@code active-kid} 不在列表、
 * {@code active-kid} 已 revoked、{@code now >= active.notAfter}（notAfter 只约束签发与发布，
 * 非 active 的过期 key 仍保留在列表中，由发布侧过滤）。
 *
 * <p><b>私钥</b>：{@code KeyFactory("Ed25519").generatePrivate(new EdECPrivateKeySpec(ED25519, seed))}——
 * JDK 15+ 公开 API，直接以 RFC 8032 的 32 字节种子为私钥，无需手拼 PKCS#8 前缀或伪造 {@code SecureRandom}
 * （后者依赖 provider 内部读随机数的次数，不是契约）。
 *
 * <p><b>公钥</b>：JDK 没有「由私钥推导公钥」的公开 API，因此按 RFC 8032 §5.1.5 在启动时每 key 推导一次：
 * {@code SHA-512(seed)} 前 32 字节按第 2 步裁剪得标量 s，以 {@link BigInteger} 在扩展坐标上做加倍-加法求
 * {@code [s]B}，按 §5.1.2 编码为 32 字节（JWKS 的 {@code x} 即此编码），再经
 * {@code KeyFactory.generatePublic(new EdECPublicKeySpec(...))} 得到 {@link PublicKey}。
 * 此实现非常数时间，但只在启动时对配置中的种子运行一次，不处理任何请求输入，性能与侧信道均无关。
 * 正确性由 RFC 8032 §7.1 向量 1–3（{@code ReceiptSigningKeysTest}）与启动自检（每把 key 签一段固定字节
 * 并用推导公钥验签，失败即启动失败）双重锁定。
 *
 * <p>{@link Startup}：即使暂无注入方也在启动时实例化，配置错误立即让启动失败而不是推迟到首次签发。
 */
@Startup
@Singleton
public class ReceiptSigningKeys {
  static final String KEYS_PROPERTY = "aster.guard.receipt.signing-keys";
  static final String ACTIVE_KID_PROPERTY = "aster.guard.receipt.active-kid";
  /** 启动自检签名的固定字节。 */
  static final byte[] SELF_CHECK_BYTES = "aster-guard-receipt-self-check/v1".getBytes(StandardCharsets.UTF_8);

  private static final ObjectMapper STRICT_JSON = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final Set<String> TOP_FIELDS = Set.of("revision", "keys");
  private static final Set<String> KEY_FIELDS = Set.of("kid", "privateKey", "notAfter", "revoked");
  /** 与 {@code guard_receipts.kid VARCHAR(64)} 一致：超长 kid 在启动时拒绝，而不是等到每次 ALLOW 落库失败。 */
  static final int MAX_KID_LENGTH = 64;

  /**
   * 一把签名密钥。{@code notAfter} 为 null 表示不过期。
   */
  public record SigningKey(String kid, KeyPair keyPair, Instant notAfter, boolean revoked) {

    /** RFC 8032 §5.1.2 编码的 32 字节公钥（JWKS {@code x} 的原始字节）。 */
    public byte[] rawPublicKey() {
      return Ed25519.encode(((EdECPublicKey) keyPair.getPublic()).getPoint());
    }

    /** {@code now >= notAfter} 时不可再用于签发或发布。 */
    public boolean expiredAt(Instant now) {
      return notAfter != null && !now.isBefore(notAfter);
    }

    @Override
    public String toString() {
      return "SigningKey[kid=" + kid + ", notAfter=" + notAfter + ", revoked=" + revoked + "]";
    }
  }

  private final long revision;
  private final List<SigningKey> keys;
  private final SigningKey active;

  /** CDI 入口：从配置解析并在启动时校验（配置缺失由 MicroProfile Config 直接报错）。 */
  @Inject
  ReceiptSigningKeys(@ConfigProperty(name = KEYS_PROPERTY) String json,
                     @ConfigProperty(name = ACTIVE_KID_PROPERTY) String activeKid,
                     Clock clock) {
    this(parse(json, activeKid, clock));
  }

  private ReceiptSigningKeys(ReceiptSigningKeys parsed) {
    this(parsed.revision, parsed.keys, parsed.active);
  }

  private ReceiptSigningKeys(long revision, List<SigningKey> keys, SigningKey active) {
    this.revision = revision;
    this.keys = List.copyOf(keys);
    this.active = active;
  }

  /**
   * 解析并校验密钥配置；{@code clock} 只用于判断 active key 的 {@code notAfter}。
   *
   * @throws IllegalStateException 配置无效（消息不含种子）
   */
  public static ReceiptSigningKeys parse(String json, String activeKid, Clock clock) {
    Instant now = clock.instant();
    JsonNode root = readTree(json);
    requireOnlyFields(root, TOP_FIELDS, "顶层");
    long revision = readRevision(root.get("revision"));
    JsonNode keysNode = root.get("keys");
    if (keysNode == null || !keysNode.isArray() || keysNode.isEmpty()) {
      throw invalid("keys 必须是非空数组");
    }
    List<SigningKey> keys = new ArrayList<>();
    Set<String> kids = new LinkedHashSet<>();
    for (JsonNode node : keysNode) {
      SigningKey key = readKey(node, keys.size());
      if (!kids.add(key.kid())) {
        throw invalid("kid 重复: " + key.kid());
      }
      keys.add(key);
    }
    SigningKey active = selectActive(keys, activeKid, now);
    return new ReceiptSigningKeys(revision, keys, active);
  }

  /** 当前用于签发的 key。 */
  public SigningKey active() {
    return active;
  }

  /** 按 kid 查找（含 revoked 与已过 notAfter 的 key）。 */
  public Optional<SigningKey> find(String kid) {
    return keys.stream().filter(k -> k.kid().equals(kid)).findFirst();
  }

  /** 全部 key，按配置顺序，含 revoked。 */
  public List<SigningKey> all() {
    return keys;
  }

  /** 配置修订号，启动时须单调不减（ADR 0040 §12 D1）。 */
  public long revision() {
    return revision;
  }

  /** kid 是否被紧急撤销；不在列表中的 kid（已退役）不算撤销。 */
  public boolean isRevoked(String kid) {
    return find(kid).map(SigningKey::revoked).orElse(false);
  }

  /**
   * 启动自检：用私钥签 {@link #SELF_CHECK_BYTES}，再用（推导出的）公钥验签。
   *
   * @throws IllegalStateException 私钥与公钥不配对或签名失败
   */
  static void selfCheck(String kid, KeyPair keyPair) {
    boolean ok;
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keyPair.getPrivate());
      signer.update(SELF_CHECK_BYTES);
      byte[] sig = signer.sign();
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(keyPair.getPublic());
      verifier.update(SELF_CHECK_BYTES);
      ok = verifier.verify(sig);
    } catch (GeneralSecurityException e) {
      ok = false;
    }
    if (!ok) {
      throw invalid("kid " + kid + " 启动自检失败：推导公钥无法验证私钥签名");
    }
  }

  private static JsonNode readTree(String json) {
    if (json == null || json.isBlank()) {
      throw invalid("未配置（ASTER_GUARD_RECEIPT_SIGNING_KEYS）");
    }
    try {
      JsonNode root = STRICT_JSON.readTree(json);
      if (root == null || !root.isObject()) {
        throw invalid("必须是 JSON 对象");
      }
      return root;
    } catch (JsonProcessingException e) {
      // 不挂 cause、不拼 e.getMessage()：Jackson 消息会回显输入片段，可能含种子
      JsonLocation loc = e.getLocation();
      String where = loc == null ? "" : "（第 " + loc.getLineNr() + " 行第 " + loc.getColumnNr() + " 列）";
      throw invalid("不是合法 JSON 或含重复键" + where);
    }
  }

  private static long readRevision(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
      throw invalid("revision 必须是非负整数");
    }
    return node.longValue();
  }

  private static SigningKey readKey(JsonNode node, int index) {
    String where = "keys[" + index + "]";
    if (!node.isObject()) {
      throw invalid(where + " 必须是对象");
    }
    requireOnlyFields(node, KEY_FIELDS, where);
    JsonNode kidNode = node.get("kid");
    if (kidNode == null || !kidNode.isTextual() || kidNode.textValue().isBlank()) {
      throw invalid(where + ".kid 必须是非空字符串");
    }
    String kid = kidNode.textValue();
    if (kid.length() > MAX_KID_LENGTH) {
      throw invalid(where + ".kid 超过 " + MAX_KID_LENGTH + " 字符（guard_receipts.kid 列宽）");
    }
    where = "kid " + kid;
    byte[] seed = readSeed(node.get("privateKey"), where);
    Instant notAfter = readNotAfter(node.get("notAfter"), where);
    boolean revoked = readRevoked(node.get("revoked"), where);
    KeyPair keyPair = keyPairFromSeed(seed);
    selfCheck(kid, keyPair);
    return new SigningKey(kid, keyPair, notAfter, revoked);
  }

  private static byte[] readSeed(JsonNode node, String where) {
    String message = where + " 的 privateKey 必须是 base64url 编码的 32 字节种子";
    if (node == null || !node.isTextual()) {
      throw invalid(message);
    }
    byte[] seed;
    try {
      seed = Base64.getUrlDecoder().decode(node.textValue());
    } catch (IllegalArgumentException e) {
      throw invalid(message);
    }
    if (seed.length != 32) {
      throw invalid(message + "（实际 " + seed.length + " 字节）");
    }
    return seed;
  }

  private static Instant readNotAfter(JsonNode node, String where) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw invalid(where + " 的 notAfter 必须是 ISO-8601 时间字符串");
    }
    try {
      return Instant.parse(node.textValue());
    } catch (DateTimeParseException e) {
      throw invalid(where + " 的 notAfter 必须是 ISO-8601 时间字符串: " + node.textValue());
    }
  }

  private static boolean readRevoked(JsonNode node, String where) {
    if (node == null || node.isNull()) {
      return false;
    }
    if (!node.isBoolean()) {
      throw invalid(where + " 的 revoked 必须是布尔值");
    }
    return node.booleanValue();
  }

  private static void requireOnlyFields(JsonNode node, Set<String> allowed, String where) {
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      String name = it.next();
      if (!allowed.contains(name)) {
        throw invalid(where + " 含未知字段: " + name);
      }
    }
  }

  private static SigningKey selectActive(List<SigningKey> keys, String activeKid, Instant now) {
    if (activeKid == null || activeKid.isBlank()) {
      throw new IllegalStateException(ACTIVE_KID_PROPERTY + " 未配置（ASTER_GUARD_RECEIPT_ACTIVE_KID）");
    }
    SigningKey active = keys.stream().filter(k -> k.kid().equals(activeKid)).findFirst()
        .orElseThrow(() -> new IllegalStateException(
            ACTIVE_KID_PROPERTY + "=" + activeKid + " 不在 " + KEYS_PROPERTY + " 的 keys 中"));
    if (active.revoked()) {
      throw new IllegalStateException(ACTIVE_KID_PROPERTY + "=" + activeKid + " 已 revoked，不能用于签发");
    }
    if (active.expiredAt(now)) {
      throw new IllegalStateException(
          ACTIVE_KID_PROPERTY + "=" + activeKid + " 已过 notAfter（" + active.notAfter() + "），不能用于签发");
    }
    return active;
  }

  private static IllegalStateException invalid(String reason) {
    return new IllegalStateException(KEYS_PROPERTY + " 无效: " + reason);
  }

  /** 种子 → Ed25519 密钥对（私钥用 JDK 公开 API；公钥按 RFC 8032 §5.1.5 推导）。 */
  static KeyPair keyPairFromSeed(byte[] seed) {
    try {
      KeyFactory factory = KeyFactory.getInstance("Ed25519");
      PrivateKey privateKey = factory.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
      PublicKey publicKey = factory.generatePublic(
          new EdECPublicKeySpec(NamedParameterSpec.ED25519, Ed25519.publicPoint(seed)));
      return new KeyPair(publicKey, privateKey);
    } catch (GeneralSecurityException e) {
      // JDK 15+ 必带 Ed25519；走到这里是运行环境缺陷，异常本身不含种子
      throw new IllegalStateException("Ed25519 不可用", e);
    }
  }

  /** RFC 8032 Ed25519 的最小算术：只用于由种子推导公钥点及其 32 字节编码。 */
  static final class Ed25519 {
    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    /** 基点 B（§5.1）：y = 4/5，x 取偶数根。扩展坐标 (X, Y, Z, T)。 */
    private static final BigInteger[] BASE = basePoint();

    private Ed25519() {
    }

    /** §5.1.5：seed → [s]B。 */
    static EdECPoint publicPoint(byte[] seed) {
      byte[] h;
      try {
        h = MessageDigest.getInstance("SHA-512").digest(seed);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("SHA-512 不可用", e);
      }
      byte[] a = new byte[32];
      System.arraycopy(h, 0, a, 0, 32);
      a[0] &= (byte) 248;
      a[31] &= 127;
      a[31] |= 64;
      BigInteger[] point = multiply(littleEndian(a), BASE);
      BigInteger zInv = point[2].modInverse(P);
      BigInteger x = point[0].multiply(zInv).mod(P);
      BigInteger y = point[1].multiply(zInv).mod(P);
      return new EdECPoint(x.testBit(0), y);
    }

    /** §5.1.2：y 的 32 字节小端编码，最高位放 x 的奇偶。 */
    static byte[] encode(EdECPoint point) {
      byte[] out = new byte[32];
      byte[] big = point.getY().toByteArray();
      for (int i = 0; i < big.length && i < 32; i++) {
        out[i] = big[big.length - 1 - i];
      }
      if (point.isXOdd()) {
        out[31] |= (byte) 0x80;
      }
      return out;
    }

    private static BigInteger littleEndian(byte[] bytes) {
      byte[] big = new byte[bytes.length];
      for (int i = 0; i < bytes.length; i++) {
        big[i] = bytes[bytes.length - 1 - i];
      }
      return new BigInteger(1, big);
    }

    private static BigInteger[] basePoint() {
      BigInteger y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
      BigInteger x = recoverX(y);
      return new BigInteger[] {x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    /** §5.1.3 第 2–4 步：由 y 求偶数 x。 */
    private static BigInteger recoverX(BigInteger y) {
      BigInteger y2 = y.multiply(y).mod(P);
      BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
      BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
      BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
      BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
      if (!x.multiply(x).mod(P).equals(x2)) {
        x = x.multiply(SQRT_M1).mod(P);
      }
      return x.testBit(0) ? P.subtract(x) : x;
    }

    /** 加倍-加法求 [k]Q（统一加法公式对 Ed25519 完备，加倍亦用 {@link #add}）。 */
    private static BigInteger[] multiply(BigInteger k, BigInteger[] q) {
      BigInteger[] result = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
      for (int i = k.bitLength() - 1; i >= 0; i--) {
        result = add(result, result);
        if (k.testBit(i)) {
          result = add(result, q);
        }
      }
      return result;
    }

    /** §5.1.4 扩展坐标点加法。 */
    private static BigInteger[] add(BigInteger[] p1, BigInteger[] p2) {
      BigInteger a = p1[1].subtract(p1[0]).multiply(p2[1].subtract(p2[0])).mod(P);
      BigInteger b = p1[1].add(p1[0]).multiply(p2[1].add(p2[0])).mod(P);
      BigInteger c = p1[3].multiply(BigInteger.TWO).multiply(D).multiply(p2[3]).mod(P);
      BigInteger d = p1[2].multiply(BigInteger.TWO).multiply(p2[2]).mod(P);
      BigInteger e = b.subtract(a);
      BigInteger f = d.subtract(c);
      BigInteger g = d.add(c);
      BigInteger h = b.add(a);
      return new BigInteger[] {
          e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }
  }
}
