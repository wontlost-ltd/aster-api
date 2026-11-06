# ADR 0054：aster-guard 执行点 —— 包裹式 MCP 代理与 GuardClient 的执行契约

- 状态：**PROPOSED**（2026-11-07 起草，待用户评审；定稿前不得据此合并实现）
- 提出：2026-11-07；来源 wontlost-ltd/aster-api#464（「如何确认治理未被绕过」讨论第 2 项）
- 相关：ADR 0040（Action Guard；§6 为 aster-guard 仓首版设计，本 ADR 细化并在冲突处取代之；§12 R63 执行收据与 consume）、ADR 0041 §12 R64（consume / result 事件）、ADR 0042 §3（from-evidence 执行路径）、#465（对账）、#466（绕过套件）
- 实现仓：`aster-cloud/aster-guard`（本地 `/Users/rpang/IdeaProjects/aster-guard`，远程 `https://github.com/aster-cloud/aster-guard`，private，0.1.0 未发布）

---

## 0. TL;DR

没有执行点，Guard 只是建议：模型可以不问 Guard 直接调工具，或问完 DENY 照样调。系统提示里的「先调用 Guard」是软约束，注入即绕过。本 ADR 把执行点做成**硬约束**：

```
模型 ──MCP──► aster-guard-mcp ──┬─► POST /guard/decisions ──► ALLOW + executionReceipt
                                ├─► 离线验签（JWKS）
                                ├─► POST /guard/receipts/{id}/consume ──► 200
                                ├─► 上游工具（模型拿不到它的端点）
                                └─► POST /guard/receipts/{id}/result
```

四个决定：

| 决定 | 内容 | 不做什么 |
|---|---|---|
| 执行点形态 | 包裹式 MCP 代理同名暴露上游工具；非 MCP 框架用 `GuardClient.run` | 不做「在模型侧注入提示要求先调 Guard」；不提供任何跳过 Guard 的入口 |
| 固定顺序 | 决策 → 验签 → consume → 转发 → 回写，任一步失败即止 | 不做「先执行后补决策」；不做「consume 失败重试执行」 |
| 隔离 | 原始工具端点对模型不可达：网络策略或工具侧鉴权，至少其一，由绕过测试证明 | 不信任「模型不知道端点」 |
| 可证伪 | 绕过测试套件随仓；对账数据契约与 #465 对齐 | 不把「测试通过」当作「不可绕过」的证明，对账是第二道 |

---

## 1. 现状（已核对本地仓 `aster-guard` main dda6aea）

| 已有 | 位置 | 缺 |
|---|---|---|
| `GuardClient`：`check / get / waitFor / listApprovals / approve / reject`，非 2xx 与网络错 → DENY | `src/client.ts:83-`，`src/types.ts:60-80` | 无 `verifyReceipt / consume / reportResult`；无把顺序固定下来的 `run` |
| 代理：`tools/list` 透传、`tools/call` 拦截、`decide → isConfirmedAllow → upstream.callTool` | `src/mcp/proxy.ts:127-141`、`isConfirmedAllow` `:272-274` | ALLOW 即转发，无验签、无 consume、无回写；`args` 与 `context` 之间无哈希绑定 |
| DENY / 失败返回：`isError: true` 文本 `Blocked by Aster Guard: <why> (decision <id>)` | `proxy.ts:276-282` | 非结构化；PENDING 超时与 DENY 同形，调用方无法区分「等人」与「被拒」 |
| 配置：`--base-url / --policy / --principal-* / --upstream / --resource-from / --approval-timeout / --upstream-timeout` | `src/mcp/config.ts:19-46` | 无工具白名单、无按工具的 action 映射、无 Agent 身份登记 |
| 幂等键：每次 `tools/call` 生成 UUID | `proxy.ts:258` | 符合 ADR 0040 §6，不改 |
| 测试：vitest，mock fetch + 内存 MCP 上游；e2e 脚本可选 | `test/*.test.ts` | 无绕过套件 |
| 发布：ADR 0040 §6（tag-push、trusted publisher、provenance），`package.json` `@aster-cloud/aster-guard`、Node ≥ 24、ESM | `package.json` | 未发布（`npm view` 404）；`engines` 与 workflow 需按 aster-lang-ts 现行 release.yml 校对 |

