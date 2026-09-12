# Strategy Replay Cloud 专项交叉审查证据

**审查时间**：2026-08-05（Pacific/Auckland）  
**审查对象**：`/tmp/review-cloud.diff`（1440 行）与 `/tmp/audit/aster-cloud` 分支 `review/all-phases`，HEAD `eb3dbee`  
**审查方式**：只读业务源码；仅创建本证据文件，未修改 `/tmp/audit/aster-cloud` 业务文件。  
**结论**：**退回（存在 1 个已复现的 P0 数据边界问题，以及 1 个 P0 外部 API 契约阻塞）**。

## 1. 范围、降级与命令结果

### 1.1 范围

审阅了完整 diff、当前分支完整相关文件、迁移与 schema、相关测试、`package.json`、Vitest 配置，并沿数据链额外审阅：

- `src/services/policy/policy-api.ts`
- `src/services/policy/cnl-executor.ts`
- `src/lib/policy-execution-log.ts`
- dashboard/v1 execute 写入路径
- CSRF middleware/gate
- API-key 与既有幂等实现
- 迁移 runner 与 baseline 脚本

### 1.2 工具降级记录

- 指令要求先用 `sequential-thinking`：当前工具集中不存在该工具，已用逐项 P0/P1/P2 推理清单人工模拟，未跳过结论。
- 指令要求优先用 `code-index`：当前工具集中不存在该工具，改用 `rg`，并逐文件用 `nl -ba` 取当前源码行号。
- 未调用外部搜索；本任务全部结论可由仓库源码与本地执行验证。

### 1.3 可重复命令与结果

| 命令 | 结果 |
|---|---|
| `wc -l /tmp/review-cloud.diff` | 1440 行 |
| `sed -n` 分段读取 `1-1440` | **完整 diff 已读** |
| `rg '^diff --git ' /tmp/review-cloud.diff` | 10 个 diff 文件（4 测试、2 route、1 UI、1 schema、2 analytics） |
| `./node_modules/.bin/vitest run <6 个相关测试文件>` | **通过：12 files / 162 tests**（saas + on-prem 双 project） |
| `./node_modules/.bin/tsc --noEmit` | **通过** |
| `./node_modules/.bin/eslint <9 个本次文件>` | **通过** |
| `git diff 81143da..HEAD --check` | **通过** |
| `pnpm ...` | **工具故障**：全局 pnpm 指向已删除的 Node 17 路径；使用仓库 `node_modules/.bin/*` 补偿并完成验证 |
| `command -v psql; command -v docker; docker info` | `psql`/`docker` 均不可用，`docker: command not found` |

真实 PostgreSQL 的迁移、upsert 与查询计划因此标记为**未验证**，没有用 mock 结果冒充真实 DB 证明。

### 1.4 TraceSkeleton 值渗透复现（P0）

```sh
./node_modules/.bin/tsx -e "import { buildReplayColumns } from './src/lib/policy-execution-log.ts'; const refs={policyVersionRowId:'v',policyVersion:1,sourceToolchainId:'t',vocabSnapshotRef:[],locale:'en',aliasSetJson:{},functionName:'f'}; const hostile={schemaVersion:'trace-skeleton/v1',steps:[{stepId:'0.1',expression:'if condition',matched:true,depth:0,result:{accountNumber:'1234'}}]}; console.log(JSON.stringify(buildReplayColumns(undefined,refs,hostile as any).traceSkeletonJson));"
```

实际输出：

```json
{"schemaVersion":"trace-skeleton/v1","steps":[{"stepId":"0.1","expression":"if condition","matched":true,"depth":0,"result":{"accountNumber":"1234"}}]}
```

这证明 Cloud 的运行时边界会把 `result`/业务值原样带入 `traceSkeletonJson`；不是仅凭类型推测。

## 2. P0 逐项结论

### P0-1 funnel/outcome 的所有 Execution 读取与 IDOR

**结论：通过；未发现 IDOR。** 两个目标 route 的 Execution 读取均显式约束当前 `userId`。

`src/app/api/policies/[id]/funnel/route.ts:61-72`：

