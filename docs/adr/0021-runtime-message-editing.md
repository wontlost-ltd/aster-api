# ADR 0021 — 运行时文案编辑（admin 改文案即时生效）

Status: **已实现（方案 A Redis，api#66，Codex 安全审 78→88→94 通过）**
Date: 2026-06-18

## ✅ 交付（2026-06-19，api#66）
方案 A（Redis 覆盖层 + classpath 兜底）实现。链路打通：admin PUT → Redis 覆盖层 + 本 pod
自更新 + publishReload → 各 pod 重载 → manifest sha 变 → 前端版本化 KV 自动刷新 → 即时生效。
- `UiMessagesService`：resolveEntry（Redis 先、真 miss 回退 classpath、**error 保留内存**）、
  writeOverride/deleteOverride（带 propagated）、baselineJson。
- `MessagesAdminResource`：PUT/DELETE，体积→HMAC→availability→validate→write，广播失败 202。
- `AdminHmacVerifier`：分布式 nonce（Redis SET NX EX），Redis-error **fail-closed**。
- `MessagesValidator`：JSON + 键集/占位符 parity + **ICU compile**（ICU4J）+ key 安全。
- perimeter 豁免 admin/messages（自做 HMAC）。65 测试 0 fail。
**Codex 三轮安全审查 78→88→94 抓出并修**：①跨 pod nonce replay（本地 Caffeine→Redis SET
NX EX）②ICU 未校验（加 ICU4J compile）③publish 失败静默成功（→202 degraded）④危险/含点 key
⑤Redis-error fail-closed（不退本地）。
Context: ADR 0018 统一语言包把"管理员加语言即显示"打通，但 ADR 0020 优化诊断（Codex 复核）
澄清了一个能力缺口：**"管理员改文案运行时即时生效"目前不成立**——后端 `UiMessagesService`
的 Redis 热刷新只 reload **classpath 资源**（构建期 bundle 的 `ui-messages/<locale>.json`），
没有可变内容源，改文案仍需重新部署。本 ADR 补这最后一根线。

## 现状：缺的只是"可变内容源 + 写入端点"，传播链已就绪

逐行核实（src/main/java/io/aster/policy/i18n/UiMessagesService.java）：

| 环节 | 状态 | 证据 |
|------|------|------|
| Redis pub/sub 传播 | ✅ 已建 | `initReloadChannel`(:140) 订阅 `aster.i18n.messages.reload`；`publishReload`(:192) 发瘦事件 |
| 热刷新 handler | ✅ 已建（但读错源） | `handleReload`(:165) 收事件后重载 |
| **可变内容源** | ❌ **缺** | `handleReload` 调 `loadFromClasspath`(:177) —— 只读 JAR 内不可变资源 |
| **写入端点** | ❌ **缺** | 无 POST/PUT messages 端点；`publishReload` 从无生产代码调用 |
| 版本传播到前端 | ✅ 已建（ADR 0020） | sha 变 → `/api/v1/messages-manifest` 变 → 前端版本化 KV key 换 → 边缘刷新 |

**结论**：pub/sub（用户问的机制）+ 版本化 KV 都已就绪。缺的是 ①admin 写入端点 ②可变存储
③`handleReload` 改为"先读可变源、miss 回退 classpath"。补齐后链路：

```
admin PUT 文案 → 写可变存储 + bump sha → publishReload(locale) 瘦事件
  → 所有 pod 收到 → handleReload 从可变存储重载 → 原子替换内存 Map + 新 sha
  → /api/v1/messages-manifest 反映新 sha
  → 前端下次 SSR 取 manifest → 版本化 KV key 换 → 拉新文案
  → 用户即时见新文案（无需重新部署）
```

## 存储后端选型（待拍板）

### 方案 A（推荐）：Redis 存文案 + 写入端点

复用现有 Redis（aster-api 已有，零新中间件——与 pub/sub 同源，[[unified-language-pack]]
已论证不引 Kafka 的理由同样适用）。

- **存储**：Redis key `aster:i18n:messages:<locale>` = 文案 JSON 全文。admin 写入即 set。
- **写入端点**：`PUT /api/v1/admin/messages/{locale}`（body = 完整 messages 树 JSON）。
  **鉴权（Codex 审查精确化）**：`LexiconAdminResource` 实际是**资源内部 HMAC 验签**
  （`verifyHmac`），**不是** `@RequireRole`。新端点采同一**内部 HMAC** 模式（不依赖
  `RoleEnforcementFilter` 读 `X-User-Role`——后者只在角色来自 API-key filter 权威覆盖时
  才可信，BFF/内部调用传的 header 不可信）。若要叠加 RBAC，须确认 `X-User-Role` 经
  ApiKeyAuthFilter 权威化（[[security-audit-2026-06]] 的 role-forgery 教训）。
