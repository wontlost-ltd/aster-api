package io.aster.audit.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 有界文本列（aster-api#498）：error_message（1000）、performed_by（100）、policy_module / policy_function（200）、
 * policy_id（100）的取值来自未限长输入，且脱敏会让文本变长。追加器一律先脱敏、后按列宽截断，不让整条事件写入失败；
 * 截断不切开代理对。只走纯映射，不触库（入链与验链见 {@link AuditChainAppenderTest}）。
 */
class AuditChainAppenderColumnBoundsTest {
  /** 每个 "1.1.1.1 "（8 字符）脱敏成 "***.***.***.*** "（16 字符），长度翻倍。 */
  private static final String IP_UNIT = "1.1.1.1 ";
  private final AuditChainAppender appender = new AuditChainAppender();

  @BeforeEach
  void setUp() {
    appender.objectMapper = new ObjectMapper();
  }

  private AuditLog map(String module, String function, String policyId, String performedBy, String errorMessage) {
    return appender.toAuditLog(new AuditEvent(EventType.POLICY_EVALUATION, Instant.now(), "t", module, function,
        policyId, null, null, performedBy, false, 1L, errorMessage, Map.of(), null, null, null, null));
  }

  private AuditLog withError(String errorMessage) {
    return map("m", "f", null, "u", errorMessage);
  }

  private AuditLog withPerformedBy(String performedBy) {
    return map("m", "f", null, performedBy, null);
  }

  @Test
  void 超长error_message截到列宽1000() {
    AuditLog log = withError("boom ".repeat(2_000));

    assertThat(log.errorMessage).hasSize(1000).startsWith("boom boom");
  }

  @Test
  void error_message先脱敏再截断() {
    AuditLog log = withError("failed for a@b.cc at " + IP_UNIT.repeat(200));

    assertThat(log.errorMessage).hasSizeLessThanOrEqualTo(1000)
        .startsWith("failed for ***@***.*** at ***.***.***.***")
        .doesNotContain("a@b.cc", "1.1.1.1");
  }

  @Test
  void 列宽内的error_message原样写入() {
    String msg = "x".repeat(1000);

    assertThat(withError(msg).errorMessage).isEqualTo(msg);
    assertThat(withError(null).errorMessage).isNull();
  }

  @Test
  void performed_by在脱敏变长后截到列宽100() {
    String performedBy = IP_UNIT.repeat(12).substring(0, 95); // 输入未超列宽，脱敏后约 190 字符

    AuditLog log = withPerformedBy(performedBy);

    assertThat(log.performedBy).hasSize(100).startsWith("***.***.***.***").doesNotContain("1.1.1.1");
  }

  @Test
  void 列宽内的performed_by原样写入() {
    assertThat(withPerformedBy("agent-42").performedBy).isEqualTo("agent-42");
    assertThat(withPerformedBy(null).performedBy).isNull();
  }

  @Test
  void 策略标识列按各自列宽截断() {
    AuditLog log = map(IP_UNIT.repeat(40), "f".repeat(500), "p".repeat(500), "u", null);

    assertThat(log.policyModule).hasSize(200).startsWith("***.***.***.***");
    assertThat(log.policyFunction).hasSize(200);
    assertThat(log.policyId).hasSize(100);
  }

  @Test
  void 截断不切开代理对() {
    String emoji = "😀"; // 😀，两个 UTF-16 单元
    AuditLog log = map("m".repeat(199) + emoji, "f", "p".repeat(99) + emoji, "u".repeat(99) + emoji,
        "e".repeat(999) + emoji);

    assertThat(log.errorMessage).isEqualTo("e".repeat(999));
    assertThat(log.performedBy).isEqualTo("u".repeat(99));
    assertThat(log.policyModule).isEqualTo("m".repeat(199));
    assertThat(log.policyId).isEqualTo("p".repeat(99));
  }

  @Test
  void 恰在列宽内的代理对保留() {
    String emoji = "😀";
    String msg = "e".repeat(998) + emoji;

    assertThat(withError(msg).errorMessage).isEqualTo(msg);
  }

  private AuditLog withTenant(String tenant) {
    return appender.toAuditLog(new AuditEvent(EventType.POLICY_EVALUATION, Instant.now(), tenant, "m", "f",
        null, null, null, "u", false, 1L, null, Map.of(), null, null, null, null));
  }

  @Test
  void tenant恰在列宽100内原样写入() {
    String tenant = "t".repeat(AuditChainAppender.TENANT_COLUMN_MAX_LENGTH);
    assertThat(AuditChainAppender.TENANT_COLUMN_MAX_LENGTH).isEqualTo(100);
    assertThat(withTenant(tenant).tenantId).isEqualTo(tenant);
  }

  @Test
  void 超宽tenant不截断而是明确拒绝() {
    // tenant_id 是哈希链分区键，截断会让该租户的链查不到（aster-api#503）。入口 TenantFilter 已把租户限到 64，
    // 这里是不依赖入口的兜底：映射阶段即拒绝，不触库、不拿链锁。
    assertThatThrownBy(() -> withTenant("t".repeat(AuditChainAppender.TENANT_COLUMN_MAX_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant_id")
        .hasMessageContaining("101");
  }
}
