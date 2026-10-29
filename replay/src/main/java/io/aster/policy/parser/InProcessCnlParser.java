package io.aster.policy.parser;

import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreModel;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import aster.core.resolve.ImportedDeclarations;
import aster.core.resolve.NameResolution;
import aster.core.resolve.NameResolutionException;
import aster.core.resolve.ResolutionContext;
import aster.core.typecheck.model.Diagnostic;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * 内嵌 CNL 解析器
 *
 * 使用 ANTLR 直接解析 CNL 源代码，替代通过 CLI 工具调用的方式。
 * 这使得 Quarkus Policy API 可以在没有 aster-convert CLI 的环境中运行。
 *
 * <p>ADR 0051：解析之后紧接名称解析（R1 → lower → R2），产出只含规范名的 Core。R2 依赖 lower 在
 * 同一进程内登记的解析标记，因此调用方必须使用 {@link ParseResult#core()}，不得自行重新 lower AST。
 */
public class InProcessCnlParser {

    private static final Logger LOG = Logger.getLogger(InProcessCnlParser.class);

    /**
     * CNL 解析结果
     *
     * @param module           R1 之后的 AST（标识符保持原写法，带解析结果）；供需要源码位置的分析使用
     * @param moduleName       模块名（无模块头时取首个函数名）
     * @param functionNames    全部函数的规范名
     * @param entryFunctionName 带 @entry 的函数规范名，无则为 null
     * @param core             名称解析后的 Core（只含规范名）
     * @param warnings         名称解析的 warning 级诊断（W8xx）
     */
    public record ParseResult(
        Module module,
        String moduleName,
        String firstFunctionName,
        List<String> functionNames,
        String entryFunctionName,
        CoreModel.Module core,
        List<Diagnostic> warnings
    ) {}

    /**
     * 解析 CNL 源代码（使用默认 locale）
     *
     * @param source CNL 源代码
     * @return 解析结果，包含 AST 模块和提取的模块/函数名称
     * @throws CnlParseException 解析失败时抛出
     */
    public static ParseResult parse(String source) {
        return parse(source, null);
    }

    /**
     * 解析 CNL 源代码（支持多语言）
     *
     * @param source CNL 源代码
     * @param locale 语言代码（如 "zh-CN"、"de-DE"、"en-US"），null 表示自动检测或默认英语
     * @return 解析结果，包含 AST 模块和提取的模块/函数名称
     * @throws CnlParseException 解析失败时抛出
     */
    public static ParseResult parse(String source, String locale) {
        return parse(source, locale, null);
    }

    /**
     * 解析 CNL 源代码并按位置解析领域词汇（ADR 0051）
     *
     * <p>词汇不再在解析前做文本替换：canonicalize 只展开字面量宏，类型 / 字段 / 枚举成员 / 函数名
     * 在 AST 与 Core 上按所在位置的角色解析；源码局部名（参数、Let 等）永不被词汇改写。
     *
     * @param source 源代码
     * @param locale 语言代码，null 表示默认英语
     * @param scopes 领域词汇作用域，null 表示无词汇
     * @return 解析结果
     * @throws CnlParseException 语法错误，或名称解析出现 error（{@link NameResolutionFailure}）
     */
    public static ParseResult parse(String source, String locale, VocabularyScopes scopes) {
        return parseUnsafeWithAliases(source, locale, scopes, null);
    }

    /**
     * 受控编译入口（ADR 0022 方案 D）：**先强制校验** aliasSet，再注入编译。
     *
     * <p>这是生产路径应调用的方法。{@link UserAliasValidator} 强制执行白名单（只允许低风险
     * 运算符/比较 kind）、仅多词、不遮蔽规范拼写/base 别名、不撞领域词汇——校验失败抛
     * {@link CnlParseException}，绝不让未经校验的 aliasSet 进入编译（堵 Codex 复核的
     * Critical-1：校验必须前置，不能靠调用方纪律）。
     *
     * @param aliasSet 用户自定义别名（应来自不可变版本快照、经审批、进哈希覆盖）
     * @param scopes   领域词汇作用域（名称解析 + 别名↔标识符碰撞校验），可为 null
     */
    public static ParseResult parseWithUserAliases(String source, String locale,
                                                   VocabularyScopes scopes,
                                                   Map<SemanticTokenKind, List<String>> aliasSet) {
        return parseWithUserAliases(source, locale, scopes, aliasSet, false);
    }

    /**
     * 带别名解析，可指定 allowStructural（ADR 0022 结构词别名扩展）。
     *
     * <p><b>执行冻结版本时应传 allowStructural=true</b>：已发布版本的 aliasSet 是在创建时经
     * 管理员授权（per-user grant）+ UserAliasValidator 校验 + 冻结进 sourceEnvelope 的**可信快照**。
     * 执行端不应再按当前 grant 状态重判（grant 撤销不应打断已发布策略）；envelope 已防篡改。
     * 现场用户提交（非冻结）走 allowStructural=false（默认），结构词别名需授权。
     *
     * @param allowStructural 是否放开结构词别名校验（冻结可信版本执行时 true）
     */
    public static ParseResult parseWithUserAliases(String source, String locale,
                                                   VocabularyScopes scopes,
                                                   Map<SemanticTokenKind, List<String>> aliasSet,
                                                   boolean allowStructural) {
        UserAliasValidator.Result vr = UserAliasValidator.validate(aliasSet, locale, scopes, allowStructural);
        if (!vr.valid()) {
            throw new CnlParseException("用户自定义别名校验失败: " + String.join("; ", vr.errors()));
        }
        return parseUnsafeWithAliases(source, locale, scopes, aliasSet);
    }

    /**
     * 底层别名注入解析（**不校验** aliasSet）。
     *
     * <p>⚠ unsafe：直接信任传入的 aliasSet。除测试/已校验的内部调用外，生产代码应用
     * {@link #parseWithUserAliases}（前置 {@link UserAliasValidator}）。别名经
     * {@link AliasOverlayLexicon} 叠加基础 lexicon，Canonicalizer 识别侧归一成规范拼写
     * 后进下游 → 别名版与规范版同一 Core IR。aliasSet 为 null/空时行为与不带别名一致。
     *
     * @param aliasSet kind → 别名列表，null/空表示无用户别名
     */
    static ParseResult parseUnsafeWithAliases(String source, String locale, VocabularyScopes scopes,
                                              Map<SemanticTokenKind, List<String>> aliasSet) {
        if (source == null || source.isBlank()) {
            throw new CnlParseException("CNL 源代码不能为空");
        }

        try {
            // 1. 根据 locale 获取对应的 Lexicon，并叠加用户自定义别名（方案 D）后规范化源代码
            Lexicon lexicon = AliasOverlayLexicon.wrap(getLexiconForLocale(locale), aliasSet);

            // 所有语言都需要规范化，因为即使是英语也需要将关键词运算符（如 "greater than"）翻译为符号（如 ">"）
            // ANTLR 解析器只支持符号运算符，不支持关键词形式
            // 词汇在此只提供字面量宏；标识符按位置解析见下方第 6 步（ADR 0051）。
            Canonicalizer canonicalizer = new Canonicalizer(lexicon, scopes);
            String canonicalizedSource = canonicalizer.canonicalize(source);
            LOG.debugf("CNL 规范化完成: locale=%s, 原始长度=%d, 规范化后长度=%d",
                locale, source.length(), canonicalizedSource.length());
            // Debug: 输出完整规范化结果
            LOG.debugf("CNL 规范化输出:\n%s", canonicalizedSource);

            // 2. 创建词法分析器
            CharStream charStream = CharStreams.fromString(canonicalizedSource);
            AsterCustomLexer lexer = new AsterCustomLexer(charStream);
            // R30+ audit P1：ANTLR 默认 lexer 把错误打到 stderr 然后继续，
            // 漏掉的 token 会让 parser 看到一个"看起来合法但语义残缺"的流。
            // 显式挂同一份错误监听器，保证 lexer 错误能传给 CnlParseException。
            // 报错里的关键词回译成作者所写语言（canonicalize 之后 ANTLR 只见英文规范拼写）
            CnlErrorListener errorListener = new CnlErrorListener(KeywordLocalizer.forTarget(lexicon));
            lexer.removeErrorListeners();
            lexer.addErrorListener(errorListener);

            // 3. 创建语法分析器
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            tokens.seek(0);

            AsterParser parser = new AsterParser(tokens);
            // R30+ audit P1：ANTLR 默认 LL 模式在病理语法下会回溯 O(2^n)，
            // 给个 SLL 上限作为 DoS 防御。在正常语法上 SLL 与 LL 等价；
            // 解析失败时 ANTLR 会自动 fallback 到 LL，对正常输入零代价。
            parser.getInterpreter().setPredictionMode(
                org.antlr.v4.runtime.atn.PredictionMode.SLL);

            // 3. 添加错误监听器（与 lexer 共享同一份）
            parser.removeErrorListeners();
            parser.addErrorListener(errorListener);

            // 4. 解析模块
            AsterParser.ModuleContext moduleCtx = parser.module();

            // 检查解析错误：抛给用户的是友好化的首个错误（根因），原始 ANTLR
            // 级联错误仅记日志供调试，避免把几十条内部消息糊到用户脸上。
            if (errorListener.hasErrors()) {
                LOG.debugf("CNL 解析原始错误（全集）: %s", errorListener.getRawErrors());
                LOG.debugf("CNL 语法错误: %s", errorListener.getErrors());
                throw new CnlParseException(
                    "CNL 语法错误 — " + errorListener.getErrors(),
                    errorListener.getDiagnostics());
            }

            // 5. 构建 AST
            AstBuilder builder = new AstBuilder();
            Module module = builder.visitModule(moduleCtx);

            if (module == null) {
                throw new CnlParseException("无法构建 AST 模块");
            }

            // 6. 名称解析（R1 → lower → R2）：任一 E8xx 即失败，不产出 IR
            NameResolution.ResolvedCore resolved = resolveNames(module, scopes);
            CoreModel.Module core = resolved.core();

            // 7. 提取函数名称（规范名，取自 Core）；@entry 唯一性由 TypeChecker 校验，此处取首个
            List<String> functionNames = functionNamesOf(core);
            String firstFunctionName = functionNames.isEmpty() ? null : functionNames.get(0);
            String entryFunctionName = entryFunctionNameOf(core);

            // 8. 提取模块名称（如果没有模块头，使用函数名作为默认）
            String moduleName = core.name;
            if (moduleName == null || moduleName.isBlank()) {
                if (firstFunctionName != null && !firstFunctionName.isBlank()) {
                    moduleName = firstFunctionName;
                    LOG.debugf("使用函数名作为默认模块名: %s", moduleName);
                } else {
                    throw new CnlParseException("无法从 CNL 中提取模块名称或函数名称");
                }
            }

            LOG.infof("CNL 解析成功: module=%s, function=%s", moduleName, firstFunctionName);

            List<Diagnostic> warnings = resolved.diagnostics().stream()
                .filter(d -> d.severity() != Diagnostic.Severity.ERROR)
                .toList();
            return new ParseResult(module, moduleName, firstFunctionName, functionNames, entryFunctionName,
                core, warnings);

        } catch (CnlParseException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "CNL 解析失败: %s", e.getMessage());
            throw new CnlParseException("CNL 解析失败: " + e.getMessage(), e);
        }
    }

    /** R1 → lower → R2。导入模块的声明只能经别名限定访问（MODULE 段不解析），故无需导入声明表。 */
    private static NameResolution.ResolvedCore resolveNames(Module ast, VocabularyScopes scopes) {
        NameResolution.ResolvedCore resolved =
            NameResolution.resolveAndLower(ast, new ResolutionContext(scopes, ImportedDeclarations.NONE));
        if (NameResolution.hasErrors(resolved.diagnostics())) {
            NameResolutionFailure failure = new NameResolutionFailure(resolved.diagnostics());
            // 用户源码问题由调用边界按场景记录，解析器自身只留 DEBUG，避免同一错误重复多条
            LOG.debugf("CNL %s", failure.getMessage());
            throw failure;
        }
        return resolved;
    }

    private static List<String> functionNamesOf(CoreModel.Module core) {
        if (core.decls == null) {
            return List.of();
        }
        return core.decls.stream()
            .filter(CoreModel.Func.class::isInstance)
            .map(d -> ((CoreModel.Func) d).name)
            .toList();
    }

    private static String entryFunctionNameOf(CoreModel.Module core) {
        if (core.decls == null) {
            return null;
        }
        return core.decls.stream()
            .filter(CoreModel.Func.class::isInstance)
            .map(CoreModel.Func.class::cast)
            .filter(InProcessCnlParser::hasEntryAnnotation)
            .map(f -> f.name)
            .findFirst()
            .orElse(null);
    }

    private static boolean hasEntryAnnotation(CoreModel.Func func) {
        return func.annotations != null
            && func.annotations.stream().anyMatch(ann -> ann != null && "entry".equals(ann.name));
    }

    /**
     * 根据 locale 获取对应的 Lexicon
     *
     * @param locale 语言代码（如 "zh-CN"、"de-DE"、"en-US"），null 表示默认英语
     * @return 对应的 Lexicon 实例
     */
    /**
     * 返回给定 locale 当前实际解析用的 lexicon 的内容指纹（id + keywords + aliases）。
     *
     * 供 Core IR 缓存 key 使用：lexicon 是可热插拔/下线的（LexiconRegistry 动态注册 +
     * 可用性开关），同一 locale 在 lexicon 被替换/禁用/恢复后，getLexiconForLocale 可能返回
     * 不同的 lexicon 对象（禁用会 fallback 到默认/前缀匹配），parse/canonicalize 结果随之变化。
     * 把当前 lexicon 的内容纳入缓存 key，使 lexicon 变更后旧 Core IR 自然失效（无需 registry
     * listener 主动清缓存）。指纹涵盖 getLexiconForLocale 的完整解析结果（含禁用→fallback），
     * 因为它对同一 locale 调同一 getLexiconForLocale 拿到的就是实际 parse 用的那个 lexicon。
     *
     * @return lexicon 内容指纹；计算失败时返回 {@code null}，调用方据此**放弃缓存该次编译产物**
     *   （保守：指纹算不出就无法保证 lexicon 未变，不缓存好过缓存可能过时的 Core IR）。
     */
    public static String lexiconFingerprintForLocale(String locale) {
        try {
            Lexicon lexicon = getLexiconForLocale(locale);
            java.util.TreeMap<String, Object> payload = new java.util.TreeMap<>();
            payload.put("id", lexicon.getId());
            // keywords/aliases 的 key 是 enum SemanticTokenKind，用 name() 转字符串保证 JSON 稳定有序。
            java.util.TreeMap<String, String> keywords = new java.util.TreeMap<>();
            lexicon.getKeywords().forEach((k, v) -> keywords.put(k.name(), v));
            payload.put("keywords", keywords);
            java.util.TreeMap<String, java.util.List<String>> aliases = new java.util.TreeMap<>();
            lexicon.getAliases().forEach((k, v) -> aliases.put(k.name(), v));
            payload.put("aliases", aliases);
            return io.aster.replay.core.parser.ReplayMappers.DEFAULT.writeValueAsString(payload);
        } catch (Exception e) {
            // 指纹计算失败（极罕见异常路径）：返回 null，调用方放弃缓存（确定性处理，不引入
            // 随机源）。不抛异常、不阻断执行。
            LOG.warnf("计算 lexicon 指纹失败（locale=%s），本次跳过 Core IR 缓存: %s", locale, e.getMessage());
            return null;
        }
    }

    private static Lexicon getLexiconForLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return LexiconRegistry.getInstance().getDefault();
        }

        String normalizedLocale = locale.toLowerCase(java.util.Locale.ROOT).replace("_", "-");

        // 优先尝试从 LexiconRegistry 获取（支持动态注册的 Lexicon）
        LexiconRegistry registry = LexiconRegistry.getInstance();
        if (registry.has(normalizedLocale)) {
            return registry.getOrThrow(normalizedLocale);
        }

        // 回退到常见 locale 前缀匹配
        if (normalizedLocale.startsWith("zh")) {
            return registry.getOrThrow("zh-cn");
        } else if (normalizedLocale.startsWith("de")) {
            return registry.getOrThrow("de-de");
        } else {
            // 对于不支持的 locale，回退到英语
            LOG.warnf("Unsupported locale '%s', falling back to en-US", locale);
            return registry.getDefault();
        }
    }

    /**
     * 名称解析失败（ADR 0051 E8xx）。归入 {@link CnlParseException}，使既有按「源码无法编译」处理的
     * 入口无需特判；编译端点通过 {@link #resolutionDiagnostics()} 拿到带码与位置的原始诊断。
     */
    public static final class NameResolutionFailure extends CnlParseException {
        private static final long serialVersionUID = 1L;

        private final transient List<Diagnostic> resolutionDiagnostics;

        NameResolutionFailure(List<Diagnostic> diagnostics) {
            this(new NameResolutionException(diagnostics));
        }

        private NameResolutionFailure(NameResolutionException errors) {
            super("名称解析失败 — " + errors.getMessage().replace("\n", "; "),
                errors.diagnostics().stream().map(NameResolutionFailure::toListenerDiagnostic).toList());
            this.resolutionDiagnostics = errors.diagnostics();
        }

        /** 只含 error 级诊断（code + 位置 + 消息）。 */
        public List<Diagnostic> resolutionDiagnostics() {
            return resolutionDiagnostics;
        }

        private static CnlErrorListener.Diagnostic toListenerDiagnostic(Diagnostic d) {
            int line = d.span().map(o -> o.start == null ? 1 : o.start.line).orElse(1);
            int col = d.span().map(o -> o.start == null ? 1 : o.start.col).orElse(1);
            return new CnlErrorListener.Diagnostic(line, col, d.code().code() + " " + d.message());
        }
    }

    /**
     * CNL 解析异常
     */
    public static class CnlParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** 结构化诊断（含 1-based 行列）。语法错误路径携带；其它路径为空。 */
        private final transient List<CnlErrorListener.Diagnostic> diagnostics;

        public CnlParseException(String message) {
            this(message, java.util.List.of());
        }

        public CnlParseException(String message, List<CnlErrorListener.Diagnostic> diagnostics) {
            super(message);
            this.diagnostics = diagnostics == null ? java.util.List.of() : diagnostics;
        }

        public CnlParseException(String message, Throwable cause) {
            super(message, cause);
            this.diagnostics = java.util.List.of();
        }

        /** 结构化诊断（含 1-based 行列 + 友好消息）；可能为空。 */
        public List<CnlErrorListener.Diagnostic> getDiagnostics() {
            return diagnostics;
        }
    }
}
