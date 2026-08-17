package io.aster.audit.chain;

import io.aster.policy.scheduler.BackgroundSchedulerSkipPredicate;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * 审计链尾锚定服务（2026-08-17 安全审计，防篡改第 3 层）。
 *
 * <h2>它解决什么</h2>
 *
 * <p>权限（层 1）与触发器（层 2）解决「不能改」，但都挡不住两类攻击：
 *
 * <ul>
 *   <li><b>删除链尾</b>：哈希链是单向的——每条记录指向前驱。删掉最后 N 条后，
 *       剩余部分<b>依然自洽</b>，{@code verifyChain} 返回 valid。攻击者只要把不利
 *       记录之后的全部记录一并删除，就能在不触发任何告警的情况下抹掉它们。
 *       （删中间记录会断链、能检出；删尾部不会。）</li>
 *   <li><b>整链重写</b>：拿到写权限后按新公式重算整条链，链自洽，无从判断。</li>
 * </ul>
 *
 * <p>两者的共同点是<b>仅凭表内数据无法判断</b>。必须有一份独立于审计表的、
 * 记录「某时刻链尾长什么样」的证据——即锚点。
 *
 * <h2>信任边界（重要，不要高估）</h2>
 *
 * <p><b>1) 锚定窗口盲区（固有性质，非缺陷）。</b>
 * 锚点只能证明「锚定那一刻链是什么样」。删除 {@code id} 大于最新锚点
 * {@code anchored_max_id} 的记录——即<b>尚未被锚定的最新记录</b>——不会被检出。
 * 实测确认：删掉这些记录后，全部锚点的三项核对（记录存在 / hash 一致 / 计数未减）
 * 依然通过。
 *
 * <p>这不是可以「修掉」的 bug，而是任何周期性锚定方案的本质：证据不可能覆盖
 * 尚未产生证据的时间段。缓解手段只有缩短窗口（提高锚定频率）——当前每小时一次，
 * 意味着最坏情况下可无声删除的范围 = 最近一小时内新增的记录。
 * 若该窗口不可接受，应改为<b>写入时同步锚定</b>（每条审计记录落库即更新锚点），
 * 代价是每次审计写入多一次锚点表写入。
 *
 * <p><b>2) 锚点存放位置。</b>
 * 锚点与审计表同库时，只能防「只改审计表、没想到还有锚点表」的粗糙攻击。
 * 真正的强度来自把锚点<b>复制到数据库之外</b>（对象存储 / 外部时间戳服务 /
 * 另一套凭据的库）。本服务负责<b>生成</b>锚点并暴露待导出集合；
 * 导出目的地属于部署侧配置，未在本类内实现——
 * {@code exported_at} 为 NULL 的锚点即「尚未获得外部保护」。
 */
@ApplicationScoped
public class AuditChainAnchorService {

    @Inject
    EntityManager entityManager;

    /**
     * 为所有有审计记录的租户各写一个锚点。
     *
     * <p>每小时一次：锚点越密，攻击者能悄悄删除的「尾巴」越短
     * （最坏情况下可无声删除的窗口 = 两次锚定之间新增的记录）。
     *
     * <p>幂等：唯一索引 {@code (tenant_id, anchored_max_id)} 保证同一链尾不会重复锚定，
     * 故链无新增时本任务实际不写入（{@code ON CONFLICT DO NOTHING}）。
     */
    @Scheduled(every = "1h", identity = "audit-chain-anchor",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
               skipExecutionIf = BackgroundSchedulerSkipPredicate.class)
    @Transactional
    void scheduledAnchor() {
        int written = anchorAllTenants();
        if (written > 0) {
            Log.infof("audit chain anchored: %d tenant(s)", written);
        }
    }

    /**
     * 为每个租户锚定当前链尾。
     *
     * <p>用单条 INSERT...SELECT 完成，避免「先查后写」在并发追加下取到不一致的
     * (max_id, hash, count) 三元组——三者必须来自同一快照，否则锚点自身就是错的。
     *
     * @return 实际写入的锚点数
     */
    @Transactional
    public int anchorAllTenants() {
        return entityManager.createNativeQuery("""
            INSERT INTO audit_chain_anchors
                (tenant_id, anchored_max_id, anchored_hash, anchored_count)
            SELECT t.tenant_id, t.max_id, a.current_hash, t.cnt
            FROM (
                SELECT tenant_id, MAX(id) AS max_id, COUNT(*) AS cnt
                FROM audit_logs
                WHERE current_hash IS NOT NULL
                GROUP BY tenant_id
            ) t
            JOIN audit_logs a ON a.id = t.max_id
            ON CONFLICT (tenant_id, anchored_max_id) DO NOTHING
            """).executeUpdate();
    }

