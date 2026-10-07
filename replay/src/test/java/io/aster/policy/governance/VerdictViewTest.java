package io.aster.policy.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;

// replay 模块测试只依赖 JUnit（无 AssertJ），断言语义与 brief 中 AssertJ 版本一致。
class VerdictViewTest {
  @Test void 识别Verdict值() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("__type", "Verdict"); m.put("outcome", "REQUIRE_APPROVAL"); m.put("role", "Senior Underwriter"); m.put("reason", "over cap");
    assertEquals(Optional.of(new VerdictView("REQUIRE_APPROVAL", "Senior Underwriter", "over cap")), VerdictView.from(m));
  }
  @Test void allow无role无reason() {
    assertEquals(Optional.of(new VerdictView("ALLOW", null, null)), VerdictView.from(Map.of("__type", "Verdict", "outcome", "ALLOW")));
  }
  @Test void 非Verdict返回empty() {
    assertEquals(Optional.empty(), VerdictView.from(Map.of("__type", "Decision", "approved", true)));
    assertEquals(Optional.empty(), VerdictView.from(true));
    assertEquals(Optional.empty(), VerdictView.from("APPROVED"));
    assertEquals(Optional.empty(), VerdictView.from(null));
  }
  // ADR 0039 §2.3：缺省键不写出；字段按字母序，与 EvaluationResponse 一致。
  @Test void 序列化不写null键且字母序() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertEquals("{\"outcome\":\"ALLOW\"}", mapper.writeValueAsString(new VerdictView("ALLOW", null, null)));
    assertEquals("{\"outcome\":\"REQUIRE_APPROVAL\",\"reason\":\"over cap\",\"role\":\"Senior Underwriter\"}",
        mapper.writeValueAsString(new VerdictView("REQUIRE_APPROVAL", "Senior Underwriter", "over cap")));
  }
}
