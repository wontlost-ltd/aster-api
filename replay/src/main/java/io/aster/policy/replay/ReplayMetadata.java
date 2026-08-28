package io.aster.policy.replay;

import aster.core.canonical.CanonicalJson;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.aster.policy.api.model.DecisionTrace;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回放元数据（ADR 0030 附录 A）——evaluate replay-capture 模式产出的回放地基字段。
 *
 * <p>★权威 hash 由 aster-api（Java 评估侧）计算（Codex 设计 #4）：cloud BFF 有请求包装/
 * 序列化差异，只有 Java 侧 hash 才权威。cloud 只<b>存储</b>本 hash，不覆盖。
 *
 * <p><b>cloud 侧重算的边界（Codex 复审 #2，诚实标注）：</b>{@link CanonicalJson} 与 TS
 * canonical-json.ts 的 {@code canonicalDecimal} 算法有 parity gate（本会话已上线）——但那只保证
 * 「<b>string-on-decimal-path 形态的相同输入</b>」两侧字节一致。本类的 {@link #liftDecimals}
 * 是<b>Java 捕获期的额外变换</b>：把裸 JS number 小数（如 {@code 100.5}）提成 string-on-decimal-path
 * 形态——TS 的 {@code canonicalHash} 对裸 number 小数是<b>抛错不 lift</b>的。因此 cloud 若要
 * 重算做 drift 校验，<b>必须先施加与本 lift 完全相同的变换</b>（[n]→[] 路径归一 + 非整数 number
 * 提 {@code BigDecimal.toString} + decimalTypeContext），否则得不到相同 hash。M1 <b>不</b>声称
 * cloud 可直接对原始输入重算；cloud 重算是未来项，须先落 TS 侧同款 lift + parity fixture。
 *
 * <p><b>canonicalInputHash 锚点语义（Codex 复审 #2，诚实标注）：</b>M1 锚在<b>请求级 context</b>
 * （调用方发来的原始 {@code request.context()}），<b>非</b>引擎归一后的 positional 参数。
 * 理由：回归工具的主用例是「拿同一请求重放到新引擎版本，比对输出是否漂移」——请求级输入
 * 天然可复现（同 source + 同 context → 确定性归一）。代价：命名格式 {@code {creditScore:680}}
 * 与位置格式 {@code [680]} 表述不同的<b>同一逻辑输入</b>会算出不同 inputHash（聚合能力下降，
 * 但不误报漂移——不同 hash 只是不聚合，不会把相同判定误标为不同）。若未来需要 runtime-level
 * 聚合，再加 canonicalRuntimeInputHash（锚归一后 positional），两者并存不互斥。
 *
 * <p><b>Decimal 处理（Codex 复审 P0）：</b>{@link CanonicalJson} 的铁律是「非 Decimal number
 * 只允许 safe integer，小数须以 string 承载 + typeCtx」（跨引擎浮点表示不一致）。金融/保险
 * 金额天然高频小数（{@code 100.50}），若直接 {@code valueToTree} 后 canonicalHash 会抛
 * {@code NON_INTEGER_NUMBER}。因此 hash 前先 {@link #liftDecimals} 把树里每个非整数 number
 * 提成精确字符串（{@code BigDecimal.toString}，保留 E 指数形式以让 canonicalDecimal 的展开
 * 上限先于展开生效）并把其路径标为 decimal path，交给 canonicalHash 的 string-on-decimal-path
 * 路径。<b>数组下标路径须经 {@code TypeContext.of} 的 [n]→[] 归一</b>才能命中（见 tryHash）。
 *
 * <p><b>fail-loud（Codex 复审 P0）：</b>显式 {@code replayCapture=true} 若无法产出完整回放
 * 地基（某字段 hash 失败），<b>不静默丢</b>——{@link #replayabilityStatus} 置 {@code NON_REPLAYABLE}
 * 并在 {@link #replayabilityReasons} 记具体原因，调用方（cloud BFF）据此写 Execution 的
 * replayabilityStatus 列。伪成功（返回无 metadata 的 200）会让调用方误以为拿到了地基。
 *
 * <p>reasonCodes M1 为空 {@code []}（Codex #5）：引擎不产结构化 reason；业务返回字段 reasonCode
 * 是 CNL 业务字段非引擎 reason，不自动抽取。reasonCodes 非回放必需字段。
 */
/*
 * ★字段序显式钉成字母序——与 EvaluationResponse 同因：
 *   Quarkus 3.38 → 3.39 后 record 序列化由字母序变为声明序，
 *   使 PolicyEvaluationReplayOrderingTest 的字节级 golden 断言失败。
 *   本类作为 replayMetadata 嵌套在响应体内，同样需要显式钉死。
 */
@JsonPropertyOrder(alphabetic = true)
public record ReplayMetadata(
    String runtimeToolchainId,
    String canonicalizationVersion,
    String canonicalInputHash,
    String canonicalOutputHash,
    String traceHash,
    List<Object> reasonCodes,
    String replayabilityStatus,
    List<String> replayabilityReasons,
    // M2 回放 payload：post-lift canonical **字符串**（被 hash 的那个串）。null=本次未提供/hash 失败。
    // cloud 收到后直接 sha256(version+"\n"+串) 校验即等于对应 hash，无需 re-canonicalize
    // （绕开 liftDecimals 的 TS↔Java parity gap，见 docs/m2-replay-payload-contract.md）。
    // ★@JsonInclude(NON_NULL) **只标这 3 个新字段**（Codex 审查）：null 时省略键，M1 消费者不受影响；
    // 但**不动**既有 hash 字段的序列化形状（它们 null 仍序列化为 null，与本改动前一致，避免兼容漂移）。
    @JsonInclude(JsonInclude.Include.NON_NULL) String canonicalInput,
    @JsonInclude(JsonInclude.Include.NON_NULL) String canonicalOutput,
    @JsonInclude(JsonInclude.Include.NON_NULL) String canonicalTrace) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** canonical 算法版本（与 CanonicalJson.CANONICALIZATION_VERSION 一致）。 */
    public static final String CANONICALIZATION_VERSION = CanonicalJson.CANONICALIZATION_VERSION;

    /** 回放完整：input/output/trace hash 全部按需产出。 */
    public static final String STATUS_REPLAYABLE = "REPLAYABLE";
    /** 回放地基不完整：至少一个必需 hash 无法产出（显式 capture 下仍返回，但标记原因）。 */
    public static final String STATUS_NON_REPLAYABLE = "NON_REPLAYABLE";

    /**
     * 计算回放元数据。<b>不因 Decimal/可归一化值抛异常</b>——小数走 string-lift；只有真正
     * 无法 canonical 化的值（非 finite / 不支持的节点类型 / 超限 Decimal）才落 NON_REPLAYABLE。
     *
     * <p>output hash 是回放地基必需字段（证明「实际产出」）：若 output hash 失败即
     * NON_REPLAYABLE。input/trace 失败也记原因并置 NON_REPLAYABLE（回放需要能复现输入）；
     * 但 input==null / trace==null 是「本次未提供」而非「失败」，不降级（对应字段 hash 为 null）。
     *
     * @param runtimeToolchainId 运行时工具链身份（ToolchainIdentityProvider.currentToolchainId()）
     * @param input              请求级 context（调用方原始 request.context()，见类 doc 锚点语义）
     * @param result             业务结果（EvaluationResponse.result，仅业务输出）
     * @param trace              决策 trace（replay-capture 模式必产；null 则 traceHash=null）
     */
    public static ReplayMetadata compute(String runtimeToolchainId, Object input, Object result, DecisionTrace trace) {
        return compute(runtimeToolchainId, input, result, trace, true);
    }

    /**
     * 计算回放元数据，并显式接收执行 trace 自身的可回放性。truffle drain 若发现 async 污染、
     * 截断或值归一降级，会给出 {@code traceReplayable=false}；此时不能基于空 steps 计算一个
     * 看似正常的 traceHash，必须返回 NON_REPLAYABLE 并说明原因。
     */
    public static ReplayMetadata compute(
            String runtimeToolchainId,
            Object input,
            Object result,
            DecisionTrace trace,
            boolean traceReplayable) {
        List<String> reasons = new ArrayList<>();

        // M2：同时拿 canonical 串 + hash（一次序列化）。null=未提供/失败。串仅在 replayCapture 路径经
        // withReplayMetadata 进响应，且响应级 HMAC 门控——非授权调用方拿不到 payload（PolicyEvaluationResource）。
        CanonicalJson.CanonicalPair inputPair = null;
        if (input != null) {
            inputPair = tryCanonical(MAPPER.valueToTree(input), "input", reasons);
        }

        // output 必需：result 可能为 null（规则返回 null/void）——那也是确定性输出，序列化之。
        CanonicalJson.CanonicalPair outputPair = tryCanonical(MAPPER.valueToTree(result), "output", reasons);

        CanonicalJson.CanonicalPair tracePair = null;
        if (trace != null) {
            // M2.1b：drain 判定不可回放（async 污染/截断/归一降级）时**不**算 traceHash，
            // 避免对空/降级 steps 生成伪可回放 hash——标 reason 并让下方 replayable 判定落 false。
            if (traceReplayable) {
                tracePair = tryCanonical(stableTraceNode(trace), "trace", reasons);
            } else {
                reasons.add("trace_not_replayable");
            }
        }

        String inputHash = inputPair != null ? inputPair.hash() : null;
        String outputHash = outputPair != null ? outputPair.hash() : null;
        String traceHash = tracePair != null ? tracePair.hash() : null;

        // 回放完整判定：output 必须成功；input/trace 若提供了也必须成功。
        boolean replayable = outputHash != null
            && (input == null || inputHash != null)
            && (trace == null || (traceReplayable && traceHash != null));

        return new ReplayMetadata(
            runtimeToolchainId,
            CANONICALIZATION_VERSION,
            inputHash,
            outputHash,
            traceHash,
            List.of(),
            replayable ? STATUS_REPLAYABLE : STATUS_NON_REPLAYABLE,
            List.copyOf(reasons),
            inputPair != null ? inputPair.canonical() : null,
            outputPair != null ? outputPair.canonical() : null,
            tracePair != null ? tracePair.canonical() : null);
    }

    /**
     * 对一个 JsonNode 同时算 canonical **串 + hash**（M2：一次 lift + 一次序列化）：先 lift 小数为
     * string + 收集 decimal paths，再交 canonicalWithHash（typeCtx）。失败（非 finite / 超限 Decimal /
     * 不支持类型）返回 null 并记原因，<b>不抛</b>（fail-loud 由上层据返回值判定，不靠异常穿透）。
     */
    private static CanonicalJson.CanonicalPair tryCanonical(JsonNode raw, String field, List<String> reasons) {
        try {
            Set<String> decimalPaths = new java.util.HashSet<>();
            JsonNode lifted = liftDecimals(raw, "", decimalPaths);
            // ★必须用 TypeContext.of(...)（Codex 复审 P0#1）：它对 array 下标做 [n]→[]
            // 归一，与 canonicalHash 遍历时 isDecimalPath 的归一口径一致。若用裸 new
            // TypeContext(concretePaths)，数组内 decimal path（如 items[0].price）永远命中
            // 不了归一后的 items[].price → lift 出的 string 被当普通 JSON string hash，导致
            // Decimal 1.5 与 string "1.5" 碰撞（回放地基级正确性错误，已实证）。
            CanonicalJson.TypeContext ctx = decimalPaths.isEmpty()
                ? CanonicalJson.TypeContext.empty()
                : CanonicalJson.TypeContext.of(decimalPaths.toArray(new String[0]));
            return CanonicalJson.canonicalWithHash(lifted, ctx);
        } catch (RuntimeException e) {
            reasons.add(field + "_hash_failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 递归复制 JsonNode，把每个<b>非整数</b> number（含超 safe-integer 的整数）提成精确
     * {@code BigDecimal.toString()} 字符串（保留 E 指数形式），并把其 canonical path 收进
     * {@code decimalPaths}。
     * 路径格式与 {@link CanonicalJson} 内部一致（root="" / obj: {@code p.isEmpty()?k:p+"."+k} /
     * arr: {@code p+"["+i+"]"}）。整数 number / string / bool / null 原样保留。
     *
     * <p>为何不直接把所有 number 都 string 化：safe-integer 走 number 路径与 TS 一致（整数不需
     * string 承载），只有小数/超范围整数才有跨引擎表示分歧，须 string-lift。
     */
    private static JsonNode liftDecimals(JsonNode node, String path, Set<String> decimalPaths) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return node == null ? null : node;
        }
        switch (node.getNodeType()) {
            case NUMBER -> {
                if (isCanonicalSafeInteger(node)) {
                    return node; // 安全整数：保留 number 形态（与 TS number 路径一致）。
                }
                // 非整数或超 safe-integer：提成精确字符串，标 decimal path。
                // ★用 toString()（保留 E 指数形式）而非 toPlainString()（Codex 复审 P0#3）：
                // toPlainString() 会先把 1E+1000000 展开成百万位字符串，绕过 canonicalDecimal
                // 的 MAX_DECIMAL_EXPONENT / 展开位数上限（DoS 防护在展开前才生效）。toString()
                // 保留紧凑指数形式，交 canonicalDecimal 自己按指数长度先拒，再决定是否展开。
                decimalPaths.add(path);
                return new TextNode(node.decimalValue().toString());
            }
            case ARRAY -> {
                ArrayNode arr = MAPPER.createArrayNode();
                for (int i = 0; i < node.size(); i++) {
                    arr.add(liftDecimals(node.get(i), path + "[" + i + "]", decimalPaths));
                }
                return arr;
            }
            case OBJECT -> {
                ObjectNode obj = MAPPER.createObjectNode();
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    String childPath = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                    obj.set(e.getKey(), liftDecimals(e.getValue(), childPath, decimalPaths));
                }
                return obj;
            }
            default -> {
                // STRING / BOOLEAN / 其它——原样（不支持类型交由 canonicalHash 拒绝）。
                return node;
            }
        }
    }

    /** number 是否为可直接走 number 路径的安全整数（|n|≤2^53−1 且无小数）。 */
    private static boolean isCanonicalSafeInteger(JsonNode node) {
        if (node.isFloatingPointNumber()) {
            double d = node.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return false; // 非 finite：交给 canonicalHash 明确拒绝并记原因。
            }
            java.math.BigDecimal bd = node.decimalValue();
            if (bd.stripTrailingZeros().scale() > 0) {
                return false; // 有小数。
            }
            return withinSafeInteger(bd.toBigIntegerExact());
        }
        return withinSafeInteger(node.bigIntegerValue());
    }

    private static boolean withinSafeInteger(java.math.BigInteger bi) {
        // 2^53 − 1 = 9007199254740991（JS Number.MAX_SAFE_INTEGER，与 CanonicalJson 一致）。
        return bi.abs().compareTo(java.math.BigInteger.valueOf(9007199254740991L)) <= 0;
    }

    /**
     * StableDecisionTrace JsonNode（剔除非决定性 executionTimeMs，Codex #3）——traceHash 输入。
     * 保留 moduleName/functionName/steps/finalResult；不含时间。
     *
     * <p><b>M2.1b traceHash 是步骤级 trace 锚点：</b>{@code steps} 由 truffle 同线程 drain
     * 填充；若 drain 判定不可回放，调用方必须通过 compute 的 traceReplayable=false 重载让本类
     * 跳过 traceHash 并标 NON_REPLAYABLE，避免对空 steps 生成伪可回放 hash。
     */
    static JsonNode stableTraceNode(DecisionTrace trace) {
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("moduleName", trace.moduleName());
        stable.put("functionName", trace.functionName());
        stable.put("steps", trace.steps());
        stable.put("finalResult", trace.finalResult());
        return MAPPER.valueToTree(stable);
    }
}
