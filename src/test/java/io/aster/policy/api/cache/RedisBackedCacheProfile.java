package io.aster.policy.api.cache;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;
import java.util.UUID;

/**
 * 让 policy-results 等缓存走 Redis 后端（与生产默认一致），value-type 沿用 main 配置。
 * 仅供需要验证 Redis 序列化/反序列化的测试使用；其余测试仍用 Caffeine。
 *
 * <p>键前缀带每次测试 JVM 唯一的运行 id：本地 Redis 跨运行保留数据，而测试库会复用主键，
 * 共用生产前缀时上一次运行同号版本的编译结果等条目会被本次命中。前缀结构保持与生产相同
 * （所有缓存共用一个前缀，不按缓存名拆分），只在其后追加运行 id。
 */
public class RedisBackedCacheProfile implements QuarkusTestProfile {

    /**
     * 每个测试 JVM 一个，同一运行内的 Redis 测试类共享，跨运行互不可见。存放在系统属性里：
     * profile 类会被测试与 Quarkus 两个类加载器各加载一次，静态字段各自初始化会得到不同的值。
     */
    static final String RUN_ID = (String) System.getProperties()
        .computeIfAbsent("aster.test.redis-run-id", k -> UUID.randomUUID().toString());

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "quarkus.cache.type", "redis",
            "quarkus.cache.redis.prefix", "policy-cache:test-run-" + RUN_ID);
    }
}
