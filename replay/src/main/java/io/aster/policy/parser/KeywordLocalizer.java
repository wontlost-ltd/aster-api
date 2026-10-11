package io.aster.policy.parser;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 把规范（英文）关键词拼写回译成作者所用 locale 的拼写，供语法错误展示。
 *
 * <p>为什么需要：{@link aster.core.canonicalizer.Canonicalizer} 在交给 ANTLR 之前
 * 把 zh/de 关键词归一成英文规范拼写，ANTLR 报错里的 token 因此是用户从未写过的
 * 'Return'。本类只做「规范拼写 → locale 主拼写」的查表，不触碰标识符与字面量——
 * 是否为关键词由调用方按 token 类型判定后再来查。
 */
final class KeywordLocalizer {

    /** 恒等实现：英文或无法确定 locale 时使用，错误信息保持原样。 */
    static final KeywordLocalizer IDENTITY = new KeywordLocalizer(Collections.emptyMap(), null);

    /** 小写规范拼写 → locale 主拼写。 */
    private final Map<String, String> canonicalToLocal;
    /** 作者所用 lexicon，用于取提示里的关键词；IDENTITY 时为 null（提示用规范拼写）。 */
    private final Lexicon target;

    private KeywordLocalizer(Map<String, String> canonicalToLocal, Lexicon target) {
        this.canonicalToLocal = canonicalToLocal;
        this.target = target;
    }

    /**
     * 以注册表默认 lexicon（规范英文拼写）为基准，建立到 {@code target} 的回译表。
     * target 与基准是同一份 lexicon 时返回 {@link #IDENTITY}。
     */
    static KeywordLocalizer forTarget(Lexicon target) {
        if (target == null) {
            return IDENTITY;
        }
        Lexicon canonical;
        try {
            canonical = LexiconRegistry.getInstance().getDefault();
        } catch (RuntimeException e) {
            return IDENTITY;   // 注册表不可用时宁可不回译，也不能让报错路径本身抛异常
        }
        if (canonical == null || canonical.getId().equalsIgnoreCase(target.getId())) {
            return IDENTITY;
        }
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<SemanticTokenKind, String> e : canonical.getKeywords().entrySet()) {
            String local = target.getKeywords().get(e.getKey());
            if (e.getValue() == null || local == null || local.isBlank()) {
                continue;
            }
            // 同一规范拼写对应多个语义（如 en 的 with/has 都是同词）时保留第一个，避免互相覆盖
            map.putIfAbsent(e.getValue().toLowerCase(Locale.ROOT), local);
        }
        return new KeywordLocalizer(Collections.unmodifiableMap(map), target);
    }

    /** 规范关键词 → locale 拼写；不是已知关键词时原样返回。 */
    String localize(String canonicalKeyword) {
        if (canonicalKeyword == null) {
            return null;
        }
        String local = canonicalToLocal.get(canonicalKeyword.toLowerCase(Locale.ROOT));
        return local != null ? local : canonicalKeyword;
    }

    /** 提示文案里用的关键词：优先 locale 拼写，缺失时退回规范英文。 */
    String keyword(SemanticTokenKind kind, String fallback) {
        if (target == null) {
            return fallback;
        }
        Optional<String> k = target.getKeyword(kind);
        return k.filter(s -> !s.isBlank()).orElse(fallback);
    }
}
