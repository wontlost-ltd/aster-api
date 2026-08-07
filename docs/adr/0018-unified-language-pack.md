# ADR 0018 — 统一语言包（界面显示语言 + 策略编写语言）

Status: **EXPLORATORY（探索/未拍板）— 部分单向门已拍板（见下），dev 重写为独立 epic**
Date: 2026-06-16

## 用户已拍板的决策（2026-06-16 追问后）

1. **保留 next-intl，不放弃** —— 实测 `next-intl@4.13.0` 类型：`getRequestConfig` 回调签名为
   `(params) => RequestConfig | Promise<RequestConfig>`（**原生 async**），`messages` 字段对来源
   无限制，且当前 `i18n/request.ts` 已是 `async` + `await import()`。把 `await import()` 换成
   `await fetch('/api/v1/messages/<locale>')` 即可实现运行时远程加载，next-intl 这层**不需改也不需弃**。
   链路：`request.ts`(async fetch) → `getMessages()` → `NextIntlClientProvider messages={...}`（SSR 序列化传客户端）。
   → **砍掉了原 Phase 2 的"自研 loader"风险与工作量。**
2. **cloud 2195 键 → 独立 messages-manifest，不进 aster-lang-locales JVM jar**（理由见"方案对比"补充）。
3. **aster-lang-dev 重写为独立 epic**，但前 3 phase 的 i18n 架构必须**预留 dev 接入点**（locale 注册表 + 运行时 messages loader 抽成站点无关的可复用 lib）。

## messages 分发实现设计（已拍板 2026-06-17）

原"剩余待拍板"里的 manifest 坐标 / 缓存失效 / 授权 / 事件总线四项均已定，构成 Phase 2 的落地契约。

### ① 消息源与分发拓扑：后端权威 + KV 边缘缓存（Cache-Aside）

```
浏览器
  ↓ next-intl getRequestConfig: await fetch
Cloudflare Worker (cloud 前端)
  ↓ miss                              ← L1: KV(CACHE), 版本化 key, 全球边缘
  └─ 后端 /api/v1/messages?locale=xx  ← L2: 权威源(system of record)
       ↓ 内存 Map(零 DB / 零阻塞)
     locales 的 ui-messages artifact(启动加载, 非进 JVM jar)
  ↓ 任意环节失败
  fallback: 内嵌 en(build 期 bundle) ← 绝不白屏
```

- **`/api/v1/messages` = 权威源**。aster-api **启动时**从 locales 的 `ui-messages` artifact（独立 npm/JSON 制品，**不进 JVM catalog**，避级联）读入内存 `ConcurrentHashMap<locale, Messages>`，端点直接吐内存——**零 DB、零阻塞**（不踩 [[policy-storage-db-backed]] 的阻塞 JPA 必须 runSubscriptionOn 的坑）。
- **KV = Cache-Aside 旁路缓存**：前端先查 KV，miss 回源后端，回填 KV。
- **R2** 仅在 messages 体积大或需整包下发时用；小 JSON 用 KV 足够。
- **内嵌 en = 终极兜底**：build 期就在 Workers bundle，任何 fetch 失败降级到它，**绝不白屏**（同 [[hindi-full-support]] dashboard 崩溃教训：hot-path fail-open 到安全默认）。

### ② 热更新事件总线：复用现有 Redis pub/sub（不引入 Kafka）

> **关键事实**（已核实 `aster-api/build.gradle`）：aster-api **无 Kafka / 无 SmallRye Reactive Messaging**，但**已有 Redis**（`quarkus-redis-cache`，注释原文 "Redis for **distributed invalidation**"）+ WebSocket/SSE + scheduler。引入 Kafka = 加 broker（K3S 新有状态组件）+ extension + **GraalVM native 兼容性验证**（本项目 native 编译），与现有零消息中间件架构不匹配。

```
更新源(locales 发布 / admin 写入口)
  → 发 Redis pub/sub: { channel: "aster.i18n.messages.updated", locale, newVersion }  ← 瘦事件
  → 所有 aster-api pod 订阅
  → 收到 → 回源拉取该 locale 新 messages → 原子替换内存 Map[locale] + bump 内存 manifestVersion
  → /api/v1/messages 继续吐内存
  → 前端下次 fetch 看到新 version → KV key 自然换 → 边缘自然刷新
```

