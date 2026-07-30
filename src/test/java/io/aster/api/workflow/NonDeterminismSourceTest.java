package io.aster.api.workflow;

import io.aster.workflow.DeterminismContext;
import aster.runtime.workflow.InMemoryWorkflowRuntime;
import io.aster.policy.api.PolicyCacheKey;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.PolicyEvaluationResult;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 非确定性来源修复验证
 */
class NonDeterminismSourceTest {

    // testPolicyStorageUuidReplay 已迁移到 PolicyStorageServiceIT —— createPolicy
    // 现已 DB-backed（@Transactional + persist），需真实数据源，故改为 @QuarkusTest IT。

    @Test
    void testIdempotencyKeyDeterministic() throws Exception {
        PostgresEventStore store = new PostgresEventStore();
        Method method = PostgresEventStore.class.getDeclaredMethod(
                "generateIdempotencyKey", String.class, String.class, String.class,
                Integer.class, Long.class, String.class);
        method.setAccessible(true);

        String payload = "{\"result\":\"ok\"}";
        String key1 = (String) method.invoke(store, "wf-1", "WorkflowStarted", payload, null, null, null);
        String key2 = (String) method.invoke(store, "wf-1", "WorkflowStarted", payload, null, null, null);
        String key3 = (String) method.invoke(store, "wf-1", "WorkflowStarted", payload + "x", null, null, null);

        Assertions.assertThat(key1).isEqualTo(key2);
        Assertions.assertThat(key3).isNotEqualTo(key1);
    }

    @Test
    void testInMemoryRuntimeDeterminism() {
        InMemoryWorkflowRuntime runtime = new InMemoryWorkflowRuntime();
        DeterminismContext context = runtime.getDeterminismContext();

        Assertions.assertThat(context).isNotNull();
        Assertions.assertThat(runtime.getDeterminismContext().clock()).isSameAs(context.clock());

        context.clock().now();
        Assertions.assertThat(context.clock().getRecordedTimes()).isNotEmpty();
    }

    @Test
    void testNoRemainingNonDeterministicSources() throws Exception {
        Map<String, List<Integer>> uuidMatches = scanPattern(
                "UUID.randomUUID",
                Paths.get("src/main/java"),
                Paths.get("../aster-runtime/src/main/java")
        );
        // 允许的 UUID.randomUUID 使用白名单：
        // - PolicyStorageService: 生成策略文档 ID (DeterminismContext 可重放)
        // - WorkflowSchedulerService: 生成工作流实例 ID (DeterminismContext 可重放)
        // - TimerSchedulerService: 生成定时器 ID (业务主键，随机性可接受)
        // - GlobalExceptionMapper: 生成 traceId 用于日志追踪 (不影响 workflow 确定性)
        // - LexiconAdminResource: ioError 生成 traceId 用于排查上传失败 (不影响 workflow 确定性)
        // - GenericOutboxScheduler: 生成 outbox claim 的 lease token (issue #119)——每次领取一个
        //   随机租约令牌用于区分 attempt 归属（防拆分事务后 reclaim 重投递的 ABA 覆盖），随机性正是
        //   诉求且不入 workflow 重放状态，不影响确定性
        // - InternalCallSigner: 生成 HMAC v2 canonical 的 nonce（2026-07-29 审计）——
        //   nonce 的**不可预测性正是安全诉求**（防重放），它只进 HTTP 头、不入 workflow
        //   重放状态，与确定性无关。若改成可重放的确定值，重放防护即失效。
        Assertions.assertThat(uuidMatches.keySet())
                .containsExactlyInAnyOrder(
                        "src/main/java/io/aster/policy/service/PolicyStorageService.java",
                        "src/main/java/io/aster/api/workflow/WorkflowSchedulerService.java",
                        "src/main/java/io/aster/api/workflow/TimerSchedulerService.java",
                        "src/main/java/io/aster/policy/exception/GlobalExceptionMapper.java",
                        "src/main/java/io/aster/policy/rest/LexiconAdminResource.java",
                        "src/main/java/io/aster/audit/outbox/GenericOutboxScheduler.java",
                        "src/main/java/io/aster/security/internal/InternalCallSigner.java"
                );

        Map<String, List<Integer>> nanoMatches = scanPattern(
                "System.nanoTime",
                Paths.get("src/main/java"),
                Paths.get("../aster-runtime/src/main/java")
        );
        // 允许的 System.nanoTime 白名单：
        // - PolicyEvaluationService: 评估耗时测量起点（不入 workflow 重放状态）
        // - PolicyVersionResolutionCache: 短 TTL 缓存的单调过期时钟（仅用于缓存淘汰，
        //   不持久化、不参与 workflow 确定性重放，安全）
        Assertions.assertThat(nanoMatches.keySet())
                .containsExactlyInAnyOrder(
                        "src/main/java/io/aster/policy/api/PolicyEvaluationService.java",
                        "src/main/java/io/aster/policy/cache/PolicyVersionResolutionCache.java"
                );
    }

    private static Map<String, List<Integer>> scanPattern(String needle, Path... roots) throws IOException {
        Map<String, List<Integer>> matches = new LinkedHashMap<>();
        Path moduleRoot = Paths.get("").toAbsolutePath();
        for (Path root : roots) {
            Path absoluteRoot = moduleRoot.resolve(root).normalize();
            if (!Files.exists(absoluteRoot)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(absoluteRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> path.toString().contains("src/main/java"))
                        .forEach(path -> collectMatches(needle, matches, moduleRoot, path));
            }
        }
        return matches;
    }

    private static void collectMatches(String needle,
                                       Map<String, List<Integer>> matches,
                                       Path moduleRoot,
                                       Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            List<Integer> hitLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) {
                    hitLines.add(i + 1);
                }
            }
            if (!hitLines.isEmpty()) {
                String relative = moduleRoot.relativize(file).toString().replace('\\', '/');
                matches.put(relative, hitLines);
            }
        } catch (IOException e) {
            throw new RuntimeException("无法读取文件: " + file, e);
        }
    }

}
