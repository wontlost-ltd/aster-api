package io.aster.policy.revalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.security.AdminHmacVerifier;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Path("/api/v1/admin/revalidation")
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
public class RevalidationAdminResource {
    private static final String BASE = "/api/v1/admin/revalidation";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.regex.Pattern REQUESTED_BY = java.util.regex.Pattern.compile("[A-Za-z0-9._@:-]{1,200}");
    @Inject AdminHmacVerifier hmac;
    @Inject RevalidationRunService runs;
    @Inject EntityManager entityManager;

    @POST @Path("/runs")
    public Response create(@Context HttpHeaders headers, String body) {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        hmac.verify(headers, "POST", BASE + "/runs", MediaType.APPLICATION_JSON, bytes.length,
            AdminHmacVerifier.sha256Hex(bytes));
        RunRequest request;
        try { request = MAPPER.readValue(body, RunRequest.class); }
        catch (Exception e) { throw error(400, "invalid_request"); }
        if (!"all".equals(request.scope()) && !"tenant".equals(request.scope())) throw error(400, "invalid_scope");
        if ("tenant".equals(request.scope()) && (request.tenantId() == null || request.tenantId().isBlank())) throw error(400, "tenant_required");
        if (request.requestedBy() != null && !REQUESTED_BY.matcher(request.requestedBy()).matches()) throw error(400, "invalid_requested_by");
        // 本端点只有 HMAC 签名的 cloud 后台能调用；requestedBy 是 cloud 已验证的管理员 id，缺省时只记「admin」。
        String triggeredBy = request.requestedBy() == null ? "admin" : "admin:" + request.requestedBy();
        var id = runs.enqueue(request.scope(), request.tenantId(), triggeredBy);
        if (id.isEmpty()) return Response.status(409).entity(Map.of("error", "active_run_exists")).build();
        return Response.status(202).entity(Map.of("runId", id.get())).build();
    }

    @GET @Path("/runs/{id}")
    public Response get(@Context HttpHeaders headers, @PathParam("id") long id) {
        hmac.verify(headers, "GET", BASE + "/runs/" + id, null, 0, null);
        List<?> rows = entityManager.createNativeQuery("SELECT id,toolchain_id,scope,scope_tenant,status,cursor,scope_max_version_id,total,checked,invalid_count,started_at,finished_at,error,triggered_by FROM policy_revalidation_run WHERE id=:id")
            .setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw error(404, "run_not_found");
        Object[] r = (Object[]) rows.getFirst();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("runId", r[0]);
        result.put("toolchainId", r[1]);
        result.put("scope", r[2]);
        result.put("scopeTenant", r[3]);
        result.put("status", r[4]);
        result.put("cursor", r[5]);
        result.put("scopeMaxVersionId", r[6]);
        result.put("total", r[7]);
        result.put("checked", r[8]);
        result.put("invalidCount", r[9]);
        result.put("startedAt", r[10]);
        result.put("finishedAt", r[11]);
        result.put("error", r[12]);
        result.put("triggeredBy", r[13]);
        return Response.ok(result).build();
    }

    @GET @Path("/invalid")
    public Response invalid(@Context HttpHeaders headers, @QueryParam("toolchainId") String toolchainId,
                            @QueryParam("page") Integer page, @QueryParam("size") Integer size) {
        hmac.verify(headers, "GET", BASE + "/invalid", null, 0, null);
        int safePage = Math.max(0, page == null ? 0 : page);
        int safeSize = Math.min(200, Math.max(1, size == null ? 50 : size));
        List<?> rows = entityManager.createNativeQuery("SELECT version_id,toolchain_id,diagnostics,checked_at FROM policy_version_validation WHERE status='invalid' AND (CAST(:toolchain AS text) IS NULL OR toolchain_id=:toolchain) ORDER BY checked_at DESC,version_id LIMIT :limit OFFSET :offset")
            .setParameter("toolchain", toolchainId).setParameter("limit", safeSize).setParameter("offset", safePage * safeSize).getResultList();
        List<Map<String, Object>> items = rows.stream().map(row -> {
            Object[] values = (Object[]) row;
            return Map.<String, Object>of("versionId", values[0], "toolchainId", values[1],
                "diagnostics", values[2] == null ? "[]" : values[2].toString(), "checkedAt", values[3]);
        }).toList();
        return Response.ok(Map.of("page", safePage, "size", safeSize, "items", items)).build();
    }

    private static WebApplicationException error(int status, String code) {
        return new WebApplicationException(Response.status(status).entity(Map.of("error", code)).build());
    }

    public record RunRequest(String scope, String tenantId, String requestedBy) {}
}
