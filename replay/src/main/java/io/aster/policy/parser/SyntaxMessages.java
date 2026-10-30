package io.aster.policy.parser;

import aster.core.lexicon.ErrorMessages;
import aster.core.lexicon.Lexicon;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语法错误文案目录：按策略 lexicon 的语言取句子骨架与 token 名称。
 *
 * <p>文案放在 {@code syntax-diagnostics/<语言>.json}（en / zh / de / hi），占位符写作 {@code {name}}，
 * 由 {@link ErrorMessages#format} 填充（与 lexicon {@code messages} 模板同一约定）。
 * 句式与编辑器一致：例如句末缺标点在编辑器里是 {@code Expected '.' at end of statement} /
 * 「语句末尾应为 '。'」/ {@code Am Ende der Anweisung wird '.' erwartet}，这里同样措辞。
 *
 * <p>为什么不放进语言包 overlay：ANTLR 的解析错误只在 aster-api 产生（编辑器用 aster-lang-ts 自己的解析器），
 * 没有错误码，进不了按错误码组织的 diagnostic overlay。目录随 replay 模块发布，runner 与 aster-api 共用。
 *
 * <p>没有对应文件的语言（新 lexicon）退回英文；某条 key 缺失时同样退回英文，不会抛异常。
 */
final class SyntaxMessages {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, SyntaxMessages> CACHE = new ConcurrentHashMap<>();

    static final SyntaxMessages ENGLISH = forLanguage(DEFAULT_LANGUAGE);

    private final Map<String, String> templates;
    private final Map<String, String> fallback;

    private SyntaxMessages(Map<String, String> templates, Map<String, String> fallback) {
        this.templates = templates;
        this.fallback = fallback;
    }

    /** 按 lexicon id 的语言部分（{@code zh-CN} → {@code zh}）取目录；null 时为英文。 */
    static SyntaxMessages forLexicon(Lexicon lexicon) {
        if (lexicon == null || lexicon.getId() == null) {
            return ENGLISH;
        }
        return forLanguage(lexicon.getId().split("[-_]", 2)[0]);
    }

    /** 按语言代码取目录；没有该语言的文件时退回英文。 */
    static SyntaxMessages forLanguage(String language) {
        String lang = language == null ? DEFAULT_LANGUAGE : language.toLowerCase(Locale.ROOT);
        return CACHE.computeIfAbsent(lang, SyntaxMessages::load);
    }

    private static SyntaxMessages load(String lang) {
        Map<String, String> own = read(lang);
        if (DEFAULT_LANGUAGE.equals(lang)) {
            return new SyntaxMessages(own, own);
        }
        Map<String, String> english = read(DEFAULT_LANGUAGE);
        return new SyntaxMessages(own.isEmpty() ? english : own, english);
    }

    private static Map<String, String> read(String lang) {
        String path = "syntax-diagnostics/" + lang + ".json";
        try (InputStream in = SyntaxMessages.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            return Map.copyOf(MAPPER.readValue(in, new TypeReference<Map<String, String>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取语法错误文案 " + path, e);
        }
    }

    /** 取模板并填充占位符；{@code args} 为「名称, 值」交替排列。 */
    String text(String key, String... args) {
        String template = templates.getOrDefault(key, fallback.getOrDefault(key, key));
        String filled = ErrorMessages.format(template, args);
        return Boolean.parseBoolean(templates.get("style.spaceQuotesAfterHan")) ? spaceQuotes(filled) : filled;
    }

    /** 「另有 N 处后续错误」：N 为 1 时用单数句式（en / de / hi 的名词与动词随数变化）。 */
    String moreErrors(int count, String message) {
        return text(count == 1 ? "moreErrors.one" : "moreErrors.other",
            "count", String.valueOf(count), "message", message);
    }

    /** 单引号片段整体，用于在汉字与引号相邻处补空格。 */
    private static final Pattern QUOTED = Pattern.compile("'[^']*'");

    /**
     * 汉字与引号片段相邻处补一个空格（{@code 应为 '。'}、{@code '返回' 之前}），汉字之间不加空格
     * （{@code 此处应为换行}）。与编辑器 parse-diagnostic-l10n.ts 的 spaceQuotes 同一排版；重复调用结果不变。
     */
    static String spaceQuotes(String s) {
        Matcher m = QUOTED.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            if (m.start() > 0 && isHan(s.codePointBefore(m.start()))) {
                out.append(' ');
            }
            out.append(m.group());
            if (m.end() < s.length() && isHan(s.codePointAt(m.end()))) {
                out.append(' ');
            }
            last = m.end();
        }
        return out.append(s.substring(last)).toString();
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** token 的显示名（缩进、换行、标识符等）。 */
    String token(String name) {
        return text("token." + name);
    }

    /** 本目录定义的全部 key（供测试核对各语言齐全）。 */
    Set<String> keys() {
        return new TreeSet<>(templates.keySet());
    }
}
