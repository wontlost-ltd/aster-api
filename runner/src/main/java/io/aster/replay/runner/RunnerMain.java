package io.aster.replay.runner;

import aster.truffle.trace.TraceAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.api.model.DecisionTrace;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.replay.ReplayMetadata;
import io.aster.replay.core.ExecutionPhaseResult;
import io.aster.replay.core.ReplayExecutionCore;
import io.aster.replay.core.ReplayExecutionRequest;

import java.io.InputStream;
import java.io.PrintStream;

/**
 * standalone β runner 入口。读 stdin JSON（schema ②）→ :replay 三阶段 →
 * 向 stdout 输出结果 envelope（最后一行完整 JSON），前置日志走 stderr；
 * 成功 exit 0 / 错误 exit≠0。★错误独立 envelope 不进 ReplayMetadata。
 */
public final class RunnerMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private RunnerMain() {}

    public static void main(String[] args) {
        int code = run(System.in, System.out);
        System.exit(code);
    }

    /** 便于测试的入口：不调 System.exit，返回 exit code。 */
    public static int run(InputStream in, PrintStream out) {
        RunnerEnvelope envelope;
        try {
            // ★进程级 trace PE gate（镜像 aster-api TruffleTraceInitializer.onStart）：
            //   TraceAccess.ENABLED 默认 false（生产关），不开则 armCurrentThread 后引擎仍不采集
            //   步骤 → drain 出 steps=[] → canonicalTrace/traceHash 与 aster-api（replayCapture 路径
            //   已 setEnabled(true)）分叉。runner 的职责就是复现生产 replayCapture 路径含 traceHash，
            //   故必须在执行前打开同一 gate。Task 0 parity 校准实证：不开则每个 fixture traceHash 全分叉。
            TraceAccess.setEnabled(true);
            LocaleAssertion.assertAllPresent();   // ★启动 fail-closed：缺 locale 立即失败
            RunnerRequest req = MAPPER.readValue(in, RunnerRequest.class);
            envelope = execute(req);
        } catch (Exception e) {
            // 顶层兜底：请求解析或未预期异常 → INTERNAL 错误 envelope
            envelope = RunnerEnvelope.error("INTERNAL", String.valueOf(e.getMessage()), "parse");
        }
        try {
            out.println(MAPPER.writeValueAsString(envelope));   // envelope = stdout 最后一行
        } catch (Exception e) {
            System.err.println("failed to serialize envelope: " + e);
            return 3;
        }
        return "SUCCESS".equals(envelope.outcome()) ? 0 : 1;
    }

    /**
     * 三阶段执行。参考 aster-api {@code PolicyEvaluationResource:522-603} 的参考序列，
     * 但用非 CDI {@link StandaloneReplayExecutor} + {@link RunnerToolchainId}（无注入）。
     * 阶段一 {@link ReplayExecutionCore#execute} 会把 executor 异常原样透传（见该类注释），
     * 由本方法 catch → {@link #mapError} 按 phase 映射错误 envelope。
     */
    private static RunnerEnvelope execute(RunnerRequest req) {
        ReplayExecutionCore core = new ReplayExecutionCore();
        StandaloneReplayExecutor executor = new StandaloneReplayExecutor();
        try {
            ReplayExecutionRequest coreReq = toCoreRequest(req);
            ExecutionPhaseResult phase = core.execute(coreReq, executor);
            // ★captureTrace=true：生产 replayCapture 路径用 (trace || effectiveReplayCapture)=true
            //   （PolicyEvaluationResource:576），traceHash 非 null。runner parity 必须同 true，
            //   否则 traceHash 分叉必挂（Codex 抓的真陷阱）。
            DecisionTrace trace = core.buildDecisionTrace(
                phase.execResult(), phase.traceDrainResult(), /* captureTrace */ true);
            ReplayMetadata rm = core.computeReplayMetadata(
                RunnerToolchainId.current(), /* context */ req.input(),
                phase.execResult(), trace, phase.traceDrainResult());
            return RunnerEnvelope.success(rm);
        } catch (Exception e) {
            return mapError(e);
        }
    }

    /**
     * RunnerRequest → ReplayExecutionRequest（11 字段，见 {@link ReplayExecutionRequest}）。
     * ★effectiveReplayCapture=true + trace=true：runner 的职责就是复现生产 replayCapture 路径，
     *   须与生产捕获的 ReplayMetadata 对齐（含 traceHash）。aliasesTrusted=false（runner 无 HMAC
     *   上下文，MVP 无签名——别名不受信，与 parity corpus 的 import-free/无别名子集一致）。
     *   legacyEvaluateSentinel=false。vocabulary=null（raw，core 内建 index，null 合法退化）。
     */
    private static ReplayExecutionRequest toCoreRequest(RunnerRequest req) {
        return new ReplayExecutionRequest(
            req.tenantId(),
            req.source(),
            /* context */ req.input(),
            req.functionName(),
            req.locale(),
            /* vocabulary */ null,
            req.aliasSet(),
            /* legacyEvaluateSentinel */ false,
            /* aliasesTrusted */ false,
            /* trace */ true,
            /* effectiveReplayCapture */ true);
    }

    /**
     * 异常 → 错误 envelope。
     *
     * <p>分类逻辑已抽到 {@link io.aster.replay.core.ErrorClassification}——runner 与
     * aster-api（{@code PolicyEvaluationResource} 的 catch 链）共用同一份，消除此前
     * "两处手写 + 注释里引用对方行号"的漂移风险（issue #173）。那套注释引用早已失效：
     * 行号变了，且两侧分类其实已经不同（API 单独处理 AmbiguousEntryException，
     * runner 把它并进了 EXECUTION）。
     *
     * <p>本方法只负责把分类结果转成 runner 的 envelope 形状；HTTP 语义留在 API 侧。
     */
    private static RunnerEnvelope mapError(Exception e) {
        var classified = io.aster.replay.core.ErrorClassification.classify(e);
        String phase = classified.kind() == io.aster.replay.core.ErrorClassification.Kind.PARSE
            ? "parse"
            : "execute";
        return RunnerEnvelope.error(classified.kind().name(), classified.message(), phase);
    }
}
