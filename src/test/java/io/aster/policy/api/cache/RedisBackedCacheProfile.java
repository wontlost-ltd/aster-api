package io.aster.policy.api.cache;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * 让 policy-results 等缓存走 Redis 后端（与生产默认一致），value-type 沿用 main 配置。
 * 仅供需要验证 Redis 序列化/反序列化的测试使用；其余测试仍用 Caffeine。
 */
public class RedisBackedCacheProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.cache.type", "redis");
    }
}
