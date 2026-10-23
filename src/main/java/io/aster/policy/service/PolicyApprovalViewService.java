package io.aster.policy.service;

import aster.core.identifier.IdentifierIndex;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.parser.AliasOverlayLexicon;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 装配审批视图（ADR 0022 §11.5 H4）：给审批者同时呈现别名源码、规范源码、别名对照、IR 摘要，
 * 消除"看别名、批归一语义"的社会工程鸿沟。
 *
 * <p>纯数据装配，不依赖 DB（输入是已读出的 {@link PolicyVersion}）。端点暴露在生产审批路径接入。
 *
 * <p><b>审计 #98（Low，DEFERRED）</b>：{@link #build(PolicyVersion)} 在 {@code src/main}
 * 内暂无调用方。反社会工程审批 legend 是否落地，取决于 cloud BFF 是否渲染等价视图——需与
 * cloud 侧确认后再决定「后端直接暴露该视图端点」还是「保留 BFF 渲染」。本 PR 不改行为，
 * 仅记录 defer；见 issue #98。</p>
 */
@ApplicationScoped
public class PolicyApprovalViewService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    PolicyCompiler policyCompiler;

    /**
     * 为某版本装配审批视图。
     */
    public PolicyApprovalView build(PolicyVersion v) {
        List<String> warnings = new ArrayList<>();
        Map<SemanticTokenKind, List<String>> aliasSet = parseAliasSet(v.aliasSet, warnings);
        Lexicon base = resolveLexicon(v.locale);
        // 注入别名后 canonicalize：这是引擎实际看到的规范源码（别名已归一成规范拼写）。
        Lexicon effective = AliasOverlayLexicon.wrap(base, aliasSet);
        String canonicalSource = new aster.core.canonicalizer.Canonicalizer(effective)
            .canonicalize(v.content == null ? "" : v.content);

        // 别名对照：每个别名 → 其 kind 的规范关键词。
        List<PolicyApprovalView.AliasLegendEntry> legend = new ArrayList<>();
        for (Map.Entry<SemanticTokenKind, List<String>> e : aliasSet.entrySet()) {
            String canonicalKeyword = base.getKeyword(e.getKey()).orElse(e.getKey().name());
            for (String alias : e.getValue()) {
                legend.add(new PolicyApprovalView.AliasLegendEntry(alias, canonicalKeyword, e.getKey().name()));
            }
        }

        // IR 摘要：用版本冻结的别名编译，取 Core IR JSON（截断为摘要，完整 IR 另有端点）。
        String irSummary;
        CompilationResult cr = policyCompiler.compile(v.content, v.locale, v.aliasSet);
        if (cr.isSuccess()) {
            String core = cr.getCoreJson();
            irSummary = core.length() > 2000 ? core.substring(0, 2000) + "…(truncated)" : core;
        } else {
            irSummary = "compile failed: " + String.join("; ", cr.getErrors());
        }

        return new PolicyApprovalView(v.content, canonicalSource, legend, irSummary, warnings);
    }

    /**
     * 解析 aliasSet；损坏时**不静默吞**——记入 warnings 让审批者看到异常状态（Codex 复核 H4）。
     * 返回空对照（编译路径已 fail-closed，此处只读展示不抛），但 warnings 会高亮损坏。
     */
    private static Map<SemanticTokenKind, List<String>> parseAliasSet(String json, List<String> warnings) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, List<String>> raw = MAPPER.readValue(json,
                new TypeReference<Map<String, List<String>>>() {});
            Map<SemanticTokenKind, List<String>> out = new java.util.EnumMap<>(SemanticTokenKind.class);
            for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                try {
                    out.put(SemanticTokenKind.valueOf(e.getKey()), List.copyOf(e.getValue()));
                } catch (IllegalArgumentException unknownKind) {
                    warnings.add("aliasSet 含未知 kind '" + e.getKey() + "'，对照表已忽略该项");
                }
            }
            return out;
        } catch (Exception e) {
            warnings.add("⚠ aliasSet 无法解析（疑似损坏）：" + e.getMessage()
                + " —— 审批前请核实该版本编译输入完整性");
            return Map.of();
        }
    }

    private static Lexicon resolveLexicon(String locale) {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        if (locale == null || locale.isBlank()) {
            return registry.getDefault();
        }
        String norm = locale.toLowerCase(Locale.ROOT).replace('_', '-');
        if (registry.has(norm)) {
            return registry.getOrThrow(norm);
        }
        if (norm.startsWith("zh") && registry.has("zh-cn")) {
            return registry.getOrThrow("zh-cn");
        }
        if (norm.startsWith("de") && registry.has("de-de")) {
            return registry.getOrThrow("de-de");
        }
        return registry.getDefault();
    }
}
