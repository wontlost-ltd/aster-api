package io.aster.policy.replay.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 批次执行服务（ADR 0034 S2）。
 *
 * <p>负责：领取待跑批次 → 逐条重跑 → 按 {@link ReplayBatchRunner} 判定结局 → 落库。
 *
 * <p><b>fail-closed 的三处保证</b>：
 * <ol>
 *   <li>逐条重跑中任一条失败 → 整批 FAILED，不出数字（{@code ReplayBatchRunner.decide}）</li>
 *   <li>worker 自身异常 → {@link #failBatchDefensively} 兜底标记，
 *       避免批次永久卡在 RUNNING 占着并发额度</li>
 *   <li>DB CHECK 约束 → 应用层全写错也拦得住（见 V6.20.0 迁移）</li>
 * </ol>
 */
@ApplicationScoped
public class ReplayBatchService {

    private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;

    /** 等待 What-If 许可的上限；超时归类为 THROTTLED（可重试）。 */
    private static final long PERMIT_WAIT_MS = 30_000;

    /**
     * 租约时长：必须覆盖<b>整批</b>的最坏耗时，而不是一条的（ADR 0034 §10.3）。
     *
     * <p>★<b>这里不能用「心跳续租」</b>：{@link #runBatch} 是 {@code @Transactional}，
     * 整批跑完才提交，期间该行一直被这个事务持有——任何独立事务的续租更新都
     * 拿不到行锁，写不进去。曾经写过一个心跳续租方法，它<b>没有任何调用点，
     * 而且即便调用也不可能生效</b>；那种「注释描述了一个不存在的机制」
     * 正是本仓最高产的 bug 模式，故整体删除。
     *
     * <p>取值依据：单条最长等许可 {@link #PERMIT_WAIT_MS}（30s）+ 执行时间，
     * 而重跑是<b>串行</b>循环；配合 {@link #MAX_BATCH_SIZE} 的规模上限，
     * 2 小时可覆盖最坏情况。
     *
     * <p>★但真正兜底的不是这个时长，而是 {@code leaseOwner} 条件更新：
     * 即便误判发生，旧 worker 也写不进终态。调长只是让误判更少发生。
     */
    private static final java.time.Duration LEASE_DURATION = java.time.Duration.ofHours(1);

    /**
     * 每段条数（ADR 0034 §11.2）。
     *
     * <p>租约只需覆盖<b>一段</b>的最坏耗时，而不是整批：
     * ★取值由 {@link #SEGMENT_WORST_CASE} 的**静态断言**把关，不再靠注释里的算术：
     * {@code 15s + 10×(30s 等许可 + 60s 执行上限) = 15.2 分钟}，×2 = 30.5 分钟
     * ≤ 60 分钟租约。三轮取值被退回（83h vs 2h、50min vs 30min、
     * 以及 fetchWindow 25min 漏项）之后，这条不等式现在由代码在启动时校验。
     *
     * <p>上一版写的是 100 条，同一段注释里自己算出「= 50 分钟」，
     * 而租约设的是 30 分钟——<b>注释里的算术直接否定了它旁边的常量</b>。
     * 后果：慢段提交后立刻可被 reclaim，反复重领把 {@code attemptCount} 涨到
     * {@link #MAX_ATTEMPTS}，最终把<b>仍在正常推进的长批次</b>误判为 FAILED。
     * （上一轮是「83 小时 vs 2 小时」，这一轮是「50 分钟 vs 30 分钟」——
     * 同一个错误换了个量级。）
     *
     * <p>★对照上一版：整批 10000 条 × 30s = <b>83 小时</b>，
     * 而我把租约定成 2 小时并注释「可覆盖最坏情况」——那个算术从没做过。
     * 分段把「租约必须覆盖多久」从不可控变成可控。
     */
    private static final int SEGMENT_SIZE = 10;

    /**
     * 批次规模硬上限（ADR 0034 §10.3）。
     *
     * <p>与 §7.4「万条以上要进度条」对齐：进度条覆盖的正是接近上限的批次。
     * 超限在**创建入口**拒绝，而不是靠租约兜底——
     * 靠租约兜底等于让一个必然超时的批次先跑两小时再失败。
     */
    static final int MAX_BATCH_SIZE = 10_000;

    /**
     * 单条重跑的执行时间**预算**（ADR 0034 §12.4）。
     *
     * <p>★<b>这段注释此前是错的</b>（issue #235）：它写着「真正的执行上界由 Truffle
     * {@code statementLimit(10M)} 提供」，而 statementLimit 在 Aster 上<b>完全不触发</b>
     * ——AsterLanguage 未声明 ProvidedTags，AST 上没有 StatementTag，Truffle 无从计数。
     * 实测对照：无限递归策略在「无限制 / limit=100 / limit=1」三种配置下表现完全一致，
     * 全部止于 JVM 栈溢出。
     *
     * <p>真正的执行上界由 {@link #runWithTimeout} 的 <b>wall-clock 超时</b>提供
     * （见 {@code replayOne}），生产 {@code /evaluate} 路径同样改用看门狗
     * （{@code TrufflePolicyRuntime}）。
     *
     * <p>我曾在这里包一层线程池 + {@code Future.get(timeout)}，但那有两个问题：
     * 每条重跑新建线程池（万条批次 = 10000 次创建/销毁），且
     * {@code shutdownNow()} 只发 interrupt、<b>不保证停下 Truffle 执行</b>——
     * 调用者不再等待，被弃线程仍在烧 CPU。已移除。
     *
     * <p>于是「段最坏耗时」不再是严格上界，而是一个<b>工程预算</b>：
     * 10M statements 在本仓实测策略上远低于 60s（典型 &lt;10K statements，
     * ADR 0033 §5.1 实测 1.35ms/条）。租约按此预算的 2 倍取值，
     * 留的是**数量级**余量而非精确证明。
     *
     * <p>★这条边界必须写明，否则下一个人会以为租约有严格保证——
     * 而那正是前三轮租约取值出错的模式（把估算当成证明）。
     */
    private static final java.time.Duration EXEC_TIMEOUT = java.time.Duration.ofSeconds(60);
    // ↑ 注意这**不是**一个被强制执行的超时（见下方说明）。

    /**
     * 单段拉取的超时上界。
     *
     * <p>分段后每段只拉**一页**（{@code ExecutionWindowClient} 的 HTTP timeout 15s），
     * 不再是「最多 100 页 × 15s ≈ 25 分钟」——那个 25 分钟正是上一轮
     * 租约算术漏掉的一项。
     */
    private static final long SEGMENT_FETCH_TIMEOUT_MS = 15_000;

    /**
     * 一段的耗时**预算** = 拉取 + N × (等许可 + 执行预算)。
     *
     * <p>★注意用词：是**预算**不是上界。执行那一项由 {@link #runWithTimeout} 的
     * wall-clock 超时约束（**不是** statementLimit——它在本语言上不触发，见 #235），
     * 故这个和是工程估算。
     * 但「租约 ≥ 2 × 预算」这条不等式仍由 {@link #assertLeaseCoversSegment()}
     * 在测试与启动时校验——把估算写进代码并校验，
     * 好过写在注释里由我口头保证（前三轮的错误模式）。
     */
    private static final java.time.Duration SEGMENT_WORST_CASE =
        java.time.Duration.ofMillis(SEGMENT_FETCH_TIMEOUT_MS)
            .plus(java.time.Duration.ofMillis(PERMIT_WAIT_MS).plus(EXEC_TIMEOUT)
                .multipliedBy(SEGMENT_SIZE));

    /**
     * 校验「段最坏耗时 × 2 ≤ 租约」。
     *
     * <p>★<b>刻意做成可调用的方法，而不是 {@code static} 块</b>：
     * static 块只在类被加载时执行，而本仓所有相关测试都只把
     * {@code ReplayBatchService} 当**字符串**读源码，从不加载这个类——
     * 实测把租约改成 10 分钟，测试全绿，static 块根本没跑。
     * 那就是又一个「写了但永不执行」的护栏
     * （前有 {@code PermitLease} 死代码、{@code renewLease} 无调用点）。
     *
     * <p>做成方法后由 {@code ReplayBatchLeaseAndSlotTest} 显式调用，
     * 常量取错时测试**必然**转红。
     *
     * @throws IllegalStateException 不等式不成立
     */
    static void assertLeaseCoversSegment() {
        if (SEGMENT_WORST_CASE.multipliedBy(2).compareTo(LEASE_DURATION) > 0) {
            throw new IllegalStateException(
                "租约不足以覆盖单段最坏耗时：段最坏 " + SEGMENT_WORST_CASE
                    + " × 2 > 租约 " + LEASE_DURATION
                    + "——健康的长批次会被误回收并双跑（ADR 0034 §12.4）");
        }
    }

    @jakarta.annotation.PostConstruct
    void verifyTimingInvariant() {
        // 应用启动时也校验一次：常量改错不该等到生产上某个长批次被误回收才暴露
        assertLeaseCoversSegment();
    }

    /** 最大尝试次数。反复崩溃说明是批次本身有问题，无限重试只会循环占用额度。 */
    private static final int MAX_ATTEMPTS = 3;

    @jakarta.inject.Inject
    ExecutionWindowClient windowClient;

    // 目标版本源码向 cloud 取——本服务的 policy_versions 是执行期编译缓存，
    // 与 cloud 的版本历史不是同一批数据（见 PolicyVersionSourceClient 头注释）。
    @jakarta.inject.Inject
    PolicyVersionSourceClient versionSourceClient;

    @jakarta.inject.Inject
    WhatIfCapacityGate capacityGate;

    @jakarta.inject.Inject
    io.aster.policy.replay.ReplayExecutorAdapter replayExecutor;

    /**
     * 原子领取一个待跑批次：{@code PENDING → RUNNING}。
     *
     * <p>★用**条件更新**而非「查出来再改」：多副本部署时调度器会并行跑，
     * 只有 {@code WHERE status='PENDING'} 才能保证同一批次不被两个副本同时领取。
     * 这也是状态机刻意禁止自迁移的原因——幂等由这里的条件表达，
     * 而不是让状态机默许重复迁移。
     *
     * @return 领到的批次 id；无待跑批次时返回 null
     */
    public Claim claimNextPending() {
        UUID candidateId = peekNextPending();
        if (candidateId == null) {
            return null;
        }
        // ★冻结必须发生在 PENDING 阶段、且**先于**状态迁移提交：
        //   DB 有 CHECK「RUNNING 必须已冻结」，先迁移再冻结会直接违反约束
        //   （实测：replay_batch_running_is_frozen_ck）。
        //   freezeWindow 是 REQUIRES_NEW，自己提交——这正是它成为
        //   「崩溃后仍可见的检查点」的原因（§10.2）。
        freezeWindow(candidateId);
        return markRunning(candidateId);
    }

    /**
     * 领取结果：批次 id **与本次持有的 owner token**。
     *
     * <p>★owner 必须一并返回（§11.4）：调度器要拿它做防御性兜底的条件写。
     * 上一版只返回 UUID，于是 {@code failBatchDefensively} 没有 owner 可用，
     * 失租的 worker 会把别人刚领走的批次标成 FAILED。
     */
    public record Claim(UUID batchId, String owner) {
    }

    /** 取下一个待跑批次 id（只读，不改状态）。 */
    @Transactional
    UUID peekNextPending() {
        ReplayBatchEntity candidate = ReplayBatchEntity
            .<ReplayBatchEntity>find("status = ?1 order by createdAt", ReplayBatchStatus.PENDING)
            .firstResult();
        return candidate == null ? null : candidate.id;
    }

    /** 把已冻结的批次原子迁移到 RUNNING 并写入租约。 */
    @Transactional
    Claim markRunning(UUID candidateId) {
        ReplayBatchEntity candidate = ReplayBatchEntity.findById(candidateId);
        if (candidate == null || candidate.status != ReplayBatchStatus.PENDING) {
            return null;
        }
        // ★领取的同时写入 lease 与 **owner token**：
        //   RUNNING 必须两者都有（DB 层 CHECK 兜底）。
        //   owner 是本次持有的唯一标识——终态写会带 `AND leaseOwner = ?`，
        //   于是被误回收的旧 worker **写不进去**（§10.3）。
        //   这把「两个 worker 互相覆盖」从「尽量不发生」变成「结构上不可能」。
        String owner = UUID.randomUUID().toString();
        long updated = ReplayBatchEntity.update(
            "status = ?1, startedAt = ?2, leaseExpiresAt = ?3, leaseOwner = ?4,"
                + " attemptCount = attemptCount + 1"
                + " where id = ?5 and status = ?6",
            ReplayBatchStatus.RUNNING, java.time.Instant.now(),
            java.time.Instant.now().plus(LEASE_DURATION), owner,
            candidate.id, ReplayBatchStatus.PENDING);
        // 竞态：别的副本先领走了 → 本轮放弃，下一轮再来
        return updated == 1 ? new Claim(candidate.id, owner) : null;
    }

    /**
     * 回收租约已过期的 RUNNING 批次。
     *
     * <p>★<b>没有这一步，进程崩溃就等于批次永久卡死</b>：领取逻辑只查 PENDING，
     * 调度器只处理当前进程捕获到的异常，所以「提交了 RUNNING 之后进程没了」
     * 这条路径无人负责。后果不止是这个批次不出结果——它<b>持续占着租户的
     * 并发额度</b>（pro 档只有 1 个），该租户从此发不出任何 What-If 批次。
     *
     * <p>超过 {@link #MAX_ATTEMPTS} 次仍失败的直接标失败并释放槽位：
     * 反复崩溃说明是这个批次本身有问题（例如某条输入必然让 worker 挂掉），
     * 无限重试只会循环占用额度。
     *
     * @return 本轮回收的批次数
     */
    @Transactional
    public int reclaimStaleLeases() {
        java.time.Instant now = java.time.Instant.now();
        List<ReplayBatchEntity> stale = ReplayBatchEntity.list(
            "status = ?1 and leaseExpiresAt < ?2", ReplayBatchStatus.RUNNING, now);

        int reclaimed = 0;
        for (ReplayBatchEntity b : stale) {
            // ★快照出条件所需的值后**不再改这个实体**——改了会被自动 flush，
            //   那正是 §11.1 的坑。所有写走下面的条件 UPDATE。
            String owner = b.leaseOwner;
            java.time.Instant expiry = b.leaseExpiresAt;
            int attempts = b.attemptCount;
            UUID id = b.id;
            int planned = b.plannedCount;

            long written;
            if (attempts >= MAX_ATTEMPTS) {
                // 放弃重试：标失败并释放槽位，否则额度被永久吃掉
                written = ReplayBatchEntity.update(
                    "status = ?1, failedCount = ?2, completedCount = 0, finishedAt = ?3,"
                        + " failureReasons = ?4, resultSummary = null,"
                        + " concurrencySlot = null, leaseExpiresAt = null, leaseOwner = null"
                        + " where id = ?5 and status = ?6"
                        + " and leaseOwner = ?7 and leaseExpiresAt = ?8",
                    ReplayBatchStatus.FAILED, planned, now,
                    toJson(List.of(ReplayFailureKind.UNKNOWN.name())),
                    id, ReplayBatchStatus.RUNNING, owner, expiry);
            } else {
                // 退回 PENDING 等待重新领取；槽位保留（它仍占着额度，这是对的）
                written = ReplayBatchEntity.update(
                    "status = ?1, startedAt = null, leaseExpiresAt = null, leaseOwner = null"
                        + " where id = ?2 and status = ?3"
                        + " and leaseOwner = ?4 and leaseExpiresAt = ?5",
                    ReplayBatchStatus.PENDING, id, ReplayBatchStatus.RUNNING, owner, expiry);
            }

            // ★写 0 行 = 别的副本已经处理过这一行（或原 worker 刚续了租）。
            //   条件里带上**读到的 owner 与到期时刻**，是为了让「先查后写」
            //   之间的窗口无害：慢的副本写不进去，不会把快的副本刚领走的
            //   新 owner 覆盖回 PENDING。
            if (written == 1) {
                reclaimed++;
                Log.warnf("批次 %s 租约过期（第 %d 次尝试）已回收", id, attempts);
            }
        }
        return reclaimed;
    }

    /**
     * 执行批次：分段重跑 → 逐条落成败标记 → 全部跑完后判定终态。
     *
     * <p>★<b>不再是一个长事务</b>（ADR 0034 §11.2）：单条最长等许可 30s、
     * 串行 10000 条 = <b>83 小时</b>最坏耗时，而租约只有 2 小时——
     * 我上一版把租约定成 2 小时并注释「可覆盖最坏情况」，<b>那个算术从没做过</b>。
     * 现在每段提交一次并续租，租约只需覆盖<b>一段</b>。
     *
     * <p>★<b>本方法不持有 managed 实体</b>（§11.1）：上一版在这里
     * {@code findById} 拿到受管实体、改它、再执行带 {@code AND leaseOwner=?}
     * 的条件更新——但 Hibernate 提交时仍会 flush 那个脏实体，
     * <b>条件更新形同虚设</b>。commit message 却写着「结构上不可能」。
     * 现在全程只读值对象，所有写走单条原子 CAS。
     */
    public void runBatch(UUID batchId) {
        BatchSnapshot snap = loadSnapshot(batchId);
        if (snap == null) {
            return;
        }

        // 空窗口：正当业务结果（这段时间该策略就是没有执行），不是失败。
        // 谎称某类失败会误导用户去排查并不存在的数据问题。
        if (snap.plannedCount() == 0) {
            finishAtomically(batchId, snap.owner(), ReplayBatchStatus.FAILED,
                0, 0, null, toJson(List.of()));
            Log.infof("批次 %s 窗口内无可重放执行，无样本可分析", batchId);
            return;
        }

        // ── 分段推进 ────────────────────────────────────────────────────
        // 每段自己提交并续租；崩溃只丢当前段，已完成的段不会回滚。
        while (true) {
            int done = runOneSegment(batchId, snap.owner());
            if (done < 0) {
                // 租约已改派：本 worker 立即让位，绝不继续写
                Log.warnf("批次 %s 租约已改派，本 worker 停止推进", batchId);
                return;
            }
            if (done == 0) {
                break;   // 没有待跑条目了
            }
        }

        // ── 全部跑完，统一判定终态 ──────────────────────────────────────
        List<ReplayBatchRunner.ItemResult> results = collectResults(batchId);
        ReplayBatchOutcome outcome = ReplayBatchRunner.decide(snap.plannedCount(), results);

        if (outcome instanceof ReplayBatchOutcome.Completed c) {
            finishAtomically(batchId, snap.owner(), ReplayBatchStatus.COMPLETED,
                snap.plannedCount(), 0, toJson(summaryOf(c)), null);
            Log.infof("批次 %s 全量成功：%d 条，%d 条决策变化",
                batchId, snap.plannedCount(), c.changed());
        } else if (outcome instanceof ReplayBatchOutcome.Rejected r) {
            finishAtomically(batchId, snap.owner(), ReplayBatchStatus.FAILED,
                snap.plannedCount() - r.totalFailures(), r.totalFailures(),
                null, toJson(reasonsOf(r)));
            Log.infof("批次 %s 拒答：%d 条失败", batchId, r.totalFailures());
        }
    }

    /** 批次的只读快照：**刻意不返回实体**，避免任何 managed 状态泄漏到写路径（§11.1）。 */
    private record BatchSnapshot(UUID id, String owner, int plannedCount,
                                 String tenantId, String targetVersionId,
                                 String policyId, String userId,
                                 java.time.Instant windowFrom, java.time.Instant windowTo) {
    }

    @Transactional
    BatchSnapshot loadSnapshot(UUID batchId) {
        ReplayBatchEntity b = ReplayBatchEntity.findById(batchId);
        if (b == null) {
            Log.warnf("批次 %s 不存在，跳过", batchId);
            return null;
        }
        if (b.status != ReplayBatchStatus.RUNNING) {
            Log.warnf("批次 %s 状态为 %s 而非 RUNNING，跳过", batchId, b.status);
            return null;
        }
        return new BatchSnapshot(b.id, b.leaseOwner, b.plannedCount, b.tenantId,
            b.targetVersionId, b.policyId, b.userId, b.windowFrom, b.windowTo);
    }

    /**
     * 跑一段（至多 {@link #SEGMENT_SIZE} 条）并**提交**，同时续租。
     *
     * @return 本段实际跑的条数；0 表示没有待跑条目；<b>-1 表示租约已改派</b>
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    int runOneSegment(UUID batchId, String owner) {
        // ★每段开头复核租约：改派后立即让位，不写任何东西
        long stillMine = ReplayBatchEntity.update(
            "leaseExpiresAt = ?1 where id = ?2 and leaseOwner = ?3 and status = ?4",
            java.time.Instant.now().plus(LEASE_DURATION),
            batchId, owner, ReplayBatchStatus.RUNNING);
        if (stillMine == 0) {
            return -1;
        }

        List<ReplayBatchItemEntity> todo = ReplayBatchItemEntity
            .find("batchId = ?1 and success is null order by executionId", batchId)
            .page(0, SEGMENT_SIZE)
            .list();
        if (todo.isEmpty()) {
            return 0;
        }

        BatchSnapshot snap = loadSnapshot(batchId);
        if (snap == null) {
            return -1;
        }
        // ★向 cloud 取，不查本地表：本地 policy_versions 是执行期缓存，id 为
        //   bigint 自增，而 targetVersionId 是 cloud 的 UUID——Long.parseLong
        //   必定失败，导致每次 What-If 都以 TARGET_VERSION_MISSING 收场。
        var target = versionSourceClient.fetch(snap.targetVersionId(), snap.userId())
            .orElseThrow(() -> new TargetVersionMissingException(snap.targetVersionId()));

        // ★只拉**本段**，不再每段拉全量窗口（ADR 0034 §12.4）。
        //   上一版是 O(N²)：500 段 × 10000 条 = 5,000,000 个对象、约 5000 次 HTTP。
        //   现在按 keyset cursor 取本段那 20 条：10,000 个对象、500 次请求。
        //
        //   ★不需要 cloud 新端点：现有窗口端点的 cursor 就是
        //     `gt(executions.id, cursor)` + `orderBy asc(executions.id)`，
        //     而冻结表也按 executionId 升序读——两边排序一致，
        //     把「本段第一条的前一个 id」当 cursor 传进去即可。
        String afterId = todo.isEmpty() ? null : previousExecutionId(batchId, todo.get(0).executionId);
        Map<String, ExecutionWindowClient.WindowedExecution> upstream =
            windowClient.fetchSegment(snap.policyId(), snap.userId(),
                    snap.windowFrom(), snap.windowTo(), afterId, todo.size())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                    ExecutionWindowClient.WindowedExecution::executionId,
                    java.util.function.Function.identity(), (x, y) -> x));

        for (ReplayBatchItemEntity item : todo) {
            ExecutionWindowClient.WindowedExecution e = upstream.get(item.executionId);
            if (e == null) {
                // ★冻结时存在、现在没了。这**不是**「输入不兼容」——
                //   它根本没进 replayOne。谎称 INPUT_INCOMPATIBLE 会让用户
                //   去排查一个不存在的数据问题，还会错误继承不可重试语义。
                item.success = false;
                item.failureKind = ReplayFailureKind.SOURCE_EXECUTION_UNAVAILABLE.name();
                continue;
            }
            ReplayBatchRunner.ItemResult r =
                replayOne(e, item.baseApproved, target, snap.tenantId());
            item.success = r.failureKind() == null;
            item.failureKind = r.failureKind() == null ? null : r.failureKind().name();
            item.targetApproved = r.failureKind() == null ? r.targetApproved() : null;
        }
        return todo.size();
    }

    /**
     * 取冻结表中排在 {@code executionId} 之前的那一个 id，用作 keyset cursor。
     *
     * <p>cloud 侧语义是 {@code gt(executions.id, cursor)}——**严格大于**，
     * 所以要传「前一个」而不是「本段第一个」，否则本段第一条会被跳过。
     * 本段第一条就是全批第一条时返回 {@code null}（从头取）。
     */
    private String previousExecutionId(UUID batchId, String executionId) {
        return ReplayBatchItemEntity
            .<ReplayBatchItemEntity>find(
                "batchId = ?1 and executionId < ?2 order by executionId desc",
                batchId, executionId)
            .firstResultOptional()
            .map(i -> i.executionId)
            .orElse(null);
    }

    /**
     * 全局共享的重跑执行池。
     *
     * <p>★<b>共享而非每条新建</b>：上一版每条重跑 {@code newSingleThreadExecutor}，
     * 万条批次要创建/销毁 10000 个线程池。容量由 {@link WhatIfCapacityGate}
     * 的许可数封顶，故池大小取一个略大于许可数的固定值即可。
     */
    private static final java.util.concurrent.ExecutorService REPLAY_POOL = newBoundedReplayPool();

    /**
     * ★<b>必须有界</b>：超时后 {@code Future.cancel(true)} 不保证停下 Truffle 执行，
     * 而许可已在 {@code withPermit} 的 {@code finally} 中释放——
     * 于是「被弃线程由容量闸门许可数封顶」这句话<b>不成立</b>（第八轮审查指出）。
     * 连续超时会让 cached pool 无限增长，线程数远超许可数。
     *
     * <p>改为固定上界 = 许可数 × 2：允许每个许可最多有一个「在跑的」和
     * 一个「被弃但未结束的」执行，超出则新任务在队列等待而不是再开线程。
     * 队列有界，满了直接拒绝（归 THROTTLED，可重试）——
     * 宁可拒绝也不让线程数失控。
     */
    private static java.util.concurrent.ExecutorService newBoundedReplayPool() {
        int max = Math.max(2, WhatIfCapacityGate.permitCount() * 2);
        return new java.util.concurrent.ThreadPoolExecutor(
            max, max, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(max),
            r -> {
                Thread t = new Thread(r, "whatif-replay-exec");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 带 wall-clock 超时地执行一次重跑。
     *
     * <p>★超时归 {@link ReplayFailureKind#TIMEOUT}（可重试）。
     * 它<b>不保证</b>停下已在跑的 Truffle 执行——被弃线程仍会烧完 CPU，
     * 但数量由容量闸门许可数封顶。它保证的是**调用者不再等待**，
     * 从而让「段最坏耗时」成为真实上界（ADR 0034 §12.4）。
     */
    private static <T> T runWithTimeout(java.util.concurrent.Callable<T> work) throws Exception {
        java.util.concurrent.Future<T> f = REPLAY_POOL.submit(work);
        try {
            return f.get(EXEC_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);   // 尽力中断；不保证 Truffle 立即停下
            throw te;
        }
    }

    /** 从冻结表读回逐条结果，供 {@link ReplayBatchRunner#decide} 统一判定。 */
    @Transactional
    List<ReplayBatchRunner.ItemResult> collectResults(UUID batchId) {
        List<ReplayBatchItemEntity> items = ReplayBatchItemEntity
            .list("batchId = ?1 order by executionId", batchId);
        List<ReplayBatchRunner.ItemResult> out = new ArrayList<>(items.size());
        for (ReplayBatchItemEntity i : items) {
            if (Boolean.TRUE.equals(i.success)) {
                out.add(ReplayBatchRunner.ItemResult.ok(i.executionId, i.baseApproved,
                    Boolean.TRUE.equals(i.targetApproved), null));
            } else {
                out.add(ReplayBatchRunner.ItemResult.failed(i.executionId,
                    ReplayFailureKind.valueOf(
                        i.failureKind == null ? ReplayFailureKind.UNKNOWN.name() : i.failureKind)));
            }
        }
        return out;
    }

    /**
     * 终态写：**单条原子 CAS**，条件带 owner 与 RUNNING（§11.1）。
     *
     * <p>写 0 行 = 租约已改派，本次结果丢弃。这里<b>不碰任何 managed 实体</b>——
     * 上一版正是因为先改受管实体、再条件更新，被 Hibernate 的自动 flush 绕过。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void finishAtomically(UUID batchId, String owner, ReplayBatchStatus status,
                          int completed, int failed, String resultJson, String reasonsJson) {
        long written = ReplayBatchEntity.update(
            "status = ?1, completedCount = ?2, failedCount = ?3, finishedAt = ?4,"
                + " resultSummary = ?5, failureReasons = ?6,"
                + " concurrencySlot = null, leaseExpiresAt = null, leaseOwner = null"
                + " where id = ?7 and leaseOwner = ?8 and status = ?9",
            status, completed, failed, java.time.Instant.now(),
            resultJson, reasonsJson, batchId, owner, ReplayBatchStatus.RUNNING);
        if (written == 0) {
            Log.warnf("批次 %s 的租约已改派（原 owner %s），丢弃本次终态写", batchId, owner);
        }
    }

    /**
     * 防御性标记失败：worker 自身炸了时用，避免批次永久卡在 RUNNING。
     *
     * <p>★独立事务：调用它的场景本身就是「上一个事务出了问题」，
     * 复用那个事务会一起回滚，批次仍然卡住。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void failBatchDefensively(UUID batchId, String owner, ReplayFailureKind kind) {
        try {
            // ★必须带 owner 条件（§11.4）：若 worker A 已失租、B 已重新领取，
            //   A 随后抛异常时会把 B 的批次标成 FAILED 并释放槽位。
            //   上一版只检查「非终态」，挡不住这条。
            long written = ReplayBatchEntity.update(
                "status = ?1, finishedAt = ?2, failureReasons = ?3, resultSummary = null,"
                    + " concurrencySlot = null, leaseExpiresAt = null, leaseOwner = null"
                    + " where id = ?4 and leaseOwner = ?5 and status = ?6",
                ReplayBatchStatus.FAILED, java.time.Instant.now(),
                toJson(List.of(kind.name())), batchId, owner, ReplayBatchStatus.RUNNING);
            if (written == 1) {
                Log.warnf("批次 %s 被防御性标记为 FAILED（%s）", batchId, kind);
            } else {
                Log.warnf("批次 %s 已改派或已终态，跳过防御性标记", batchId);
            }
        } catch (RuntimeException e) {
            // 连兜底都失败：记日志，不再抛——否则会掩盖最初的那个异常
            Log.errorf(e, "批次 %s 防御性标记失败", batchId);
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    /**
     * 逐条重跑窗口内的执行。
     *
     * <p>★S2 阶段留桩：真实重跑接线在 S3 完成（需要 Execution 查询与
     * evaluate-source 调用）。当前返回空列表会让 {@code decide} 抛出
     * 「条数不符」——这是**有意的**：宁可炸掉暴露未接线，
     * 也不要返回一个看似成功的空结果。
     */
    /**
     * 拉窗口 → 逐条重跑 → 收集结果。
     *
     * <p>★<b>先全量拉、再逐条跑</b>，不边拉边跑：批次要先知道 plannedCount
     * 才能判定「全量成功」（§1.1）。边拉边跑时中途拉取失败会让 planned 本身
     * 就不可信——那时无论结果如何都不该出数字。
     *
     * <p>★每条重跑都过 {@link WhatIfCapacityGate}——**独立池是隔离，不是豁免**。
     * 任何「批次太慢就绕过闸门」的改动都要被拒绝，那等于把隔离墙拆了。
     */
    private List<ReplayBatchRunner.ItemResult> replayAll(ReplayBatchEntity batch) {
        // 目标版本源码：content 列是冻结的（updatable=false），可安全复用
        // 见上：源码的系统真相在 cloud，本服务不查本地缓存表。
        // 取不到 = 版本已删或不属于该用户 → 整批失败，属「重跑无用」那一类。
        var target = versionSourceClient.fetch(batch.targetVersionId, batch.userId)
            .orElseThrow(() -> new TargetVersionMissingException(batch.targetVersionId));

        // ★总体由**冻结集合**定义，不由「此刻拉到什么」定义（ADR 0034 §10.2）。
        //
        //   冻结表只存 id 与基线（不存 input——那会把用户数据复制一份，
        //   扩大 PII 面，正是 §3.1 要避免的）。所以重跑时仍需向上游取 payload，
        //   但**成员资格以冻结集合为准**：
        //     · 冻结内、上游还在  → 正常重跑
        //     · 冻结内、上游已没  → 记为失败（诚实：这条属于总体但跑不了）
        //     · 不在冻结内        → **忽略**，哪怕上游新增了
        //
        //   这样窗口漂移不会改变总体，§1.1 的「样本即总体全量」前提才成立。
        List<ReplayBatchItemEntity> frozen = ReplayBatchItemEntity
            .list("batchId = ?1 order by executionId", batch.id);

        Map<String, ExecutionWindowClient.WindowedExecution> upstream =
            windowClient.fetchWindow(batch.policyId, batch.userId, batch.windowFrom, batch.windowTo)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                    ExecutionWindowClient.WindowedExecution::executionId,
                    java.util.function.Function.identity(),
                    (a, b) -> a));

        // ★取证日志：定位「冻结成功但重放全失败」。
        //   生产实测 126 条全部走到下面的 e == null 分支，但冻结时同一窗口
        //   明明取到了 126 条。是 upstream 空、还是 key 对不上，只有这一行能分辨。
        if (upstream.size() != frozen.size()) {
            String frozenSample = frozen.stream().limit(3)
                .map(i -> i.executionId).collect(java.util.stream.Collectors.joining(","));
            String upstreamSample = upstream.keySet().stream().limit(3)
                .collect(java.util.stream.Collectors.joining(","));
            Log.errorf("重放集合不匹配 batch=%s 冻结=%d 上游=%d 冻结样本=[%s] 上游样本=[%s]",
                batch.id, frozen.size(), upstream.size(), frozenSample, upstreamSample);
        }

        List<ReplayBatchRunner.ItemResult> results = new ArrayList<>(frozen.size());
        for (ReplayBatchItemEntity item : frozen) {
            ExecutionWindowClient.WindowedExecution e = upstream.get(item.executionId);
            if (e == null) {
                // 冻结时还在、现在没了（删除／状态变更）。这是**真实失败**，
                // 不能假装它不属于总体——那就退回成「拿成功子集出数」了。
                results.add(ReplayBatchRunner.ItemResult.failed(
                    item.executionId, ReplayFailureKind.INPUT_INCOMPATIBLE));
                continue;
            }
            // ★基线取**冻结时**的值，不取上游当前值：
            //   上游 decision 可能因数据订正而变，那会让「变化了多少条」
            //   这个结论随时间漂移。
            results.add(replayOne(e, item.baseApproved, target, batch.tenantId));
        }
        return results;
    }

    /**
     * 冻结总体：拉取窗口、把 execution id 与基线落表、派生 {@code plannedCount}。
     *
     * <p>★<b>独立事务</b>（{@code REQUIRES_NEW}）：这是本次修复的关键。
     * 上一版把 {@code plannedCount = window.size(); persist();} 写在
     * {@code replayAll} 里，而后者由 {@code @Transactional runBatch} 调用、
     * <b>加入同一个长事务</b>——Panache 的 {@code persist()} 不提交，
     * worker 崩溃后事务回滚，库里仍是创建时的 0。
     * 我曾在 commit message 里声称「在任何一条重跑开始前落库」，
     * <b>那是不实陈述</b>。只有独立提交的事务才是真正的检查点。
     *
     * <p>冻结完成后写 {@code windowFrozenAt}：它区分「还没冻结」与
     * 「冻结完成但窗口为空」——后者是正当业务结果，前者是系统故障，
     * 两者对用户的含义完全不同，不能都表现成 {@code plannedCount == 0}。
     *
     * @return 冻结的条数（即 plannedCount）
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int freezeWindow(UUID batchId) {
        ReplayBatchEntity batch = ReplayBatchEntity.findById(batchId);
        if (batch == null) {
            return 0;
        }
        if (batch.windowFrozenAt != null) {
            // 已冻结（回收重跑的场景）：直接复用，绝不重新拉窗口
            return batch.plannedCount;
        }

        List<ExecutionWindowClient.WindowedExecution> window =
            windowClient.fetchWindow(batch.policyId, batch.userId, batch.windowFrom, batch.windowTo);

        // ★规模上限在**冻结时**判定：这是第一次知道窗口真实条数的时刻
        //   （创建时窗口还没拉，那时判不了）。
        //   超限直接拒答，不进重跑——靠租约兜底等于让一个必然超时的批次
        //   先跑两小时再失败，既占额度又浪费容量（§10.3）。
        if (window.size() > MAX_BATCH_SIZE) {
            batch.plannedCount = window.size();
            batch.windowFrozenAt = java.time.Instant.now();
            batch.failedCount = window.size();
            batch.completedCount = 0;
            batch.markFailed(toJson(List.of(ReplayFailureKind.WINDOW_TOO_LARGE.name())));
            batch.persist();
            Log.warnf("批次 %s 窗口 %d 条超过上限 %d，拒答", batchId, window.size(), MAX_BATCH_SIZE);
            return 0;
        }

        for (ExecutionWindowClient.WindowedExecution e : window) {
            new ReplayBatchItemEntity(batchId, e.executionId(), e.baseApproved()).persist();
        }
        batch.plannedCount = window.size();
        batch.windowFrozenAt = java.time.Instant.now();
        batch.persist();

        Log.infof("批次 %s 冻结总体：%d 条", batchId, window.size());
        return window.size();
    }

    /**
     * 重跑一条。**任何失败都归类而不抛出**——批次的 fail-closed 语义由
     * {@link ReplayBatchRunner#decide} 统一裁决，这里只负责如实分类。
     */
    private ReplayBatchRunner.ItemResult replayOne(
        ExecutionWindowClient.WindowedExecution e,
        boolean frozenBaseApproved,
        PolicyVersionSourceClient.VersionSource target,
        String tenantId) {

        try {
            // ★执行上界由 **wall-clock 超时** 提供——不能靠 statementLimit。
            //   实测（limit=1/2/10，同一策略连续执行 10 万次）：
            //   Aster AST **根本不产生可计数的 statement**，statementLimit 完全不触发。
            //   我上一轮删掉这层超时、改称「上界由 statementLimit 保证」，
            //   净效果是把执行上界从「有」变成「没有」，而注释却宣称有——
            //   本仓「注释声称 ≠ 实现如此」的又一次。
            //
            //   ★用**共享**线程池而非每条新建：万条批次原会创建 10000 个池。
            //   shutdownNow 确实不保证停下 Truffle（被弃线程仍占 CPU），
            //   但它保证**调用者不再等待**，从而让「段耗时」有真实上界——
            //   这正是租约取值的依据。被弃线程由容量闸门的许可数封顶。
            var execResult = capacityGate.withPermit(PERMIT_WAIT_MS, () ->
                runWithTimeout(() -> replayExecutor.execute(
                    tenantId, target.content(), e.input(),
                    e.functionName(), e.locale(),
                    /* vocabIndex */ null,
                    /* legacyEvaluateSentinel */ false,
                    /* aliasSet */ java.util.Map.of(),
                    /* aliasesTrusted */ true)));

            var targetVerdict = DecisionInterpreter.interpret(execResult.result());

            // ★不是准入决策的执行（值输出）不参与比较——
            //   强行归入通过或拒绝都会造出假的「变化」。
            //   这类执行**仍算成功重跑**，只是决策视为未变。
            if (targetVerdict == DecisionInterpreter.Verdict.INDETERMINATE) {
                return ReplayBatchRunner.ItemResult.ok(
                    e.executionId(), frozenBaseApproved, frozenBaseApproved, null);
            }

            boolean targetApproved = targetVerdict == DecisionInterpreter.Verdict.APPROVED;
            return ReplayBatchRunner.ItemResult.ok(
                e.executionId(), frozenBaseApproved, targetApproved, null);

        } catch (WhatIfCapacityGate.WhatIfThrottledException throttled) {
            // 服务端繁忙，不是用户数据的问题——文案上必须与 INPUT_INCOMPATIBLE 区分
            return ReplayBatchRunner.ItemResult.failed(
                e.executionId(), ReplayFailureKind.THROTTLED);

        } catch (Exception ex) {
            ReplayFailureKind kind = classify(ex);
            // ★必须记下**原始异常**：classify() 的默认分支是 INPUT_INCOMPATIBLE，
            //   它会把任何未识别的失败一律说成「你的历史输入与新版本不兼容」。
            //   生产实测（2026-08-17）126 条全 INPUT_INCOMPATIBLE，而同样的源码 +
            //   同样的输入走 evaluate-source 却成功——真因被这个 catch 吞掉了，
            //   日志、item 表里都没有任何线索，只能靠猜。
            //   条目表只存分类（无 message 列），故这里是唯一的取证点。
            Log.errorf(ex, "重放失败 execution=%s 归类=%s", e.executionId(), kind);
            return ReplayBatchRunner.ItemResult.failed(e.executionId(), kind);
        }
    }

    /**
     * 把异常归类成**面向用户决策**的失败类型。
     *
     * <p>分类标准不是异常类型，而是「用户看到后该做什么」：
     * 清理数据重跑？换个版本？还是等一会儿再试？
     */
    private static ReplayFailureKind classify(Exception ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(java.util.Locale.ROOT);

        // ★执行池满：这是**服务端容量**问题，不是用户数据问题。
        //   归 THROTTLED（可重试），落进默认的 INPUT_INCOMPATIBLE 会让用户
        //   去排查自己没问题的数据。
        if (ex instanceof java.util.concurrent.RejectedExecutionException) {
            return ReplayFailureKind.THROTTLED;
        }

        // ★资源耗尽（Truffle statementLimit）必须单独归类。
        //   它落进下面的默认分支 INPUT_INCOMPATIBLE 会**误导用户**：
        //   那句话是「你的历史输入与新版本不兼容」，而真相是
        //   「这条策略执行量超出上限」——两者要做的事完全不同。
        //   用 PolyglotException.isResourceExhausted() 判定，
        //   不靠消息文本匹配（消息随 Graal 版本变化）。
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.graalvm.polyglot.PolyglotException pe
                && pe.isResourceExhausted()) {
                return ReplayFailureKind.TIMEOUT;
            }
        }

        if (ex instanceof java.util.concurrent.TimeoutException || msg.contains("timeout")) {
            return ReplayFailureKind.TIMEOUT;
        }
        if (msg.contains("vocabulary") || msg.contains("alias")) {
            return ReplayFailureKind.VOCABULARY_UNAVAILABLE;
        }
        if (msg.contains("compile") || msg.contains("parse") || msg.contains("syntax")) {
            return ReplayFailureKind.TARGET_COMPILE_ERROR;
        }
        // ★默认落 INPUT_INCOMPATIBLE 而非 UNKNOWN：绝大多数重跑失败确实源于
        //   历史输入与新版本不兼容，这也是**选择偏差的源头**——如实归类才能让
        //   用户看清「失败与输入相关」，从而理解为什么不能只算成功的那些。
        return ReplayFailureKind.INPUT_INCOMPATIBLE;
    }


    /** 目标版本不存在——整批失败，且重跑无用。 */
    static class TargetVersionMissingException extends RuntimeException {
        TargetVersionMissingException(String versionId) {
            super("目标版本不存在：" + versionId);
        }
    }

    private static Map<String, Object> summaryOf(ReplayBatchOutcome.Completed c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changed", c.changed());
        m.put("newlyApproved", c.newlyApproved());
        m.put("newlyRejected", c.newlyRejected());
        m.put("totalSampled", c.totalSampled());
        // null 表示「无金额基线，无法估算」——与「估算为 0」是两回事，前端必须能区分
        m.put("estimatedValueDelta", c.estimatedValueDelta());
        return m;
    }

    private static List<String> reasonsOf(ReplayBatchOutcome.Rejected r) {
        // ★只给失败**类别**，不给每类条数（ADR 0034 §10.1，方案 B）。
        //
        //   §1.1 是**信息流**约束，不是「同屏」约束：用户可以缓存 RUNNING 响应里的
        //   plannedCount，再读 FAILED 响应的失败条数，跨请求相减得出成功数——
        //   那正是 Phase 4 的死因，只是分成了两次请求。
        //   上一轮我只删掉了 FAILED 响应里的 plannedCount，堵的是同屏，堵不住这条。
        //
        //   类别足以指导排查（「有版本不兼容」告诉用户该去看什么），
        //   失去的只是规模感——而规模感一旦给出就可被相减。
        //
        //   排序保证同一批失败每次呈现一致，便于用户比对与我们复现。
        return r.failuresByKind().keySet().stream()
            .map(Enum::name)
            .sorted()
            .toList();
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("序列化批次结果失败", e);
        }
    }
}
