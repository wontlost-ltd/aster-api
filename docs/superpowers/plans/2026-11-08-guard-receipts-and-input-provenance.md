# Guard 执行收据与输入来源实施计划（治理阶段 1：#463 · 阶段 2：#461）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阶段 1——ALLOW 决策签发 Ed25519 执行收据（绑定整个 `action` 的 `actionHash`），发布带 `revision` 的 JWKS，提供原子一次性 consume 与结果回写，三类新事件入链；阶段 2——Guard 请求逐字段标注 `verified / declared`，签名事实片段验签，Profile 要求 `verifiedInputs` 时按规则 `inputRefs` 在评估前 fail-closed（编译期 E708 走引擎发版列车）。

**Architecture:** 全部落在 aster-api 的 `io.aster.guard` 包：新增 `receipt` 子包（哈希、密钥、签名、consume / result 服务、两个资源）与 `provenance` 子包（签发方表、事实验签、来源判定、档案执行点）。持久化沿用 Panache 实体 + `GuardDecisionStore` 风格的接口（每方法自带 `requiringNew` 事务）、「先入链 → 再 `PESSIMISTIC_WRITE` 加锁判定，以行上的 audit id 为权威」模式（同 `GuardDecisionJpaStore.transition`）。私钥来自环境变量（k3s Secret 注入，同 HMAC 密钥先例），进程不直连 Vault。阶段 2 的编译期部分（`inputRefs`、E708、registry 1.2.0）在 aster-lang-core / ts / locales / test，按 ADR 0046 §11.4 的发版顺序；aster-api 侧执行点先行且在引擎发版前不改变任何现有行为。

**Tech Stack:** Quarkus 3 / RESTEasy Reactive / Hibernate Panache / Flyway / PostgreSQL 17；JDK 内建 `Ed25519`（`java.security.Signature` / `KeyPairGenerator` / `KeyFactory`，不引新依赖）；Jackson `ORDER_MAP_ENTRIES_BY_KEYS`；JUnit 5 + RestAssured + `@QuarkusTest`。

**Spec（按此顺序读）：**
- `docs/adr/0040-action-guard.md` §12（R63 执行收据 / consume / result / 密钥）与 §11（R60 来源模型 / 签名事实 / 执行点）
- `docs/adr/0041-evidence-chain-enrichment.md` §12（R64 consume / result 事件）与 §11（R61 收据键）
- `docs/adr/0046-governance-profile.md` §11（R62 `verifiedInputs` / `inputRefs` / E708）
- `docs/adr/0054-guard-execution-point.md`（执行点契约：本计划产出的接口的消费方）
- 风格与既有代码形状：`docs/superpowers/plans/2026-10-07-action-guard.md`、`2026-10-10-governance-profile.md`

## Global Constraints

