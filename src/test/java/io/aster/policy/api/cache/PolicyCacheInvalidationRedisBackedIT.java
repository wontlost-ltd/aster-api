package io.aster.policy.api.cache;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * 以 Redis 缓存后端（生产默认，多副本共享 policy-results）重跑 {@link PolicyCacheInvalidationSubscribeIT}。
 *
 * <p>重点是断线期间漏收的租户级失效：共享条目由哪个副本写入都无所谓，恢复后本副本回源拿到新 epoch，
 * 旧 epoch 的共享条目对所有副本都不可达（issue #484）。
 */
@QuarkusTest
@TestProfile(RedisBackedCacheProfile.class)
@RedisEnabledTest
class PolicyCacheInvalidationRedisBackedIT extends PolicyCacheInvalidationSubscribeIT {
}
