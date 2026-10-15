# BYOK 模型选择与调用优先级 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户为每条 BYOK key 绑定一个可选 model，调用时由选中的 binding 决定 provider 与 model，保存时经 provider `/v1/models` 校验，用量与审计记录真实 model。

**Architecture:** aster-api 把 `model` 加进 `_byok` envelope → `ByokOverride` → `LlmRuntimeOptions`，由 `LlmModelPolicy` 统一算出「实际发出的 model」（BYOK 指定 > 请求 > 平台默认；Anthropic 兜底只对平台路径与未指定 model 的 BYOK 生效），并通过 `CompleteResponse.model` / SSE `meta` 帧把真实 model 回传 cloud；新增 `POST /api/v1/ai/models` 代理 provider 模型列表。aster-cloud 给 `AiKeyBinding` 加 `model` / `availableModels` / `modelsCheckedAt`，保存与 healthcheck 时调该端点校验，envelope 注入 `model`，用量记录改写真实 model，设置页加可选模型字段。

**Tech Stack:** aster-api（Quarkus 3 / Java 25 / Mutiny / Vert.x WebClient / JUnit 5 + AssertJ + Mockito）；aster-cloud（Next.js 16 / Drizzle / vitest / next-intl）。

**Spec:** `/Users/rpang/IdeaProjects/aster-api/docs/adr/0048-byok-model-selection.md`（ACCEPTED；本计划同时修正其 §3.3 路径为 `/api/v1/ai/*`、models 端点为签名 POST、PATCH 以 `update` 动作携带 `model`、审计动作沿用 `ai-key.*`）。

## Global Constraints

- 两仓各开一条分支：aster-api `feat/adr0048-byok-model`，aster-cloud `feat/adr0048-byok-model`（cloud 主 checkout 在用户分支上，**必须**用 `git worktree add /Users/rpang/IdeaProjects/aster-cloud-adr0048 -b feat/adr0048-byok-model origin/main`）。
- 提交 trailer 固定：`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`；提交信息、注释、文档不得出现其它模型标识。
- 代码注释用简体中文说明意图，禁止「修改说明」式注释；函数缩进 ≤ 3 层。
- aster-api：`./gradlew --offline test --tests '<Class>'` 跑单测（无需 DB；本计划不新增 `@QuarkusTest`）；全量门禁用 memory 文件里的 sdd-pg/sdd-redis 配方。Java 25、`Map.of`/record 风格与现有文件一致。
- aster-cloud：Node 24（`export PATH=/opt/homebrew/opt/node@24/bin:$PATH`），`pnpm exec vitest run <file>`；`pnpm exec tsc --noEmit -p tsconfig.json`；`pnpm exec eslint <files>`；`pnpm run check:message-keys`。新 i18n key 四种语言（en/zh/de/hi）在 `src/i18n/demo-supplement.ts` 同步新增。
- envelope `_byok.model` 必须在注入**之后**签名（现有 `injectByokEnvelope` → `signInternalCallerHeaders` 顺序不变）。
- BYOK 非法永不回退平台 key（现有铁律）；`model` 不进日志、审计 vault 可记 model 但绝不记 apiKey。
- 平台凭证路径保留现有 Anthropic 模型替换（用户决策）；仅 BYOK 且指定了 model 时原样发出。
- 发版顺序：aster-api 先（旧 cloud 不带 `model` 时 api 按空处理），cloud 后（migration 0055 先于镜像）。

## Review Focus

1. 旧版 cloud（不带 `_byok.model`）打到新版 api：行为必须与今天逐字节一致（`ByokOverride.model=null` → 平台默认 / Anthropic 兜底）。→ Task 1 `resolverKeepsNullModel`、Task 2 `byokWithoutModelStillFallsBack`。
2. 用户指定了非 `claude*` 的 model 给 anthropic BYOK（自定义网关别名）：必须原样发出，不得被替换。→ Task 2 `byokExplicitModelIsNotReplaced`。
3. `_byok.model` 含空白/超长/非法字符：api 侧规范化为 null 或拒绝，不得把垃圾塞进 provider 请求。→ Task 1 `parserTrimsAndRejectsBadModel`。
4. `/v1/models` 上游失败（5xx/超时/网关不支持 404）：保存 key 仍成功，`availableModels` 不被清空，UI 提示未校验。→ Task 8 `postSavesWhenModelsUnavailable`、Task 7 `unsupportedListIsNotAnError`。
5. 真实 model 写入用量：SSE `meta` 帧在 `upstream_reached` 之前到达，计费 watcher 捕获到 model；占位记录带真实 model，回填不覆盖为 `unknown`。→ Task 9 `watcherCapturesModelBeforeBillable`、`placeholderUsesMetaModel`。

---

## 文件结构

**aster-api**
- Modify `src/main/java/io/aster/llm/model/ByokOverride.java` — 加 `model` 组件（4 参 + 保留 2/3 参构造）。
- Modify `src/main/java/io/aster/llm/model/LlmRuntimeOptions.java` — 加 `model` 组件（保留 4/6 参构造）。
- Modify `src/main/java/io/aster/llm/api/ByokEnvelopeParser.java` — 解析/规范化 `model`。
- Modify `src/main/java/io/aster/llm/tenant/LlmRuntimeOptionsResolver.java` — 透传 model。
- Create `src/main/java/io/aster/llm/model/LlmModelPolicy.java` — 「实际发出 model」唯一算法。
- Modify `src/main/java/io/aster/llm/client/VertxLlmClient.java` — Anthropic body 用 policy；新增 `listModels`。
- Modify `src/main/java/io/aster/llm/client/LlmClient.java` — `listModels` 抽象方法。
- Modify `src/main/java/io/aster/llm/client/LlmCallFailedException.java` — 带 `statusCode`。
- Modify `src/main/java/io/aster/llm/api/dto/GeneratePolicyEvent.java` — `meta` 工厂。
- Modify `src/main/java/io/aster/llm/service/LlmProxyService.java` — model 下推、meta 帧、真实 model 记账、`provider_rejected_model`。
- Create `src/main/java/io/aster/llm/api/dto/ModelsResponse.java`；Modify `src/main/java/io/aster/llm/api/AiAssistantResource.java` — `POST /api/v1/ai/models`。
- Tests：`LlmRuntimeOptionsResolverTest`、`ByokEnvelopeParserTest`、新 `LlmModelPolicyTest`、`VertxLlmClientAnthropicBodyTest`、新 `LlmProxyServiceModelTest`、新 `VertxLlmClientListModelsTest`。

**aster-cloud**
- Modify `src/db/schema.ts`；Create `drizzle/0055_ai_key_binding_model.sql`；Modify `drizzle/meta/_journal.json`。
- Modify `src/lib/ai-key-vault.ts` — `model` 入库/读取、`updateBYOKKeyMeta.model`、`updateBYOKAvailableModels`。
- Modify `src/lib/byok-envelope.ts` — `ByokEnvelope.model`、注入 `_byok.model`。
- Create `src/lib/byok-models.ts` — 经 aster-api 查询模型列表。
- Modify `src/app/api/user/ai-keys/route.ts`、`src/app/[locale]/(dashboard)/settings/ai-keys/page.tsx`。
- Modify `src/lib/sse-frame.ts`、`src/lib/llm-sse-proxy.ts`、`src/app/api/llm/complete/route.ts` — 真实 model 记账。
- Modify `src/lib/ai-byok-healthcheck.ts` — 刷新 `availableModels`。
- Modify `src/app/[locale]/(dashboard)/settings/ai-keys/ai-keys-content.tsx`、`src/i18n/demo-supplement.ts`。
- Tests：`byok-envelope.test.ts`、`ai-keys-route.test.ts`、新 `byok-models.test.ts`、`sse-frame-billable-watcher.test.ts`、`complete-quota-gate.test.ts`、新 `ai-byok-healthcheck.test.ts`、新 `ai-keys-content.test.tsx`、新 `ai-keys-namespace-parity.test.ts`。

---

## aster-api

### Task 1: envelope / override / runtime options 携带 model

**Files:**
- Modify: `src/main/java/io/aster/llm/model/ByokOverride.java`
- Modify: `src/main/java/io/aster/llm/model/LlmRuntimeOptions.java`
- Modify: `src/main/java/io/aster/llm/api/ByokEnvelopeParser.java:62-67`
- Modify: `src/main/java/io/aster/llm/tenant/LlmRuntimeOptionsResolver.java:53-91`
- Test: `src/test/java/io/aster/llm/tenant/LlmRuntimeOptionsResolverTest.java`, `src/test/java/io/aster/llm/api/ByokEnvelopeParserTest.java`

**Interfaces:**
- Produces: `ByokOverride(String provider, String apiKey, String baseUrl, String model)`，`ByokOverride.hasModel()`；`LlmRuntimeOptions(apiKey, provider, baseUrl, source, wireFormat, customEndpoint, model)` + `model()`；`ByokEnvelopeParser.normalizeModel(String)`（package-private static）。

- [ ] **Step 1: 失败测试（resolver 透传 model，缺省为 null）**

在 `LlmRuntimeOptionsResolverTest` 末尾（`FakeEndpointPolicy` 之前）加：

```java
    @Test
    @DisplayName("BYOK 指定 model → 透传到 LlmRuntimeOptions.model")
    void resolverPassesByokModel() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1",
            new ByokOverride("openai", "sk-user", null, "gpt-4o"));
        assertThat(opts.model()).isEqualTo("gpt-4o");
        assertThat(opts.usedByok()).isTrue();
    }

    @Test
    @DisplayName("★旧 cloud 不带 model → model=null，平台/BYOK 行为与今天一致")
    void resolverKeepsNullModel() {
        assertThat(resolver.resolve("tenant-1", new ByokOverride("openai", "sk-user")).model()).isNull();
        assertThat(resolver.resolve("tenant-1", null).model()).isNull();
    }

    @Test
    @DisplayName("BYOK 自定义端点 + 指定 model → 两者同时保留")
    void customEndpointKeepsModel() {
        LlmRuntimeOptions opts = resolver.resolve("tenant-1",
            new ByokOverride("openai", "sk-user", "https://llm-gateway.example.com/v1", "gw-alias-1"));
        assertThat(opts.customEndpoint()).isTrue();
        assertThat(opts.model()).isEqualTo("gw-alias-1");
    }
```

在 `ByokEnvelopeParserTest` 加（沿用该文件的 `ctx(verified, body)` 工厂）：

```java
    @Test
    @DisplayName("_byok.model 解析并 trim；空白视为未指定")
    void parsesModel() {
        assertThat(parser.parse(ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\" gpt-4o \"}}")).model())
            .isEqualTo("gpt-4o");
        assertThat(parser.parse(ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\"   \"}}")).model())
            .isNull();
        assertThat(parser.parse(ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\"}}")).model())
            .isNull();
        // 长度边界：128 可用，129 拒绝
        assertThat(parser.parse(ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\"" + "a".repeat(128) + "\"}}")).model())
            .hasSize(128);
    }

    @Test
    @DisplayName("★_byok.model 非法（超长/非法字符）→ IllegalArgumentException（fail-closed，不回退平台）")
    void parserTrimsAndRejectsBadModel() {
        String tooLong = "a".repeat(129);
        assertThatThrownBy(() -> parser.parse(ctx(true,
            "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\"" + tooLong + "\"}}")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model");
        assertThatThrownBy(() -> parser.parse(ctx(true,
            "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\",\"model\":\"gpt 4o\\n\"}}")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model");
    }
```

（若该测试类没有 `parser` 字段，按文件现有写法 `new ByokEnvelopeParser()`；`assertThatThrownBy` 需 `import static org.assertj.core.api.Assertions.assertThatThrownBy;`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew --offline test --tests 'io.aster.llm.tenant.LlmRuntimeOptionsResolverTest' --tests 'io.aster.llm.api.ByokEnvelopeParserTest'`
Expected: 编译失败（`ByokOverride` 无 4 参构造 / `model()` 不存在）。

- [ ] **Step 3: 实现**

`ByokOverride.java` 整体替换为：

```java
package io.aster.llm.model;

/**
 * 一次请求携带的 BYOK 覆盖凭证（issue: BYOK 真接入推理，Phase 2；ADR 0048 增加 model）。
 *
 * <p>由 aster-cloud 在【已 HMAC 验签】的内部请求 body 顶层以 {@code _byok} envelope 传入，
 * 经 {@code AiAssistantResource} 从原始 body 解析、剥离后构造，<b>不进业务 DTO、不进 PromptComposer、
 * 不进审计 vault、不打日志</b>。
 *
 * <p>安全：携带 provider + 用户解密后的 apiKey + 可选 baseUrl + 可选 model。baseUrl 来自 cloud 已验签
 * envelope，但仍是用户配置派生值，aster-api 每次使用前必须重新经过管理员 allowlist + SSRF guard。
 * model 为空表示「用平台该 provider 的默认模型」。{@link #toString()} 脱敏。
 */
public record ByokOverride(String provider, String apiKey, String baseUrl, String model) {

    public ByokOverride(String provider, String apiKey) {
        this(provider, apiKey, null, null);
    }

    public ByokOverride(String provider, String apiKey, String baseUrl) {
        this(provider, apiKey, baseUrl, null);
    }

    public boolean isValid() {
        return provider != null && !provider.isBlank()
            && apiKey != null && !apiKey.isBlank();
    }

    public boolean hasCustomBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }

    @Override
    public String toString() {
        return "ByokOverride{provider=" + provider
            + ", baseUrl=" + (hasCustomBaseUrl() ? baseUrl : "<default>")
            + ", model=" + (hasModel() ? model : "<default>")
            + ", apiKey=***}";
    }
}
```

`LlmRuntimeOptions.java`：record 头与构造改为

```java
public record LlmRuntimeOptions(
    String apiKey,
    String provider,
    String baseUrl,
    Source source,
    WireFormat wireFormat,
    boolean customEndpoint,
    /** BYOK 指定的模型；null = 平台该 provider 默认（含 Anthropic 兜底规则，见 LlmModelPolicy）。 */
    String model
) {

    public LlmRuntimeOptions(String apiKey, String provider, String baseUrl, Source source) {
        this(apiKey, provider, baseUrl, source, inferWireFormat(provider, baseUrl), false, null);
    }

    public LlmRuntimeOptions(String apiKey, String provider, String baseUrl, Source source,
                             WireFormat wireFormat, boolean customEndpoint) {
        this(apiKey, provider, baseUrl, source, wireFormat, customEndpoint, null);
    }

    public boolean hasModel() {
        return model != null && !model.isBlank();
    }
```

`toString()` 加 `+ ", model=" + (hasModel() ? model : "<default>")`。

`ByokEnvelopeParser.java` 第 62–67 行改为：

```java
        // present：已验签 body 明确带 BYOK 意图，无效则 fail-closed（不回退平台）
        ByokOverride override = new ByokOverride(env.getString("provider"), env.getString("apiKey"),
            env.getString("baseUrl"), normalizeModel(env.getString("model")));
        if (!override.isValid()) {
            throw new IllegalArgumentException("_byok envelope 字段无效（provider/apiKey 缺失）");
        }
        return override;
    }

    /** 允许的模型 id 形状（与 cloud 保存时校验一致）：1–128 字符，字母数字与 . _ : / -。 */
    private static final java.util.regex.Pattern MODEL_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    /** 空白 → null（未指定）；非法形状 → 抛错，不把垃圾塞进 provider 请求。 */
    static String normalizeModel(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!MODEL_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("_byok.model 非法（长度 1–128，仅 A-Z a-z 0-9 . _ : / -）");
        }
        return trimmed;
    }
```

`LlmRuntimeOptionsResolver.resolve`：两处 `new LlmRuntimeOptions(byok.apiKey(), provider, ...)` 改为 7 参，末尾加 `byok.hasModel() ? byok.model() : null`：

```java
            return new LlmRuntimeOptions(byok.apiKey(), provider, officialBaseUrl,
                LlmRuntimeOptions.Source.BYOK,
                LlmRuntimeOptions.inferWireFormat(provider, officialBaseUrl),
                false,
                byok.hasModel() ? byok.model() : null);
