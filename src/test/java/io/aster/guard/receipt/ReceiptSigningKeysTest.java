package io.aster.guard.receipt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 执行收据签名密钥配置（ADR 0040 §12 D1）：解析、fail-fast 校验、RFC 8032 公钥推导与启动自检。 */
class ReceiptSigningKeysTest {
  private static final HexFormat HEX = HexFormat.of();
  private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String SEED_A = b64(new byte[32]);
  private static final String SEED_B = b64(filled(0x02));

  private static byte[] filled(int b) {
    byte[] out = new byte[32];
    Arrays.fill(out, (byte) b);
    return out;
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String key(String kid, String seed, String extra) {
    return "{\"kid\":\"" + kid + "\",\"privateKey\":\"" + seed + "\"" + extra + "}";
  }

  private static String config(long revision, String... keys) {
    return "{\"revision\":" + revision + ",\"keys\":[" + String.join(",", keys) + "]}";
  }

  /** RFC 8032 §7.1 TEST 1/2/3：种子 → 公钥逐字节相等，签名逐字节等于 RFC 给出的签名。 */
  @Test
  void rfc8032Vectors1To3() throws Exception {
    String[][] vectors = {
        {"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"},
        {"4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"},
        {"c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"},
    };
    for (String[] v : vectors) {
      String json = config(1, key("rfc", b64(HEX.parseHex(v[0])), ""));
      ReceiptSigningKeys keys = ReceiptSigningKeys.parse(json, "rfc", CLOCK);
      ReceiptSigningKeys.SigningKey k = keys.active();
      assertThat(HEX.formatHex(k.rawPublicKey())).isEqualTo(v[1]);

      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(k.keyPair().getPrivate());
      signer.update(HEX.parseHex(v[2]));
      byte[] sig = signer.sign();
      assertThat(HEX.formatHex(sig)).isEqualTo(v[3]);

      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(k.keyPair().getPublic());
      verifier.update(HEX.parseHex(v[2]));
      assertThat(verifier.verify(sig)).isTrue();
    }
  }

  @Test
  void parsesTwoKeysWithRevisionNotAfterAndRevoked() {
    String json = config(7,
        key("a", SEED_A, ",\"notAfter\":\"2027-01-01T00:00:00Z\""),
        key("b", SEED_B, ",\"revoked\":true"));
    ReceiptSigningKeys keys = ReceiptSigningKeys.parse(json, "a", CLOCK);

    assertThat(keys.revision()).isEqualTo(7L);
    assertThat(keys.active().kid()).isEqualTo("a");
    assertThat(keys.active().notAfter()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    assertThat(keys.all()).extracting(ReceiptSigningKeys.SigningKey::kid).containsExactly("a", "b");
    assertThat(keys.find("b")).get().extracting(ReceiptSigningKeys.SigningKey::revoked).isEqualTo(true);
    assertThat(keys.find("missing")).isEmpty();
    assertThat(keys.isRevoked("b")).isTrue();
    assertThat(keys.isRevoked("a")).isFalse();
    assertThat(keys.isRevoked("missing")).isFalse();
  }

  @Test
  void emptyKeysFailsFast() {
    for (String json : new String[] {null, "", "  ", config(1)}) {
      assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, "a", CLOCK))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("signing-keys");
    }
  }

  @Test
  void activeKidMissingFailsFast() {
    String json = config(1, key("a", SEED_A, ""));
    assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, "zzz", CLOCK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active-kid")
        .hasMessageContaining("zzz");
    assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, null, CLOCK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active-kid");
    assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, " ", CLOCK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active-kid");
  }

