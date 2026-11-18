package io.aster.policy.rest;

import io.aster.common.signing.Jwks;
import io.aster.policy.export.ExportSigningKeys;
import io.aster.policy.security.rbac.AnonymousAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Clock;
import java.util.List;

/**
 * 策略包导出验签公钥（ADR 0059 修订 3 P2-R2）：{@code GET /api/v1/exports/keys}，形状与 {@code /api/v1/guard/keys}
 * 相同（见 {@link Jwks}），{@code Cache-Control: max-age=300}。
 *
 * <p>匿名可读：公钥是公开信息，CLI 的 {@code --jwks <url>} 不带任何凭据（与 Guard 收据公钥的 MEMBER 不同）。
 * 因此本路径在 TenantFilter 与 RequestSignatureFilter 中按精确路径豁免，限流走 RateLimitFilter 的默认（按 IP）桶。
 * 未配置导出密钥时返回 {@code {"keys":[],"revision":0}}；不受包导出开关影响。
 */
@Path(ExportKeysResource.PATH)
@Produces(MediaType.APPLICATION_JSON)
public class ExportKeysResource {
  public static final String PATH = "/api/v1/exports/keys";
  static final int MAX_AGE_SECONDS = 300;

  @Inject
  ExportSigningKeys keys;

  @Inject
  Clock clock;

  /** 精确路径匹配（RESTEasy 给的路径可能不带前导 /）；供各 filter 判豁免。 */
  public static boolean isPath(String path) {
    return PATH.equals(path) || PATH.substring(1).equals(path);
  }

  @GET
  @AnonymousAllowed
  public Response list() {
    CacheControl cache = new CacheControl();
    cache.setMaxAge(MAX_AGE_SECONDS);
    Object body = keys.ring()
        .map(ring -> Jwks.render(ring.all(), ring.revision(), clock.instant()))
        .orElseGet(() -> Jwks.render(List.of(), 0, clock.instant()));
    return Response.ok(body).cacheControl(cache).build();
  }
}
