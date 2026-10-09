# ADR 0044：Annex III 信贷试点 —— 端到端跑通并产出对照 Article 14 的证据包（AI 治理方向·子项目 4）

- 状态：**PROPOSED**（2026-10-09）
- 提出：2026-10-09；实施计划 `docs/superpowers/plans/2026-10-09-credit-pilot.md`（待写）
- 决策者：用户已拍板「对照表内嵌证据包 manifest + 文档页，报告类型枚举待子项目 5 后再做；新写 Verdict 版信贷策略替换 seed 模板；脚本与 Playwright 两者都做；缺证条款逐条列出并标 none」
- 相关：ADR 0039（路线图 §1 行 4；§1 行 304 推迟的模板迁移）、ADR 0041（证据包 v2、收据查询）、ADR 0042（审批闭环、验证角色）、ADR 0043（四态回放；§10 逐项查询端点）

---

## 0. TL;DR

子项目 1–3 分别给了规则四态结论、动作护栏与审批闭环、证据链与四态回放。但没有一条信贷场景把它们串起来，也没有任何产物能回答「这些证据对应 EU AI Act Article 14 的哪一款」。

本 ADR 新写一条 Verdict 版信贷策略，用脚本与浏览器两条路跑通「执行 → 审批 → What-If → 导出」，并在证据包 manifest 内嵌一张只陈述事实的 Article 14 对照表：每款给 `evidenced / partial / none` 与指向的证据字段，缺证的款项如实标 none。

## 1. 现状与问题

| 现状 | 位置 | 后果 |
|---|---|---|
| 信贷素材（公开演示 `credit-risk-demo.ts`、seed 三个中文贷款模板、语料 `loan*.aster`）都返回文本结论，不用 Verdict | aster-cloud / aster-lang-test | 无法触发护栏、审批、四态回放 |
| "Article 14 / Annex III / human oversight" 只出现在 ADR 0039 与测试里的 `EU_AI_ACT:ART14` 控制串 | 两仓 | 无 UI、文档、i18n，审阅者看不到任何对照 |
| 证据包 v2 条目有 `controls[]`、`reviewers[]`（roleVerified）、收据，但无 What-If 关联 | `services/evidence/types.ts` | 14(4)(a) 的佐证（可比对）不在包内 |
| What-If 结论逐条存在 api `replay_batch_item`（按 `execution_id`），无查询端点 | ADR 0043 §10 | cloud 导出取不到 |
| 没有一条自动化流程覆盖 执行 → 审批 → 导出 | 两仓 | ADR 0041 §9 验收靠手工 SQL |
| `ComplianceType` 枚举无 `eu_ai_act`，导出固定 `custom` | `schema.ts:108` | 子项目 5 控制注册表未做，现在加类型会被返工 |

## 2. 决策一：试点策略 `aster.pilot.credit`

### 2.1 规则

输入记录 `Applicant { creditScore: Int, monthlyIncome: Decimal, monthlyDebt: Decimal, requestedAmount: Decimal, activeDefaults: Int }`（用违约笔数代替布尔，避开各语种布尔类型关键字）。策略只有一条入口规则 `decide`，因为 `POST /api/v1/policies/{id}/execute` 不接受 functionName。规则均 `@id`，均 `@control("EU_AI_ACT:ART14")`；产出自动拒绝的规则再加 `@control("GDPR:ART22")`。

| @id | 条件 | 结论 |
|---|---|---|
| `CP-001 active_default` | `activeDefaults ≥ 1` | `Verdict.deny("active_default")` |
| `CP-002 dti_too_high` | `monthlyDebt > monthlyIncome × 0.45` | `Verdict.deny("dti_exceeds_limit")` |
| `CP-003 large_amount_review` | `requestedAmount ≥ 50000` | `Verdict.require_approval("Credit Officer", "large_exposure")` |
| `CP-004 borderline_score` | `620 ≤ creditScore ≤ 679` | `Verdict.escalate("borderline_credit_score")` |
| `CP-005 default_allow` | 其余 | `Verdict.allow()` |

