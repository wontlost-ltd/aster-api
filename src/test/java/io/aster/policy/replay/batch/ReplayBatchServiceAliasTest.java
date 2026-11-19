package io.aster.policy.replay.batch;

import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量 What-If 重放目标版本时必须带上该版本冻结的 aliasSet（ADR 0060 §1.5、§7.4）。
 *
 * <p>此前 {@code ReplayBatchService.replayOne} 固定传空别名：cloud 回源接口已下发 {@code aliasSet}，
 * 但 {@link PolicyVersionSourceClient.VersionSource} 没有这个字段，于是任何靠别名才能解析的版本在
 * 批量 What-If 下都归为 {@code TARGET_COMPILE_ERROR}。
 *
 * <p>目标源码取 cloud #829 模板 {@code showcase-jingyesi}：规则体 {@code 夜.举头望明月 接 着 夜.低头思故乡}
 * 用多词运算符别名 {@code 接 着}（PLUS），去掉别名就无法解析——别名是承重的。
 * 只桩上游 HTTP（cloud 的执行窗口与版本源码），重放走真实执行器。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ReplayBatchServiceAliasTest {

    /** cloud {@code src/data/policy-example-showcase.ts} 的 {@code JINGYESI_SOURCE}（逐字复制）。 */
    private static final String ALIASED_TARGET = """
        模块 床前明月光。

        定义 地上霜 包含
          举头望明月，
          低头思故乡。

        规则 吟 给定 夜 作为 地上霜，产出 文本：
          返回 夜.举头望明月 接 着 夜.低头思故乡。
        """;

    /** cloud 回源下发的形状：kind 名 → 别名数组。 */
    private static final Map<String, List<String>> ALIAS_SET = Map.of("PLUS", List.of("接 着"));

    @Inject
    EntityManager em;

    @Inject
    ReplayBatchService service;

    @InjectMock
    ExecutionWindowClient windowClient;

    @InjectMock
    PolicyVersionSourceClient versionSourceClient;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM replay_batch_item").executeUpdate();
            em.createNativeQuery("DELETE FROM replay_batch").executeUpdate();
        });
        List<ExecutionWindowClient.WindowedExecution> rows = List.of(new ExecutionWindowClient.WindowedExecution(
            "a1", Map.of("夜", Map.of("举头望明月", "举头望明月，", "低头思故乡", "低头思故乡。")),
            "approved", true, "吟", "zh-CN", new JsonObject(), "pv-alias", "ALLOW"));
        Mockito.when(windowClient.fetchWindow(
                Mockito.anyString(), Mockito.anyString(),
                Mockito.any(Instant.class), Mockito.any(Instant.class)))
            .thenReturn(rows);
        Mockito.when(windowClient.fetchSegment(
                Mockito.anyString(), Mockito.anyString(),
                Mockito.any(Instant.class), Mockito.any(Instant.class),
                Mockito.any(), Mockito.anyInt()))
            .thenReturn(rows);
    }

    @Test
    void batchReplayCompilesAVersionThatNeedsItsAliasSet() {
        stubTarget(new PolicyVersionSourceClient.VersionSource(
            "pv-alias", "p-alias", 2, ALIASED_TARGET, null, ALIAS_SET));

        UUID batchId = runBatch();

        assertThat(itemColumn(batchId, "failure_kind"))
            .as("目标版本带 aliasSet 时必须能编译重跑，而不是归为目标编译错误")
            .isEqualTo("null");
        assertThat(itemColumn(batchId, "success")).isEqualTo("true");
        // 规则输出文本（不是准入决策）→ 成功重跑但不可比
        assertThat(itemColumn(batchId, "target_outcome")).isEqualTo("INDETERMINATE");
        JsonObject summary = summaryOf(batchId);
        assertThat(summary.getInteger("incomparable")).isEqualTo(1);
    }

    /** 对照：同一源码不带别名必须编译失败——证明上面的用例确实依赖 aliasSet 被传入。 */
    @Test
    void sameSourceWithoutItsAliasSetIsATargetCompileError() {
        stubTarget(new PolicyVersionSourceClient.VersionSource("pv-alias", "p-alias", 2, ALIASED_TARGET));

        UUID batchId = runBatch();

        assertThat(itemColumn(batchId, "success")).isEqualTo("false");
        assertThat(itemColumn(batchId, "failure_kind")).isEqualTo("TARGET_COMPILE_ERROR");
    }

    private void stubTarget(PolicyVersionSourceClient.VersionSource source) {
        Mockito.when(versionSourceClient.fetch(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(Optional.of(source));
    }

    private UUID runBatch() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            INSERT INTO replay_batch (id,tenant_id,user_id,policy_id,base_version_id,
              target_version_id,window_kind,window_label,window_timezone,window_from,
              window_to,planned_count,status,toolchain_id,expires_at,concurrency_slot)
            VALUES (?1,'t-alias','u-alias','p-alias','pv-1','pv-alias','LAST_MONTH','近一个月','UTC',
              NOW() - INTERVAL '30 day', NOW(), 0, 'PENDING', 'tc',
              NOW() + INTERVAL '30 day', 0)
            """).setParameter(1, id).executeUpdate());
        ReplayBatchService.Claim claim = service.claimNextPending();
        assertThat(claim).isNotNull();
        assertThat(claim.batchId()).isEqualTo(id);
        service.runBatch(id);
        return id;
    }

    private String itemColumn(UUID batchId, String column) {
        Object v = QuarkusTransaction.requiringNew().call(() ->
            em.createNativeQuery("SELECT " + column + "::text FROM replay_batch_item"
                    + " WHERE batch_id = ?1 AND execution_id = 'a1'")
                .setParameter(1, batchId).getSingleResult());
        return String.valueOf(v);
    }

    private JsonObject summaryOf(UUID batchId) {
        Object raw = QuarkusTransaction.requiringNew().call(() ->
            em.createNativeQuery("SELECT result_summary::text FROM replay_batch WHERE id = ?1")
                .setParameter(1, batchId).getSingleResult());
        assertThat(raw).as("批次必须完成并产出摘要").isNotNull();
        return new JsonObject(String.valueOf(raw));
    }
}
