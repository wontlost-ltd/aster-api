package io.aster.guard.api;

import io.aster.guard.GuardInvalidActionException;
import io.aster.guard.GuardRequestBodyReader;
import io.aster.guard.provenance.FactIssuerStore;
import io.aster.guard.provenance.FactIssuerStore.UpsertResult;
import io.aster.guard.provenance.GuardFactIssuerEntity;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.aster.security.apikey.InternalCallerFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 签名事实签发方管理（ADR 0040 §11 D3）：cloud 团队设置页经内部 HMAC 通道维护本租户的签发方公钥。
 *
 * <p>只接受 HMAC：路径已由 {@code InternalCallerFilter} 恒要求验签（Bearer 在过滤器处 403 {@code internal_only}），
 * 这里再按验签章兜底，同 {@code from-evidence}。租户取自签名内的 {@code X-Tenant-Id}，跨租户不可见、不可改。
 * 写入即生效：验签按主键直查，不经缓存。{@code DELETE} 为软删（{@code enabled=false}，行保留供审计）。
 * 响应键按字母序，{@code notAfter} 为空时不写。
 */
@Path("/api/v1/guard/fact-issuers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardFactIssuerResource {
  private static final int PUBLIC_KEY_BYTES = 32;
  private static final int MAX_ISSUER_LENGTH = 255;
  private static final int MAX_KID_LENGTH = 64;
  private static final String INVALID_ISSUER = "invalid_issuer";
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  @Inject
  RequestIdentityResolver identityResolver;

  @Inject
  FactIssuerStore store;

  @Inject
  GuardRequestBodyReader bodyReader;

  @Inject
  Clock clock;

  @Context
  ContainerRequestContext requestContext;

  @GET
  public Response list() {
    if (!InternalCallerFilter.isHmacVerified(requestContext)) {
      return internalOnly();
    }
    Optional<String> tenant = signedTenant();
    if (tenant.isEmpty()) {
      return tenantRequired();
    }
    List<Map<String, Object>> issuers = store.list(tenant.get()).stream()
        .map(GuardFactIssuerResource::view)
        .toList();
    return Response.ok(Map.of("issuers", issuers)).build();
  }

  /**
   * 登记签发方；同键同公钥为幂等更新（enabled / notAfter，保留首次的创建时间与创建人）。
   * 同键异公钥 409 {@code issuer_key_conflict}；已吊销的再启用 409 {@code issuer_revoked}。
   */
  @PUT
  @Path("/{issuer}/{kid}")
  public Response put(@PathParam("issuer") String issuer, @PathParam("kid") String kid, InputStream body) {
    if (!InternalCallerFilter.isHmacVerified(requestContext)) {
      return internalOnly();
    }
    Optional<String> tenant = signedTenant();
    if (tenant.isEmpty()) {
      return tenantRequired();
    }
    FactIssuerRequest req;
    try {
      req = bodyReader.read(body, FactIssuerRequest.class).value();
    } catch (GuardInvalidActionException duplicate) {
      return error(Response.Status.BAD_REQUEST, "invalid_action", duplicate.getMessage());
    } catch (IOException | WebApplicationException malformed) {
      return error(Response.Status.BAD_REQUEST, INVALID_ISSUER, "请求体不是合法的签发方 JSON");
    }
    try {
      validateName("issuer", issuer, MAX_ISSUER_LENGTH);
      validateName("kid", kid, MAX_KID_LENGTH);
      if (req == null) {
        throw new InvalidIssuerException("缺少请求体");
      }
      if (req.alg() != null && !GuardFactIssuerEntity.ALG_ED25519.equals(req.alg())) {
        throw new InvalidIssuerException("alg 只支持 Ed25519");
      }
      String publicKey = canonicalPublicKey(req.publicKey());
      Instant notAfter = parseNotAfter(req.notAfter());
      boolean enabled = req.enabled() == null || req.enabled();
      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      return switch (store.upsert(tenant.get(), issuer, kid, publicKey, enabled, notAfter,
          identityResolver.performedBy(), now)) {
        case UpsertResult.Saved saved -> Response.ok(view(saved.row())).build();
        case UpsertResult.Conflict conflict -> error(Response.Status.CONFLICT, conflict.code(), conflictMessage(conflict));
      };
    } catch (InvalidIssuerException bad) {
      return error(Response.Status.BAD_REQUEST, INVALID_ISSUER, bad.getMessage());
    }
  }

  /** 软删：置 {@code enabled=false}；已禁用的重复删除同样 204，本租户不存在 404 空体。 */
  @DELETE
  @Path("/{issuer}/{kid}")
  public Response delete(@PathParam("issuer") String issuer, @PathParam("kid") String kid) {
    if (!InternalCallerFilter.isHmacVerified(requestContext)) {
      return internalOnly();
    }
    Optional<String> tenant = signedTenant();
    if (tenant.isEmpty()) {
      return tenantRequired();
    }
    return store.disable(tenant.get(), issuer, kid)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
  }

  /**
   * issuer / kid 非空、不超列宽、无控制字符且不含 {@code #}：{@code issuer#kid} 是决策行与收据里的签发方引用形式，
   * 允许 {@code #} 会让该引用无法唯一拆分。
   */
  private static void validateName(String field, String value, int maxLength) {
    if (value == null || value.isBlank() || value.length() > maxLength || value.indexOf('#') >= 0
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidIssuerException(field + " 须为 1–" + maxLength + " 个字符，不含 # 与控制字符");
    }
  }

  /** 解码 base64url（容忍填充），要求恰好 32 字节，返回无填充的规范编码。 */
  private static String canonicalPublicKey(String encoded) {
    if (encoded == null) {
      throw new InvalidIssuerException("缺少 publicKey");
    }
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      throw new InvalidIssuerException("publicKey 不是合法的 base64url");
    }
    if (raw.length != PUBLIC_KEY_BYTES) {
      throw new InvalidIssuerException("publicKey 须为 32 字节 Ed25519 原始公钥");
    }
    return B64.encodeToString(raw);
  }

  /** 与 TIMESTAMPTZ 精度对齐到微秒，使 PUT 响应与随后的 GET 一致。 */
  private static Instant parseNotAfter(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeParseException e) {
      throw new InvalidIssuerException("notAfter 须为 ISO-8601 时刻");
    }
  }

  private static Map<String, Object> view(GuardFactIssuerEntity row) {
    Map<String, Object> v = new TreeMap<>();
    v.put("alg", row.alg);
    v.put("createdAt", row.createdAt.toString());
    v.put("createdBy", row.createdBy);
    v.put("enabled", row.enabled);
    v.put("issuer", row.issuer);
    v.put("kid", row.kid);
    if (row.notAfter != null) {
      v.put("notAfter", row.notAfter.toString());
    }
    v.put("publicKey", row.publicKey);
    return v;
  }

  /**
   * 签名内的租户：签发方是租户级事实，HMAC 调用缺 {@code X-Tenant-Id}（按空串签名）时不得回退到默认租户，
   * 否则会把签发方登记到 {@code default} 租户名下。现有 {@code TenantFilter} 已先以 400 拒绝缺租户头的请求，
   * 这里是不依赖过滤器配置的兜底；同 from-evidence 对缺签名用户的处理（400）。
   */
  private Optional<String> signedTenant() {
    String header = requestContext.getHeaderString("X-Tenant-Id");
    return header == null || header.isBlank() ? Optional.empty() : Optional.of(identityResolver.tenantId());
  }

  private static Response tenantRequired() {
    return error(Response.Status.BAD_REQUEST, INVALID_ISSUER, "签发方管理需要签名内的租户（X-Tenant-Id）");
  }

  private static String conflictMessage(UpsertResult.Conflict conflict) {
    return FactIssuerStore.REVOKED.equals(conflict.code())
        ? "签发方已吊销，不可重新启用；请轮换到新的 kid"
        : "该 kid 已登记不同的公钥；换钥请使用新的 kid";
  }

  private static Response internalOnly() {
    return error(Response.Status.FORBIDDEN, "internal_only", "仅限内部 HMAC 调用者");
  }

  private static Response error(Response.Status status, String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
  }

  /** 请求字段不合法；仅在本资源内部流转，映射为 400 {@code invalid_issuer}。 */
  private static final class InvalidIssuerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidIssuerException(String message) {
      super(message);
    }
  }
}
