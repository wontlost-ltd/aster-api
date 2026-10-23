package io.aster.policy.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import aster.core.cedar.CedarExport;
import aster.core.cedar.CedarExporter;
import aster.core.cedar.EmittedPolicy;
import aster.core.ir.CoreModel;
import io.aster.common.JacksonMappers;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.governance.VerdictView;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.rest.model.CompileDiagnostic;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** ADR 0047 §5：导出服务的严格校验（E724）与逐探针等价性（E723）。 */
@QuarkusTest
class CedarExportServiceTest {

    static final String GUARD_DEFINE = "Define GuardRequest has principal_id, principal_type, principal_roles, action, "
        + "resource_type, resource_id, context, agent_model.\n\n";

    @Inject CedarExportService service;
    @Inject PolicyCompiler compiler;

    record Compiled(CoreModel.Module module, String coreJson) {
    }

    @Test
    void sampleExportsAndAllProbesAgree() {
        var out = service.exportSource(read("cedar/guard-cedar-sample.aster"), "en-US", null, null);
        assertThat(out.exportable()).as("%s", out.diagnostics()).isTrue();
        assertThat(out.ruleId()).isEqualTo("GCS-001");
        assertThat(out.moduleName()).isEqualTo("guard.cedar.sample");
        assertThat(out.probeCount()).isPositive().isEqualTo(out.probesAgreed());
        assertThat(out.policies()).contains("@aster_outcome(\"escalate\")").contains("@aster_controls(\"EU_AI_ACT:ART14\")");
    }

    @Test
    void probePolicyFromGuardTestExports() {
        var out = service.exportSource(DeployedMapParamProbeTest.SOURCE, "en-US", null, "decide");
        assertThat(out.exportable()).as("%s", out.diagnostics()).isTrue();
        assertThat(out.probeCount()).isPositive().isEqualTo(out.probesAgreed());
    }

