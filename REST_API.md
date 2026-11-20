# Aster Policy API -- REST 端点参考

Base URL: `http://localhost:8080`（开发环境），通过环境变量配置。所有端点使用 `application/json`。

**请求头**: `X-Tenant-Id`（租户隔离，默认 `"default"`）、`X-User-Id`（操作者，默认 `"anonymous"`）。
**权限**: 策略评估端点要求 `MEMBER` 角色；审计/分析端点要求 `ADMIN` 角色。

## 1 策略评估 `/api/v1/policies`

**POST /evaluate** -- 评估已部署策略。请求体: `{"policyModule":"aster.finance.loan","policyFunction":"evaluateLoanEligibility","context":[{...}]}`
响应: `{"result":{...},"executionTimeMs":42,"error":null}`

**POST /evaluate-source?trace=false** -- 直接传入 CNL 源代码评估（无需部署）。`trace=true` 时返回 `decisionTrace`。
请求体: `{"source":"Module aster.example.\n...","context":{"x":25},"locale":"en-US","functionName":"approveLoan"}`
`locale` 默认 `"en-US"`。`functionName` 可选：未指定时由服务端入口选择器决定（仅一个 Rule 时选它；多个 Rule 无入口时返回 `400` + `diagnostics:[{code:"ENTRY_AMBIGUOUS",candidates:[...]}]`，需显式指定）。成功响应回传实际执行的 `executedFunction`。`context` 支持命名格式(Map)和位置格式(数组)。
```bash
curl -X POST 'http://localhost:8080/api/v1/policies/evaluate-source?trace=true' \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant-a' \
  -d '{"source":"Module aster.demo.\nRule evaluate given x:\n  x > 10.","context":{"x":25}}'
```
trace 响应包含: `{"decisionTrace":{"moduleName":"...","functionName":"...","steps":[],"finalResult":true,"executionTimeMs":15}}`

**POST /evaluate-json** -- 传入 Core IR JSON 策略评估。请求体: `{"policy":"{...}","context":{...}}`

**POST /evaluate/batch** -- 批量评估(1-100个)。请求体: `{"requests":[{policyModule,policyFunction,context},...]}`
响应: `{"results":[...],"totalExecutionTimeMs":120,"successCount":2,"failureCount":0}`

**POST /validate** -- 验证策略是否可调用。请求体: `{"policyModule":"...","policyFunction":"..."}`
响应: `{"valid":true,"message":"...","parameterCount":2,"returnType":"Boolean"}`

**POST /schema** -- 从 CNL 源码提取参数模式（用于动态表单）。请求体: `{"source":"...","functionName":"...","locale":"en-US"}`
响应含 `parameters` 数组，每项包含 `name/type/typeKind/optional/position/fields`。

**DELETE /cache** -- 清除策略缓存。请求体: `{"policyModule":"...","policyFunction":"..."}`（均可选）。

**POST /{policyId}/rollback** -- 回滚到历史版本。请求体: `{"targetVersion":1730890123456,"reason":"回滚原因"}`

**GET /{policyId}/versions** -- 获取策略版本历史列表。

**POST /export/package/check**、**POST /export/package/prepare**、**POST /export/package** -- B 形态策略包导出（ADR 0059 P2，修订 3 P2-R8）。
只接受 aster-cloud 的 HMAC 内部调用（其它调用方 `403 export_requires_cloud`）；开关 `ASTER_EXPORT_PACKAGE_ENABLED`（缺省 `false`）关闭时三者均 `404`。
请求体相同：`{"source","locale","aliasSet","allowStructural","vocabulary","entry","target":"jre|node","mode":"b","uiLocale":"en|zh|de|hi","claimedRef":{"policyId","version","status","approvedAt"},"nodeProbes":{…}}`。
入参错误 `400`（`source_required`、`source_too_large`、`target_invalid`、`mode_unsupported`、`ui_locale_invalid`、`claimed_ref_invalid`、`alias_set_too_large`、词汇表超限）；进程设置了 `ASTER_TRUFFLE_FUNC` 时 `503 export_runtime_misconfigured`。
- `check`：编译 + 闭世界检查，返回 `{"targets":{"jre":{exportable,diagnostics,unavailable},"node":{…}},"defaultEntry","entryCandidates"}`；不占并发许可、不写审计。
- `prepare`：另跑探针（≤ 256 条），返回 `{exportable,diagnostics,entry,coreIr,coreIrSha256,positionFreeSha256,probes,probesSha256,probeCount,runtime:{cliName,cliVersion,ecosystem}}`；入口确定不了 `400 entry_required`（带 `entryCandidates`）；target 不可用 `503 export_runtime_unavailable`。
- `export/package`：成功 `200 application/zip`，头 `Content-Disposition`、`X-Aster-Package-Sha256`、`X-Aster-Package-Signed`、`Cache-Control: private, no-store`，并同步入链 `POLICY_EXPORTED`（target `package:jre-b` / `package:node-b`）；不可导出（含 E734 超过 1 MiB）`200 {"exportable":false,"diagnostics":[…]}`；签名不可用 `503 export_signing_unavailable` / kid 未进 CLI 锚 `503 export_signing_kid_unanchored`（均在组装前、不写审计）；`target=node` 须带 cloud 核对过的 `nodeProbes {probesSha256,count,agreed,tsVersion}`，与平台重算不符分别 `400 node_probes_mismatch` / `node_check_incomplete` / `node_ts_version_mismatch`，`target=jre` 带它为 `400 node_probes_unexpected`；入链失败 `500 audit_unavailable`。
`prepare` 与 `export/package` 共用并发许可 `ASTER_EXPORT_PACKAGE_MAX_CONCURRENT`（缺省 2），占满 `503 package_export_busy` + `Retry-After: 1`。

