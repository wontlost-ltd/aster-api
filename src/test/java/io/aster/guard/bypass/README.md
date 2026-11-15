# Guard 绕过红队套件（wontlost-ltd/aster-api#466）

计划：`docs/superpowers/plans/2026-11-14-guard-bypass-suite.md`。本目录是阶段 A 与阶段 B：aster-api 内的 JUnit 用例，
标签 `guard-bypass`，只由 Gradle 任务 `guardBypassTest` 运行（`test` / `integrationTest` 已排除该标签）。
阶段 B 的代理层用例经 `ProxyHarness` 运行 `e2e/guard-bypass/` 的 Node 脚本与钉死版本的已发布包
`@aster-cloud/aster-guard@0.2.0`。

## 运行

```bash
# 阶段 B 需要 Node 24+ 与冻结安装的依赖（pnpm 版本取自该目录 package.json 的 packageManager）
(cd e2e/guard-bypass && corepack pnpm install --frozen-lockfile)
# 需要 Postgres 与 Redis（同 :test 的本地配方）
DB_JDBC_URL=jdbc:postgresql://localhost:5432/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres \
  ASTER_GUARD_BYPASS_REQUIRE_NODE=1 ./gradlew guardBypassTest
```

- `ASTER_GUARD_BYPASS_REQUIRE_NODE=1`（CI 设置）：缺 Node 24+ 或 `e2e/guard-bypass/node_modules` 时阶段 B 用例**失败**；
  未设置时以「本地无法运行阶段 B 代理用例：…」跳过（本地开发机可以不装 Node）。

- `retry` 0 次：安全门不容忍「重试后通过」。
- 一个测试都没执行时任务失败（标签被误删、类被误排除都会变红）。
- `check` 依赖本任务，`deploy.yml` 的 `./gradlew build` 因而在发版构建中同样运行。
- CI 必需检查 `Guard bypass suite (#466)`（`ci.yml` 汇总作业）只判定本任务所在步骤；它**不**覆盖 aster-cloud
  Playwright（本地栈手动运行）与部署层探针（阶段 C，由 k3s 工作流判定，不在本仓 CI）。

## 三样断言（`BypassAssertions`）

| 断言 | 含义 |
|---|---|
| `decisionRecorded` | `GET /api/v1/guard/decisions/{id}` 200，`effectiveOutcome` / `reasonCode` 相等 |
| `noSideEffect` | 内存账本 `SideEffectLedger` 中没有该决策的条目。只有参考执行方与攻击者原语在 consume **200** 后写账本，所以「账本为空」等价于「服务端没给出 200」 |
| `notConsumed` / `consumedExactlyOnce` | 收据行 `consumed_at` 与 `consume_audit_id`、`/api/v1/audit/receipts` 的 `consumes[].authoritative`、回读的 `consumedBy` 一致（对账 #465 上线前以同一数据代替） |

参考执行方 `ReferenceExecutor` 按 ADR 0040 §12 D6 的顺序本地验签（kid ∈ JWKS → Ed25519 → tenant / decision /
outcome → 时间窗 → actionHash），通过才 consume；`attackConsume` 跳过本地检查直接 consume。
套件 profile `BypassProfile` 打开 apikey + RBAC + HMAC，所有请求只带 Bearer key。

## 场景矩阵与启用状态

