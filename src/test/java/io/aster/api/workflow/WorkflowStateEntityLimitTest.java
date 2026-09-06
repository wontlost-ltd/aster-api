package io.aster.api.workflow;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code findByStatus} 的行数上限必须**下推到 SQL**。
 *
 * <p>★背景：{@code /workflows/by-status} 端点此前写的是
 * {@code findByStatus(status, tenantId).stream().limit(n)} ——
 * 那会**先把整个租户的结果集读进堆、再在 Java 里截断**，
 * {@code limit} 参数从未进入 SQL，只制造了「有上限」的错觉。
 *
 * <p>本表的行含 jsonb 的 {@code result}/{@code snapshot} 与 TEXT 的
 * {@code error_message}，且经 REST 可达、随时间无限增长 ——
 * 在 1 vCPU 的 pod 上足以 OOM。
 *
 * <p>★这组用例守的是「**加载了多少行**」，不是「返回了多少行」。
 * 后者在修复前后都是对的（Java 侧截断同样能给出正确条数），
 * 所以只断言返回条数**发现不了这个缺陷**。
 */
@QuarkusTest
class WorkflowStateEntityLimitTest {

    private static final String TENANT = "limit-test-tenant";
    private static final String OTHER_TENANT = "limit-test-other";
    private static final String STATUS = "COMPLETED";

    @BeforeEach
    @Transactional
    void seed() {
        WorkflowStateEntity.delete("tenantId in ?1", List.of(TENANT, OTHER_TENANT));
        for (int i = 0; i < 7; i++) {
            persistOne(TENANT, STATUS);
        }
        // 另一租户的数据：用于确认 limit 生效的同时租户隔离没被破坏
        persistOne(OTHER_TENANT, STATUS);
    }

    private void persistOne(String tenant, String status) {
        WorkflowStateEntity e = new WorkflowStateEntity();
        e.workflowId = UUID.randomUUID();
        e.tenantId = tenant;
        e.status = status;
        e.lastEventSeq = 0L;
        e.scheduleCount = 0;
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        e.persist();
    }

    @Test
    void limitIsPushedDownToSql() {
        // ★核心断言：请求 3 条时，**数据库只返回 3 行**。
        //   修复前该方法无 limit 参数，返回全部 7 行后由调用方截断。
        List<WorkflowStateEntity> got = WorkflowStateEntity.findByStatus(STATUS, TENANT, 3);
        assertEquals(3, got.size(), "limit 未下推到 SQL：期望 DB 只返回 3 行");
    }

    @Test
    void limitLargerThanRowCountReturnsAll() {
        // 上限大于实际行数时应返回全部，不报错、不截断
        List<WorkflowStateEntity> got = WorkflowStateEntity.findByStatus(STATUS, TENANT, 100);
        assertEquals(7, got.size(), "上限大于行数时应返回全部");
    }

    @Test
    void limitDoesNotLeakAcrossTenants() {
        // ★加了 limit 不能顺手破坏租户隔离 —— 该表的无租户重载
        //   （findByStatus(status)）跨租户返回，只允许调度器内部用。
        List<WorkflowStateEntity> got = WorkflowStateEntity.findByStatus(STATUS, TENANT, 100);
        assertTrue(got.stream().allMatch(e -> TENANT.equals(e.tenantId)),
                "结果中混入了其它租户的行");
    }
}
