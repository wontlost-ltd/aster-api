package io.aster.policy.rest.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词汇表上限校验器：每条上限的边界值放行、越界拒绝；真实形状与套餐配额规模的词汇表放行。
 */
class VocabularyBoundsTest {

    /** 与 cloud 组装的 DomainVocabulary 同形的单条映射。 */
    static Map<String, Object> mapping(int i) {
        return Map.of("canonical", "Field" + i, "localized", "feld" + i, "kind", "field",
            "parent", "Driver", "aliases", List.of("f" + i));
    }

    static Map<String, Object> vocab(String section, List<?> entries) {
        Map<String, Object> v = new HashMap<>(Map.of("id", "custom", "name", "Custom",
            "locale", "en-US", "version", "user"));
        v.put(section, entries);
        return v;
    }

    @Test
    void 空词汇与null放行() {
        assertThat(VocabularyBounds.validate(null)).isNull();
        assertThat(VocabularyBounds.validate(Map.of())).isNull();
    }

    @Test
    void Pro套餐规模的真实词汇放行() {
        List<Object> fields = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            fields.add(mapping(i));
        }
        Map<String, Object> v = vocab("fields", fields);
        v.put("metadata", Map.of("author", "u", "description", "d"));
        assertThat(VocabularyBounds.validate(v)).isNull();
    }

    @Test
    void 单分组条目数上限() {
        int max = VocabularyBounds.MAX_ENTRIES_PER_SECTION;
        assertThat(VocabularyBounds.validate(vocab("fields", Collections.nCopies(max, 1)))).isNull();
        assertThat(VocabularyBounds.validate(vocab("fields", Collections.nCopies(max + 1, 1))))
            .contains("单个分组");
    }

    @Test
    void 映射总数上限() {
        int per = VocabularyBounds.MAX_ENTRIES_PER_SECTION;
        Map<String, Object> atLimit = vocab("structs", Collections.nCopies(per, 1));
        atLimit.put("fields", Collections.nCopies(VocabularyBounds.MAX_TOTAL_MAPPINGS - per, 1));
        assertThat(VocabularyBounds.validate(atLimit)).isNull();
        atLimit.put("functions", List.of(1));
        assertThat(VocabularyBounds.validate(atLimit)).contains("映射总数");
    }

    @Test
    void 映射内数组元素数上限() {
        int max = VocabularyBounds.MAX_NESTED_LIST_SIZE;
        Map<String, Object> ok = Map.of("canonical", "A", "aliases", Collections.nCopies(max, "x"));
        Map<String, Object> bad = Map.of("canonical", "A", "aliases", Collections.nCopies(max + 1, "x"));
        assertThat(VocabularyBounds.validate(vocab("fields", List.of(ok)))).isNull();
        assertThat(VocabularyBounds.validate(vocab("fields", List.of(bad)))).contains("映射内数组");
    }

    @Test
    void 对象键数上限() {
        Map<String, Object> wide = new HashMap<>();
        for (int i = 0; i <= VocabularyBounds.MAX_OBJECT_KEYS; i++) {
            wide.put("k" + i, "v");
        }
        assertThat(VocabularyBounds.validate(vocab("fields", List.of(wide)))).contains("键数");
    }

    @Test
    void 字符串长度上限() {
        int max = VocabularyBounds.MAX_STRING_LENGTH;
        assertThat(VocabularyBounds.validate(Map.of("description", "x".repeat(max)))).isNull();
        assertThat(VocabularyBounds.validate(Map.of("description", "x".repeat(max + 1))))
            .contains("字符串长度");
        // 键同样受限
        assertThat(VocabularyBounds.validate(Map.of("k".repeat(max + 1), "v"))).contains("字符串长度");
    }

    @Test
    void 异常嵌套被拒() {
        // aliases 数组里再嵌数组 = 第 5 层，超出期望形状
        Map<String, Object> deep = Map.of("canonical", "A", "aliases", List.of(List.of("x")));
        assertThat(VocabularyBounds.validate(vocab("fields", List.of(deep)))).contains("嵌套层数");
        Map<String, Object> deepMap = Map.of("canonical", "A", "aliases", List.of(Map.of("a", "b")));
        assertThat(VocabularyBounds.validate(vocab("fields", List.of(deepMap)))).contains("嵌套层数");
    }
}
