package io.aster.policy.api.cache;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * 以 Redis 缓存后端（生产默认，多副本共享 policy-results）重跑 {@link PolicyResultEpochIT}。
 *
 * <p>这正是 issue #484 的主场景：共享条目由哪个副本写入、哪个副本的索引追踪它都不再重要，
 * 旧 epoch 的键对所有副本都不可达。
 */
@QuarkusTest
@TestProfile(RedisBackedCacheProfile.class)
@RedisEnabledTest
class PolicyResultEpochRedisBackedIT extends PolicyResultEpochIT {
}
