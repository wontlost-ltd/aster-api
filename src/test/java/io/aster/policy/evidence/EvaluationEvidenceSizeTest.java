package io.aster.policy.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估事件 metadata 的总长预算（ADR 0046 §11.6 第 4 条）：审计链 metadata 超过 4096 字符会被截断成非法 JSON，
 * from-evidence 读不出 outcome 即 409。把每个既有键填到其校验上限，再加上上限内最长的 {@code inputRefs} 与
 * {@code verifiedInputsRequired}，序列化长度必须小于 4096。超出时只能下调 inputRefs 上限，不得提高审计链上限。
 *
 * <p>各键的上限：correlationId 为服务端生成的 UUID（36）；outcome 为 Verdict 结论（最长 REQUIRE_APPROVAL）；
 * ruleId / role / reason / agent 四键按 {@link AgentIdentity#MAX_TEXT} 裁剪；controls 为 16 × 64；版本 id 为 Long；
 * 两个哈希为 64 位 hex；profile 受注册表 id 形态约束（≤ 64）；另加 /evaluate-source 写的四个附加键与 contextSize。
 */
class EvaluationEvidenceSizeTest {
  /** AuditChainAppender.MAX_METADATA_SIZE（私有常量，按字符计）。 */
  private static final int AUDIT_METADATA_LIMIT = 4096;

  @Test
  void maximalLegalMetadataWithInputRefsFitsAuditLimit() throws Exception {
    List<String> controls = IntStream.range(0, 40).mapToObj(i -> "C".repeat(100) + i).toList();
    String max = "x".repeat(AgentIdentity.MAX_TEXT);
    List<String> refs = largestAcceptedInputRefs();
    assertThat(EvaluationEvidence.capInputRefs(refs)).as("上限内的引用集原样保留").isEqualTo(refs);

    EvaluationEvidence e = new EvaluationEvidence("0f8fad5b-d9cb-469f-a165-70867728950e", "REQUIRE_APPROVAL", max,
        controls, Long.MAX_VALUE, new AgentIdentity(max, max, max, max), max, max, "p".repeat(64),
        "a".repeat(64), "b".repeat(64))
        .withInputRefs(refs, true);
    Map<String, Object> m = new HashMap<>(Map.of("sourceFormat", "cnl", "locale", "en-US", "dynamicExecution", true,
        "namedContext", true, "contextSize", 1));
    m.putAll(e.toMetadata());
    assertThat(m).containsEntry(EvaluationEvidence.KEY_INPUT_REFS, refs);

    String json = new ObjectMapper().writeValueAsString(m);
    assertThat(json.length()).as(json).isLessThan(AUDIT_METADATA_LIMIT);
  }

  /** 字节数恰好不超过上限的引用集（单字节字符，字符数 = 字节数）。 */
  private static List<String> largestAcceptedInputRefs() {
    List<String> refs = new ArrayList<>();
    int i = 0;
    while (true) {
      List<String> next = new ArrayList<>(refs);
      next.add("context.req.field" + i++);
      if (EvaluationEvidence.inputRefsJsonBytes(next) > EvaluationEvidence.MAX_INPUT_REFS_BYTES
          || next.size() > EvaluationEvidence.MAX_INPUT_REFS) {
        break;
      }
      refs = next;
    }
    // 补一个长尾项，把字节数撑到上限
    int room = EvaluationEvidence.MAX_INPUT_REFS_BYTES - EvaluationEvidence.inputRefsJsonBytes(refs) - 3;
    if (room > 0 && refs.size() < EvaluationEvidence.MAX_INPUT_REFS) {
      refs.add("z".repeat(room));
    }
    assertThat(EvaluationEvidence.inputRefsJsonBytes(refs)).isLessThanOrEqualTo(EvaluationEvidence.MAX_INPUT_REFS_BYTES)
        .isGreaterThan(EvaluationEvidence.MAX_INPUT_REFS_BYTES - 20);
    return refs;
  }
}
