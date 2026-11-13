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

状态：**ACCEPTED**（2026-11-08 用户确认；实施计划 docs/superpowers/plans/2026-11-08-guard-receipts-and-input-provenance.md）。定稿后与正文冲突时以本节为准。
相关：wontlost-ltd/aster-api#461；ADR 0040 §11 R60（来源模型与执行点）、ADR 0041 §11 R61（收据键）、ADR 0045 §2.2（注册表三份副本与守护测试）、ADR 0031（错误码单一来源）。

### 11.1 现状

- 档案要求只有三项：`ruleId / registeredControls / frameworks`（§3；`aster-lang-locales/controls/registry.json:26-34`，core `ControlRegistry.Profile` 记录 `ControlRegistry.java:44`，ts `governance.ts:140`）。检查对象是注解，不涉及规则读了哪些输入。
- 两个编译器的治理检查器（core `GovernanceChecker.java:40-117`、ts `governance.ts:100-198`）按规则报 E706；错误码 E701–E707 已用，E708–E719 空闲，E720–E724 为 Cedar（ADR 0047）。
- aster-api 从模块 IR 读规则元数据：`replay/.../governance/RuleMetadata.java:11` `RuleMetadata(ruleId, controls, profile)`，评估路径经 `PolicyEvaluationService.java:322-327` 带出；guard 路径复用评估结果（§6 行 102 曾声明 guard 不在本 ADR，本节把它纳入）。
- 运行期：guard 请求没有字段来源概念（ADR 0040 §11.1）。

### 11.2 决策

**D1 档案新要求项 `requires.verifiedInputs: boolean`**（缺省 `false`）。注册表升 `1.2.0`：**所有既有档案（含 `eu-ai-act-high-risk`）保持 `false`**，本版只引入键与校验；`eu-ai-act-high-risk` 翻为 `true` 推迟到 `1.3.0`（时点与条件见 D5）。三份副本（core / ts / cloud）与守护测试同步；`exportControls` 校验新键为布尔。

前置依赖「旧解析器忽略未知 registry 字段」：三份副本的解析器（core `ControlRegistry`、ts `governance/controls.ts`、cloud 的注册表读取）今天都只取已知字段，但没有测试钉住。守护测试**各归各仓**：aster-lang-core、aster-lang-ts、aster-cloud 各加一条「`profiles[].requires` 含未知键时加载成功且该键被忽略」的测试，并**先于 registry 1.2.0 发布**随各自下一次常规发版（core / ts 走 1.0.43 列车，cloud 随下一次部署）；locales 1.2.0 在三者都发布后再发（§11.4 ②）。这样 1.2.0 到达任何消费方时，它的解析器都已被证明忽略新键。（守护测试的发版时点与 §11.4 ②/③ 的先后已被 1.0.44 列车计划取代：守护测试随列车前的独立 PR 合并，② 与 ③ 合并为同名分支组同批合并，见 §11.6 第 10 条。）

**D2 编译期 E7xx 还是运行期 fail-closed——比较与推荐。**

| 方案 | 能回答什么 | 回答不了什么 |
|---|---|---|
| A 编译期错误（E7xx）：规则引用的字段必须被声明为 verified | 作者在编辑器里即时得到反馈；档案保存门能挡 | **来源是每个请求的事实**（这次有签名、下次没有），编译器看不到请求；要让它可判定，作者必须在源码里**声明**某字段是 verified——但作者无权替部署环境担保，这等于让被约束者自证合规；还会让来源进入 IR 与 `source_hash`，同一策略在不同接入方（有无签发方）之间无法复用 |
| B 运行期 fail-closed：guard 按本次请求的实际来源拒绝 | 以事实为准，与 ADR 0040 的 fail-closed 哲学一致；接入方换签发方不改策略 | 运行期不知道规则**读了哪些**字段——除非编译器把它算出来；没有静态引用集就只能要求「全部输入 verified」，过严到不可用 |

**推荐：B 为执行点，编译器只负责把「规则读了哪些输入」算出来（数据，不是诊断），外加一条判定性错误。** 即：

