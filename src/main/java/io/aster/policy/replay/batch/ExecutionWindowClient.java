package io.aster.policy.replay.batch;

import io.aster.billing.PlanGateConfig;
import io.quarkus.logging.Log;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 aster-cloud 分页拉取窗口内可重跑执行（ADR 0034 §3.0）。
 *
 * <p>调 cloud 的 {@code /api/internal/executions/window}，
 * 与 {@code SnapshotWarmupService} 是同一个模式（cursor 分页 + HMAC 签名），
 * <b>不是新机制</b>——反向通道早已存在，api 已在调 cloud 的 13 个内部端点。
 *
 * <p><b>为什么由 api 拉而不是 cloud 推</b>：若 cloud 逐条调 api 重跑，
 * 万条批次约 49MB / 10000 次往返，其中 38MB 是同一份目标版本源码的重复传输。
 * api 拉走输入后进程内直调重跑：万条 ≈13 秒、零往返、零重传。
 */
@ApplicationScoped
public class ExecutionWindowClient {

    /** 单页条数。与 cloud 端点默认值一致。 */
    private static final int PAGE_LIMIT = 1000;

    /**
     * 分页上限。★不是「性能调优参数」，是**失控保护**：
     * 万一 cursor 逻辑有 bug 导致不前进，这个上限会让批次失败而不是无限拉。
     * 100 页 × 1000 条 = 10 万条，远超任何合理策略的执行量。
     */
    private static final int MAX_PAGES = 100;

    @Inject
    PlanGateConfig config;

    // P0-R19：WebClient 连接池收敛到 SharedWebClient，不各自 create
    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;

    /** 一条待重跑的历史执行。 */
    public record WindowedExecution(
        String executionId,
        Object input,
        String decision,
        boolean success,
        String functionName,
        String locale,
        JsonObject aliasSetJson,
        String policyVersionRowId
    ) {
        /**
         * 基线是否「通过」。
         *
         * <p>★用 {@code decision} 而非 {@code success}：后者是旧语义
         * （值输出也算 false），会把「返回一个数字」误判成拒绝。
         * {@code decision} 为空的历史行回退到 {@code success}。
         */
        public boolean baseApproved() {
            if (decision != null && !decision.isBlank()) {
                return "approved".equals(decision);
            }
            return success;
        }
    }

    /**
     * 拉取窗口内**全部** REPLAYABLE 执行。
     *
     * <p>★一次性全拉而不是流式处理：批次要先知道 {@code plannedCount} 才能
     * 判定「全量成功」（§1.1）。边拉边跑的话，中途拉取失败会让 planned 本身
     * 就不可信——那时无论结果如何都不该出数字。
     *
     * @throws IllegalStateException 拉取失败或超过分页上限
     */
    public List<WindowedExecution> fetchWindow(
        String policyId, String userId, Instant from, Instant to) {

        List<WindowedExecution> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        while (pages < MAX_PAGES) {
            JsonObject page = fetchPage(policyId, userId, from, to, cursor);
            JsonArray rows = page.getJsonArray("executions", new JsonArray());
            for (int i = 0; i < rows.size(); i++) {
                all.add(toExecution(rows.getJsonObject(i)));
            }
            cursor = page.getString("nextCursor");
            pages++;
            if (cursor == null) {
                return all;
            }
        }
        // 到这里说明 cursor 一直非空——要么数据真的超过 10 万条，
        // 要么 cursor 没有前进。两种情况都不该静默截断出数字。
        throw new IllegalStateException(
            "执行窗口分页超过上限 " + MAX_PAGES + " 页（已拉 " + all.size() + " 条）——"
                + "拒绝截断，否则会用子集冒充全量");
    }

    private static WindowedExecution toExecution(JsonObject o) {
        return new WindowedExecution(
            o.getString("id"),
            o.getValue("input"),
            o.getString("decision"),
            o.getBoolean("success", false),
            o.getString("functionName"),
            o.getString("locale"),
            o.getJsonObject("aliasSetJson"),
            o.getString("policyVersionRowId"));
    }

    private JsonObject fetchPage(
        String policyId, String userId, Instant from, Instant to, String cursor) {

        URI baseUri = URI.create(config.cloudInternalUrl());
        int port = baseUri.getPort() == -1
            ? ("https".equals(baseUri.getScheme()) ? 443 : 80)
            : baseUri.getPort();
        boolean ssl = "https".equals(baseUri.getScheme());
        String path = "/api/internal/executions/window";

        StringBuilder q = new StringBuilder()
            .append("policyId=").append(enc(policyId))
            .append("&userId=").append(enc(userId))
            .append("&from=").append(enc(from.toString()))
            .append("&to=").append(enc(to.toString()))
            .append("&limit=").append(PAGE_LIMIT);
        if (cursor != null) {
            q.append("&cursor=").append(enc(cursor));
        }

        // ★必须用 v2 canonical（nonce + bodySha256）：cloud 侧已于 2026-08-01
        //   关闭 v1 兼容窗口（v1 不绑 body/nonce，300s 时钟窗内可原样重放）。
        //   手写 v1 签名会被 cloud 以 invalid_signature 拒绝——这是端到端验证抓到的，
        //   单测测不出来，因为它是**跨服务契约**。
        //   故走共享的 InternalCallSigner，不各自手写。
        var signed = io.aster.security.internal.InternalCallSigner.sign(
            config.hmacKey().orElse(""), "GET", path, "");

        var resp = sharedWebClient.client()
            .get(port, baseUri.getHost(), path + "?" + q)
            .ssl(ssl)
            .timeout(15_000)
            .putHeader("X-Aster-Timestamp", signed.timestamp())
            .putHeader("X-Aster-Nonce", signed.nonce())
            .putHeader("X-Aster-Signature", signed.signature())
            .send()
            .toCompletionStage().toCompletableFuture().join();

        if (resp.statusCode() != 200) {
            Log.errorf("拉取执行窗口失败：status=%d policy=%s", resp.statusCode(), policyId);
            throw new IllegalStateException(
                "拉取执行窗口失败，status=" + resp.statusCode());
        }
        return resp.bodyAsJsonObject();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

}
