# X4：cloud 签发能力与 registry 1.3.0 翻转实施计划（ADR 0046 §11.4 ⑥ + ⑦）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**性质：** 主计划 `docs/superpowers/plans/2026-11-08-guard-receipts-and-input-provenance.md`「Task X4（不在本计划实施，仅登记）」的实施计划，同时承接 1.0.44 列车计划 `2026-11-12-train-1044-task16-engine.md`（下称「列车计划」）的 §4 第 4 条、阶段 C 第 3 条（X4 输入清单）与阶段 D 的 PR 16。与两份计划冲突处以本文为准，冲突逐条列在「对上游文档的更正」。

**Goal:** ⑥ aster-cloud 升到 ts 1.5.0 / 注册表副本 1.2.0、编辑器展示 `inputRefs`、团队（及个人）签发方设置页、服务端 `fact-signer` 与来源函数清单、from-evidence 附签名事实、信贷试点测试签发方；⑦ 生产只读扫描后，经 1.0.45 引擎列车把 `eu-ai-act-high-risk.requires.verifiedInputs` 翻为 `true`（locales → core / ts 副本 → aster-api pin → cloud 副本）。

**Architecture:** cloud 侧全部是新增的服务端模块与 UI，经现有 `PolicyApiClient`（内部 HMAC v3）调用 aster-api 已上线的 `/api/v1/guard/fact-issuers` 与 `from-evidence`；签名用 WebCrypto Ed25519（同 `src/lib/ai-workspace-handoff.ts`），规范化用本仓新增的 RFC 8785 小模块并逐字节复用 aster-api 的共享向量。翻转本身只改注册表数据：四份副本（locales 真相源、core jar、ts 生成物、cloud）同值，走 ADR 0053 列车；aster-api 只升 pin 并更新两处「今天不要求」的测试。除 ⑦ 外，每个 PR 对现有租户都不改变行为。

**Tech Stack:** aster-cloud：Next.js（OpenNext on Cloudflare Workers，`nodejs_compat`）、drizzle、vitest、Playwright、WebCrypto Ed25519；aster-lang-platform：bash + Node 24 脚本；引擎：Java 25 / Gradle（core）、TypeScript（ts）、locales JSON；aster-api：Quarkus / JUnit 5。

**Spec（按此顺序读）：**
- aster-api `docs/adr/0046-governance-profile.md` §11.2 D5（第 2、3 项）、§11.4 ⑥⑦、§11.6 第 4、6、7、11、16、17、19 条
- `docs/adr/0040-action-guard.md` §11 D2–D5（事实格式、验签顺序、签发方端点与错误码、吊销联动、D5 实施澄清）
- `docs/adr/0041-evidence-chain-enrichment.md` §11 D1/D2/D4（`factSources`、事件 `inputRefs` 上限 512 字节、证据包 v6）
- `docs/adr/0044-annex-iii-credit-pilot.md` §2、§5（试点团队、四组 fixture、seed / run 脚本）
- 主计划「Task X4」与「收据接口契约」；列车计划 §1.1、§1.4、§2 阶段 C/D、§4

---

## 0. 现状核对（2026-11-13，各仓 `origin/main`）

| 事实 | 证据 |
|---|---|
| 线上：引擎 1.0.44（registry 1.2.0，两个档案 `verifiedInputs:false`）、ts npm 1.5.0、aster-api Task 16b（#492：事件自带已映射 `inputRefs` + `verifiedInputsRequired` 快照；from-evidence 按事件 `profile` + 当前注册表重算要求项；`RuleMetadata` 一律重算、不信 IR 自带值） | aster-api `6ebefadf`；ADR 0046 §11.6 第 19 条 |
| aster-api 已含前缀规则（#487）与 16b（#492），§11.6 第 17、19 条两个硬前置已满足 | `git log origin/main` |
| 签发方端点：`GET /api/v1/guard/fact-issuers`、`PUT|DELETE /api/v1/guard/fact-issuers/{issuer}/{kid}`，仅 HMAC；PUT 体 `{alg?, publicKey, enabled?, notAfter?}`；DELETE 成功 **204 无体**；issuer ≤ 255、kid ≤ 64，不含 `#` 与控制字符 | `GuardFactIssuerResource.java`、`FactIssuerRequest.java` |
| from-evidence 已验签 `action.facts`（在查既有决策之前） | `GuardEvidenceDecisionService.java:127` |
| `/evaluate-source` 的 HTTP 响应**不带** `inputRefs`（只写进评估事件） | `PolicyEvaluationResource.java:729-738`（`EvaluationResponse.success(...)` 无该参数） |
| 审批列表项已带 `expiredReason` | `GuardApprovalView.java:21` |
| 治理诊断 `blocking` 恒 false：E708 不阻止编译与求值，只让 cloud 保存门（读 aster-api `/compile` 的 error 级）拒绝保存 | `GovernanceDiagnostics.java` 类注释 |
| cloud **没有** `src/services/guard/` 目录；guard 调用在 `src/lib/guard-register.ts`（`toGuardAction` 把整个执行输入放进 `context`，`principal.id` 恒为发起人）与 `src/services/policy/policy-api.ts`（`guardFromEvidence` 等，HMAC v3 签名取纯 pathname） | 两文件 |
| 三个执行入口都经 `registerGuardDecisionWith` → `registerGuardDecision`：`/api/policies/[id]/execute`、`/api/v1/policies/[id]/execute`、`/api/policies/[id]/executions/[execId]/guard-register`；只在 `require_approval / escalate` 时调 from-evidence | `guard-register.ts:76-125`、三处路由 |
| `PolicyApiClient.request` 一律 `response.json()`：对 204 会抛错 | `policy-api.ts:426-500` |
| guard 路径段在 cloud 本地按 `^[A-Za-z0-9_-]{1,64}$` 校验（签名取编码后 path、api 用解码后 path） | `policy-api.ts:356-364` |
| 编辑器 Decision 页签的数据来自**浏览器内** ts `compileAndTypecheck` 的 Core IR（`summarizeCoreModule`），不是 aster-api `/compile`；ts 1.5.0 的浏览器编译已在 R2 之后写 `Func.inputRefs` | `core-module-summary.ts`、`side-panel.tsx:318-420`；ts `compile-pipeline.ts:87-94` |
| cloud 注册表副本 `src/config/controls-registry.json` 仍为 `1.1.0`；`RegistryProfile.requires` 无 `verifiedInputs`；sibling parity 测试在 CI 中跳过；`bundle.test.ts:345`、`regulatory-mapping.test.ts:108` 硬编码 `'1.1.0'` | `control-registry.ts:13-17`、`control-registry-parity.test.ts` |
| cloud#747（auto-upgrade，`deps/aster-lang-ts-v1.5.0`）已开、CI 全绿，只改 `package.json` / `lsp-package.json` / lockfile | `gh pr view 747` |
| 先例：团队设置卡片 `TeamLanguageCard` + `/api/teams/[teamId]/locales`（读 TEAM_VIEW、写专用权限）；按租户的 aster-api 代理路由 `/api/approvals/[tenantId]/…`（`createPolicyApiClientForUser`、上游错误映射）；个人设置子页 `settings/api-keys`、`settings/ai-keys`（`config/routes.yaml` 登记）；Ed25519 签名 `ai-workspace-handoff.ts`（pkcs8 base64 私钥，Worker secret） | 各文件 |
| cloud 生产 = Cloudflare Worker，`deploy-worker.yml` 由 main 发布；Worker secret 用 `wrangler secret put`（`docs/runbooks/secrets-rotation.md`） | 同 |
| 本地化文案走本仓 `src/i18n/demo-supplement.ts`（en / zh / de / hi），不动 ui-messages 包 | `side-panel-decision.test.tsx` 先例 |
| 生产扫描工具 `aster-lang-platform/scripts/gate-scan/`（导出 cloud `Policy` / `PolicyVersion` 源码，只打印 id 与诊断码）；aster-api 生产 `policy_versions` 为 0 行 | platform#116、README |
| 列车计划阶段 C 第 3 条「X4 输入清单」**未产出**（1.0.44 只做了诊断差异扫描） | 记录 |
| 引擎各仓自 `v1.0.44` 以来只有 runtime#77（重放门面加 `synchronized`）未发布；ts 自 `v1.5.0` 以来无新提交 | `git log v1.0.44..origin/main` |
| 翻转会直接打破的测试：core `ControlRegistryTest.默认注册表两个档案都不要求verifiedInputs`；ts `test/unit/governance/profile-check.test.ts:148-150`；aster-api `ProfileRequirementsTest.noProfileInBundledRegistryRequiresVerifiedInputs`、`PolicyEvaluationServiceRuleMetadataTest.highRiskProfileModuleIsNotGatedToday` | 各文件 |
| aster-lang-test `corpus/tier1-equivalence` 有 49 个声明 `eu-ai-act-high-risk` 的语料，其中若干 `expectedInputRefs` 为 `["*"]`（如 `adr-06-dynamic-map-key`）并带 eval 用例；翻转后两引擎都会对它们报 E708（error 级），harness 是否因此判失败**未知** | `corpus/tier1-equivalence/*.meta.json` |
| 信贷试点 v3（`pol-credit-pilot`，`Profile "eu-ai-act-high-risk"`）的引用为 `context.applicant.{activeDefaults,creditScore,monthlyDebt,monthlyIncome,requestedAmount}`（证据形状，执行输入键 `applicant`） | `src/config/credit-pilot-source.ts`；16b 生产冒烟 |

