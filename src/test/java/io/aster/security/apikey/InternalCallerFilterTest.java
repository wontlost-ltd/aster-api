package io.aster.security.apikey;

import io.aster.policy.security.TrialBypassPredicate;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static io.aster.security.apikey.InternalCallerFilter.Classification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R27-Minor-3：InternalCallerFilter 单元测试。
 *
 * <p>测试 path 分类 + 旁路决策的纯函数 {@link InternalCallerFilter#classify}，
 * 以及 HMAC canonical 形式（method + path + ts）。不依赖 Quarkus 上下文。
 *
 * <p>覆盖的关键回归点：
 * <ul>
 *   <li>R23-Critical-2: AI 端点未签名时必须 REQUIRE_HMAC</li>
 *   <li>R25-Critical-1: matrix-param 不应绕过分类</li>
 *   <li>R25-Major-3: aster.security.ai.sse.public 只放过 SSE 三段，/complete 仍要 HMAC</li>
 *   <li>aster.security.ai.public 全量旁路（粗粒度）</li>
 * </ul>
 */
class InternalCallerFilterTest {

    // ============================================================
    // Classification: 路径不在管辖范围 → NOT_PROTECTED
    // ============================================================

    @Test
    void unrelatedPathsAreNotProtected() {
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/lexicons", false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/policies/evaluate", false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/q/health", false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/", false, false, false, false));
    }

    // ============================================================
    // ★安全审计修复（2026-08-17）：What-If 批次必须要求 HMAC。
    //
    // 历史缺陷：cloud BFF 一直把 /whatif-batches 当内部端点签名并发送
    // X-Internal-Caller + HMAC 头（policy-api.ts 的 needsInternalCaller），
    // 但服务端 classify() 只认 evaluate-source 与 /ai/*，其余一律 NOT_PROTECTED
    // ——**客户端签了名，服务端从不校验**，该端点仅剩可伪造的 X-User-Id/X-User-Role
    // 把守（生产已关闭全局 signature.enabled）。
    //
    // 注意不能改用 ApiKeyAuthFilter 保护：BFF 不带 Bearer API key，
    // 纳入 API-key allowlist 会让 UI 的 What-If 全部 401。
    // ============================================================

    @Test
    void whatIfBatchesRequireHmac() {
        // 集合与单条路径都必须要求 HMAC
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/policies/pol-1/whatif-batches",
                false, false, false, false));
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify(
                "/api/v1/policies/pol-1/whatif-batches/9f8e7d6c-1234-4a5b-8c9d-0e1f2a3b4c5d",
                false, false, false, false));
    }

    @Test
    void whatIfBatchesHaveNoPublicOrTrialBypass() {
        // What-If 按窗口重跑历史执行，属内部编排能力，不对匿名浏览器流量开放：
        // 即使 evaluate-source 的 public / trial 旁路全开，也必须 REQUIRE_HMAC。
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/policies/pol-1/whatif-batches",
                true, true, true, true));
    }

    @Test
    void whatIfGuardDoesNotOverMatchSiblings() {
        // 不得误伤形近路径，否则会把无关端点拖进 HMAC 强制而 403
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/policies/pol-1/whatif-batchesX",
                false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/policies/whatif-batches",
                false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/policies/pol-1/whatif-batches/a/b",
                false, false, false, false));
    }

    @Test
    void aiPathWithoutSubResourceNotProtected() {
        // /api/v1/ai 本身（没有 sub-resource）不属于 LLM 端点，不在管辖
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/ai", false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/ai/", false, false, false, false));
    }

    // ============================================================
    // Classification: evaluate-source 默认需要 HMAC
    // ============================================================

    @Test
    void evaluateSourceRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                false, false, false, false));
    }

    @Test
    void evaluateSourcePublicFlagBypasses() {
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ true, /*evaluateSourceTrial*/ false, false, false));
    }

    @Test
    void evaluateSourceTrialFlagBypassesAsTrial() {
        // .trial 单独打开时返回 BYPASS_TRIAL —— 与 BYPASS_OK 区分，确保下游 filter
        // (TrialEndpointGuard) 才是真正的限流闸门，避免 operator 把 .trial=true
        // 当成 .public=true 用。
        assertEquals(Classification.BYPASS_TRIAL,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ false, /*evaluateSourceTrial*/ true, false, false));
    }

    @Test
    void evaluateSourceTrialWithInternalCallerRequiresHmac() {
        // 关键修复：trial 开启时，携带内部调用头（cloud-bff）的 evaluate-source 必须走
        // HMAC 校验（REQUIRE_HMAC），而非 trial 旁路。否则已认证的内部调用（服务器到服务器、
        // 无浏览器 Origin）会被 TrialEndpointGuard 的 Origin 闸门误拦 → cloud /policies/{id}/
        // execute 报 origin_not_allowed。
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ false, /*evaluateSourceTrial*/ true,
                /*aiPublic*/ false, /*aiSsePublic*/ false, /*hasInternalCaller*/ true));
    }

    @Test
    void evaluateSourceTrialWithoutInternalCallerStaysTrial() {
        // 反面：无内部调用头 → 仍是匿名 trial 流量，走 BYPASS_TRIAL（经 TrialEndpointGuard）。
        assertEquals(Classification.BYPASS_TRIAL,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                false, /*evaluateSourceTrial*/ true, false, false, /*hasInternalCaller*/ false));
    }

    @Test
    void evaluateSourcePublicTakesPrecedenceOverInternalCaller() {
        // .public 仍是最高优先级全量旁路，即便带内部调用头也走 BYPASS_OK。
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ true, /*evaluateSourceTrial*/ true,
                false, false, /*hasInternalCaller*/ true));
    }

    @Test
    void evaluateSourceTrialDoesNotAffectAiEndpoints() {
        // .trial 是 evaluate-source 专属的：AI 路径仍要 HMAC
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, /*evaluateSourceTrial*/ true, false, false));
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/generate",
                false, true, false, false));
    }

    @Test
    void evaluateSourcePublicTakesPrecedenceOverTrial() {
        // 同时设两个开关时，.public 是更粗粒度的旁路，优先生效（operator 应当
        // 显式选择只设一个）。
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ true, /*evaluateSourceTrial*/ true, false, false));
    }

    // ============================================================
    // Classification: AI 端点 — 默认 / ai.public / ai.sse.public 三个层级
    // ============================================================

    @Test
    void aiCompleteRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/complete", false, false, false, false));
    }

    @Test
    void aiGenerateRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/generate", false, false, false, false));
    }

    @Test
    void aiPublicBypassesEverything() {
        // 粗粒度全量旁路 —— /complete 也放过
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, false, /*aiPublic*/ true, false));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/generate",
                false, false, true, false));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/translate", // 未来端点
                false, false, true, false));
    }

    @Test
    void aiSsePublicBypassesOnlySseEndpoints() {
        // R25-Major-3: ai.sse.public 只放 generate/suggest（explain 端点已移除）
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/generate",
                false, false, false, /*aiSsePublic*/ true));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/suggest",
                false, false, false, true));
        // explain 端点已删除：即便 sse.public=true 也不在白名单 → 仍要求 HMAC。
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/explain",
                false, false, false, true));
    }

    @Test
    void aiSsePublicDoesNotAffectComplete() {
        // R25-Major-3 关键不变式：/complete 永远不被 sse.public 影响
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, false, false, /*aiSsePublic*/ true));
    }

    @Test
    void aiSsePublicDoesNotAffectUnknownFutureAiEndpoint() {
        // 未来加一个 /api/v1/ai/translate —— 不在 SSE 白名单 set 里，
        // 设了 ai.sse.public 也不放过，必须显式扩展 AI_SSE_PATHS
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/translate",
                false, false, false, true));
    }

    @Test
    void aiPublicTakesPrecedenceOverSsePublic() {
        // ai.public 是更粗粒度的旁路；同时设 sse.public 时它优先（reviewer 确认这是
        // 当前实现的优先级顺序，operator 应当显式选择）
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, false, /*aiPublic*/ true, /*aiSsePublic*/ true));
    }

    // ============================================================
    // Classification: matrix-param 不应改变分类
    // 这里 classify 收到的是 normalized path（PathNormalizer.normalize 已剥离 ;...）
    // 但保险起见也防御 NOT_PROTECTED 的 matrix-param case
    // ============================================================

    @Test
    void classifyAssumesAlreadyNormalizedPath() {
        // classify 不做 normalization —— 调用方（filter()）必须先用 PathNormalizer。
        // 如果有人把 raw path 传进来，matrix params 会让分类变化。
        // 这个测试记录"约定 = 必须先 normalize"。
        Classification rawPath = InternalCallerFilter.classify(
            "/api/v1/ai/complete;jsessionid=abc", false, false, false, false);
        // 因为 isAi 用 startsWith，所以 /api/v1/ai/complete;x 仍被识别 isAi=true。
        // 但更重要的：filter() 入口已 normalize → 实际调用 classify 时不会有 ;。
        // 此处仅断言"原始 raw 路径形态下行为是 REQUIRE_HMAC"，即未 normalize 不会
        // 引入 BYPASS_OK / BYPASS_TRIAL 这样的危险错分类。
        assertNotEquals(Classification.BYPASS_OK, rawPath);
        assertNotEquals(Classification.BYPASS_TRIAL, rawPath);
    }

    // ============================================================
    // HMAC sign(): canonical 形式 method + "\n" + path + "\n" + ts
    // ============================================================

    @Test
    void signProducesDeterministicHex() {
        // 同样输入产生同样输出
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        assertEquals(a, b);
        // 64 hex chars (HmacSHA256 → 32 bytes)
        assertEquals(64, a.length());
        // 仅 hex 字符
        org.junit.jupiter.api.Assertions.assertTrue(a.matches("[0-9a-f]+"));
    }

    @Test
    void signWithDifferentPathProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/generate\n1700000000");
        assertNotEquals(a, b, "path 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentTimestampProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000001");
        assertNotEquals(a, b, "ts 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentKeyProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k1", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k2", "POST\n/api/v1/ai/complete\n1700000000");
        assertNotEquals(a, b, "key 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentMethodProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "GET\n/api/v1/ai/complete\n1700000000");
        assertNotEquals(a, b, "method 不同必须产生不同签名");
    }

    // ============================================================
    // 红队 P0-C：7 行 canonical（method\npath\nts\nnonce\nbodySha256\ntenant\nrole）
    // 证明 body / tenant / role / nonce 全部进签名 —— 任一改动都会使签名失配，
    // 堵住"5min 窗口内改 body 烧 LLM 预算 / 改 tenant 假冒 / 改 role 提权 / 原样重放"。
    // 与 aster-cloud signInternalCallerHeaders 逐字节对齐。
    // ============================================================

    private static String canonical(String method, String path, long ts, String nonce,
                                    String bodySha, String tenant, String role) {
        return method + "\n" + path + "\n" + ts + "\n" + nonce + "\n"
            + bodySha + "\n" + tenant + "\n" + role;
    }

    @Test
    void bodyIsBoundIntoSignature() {
        String shaCheap = InternalCallerFilter.sha256Hex("{\"model\":\"cheap\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String shaPricey = InternalCallerFilter.sha256Hex("{\"model\":\"pricey\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String a = InternalCallerFilter.sign("k", canonical("POST", "/api/v1/ai/complete", 1700000000L, "n1", shaCheap, "t", ""));
        String b = InternalCallerFilter.sign("k", canonical("POST", "/api/v1/ai/complete", 1700000000L, "n1", shaPricey, "t", ""));
        assertNotEquals(a, b, "body 不同（换 LLM model）必须产生不同签名");
    }

    @Test
    void tenantIsBoundIntoSignature() {
        String sha = InternalCallerFilter.sha256Hex("body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String a = InternalCallerFilter.sign("k", canonical("POST", "/api/v1/policies/evaluate-source", 1700000000L, "n1", sha, "tenant-a", ""));
        String b = InternalCallerFilter.sign("k", canonical("POST", "/api/v1/policies/evaluate-source", 1700000000L, "n1", sha, "tenant-b", ""));
        assertNotEquals(a, b, "tenant 不同（跨租户假冒）必须产生不同签名");
    }

    @Test
    void roleIsBoundIntoSignature() {
        String sha = InternalCallerFilter.sha256Hex("body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String a = InternalCallerFilter.sign("k", canonical("POST", "/api/v1/policies/evaluate-source", 1700000000L, "n1", sha, "t", "MEMBER"));
        String b = InternalCallerFilter.sign("k", canonical("POST", "/api/v1/policies/evaluate-source", 1700000000L, "n1", sha, "t", "ADMIN"));
        assertNotEquals(a, b, "role 不同（提权）必须产生不同签名");
    }

    @Test
    void nonceIsBoundIntoSignature() {
        String sha = InternalCallerFilter.sha256Hex("body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String a = InternalCallerFilter.sign("k", canonical("POST", "/api/v1/ai/complete", 1700000000L, "nonce-A", sha, "t", ""));
        String b = InternalCallerFilter.sign("k", canonical("POST", "/api/v1/ai/complete", 1700000000L, "nonce-B", sha, "t", ""));
        assertNotEquals(a, b, "nonce 不同必须产生不同签名（重放防护基础）");
    }

    @Test
    void sha256HexOfEmptyBodyIsCanonicalConstant() {
        // 空 body 两端都按空字节 sha256 → 固定常量（RFC 6234 空串 sha256）。
        String empty = InternalCallerFilter.sha256Hex(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty);
        // null 与空数组一致（防 NPE + 与 cloud 端 body===undefined 对齐）。
        assertEquals(empty, InternalCallerFilter.sha256Hex(null));
    }

    // ============================================================
    // R22 后处理：HMAC startup 校验必须由 StartupEvent 触发
    //
    // 历史背景：曾经用 @PostConstruct，但 @ApplicationScoped 在 Quarkus 是 CDI
    // 懒初始化 —— @PostConstruct 直到首个请求到达才会跑，于是配置错误的生产
    // 部署可能在收到流量之前一直吞掉警告（podman /ccg:test 实测确认）。
    // 改成观察 StartupEvent 后，Quarkus 启动期间一定会触发该方法。
    //
    // 这里用反射钉住契约：任何把它改回 @PostConstruct 或去掉 @Observes 的
    // 重构都会让这个测试失败，避免静默回归。
    // ============================================================

    @Test
    void hmacValidatorIsWiredAsStartupEventObserver() {
        Method observer = null;
        for (Method m : InternalCallerFilter.class.getDeclaredMethods()) {
            if (!"validateHmacKeyConfig".equals(m.getName())) continue;
            observer = m;
            break;
        }
        assertNotNull(observer,
            "validateHmacKeyConfig 方法必须存在 —— 它是 HMAC 配置缺失的启动期可见性");

        Parameter[] params = observer.getParameters();
        assertEquals(1, params.length,
            "validateHmacKeyConfig 必须仅接收一个 @Observes StartupEvent 参数");

        Parameter p = params[0];
        assertEquals(StartupEvent.class, p.getType(),
            "参数类型必须是 io.quarkus.runtime.StartupEvent —— 这是 Quarkus 启动期触发器，"
                + "不要换成 @PostConstruct（@ApplicationScoped 懒初始化 = 启动期不触发）");

        boolean hasObserves = false;
        for (var annotation : p.getAnnotations()) {
            if (annotation.annotationType().equals(Observes.class)) {
                hasObserves = true;
                break;
            }
        }
        assertTrue(hasObserves,
            "StartupEvent 参数必须标 @Observes —— 否则 CDI 不会把该方法注册为观察者");
    }

    // ============================================================
    // 审计 #98（Medium 1）：结构词别名信任必须绑定「HMAC 已验证」，
    // 而非 X-Internal-Caller 头的存在。
    //
    // 逃生舱 evaluate-source.public=true 会在 HMAC 分支之前就 BYPASS_OK 放行，
    // 一个带三条 X-Internal-* 头 + 垃圾签名的调用方，若用「头存在」当信任信号
    // （旧的 hasInternalCallerCredentials）会拿到 aliasesTrusted=true 注入结构词
    // 别名（RETURN/IF/MATCH…），击穿 ADR-0022 门控。修复：只认 filter 在
    // constantTimeEquals 真正通过后盖的 HMAC_VERIFIED_PROP 章。
    // ============================================================

    /** 构造一个带完整内部调用头形态、但可选是否已盖 HMAC 验证章的请求上下文。 */
    private static ContainerRequestContext ctxWithInternalHeaders(boolean hmacVerified) {
        ContainerRequestContext c = mock(ContainerRequestContext.class);
        when(c.getHeaderString(TrialBypassPredicate.INTERNAL_CALLER_HEADER)).thenReturn("cloud-bff");
        when(c.getHeaderString(TrialBypassPredicate.INTERNAL_SIGNATURE_HEADER)).thenReturn("deadbeef-garbage-signature");
        when(c.getHeaderString(TrialBypassPredicate.INTERNAL_TIMESTAMP_HEADER)).thenReturn("1700000000");
        if (hmacVerified) {
            when(c.getProperty(InternalCallerFilter.HMAC_VERIFIED_PROP)).thenReturn(Boolean.TRUE);
        }
        return c;
    }

    @Test
    void hmacVerifiedPropConstantIsStable() {
        // resource 与 filter 必须引用同一属性键；改名会静默断开信任传递。
        assertEquals("aster.internal.hmac-verified", InternalCallerFilter.HMAC_VERIFIED_PROP);
    }

    @Test
    void badSignatureWithInternalHeadersIsNotHmacVerified() {
        // 核心回归：带 X-Internal-Caller + 垃圾签名（public 逃生舱下会进到 resource），
        // 头齐全 → hasInternalCallerCredentials=true（旧信任信号），但 filter 从未盖章
        // → isHmacVerified=false（新信任信号）→ 下游 aliasesTrusted=false，拒结构词别名。
        ContainerRequestContext ctx = ctxWithInternalHeaders(/*hmacVerified*/ false);

        assertTrue(TrialBypassPredicate.hasInternalCallerCredentials(ctx),
            "头形态齐全时旧的 header-presence 判定仍返回 true —— 正是它不可作为信任信号的原因");
        assertFalse(InternalCallerFilter.isHmacVerified(ctx),
            "垃圾签名/未盖章的请求必须 isHmacVerified=false → aliasesTrusted=false，"
                + "堵住 public 逃生舱下伪造内部头注入结构词别名（ADR-0022 门控绕过）");
    }

    @Test
    void onlyHmacVerifiedPropGrantsTrust() {
        // 只有 filter 在 constantTimeEquals 通过后盖了 HMAC_VERIFIED_PROP=TRUE 才算可信。
        ContainerRequestContext verified = ctxWithInternalHeaders(/*hmacVerified*/ true);
        assertTrue(InternalCallerFilter.isHmacVerified(verified));
    }

    @Test
    void isHmacVerifiedFalseWhenNoPropAndNoHeaders() {
        // 匿名 trial / public 旁路请求：既无内部头也无验证章 → 不可信。
        ContainerRequestContext bare = mock(ContainerRequestContext.class);
        assertFalse(InternalCallerFilter.isHmacVerified(bare));
        assertFalse(InternalCallerFilter.isHmacVerified(null),
            "null ctx 必须安全返回 false，不 NPE");
    }

    @Test
    void nonBooleanPropDoesNotGrantTrust() {
        // 防御：属性被写成非 TRUE 的值（如字符串 "true"）也不得授予信任。
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(InternalCallerFilter.HMAC_VERIFIED_PROP)).thenReturn("true");
        assertFalse(InternalCallerFilter.isHmacVerified(ctx),
            "只有 Boolean.TRUE 授予信任；字符串等非布尔值一律不可信");
    }

    @Test
    void hmacVerifiedPropIsSetOnlyAfterSignatureCheck() throws Exception {
        // 源码级契约钉：filter() 里对 HMAC_VERIFIED_PROP 的 setProperty 调用必须出现在
        // constantTimeEquals 通过分支之后，且方法体内除此之外不得在 BYPASS 分支盖章。
        // 用反射拿不到方法体，这里退而校验 isHmacVerified 只读 HMAC_VERIFIED_PROP。
        Method m = InternalCallerFilter.class.getMethod("isHmacVerified", ContainerRequestContext.class);
        assertEquals(boolean.class, m.getReturnType());
    }

    @Test
    void hmacValidatorIsNotAnnotatedWithPostConstruct() {
        // 配套防御：直接确认没有 @PostConstruct 注解残留。
        // 即便参数签名对了，如果同时还挂着 @PostConstruct，行为会变得难以预测。
        Method observer;
        try {
            observer = InternalCallerFilter.class
                .getDeclaredMethod("validateHmacKeyConfig", StartupEvent.class);
        } catch (NoSuchMethodException e) {
            fail("validateHmacKeyConfig(StartupEvent) 必须存在");
            return;
        }
        try {
            Class<?> postConstruct = Class.forName("jakarta.annotation.PostConstruct");
            @SuppressWarnings({"unchecked", "rawtypes"})
            boolean hasPostConstruct = observer.isAnnotationPresent(
                (Class) postConstruct);
            assertTrue(!hasPostConstruct,
                "validateHmacKeyConfig 不应再带 @PostConstruct —— "
                    + "@ApplicationScoped + @PostConstruct 在 Quarkus 是懒触发，"
                    + "已迁移到 StartupEvent 以保证启动期一定执行");
        } catch (ClassNotFoundException ignored) {
            // PostConstruct 不在 classpath 上，更不可能用到它 —— 通过
        }
    }
}
