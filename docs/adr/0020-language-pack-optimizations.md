# ADR 0020 — 统一语言包性能/正确性优化（诊断）

Status: **已完成（优化 1+2 实现合并，优化 3 被优化 1 吸收，优化 5 仅澄清不实现）**
Date: 2026-06-18

## ✅ 交付状态（2026-06-18）
| 优化 | 内容 | 状态 |
|------|------|------|
| **1** KV 版本化 | messages-manifest 端点 + 版本化 KV key + body/manifest ETag 一致性 | ✅ api#65 + cloud#104（Codex 76→94 通过） |
| **2** 双加载并行 | request.ts 非默认 locale 用 Promise.all 并行 en 底座+locale | ✅ cloud#105 |
| **3** fetch 缓存/ETag | manifest Next 缓存 + body 304 | ⏸️ 被优化 1 吸收（不单独做，避免边际复杂度） |
| **5** 运行时文案写入 | 后端无 live 文案写入能力 | 📝 仅澄清（ADR 0018 措辞收窄），非本轮 |
Context: ADR 0018 统一语言包 P0-P3 已落地（[[unified-language-pack]]），发版前修了
perimeter 误拦 bug（PR #63）。本 ADR 是发版前的语言包优化扫描，**全部实测取证**，
不臆测。涉及 aster-api（后端权威源）+ aster-cloud（前端运行时加载）。

## 背景：当前数据流（已实现，已上线）

```
浏览器 SSR 请求
  ↓ next-intl getRequestConfig (request.ts)
Cloudflare Worker (cloud)
  ↓ loadMessages(locale)  (messages-loader.ts)
  ├─ 1) KV.get("ui-messages:<fullId>")        ← 固定 key + TTL 300s
  ├─ 2) miss → fetch GET /api/v1/messages/<fullId>  ← 后端权威
  │       回填 KV (TTL 300s)
  └─ 3) 任何失败 → loadEmbedded (build 期内嵌 en.json 129KB)
后端 aster-api
  ↓ MessagesResource.get  (内存 ConcurrentHashMap, ETag=sha256, 304)
  ↓ UiMessagesService (启动加载 classpath + Redis pub/sub 热刷新)
```

后端侧已经很干净（内存零 DB、ETag/304、预算 sha256）。**优化点全在前端加载链
+ 后端与前端的版本协议**。

---

## 🔴 优化 1（高优先，正确性 bug）：KV key 未版本化 → 后端版本变更后前端 stale 300s

> **✅ 已实现（2026-06-18，Codex 审查 76→94/100 通过）**：api#65（`/api/v1/messages-manifest`
> 端点 + perimeter 豁免 + shortSha 16 位）+ cloud#104（messages-loader 版本化 KV key +
> body/manifest ETag 一致性校验）。后端 41 测试 / 前端 160 测试 0 fail。
> **★Codex 抓出的关键竞态（我漏了）**：manifest 与 body 是两次独立请求，滚动发布/多实例/
> 边缘缓存下可能拿到新 sha 的 manifest 但旧 body → 无条件写进 `v<新sha>` key 会被长 TTL
> 钉住 = 比原 stale 更糟的"错版本污染"。修：`bodyMatchesSha`——body 的 ETag 须以 manifest
> sha 开头才回填版本化 key；不一致只返回 body 给本次请求(fail-open)、不污染 KV。**教训:
> 版本化缓存的两段式拉取(版本表+内容)必须校验内容版本与版本表一致, 否则把"读旧值"换成
> "写错版本 key"更难发现**。


### 实测证据
- 后端 `UiMessagesService` Javadoc（src/main/java/io/aster/policy/i18n/UiMessagesService.java:42-44）
  **明确设计** KV key 为版本化：`messages:<locale>:v<sha 前 8 位>`，"文案一变 sha 变
  → 前端 KV key 自然换 → 边缘自然刷新"。
- 但前端 `messages-loader.ts:93` 实际用**固定 key** `ui-messages:${fullId}` + 300s TTL
  （`messages-loader.ts:118` 回填 TTL=300）。

