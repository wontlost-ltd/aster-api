package io.aster.policy.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.evidence.AgentIdentity;
import io.aster.policy.module.ModuleResolutionException;
import io.aster.policy.rest.model.EvaluationResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * evaluate 系列 400 诊断（入口歧义、非法 agent）的多语言文案（aster-api#445）。
 *
 * <p>code（{@code ENTRY_AMBIGUOUS} / {@code invalid_agent}）是稳定契约，调用方按 code 分支；
 * message 只供展示，按请求语言取 {@code evaluation-diagnostics/<locale-id>.json}。
 * 请求语言优先取 evaluate-source 请求体的 {@code locale}（策略 CNL 语言），其次 {@code Accept-Language}，
 * 都不认识时为英文；某条 key 缺失时同样退回英文。
 *
 * <p>唯一真相源是 aster-lang-locales 的 {@code evaluation-diagnostics/<locale-id>.json}（en-US / zh-CN / de-DE / hi-IN，
 * 随 {@code aster-lang-locales-en} jar 上 classpath，aster-lang-locales#121），本仓不留副本——同路径副本会遮蔽 jar。
 * 读取方式与 {@code SyntaxMessages} 一致：文件缺失时得到空目录，调用方退回英文，不抛异常。
 *
 * <p>模块解析错误（MODULE_* 系列）同一 code 有多种原因：按 {@link ModuleResolutionException.Reason} 取
 * {@code module.<原因名>}，填命名参数；目录缺 key 时退回异常的英文原文（aster-api#452）。
 */
final class EvaluationDiagnostics {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final String CATALOG_DIR = "evaluation-diagnostics/";
    /** 语言代码 → 目录文件名（与 syntax-diagnostics 同一套 locale id）。 */
    private static final Map<String, String> LOCALE_IDS =
        Map.of("en", "en-US", "zh", "zh-CN", "de", "de-DE", "hi", "hi-IN");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private EvaluationDiagnostics() {
    }

    /**
     * 解析请求语言：依次尝试请求体 locale 与 Accept-Language 各项（按出现顺序），取第一个有文案目录的语言。
     */
    static String language(String bodyLocale, String acceptLanguage) {
        java.util.ArrayList<String> tags = new java.util.ArrayList<>();
        tags.add(bodyLocale);
        if (acceptLanguage != null) {
            for (String part : acceptLanguage.split(",")) {
                tags.add(part.split(";", 2)[0]);
            }
        }
        return tags.stream()
            .map(EvaluationDiagnostics::primarySubtag)
            .filter(LOCALE_IDS::containsKey)
            .findFirst()
            .orElse(DEFAULT_LANGUAGE);
    }

    private static String primarySubtag(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        return tag.trim().split("[-_]", 2)[0].toLowerCase(Locale.ROOT);
    }

    static EvaluationResponse ambiguous(List<String> candidates, String language) {
        return EvaluationResponse.ambiguous(candidates,
            text(language, "entryAmbiguous.error"), text(language, "entryAmbiguous.detail"));
    }

    /** 非 {@link AgentIdentity.InvalidAgentException} 的校验异常没有结构化原因，原样透出其 message。 */
    static EvaluationResponse invalidAgent(IllegalArgumentException e, String language) {
        String message = e instanceof AgentIdentity.InvalidAgentException invalid
            ? text(language, "agent." + invalid.reason().name())
                .replace("{field}", invalid.field())
                .replace("{max}", String.valueOf(AgentIdentity.MAX_TEXT))
            : e.getMessage();
        return EvaluationResponse.diagnostic("invalid_agent", message, List.of());
    }

    /** MODULE_NOT_VISIBLE 目录缺 key 时的通用英文文案（不得退回异常原文：原文按细分原因区分）。 */
    static final String NOT_VISIBLE_FALLBACK = "Imported module is not visible";

    /**
     * MODULE_* 400：code、candidates 不变，附 reason，message 按请求语言。
     *
     * <p>ADR 0015（不泄露模块存在性）：code=MODULE_NOT_VISIBLE 的五种原因（无租户、不可见、带别名、
     * 解析失败、解析器不可用）对外一律报 {@code reason=NOT_VISIBLE} 与通用文案——否则调用方能从
     * 「解析失败 / 带别名」反推出一个对自己不可见的模块确实存在。细分原因只进服务端日志
     * （{@link #moduleLogFields}）。其它 code 的原因不涉及可见性，照常对外区分。
     */
    static EvaluationResponse module(ModuleResolutionException e, String language) {
        return module(e, language, (lang, key) -> catalog(lang).get(key));
    }

    /**
     * @param lookup (语言, key) → 文案，缺失返回 null；测试注入空目录以验证回退
     */
    static EvaluationResponse module(ModuleResolutionException e, String language,
                                     java.util.function.BiFunction<String, String, String> lookup) {
        if (e.code() == ModuleResolutionException.Code.MODULE_NOT_VISIBLE) {
            // 对外不带候选、不带细分原因：五种可见性原因与 NOT_FOUND 的响应体逐字段相同
            String key = "module." + ModuleResolutionException.Reason.NOT_VISIBLE.name();
            String own = lookup.apply(language, key);
            if (own == null) {
                own = lookup.apply(DEFAULT_LANGUAGE, key);
            }
            return EvaluationResponse.diagnostic(e.code().name(), ModuleResolutionException.Reason.NOT_VISIBLE.name(),
                own != null ? own : NOT_VISIBLE_FALLBACK, List.of());
        }
        return EvaluationResponse.diagnostic(e.code().name(), e.reason().name(),
            moduleText(e, language, "module." + e.reason().name()), e.candidates());
    }

    /** 服务端日志的结构化字段：含细分原因，不含模块名 / 版本 / 原文（MODULE_NOT_VISIBLE 原文虽不带模块名，也一并不记）。 */
    static String moduleLogFields(ModuleResolutionException e) {
        return "code=" + e.code().name() + " reason=" + e.reason().name();
    }

    /** 取 {@code key} 的文案并填异常参数；本语言与英文目录都没有该 key 时用异常的英文原文。 */
    static String moduleText(ModuleResolutionException e, String language, String key) {
        String template = catalog(language).getOrDefault(key, catalog(DEFAULT_LANGUAGE).get(key));
        if (template == null) {
            return e.getMessage();
        }
        String out = template;
        for (Map.Entry<String, String> p : e.params().entrySet()) {
            out = out.replace("{" + p.getKey() + "}", p.getValue());
        }
        return out;
    }

    static String text(String language, String key) {
        String own = catalog(language).get(key);
        return own != null ? own : catalog(DEFAULT_LANGUAGE).getOrDefault(key, key);
    }

    /** 该语言目录定义的全部 key（供测试核对各语言齐全）。 */
    static Set<String> keys(String language) {
        return new TreeSet<>(catalog(language).keySet());
    }

    private static Map<String, String> catalog(String language) {
        String lang = LOCALE_IDS.containsKey(language) ? language : DEFAULT_LANGUAGE;
        return CACHE.computeIfAbsent(lang, l -> read(EvaluationDiagnostics.class.getClassLoader(), l));
    }

    /** 读 locales 目录里该语言的文件；语言未登记或文件不存在时返回空 Map（调用方退回英文）。 */
    static Map<String, String> read(ClassLoader loader, String lang) {
        String localeId = LOCALE_IDS.get(lang);
        if (localeId == null) {
            return Map.of();
        }
        String path = CATALOG_DIR + localeId + ".json";
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            return Map.copyOf(MAPPER.readValue(in, new TypeReference<Map<String, String>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取评估诊断文案 " + path, e);
        }
    }
}
