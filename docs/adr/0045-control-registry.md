# ADR 0045：控制注册表 —— `@control` 受控词表、编译期提示与注册表驱动的对照表（AI 治理方向·子项目 5）

- 状态：**PROPOSED**（2026-10-09）
- 提出：2026-10-09；实施计划 `docs/superpowers/plans/2026-10-09-control-registry.md`（待写）
- 决策者：用户已拍板「真相源在 aster-lang-locales、走包分发；未登记给 W704 警告不阻断；两个编译器的治理检查器各实现并可注入词表；条目带证据来源声明、对照判定通用化；报告页渲染对照表但不加 ComplianceType 枚举；版本随证据包 manifest 固化」
- 相关：ADR 0039（§3 `@control` 键定义；§1 行 5）、ADR 0031（错误码单一来源）、ADR 0041（证据包、controls 进链）、ADR 0044（§3.1 静态对照表；§8/§10 推迟到本项目的事项）、ADR 0015（模块目录先例）

---

## 0. TL;DR

`@control("EU_AI_ACT:ART14")` 今天是自由文本：编译期只检查「恰好一个字符串」，内容没有任何校验，审计写入时超过 64 字符还会被静默截断；证据包的 Article 14 对照表把条款与控制键硬编码在 `article14.ts`。

本 ADR 引入一份版本化的控制注册表（框架 → 控制键 → 对照条款 + 证据来源声明），由 aster-lang-locales 构建并以 `@aster-cloud/controls` 包分发；两个编译器对未登记或形态非法的键给 W704 警告；aster-cloud 的对照表改为由注册表数据驱动并在报告页渲染；证据包 manifest 记录所用注册表版本。

## 1. 现状与问题

| 现状 | 位置 | 后果 |
|---|---|---|
| `@control` 只有 E702 形式检查（恰好一个字符串字面量） | aster-lang-ts `typecheck/governance.ts`、aster-lang-core `GovernanceChecker` | 拼错、小写、空串都能编译通过 |
| 控制串 >64 字符或 >16 条在审计写入时静默截断 | aster-api `EvaluationEvidence`、`GuardReceiptBuilder`（常量重复） | 作者不知情，链上证据与源码不一致 |
| 全仓只用过 5 个值：`EU_AI_ACT:ART14`、`GDPR:ART6`、`GDPR:ART17`、`GDPR:ART22`、`SOX:404` | 语料、测试、试点 | 词表规模小，适合一次登记 |
| 对照表条款、标题、控制键硬编码，只支持 Article 14 | aster-cloud `article14.ts` | 新增框架要改代码；ADR 0044 §10 已要求注册表驱动 |
| 报告页不渲染 `regulatoryMapping`，`ComplianceType` 无 `eu_ai_act` | aster-cloud reports | 审阅者只能下载 JSON 看对照 |
| LSP 无 `@control` 补全 | aster-lang-ts `lsp/completion.ts` | 作者需记忆键 |

## 2. 决策一：注册表数据与分发

### 2.1 数据

真相源 `aster-lang-locales/controls/registry.json`：

```json
{
  "version": "1.0.0",
  "frameworks": [{ "id": "EU_AI_ACT", "title": { "en": "EU AI Act", "zh": "欧盟人工智能法", "de": "EU-KI-Verordnung" } }],
  "controls": [{ "key": "EU_AI_ACT:ART14", "framework": "EU_AI_ACT", "article": "14", "title": { "en": "Human oversight", "zh": "人工监督", "de": "Menschliche Aufsicht" } }],
  "clauses": [{ "control": "EU_AI_ACT:ART14", "clause": "14(4)(d)", "title": { "en": "Decide not to use or override the output", "zh": "…", "de": "…" },
                "evidenced": ["reviewers.guard.rejected"], "partial": ["reviewers.guard.approved"] }]
}
```

- 键格式 `^[A-Z0-9_]+:[A-Z0-9_().]+$`，长度 ≤ 64（与 api 审计截断上限一致），同一注册表内唯一。
- 初始 controls：`EU_AI_ACT:ART14`、`GDPR:ART6`、`GDPR:ART17`、`GDPR:ART22`、`SOX:404`；初始 clauses：Article 14 九款，`evidenced`/`partial` 逐字复现 ADR 0044 §3.1 的判定（见 §4.1 表）。
- 证据来源只能取 §4.1 的固定词表；构建期校验。

### 2.2 构建与分发

