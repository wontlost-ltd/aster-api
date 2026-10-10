package io.aster.policy.replay.batch;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 回放逐项查询（ADR 0044 §4）：供 aster-cloud 证据导出把 What-If 结论挂到条目上。
 *
 * <p>与 /api/v1/audit/receipts 同为双通道：HMAC 内部调用者或 Bearer API key；租户取自 RequestIdentityResolver。
 */
@Path("/api/v1/replay/items")
@Produces(MediaType.APPLICATION_JSON)
public class ReplayItemLookupResource {

    static final int MAX_IDS = AuditLog.MAX_LOOKUP_IDS;

    @Inject RequestIdentityResolver identityResolver;
    @Inject ReplayItemLookupService service;

    @GET
    @Blocking
    @RequireRole(Role.MEMBER)
    public Response lookup(@QueryParam("executionIds") String executionIds) {
        if (executionIds == null) return badRequest("executionIds_required");
        List<String> ids = Arrays.stream(executionIds.split(",")).map(String::trim)
            .filter(s -> !s.isEmpty()).distinct().toList();
        if (ids.size() > MAX_IDS) return badRequest("too_many_ids");
        if (ids.isEmpty()) return badRequest("executionIds_required");
        return Response.ok(service.latestByExecutionIds(identityResolver.tenantId(), ids)).build();
    }

    private static Response badRequest(String error) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", error)).build();
    }
}