- **瘦事件**（只带 `locale` + `newVersion`，api 回源拉取）：payload 小、回源点单一好审计；事件 = "失效信号"非"数据载体"。
- 达成"**不重启即热更新 messages**"（你最初想用 Kafka event 的目的），但复用已在用的 Redis，**零新中间件、零 native 新风险**。
- Kafka 仅在需要**事件持久化/重放/多消费者审计流**时才值得引入——就 messages 失效广播这个用例属 overkill。

### ③ KV 缓存失效：版本化 key + 短 TTL 兜底

```
KV key = messages:{locale}:v{manifestVersion}   (如 messages:en:v3)
后端版本一升(Redis 事件触发) → key 自然变 v4 → 旧 v3 TTL 过期自然清，无需主动 purge
TTL(如 5min) 作为漏掉事件时的兜底上限
```

天然避免 stale：无需调 CF API 主动 purge（purge 失败本身=stale 风险源）。

### ⑤ manifest 承载形态与开发工作流（已拍板 2026-06-17）

- **messages 归位 aster-lang-locales = 唯一源（system of record）**。把 cloud `messages/{en,zh,de,hi}.json`（38 namespace，en 129KB）迁入 `locales/<lang>/src/main/resources/ui-messages/`，与现有 `overlays/`、`lexicons/` 同级。**真正的单一语言包**：关键词 + 界面文案同仓。
- **走独立 npm/JSON 发布通道，不进 JVM jar**（避 [[hindi-full-version]] 级联）。locales 仓现为 JVM-only Gradle（maven-publish），需**新增一个导出 manifest 的发布管线**（Gradle Copy/Jar task 聚合各 locale 的 ui-messages → 单一 JSON manifest 制品 + npm 包）。
- **cloud 开发工作流**：locales 为唯一源，cloud **构建期从 locales npm 包/manifest 拉取本地缓存副本**，作 ① 运行时 fetch 失败的 fallback ② 本地 dev 离线可用。**改文案 = 改 locales 仓 PR → 发包 → cloud 同步**（代价：多一道发布；收益：单一源、无漂移）。
- 现有 `lsp-ui-texts.json`（~30 编译器/编辑器 UI 文案，`{version, texts}` 形状）保持原 overlays 位置不动；新的产品 UI messages 是独立的 `ui-messages/` 资源。两者都在 locales 仓，但分目录、各自 manifest。

### ④ /api/v1/messages 授权：公开只读 + locale 可用性开关约束

```
GET /api/v1/messages?locale=xx
  → 复用 platform∩team∩backend 三重交集([[platform-team-language-gating]])
  → locale 未启用 → 404
  → 已启用 → 返回(messages 是非敏感公开 UI 文案, 无需认证)
```

与 `/api/v1/lexicons` **同款授权边界**，一致性好；且保住"管理员设语言可用性"这个产品卖点——关掉某语言后 `/api/v1/messages` 对该 locale 一并 404。

## 背景与目标

今天 Aster 生态里"语言"分散在**三套互不相干的 i18n 体系**里：

| 体系 | 是什么 | 技术 | 加载机制 |
|------|--------|------|---------|
| **A. 策略编程语言**（lexicon 包） | 78 个 CNL 关键词翻译 + 标点 + canonicalization + **overlays(LSP/诊断界面文案)** + 领域词汇 | JVM `LexiconPlugin` SPI（`aster-lang-locales` 多模块 + 独立 `aster-lang-hi`）；npm 镜像 `aster-lang-ts`（`@generated`） | **运行时 `ServiceLoader` + 后端 `HotPlugLexiconLoader` 真热插拔**；前端 npm 内嵌 |
| **B. aster-cloud 网站界面** | 2195 个 UI 文案键（38 namespace） | `next-intl` `messages/{en,zh,de,hi}.json` | **构建期** `await import()`，编进 Cloudflare Workers bundle |
| **C. aster-lang-dev 文档站** | 56 个 md 文档 × 每 locale 一套 `docs/<lang>/` + 10 个 Vue 组件 | **VitePress**（构建期静态生成）+ 外部 `@aster-cloud/glossary` 注册 locale | **构建期**静态生成 |