### 后果
**当后端某 locale 的 messages sha 变化后**，前端某个 edge/isolate 的 KV 里若已有旧
`ui-messages:<fullId>`，会在 KV hit 时直接返回旧 JSON、不回源、不看 ETag，**最长 stale
到该 KV entry 过期（300s）**。设计契约（版本化 key）与实现（固定 key）不一致 = 正确性
bug。

### ⚠️ 前提澄清（Codex 审查修正——我原稿过度承诺）
后端的 Redis "热刷新"（UiMessagesService:150）**只是从 classpath 重新加载
`ui-messages/<locale>.json`**，README（src/main/resources/ui-messages/README.md:30）明说
**改文案仍需同步资源 + 重新部署**。即：**当前后端并不支持"管理员运行时写文案即生效"**——
sha 变化目前只发生在重新部署时，不是 live 编辑。所以本优化是"**当后端 sha 变（=发版）后，
前端边缘正确地随版本刷新**"的正确性修复，**不是**"管理员改文案前端秒级生效"——后者还缺
后端运行时文案写入能力（独立缺口，见优化 5）。修了 KV 版本化，发版后前端边缘随版本即时
换 key（不靠 TTL），是把"发版即生效"做对。

### 设计（与后端 Javadoc 对齐）
KV key 带版本：`ui-messages:<fullId>:v<sha8>`。版本来源两选一（Codex 审查倾向 manifest）：
- **A（推荐，语义最干净）**：新增独立 `GET /api/v1/messages-manifest` 返回
  `{locale, sha}[]`。前端先拿 manifest（自身可短缓存）→ 用 sha 拼 KV key → 命中即新鲜，
  sha 变则 key 变自然 miss 回源。**版本变更随 manifest 即时生效，不靠 TTL**。比污染
  lexicons DTO 干净（不让"语言可用性"DTO 同时担"messages 版本"职责）。
- **B（复用 lexicons 快照）**：`/api/v1/lexicons` 的 `LexiconInfo` 加 `messagesSha` 字段。
  省一个端点，但 **Codex 提醒：必须同步改 `LexiconStreamResource`（SSE）的 snapshot**
  （它复用 `LexiconResource.LexiconInfo`），否则 SSE 首帧/变更帧 shape 不一致；且要改
  前端 `LexiconInfo` 类型 + golden contract。

倾向 **A（manifest）**——单一职责，改动面集中，不动既有 lexicons 契约/SSE。

### 范围
- 后端：新增 `MessagesManifestResource`（从 UiMessagesService 取各 locale sha256 前 8 位）。
  **注意 perimeter 豁免**（同 messages，复用 PR #63 的 `MessagesPathMatcher` 模式给
  manifest 路径也加豁免，否则又被 TenantFilter 拦——这正是 PR #63 的教训）。
- 前端：`messages-loader` 先取 manifest → KV key 拼 sha。manifest 自身用短 TTL/Next 缓存。

---

## 🟡 优化 2（中优先，每请求成本）：request.ts 双加载，非默认 locale 加载 2 次

> **✅ 已实现（2026-06-18）**：cloud#105。非默认 locale 用 `Promise.all` 并行加载 en
> 底座 + 当前 locale（原串行）再 deepMerge，合并语义不变。i18n 160 测试 0 fail。
> 未做 isolate 级 memo（Workers 隔离模型下命中率不确定，并行已拿到主要收益；留作观察）。


### 实测证据
`request.ts:54,60`：非默认 locale 的每个 SSR 请求调 `loadMessages` **两次**——
一次 `loadMessages(defaultLocale)`（en 兜底底座），一次 `loadMessages(locale)`。各自
一轮 KV 读 + 可能的后端 fetch。en 用户只 1 次。

### 后果
- 非英文用户每个 SSR 请求 2×(KV 往返 + 潜在 fetch)。中文/德文/印地语用户全程双倍加载
  延迟。
