package io.aster.policy.stability;

import aster.core.ir.CoreModel;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.stability.StabilityGate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.compiler.CompilationResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 稳定性门禁服务（ADR 0031 P0-C）——把 core {@link StabilityGate} 接进 aster-api 生产路径。
 *
 * <p>集中「strict 分环境默认」（ADR §3.4）+ Core IR 扫描 + W600 诊断映射 + strict 拒绝，
 * 让各 surface 只声明意图（哪个 surface），不散落 strict 判定：
 * <ul>
 *   <li>dev/playground/裸 compile → warn（不阻断探索）</li>
 *   <li>普通 tenant 保存 → warn；regulated tenant 保存 → strict</li>
 *   <li>激活/审批（activate/approve）→ strict（批准=托付生产，Experimental 不该无声进批准链）</li>
 * </ul>
 *
 * <p>★为何扫 Core IR 而非 typecheck：aster-api 生产路径不走 typecheck，gate 挂 typecheck
 * =服务端看不到=假门禁（ADR §3.2）。这里对 compile 产出的 coreModule / 已存 coreJson
 * （反序列化，堵 §3.7 cache 盲区）扫描。
 */
@ApplicationScoped
public class StabilityEnforcement {

    private static final Logger LOG = Logger.getLogger(StabilityEnforcement.class);

    /**
     * 宽松 mapper：读旧 coreJson 产物做扫描，容忍未知字段（产物结构可能随版本演进，
     * 扫描不应因新增字段而崩）。Jackson 多态由 CoreModel 的 @JsonTypeInfo/@JsonSubTypes 驱动。
     */
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<io.aster.policy.event.AuditEvent> auditEventPublisher;

    /**
     * 受监管租户列表（保存路径 strict）。M2 用配置；后续接真实 tenant profile/plan。
     * ★用 Optional：SmallRye 把 {@code defaultValue=""} 当「无默认」→SRCFG00014 required 缺失
     * 致 app 启动失败（空字符串非有效 String 默认）；Optional 无值时 empty，不 required。
     */
    @ConfigProperty(name = "aster.stability.regulated-tenants")
    java.util.Optional<String> regulatedTenantsConfig;

    /** 测试直接注入用（绕过 Optional 包装）；生产从 regulatedTenantsConfig 读。 */
    String regulatedTenantsCsv;

    private String regulatedTenants() {
        if (regulatedTenantsCsv != null) {
            return regulatedTenantsCsv;
        }
        return regulatedTenantsConfig == null ? "" : regulatedTenantsConfig.orElse("");
    }

    /**
     * Experimental 放行白名单（ADR 0031 §3.4，M3）——**平台控制面**，非 tenant 可改。
     * 语法（CSV）：{@code tenantId:policyId:featureId=approvalRef} / {@code tenantId:*:featureId=ref}
     * / {@code tenantId:featureId=ref}（简写等价 tenantId:*:featureId）。无全局 `*`、无 feature 通配、
     * 无 {@code tenantId:*}（不整体放行 tenant）。approvalRef 记审计「为何放行」。
     * ★安全铁律（Codex 设计）：只授权「某 tenant 在某 policy 用某已批准 featureId」，request body
     * 不得影响匹配；actor 仅审计不参与授权；查失败 fail-closed（当无授权=拒）。
     */
    @ConfigProperty(name = "aster.stability.experimental-allow")
    java.util.Optional<String> experimentalAllowConfig;

    /** 测试直接注入用。 */
    String experimentalAllowCsv;

    private String experimentalAllow() {
        if (experimentalAllowCsv != null) {
            return experimentalAllowCsv;
        }
        return experimentalAllowConfig == null ? "" : experimentalAllowConfig.orElse("");
    }

