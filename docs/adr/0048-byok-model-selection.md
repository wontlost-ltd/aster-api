# ADR 0048：自带 Key（BYOK）的模型选择与调用优先级

- 状态：**ACCEPTED**（2026-10-15）
- 实施计划：`docs/superpowers/plans/2026-10-15-byok-model-selection.md`
- 决策者：用户已拍板「model 为**可选**配置，默认沿用平台 per-provider 默认；系统查询 provider 可用模型列表**只用于校验与提示**，不自动决策；调用优先级保持单队列，粒度从 key 扩为 key+model；平台凭证路径保留现有的 anthropic 模型替换，仅 BYOK 指定值原样发出」
- 相关：ADR 0037（语义桥）、aster-cloud `src/lib/byok-envelope.ts`（BYOK envelope，Phase 2）、aster-api `LlmRuntimeOptionsResolver`（凭证解析）、aster-api#360（Anthropic 默认模型改配置）

---

## 0. TL;DR

用户在「AI 密钥（自带 Key）」里可以**为每条 key 绑定一个可选的 model**；不填就用平台为该 provider 配置的默认
model，行为与今天完全一致。保存 key 时 cloud 经 aster-api 调一次 provider 的 `GET /v1/models`：填了 model 就校验
存在，没填就把列表展示给用户选。调用时仍按现有的单一优先级队列挑出第一条可用 binding，**该 binding 的
provider 和 model 就是本次请求用的**；不引入第二套 model 优先级，不做运行时跨 model 降级。aster-api 只在 model
为空时补默认，不再静默替换用户指定的名字；用量记录与审计按真实 model 落库。

---

## 1. 现状与问题

现状（代码为准）：

- cloud `AiKeyBinding` 只有 provider / 加密 key / providerUrl / tokenQuota / expiresAt / priority，**没有 model**
  （`src/db/schema.ts` `aiKeyBindings`；`src/app/api/user/ai-keys/route.ts` 只接受这些字段）。
- 推理时 `resolveByokEnvelope` 把用户全部 active binding 按 `(priority, createdAt)` 跨 provider 混排，跳过不支持的
  provider（vertex）、已过期、本月超额，取第一条；envelope 只带 `{ provider, apiKey, baseUrl? }`。
- aster-api `PromptComposer` 用 `req.model() ?? config.model()`，请求中的 model 由服务端拼装（前端不传，HMAC 绑定
  body），实际恒为 `aster.llm.model`（默认 `gpt-4o-mini`）。选中 anthropic key 时 `VertxLlmClient` 发现 model 不以
  `claude` 开头就替换为 `aster.llm.anthropic-default-model`（默认 `claude-sonnet-5-5`）。
- cloud 用量记录写 `model: 'unknown'`（`src/app/api/llm/complete/route.ts`），dashboard 的 BYOK 用量无法按 model 追溯。
- `byok-healthcheck` cron 只验 key 是否有效，不查询可用模型。

问题：

1. 企业 key 常带模型限制（只开了 gpt-4o、公司只允许 haiku），用户没有入口表达，调用会被 provider 以 400 拒绝，且
   错误在推理时才暴露。
2. 自定义 providerUrl（OpenAI 兼容网关）上的模型别名无法使用：anthropic 分支会把非 `claude*` 名字静默换掉。
3. 审计与用量没有真实 model，BYOK 成本不可归因。

---

## 2. 方向与范围

方向：model 成为 binding 的一个**可选属性**，由选中的 binding 决定本次 provider 与 model；平台默认继续兜底。

范围内：

- cloud：`AiKeyBinding.model`（可空）；设置页可选下拉/输入；`_byok` envelope 增加 `model`；用量记录真实 model。
- aster-api：`ByokOverride.model`（可空）；`LlmRuntimeOptionsResolver` 透传；model 为空才补 provider 默认，不替换
  用户指定值；新增 `GET /api/v1/llm/models` 代理 provider 的模型列表（经 BYOK envelope，复用 allowlist + SSRF 边界）。
- 校验：保存与 healthcheck 时查询 `/v1/models`，结果存 binding（`availableModels`、`modelsCheckedAt`），仅用于校验
  与提示。

范围外（见 §8）：按 model 自动降级、按价格/上下文自动选型、vertex、平台 key 的多模型策略。

---

## 3. 数据与接口

### 3.1 cloud `AiKeyBinding` 新增列（migration 0055）

| 列 | 类型 | 语义 |
| --- | --- | --- |
| `model` | text，可空 | 用户指定的模型 id；空=平台该 provider 默认 |
| `availableModels` | jsonb，可空 | 最近一次 `/v1/models` 查询结果（仅 id 列表，上限 200 条） |
| `modelsCheckedAt` | timestamp，可空 | 上次查询时间；查询失败不清空旧列表，只写 `lastError` |

### 3.2 `_byok` envelope

```json
{ "provider": "openai", "apiKey": "…", "baseUrl": "https://…(可选)", "model": "gpt-4o(可选)" }
```

`model` 与现有字段一样在注入后签名（HMAC 覆盖最终 body），aster-api 验签后才读取；浏览器 body 里的 `_byok` 一律覆盖
（现有规则不变）。

### 3.3 aster-api

