package io.aster.common.signing;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ed25519 公钥集合的 JWKS 渲染（RFC 8037 OKP），Guard 收据公钥与策略包导出公钥共用同一形状：
 * {@code {"keys":[{alg,crv,kid,kty,use,x[,exp]}],"revision":N}}，键按字母序。
 *
 * <p>只发布 {@code now < notAfter}（无 notAfter 视为不过期）且未 revoked 的 key，包含非 active 的旧 key；
 * {@code exp} 仅在有 notAfter 时写，单位 epoch 秒。
 */
public final class Jwks {
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private Jwks() {
  }

  /** 渲染 JWKS 文档；与 HTTP 无关，便于以固定时刻测试时间窗语义。 */
  public static Map<String, Object> render(List<Ed25519KeyRing.SigningKey> keys, long revision, Instant now) {
    List<Map<String, Object>> jwks = keys.stream()
        .filter(k -> !k.revoked() && !k.expiredAt(now))
        .map(Jwks::jwk)
        .toList();
    Map<String, Object> body = new TreeMap<>();
    body.put("keys", jwks);
    body.put("revision", revision);
    return body;
  }

  private static Map<String, Object> jwk(Ed25519KeyRing.SigningKey key) {
    Map<String, Object> jwk = new TreeMap<>();
    jwk.put("alg", "EdDSA");
    jwk.put("crv", "Ed25519");
    jwk.put("kid", key.kid());
    jwk.put("kty", "OKP");
    jwk.put("use", "sig");
    jwk.put("x", B64.encodeToString(key.rawPublicKey()));
    if (key.notAfter() != null) {
      jwk.put("exp", key.notAfter().getEpochSecond());
    }
    return jwk;
  }
}