```ts
const conds = [eq(executions.policyId, id), eq(executions.userId, session.user.id)];
// ...
.from(executions)
.where(and(...conds))
```

此外 policy 归属本身也带租户条件，`src/app/api/policies/[id]/funnel/route.ts:43-47`：

```ts
.from(policies)
.where(and(eq(policies.id, id), eq(policies.userId, session.user.id)))
.limit(1);
```

`src/app/api/v1/executions/[id]/outcome/route.ts:79-83`：

```ts
.select({ id: executions.id, policyId: executions.policyId })
.from(executions)
.where(and(eq(executions.id, id), eq(executions.userId, session.user.id)))
.limit(1);
```

测试也捕获 Drizzle 条件而不是仅配置 mock 空返回：

- `src/__tests__/api/policy-funnel-route.test.ts:115-127`
- `src/__tests__/api/execution-outcome-route.test.ts:94-99`

限制：这些仍是 mock AST 断言，不是真实 PostgreSQL 集成测试；但源码条件本身明确成立。

### P0-2 outcome 外部写端点：鉴权、CSRF、幂等与重复回传

#### P0-2a 外部鉴权

**确认发现（P0 契约阻塞）：该 `/api/v1` 对外写端点只认浏览器 cookie session，不认项目既有 Bearer API key。**

目标端点，`src/app/api/v1/executions/[id]/outcome/route.ts:22-29`：

```ts
export async function POST(request: NextRequest, ...) {
  const session = await getSession();
  if (!session?.user?.id) return ...401;
```

而既有对外 v1 execute，`src/app/api/v1/policies/[id]/execute/route.ts:49-64`：

```ts
const [auth, { id }, bodyResult] = await Promise.all([
  authenticateApiRequest(req),
  params,
  req.json().catch(() => null),
]);
if (!auth.success) return ...;
const { userId, apiKeyId } = auth;
```

同级 v1 policies 也在 `src/app/api/v1/policies/route.ts:1-17` 使用 `authenticateApiRequest`。API-key helper 明确要求 Bearer，`src/lib/api-keys.ts:167-193`。

因此无浏览器 session 的客户后端即使携带合法 API key，route 仍会 401。若产品有意把它定义成 BFF/cookie route，则路径与“客户外部回传”的文档/命名应调整；按当前声明，它不是可用的外部 ingestion API。

#### P0-2b CSRF

**通过（架构级），目标 route 单测未直接覆盖。**

所有 `/api/**` 都进入 middleware gate，`src/middleware.ts:43-51`：

```ts
if (pathname.startsWith('/api/')) {
  const denied = applyCsrfGate(request);
  if (denied) return denied;
  return NextResponse.next();
}
```

目标路径不在豁免列表；变更方法执行 Origin/Referer 校验，`src/lib/security/csrf-gate.ts:55-65`：

```ts
if (CSRF_SAFE_METHODS.has(request.method.toUpperCase())) return null;
if (isCsrfExempt(pathname)) return null;
const csrf = checkCsrf(request);
if (csrf.allowed) return null;
return NextResponse.json(..., { status: 403 });
```

`src/lib/security/csrf-gate.ts:30-40` 的豁免前缀不含 `/api/v1/executions`。通用 v1 cookie route 的跨站拒绝测试在 `src/__tests__/lib/middleware-csrf-gate.test.ts:74-80`，但没有直接列 outcome 路径。

#### P0-2c 幂等与重复回传

**部分通过：数据库层只保留一行；严格请求幂等不成立。**

`src/app/api/v1/executions/[id]/outcome/route.ts:89-105`：

```ts
await db.insert(executionOutcomes).values({
  id: globalThis.crypto.randomUUID(), executionId: id, ...
}).onConflictDoUpdate({
  target: executionOutcomes.executionId,
  set: { outcome, value, occurredAt, note, reportedAt: new Date() },
});
```

`src/db/schema.ts:791-797` 用 executionId unique index 保证并发/重复写不会堆叠：

```ts
uniqueIndex('ExecutionOutcome_executionId_key').on(table.executionId),
```

