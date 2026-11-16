package io.aster.policy.cedar;

import aster.core.cedar.CedarExport;
import aster.core.cedar.CedarExporter;
import aster.core.cedar.GuardShape;
import aster.core.cedar.Probe;
import aster.core.cedar.ProbeGenerator;
import aster.core.ir.CoreIrFormatException;
import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.aster.common.JacksonMappers;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.governance.VerdictView;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.DynamicCnlExecutor.DynamicExecutionException;
import io.aster.policy.rest.model.CompileDiagnostic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Cedar 导出服务（ADR 0047 §5）：编译 → 核心导出 → Cedar 严格校验（E724）→ 逐探针比对 Aster 与 Cedar（E723）。
 * {@link #check} 只走前两步，供页面判断是否提供导出（ADR 0047 §6）。
 *
 * <p>等价性是整个导出的保证：只有全部探针都满足各自的一致性规则，导出才可用。数值越界探针（{@link Probe#outOfDomain()}）
 * 只要求 Cedar 不比 Aster 更松；其余探针要求结论、原因、审批人逐项一致。探针按生成顺序比对，首个不一致写入 E723。
 */
@ApplicationScoped
public class CedarExportService {

    /** 单次导出比对的探针上限（ADR 0047 §5 初值）。 */
    static final int MAX_PROBES = 512;

    private static final int THROW_DETAIL_MAX = 120;
    private static final String ALLOW = "ALLOW";

    private final PolicyCompiler compiler;
    private final CedarEngine cedar;

    @Inject
    public CedarExportService(PolicyCompiler compiler, CedarEngine cedar) {
        this.compiler = compiler;
        this.cedar = cedar;
    }

    /** Aster 侧一次求值：正常时 outcome 为大写结论；抛错时 thrown 非空。 */
    private record AsterSide(String outcome, String reason, String role, String thrown) {
        String summary() {
            return thrown == null ? outcome : "THROW:" + thrown;
        }
    }

    /** 首个不一致的探针及两侧摘要。 */
    private record Mismatch(Probe probe, AsterSide aster, CedarEngine.Decision cedar) {
    }

    /**
     * 编译源码并导出。导出基于名称解析（ADR 0051 R2）之后的 Core IR，规则、类型、字段均为规范名。
     *
     * @param scopes 领域词汇作用域，null 表示无词汇
     */
    public CedarExportOutcome exportSource(String source, String locale, String aliasSetJson,
                                           aster.core.identifier.VocabularyScopes scopes, String functionName) {
        return exportSource(source, locale, aliasSetJson, scopes, functionName, false);
    }

    /**
     * 同 {@link #exportSource(String, String, String, aster.core.identifier.VocabularyScopes, String)}，
     * 但可指定结构词别名是否按已授权编译。
     *
     * @param allowStructural 必须来自权威信任判定（HMAC 已验签的内部调用方显式声明），语义同
     *                        {@link PolicyCompiler#compile(String, String, String, aster.core.identifier.VocabularyScopes, boolean)}
     */
    public CedarExportOutcome exportSource(String source, String locale, String aliasSetJson,
                                           aster.core.identifier.VocabularyScopes scopes, String functionName,
                                           boolean allowStructural) {
        Prepared p = prepare(source, locale, aliasSetJson, scopes, functionName, allowStructural);
        if (!p.errors().isEmpty()) {
            return new CedarExportOutcome(false, null, functionName, null, null, null, null, List.of(), 0, 0,
                p.sourceHash(), p.errors());
        }
        CoreModel.Module module = p.module();
        if (p.candidates().size() != 1) {
            return new CedarExportOutcome(false, null, null, module.name, null, null, module.profile, List.of(), 0, 0,
                p.sourceHash(), List.of(functionRequired(module, p.candidates())));
        }
        return verify(CedarExporter.export(module, p.candidates().get(0), p.sourceHash()), p.coreJson(),
            p.sourceHash(), module.name);
    }

    /**
     * 可导出检查（aster-cloud#786）：与 {@link #exportSource} 同一编译与候选规则选择，再对每个候选做核心翻译。
     *
     * <p>只是必要条件检查：不做 Cedar 严格校验（E724）与逐探针比对（E723），不写审计，代价与一次编译同级。
     * E723/E724 属于编译器缺陷信号，只有真实导出才能发现，因此检查通过不保证导出一定成功，真实导出仍是唯一权威。
     * 多个候选都能翻译时全部列出；真实导出届时会要求调用方指定其一（E720）。
     *
     * @param allowStructural 语义同 {@link #exportSource(String, String, String, aster.core.identifier.VocabularyScopes, String, boolean)}
     */
    public CedarExportCheck check(String source, String locale, String aliasSetJson,
                                  aster.core.identifier.VocabularyScopes scopes, String functionName,
                                  boolean allowStructural) {
        Prepared p = prepare(source, locale, aliasSetJson, scopes, functionName, allowStructural);
        if (!p.errors().isEmpty()) {
            return CedarExportCheck.rejected(p.errors(), p.sourceHash());
        }
        if (p.candidates().isEmpty()) {
            return CedarExportCheck.rejected(List.of(functionRequired(p.module(), p.candidates())), p.sourceHash());
        }
        List<CedarExport> exports = p.candidates().stream()
            .map(fn -> CedarExporter.export(p.module(), fn, p.sourceHash()))
            .toList();
        List<String> rules = exports.stream().filter(CedarExport::exportable).map(CedarExport::functionName).toList();
        if (!rules.isEmpty()) {
            return new CedarExportCheck(true, rules, List.of(), p.sourceHash());
        }
        List<CompileDiagnostic> diagnostics = exports.stream()
            .flatMap(e -> e.diagnostics().stream())
            .map(CedarExportService::toCompileDiagnostic)
            .toList();
        return CedarExportCheck.rejected(diagnostics, p.sourceHash());
    }

    /**
     * 导出与检查共用的前半段：编译、读入 Core IR、确定候选规则。
     * 指定函数名时候选即该名；否则为全部通过 GuardShape 的函数（声明顺序）。{@code errors} 非空时其余字段无意义。
     */
    private record Prepared(String sourceHash, List<CompileDiagnostic> errors, String coreJson,
                            CoreModel.Module module, List<String> candidates) {
    }

    private Prepared prepare(String source, String locale, String aliasSetJson,
                             aster.core.identifier.VocabularyScopes scopes, String functionName,
                             boolean allowStructural) {
        String sourceHash = sha256(source == null ? "" : source);
        CompilationResult compiled = compiler.compile(source, locale, aliasSetJson, scopes, allowStructural);
        List<CompileDiagnostic> errors = compileErrors(compiled);
        if (!errors.isEmpty()) {
            return new Prepared(sourceHash, errors, null, null, List.of());
        }
        CoreModel.Module module = readModule(compiled.getCoreJson());
        boolean unnamed = functionName == null || functionName.isBlank();
        List<String> candidates = unnamed ? guardFunctions(module) : List.of(functionName);
        return new Prepared(sourceHash, List.of(), compiled.getCoreJson(), module, candidates);
    }

    // 未指定函数名且无法唯一确定导出规则时的 E720
    private static CompileDiagnostic functionRequired(CoreModel.Module module, List<String> candidates) {
        return toCompileDiagnostic(
            GuardShape.notExportable(module.name, functionRequiredReason(candidates), module.origin));
    }

    /** 包可见：对给定导出执行严格校验与逐探针等价性检查，供测试注入被破坏的导出。 */
    CedarExportOutcome verify(CedarExport exp, String coreJson, String sourceHash) {
        return verify(exp, coreJson, sourceHash, readModule(coreJson).name);
    }

    private CedarExportOutcome verify(CedarExport exp, String coreJson, String sourceHash, String moduleName) {
        if (!exp.exportable()) {
            return rejected(exp, moduleName, sourceHash, 0, 0, exp.diagnostics());
        }
        CedarEngine.ValidationResult validation = cedar.validate(exp.policies(), exp.policyIds(), exp.schemaJson());
        if (!validation.ok()) {
            String detail = validation.errors().isEmpty() ? "validation failed" : validation.errors().get(0);
            Diagnostic e724 = diagnostic(ErrorCode.CEDAR_SCHEMA_INVALID, Map.of("rule", ruleLabel(exp), "detail", detail));
            return rejected(exp, moduleName, sourceHash, 0, 0, List.of(e724));
        }
        List<Probe> probes = ProbeGenerator.generate(exp.facts(), exp.functionName(), MAX_PROBES);
        // 结论、原因、审批人以实际发布的策略文本注解为准，不信任导出器的中间元数据
        Map<String, Map<String, String>> byId = cedar.annotations(exp.policies(), exp.policyIds());
        int agreed = 0;
        Mismatch first = null;
        for (Probe probe : probes) {
            AsterSide aster = runAster(coreJson, exp.functionName(), probe);
            CedarEngine.Decision decision = cedar.authorize(exp.policies(), exp.policyIds(), cedarRequest(probe));
            boolean ok = agrees(probe, aster, decision, byId);
            agreed += ok ? 1 : 0;
            first = (ok || first != null) ? first : new Mismatch(probe, aster, decision);
        }
        if (first != null) {
            return rejected(exp, moduleName, sourceHash, probes.size(), agreed, List.of(mismatchDiagnostic(exp, first)));
        }
        return new CedarExportOutcome(true, exp.ruleId(), exp.functionName(), moduleName, exp.policies(),
            exp.schemaJson(), exp.profile(), exp.controls(), probes.size(), agreed, sourceHash, List.of());
    }

    // ---------- 一致性判定 ----------

    // 越界探针单向比较：Cedar 允许时 Aster 必须允许；Cedar 拒绝时接受任何 Aster 结论
    private static boolean agrees(Probe probe, AsterSide aster, CedarEngine.Decision cedar,
                                  Map<String, Map<String, String>> byId) {
        if (probe.outOfDomain()) {
            return !cedar.allow() || ALLOW.equals(aster.outcome());
        }
        if (aster.thrown() != null) {
            return !cedar.allow() && cedar.determiningPolicyIds().isEmpty();
        }
        if (ALLOW.equals(aster.outcome())) {
            return cedar.allow();
        }
        return !cedar.allow() && cedar.determiningPolicyIds().size() == 1
            && sameVerdict(byId.get(cedar.determiningPolicyIds().get(0)), aster);
    }

    // 决定性策略的 @aster_outcome（大写比较）、@aster_reason、@aster_approver 须与 Aster 的 Verdict 一致
    private static boolean sameVerdict(Map<String, String> annotations, AsterSide aster) {
        String outcome = annotations == null ? null : annotations.get("aster_outcome");
        return outcome != null
            && outcome.toUpperCase(Locale.ROOT).equals(aster.outcome())
            && Objects.equals(annotations.get("aster_reason"), aster.reason())
            && Objects.equals(annotations.get("aster_approver"), aster.role());
    }

    // ---------- 两侧求值 ----------

    // Aster 侧恒以 toGuardArgs() 原样入参，与 guard 网关的扁平化请求同构
    private static AsterSide runAster(String coreJson, String functionName, Probe probe) {
        try {
            var result = DynamicCnlExecutor.executeCoreIrJson(coreJson, new Object[] {probe.toGuardArgs()}, functionName);
            return VerdictView.from(result.result())
                .map(v -> new AsterSide(v.outcome(), v.reason(), v.role(), null))
                .orElseGet(() -> new AsterSide("NOT_VERDICT", null, null, null));
        } catch (DynamicExecutionException e) {
            return new AsterSide(null, null, null, truncate(String.valueOf(e.getMessage())));
        }
    }

    // agent_model 为 null 表示请求未带该字段，Cedar context 中省略；空串及其他取值原样放入
    private static CedarEngine.CedarRequest cedarRequest(Probe probe) {
        Map<String, Object> context = new LinkedHashMap<>(probe.context());
        if (probe.agentModel() != null) {
            context.put("agent_model", probe.agentModel());
        }
        return new CedarEngine.CedarRequest(probe.principalId(), probe.principalType(), probe.roles(), probe.action(),
            probe.resourceType(), probe.resourceId(), context);
    }

    // ---------- 诊断 ----------

    private static List<CompileDiagnostic> compileErrors(CompilationResult compiled) {
        if (compiled.isSuccess()) {
            return compiled.getDiagnostics().stream()
                .filter(d -> "error".equals(d.severity()))
                .map(CompileDiagnostic::from)
                .toList();
        }
        if (!compiled.getDiagnostics().isEmpty()) {
            return compiled.getDiagnostics().stream().map(CompileDiagnostic::from).toList();
        }
        return compiled.getErrors().stream().map(m -> CompileDiagnostic.error(1, 1, m)).toList();
    }

    private static Diagnostic mismatchDiagnostic(CedarExport exp, Mismatch m) {
        String cedarSummary = (m.cedar().allow() ? "Allow " : "Deny ") + m.cedar().determiningPolicyIds();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rule", ruleLabel(exp));
        data.put("probe", probeJson(m.probe()));
        data.put("aster", m.aster().summary());
        data.put("cedar", cedarSummary);
        return diagnostic(ErrorCode.CEDAR_EQUIVALENCE_MISMATCH, data);
    }

    private static Diagnostic diagnostic(ErrorCode code, Map<String, Object> data) {
        return new Diagnostic(Diagnostic.Severity.ERROR, code, code.render(data), Optional.empty(),
            Optional.ofNullable(code.help()), Map.copyOf(data));
    }

    // 有 span 时取其起止行列，否则 1/1；终点缺失时与起点相同
    private static CompileDiagnostic toCompileDiagnostic(Diagnostic d) {
        CoreModel.Position start = d.span().map(o -> o.start).orElse(null);
        CoreModel.Position end = d.span().map(o -> o.end).orElse(start);
        int line = start == null ? 1 : start.line;
        int col = start == null ? 1 : start.col;
        int endLine = end == null ? line : end.line;
        int endCol = end == null ? col : end.col;
        return new CompileDiagnostic("error", d.message(), line, col, endLine, endCol, d.code().code(), null, false);
    }

    private static CedarExportOutcome rejected(CedarExport exp, String moduleName, String sourceHash, int probeCount,
                                               int agreed, List<Diagnostic> diagnostics) {
        List<CompileDiagnostic> mapped = diagnostics.stream().map(CedarExportService::toCompileDiagnostic).toList();
        return new CedarExportOutcome(false, exp.ruleId(), exp.functionName(), moduleName, null, null, exp.profile(),
            exp.controls(), probeCount, agreed, sourceHash, mapped);
    }

    // ---------- 辅助 ----------

    /** 模块中可通过 GuardShape 的函数名（声明顺序）；未指定函数名时导出只接受其中唯一的一个。 */
    private static List<String> guardFunctions(CoreModel.Module module) {
        if (module.decls == null) {
            return List.of();
        }
        return module.decls.stream()
            .filter(d -> d instanceof CoreModel.Func f && GuardShape.check(module, f, new ArrayList<>()).isPresent())
            .map(d -> ((CoreModel.Func) d).name)
            .toList();
    }

    /**
     * 未指定函数名且无法唯一确定导出规则时 E720 的原因。
     * 没有候选时说明缺的是 guard 形态（E720 的 help 列出具体要求），而不是让人去找函数名；
     * 多个候选时列出它们，供调用方选择。
     */
    static String functionRequiredReason(List<String> candidates) {
        return candidates.isEmpty()
            ? "no rule has the guard request shape"
            : "several rules can be exported (" + String.join(", ", candidates) + "); choose one";
    }

    private static String ruleLabel(CedarExport exp) {
        return exp.ruleId() != null ? exp.ruleId() : exp.functionName();
    }

    // 探针 JSON 键序固定：guard 入参键序，再加 out_of_domain
    private static String probeJson(Probe probe) {
        Map<String, Object> json = new LinkedHashMap<>(probe.toGuardArgs());
        json.put("out_of_domain", probe.outOfDomain());
        try {
            return JacksonMappers.DEFAULT.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("探针序列化失败", e);
        }
    }

    /**
     * coreJson 由本服务的编译器产出，经 {@link CoreIrJson#read} 严格读入（ADR 0055 §5）；
     * 不是合法 v2 产物属于内部缺陷。
     */
    static CoreModel.Module readModule(String coreJson) {
        try {
            return CoreIrJson.read(coreJson);
        } catch (CoreIrFormatException e) {
            throw new IllegalStateException("Core IR JSON 不是合法的 v2 产物", e);
        }
    }

    private static String truncate(String message) {
        return message.length() <= THROW_DETAIL_MAX ? message : message.substring(0, THROW_DETAIL_MAX);
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
