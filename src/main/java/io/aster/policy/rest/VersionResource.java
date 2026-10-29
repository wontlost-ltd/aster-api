package io.aster.policy.rest;

import io.aster.policy.parser.CompileIdentity;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.aster.policy.stability.ToolchainIdentityProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * 后端版本信息 —— 供 aster-cloud 的「关于」弹框展示。
 *
 * <p><b>为什么不复用 /q/health</b>：health 只报 UP/DOWN，不含版本；而 evaluate 响应里的
 * {@code runtimeToolchainId} 是**每次执行**的伴生字段，拿它当版本源需要先跑一次策略，
 * 语义不符。故单开一个只读端点。
 *
 * <p><b>数据来源（都不新造）</b>：
 * <ul>
 *   <li>{@code engine} —— {@link CompileIdentity#coreEngineVersion()}：运行时从 core jar 的
 *       {@code Implementation-Version} 读，即**实际加载的**引擎版本（而非 build.gradle 里可能陈旧的声明值）。
 *       toolchainId 的 core 段只是引擎名（java），不再携带版本。</li>
 *   <li>{@code toolchain} —— 完整工具链身份串，与写进 Execution 的
 *       {@code runtimeToolchainId} **同一口径**，便于把弹框显示值与审计记录对上。</li>
 * </ul>
 *
 * <p><b>授权</b>：{@link Role#VIEWER}（最低权限）。版本信息只对**已登录**用户可见——
 * 故意**不做成公开端点**：公开化需要同时在 {@code RequestSignatureFilter} 与
 * {@code TenantFilter} 两处 perimeter 登记豁免（见 MessagesManifestResource 的注释），
 * 为一个展示用弹框去改安全边界不划算。cloud 侧用既有 signRequest 在服务端取。
 */
@Path("/api/v1/version")
@Produces(MediaType.APPLICATION_JSON)
@RequireRole(Role.VIEWER)
public class VersionResource {

    @Inject
    ToolchainIdentityProvider toolchain;

    /**
     * 版本信息。
     *
     * @param engine    引擎（aster-lang-core）版本，取不到时为 {@code "dev"}
     * @param toolchain 完整工具链身份串（abi/core/validator/resolver/casefold/build）
     */
    public record VersionInfo(String engine, String toolchain) {}

    @GET
    public VersionInfo version() {
        return new VersionInfo(CompileIdentity.coreEngineVersion(), toolchain.currentToolchainId());
    }
}
