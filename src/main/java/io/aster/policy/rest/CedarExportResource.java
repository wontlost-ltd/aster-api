package io.aster.policy.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.common.JacksonMappers;
import io.aster.policy.cedar.CedarExportOutcome;
import io.aster.policy.cedar.CedarExportService;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.rest.model.CedarExportRequest;
import io.aster.policy.rest.model.CedarExportResponse;
import io.aster.policy.rest.model.CnlSourceLimits;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.aster.security.apikey.InternalCallerFilter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cedar 导出端点（ADR 0047 §6）：把一条 Aster 规则翻译为 Cedar 策略与 schema，经严格校验与逐探针等价性
 * 比对后返回。
 *
 * <p>鉴权为双通道：aster-cloud 走 HMAC 内部调用，其它调用方走 Bearer API key，二者皆无则由过滤器拒绝；
 * 租户与操作者一律取自 {@link RequestIdentityResolver} 的已验证身份。
 *
 * <p>成功导出须先同步写入 {@code POLICY_EXPORTED} 审计事件再返回；入链失败则整次请求失败，
 * 不允许出现未留痕的导出。本方法跑在 worker 线程且不带事务，入链由追加器自开独立事务。
 */
@Path("/api/v1/policies/export/cedar")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class CedarExportResource {

    private static final Logger LOG = Logger.getLogger(CedarExportResource.class);

    static final int MAX_CLAIMED_REF_ENTRIES = 4;
    static final int MAX_CLAIMED_REF_TEXT = 128;
    private static final String DEFAULT_LOCALE = "en-US";
    /** claimedRef 键值只允许标识符字符：控制字符经转义会膨胀，可能把审计元数据挤过追加器上限而被截断。 */
    private static final Pattern CLAIMED_REF_TEXT = Pattern.compile("[A-Za-z0-9._:/-]{1,128}");
    /** ApiKeyAuthFilter 验证 key 后写入的已验证租户属性。 */
    private static final String API_KEY_TENANT_PROP = "aster.apikey.tenantId";

    /** 一次导出的编译输入：源码模式取自请求，版本模式取自已保存版本。 */
    private record ExportInput(String source, String locale, String aliasSetJson, String functionName, Long versionId) {
    }

    @Inject CedarExportService exportService;
    @Inject AuditChainAppender appender;
    @Inject RequestIdentityResolver identityResolver;
    @Inject CedarExportGate gate;

    @POST
    @Blocking
    public Response export(CedarExportRequest request, @Context ContainerRequestContext ctx) {
        // 过滤器可被配置关闭（apikey.enabled=false），端点自身仍要求已验证身份，不退回自述的 X-Tenant-Id
        if (!hasVerifiedIdentity(ctx)) {
            return error(Response.Status.UNAUTHORIZED, "unauthenticated");
        }
        String inputError = validate(request);
        if (inputError != null) {
            return error(Response.Status.BAD_REQUEST, inputError);
        }
        String aliasError = PolicyEvaluationResource.validateAliasSetBounds(request.aliasSet());
        if (aliasError != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "alias_set_too_large", "message", aliasError)).build();
        }
        if (!gate.tryAcquire()) {
            LOG.warnf("Cedar 导出拒绝：并发达到上限 %d，返回 503", gate.limit());
            throw busy(gate.limit());
        }
        try {
            return exportWithPermit(request);
        } finally {
            gate.release();
        }
    }

    private Response exportWithPermit(CedarExportRequest request) {
        String tenantId = identityResolver.tenantId();
        ExportInput input = request.versionId() == null ? fromSource(request) : fromVersion(request.versionId(), tenantId);
        if (input == null) {
            return error(Response.Status.NOT_FOUND, "version not found");
        }
        CedarExportOutcome outcome = exportService.exportSource(
            input.source(), input.locale(), input.aliasSetJson(), input.functionName());
        CedarExportResponse body = toResponse(outcome);
        if (outcome.exportable()) {
            audit(outcome, input.versionId(), request.claimedRef(), tenantId);
        }
        return Response.ok(body).build();
    }

    /** HMAC 内部调用已验签，或 API key 已验证并写入租户属性。 */
    static boolean hasVerifiedIdentity(ContainerRequestContext ctx) {
        if (InternalCallerFilter.isHmacVerified(ctx)) {
            return true;
        }
        return ctx != null && ctx.getProperty(API_KEY_TENANT_PROP) instanceof String tenant && !tenant.isBlank();
    }

    /** 与 /evaluate-source 占满时同一形态：503 + Retry-After，body 带错误码、说明与并发上限。 */
    private static WebApplicationException busy(int limit) {
        return new WebApplicationException(Response.status(503)
            .header("Retry-After", "1")
            .entity(Map.of(
                "error", "cedar_export_busy",
                "message", "Too many concurrent Cedar export requests. Retry in a moment.",
                "concurrencyLimit", limit))
            .type(MediaType.APPLICATION_JSON)
            .build());
    }

    /** 入参约束：source 与 versionId 恰给其一、源码长度与匿名编译同上限、claimedRef 有界。返回错误文本或 null。 */
    static String validate(CedarExportRequest request) {
        boolean hasSource = request != null && request.source() != null && !request.source().isBlank();
        boolean hasVersion = request != null && request.versionId() != null;
        if (hasSource == hasVersion) {
            return "exactly one of source or versionId";
        }
        if (hasSource && request.source().length() > CnlSourceLimits.MAX_ANON_SOURCE_LENGTH) {
            return "source too large";
        }
        if (!claimedRefFits(request.claimedRef())) {
            return "claimedRef too large";
        }
        return claimedRefWellFormed(request.claimedRef()) ? null : "claimedRef invalid";
    }

    private static boolean claimedRefWellFormed(Map<String, String> claimedRef) {
        return claimedRef == null || claimedRef.entrySet().stream()
            .allMatch(e -> identifierText(e.getKey()) && identifierText(e.getValue()));
    }

    private static boolean identifierText(String text) {
        return text != null && CLAIMED_REF_TEXT.matcher(text).matches();
    }

    private static boolean claimedRefFits(Map<String, String> claimedRef) {
        if (claimedRef == null) {
            return true;
        }
        return claimedRef.size() <= MAX_CLAIMED_REF_ENTRIES
            && claimedRef.entrySet().stream().allMatch(e -> textFits(e.getKey()) && textFits(e.getValue()));
    }

    private static boolean textFits(String text) {
        return text == null || text.length() <= MAX_CLAIMED_REF_TEXT;
    }

    private static ExportInput fromSource(CedarExportRequest request) {
        String aliasSetJson = request.aliasSet() == null || request.aliasSet().isEmpty()
            ? null : writeJson(request.aliasSet());
        return new ExportInput(request.source(), request.getLocaleOrDefault(), aliasSetJson,
            request.functionName(), null);
    }

    /** 版本模式：只在本租户内查找，找不到返回 null；编译输入全部取自版本本身。 */
    private static ExportInput fromVersion(Long versionId, String tenantId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            PolicyVersion v = PolicyVersion.findByIdInTenant(versionId, tenantId);
            if (v == null) {
                return null;
            }
            String locale = v.locale == null || v.locale.isBlank() ? DEFAULT_LOCALE : v.locale;
            return new ExportInput(v.content, locale, v.aliasSet, v.functionName, v.id);
        });
    }

    private static CedarExportResponse toResponse(CedarExportOutcome o) {
        JsonNode schema = o.schemaJson() == null ? null : readJson(o.schemaJson());
        return new CedarExportResponse(o.exportable(), o.policies(), schema, o.ruleId(), o.profile(), o.controls(),
            new CedarExportResponse.Probes(o.probeCount(), o.probesAgreed()), o.diagnostics(), o.sourceHash());
    }

    /** 同步入链；失败即整次请求失败（500），导出结果不返回给调用方。 */
    private void audit(CedarExportOutcome o, Long versionId, Map<String, String> claimedRef, String tenantId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("target", "cedar");
        metadata.put("sourceHash", o.sourceHash());
        metadata.put("policiesHash", sha256(o.policies()));
        metadata.put("schemaHash", sha256(o.schemaJson()));
        metadata.put("ruleId", o.ruleId());
        metadata.put("probeCount", o.probeCount());
        if (versionId != null) {
            metadata.put("versionId", versionId);
        }
        if (claimedRef != null && !claimedRef.isEmpty()) {
            metadata.put("claimedRef", writeJson(claimedRef));
        }
        AuditEvent event = new AuditEvent(EventType.POLICY_EXPORTED, Instant.now(), tenantId,
            o.moduleName(), o.functionName(), null, null, versionId, identityResolver.performedBy(), true,
            null, null, metadata, null, null, null, null);
        try {
            appender.appendSync(event);
        } catch (AuditChainAppender.AuditAppendException e) {
            LOG.errorf(e, "Cedar 导出审计事件写入失败，导出结果不返回: tenant=%s rule=%s", tenantId, o.ruleId());
            throw new jakarta.ws.rs.InternalServerErrorException(
                error(Response.Status.INTERNAL_SERVER_ERROR, "audit_unavailable"));
        }
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build();
    }

    private static String writeJson(Object value) {
        try {
            return JacksonMappers.DEFAULT.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    private static JsonNode readJson(String json) {
        try {
            return JacksonMappers.DEFAULT.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Cedar schema 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
