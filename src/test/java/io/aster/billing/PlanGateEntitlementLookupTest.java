package io.aster.billing;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * lookupPlanForEntitlement 的故障分类与缓存隔离：
 * 超时/连接失败 → 不可用；404 只影响本次权益判定，不污染与 lookupPlan 共享的缓存；
 * event-loop 缓存未命中 → 不可用 + 后台预热，预热后（含 404）能得到确定答案。
 */
class PlanGateEntitlementLookupTest {

    /** 每次调用 sendPlanRequest 时执行的桩行为。 */
    interface Stub {
        HttpResponse<String> send() throws Exception;
    }

    private final AtomicReference<Stub> stub = new AtomicReference<>();
    private PlanGateService service;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        PlanGateConfig config = mock(PlanGateConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.failOpen()).thenReturn(true);
        service = new PlanGateService() {
            @Override
            HttpResponse<String> sendPlanRequest(String tenantId) throws Exception {
                return stub.get().send();
            }
        };
        service.config = config;
        service.cache = Caffeine.newBuilder().build();
        service.notFoundTenants = Caffeine.newBuilder().build();
        pool = Executors.newSingleThreadExecutor();
        service.lookupPool = pool;
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        return r;
    }

    @Test
    void 超时为不可用() {
        stub.set(() -> { throw new HttpTimeoutException("request timed out"); });
        assertThatThrownBy(() -> service.lookupPlanForEntitlement("t1"))
            .isInstanceOf(PlanLookupUnavailableException.class);
        assertThat(service.cache.getIfPresent("t1")).isNull();
    }

    @Test
    void 连接失败为不可用() {
        stub.set(() -> { throw new ConnectException("refused"); });
        assertThatThrownBy(() -> service.lookupPlanForEntitlement("t1"))
            .isInstanceOf(PlanLookupUnavailableException.class);
    }

    @Test
    void 确认的pro档写入共享缓存() {
        stub.set(() -> response(200, "{\"plan\":\"pro\",\"concurrentReplayBatches\":1}"));
        assertThat(service.lookupPlanForEntitlement("t1").allowsReplayBatch()).isTrue();
        assertThat(service.cache.getIfPresent("t1")).isNotNull();
    }

    @Test
    void 租户404为无权益且不写共享缓存() {
        stub.set(() -> response(404, ""));
        assertThat(service.lookupPlanForEntitlement("t1").allowsReplayBatch()).isFalse();
        assertThat(service.cache.getIfPresent("t1")).as("404 不得进入与 lookupPlan 共享的缓存").isNull();

        // 共享缓存为空 → 审批等软门控仍走 lookupPlan 的既有路径（此处 cloud 404 → 占位值，允许审批）
        assertThat(service.allowsApproval("t1")).isTrue();
    }

    @Test
    void event_loop缓存未命中为不可用且后台预热() throws Exception {
        stub.set(() -> response(200, "{\"plan\":\"pro\",\"concurrentReplayBatches\":1}"));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                service.lookupPlanForEntitlement("t1");
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "vert.x-eventloop-thread-0");
        t.start();
        t.join();
        assertThat(thrown.get()).isInstanceOf(PlanLookupUnavailableException.class);

        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.cache.getIfPresent("t1")).as("后台预热后命中缓存").isNotNull();
    }

    @Test
    void event_loop上404租户预热后得到确定的无权益而非永远503() throws Exception {
        stub.set(() -> response(404, ""));
        Thread t = new Thread(() -> {
            try {
                service.lookupPlanForEntitlement("t1");
            } catch (PlanLookupUnavailableException expected) {
                // 首次必然不可用
            }
        }, "vert.x-eventloop-thread-0");
        t.start();
        t.join();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        AtomicReference<PlanInfo> second = new AtomicReference<>();
        Thread t2 = new Thread(() -> second.set(service.lookupPlanForEntitlement("t1")),
            "vert.x-eventloop-thread-0");
        t2.start();
        t2.join();
        assertThat(second.get()).isNotNull();
        assertThat(second.get().allowsReplayBatch()).isFalse();
        assertThat(service.cache.getIfPresent("t1")).isNull();
    }
}