仓库归属：ADR 0040 §6 与现有代码都在 `aster-cloud` 组织；#464 写的是 `wontlost-ltd/aster-guard`。本 ADR 按**现状**写 `aster-cloud/aster-guard`（代码已在那里、npm scope 已是 `@aster-cloud`）；是否迁到 `wontlost-ltd` 由用户定（§9 可改项），迁移只影响 `repository.url` 与 release workflow 的 OIDC 主体。

---

## 2. 决策一：执行点的两种形态与一条顺序

### 2.1 `GuardClient.run`（SDK，TS 先行）

```ts
const outcome = await guard.run(
  { action: { name: 'delete_customer_record' }, resource: { type: 'customer', id: 'C-1001' }, context: { amount: 250000 } },
  async (grant) => deleteCustomerRecord('C-1001'),   // 只有 consume 200 后才被调用；grant 含 decisionId / attemptId
  { executorId: 'billing-worker@pod-3', approvalTimeoutMs: 300_000 },
);
// outcome: { kind: 'executed', decisionId, attemptId, result } | { kind: 'blocked', decision } | { kind: 'pending', decision } | { kind: 'failed', decisionId, attemptId, error }
```

`run` 内部固定顺序（任一步失败即返回 `blocked`，回调不被调用）：

1. `actionHash = hashAction(action)`（§4 配方；与送出的请求体是**同一个对象**，`check` 内部不再复制改写——现有 `check` 对 `principal` 的缺省填充在哈希**之前**完成）。
2. `check(action)`；`PENDING` → `waitFor`；最终非 `ALLOW` → `blocked` / `pending`。
3. `verifyReceipt(decision.executionReceipt, { tenantId, decisionId, actionHash })`（ADR 0040 §12 D6 顺序；JWKS 来自 `keys()`，内存缓存 5 分钟，未命中 `kid` 刷新一次）。
4. `consume(decisionId, { actionHash, executorId, attemptId: randomUUID() })`；非 200 → `blocked`（网络错误同样 `blocked`，并尽力 `reportResult(aborted, errorCode: 'consume_response_lost')`，ADR 0040 §12 D5）。
5. 调用回调；成功 → `reportResult(executed, resultHash?)`；回调抛错 → `reportResult(failed, errorCode)` 后把错误以 `failed` 返回（不吞）。
6. 回写失败只记日志，不影响已执行的结果（副作用已落地，回写是对账证据，不是放行条件）。

低层方法 `check / verifyReceipt / consume / reportResult / keys / hashAction` 公开（代理与测试要用），但 README 只以 `run` 为推荐入口；**没有**「跳过验签」「跳过 consume」的选项——想绕过只能不用 SDK，而那正是 #465 对账要抓的。

### 2.2 `aster-guard-mcp`（包裹式代理）

每次 `tools/call` 的处理即 `run` 的代理化：

1. 工具名不在白名单（§3.1）→ `McpError(InvalidParams)`（现状 `proxy.ts:126` 的「Unknown tool」不变）。
2. 按 action 映射（§3.2）构造 `action`；`actionHash` 对该对象计算。
3. `decide`（现有，含 `waitFor` 到 `--approval-timeout`）。
4. `verifyReceipt`；`consume`。
5. `upstream.callTool({ name, arguments: args })`——`args` 为上游工具收到的参数，与 `action.context` 的关系由 §3.2 的不变式保证。
6. `reportResult`：上游成功 → `executed`，`resultHash` 为对 `CallToolResult.content` 的 `sha256-sorted-json/v1`；上游 `isError` → `failed`；下游取消（`extra.signal.aborted`，现状 `proxy.ts:131`）发生在 consume 之后、转发之前 → 不转发，`aborted`。