**问题**：加一门语言要在三处分别动手（lexicon 包发布 + cloud `messages/*.json` + dev `docs/<lang>/` + glossary 注册），且语言列表三处独立维护、易漂移。已经踩过的坑：加 hi 时三套各改一遍 + 触发 JVM 全生态版本级联。

**用户目标（本 ADR 的需求）**：
1. **统一语言包** —— 界面显示语言 + 策略编写语言用同一套语言体系，一处维护。
2. **所有前后端文本统一进语言包**（用户明确：界面文案也进语言包）。
3. **语言包可热插拔** —— 运维上传/删除一门语言即生效。
4. **管理员可设语言可用性** —— 平台/团队级开关控制哪些语言对用户可见（**此项已实现**，见 [[platform-team-language-gating]]）。
5. **aster-lang-dev 用 aster-cloud 技术栈重写**（Next.js 替换 VitePress），统一两个站。
6. **必要时放弃 next-intl**。

## 核心张力（决定方案上限的根本约束）

**三套体系的运行时机制根本不同**：

- **lexicon 包（后端 JVM）= 真热插拔**：`ServiceLoader` 运行时发现，`HotPlugLexiconLoader` 上传/删 jar，`/api/v1/lexicons/stream` SSE 推送。
- **cloud 界面（Cloudflare Workers）= 构建期固定**：Workers 无文件系统，`messages/*.json` 通过 `await import()` 编进 bundle。加一门语言**当前必须重新构建部署**。
- **dev 文档（VitePress）= 纯静态**：构建期生成，加 locale 必须重新构建。

→ **"网站显示语言热插拔"与"Workers/静态站构建期固定"物理冲突**。这是必须正视的硬约束，不能假装绕过。

**但有两个有利的现实，让"前端动态加载"变得可行（不是从零）**：
1. **cloud 前端已经在运行时拉后端数据**：`useAvailableLexicons` 已订阅 `/api/v1/lexicons/stream` SSE，前端**已有**运行时获取"哪些语言可用"的机制——只是 UI 文案本身还是构建期 import。
2. **Workers 有运行时存储绑定**：`wrangler.toml` 已有 **KV (`CACHE`)** + **R2 (`ASSETS`)** + **Hyperdrive(Postgres)**。messages 完全可以改成运行时从 KV/R2/后端 `fetch`，而非编进 bundle。
3. **lexicon 包里已经有 UI 文本**：`overlays/lsp-ui-texts.json`（编辑器界面文案）、`diagnostic-messages.json`（诊断消息）已经随语言包走——"语言包供部分界面文本"在数据层已成立。

## 目标架构（理想终态）

```
                  ┌─────────────────────────────────────────────┐
                  │  统一语言包 (per-locale)                      │
                  │  ┌─────────────┬──────────────┬───────────┐ │
                  │  │ cnl-keywords│ editor-overlays│ ui-messages│ │
                  │  │ (78 关键词) │ (lsp/diagnostic)│ (网站文案) │ │
                  │  └─────────────┴──────────────┴───────────┘ │
                  │  JVM jar (SPI) ⇄ npm 镜像 ⇄ JSON manifest    │
                  └───────┬──────────────┬───────────┬──────────┘
                          │              │           │
         后端热插拔 ◄─────┘              │           └──► 前端运行时 fetch
         (ServiceLoader +               │                (KV/R2/后端 API,
          HotPlugLoader)        统一 locale 注册表        非构建期 import)
                          │      (单一真相源:                    │
                          │       哪些语言存在+显示名+状态)       │
                          ▼              │                       ▼
              /api/v1/lexicons ◄─────────┴──────────► /api/v1/locales
              (编程语言可用集)        管理员可用性开关      (界面语言可用集)
                                    (平台∩团队, 已实现)
```

