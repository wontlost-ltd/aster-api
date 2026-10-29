package io.aster.policy.stability;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 工具链身份提供者（ADR 0022/0030）——「用哪版引擎编译/执行」的规范化身份串。
 *
 * <p>格式（ADR 0051 §9）：{@code abi=1.1;core=<core 引擎版本>;validator=<v>;resolver=1;casefold=15.1;build=<runtimeBuild>}，
 * 即 {@link io.aster.policy.parser.CompileIdentity#toolchainId()} 追加进程级 build 段——编译缓存键与对外身份同源。
 * core 段取 core jar 的 Implementation-Version，未 stamp 时为 dev（由 {@link ToolchainIdentityGuard} 发现）。
 * <ul>
 *   <li>{@code sourceToolchainId}：策略版本创建/编译时的工具链（进 source envelope，
 *       PolicyVersionService 已用）。</li>
 *   <li>{@code runtimeToolchainId}：本次 evaluate **实际执行**时的工具链（回放地基需要，
 *       ADR 0030：旧策略版本在新运行时执行时两者可能不同，正是要记录它的原因）。</li>
 * </ul>
 * 两者格式相同、语义不同，不要求总相等。抽成共享组件（原 PolicyVersionService private），
 * 供 evaluate 响应契约 + 版本创建统一口径（Codex 设计）。
 */
@ApplicationScoped
public class ToolchainIdentityProvider {

    @ConfigProperty(name = "aster.runtime.build", defaultValue = "dev")
    String runtimeBuild;

    /** 当前运行时工具链身份（evaluate 执行侧 + 版本创建侧统一口径）。 */
    public String currentToolchainId() {
        return io.aster.policy.parser.CompileIdentity.toolchainId() + ";build=" + runtimeBuild;
    }
}