**返回形态**（`CallToolResult`，同时给 `content[0].text` 与 `structuredContent`，后者是机器可读契约）：

| 情况 | `isError` | `structuredContent` |
|---|---|---|
| ALLOW 且上游成功 | 上游原值 | 上游原值 + `_meta.asterGuard: { decisionId, attemptId }` |
| DENY | `true` | `{ "asterGuard": { "status": "denied", "decisionId", "outcome", "reasonCode", "reason", "ruleId", "controls" } }` |
| REQUIRE_APPROVAL / ESCALATE 等到超时仍 PENDING | `true` | `{ "asterGuard": { "status": "pending", "decisionId", "approvalId", "requiredRole", "expiresAt", "poll": "GET /api/v1/guard/decisions/{decisionId}" } }`；模型可在之后用 `guard_status` 工具（现有 standalone 工具集）重新查询，但**不能**凭 ALLOW 结果自行调用上游——再次调用同名工具会重新走一遍 1–6，得到新决策（幂等键是每次新生成的，ADR 0040 §6） |
| 验签 / consume / 网络 / 超时失败 | `true` | `{ "asterGuard": { "status": "blocked", "decisionId", "reasonCode": "receipt_invalid" \| "receipt_consumed" \| "action_mismatch" \| "receipt_expired" \| "not_allowed" \| "client_error" \| "client_timeout", "reason" } }` |

`isError: true` 的返回对模型是普通工具错误，它可以读到原因与 `decisionId`，但读不到任何能让它换路执行的东西（没有上游端点、没有收据原文）。

---

## 3. 决策二：配置契约

### 3.1 工具白名单与 Agent 身份

- `--tools <a,b,c>`（或配置文件 `tools: [...]`）：只有列出的上游工具出现在 `tools/list` 并可调用；未配置 → 全部上游工具（现状），但启动日志打 warn「未配置工具白名单」。白名单只能**缩小**暴露面，没有「某工具免 Guard」的写法。
- Agent 身份：代理以 **API key 为身份**（ADR 0041 §10 的 `key-bound` 思路）。stdio 模式下，启动代理的宿主进程就是唯一 Agent，key 由环境变量 `ASTER_API_KEY` 提供，模型侧不可见；`principal.id` 由 `--principal-id` 固定（现状），模型无法在 `tools/call` 里改写 `principal`（映射层不读参数里的 `principal` 键，若出现则整体按 context 的普通键处理并被哈希覆盖）。
- Streamable HTTP 模式（本 ADR 第二阶段，§8）：代理要求每个连接 `Authorization: Bearer <agentToken>`，`agents: [{ id, tokenSha256, principalId, principalRoles? }]` 在配置文件登记；未登记 → 401，不建会话。一个代理实例服务多个 Agent 时，`principal` 取登记项，不取连接方自报。

### 3.2 action 映射与「同一对象」不变式

每工具可选映射（配置文件 `actions.<toolName>`）：

| 键 | 作用 | 缺省 |
|---|---|---|
| `resourceFrom` | 取该顶层参数作 `resource.id`（现状 `--resource-from` 的按工具版本；`resource.type` 为 `arg`） | 无 resource |
| `digestArgs` | 列出的顶层参数在 `context` 中以 `{ "$sha256": "<hex>" }` 代入（大对象 / 二进制 / 不想入收据预算的字段） | 无 |
| `actionName` | 覆盖 `action.name`（缺省为工具名） | 工具名 |

**不变式**（绕过套件 §7 第 3 条断言）：`context` = 上游参数去 null 后逐键映射，`digestArgs` 之外的键**原值**进入 `context`，`digestArgs` 内的键以摘要进入；转发给上游的 `args` 就是映射前的那个对象。因此：改任何参数都改变 `actionHash`（原值或摘要之一必变）；不存在「不进 context 也不进摘要」的参数。没有 `excludeArgs`——这正是 #464 第 3 条「哪些参数进 actionHash」的回答：全部，只是形式有原值与摘要两种。

