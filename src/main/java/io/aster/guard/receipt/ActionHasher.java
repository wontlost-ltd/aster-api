package io.aster.guard.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import io.aster.guard.GuardInvalidActionException;
import jakarta.inject.Singleton;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * {@code actionHash}（ADR 0040 §12 D2）：请求体 {@code action} 子树的 {@code jcs-sha256/v1}，
 * 即 RFC 8785 规范化 UTF-8 字节串的 SHA-256 小写 hex。
 *
 * <p>输入必须是服务端收到的原文 JSON 树（含调用方写出的显式 null 与未知键），不能是请求 record 再序列化：
 * record 会补出 {@code "type":null} 等键，与执行方自算的哈希不一致。重复键须在解析阶段拒绝
 * （{@code STRICT_DUPLICATE_DETECTION}），不进入这里。
 */
@Singleton
public final class ActionHasher {

  /**
   * @throws GuardInvalidActionException {@code action} 缺失 / 非对象，或含非有限数、超出 ±(2^53−1) 的整数
   */
  public String hash(JsonNode action) {
    if (action == null || !action.isObject()) {
      throw new GuardInvalidActionException("action 必须是 JSON 对象");
    }
    try {
      return DigestUtils.sha256Hex(JcsCanonicalizer.canonicalize(action));
    } catch (IllegalArgumentException e) {
      throw new GuardInvalidActionException("action 无法规范化: " + e.getMessage());
    }
  }
}
