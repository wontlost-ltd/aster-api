# ADR 0031：编译器强制 Stable/Experimental 边界（Stability Gate）

- 状态：M1+M2 IMPLEMENTED（2026-07-14。**M1 双引擎 StabilityGate.scan** 全上线（TS ts#73 Codex 7→89 / Java 权威 core#72 Codex 86→93 / TS↔Java parity ts#74，Java 16+TS 32 测，负向证明）。**M2 服务端 enforcement** 上线 aster-api#139（Codex 5.5→92）：StabilityEnforcement 服务（strictFor 分环境 + resolveAndScan coreJson/content 带 aliasSet + enforceVersion strict→422）接 PolicyCompiler.compile（warn）+ approve/activate（含回滚，strict 拒 Experimental）+ REST W600 诊断。★content 编译失败放行（非 stability 职责），仅 coreJson 腐化+全空 fail-closed。**M3 剩余**：monorepo W600 单源注册（shared/error_codes.json generator，本仓用本地常量）+ Surface.SAVE 接保存端点 + exception 白名单/审计 + 前端 W600 展示。原 2026-07-13 PROPOSED：Codex 60→重设计检测层从 typecheck 改独立 StabilityGate 扫 Core IR）
- 决策者：待用户拍板
- 相关：[[commercial-readiness-roadmap]]（2026-07-13 重评 P0-C）、`spec-1.0-freeze-proposal.md`（Stable/Experimental/Excluded 分级源）、ADR 0009（PII 类型系统）、ADR 0016（双引擎 parity）

---

## 1. 背景与问题

推广就绪度重评（78/100）的 P0-C blocker = **spec-1.0 freeze 只是文档分级，编译器不强制**。

`spec-1.0-freeze-proposal.md` 把语言特性分了 Stable / Experimental / Excluded 三级，承诺「Stable 集 1.x 内语法+语义不变」。但**编译器对 Experimental 特性零拦截、零警告**——客户可能无意依赖 Experimental 特性（如 Workflow async），下个版本语义变了 → 规则悄悄坏，spec-freeze 的兼容承诺被稀释。

**CCO 视角**：freeze 若无编译器兑现，客户「把核心规则长期托付」的信心不成立——他们无从知道自己踩了不承诺的特性。

**用户拍板形态**：**警告默认 + strict 模式可拒**——用 Experimental 特性默认出 warning 诊断（不阻断，向后兼容现有用户 + demo 里的 Workflow），生产/审批路径可开 strict → Experimental 编译拒绝。给 CCO 硬门选项，又不破现有代码。

---

## 2. Experimental 特性精确清单（spec-freeze §3）

| # | 特性 | 表面语法 | 拦哪个精确形态 |
|---|---|---|---|
| 1 | Workflow | `Start … async` / `Wait for` | 任一 Workflow 语句/表达式 |
| 2 | 跨模块版本引用 | `Use … version N` | `Import.version != null`（版本钉版才是 experimental） |
| 3 | Effect capability 显式列表 | `It performs io [Http, Sql]` | `effectCapsExplicit == true`（裸 `@io`/`@cpu` effect 是 Stable） |
| 4 | PII 类型系统 | `@pii` + 流分析 | Core IR `PiiType` 节点 / `Func.piiLevel` 非空 |
| 5 | 执行期注解 | `@example` / `@deprecated` | `annotation.name ∈ {example, deprecated}`（`@entry` 是 Stable，`@cpu` 是 effect 非注解） |

---

## 3. 设计

### 3.1 诊断码（单源生成，双引擎自动一致）

在 `shared/error_codes.json` 加一条（generator `aster-lang-ts/scripts/generate_error_codes.ts` 同时产 TS `error_codes.ts` + Java `ErrorCode.java`，双引擎逐字一致）：

```json
"STABILITY_EXPERIMENTAL_FEATURE": {
  "code": "W600",
  "category": "other",
  "severity": "warning",
  "message": "使用了 Experimental 特性「{feature}」，其语义在 1.x 内可能变更，不进兼容承诺",
  "help": "该特性标为 Experimental（见 spec-1.0-freeze）。生产/受监管场景建议改用 Stable 等价特性，或经评审接受风险。strict 模式下此为编译错误。"
}
```