五个 `@id` 对应同一规则 `decide` 内的五个分支；`@id`/`@control` 标在规则上（`CP-DECIDE`），分支由 reason 区分。判定顺序即表序（拒绝优先于审批，审批优先于升级）。v2 仅把 CP-003 阈值提到 80 000，用于 What-If 制造 `REQUIRE_APPROVAL → ALLOW` 转移。

### 2.2 落点

- `aster-lang-test/corpus/tier1-equivalence/policies/credit-pilot.aster` + `inputs/credit-pilot.cases.json`：四种结论各一组用例，走三引擎一致性。
- aster-cloud 本地化校验：`src/__tests__/credit-pilot-locales.test.ts` 用 en/zh/de 三份源文各编译一次并断言同一输入得到同一 Verdict（zh 源即 seed 模板内容）。
- `seed-usability-tenant.ts`：`tpl-blank-loan` 保留；`tpl-half-loan`（只含 CP-001/CP-005）、`tpl-complete-loan`（全部五条）迁为中文 Verdict 版。这兑现 ADR 0039 行 304 的欠账。用研模板只用于编辑器任务，不建 PolicyVersion 行。

## 3. 决策二：Article 14 对照内嵌证据包

### 3.1 判定模块 `src/services/evidence/article14.ts`

纯函数 `mapArticle14(entries: EvidenceEntry[]): Article14Mapping`。静态表 `ARTICLE14_CLAUSES` 固定覆盖 14(1)、14(2)、14(3)、14(4)(a)–(e)、14(5)；每款 `{ clause, title, status, evidence: EvidenceRef[] }`，`EvidenceRef = { executionId, field }`，`status ∈ evidenced | partial | none`。

| 款 | 判定（只看 entries 字段） | 状态 |
|---|---|---|
| 14(1) 监督设计 | 任一条目 `controls` 含 `EU_AI_ACT:ART14` | evidenced，否则 none |
| 14(2) 风险配置 | 存在 `outcome ∈ {REQUIRE_APPROVAL, ESCALATE}` 条目 | evidenced，否则 none |
| 14(3) 监督措施内建 | 同 14(1) 且存在 `reviewers.length > 0` | evidenced；仅满足其一 partial |
| 14(4)(a) 理解能力 | 存在 `whatIf` 非 null 条目 | partial（可比对不等于理解），否则 none |
| 14(4)(b) 自动化偏见 | 无可证字段 | 恒 none |
| 14(4)(c) 解读输出 | `ruleId` 与 `reasonCodes` 均非空 | evidenced，否则 none |
| 14(4)(d) 否决/不使用 | `reviewers` 含 `roleVerified: true` 且 `outcome = REJECTED` | evidenced；只有 APPROVED partial；无 none |
| 14(4)(e) 干预/停止 | `outcome = REQUIRE_APPROVAL` 且 `reviewers.length > 0` | evidenced；有阻断无复核 partial |
| 14(5) 双人验证 | 任一条目 `reviewers.length ≥ 2` | evidenced，否则 none |

entries 为空：全部 none。不产生评分、不写建议文案。

### 3.2 manifest v3

`schemaVersion: '3'`，新增 `regulatoryMapping: { framework: 'EU_AI_ACT', article: '14', clauses }`；`notes.whatIfUnavailable: number`。v2 类型冻结为只读（同 v1 做法），已存 v1/v2 包按原样下载。mapping 与 notes 不进 `bundleHash`（manifest 从不进）。

### 3.3 条目新增 `whatIf`

`EvidenceEntry.whatIf: { batchId, baseOutcome, targetOutcome, baseLegacy } | null`，来自 api 的最近一次回放。取不到（超时/非 2xx）→ 所有条目 null，`notes.whatIfUnavailable` 计数，导出照常完成。`bundleHash` 覆盖 entries，因此 v3 的哈希包含 `whatIf`；这与收据引用在 entries 内的既有做法一致，审计方按 notes.verification 重算即可。

## 4. 决策三：aster-api 回放逐项查询端点

`GET /api/v1/replay/items?executionIds=<csv>`，同 `/api/v1/audit/receipts` 先例：上限复用 `AuditLog.MAX_LOOKUP_IDS`，超限 400 `too_many_ids`；双通道（内部 HMAC v3 或 Bearer），`InternalCallerFilter` 与 `ApiKeyAuthFilter` 旁路列表各加一项。

