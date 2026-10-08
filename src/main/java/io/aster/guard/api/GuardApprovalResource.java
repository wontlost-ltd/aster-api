package io.aster.guard.api;

import io.aster.guard.GuardApprovalConflictException;
import io.aster.guard.GuardApprovalService;
import io.aster.guard.GuardApprovalStatus;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Action Guard 待审批端点（ADR 0040 §4.2）：列出租户内待审批，人工批准 / 拒绝。
 *
 * <p>审批人身份取 {@link RequestIdentityResolver#performedBy()}（{@code X-User-Id}，API key 鉴权时被覆盖为已验证用户）
 * 与 {@link RequestIdentityResolver#apiKeyId()}，用于四眼比对；业务角色取 {@link RequestIdentityResolver#businessRoles()}
 * 的已验证集合（API key verify 结果或 HMAC 签过的值，ADR 0042 §2.3），调用方自报的角色头不参与判定。
 * 方法为阻塞签名，由 worker 线程执行：服务内部同步写审计链，不加 {@code @Transactional}。
 */
@Path("/api/v1/guard/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardApprovalResource {
  static final int MAX_PAGE_SIZE = 200;

  @Inject
  RequestIdentityResolver identityResolver;

  @Inject
  GuardApprovalService service;

  /** 租户内按状态分页列出审批，默认 PENDING（不含已到期行）；page 从 0 起，size 取 1–200。 */
  @GET
  public Response list(@QueryParam("status") @DefaultValue("PENDING") String status,
                       @QueryParam("page") @DefaultValue("0") int page,
                       @QueryParam("size") @DefaultValue("20") int size) {
    GuardApprovalStatus parsed;
    try {
      parsed = GuardApprovalStatus.valueOf(status);
    } catch (IllegalArgumentException e) {
      return error(Response.Status.BAD_REQUEST, "invalid_status", "status 须为 PENDING / APPROVED / REJECTED / EXPIRED");
    }
    int safePage = Math.max(0, page);
    int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
    List<GuardApprovalView> items = service.list(identityResolver.tenantId(), parsed, safePage, safeSize);
    return Response.ok(new GuardApprovalListResponse(items, safePage, safeSize)).build();
  }

  /** 批准：comment 可选；成功返回 effectiveOutcome 为 ALLOW 的决策响应。 */
  @POST
  @Path("/{id}/approve")
  public Response approve(@PathParam("id") String id, Map<String, String> body) {
    return decide(id, true, body);
  }

  /** 拒绝：comment 必填；成功返回 effectiveOutcome 为 DENY 的决策响应。 */
  @POST
  @Path("/{id}/reject")
  public Response reject(@PathParam("id") String id, Map<String, String> body) {
    return decide(id, false, body);
  }

  /** 非法 UUID 与跨租户一律 404 approval_not_found，不泄露审批是否存在。 */
  private Response decide(String id, boolean approve, Map<String, String> body) {
    String comment = body == null ? null : body.get("comment");
    try {
      UUID approvalId = parseId(id);
      GuardDecisionResponse r = service.decideApproval(identityResolver.tenantId(), approvalId,
          identityResolver.performedBy(), identityResolver.apiKeyId(), identityResolver.businessRoles(), approve,
          comment);
      return Response.ok(r).build();
    } catch (GuardApprovalConflictException e) {
      return error(statusOf(e.code()), e.code(), e.getMessage(), e.details());
    }
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new GuardApprovalConflictException("approval_not_found", "审批不存在: " + id);
    }
  }

  private static Response.Status statusOf(String code) {
    return switch (code) {
      case "approval_not_found" -> Response.Status.NOT_FOUND;
      case "segregation_of_duties", "role_mismatch" -> Response.Status.FORBIDDEN;
      case "approval_not_pending" -> Response.Status.CONFLICT;
      case "receipt_unavailable" -> Response.Status.SERVICE_UNAVAILABLE;
      default -> Response.Status.BAD_REQUEST;
    };
  }

  private static Response error(Response.Status status, String code, String message) {
    return error(status, code, message, Map.of());
  }

  /** 错误体：error、message 在前，异常附加字段（如 verifiedRoles）随后并入。 */
  private static Response error(Response.Status status, String code, String message, Map<String, Object> details) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    details.forEach(body::putIfAbsent);
    return Response.status(status).entity(body).build();
  }
}
