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
    void 并发超限返回409且只带批次id不带进度() throws Exception {
        String body = createBody(source());
        int concurrencyCheck = body.indexOf("concurrentReplayBatches()");
        assertThat(concurrencyCheck).as("必须查并发上限").isGreaterThan(0);

        String after = body.substring(concurrencyCheck);
        assertThat(after)
            .as("★并发超限必须返回 409 CONFLICT")
            .contains("Response.Status.CONFLICT");
        assertThat(after)
            .as("★带当前批次 id——前端要能接管并显示进度")
            .contains("currentBatchId");

        // ★本用例此前还断言 409 含 plannedCount 与 completedCount，
        //   而那两个字段正是 §1.1 的泄漏（相减即得成功数），已被删除。
        //   它当时仍全绿——只因为那两个词还出现在**解释为什么删掉它们**的注释里。
        //   「名称承诺 ≠ 断言体」的又一例：测试名说「带进度」，
        //   而实现刻意不带进度，测试却因为扫到注释里的词而通过。
        //   现在改为断言它们**确实不在** put 调用中。
        assertThat(after)
            .as("★409 不得下发 plannedCount/completedCount：两者相减即得成功数")
            .doesNotContain("\"plannedCount\", current.plannedCount")
            .doesNotContain("\"completedCount\", current.completedCount");
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
    void 并发查询必须按租户隔离且口径为租户级() throws Exception {
        // 隔离诉求不变：不能把别的租户的批次算进本租户额度，
        // 更不能让调用方看到别的租户的批次 id。
        //
        // ★但口径必须是**租户级**而非用户级（ADR 0034 §7.2）：
        //   额度按租户售卖，原实现按 userId 统计，
        //   同租户多个用户各开一个即可绕过上限。
        //   本用例此前钉死 "userId = ?1"，等于把这个 bug 写进了契约。
        String body = createBody(source());
        assertThat(body)
            .as("★并发查询必须按 tenantId 过滤——额度是租户级的")
            .contains("tenantId = ?1");
        assertThat(body)
            .as("★不得再按 userId 统计活跃批次：同租户多用户可绕过上限")
            .doesNotContain("\"userId = ?1 and status in ?2\"");
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

        // ★2026-08-17 安全审计：此前断言 `userId = ?2` 并称之为「租户隔离」——
        //   但 userId **不是** tenantId，且它来自 RequestIdentityResolver.performedBy()，
        //   该函数在无 JAX-RS property 时回退读客户端的 X-User-Id 头
        //   （其自身文档明写「授权判断一律不得依赖它」）。
        //   真正的租户隔离必须以 tenantId 为谓词，userId 仅作纵深防御。
        //   同时断言参数序号会让「加一个谓词」这种正确修复反而挂掉，故改为
        //   只断言**语义不变量**：谓词里必须出现 tenantId。
        assertThat(getBody)
            .as("★查询必须带 tenantId——这才是租户隔离（userId 来自可伪造的 X-User-Id 头）")
            .contains("tenantId = ?");
        assertThat(getBody)
            .as("★userId 保留为纵深防御")
            .contains("userId = ?");
        assertThat(getBody)
            .as("★不属于本用户的批次返回 404 而非 403——"
                + "403 会泄露「这个批次存在」，让端点变成存在性探针")
            .contains("Response.Status.NOT_FOUND");
    }

    @Test
    void 拒答的批次不返回任何计数() throws Exception {
        // ★§1.1：FAILED 只给失败**类别**，既不给总体量也不给每类条数。
        //
        // ★这条用例本身曾是本仓最典型的假绿：它用 indexOf 切出
        //   [case FAILED ->, case EXPIRED) 这个 192 字符窗口，
        //   而真正的泄漏（无条件 put("plannedCount", ...)）在 switch **之前**，
        //   结构上就在窗口外——实测注入一个字面的 successCount 它照样全绿。
        //   窗口边界是人选的，而 bug 恰好爱待在边界外。
        //
        //   真正的行为约束现由 ReplayBatchResponseContractTest 断言**真实输出的 key**；
        //   这里只保留「分支内不得出现计数」这一条结构性检查，不再冒充完整守护。
        String src = source();
        int failedCase = src.indexOf("case FAILED ->");
        assertThat(failedCase).isGreaterThan(0);
        String branch = src.substring(failedCase, src.indexOf("case EXPIRED", failedCase));

        assertThat(branch)
            .as("★只给失败类别（failureKinds），不给 {类别:条数} 的分布")
            .contains("failureKinds");

        // ★断言的是 **body.put 调用**，不是「分支里不出现这些词」——
        //   分支内的注释正是在解释「为什么不下发 plannedCount」，
        //   按裸词断言会被自己的注释判负（实测撞过一次）。
        //   这与本文件顶部记的教训同源：扫文本时，边界与词形都是人选的。
        assertThat(branch)
            .as("★拒答分支不得 put 任何计数字段")
            .doesNotContain("body.put(\"completedCount\"")
            .doesNotContain("body.put(\"processedCount\"")
            .doesNotContain("body.put(\"plannedCount\"");
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
