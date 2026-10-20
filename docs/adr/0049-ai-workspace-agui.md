# ADR 0049：AI 助手改用 AG-UI（vaadin-agui + agui-vaadin-pro）独立服务

- 状态：**DRAFT**（2026-10-20）
- 实施计划：待定稿后编写（`docs/superpowers/plans/2026-10-20-ai-workspace-agui.md`）
- 决策者：用户已拍板「方向 1：新增 Spring Boot + Vaadin 服务（k3s），完整使用 vaadin-agui + Pro；形态 3：独立 AI 工作台为主 + 策略编辑器紧凑侧栏，共用会话；
  AI 调用经 cloud 现有 `/api/llm/*`；`ai.aster-lang.cloud` 同站子域 + cloud 签发一次性交接令牌；首版 Pro 模块 Conversations / Audit / Governance /
  Observability；SaaS 先上，验收通过后 on-prem 同步切换并删除旧 React 面板；on-prem 仅在客户配置了 Pro license key 时启用 Pro 能力」
- 相关：ADR 0048（BYOK 模型选择与 provider 错误分类修订）、aster-cloud `src/components/policy/ai-assistant-panel.tsx`（现有面板）、
  `src/lib/llm-sse-proxy.ts`（配额/计费/BYOK 注入）、`src/lib/deployment-mode.ts`（SaaS / on-prem）、
  wontlost-ltd/vaadin-agui 0.1.1（Apache-2.0）、wontlost-ltd/agui-vaadin-pro 0.1.2（商业许可）

---

## 0. TL;DR

现有「AI 助手」是 aster-cloud 策略编辑器里的 React 侧栏：输出只存在于组件状态，**离开页面即消失**；错误展示也曾因嵌套条件整块不渲染。
本 ADR 把 AI 助手改为一个新的 **Spring Boot 4 + Vaadin 25 服务 `aster-ai-workspace`**，界面用 vaadin-agui 的 AG-UI 聊天组件，
会话、审计、治理、可观测性用 agui-vaadin-pro。它以两种形态出现：独立的 **AI 工作台**（`ai.aster-lang.cloud`，会话侧栏 + 聊天）和策略编辑器里的
**紧凑侧栏**（iframe），二者共用同一份持久化会话——建议在会话内、跨页面、跨登录都可读。服务本身**不直接调用模型**：它通过 AG-UI 适配器调用
cloud 现有的 `/api/llm/*`，BYOK、配额、计费、安全拒答、provider 错误分类、temperature 自适应全部保持单一来源。

---

## 1. 现状与问题

- **建议不持久**：`useAIAssistant` / `useSSEStream` 只把输出放在 React state；离开或刷新编辑器即丢失。用户要求「在当前 session 下长期可读」。
- **面板能力有限**：单轮生成/建议，无会话历史、无检索、无审计视图；错误展示依赖手写分支（#529 刚修复无内容时不显示错误）。
- **运行时约束**：aster-cloud 是 Next.js on Cloudflare Workers（V8 isolate，无 JVM）；aster-api 是 Quarkus。vaadin-agui / agui-vaadin-pro 需要
  Servlet + Spring Boot 4 + Vaadin 25 + Java 21，两者都跑不了。Cloudflare Containers 虽可运行 JVM 镜像，但冷启动与 Vaadin 有状态会话
  （需粘性路由、休眠即丢 UI 状态）不适合；aster-api 已在 k3s 上，与 Postgres 同集群。
- **库的现状**（代码为准）：vaadin-agui 的唯一 SPI 是 `AgUiAgent.run(RunAgentInput, AgUiEmitter)`，`<agui-chat>` 以 POST + SSE 说 AG-UI 协议，
  支持 22 类事件、Markdown、工具卡片、复制/赞踩/重新生成，但**没有按代码块的「插入/替换」动作**；Pro 的会话持久化
  （`JdbcConversationStore`，`agui_conversation`）、审计、治理均为 Vaadin Flow + Spring 自动配置，需 license key（生产不阻断，仅告警 + 横幅）。

