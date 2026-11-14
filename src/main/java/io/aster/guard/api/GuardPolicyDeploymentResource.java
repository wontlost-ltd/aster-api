package io.aster.guard.api;

import io.aster.guard.GuardInvalidActionException;
import io.aster.guard.GuardRequestBodyReader;
import io.aster.guard.deploy.PolicyDeployment;
import io.aster.guard.deploy.PolicyDeployment.DeployResult;
import io.aster.guard.deploy.PolicyDeployment.Deployed;
import io.aster.guard.deploy.PolicyDeployment.Rejected;
import io.aster.guard.deploy.PolicyDeployment.Withdrawn;
import io.aster.guard.deploy.PolicyDeploymentService;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.aster.security.apikey.InternalCallerFilter;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * cloud 策略部署（ADR 0054 §11 D1，R66）：cloud 在策略默认版本变更时经内部 HMAC 通道把该版本部署为 aster-api 的
 * 活跃版本，删除策略时撤回。只接受 HMAC：路径由 {@code InternalCallerFilter} 恒要求验签（Bearer 403
 * {@code internal_only}），这里再按验签章兜底，同签发方管理。租户取签名内的 {@code X-Tenant-Id}；
 * 签名用户为空 / {@code anonymous} 时 400 {@code user_required}（部署须可归责到 cloud 的具体操作者）。
 *
 * <p>{@code PUT} 200 {@code { cloudPolicyId, cloudVersionId, created, functionName, moduleName, policyVersionId,
 * sourceHash, version }}；{@code DELETE ?generation=n} 204，无此部署 404 空体；错误体 {@code { error, message, ... }}。
 */
@Path("/api/v1/guard/policy-deployments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardPolicyDeploymentResource {
  private static final String INVALID = "invalid_deployment";

  @Inject
  RequestIdentityResolver identityResolver;

  @Inject
  PolicyDeploymentService service;

  @Inject
  GuardRequestBodyReader bodyReader;

  @Context
  ContainerRequestContext requestContext;

  @PUT
  @Path("/{cloudPolicyId}")
  public Response put(@PathParam("cloudPolicyId") String cloudPolicyId, InputStream body) {
    Response denied = gate();
    if (denied != null) {
      return denied;
    }
    PolicyDeploymentRequest req;
    try {
      req = bodyReader.read(body, PolicyDeploymentRequest.class).value();
    } catch (GuardInvalidActionException duplicate) {
      return error(400, INVALID, duplicate.getMessage(), null);
    } catch (IOException | WebApplicationException malformed) {
      return error(400, INVALID, "请求体不是合法的部署 JSON", null);
    }
    if (req == null || req.generation() == null || req.cloudVersion() == null) {
      return error(400, INVALID, "缺少请求体或 generation / cloudVersion", null);
    }
    PolicyDeployment d = new PolicyDeployment(cloudPolicyId, req.generation(), req.cloudVersionId(),
        req.cloudVersion(), req.moduleName(), req.functionName(), req.source(), req.locale(), req.aliasSet(),
        req.vocabulary(), req.sourceSha256());
    DeployResult result = service.deploy(identityResolver.tenantId(), identityResolver.performedBy(), d);
    if (result instanceof Deployed ok) {
      Map<String, Object> view = new TreeMap<>();
      view.put("cloudPolicyId", cloudPolicyId);
      view.put("cloudVersionId", d.cloudVersionId());
      view.put("created", ok.created());
      view.put("functionName", d.functionName());
      view.put("moduleName", d.moduleName());
      view.put("policyVersionId", ok.policyVersionId());
      view.put("sourceHash", ok.sourceHash());
      view.put("version", ok.version());
      return Response.ok(view).build();
    }
    return rejected((Rejected) result);
  }

  @DELETE
  @Path("/{cloudPolicyId}")
  public Response delete(@PathParam("cloudPolicyId") String cloudPolicyId, @QueryParam("generation") Long generation) {
    Response denied = gate();
    if (denied != null) {
      return denied;
    }
    if (generation == null) {
      return error(400, INVALID, "缺少 generation 查询参数", null);
    }
    DeployResult result = service.withdraw(identityResolver.tenantId(), identityResolver.performedBy(), cloudPolicyId,
        generation);
    if (result instanceof Withdrawn) {
      return Response.noContent().build();
    }
    return rejected((Rejected) result);
  }

  /** HMAC 验签、签名租户与签名用户三道前置；通过返回 null。 */
  private Response gate() {
    if (!InternalCallerFilter.isHmacVerified(requestContext)) {
      return error(403, "internal_only", "仅限内部 HMAC 调用者", null);
    }
    String tenantHeader = requestContext.getHeaderString("X-Tenant-Id");
    if (tenantHeader == null || tenantHeader.isBlank()) {
      return error(400, INVALID, "策略部署需要签名内的租户（X-Tenant-Id）", null);
    }
    String user = identityResolver.performedBy();
    if (user == null || user.isBlank() || "anonymous".equals(user)) {
      return error(400, "user_required", "策略部署需要签名内的操作者（X-User-Id）", null);
    }
    return null;
  }

  private static Response rejected(Rejected r) {
    if (r.status() == 404) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return error(r.status(), r.error(), r.message(), r.detail());
  }

  private static Response error(int status, String code, String message, Object detail) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    if (detail instanceof Map<?, ?> extra) {
      extra.forEach((k, v) -> body.put(String.valueOf(k), v));
    }
    return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
  }
}