## 对上游文档的更正与冲突（均列入 §6 待确认）

1. **cloud 文件位置**：ADR 写 `services/guard/fact-signer.ts`；该目录今天不存在，本计划新建 `src/services/guard/`，现有 `src/lib/guard-register.ts` 不搬家。
2. **编辑器 `inputRefs` 的来源**：用户口径与 ADR D4 写「取自 `/compile` 的 `inputRefs`」。但 Decision 页签全部数据来自浏览器 ts 编译，为它额外调 `/compile` 会给每次按键加网络往返。本计划改为读浏览器 Core IR 的 `Func.inputRefs`（两引擎 parity 已由 aster-lang-test 锁定，aster-api 运行期另行重算）；`/compile` 的 `inputRefs` 不消费。
3. **`catalog.piiMark` 来源函数推迟**：它的 claim 路径 `context.<field>.$pii` 在 guard 请求里不存在，aster-api 会按 `claim_path_absent` 400 整个请求；它的唯一消费方是 #460（ADR 0042 §11），尚未实施。第一期清单为 `hmac.userId`、`idp.businessRoles`、`fixture.creditPilot` 三项。
4. **证据包 v6 的 `factSources` 展示不在 X4**：ADR 0041 §11 D4 规定 v6 与 ADR 0042 §11（#460 审批快照键）一次升版。X4 只保证 `factSources` 进链上收据（aster-api 已实现），v6 随 #460。
5. **试点签发方的密钥**：ADR 写「seed 脚本生成 Ed25519 密钥对」。本计划改为：密钥对由新脚本 `pnpm fact-key:gen` 生成并输出 env 行，seed 只把已配置私钥对应的公钥登记到 `credit-pilot` 租户。理由：seed 每次生成新密钥会让已运行的 cloud 进程持有的私钥与登记的公钥不一致。
6. **`fixture.creditPilot` 取值方式**：ADR 写「从 seed 固定 fixture 文件读值，不读请求体」。实现上必须先用请求体里的 `applicant` **选出**是哪一组 fixture（JCS 字节相等），claim 的值取自 fixture 常量 `PILOT_APPLICANTS`；请求体不与任何 fixture 相等时不签。
7. **1.3.0 翻转后生产里的信贷试点会得到 DENY**：`fixture.creditPilot` 只在 `NODE_ENV !== 'production'` 登记（ADR D5 第 2 项原文），生产没有任何来源函数能签 `context.*`。列车计划阶段 C 的上线冒烟「信贷试点 from-evidence 仍得 PENDING」在 1.0.45 之后改为期望 DENY `unverified_input`。
8. **core#245 不进 1.0.45**：列车计划 §3 把它推迟「到 1.0.45」。本计划让 1.0.45 只承载翻转与已合并的 runtime#77，#245 另排列车，避免把行为变更点与新语法的 parity 风险叠在一次发布里。

---

## Global Constraints

- **唯一行为变更点是 ⑦**（ADR 0046 §11.4）。⑥ 的每个 PR 对未登记 cloud 签发方的租户必须逐字节不改变 from-evidence 请求体；对已登记的租户只追加 `action.facts`，评估结论不变（1.2.0 下无档案要求 verifiedInputs）。
- **事实格式**（ADR 0040 §11 D3，逐字）：签名载荷 `{ alg, audience, claims, expiresAt, issuedAt, issuer, kid, sources }` 的 RFC 8785（JCS）字节串，Ed25519 直接签该字节串，签名 base64url 无填充、64 字节；`alg` 只用 `Ed25519`；`audience == tenantId`（`policyTenantId(policy)`）；`expiresAt − issuedAt ≤ 24h`，本计划取 **10 分钟**（`FACT_TTL_MS = 600_000`）；每请求 facts ≤ 8、claims 总数 ≤ 64、路径 ≤ 128 字符；`sources` 键集 == `claims` 键集，值 ≤ 64 字符。cloud 每次至多附 **1** 条 fact。
- **claim 值必须与 action 对应路径的值 JCS 字节相等**，否则 aster-api 400 `invalid_fact`（`claim_mismatch`）且整个 from-evidence 失败。fact-signer 在签名**之前**比对，不等的 claim 丢弃（记 `value_mismatch`），永不发出会被拒的 fact。
- **数值**：JCS 按 I-JSON 拒绝 |x| > 2^53−1 与非有限数（`unsafe_integer`），拒绝含孤立代理项的字符串（`invalid_string`）；与 aster-api `jcs-sha256-v1-vectors.json` 逐字节一致。
- **签发端不接受原值**（ADR 0046 D5 第 2 项）：`fact-signer` 输入只有 `{ path, source, args }`；带 `value` 或 `claims` 键 → `FactSignerError('claims_not_allowed', path)`；`source` 不在清单 → `unknown_fact_source`；`path` 不在该 source 的 `allowedPaths` → `path_not_allowed`；其他畸形 → `invalid_fact_request`。四个码的 `status` 均为 400。来源函数清单是代码常量（`src/config/fact-sources.ts`），不可运行时配置。
- **来源函数清单**（本计划第一期）：`hmac.userId`（`principal.id`）、`idp.businessRoles`（`principal.roles`，取现有 `loadBusinessRoles(userId, tenantId)`——`src/lib/business-roles.ts`，即 `createPolicyApiClientForUser` 签入 HMAC v3 的同一函数，读团队成员 / 个人的已登记业务角色；**不读**执行输入里的 `principal_roles`）、`fixture.creditPilot`（`context.*`；仅 `process.env.NODE_ENV !== 'production'` 且租户为 `credit-pilot` 时登记）。
- **cloud 签发方身份**：issuer、kid、私钥、公钥全部来自 Worker secret `ASTER_FACT_SIGNING_KEY`（JSON `{"issuer","kid","privateKey":"<pkcs8 base64>","publicKey":"<base64url 原始 32 字节>"}`）；缺失或自检失败时 cloud 不签任何事实（只告警一次），登记照常进行。不新增数据库表，不在 cloud 存签发方状态（aster-api 是唯一真相源）。
- **签发方名字段**（cloud 本地，比 aster-api 更严，使 HMAC 签名路径与 api 解码后的路径一致）：`^[A-Za-z0-9._~-]+$`，且不等于 `.` 或 `..`；issuer ≤ 255、kid ≤ 64。不合规 → cloud 400 `invalid_issuer_name`，不发请求。
- **权限**：读签发方 = 个人租户本人或团队 `TEAM_VIEW`；写 / 吊销 = 个人租户本人或团队 `MEMBER_UPDATE_ROLE`（ADR 0040 §11 D3，不新增权限常量）。
- **注册表四份副本同值**：locales `controls/registry.json`（真相源）、core `src/main/resources/governance/controls-registry.json`、ts `src/governance/controls-registry.data.ts`、cloud `src/config/controls-registry.json`。版本号 1.3.0 唯一变化：`eu-ai-act-high-risk.requires.verifiedInputs: true`，`governed` 保持 `false`。
- **1.3.0 翻转的硬前置（全部满足才可开列车）**：G0 PR 0（ADR §11.7）已由用户合并；G1 ⑥ 全部 PR 已合并并部署；G2 本地栈以 1.3.0 注册表跑通信贷试点（九款状态不变、`reviewers[]` 含 Credit Officer、require_approval 那条为 PENDING），并跑通「未附事实 → DENY 消耗证据 → 重新执行得新证据 → PENDING」的恢复路径；G3 **生产可验证性台账**：P2 报告中每个声明 `eu-ai-act-high-risk` 的生产模块（含近 30 天无执行量的）都有用户写下的一条裁定，且只能是三种之一——(a) 可验证：列出覆盖其全部 `inputRefs` 的 `issuer#kid` 与来源函数，且该租户签发方已启用；(b) 接受翻转后 DENY：须引用 PR 0 中批准该口径的条款；(c) 阻塞翻转。存在任一 (c) 或无裁定的模块 → 不开列车；G4 aster-api `origin/main` 含 #487 与 #492；G5 列车前常规 gate scan 的差异只有 G3 台账里列出的 E708。
- 不新增依赖（cloud 不引 `canonicalize` / `@noble/ed25519`；platform 脚本只用 Node 内建）。代码注释、提交信息简体中文；提交末尾空行后 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`；PR 描述末尾 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`，`Refs #461`。
- 不读取 k8s Secret、不连生产库、不执行 `wrangler secret put`；需要用户执行的步骤逐字写进 PR 描述。跨仓联合验证的 PR 用同名分支，合并后立即删除分支（platform#114 教训）。
- cloud 每个 PR 的本地验证：`pnpm lint && pnpm exec tsc --noEmit && pnpm test:run`，加本 PR 列出的定向用例；涉及 local-stack 的按 memory「Aster local verification env」配方起栈后跑 Playwright。

