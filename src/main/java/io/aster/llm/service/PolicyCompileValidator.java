package io.aster.llm.service;

import io.aster.llm.model.ValidationResult;
import aster.core.embed.InProcessCnlParser;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 策略编译校验器
 *
 * 验证 LLM 生成的 CNL 源代码是否可通过编译。
 * 复用现有 InProcessCnlParser 执行语法校验。
 */
@ApplicationScoped
public class PolicyCompileValidator {

    private static final Logger LOG = Logger.getLogger(PolicyCompileValidator.class);

    /** 匹配 markdown 代码块标记 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("^```\\w*\\s*\n?(.*?)\\s*```$", Pattern.DOTALL);

    /**
     * 校验 CNL 源代码
     *
     * @param policySource LLM 生成的策略代码（可能包含 markdown 标记）
     * @param locale       语言代码
     * @return 校验结果
     */
    public ValidationResult validate(String policySource, String locale) {
        if (InProcessCnlParser.isBlankSource(policySource)) {
            return ValidationResult.failure("策略代码为空");
        }

        // 清理 LLM 输出中常见的 markdown 标记
        String cleanedSource = cleanLlmOutput(policySource);

        List<String> errors = new ArrayList<>();

        try {
            // 语法解析
            InProcessCnlParser.ParseResult parseResult = InProcessCnlParser.parse(cleanedSource, locale);

            LOG.infof("CNL 校验通过: module=%s, function=%s",
                parseResult.moduleName(), parseResult.firstFunctionName());

            return ValidationResult.success(parseResult.moduleName(), parseResult.firstFunctionName());

        } catch (InProcessCnlParser.CnlParseException e) {
            errors.add("语法错误: " + e.getMessage());
            LOG.warnf("CNL 校验失败: %s", e.getMessage());
        } catch (Exception e) {
            errors.add("校验异常: " + e.getMessage());
            LOG.errorf(e, "CNL 校验异常");
        }

        return ValidationResult.failure(errors);
    }

    /**
     * 清理 LLM 输出
     *
     * LLM 有时会在输出中包含 markdown 代码块标记，需要去除。
     */
    String cleanLlmOutput(String source) {
        String trimmed = source.trim();

        // 去除 markdown 代码块
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            trimmed = matcher.group(1).trim();
        }

        // 去除开头可能的 "```aster" 或 "```" 行
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }

        return trimmed;
    }
}