**关键设计**：
- **单一 locale 注册表**（解决"语言列表三处漂移"）：一个权威清单（语言 id + 显示名 + 方向 + 状态），三套体系都从它读。最自然的位置 = **后端 `/api/v1/locales`**（lexicon 注册表已经是这个角色的一半）。
- **界面文案进语言包，但分层**：`ui-messages` 作为语言包的一个新 section（与现有 `overlays` 同级），但 **cloud 网站的 2195 键不必全塞进 JVM jar**——它们与产品紧耦合，更适合作为**独立的 npm/JSON manifest** 随语言包版本一起发，前端运行时 fetch。
- **前端运行时加载**：`next-intl` 的 `getRequestConfig` 把 `await import(messages)` 改为**运行时 fetch**（从后端 API 或 Workers KV，按 locale）。这样后端加一门语言 → 前端 fetch 到新 messages → **无需重新构建即可显示新语言**（真热插拔贯通到前端）。
- **dev 站重写为 Next.js**：与 cloud 同技术栈，复用同一套运行时 messages 加载 + 同一个 locale 注册表 + 同一个 playground（React 版）。

## 方案对比（三种深浅）

### 方案 1：轻量统一 —— 单一 locale 注册表（不动文本存储）
只加**一个权威 locale 注册表**（后端 `/api/v1/locales` 返回 id/name/dir/状态），三套体系都从它读"有哪些语言"。文本内容仍各自维护（lexicon 包 / cloud messages / dev docs 不变）。
- ✅ 风险最低、改动最小（~2 周）；解决"语言列表漂移"这个最痛的点。
- ❌ 没解决"文本一处维护"；加语言仍要三处填文本（但至少列表统一了）。

### 方案 2：中度统一 —— 前端 messages 运行时加载（推荐起点）
方案 1 + 把 **cloud 界面 messages 改为运行时 fetch**（从后端 API / Workers KV，非构建期 import）。`ui-messages` 作为语言包的一个 section（独立 manifest 随包发），后端 `/api/v1/messages/<locale>` 暴露。
- ✅ **真正打通"后端加语言 → 前端无需重部署即显示"**（前端热插拔）；管理员开关天然复用（已实现）。
- ✅ 文本仍可渐进迁移；不强求一次性把 2195 键塞进 JVM jar（用独立 manifest 解耦）。
- ⚠️ next-intl loader 重写 + Workers KV/R2 存 messages + fallback 链 + 缓存失效；中等架构改动。dev 重写为 Next.js 后复用同一机制。

### 方案 3：彻底统一 —— 所有文本进语言包 jar
把 cloud 的 2195 键、dev 的文档全塞进 JVM 语言包，单一来源。
- ✅ 最彻底的"一处维护"。
- ❌ **过度耦合**：网站界面文案/文档与产品迭代紧耦合，塞进 JVM jar 后每次文案改动都要发 JVM 版本（且 JVM/TS 双发布）；版本级联噩梦。**不推荐**——界面文案的迭代节奏 ≠ 编程语言关键词的稳定节奏，强行同包违反关注点分离。

## 推荐路线（分阶段，单向门标注）

> 用户拍板：**方案 2 主干 + 方案 1 地基**。cloud 2195 键走**独立 messages-manifest**（不进 JVM jar，避级联）。next-intl 保留（实测原生支持运行时远程加载）。dev 重写为独立 epic（前 3 phase 预留接入点）。方案 3 的"全塞 jar"不取（过度耦合）。
>
> **为什么 2195 键不进 aster-lang-locales JVM jar（用户确认后的论据）**：
> ① **版本级联噩梦** —— aster-lang-locales 走 platform 单一 catalog 版本，文案改一字 → 发 JVM 包 → 触发全生态 1.0.x 级联（实证见 [[hindi-full-support]]）。界面文案迭代频率 ≫ 编程关键词，每改必级联 = 灾难。
> ② **JVM/TS 双发布** —— locales 包同时供 JVM(后端)+npm(前端)，2195 键塞进去每次改文案两套管线都跑。
> ③ **关注点错配** —— locales 包职责是**稳定的 78 个 CNL 关键词**；网站文案是**高频迭代的产品 UI**，强行同包焊死两种节奏。
> **折中**：2195 键做成独立 manifest（如 `@aster-cloud/i18n-messages`），**与语言包同步版本号但走独立发布管线** → 语言列表/元数据统一(从注册表读) + 文案可独立高频迭代(不触发 JVM 级联) + 仍一处维护。

