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
    private List<ReplayBatchRunner.ItemResult> replayAll(ReplayBatchEntity batch) {
        // TODO(ADR-0034-S3): 接 Execution 窗口查询 + evaluate-source?simulate=true 重跑。
        //   必须复用 PermitLease 背压（不得绕过并发闸门）与 simulate 免计费门控。
        return new ArrayList<>();
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