```
```java
        return new LlmRuntimeOptions(byok.apiKey(), provider, baseUrl,
            LlmRuntimeOptions.Source.BYOK, wireFormat, true,
            byok.hasModel() ? byok.model() : null);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew --offline test --tests 'io.aster.llm.tenant.LlmRuntimeOptionsResolverTest' --tests 'io.aster.llm.api.ByokEnvelopeParserTest'`
Expected: PASS（含既有用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/llm/model/ByokOverride.java src/main/java/io/aster/llm/model/LlmRuntimeOptions.java src/main/java/io/aster/llm/api/ByokEnvelopeParser.java src/main/java/io/aster/llm/tenant/LlmRuntimeOptionsResolver.java src/test/java/io/aster/llm/tenant/LlmRuntimeOptionsResolverTest.java src/test/java/io/aster/llm/api/ByokEnvelopeParserTest.java
git commit -m "feat(byok): _byok envelope 携带可选 model 并透传到 LlmRuntimeOptions（ADR 0048 §3.2/§3.3）" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 2: LlmModelPolicy —「实际发出的 model」唯一算法 + Anthropic 兜底只对平台/未指定生效

**Files:**
- Create: `src/main/java/io/aster/llm/model/LlmModelPolicy.java`
- Modify: `src/main/java/io/aster/llm/client/VertxLlmClient.java:289-293,331-337`
- Modify: `src/main/java/io/aster/llm/client/LlmCallFailedException.java`
- Test: `src/test/java/io/aster/llm/model/LlmModelPolicyTest.java`（新）, `src/test/java/io/aster/llm/client/VertxLlmClientAnthropicBodyTest.java`

**Interfaces:**
- Produces: `LlmModelPolicy.wireModel(String requestModel, LlmRuntimeOptions options, LlmConfig config)` → 非空 String；`VertxLlmClient.buildAnthropicBody(LlmRequest, LlmRuntimeOptions)`（保留 1 参重载 = options null）；`LlmCallFailedException(String message, boolean reachedProvider, Throwable cause, int statusCode)` + `statusCode()`（无状态时 0）。

- [ ] **Step 1: 失败测试**

新建 `src/test/java/io/aster/llm/model/LlmModelPolicyTest.java`：

```java
package io.aster.llm.model;

import io.aster.llm.config.LlmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/** LlmModelPolicy：BYOK 指定 > 请求 model > 平台默认；Anthropic 兜底只在非 BYOK 指定时生效。 */
class LlmModelPolicyTest {

    private static LlmConfig config() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "model" -> "gpt-4o-mini";
            case "anthropicDefaultModel" -> "claude-default";
            default -> null;
        };
        return (LlmConfig) Proxy.newProxyInstance(LlmConfig.class.getClassLoader(),
            new Class<?>[]{LlmConfig.class}, h);
    }

    private static LlmRuntimeOptions byok(String provider, String model) {
        return new LlmRuntimeOptions("k", provider, "https://x", LlmRuntimeOptions.Source.BYOK,
            LlmRuntimeOptions.inferWireFormat(provider, "https://x"), false, model);
    }

    private static LlmRuntimeOptions platform(String provider) {
        return new LlmRuntimeOptions("k", provider, "https://x", LlmRuntimeOptions.Source.PLATFORM);
    }

    @Test
    @DisplayName("BYOK 指定 model → 原样（openai）")
    void byokModelWins() {
        assertThat(LlmModelPolicy.wireModel("gpt-4o-mini", byok("openai", "gpt-4o"), config())).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("★BYOK anthropic 指定非 claude 名（网关别名）→ 原样发出，不替换")
    void byokExplicitModelIsNotReplaced() {
        assertThat(LlmModelPolicy.wireModel("gpt-4o-mini", byok("anthropic", "gw-sonnet-alias"), config()))
            .isEqualTo("gw-sonnet-alias");
    }

    @Test
    @DisplayName("★BYOK anthropic 未指定 model + 请求为 OpenAI 名 → 仍兜底 Anthropic 默认（与今天一致）")
    void byokWithoutModelStillFallsBack() {
        assertThat(LlmModelPolicy.wireModel("gpt-4o-mini", byok("anthropic", null), config())).isEqualTo("claude-default");
    }

    @Test
    @DisplayName("平台 anthropic：非 claude → 兜底；claude* → 保留")
    void platformAnthropicRule() {
        assertThat(LlmModelPolicy.wireModel("gpt-4o-mini", platform("anthropic"), config())).isEqualTo("claude-default");
        assertThat(LlmModelPolicy.wireModel("claude-3-7", platform("anthropic"), config())).isEqualTo("claude-3-7");
        assertThat(LlmModelPolicy.wireModel(null, platform("anthropic"), config())).isEqualTo("claude-default");
    }

    @Test
    @DisplayName("非 anthropic：请求 model，缺省平台 config.model")
    void openaiRule() {
        assertThat(LlmModelPolicy.wireModel("gpt-4.1", platform("openai"), config())).isEqualTo("gpt-4.1");
        assertThat(LlmModelPolicy.wireModel(null, platform("openai"), config())).isEqualTo("gpt-4o-mini");
        assertThat(LlmModelPolicy.wireModel(null, null, config())).isEqualTo("gpt-4o-mini");
    }
}
```

在 `VertxLlmClientAnthropicBodyTest` 加：

```java
    @Test
    @DisplayName("★BYOK 指定 model → Anthropic body 原样发出（不兜底）")
    void byokModelKeptInAnthropicBody() {
        LlmRequest req = new LlmRequest("gpt-4o-mini", List.of(new LlmRequest.Message("user", "hi")), 0.0, 500, false);
        LlmRuntimeOptions opts = new LlmRuntimeOptions("k", "anthropic", "https://api.anthropic.com",
            LlmRuntimeOptions.Source.BYOK, LlmRuntimeOptions.WireFormat.ANTHROPIC_NATIVE, false, "my-alias");
        assertThat(client.buildAnthropicBody(req, opts).getString("model")).isEqualTo("my-alias");
    }
```

（需 `import io.aster.llm.model.LlmRuntimeOptions;`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew --offline test --tests 'io.aster.llm.model.LlmModelPolicyTest' --tests 'io.aster.llm.client.VertxLlmClientAnthropicBodyTest'`
Expected: 编译失败（`LlmModelPolicy` 不存在、`buildAnthropicBody` 无 2 参重载）。

- [ ] **Step 3: 实现**

新建 `src/main/java/io/aster/llm/model/LlmModelPolicy.java`：

```java
package io.aster.llm.model;

import io.aster.llm.config.LlmConfig;

import java.util.Locale;

/**
 * 「本次实际发给 provider 的 model」的唯一算法（ADR 0048 §3.3）。
 *
 * <p>优先级：BYOK 指定的 model（原样，不做任何替换）> 请求里的 model > 平台 {@code aster.llm.model}。
 * Anthropic 兜底（非 claude 系名字换成 {@code anthropic-default-model}）只对「平台凭证」与「BYOK 未指定
 * model」两种情况生效——用户在自定义网关上指定的别名必须原样发出（用户决策）。
 *
 * <p>客户端拼 body 与 service 记账/回传 cloud 都必须经由本类，保证日志、provider 请求、用量三处一致。
 */
public final class LlmModelPolicy {

    private LlmModelPolicy() {
    }

    public static String wireModel(String requestModel, LlmRuntimeOptions options, LlmConfig config) {
        if (options != null && options.hasModel()) {
            return options.model();
        }
        String model = requestModel != null && !requestModel.isBlank() ? requestModel : config.model();
        boolean anthropic = options != null && "anthropic".equals(options.provider());
        if (anthropic && !model.toLowerCase(Locale.ROOT).startsWith("claude")) {
            return config.anthropicDefaultModel();
        }
        return model;
    }
}
```

`VertxLlmClient.java`：

```java
    JsonObject buildRequestBody(LlmRequest request, LlmRuntimeOptions options) {
        String provider = options.provider();
        if ("anthropic".equals(provider)) {
            return buildAnthropicBody(request, options);
        }
```
```java
    // 既有单测以 1 参调用：等价于平台凭证（无 BYOK 指定 model）。
    JsonObject buildAnthropicBody(LlmRequest request) {
        return buildAnthropicBody(request, null);
    }

    // package-private for unit testing（Anthropic Messages API 结构是纯逻辑，值得锁定）
    JsonObject buildAnthropicBody(LlmRequest request, LlmRuntimeOptions options) {
        // 模型选择统一走 LlmModelPolicy：平台/未指定 → 非 claude 系兜底；BYOK 指定 → 原样。
        LlmRuntimeOptions effective = options != null ? options
            : new LlmRuntimeOptions(null, "anthropic", null, LlmRuntimeOptions.Source.PLATFORM);
        String model = LlmModelPolicy.wireModel(request.model(), effective, config);
        StringBuilder system = new StringBuilder();
```

（删掉原 334–337 行的本地兜底逻辑；其余不变。`import io.aster.llm.model.LlmModelPolicy;`。）

OpenAI 分支第 308 行 `.put("model", request.model())` 改为 `.put("model", LlmModelPolicy.wireModel(request.model(), options, config))`。

`LlmCallFailedException.java`：加字段与构造：

```java
    private final int statusCode;

    public LlmCallFailedException(String message, boolean reachedProvider, Throwable cause) {
        this(message, reachedProvider, cause, 0);
    }

    /** statusCode：provider 的 HTTP 状态（0 = 无响应/连接失败）；供 service 区分「模型被拒」等情形。 */
    public LlmCallFailedException(String message, boolean reachedProvider, Throwable cause, int statusCode) {
        super(message, cause);
        this.reachedProvider = reachedProvider;
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
```

`VertxLlmClient.chat` 第 270–271 行改为 `emitter.fail(new LlmCallFailedException("LLM API 错误 (" + response.statusCode() + ")", true, null, response.statusCode()));`；流式第 157 行的 `LlmStreamEvent.error("LLM API 错误 (" + statusCode + ")")` 保持。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew --offline test --tests 'io.aster.llm.model.LlmModelPolicyTest' --tests 'io.aster.llm.client.*'`
Expected: PASS（既有 `nonClaudeModelFallsBackToAnthropicDefault` / `nullModelFallsBackToAnthropicDefault` 仍通过）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/llm/model/LlmModelPolicy.java src/main/java/io/aster/llm/client/VertxLlmClient.java src/main/java/io/aster/llm/client/LlmCallFailedException.java src/test/java/io/aster/llm/model/LlmModelPolicyTest.java src/test/java/io/aster/llm/client/VertxLlmClientAnthropicBodyTest.java
git commit -m "feat(llm): LlmModelPolicy 统一实际发出的 model；BYOK 指定 model 原样发出，兜底只对平台/未指定生效" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 3: LlmProxyService 下推 model、meta 帧、真实 model 记账、provider_rejected_model

**Files:**
- Modify: `src/main/java/io/aster/llm/api/dto/GeneratePolicyEvent.java`
- Modify: `src/main/java/io/aster/llm/service/LlmProxyService.java:70-96,202-236,245-273,278-311`
- Test: `src/test/java/io/aster/llm/service/LlmProxyServiceModelTest.java`（新）

**Interfaces:**
- Consumes: `LlmModelPolicy.wireModel`、`LlmRuntimeOptions.model()`、`LlmCallFailedException.statusCode()`。
- Produces: SSE 首帧 `{"type":"meta","data":"<model>"}`（`GeneratePolicyEvent.meta(String)`）；`CompleteResponse.model` = 实际发出 model；`complete` 在 BYOK 指定 model 且 provider 返回 400 时抛 400 `provider_rejected_model`；流式对应 error 帧文本 `provider_rejected_model`。

- [ ] **Step 1: 失败测试**

新建 `src/test/java/io/aster/llm/service/LlmProxyServiceModelTest.java`（沿用 `LlmProxyServiceCompleteBillingTest` 的装配方式）：

```java
package io.aster.llm.service;

import io.aster.llm.api.dto.CompleteRequest;
import io.aster.llm.api.dto.CompleteResponse;
import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.client.LlmCallFailedException;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.LlmChatResult;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.model.LlmStreamEvent;
import io.aster.llm.model.LlmUsage;
import io.aster.llm.prompt.PromptComposer;
import io.aster.llm.prompt.PromptContext;
import io.aster.llm.tenant.LlmRuntimeOptionsResolver;
import io.aster.llm.usage.AiUsageReporter;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR 0048：service 层把 BYOK 指定的 model 下推、按实际 model 记账、首帧 meta、模型被拒不伪装成功。 */
class LlmProxyServiceModelTest {

    private LlmProxyService service;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        service = new LlmProxyService();
        client = mock(LlmClient.class);
        service.llmClient = client;
        PromptComposer composer = mock(PromptComposer.class);
        when(composer.buildCompleteContext(anyString(), any())).thenAnswer(inv ->
            new PromptContext().systemPrompt("s").userPrompt("u").model("gpt-4o-mini").temperature(0.1).maxTokens(10));
        when(composer.buildSuggestContext(anyString(), any())).thenAnswer(inv ->
            new PromptContext().systemPrompt("s").userPrompt("u").model("gpt-4o-mini").temperature(0.1).maxTokens(10));
        service.promptComposer = composer;
        service.config = mock(LlmConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(service.config.model()).thenReturn("gpt-4o-mini");
        when(service.config.anthropicDefaultModel()).thenReturn("claude-default");
        service.validator = mock(PolicyCompileValidator.class);
        service.usageReporter = mock(AiUsageReporter.class);
        service.optionsResolver = mock(LlmRuntimeOptionsResolver.class);
    }

    private void options(String provider, String model) {
        when(service.optionsResolver.resolve(anyString(), any())).thenReturn(
            new LlmRuntimeOptions("k", provider, "https://x", LlmRuntimeOptions.Source.BYOK,
                LlmRuntimeOptions.inferWireFormat(provider, "https://x"), false, model));
    }

