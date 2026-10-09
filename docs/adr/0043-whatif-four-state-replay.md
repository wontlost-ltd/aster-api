# ADR 0043：What-If 四态回放 —— 结论码逐条落库与转移矩阵汇总（AI 治理方向·子项目 3c）

- 状态：**PROPOSED**（2026-10-09）
- 提出：2026-10-09；实施计划待写（`docs/superpowers/plans/2026-10-09-whatif-four-state.md`）
- 决策者：用户已拍板「逐条存 base/target 结论码 + 汇总转移矩阵；基线或目标为 INDETERMINATE/ERROR 的条目单列不可比，不进 changed」
- 相关：ADR 0034（What-If 批次：窗口、租约、汇总契约、§3.2 隐私规则）、ADR 0039（Verdict 四态；§5.2 记录了回放把四态压成布尔的限制）、ADR 0030（回放地基）、ADR 0041 §10（预留本子项目的做法）

---

## 0. TL;DR

What-If 今天把目标结论压成 `targetApproved = (verdict == APPROVED)`，基线侧也是布尔 `base_approved`。于是 `REQUIRE_APPROVAL → DENY` 算「没变」，`ALLOW → ESCALATE` 只能显示为「新拒绝」，INDETERMINATE 被写成与基线相同。子项目 1 给了规则四态结论，What-If 却看不见。

本 ADR 让 What-If 以统一结论码逐条记录基线与目标结论，并按转移对汇总；旧的布尔列与汇总键原样保留，旧读者不受影响。

## 1. 现状与问题

| 现状 | 位置 | 后果 |
|---|---|---|
| `DecisionInterpreter` 已返回 APPROVED/DENIED/REQUIRE_APPROVAL/ESCALATE/INDETERMINATE，`ReplayBatchService.replayOne` 压成 `targetApproved` 布尔 | `ReplayBatchService` | 四态信息在落库前丢失 |
| 基线 `WindowedExecution.baseApproved()` = `"approved".equals(decision)`，回退 `success` | `ReplayBatchService` / cloud `window/route.ts` | 基线同样只有布尔；cloud `Execution.outcome` 列（ADR 0041）未被窗口接口选出 |
| INDETERMINATE 目标写成「与基线相同」且 success=true | `replayOne` | 评估不出结论被当成无变化 |
| `replay_batch_item` 只有 `base_approved/target_approved/success/failure_kind` | V6.20.x / V6.26.0 | 无结论码、无转移类别 |
| `result_summary` 只有 `changed/newlyApproved/newlyRejected/totalSampled/estimatedValueDelta` | `summaryOf` | 无法回答「多少条从 ALLOW 变成了需审批」 |
| 四套词表并存：interpreter 枚举、Verdict 字符串、cloud `decision`（小写六值）、cloud `outcome`（大写） | 两仓 | 需要一个规范集合 |
| ADR 0034 §3.2「不落逐条 targetDecision」 | ADR 0034 | 与逐条落结论码需要对齐 |

## 2. 决策一：统一结论码 `ReplayOutcome`

`ALLOW / DENY / REQUIRE_APPROVAL / ESCALATE / INDETERMINATE / ERROR`（与 cloud `Execution.outcome` 同一套大写码）。

- 目标侧：`DecisionInterpreter` 五值一一映射（APPROVED→ALLOW、DENIED→DENY，其余同名）；回放抛错或 fail-closed → `ERROR`（`failure_kind` 照旧记录原因）。
- 基线侧：cloud 窗口接口新增 `outcome` 字段（`Execution.outcome`，可为 null）。非 null 直接采用；null（迁移前旧行）按 `decision` 折算：`approved→ALLOW`、`denied→DENY`、`require_approval/escalate/indeterminate/error` 同名大写；`decision` 也为 null 时按 `success` 折算 ALLOW/DENY。折算得来的基线标 `base_legacy=true`。
- 结论码是「结论体」的类别，不含 reason/role。ADR 0034 §3.2 的隐私规则改述为：**只落结论码，不落结论体**。结论码与既有 `base_approved` 布尔同级，不新增隐私面。

## 3. 决策二：逐条落库与回填

迁移 `V6.30.0__replay_batch_item_outcomes.sql`：

```sql
ALTER TABLE replay_batch_item ADD COLUMN base_outcome VARCHAR(20);
ALTER TABLE replay_batch_item ADD COLUMN target_outcome VARCHAR(20);
ALTER TABLE replay_batch_item ADD COLUMN base_legacy BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE replay_batch_item ADD CONSTRAINT replay_batch_item_base_outcome_ck CHECK (base_outcome IS NULL OR base_outcome IN ('ALLOW','DENY','REQUIRE_APPROVAL','ESCALATE','INDETERMINATE','ERROR'));
ALTER TABLE replay_batch_item ADD CONSTRAINT replay_batch_item_target_outcome_ck CHECK (target_outcome IS NULL OR target_outcome IN ('ALLOW','DENY','REQUIRE_APPROVAL','ESCALATE','INDETERMINATE','ERROR'));
UPDATE replay_batch_item SET base_outcome = CASE WHEN base_approved THEN 'ALLOW' ELSE 'DENY' END, base_legacy = true WHERE base_outcome IS NULL;
UPDATE replay_batch_item SET target_outcome = CASE WHEN target_approved THEN 'ALLOW' ELSE 'DENY' END WHERE target_outcome IS NULL AND success = true;
```

