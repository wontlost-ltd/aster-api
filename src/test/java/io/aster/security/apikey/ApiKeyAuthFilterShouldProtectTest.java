package io.aster.security.apikey;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R21-Critical-1：ApiKeyAuthFilter.shouldProtect 分类回归测试。
 *
 * <p>背景：生产 k3s 把 {@code aster.security.signature.enabled=false}，让全局
 * RequestSignatureFilter 失效以适应浏览器直连场景。突变端点（rollback / cache /
 * versions）失去全局兜底，必须由本 filter 显式覆盖。
 *
 * <p>不需要 Quarkus 上下文 —— 反射拿 private static method，直接断言路径分类。
 */
class ApiKeyAuthFilterShouldProtectTest {

    private static boolean call(String path) throws Exception {
        Method m = ApiKeyAuthFilter.class.getDeclaredMethod("shouldProtect", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, path);
    }

    @Test
    void evaluateEndpointsRequireApiKey() throws Exception {
        assertTrue(call("/api/v1/policies/evaluate"));
        assertTrue(call("/api/v1/policies/evaluate-json"));
        assertTrue(call("/api/v1/policies/evaluate/batch"));
        // 无前导斜杠也要保护
        assertTrue(call("api/v1/policies/evaluate"));
    }

    @Test
    void evaluateSourceIsExcluded() throws Exception {
        // evaluate-source 由 InternalCallerFilter 守护，不走 API key
        assertFalse(call("/api/v1/policies/evaluate-source"));
    }

    @Test
    void rollbackRequiresApiKey() throws Exception {
        // R21-Critical-1: 突变端点必须鉴权
        assertTrue(call("/api/v1/policies/aster.finance.loan.eval/rollback"));
        assertTrue(call("/api/v1/policies/some-policy-id/rollback"));
    }

    @Test
    void versionListRequiresApiKey() throws Exception {
        // R21-Critical-1: 历史查询必须鉴权
        assertTrue(call("/api/v1/policies/aster.finance.loan.eval/versions"));
    }

    @Test
    void cacheClearRequiresApiKey() throws Exception {
        // R21-Critical-1: 缓存清理是突变操作
        assertTrue(call("/api/v1/policies/cache"));
    }

    @Test
    void anonymousReadEndpointsAreNotProtected() throws Exception {
        // 元数据查询无副作用，匿名可读
        assertFalse(call("/api/v1/policies/schema"));
        assertFalse(call("/api/v1/policies/validate"));
    }

    @Test
    void unrelatedPathsAreNotProtected() throws Exception {
        assertFalse(call("/api/v1/lexicons"));
        assertFalse(call("/api/v1/admin/lexicons"));
        assertFalse(call("/q/health/live"));
        assertFalse(call("/api/internal/snapshot/full"));
        assertFalse(call("/"));
    }

    @Test
    void endpointsThatLookSimilarButAreNotPoliciesAreUnprotected() throws Exception {
        // 防御 endsWith("/rollback") 误伤兄弟路径
        assertFalse(call("/api/v1/admin/lexicons/rollback"));
    }

    // ============================================================
    // 审计/分析/指标端点：文档约定 Bearer + ADMIN，必须经 API key 鉴权
    // （历史上既不在本 filter 也不在 InternalCaller HMAC 范围，签名关闭时
    // bearer 形同虚设）。
    // ============================================================

    @Test
    void auditEndpointsRequireApiKey() throws Exception {
        assertTrue(call("/api/v1/audit/range"));
        assertTrue(call("/api/v1/audit/verify-chain"));
        assertTrue(call("/api/v1/audit/type/POLICY_EVALUATION"));
        assertTrue(call("/api/v1/audit/policy-versions/5/usage"));
        assertTrue(call("/api/v1/audit/anomalies"));
        assertTrue(call("/api/v1/audit/stats/version-usage"));
        // 无前导斜杠 + matrix-param 也要保护
        assertTrue(call("api/v1/audit/range"));
        assertTrue(call("/api/v1/audit/range;jsessionid=abc"));
    }

