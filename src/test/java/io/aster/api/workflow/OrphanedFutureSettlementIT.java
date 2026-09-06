package io.aster.api.workflow;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>跨副本孤儿 future</b> 的结算（issue #302 H1）。
 *
 * <p>★被修的缺陷：{@code resultFutures} 是 per-副本的 {@code ConcurrentHashMap}。
 * {@code aster-api} 跑 4 副本，请求落在副本 A 时在 A 上建 future 并等待，
 * 而 workflow 可能由副本 B 的调度器执行、在 <b>B 自己的 map</b> 上 complete。
 * A 的 future <b>永远等不到</b>，直到 24 小时 TTL 才以
 * {@code WorkflowExpiredException} 收场 —— 一个明明成功了的 workflow，
 * 调用方却卡了一天然后收到「过期」。
 *
 * <p>★<b>怎么模拟另一个副本</b>：本副本建 future 后，
 * 只<b>直接改数据库</b>把 workflow 置为终态，而<b>不</b>调用本副本的
 * {@code completeWorkflow}。这正是「另一个副本干完了活」在本副本看到的样子——
 * DB 是终态，本地 map 毫不知情。
 */
@QuarkusTest
@TestProfile(OrphanedFutureSettlementIT.SettlerOnlyProfile.class)
class OrphanedFutureSettlementIT {

    /** 只留结算任务，关掉别的后台调度器，避免它们改动 workflow 状态干扰断言。 */
    public static class SettlerOnlyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.scheduler.enabled", "true",
                "aster.scheduler.background.enabled", "true",
                "workflow.scheduler.polling.enabled", "false");
        }
    }

    @Inject
    PostgresWorkflowRuntime runtime;

    @AfterEach
    void cleanup() {
        // ★不能写成 WorkflowStateEntity::deleteAll —— 方法引用会绑到未被
        //   Panache 增强的基类方法上，运行时抛「did you forget to annotate...」。
        QuarkusTransaction.requiringNew().run(() -> WorkflowStateEntity.deleteAll());
    }

    /**
     * ★核心断言：另一个副本完成 workflow 后，本副本的 future 必须<b>及时</b>拿到结果。
     *
     * <p>缺陷版下本用例会超时——future 永远不 complete。
     */
    @Test
    @DisplayName("另一副本完成 workflow 后，本副本的 future 必须被结算为成功")
    void completedOnAnotherReplicaSettlesLocalFuture() throws Exception {
        UUID wf = UUID.randomUUID();
        seedState(wf, "RUNNING", null);

        // 本副本建 future 并等待（等同于请求落在副本 A）
        CompletableFuture<Object> future = runtime.getResultFuture(wf.toString());
        assertThat(future.isDone())
            .as("刚建的 future 不应已完成")
            .isFalse();

        // 「副本 B」干完了活：只落库，不碰本副本的内存 map
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowStateEntity s = WorkflowStateEntity.findById(wf);
            s.status = "COMPLETED";
            s.result = "{\"ok\":true}";
            s.updatedAt = Instant.now();
            s.persist();
        });

        Object result = future.get(30, TimeUnit.SECONDS);   // 结算任务每秒跑一次

        // ★按语义比而非按字节：result 列是 jsonb，PG 会把 {"ok":true} 规范化成
        //   {"ok": true}（加空格）。按字面量断言会因存储层的格式化而误报。
        assertThat(result)
            .as("★必须拿到另一副本写进 DB 的结果。等不到 = 调用方要卡满 24h TTL "
                + "才收到 WorkflowExpiredException（issue #302 H1）")
            .isNotNull();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(String.valueOf(result)))
            .as("★结果内容必须与另一副本写入的一致")
            .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"ok\":true}"));
    }

    /** 另一副本上失败的 workflow，本副本的 future 必须以异常结算，而不是继续挂着。 */
    @Test
    @DisplayName("另一副本失败的 workflow，本副本 future 必须异常结算")
    void failedOnAnotherReplicaSettlesLocalFutureExceptionally() {
        UUID wf = UUID.randomUUID();
        seedState(wf, "RUNNING", null);

        CompletableFuture<Object> future = runtime.getResultFuture(wf.toString());

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowStateEntity s = WorkflowStateEntity.findById(wf);
            s.status = "FAILED";
            s.updatedAt = Instant.now();
            s.persist();
        });

        assertThatThrownBy(() -> future.get(30, TimeUnit.SECONDS))
            .as("★失败也必须送达——挂着不动比报错更糟，调用方无从判断")
            .isInstanceOf(ExecutionException.class);
    }

    /**
     * ★反向守卫：workflow 仍在运行时，future <b>不得</b>被提前结算。
     *
     * <p>没有这条，「无脑 complete 所有 future」也能让上面两条变绿，
     * 却会把还没跑完的 workflow 谎报成已完成。
     */
    @Test
    @DisplayName("workflow 仍在运行时不得提前结算 future")
    void runningWorkflowMustNotBeSettled() {
        UUID wf = UUID.randomUUID();
        seedState(wf, "RUNNING", null);

        CompletableFuture<Object> future = runtime.getResultFuture(wf.toString());

        assertThatThrownBy(() -> future.get(4, TimeUnit.SECONDS))
            .as("★RUNNING 的 workflow 不得被结算——提前 complete 等于谎报完成")
            .isInstanceOf(TimeoutException.class);
    }

    private void seedState(UUID wf, String status, String result) {
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowStateEntity s = new WorkflowStateEntity();
            s.workflowId = wf;
            s.status = status;
            s.result = result;
            s.snapshot = "{}";
            s.createdAt = Instant.now();
            s.updatedAt = Instant.now();
            s.persist();
        });
    }
}
