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

仓库归属：ADR 0040 §6 与现有代码都在 `aster-cloud` 组织；#464 原文写的 `wontlost-ltd/aster-guard` 为笔误，按现状定稿为 **`aster-cloud/aster-guard`**（代码已在那里、npm scope 已是 `@aster-cloud`），#464 另行评论更正。

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

1. `actionHash = hashAction(action)`（§4 配方；与送出的请求体是**同一个对象**，`check` 内部不再复制改写、不剔除 null——现有 `check` 对 `principal` 的缺省填充在哈希**之前**完成）。
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

**这些结构化字段是非授权信息。** `reason / reasonCode / approvalId / poll / requiredRole` 只供人与审计阅读，不是给模型的指令：SDK 与代理**不得**据此触发任何替代执行（不自动改参重试、不换工具、不在 PENDING 期间以其他路径「先做再说」）；模型若读到 DENY 后转而调用另一个可用工具，那次调用会**独立**走一遍 1–6 并得到自己的决策，不继承任何前序结论。安全边界不在这些字段的措辞上，而在 §3.3 的端点隔离：模型读到什么都无所谓，只要它触达不到原始工具。验收见 §7 第 2b 条。

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

**不变式**（绕过套件 §7 第 3 条断言）：

1. **规范化只排序、不改值。** 代理对下游 `arguments` 做一次 `canonicalize`：递归按键排序，**null 原样保留**，不删键、不改类型、不补缺省。送去 Guard 的 `action.context` 与转发给上游工具的 `arguments` 是**同一个规范化后的对象**（同一引用，序列化后逐字节相同）。0.1.0 的「check 之前剔除 null」（ADR 0040 §6）**废止**：aster-api 当前拒绝 `context` 中的 null 值（§2.2「值不得为 null」），本 ADR 要求 aster-api 在 R63 中放开 `context` 的 null 值（`GuardActionMapper` 只需允许 null 叶子；规则读到 null 与读到缺省键的行为由规则作者负责）。
2. **缺失 ≠ null ≠ `""`。** 三者是三个不同的 JSON 文档，哈希各不相同；工具若把「缺省」与「显式 null」当作不同语义，两次调用也会得到两个不同的 `actionHash`，不会共用一份收据。
3. **`digestArgs` 的摘要输入**：该键的值先经同一 `sha256-sorted-json/v1` 序列化，再 sha256，`context` 中以 `{ "$sha256": "<hex>" }` 代入；值为 null 时摘要输入是字面量 `null`（4 字节），键缺失时 `context` 中**没有**该键（不生成 `$sha256`）。转发给上游的仍是原值（摘要只替换 Guard 看到的副本——这是不变式 1 的唯一例外，且被摘要绑定）。
4. `digestArgs` 之外的键原值进入 `context`；不存在「不进 context 也不进摘要」的参数。没有 `excludeArgs`——这正是 #464 第 3 条「哪些参数进 actionHash」的回答：全部，只是形式有原值与摘要两种。

正反例（`actions.send_email = { resourceFrom: "to", digestArgs: ["body"] }`）：

| 下游 `arguments` | Guard 看到的 `context` | 转发给上游 | 哈希 |
|---|---|---|---|
| `{ to: "a@x", body: "hi", cc: null }` | `{ body: {$sha256: H("\"hi\"")}, cc: null, to: "a@x" }` | `{ body: "hi", cc: null, to: "a@x" }` | h1 |
| `{ to: "a@x", body: "hi" }`（无 `cc`） | `{ body: {…}, to: "a@x" }` | 同左 | h2 ≠ h1 |
| `{ to: "a@x", body: "hi", cc: "" }` | `{ body: {…}, cc: "", to: "a@x" }` | 同左 | h3 ≠ h1, h2 |
| `{ to: "a@x", body: "hi!", cc: null }` | `body` 摘要变 | 同左 | h4 ≠ h1（摘要绑定 body） |
| `{ to: "a@x", body: null }` | `{ body: {$sha256: H("null")}, to: "a@x" }` | `{ body: null, to: "a@x" }` | h5 |
| `{ to: "a@x" }` | `{ to: "a@x" }` | 同左 | h6 ≠ h5 |
| ALLOW 后代理内部改 `arguments.to` 再转发（套件第 3 条注入） | — | — | consume 409 `action_mismatch`，不转发 |

