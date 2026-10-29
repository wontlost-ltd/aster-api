package io.aster.policy.parser;

import aster.core.identifier.DomainVocabulary;
import aster.core.identifier.VocabularyScopes;
import aster.core.identifier.IdentifierMapping;
import aster.core.lexicon.SemanticTokenKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 D 用户别名校验测试（ADR 0022 §11.5，堵 Codex High-3 社会工程攻击面）。
 */
class UserAliasValidatorTest {

    @Test
    void allowsLowRiskMultiWordOperatorAlias() {
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by")), "en-US");
        assertTrue(r.valid(), () -> r.errors().toString());
    }

    @Test
    void rejectsSensitiveKindAlias_socialEngineering() {
        // High-3：用户把别名挂到 RETURN（审批者看别名、实际批准的是 RETURN 语义）
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.RETURN, List.of("approve as")), "en-US");
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("不允许为")));
    }

    @Test
    void rejectsControlFlowAndImportAndEffectKinds() {
        for (SemanticTokenKind sensitive : List.of(
                SemanticTokenKind.IF, SemanticTokenKind.OTHERWISE, SemanticTokenKind.MATCH,
                SemanticTokenKind.IMPORT, SemanticTokenKind.IO, SemanticTokenKind.AND,
                SemanticTokenKind.FUNC_TO, SemanticTokenKind.WORKFLOW)) {
            var r = UserAliasValidator.validate(
                Map.of(sensitive, List.of("some phrase")), "en-US");
            assertFalse(r.valid(), "应拒绝敏感 kind: " + sensitive);
        }
    }

    @Test
    void rejectsSingleWordAlias_userspaceCollision() {
        // 单词别名占标识符命名空间
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("scaled")), "en-US");
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("多词")));
    }

    @Test
    void rejectsAliasShadowingCanonical() {
        // 别名与某规范关键词同形（"plus" 是 PLUS 的规范拼写）
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("plus times")), "en-US");
        // "plus times" 是多词、不撞单词规范，应通过；改测真正撞规范的多词需要规范本身是多词。
        // 这里验证不遮蔽逻辑对多词规范（如 "divided by"）生效：
        var r2 = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("divided by")), "en-US");
        assertFalse(r2.valid());
        assertTrue(r2.errors().stream().anyMatch(e -> e.contains("遮蔽")));
    }

    @Test
    void nullAndEmptyAreValid() {
        assertTrue(UserAliasValidator.validate(null, "en-US").valid());
        assertTrue(UserAliasValidator.validate(Map.of(), "en-US").valid());
    }

    @Test
    void rejectsNonCanonicalWhitespaceAlias() {
        // "scaled  by"（双空格）非规范形 → 拒绝。否则 Canonicalizer 折叠源码空白成单空格，
        // 而 overlay 注入 raw 双空格做 translation key 匹配不到（Codex 二轮挖出的 bug）。
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("scaled  by")), "en-US");
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("非规范空白")));
    }

    @Test
    void rejectsUppercaseAlias_requiresCanonicalForm() {
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("Scaled By")), "en-US");
        assertFalse(r.valid());
    }

    @Test
    void rejectsAliasCollidingWithDomainVocabulary() {
        // High-3：别名撞领域词汇标识符 → 关键词翻译先于标识符翻译会抢赢用户字段名 → 拒绝
        var idx = vocabIndexWith("monthlyFee", "monthly fee");
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("monthly fee")), "en-US", idx);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("领域词汇")));
    }

    @Test
    void allowsAliasNotCollidingWithVocabulary() {
        var idx = vocabIndexWith("monthlyFee", "monthly fee");
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by")), "en-US", idx);
        assertTrue(r.valid(), () -> r.errors().toString());
    }

    @Test
    void gate_parseWithUserAliasesRejectsSensitiveKind() {
        // Critical-1：受控入口必须在编译前强制校验，敏感 kind 别名直接抛异常
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 2.";
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
            InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parseWithUserAliases(
                src, "en-US", null, Map.of(SemanticTokenKind.RETURN, List.of("approve as"))));
        assertTrue(ex.getMessage().contains("用户自定义别名校验失败"));
    }

    @Test
    void gate_parseWithUserAliasesAcceptsValidAlias() {
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x multiplied by 2.";
        var pr = InProcessCnlParser.parseWithUserAliases(
            src, "en-US", null, Map.of(SemanticTokenKind.TIMES, List.of("multiplied by")));
        org.junit.jupiter.api.Assertions.assertNotNull(pr.module());
    }

    // ─── AliasOverlayLexicon 合并/不可变（Critical-2 直接覆盖）───────────────

    @Test
    void overlayMergesBaseAndUserAliases() {
        // base 带别名 + user 别名 → 合并（base 不丢失）
        var base = new aster.core.lexicon.Lexicon() {
            private final aster.core.lexicon.Lexicon delegate =
                aster.core.lexicon.LexiconRegistry.getInstance().getDefault();
            public String getId() { return delegate.getId(); }
            public String getName() { return delegate.getName(); }
            public Direction getDirection() { return delegate.getDirection(); }
            public Map<SemanticTokenKind, String> getKeywords() { return delegate.getKeywords(); }
            public Map<SemanticTokenKind, List<String>> getAliases() {
                return Map.of(SemanticTokenKind.PLUS, List.of("added together"));
            }
            public aster.core.lexicon.PunctuationConfig getPunctuation() { return delegate.getPunctuation(); }
            public aster.core.lexicon.CanonicalizationConfig getCanonicalization() { return delegate.getCanonicalization(); }
            public aster.core.lexicon.ErrorMessages getMessages() { return delegate.getMessages(); }
        };
        var wrapped = AliasOverlayLexicon.wrap(base,
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by")));
        // base 别名保留 + user 别名加入
        assertTrue(wrapped.getAliases().get(SemanticTokenKind.PLUS).contains("added together"));
        assertTrue(wrapped.getAliases().get(SemanticTokenKind.TIMES).contains("multiplied by"));
    }

    @Test
    void overlayIsDeeplyImmutable() {
        var user = new java.util.HashMap<SemanticTokenKind, List<String>>();
        user.put(SemanticTokenKind.TIMES, new java.util.ArrayList<>(List.of("multiplied by")));
        var wrapped = AliasOverlayLexicon.wrap(
            aster.core.lexicon.LexiconRegistry.getInstance().getDefault(), user);
        // 改原始入参不影响 wrapped
        user.get(SemanticTokenKind.TIMES).add("scaled by");
        user.put(SemanticTokenKind.PLUS, List.of("x"));
        assertTrue(wrapped.getAliases().get(SemanticTokenKind.TIMES).contains("multiplied by"));
        org.junit.jupiter.api.Assertions.assertEquals(
            1, wrapped.getAliases().get(SemanticTokenKind.TIMES).size());
        org.junit.jupiter.api.Assertions.assertFalse(
            wrapped.getAliases().containsKey(SemanticTokenKind.PLUS));
    }

    @Test
    void canonicalJsonIsDeterministicRegardlessOfInputOrder() {
        // 不同输入顺序/未归一 → 同一规范 JSON（同 envelope，可复现）
        var a = new java.util.LinkedHashMap<SemanticTokenKind, List<String>>();
        a.put(SemanticTokenKind.TIMES, List.of("multiplied by"));
        a.put(SemanticTokenKind.PLUS, List.of("added to"));
        var b = new java.util.LinkedHashMap<SemanticTokenKind, List<String>>();
        b.put(SemanticTokenKind.PLUS, List.of("added to"));
        b.put(SemanticTokenKind.TIMES, List.of("multiplied by"));
        assertEquals(UserAliasValidator.canonicalJson(a), UserAliasValidator.canonicalJson(b));
        // 键有序：PLUS 在 TIMES 前
        assertTrue(UserAliasValidator.canonicalJson(a).indexOf("PLUS")
            < UserAliasValidator.canonicalJson(a).indexOf("TIMES"));
    }

    @Test
    void canonicalJsonNullForEmpty() {
        org.junit.jupiter.api.Assertions.assertNull(UserAliasValidator.canonicalJson(null));
        org.junit.jupiter.api.Assertions.assertNull(UserAliasValidator.canonicalJson(Map.of()));
    }

    // ── 结构词别名门控（ADR 0022 扩展；与 cloud policy-alias-shared 对齐） ──────────

    @Test
    void structuralKindsRejectedByDefault_allowStructuralFalse() {
        // MODULE_DECL/FUNC_TO/IF/OTHERWISE/MATCH/WHEN/RETURN 默认（未授权）拒绝。
        for (SemanticTokenKind k : List.of(
                SemanticTokenKind.MODULE_DECL, SemanticTokenKind.FUNC_TO, SemanticTokenKind.IF,
                SemanticTokenKind.OTHERWISE, SemanticTokenKind.MATCH, SemanticTokenKind.WHEN,
                SemanticTokenKind.RETURN)) {
            var r = UserAliasValidator.validate(Map.of(k, List.of("the phrase here")), "en-US");
            assertFalse(r.valid(), "结构词默认应拒: " + k);
        }
    }

    @Test
    void structuralKindsAllowedWhenAuthorized_multiWord() {
        for (SemanticTokenKind k : List.of(
                SemanticTokenKind.MODULE_DECL, SemanticTokenKind.FUNC_TO, SemanticTokenKind.IF,
                SemanticTokenKind.OTHERWISE, SemanticTokenKind.MATCH, SemanticTokenKind.WHEN,
                SemanticTokenKind.RETURN)) {
            var r = UserAliasValidator.validate(
                Map.of(k, List.of("the phrase here")), "en-US", null, true);
            assertTrue(r.valid(), "授权后结构词多词别名应允许: " + k + " errors=" + r.errors());
        }
    }

    @Test
    void structuralAuthorizedButSingleWord_stillRejected_guardMultiWord() {
        // 护栏①：授权下结构词单词别名仍拒（多词铁律）。
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.RETURN, List.of("answer")), "en-US", null, true);
        assertFalse(r.valid());
        assertTrue(String.join(",", r.errors()).contains("多词"));
    }

    @Test
    void highRiskKindsRejectedEvenWhenAuthorized() {
        // 高危 kind（IMPORT/AND/OR/NOT/IO/AWAIT/布尔）即使 allowStructural=true 也拒。
        for (SemanticTokenKind k : List.of(
                SemanticTokenKind.IMPORT, SemanticTokenKind.AND, SemanticTokenKind.OR,
                SemanticTokenKind.NOT, SemanticTokenKind.IO, SemanticTokenKind.AWAIT)) {
            var r = UserAliasValidator.validate(
                Map.of(k, List.of("some phrase")), "en-US", null, true);
            assertFalse(r.valid(), "高危 kind 即使授权也应拒: " + k);
        }
    }

    // ---- W2 DoS 上界（与 cloud policy-alias.test.ts W2 块逐一对齐） ----

    @Test
    void w2_rejectsTooManyKinds() {
        java.util.Map<SemanticTokenKind, List<String>> big = new java.util.HashMap<>();
        // 33 > MAX_ALIAS_KINDS(32)。用真实 kind 枚举填够数量（不足则本测试无意义，断言防退化）。
        var all = SemanticTokenKind.values();
        int n = Math.min(all.length, UserAliasValidator.MAX_ALIAS_KINDS + 1);
        for (int i = 0; i < n; i++) {
            big.put(all[i], List.of("a b"));
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            all.length > UserAliasValidator.MAX_ALIAS_KINDS,
            "枚举数量须超过上限，测试才有效");
        var r = UserAliasValidator.validate(big, "en-US");
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("kind 数量")));
    }

    @Test
    void w2_rejectsTooManyAliasesPerKind() {
        java.util.List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < UserAliasValidator.MAX_ALIASES_PER_KIND + 1; i++) {
            many.add("alias number " + i);
        }
        var r = UserAliasValidator.validate(Map.of(SemanticTokenKind.TIMES, many), "en-US");
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("别名数量")));
    }

    @Test
    void w2_rejectsOverlongAlias() {
        String longAlias = "a " + "x".repeat(200);
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of(longAlias)), "en-US");
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("别名长度")));
    }

    @Test
    void w2_acceptsAtLimitBoundary() {
        java.util.List<String> eight = new java.util.ArrayList<>();
        for (int i = 0; i < UserAliasValidator.MAX_ALIASES_PER_KIND; i++) {
            eight.add("alias phrase " + i);
        }
        var r = UserAliasValidator.validate(Map.of(SemanticTokenKind.TIMES, eight), "en-US");
        assertTrue(r.valid(), () -> r.errors().toString());
    }

    /** 构造一个含单条 field 映射的领域词汇作用域（用于碰撞测试）。 */
    private static VocabularyScopes vocabIndexWith(String canonical, String localized) {
        DomainVocabulary vocab = new DomainVocabulary(
            "test-vocab", "Test Vocab", "en-US", "1.0.0",
            List.of(), List.of(IdentifierMapping.field(canonical, localized, "Account")),
            List.of(), List.of(), List.of(), null);
        return VocabularyScopes.build(vocab);
    }
}