- **输入校验（Codex 审查升级——不止 JSON 合法）**：
  - JSON 合法 + Content-Type 强制 `application/json`（拒 multipart/text）。
  - **体积上限** @Size（messages ~129KB → 256KB 上限，防 DoS，[[security-audit-2026-06]]）。
  - **next-intl/ICU message 语法合法**——坏 ICU 文案会让 SSR/render 抛错（可用性风险）。
  - **键集 + 占位符 parity**：覆盖 JSON 的 key/type 与 classpath 基线一致，占位符集合
    （`{name}` 等）一致——防误删/新增占位符导致运行时异常。
- **`handleReload` 改造**：`loadFromMutableThenClasspath(locale)` —— 先读 Redis
  `aster:i18n:messages:<locale>`，命中用它（+算 sha）；**真 miss** 回退 `loadFromClasspath`。
  **★Codex 关键点：区分 Redis miss vs error**——只有 key 不存在（真 miss）才回退 classpath；
  Redis **读失败（error）保留当前内存 + 告警**，不能把瞬时 Redis 故障当 miss 而用 classpath
  覆盖掉运行时增量。写入端点写完 Redis 后调 `publishReload(locale)`。
- **多 pod + 竞态（Codex 审查补强）**：
  - 写顺序：必须先 Redis `SET` 成功，**再** `publishReload`（事件只带 locale，pod 收到读
    Redis 真相源）。
  - **writer pod 自更新**：写入端点写完 Redis 后**本地也同步重载内存**（或调同一 reload
    逻辑），不能只靠 pub/sub——否则发布失败时本 pod 内存还旧。
  - **并发写**：Redis key 旁存 `sha/updatedAt`，写入支持 `If-Match`/CAS（compare-and-set），
    避免两个 admin 并发写互相无意覆盖（last-write-wins 可接受但要可控）。
- **审计**：写入事件进审计链（locale、old sha→new sha、操作者 actor、tenant、request id），
  复用现有 AuditService。**不把完整文案含敏感内容无脑打日志**。

**优点**：最轻、与 pub/sub + 版本化 KV 无缝衔接、零新中间件、Redis set/get 是 O(1)。
**风险**：Redis 持久化策略（AOF/RDB）决定文案掉电后是否还在；若 Redis 清空，回退 classpath
（不丢"显示能力"，只丢"运行时编辑增量"——可接受，admin 重新编辑即可，或定期快照到 DB/R2）。

### 方案 B：Postgres 表存文案（policy_documents 同模式）

用 DB 表（同 [[policy-storage-db-backed]] 的 PolicyStorageService DB 化）。

- **存储**：`ui_messages` 表（locale PK + JSONB content + sha + updated_at + updated_by），
  Flyway migration 建表（须登记 migration-checksums.golden，[[policy-storage-db-backed]] 教训）。
- **写入端点**：同方案 A，但落 DB。
- **`handleReload`**：先查 DB，miss 回退 classpath。**坑（[[policy-storage-db-backed]]）**：
  阻塞 JPA 不能跑在 event loop，handleReload 在 daemon executor 跑（已是），但若端点用
  Panache 须 `runSubscriptionOn(worker pool)` 否则吞成 "System error"。

**优点**：持久化强、可审计历史（版本表）、掉电不丢。
**缺点**：比 Redis 重——引阻塞 JPA + Flyway migration + checksum golden 登记；handleReload
读 DB 比读 Redis 慢（但热刷新非热路径，可接受）。

### 取舍建议

**倾向方案 A（Redis）**：本 epic 的价值是"运行时编辑增量即时生效"，不是"文案主真相源迁出
代码库"——主真相源仍是语言包仓（classpath 基线），Redis 只存 admin 的运行时覆盖增量。
Redis 掉电回退 classpath 不丢显示能力。若要"编辑历史可审计/持久"再叠加方案 B（Redis 作热
路径 + DB 作持久快照），但那是后续增强，不是本轮必需。

## 实现范围（方案 A）

