package io.aster.policy.compiler;

import aster.core.ast.Module;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.repository.PolicySourceRepository;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * PolicyCompiler 负责将策略版本映射到 Core IR JSON。
 *
 * 编译优先级：
 * 1. 优先从 PolicyArtifact 读取预编译的 Core JSON
 * 2. 其次从 PolicyVersion.coreJson 读取
 * 3. 最后从 PolicyVersion.content 动态编译 CNL 源代码
 */
@ApplicationScoped
public class PolicyCompiler {

    private static final Logger LOG = Logger.getLogger(PolicyCompiler.class);
    private static final ObjectMapper MAPPER = JacksonMappers.PRETTY;

    private final PolicySourceRepository policySourceRepository;
    private final io.aster.policy.stability.StabilityEnforcement stabilityEnforcement;
    private final io.aster.policy.analysis.RuleConflictDiagnostics ruleConflictDiagnostics;

    @Inject
    public PolicyCompiler(PolicySourceRepository policySourceRepository,
                          io.aster.policy.stability.StabilityEnforcement stabilityEnforcement,
                          io.aster.policy.analysis.RuleConflictDiagnostics ruleConflictDiagnostics) {
        this.policySourceRepository = policySourceRepository;
        this.stabilityEnforcement = stabilityEnforcement;
        this.ruleConflictDiagnostics = ruleConflictDiagnostics;
    }

    /**
     * 从 CNL 源代码编译为 Core IR JSON。
     *
     * @param sourceCnl CNL 源代码
     * @param locale    语言环境（如 "zh-CN"、"en-US"）
     * @return 编译结果
     */
    public CompilationResult compile(String sourceCnl, String locale) {
        return compile(sourceCnl, locale, null);
    }

    /**
     * 从 CNL 源代码编译为 Core IR JSON，支持版本冻结的用户自定义别名（ADR 0022 方案 D）。
     *
     * <p>aliasSetJson 非空时经 {@link InProcessCnlParser#parseWithUserAliases}（**强制校验**
     * 别名）注入编译——保证动态编译路径与版本快照的别名一致，不会丢别名重解释
     * （Codex 持久化复核 C2-a：runtime fallback 必须带 aliasSet，否则带别名策略求值
     * 用无别名重解释→编译失败/语义错）。aliasSetJson 为 null/空时与旧行为一致。
     *
     * @param aliasSetJson 版本冻结的别名 JSON（kind→[别名,...]），null=无用户别名
     */
    public CompilationResult compile(String sourceCnl, String locale, String aliasSetJson) {
        if (sourceCnl == null || sourceCnl.isBlank()) {
            return CompilationResult.failure("CNL 源代码不能为空");
        }

        try {
            // 1. 解析 CNL → AST（带版本冻结的用户别名，经强制校验）
            LOG.debugf("解析 CNL 源代码... locale=%s, hasAliases=%b", locale, aliasSetJson != null && !aliasSetJson.isBlank());
            Map<SemanticTokenKind, List<String>> aliasSet = parseAliasSetJson(aliasSetJson);
            InProcessCnlParser.ParseResult parseResult = (aliasSet == null || aliasSet.isEmpty())
                ? InProcessCnlParser.parse(sourceCnl, locale)
                : InProcessCnlParser.parseWithUserAliases(sourceCnl, locale, null, aliasSet);
            Module astModule = parseResult.module();

            // 2. 降级 AST → Core IR
            LOG.debugf("降级 AST → Core IR...");
            CoreLowering lowering = new CoreLowering();
            CoreModel.Module coreModule = lowering.lowerModule(astModule);

            // 3. 序列化 Core IR → JSON
            String coreJson = MAPPER.writeValueAsString(coreModule);
            LOG.debugf("Core JSON 长度: %d 字符", coreJson.length());

            // 3b. P0-C 稳定性扫描（ADR 0031）：裸 compile 是 playground/dev surface = warn 默认，
            //     W600 warning 随成功返回（前端展示黄标），不阻断探索。strict enforcement 在
            //     approve/activate（StabilityEnforcement.enforceVersion），保存路径按 regulated。
            var stability = stabilityEnforcement.scan(coreModule, false);

            // 3c. 规则冲突静态检测（Phase 2）：纯 AST 分析，不需要执行数据，故对全部
            //     租户立即可用。恒 warning、永不阻断——见 RuleConflictDiagnostics 类注释。
            //     ★扫 AST 而非 Core IR：分析器要读 Stmt.If 的 span 拿行号，
            //     Core IR 的 Origin 目前不进序列化（见 ADR 0032）。
            var conflicts = ruleConflictDiagnostics.scan(astModule);

            // 4. 提取元数据
            CompilationMetadata metadata = new CompilationMetadata(
                null,
                String.format("module=%s, function=%s", parseResult.moduleName(), parseResult.firstFunctionName()),
                null
            );

            // 两类诊断合流走同一 diagnostics 通道（与 ADR 0031 W600 同构，非平行字段）
            List<CompilationResult.Diagnostic> allDiagnostics =
                new java.util.ArrayList<>(stability.diagnostics());
            allDiagnostics.addAll(conflicts);

            return CompilationResult.success(coreJson, metadata, allDiagnostics);

        } catch (InProcessCnlParser.CnlParseException e) {
            String message = "CNL 解析失败: " + e.getMessage();
            LOG.warn(message);
            // 透传结构化诊断（含 1-based 行列），供编译端点精确标错。
            List<CompilationResult.Diagnostic> diags = e.getDiagnostics().stream()
                .map(d -> CompilationResult.Diagnostic.error(d.line(), d.column(), d.message()))
                .toList();
            return CompilationResult.failure(java.util.List.of(message), diags);
        } catch (Exception e) {
            String message = "CNL 编译失败: " + e.getMessage();
            LOG.error(message, e);
            return CompilationResult.failure(message);
        }
    }

