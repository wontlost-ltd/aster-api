# ADR 0046：Governance Profile 与 When/Otherwise 语法糖（AI 治理方向·子项目 6a）

- 状态：**ACCEPTED**（2026-10-10；验收记录见 §9.3）
- 实施计划：`docs/superpowers/plans/2026-10-10-governance-profile.md`
- 决策者：用户已拍板「子项目 6 拆为 6a（Profile + 语法糖）与 6b（Cedar 目标），先做 6a；Profile 写在策略源码里、提升治理严格度；Profile 定义作为数据放在控制注册表旁；语法糖为规则内的语句级 When/Otherwise；违反 Profile 的诊断在所有通道都是错误、会阻止保存」
- 相关：ADR 0039（§1 行 6；§2.1「语法糖降糖为同一个内置模块调用」）、ADR 0045（控制注册表、W704、编译通道治理诊断降为 warning 的裁决）、ADR 0031（错误码单一来源）、ADR 0016（双引擎 parity）

---

## 0. TL;DR

子项目 1–5 让策略能给出 Verdict、带 `@id`/`@control`、受控词表校验，但这些都只是**警告**：作者可以忽略。
本 ADR 让一个模块用一行 `Profile <id>.` 声明它受某个治理档案约束；档案定义放在控制注册表里（数据），
声明了档案的模块，违反要求即编译错误，aster-cloud 现有保存门随之拒绝保存。未声明档案的模块行为完全不变。
同时引入 `When … / Otherwise …` 语句级简写，降糖为既有的 `If … Return Verdict.*(…)`，IR 与运行结果与长写法逐字一致。

Cedar 编译目标拆为子项目 6b，另立 ADR。

## 1. 现状与问题

| 现状 | 位置 | 后果 |
|---|---|---|
| W700（缺 `@id`）、W704（未登记控制点）只是警告，编译通道还把 E701/E702 也降为警告（ADR 0045 §9.1） | 两个编译器的治理检查器、aster-api `GovernanceDiagnostics` | 受监管场景无法强制「每条决策规则可追溯、可对照」 |
| 没有任何「档案/严格度」概念；唯一近似物是 aster-api 稳定性检查的受监管租户 CSV 配置 | `StabilityEnforcement` | 严格度不随策略文本走，编辑器与浏览器端无从知晓 |
| Verdict 规则只能写成嵌套 `If … Return Verdict.*` | 语料与模板 | 决策表式策略冗长；ADR 0039 §2.1 承诺的语法糖未落地 |
| `CoreModel.Module` 无模块级元数据槽位 | aster-lang-core、aster-lang-ts | 需要新增一个可选字段 |

## 2. 决策一：Profile 声明

```
Module aster.pilot.credit.
Profile "eu-ai-act-high-risk".
```

- 可选，紧跟 `Module` 行之后，至多一条。档案 id 用**字符串字面量**书写（两个引擎的词法都把 `-` 切成 MINUS，裸标识符无法承载连字符），形如 `^[a-z][a-z0-9-]{0,63}$`，不合形态为解析错误。
- 新 `SemanticTokenKind.PROFILE`：en `Profile`、zh `档案`、de `Profil`、hi `प्रोफ़ाइल`。Java 语法不新增硬词法记号：`module` 规则在 `moduleHeader` 之后接受 `TYPE_IDENT STRING_LITERAL DOT` 并在 AstBuilder 校验其文本为 `Profile`（经 Canonicalizer 规范化后），从而不夺走用户的 `profile` 参数名或 `Profile` 类型名。
- IR：Java `CoreModel.Module` 与 TS `Core.Module` 增可选字段 `profile`，仅在存在时序列化。未声明档案的模块 IR 与今天逐字节相同，既有 parity 语料不受影响。

## 3. 决策二：档案定义作为注册表数据

在 aster-lang-locales `controls/registry.json` 增 `profiles` 数组，由同一个 `exportControls` 任务校验（id 形态、唯一、`frameworks` 中每个值是已登记框架）：

```json
{ "id": "eu-ai-act-high-risk",
  "title": { "en": "EU AI Act high-risk system", "zh": "欧盟人工智能法高风险系统", "de": "Hochrisiko-KI-System nach EU-KI-Verordnung" },
  "requires": { "ruleId": true, "registeredControls": true, "frameworks": ["EU_AI_ACT"] } }
```

