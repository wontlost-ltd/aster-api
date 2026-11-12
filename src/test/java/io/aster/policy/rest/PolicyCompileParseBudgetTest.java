package io.aster.policy.rest;

import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.parser.ParseBudget;
import io.aster.policy.test.RedisEnabledTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编译端点的解析时间预算（aster-cloud/aster-lang-core#254 兜底）。
 *
 * <p>病态输入取 replay 模块的共享夹具 {@code sll-pathological-types.aster}（生产 SLL 路径不限时实测约 46s）。
 * 端点必须在预算附近返回 200 + 带码 E054（{@code reason=time-budget}），不占住工作线程；
 * 几个慢请求并发时正常请求不受阻（在匿名解析许可池容量之内）；按版本编译的超时结果不进编译缓存。
 */
@QuarkusTest
@RedisEnabledTest
public class PolicyCompileParseBudgetTest {

    /** 端点往返 + 预算检查间隔的允许超出量。 */
    private static final long MARGIN_MS = 1500;

    private static final String NORMAL = """
        Module loan.

        Define Applicant has creditScore as Int, income as Int.

        Rule approve given applicant as Applicant, produce Bool:
          If applicant.creditScore greater than 700:
            Return true.
          Return applicant.income greater than 5000.
        """;

    @Inject
    PolicyCompiler policyCompiler;

    @Inject
    MeterRegistry registry;

    @AfterEach
    void restoreConfiguredBudget() {
        ParseBudget.configure(ParseBudget.DEFAULT_BUDGET);
    }

    private static String pathological() throws IOException {
        // 与 replay 单测共用同一份夹具（Gradle 以项目目录为测试工作目录）
        return Files.readString(Path.of("replay/src/test/resources/parse-budget/sll-pathological-types.aster"));
    }

    /** core#254 报告的形态：result of 成功 / 失败分量交替嵌套。 */
    private static String alternatingResult(int depth) {
        String type = "Int";
        for (int i = 0; i < depth; i++) {
            type = i % 2 == 0 ? "result of " + type + " and Text" : "result of Int and " + type;
        }
        return "Module m.\n\nRule f given x as " + type + ", produce Int:\n  Return 1.\n";
    }

    private static ValidatableResponse compile(String source) {
        return given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body(java.util.Map.of("source", source, "locale", "en-US"))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .log().ifValidationFails()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }

    private static long millisSince(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    private double exceededMetric() {
        return registry.get("aster.parse.budget.exceeded").functionCounter().count();
    }

    @Test
    void pathologicalSourceReturnsCodedE054WithinDefaultBudget() throws IOException {
        assertEquals(ParseBudget.DEFAULT_BUDGET, ParseBudget.configured(), "启动时按 aster.parse.time-budget=2s 配置");
        String source = pathological();
        double metricBefore = exceededMetric();

        long t0 = System.nanoTime();
        compile(source)
            .body("success", is(false))
            .body("diagnostics.findAll { it.code == '" + io.aster.policy.rest.model.CompileDiagnostic.INTERNAL_FAILURE_CODE
                + "' }", hasSize(0))
            .body("diagnostics.findAll { it.code == 'E054' && it.params.reason == 'time-budget' }", hasSize(1))
            .body("diagnostics.find { it.code == 'E054' }.params.budgetMs", is("2000"))
            .body("diagnostics.find { it.code == 'E054' }.blocking", is(true));
        long elapsed = millisSince(t0);

        assertTrue(elapsed < 2000 + MARGIN_MS, "应在 2s 预算附近返回，实际 " + elapsed + "ms");
        assertEquals(metricBefore + 1, exceededMetric(), "aster.parse.budget.exceeded 指标加一");
    }

    @Test
    void normalAndCoreReportedShapesAreUnaffected() {
        long t0 = System.nanoTime();
        compile(NORMAL)
            .body("success", is(true))
            .body("diagnostics.findAll { it.code == 'E054' }", hasSize(0));
        // core#254 报告的 result of 交替嵌套 32 层：生产 SLL 预测下线性，照常编译成功
        compile(alternatingResult(32))
            .body("success", is(true))
            .body("diagnostics.findAll { it.code == 'E054' }", hasSize(0));
        long elapsed = millisSince(t0);
        assertTrue(elapsed < 2000, "两次正常编译应远低于预算，实际 " + elapsed + "ms");
    }

    @Test
    void concurrentSlowRequestsDoNotBlockANormalOne() throws Exception {
        // 慢请求数取 3：低于匿名解析许可池的下限（max(2, 2×核数) ≥ 4，单核 CI 也满足），
        // 正常请求不会因许可耗尽被 503，验证的是「慢请求不占住线程 / 不拖慢别人」
        ParseBudget.configure(Duration.ofMillis(1000));
        String source = pathological();
        int slowCount = 3;
        ExecutorService clients = Executors.newFixedThreadPool(slowCount);
        try {
            List<CompletableFuture<Long>> slow = new ArrayList<>();
            for (int i = 0; i < slowCount; i++) {
                slow.add(CompletableFuture.supplyAsync(() -> {
                    long t0 = System.nanoTime();
                    compile(source)
                        .body("success", is(false))
                        .body("diagnostics.findAll { it.code == 'E054' && it.params.reason == 'time-budget' }", hasSize(1));
                    return millisSince(t0);
                }, clients));
            }
            Thread.sleep(200);   // 让慢请求先进入解析
            long t0 = System.nanoTime();
            compile(NORMAL).body("success", is(true));
            long normalElapsed = millisSince(t0);

            for (CompletableFuture<Long> f : slow) {
                long elapsed = f.get(30, TimeUnit.SECONDS);
                assertTrue(elapsed < 1000 + MARGIN_MS, "并发慢请求也应在预算附近返回，实际 " + elapsed + "ms");
            }
            assertTrue(normalElapsed < 1000, "慢请求进行中，正常请求不应被拖住，实际 " + normalElapsed + "ms");
        } finally {
            clients.shutdownNow();
        }
    }

    @Test
    void budgetExceededOnVersionPathIsNotCached() throws IOException {
        ParseBudget.configure(Duration.ofMillis(300));
        PolicyVersion version = new PolicyVersion();
        version.content = pathological();
        version.locale = "en-US";
        Long versionId = 9_254_000_001L;   // 不落库：compile(id, source) 零 DB 访问
        PolicyCompiler.VersionSource source = new PolicyCompiler.VersionSource(version, null);

        // 若超时结果被缓存，第二次会直接拿到缓存的失败结果而不再抛出
        assertThrows(InProcessCnlParser.BudgetExceededFailure.class, () -> policyCompiler.compile(versionId, source));
        assertThrows(InProcessCnlParser.BudgetExceededFailure.class, () -> policyCompiler.compile(versionId, source),
            "超时取决于负载，不得进 policy-compile-by-version 缓存");

        // 同一路径上的普通语法错误仍按原样返回失败结果（不改变既有行为）
        PolicyVersion broken = new PolicyVersion();
        broken.content = "Module m.\n\nRule f given x as Int, produce Int:\n  Return (1.\n";
        broken.locale = "en-US";
        CompilationResult result = policyCompiler.compile(9_254_000_002L, new PolicyCompiler.VersionSource(broken, null));
        assertFalse(result.isSuccess());
    }
}