**GET /api/v1/exports/keys** -- 策略包导出验签公钥（JWKS，匿名可读，`Cache-Control: max-age=300`），形状同 `/api/v1/guard/keys`：`{"keys":[{alg,crv,kid,kty,use,x[,exp]}],"revision"}`；未配置导出密钥时 `{"keys":[],"revision":0}`。供 CLI `aster verify --jwks <url>` 使用。

## 2 审计日志 `/api/v1/audit`（ADMIN）

**GET /** -- 查询当前租户全部审计日志。
**GET /type/{eventType}** -- 按事件类型筛选。
**GET /policy/{policyModule}/{policyFunction}** -- 按策略筛选。
**GET /range?startTime={ISO8601}&endTime={ISO8601}** -- 按时间范围筛选。
**GET /verify-chain?start={ISO8601}&end={ISO8601}** -- 验证审计哈希链完整性（上限 30 天）。
响应: `{"valid":true,"brokenAt":null,"reason":null,"recordsVerified":128}`

## 3 策略分析 `/api/v1/audit`（ADMIN）

**GET /stats/version-usage** -- 版本使用统计。参数: `versionId`(必填)、`granularity`(hour/day/week/month，默认day)、`from`/`to`(ISO8601，跨度上限90天)。
**GET /anomalies** -- 异常检测报告。参数: `page`(从1起)、`size`(1-100)、`type`(HIGH_FAILURE_RATE/ZOMBIE_VERSION/PERFORMANCE_DEGRADATION)、`days`(默认30)。
**GET /anomalies/{id}** -- 异常详情（含验证结果）。
**POST /anomalies/{id}/actions/verify** -- 触发异步验证，返回 `202 Accepted`。
**PATCH /anomalies/{id}/status** -- 更新异常状态。支持 `Idempotency-Key` 头。请求体: `{"status":"RESOLVED","notes":"..."}`
状态转换: PENDING->DISMISSED, VERIFIED->RESOLVED, VERIFIED->DISMISSED。
**GET /compare?versionA=1&versionB=2&days=7** -- 对比两个版本性能指标。
**GET /policy-versions/{versionId}/usage?status=RUNNING&page=0&size=20** -- 版本使用的 workflow 列表。
**GET /policy-versions/{versionId}/timeline?from=...&to=...&page=0&size=20** -- 版本使用时间线。
**GET /policy-versions/{versionId}/impact** -- 回滚影响评估。
**GET /workflows/{workflowId}/version-history** -- Workflow 版本历史（workflowId 须为 UUID）。
**GET /artifacts/{sha256}** -- 编译产物查询（sha256 为 64 位十六进制）。
**GET /runtime/{build}/policies** -- 查询使用指定 runtime 版本的策略。

## 4 Workflow `/api/v1/workflows`（MEMBER）

**GET /{workflowId}/events?fromSeq=0** -- 事件历史。
**GET /{workflowId}/state** -- 当前状态。
**GET /metrics** -- 聚合指标（READY/RUNNING/COMPLETED/FAILED/COMPENSATING/COMPENSATED/COMPENSATION_FAILED 计数）。
**GET /by-status/{status}?limit=100** -- 按状态查询 workflow 列表。

## 5 Action Guard `/api/v1/guard`（MEMBER）

**GET /decisions/{id}** -- 回读决策（Bearer 或内部 HMAC；非法 UUID 与跨租户 404 空体）。响应为存储的决策响应（与 `POST /decisions` 同字段：`effectiveOutcome`、`approval`、`executionReceipt`（含签发时的 `revision`）等），另附执行状态（ADR 0054 §3.3，仅回读端点有，幂等重放不含）：收据已 consume 时附 `actionHash` 与 `consumedBy: { attemptId, consumedAt, executorId }`，已结果回写时附 `result: { resultAt, status }`；未发生的不写出。`executionReceipt.payload.policyVersionId` 可缺省（决策无已保存策略版本，如 evaluate-source 锚定的 from-evidence 决策）。`executionReceipt.payload` 另含签名覆盖的策略地址 `policyModule / policyFunction`（ADR 0040 §13 R68，#517；`v` 仍为 1，本修订前签发的收据没有这两键）：执行方须核对它们等于自己期望的策略，不符或缺失按 `receipt_invalid`（`policy_mismatch`）拒绝。
**GET /keys** -- 执行收据验签公钥（JWKS：`alg=EdDSA`、`crv=Ed25519`、`kty=OKP`、`use=sig`、`kid`、`x`=base64url 32 字节公钥，有 `notAfter` 时附 `exp` 秒）与配置 `revision`；仅列出未过期且未撤销的 key，响应头 `Cache-Control: max-age=300`。密钥运维见 `docs/runbooks/guard-receipt-signing-keys.md`。

### 对账（ADR 0054 §10，#465）

Bearer API key 与 HMAC 内部调用双通道；租户取自已验证身份；跨租户、不存在、非数字 id 一律 404 空体。

**POST /side-effects** -- 副作用落地方（工具服务、业务系统、cloud）批量上报（MEMBER，不计量配额）。请求 `{ "effects": [ { "effectId", "source", "occurredAt", "action": { "name" }, "resource"?: { "type", "id" }, "decisionId"?, "attemptId"?, "actionHash"? } ] }`：1–500 条；`effectId` 1–128 字符无控制字符；`source` 匹配 `[a-z0-9][a-z0-9._-]{0,63}`；`occurredAt` 带偏移的 ISO-8601 且不晚于 now + 5 分钟；`action.name` 1–255；`resource.type / id` 各不超过 255；`decisionId` / `attemptId` 为 UUID 形态；`actionHash` 64 位小写 hex。200 `{ "accepted": n, "duplicates": m }`，幂等键 `(source, effectId)`，重复上报不改原行。任一条不合法 → 400 `{ "error": "invalid_side_effect", "index", "field" }` 整批不写；条数不合法 → 400 `{ "error": "invalid_side_effect", "field": "effects" }`；重复键 / 非法 JSON → 400 `{ "error": "invalid_side_effect" }`。上报粒度：同一 `source` 对同一 `decisionId` 恰报一条，第二条已匹配的上报判 `side_effect_mismatch`（`replayed`）。
**GET /reconciliation** -- 摘要（MEMBER）：`{ "lastRun": { "runId", "finishedAt", "windowStart", "windowEnd", "newOrphans", "resolvedOrphans", "openOrphans" } | null, "open": { "<kind>": n }, "openTotal", "resultGrace", "lookback" }`（`open` 只列非零）。
**GET /reconciliation/orphans** -- 孤儿列表（MEMBER）：`?status=OPEN|RESOLVED|ACKNOWLEDGED`（缺省 OPEN）`&kind=<kind>&severity=critical|warning|info`（可逗号多选，如 `critical,warning`；不得重复或含空项；缺省不过滤；与 `kind` 同时给时取交集，可能为空页；ADR 0061 §3.4）`&limit=1..200`（缺省 50）`&before=<id>`；`{ "items": [ { "id", "kind", "severity", "status", "decisionId"?, "sideEffectId"?, "executor", "executorId"?, "detectedAt", "resolvedAt"?, "resolution"?, "acknowledgedBy"?, "ackComment"?, "detail" } ], "next"? }`，`id` 降序，`next` 作下一页 `before`；`ackComment` 只在 `ACKNOWLEDGED` 行出现（确认时提交的说明）。非法参数 → 400 `{ "error": "invalid_query" }`。旧版本服务端会忽略 `severity`（返回全部 severity），调用方如需兼容旧版应在本地再按 `severity` 过滤一次。
**POST /reconciliation/orphans/{id}/ack** -- 管理员确认（ADMIN）：体 `{ "comment": "<1–1000 字符>" }` → 200 同列表项（含 `ackComment`），并写链事件 `GUARD_ORPHAN_ACKNOWLEDGED`；非 OPEN → 409 `{ "error": "not_open", "status" }`；`comment` 不合法 → 400 `{ "error": "invalid_ack", "field": "comment" }`，非法 JSON / 重复键 → 400 `{ "error": "invalid_ack" }`。

`kind` 与 severity：`side_effect_without_decision` / `side_effect_unconsumed` / `side_effect_mismatch` / `consume_on_non_allow` / `result_without_consume`（critical）、`consumed_without_result`（warning，补到结果自动解除 `late_result`）、`allow_unconsumed` / `orphan_receipt`（info，检出满 `ASTER_GUARD_RECONCILIATION_ALLOW_UNCONSUMED_TTL` 后 `aged_out`）。判定见 ADR 0054 §10.3。

## 6 错误处理

评估端点业务错误: `{"result":null,"executionTimeMs":0,"error":"CNL 解析失败: ..."}`

| 状态码 | 含义             |
|--------|-----------------|
| 400    | 参数校验或格式错误 |
| 404    | 资源不存在        |
| 409    | 幂等冲突          |
| 500    | 服务器内部错误     |
