package io.aster.security.apikey;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 401 响应体里的自助链接必须指向 cloud 实际存在的 API Key 管理页（/settings/api-keys）。
 * 此前指向不存在的 /billing/api-keys，用户按提示点过去只看到 404（生产 E2E F12）。
 *
 * <p>不需要 Quarkus 上下文 —— 反射拿 private static method，直接断言响应体。
 */
class ApiKeyAuthFilterUnauthorizedBodyTest {

    @Test
    @SuppressWarnings("unchecked")
    void unauthorizedBodyLinksToSettingsApiKeys() throws Exception {
        Method m = ApiKeyAuthFilter.class.getDeclaredMethod("unauthorizedResponse", String.class);
        m.setAccessible(true);
        Response resp = (Response) m.invoke(null, "invalid_key");

        assertEquals(401, resp.getStatus());
        Map<String, String> body = (Map<String, String>) resp.getEntity();
        assertEquals("unauthorized", body.get("error"));
        assertEquals("invalid_key", body.get("reason"));
        assertTrue(body.get("message").contains("https://aster-lang.cloud/settings/api-keys"));
        assertFalse(body.get("message").contains("/billing/"));
    }
}
