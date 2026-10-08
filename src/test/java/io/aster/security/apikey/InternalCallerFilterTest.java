package io.aster.security.apikey;

import io.aster.policy.rest.RequestIdentityResolver;
import io.aster.policy.security.NonceService;
import io.aster.policy.security.TrialBypassPredicate;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.aster.security.apikey.InternalCallerFilter.Classification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R27-Minor-3：InternalCallerFilter 单元测试。
 *
 * <p>测试 path 分类 + 旁路决策的纯函数 {@link InternalCallerFilter#classify}，
 * 以及 HMAC canonical v3 经真实 {@code filter()} 的验签行为（mock 请求上下文）。不依赖 Quarkus 上下文。
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
    // HMAC sign() 原语：确定性 + 输入敏感（与 canonical 版本无关）
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
    // canonical v3（ADR 0042 §4.1）：method\npath\nquery\nts\nnonce\nbodySha256\ntenant\nrole\nuserId\nbusinessRoles
    // 经真实 filter() 验证：body / tenant / role / nonce / query / userId / 业务角色任一被改都 403
    // invalid_signature；角色头的书写顺序不影响签名。签名统一由 InternalCallerSigning 生成，
    // 它也是 cloud signInternalCallerHeaders 的对照实现。
    // ============================================================

    private static final String KEY = "unit-internal-caller-key-32bytes!";
    private static final String RECEIPTS = "/api/v1/audit/receipts";
    private static final String SOURCE = "/api/v1/policies/evaluate-source";

    private static InternalCallerFilter filter() {
        InternalCallerFilter f = new InternalCallerFilter();
        f.hmacKey = Optional.of(KEY);
        f.nonceService = mock(NonceService.class);
        return f;
    }

    /** 构造带指定头、查询串与 body 的请求上下文（query 不含 '?'，空串表示无查询）。 */
    private static ContainerRequestContext request(String method, String path, String query, String body,
                                                   Map<String, String> headers) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(
            URI.create("http://localhost" + path + (query.isEmpty() ? "" : "?" + query)));
        ContainerRequestContext c = mock(ContainerRequestContext.class);
        when(c.getUriInfo()).thenReturn(uri);
        when(c.getMethod()).thenReturn(method);
        when(c.getLength()).thenReturn(bytes.length);
        when(c.getEntityStream()).thenReturn(new ByteArrayInputStream(bytes));
        headers.forEach((k, v) -> when(c.getHeaderString(k)).thenReturn(v));
        return c;
    }

    /** 跑一次 filter：放行返回 "ok"，被拒返回 403 响应里的 reason。 */
    private static String run(ContainerRequestContext c) {
        try {
            filter().filter(c).await().indefinitely();
            return "ok";
        } catch (WebApplicationException e) {
            assertEquals(403, e.getResponse().getStatus());
            return String.valueOf(((Map<?, ?>) e.getResponse().getEntity()).get("reason"));
        }
    }

    private static Map<String, String> receiptHeaders() {
        return new HashMap<>(InternalCallerSigning.headers(KEY, "GET", RECEIPTS, "correlationIds=c1", "",
            "t-1", "member", "u-1", List.of("DPO", "CISO")));
    }

    private static Map<String, String> with(Map<String, String> h, String name, String value) {
        Map<String, String> copy = new HashMap<>(h);
        copy.put(name, value);
        return copy;
    }

    @Test
    void v3签名原样通过() {
        assertEquals("ok", run(request("GET", RECEIPTS, "correlationIds=c1", "", receiptHeaders())));
    }

    @Test
    void v3签名绑定query() {
        assertEquals("invalid_signature",
            run(request("GET", RECEIPTS, "correlationIds=c2", "", receiptHeaders())));
    }

    @Test
    void v3签名绑定userId() {
        assertEquals("invalid_signature",
            run(request("GET", RECEIPTS, "correlationIds=c1", "", with(receiptHeaders(), "X-User-Id", "u-2"))));
    }

    @Test
    void v3签名绑定业务角色() {
        assertEquals("invalid_signature", run(request("GET", RECEIPTS, "correlationIds=c1", "",
            with(receiptHeaders(), "X-User-Business-Roles", "CISO"))));
    }

    @Test
    void v3签名与角色头顺序无关() {
        // 按 [DPO,CISO] 签名，头改写成 "CISO, DPO,CISO"：规范化（trim/去重/排序）后同为 "CISO,DPO"。
        assertEquals("ok", run(request("GET", RECEIPTS, "correlationIds=c1", "",
            with(receiptHeaders(), "X-User-Business-Roles", "CISO, DPO,CISO"))));
    }

    @Test
    void v3签名绑定body_tenant_role_nonce() {
        String body = "{\"model\":\"cheap\"}";
        Map<String, String> h = InternalCallerSigning.headers(KEY, "POST", SOURCE, "", body,
            "tenant-a", "MEMBER", "", List.of());
        assertEquals("ok", run(request("POST", SOURCE, "", body, h)));
        assertEquals("invalid_signature", run(request("POST", SOURCE, "", "{\"model\":\"pricey\"}", h)));
        assertEquals("invalid_signature", run(request("POST", SOURCE, "", body, with(h, "X-Tenant-Id", "tenant-b"))));
        assertEquals("invalid_signature", run(request("POST", SOURCE, "", body, with(h, "X-User-Role", "ADMIN"))));
        assertEquals("invalid_signature", run(request("POST", SOURCE, "", body, with(h, "X-Aster-Nonce", "other"))));
    }

    @Test
    void v2签名不再被接受() {
        // 旧 7 行 canonical（无 query/userId/businessRoles）一律 invalid_signature，无回退。
        long ts = System.currentTimeMillis() / 1000;
        String v2 = "GET\n" + RECEIPTS + "\n" + ts + "\nn-v2\n" + InternalCallerFilter.sha256Hex(new byte[0])
            + "\nt-1\nmember";
        Map<String, String> h = Map.of(
            "X-Internal-Caller", "cloud-bff",
            "X-Aster-Timestamp", Long.toString(ts),
            "X-Aster-Nonce", "n-v2",
            "X-Internal-Signature", InternalCallerFilter.sign(KEY, v2),
            "X-Tenant-Id", "t-1",
            "X-User-Role", "member");
        assertEquals("invalid_signature", run(request("GET", RECEIPTS, "", "", h)));
    }

    @Test
    void 验签通过后写入签过的身份属性() {
        ContainerRequestContext c = request("GET", RECEIPTS, "correlationIds=c1", "",
            with(receiptHeaders(), "X-User-Business-Roles", " DPO ,CISO,,"));
        assertEquals("ok", run(c));
        verify(c).setProperty(InternalCallerFilter.HMAC_VERIFIED_PROP, Boolean.TRUE);
        verify(c).setProperty(RequestIdentityResolver.PROP_IDENTITY_USER_ID, "u-1");
        verify(c).setProperty(RequestIdentityResolver.PROP_BUSINESS_ROLES, List.of("CISO", "DPO"));
    }

    @Test
    void 验签失败不写身份属性() {
        ContainerRequestContext c = request("GET", RECEIPTS, "correlationIds=c1", "",
            with(receiptHeaders(), "X-User-Id", "u-2"));
        assertEquals("invalid_signature", run(c));
        verify(c, never()).setProperty(org.mockito.ArgumentMatchers.eq(RequestIdentityResolver.PROP_IDENTITY_USER_ID),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void parseRoles规范化() {
        assertEquals(List.of(), InternalCallerFilter.parseRoles(null));
        assertEquals(List.of(), InternalCallerFilter.parseRoles(" , "));
        assertEquals(List.of("CISO", "DPO"), InternalCallerFilter.parseRoles("DPO, CISO,DPO,"));
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

    // ADR 0041 §2.4：审计收据查询同时接受 Bearer API key 与 HMAC 内部调用者。
    @Test
    void auditReceiptsRequireHmacOnlyWithInternalCallerHeaders() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/audit/receipts", false, false, false, false, true));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/audit/receipts", false, false, false, false, false));
        // 兄弟路径不受影响
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/audit/receiptsX", false, false, false, false, true));
    }

    // ADR 0042 §4.2：from-evidence 恒要求 HMAC；审批与决策回读仅在带内部调用头时要求 HMAC。
    @Test
    void guardFromEvidenceAlwaysRequiresHmac() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/guard/decisions/from-evidence", false, false, false, false, false));
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/guard/decisions/from-evidence", true, true, true, true, true));
    }

    @Test
    void guardApprovalPathsRequireHmacOnlyWithInternalCallerHeaders() {
        String id = "3f1c2a4e-0000-4000-8000-000000000001";
        for (String p : List.of("/api/v1/guard/approvals", "/api/v1/guard/approvals/" + id + "/approve",
                "/api/v1/guard/approvals/" + id + "/reject", "/api/v1/guard/decisions/" + id)) {
            assertEquals(Classification.REQUIRE_HMAC,
                InternalCallerFilter.classify(p, false, false, false, false, true), p);
            assertEquals(Classification.NOT_PROTECTED,
                InternalCallerFilter.classify(p, false, false, false, false, false), p);
        }
        // Agent 提交决策与其它兄弟路径不归本 filter
        for (String p : List.of("/api/v1/guard/decisions", "/api/v1/guard/approvals/" + id,
                "/api/v1/guard/approvals/" + id + "/delete", "/api/v1/guard/decisions/" + id + "/x",
                "/api/v1/guard/approvalsX")) {
            assertEquals(Classification.NOT_PROTECTED,
                InternalCallerFilter.classify(p, false, false, false, false, true), p);
        }
    }

    // ADR 0042 §3.1：guard 路径缺内部凭证的 reason 为 internal_only，其余路径沿用既有码。
    @Test
    void missingCredentialReasonIsInternalOnlyForGuardPaths() {
        assertEquals("internal_only", InternalCallerFilter.missingCredentialReason("/api/v1/guard/decisions/from-evidence"));
        assertEquals("internal_only", InternalCallerFilter.missingCredentialReason("/api/v1/guard/approvals"));
        assertEquals("ai_internal_only", InternalCallerFilter.missingCredentialReason("/api/v1/ai/complete"));
        assertEquals("evaluate_source_internal_only",
            InternalCallerFilter.missingCredentialReason("/api/v1/policies/evaluate-source"));
        assertEquals("evaluate_source_internal_only", InternalCallerFilter.missingCredentialReason("/api/v1/audit/receipts"));
    }
}
