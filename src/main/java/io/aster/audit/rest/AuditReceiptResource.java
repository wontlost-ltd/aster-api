package io.aster.audit.rest;

import io.aster.audit.service.AuditReceiptService;
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
 * 审计链收据查询（ADR 0041 §2.4）：供 aster-cloud 证据导出按相关键 / 决策 ID 引用链上收据。
 *
 * <p>Bearer API key 与 HMAC 内部调用者（InternalCallerFilter 验签）均可访问；
 * 方法级 MEMBER 角色，租户一律取自 {@link RequestIdentityResolver}。
 */
@Path("/api/v1/audit/receipts")
@Produces(MediaType.APPLICATION_JSON)
public class AuditReceiptResource {

    /** 单次查询 id 上限，与 AuditLog 查询上限一致。 */
    static final int MAX_IDS = 200;

    @Inject
    RequestIdentityResolver identityResolver;

    @Inject
    AuditReceiptService service;

    @GET
    @Blocking
    @RequireRole(Role.MEMBER)
    public Response lookup(@QueryParam("correlationIds") String correlationIds,
                           @QueryParam("decisionIds") String decisionIds) {
        boolean hasCorrelation = correlationIds != null;
        boolean hasDecision = decisionIds != null;
        if (hasCorrelation == hasDecision) {
            return badRequest("exactly_one_of_correlationIds_or_decisionIds");
        }
        List<String> ids = parseIds(hasCorrelation ? correlationIds : decisionIds);
        if (ids.size() > MAX_IDS) {
            return badRequest("too_many_ids");
        }
        String tenantId = identityResolver.tenantId();
        Object body = hasCorrelation
            ? service.byCorrelationIds(tenantId, ids)
            : service.byDecisionIds(tenantId, ids);
        return Response.ok(body).build();
    }

    /** 逗号切分、去空白、去空串、去重，保持请求顺序。 */
    static List<String> parseIds(String csv) {
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    }

    private static Response badRequest(String error) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", error)).build();
    }
}
