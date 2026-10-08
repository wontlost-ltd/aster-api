package io.aster.audit.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.common.PIIRedactor;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追加器 metadata 脱敏的豁免（ADR 0040 §5.2）：身份键与规范 UUID 形态的值原样入链，其余顶层 String 照常脱敏。
 * 只走纯映射 {@link AuditChainAppender#toAuditLog}，不触库、不启动 Quarkus。
 */
class AuditChainAppenderRedactionTest {
  /** 三个样本都含「4 组 4 位数字」片段，会被 CREDIT_CARD_PATTERN 改写。 */
  private static final String DECISION_ID = "12345678-1234-5678-9012-abcdef123456";
  private static final String APPROVAL_ID = "abcdef12-1234-5678-9012-345678901234";
  private static final String IDEMPOTENCY_KEY = "87654321-4321-8765-2109-fedcba654321";
  private static final String CARD = "4111-1111-1111-1111";

  private final AuditChainAppender appender = new AuditChainAppender();

  @BeforeEach
  void setUp() {
    appender.objectMapper = new ObjectMapper();
  }

  private String persistedMetadata(Map<String, Object> metadata) {
    AuditEvent event = new AuditEvent(EventType.ACTION_GUARD_DECISION, Instant.now(), "tenant-redaction", "guard.probe",
        "decide", null, null, 3L, "agent-42", false, null, null, metadata, null, null, null, null);
    return appender.toAuditLog(event).metadata;
  }

  @Test
  void 样本UUID确实会被PII脱敏改写() {
    PIIRedactor redactor = new PIIRedactor();
    assertThat(redactor.redact(DECISION_ID)).isNotEqualTo(DECISION_ID);
    assertThat(redactor.redact(APPROVAL_ID)).isNotEqualTo(APPROVAL_ID);
    assertThat(redactor.redact(IDEMPOTENCY_KEY)).isNotEqualTo(IDEMPOTENCY_KEY);
  }

  @Test
  void 身份键逐字入链_同一metadata中的普通键仍脱敏() {
    String json = persistedMetadata(Map.of("decisionId", DECISION_ID, "approvalId", APPROVAL_ID,
        "idempotencyKey", IDEMPOTENCY_KEY, "comment", CARD));

    assertThat(json).contains("\"decisionId\":\"" + DECISION_ID + "\"")
        .contains("\"approvalId\":\"" + APPROVAL_ID + "\"")
        .contains("\"idempotencyKey\":\"" + IDEMPOTENCY_KEY + "\"")
        .contains("\"comment\":\"****-****-****-****\"")
        .doesNotContain(CARD);
  }

  @Test
  void 身份键的非UUID值同样不脱敏() {
    String json = persistedMetadata(Map.of("idempotencyKey", CARD, "decisionReceiptHash", "a".repeat(64)));

    assertThat(json).contains("\"idempotencyKey\":\"" + CARD + "\"")
        .contains("\"decisionReceiptHash\":\"" + "a".repeat(64) + "\"");
  }

  @Test
  void 其他键的规范UUID值整串豁免_大写同样豁免() {
    String upper = DECISION_ID.toUpperCase();
    String json = persistedMetadata(Map.of("requestRef", DECISION_ID, "traceRef", upper));

    assertThat(json).contains("\"requestRef\":\"" + DECISION_ID + "\"")
        .contains("\"traceRef\":\"" + upper + "\"");
  }

  @Test
  void 嵌在自由文本里的UUID不豁免() {
    String json = persistedMetadata(Map.of("reason", "ref " + DECISION_ID));

    assertThat(json).doesNotContain(DECISION_ID);
  }

  @Test
  void correlationId非UUID值也逐字入链() {
    String json = persistedMetadata(Map.of("correlationId", "4111-1111-1111-1111-x", "comment", CARD));
    assertThat(json).contains("\"correlationId\":\"4111-1111-1111-1111-x\"")
        .contains("\"comment\":\"****-****-****-****\"");
  }

  private AuditEvent eventWith(Map<String, Object> metadata) {
    return new AuditEvent(EventType.POLICY_EVALUATION, Instant.now(), "t", "m", "f", null, null, null, "u", true, null, null,
        metadata, null, null, null, null);
  }

  @Test
  void toAuditLog把correlationId与decisionId投影到列() {
    AuditLog log = appender.toAuditLog(eventWith(Map.of("correlationId", "c-1", "decisionId", "d-1", "x", 1)));
    assertThat(log.correlationId).isEqualTo("c-1");
    assertThat(log.decisionId).isEqualTo("d-1");
  }

  @Test
  void 列投影缺失或非字符串时为null() {
    AuditLog missing = appender.toAuditLog(eventWith(Map.of("x", 1)));
    assertThat(missing.correlationId).isNull();
    assertThat(missing.decisionId).isNull();
    AuditLog nonString = appender.toAuditLog(eventWith(Map.of("decisionId", 7, "correlationId", " ")));
    assertThat(nonString.decisionId).isNull();
    assertThat(nonString.correlationId).isNull();
  }

  @Test
  void 列投影超长时截断到64() {
    AuditLog log = appender.toAuditLog(eventWith(Map.of("correlationId", "c".repeat(100))));
    assertThat(log.correlationId).hasSize(64);
  }
}