- `deepMergeMessages`（request.ts:19）每请求递归合并整棵 2195 键消息树——CPU 成本
  （Workers CPU 时间受限，虽不大但可省）。

### 设计
- **en 兜底底座请求级 memo**：en 的 messages 在进程内基本不变（除非热刷新），可在
  Worker 实例内缓存 en 树（带 sha 失效），避免每请求重读。next-intl 的 messages 也可
  在 KV 命中路径上直接返回合并好的树（见下）。
- **合并结果也可缓存**：`deepMerge(en, locale)` 的结果对 (en-sha, locale-sha) 组合是
  确定的，可缓存 `merged:<locale>:v<enSha>_<localeSha>`，命中直接返回成品，免 merge。
  （需评估 KV 写放大 vs CPU 节省，可能只缓存 en 底座 + 客户端不变的部分。）
- 最小改动版：只把 en 底座做 Worker 实例级 memo（带版本失效），不碰 KV 拓扑。

### 范围
纯前端（request.ts + messages-loader）。需小心 Workers 无共享可变全局的隔离模型
（per-isolate 缓存 OK，跨 isolate 不保证）——memo 命中率取决于 isolate 复用，但即便
偶尔 miss 也不劣于现状。

---

## 🟡 优化 3（中优先，回源带宽）：fetch 未用 Next 缓存 / 未发 If-None-Match

> **⏸️ 大部分被优化 1 吸收，不单独做**：优化 1 落地后——①manifest fetch 已加
> `next:{revalidate:60}` Worker 缓存（优化 3 的"用 Next 缓存"对 manifest 已做）；
> ②body fetch **只在版本变化时发生**（版本化 KV miss = 新版本），此时 body 必是新内容，
> `If-None-Match` 永远不会 304（版本不同）→ 对 body 发条件请求无收益。剩余仅"冷 isolate
> 首次 body 全量传输"窗口，KV 跨 isolate 共享已大幅覆盖。**结论：优化 3 的实际价值已被
> 优化 1 的版本化 KV + manifest Next 缓存吸收，不再单独实现**（避免为边际收益加复杂度）。


### 实测证据
- `messages-loader.ts:109` 的 fetch 只带 `Accept`，**没用 Next 的 `next:{revalidate}`
  缓存**，也**没发 `If-None-Match`**（后端 MessagesResource 支持 304，前端没利用）。
- KV miss 时每次都拉全量 body（en 129KB 量级）。

### 后果
KV miss（冷启动 / TTL 过期 / 新 isolate）时回源总是全量传输，即便内容没变也不走 304。
OpenNext 在 Workers 上对 server-side fetch 有自己的缓存层，当前完全没接。

### 设计
- fetch 加 `next: { revalidate: 300, tags: ['ui-messages-<locale>'] }`，让 Worker fetch
  缓存层兜一层（与 KV 互补，KV 是显式控制、Next 缓存是透明兜底）。
- **KV 存 `{etag, body}`（而非只存 body）**——Codex 提醒：单纯 KV miss 时前端**没有旧
  ETag 可发** `If-None-Match`。要利用 304，须 KV hit 时拿到存的 etag 做条件 revalidate，
  或另存一个长寿命 etag-only metadata key。304 时复用 KV body 仅 bump TTL，省 129KB。
- 与优化 1 协同：版本化 key 后，KV 命中即新鲜，回源只在版本真变时发生，304 优化主要
  覆盖"版本没变但 KV 过期/冷 isolate"的窗口。

### 范围
纯前端 fetch 选项。低风险（fail-open 链不变）。

---

## 🟠 优化 5（产品语义缺口，Codex 审查指出，非本轮但需澄清）：后端无运行时文案写入

### 实测证据
- `UiMessagesService.handleReload`（:150）的"热刷新"**只从 classpath 重新加载**已有的
  `ui-messages/<locale>.json` 资源；Redis 瘦事件只是触发 reload 信号。
- README（src/main/resources/ui-messages/README.md:30）明说**新增/改文案需同步资源 +
  重新部署**。

