package io.aster.policy.export;

import aster.core.ir.CoreModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 探针生成（ADR 0059 修订 3 P2-R12）：上限、基准在首、可复现、每条通过入口 input schema、Decimal 叶子为字符串。 */
class ProbeGeneratorTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void creditLimitInputs() throws Exception {
    CoreModel.Module m = EntrySchemaGeneratorTest.platformModule("b-credit-limit");
    List<JsonNode> a = ProbeGenerator.inputs(m, "decide", ProbeGenerator.MAX_PROBES);
    List<JsonNode> b = ProbeGenerator.inputs(m, "decide", ProbeGenerator.MAX_PROBES);
    assertThat(a).hasSizeBetween(2, ProbeGenerator.MAX_PROBES);
    assertThat(a.stream().map(ValueJson::write).toList()).isEqualTo(b.stream().map(ValueJson::write).toList());
    assertThat(ValueJson.write(a.get(0))).isEqualTo("{\"applicant\":{\"activeDefaults\":0,\"creditScore\":0,"
        + "\"monthlyDebt\":\"0\",\"monthlyIncome\":\"0\",\"requestedAmount\":\"0\"}}");
    assertThat(a.stream().map(ValueJson::write).distinct()).hasSize(a.size());
    JsonNode inputSchema = JSON.readTree(EntrySchemaGenerator.generate(m, "decide").inputJson());
    JsonNode entrySchema = inputSchema.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) entrySchema).put("$ref", "#/$defs/entry.decide");
    for (JsonNode input : a) {
      assertThat(JsonSchemas.errors(entrySchema, input)).as(input.toString()).isEmpty();
      assertThat(input.at("/applicant/monthlyIncome").isTextual()).isTrue();
    }
    assertThat(a.stream().map(i -> i.at("/applicant/requestedAmount").asText()))
        .contains("50000", "49999", "50001");
    assertThat(a.stream().map(i -> i.at("/applicant/creditScore").asInt())).contains(620, 619, 621, 679, 680);
  }

  @Test
  void respectsMaxAndKeepsBaselineFirst() throws Exception {
    CoreModel.Module m = EntrySchemaGeneratorTest.platformModule("b-credit-limit");
    List<JsonNode> few = ProbeGenerator.inputs(m, "decide", 3);
    assertThat(few).hasSize(3);
    assertThat(ValueJson.write(few.get(0)))
        .isEqualTo(ValueJson.write(ProbeGenerator.inputs(m, "decide", 256).get(0)));
  }

  @Test
  void enumsMaybesAndZeroParameterEntries() throws Exception {
    CoreModel.Module m = EntrySchemaGeneratorTest.platformModule("a-inputs");
    List<String> tier = ProbeGenerator.inputs(m, "tierCode", 256).stream().map(ValueJson::write).toList();
    assertThat(tier).contains("{\"tier\":\"Gold\"}", "{\"tier\":\"Silver\"}");
    List<String> orZero = ProbeGenerator.inputs(m, "orZero", 256).stream().map(ValueJson::write).toList();
    assertThat(orZero.get(0)).endsWith(":null}");
    assertThat(orZero).hasSizeGreaterThanOrEqualTo(2);
    CoreModel.Module multi = EntrySchemaGeneratorTest.platformModule("a-multi-entry");
    assertThat(ProbeGenerator.inputs(multi, "answer", 256).stream().map(ValueJson::write)).containsExactly("{}");
  }

  /** Long 字面量在 int64 边界时 ±1 饱和后截到 ±(2⁵³−1)，不回绕（CNL 写不出负的 int64 下界，按 IR 构造）。 */
  @Test
  void longLiteralsAtInt64BoundsSaturate() {
    aster.core.ir.CoreModel.TypeName longT = new aster.core.ir.CoreModel.TypeName();
    longT.name = "Long";
    aster.core.ir.CoreModel.Param n = new aster.core.ir.CoreModel.Param();
    n.name = "n";
    n.type = longT;
    aster.core.ir.CoreModel.LongE max = new aster.core.ir.CoreModel.LongE();
    max.value = Long.MAX_VALUE;
    aster.core.ir.CoreModel.ListE body = new aster.core.ir.CoreModel.ListE();
    body.elements = List.of(max);
    aster.core.ir.CoreModel.Return ret = new aster.core.ir.CoreModel.Return();
    ret.expr = body;
    aster.core.ir.CoreModel.Block block = new aster.core.ir.CoreModel.Block();
    block.statements = List.of(ret);
    aster.core.ir.CoreModel.Func f = new aster.core.ir.CoreModel.Func();
    f.name = "f";
    f.params = List.of(n);
    f.ret = longT;
    f.body = block;
    aster.core.ir.CoreModel.Module m = new aster.core.ir.CoreModel.Module();
    m.name = "t.long";
    m.decls = List.of(f);
    List<String> inputs = ProbeGenerator.inputs(m, "f", 256).stream().map(ValueJson::write).toList();
    // 只有上界字面量：MAX + 1 若回绕会多出 −(2⁵³−1)
    assertThat(inputs).containsExactly("{\"n\":0}", "{\"n\":9007199254740991}");
  }
}