- `ByokOverride(provider, apiKey, baseUrl, model)`；`LlmRuntimeOptions` 增加 `model`（可空）。
- `PromptComposer`：`model = runtime.model ?? req.model ?? config.model`（BYOK 指定 > 服务端请求 > 平台默认）。
- `VertxLlmClient` anthropic 分支：仅当 model 为空时用 `anthropicDefaultModel`；非空原样发出。平台凭证（无 BYOK）
  路径保持现有替换逻辑（平台 key 只对应平台默认模型，避免 aster-api#360 的 400 回潮）。
- 新端点 `GET /api/v1/llm/models`：须带合法 `_byok` envelope（与 `/api/v1/llm/complete` 同一鉴权与签名），按
  provider 调 `GET {baseUrl}/v1/models`，返回 `{ provider, models: [id…], fetchedAt }`；上游失败返回 502
  `models_unavailable`。走 `LlmEndpointPolicy`（allowlist + SsrfGuard），不新增出站边界。

### 3.4 cloud 用户端点

- `POST /api/user/ai-keys`：新增可选 `model`（trim 后 1–128 字符，`^[A-Za-z0-9._:/-]+$`）。保存前调
  `GET /api/v1/llm/models`：
  - 查询成功且 `model` 非空但不在列表 → 400 `model_not_available`，列出可用项；
  - 查询成功 → 写入 `availableModels` / `modelsCheckedAt`；
  - 查询失败（上游 5xx/超时）→ **仍允许保存**（不把 provider 抖动变成用户不能存 key），`lastError` 记原因，UI 提示
    「未能校验模型，将在调用时按所填使用」。
- `GET /api/user/ai-keys`：返回 `model`、`availableModels`、`modelsCheckedAt`、以及平台该 provider 的默认 model
  （供 UI 显示「将使用默认 X」）。
- `PATCH`（reorder 已有）新增 `action: "setModel"`，允许改 model 而不重录 key。

### 3.5 设置页

provider 之后增加「模型（可选）」：有 `availableModels` 时为可搜索下拉 + 允许手输；没有时为文本框；占位文案
「默认：{platformDefault}」。列表项显示 `provider · model 或「默认」· 优先级`。

---

## 4. 调用优先级（不变的部分与变的部分）

不变：单一队列，`(priority, createdAt)` 跨 provider 混排；跳过不支持 provider / 过期 / 超额；选择时 fallback，
无运行时重试；BYOK 非法不回退平台 key。

变：队列元素从「key」变成「key + model」。用户要「先 gpt-4o，不行再 gpt-4o-mini」，就录两条 binding（允许同一
key 重复录入，`keyHint` 相同），顺序即优先级。这样「这次到底用了哪个 provider/model」对用户与审计都只有一个答案。

明确不做：同一 binding 内多 model 自动降级、按 provider 分组的二级优先级、按 model 可用性在调用时剔除 binding
（`availableModels` 只是提示，不参与选择——列表过期或网关别名不在列表里时不能误伤）。

---

## 5. 用量、审计与配额

- `recordAiUsage` 的 `model` 改为真实 model：BYOK 取 envelope 的 model，否则取 aster-api 响应头 `X-Aster-Llm-Model`
  （新增，aster-api 在 2xx 响应上回传实际发出的 model；流式响应放在首个 SSE 事件 `meta` 里）。
- 审计事件 `byok.key.created` / `byok.key.updated` 增加 `model`（不含 key 明文，规则同现有 keyHint）。
- 配额语义不变：BYOK 跳过平台月配额，`tokenQuota` 仍按用户总量；model 不改变计费口径（token 数由 provider 回传）。

---

## 6. 错误与降级

| 场景 | 行为 |
| --- | --- |
| 用户指定 model 不在列表 | 保存时 400 `model_not_available`；调用时若 provider 400 → 原样透传 `provider_rejected_model`，不回退默认 model（避免「以为用了 A 实际用了 B」） |
| `/v1/models` 上游失败 | 保存放行，提示未校验；healthcheck 下次再试 |
| 自定义网关不提供 `/v1/models` | 404/405 视为「不支持列表」，不记 `lastError`，UI 退化为文本框 |
| envelope 带 model 但 provider 不支持 | 与现有「不支持 provider」同路径：aster-api 抛错，不回退 |

---

## 7. 测试与发版

- cloud：`ai-keys` 路由单测（model 校验、`/models` 失败仍可保存、setModel）；`byok-envelope` 单测（envelope 含
  model、无 model 不注入字段）；`llm/complete` 用量记录真实 model；设置页组件测试；i18n 四语言键一致性。
- aster-api：`LlmRuntimeOptionsResolverTest`（model 透传）、`PromptComposerTest`（优先级 BYOK > req > config）、
  `VertxLlmClient` anthropic 分支（空补默认、非空原样）、`/api/v1/llm/models` 资源测试（allowlist / SSRF / 502）。
- 本地栈验收：录入 openai key 指定 `gpt-4o-mini`、anthropic key 不指定；分别置顶后调补全，核对 aster-api 日志与
  `aiUsageRecords.model`；录入不存在的 model 得 400。
- 发版顺序：aster-api 先（envelope 多字段向后兼容：旧 cloud 不带 model，api 按空处理），cloud 后（migration 0055
  先于镜像）。

---

## 8. 不做 / 后续

- 按价格、上下文长度、延迟自动选型；跨 model 运行时降级。
- vertex / 非 API-key 鉴权的 provider。
- 平台 key 的多模型策略（仍是单一 `aster.llm.model`）。
- 团队级共享 BYOK（binding 仍按用户）。

---

## 9. 写回与验收

（定稿与实施后回填：实施中的决定、栈验收记录。）
