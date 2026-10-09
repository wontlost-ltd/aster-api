# ADR 0043 What-If 四态回放 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** What-If 批次以统一结论码逐条记录基线/目标结论并按转移对汇总，旧布尔列与旧汇总键原样保留。

**Architecture:** aster-api 新增 `ReplayOutcome`（六值）与两条映射（目标侧由 `DecisionInterpreter`，基线侧由 cloud 窗口 `outcome` 或 `decision/success` 折算）；`replay_batch_item` 增 `base_outcome/target_outcome/base_legacy`；`ReplayBatchRunner.decide` 按可比性计算 changed 与转移矩阵；`summaryOf` 增四个键。aster-cloud 窗口接口多选一列，面板渲染转移矩阵。

**Tech Stack:** Quarkus/Panache/Flyway（下一个 V6.30.0）、@QuarkusTest（sdd 容器 55433/6380）；Next.js 16、vitest、`DEMO_SUPPLEMENT` 四语。

**Spec:** `docs/adr/0043-whatif-four-state-replay.md`。代码摘录：`/private/tmp/aster-stack/adr0043-refs.md`（§1–§10，下文「refs §n」）。

## Global Constraints

- 注释一律简体中文、只写意图；禁止「修改说明」式注释；缩进 ≤ 3 层；沿用既有 import 顺序；提交 trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`；不推送。
- 结论码集合逐字：`ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE, INDETERMINATE, ERROR`。
- 目标侧映射：`DecisionInterpreter.Verdict` APPROVED→ALLOW、DENIED→DENY、REQUIRE_APPROVAL→REQUIRE_APPROVAL、ESCALATE→ESCALATE、INDETERMINATE→INDETERMINATE；回放异常→ERROR（`failure_kind` 照旧）。
- 基线侧映射：窗口 `outcome` 非空且在六值内 → 直接采用、`legacy=false`；`outcome` 非空但不在六值内 → ERROR、`legacy=false`、warn；`outcome` 为空 → 按 `decision`：`approved→ALLOW, denied→DENY, require_approval→REQUIRE_APPROVAL, escalate→ESCALATE, indeterminate→INDETERMINATE, error→ERROR`，`legacy=true`；`decision` 也为空 → `success ? ALLOW : DENY`，`legacy=true`。
- `comparable(o) = o ∈ {ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE}`；`changed` = 两侧可比且不等；`incomparable` = 任一侧不可比；`newlyApproved` = 基线可比且非 ALLOW、目标 ALLOW；`newlyRejected` = 基线 ALLOW、目标可比且非 ALLOW。
- `base_approved/target_approved` 继续写入（= outcome == ALLOW）；INDETERMINATE 目标 `success=true`、`target_outcome='INDETERMINATE'`、`target_approved=false`。
- `result_summary` 新键：`transitions`（`"BASE->TARGET"` → 计数，仅非零）、`byTargetOutcome`（六值全部给出，含 0）、`incomparable`、`legacyBase`；旧四键 + `estimatedValueDelta` 不变。
- 不新增逐条端点；不发审计事件；不改 HMAC 签名。
- api 测试命令：`DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380 ./gradlew --offline :test --tests '...'`；IT 用 `:integrationTest`。cloud 门禁：`pnpm exec tsc --noEmit -p tsconfig.json && pnpm lint && pnpm test:run && pnpm check:locales:strict`。绝不触碰 `aster-*` 容器。

## Review Focus

1. 旧 cloud 窗口不带 `outcome`（字段缺失）→ 全部走折算且 `legacyBase = plannedCount`，不报错 —— Task 4 IT 用例。
2. 窗口 `decision` 大小写：cloud 真实值为小写；大写值（旧测试夹具）不得被当成 ERROR —— Task 1 折算对大小写不敏感，单测。
3. 旧批次（迁移前完成）的 `result_summary` 无新键 → 面板不渲染矩阵、不崩 —— Task 5 组件测试。
4. `Completed` 不变量：`newlyApproved + newlyRejected ≤ changed ≤ totalSampled - incomparable` —— Task 3 紧凑构造器与测试。
5. 回填：迁移前 `success=false` 的行 `target_outcome` 保持 null，不得被回填成 DENY —— Task 2 迁移文本测试。

---

### Task 1: `ReplayOutcome` 与两条映射（aster-api）

**Files:**
- Create: `src/main/java/io/aster/policy/replay/batch/ReplayOutcome.java`
- Test: `src/test/java/io/aster/policy/replay/batch/ReplayOutcomeTest.java`

**Interfaces:**
- Produces:
```java
public enum ReplayOutcome {
  ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE, INDETERMINATE, ERROR;
  public boolean comparable();                                   // 四个 Verdict 态为 true
  public static ReplayOutcome fromVerdict(DecisionInterpreter.Verdict v);
  /** 基线折算结果：outcome 与是否来自折算（legacy）。 */
  public record Base(ReplayOutcome outcome, boolean legacy) {}
  public static Base fromWindow(String outcome, String decision, boolean success);
  public static String transitionKey(ReplayOutcome base, ReplayOutcome target); // "ALLOW->DENY"
}
```

- [ ] **Step 1: 写失败测试**

```java
class ReplayOutcomeTest {
  @Test void 目标侧五值一一映射() {
    assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.APPROVED)).isEqualTo(ReplayOutcome.ALLOW);
    assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.DENIED)).isEqualTo(ReplayOutcome.DENY);
    assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.REQUIRE_APPROVAL)).isEqualTo(ReplayOutcome.REQUIRE_APPROVAL);
    assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.ESCALATE)).isEqualTo(ReplayOutcome.ESCALATE);
    assertThat(ReplayOutcome.fromVerdict(DecisionInterpreter.Verdict.INDETERMINATE)).isEqualTo(ReplayOutcome.INDETERMINATE);
  }
  @Test void 基线侧outcome优先且不标legacy() {
    assertThat(ReplayOutcome.fromWindow("REQUIRE_APPROVAL", "approved", true))
        .isEqualTo(new ReplayOutcome.Base(ReplayOutcome.REQUIRE_APPROVAL, false));
  }
  @Test void 基线侧未知outcome为ERROR() {
    assertThat(ReplayOutcome.fromWindow("MAYBE", "approved", true).outcome()).isEqualTo(ReplayOutcome.ERROR);
  }
  @Test void 基线侧无outcome按decision折算且大小写不敏感() {
    assertThat(ReplayOutcome.fromWindow(null, "escalate", false)).isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ESCALATE, true));
    assertThat(ReplayOutcome.fromWindow("", "APPROVED", false)).isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ALLOW, true));
    assertThat(ReplayOutcome.fromWindow(null, "error", true).outcome()).isEqualTo(ReplayOutcome.ERROR);
  }
  @Test void 基线侧无decision按success折算() {
    assertThat(ReplayOutcome.fromWindow(null, null, true)).isEqualTo(new ReplayOutcome.Base(ReplayOutcome.ALLOW, true));
    assertThat(ReplayOutcome.fromWindow(null, " ", false)).isEqualTo(new ReplayOutcome.Base(ReplayOutcome.DENY, true));
  }
  @Test void 可比性与转移键() {
    assertThat(ReplayOutcome.ERROR.comparable()).isFalse();
    assertThat(ReplayOutcome.INDETERMINATE.comparable()).isFalse();
    assertThat(ReplayOutcome.REQUIRE_APPROVAL.comparable()).isTrue();
    assertThat(ReplayOutcome.transitionKey(ReplayOutcome.ALLOW, ReplayOutcome.ESCALATE)).isEqualTo("ALLOW->ESCALATE");
  }
}
```

- [ ] **Step 2: 运行确认失败**（编译失败）
- [ ] **Step 3: 实现**

```java
/** What-If 的统一结论码（ADR 0043 §2）：与 cloud Execution.outcome 同一套大写码。 */
public enum ReplayOutcome {
  ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE, INDETERMINATE, ERROR;

