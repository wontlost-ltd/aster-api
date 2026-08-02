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
 * <p>重点验证 {@code core=} 段解析：弹框显示的引擎版本必须与写进 Execution 的
 * runtimeToolchainId **同源**，故解析必须对各种真实/异常形态都稳。
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
    @DisplayName("engine 恰为 toolchain 串里的 core= 段（两者同源，不各自实现）")
    void engineMatchesCoreSegment() throws Exception {
        VersionResource.VersionInfo info = resourceWithBuild("dev").version();

        // 从串里独立解析一次，与 record 的 engine 字段比对——防将来有人把 engine
        // 改成另一处反射而与 runtimeToolchainId 漂移。
        String expected = null;
        for (String seg : info.toolchain().split(";")) {
            if (seg.startsWith("core=")) {
                expected = seg.substring("core=".length());
            }
        }
        assertThat(expected).isNotNull();
        assertThat(info.engine()).isEqualTo(expected);
    }

    @Test
    @DisplayName("engine 永不为空/null（jar 无 Implementation-Version 时落 dev）")
    void engineNeverBlank() throws Exception {
        VersionResource.VersionInfo info = resourceWithBuild("dev").version();
        assertThat(info.engine()).isNotNull().isNotBlank();
    }
}