**Phase 0 — 单一 locale 注册表（地基，低风险）** ✅ **已完成（本已存在）**
- `/api/v1/lexicons` 返回 `{id,name,direction}`，`LexiconRegistry.availableIds()` 已过滤软下线 = 后端可用性层。
- cloud 四重交集 compiled∩backend∩platform∩team（`team-locales.ts`/`lexicon-availability.ts`/`useAvailableLexicons`）= Hindi 工作遗产，早已上线。**P0 零新工作**。

**Phase 1 — 语言包加 `ui-messages` + manifest（数据层统一）** ✅ **已完成**
- messages 归位 `aster-lang-locales/locales/<lang>/.../ui-messages/<id>.json`（en/zh/de，38 ns）+ `aster-lang-hi`（hi-IN，12 ns 部分）。
- `exportUiMessages` task 聚合为单一 manifest 制品（envelope 带 sha256/bytes）+ `verifyUiMessagesParity`（PR-blocking）。
- `@aster-cloud/ui-messages`(+`-hi`) npm 包，release.yml v* tag 发布。**走独立 npm 通道，不进 JVM jar**。
- 两仓 `./gradlew build` 全绿。分支：locales `feat/ui-messages-manifest`、hi `main`（已 commit，未发版）。

**Phase 2 — messages 运行时加载（前端热插拔）** ✅ **已完成**
- 后端：`UiMessagesService`（classpath 加载内存 + Redis pub/sub 热刷新，channel `aster.i18n.messages.reload`）+ `MessagesResource`（`GET /api/v1/messages/{locale}`，授权同 `/api/v1/lexicons`，ETag/304）。**复用现有 Redis，不引入 Kafka**。aster-api `feat/messages-endpoint-p2`（已 commit）。
- 🔴 **发版前发现并修复的生产 bug（2026-06-18，PR #63）**：`MessagesResource` 声称"授权同 `/api/v1/lexicons`"，但 `TenantFilter` + `RequestSignatureFilter` 的 perimeter 豁免列表只有 lexicons、**漏了 messages** → 生产 `GET /api/v1/messages/en-US` 被 400 拒（缺 X-Tenant-Id）→ 前端 messages-loader 匿名 fetch 拿 400 → 静默 fail-open 回退内嵌英文 → **P2"后端改文案前端即显示"在生产从未生效**（fail-open 不白屏故一直未察觉）。修复=两 filter 加 `MessagesPathMatcher.isSingleLocaleMessagesPath` 共享豁免（单段 locale 精确匹配，拒多段/路径穿越，Codex 安全审 94/100）。教训：**`MessagesResourceTest` 直接 new resource 绕过 filter 链，故 perimeter 缺口要在 filter 层测**。
- 前端：`messages-loader.ts`（KV→后端→内嵌兜底，**fail-open 绝不白屏**）+ `request.ts` `await import()`→`await loadMessages()`。aster-cloud `feat/i18n-runtime-messages-p2`（已 commit）。
- ✅ **保留 next-intl**（实测原生 async loader）。后端 14 测试 + 前端 12 测试 + 152 i18n 测试全绿。
- ✅ 打通：后端加语言/改文案 → 前端 fetch 到 → 无需重部署显示。

**Phase 3 — 新建 aster-dev（Next.js 重写）** 🔨 **里程碑 1 完成（脚手架 + PoC）**
- **决策（用户拍板）**：不在旧 `aster-lang-dev` 原地改，而是**新建 project `~/IdeaProjects/aster-dev`**（git init，已 commit 28 文件）。
- ✅ 里程碑 1：Next.js 16 App Router 骨架 + `[locale]` 路由 + next-intl 中间件 + 运行时 `messages-loader`（复用 P2 fail-open）+ 首页（VitePress index.md hero 迁移 4 locale）+ docs MDX PoC + **AsterPlayground React PoC**（Vue 1074 行→React+CodeMirror，evaluate 走后端，保持可信执行链）。typecheck/build(14 路由)/eslint 全绿，build 日志实证 fail-open。
- ⬜ 后续滚动里程碑（各独立 PR）：批量迁移剩余 63 篇文档（md→mdx）+ 9 个 Vue 组件 + playground 全功能 + 语言切换器接后端 `/api/v1/lexicons` 可用性 + `pathnames` 本地化 URL + glossary 门去留 + 退役旧 VitePress 站。
- ⚠️ **量级**：实测 64 md × 4 locale + 10 Vue 组件，是 ADR 标注的"数周 epic"。里程碑 1 证明路线可行，剩余按批次滚动推进。