- **ADR 状态门（硬门）**：用户已批准设计方向，但 R59–R64 与 ADR 0054 文本仍为 `PROPOSED`。Task 1 的状态翻转是单独的文档 PR，**由用户审阅并合并**；该 PR 合并前任何实现 PR 不得合并。
- **迁移编号（本计划裁定，覆盖 ADR 文本）**：按合并顺序，阶段 1 用 `V6.35.0__guard_execution_receipts.sql`，阶段 2 用 `V6.36.0__guard_input_provenance.sql`；#460（ADR 0042 §11 R59 审批输入快照）顺延为 `V6.37.0`。三份 ADR（0040 / 0042 / 0054 引用处）在 Task 1 同步改写。每条迁移在 `src/test/resources/db/migration-checksums.golden` 追加 `文件名=sha256`（`shasum -a 256`，LF）。
- **规范化配方 `jcs-sha256/v1`（取代 ADR 中 actionHash / 签名载荷用的 `sha256-sorted-json/v1`）**：SHA-256 hex（小写）作用于 **RFC 8785（JCS）** 规范化后的 UTF-8 字节串。字节级定义与语言无关：对象成员按键的 **UTF-16 码元序**排序；无空白；字符串先**解码全部转义**（`"\u00e9"` 与 `"é"` 是同一值），再按 RFC 8785 §3.2.2.2 输出（只转义 `"`、`\`、U+0000–U+001F，其中 `\b \t \n \f \r` 用短形式、其余 `\u00xx` 小写 hex；其余字符原样 UTF-8）；**不做 Unicode 正规化**（NFC 与 NFD 是不同值）；含孤立代理项（无法编码为 UTF-8）的字符串拒绝，与不安全整数同为 400（共享向量错误码 `invalid_string`）；数字一律按 IEEE 754 double 解释，按 RFC 8785 §3.2.2.3（ECMAScript `Number.prototype.toString`）输出：`1`、`1.0`、`1e0` 同为 `1`，`-0.0` 为 `0`，`1e21` 为 `1e+21`，按 I-JSON（RFC 7493 §2.2）**拒绝**：`action`（含阶段 2 签名事实的 `claims` 与载荷）中任何数值按 IEEE 754 double 解释后 |x| > 2^53−1（9007199254740991；超出此界的 double 必为整数，`9007199254740991.5` 这类小数写法同样拒绝）或非有限数 → 400 `invalid_action`（事实内为 `invalid_fact`），不做舍入；这类值（大金额、长 ID）必须以字符串发送。判定在解析后、哈希前进行（整数 token 按精确值比较；小数 token 按 double 判断 |x| > 2^53−1）；`true/false/null` 字面量保留，`null` 叶子与缺键是不同文档。依赖检查：运行期 classpath 无任何 JCS 实现（`./gradlew -q :dependencies --configuration runtimeClasspath | grep -i "jcs\|canonical\|erdtman\|webpki"` 无输出），因此按 RFC 8785 §3.2.2.3 自行实现 `JcsCanonicalizer`，以共享向量锁定。`contextHash` 仍用既有 `sha256-sorted-json/v1`（不改旧收据语义）。
- **重复键**：guard 请求体（`/decisions`、`/decisions/from-evidence`、`/receipts/*`、`/fact-issuers/*`）的解析器开启 `JsonParser.Feature.STRICT_DUPLICATE_DETECTION`；任一层出现重复键 → 400 `invalid_action`（`/receipts/*` 为 `invalid_consume` / `invalid_result`），不进入哈希。
- **`actionHash` 输入**：请求体中 `action` 子树**按服务端收到的 JSON**（含调用方写出的显式 `null`、未知键、阶段 2 的 `facts`），不是 `GuardActionRequest.Action` 再序列化（record 会补出 `"type":null` 等键，导致与执行方不一致）。
- **执行收据载荷**字母序：`{ actionHash, decisionId, expiresAt, issuedAt, kid, outcome:"ALLOW", policyVersionId?, tenantId, v:1 }`（`policyVersionId` 可选：决策无已保存版本——evaluateSource 锚定的 from-evidence 决策——时省略，收据照常签发；外层另带不受签名覆盖的签发时 `revision`）；签名 = Ed25519 作用于载荷 JCS 字节串（不二次哈希），base64url 无填充、64 字节。阶段 2 事实签名载荷与 claim 比对同样用 JCS 字节。
- **时间窗语义**：收据有效当且仅当 `now < expiresAt`（`expiresAt` **排他**），consume 在 `now >= expiresAt` 时 409 `receipt_expired`；执行方本地验签窗口为 `issuedAt − 60s <= now < expiresAt`。密钥 `notAfter` 只约束**签发与发布**：`now >= notAfter` 的 key 不再被选为 active（启动失败）且不再出现在 `/keys`；已签发收据的验签只看该 `kid` 是否仍在 JWKS 中（退役 = 从列表删除，之后验签失败）。**退役（retired，正常到期删除）**与**撤销（revoked: true，紧急）**区分：前者服务端 consume 照常（收据仍在 TTL 内即可），后者 consume 409 `kid_revoked`。
- **配置键**：`aster.guard.receipt.ttl=${ASTER_GUARD_RECEIPT_TTL:PT5M}`；`aster.guard.receipt.signing-keys=${ASTER_GUARD_RECEIPT_SIGNING_KEYS}`（JSON `{ "revision": <int>, "keys": [{ "kid", "privateKey": "<base64url 32 字节种子>", "notAfter"?: "<ISO-8601>", "revoked"?: true }] }`）；`aster.guard.receipt.active-kid=${ASTER_GUARD_RECEIPT_ACTIVE_KID}`；`aster.guard.result-grace=${ASTER_GUARD_RESULT_GRACE:PT15M}`（只供 #465 读取，本计划只登记键）。缺密钥 / `active-kid` 不在列表 / 种子非 32 字节 / `active-kid` 已 `revoked` → **启动失败**。
- **错误体**沿用 guard 现有形状 `{ "error": "<code>", ... }`；跨租户与非法 UUID 一律 404 空体（ADR 0040 §3.4）。
- **事件类型名**（≤ 50 字符，无需迁移）：`ACTION_GUARD_CONSUME_ATTEMPT`、`ACTION_GUARD_CONSUME_OUTCOME`、`ACTION_GUARD_RESULT`、`GUARD_FACT_ISSUER_REVOKED`。新身份键（免脱敏）：`actionHash, attemptId, executorId, resultHash, consumeReceiptHash, attemptReceiptHash`。
- **时间**：新服务一律注入 `java.time.Clock`（生产 `Clock.systemUTC()`，CDI producer 放 `io.aster.guard.receipt.GuardClockProducer`），测试用 `Clock.fixed` / 可变时钟；`NonDeterminismSourceTest` 白名单登记 `Clock` 的新使用点并写理由。
- 新路径加入 `public-api-contract.golden`（`PublicApiContractTest`）；`/api/v1/guard/*` 已在 `ApiKeyAuthFilter.shouldProtect`（`:249`），新增路径只需在 `ApiKeyAuthFilterShouldProtectTest` 加断言；HMAC 双通道路径加入 `InternalCallerFilter.isGuardDualChannel` 与 `ApiKeyAuthFilter.HMAC_BYPASS_*`。
- 代码注释、提交信息简体中文，不写「修改说明」式注释；提交信息末尾空行后加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 描述末尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`，`Refs #463` / `Refs #461`。
- 不新增依赖。不读取 k8s Secret、不连生产库；k3s 改动只写进 PR 描述由用户执行（见「部署与 k3s」）。
- 本地测试：podman `sweep-pg`（:558xx）/`sweep-redis`（:65xx），环境变量 `DB_JDBC_URL=jdbc:postgresql://localhost:<port>/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:<port>`。每个任务的 Step 4 跑本任务测试类后，**再跑完整 guard 套件**：`./gradlew --offline :test --tests 'io.aster.guard.*' --tests 'io.aster.audit.*' --tests 'io.aster.security.apikey.*' --tests 'io.aster.api.PublicApiContractTest' --tests 'io.aster.db.MigrationGovernanceTest' --tests 'io.aster.api.workflow.NonDeterminismSourceTest' --tests 'io.aster.policy.common.AuditIdentifierRedactionTest'`；**每个阶段开 PR 前**跑全量 `./gradlew --offline :replay:cleanTest :cleanTest :replay:test :test --continue`，已知基线失败（`PolicyGraphQLResourceTest.testBatchEvaluateLargeVolume`、`PrismaDatabaseE2ETest`）在 PR 中注明。**不要**执行 `./gradlew --stop`；`@QuarkusTest` 顺序运行；确认 `@QuarkusTest` 用例实际执行而非静默跳过。

## Review Focus

1. **显式 `null` / 缺键 / `""` 与 record 默认值**：调用方发 `{"principal":{"id":"a"}}`（无 `type`）与 `{"principal":{"id":"a","type":null}}` 必须得到不同 `actionHash`，且服务端存的哈希与执行方对**自己发出的 JSON** 自算的哈希逐字节相等。测试在 Task 5（`actionHashBindsRawJsonNotRecord`）。
2. **两个执行方并发 consume 同一收据**：恰一个 200，另一个 409 `receipt_consumed`，两条尝试事件只有一条被 `consume_audit_id` 引用。测试在 Task 8（`concurrentConsumeExactlyOneWins`）。
3. **审批后转 ALLOW 的收据时间窗与重放**：`issuedAt` 为审批加锁时刻；幂等重放与 `GET /decisions/{id}` 返回同一份已存收据（不续签），过期后 GET 仍返回原收据而 consume 409 `receipt_expired`。测试在 Task 5（`approvedReceiptIssuedAtApprovalAndNeverResigned`）。
4. **多副本密钥配置回退**：某副本以更小的 `revision` 启动必须失败；两副本同时以 N 与 N+1 启动（任一先后顺序）最终表中最大值为 N+1，且以 N 启动的副本**只有在 N+1 已提交后才**失败；同一 `revision` 并发启动不崩溃。测试在 Task 4（`revisionRegressionFailsStartup`、`concurrentNAndNPlus1BothOrders`、`concurrentSameRevisionStartupIsIdempotent`）。
5. **事实 claim 的数值形态**：claims `250000` 与 `action.context.amount` 为 `250000.0` / `2.5E5` 在 JCS 下字节相等 → **接受**（同一数值，与执行方哈希一致）；claims 为数字而 action 为字符串 `"250000"`（或反之）→ 400 `invalid_fact`，不得宽松比较放行。测试在 Task 12（`claimEqualityIsJcsBytes`）。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `src/main/java/io/aster/guard/receipt/JcsCanonicalizer.java` | RFC 8785 规范化（含 ECMAScript 数字格式化） |
| `src/main/java/io/aster/guard/receipt/ActionHasher.java` | JSON 树 → `jcs-sha256/v1` hex |
| `src/main/java/io/aster/guard/GuardRequestBodyReader.java` | guard 请求体解析：重复键检测 + `treeToValue` + Bean Validation，错误形状与现状一致 |
| `src/main/java/io/aster/guard/receipt/ReceiptSigningKeys.java` | 解析 / 校验密钥配置；种子 → `KeyPair`；`active()`、`find(kid)`、`revision()`、`isRevoked(kid)` |
| `src/main/java/io/aster/guard/receipt/ExecutionReceiptSigner.java` | 组载荷、签名、验签（测试与自检用） |
| `src/main/java/io/aster/guard/receipt/ExecutionReceipt.java` | 响应中的 `executionReceipt` 视图（record，字母序，可往返） |
| `src/main/java/io/aster/guard/receipt/GuardReceiptEntity.java` / `GuardSigningKeyRevisionEntity.java` | 两张新表 |
| `src/main/java/io/aster/guard/receipt/GuardReceiptStore.java` + `GuardReceiptJpaStore.java` | 收据行写入、加锁 consume / result 判定 |
| `src/main/java/io/aster/guard/receipt/GuardReceiptService.java` | consume / result 编排（先入链 → 加锁判定 → 结果事件） |
| `src/main/java/io/aster/guard/receipt/SigningKeyRevisionStartup.java` | 启动时 `revision` 单调校验 |
| `src/main/java/io/aster/guard/api/GuardKeysResource.java` / `GuardReceiptResource.java` | `GET /keys`；`POST /receipts/{id}/consume|result` |
| `src/main/java/io/aster/guard/provenance/*` | 阶段 2：`GuardFactIssuerEntity`、`FactIssuerStore`、`FactVerifier`、`InputSourceResolver`、`InputSources`、`VerifiedInputsGate`、`GuardFactIssuerResource`、`FactIssuerRevocationService` |
| `src/main/resources/db/migration/V6.35.0__guard_execution_receipts.sql` / `V6.36.0__guard_input_provenance.sql` | 两条迁移 |
| `src/test/resources/guard/jcs-sha256-v1-vectors.json` | 共享向量；#464 执行方 SDK（aster-guard `test/fixtures/jcs-sha256-v1-vectors.json`）必须逐字节复制并全部通过 |
| `docs/runbooks/guard-receipt-signing-keys.md` | 生成 / 轮换 / 紧急撤销操作手册 |

---

## 阶段 1：执行收据与一次性消耗（#463，ADR 0040 §12 R63 + ADR 0041 §12 R64）

### Task 1: ADR 定稿与迁移编号回写

**Files:**
- Modify: `docs/adr/0040-action-guard.md`（§11、§12 状态行；§11 D3/D4 与 §12 D2/D4/D6 的迁移号与配方名）、`docs/adr/0041-evidence-chain-enrichment.md`（§11、§12 状态行）、`docs/adr/0042-approval-closure.md`（§11 R59 状态行；`:247`、`:295`、`:334` 的 `V6.35.0` → `V6.37.0`）、`docs/adr/0046-governance-profile.md`（§11 状态行）、`docs/adr/0054-guard-execution-point.md`（状态行；§4 配方名与向量文件名）

**交付方式：** 单独的文档 PR，由用户审阅合并（硬门）；Task 2 起的实现 PR 在其合并后才可合并。

**Interfaces:**
- Produces: 后续任务引用的迁移号 `V6.35.0`（收据）/ `V6.36.0`（来源）。

- [ ] **Step 1:** 六处 `状态：**PROPOSED**…` 改为 `状态：**ACCEPTED**（2026-11-08 用户确认；实施计划 docs/superpowers/plans/2026-11-08-guard-receipts-and-input-provenance.md）`。
- [ ] **Step 2:** 迁移号：ADR 0040 §12 D4 `V6.37.0` → `V6.35.0`；§11 保持 `V6.36.0`；ADR 0042 §11 三处 `V6.35.0` → `V6.37.0`；§12 D4 括注改为「编号按合并顺序：R63（V6.35.0）→ R60（V6.36.0）→ R59（V6.37.0）」。§12 D6 删除 `aster.guard.receipt.revoked-kids`，只保留列表项 `"revoked": true`，并写入 Global Constraints 的「时间窗语义」（`expiresAt` 排他、`notAfter` 只约束签发与发布、退役 vs 撤销）。
- [ ] **Step 2b:** 配方：ADR 0040 §12 D1/D2/D6、§11 D3 与 ADR 0054 §4 中凡用于 `actionHash`、执行收据签名、事实签名 / claim 比对的 `sha256-sorted-json/v1` 改为 `jcs-sha256/v1`，并把 Global Constraints 中的字节级定义（RFC 8785、转义解码、无 Unicode 正规化、数字按 ECMAScript、重复键 400）替换 §12 D2「跨语言一致性」段；`contextHash` 处保持原名。ADR 0054 §4 的 Java `Double.toString` / `javaDoubleToString` 描述删除（JCS 下 TS 直接用 `Number#toString`），向量文件名改为 `jcs-sha256-v1-vectors.json`。ADR 0040 §12 D9 补一句：同键重放返回首次决策的收据，其 `actionHash` 是首次请求的哈希（指纹不含 `actionHash`）。
- [ ] **Step 3:** 校验：
  - `git grep -n "V6.3[5-7].0" docs/adr` 只命中：0040 §12（`V6.35.0`）、0040 §11 与 0041/0046 引用（`V6.36.0`）、0042 §11（`V6.37.0`）——逐行核对无错配；
  - `git grep -n "revoked-kids\|javaDoubleToString" docs/adr` 无输出；
  - `git grep -n "sha256-sorted-json" docs/adr/0040-action-guard.md docs/adr/0054-guard-execution-point.md` 只剩 `contextHash` 语境。
- [ ] **Step 4: 提交** `docs(adr): R60–R64 与 ADR 0054 定稿，迁移号按落地顺序回写`

### Task 2: `jcs-sha256/v1` 规范化、共享向量、重复键拒绝；`context` 放开 null 叶子

**Files:**
- Create: `src/main/java/io/aster/guard/receipt/JcsCanonicalizer.java`、`ActionHasher.java`、`src/test/resources/guard/jcs-sha256-v1-vectors.json`、`src/test/java/io/aster/guard/receipt/JcsCanonicalizerTest.java`、`GuardActionHashVectorsTest.java`
- Modify: `src/main/java/io/aster/guard/GuardActionMapper.java`（`:26` 注释、`:104-130` `freezeMap` 的 null 校验）、`src/main/java/io/aster/guard/GuardDecisionService.java`（`evaluate` 中 `Map.copyOf(base.action())` 遇 null 值抛 NPE，改为递归不可变的 `Collections.unmodifiableMap(new LinkedHashMap<>(…))`）
- Test: `src/test/java/io/aster/guard/GuardActionMapperTest.java`、`GuardDecisionServiceTest.java`

**Interfaces:**
- Produces: `public final class JcsCanonicalizer { public static byte[] canonicalize(JsonNode node); static String formatNumber(double d); }`——遇 NaN/Infinity 抛 `IllegalArgumentException`；`public final class ActionHasher { public String hash(JsonNode action); }`（`action` 为 null / `MissingNode` / 非对象 → `GuardInvalidActionException`）。阶段 2 的事实签名与 claim 比对也调用 `JcsCanonicalizer.canonicalize`。

- [ ] **Step 1: 写共享向量文件**（JSON 数组 `[{ "name", "input"（字符串，原文 JSON，保留转义与数字写法）, "canonical"（期望 JCS 文本）, "sha256" | "error": "duplicate_key" | "unsafe_integer" }]`）。必含：`1` / `1.0` / `1e0` 同哈希；`-0.0` → `0`；`0.1`、`1e-6` → `0.000001`、`1e-7` → `1e-7`、`1.5e-7` → `1.5e-7`、`123e-20` → `1.23e-18`（指数形式只能由非整数小值触发：大于 2^53−1 的 double 全是整数，会被 I-JSON 拒绝，所以向量文件**不存在**正大指数的通过用例；1e20 / 1e21 的格式化只在 `JcsCanonicalizerTest.fixedVsExponentBoundaries` 直接调用 `formatNumber` 检查）、`9007199254740991` → 原样；`1e20`、`1e21`、`9007199254740992`、`9007199254740993`、`123456789012345680000`、`-9007199254740992` → `"error": "unsafe_integer"`；`"\u00e9"` 与 `"é"` 同哈希；NFC `"é"` 与 NFD `"e\u0301"` 不同哈希；`"\u001f"` → `"\u001f"`、`"\t"` → `"\t"`、`"\/"` → `"/"`；键序 `{"b":1,"a":2}`、UTF-16 码元序 `{"\ud83d\ude00":1,"\uffff":2}`（emoji 代理对排在 U+FFFF 之前）；重复键 `{"a":1,"a":2}` 与嵌套重复键 → `error`；嵌套 null `{"a":{"b":null}}` vs `{"a":{}}` vs `{"a":{"b":""}}` 两两不同；ADR 0054 §3.2 `cc` 三例；`{"$sha256":"…"}` 摘要代入。文件头不能写注释（JSON），说明写进 `GuardActionHashVectorsTest` 的类 Javadoc：「#464 执行方 SDK 必须逐字节复制本文件并全部通过」。
- [ ] **Step 2: 写失败测试**

```java
// JcsCanonicalizerTest
@Test void rfc8785Appendix_B_numberSamples()  // RFC 8785 附录 B 表中全部 IEEE 值 → 期望字符串
@Test void nanAndInfinityRejected()
@Test void fixedVsExponentBoundaries()          // 1e-6→0.000001、1e-7→1e-7、1e20→100000000000000000000、1e21→1e+21、123e-20→1.23e-18、-0.0→0
@Test void unsafeIntegerIs400InvalidAction()    // 资源层：context.amount=9007199254740992 → 400 invalid_action；以字符串发送 → 200
// GuardActionHashVectorsTest
@Test void everyVectorMatches()               // 用开启 STRICT_DUPLICATE_DETECTION 的 mapper 读 input；有 error 的向量断言抛 JsonParseException，其余 canonical 与 sha256 均相等
// GuardActionMapperTest
@Test void nullLeafInContextIsAccepted()       // 删除 / 改写原「context 不允许 null 值」断言
@Test void contextHashUnchangedForExistingInputs()
// GuardDecisionServiceTest
@Test void nestedNullReachesRuleThroughEvaluation() // M6：context {a:{b:null}} → evaluate 不抛 NPE，规则收到的 Map 中 context.a.b 为 null 且 containsKey 为 true；规则可据此返回 Verdict
```

- [ ] **Step 3:** 跑本任务测试类 → FAIL。
- [ ] **Step 4: 实现。** `formatNumber` 严格按 ECMA-262 `Number::toString(x)`（RFC 8785 §3.2.2.3 引用之）：`x = 0`（含 −0）→ `0`；负数输出 `-` 后对 |x| 处理。取最短往返十进制表示：整数 `k ≥ 1`、`n` 与 k 位、不以 0 结尾的数字串 `s`，使 `x = s × 10^(n−k)`（`Double.toString` 在 JDK 19+ 给出最短往返有效数字，取其数字串与指数换算出 `s/k/n`）。然后：`k ≤ n ≤ 21` → `s` 后补 `n−k` 个 `0`；`0 < n ≤ 21` → `s` 前 n 位、`.`、其余位；`−6 < n ≤ 0` → `0.`、`−n` 个 `0`、`s`；否则指数形式：`k = 1` 为 `s` + `e` + 符号 + `|n−1|`，`k > 1` 为 `s[0]` + `.` + `s[1..]` + `e` + 符号 + `|n−1|`（符号恒写 `+`/`-`）。等价表述：**定点当且仅当 −6 < n ≤ 21，即 x = 0 或 1e-6 ≤ |x| < 1e21**。Javadoc 写明以上与「为何不依赖库」（classpath 无 JCS 实现）。数字节点先过 I-JSON 检查（Global Constraints），再取 `doubleValue()`。字符串经 Jackson 解码后按 §3.2.2.2 重新输出。排序用 `String.compareTo`（UTF-16 码元序）。
- [ ] **Step 5:** 本任务测试类 → PASS；完整 guard 套件（Global Constraints）→ PASS。
- [ ] **Step 6: 提交** `feat(guard): jcs-sha256/v1 规范化与共享向量，context 允许 null 叶子（ADR 0040 §12 D2）`

### Task 3: 签名密钥配置与 Ed25519 签名器

**Files:**
- Create: `src/main/java/io/aster/guard/receipt/ReceiptSigningKeys.java`、`ExecutionReceiptSigner.java`、`ExecutionReceipt.java`、`GuardClockProducer.java`
- Modify: `src/main/resources/application.properties`（`:286` 附近加 Global Constraints 中的四个键）、`src/test/resources/application.properties`（测试密钥：两把 key，`revision: 1`，`active-kid=test-a`；种子为测试专用常量并注释「仅测试」）
- Test: `src/test/java/io/aster/guard/receipt/ReceiptSigningKeysTest.java`、`ExecutionReceiptSignerTest.java`

**Interfaces:**
- Produces:
  - `record ExecutionReceipt(String kid, Payload payload, String signature)`，`record Payload(String actionHash, String decisionId, String expiresAt, String issuedAt, String kid, String outcome, Long policyVersionId, String tenantId, Integer v)`；二者 `@JsonPropertyOrder(alphabetic = true)` + `@JsonInclude(NON_EMPTY)`。
  - `ReceiptSigningKeys.parse(String json, String activeKid): ReceiptSigningKeys`（失败抛 `IllegalStateException`，消息含原因但**不含**种子）；`active(): SigningKey`；`find(String kid): Optional<SigningKey>`；`all(): List<SigningKey>`（含 revoked）；`revision(): long`；`record SigningKey(String kid, KeyPair keyPair, Instant notAfter, boolean revoked)`。
  - `ExecutionReceiptSigner.sign(String decisionId, String tenantId, String actionHash, long policyVersionId, Instant issuedAt): ExecutionReceipt`（`expiresAt = issuedAt + ttl`，用 `active()`）；`verify(ExecutionReceipt r): boolean`。

- [ ] **Step 1: 写失败测试**

```java
// ReceiptSigningKeysTest
@Test void rfc8032Vectors1To3() // RFC 8032 §7.1 TEST 1/2/3：种子 → 公钥逐字节相等，且对给定消息（空、0x72、0xaf82）签名逐字节等于 RFC 给出的签名
  // 种子 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60 → x = d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
@Test void emptyKeysFailsFast()        // 抛 IllegalStateException，消息含 "signing-keys"
@Test void activeKidMissingFailsFast()
@Test void seedNot32BytesFailsFast()
@Test void activeKidRevokedFailsFast()
@Test void duplicateKidFailsFast()
@Test void errorMessageNeverContainsSeed()
@Test void activeKidPastNotAfterFailsFast()     // M8：notAfter 约束签发
@Test void startupSelfCheckSignsAndVerifies()   // 每把 key 启动时签一段固定字节并用推导公钥验签，失败即启动失败
// ExecutionReceiptSignerTest
@Test void signedPayloadVerifiesWithJdkSignature() // Signature.getInstance("Ed25519") 用公钥复验规范化字节
@Test void anyPayloadByteChangeFailsVerify()       // 改 actionHash 末字符 / 换 signature → verify false
@Test void payloadSerializesAlphabeticallyWithV1AndOutcomeAllow()
@Test void expiresAtIsIssuedAtPlusTtl()            // ttl PT5M
@Test void verifyWindowBoundaries()                // 本地窗口：issuedAt−60s 有效、issuedAt−61s 无效、expiresAt−1ms 有效、expiresAt 恰好无效（排他）
```

- [ ] **Step 2:** 跑两测试类 → FAIL。
- [ ] **Step 3: 实现。** 私钥：`KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed))`——JDK 15+ 的公开 API，直接以 RFC 8032 的 32 字节种子为私钥，无需手拼 PKCS8 前缀或伪造 `SecureRandom`（后者依赖 provider 内部读随机数的次数，不是契约）。公钥：JDK 无「由私钥推导公钥」的公开 API，因此按 RFC 8032 §5.1.5 在启动时推导一次：`SHA-512(seed)` 前 32 字节按 §5.1.5 第 2 步裁剪得标量 `s`，计算 `[s]B` 并按 §5.1.2 编码为 32 字节，再 `KeyFactory.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint))`；点乘用 `BigInteger` 实现扩展坐标加倍-加法（只在启动时每 key 一次，性能无关）。在类 Javadoc 中写明上述理由。正确性由 RFC 8032 §7.1 向量 1–3 与启动自检（每把 key 签固定字节后用推导公钥验签，失败即启动失败）双重锁定。JWKS `x` = 该 32 字节编码。签名字节 = `JcsCanonicalizer.canonicalize(mapper.valueToTree(payload))`。`GuardClockProducer` `@Produces @ApplicationScoped Clock`（`@DefaultBean`）。
- [ ] **Step 4:** 跑两测试类 → PASS。
- [ ] **Step 5: 提交** `feat(guard): 执行收据签名密钥配置与 Ed25519 签名器（ADR 0040 §12 D1/D6）`

### Task 4: 迁移 `V6.35.0`、收据实体与 `revision` 启动校验

**Files:**
- Create: `src/main/resources/db/migration/V6.35.0__guard_execution_receipts.sql`、`src/main/java/io/aster/guard/receipt/GuardReceiptEntity.java`、`GuardSigningKeyRevisionEntity.java`、`SigningKeyRevisionStartup.java`
- Modify: `src/test/resources/db/migration-checksums.golden`、`src/main/java/io/aster/guard/GuardDecisionEntity.java`（`actionHash` 字段）
- Test: `src/test/java/io/aster/guard/receipt/GuardReceiptPersistenceTest.java`、`SigningKeyRevisionStartupTest.java`

**Interfaces:**
- Produces: 表 `guard_receipts`（列见 ADR 0040 §12 D4，`decision_id UUID PRIMARY KEY REFERENCES guard_decisions(id)`，`signature VARCHAR(128) NOT NULL`，`consumed_at/executor_id/attempt_id/consume_audit_id/result_status/result_at/result_audit_id` 可空，`CHECK (result_status IN ('executed','failed','aborted'))`，索引 `guard_receipts_tenant_consumed_ix (tenant_id, consumed_at)`）；`guard_decisions.action_hash VARCHAR(64)`；表 `guard_signing_key_revisions(revision BIGINT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL, kids JSONB NOT NULL)`。
  - `SigningKeyRevisionStartup.check(long revision, List<String> kids)`：`revision < max` → 抛 `IllegalStateException("签名密钥配置 revision 回退")`；`== max` → 不写；`> max` → `INSERT`。整个「读 max → 比较 → 插入」在一个 `requiringNew` 事务内、先执行 `SELECT pg_advisory_xact_lock(<常量键，hashtext('guard_signing_key_revisions')>)` 串行化，所以并发启动看到的 `max` 一定包含已提交的更大值；插入仍用 `ON CONFLICT (revision) DO NOTHING` 兜底。以 `@Observes StartupEvent` 调用，在 Flyway 之后。

- [ ] **Step 1: 写失败测试**

```java
@Test void receiptRowRoundTrips()                     // 插入后按 decisionId 读回各列
@Test void receiptRequiresExistingDecision()          // 外键：无决策行插入失败
@Test void resultStatusCheckRejectsUnknown()
@Test void revisionRegressionFailsStartup()           // 先写 5，再 check(4, …) 抛
@Test void sameRevisionIsNoop()                       // check(5) 两次，表仍一行
@Test void concurrentSameRevisionStartupIsIdempotent() // 两线程 CountDownLatch 同时 check(6)，均不抛、表一行
@Test void concurrentNAndNPlus1BothOrders()   // 表中 max=5；用两个连接 + 显式持锁钩子分别构造「6 先拿锁」与「7 先拿锁」：6 先 → 两者都成功、max=7；7 先 → check(6) 抛回退、max=7。各重复 20 次无偶发
```

- [ ] **Step 2:** `--tests 'io.aster.guard.receipt.GuardReceiptPersistenceTest' --tests '*SigningKeyRevisionStartupTest' --tests 'io.aster.db.MigrationGovernanceTest'` → FAIL。
- [ ] **Step 3: 实现** 迁移、实体、启动观察者；`migration-checksums.golden` 追加一行。
- [ ] **Step 4:** 同 Step 2 → PASS。
- [ ] **Step 5: 提交** `feat(guard): V6.35.0 执行收据与密钥 revision 表（ADR 0040 §12 D4/D6）`

### Task 5: 签发——直接 ALLOW、from-evidence、审批 APPROVED

**Files:**
- Modify: `src/main/java/io/aster/guard/api/GuardDecisionResource.java:75-76`（`decide` / `fromEvidence` 改收原始请求体，经新建 `GuardRequestBodyReader.read(InputStream, Class<T>)`：用开启 `STRICT_DUPLICATE_DETECTION` 的 `ObjectReader` 读成 `JsonNode`（重复键 / 非法 JSON → 抛 `GuardInvalidActionException`）→ `ActionHasher.hash(body.get("action"))` → `treeToValue` → 注入的 `jakarta.validation.Validator.validate(...)`，违规映射为与今天 `@Valid` 失败**相同的状态码与响应体**；`treeToValue` 的类型错误映射为与今天 Jackson 反序列化失败相同的响应。实现前先用 Step 1 的回归测试录下现状响应作为 golden）、`GuardDecisionService.java`（`decide` 增参数 `String actionHash`，草稿携带；ALLOW 时在 `store.saveDecision` 同一事务插收据行）、`GuardEvidenceDecisionService.java:87-121`（同上）、`GuardApprovalService.java:205-215`（APPROVED 时在 `completeApproval` 的同一事务内签发并插行）、`GuardDecisionStore.java` / `GuardDecisionJpaStore.java`（`saveDecision(entity, approval, receiptOrNull)`；`completeApproval(decided, newResponseJson, receiptFactory)`，`receiptFactory: Function<Instant, GuardReceiptEntity>` 以加锁后的 `now` 调用，仅目标为 APPROVED 时非 null）、`GuardDecisionDraft.java`（`actionHash` 组件）、`GuardDecisionAssembler.java`（`toEntity` 写 `action_hash`）、`api/GuardDecisionResponse.java`（新组件 `ExecutionReceipt executionReceipt`，字母序位于 `effectiveOutcome` 之后；`withApprovalStatus` 保留它）
- Create: `src/main/java/io/aster/guard/GuardRequestBodyReader.java`、`src/test/resources/guard/invalid-request-responses.golden.json`
- Test: `src/test/java/io/aster/guard/api/GuardDecisionResourceTest.java`、`GuardApprovalResourceTest.java`、`GuardFromEvidenceResourceTest.java`、`GuardDecisionServiceTest.java`、`GuardInvalidRequestRegressionTest.java`

**Interfaces:**
- Consumes: `ActionHasher.hash`（Task 2）、`ExecutionReceiptSigner.sign`（Task 3）、`GuardReceiptEntity`（Task 4）。
- Produces: `GuardDecisionResponse.executionReceipt()`（仅 `effectiveOutcome == ALLOW` 时非 null）；决策行 `action_hash` 对所有新决策写入（含 DENY / PENDING，供审批后签发与对账）。

响应示例（ALLOW）：

```json
{
  "decisionId": "7c1e…", "effectiveOutcome": "ALLOW",
  "executionReceipt": {
    "kid": "2026-11a",
    "payload": { "actionHash": "…64hex…", "decisionId": "7c1e…", "expiresAt": "2026-11-08T01:05:00Z",
                 "issuedAt": "2026-11-08T01:00:00Z", "kid": "2026-11a", "outcome": "ALLOW",
                 "policyVersionId": 1730890123456, "tenantId": "t1", "v": 1 },
    "signature": "<base64url 86 字符>"
  },
  "outcome": "ALLOW", "policyVersion": { … }, "receipt": { "auditId": 42, "hash": "…", "prevHash": "…" }, "ruleId": "…"
}
```

**幂等重放与 `actionHash`（M5，按 ADR 不改指纹）：** 指纹仍是 ADR 0040 §3.1 的七项，`actionHash` 不在其中。因此同一 `Idempotency-Key` 下，若第二次请求只在指纹之外的字段（`principal.type`、`principal.roles`、`agent.*`、显式 null vs 缺键的非 context 字段、阶段 2 的 `facts`）与首次不同，返回的是**首次决策及其收据**，收据 `actionHash` 是首次请求的哈希；执行方若对第二次请求体自算哈希，consume 会 409 `action_mismatch`（fail-closed）。这一点写进 REST_API.md Guard 节与 ADR 0040 §12 D9 的一句补充（Task 1 Step 2b 同一提交）。`context` 内的 null vs 缺键会改变 `contextHash`，所以属于指纹差异 → 422。

- [ ] **Step 0: 录现状 golden（先于任何改动）**：`GuardInvalidRequestRegressionTest` 对当前代码发送：非法 JSON、`action` 缺失、`principal.id` 为数字、`waitSeconds` 为字符串、空体、`policyModule` 缺失、超长幂等键、`context` 深度 9；把状态码 + 响应体（逐字节）写入 `invalid-request-responses.golden.json` 并提交。该测试此后断言逐字节相等。

- [ ] **Step 1: 写失败测试**

```java
@Test void duplicateKeyInBodyIs400InvalidAction()      // 顶层与 context 内重复键各一例
@Test void replaySameKeyNullVsMissingPrincipalTypeReturnsFirstReceipt() // M5：首次 {"principal":{"id":"a"}}，同键第二次 {"principal":{"id":"a","type":null}} → 200 同 decisionId、同 executionReceipt；其 actionHash 等于首次原文哈希、不等于第二次原文哈希
@Test void replaySameKeyNullVsMissingInContextIs422()  // context {cc:null} vs {} → 指纹不同 → 422
@Test void allowCarriesVerifiableExecutionReceipt()        // 用 /keys 之外的测试公钥复验；payload.actionHash == 对请求体 action 子树自算
@Test void denyAndPendingHaveNoExecutionReceipt()          // 且无 guard_receipts 行；action_hash 列仍写入
@Test void actionHashBindsRawJsonNotRecord()               // Review Focus 1：{"principal":{"id":"a"}} 与 {"principal":{"id":"a","type":null}} 两次决策（不同幂等键）actionHash 不等，且各等于对原文 action 自算
@Test void replayReturnsSameStoredReceipt()                // 同幂等键重放：executionReceipt 逐字段相等，guard_receipts 仍一行
@Test void approvedReceiptIssuedAtApprovalAndNeverResigned() // Review Focus 3：可变 Clock：决策 t0 → approve t0+10m → issuedAt=t0+10m、expiresAt=+5m；GET 两次相等；时钟推进到 +20m 后 GET 仍为同一收据
@Test void rejectedAndExpiredApprovalsIssueNothing()
@Test void fromEvidenceAllowIssuesReceipt()
@Test void legacyResponseJsonWithoutReceiptStillDeserializes() // 旧 response_json 往返
```

- [ ] **Step 2:** 跑上述测试类 → FAIL。
- [ ] **Step 3: 实现**（见 Files）。签发失败（签名异常）按 fail-closed：改判 DENY `receipt_unavailable`，与链收据失败同一路径（`GuardReasonCode.RECEIPT_UNAVAILABLE`）。幂等指纹**不**加 `actionHash`（ADR 0040 §3.1 指纹不变，阶段 2 的 facts 不应触发 422）。
- [ ] **Step 4:** 跑上述测试类（含 `GuardInvalidRequestRegressionTest` 仍逐字节相等）+ 完整 guard 套件 → PASS。
- [ ] **Step 5: 提交** `feat(guard): ALLOW 与审批通过时签发执行收据（ADR 0040 §12 D1–D3）`

### Task 6: `GET /api/v1/guard/keys`、契约 golden 与密钥运维手册

**Files:**
- Create: `src/main/java/io/aster/guard/api/GuardKeysResource.java`、`docs/runbooks/guard-receipt-signing-keys.md`
- Modify: `src/test/resources/api/public-api-contract.golden`、`src/test/java/io/aster/security/apikey/ApiKeyAuthFilterShouldProtectTest.java`、`REST_API.md`（Guard 节）
- Test: `src/test/java/io/aster/guard/api/GuardKeysResourceTest.java`

**Interfaces:**
- Consumes: `ReceiptSigningKeys.all()/revision()`（Task 3）。
- Produces（SDK 与 ADR 0054 消费）：

```http
GET /api/v1/guard/keys          Authorization: Bearer <key>    @RequireRole(MEMBER)
200  Cache-Control: max-age=300
{ "keys": [ { "alg": "EdDSA", "crv": "Ed25519", "exp": 1731100000, "kid": "2026-11a", "kty": "OKP", "use": "sig", "x": "<base64url 32 字节>" } ],
  "revision": 7 }
```

列出全部 `now < notAfter`（无 `notAfter` 视为不过期）且未 `revoked` 的 key；`exp` 仅在有 `notAfter` 时写（epoch 秒）；键字母序。

- [ ] **Step 1: 写失败测试**：`listsAllUnexpiredUnrevokedKidsWithRevision`、`omitsRevokedAndPastNotAfter`、`notAfterBoundaryIsExclusive`（`now == notAfter` 已不发布）、`retiredKidRemovedFromConfigStillConsumable`（M8：旧 kid 删除后其签发的未过期收据 consume 仍 200，`/keys` 不再列出）、`cacheControlMaxAge300`、`requiresBearer`（无头 401）、`jwkXDecodesToPublicKeyThatVerifiesIssuedReceipt`。
- [ ] **Step 2:** 跑 `GuardKeysResourceTest` 与 `PublicApiContractTest` → FAIL。
- [ ] **Step 3: 实现** 资源；golden 加 `GET /api/v1/guard/keys`；runbook 写：用 `openssl genpkey -algorithm ed25519` 生成后取 32 字节种子 base64url 的命令、Vault 路径 `apps/aster-api-guard-receipt` 属性 `signing-keys` / `active-kid`、计划轮换（加新 key → `revision+1` → 切 `active-kid` → `revision+1` → ≥24h 后删旧 → `revision+1`）、紧急撤销（`"revoked": true` + `revision+1` → `kubectl rollout restart` → `rollout status` 完成才算撤销完成；离线验签最多滞后 300 s）。
- [ ] **Step 4:** 同 Step 2 → PASS。
- [ ] **Step 5: 提交** `feat(guard): 发布执行收据 JWKS 与 revision（ADR 0040 §12 D6）`

### Task 7: consume / result 事件类型与构造器

**Files:**
- Modify: `src/main/java/io/aster/policy/event/EventType.java:23-24`（三项）、`src/main/java/io/aster/guard/GuardReceiptBuilder.java`（三个方法）、`src/main/java/io/aster/audit/chain/AuditChainAppender.java:52-54`（六个身份键）
- Test: `src/test/java/io/aster/guard/GuardReceiptBuilderTest.java`、`src/test/java/io/aster/policy/common/AuditIdentifierRedactionTest.java`

**Interfaces:**
- Produces:
  - `AuditEvent consumeAttemptEvent(GuardReceiptEntity r, String actionHash, String attemptId, String executorId, String decisionReceiptHash, String performedBy)`——metadata `kind:"attempt", decisionId, actionHash, attemptId, executorId, kid, receiptIssuedAt, receiptExpiresAt, decisionReceiptHash`，`success=true`。
  - `AuditEvent consumeOutcomeEvent(UUID decisionId, String attemptId, String executorId, String outcome, String attemptReceiptHash, String performedBy)`——`outcome ∈ consumed | rejected:<code>`，`success = "consumed".equals(outcome)`。
  - `AuditEvent resultEvent(UUID decisionId, ResultRequest req, String consumeReceiptHash, String performedBy)`——`success = "executed".equals(status)`；可缩短键只有 `errorCode`。
  - 三者都投影 `decision_id` 列，不写 `correlation_id`。

- [ ] **Step 1: 写失败测试**：每个方法的 metadata 键集合与 `success`；超 3800 字符预算时只删 `errorCode`；`AuditIdentifierRedactionTest` 断言 `executorId: "4111-1111-1111-1111@host"`、`actionHash` 64 hex 原样入链（不被卡号模式改写）。
- [ ] **Step 2:** 跑两测试类 → FAIL。 **Step 3:** 实现。 **Step 4:** → PASS。
- [ ] **Step 5: 提交** `feat(guard): consume 尝试 / 结果与执行结果事件（ADR 0041 §12 D1）`

### Task 8: `POST /api/v1/guard/receipts/{decisionId}/consume`

**Files:**
- Create: `src/main/java/io/aster/guard/receipt/GuardReceiptStore.java`、`GuardReceiptJpaStore.java`、`GuardReceiptService.java`、`src/main/java/io/aster/guard/api/GuardReceiptResource.java`、`api/ConsumeRequest.java`、`api/ConsumeResponse.java`
- Modify: `InternalCallerFilter.java`（`isGuardDualChannel` 加 `/api/v1/guard/receipts/[^/]+/(?:consume|result)`）、`ApiKeyAuthFilter.java:47-57`（`HMAC_BYPASS_PREFIXES` 加 `/api/v1/guard/receipts/`）、`public-api-contract.golden`、`NonDeterminismSourceTest`（如需）
- Test: `src/test/java/io/aster/guard/api/GuardReceiptResourceTest.java`、`src/test/java/io/aster/guard/receipt/GuardReceiptServiceTest.java`、`GuardReceiptJpaStoreTest.java`

**Interfaces:**
- Consumes: Task 4 实体、Task 7 事件构造器、`ReceiptSigningKeys.isRevoked/revision`（Task 3）、`RequestIdentityResolver`（租户 / performedBy）。
- Produces:
  - `GuardReceiptStore.consume(String tenantId, UUID decisionId, ConsumeRequest req, long attemptAuditId, Instant now, Predicate<String> kidRevoked): ConsumeVerdict`——`sealed interface ConsumeVerdict { record Consumed(Instant consumedAt, GuardReceiptEntity row); record Rejected(String code, Map<String,Object> extra); }`，在 `requiringNew` 事务内 `PESSIMISTIC_WRITE` 锁行后按 ADR 0040 §12 D5 顺序判定：①行不存在 → `not_allowed`（若决策存在且 `effectiveOutcome==ALLOW` 但无行，`extra` 带 `legacy:true`）；①′ `kid_revoked`（`extra.revision`）；② `receipt_consumed`（`extra` 原消耗者 `consumedAt/executorId/attemptId`）；③ `receipt_expired`（`extra.expiresAt`）；④ `action_mismatch`（不回显哈希）。
  - `GuardReceiptService.consume(tenantId, performedBy, decisionId, req): ConsumeVerdict`——入参校验 → `appendSync(consumeAttemptEvent)`（失败 → 抛 `ReceiptUnavailableException` → 503 `receipt_unavailable`）→ `store.consume` → `appendSync(consumeOutcomeEvent)`（失败只记日志）。

HTTP 契约：

```http
POST /api/v1/guard/receipts/{decisionId}/consume     Bearer 或 HMAC   @RequireRole(MEMBER)
{ "actionHash": "<64 hex 小写>", "attemptId": "<UUID>", "executorId": "<≤255 无控制字符>" }

200 { "consumedAt": "…", "decisionId": "…", "executionReceipt": { …同决策响应… } }
400 { "error": "invalid_consume", "field": "actionHash|attemptId|executorId" }
404 （空体：非法 UUID / 跨租户 / 决策不存在）
409 { "error": "not_allowed", "effectiveOutcome": "DENY|PENDING|ALLOW", "legacy": true? }
409 { "error": "kid_revoked", "kid": "…", "revision": 8 }
409 { "error": "receipt_consumed", "consumedAt": "…", "executorId": "…", "attemptId": "…" }
409 { "error": "receipt_expired", "expiresAt": "…" }
409 { "error": "action_mismatch" }
503 { "error": "receipt_unavailable" }
```

配额：与 `/decisions` 同一计量（`GuardDecisionResource` 现有计量辅助方法），不限速。

- [ ] **Step 1: 写失败测试**（`GuardReceiptResourceTest`，`@QuarkusTest`，先经 `/decisions` 得 ALLOW）

```java
@Test void firstConsume200WritesRowAndTwoEvents()      // consume_audit_id 指向 attempt 事件；outcome 事件 outcome=consumed
@Test void changedParameterIs409ActionMismatch()       // 改 context.amount 自算 → 409；行未消耗；outcome=rejected:action_mismatch；consume_audit_id 空
@Test void secondConsumeSameOrNewAttemptIs409Consumed() // 响应体回显首次 executorId/attemptId
@Test void expiredIs409()                              // 可变 Clock：now == expiresAt → 409（排他）；expiresAt − 1ms → 200（另一决策）
@Test void denyPendingRejectedAre409NotAllowed()
@Test void legacyAllowWithoutRowIs409NotAllowedLegacy() // 直接插一条无收据行的 ALLOW 决策
@Test void revokedKidIs409WithRevision()               // 测试 profile 切换：签发后把 kid 标 revoked 重建 ReceiptSigningKeys
@Test void orderIsConsumedBeforeExpiredBeforeMismatch() // 已消耗 + 过期 + 错哈希 → receipt_consumed；未消耗 + 过期 + 错哈希 → receipt_expired
@Test void crossTenantAndBadUuidAre404()
@Test void invalidBodyIs400()                          // 63 hex、非 UUID attemptId、executorId 含 \n、256 字符
@Test void attemptAppendFailureIs503AndRowUntouched()  // GuardReceiptServiceTest，mock appender 抛 AuditAppendException
@Test void hmacInternalCallerCanConsume()              // 双通道
```

`GuardReceiptJpaStoreTest`：

```java
@Test void concurrentConsumeExactlyOneWins() // Review Focus 2：16 线程 CountDownLatch 同时 consume 同一 decisionId（各自不同 attemptId）→ 恰 1 个 Consumed，15 个 Rejected("receipt_consumed")；链上 16 条 attempt 事件，consume_audit_id 指向赢家
@Test void crashAfterCommitBeforeOutcomeLeavesOnlyAttempt() // outcome 追加抛异常：响应仍 200，链上无 outcome 事件
```

- [ ] **Step 2:** 跑三测试类 → FAIL。
- [ ] **Step 3: 实现**（见 Interfaces）。资源方法在 worker 线程（`@Blocking`），与 `GuardDecisionResource` 相同；服务方法不得在外层事务中调用。
- [ ] **Step 4:** 跑三测试类 + `PublicApiContractTest` + `ApiKeyAuthFilterShouldProtectTest` + `InternalCallerFilter*Test` → PASS。
- [ ] **Step 5: 提交** `feat(guard): 执行收据原子一次性 consume（ADR 0040 §12 D5）`

### Task 9: `POST /api/v1/guard/receipts/{decisionId}/result`

**Files:**
- Modify: `GuardReceiptStore.java` / `GuardReceiptJpaStore.java` / `GuardReceiptService.java` / `GuardReceiptResource.java`；Create `api/ResultRequest.java`
- Test: 同 Task 8 的三个测试类追加

**Interfaces:**
- Produces: `GuardReceiptService.recordResult(tenantId, performedBy, decisionId, ResultRequest req): ResultVerdict`；`record ResultRequest(String attemptId, String executorId, String status, String finishedAt, String resultHash, String errorCode)`。判定：先在短事务读行做预判（不存在 / 未消耗 → `not_consumed`；attempt/executor 不符 → `attempt_mismatch`；已有结果 → `result_recorded`），通过则 `appendSync(resultEvent)` → 加锁复核同三条后写 `result_status/result_at/result_audit_id`（复核失败 → 同码 409，事件已入链但行不引用，按「未生效」读）。

```http
POST /api/v1/guard/receipts/{decisionId}/result
{ "attemptId": "…", "executorId": "…", "status": "executed|failed|aborted", "finishedAt"?: "ISO-8601", "resultHash"?: "<64 hex>", "errorCode"?: "<≤64>" }
200 { "decisionId": "…", "resultAt": "…", "status": "executed" }
400 { "error": "invalid_result", "field": "…" }
409 { "error": "not_consumed" | "attempt_mismatch" | "result_recorded" }
```

- [ ] **Step 1: 写失败测试**：`resultBeforeConsumeIs409NotConsumed`、`resultByOtherAttemptIs409`、`secondResultIs409Recorded`、`abortedAfterLostConsumeResponseIsAccepted`（同 attemptId 报 `aborted`）、`resultEventAuditIdGreaterThanAuthoritativeAttemptAndHashLinked`（`consumeReceiptHash` = 权威尝试事件 `currentHash`）。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS（含 `PublicApiContractTest`）。
- [ ] **Step 5: 提交** `feat(guard): 执行结果回写（ADR 0040 §12 D7）`

### Task 10: 收据查询接口 `consumes[] / results[]`

**Files:**
- Modify: `src/main/java/io/aster/audit/rest/AuditReceiptResource.java`（`decisionIds` 路径）及其查询服务
- Test: `src/test/java/io/aster/audit/rest/AuditReceiptResourceTest.java`

**Interfaces:**
- Produces（#465 对账与 cloud 证据包消费）：`decisionIds` 路径响应增

```json
"consumes": [ { "auditId": 101, "currentHash": "…", "decisionId": "…", "authoritative": true,
                "outcome": "consumed|rejected:<code>|crashed-unknown|unknown", "attemptId": "…", "executorId": "…", "actionHash": "…", "timestamp": "…" } ],
"results":  [ { "auditId": 120, "currentHash": "…", "decisionId": "…", "status": "executed", "attemptId": "…", "executorId": "…", "resultHash": "…", "errorCode": null, "finishedAt": "…", "consumeReceiptHash": "…", "timestamp": "…" } ]
```

`receipts[]` 中对应决策收据项增 `authoritativeConsumeAuditId`（来自 `guard_receipts.consume_audit_id`）。空集合不输出；`correlationIds` 路径不变。

- [ ] **Step 1: 写失败测试**：`consumesMarkAuthoritativeFromRowPointer`（并发产生两条尝试，只有一条 `authoritative`）、`missingOutcomeOnAuthoritativeIsCrashedUnknown`、`missingOutcomeOnLoserIsUnknown`、`consumeAuditIdLessThanResultAuditId`、`verifyChainValidOverNewEvents`（`/api/v1/audit/verify-chain` 区间含三类新事件 → valid）。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(audit): 收据查询返回 consume / result 事件与权威指针（ADR 0041 §12 D2）`

**阶段 1 交付：** 开 PR `feat(guard): 执行收据与一次性消耗（#463）`，PR 描述含「部署与 k3s」一节全文与「部署前用户操作」。

---

## 阶段 2：输入来源与已验证事实（#461，ADR 0040 §11 R60 + ADR 0041 §11 R61 + ADR 0046 §11 R62）

### Task 11: 迁移 `V6.36.0`、签发方表与 HMAC 管理端点

**Files:**
- Create: `src/main/resources/db/migration/V6.36.0__guard_input_provenance.sql`、`src/main/java/io/aster/guard/provenance/GuardFactIssuerEntity.java`、`FactIssuerStore.java`、`src/main/java/io/aster/guard/api/GuardFactIssuerResource.java`
- Modify: `migration-checksums.golden`、`GuardDecisionEntity.java`（`verifiedFields`、`factIssuers` JSONB，`factExpiresAt`）、`GuardApprovalEntity.java`（`expiredReason`）、`InternalCallerFilter.java`（`/api/v1/guard/fact-issuers` 及子路径 → `REQUIRE_HMAC`，同 `GUARD_FROM_EVIDENCE_PATH`）、`ApiKeyAuthFilter.java`（HMAC 旁路）、`public-api-contract.golden`
- Test: `src/test/java/io/aster/guard/api/GuardFactIssuerResourceTest.java`、`GuardEntitiesPersistenceTest.java`

**Interfaces:**
- Produces: 表 `guard_fact_issuers(tenant_id, issuer, kid, alg, public_key, enabled, not_after, created_at, created_by, UNIQUE(tenant_id, issuer, kid))`；`guard_decisions.verified_fields JSONB, fact_issuers JSONB, fact_expires_at TIMESTAMPTZ`；`guard_approvals.expired_reason VARCHAR(32)`。`FactIssuerStore.find(tenantId, issuer, kid): Optional<GuardFactIssuerEntity>`（主键直查，不缓存）。

```http
GET    /api/v1/guard/fact-issuers                         → 200 { "issuers": [ { "alg": "Ed25519", "createdAt", "createdBy", "enabled", "issuer", "kid", "notAfter"?, "publicKey" } ] }
PUT    /api/v1/guard/fact-issuers/{issuer}/{kid}          { "publicKey": "<base64url 32 字节>", "enabled": true, "notAfter"?: "…" } → 200 同项
DELETE /api/v1/guard/fact-issuers/{issuer}/{kid}          → 204（软删：enabled=false，触发 Task 14 吊销）
Bearer 一律 403 { "error": "internal_only" }；publicKey 解码非 32 字节 → 400 { "error": "invalid_issuer" }
```

- [ ] **Step 1: 写失败测试**：`bearerIs403InternalOnly`、`hmacPutThenGetRoundTrips`、`badPublicKeyIs400`、`deleteIsSoftDisable`、`crossTenantInvisible`；持久化测试覆盖新列往返。
- [ ] **Step 2:** FAIL（含 `MigrationGovernanceTest`）。 **Step 3:** 实现（`DELETE` 先只置 `enabled=false`，吊销联动在 Task 14 接入）。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(guard): V6.36.0 签发方表与内部管理端点（ADR 0040 §11 D3）`

### Task 12: 签名事实验签

**Files:**
- Create: `src/main/java/io/aster/guard/provenance/FactVerifier.java`、`GuardInvalidFactException.java`
- Modify: `api/GuardActionRequest.java`（`Action` 增 `List<Fact> facts`；`record Fact(String issuer, String kid, String alg, String audience, String issuedAt, String expiresAt, Map<String,Object> claims, Map<String,String> sources, String signature)`）、`GuardDecisionResource.java`（`GuardInvalidFactException` → 400 `{ "error": "invalid_fact", "issuer", "kid", "reason" }`）
- Test: `src/test/java/io/aster/guard/provenance/FactVerifierTest.java`

**Interfaces:**
- Consumes: `FactIssuerStore.find`（Task 11）、`JcsCanonicalizer.canonicalize`（Task 2）、`Clock`。
- Produces: `FactVerifier.verify(String tenantId, JsonNode rawAction): VerifiedFacts`；`record VerifiedFacts(SortedSet<String> paths, SortedMap<String, SortedSet<String>> issuersByPath, SortedSet<String> issuerKids /* issuer#kid */, SortedMap<String,String> sourcesByPath, Instant earliestExpiresAt)`；无 `facts` 时返回 `VerifiedFacts.EMPTY`。

规则逐字取 ADR 0040 §11 D3（配方改为 JCS，见 Global Constraints）：≤ 8 条、`claims` 总数 ≤ 64、路径 ≤ 128；签名载荷 `{ alg, audience, claims, expiresAt, issuedAt, issuer, kid, sources }` 规范化字节；`alg` 仅 `Ed25519`；签发方存在且 `enabled` 且未过 `not_after`；`audience == tenantId`；`issuedAt − 60s ≤ now < expiresAt`（`expiresAt` 排他，与收据一致）、`expiresAt − issuedAt ≤ 24h`；每个 claim 路径在 `rawAction` 中存在且两边**规范化字节相等**；`sources` 键集 == `claims` 键集、值 ≤ 64 字符。任一失败整个请求 400，不降级。

- [ ] **Step 1: 写失败测试**：`validFactVerifiesPaths`；以下各 → `invalid_fact` 且 `reason` 区分：`tamperedClaim`、`tamperedSources`、`expired`、`notYetValidBeyondSkew`、`windowOver24h`、`audienceMismatch`、`unknownKid`、`disabledIssuer`、`pastNotAfter`、`nonEd25519Alg`、`claimPathAbsentInAction`、`sourcesKeySetMismatch`、`moreThan8Facts`、`moreThan64Claims`；`claimEqualityIsJcsBytes`（Review Focus 5：claims `250000` vs action `250000.0` / `2.5E5` → 通过；vs 字符串 `"250000"` → 400；claims 中 `9007199254740992` → 400 `invalid_fact` reason `unsafe_integer`）、`expiresAtBoundaryIsExclusive`（`now == expiresAt` → 400；`expiresAt − 1ms` → 通过；`issuedAt − 60s` 通过、`− 61s` 拒）、`signedPayloadExactJcsBytes`（测试签发器夹具：断言载荷 JCS 文本逐字节等于写死的期望字符串，再签名）；`twoIssuersSamePathRecordsBoth`。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现；`GuardDecisionService.decideOnce` 中在 `flatten` 之后、`findByIdempotencyKey` 之前调用（ADR 0040 §11 D4 顺序）。 **Step 4:** PASS + `GuardDecisionResourceTest` 新增 `invalidFactIs400AndWritesNoReceipt`、`invalidFactRejectedBeforeIdempotencyReplay`。
- [ ] **Step 5: 提交** `feat(guard): 签名事实片段验签（ADR 0040 §11 D3）`

### Task 13: 来源判定、响应 / 决策行 / 收据键与重放 `factsStale`

**Files:**
- Create: `src/main/java/io/aster/guard/provenance/InputSourceResolver.java`、`InputSources.java`
- Modify: `GuardDecisionService.java`、`GuardEvidenceDecisionService.java`、`GuardDecisionDraft.java`、`GuardDecisionAssembler.java`、`GuardReceiptBuilder.java:77-99`（新键与缩短顺序）、`api/GuardDecisionResponse.java`（`InputSourcesView inputSources`）、`src/main/java/io/aster/policy/api/PolicyEvaluationService.java`（`POLICY_EVALUATION` metadata 增 `inputSource: "declared"`）
- Test: `src/test/java/io/aster/guard/provenance/InputSourceResolverTest.java`、`GuardReceiptBuilderTest.java`、`GuardDecisionResourceTest.java`、`src/test/java/io/aster/policy/api/PolicyEvaluationServiceTest.java`（或现有 evaluate 事件测试）

**Interfaces:**
- Consumes: `VerifiedFacts`（Task 12）、`RequestIdentityResolver.performedBy()/businessRoles()`。
- Produces: `InputSourceResolver.resolve(GuardActionRequest.Action a, VerifiedFacts facts, String performedBy, Set<String> businessRoles): InputSources`；`record InputSources(SortedSet<String> verified, SortedSet<String> factIssuers, SortedMap<String,String> factSources, Boolean principalIdentityMatched /* 仅 principal.type=="user" 时非 null */, Instant factExpiresAt)`；`InputSources.isVerified(String path)`。响应：

```json
"inputSources": { "factIssuers": ["idp.example.com#2026-11"], "factsStale": true?, "verified": ["context.amount", "principal.id"] }
```

`verified` 为空时整个对象不写；`factsStale` 只在重放 / GET 且 `now > fact_expires_at` 或任一记录的 `issuer#kid` 现已禁用 / 过 `not_after` 时追加，不改写 `response_json`、不写事件。收据键 `verifiedFields / factIssuers / factSources / principalIdentityMatched / factExpiresAt / inputSourcesTruncated`，缩短顺序按 ADR 0041 §11 D1（`factSources` 紧随 `verifiedFields`）。