## Review Focus

1. **租户未登记 cloud 签发方（或已吊销 / 过 `notAfter`）时附了事实**：aster-api 400 `invalid_fact`（`unknown_issuer / issuer_disabled / issuer_expired`）会让本应 PENDING 的登记整体失败。期望：签前查 `listFactIssuers()`，只有 `issuer#kid` 存在、`enabled` 且 `notAfter` 为空或晚于 `now + FACT_TTL_MS` 时才附；否则不附、照常登记。测试在 Task C8（`noFactsWhenCloudIssuerNotEnabled`、`noFactsWhenIssuerExpiresWithinTtl`）。
2. **claim 值与 action 不等**（执行人在输入里自报的 `principal_roles` 与真实业务角色不同、顺序不同；试点输入与任何 fixture 不相等）：期望该 claim 被丢弃，其余 claim 照签；全部丢弃时不附 fact。测试在 Task C7（`mismatchingClaimIsDroppedBeforeSigning`、`rolesInDifferentOrderAreDropped`）与 Task C9（`nonFixtureApplicantIsNotSigned`）。
3. **签发方名含 `.`、`~`、`%`、空格或恰为 `..`**：`.`/`~` 合法且签名路径与 api 一致；`..` 与需编码字符在 cloud 本地 400 `invalid_issuer_name`，不产生 `invalid_signature`。测试在 Task C3（`issuerSegmentValidation`）。
4. **DELETE 返回 204 无体与吊销联动 503**：客户端不得因 `response.json()` 失败把成功的吊销报成 500；503 `receipt_unavailable` 原样透传为可重试错误（签发方已禁用）。测试在 Task C3（`deleteFactIssuer204ReturnsVoid`）与 Task C4（`deleteReceiptUnavailableIs503Retryable`）。
5. **翻转后第一次 from-evidence 没带事实即消耗证据**（ADR 0046 §11.6 第 11 条）：重新登记（日志页「重新登记」）必须走同一附事实路径，且不能对已被 DENY 消耗的 correlationId 自动重试。测试在 Task C8（`reRegisterRouteAlsoAttachesFacts`、`invalidFactIsNotRetriedWithoutFacts`）。

---

## 1. 文件结构（aster-cloud）

| 文件 | 职责 |
|---|---|
| `src/lib/jcs.ts` | RFC 8785 规范化：`jcsCanonicalize(value: unknown): string`、`JcsError` |
| `src/__tests__/fixtures/jcs-sha256-v1-vectors.json` | aster-api `src/test/resources/guard/jcs-sha256-v1-vectors.json` 的逐字节副本 |
| `src/services/guard/fact-signing-key.ts` | 解析 `ASTER_FACT_SIGNING_KEY`、导入 WebCrypto 密钥、启动自检（PR 2，设置页要用公钥） |
| `src/config/fact-sources.ts` | 来源函数清单与 `allowedPaths` 匹配 |
| `src/services/guard/fact-signer.ts` | `{path, source, args}` → 取值 → 与 action 比对 → 签一条 fact |
| `src/services/guard/fact-plan.ts` | 由 action 推出要签哪些路径；判定本租户 cloud 签发方是否可用 |
| `src/lib/fact-issuer-access.ts` | 读 / 写签发方的租户权限判定 |
| `src/app/api/fact-issuers/[tenantId]/route.ts`、`[tenantId]/[issuer]/[kid]/route.ts` | 代理 aster-api 签发方端点 |
| `src/components/guard/fact-issuers-panel.tsx` | 签发方列表、启用 cloud 签发方、登记外部签发方、吊销 |
| `src/app/[locale]/(dashboard)/settings/fact-issuers/page.tsx` | 个人租户设置子页 |
| `scripts/gen-fact-signing-key.ts` | 生成 `ASTER_FACT_SIGNING_KEY` 的 JSON 值 |
| 修改：`policy-api.ts`、`guard-types.ts`、`config/api-versions.ts`、`guard-register.ts`、`control-registry.ts`、`controls-registry.json`、`core-module-summary.ts`、`side-panel.tsx`、`teams/[teamId]/settings/page.tsx`、`settings/page.tsx`、`approvals-content.tsx`、`upstream-error-messages.ts`、`demo-supplement.ts`、`config/routes.yaml`、`seed-credit-pilot.ts`、`scripts/run-credit-pilot.ts`、`credit-pilot.spec.ts`、`local-stack/README.md` | 见各 Task |

---

## 2. PR 清单与合并顺序

| 阶段 | # | 仓 / 分支 | 内容 | 合并门 |
|---|---|---|---|---|
| 0 | 0 | aster-api `docs/x4-adr-clarify` | ADR 0046 新增 §11.7「X4 实施澄清」与 ADR 0041 §11 D4 补注：本文「对上游文档的更正」1–8 条，按 §6 用户裁定的结果写；其中偏离 ADR 原文的范围变更（`catalog.piiMark` 推迟、v6 `factSources` 推迟、生产高风险模块翻转后 DENY 的接受口径）以本 PR 为唯一批准记录 | **硬门**：用户审阅合并；任何实现 PR（含 PR 1）合并前必须先合 |
| A | 1a | aster-cloud #747（已开） | ts 1.5.0（auto-upgrade） | 已绿；PR 1 之前合并 |
| A | 1 | aster-cloud `feat/x4-registry-120-input-refs` | Task C1、C2 | 本地验证 |
| A | 2 | aster-cloud `feat/x4-fact-issuers` | Task C3、C4、C5 | 本地验证；PR 1 之后 |
| A | 3 | aster-cloud `feat/x4-fact-signer` | Task C6、C7、C8、C9 | PR 0、2 之后；用户先 `wrangler secret put ASTER_FACT_SIGNING_KEY` |
| B | 4 | aster-lang-platform `feat/gate-scan-input-refs` | Task P1 | 本地 `gate-scan.test.sh` 通过 |
| B | — | （用户执行） | Task P2：生产只读扫描与影响报告 | G3 |
| C | 5 | `feat/registry-130` 同名分支组：locales #5a → core #5b → ts #5c → test #5d（按需） | Task R1–R4 | 一次性连续合并；G1–G4 |
| D | 6 | aster-lang-platform `chore/release-1.0.45` + 各引擎 pin bump | Task T1 | G5 |
| D | 7 | aster-api `chore/release-1.0.45` | Task T2（pin + 两处测试） | 随列车 |
| E | 8 | aster-cloud `chore/ts-160-registry-130` | Task E1 | 列车完成后当天 |

---

## 3. 阶段 A：aster-cloud ⑥

### Task C1：ts 1.5.0 之上的注册表副本 1.2.0 与 `verifiedInputs` 类型

**Files:**
- Modify: `src/config/controls-registry.json`（逐字复制 aster-lang-locales `v1.0.44` 的 `controls/registry.json`）、`src/services/evidence/control-registry.ts`
- Modify: `src/__tests__/services/evidence/bundle.test.ts:345`、`src/__tests__/services/evidence/regulatory-mapping.test.ts:108`（期望值改为 `defaultControlRegistry.version`）
- Create: `src/__tests__/services/evidence/control-registry-ts-parity.test.ts`

**Interfaces:**
- Produces: `RegistryProfile.requires: { ruleId: boolean; registeredControls: boolean; frameworks: string[]; verifiedInputs?: boolean }`；`requiresVerifiedInputs(profileId: string | undefined, registry?: ControlRegistryData): boolean`（未知档案 / 缺键 → `false`，与 ADR 0046 §11.6 第 6 条同口径）。

- [ ] **Step 1: 写失败测试** `control-registry-ts-parity.test.ts`（CI 必跑，不依赖兄弟仓）：

```ts
import { CONTROLS_REGISTRY } from '@aster-cloud/aster-lang-ts/browser';
it('cloud 副本与已安装 aster-lang-ts 的副本深度相等', () => {
  expect(defaultControlRegistry).toEqual(CONTROLS_REGISTRY);
});
it('requiresVerifiedInputs 按档案判定', () => {
  expect(requiresVerifiedInputs('eu-ai-act-high-risk')).toBe(false); // 1.2.0
  expect(requiresVerifiedInputs('nope')).toBe(false);
  expect(requiresVerifiedInputs(undefined)).toBe(false);
  const flipped = structuredClone(defaultControlRegistry);
  flipped.profiles.find((p) => p.id === 'eu-ai-act-high-risk')!.requires.verifiedInputs = true;
  expect(requiresVerifiedInputs('eu-ai-act-high-risk', flipped)).toBe(true);
});
```

