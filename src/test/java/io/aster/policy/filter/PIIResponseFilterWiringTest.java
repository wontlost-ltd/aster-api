package io.aster.policy.filter;

import io.aster.policy.config.PIIConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * {@link PIIResponseFilter} 的<b>接线</b>测试（「测试配置掩盖生产行为」专项扫描发现）。
 *
 * <p>★<b>为什么需要这个文件</b>：既有的 4 个 PII 测试（共 20 个用例）
 * 全都在测 {@link io.aster.policy.common.PIIRedactor} 这个<b>纯函数</b>，
 * 或者只断言「API 调用没被过滤器弄坏」。**没有一个测过滤器本身是否真的脱敏**。
 *
 * <p>实测证据：把 {@code PIIResponseFilter.filter()} 开头改成
 * {@code if (true) return;}（整个过滤器停摆），<b>20 个 PII 测试全部仍然绿</b>。
 *
 * <p>★<b>根因是配置</b>：{@code aster.pii.enforce} 生产是 {@code true}、
 * 测试 {@code src/test/resources/application.properties} 里是 {@code false}，
 * 且<b>没有任何测试把它设回 true</b>。于是过滤器在测试里从第 53 行就返回了，
 * 后面的脱敏逻辑<b>一行都没跑过</b>——针对它的任何断言都只能是假绿。
 *
 * <p>本用例绕开配置直接构造 {@code enforce=true} 的过滤器，
 * 锁住「过滤器被调用时确实会脱敏」这一行为本身。
 */
class PIIResponseFilterWiringTest {

    /** 造一个 enforce=true 的过滤器（生产口径），不依赖测试 profile 的配置值。 */
    private PIIResponseFilter enforcingFilter() throws Exception {
        PIIConfig cfg = new PIIConfig();
        Field f = PIIConfig.class.getDeclaredField("enforce");
        f.setAccessible(true);
        f.setBoolean(cfg, true);

        PIIResponseFilter filter = new PIIResponseFilter();
        Field cf = PIIResponseFilter.class.getDeclaredField("piiConfig");
        cf.setAccessible(true);
        cf.set(filter, cfg);
        return filter;
    }

    private ContainerRequestContext req() {
        ContainerRequestContext r = Mockito.mock(ContainerRequestContext.class);
        Mockito.when(r.getMethod()).thenReturn("GET");
        jakarta.ws.rs.core.UriInfo ui = Mockito.mock(jakarta.ws.rs.core.UriInfo.class);
        Mockito.when(ui.getPath()).thenReturn("/api/v1/test");
        Mockito.when(r.getUriInfo()).thenReturn(ui);
        return r;
    }

    /**
     * ★核心断言：含 PII 的字符串响应体<b>必须被改写</b>。
     *
     * <p>断言的是 {@code setEntity} 被调用且新值不含原始 PII——
     * 「过滤器跑没跑」在这里是可观测的，而不是靠 API 没报错来推断。
     */
    @Test
    @DisplayName("enforce=true 时，含 PII 的响应体必须被真实脱敏")
    void redactsPiiInStringResponse() throws Exception {
        PIIResponseFilter filter = enforcingFilter();
        ContainerResponseContext resp = Mockito.mock(ContainerResponseContext.class);
        Mockito.when(resp.getEntity())
            .thenReturn("{\"contact\":\"user@example.com\",\"ssn\":\"123-45-6789\"}");

        filter.filter(req(), resp);

        org.mockito.ArgumentCaptor<Object> captor =
            org.mockito.ArgumentCaptor.forClass(Object.class);
        Mockito.verify(resp).setEntity(captor.capture());

        String out = String.valueOf(captor.getValue());
        assertThat(out)
            .as("★原始 email 不得出现在响应里——出现即脱敏没生效")
            .doesNotContain("user@example.com");
        assertThat(out)
            .as("★原始 SSN 不得出现在响应里")
            .doesNotContain("123-45-6789");
        assertThat(out)
            .as("★应替换成脱敏占位符，而不是整段丢弃")
            .contains("***");
    }

    /**
     * ★反向守卫：不含 PII 的响应<b>不得</b>被改写。
     *
     * <p>没有这条，「无条件 setEntity(\"***\")」也能让上一条变绿，
     * 却会把所有正常响应体毁掉。
     */
    @Test
    @DisplayName("不含 PII 的响应不得被改写")
    void leavesCleanResponseUntouched() throws Exception {
        PIIResponseFilter filter = enforcingFilter();
        ContainerResponseContext resp = Mockito.mock(ContainerResponseContext.class);
        Mockito.when(resp.getEntity()).thenReturn("{\"status\":\"ok\",\"count\":42}");

        filter.filter(req(), resp);

        Mockito.verify(resp, Mockito.never()).setEntity(Mockito.any());
    }

    /**
     * ★{@code enforce=false} 时必须整体跳过——这是「渐进式启用」的语义。
     *
     * <p>同时它也解释了本文件存在的理由：测试 profile 走的正是这条分支，
     * 所以其余 PII 测试根本没碰到脱敏代码。
     */
    @Test
    @DisplayName("enforce=false 时整体跳过（测试 profile 走的就是这条分支）")
    void skipsEntirelyWhenDisabled() throws Exception {
        PIIConfig cfg = new PIIConfig();   // 默认 enforce=true，这里显式关掉
        Field f = PIIConfig.class.getDeclaredField("enforce");
        f.setAccessible(true);
        f.setBoolean(cfg, false);

        PIIResponseFilter filter = new PIIResponseFilter();
        Field cf = PIIResponseFilter.class.getDeclaredField("piiConfig");
        cf.setAccessible(true);
        cf.set(filter, cfg);

        ContainerResponseContext resp = Mockito.mock(ContainerResponseContext.class);
        filter.filter(req(), resp);

        Mockito.verify(resp, Mockito.never()).setEntity(Mockito.any());
        // 连 getEntity 都不该问——关掉时应在第一行就返回
        Mockito.verify(resp, Mockito.never()).getEntity();
    }

    /**
     * ★{@link PIIConfig} 的默认值必须是 {@code true}。
     *
     * <p>配置项缺失时若回退到 false，PII 保护就在无人察觉的情况下关掉了。
     * 这条锁住「不安全的默认值」这个具体风险。
     */
    @Test
    @DisplayName("PIIConfig 默认必须启用（缺配置时不得回退到不保护）")
    void defaultsToEnforcing() throws Exception {
        Field f = PIIConfig.class.getDeclaredField("enforce");
        org.eclipse.microprofile.config.inject.ConfigProperty ann =
            f.getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class);
        assertThat(ann).as("enforce 字段应带 @ConfigProperty").isNotNull();
        assertThat(ann.defaultValue())
            .as("★缺配置时必须默认启用 PII 保护——默认 false 等于静默关闭防护")
            .isEqualTo("true");
    }
}