    /**
     * 查白名单是否放行「tenant 在 policy 用 featureId」。返回匹配的 approvalRef（放行）或 null（不放行）。
     * fail-closed：任何解析异常 → 不放行（return null）。
     */
    String matchExperimentalAllow(String tenantId, String policyId, String featureId) {
        String csv = experimentalAllow();
        if (csv == null || csv.isBlank() || tenantId == null || featureId == null) {
            return null;
        }
        String tp = tenantId + ":" + (policyId == null ? "" : policyId) + ":" + featureId;
        String tw = tenantId + ":*:" + featureId;
        String tf = tenantId + ":" + featureId;
        String wildcardRef = null;
        for (String raw : csv.split("\\s*,\\s*")) {
            if (raw.isBlank()) {
                continue;
            }
            int eq = raw.indexOf('=');
            // ★approvalRef 必填（Codex 审查）：无 '=' 或空 ref = 配置损坏 → 跳过（不放行），
            //   否则 `tenant:p:feature`（漏 ref）会被当作放行，绕过「为何放行」审计要求。
            if (eq <= 0) {
                LOG.warnf("stability 白名单条目缺 approvalRef，忽略（fail-closed）: %s", raw);
                continue;
            }
            String rule = raw.substring(0, eq);
            String ref = raw.substring(eq + 1).trim();
            if (ref.isEmpty()) {
                LOG.warnf("stability 白名单条目 approvalRef 为空，忽略（fail-closed）: %s", raw);
                continue;
            }
            // 精确 tenantId:policyId:featureId 优先命中直接返回。
            if (rule.equals(tp)) {
                return ref;
            }
            if (rule.equals(tw) || rule.equals(tf)) {
                wildcardRef = ref;
            }
        }
        return wildcardRef;
    }

    /** enforcement surface —— 决定 strict 默认。 */
    public enum Surface {
        COMPILE_PLAYGROUND,
        SAVE,
        APPROVE,
        ACTIVATE
    }

    /** 扫描结果：诊断 + 是否被 strict 拒。 */
    public record Result(List<CompilationResult.Diagnostic> diagnostics, boolean blocked) {}

    /** 某 surface + tenant 是否 strict（ADR §3.4）。 */
    public boolean strictFor(Surface surface, String tenantId) {
        return switch (surface) {
            case APPROVE, ACTIVATE -> true;
            case SAVE -> isRegulatedTenant(tenantId);
            case COMPILE_PLAYGROUND -> false;
        };
    }

    private boolean isRegulatedTenant(String tenantId) {
        String csv = regulatedTenants();
        if (tenantId == null || csv == null || csv.isBlank()) {
            return false;
        }
        Set<String> regulated = Set.of(csv.split("\\s*,\\s*"));
        return regulated.contains(tenantId);
    }

    /** 扫已 lower 的 coreModule（compile(source) 路径）。 */
    public Result scan(CoreModel.Module coreModule, boolean strict) {
        List<StabilityGate.Diagnostic> diags = StabilityGate.scan(
            coreModule, new StabilityGate.Options(strict, false));
        return toResult(diags, strict);
    }

