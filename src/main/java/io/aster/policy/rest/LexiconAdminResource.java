package io.aster.policy.rest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.aster.policy.lexicon.HotPlugLexiconLoader;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 运维端：上传 / 删除语言包 jar，或软上/下线已注册 lexicon。
 *
 * <p><b>HMAC 验签（C2 + R3-C2b + R4）</b>：客户端把请求做 canonicalization 后用共享密钥签名。
 * canonical-string 含 8 行（与 verifyHmac() 实现一致）：
 * <pre>
 *   method + "\n"
 *   path + "\n"
 *   timestamp + "\n"
 *   nonce + "\n"
 *   content-type-or-empty + "\n"
 *   content-length-or-zero + "\n"
 *   body-sha256-hex-or-empty + "\n"
 *   sanitized-filename-or-empty
 * </pre>
 * 服务端：
 * <ul>
 *   <li>时钟漂移 &gt; 5 分钟 → 拒绝（防重放）</li>
 *   <li>nonce 一旦观察到就消费（即使签名失败）→ 防 nonce-grabbing DoS</li>
 *   <li>locale id 必须是 BCP-47 形状，filename 必须匹配 [a-zA-Z0-9._-]</li>
 *   <li>对上传请求，验证 sha256 与实际接收 body 一致 → 防止替换 jar 内容</li>
 * </ul>
 *
 * <p><b>Jar 校验（C3）</b>：上传 jar 落盘前必须通过：
 * <ul>
 *   <li>大小 ≤ 50 MiB</li>
 *   <li>合法 ZIP 结构，entry 数 ≤ 50,000，单 entry 解压 ≤ 20 MiB，总解压 ≤ 200 MiB</li>
 *   <li>必须包含 META-INF/services/aster.core.lexicon.LexiconPlugin</li>
 *   <li>可选 SHA-256 allowlist（{@code aster.lexicon.upload.sha256-allowlist}）</li>
 * </ul>
 */
@Path("/api/v1/admin/lexicons")
public class LexiconAdminResource {

    private static final Logger LOG = Logger.getLogger(LexiconAdminResource.class);
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final long MAX_JAR_BYTES = 50L * 1024 * 1024;
    private static final long MAX_ENTRY_UNCOMPRESSED_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 200L * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 50_000;
    private static final String REQUIRED_SPI_PATH = "META-INF/services/aster.core.lexicon.LexiconPlugin";

    @ConfigProperty(name = "aster.plan-gate.hmac-key")
    Optional<String> hmacKey;

    @ConfigProperty(name = "aster.lexicon.hotplug.dir",
        defaultValue = "/var/aster/lexicons/jars")
    String hotplugDir;

    /** 可选受信 jar SHA-256 allowlist（逗号分隔 hex）。 */
    @ConfigProperty(name = "aster.lexicon.upload.sha256-allowlist")
    Optional<String> shaAllowlist;

    /**
     * 是否强制要求 SHA-256 allowlist（纵深防御，ADR 0018 安全加固）。
     *
     * <p>热插拔上传走 {@code URLClassLoader} 加载 jar = 任意代码执行。HMAC 签名是第一道
     * 门（默认强制），但 HMAC key 泄露后若无 allowlist 兜底即 RCE。本开关在生产 profile
     * 默认 {@code true}（fail-closed）：allowlist 为空时**拒绝上传**而非放行；dev/test 默认
     * {@code false}（开发便利，沿用旧 fail-open 行为）。profile 默认值见 application.properties。
     */
    @ConfigProperty(name = "aster.lexicon.upload.require-allowlist", defaultValue = "false")
    boolean requireAllowlist;

