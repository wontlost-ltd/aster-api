package io.aster.api.workflow;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * workflow_state.chk_status 约束必须容纳源枚举全部状态（审计 P0 回归）。
 *
 * <p>缺陷：V2.2.2 建表时约束含 8 个状态；V2.2.5 为加 PAUSED 而 DROP + 重建时
 * **漏抄** COMPENSATION_FAILED 与 TERMINATED，且此后再无迁移修改该约束。于是
 * saga 补偿失败路径（{@code markCompleted("COMPENSATION_FAILED")} → persist）
 * 必然违反 check 约束——补偿一旦失败，状态就再也写不进库，而这正是最需要保留
 * 现场的时刻。由 V6.19.0 修复。
 *
 * <p>★必须跑真 Postgres：{@code src/test/resources/db/h2/} 的 V2.2.2 镜像**根本
 * 没有 chk_status 约束**，所以任何基于 H2 的测试对本缺陷完全不敏感——这正是它
 * 长期未被发现的原因。本类用 Testcontainers Postgres 跑真实 Flyway 迁移链。
 *
 * <p>断言策略：不硬编码"9 个值"这种会随需求漂移的数字，而是**以源枚举
 * {@code WorkflowState.Status} 为准**逐个真实 INSERT——枚举将来新增成员而迁移
 * 漏跟时，本测试直接失败。
 */
@QuarkusTest
@TestProfile(CrashRecoveryTestProfile.class)
class WorkflowStatusConstraintIT {

    @Inject
    EntityManager em;

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM workflow_state WHERE tenant_id = 'chk-status-it'")
            .executeUpdate();
    }

    /**
     * 源枚举的每一个 Status 成员都必须能真正落库。
     *
     * <p>逐值 INSERT 真实行；任一值被约束拒绝即失败并指名是哪个值。
     */
    @Test
    void everyStatusInSourceEnumCanBePersisted() {
        List<String> statuses = java.util.Arrays.stream(aster.runtime.workflow.WorkflowState.Status.values())
            .map(Enum::name)
            .toList();

        // 前提：枚举非空，且确实包含历史上被漏掉的两个值（防语料退化导致假通过）
        assertThat(statuses)
            .as("源枚举应包含历史漏掉的两个状态，否则本回归失去意义")
            .contains("COMPENSATION_FAILED", "TERMINATED");

        for (String status : statuses) {
            assertThatStatusPersists(status);
        }
    }

    /**
     * PAUSED 是仅存于持久层的状态（不在源枚举中），也必须允许——V2.2.5 加它的初衷。
     */
    @Test
    void persistenceOnlyPausedStatusIsStillAllowed() {
        assertThatStatusPersists("PAUSED");
    }

    /**
     * 约束仍然是**收紧的**：明显非法的值必须被拒绝。
     *
     * <p>否则"修复"可能退化成直接删掉约束，那样上面的用例也会全绿（假修复）。
     */
    @Test
    void bogusStatusIsStillRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> insertWithStatus("NOT_A_REAL_STATUS"),
            "chk_status 必须仍然拒绝非法状态——约束不能被整体删除来'修复'本问题");
    }

    private void assertThatStatusPersists(String status) {
        try {
            insertWithStatus(status);
        } catch (Exception e) {
            throw new AssertionError(
                "状态 '" + status + "' 无法写入 workflow_state：chk_status 约束缺该值。"
                    + " 源枚举 WorkflowState.Status 与迁移链已漂移。", e);
        }
    }

    @Transactional
    void insertWithStatus(String status) {
        em.createNativeQuery(
                "INSERT INTO workflow_state"
                    + " (workflow_id, status, last_event_seq, schedule_count,"
                    + "  tenant_id, created_at, updated_at)"
                    + " VALUES (:wid, :status, 0, 0, 'chk-status-it', :now, :now)")
            .setParameter("wid", UUID.randomUUID())
            .setParameter("status", status)
            .setParameter("now", Instant.now())
            .executeUpdate();
        em.flush();
    }
}
