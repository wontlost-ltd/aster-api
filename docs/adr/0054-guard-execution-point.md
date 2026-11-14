# ADR 0054：aster-guard 执行点 —— 包裹式 MCP 代理与 GuardClient 的执行契约

- 状态：**ACCEPTED**（2026-11-08 用户确认；实施计划 docs/superpowers/plans/2026-11-08-guard-receipts-and-input-provenance.md）
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
6. `reportResult`：上游成功 → `executed`，`resultHash` 为对 `CallToolResult.content` 的 `jcs-sha256/v1`；上游 `isError` → `failed`；下游取消（`extra.signal.aborted`，现状 `proxy.ts:131`）发生在 consume 之后、转发之前 → 不转发，`aborted`。

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
3. **`digestArgs` 的摘要输入**：该键的值先经同一 `jcs-sha256/v1` 序列化，再 sha256，`context` 中以 `{ "$sha256": "<hex>" }` 代入；值为 null 时摘要输入是字面量 `null`（4 字节），键缺失时 `context` 中**没有**该键（不生成 `$sha256`）。转发给上游的仍是原值（摘要只替换 Guard 看到的副本——这是不变式 1 的唯一例外，且被摘要绑定）。
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
  1. **认证**：工具用**自己的** API key（不是代理的）调 `GET /api/v1/guard/decisions/{decisionId}`；该 key 所属租户必须与决策租户一致（跨租户 404，与 ADR 0040 §3.4 同口径），所以工具只能核对本租户的决策。R63 响应增 `consumedBy: { attemptId, executorId, consumedAt }` 与 `actionHash`（仅 ALLOW 且已消耗时写），已结果回写时另增 `result: { status, resultAt }`（未回写则缺省）；这些字段只出现在 `GET /decisions/{id}`，不进入 `response_json`，幂等重放不含。
  2. **绑定到当前请求**：工具对收到的参数按代理的同一映射（§3.2：工具知道自己的 `digestArgs` 配置，或由代理在头中附 `actionHash` 供工具**比对而非信任**）重算 `actionHash`，与响应 `actionHash` 相等；`consumedBy.attemptId` 等于票据 `attemptId`；`consumedAt` 在 `now − ttl` 之后（`ttl` = 工具侧配置，缺省同收据 TTL `PT5M`）；`effectiveOutcome == ALLOW`；未写 result（响应 `result` 缺省）。
  3. **fail-closed**：Guard 不可达、超时、非 200、字段缺失或不符 → 401 不执行，不做「查不到就放行」。工具侧不缓存核对结果（每请求一次 GET；成本可接受——它只对有副作用的调用发生）。

---

## 4. 决策三：`hashAction` 与 golden 向量

`jcs-sha256/v1` 的 TS 实现（ADR 0040 §12 D2 的字节级定义，RFC 8785 JCS）：对象成员按键的 UTF-16 码元序排序、无空白；字符串先解码转义再按 RFC 8785 输出，不做 Unicode 正规化；数字直接用 `Number#toString`（即 RFC 8785 §3.2.2.3 的 ECMAScript 形态），按 double 解释后绝对值超过 2^53−1（含 `9007199254740991.5` 这类小数写法）或非有限数、含孤立代理项的字符串在发送前即拒绝（大数须以字符串发送）。向量文件 `test/fixtures/jcs-sha256-v1-vectors.json` 与 aster-api `src/test/resources/guard/jcs-sha256-v1-vectors.json` 逐字节相同；两仓各自跑 `vectors.every(v => hashAction(v.input) === v.sha256)`。任何一方改配方必须同时改两份向量并升版 `/v2`（ADR 0040 §12 D2 的 fail-closed 保证：不一致只会得到 409，不会放行）。

---

## 5. 决策四：与 #465 对账的数据契约

执行方在副作用落地前后留下的记录，字段固定为 ADR 0040 §12 D5 / D7 的请求体：

```
consume: { decisionId, actionHash, executorId, attemptId }
result:  { decisionId, attemptId, executorId, status: executed|failed|aborted, finishedAt, resultHash?, errorCode? }
```

- `executorId` 形态：`<component>@<host-or-pod>[:<instance>]`，如 `aster-guard-mcp@agent-host-1`、`aster-cloud-exec@cloud-7f9c`；≤ 255 字符、无控制字符。它与 `attemptId` 都是**关联 / 审计用途**的自报值，不承担身份认证；身份是 API key（§3.1），对账里「谁执行的」以链事件 `performed_by` 与 `requested_api_key_id` 为准，`executorId` 只用来把 consume、result 与执行方日志对上。
- `attemptId`：执行方生成的 UUID v4，每次 `run` 一个；同一 `decisionId` 只会有一个被 consume 接受。
- 代理与 SDK 自身**无持久化**，它们的「执行侧记录」就是 consume / result 两次回写；对账任务（#465，aster-api 定时）只读 `guard_receipts` 与链事件，不需要执行方再上报。aster-cloud 自有执行方（策略执行、workflow runner）在自己的表里记 `decisionId`（`executions.metadata.guardDecisionId` 已存在，ADR 0041 §5.2），同时按本契约 consume / 回写；外部业务系统的批量上报端点是 #465 的范围（→ §10）。
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