    /**
     * strict 门禁因「无法判断稳定性」被拒时的哨兵结果（fail-closed）。
     * ★warn-mode 可放行（不阻断探索）；strict-mode 必须 fail-closed（Codex 审查 L3：
     * strict 把「无法判断」当「稳定」= 漏报 Experimental 进生产）。
     */
    public static final class UnscannableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnscannableException(String message) {
            super(message);
        }
    }

    /**
     * 扫已存 coreJson 产物（★compile(versionId) cache / approve / activate 路径，堵 ADR §3.7 盲区）。
     * 反序列化 coreJson → CoreModel 再扫，不 re-lower（re-lower 会重解释旧版本，违背冻结产物语义）。
     *
     * <p>★fail 语义按 strict 分（Codex 审查 L3）：coreJson 空/损坏时——warn-mode 放行（返回空），
     * strict-mode 抛 {@link UnscannableException}（fail-closed，不把「无法判断」当「稳定」）。
     */
    public Result scanCoreJson(String coreJson, boolean strict) {
        if (coreJson == null || coreJson.isBlank()) {
            if (strict) {
                throw new UnscannableException("版本无 Core IR（coreJson 空），strict 门禁无法确认稳定性，拒绝放行");
            }
            return new Result(List.of(), false);
        }
        CoreModel.Module module;
        try {
            module = LENIENT_MAPPER.readValue(coreJson, CoreModel.Module.class);
        } catch (Exception e) {
            if (strict) {
                throw new UnscannableException("Core IR 无法反序列化（strict 门禁 fail-closed）: " + e.getMessage());
            }
            LOG.warnf("stability 扫描无法反序列化 coreJson（warn-mode 跳过 stability 检查）: %s", e.getMessage());
            return new Result(List.of(), false);
        }
        return scan(module, strict);
    }

    private Result toResult(List<StabilityGate.Diagnostic> diags, boolean strict) {
        List<CompilationResult.Diagnostic> mapped = new ArrayList<>(diags.size());
        for (StabilityGate.Diagnostic d : diags) {
            mapped.add(toCompilationDiagnostic(d));
        }
        boolean blocked = StabilityGate.shouldReject(diags, strict);
        return new Result(mapped, blocked);
    }

    /** W600 → CompilationResult.Diagnostic（severity 恒 warning，即使 strict 拒）。 */
    private static CompilationResult.Diagnostic toCompilationDiagnostic(StabilityGate.Diagnostic d) {
        int line = 1;
        int col = 1;
        int endLine = 1;
        int endCol = 1;
        if (d.origin() != null) {
            if (d.origin().start != null) {
                line = d.origin().start.line;
                col = d.origin().start.col;
            }
            if (d.origin().end != null) {
                endLine = d.origin().end.line;
                endCol = d.origin().end.col;
            } else {
                endLine = line;
                endCol = col;
            }
        }
        String message = "Experimental feature '" + d.featureId().id()
            + "' is not part of the stable surface (1.x 内语义可能变更，不进兼容承诺)";
        return new CompilationResult.Diagnostic(
            line, col, endLine, endCol, message,
            StabilityGate.STABILITY_EXPERIMENTAL_CODE, "warning",
            d.featureId().id(), d.nodeKind(), d.blocking());
    }

    /**
     * 解析版本的 Core IR 用于扫描：优先已存 coreJson（冻结产物）；空时从 content 源码
     * 现编译（★createVersion 不填 coreJson，approve/activate 时常为空——Codex 审查 L1 P0：
     * 只读 coreJson 会漏报）。strict 下无法得到可扫 IR 则抛 UnscannableException（fail-closed）。
     */
    public Result resolveAndScan(String coreJson, String content, String locale, String aliasSetJson, boolean strict) {
        if (coreJson != null && !coreJson.isBlank()) {
            return scanCoreJson(coreJson, strict);
        }
        // coreJson 空 → 从 content 现编译到 Core IR（★带版本冻结 aliasSet，Codex 复审：
        // 依赖别名的版本用无别名重解释会编译失败，误 fail-closed 拒正常 Stable 版本）。
        if (content == null || content.isBlank()) {
            if (strict) {
                throw new UnscannableException("版本无 coreJson 也无 content，strict 门禁无法确认稳定性，拒绝放行");
            }
            return new Result(List.of(), false);
        }
        CoreModel.Module module;
        try {
            module = compileToCore(content, locale, aliasSetJson);
        } catch (Exception e) {
            // ★content 编译失败 ≠ Experimental：这是编译错误（非 CNL/语法错），是编译校验的
            //   职责，非 stability gate 的。且 Experimental 特性（Workflow/PII/@deprecated）都是
            //   合法 CNL 能 lower——只有非 CNL/语法错才编译失败，那种版本本就无法通过编译校验
            //   激活。故 stability 对「编译失败」放行（不 fail-closed），交编译校验报错。
            //   （区别于 coreJson 腐化=产物异常仍 fail-closed。）
            LOG.warnf("stability 扫描无法编译 content（非 stability 职责，放行交编译校验）: %s", e.getMessage());
            return new Result(List.of(), false);
        }
        return scan(module, strict);
    }

    /**
     * 源码 → CoreModel（带版本冻结 aliasSet，复用 InProcessCnlParser 的 alias-aware parse，
     * 与 PolicyCompiler.compile 同口径——否则依赖别名的版本会因无别名重解释而编译失败）。
     */
    private static CoreModel.Module compileToCore(String source, String locale, String aliasSetJson) {
        Map<SemanticTokenKind, List<String>> aliasSet = io.aster.policy.compiler.PolicyCompiler.parseAliasSetJson(aliasSetJson);
        io.aster.policy.parser.InProcessCnlParser.ParseResult parseResult =
            (aliasSet == null || aliasSet.isEmpty())
                ? io.aster.policy.parser.InProcessCnlParser.parse(source, locale)
                : io.aster.policy.parser.InProcessCnlParser.parseWithUserAliases(source, locale, null, aliasSet);
        return parseResult.core();
    }

    public void enforceVersion(Long versionId, String policyId, String tenantId,
                               String coreJson, String content, String locale, String aliasSetJson,
                               Surface surface, String actor) {
        boolean strict = strictFor(surface, tenantId);
        Result result;
        try {
            result = resolveAndScan(coreJson, content, locale, aliasSetJson, strict);
        } catch (UnscannableException e) {
            // ★strict fail-closed：无法确认稳定性 → 拒（不放行）。message 直接进 422（GlobalExceptionMapper
            // 保留 exception.getMessage()，Codex 审查 L4：entity 会被丢弃，故关键信息放 message）。
            LOG.warnf("stability gate fail-closed 拒绝 %s：versionId=%d policyId=%s actor=%s reason=%s",
                surface, versionId, policyId, actor, e.getMessage());
            throw new WebApplicationException(
                "stability_unscannable: " + e.getMessage(), 422);
        }
        if (!result.blocked()) {
            return;
        }
        // ★scan-then-filter（ADR 0031 §3.4）：每个 W600 featureId 独立查白名单——命中放行（审计
        //   STABILITY_EXCEPTION_USED），未授权仍拒。整版含多 Experimental 时，只放行命中的、
        //   其余仍 422（不整体放行）。
        List<String> allFeatures = result.diagnostics().stream()
            .map(CompilationResult.Diagnostic::featureId)
            .filter(f -> f != null)
            .distinct()
            .toList();
        // 防御性不变式（Codex 审查）：blocked 但无可辨识 featureId → 拒（不走白名单放行分支，
        // 否则 allFeatures 空时 deniedFeatures 也空会误放行一个「被 block 却无 feature」的异常态）。
        if (allFeatures.isEmpty()) {
            throw new WebApplicationException(
                "stability_experimental_blocked: blocked with no identifiable feature（异常态，拒绝）", 422);
        }
        List<String> allowedFeatures = new ArrayList<>();
        List<String> allowRules = new ArrayList<>();
        List<String> deniedFeatures = new ArrayList<>();
        for (String featureId : allFeatures) {
            String approvalRef = matchExperimentalAllow(tenantId, policyId, featureId);
            if (approvalRef != null) {
                allowedFeatures.add(featureId);
                allowRules.add(featureId + "=" + approvalRef);
            } else {
                deniedFeatures.add(featureId);
            }
        }

        if (deniedFeatures.isEmpty()) {
            // 全部经白名单放行 → 审计放行，不抛。
            audit(EventTypeUsed(), tenantId, policyId, versionId, actor, true, null,
                java.util.Map.of("surface", surface.name(), "allowedFeatureIds", allowedFeatures,
                    "allowRules", allowRules, "strict", strict));
            LOG.infof("stability gate 白名单放行 %s：versionId=%d policyId=%s tenant=%s features=%s rules=%s",
                surface, versionId, policyId, tenantId, allowedFeatures, allowRules);
            return;
        }

        // 有未授权 Experimental → 拒 422（审计留痕，含 denied/authorized 区分）。
        audit(EventTypeDenied(), tenantId, policyId, versionId, actor, false,
            "stability_experimental_blocked",
            java.util.Map.of("surface", surface.name(), "deniedFeatureIds", deniedFeatures,
                "authorizedFeatureIds", allowedFeatures, "strict", strict));
        LOG.warnf("stability gate 拒绝 %s：versionId=%d policyId=%s tenant=%s actor=%s denied=%s authorized=%s",
            surface, versionId, policyId, tenantId, actor, deniedFeatures, allowedFeatures);
        throw new WebApplicationException(
            "stability_experimental_blocked: Experimental features " + deniedFeatures
                + " are not allowed for " + surface.name().toLowerCase()
                + "（如需使用请申请 aster.stability.experimental-allow 白名单授权）",
            422);
    }

    private static io.aster.policy.event.EventType EventTypeUsed() {
        return io.aster.policy.event.EventType.STABILITY_EXCEPTION_USED;
    }

    private static io.aster.policy.event.EventType EventTypeDenied() {
        return io.aster.policy.event.EventType.STABILITY_EXCEPTION_DENIED;
    }

    /** 发稳定性审计事件（放行/拒绝留痕）。审计失败不阻断主流程（记 error log）。 */
    private void audit(io.aster.policy.event.EventType type, String tenantId, String policyId,
                       Long versionId, String actor, boolean success, String errorMessage,
                       java.util.Map<String, Object> metadata) {
        try {
            String module = null;
            String function = null;
            if (policyId != null && policyId.contains(".")) {
                int dot = policyId.lastIndexOf('.');
                module = policyId.substring(0, dot);
                function = policyId.substring(dot + 1);
            }
            io.aster.policy.event.AuditEvent event = new io.aster.policy.event.AuditEvent(
                type, java.time.Instant.now(), tenantId, module, function, policyId,
                null, versionId, actor, success, null, errorMessage, metadata,
                null, null, null, null);
            auditEventPublisher.fireAsync(event);
        } catch (Exception e) {
            LOG.errorf("stability 审计事件发布失败（不阻断）: type=%s policyId=%s err=%s", type, policyId, e.getMessage());
        }
    }
}