- ErrorSpec schema（generator 校验）：`{ code, category, severity, message, help }`，key=常量名。
- W6xx 段空闲（现有 W071/W074/W105/W106），W600 起用于 stability 类。
- **严禁手改生成的 error_codes.ts / ErrorCode.java**（标「勿手动修改」）——只改 `shared/error_codes.json` 跑 generator，否则双引擎漂移。这正是本 gate 要保护的一致性。

### 3.2 检测层：独立 StabilityGate pass 扫 Core IR（★Codex 审查纠正：非 typecheck）

**★关键纠正（Codex 60/100 抓出 + 我实证）**：初版设计把 gate 挂 typecheck 层是**错的**——实证 aster-api 生产路径**不走 typecheck**：
- `PolicyCompiler.compile()`（`compiler/PolicyCompiler.java:68`）= parse + `CoreLowering.lowerModule`，**无 typecheck**。
- `/api/v1/policies/compile`（`rest/PolicyEvaluationResource.java`）调 `policyCompiler.compile(...)`。
- `evaluate-source`（`runtime/DynamicCnlExecutor.java`）= parse + lowering，**零 typecheck**。
- aster-api main 里 TypeChecker/StaticAnalyzer 几乎不被生产路径调。

若 gate 挂 typecheck → 服务端 compile/保存/激活**看不到 Experimental 使用，strict 拒不了 = 假门禁**——恰是本任务要消除的「文档承诺但不强制」，反而重蹈覆辙。

**正确设计：独立 `StabilityGate.scan(coreModule, options) → StabilityDiagnostic[]`**，扫 **Core IR**（非 typecheck、非表面 AST），双引擎各实现同一五类检测，接到**所有产 Core IR 的路径**：

```
StabilityDiagnostic { code: "W600", featureId, stability: "experimental", severity, span }
```
- **扫 Core IR 的额外好处**：PII 在 Core IR 是对称的 `PiiType`/`Func.piiLevel`（表面 AST 不对称）——**顺带解决 Codex #2 层次矛盾**（全部五类统一在 Core IR 层检，无「有的 AST 有的 IR」的边界混乱）。Workflow/Import/effect/注解在 Core IR 也都保留（`Core.Workflow`/`Core.Start`/`Core.Wait`/`Core.Await`、Import version、effectCapsExplicit、annotations）。
- **接线点（enforcement surface，必经）**：
  | 路径 | 接线 | strict 语义 |
  |---|---|---|
  | `/api/v1/policies/compile`（PolicyCompiler.compile 后） | 扫 lowered coreModule → W600 进诊断 | strict=有 W600 拒（success=false） |
  | 保存前编译门禁（ADR 0030/本会话 compile 端点已接） | 复用上面 compile → W600 冒泡到保存流 | strict tenant/approval 下拒落库 |
  | **版本激活/审批**（activateVersion/approve） | 激活前扫该版本 Core IR | strict 下有 W600 拒激活（防 Experimental 进批准链） |
  | `evaluate-source` | 扫 → warning 返回；strict 下拒 | strict 拒执行 |
  | TS 浏览器 `compileAndTypecheck`/playground | 扫 lowered core → warning 显示 | 客户端只 warn（无 strict enforcement，展示用） |
- **★已编译 artifact / cache / rollback 盲区（Codex 纠正，必须写清）**：`PolicyCompiler.compile(versionId)` 命中 `PolicyArtifact`/`PolicyVersion.coreJson` 时**不 re-lower** → gate 必须**也扫已存 coreJson**（不只扫新编译源码），否则漏掉缓存/已批准版本。回滚 `activateVersionInternal` 也继承 strict gate。**已 active 的历史版本在平台升级后**：至少下次审批/激活/回滚时 gate；是否立即 retro-scan 所有活跃版本 → 另列迁移任务（与 ADR 0030 规则集升级回归呼应）。
- **`featureId`（机器可读，非仅 {feature} 文案）**：`workflow`/`version-import`/`effect-capabilities`/`pii`/`deprecated-annotation`。→ 支持未来 per-feature policy（如允许 `@deprecated` 但拒 Workflow），W600 单码 + featureId 兼顾统一诊断 + 可细化。