    @Test
    void waadrMetricsRequireApiKey() throws Exception {
        assertTrue(call("/api/v1/metrics/waadr"));
        assertTrue(call("/api/v1/metrics/waadr/weekly"));
    }

    @Test
    void auditAndWaadrSiblingPathsAreNotOverMatched() throws Exception {
        // 前缀边界防误伤：兄弟路径不应被审计/指标规则吞掉。
        assertFalse(call("/api/v1/auditXYZ"));
        assertFalse(call("/api/v1/audit-export"));
        assertFalse(call("/api/v1/metrics/waadrXYZ"));
        assertFalse(call("/api/v1/metrics/other"));
    }

    // ============================================================
    // R23-Critical-1: matrix-parameter bypass regression guards
    // ============================================================

    @Test
    void matrixParamsDoNotBypassEvaluate() throws Exception {
        // JAX-RS @Path("/evaluate") matches /evaluate;x，filter 必须 protect
        assertTrue(call("/api/v1/policies/evaluate;x"));
        assertTrue(call("/api/v1/policies/evaluate;jsessionid=abc"));
        assertTrue(call("/api/v1/policies/evaluate-json;sid=1"));
        assertTrue(call("/api/v1/policies/evaluate/batch;x"));
    }

    @Test
    void matrixParamsDoNotBypassRollback() throws Exception {
        assertTrue(call("/api/v1/policies/foo/rollback;x"));
        assertTrue(call("/api/v1/policies/aster.finance.loan/rollback;jsessionid=abc"));
    }

    @Test
    void matrixParamsDoNotBypassVersions() throws Exception {
        assertTrue(call("/api/v1/policies/foo/versions;x"));
    }

    @Test
    void matrixParamsDoNotBypassCache() throws Exception {
        assertTrue(call("/api/v1/policies/cache;x"));
        assertTrue(call("/api/v1/policies/cache;sid=1"));
    }

    @Test
    void trailingSlashDoesNotBypass() throws Exception {
        // 防尾斜杠（/rollback/）和 matrix+slash 组合
        assertTrue(call("/api/v1/policies/foo/rollback/"));
        assertTrue(call("/api/v1/policies/foo/versions/"));
        assertTrue(call("/api/v1/policies/cache/"));
    }

    @Test
    void matrixParamsOnUnrelatedPathsStillUnprotected() throws Exception {
        // 防误伤：matrix-param 不应让无关 path 突然被保护
        assertFalse(call("/api/v1/lexicons;x"));
        assertFalse(call("/q/health;x"));
    }

    // ============================================================
    // R25-Critical-1: per-segment matrix-param normalization regression
    // ============================================================

    @Test
    void intermediateSegmentMatrixParamDoesNotBypassRollback() throws Exception {
        // RESTEasy 按段剥离：foo;x → foo，路由仍命中 @Path("/{policyId}/rollback")。
        // R23 的"在第一个 ; 处截断"逻辑会把 /foo;x/rollback 砍成 /foo，丢失 /rollback。
        // R25 改成 per-segment 归一化后，必须能识别。
        assertTrue(call("/api/v1/policies/foo;x/rollback"));
        assertTrue(call("/api/v1/policies/foo;jsessionid=abc/rollback"));
        assertTrue(call("/api/v1/policies/aster.finance.loan;v=1/rollback"));
    }

    @Test
    void intermediateSegmentMatrixParamDoesNotBypassVersions() throws Exception {
        assertTrue(call("/api/v1/policies/foo;x/versions"));
    }

    @Test
    void matrixParamOnPoliciesSegmentDoesNotBypassCache() throws Exception {
        // 极端：matrix params 落在 /policies 段
        assertTrue(call("/api/v1/policies;v=1/cache"));
    }

    @Test
    void multiSegmentMatrixParamDoesNotBypass() throws Exception {
        // 每一段都加 matrix params
        assertTrue(call("/api/v1/policies;a=1/foo;b=2/rollback"));
        assertTrue(call("/api/v1/policies;a=1/foo;b=2/rollback;c=3"));
    }