  private static final Set<ReplayOutcome> COMPARABLE = EnumSet.of(ALLOW, DENY, REQUIRE_APPROVAL, ESCALATE);

  public boolean comparable() { return COMPARABLE.contains(this); }

  public static ReplayOutcome fromVerdict(DecisionInterpreter.Verdict v) {
    return switch (v) {
      case APPROVED -> ALLOW; case DENIED -> DENY; case REQUIRE_APPROVAL -> REQUIRE_APPROVAL;
      case ESCALATE -> ESCALATE; case INDETERMINATE -> INDETERMINATE;
    };
  }

  public record Base(ReplayOutcome outcome, boolean legacy) {}

  /** 基线折算：outcome 列优先；缺失时按 decision（小写六值，忽略大小写），再缺按 success。 */
  public static Base fromWindow(String outcome, String decision, boolean success) {
    if (outcome != null && !outcome.isBlank()) {
      return new Base(parse(outcome.trim()).orElse(ERROR), false);
    }
    if (decision != null && !decision.isBlank()) {
      return new Base(switch (decision.trim().toLowerCase(Locale.ROOT)) {
        case "approved" -> ALLOW; case "denied" -> DENY; case "require_approval" -> REQUIRE_APPROVAL;
        case "escalate" -> ESCALATE; case "indeterminate" -> INDETERMINATE; default -> ERROR;
      }, true);
    }
    return new Base(success ? ALLOW : DENY, true);
  }

