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
    @Transactional
    public UUID claimNextPending() {
        ReplayBatchEntity candidate = ReplayBatchEntity
            .<ReplayBatchEntity>find("status = ?1 order by createdAt", ReplayBatchStatus.PENDING)
            .firstResult();
        if (candidate == null) {
            return null;
        }
        long updated = ReplayBatchEntity.update(
            "status = ?1, startedAt = ?2 where id = ?3 and status = ?4",
            ReplayBatchStatus.RUNNING, java.time.Instant.now(),
            candidate.id, ReplayBatchStatus.PENDING);
        // 竞态：别的副本先领走了 → 本轮放弃，下一轮再来
        return updated == 1 ? candidate.id : null;
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
            batch.markFailed(toJson(Map.of()));
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
        batch.persist();
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
            batch.markFailed(toJson(Map.of(kind.name(), batch.plannedCount)));
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

        List<ExecutionWindowClient.WindowedExecution> window =
            windowClient.fetchWindow(batch.policyId, batch.userId, batch.windowFrom, batch.windowTo);

        // ★plannedCount 由**冻结的窗口**派生，并在任何一条重跑开始前落库。
        //   创建时写 0 且从不回填是致命 bug：decide() 对 plannedCount<=0 必抛异常，
        //   于是**任何批次都跑不完**（实测全仓对该字段零赋值）。
        //   总体必须在开跑前确定：先全量拉完再定 planned，才谈得上「全量成功」（§1.1）。
        batch.plannedCount = window.size();
        batch.persist();

        List<ReplayBatchRunner.ItemResult> results = new ArrayList<>(window.size());
        for (ExecutionWindowClient.WindowedExecution e : window) {
            results.add(replayOne(e, target, batch.tenantId));
        }
        return results;
    }

    /**
     * 重跑一条。**任何失败都归类而不抛出**——批次的 fail-closed 语义由
     * {@link ReplayBatchRunner#decide} 统一裁决，这里只负责如实分类。
     */
    private ReplayBatchRunner.ItemResult replayOne(
        ExecutionWindowClient.WindowedExecution e,
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
                    e.executionId(), e.baseApproved(), e.baseApproved(), null);
            }

            boolean targetApproved = targetVerdict == DecisionInterpreter.Verdict.APPROVED;
            return ReplayBatchRunner.ItemResult.ok(
                e.executionId(), e.baseApproved(), targetApproved, null);

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

    private static Map<String, Integer> reasonsOf(ReplayBatchOutcome.Rejected r) {
        Map<String, Integer> m = new LinkedHashMap<>();
        r.failuresByKind().forEach((k, v) -> m.put(k.name(), v));
        return m;
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("序列化批次结果失败", e);
        }
    }
}