- [ ] **Step 1: 写失败测试**：`userPrincipalEqualToPerformedByIsVerified`、`otherIdOrAgentTypeIsDeclared`、`rolesVerifiedOnlyIfSubsetOfBusinessRoles`（一项不在 → 整叶 declared）、`factsAndServerResolutionUnion`、`principalIdentityMatchedRecordedEvenWhenFactCovers`、`noFactsResponseByteIdenticalToToday`（与既有 golden 响应逐字节相等）、`receiptTruncationOrderAndFlag`（64 条 128 字符路径）、`replayAfterFactExpiryMarksStaleWithoutNewEvent`、`sameKeyDifferentFactsIsNot422`、`evaluateEventsCarryInputSourceDeclared`。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(guard): 输入逐字段来源进响应、决策行与收据（ADR 0040 §11 D1/D2/D4，ADR 0041 §11）`

### Task 14: 签发方吊销联动

**Files:**
- Create: `src/main/java/io/aster/guard/provenance/FactIssuerRevocationService.java`
- Modify: `EventType.java`（`GUARD_FACT_ISSUER_REVOKED`）、`GuardReceiptBuilder.java`（`issuerRevokedEvent`；审批事件 metadata `expiredReason`）、`GuardApprovalService.java`（复用过期迁移路径，带 `expiredReason`）、`GuardApprovalExpiryScheduler.java`（同轮扫描 `not_after` 到期签发方）、`GuardFactIssuerResource.java`（PUT `enabled=false` / DELETE 同步调用）、`GuardApprovalResource.java`（409 `approval_not_pending` 体附 `expiredReason`）
- Test: `src/test/java/io/aster/guard/provenance/FactIssuerRevocationIT.java`（`@QuarkusTest`）

**Interfaces:**
- Produces: `FactIssuerRevocationService.revoke(String tenantId, String issuer, String kid, String performedBy): int`（返回被过期的审批数）；按 `guard_decisions.fact_issuers @> '["issuer#kid"]'` 找本租户 PENDING 审批，逐条走「追加审批事件 → tx2 加锁迁移」为 EXPIRED，`expired_reason='fact_issuer_revoked'`；再追加一条 `GUARD_FACT_ISSUER_REVOKED`（`affectedDecisionIds` ≤ 64，超出写 `affectedCount` + `affectedTruncated:true`）。

- [ ] **Step 1: 写失败测试**（ADR 0040 §11.3 第 6 条整条）：建签发方 → 附事实决策 PENDING → 禁用 → 审批 EXPIRED + `expiredReason` → 链上吊销事件含该 decisionId → approve 409 `approval_not_pending` 附 `expiredReason` → 新键不附事实重试为 declared → 附新签发方事实 → PENDING；`notAfterExpiryHandledByScheduler`（`performedBy=SYSTEM`）；`affectedOver64Truncated`。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(guard): 签发方吊销即过期依赖其事实的待审批（ADR 0040 §11 D4）`