- 初始档案：`eu-ai-act-high-risk`（如上）与 `governed`（`ruleId` + `registeredControls`，无框架要求）。
- 注册表版本升到 `1.1.0`；aster-lang-core、aster-lang-ts、aster-cloud 三份副本与其守护测试同步（ADR 0045 §2.2）。

## 4. 决策三：档案检查（两个编译器的治理检查器）

只对声明了档案的模块中的 Verdict 规则生效：

| 码 | 名称 | 触发 |
|---|---|---|
| E705 | `GOV_PROFILE_UNKNOWN` | 声明的档案不在注册表中（定位在模块起始，即 `Module` 行） |
| E706 | `GOV_PROFILE_VIOLATION` | `requires.ruleId` 且规则缺 `@id`；`requires.registeredControls` 且规则含未登记或形态非法的 `@control`；`requires.frameworks` 非空且规则没有任何一个属于这些框架的已登记 `@control`。消息参数含档案 id、规则名、被违反的要求 |
| E707 | `GOV_CHECK_UNAVAILABLE` | 不由检查器报出：检查器自身抛运行时异常、无法完成声明了档案的模块的检查时，由 aster-api 编译通道合成（见下文「检查器失败的取舍」）。消息参数含模块名与档案 id |

- 同一规则同一要求、同一键只报一次：`missing @id` 与框架要求每条规则至多一条，`unregistered control {control}` 按每个不同的控制点键各报一条。W700/W704 照常报出（警告），E706 是档案层面的额外错误。
- aster-api 编译通道：E705/E706 **保持 error 严重级**（其余治理码仍按 ADR 0045 §9.1 降为 warning）。编译 `success` 仍为 true；aster-cloud 现有保存门按 `severity === 'error'` 拒绝保存，因此声明了档案且不合规的模块无法保存、建版或审批。未声明档案的模块不受影响，所以今天能保存的策略明天仍能保存。
- 检查器失败的取舍：治理检查器抛运行时异常时，编译 `success` 仍为 true，aster-api 以 error 级别记录该异常。未声明档案的模块沿用 ADR 0045 的降级，结果中不带治理诊断；声明了档案的模块改为得到一条无源码位置（1:1）的 E707 `GOV_CHECK_UNAVAILABLE` error（`blocking` 仍为 false），消息取 core 模板，说明治理检查未能对该模块与档案执行，档案保存门因此拒绝保存（fail-closed）。用独立的 E707 而不是 E706，是因为基础设施故障不能被读成策略违规：作者无需也无法改源码，aster-cloud 把 E707 映射为可重试的 503 `compile_unavailable`（与下一条不可达时的处理一致），而不是 400 `compile_error`。
- 编译端点内部故障：`POST /api/v1/policies/compile` 在编译通道之外抛出意外异常时，返回 `success:false` 与一条 1:1 error 诊断，诊断码为 `COMPILE_INTERNAL_ERROR`（api 级常量 `CompileDiagnostic.INTERNAL_FAILURE_CODE`；core 码表没有通用的编译器内部故障码，故不占用 E 段编号）。它表示编译没做成，而不是源码有错：aster-cloud 保存门与 E707 同样把它视为编译不可用，声明了档案的源码返回可重试的 503 `compile_unavailable`，未声明档案的源码沿用不可达时的 fail-open 放行。`success:false` 却没有任何 error 诊断的响应按同一规则处理，作为兜底。
- 编译检查不可达时的取舍：aster-cloud 保存门调用编译端点遇到超时（408）、5xx 或网络失败时，若源码声明了档案（保守识别：字符串字面量与注释之外出现档案关键字并后接字符串字面量，不要求位置），则与上游限流（429）一样拒绝保存并返回 503 `compile_unavailable` 与 `Retry-After`；未声明档案的源码仍照旧放行。

## 5. 决策四：When / Otherwise 语法糖

```
Rule decide given applicant as Applicant, produce Verdict:
  When applicant.activeDefaults at least 1, deny "active_default".
  When applicant.requestedAmount at least 50000m, require approval by "Credit Officer" because "large_exposure".
  When applicant.creditScore at most 679, escalate "borderline_credit_score".
  Otherwise allow.
```

