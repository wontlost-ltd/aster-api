package io.aster.guard.api;

import io.aster.guard.GuardInvalidActionException;
import io.aster.guard.GuardRequestBodyReader;
import io.aster.guard.reconcile.GuardOrphanAdminService;
import io.aster.guard.reconcile.GuardOrphanAdminService.AckOutcome;
import io.aster.guard.reconcile.GuardOrphanAdminService.ListQuery;
import io.aster.guard.reconcile.GuardOrphanAdminService.Page;
import io.aster.guard.reconcile.GuardOrphanAdminService.Summary;
import io.aster.guard.reconcile.GuardOrphanActionEntity;
import io.aster.guard.reconcile.GuardReconciliationRunEntity;
import io.aster.guard.reconcile.OrphanKind;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 对账摘要、孤儿列表与管理员确认（ADR 0054 §10.2，#465）。类级 {@code MEMBER}，确认需 {@code ADMIN}；租户取自
 * {@link RequestIdentityResolver}，跨租户、不存在与非数字 id 一律 404 空体（ADR 0040 §3.4）。
 * Bearer 与 HMAC 内部调用（cloud 治理页）双通道。阻塞签名，不加 {@code @Transactional}。
 */
@Path("/api/v1/guard/reconciliation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardReconciliationResource {
  static final String INVALID_QUERY = "invalid_query";
  static final String INVALID_ACK = "invalid_ack";
  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;
  static final int MAX_COMMENT = 1000;
  /** 重复的 {@code severity} 参数的哨兵值：不是任何合法字面量，parse 必判非法。 */
  private static final String REPEATED = "\u0000repeated";
  private static final Set<String> STATUSES = Set.of(GuardOrphanActionEntity.OPEN, GuardOrphanActionEntity.RESOLVED,
      GuardOrphanActionEntity.ACKNOWLEDGED);

  /** 确认请求体。 */
  public record AckRequest(String comment) {}

  @Inject RequestIdentityResolver identityResolver;
  @Inject GuardOrphanAdminService admin;
  @Inject GuardRequestBodyReader bodyReader;

  /** 原样回显配置文本（如 {@code P7D}），不经 Duration 规范化。 */
  @ConfigProperty(name = "aster.guard.result-grace") String resultGrace;
  @ConfigProperty(name = "aster.guard.reconciliation.lookback") String lookback;

  @GET
  public Response summary() {
    Summary s = admin.summary(identityResolver.tenantId());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("lastRun", s.lastRun().map(GuardReconciliationResource::runView).orElse(null));
    body.put("open", s.open());
    body.put("openTotal", s.openTotal());
    body.put("resultGrace", resultGrace);
    body.put("lookback", lookback);
    return Response.ok(body).build();
  }

  @GET
  @Path("/orphans")
  public Response list(@QueryParam("status") String status, @QueryParam("kind") String kind,
                       @QueryParam("limit") String limit, @QueryParam("before") String before,
                       @Context UriInfo uri) {
    Optional<ListQuery> q = parse(status, kind, severityParam(uri), limit, before);
    if (q.isEmpty()) {
      return error(Response.Status.BAD_REQUEST, INVALID_QUERY, Map.of());
    }
    Page page = admin.list(identityResolver.tenantId(), q.get());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", page.items().stream().map(admin::view).toList());
    if (page.next() != null) {
      body.put("next", page.next());
    }
    return Response.ok(body).build();
  }

  @POST
  @Path("/orphans/{id}/ack")
  @RequireRole(Role.ADMIN)
  public Response acknowledge(@PathParam("id") String id, InputStream body) {
    Optional<Long> orphanId = positiveLong(id);
    if (orphanId.isEmpty()) {
      return notFound();
    }
    String comment;
    try {
      AckRequest req = bodyReader.read(body, AckRequest.class).value();
      comment = req == null ? null : req.comment();
    } catch (GuardInvalidActionException | IOException | WebApplicationException e) {
      return error(Response.Status.BAD_REQUEST, INVALID_ACK, Map.of());
    }
    if (comment == null || comment.isBlank() || comment.length() > MAX_COMMENT) {
      return error(Response.Status.BAD_REQUEST, INVALID_ACK, Map.of("field", "comment"));
    }
    AckOutcome outcome = admin.acknowledge(identityResolver.tenantId(), orphanId.get(), comment,
        identityResolver.performedBy());
    return switch (outcome) {
      case AckOutcome.Acknowledged a -> Response.ok(admin.view(a.orphan())).build();
      case AckOutcome.NotFound n -> notFound();
      case AckOutcome.NotOpen n -> error(Response.Status.CONFLICT, "not_open", Map.of("status", n.status()));
    };
  }

  /**
   * {@code severity} 原值：未给 → null；给了一次 → 原文（{@code ?severity=} 为空串，JAX-RS 的 {@code @QueryParam} 会把它读成
   * null，故从 {@link UriInfo} 取）；重复给多次 → {@link #REPEATED}（按非法处理，避免静默丢掉后几个）。
   */
  private static String severityParam(UriInfo uri) {
    List<String> values = uri.getQueryParameters().get("severity");
    if (values == null || values.isEmpty()) {
      return null;
    }
    if (values.size() > 1) {
      return REPEATED;
    }
    return values.get(0) == null ? "" : values.get(0);
  }

  /**
   * status 缺省 OPEN；kind 为 8 个字面量之一；severity 为逗号分隔的 {@code critical | warning | info}（不得为空项、
   * 不得重复，缺省不过滤，ADR 0061 §3.4）；limit 1..200（缺省 50）；before 为正整数。任一不合法 → empty。
   */
  static Optional<ListQuery> parse(String status, String kind, String severity, String limit, String before) {
    String s = status == null ? GuardOrphanActionEntity.OPEN : status;
    if (!STATUSES.contains(s)) {
      return Optional.empty();
    }
    OrphanKind k = null;
    if (kind != null) {
      Optional<OrphanKind> parsed = OrphanKind.fromWire(kind);
      if (parsed.isEmpty()) {
        return Optional.empty();
      }
      k = parsed.get();
    }
    Optional<Set<String>> severities = severities(severity);
    if (severities.isEmpty()) {
      return Optional.empty();
    }
    int n = DEFAULT_LIMIT;
    if (limit != null) {
      Optional<Long> parsed = positiveLong(limit);
      if (parsed.isEmpty() || parsed.get() > MAX_LIMIT) {
        return Optional.empty();
      }
      n = parsed.get().intValue();
    }
    Long b = null;
    if (before != null) {
      Optional<Long> parsed = positiveLong(before);
      if (parsed.isEmpty()) {
        return Optional.empty();
      }
      b = parsed.get();
    }
    return Optional.of(new ListQuery(s, k, severities.get(), n, b));
  }

  /** null → 空集（不过滤）；否则逐项校验，空项、重复项或未知字面量（含大小写不符）→ empty。 */
  private static Optional<Set<String>> severities(String raw) {
    if (raw == null) {
      return Optional.of(Set.of());
    }
    Set<String> out = new LinkedHashSet<>();
    for (String v : raw.split(",", -1)) {
      if (!OrphanKind.SEVERITIES.contains(v) || !out.add(v)) {
        return Optional.empty();
      }
    }
    return Optional.of(out);
  }

  private static Optional<Long> positiveLong(String v) {
    if (v == null || v.isEmpty() || v.length() > 18 || !v.chars().allMatch(Character::isDigit)) {
      return Optional.empty();
    }
    long n = Long.parseLong(v);
    return n > 0 ? Optional.of(n) : Optional.empty();
  }

  private static Map<String, Object> runView(GuardReconciliationRunEntity r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("runId", r.id);
    m.put("finishedAt", r.finishedAt.toString());
    m.put("windowStart", r.windowStart.toString());
    m.put("windowEnd", r.windowEnd.toString());
    m.put("newOrphans", r.newOrphans);
    m.put("resolvedOrphans", r.resolvedOrphans);
    m.put("openOrphans", r.openOrphans);
    return m;
  }

  private static Response notFound() {
    return Response.status(Response.Status.NOT_FOUND).build();
  }

  private static Response error(Response.Status status, String code, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    extra.forEach(body::putIfAbsent);
    return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
  }
}
