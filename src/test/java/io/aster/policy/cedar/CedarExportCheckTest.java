package io.aster.policy.cedar;

import static io.aster.policy.cedar.CedarExportServiceTest.GUARD_DEFINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import io.aster.policy.parser.GermanReproductions;
import io.aster.policy.rest.model.CompileDiagnostic;
import io.aster.replay.core.ReplayExecutionCore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * aster-cloud#786：可导出检查只做编译 + 候选选择 + 核心翻译，是导出的必要条件检查；
 * 不做 Cedar 严格校验与探针（Cedar 引擎一次都不调用），真实导出仍是唯一权威。
 */
@QuarkusTest
class CedarExportCheckTest {

    static final String RULE = "@id(\"R-%s\")\nRule %s given req as GuardRequest, produce Verdict:\n"
        + "  Return Verdict.allow().\n\n";
    /** Text.length 没有 Cedar 翻译：规则形态合格，但核心翻译给出 E721。 */
    static final String UNTRANSLATABLE = "@id(\"U-%s\")\nRule %s given req as GuardRequest, produce Verdict:\n"
        + "  If Text.length(req.action) at least 1:\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n\n";

    @Inject CedarExportService service;
    @InjectSpy CedarEngine cedar;

    @Test
    void sampleIsExportableWithItsRuleName() {
        var check = service.check(read("cedar/guard-cedar-sample.aster"), "en-US", null, null, null, false);
        assertThat(check.exportable()).as("%s", check.diagnostics()).isTrue();
        assertThat(check.rules()).containsExactly("decide");
        assertThat(check.diagnostics()).isEmpty();
        assertThat(check.sourceHash()).matches("[0-9a-f]{64}");
        verifyNoInteractions(cedar);
    }

    @Test
    void sourceHashMatchesTheExport() {
        String src = read("cedar/guard-cedar-sample.aster");
        var check = service.check(src, "en-US", null, null, null, false);
        assertThat(check.sourceHash()).isEqualTo(service.exportSource(src, "en-US", null, null, null).sourceHash());
    }

    @Test
    void moduleWithoutGuardShapedRuleIsE720() {
        String src = "Module verdict.only.\n\nDefine Fahrer has alter as Int.\n\n"
            + "@id(\"V-1\")\nRule decide given f as Fahrer, produce Verdict:\n  Return Verdict.allow().\n";
        var check = service.check(src, "en-US", null, null, null, false);
        assertThat(check.exportable()).isFalse();
        assertThat(check.rules()).isEmpty();
        assertThat(check.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E720");
        assertThat(check.diagnostics().get(0).message()).contains("no rule has the guard request shape");
        verifyNoInteractions(cedar);
    }

    @Test
    void untranslatableConstructIsE721() {
        String src = "Module guard.cedar.bad.\n\n" + GUARD_DEFINE
            + "@id(\"R-X\")\nRule decide given req as GuardRequest, produce Verdict:\n"
            + "  If Text.length(req.action) at least 1:\n    Return Verdict.deny(\"x\").\n  Return Verdict.allow().\n";
        var check = service.check(src, "en-US", null, null, null, false);
        assertThat(check.exportable()).isFalse();
        assertThat(check.rules()).isEmpty();
        assertThat(check.diagnostics()).extracting(CompileDiagnostic::code).contains("E721");
        verifyNoInteractions(cedar);
    }

    @Test
    void compileErrorIsNotExportable() {
        var check = service.check("Module broken.\n\nRule decide given:\n", "en-US", null, null, null, false);
        assertThat(check.exportable()).isFalse();
        assertThat(check.rules()).isEmpty();
        assertThat(check.diagnostics()).isNotEmpty();
        verifyNoInteractions(cedar);
    }

    @Test
    void severalExportableRulesAreAllListedInDeclarationOrder() {
        String src = "Module two.\n\n" + GUARD_DEFINE + RULE.formatted("2", "second") + RULE.formatted("1", "first");
        var check = service.check(src, "en-US", null, null, null, false);
        assertThat(check.exportable()).as("%s", check.diagnostics()).isTrue();
        assertThat(check.rules()).containsExactly("second", "first");
        assertThat(check.diagnostics()).isEmpty();
    }

    @Test
    void onlyTranslatableCandidatesAreListed() {
        String src = "Module mixed.\n\n" + GUARD_DEFINE + UNTRANSLATABLE.formatted("1", "bad") + RULE.formatted("2", "good");
        var check = service.check(src, "en-US", null, null, null, false);
        assertThat(check.exportable()).isTrue();
        assertThat(check.rules()).containsExactly("good");
        assertThat(check.diagnostics()).isEmpty();
    }

    @Test
    void allCandidatesFailingAggregatesTheirDiagnosticsInDeclarationOrder() {
        String src = "Module bad.\n\n" + GUARD_DEFINE
            + UNTRANSLATABLE.formatted("1", "first") + UNTRANSLATABLE.formatted("2", "second");
        var check = service.check(src, "en-US", null, null, null, false);
        assertThat(check.exportable()).isFalse();
        assertThat(check.rules()).isEmpty();
        assertThat(check.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E721", "E721");
        assertThat(check.diagnostics().get(0).message()).contains("'first'");
        assertThat(check.diagnostics().get(1).message()).contains("'second'");
    }

    @Test
    void namedFunctionLimitsTheCheckToThatRule() {
        String src = "Module two.\n\n" + GUARD_DEFINE + RULE.formatted("1", "first") + RULE.formatted("2", "second");
        var check = service.check(src, "en-US", null, null, "second", false);
        assertThat(check.rules()).containsExactly("second");
    }

    @Test
    void missingNamedFunctionIsE720() {
        var check = service.check(read("cedar/guard-cedar-sample.aster"), "en-US", null, null, "nope", false);
        assertThat(check.exportable()).isFalse();
        assertThat(check.diagnostics()).extracting(CompileDiagnostic::code).containsExactly("E720");
        assertThat(check.diagnostics().get(0).message()).contains("function nope not found");
    }

    @Test
    void germanSourceWithVocabularyChecksOverResolvedCoreIr() {
        var scopes = ReplayExecutionCore.buildVocabularyScopes(GermanReproductions.GUARD_VOCABULARY);
        var check = service.check(GermanReproductions.GUARD_SOURCE, "de-DE", null, scopes, null, false);
        assertThat(check.exportable()).as("%s", check.diagnostics()).isTrue();
        assertThat(check.rules()).containsExactly("decide");
    }

    private static String read(String resource) {
        try (InputStream in = CedarExportCheckTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
