package io.aster.llm.safety;

import io.aster.llm.safety.PromptScopeFilter.Strictness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptScopeFilterTest {

    private final PromptScopeFilter filter = new PromptScopeFilter();

    @Nested
    @DisplayName("MEDIUM — 偏题黑名单（应拒绝）")
    class OffTopicDeny {

        @Test
        void writeNovel() {
            assertThat(filter.check("write a novel about dragons", Strictness.MEDIUM).blocked()).isTrue();
        }

        @Test
        void translateToChinese() {
            assertThat(filter.check("Translate this to Chinese", Strictness.MEDIUM).blocked()).isTrue();
        }

        @Test
        void writePythonCode() {
            assertThat(filter.check("Write some python code for me", Strictness.MEDIUM).blocked()).isTrue();
        }

        @Test
        void explainPythonCode() {
            assertThat(filter.check("Explain this python code: print(1)", Strictness.MEDIUM).blocked()).isTrue();
        }

        @Test
        void writeNovelChinese() {
            assertThat(filter.check("写一个小说", Strictness.MEDIUM).blocked()).isTrue();
        }

        @Test
        void translateChinese() {
            assertThat(filter.check("翻译成英文", Strictness.MEDIUM).blocked()).isTrue();
        }

        @Test
        void tellJokeChinese() {
            assertThat(filter.check("讲一个笑话", Strictness.MEDIUM).blocked()).isTrue();
        }

        @Test
        void writePythonChinese() {
            assertThat(filter.check("写一段 Python 代码", Strictness.MEDIUM).blocked()).isTrue();
        }
    }

    @Nested
    @DisplayName("MEDIUM — 正常 policy 请求（应放行）")
    class OnTopicAllow {

        @Test
        void englishPolicyRequest() {
            assertThat(filter.check("Create a policy that checks loan eligibility", Strictness.MEDIUM)
                .blocked()).isFalse();
        }

        @Test
        void chinesePolicyRequest() {
            assertThat(filter.check("写一个判断贷款资格的规则", Strictness.MEDIUM).blocked()).isFalse();
        }

        @Test
        void asterLangKeyword() {
            assertThat(filter.check("Module finance.loan. Define LoanApp has amount.", Strictness.MEDIUM)
                .blocked()).isFalse();
        }

        @Test
        void complianceCheck() {
            assertThat(filter.check("validate compliance for KYC", Strictness.MEDIUM).blocked()).isFalse();
        }

        @Test
        void approvalChinese() {
            assertThat(filter.check("评估贷款审批流程", Strictness.MEDIUM).blocked()).isFalse();
        }
    }

    @Nested
    @DisplayName("MEDIUM — 既不偏题也不命中白名单（应拒绝）")
    class OffTopicNoKeywords {

        @Test
        void randomNonPolicyText() {
            SafetyVerdict v = filter.check("What's the weather like today?", Strictness.MEDIUM);
            assertThat(v.blocked()).isTrue();
            assertThat(v.ruleId()).isEqualTo("off-topic-no-keywords");
        }

        @Test
        void mathQuestion() {
            assertThat(filter.check("What is 2 + 2?", Strictness.MEDIUM).blocked()).isTrue();
        }
    }

    @Nested
    @DisplayName("LENIENT — Complete 路径仅长度限制")
    class Lenient {

        @Test
        void asterLangPrefixAllowed() {
            assertThat(filter.check("Module finance.loan.\nRule check given app, produce:\n  ",
                Strictness.LENIENT).blocked()).isFalse();
        }

        @Test
        void evenWeirdPrefixAllowed() {
            // Complete 输入是机器拼接的 aster-lang 片段，不强制话题白名单
            assertThat(filter.check("xyz abc", Strictness.LENIENT).blocked()).isFalse();
        }

        @Test
        void overLengthBlocked() {
            String tooLong = "a".repeat(8 * 1024 + 1);
            SafetyVerdict v = filter.check(tooLong, Strictness.LENIENT);
            assertThat(v.blocked()).isTrue();
            assertThat(v.ruleId()).isEqualTo("too-long");
        }

        @Test
        @DisplayName("★黑名单在 LENIENT 下同样生效（否则端点=免费 LLM）")
        void denylistStillAppliesUnderLenient() {
            // 修复前 LENIENT 在黑名单之前就 return allow()，这些明显越权的请求
            // 会被放行——与该段"不分严格度都跑"的注释自相矛盾。
            for (String abuse : new String[]{
                "写一首诗",
                "讲个笑话",
                "翻译成英文",
                "写一段 Python 代码",
                "write a novel about pirates",
                "translate into Japanese"
            }) {
                SafetyVerdict v = filter.check(abuse, Strictness.LENIENT);
                assertThat(v.blocked())
                    .as("LENIENT 应拦截越权请求: %s", abuse)
                    .isTrue();
                assertThat(v.ruleId()).isEqualTo("off-topic-denylist");
            }
        }

        @Test
        @DisplayName("★文档问答类问句放行（助手的核心用例）")
        void docsQuestionsAllowed() {
            // 这些是最该被回答的入门问题，却一个 policy 白名单词都不含。
            // MEDIUM 会判 off-topic 拒掉；LENIENT 必须放行。
            for (String q : new String[]{
                "我能用这个网址干什么？",
                "如何开始第一个策略的编写",
                "安全监控",
                "怎么导出报告"
            }) {
                assertThat(filter.check(q, Strictness.LENIENT).blocked())
                    .as("助手应能回答: %s", q)
                    .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("边界")
    class Edges {

        @Test
        void nullPrompt() {
            SafetyVerdict v = filter.check(null, Strictness.MEDIUM);
            assertThat(v.blocked()).isTrue();
            assertThat(v.ruleId()).isEqualTo("empty-prompt");
        }

        @Test
        void emptyPromptMedium() {
            // 空字符串视为既无白名单关键词也未触发其他守卫，按 off-topic-no-keywords 拒绝
            SafetyVerdict v = filter.check("", Strictness.MEDIUM);
            assertThat(v.blocked()).isTrue();
        }
    }
}