### 含义
ADR 0018 的"管理员加语言/改文案前端即显示"卖点里——**"加语言"成立**（lexicon SPI +
locale 可用性开关，[[platform-team-language-gating]]），但**"改文案运行时即生效"不成立**：
没有运行时文案写入端点，sha 只在重新部署时变。Redis 热刷新机制是"为未来运行时写入预留
的管道"，当前只在"多 pod 重启后统一从同一 classpath 重载"场景有意义。

### 决策
**本轮不实现**（是独立的后端能力扩展，不是优化）。但**ADR 0018 的措辞需收窄**——避免把
"改文案即时显示"当已交付能力。优化 1（KV 版本化）修的是"**发版后**前端边缘随版本即时刷新"，
不依赖优化 5。若未来要真"管理员 live 改文案"，需：运行时文案写入端点（DB or 配置中心）→
更新内存 + bump sha → Redis 瘦事件 → 各 pod 重载 → manifest sha 变 → 前端 KV key 换。
那时优化 1 是必要前置。

## ⚪ 优化 4（低优先，观察项，未列入本轮）

- **内嵌兜底 129KB**：en.json build 期进 Worker bundle 是终极兜底（绝不白屏），体积
  无法避免（fail-open 铁律）。但可评估是否只内嵌"关键路径文案"（登录/错误页）做更小
  的最小兜底，完整树仍走 KV/后端。**风险高**（兜底不全 = 退化白屏风险），暂不动。
- **lexicon 侧对称**：`useAvailableLexicons`（客户端 hook）fetch 无缓存提示；
  `lexicon-availability.ts` 用 `cache: 'no-store'`（可用性需实时，合理）。lexicon 列表
  比 messages 小得多，优化收益低，暂不列。
- **后端 UiMessagesService 已最优**：内存 Map、预算 sha256、ETag/304、Redis 瘦事件热
  刷新。无明显优化点。

---

## 推进建议（DAG，Codex 审查后调整）

1. **优化 1（KV 版本化）先做**——设计契约 vs 实现不一致的正确性 bug。后端加
   `messages-manifest` 端点（+perimeter 豁免）→ 发版 → 前端消费拼版本化 key。优化 3 的
   304 协同依赖此版本协议。
2. **优化 3（fetch 缓存 + KV 存 {etag,body}）**——纯前端，与优化 1 的前端改动同 PR。
3. **优化 2（双加载去重 + en 底座 memo）**——纯前端，独立，最后做（收益看 isolate 复用率）。
4. **优化 5（运行时文案写入）= 非优化，是能力缺口**——本轮只澄清 ADR 0018 措辞，不实现。

每项独立可验证：优化 1 用"**发版改一个 sha** → 前端 X 秒内随 manifest 换 key 见新"端到端
验（X 应趋近 0 而非 300s TTL）；优化 2/3 用 Worker CPU 时间 / 回源字节数对比。

## ★Codex 诊断复核（2026-06-18）：3 个优化点全部属实（非误诊）
- 优化 1/2/3 逐条读源码确认成立。**唯一修正**：原稿"管理员改文案后端即时更新内存"过度
  承诺——后端热刷新只 reload classpath 资源，改文案仍需重新部署（优化 5 = 真缺口）。KV
  版本化 bug 本身成立（后端 sha 变=发版后，前端固定 key stale 300s）。
- 优化 1 修法：倾向独立 manifest 端点 > 污染 lexicons DTO（后者要同步改 SSE snapshot）。
- 优化 3 修法补正：KV 须存 `{etag, body}`，否则纯 KV miss 时无旧 ETag 可发 If-None-Match。

## 不做（避免过度设计）
- R2 整包下发（小 JSON KV 足够，ADR 0018 已定）。
- 内嵌兜底瘦身（白屏风险 > 收益）。
- 后端改 DB 化 / 缓存层（已是内存零阻塞最优）。

参见 [[unified-language-pack]]（ADR 0018）/ [[aster-cloud-gotchas]] /
[[policy-storage-db-backed]]（零阻塞内存模式先例）。
