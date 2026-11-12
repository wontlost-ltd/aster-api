package io.aster.policy.parser;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.atn.ATNConfig;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.atn.ParserATNSimulator;

import java.util.Set;

/**
 * 带时间预算的 ANTLR 预测器（aster-cloud/aster-lang-core#254 兜底）。
 *
 * <p>病态输入的耗时几乎全在一次 {@code adaptivePredict} 内部的 closure 递归里（栈顶为
 * {@code ParserATNSimulator.closure_}），期间既不消费 token 也不回到规则方法，所以只在 token 流或
 * parse listener 里检查截止时间拦不住；必须在 closure 递归本身检查。{@link #closureCheckingStopState}
 * 是 closure 的递归入口，每 {@link #CHECK_INTERVAL} 次调用读一次时钟，开销可忽略。
 *
 * <p>中途抛出不会污染与其它解析共享的 DFA 缓存：closure 只往本次调用的局部配置集里加东西，
 * DFA 边与状态在 reach 集算完之后才登记；{@code adaptivePredict} 的 finally 负责复位输入流。
 */
final class BudgetedParserATNSimulator extends ParserATNSimulator {

    /** 每多少次 closure 调用检查一次时钟（2 的幂，用位与取模）。 */
    private static final int CHECK_INTERVAL = 1 << 10;

    private final ParseBudget budget;
    private int calls;

    BudgetedParserATNSimulator(Parser parser, ParserATNSimulator base, ParseBudget budget) {
        super(parser, base.atn, base.decisionToDFA, base.getSharedContextCache());
        this.budget = budget;
        setPredictionMode(base.getPredictionMode());
    }

    @Override
    public int adaptivePredict(TokenStream input, int decision, ParserRuleContext outerContext) {
        budget.check();
        return super.adaptivePredict(input, decision, outerContext);
    }

    @Override
    protected void closureCheckingStopState(ATNConfig config, ATNConfigSet configs, Set<ATNConfig> closureBusy,
                                            boolean collectPredicates, boolean fullCtx, int depth,
                                            boolean treatEofAsEpsilon) {
        if ((++calls & (CHECK_INTERVAL - 1)) == 0) {
            budget.check();
        }
        super.closureCheckingStopState(config, configs, closureBusy, collectPredicates, fullCtx, depth,
            treatEofAsEpsilon);
    }
}