    @Test
    @DisplayName("complete：BYOK 指定 model → 请求 model 与记账 model 都是它，CompleteResponse.model 回传")
    void completeUsesByokModel() {
        options("openai", "gpt-4o");
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().item(new LlmChatResult("x", LlmUsage.ZERO)));
        CompleteResponse res = service.complete("t", new CompleteRequest("Rule r", null, null), null, "r")
            .await().atMost(Duration.ofSeconds(5));
        assertThat(res.model()).isEqualTo("gpt-4o");
        verify(client).chat(org.mockito.ArgumentMatchers.argThat(req -> "gpt-4o".equals(req.model())), any());
        verify(service.usageReporter).reportUsage(eq("t"), eq("complete"), eq("gpt-4o"), any(), eq(true), eq("r"));
    }

    @Test
    @DisplayName("complete：BYOK anthropic 未指定 model → 记账/回传为 Anthropic 兜底模型（实际发出的）")
    void completeReportsEffectiveAnthropicModel() {
        options("anthropic", null);
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().item(new LlmChatResult("x", LlmUsage.ZERO)));
        CompleteResponse res = service.complete("t", new CompleteRequest("Rule r", null, null), null, "r")
            .await().atMost(Duration.ofSeconds(5));
        assertThat(res.model()).isEqualTo("claude-default");
    }

    @Test
    @DisplayName("★complete：BYOK 指定 model 且 provider 400 → 400 provider_rejected_model，不伪装为空补全")
    void completeRejectedModelIs400() {
        options("openai", "nope-1");
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM API 错误 (400)", true, null, 400)));
        assertThatThrownBy(() -> service.complete("t", new CompleteRequest("Rule r", null, null), null, "r")
            .await().atMost(Duration.ofSeconds(5)))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("complete：未指定 model 时 provider 400 → 保持今天的降级（空补全、upstreamReached=true）")
    void completeWithoutModelKeepsRecovery() {
        options("openai", null);
        when(client.chat(any(), any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM API 错误 (400)", true, null, 400)));
        CompleteResponse res = service.complete("t", new CompleteRequest("Rule r", null, null), null, "r")
            .await().atMost(Duration.ofSeconds(5));
        assertThat(res.completion()).isEmpty();
        assertThat(res.upstreamReached()).isTrue();
    }

    @Test
    @DisplayName("suggest 流：首帧为 meta（实际 model），其后才是 upstream_reached/delta")
    void streamStartsWithMeta() {
        options("openai", "gpt-4o");
        when(client.streamChat(any(), any())).thenReturn(Multi.createFrom().items(
            LlmStreamEvent.reached(), LlmStreamEvent.delta("a"), LlmStreamEvent.done()));
        List<String> frames = service.streamSuggest("t", new SuggestRequest("Rule r", null, null), null, "r")
            .collect().asList().await().atMost(Duration.ofSeconds(5));
        assertThat(frames.get(0)).contains("\"type\":\"meta\"").contains("gpt-4o");
        assertThat(frames.get(1)).contains("upstream_reached");
    }
}
```

（`SuggestRequest` 构造参数以 `src/main/java/io/aster/llm/api/dto/SuggestRequest.java` 为准；若字段数不同按其 record 组件补 null。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew --offline test --tests 'io.aster.llm.service.LlmProxyServiceModelTest'`
Expected: FAIL（无 meta 帧、model 仍为请求值、400 被吞成空补全）。

- [ ] **Step 3: 实现**

`GeneratePolicyEvent.java` 加：

```java
    /** 首帧：本次实际发往 provider 的 model（ADR 0048 §5），cloud 据此记账。 */
    public static GeneratePolicyEvent meta(String model) {
        return new GeneratePolicyEvent("meta", model, null, null);
    }
```

`LlmProxyService.java`：

1. 新增私有方法（放在 `isForwardedToCloud` 之前）：

```java
    /** 把 BYOK 指定的 model 下推到提示上下文，并返回本次实际发出的 model（与 client 拼 body 同一算法）。 */
    private String applyModel(PromptContext ctx, LlmRuntimeOptions options) {
        String model = LlmModelPolicy.wireModel(ctx.toLlmRequest().model(), options, config);
        ctx.model(model);
        return model;
    }

    /** BYOK 指定了 model 且 provider 以 400 拒绝 → 不回退默认 model、不伪装成功。 */
    private static boolean isRejectedModel(LlmRuntimeOptions options, Throwable t) {
        return options.hasModel() && t instanceof LlmCallFailedException e && e.statusCode() == 400;
    }
```

（`import io.aster.llm.model.LlmModelPolicy; import io.aster.llm.client.LlmCallFailedException;`。）

2. `streamGenerate`（83–94 行）：`PromptContext ctx = promptComposer.buildGenerateContext(tenantId, req);` 后加 `String model = applyModel(ctx, options);`；`UsageReportCtx rc = new UsageReportCtx(tenantId, "generate", model, ...)`（替换原 `req.model() != null ? req.model() : config.model()`）；`emitter.emit(toJson(GeneratePolicyEvent.meta(model)));` 放在 `executeGenerateAttempt(...)` 之前。

3. `streamSuggest`（214–235 行）：

```java
        PromptContext ctx = promptComposer.buildSuggestContext(tenantId, req);
        String model = applyModel(ctx, options);
        boolean usedByok = options.usedByok();
        ...
        return Multi.createFrom().item(toJson(GeneratePolicyEvent.meta(model)))
            .onItem().transformToMultiAndConcatenate(meta -> Multi.createFrom().item(meta))
            .onCompletion().continueWith(() -> null) // 占位，见下
```

——用更直接的写法：把原返回值记为 `Multi<String> body = llmClient.streamChat(...)...map(LlmProxyService::toCloudFrame);`，然后 `return Multi.createBy().concatenating().streams(Multi.createFrom().item(toJson(GeneratePolicyEvent.meta(model))), body);`。`reportUsage` 的 model 参数改用 `model`。`streamAssistant` 同样处理（258 行改为 `String model = applyModel(ctx, options);`，返回前拼 meta 帧）。流式的「模型被拒」不在 service 里猜文案：由 client 在拿到 provider 状态码的地方判定（见本 Task 第 5 小节），service 只透传 error 帧。

4a. `VertxLlmClient.streamChat` 非 200 分支（第 157 行附近）改为：

```java
                    // BYOK 指定了 model 且 provider 400：按结构化错误码上报，service/cloud 不必解析文案（ADR 0048 §6）
                    boolean rejectedModel = statusCode == 400 && options.hasModel();
                    emitter.emit(LlmStreamEvent.error(rejectedModel
                        ? "provider_rejected_model"
                        : "LLM API 错误 (" + statusCode + ")"));
```

对应测试新建 `src/test/java/io/aster/llm/client/VertxLlmClientRejectedModelTest.java`（复用 `VertxLlmClientChatReachedTest` 的 `listen(...)` 原始 socket 桩与 `LlmClientTestSupport.newClient`，对 `streamChat` 收集事件）：

```java
    @Test
    @DisplayName("★流式：BYOK 指定 model + provider 400 → error 帧为 provider_rejected_model")
    void streamRejectedModel() throws Exception {
        int port = listen(sock -> sock.write(
            "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"));
        LlmRuntimeOptions opts = new LlmRuntimeOptions("k", "openai", "http://127.0.0.1:" + port,
            LlmRuntimeOptions.Source.BYOK, LlmRuntimeOptions.WireFormat.OPENAI_NATIVE, false, "nope-1");
        List<LlmStreamEvent> events = client().streamChat(request(), opts).collect().asList().await().atMost(Duration.ofSeconds(10));
        assertThat(events).anySatisfy(ev -> {
            assertThat(ev.type()).isEqualTo(LlmStreamEvent.Type.ERROR);
            assertThat(ev.error()).isEqualTo("provider_rejected_model");
        });
    }

    @Test
    @DisplayName("流式：未指定 model 的 400 → 保持原文案 LLM API 错误 (400)")
    void streamPlain400() throws Exception {
        int port = listen(sock -> sock.write(
            "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"));
        LlmRuntimeOptions opts = new LlmRuntimeOptions("k", "openai", "http://127.0.0.1:" + port, LlmRuntimeOptions.Source.BYOK);
        List<LlmStreamEvent> events = client().streamChat(request(), opts).collect().asList().await().atMost(Duration.ofSeconds(10));
        assertThat(events).anySatisfy(ev -> assertThat(ev.error()).isEqualTo("LLM API 错误 (400)"));
    }
```

（`request()` = `new LlmRequest("m", List.of(new LlmRequest.Message("user", "hi")), 0.2, 16, true)`；`client()` 与 `VertxLlmClientChatReachedTest.chatFailure` 中的组装方式相同。）

4b. `complete`（297–310 行）：

```java
        PromptContext ctx = promptComposer.buildCompleteContext(tenantId, req);
        String model = applyModel(ctx, options);
        boolean usedByok = options.usedByok();

        return llmClient.chat(ctx.toLlmRequest(), options)
            .map(result -> {
                usageReporter.reportUsage(tenantId, "complete", model, result.usage(), usedByok, requestId);
                return new CompleteResponse(result.content(), model, true);
            })
            .onFailure(t -> isRejectedModel(options, t)).transform(t ->
                new jakarta.ws.rs.WebApplicationException(
                    jakarta.ws.rs.core.Response.status(400)
                        .entity(java.util.Map.of("error", "provider_rejected_model",
                            "message", "provider 拒绝了所选模型: " + model))
                        .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                        .build()))
            .onFailure(t -> !(t instanceof jakarta.ws.rs.WebApplicationException)).recoverWithItem(throwable -> {
                LOG.errorf(throwable, "代码补全失败");
                return new CompleteResponse("", model, reachedProvider(throwable));
            });
```

第 294 行未配置 key 的返回保持 `config.model()`。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew --offline test --tests 'io.aster.llm.service.*' --tests 'io.aster.llm.client.VertxLlmClientRejectedModelTest'`
Expected: PASS（含 `LlmProxyServiceCompleteBillingTest`、`LlmProxyServiceUpstreamReachedTest`——若它们断言首帧为 `upstream_reached`，改为跳过首个 meta 帧后断言）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/aster/llm/api/dto/GeneratePolicyEvent.java src/main/java/io/aster/llm/service/LlmProxyService.java src/main/java/io/aster/llm/client/VertxLlmClient.java src/test/java/io/aster/llm/service/ src/test/java/io/aster/llm/client/VertxLlmClientRejectedModelTest.java
git commit -m "feat(llm): service 下推 BYOK model、首帧 meta 回传实际 model、按实际 model 记账、模型被拒返回 provider_rejected_model" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 4: `POST /api/v1/ai/models` —— 经 BYOK envelope 代理 provider 模型列表

**Files:**
- Modify: `src/main/java/io/aster/llm/client/LlmClient.java`
- Modify: `src/main/java/io/aster/llm/client/VertxLlmClient.java`（新增 `listModels`、`resolveModelsPath`）
- Create: `src/main/java/io/aster/llm/api/dto/ModelsResponse.java`
- Modify: `src/main/java/io/aster/llm/service/LlmProxyService.java`（新增 `listModels`）
- Modify: `src/main/java/io/aster/llm/api/AiAssistantResource.java`
- Test: `src/test/java/io/aster/llm/client/VertxLlmClientListModelsTest.java`（新，沿用 `VertxLlmClientChatReachedTest` 的本地 HTTP 桩方式）, `src/test/java/io/aster/llm/service/LlmProxyServiceModelsTest.java`（新）

**Interfaces:**
- Produces: `LlmClient.listModels(LlmRuntimeOptions options): Uni<List<String>>`（404/405 → 失败 `LlmCallFailedException(statusCode)`）；`ModelsResponse(String provider, List<String> models, String defaultModel, String fetchedAt)`（`models == null` 即「网关不提供列表」）；`LlmProxyService.listModels(String tenantId, ByokOverride byok): Uni<ModelsResponse>`；HTTP：`POST /api/v1/ai/models`，body `{"_byok":{...}}`（与其它 `/api/v1/ai/*` 同一 HMAC 规则），200 `ModelsResponse`；`_byok` 缺失/非法 → 400 `invalid_byok`；上游 5xx/超时/连接失败 → 502 `models_unavailable`；404/405 → 200 `models=null`。

- [ ] **Step 1: 失败测试（service 层）**

新建 `src/test/java/io/aster/llm/service/LlmProxyServiceModelsTest.java`：

```java
package io.aster.llm.service;

import io.aster.llm.api.dto.ModelsResponse;
import io.aster.llm.client.LlmCallFailedException;
import io.aster.llm.client.LlmClient;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.model.ByokOverride;
import io.aster.llm.model.LlmRuntimeOptions;
import io.aster.llm.tenant.LlmRuntimeOptionsResolver;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** POST /api/v1/ai/models 的 service 契约（ADR 0048 §3.3 / §6）。 */
class LlmProxyServiceModelsTest {

    private LlmProxyService service;
    private LlmClient client;

    @BeforeEach
    void setUp() {
        service = new LlmProxyService();
        client = mock(LlmClient.class);
        service.llmClient = client;
        service.config = mock(LlmConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(service.config.model()).thenReturn("gpt-4o-mini");
        when(service.config.anthropicDefaultModel()).thenReturn("claude-default");
        service.optionsResolver = mock(LlmRuntimeOptionsResolver.class);
        when(service.optionsResolver.resolve(anyString(), any())).thenReturn(
            new LlmRuntimeOptions("k", "openai", "https://api.openai.com", LlmRuntimeOptions.Source.BYOK));
    }

    private ModelsResponse call() {
        return service.listModels("t", new ByokOverride("openai", "k")).await().atMost(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("上游返回列表 → models 排序去重，defaultModel=平台该 provider 默认")
    void returnsModels() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().item(List.of("gpt-4o", "gpt-4o-mini", "gpt-4o")));
        ModelsResponse res = call();
        assertThat(res.models()).containsExactly("gpt-4o", "gpt-4o-mini");
        assertThat(res.defaultModel()).isEqualTo("gpt-4o-mini");
        assertThat(res.fetchedAt()).isNotBlank();
    }

    @Test
    @DisplayName("★网关不提供 /models（404/405）→ 200 models=null，不是错误")
    void unsupportedListIsNotAnError() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM API 错误 (404)", true, null, 404)));
        assertThat(call().models()).isNull();
    }

    @Test
    @DisplayName("★大小边界：超过 128 字符或形状非法的 id 丢弃，最多 200 条（jsonb 上界 ≤ 200×128）")
    void boundsIds() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().item(List.of("ok-1", "a".repeat(129), "bad id", "ok-2")));
        assertThat(call().models()).containsExactly("ok-1", "ok-2");
    }

    @Test
    @DisplayName("上游 5xx/连接失败 → 502 models_unavailable")
    void upstreamFailureIs502() {
        when(client.listModels(any())).thenReturn(Uni.createFrom().failure(
            new LlmCallFailedException("LLM 连接失败", false, null, 0)));
        assertThatThrownBy(this::call).isInstanceOf(WebApplicationException.class)
            .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(502));
    }

    @Test
    @DisplayName("无 BYOK → 400 invalid_byok（本端点只服务 BYOK key 校验）")
    void requiresByok() {
        assertThatThrownBy(() -> service.listModels("t", null).await().atMost(Duration.ofSeconds(5)))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("列表上限 200 条")
    void capsAt200() {
        List<String> many = java.util.stream.IntStream.range(0, 300).mapToObj(i -> "m-" + String.format("%03d", i)).toList();
        when(client.listModels(any())).thenReturn(Uni.createFrom().item(many));
        assertThat(call().models()).hasSize(200);
    }
}
```

- [ ] **Step 2: 失败测试（client 层）**

新建 `src/test/java/io/aster/llm/client/VertxLlmClientListModelsTest.java`（与 `VertxLlmClientChatReachedTest` 同一套原始 socket 桩：记录请求头、按需回包）：

```java
package io.aster.llm.client;

import io.aster.llm.model.LlmRuntimeOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** listModels：GET {base}/v1/models（或 {basePath}/models），按 provider 加鉴权头，解析 data[].id，非 200 带状态码失败。 */
class VertxLlmClientListModelsTest {