### 3.3 五类检测（★规避 Explore 抓的 4 个 parity 陷阱）

| 特性 | 检测（双引擎一致口径） | parity 陷阱规避 |
|---|---|---|
| Workflow | 扫 Stmt kind ∈ {`Start`,`Wait`,`workflow`} + Expr `Await`（节点名双引擎逐字一致，注意 `workflow` 小写） | 无（干净对称） |
| 跨模块版本 | `Import.version != null`（Java `Decl.Import.version:Integer` / TS `Import.version?:number`） | 与 `aster.modules.enabled`（aster-api 侧、gate link 非 parse）独立互补 |
| Effect capability | **`effectCapsExplicit == true`** | ★不用 `effectCaps.length`——TS 从 `@io` 自动推断 caps，`.length>0` 会假阳性；只 `effectCapsExplicit` 可靠 |
| PII | **Core IR `PiiType` kind / `Func.piiLevel` 非空** | ★不在表面 AST 检——Java 是 `Annotation{name:"pii"}` on Type，TS 是专门 `TypePii` 节点，不对称；Core IR 层双引擎都是 `PiiType` kind，对称 |
| 注解 | `annotation.name ∈ {example, deprecated}`（大小写不敏感，两引擎都不归一大小写） | ★`@cpu` 是 effect 不是注解（在 `effects` 里存 `"cpu"`），不在注解列表找；`@entry` 是 Stable 排除 |

### 3.4 strict 语义（★Codex #3：分环境默认，非全局 opt-in）

单一全局 `strict=false` 默认对受监管生产不够硬——管理员忘开 strict，Experimental 规则仍进批准链。**按 enforcement surface 分默认**：

| 场景 | strict 默认 | 理由 |
|---|---|---|
| dev / playground / 裸 compile | **false（warn）** | 开发体验，不阻断探索 |
| **版本激活 / 审批（activate/approve）** | **true（拒）** | 批准=托付生产，Experimental 不该无声进批准链 |
| 保存到 regulated tenant | **true（拒）** | 受监管租户默认硬门 |
| 普通 tenant 保存 | false（warn） | 非监管场景兼容 |

- 放行 Experimental 须**显式 tenant/policy exception**（`aster.stability.experimental-allow` 白名单）+ **进审计**（谁、哪个特性、为何放行）——不是静默 warn 过去。
- **接线**：`StabilityGate.scan(core, { strict, allowExperimental })`；aster-api 各 surface 传对应 strict（activate/approve 硬编 true 除非 exception）。TS 客户端 surface 传 strict=false（展示用，enforcement 在服务端）。

### 3.5 报告语义
- 默认（warn surface）：W600 warning，编译/保存成功、诊断列表含 warning（Monaco 显黄标；playground 诊断面板显示）。
- strict surface（激活/审批/regulated tenant）：有 W600 → 该操作**拒绝**（compile success=false / 拒保存 / 拒激活），除非 exception 白名单放行（进审计）。
- 每命中一个 Experimental 特性一条 StabilityDiagnostic，含机器可读 `featureId`（workflow/version-import/effect-capabilities/pii/deprecated-annotation）+ span 指到触发节点。exception 审计按 `featureId` 存（非仅人类文案）。

---

## 4. 分阶段（★Codex #4：parity 是 M1 exit criterion，非 M3）

- **M1（诊断码 + StabilityGate + 双引擎 parity）**：`shared/error_codes.json` 加 W600 → generator → 双引擎 `StabilityGate.scan(coreModule)`（5 类检测全在 Core IR 层）+ **M1 exit 硬门：双引擎 parity corpus**（5 类 positive 各一样本 + Stable 等价 false-positive 对照，验 TS/Java 同源码产同 featureId/severity/span）。本 gate 本质就是双引擎稳定性门禁，parity 不一致的 gate 不可先合。
- **M2（服务端 enforcement + strict 分环境）**：接 `/compile`、保存门禁、**激活/审批**（strict 默认）、evaluate-source；exception 白名单 + 审计；测（activate strict 拒 Experimental、exception 放行进审计）。
- **M3（前端展示）**：aster-cloud playground 诊断面板/Monaco 显 W600 warning（W600 经既有 diagnostics 通道，多半零改动）。

