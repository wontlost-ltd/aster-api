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

- 同一规则同一要求、同一键只报一次：`missing @id` 与框架要求每条规则至多一条，`unregistered control {control}` 按每个不同的控制点键各报一条。W700/W704 照常报出（警告），E706 是档案层面的额外错误。
- aster-api 编译通道：E705/E706 **保持 error 严重级**（其余治理码仍按 ADR 0045 §9.1 降为 warning）。编译 `success` 仍为 true；aster-cloud 现有保存门按 `severity === 'error'` 拒绝保存，因此声明了档案且不合规的模块无法保存、建版或审批。未声明档案的模块不受影响，所以今天能保存的策略明天仍能保存。
- 检查器失败的取舍：治理检查器抛运行时异常时，编译 `success` 仍为 true，aster-api 以 error 级别记录该异常。未声明档案的模块沿用 ADR 0045 的降级，结果中不带治理诊断；声明了档案的模块改为得到一条无源码位置（1:1）的 E706 error，消息说明治理检查未能执行、档案无法验证，档案保存门因此拒绝保存（fail-closed）。
- 编译检查不可达时的取舍：aster-cloud 保存门调用编译端点遇到超时（408）、5xx 或网络失败时，若源码在 `Module` 行之后紧跟档案声明行，则与上游限流（429）一样拒绝保存并返回 503 `compile_unavailable` 与 `Retry-After`；未声明档案的源码仍照旧放行。

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
- `Otherwise` 必须是所在块的最后一条语句，否则为**解析错误**（消息 `Otherwise must be the last statement of its block`，无错误码）：两个引擎都没有带码的解析期诊断通道，原设想的 E707 撤销。参数的类型与个数检查沿用降糖后对 `Verdict.*` 的既有检查（E703）。
- 语法糖与长写法的 IR 在去除 origin 后相等（语法糖把 If 与 Return 放在同一行，origin 行号不同）；parity 对比以此为准。
- TS 格式化器从 AST 打印：保留 `Profile` 行，语法糖按长写法输出（语义不变）——已知限制，见 §10。

## 6. 决策五：向 aster-api、aster-cloud 与证据传播

- aster-api 编译响应带 `profile`（模块声明的档案 id，无则省略）。
- 评估证据：执行时从模块 IR 读出 `profile`，与 `ruleId`/`controls` 一样写入评估证据与执行行；证据包条目带 `profile`。manifest 升 `schemaVersion: '5'`，新增 `profilesUsed: [{ id, title }]` 与所用注册表版本；v1–v4 只读。
- aster-cloud：编辑器与策略详情页显示档案徽标；报告页对照视图列出所用档案。编辑器徽标在实施计划中漏列，于 Task 8 修复轮补上：编辑器侧栏 Decision 页签从浏览器端 Core IR 读取 `profile`，显示「档案 + 注册表标题」；已安装的 aster-lang-ts 尚不产出 `profile` 时不显示（见 §7）。详情页徽标经 `/api/policies/compile` 取得档案（带 aliasSet，独立限流桶 `policy-profile:<user>`）。
- 信贷试点：`pol-credit-pilot` 增 v3（`pv-credit-pilot-3`，成为在线版本），即 v1 的五个分支用语法糖逐条改写并声明 `Profile "eu-ai-act-high-risk".`（§5 示例仅为示意，不得省略 DTI 分支或改变边界）；What-If 仍对比 v1→v2。试点脚本仍须得到与 ADR 0044 相同的九款状态。
- 档案只沿评估路径（`RuleMetadata` → 三条评估结果 → `EvaluationEvidence` 与 `EvaluationResponse`）传播；guard 决策路径不在本 ADR。

## 7. 兼容与发版

- 未声明档案、未使用语法糖的模块：语法、IR、诊断、保存行为均不变。
- 新关键字可能与用户标识符冲突：实施时对语料、模板、示例做冲突扫描；冲突处理按既有软关键字规则。
- 与子项目 5 相同：浏览器端编辑器要等 aster-lang-ts 与 aster-lang-locales（含新词法与注册表）发布后才能看到；aster-api 编译通道经 composite build 本地即可用。发布由用户决定。
- aster-cloud 当前锁定的 aster-lang-ts 为 1.0.28，解析不了 `Profile` 与语法糖；编辑器在客户端编译有错误时禁用保存，所以在 cloud 于发版后升级 aster-lang-ts 依赖之前，声明了档案或使用语法糖的模块无法从 UI 保存。服务端保存路径、API 与试点种子数据现在就可用。

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

六个新 `SemanticTokenKind` 按下表落地（aster-lang-core 内置 en、aster-lang-locales en/zh/de、aster-lang-hi），均列入 Java `OPTIONAL_KINDS` 与 TS `CI_OPTIONAL_KINDS`，发版列车完成后移出：

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
- 上一条的 api 侧处理（终审 §4 (b)）：`RateLimitFilter` 对携带内部调用头的请求复用 `InternalCallerFilter` 的头预检与 canonical v3 验签（不消费 nonce），签名有效时按 `rest:internal:cloud-bff:<X-Tenant-Id>` 分桶，额度为 `aster.ratelimit.internal.max-requests`（环境变量 `ASTER_RATELIMIT_INTERNAL_MAX`，默认每窗口 600 次，窗口沿用 `aster.ratelimit.rest.window-seconds`）；验签不通过或未带内部头的请求仍按 IP（或已建立的租户上下文）计入 `aster.ratelimit.rest.max-requests`。限流 filter 早于 `InternalCallerFilter` 执行，选择就地验签而不调换顺序，是为了让未认证洪泛仍在最前面被拦下。cloud 目前只对 evaluate-source、What-If 与 guard 路径签内部头；`/api/v1/policies/compile` 等其余服务端调用要改用租户桶，须由 cloud 对这些调用同样签发内部头（api 对这些路径只用签名来分桶，不改变鉴权）。签名在 5 分钟时间戳窗口内可被重放来消耗该租户的内部额度，受保护路径上的重放仍由 `InternalCallerFilter` 的 nonce 去重拒绝。
- 栈夹具会老化：guard 待审批 24 小时后过期，`pol-adr0041-team` 种子执行的审批已过期；验收时将该条审批恢复为 PENDING，并用 aster-guard `scripts/e2e-local.mjs` 补一条 `e2e-` 待审批。

## 10. 后续（不在本 ADR）

- TS 格式化器保留语法糖原样输出。
- 发版：新词法 kind 要走 ADR 0029 发版列车（core → locales/hi → ts）；本地验证经 mavenLocal 构建的词法包完成。
- 子项目 6b：Cedar 编译目标（REQUIRE_APPROVAL/ESCALATE 的表达方式、可翻译子集与 schema 生成）。
- 租户级「强制档案」（租户要求其所有策略必须声明某档案）。
- 档案要求的扩展（如强制 `@reason`、禁止某些结论类型）。