### Task 15: 档案 `verifiedInputs` 执行点（引擎无关接缝）

**Files:**
- Create: `src/main/java/io/aster/guard/provenance/VerifiedInputsGate.java`
- Modify: `replay/src/main/java/io/aster/policy/governance/RuleMetadata.java`（增 `List<String> inputRefs`（null = IR 未提供）、`boolean verifiedInputsRequired`；保留现有两个构造器，`NONE` 不变）、`PolicyEvaluationService.java`（新方法 `RuleMetadata ruleMetadata(String tenantId, String module, String function)`：从评估所用的同一编译缓存取模块，**不执行**规则）、`GuardReasonCode.java`（`UNVERIFIED_INPUT("unverified_input")`、`INPUT_REFS_UNAVAILABLE("input_refs_unavailable")`）、`GuardDecisionService.java` / `GuardEvidenceDecisionService.java`（`resolveVersion` 之后、`evaluate` 之前调用门）、`GuardReceiptBuilder.java`（`unverifiedFields`）
- Test: `src/test/java/io/aster/guard/provenance/VerifiedInputsGateTest.java`、`GuardDecisionServiceTest.java`

**Interfaces:**
- Consumes: `InputSources`（Task 13）、`RuleMetadata`。
- Produces: `VerifiedInputsGate.check(RuleMetadata meta, InputSources sources, Set<String> presentLeafPaths): Optional<GateDenial>`；`record GateDenial(GuardReasonCode code, List<String> unverifiedFields)`。规则：`!meta.verifiedInputsRequired()` → empty；`inputRefs == null` → `INPUT_REFS_UNAVAILABLE`；`inputRefs` 含 `"*"` → 若 `presentLeafPaths` 中存在任一 declared 叶子 → `UNVERIFIED_INPUT`，`unverifiedFields=["*"]`；否则 `inputRefs` 中 declared 的路径（字典序）非空 → `UNVERIFIED_INPUT`。被拒时：DENY（200）、`reason` null、写链收据与决策行、**不**建审批、**不**签执行收据。
- 本任务中 `RuleMetadata.of(CoreModel.Module, String)` 的两个新字段恒为 `null / false`（引擎尚无字段）；接线在 Task 16。

