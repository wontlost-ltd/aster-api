package io.aster.policy.rest;

import aster.core.typecheck.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.common.JacksonMappers;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.export.EntryRequiredException;
import io.aster.policy.export.ExportDiagnostic;
import io.aster.policy.export.ExportRejectedException;
import io.aster.policy.export.PackageAssembler;
import io.aster.policy.export.PackageExportCheck;
import io.aster.policy.export.PackageExportService;
import io.aster.policy.export.PackageExportUnavailableException;
import io.aster.policy.export.PackageLayoutGate;
import io.aster.policy.export.PackagePrepared;
import io.aster.policy.export.PackageTooLargeException;
import io.aster.policy.parser.CompileIdentity;
import io.aster.policy.rest.model.CnlSourceLimits;
import io.aster.policy.rest.model.CompileDiagnostic;
import io.aster.policy.rest.model.PackageExportCheckResponse;
import io.aster.policy.rest.model.PackageExportPrepareResponse;
import io.aster.policy.rest.model.PackageExportRequest;
import io.aster.policy.rest.model.VocabularyBounds;
import io.aster.replay.core.ReplayExecutionCore;
import io.aster.security.apikey.InternalCallerFilter;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * B 形态策略包导出端点（ADR 0059 P2，修订 3 P2-R8 / P2-R10 / P2-R11）：{@code POST …/check}（编译 + 静态检查，两个 target 的结论）、
 * {@code POST …/prepare}（另加探针与平台期望值，供 cloud 做 Node 侧比对）、{@code POST …}（组装、签名、审计，返回 zip）。
 *
 * <p>前置拒绝顺序：开关关（{@code aster.export.package.enabled}，缺省 false）→ 404；不是 HMAC 已验签的 cloud 内部调用 →
 * 403 {@code export_requires_cloud}（API key 不是有效通道，计划门只在 cloud）；入参 → 400；别名集与词汇表上限 → 400；
 * 进程设置了 {@code ASTER_TRUFFLE_FUNC}（会改写 Truffle 的入口选择，探针期望值不可信）→ 503 {@code export_runtime_misconfigured}。
 *
 * <p>{@code prepare} 与导出共用 {@link PackageExportGate}（不等待，占满 503 {@code package_export_busy}）；{@code check} 不占许可、
 * 不写审计。导出在内存中组装完整 zip、签名并同步入链 {@code POLICY_EXPORTED} 之后才返回；签名被拒在组装前 503、不写审计；
 * 入链失败 500 {@code audit_unavailable}，不返回 zip。
 *
 * <p>显示排版（ADR 0059 修订 4 D11–D12）：导出请求可带 {@code layout}（&gt; 49 152 字节 400 {@code layout_too_large}），经
 * {@link io.aster.policy.export.PackageLayoutGate} 判定是否随包；结论写入响应头 {@code X-Aster-Package-Layout}
 * （{@code included | absent | omitted}，省略时另有 {@code X-Aster-Package-Layout-Reason}）与审计的 {@code layoutSha256}、
 * {@code layoutOutcome}。排版问题只省略排版，不拒绝导出。
 */