    private static final String LIST_BODY = "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"},{\"object\":\"model\"}]}";

    private Vertx vertx;
    private NetServer server;
    private final AtomicReference<String> requestHead = new AtomicReference<>("");

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        vertx.close();
    }

    /** 起本地 TCP 桩：收齐请求头后记录并回写 response；返回端口。 */
    private int listen(String response) throws Exception {
        CountDownLatch listening = new CountDownLatch(1);
        server = vertx.createNetServer();
        server.connectHandler(sock -> {
            StringBuilder head = new StringBuilder();
            boolean[] answered = {false};
            sock.handler((Buffer buf) -> {
                head.append(buf.toString());
                if (!answered[0] && head.indexOf("\r\n\r\n") >= 0) {
                    answered[0] = true;
                    requestHead.set(head.toString());
                    sock.write(response);
                }
            });
        });
        server.listen(0, "127.0.0.1").onComplete(ar -> listening.countDown());
        assertThat(listening.await(10, TimeUnit.SECONDS)).isTrue();
        return server.actualPort();
    }

    private static String ok(String json) {
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + json.length() + "\r\n\r\n" + json;
    }

    private VertxLlmClient client() {
        VertxLlmClient client = LlmClientTestSupport.newClient(vertx, Duration.ofSeconds(5), Duration.ofSeconds(5));
        io.aster.common.http.SharedWebClient shared = mock(io.aster.common.http.SharedWebClient.class);
        when(shared.client()).thenReturn(WebClient.create(vertx));
        client.sharedWebClient = shared;
        return client;
    }

    private static LlmRuntimeOptions opts(String provider, String baseUrl) {
        return new LlmRuntimeOptions("k-secret", provider, baseUrl, LlmRuntimeOptions.Source.BYOK);
    }

    @Test
    @DisplayName("openai：GET /v1/models，Bearer 鉴权，解析 data[].id（缺 id 的项跳过；响应体 > 1 MiB 拒绝解析、按失败处理）")
    void parsesOpenAiList() throws Exception {
        int port = listen(ok(LIST_BODY));
        List<String> ids = client().listModels(opts("openai", "http://127.0.0.1:" + port)).await().atMost(Duration.ofSeconds(10));
        assertThat(ids).containsExactly("gpt-4o", "gpt-4o-mini");
        assertThat(requestHead.get()).startsWith("GET /v1/models HTTP/1.1").contains("Authorization: Bearer k-secret");
    }

    @Test
    @DisplayName("anthropic：x-api-key + anthropic-version 头")
    void parsesAnthropicList() throws Exception {
        int port = listen(ok(LIST_BODY));
        client().listModels(opts("anthropic", "http://127.0.0.1:" + port)).await().atMost(Duration.ofSeconds(10));
        assertThat(requestHead.get()).contains("x-api-key: k-secret").contains("anthropic-version: 2023-06-01")
            .doesNotContain("Authorization:");
    }

    @Test
    @DisplayName("base URL 带路径（/codex/v1）→ 请求 /codex/v1/models")
    void appendsToBasePath() throws Exception {
        int port = listen(ok(LIST_BODY));
        client().listModels(opts("openai", "http://127.0.0.1:" + port + "/codex/v1/")).await().atMost(Duration.ofSeconds(10));
        assertThat(requestHead.get()).startsWith("GET /codex/v1/models HTTP/1.1");
    }

    @Test
    @DisplayName("404 → LlmCallFailedException.statusCode()==404，reachedProvider=true")
    void propagatesStatus() throws Exception {
        int port = listen("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
        Throwable t = catchThrowable(() -> client().listModels(opts("openai", "http://127.0.0.1:" + port)).await().atMost(Duration.ofSeconds(10)));
        assertThat(t).isInstanceOf(LlmCallFailedException.class);
        assertThat(((LlmCallFailedException) t).statusCode()).isEqualTo(404);
        assertThat(((LlmCallFailedException) t).reachedProvider()).isTrue();
    }

    @Test
    @DisplayName("连不上 → reachedProvider=false，statusCode=0")
    void connectionRefused() throws Exception {
        int port = listen(ok(LIST_BODY));
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        server = null;
        Throwable t = catchThrowable(() -> client().listModels(opts("openai", "http://127.0.0.1:" + port)).await().atMost(Duration.ofSeconds(10)));
        assertThat(t).isInstanceOf(LlmCallFailedException.class);
        assertThat(((LlmCallFailedException) t).reachedProvider()).isFalse();
        assertThat(((LlmCallFailedException) t).statusCode()).isZero();
    }
}
```

资源级测试：在 `src/test/java/io/aster/llm/api/` 新建 `AiAssistantResourceModelsTest.java`——不起 Quarkus，手工 `new AiAssistantResource()` 注入 `byokParser = new ByokEnvelopeParser()`、`llmProxyService = mock(LlmProxyService.class)`、`identityResolver = mock(RequestIdentityResolver.class)`（`tenantId()` 返回 "t"）、`llmConfig`（enabled=true 的 Proxy 桩，同 `PromptComposerAssistantTest.stubConfig`），`requestContext` 用本测试自己的 Proxy helper（`ByokEnvelopeParserTest.ctx` 是 private，不能复用）：

```java
    private static ContainerRequestContext ctx(boolean verified, String bodyJson) {
        Map<String, Object> props = Map.of(
            InternalCallerFilter.HMAC_VERIFIED_PROP, verified ? Boolean.TRUE : Boolean.FALSE,
            InternalCallerFilter.VERIFIED_BODY_PROP, bodyJson == null ? new byte[0] : bodyJson.getBytes(StandardCharsets.UTF_8));
        return (ContainerRequestContext) Proxy.newProxyInstance(ContainerRequestContext.class.getClassLoader(),
            new Class<?>[]{ContainerRequestContext.class},
            (proxy, method, args) -> "getProperty".equals(method.getName()) ? props.get((String) args[0]) : null);
    }

    @Test
    @DisplayName("已验签 body 带 _byok → 解析后交给 service.listModels；tenant 来自 identityResolver")
    void parsesEnvelopeFromVerifiedBody() {
        resource.requestContext = ctx(true, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\"}}");
        when(resource.llmProxyService.listModels(eq("t"), any())).thenReturn(Uni.createFrom().item(
            new ModelsResponse("openai", List.of("gpt-4o"), "gpt-4o-mini", "now")));
        resource.models("{}").await().atMost(Duration.ofSeconds(1));
        verify(resource.llmProxyService).listModels(eq("t"), argThat(b -> "openai".equals(b.provider())));
    }

    @Test
    @DisplayName("★未验签请求（公网/浏览器）→ _byok 被忽略，service 收到 null（由其回 400 invalid_byok）")
    void unverifiedBodyIsIgnored() {
        resource.requestContext = ctx(false, "{\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"k\"}}");
        when(resource.llmProxyService.listModels(eq("t"), isNull())).thenReturn(Uni.createFrom().item(
            new ModelsResponse("openai", null, "gpt-4o-mini", "now")));
        resource.models("{}").await().atMost(Duration.ofSeconds(1));
        verify(resource.llmProxyService).listModels(eq("t"), isNull());
    }

    @Test
    @DisplayName("★已验签但 envelope 非法（缺 apiKey）→ 资源层 400 invalid_byok，不调 service")
    void invalidVerifiedEnvelopeIs400() {
        resource.requestContext = ctx(true, "{\"_byok\":{\"provider\":\"openai\"}}");
        assertThatThrownBy(() -> resource.models("{}"))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(e -> {
                WebApplicationException wae = (WebApplicationException) e;
                assertThat(wae.getResponse().getStatus()).isEqualTo(400);
                assertThat(((Map<?, ?>) wae.getResponse().getEntity()).get("error")).isEqualTo("invalid_byok");
            });
        verify(resource.llmProxyService, never()).listModels(any(), any());
    }
```

（body 缓冲由 `InternalCallerFilter.bufferBody` 对所有 `/api/v1/ai/*` 请求统一完成——`/complete` 今天就靠它读 `_byok`，本端点同路径，不另起机制。）

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew --offline test --tests 'io.aster.llm.service.LlmProxyServiceModelsTest' --tests 'io.aster.llm.client.VertxLlmClientListModelsTest' --tests 'io.aster.llm.api.AiAssistantResourceModelsTest'`
Expected: 编译失败（`listModels` / `ModelsResponse` 不存在）。

- [ ] **Step 4: 实现**

`LlmClient.java` 接口加：

```java
    /**
     * 列出 provider 的可用模型 id（GET {base}/v1/models；ADR 0048 §3.3）。
     * 非 200 → 失败 {@link LlmCallFailedException}（带 statusCode，404/405 由上层判为「不支持列表」）。
     */
    Uni<List<String>> listModels(LlmRuntimeOptions options);
