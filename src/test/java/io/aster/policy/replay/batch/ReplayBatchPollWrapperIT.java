package io.aster.policy.replay.batch;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ReplayBatchScheduler#pollAndRun()} 的<b>调度包装层</b>测试（issue #311）。
 *
 * <p>★<b>为什么单独测包装层</b>：{@code reclaimStaleLeases}/{@code claimNextPending}/
 * {@code runBatch}/{@code failBatchDefensively} 各自都有测试，但把它们<b>怎么串起来</b>
 * 的四个决定只存在于这个方法里，此前测试树对它零引用：
 * <ol>
 *   <li><b>先回收、再领取</b>的顺序 —— 顺序反了，卡死的批次会一直占着租户并发额度；</li>
 *   <li>回收失败<b>不得阻断</b>领取 —— 否则一次回收异常就让整条流水线停摆；</li>
 *   <li>批次执行异常<b>不得逃出</b>调度器 —— 逃出去会停掉整个轮询；</li>
 *   <li>失败分类要<b>诚实</b> —— 目标版本缺失是输入契约问题，不能一律归 UNKNOWN。</li>
 * </ol>
 *
 * <p>这四条一旦写错，内层测试一个都不会红。
 */
@QuarkusTest
@TestProfile(ReplayBatchPollWrapperIT.NoSchedulerProfile.class)
class ReplayBatchPollWrapperIT {

    /** 关掉后台调度器：否则它会与测试线程抢着调用 pollAndRun，让交互次数不可断言。 */
    public static class NoSchedulerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.scheduler.enabled", "false",
                "aster.scheduler.background.enabled", "false");
        }
    }

    @Inject
    ReplayBatchScheduler scheduler;

    @InjectMock
    ReplayBatchService service;

    @BeforeEach
    void reset() {
        Mockito.reset(service);
    }

    /**
     * ★<b>顺序</b>：必须先回收过期租约，再领取新批次。
     *
     * <p>源码注释说明了这个顺序的意义：领取只查 PENDING，而「提交 RUNNING 之后
     * 进程崩溃」的批次卡在 RUNNING，没有回收就无人负责 —— 它不止自己不出结果，
     * 还<b>持续占着租户并发额度</b>（pro 档只有 1 个），该租户从此发不出任何
     * What-If 批次。
     */
    @Test
    @DisplayName("必须先回收过期租约、再领取新批次")
    void reclaimsBeforeClaiming() {
        Mockito.when(service.reclaimStaleLeases()).thenReturn(0);
        Mockito.when(service.claimNextPending()).thenReturn(null);

        scheduler.pollAndRun();

        // ★断言力全在 InOrder 上：顺序反了这里就会失败。
        //   不写 assertThat(true).isTrue() 那种空断言——它对任何实现都成立。
        InOrder order = Mockito.inOrder(service);
        order.verify(service).reclaimStaleLeases();
        order.verify(service).claimNextPending();
    }

    /**
     * ★回收<b>失败</b>不得阻断领取 —— 下一轮再试即可。
     *
     * <p>没有这条守卫，一次回收异常就会让整条流水线停摆：
     * 既不回收也不领取，且因为异常被吞在调度器外层，问题会长期隐身。
     */
    @Test
    @DisplayName("回收过期租约失败时仍须继续领取")
    void reclaimFailureDoesNotBlockClaiming() {
        Mockito.when(service.reclaimStaleLeases())
            .thenThrow(new RuntimeException("回收炸了"));
        Mockito.when(service.claimNextPending()).thenReturn(null);

        assertThatCode(() -> scheduler.pollAndRun())
            .as("★回收失败不得让 pollAndRun 抛出——抛出会停掉整个轮询")
            .doesNotThrowAnyException();

        // ★真正的断言：回收抛异常后**仍然**调了 claimNextPending。
        //   没这一句的话，一个「回收失败就直接 return」的实现也能让上面的
        //   doesNotThrowAnyException 通过，而流水线其实已经停摆。
        Mockito.verify(service).claimNextPending();
    }

    /**
     * ★批次执行异常<b>不得逃出</b>调度器，且必须走防御性标记。
     *
     * <p>一个批次炸掉不应停掉整个轮询；同时该批次必须被标记，
     * 否则它会永久卡在 RUNNING 并继续占额度。
     */
    @Test
    @DisplayName("批次执行异常不得逃出调度器，且必须防御性标记")
    void runBatchFailureIsContainedAndMarked() {
        UUID batchId = UUID.randomUUID();
        Mockito.when(service.reclaimStaleLeases()).thenReturn(0);
        Mockito.when(service.claimNextPending())
            .thenReturn(new ReplayBatchService.Claim(batchId, "owner-1"));
        Mockito.doThrow(new RuntimeException("批次炸了"))
            .when(service).runBatch(batchId);

        assertThatCode(() -> scheduler.pollAndRun())
            .as("★单个批次失败不得让异常逃出调度器——逃出去会停掉整个轮询")
            .doesNotThrowAnyException();

        Mockito.verify(service).failBatchDefensively(
            batchId, "owner-1", ReplayFailureKind.UNKNOWN);
    }

    /**
     * ★失败分类必须<b>诚实</b>：目标版本缺失归 {@code TARGET_VERSION_MISSING}。
     *
     * <p>源码注释说明了原因：一律归 UNKNOWN 会让 UI 说成「部分执行无法重放」，
     * 而这类失败下<b>一条都没跑</b> —— 那句话会把用户支去排查自己的执行记录，
     * 而真正的问题在输入契约（目标版本定位不到）。
     */
    @Test
    @DisplayName("目标版本缺失必须归类为 TARGET_VERSION_MISSING 而非 UNKNOWN")
    void targetVersionMissingIsClassifiedHonestly() {
        UUID batchId = UUID.randomUUID();
        Mockito.when(service.reclaimStaleLeases()).thenReturn(0);
        Mockito.when(service.claimNextPending())
            .thenReturn(new ReplayBatchService.Claim(batchId, "owner-2"));
        Mockito.doThrow(new ReplayBatchService.TargetVersionMissingException("v-404"))
            .when(service).runBatch(batchId);

        scheduler.pollAndRun();

        Mockito.verify(service).failBatchDefensively(
            batchId, "owner-2", ReplayFailureKind.TARGET_VERSION_MISSING);
        Mockito.verify(service, Mockito.never()).failBatchDefensively(
            Mockito.any(), Mockito.any(), Mockito.eq(ReplayFailureKind.UNKNOWN));
    }

    /**
     * 无待跑批次时不得调用 {@code runBatch}。
     *
     * <p>反向守卫：防止「不管领没领到都跑一把」这种实现 ——
     * 那会拿 null 批次去执行，把 NPE 当成业务失败记下来。
     */
    @Test
    @DisplayName("没有待跑批次时不得执行")
    void noClaimMeansNoRun() {
        Mockito.when(service.reclaimStaleLeases()).thenReturn(0);
        Mockito.when(service.claimNextPending()).thenReturn(null);

        scheduler.pollAndRun();

        Mockito.verify(service, Mockito.never()).runBatch(Mockito.any());
        Mockito.verify(service, Mockito.never())
            .failBatchDefensively(Mockito.any(), Mockito.any(), Mockito.any());
    }
}
