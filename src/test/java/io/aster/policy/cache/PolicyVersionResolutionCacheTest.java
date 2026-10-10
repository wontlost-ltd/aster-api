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

    /** 不经过在途解析时的写入：先取当前代次，再按代次写回。 */
    private static void store(PolicyVersionResolutionCache c, String t, String m, String f, String v) {
        c.putIfUnchanged(t, m, f, v, c.stamp(t, m, f));
    }

    @Test
    void missWhenEmpty() {
        PolicyVersionResolutionCache c = newCache(5000);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
    }

    @Test
    void hitAfterPut() {
        PolicyVersionResolutionCache c = newCache(5000);
        store(c, "t", "m", "f", "42");
        PolicyVersionResolutionCache.Holder h = c.get("t", "m", "f");
        assertThat(h.isPresent()).isTrue();
        assertThat(h.versionId()).isEqualTo("42");
    }

    @Test
    void nullSentinelCachesNoActiveVersionWithoutPenetration() {
        PolicyVersionResolutionCache c = newCache(5000);
        store(c, "t", "m", "f", null); // 「无活跃版本」也缓存，避免反复查 DB
        PolicyVersionResolutionCache.Holder h = c.get("t", "m", "f");
        assertThat(h.isPresent()).isTrue();   // 命中（无需再查 DB）
        assertThat(h.versionId()).isNull();   // 但值为 null = 无活跃版本
    }

    @Test
    void expiresAfterTtl() throws InterruptedException {
        PolicyVersionResolutionCache c = newCache(30); // 30ms TTL
        store(c, "t", "m", "f", "7");
        assertThat(c.get("t", "m", "f").isPresent()).isTrue();
        Thread.sleep(60);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse(); // 过期 → 未命中
    }

    @Test
    void invalidateDropsEntry() {
        PolicyVersionResolutionCache c = newCache(5000);
        store(c, "t", "m", "f", "7");
        c.invalidate("t", "m", "f");
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
    }

    @Test
    void invalidateIsScopedToKey() {
        PolicyVersionResolutionCache c = newCache(5000);
        store(c, "t", "m", "f1", "1");
        store(c, "t", "m", "f2", "2");
        c.invalidate("t", "m", "f1");
        assertThat(c.get("t", "m", "f1").isPresent()).isFalse();
        assertThat(c.get("t", "m", "f2").versionId()).isEqualTo("2");
    }

    @Test
    void disabledWhenTtlZero() {
        PolicyVersionResolutionCache c = newCache(0);
        store(c, "t", "m", "f", "7");
        assertThat(c.get("t", "m", "f").isPresent()).isFalse(); // 禁用 → 永不命中
    }

    @Test
    void tenantIsolation() {
        PolicyVersionResolutionCache c = newCache(5000);
        store(c, "tenantA", "m", "f", "A");
        store(c, "tenantB", "m", "f", "B");
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
        store(c, "t", "m", "f", "1");
        store(c, "u", "m", "f", "2");
        long other = c.stamp("u", "m", "g");
        c.invalidateTenant("t");
        c.putIfUnchanged("u", "m", "g", "3", other);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
        assertThat(c.get("u", "m", "f").versionId()).isEqualTo("2");
        assertThat(c.get("u", "m", "g").versionId()).isEqualTo("3");
    }
    @Test
    void 清空后失效代次全部回收() {
        PolicyVersionResolutionCache c = newCache(5000);
        c.invalidate("t", "m", "f");
        c.invalidate("u", "m", "g");
        c.invalidateTenant("t");
        c.invalidateTenant("v");
        c.clear();
        assertThat(c.trackedGenerations()).isZero();
    }

    @Test
    void 租户失效回收该租户的键代次() {
        PolicyVersionResolutionCache c = newCache(5000);
        c.invalidate("t", "m", "f");
        c.invalidate("t", "m", "g");
        c.invalidate("u", "m", "f");
        c.invalidateTenant("t");
        // 剩下 u 的键代次与 t 的租户代次
        assertThat(c.trackedGenerations()).isEqualTo(2);
    }

    @Test
    void 回收后旧stamp仍被拒绝() {
        PolicyVersionResolutionCache c = newCache(5000);
        long beforeKey = c.stamp("t", "m", "f");
        c.invalidate("t", "m", "f");
        long afterKey = c.stamp("t", "m", "f");
        c.invalidateTenant("t");
        c.putIfUnchanged("t", "m", "f", "old", beforeKey);
        c.putIfUnchanged("t", "m", "f", "old", afterKey);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();

        long beforeClear = c.stamp("t", "m", "f");
        c.invalidate("u", "m", "f");
        long uBeforeClear = c.stamp("u", "m", "f");
        c.clear();
        c.putIfUnchanged("t", "m", "f", "old", beforeClear);
        c.putIfUnchanged("u", "m", "f", "old", uBeforeClear);
        assertThat(c.get("t", "m", "f").isPresent()).isFalse();
        assertThat(c.get("u", "m", "f").isPresent()).isFalse();

        // 回收后新取得的 stamp 照常写入
        c.putIfUnchanged("t", "m", "f", "new", c.stamp("t", "m", "f"));
        assertThat(c.get("t", "m", "f").versionId()).isEqualTo("new");
    }

    @Test
    void 租户失效不回收id以该租户为前缀的其他租户的键代次() {
        PolicyVersionResolutionCache c = newCache(5000);
        long before = c.stamp("a:b", "m", "f");
        c.invalidate("a:b", "m", "f");
        // 租户 "a" 的键前缀 "a:" 也是 "a:b:m:f" 的前缀；回收不能误删别的租户的代次
        c.invalidateTenant("a");
        c.putIfUnchanged("a:b", "m", "f", "old", before);
        assertThat(c.get("a:b", "m", "f").isPresent()).isFalse();
    }
}