- 租户隔离：`replay_batch_item` 无租户列，经 `replay_batch.tenant_id` 过滤，非本租户 id 静默不返回。
- 多次回放取 `replay_batch.created_at` 最新一条；无回放的 id 不出现在结果里。
- 响应 `{ items: [{ executionId, batchId, baseOutcome, targetOutcome, baseLegacy }] }`，只返回结论码不返回结论体（ADR 0043 §2 隐私规则）。
- 了结 ADR 0043 §10 第一条。

## 5. 决策四：跑通产物

| 产物 | 位置 | 职责 |
|---|---|---|
| `pnpm seed:credit-pilot` | `aster-cloud/src/scripts/seed-credit-pilot.ts` | 幂等播种团队 `credit-pilot`（plan team）、`cp-owner`、`cp-officer`（businessRole "Credit Officer"）、`cp-analyst`，策略 `pol-credit-pilot`（content=v2，version=2）及 PolicyVersion 行 `pv-credit-pilot-1/2`（APPROVED，含 `sourceToolchainId`），cp-analyst 的 team API key（明文打印一次），推送 team key snapshot |
| `scripts/run-credit-pilot.ts` | aster-cloud | 纯 HTTP：四组 fixture 各执行一次 → cp-officer 批准 require_approval 那条 → v1→v2 What-If → 导出到 `./credit-pilot-bundle.json` → 打印对照状态；任一款与预期表不符退出码非零 |
| `credit-pilot.spec.ts` | `src/__tests__/e2e-browser/local-stack/` | 串行：cp-analyst 执行大额申请 → 日志页 guard 链接 → cp-officer `/approvals` 批准 → What-If 面板显示 REQUIRE_APPROVAL→ALLOW → 报告页导出 → 断言 14(4)(d)/(e) evidenced、14(4)(b) none |
| 文档 | `src/app/[locale]/docs/guides/credit-pilot/{page.tsx,en,zh,de}.mdx` + `sidebar.ts` 新节 + `page-actions.ts` 登记 + `DEMO_SUPPLEMENT.docs.sidebar.guides.*` 四语 | 试点流程、对照表含义、每款证据来源、用 `GET /api/v1/audit/receipts` 独立核验。标签走本地补充层，不动 ui-messages 包 |

预期表（run 脚本与 spec 共用）：14(1)/14(2)/14(3)/14(4)(c)/14(4)(e) evidenced；14(4)(a)/14(4)(d) partial（脚本只做 approve）；14(4)(b)/14(5) none。

## 6. 错误处理

- 对照判定纯函数，不抛错。
- 回放查询失败只记 `whatIfUnavailable`，14(4)(a) 退化为 none。
- 端点：超限 400；非本租户静默。
- seed/run 脚本：失败即退出并打印步骤名，不回滚；seed 幂等可重跑。

## 7. 测试

- aster-lang-test：`credit-pilot` 三引擎一致性，四种结论用例。
- aster-api：端点租户隔离、上限、无回放为空、最新一条优先；NonDeterminism 允许表按需追加。
- aster-cloud 单测：`article14.test.ts` 每款各一组 evidenced/partial/none；`bundle.test.ts` v3 快照；v1/v2 存量包读取回归；locales 一致性测试。
- 集成：`evidence-export.integration.test.ts` 加 whatIf 关联与 `whatIfUnavailable` 分支。
- 本地栈验收：run 脚本退出码 0 + Playwright spec 通过。

## 8. 不做

- `eu_ai_act` 报告类型与独立报告页：子项目 5 控制注册表之后再做。
- PDF/HTML 渲染。
- 公开演示 `credit-risk-demo.ts` 迁 Verdict。
- 对照表给评分或合规建议。

## 9. 写回

- ADR 0039 §1 行 4 指向本 ADR；行 304 模板迁移标记已做。
- ADR 0043 §10 第一条标记已做并指向 §4。

## 10. 后续（不在本 ADR）

- 对照表改由子项目 5 控制注册表驱动，替换 §3.1 静态表。
- 14(4)(b)、14(5) 的证据来源需产品层能力（偏见监测、双人审批）。
- 公开演示页切到同一策略源。