- 语句：`When <cond>, <outcome>.` 与 `Otherwise <outcome>.`；`<outcome>` ∈ `allow` | `deny <text>` | `escalate <text>` | `require approval by <text> because <text>`。
- `When` / `Otherwise` **复用既有** `WHEN`（Match 分支词）与 `OTHERWISE` 词法项：en When/Otherwise、zh 当/否则、de bei/sonst、hi जब/अन्यथा。语句级 `When` 只出现在规则体语句位置，Match 分支内的 `When` 语义不变；`If` 块后紧随的 `Otherwise` 仅当其后是结论词时才作语法糖（TS 解析器加前瞻，Java 语法本就要求 else 分支后换行）。
- 新 `SemanticTokenKind`：`ALLOW`、`DENY`、`ESCALATE`、`REQUIRE_APPROVAL_BY`、`BECAUSE`，每项是一个连续短语：

  | kind | en | zh | de | hi |
  |---|---|---|---|---|
  | ALLOW | allow | 允许 | erlauben | अनुमति |
  | DENY | deny | 拒绝 | ablehnen | अस्वीकार |
  | ESCALATE | escalate | 升级 | eskalieren | आगे बढ़ाएँ |
  | REQUIRE_APPROVAL_BY | require approval by | 需审批人 | Genehmigung durch | अनुमोदक |
  | BECAUSE | because | 因为 | weil | क्योंकि |

  这些词在 Java 侧经 Canonicalizer 规范化为英文后，作为软关键字（`structKeywordName`）出现在语法糖语句中，不新增硬词法记号，因而 `Verdict.allow()` 等成员名不受影响。新 kind 与 PROFILE 在 Java `OPTIONAL_KINDS` 与 TS `CI_OPTIONAL_KINDS` 中保留到发版完成（ADR 0029 先例）。hi 用词为暂定，待母语审校。
- 降糖在两个前端的解析/AST 构建阶段完成：`When` → `If <cond>: Return Verdict.<op>(…)`；`Otherwise` → `Return Verdict.<op>(…)`。不新增 IR 节点、不改求值器；trace、证据与长写法一致。
- `Otherwise` 必须是所在块的最后一条语句，否则为**解析错误**（消息 `Otherwise must be the last statement of its block`，无错误码）：两个引擎都没有带码的解析期诊断通道，原设想给它的 E707 撤销（E707 后改作 `GOV_CHECK_UNAVAILABLE`，见 §4）。参数的类型与个数检查沿用降糖后对 `Verdict.*` 的既有检查（E703）。
- 语法糖与长写法的 IR 在去除 origin 后相等（语法糖把 If 与 Return 放在同一行，origin 行号不同）；parity 对比以此为准。
- TS 格式化器从 AST 打印：保留 `Profile` 行，语法糖按长写法输出（语义不变）——已知限制，见 §10。

## 6. 决策五：向 aster-api、aster-cloud 与证据传播

- aster-api 编译响应带 `profile`（模块声明的档案 id，无则省略）。
- 评估证据：执行时从模块 IR 读出 `profile`，与 `ruleId`/`controls` 一样写入评估证据与执行行；证据包条目带 `profile`。manifest 升 `schemaVersion: '5'`，新增 `profilesUsed: [{ id, title }]` 与所用注册表版本；v1–v4 只读。
- aster-cloud：编辑器与策略详情页显示档案徽标；报告页对照视图列出所用档案。编辑器徽标在实施计划中漏列，于 Task 8 修复轮补上：编辑器侧栏 Decision 页签从浏览器端 Core IR 读取 `profile`，显示「档案 + 注册表标题」；已安装的 aster-lang-ts 尚不产出 `profile` 时不显示（见 §7）。详情页徽标直接读取版本行的 `PolicyVersion.profile`（cloud 迁移 0054，可空）：该列在保存版本时由保存门的编译结果写入，查看详情时不再调用编译端点；旧版本与未声明档案的版本为 NULL（不回填），不显示徽标。
- 信贷试点：`pol-credit-pilot` 增 v3（`pv-credit-pilot-3`，成为在线版本），即 v1 的五个分支用语法糖逐条改写并声明 `Profile "eu-ai-act-high-risk".`（§5 示例仅为示意，不得省略 DTI 分支或改变边界）；What-If 仍对比 v1→v2。试点脚本仍须得到与 ADR 0044 相同的九款状态。
- 档案只沿评估路径（`RuleMetadata` → 三条评估结果 → `EvaluationEvidence` 与 `EvaluationResponse`）传播；guard 决策路径不在本 ADR。

