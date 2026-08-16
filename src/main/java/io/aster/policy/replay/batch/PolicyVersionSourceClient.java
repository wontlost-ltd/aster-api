package io.aster.policy.replay.batch;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.aster.billing.PlanGateConfig;
import io.quarkus.logging.Log;
import io.vertx.core.json.JsonObject;

/**
 * 向 aster-cloud 取「策略版本源码」。
 *
 * <h2>为什么必须跨服务取，而不是查本地库</h2>
 *
 * What-If 重放需要目标版本的源码。原实现在**本服务的** {@code policy_versions}
 * 表里找，但那张表与 cloud 的 {@code PolicyVersion} **不是同一批数据**：
 *
 * <ul>
 *   <li>cloud 侧 id 是 UUID（text）；本服务是 bigint 自增</li>
 *   <li>本服务的 {@code policy_id} 里混着
 *       {@code aster.test.failure.failingPolicy.tenant-batch-partial}
 *       这类**执行期缓存键**，并非都是策略 UUID</li>
 *   <li>实测行数 54 vs 57，本就不是镜像关系</li>
 * </ul>
 *
 * 于是 UI 传来的 UUID 在本服务里 {@code Long.parseLong} 必定失败，
 * 每一次 What-If 都以 {@code TARGET_VERSION_MISSING} 收场——
 * **该功能从 UI 上从未成功过**（2026-08-17 生产实测确认）。
 *
 * <p>版本历史的**系统真相在 aster-cloud**。本服务不该猜，也不该维护副本，
 * 而应向真相方索取。这与 {@link ExecutionWindowClient} 去 cloud 取执行数据
 * 是同一条既有通道、同一套签名，不新增机制。
 *
 * <h2>为什么不把源码存进批次实体</h2>
 *
 * 看似可以在创建批次时把源码冻结进 {@code ReplayBatchEntity}，省掉运行期调用。
 * 但那会把**用户策略源码**复制一份到本服务的库里，扩大资产面——与 ADR 0034 §3.1
 * 「冻结表只存 id 与基线、不存 input」的取舍同源。故运行期取，用完即弃。
 */
@ApplicationScoped
public class PolicyVersionSourceClient {

    @Inject
    PlanGateConfig config;

    // 与 ExecutionWindowClient 共用连接池，不各自 create（P0-R19）
    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;

    /** 一个策略版本的源码。 */
    public record VersionSource(String versionId, String policyId, int version, String content) {
    }

    /**
     * 按 versionId 取源码。
     *
     * <p>★必须带 userId：cloud 侧据此做租户隔离（join policies 过滤 userId）。
     * 不传就拿不到数据——这是有意的 fail-closed，不是可省参数。
     *
     * @return 空 = 版本不存在**或不属于该用户**（cloud 侧刻意不区分二者，
     *         避免把「存在但不是你的」变成可探测的存在性信号）
     */
    public Optional<VersionSource> fetch(String versionId, String userId) {
        if (versionId == null || versionId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        URI baseUri = URI.create(config.cloudInternalUrl());
        int port = baseUri.getPort() == -1
            ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
            : baseUri.getPort();
        boolean ssl = "https".equals(baseUri.getScheme());
        String path = "/api/internal/policy-versions";
        String query = "versionId=" + enc(versionId) + "&userId=" + enc(userId);

        // ★走共享的 InternalCallSigner（v2 canonical：nonce + bodySha256）。
        //   cloud 侧已关闭 v1 兼容窗口，手写 v1 会被拒——这是跨服务契约，
        //   单测测不出来，必须用同一个签名器。
        var signed = io.aster.security.internal.InternalCallSigner.sign(
            config.hmacKey().orElse(""), "GET", path, "");

        var resp = sharedWebClient.client()
            .get(port, baseUri.getHost(), path + "?" + query)
            .ssl(ssl)
            .timeout(15_000)
            .putHeader("X-Aster-Timestamp", signed.timestamp())
            .putHeader("X-Aster-Nonce", signed.nonce())
            .putHeader("X-Aster-Signature", signed.signature())
            .send()
            .toCompletionStage().toCompletableFuture().join();

        if (resp.statusCode() == 404) {
            // 版本不存在或不属于该用户——调用方据此抛 TargetVersionMissingException。
            Log.warnf("目标版本在 cloud 侧不存在或不属于该用户：versionId=%s", versionId);
            return Optional.empty();
        }
        if (resp.statusCode() != 200) {
            // ★其它非 200 **不能**降级成「版本不存在」：那会把一次网络/鉴权故障
            //   谎报成用户的数据问题，把人支去排查自己的策略。抛错，让上层归 UNKNOWN。
            Log.errorf("取策略版本源码失败：status=%d versionId=%s", resp.statusCode(), versionId);
            throw new IllegalStateException(
                "取策略版本源码失败，status=" + resp.statusCode());
        }

        JsonObject o = resp.bodyAsJsonObject();
        return Optional.of(new VersionSource(
            o.getString("versionId"),
            o.getString("policyId"),
            o.getInteger("version", 0),
            o.getString("content")));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
