package io.aster.guard.api;

import io.aster.guard.GuardInvalidActionException;
import io.aster.guard.GuardRequestBodyReader;
import io.aster.guard.reconcile.SideEffectIngestService;
import io.aster.guard.reconcile.SideEffectReport;
import io.aster.guard.reconcile.SideEffectReport.InvalidSideEffectException;
import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 副作用落地方批量上报（ADR 0054 §10.2，#465）：工具服务、业务系统、cloud 上报「我做了什么、凭哪个 decisionId」，
 * 由定时对账逐条对到收据行。这是唯一能发现绕过的数据面——绕过者从不触达 Guard，但副作用总要落在某个系统里。
 *
 * <p>Bearer API key 与 HMAC 内部调用双通道，租户取自 {@link RequestIdentityResolver}。不计量配额：上报是账本输入，
 * 不含评估。阻塞签名，不加 {@code @Transactional}，写入由服务自开事务。
 */
@Path("/api/v1/guard/side-effects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class GuardSideEffectResource {
  static final String INVALID_SIDE_EFFECT = "invalid_side_effect";

  @Inject
  RequestIdentityResolver identityResolver;

  @Inject
  SideEffectIngestService ingest;

  @Inject
  GuardRequestBodyReader bodyReader;

  @Inject
  Clock clock;

  @POST
  public Response report(InputStream body) {
    SideEffectReport report;
    try {
      report = bodyReader.read(body, SideEffectReport.class).value();
    } catch (GuardInvalidActionException | IOException | WebApplicationException e) {
      // 重复键、非法 JSON、类型不符：整体不合法，无下标
      return invalid(null, null);
    }
    try {
      SideEffectReport.validate(report, clock.instant());
    } catch (InvalidSideEffectException e) {
      return invalid(e.index() < 0 ? null : e.index(), e.field());
    }
    SideEffectIngestService.IngestResult result =
        ingest.ingest(identityResolver.tenantId(), identityResolver.performedBy(), report);
    Map<String, Object> body200 = new LinkedHashMap<>();
    body200.put("accepted", result.accepted());
    body200.put("duplicates", result.duplicates());
    return Response.ok(body200).build();
  }

  private static Response invalid(Integer index, String field) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", INVALID_SIDE_EFFECT);
    if (index != null) {
      body.put("index", index);
    }
    if (field != null) {
      body.put("field", field);
    }
    return Response.status(Response.Status.BAD_REQUEST).entity(body).type(MediaType.APPLICATION_JSON).build();
  }
}