  public static String transitionKey(ReplayOutcome base, ReplayOutcome target) { return base.name() + "->" + target.name(); }

  private static Optional<ReplayOutcome> parse(String s) {
    try { return Optional.of(valueOf(s)); } catch (IllegalArgumentException e) { return Optional.empty(); }
  }
}
```
（未知 outcome 的 warn 日志放在调用处 Task 4，枚举保持纯函数。）

- [ ] **Step 4: 运行通过**：`./gradlew --offline :test --tests 'io.aster.policy.replay.batch.ReplayOutcomeTest'`
- [ ] **Step 5: 提交** `feat(replay): ReplayOutcome 结论码与基线/目标映射（ADR 0043 §2）`

### Task 2: V6.30.0 迁移与实体列

**Files:**
- Create: `src/main/resources/db/migration/V6.30.0__replay_batch_item_outcomes.sql`（内容逐字取 ADR §3）
- Modify: `src/main/java/io/aster/policy/replay/batch/ReplayBatchItemEntity.java`（refs §4）、`src/test/resources/db/migration-checksums.golden`
- Test: `src/test/java/io/aster/policy/replay/batch/ReplayBatchMigrationUpgradeTest.java`（refs §5 模式）、`src/test/java/io/aster/db/MigrationGovernanceTest.java`

**Interfaces:**
- Produces: `ReplayBatchItemEntity.baseOutcome`（`base_outcome`, String）、`targetOutcome`（`target_outcome`）、`baseLegacy`（`base_legacy`, boolean）；构造器 `ReplayBatchItemEntity(UUID batchId, String executionId, boolean baseApproved, String baseOutcome, boolean baseLegacy)`，旧 3 参构造器保留并委托（`baseOutcome = baseApproved ? "ALLOW" : "DENY"`, `baseLegacy = true`）。

- [ ] **Step 1: 写失败测试**（`ReplayBatchMigrationUpgradeTest` 新增）
```java
  private static String outcomesMigration() throws Exception {
    return Files.readString(Path.of("src/main/resources/db/migration/V6.30.0__replay_batch_item_outcomes.sql"));
  }
  @Test void 四态迁移先回填再建约束_失败行目标不回填() throws Exception {
    String sql = outcomesMigration();
    int addBase = sql.indexOf("ADD COLUMN base_outcome");
    int backfillBase = sql.indexOf("SET base_outcome = CASE");
    int backfillTarget = sql.indexOf("SET target_outcome = CASE");
    assertThat(addBase).isLessThan(backfillBase);
    assertThat(sql.substring(backfillTarget)).contains("success = true");
    assertThat(sql).contains("base_legacy = true");
    assertThat(sql).contains("CHECK (base_outcome IS NULL OR base_outcome IN ('ALLOW','DENY','REQUIRE_APPROVAL','ESCALATE','INDETERMINATE','ERROR'))");
  }
