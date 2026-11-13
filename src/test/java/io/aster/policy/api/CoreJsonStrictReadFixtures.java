package io.aster.policy.api;

import aster.core.ir.CoreIrJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.policy.parser.InProcessCnlParser;

import java.util.List;

/** 严格读方测试共用的 Core IR 夹具：一份合法 v2 IR 与四种非法输入（ADR 0055 §5）。 */
public final class CoreJsonStrictReadFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = """
        Module strict.read.

        Define Driver has age as Int.

        Rule main given driver as Driver, produce Int:
          Return driver.age.
        """;

    /** 手写的 v1 IR：没有 schemaVersion、origin 与 v2 恒输出字段。 */
    public static final String V1_IR = "{\"name\":\"strict.v1\",\"decls\":[{\"kind\":\"Func\",\"name\":\"main\","
        + "\"params\":[{\"name\":\"driver\",\"type\":{\"kind\":\"TypeName\",\"name\":\"Int\"}}],"
        + "\"ret\":{\"kind\":\"TypeName\",\"name\":\"Int\"},\"body\":{\"kind\":\"Block\","
        + "\"statements\":[{\"kind\":\"Return\",\"expr\":{\"kind\":\"Int\",\"value\":1}}]}}]}";

    public static String v2Ir() {
        return CoreIrJson.serialize(InProcessCnlParser.parse(SOURCE, null, null).core());
    }

    /** v1、字符串版本、未知字段、缺字段四种非法输入。 */
    public static List<String> invalidIrs() throws Exception {
        ObjectNode stringVersion = (ObjectNode) MAPPER.readTree(v2Ir());
        stringVersion.put("schemaVersion", "2");
        ObjectNode unknownField = (ObjectNode) MAPPER.readTree(v2Ir());
        unknownField.put("extra", true);
        ObjectNode missingField = (ObjectNode) MAPPER.readTree(v2Ir());
        missingField.remove("profile");
        return List.of(V1_IR, MAPPER.writeValueAsString(stringVersion), MAPPER.writeValueAsString(unknownField),
            MAPPER.writeValueAsString(missingField));
    }

    private CoreJsonStrictReadFixtures() {}
}