### 3.3 原始工具端点的隔离（至少其一，缺省必须声明选了哪一种）

| 方式 | 要求 | 验证 |
|---|---|---|
| 网络策略 | 上游 MCP 服务器只接受来自代理的连接：stdio 上游（子进程，现状）天然满足；HTTP 上游用 k8s `NetworkPolicy` / 主机防火墙只放行代理 Pod | 套件第 1 条：从模型所在网络直连上游必须失败 |
| 工具侧鉴权 | 上游要求一个只有代理持有的凭证（`--upstream-auth-env <VAR>` 注入 header） | 套件第 1 条：不带凭证直连 401/403 |

代理启动参数 `--isolation network|auth` **必填**，只是声明，用于 README 与套件选择断言方式；代理无法自己证明隔离成立，所以套件必须随部署跑（§7）。

---

## 4. 决策三：`hashAction` 与 golden 向量

`sha256-sorted-json/v1` 的 TS 实现（ADR 0040 §12 D2 的字节级定义）：递归按键 UTF-16 码元序排序、`JSON.stringify` 无空白；整数（`Number.isSafeInteger`）十进制；非安全整数与非整数按 Java `Double.toString` 形态（实现一个 `javaDoubleToString`，向量覆盖 `1.5 / 1e21 / 0.1 / -0.0`）；字符串按 JSON 标准转义、非 ASCII 原样。向量文件 `test/fixtures/action-hash-vectors.json` 与 aster-api `src/test/resources/guard/action-hash-vectors.json` 逐字节相同；两仓各自跑 `vectors.every(v => hashAction(v.input) === v.sha256)`。任何一方改配方必须同时改两份向量并升版 `/v2`（ADR 0040 §12 D2 的 fail-closed 保证：不一致只会得到 409，不会放行）。

---

## 5. 决策四：与 #465 对账的数据契约

执行方在副作用落地前后留下的记录，字段固定为 ADR 0040 §12 D5 / D7 的请求体：

```
consume: { decisionId, actionHash, executorId, attemptId }
result:  { decisionId, attemptId, executorId, status: executed|failed|aborted, finishedAt, resultHash?, errorCode? }
```

- `executorId` 形态：`<component>@<host-or-pod>[:<instance>]`，如 `aster-guard-mcp@agent-host-1`、`aster-cloud-exec@cloud-7f9c`；≤ 255 字符、无控制字符。
- `attemptId`：执行方生成的 UUID v4，每次 `run` 一个；同一 `decisionId` 只会有一个被 consume 接受。
- 代理与 SDK 自身**无持久化**，它们的「执行侧记录」就是 consume / result 两次回写；对账任务（#465，aster-api 定时）只读 `guard_receipts` 与链事件，不需要执行方再上报。aster-cloud 自有执行方（策略执行、workflow runner）在自己的表里记 `decisionId`（`executions.metadata.guardDecisionId` 已存在，ADR 0041 §5.2），同时按本契约 consume / 回写；外部业务系统的批量上报端点是 #465 的范围。
- 对账三类异常（#465）在本契约下的判定：无 `decisionId` 的副作用 → 孤儿；有 consume 无 result 超过 `result-grace` → 执行侧丢失；consume 的决策为 DENY → 不可能发生（consume 对非 ALLOW 409 `not_allowed`），若出现说明 aster-api 自身被绕过，严重告警。

---

## 6. 决策五：仓、语言与发布

- 仓：`aster-cloud/aster-guard`（现状）；单包 `@aster-cloud/aster-guard`，入口 `.`（SDK）与 `./mcp`，bin `aster-guard-mcp`（现状 `package.json`）。
- 语言：**TypeScript 先行**（0.2.0 实现本 ADR）；Java `GuardClient` 留作后续（aster-cloud 没有 Java 执行方，aster-api 自身是签发方不需要客户端；需求出现再立项）。
- 发布：tag-push 触发 release workflow，npm trusted publisher + provenance（ADR 0040 §6；workflow 照 `aster-lang-ts/.github/workflows/release.yml`，发布前用户需在 npm 为 `@aster-cloud/aster-guard` 配置 Trusted Publisher——与 design-system v0.3.2 时同样的前置操作）。独立版本号，不进 ADR 0023 lockstep。首个发布版本 **0.2.0**（0.1.0 从未发布，直接跳过）。
- 容器镜像：Streamable HTTP 模式需要常驻进程，第二阶段随 `aster-deploy` 出镜像；stdio 模式只需 npm 包。

