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
    private static final java.time.Duration LEASE_DURATION = java.time.Duration.ofHours(2);

    /**
     * 批次规模硬上限（ADR 0034 §10.3）。
     *
     * <p>与 §7.4「万条以上要进度条」对齐：进度条覆盖的正是接近上限的批次。
     * 超限在**创建入口**拒绝，而不是靠租约兜底——
     * 靠租约兜底等于让一个必然超时的批次先跑两小时再失败。
     */
    static final int MAX_BATCH_SIZE = 10_000;

    /** 最大尝试次数。反复崩溃说明是批次本身有问题，无限重试只会循环占用额度。 */
    private static final int MAX_ATTEMPTS = 3;

    @jakarta.inject.Inject
    ExecutionWindowClient windowClient;

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
    public UUID claimNextPending() {
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
    UUID markRunning(UUID candidateId) {
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
        return updated == 1 ? candidate.id : null;
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
            if (b.attemptCount >= MAX_ATTEMPTS) {
                // 放弃重试：标失败并**释放槽位**，否则额度被永久吃掉
                b.failedCount = b.plannedCount;
                b.completedCount = 0;
                b.markFailed(toJson(List.of(ReplayFailureKind.UNKNOWN.name())));
                b.concurrencySlot = null;
                b.leaseExpiresAt = null;
                Log.warnf("批次 %s 已尝试 %d 次仍未完成，判定失败并释放并发槽",
                    b.id, b.attemptCount);
            } else {
                // 退回 PENDING 等待重新领取；槽位保留（它仍占着额度，这是对的）
                b.status = ReplayBatchStatus.PENDING;
                b.startedAt = null;
                b.leaseExpiresAt = null;
                Log.warnf("批次 %s 租约过期（第 %d 次尝试），退回 PENDING 等待重跑",
                    b.id, b.attemptCount);
            }
            b.persist();
            reclaimed++;
        }
        return reclaimed;
    }

    /**
     * 执行批次：逐条重跑 → 判定 → 落库。
     *
     * <p>★<b>本方法不吞异常</b>：重跑过程中的业务失败被归类进
     * {@link ReplayFailureKind} 并导致整批拒答（这是**预期路径**）；
     * 而基础设施异常（DB 断连等）应当抛出，由调度器兜底标记——
     * 把两者混为一谈会让真正的缺陷伪装成「用户数据有问题」。
     */
    @Transactional
    public void runBatch(UUID batchId) {
        ReplayBatchEntity batch = ReplayBatchEntity.findById(batchId);
        if (batch == null) {
            Log.warnf("批次 %s 不存在，跳过", batchId);
            return;
        }
        if (batch.status != ReplayBatchStatus.RUNNING) {
            Log.warnf("批次 %s 状态为 %s 而非 RUNNING，跳过", batchId, batch.status);
            return;
        }

        // ★记下开跑时的 owner。终态写前会复核它——若期间本批次被回收并被
        //   另一个 worker 重新领取，owner 已变，本次结果**必须丢弃**而不是覆盖。
        //   这是 §10.3 的落点：让「两个 worker 互相覆盖」结构上不可能，
        //   而不是寄望于「租约调长后误判不会发生」。
        final String owner = batch.leaseOwner;

        List<ReplayBatchRunner.ItemResult> results = replayAll(batch);

        // ★空窗口是**正当业务结果**，不是编程错误：这段时间内该策略就是没有执行。
        //   decide() 对 plannedCount<=0 会抛异常（那是给「worker 提前退出」用的），
        //   所以必须在此之前分流。呈现上它既不是「全量成功」也不是「拒答」，
        //   而是「没有可分析的样本」——这同样满足 §1.1：总体为空，不给任何推断。
        if (batch.plannedCount == 0) {
            batch.completedCount = 0;
            batch.failedCount = 0;
            // 空对象而非某个失败 kind：窗口内没有执行**不是失败**，
            // 谎称某类失败会误导用户去排查并不存在的数据问题。
            batch.markFailed(toJson(List.of()));
            Log.infof("批次 %s 窗口内无可重放执行，无样本可分析", batchId);
            batch.persist();
            return;
        }

        ReplayBatchOutcome outcome = ReplayBatchRunner.decide(batch.plannedCount, results);

        if (outcome instanceof ReplayBatchOutcome.Completed c) {
            batch.completedCount = batch.plannedCount;
            batch.failedCount = 0;
            batch.markCompleted(toJson(summaryOf(c)));
            Log.infof("批次 %s 全量成功：%d/%d 条，%d 条决策变化",
                batchId, batch.plannedCount, batch.plannedCount, c.changed());
        } else if (outcome instanceof ReplayBatchOutcome.Rejected r) {
            batch.failedCount = r.totalFailures();
            batch.completedCount = batch.plannedCount - batch.failedCount;
            batch.markFailed(toJson(reasonsOf(r)));
            Log.infof("批次 %s 拒答：%d 条失败，分布=%s",
                batchId, r.totalFailures(), r.failuresByKind());
        }

        // ★owner 复核：本次持有期间批次若被回收并改派，owner 已变。
        //   此时丢弃本次结果——新 worker 会重新跑出一份，
        //   而覆盖会让「谁的结果」变得不可知。
        // owner 不同即丢弃——不附加状态条件：被改派后无论新 owner 处于
        // RUNNING 还是已跑完，本次结果都不该落库。
        long written = ReplayBatchEntity.update(
            "status = ?1, completedCount = ?2, failedCount = ?3, finishedAt = ?4,"
                + " resultSummary = ?5, failureReasons = ?6,"
                + " concurrencySlot = null, leaseExpiresAt = null"
                + " where id = ?7 and leaseOwner = ?8",
            batch.status, batch.completedCount, batch.failedCount, batch.finishedAt,
            batch.resultSummary, batch.failureReasons, batchId, owner);
        if (written == 0) {
            Log.warnf("批次 %s 的租约已改派（原 owner %s），丢弃本次结果", batchId, owner);
        }
    }

    /**
     * 防御性标记失败：worker 自身炸了时用，避免批次永久卡在 RUNNING。
     *
     * <p>★独立事务：调用它的场景本身就是「上一个事务出了问题」，
     * 复用那个事务会一起回滚，批次仍然卡住。
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void failBatchDefensively(UUID batchId, ReplayFailureKind kind) {
        ReplayBatchEntity batch = ReplayBatchEntity.findById(batchId);
        if (batch == null || batch.status.isTerminal()) {
            return;
        }
        try {
            batch.markFailed(toJson(List.of(kind.name())));
            batch.persist();
            Log.warnf("批次 %s 被防御性标记为 FAILED（%s）", batchId, kind);
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
        io.aster.policy.entity.PolicyVersion target =
            io.aster.policy.entity.PolicyVersion.findById(Long.valueOf(batch.targetVersionId));
        if (target == null) {
            // 目标版本已被删除 → 整批失败，且是「重跑无用」那一类
            throw new TargetVersionMissingException(batch.targetVersionId);
        }

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
        io.aster.policy.entity.PolicyVersion target,
        String tenantId) {

        try {
            var execResult = capacityGate.withPermit(PERMIT_WAIT_MS, () ->
                replayExecutor.execute(
                    tenantId, target.content, e.input(),
                    e.functionName(), e.locale(),
                    /* vocabIndex */ null,
                    /* legacyEvaluateSentinel */ false,
                    /* aliasSet */ java.util.Map.of(),
                    /* aliasesTrusted */ true));

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
            return ReplayBatchRunner.ItemResult.failed(
                e.executionId(), classify(ex));
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
