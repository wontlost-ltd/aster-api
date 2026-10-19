package io.aster.llm.client;

/**
 * 非流式 LLM 调用失败，并标明请求是否已到达 provider（cloud#518 计费口径）。
 *
 * <p>规则与流式一致（cloud#470）：请求到达了第三方 AI 就计入配额。连不上 provider
 * （拒绝连接、域名解析失败、建连超时）算没到达；请求发出之后的失败（非 200、
 * 响应超时、响应解析失败）算已到达。
 */
public class LlmCallFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean reachedProvider;
    private final int statusCode;
    private final ProviderErrorClassifier.Classified providerError;

    public LlmCallFailedException(String message, boolean reachedProvider, Throwable cause) {
        this(message, reachedProvider, cause, 0);
    }

    /** statusCode：provider 的 HTTP 状态（0 = 无响应/连接失败）；供 service 区分「模型被拒」等情形。 */
    public LlmCallFailedException(String message, boolean reachedProvider, Throwable cause, int statusCode) {
        this(message, reachedProvider, cause, statusCode, null);
    }

    /** providerError：provider 非 2xx 时由 {@link ProviderErrorClassifier} 得出的分类；其它失败为 null。 */
    public LlmCallFailedException(String message, boolean reachedProvider, Throwable cause, int statusCode,
                                  ProviderErrorClassifier.Classified providerError) {
        super(message, cause);
        this.reachedProvider = reachedProvider;
        this.statusCode = statusCode;
        this.providerError = providerError;
    }

    public ProviderErrorClassifier.Classified providerError() {
        return providerError;
    }

    public int statusCode() {
        return statusCode;
    }

    /** 请求是否已到达 provider。 */
    public boolean reachedProvider() {
        return reachedProvider;
    }

    /**
     * 发送失败的原因是否属于「没连上 provider」。
     *
     * <p>沿 cause 链查找：Vert.x / Netty 常把底层异常包一层。
     */
    static boolean isConnectFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                || t instanceof java.net.UnknownHostException
                || t instanceof java.net.NoRouteToHostException
                || t instanceof io.netty.channel.ConnectTimeoutException) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }
}
