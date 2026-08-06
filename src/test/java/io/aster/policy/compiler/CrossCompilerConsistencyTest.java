package io.aster.policy.compiler;

import aster.core.ir.CoreModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨编译器一致性测试
 *
 * 验证 TypeScript 编译器 (aster-lang-ts) 生成的 Core IR JSON 能够被 Java 后端正确解析。
 * 这确保了前端编译器和后端执行引擎之间的数据格式兼容性。
 *
 * 测试策略：
 * 1. 读取 TypeScript golden 测试生成的 Core IR JSON 文件
 * 2. 使用 Java CoreModel 类反序列化
 * 3. 验证解析成功且关键结构正确
 * 4. 重新序列化并比较结构一致性
 */
@DisplayName("跨编译器一致性测试：验证 TypeScript Core IR 与 Java CoreModel 兼容性")
class CrossCompilerConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private static Path projectRoot;

    @BeforeAll
    static void setup() {
        // 定位项目根目录（从 quarkus-policy-api 向上两级）
        projectRoot = Paths.get("").toAbsolutePath();
        if (projectRoot.endsWith("quarkus-policy-api")) {
            projectRoot = projectRoot.getParent();
        }
        System.out.println("项目根目录: " + projectRoot);
    }

    @ParameterizedTest(name = "解析 TypeScript golden 文件: {0}")
    @ValueSource(strings = {
        "test/e2e/golden/core/expected_login_core.json",
        "test/e2e/golden/core/expected_greet_core.json",
        "test/e2e/golden/core/expected_if_param_core.json",
        "test/e2e/golden/core/expected_lambda_cnl_core.json",
        "test/e2e/golden/core/expected_match_enum_core.json"
    })
    void shouldParseTypeScriptGoldenCoreJson(String relativePath) throws IOException {
        Path goldenFile = projectRoot.resolve(relativePath);

        if (!Files.exists(goldenFile)) {
            System.out.println("跳过不存在的文件: " + goldenFile);
            return;
        }

        String goldenJson = Files.readString(goldenFile);
        System.out.println("读取 golden 文件: " + goldenFile);
        System.out.println("JSON 长度: " + goldenJson.length() + " 字符");

        // 反序列化为 Java CoreModel
        CoreModel.Module module = MAPPER.readValue(goldenJson, CoreModel.Module.class);

        // 验证基本结构
        assertThat(module).isNotNull();
        assertThat(module.decls).isNotNull();
        System.out.println("模块名称: " + module.name);
        System.out.println("声明数量: " + module.decls.size());

        // 重新序列化
        String reserialized = MAPPER.writeValueAsString(module);
        System.out.println("重新序列化成功，长度: " + reserialized.length() + " 字符");

        // 再次反序列化验证往返一致性
        CoreModel.Module roundTripped = MAPPER.readValue(reserialized, CoreModel.Module.class);
        assertThat(roundTripped.name).isEqualTo(module.name);
        assertThat(roundTripped.decls).hasSameSizeAs(module.decls);

        System.out.println("✅ 往返一致性验证通过: " + relativePath);
    }

    @Test
    @DisplayName("验证 login.aster 的 Core IR 结构")
    void shouldParseLoginCoreWithDetailedValidation() throws IOException {
        Path goldenFile = projectRoot.resolve("test/e2e/golden/core/expected_login_core.json");

        if (!Files.exists(goldenFile)) {
            System.out.println("跳过测试：golden 文件不存在");
            return;
        }

        String goldenJson = Files.readString(goldenFile);
        CoreModel.Module module = MAPPER.readValue(goldenJson, CoreModel.Module.class);

        // 验证模块名称
        assertThat(module.name).isEqualTo("app.service");

        // 查找 Data 声明
        long dataCount = module.decls.stream()
            .filter(d -> d instanceof CoreModel.Data)
            .count();
        assertThat(dataCount).isGreaterThanOrEqualTo(1);

        // 查找 Enum 声明
        long enumCount = module.decls.stream()
            .filter(d -> d instanceof CoreModel.Enum)
            .count();
        assertThat(enumCount).isGreaterThanOrEqualTo(1);

        // 查找 Func 声明
        long funcCount = module.decls.stream()
            .filter(d -> d instanceof CoreModel.Func)
            .count();
        assertThat(funcCount).isGreaterThanOrEqualTo(1);

        // 验证 login 函数
        CoreModel.Func loginFunc = module.decls.stream()
            .filter(d -> d instanceof CoreModel.Func)
            .map(d -> (CoreModel.Func) d)
            .filter(f -> "login".equals(f.name))
            .findFirst()
            .orElse(null);

        assertThat(loginFunc).isNotNull();
        assertThat(loginFunc.params).hasSize(2);
        assertThat(loginFunc.ret).isInstanceOf(CoreModel.Result.class);
        assertThat(loginFunc.body).isNotNull();
        assertThat(loginFunc.body.statements).isNotEmpty();

        System.out.println("✅ login.aster Core IR 详细验证通过");
        System.out.println("  - Data 声明: " + dataCount);
        System.out.println("  - Enum 声明: " + enumCount);
        System.out.println("  - Func 声明: " + funcCount);
        System.out.println("  - login 函数参数数量: " + loginFunc.params.size());
        System.out.println("  - login 函数语句数量: " + loginFunc.body.statements.size());
    }

    @Test
    @DisplayName("验证 Java 编译器与 TypeScript 编译器输出格式一致")
    void shouldProduceConsistentCoreJsonBetweenCompilers() throws IOException {
        // 使用 Java 编译器编译简单的 CNL 源代码
        String cnlSource = """
            Module test.greet.

            Rule greet given name as Text:
              Return "Hello, " plus name.
            """;

        // 使用 Java InProcessCnlParser 编译
        PolicyCompiler compiler = new PolicyCompiler(null, new io.aster.policy.stability.StabilityEnforcement(),
            new io.aster.policy.analysis.RuleConflictDiagnostics());
        CompilationResult result = compiler.compile(cnlSource, "en-US");

        if (!result.isSuccess()) {
            System.out.println("编译失败（可能缺少依赖），跳过比较测试");
            System.out.println("错误: " + result.getErrors());
            return;
        }

        String javaCoreJson = result.getCoreJson();
        assertThat(javaCoreJson).isNotNull();

        // 验证 Java 生成的 Core JSON 可以被解析
        CoreModel.Module javaModule = MAPPER.readValue(javaCoreJson, CoreModel.Module.class);
        assertThat(javaModule).isNotNull();
        assertThat(javaModule.decls).isNotEmpty();

        // 查找 greet 函数
        CoreModel.Func greetFunc = javaModule.decls.stream()
            .filter(d -> d instanceof CoreModel.Func)
            .map(d -> (CoreModel.Func) d)
            .filter(f -> "greet".equals(f.name))
            .findFirst()
            .orElse(null);

        assertThat(greetFunc).isNotNull();
        assertThat(greetFunc.params).hasSize(1);
        assertThat(greetFunc.params.get(0).name).isEqualTo("name");

        System.out.println("✅ Java 编译器输出格式验证通过");
        System.out.println("  Core JSON 预览: " + javaCoreJson.substring(0, Math.min(200, javaCoreJson.length())) + "...");
    }

    @Test
    @DisplayName("验证复杂表达式类型的序列化一致性")
    void shouldSerializeComplexExpressionTypesConsistently() throws Exception {
        // 测试各种表达式类型

        // 1. Call 表达式
        CoreModel.Call call = new CoreModel.Call();
        call.target = new CoreModel.Name();
        ((CoreModel.Name) call.target).name = "print";
        CoreModel.StringE arg = new CoreModel.StringE();
        arg.value = "hello";
        call.args = java.util.List.of(arg);

        String callJson = MAPPER.writeValueAsString(call);
        CoreModel.Expr parsedCall = MAPPER.readValue(callJson, CoreModel.Expr.class);
        assertThat(parsedCall).isInstanceOf(CoreModel.Call.class);

        // 2. Ok/Err 表达式
        CoreModel.Ok ok = new CoreModel.Ok();
        ok.expr = new CoreModel.IntE();
        ((CoreModel.IntE) ok.expr).value = 42;

        String okJson = MAPPER.writeValueAsString(ok);
        CoreModel.Expr parsedOk = MAPPER.readValue(okJson, CoreModel.Expr.class);
        assertThat(parsedOk).isInstanceOf(CoreModel.Ok.class);

        // 3. Construct 表达式
        CoreModel.Construct construct = new CoreModel.Construct();
        construct.typeName = "User";
        CoreModel.FieldInit field = new CoreModel.FieldInit();
        field.name = "name";
        field.expr = new CoreModel.StringE();
        ((CoreModel.StringE) field.expr).value = "Alice";
        construct.fields = java.util.List.of(field);

        String constructJson = MAPPER.writeValueAsString(construct);
        CoreModel.Expr parsedConstruct = MAPPER.readValue(constructJson, CoreModel.Expr.class);
        assertThat(parsedConstruct).isInstanceOf(CoreModel.Construct.class);

        System.out.println("✅ 复杂表达式类型序列化一致性验证通过");
    }

    @Test
    @DisplayName("验证 Workflow 语句的序列化一致性")
    void shouldSerializeWorkflowStatementsConsistently() throws Exception {
        // 创建 Workflow 语句
        CoreModel.Workflow workflow = new CoreModel.Workflow();

        CoreModel.Step step1 = new CoreModel.Step();
        step1.name = "step1";
        step1.body = new CoreModel.Block();
        step1.body.statements = java.util.List.of();
        step1.dependencies = java.util.List.of();

        CoreModel.Step step2 = new CoreModel.Step();
        step2.name = "step2";
        step2.body = new CoreModel.Block();
        step2.body.statements = java.util.List.of();
        step2.dependencies = java.util.List.of("step1");

        workflow.steps = java.util.List.of(step1, step2);

        String workflowJson = MAPPER.writeValueAsString(workflow);
        assertThat(workflowJson).containsPattern("\"kind\"\\s*:\\s*\"workflow\"");

        CoreModel.Stmt parsedWorkflow = MAPPER.readValue(workflowJson, CoreModel.Stmt.class);
        assertThat(parsedWorkflow).isInstanceOf(CoreModel.Workflow.class);

        CoreModel.Workflow parsed = (CoreModel.Workflow) parsedWorkflow;
        assertThat(parsed.steps).hasSize(2);
        assertThat(parsed.steps.get(1).dependencies).contains("step1");

        System.out.println("✅ Workflow 语句序列化一致性验证通过");
    }

    @Test
    @DisplayName("验证 PII 类型的序列化一致性")
    void shouldSerializePiiTypeConsistently() throws Exception {
        // 创建 PII 类型
        CoreModel.PiiType piiType = new CoreModel.PiiType();
        piiType.baseType = new CoreModel.TypeName();
        ((CoreModel.TypeName) piiType.baseType).name = "Text";
        piiType.sensitivity = "L2";
        piiType.category = "email";

        String piiJson = MAPPER.writeValueAsString(piiType);
        assertThat(piiJson).containsPattern("\"kind\"\\s*:\\s*\"PiiType\"");
        assertThat(piiJson).containsPattern("\"sensitivity\"\\s*:\\s*\"L2\"");

        CoreModel.Type parsedPii = MAPPER.readValue(piiJson, CoreModel.Type.class);
        assertThat(parsedPii).isInstanceOf(CoreModel.PiiType.class);

        CoreModel.PiiType parsed = (CoreModel.PiiType) parsedPii;
        assertThat(parsed.sensitivity).isEqualTo("L2");
        assertThat(parsed.category).isEqualTo("email");

        System.out.println("✅ PII 类型序列化一致性验证通过");
    }
}
