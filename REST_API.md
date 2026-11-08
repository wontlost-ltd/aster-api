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

**GET /keys** -- 执行收据验签公钥（JWKS：`alg=EdDSA`、`crv=Ed25519`、`kty=OKP`、`use=sig`、`kid`、`x`=base64url 32 字节公钥，有 `notAfter` 时附 `exp` 秒）与配置 `revision`；仅列出未过期且未撤销的 key，响应头 `Cache-Control: max-age=300`。密钥运维见 `docs/runbooks/guard-receipt-signing-keys.md`。

## 6 错误处理

评估端点业务错误: `{"result":null,"executionTimeMs":0,"error":"CNL 解析失败: ..."}`

| 状态码 | 含义             |
|--------|-----------------|
| 400    | 参数校验或格式错误 |
| 404    | 资源不存在        |
| 409    | 幂等冲突          |
| 500    | 服务器内部错误     |
