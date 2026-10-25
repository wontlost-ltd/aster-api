package io.aster.billing;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;

import java.time.Duration;

/**
 * 仅测试用探针端点：模拟「每秒限流裁决异步完成（含 Mutiny 定时线程上的超时分支）后，
 * 再继续执行 evaluate 主体」的组合方式，验证续体里请求作用域状态仍然可用。
 */
@Path("/test-393/rate-gate-probe")
public class RateGateContinuationProbeResource {

    @Inject
    RoutingContext routingContext;

    @Context
    ContainerRequestContext jaxrsCtx;

    @Inject
    io.aster.policy.tenant.TenantContext tenantContext;

    @GET
    public Uni<String> probe() {
        jaxrsCtx.setProperty("probe.prop", "captured");
        // delayIt 在 Mutiny 默认定时线程上发射——与限流超时分支同一类线程
        Uni<String> gate = ApiQuotaGuard.resumeOnCaller(
            Uni.createFrom().item("gate").onItem().delayIt().by(Duration.ofMillis(30)));
        return gate.chain(ignored -> Uni.createFrom().item(
            routingContext.request().getHeader("X-Probe")
                + "|" + jaxrsCtx.getProperty("probe.prop")
                + "|" + tenantContext.getCurrentTenant()
                + "|" + io.vertx.core.Context.isOnEventLoopThread()));
    }
}
