package io.aster.policy.parser;

import aster.core.lexicon.CanonicalizationConfig;
import aster.core.lexicon.ErrorMessages;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.PunctuationConfig;
import aster.core.lexicon.SemanticTokenKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 别名覆盖装饰器（ADR 0022 方案 D）。
 *
 * <p>把一个基础 lexicon（en-US/zh-CN/…）与一组**用户自定义别名**（aliasSet）组合：
 * {@link #getAliases()} 返回 base 别名与 user 别名的**合并快照**（深拷贝、不可变），
 * 其余全部透传基础 lexicon。
 *
 * <p>设计原则（与 ADR 0022 §3 核心不变式一致）：别名只在**识别侧**。Canonicalizer
 * 读 {@code getAliases()} 把别名归一成规范拼写后再进下游 → 用别名与规范拼写写的同一
 * 逻辑产出**同一 Core IR**。aliasSet 为空时行为与基础 lexicon 逐字节相同（向后兼容）。
 *
 * <p>方案 D 形态：aliasSet 来自策略版本快照（不可变、随版本固化、进哈希覆盖），编译期
 * 注入；runtime 不涉及（{@code /evaluate} 跑已编译的 Core IR）。
 */
public final class AliasOverlayLexicon implements Lexicon {

    private final Lexicon base;
    /** base.getAliases() 与 user aliasSet 合并后的不可变快照（深拷贝）。 */
    private final Map<SemanticTokenKind, List<String>> aliases;

    private AliasOverlayLexicon(Lexicon base, Map<SemanticTokenKind, List<String>> merged) {
        this.base = base;
        this.aliases = merged;
    }

    /**
     * 用 aliasSet 包裹基础 lexicon。aliasSet 为空/null 时直接返回基础 lexicon（零开销，
     * 不引入额外装饰层）。
     *
     * <p>合并语义（Codex 复核修正）：**叠加**而非替换——base.getAliases() 先入，user
     * aliasSet 后入，对同一 kind 去重合并。深拷贝（含嵌套 List）保证版本快照不可变。
     * base alias 与 user alias 的拼写冲突由 {@link UserAliasValidator} 在前置校验拦截，
     * 此处合并不静默丢弃任一侧。
     */
    public static Lexicon wrap(Lexicon base, Map<SemanticTokenKind, List<String>> aliasSet) {
        if (base == null) {
            throw new IllegalArgumentException("base lexicon required");
        }
        boolean userEmpty = aliasSet == null || aliasSet.isEmpty();
        Map<SemanticTokenKind, List<String>> baseAliases = base.getAliases();
        boolean baseEmpty = baseAliases == null || baseAliases.isEmpty();
        // 用户与 base 都无别名 → 直接返回 base（零开销）。
        if (userEmpty && baseEmpty) {
            return base;
        }

        Map<SemanticTokenKind, List<String>> merged = new EnumMap<>(SemanticTokenKind.class);
        if (!baseEmpty) {
            for (Map.Entry<SemanticTokenKind, List<String>> e : baseAliases.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    merged.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
            }
        }
        if (!userEmpty) {
            for (Map.Entry<SemanticTokenKind, List<String>> e : aliasSet.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                List<String> list = merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                for (String a : e.getValue()) {
                    if (a != null && !a.isBlank() && !list.contains(a)) {
                        list.add(a);
                    }
                }
            }
        }
        // 深拷贝为不可变；保持 EnumMap 的声明顺序遍历。不能用 Map.copyOf：其遍历顺序随 JVM 启动时的随机 SALT
        // 变化，而 Canonicalizer 按 getAliases() 的遍历顺序 putIfAbsent 登记别名（先到者胜），两个 kind 的别名
        // 同形时同一份源会在不同进程里规范化成不同关键词（与 aster-lang-core#281 同类）。
        Map<SemanticTokenKind, List<String>> immutable = new EnumMap<>(SemanticTokenKind.class);
        for (Map.Entry<SemanticTokenKind, List<String>> e : merged.entrySet()) {
            immutable.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return new AliasOverlayLexicon(base, Collections.unmodifiableMap(immutable));
    }

    @Override
    public Map<SemanticTokenKind, List<String>> getAliases() {
        return aliases;
    }

    // ─── 其余全部透传基础 lexicon ───────────────────────────────

    @Override
    public String getId() {
        return base.getId();
    }

    @Override
    public String getName() {
        return base.getName();
    }

    @Override
    public Direction getDirection() {
        return base.getDirection();
    }

    @Override
    public Map<SemanticTokenKind, String> getKeywords() {
        return base.getKeywords();
    }

    @Override
    public PunctuationConfig getPunctuation() {
        return base.getPunctuation();
    }

    @Override
    public CanonicalizationConfig getCanonicalization() {
        return base.getCanonicalization();
    }

    @Override
    public ErrorMessages getMessages() {
        return base.getMessages();
    }
}