重复行为是 **last-write-wins 覆盖**；相同请求重试也会改变 `reportedAt`，不同 payload 复用同 executionId 会无冲突提示地覆盖。它没有复用仓库既有的 `Idempotency-Key` 机制；后者在 `src/app/api/v1/domain-vocabularies/terms/route.ts:190-206` 明确处理 replay/conflict。故：

- “不产生重复行”通过；
- “同请求重放无新副作用/响应可重放”不通过；
- “更正覆盖”是显式设计，但 schema 注释所说的“审计日志留痕”并不存在；只有最新 `reportedAt`，旧值和旧时间均被抹除。

建议：先明确 API 契约。如果要求 retry-safe，复用 `withIdempotency`；如果要求更正历史可审计，不能仅覆盖当前行。

### P0-3 TraceSkeleton 是否结构性无 result/业务数据

**确认发现（P0）：静态接口无 `result`，但运行时结构边界不存在，值渗透已复现。**

静态类型本身正确，`src/services/policy/policy-api.ts:119-128`：

```ts
export interface PolicyTraceSkeletonStep {
  stepId: string;
  expression: string;
  matched: boolean;
  depth: number;
}
```

但 HTTP 响应直接 `response.json()` 泛型断言，没有解析/白名单投影，`src/services/policy/policy-api.ts:325-342`：

```ts
const response = await fetch(url, ...);
// ...
return response.json();
```

随后 `src/lib/policy-execution-log.ts:161-167` 无条件原样保存：

```ts
if (traceSkeleton) {
  base.traceSkeletonJson = traceSkeleton;
}
```

数据库列是任意 jsonb，`src/db/schema.ts:692-701`：

```ts
// 骨架结构上不含任何值
traceSkeletonJson: jsonb('traceSkeletonJson'),
```

现有测试 `src/__tests__/lib/policy-execution-replay-columns.test.ts:210-218` 只用手工构造的干净 fixture 验证“不含 result”，没有给带额外字段的实际边界输入；因此是假阴性。第 1.4 节已证明 hostile/upstream-regression JSON 中的 `result.accountNumber` 会原样落库。

必须在 Cloud 信任边界做运行时 schema 校验并**重建**对象（只复制 schemaVersion/moduleName/functionName 与每步四个允许字段），拒绝或剥离额外字段；只补 TypeScript 类型无效。

### P0-4 Drizzle SQL：N+1、索引、参数类型、0043 空库重放

#### N+1

**通过：目标路径无数据量相关的查询循环。** funnel 固定 2 次查询（policy ownership + executions），outcome 固定 2 次写链路查询（execution ownership + upsert）。相关位置：

- `src/app/api/policies/[id]/funnel/route.ts:43-47,67-72`
- `src/app/api/v1/executions/[id]/outcome/route.ts:79-105`

#### 索引

**当前正确性通过，funnel 查询性能有 P1 风险。**

Execution 当前只有独立单列索引，`src/db/schema.ts:730-741`：

```ts
index('Execution_userId_idx').on(table.userId),
index('Execution_policyId_idx').on(table.policyId),
index('Execution_createdAt_idx').on(table.createdAt),
```

而 funnel 查询形态是 `policyId + userId + ORDER BY createdAt DESC LIMIT`（`route.ts:61-72`）。没有 `(policyId, userId, createdAt DESC)` 或至少 `(policyId, createdAt DESC)` 复合索引，数据库可能先取所有 policy 行再排序；`MAX_SAMPLE` 只限制返回/应用处理量，不限制排序候选量。真实 `EXPLAIN ANALYZE` 因无 PostgreSQL 未验证，故性能影响标为风险而非伪称已测退化。

Outcome 当前 writer 所需的 PK/unique 已有；`src/db/schema.ts:791-797` 的单列索引与 `drizzle/0043_execution_outcome.sql:13-17` 一致。尚无 outcome read query，不能凭假设要求额外复合索引。

#### 参数类型 / CASE THEN int cast

**本审查范围不适用，未发现相关 raw SQL/CASE。** 对目标 route、analytics 与 0042/0043 执行 `rg`，没有 `CASE ... THEN ${...}`。因此不存在本 diff 的“THEN 参数须 `::int`”问题，也不能宣称执行验证。当前 Drizzle 参数值得注意的是 `version`/日期未经有限性验证，见 P2。

