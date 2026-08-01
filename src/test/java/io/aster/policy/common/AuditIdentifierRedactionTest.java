package io.aster.policy.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 回归：审计链的**分区键 {@code tenantId} 绝不能经过 PII 脱敏**。
 *
 * <p>2026-08-01 事故：{@code AuditEventListener} 曾对 {@code tenantId}
 * 调用 {@code redact()}。而 {@code tenantId} 同时是哈希链的
 * <b>分区键</b>（加锁、查前序哈希、参与哈希计算都用它），读取侧
 * {@code RequestIdentityResolver} 又完全不脱敏 —— 键因此永久不匹配。
 *
 * <p>后果不是"少了几条日志"，而是 {@code verifyChain} 查到 0 条记录后返回
 * {@code valid=true, recordsVerified=0}：一个覆盖为空的"防篡改链完好"绿灯。
 * 生产 3430 条审计记录中已有 1272 条（37%）的键被改写。
 *
 * <p>本测试锁定两件事：
 * <ol>
 *   <li>{@code CREDIT_CARD_PATTERN} 确实会吃掉 UUID 形态的标识符（说明为何必须禁止脱敏）；
 *   <li>标识符必须原样透传。
 * </ol>
 */
class AuditIdentifierRedactionTest {

    private final PIIRedactor redactor = new PIIRedactor();

    @Test
    @DisplayName("信用卡正则会破坏含『4 组 4 位数字』的 UUID —— 故标识符不可脱敏")
    void creditCardPatternCorruptsUuidLikeIdentifiers() {
        // 生产实测受损样本的等价形态：UUID 尾部恰好是 4 组 4 位数字
        String tenantId = "5e830b74-45d1-45b9-1234-5678-9012-3456";

        String redacted = redactor.redact(tenantId);

        // 证明危害真实存在：整段尾部被替换，标识符不再可用于查询/分区
        assertEquals(
                "5e830b74-45d1-45b9-****-****-****-****",
                redacted,
                "信用卡正则吃掉了 UUID 尾部——这正是审计链键失配的成因");
    }

    @Test
    @DisplayName("不含该形态的 UUID 不受影响 —— 说明损坏是间歇性的、更难察觉")
    void unaffectedUuidPassesThrough() {
        String tenantId = "784d38c4-f433-4369-b4dc-ab0f4f0cc2b9";

        assertEquals(
                tenantId,
                redactor.redact(tenantId),
                "同为 UUID 却不受影响——损坏取决于具体取值，因此长期无人发现");
    }

    @Test
    @DisplayName("AuditEventListener 源码中 tenantId / performedBy 不得再调用 redact")
    void listenerMustNotRedactIdentifiers() throws Exception {
        // ★直接断言**生产源码**，而不是在测试里复刻一份逻辑——
        //   复刻版永远会通过，无法在有人改回 redact(...) 时报警。
        Path listener = Path.of(
                "src/main/java/io/aster/policy/event/AuditEventListener.java");
        String src = Files.readString(listener);

        assertTrue(
                src.contains("log.tenantId = tenant;"),
                "tenantId 必须原样赋值；它是哈希链分区键，脱敏会导致读写键失配");
        assertFalse(
                src.contains("log.tenantId = redact("),
                "禁止对 tenantId 脱敏——会造成 verifyChain 对 0 条记录返回 valid=true");
        // ★performedBy **保持脱敏**：它可能是 email（AuditLogComplianceTest
        //   testPIIRedactionInDatabase 钉死了 "***@***.***" 契约），是真实 PII。
        //   它不是链的分区键，脱敏不会造成读写失配。
        assertTrue(
                src.contains("log.performedBy = redact("),
                "performedBy 必须继续脱敏——它可能是 email，且不是链分区键");
    }
}
