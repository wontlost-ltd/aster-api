package io.aster.policy.parser;

import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.runtime.MissingInputFieldException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 输入缺少规则实际读取的字段（生产复测第 6 轮第 4 项）：此前运行时抛
 * {@code RuntimeException: Map 中不存在键：creditScore}，被包装成普通执行失败（200 + error、
 * 消息带 Java 异常类），调用方既看不出是输入问题，也看不出缺的是哪个字段。
 *
 * <p>现在归为调用方可修正的 {@link MissingInputFieldException}：带相对于调用方输入的字段路径，
 * 消息不含内部实现细节。规则没读到的缺失字段、多余字段仍照常执行（与原行为一致）。
 */
class MissingInputFieldTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LOAN = """
        Module finance.loan.

        Define Applicant has id as Text, creditScore as Int, age as Int.

        Rule evaluateLoan given applicant as Applicant, produce Text:
          If applicant.creditScore greater than 750
            Return "Good credit".
          Return "Manual review".
        """;

    private static final String NESTED = """
        Module demo.nested.

        Define Address has city as Text, zip as Text.

        Define Person has name as Text, address as Address.

        Rule home given person as Person, produce Text:
          Return person.address.city.
        """;

    private static final String TWO_PARAMS = """
        Module demo.card.

        Define Applicant has creditScore as Int.

        Define Application has amount as Int.

        Rule decide given applicant as Applicant, application as Application, produce Bool:
          If applicant.creditScore greater than 700
            Return application.amount less than 1000.
          Return false.
        """;

    private static Map<String, Object> applicant(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Object run(String source, String fn, Object context) {
        return DynamicCnlExecutor.executeWithContext(source, context, fn, "en-US").result();
    }

    private static MissingInputFieldException missing(String source, String fn, Object context) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> run(source, fn, context));
        assertThat(thrown).isInstanceOf(MissingInputFieldException.class);
        return (MissingInputFieldException) thrown;
    }

    @Test
    void 缺少规则读取的字段_报字段路径_归为输入错误() {
        MissingInputFieldException e = missing(LOAN, "evaluateLoan",
            Map.of("applicant", applicant("id", "A1", "age", 35)));

        assertThat(e.fieldPath()).isEqualTo("applicant.creditScore");
        assertThat(e.code()).isEqualTo(MissingInputFieldException.CODE).isEqualTo("missing_input_field");
        assertThat(e.kind()).isEqualTo(DynamicCnlExecutor.DynamicExecutionException.Kind.INVALID_INPUT);
        assertThat(e.getMessage())
            .contains("applicant.creditScore")
            .doesNotContain("java.", "Polyglot", "RuntimeException", "Map 中不存在键");
    }

    @Test
    void 缺少规则没读到的字段_照常执行() {
        assertThat(run(LOAN, "evaluateLoan", Map.of("applicant", applicant("creditScore", 800, "age", 35))))
            .isEqualTo("Good credit");
    }

    @Test
    void 多余字段与顶层多余键_静默忽略() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("applicant", applicant("id", "A1", "creditScore", 800, "age", 35, "nickname", "Al"));
        context.put("foo", 1);

        assertThat(run(LOAN, "evaluateLoan", context)).isEqualTo("Good credit");
    }

    @Test
    void 嵌套结构体缺字段_路径逐层给出() {
        Map<String, Object> person = applicant("name", "Ann", "address", applicant("zip", "6011"));

        MissingInputFieldException e = missing(NESTED, "home", Map.of("person", person));

        assertThat(e.fieldPath()).isEqualTo("person.address.city");
    }

    @Test
    void 嵌套结构体整体缺失_报缺失的那一层() {
        MissingInputFieldException e = missing(NESTED, "home", Map.of("person", applicant("name", "Ann")));

        assertThat(e.fieldPath()).isEqualTo("person.address");
    }

    @Test
    void 多参数规则缺少整个参数_报参数名() {
        MissingInputFieldException e = missing(TWO_PARAMS, "decide",
            Map.of("applicant", applicant("creditScore", 800)));

        assertThat(e.fieldPath()).isEqualTo("application");
    }

    @Test
    void 多参数规则第二个参数缺字段_路径指向该参数() {
        MissingInputFieldException e = missing(TWO_PARAMS, "decide",
            Map.of("applicant", applicant("creditScore", 800), "application", applicant("currency", "NZD")));

        assertThat(e.fieldPath()).isEqualTo("application.amount");
    }

    @Test
    void 单参数平铺输入_路径相对调用方输入_不带参数名() {
        MissingInputFieldException e = missing(LOAN, "evaluateLoan", applicant("id", "A1", "age", 35));

        assertThat(e.fieldPath()).isEqualTo("creditScore");
    }

    @Test
    void Core_IR_直接执行同样报字段路径() throws Exception {
        CoreModel.Module core = new CoreLowering().lowerModule(InProcessCnlParser.parse(LOAN, "en-US").module());
        String coreJson = MAPPER.writeValueAsString(core);

        assertThatThrownBy(() -> DynamicCnlExecutor.executeCoreIrJson(coreJson,
                Map.of("applicant", applicant("id", "A1")), "evaluateLoan"))
            .isInstanceOfSatisfying(MissingInputFieldException.class,
                e -> assertThat(e.fieldPath()).isEqualTo("applicant.creditScore"));
    }
}
