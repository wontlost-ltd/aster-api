package io.aster.policy.rest;

import io.aster.policy.replay.batch.ReplayBatchEntity;
import io.aster.policy.replay.batch.ReplayBatchStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What-If 批次查询响应的 §1.1 契约（ADR 0034）。
 *
 * <p>ADR 0034 §1.1：<b>任何被呈现的数字，其样本必须是某个用户能理解的总体的全量，
 * 而非该总体的成功子集。</b>
 *
 * <h2>为什么这个文件断言真实输出，而不是扫源码</h2>
 *
 * <p>此前守护这条约束的是 {@code ReplayBatchEntitlementContractTest.拒答的批次不返回任何计数}，
 * 它用 {@code indexOf("case FAILED ->")} 到 {@code indexOf("case EXPIRED")} 切出一个
 * <b>192 字符窗口</b>，断言窗口内不含 {@code completedCount}。
 * 而真正的泄漏在 {@code switch} <b>之前</b>（无条件 {@code put("plannedCount", ...)}），
 * 结构上就在窗口外——实测注入一个字面的 {@code successCount} 字段，该测试<b>仍然全绿</b>。
 *
 * <p>教训：<b>扫源码切窗口的测试，边界是人选的，而 bug 恰好爱待在边界外。</b>
 * 本文件改为直接调用 {@link ReplayBatchResource#describe} 并检查<b>真实输出的 key</b>。
 */
class ReplayBatchResponseContractTest {

    private static ReplayBatchEntity batch(ReplayBatchStatus status) {
        ReplayBatchEntity b = new ReplayBatchEntity();
        b.id = UUID.randomUUID();
        b.status = status;
        b.windowLabel = "近一个月";
        b.windowFrom = Instant.parse("2026-07-09T00:00:00Z");
        b.windowTo = Instant.parse("2026-08-09T00:00:00Z");
        b.plannedCount = 200;
        return b;
    }

    /**
     * ★核心用例：拒答态不得同时给出「总体量」与「失败量」。
     *
     * <p>两者同屏即可相减得出成功数——那正是 Phase 4 的死因。
     */
    @Test
    void 拒答态不得下发总体量_否则可与失败量相减得出成功数() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.FAILED);
        b.failedCount = 170;
        b.completedCount = 30;
        b.failureReasons = "{\"INPUT_INCOMPATIBLE\":170}";

        Map<String, Object> body = ReplayBatchResource.describe(b);

        // 失败原因分布本身是允许的：用户要知道「为什么不给数字」
        assertThat(body).containsKey("failureReasons");
        assertThat(body).containsEntry("rejected", true);

        // ★但凡能推出总体量或成功量的字段，一个都不能有
        assertThat(body)
            .as("★拒答态出现 %s 即可算出 成功数 = 总体 - 失败", "plannedCount")
            .doesNotContainKey("plannedCount");
        assertThat(body).doesNotContainKey("completedCount");
        assertThat(body).doesNotContainKey("processedCount");
        assertThat(body).doesNotContainKey("successCount");
        assertThat(body).doesNotContainKey("totalSampled");

        // 兜底：整个响应里不得出现「成功数」这个值（30），也不得出现总体量（200）
        assertThat(body.values().stream().map(String::valueOf))
            .as("★拒答态响应中不得出现可被读作成功数/总体量的裸数字")
            .doesNotContain("30", "200");
    }

    /** 进行中只给进度分母与已处理数，不给成功数——否则用户跑完前就能推断结论（§7.4）。 */
    @Test
    void 进行中不得下发成功数() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.RUNNING);
        b.completedCount = 40;
        b.failedCount = 20;

        Map<String, Object> body = ReplayBatchResource.describe(b);

        assertThat(body).containsEntry("plannedCount", 200);
        assertThat(body)
            .as("进度只给「跑了几条」=完成+失败，合并成一个数才无法拆出成功数")
            .containsEntry("processedCount", 60);
        assertThat(body).doesNotContainKey("completedCount");
        assertThat(body).doesNotContainKey("failedCount");
        assertThat(body).doesNotContainKey("failureReasons");
    }

    /**
     * 完成态可以给总体量：全量成功时样本即总体，两者相等，无可推断。
     *
     * <p>这条不是「网开一面」，而是 §1.1 的正例——用户看到的数字，
     * 其样本恰好就是他能理解的那个总体的全量。
     */
    @Test
    void 完成态给出总体量与结果() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.COMPLETED);
        b.completedCount = 200;
        b.failedCount = 0;
        b.resultSummary = "{\"changed\":5}";

        Map<String, Object> body = ReplayBatchResource.describe(b);

        assertThat(body).containsEntry("plannedCount", 200);
        assertThat(body).containsKey("result");
        // 完成态不该有失败分布——有就说明它其实不是全量成功
        assertThat(body).doesNotContainKey("failureReasons");
        assertThat(body).doesNotContainKey("rejected");
    }

    /**
     * ★P0-3：JSON 列必须以**对象**下发，不能是转义字符串。
     *
     * <p>实体字段是 {@code String}（jsonb 列映射），直接放进响应会被再编码一次，
     * wire 上变成 {@code "failureReasons":"{\"INPUT_INCOMPATIBLE\":170}"}。
     * cloud 侧按对象读（{@code Object.entries(...)}），于是完成态数字变 undefined、
     * 失败原因按**字符**枚举。组件测试手造对象 fixture，恰好绕开了真实 wire 契约。
     */
    @Test
    void 拒答态的失败原因必须是对象而非转义字符串() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.FAILED);
        b.failureReasons = "{\"INPUT_INCOMPATIBLE\":170,\"TIMEOUT\":30}";

        Object reasons = ReplayBatchResource.describe(b).get("failureReasons");

        assertThat(reasons)
            .as("★失败原因必须是 Map；String 会让 cloud 侧按字符枚举")
            .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = (Map<String, Object>) reasons;
        assertThat(asMap)
            .containsEntry("INPUT_INCOMPATIBLE", 170)
            .containsEntry("TIMEOUT", 30);
    }

    @Test
    void 完成态的结果必须是对象而非转义字符串() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.COMPLETED);
        b.completedCount = 200;
        b.resultSummary = "{\"changed\":5,\"newlyApproved\":3,\"estimatedValueDelta\":null}";

        Object result = ReplayBatchResource.describe(b).get("result");

        assertThat(result)
            .as("★结果必须是 Map；String 会让 cloud 侧读到 undefined")
            .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) result;
        assertThat(m).containsEntry("changed", 5);
        // null 必须原样保留：cloud 靠 `=== null` 显示「无法估算」，
        // 若丢失或变成 0 会被读成「换版本没有金额影响」——一个没有依据的结论
        assertThat(m).containsKey("estimatedValueDelta");
        assertThat(m.get("estimatedValueDelta")).isNull();
    }

    /** JSON 列为空时给 null，而不是空字符串或 "null" 文本。 */
    @Test
    void 空的JSON列下发为null() {
        ReplayBatchEntity b = batch(ReplayBatchStatus.FAILED);
        b.failureReasons = null;
        assertThat(ReplayBatchResource.describe(b).get("failureReasons")).isNull();
    }

    /** 窗口口径必须与任何数字同屏：用户要知道自己看的是哪个总体。 */
    @Test
    void 每种状态都必须带窗口口径() {
        for (ReplayBatchStatus s : ReplayBatchStatus.values()) {
            Map<String, Object> body = ReplayBatchResource.describe(batch(s));
            assertThat(body)
                .as("%s 缺窗口口径——数字脱离总体就无法被正确理解", s)
                .containsKeys("windowLabel", "windowFrom", "windowTo");
        }
    }
}
