# aster-api 指标目录

> 与 PM 北极星指标 + 反指标对齐的可观测性目录
> 详见 aster-deploy/docs/pm/02-north-star-metric.md

## 北极星指标（NSM）

| 指标 | 类型 | 来源 | PromQL | 阈值 |
|---|---|---|---|---|
| `pm_weekly_waadr` (view) | Materialized View | DB | `SELECT sum(waadr) FROM pm_weekly_waadr WHERE week >= now() - interval '12 weeks'` | 季度目标见 02-NSM Q2/Q3/Q4 |

## 反指标（Counter Metrics）

PM 文档要求每个反指标都对应一个可监控的 Prometheus 查询，并在阈值越过时告警。

| 反指标 | Owner | Source meter | PromQL | 阈值 |
|---|---|---|---|---|
| **7 日规则回滚率** | aster-api | `rule_rolled_back_total` + `draft_published_total` | `sum(increase(rule_rolled_back_total[7d])) / sum(increase(draft_published_total[7d]))` | ≤ 0.15 |
| **P99 评估延迟** | aster-api | `policy_evaluation_duration_seconds` (PolicyMetrics 已暴露) | `histogram_quantile(0.99, sum(rate(policy_evaluation_duration_seconds_bucket[5m])) by (le))` | ≤ 0.2 s |
| **平台月 SLA** | infra | ingress/uptime probes | `avg_over_time(up{job="aster-api"}[30d])` | ≥ 0.995 |
| **LLM 成本/采纳草稿** | aster-api | `llm_tokens_total{kind="completion"}` + `pm_weekly_waadr` | 见下方 PromQL | ≤ ¥3.5 / 采纳草稿 |
| **Mixpanel 事件丢失率** | aster-api | `mixpanel_events_dropped_total` / `mixpanel_events_enqueued_total` | `sum(rate(mixpanel_events_dropped_total[5m])) / sum(rate(mixpanel_events_enqueued_total[5m]))` | < 0.01 |
| **PlanGate 失败率** | aster-api | aster-cloud 内部接口调用 | 由 Vert.x WebClient 异常日志 + Caffeine miss rate 综合 | fail-open 后不阻塞业务 |

### LLM 成本计算细节

```promql
# 假设单价 cost_per_1k_completion_token = 0.06 USD（约 ¥0.43）
sum(increase(llm_tokens_total{kind="completion"}[7d])) / 1000 * 0.43
/
sum(increase(pm_weekly_waadr_adopted_total[7d]))
```

> 注：流式 SSE 响应通常不返回 `usage` 字段；`llm_tokens_total` 仅记录非流式 LLM 调用（如 explain / repair）。
> 流式 chat 的 token 量需通过 provider 的 batch billing 报表对账。

## NSM 事件 Counter（前端 + 后端）

前端事件由 Mixpanel 直接接收，不在此处。后端权威发射：

| 事件 | 后端发射点 | Mixpanel 属性 |
|---|---|---|
| `draft_published` | PolicyVersionService.activateVersion → trackDraftPublished | rule_id / version / source_kind / tenant_id / reviewer_id / author_role / emitted_by="server" |
| `rule_rolled_back` | PolicyEvaluationResource.rollback | rule_id / from_version / to_version / days_after_publish / reason / tenant_id |

去重原则：分析时按 `emitted_by="server"` 过滤即可（前端同名事件保留作 UX timing）。

## Guard 对账（ADR 0054 §10，#465）

| 指标 | 类型 | 标签 | 多副本读法 |
|---|---|---|---|
| `guard_orphan_actions` | Gauge（MultiGauge） | `tenant`（至多 `ASTER_GUARD_RECONCILIATION_METRIC_MAX_TENANTS` 个，缺省 100，其余合并为 `_other`）、`executor`（`aster-guard-mcp` / `aster-guard-sdk` / `aster-cloud-exec` 白名单内的组件名，其余 `other`，空为 `unknown`）、`kind`（8 个孤儿类别） | 每个副本每 `metric-refresh`（缺省 60 s）从库重读当前 OPEN 行数，各副本同值，取 `max`；只发非零序列 |
| `guard_orphan_actions_detected_total` | Counter | `kind` | 只由检出它的副本累加，取 `sum` |
| `guard_reconciliation_runs_total` | Counter | `outcome` ∈ `ok` / `failed` / `skipped_locked` / `skipped_recent`（按租户计） | 取 `sum` |
| `guard_reconciliation_last_success_seconds` | Gauge | — | 本副本最近一次完整跑完租户循环（含无租户、含跳过）的 epoch 秒，进程内值，取 `max` |

`kind` 的 severity：`critical`（`side_effect_without_decision`、`side_effect_unconsumed`、`side_effect_mismatch`、`consume_on_non_allow`、`result_without_consume`）、`warning`（`consumed_without_result`）、`info`（`allow_unconsumed`、`orphan_receipt`）。告警规则在 k3s `apps/aster-lang/observability/prometheus-rule.yaml` 的 `aster.guard.reconciliation` 组：critical 类任一 > 0 即 `AsterGuardBypassSuspected`，`consumed_without_result` ≥ 5 持续 30 m 为 `AsterGuardExecutionLost`，`time() - max(last_success) > 1800` 持续 10 m 为 `AsterGuardReconciliationStale`；info 类不告警。

## 告警规则示例（Grafana / Prometheus alerting）

```yaml
- alert: HighRollbackRate
  expr: |
    sum(increase(rule_rolled_back_total[7d]))
      / sum(increase(draft_published_total[7d])) > 0.15
  for: 1h
  annotations:
    summary: "WAADR 反指标越界：7 日回滚率 > 15%"

- alert: HighEvaluationLatency
  expr: |
    histogram_quantile(0.99,
      sum(rate(policy_evaluation_duration_seconds_bucket[5m])) by (le)) > 0.2
  for: 10m
  annotations:
    summary: "P99 评估延迟超过 200ms"

- alert: MixpanelDeliveryDegraded
  expr: |
    sum(rate(mixpanel_events_dropped_total[5m]))
      / sum(rate(mixpanel_events_enqueued_total[5m])) > 0.01
  for: 30m
  annotations:
    summary: "Mixpanel 事件丢失率 > 1%（可能 token 或网络问题）"
```

## 仪表盘建议

每个 PM 评审会前打开此仪表盘：

1. WAADR 周趋势（`pm_weekly_waadr` 折线图）
2. 反指标四象限：回滚率 / P99 延迟 / SLA / LLM 成本
3. Mixpanel 投递健康度（enqueued / sent / dropped）
4. PlanGate 命中率与 cloud 内部接口延迟

---

**版本**：v1.0 · 2026-05-10
**关联**：`aster-deploy/docs/pm/02-north-star-metric.md`、`aster-deploy/docs/pm/03-telemetry-spec.md`
