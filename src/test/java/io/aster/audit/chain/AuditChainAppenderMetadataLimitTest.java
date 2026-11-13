package io.aster.audit.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.event.EventType;
import io.aster.policy.evidence.EvaluationMetadataBudget;
import io.aster.policy.evidence.EvaluationMetadataBudgetTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追加器的最后防线（aster-api#493）：metadata 超过 {@link AuditChainAppender#MAX_METADATA_SIZE} 时写合法 JSON 的占位对象，
 * 不再截断成非法 JSON；reason 列投影在脱敏变长后按列宽 500 截断，不让整条事件写入失败。只走纯映射，不触库。
 */
class AuditChainAppenderMetadataLimitTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final AuditChainAppender appender = new AuditChainAppender();

  @BeforeEach
  void setUp() {
    appender.objectMapper = JSON;
  }

  private AuditLog map(Map<String, Object> metadata) {
    return appender.toAuditLog(new AuditEvent(EventType.POLICY_EVALUATION, Instant.now(), "t", "m", "f", null, null,
        null, "u", true, 1L, null, metadata, null, null, null, null));
  }

  @Test
  void 超限metadata写成合法JSON占位_保留相关键与原长度() throws Exception {
    String cid = "0f8fad5b-d9cb-469f-a165-70867728950e";
    AuditLog log = map(Map.of("correlationId", cid, "outcome", "REQUIRE_APPROVAL", "role", "DPO",
        "applicantId", "x".repeat(5_000)));

    assertThat(log.metadata.length()).isLessThanOrEqualTo(AuditChainAppender.MAX_METADATA_SIZE);
    Map<String, Object> back = readMap(log.metadata);
    assertThat(back.get("metadataTruncated")).isEqualTo(true);
    assertThat(((Number) back.get("metadataLength")).intValue()).isGreaterThan(AuditChainAppender.MAX_METADATA_SIZE);
    assertThat(back.get("correlationId")).isEqualTo(cid);
    // 结论键全部不保留：from-evidence 读不到 outcome，按不可审批拒绝（fail-closed）
    assertThat(back).doesNotContainKeys("outcome", "role", "applicantId");
    assertThat(log.correlationId).isEqualTo(cid);
  }

  @Test
  void 占位中的标识键截到列宽64() throws Exception {
    AuditLog log = map(Map.of("correlationId", "c".repeat(300), "decisionId", "\"".repeat(300),
        "pad", "p".repeat(5_000)));

    Map<String, Object> back = readMap(log.metadata);
    assertThat(back.get("correlationId")).isEqualTo("c".repeat(64));
    assertThat(back.get("decisionId")).isEqualTo("\"".repeat(64));
  }

  @Test
  void 恰在上限内的metadata原样写入() throws Exception {
    // {"k":"…"} 包装 8 个字符
    String json = map(Map.of("k", "v".repeat(AuditChainAppender.MAX_METADATA_SIZE - 8))).metadata;

    assertThat(json).hasSize(AuditChainAppender.MAX_METADATA_SIZE);
    assertThat(readMap(json)).containsKey("k");
  }

  @Test
  void reason列在脱敏变长后截到列宽500() {
    String reason = "1.1.1.1 ".repeat(32).substring(0, 255); // 脱敏后约 510 字符

    AuditLog log = map(Map.of("reason", reason));

    assertThat(log.reason).hasSizeLessThanOrEqualTo(500).startsWith("***.***.***.***");
  }

  @Test
  void 降级结果经追加器序列化为合法JSON且不超过审计链上限() throws Exception {
    Map<String, Object> m = EvaluationMetadataBudgetTest.normal();
    m.put("applicantId", "1.1.1.1 ".repeat(5_000));
    m.put("reason", "a@b.cc ".repeat(40));
    String esc = "\u0001".repeat(255);
    m.put("agentSession", esc);
    m.put("agentVersion", esc);
    m.put("agentModel", esc);
    m.put("agentProvider", esc);

    String json = map(EvaluationMetadataBudget.fit(m)).metadata;

    assertThat(json.length()).isLessThanOrEqualTo(AuditChainAppender.MAX_METADATA_SIZE);
    Map<String, Object> back = readMap(json);
    assertThat(back.get("outcome")).isEqualTo("REQUIRE_APPROVAL");
    assertThat(back.get("role")).isEqualTo("Credit Officer");
    assertThat(back.get("correlationId")).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
    assertThat(back.get("inputRefs")).isEqualTo(List.of("context.req.amount", "context.req.country"));
  }

  /** 最大合法组合（上限内最长 inputRefs、16 × 64 controls、各文本键 255 且脱敏膨胀）经预检后由追加器实际写出：合法且不超限。 */
  @Test
  void 最大字段组合经预检后追加器实际写出合法JSON() throws Exception {
    String pii = "1.1.1.1 ".repeat(32).substring(0, 255);
    List<String> refs = EvaluationMetadataBudgetTest.largestRefs();
    Map<String, Object> m = EvaluationMetadataBudgetTest.normal();
    m.put("applicantId", pii);
    m.put("locale", pii);
    m.put("reason", pii);
    m.put("role", "Data Protection Officer");
    m.put("ruleId", "x".repeat(255));
    m.put("controls", java.util.stream.IntStream.range(0, 16).mapToObj(i -> "C".repeat(62) + String.format("%02d", i)).toList());
    m.put("inputRefs", refs);
    for (String k : List.of("agentProvider", "agentModel", "agentVersion", "agentSession")) {
      m.put(k, pii);
    }

    String json = map(EvaluationMetadataBudget.fit(m)).metadata;

    assertThat(json.length()).isLessThanOrEqualTo(AuditChainAppender.MAX_METADATA_SIZE);
    Map<String, Object> back = readMap(json);
    assertThat(back).doesNotContainKey("metadataTruncated")
        .containsEntry("role", "Data Protection Officer")
        .containsEntry("ruleId", "x".repeat(255))
        .containsEntry("outcome", "REQUIRE_APPROVAL");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readMap(String json) throws Exception {
    return JSON.readValue(json, Map.class);
  }
}
