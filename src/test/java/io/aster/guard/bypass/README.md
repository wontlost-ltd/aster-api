# Guard 绕过红队套件（wontlost-ltd/aster-api#466）

计划：`docs/superpowers/plans/2026-11-14-guard-bypass-suite.md`。本目录是阶段 A：aster-api 内的 JUnit 用例，
标签 `guard-bypass`，只由 Gradle 任务 `guardBypassTest` 运行（`test` / `integrationTest` 已排除该标签）。

## 运行

```bash
# 需要 Postgres 与 Redis（同 :test 的本地配方）
DB_JDBC_URL=jdbc:postgresql://localhost:5432/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres \
  ./gradlew guardBypassTest
```

- `retry` 0 次：安全门不容忍「重试后通过」。
- 一个测试都没执行时任务失败（标签被误删、类被误排除都会变红）。
- `check` 依赖本任务，`deploy.yml` 的 `./gradlew build` 因而在发版构建中同样运行。
- CI 必需检查 `Guard bypass suite (#466)`（`ci.yml` 汇总作业）只判定本任务所在步骤；它**不**覆盖 aster-cloud
  Playwright（本地栈手动运行）与部署层探针（阶段 C）。

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
| 1 | 不经 Guard 直接调用（部署层） | `B1DirectConnectToolBypassTest` | **部署层未验证**：`@Disabled`，阶段 C 由 k3s `scripts/guard-bypass/direct-connect-probe.sh` 判定 |
| 2 | DENY 后执行 | `B2ExecuteAfterDenyBypassTest` | 启用：DENY / PENDING / REJECTED 409 `not_allowed`、自批 403、同幂等键改动作 422 |
| 3 | 收据改参 | `B3TamperedParamsBypassTest` | 启用：改金额 / 目标 / null 与 "" 与缺省 / 收据挪用 → 409 `action_mismatch` |
| 4 | 收据重放 | `B4ReceiptReplayBypassTest` | 启用：二次 consume 409 `receipt_consumed`、8 线程并发恰一个 200、结果不可改写 |
| 5 | 伪造 / 篡改 / 过期收据 | `B5ForgedReceiptBypassTest`、`B5RevokedKidBypassTest` | 启用：本地拒绝篡改 / 攻击者签名 / 未知 kid / 他租户 / 嫁接 / 未生效；服务端 409 `receipt_expired`、`kid_revoked` |
| 6 | 注入指令驱动 Agent | `B6PromptInjectionBypassTest` | `@Disabled`：阶段 B 待 #464（aster-guard 0.2.0）发布 |
| 7 | 自报输入篡改（高风险档案） | `B7TamperedSelfReportedInputBypassTest` | 启用：DENY `unverified_input`；`invalid_fact` 的 `claim_mismatch / bad_signature / unknown_issuer / expired`；两条正向对照 |
| 7 | 自报输入填小使规则直接 ALLOW（高风险档案，#510） | `B7bAllowSkipsProvenanceGateBypassTest` | 启用：HMAC evaluate-source 得 ALLOW → from-evidence 未附事实 DENY `unverified_input`、无收据、consume 409；附事实 ALLOW 执行恰一次；不要求 verifiedInputs 的档案仍 409 `evidence_not_approvable`（ADR 0046 §11.8） |
| — | 抹掉对账证据（#465） | `io.aster.guard.reconcile.GuardReconciliationAuthBypassTest` | 启用：MEMBER 确认孤儿 403、无凭证上报 / 读取 401、他租户不可见、自报 `X-Tenant-Id` 403；ADMIN 确认 200 |

`ReferenceExecutorSelfTest` 是套件自检（诚实路径会执行、攻击者原语只在 200 时写账本、本地检查会拦）。

伪造签名类收据的判定点是执行方本地验签：服务端没有「提交收据」入口（ADR 0040 §12 D6 末段），生产执行方由
aster-guard SDK 的夹具层（#464，ADR 0054 §7）证明同一性质。

## 后续阶段的启用条件

- 阶段 B：`@aster-cloud/aster-guard` 0.2.0 发布（含 Trusted Publisher），`e2e/guard-bypass/` 拉起代理与脚本化
  被注入 Agent，启用 `B6PromptInjectionBypassTest`。
- 阶段 C：集群中出现模型命名空间与第一个 HTTP 工具端点（ADR 0054 §8 ⑤）后，探针接入部署后 smoke，
  `B1DirectConnectToolBypassTest` 的 `@Disabled` 原因改为指向探针运行记录；在此之前 #466 不关闭。
- aster-cloud 本地栈场景（C2–C7）见 aster-cloud `src/__tests__/e2e-browser/local-stack/guard-bypass.spec.ts`，
  不进任何 CI（跟进项 F1）。
