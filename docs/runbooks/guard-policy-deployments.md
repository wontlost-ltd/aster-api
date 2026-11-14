# Guard 策略部署（ADR 0054 §11 D1，R66）

aster-cloud 把策略的**默认版本**部署为 aster-api 的活跃版本，使 `POST /api/v1/guard/decisions`（aster-guard 代理 / SDK）能按 `policyModule / policyFunction` 解析到它。只接受内部 HMAC 通道（cloud → aster-api），Bearer 一律 403 `internal_only`。

## 端点

| 方法 | 路径 | 成功 | 说明 |
|---|---|---|---|
| PUT | `/api/v1/guard/policy-deployments/{cloudPolicyId}` | 200 | 部署或切换到该 cloud 版本 |
| DELETE | `/api/v1/guard/policy-deployments/{cloudPolicyId}?generation=<n>` | 204 | 撤回；无此部署 404 空体 |

PUT 请求体：

```json
{ "generation": 3, "cloudVersionId": "…", "cloudVersion": 2, "moduleName": "acme.billing", "functionName": "decide",
  "source": "Module acme.billing. …", "locale": "en-US", "aliasSet": { "TIMES": ["multiplied by"] },
  "vocabulary": { … DomainVocabulary … }, "sourceSha256": "<sha256(源码 UTF-8) 小写 hex>" }
```

200 响应：`{ cloudPolicyId, cloudVersionId, created, functionName, moduleName, policyVersionId, sourceHash, version }`（`created=false` 表示同一 cloud 版本的重放或切回旧版本）。

租户取签名内的 `X-Tenant-Id`（团队策略为团队 id），签名用户为空 / `anonymous` → 400 `user_required`。

## 错误码

| 状态 | `error` | 含义 / 处置 |
|---|---|---|
| 400 | `invalid_deployment` | 字段缺失或越界、`sourceSha256` 与源码不符、源码 `Module` 头与 `moduleName` 不符、重复键 |
| 409 | `stale_generation` | 已有更大的 `generation`（响应带 `generation`）；cloud 视为已被更新的请求取代 |
| 409 | `address_conflict` | 同租户另一策略（含 aster-api 原生策略）已占用该 `module.function` |
| 409 | `deployment_mismatch` | 同一 `cloudVersionId` 已以不同源码 / 地址部署过 |
| 409 | `concurrent_activation` | 同一策略的另一次激活并发提交；可重试 |
| 422 | `compile_failed` | 源码或别名编译失败（带 `errors / diagnostics`） |
| 422 | `function_not_found` | 源码中没有 `functionName` |
| 422 | `vocabulary_invalid` | 词汇超出结构上限或无法解析 |
| 422 | `stability_blocked` | ADR 0031 稳定性门禁拒绝（Experimental 特性） |

## 语义要点

- 版本以 `source_kind='cloud-deploy'` 落库，DRAFT → APPROVED（`approved_by` = 签名用户，跳过套餐 reviewer，保留稳定性门禁）→ ACTIVE（乐观锁并发保护），写审计事件 `POLICY_DEPLOYED` / `POLICY_WITHDRAWN`。整次部署单一事务：任一步失败不留行，现活跃版本不变。
- 版本冻结词汇写在 `policy_versions.vocabulary_json`，编译与 `source_envelope_sha256` 读同一快照；无词汇的存量版本 envelope 不变。
- 同一 cloud 策略的部署 / 撤回按 `(tenant, cloudPolicyId)` advisory 锁串行，`generation` 比较原子。新默认版本改了模块头 / 入口时，旧地址自动撤回。
- `GET /api/v1/guard/decisions/{id}` 回读带 `policyModule / policyFunction`，供 `auth` 模式工具侧核对（ADR 0054 §11 D12）。

## 排查

```sql
-- 某租户的 cloud 部署
SELECT c.module_name, c.function_name, c.external_ref, c.external_generation, c.default_version_id
  FROM policy_catalog c WHERE c.tenant_id = :tenant AND c.external_ref IS NOT NULL;
SELECT id, external_ref, status, active, approved_by, created_at
  FROM policy_versions WHERE tenant_id = :tenant AND source_kind = 'cloud-deploy' ORDER BY id DESC;
```

## 发布顺序

本端点（aster-api，迁移 `V6.39.0`）先上线，aster-cloud 的部署挂点（设默认版本 / 删除策略时调用）后合并；cloud 上线后对生产执行一次 `pnpm guard:backfill`（先 dry-run 再 `--apply`）。
