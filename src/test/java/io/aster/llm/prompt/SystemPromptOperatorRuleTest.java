package io.aster.llm.prompt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 生成策略的系统提示词必须讲清「`is` 不能接符号」（三语一致）。
 *
 * <h2>被修复的缺口</h2>
 *
 * <p>提示词原本并列列出「自然语言：less than…」与「符号：&lt;, &gt;, &lt;=…」，
 * 却<b>从不提 {@code is} 连接词</b>，示例本身也两种风格混着用
 * （{@code If applicant.age less than 18} 与 {@code If creditScore >= 750}）。
 * 模型于是把上下文里见到的 {@code is …} 与提示词列出的符号拼在一起，
 * 生成 {@code is < 18}——看着像对的，实际是语法错误。
 *
 * <p>真实事故：这样的策略存进库，用户在执行页才看到
 * 「行 15 第 25 列：无法解析 'Ifapplicant.ageis&lt;18'」。
 *
 * <p>本测试锁住三份提示词都写明该约束，防止改动时漏掉某一语言。
 */
class SystemPromptOperatorRuleTest {

    private static final List<String> LOCALES = List.of("en", "zh", "de");

    private static String prompt(String locale) throws Exception {
        return Files.readString(
            Path.of("src/main/resources/prompts/system/system_base_" + locale + ".txt"));
    }

    @Test
    void 三语提示词都必须给出is接符号的反例() throws Exception {
        for (String loc : LOCALES) {
            assertThat(prompt(loc))
                .as("★%s 提示词必须写出反例 `is < 18`——"
                    + "只正面列出两种写法而不说不能混用，模型就会把它们拼起来", loc)
                .contains("is < 18");
        }
    }

    @Test
    void 三语提示词都必须给出可用的替代写法() throws Exception {
        for (String loc : LOCALES) {
            assertThat(prompt(loc))
                .as("★%s 提示词必须同时给出两条改法，否则用户只知道错、不知道怎么改", loc)
                .contains("is less than 18");
        }
    }

    /**
     * ★提示词此前断言「不要使用 at least/at most」，而引擎<b>接受</b>这两个词
     * （实测：{@code If applicant.age is at least 18} 编译并执行通过）。
     * 提示词不得再散布这条与实现不符的禁令。
     */
    @Test
    void 提示词不得再禁止引擎实际支持的atLeast() throws Exception {
        assertThat(prompt("en"))
            .as("★引擎支持 at least/at most，提示词不得禁止")
            .doesNotContain("Do NOT use \"at least\" or \"at most\"");
        assertThat(prompt("zh"))
            .doesNotContain("不要使用 \"at least\" 或 \"at most\"");
        assertThat(prompt("de"))
            .doesNotContain("Verwende NICHT \"at least\" oder \"at most\"");
    }
}