```
- [ ] **Step 2: 运行确认失败**（文件不存在）
- [ ] **Step 3: 实现**：迁移文件（ADR §3 的六条语句，首行中文注释说明目的）；实体加三列与五参构造器；`MigrationGovernanceTest` 首跑给出 golden 行并追加。
- [ ] **Step 4: 运行**：`MigrationGovernanceTest`、`ReplayBatchMigrationUpgradeTest`、`io.aster.policy.replay.batch.*` PASS
- [ ] **Step 5: 提交** `feat(replay): replay_batch_item 结论码列与回填（ADR 0043 §3）`

### Task 3: `ItemResult` / `Completed` / `decide` 四态化

**Files:**
- Modify: `src/main/java/io/aster/policy/replay/batch/ReplayBatchRunner.java`（refs §3）、`src/main/java/io/aster/policy/replay/batch/ReplayBatchOutcome.java`（refs §3）、`src/main/java/io/aster/policy/replay/batch/ReplayBatchService.java` 的 `summaryOf`（refs §2）
- Test: `src/test/java/io/aster/policy/replay/batch/ReplayBatchRunnerTest.java`、`src/test/java/io/aster/policy/replay/batch/ReplayBatchSummaryTest.java`（新，纯单测 `summaryOf` 改为包级静态）

**Interfaces:**
- Produces:
```java
public record ItemResult(String sourceExecutionId, ReplayOutcome baseOutcome, boolean baseLegacy,
                         ReplayOutcome targetOutcome, BigDecimal valueDelta, ReplayFailureKind failureKind) {
  public static ItemResult ok(String id, ReplayOutcome base, boolean baseLegacy, ReplayOutcome target, BigDecimal valueDelta);
  public static ItemResult failed(String id, ReplayOutcome base, boolean baseLegacy, ReplayFailureKind kind); // target = ERROR
  public boolean baseApproved();   // base == ALLOW
  public boolean targetApproved(); // target == ALLOW
}
record Completed(int changed, int newlyApproved, int newlyRejected, int totalSampled, BigDecimal estimatedValueDelta,
                 Map<String,Integer> transitions, Map<ReplayOutcome,Integer> byTargetOutcome, int incomparable, int legacyBase)
```
`Completed` 紧凑构造器保留旧不变量并加：`incomparable + changed ≤ totalSampled`、`transitions` 值之和 == totalSampled、`byTargetOutcome` 含六个键。`summaryOf` 输出 Global Constraints 的键。

- [ ] **Step 1: 写失败测试**

`ReplayBatchRunnerTest` 的 `ok(...)` 助手改为 `ok(id, ReplayOutcome base, ReplayOutcome target)`（legacy=false），全部既有用例按 `true→ALLOW,false→DENY` 迁移；新增：
```java
  @Test void 四态变化按可比性统计() {
    var c = (ReplayBatchOutcome.Completed) ReplayBatchRunner.decide(6, List.of(
        ok("e1", ReplayOutcome.ALLOW, ReplayOutcome.REQUIRE_APPROVAL),   // changed, newlyRejected
        ok("e2", ReplayOutcome.REQUIRE_APPROVAL, ReplayOutcome.ESCALATE), // changed
        ok("e3", ReplayOutcome.DENY, ReplayOutcome.ALLOW),                // changed, newlyApproved
        ok("e4", ReplayOutcome.ALLOW, ReplayOutcome.INDETERMINATE),       // incomparable
        ok("e5", ReplayOutcome.ERROR, ReplayOutcome.ALLOW),               // incomparable（基线不可比）
        ok("e6", ReplayOutcome.ALLOW, ReplayOutcome.ALLOW)));
    assertThat(c.changed()).isEqualTo(3);
    assertThat(c.newlyRejected()).isEqualTo(1);
    assertThat(c.newlyApproved()).isEqualTo(1);
    assertThat(c.incomparable()).isEqualTo(2);
    assertThat(c.transitions()).containsEntry("ALLOW->REQUIRE_APPROVAL", 1).containsEntry("ERROR->ALLOW", 1).doesNotContainKey("DENY->DENY");
    assertThat(c.byTargetOutcome()).containsEntry(ReplayOutcome.ALLOW, 3).containsEntry(ReplayOutcome.DENY, 0);
  }
  @Test void legacy基线计数() {
    var c = (ReplayBatchOutcome.Completed) ReplayBatchRunner.decide(2, List.of(
        ReplayBatchRunner.ItemResult.ok("e1", ReplayOutcome.ALLOW, true, ReplayOutcome.ALLOW, null),
        ok("e2", ReplayOutcome.DENY, ReplayOutcome.DENY)));
    assertThat(c.legacyBase()).isEqualTo(1);
    assertThat(c.changed()).isZero();
  }