1. **编译器产出 `inputRefs`**（两引擎同车次，parity 锁定）：对每个返回 Verdict 的规则，静态收集对**第一个参数**（记录型输入）的字段读取路径（`req.context.amount` → `context.amount`）。写入 `CoreModel.Func` / `Core.Func` 的可选字段 `inputRefs`（字典序去重；未声明档案的模块**不写**，IR 与 `source_hash` 对既有模块逐字节不变——同 §2 对 `profile` 的处理）。aster-api `RuleMetadata` 增 `inputRefs`。

   **静态传播规则（完整、可判定）。** 分析在 lowering 后的 Core IR 上进行，是一个对「输入别名」的前向数据流：每个表达式求得一个**来源集** ⊆ {`<路径>`, `*`, ∅}；规则的 `inputRefs` = 规则体内所有「消费」（条件判断、算术 / 比较 / 字符串运算、`Return` 的参数、传给内置函数的实参）处来源集的并。

   | 构造 | 规则 |
   |---|---|
   | 参数引用 `req`（第一个参数） | 来源 {`""`}（根） |
   | 字段访问 `e.f` | 把 `e` 的每个路径 p 扩成 `p.f`；`e` 含 `*` 则仍为 `*` |
   | `Let x be e.` / `Set x to e.` | 变量 x 绑定到 `e` 的来源集；之后对 x 的引用取该集合。多次赋值（含分支内赋值）取**并**（流不敏感、路径不敏感：宁可多收不少收） |
   | 记录构造 `Construct T with f1 = e1 …`、列表字面量 | 结果是**聚合**：按字段 / 元素分别保存来源集；后续 `.f1` 取对应集合。无法按字段分解时（如传入整个聚合再做动态访问）退化为各字段并集 |
   | 同模块调用 `g(a1, a2)` | 把 g 的形参绑定到实参来源集做**内联求值**（g 的分析结果以形参为符号，调用点代入；任何递归（互递归与直接自递归）→ `*`，超过 8 层或超出单规则节点访问预算 → `*`，见 §11.6 第 12–13 条）；g 的返回值来源集是 g 体内 `Return` 表达式的并 |
   | `Match` / `If` 分支、模式绑定 `When x is Some(v)` | 被匹配表达式（Match 主语）本身算一次读取，记入结果；其来源集绑定到模式变量；各分支取并（§11.6 第 15 条） |
   | 对 Map 用字面量键取值 `m["amount"]` 或 `m.amount` | 路径扩展 `.amount` |
   | 对 Map 用**非字面量**键取值、迭代 Map 的键 / 值、把 `context`（或任何含输入来源的值）整体转为文本 / 序列化 | **`*`** |
   | 跨模块调用（`Use` 导入的函数）、未限定的非本模块非 builtin 调用、以及限定名不在 builtin 命名空间白名单内的调用，且任一实参来源集非 ∅ | **`*`**（construct `cross-module-call`；不分析导入模块；§15–16 之前导入签名契约未落地，见 ADR 0051 §16.3） |
   | 把输入子记录传给同模块函数 | 不是 `*`：按「同模块调用」内联，子记录的路径前缀随形参代入（`g(req.context)` 中 g 读 `c.amount` → `context.amount`） |
   | 闭包 / 函数值（ADR 0052 之后允许的函数值、`Apply`） | 调用目标静态可定位（同模块具名函数作为值直接传递）→ 按同模块调用处理；目标来自变量或参数（高阶）→ **`*`** |
   | 常量、不含输入来源的表达式 | ∅ |

   可判定性：来源集的元素是有限路径（受记录类型深度限制，Map 路径只在字面量键上扩展，长度 ≤ 8 段）加一个顶元 `*`，并集单调，内联深度有上界，因此分析必然终止且结果唯一；两引擎对同一 IR 得到相同集合是 parity 的断言。**不做**：数值 / 字符串的「污点跳过」（`Let n be req.context.amount + 1` 的 n 仍来源 `context.amount`——保守）；条件式剪枝（分支不可达也计入）。

   正反例（`GuardRequest has …, context as GuardContext`；`GuardContext has amount as Decimal, tier as Text, meta as Map of Text to Text`）：

   | 规则片段 | `inputRefs` |
   |---|---|
   | `If req.context.amount at least 50000m, …` | `context.amount` |
   | `Let c be req.context. Let a be c.amount. If a > 0 …` | `context.amount` |
   | `Let x be req.context.amount. Set x to req.context.tier. If x …` | `context.amount`、`context.tier` |
   | `Let big be isBig(req.context).`（同模块 `isBig given c as GuardContext` 读 `c.amount`） | `context.amount` |
   | `If req.context.meta["region"] equals to "EU" …` | `context.meta.region` |
   | `Let k be req.action. If req.context.meta[k] …` | `*` → E708 |
   | `Let s be Text.format(req.context) …`（整体序列化） | `*` → E708 |
   | `Let r be ext.score(req.context.amount).`（`ext` 为 `Use` 导入） | `*` → E708 |
   | `Let f be pick(req.action). Let v be apply f to req.context …`（函数值来自变量） | `*` → E708 |
   | `If req.principal_roles contains "ops" …` | 引擎 `principal_roles` → guard 形状映射为 `principal.roles`（数组整叶子；与 ADR 0040 §11 D1 一致；两种路径空间见 §11.6 第 3 条） |
   | 规则只用常量与 `req.action` | 引擎 `action` → guard 形状映射为 `action.name`（§11.6 第 3 条） |
   | `Let c be req.context. Let lim be limitFor(c.tier). If c.amount > lim …`（`limitFor` 读 `t` 做比较，不再读输入） | `context.tier`、`context.amount`（同模块内联，形参 `t` 代入 `context.tier`） |
   | 内联深度**恰好 8 层**：`decide → h1 → h2 → … → h8`，`h8 given c as GuardContext` 读 `c.amount`，每层把 `req.context` 原样传下去 | `context.amount`（8 层在上界内，仍可静态定位；两引擎 parity 夹具钉住该边界） |
   | 同上但 **9 层**（`h9` 才读 `c.amount`） | `*` → E708（超过内联上界；夹具与上一条成对） |
   | 互递归：`isOk(c)` 调 `isBad(c)`，`isBad(c)` 调 `isOk(c)`，任一方读 `c.amount` | `*` → E708（construct `recursion`） |
| 直接自递归：`walk(c)` 在体内再调 `walk(c)`，并读 `c.amount` | `*` → E708（construct `recursion`；不再展开一次求定点，§11.6 第 12 条） |

   运行时对 `"*"` 的保守处理（写明，供 ⑤ 之前已发布 IR 或 E708 被绕过的情形）：aster-api 在档案要求 `verifiedInputs` 时把 `"*"` 视为「引用全部输入叶子」，即请求中**每个** declared 叶子都算被引用 → DENY `unverified_input`，收据 `unverifiedFields` 写 `["*"]`。编译期 E708 与运行期保守处理是同一条规则的两道门，前者让作者改源码，后者保证前者缺位时不放行。
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

**D5 兼容与翻转时点（默认方案，不留二选一）。** 未声明档案、或档案 `verifiedInputs=false` 的模块：IR、诊断、运行行为逐字节不变。`eu-ai-act-high-risk` 今天的使用者只有信贷试点 `pol-credit-pilot` v3（§6），走 `/evaluate-source` + from-evidence；若该档案在引擎发布时即要求 verifiedInputs，from-evidence 决策会因输入 declared 而 DENY（ADR 0040 §11 D5），试点脚本与 Playwright 的「Credit Officer 批准」步骤失败。因此：