## 风险与现实

1. **JVM 版本级联**：语言包加 section = 内容变更 → 又触发全生态 1.0.x 级联（见 [[hindi-full-support]] 的级联踩坑）。**缓解**：把 `ui-messages` 做成独立 manifest（不进 JVM jar）可避开 JVM 级联，只走 npm/JSON 节奏。
2. **next-intl 取舍**：先验证 next-intl 是否原生支持 async/远程 message loader——支持则保留（Phase 2 大幅简化），不支持才自研。
3. **dev 重写是真大工程**：VitePress 的 md/特性/playground 移植到 Next.js 不是平移；建议**最后做**、可独立成 epic，不阻塞 Phase 0-2。
4. **Workers 运行时 fetch 的冷启动/缓存**：per-locale messages 从 KV/R2 拉要做好缓存 + fallback，避免每请求打后端。
5. **glossary locale-parity 门**：dev 重写后这套外部强约束怎么办（迁移 or 退役）需决策。

## 安全性（热插拔 + 运行时加载引入的新攻击面）

统一语言包把"热插拔"作为卖点，必须正视它引入的安全面。**实测当前实现**（`LexiconAdminResource.java` / `HotPlugLexiconLoader.java`）：

### 当前热插拔上传的防线画像（实测）

| 防线 | 状态 | 实测依据 |
|------|------|---------|
| **① HMAC 签名认证** | **✅ 默认强制** | `verifyHmac`（先于 allowlist 执行）：无 `aster.plan-gate.hmac-key` → 直接 403(`hmac_not_configured`)；要求 `X-Internal-Signature` + `X-Aster-Timestamp` + `X-Aster-Nonce`（时间戳防重放 + nonce 去重）。**这是 RCE 的真正第一道门** —— 拿不到 hmac-key 根本调不通上传端点。不受 `signature.enabled` 开关影响（独立硬校验） |
| **② SHA-256 allowlist** | **✅ 生产默认强制（fail-closed，hardening 已落地）** | `aster.lexicon.upload.require-allowlist` 默认 `true`（`%dev`/`%test` 覆盖为 `false`）。prod 下空 allowlist → `checkAllowlist` 返回 `REQUIRED` → 403(`allowlist_required`)，**不再放行**；已配但 sha 未命中 → 403(`not_in_allowlist`，constant-time 比对）。dev/test 仍 fail-open 便于开发。判定抽为纯静态 `LexiconAdminResource.checkAllowlist`，`LexiconAdminAllowlistTest` 覆盖 8 例（prod 空拒/命中/未命中、dev 空放行、多条目/大小写/空白） |
| **③ body-sha256 一致性** | ✅ 默认 | 上传请求里声明的 sha256 必须与实际接收 body 一致（防替换 jar 内容），且签名覆盖 body-sha → 防中途篡改 |
| **④ 体积/扩展名校验** | ✅ 默认 | ≤50 MiB、必须 `.jar` |

### 准确结论（写实，不乐观）

- **不会被随便 RCE**：上传端点 HMAC 默认强制，攻击者拿不到 `hmac-key` 进不来。
- **但纵深不足**：`URLClassLoader` 加载上传 jar = **任意代码执行**（语言包含 Java transformer 类）。**SHA-256 allowlist 是可选加固、默认关闭**——一旦 HMAC key 泄露（或持密钥的内部组件被攻陷），**没有 allowlist 兜底就是 RCE**，纵深防御缺一层。

### 其它运行时加载风险（messages 改运行时 fetch 后）

| 风险 | 缓解 |
|------|------|
| **messages 运行时 fetch = 注入面** | ICU 消息里塞 `<script>` → XSS。next-intl 默认转义；**禁止对 messages 用 raw/`dangerouslySetInnerHTML`**；manifest 内容校验 |
| **manifest 投毒** | 从 CDN/KV 拉 messages，中间人或 KV 写权限泄露 = 改 UI 文案钓鱼。**manifest 完整性校验(hash/签名)** + KV 写权限最小化 |
| **巨型 manifest DoS** | 运行时 fetch 超大 messages 放大内存/带宽。**大小上限 + 缓存** |
| **fallback 链失败 = 白屏** | 任何 fetch 环节失败必须**优雅降级到内嵌 en**（同 [[hindi-full-support]] 的 dashboard 崩溃教训：hot-path 必须 fail-open 到安全默认） |
| **管理员可用性开关绕过** | 平台∩团队∩后端三重交集若某层 fail-open 不当可泄露未授权语言。新 locale-registry 须保持同样授权边界（[[platform-team-language-gating]] 已建立） |

### 🔒 关键 hardening 前提

> **把"热插拔"作为统一语言包的卖点前，SHA-256 allowlist 必须改为生产默认强制（或加 jar 签名验证）。** 否则 HMAC key 一旦泄露即 RCE，纵深防御缺失。这不是当前漏洞（HMAC 挡着），而是热插拔架构推广前必补的纵深。**✅ 已落地**：`require-allowlist` 生产默认 `true`，空 allowlist 在 prod 下拒绝上传（见上方防线 ② 与"关联任务"）。

## 维护性（统一是双刃剑）

| 维度 | 利 | 弊/风险 |
|------|----|---------|
| **单一真相源** | 语言列表不再三处漂移；加语言一处登记 | 单点故障：注册表/manifest 服务挂 = 三套全受影响 → **必须 fallback 到内嵌 en** |
| **文案迭代** | 一处维护界面文案 | 若不解耦（全塞 jar）则被 JVM 发布节奏绑架（故采纳独立 manifest） |
| **运行时加载** | 前端热插拔语言 | 多一层运行时依赖（fetch 失败/缓存陈旧）；本地开发要 mock loader；SSR 时序复杂 |
| **dev/cloud 同栈** | 复用 i18n lib，维护面减半 | 重写 dev 是真大工程；迁移期两套并存 |

## 本 ADR 不决定（剩余待拍板）

✅ 已拍板：① 保留 next-intl（实测原生 async/远程 loader）② 2195 键走独立 manifest 不进 jar ③ dev 独立 epic 预留接入点。
✅ 已拍板（2026-06-17，见上"messages 分发实现设计"）：④ **后端 `/api/v1/messages` 权威源 + Workers KV 边缘缓存**（Cache-Aside）⑤ **版本化 KV key + 短 TTL**（无需主动 purge）⑥ **公开只读 + locale 可用性开关约束**（与 lexicons 同边界）⑦ **热更新走复用的 Redis pub/sub，不引入 Kafka**（aster-api 已有 Redis、无 Kafka）。

剩余：
- [ ] 瘦事件 vs 胖事件的最终确认（ADR 倾向瘦事件 + 回源拉取，待实现时定）
- [ ] Phase 0→1→2 主干的启动时机（独立 epic vs 接力现有工作）
- [ ] dev 重写 epic 的 glossary locale-parity 门去留（迁移 or 退役）

## 关联任务
- **hardening（✅ 已完成）**：`aster.lexicon.upload.sha256-allowlist` 在生产 profile 默认强制（fail-closed）。新增 `aster.lexicon.upload.require-allowlist`（base=`true`，`%dev`/`%test`=`false`），允许 `ASTER_LEXICON_UPLOAD_REQUIRE_ALLOWLIST` env 覆盖。判定逻辑抽为纯静态 `LexiconAdminResource.checkAllowlist`（`AllowlistVerdict`/`AllowlistOutcome` record+enum，constant-time 比对），单测 `LexiconAdminAllowlistTest` 8 例全绿。改动文件：`LexiconAdminResource.java`、`application.properties`、`LexiconAdminAllowlistTest.java`。

## See also
- [[hindi-full-support]] — 加 hi 时三套各改一遍 + JVM 级联踩坑（本 ADR 要解决的痛点的实证）
- [[platform-team-language-gating]] — 管理员可用性开关（需求点 4，已实现，本 ADR 复用）
- [[locales-consolidation]] — ADR 0011 把 en/zh/de 合进 aster-lang-locales（语言包侧已统一一半）
- [[fourth-language-feasibility]] — ADR 0017 Hindi 引擎层
- [[aster-cloud-gotchas]] — cloud i18n 加新 locale 的现有坑（routes/messages/check-locales）
