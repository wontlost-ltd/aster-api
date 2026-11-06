package io.aster.policy.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 过渡期兼容映射：改码前的语法诊断（句型 key / 英文原句，没有错误码）→ 共享码表 E050–E069。
 *
 * <p>用途只有一个：读取尚未带码的旧诊断（例如升级前落库的验证记录）时补上码，让按码处理的消费方
 * 不必再按英文原句识别（ADR 0051 §16「兼容与迁移」）。新产出的诊断一律由 core 分类器给码，不经过这里。
 * 只保留一个车次（1.0.42），之后连同 syntax-diagnostics 目录里的旧句型 key 一起删除。
 */
public final class LegacySyntaxCodes {

    /** 旧句型 key → 码。外框句型（summary / located / moreErrors.* / withHint / or / more）不是诊断，不在其中。 */
    private static final Map<String, String> KEY_TO_CODE = keyToCode();

    /** 旧英文原句（en-US 目录模板转成的正则）→ 码；按声明顺序匹配，具体句型在前。 */
    private static final List<Map.Entry<Pattern, String>> ENGLISH_TO_CODE = englishToCode();

    private LegacySyntaxCodes() {
    }

    private static Map<String, String> keyToCode() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("unrecognizedCharacter", "E050");
        m.put("indentation", "E052");
        m.put("expected", "E055");
        m.put("expectedAfter", "E056");
        m.put("expectedIn", "E057");
        m.put("expectedAfterIn", "E058");
        m.put("expectedAtEndOfStatement", "E059");
        m.put("expectedBefore", "E060");
        m.put("unexpectedExpecting", "E062");
        m.put("extraneous", "E061");
        m.put("unexpected", "E061");
        m.put("isBeforeSymbol", "E063");
        m.put("unknownOperator", "E063");
        m.put("noViable", "E063");
        m.put("syntaxError", "E063");
        m.put("unknownStatement", "E064");
        m.put("unexpectedAtTopLevel", "E064");
        m.put("unexpectedExpression", "E064");
        return Map.copyOf(m);
    }

    private static List<Map.Entry<Pattern, String>> englishToCode() {
        // 顺序即优先级：带 "; expected" 的 E062 必须先于只有 "Unexpected {token}" 的 E061
        List<String> order = List.of("unexpectedExpecting", "expectedAtEndOfStatement", "expectedAfterIn",
            "expectedAfter", "expectedIn", "expectedBefore", "extraneous", "unexpectedAtTopLevel",
            "unexpectedExpression", "unknownStatement", "unexpected", "expected", "indentation",
            "isBeforeSymbol", "unknownOperator", "noViable", "unrecognizedCharacter", "syntaxError");
        SyntaxMessages english = SyntaxMessages.ENGLISH;
        return order.stream()
            .filter(english::has)
            .map(key -> Map.entry(toPattern(english.text(key, placeholders(key))), KEY_TO_CODE.get(key)))
            .toList();
    }

    /** 以占位符名自身填充模板，得到带 {@code {name}} 标记的原句骨架。 */
    private static String[] placeholders(String key) {
        return new String[] {"token", "{token}", "expected", "{token}", "before", "{token}",
            "after", "{token}", "context", "{token}"};
    }

    /** 骨架 → 正则：字面部分逐字匹配，{@code {name}} 匹配任意非空片段。 */
    private static Pattern toPattern(String skeleton) {
        StringBuilder regex = new StringBuilder("^");
        for (String part : skeleton.split("\\{token\\}", -1)) {
            if (regex.length() > 1) {
                regex.append(".+");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.DOTALL);
    }

    /** 旧句型 key 对应的码；外框或未知 key 返回 null。 */
    public static String codeForKey(String key) {
        return key == null ? null : KEY_TO_CODE.get(key);
    }

    /** 旧英文原句对应的码；不是改码前的语法句型（含 null）返回 null。 */
    public static String codeForMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        for (Map.Entry<Pattern, String> e : ENGLISH_TO_CODE) {
            if (e.getKey().matcher(message).matches()) {
                return e.getValue();
            }
        }
        return null;
    }
}