1. **registry 1.2.0（aster-lang-locales）**：只加键，`eu-ai-act-high-risk.requires.verifiedInputs = false`。随 ③ 发布的引擎与 ⑤ 的 aster-api 对任何现有策略**不改变行为**；E708 此时对 `eu-ai-act-high-risk` 模块也不会触发（要求项为 false 时不做 `*` 检查）。
2. **aster-cloud 具备签发能力**（责任仓 aster-cloud）：(a) 团队设置页维护签发方（ADR 0040 §11 D3）；(b) 执行路由在调用 from-evidence 时可为输入附 facts——第一期只做一个内置签发方「cloud-execution」：cloud 以自己的租户级密钥对**执行人在表单里填写的输入**签名？**不做**——cloud 签名不会把人手填的值变成事实，这与「事实必须来自可信数据源」矛盾。第一期的可信来源限定为：`principal.id`（已由 HMAC 签过）与 cloud **从策略租户数据目录 / 身份系统查得**的值（如执行人的 `businessRoles`）；其他 context 字段仍 declared。

   「不签手填值」的可执行约束（责任仓 aster-cloud）：
   - cloud 内部签发端（服务端模块 `services/guard/fact-signer.ts`，无公开 HTTP 入口；执行路由与试点脚本都经它）**不接受 claims 原值**。它的输入只有 `{ path, source, args }` 三元组：`source` 必须是 cloud 配置中登记的**来源函数**之一，签名载荷的 `claims[path]` 由该函数在服务端取得，调用方传入的任何 `value` 字段 → 400 `claims_not_allowed`（响应体 `path`）。
   - 第一期来源函数清单（`src/config/fact-sources.ts`，每项 `{ id, allowedPaths, resolve(ctx, args) }`，新增须改代码与测试，不可运行时配置）：`hmac.userId`（路径 `principal.id`，取签入 canonical 的 userId）；`idp.businessRoles`（路径 `principal.roles`，取策略租户的 `TeamMember.businessRoles` / `User.businessRoles`）；`catalog.piiMark`（路径 `context.<field>.$pii`，取数据目录对该字段的 PII 标记——供 ADR 0042 §11 的标记交叉核对，不签业务值）；试点专用 `fixture.creditPilot`（路径 `context.*`，只在 `NODE_ENV !== 'production'` 且租户为 `credit-pilot` 时登记，从 seed 固定 fixture 文件读值，**不读请求体**）。
   - 每条签出的事实在 `claims` 之外带 `sources: { "<path>": "<source id>" }`，进入签名载荷（aster-api 验签时原样校验，不解释）；aster-api 收据 `factIssuers` 旁增 `factSources`（`path=source` 列表，≤ 64 项，缩短顺序排在 `verifiedFields` 之后），证据包 `guardInputSources.factSources` 展示每条事实的 source。
   - 验收（cloud 集成测试）：提交含 `value` 的签发请求 → 400 `claims_not_allowed`；`source` 不在清单 → 400 `unknown_fact_source`；`path` 不在该 source 的 `allowedPaths` → 400 `path_not_allowed`；执行路由产出的事实其 `sources` 与清单一致；证据包 v6 条目展示 `factSources`。(c) 为信贷试点配置一个测试签发方（seed 脚本生成 Ed25519 密钥对、写入 `guard_fact_issuers`），`run-credit-pilot.ts` 与 Playwright 用它对四组 fixture 的 `context.*` 签名后执行，使 from-evidence 在要求 verifiedInputs 时得到 PENDING 而非 DENY。
3. **registry 1.3.0（aster-lang-locales）**：第 2 项合并并在本地栈跑通（九款状态不变、`reviewers[]` 仍含 Credit Officer）后，把 `eu-ai-act-high-risk.requires.verifiedInputs` 翻为 `true`，随**引擎发版列车**发布（core 内注册表副本同步）；aster-api 升引擎 pin，cloud 升自己的注册表副本（与 §11.4 ⑦ 同；§11.6 第 7 条：aster-api 读 core jar 内的注册表副本，该副本受 parity 测试约束须与 locales 相等）。翻转后，任何声明该档案而没有签名事实的生产策略会在 guard 路径被 DENY——发布 1.3.0 前由用户在生产库只读扫描声明了该档案的策略（SQL 与 ADR 0051 §16.2 的扫描同法），逐一确认有签发方配置。
4. 期间若有租户想在 1.3.0 前启用：自定义档案可在注册表加 `…-verified` 变体（如 `eu-ai-act-high-risk-verified`，要求 verifiedInputs=true），由该租户策略显式声明。新增这类变体同样是注册表变更，**同样需要引擎列车**（理由同第 3 项）。

### 11.3 验收

1. 两引擎对夹具 (a)(b)(c) 结论一致；`inputRefs` 字典序、经调用传递、`"*"` 判定逐项有正反例。
2. 既有 parity 语料（parse / ir / eval）零差异；未声明档案模块 `source_hash` 不变。
3. aster-api 编译端点：高风险档案 + 动态键 → E708 error、`success: true`（与 E706 同口径）；cloud 保存 400 `compile_error`。
4. guard：ADR 0040 §11.3 第 4 条。
5. 注册表 1.2.0 对旧引擎（1.0.42 pin）加载不报错（守护测试）。

### 11.4 分阶段落地顺序（需发版列车）

① 本节与 ADR 0040 §11 / ADR 0041 §11 同车次定稿 → ①′ aster-lang-core、aster-lang-ts、aster-cloud 各自加「忽略未知 registry 字段」守护测试并随各自下一次常规发版（D1）→ ② aster-lang-locales 发布 registry 1.2.0（只加键，全部档案 `verifiedInputs=false`）→ ③ aster-lang-core 与 aster-lang-ts **同车次**：`verifiedInputs` 解析、`inputRefs`、E708、parity 夹具（aster-lang-test）；E708 的英文模板随码表进 `error_codes.json` → ④ locales 补 E708 zh/de 译文（hi 由 aster-lang-hi）；**译文补发前**，按 ADR 0051 §16.1 R56 的回退规则，aster-api 编译通道与 cloud 编辑器对 E708 显示 `error_codes.json` 的英文模板（码与参数完整，仅文案未本地化），不阻塞 ③/⑤ → ⑤ aster-api 升级引擎 pin（发版列车自动部署）：`RuleMetadata.inputRefs` 与 guard 执行点生效，但因无档案要求 verifiedInputs，不拒绝任何请求 → ⑥ aster-cloud 升级 pin、编辑器展示 `inputRefs`、签发方设置页、from-evidence 附 facts、试点测试签发方（D5 第 2 项）→ ⑦ locales registry **1.3.0** 翻转 `eu-ai-act-high-risk.verifiedInputs=true`，随**引擎发版列车**（core 副本同步）发布，aster-api 升引擎 pin、cloud 升注册表副本（D5 第 3 项、§11.6 第 7 条；前置条件还有：aster-api 已部署「评估事件自带 `inputRefs`」并关闭 from-evidence 的 fail-open，cloud 具备签发能力；发布前用户执行生产只读扫描）。②–⑥ 任一顺序颠倒都不会改变现有策略行为；只有 ⑦ 是行为变更点。