---

## 7. 绕过测试套件（随仓，#466 的执行点部分）

`test/bypass/*.e2e.test.ts`，以 `ASTER_GUARD_E2E=1` 启用，否则 `it.skip` 并打印原因（#466「先以 skip + 原因登记」）。依赖本地 aster-api（含 ADR 0040 §12）与一个内存上游 MCP 服务器，上游每次调用把副作用写进测试内的 `sideEffects[]`，三样断言（Guard 决策记录、`sideEffects` 无新增、链 / 行一致）每条都做：

| # | 场景 | 断言 |
|---|---|---|
| 1 | 不经代理直连上游（按 `--isolation` 声明的方式） | 连接被拒 / 401；`sideEffects` 空 |
| 2 | 规则 DENY 后调用 | 返回 `status: denied`；`sideEffects` 空；链上有 DECISION、无 CONSUME |
| 3 | ALLOW 后、转发前改参数（测试用钩子篡改 `args`） | consume 409 `action_mismatch`；`sideEffects` 空；`guard_receipts.consumed_at` 空 |
| 4 | 同一收据执行两次（用低层 API 重放 `consume`） | 第二次 409 `receipt_consumed`；`sideEffects` 恰一条 |
| 5 | 伪造 / 篡改签名、过期收据（mock `/decisions` 响应） | `verifyReceipt` 失败，未调用 consume；`sideEffects` 空 |
| 6 | 注入指令驱动的演示 Agent | 属 aster-cloud 场景（#464 验收），本仓只提供 1–5 与 §5 契约 |
| 7 | 向量文件与 aster-api 副本一致 | 单测，始终启用 |

套件是**必要非充分**：它证明已知路径关着，不证明没有未知路径；未知路径由 #465 对账兜底。

---

## 8. 分阶段落地

① 本 ADR 与 ADR 0040 §12、ADR 0041 §12 同车次定稿 → ② aster-api 落 R63/R64 → ③ aster-guard 0.2.0：`hashAction` + 向量、`verifyReceipt / consume / reportResult / keys / run`、代理固定顺序与结构化返回、`--tools` / 映射文件 / `--isolation`、套件 1–5、7；release workflow 与 Trusted Publisher；发布 → ④ aster-cloud：演示 Agent 场景接入代理（#464 验收第 6 条），from-evidence 执行路径用同一契约 consume / 回写 → ⑤ Streamable HTTP 模式 + Agent 登记 + 镜像（有多 Agent 共用代理的需求时）→ ⑥ Java 客户端（有需求时）。

---

## 9. 已选默认与可改项

| 项 | 已选默认 | 可改为 | 影响 |
|---|---|---|---|
| 仓归属 | `aster-cloud/aster-guard`（现状） | 迁至 `wontlost-ltd/aster-guard` | 改 `repository.url`、release OIDC 主体、npm scope 是否同改 |
| 传输 | stdio 先行 | 首版即含 Streamable HTTP | 多 Agent 登记与镜像提前到 ③ |
| 未配置 `--tools` | 全部暴露 + warn | 启动拒绝 | 现有用户升级即失败 |
| `digestArgs` | 显式列出 | 超过阈值（如 8 KB）自动摘要 | 哈希输入取决于大小阈值，跨实现更难一致 |
| PENDING 超时返回 | `isError: true` + `status: pending` | `isError: false` 的「等待中」结果 | 模型更可能误以为成功 |
| 回写失败 | 记日志，不影响结果 | 重试队列 | 代理需要本地状态 |