```
`ReplayBatchSummaryTest`：`summaryOf(c)` 的 Map 含 `transitions`（键串）、`byTargetOutcome`（键为 `name()` 串、六键）、`incomparable`、`legacyBase`，且旧五键仍在、顺序 changed 在前。

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**（`decide` 循环：
```java
      boolean comparable = r.baseOutcome().comparable() && r.targetOutcome().comparable();
      transitions.merge(ReplayOutcome.transitionKey(r.baseOutcome(), r.targetOutcome()), 1, Integer::sum);
      byTarget.merge(r.targetOutcome(), 1, Integer::sum);
      if (r.baseLegacy()) legacyBase++;
      if (!comparable) { incomparable++; continue; }
      if (r.baseOutcome() != r.targetOutcome()) {
        changed++;
        if (r.targetOutcome() == ReplayOutcome.ALLOW) newlyApproved++;
        else if (r.baseOutcome() == ReplayOutcome.ALLOW) newlyRejected++;
        if (r.valueDelta() != null) delta = delta == null ? r.valueDelta() : delta.add(r.valueDelta());
      }
```
`byTarget` 用 `EnumMap` 预填六键为 0；`transitions` 用 `TreeMap` 保证输出稳定。`summaryOf` 改为包级 `static Map<String,Object> summaryOf(Completed c)`，`byTargetOutcome` 以 `name()` 为键。）
- [ ] **Step 4: 运行** `io.aster.policy.replay.batch.*` PASS
- [ ] **Step 5: 提交** `feat(replay): 批次判定按四态可比性统计并输出转移矩阵（ADR 0043 §4/§5）`

### Task 4: 服务接线、窗口 `outcome`、契约与端到端

**Files:**
- Modify: `src/main/java/io/aster/policy/replay/batch/ExecutionWindowClient.java`（`WindowedExecution` 增 `String outcome`，`toExecution` 读 `o.getString("outcome")`；`baseApproved()` 改为 `base().outcome() == ALLOW`，新增 `ReplayOutcome.Base base()`）
- Modify: `src/main/java/io/aster/policy/replay/batch/ReplayBatchService.java`：冻结落库用五参构造器（refs §2 :931-933）；`replayOne` 返回带结论码的 `ItemResult`（INDETERMINATE 不再伪装；异常 → `failed(id, base, legacy, kind)`）；段循环写 `item.targetOutcome = r.targetOutcome().name()`（成功行写结论码；失败行写 `'ERROR'`，与 ADR §7 一致；迁移回填只对历史 success 行补值，历史失败行保持 null）；`persistSegment` 的 UPDATE 增 `target_outcome = ?`；`collectResults` 读新列（`base_outcome` 为 null 的旧行按 `baseApproved` 折算、legacy=true）
- Modify: `src/test/java/io/aster/policy/rest/ReplayBatchResponseContractTest.java`（完成态 `result` 可含新键的正例）
- Test: `src/test/java/io/aster/policy/replay/batch/ReplayBatchEndToEndIT.java`（refs §5）新增 Verdict 用例；`ExecutionWindowInputShapeTest` 夹具加 `outcome`

**Interfaces:**
- Consumes: Task 1–3。

- [ ] **Step 1: 写失败测试**（IT 新增）
```java
  private static final String VERDICT_BASE =
      "Module M.\n\nDefine Req has action.\n\nRule decide given r as Req, produce Verdict:\n  If r.action equals to \"delete\":\n    Return Verdict.allow().\n  Otherwise:\n    Return Verdict.deny().\n";
  private static final String VERDICT_TARGET =
      "Module M.\n\nDefine Req has action.\n\nRule decide given r as Req, produce Verdict:\n  If r.action equals to \"delete\":\n    Return Verdict.require_approval(\"DPO\", \"review\").\n  Otherwise:\n    Return Verdict.deny().\n";

  @Test void Verdict策略的四态变化进入转移矩阵() {
    // 窗口三条：v1 基线 outcome=ALLOW（新 cloud）、v2 基线 outcome 缺失但 decision=approved（旧 cloud）、v3 基线 DENY
    stubWindow(List.of(execVerdict("v1", "delete", "ALLOW", null), execVerdict("v2", "delete", null, "approved"), execVerdict("v3", "read", "DENY", null)));
    stubTarget(VERDICT_TARGET);
    UUID batchId = seedPendingBatch();
    service.claimNextPending(); service.runBatch(batchId);
    JsonObject summary = summaryOf(batchId);
    assertThat(summary.getInteger("changed")).isEqualTo(2);
    assertThat(summary.getInteger("newlyRejected")).isEqualTo(2);
    assertThat(summary.getJsonObject("transitions").getInteger("ALLOW->REQUIRE_APPROVAL")).isEqualTo(2);
    assertThat(summary.getJsonObject("transitions").getInteger("DENY->DENY")).isEqualTo(1);
    assertThat(summary.getInteger("incomparable")).isZero();
    assertThat(summary.getInteger("legacyBase")).isEqualTo(1);
    assertThat(summary.getJsonObject("byTargetOutcome").getInteger("REQUIRE_APPROVAL")).isEqualTo(2);
    // 逐条列
    assertThat(itemColumn(batchId, "v2", "base_outcome")).isEqualTo("ALLOW");
    assertThat(itemColumn(batchId, "v2", "base_legacy")).isEqualTo("true");
    assertThat(itemColumn(batchId, "v1", "target_outcome")).isEqualTo("REQUIRE_APPROVAL");
    assertThat(itemColumn(batchId, "v1", "target_approved")).isEqualTo("false");
  }