### 11.5 已选默认与可改项

| 项 | 已选默认（定稿即生效） | 可改为 | 改动影响 |
|---|---|---|---|
| `eu-ai-act-high-risk` 的 `verifiedInputs` | 1.2.0 为 `false`；1.3.0 翻 `true`（D5） | 直接在 1.2.0 置 `true` | 信贷试点立即 DENY，须先完成 D5 第 2 项 |
| 信贷试点迁移 | 配置测试签发方并附 facts（D5 第 2(c) 项） | 改声明不要求 verifiedInputs 的档案变体 | 试点不再验收 R60 整条链 |
| `inputRefs` 产出范围 | 仅返回 Verdict 的规则（声明档案的模块） | 全部函数 | IR 体积与 parity 语料范围扩大，无运行期消费方 |
| E708 范围 | 仅声明档案且档案要求 verifiedInputs 的模块；未声明档案的模块**不报**（守 §7「零新增诊断」） | 对未声明档案模块报 warning | 违背 §7 承诺，编辑器噪声 |
| `*` 的运行期处理 | 视为引用全部输入，DENY `unverified_input`（D2） | — | 放宽即放行未验证输入，不提供选项 |

### 11.6 实施澄清（2026-11-12）

来源：实施计划 `docs/superpowers/plans/2026-11-12-train-1044-task16-engine.md`（1.0.44 列车，用户 2026-11-12 确认 Q1–Q6）。本小节澄清 §11.2–§11.5 未写明的细节，并更正 D5 第 3 项、补充运行期未知档案规则（用户 2026-11-12 确认 Q3/Q4）。被本小节取代的只有：D5 第 3 项「翻转不需要引擎列车」（第 7 条）、§11.2 规则表中「递归按一次展开后定点」与「限定名即 builtin」的写法（第 12、14 条）、D1 守护测试的发版时点与 §11.4 ②/③ 的先后（第 10 条）、§11.2 正反例表中 `principal.roles` / `action.name` 两行的路径写法（第 3 条）；其余以上文为准。

1. **`inputRefs` 的写入条件与计算位置。**
   - 写入条件：模块声明了档案（`module.profile != null`，不论注册表里是否有该档案、要求项是什么），**且**函数返回 Verdict（与 `GovernanceChecker.returnsVerdict` 同口径）。其余函数不写该键；未声明档案的模块 IR 逐字节不变。
   - 计算位置：在 **R2（`TypedReferenceResolver`）之后**，不在 lowering 末尾。R2 负责把本地化名称规范化、判定 `x.f` 是记录字段还是 Map 键；在 R2 之前计算，zh / de 源码会产出本地化路径。分析器是纯函数，不读注册表。R2 之后仍会复制 Func 的通道必须保留该字段。
   - 取值：路径用点连接，**引擎路径**最多 8 段（超过 → `*`，construct `path-too-long`）；按 UTF-16 码元字典序去重（Java `String.compareTo` 与 JS 默认 `sort` 同序）。规则不读输入时为 `[]`，与 null（未提供，旧引擎）区分。
2. **补充传播规则**（§11.2 D2 规则表的补遗）：
   - (a) 直接消费第一个参数本身（标量参数如 `given amount`，或整条记录参与比较）→ 路径 `""`（根）。整体转文本 / 序列化仍为 `"*"`。
   - (b) 字面量 Map 键为空串、含 `.` 或含控制字符（U+0000–U+001F）→ `"*"`，construct `unrepresentable-key`（点路径无法无歧义表示，控制字符会让路径在日志与审计 JSON 中失真；core `InputRefsAnalyzer.representableKey`，ts `UNREPRESENTABLE_KEY_CHAR`）。
   - (c) 第一个参数之外的参数不追踪（§11.2 只定义第一个参数）。
   - **`"*"` 独占**：`"*"` 是顶元；出现 `"*"` 时列表**只含** `["*"]`，因为它已覆盖其余路径，两引擎也不必对「`*` 与具体路径并存」的排序达成一致。
   - **被调函数按模块限定名分类**：R2 之后 Call 目标名是规范全名；它等于本模块某个 Func（`<module>.<name>`），或者是未限定的本模块名，才按同模块调用内联。经 `Use … as Alias` 导入的函数，即使别名与本模块函数同名，也按跨模块调用处理（→ `*`）。
3. **两种路径空间：引擎路径 vs 请求路径。** 引擎只看得到规则第一个参数的**源码字段路径**（作者写的名字，编辑器展示的也是它），与 guard 无关；guard 请求路径（`principal.roles`、`action.name`、`context.amount`）由 aster-api 按**入口形状**映射：
   - **guard 形状**（仅 `POST /api/v1/guard/decisions`，规则参数是 `GuardActionMapper.flatten` 产出的扁平记录）：`principal_id→principal.id`、`principal_type→principal.type`、`principal_roles[.…]→principal.roles`、`action→action.name`、`resource_type→resource.type`、`resource_id→resource.id`、`agent_model→agent.model`、`context→context`、`context.<p>→context.<p>`；标量字段带子路径（如 `action.x`）、`""`、`"*"`、未知首段 → `*`。映射表与 `flatten` 的键表是同一张常量表。
   - **证据形状**（`from-evidence`）：评估输入是 `context`，第一个参数名进入路径前缀。映射在**写评估事件时**完成（见第 4 条）：只有当第一个参数由命名输入 Map 中的某个键实际匹配时，路径映射为 `context.<实际匹配键>.<path>`，`""` 映射为 `context.<该键>`；位置数组输入、无法确定匹配键、或路径含 `*` → `["*"]`。
   - 因此根路径 `""` 在 guard 形状下为 `*`（扁平记录整体被消费，等于读全部输入），在证据形状下为 `context.<实际匹配键>`。
