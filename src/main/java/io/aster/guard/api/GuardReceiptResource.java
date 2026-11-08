package io.aster.guard.api;

import io.aster.billing.ApiQuotaGuard;
import io.aster.guard.GuardInvalidActionException;
import io.aster.guard.GuardRequestBodyReader;
import io.aster.guard.receipt.ExecutionReceiptUnavailableException;
import io.aster.guard.receipt.GuardReceiptService;
import io.aster.guard.receipt.GuardReceiptStore.ConsumeVerdict;
import io.aster.guard.receipt.InvalidConsumeException;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 执行收据端点（ADR 0040 §12 D5）：执行方在落副作用前 consume 收据，同一决策至多一次成功。
 *
 * <p>Bearer API key（SDK / 代理）与 HMAC 内部调用者（cloud 执行路径）双通道，租户取自
 * {@link RequestIdentityResolver}；跨租户、非法 UUID、决策不存在一律 404 空体（§3.4）。
 * 方法返回 {@link Response}（阻塞签名），由 RESTEasy Reactive 调度到 worker 线程；不加 {@code @Transactional}，
 * 服务内部各步自行开启独立事务。
 */
@Path("/api/v1/guard/receipts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardReceiptResource {
  /**
   * 计量标签：与 {@code /decisions} 同一口径（ADR 0040 §12 D5「与 /decisions 同一口径计量一次」、§3.1 第 3 条），
   * 计入同一端点标签。
   */
  static final String CONSUME_METER_PATH = GuardDecisionResource.PATH;
  private static final String INVALID_CONSUME = "invalid_consume";

  @Inject
  RequestIdentityResolver identityResolver;

  @Inject
  GuardReceiptService service;

  @Inject
  ApiQuotaGuard apiQuotaGuard;

  @Inject
  GuardRequestBodyReader bodyReader;

  /**
   * 原子一次性消耗。200 时按 {@link #CONSUME_METER_PATH}（即 {@code /api/v1/guard/decisions}）以同一 {@code recordAsync} 口径计量一次，
   * 不做配额与 RPS 判定：它不含评估，每个决策至多一次成功；HMAC 内部会话调用不计量。
   */
  @POST
  @Path("/{decisionId}/consume")
  public Response consume(@PathParam("decisionId") String id, InputStream body) {
    long startTime = System.currentTimeMillis();
    Optional<UUID> decisionId = parseUuid(id);
    if (decisionId.isEmpty()) {
      return notFound();
    }
    String tenantId = identityResolver.tenantId();
    String performedBy = identityResolver.performedBy();
    try {
      Optional<GuardReceiptService.Result> result = service.consume(tenantId, performedBy, decisionId.get(),
          readBody(body));
      if (result.isEmpty()) {
        return notFound();
      }
      return switch (result.get().verdict()) {
        case ConsumeVerdict.Consumed c -> {
          meter(tenantId, performedBy, startTime);
          yield Response.ok(new ConsumeResponse(c.consumedAt().toString(), decisionId.get().toString(),
              result.get().executionReceipt())).build();
        }
        case ConsumeVerdict.Rejected r -> error(Response.Status.CONFLICT, r.code(), r.extra());
      };
    } catch (InvalidConsumeException e) {
      return error(Response.Status.BAD_REQUEST, INVALID_CONSUME,
          e.field() == null ? Map.of("message", e.getMessage()) : Map.of("field", e.field()));
    } catch (ExecutionReceiptUnavailableException e) {
      return error(Response.Status.SERVICE_UNAVAILABLE, "receipt_unavailable", Map.of());
    }
  }

  /**
   * 以 {@link GuardRequestBodyReader} 读取（重复键检测）；重复键、非法 JSON、类型不符一律 {@code invalid_consume}——
   * 新端点没有需要保持逐字节一致的旧响应。
   */
  private ConsumeRequest readBody(InputStream body) {
    try {
      return bodyReader.read(body, ConsumeRequest.class).value();
    } catch (GuardInvalidActionException | IOException | WebApplicationException e) {
      throw new InvalidConsumeException(null, "请求体不是合法的 consume JSON 或含重复键");
    }
  }

  private void meter(String tenantId, String performedBy, long startTime) {
    if (identityResolver.internalSessionCall()) {
      return;
    }
    apiQuotaGuard.recordAsync(identityResolver.quotaOwnerId(), performedBy, tenantId, identityResolver.apiKeyId(),
        CONSUME_METER_PATH, "success", System.currentTimeMillis() - startTime);
  }

  private static Optional<UUID> parseUuid(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Response notFound() {
    return Response.status(Response.Status.NOT_FOUND).build();
  }

  /** 错误体：{@code error} 在前，附加字段按给定顺序随后。 */
  private static Response error(Response.Status status, String code, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    extra.forEach(body::putIfAbsent);
    return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
  }
}
