package io.aster.policy.api;

import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.cache.CompiledPolicyCache;
import io.aster.policy.cache.PolicyVersionResolutionCache;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 冷缓存下同一策略的并发评估只解析一次活跃版本、只按版本加载编译一次：
 * 此前每个并发请求各开一个事务、各占一条 JDBC 连接重复同一查询，突发量超过连接池时
 * 请求排队到超时，且调用方放弃后这些任务仍占着连接池，拖垮之后的请求。
 */
@QuarkusTest
@RedisEnabledTest
class VersionResolutionSingleFlightTest {

    private static final String TENANT = "tenant-batch-parallel";
    private static final String MODULE = "aster.test.batch";
    private static final String FUNCTION = "slowPolicy";
    private static final int CONCURRENCY = 60;

    @Inject PolicyEvaluationService service;
    @Inject PolicyVersionResolutionCache versionResolutionCache;
    @Inject CompiledPolicyCache compiledPolicyCache;
    @InjectSpy PolicySourceRepository repository;

    @BeforeEach
    void coldCaches() {
        service.clearAllCache().await().indefinitely();
        versionResolutionCache.clear();
        compiledPolicyCache.clear();
    }

    @Test
    void 冷缓存并发评估只解析一次版本并只加载一次版本() {
        List<Uni<PolicyEvaluationResult>> unis = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            unis.add(service.evaluatePolicy(TENANT, MODULE, FUNCTION, new Object[]{i}));
        }

        List<PolicyEvaluationResult> results = Uni.join().all(unis).andFailFast()
            .await().atMost(Duration.ofSeconds(30));

        assertThat(results).hasSize(CONCURRENCY);
        verify(repository, times(1)).findActiveCatalog(eq(TENANT), eq(MODULE), eq(FUNCTION));
        verify(repository, atMost(1)).findVersionById(anyLong());
    }
}
