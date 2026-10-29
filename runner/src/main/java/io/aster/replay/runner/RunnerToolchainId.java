package io.aster.replay.runner;

/**
 * runner 侧 toolchainId 复现（与 aster-api {@code ToolchainIdentityProvider} 同源：
 * {@link io.aster.policy.parser.CompileIdentity#toolchainId()} 追加 {@code ;build=}）。
 * ★build 字段来自 env {@code ASTER_RUNTIME_BUILD}——与 aster-api 镜像天然不同，正因如此
 * parity 比对必须排除 toolchainId（见 spec toolchainId 归一）。仅诊断，不进比对判定。
 */
public final class RunnerToolchainId {
    private RunnerToolchainId() {}

    public static String current() {
        String build = System.getenv().getOrDefault("ASTER_RUNTIME_BUILD", "dev");
        return io.aster.policy.parser.CompileIdentity.toolchainId() + ";build=" + build;
    }
}
