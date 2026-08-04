package io.aster.llm.safety;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 话题白名单守门：只让"aster-lang policy 相关"的 prompt 通过
 *
 * 三档严格度：
 *   STRICT  — 必须命中 aster-lang 关键字（Module/Rule/Define/given/produce 等）；误伤合法纯自然语言用户
 *   MEDIUM  — 命中 policy 领域词（policy/rule/规则/策略/合规/审批/Module/Rule…）+ 黑名单非 policy 关键词不得命中（推荐）
 *   LENIENT — 仅长度限制（complete 路径用，因为输入是 aster-lang 片段，话题守门误伤过多）
 *
 * Generate / Explain → MEDIUM
 * Complete           → LENIENT
 */
@ApplicationScoped
public class PromptScopeFilter {

    public enum Strictness { STRICT, MEDIUM, LENIENT }

    /** 用户输入硬上限 8KB —— 防止用 prompt 撑爆 context window 后塞越狱指令 */
    private static final int MAX_LENGTH = 8 * 1024;

    /**
     * 黑名单：明显不是 policy 的请求 → 直接拒绝
     */
    private static final List<Pattern> OFF_TOPIC_DENY = List.of(
        Pattern.compile("\\b(write|generate|compose)\\s+(a\\s+)?(novel|story|poem|song|joke|essay|article|fiction)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(translate|translation)\\s+(to|into|from)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(write|generate)\\s+(some\\s+|a\\s+)?(python|javascript|java|typescript|golang|c\\+\\+|c#|ruby|php|rust)\\s+code\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bexplain\\s+(this\\s+)?(python|javascript|java|typescript|golang|c\\+\\+|ruby)\\s+code\\b", Pattern.CASE_INSENSITIVE),
        // ★量词允许多个：原来是 (一|首|个)? 只许**一个**，导致「写一首诗」
        //   「写一个故事」「写一篇文章」这些最自然的说法全部漏过（只能拦住
        //   「写诗」「写首诗」这种简写）。改成量词序列 + 补全量词字表。
        Pattern.compile("写[一二三两]?[首个篇段部本]?(小说|诗|诗歌|歌|笑话|文章|故事|散文|作文)"),
        Pattern.compile("(翻译|翻成)(成|为)?(中文|英文|英语|日文|日语|德文|德语|法文|法语)"),
        Pattern.compile("(讲|说)[一二两]?[个则段]?(笑话|故事|段子)"),
        // ★量词与语言名之间允许空格：「写一段 Python 代码」这种最常见的中英混排
        //   写法原来漏过（只能拦住无空格的「写一段Python代码」）。
        Pattern.compile("(写|生成)\\s*(一段|一些|一个|几行)?\\s*(Python|JavaScript|Java|TypeScript|Go|C\\+\\+|Ruby|PHP|Rust)\\s*代码", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 白名单：必须命中至少一个 policy 领域关键词
     * 覆盖中英文 + aster-lang 语法关键字
     */
    private static final List<Pattern> ON_TOPIC_ALLOW = List.of(
        // aster-lang 语法关键字（直接出现 = 强信号）
        Pattern.compile("\\b(Module|Rule|Define|given|produce|Match|When|Otherwise|Return)\\b"),
        // 英文 policy 领域词
        Pattern.compile("\\b(policy|policies|rule|rules|compliance|approval|approve|reject|eligibility|access\\s+control|authorize|authorization|loan|discount|risk|fraud|kyc|pii|gdpr|hipaa|sox)\\b", Pattern.CASE_INSENSITIVE),
        // 中文 policy 领域词
        Pattern.compile("(规则|策略|政策|合规|审批|批准|拒绝|准入|授权|鉴权|风控|信贷|贷款|折扣|资格|身份|实名)"),
        // 业务判定动词（中英）
        Pattern.compile("\\b(check|validate|verify|determine|decide|evaluate|judge|assess)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(判断|校验|验证|评估|判定|决策|检查)")
    );

    public SafetyVerdict check(String prompt, Strictness strictness) {
        if (prompt == null) {
            return SafetyVerdict.block("empty-prompt", "请求内容为空");
        }
        if (prompt.length() > MAX_LENGTH) {
            return SafetyVerdict.block(
                "too-long",
                "输入超过最大长度（" + MAX_LENGTH + " 字节）"
            );
        }
        // 黑名单：**所有严格度都跑**（含 LENIENT）。
        //
        // ★此前 LENIENT 在这之前就 return allow()，与本段"不分严格度都跑"的注释
        //   自相矛盾——写小说/翻译/写 Python 代码这类明显越权的请求，在 LENIENT
        //   路径上是放行的，等于把端点当免费 LLM 用。注释声称的行为才是本意，
        //   故把早退移到黑名单之后。
        for (Pattern p : OFF_TOPIC_DENY) {
            if (p.matcher(prompt).find()) {
                return SafetyVerdict.block(
                    "off-topic-denylist",
                    "请求与 aster-lang policy 无关，已拒绝"
                );
            }
        }

        // LENIENT 只豁免"必须命中领域白名单"这一条：
        // complete 是补全用户已在写的内容、assistant 是文档问答，
        // 都不该要求问句本身含 policy 关键词。
        if (strictness == Strictness.LENIENT) {
            return SafetyVerdict.allow();
        }

        // 白名单：MEDIUM 与 STRICT 都要求命中
        boolean onTopic = false;
        for (Pattern p : ON_TOPIC_ALLOW) {
            if (p.matcher(prompt).find()) {
                onTopic = true;
                break;
            }
        }
        if (!onTopic) {
            return SafetyVerdict.block(
                "off-topic-no-keywords",
                "请求未识别为 policy 相关，请描述具体的策略 / 规则 / 合规需求"
            );
        }
        return SafetyVerdict.allow();
    }
}