- [ ] **Step 2:** `pnpm vitest run src/__tests__/services/evidence` → parity 用例 FAIL（版本 1.1.0 ≠ 1.2.0）。
- [ ] **Step 3:** 复制注册表、加类型与 `requiresVerifiedInputs`。
- [ ] **Step 4:** 同一命令 PASS；另跑既有 `control-registry-sources.test.ts`（X1 守护）与 `control-registry-parity.test.ts`（兄弟仓存在时）。
- [ ] **Step 5: 提交** `chore(governance): 注册表副本升 1.2.0，档案要求增 verifiedInputs（ADR 0046 §11 D1）`

### Task C2：编辑器 Decision 页签展示 `inputRefs`

**Files:**
- Modify: `src/components/policy/core-module-summary.ts`、`src/components/policy/policy-form/use-compile.ts`（`CompileModuleSummary` 同步加字段）、`src/components/policy/policy-form/side-panel.tsx`（`DecisionTab`）、`src/i18n/demo-supplement.ts`（`policies.form.decisionInputRefs*` 四语）
- Test: `src/__tests__/components/policy/core-module-summary.test.ts`、`src/__tests__/components/policy/side-panel-decision.test.tsx`

**Interfaces:**
- Consumes: `requiresVerifiedInputs`（Task C1）。
- Produces: `EditorCompileModuleSummary.inputRefs?: Record<string, readonly string[]>`——键为函数名，值为 Core IR `Func.inputRefs` 原样（引擎路径）；只有至少一个 Func 带数组时才出现该键。

- [ ] **Step 1: 写失败测试**：
  - `summarizeCoreModule`：`decls` 中 `{kind:'Func', name:'decide', inputRefs:['applicant.creditScore']}` 与无 `inputRefs` 的 `helper` → `inputRefs` 为 `{ decide: ['applicant.creditScore'] }`；没有任何 Func 带数组 → 无该键；`inputRefs: []` 原样保留为 `[]`。
  - `DecisionTab`（zh）：`inputRefs: { decide: ['applicant.creditScore', 'applicant.requestedAmount'] }` → 显示「规则读取的输入字段」与两条路径；`['*']` → 显示「无法静态确定」；`['']` → 显示「整个输入」；`[]` → 显示「不读取输入」；档案 `eu-ai-act-high-risk` 且注入的注册表要求 verifiedInputs 时显示提示「运行时这些字段须由已登记签发方签名」，`governed` 档案不显示提示。
- [ ] **Step 2:** `pnpm vitest run src/__tests__/components/policy` → FAIL。
- [ ] **Step 3:** 实现。`DecisionTab` 增可选 prop `registry?: ControlRegistryData`（缺省 `defaultControlRegistry`），只为测试注入。
- [ ] **Step 4:** PASS；`pnpm lint && pnpm exec tsc --noEmit`。
- [ ] **Step 5: 提交** `feat(editor): Decision 页签展示规则输入引用 inputRefs（ADR 0046 §11 D4）`

**PR 1 验证：** `pnpm test:run` 全绿；本地 `pnpm dev` 打开信贷试点 v3，Decision 页签列出五个 `applicant.*` 路径（截图贴 PR）。

### Task C3：`PolicyApiClient` 签发方方法与类型

**Files:**
- Modify: `src/config/api-versions.ts`（`guardFactIssuers`、`guardFactIssuer(issuer, kid)`）、`src/services/policy/guard-types.ts`、`src/services/policy/policy-api.ts`
- Test: `src/__tests__/services/policy-api-guard.test.ts`

**Interfaces:**
- Produces（`guard-types.ts`）：

```ts
export interface GuardFactIssuer {
  alg: 'Ed25519'; createdAt: string; createdBy: string; enabled: boolean;
  issuer: string; kid: string; notAfter?: string; publicKey: string;
}
export interface GuardFactIssuerPut { publicKey: string; enabled?: boolean; notAfter?: string }
```
- Produces（`policy-api.ts`）：`listFactIssuers(): Promise<{ issuers: GuardFactIssuer[] }>`、`putFactIssuer(issuer: string, kid: string, body: GuardFactIssuerPut): Promise<GuardFactIssuer>`、`deleteFactIssuer(issuer: string, kid: string): Promise<void>`；`export function isFactIssuerSegment(value: string, maxLength: number): boolean`（Global Constraints 的正则、长度、`.`/`..`），方法内不合规抛 `PolicyApiError('Invalid issuer name', 400, 'invalid_issuer_name')`；`request<T>` 对 `response.status === 204` 返回 `undefined as T`。
- 三个方法都用 `GUARD_TIMEOUT_MS`。

- [ ] **Step 1: 写失败测试**：`listFactIssuersSignsPathname`（fetch 收到 `X-Internal-Signature` 等头、URL 为 `/api/v1/guard/fact-issuers`）；`putFactIssuerEncodesSegments`（`idp.example.com` / `2026-11` 原样出现在 path）；`issuerSegmentValidation`（`'..'`、`'.'`、`'a b'`、`'a%2f'`、`'a#b'`、256 字符 issuer、65 字符 kid → 400 `invalid_issuer_name` 且 fetch 未被调用；`'idp.example.com'`、`'k~1'` 通过）；`deleteFactIssuer204ReturnsVoid`（mock 204 无体 → resolve `undefined`）；`putConflictSurfacesCode`（409 `{error:'issuer_key_conflict'}` → `PolicyApiError.code === 'issuer_key_conflict'`）。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(guard): PolicyApiClient 增签发方管理调用（ADR 0040 §11 D3）`

### Task C4：签发方代理路由与权限

**Files:**
- Create: `src/lib/fact-issuer-access.ts`、`src/services/guard/fact-signing-key.ts`、`scripts/gen-fact-signing-key.ts`（`package.json` 增 `"fact-key:gen": "tsx scripts/gen-fact-signing-key.ts"`）、`src/app/api/fact-issuers/[tenantId]/route.ts`、`src/app/api/fact-issuers/[tenantId]/[issuer]/[kid]/route.ts`
- Modify: `src/lib/upstream-error-messages.ts`（登记 `invalid_issuer`、`issuer_key_conflict`、`issuer_revoked`、`receipt_unavailable`、`invalid_issuer_name` 的英文文案）
- Test: `src/__tests__/api/fact-issuers-route.test.ts`、`src/__tests__/services/guard/fact-signing-key.test.ts`

**Interfaces:**
- Consumes: Task C3 三个方法与 `isFactIssuerSegment`、`createPolicyApiClientForUser(tenantId, userId)`、`checkTeamPermission`。
- Produces：
  - `fact-signing-key.ts`：`interface FactSigningKey { issuer: string; kid: string; publicKey: string; privateKey: CryptoKey }`；`loadFactSigningKey(raw = safeEnv('ASTER_FACT_SIGNING_KEY')): Promise<FactSigningKey | null>`——JSON 解析、issuer / kid 过 `isFactIssuerSegment`、`publicKey` 解码恰 32 字节、pkcs8 导入（同 `ai-workspace-handoff.ts`）后对固定探针签名并用 `publicKey` 验签；任一失败返回 `null` 并告警一次（不打印密钥）；结果按 `raw` 值缓存。`gen-fact-signing-key.ts` 用 WebCrypto 生成密钥对，打印一行 JSON（issuer 缺省 `aster-cloud`，kid 由参数 `--kid` 给出，必填）。
  - `canViewFactIssuers(userId: string, tenantId: string): Promise<boolean>`、`canManageFactIssuers(userId: string, tenantId: string): Promise<boolean>`（个人租户 `tenantId === userId` 恒 true；团队分别要 `TEAM_VIEW` / `MEMBER_UPDATE_ROLE`）。
  - `GET /api/fact-issuers/[tenantId]` → `200 { issuers: GuardFactIssuer[], cloudIssuer: { issuer, kid, publicKey } | null, canManage: boolean }`。
  - `PUT /api/fact-issuers/[tenantId]/[issuer]/[kid]` body `GuardFactIssuerPut` → 200 `GuardFactIssuer`。
  - `DELETE …` → 204。
  - 错误：401 `unauthorized`；403 `forbidden`；400 `invalid_issuer_name`（cloud 本地）；上游 4xx 按原状态码与 `error` 码透传、文案取 `registeredUpstreamMessage`；上游 503 `receipt_unavailable` → 503 原码（可重试）；其他 408 / 5xx / 网络错误 → 502 `upstream_unavailable` 带 `requestId`（同 `approvals/[tenantId]/…/[verb]/route.ts` 的 `apiErrorResponse`）。

- [ ] **Step 1: 写失败测试**：`fact-signing-key`：`malformedJsonIsNull`、`publicKeyMismatchIsNull`（私钥与公钥不配对）、`badIssuerNameIsNull`、`validKeyLoads`；路由：`viewerCanListButNotPut`（团队 viewer：GET 200、PUT 403）；`adminCanPutAndDelete`；`personalTenantOwnerCanManage`；`otherUsersPersonalTenantIs403`；`invalidIssuerNameIs400WithoutUpstreamCall`；`deleteReceiptUnavailableIs503Retryable`；`upstreamTimeoutIs502WithRequestId`；`cloudIssuerNullWithoutSigningKey`。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(guard): 签发方管理代理路由，按租户权限读写（ADR 0040 §11 D3）`

