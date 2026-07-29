package io.aster.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 业务指标聚合：策略评估次数、审计写入、工作流执行与评估耗时。
 */
@ApplicationScoped
public class BusinessMetrics {

    private final MeterRegistry registry;
    private final Counter policyEvaluations;
    private final Counter auditLogWrites;
    private final Counter auditLogWriteFailures;
    private final Counter workflowExecutions;
    private final Timer policyEvaluationTimer;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.policyEvaluations = Counter.builder("business.policy.evaluations.total")
            .description("Total policy evaluations processed by Policy API")
            .tag("app", "policy-api")
            .register(registry);

        this.auditLogWrites = Counter.builder("business.audit.log.writes.total")
            .description("Total audit log entries persisted")
            .tag("app", "policy-api")
            .register(registry);

        // 审计记录写入失败（含哈希链计算失败导致的拒绝写入）。
        // 这个计数器必须有人盯：审计记录丢失是可观测的失败，而无哈希记录
        // 是不可观测的失败——我们刻意选择前者，前提是它真的被观测到。
        this.auditLogWriteFailures = Counter.builder("business.audit.log.write.failures.total")
            .description("Audit log entries dropped due to persistence or hash-chain failure")
            .tag("app", "policy-api")
            .register(registry);

        this.workflowExecutions = Counter.builder("business.workflow.executions.total")
            .description("Total workflow executions completed")
            .tag("app", "policy-api")
            .register(registry);

        this.policyEvaluationTimer = Timer.builder("business.policy.evaluation.duration")
            .description("Policy evaluation duration in seconds")
            .tag("app", "policy-api")
            .register(registry);
    }

    public void recordPolicyEvaluation() {
        policyEvaluations.increment();
    }

    public void recordAuditLogWrite() {
        auditLogWrites.increment();
    }

    /** 审计记录未能写入（哈希链失败或持久化异常）——需告警。 */
    public void recordAuditLogWriteFailure() {
        auditLogWriteFailures.increment();
    }

    public void recordWorkflowExecution() {
        workflowExecutions.increment();
    }

    public Timer.Sample startPolicyEvaluation() {
        return Timer.start(registry);
    }

    public void endPolicyEvaluation(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(policyEvaluationTimer);
        }
    }
}
