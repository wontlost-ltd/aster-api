package io.aster.policy.rest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 窗口解析（ADR 0034 §3.3 / §7.1）。
 *
 * <p><b>这个测试守两条容易写错的语义</b>：
 * <ol>
 *   <li>右边界取**当天 00:00 且不含**——边界指向已封闭的过去，
 *       正在写入的数据天然落在窗口外。取 {@code now()} 会把边界切在
 *       数据正在写入的位置。</li>
 *   <li>**不能选未来**——服务端独立校验，不依赖前端 disable。
 *       前端只是体验，API 直连必须被拒。</li>
 * </ol>
 */
class ReplayBatchWindowResolutionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** resolveWindow 是私有静态方法，反射调用以做纯逻辑测试。 */
    private static Object resolve(ReplayBatchResource.CreateRequest req) throws Exception {
        Method m = ReplayBatchResource.class
            .getDeclaredMethod("resolveWindow", ReplayBatchResource.CreateRequest.class, ZoneId.class);
        m.setAccessible(true);
        try {
            return m.invoke(null, req, UTC);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private static Instant from(Object window) throws Exception {
        Method m = window.getClass().getDeclaredMethod("from");
        m.setAccessible(true);
        return (Instant) m.invoke(window);
    }

    private static Instant to(Object window) throws Exception {
        Method m = window.getClass().getDeclaredMethod("to");
        m.setAccessible(true);
        return (Instant) m.invoke(window);
    }

    private static String label(Object window) throws Exception {
        Method m = window.getClass().getDeclaredMethod("label");
        m.setAccessible(true);
        return (String) m.invoke(window);
    }

    private static ReplayBatchResource.CreateRequest req(String kind, String f, String t) {
        return req(kind, f, t, null);
    }

    /** includeToday 可控的重载——默认档位（null/false）行为必须与既有用例一致。 */
    private static ReplayBatchResource.CreateRequest req(
        String kind, String f, String t, Boolean includeToday) {
        return new ReplayBatchResource.CreateRequest("v1", "v2", kind, f, t, includeToday);
    }

    @Test
    void 右边界是当天00点且不含当天() throws Exception {
        Object w = resolve(req("LAST_MONTH", null, null));
        Instant expectedTo = LocalDate.now(UTC).atStartOfDay(UTC).toInstant();

        assertThat(to(w))
            .as("★右边界必须是当天 00:00，不是 now()——边界要指向已封闭的过去")
            .isEqualTo(expectedTo);
    }

    @Test
    void 四个预设档位的跨度正确() throws Exception {
        LocalDate today = LocalDate.now(UTC);
        record Case(String kind, LocalDate expectedFrom, String expectedLabel) {
        }
        for (Case c : new Case[] {
            new Case("LAST_MONTH", today.minusMonths(1), "最近一个月"),
            new Case("LAST_QUARTER", today.minusMonths(3), "最近一个季度"),
            new Case("LAST_HALF_YEAR", today.minusMonths(6), "最近半年"),
            new Case("LAST_YEAR", today.minusYears(1), "最近一年"),
        }) {
            Object w = resolve(req(c.kind(), null, null));
            assertThat(from(w))
                .as("%s 的起点", c.kind())
                .isEqualTo(c.expectedFrom().atStartOfDay(UTC).toInstant());
            assertThat(label(w))
                .as("★口径文案必须与数字同屏，故不能为空")
                .isEqualTo(c.expectedLabel());
        }
    }

    @Test
    void 自然月而非30天() throws Exception {
        // 用户说「一个月」想的是日历月，不是 30 天
        Object w = resolve(req("LAST_MONTH", null, null));
        LocalDate today = LocalDate.now(UTC);
        assertThat(from(w)).isEqualTo(today.minusMonths(1).atStartOfDay(UTC).toInstant());
    }

    @Test
    void 自定义窗口不得选未来() {
        // ★服务端独立拒绝，不依赖前端 disable——前端是提示，服务端是边界
        LocalDate tomorrow = LocalDate.now(UTC).plusDays(1);
        assertThatThrownBy(() -> resolve(req("CUSTOM", "2026-01-01", tomorrow.toString())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("未来");
    }

    @Test
    void 自定义窗口起点必须早于终点() {
        assertThatThrownBy(() -> resolve(req("CUSTOM", "2026-06-01", "2026-06-01")))
            .as("起止相同是空窗口")
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolve(req("CUSTOM", "2026-06-02", "2026-06-01")))
            .as("倒置")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 自定义窗口缺参数时报错() {
        assertThatThrownBy(() -> resolve(req("CUSTOM", null, "2026-06-01")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolve(req("CUSTOM", "2026-06-01", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 非法日期格式报错而非静默兜底() {
        assertThatThrownBy(() -> resolve(req("CUSTOM", "not-a-date", "2026-06-01")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    void 未知档位报错而非默认() {
        // ★静默默认成 LAST_MONTH 会让用户以为选中了自己要的档位
        assertThatThrownBy(() -> resolve(req("LAST_DECADE", null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LAST_DECADE");
    }

    @Test
    void 自定义窗口到今天是允许的() throws Exception {
        // 今天不是未来；右边界取当天 00:00 意味着今天的数据本就不含
        LocalDate today = LocalDate.now(UTC);
        Object w = resolve(req("CUSTOM", today.minusDays(7).toString(), today.toString()));
        assertThat(to(w)).isEqualTo(today.atStartOfDay(UTC).toInstant());
    }

    // ── includeToday：用户显式选择"覆盖到此刻" ────────────────────────
    //
    // ★背景：默认右边界是当天 00:00，意味着**今天刚跑的执行要等到明天**
    //   才进得了窗口。用户实测反馈：改完策略立刻想看 What-If，却总是
    //   "nothing to compare"。这是真实痛点，故提供显式开关。
    //   代价是该区间尚未封闭——写在 CreateRequest.includeToday 的注释里。

    @Test
    void includeToday为true时右边界延伸到此刻() throws Exception {
        Instant before = Instant.now();
        Object w = resolve(req("LAST_MONTH", null, null, true));
        Instant after = Instant.now();

        // 右边界应落在本次调用的时间区间内，而不是当天 00:00。
        assertThat(to(w)).isBetween(before, after);
        assertThat(to(w))
            .as("勾选后右边界不应还停在当天 00:00")
            .isAfter(LocalDate.now(UTC).atStartOfDay(UTC).toInstant());
    }

    @Test
    void includeToday为null或false时保持原有行为() throws Exception {
        Instant expected = LocalDate.now(UTC).atStartOfDay(UTC).toInstant();

        // ★默认必须与既有行为逐字节一致——这是不改变默认语义的保证。
        assertThat(to(resolve(req("LAST_MONTH", null, null, null)))).isEqualTo(expected);
        assertThat(to(resolve(req("LAST_MONTH", null, null, false)))).isEqualTo(expected);
    }

    @Test
    void includeToday不改变左边界() throws Exception {
        // 只动右边界；左边界仍是"今天往前推 N 个月的 00:00"。
        Instant expectedFrom = LocalDate.now(UTC).minusMonths(1).atStartOfDay(UTC).toInstant();
        assertThat(from(resolve(req("LAST_MONTH", null, null, true)))).isEqualTo(expectedFrom);
    }

    @Test
    void includeToday对CUSTOM档位不生效() throws Exception {
        // CUSTOM 的边界完全由用户给的日期决定，没有"要不要含当天"的歧义。
        String from = LocalDate.now(UTC).minusDays(10).toString();
        String to = LocalDate.now(UTC).minusDays(1).toString();
        Instant expectedTo = LocalDate.parse(to).atStartOfDay(UTC).toInstant();

        assertThat(to(resolve(req("CUSTOM", from, to, true)))).isEqualTo(expectedTo);
    }

}
