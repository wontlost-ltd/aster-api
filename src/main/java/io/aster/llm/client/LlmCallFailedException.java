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

    public LlmCallFailedException(String message, boolean reachedProvider, Throwable cause) {
        super(message, cause);
        this.reachedProvider = reachedProvider;
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