- `base_approved/target_approved` 列保留并继续写入（`= outcome == ALLOW`），旧读者与旧汇总语义不变。
- 回填后的旧行 `target_outcome` 只在 `success=true` 时可知；失败行保持 null。

## 4. 决策三：changed 与不可比

- `comparable(o)`：`o ∈ {ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE}`。
- `changed`：两侧都可比且不相等。
- `incomparable`：任一侧为 INDETERMINATE 或 ERROR。它们的转移对仍进矩阵，但不进 `changed`，也不进 `newlyApproved/newlyRejected`。
- `newlyApproved`：基线非 ALLOW 且可比、目标 ALLOW；`newlyRejected`：基线 ALLOW、目标可比且非 ALLOW。与旧语义在布尔策略上逐条相等（旧实现只有 ALLOW/DENY 两值）。
- INDETERMINATE 目标不再写成「与基线相同」：`target_outcome='INDETERMINATE'`、`success=true`、计入 incomparable。

## 5. 决策四：汇总契约

`result_summary` 在既有键之外新增（全部可选，旧批次无）：

```json
{
  "changed": 3, "newlyApproved": 1, "newlyRejected": 2, "totalSampled": 40, "estimatedValueDelta": …,
  "transitions": { "ALLOW->REQUIRE_APPROVAL": 2, "REQUIRE_APPROVAL->ESCALATE": 1, "ALLOW->ALLOW": 30 },
  "byTargetOutcome": { "ALLOW": 31, "REQUIRE_APPROVAL": 2, "ESCALATE": 1, "INDETERMINATE": 0, "DENY": 4, "ERROR": 2 },
  "incomparable": 2,
  "legacyBase": 5
}
```

- `transitions` 只含出现过的对，键为 `BASE->TARGET`。
- 这些都是批次级汇总，与 `changed` 同级，符合 ADR 0034「只回汇总、不回逐条」；`ReplayBatchResponseContractTest` 的不泄露规则按此更新。
- 不新增逐条端点。

## 6. 数据流

```
cloud window/route.ts（+outcome）──▶ api ReplayBatchService：base = outcome ?? 折算(decision, success)
   回放 → DecisionInterpreter → ReplayOutcome target
   ItemResult(baseOutcome, targetOutcome, baseLegacy) → replay_batch_item（新列 + 旧布尔）
   summaryOf：transitions / byTargetOutcome / incomparable / legacyBase + 旧四键
GET …/whatif-batches/{id} → resultSummary → cloud whatif-batch-panel：旧四块 + 转移矩阵 + 不可比/旧基线计数
```

## 7. 失败与边界

| 情形 | 行为 |
|---|---|
| 窗口行无 `outcome` 也无 `decision` | 按 `success` 折算，`base_legacy=true` |
| 窗口行 `outcome` 不在六值内 | 视为 ERROR 基线，记 warn |
| 回放抛错 | `target_outcome='ERROR'`、`success=false`、`failure_kind` 照旧 |
| 目标 INDETERMINATE | 不再伪装为无变化；计入 incomparable |
| 旧批次（迁移前完成） | 回填后 `transitions` 等键缺失，面板按「无矩阵」渲染 |

## 8. 兼容性与发布

- 仅增列、增键；旧客户端忽略新键。
- api 先于 cloud 发布：旧 cloud 窗口不带 `outcome` → 全部走折算（`legacyBase` 偏高，无错误）。
- 不改 HMAC 签名（ADR 0042 v3）；不发审计事件。
- ADR 0034 §3.2、§6 文字同步；ADR 0039 §5.2 的限制标记为已解决。

## 9. 测试与验收

- api：`ReplayOutcome` 映射与折算单测；`ReplayBatchRunnerTest` 转移/不可比/legacy/newly* 语义等价用例；`ReplayBatchEndToEndIT` 增 Verdict 策略用例（基线 ALLOW、目标 REQUIRE_APPROVAL → changed=1，`transitions["ALLOW->REQUIRE_APPROVAL"]=1`，newlyRejected=1，incomparable=0）；迁移回填测试（`ReplayBatchMigrationUpgradeTest`）；`MigrationGovernanceTest` golden；契约 golden 不变。
- cloud：窗口路由带 `outcome`；面板矩阵只渲染非零对、四语 key 测试、旧批次无矩阵渲染。
- 栈验收：对 `pol-adr0041-team` 的历史执行（REQUIRE_APPROVAL）建 What-If，目标版本把规则改为 `Verdict.escalate(...)` → 汇总 `transitions["REQUIRE_APPROVAL->ESCALATE"] ≥ 1`、changed 一致、面板显示矩阵；再以 `Verdict.allow()` 为目标 → `newlyApproved` 与 `transitions["REQUIRE_APPROVAL->ALLOW"]` 一致。

## 10. 后续（不在本 ADR）

- 逐条查看端点（需另行评估隐私面）。
- 用回放密文而非明文 `input` 回放（ADR 0030 遗留）。
- 估值 `estimatedValueDelta` 对四态的扩展。