  @Test
  void seedNot32BytesFailsFast() {
    for (String seed : new String[] {b64(new byte[31]), b64(new byte[33]), "", "!!!not-base64!!!"}) {
      String json = config(1, key("a", seed, ""));
      assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, "a", CLOCK))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("32");
    }
  }

  @Test
  void seedLengthMismatchMessageHasLengthButNoSeed() {
    String seed31 = b64(Arrays.copyOf(filled(0x07), 31));
    assertThatThrownBy(() -> ReceiptSigningKeys.parse(config(1, key("a", seed31, "")), "a", CLOCK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("实际 31 字节")
        .hasNoCause()
        .satisfies(t -> assertThat(t.getMessage()).doesNotContain(seed31));
  }

  @Test
  void activeKidRevokedFailsFast() {
    String json = config(1, key("a", SEED_A, ",\"revoked\":true"), key("b", SEED_B, ""));
    assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, "a", CLOCK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("revoked");
  }

  @Test
  void duplicateKidFailsFast() {
    String json = config(1, key("a", SEED_A, ""), key("a", SEED_B, ""));
    assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, "a", CLOCK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("重复");
  }

  @Test
  void malformedStructureFailsFast() {
    List<String> bad = List.of(
        "not json",
        "{\"keys\":[" + key("a", SEED_A, "") + "]}",
        "{\"revision\":-1,\"keys\":[" + key("a", SEED_A, "") + "]}",
        "{\"revision\":1.5,\"keys\":[" + key("a", SEED_A, "") + "]}",
        "{\"revision\":1,\"revision\":2,\"keys\":[" + key("a", SEED_A, "") + "]}",
        config(1, "{\"privateKey\":\"" + SEED_A + "\"}"),
        config(1, key("a", SEED_A, ",\"notAfter\":\"tomorrow\"")),
        config(1, key("a", SEED_A, ",\"revoked\":\"yes\"")),
        config(1, key("a", SEED_A, ",\"privatekey\":\"x\"")));
    for (String json : bad) {
      assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, "a", CLOCK))
          .as(json)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("signing-keys");
    }
  }

  @Test
  void errorMessageNeverContainsSeed() {
    String seed31 = b64(Arrays.copyOf(filled(0x07), 31));
    String invalidB64 = SEED_B.substring(0, 41);
    assertThat(seed31).hasSize(42);
    List<String> bad = List.of(
        config(1, key("a", seed31, "")),
        config(1, key("a", invalidB64, "")),
        config(1, key("a", SEED_B, ""), key("a", SEED_B, "")),
        config(1, key("a", SEED_B, ",\"revoked\":true")),
        config(1, key("a", SEED_B, ",\"notAfter\":\"2020-01-01T00:00:00Z\"")),
        config(1, key("a", SEED_B, ",\"unknown\":1")),
        "{\"revision\":1,\"keys\":[{\"kid\":\"a\",\"privateKey\":\"" + SEED_B + "\" oops}]}");
    for (String json : bad) {
      Throwable t = catchThrowable(() -> ReceiptSigningKeys.parse(json, "a", CLOCK));
      assertThat(t).as(json).isInstanceOf(IllegalStateException.class);
      for (Throwable c = t; c != null; c = c.getCause()) {
        assertThat(String.valueOf(c.getMessage())).doesNotContain(SEED_B).doesNotContain(seed31).doesNotContain(invalidB64);
      }
    }
  }

  /** M8：notAfter 只约束签发——now >= notAfter 的 key 不能被选为 active（排他边界）。 */
  @Test
  void activeKidPastNotAfterFailsFast() {
    String json = config(1, key("a", SEED_A, ",\"notAfter\":\"" + NOW + "\""));
    assertThatThrownBy(() -> ReceiptSigningKeys.parse(json, "a", CLOCK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notAfter");
    assertThat(ReceiptSigningKeys.parse(json, "a", Clock.fixed(NOW.minusMillis(1), ZoneOffset.UTC)).active().kid()).isEqualTo("a");

    String expiredNonActive = config(1, key("a", SEED_A, ""), key("old", SEED_B, ",\"notAfter\":\"2020-01-01T00:00:00Z\""));
    assertThat(ReceiptSigningKeys.parse(expiredNonActive, "a", CLOCK).find("old")).isPresent();
  }

  /** 每把 key 启动时签一段固定字节并用推导公钥验签；私钥与公钥不配对即启动失败。 */
  @Test
  void startupSelfCheckSignsAndVerifies() {
    ReceiptSigningKeys keys = ReceiptSigningKeys.parse(
        config(1, key("a", SEED_A, ""), key("b", SEED_B, ",\"revoked\":true")), "a", CLOCK);
    for (ReceiptSigningKeys.SigningKey k : keys.all()) {
      ReceiptSigningKeys.selfCheck(k.kid(), k.keyPair());
    }

    KeyPair a = keys.find("a").orElseThrow().keyPair();
    KeyPair b = keys.find("b").orElseThrow().keyPair();
    assertThatThrownBy(() -> ReceiptSigningKeys.selfCheck("mixed", new KeyPair(b.getPublic(), a.getPrivate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mixed")
        .hasMessageContaining("自检");
    assertThat(new String(ReceiptSigningKeys.SELF_CHECK_BYTES, StandardCharsets.UTF_8)).isNotEmpty();
  }

  /** application.properties 的 %dev 密钥（quarkus dev 启动用）必须能通过同一套校验。 */
  @Test
  void devProfileKeysParse() throws Exception {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
      props.load(in);
    }
    String json = props.getProperty("%dev.aster.guard.receipt.signing-keys");
    String activeKid = props.getProperty("%dev.aster.guard.receipt.active-kid");
    assertThat(json).isNotNull();
    assertThat(ReceiptSigningKeys.parse(json, activeKid, CLOCK).active().kid()).isEqualTo("dev-only");
    assertThat(props.getProperty("aster.guard.receipt.signing-keys")).isEqualTo("${ASTER_GUARD_RECEIPT_SIGNING_KEYS}");
    assertThat(props.getProperty("aster.guard.receipt.active-kid")).isEqualTo("${ASTER_GUARD_RECEIPT_ACTIVE_KID}");
  }

  /** perf.yml 与两份本地脚本里的仅测试密钥必须能通过同一套校验（否则这些启动路径会 fail-fast）。 */
  @Test
  void perfAndScriptKeysParse() throws Exception {
    Pattern single = Pattern.compile("'(\\{\"revision\".*?)'");
    for (String file : List.of(".github/workflows/perf.yml", "perf/run-capacity.sh", "scripts/test-pluggable-locally.sh")) {
      String text = Files.readString(Path.of(file));
      Matcher m = single.matcher(text);
      assertThat(m.find()).as(file).isTrue();
      assertThat(ReceiptSigningKeys.parse(m.group(1), "perf-only", CLOCK).active().kid()).as(file).isEqualTo("perf-only");
      assertThat(text).as(file).contains("ASTER_GUARD_RECEIPT_ACTIVE_KID").contains("ASTER_GUARD_RECEIPT_SIGNING_KEYS");
    }
  }
}