```
（`execVerdict/stubWindow/stubTarget/summaryOf/itemColumn` 为本测试类内的小助手，按既有 `exec(...)` 与 `setUp` 写法实现。）既有布尔用例保持通过，并追加断言 `transitions["ALLOW->DENY"]==1`、`incomparable==0`。
`ReplayBatchResponseContractTest`：完成态 `resultSummary` 含 `transitions` 时 `result.transitions` 为 Map 对象（非字符串）。

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**（见 Files；未知 `outcome` 值在 `toExecution`/冻结处 `Log.warnf` 一次）
- [ ] **Step 4: 运行** `io.aster.policy.replay.batch.*`、`io.aster.policy.rest.ReplayBatch*`、`:integrationTest --tests 'io.aster.policy.replay.batch.ReplayBatchEndToEndIT'` PASS；`PublicApiContractTest` golden 不变
- [ ] **Step 5: 提交** `feat(replay): 窗口 outcome 接入、逐条结论码落库与四态汇总（ADR 0043 §2–§6）`

### Task 5: cloud 窗口接口与面板矩阵

**Files:**
- Modify: `src/app/api/internal/executions/window/route.ts`（refs §7：`columns` 加 `outcome: true`）、`src/__tests__/api/internal-executions-window.test.ts`（断言 `captured.columns.outcome === true`）
- Modify: `src/components/policy/whatif-batch-panel.tsx`（refs §8：`result` 类型增可选 `transitions?: Record<string, number>; byTargetOutcome?: Record<string, number>; incomparable?: number; legacyBase?: number`；COMPLETED 块在三个 Metric 之后渲染 `<TransitionMatrix transitions={…} />`（仅当 `transitions` 存在且有非零项），再渲染两行说明：`incomparable > 0` → `t('incomparable', {count})`，`legacyBase > 0` → `t('legacyBase', {count})`）
- Modify: `src/i18n/demo-supplement.ts` 四语 `whatIf.transitionsTitle`、`whatIf.transitionFrom`、`whatIf.transitionTo`、`whatIf.incomparable`、`whatIf.legacyBase`、`whatIf.outcome.{ALLOW,DENY,REQUIRE_APPROVAL,ESCALATE,INDETERMINATE,ERROR}`
- Test: `src/__tests__/components/whatif-batch-panel.test.tsx`（新增：有矩阵时渲染非零对且不渲染零对；旧批次无 `transitions` 时不渲染矩阵标题；i18n 覆盖测试自然覆盖新 key）

- [ ] **Step 1: 写失败测试**
```tsx
  it('完成态渲染转移矩阵的非零对与说明计数', async () => {
    mockBatch({ status: 'COMPLETED', plannedCount: 3, result: { changed: 2, newlyApproved: 0, newlyRejected: 2, totalSampled: 3, estimatedValueDelta: null,
      transitions: { 'ALLOW->REQUIRE_APPROVAL': 2, 'DENY->DENY': 1 }, byTargetOutcome: { ALLOW: 0, DENY: 1, REQUIRE_APPROVAL: 2, ESCALATE: 0, INDETERMINATE: 0, ERROR: 0 }, incomparable: 0, legacyBase: 1 } });
    render(<WhatIfBatchPanel {...props} />);
    await screen.findByText(DEMO_SUPPLEMENT.en.whatIf.transitionsTitle);
    expect(screen.getByText('Allow → Needs approval')).toBeInTheDocument();   // 由 outcome.* 文案拼出
    expect(screen.queryByText(/Escalate → /)).toBeNull();
    expect(screen.getByText(/1 legacy baseline/)).toBeInTheDocument();
  });
  it('旧批次无 transitions 时不渲染矩阵', async () => { …result 只有旧五键… expect(screen.queryByText(DEMO_SUPPLEMENT.en.whatIf.transitionsTitle)).toBeNull(); });
