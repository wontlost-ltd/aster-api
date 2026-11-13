package io.aster.policy.api;

import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.stability.StabilityEnforcement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0055 §5 严格读方清单：aster-api 每个读 {@code core_json} / Core IR 的入口都必须经 core
 * {@link CoreIrJson#read} / {@link CoreIrJson#readTree}，非 v2 输入一律拒收。
 *
 * <p>主证据是逐入口喂入非法 IR 并断言被拒收；ArchUnit 规则是辅证（不扫源码文本）。
 * Cedar 导出与编译响应两个入口的包可见读方在各自包内的
 * {@code CedarCoreJsonStrictReadTest}、{@code CompileResponseCoreJsonStrictReadTest} 中验证。
 */
class CoreJsonStrictReadTest {

    private static String v2Ir() {
        return CoreJsonStrictReadFixtures.v2Ir();
    }

    private static List<String> invalidIrs() throws Exception {
        return CoreJsonStrictReadFixtures.invalidIrs();
    }

    // ---- PolicyEvaluationService.parseCoreModule（规则元数据） ----

    @Test
    void parseCoreModule_accepts_v2_and_rejects_everything_else() throws Exception {
        assertThat(PolicyEvaluationService.parseCoreModule(v2Ir())).isNotNull();
        for (String ir : invalidIrs()) {
            assertThat(PolicyEvaluationService.parseCoreModule(ir)).as(ir).isNull();
        }
    }

    // ---- PolicyEvaluationService.extractParametersFromCoreJson（树读取） ----

    @Test
    void extractParameters_reads_v2_tree() {
        List<io.aster.policy.api.model.ParameterInfo> params =
            PolicyEvaluationService.extractParametersFromCoreJson(v2Ir(), "main");
        assertThat(params).extracting(io.aster.policy.api.model.ParameterInfo::getName).containsExactly("driver");
    }

    @Test
    void extractParameters_rejects_non_v2_ir() throws Exception {
        for (String ir : invalidIrs()) {
            assertThatThrownBy(() -> PolicyEvaluationService.extractParametersFromCoreJson(ir, "main"))
                .as(ir)
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(aster.core.ir.CoreIrFormatException.class);
        }
    }

    // ---- StabilityEnforcement.scanCoreJson ----

    @Test
    void stabilityScan_rejects_non_v2_ir_in_strict_mode() throws Exception {
        StabilityEnforcement enforcement = new StabilityEnforcement();
        assertThat(enforcement.scanCoreJson(v2Ir(), true).blocked()).isFalse();
        for (String ir : invalidIrs()) {
            assertThatThrownBy(() -> enforcement.scanCoreJson(ir, true))
                .as(ir)
                .isInstanceOf(StabilityEnforcement.UnscannableException.class);
            assertThat(enforcement.scanCoreJson(ir, false).diagnostics()).as(ir).isEmpty();
        }
    }

    // ---- DynamicCnlExecutor.executeCoreIrJson（/evaluate-json，replay / runner 共用） ----

    @Test
    void executeCoreIrJson_rejects_non_v2_ir_before_execution() throws Exception {
        assertThat(DynamicCnlExecutor.executeCoreIrJson(v2Ir(), Map.of("driver", Map.of("age", 3)), null).result())
            .isEqualTo(3);
        for (String ir : invalidIrs()) {
            assertThatThrownBy(() -> DynamicCnlExecutor.executeCoreIrJson(ir, Map.of("driver", Map.of("age", 3)), null))
                .as(ir)
                .isInstanceOfSatisfying(DynamicCnlExecutor.DynamicExecutionException.class, e ->
                    assertThat(e.kind()).isEqualTo(DynamicCnlExecutor.DynamicExecutionException.Kind.INVALID_INPUT));
        }
    }

    // ---- 辅证：除 core CoreIrJson 外，aster-api 的类不自己把 JSON 绑定成 CoreModel.Module ----

    /**
     * 引用 {@code CoreModel.Module.class} 字面量并调用 Jackson {@code ObjectMapper} / {@code ObjectReader}
     * 的类，就是在绕开 {@link CoreIrJson} 自建 IR 读方。
     */
    @Test
    void no_class_binds_core_model_module_with_its_own_object_mapper() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.aster");
        assertThat(classes).as("导入的 aster-api 类").isNotEmpty();
        assertThat(classes.contain(DynamicCnlExecutor.class)).as("replay 模块的类须在扫描范围内").isTrue();

        List<String> offenders = classes.stream()
            .filter(c -> referencesModuleClassLiteral(c) && callsJacksonReader(c))
            .map(JavaClass::getName)
            .toList();
        assertThat(offenders).isEmpty();
    }

    private static boolean referencesModuleClassLiteral(JavaClass c) {
        return c.getReferencedClassObjects().stream()
            .anyMatch(ref -> ref.getRawType().isEquivalentTo(CoreModel.Module.class));
    }

    private static boolean callsJacksonReader(JavaClass c) {
        return c.getMethodCallsFromSelf().stream()
            .map(call -> call.getTargetOwner().getName())
            .anyMatch(owner -> owner.equals("com.fasterxml.jackson.databind.ObjectMapper")
                || owner.equals("com.fasterxml.jackson.databind.ObjectReader"));
    }
}
