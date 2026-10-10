package io.aster.policy.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版本解析缓存单元测试：命中/未命中、TTL 过期、null 哨兵（防穿透）、失效、禁用。
 * 纯 POJO（不需要 Quarkus 容器），手动设置 ttlMs 字段。
 */
class PolicyVersionResolutionCacheTest {

    private PolicyVersionResolutionCache newCache(long ttlMs) {
        PolicyVersionResolutionCache c = new PolicyVersionResolutionCache();
        c.ttlMs = ttlMs;
        return c;
    }

    @Test
    void missWhenEmpty() {
        PolicyVersionResolutionCache c = newCache(5000);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
    }

    @Test
    void hitAfterPut() {
        PolicyVersionResolutionCache c = newCache(5000);
        c.put("t", "m", "f", "42");
        PolicyVersionResolutionCache.Holder h = c.get("t", "m", "f");
        assertThat(h.isPresent()).isTrue();
        assertThat(h.versionId()).isEqualTo("42");
    }

    @Test
    void nullSentinelCachesNoActiveVersionWithoutPenetration() {
        PolicyVersionResolutionCache c = newCache(5000);
        c.put("t", "m", "f", null); // 「无活跃版本」也缓存，避免反复查 DB
        PolicyVersionResolutionCache.Holder h = c.get("t", "m", "f");
        assertThat(h.isPresent()).isTrue();   // 命中（无需再查 DB）
        assertThat(h.versionId()).isNull();   // 但值为 null = 无活跃版本
    }

    @Test
    void expiresAfterTtl() throws InterruptedException {
        PolicyVersionResolutionCache c = newCache(30); // 30ms TTL
        c.put("t", "m", "f", "7");
        assertThat(c.get("t", "m", "f").isPresent()).isTrue();
        Thread.sleep(60);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse(); // 过期 → 未命中
    }

    @Test
    void invalidateDropsEntry() {
        PolicyVersionResolutionCache c = newCache(5000);
        c.put("t", "m", "f", "7");
        c.invalidate("t", "m", "f");
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
    }

    @Test
    void invalidateIsScopedToKey() {
        PolicyVersionResolutionCache c = newCache(5000);
        c.put("t", "m", "f1", "1");
        c.put("t", "m", "f2", "2");
        c.invalidate("t", "m", "f1");
        assertThat(c.get("t", "m", "f1").isPresent()).isFalse();
        assertThat(c.get("t", "m", "f2").versionId()).isEqualTo("2");
    }

    @Test
    void disabledWhenTtlZero() {
        PolicyVersionResolutionCache c = newCache(0);
        c.put("t", "m", "f", "7");
        assertThat(c.get("t", "m", "f").isPresent()).isFalse(); // 禁用 → 永不命中
    }

    @Test
    void tenantIsolation() {
        PolicyVersionResolutionCache c = newCache(5000);
        c.put("tenantA", "m", "f", "A");
        c.put("tenantB", "m", "f", "B");
        assertThat(c.get("tenantA", "m", "f").versionId()).isEqualTo("A");
        assertThat(c.get("tenantB", "m", "f").versionId()).isEqualTo("B");
    }

    @Test
    void 取得stamp后发生失效则写回被丢弃() {
        PolicyVersionResolutionCache c = newCache(5000);
        long stamp = c.stamp("t", "m", "f");
        c.invalidate("t", "m", "f");
        c.putIfUnchanged("t", "m", "f", "old", stamp);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
    }

    @Test
    void 租户失效与清空同样使旧stamp失效() {
        PolicyVersionResolutionCache c = newCache(5000);
        long beforeTenant = c.stamp("t", "m", "f");
        c.invalidateTenant("t");
        c.putIfUnchanged("t", "m", "f", "old", beforeTenant);
        long beforeClear = c.stamp("t", "m", "f");
        c.clear();
        c.putIfUnchanged("t", "m", "f", "old", beforeClear);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
    }

    @Test
    void 租户失效只影响该租户() {
        PolicyVersionResolutionCache c = newCache(5000);
        c.put("t", "m", "f", "1");
        c.put("u", "m", "f", "2");
        long other = c.stamp("u", "m", "g");
        c.invalidateTenant("t");
        c.putIfUnchanged("u", "m", "g", "3", other);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
        assertThat(c.get("u", "m", "f").versionId()).isEqualTo("2");
        assertThat(c.get("u", "m", "g").versionId()).isEqualTo("3");
    }
}