## 7. 兼容与发版

- 未声明档案、未使用语法糖的模块：语法、IR、诊断、保存行为均不变。
- 新关键字可能与用户标识符冲突：实施时对语料、模板、示例做冲突扫描；冲突处理按既有软关键字规则。
- 与子项目 5 相同：浏览器端编辑器要等 aster-lang-ts 与 aster-lang-locales（含新词法与注册表）发布后才能看到；aster-api 编译通道经 composite build 本地即可用。发布由用户决定。
- aster-cloud 当前锁定的 aster-lang-ts 为 1.0.28，解析不了 `Profile` 与语法糖；编辑器在客户端编译有错误时禁用保存，所以在 cloud 于发版后升级 aster-lang-ts 依赖之前，声明了档案或使用语法糖的模块无法从 UI 保存。服务端保存路径、API 与试点种子数据现在就可用。
- aster-api 的 Redis 缓存键改为 `<根>:<缓存名>:<键>`（C8）后，首次部署不再读取旧前缀下的条目：Redis 缓存冷启动一次，旧键随 30 分钟 TTL 自然过期，无需手工清理。

## 8. 测试

- 两个编译器：E705/E706 与「Otherwise 非末尾」解析错误逐条用例；语法糖与长写法 IR 相等；未声明档案的模块零新增诊断。
- aster-lang-test：一对「语法糖/长写法」样本，parse/ir/eval 三种 parity 全部一致；一个声明档案的样本。
- aster-lang-locales：`profiles` 校验的正负夹具。
- aster-api：编译通道 E705/E706 为 error 而其余治理码仍为 warning；证据带 `profile`。
- aster-cloud：声明档案的不合规模块保存被拒（400）；证据包 v5；报告页显示档案。
- 栈验收：试点 v3（语法糖 + 档案）跑通，九款不变；不合规改动无法保存。

## 9. 写回与验收

- ADR 0039 §1 行 6 已回写为「6a → ADR 0046；6b 待定」（6b：Cedar 编译目标，另立 ADR）。

### 9.1 最终用词

六个新 `SemanticTokenKind` 按下表落地（aster-lang-core 内置 en、aster-lang-locales en/zh/de、aster-lang-hi），均列入 Java `OPTIONAL_KINDS` 与 TS `CI_OPTIONAL_KINDS`，发版列车完成后移出。

**转正（2026-10-12）**：1.0.32 发版列车完成（四语 first-party 包均含六词、消费方均 pin 1.0.32）后，六个 kind 已移出 Java `OPTIONAL_KINDS` 与 TS `CI_OPTIONAL_KINDS`，语言包缺词即错误。

用词：

| kind | en | zh | de | hi |
|---|---|---|---|---|
| PROFILE | Profile | 档案 | Profil | प्रोफ़ाइल |
| ALLOW | allow | 允许 | erlauben | अनुमति |
| DENY | deny | 拒绝 | ablehnen | अस्वीकार |
| ESCALATE | escalate | 升级 | eskalieren | आगे बढ़ाएँ |
| REQUIRE_APPROVAL_BY | require approval by | 需审批人 | Genehmigung durch | अनुमोदक |
| BECAUSE | because | 因为 | weil | क्योंकि |

`When` / `Otherwise` 复用既有 WHEN / OTHERWISE（en When/Otherwise、zh 当/否则、de bei/sonst、hi जब/अन्यथा），未新增 kind。hi 用词仍为暂定，待母语审校。

### 9.2 冲突扫描结论

扫描范围：aster-lang-test 全部 `.aster`（含 packages/js 副本）、aster-lang-ts 测试夹具（`.aster` 与 `.ts` 内联源）、aster-cloud `src/data` 与 `src/config`。拉丁词按词边界、大小写不敏感匹配，zh/hi 按子串匹配。

