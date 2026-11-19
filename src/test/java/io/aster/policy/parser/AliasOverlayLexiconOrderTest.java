package io.aster.policy.parser;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.embed.AliasOverlayLexicon;
import aster.core.embed.UserAliasValidator;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AliasOverlayLexicon#getAliases()} 的遍历顺序必须与 JVM 进程无关（wontlost-ltd/aster-api#564）。
 *
 * <p>历史 bug：合并后的别名表交给 {@code Map.copyOf}，其遍历顺序随 JVM 启动时的随机 SALT 变化。Canonicalizer 按
 * {@code getAliases()} 的遍历顺序用 {@code putIfAbsent} 登记别名（先到者胜），两个 kind 的别名同形时，同一份带
 * aliasSet 的源会在不同 pod 或重启前后规范化成不同的关键词。这里与 {@link SemanticTokenKind} 声明顺序比较，而不是
 * 在同一进程里包装两次（同一进程内 SALT 不变，测不出问题）。
 */
class AliasOverlayLexiconOrderTest {

    @Test
    void aliasesIterateInDeclarationOrder() {
        // 选取分散在枚举各处的多个 kind，Map.copyOf 下遍历顺序恰好等于声明顺序的概率可忽略
        Map<SemanticTokenKind, List<String>> aliasSet = new EnumMap<>(SemanticTokenKind.class);
        for (SemanticTokenKind kind : List.of(
                SemanticTokenKind.CPU, SemanticTokenKind.TIMES, SemanticTokenKind.PLUS, SemanticTokenKind.MODULO,
                SemanticTokenKind.LESS_THAN, SemanticTokenKind.GREATER_THAN, SemanticTokenKind.TRUE,
                SemanticTokenKind.FALSE, SemanticTokenKind.TEXT, SemanticTokenKind.INT_TYPE,
                SemanticTokenKind.MAYBE, SemanticTokenKind.NONE, SemanticTokenKind.LET, SemanticTokenKind.BE,
                SemanticTokenKind.OR, SemanticTokenKind.AND, SemanticTokenKind.NOT, SemanticTokenKind.TYPE_DEF)) {
            aliasSet.put(kind, List.of("alias-" + kind.name().toLowerCase()));
        }

        Lexicon wrapped = AliasOverlayLexicon.wrap(LexiconRegistry.getInstance().getOrThrow("en-US"), aliasSet);

        List<SemanticTokenKind> actual = new ArrayList<>(wrapped.getAliases().keySet());
        List<SemanticTokenKind> expected = Arrays.stream(SemanticTokenKind.values())
            .filter(wrapped.getAliases()::containsKey)
            .toList();
        assertTrue(actual.size() >= aliasSet.size(), "前提：合并后至少包含全部用户别名 kind");
        assertEquals(expected, actual, "别名表的遍历顺序应为 SemanticTokenKind 声明顺序（与 JVM 进程无关）");
    }

    @Test
    void sharedAliasResolvesToEarlierDeclaredKind() {
        // 遍历顺序影响结果：同形别名在 Canonicalizer 里先到者胜。wrap 本身不做冲突校验（UserAliasValidator 在前置
        // 拦截大部分此类冲突），这里直接构造冲突，确认结果固定为声明顺序在前的 kind（LET 先于 SET）。
        Lexicon base = LexiconRegistry.getInstance().getOrThrow("en-US");
        Map<SemanticTokenKind, List<String>> aliasSet = new EnumMap<>(SemanticTokenKind.class);
        aliasSet.put(SemanticTokenKind.SET, List.of("Bind"));
        aliasSet.put(SemanticTokenKind.LET, List.of("Bind"));

        String canonical = new Canonicalizer(AliasOverlayLexicon.wrap(base, aliasSet)).canonicalize("Bind x be 1.");

        String let = base.getKeywords().get(SemanticTokenKind.LET);
        assertTrue(canonical.startsWith(let + " "), "同形别名应归一成声明顺序在前的 LET（" + let + "）：" + canonical);
    }
}
