package io.aster.policy.rest;

import io.aster.policy.stability.ToolchainIdentityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VersionResource 端点逻辑测试。
 *
 * <p>不启动 Quarkus（@QuarkusTest 在本仓需真实 Postgres，见 MessagesResourceTest 的说明）——
 * 直接 new VersionResource + 反射注入 ToolchainIdentityProvider。
 *
 * <p>重点验证：弹框显示的引擎版本是实际加载的 core 版本（与编译身份同源），
 * toolchain 串与写进 Execution 的 runtimeToolchainId 同一口径。
 */
@DisplayName("VersionResource 端点逻辑")
class VersionResourceTest {

    /** 用固定 build 值构造 provider，使 currentToolchainId() 可预测。 */
    private static VersionResource resourceWithBuild(String build) throws Exception {
        ToolchainIdentityProvider provider = new ToolchainIdentityProvider();
        Field buildField = ToolchainIdentityProvider.class.getDeclaredField("runtimeBuild");
        buildField.setAccessible(true);
        buildField.set(provider, build);

        VersionResource resource = new VersionResource();
        Field toolchainField = VersionResource.class.getDeclaredField("toolchain");
        toolchainField.setAccessible(true);
        toolchainField.set(resource, provider);
        return resource;
    }

    @Test
    @DisplayName("toolchain 串含 abi/core/validator/build 四段，且 build 用注入值")
    void returnsToolchainIdentity() throws Exception {
        VersionResource.VersionInfo info = resourceWithBuild("abc123def456").version();

        assertThat(info.toolchain())
            .contains("abi=")
            .contains("core=")
            .contains("validator=")
            .contains("build=abc123def456");
    }

    @Test
    @DisplayName("engine 为实际加载的 core 引擎版本（与编译身份同源）")
    void engineIsLoadedCoreVersion() throws Exception {
        VersionResource.VersionInfo info = resourceWithBuild("dev").version();
        assertThat(info.engine()).isEqualTo(io.aster.policy.parser.CompileIdentity.coreEngineVersion());
        assertThat(info.toolchain()).contains("core=java");
    }

    @Test
    @DisplayName("engine 永不为空/null（jar 无 Implementation-Version 时落 dev）")
    void engineNeverBlank() throws Exception {
        VersionResource.VersionInfo info = resourceWithBuild("dev").version();
        assertThat(info.engine()).isNotNull().isNotBlank();
    }
}
