package io.aster.guard.api;

import io.aster.guard.reconcile.GuardOrphanFeedService;
import io.aster.guard.reconcile.GuardOrphanFeedService.Summary;
import io.aster.guard.reconcile.GuardOrphanFeedService.TenantIndexPage;
import io.aster.security.internal.InternalV2SignatureVerifier;
import io.aster.security.internal.InternalV2SignatureVerifier.Policy;
import io.aster.security.internal.InternalV2SignatureVerifier.Request;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.StringReader;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * 孤儿通知源内部端点（ADR 0065 §3.4.2），仅供 aster-cloud 的通知 cron 调用。
 *
 * <p>只认 v2 内部签名（{@code aster.plan-gate.hmac-key}，nonce 命名空间 {@code guard-orphan-feed}，v1 一律拒绝）；
 * 不经 Bearer API key，也不走 InternalCallerFilter 的 v3 头。先验签、后解析 body。
 * 响应键集固定，不含 detail / executor 等任何明细字段。
 */
@Path("/api/internal/guard/orphan-feed")
public class GuardOrphanFeedResource {
  private static final String BASE = "/api/internal/guard/orphan-feed";
  private static final Policy POLICY = new Policy("guard-orphan-feed", false, "guard orphan feed");
  private static final String INVALID_QUERY = "{\"error\":\"invalid_query\"}";

  @Inject InternalV2SignatureVerifier verifier;
  @Inject GuardOrphanFeedService service;

  @POST
  @Path("/tenants")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Blocking
  public Response tenants(@HeaderParam("X-Aster-Timestamp") String timestamp,
      @HeaderParam("X-Aster-Signature") String signature, @HeaderParam("X-Aster-Nonce") String nonce, String body) {
    return handle("/tenants", timestamp, signature, nonce, body,
        json -> service.parseIndex(json, Instant.now()).map(q -> tenantsJson(service.tenants(q))));
  }

  @POST
  @Path("/summary")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Blocking
  public Response summary(@HeaderParam("X-Aster-Timestamp") String timestamp,
      @HeaderParam("X-Aster-Signature") String signature, @HeaderParam("X-Aster-Nonce") String nonce, String body) {
    return handle("/summary", timestamp, signature, nonce, body,
        json -> service.parseSummary(json, Instant.now()).map(q -> summaryJson(service.summary(q))));
  }

  /** 验签 → 解析 → 查询；拒绝、非法查询、成功各一个出口。 */
  private Response handle(String suffix, String timestamp, String signature, String nonce, String body,
      Function<JsonObject, Optional<JsonObject>> query) {
    Optional<Response> rejected = verifier.verify(new Request("POST", BASE + suffix, timestamp, signature, nonce, body), POLICY);
    if (rejected.isPresent()) {
      return rejected.get();
    }
    Optional<JsonObject> result = parseObject(body).flatMap(query);
    if (result.isEmpty()) {
      return Response.status(400).entity(INVALID_QUERY).build();
    }
    return Response.ok(result.get().toString()).build();
  }

  private static Optional<JsonObject> parseObject(String body) {
    if (body == null) {
      return Optional.empty();
    }
    try (JsonReader reader = Json.createReader(new StringReader(body))) {
      JsonStructure s = reader.read();
      return s instanceof JsonObject o ? Optional.of(o) : Optional.empty();
    } catch (RuntimeException e) {
      // 非法 JSON、顶层标量、嵌套过深（解析器抛出的各类运行时异常）一律视为非法查询

      return Optional.empty();
    }
  }

  private static JsonObject tenantsJson(TenantIndexPage page) {
    JsonArrayBuilder items = Json.createArrayBuilder();
    page.items().forEach(t -> items.add(Json.createObjectBuilder().add("tenantId", t.tenantId()).add("maxId", t.maxId())));
    JsonObjectBuilder out = Json.createObjectBuilder().add("items", items);
    if (page.next() != null) {
      out.add("next", page.next());
    }
    return out.build();
  }

  private static JsonObject summaryJson(Summary s) {
    JsonObjectBuilder byKind = Json.createObjectBuilder();
    s.byKind().forEach(byKind::add);
    JsonArrayBuilder runIds = Json.createArrayBuilder();
    s.runIds().forEach(runIds::add);
    JsonObjectBuilder out = Json.createObjectBuilder()
        .add("tenantId", s.tenantId()).add("maxId", s.maxId()).add("total", s.total()).add("byKind", byKind);
    if (s.firstDetectedAt() != null) {
      out.add("firstDetectedAt", s.firstDetectedAt().toString());
    }
    if (s.lastDetectedAt() != null) {
      out.add("lastDetectedAt", s.lastDetectedAt().toString());
    }
    return out.add("runIds", runIds).build();
  }
}