---

## 10. 对账契约（R65，#465）

- 状态：**ACCEPTED**（2026-11-15，提出于 2026-11-14；实施计划 docs/superpowers/plans/2026-11-14-guard-reconciliation.md）
- 范围：只记外部可见的契约（端点、类别、指标、告警、与 #464 的集成点）；SQL、类名等实现细节见实施计划。

### 10.1 事实来源

| 选项 | 能抓什么 | 抓不到 | 结论 |
|---|---|---|---|
| A. 只读账本（`guard_receipts` + 链事件） | 已消耗无结果、未消耗 ALLOW、对非 ALLOW 消耗、无消耗结果、链上有决策事件而决策未落库 | **绕过**（绕过者不留任何账本记录） | 必做，但不够 |
| B. 执行方（代理 / SDK）自报执行记录 | 同 A（它的「执行记录」就是 consume / result，§5） | 绕过——被绕过的正是它 | 不做（与 A 重复） |
| C. **副作用落地方上报**（工具服务、业务系统、cloud）批量端点 | 无 decisionId 的副作用、凭未消耗 / 非 ALLOW 决策落地的副作用、参数或尝试不符、一次决策多次落地 | 落地方本身不上报的副作用 | **选 A + C** |
| D. aster-api 主动拉取业务系统（连接器） | 同 C | — | 不做：每个系统一个连接器，超出本期 |

C 的独立性来自「观测者 ≠ 执行者」：绕过路径能跳过代理，但副作用总要落在某个系统里，由那个系统上报。§3.3 `auth` 隔离模式下工具侧本就收到 `X-Aster-Guard-Consume: {decisionId, attemptId}`，是天然的上报方。

**上报义务按「落地」而不是按「票据」**：落地方对它**实际执行**的每一个受守卫动作都上报一条，有票据时带上票据里的 `decisionId / attemptId`，没有票据也照报（不带这两项）——无票据的落地正是 `side_effect_without_decision` 要抓的。`auth` 模式的工具对无票据请求 401 且不执行，不产生副作用，也就无需上报；所以在隔离成立时对账看到零孤儿，在隔离失效（`network` 策略缺失、工具漏核票据、业务系统被旁路写入）时由落地方的上报暴露。

「有效消耗」一律且只以 `guard_receipts.consume_audit_id IS NOT NULL` 判定（ADR 0041 §12 D1「读法」），`consumed_at` 只用作时间坐标；不数 `ACTION_GUARD_CONSUME_*` 事件、不看 `success` 列。迁移前（`V6.35.0` 之前）的决策没有收据行，账本自检不覆盖它们（不回填，ADR 0040 §12 D10）。

### 10.2 上报端点

`POST /api/v1/guard/side-effects`，请求：

```
{ "effects": [ { "effectId", "source", "occurredAt", "action": { "name" }, "resource"?: { "type", "id" },
                 "decisionId"?, "attemptId"?, "actionHash"? } ] }
```

- 1–500 条；`effectId` 1–128 字符无控制字符；`source` 匹配 `[a-z0-9][a-z0-9._-]{0,63}`；`occurredAt` ISO-8601 且 `≤ now + PT5M`；`action.name` 1–255；`resource.type / id` 各 ≤ 255；`decisionId` / `attemptId` 为 UUID 形态；`actionHash` 64 位小写 hex。
- 任一条不合法 → 400 `{ "error": "invalid_side_effect", "index": <i>, "field": "<name>" }`，整批不写；重复键 / 非法 JSON → 400 `invalid_side_effect`（无 `index`）。
- 幂等键 `(tenant, source, effectId)`：重复上报计入 `duplicates`、不改原行。200 `{ "accepted": n, "duplicates": m }`。不计量配额（它是账本输入，不含评估）。
- **重试与重放的区分只靠 `effectId`**：上报方对同一动作的网络重试**必须**沿用同一 `effectId`（得到 `duplicates`，无副作用）；换了 `effectId` 即视为另一次落地。端点从不按 `decisionId` 拒绝请求——是否重放由对账在接受之后判定（见下条）。
- **上报粒度**：一个上报方（`source`）对一次受守卫动作（一个 `decisionId`）**恰好上报一条**；一个动作落地多处副作用时由上报方聚合成一条（`effectId` 取上报方自己的动作记录键）。不同 `source` 观测同一决策是正常的。据此同 `(decisionId, source)` 的第二条上报（另一个 `effectId`）照常 200 接受，对账时若同一 `(decisionId, source)` 已有一条判为匹配，它判 `side_effect_mismatch`（`reason = replayed`）。
- 鉴权：Bearer API key（SDK、工具服务、业务系统）与 HMAC 内部调用（cloud）双通道，`MEMBER` 即可；租户取自已验证身份。
- **信任边界**：上报是租户内成员对自己系统的陈述，接受即入账，不做真实性核验——本租户任一 `MEMBER` key 都能上报一条无 `decisionId` 的副作用并触发 critical 孤儿。这是有意的：漏报（绕过不可见）的代价远高于误报，而误报可追责、可处置——每行记 `reported_by`（已解析身份），孤儿 `detail` 复制上报原文，管理员核实后经确认端点关闭并入链。建议为每个落地方发独立的 key，使 `reported_by` 能直接指认上报方。