#### `0043_execution_outcome.sql` 从空库重放

**未验证（真实 PostgreSQL 不可用）；静态检查单独应用 0043 可自洽。**

`drizzle/0043_execution_outcome.sql:1-17` 只创建独立表和 5 个索引，没有引用既有表/FK/enum，静态上可在空 schema 执行。推荐复验：

```sh
createdb aster_cloud_0043_replay
psql -v ON_ERROR_STOP=1 -d aster_cloud_0043_replay -f drizzle/0043_execution_outcome.sql
psql -v ON_ERROR_STOP=1 -d aster_cloud_0043_replay -c '\d+ "ExecutionOutcome"'
```

阻塞证据：本机 `psql` 不存在，Docker 也不存在（`docker: command not found`）。

另需区分“单独 0043 空 schema”与“全迁移链从全新 DB”：仓库 `drizzle/0001_grandfather_legacy_tier.sql:1` 起即 ALTER 已有 `User`，且 `scripts/baseline-drizzle.ts:2-18` 明说 0001-0003 是已手工应用后的 baseline。全迁移链不是 fresh-DB bootstrap，这不是 0043 新引入的问题。

## 3. P1 逐项结论

### P1-1 `stepId + expression` 联合分组修复

**核心聚合修复通过，但 UI 集成不完整。**

`src/lib/analytics/condition-funnel.ts:94-117` 使用复合键并保留首次出现顺序：

```ts
const key = `${step.stepId}\u0000${step.expression}`;
let cur = acc.get(key);
// ...
acc.set(key, cur);
order.push(key);
```

回归测试覆盖“同 stepId 不同 expression 拆分”和“相同组合继续合并”，`src/__tests__/lib/condition-funnel.test.ts:127-159`。已知 expression 占位、depth=0（ADR0032）不作为新问题；当前缓解确实仍无法区分“同 stepId + 同占位 expression”的不同源码节点，但这正是已登记的引擎信息缺失。

**新发现（P1）：React key 仍只用 stepId。** 联合分组后同一列表可合法出现多个相同 stepId；UI 却在两处使用重复 key：

`src/components/policy/condition-funnel-panel.tsx:111-113`：

```tsx
{data.steps.map((s) => (
  <FunnelRow key={s.stepId} ... />
))}
```

`src/components/policy/condition-funnel-panel.tsx:126-130`：

```tsx
{data.deadBranches.map((s) => (
  <li key={s.stepId}>...</li>
))}
```

这与新数据模型冲突，会触发 duplicate-key warning，并可能在刷新/重排时错误复用 DOM。应使用与聚合一致的稳定联合键（或给 `FunnelStep` 暴露显式 key）。没有组件测试覆盖该回归。

### P1-2 deadBranches 在采样下是否误报

**确认风险（P1）：会把“本次有限样本内未命中”直接命名为死分支。**

`src/lib/analytics/condition-funnel.ts:128-135`：

```ts
sampleSize: skeletons.length,
deadBranches: steps.filter((s) => s.evaluated > 0 && s.matched === 0),
```

只要采样中一次求值未命中，即会进入 deadBranches；它不可能证明策略全历史或全输入域“死”。通用 `sampleNote` 能缓解，但没有置信下限/最少 evaluated 数，字段名与注释仍是强断言。更关键的是该 Panel 当前没有调用方或 labels 接线（见 P1-5），无法证明实际 deadHint 会清楚写成“样本内从未命中”。建议 API/类型改名为 `neverMatchedInSample`，并在 UI 强制显示 evaluated 与采样窗口。

### P1-3 `MAX_SAMPLE=2000` 截断

**确认发现（P1）：截断静默失真。**

`src/app/api/policies/[id]/funnel/route.ts:25-26,53-56,67-72` 把查询硬限制到最近最多 2000 条，但响应只返回 `limit`，没有 `truncated`、总匹配数、next cursor 或“候选数 > limit”的探测：