```

`VertxLlmClient.java` 加：

```java
    @Override
    public Uni<List<String>> listModels(LlmRuntimeOptions options) {
        String apiKey = options.apiKey();
        String provider = options.provider();
        validateCustomEndpoint(options);
        URI baseUri = URI.create(options.baseUrl());
        String path = resolveModelsPath(baseUri);
        int port = resolvePort(baseUri);

        return Uni.createFrom().emitter(emitter -> {
            HttpRequest<Buffer> httpRequest = getClient()
                .request(HttpMethod.GET, port, baseUri.getHost(), path)
                .ssl("https".equals(baseUri.getScheme()))
                .followRedirects(false)
                .timeout(config.timeout().toMillis());
            addAuthHeaders(httpRequest, apiKey, provider);

            httpRequest.send(ar -> {
                if (ar.failed()) {
                    boolean reached = !LlmCallFailedException.isConnectFailure(ar.cause());
                    emitter.fail(new LlmCallFailedException(
                        reached ? "模型列表读取失败" : "LLM 连接失败", reached, ar.cause(), 0));
                    return;
                }
                HttpResponse<Buffer> response = ar.result();
                if (response.statusCode() != 200) {
                    // 不回显 provider 错误体（可能含鉴权诊断）
                    emitter.fail(new LlmCallFailedException(
                        "LLM API 错误 (" + response.statusCode() + ")", true, null, response.statusCode()));
                    return;
                }
                // 响应体上界：Vert.x WebClient 已把 body 完整缓冲到内存后才到这里，本检查只是「拒绝超限响应、不继续解析入库」，
                // 不是接收期间的内存硬上限（硬上限需 BodyCodec 流式读取，本 ADR 不做；出站只打 allowlist 内网关）。
                if (response.body() != null && response.body().length() > MAX_MODELS_BODY_BYTES) {
                    emitter.fail(new LlmCallFailedException("模型列表响应过大", true, null, 200));
                    return;
                }
                try {
                    emitter.complete(extractModelIds(response.bodyAsJsonObject()));
                } catch (Exception e) {
                    emitter.fail(new LlmCallFailedException("模型列表解析失败", true, e, 200));
                }
            });
        });
    }

    private static final int MAX_MODELS_BODY_BYTES = 1 << 20;

    /** OpenAI 与 Anthropic 的 /v1/models 都是 {"data":[{"id":...}]}；缺 id 的项跳过（形状/条数限制在 service 层统一做）。 */
    private static List<String> extractModelIds(JsonObject body) {
        JsonArray data = body.getJsonArray("data", new JsonArray());
        List<String> ids = new java.util.ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            String id = data.getJsonObject(i).getString("id");
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 与 resolveChatPath 同一规则：base 带路径则追加 /models，否则 /v1/models。 */
    private String resolveModelsPath(URI baseUri) {
        String basePath = baseUri.getPath();
        if (basePath == null) basePath = "";
        if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        return basePath.isEmpty() ? "/v1/models" : basePath + "/models";
    }
```

新建 `src/main/java/io/aster/llm/api/dto/ModelsResponse.java`：

```java
package io.aster.llm.api.dto;

import java.util.List;

/**
 * {@code POST /api/v1/ai/models} 响应（ADR 0048 §3.3）。
 * models=null 表示 provider/网关不提供模型列表（404/405），不算错误；非空时已按形状过滤、去重、排序、≤200 条。
 */
public record ModelsResponse(String provider, List<String> models, String defaultModel, String fetchedAt) {
}
```

`LlmProxyService.java` 加：

```java
    /** 经 BYOK 凭证查询 provider 模型列表；仅服务 BYOK（无 envelope → 400），上游失败 → 502。 */
    public Uni<ModelsResponse> listModels(String tenantId, ByokOverride byok) {
        if (byok == null || !byok.isValid()) {
            throw jsonError(400, "invalid_byok", "模型列表查询需要 BYOK 凭证");
        }
        LlmRuntimeOptions options;
        try {
            options = optionsResolver.resolve(tenantId, byok);
        } catch (IllegalArgumentException e) {
            throw jsonError(400, "invalid_byok", e.getMessage());
        }
        String defaultModel = LlmModelPolicy.wireModel(null, options, config);
        String fetchedAt = java.time.Instant.now().toString();
        return llmClient.listModels(options)
            .map(ids -> new ModelsResponse(options.provider(), boundModelIds(ids), defaultModel, fetchedAt))
            .onFailure(LlmCallFailedException.class).recoverWithUni(t -> {
                int status = ((LlmCallFailedException) t).statusCode();
                if (status == 404 || status == 405) {
                    return Uni.createFrom().item(new ModelsResponse(options.provider(), null, defaultModel, fetchedAt));
                }
                LOG.warnf("模型列表查询失败: tenant=%s provider=%s status=%d", tenantId, options.provider(), status);
                return Uni.createFrom().failure(jsonError(502, "models_unavailable", "无法从 provider 读取模型列表，请稍后重试"));
            });
    }

    /** 与 ByokEnvelopeParser.normalizeModel 同一形状；去重、排序、≤200 条 → jsonb 上界 ≤ 200×128 字符。 */
    private static final java.util.regex.Pattern MODEL_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    private static List<String> boundModelIds(List<String> ids) {
        return ids.stream().filter(id -> MODEL_ID.matcher(id).matches()).distinct().sorted().limit(200).toList();
    }

    private static jakarta.ws.rs.WebApplicationException jsonError(int status, String code, String message) {
        return new jakarta.ws.rs.WebApplicationException(
            jakarta.ws.rs.core.Response.status(status)
                .entity(java.util.Map.of("error", code, "message", message))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .build());
    }
```

`AiAssistantResource.java` 加端点（放在 `complete` 之后）：

```java
    /**
     * 经 BYOK 凭证查询 provider 可用模型（ADR 0048）。POST 而非 GET：{@code _byok} envelope 只能从
     * 已 HMAC 验签的 body 读取。body 只含 envelope，无用户提示，故不过安全 guard。
     */
    @POST
    @Path("/models")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ModelsResponse> models(String ignoredBody) {
        checkEnabled();
        return llmProxyService.listModels(tenantId(), parseByok());
    }
```

（`import io.aster.llm.api.dto.ModelsResponse;`。参数 `ignoredBody` 仅为让 RESTEasy 消费 body；envelope 由 `parseByok()` 从验签字节读取。）

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew --offline test --tests 'io.aster.llm.*'`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/aster/llm/client/LlmClient.java src/main/java/io/aster/llm/client/VertxLlmClient.java src/main/java/io/aster/llm/api/dto/ModelsResponse.java src/main/java/io/aster/llm/service/LlmProxyService.java src/main/java/io/aster/llm/api/AiAssistantResource.java src/test/java/io/aster/llm/client/VertxLlmClientListModelsTest.java src/test/java/io/aster/llm/service/LlmProxyServiceModelsTest.java src/test/java/io/aster/llm/api/AiAssistantResourceModelsTest.java
git commit -m "feat(ai): POST /api/v1/ai/models 经 BYOK 凭证代理 provider 模型列表（ADR 0048 §3.3）" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 5: aster-api 全量门禁 + ADR §9.1 写回

- [ ] **Step 1: 全量测试（memory 配方，sdd-pg :55433 / sdd-redis :6380）**

Run: `DB_JDBC_URL=jdbc:postgresql://localhost:55433/aster_policy DB_USERNAME=postgres DB_PASSWORD=postgres QUARKUS_REDIS_HOSTS=redis://localhost:6380 ./gradlew --offline :replay:cleanTest :cleanTest :replay:test :test --continue`
Expected: 仅已知基线失败（`PrismaDatabaseE2ETest`）。

- [ ] **Step 2: ADR 写回**

在 `docs/adr/0048-byok-model-selection.md` §3.3 把 `GET /api/v1/llm/models` 改为 `POST /api/v1/ai/models`（body 只含 `_byok`，与其它 `/api/v1/ai/*` 同一 HMAC 规则），`/api/v1/llm/complete` → `/api/v1/ai/complete`，`io.aster.llm.tenant.LlmEndpointPolicy` → `io.aster.llm.security`；§3.4 `action: "setModel"` 改为「`update` 动作可携带 `model`（null/空白=清空），非空时与新建同样经列表校验」；§3.3 响应去掉 `supported`（`models=null` 即不支持），模型 id 形状/条数/响应体大小上界写入；§3.4 healthcheck 刷新预算（24h、每轮 50 条、5 s 超时）；§5 审计动作改为 `ai-key.create` / `ai-key.update` 的 metadata 增加 `model`；§9.1 记录「实际发出 model 的唯一算法在 `LlmModelPolicy`；SSE 首帧 `meta`」。

```bash
git add docs/adr/0048-byok-model-selection.md
git commit -m "docs(adr): ADR 0048 §3/§5/§9 按实现回写（/api/v1/ai/models、update 携带 model、LlmModelPolicy）" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## aster-cloud（worktree `/Users/rpang/IdeaProjects/aster-cloud-adr0048`，分支 `feat/adr0048-byok-model`）

### Task 6: schema + migration 0055 + vault

**Files:**
- Modify: `src/db/schema.ts:1680`（`priority` 之后）
- Create: `drizzle/0055_ai_key_binding_model.sql`
- Modify: `drizzle/meta/_journal.json`
- Modify: `src/lib/ai-key-vault.ts:29-72,120-136,143-184`
- Test: `src/__tests__/lib/ai-key-vault-model.test.ts`（新；用 `vi.mock('@/lib/prisma')` 捕获 SQL/update 参数，风格参考 `src/__tests__/lib/ai-quota.test.ts` 对 `db` 的 mock）

**Interfaces:**
- Produces: 列 `model text`、`availableModels jsonb`（形状 `{ models: string[] | null, defaultModel: string }`）、`modelsCheckedAt timestamp`；`saveBYOKKey(params & { model?: string | null })`；`updateBYOKKeyMeta(params & { model?: string | null })`；`updateBYOKAvailableModels(bindingId, { models, defaultModel }): Promise<void>`；`ByokCandidate.model: string | null`。

- [ ] **Step 1: 失败测试**

新建 `src/__tests__/lib/ai-key-vault-model.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';

// 捕获 db.execute 的 SQL 片段与 update().set() 参数，不连库。
const { executeMock, setMock, whereMock, returningMock, selectRows } = vi.hoisted(() => ({
  executeMock: vi.fn(),
  setMock: vi.fn(),
  whereMock: vi.fn(),
  returningMock: vi.fn(),
  selectRows: vi.fn(),
}));

vi.mock('@/lib/prisma', () => {
  const chain = { set: setMock, where: whereMock, returning: returningMock };
  setMock.mockReturnValue(chain);
  whereMock.mockReturnValue(chain);
  return {
    db: {
      execute: executeMock,
      update: vi.fn(() => chain),
      select: vi.fn(() => ({ from: () => ({ where: () => ({ orderBy: selectRows }) }) })),
    },
    aiKeyBindings: {
      id: 'id', userId: 'userId', provider: 'provider', providerUrl: 'providerUrl', tokenQuota: 'tokenQuota',
      expiresAt: 'expiresAt', priority: 'priority', createdAt: 'createdAt', active: 'active', model: 'model',
      availableModels: 'availableModels', modelsCheckedAt: 'modelsCheckedAt', updatedAt: 'updatedAt',
    },
  };
});

import { saveBYOKKey, updateBYOKKeyMeta, updateBYOKAvailableModels, getBYOKCandidatesForInference } from '@/lib/ai-key-vault';

beforeEach(() => {
  vi.clearAllMocks();
  process.env.AI_KEY_ENCRYPTION_SECRET = 'unit-test-secret-at-least-16';
});

describe('ai-key-vault：model 列（ADR 0048）', () => {
  it('saveBYOKKey 把 model 写入 INSERT（未提供 → NULL）', async () => {
    executeMock.mockResolvedValue([]);
    await saveBYOKKey({ userId: 'u', provider: 'openai', apiKey: 'sk-'.padEnd(24, 'x'), model: 'gpt-4o' });
    const sqlText = JSON.stringify(executeMock.mock.calls[0][0]);
    expect(sqlText).toContain('"model"');
    expect(sqlText).toContain('gpt-4o');
    await saveBYOKKey({ userId: 'u', provider: 'openai', apiKey: 'sk-'.padEnd(24, 'x') });
    expect(JSON.stringify(executeMock.mock.calls[1][0])).toContain('"model"');
  });

  it('updateBYOKKeyMeta：model 未提供=不动；null=清空；字符串=写入', async () => {
    returningMock.mockResolvedValue([{ provider: 'openai', keyHint: 'xxxx' }]);
    await updateBYOKKeyMeta({ userId: 'u', bindingId: 'b', tokenQuota: 5 });
    expect(setMock.mock.calls[0][0]).not.toHaveProperty('model');
    await updateBYOKKeyMeta({ userId: 'u', bindingId: 'b', model: null });
    expect(setMock.mock.calls[1][0]).toMatchObject({ model: null });
    await updateBYOKKeyMeta({ userId: 'u', bindingId: 'b', model: 'gpt-4o' });
    expect(setMock.mock.calls[2][0]).toMatchObject({ model: 'gpt-4o' });
  });

  it('updateBYOKAvailableModels 写 availableModels + modelsCheckedAt，并再次收敛大小（形状过滤、≤200 条）', async () => {
    whereMock.mockResolvedValue(undefined);
    await updateBYOKAvailableModels('b', { models: ['a', 'b', 'x'.repeat(129), 'bad id'], defaultModel: 'a' });
    expect(setMock.mock.calls[0][0]).toMatchObject({ availableModels: { models: ['a', 'b'], defaultModel: 'a' } });
    expect(setMock.mock.calls[0][0].modelsCheckedAt).toBeInstanceOf(Date);
    const many = Array.from({ length: 250 }, (_, i) => `m-${i}`);
    await updateBYOKAvailableModels('b', { models: many, defaultModel: 'a' });
    expect(setMock.mock.calls[1][0].availableModels.models).toHaveLength(200);
  });

  it('getBYOKCandidatesForInference 返回 model（缺省 null）', async () => {
    selectRows.mockResolvedValue([{ id: 'b', provider: 'openai', providerUrl: null, tokenQuota: null, expiresAt: null, model: 'gpt-4o' },
      { id: 'c', provider: 'anthropic', providerUrl: null, tokenQuota: null, expiresAt: null, model: null }]);
    const rows = await getBYOKCandidatesForInference('u');
    expect(rows.map((r) => r.model)).toEqual(['gpt-4o', null]);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm exec vitest run src/__tests__/lib/ai-key-vault-model.test.ts`
Expected: FAIL（`updateBYOKAvailableModels` 不存在；INSERT 无 `"model"`；candidates 无 `model`）。

- [ ] **Step 3: 实现**

`src/db/schema.ts` 在 `priority` 之后加：

```ts
    /**
     * 用户为这条 key 指定的模型 id（ADR 0048）。null=平台该 provider 的默认模型。
     * 调用时由选中的 binding 决定 provider 与 model；不参与优先级排序。
     */
    model: text('model'),
    /**
     * 最近一次经 aster-api 查询到的 provider 模型列表：{ models: string[] | null, defaultModel: string }。
     * models=null 表示网关不提供列表。只用于保存时校验与 UI 提示，不参与调用选择。
     */
    availableModels: jsonb('availableModels').$type<{ models: string[] | null; defaultModel: string }>(),
    /** 上次查询模型列表的时间；查询失败不清空旧列表，只写 lastError。 */
    modelsCheckedAt: timestamp('modelsCheckedAt', { mode: 'date' }),
```

（确认文件顶部已 `import { jsonb } from 'drizzle-orm/pg-core'`；`aiUsageRecords.safetyFlags` 已用 json/jsonb，沿用同一导入。）

新建 `drizzle/0055_ai_key_binding_model.sql`：

```sql
-- ADR 0048：BYOK 绑定可选模型。
--   model           : 用户指定的模型 id；NULL=平台该 provider 默认。
--   availableModels : 最近一次 provider /v1/models 结果 { models, defaultModel }，仅校验/提示用。
--   modelsCheckedAt : 上次查询时间。
-- 全部 nullable，不破坏现有行；IF NOT EXISTS 与 db-bootstrap 自愈路径幂等一致。
ALTER TABLE "AiKeyBinding" ADD COLUMN IF NOT EXISTS "model" text;--> statement-breakpoint
ALTER TABLE "AiKeyBinding" ADD COLUMN IF NOT EXISTS "availableModels" jsonb;--> statement-breakpoint
ALTER TABLE "AiKeyBinding" ADD COLUMN IF NOT EXISTS "modelsCheckedAt" timestamp;
```

`drizzle/meta/_journal.json` 末尾 entries 追加：

```json
    {
      "idx": 55,
      "version": "7",
      "when": 1790000000000,
      "tag": "0055_ai_key_binding_model",
      "breakpoints": true
    }
```

`src/lib/ai-key-vault.ts`：
- `saveBYOKKey` params 加 `/** 指定模型 id（可选）。null/undefined = 平台默认。 */ model?: string | null;`；`const model = params.model ?? null;`；INSERT 列表 `"expiresAt", "priority"` 之间插入 `"model"`，VALUES 对应位置插入 `${model},`。
- `updateBYOKKeyMeta` params 加 `model?: string | null;`，set 加 `if (params.model !== undefined) set.model = params.model;`。
- `ByokCandidate` 加 `model: string | null;`；`getBYOKCandidatesForInference` 的 select 加 `model: aiKeyBindings.model`，map 加 `model: r.model ?? null`。
- 新增：

```ts
/** 与 aster-api / byok-models.ts 同一形状；入库前再收敛一次，jsonb 上界 ≤ 200×128 字符。 */
const MODEL_ID_RE = /^[A-Za-z0-9._:/-]{1,128}$/;
const MAX_AVAILABLE_MODELS = 200;

/** 写入最近一次模型列表查询结果（healthcheck / 保存时）。失败路径不调用本函数，旧列表保留。 */
export async function updateBYOKAvailableModels(
  bindingId: string,
  result: { models: string[] | null; defaultModel: string },
): Promise<void> {
  const models = result.models
    ? result.models.filter((m) => MODEL_ID_RE.test(m)).slice(0, MAX_AVAILABLE_MODELS)
    : null;
  await db
    .update(aiKeyBindings)
    .set({ availableModels: { models, defaultModel: result.defaultModel }, modelsCheckedAt: new Date(), updatedAt: new Date() })
    .where(eq(aiKeyBindings.id, bindingId));
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm exec vitest run src/__tests__/lib/ai-key-vault-model.test.ts src/__tests__/integration/ai-key-vault-multikey.integration.test.ts && pnpm exec tsc --noEmit -p tsconfig.json`
Expected: PASS（集成测试若需 DB 则按其头部说明用 sdd-pg `aster_cloud` 跑；先对 sdd-pg 执行 `pnpm db:migrate` 应用 0055）。

- [ ] **Step 5: 提交**

```bash
git add src/db/schema.ts drizzle/0055_ai_key_binding_model.sql drizzle/meta/_journal.json src/lib/ai-key-vault.ts src/__tests__/lib/ai-key-vault-model.test.ts
git commit -m "feat(byok): AiKeyBinding 增加 model / availableModels / modelsCheckedAt（migration 0055，ADR 0048 §3.1）" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 7: envelope 注入 `_byok.model` + `byok-models.ts` 查询助手

**Files:**
- Modify: `src/lib/byok-envelope.ts:24-35,85,120-124`
- Create: `src/lib/byok-models.ts`
- Test: `src/__tests__/lib/byok-envelope.test.ts`, `src/__tests__/lib/byok-models.test.ts`（新）

**Interfaces:**
- Produces: `ByokEnvelope.model: string | null`；`injectByokEnvelope` 在 `model` 非空时写 `_byok.model`；`fetchByokModels(userId, { provider, apiKey, baseUrl }, opts?: { timeoutMs?: number }): Promise<ByokModelsResult>`，`type ByokModelsResult = { ok: true; models: string[] | null; defaultModel: string } | { ok: false; error: string }`（`models === null` = 网关不提供列表）；`MODEL_ID_RE = /^[A-Za-z0-9._:/-]{1,128}$/`（导出，供路由复用）。

- [ ] **Step 1: 失败测试**

`byok-envelope.test.ts`：把 L53–55 的 `toEqual` 对象加 `model: null`；`cand()` 工厂加 `model: over.model ?? null`（类型加 `model: string | null`）；新增：

```ts
  it('候选带 model → envelope.model 透传', async () => {
    mockCandidates.mockResolvedValue([cand({ id: 'b1', model: 'gpt-4o' })]);
    mockDecryptById.mockResolvedValue('sk-user');
    expect((await resolveByokEnvelope('u1'))?.model).toBe('gpt-4o');
  });
```

`describe('injectByokEnvelope')` 内新增：

```ts
  it('envelope.model 非空 → 注入 _byok.model；为空 → 不带该字段（旧 api 兼容）', () => {
    const withModel = injectByokEnvelope('{"a":1}', { provider: 'openai', apiKey: 'k', baseUrl: null, bindingId: 'b', model: 'gpt-4o' });
    expect(JSON.parse(withModel.body)._byok).toEqual({ provider: 'openai', apiKey: 'k', model: 'gpt-4o' });
    const noModel = injectByokEnvelope('{"a":1}', { provider: 'openai', apiKey: 'k', baseUrl: null, bindingId: 'b', model: null });
    expect(JSON.parse(noModel.body)._byok).toEqual({ provider: 'openai', apiKey: 'k' });
  });
```

新建 `src/__tests__/lib/byok-models.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/api-signing', () => ({
  signInternalCallerHeaders: vi.fn().mockResolvedValue({ 'X-Internal-Signature': 'sig', 'X-Aster-Timestamp': '1', 'X-Aster-Nonce': 'n', 'X-Internal-Caller': 'cloud-bff' }),
}));

import { fetchByokModels } from '@/lib/byok-models';
import { signInternalCallerHeaders } from '@/lib/api-signing';

const creds = { provider: 'openai', apiKey: 'sk-x', baseUrl: null };

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('fetch', vi.fn());
});

describe('fetchByokModels（经 aster-api POST /api/v1/ai/models）', () => {
  it('签名覆盖含 _byok 的 body，并以 userId 作 tenant', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ provider: 'openai', models: ['gpt-4o'], defaultModel: 'gpt-4o-mini', fetchedAt: 'now' }), { status: 200 }));
    const res = await fetchByokModels('u1', creds);
    expect(res).toEqual({ ok: true, models: ['gpt-4o'], defaultModel: 'gpt-4o-mini' });
    const [, path, body, tenant] = vi.mocked(signInternalCallerHeaders).mock.calls[0];
    expect(path).toBe('/api/v1/ai/models');
    expect(JSON.parse(body as string)._byok).toEqual({ provider: 'openai', apiKey: 'sk-x' });
    expect(tenant).toBe('u1');
  });

  it('baseUrl 非空时注入 _byok.baseUrl', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ models: null, defaultModel: 'x' }), { status: 200 }));
    await fetchByokModels('u1', { ...creds, baseUrl: 'https://gw.example.com/v1' });
    expect(JSON.parse(vi.mocked(signInternalCallerHeaders).mock.calls[0][2] as string)._byok.baseUrl).toBe('https://gw.example.com/v1');
  });

  it('★网关不支持列表 → ok:true models:null（不是错误）', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ models: null, defaultModel: 'x' }), { status: 200 }));
    expect(await fetchByokModels('u1', creds)).toEqual({ ok: true, models: null, defaultModel: 'x' });
  });

  it('上游 502 / 网络异常 → ok:false', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ error: 'models_unavailable' }), { status: 502 }));
    expect(await fetchByokModels('u1', creds)).toEqual({ ok: false, error: 'models_unavailable' });
    vi.mocked(fetch).mockRejectedValue(new Error('boom'));
    expect((await fetchByokModels('u1', creds)).ok).toBe(false);
  });

  it('HMAC 密钥缺失 → ok:false cloud_misconfigured', async () => {
    vi.mocked(signInternalCallerHeaders).mockRejectedValueOnce(new Error('ASTER_PLAN_GATE_HMAC_KEY not configured'));
    expect(await fetchByokModels('u1', creds)).toEqual({ ok: false, error: 'cloud_misconfigured' });
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm exec vitest run src/__tests__/lib/byok-envelope.test.ts src/__tests__/lib/byok-models.test.ts`
Expected: FAIL / 模块不存在。

- [ ] **Step 3: 实现**

`byok-envelope.ts`：`ByokEnvelope` 加

```ts
  /** AiKeyBinding.model：用户为该 key 指定的模型；null=平台该 provider 默认。非空则注入 `_byok.model`。 */
  model: string | null;
```

L85 返回改为 `return { provider, apiKey, baseUrl: c.providerUrl ?? null, bindingId: c.id, model: c.model ?? null };`；`injectByokEnvelope` 在 `if (envelope.baseUrl)` 之后加：

```ts
    // model 仅在用户指定时注入；为空则 aster-api 用平台该 provider 默认（旧版 api 也能照常解析）。
    if (envelope.model) {
      byok.model = envelope.model;
    }
```

文件头注释的 envelope 形状补 `model?`。

新建 `src/lib/byok-models.ts`：

```ts
/**
 * 经 aster-api `POST /api/v1/ai/models` 查询某 BYOK 凭证可用的模型列表（ADR 0048 §3.3/§3.4）。
 *
 * 为什么不直接从 cloud 调 provider：出站 SSRF/allowlist 边界在 aster-api（自定义 providerUrl 必须
 * 经管理员 allowlist），cloud 只负责解密 key 并签名转发。结果只用于保存时校验与 UI 提示，
 * 不参与调用选择。
 */
import { signInternalCallerHeaders } from '@/lib/api-signing';

const ASTER_API_BASE =
  process.env.ASTER_POLICY_API_INTERNAL_URL ||
  process.env.NEXT_PUBLIC_ASTER_POLICY_API_URL ||
  'https://policy.aster-lang.dev';

const MODELS_PATH = '/api/v1/ai/models';

/** 与 aster-api ByokEnvelopeParser.normalizeModel 同一形状。 */
export const MODEL_ID_RE = /^[A-Za-z0-9._:/-]{1,128}$/;

/** models === null：网关不提供列表（不算错误，保存时跳过存在性校验）。 */
export type ByokModelsResult =
  | { ok: true; models: string[] | null; defaultModel: string }
  | { ok: false; error: string };

export async function fetchByokModels(
  userId: string,
  creds: { provider: string; apiKey: string; baseUrl: string | null },
  opts?: { timeoutMs?: number },
): Promise<ByokModelsResult> {
  const byok: Record<string, string> = { provider: creds.provider, apiKey: creds.apiKey };
  if (creds.baseUrl) byok.baseUrl = creds.baseUrl;
  const body = JSON.stringify({ _byok: byok });

  let headers: Awaited<ReturnType<typeof signInternalCallerHeaders>>;
  try {
    // tenant 与推理路径一致：用户 id（见 llm/complete）。签名覆盖含 envelope 的最终 body。
    headers = await signInternalCallerHeaders('POST', MODELS_PATH, body, userId, '', { query: '', userId: '' });
  } catch {
    return { ok: false, error: 'cloud_misconfigured' };
  }

  try {
    const res = await fetch(`${ASTER_API_BASE}${MODELS_PATH}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': userId, ...headers },
      body,
      signal: AbortSignal.timeout(opts?.timeoutMs ?? 10_000),
    });
    if (!res.ok) {
      const err = (await res.json().catch(() => null)) as { error?: string } | null;
      return { ok: false, error: err?.error ?? `http_${res.status}` };
    }
    const data = (await res.json()) as { models: string[] | null; defaultModel: string };
    return { ok: true, models: data.models ?? null, defaultModel: data.defaultModel };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : 'fetch_failed' };
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm exec vitest run src/__tests__/lib/byok-envelope.test.ts src/__tests__/lib/byok-models.test.ts src/__tests__/app/api/llm`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/lib/byok-envelope.ts src/lib/byok-models.ts src/__tests__/lib/byok-envelope.test.ts src/__tests__/lib/byok-models.test.ts
git commit -m "feat(byok): envelope 注入 _byok.model；经 aster-api 查询 provider 模型列表" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 8: `/api/user/ai-keys` 与设置页数据：保存校验、update 携带 model、GET 新列

**Files:**
- Modify: `src/app/api/user/ai-keys/route.ts`
- Modify: `src/app/[locale]/(dashboard)/settings/ai-keys/page.tsx:17-53`
- Test: `src/__tests__/api/ai-keys-route.test.ts`

**Interfaces:**
- Consumes: `fetchByokModels`、`MODEL_ID_RE`、`saveBYOKKey.model`、`updateBYOKKeyMeta.model`、`updateBYOKAvailableModels`、`getDecryptedBYOKKeyById`（PATCH 重校验）。
- Produces: POST body 可带 `model?: string | null`；响应 `{ ok, id, modelsChecked: boolean, availableModels?: string[] | null }`；400 `{ error: 'model_not_available', available: string[] }`；400 `{ error: 'Invalid model' }`；PATCH `update` 可带 `model?: string | null`——非空时**同样经列表校验**（解密该 binding 的 key 后调 `fetchByokModels`，查询失败放行，响应带 `modelsChecked`）；GET 每条 binding 多返回 `model`、`availableModels`、`modelsCheckedAt`。空白字符串在两处都等价于 null（清空）。

- [ ] **Step 1: 失败测试**

`ai-keys-route.test.ts` 顶部 mock 增加：

```ts
vi.mock('@/lib/byok-models', () => ({
  fetchByokModels: vi.fn(),
  MODEL_ID_RE: /^[A-Za-z0-9._:/-]{1,128}$/,
}));
```

`vi.mock('@/lib/ai-key-vault', ...)` 加 `updateBYOKAvailableModels: vi.fn()`、`getDecryptedBYOKKeyById: vi.fn()`、`findBindingForUser: vi.fn()`；import 行加 `fetchByokModels`、`updateBYOKAvailableModels`、`getDecryptedBYOKKeyById`、`findBindingForUser`。在 `describe('POST ...')` 内新增（沿用 `goodKey`）：

```ts
  it('model 指定且在列表中 → 保存并写 availableModels，返回 modelsChecked=true', async () => {
    vi.mocked(fetchByokModels).mockResolvedValue({ ok: true, models: ['gpt-4o', 'gpt-4o-mini'], defaultModel: 'gpt-4o-mini' });
    vi.mocked(saveBYOKKey).mockResolvedValue({ id: 'new-1' });
    const r = await POST(postReq({ provider: 'openai', apiKey: goodKey, model: 'gpt-4o' }));
    expect(r.status).toBe(200);
    expect(await r.json()).toMatchObject({ ok: true, id: 'new-1', modelsChecked: true });
    expect(vi.mocked(saveBYOKKey).mock.calls[0][0]).toMatchObject({ model: 'gpt-4o' });
    expect(updateBYOKAvailableModels).toHaveBeenCalledWith('new-1', { models: ['gpt-4o', 'gpt-4o-mini'], defaultModel: 'gpt-4o-mini' });
    expect(vi.mocked(logAuditEvent).mock.calls[0][0].metadata).toMatchObject({ model: 'gpt-4o' });
  });

  it('★model 不在列表 → 400 model_not_available，附可用列表，不保存', async () => {
    vi.mocked(fetchByokModels).mockResolvedValue({ ok: true, models: ['gpt-4o-mini'], defaultModel: 'gpt-4o-mini' });
    const r = await POST(postReq({ provider: 'openai', apiKey: goodKey, model: 'gpt-9' }));
    expect(r.status).toBe(400);
    expect(await r.json()).toMatchObject({ error: 'model_not_available', available: ['gpt-4o-mini'] });
    expect(saveBYOKKey).not.toHaveBeenCalled();
  });

  it('★列表查询失败 → 仍保存，modelsChecked=false，不写 availableModels', async () => {
    vi.mocked(fetchByokModels).mockResolvedValue({ ok: false, error: 'models_unavailable' });
    vi.mocked(saveBYOKKey).mockResolvedValue({ id: 'new-2' });
    const r = await POST(postReq({ provider: 'openai', apiKey: goodKey, model: 'gpt-4o' }));
    expect(r.status).toBe(200);
    expect(await r.json()).toMatchObject({ ok: true, modelsChecked: false });
    expect(updateBYOKAvailableModels).not.toHaveBeenCalled();
  });

  it('网关不支持列表（models=null）→ 保存，不校验 model', async () => {
    vi.mocked(fetchByokModels).mockResolvedValue({ ok: true, models: null, defaultModel: 'x' });
    vi.mocked(saveBYOKKey).mockResolvedValue({ id: 'new-3' });
    const r = await POST(postReq({ provider: 'openai', apiKey: goodKey, model: 'gw-alias' }));
    expect(r.status).toBe(200);
    expect(updateBYOKAvailableModels).toHaveBeenCalledWith('new-3', { models: null, defaultModel: 'x' });
  });

  it('model 形状非法 → 400，不调 aster-api', async () => {
    const r = await POST(postReq({ provider: 'openai', apiKey: goodKey, model: 'bad model!' }));
    expect(r.status).toBe(400);
    expect(fetchByokModels).not.toHaveBeenCalled();
  });

  it('★model 纯空白 → 视为未指定（null），与 aster-api normalizeModel 一致', async () => {
    vi.mocked(fetchByokModels).mockResolvedValue({ ok: true, models: ['gpt-4o-mini'], defaultModel: 'gpt-4o-mini' });
    vi.mocked(saveBYOKKey).mockResolvedValue({ id: 'new-5' });
    const r = await POST(postReq({ provider: 'openai', apiKey: goodKey, model: '   ' }));
    expect(r.status).toBe(200);
    expect(vi.mocked(saveBYOKKey).mock.calls[0][0]).toMatchObject({ model: null });
  });

  it('vertex 不查询列表（Phase 2 不支持 BYOK 推理），model 原样入库', async () => {
    vi.mocked(saveBYOKKey).mockResolvedValue({ id: 'new-4' });
    const r = await POST(postReq({ provider: 'vertex', apiKey: goodKey, model: 'gemini-x' }));
    expect(r.status).toBe(200);
    expect(fetchByokModels).not.toHaveBeenCalled();
  });
```

在 `describe('PATCH ... 编辑')` 内新增：

```ts
  it('update 携带 model → 解密该 key 经列表校验后写入，审计 metadata 含 model', async () => {
    vi.mocked(findBindingForUser).mockResolvedValue({ id: 'b1', provider: 'openai', providerUrl: null });
    vi.mocked(getDecryptedBYOKKeyById).mockResolvedValue('sk-'.padEnd(24, 'x'));
    vi.mocked(fetchByokModels).mockResolvedValue({ ok: true, models: ['gpt-4o'], defaultModel: 'gpt-4o-mini' });
    vi.mocked(updateBYOKKeyMeta).mockResolvedValue({ provider: 'openai', keyHint: 'xxxx' });
    const r = await PATCH(patchReq({ id: 'b1', action: 'update', model: 'gpt-4o' }));
    expect(r.status).toBe(200);
    expect(await r.json()).toMatchObject({ ok: true, modelsChecked: true });
    expect(getDecryptedBYOKKeyById).toHaveBeenCalledWith('user-1', 'b1');
    expect(vi.mocked(updateBYOKKeyMeta).mock.calls[0][0]).toMatchObject({ model: 'gpt-4o' });
    expect(updateBYOKAvailableModels).toHaveBeenCalledWith('b1', { models: ['gpt-4o'], defaultModel: 'gpt-4o-mini' });
    expect(vi.mocked(logAuditEvent).mock.calls[0][0].metadata).toMatchObject({ model: 'gpt-4o' });
  });

  it('★update model 不在列表 → 400 model_not_available，不写入', async () => {
    vi.mocked(findBindingForUser).mockResolvedValue({ id: 'b1', provider: 'openai', providerUrl: null });
    vi.mocked(getDecryptedBYOKKeyById).mockResolvedValue('sk-'.padEnd(24, 'x'));
    vi.mocked(fetchByokModels).mockResolvedValue({ ok: true, models: ['gpt-4o-mini'], defaultModel: 'gpt-4o-mini' });
    const r = await PATCH(patchReq({ id: 'b1', action: 'update', model: 'gpt-9' }));
    expect(r.status).toBe(400);
    expect(updateBYOKKeyMeta).not.toHaveBeenCalled();
  });

  it('update model：列表查询失败 → 仍写入，modelsChecked=false', async () => {
    vi.mocked(findBindingForUser).mockResolvedValue({ id: 'b1', provider: 'openai', providerUrl: null });
    vi.mocked(getDecryptedBYOKKeyById).mockResolvedValue('sk-'.padEnd(24, 'x'));
    vi.mocked(fetchByokModels).mockResolvedValue({ ok: false, error: 'models_unavailable' });
    vi.mocked(updateBYOKKeyMeta).mockResolvedValue({ provider: 'openai', keyHint: 'xxxx' });
    const r = await PATCH(patchReq({ id: 'b1', action: 'update', model: 'gpt-4o' }));
    expect(r.status).toBe(200);
    expect(await r.json()).toMatchObject({ ok: true, modelsChecked: false });
  });

  it('update：binding 不属于本人 → 404，不解密', async () => {
    vi.mocked(findBindingForUser).mockResolvedValue(null);
    const r = await PATCH(patchReq({ id: 'b-other', action: 'update', model: 'gpt-4o' }));
    expect(r.status).toBe(404);
    expect(getDecryptedBYOKKeyById).not.toHaveBeenCalled();
  });

  it('update model=null → 清空（回到平台默认）', async () => {
    vi.mocked(updateBYOKKeyMeta).mockResolvedValue({ provider: 'openai', keyHint: 'xxxx' });
    const r = await PATCH(patchReq({ id: 'b1', action: 'update', model: null }));
    expect(r.status).toBe(200);
    expect(vi.mocked(updateBYOKKeyMeta).mock.calls[0][0]).toMatchObject({ model: null });
  });

  it('update model 形状非法 → 400', async () => {
    const r = await PATCH(patchReq({ id: 'b1', action: 'update', model: 'x y' }));
    expect(r.status).toBe(400);
    expect(updateBYOKKeyMeta).not.toHaveBeenCalled();
  });
```

测试文件的 `vi.mock('@/lib/ai-key-vault', ...)` 再加 `getDecryptedBYOKKeyById: vi.fn()` 与 `findBindingForUser: vi.fn()`，并相应 import。

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm exec vitest run src/__tests__/api/ai-keys-route.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

`route.ts`：
- import：`import { saveBYOKKey, deleteBYOKKey, updateBYOKKeyMeta, reorderBYOKKeys, updateBYOKAvailableModels } from '@/lib/ai-key-vault';`、`import { fetchByokModels, MODEL_ID_RE } from '@/lib/byok-models';`。
- 新增顶层助手：

```ts
/**
 * model 字段规范化（与 aster-api ByokEnvelopeParser.normalizeModel 同语义）：
 * undefined=未提供（PATCH 不改）；null / 空串 / 纯空白 = 清空（平台默认）；
 * 否则 trim 后须符合 MODEL_ID_RE。返回 { ok: false } 表示形状非法。
 */
function parseModelField(raw: unknown): { ok: true; model: string | null | undefined } | { ok: false } {
  if (raw === undefined) return { ok: true, model: undefined };
  if (raw === null) return { ok: true, model: null };
  if (typeof raw !== 'string') return { ok: false };
  const m = raw.trim();
  if (m === '') return { ok: true, model: null };
  return MODEL_ID_RE.test(m) ? { ok: true, model: m } : { ok: false };
}

/**
 * 填了 model 时经 aster-api 查 provider 列表（ADR 0048 §3.4）：列表存在且不含该 model → 拒绝；
 * 查询失败（provider 抖动）或网关不提供列表 → 放行。返回 null 表示拒绝（调用方回 400）。
 */
async function checkModelAgainstProvider(
  userId: string,
  creds: { provider: string; apiKey: string; baseUrl: string | null },
  model: string | null,
): Promise<{ result: Awaited<ReturnType<typeof fetchByokModels>> } | { rejected: string[] }> {
  const result = await fetchByokModels(userId, creds);
  if (result.ok && result.models && model && !result.models.includes(model)) {
    return { rejected: result.models };
  }
  return { result };
}
```

- GET：`columns` 加 `model: true, availableModels: true, modelsCheckedAt: true`。
- POST：body 类型加 `model?: string | null;`；在 expiresAt 校验之后：

```ts
    const parsedModel = parseModelField(body.model);
    if (!parsedModel.ok) {
      return NextResponse.json({ error: 'Invalid model' }, { status: 400 });
    }
    const model = parsedModel.model ?? null;

    // 保存前经 aster-api 查一次 provider 模型列表（ADR 0048 §3.4）：填了 model 就校验存在；
    // 查询失败不阻断保存（provider 抖动不该让用户存不了 key），只是不写 availableModels。
    let models: Awaited<ReturnType<typeof fetchByokModels>> | null = null;
    if (body.provider !== 'vertex') {
      const check = await checkModelAgainstProvider(session.user.id,
        { provider: body.provider, apiKey: body.apiKey, baseUrl: providerUrl }, model);
      if ('rejected' in check) {
        return NextResponse.json({ error: 'model_not_available', available: check.rejected }, { status: 400 });
      }
      models = check.result;
    }
```

`saveBYOKKey({...})` 加 `model`；保存后：

```ts
    const modelsChecked = models?.ok === true;
    if (models?.ok) {
      await updateBYOKAvailableModels(id, { models: models.models, defaultModel: models.defaultModel });
    }
```

审计 metadata 加 `model: model ?? null`；返回 `NextResponse.json({ ok: true, id, modelsChecked, availableModels: models?.ok ? models.models : undefined })`。

- PATCH update：body 类型加 `model?: string | null;`；在 expiresAt 解析后：

```ts
    const parsedModel = parseModelField(body.model);
    if (!parsedModel.ok) {
      return NextResponse.json({ error: 'Invalid model' }, { status: 400 });
    }
    const model = parsedModel.model;
    if (tokenQuota === undefined && expiresAt === undefined && model === undefined) {
      return NextResponse.json({ error: 'Nothing to update' }, { status: 400 });
    }

    // 改 model 与新建同一校验路径：解密**本人**这条 key（归属不符 → 404，不解密），经 aster-api 查列表。
    let modelsChecked: boolean | undefined;
    if (typeof model === 'string') {
      const binding = await findBindingForUser(userId, body.id);
      if (!binding) {
        return NextResponse.json({ error: 'AI key not found' }, { status: 404 });
      }
      if (binding.provider !== 'vertex') {
        const apiKey = await getDecryptedBYOKKeyById(userId, body.id);
        if (!apiKey) {
          return NextResponse.json({ error: 'AI key not found' }, { status: 404 });
        }
        const check = await checkModelAgainstProvider(userId,
          { provider: binding.provider, apiKey, baseUrl: binding.providerUrl }, model);
        if ('rejected' in check) {
          return NextResponse.json({ error: 'model_not_available', available: check.rejected }, { status: 400 });
        }
        modelsChecked = check.result.ok;
        if (check.result.ok) {
          await updateBYOKAvailableModels(body.id, { models: check.result.models, defaultModel: check.result.defaultModel });
        }
      }
    }
    const updated = await updateBYOKKeyMeta({ userId, bindingId: body.id, tokenQuota, expiresAt, model });
```

审计 metadata 加 `...(model !== undefined ? { model } : {})`；成功响应 `NextResponse.json({ ok: true, ...(modelsChecked !== undefined ? { modelsChecked } : {}) })`。

`src/lib/ai-key-vault.ts` 新增（Task 6 的 vault 提交里一并落地）：

```ts
/** 按 (id, userId) 取 binding 的 provider / providerUrl（不解密），归属不符返回 null。 */
export async function findBindingForUser(
  userId: string,
  bindingId: string,
): Promise<{ id: string; provider: string; providerUrl: string | null } | null> {
  const rows = await db
    .select({ id: aiKeyBindings.id, provider: aiKeyBindings.provider, providerUrl: aiKeyBindings.providerUrl })
    .from(aiKeyBindings)
    .where(and(eq(aiKeyBindings.id, bindingId), eq(aiKeyBindings.userId, userId)))
    .limit(1);
  return rows[0] ? { ...rows[0], providerUrl: rows[0].providerUrl ?? null } : null;
}
```

`page.tsx`：`columns` 加三列；`initialBindings` map 加 `model: b.model ?? null, availableModels: b.availableModels ?? null, modelsCheckedAt: b.modelsCheckedAt?.toISOString() ?? null`。

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm exec vitest run src/__tests__/api/ai-keys-route.test.ts && pnpm exec tsc --noEmit -p tsconfig.json`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/app/api/user/ai-keys/route.ts "src/app/[locale]/(dashboard)/settings/ai-keys/page.tsx" src/__tests__/api/ai-keys-route.test.ts
git commit -m "feat(ai-keys): 保存/编辑可指定 model，保存时经 provider 模型列表校验（ADR 0048 §3.4）" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 9: 用量记录真实 model（SSE meta 帧 + complete 响应）

**Files:**
- Modify: `src/lib/sse-frame.ts:9,53-57,131-161`
- Modify: `src/lib/llm-sse-proxy.ts:164-198`
- Modify: `src/app/api/llm/complete/route.ts:120-144`
- Test: `src/__tests__/lib/sse-frame-billable-watcher.test.ts`, `src/__tests__/app/api/llm/complete-quota-gate.test.ts`, `src/__tests__/hooks/useSSEStream.test.ts`

**Interfaces:**
- Produces: `SSEEventType` 增 `'meta'`；`createSseBillableWatcher()` 返回对象增 `model(): string | null`（来自首个 `meta` 帧的 `data`）；`llm-sse-proxy` 与 `complete` 的占位 `recordAiUsage` 用真实 model，缺省 `'unknown'`。

- [ ] **Step 1: 失败测试**

`sse-frame-billable-watcher.test.ts` 新增：

```ts
  it('★meta 帧先于 upstream_reached 到达 → watcher.model() 为该 model，且计费判定不变', () => {
    const w = createSseBillableWatcher();
    const enc = new TextEncoder();
    expect(w.push(enc.encode('data: {"type":"meta","data":"gpt-4o"}\n\n'))).toBe(false);
    expect(w.model()).toBe('gpt-4o');
    expect(w.push(enc.encode('data: {"type":"upstream_reached"}\n\n'))).toBe(true);
    expect(w.model()).toBe('gpt-4o');
  });

  it('★旧版 api 无 meta 帧 → model() 为 null，计费判定与今天一致', () => {
    const w = createSseBillableWatcher();
    expect(w.push(new TextEncoder().encode('data: {"type":"upstream_reached"}\n\n'))).toBe(true);
    expect(w.model()).toBeNull();
  });

  it('meta 与 upstream_reached 在同一 chunk → 两者都识别', () => {
    const w = createSseBillableWatcher();
    expect(w.push(new TextEncoder().encode('data: {"type":"meta","data":"gpt-4o"}\n\ndata: {"type":"upstream_reached"}\n\n'))).toBe(true);
    expect(w.model()).toBe('gpt-4o');
  });

  it('meta 帧被 chunk 边界切开 → 拼接后仍识别', () => {
    const w = createSseBillableWatcher();
    const enc = new TextEncoder();
    expect(w.push(enc.encode('data: {"type":"me'))).toBe(false);
    expect(w.push(enc.encode('ta","data":"gpt-4o"}\n\n'))).toBe(false);
    expect(w.model()).toBe('gpt-4o');
  });
```

`complete-quota-gate.test.ts` 的 BYOK 用例（L172–192）把 fetch 桩响应改为 `'{"completion":"x","model":"gpt-4o","upstreamReached":true}'`，并断言 `mockRecordAiUsage.mock.calls[0][0]` `toMatchObject({ usedByok: true, model: 'gpt-4o' })`；再加一例响应无 `model` 字段时 `model: 'unknown'`。

`sse-proxy-quota-gate.test.ts`：桩上游流为 `meta` + `upstream_reached` 两帧，断言 `recordAiUsage` 的 `model` 为 meta 值；再加一例桩流只有 `upstream_reached`（旧版 api），断言仍记账且 `model: 'unknown'`。

`src/__tests__/lib/ai-quota.test.ts` 新增（沿用该文件对 `db.insert(...).onConflictDoUpdate` 的 mock 方式）：

```ts
  it('★占位记录带真实 model：占位（无 token）不覆盖已有 model；带 token 的回填以回填值为准', async () => {
    // 占位：values.model 是真实 model，set.model 必须是「保留已有列」的 SQL 表达式（非字符串字面量）
    await recordAiUsage({ userId: 'u', callKind: 'complete', model: 'gpt-4o', promptTokens: 0, completionTokens: 0, usedByok: true, requestId: 'r1', status: 'success' });
    const placeholder = capturedUpsert(); // { values, set }：最近一次 .values() / .onConflictDoUpdate({ set })
    expect(placeholder.values.model).toBe('gpt-4o');
    expect(typeof placeholder.set.model).not.toBe('string');
    // 回填（带 token）：model 以回填值为准——新版 api 回填的是实际发出 model（与占位一致）；
    // 旧版 api 回填的是其请求/平台默认 model，这是既有 #185 契约（token 为权威），本用例把该规则锁定
    await recordAiUsage({ userId: 'u', callKind: 'complete', model: 'gpt-4o-mini', promptTokens: 10, completionTokens: 5, usedByok: true, requestId: 'r1', status: 'success' });
    expect(capturedUpsert().set.model).toBe('gpt-4o-mini');
  });
```

（`capturedUpsert()` 读取 mock 的最近一次 `.values()` 与 `.onConflictDoUpdate({ set })` 参数；按该测试文件已有的 db 链式 mock 实现。）

`useSSEStream.test.ts` 加一例：收到 `{"type":"meta","data":"gpt-4o"}` 帧不产生内容、不报错（断言 hook 输出文本为空且无 error）。

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm exec vitest run src/__tests__/lib/sse-frame-billable-watcher.test.ts src/__tests__/app/api/llm src/__tests__/hooks/useSSEStream.test.ts`
Expected: FAIL（`model` 不是函数 / 记账 model 为 `unknown`）。

- [ ] **Step 3: 实现**

`sse-frame.ts`：`SSEEventType` 加 `'meta'`；`createSseBillableWatcher` 内加 `let model: string | null = null;`，`scan` 改为：

```ts
  const scan = (frames: string[]) => {
    for (const frame of frames) {
      const ev = parseSSEFrame(frame);
      // meta 帧（ADR 0048）：aster-api 首帧回传实际发出的 model，先于计费帧；只取第一次。
      if (ev?.type === 'meta' && model === null && typeof ev.data === 'string' && ev.data) model = ev.data;
      if (isBillableEvent(ev)) seen = true;
    }
  };
```

返回对象加 `model: () => model`；返回类型声明加 `/** 首个 meta 帧携带的实际 model；无则 null。 */ model: () => string | null;`。`isContentEvent` 不变（meta 非内容）。

`llm-sse-proxy.ts`：`recordUsageOnce` 的 `model: 'unknown'` 改为 `model: billableWatcher.model() ?? 'unknown'`（`billableWatcher` 的声明移到 `recordUsageOnce` 之前）。

`complete/route.ts`：在 `const text = await upstreamResp.text();` 后加：

```ts
/** aster-api 响应里的实际发出 model（ADR 0048）；旧版/非 JSON → 'unknown'。 */
function responseModel(responseText: string): string {
  try {
    const parsed: unknown = JSON.parse(responseText);
    const m = typeof parsed === 'object' && parsed !== null ? (parsed as { model?: unknown }).model : undefined;
    return typeof m === 'string' && m ? m : 'unknown';
  } catch {
    return 'unknown';
  }
}
```

（放在文件底部与 `reachedUpstream` 并列）；`recordAiUsage({... model: responseModel(text), ...})`。

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm exec vitest run src/__tests__/lib/sse-frame-billable-watcher.test.ts src/__tests__/app/api/llm src/__tests__/hooks/useSSEStream.test.ts src/__tests__/api/llm-assistant-route.test.ts src/__tests__/lib/ai-quota.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/lib/sse-frame.ts src/lib/llm-sse-proxy.ts src/app/api/llm/complete/route.ts src/__tests__/lib/sse-frame-billable-watcher.test.ts src/__tests__/app/api/llm src/__tests__/hooks/useSSEStream.test.ts src/__tests__/lib/ai-quota.test.ts
git commit -m "feat(ai-usage): 用量记录改用 aster-api 回传的实际 model（SSE meta 帧 / complete.model）" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 10: healthcheck 刷新 availableModels

**Files:**
- Modify: `src/lib/ai-byok-healthcheck.ts:36-56`
- Test: `src/__tests__/lib/ai-byok-healthcheck.test.ts`（新）

**Interfaces:**
- Consumes: `fetchByokModels`、`updateBYOKAvailableModels`。
- Produces: 健康 ping 成功后，对 openai/anthropic 且 `modelsCheckedAt` 为空或早于 24h 的 binding 调 `fetchByokModels`（超时 5 s），`ok` 则写 `availableModels`/`modelsCheckedAt`；失败只记日志，不改 `lastError`；每次 cron 最多**尝试** `MODELS_REFRESH_PER_RUN = 50` 次查询（成功与失败都计入，其余留到下次）。这只限制本功能给 healthcheck 新增的耗时（≤ 50 × 5 s），原有的逐 binding ping 不变。

- [ ] **Step 1: 失败测试**

新建 `src/__tests__/lib/ai-byok-healthcheck.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { findMany, updateSet, fetchModels, updateAvail } = vi.hoisted(() => ({
  findMany: vi.fn(),
  updateSet: vi.fn(),
  fetchModels: vi.fn(),
  updateAvail: vi.fn(),
}));

vi.mock('@/lib/prisma', () => {
  const chain = { set: updateSet, where: vi.fn().mockResolvedValue(undefined) };
  updateSet.mockReturnValue(chain);
  return {
    db: { query: { aiKeyBindings: { findMany }, users: { findFirst: vi.fn() } }, update: vi.fn(() => chain) },
    aiKeyBindings: { id: 'id', active: 'active' },
    users: { id: 'id' },
  };
});
vi.mock('@/lib/ai-key-vault', () => ({
  getDecryptedBYOKKeyById: vi.fn().mockResolvedValue('sk-'.padEnd(24, 'x')),
  updateBYOKAvailableModels: updateAvail,
}));
vi.mock('@/lib/byok-models', () => ({ fetchByokModels: fetchModels }));

import { checkAllBYOKKeys } from '@/lib/ai-byok-healthcheck';

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })));
});

