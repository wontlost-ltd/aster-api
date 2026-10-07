package io.aster.guard.api;

import io.aster.billing.ApiQuotaGuard;
import io.aster.guard.GuardDecisionService;
import io.aster.guard.GuardInvalidActionException;
import io.aster.guard.GuardPolicyNotFoundException;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Action Guard 决策端点（ADR 0040 §3）：Agent 在执行动作前提交提议，拿回 ALLOW / DENY / PENDING 与收据。
 *
 * <p>方法返回 {@link Response}（阻塞签名），由 RESTEasy Reactive 调度到 worker 线程——
 * 决策服务内部同步等待规则评估与审计链写入。不加 {@code @Transactional}：服务内部各写入步骤
 * 自行开启独立事务。路径由 {@code ApiKeyAuthFilter} 统一纳入 API key 鉴权。
 */
@Path(GuardDecisionResource.PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardDecisionResource {

  /** 计量与日志使用的端点路径。 */
  static final String PATH = "/api/v1/guard/decisions";

  @Inject
  RequestIdentityResolver identityResolver;

  @Inject
  GuardDecisionService service;

  @Inject
  ApiQuotaGuard apiQuotaGuard;

  /**
   * 对提议动作作出决策；{@code Idempotency-Key} 可选，同租户同键重放返回首次响应。
   * 返回决策（200）时按 {@link #PATH} 计量一次调用，与 {@code /evaluate} 同一计费口径；400 / 404 不计量。
   */
  @POST
  public Response decide(@Valid GuardActionRequest req, @HeaderParam("Idempotency-Key") String idempotencyKey) {
    long startTime = System.currentTimeMillis();
    // 身份在请求线程上一次取齐：限流桶键与计费归属都依赖已验证的 apiKeyId。
    String tenantId = identityResolver.tenantId();
    String performedBy = identityResolver.performedBy();
    String apiKeyId = identityResolver.apiKeyId();
    Optional<Response> quotaRejection = quotaRejection(tenantId, performedBy, apiKeyId);
    if (quotaRejection.isPresent()) {
      return quotaRejection.get();
    }
    try {
      GuardDecisionResponse decision = service.decide(tenantId, performedBy, req, idempotencyKey);
      apiQuotaGuard.recordAsync(performedBy, tenantId, apiKeyId, PATH, "success",
          System.currentTimeMillis() - startTime);
      return Response.ok(decision).build();
    } catch (GuardInvalidActionException bad) {
      return error(Response.Status.BAD_REQUEST, "invalid_action", bad.getMessage());
    } catch (GuardPolicyNotFoundException nf) {
      return error(Response.Status.NOT_FOUND, "policy_not_found", nf.getMessage());
    }
  }

  /** 回读决策；非法 UUID 与跨租户一律 404，不泄露决策是否存在。 */
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    UUID decisionId;
    try {
      decisionId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return service.get(identityResolver.tenantId(), decisionId)
        .map(r -> Response.ok(r).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * 与 {@code PolicyEvaluationResource.enforceApiQuota} 相同的配额判定与拒绝体：套餐裁决（403 / 429），
   * 再按已验证 API key 做每秒限流（429 + {@code Retry-After: 1}）。那边是私有方法且耦合 trial 旁路与
   * 响应头写入，这里只复用 {@link ApiQuotaGuard} 的两次裁决映射。
   */
  private Optional<Response> quotaRejection(String tenantId, String userId, String apiKeyId) {
    ApiQuotaGuard.GuardResult result = apiQuotaGuard.check(tenantId, userId);
    switch (result.verdict()) {
      case FORBIDDEN -> {
        return Optional.of(Response.status(Response.Status.FORBIDDEN)
            .entity(Map.of(
                "error", "api_access_denied",
                "message", "Free 计划不包含 Policy Execution API。请升级到 Pro/Team 或试用 Trial。"))
            .type(MediaType.APPLICATION_JSON)
            .build());
      }
      case RATE_LIMITED -> {
        return Optional.of(Response.status(Response.Status.TOO_MANY_REQUESTS)
            .entity(Map.of(
                "error", "api_quota_hard_exceeded",
                "message", "本月 API 调用已超 200% 上限，已拒绝。请升级套餐或联系客服。",
                "limit", result.limit(),
                "used", result.used()))
            .type(MediaType.APPLICATION_JSON)
            .build());
      }
      default -> { /* ALLOW：即使 soft warn 也继续 */ }
    }
    if (apiKeyId == null || apiKeyId.isBlank()) {
      return Optional.empty();
    }
    ApiQuotaGuard.RateCheck rate = apiQuotaGuard.checkRate(tenantId, apiKeyId);
    if (rate.allowed()) {
      return Optional.empty();
    }
    return Optional.of(Response.status(Response.Status.TOO_MANY_REQUESTS)
        .header("Retry-After", "1")
        .entity(Map.of(
            "error", "rate_limit_exceeded",
            "message", "Per-API-key per-second 限流：超过 " + rate.limit() + " RPS",
            "rps_limit", rate.limit(),
            "rps_used", rate.used()))
        .type(MediaType.APPLICATION_JSON)
        .build());
  }

  private static Response error(Response.Status status, String code, String message) {
    return Response.status(status).entity(Map.of("error", code, "message", message)).build();
  }
}