```ts
const MAX_SAMPLE = 2000;
// ...
.orderBy(desc(executions.createdAt))
.limit(limit);
```

当恰好返回 2000 行时，消费者无法区分总数据是 2000 还是 200 万；coverage 也只是这 2000 行内部的骨架覆盖率。`src/components/policy/condition-funnel-panel.tsx:93-105` 只显示样本与 coverage，不显示“已达上限/仅最近 N 条”。现有测试 `policy-funnel-route.test.ts:152-155` 只断言 cap 数值，未断言诚实截断元数据。

### P1-4 what-if `estimatedValueDelta` 无基线 null 语义与调用方/UI

**纯函数通过；调用方/UI 未实现，故端到端未验证且当前不可交付。**

`src/lib/analytics/whatif-estimate.ts:139-153` 正确区分无金额基线与零变化：

```ts
const baselineAvgValue = baseValueCount > 0 ? baseValueSum / baseValueCount : null;
const estimatedValueDelta =
  baselineAvgValue === null ? null : (newlyApproved - newlyRejected) * baselineAvgValue;
```

测试 `src/__tests__/lib/whatif-estimate.test.ts:63-74` 明确断言 null 不等于 0。但是全仓 `rg "estimatedValueDelta|estimateWhatIf" src` 除定义/测试外没有 API、hook、组件或页面调用；因此不存在可审查的 UI null 呈现，不能把单元测试当作调用方正确性证据。

**附加统计风险（P1）**：置信度使用全部 `withOutcome`，而金额估计只依赖 `baseValueCount`。`src/lib/analytics/whatif-estimate.ts:155-157`：

```ts
if (withOutcome >= MIN_FOR_MODERATE && baseApproved > 0) confidence = 'moderate';
else if (withOutcome >= MIN_FOR_ESTIMATE && baseApproved > 0) confidence = 'low';
```

例如 199 个 rejected outcome + 1 个 approved/value 样本即可得到 `moderate`，尽管金额基线只有 1 条。应按估计目标使用 `baseValueCount`（金额）与 `baseApproved`（正面率）分别表达置信度。

### P1-5 功能接线完整性

**确认发现（P1）：新增 UI 与 Phase 4 纯函数均未接线。**

- `ConditionFunnelPanel` 只在 `src/components/policy/condition-funnel-panel.tsx:33` 定义；全仓无 import/渲染调用，也没有对应 messages labels。
- `estimateWhatIf` 只在 `src/lib/analytics/whatif-estimate.ts:92` 定义并被测试引用；无读取 `ExecutionOutcome` 的 query、API 或 UI。

所以当前分支实际可访问的新增能力只有 funnel GET 与 outcome POST；漏斗面板、what-if 估算和 outcome→what-if 数据链都未完成。

## 4. P2：测试、错误处理、边界与风格

### 4.1 测试覆盖评估

| 项目 | 结论 | 证据 |
|---|---|---|
| SQL/迁移 | **未覆盖** | outcome/funnel route 全部 mock `@/lib/prisma`；无 0043 integration test |
| 鉴权 | **部分覆盖** | 401 与 where AST 有测试；未覆盖合法 Bearer 外部调用，也未识别 auth 模式错位 |
| 租户 | **较好但仍是 mock** | 两 route 都断言 `userId` 条件；无真实 DB 脏行/跨租户集成 |
| 重复写 | **弱断言/假信心** | `execution-outcome-route.test.ts:111-115` 只断言 `conflict` truthy；没有连续两次真实写、并发、reportedAt、覆盖值或单行计数 |
| CSRF | **架构通用测试有，目标路径无** | gate 测 v1 cookie 示例，未列 `/api/v1/executions/e1/outcome` |
| Trace PII | **假信心** | 只检查干净 fixture 的 key；不测响应含额外 `result` 时的剥离/拒绝 |
| null UI | **未覆盖** | what-if 无 UI/调用方；只有纯函数 null 测试 |
| 采样截断 | **未覆盖语义** | 只断言 limit=2000，不断言 truncation 标识 |
| 联合分组 UI | **未覆盖** | 无 `ConditionFunnelPanel` 测试，未发现 duplicate React key |

