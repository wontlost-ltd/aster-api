package io.aster.guard.api;

import io.aster.billing.ApiQuotaGuard;
import io.aster.guard.GuardApprovalConflictException;
import io.aster.guard.GuardDecisionService;
import io.aster.guard.GuardEvidenceDecisionService;
import io.aster.guard.GuardIdempotencyMismatchException;
import io.aster.guard.GuardInvalidActionException;
import io.aster.guard.GuardPolicyNotFoundException;
import io.aster.guard.GuardRequestBodyReader;
import io.aster.guard.provenance.GuardInvalidFactException;
import io.aster.guard.receipt.GuardReceiptService;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.aster.security.apikey.InternalCallerFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Action Guard 决策端点（ADR 0040 §3）：Agent 在执行动作前提交提议，拿回 ALLOW / DENY / PENDING 与收据。
 *
 * <p>方法返回 {@link Response}（阻塞签名），由 RESTEasy Reactive 调度到 worker 线程——
 * 决策服务内部同步等待规则评估与审计链写入。不加 {@code @Transactional}：服务内部各写入步骤
 * 自行开启独立事务。路径由 {@code ApiKeyAuthFilter} 统一纳入 API key 鉴权；{@code GET /{id}} 与
 * {@code POST /from-evidence} 另对内部 HMAC 通道开放（ADR 0042 §4.2），后者只接受 HMAC。
 */