@Path(PackageExportResource.PATH)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PackageExportResource {
  static final String PATH = "/api/v1/policies/export/package";
  static final String TRUFFLE_FUNC_ENV = "ASTER_TRUFFLE_FUNC";
  private static final Logger LOG = Logger.getLogger(PackageExportResource.class);
  private static final Set<String> TARGETS = Set.of("jre", "node");
  private static final Set<String> UI_LOCALES = Set.of("en", "zh", "de", "hi");
  private static final Pattern CLAIMED_REF_TEXT = Pattern.compile("[A-Za-z0-9._:/-]{1,128}");
  private static final String DEFAULT_LOCALE = "en-US";
  static final String LAYOUT_HEADER = "X-Aster-Package-Layout";
  static final String LAYOUT_REASON_HEADER = "X-Aster-Package-Layout-Reason";

  @ConfigProperty(name = "aster.export.package.enabled", defaultValue = "false")
  boolean enabled;

  @Inject PackageExportService service;
  @Inject PackageExportGate gate;
  @Inject AuditChainAppender appender;
  @Inject RequestIdentityResolver identityResolver;

  /** 测试钩子：非 null 时覆盖开关配置（避免为「开关关」单独重启 Quarkus）。 */
  static volatile Boolean enabledForTest;
  /** 测试钩子：取得许可后、进入 prepare 之前执行，用于构造确定的并发占用。 */
  static volatile Runnable afterPermit = () -> { };
  /** 测试钩子：进程环境是否设置了 {@code ASTER_TRUFFLE_FUNC}。 */
  static volatile java.util.function.BooleanSupplier truffleFuncSet = () -> {
    String v = System.getenv(TRUFFLE_FUNC_ENV);
    return v != null && !v.isBlank();
  };

  @POST
  @Path("check")
  @Blocking
  public Response check(PackageExportRequest request, @Context ContainerRequestContext ctx) {
    Response rejected = reject(request, ctx);
    if (rejected != null) {
      return rejected;
    }
    PackageExportCheck c = service.check(spec(request));
    Map<String, PackageExportCheckResponse.TargetCheck> targets = new TreeMap<>();
    c.targets().forEach((t, r) -> targets.put(t,
        new PackageExportCheckResponse.TargetCheck(r.exportable(), diagnostics(r.diagnostics()), r.unavailable())));
    return Response.ok(new PackageExportCheckResponse(targets, c.defaultEntry(), c.entryCandidates())).build();
  }

  @POST
  @Path("prepare")
  @Blocking
  public Response prepare(PackageExportRequest request, @Context ContainerRequestContext ctx) {
    Response rejected = reject(request, ctx);
    if (rejected != null) {
      return rejected;
    }
    PackageExportService.ExportSpec spec = spec(request);
    return withPermit(() -> prepared(spec, p -> Response.ok(prepareResponse(p)).build()));
  }

  @POST
  @Blocking
  public Response export(PackageExportRequest request, @Context ContainerRequestContext ctx) {
    Response rejected = reject(request, ctx);
    if (rejected != null) {
      return rejected;
    }
    boolean node = "node".equals(request.target());
    if (!node && request.nodeProbes() != null) {
      return error(400, "node_probes_unexpected", "nodeProbes is only accepted for target=node");
    }
    if (node && request.nodeProbes() == null) {
      return error(400, "node_check_incomplete", "target=node requires nodeProbes from the Node-side check");
    }
    if (request.layout() != null
        && request.layout().getBytes(StandardCharsets.UTF_8).length > PackageLayoutGate.MAX_LAYOUT_BYTES) {
      return error(400, "layout_too_large", "layout exceeds " + PackageLayoutGate.MAX_LAYOUT_BYTES + " bytes");
    }
    PackageExportService.ExportSpec spec = spec(request);
    return withPermit(() -> prepared(spec, p -> exportPrepared(spec, p, request.nodeProbes(), request.layout())));
  }

  private Response withPermit(Supplier<Response> body) {
    if (!gate.tryAcquire()) {
      LOG.warnf("策略包导出拒绝：并发达到上限 %d，返回 503", gate.limit());
      return Response.status(503).header("Retry-After", "1").type(MediaType.APPLICATION_JSON)
          .entity(Map.of("error", "package_export_busy",
              "message", "Too many concurrent package exports. Retry in a moment.",
              "concurrencyLimit", gate.limit()))
          .build();
    }
    try {
      afterPermit.run();
      return body.get();
    } finally {
      gate.release();
    }
  }

  /** 跑 prepare 并把 503 / 400 / 不可导出统一转为响应；可导出时交给 {@code then}。 */
  private Response prepared(PackageExportService.ExportSpec spec, java.util.function.Function<PackagePrepared, Response> then) {
    PackagePrepared p;
    try {
      p = service.prepare(spec);
    } catch (PackageExportUnavailableException e) {
      return error(503, e.code(), "package export is not available for target " + spec.target());
    } catch (EntryRequiredException e) {
      return Response.status(400).type(MediaType.APPLICATION_JSON).entity(Map.of("error", "entry_required",
          "message", "choose an entry rule", "entryCandidates", e.candidates())).build();
    }
    if (!p.exportable()) {
      return notExportable(p.diagnostics());
    }
    return then.apply(p);
  }

  private Response exportPrepared(PackageExportService.ExportSpec spec, PackagePrepared p,
                                  PackageExportRequest.NodeProbes nodeProbes, String layout) {
    PackageAssembler.NodeCheck nodeCheck = null;
    if ("node".equals(spec.target())) {
      Response invalid = validateNodeProbes(nodeProbes, p);
      if (invalid != null) {
        return invalid;
      }
      nodeCheck = new PackageAssembler.NodeCheck(nodeProbes.tsVersion(), nodeProbes.count());
    }
    PackageAssembler.AssembledPackage pkg;
    try {
      pkg = service.export(spec, p, nodeCheck, layout);
    } catch (PackageExportUnavailableException e) {
      return error(503, e.code(), "package signing is not available");
    } catch (PackageTooLargeException e) {
      return notExportable(List.of(ExportDiagnostic.of(ErrorCode.EXPORT_SIZE_EXCEEDED, null, null,
          Map.of("size", Long.toString(e.size()), "limit", Long.toString(e.limit())))));
    } catch (ExportRejectedException e) {
      return notExportable(e.diagnostics());
    }
    audit(spec, p, pkg, nodeCheck, layout);
    Response.ResponseBuilder ok = Response.ok(pkg.zip(), "application/zip")
        .header("Content-Disposition", contentDisposition(pkg.rootName()))
        .header("X-Aster-Package-Sha256", pkg.zipSha256())
        .header("X-Aster-Package-Signed", Boolean.toString(pkg.kid() != null))
        .header(LAYOUT_HEADER, pkg.layout().status())
        .header("Cache-Control", "private, no-store");
    if (pkg.layout().reason() != null) {
      ok.header(LAYOUT_REASON_HEADER, pkg.layout().reason());
    }
    return ok.build();
  }

  /** Review Focus 5：cloud 提交的 Node 侧结论必须与平台重算的探针一致，否则不签名。 */
  static Response validateNodeProbes(PackageExportRequest.NodeProbes n, PackagePrepared p) {
    if (n.probesSha256() == null || !n.probesSha256().equals(p.probes().sha256())) {
      return error(400, "node_probes_mismatch", "probesSha256 does not match the platform probes");
    }
    if (n.count() == null || n.agreed() == null || n.count() != p.probes().count() || !n.agreed().equals(n.count())) {
      return error(400, "node_check_incomplete", "every platform probe must agree on Node");
    }
    if (n.tsVersion() == null || !n.tsVersion().equals(p.runtime().cliVersion())) {
      return error(400, "node_ts_version_mismatch", "tsVersion does not match the locked Node CLI version");
    }
    return null;
  }

  private Response reject(PackageExportRequest request, ContainerRequestContext ctx) {
    if (!(enabledForTest != null ? enabledForTest : enabled)) {
      return error(404, "not_found", "package export is disabled");
    }
    if (!InternalCallerFilter.isHmacVerified(ctx)) {
      return error(403, "export_requires_cloud", "package export is only available through aster-cloud");
    }
    String[] invalid = validate(request);
    if (invalid != null) {
      return error(400, invalid[0], invalid[1]);
    }
    String aliasError = PolicyEvaluationResource.validateAliasSetBounds(request.aliasSet());
    if (aliasError != null) {
      return error(400, "alias_set_too_large", aliasError);
    }
    String vocabularyError = VocabularyBounds.validate(request.vocabulary());
    if (vocabularyError != null) {
      return error(400, VocabularyBounds.ERROR_CODE, vocabularyError);
    }
    if (truffleFuncSet.getAsBoolean()) {
      return error(503, "export_runtime_misconfigured", TRUFFLE_FUNC_ENV + " is set in the aster-api process");
    }
    return null;
  }

  /** 入参校验；返回 {错误码, 说明} 或 null。 */
  static String[] validate(PackageExportRequest r) {
    if (r == null || r.source() == null || r.source().isBlank()) {
      return new String[] {"source_required", "source is required"};
    }
    if (r.source().length() > CnlSourceLimits.MAX_ANON_SOURCE_LENGTH) {
      return new String[] {"source_too_large", "source too large"};
    }
    if (r.target() == null || !TARGETS.contains(r.target())) {
      return new String[] {"target_invalid", "target must be node or jre"};
    }
    if (!"b".equals(r.mode())) {
      return new String[] {"mode_unsupported", "only mode b is supported"};
    }
    if (r.uiLocale() != null && !UI_LOCALES.contains(r.uiLocale())) {
      return new String[] {"ui_locale_invalid", "uiLocale must be one of en, zh, de, hi"};
    }
    return claimedRefValid(r.claimedRef()) ? null : new String[] {"claimed_ref_invalid", "claimedRef is invalid"};
  }

  static boolean claimedRefValid(PackageExportRequest.ClaimedRefBody c) {
    if (c == null || c.version() == null || c.version() < 1) {
      return false;
    }
    if (!identifier(c.policyId()) || !identifier(c.status())) {
      return false;
    }
    if (c.approvedAt() == null) {
      return true;
    }
    try {
      OffsetDateTime.parse(c.approvedAt());
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static boolean identifier(String s) {
    return s != null && CLAIMED_REF_TEXT.matcher(s).matches();
  }

  private PackageExportService.ExportSpec spec(PackageExportRequest r) {
    PackageExportRequest.ClaimedRefBody c = r.claimedRef();
    String locale = r.locale() == null || r.locale().isBlank() ? DEFAULT_LOCALE : r.locale();
    return new PackageExportService.ExportSpec(r.source(), locale, r.aliasSet(), Boolean.TRUE.equals(r.allowStructural()),
        r.vocabulary(), r.entry(), r.target(), r.uiLocale() == null ? "en" : r.uiLocale(),
        new PackageAssembler.ClaimedRef(c.policyId(), c.version(), c.status(), c.approvedAt()),
        identityResolver.tenantId());
  }

  private static PackageExportPrepareResponse prepareResponse(PackagePrepared p) {
    JsonNode probes;
    try {
      probes = JacksonMappers.DEFAULT.readTree(p.probes().json());
    } catch (Exception e) {
      throw new IllegalStateException("探针文件不是合法 JSON", e);
    }
    return new PackageExportPrepareResponse(true, List.of(), p.entry(), p.coreJson(), p.coreIrSha256(),
        p.positionFreeSha256(), probes, p.probes().sha256(), p.probes().count(),
        new PackageExportPrepareResponse.Runtime(p.runtime().cliName(), p.runtime().cliVersion(), p.runtime().ecosystem()));
  }

  private static Response notExportable(List<ExportDiagnostic> diagnostics) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("exportable", false);
    body.put("diagnostics", diagnostics(diagnostics));
    return Response.ok(body).build();
  }

  private static List<CompileDiagnostic> diagnostics(List<ExportDiagnostic> ds) {
    return ds.stream().map(ExportDiagnostic::toCompileDiagnostic).toList();
  }

  /** 同步入链；失败即整次请求失败（500），zip 不返回。 */
  private void audit(PackageExportService.ExportSpec spec, PackagePrepared p, PackageAssembler.AssembledPackage pkg,
                     PackageAssembler.NodeCheck nodeCheck, String layout) {
    Map<String, Object> metadata = auditMetadata(spec, p, pkg, nodeCheck, layout);
    String module = pkg.rootName().substring(0, pkg.rootName().lastIndexOf("-v"));
    AuditEvent event = new AuditEvent(EventType.POLICY_EXPORTED, java.time.Instant.now(), spec.tenantId(),
        module, p.entry(), null, null, null, identityResolver.performedBy(), true,
        null, null, metadata, null, null, null, null);
    try {
      appender.appendSync(event);
    } catch (AuditChainAppender.AuditAppendException e) {
      LOG.errorf(e, "策略包导出审计事件写入失败，zip 不返回: tenant=%s target=%s", spec.tenantId(), spec.target());
      throw new jakarta.ws.rs.InternalServerErrorException(error(500, "audit_unavailable", "audit chain unavailable"));
    }
  }

  /**
   * {@code POLICY_EXPORTED} 的元数据。{@code layoutSha256} 为 cloud 送来的排版文本的 sha256（未送为 null），与
   * {@code layoutOutcome}（{@code included} / {@code absent} / {@code omitted:<reason>}）一起说明排版是否进了包。
   */
  static Map<String, Object> auditMetadata(PackageExportService.ExportSpec spec, PackagePrepared p,
                                           PackageAssembler.AssembledPackage pkg, PackageAssembler.NodeCheck nodeCheck,
                                           String layout) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("target", "package:" + spec.target() + "-b");
    m.put("manifestSha256", pkg.manifestSha256());
    m.put("zipSha256", pkg.zipSha256());
    m.put("coreIrSha256", p.coreIrSha256());
    m.put("positionFreeSha256", p.positionFreeSha256());
    m.put("cliVersion", p.runtime().cliVersion());
    m.put("probeCount", p.probes().count());
    m.put("nodeCheck", nodeCheck == null ? null
        : "{\"agreed\":" + nodeCheck.agreed() + ",\"tsVersion\":" + jsonString(nodeCheck.tsVersion()) + "}");
    m.put("kid", pkg.kid());
    PackageAssembler.ClaimedRef c = spec.claimedRef();
    m.put("claimedRef", "{\"approvedAt\":" + (c.approvedAt() == null ? "null" : jsonString(c.approvedAt()))
        + ",\"policyId\":" + jsonString(c.policyId()) + ",\"status\":" + jsonString(c.status())
        + ",\"version\":" + c.version() + "}");
    m.put("sourceHash", sha256(spec.source()));
    m.put("scopesHash", CompileIdentity.scopesHash(ReplayExecutionCore.buildVocabularyScopes(spec.vocabulary())));
    m.put("layoutSha256", layout == null ? null : sha256(layout));
    m.put("layoutOutcome", pkg.layout().auditValue());
    return m;
  }

  private static String jsonString(String s) {
    try {
      return JacksonMappers.DEFAULT.writeValueAsString(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** ASCII 回退名（非 {@code [A-Za-z0-9._-]} 换成 {@code _}）加 RFC 5987 的 UTF-8 百分号编码名。 */
  static String contentDisposition(String rootName) {
    String ascii = rootName.replaceAll("[^A-Za-z0-9._-]", "_");
    StringBuilder encoded = new StringBuilder();
    for (byte b : (rootName + ".zip").getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') {
        encoded.append((char) c);
      } else {
        encoded.append('%').append(String.format("%02X", c));
      }
    }
    return "attachment; filename=\"" + ascii + ".zip\"; filename*=UTF-8''" + encoded;
  }

  private static Response error(int status, String code, String message) {
    return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", code, "message", message))
        .build();
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
