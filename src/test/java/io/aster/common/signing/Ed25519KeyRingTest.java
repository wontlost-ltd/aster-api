package io.aster.common.signing;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 共用 Ed25519 密钥环（ADR 0059 修订 3 P2-R2）：解析、fail-fast 校验、RFC 8032 公钥推导与启动自检；错误消息带配置名、不含种子。 */
class Ed25519KeyRingTest {
  private static final String CONFIG = "aster.export.signing-keys";
  private static final HexFormat HEX = HexFormat.of();
  private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
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

  private static Ed25519KeyRing parse(String json, String activeKid) {
    return Ed25519KeyRing.parse(json, activeKid, CLOCK, CONFIG);
  }

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
      Ed25519KeyRing.SigningKey k = parse(config(1, key("rfc", b64(HEX.parseHex(v[0])), "")), "rfc").active();
      assertThat(HEX.formatHex(k.rawPublicKey())).isEqualTo(v[1]);
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(k.keyPair().getPrivate());
      signer.update(HEX.parseHex(v[2]));
      assertThat(HEX.formatHex(signer.sign())).isEqualTo(v[3]);
    }
  }

  @Test
  void parsesRevisionNotAfterAndRevoked() {
    Ed25519KeyRing ring = parse(config(7,
        key("a", SEED_A, ",\"notAfter\":\"2027-01-01T00:00:00Z\""),
        key("b", SEED_B, ",\"revoked\":true")), "a");
    assertThat(ring.revision()).isEqualTo(7L);
    assertThat(ring.active().kid()).isEqualTo("a");
    assertThat(ring.all()).extracting(Ed25519KeyRing.SigningKey::kid).containsExactly("a", "b");
    assertThat(ring.isRevoked("b")).isTrue();
    assertThat(ring.isRevoked("missing")).isFalse();
    assertThat(ring.find("missing")).isEmpty();
    assertThat(ring.active().toString()).doesNotContain(SEED_A);
  }

  @Test
  void invalidConfigsFailWithConfigNameAndNoSeed() {
    String seed31 = b64(Arrays.copyOf(filled(0x07), 31));
    List<String> bad = List.of(
        "not json",
        config(1),
        "{\"revision\":1,\"revision\":2,\"keys\":[" + key("a", SEED_B, "") + "]}",
        config(1, key("a", SEED_B, ",\"unknown\":1")),
        config(1, key("a", SEED_B, ""), key("a", SEED_B, "")),
        config(1, key("k".repeat(65), SEED_B, "")),
        config(1, key("a", seed31, "")),
        config(1, key("a", SEED_B, ",\"revoked\":true")),
        config(1, key("a", SEED_B, ",\"notAfter\":\"" + NOW + "\"")),
        config(1, key("b", SEED_B, "")),
        "{\"revision\":1,\"keys\":[{\"kid\":\"a\",\"privateKey\":\"" + SEED_B + "\" oops}]}");
    for (String json : bad) {
      Throwable t = catchThrowable(() -> parse(json, "a"));
      assertThat(t).as(json).isInstanceOf(IllegalStateException.class);
      assertThat(t.getMessage()).as(json).contains("aster.export.");
      for (Throwable c = t; c != null; c = c.getCause()) {
        assertThat(String.valueOf(c.getMessage())).doesNotContain(SEED_B).doesNotContain(seed31);
      }
    }
  }

  @Test
  void activeKidMessagesNameTheActiveKidProperty() {
    String json = config(1, key("a", SEED_A, ""));
    assertThatThrownBy(() -> parse(json, null)).hasMessageContaining("aster.export.active-kid")
        .hasMessageContaining("ASTER_EXPORT_ACTIVE_KID");
    assertThatThrownBy(() -> parse(json, "zzz")).hasMessageContaining("aster.export.active-kid=zzz");
    assertThatThrownBy(() -> parse(null, "a")).hasMessageContaining("ASTER_EXPORT_SIGNING_KEYS");
  }

  @Test
  void selfCheckRejectsMismatchedPair() {
    Ed25519KeyRing ring = parse(config(1, key("a", SEED_A, ""), key("b", SEED_B, "")), "a");
    KeyPair a = ring.find("a").orElseThrow().keyPair();
    KeyPair b = ring.find("b").orElseThrow().keyPair();
    Ed25519KeyRing.selfCheck("a", a, CONFIG);
    assertThatThrownBy(() -> Ed25519KeyRing.selfCheck("mixed", new KeyPair(b.getPublic(), a.getPrivate()), CONFIG))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("mixed").hasMessageContaining(CONFIG);
  }
}
