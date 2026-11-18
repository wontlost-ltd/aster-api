package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/** 测试辅助：按 JSON Schema 2020-12 校验（networknt，与 core 运行时同一版本），返回错误消息列表（空即通过）。 */
final class JsonSchemas {
  private static final ObjectMapper JSON = new ObjectMapper();

  private JsonSchemas() {
  }

  static List<String> errors(Path schemaFile, JsonNode instance) {
    try {
      return errors(JSON.readTree(schemaFile.toFile()), instance);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<String> errors(JsonNode schemaNode, JsonNode instance) {
    String id = schemaNode.path("$id").asText("urn:aster:test-schema");
    Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(SchemaLocation.of(id), schemaNode);
    return schema.validate(instance).stream().map(Object::toString).toList();
  }
}
