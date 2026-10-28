package io.aster.policy.rest.model;

import java.util.List;
import java.util.Map;

/**
 * 请求体领域词汇表（DomainVocabulary JSON）的大小与形状上限，供 /compile 与 /evaluate-source 共用。
 *
 * <p>词汇表是独立的 body 字段，不受源码 16 KiB 上限约束；全局 body 上限只限总字节，
 * 挡不住「大量短条目」或「异常嵌套」放大 {@code VocabularyLoader} / {@code VocabularyScopes.build}
 * 的反序列化与建索引成本。故在进入词汇解析前按结构快速拒绝（400）。
 *
 * <p>期望形状：顶层对象 → 分组数组（structs/fields/functions/enumValues/literals）→ 映射对象
 * → aliases 字符串数组；另有 metadata 等浅层对象。超过 {@link #MAX_DEPTH} 层即视为异常嵌套。
 *
 * <p>上限取值远高于 aster-cloud 套餐词条配额（Pro 5000 / Team 25000），正常用户永远碰不到；
 * 默认 1 MiB body 上限下实际能送达的条目数更少，这里的上限是在运维调大 body 上限后仍然有效的硬闸。
 * cloud 目前不限制单个词条的 aliases 个数与 description 长度，故映射内数组与字符串上限也留足余量。
 */
public final class VocabularyBounds {

    /** 单个分组数组的最大条目数（高于 Team 套餐 25000 词条配额）。 */
    public static final int MAX_ENTRIES_PER_SECTION = 30_000;
    /** 所有分组合计的最大映射数。 */
    public static final int MAX_TOTAL_MAPPINGS = 60_000;
    /** 映射内部数组（如 aliases）的最大元素数。 */
    public static final int MAX_NESTED_LIST_SIZE = 256;
    /** 任一对象的最大键数。 */
    public static final int MAX_OBJECT_KEYS = 32;
    /** 任一字符串（键或值）的最大长度。 */
    public static final int MAX_STRING_LENGTH = 16_384;
    /** 最大嵌套层数：顶层对象=1，分组数组=2，映射对象=3，aliases 数组=4，其内只能是标量。 */
    public static final int MAX_DEPTH = 4;

    /** 违规时返回给调用方的错误码。 */
    public static final String ERROR_CODE = "vocabulary_too_large";

    private VocabularyBounds() {
    }

    /**
     * 校验词汇表是否在上限内。
     *
     * @return null=合规（含 null/空词汇）；否则为可读的违规说明
     */
    public static String validate(Map<String, Object> vocabulary) {
        if (vocabulary == null || vocabulary.isEmpty()) {
            return null;
        }
        int[] totalMappings = {0};
        return check(vocabulary, 1, totalMappings);
    }

    private static String check(Object node, int depth, int[] totalMappings) {
        if (node instanceof Map<?, ?> map) {
            return checkMap(map, depth, totalMappings);
        }
        if (node instanceof List<?> list) {
            return checkList(list, depth, totalMappings);
        }
        if (node instanceof String s && s.length() > MAX_STRING_LENGTH) {
            return "词汇表字符串长度超过上限（最多 " + MAX_STRING_LENGTH + " 字符）";
        }
        return null;
    }

    private static String checkMap(Map<?, ?> map, int depth, int[] totalMappings) {
        if (depth > MAX_DEPTH) {
            return "词汇表嵌套层数超过上限（最多 " + MAX_DEPTH + " 层）";
        }
        if (map.size() > MAX_OBJECT_KEYS) {
            return "词汇表对象键数超过上限（最多 " + MAX_OBJECT_KEYS + "）";
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String err = check(e.getKey(), depth + 1, totalMappings);
            if (err == null) {
                err = check(e.getValue(), depth + 1, totalMappings);
            }
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private static String checkList(List<?> list, int depth, int[] totalMappings) {
        if (depth > MAX_DEPTH) {
            return "词汇表嵌套层数超过上限（最多 " + MAX_DEPTH + " 层）";
        }
        // 第 2 层数组是映射分组，第 3 层及以下是映射内部数组（aliases 等）
        boolean isSection = depth == 2;
        int limit = isSection ? MAX_ENTRIES_PER_SECTION : MAX_NESTED_LIST_SIZE;
        if (list.size() > limit) {
            return isSection
                ? "词汇表单个分组条目数超过上限（最多 " + MAX_ENTRIES_PER_SECTION + "）"
                : "词汇表映射内数组元素数超过上限（最多 " + MAX_NESTED_LIST_SIZE + "）";
        }
        if (isSection) {
            totalMappings[0] += list.size();
            if (totalMappings[0] > MAX_TOTAL_MAPPINGS) {
                return "词汇表映射总数超过上限（最多 " + MAX_TOTAL_MAPPINGS + "）";
            }
        }
        for (Object item : list) {
            String err = check(item, depth + 1, totalMappings);
            if (err != null) {
                return err;
            }
        }
        return null;
    }
}