@Path("/api/v1/guard/decisions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardDecisionResource {

  /**
   * 计量与日志使用的端点路径，与类级 {@code @Path} 相同。类级注解必须写字符串字面量：
   * 公开 API 契约守卫（{@code PublicApiContractTest}）只识别字面量，写常量会让本端点逃出契约。
   */
  static final String PATH = "/api/v1/guard/decisions";
  /** JAX-RS {@link Response.Status} 没有 422 常量。 */
  private static final int UNPROCESSABLE_ENTITY = 422;

  @Inject
  RequestIdentityResolver identityResolver;

  @Inject
  GuardDecisionService service;

  @Inject
  GuardEvidenceDecisionService evidenceService;

  @Inject
  ApiQuotaGuard apiQuotaGuard;

  @Inject
  GuardRequestBodyReader bodyReader;

  @Inject
  GuardReceiptService receiptService;

  @Context
  ContainerRequestContext requestContext;

  /**
   * 对提议动作作出决策；{@code Idempotency-Key} 可选，同租户同键重放同一决策（审批状态取当前值），
   * 同键不同动作返回 422 {@code idempotency_mismatch}。
   * 返回决策（200）时按 {@link #PATH} 计量一次调用，与 {@code /evaluate} 同一计费口径；400 / 404 / 422 不计量。
   *
   * <p>请求体以原始字节读取（{@link GuardRequestBodyReader}）：{@code actionHash} 作用于收到的 {@code action} 原文，
   * 重复键拒绝；其余非法请求的响应与框架解析时逐字节相同。解析放在配额判定之前，与框架在进入方法前解析同序。
   *
   * @throws IOException 类型不符（{@code MismatchedInputException}）原样交给 Quarkus 内置映射器，或读取请求体失败
   */
  @POST
  public Response decide(InputStream body, @HeaderParam("Idempotency-Key") String idempotencyKey) throws IOException {
    long startTime = System.currentTimeMillis();
    GuardRequestBodyReader.Parsed<GuardActionRequest> req;
    try {
      req = bodyReader.read(body, GuardActionRequest.class);
    } catch (GuardInvalidFactException bad) {
      return invalidFact(bad);
    } catch (GuardInvalidActionException bad) {
      return error(Response.Status.BAD_REQUEST.getStatusCode(), "invalid_action", bad.getMessage());
    }
    // 身份在请求线程上一次取齐：限流桶键与计费归属都依赖已验证的 apiKeyId。
    String tenantId = identityResolver.tenantId();
    String performedBy = identityResolver.performedBy();
    String apiKeyId = identityResolver.apiKeyId();
    // 已验证业务角色：principal.roles 只有在其中才能被服务端证实（ADR 0040 §11 D2 (a)）
    Set<String> businessRoles = Set.copyOf(identityResolver.businessRoles());
    // 月度配额按归属取键（ADR 0015 §3）：团队 key 计入 team owner 的池。
    String quotaOwnerId = identityResolver.quotaOwnerId();
    // 套餐门禁与计量只针对 API 调用；HMAC 已验签、无 API key 的内部会话调用两者都不经过。
    // 当前过滤器配置下该分支不可达：POST /api/v1/guard/decisions 不在 InternalCallerFilter 的验签路径里，
    // 拿不到验签章（ApiKeyAuthFilter 恒要求 Bearer）。保留它是为了与 /evaluate* 同口径，
    // 将来若给本端点开 HMAC 通道，cloud 侧须同时补上配额（见 RequestIdentityResolver#internalSessionCall）。
    boolean apiCall = !identityResolver.internalSessionCall();
    Optional<Response> quotaRejection = apiCall ? quotaRejection(tenantId, quotaOwnerId, apiKeyId) : Optional.empty();
    if (quotaRejection.isPresent()) {
      return quotaRejection.get();
    }
    try {
      GuardDecisionResponse decision = service.decide(tenantId, performedBy, apiKeyId, businessRoles, req.value(),
          req.actionHash(), req.action(), idempotencyKey);
      if (apiCall) {
        apiQuotaGuard.recordAsync(quotaOwnerId, performedBy, tenantId, apiKeyId, PATH, "success",
            System.currentTimeMillis() - startTime);
      }
      return Response.ok(decision).build();
    } catch (GuardInvalidFactException bad) {
      return invalidFact(bad);
    } catch (GuardInvalidActionException bad) {
      return error(Response.Status.BAD_REQUEST.getStatusCode(), "invalid_action", bad.getMessage());
    } catch (GuardIdempotencyMismatchException mismatch) {
      return error(UNPROCESSABLE_ENTITY, "idempotency_mismatch", mismatch.getMessage());
    } catch (GuardPolicyNotFoundException nf) {
      return error(Response.Status.NOT_FOUND.getStatusCode(), "policy_not_found", nf.getMessage());
    }
  }

  /**
   * 从证据开决策（ADR 0042 §3.1）：仅内部 HMAC 通道，收据即评估事件。
   * 路径已由 {@code InternalCallerFilter} 恒要求 HMAC，这里再按验签章兜底；内部调用不计量配额。
   */
  @POST
  @Path("/from-evidence")
  public Response fromEvidence(InputStream body) throws IOException {
    if (!InternalCallerFilter.isHmacVerified(requestContext)) {
      return error(Response.Status.FORBIDDEN.getStatusCode(), "internal_only", "仅限内部 HMAC 调用者");
    }
    try {
      GuardRequestBodyReader.Parsed<GuardFromEvidenceRequest> req = bodyReader.read(body, GuardFromEvidenceRequest.class);
      return Response.ok(evidenceService.createFromEvidence(identityResolver.tenantId(),
          identityResolver.performedBy(), Set.copyOf(identityResolver.businessRoles()), req.value(), req.actionHash(),
          req.action())).build();
    } catch (GuardInvalidFactException bad) {
      return invalidFact(bad);
    } catch (GuardInvalidActionException bad) {
      return error(Response.Status.BAD_REQUEST.getStatusCode(), "invalid_action", bad.getMessage());
    } catch (GuardApprovalConflictException e) {
      return error(evidenceStatusOf(e.code()), e.code(), e.getMessage(), e.details());
    }
  }

  /**
   * 回读决策；非法 UUID 与跨租户一律 404，不泄露决策是否存在。响应在存储的决策之外附执行状态
   * （{@code actionHash / consumedBy / result}，ADR 0054 §3.3），见 {@link GuardDecisionReadResponse}。
   */
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    UUID decisionId;
    try {
      decisionId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return receiptService.decisionView(identityResolver.tenantId(), decisionId)
        .map(r -> Response.ok(r).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * 与 {@code PolicyEvaluationResource.enforceApiQuota} 相同的配额判定与拒绝体：套餐裁决（403 / 429），
   * 再按已验证 API key 做每秒限流（429 + {@code Retry-After: 1}）。那边是私有方法且耦合 trial 旁路与
   * 响应头写入，这里只复用 {@link ApiQuotaGuard} 的两次裁决映射。
   */
  private Optional<Response> quotaRejection(String tenantId, String quotaOwnerId, String apiKeyId) {
    ApiQuotaGuard.GuardResult result = apiQuotaGuard.check(tenantId, quotaOwnerId);
    switch (result.verdict()) {
      case FORBIDDEN -> {
        return Optional.of(apiAccessDenied());
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
    // plan=free（RPS 上限 0）是「无权访问」而非「太快」：与月度配额 FORBIDDEN 同为 403
    if (rate.forbidden()) {
      return Optional.of(apiAccessDenied());
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

  private static Response apiAccessDenied() {
    return Response.status(Response.Status.FORBIDDEN)
        .entity(Map.of(
            "error", "api_access_denied",
            "message", "Free 计划不包含 Policy Execution API。请升级到 Pro/Team 或试用 Trial。"))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }

  private static int evidenceStatusOf(String code) {
    Response.Status status = switch (code) {
      case "evidence_not_found" -> Response.Status.NOT_FOUND;
      case "evidence_not_approvable" -> Response.Status.CONFLICT;
      default -> Response.Status.BAD_REQUEST;
    };
    return status.getStatusCode();
  }

  /**
   * 400 {@code invalid_fact}（ADR 0040 §11 D3）：{@code error, message} 之后附 {@code issuer / kid / reason}；
   * issuer、kid 无法从请求中确定时省略。不计量（与其他 400 一致）。
   */
  private static Response invalidFact(GuardInvalidFactException bad) {
    Map<String, Object> details = new LinkedHashMap<>();
    if (bad.issuer() != null) {
      details.put("issuer", bad.issuer());
    }
    if (bad.kid() != null) {
      details.put("kid", bad.kid());
    }
    details.put("reason", bad.reason());
    return error(Response.Status.BAD_REQUEST.getStatusCode(), "invalid_fact", bad.getMessage(), details);
  }

  private static Response error(int status, String code, String message) {
    return error(status, code, message, Map.of());
  }

  /** 错误体：error、message 在前，异常附加字段（如 evidence_not_approvable 的 outcome）随后并入。 */
  private static Response error(int status, String code, String message, Map<String, Object> details) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    details.forEach(body::putIfAbsent);
    return Response.status(status).entity(body).build();
  }
}