    /** Nonce 缓存（key = nonce hex, value = 占位），TTL 与 clock skew 一致。 */
    private final Cache<String, Boolean> usedNonces = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(MAX_CLOCK_SKEW_SECONDS + 60))
        .maximumSize(50_000)
        .build();

    /**
     * R8-Backend-3：同步触发 hot-plug 加载，让 upload 响应能精确反馈结果。
     */
    @Inject
    HotPlugLexiconLoader hotPlugLoader;

    /**
     * R16-Architectural-5：micrometer counter + 结构化审计日志。
     */
    @Inject
    io.aster.policy.lexicon.LexiconAdminMetrics metrics;

    /**
     * 可用性（软上/下线）跨副本一致性服务：disable/enable 经它持久到 Redis + 广播，
     * 让 K3S 多副本同步生效（否则只改命中的那一个副本 → /api/v1/lexicons 各副本不一致）。
     */
    @Inject
    io.aster.policy.lexicon.LexiconAvailabilityService availabilityService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    // `try (var ignored = xLock.get())` 是“持有即用”的分布式锁惯用法（try 退出即
    // 释放锁），javac 的 [try] 警告对此为误报——抑制以保持编译零警告。
    @SuppressWarnings("try")
    public Response upload(
        @Context HttpHeaders headers,
        @RestForm("file") @PartType(MediaType.APPLICATION_OCTET_STREAM) FileUpload file
    ) {
        if (file == null || file.fileName() == null) {
            throw badRequest("missing_file", "multipart field 'file' is required");
        }
        if (file.size() > MAX_JAR_BYTES) {
            throw badRequest("file_too_large", "jar exceeds 50 MiB");
        }
        // R3-C2b：sanitize 拒 CR/LF/control，再校验 .jar 后缀 + 严格正则
        String fileName = sanitizeFileName(file.fileName());
        if (!fileName.endsWith(".jar")) {
            throw badRequest("invalid_extension", "lexicon pack must be .jar");
        }

        // 读 body 一次，做 SHA-256；C2 要求 HMAC 必须签 body digest
        byte[] body;
        try (InputStream is = Files.newInputStream(file.uploadedFile())) {
            body = is.readAllBytes();
        } catch (IOException e) {
            throw ioError("io_error", "failed to read uploaded body");
        }
        String bodySha = sha256Hex(body);

        // R3-C2b: HMAC 必须包含 sanitized filename（防止 MITM 改 filename 不改 body 而绕过验签）
        verifyHmac(headers, "POST", "/api/v1/admin/lexicons",
            file.contentType(), body.length, bodySha, fileName);

        // SHA-256 allowlist（纵深防御，ADR 0018）。判定逻辑抽到纯静态方法以便单测。
        // **置于 ZIP/SPI 解析之前（Codex 审查）**：allowlist 只需 bodySha，不依赖 jar 内容；
        // 提前拒绝可减少 HMAC key 泄露后攻击者反复触发 ZIP 解压/限额逻辑的成本。
        AllowlistVerdict verdict =
            checkAllowlist(shaAllowlist.orElse(null), requireAllowlist, bodySha);
        switch (verdict.outcome()) {
            case REQUIRED -> throw forbidden("allowlist_required",
                "aster.lexicon.upload.sha256-allowlist must be configured in production; "
                    + "empty allowlist rejected (set aster.lexicon.upload.require-allowlist=false to opt out)");
            case NOT_LISTED -> throw forbidden("not_in_allowlist",
                "sha256 " + bodySha + " not in aster.lexicon.upload.sha256-allowlist");
            case ALLOWED -> { /* 通过 */ }
        }

        // C3: jar 结构 + SPI descriptor 校验（allowlist 通过后才解析 jar）
        validateJarStructure(body, fileName);

        // R11-Backend-Critical + R12-Backend-Major-2：双层锁。
        //   1) JVM-local striped ReentrantLock —— 同 pod 内同 fileName 串行
        //   2) FS-level FileChannel.tryLock —— 跨 pod (共享 PVC) 同 fileName 串行
        // 取锁顺序：JVM 锁先（避免同 pod 内自己 race 自己拿 FS 锁），FS 锁后。
        // 释放顺序相反。
        java.util.concurrent.locks.ReentrantLock fileLock = hotPlugLoader.acquireUploadLock(fileName);
        fileLock.lock();
        try {
            Optional<HotPlugLexiconLoader.CrossReplicaLock> xLock =
                hotPlugLoader.tryAcquireCrossReplicaLock(fileName);
            if (xLock.isEmpty()) {
                LOG.warnf("Cross-replica lock contention for %s — another pod is uploading this file",
                    fileName);
                metrics.recordUpload("cross_replica_busy", fileName, bodySha, null, null);
                return Response.status(409).entity(Map.of(
                    "status", "rejected",
                    "outcome", "cross_replica_busy",
                    "message", "another replica is currently uploading this jar; retry later",
                    "fileName", fileName,
                    "sha256", bodySha
                )).type(MediaType.APPLICATION_JSON).build();
            }
            try (var ignored = xLock.get()) {
                return commitAndLoad(fileName, body, bodySha);
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * R10-Backend-C1+C2 + R11: commit-then-load with backup rollback.
     *
     * <p>关键修正：R9 把 loadJarSync(stagingPath) 放在 commit 之前 —— URLClassLoader 持有
     * staging 路径，commit Files.move 后那个路径已删除/移走，loader 的 jar 文件就丢了。
     * R10 改顺序：(1) 写 staging 用 nonce-suffix 防并发覆盖 (2) 备份现有 canonical
     * (3) atomic-move staging → canonical (4) loadJarSync(canonical) (5) 失败时 atomic-move
     * backup → canonical 恢复，并通知 loader 重读。
     *
     * <p>R11：caller 必须在 per-fileName 锁内调用此方法。
     */
    private Response commitAndLoad(String fileName, byte[] body, String bodySha) {
        java.nio.file.Path pendingDir = java.nio.file.Path.of(hotplugDir, "pending");
        // R10-Backend-C2: per-request unique staging name —— 即便有锁，nonce 也能让
        // stale pending 文件不冲突（崩溃恢复 / 多 replica 场景）。
        String nonce = java.util.UUID.randomUUID().toString();
        java.nio.file.Path stagingPath = pendingDir.resolve(fileName + "." + nonce + ".staging");
        java.nio.file.Path target = java.nio.file.Path.of(hotplugDir, fileName);
        java.nio.file.Path backupPath = pendingDir.resolve(fileName + "." + nonce + ".backup");

        boolean hadCanonical = Files.exists(target);
        try {
            Files.createDirectories(pendingDir);
            Files.createDirectories(target.getParent());
            Files.write(stagingPath, body);
            LOG.infof("Lexicon jar staged: %s (sha256=%s, %d bytes)", stagingPath, bodySha, body.length);
            // R10-Backend-C1 step 2: 备份现有 canonical（如果存在）以便回滚
            if (hadCanonical) {
                Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.errorf(e, "Failed to stage uploaded lexicon jar %s", fileName);
            deleteQuietly(stagingPath);
            deleteQuietly(backupPath);
            metrics.recordUpload("io_error", fileName, bodySha, null, null);
            throw ioError("io_error", "failed to stage jar");
        }

        // R10-Backend-C1 step 3: commit to canonical path BEFORE load
        try {
            Files.move(stagingPath, target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to atomic-move staging→canonical for %s", fileName);
            deleteQuietly(stagingPath);
            deleteQuietly(backupPath);
            metrics.recordUpload("io_error", fileName, bodySha, null, null);
            throw ioError("io_error", "failed to commit jar to hotplug dir");
        }

        // R10-Backend-C1 step 4: 现在 loader 从 canonical 加载，path 与磁盘文件一致
        HotPlugLexiconLoader.LoadResult result = hotPlugLoader.loadJarSync(target);

        if (!result.ok()) {
            // R10-Backend-C1 step 5: 失败 → 用 backup 恢复 canonical，再让 loader 重读旧 jar
            LOG.warnf("Rejected lexicon jar %s (outcome=%s) — restoring backup",
                fileName, result.outcome());
            if (hadCanonical) {
                try {
                    Files.move(backupPath, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                    // 通知 loader 重读 canonical（现在是旧字节）
                    HotPlugLexiconLoader.LoadResult restore = hotPlugLoader.loadJarSync(target);
                    if (!restore.ok()) {
                        // R10-Backend-M3 + R11-Minor：备份恢复失败 —— 用 opaque recoveryId
                        // 替代裸路径，避免 admin 响应里泄露 FS 布局。
                        String recoveryId = java.util.UUID.randomUUID().toString();
                        LOG.errorf("CRITICAL: restored backup for %s but loader rejected old jar "
                            + "(outcome=%s). recoveryId=%s target=%s backup=%s",
                            fileName, restore.outcome(), recoveryId, target, backupPath);
                        metrics.recordUpload("backup_restore_load_failed",
                            fileName, bodySha, recoveryId, null);
                        return Response.status(500).entity(Map.of(
                            "status", "rejected",
                            "outcome", "backup_restore_load_failed",
                            "message", "new jar rejected (" + result.outcome()
                                + ") AND reload of backup also failed (" + restore.outcome()
                                + ") — manual recovery required, see server log with recoveryId",
                            "fileName", fileName,
                            "sha256", bodySha,
                            "recoveryId", recoveryId
                        )).type(MediaType.APPLICATION_JSON).build();
                    }
                } catch (IOException ioe) {
                    // R11-Minor：opaque recoveryId 而非裸路径
                    String recoveryId = java.util.UUID.randomUUID().toString();
                    LOG.errorf(ioe,
                        "CRITICAL: failed to restore backup for %s — disk state inconsistent. "
                        + "recoveryId=%s target=%s backup=%s",
                        fileName, recoveryId, target, backupPath);
                    metrics.recordUpload("backup_restore_io_failed",
                        fileName, bodySha, recoveryId, null);
                    return Response.status(500).entity(Map.of(
                        "status", "rejected",
                        "outcome", "backup_restore_io_failed",
                        "message", "new jar rejected (" + result.outcome()
                            + ") AND backup restore IO failed — manual recovery required, "
                            + "see server log with recoveryId for affected paths",
                        "fileName", fileName,
                        "sha256", bodySha,
                        "recoveryId", recoveryId
                    )).type(MediaType.APPLICATION_JSON).build();
                }
            } else {
                // 没有旧 jar 可恢复 —— 删 canonical（它现在是被拒的字节）
                deleteQuietly(target);
            }
            metrics.recordUpload(result.outcome(), fileName, bodySha, null, result.introduced());
            return Response.status(result.httpStatus()).entity(Map.of(
                "status", "rejected",
                "outcome", result.outcome(),
                "message", result.message(),
                "fileName", fileName,
                "sha256", bodySha,
                "registered", result.introduced()
            )).type(MediaType.APPLICATION_JSON).build();
        }

        // R10-Backend-C1 step 6: 成功 → 删 backup
        deleteQuietly(backupPath);
        LOG.infof("Lexicon jar committed and loaded: %s (sha256=%s, outcome=%s)",
            fileName, bodySha, result.outcome());

        // R10-Backend-S1: response status 体现实际语义
        String responseStatus = "ok".equals(result.outcome()) ? "loaded" : "unchanged";
        metrics.recordUpload(result.outcome(), fileName, bodySha, null, result.introduced());
        return Response.status(result.httpStatus()).entity(Map.of(
            "status", responseStatus,
            "outcome", result.outcome(),
            "message", result.message(),
            "fileName", fileName,
            "sha256", bodySha,
            "registered", result.introduced()
        )).type(MediaType.APPLICATION_JSON).build();
    }

    private static void deleteQuietly(java.nio.file.Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件，留着也只是占空间；下次重启清理
        }
    }

    @POST
    @Path("/{id}/disable")
    @Produces(MediaType.APPLICATION_JSON)
    public Response disable(@Context HttpHeaders headers, @PathParam("id") String id) {
        String validatedId = sanitizeLocaleId(id);
        verifyHmac(headers, "POST", "/api/v1/admin/lexicons/" + validatedId + "/disable",
            null, 0, null, null);
        // 经可用性服务：本地 markUnavailable + 持久 Redis + 广播（跨副本一致）。
        boolean changed = availabilityService.disable(validatedId);
        // R17-Major-1：可观测对齐 upload/delete
        String outcome = changed ? "disabled" : "unchanged";
        metrics.recordAvailabilityChange("disable", outcome, validatedId);
        return Response.ok(Map.of(
            "status", outcome,
            "id", validatedId
        )).build();
    }

    @POST
    @Path("/{id}/enable")
    @Produces(MediaType.APPLICATION_JSON)
    public Response enable(@Context HttpHeaders headers, @PathParam("id") String id) {
        String validatedId = sanitizeLocaleId(id);
        verifyHmac(headers, "POST", "/api/v1/admin/lexicons/" + validatedId + "/enable",
            null, 0, null, null);
        // 经可用性服务：本地 markAvailable + Redis SREM + 广播（跨副本一致）。
        boolean changed = availabilityService.enable(validatedId);
        String outcome = changed ? "enabled" : "unchanged";
        metrics.recordAvailabilityChange("enable", outcome, validatedId);
        return Response.ok(Map.of(
            "status", outcome,
            "id", validatedId
        )).build();
    }

    @DELETE
    @Path("/{fileName}")
    @Produces(MediaType.APPLICATION_JSON)
    // 同 upload：`try (var ignored = xLock.get())` 的 [try] 警告为误报，抑制。
    @SuppressWarnings("try")
    public Response delete(@Context HttpHeaders headers, @PathParam("fileName") String fileName) {
        String sanitized = sanitizeFileName(fileName);
        // ★鉴权先于输入校验（api#276）：此前先查 .jar 后验 HMAC，于是**未鉴权**的
        //   调用方能靠 400/403 区分「是不是 .jar」。虽然实测不泄漏文件存在性
        //   （不存在的 .jar 与真实存在的返回同样的 403），也不构成路径穿越，
        //   但同文件其它写端点（disable/enable/upload）都是先鉴权——这里对齐，
        //   让「未通过鉴权就什么都问不出来」成为该资源的一致性质。
        verifyHmac(headers, "DELETE", "/api/v1/admin/lexicons/" + sanitized,
            null, 0, null, sanitized);
        // R3-delete-jar-restrict：DELETE 同样必须是 .jar
        if (!sanitized.endsWith(".jar")) {
            throw badRequest("invalid_extension", "only .jar files may be deleted");
        }
        // R12-Backend-Major-1 + Major-2: DELETE 走同样双层锁 (JVM + 跨 replica)。
        java.util.concurrent.locks.ReentrantLock fileLock = hotPlugLoader.acquireUploadLock(sanitized);
        fileLock.lock();
        try {
            Optional<HotPlugLexiconLoader.CrossReplicaLock> xLock =
                hotPlugLoader.tryAcquireCrossReplicaLock(sanitized);
            if (xLock.isEmpty()) {
                LOG.warnf("Cross-replica lock contention for DELETE %s", sanitized);
                metrics.recordDelete("cross_replica_busy", sanitized);
                return Response.status(409).entity(Map.of(
                    "status", "rejected",
                    "outcome", "cross_replica_busy",
                    "message", "another replica is currently mutating this jar; retry later",
                    "fileName", sanitized
                )).type(MediaType.APPLICATION_JSON).build();
            }
            try (var ignored = xLock.get()) {
                java.nio.file.Path target = java.nio.file.Path.of(hotplugDir, sanitized);
                try {
                    boolean removed = Files.deleteIfExists(target);
                    LOG.infof("Lexicon jar delete: %s (existed=%s)", sanitized, removed);
                    metrics.recordDelete(removed ? "removed" : "not_found", sanitized);
                    return Response.ok(Map.of(
                        "status", removed ? "removed" : "not_found",
                        "fileName", sanitized
                    )).build();
                } catch (IOException e) {
                    metrics.recordDelete("io_error", sanitized);
                    throw ioError("io_error", "failed to delete jar");
                }
            }
        } finally {
            fileLock.unlock();
        }
    }

    // ---------------------------------------------------------- HMAC

    /**
     * 验证 HMAC 签名（C2 + R3）。
     *
     * Canonical string（R3-C2b：加 filename 防止 MITM 替换 filename）：
     * <pre>
     *   method + "\n"
     *   path + "\n"
     *   timestamp + "\n"
     *   nonce + "\n"
     *   content-type-or-empty + "\n"
     *   content-length + "\n"
     *   body-sha256-hex-or-empty + "\n"
     *   sanitized-filename-or-empty
     * </pre>
     *
     * @param contentType POST 时为 multipart content-type；GET/DELETE 用 null
     * @param contentLen 实际 body 长度；非 body 请求传 0
     * @param bodySha256 hex；非 body 请求传 null
     * @param fileName 上传 / 删除的 sanitized filename；其他端点传 null
     */
    private void verifyHmac(HttpHeaders headers, String method, String path,
                            String contentType, long contentLen, String bodySha256,
                            String fileName) {
        if (hmacKey.isEmpty()) {
            throw forbidden("hmac_not_configured",
                "server has no aster.plan-gate.hmac-key set");
        }
        String tsStr = headers.getHeaderString("X-Aster-Timestamp");
        String nonce = headers.getHeaderString("X-Aster-Nonce");
        String sig   = headers.getHeaderString("X-Internal-Signature");
        if (tsStr == null || nonce == null || sig == null) {
            throw forbidden("missing_signature_headers",
                "X-Aster-Timestamp, X-Aster-Nonce, X-Internal-Signature required");
        }

        long ts;
        try { ts = Long.parseLong(tsStr); }
        catch (NumberFormatException e) { throw forbidden("invalid_timestamp", "timestamp not a number"); }

        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > MAX_CLOCK_SKEW_SECONDS) {
            throw forbidden("stale_timestamp", "clock skew exceeds 5 min");
        }

        // R3-C2a + R4：原子 nonce 预约。
        // 关键不变式：一旦 nonce 被观察到（无论签名对错）就消费掉。
        // 不在 invalid_signature 时回滚 —— 否则攻击者可以：
        //   1) 预先发出 bad-sig 请求占住合法 nonce → 让真实请求被 replayed_nonce 拒
        //   2) 然后回滚释放 slot
        // 接受的副作用：拼写错误的客户端浪费 nonce，但 5min TTL 内自然过期。
        Boolean prev = usedNonces.asMap().putIfAbsent(nonce, Boolean.TRUE);
        if (prev != null) {
            throw forbidden("replayed_nonce", "nonce already used within window");
        }

        String ct = contentType == null ? "" : contentType;
        String sha = bodySha256 == null ? "" : bodySha256;
        String fn = fileName == null ? "" : fileName;
        String canonical = method + "\n"
            + path + "\n"
            + ts + "\n"
            + nonce + "\n"
            + ct + "\n"
            + contentLen + "\n"
            + sha + "\n"
            + fn;
        String expected = hmacSha256Hex(hmacKey.get(), canonical);

        if (!constantTimeEqualsHex(expected, sig)) {
            // R4：**不**回滚 nonce —— 否则会被用作 nonce-grabbing DoS
            throw forbidden("invalid_signature", "HMAC mismatch");
        }
    }

    // ---------------------------------------------------------- jar validation (C3)

    private static void validateJarStructure(byte[] body, String fileName) {
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(body))) {
            ZipEntry e;
            int count = 0;
            long totalUncompressed = 0;
            boolean hasSpiDescriptor = false;
            byte[] buf = new byte[8192];

            while ((e = zis.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRY_COUNT) {
                    throw badRequest("zip_too_many_entries",
                        "jar exceeds " + MAX_ENTRY_COUNT + " entries");
                }
                String entryName = e.getName();
                // 拒绝 entry 名穿越（zip slip 防御）
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    throw badRequest("zip_slip", "entry name contains path traversal: " + entryName);
                }
                // 统计真实解压大小（不能信 ZipEntry.getSize()，可被伪造）
                long entryBytes = 0;
                int n;
                while ((n = zis.read(buf)) > 0) {
                    entryBytes += n;
                    if (entryBytes > MAX_ENTRY_UNCOMPRESSED_BYTES) {
                        throw badRequest("zip_entry_too_large",
                            entryName + " exceeds " + MAX_ENTRY_UNCOMPRESSED_BYTES + " bytes");
                    }
                }
                totalUncompressed += entryBytes;
                if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw badRequest("zip_bomb",
                        "total uncompressed exceeds " + MAX_TOTAL_UNCOMPRESSED_BYTES);
                }
                if (REQUIRED_SPI_PATH.equals(entryName)) hasSpiDescriptor = true;
                zis.closeEntry();
            }

            if (count == 0) {
                throw badRequest("empty_jar", "no entries found in " + fileName);
            }
            if (!hasSpiDescriptor) {
                throw badRequest("missing_spi_descriptor",
                    "jar must contain " + REQUIRED_SPI_PATH);
            }
        } catch (WebApplicationException w) {
            throw w;
        } catch (IOException e) {
            throw badRequest("zip_parse_error", "could not parse jar as ZIP");
        }
    }

    /**
     * R4：locale id 白名单。BCP-47 简化形式 + 大小写不敏感。
     * 例如：en-US, zh-CN, de-DE, fr, ja-JP-x-custom（最长 35 字符按 BCP-47）。
     * 防止 enable/disable 路径中嵌入 ../、\r\n 等。
     */
    private static final java.util.regex.Pattern SAFE_LOCALE_ID =
        java.util.regex.Pattern.compile("^[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8}){0,4}$");

    private static String sanitizeLocaleId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw badRequest("invalid_locale_id", "locale id required");
        }
        String trimmed = raw.trim();
        if (!SAFE_LOCALE_ID.matcher(trimmed).matches()) {
            throw badRequest("invalid_locale_id",
                "locale id must match BCP-47 shape (e.g. en-US, zh-CN)");
        }
        return trimmed;
    }

    /**
     * R3-C2b：严格的 filename 校验。
     *
     * 拒绝：
     * <ul>
     *   <li>空白 / 路径穿越（/、\、..）</li>
     *   <li>任何控制字符（含 \r\n\t）—— 防止 log injection 和 canonical-string 歧义</li>
     *   <li>不匹配 lexicon-pack 命名约定（白名单：字母/数字/点/横杠/下划线，结尾 .jar）</li>
     * </ul>
     */
    private static final java.util.regex.Pattern SAFE_FILENAME =
        java.util.regex.Pattern.compile("^[a-zA-Z0-9._-]{1,200}$");

    private static String sanitizeFileName(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw badRequest("invalid_filename",
                "file name must not contain /, \\, or ..");
        }
        // 控制字符检测：任何 < 0x20 或 0x7F 立即拒
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw badRequest("invalid_filename",
                    "file name contains control character (e.g. CR/LF/TAB)");
            }
        }
        // 严格白名单：lexicon-pack 文件名应当只含 [a-zA-Z0-9._-]
        if (!SAFE_FILENAME.matcher(trimmed).matches()) {
            throw badRequest("invalid_filename",
                "file name must match [a-zA-Z0-9._-]{1,200}");
        }
        return trimmed;
    }

    // ---------------------------------------------------------- crypto helpers

    private static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("HMAC sign failed", e);
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /**
     * Hex-string constant-time compare. 用 MessageDigest.isEqual 比裸 char xor 更稳。
     */
    private static boolean constantTimeEqualsHex(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        return MessageDigest.isEqual(
            a.toLowerCase().getBytes(StandardCharsets.US_ASCII),
            b.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    // ---------------------------------------------------------- allowlist 判定

    /** allowlist 判定结果。{@link #checkAllowlist} 的纯返回值，便于单测。 */
    enum AllowlistOutcome {
        /** 通过：allowlist 命中，或未配置且非强制（fail-open）。 */
        ALLOWED,
        /** 拒绝：require-allowlist=true（生产）但 allowlist 为空——fail-closed。 */
        REQUIRED,
        /** 拒绝：allowlist 已配置但 body sha 不在其中。 */
        NOT_LISTED
    }

    record AllowlistVerdict(AllowlistOutcome outcome) {}

    /**
     * SHA-256 allowlist 纵深防御判定（ADR 0018）。纯函数，无副作用，可独立单测。
     *
     * <p>HMAC 是上传的第一道门（默认强制）；allowlist 是 HMAC key 泄露后的兜底。
     * 因此生产 profile 下（{@code requireAllowlist=true}）allowlist 不可为空：
     * 空即 {@link AllowlistOutcome#REQUIRED} 拒绝，而非放行。dev/test 默认放行便于开发。
     *
     * @param shaAllowlist 逗号分隔的受信 SHA-256 hex；{@code null}/空白视为未配置
     * @param requireAllowlist fail-closed 开关（prod=true，dev/test=false）
     * @param bodySha 本次上传 jar 的 SHA-256 hex
     */
    static AllowlistVerdict checkAllowlist(String shaAllowlist, boolean requireAllowlist, String bodySha) {
        boolean configured = shaAllowlist != null && !shaAllowlist.isBlank();
        if (!configured) {
            return new AllowlistVerdict(
                requireAllowlist ? AllowlistOutcome.REQUIRED : AllowlistOutcome.ALLOWED);
        }
        for (String s : shaAllowlist.split(",")) {
            if (constantTimeEqualsHex(s.trim(), bodySha)) {
                return new AllowlistVerdict(AllowlistOutcome.ALLOWED);
            }
        }
        return new AllowlistVerdict(AllowlistOutcome.NOT_LISTED);
    }

    // ---------------------------------------------------------- error helpers

    /**
     * 抹掉原始 exception 细节，只回通用错误 + traceId。
     * 真实细节进 server log（minor finding fix）。
     */
    private static WebApplicationException ioError(String error, String publicMessage) {
        String traceId = UUID.randomUUID().toString();
        LOG.warnf("Admin IO failure traceId=%s", traceId);
        return new WebApplicationException(
            Response.status(500)
                .entity(Map.of("error", error, "message", publicMessage, "traceId", traceId))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    private static WebApplicationException forbidden(String error, String message) {
        return new WebApplicationException(
            Response.status(403)
                .entity(Map.of("error", error, "message", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    private static WebApplicationException badRequest(String error, String message) {
        return new WebApplicationException(
            Response.status(400)
                .entity(Map.of("error", error, "message", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