## 5. v1 不做
- 细粒度「哪些 Experimental 子特性可单独 opt-in」（v1 一个 strict 总开关，不做 per-feature 白名单）。
- 自动改写 Experimental→Stable（不自动改客户规则）。
- Excluded 特性拦截（那些本就 parse 不了/未实现，无需 gate）。

## 6. ★双引擎 parity 风险点（Explore 实证，实现须专门测）
1. **PII 必须 Core IR 层检**（表面 AST 不对称）——test 须验 Java/TS 对同一 `@pii` 源码产同一 W600 同位。
2. **effect capability 用 `effectCapsExplicit`**——test 须含「裸 `@io`（Stable，不触发）vs `@io [Http]`（Experimental，触发）」双引擎一致。
3. **注解大小写**——两引擎都不归一，`@Example`/`@example` 都要匹配（大小写不敏感），test 双引擎一致。
4. **enforcement surface 边界（★Codex 纠正，取代旧 typecheck 描述）**：所有 production compile surface（`/compile`、保存、激活/审批、evaluate-source）**必须调 `StabilityGate.scan`**；纯内部 compile helper 若不调 scan，**不得**作为 enforcement surface（不能靠它拦）。W600 是否出取决于该路径是否调 scan，与是否 typecheck 无关。
5. **诊断单源**——只改 `shared/error_codes.json`，禁手改生成文件（否则漂移）。
6. **★Core IR 五类信号保真（M1 必证，Codex 纠正）**：Workflow/PII 有证据（`CoreModel.PiiType` 序列化测试存在），但 **Import version / `effectCapsExplicit` / annotations 是否在 lowered Core IR 完整保留，本地无法验（CoreModel 来自依赖包）**——M1 必须用真实双引擎 corpus 证明，不能只靠设计文字。**尤其 `effectCapsExplicit`**：若 lowering 后只剩 caps 列表、丢「显式 vs 推断」标记，会重掉进 false-positive 陷阱。若某信号 lowering 后不保真 → 该类 fallback 到表面 AST 检（但须双引擎各自处理不对称，见 PII）。
7. **★span parity 可能过严（Codex 纠正）**：Core IR 未必保留完整源位置。M1 parity 先验 `featureId/severity/kind` 一致；**span 只要求「存在且合理」不要求逐字节同**（否则实现卡在诊断定位而非门禁本体）。若需精确 span，先把 span 加进 Core IR（独立工作）。

## 7. 回滚
全是**新增**（新诊断码 + 新 checker pass + 新 flag），不改现有类型检查/求值/编译逻辑。gate 可 config 关（strict=false 默认已是「只 warn 不阻断」，最保守）。W600 是加法诊断码，不 bump spec 版本。

## 8. ★本地环境约束（诚实记录，非甩锅）
实证确认 `shared/error_codes.json` **不在** aster-lang-ts/core 的任何 git 分支/历史/远程/本地工作树，也不在 ~/IdeaProjects 任何仓——它是构建时注入的单源，仅在合并 monorepo/CI 上下文存在。standalone clone 无法 checkout（git 无 ref）。故本 ADR 是**完整实现规格**（含精确文件行号/节点名/parity 陷阱/schema），供有 monorepo 上下文时按 M1-M3 执行；不在 standalone clone 手改双引擎生成文件（会破单源契约 + 制造漂移，与本 gate 保护的一致性目标矛盾）。

## 9. 为什么这对 CCO 有价值
把 spec-1.0 freeze 从「文档承诺」变「编译器兑现」：客户用 Experimental 特性时**编译器主动警告**（strict 模式拒绝），不再无意踩不承诺的特性。CCO 的「freeze 信用被稀释」担忧被消除——「Stable 集我签字长期托付；Experimental 编译器会拦住我」。这是把稳定性承诺从纸面落到工具链的关键一步。
