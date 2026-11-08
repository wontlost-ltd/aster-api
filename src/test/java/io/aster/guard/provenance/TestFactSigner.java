package io.aster.guard.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.receipt.JcsCanonicalizer;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * 测试用签名事实签发器（ADR 0040 §11 D3）：每个实例持有一把随机 Ed25519 密钥，按与生产相同的载荷配方签名——
 * 载荷为事实中除 {@code signature} 外的全部成员，经 RFC 8785（JCS）规范化后的 UTF-8 字节串，签名不二次哈希。
 *
 * <p>链式设置各字段后 {@link #sign()} 得到可直接放进 {@code action.facts[]} 的对象；{@link #unsigned()} 只放占位签名，
 * 供「签名之前就该被拒绝」的用例（如 claims 内的不安全整数，载荷本身无法规范化）使用。
 */
public final class TestFactSigner {
  public static final String ISSUER = "idp.example.com";
  public static final String KID = "2026-11";
  private static final ObjectMapper JSON = new ObjectMapper();
  /** 64 字节全零的占位签名（base64url 无填充）。 */
  private static final String PLACEHOLDER_SIGNATURE = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);

  private final KeyPair keys;
  private final ObjectNode fact = JSON.createObjectNode();

  private TestFactSigner(KeyPair keys) {
    this.keys = keys;
  }

  /** 新密钥；字段取 {@link #ISSUER} / {@link #KID} / Ed25519，其余须由调用方设置。 */
  public static TestFactSigner newKey() {
    try {
      return new TestFactSigner(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
          .issuer(ISSUER).kid(KID).alg("Ed25519");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 同一把密钥、字段从头设置（用于同一签发方签多条事实）。 */
  public TestFactSigner fresh() {
    return new TestFactSigner(keys).issuer(fact.path("issuer").asText(ISSUER)).kid(fact.path("kid").asText(KID))
        .alg("Ed25519");
  }

  /** 签发方表里登记的公钥：base64url（无填充）的 32 字节原始公钥（X.509 编码的末 32 字节）。 */
  public String publicKey() {
    byte[] spki = keys.getPublic().getEncoded();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
  }

  public TestFactSigner issuer(String v) {
    fact.put("issuer", v);
    return this;
  }

  public TestFactSigner kid(String v) {
    fact.put("kid", v);
    return this;
  }

  public TestFactSigner alg(String v) {
    fact.put("alg", v);
    return this;
  }

  public TestFactSigner audience(String v) {
    fact.put("audience", v);
    return this;
  }

  public TestFactSigner window(Instant issuedAt, Instant expiresAt) {
    fact.put("issuedAt", issuedAt.toString());
    fact.put("expiresAt", expiresAt.toString());
    return this;
  }

  /** claims 以 JSON 原文给出，保留调用方写出的数字形式（如 {@code 2.5E5}）。 */
  public TestFactSigner claims(String json) {
    fact.set("claims", parse(json));
    return this;
  }

  public TestFactSigner sources(String json) {
    fact.set("sources", parse(json));
    return this;
  }

  /** 载荷的 JCS 文本：事实中除 {@code signature} 外的全部成员。 */
  public String payloadText() {
    ObjectNode payload = fact.deepCopy();
    payload.remove("signature");
    return new String(JcsCanonicalizer.canonicalize(payload), StandardCharsets.UTF_8);
  }

  /** 对 {@link #payloadText()} 的字节签名，返回带 {@code signature} 的新对象（本签发器状态不变）。 */
  public ObjectNode sign() {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keys.getPrivate());
      signer.update(payloadText().getBytes(StandardCharsets.UTF_8));
      ObjectNode signed = fact.deepCopy();
      signed.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
      return signed;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 带占位签名的对象：载荷无法规范化或签名前就应被拒绝的用例。 */
  public ObjectNode unsigned() {
    ObjectNode copy = fact.deepCopy();
    copy.put("signature", PLACEHOLDER_SIGNATURE);
    return copy;
  }

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException(json, e);
    }
  }
}
