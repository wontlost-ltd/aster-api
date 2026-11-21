package io.aster.policy.parser;

import aster.core.embed.CnlErrorListener;
import aster.core.embed.InProcessCnlParser;
import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.IdentifierMapping;
import aster.core.identifier.VocabularyScopes;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.ParseDiagnostic;
import aster.core.parser.ParseDiagnostics;
import aster.core.typecheck.ErrorCode;
import aster.core.typecheck.model.Diagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0051 §16 R56/R57 跨引擎 parity（aster-api 侧）：aster-api 的生产解析入口
 * （{@link InProcessCnlParser} + {@link CnlErrorListener}）对共享夹具逐例产出与夹具同码同行的诊断，
 * 语法诊断的命名参数与 core {@link ParseDiagnostics} 等价。
 *
 * <p>夹具 {@code diagnostics/parse_diagnostics_parity.json} 只读 aster-lang-core 的权威副本
 * {@code ../aster-lang-core/src/test/resources/diagnostics/}（与 aster-lang-ts {@code shared/} 同名文件 byte-identical；core 测试资源
 * 不随 jar 发布）。CI、列车的 api-parity 预检与本地 composite 都把 core 检出在兄弟目录，且检出的就是 classpath 上那个 core
 * 版本（CI：setup-aster-build 的 sibling-ref = settings.gradle 的 pin；列车预检：候选 core main），所以夹具与引擎永远同版本，
 * pin bump 不需要同步复制夹具——列车在 L4 开的 pin PR 只改两处版本号（ADR 0053 修订 A1）。
 * 本仓不再保留副本（wontlost-ltd/aster-api#578）：它只在没有兄弟仓时被读，而那时与 classpath 上的 core 是否同版本无从保证，
 * 历次 pin bump 也没人同步，早已过期。没有兄弟仓即失败，不静默改测一份旧夹具。
 * 判定规则与 core 一致：有解析错误时取第一条非 E063 的诊断（全是 E063 时取第一条）；
 * 无解析错误时取名称解析的全部 error。{@code only=ts} 的用例跳过。
 */
class ParseDiagnosticsParityTest {

    /** 兄弟仓 aster-lang-core 的权威副本（Gradle 以项目目录为测试工作目录）。 */
    private static final Path SIBLING_FIXTURE =
        Path.of("..", "aster-lang-core", "src", "test", "resources", "diagnostics", "parse_diagnostics_parity.json");

    private static JsonNode fixture() throws Exception {
        assertThat(SIBLING_FIXTURE)
            .as("缺少兄弟仓 aster-lang-core 的 parse diagnostics parity 夹具 %s：把 core 检出到 ../aster-lang-core"
                + "（版本与 settings.gradle 的 platform pin 一致，CI 由 setup-aster-build 完成）", SIBLING_FIXTURE.toAbsolutePath())
            .isRegularFile();
        return new ObjectMapper().readTree(Files.readAllBytes(SIBLING_FIXTURE));
    }

    /** 与 core 夹具测试同样的词汇构造：structs 为 [[规范名, 本地名]]。 */
    private static VocabularyScopes scopes(JsonNode structs) {
        if (structs == null || structs.isMissingNode() || structs.isEmpty()) {
            return null;
        }
        List<IdentifierMapping> mappings = new ArrayList<>();
        structs.forEach(s -> mappings.add(IdentifierMapping.struct(s.get(0).asText(), s.get(1).asText())));
        return VocabularyScopes.build(new DomainVocabulary("t", "t", "de-DE", "1",
            mappings, List.of(), List.of(), List.of(), List.of(), null));
    }

    /** aster-api 产出的主诊断：[码, 行]，以及语法诊断的参数（名称解析诊断为 null）。 */
    private record Observed(List<List<Object>> codes, Map<String, String> params) {}

    private static Observed observe(String source, String lexicon, JsonNode structs) {
        try {
            InProcessCnlParser.parse(source, lexicon, scopes(structs));
            return new Observed(List.of(), null);
        } catch (InProcessCnlParser.NameResolutionFailure e) {
            List<List<Object>> out = new ArrayList<>();
            for (Diagnostic d : e.resolutionDiagnostics()) {
                out.add(List.of(d.code().code(), d.span().map(o -> o.start.line).orElse(0)));
            }
            return new Observed(out, null);
        } catch (InProcessCnlParser.CnlParseException e) {
            List<CnlErrorListener.Diagnostic> ds = e.getDiagnostics();
            assertThat(ds).as("语法错误必须带结构化诊断：%s", e.getMessage()).isNotEmpty();
            CnlErrorListener.Diagnostic primary = ds.stream()
                .filter(d -> !"E063".equals(d.code()))
                .findFirst()
                .orElse(ds.get(0));
            return new Observed(List.of(List.of(primary.code(), primary.line())), primary.params());
        }
    }

    /** core 参考实现的主诊断参数（同一选取规则）。 */
    private static Map<String, String> coreParams(String source, String lexicon) {
        var result = ParseDiagnostics.parse(source, LexiconRegistry.getInstance().getOrThrow(lexicon));
        return result.diagnostics().stream()
            .filter(d -> d.code() != ErrorCode.PARSE_NO_VIABLE)
            .findFirst()
            .or(() -> result.diagnostics().stream().findFirst())
            .map(ParseDiagnostic::params)
            .orElse(null);
    }

    @TestFactory
    Stream<DynamicTest> sharedFixture_sameCodeLineAndParams() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            if ("ts".equals(c.path("only").asText())) {
                continue;
            }
            List<List<Object>> expected = new ArrayList<>();
            c.get("codes").forEach(pair -> expected.add(List.of(pair.get(0).asText(), pair.get(1).asInt())));
            String lexicon = c.path("lexicon").asText("en-US");
            String source = c.get("source").asText();
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                Observed observed = observe(source, lexicon, c.get("structs"));
                assertThat(observed.codes()).isEqualTo(expected);
                if (observed.params() != null) {
                    assertThat(observed.params()).isEqualTo(coreParams(source, lexicon));
                }
            }));
        }
        return tests.stream();
    }
}