查询与确认（类级 `MEMBER`，确认需 `ADMIN`；跨租户、不存在、非数字 id 一律 404 空体）：

- `GET /api/v1/guard/reconciliation` → `{ "lastRun": { "runId", "finishedAt", "windowStart", "windowEnd", "newOrphans", "resolvedOrphans", "openOrphans" } | null, "open": { "<kind>": n }, "openTotal": n, "resultGrace": "PT15M", "lookback": "P7D" }`（`open` 只列非零）。
- `GET /api/v1/guard/reconciliation/orphans?status=OPEN|RESOLVED|ACKNOWLEDGED&kind=<kind>&limit=1..200&before=<id>` → `{ "items": [ { "id", "kind", "severity", "status", "decisionId"?, "sideEffectId"?, "executor", "executorId"?, "detectedAt", "resolvedAt"?, "resolution"?, "acknowledgedBy"?, "detail" } ], "next"?: <id> }`；`id` 降序，`status` 缺省 `OPEN`，`limit` 缺省 50；非法参数 → 400 `invalid_query`。
- `POST /api/v1/guard/reconciliation/orphans/{id}/ack`，体 `{ "comment": "<1–1000 字符>" }` → 200 同列表项；非 `OPEN` → 409 `{ "error": "not_open", "status": "<当前>" }`；`comment` 不合法 → 400 `invalid_ack`。确认写链事件 `GUARD_ORPHAN_ACKNOWLEDGED`。

### 10.3 孤儿类别

租户 `t`，`cutoff = now − aster.guard.result-grace`，`since = now − lookback`（缺省 `P7D`）。`kind` 是固定枚举（snake_case），`severity` 由 kind 决定：

| kind | 判定 | severity | 自动解除 |
|---|---|---|---|
| `side_effect_without_decision` | 上报无 `decisionId`，或该 id 在本租户不存在（含他租户的真实 id——不泄露其存在） | critical | 否（只能确认） |
| `side_effect_unconsumed` | 上报的决策存在，但无收据行或未有效消耗（含 DENY / PENDING / 迁移前旧 ALLOW / 未消耗 ALLOW） | critical | 否 |
| `side_effect_mismatch` | 决策已消耗，但上报的 `attemptId` / `actionHash`（给了才比）与收据行不符；或同一 `(decisionId, source)` 已有更早的已匹配上报（`replayed`） | critical | 否 |
| `consumed_without_result` | 已有效消耗、`consumed_at ∈ [since, cutoff)` 且无结果（执行侧丢失，ADR 0040 §12 D7） | warning | 是：补到结果 → `RESOLVED / late_result` |
| `allow_unconsumed` | 未消耗、`expires_at < cutoff` 且 `issued_at ≥ since`（ADR 0040 §12 D10 治理缺口） | info | 是：检出满 `allow-unconsumed-ttl`（缺省 `P7D`）→ `RESOLVED / aged_out` |
| `consume_on_non_allow` | 已有效消耗，但决策既非 `ALLOW` 也无 `APPROVED` 审批，或收据与决策租户不同（服务端本应 409，出现即 aster-api 自身被绕过） | critical | 否 |
| `result_without_consume` | 有结果而未有效消耗（行级判定；复核失败的结果事件按 ADR 0041 §12 D1 读作未生效，是合法状态，不按事件判） | critical | 否 |
| `orphan_receipt` | 链上有 `ACTION_GUARD_DECISION` 事件（`timestamp ∈ [since, cutoff)`），而本租户无对应决策行：同一幂等键 / 证据相关键并发登记的落败方已先把决策事件写入链、决策行随唯一约束冲突未落库（#522），或进程在两步之间崩溃 | info | 是：检出满 `allow-unconsumed-ttl`（同样从 `detected_at` 起算）→ `RESOLVED / aged_out` |

