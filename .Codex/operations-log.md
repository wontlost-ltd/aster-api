# 第十二轮 Phase 4 What-if 收敛审查操作日志

## 2026-08-07

- 任务：独立验证第十一轮九项最小可发路径，并判断当前路线是否值得继续。
- 范围：cloud `2851dce`、api `16061a5`、ADR 0033；不重审 Phase 1/2/3，不改产品实现。
- 工具降级：`sequential-thinking`、`shrimp-task-manager`、`code-index`、Codex MCP 未暴露；改用显式计划、`rg`、逐行源码、本地测试、真 PostgreSQL、临时最终态探针和逐项反向变异。
- 基线：cloud 目标 unit 102 项通过；真 PostgreSQL integration 10 项通过；全仓 lint 0 error；TypeScript 通过；API 目标 Gradle 测试通过。
- 关键变异：成功子集过滤、统计 deadline、0.2 阈值、UI 分母、taxonomy 查询顺序、client signal、Vocabulary cloud/Java、PATCH 零行、ADR 状态、simulate runtime metric。所有产品变异均已还原。
- 独立交叉审查：初稿被退回；审查者指出取消可能在同步求值结束前触发 `onTermination` 释放许可、异步唯一性结论越过证据、授权历史输入语义未拍板，以及 per-batch timer 未清理。
- 复核结果：本地 Mutiny 3.3.0 源码与临时 JUnit latch 探针共同证明 `cancel → termination callback → supplier 仍运行`；探针 37 个 Gradle task 通过后已删除。该问题提升为 P0。其余三点均已按证据修正报告。
- 二次交叉审查：修订稿 94/100、通过；该分数仅评价报告证据质量，不替代 Phase 4 产品 47/100 评分。
- 关键判断：按需重求值方向可保留；当前“自动 GET + route 同步扇出普通 evaluate-source + 部分成功后估算”提交不再局部修补，建议撤下并恢复 409。未来需重构后再审，异步 ReplayBatch 是优先候选但不是本轮已证明的唯一方案。
- 权威报告：`../.claude/review-report-round12.md`。

## 最终决策

- 综合评分：47/100（技术 48，战略 46）。
- 建议：撤下 Phase 4 动态 What-if，恢复 409；未来以独立批次服务重构后重新立项，优先评估异步 ReplayBatch，同时保留严格有界同步方案的比较空间。
- 理由：九项仅 1 项完整成立、6 项部分成立、2 项无效；20% 成功子集的选择偏差、自动请求放大、取消时 permit 提前释放和职责集中属于设计/生命周期问题。
