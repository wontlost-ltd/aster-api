package io.aster.policy.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreIrEntrySelectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TWO_FUNCS = """
        {"name":"m","decls":[
          {"kind":"Data","name":"Req","fields":[]},
          {"kind":"Func","name":"add2","params":[],"body":{"kind":"Int","value":1}},
          {"kind":"Enum","name":"Op","variants":["A"]},
          {"kind":"Func","name":"twice","params":[],"body":{"kind":"Double","value":0.450}}
        ]}""";

    private static List<String> declOrder(String json) throws Exception {
        List<String> order = new ArrayList<>();
        for (JsonNode d : MAPPER.readTree(json).get("decls")) {
            order.add(d.get("kind").asText() + ":" + d.get("name").asText());
        }
        return order;
    }

    @Test
    void 目标已是第一个函数时原样返回同一字符串() {
        assertThat(CoreIrEntrySelector.withEntry(TWO_FUNCS, "add2")).isSameAs(TWO_FUNCS);
    }

    @Test
    void 未指定目标时原样返回() {
        assertThat(CoreIrEntrySelector.withEntry(TWO_FUNCS, null)).isSameAs(TWO_FUNCS);
        assertThat(CoreIrEntrySelector.withEntry(TWO_FUNCS, "")).isSameAs(TWO_FUNCS);
    }

    @Test
    void 目标不存在时抛出而非原样返回() {
        assertThatThrownBy(() -> CoreIrEntrySelector.withEntry(TWO_FUNCS, "missing"))
            .isInstanceOf(EntryFunctionException.class)
            .hasMessageContaining("'missing'")
            .hasMessageContaining("add2")
            .hasMessageContaining("twice");
        assertThatThrownBy(() -> CoreIrEntrySelector.withEntry("{\"name\":\"m\",\"decls\":[]}", "f"))
            .isInstanceOf(EntryFunctionException.class);
    }

    /**
     * 重排必须逐字节保留每个字面量：重序列化会把 -0.0 变成 0.0（1/x 由 -Infinity 变 +Infinity）、
     * 改写指数形式、去掉 \u2028 的转义。这里只允许 decls 元素的先后顺序变化。
     */
    @Test
    void 重排逐字节保留字面量() {
        String json = "{\"name\":\"m\",  \"decls\" : [ "
            + "{\"kind\":\"Func\",\"name\":\"a\",\"body\":{\"kind\":\"Double\",\"value\":-0.0}} ,\n"
            + "{\"kind\":\"Data\",\"name\":\"D\",\"note\":\"x\\u2028y\"},"
            + "{\"kind\":\"Func\",\"name\":\"b\",\"body\":{\"kind\":\"Double\",\"value\":1e5,\"t\":\"\\u2028\"}}"
            + " ], \"origin\":{\"v\":-0.0}}";
        String a = "{\"kind\":\"Func\",\"name\":\"a\",\"body\":{\"kind\":\"Double\",\"value\":-0.0}}";
        String d = "{\"kind\":\"Data\",\"name\":\"D\",\"note\":\"x\\u2028y\"}";
        String b = "{\"kind\":\"Func\",\"name\":\"b\",\"body\":{\"kind\":\"Double\",\"value\":1e5,\"t\":\"\\u2028\"}}";

        String out = CoreIrEntrySelector.withEntry(json, "b");

        assertThat(out).isEqualTo("{\"name\":\"m\",  \"decls\" : [ " + b + " ,\n" + a + "," + d
            + " ], \"origin\":{\"v\":-0.0}}");
        assertThat(out).hasSameSizeAs(json);
    }

    @Test
    void 目标插到第一个函数之前且其余声明相对顺序不变() throws Exception {
        String out = CoreIrEntrySelector.withEntry(TWO_FUNCS, "twice");

        assertThat(declOrder(out)).containsExactly("Data:Req", "Func:twice", "Func:add2", "Enum:Op");
    }

    @Test
    void 重排保留小数原始精度() {
        assertThat(CoreIrEntrySelector.withEntry(TWO_FUNCS, "twice")).contains("0.450");
    }

    /** 重复的顶层 decls：本类只会重排第一个，Jackson 却保留最后一个——必须拒绝而不是执行错函数。 */
    @Test
    void 重复的顶层decls键被拒绝() {
        String json = """
            {"name":"m",
             "decls":[{"kind":"Func","name":"a","params":[]},{"kind":"Func","name":"b","params":[]}],
             "decls":[{"kind":"Func","name":"a","params":[]},{"kind":"Func","name":"b","params":[]}]}""";

        assertThatThrownBy(() -> CoreIrEntrySelector.withEntry(json, "b"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("decls");
    }

    @Test
    void 被导入模块的mangle后main不触发抢占检查() throws Exception {
        String json = """
            {"name":"app","decls":[
              {"kind":"Func","name":"risk_Scoring__main","params":[]},
              {"kind":"Func","name":"run","params":[]}
            ]}""";

        assertThat(declOrder(CoreIrEntrySelector.withEntry(json, "run")))
            .containsExactly("Func:run", "Func:risk_Scoring__main");
    }

    @Test
    void 同名重载一起移动() throws Exception {
        String json = """
            {"name":"m","decls":[
              {"kind":"Func","name":"a","params":[]},
              {"kind":"Func","name":"b","params":[{"name":"x"}]},
              {"kind":"Func","name":"b","params":[]}
            ]}""";

        assertThat(declOrder(CoreIrEntrySelector.withEntry(json, "b")))
            .containsExactly("Func:b", "Func:b", "Func:a");
    }

    @Test
    void 模块含main且目标不是main时拒绝执行() {
        String json = """
            {"name":"m","decls":[
              {"kind":"Func","name":"helper","params":[]},
              {"kind":"Func","name":"main","params":[]}
            ]}""";

        assertThatThrownBy(() -> CoreIrEntrySelector.withEntry(json, "helper"))
            .isInstanceOf(EntryFunctionException.class)
            .hasMessageContaining("'helper'")
            .hasMessageContaining("'main'");
        assertThat(CoreIrEntrySelector.withEntry(json, "main")).isNotNull();
    }
}
