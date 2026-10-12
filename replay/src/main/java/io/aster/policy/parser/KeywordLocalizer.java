package io.aster.policy.parser;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.parser.AsterCustomLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        for (Map.Entry<SemanticTokenKind, String> e : inKindOrder(canonical)) {
            String local = target.getKeywords().get(e.getKey());
            if (e.getValue() == null || local == null || local.isBlank()) {
                continue;
            }
            // 防御：若两个语义共用同一规范拼写，保留先出现的那个，避免互相覆盖
            map.putIfAbsent(e.getValue().toLowerCase(Locale.ROOT), local);
        }
        return new KeywordLocalizer(Collections.unmodifiableMap(map), target);
    }

    /**
     * 按 {@link SemanticTokenKind} 声明顺序遍历关键词。
     *
     * <p>lexicon 的 getKeywords() 不保证迭代顺序，且不同 JVM 进程间会变化；下游用
     * putIfAbsent 去重时，「谁先出现谁胜出」就随进程漂移（实测 GREATER_THAN_WORD 时而
     * 回译成「多于」时而「大于」）。固定按枚举声明顺序，先声明的语义胜出，结果可复现。
     */
    static List<Map.Entry<SemanticTokenKind, String>> inKindOrder(Lexicon lexicon) {
        List<Map.Entry<SemanticTokenKind, String>> entries = new ArrayList<>(lexicon.getKeywords().entrySet());
        entries.sort(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(Enum::ordinal)));
        return entries;
    }

    /** 是否会改写（非英文 locale）。英文为恒等，调用方据此保持英文文案逐字不变。 */
    boolean isLocalizing() {
        return !canonicalToLocal.isEmpty();
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

    /**
     * 关键词 token 类型表：把规范 lexicon 里的每个关键词拼写单独过一遍 AsterCustomLexer，
     * 记下它被识别成的 token 类型。
     *
     * <p>为什么不用「符号名 == 文本大写」之类的命名约定：AsterLexer 里既有大小写不敏感、
     * 无字面量名的规则（{@code RETURN: [Rr][Ee]...}），也有带字面量名的规则
     * （{@code GIVEN: 'given'}、{@code TO_WORD: 'to'}、{@code MODULE_KW: 'Module'}、
     * {@code PLUS_WORD} 等），还有符号名与拼写不同的规则（{@code ELSE} 对应 Otherwise、
     * {@code SECONDS}）。命名约定会漏掉后几类。以词法器自身的判定为准则不会漏。
     *
     * <p>被识别成 IDENT / TYPE_IDENT / 字面量的拼写不算关键词（例如内置类型名 Int 是
     * TYPE_IDENT），这样与关键词同形的用户名字（类型 Given、Profile）永远不会被回译。
     */
    static final class KeywordTokens {
        /** 关键词 token 类型集合。 */
        final Set<Integer> types;
        /** token 符号名 → 规范拼写（如 ELSE → Otherwise），用于回译 expecting 集合里的裸符号名。 */
        final Map<String, String> symbolicToCanonical;

        private KeywordTokens(Set<Integer> types, Map<String, String> symbolicToCanonical) {
            this.types = types;
            this.symbolicToCanonical = symbolicToCanonical;
        }

        private static final class Holder {
            static final KeywordTokens INSTANCE = build();
        }

        static KeywordTokens get() {
            return Holder.INSTANCE;
        }

        private static KeywordTokens build() {
            Set<Integer> types = new HashSet<>();
            Map<String, String> symbolic = new HashMap<>();
            Lexicon canonical;
            try {
                canonical = LexiconRegistry.getInstance().getDefault();
            } catch (RuntimeException e) {
                return new KeywordTokens(Set.of(), Map.of());
            }
            if (canonical == null) {
                return new KeywordTokens(Set.of(), Map.of());
            }
            for (Map.Entry<SemanticTokenKind, String> entry : inKindOrder(canonical)) {
                String spelling = entry.getValue();
                if (spelling == null || spelling.isBlank()) {
                    continue;
                }
                Token only = singleToken(spelling);
                if (only == null || !only.getText().equalsIgnoreCase(spelling)) {
                    continue;   // 多词拼写（会被拆成多个 token）或被规范化成符号的，不在 token 层面判定
                }
                Vocabulary vocab = AsterCustomLexer.VOCABULARY;
                String name = vocab.getSymbolicName(only.getType());
                if (name == null || "IDENT".equals(name) || "TYPE_IDENT".equals(name)
                        || name.endsWith("LITERAL")) {
                    continue;
                }
                types.add(only.getType());
                symbolic.putIfAbsent(name, spelling);
            }
            return new KeywordTokens(Set.copyOf(types), Map.copyOf(symbolic));
        }

        /** 词法分析一段文本；只产出恰好一个有意义 token 时返回它。 */
        private static Token singleToken(String text) {
            try {
                AsterCustomLexer lexer = new AsterCustomLexer(CharStreams.fromString(text));
                lexer.removeErrorListeners();
                List<Token> significant = new ArrayList<>();
                for (Token t = lexer.nextToken(); t.getType() != Token.EOF; t = lexer.nextToken()) {
                    if (t.getChannel() != Token.DEFAULT_CHANNEL) {
                        continue;
                    }
                    String name = AsterCustomLexer.VOCABULARY.getSymbolicName(t.getType());
                    if ("NEWLINE".equals(name) || "INDENT".equals(name) || "DEDENT".equals(name)) {
                        continue;
                    }
                    significant.add(t);
                }
                return significant.size() == 1 ? significant.get(0) : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
