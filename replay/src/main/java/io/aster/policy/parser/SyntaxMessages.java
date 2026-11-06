package io.aster.policy.parser;

import aster.core.lexicon.ErrorMessages;
import aster.core.lexicon.Lexicon;
import aster.core.typecheck.ErrorCode;
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
 * <p>唯一真相源是 aster-lang-locales 的 {@code syntax-diagnostics/<locale-id>.json}（en-US / zh-CN / de-DE / hi-IN，
 * 随 {@code aster-lang-locales-en} jar 上 classpath；编辑器经 {@code @aster-cloud/ui-messages} 读同一份，aster-api#423）。
 * 占位符写作 {@code {name}}，
 * 由 {@link ErrorMessages#format} 填充（与 lexicon {@code messages} 模板同一约定）。
 * 句式与编辑器一致：例如句末缺标点在编辑器里是 {@code Expected '.' at end of statement} /
 * 「语句末尾应为 '。'」/ {@code Am Ende der Anweisung wird '.' erwartet}，这里同样措辞。
 *
 * <p>诊断句子按错误码取（ADR 0051 §16 R56）：E050–E069 的模板来自语言包 overlay
 * {@code overlays/<locale-id>/diagnostic-messages.json}（zh/de 随 locales jar，hi 随 aster-lang-hi jar），
 * 缺译回退 core 码表的英文模板（{@link ErrorCode#messageTemplate()}）。本目录只提供外框句型
 * （summary / located / moreErrors / withHint / or / more）、提示（isBeforeSymbol / unknownOperator /
 * inlineIfHint）与 {@code token.*} 名词。
 *
 * <p>没有对应文件的语言（新 lexicon）退回英文；某条 key 缺失时同样退回英文，不会抛异常。
 */
final class SyntaxMessages {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final String CATALOG_DIR = "syntax-diagnostics/";
    private static final String OVERLAY_TEMPLATE = "overlays/%s/diagnostic-messages.json";
    /** 语言代码 → aster-lang-locales 目录的文件名。 */
    private static final Map<String, String> LOCALE_IDS =
        Map.of("en", "en-US", "zh", "zh-CN", "de", "de-DE", "hi", "hi-IN");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, SyntaxMessages> CACHE = new ConcurrentHashMap<>();

    static final SyntaxMessages ENGLISH = forLanguage(DEFAULT_LANGUAGE);

    private final Map<String, String> templates;
    private final Map<String, String> fallback;
    /** 错误码 → 该语言的诊断模板（overlay 的 messages 段）；英文为空，直接用码表模板。 */
    private final Map<String, String> codeTemplates;

    private SyntaxMessages(Map<String, String> templates, Map<String, String> fallback,
            Map<String, String> codeTemplates) {
        this.templates = templates;
        this.fallback = fallback;
        this.codeTemplates = codeTemplates;
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
        ClassLoader loader = SyntaxMessages.class.getClassLoader();
        Map<String, String> own = read(loader, lang);
        if (DEFAULT_LANGUAGE.equals(lang)) {
            return new SyntaxMessages(own, own, Map.of());
        }
        Map<String, String> english = read(loader, DEFAULT_LANGUAGE);
        return new SyntaxMessages(own.isEmpty() ? english : own, english, readOverlay(loader, lang));
    }

    /** 读语言包 overlay 的诊断模板（messages 段）；语言未登记或资源不存在时返回空 Map（按码回退英文）。 */
    static Map<String, String> readOverlay(ClassLoader loader, String lang) {
        String localeId = LOCALE_IDS.get(lang);
        if (localeId == null) {
            return Map.of();
        }
        String path = String.format(OVERLAY_TEMPLATE, localeId);
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            Map<String, Object> root = MAPPER.readValue(in, new TypeReference<Map<String, Object>>() {});
            Map<String, String> out = new java.util.HashMap<>();
            if (root.get("messages") instanceof Map<?, ?> messages) {
                messages.forEach((k, v) -> {
                    if (k instanceof String key && v instanceof String value) {
                        out.put(key, value);
                    }
                });
            }
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取诊断 overlay " + path, e);
        }
    }

    /** 读 locales 目录里该语言的文件；语言未登记或文件不存在时返回空 Map（调用方退回英文）。 */
    static Map<String, String> read(ClassLoader loader, String lang) {
        String localeId = LOCALE_IDS.get(lang);
        Map<String, String> catalog = localeId == null ? null : readResource(loader, CATALOG_DIR + localeId + ".json");
        return catalog == null ? Map.of() : catalog;
    }

    /** 读一个文案文件；资源不存在时返回 null。 */
    static Map<String, String> readResource(ClassLoader loader, String path) {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                return null;
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

    /** 按错误码取诊断模板并填充命名参数：overlay 译文优先，缺译回退码表英文模板。 */
    String diagnostic(ErrorCode code, String... args) {
        String template = codeTemplates.getOrDefault(code.code(), code.messageTemplate());
        String filled = ErrorMessages.format(template, args);
        if (!Boolean.parseBoolean(templates.get("style.spaceQuotesAfterHan"))) {
            return filled;
        }
        // overlay 模板在占位符两侧留了空格（为引号片段准备）；参数是汉字名词（「文件结尾」）时去掉汉字之间的空格
        return spaceQuotes(HAN_GAP.matcher(filled).replaceAll(""));
    }

    /** 两个汉字之间的空白。 */
    private static final Pattern HAN_GAP = Pattern.compile("(?<=\\p{IsHan}) +(?=\\p{IsHan})");

    /** 本语言或英文目录是否定义了 {@code key}。 */
    boolean has(String key) {
        return templates.containsKey(key) || fallback.containsKey(key);
    }

    /** 驼峰词：在小写字母 / 数字与其后的大写字母之间断开。 */
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /**
     * 诊断参数 {@code token.<name>} 的显示名：目录有 {@code token.<name>} 时取译文；目录尚未收录的
     * （引擎新增的构造名）退回把驼峰名拆成小写英文词，绝不把内部 key 原样展示给作者。
     */
    String tokenName(String name) {
        if (has("token." + name)) {
            return token(name);
        }
        return CAMEL.matcher(name).replaceAll(" ").toLowerCase(Locale.ROOT);
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