    /**
     * 根据策略版本 ID 获取 Core IR JSON。
     *
     * 查找顺序：
     * 1. PolicyArtifact 中的预编译 Core JSON
     * 2. PolicyVersion.coreJson 字段
     * 3. 从 PolicyVersion.content 动态编译
     *
     * <p>结果按 versionId 缓存：策略版本一经创建，其 {@code content} 不可变
     *（任何修改都新建一条带新 ID 的版本；回滚是切换 active 指针、不改旧版本内容），
     * 故 {@code versionId → 编译结果} 是恒定映射，缓存零陈旧风险。这消除了
     * /validate 主路径（多数版本无预编译产物 → 走动态编译）的重复编译开销。
     * 失败结果同样被缓存——content 恒定则失败也恒定，缓存正确且避免反复编译坏源码。
     *
     * @param policyVersionId 策略版本 ID
     * @return 编译结果
     */
    @CacheResult(cacheName = "policy-compile-by-version")
    public CompilationResult compile(@CacheKey Long policyVersionId) {
        if (policyVersionId == null) {
            return CompilationResult.failure("策略版本ID不能为空");
        }

        // 1. 尝试从 PolicyArtifact 读取
        if (policySourceRepository != null) {
            Optional<PolicyArtifact> artifact = policySourceRepository.findCoreJsonArtifact(policyVersionId);
            if (artifact.isPresent()) {
                CompilationResult result = buildSuccessResultFromArtifact(artifact.get());
                if (result.isSuccess()) {
                    LOG.debugf("从 PolicyArtifact 读取 Core JSON: versionId=%d", policyVersionId);
                    return result;
                }
            }
        }

        // 2. 尝试从 PolicyVersion.coreJson 读取
        Optional<PolicyVersion> optVersion = policySourceRepository.findVersionById(policyVersionId);
        if (optVersion.isEmpty()) {
            return CompilationResult.failure(String.format("策略版本不存在: versionId=%d", policyVersionId));
        }
        PolicyVersion version = optVersion.get();

        if (version.coreJson != null && !version.coreJson.isBlank()) {
            LOG.debugf("从 PolicyVersion.coreJson 读取: versionId=%d", policyVersionId);
            return CompilationResult.success(version.coreJson, CompilationMetadata.empty());
        }

        // 3. 从 CNL 源代码动态编译
        if (version.content == null || version.content.isBlank()) {
            return CompilationResult.failure(String.format("策略版本 %d 没有可用的 CNL 源代码", policyVersionId));
        }

        LOG.infof("动态编译 CNL 源代码: versionId=%d, locale=%s, hasAliases=%b",
            policyVersionId, version.locale, version.aliasSet != null);
        return compile(version.content, version.locale, version.aliasSet);
    }

    /**
     * 解析版本冻结的 aliasSet JSON（{@code {"TIMES":["multiplied by"],...}}）为
     * {@code Map<SemanticTokenKind,List<String>>}。
     *
     * <p><b>fail-closed</b>（Codex 持久化复核 C2-a）：非法 JSON / 未知 kind 一律抛异常，
     * **绝不**回落空别名。否则 alias_set 腐化或被篡改后，runtime 会静默用无别名重编译，
     * 正好回到 C2 的原始风险（带别名策略被无别名重解释）。null/空才视为"本就无别名"。
     */
    // package-visible：StabilityEnforcement 的 compile-on-demand 复用同一 alias 解析口径（ADR 0031）。
    public static Map<SemanticTokenKind, List<String>> parseAliasSetJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> raw;
        try {
            raw = MAPPER.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("版本 alias_set JSON 损坏，拒绝编译（fail-closed）: " + e.getMessage(), e);
        }
        Map<SemanticTokenKind, List<String>> out = new java.util.EnumMap<>(SemanticTokenKind.class);
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            SemanticTokenKind kind;
            try {
                kind = SemanticTokenKind.valueOf(e.getKey());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                    "版本 alias_set 含未知 kind '" + e.getKey() + "'，拒绝编译（fail-closed）", ex);
            }
            out.put(kind, List.copyOf(e.getValue()));
        }
        return out;
    }

    private CompilationResult buildSuccessResultFromArtifact(PolicyArtifact artifact) {
        if (artifact.content == null || artifact.content.length == 0) {
            return CompilationResult.failure("Core JSON 内容为空");
        }

        String coreJson = new String(artifact.content, StandardCharsets.UTF_8);
        CompilationMetadata metadata = extractMetadata(artifact);
        return CompilationResult.success(coreJson, metadata);
    }

    private CompilationMetadata extractMetadata(PolicyArtifact artifact) {
        if (artifact.compilerOpts == null || artifact.compilerOpts.isBlank()) {
            return CompilationMetadata.empty();
        }
        return new CompilationMetadata(null, artifact.compilerOpts, null);
    }
}