相关双项目测试虽 162/162 通过，但不能覆盖上述真实 DB/端到端边界。

### 4.2 错误与输入边界

1. **funnel 查询参数会把非法值带到 DB（P2）**。`src/app/api/policies/[id]/funnel/route.ts:57-65` 对 `from/to` 直接 `new Date(...)`，对 `version` 直接 `Number(...)`。`?from=xx` 产生 Invalid Date，`?version=abc` 产生 NaN，可能在 Drizzle/Postgres 参数序列化阶段抛 500；无 400，也无 route try/catch。测试只有合法 version 与 limit 非法回退。
2. **numeric(20,4) 与入口校验不一致（P2）**。`outcome/route.ts:55-64` 只检查 `Number.isFinite`，会接受超出 numeric(20,4) 精度的 `1e100` 并在 DB 失败；还会把空字符串转为 0。`src/db/schema.ts:782-783` 明确列精度。应使用严格 number/decimal 字符串格式并校验范围/小数位。
3. **DB/Auth 异常没有结构化恢复（P2）**。outcome 与 funnel 没有外层 try/catch；对照 `src/app/api/v1/domain-vocabularies/terms/route.ts:139-149,190-215`，既有模式会区分 session lookup 与 service failure，记录日志并返回稳定 envelope。
4. **审计注释与实现不一致（P2）**。`src/db/schema.ts:762-764` 声称更正“靠 reportedAt + 审计日志留痕”，但本变更未调用 audit log，也没有历史表；覆盖会删除旧内容。
5. **无 FK 的数据一致性风险（P2）**。`drizzle/0043_execution_outcome.sql:1-17` 的 executionId/policyId/userId 都无外键。当前 writer 在应用层查 execution 可防正常 orphan，但直接写、删除 execution、未来 writer 都可产生孤儿或冗余租户字段不一致。是否加 FK/级联需按仓库迁移策略决定，至少应记录不变量与测试。

风格/静态质量：受影响文件 ESLint、tsc、diff-check 均通过；主函数缩进未超过 3 层，命名和中文注释整体符合仓库风格。

## 5. 三个既有模式对比

### 模式 A：对外 `/api/v1` 用 Bearer API key

- 既有：`src/app/api/v1/policies/[id]/execute/route.ts:49-64`、`src/app/api/v1/policies/route.ts:9-17`。
- 本次：`src/app/api/v1/executions/[id]/outcome/route.ts:22-29` 改用 `getSession`。
- 结论：若 endpoint 是“客户系统事后回传”的外部 API，本次偏离既有模式且无法被客户后端调用。

### 模式 B：Execution 详情读取显式 tenant predicate

- 既有：`src/lib/policy-execution-log.ts:353-361` 同时 `eq(executions.id, executionId)` 与 `eq(executions.userId, userId)`。
- 既有增强：`src/app/api/policies/[id]/executions/[execId]/verify-parity/route.ts:40-49` 同时约束 execution id、policy id、user id。
- 本次 outcome：`outcome/route.ts:79-83` 同时约束 execution id 与 user id；funnel 同时 policyId 与 userId。
- 结论：本次租户过滤符合 canonical 模式，IDOR 项通过。

### 模式 C：写 API 的 Idempotency-Key 与错误封装

- 既有：`src/app/api/v1/domain-vocabularies/terms/route.ts:190-215` 用 `withIdempotency`，同 key/同 body replay、不同 body 409，并对 DB 失败返回稳定 envelope。
- 底层：`src/lib/api/idempotency.ts:148-163` 明确定义并发 reservation 与响应 replay。
- 本次：仅以 executionId unique + upsert 做 last-write-wins，未实现请求 replay/conflict，且 DB 异常裸抛。
- 结论：满足“一执行一当前结果”，不满足仓库已有的严格 retry-safe 写请求模式。

### 模式 D（补充）：CSRF 集中网关

- 既有：`src/middleware.ts:43-51` + `src/lib/security/csrf-gate.ts:55-65`。
- 本次路径未被豁免，因此 cookie 模式有集中 CSRF 防护。
- 测试应增加目标路径实例，避免未来豁免表变更时静默失守。

