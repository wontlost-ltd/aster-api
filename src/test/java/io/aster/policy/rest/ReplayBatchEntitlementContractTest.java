package io.aster.policy.rest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次创建的权益与并发契约（ADR 0034 §7.2）。
 *
 * <p><b>为什么用结构断言而非 REST 调用</b>：这条契约的本质是
 * 「**先查权益、再查并发**，且两者返回不同状态码」——一个**顺序与分支**约束。
 * 用 {@code @QuarkusTest} 测需要 mock 掉 PlanGate 与整条身份链，
 * 反而把测试变成对 mock 的断言。真正的运行时行为由 S3 的集成测试覆盖。
 *
 * <p>本测试锁住的是：将来有人调整这段逻辑时，如果把顺序调反或把状态码统一，
 * 断言会失败并提醒他为什么不能那么做。
 */
class ReplayBatchEntitlementContractTest {

    private static String source() throws Exception {
        return Files.readString(
            Path.of("src/main/java/io/aster/policy/rest/ReplayBatchResource.java"));
    }

    /** 截取 create 方法体（到下一个 @GET 之前）。 */
    private static String createBody(String src) {
        int start = src.indexOf("public Response create(");
        assertThat(start).as("找不到 create 方法").isGreaterThan(0);
        int next = src.indexOf("@GET", start);
        return next > 0 ? src.substring(start, next) : src.substring(start);
    }

    @Test
    void 无权益返回403而非409() throws Exception {
        // ★free 档 concurrentReplayBatches=0 表示「没买这个功能」，
        //   不是「现在不能再开一个」。返回 409 会让前端提示「等一会儿」，
        //   而用户等多久都没用。
        String body = createBody(source());
        int entitlementCheck = body.indexOf("allowsReplayBatch()");
        assertThat(entitlementCheck).as("必须查权益").isGreaterThan(0);

        String afterCheck = body.substring(entitlementCheck, Math.min(entitlementCheck + 600, body.length()));
        assertThat(afterCheck)
            .as("★无权益必须返回 403 FORBIDDEN")
            .contains("Response.Status.FORBIDDEN");
        assertThat(afterCheck)
            .as("★并给出升级引导标记，让前端能区分「去升级」与「等一会儿」")
            .contains("upgrade");
    }

    @Test
    void 并发超限返回409且带当前批次进度() throws Exception {
        String body = createBody(source());
        int concurrencyCheck = body.indexOf("concurrentReplayBatches()");
        assertThat(concurrencyCheck).as("必须查并发上限").isGreaterThan(0);

        String after = body.substring(concurrencyCheck);
        assertThat(after)
            .as("★并发超限必须返回 409 CONFLICT")
            .contains("Response.Status.CONFLICT");
        assertThat(after)
            .as("★带当前批次 id 与进度——前端要能显示「正在跑，还剩 N 条」")
            .contains("currentBatchId")
            .contains("plannedCount")
            .contains("completedCount");
    }

    @Test
    void 权益检查必须在并发检查之前() throws Exception {
        // ★顺序反了的话，free 租户在自己已有批次时会拿到 409——
        //   提示「等一会儿」，而实际上他等多久都没用
        String body = createBody(source());
        int entitlement = body.indexOf("allowsReplayBatch()");
        int concurrency = body.indexOf("concurrentReplayBatches()");

        assertThat(entitlement).isGreaterThan(0);
        assertThat(concurrency).isGreaterThan(0);
        assertThat(entitlement)
            .as("★权益（403）必须先于并发（409）检查")
            .isLessThan(concurrency);
    }

    @Test
    void 并发计数只数活跃状态() throws Exception {
        // 终态批次不该继续占额度，否则用户跑过一次就永远开不了新的
        String body = createBody(source());
        assertThat(body)
            .as("并发查询只应统计 PENDING/RUNNING")
            .contains("ReplayBatchStatus.PENDING")
            .contains("ReplayBatchStatus.RUNNING");
        assertThat(body)
            .as("★不得把终态也算进并发额度")
            .doesNotContain("ReplayBatchStatus.COMPLETED")
            .doesNotContain("ReplayBatchStatus.FAILED");
    }

    @Test
    void 并发查询必须带userId() throws Exception {
        // 租户隔离：不能把别人的批次算进本用户的并发额度，
        // 更不能让本用户看到别人的批次 id
        String body = createBody(source());
        assertThat(body)
            .as("★并发查询必须按 userId 过滤")
            .contains("userId = ?1");
    }

    @Test
    void enterprise不限并发时跳过上限判断() throws Exception {
        String body = createBody(source());
        assertThat(body)
            .as("hasUnlimitedReplayBatches 用于 enterprise 的 -1 语义")
            .contains("hasUnlimitedReplayBatches()");
    }

    @Test
    void 查询端点按userId过滤且不存在返回404() throws Exception {
        String src = source();
        int getStart = src.indexOf("public Response get(");
        assertThat(getStart).isGreaterThan(0);
        String getBody = src.substring(getStart);

        assertThat(getBody)
            .as("★查询必须带 userId——租户隔离")
            .contains("userId = ?2");
        assertThat(getBody)
            .as("★不属于本用户的批次返回 404 而非 403——"
                + "403 会泄露「这个批次存在」，让端点变成存在性探针")
            .contains("Response.Status.NOT_FOUND");
    }

    @Test
    void 拒答的批次不返回任何计数() throws Exception {
        // ★§1.1：FAILED 只给失败原因分布，不给 completedCount——
        //   后者会诱导前端自行计算成功率
        String src = source();
        int failedCase = src.indexOf("case FAILED ->");
        assertThat(failedCase).isGreaterThan(0);
        String branch = src.substring(failedCase, src.indexOf("case EXPIRED", failedCase));

        assertThat(branch).contains("failureReasons");
        assertThat(branch)
            .as("★拒答分支不得返回 completedCount / processedCount")
            .doesNotContain("completedCount")
            .doesNotContain("processedCount");
    }

    @Test
    void 进行中只给已处理数不给成功数() throws Exception {
        // ★§7.4：进度条不得显示「已成功 N 条」，
        //   否则用户会在批次跑完前自行推断结论
        String src = source();
        int running = src.indexOf("case PENDING, RUNNING ->");
        assertThat(running).isGreaterThan(0);
        String branch = src.substring(running, src.indexOf("case COMPLETED", running));

        assertThat(branch).contains("processedCount");
        assertThat(branch)
            .as("★不得单独暴露成功数")
            .doesNotContain("\"completedCount\"")
            .doesNotContain("\"failedCount\"");
    }
}