- aster-lang-locales 新 Gradle 任务 `exportControls`：JSON Schema 校验、键唯一、clause 的 control 必须存在、来源词表合法；输出 `build/controls/registry.json`。构建失败即不发包。
- npm 包 `@aster-cloud/controls`（独立版本号，发版流程同 `@aster-cloud/ui-messages`），内容为 registry.json + TypeScript 类型 + `loadControlRegistry()`。
- aster-lang-core 在 classpath 内置副本 `governance/controls-registry.json`，aster-api 经 composite build 直接使用 core 的副本，不再另存一份；api 启动时加载并记录版本；与 `UiMessagesService` 容忍缺失不同，注册表缺失或不合法 → 启动失败（词表是治理检查的输入，不能静默为空）。
- 发布前的消费方式：aster-lang-ts（TS 数据模块 `src/governance/controls-registry.data.ts`）、aster-lang-core（classpath JSON）、aster-cloud（`src/config/controls-registry.json`）各持一份副本，每份配「与 aster-lang-locales 真相源深度相等」的守护测试（兄弟仓缺失时跳过，同 `UiMessagesClasspathSiblingParityTest` 先例）。`@aster-cloud/controls` 发布后，cloud 改为依赖 npm 包、删除副本。

## 3. 决策二：编译期 W704 与补全

- 错误码 `GOV_CONTROL_UNREGISTERED` = **W704**（ADR 0031：`error_codes.json`、TS `error_codes.ts`、Java `ErrorCode` 三处手工同步，由既有 parity 测试守护）。触发：键形态非法或不在注册表。消息含该键与注册表版本；帮助文案英文（与 W700–E703 同口径，三语覆盖留待错误码本地化机制）。定位：Core IR 注解无源位置，诊断落在规则（函数）范围上。
- aster-lang-ts：`checkGovernance(decls, opts?: { controls?: ControlRegistry })`，`ControlRegistry = { version: string; has(key: string): boolean }`，默认内置注册表；`CompileOptions` 增 `controls?: ControlRegistry` 并经 `compileAndTypecheck` 透传（`compile()` 本就不做类型检查，不受影响）。
- aster-lang-core：`GovernanceChecker` 同名注入点，默认 classpath 注册表。
- 永不阻断：W704 是警告；既有策略只多出提示。
- LSP：`@control("` 处补全注册表键（detail = 英文标题，documentation = 框架与三语标题）。悬停提示不在本 ADR（completion.ts 无悬停处理器，另行接入）。
- aster-api：`PolicyCompiler.compile` 目前只做 parse/lowering/StabilityEnforcement/RuleConflict 扫描，**不跑治理检查**，W700–E702 从未进入 `CompileResponse`。本 ADR 在编译端点追加 `GovernanceChecker.checkModule(core)` 扫描（含 W704），诊断与既有扫描同格式合并；`CompileResponse` 增 `controlsRegistryVersion`。这是行为变化：既有策略的 W700/E702 从此会在编辑器显示（均为既定语义，不阻断）。
- 诊断一致性：aster-lang-test 的 parity 只有 parse/ir/eval 三种模式，诊断黄金仅 TS 侧 tier3。本 ADR 不新增模式：同一源文 `governance-control-unregistered.aster` 进 tier3 诊断黄金（TS），并在 aster-lang-core `GovernanceCheckerTest` 以同一源文断言同一组码（Java），两侧各守一边。

## 4. 决策三：注册表驱动的对照表（aster-cloud）

### 4.1 证据来源词表与判定

| 来源 | 命中条件（对单条 entry） | 引用字段 |
|---|---|---|
| `control` | `controls` 含该 clause 的 control | controls |
| `outcome.pending` | outcome ∈ {REQUIRE_APPROVAL, ESCALATE} | outcome |
| `outcome.requireApproval` | outcome = REQUIRE_APPROVAL | outcome |
| `reviewers.any` | reviewers 非空（三来源） | reviewers |
| `reviewers.guard.any` | 存在 source=guard-approval 且 roleVerified | reviewers |
| `reviewers.guard.approved` / `reviewers.guard.rejected` | 上者且 outcome=APPROVED / REJECTED | reviewers |
| `reviewers.guard.two` | 同一条目内 guard-approval 且 roleVerified 的复核者 ≥ 2 | reviewers |
| `whatIf.comparable` | whatIf 非空且 targetOutcome 非空非 ERROR | whatIf |
| `rule.reason` | ruleId 非空且 reasonCodes 非空数组 | ruleId、reasonCodes（两条引用） |

判定：`evidenced` 列表中每个来源都至少命中一条 entry（跨条目）→ evidenced；否则 `partial` 列表任一来源命中 → partial；否则 none。证据为各命中来源的引用并集。需要「同一条目同时满足」的语义由单个来源内部表达（如 `reviewers.guard.two`），不在列表层组合。

