package io.aster.policy.stability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolchainIdentityProvider 单元测试（S0 / m1.5 层1：build/core 真值）。
 *
 * <p>验证工具链身份串 {@code abi=;core=;validator=;build=} 的两个「真值」维度：
 * <ul>
 *   <li>build —— 由 {@code aster.runtime.build} 配置注入（部署时 = 产出镜像的 github.sha，
 *       烘进镜像 ENV ASTER_RUNTIME_BUILD → MicroProfile relaxed binding）；本地/未注入时保持 dev。</li>
 *   <li>core —— 由 aster-lang-core jar MANIFEST 的 Implementation-Version 决定（core build.gradle.kts
 *       stamp）；本测试断言运行时 classpath 上的 core 已带真实版本（非 dev），证明 stamp 生效。</li>
 * </ul>
 * ★这是签字级回放地基的一部分：build/core 谎报会让「同 toolchainId 应产同输出」不变量破裂。
 */
class ToolchainIdentityProviderTest {

    private ToolchainIdentityProvider providerWithBuild(String build) {
        ToolchainIdentityProvider p = new ToolchainIdentityProvider();
        p.runtimeBuild = build; // 包内可见字段，模拟配置注入（等价 ASTER_RUNTIME_BUILD env）。
        return p;
    }

    @Test
    void injectedBuildAppearsInToolchainId() {
        // 部署路径：github.sha 经镜像 ENV 注入 → build= 段为真值（非 dev）。
        String sha = "02429c3fb17878ee5d0814e1a522058e2e1a3a2a";
        String id = providerWithBuild(sha).currentToolchainId();
        assertThat(id).contains(";build=" + sha);
        assertThat(id).startsWith("abi=");
        // 结构完整：四段齐全。
        assertThat(id).contains(";core=").contains(";validator=");
    }

    @Test
    void defaultBuildIsDevWhenNotInjected() {
        // 本地/未注入 ASTER_RUNTIME_BUILD → 诚实降级 dev（与 @ConfigProperty defaultValue 一致），不谎报。
        String id = providerWithBuild("dev").currentToolchainId();
        assertThat(id).contains(";build=dev");
    }

    @Test
    void coreVersionIsStampedNotDev() {
        // ★Part 3 验证：aster-lang-core jar MANIFEST 已 stamp Implementation-Version → core= 段为真实
        // 引擎版本（如 1.0.x），非 dev。若 core jar 未 stamp（回归），此断言失败。
        String id = providerWithBuild("test").currentToolchainId();
        // 提取 core= 段值。
        String core = id.replaceAll(".*;core=", "").replaceAll(";.*", "");
        assertThat(core)
            .as("core 引擎版本应来自 jar MANIFEST Implementation-Version（非 dev 回退）")
            .isNotBlank()
            .isNotEqualTo("dev")
            .matches("\\d+\\.\\d+\\.\\d+.*"); // 语义版本形态，如 1.0.14
    }

    @Test
    void strictProductionRejectsDevelopmentIdentityWithSegmentNames() {
        ToolchainIdentityGuard guard = new ToolchainIdentityGuard();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            guard.check("abi=V1;core=dev;validator=1.0;build=dev", "prod", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core")
            .hasMessageContaining("build");
    }

    @Test
    void lenientIdentityDoesNotThrow() {
        ToolchainIdentityGuard guard = new ToolchainIdentityGuard();
        org.assertj.core.api.Assertions.assertThatCode(() ->
            guard.check("abi=V1;core=dev;validator=1.0;build=dev", "test", false))
            .doesNotThrowAnyException();
    }
}