1. `UiMessagesService`：
   - `loadFromRedis(locale)`（读 `aster:i18n:messages:<locale>`，算 sha）。
   - `handleReload` 改为先 Redis 后 classpath。
   - `onStart` 预加载也先 Redis 后 classpath（重启后恢复运行时增量）。
   - 新增 `writeMessages(locale, json)`（写 Redis + publishReload）。
2. `MessagesAdminResource`（新）：`PUT /api/v1/admin/messages/{locale}`，
   `@RequireRole(Role.ADMIN)` + HMAC + @Size + JSON 校验。镜像 `LexiconAdminResource`。
   **perimeter**：admin 路径**不**走 messages 公开豁免（`MessagesPathMatcher` 只豁免读路径
   `/api/v1/messages/<locale>` 和 manifest，**绝不**豁免 `/api/v1/admin/messages/*`）——
   写入必须经全 RBAC + HMAC。
3. 审计：写入进审计链。
4. 测试：写入端点鉴权（无 role/无 HMAC → 拒）、写后 get 即新、handleReload Redis 优先、
   Redis miss 回退 classpath、跨 pod（单测模拟两 service 实例 + 共享 Redis stub）。
5. **manifest/版本化 KV 已就绪**（ADR 0020）——写入 bump sha 后，前端自动随 manifest 刷新，
   无需改前端。这是 ADR 0020 优化 1 的复利。

## 安全考量（写入端点是新攻击面）

- **鉴权**：`@RequireRole(Role.ADMIN)` + HMAC 双层，与 lexicon admin 同（[[security-audit-2026-06]]）。
- **DoS**：@Size 体积上限（messages 树 ~129KB，给 256KB 上限）；JSON 解析深度/键数上限。
- **注入/XSS（Codex 复核：低风险但要钉死）**：next-intl 普通 `t()` 返回字符串，React
  默认转义 → 直接 HTML **不会执行**。富文本标签只在代码用 `t.rich(...)` + 提供 tag 映射
  时才变 React 元素（aster-cloud grep 未见 `t.rich(`）。ICU 插值本身不是 HTML 执行点（错误
  格式只是可用性风险，已由上面 ICU 校验覆盖）。**ADR 硬约束**：禁止 `t.rich` 消费 admin
  可编辑文案（或富文本标签走白名单）、禁止任何 `dangerouslySetInnerHTML` 消费 messages。
- **限流**：写入端点加 rate limit（运营滥用面）。
- **可用性开关仍守**：写入只对已注册 locale 生效（未注册 locale 写入拒），与读端点同源。
- **回滚**：误改文案 → 删 Redis key（回退 classpath 基线）或 admin 重写。classpath 永远是
  安全基线。

## 不做（避免过度设计）
- 文案编辑 UI（前端 admin 界面）——本 ADR 只做后端能力，UI 是 aster-cloud 后续。
- 富文本/Markdown 文案编辑器——纯 JSON PUT 即可。
- 多版本/草稿/审批流——admin 直接生效 + 审计 + 回退即可，工作流是过度设计。
- DB 持久（方案 B）——除非用户要"编辑历史可审计持久"，否则 Redis + classpath 兜底够用。

## ★Codex 设计审查（2026-06-18，82/100，方向对+事实核实准确）
现状核实全部属实（pub/sub 已建、handleReload 只读 classpath、无写入端点、publishReload
无生产调用）。补强 4 点已并入上文：①鉴权信任边界精确化（LexiconAdminResource 是**内部
HMAC** 非 @RequireRole；X-User-Role 须经 ApiKeyAuthFilter 权威化才可信）；②输入校验从
"JSON 合法"升到 schema+ICU 语法+占位符 parity；③**Redis miss vs error 必须区分**（error
保留内存别用 classpath 冲掉增量）；④并发写加 sha/CAS、writer pod 自更新内存别只靠 pub/sub。
XSS 低风险（next-intl t() 返回字符串 React 转义），但硬约束禁 t.rich/dangerouslySetInnerHTML
消费 messages。补这些后设计可达 90+。

参见 [[unified-language-pack]]（ADR 0018）/ [[language-pack-optimizations]]（ADR 0020，
版本化 KV 是本 epic 前置）/ [[policy-storage-db-backed]]（DB 化阻塞 JPA 坑）/
[[security-audit-2026-06]]（admin 端点鉴权 + role-forgery + DoS 上限）。
