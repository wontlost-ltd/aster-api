package io.aster.policy.compiler;

import io.aster.policy.entity.ArtifactType;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PolicyCompiler 单元测试：验证读取 core_json 的简化流程。
 */
@ExtendWith(MockitoExtension.class)
class PolicyCompilerTest {

    @Mock
    PolicySourceRepository policySourceRepository;

    private PolicyCompiler policyCompiler;

    @BeforeEach
    void setUp() {
        policyCompiler = new PolicyCompiler(policySourceRepository, new io.aster.policy.stability.StabilityEnforcement(),
            new io.aster.policy.analysis.RuleConflictDiagnostics());
    }

    @Test
    void compileShouldReturnStoredCoreJsonWhenArtifactExists() {
        PolicyArtifact artifact = createArtifact(100L, "{\"module\":\"aster.finance.loan\"}");
        when(policySourceRepository.findCoreJsonArtifact(100L)).thenReturn(Optional.of(artifact));

        CompilationResult result = policyCompiler.compile(100L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCoreJson()).contains("aster.finance.loan");
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void compileShouldFailWhenArtifactAndVersionMissing() {
        long versionId = 200L;
        when(policySourceRepository.findCoreJsonArtifact(versionId)).thenReturn(Optional.empty());
        when(policySourceRepository.findVersionById(versionId)).thenReturn(Optional.empty());

        CompilationResult result = policyCompiler.compile(versionId);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCoreJson()).isNull();
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0)).contains("不存在");
    }

    @Test
    void compileShouldUseCoreJsonFieldWhenArtifactMissing() {
        long versionId = 300L;
        String storedCoreJson = "{\"module\":\"aster.finance.risk\",\"functions\":[]}";
        PolicyVersion version = createVersion(versionId, storedCoreJson);
        when(policySourceRepository.findCoreJsonArtifact(versionId)).thenReturn(Optional.empty());
        when(policySourceRepository.findVersionById(versionId)).thenReturn(Optional.of(version));

        CompilationResult result = policyCompiler.compile(versionId);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCoreJson()).isEqualTo(storedCoreJson);
        assertThat(result.getErrors()).isEmpty();
    }

    private PolicyVersion createVersion(long versionId, String coreJson) {
        PolicyVersion version = new PolicyVersion();
        version.id = versionId;
        version.policyId = "test.policy";
        version.moduleName = "test";
        version.functionName = "policy";
        version.coreJson = coreJson;
        version.locale = "zh-CN";
        return version;
    }

    @Test
    void compileWithAliasSetProducesSameResultAsCanonical() {
        // 方案 D：带用户别名的动态编译与规范拼写产出同一 Core IR JSON
        String aliasSrc = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x multiplied by 3.";
        String canonSrc = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 3.";
        CompilationResult aliased = policyCompiler.compile(
            aliasSrc, "en-US", "{\"TIMES\":[\"multiplied by\"]}");
        CompilationResult canon = policyCompiler.compile(canonSrc, "en-US", null);
        assertThat(aliased.isSuccess()).isTrue();
        assertThat(canon.isSuccess()).isTrue();
        assertThat(aliased.getCoreJson()).isEqualTo(canon.getCoreJson());
    }

    @Test
    void compileFailsClosedOnCorruptAliasSetJson() {
        // C2-a fail-closed：损坏的 alias_set → 编译失败（不静默回落无别名成功编译）
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 3.";
        CompilationResult result = policyCompiler.compile(src, "en-US", "{not valid json");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void compileSurfacesStructuredDiagnosticsWithLineColumnOnSyntaxError() {
        // 语法错误源码：编译失败并带结构化诊断（含 1-based 行列），供前端精确标错。
        String badSrc = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times .";
        CompilationResult result = policyCompiler.compile(badSrc, "en-US", null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDiagnostics()).isNotEmpty();
        CompilationResult.Diagnostic d = result.getDiagnostics().get(0);
        // 行列 1-based（>=1），消息非空。
        assertThat(d.line()).isGreaterThanOrEqualTo(1);
        assertThat(d.column()).isGreaterThanOrEqualTo(1);
        assertThat(d.message()).isNotBlank();
    }

    @Test
    void compileSuccessHasNoDiagnostics() {
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 3.";
        CompilationResult result = policyCompiler.compile(src, "en-US", null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDiagnostics()).isEmpty();
    }

    @Test
    void typecheckRejectsUntypedEnumFieldAndReportsParityDiagnostic() {
        var result = policyCompiler.compile(untypedEnumPolicy(), "en");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDiagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("E012");
            assertThat(d.severity()).isEqualTo("error");
            assertThat(d.blocking()).isTrue();
            assertThat(d.line()).isEqualTo(8);
            // Java 侧把 Text 以规范名 String 打印（TS 打印 Text），只锁字段与实际类型
            assertThat(d.message()).contains("Field 'status' expects").contains("got DecisionStatus");
        });
        assertThat(result.getErrors().get(0)).contains("E012 L8:");
    }

    @Test
    void typecheckRejectsUnknownField() {
        var result = policyCompiler.compile(typedPolicy(true), "en");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDiagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("E011");
            assertThat(d.message()).contains("Unknown field 'creditScor' for LoanApplication");
        });
    }

    @Test
    void typecheckAcceptsCorrectlyTypedPolicy() {
        var result = policyCompiler.compile(typedPolicy(false), "en");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDiagnostics()).noneSatisfy(d -> assertThat(d.severity()).isEqualTo("error"));
    }

    @Test
    void warnModeDowngradesTypeErrors() {
        policyCompiler.typecheckMode = "warn";
        var result = policyCompiler.compile(untypedEnumPolicy(), "en");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDiagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("E012");
            assertThat(d.severity()).isEqualTo("warning");
            assertThat(d.blocking()).isFalse();
        });
    }

    @Test
    void typeErrorsStillCarryOtherDiagnostics() {
        // 类型错误不早退：规则冲突（W601）等非阻断诊断与 E012 一起返回
        String src = untypedEnumPolicy().replace(
            "  If application.creditScore less than 580\n",
            "  If application.creditScore less than 580\n    If application.creditScore greater than 700\n      Return LoanDecision with status set to Review, reason set to \"odd\", riskTier set to Low.\n");
        var result = policyCompiler.compile(src, "en");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDiagnostics()).anySatisfy(d -> assertThat(d.code()).isEqualTo("E012"));
        assertThat(result.getDiagnostics()).anySatisfy(d -> assertThat(d.code())
            .isEqualTo(io.aster.policy.analysis.RuleConflictDiagnostics.CONFLICT_CODE));
    }

    @Test
    void typecheckDiagnosticMappingFallsBackWhenSpanIsMissing() {
        var noSpan = new aster.core.typecheck.model.Diagnostic(
            aster.core.typecheck.model.Diagnostic.Severity.ERROR,
            aster.core.typecheck.ErrorCode.FIELD_TYPE_MISMATCH, "msg",
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Map.of());
        var mapped = PolicyCompiler.mapTypecheckDiagnostic(noSpan, false);
        assertThat(mapped.line()).isEqualTo(1);
        assertThat(mapped.column()).isEqualTo(1);
        assertThat(mapped.endLine()).isEqualTo(1);
        assertThat(mapped.endColumn()).isEqualTo(1);
        assertThat(mapped.blocking()).isTrue();

        var origin = new aster.core.ir.CoreModel.Origin();
        origin.start = new aster.core.ir.CoreModel.Position();
        origin.start.line = 7;
        origin.start.col = 3;
        var startOnly = new aster.core.typecheck.model.Diagnostic(
            aster.core.typecheck.model.Diagnostic.Severity.ERROR,
            aster.core.typecheck.ErrorCode.UNKNOWN_FIELD, "msg",
            java.util.Optional.of(origin), java.util.Optional.empty(), java.util.Map.of());
        var mappedStart = PolicyCompiler.mapTypecheckDiagnostic(startOnly, true);
        assertThat(mappedStart.line()).isEqualTo(7);
        assertThat(mappedStart.endLine()).isEqualTo(7);
        assertThat(mappedStart.endColumn()).isEqualTo(3);
        assertThat(mappedStart.severity()).isEqualTo("warning");
        assertThat(mappedStart.blocking()).isFalse();
    }

    @Test
    void lenientCompileKeepsStoredVersionsExecutable() {
        // 运行时/导出/审批视图路径：类型错误降为 warning，success 不受影响
        var result = policyCompiler.compileLenient(untypedEnumPolicy(), "en", null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCoreJson()).isNotBlank();
        assertThat(result.getDiagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("E012");
            assertThat(d.severity()).isEqualTo("warning");
            assertThat(d.blocking()).isFalse();
        });
    }

    @Test
    void typeErrorsCoexistWithGovernanceDiagnostics() {
        // 未登记控制点（W704）来自 GovernanceDiagnostics，不来自类型检查；类型错误下仍只出现一次
        String src = untypedEnumPolicy().replace(
            "Rule evaluateSmallBusinessLoan given application, produce:",
            "@id(\"GP-001\")\n@control(\"ACME:ART1\")\nRule evaluateSmallBusinessLoan given application, produce:");
        var result = policyCompiler.compile(src, "en");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDiagnostics()).anySatisfy(d -> assertThat(d.code()).isEqualTo("E012"));
        assertThat(result.getDiagnostics().stream().filter(d -> "W704".equals(d.code())).count()).isEqualTo(1);
    }

    private String untypedEnumPolicy() {
        return """
            Module company.finance.smallbusiness.
            Define DecisionStatus as one of Approved, Rejected, Review.
            Define RiskTier as one of Low, Medium, High.
            Define LoanApplication has annualRevenue, yearsInBusiness, creditScore, requestedAmount, hasCollateral, industry.
            Define LoanDecision has status, reason, riskTier.
            Rule evaluateSmallBusinessLoan given application, produce:
              If application.creditScore less than 580
                Return LoanDecision with status set to Rejected, reason set to "Credit score below 580", riskTier set to High.
              Return LoanDecision with status set to Approved, reason set to "ok", riskTier set to Low.
            """;
    }

    private String typedPolicy(boolean typo) {
        return """
            Module company.finance.smallbusiness.
            Define DecisionStatus as one of Approved, Rejected, Review.
            Define RiskTier as one of Low, Medium, High.
            Define LoanApplication has annualRevenue as Double, yearsInBusiness as Int, creditScore as Int, requestedAmount as Double, hasCollateral as Bool, industry as Text.
            Define LoanDecision has status as DecisionStatus, reason as Text, riskTier as RiskTier.
            Rule evaluateSmallBusinessLoan given application as LoanApplication, produce LoanDecision:
              If application.%s less than 580
                Return LoanDecision with status set to Rejected, reason set to "Credit score below 580", riskTier set to High.
              Return LoanDecision with status set to Approved, reason set to "ok", riskTier set to Low.
            """.formatted(typo ? "creditScor" : "creditScore");
    }

    private PolicyArtifact createArtifact(long versionId, String json) {
        PolicyArtifact artifact = new PolicyArtifact();
        artifact.id = UUID.randomUUID();
        artifact.policyVersionId = versionId;
        artifact.artifactType = ArtifactType.CORE_JSON.name();
        artifact.content = json.getBytes(StandardCharsets.UTF_8);
        artifact.contentSha256 = "placeholder";
        artifact.compilerOpts = "{\"functionSignature\":\"evaluate\"}";
        artifact.createdAt = Instant.now();
        return artifact;
    }

    // ★Phase 2 接线回归：规则冲突分析器必须真的接到编译诊断链上。
    //
    // 它此前写完了却**没有任何调用方**（四轮交叉审查指出的交付缺口）。
    // 一个没人调用的分析器不是功能，是死代码——这几条测试锁住它确实被调用、
    // 结论确实到达用户，且**永不阻断编译**。

    @Test
    void 规则冲突提示随编译结果返回() {
        // 内层 x < 50 与外层 x > 100 矛盾 —— 真恒假，应报出
        String src = """
            Module m.

            Rule r given x as Int produce Int:
              If x is greater than 100:
                If x is less than 50:
                  Return 1.
                Return 2.
              Return 0.
            """;

        CompilationResult result = policyCompiler.compile(src, "en");

        assertThat(result.isSuccess()).isTrue();
        var conflicts = result.getDiagnostics().stream()
            .filter(d -> io.aster.policy.analysis.RuleConflictDiagnostics.CONFLICT_CODE.equals(d.code()))
            .toList();
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).severity()).isEqualTo("warning");
        // ★永不阻断：曾多次误报的检查不该有权拒绝用户的策略
        assertThat(conflicts.get(0).blocking()).isFalse();
        assertThat(conflicts.get(0).line()).isGreaterThan(0);
    }

    @Test
    void 正常策略不产生规则冲突提示() {
        // 负向断言同等重要：误报会让整个功能被业务人员忽略
        String src = """
            Module m.

            Rule r given x as Int produce Int:
              If x is greater than 100:
                Return 1.
              Return 0.
            """;

        CompilationResult result = policyCompiler.compile(src, "en");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDiagnostics().stream()
            .filter(d -> io.aster.policy.analysis.RuleConflictDiagnostics.CONFLICT_CODE.equals(d.code())))
            .isEmpty();
    }

    @Test
    void 有冲突时编译仍然成功() {
        // 分析器只提示不夺决策权 —— coreJson 必须照常产出
        String src = """
            Module m.

            Rule r given x as Int produce Int:
              If x is greater than 100:
                If x is less than 50:
                  Return 1.
                Return 2.
              Return 0.
            """;

        CompilationResult result = policyCompiler.compile(src, "en");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCoreJson()).isNotBlank();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void 诊断在_enUS_locale_下同样产出() {
        // REST 端点传的是 en-US（不是 en）——若 locale 影响解析，接线会在生产路径上失效
        String src = String.join("\n",
            "Module M.",
            "",
            "Rule r given x as Int produce Int:",
            "  If x is greater than 100:",
            "    If x is less than 50:",
            "      Return 1.",
            "    Return 2.",
            "  Return 0.");

        CompilationResult result = policyCompiler.compile(src, "en-US", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDiagnostics().stream()
            .filter(d -> io.aster.policy.analysis.RuleConflictDiagnostics.CONFLICT_CODE.equals(d.code())))
            .hasSize(1);
    }
}