- 编译源码中没有本地化新词命中真实标识符。en `allow/deny/escalate` 只作成员名 `Verdict.allow/deny/escalate(…)` 与注释出现；`because`、de `weil` 只在字符串字面量或散文中；de 其余新词、hi 六词、zh `档案/允许/升级/需审批人` 零命中。
- en 小写 `profile` 在 8 个语料文件中作参数/变量/字段名。Java 侧 `Profile` 不是硬词法记号（`TYPE_IDENT` 文本校验，§2），TS 侧把 PROFILE/ALLOW/DENY/ESCALATE/BECAUSE 加入 `apply` 目标软关键词，`apply profile to x` 等行为不变。
- 规范化等价探针：385 个源码（en 379 / zh 5 / de 1，含信贷试点三语源码）分别以带与不带六个新 kind 的词表经 Java Canonicalizer 规范化，385/385 输出逐字相同；TS 对 386 个输入编译，新旧构建 0 处差异。
- 已知影响：zh/de/hi 源码若把新词用作标识符，会像既有关键词一样被规范化为英文词（如 zh `令 允许 为 真。` → `Let allow 为 真.`）。语料中无此用法；aster-cloud `src/data/cnl-syntax-reference.ts` 中一条仅供显示、从不编译的 zh 示例以 `拒绝` 作字段名，照抄会受影响。
- 词表内部：六个新词与各语言既有关键词/别名不存在相同或包含关系。

### 9.3 栈验收记录（2026-10-10，本地 podman 全栈）

分支 `adr0046-governance-profile`：aster-lang-locales 2a7a5a6、aster-lang-hi ed398b6、aster-lang-core 9ac7df9、aster-lang-ts 5752c01、aster-lang-test e4b19fe、aster-api 2a4b032、aster-cloud 4c954f04（含验收中发现的修复，见下）。aster-api 经 composite build 使用本地 core/locales；`~/.m2` 中 locales/hi 1.0.30 为本地构建的词法包。

| 步骤 | 命令/路径 | 结果 |
|---|---|---|
| 重建栈 | `./gradlew --offline quarkusBuild -x test` → `podman build -f Dockerfile.jvm` → 重建 `aster-api`；容器内 `pnpm db:migrate`（55 条迁移，含 0053 `Execution.profile`）；`podman restart aster-cloud`；`seed-credit-pilot.ts` | 全部成功；`pol-credit-pilot` 有 v1/v2/v3 |
| 编译端点 | `POST /api/v1/policies/compile` | `Profile "nope".` → E705 error（registry 1.1.0）；`Profile "governed".` 缺 `@id` → W700 warning + E706 error `missing @id`；合规模块诊断为空；三者 `success: true`、响应带 `profile` |
| 经 cloud 保存 | `POST /api/policies`（会话 cookie） | 声明 `eu-ai-act-high-risk` 且缺 `@id`/框架控制点 → 400 `compile_error`；去掉 `Profile` 行的同一模块 → 201；`PUT /api/policies/:id` 改为违规源码 → 400；cloud `/api/policies/compile` 返回 W700 + 两条 E706（`missing @id`、`no registered control from framework EU_AI_ACT`） |
| 信贷试点 | `scripts/run-credit-pilot.ts` | exit 0；九款状态全部符合期望；证据包 `schemaVersion '5'`、`profilesUsed: [{ id: 'eu-ai-act-high-risk', … }]`、`regulatoryMapping.registryVersion '1.1.0'`；v3 执行条目 `profile = 'eu-ai-act-high-risk'`，早先 v1 执行条目为 `null` |
| 浏览器 E2E | `playwright test src/__tests__/e2e-browser/local-stack --project=chromium-desktop --workers=1` | 9/9 通过（含信贷试点 v5 证据包与档案核对） |
| locales / hi | `./gradlew --offline check` | 均 BUILD SUCCESSFUL |
| core | `./gradlew --offline cleanTest test` | 1806 个，0 失败，7 跳过 |
| ts | `pnpm test` | 2052 个，0 失败 |
| parity | `parity-tier1.mjs --mode=parse/ir/eval` | parse 228/228、ir 228/228 逐字相同、eval 735/735 一致 |
| api | `:replay:test :test`（sdd-pg/sdd-redis） | 1788 个，失败仅为既有基线 `PolicyGraphQLResourceTest.testBatchEvaluateLargeVolume`、`PrismaDatabaseE2ETest`（各含一次重试）；14 跳过；@QuarkusTest 实际执行 553 个；replay 98/98 |
| cloud | tsc、lint、`vitest run`、check:locales:strict、check:message-keys、routes:check、evidence-export 集成测试 | tsc/lint 0 error（3 个既有 warning）；vitest 7083 通过、31 跳过；三项检查 exit 0；集成 18/18 |

