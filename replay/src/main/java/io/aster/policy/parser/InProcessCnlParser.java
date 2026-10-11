package io.aster.policy.parser;

import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.IdentifierIndex;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
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
 */
public class InProcessCnlParser {

    private static final Logger LOG = Logger.getLogger(InProcessCnlParser.class);

    /**
     * CNL 解析结果
     */
    public record ParseResult(
        Module module,
        String moduleName,
        String firstFunctionName,
        List<String> functionNames,
        String entryFunctionName
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
     * 解析 CNL 源代码（支持多语言 + 领域词汇翻译）
     *
     * <p>ADR 0014 线C：发布的策略在执行端也需识别用户自定义领域术语。调用方
     * 传入由策略快照词汇构建的 {@link IdentifierIndex} 时，规范化阶段会执行
     * 标识符翻译（step 8.5），把本地化术语翻成规范化名称；为 null 时行为与
     * 仅内置一致（不做用户词翻译）。
     *
     * @param source 源代码
     * @param locale 语言代码，null 表示默认英语
     * @param identifierIndex 领域词汇索引，null 表示不做用户词翻译
     * @return 解析结果
     * @throws CnlParseException 解析失败时抛出
     */
    public static ParseResult parse(String source, String locale, IdentifierIndex identifierIndex) {
        return parseUnsafeWithAliases(source, locale, identifierIndex, null);
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
     * @param identifierIndex 领域词汇索引（用于别名↔标识符碰撞校验），可为 null
     */
    public static ParseResult parseWithUserAliases(String source, String locale,
                                                   IdentifierIndex identifierIndex,
                                                   Map<SemanticTokenKind, List<String>> aliasSet) {
        return parseWithUserAliases(source, locale, identifierIndex, aliasSet, false);
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
                                                   IdentifierIndex identifierIndex,
                                                   Map<SemanticTokenKind, List<String>> aliasSet,
                                                   boolean allowStructural) {
        UserAliasValidator.Result vr = UserAliasValidator.validate(aliasSet, locale, identifierIndex, allowStructural);
        if (!vr.valid()) {
            throw new CnlParseException("用户自定义别名校验失败: " + String.join("; ", vr.errors()));
        }
        return parseUnsafeWithAliases(source, locale, identifierIndex, aliasSet);
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
    static ParseResult parseUnsafeWithAliases(String source, String locale, IdentifierIndex identifierIndex,
                                              Map<SemanticTokenKind, List<String>> aliasSet) {
        if (source == null || source.isBlank()) {
            throw new CnlParseException("CNL 源代码不能为空");
        }

        try {
            // 1. 根据 locale 获取对应的 Lexicon，并叠加用户自定义别名（方案 D）后规范化源代码
            Lexicon lexicon = AliasOverlayLexicon.wrap(getLexiconForLocale(locale), aliasSet);

            // 所有语言都需要规范化，因为即使是英语也需要将关键词运算符（如 "greater than"）翻译为符号（如 ">"）
            // ANTLR 解析器只支持符号运算符，不支持关键词形式
            // 提供 identifierIndex 时启用领域标识符翻译（ADR 0014 线C）。
            Canonicalizer canonicalizer = identifierIndex != null
                ? new Canonicalizer(lexicon, identifierIndex)
                : new Canonicalizer(lexicon);
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

            // 6. 提取函数名称
            List<String> functionNames = extractFunctionNames(module);
            String firstFunctionName = functionNames.isEmpty() ? null : functionNames.get(0);
            // 6.1 提取 @entry 标记的入口函数名（ADR 0015 阶段2）。唯一性由
            //     aster-lang-core TypeChecker 校验，此处仅取首个匹配。
            String entryFunctionName = extractEntryFunctionName(module);

            // 7. 提取模块名称（如果没有模块头，使用函数名作为默认）
            String moduleName = module.name();
            if (moduleName == null || moduleName.isBlank()) {
                if (firstFunctionName != null && !firstFunctionName.isBlank()) {
                    moduleName = firstFunctionName;
                    LOG.debugf("使用函数名作为默认模块名: %s", moduleName);
                } else {
                    throw new CnlParseException("无法从 CNL 中提取模块名称或函数名称");
                }
            }

            LOG.infof("CNL 解析成功: module=%s, function=%s", moduleName, firstFunctionName);

            return new ParseResult(module, moduleName, firstFunctionName, functionNames, entryFunctionName);

        } catch (CnlParseException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "CNL 解析失败: %s", e.getMessage());
            throw new CnlParseException("CNL 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从模块中提取全部函数名称
     */
    private static List<String> extractFunctionNames(Module module) {
        if (module.decls() == null || module.decls().isEmpty()) {
            return List.of();
        }

        return module.decls().stream()
            .filter(decl -> decl instanceof Decl.Func)
            .map(decl -> ((Decl.Func) decl).name())
            .toList();
    }

    /**
     * 提取带 @entry 注解的入口函数名（无则返回 null）。
     *
     * <p>@entry 唯一性由 aster-lang-core TypeChecker 校验，此处取首个匹配即可。
     */
    private static String extractEntryFunctionName(Module module) {
        if (module.decls() == null || module.decls().isEmpty()) {
            return null;
        }
        return module.decls().stream()
            .filter(decl -> decl instanceof Decl.Func)
            .map(decl -> (Decl.Func) decl)
            .filter(InProcessCnlParser::hasEntryAnnotation)
            .map(Decl.Func::name)
            .findFirst()
            .orElse(null);
    }

    /**
     * 判断函数是否带 @entry 注解。
     */
    private static boolean hasEntryAnnotation(Decl.Func func) {
        if (func.annotations() == null) {
            return false;
        }
        return func.annotations().stream()
            .anyMatch(ann -> "entry".equals(ann.name()));
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
