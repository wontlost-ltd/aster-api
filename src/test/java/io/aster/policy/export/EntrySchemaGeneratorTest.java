package io.aster.policy.export;

import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.parser.InProcessCnlParser;
import aster.core.lowering.CoreLowering;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口 JSON Schema（ADR 0059 修订 3 P2-R4）：对 conformance 每个带源码的黄金包，以其 platform-core.json 与包内入口生成，
 * {@code schema/input.json} / {@code output.json} 必须与黄金包逐字节相同；E733 / E738 各类型与路径。
 */
class EntrySchemaGeneratorTest {
  static final Path FIXTURES = Path.of("src/test/resources/package-v1");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> GOLDEN = List.of(
      "b-credit-limit", "b-vocab-de", "b-zh", "b-alias", "a-decimal", "a-inputs", "a-multi-entry");

  static CoreModel.Module platformModule(String pkg) throws Exception {
    return CoreIrJson.read(Files.readString(FIXTURES.resolve("src/" + pkg + "/platform-core.json")));
  }

  static List<String> entries(String pkg) throws Exception {
    List<String> out = new ArrayList<>();
    JSON.readTree(FIXTURES.resolve("src/" + pkg + "/fixture.json").toFile()).get("entries")
        .forEach(e -> out.add(e.get("function").asText()));
    return out;
  }

  private static CoreModel.Module compile(String source) {
    return new CoreLowering().lowerModule(InProcessCnlParser.parse(source, "en-US").module());
  }

  @Test
  void goldenSchemasAreByteIdentical() throws Exception {
    for (String pkg : GOLDEN) {
      EntrySchemaGenerator.EntrySchemas s = EntrySchemaGenerator.generate(platformModule(pkg), entries(pkg));
      assertThat(s.diagnostics()).as(pkg).isEmpty();
      assertThat(new String(s.inputJson(), StandardCharsets.UTF_8)).as(pkg + " input")
          .isEqualTo(Files.readString(FIXTURES.resolve("packages/" + pkg + "/schema/input.json")));
      assertThat(new String(s.outputJson(), StandardCharsets.UTF_8)).as(pkg + " output")
          .isEqualTo(Files.readString(FIXTURES.resolve("packages/" + pkg + "/schema/output.json")));
    }
  }

  private static List<ExportDiagnostic> diagnostics(String source, String entry) {
    return EntrySchemaGenerator.generate(compile(source), entry).diagnostics();
  }

  @Test
  void e733ForTypesWithoutJsonForm() {
    String src = String.join("\n",
        "Module t.e733.",
        "",
        "Define Box has inner as Option of Option of Int.",
        "",
        "Rule f given r as Result of Int and Text, m as Map Int to Int, b as Box, produce Int:",
        "  Return 1.");
    List<ExportDiagnostic> ds = diagnostics(src, "f");
    assertThat(ds).extracting(ExportDiagnostic::code).containsOnly("E733");
    assertThat(ds).extracting(ExportDiagnostic::message).contains(
        "Entry 'f' at r has type Result<Int, Text>, which has no JSON input/output form",
        "Entry 'f' at m has type Map<Int, Int>, which has no JSON input/output form",
        "Entry 'f' at b.inner has type Option<Option<Int>>, which has no JSON input/output form");
    assertThat(ds).allSatisfy(d -> assertThat(d.line()).isNull());
  }

  /** 函数类型（CNL 写不出入口函数类型的形参，按 IR 构造）；Java IR 的 Enum 变体只有名字，带载荷 Enum 不会出现。 */
  @Test
  void e733ForFunctionTypedParameter() {
    CoreModel.TypeName intT = new CoreModel.TypeName();
    intT.name = "Int";
    CoreModel.FuncType fn = new CoreModel.FuncType();
    fn.params = List.of(intT);
    fn.ret = intT;
    CoreModel.Param p = new CoreModel.Param();
    p.name = "g";
    p.type = fn;
    CoreModel.Func f = new CoreModel.Func();
    f.name = "f";
    f.params = List.of(p);
    f.ret = intT;
    CoreModel.Module m = new CoreModel.Module();
    m.name = "t.fn";
    m.decls = List.of(f);
    assertThat(EntrySchemaGenerator.generate(m, "f").diagnostics()).extracting(ExportDiagnostic::message)
        .containsExactly("Entry 'f' at g has type Function<Int -> Int>, which has no JSON input/output form");
  }

  @Test
  void resultIsAllowedAsOutput() {
    String src = String.join("\n", "Module t.ok.", "", "Rule f given x as Int, produce Result of Int and Text:",
        "  Return ok of x.");
    assertThat(diagnostics(src, "f")).isEmpty();
  }

  @Test
  void e738ForDateTimeNestedInDataAndList() {
    String src = String.join("\n",
        "Module t.e738.",
        "",
        "Define Claim has id as Text, submittedAt as DateTime.",
        "",
        "Rule f given claims as List of Claim, produce List of Claim:",
        "  Return claims.");
    List<ExportDiagnostic> ds = diagnostics(src, "f");
    assertThat(ds).extracting(ExportDiagnostic::code).containsOnly("E738");
    assertThat(ds).extracting(ExportDiagnostic::message).containsExactly(
        "Entry 'f' uses DateTime at claims.List<Claim>.submittedAt; its value form is not defined yet",
        "Entry 'f' uses DateTime at return.List<Claim>.submittedAt; its value form is not defined yet");
  }

  @Test
  void schemaDocumentShape() throws Exception {
    String src = String.join("\n", "Module t.shape.", "", "Rule f given x as Decimal, produce Decimal:", "  Return x.");
    EntrySchemaGenerator.EntrySchemas s = EntrySchemaGenerator.generate(compile(src), "f");
    JsonNode input = JSON.readTree(s.inputJson());
    assertThat(input.fieldNames()).toIterable().containsExactly("$defs", "$schema", "title");
    assertThat(input.get("title").asText()).isEqualTo("t.shape entry inputs");
    assertThat(input.at("/$defs/entry.f/required").toString()).isEqualTo("[\"x\"]");
    assertThat(JSON.readTree(s.outputJson()).at("/$defs/entry.f/type").asText()).isEqualTo("string");
  }
}