验收中发现并修复（aster-cloud 4c954f04，独立提交）：Node 运行时（next dev / next start / docker）下 `getDb()` 单例的连接池沿用 Workers 路径的 `max=1`，`POST/PUT /api/policies` 在 `db.transaction` 内调用的 `createVersion` 经全局 `db` 写安全事件与失效缓存，事务占住唯一连接后这些查询永远排队，请求挂死并拖住整个进程的数据库访问。单例改为 `max=10`，Workers 路径不变；附单测。

环境记录：

- aster-api 默认按来源 IP 限流（每分钟 60 次），本地栈中 cloud 到 api 的服务端调用全部来自同一容器 IP，浏览器 E2E 连跑时出现 429（日志 `限流拒绝 identifier=rest:ip:<cloud 容器>`），表现为审阅人、收据或 What-If 结果缺失。以 `ASTER_RATELIMIT_REST_MAX=600` 重建 api 容器后 9/9 通过，随后按配方恢复默认值。
- 上一条的 api 侧处理（终审 §4 (b)）：`RateLimitFilter` 对携带内部调用头的请求复用 `InternalCallerFilter` 的头预检与 canonical v3 验签（不消费 nonce），签名有效时按 `rest:internal:cloud-bff:<X-Tenant-Id>` 分桶，额度为 `aster.ratelimit.internal.max-requests`（环境变量 `ASTER_RATELIMIT_INTERNAL_MAX`，默认每窗口 600 次，窗口沿用 `aster.ratelimit.rest.window-seconds`）；验签不通过或未带内部头的请求仍按 IP（或已建立的租户上下文）计入 `aster.ratelimit.rest.max-requests`。限流 filter 早于 `InternalCallerFilter` 执行，选择就地验签而不调换顺序，是为了让未认证洪泛仍在最前面被拦下。cloud 对除 `/q/` 外的全部 PolicyApiClient 调用签 canonical v3 内部头（D9，aster-cloud a6033721），`/api/v1/policies/compile` 等服务端调用因此同样计入租户桶；cloud 缺签名密钥时不签并告警一次，请求回落到 IP 桶（api 对非受保护路径只用签名来分桶，不改变鉴权）。签名在 5 分钟时间戳窗口内可被重放来消耗该租户的内部额度，受保护路径上的重放仍由 `InternalCallerFilter` 的 nonce 去重拒绝。
- 栈夹具会老化：guard 待审批 24 小时后过期，`pol-adr0041-team` 种子执行的审批已过期；验收时将该条审批恢复为 PENDING，并用 aster-guard `scripts/e2e-local.mjs` 补一条 `e2e-` 待审批。

## 10. 后续（不在本 ADR）

- TS 格式化器保留语法糖原样输出。
- 发版：新词法 kind 要走 ADR 0029 发版列车（core → locales/hi → ts）；本地验证经 mavenLocal 构建的词法包完成。
- 子项目 6b：Cedar 编译目标（REQUIRE_APPROVAL/ESCALATE 的表达方式、可翻译子集与 schema 生成）。
- 租户级「强制档案」（租户要求其所有策略必须声明某档案）。
- 档案要求的扩展（如强制 `@reason`、禁止某些结论类型）。

## 11. 2026-11 修订：档案可要求高风险规则只引用已验证字段（R62）

状态：**PROPOSED**（2026-11-07 起草，待用户评审）。本节定稿前不得据此合并实现；定稿后与正文冲突时以本节为准。
相关：wontlost-ltd/aster-api#461；ADR 0040 §11 R60（来源模型与执行点）、ADR 0041 §11 R61（收据键）、ADR 0045 §2.2（注册表三份副本与守护测试）、ADR 0031（错误码单一来源）。

### 11.1 现状

- 档案要求只有三项：`ruleId / registeredControls / frameworks`（§3；`aster-lang-locales/controls/registry.json:26-34`，core `ControlRegistry.Profile` 记录 `ControlRegistry.java:44`，ts `governance.ts:140`）。检查对象是注解，不涉及规则读了哪些输入。
- 两个编译器的治理检查器（core `GovernanceChecker.java:40-117`、ts `governance.ts:100-198`）按规则报 E706；错误码 E701–E707 已用，E708–E719 空闲，E720–E724 为 Cedar（ADR 0047）。
- aster-api 从模块 IR 读规则元数据：`replay/.../governance/RuleMetadata.java:11` `RuleMetadata(ruleId, controls, profile)`，评估路径经 `PolicyEvaluationService.java:322-327` 带出；guard 路径复用评估结果（§6 行 102 曾声明 guard 不在本 ADR，本节把它纳入）。
- 运行期：guard 请求没有字段来源概念（ADR 0040 §11.1）。

