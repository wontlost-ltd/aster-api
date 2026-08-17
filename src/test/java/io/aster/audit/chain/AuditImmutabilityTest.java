package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.test.BlockingDbTestHelper;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计不可篡改三层防御的端到端验证（2026-08-17 安全审计）。
 *
 * <p>哈希链本身只能<b>检测</b>篡改，不能<b>阻止</b>。三层：
 * <ol>
 *   <li>权限（V6.22.1）：应用角色无 UPDATE/DELETE/TRUNCATE</li>
 *   <li>触发器（V6.22.0）：数据库硬拒，对超级用户同样生效</li>
 *   <li>锚定（V6.23.0）：检出「删除链尾」与「整链重写」——
 *       这两类攻击前两层都挡不住，且仅凭表内数据无法发现</li>
 * </ol>
 *
 * <p>本类跑在真实 PostgreSQL 上（非 mock）：SQL 层的拒绝行为只有真库能验证。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class AuditImmutabilityTest {

    @Inject
    BlockingDbTestHelper db;

    @Inject
    Event<AuditEvent> auditEventProducer;

    @Inject
    AuditChainAnchorService anchorService;

    @Inject
    AuditChainVerifier verifier;

    @BeforeEach
    void cleanup() {
        // 夹具清理必须走特权通道——普通 DELETE 已被触发器拒绝，
        // 这本身就说明 append-only 生效了。
        //
        // ★顺序不可颠倒：锚点触发器要求「锚定点对应的审计记录已不存在」才允许删锚点，
        //   故必须先删 audit_logs 再删锚点。这个约束正是防止
        //   「删记录 + 删揭发它的锚点」这一组合攻击的核心。
        db.executeAsAuditMaintenance("DELETE FROM audit_logs");
        db.executeAsAnchorRetention("DELETE FROM audit_chain_anchors");
    }

    // ============================================================
    // 层 2：触发器硬拒（对超级用户同样生效）
    // ============================================================

    @Test
    void updateIsRejectedAtDatabaseLevel() throws Exception {
        String tenantId = "t-immutable-update";
        emit(tenantId, "f1");

        // 普通路径 UPDATE 必须被数据库拒绝——不是「事后能检测」，是当场写不进去
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> db.execute("UPDATE audit_logs SET performed_by = 'mallory' WHERE tenant_id = ?",
                tenantId),
            "audit_logs 的 UPDATE 必须被数据库层拒绝");
        assertTrue(rootMessage(e).contains("append-only"),
            "拒绝原因应来自 append-only 守卫，实际: " + rootMessage(e));
    }

    @Test
    void deleteIsRejectedAtDatabaseLevel() throws Exception {
        String tenantId = "t-immutable-delete";
        emit(tenantId, "f1");

        RuntimeException e = assertThrows(RuntimeException.class,
            () -> db.execute("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId),
            "audit_logs 的 DELETE 必须被数据库层拒绝");
        assertTrue(rootMessage(e).contains("append-only"),
            "拒绝原因应来自 append-only 守卫，实际: " + rootMessage(e));
    }

    @Test
    void truncateIsRejected() throws Exception {
        emit("t-immutable-truncate", "f1");
        // TRUNCATE 不触发行级触发器，必须由语句级触发器单独挡住，
        // 否则整表清空可绕过全部行级保护
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> db.execute("TRUNCATE audit_logs"),
            "TRUNCATE 必须被拒绝，否则可整表抹除审计链");
        assertTrue(rootMessage(e).contains("append-only"));
    }

    @Test
    void insertRemainsAllowed() throws Exception {
        // append-only 的含义是「只能追加」——追加本身必须畅通，
        // 否则审计功能整体不可用（这条用例防止把保护做成拒绝一切）
        String tenantId = "t-immutable-insert";
        emit(tenantId, "f1");
        emit(tenantId, "f2");
        assertEquals(2, AuditLog.findByTenant(tenantId).size(),
            "INSERT 必须继续工作");
    }

    @Test
    void retentionChannelAllowsDeleteButNeverUpdate() throws Exception {
        String tenantId = "t-retention";
        emit(tenantId, "f1");

        // 保留期清理：显式声明意图后可以删
        int deleted = db.executeAsAuditMaintenance(
            "DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        assertEquals(1, deleted, "保留期清理通道必须能删除到期记录");

        // ★但同一通道**不得**放行 UPDATE：保留期清理只需要删除，从不需要修改。
        //   两个豁免必须分离，否则「清理任务」会顺带获得改写审计记录的能力。
        emit(tenantId, "f2");
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> db.executeAsAuditRetention(
                "UPDATE audit_logs SET performed_by = 'mallory' WHERE tenant_id = ?", tenantId),
            "保留期清理通道绝不能放行 UPDATE");
        assertTrue(rootMessage(e).contains("append-only"));
    }

    // ============================================================
    // 层 3：锚定 —— 检出前两层挡不住的「删除链尾」
    // ============================================================

    @Test
    void anchorDetectsTailDeletion_whichChainVerificationAlone_cannot() throws Exception {
        String tenantId = "t-anchor-tail";
        for (int i = 0; i < 4; i++) {
            emit(tenantId, "f" + i);
        }

        // 锚定当前链尾
        anchorService.anchorAllTenants();
        var before = anchorService.verifyAgainstAnchor(tenantId);
        assertTrue(before.hasAnchor(), "应已写入锚点");
        assertTrue(before.intact(), "锚定后立即核对应完好");

        // 攻击：删掉最后 2 条（走特权通道模拟拿到写权限的攻击者）
        db.executeAsAuditMaintenance(
            "DELETE FROM audit_logs WHERE tenant_id = ? AND id > "
                + "(SELECT MIN(id) + 1 FROM audit_logs WHERE tenant_id = ?)",
            tenantId, tenantId);

        // ★关键对比：删链尾后，链本身**依然自洽**——
        //   verifyChain 看不出任何问题，因为剩余记录彼此仍然首尾相接。
        var chainResult = verifier.verifyChain(tenantId,
            Instant.parse("2020-01-01T00:00:00Z"), Instant.now().plusSeconds(60));
        assertTrue(chainResult.isValid(),
            "★删除链尾后链仍自洽——这正是仅靠链验证无法发现的攻击，也是锚定存在的理由");

        // 而锚点能发现：锚定过的那条记录不见了
        var after = anchorService.verifyAgainstAnchor(tenantId);
        assertFalse(after.intact(), "锚点必须检出链尾被删除");
        assertTrue(after.reason().contains("链尾被删除") || after.reason().contains("被删除"),
            "应明确指出链尾被删，实际: " + after.reason());
    }

    @Test
    void anchorDetectsRewrittenChain() throws Exception {
        String tenantId = "t-anchor-rewrite";
        for (int i = 0; i < 3; i++) {
            emit(tenantId, "f" + i);
        }
        anchorService.anchorAllTenants();

        // 攻击：重写链尾记录的 hash（模拟按新公式重算整条链）
        db.executeAsAuditTamper(
            "UPDATE audit_logs SET current_hash = ? WHERE id = "
                + "(SELECT MAX(id) FROM audit_logs WHERE tenant_id = ?)",
            "f".repeat(64), tenantId);

        var after = anchorService.verifyAgainstAnchor(tenantId);
        assertFalse(after.intact(), "锚点必须检出链被重写");
        assertTrue(after.reason().contains("重写"), "实际: " + after.reason());
    }

    /**
     * ★「等下一次锚定洗白」攻击：删链尾后继续追加，让新锚点覆盖旧证据。
     *
     * <p>此前 {@code verifyAgainstAnchor} 只取<b>最新</b>锚点
     * （{@code ORDER BY anchored_max_id DESC LIMIT 1}），攻击者只需：
     * <ol>
     *   <li>等一次锚定落下（锚点 A 记录 max_id=N）</li>
     *   <li>删掉链尾</li>
     *   <li>让系统继续追加，下一次锚定写入锚点 B（max_id&gt;N）</li>
     *   <li>此后只核对 B —— B 自洽，删除被彻底掩盖</li>
     * </ol>
     *
     * <p>修复：遍历<b>全部</b>历史锚点。锚点是累积证据，新证据不得覆盖旧证据。
     * 本用例在修复前会失败（返回 intact），是该修复的可执行证明。
     */
    @Test
    void anchorDetectsTailDeletion_evenAfterNewerAnchorLands() throws Exception {
        String tenantId = "t-anchor-washing";
        for (int i = 0; i < 4; i++) {
            emit(tenantId, "old" + i);
        }
        anchorService.anchorAllTenants();   // 锚点 A：覆盖这 4 条

        long anchoredMaxId = db.queryLong(
            "SELECT MAX(anchored_max_id) FROM audit_chain_anchors WHERE tenant_id = ?", tenantId);

        // 攻击第 1 步：删掉链尾（锚点 A 覆盖范围内的最后一条）
        db.executeAsAuditMaintenance(
            "DELETE FROM audit_logs WHERE tenant_id = ? AND id = ?", tenantId, anchoredMaxId);

        // 攻击第 2 步：继续追加新记录，使 max_id 超过锚点 A
        for (int i = 0; i < 3; i++) {
            emit(tenantId, "new" + i);
        }

        // 攻击第 3 步：等下一次锚定落下（锚点 B，max_id 更大且自洽）
        anchorService.anchorAllTenants();
        long anchorRows = anchorCount(tenantId);
        assertTrue(anchorRows >= 2, "应已存在新旧两个锚点，实际 " + anchorRows);

        // ★核心断言：新锚点不得掩盖旧锚点记录的删除
        var check = anchorService.verifyAgainstAnchor(tenantId);
        assertFalse(check.intact(),
            "新锚点落下后仍必须检出链尾被删——锚点是累积证据，不能只看最新的一个");
    }

    /**
     * ★<b>已知边界</b>：删除尚未被锚定的最新记录**不会**被检出。
     *
     * <p>这不是缺陷，而是周期性锚定的固有性质——证据不可能覆盖尚未产生证据的
     * 时间段。本用例把该边界**固化为可执行的事实陈述**，避免它被误当作已解决：
     * 若将来有人改进实现（如改为写入时同步锚定）使其可检出，本用例会失败，
     * 从而提示同步更新对外的能力声明。
     *
     * <p>当前缓解手段是缩短窗口：每小时锚定一次 → 最坏情况下可无声删除的范围
     * 是最近一小时内新增的记录。
     */
    @Test
    void knownGap_deletingRecordsNewerThanLastAnchorIsNotDetected() throws Exception {
        String tenantId = "t-anchor-gap";
        for (int i = 0; i < 3; i++) {
            emit(tenantId, "anchored" + i);
        }
        anchorService.anchorAllTenants();
        long anchoredMaxId = db.queryLong(
            "SELECT MAX(anchored_max_id) FROM audit_chain_anchors WHERE tenant_id = ?", tenantId);

        // 追加 2 条尚未被锚定的记录
        for (int i = 0; i < 2; i++) {
            emit(tenantId, "unanchored" + i);
        }

        // 攻击：只删这些「锚点看不见」的最新记录
        int deleted = db.executeAsAuditMaintenance(
            "DELETE FROM audit_logs WHERE tenant_id = ? AND id > ?", tenantId, anchoredMaxId);
        assertEquals(2, deleted, "应删掉两条未锚定记录");

        var check = anchorService.verifyAgainstAnchor(tenantId);
        assertTrue(check.intact(),
            "已知边界：锚点只能证明锚定那一刻的状态，删除其后新增的记录无法检出。"
                + "若本断言开始失败，说明实现已能覆盖该窗口——请同步更新 "
                + "AuditChainAnchorService 的信任边界文档与对外能力声明");
    }

    /**
     * ★「用清理通道自解层 3」攻击：删掉不利记录后，再删掉能揭发它的那个锚点。
     *
     * <p>此前 audit_logs 与 audit_chain_anchors 的 DELETE 共用同一个开关
     * {@code aster.audit_retention_job}，于是攻击者在**同一个事务**里即可完成：
     * <pre>
     *   SET LOCAL aster.audit_retention_job = 'on';
     *   DELETE FROM audit_logs        WHERE ...;   -- 删掉不利记录
     *   DELETE FROM audit_chain_anchors WHERE ...; -- 删掉能揭发它的锚点
     * </pre>
     * 核对随即报告「完好」——层 3 被自己的清理通道解除。
     *
     * <p>修复两处：①锚点删除改用独立开关；②触发器要求锚定点对应的审计记录
     * **确实已不存在**才允许删该锚点。本用例锁住第二条——它才是真正的不变量。
     */
    @Test
    void anchorCannotBeDeletedWhileItsAuditRecordStillExists() throws Exception {
        String tenantId = "t-anchor-selfclear";
        for (int i = 0; i < 3; i++) {
            emit(tenantId, "f" + i);
        }
        anchorService.anchorAllTenants();

        // 记录仍在时，即便持有锚点退休开关也不得删除该锚点——
        // 否则「改了记录 → 删掉证据」这条链就通了
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> db.executeAsAnchorRetention(
                "DELETE FROM audit_chain_anchors WHERE tenant_id = ?", tenantId),
            "锚定点对应的审计记录仍存在时，不得删除该锚点");
        assertTrue(rootMessage(e).contains("仍然存在"),
            "应明确拒绝原因，实际: " + rootMessage(e));

        // 审计表的保留期开关**不得**解锁锚点删除（两个通道必须分离）
        RuntimeException e2 = assertThrows(RuntimeException.class,
            () -> db.executeAsAuditRetention(
                "DELETE FROM audit_chain_anchors WHERE tenant_id = ?", tenantId),
            "审计表的清理开关不得同时解锁锚点删除");
        assertTrue(rootMessage(e2).contains("append-only"),
            "实际: " + rootMessage(e2));
    }

    /**
     * ★「有审计记录却零锚点」必须上报为异常，而非静默判为完好。
     *
     * <p>最典型的成因是应用角色缺少 audit_chain_anchors 的 INSERT 权限——
     * 锚定任务每小时抛异常、锚点表恒空，而核对返回 intact=true，
     * 使「层 3 从未工作」与「确已完好」在 API 响应里不可区分（fail-open）。
     */
    @Test
    void auditRecordsWithoutAnyAnchor_isReportedNotSilentlyIntact() throws Exception {
        String tenantId = "t-no-anchor-but-records";
        emit(tenantId, "f1");
        // 刻意不锚定

        var check = anchorService.verifyAgainstAnchor(tenantId);
        assertFalse(check.intact(),
            "有审计记录却一条锚点都没有 = 锚定从未成功，必须上报而非静默判为完好");
        assertTrue(check.reason().contains("没有任何锚点"),
            "应说明原因，实际: " + check.reason());
    }

    @Test
    void anchorIsIdempotent_noNewRecordsNoNewAnchor() throws Exception {
        String tenantId = "t-anchor-idem";
        emit(tenantId, "f1");

        anchorService.anchorAllTenants();
        long first = anchorCount(tenantId);
        anchorService.anchorAllTenants();
        long second = anchorCount(tenantId);

        assertEquals(first, second,
            "链无新增时不应重复锚定（唯一索引 + ON CONFLICT DO NOTHING）");
    }

    @Test
    void anchorRecordsAreThemselvesImmutable() throws Exception {
        String tenantId = "t-anchor-immutable";
        emit(tenantId, "f1");
        anchorService.anchorAllTenants();

        // 锚点若可改写，攻击者删完链尾再改锚点即可完全掩盖
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> db.execute(
                "UPDATE audit_chain_anchors SET anchored_hash = ? WHERE tenant_id = ?",
                "0".repeat(64), tenantId),
            "锚定内容必须不可变，否则可被改写以掩盖链尾删除");
        assertTrue(rootMessage(e).contains("不可变") || rootMessage(e).contains("append-only"),
            "实际: " + rootMessage(e));

        // 常规路径不得删除锚点——否则攻击者删完链尾再删锚点即可完全掩盖。
        // （保留期清理通道可以删：锚点是派生数据，随审计记录一起到期是合理的。）
        RuntimeException d = assertThrows(RuntimeException.class,
            () -> db.execute("DELETE FROM audit_chain_anchors WHERE tenant_id = ?", tenantId),
            "常规路径不得删除锚点");
        assertTrue(rootMessage(d).contains("append-only"));
    }

    /**
     * ★生产迁移中<b>不得</b>存在任何 UPDATE 豁免通道。
     *
     * <p>篡改模拟豁免只允许出现在 test-only 迁移位置
     * （{@code db/migration-test}，仅 %test profile 加载）。若有人「为了方便」
     * 把它挪进主迁移，生产库就会多出一条后门：拿到应用数据库连接的攻击者
     * （SQL 注入 / 凭据泄露）只要自己 {@code SET LOCAL aster.audit_tamper_simulation='on'}
     * 就能改写审计记录，整层保护形同虚设。
     *
     * <p>本用例扫生产迁移目录的源文件——这是唯一能锁住「生产无后门」的方式，
     * 因为测试运行时加载的恰恰是带豁免的 test 版本，运行期行为测不出这一点。
     *
     * <p>★检查的是**结构**而非特定变量名：审查实测证明，扫
     * {@code audit_tamper_simulation} 这个字面量至少有 4 种绕法
     * （字符串拼接 {@code 'aster.audit_tamper' || '_simulation'}、换个变量名、
     * {@code chr()} 拼装、直接复用保留期开关放行 UPDATE）。
     * 现改为断言：生产迁移的 UPDATE 分支里**不得出现任何 current_setting(...)**
     * ——「UPDATE 是否放行取决于某个 session 变量」这个形状本身就是后门，
     * 与它读哪个变量无关。
     */
    @Test
    void productionMigrationsContainNoUpdateBypass() throws Exception {
        java.nio.file.Path prodMigrations = java.nio.file.Path.of("src/main/resources/db/migration");
        assertTrue(java.nio.file.Files.isDirectory(prodMigrations),
            "生产迁移目录应存在: " + prodMigrations.toAbsolutePath());

        try (var paths = java.nio.file.Files.walk(prodMigrations)) {
            var offenders = paths
                .filter(java.nio.file.Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sql"))
                .filter(p -> {
                    try {
                        // ★不再扫特定变量名（那太容易绕开：字符串拼接、换个变量名、
                        //   chr() 拼装、甚至复用保留期开关，实测均可逃过子串匹配）。
                        //   改为扫**结构**：生产迁移的 UPDATE 分支里不得出现任何
                        //   current_setting(...) —— 无论读的是哪个变量，
                        //   「UPDATE 是否放行取决于某个 session 变量」本身就是后门形状。
                        String sql = java.nio.file.Files.readAllLines(p).stream()
                            .map(String::trim)
                            .filter(line -> !line.startsWith("--"))
                            .reduce("", (a, b) -> a + "\n" + b);

                        // 定位 UPDATE 分支：TG_OP = 'UPDATE' 之后到下一个 TG_OP 之前
                        int start = sql.indexOf("TG_OP = 'UPDATE'");
                        if (start < 0) return false;
                        int next = sql.indexOf("TG_OP =", start + 16);
                        String updateBranch = next > 0 ? sql.substring(start, next) : sql.substring(start);
                        return updateBranch.contains("current_setting");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(java.nio.file.Path::getFileName)
                .map(Object::toString)
                .toList();

            assertTrue(offenders.isEmpty(),
                "生产迁移的 UPDATE 分支不得依赖任何 session 变量（current_setting）——"
                    + "那是生产后门，无论变量叫什么名字。豁免只允许存在于 db/migration-test。"
                    + "违规文件: " + offenders);
        }
    }

    @Test
    void tenantWithNoAuditRecordsAtAll_isNotReportedAsTampering() {
        // 完全没有审计记录的租户：没有需要保护的东西，也就无所谓被篡改。
        // 「没有数据」与「数据被改」是两回事，不得误报。
        //
        // ★注意与 auditRecordsWithoutAnyAnchor_isReportedNotSilentlyIntact 的区别：
        //   那个场景是「有记录但零锚点」= 锚定从未工作 = 必须上报；
        //   本场景是「连记录都没有」= 无结论，正常。
        var check = anchorService.verifyAgainstAnchor("t-never-anchored-no-records");
        assertFalse(check.hasAnchor());
        assertTrue(check.intact(), "无任何审计记录时应视为「无结论」，不得误报为篡改");
    }

    // ============================================================

    private void emit(String tenantId, String func) throws Exception {
        auditEventProducer.fireAsync(new AuditEvent(
            EventType.POLICY_EVALUATION, Instant.now(), tenantId,
            "test.module", func, null, null, null, "tester", true, 1L, null,
            Map.of(), null, null, null, null));
        waitForCount(tenantId);
    }

    private void waitForCount(String tenantId) throws Exception {
        for (int i = 0; i < 100; i++) {
            long n = db.queryLong(
                "SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ?", tenantId);
            if (n > 0) {
                Thread.sleep(30);
                long after = db.queryLong(
                    "SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ?", tenantId);
                if (after == n) return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("审计记录未在预期时间内落库: " + tenantId);
    }

    private long anchorCount(String tenantId) {
        return db.queryLong(
            "SELECT COUNT(*) FROM audit_chain_anchors WHERE tenant_id = ?", tenantId);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        StringBuilder all = new StringBuilder();
        while (cur != null) {
            if (cur.getMessage() != null) all.append(cur.getMessage()).append(' ');
            cur = cur.getCause();
        }
        return all.toString();
    }
}
