package io.aster.policy.api.cache;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * 以 Redis 缓存后端（生产默认，多副本共享 policy-results）重跑 {@link PolicyCacheInvalidationSubscribeIT}。
 *
 * <p>重点是断线期间漏收的租户级失效：共享条目只有写入它的副本在自己的租户索引里追踪，源实例的
 * 租户级失效删不到它，必须由该副本恢复订阅后按索引补删，否则全部副本都会读到陈旧结果直至 TTL。
 */
@QuarkusTest
@TestProfile(RedisBackedCacheProfile.class)
@RedisEnabledTest
class PolicyCacheInvalidationRedisBackedIT extends PolicyCacheInvalidationSubscribeIT {
}
