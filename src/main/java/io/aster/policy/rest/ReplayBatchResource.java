package io.aster.policy.rest;

import io.aster.billing.PlanGateService;
import io.aster.policy.replay.batch.ReplayBatchEntity;
import io.aster.policy.replay.batch.ReplayBatchStatus;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What-If 批次 REST（ADR 0034 S3）。
 *
 * <p><b>三个端点</b>：创建批次 / 查询进度 / 取结果。
 *
 * <p><b>两条容易混淆的拒绝语义（§7.2）</b>：
 * <ul>
 *   <li><b>403</b>——租户**没有这个功能**（free 档 {@code concurrentReplayBatches=0}）。
 *       前端应引导升级。</li>
 *   <li><b>409</b>——有功能但**现在不能再开一个**（已达并发上限）。
 *       前端应提示等待，并给出当前批次进度。</li>
 * </ul>
 * ★两者状态码**必须不同**：混用会让前端无法区分「去升级」与「等一会儿」。
 */
@Path("/api/v1/policies/{policyId}/whatif-batches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class ReplayBatchResource {

    /** 保留期（§7.3）：到期转 EXPIRED 并清空聚合结果。 */
    private static final int RETENTION_DAYS = 30;

    @Inject
    RequestIdentityResolver identityResolver;

    @Inject
    PlanGateService planGateService;

    /** 创建批次的请求体。 */
    public record CreateRequest(
        String baseVersionId,
        String targetVersionId,
        /** LAST_MONTH / LAST_QUARTER / LAST_HALF_YEAR / LAST_YEAR / CUSTOM */
        String windowKind,
        /** CUSTOM 时必填，ISO-8601 日期（如 2026-07-01） */
        String customFrom,
        String customTo
    ) {
    }

    /**
     * 创建批次。
     *
     * <p>顺序刻意如此：**先查权益（403）、再查并发（409）**。
     * 反过来的话，free 租户在自己已有批次时会拿到 409——
     * 提示「等一会儿」，而实际上他等多久都没用。
     */
    @POST
    @Transactional
    public Response create(@PathParam("policyId") String policyId, CreateRequest req) {
        String tenantId = identityResolver.tenantId();
        String userId = identityResolver.performedBy();

        // ── 1. 权益：有没有这个功能 ────────────────────────────────────
        var plan = planGateService.lookupPlan(tenantId);
        if (!plan.allowsReplayBatch()) {
            // ★403 而非 409：这不是「现在不行」，是「你没买这个功能」
            return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of(
                    "error", "whatif_not_entitled",
                    "message", "What-If 影响估算需 Pro 及以上套餐",
                    "upgrade", true))
                .build();
        }

        // ── 2. 并发：现在能不能再开一个 ────────────────────────────────
        List<ReplayBatchEntity> active = ReplayBatchEntity.list(
            "userId = ?1 and status in ?2",
            userId, List.of(ReplayBatchStatus.PENDING, ReplayBatchStatus.RUNNING));

        boolean unlimited = plan.hasUnlimitedReplayBatches();
        if (!unlimited && active.size() >= plan.concurrentReplayBatches()) {
            ReplayBatchEntity current = active.get(0);
            // ★409 带上当前批次 id 与进度——让前端能显示「正在跑，还剩 N 条」，
            //   而不是干巴巴一句「请稍后」
            return Response.status(Response.Status.CONFLICT)
                .entity(Map.of(
                    "error", "whatif_batch_in_progress",
                    "message", "已有批次在运行，完成后可再发起",
                    "currentBatchId", current.id.toString(),
                    "plannedCount", current.plannedCount,
                    "completedCount", current.completedCount))
                .build();
        }

        // ── 3. 窗口解析与固化（§3.3）────────────────────────────────────
        ZoneId tz = resolveTenantZone(tenantId);
        Window w;
        try {
            w = resolveWindow(req, tz);
        } catch (IllegalArgumentException bad) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "invalid_window", "message", bad.getMessage()))
                .build();
        }

        ReplayBatchEntity batch = new ReplayBatchEntity();
        batch.id = UUID.randomUUID();
        batch.tenantId = tenantId;
        batch.userId = userId;
        batch.policyId = policyId;
        batch.baseVersionId = req.baseVersionId();
        batch.targetVersionId = req.targetVersionId();
        batch.windowKind = w.kind();
        batch.windowLabel = w.label();
        batch.windowTimezone = tz.getId();
        batch.windowFrom = w.from();
        batch.windowTo = w.to();
        // plannedCount 在 worker 拉完窗口后回填；创建时未知
        batch.plannedCount = 0;
        batch.toolchainId = "pending";
        batch.expiresAt = Instant.now().plus(java.time.Duration.ofDays(RETENTION_DAYS));
        batch.persist();

        Log.infof("创建 What-If 批次 %s：policy=%s 窗口=%s [%s, %s)",
            batch.id, policyId, w.label(), w.from(), w.to());

        return Response.status(Response.Status.ACCEPTED)
            .entity(Map.of(
                "batchId", batch.id.toString(),
                "status", batch.status.name(),
                "windowLabel", batch.windowLabel,
                "windowFrom", batch.windowFrom.toString(),
                "windowTo", batch.windowTo.toString()))
            .build();
    }

    /**
     * 查询批次进度/结果。
     *
     * <p>★<b>拒答的批次不返回任何数字</b>：FAILED 只给失败原因分布，
     * 不给 completedCount——那会诱导前端自行计算成功率（§1.1）。
     */
    @GET
    @jakarta.ws.rs.Path("/{batchId}")
    public Response get(@PathParam("policyId") String policyId,
                        @PathParam("batchId") String batchId) {
        String userId = identityResolver.performedBy();

        UUID id;
        try {
            id = UUID.fromString(batchId);
        } catch (IllegalArgumentException bad) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        // ★查询带 userId：租户隔离。不属于本用户的批次一律 404（不是 403）——
        //   403 会泄露「这个批次存在」，让端点变成存在性探针。
        ReplayBatchEntity batch = ReplayBatchEntity
            .find("id = ?1 and userId = ?2", id, userId)
            .firstResult();
        if (batch == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("batchId", batch.id.toString());
        body.put("status", batch.status.name());
        body.put("windowLabel", batch.windowLabel);
        body.put("windowFrom", batch.windowFrom.toString());
        body.put("windowTo", batch.windowTo.toString());
        body.put("plannedCount", batch.plannedCount);

        switch (batch.status) {
            case PENDING, RUNNING -> {
                // 进度：只给「跑了几条」，不给「成功几条」——
                // 后者会让用户在批次跑完前自行推断结论（§7.4）
                body.put("processedCount", batch.completedCount + batch.failedCount);
            }
            case COMPLETED -> body.put("result", batch.resultSummary);
            case FAILED -> {
                // ★拒答：只给失败原因分布，不给任何计数
                body.put("failureReasons", batch.failureReasons);
                body.put("rejected", true);
            }
            case EXPIRED -> body.put("expired", true);
        }
        return Response.ok(body).build();
    }

    // ── 窗口解析 ───────────────────────────────────────────────────────

    private record Window(String kind, String label, Instant from, Instant to) {
    }

    /**
     * 把窗口档位解析成**绝对时刻**（§3.3）。
     *
     * <p>★右边界取**当天 00:00**（不含当天）：边界指向已封闭的过去，
     * 正在写入的数据天然落在窗口外。
     * ★不存相对表达：跨零点会让左边界前移一天，且窗口边界是**结果的一部分**，
     * 不固化则结果不可复现。
     */
    private static Window resolveWindow(CreateRequest req, ZoneId tz) {
        LocalDate today = LocalDate.now(tz);
        Instant to = today.atStartOfDay(tz).toInstant();   // 当天 00:00，不含当天

        String kind = req.windowKind() == null ? "LAST_MONTH" : req.windowKind();
        return switch (kind) {
            case "LAST_MONTH" -> new Window(kind, "最近一个月",
                today.minusMonths(1).atStartOfDay(tz).toInstant(), to);
            case "LAST_QUARTER" -> new Window(kind, "最近一个季度",
                today.minusMonths(3).atStartOfDay(tz).toInstant(), to);
            case "LAST_HALF_YEAR" -> new Window(kind, "最近半年",
                today.minusMonths(6).atStartOfDay(tz).toInstant(), to);
            case "LAST_YEAR" -> new Window(kind, "最近一年",
                today.minusYears(1).atStartOfDay(tz).toInstant(), to);
            case "CUSTOM" -> resolveCustom(req, tz, today);
            default -> throw new IllegalArgumentException("不支持的窗口档位：" + kind);
        };
    }

    private static Window resolveCustom(CreateRequest req, ZoneId tz, LocalDate today) {
        if (req.customFrom() == null || req.customTo() == null) {
            throw new IllegalArgumentException("CUSTOM 窗口必须提供 customFrom 与 customTo");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(req.customFrom());
            to = LocalDate.parse(req.customTo());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD");
        }
        // ★不能选未来：服务端独立校验，不依赖前端 disable（§7.1）
        if (to.isAfter(today)) {
            throw new IllegalArgumentException("窗口终点不能晚于今天——未来没有数据");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("窗口起点必须早于终点");
        }
        return new Window("CUSTOM",
            from + " 至 " + to,
            from.atStartOfDay(tz).toInstant(),
            to.atStartOfDay(tz).toInstant());
    }

    /**
     * 租户时区。未配置回退 UTC——★口径会随结果一起呈现，不静默。
     */
    private static ZoneId resolveTenantZone(String tenantId) {
        // TODO(ADR-0034-§3.3)：接租户配置。当前统一 UTC 并在 windowTimezone 列留痕，
        //   使「按哪个时区算的当天」对用户可见，而不是藏在代码里。
        return ZoneId.of("UTC");
    }
}
