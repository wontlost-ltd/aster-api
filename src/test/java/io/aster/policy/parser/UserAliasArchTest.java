package io.aster.policy.parser;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 架构约束（ADR 0022 §11.5，Codex 复核建议）：堵住"未经校验的 aliasSet 进入编译"的绕过。
 *
 * <p>{@link InProcessCnlParser#parseUnsafeWithAliases} 是 package-private 的 unsafe 原语
 * （不校验 aliasSet）。生产路径必须走 {@link InProcessCnlParser#parseWithUserAliases}（前置
 * {@link UserAliasValidator}）。package-private 不是安全边界——同包生产类仍可调用。本规则把
 * "禁止生产代码调 unsafe" 升级为可执行的架构约束：只有 parser 包内的受控入口/测试可调。
 */
class UserAliasArchTest {

    private static final JavaClasses PROD_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("io.aster");

    @Test
    void unsafeParseMethodSignatureExists() {
        // 防 ArchUnit 假绿：先用反射确认目标方法签名真实存在。若 unsafe 方法被重命名/改签名，
        // 这里立即失败提示更新规则，而非让下面的规则因匹配不到而静默通过。
        assertDoesNotThrow(() -> InProcessCnlParser.class.getDeclaredMethod(
                "parseUnsafeWithAliases",
                String.class, String.class,
                aster.core.identifier.VocabularyScopes.class, Map.class),
            "parseUnsafeWithAliases 签名变更：请同步更新 ArchUnit 规则");
    }

    @Test
    void noProductionCodeOutsideParserMayCallUnsafeAliasParse() {
        // 仅 InProcessCnlParser 自身（受控入口 parseWithUserAliases → unsafe）可调 unsafe 原语。
        // 其它任何生产类调用 = 绕过 UserAliasValidator 强制校验 = 架构违规。
        // 精确按 FQCN 排除该类（不用 simpleName 后缀匹配，避免误放行同名结尾的其它类）。
        ArchRule rule = noClasses()
                .that()
                .doNotHaveFullyQualifiedName(InProcessCnlParser.class.getName())
                .should()
                .callMethod(InProcessCnlParser.class, "parseUnsafeWithAliases",
                        String.class, String.class,
                        aster.core.identifier.VocabularyScopes.class, Map.class)
                .allowEmptyShould(true)
                .because("生产路径必须走 parseWithUserAliases（强制 UserAliasValidator 校验），"
                        + "parseUnsafeWithAliases 仅供 parser 受控入口/测试使用（ADR 0022 §11.5）");
        rule.check(PROD_CLASSES);
    }
}