4. **评估事件自带输入引用**（ADR 0041 §11 D2 键旁登记）：`POLICY_EVALUATION` metadata 在 `profile` 旁新增 `inputRefs`（写事件时已按第 3 条证据形状映射好的请求路径）与 `verifiedInputsRequired`（布尔），只在模块声明档案时写入；无档案事件的 metadata 逐字节不变。
   - **上限**：对**映射之后**的列表检查——≤ 32 项，且整个 JSON 数组序列化后的 **UTF-8 字节数** ≤ 512；超过任一上限整体写 `["*"]`（审计链 metadata 上限 4096 字符，超长会被截断成非法 JSON）。字节上限原定 1024，实施时（Task 16b）把其余各键撑到校验上限后实测只剩约 570 字符，故下调为 512，由 `EvaluationEvidenceSizeTest` 守住；审计链上限不动。8 段上限只约束引擎路径（第 1 条），本上限只约束映射后的请求路径（第 9 条）。规则不读输入时写 `[]`，**不省略**。
   - **IR 没有 `inputRefs`**（声明档案的模块由旧引擎编译）：事件**不写** `inputRefs`，消费方回退到下面的「档案 + 注册表」判定。
   - **要求项永远在消费时重算**：是否要求 verifiedInputs 一律由事件的 `profile` 加**当前**注册表在消费时判定；事件里的 `verifiedInputsRequired` 只是写事件时的快照，供审计，**不参与判定**。否则 1.3.0 翻转前写下、`verifiedInputsRequired=false` 的事件在翻转后仍会放行未验证输入。事件自带的 `inputRefs` 照常使用——它是编译事实，不随注册表变化。
   - `from-evidence` 的取用顺序见 ADR 0040 §11 D5 实施澄清：事件自带 `inputRefs` → 按事件 `policyVersionId` 取的规则元数据 → 都没有且要求 verifiedInputs → DENY `input_refs_unavailable`。evaluate-source 模块没有已保存版本，事件是它唯一的来源。
5. **显式引用即使请求中缺字段也计入**：`inputRefs` 中的路径在本请求里不存在，等于该路径未经验证 → 档案要求 verifiedInputs 时 DENY `unverified_input`（缺字段不能当作「不需要验证」）。
6. **未知档案在运行期按「不要求 verifiedInputs」处理**：编译期已报 E705，此类模块无法保存。代价：D5 第 4 项的 `…-verified` 自定义档案若在某个注册表版本里被回滚删除，声明它的模块会悄悄降为不要求；回滚注册表前须先扫描声明该档案的策略。aster-api 对「元数据读不到」计 counter（`aster_guard_rule_metadata_unavailable_total{path}`）并记日志：默认 DEBUG（evaluate-source 锚定读不到元数据是常态），只有事件带非空 `profile` 时升为 WARN。
7. **1.3.0 翻转需要引擎列车**（更正 D5 第 3 项「不需要引擎列车」）：aster-api 读的是 core jar 内的注册表副本（`ControlRegistry.loadDefault()`），该副本由 `ControlRegistrySiblingParityTest` 约束须与 locales 深度相等，所以翻转须 core 发版、aster-api 升 pin。§11.4 ⑦ 已相应改写。
8. **E708 的 `construct`**：取**先序遍历中第一个产生 `*` 的节点**的类别。遍历顺序：规则体语句按源码顺序；遇到同模块调用时先遍历实参，再进入被内联的被调函数体（深度优先），然后回到调用点之后继续；同一表达式的子节点按 Core IR 字段声明顺序。`construct` 闭集：`dynamic-map-key`、`map-iteration`、`whole-input-serialized`、`cross-module-call`、`function-value`、`inline-depth`、`recursion`、`path-too-long`、`unrepresentable-key`；值不翻译。
9. **非叶子引用的满足规则**（前缀规则）：引用 `r` 被满足，当且仅当
   - `r` 本身或其祖先路径被签名事实覆盖（verified 的祖先语义）；或
   - `r` 不是本请求中出现的叶子，而本请求中 `r` 之下**至少有一个**出现的叶子，且这些叶子**全部** verified。
   「出现的叶子」与 `presentLeafPaths` 同口径：数组是一个整叶子，空对象自身是一个叶子，请求未给出的字段不算出现。`r` 之下没有任何出现的叶子（字段缺失）→ 未满足（第 5 条）。例：`context.applicant` 或裸 `context` 被引用时，其下每个出现的叶子都须 verified。