Article 14 九款数据：14(1) evidenced=[control]；14(2) evidenced=[outcome.pending]；14(3) evidenced=[control, reviewers.any]、partial=[control, reviewers.any]；14(4)(a) partial=[whatIf.comparable]；14(4)(b) 空；14(4)(c) evidenced=[rule.reason]；14(4)(d) evidenced=[reviewers.guard.rejected]、partial=[reviewers.guard.approved]；14(4)(e) evidenced=[reviewers.guard.any]（guard 复核者只会出现在被阻断的条目上，故单来源即表达「阻断且被人决定」）、partial=[outcome.requireApproval]；14(5) evidenced=[reviewers.guard.two]。与 ADR 0044 修订后实现的差异：14(4)(e) 不再要求同一条目同时含 REQUIRE_APPROVAL；14(5) 新增 roleVerified 要求。试点预期表不变。

### 4.2 模块与 manifest

- `src/services/evidence/regulatory-mapping.ts` 取代 `article14.ts`：`mapRegulatory(entries, registry = defaultRegistry, locale = 'en')`。
- manifest `schemaVersion: '4'`，`regulatoryMapping: { registryVersion, frameworks: [{ framework, article, clauses: [{ clause, title: {en,zh,de}, status, evidence }] }] }`；现行 v3 类型复制为 `*V3` 冻结只读，`stored.ts` 识别 '4'。
- 只为 entries 中出现过的 control 所属框架生成；entries 为空 → 注册表中含 clause 的框架全部 none。
- `title` 为三语对象 `{ en, zh, de }` 原样写入（导出路径无 locale，UI 按当前 locale 取用）；不再有 `titleKey`。

## 5. 决策四：报告页渲染

- 报告列表行可展开「对照」：v4 包按框架渲染表格（条款 | 标题 | 状态徽标 | 证据条数，可展开 `executionId:field`）；v1–v3 显示「此版本不含对照」。
- 新 `GET /api/reports/{id}/mapping` 只返回 `regulatoryMapping`。
- 文案 `DEMO_SUPPLEMENT.evidenceExport.mapping.*` 四语（报告页既有命名空间是 `evidenceExport`）；不加 `ComplianceType` 枚举，仍存 `custom`。
- 信贷试点指南三语：对照表来源改为注册表版本驱动。

## 6. 错误处理与兼容

- 注册表 JSON 不合法 → 构建失败；api/core 加载失败 → 启动失败；cloud 找不到 clause 的框架不出现，不抛错。
- `@control` 语法与 IR 不变；W704 永不阻断；证据包 v1–v3 只读。
- run 脚本与 Playwright 的期望表改读 `frameworks[0].clauses`。

## 7. 发版依赖（不在本 ADR 内执行）

- 新包 `@aster-cloud/controls` 发布：需在 aster-lang-platform `release-plan.json` 登记并配置 npm trusted publisher；aster-lang-ts、aster-cloud 引入。
- aster-lang-ts 需发布含 W704（及 ADR 0044 §10 的 Verdict 内置）的版本，cloud 浏览器端才有提示。实施阶段用本地 `file:` 依赖验证并在 ADR 记差距。

## 8. 测试

- aster-lang-locales：schema 校验测试、构建产物快照。
- aster-lang-ts / aster-lang-core：已登记无告警、未登记 W704、形态非法 W704、注入空注册表全部 W704；aster-lang-test 诊断 parity。
- aster-cloud：`regulatory-mapping.test.ts`（九款 + 来源词表逐项 + 空 entries + 版本写入）；`stored.test.ts` v4；报告页组件测试；集成测试导出 v4；Playwright 试点规格断言 '4' 与九款。
- 栈验收：run 脚本 exit 0；报告页展示对照表。

## 9. 写回

- ADR 0039 §1 行 5 指向本 ADR。
- ADR 0044 §10：注册表驱动对照表、报告页渲染两条标记已做。

## 10. 后续（不在本 ADR）

- `@control` 悬停提示（LSP hover）。
- `@aster-cloud/controls` 发布后 cloud 改依赖 npm 包并删除副本。

- 租户自定义控制点（api 表 + 目录端点）。
- 控制点层级（框架 → 条款 → 子款）汇总。
- `eu_ai_act` 报告类型枚举与按类型过滤。
- `EvaluationEvidence` 与 `GuardReceiptBuilder` 重复的 16×64 常量收敛为一处。