---

## 2. 决策

### 2.1 部署与形态

| 项 | 决定 |
|---|---|
| 新服务 | 新仓 `wontlost-ltd/aster-ai-workspace`：Spring Boot 4 / Vaadin 25 / Java 21，部署在 k3s（Deployment + Service + ingress `ai.aster-lang.cloud`），镜像与 Deploy / image-pin 流程沿用 aster-api 的模式 |
| 工作台 | `WorkspaceView`（`/`）：Pro `ConversationSidebar`（搜索/新建/重命名/删除）+ `AgUiChat` |
| 编辑器侧栏 | `PanelView`（`/panel`，`COMPACT` 变体）以 iframe 嵌入 cloud 策略编辑器右栏 |
| 共享会话 | 二者读写同一 `ConversationStore`；编辑器侧栏按策略默认 thread `policy:{policyId}`，可「新对话」 |

### 2.2 AI 调用：经 cloud `/api/llm/*`

- 服务内 `AsterAgent implements AgUiAgent` 按 `forwardedProps.mode`（`generate` / `suggest` / `ask`）调用 cloud
  `/api/llm/generate|suggest|assistant`，以 PlanGate 同款 HMAC 签入**用户与租户身份**；cloud 对 `/api/llm/*` 增加「服务签名身份」认证分支，
  与浏览器会话等价——BYOK 注入、配额、`AiUsageRecord` 计费、安全拒答、provider 错误分类、temperature 自适应**全部不变**。
- 每次 run 恰好一次 cloud 调用、一条 `AiUsageRecord`；新服务**不记账**（不启用 Pro Metering）。

### 2.3 帧映射（cloud SSE → AG-UI）

| cloud 帧 | AG-UI 事件 |
|---|---|
| （run 开始） | `RUN_STARTED` |
| `meta`（model） | `CUSTOM aster.model`（界面小字显示实际模型） |
| `upstream_reached` | 仅内部使用，不外发 |
| `delta` | 首个发 `TEXT_MESSAGE_START`，随后 `TEXT_MESSAGE_CONTENT` |
| `repair_start` | `STEP_STARTED "repair n"`；结束当前消息并开始新消息 |
| `validation_error` | `STATE_DELTA {validationError}`（以提示显示） |
| `final`（validated） | `MESSAGES_SNAPSHOT`（以校验后的全文替换）+ `STATE_DELTA {validated}` |
| `error` | `RUN_ERROR {code, message}`（沿用 ADR 0048 修订的错误码、本地化文案与 provider 原文规则） |
| （结束） | `RUN_FINISHED` |

### 2.4 持久化（回应「长期可读」）

- Pro `ConversationBinder` 在 `RUN_FINISHED` 时按 `ConversationScope(tenantId, ownerId)` + threadId 保存整段会话（`JdbcConversationStore`，
  与 cloud 共用 Postgres，独立 schema `ai_workspace`）。
- 建议因此跨页面、刷新、会话可读，直至用户删除或治理保留期到期。
- 无 Pro license 的 on-prem（见 2.8）：退化为 vaadin-agui 基础聊天，会话保存在当前 Vaadin 会话内存中（当前会话内可读）。

### 2.5 写回编辑器

- 上游改进 **vaadin-agui 0.2.0**：`<agui-chat>` 为代码块提供可配置动作（复制 / 插入 / 替换），派发 `agui-code-action` 事件。
- 侧栏在 iframe 内浏览器侧监听该事件，`postMessage` 给父页面；cloud 只接受 origin 为 `ai.aster-lang.cloud` 且 `policyId` 与当前编辑器一致的消息，
  应用到 Monaco 后回执。
- 现有 `generate`「编译通过自动填入编辑器」改为显式「应用到编辑器」按钮，不再静默覆盖。

### 2.6 身份与交接