- [ ] **Step 1: 写失败测试**：`notRequiredIsNoop`、`nullInputRefsDeniesInputRefsUnavailable`、`declaredReferencedFieldDenies`、`allReferencedVerifiedPasses`、`starWithAnyDeclaredLeafDeniesWithStar`、`gateDenyCreatesNoApprovalAndNoExecutionReceipt`、`gateRunsBeforeEvaluation`（mock `PolicyEvaluationService.evaluatePolicy` 未被调用）、`fromEvidenceAlsoGated`。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS + `--tests 'io.aster.guard.*'` 全绿（现有模块无档案要求 → 行为不变）。
- [ ] **Step 5: 提交** `feat(guard): 档案要求 verifiedInputs 时评估前 fail-closed（ADR 0040 §11 D5，ADR 0046 §11 D2）`

**阶段 2 aster-api 交付：** 开 PR `feat(guard): 输入来源与签名事实（#461）`（Task 11–15）。可在引擎发版前合并部署：无档案要求 verifiedInputs，不拒绝任何现有请求。

### Task 16: 接线引擎 `inputRefs` / `verifiedInputs` 与 E708 诊断（引擎发版后）

**前置（硬门）：** aster-api 的引擎 pin（`gradle.properties` 中 aster-lang-core 版本）**≥ 跨仓 Task X2 的发布版本**，且 locales pin ≥ registry 1.2.0。未满足时本任务不开工；Step 1 先加 `EnginePinGateTest`，断言 `CoreModel.Func` 存在 `inputRefs` 字段、`ControlRegistry.Profile` 存在 `verifiedInputs`（反射），在旧 pin 上该测试即失败，作为门的可执行形式。registry 1.3.0 翻转（`eu-ai-act-high-risk.verifiedInputs=true`）**不在本计划**。