### 3.3 原始工具端点的隔离（至少其一，缺省必须声明选了哪一种）

| 方式 | 要求 | 验证 |
|---|---|---|
| 网络策略 | 上游 MCP 服务器只接受来自代理的连接：stdio 上游（子进程，现状）天然满足；HTTP 上游用 k8s `NetworkPolicy` / 主机防火墙只放行代理 Pod | 套件第 1 条：从模型所在网络直连上游必须失败 |
| 工具侧鉴权 | 上游要求一个只有代理持有的凭证（`--upstream-auth-env <VAR>` 注入 header） | 套件第 1 条：不带凭证直连 401/403 |

代理启动参数 `--isolation network|auth` **必填**，但它只是**配置自述**：代理无法自己证明隔离成立。隔离的验收是**部署级**的（§7 第二层），以 e2e 从模型所在网络命名空间直连原始工具端点**失败**为准；`--isolation` 的值只决定 e2e 用哪种断言（连接被拒 / 401）与从哪里读取配置：

- `network`：e2e 读取部署仓（aster-deploy / k3s）中该上游工具的 `NetworkPolicy`（`podSelector` 指向工具 Pod，`ingress.from` 仅含代理 Pod 的标签选择器），并在模型所在命名空间启动一个一次性 Pod 对工具 Service 发起连接，期望超时 / 拒绝；策略对象缺失即判失败。
- `auth`：工具侧要求 **consume 票据**——代理在 consume 200 后把 `{ decisionId, attemptId }` 以 `X-Aster-Guard-Consume` 头附在上游请求上，工具侧按下列规则核对，任一不满足 → 401 且**不执行**（fail-closed）；e2e 从模型所在网络不带头直连，期望 401。该方式要求工具可改；不可改的第三方工具只能用 `network`。
  1. **认证**：工具用**自己的** API key（不是代理的）调 `GET /api/v1/guard/decisions/{decisionId}`；该 key 所属租户必须与决策租户一致（跨租户 404，与 ADR 0040 §3.4 同口径），所以工具只能核对本租户的决策。R63 响应增 `consumedBy: { attemptId, executorId, consumedAt }` 与 `actionHash`（仅 ALLOW 且已消耗时写）。
  2. **绑定到当前请求**：工具对收到的参数按代理的同一映射（§3.2：工具知道自己的 `digestArgs` 配置，或由代理在头中附 `actionHash` 供工具**比对而非信任**）重算 `actionHash`，与响应 `actionHash` 相等；`consumedBy.attemptId` 等于票据 `attemptId`；`consumedAt` 在 `now − ttl` 之后（`ttl` = 工具侧配置，缺省同收据 TTL `PT5M`）；`effectiveOutcome == ALLOW`；未写 result（响应 `result` 缺省）。
  3. **fail-closed**：Guard 不可达、超时、非 200、字段缺失或不符 → 401 不执行，不做「查不到就放行」。工具侧不缓存核对结果（每请求一次 GET；成本可接受——它只对有副作用的调用发生）。

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

- `executorId` 形态：`<component>@<host-or-pod>[:<instance>]`，如 `aster-guard-mcp@agent-host-1`、`aster-cloud-exec@cloud-7f9c`；≤ 255 字符、无控制字符。它与 `attemptId` 都是**关联 / 审计用途**的自报值，不承担身份认证；身份是 API key（§3.1），对账里「谁执行的」以链事件 `performed_by` 与 `requested_api_key_id` 为准，`executorId` 只用来把 consume、result 与执行方日志对上。
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

套件分两层，结论的效力不同：

- **夹具层（随仓，`test/bypass/*.e2e.test.ts`）**：以 `ASTER_GUARD_E2E=1` 启用，否则 `it.skip` 并打印原因（#466「先以 skip + 原因登记」）。依赖本地 aster-api（含 ADR 0040 §12）与一个内存上游 MCP 服务器，上游每次调用把副作用写进 `sideEffects[]`。它**只证明代理逻辑**（DENY 不转发、改参 409、重放 409、验签拒绝），不证明部署里的原始工具不可达。
- **部署层（#466，aster-cloud Playwright + 部署仓脚本）**：在真实部署拓扑里，由模型所在网络命名空间的一次性 Pod 对原始工具端点直连（§3.3），**必须失败**；配置从部署仓读取（`NetworkPolicy` 对象或工具侧 consume 票据开关）。第 1 条的通过与否**只以部署层为准**，夹具层对第 1 条只是 `--isolation` 参数解析测试。