### Task C5：签发方设置 UI 与收件箱吊销原因

**Files:**
- Create: `src/components/guard/fact-issuers-panel.tsx`、`src/app/[locale]/(dashboard)/settings/fact-issuers/page.tsx`
- Modify: `src/app/[locale]/(dashboard)/teams/[teamId]/settings/page.tsx`（`TeamLanguageCard` 之后嵌入 `FactIssuersPanel`）、`src/app/[locale]/(dashboard)/settings/page.tsx`（链接到子页）、`config/routes.yaml`（`/settings/fact-issuers`，同 `/settings/api-keys` 条目形状）、`src/services/policy/guard-types.ts`（`GuardApprovalItem.expiredReason?: string | null`）、`src/app/[locale]/(dashboard)/approvals/approvals-content.tsx`、`src/i18n/demo-supplement.ts`（`factIssuers.*`、`approvals.expiredReason.fact_issuer_revoked` 四语）
- Test: `src/__tests__/components/guard/fact-issuers-panel.test.tsx`、`src/app/[locale]/(dashboard)/approvals/approvals-content.test.tsx`

**Interfaces:**
- Produces: `FactIssuersPanel({ tenantId }: { tenantId: string })`——自取 `GET /api/fact-issuers/[tenantId]`，按 `canManage` 决定是否渲染写操作。
- 行为：列表列 issuer、kid、状态（启用 / 已吊销）、`notAfter`、创建人；「启用 Aster Cloud 签发方」按钮在 `cloudIssuer` 非 null 且列表中不存在同 `issuer#kid` 时出现，PUT `{ publicKey: cloudIssuer.publicKey }`；「登记外部签发方」表单（issuer、kid、publicKey、可选 notAfter）；「吊销」用 `ConfirmDialog`，文案写明「不可恢复；依赖该签发方的待审批会被自动拒绝」。

- [ ] **Step 1: 写失败测试**：`rendersIssuersAndStatus`；`enableCloudIssuerPutsConfiguredPublicKey`；`enableButtonHiddenWhenAlreadyRegistered`；`viewerSeesNoWriteControls`；`revokeRequiresConfirm`；`conflictShowsLocalizedMessage`（409 `issuer_key_conflict`）；收件箱 `expiredReasonFactIssuerRevokedShowsBadge`（EXPIRED 且 `expiredReason: 'fact_issuer_revoked'` → 「签发方已吊销，自动拒绝」，自然过期无该徽标）。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS；`pnpm routes:check`、`pnpm check:message-keys`。
- [ ] **Step 5: 提交** `feat(settings): 团队与个人签发方设置页，收件箱显示吊销原因（ADR 0040 §11 D3/D4）`

**PR 2 验证：** 本地栈：团队 owner 登记一个外部签发方 → 列表出现；viewer 只读；吊销后状态为已吊销；aster-api `GET /fact-issuers`（HMAC）同步（截图 + 命令输出贴 PR）。

### Task C6：RFC 8785 规范化

**Files:**
- Create: `src/lib/jcs.ts`、`src/__tests__/fixtures/jcs-sha256-v1-vectors.json`（`cp` 自 aster-api，提交前 `shasum -a 256` 两份相等并写入 PR 描述）
- Test: `src/__tests__/lib/jcs.test.ts`

**Interfaces:**
- Produces: `jcsCanonicalize(value: unknown): string`；`class JcsError extends Error { code: 'unsafe_integer' | 'invalid_string' | 'unsupported_value' }`。对象键按 UTF-16 码元序（JS 默认 `sort`）；数字用 `JSON.stringify` 的 ECMAScript 格式；|x| > 2^53−1 或非有限 → `unsafe_integer`；`!s.isWellFormed()` 的字符串（含键）→ `invalid_string`；`undefined`、函数、`bigint` → `unsupported_value`。

- [ ] **Step 1: 写失败测试**：对向量文件逐条：有 `canonical` 的 → `jcsCanonicalize(JSON.parse(input)) === canonical` 且 `sha256(canonical) === sha256`；`error` 为 `unsafe_integer` / `invalid_string` 的 → 抛该码；`error` 为 `duplicate_key` 的 3 条显式跳过，`it.skip` 名称写明「JS 值不可能含重复键；cloud 只规范化自己构造的值」；外加 `vectorFileIsByteIdenticalToApi`（文件 sha256 等于写死的 aster-api 值）。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(guard): RFC 8785 规范化，复用 aster-api 共享向量（ADR 0040 §12 D2）`

### Task C7：来源函数清单与 fact-signer

**Files:**
- Create: `src/config/fact-sources.ts`、`src/services/guard/fact-signer.ts`
- Test: `src/__tests__/services/guard/fact-signer.test.ts`

**Interfaces:**
- Consumes: `FactSigningKey`（Task C4）、`jcsCanonicalize`（Task C6）。
- `fact-sources.ts`：

```ts
export interface FactSourceContext { tenantId: string; userId: string; input: Record<string, unknown> }
export interface FactSource {
  id: string;
  allowedPaths: readonly string[];        // 精确路径，或以 '.*' 结尾表示其下任意更深路径
  resolve(ctx: FactSourceContext, path: string, args: Record<string, unknown>): Promise<unknown | undefined>;
}
export function factSourcesFor(tenantId: string, nodeEnv = process.env.NODE_ENV): ReadonlyMap<string, FactSource>;
export function pathAllowed(allowed: readonly string[], path: string): boolean;
```
  `fixture.creditPilot` 的 `resolve` 只对 `path === 'context.applicant'` 返回值：在 `PILOT_APPLICANTS` 中找与 `ctx.input.applicant` JCS 相等的一组并返回该常量，否则 `undefined`。
- `fact-signer.ts`：

```ts
export interface FactRequest { path: string; source: string; args?: Record<string, unknown> }
export type FactSignerErrorCode = 'claims_not_allowed' | 'unknown_fact_source' | 'path_not_allowed' | 'invalid_fact_request';
export class FactSignerError extends Error { readonly status = 400; constructor(readonly code: FactSignerErrorCode, readonly path?: string) }
export interface GuardFact { alg: 'Ed25519'; audience: string; claims: Record<string, unknown>; expiresAt: string;
  issuedAt: string; issuer: string; kid: string; sources: Record<string, string>; signature: string }
export interface SignFactsResult { fact: GuardFact | null; skipped: Array<{ path: string; reason: 'unresolved' | 'value_mismatch' | 'ambiguous_path' }> }
export function signFacts(input: { ctx: FactSourceContext; requests: unknown[]; action: GuardAction;
  key: FactSigningKey; sources: ReadonlyMap<string, FactSource>; now: Date }): Promise<SignFactsResult>;
