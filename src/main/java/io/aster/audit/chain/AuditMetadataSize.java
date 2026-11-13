package io.aster.audit.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.common.PIIRedactor;

import java.util.HashMap;
import java.util.Map;

/**
 * 按追加器口径测量 metadata 序列化长度，供写事件前的预算预检使用（Action Guard 收据、评估事件）。
 *
 * <p>追加器先对顶层 String 值做 PII 脱敏（邮箱、IP 等会变长；身份键与规范 UUID 豁免，见
 * {@link AuditChainAppender#isRedactionExempt}），再 JSON 序列化（引号、反斜杠、控制字符转义后变长），最后按字符数
 * 与 {@link AuditChainAppender#MAX_METADATA_SIZE} 比较。这里取「原样序列化」与「脱敏后序列化」两者较长者。
 */
public final class AuditMetadataSize {
  /** 预检预算：低于追加器上限，给两个 ObjectMapper 之间可能的细微差异留出余量。 */
  public static final int BUDGET = 3800;
  /** 只用于测量：对 String / List / Number / Boolean 值，序列化结果与 Quarkus 托管的 ObjectMapper 相同。 */
  private static final ObjectMapper MEASURE = new ObjectMapper();
  private static final PIIRedactor REDACTOR = new PIIRedactor();

  private AuditMetadataSize() {
  }

  /** 与追加器一致的测量长度（字符数）。 */
  public static int measure(Map<String, Object> m) {
    Map<String, Object> redacted = new HashMap<>();
    m.forEach((k, v) -> redacted.put(k, v instanceof String s ? redactLikeAppender(k, s) : v));
    return Math.max(serializedLength(m), serializedLength(redacted));
  }

  private static String redactLikeAppender(String key, String value) {
    return AuditChainAppender.isRedactionExempt(key, value) ? value : REDACTOR.redact(value);
  }

  private static int serializedLength(Map<String, Object> m) {
    try {
      return MEASURE.writeValueAsString(m).length();
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("metadata 无法序列化", e);
    }
  }
}
