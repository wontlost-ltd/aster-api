-- 修复 workflow_state.chk_status 漏掉 COMPENSATION_FAILED / TERMINATED 导致的写入失败
--
-- 背景：V2.2.2 建表时 chk_status 允许 8 个状态（含 COMPENSATION_FAILED、TERMINATED）。
-- V2.2.5 为加入 PAUSED 而 DROP + 重建该约束，但重建时**漏抄**了这两个值，只留 7 个。
-- 此后 chk_status 再未被任何迁移修改（全库仅 V2.2.2 / V2.2.5 触碰），故线上生效的
-- 约束一直缺这两个状态。
--
-- 实际后果（非理论风险）：saga 补偿失败路径必然写入违反约束的值 →
--   * WorkflowSchedulerService.handleCompensation 走 markCompleted("COMPENSATION_FAILED")
--     后 persist()，直接触发 check 约束违反，补偿失败无法落库；
--   * PostgresEventStore.deriveStatusFromEvent 把 CompensationFailed / WorkflowTerminated
--     事件映射为 COMPENSATION_FAILED / TERMINATED 并赋给 state.status，同样写不进去。
-- 即：**补偿一旦失败，工作流状态就再也写不进数据库**，而这正是最需要留下现场的时刻。
--
-- 目标集合取 V2.4.0__create_workflow_query_view.sql 的 chk_query_status（9 值 =
-- 源枚举 WorkflowState.Status 的 8 个成员 + 仅存于持久层的 PAUSED）。修完三处一致：
--   aster-lang-runtime WorkflowState.Status（8）+ PAUSED == chk_status == chk_query_status
--
-- 幂等：DROP ... IF EXISTS 后重建；已在正确状态的库重跑本迁移无副作用。
-- ★不在此文件写"回滚"段（承 V4.3.0 的正确写法）：V6.1.0/V6.2.0/V6.3.0 曾把回滚 DDL
--   直接留在迁移体里，导致刚建好的表/列当场被 DROP，最终要靠 V6.4.0 补救。

ALTER TABLE workflow_state
    DROP CONSTRAINT IF EXISTS chk_status;

ALTER TABLE workflow_state
    ADD CONSTRAINT chk_status CHECK (status IN (
        'READY',
        'RUNNING',
        'COMPLETED',
        'FAILED',
        'COMPENSATING',
        'COMPENSATED',
        'COMPENSATION_FAILED',
        'TERMINATED',
        'PAUSED'
    ));

COMMENT ON CONSTRAINT chk_status ON workflow_state IS
    'Allowed workflow states: WorkflowState.Status 全 8 成员 + 持久层专有 PAUSED；与 V2.4.0 chk_query_status 对齐';
