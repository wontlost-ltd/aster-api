package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 探针的平台期望值（P2-R12）：非整数 Decimal 求值成功（#558）、超时丢弃、除零为 EVAL_ERROR、文件为值规范 JSON。 */
class ProbeRunnerTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static String core(String pkg) throws Exception {
    return Files.readString(EntrySchemaGeneratorTest.FIXTURES.resolve("src/" + pkg + "/platform-core.json"));
  }

  private static JsonNode input(String json) throws Exception {
    return JSON.readTree(json);
  }

  @Test
  void fractionalDecimalProbeSucceeds() throws Exception {
    ProbeRunner.ProbesFile f = ProbeRunner.run(core("a-decimal"), "withTax",
        List.of(input("{\"amount\":\"12.34\"}"), input("{\"amount\":\"10\"}")));
    assertThat(f.count()).isEqualTo(2);
    assertThat(new String(f.json(), StandardCharsets.UTF_8)).isEqualTo("{\"probes\":["
        + "{\"entry\":\"withTax\",\"expected\":{\"ok\":true,\"value\":\"13.36\"},\"input\":{\"amount\":\"12.34\"}},"
        + "{\"entry\":\"withTax\",\"expected\":{\"ok\":true,\"value\":\"10.82\"},\"input\":{\"amount\":\"10\"}}]}");
    assertThat(f.sha256()).hasSize(64);
  }

  @Test
  void generatedDecimalProbesNeverMixDecimalAndDouble() throws Exception {
    String coreJson = core("a-decimal");
    List<JsonNode> inputs = ProbeGenerator.inputs(aster.core.ir.CoreIrJson.read(coreJson), "withTax", 256);
    ProbeRunner.ProbesFile f = ProbeRunner.run(coreJson, "withTax", inputs);
    assertThat(f.count()).isEqualTo(inputs.size());
    assertThat(new String(f.json(), StandardCharsets.UTF_8)).doesNotContain("EVAL_ERROR");
  }

  @Test
  void timeoutIsDroppedAndDivisionByZeroIsEvalError() throws Exception {
    ProbeRunner.ProbesFile spin = ProbeRunner.run(core("a-timeout"), "spin",
        List.of(input("{\"n\":3}"), input("{\"n\":60}")));
    assertThat(spin.count()).isEqualTo(1);
    assertThat(new String(spin.json(), StandardCharsets.UTF_8)).contains("{\"n\":3}").doesNotContain("60");
    ProbeRunner.ProbesFile ratio = ProbeRunner.run(core("a-multi-entry"), "ratio", List.of(input("{\"x\":0}")));
    assertThat(new String(ratio.json(), StandardCharsets.UTF_8))
        .isEqualTo("{\"probes\":[{\"entry\":\"ratio\",\"expected\":{\"code\":\"EVAL_ERROR\",\"ok\":false},\"input\":{\"x\":0}}]}");
  }

  @Test
  void goldenProbeExpectationsReproduce() throws Exception {
    for (String pkg : List.of("b-credit-limit", "a-inputs")) {
      JsonNode golden = JSON.readTree(Files.readString(EntrySchemaGeneratorTest.FIXTURES.resolve("packages/" + pkg + "/tests/probes.json")));
      for (JsonNode p : golden.get("probes")) {
        ProbeRunner.ProbesFile f = ProbeRunner.run(core(pkg), p.get("entry").asText(), List.of(p.get("input")));
        assertThat(JSON.readTree(f.json()).at("/probes/0/expected")).as(pkg + " " + p).isEqualTo(p.get("expected"));
      }
    }
  }
}