10. **落地顺序以 1.0.44 列车计划为准**：①′ 守护测试作为列车前的独立 PR 合并；② 与 ③ 合并为同名分支组（registry 1.2.0、两引擎 `inputRefs` 与 E708、E708 译文）同批合并，因为 core / ts 注册表副本的 parity 测试要求 locales 与副本同时变；D1 要的「旧解析器忽略新键」由守护测试在旧 tag 上运行证明，不靠 ② 先于 ③ 的时间差。
11. **给 cloud ⑥ 设计的提示**：签名事实必须附在**第一次** `from-evidence` 请求上。门的 DENY 会消耗该证据的 correlationId（ADR 0040 §11 D5 实施澄清），事后补附事实无法挽回，只能重新评估得到新的评估事件。
12. **任何递归一律 `*`**（取代 §11.2 规则表「自递归展开一次至定点」）：互递归与直接自递归都产生 `*`，construct 为 `recursion`；分析器在内联栈上发现被调函数已在栈中即判定，不做定点迭代。理由：定点迭代需要两引擎对「展开几次」「部分结果如何合并」逐位一致，换来的只是少数递归规则能通过 E708，而高风险档案下的规则本就应当让「读了什么」一眼可见；更保守，且两引擎实现更简单。代价：自递归规则在要求 verifiedInputs 的档案下必须改写为迭代或拆出非递归读取。
13. **单规则节点访问预算与内联记忆化。**
    - 预算：每条规则的分析受**节点访问预算**约束，常量 `MAX_NODE_VISITS = 200_000`（core `InputRefsAnalyzer.MAX_NODE_VISITS`，ts `input-refs.ts` 的 `MAX_NODE_VISITS`，两引擎同值、按同一口径计数：`eval` 每访问一个表达式节点即把先序编号计数器加一，编号超过上限 → 该节点产生 `*`，construct 为 `inline-depth`，不再展开）。计数器与 E708 `construct` 的先序编号共用，所以两引擎在同一 IR 上耗尽预算的节点相同。此前草案的「内联调用总数上限 4096」（`MAX_INLINE_CALLS`）**已取消**，由本预算取代；内联嵌套深度上限 `MAX_INLINE_DEPTH = 8` 不变，超出同样是 `inline-depth`。construct 闭集不变。
    - 记忆化：同模块调用的内联结果按（调用栈、被调函数、实参来源）缓存（`memo` / `memoKey`），同一调用点的重复展开不再重遍历函数体。记忆化只是实现优化，**结果与不记忆化的完整遍历逐项相同，包括 E708 报告的 `construct`**（缓存摘要记录体内第一个产生 `*` 的 construct，命中时在实参之后、调用点之后的节点之前按先序重新登记）；缓存键含调用栈，因此不会把递归判定带到别的调用上下文。取值依据：语料中最大的规则不足 2 千次访问，20 万次留两个数量级余量，同时把恶意扇出（如 64×64 调用链）的分析耗时限制在毫秒级。
14. **调用分类细则**（补充第 2 条末句「被调函数按模块限定名分类」）：
    - 未限定名：本模块函数 → 同模块内联；单段 builtin 与运算符 → 消费实参；其余（非局部、非 builtin）→ `cross-module-call` 的 `*`。
    - 限定名：属于本模块（`<模块名>.<函数名>`）→ 同模块内联；首段在 builtin 命名空间白名单（`BUILTIN_NAMESPACES`）内 → 按 builtin 分析（实参被消费，Map.keys / Map.values、Crypto.hash 等特例见第 16 条）；其余一律 `cross-module-call` 的 `*`。
    - builtin 命名空间白名单（闭集；core `InputRefsAnalyzer.BUILTIN_NAMESPACES`，ts `input-refs.ts` 同名常量，同表）：`Bool`、`Consent`、`Console`、`Crypto`、`Date`、`Db`、`Decimal`、`GDPR`、`Http`、`IO`、`Int`、`List`、`Log`、`Map`、`Maybe`、`Option`、`PII`、`Result`、`Set`、`Text`、`UUID`、`Verdict`（共 22 项；`Consent`、`Db`、`GDPR`、`Set` 只出现在 TS 的效果 / PII / LSP 表中，为两引擎同表而列入）。新增命名空间须同时改两引擎与 parity 夹具。此前「限定名且未被导入即视为 builtin」的写法被取代：未登记的限定名（拼写错误、尚未登记的命名空间）不再被默许为 builtin，而是保守地得到 `*`。
15. **Match 主语算一次读取**：`Match` 的被匹配表达式在进入分支前即记为消费，不论各分支是否再引用模式变量。这是可靠性所需：分支选择本身取决于主语的值（`Match req.context.tier` 仅凭走了哪个分支就泄露了 `context.tier`），不记则 `inputRefs` 会漏报。模式变量绑定到主语的来源集；各分支取并。
16. **序列化与迭代特例的取值**：`Map.keys` / `Map.values` 作用于带输入来源的值 → `*`，construct `map-iteration`；`Crypto.hash`、`Int.toString`、`Bool.toString` 作用于输入派生值 → `*`，construct `whole-input-serialized`。已知误报：`Int.toString` 作用于标量叶子（如 `Int.toString(req.context.count)`）并未整体序列化任何记录，但 1.3.0 翻转后会在要求 verifiedInputs 的档案下得到 E708。当前接受该误报以换取实现简单；是否按「标量叶子豁免」收窄，待 X4 生产扫描（§11.4 ⑦ 发布前的只读扫描）给出真实命中数后再定。
17. **硬前置条件：祖先路径记录的可靠性依赖前缀规则。** 若干形状记录的是**祖先路径**而不是具体叶子，例如 `List.filter(req.context.items, f)` 记 `context.items`，传入整个子记录的调用记该子记录路径。这对「已验证」的判定只有在第 9 条前缀规则成立时才可靠：非叶子引用被满足，当且仅当其下每个出现的叶子都 verified（且至少有一个）。该规则已在 aster-api `VerifiedInputsGate` 实现（aster-api#487）。因此：**前缀规则是 1.3.0 翻转（§11.4 ⑦）的硬前置条件**——翻转所在的 aster-api 版本必须包含 #487，且其回归测试（非叶子引用下存在未验证叶子 → DENY `unverified_input`）在发版前通过；任何绕过或削弱前缀规则的改动都等同于破坏 `inputRefs` 的可靠性，必须同时撤回翻转。
18. **E708 检查一律重算 `inputRefs`，不信任 IR 自带值。** aster-api 接受用户提交的 Core IR（如 `POST /evaluate-json`），IR 里的 `inputRefs` 可以伪造。因此治理检查器（core `GovernanceChecker.checkInputRefs`，ts 同口径）对声明要求 verifiedInputs 的档案，总是对规则体调用 `InputRefsAnalyzer.analyze` 重新计算，从不读取 IR 已有的 `inputRefs`；这也使不带该字段的旧 IR 照常被检查。分析带记忆化与节点预算，重算代价可接受。
19. **1.3.0 翻转的补充前置条件（信任边界）：** aster-api 的运行期消费方（guard / from-evidence 读 `RuleMetadata.inputRefs`）面对**不可信 IR**（用户提交的 Core IR、或不是由本通道编译产出的 IR）时，必须在读取前自行重算 `inputRefs`，或把 IR 携带的 `inputRefs` 一律当作 `["*"]`；否则攻击者可在 IR 里写 `inputRefs: []` 绕过前缀规则与 `unverified_input`。只有经本通道编译、`source_hash` 可核对的 IR，才可直接采信其 `inputRefs`。此条与第 17 条并列，同为 §11.4 ⑦ 的硬前置条件。
    - **aster-api 实现（Task 16b）**：`RuleMetadata.of` 对所有入口一律按规则体调用 `InputRefsAnalyzer.analyze` 重算，从不读 IR 自带的 `Func.inputRefs`（写入条件与引擎同口径：声明档案且返回 Verdict）。`/evaluate-json` 的 IR 不可信必须如此；`/evaluate`（已保存产物）与 `/evaluate-source`（即时编译）虽出自本通道，统一重算后不必逐路径判定 IR 是否可信，也让旧引擎编译、不带该字段的产物得到引用集；结果按编译产物 memo。分析器对畸形 IR 抛错时按 `["*"]`。`/compile` 响应里的 `inputRefs` 取自本次编译的 IR，只供展示。