## 6. 审查五层法

### 第一层：数据结构（25%）

- 好：funnel 用 `Map<stepId+expression, FunnelStep>` 消除原 stepId 错聚；Outcome 用 executionId unique 表达“一执行一当前结果”。
- 致命：TraceSkeleton 只在 TS 类型层声明“无值”，运行时 jsonb 数据结构无约束且直接持久化。
- 风险：Outcome 冗余 userId/policyId 无 FK/DB invariant；upsert 丢失旧结果，和“审计留痕”目标冲突。

### 第二层：特殊情况（20%）

- 联合分组消除了不同 expression 的主要特殊情况；ADR0032 已知同占位 expression 残留不重复报新问题。
- deadBranches 把“有限样本未命中”当“死”是语义特殊情况未被数据模型表达。
- what-if 用 null 表达无基线是好设计；但置信度没有按实际基线样本结构拆分。

### 第三层：复杂度（25%）

- analytics 纯函数短小，嵌套不深，复杂度可控。
- 两 route 均为线性流程，固定查询数，无 N+1。
- 问题不是代码太复杂，而是少了信任边界 validator、真实幂等契约与截断状态三个必要概念。

### 第四层：破坏性（15%）

- 新表/nullable 新列为附加性 schema 变更，静态上不破坏旧行。
- `/api/v1` auth 模式偏离会让预期 Bearer 客户无法接入，是外部契约级破坏。
- 联合分组改变返回 cardinality 是正确修复，但 UI key 没同步，形成新渲染缺陷。

### 第五层：可行性（15%）

- 漏斗、outcome、what-if 都解决真实分析缺口，方案规模本可合理。
- 但当前没有把 Panel 接页面，也没有把 outcome read 接到 what-if；Phase 4 只有库函数，无法兑现业务闭环。
- P0 Trace runtime projection 是小而必要的修复；复用既有 API-key/auth、idempotency、error envelope 比新增自研机制更合适。

## 7. 评分与最终建议

**品味评分**：一般（核心纯函数清晰，但边界与接线不完整）  
**技术维度**：68/100

- 代码质量：74
- 测试覆盖：58（单测多，但 DB/迁移/端到端关键面缺失）
- 规范遵循：72

**战略维度**：61/100

- 需求匹配：58（外部 auth、UI/what-if 数据链不完整）
- 架构一致：64（租户/CSRF 一致，auth/idempotency 偏离）
- 风险控制：60（Trace P0 与静默截断）

**综合评分：65/100；建议：退回。**

### 必须先修（P0）

1. 在 policy API 响应进入 Cloud 时对 TraceSkeleton 做运行时严格解析/白名单投影；增加 hostile fixture，证明 `result`、`actual`、任意额外字段无法落库。
2. 明确 outcome 是外部 Bearer API 还是 cookie BFF。按当前 `/api/v1` 与“客户回传”契约，应改用/兼容 `authenticateApiRequest`，并使用其 userId 做 execution tenant predicate；补合法/非法 Bearer 测试。

### 合入前应修（P1）

3. React key 改为联合稳定 key；增加同 stepId 不同 expression 的组件测试。
4. 返回明确 truncation 元数据（建议 fetch `limit+1`），UI 常驻显示最近样本/截断；dead branch 改成“样本内从未命中”。
5. 明确请求幂等与“更正”契约；若 retry-safe，复用 `withIdempotency`；若需审计，保留版本/事件，不要声称 reportedAt 是历史。
6. what-if 置信度按 `baseValueCount`/`baseApproved`，并完成 Outcome 读取/API/UI 接线和 null UI 测试。
7. 对 funnel 真实数据规模跑 `EXPLAIN (ANALYZE, BUFFERS)`，按结果增加 `(policyId, userId, createdAt DESC)`（或等价最小索引）。

### 未验证阻塞

- 真实 PostgreSQL：0043 空库执行、全链迁移、upsert 重复/并发、query plan。
- 原因：环境无 psql、Docker；仓库已有 testcontainers 测试基础，但当前主机无法启动容器。
