package io.aster.guard.api;

/**
 * 签发方登记请求体（ADR 0040 §11 D3）。{@code publicKey} 为 base64url 的 32 字节 Ed25519 原始公钥；
 * {@code enabled} 缺省为 true；{@code notAfter} 可选，ISO-8601 时刻；{@code alg} 可省略，给出时只能是 {@code Ed25519}。
 */
public record FactIssuerRequest(String alg, String publicKey, Boolean enabled, String notAfter) {
}
