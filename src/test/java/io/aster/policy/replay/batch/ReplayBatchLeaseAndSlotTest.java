package io.aster.policy.replay.batch;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 崩溃恢复（P0-4）与并发槽位（P0-5）。
 *
 * <h2>P0-4：进程崩溃 = 批次永久卡死 + 租户额度永久被占</h2>
 *
 * <p>领取只查 {@code status='PENDING'}，异常处理只覆盖<b>当前进程捕获到的</b>异常。
 * 于是「PENDING→RUNNING 已提交、worker 尚未完成、进程没了」这条路径无人负责：
 * 没有 lease、没有心跳、没有超时回收。类注释曾声称「进程重启后自然接管」——
 * 实现并非如此（又一处「注释声称 ≠ 实现如此」）。
 *
 * <p>后果不止是这个批次不出结果：它<b>持续占着租户的并发额度</b>
 * （pro 档只有 1 个），该租户从此发不出任何 What-If 批次。
 *
 * <h2>P0-5：先查后写 = 上限形同虚设</h2>
 *
 * <p>先 SELECT 活跃数、再 INSERT，两步之间没有互斥；两个并发请求都读到未满，
 * 于是都插入成功。且原实现按 {@code user_id} 统计，而额度语义是<b>租户级</b>——
 * 同租户多用户各开一个即可绕过。
 */
class ReplayBatchLeaseAndSlotTest {

    private static ReplayBatchEntity batch(ReplayBatchStatus status) {
        ReplayBatchEntity b = new ReplayBatchEntity();
        b.id = UUID.randomUUID();
        b.status = status;
        b.tenantId = "t1";
        b.userId = "u1";
        b.plannedCount = 10;
        b.concurrencySlot = 0;
        b.leaseExpiresAt = Instant.now().plusSeconds(600);
        return b;
    }

    // ── 终态必须释放槽位与租约 ────────────────────────────────────────────

    /**
     * ★终态释放并发槽——放在 {@code transitionTo} 里由<b>结构</b>保证。
     *
     * <p>四条终态路径（完成 / 拒答 / 空窗口 / 防御性失败）漏掉任何一条
     * 都会让租户额度被永久吃掉；靠每个调用点自觉不可靠。
     */
    @Test
    void 标记完成必须释放并发槽与租约() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.RUNNING);
        b.completedCount = 10;
        b.failedCount = 0;

        b.markCompleted("{\"changed\":1}");

        assertThat(b.concurrencySlot)
            .as("★终态仍占槽会让租户额度被永久吃掉（pro 档只有 1 个）")
            .isNull();
        assertThat(b.leaseExpiresAt).isNull();
    }

    @Test
    void 标记失败必须释放并发槽与租约() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.RUNNING);

        b.markFailed("{\"TIMEOUT\":3}");

        assertThat(b.concurrencySlot).isNull();
        assertThat(b.leaseExpiresAt).isNull();
    }

    /** PENDING 直接失败（开跑前就失败）同样要释放槽位。 */
    @Test
    void 未开跑即失败也必须释放并发槽() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.PENDING);
        b.leaseExpiresAt = null;   // PENDING 尚未持有租约

        b.markFailed("{\"UNKNOWN\":0}");

        assertThat(b.concurrencySlot).isNull();
    }

    // ── 源码级约束：这些是「不能被顺手改掉」的接线 ──────────────────────

    private static String serviceSource() throws Exception {
        return Files.readString(
            Path.of("src/main/java/io/aster/policy/replay/batch/ReplayBatchService.java"));
    }

    @Test
    void 领取批次必须同时写入租约() throws Exception {
        assertThat(serviceSource())
            .as("★RUNNING 必须持有租约——没有租约的 RUNNING 就是崩溃卡死的形态"
                + "（DB 层 CHECK 也会拒绝）")
            .contains("leaseExpiresAt = ?3");
    }

    @Test
    void 必须存在过期租约回收逻辑() throws Exception {
        String src = serviceSource();

        assertThat(src)
            .as("★没有回收，进程崩溃就等于批次永久卡死 + 额度永久被占")
            .contains("reclaimStaleLeases");
        assertThat(src)
            .as("回收条件必须是「RUNNING 且租约已过期」")
            .contains("status = ?1 and leaseExpiresAt < ?2");
        assertThat(src)
            .as("★重试必须有上限：反复崩溃说明批次本身有问题，无限重试只会循环占额度")
            .contains("MAX_ATTEMPTS");
    }

    @Test
    void 调度器必须真的调用回收() throws Exception {
        String scheduler = Files.readString(
            Path.of("src/main/java/io/aster/policy/replay/batch/ReplayBatchScheduler.java"));

        assertThat(scheduler)
            .as("★回收逻辑写了但没人调用 = 死代码，崩溃卡死依然发生"
                + "（本仓有前科：PermitLease 曾整条是死代码，13 条测试全绿）")
            .contains("reclaimStaleLeases()");

        int reclaim = scheduler.indexOf("reclaimStaleLeases()");
        int claim = scheduler.indexOf("claimNextPending()");
        assertThat(reclaim)
            .as("回收必须在领取**之前**：否则本轮领不到被卡死批次腾出的名额")
            .isLessThan(claim);
    }

    // ── 并发上限口径 ──────────────────────────────────────────────────────

    @Test
    void 并发上限必须按租户统计而非按用户() throws Exception {
        String resource = Files.readString(
            Path.of("src/main/java/io/aster/policy/rest/ReplayBatchResource.java"));

        assertThat(resource)
            .as("★额度是**租户级**的（§7.2）；按 userId 统计时"
                + "同租户多用户各开一个即可绕过上限")
            .contains("\"tenantId = ?1 and status in ?2\"");
        assertThat(resource)
            .as("不得再按 userId 统计活跃批次")
            .doesNotContain("\"userId = ?1 and status in ?2\"");
    }

    @Test
    void 创建批次必须占槽且唯一约束冲突转409() throws Exception {
        String resource = Files.readString(
            Path.of("src/main/java/io/aster/policy/rest/ReplayBatchResource.java"));

        assertThat(resource)
            .as("★活跃批次必须占槽——(tenant_id, slot) 唯一索引是唯一能堵住 TOCTOU 的机制")
            .contains("batch.concurrencySlot = firstFreeSlot(active)");
        assertThat(resource)
            .as("★必须 flush 才能在此处捕获唯一约束冲突；"
                + "延迟到事务提交时抛出会变成 500")
            .contains("persistAndFlush()");
        assertThat(resource)
            .as("★抢槽失败对调用方就是「已有批次在跑」，应为 409 而非 500")
            .containsPattern("(?s)catch \\(jakarta\\.persistence\\.PersistenceException.*?CONFLICT");
    }
}