describe('BYOK healthcheck：刷新 availableModels（ADR 0048）', () => {
  it('健康 ping 后查询模型列表并写入', async () => {
    findMany.mockResolvedValue([{ id: 'b1', userId: 'u', provider: 'openai', providerUrl: null, lastErrorAt: null }]);
    fetchModels.mockResolvedValue({ ok: true, models: ['gpt-4o'], defaultModel: 'gpt-4o-mini' });
    const res = await checkAllBYOKKeys(vi.fn());
    expect(res[0].status).toBe('healthy');
    expect(fetchModels).toHaveBeenCalledWith('u', { provider: 'openai', apiKey: expect.any(String), baseUrl: null }, { timeoutMs: 5000 });
    expect(updateAvail).toHaveBeenCalledWith('b1', { models: ['gpt-4o'], defaultModel: 'gpt-4o-mini' });
  });

  it('24h 内查过的 binding 不再查询', async () => {
    findMany.mockResolvedValue([{ id: 'b1', userId: 'u', provider: 'openai', providerUrl: null, lastErrorAt: null, modelsCheckedAt: new Date(Date.now() - 60_000) }]);
    await checkAllBYOKKeys(vi.fn());
    expect(fetchModels).not.toHaveBeenCalled();
  });

  it('★每轮最多刷新 50 条（预算上界）', async () => {
    findMany.mockResolvedValue(Array.from({ length: 60 }, (_, i) => ({ id: `b${i}`, userId: 'u', provider: 'openai', providerUrl: null, lastErrorAt: null, modelsCheckedAt: null })));
    fetchModels.mockResolvedValue({ ok: true, models: ['m'], defaultModel: 'm' });
    await checkAllBYOKKeys(vi.fn());
    expect(fetchModels).toHaveBeenCalledTimes(50);
  });

  it('★列表查询失败 → 不写 availableModels，binding 仍 healthy', async () => {
    findMany.mockResolvedValue([{ id: 'b1', userId: 'u', provider: 'openai', providerUrl: null, lastErrorAt: null }]);
    fetchModels.mockResolvedValue({ ok: false, error: 'models_unavailable' });
    const res = await checkAllBYOKKeys(vi.fn());
    expect(res[0].status).toBe('healthy');
    expect(updateAvail).not.toHaveBeenCalled();
  });

  it('vertex 不查询列表', async () => {
    findMany.mockResolvedValue([{ id: 'b1', userId: 'u', provider: 'vertex', providerUrl: null, lastErrorAt: null }]);
    await checkAllBYOKKeys(vi.fn());
    expect(fetchModels).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm exec vitest run src/__tests__/lib/ai-byok-healthcheck.test.ts`
Expected: FAIL（未调用 `fetchByokModels`）。

- [ ] **Step 3: 实现**

`ai-byok-healthcheck.ts`：import 加 `updateBYOKAvailableModels` 与 `import { fetchByokModels } from '@/lib/byok-models';`；在健康分支 `results.push({... status: 'healthy' })` 之前加：

```ts
      // ADR 0048：顺带刷新可用模型列表（仅校验/提示用）。失败不影响健康状态，也不动 lastError。
      // 预算：只刷 24h 未查过的，每轮最多尝试 MODELS_REFRESH_PER_RUN 次（成败都算），单次 5 s 超时，
      // 新增耗时 ≤ 50 × 5 s；原有逐 binding ping 不受影响。
      if (refreshAttempts < MODELS_REFRESH_PER_RUN && await refreshAvailableModels(b, apiKey)) refreshAttempts += 1;
```

在 `checkAllBYOKKeys` 的 `for` 之前加 `let refreshAttempts = 0;`；新增常量与私有函数：

```ts
const MODELS_REFRESH_PER_RUN = 50;
const MODELS_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000;
const MODELS_FETCH_TIMEOUT_MS = 5_000;

/** 经 aster-api 查询并写入 availableModels。返回「是否真的发起了查询」供预算计数：vertex 与 24h 内已查返回 false（不计），查询失败也返回 true（计入尝试，避免故障时反复打 api）。 */
async function refreshAvailableModels(
  b: { id: string; userId: string; provider: string; providerUrl: string | null; modelsCheckedAt: Date | null },
  apiKey: string,
): Promise<boolean> {
  if (b.provider !== 'openai' && b.provider !== 'anthropic') return false;
  if (b.modelsCheckedAt && Date.now() - b.modelsCheckedAt.getTime() < MODELS_REFRESH_INTERVAL_MS) return false;
  const res = await fetchByokModels(b.userId, { provider: b.provider, apiKey, baseUrl: b.providerUrl ?? null },
    { timeoutMs: MODELS_FETCH_TIMEOUT_MS });
  if (!res.ok) {
    console.warn('[byok-healthcheck] models refresh failed for binding=%s: %s', b.id, res.error);
    return true;
  }
  await updateBYOKAvailableModels(b.id, { models: res.models, defaultModel: res.defaultModel });
  return true;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm exec vitest run src/__tests__/lib/ai-byok-healthcheck.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/lib/ai-byok-healthcheck.ts src/__tests__/lib/ai-byok-healthcheck.test.ts
git commit -m "feat(byok): healthcheck 顺带刷新 availableModels" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 11: 设置页模型字段 + i18n 四语言

**Files:**
- Modify: `src/app/[locale]/(dashboard)/settings/ai-keys/ai-keys-content.tsx`
- Modify: `src/i18n/demo-supplement.ts`（en L462–481、zh L1163–1180、de L1860–1877、hi L2555–2572 的 `aiKeysPage` 块）
- Test: `src/__tests__/components/settings/ai-keys-content.test.tsx`（新）, `src/__tests__/i18n/ai-keys-namespace-parity.test.ts`（新，复制 `teams-namespace-parity.test.ts` 的做法改为 `settings.aiKeysPage` + 本组件文件）

**Interfaces:**
- Consumes: GET/POST/PATCH 契约（Task 8）。
- Produces: 新 i18n keys（四语言同名）：`model`、`modelHint`、`modelDefault`（含 `{model}`）、`modelPlaceholder`、`modelNotAvailable`、`modelUnverified`、`thModel`、`modelInvalid`、`editModel`。

- [ ] **Step 1: 失败测试**

新建 `src/__tests__/components/settings/ai-keys-content.test.tsx`（`@testing-library/react`；用 `NextIntlClientProvider` 注入最小 messages：从 `DEMO_SUPPLEMENT.en.settings.aiKeysPage` 与 `node_modules/@aster-cloud/ui-messages/en-US.json` 的 `settings.aiKeysPage` 深合并；参考 `src/__tests__/components/policy/cedar-export-panel.test.tsx` 的 render/fetch 桩风格）：

```tsx
  it('表单含「模型（可选）」输入，占位显示平台默认', () => {
    render(<AiKeysContent initialBindings={[]} locale="en" />, { wrapper });
    const input = screen.getByLabelText('Model (optional)');
    expect(input).toBeDefined();
  });

  it('提交时把 model 发给 POST；留空发 null', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true, id: 'b1', modelsChecked: true }), { status: 200 })));
    render(<AiKeysContent initialBindings={[]} locale="en" />, { wrapper });
    fireEvent.change(screen.getByLabelText('API key'), { target: { value: 'sk-'.padEnd(24, 'x') } });
    fireEvent.change(screen.getByLabelText('Model (optional)'), { target: { value: 'gpt-4o' } });
    fireEvent.click(screen.getByText('Save key'));
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    const body = JSON.parse((vi.mocked(fetch).mock.calls[0][1] as RequestInit).body as string);
    expect(body.model).toBe('gpt-4o');
  });

  it('400 model_not_available → 显示可用列表提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'model_not_available', available: ['gpt-4o-mini'] }), { status: 400 })));
    render(<AiKeysContent initialBindings={[]} locale="en" />, { wrapper });
    fireEvent.change(screen.getByLabelText('API key'), { target: { value: 'sk-'.padEnd(24, 'x') } });
    fireEvent.change(screen.getByLabelText('Model (optional)'), { target: { value: 'gpt-9' } });
    fireEvent.click(screen.getByText('Save key'));
    expect(await screen.findByText(/not available.*gpt-4o-mini/)).toBeDefined();
  });

  it('保存成功但 modelsChecked=false → 显示「未校验」提示，且下次提交时清除', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true, id: 'b1', modelsChecked: false }), { status: 200 })));
    render(<AiKeysContent initialBindings={[]} locale="en" />, { wrapper });
    fireEvent.change(screen.getByLabelText('API key'), { target: { value: 'sk-'.padEnd(24, 'x') } });
    fireEvent.click(screen.getByText('Save key'));
    expect(await screen.findByRole('status')).toHaveTextContent(/could not be verified/);
    fireEvent.change(screen.getByLabelText('API key'), { target: { value: 'sk-'.padEnd(24, 'y') } });
    fireEvent.click(screen.getByText('Save key'));
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull());
  });

  it('已绑定列表显示 model 列：指定值或「Platform default」', () => {
    render(<AiKeysContent locale="en" initialBindings={[
      { id: 'a', provider: 'openai', keyHint: '1234', active: true, providerUrl: null, tokenQuota: null, expiresAt: null, priority: 0, usedTokensThisMonth: 0, lastUsedAt: null, lastErrorAt: null, lastError: null, createdAt: '2026-01-01T00:00:00Z', model: 'gpt-4o', availableModels: null, modelsCheckedAt: null },
      { id: 'b', provider: 'anthropic', keyHint: '5678', active: true, providerUrl: null, tokenQuota: null, expiresAt: null, priority: 0, usedTokensThisMonth: 0, lastUsedAt: null, lastErrorAt: null, lastError: null, createdAt: '2026-01-01T00:00:00Z', model: null, availableModels: { models: null, defaultModel: 'claude-sonnet-5-5' }, modelsCheckedAt: null },
    ]} />, { wrapper });
    expect(screen.getByText('gpt-4o')).toBeDefined();
    expect(screen.getByText('Platform default (claude-sonnet-5-5)')).toBeDefined();
  });
```

新建 `src/__tests__/i18n/ai-keys-namespace-parity.test.ts`：复制 `teams-namespace-parity.test.ts`，把扫描文件改为 `src/app/[locale]/(dashboard)/settings/ai-keys/ai-keys-content.tsx`、命名空间改为 `settings.aiKeysPage`，断言 en/zh/de/hi 四语言在 package JSON ⊕ `DEMO_SUPPLEMENT` 合并后都含组件用到的每个 key。

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm exec vitest run src/__tests__/components/settings/ai-keys-content.test.tsx src/__tests__/i18n/ai-keys-namespace-parity.test.ts`
Expected: FAIL（无该输入/keys）。

- [ ] **Step 3: 实现**

`demo-supplement.ts` 四个 `aiKeysPage` 块各加（en 文案；zh/de/hi 同名 key 翻译，hi 块按仓库惯例可用英文）：

```ts
        // ADR 0048：BYOK 可选模型。
        model: 'Model (optional)',
        modelHint: 'Leave blank to use the platform default for this provider. Enter a model id your key is allowed to use; it is checked against the provider when you save.',
        modelDefault: 'Platform default ({model})',
        modelDefaultUnknown: 'Platform default',
        modelPlaceholder: 'e.g. gpt-4o or claude-sonnet-5-5',
        modelNotAvailable: 'This model is not available for your key. Available: {available}',
        modelUnverified: 'Saved, but the model list could not be verified right now; the model will be used as entered.',
        modelInvalid: 'Model id may only contain letters, digits and . _ : / - (max 128).',
        thModel: 'Model',
```

zh：`model: '模型（可选）'`、`modelHint: '留空则使用平台为该 provider 配置的默认模型；填写你的 key 允许使用的模型 id，保存时会向 provider 校验。'`、`modelDefault: '平台默认（{model}）'`、`modelDefaultUnknown: '平台默认'`、`modelPlaceholder: '例如 gpt-4o 或 claude-sonnet-5-5'`、`modelNotAvailable: '该模型对你的 key 不可用。可用：{available}'`、`modelUnverified: '已保存，但暂时无法校验模型列表；将按所填模型调用。'`、`modelInvalid: '模型 id 只能包含字母、数字与 . _ : / -（最长 128）。'`、`thModel: '模型'`。
de：`model: 'Modell (optional)'`、`modelHint: 'Leer lassen, um den Plattform-Standard dieses Providers zu verwenden. Eine Modell-ID eintragen, die Ihr Key nutzen darf; sie wird beim Speichern beim Provider geprüft.'`、`modelDefault: 'Plattform-Standard ({model})'`、`modelDefaultUnknown: 'Plattform-Standard'`、`modelPlaceholder: 'z. B. gpt-4o oder claude-sonnet-5-5'`、`modelNotAvailable: 'Dieses Modell ist für Ihren Key nicht verfügbar. Verfügbar: {available}'`、`modelUnverified: 'Gespeichert, aber die Modellliste konnte gerade nicht geprüft werden; das Modell wird wie eingegeben verwendet.'`、`modelInvalid: 'Die Modell-ID darf nur Buchstaben, Ziffern und . _ : / - enthalten (max. 128).'`、`thModel: 'Modell'`。

`ai-keys-content.tsx`：
- `BYOKBinding` 加 `model: string | null; availableModels: { models: string[] | null; defaultModel: string } | null; modelsCheckedAt: string | null;`。
- 状态：`const [model, setModel] = useState('');`、`const [notice, setNotice] = useState<string | null>(null);`（保存成功但未校验时的提示；每次提交开始时与 error 一起清空）。
- 表单在 provider `<Select>` 块之后插入：

```tsx
          <div className="flex flex-col gap-2">
            <Label htmlFor="ai-keys-model">{t('model')}</Label>
            <Input
              id="ai-keys-model"
              value={model}
              onChange={(e) => setModel(e.target.value)}
              placeholder={t('modelPlaceholder')}
            />
            <p className="text-xs text-fg-muted">{t('modelHint')}</p>
          </div>
```

（不做跨 binding 的「已知模型」下拉：其它 key 查到的列表不能证明当前 key 可用；保存时的 400 会把该 key 的真实可用列表回显。）

- `handleSubmit`：开头 `setNotice(null)`；本地校验 `if (model.trim() && !/^[A-Za-z0-9._:/-]{1,128}$/.test(model.trim())) { setError(t('modelInvalid')); return; }`；POST body 加 `model: model.trim() || null`；失败分支：若 `data.error === 'model_not_available'` → `setError(t('modelNotAvailable', { available: (data.available as string[]).join(', ') }))`；成功后 `const data = await r.json(); setModel(''); if (data.modelsChecked === false) setNotice(t('modelUnverified'));`，并在表单下方渲染 `{notice && <p className="text-sm text-amber-700" role="status">{notice}</p>}`。
- 表头在 `thKeyHint` 之后加 `<th className="pb-2 text-left">{t('thModel')}</th>`；行内对应单元格：

```tsx
                    <td className="py-3 font-mono text-xs">
                      {editingId === b.id ? (
                        <Input value={editModel} onChange={(e) => setEditModel(e.target.value)} placeholder={t('modelPlaceholder')} aria-label={t('model')} />
                      ) : b.model ? b.model : (
                        <span className="font-sans text-fg-muted">
                          {b.availableModels?.defaultModel ? t('modelDefault', { model: b.availableModels.defaultModel }) : t('modelDefaultUnknown')}
                        </span>
                      )}
                    </td>
```

- 行内编辑：状态 `const [editModel, setEditModel] = useState('');`，`startEdit` 时 `setEditModel(b.model ?? '')`，`saveEdit` 的 PATCH body 加 `model: editModel.trim() || null`（与现有 `action: 'update'` 一起）。

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm exec vitest run src/__tests__/components/settings/ai-keys-content.test.tsx src/__tests__/i18n && pnpm run check:message-keys && pnpm exec eslint "src/app/[locale]/(dashboard)/settings/ai-keys/ai-keys-content.tsx" src/i18n/demo-supplement.ts && pnpm exec tsc --noEmit -p tsconfig.json`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add "src/app/[locale]/(dashboard)/settings/ai-keys/ai-keys-content.tsx" src/i18n/demo-supplement.ts src/__tests__/components/settings/ai-keys-content.test.tsx src/__tests__/i18n/ai-keys-namespace-parity.test.ts
git commit -m "feat(settings): AI 密钥页可选模型字段与模型列（四语言文案）" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

### Task 12: cloud 全量门禁 + 本地栈验收 + ADR §9.2 写回

- [ ] **Step 1: 全量门禁**

Run（worktree 内）: `pnpm exec tsc --noEmit -p tsconfig.json && pnpm exec eslint . && pnpm run check:message-keys && pnpm test:run`
Expected: 全绿（基线 13 skipped）。

- [ ] **Step 2: 本地栈验收（按 memory 的 production-build 配方，api 用 Task 5 后的镜像重建）**

1. 重建 aster-api 镜像并替换 `aster-api` 容器；对 sdd-pg 与 aster-pg 的 `aster_cloud` 应用 migration 0055（`pnpm db:migrate`，在容器内的启动脚本已含）。
2. 以 m-free 在 `/en/settings/ai-keys` 录入 openai key 指定 `gpt-4o-mini`（真实 key 从 `/Users/rpang/.aster-stack/` 现有 BYOK 夹具取，若无则用 `OPENAI_API_KEY`/用户提供；没有可用 key 时用自定义 providerUrl 指向本地 OpenAI 兼容桩并在 allowlist 加 host），再录入 anthropic key 不指定 model；分别置顶后触发编辑器补全，核对 `aster-api` 日志 `LLM 流式请求: ... model=` 与 `aiUsageRecords.model`。
3. 录入不存在的 model → 400 `model_not_available`；指定 model 后 provider 拒绝 → `provider_rejected_model`。
4. Playwright local-stack 全量 + API smoke 作为回归。

把结果写入 `docs/adr/0048-byok-model-selection.md` §9.2（命令、输出要点、日期）。

- [ ] **Step 3: 提交写回**

```bash
git -C /Users/rpang/IdeaProjects/aster-api add docs/adr/0048-byok-model-selection.md
git -C /Users/rpang/IdeaProjects/aster-api commit -m "docs(adr): ADR 0048 §9.2 本地栈验收记录" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## 自检记录

- 覆盖：ADR §3.1（Task 6）、§3.2（Task 1/7）、§3.3（Task 1–4）、§3.4（Task 8）、§3.5（Task 11）、§4 优先级不变（Task 7 只透传 model，不改选择）、§5（Task 3/9/8 审计）、§6（Task 3/4/8）、§7（各 Task 测试 + Task 5/12）。
- 名称一致性：`LlmModelPolicy.wireModel`、`ByokOverride.hasModel`、`LlmRuntimeOptions.hasModel/model`、`fetchByokModels`、`MODEL_ID_RE`、`updateBYOKAvailableModels`、`createSseBillableWatcher().model()` 在各 Task 中拼写一致。
- 与 ADR 的偏差（Task 5 写回）：端点为 `POST /api/v1/ai/models`；PATCH `update` 携带 `model` 替代 `setModel`（同样经列表校验）；审计沿用 `ai-key.*`；`ModelsResponse` 无 `supported`。
- Codex 计划审查（76 分退回）的修订：流式 400 改由 client 按 options 直接发 `provider_rejected_model`（Task 3 §4a）；模型 id 形状 + 条数 + 响应体三重上界（Task 4/6）；跨版本兼容与占位/回填测试（Task 9）；PATCH 重校验 + 空白语义统一（Task 8）；healthcheck 预算（Task 10）；资源级 `/models` 测试（Task 4）；砍掉 `supported`、`knownModels`，`notice` 提交时清空（Task 4/11）。