    @Test
    void brokenEmitterIsCaughtAsE723() {
        var compiled = compileToModule(read("cedar/guard-cedar-sample.aster"));
        var exp = CedarExporter.export(compiled.module(), "decide", "h");
        // 故意把第一条 forbid 改为 permit，制造与 Aster 不一致的导出
        var broken = withFirstForbidAsPermit(exp);
        var out = service.verify(broken, compiled.coreJson(), "h");
        assertThat(out.exportable()).isFalse();
        assertThat(out.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E723");
        assertThat(out.diagnostics().get(0).message()).contains("\"principal_id\"").contains("Cedar Allow");
    }

    @Test
    void invalidSchemaIsCaughtAsE724() {
        var compiled = compileToModule(read("cedar/guard-cedar-sample.aster"));
        var exp = CedarExporter.export(compiled.module(), "decide", "h");
        // amount 声明为 String，而策略按 Long 比较：严格校验必然拒绝
        String schema = exp.schemaJson().replace("\"amount\":{\"type\":\"Long\"", "\"amount\":{\"type\":\"String\"");
        assertThat(schema).isNotEqualTo(exp.schemaJson());
        var out = service.verify(withSchema(exp, schema), compiled.coreJson(), "h");
        assertThat(out.exportable()).isFalse();
        assertThat(out.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E724");
    }

    @Test
    void swappedReasonAnnotationIsCaughtAsE723() {
        assertTextOnlyMutationIsE723(text -> text
            .replace("@aster_reason(\"suspended principal\")", "@aster_reason(\"__swap__\")")
            .replace("@aster_reason(\"large refund\")", "@aster_reason(\"suspended principal\")")
            .replace("@aster_reason(\"__swap__\")", "@aster_reason(\"large refund\")"));
    }

    @Test
    void changedOutcomeAnnotationIsCaughtAsE723() {
        assertTextOnlyMutationIsE723(text -> text.replace("@aster_outcome(\"escalate\")", "@aster_outcome(\"deny\")"));
    }

    @Test
    void changedApproverAnnotationIsCaughtAsE723() {
        assertTextOnlyMutationIsE723(text -> text
            .replace("@aster_approver(\"Data Protection Officer\")", "@aster_approver(\"Legal\")"));
    }

    // 只改实际发布的策略文本中的注解，emitted() 原样保留：比对必须以文本为准
    private void assertTextOnlyMutationIsE723(UnaryOperator<String> mutate) {
        var compiled = compileToModule(read("cedar/guard-cedar-sample.aster"));
        var exp = CedarExporter.export(compiled.module(), "decide", "h");
        String policies = mutate.apply(exp.policies());
        assertThat(policies).isNotEqualTo(exp.policies());
        var out = service.verify(withPolicies(exp, policies), compiled.coreJson(), "h");
        assertThat(out.exportable()).isFalse();
        assertThat(out.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E723");
    }

    @Test
    void profileViolationBlocksExport() {
        String src = "Module m.\nProfile \"governed\".\n\n" + GUARD_DEFINE
            + "Rule decide given req as GuardRequest, produce Verdict:\n  Return Verdict.allow().\n";
        var out = service.exportSource(src, "en-US", null, "decide");
        assertThat(out.exportable()).isFalse();
        assertThat(out.diagnostics()).extracting(CompileDiagnostic::code).contains("E706");
    }

    @Test
    void missingContextKeyAgreesAsDeny() {
        // 样本第一条读 context.amount 的 Let：探针中 amount 缺失时 Aster 抛错，Cedar 必须 Deny 且无决定性策略
        var out = service.exportSource(read("cedar/guard-cedar-sample.aster"), "en-US", null, null);
        assertThat(out.probesAgreed()).isEqualTo(out.probeCount());
    }

    @Test
    void missingFunctionNameWithTwoCandidatesIsE720() {
        String rule = "@id(\"R-%s\")\nRule %s given req as GuardRequest, produce Verdict:\n  Return Verdict.allow().\n\n";
        String src = "Module two.\n\n" + GUARD_DEFINE + rule.formatted("1", "first") + rule.formatted("2", "second");
        var out = service.exportSource(src, "en-US", null, null);
        assertThat(out.exportable()).isFalse();
        assertThat(out.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E720");
        assertThat(out.diagnostics().get(0).message()).contains("function name required");
    }

    @Test
    void asterDecidesIntegerAndLongAlike() {
        // 真实 guard 请求经 Jackson 得到 Integer，探针用 Long：两者在 Aster 中必须得出相同结论
        String coreJson = compileToModule(read("cedar/guard-cedar-sample.aster")).coreJson();
        for (long amount : new long[] {9999L, 10000L, 10001L}) {
            var asLong = verdict(coreJson, amount);
            var asInt = verdict(coreJson, (int) amount);
            assertThat(asInt).as("amount=%d", amount).isEqualTo(asLong);
        }
        assertThat(verdict(coreJson, 10000).outcome()).isEqualTo("ESCALATE");
        assertThat(verdict(coreJson, 9999).outcome()).isEqualTo("ALLOW");
    }

    private static VerdictView verdict(String coreJson, Object amount) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("principal_id", "p");
        args.put("principal_type", "agent");
        args.put("principal_roles", List.of());
        args.put("action", "refund");
        args.put("resource_type", "res");
        args.put("resource_id", "r");
        args.put("context", Map.of("amount", amount));
        args.put("agent_model", "");
        var result = DynamicCnlExecutor.executeCoreIrJson(coreJson, new Object[] {args}, "decide");
        return VerdictView.from(result.result()).orElseThrow();
    }

    private Compiled compileToModule(String source) {
        var result = compiler.compile(source, "en-US", null);
        assertThat(result.isSuccess()).as("%s", result.getErrors()).isTrue();
        try {
            return new Compiled(JacksonMappers.LENIENT.readValue(result.getCoreJson(), CoreModel.Module.class),
                result.getCoreJson());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CedarExport withFirstForbidAsPermit(CedarExport e) {
        List<EmittedPolicy> emitted = new ArrayList<>(e.emitted());
        int i = indexOfFirstForbid(emitted);
        EmittedPolicy p = emitted.get(i);
        emitted.set(i, new EmittedPolicy(p.id(), "permit", p.outcome(), p.reason(), p.approver(), p.condition()));
        String policies = e.policies().replaceFirst("(?m)^forbid \\(", "permit (");
        return new CedarExport(e.exportable(), e.ruleId(), e.functionName(), policies, e.policyIds(), emitted,
            e.schemaJson(), e.profile(), e.controls(), e.facts(), e.diagnostics());
    }

    private static int indexOfFirstForbid(List<EmittedPolicy> emitted) {
        for (int i = 0; i < emitted.size(); i++) {
            if ("forbid".equals(emitted.get(i).effect())) {
                return i;
            }
        }
        throw new IllegalStateException("样本没有 forbid 策略");
    }

    private static CedarExport withPolicies(CedarExport e, String policies) {
        return new CedarExport(e.exportable(), e.ruleId(), e.functionName(), policies, e.policyIds(), e.emitted(),
            e.schemaJson(), e.profile(), e.controls(), e.facts(), e.diagnostics());
    }

    private static CedarExport withSchema(CedarExport e, String schemaJson) {
        return new CedarExport(e.exportable(), e.ruleId(), e.functionName(), e.policies(), e.policyIds(),
            e.emitted(), schemaJson, e.profile(), e.controls(), e.facts(), e.diagnostics());
    }

    private static String read(String resource) {
        try (InputStream in = CedarExportServiceTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