### 11.2 决策

**D1 档案新要求项 `requires.verifiedInputs: boolean`**（缺省 `false`）。注册表升 `1.2.0`：`eu-ai-act-high-risk` 置 `true`，`governed` 保持 `false`。三份副本（core / ts / cloud）与守护测试同步；`exportControls` 校验新键为布尔。旧引擎读到未知键须忽略（三份副本的解析器今天都只取已知字段，须加一条「未知键不报错」的测试钉住，否则 1.2.0 数据会先于引擎发布到 locales）。

**D2 编译期 E7xx 还是运行期 fail-closed——比较与推荐。**

| 方案 | 能回答什么 | 回答不了什么 |
|---|---|---|
| A 编译期错误（E7xx）：规则引用的字段必须被声明为 verified | 作者在编辑器里即时得到反馈；档案保存门能挡 | **来源是每个请求的事实**（这次有签名、下次没有），编译器看不到请求；要让它可判定，作者必须在源码里**声明**某字段是 verified——但作者无权替部署环境担保，这等于让被约束者自证合规；还会让来源进入 IR 与 `source_hash`，同一策略在不同接入方（有无签发方）之间无法复用 |
| B 运行期 fail-closed：guard 按本次请求的实际来源拒绝 | 以事实为准，与 ADR 0040 的 fail-closed 哲学一致；接入方换签发方不改策略 | 运行期不知道规则**读了哪些**字段——除非编译器把它算出来；没有静态引用集就只能要求「全部输入 verified」，过严到不可用 |

**推荐：B 为执行点，编译器只负责把「规则读了哪些输入」算出来（数据，不是诊断），外加一条判定性错误。** 即：

1. **编译器产出 `inputRefs`**（两引擎同车次，parity 锁定）：对每个返回 Verdict 的规则，静态收集对**第一个参数**（记录型输入）的字段读取路径（`req.context.amount` → `context.amount`），经同模块内规则 / 函数调用传递（参数绑定做别名替换）；对 Map 的非字面量键取值、把输入整体或子记录传给导入模块的函数、或任何无法静态定位到路径的读取，记为 `"*"`。写入 `CoreModel.Func` / `Core.Func` 的可选字段 `inputRefs`（字典序去重；未声明档案的模块**不写**，IR 与 `source_hash` 对既有模块逐字节不变——同 §2 对 `profile` 的处理）。aster-api `RuleMetadata` 增 `inputRefs`。
2. **E708 `GOV_PROFILE_INPUT_UNRESOLVABLE`（error）**：档案要求 `verifiedInputs` 且某 Verdict 规则的 `inputRefs` 含 `"*"`。消息参数 `rule / profile / construct`。这是唯一的新编译期诊断，它管的是**可判定性**而不是来源：高风险规则必须让「读了什么」静态可见，否则运行期无从比对。aster-api 编译通道保持 error 级（与 E705/E706 同列）；cloud 保存门随之拒绝。
3. **运行期执行点**在 guard（ADR 0040 §11 D5）：`inputRefs ⊄ verified` → DENY `unverified_input`；IR 无 `inputRefs` 而档案要求 → DENY `input_refs_unavailable`。不新增运行期错误码进共享码表（它们是 `reasonCode`，与 `not_a_verdict` 同类）。

**D3 字段来源在哪里声明：Guard 契约，不是规则源码。** 来源是请求级事实，由调用方以签名事实片段携带、由 aster-api 验签后标定（ADR 0040 §11 D2/D3）。规则源码**不出现**来源声明（不设 `Verified Decimal` 之类的类型修饰）；源码只通过引用间接表达「哪些字段重要」。理由：(1) 担保者与被约束者分离——作者写规则，接入方证明事实，aster-api 裁决；(2) 同一策略可在有 / 无签发方的环境复用，`source_hash` 不随部署环境漂移；(3) 对 aster-lang-core / ts 的改动收敛为「算引用集 + 一条诊断」，不动类型系统、不动求值器、不动 parity 语料的 IR。

**D4 对 aster-lang-core / ts 的影响面。**