export const FACT_TTL_MS = 600_000;
```
  先整体校验 `requests`（数组、≤ 64 项、每项只含 `path/source/args` 三键、`path` 为 ≤ 128 字符的非空串、同一 path 不重复），再逐项取值；`resolve` 返回 `undefined` → `unresolved`；沿路径经过的任一对象含带 `.` 的字面键、且其点路径与 claim 路径相等或互为前缀 → `ambiguous_path`（与 aster-api `FactVerifier.requireUnambiguous` 同口径，否则整请求 400 `ambiguous_path`）；路径在 action 中不存在或与 `valueAtPath(action, path)` 的 JCS 串不等 → `value_mismatch`；剩余 claim 为空 → `fact: null`。`issuedAt = now.toISOString()`，`expiresAt = now + FACT_TTL_MS`，`audience = ctx.tenantId`。

- [ ] **Step 1: 写失败测试**（ADR 0046 D5 第 2 项验收逐条）：`valueKeyIsClaimsNotAllowed`（`{path:'principal.id', source:'hmac.userId', value:'x'}` → `claims_not_allowed`，`path` 为 `principal.id`）；`unknownSourceRejected`；`pathOutsideAllowedPathsRejected`（`hmac.userId` 签 `context.amount`）；`moreThan64RequestsRejected`；`signedPayloadExactJcsBytes`（固定 `now` 与测试密钥：断言载荷 JCS 文本逐字节等于写死的期望串，再用公钥验签通过）；`sourcesMatchClaimsKeySet`；`mismatchingClaimIsDroppedBeforeSigning`；`dottedKeyCollisionIsDroppedAsAmbiguous`（`context` 含字面键 `"applicant.x"` 时不签 `context.applicant`）；`rolesInDifferentOrderAreDropped`；`allDroppedYieldsNullFact`；`fixtureSourceAbsentInProduction`（`factSourcesFor('credit-pilot', 'production')` 不含 `fixture.creditPilot`）；`fixtureSourceOnlyForCreditPilotTenant`。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 提交** `feat(guard): 服务端签发端 fact-signer 与来源函数清单（ADR 0046 §11 D5 第 2 项）`

### Task C8：from-evidence 附签名事实

**Files:**
- Create: `src/services/guard/fact-plan.ts`
- Modify: `src/lib/guard-register.ts`（`registerGuardDecision`）、`src/services/policy/guard-types.ts`（`GuardAction.facts?: GuardFact[]`；`GuardDecision.inputSources?: { verified?: string[]; factIssuers?: string[]; factsStale?: boolean }`）
- Test: `src/__tests__/lib/guard-register.test.ts`、`src/__tests__/services/guard/fact-plan.test.ts`

**Interfaces:**
- Consumes: Task C3 `listFactIssuers`、Task C7 全部导出、`loadBusinessRoles`。
- Produces（`fact-plan.ts`）：
  - `planFactRequests(action: GuardAction, sources: ReadonlyMap<string, FactSource>): FactRequest[]`——`action.principal.type === 'user'` → `{path:'principal.id', source:'hmac.userId'}`；`principal.roles` 非空 → `{path:'principal.roles', source:'idp.businessRoles'}`；清单含 `fixture.creditPilot` 且 `action.context?.applicant` 存在 → `{path:'context.applicant', source:'fixture.creditPilot'}`。
  - `cloudIssuerUsable(issuers: GuardFactIssuer[], key: FactSigningKey, now: Date): boolean`——同 `issuer` 与 `kid`、`enabled`、`publicKey === key.publicKey`、`notAfter` 缺省或 `> now + FACT_TTL_MS`。
  - `attachFacts(client: PolicyApiClient, action: GuardAction, ctx: FactSourceContext, now: Date): Promise<GuardAction>`——无密钥 / 签发方不可用 / `fact: null` → 原样返回 `action`（对象同一引用，保证请求体逐字节不变）；否则返回 `{ ...action, facts: [fact] }`。`listFactIssuers` 失败按「不可用」处理（记日志，不阻断登记）。
- `registerGuardDecision` 在 `toGuardAction` 之后、`guardFromEvidence` 之前调用 `attachFacts`。`invalid_fact` 400 按现有路径记 `guardError: 'invalid_fact'`，**不**去掉事实重试。

- [ ] **Step 1: 写失败测试**：`noFactsWhenSigningKeyMissing`（请求体与今天 `toEqual` 且 `action` 不含 `facts` 键）；`noFactsWhenCloudIssuerNotEnabled`；`noFactsWhenIssuerExpiresWithinTtl`；`noFactsWhenListIssuersFails`；`attachesOneFactWithPrincipalId`（`claims['principal.id'] === requesterUserId`，`sources['principal.id'] === 'hmac.userId'`）；`reRegisterRouteAlsoAttachesFacts`（经 `registerGuardDecisionWith` 走同一函数）；`invalidFactIsNotRetriedWithoutFacts`（`guardFromEvidence` 只被调用一次，结果 `guardError: 'invalid_fact'`）。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS + 既有 `guard-register.test.ts`、`policies-execute-guard.test.ts`、`guard-register-route.test.ts` 全绿。
- [ ] **Step 5: 提交** `feat(guard): from-evidence 首次请求即附签名事实（ADR 0046 §11.6 第 11 条）`

### Task C9：信贷试点测试签发方与本地栈验收

**Files:**
- Modify: `src/scripts/seed-credit-pilot.ts`（新步骤 `registerPilotFactIssuer`：`loadFactSigningKey()` 为 null → 打印「未配置 ASTER_FACT_SIGNING_KEY，跳过签发方登记」并以非零退出；否则以 `cp-owner` 身份对租户 `credit-pilot` `putFactIssuer(key.issuer, key.kid, { publicKey: key.publicKey })`，409 `issuer_key_conflict` → 打印「换 kid 或吊销旧钥」并非零退出；幂等）、`scripts/run-credit-pilot.ts`（新增 `assertGuardOutcome(expected: 'PENDING')`：require_approval 那条执行的 guard 决策须有 `guardApprovalId`）、`src/__tests__/e2e-browser/local-stack/credit-pilot.spec.ts`（大额申请后审批出现在 cp-officer 收件箱，现有断言不变）、`src/__tests__/e2e-browser/local-stack/README.md`（env 表增 `ASTER_FACT_SIGNING_KEY`，取值来自 `pnpm fact-key:gen`）
- Test: `src/__tests__/scripts/seed-credit-pilot-issuer.test.ts`、`src/__tests__/services/guard/fact-sources-credit-pilot.test.ts`

- [ ] **Step 1: 写失败测试**：`fixtureApplicantSignedFromConstant`（输入为 `PILOT_APPLICANTS.requireApproval` 的结构等值拷贝 → claim 值 `toBe` 常量对象本身）；`nonFixtureApplicantIsNotSigned`（`requestedAmount` 改 1 → `undefined`）；seed：`registersConfiguredPublicKey`、`missingKeyExitsNonZero`、`conflictExitsNonZeroWithHint`。
- [ ] **Step 2:** FAIL。 **Step 3:** 实现。 **Step 4:** PASS。
- [ ] **Step 5: 本地栈验收（registry 1.2.0）**：按配方起栈（cloud 容器加 `-e ASTER_FACT_SIGNING_KEY=…`）→ `pnpm seed:credit-pilot` 两次（第二次幂等）→ `tsx scripts/run-credit-pilot.ts` 退出码 0 → 读 aster-api `GET /api/v1/audit/receipts?decisionIds=<require_approval 那条>`（cp-owner key），metadata 含 `verifiedFields` ⊇ `["context.applicant","principal.id"]`、`factSources` 含 `context.applicant=fixture.creditPilot`、`factIssuers` 含 `<issuer>#<kid>` → Playwright `credit-pilot.spec.ts` 通过。输出贴 PR。
- [ ] **Step 6: 提交** `feat(pilot): 信贷试点测试签发方与 fixture 来源（ADR 0046 §11 D5 第 2(c) 项）`

**PR 3 部署（用户执行，逐字写进 PR 描述）：** 1) 本机 `pnpm fact-key:gen` 生成值并存 1Password；2) `echo "$VALUE" | wrangler secret put ASTER_FACT_SIGNING_KEY --name aster-cloud`；3) 合并 PR 3（`deploy-worker.yml` 发布）；4) 冒烟：任一团队 owner 打开 `/teams/<id>/settings` 见「启用 Aster Cloud 签发方」按钮（不必点击）。`docs/runbooks/secrets-rotation.md` 增一节：轮换 = 新 kid 写入 secret → 各租户在设置页启用新 kid → 吊销旧 kid（顺序不可反，否则吊销会让待审批过期）。

---

## 4. 阶段 B：生产只读扫描（X4 输入清单）

### Task P1：gate-scan 增 `input-refs` 子命令

**Files（aster-lang-platform）:**
- Create: `scripts/gate-scan/input-refs.mjs`、`scripts/gate-scan/export-profile-impact.sql`
- Modify: `scripts/gate-scan/gate-scan.sh`（子命令 `input-refs`，参数 `--ts <ref|path:dir> --profile <id> [--sources <dir>] [--impact <jsonl>]`，导出 / 清理沿用现有 `export` 逻辑）、`scripts/gate-scan/gate-scan.test.sh`、`scripts/gate-scan/README.md`

**Interfaces:**
- `export-profile-impact.sql`（READ ONLY 事务，末行 `{"_count":N}`）：cloud 变量下每行 `{"id":"cloud:<policyId>"|"cloudv:<versionId>","tenant":"<teamId 或 userId>","gatedExecutions30d":<近 30 天 decision in ('require_approval','escalate') 的执行数>}`；无 cloud 变量（aster_api 库）下每行 `{"tenant","issuer","kid","enabled","notAfter"}`（`guard_fact_issuers`）。
- `input-refs.mjs`：对每份源依 en-US → zh-CN → de-DE → 其余的顺序找第一个编译成功的 lexicon，用 `compileAndTypecheck(source, { lexicon, controls })` 编译，`controls = controlRegistryFrom(把 --profile 的 verifiedInputs 置 true 的 CONTROLS_REGISTRY 副本)`；只处理 Core IR `profile === --profile` 的模块。每个带 `inputRefs` 的 Func 输出一行：`id kind lexicon module rule inputRefs flags construct tenant gatedExecutions30d tenantHasEnabledIssuer`；`flags` ∈ `STAR`（含 `*`）/ `ROOT`（含 `""`）；`construct` 取同一规则 E708 诊断的 `construct` 参数。末尾汇总：模块数、规则数、`STAR` 规则数（按 construct 分组，单列 `whole-input-serialized`，供 ADR 0046 §11.6 第 16 条裁定）、`ROOT` 规则数、涉及租户数、其中无启用签发方的租户数。**绝不打印源码**；金丝雀：编译一份声明档案的已知模块，其 IR 无 `inputRefs` → 退出码 3（ts 构建低于 1.5.0）。退出码：0 = 无 `STAR`；1 = 有 `STAR`；2 = 用法错误；3 = 输入或构建不可信。