    // ============================================================
    // 跨租户隔离：X-Tenant-Id 必须匹配 API key 所属租户
    // 历史漏洞：缺失该校验时，持有任意有效 key 的调用方带 X-Tenant-Id: victim
    // 即可对 victim 租户执行突变操作（跨租户越权）。
    // ============================================================

    @Test
    void noHeaderTenantIsNotMismatch() {
        // 调用方未主张租户 → 不算冲突（下游以已验证租户填充）
        assertFalse(ApiKeyAuthFilter.isTenantMismatch("tenant-a", null));
        assertFalse(ApiKeyAuthFilter.isTenantMismatch("tenant-a", ""));
        assertFalse(ApiKeyAuthFilter.isTenantMismatch("tenant-a", "   "));
    }

    @Test
    void matchingHeaderTenantIsAllowed() {
        assertFalse(ApiKeyAuthFilter.isTenantMismatch("tenant-a", "tenant-a"));
        // 前后空白应被 trim 后比较
        assertFalse(ApiKeyAuthFilter.isTenantMismatch("tenant-a", "  tenant-a  "));
    }

    @Test
    void differentHeaderTenantIsRejected() {
        // 核心安全断言：header 租户 != key 租户 → 必须拒绝
        assertTrue(ApiKeyAuthFilter.isTenantMismatch("tenant-a", "tenant-b"));
        assertTrue(ApiKeyAuthFilter.isTenantMismatch("tenant-a", "victim"));
        // 大小写敏感：租户 ID 精确匹配，TENANT-A != tenant-a
        assertTrue(ApiKeyAuthFilter.isTenantMismatch("tenant-a", "TENANT-A"));
    }

    // ============================================================
    // ★假绿修复（2026-08-17 审计）：上面三条只枚举了几个字面量，
    // 而 tenant-b / victim / TENANT-A 都**不是** tenant-a 的前缀延伸。
    // 实测：把生产实现改成 `headerTenant.trim().startsWith(verifiedTenant)`
    // 后，上述断言与 ApiKeyTenantIsolationIT 的 12 条 IT **全部仍然通过**，
    // 意味着 tenant-a 的 key 可越权访问 tenant-a-evil / tenant-abc。
    //
    // 修法：不再枚举字面量，改为断言**不变量**——
    // 「trim 后严格相等」是唯一允许放行的条件，其余一律 mismatch。
    // ============================================================

    @Test
    void anyNonEqualTenantIsRejected_invariant() {
        String verified = "tenant-a";
        // 前缀延伸（startsWith 变异的杀手）
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "tenant-a-evil"));
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "tenant-abc"));
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "tenant-a1"));
        // 被包含（endsWith / contains 变异的杀手）
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "evil-tenant-a"));
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "xtenant-a"));
        // 尾部标点（历史变异：以 '.' 结尾即跳过校验）
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "tenant-a."));
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "tenant-a/"));
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "tenant-a\\"));
        // 大小写变体（equalsIgnoreCase 变异的杀手）
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "Tenant-A"));
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "TENANT-a"));
        // 内嵌空白不等于 trim
        assertTrue(ApiKeyAuthFilter.isTenantMismatch(verified, "tenant -a"));
    }

    @Test
    void onlyExactTrimmedEqualityPasses_invariant() {
        // 穷举式不变量：对一批候选值，当且仅当 trim 后与已验证租户相等才放行。
        String verified = "tenant-a";
        String[] candidates = {
            "tenant-a", "  tenant-a  ", "\ttenant-a\n",           // 应放行
            "tenant-b", "tenant-a-evil", "tenant-abc", "TENANT-A", // 应拒绝
            "tenant-a.", "evil-tenant-a", "tenant_a", "tenant-a "
        };
        for (String c : candidates) {
            boolean expectedMismatch = !c.trim().equals(verified);
            assertEquals(expectedMismatch, ApiKeyAuthFilter.isTenantMismatch(verified, c),
                "租户比较必须是 trim 后严格相等；候选值=[" + c + "]");
        }
    }

}