- cloud 签发一次性交接令牌（用户、租户、角色、locale、单次 id；60 秒），服务用公钥验证后建立 Vaadin 会话，Cookie 落在 `ai.aster-lang.cloud`
  （与 `aster-lang.cloud` 同站，iframe 内可用）。仅做建立身份所必需的部分，不新增其它安全控制。
- 令牌过期/已用 → 回跳 cloud 重签；iframe 内由侧栏 `postMessage` 请父页面重签。

### 2.7 失败处理

| 情况 | 行为 |
|---|---|
| Vaadin 会话丢失（重启/滚动发布） | 刷新即重新交接；会话在库中不丢，进行中的回复中断，可「重新生成」 |
| cloud / aster-api 不可达、超时 | `RUN_ERROR network`，聊天内显示错误与重试 |
| provider 错误 / 配额用尽 / 账号受限 | `RUN_ERROR` 携带 cloud 的错误码与 denial 原因，复用现有本地化文案 |
| 数据库不可用 | 对话照常，横幅提示「本次对话未能保存」，不阻断 |
| Pro license 缺失（SaaS） | 使用我方自有 key（Secret）；生产模式下 Pro 不阻断，仅记日志 |

治理默认：存档副本做 PII / 类 Key 脱敏；会话默认保留 180 天（可配置）；管理员可 Legal Hold 与导出。可观测性：Micrometer 指标（run 时长、失败率、各模式次数）。

### 2.8 SaaS 与 on-prem

- **SaaS**（aster-lang.cloud，cloud 在 Cloudflare Workers、服务在我方 k3s）：首发；cloud 侧以 `AI_WORKSPACE_URL` 开关灰度，未设置时保持现有 React 面板。
- **on-prem**（客户自托管，`DEPLOYMENT_MODE=on-prem`）：SaaS 验收通过后切换——`aster-ai-workspace` 以可选容器镜像随 on-prem 交付，随后删除 cloud
  旧 React 面板代码。**on-prem 仅在客户配置了有效的 Pro license key 时启用 Pro 模块**（会话持久化、审计、治理、可观测性）；无 key 时以 vaadin-agui
  基础聊天运行（会话仅当前会话内存可读），所有 AI 调用仍经客户自己的 cloud `/api/llm/*`。

### 2.9 首版不做

Pro Metering（与 cloud 计费重复）、Pro Approvals（Aster 已有 Action Guard 四眼审批）、编辑器侧栏的跨设备实时同步。

---

## 3. 验证与上线

- 单测：帧映射全表（含 `repair_start` / `final` 替换、错误码）、mode 路由、交接令牌（过期/重放/签名错）、服务签名与 cloud 验证共享 fixture、
  vaadin-agui 代码块动作事件、on-prem 有无 license 的模块启用条件。
- 集成：Pro 会话/审计/治理在 Postgres 上的保存、恢复、检索、脱敏、保留期。
- 端到端（本地 podman 栈 + stub provider）：cloud → 工作台；编辑器 → iframe 侧栏 → 建议 → 插入/替换回 Monaco；离开再回/刷新后建议仍在；
  provider 拒绝、配额、网络错误可读；4 种语言。
- 性能：首字时间较现状增加 ≤ 150 ms（p95）；单 Vaadin 会话内存实测。
- 上线顺序（每步可独立回退）：① vaadin-agui 0.2.0 → ② cloud（交接、内部签名、CSP、导航、iframe 侧栏，均在开关后）→ ③ aster-ai-workspace 服务与
  k3s 清单 → ④ 生产暗发布（开关关闭）→ 先为我方租户开启 → 全量 SaaS → ⑤ 验收通过后 on-prem 切换并删除旧面板。

---

## 4. 后果

- 新增一个 JVM 服务与一套部署流水线；k3s 资源与运维面增加。
- AI 调用多一跳（服务 → cloud → aster-api），换取 BYOK/配额/计费/安全逻辑的单一来源。
- 会话数据进入 Postgres（`ai_workspace` schema），受治理保留期与脱敏约束。
- on-prem 交付物增加一个可选镜像与 Pro license 配置说明（`docs/on-prem/` 需补充）。
