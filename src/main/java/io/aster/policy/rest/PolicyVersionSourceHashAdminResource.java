package io.aster.policy.rest;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.security.AdminHmacVerifier;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只读返回租户策略版本的源码哈希，供跨服务一致性探针使用。 */
@Path("/api/v1/admin/policy-versions/source-hashes")
@Produces(MediaType.APPLICATION_JSON)
public class PolicyVersionSourceHashAdminResource {

    private static final String PATH = "/api/v1/admin/policy-versions/source-hashes";
    private static final int MAX_IDS = 200;

    @Inject
    AdminHmacVerifier hmac;

    @Inject
    EntityManager entityManager;

    @GET
    public Response list(@Context HttpHeaders headers,
                         @QueryParam("tenantId") String tenantId,
                         @QueryParam("ids") String ids,
                         @QueryParam("beforeId") Long beforeId) {
        hmac.verify(headers, "GET", PATH, null, 0, null);
        if (tenantId == null || tenantId.isBlank()) {
            throw badRequest("tenantId_required");
        }
        if (beforeId != null && beforeId <= 0) throw badRequest("invalid_before_id");
        List<Long> parsedIds = parseIds(ids);
        List<PolicyVersionView> rows = parsedIds.isEmpty()
            ? latestForTenant(tenantId, beforeId)
            : versionsByIds(tenantId, parsedIds);
        return Response.ok(rows).build();
    }

    private List<Long> parseIds(String ids) {
        if (ids == null || ids.isBlank()) return List.of();
        String[] parts = ids.split(",", -1);
        if (parts.length > MAX_IDS) throw badRequest("too_many_ids");
        List<Long> parsed = new ArrayList<>(parts.length);
        try {
            for (String part : parts) {
                long id = Long.parseLong(part.trim());
                if (id <= 0) throw new NumberFormatException();
                parsed.add(id);
            }
        } catch (NumberFormatException e) {
            throw badRequest("invalid_ids");
        }
        return parsed;
    }

    private List<PolicyVersionView> latestForTenant(String tenantId, Long beforeId) {
        var query = beforeId == null
            ? entityManager.createQuery(
                "select v from PolicyVersion v where v.tenantId = :tenant order by v.id desc",
                PolicyVersion.class)
            : entityManager.createQuery(
                "select v from PolicyVersion v where v.tenantId = :tenant and v.id < :beforeId order by v.id desc",
                PolicyVersion.class);
        query.setParameter("tenant", tenantId);
        if (beforeId != null) query.setParameter("beforeId", beforeId);
        return query.setMaxResults(MAX_IDS).getResultList().stream()
            .map(PolicyVersionSourceHashAdminResource::view).toList();
    }

    private List<PolicyVersionView> versionsByIds(String tenantId, List<Long> ids) {
        return entityManager.createQuery(
                "select v from PolicyVersion v where v.tenantId = :tenant and v.id in :ids order by v.id desc",
                PolicyVersion.class)
            .setParameter("tenant", tenantId)
            .setParameter("ids", ids)
            .getResultList().stream().map(PolicyVersionSourceHashAdminResource::view).toList();
    }

    private static PolicyVersionView view(PolicyVersion version) {
        return new PolicyVersionView(version.id, version.moduleName, version.functionName,
            version.sourceHash, version.status == null ? null : version.status.name(),
            Boolean.TRUE.equals(version.active));
    }

    private static WebApplicationException badRequest(String error) {
        return new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("error", error)).type(MediaType.APPLICATION_JSON).build());
    }

    public record PolicyVersionView(Long id, String moduleName, String functionName,
                                    String sourceHash, String status, boolean active) { }
}
