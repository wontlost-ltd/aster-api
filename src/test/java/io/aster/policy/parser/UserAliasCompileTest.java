package io.aster.policy.parser;

import aster.core.ast.Module;
import aster.core.embed.AliasOverlayLexicon;
import aster.core.ir.CoreModel;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 D（ADR 0022 §11）用户自定义关键词别名编译路径测试。
 *
 * <p>验证核心不变式：编译期注入的 aliasSet 经 {@link AliasOverlayLexicon} 注入后，
 * 「别名版」源码与「规范版」源码降到**结构一致的 Core IR**（剥离 origin 源码位置元
 * 数据，与 ADR 0016 同口径）→ 一致性锚点（Core IR）不受用户别名影响。
 */
class UserAliasCompileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode lowerToIr(String source, Map<SemanticTokenKind, List<String>> aliasSet) {
        // 测编译机制本身：用 unsafe 入口（不校验）；校验闸由 UserAliasValidatorTest 覆盖。
        InProcessCnlParser.ParseResult pr =
            InProcessCnlParser.parseUnsafeWithAliases(source, "en-US", null, aliasSet);
        Module ast = pr.module();
        CoreModel.Module core = new CoreLowering().lowerModule(ast);
        return stripOrigin(MAPPER.valueToTree(core));
    }

    private static JsonNode stripOrigin(JsonNode node) {
        if (node.isObject()) {
            var out = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                String k = e.getKey();
                if (k.equals("origin") || k.equals("span") || k.equals("line") || k.equals("col")) {
                    continue;
                }
                out.set(k, stripOrigin(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            var out = MAPPER.createArrayNode();
            for (JsonNode c : node) {
                out.add(stripOrigin(c));
            }
            return out;
        }
        return node;
    }

    @Test
    void userAliasLowersToSameIrAsCanonical() {
        // 用户自定义多词别名（方案 D 白名单形态：低风险多词运算符）
        Map<SemanticTokenKind, List<String>> aliasSet = Map.of(
            SemanticTokenKind.TIMES, List.of("multiplied by"),
            SemanticTokenKind.DIVIDED_BY, List.of("split by")
        );

        String aliasSrc = """
            Module Pricing.

            Rule discountedPrice given amount as Int, produce Int:
              If amount greater than 100
                Return amount multiplied by 90 split by 100.
              Return amount.""";

        String canonicalSrc = """
            Module Pricing.

            Rule discountedPrice given amount as Int, produce Int:
              If amount greater than 100
                Return amount times 90 divided by 100.
              Return amount.""";

        JsonNode aliasIr = lowerToIr(aliasSrc, aliasSet);
        JsonNode canonIr = lowerToIr(canonicalSrc, null);

        assertEquals(canonIr, aliasIr, "用户别名版与规范版应降到结构一致的 Core IR");
    }

    @Test
    void twoDifferentUserAliasSetsProduceSameIr() {
        // 两个用户各自的别名（含自创 "scaled by"）→ 同一 Core IR（一致性与别名正交）
        String srcA = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x multiplied by 3.";
        String srcB = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x scaled by 3.";

        JsonNode irA = lowerToIr(srcA, Map.of(SemanticTokenKind.TIMES, List.of("multiplied by")));
        JsonNode irB = lowerToIr(srcB, Map.of(SemanticTokenKind.TIMES, List.of("scaled by")));

        assertEquals(irA, irB, "两套不同用户别名应产出同一 Core IR");
    }

    @Test
    void structuralAliasesLowerToSameIrAsCanonical_whenAuthorized() {
        // ADR 0022 结构词扩展（C1）：授权用户用结构词多词别名写的源码，经
        // parseWithUserAliases(allowStructural=true) 应降到与规范关键词版一致的 Core IR。
        // 证明冻结版本执行时别名能归回真实结构（存得进也跑得了）。
        Map<SemanticTokenKind, List<String>> aliasSet = Map.of(
            SemanticTokenKind.FUNC_TO, List.of("the rule for"),
            SemanticTokenKind.IF, List.of("in the case that"),
            SemanticTokenKind.RETURN, List.of("the answer is")
        );
        String aliasSrc = """
            Module Pricing.

            the rule for discountedPrice given amount as Int, produce Int:
              in the case that amount greater than 100
                the answer is amount times 90 divided by 100.
              the answer is amount.""";
        String canonicalSrc = """
            Module Pricing.

            Rule discountedPrice given amount as Int, produce Int:
              If amount greater than 100
                Return amount times 90 divided by 100.
              Return amount.""";

        InProcessCnlParser.ParseResult pr =
            InProcessCnlParser.parseWithUserAliases(aliasSrc, "en-US", null, aliasSet, true);
        JsonNode aliasIr = stripOrigin(MAPPER.valueToTree(new CoreLowering().lowerModule(pr.module())));
        JsonNode canonIr = lowerToIr(canonicalSrc, null);
        assertEquals(canonIr, aliasIr, "授权的结构词别名版应降到与规范版一致的 Core IR");
    }

    @Test
    void structuralAliasesRejected_whenNotAuthorized() {
        // 未授权（默认 allowStructural=false）：结构词别名被校验拒 → 抛 CnlParseException。
        Map<SemanticTokenKind, List<String>> aliasSet = Map.of(
            SemanticTokenKind.RETURN, List.of("the answer is"));
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  the answer is x.";
        org.junit.jupiter.api.Assertions.assertThrows(
            InProcessCnlParser.CnlParseException.class,
            () -> InProcessCnlParser.parseWithUserAliases(src, "en-US", null, aliasSet),
            "未授权时结构词别名应被校验拒绝");
    }

    @Test
    void nullAliasSetIsBackwardCompatible() {
        // aliasSet 为 null → 与不带别名的 parse 完全一致
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 2.";
        JsonNode withNull = lowerToIr(src, null);
        JsonNode withEmpty = lowerToIr(src, Map.of());
        assertEquals(withNull, withEmpty);
        assertTrue(withNull.toString().contains("\"*\""), "应含乘法算子");
    }

    // ---- 安全边界：executor 按 aliasesTrusted 决定 allowStructural（trial vs 冻结版本）----

    @Test
    void executor_untrustedStructuralAlias_rejected() {
        // trial/现场输入（aliasesTrusted=false）：结构词别名不可信 → 校验拒 → parse 抛异常，
        // executor 包成 DynamicExecutionException。防直连 API-key 的 trial 调用绕过 per-user
        // 授权注入结构词别名。
        Map<SemanticTokenKind, List<String>> aliasSet = Map.of(
            SemanticTokenKind.RETURN, List.of("the answer is"));
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  the answer is x.";

        DynamicCnlExecutor executor = new DynamicCnlExecutor();
        org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class,
            () -> executor.executeWithTenantContext(
                "tenant-1", src, Map.of("x", 5), "p", "en-US", null, false, aliasSet, false),
            "未授权结构词别名的 trial 执行应被拒绝");
    }

    @Test
    void executor_trustedStructuralAlias_executes() {
        // 冻结版本（aliasesTrusted=true）：结构词别名可信 → 正常执行。
        Map<SemanticTokenKind, List<String>> aliasSet = Map.of(
            SemanticTokenKind.RETURN, List.of("the answer is"));
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  the answer is x.";

        DynamicCnlExecutor executor = new DynamicCnlExecutor();
        DynamicCnlExecutor.ExecutionResult result = executor.executeWithTenantContext(
            "tenant-1", src, Map.of("x", 5), "p", "en-US", null, false, aliasSet, true);

        assertEquals(5, result.result(), "授权结构词别名应正常执行");
    }
}
