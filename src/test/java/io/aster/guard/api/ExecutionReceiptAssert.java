package io.aster.guard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.receipt.JcsCanonicalizer;
import io.aster.guard.receipt.ReceiptSigningKeys;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端测试里以执行方视角复验执行收据：只用响应原文与测试配置里的密钥，不经服务端的签名器或 {@code /keys}。
 */
final class ExecutionReceiptAssert {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ExecutionReceiptAssert() {
  }

  static JsonNode tree(String json) {
    try {
      return JSON.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 执行方自算：请求体原文中 {@code action} 子树的 jcs-sha256/v1。 */
  static String actionHashOf(String requestBody) {
    return DigestUtils.sha256Hex(JcsCanonicalizer.canonicalize(tree(requestBody).get("action")));
  }

  /**
   * 响应里 {@code executionReceipt} 的签名作用于其 {@code payload} 原文的 JCS 字节串，且由配置中同 kid 的公钥验证通过。
   *
   * @return 收据节点
   */
  static JsonNode assertVerifiable(String responseBody, String signingKeysJson) {
    JsonNode receipt = tree(responseBody).get("executionReceipt");
    assertThat(receipt).as("executionReceipt").isNotNull();
    String kid = receipt.get("kid").asText();
    assertThat(receipt.get("payload").get("kid").asText()).isEqualTo(kid);
    PublicKey key = ReceiptSigningKeys.parse(signingKeysJson, kid, Clock.systemUTC()).find(kid).orElseThrow()
        .keyPair().getPublic();
    byte[] signature = Base64.getUrlDecoder().decode(receipt.get("signature").asText());
    assertThat(signature).hasSize(64);
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(JcsCanonicalizer.canonicalize(receipt.get("payload")));
      assertThat(verifier.verify(signature)).as("Ed25519 验签").isTrue();
    } catch (GeneralSecurityException e) {
      throw new AssertionError(e);
    }
    return receipt;
  }
}