### 11.7 X4 实施裁定（2026-11-13）

来源：实施计划 `docs/superpowers/plans/2026-11-13-x4-fact-issuance-and-registry-130.md`（wontlost-ltd/aster-api#494，下称「X4 计划」）§6 第 1–15 条，用户 2026-11-13 全部按 proposed 确认。本小节是这些裁定的唯一批准记录；与 §11.2–§11.6 冲突处以本小节为准，被取代的原文逐条注明。未注明的其余条款不变——特别是：`fixture.creditPilot` 不进生产（D5 第 2 项）、要求项在消费时按事件 `profile` + 当前注册表重算（§11.6 第 4 条）、前缀规则与不信任 IR 自带 `inputRefs` 是翻转硬前置（§11.6 第 17、19 条）、签名事实须附在第一次 from-evidence 请求上（§11.6 第 11 条）。

每条状态均为 **accepted 2026-11-13**。

1. **编辑器 `inputRefs` 取浏览器 Core IR。** cloud 编辑器 Decision 页签展示的 `inputRefs` 读浏览器内 aster-lang-ts 编译产出的 Core IR `Func.inputRefs`（引擎路径，作者视角），不为此调用 aster-api `/compile`。修订 D4 表中 aster-api 一行「编译响应带 `inputRefs`（供 cloud 编辑器展示）」：该字段保留，但 cloud 编辑器不是其消费方。理由：Decision 页签的全部数据本就来自浏览器编译，额外调 `/compile` 会给每次按键加一次网络往返；两引擎 `inputRefs` 的一致性由 aster-lang-test parity 锁定，运行期判定仍由 aster-api 用 Java 分析器重算（§11.6 第 19 条），编辑器展示不参与任何判定。
2. **`catalog.piiMark` 推迟到 #460。** 取代 D5 第 2 项第一期来源函数清单中的 `catalog.piiMark`。第一期清单为三项：`hmac.userId`（`principal.id`）、`idp.businessRoles`（`principal.roles`，取 cloud 已登记的业务角色，不读执行输入）、`fixture.creditPilot`（`context.*`，仅非生产且租户为 `credit-pilot`）。理由：`catalog.piiMark` 的 claim 路径 `context.<field>.$pii` 在 guard 请求的 `action` 中不存在，aster-api 会按 ADR 0040 §11 D3 的 `claim_path_absent` 拒绝整个请求；它唯一的消费方是 ADR 0042 §11 的标记交叉核对（#460），届时与该消费方一起定义取值与路径。现在登记而不附，只会留下无消费方的代码。
3. **证据包 v6 的 `factSources` 展示随 #460。** 取代 D5 第 2 项验收中「证据包 v6 条目展示 `factSources`」。X4 只保证 `factSources` 进链上决策收据（ADR 0041 §11 D1，aster-api 已实现，可经 `GET /api/v1/audit/receipts` 核验）。理由：ADR 0041 §11 D4 规定 v6 与 ADR 0042 §11 的审批快照键一次升版，单独为 `factSources` 升版会造成两次 schema 变更。它不是 1.3.0 翻转的前置条件。
4. **cloud 签发方用一把平台级 Ed25519 密钥，由各租户显式启用。** 取代 D5 第 2 项 (b) 中「内置签发方 `cloud-execution`、租户级密钥」的设想。签发方身份（`issuer`、`kid`、私钥、公钥）全部来自 cloud Worker secret `ASTER_FACT_SIGNING_KEY`；`issuer` 取该配置的值（生成脚本缺省 `aster-cloud`）。租户在团队 / 个人设置页点击「启用」，即把该公钥经 HMAC 端点登记为本租户签发方；未启用的租户不附任何事实。理由：每租户一把密钥需要新表与加密存储，ADR 未要求；签名载荷含 `audience = tenantId`，跨租户重放在 aster-api 验签时即被拒（ADR 0040 §11 D3）。轮换：新 kid 写入 secret → 各租户启用新 kid → 吊销旧 kid（顺序不可反，否则吊销会让依赖旧 kid 的待审批过期）。
5. **试点密钥由 `pnpm fact-key:gen` 生成，seed 只登记公钥。** 取代 D5 第 2 项 (c)「seed 脚本生成 Ed25519 密钥对、写入 `guard_fact_issuers`」。`seed-credit-pilot.ts` 读已配置的 `ASTER_FACT_SIGNING_KEY`，把其公钥经 HMAC `PUT /api/v1/guard/fact-issuers/{issuer}/{kid}` 登记到 `credit-pilot` 租户（不直写 aster-api 库表）；未配置即报错退出。理由：seed 每次生成新密钥会让运行中的 cloud 进程持有的私钥与登记的公钥不一致。
6. **附事实前确认签发方可用；`invalid_fact` 不去掉事实自动重试。** cloud 在 from-evidence 前读本租户签发方列表，只有 cloud 签发方的 `issuer#kid` 存在、启用、公钥与配置一致、且 `notAfter` 缺省或晚于本条事实的 `expiresAt` 时才附事实；否则不附，登记照常。aster-api 返回 400 `invalid_fact` 时记为登记错误，不去掉事实重试。理由：附上不可验的事实会让整个请求 400；而去掉事实重试在翻转后会得到一次 DENY 并消耗该证据（§11.6 第 11 条），无法挽回。
7. **`hmac.userId` 与 `idp.businessRoles` 照样签。** 补充 D5 第 2 项：aster-api 对 from-evidence 已能按 ADR 0040 §11 D2 (a)/(c) 判定 `principal.id` / `principal.roles` 为 verified，cloud 仍签这两项，使收据 `factSources` 记录 cloud 侧来源。`principal.roles` 只在 cloud 取得的业务角色与 action 中的值规范化后逐字节相等时才签；任何 claim 与 action 的值不等时，cloud 在签名前丢弃该 claim，从不发出会被 `claim_mismatch` 拒绝的事实。
8. **不透传调用方带来的外部事实。** X4 不让 cloud 执行 API 接受调用方附带的 `facts` 并转交 from-evidence；租户在设置页登记的外部签发方，在 X4 中只服务直接调用 `POST /api/v1/guard/decisions` 的接入方。后果（写明，作为 D5 第 3 项「逐一确认有签发方配置」的显式例外）：翻转后，经 cloud 执行、声明 `eu-ai-act-high-risk` 的生产模块，其 from-evidence 在 `inputRefs` 含任何 `context.*` 路径时一律 DENY `unverified_input`；生产里能被 cloud 签发的只有 `principal.*`。翻转前的生产可验证性台账（X4 计划 G3）对每个这样的模块须写明裁定：(a) 可验证（列出覆盖其全部引用的 `issuer#kid` 与来源函数）、(b) 接受翻转后 DENY（引用本条）、(c) 阻塞翻转；存在 (c) 或缺裁定即不翻转。
9. **翻转后生产信贷试点 DENY 是预期。** 由第 8 条与 D5 第 2 项（`fixture.creditPilot` 只在非生产登记）直接推出；1.0.44 列车计划阶段 C 上线冒烟中「信贷试点 from-evidence 仍得 PENDING」在 1.0.45 之后改为期望 DENY `unverified_input`。D5 第 3 项要求的「本地栈跑通（九款状态不变、`reviewers[]` 含 Credit Officer）」不变，在本地栈以 1.3.0 注册表验收，并同时验收「未附事实 → DENY 消耗证据 → 重新执行得新证据 → PENDING」的恢复路径。
10. **1.0.45 列车只承载翻转与 runtime#77。** 修订 1.0.44 列车计划 §3 中 core#245「推迟到 1.0.45」：#245 另排列车。理由：不把唯一的行为变更点与新语法的 parity 风险叠在同一次发布里。
11. **aster-lang-ts 版本为 1.6.0（minor）。** 默认注册表变化会让既有源码出现新的 error 级诊断（E708），不是补丁级变化。
12. **aster-lang-test 语料按需改档案。** 若 tier1 语料中 `expectedInputRefs` 为 `["*"]`、声明 `eu-ai-act-high-risk` 的用例在 1.3.0 下因 E708 失败，只把这些用例改为 `Profile "governed"`（`inputRefs` 照写、期望不变）；E708 本身由两引擎共享的 parity 夹具以注入注册表覆盖（§11.3 第 1 条）。实测未失败则不改。
13. **§11.6 第 16 条的裁定时点。** `Int.toString` 等「标量叶子」误报是否豁免，由翻转前生产扫描中 construct 为 `whole-input-serialized` 的命中数决定：为 0 则维持第 16 条现状直接翻转；非 0 则由用户在 G3 台账逐条裁定，若决定豁免，须两引擎同改，翻转相应推迟。
14. **个人租户也提供签发方设置页。** 落实 ADR 0040 §11 D3「个人租户同样可配」：个人设置子页与团队设置卡片共用同一组件；写权限为个人租户本人或团队 `MEMBER_UPDATE_ROLE`，读权限为个人租户本人或团队 `TEAM_VIEW`。
15. **签发方吊销导致的过期暂不发通知。** 修订 ADR 0040 §11 D4 第 4 项「`guard.approval_decided` 通知 payload 增 `expiredReason`」：X4 只在收件箱对 `expiredReason = fact_issuer_revoked` 的项显示「签发方已吊销，自动拒绝」；不发通知，因为签发方 DELETE 不返回受影响的审批，cloud 无从逐条通知。通知另案，前提是 aster-api 回传 `affectedDecisionIds`。

**1.3.0 翻转（§11.4 ⑦）的硬门**（X4 计划 Global Constraints，本小节确认）：G0 本小节已合并；G1 X4 计划阶段 A 的 cloud PR 全部合并并部署；G2 本地栈以 1.3.0 注册表完成第 9 条两项验收，且全部对真实 aster-api 验签与执行点进行；G3 生产可验证性台账覆盖扫描报告中全部声明 `eu-ai-act-high-risk` 的模块且无 (c)，扫描输入可信（cloud 导出非空、按种类计数打印、源码头部声明档案的 id 集合与扫描所得逐 id 相等）；G4 aster-api 含 #487（前缀规则）与 #492（Task 16b）；G5 列车前常规扫描在同一非空源集合上，差异只有 G3 台账列出的 E708；G6 aster-api#493（评估事件 metadata 超 4096 字符被截断，from-evidence 409 `evidence_not_approvable`）已修复并部署——翻转后高风险模块的 from-evidence 是用户可见路径。回滚顺序固定为先 cloud（ts / 注册表副本）后 aster-api（引擎 pin），避免出现编辑器报 E708 禁止保存而服务端允许的窗口。