- [ ] **Step 1: 写失败测试**（`gate-scan.test.sh`，假 kubectl + 本地 ts 1.5.0 构建）：三份夹具源——字面路径规则、动态 Map 键规则、`Profile "governed"` 规则——断言输出恰两行（第三份被按档案过滤），动态键行 `flags=STAR construct=dynamic-map-key`，退出码 1；`--impact` 合入租户与执行数；源码中的哨兵串不出现在 stdout / stderr；ts 1.4.0 构建 → 退出码 3。
- [ ] **Step 2:** `bash scripts/gate-scan/gate-scan.test.sh` FAIL。 **Step 3:** 实现。 **Step 4:** PASS（现有用例不变）。
- [ ] **Step 5: 提交** `feat(gate-scan): input-refs 子命令列出档案模块的规则输入引用（ADR 0046 §11.4 ⑦ 前置扫描）`

### Task P2：用户执行生产扫描（只读）

PR 4 描述逐字给出：

```bash
export PATH=/opt/homebrew/opt/node@24/bin:$PATH
export ASTER_TS_REPO=~/IdeaProjects/aster-lang-ts KUBECONFIG=~/.kube/k3s-config
scripts/gate-scan/gate-scan.sh input-refs --ts v1.5.0 --profile eu-ai-act-high-risk --allow-empty-db
```

产出只贴进 PR 5a（locales）描述，不入库。用户按 Global Constraints 的 G3 台账格式逐模块裁定（(a) 可验证 / (b) 接受 DENY / (c) 阻塞），`STAR` 规则另须裁定「先改源码」或归入 (b)/(c)。台账写在 PR 5a 描述里；**G3 = 台账覆盖报告中全部模块且无 (c)。** ADR 0046 D5 第 3 项要求的「逐一确认有签发方配置」由 (a) 满足；(b) 是对该要求的显式例外，只在 PR 0 批准该口径后可用。 另由 `STAR` 中 `whole-input-serialized` 的命中数决定 §11.6 第 16 条（`Int.toString` 标量叶子豁免）是否需要先改引擎；若用户要求豁免，翻转推迟，豁免另开列车（不进本计划）。

---

## 5. 阶段 C–E：registry 1.3.0 翻转

### Task R1–R4：`feat/registry-130` 同名分支组（一次性连续合并：locales → core → ts → test）

- **R1 locales**（`controls/registry.json`）：`version` → `1.3.0`；`eu-ai-act-high-risk.requires.verifiedInputs` → `true`。`./gradlew build`（`profileRequiresProblems` 校验、`expected-problems.json` 不变）。
- **R2 core**：`src/main/resources/governance/controls-registry.json` 逐字同步；`ControlRegistryTest.默认注册表两个档案都不要求verifiedInputs` 改名 `默认注册表仅高风险档案要求verifiedInputs`（`governed` false、`eu-ai-act-high-risk` true、`version()` 为 `1.3.0`）；`git grep -n "verifiedInputs\|loadDefault" src/test` 逐处复核其余用例（`ProfileCheckTest`、`GovernanceInputRefsParityTest` 用注入注册表，不改）。`ControlRegistrySiblingParityTest` 在 checkout-sibling 下通过。
- **R3 ts**：`src/governance/controls-registry.data.ts` 同步（生成方式同 1.2.0）；`test/unit/governance/profile-check.test.ts:148-150` 改为 `version '1.3.0'`、`eu-ai-act-high-risk` 为 true；新增 `defaultRegistryReportsE708ForHighRiskStarRule`（默认注册表 + 动态键规则 → 一条 E708，`construct: 'dynamic-map-key'`）；`CHANGELOG.md` 1.6.0：「registry 1.3.0：`eu-ai-act-high-risk` 要求 verifiedInputs，输入引用无法静态确定的规则报 E708」。`controls-registry-parity.test.ts` 通过。
- **R4 test（按需）**：在本地 checkout-sibling 用 R1–R3 跑 `aster-lang-test` 的 tier1 parse / ir / eval（`--full`）。**判定规则**：若 `expectedInputRefs` 为 `["*"]` 的 `eu-ai-act-high-risk` 语料因 E708 失败，则把这些语料（只这些）的 `Profile` 改为 `"governed"`（IR 仍写 `inputRefs`，`expectedInputRefs` / `expectedInputRefConstructs` 不变；E708 的断言由两引擎共享 parity JSON 以注入注册表覆盖），并在 `.meta.json` 的 `source` 注明「1.3.0 起改用 governed，避免默认注册表下 E708」；若全部通过则不开 R4。
- **G2（本分支组合并前）**：本地以 composite 构建（各仓 `feat/registry-130`）起 aster-api，cloud 用 PR 3 之后的 main（cloud 副本仍为 1.2.0 不影响服务端判定）→ `pnpm seed:credit-pilot` → `run-credit-pilot.ts` 退出码 0：九款状态与 ADR 0044 §5 预期表一致、`reviewers[]` 含 Credit Officer、require_approval 那条为 PENDING；再把 cloud 容器去掉 `ASTER_FACT_SIGNING_KEY` 重跑同一条 → guard 决策 DENY、`reasonCode: unverified_input`、无审批行（反证门确实生效）；对该执行在日志页「重新登记」→ 返回同一 DENY（证据已被消耗，§11.6 第 11 条）；恢复 `ASTER_FACT_SIGNING_KEY` 后**重新执行**同一输入（新评估事件、新 correlationId）→ PENDING。四次输出贴 PR 5a。以上全部对真实 aster-api（`FactVerifier` + `VerifiedInputsGate`）进行，不以 cloud 本地验签代替。

### Task T1：列车 1.0.45（aster-lang-platform `chore/release-1.0.45` + 各仓 pin bump）

- `release-plan.json`：`platformVersion` / `ecosystemVersion` → `1.0.45`，`tsNpmVersion` → `1.6.0`，`uiMessagesNpmVersion` `1.0.30`、hi npm、test npm 不变（R4 只动语料不动 `packages/js`）；`artifacts[]` 同列车计划 PR 13 的改法。PR 描述列出本列车全部内容：R1–R4 与 runtime#77；开跑前在各引擎仓执行 `git log v1.0.44..origin/main --oneline`，出现清单之外的提交即停车。
- 各引擎 `chore/release-1.0.45` pin bump（同 1.0.44 的 13′），ts `package.json` → `1.6.0`。
- **列车前 gate scan（G5）**：`gate-scan.sh --old-ts v1.5.0 --new-ts feat/registry-130 --allow-empty-db`。期望 `newly-failing` / `newly-passing` 为空；`changed-codes` **只**含 E708，且每个 id 都在 P2 报告的 `STAR` 清单里。其他任何差异 → 停车。
- 运行：ADR 0053 `run-train.sh`，trainId `eco-1.0.45`，层序 L0 platform → L1 core / runtime / validation / test → L2 locales / hi → L3 truffle → L4 ts npm 1.6.0 + aster-api 部署。**L4 部署即生产翻转。**

### Task T2：aster-api pin 与测试（`chore/release-1.0.45`，随列车）

**Files:** `settings.gradle:19` → `1.0.45`；`.github/actions/setup-aster-build/action.yml:62` → `v1.0.45`；`src/test/java/io/aster/guard/provenance/ProfileRequirementsTest.java`；`src/test/java/io/aster/policy/api/PolicyEvaluationServiceRuleMetadataTest.java`。

- [ ] **Step 1: 改测试**：`noProfileInBundledRegistryRequiresVerifiedInputs` → `bundledRegistryRequiresVerifiedInputsOnlyForHighRisk`（`governed` false、`eu-ai-act-high-risk` true）；`highRiskProfileModuleIsNotGatedToday` → `highRiskProfileModuleIsGatedAfter130`：同一部署模块 `verifiedInputsRequired()` 为 true，全 declared 请求 `VerifiedInputsGate.check` 返回 `UNVERIFIED_INPUT`，`unverifiedFields` 为 `["context.amount"]`（该测试源码规则读 `context.amount`）。
- [ ] **Step 2:** 本地 composite（各仓 `feat/registry-130` 或已打的 `v1.0.45`）跑 `./gradlew --offline :replay:test :test --continue`；已知基线失败写进 PR。CI 在 `v1.0.45` tag 存在前为红，属预期（同 #457 / #476 / #491）。
- [ ] **Step 3: 提交** `chore(release): 生态 pin bump 1.0.44 → 1.0.45（registry 1.3.0：eu-ai-act-high-risk 要求 verifiedInputs）`

### 上线后冒烟（用户执行，需要 API key / 本地 HMAC）

1. `POST /api/v1/policies/compile`（声明 `eu-ai-act-high-risk` 的动态键源码，`X-Tenant-Id` = key 租户）→ `controlsRegistryVersion: "1.3.0"`，一条 E708 error，`success: true`。
2. 同档案、引用全部字面路径的源码 → 无 E708。
3. 生产中一个声明 `eu-ai-act-high-risk`、走 from-evidence 的执行（P2 清单中的模块；没有则用信贷试点若已在生产播种）→ guard 决策 DENY `unverified_input`、无审批行（更正第 7 条：这是预期）。
4. `governed` 档案与无档案策略的 guard golden 抽样结果不变。
5. aster-api 日志无 `aster_guard_rule_metadata_unavailable_total` 激增。