**Files:**
- Modify: `RuleMetadata.of`（读 `CoreModel.Func.inputRefs`、按 `module.profile` 查 `ControlRegistry` 档案的 `verifiedInputs`）、`src/main/java/io/aster/policy/compiler/GovernanceDiagnostics.java:66-79`（`isProfileCode` 含 `E708` → error 级）、`src/main/java/io/aster/policy/rest/model/CompileResponse.java`（每个 Verdict 规则带 `inputRefs`，供 cloud 编辑器展示）
- Test: `RuleMetadataTest`（replay）、`GovernanceDiagnosticsTest`、`PolicyCompileResourceTest`

- [ ] **Step 1: 写失败测试**：`ruleMetadataReadsInputRefsFromIr`、`noProfileModuleHasNullInputRefs`、`e708IsErrorSeverityWithSuccessTrue`（高风险档案 + `meta[k]` 动态键）、`compileResponseCarriesInputRefs`。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(guard): 读取引擎 inputRefs 并将 E708 定为 error（ADR 0046 §11 D2/D4）`

---

## 跨仓任务（阶段 2 编译期部分；按 ADR 0046 §11.4 顺序）

已核对：`aster-lang-core` / `aster-lang-ts` / `aster-lang-locales` 中**不存在** `E708`、`inputRefs`、`verifiedInputs`（core 现有 E701–E703、E705–E707；E720–E724 为 Cedar）。因此需要以下任务，各自开 PR、互相引用，并在 aster-api 阶段 2 PR 中列出发版顺序。

### Task X1: 「忽略未知 registry 字段」守护测试（core / ts / cloud）

- aster-lang-core：`ControlRegistryTest.unknownRequiresKeyIsIgnored`（`profiles[].requires` 含 `verifiedInputs` 与 `futureKey` → 加载成功）。
- aster-lang-ts：`test/unit/governance/controls.test.ts` 同名用例。
- aster-cloud：注册表读取处同名用例。
- 随各自下一次常规发版；**先于** X2。

### Task X2: registry 1.2.0 + `inputRefs` + E708（locales → core 与 ts 同车次 → test 夹具）

- aster-lang-locales：`controls/registry.json` `version` → `1.2.0`，全部档案 `requires.verifiedInputs: false`；`validateControlsRegistry` 校验其为布尔；负向夹具 `bad-profile-verified-inputs-not-bool.json`。
- aster-lang-core：`ControlRegistry.Profile.verifiedInputs`；`CoreModel.Func.inputRefs`（可选，未声明档案不写）；`GovernanceChecker` 在 lowering 后 Core IR 上按 ADR 0046 §11 D2 第 1 条传播规则表计算；`ErrorCode.E708 GOV_PROFILE_INPUT_UNRESOLVABLE`（参数 `rule / profile / construct`）与 `error_codes.json` 英文模板。
- aster-lang-ts：`governance.ts`、`Core.Func.inputRefs`、`error_codes.ts` 生成物；`ErrorCodeParityTest` 两侧锁定。
- aster-lang-test：ADR 0046 §11.2 正反例表**逐行**一个夹具（含 8 层 / 9 层内联、互递归），两引擎 `inputRefs` 逐字相同；未声明档案模块 IR 与现有 golden 逐字节相同。
- 发版：locales 1.2.0 → core + ts 同车次（发版列车自动部署 aster-api）→ aster-api Task 16。

### Task X3: E708 译文

- aster-lang-locales zh / de overlay；aster-lang-hi 同法。补发前编辑器显示英文模板（ADR 0051 §16.1 R56 回退），不阻塞 X2 / Task 16。

### Task X4（不在本计划实施，仅登记）: cloud 签发能力与 registry 1.3.0 翻转

- aster-cloud 签发方设置页、`services/guard/fact-signer.ts` 来源函数清单、from-evidence 附 facts、信贷试点测试签发方（ADR 0046 §11 D5 第 2 项）；之后 locales 1.3.0 把 `eu-ai-act-high-risk.requires.verifiedInputs` 翻为 `true`，发布前由用户在生产库做只读扫描。这是唯一的行为变更点，另立计划。

---

## 收据接口契约（供后续阶段消费）

#464（aster-guard 执行点 / ADR 0054）、#465（对账）、#466（绕过套件）、#460（审批输入快照）**不在本计划**；它们只依赖下列已固定的接口，本计划交付后这些接口不再改形（改形须升 `v` 或 `/v2`）：

| 接口 | 形状 | 消费方 |
|---|---|---|
| `GuardDecisionResponse.executionReceipt` | Task 5 示例；仅 ALLOW；重放 / GET 返回同一份 | #464 `verifyReceipt`、cloud from-evidence 执行路径 |
| `jcs-sha256/v1`（RFC 8785）+ `jcs-sha256-v1-vectors.json` | Global Constraints；#464 SDK 必须逐字节复制向量文件并全部通过（含重复键须拒绝） | #464 `hashAction` |
| `GET /api/v1/guard/keys` | Task 6；`revision` 单调；`max-age=300` | #464 JWKS 缓存与强制刷新（验签失败 / `kid_revoked` / 更大 `revision`，每 60 s 至多一次） |
| `POST /receipts/{id}/consume` | Task 8 全部状态码 | #464 `consume`；#466 场景 3/4 |
| `POST /receipts/{id}/result` | Task 9 | #464 `reportResult`；#465 「执行侧丢失」= 已消耗且超过 `aster.guard.result-grace` 无结果 |
| `guard_receipts` 表 + `GET /api/v1/audit/receipts?decisionIds=` 的 `consumes[] / results[] / authoritativeConsumeAuditId` | Task 4 / Task 10；**有效消耗只认 `consume_audit_id`** | #465 对账、cloud 证据包 `execution` 段（ADR 0041 §12 D3） |
| `inputSources` 响应 + 收据键 + `unverified_input` reasonCode | Task 13 / 15 | #466 场景 7、#460 审批视图来源徽标 |

执行方顺序（写入 REST_API.md 的 Guard 节，Task 6 完成）：本地验签（`kid` → 签名 → `tenantId/decisionId/outcome` → `now ∈ [issuedAt−60s, expiresAt]` → `actionHash` 自算相等）→ consume 200 → 执行 → result；任一步失败不执行，重试须换新 `Idempotency-Key` 重新申请决策。

## 部署与 k3s（本计划不改 k3s；清单逐字写入阶段 1 PR 描述，由用户执行）

现有模式：`k3s/apps/aster-lang/cloud/deployment.yaml` 中 `aster-api` 容器以 `env[].valueFrom.secretKeyRef` 注入，Secret 由同目录 `external-secrets.yaml` 的 `ExternalSecret`（`secretStoreRef: vault-backend`）从 Vault 同步（先例：`aster-api-llm-credentials` ← `apps/aster-api-llm`）。env 在容器启动时快照，任何密钥变更都要 `kubectl rollout restart`。

**部署清单（顺序不可颠倒；任一步未通过即停）：**

1. **Vault**：按 `docs/runbooks/guard-receipt-signing-keys.md` 生成种子，写入 `apps/aster-api-guard-receipt`，属性 `signing-keys`（`{"revision":1,"keys":[{"kid":"2026-11a","privateKey":"…"}]}`）与 `active-kid`（`2026-11a`）。
2. **k3s**：新增 `ExternalSecret aster-api-guard-receipt-keys`（目标 Secret 同名，`data` 两项 → `remoteRef.key: apps/aster-api-guard-receipt`）；`aster-api` 容器加 `ASTER_GUARD_RECEIPT_SIGNING_KEYS`、`ASTER_GUARD_RECEIPT_ACTIVE_KID` 两个 `secretKeyRef`。合并后确认同步（用户执行）：
   `kubectl -n aster-lang get externalsecret aster-api-guard-receipt-keys -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}'` 输出 `True`，且 `kubectl -n aster-lang get secret aster-api-guard-receipt-keys -o jsonpath='{.data}' | jq 'keys'` 列出两个键（只看键名，不解码值）。