`orphan_receipt` 不可执行（无决策行即无收据行，consume 一律 404），只是链上噪声；单列一类是为了让它不混入任何 critical 类别、不触发绕过告警，也不被重复计数（同一决策事件只记一行）。根因修复（决策事件与决策行同事务写入）由 #522 承担。

状态 `OPEN | RESOLVED | ACKNOWLEDGED`，解除方式 `late_result | aged_out | acknowledged`。同一事实（`tenant, kind, subject`）永远只有一行，跨多轮对账不重复；OPEN 孤儿不随保留期删除。某租户一次对账有新孤儿或有解除时写一条链事件 `GUARD_RECONCILIATION`（`performedBy=SYSTEM`；metadata `runId, windowStart, windowEnd, newOrphans, resolvedOrphans, openOrphans, newByKind, newOrphanIds[≤64]`，超出加 `newOrphansTruncated: true`）；无变化的对账不入链。

### 10.4 指标与告警

- `guard_orphan_actions{tenant,executor,kind}`（Gauge）：当前 `OPEN` 孤儿数，只发非零序列；每个副本定时从库重读（各副本同值，告警取 `max`）。`executor` 由 `executorId`（或上报 `source`）`@` 之前的组件名小写归一：在白名单 `ASTER_GUARD_RECONCILIATION_EXECUTOR_LABELS`（缺省 `aster-guard-mcp,aster-guard-sdk,aster-cloud-exec`）内取原值，否则 `other`，空为 `unknown`；`tenant` 至多 `ASTER_GUARD_RECONCILIATION_METRIC_MAX_TENANTS`（缺省 100）个，其余合并为 `_other`。原始 `executorId` 只进孤儿 `detail`。
- `guard_orphan_actions_detected_total{kind}`（Counter）：新孤儿数（只由检出它的副本累加，`sum` 正确）。
- `guard_reconciliation_runs_total{outcome}`（Counter，`ok | failed | skipped_locked | skipped_recent`）：按租户计。
- `guard_reconciliation_last_success_seconds`（Gauge）：本副本最近一次完整跑完租户循环的 epoch 秒，告警取 `max`。

告警（k3s `PrometheusRule`）：`AsterGuardBypassSuspected`（任一 critical kind > 0，`for: 0m`，critical）、`AsterGuardExecutionLost`（`consumed_without_result` ≥ 5 持续 30 m，warning）、`AsterGuardReconciliationStale`（`time() − max(last_success) > 1800` 持续 10 m，warning）。info 类（`allow_unconsumed`、`orphan_receipt`）不告警，只在治理页展示。

对账每 5 分钟一轮（`ASTER_GUARD_RECONCILIATION_CRON`），每租户独立事务、多副本以租户 advisory 锁串行；账本类按 `[since, cutoff)` 滑动窗口重扫，上报类处理全部未对账的行（每租户每轮至多 `batch-limit`，超出顺延）。

### 10.5 与 #464 的集成点

- **I1 `executorId` 组件名**：代理用 `aster-guard-mcp@<host>[:<inst>]`，SDK `run` 缺省用 `aster-guard-sdk@<host>`，cloud 执行路径用 `aster-cloud-exec@<pod>`——三者正是 `executor-labels` 缺省白名单；改名须同时改 `ASTER_GUARD_RECONCILIATION_EXECUTOR_LABELS`。
- **I2 工具侧上报**：§3.3 `auth` 隔离模式的工具在核对 consume 票据后落地副作用，是上报端点的首选上报方——建议 SDK 提供 `reportSideEffects(effects[])`（同一 API key，`source` = 工具名），README「工具侧接入」写明「每次实际执行后上报一条，有票据带票据里的 `decisionId / attemptId`，无票据也上报（§10.1 上报义务）；网络重试沿用同一 `effectId`」。不提供该助手时工具可直接调 REST。
- **I3 结果回写解除「执行侧丢失」**：代理 / SDK 的 `reportResult` 失败只记日志（§2.1 第 6 步）→ 对账在宽限后报 `consumed_without_result`，补写后自动解除；consume 响应丢失时报 `aborted`（ADR 0040 §12 D5）不会形成孤儿。
- **I4 #466 部署层**：绕过套件第 1 条（直连原始工具）在隔离成立时直连失败、无副作用、对账零孤儿；用于证明「对账是第二道」（§7 末段）的反证场景是**故意关掉隔离**（去掉 `NetworkPolicy` 或关闭工具侧票据核对）后直连执行，此时工具按 §10.1 上报义务照报，下一轮对账必须出现 `side_effect_without_decision`，由 #466 断言。
- **I5 旧代理 / 旧 SDK**：0.1.0 不 consume → 它放行的每个 ALLOW 都会成为 `allow_unconsumed`（info，不告警），治理页据此列出「未消耗 ALLOW」（ADR 0040 §12 D10）。
