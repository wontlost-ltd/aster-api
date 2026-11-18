package io.aster.policy.export;

import aster.core.canonical.CanonicalJson;
import aster.core.ir.CoreModel;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 包内 {@code schema/input.json} 与 {@code schema/output.json} 的生成器（ADR 0059 修订 3 P2-R4）：基于 Core IR 类型，
 * 与 conformance 黄金包的 schema 逐字节相同。文档形如
 * {@code {"$defs":{…},"$schema":"https://json-schema.org/draft/2020-12/schema","title":"<moduleName> entry inputs|outputs"}}，
 * 规范 JSON 字节（core {@link CanonicalJson}）。入口定义 {@code entry.<f>}（输入：全部形参必填、无多余字段），
 * Data 定义 {@code data.<T>}。
 *
 * <p>平台导出恰有一个入口（P2-R9），但生成器接受入口列表，使多入口的 conformance 黄金包也能逐字节复现。
 */
public final class EntrySchemaGenerator {
  static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** 生成结果；{@code diagnostics} 非空时 schema 字节不可用（含占位的空 schema）。 */
  public record EntrySchemas(byte[] inputJson, byte[] outputJson, List<ExportDiagnostic> diagnostics) {
    public EntrySchemas {
      diagnostics = List.copyOf(diagnostics);
    }
  }

  private EntrySchemaGenerator() {
  }

  /** 单入口（平台导出）。 */
  public static EntrySchemas generate(CoreModel.Module module, String entry) {
    return generate(module, List.of(entry));
  }

  /**
   * @throws IllegalArgumentException 某个入口不是模块中的函数
   */
  public static EntrySchemas generate(CoreModel.Module module, List<String> entries) {
    ExportTypeWalker walker = new ExportTypeWalker(module);
    ObjectNode inputDefs = F.objectNode();
    ObjectNode outputDefs = F.objectNode();
    for (String entry : entries) {
      CoreModel.Func func = findFunc(module, entry);
      ObjectNode properties = F.objectNode();
      List<String> required = new ArrayList<>();
      for (CoreModel.Param p : func.params) {
        properties.set(p.name, walker.schema(p.type, ExportTypeWalker.Direction.INPUT, inputDefs, entry, p.name));
        required.add(p.name);
      }
      ObjectNode entryInput = F.objectNode().put("type", "object");
      entryInput.set("properties", properties);
      entryInput.set("required", F.arrayNode().addAll(required.stream().map(F::textNode).toList()));
      entryInput.put("additionalProperties", false);
      inputDefs.set("entry." + entry, entryInput);
      outputDefs.set("entry." + entry,
          walker.schema(func.ret, ExportTypeWalker.Direction.OUTPUT, outputDefs, entry, "return"));
    }
    return new EntrySchemas(document(module.name + " entry inputs", inputDefs),
        document(module.name + " entry outputs", outputDefs), walker.diagnostics());
  }

  static CoreModel.Func findFunc(CoreModel.Module module, String entry) {
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f && f.name.equals(entry)) {
        return f;
      }
    }
    throw new IllegalArgumentException("入口 " + entry + " 不在模块中");
  }

  private static byte[] document(String title, ObjectNode defs) {
    ObjectNode doc = F.objectNode();
    doc.put("$schema", DRAFT);
    doc.put("title", title);
    doc.set("$defs", defs);
    return CanonicalJson.canonicalJson(doc).getBytes(StandardCharsets.UTF_8);
  }
}