### Task E1：cloud 跟进（列车完成当天）

**Files:** `package.json` / `lsp-package.json` / lockfile → ts `1.6.0`（auto-upgrade 若已开 PR 则在其上追加提交）；`src/config/controls-registry.json` → 1.3.0。
- [ ] `control-registry-ts-parity.test.ts`（Task C1）与 `side-panel-decision.test.tsx` 在默认注册表下的新期望（高风险档案显示「须由已登记签发方签名」提示）PASS；`pnpm test:run` 全绿。
- [ ] 提交 `chore(deps): aster-lang-ts 1.6.0 与注册表副本 1.3.0（ADR 0046 §11.4 ⑦）`。

### 回滚

- **生产翻转回滚**：k3s 把 aster-api 镜像 pin 回退到 1.0.44 的 digest（用户提 revert PR，Argo 同步）。注册表随 core jar 回到 1.2.0；1.3.0 期间写下的事件 `verifiedInputsRequired: true` 只是审计快照，消费时按当前注册表重算 → 不再要求；期间已作出的 DENY 决策不变（链不可改）。不需要回滚引擎制品（已发布的 1.0.45 / ts 1.6.0 无人引用即无害）。
- **cloud**：E1 若已合并，revert 即可（只影响编辑器提示与标题）；PR 1–3 不随翻转回滚（对 1.2.0 无行为影响）。
- **签发失误**（错误公钥 / 密钥泄露）：设置页吊销对应 `issuer#kid`（aster-api 联动过期依赖它的待审批），换 kid 重新登记；Worker secret 按 runbook 轮换。
- **列车中途失败**：按 1.0.41 恢复流程（合入修复 → 删除相关仓 `v1.0.45` tag → `fromLayer` 续跑）；L4 之前失败对生产无影响。

---

## 6. 待用户确认（状态均为「**proposed — pending user confirmation**」）

确认方式：用户合并 PR 0（ADR §11.7）。第 2、3、8、9 条偏离 ADR 原文，PR 0 合并前不得合并任何实现 PR；其余各条确认前可按 proposed 开发，随 PR 0 定稿。

1. **编辑器 `inputRefs` 取浏览器 Core IR，不调 `/compile`**（更正第 2 条）——proposed：采用。代价：编辑器显示的是 ts 分析器结果，运行期判定用 Java 分析器重算；两者 parity 由 aster-lang-test 锁定。
2. **`catalog.piiMark` 推迟到 #460**（更正第 3 条）——proposed：推迟，第一期清单三项。备选是现在登记但不自动附，那是无消费方的死代码。
3. **证据包 v6 `factSources` 展示随 #460**（更正第 4 条）——proposed：不进 X4，也不是翻转门。
4. **cloud 签发方用一把平台级 Ed25519 密钥（Worker secret），由各租户在设置页显式启用**——proposed：采用。备选是每租户一把密钥（需新表与加密存储，ADR 未要求，且违背「不新增安全设计」的仓库准则）。`audience = tenantId` 已在签名内，跨租户重放不成立。
5. **试点密钥由 `pnpm fact-key:gen` 生成、seed 只登记公钥**（更正第 5 条）——proposed：采用。
6. **附事实前查 `listFactIssuers()`，不可用则不附；`invalid_fact` 不自动去掉事实重试**——proposed：采用。代价：每次 require_approval / escalate 登记多一次 HMAC GET（8 s 超时内）；自动重试会在翻转后用一次 DENY 消耗证据（§11.6 第 11 条），得不偿失。
7. **`hmac.userId` / `idp.businessRoles` 照样签**（aster-api 对 from-evidence 已能服务端判定这两项为 verified）——proposed：签，作用是让收据 `factSources` 记录 cloud 的来源；`principal.roles` 只在与真实业务角色完全相等时签。备选：只签 `fixture.creditPilot`。
8. **外部签发方事实的透传（执行 API 接受调用方带来的 `facts`）不在 X4**——proposed：不做；外部签发方在 X4 中只服务直接调 `POST /api/v1/guard/decisions` 的接入方。这意味着翻转后，生产里经 cloud 执行的高风险模块的 from-evidence 一律 DENY，除非输入只引用 `principal.*`（更正第 7 条）。
9. **翻转后生产信贷试点 DENY 是预期**（更正第 7 条）——proposed：接受，ADR 0046 D5 第 2 项原文要求 `fixture.creditPilot` 不进生产；生产演示改用本地栈。
10. **1.0.45 只含翻转与 runtime#77，core#245 另排**（更正第 8 条）——proposed：采用。
11. **ts 1.6.0（minor）**——proposed：采用，因为默认注册表变化会让既有源码出现新的 error 级诊断。
12. **aster-lang-test 的 `*` 语料按需改为 `governed`**（R4 判定规则）——proposed：采用；只在实测失败时改。
13. **§11.6 第 16 条（`Int.toString` 标量叶子豁免）由 P2 的 `whole-input-serialized` 命中数决定**——proposed：命中数为 0 则维持现状直接翻转；非 0 由用户在 G3 逐条裁定，豁免需要改两引擎，届时翻转推迟。
14. **个人租户也提供签发方设置子页**（ADR 0040 §11 D3「个人租户同样可配」）——proposed：做，子页 `/settings/fact-issuers` 与团队卡片共用 `FactIssuersPanel`。
15. **签发方吊销导致的过期不发 `guard.approval_decided` 通知**——proposed：X4 只在收件箱显示原因；aster-api DELETE 不返回受影响的审批，cloud 无从逐条通知，通知另案（需 aster-api 回传 `affectedDecisionIds`）。

---

## 7. 风险

- **评估事件 metadata 超 4096 被截断**（16b 冒烟时记下的未立案问题：`applicantId` / PII 键较长时）：截断后 from-evidence 解析失败 → 409 `evidence_not_approvable`，与事实无关但会在翻转后被误读成签发问题。缓解：P2 之前先把它立案；翻转后冒烟第 3 条若出现 409，按此排查。
- **harness 对 E708 的处理未知**（R4）：可能需要改语料，已给出判定规则。
- **cloud 签发方启用依赖租户管理员手动操作**：翻转后未启用的租户其 `principal.*` 事实不附，但这只影响 `principal.*` 引用（`context.*` 在生产本来就无法签，见待确认第 8 条）。
- **Workers 的 WebCrypto Ed25519 / `String.prototype.isWellFormed`**：现有 `ai-workspace-handoff.ts` 已在 Workers 上用 Ed25519；`isWellFormed` 若在 Workers 运行时缺失，Task C6 用等价的代理项正则替代（单测覆盖两条 `invalid_string` 向量）。
- **auto-upgrade 与 E1 撞车**：ts 1.6.0 发布后 auto-upgrade 会在次日开 PR；E1 在其上追加提交，不另开分支。

## 8. 自审记录

- Codex 审查（2026-11-13，68/100 退回）已处理：Critical「翻转门没有证明生产输入可验证」→ G3 改为逐模块可验证性台账，新增 G0；Critical「ADR 范围变更未经批准即执行」→ PR 0 升为全部实现 PR 的硬门，§6 写明确认方式；Important「业务角色来源未指明」→ 指名 `src/lib/business-roles.ts` 的 `loadBusinessRoles`；Important「未附事实即消耗证据的恢复路径无验收」→ G2 增加 DENY → 重新登记仍 DENY → 重新执行得 PENDING；Minor「须对真实 verifier 验收」→ C9 Step 5 与 G2 明确走真实 aster-api。

- 规格覆盖：ADR 0046 D5 第 2 项 (a) 设置页 → C3–C5；(b) fact-signer、来源函数清单、`sources`、验收四条 → C6–C8（`catalog.piiMark` 与 v6 展示按待确认 2、3 推迟）；(c) 试点测试签发方 → C9；D5 第 3 项（扫描、引擎列车、aster-api pin、cloud 副本）→ P1/P2、R1–R4、T1/T2、E1；§11.4 ⑥ 编辑器 `inputRefs` → C2，`RegistryProfile.requires.verifiedInputs` → C1；§11.6 第 11 条 → C8；第 16 条 → P2；第 17、19 条 → G4；ADR 0040 §11 D3 权限 / 错误码 → C3/C4，D4 第 4 项收件箱 → C5；列车计划阶段 C 第 3 条 X4 输入清单 → P1/P2；PR 16 → C1/C2；runtime#77 → T1。
- 类型一致性：`GuardFact`（C7）在 C8 的 `GuardAction.facts` 复用；`FactSigningKey`（C4）在 C7 / C8 / C9 复用；`isFactIssuerSegment`（C3）在 C4 的密钥校验复用；`requiresVerifiedInputs`（C1）在 C2 复用。
- 比例：代码块只给签名、测试断言与必须逐字的命令；函数体全部由签名与测试决定。
