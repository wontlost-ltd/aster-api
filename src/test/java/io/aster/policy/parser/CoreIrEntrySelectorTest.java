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
    void 未指定目标或目标不存在时原样返回() {
        assertThat(CoreIrEntrySelector.withEntry(TWO_FUNCS, null)).isSameAs(TWO_FUNCS);
        assertThat(CoreIrEntrySelector.withEntry(TWO_FUNCS, "")).isSameAs(TWO_FUNCS);
        assertThat(CoreIrEntrySelector.withEntry(TWO_FUNCS, "missing")).isSameAs(TWO_FUNCS);
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
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'helper'")
            .hasMessageContaining("'main'");
        assertThat(CoreIrEntrySelector.withEntry(json, "main")).isNotNull();
    }
}