| 仓 | 改动 |
|---|---|
| aster-lang-core | `ControlRegistry.Profile` 增 `verifiedInputs`；`GovernanceChecker` 增 `inputRefs` 收集（在 lowering 后的 Core IR 上遍历 `FieldAccess` / `Call`，复用现有 Func 级遍历）与 E708；`CoreModel.Func.inputRefs` 可选字段与序列化；`ErrorCode` 加 E708；`error_codes.json` 加码 |
| aster-lang-ts | 同上：`governance.ts`、`Core.Func.inputRefs`、`error_codes.ts` 生成物；`ErrorCodeParityTest` 两侧锁定 |
| aster-lang-locales | `registry.json` 1.2.0；E708 的 zh/de 诊断译文（与 ADR 0051 §16.1 R56 同一 overlay 机制；hi 由 aster-lang-hi 同法补；缺译回退英文） |
| aster-lang-test | 夹具：声明高风险档案的规则（a）全部读取可定位 → 无 E708 且 `inputRefs` 两引擎逐字相同；（b）Map 动态键 → E708；（c）未声明档案 → IR 无 `inputRefs`、与现有 golden 逐字节相同 |
| aster-api | `RuleMetadata.inputRefs`；`GovernanceDiagnostics` E708 error 级；guard 执行点（ADR 0040 §11 D5）；编译响应带 `inputRefs`（供 cloud 编辑器展示「本规则依赖字段」） |
| aster-cloud | 保存门不改（按 severity）；编辑器 Decision 页签显示 `inputRefs`；签发方设置页（ADR 0040 §11 D3） |

**D5 兼容。** 未声明档案、或档案 `verifiedInputs=false` 的模块：IR、诊断、运行行为逐字节不变。`eu-ai-act-high-risk` 今天的使用者只有信贷试点 `pol-credit-pilot` v3（§6）：它走 `/evaluate-source` + from-evidence，**升级后 from-evidence 决策将因输入 declared 而 DENY**（ADR 0040 §11 D5），试点脚本与 Playwright 的「Credit Officer 批准」步骤会失败——须在 ⑤ 之前二选一：(i) 试点改声明 `governed` 并新增档案 `eu-ai-act-high-risk-pilot`（不要求 verifiedInputs）；(ii) 试点脚本改为附带由测试签发方签名的事实（cloud 需先有附 facts 的能力）。本节倾向 (ii)，因为它顺便验收整条链；由用户拍板（§11.5 Q1）。

### 11.3 验收

1. 两引擎对夹具 (a)(b)(c) 结论一致；`inputRefs` 字典序、经调用传递、`"*"` 判定逐项有正反例。
2. 既有 parity 语料（parse / ir / eval）零差异；未声明档案模块 `source_hash` 不变。
3. aster-api 编译端点：高风险档案 + 动态键 → E708 error、`success: true`（与 E706 同口径）；cloud 保存 400 `compile_error`。
4. guard：ADR 0040 §11.3 第 4 条。
5. 注册表 1.2.0 对旧引擎（1.0.42 pin）加载不报错（守护测试）。

### 11.4 分阶段落地顺序（需发版列车）

① 本节与 ADR 0040 §11 / ADR 0041 §11 同车次定稿 → ② aster-lang-locales 发布 registry 1.2.0（数据先行；旧引擎忽略新键，须先有 D1 的守护测试）→ ③ aster-lang-core 与 aster-lang-ts **同车次**：`verifiedInputs`、`inputRefs`、E708、parity 夹具（aster-lang-test）→ ④ locales 补 E708 译文（可与 ② 合并为一次 locales 发布，若 ③ 的码表先定稿）→ ⑤ aster-api 升级引擎 pin（发版列车自动部署）：`RuleMetadata.inputRefs` 与 guard 执行点生效 → ⑥ aster-cloud 升级 pin、编辑器展示、试点改造（D5）。⑤ 之前 aster-api 的执行点因 IR 无 `inputRefs` 不会拒绝任何请求（档案 `verifiedInputs` 在 ② 之前也不存在），顺序颠倒不会误伤。

### 11.5 待用户拍板

- Q1 信贷试点在 ⑤ 后的处理：改档案（i）还是附签名事实（ii）。
- Q2 `inputRefs` 是否也对非 Verdict 函数产出（本节只对 Verdict 规则，范围最小）。
- Q3 E708 是否同时作为 warning 对未声明档案的 Verdict 规则报出（本节：不报，未声明档案的模块零新增诊断，守 §7 承诺）。