3. **镜像**：上一步通过后再发布含 Task 3 的 aster-api 镜像（发版列车 / image pin PR）。新 Pod 启动日志应含 `签名密钥已加载 revision=1 kids=[2026-11a]`；缺密钥时 Pod 启动失败（CrashLoopBackOff）而旧 ReplicaSet 保持服务——这是有意的 fail-fast。
4. **验收**：`curl -H "Authorization: Bearer <key>" https://<api>/api/v1/guard/keys` 返回 `revision: 1` 与一个 `kid`。

**回滚：** 镜像回退到上一版本即可（新表 / 新列 / 新事件类型对旧代码无害，ADR 0040 §12 D10）；Secret 与 ExternalSecret 可留存，旧代码不读。若在第 3 步因密钥问题失败，修正 Vault 后 `kubectl rollout restart deployment/aster-api`，不需要回滚 k3s。阶段 2 无新密钥（签发方公钥在库表中，由 cloud 经 HMAC 端点维护）。

## 自审记录

- 规格覆盖：R63 D1–D10 → Task 2–10；R64 D1–D2 → Task 7、10（D3 证据包属 cloud）；R60 D1–D6 → Task 11–15；R61 D1–D2 → Task 13（D3/D4 证据包属 cloud，#460 的 `approvals[]` 键不在本计划）；R62 → Task 15、16、X1–X4。
- #463 验收「伪造 / 篡改签名 401」由执行方本地拒绝承担（ADR 0040 §12 D6 末段），服务端无提交收据入口；本计划以 `ExecutionReceiptSignerTest.anyPayloadByteChangeFailsVerify` 覆盖签发侧，SDK 侧属 #464。