    /**
     * 用**全部历史锚点**核对该租户的审计链，检出「删除链尾」与「整链重写」。
     *
     * <p>这是 {@code AuditChainVerifier} 的<b>补充而非替代</b>：后者验证链内自洽性
     * （改字段、删中间、插伪造），本方法验证链<b>相对外部证据</b>是否被截断或重写。
     *
     * @param tenantId 租户
     * @return 核对结果；无锚点时返回 {@link AnchorCheck#noAnchor()}
     */
    @Transactional
    public AnchorCheck verifyAgainstAnchor(String tenantId) {
        // ★必须核对**全部**历史锚点，不能只看最新的一个。
        //
        //   若只取最新锚点（ORDER BY ... LIMIT 1），存在「等下一次锚定洗白」攻击：
        //     1. 锚点 A 记录 (max_id=5, count=5)
        //     2. 攻击者删掉 id 4、5（链尾）——此时锚点 A 已能揭发
        //     3. 系统继续追加新记录，下一次定时锚定写入锚点 B (max_id=9, count=7)
        //     4. 只查最新锚点 → 只核对 B → B 自洽 → 返回「完好」，删除被彻底掩盖
        //
        //   实测（PG 16）该攻击成立：B 的核对全部通过，而 A 的 anchored_max_id=5
        //   对应的记录已不存在。因此改为遍历全部锚点，任一不符即判定被篡改——
        //   锚点是**累积**证据，新证据不能覆盖旧证据。
        //
        //   代价可控：锚点每租户每小时至多一条且只在链有新增时写入；
        //   即便按年计也只有数千条，且查询走 (tenant_id, anchored_max_id) 索引。
        List<?> rows = entityManager.createNativeQuery("""
            SELECT anchored_max_id, anchored_hash, anchored_count
            FROM audit_chain_anchors
            WHERE tenant_id = :tenant
            ORDER BY anchored_max_id ASC
            """)
            .setParameter("tenant", tenantId)
            .getResultList();

        if (rows.isEmpty()) {
            // ★区分「本来就没有审计记录」与「有记录却一条锚点都没有」。
            //
            //   后者是**异常状态**：锚定任务每小时跑一次，有记录必然产生锚点。
            //   一条都没有意味着锚定从未成功——最典型的原因是应用角色缺少
            //   audit_chain_anchors 的 INSERT 权限（层 3 静默不工作），
            //   也可能是攻击者设法阻止了锚定任务。
            //
            //   此前一律返回 intact=true，使「从未锚定」与「确已完好」
            //   在 API 响应里不可区分 —— 那是 fail-open。现改为显式上报。
            long auditedRecords = ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM audit_logs
                WHERE tenant_id = :tenant AND current_hash IS NOT NULL
                """)
                .setParameter("tenant", tenantId)
                .getSingleResult()).longValue();

            if (auditedRecords > 0) {
                return AnchorCheck.tampered(String.format(
                    "该租户有 %d 条审计记录但没有任何锚点——锚定任务从未成功执行"
                        + "（常见原因：应用角色缺 audit_chain_anchors 写权限）。"
                        + "在锚点建立前，无法证明审计链未被截断。",
                    auditedRecords));
            }
            return AnchorCheck.noAnchor();
        }

        long newestMaxId = 0;
        long newestCount = 0;

        for (Object row : rows) {
            Object[] anchor = (Object[]) row;
            long anchoredMaxId = ((Number) anchor[0]).longValue();
            String anchoredHash = (String) anchor[1];
            long anchoredCount = ((Number) anchor[2]).longValue();
            newestMaxId = anchoredMaxId;
            newestCount = anchoredCount;

            // 锚定点的记录是否还在、hash 是否还一致
            List<?> current = entityManager.createNativeQuery("""
                SELECT current_hash FROM audit_logs WHERE id = :id AND tenant_id = :tenant
                """)
                .setParameter("id", anchoredMaxId)
                .setParameter("tenant", tenantId)
                .getResultList();

            if (current.isEmpty()) {
                // 锚定过的那条记录消失了 —— 链尾被删（层 1/2 之外唯一能发现它的手段）
                return AnchorCheck.tampered(
                    "锚定记录 id=" + anchoredMaxId + " 已不存在——审计链尾被删除");
            }
            String currentHash = (String) current.get(0);
            if (!anchoredHash.equals(currentHash)) {
                return AnchorCheck.tampered(
                    "锚定记录 id=" + anchoredMaxId + " 的 hash 与锚点不符——该点及之前的链被重写");
            }

            // 记录数只增不减：变少说明有记录被删（可能删在锚定点之前的任意位置）
            long currentCount = ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM audit_logs
                WHERE tenant_id = :tenant AND current_hash IS NOT NULL AND id <= :id
                """)
                .setParameter("tenant", tenantId)
                .setParameter("id", anchoredMaxId)
                .getSingleResult()).longValue();

            if (currentCount < anchoredCount) {
                return AnchorCheck.tampered(String.format(
                    "锚定时该租户在 id<=%d 范围内有 %d 条记录，现仅剩 %d 条——有记录被删除",
                    anchoredMaxId, anchoredCount, currentCount));
            }
        }

        return AnchorCheck.ok(newestMaxId, newestCount);
    }

    /** 锚点核对结果。 */
    public record AnchorCheck(boolean hasAnchor, boolean intact, String reason,
                              long anchoredMaxId, long anchoredCount) {

        public static AnchorCheck noAnchor() {
            return new AnchorCheck(false, true, null, 0, 0);
        }

        public static AnchorCheck ok(long maxId, long count) {
            return new AnchorCheck(true, true, null, maxId, count);
        }

        public static AnchorCheck tampered(String reason) {
            return new AnchorCheck(true, false, reason, 0, 0);
        }
    }
}
