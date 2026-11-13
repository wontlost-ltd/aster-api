package io.aster.policy.tenant;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户 ID 长度上限（aster-api#503）：严格与宽松模式共用同一上限
 * {@link TenantFilter#MAX_TENANT_ID_LENGTH}，不超过最窄的租户列（workflow_state.tenant_id VARCHAR(64)）。
 *
 * <p>此前宽松模式放行到 255，而 audit_logs.tenant_id 只有 100 宽且是哈希链分区键（不可截断），
 * 超宽租户的每条审计都整条写入失败。现在超限在入口以 400 拒绝。
 */
class TenantFilterLengthCapTest {

    private TenantContext tenantContext;

    private TenantFilter newFilter(boolean strict) throws Exception {
        TenantFilter filter = new TenantFilter();
        set(filter, "whitelistEnabled", false);
        set(filter, "allowedTenants", Optional.<String>empty());
        set(filter, "strictFormat", strict);
        tenantContext = new TenantContext();
        set(filter, "tenantContext", tenantContext);
        return filter;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = TenantFilter.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static ContainerRequestContext ctx(String tenantHeader) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getMethod()).thenReturn("POST");
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn("/api/v1/policies/evaluate");
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/api/v1/policies/evaluate"));
        when(ctx.getUriInfo()).thenReturn(uri);
        MultivaluedHashMap<String, String> hdr = new MultivaluedHashMap<>();
        hdr.add("X-Tenant-Id", tenantHeader);
        when(ctx.getHeaders()).thenReturn(hdr);
        when(ctx.getHeaderString(any())).thenAnswer(inv -> hdr.getFirst(inv.<String>getArgument(0)));
        return ctx;
    }

    private static Response rejection(ContainerRequestContext c) {
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(c).abortWith(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("上限与最窄租户列一致（64）")
    void capIsSixtyFour() {
        assertThat(TenantFilter.MAX_TENANT_ID_LENGTH).isEqualTo(64);
    }

    @ParameterizedTest(name = "宽松模式 {0} 字符 → 400")
    @ValueSource(ints = {65, 101, 255, 256})
    void nonStrictRejectsOverCap(int length) throws Exception {
        TenantFilter filter = newFilter(false);
        ContainerRequestContext c = ctx("t".repeat(length));
        filter.filter(c);
        Response r = rejection(c);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getEntity()).isEqualTo("Tenant ID must not exceed 64 characters");
        assertThat(tenantContext.isInitialized()).isFalse();
    }

    @Test
    @DisplayName("宽松模式恰好 64 字符（含严格模式不允许的字符）→ 放行")
    void nonStrictAcceptsBoundary() throws Exception {
        TenantFilter filter = newFilter(false);
        String tenant = "acme.corp@" + "x".repeat(54);
        assertThat(tenant).hasSize(64);
        ContainerRequestContext c = ctx(tenant);
        filter.filter(c);
        verify(c, never()).abortWith(any());
        assertThat(tenantContext.getCurrentTenant()).isEqualTo(tenant);
    }

    @Test
    @DisplayName("宽松模式按去除首尾空白后的长度判定")
    void nonStrictMeasuresTrimmedValue() throws Exception {
        TenantFilter filter = newFilter(false);
        String tenant = "y".repeat(64);
        ContainerRequestContext c = ctx("  " + tenant + "  ");
        filter.filter(c);
        verify(c, never()).abortWith(any());
        assertThat(tenantContext.getCurrentTenant()).isEqualTo(tenant);
    }

    @Test
    @DisplayName("严格模式不变：64 字符放行，65 字符按格式错误 400")
    void strictUnchanged() throws Exception {
        TenantFilter filter = newFilter(true);
        ContainerRequestContext ok = ctx("a".repeat(64));
        filter.filter(ok);
        verify(ok, never()).abortWith(any());
        assertThat(tenantContext.getCurrentTenant()).isEqualTo("a".repeat(64));

        TenantFilter strict = newFilter(true);
        ContainerRequestContext tooLong = ctx("a".repeat(65));
        strict.filter(tooLong);
        Response r = rejection(tooLong);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getEntity()).isEqualTo(
            "Invalid tenant ID format. Only alphanumeric characters, hyphens, and underscores are allowed (max 64 characters).");
    }

    @Test
    @DisplayName("严格模式不变：常见租户形态（UUID / cuid / trial-playground）放行")
    void strictAcceptsKnownFormats() throws Exception {
        for (String tenant : new String[]{"0b6f3c1e-2d4a-4f8e-9c3b-7a1d2e3f4a5b", "cm3k9x2ab0000qwer1234tyui", "trial-playground", "default"}) {
            TenantFilter filter = newFilter(true);
            ContainerRequestContext c = ctx(tenant);
            filter.filter(c);
            verify(c, never()).abortWith(any());
            assertThat(tenantContext.getCurrentTenant()).as(tenant).isEqualTo(tenant);
        }
    }
}
