package io.aster.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 《静夜思》原诗即策略的共享输入（ADR 0060 §8）：源码、版本冻结的 aliasSet、cloud 合并后的词汇与跨引擎期望哈希。
 *
 * <p>与 aster-lang-core {@code JingyesiPolicyTest}、aster-lang-ts {@code jingyesi-policy.test.ts} 逐字相同；
 * 改动任何一项都必须同步三处，否则 {@link #POSITION_FREE_SHA256} 不再是两引擎的共同值。
 */
public final class JingyesiPolicy {

    private JingyesiPolicy() {
    }

    /** aster-lang-ts JINGYESI_POSITION_FREE_SHA256 与 aster-lang-core JingyesiPolicyTest 的同一期望值。 */
    public static final String POSITION_FREE_SHA256 =
        "55512170231ae56d7f01b8130ce368d650c5b390ad6f0ac7f34d5350c8960ec6";

    public static final String LOCALE = "zh-CN";

    /** 入口规则：无参数，返回 Text。 */
    public static final String ENTRY = "地上霜";

    /** 规则输出：literal 思故乡 展开后的字符串。 */
    public static final String OUTPUT = "静夜思";

    /** 规范源码（别名后带空格，由 LayoutMap 隐去）。 */
    public static final String SOURCE = "床前 明月光。\n疑是 地上霜，举头 望明月：\n  低头 思故乡。\n";

    /** 版本冻结的 aliasSet，与 cloud 下发同形（kind 名 → 别名数组）；四个都是结构词，需授权。 */
    public static final Map<String, List<String>> ALIAS_SET = Map.of(
        "FUNC_PRODUCE", List.of("举头"),
        "FUNC_TO", List.of("疑是"),
        "MODULE_DECL", List.of("床前"),
        "RETURN", List.of("低头"));

    /**
     * cloud {@code JINGYESI_LAYOUT} 的 {@code serializeLayout} 文本（ADR 0059 修订 4 导出随包的 {@code policy/display/layout.json}）：
     * 四个隐去别名后空格的结构 span 与四个换标点的结构 span。与 {@code src/test/resources/export/jingyesi-layout.json}、
     * conformance 黄金包 {@code display/packages/b-jingyesi} 的同名文件逐字节相同。
     */
    public static final String LAYOUT_JSON = "{\"v\":1,\"spans\":["
        + "{\"text\":\"床前\"},{\"canonical\":\" \",\"display\":\"\"},{\"text\":\"明月光\"},"
        + "{\"canonical\":\"。\\n\",\"display\":\"，\\n\"},"
        + "{\"text\":\"疑是\"},{\"canonical\":\" \",\"display\":\"\"},{\"text\":\"地上霜\"},"
        + "{\"canonical\":\"，\",\"display\":\"。\\n\"},"
        + "{\"text\":\"举头\"},{\"canonical\":\" \",\"display\":\"\"},{\"text\":\"望明月\"},"
        + "{\"canonical\":\"：\\n  \",\"display\":\"，\\n\"},"
        + "{\"text\":\"低头\"},{\"canonical\":\" \",\"display\":\"\"},{\"text\":\"思故乡\"},"
        + "{\"canonical\":\"。\\n\",\"display\":\"。\"}]}";

    /** {@code toDisplay(LAYOUT)}：原诗四句，末句不带换行。 */
    public static final String DISPLAY = "床前明月光，\n疑是地上霜。\n举头望明月，\n低头思故乡。";

    /** cloud 合并后发给 aster-api 的 DomainVocabulary：只含两条内嵌词条。每次返回新的可变副本。 */
    public static Map<String, Object> vocabulary() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", "policy.embedded");
        v.put("name", "policy");
        v.put("locale", LOCALE);
        v.put("version", "1");
        v.put("structs", List.of(Map.of("localized", "望明月", "canonical", "Text", "kind", "struct")));
        v.put("fields", List.of());
        v.put("functions", List.of());
        v.put("enumValues", List.of());
        v.put("literals", List.of(Map.of("localized", "思故乡", "canonical", OUTPUT, "kind", "literal")));
        return v;
    }
}
