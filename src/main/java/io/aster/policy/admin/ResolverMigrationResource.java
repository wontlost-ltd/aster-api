package io.aster.policy.admin;

import io.aster.policy.security.AdminHmacVerifier;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * 触发存量重编译（ADR 0051 §9），返回 {@code { processed, succeeded, failed, failures: [{ id, error }] }}。
 *
 * <p>只允许内部可信调用方：与其它 admin 端点同一套资源内 HMAC（{@link AdminHmacVerifier}）。
 * 请求无 body，按无 body 口径签名（contentType 空、长度 0、body 哈希空）；batchSize 只影响分页粒度，不进签名。
 * 同步执行到全部待迁移行处理完；任务幂等，可随时中断后重调。
 */
@Path(ResolverMigrationResource.PATH)
@Produces(MediaType.APPLICATION_JSON)
public class ResolverMigrationResource {

    static final String PATH = "/api/v1/admin/resolver-migration/run";
    static final int DEFAULT_BATCH_SIZE = 50;
    static final int MAX_BATCH_SIZE = 500;

    @Inject AdminHmacVerifier hmac;
    @Inject ResolverMigrationService migration;

    @POST
    public Response run(@Context HttpHeaders headers, @QueryParam("batchSize") Integer batchSize) {
        hmac.verify(headers, "POST", PATH, null, 0, null);
        int size = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        if (size < 1 || size > MAX_BATCH_SIZE) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "invalid_batch_size")).type(MediaType.APPLICATION_JSON).build());
        }
        return Response.ok(migration.run(size, null)).build();
    }
}
