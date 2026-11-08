package io.aster.guard.api;

import io.aster.guard.receipt.ReceiptSigningKeys;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 执行收据验签公钥发布（ADR 0040 §12 D6）：JWKS（RFC 8037 OKP / Ed25519）加配置 {@code revision}。
 *
 * <p>发布 {@code now < notAfter}（无 notAfter 视为不过期）且未 revoked 的全部 key，包含非 active 的旧 key，
 * 使轮换期内已签发的收据仍可验签；退役 key 从配置删除后即不再出现。{@code exp} 仅在有 notAfter 时写，单位 epoch 秒。
 * 响应键按字母序（{@link TreeMap}），客户端可据 {@code revision} 判断缓存是否过期；
 * {@code Cache-Control: max-age=300} 即离线验签对撤销的最大滞后。
 */
@Path("/api/v1/guard/keys")
@Produces(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardKeysResource {
  static final int MAX_AGE_SECONDS = 300;
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  @Inject
  ReceiptSigningKeys keys;

  @Inject
  Clock clock;

  @GET
  public Response list() {
    CacheControl cache = new CacheControl();
    cache.setMaxAge(MAX_AGE_SECONDS);
    return Response.ok(render(keys, clock.instant())).cacheControl(cache).build();
  }

  /** 渲染 JWKS 文档；与 HTTP 无关，便于以固定时刻测试时间窗语义。 */
  static Map<String, Object> render(ReceiptSigningKeys keys, Instant now) {
    List<Map<String, Object>> jwks = keys.all().stream()
        .filter(k -> !k.revoked() && !k.expiredAt(now))
        .map(GuardKeysResource::jwk)
        .toList();
    Map<String, Object> body = new TreeMap<>();
    body.put("keys", jwks);
    body.put("revision", keys.revision());
    return body;
  }

  private static Map<String, Object> jwk(ReceiptSigningKeys.SigningKey key) {
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
