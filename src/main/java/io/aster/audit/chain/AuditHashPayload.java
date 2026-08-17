package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * 审计哈希链的<b>唯一</b>载荷构造与摘要计算入口。
 *
 * <p>★为什么必须只有一份：此前写侧（{@code AuditEventListener.computeHashChain}）
 * 与验侧（{@code AuditChainVerifier.computeHash}）各自实现了一遍同样的字符串拼接。
 * 两份实现没有任何一致性测试锁定——任一处漂移都会静默产生「全链验签失败」
 * 或更糟的「篡改漏检」。现在两侧都调用本类，公式只存在一处。
 *
 * <h2>版本化</h2>
 *
 * <p>{@link #V1_LEGACY_SIX_FIELDS} 是历史公式，只覆盖 17 个业务字段中的 6 个。
 * 未进链的字段包括 {@code performedBy}（谁做的）、{@code reason}（为什么）、
 * {@code clientIp}、{@code metadata}、{@code fromVersion}/{@code toVersion} 等——
 * 也就是说「谁批准、为何批准」可被静默改写而链验证仍返回 valid，
 * 与对外「tamper-evident / 不可篡改」的承诺不符（2026-08-17 审计发现）。
 *
 * <p>{@link #V2_FULL_CANONICAL} 覆盖全部业务字段。
 *
 * <p><b>历史行不重算</b>：重算等于改写审计记录本身，会销毁「链从未被动过」
 * 这一属性。改为在行上记录 {@code hash_version}，验证器按行选择公式：
 * 旧行仍以 V1 验证（保持可验证），新行以 V2 写入（获得完整保护）。
 *
 * <h2>编码</h2>
 *
 * <p>V2 不再用裸字符串拼接，而是 {@code 字段名=值} 且值经长度前缀编码。
 * 裸拼接存在边界歧义：{@code ("ab","c")} 与 {@code ("a","bc")} 产出同一串，
 * 攻击者可在两个相邻字段间搬运字符而不改变摘要。长度前缀消除该歧义。
 */
public final class AuditHashPayload {

    /** 历史公式：仅 6 个字段，裸字符串拼接。既有行以此验证。 */
    public static final short V1_LEGACY_SIX_FIELDS = 1;

    /** 当前公式：全部业务字段，长度前缀编码。新写入以此计算。 */
    public static final short V2_FULL_CANONICAL = 2;

    /** 新记录一律使用的版本。 */
    public static final short CURRENT_VERSION = V2_FULL_CANONICAL;

    private AuditHashPayload() {
    }

    /**
     * 按记录自身的 {@code hashVersion} 计算摘要。
     *
     * @param log       待计算的审计记录（其 {@code prevHash} 必须已就位）
     * @param version   哈希版本；{@code null} 视为 V1（列的数据库默认值即 1，
     *                  但通过 JPA 读到的旧对象仍可能为 null）
     * @return SHA-256 hex
     */
    public static String digest(AuditLog log, Short version) {
        // null 视为 V1：数据库列默认值即 1，但经 JPA 读到的旧对象仍可能为 null。
        short v = version == null ? V1_LEGACY_SIX_FIELDS : version;
        return switch (v) {
            case V1_LEGACY_SIX_FIELDS -> DigestUtils.sha256Hex(legacyV1(log));
            case V2_FULL_CANONICAL -> DigestUtils.sha256Hex(canonicalV2(log));
            // ★未知版本必须 fail-closed 抛错，不得静默降级到 V1。
            //   降级看似「保守」，实则更危险：若某条 V3 记录的 V1 摘要**碰巧**匹配
            //   （攻击者可主动构造这种碰撞——只要让 6 个 V1 字段与 current_hash 自洽），
            //   验证器就会把一条自己根本不理解的记录判为有效。
            //   无法理解的版本 = 无法验证，这必须是显式失败而非默默通过。
            default -> throw new IllegalArgumentException(
                "未知 audit hash_version: " + v + "（记录 id=" + log.id + "）——"
                    + "无法验证该记录；请升级应用或排查数据来源");
        };
    }

    /**
     * V1 历史载荷：{@code prev + eventType + timestamp + tenantId + policyModule
     * + policyFunction + success}，裸拼接、空值转空串。
     *
     * <p>逐字节复刻原实现，<b>不得</b>「顺手优化」——任何改动都会让既有链全部失效。
     */
    static String legacyV1(AuditLog log) {
        StringBuilder content = new StringBuilder();
        if (log.prevHash != null) {
            content.append(log.prevHash);
        }
        content.append(log.eventType != null ? log.eventType : "");
        content.append(log.timestamp != null ? log.timestamp.toString() : "");
        content.append(log.tenantId != null ? log.tenantId : "");
        content.append(log.policyModule != null ? log.policyModule : "");
        content.append(log.policyFunction != null ? log.policyFunction : "");
        content.append(log.success != null ? log.success.toString() : "");
        return content.toString();
    }

    /**
     * V2 载荷：覆盖全部业务字段，{@code 名:长度:值} 编码消除拼接边界歧义。
     *
     * <p>字段顺序固定且不得变更——顺序即协议的一部分。
     * 新增字段只能<b>追加</b>在末尾，并同时提升版本号。
     */
    static String canonicalV2(AuditLog log) {
        StringBuilder c = new StringBuilder(512);
        c.append("v2\n");
        put(c, "prevHash", log.prevHash);
        put(c, "eventType", log.eventType);
        put(c, "timestamp", log.timestamp == null ? null : log.timestamp.toString());
        put(c, "tenantId", log.tenantId);
        put(c, "performedBy", log.performedBy);
        put(c, "policyModule", log.policyModule);
        put(c, "policyFunction", log.policyFunction);
        put(c, "policyId", log.policyId);
        put(c, "fromVersion", log.fromVersion == null ? null : log.fromVersion.toString());
        put(c, "toVersion", log.toVersion == null ? null : log.toVersion.toString());
        put(c, "executionTimeMs", log.executionTimeMs == null ? null : log.executionTimeMs.toString());
        put(c, "success", log.success == null ? null : log.success.toString());
        put(c, "reason", log.reason);
        put(c, "errorMessage", log.errorMessage);
        put(c, "notes", log.notes);
        put(c, "metadata", log.metadata);
        put(c, "clientIp", log.clientIp);
        put(c, "userAgent", log.userAgent);
        return c.toString();
    }

    /**
     * 长度前缀编码单个字段。
     *
     * <p>{@code null} 与空串必须可区分（前者 {@code -1}，后者 {@code 0}），
     * 否则「把 reason 从 null 改成空串」这类改动会逃过校验。
     */
    private static void put(StringBuilder c, String name, String value) {
        c.append(name).append(':');
        if (value == null) {
            c.append("-1").append('\n');
        } else {
            c.append(value.length()).append(':').append(value).append('\n');
        }
    }
}