```
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**：`TransitionMatrix` 为同文件内小组件：解析键 `BASE->TARGET`，按 `t(\`outcome.${BASE}\`) + ' → ' + t(\`outcome.${TARGET}\`)` 渲染一行 + 计数，过滤 0；文案例：en `transitionsTitle: 'Decision transitions'`, `incomparable: '{count} executions could not be compared (indeterminate or error on one side).'`, `legacyBase: '{count} legacy baseline(s) derived from the old boolean decision.'`，`outcome.ALLOW: 'Allow'`, `DENY: 'Deny'`, `REQUIRE_APPROVAL: 'Needs approval'`, `ESCALATE: 'Escalate'`, `INDETERMINATE: 'Indeterminate'`, `ERROR: 'Error'`；zh/de/hi 对应翻译。
- [ ] **Step 4: 全门禁 PASS**
- [ ] **Step 5: 提交** `feat(whatif): 窗口接口带 outcome，面板渲染四态转移矩阵（ADR 0043 §5）`

### Task 6: 全栈验收与 ADR 定稿

**Files:**
- Modify: `docs/adr/0043-whatif-four-state-replay.md`（ACCEPTED；§9.1 验收结果；按执行裁决回写）、`docs/adr/0034-*.md` §3.2/§6 改述「只落结论码」、`docs/adr/0039-*.md` §5.2 限制标记已解决

- [ ] **Step 1**：重建 api 镜像并重启（同 ADR 0042 Task 12 Step 1；V6.30.0 自动迁移）；`podman restart aster-cloud`。
- [ ] **Step 2 验收 1**：对 `pol-adr0041-team` 新建版本 2（规则改为 `Verdict.escalate("DPO","…")`），以 m-free 身份（`createPolicyApiClientForUser('team1','m-free').createWhatIfBatch(...)`，窗口含今日）→ 轮询 `getWhatIfBatch` 至 COMPLETED → `result.transitions["REQUIRE_APPROVAL->ESCALATE"] ≥ 1`、`changed == 该值`、`byTargetOutcome.ESCALATE` 一致、`incomparable` 为历史 `error` 行数。
- [ ] **Step 3 验收 2**：目标版本改为 `Verdict.allow()` → `newlyApproved == transitions["REQUIRE_APPROVAL->ALLOW"]`。
- [ ] **Step 4 验收 3**：面板（组件测试已覆盖矩阵）；SQL 检查 `replay_batch_item` 的 `base_outcome/target_outcome/base_legacy` 与窗口行一致。
- [ ] **Step 5**：ADR 定稿提交 `docs(adr): ADR 0043 定稿——回写修正并记录栈验收结果`。