三样断言（Guard 决策记录、`sideEffects` 无新增、链 / 行一致）每条都做：

| # | 场景 | 断言 |
|---|---|---|
| 1 | 不经代理直连上游（**部署层**，按 `--isolation` 选断言；§3.3） | 连接被拒 / 401；工具侧无副作用 |
| 1b | `auth` 模式票据核对（工具侧夹具）：缺头；错 `attemptId`；票据对应另一租户决策（用另一租户 key 签发）；参数被改（重算 `actionHash` 不符）；`consumedAt` 超 ttl；已写 result 的票据重放；Guard 不可达 / 超时 / 5xx | 每种都 401、无副作用；只有「头正确 + 哈希相等 + attempt 相等 + 时间在窗内 + 未写 result + Guard 200」执行 |
| 2 | 规则 DENY 后调用 | 返回 `status: denied`；`sideEffects` 空；链上有 DECISION、无 CONSUME_ATTEMPT |
| 2b | DENY 后模型尝试其它可用工具 / 路径：同一会话里对白名单内另一工具发起调用、对同一工具改参重试、直连上游（#466 第 2 条扩展） | 每次调用各得独立决策（新 decisionId）；被拒的不转发；直连按第 1 条失败；`sideEffects` 只含被 ALLOW 的那些；代理与 SDK 代码中不存在读取 `reason / poll / approvalId` 后分支执行的逻辑（静态断言：grep 这些字段只出现在返回构造处） |
| 3 | ALLOW 后、转发前改参数（测试用钩子篡改 `args`）；以及 null / 缺失 / `""` 三种 `cc` 各得不同 `actionHash`（§3.2 正反例） | consume 409 `action_mismatch`；`sideEffects` 空；`guard_receipts.consumed_at` 空；三哈希两两不等 |
| 4 | 同一收据执行两次（用低层 API 重放 `consume`） | 第二次 409 `receipt_consumed`；`sideEffects` 恰一条 |
| 5 | 伪造 / 篡改签名、过期收据（mock `/decisions` 响应） | `verifyReceipt` 失败，未调用 consume；`sideEffects` 空 |
| 6 | 注入指令驱动的演示 Agent | 属 aster-cloud 场景（#464 验收），本仓只提供 1–5 与 §5 契约 |
| 7 | 向量文件与 aster-api 副本一致 | 单测，始终启用 |

套件是**必要非充分**：它证明已知路径关着，不证明没有未知路径；未知路径由 #465 对账兜底。对账只认 `guard_receipts.consume_audit_id` 指向的权威尝试事件（ADR 0041 §12 D1），不数事件。

---

## 8. 分阶段落地

① 本 ADR 与 ADR 0040 §12、ADR 0041 §12 同车次定稿 → ② aster-api 落 R63/R64（先于代理；旧代理不受影响）→ ②′ 排空：待处理的迁移前 ALLOW 决策处理完或过期（ADR 0040 §12 D10）→ ③ aster-guard 0.2.0：`hashAction` + 向量、`verifyReceipt / consume / reportResult / keys / run`、代理固定顺序与结构化返回、`--tools` / 映射文件 / `--isolation`、套件 1–5、7；release workflow 与 Trusted Publisher；发布 → ④ aster-cloud：演示 Agent 场景接入代理（#464 验收第 6 条），from-evidence 执行路径用同一契约 consume / 回写 → ⑤ Streamable HTTP 模式 + Agent 登记 + 镜像（有多 Agent 共用代理的需求时）→ ⑥ Java 客户端（有需求时）。

---

## 9. 已选默认与可改项

| 项 | 已选默认 | 可改为 | 影响 |
|---|---|---|---|
| 传输 | stdio 先行 | 首版即含 Streamable HTTP | 多 Agent 登记与镜像提前到 ③ |
| 未配置 `--tools` | 全部暴露 + warn | 启动拒绝 | 现有用户升级即失败 |
| `digestArgs` | 显式列出 | 超过阈值（如 8 KB）自动摘要 | 哈希输入取决于大小阈值，跨实现更难一致 |
| PENDING 超时返回 | `isError: true` + `status: pending` | `isError: false` 的「等待中」结果 | 模型更可能误以为成功 |
| 回写失败 | 记日志，不影响结果 | 重试队列 | 代理需要本地状态 |