| # | #466 场景 | 类 | 状态 |
|---|---|---|---|
| 1 | 不经 Guard 直接调用（API 层） | `B1ApiDirectCallBypassTest` | 启用：无凭证 401、他租户 404（自报 `X-Tenant-Id` 403）、VIEWER 403、未 consume 回写 409 |
| 1 | 不经 Guard 直接调用（部署层） | `B1DirectConnectToolBypassTest` | **已验证（部署层，生产，2026-11-16）**：`@Disabled` 保持，判定在 k3s `guard-bypass-probe` 工作流（网络层与票据层各自独立通过，运行 43746736433；CronJob `aster-guard/guard-auth-probe` 通过）与 `redteam-window.sh` 两条反向记录（六行均符合预期）。记录：[生产探针](https://github.com/wontlost-ltd/aster-api/issues/466#issuecomment-6899245972)、[红队反向](https://github.com/wontlost-ltd/aster-api/issues/466#issuecomment-6899602342)、[vap-check](https://github.com/wontlost-ltd/k3s/pull/1258#issuecomment-6893725766) |
| 2 | DENY 后执行 | `B2ExecuteAfterDenyBypassTest` | 启用：DENY / PENDING / REJECTED 409 `not_allowed`、自批 403、同幂等键改动作 422 |
| 3 | 收据改参 | `B3TamperedParamsBypassTest` | 启用：改金额 / 目标 / null 与 "" 与缺省 / 收据挪用 → 409 `action_mismatch` |
| 4 | 收据重放 | `B4ReceiptReplayBypassTest` | 启用：二次 consume 409 `receipt_consumed`、8 线程并发恰一个 200、结果不可改写 |
| 5 | 伪造 / 篡改 / 过期收据 | `B5ForgedReceiptBypassTest`、`B5RevokedKidBypassTest` | 启用：本地拒绝篡改 / 攻击者签名 / 未知 kid / 他租户 / 嫁接 / 未生效；服务端 409 `receipt_expired`、`kid_revoked` |
| 2 | DENY 后执行（代理层，阶段 B） | `B2ProxyDenyBypassTest` | 启用：经已发布 `aster-guard-mcp`，规则 DENY → `status: denied`、上游未调用、链上无 consume 尝试；等审批超时 PENDING → `status: pending` 不转发，DPO 随后批准仍不转发 |
| 4 | 收据重放（代理层，阶段 B） | `B2ProxyDenyBypassTest.replayConsumeAfterProxyExecutedIs409` | 启用：代理执行 ALLOW 后低层重放 consume → 409 `receipt_consumed`（附代理的 `executorId / attemptId`），上游恰一次 |
| 6 | 注入指令驱动 Agent（阶段 B） | `B6PromptInjectionBypassTest` | 启用：脚本化被注入 Agent 经 `aster-guard-mcp`（`--isolation network`）① 删除 → DENY、不转发、链上无 consume 尝试；② 改参 `approved: true` 重试 → 新决策仍 DENY；③ 换工具退款 → 独立决策 ALLOW、上游恰一次且参数与 Guard 哈希的 `context` 逐字节相同；④ 无 key 自助读决策 / consume → 401 |
| 7 | 自报输入篡改（高风险档案） | `B7TamperedSelfReportedInputBypassTest` | 启用：DENY `unverified_input`；`invalid_fact` 的 `claim_mismatch / bad_signature / unknown_issuer / expired`；两条正向对照 |
| 7 | 自报输入填小使规则直接 ALLOW（高风险档案，#510） | `B7bAllowSkipsProvenanceGateBypassTest` | 启用：HMAC evaluate-source 得 ALLOW → from-evidence 未附事实 DENY `unverified_input`、无收据、consume 409；附事实 ALLOW 执行恰一次；不要求 verifiedInputs 的档案仍 409 `evidence_not_approvable`（ADR 0046 §11.8） |
| — | 抹掉对账证据（#465） | `io.aster.guard.reconcile.GuardReconciliationAuthBypassTest` | 启用：MEMBER 确认孤儿 403、无凭证上报 / 读取 401、他租户不可见、自报 `X-Tenant-Id` 403；ADMIN 确认 200 |

`ReferenceExecutorSelfTest` 是套件自检（诚实路径会执行、攻击者原语只在 200 时写账本、本地检查会拦）。
`ProxyHarnessSelfTest` 是阶段 B 夹具自检：ALLOW 的读取经代理转发上游恰一次，链上恰一条权威 consume 与一条
`executed` 回写——否则代理层用例里「上游账本为空」证明不了什么。

阶段 B 的「三样断言」：Guard 决策记录（`decisionRecorded`）、上游账本 `upstream-recorder.mjs` 写的 JSONL
（上游工具被调用一次写一行，没有行 = 副作用没发生）、链 / 行一致（被拒调用 `noConsumeAttempt`：连 consume 尝试都没有；
执行的调用 `executedOnceBy`：恰一条权威 consume 与一条 `executed` 回写，attemptId 等于代理回报值）。
注：规则自己的拒绝理由在代理载荷的 `reason / ruleId` 里，`reasonCode` 只承载系统原因码（如 `unverified_input`），
规则 DENY 不写——计划 Task B2 写的「reasonCode 非空」以此更正。

伪造签名类收据的判定点是执行方本地验签：服务端没有「提交收据」入口（ADR 0040 §12 D6 末段），生产执行方由
aster-guard SDK 的夹具层（#464，ADR 0054 §7）证明同一性质。

## 后续阶段的启用条件

- 阶段 B：**已启用**（`@aster-cloud/aster-guard` 0.2.0）。升级该包 = 改 `e2e/guard-bypass/package.json` 的精确版本并
  重新生成 lockfile，套件随之重跑（Gradle 任务以该目录为输入，构建缓存不会复用旧结论）。
  `deploy.yml` 的 `./gradlew build` 未安装 Node，发版构建里阶段 B 用例为 skipped（阶段 A 照常执行）；PR 的必需检查
  `Guard bypass suite (#466)` 覆盖阶段 B。
- 阶段 C：**已完成**（2026-11-16）。部署层判定在 k3s `guard-bypass-probe` 工作流（main 推送 / 每日定时 / 手动），
  `B1DirectConnectToolBypassTest` 保持 `@Disabled`，原因指向该工作流与 #466 记录（见上表第 1 条）。
- aster-cloud 本地栈场景（C2–C7）见 aster-cloud `src/__tests__/e2e-browser/local-stack/guard-bypass.spec.ts`，
  不进任何 CI（跟进项 F1）。
