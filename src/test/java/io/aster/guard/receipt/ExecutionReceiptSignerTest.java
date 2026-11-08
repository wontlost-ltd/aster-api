package io.aster.guard.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** 执行收据签发与本地验签（ADR 0040 §12 D1/D6）：JCS 载荷字节、Ed25519 签名、时间窗。 */
class ExecutionReceiptSignerTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");
  private static final String HASH = "a".repeat(64);
  private static final String CONFIG = "{\"revision\":1,\"keys\":["
      + "{\"kid\":\"k1\",\"privateKey\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE\"},"
      + "{\"kid\":\"k2\",\"privateKey\":\"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\"}]}";

  /** 测试用可变时钟。 */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      this.now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final MutableClock clock = new MutableClock(T0);
  private final ReceiptSigningKeys keys = ReceiptSigningKeys.parse(CONFIG, "k1", T0);
  private final ExecutionReceiptSigner signer = new ExecutionReceiptSigner(keys, Duration.ofMinutes(5), clock);

  private ExecutionReceipt sign() {
    return signer.sign("dec-1", "tenant-1", HASH, 42L, T0);
  }

  @Test
  void signedPayloadVerifiesWithJdkSignature() throws Exception {
    ExecutionReceipt r = sign();
    byte[] sig = Base64.getUrlDecoder().decode(r.signature());
    assertThat(sig).hasSize(64);
    assertThat(r.signature()).doesNotContain("=").doesNotContain("+").doesNotContain("/");

    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(keys.find("k1").orElseThrow().keyPair().getPublic());
    verifier.update(JcsCanonicalizer.canonicalize(JSON.valueToTree(r.payload())));
    assertThat(verifier.verify(sig)).isTrue();
    assertThat(signer.verify(r)).isTrue();
  }

  @Test
  void anyPayloadByteChangeFailsVerify() {
    ExecutionReceipt r = sign();
    ExecutionReceipt.Payload p = r.payload();
    String tamperedHash = HASH.substring(0, 63) + "b";
    ExecutionReceipt.Payload changedHash = new ExecutionReceipt.Payload(tamperedHash, p.decisionId(), p.expiresAt(),
        p.issuedAt(), p.kid(), p.outcome(), p.policyVersionId(), p.tenantId(), p.v());
    assertThat(signer.verify(new ExecutionReceipt(r.kid(), changedHash, r.signature()))).isFalse();

    ExecutionReceipt.Payload changedTenant = new ExecutionReceipt.Payload(p.actionHash(), p.decisionId(), p.expiresAt(),
        p.issuedAt(), p.kid(), p.outcome(), p.policyVersionId(), "tenant-2", p.v());
    assertThat(signer.verify(new ExecutionReceipt(r.kid(), changedTenant, r.signature()))).isFalse();

    ExecutionReceipt other = signer.sign("dec-2", "tenant-1", HASH, 42L, T0);
    assertThat(signer.verify(new ExecutionReceipt(r.kid(), p, other.signature()))).isFalse();

    byte[] sig = Base64.getUrlDecoder().decode(r.signature());
    sig[0] ^= 1;
    String flipped = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    assertThat(signer.verify(new ExecutionReceipt(r.kid(), p, flipped))).isFalse();
    assertThat(signer.verify(new ExecutionReceipt(r.kid(), p, "not-base64!"))).isFalse();

    ExecutionReceipt.Payload otherKid = new ExecutionReceipt.Payload(p.actionHash(), p.decisionId(), p.expiresAt(),
        p.issuedAt(), "k2", p.outcome(), p.policyVersionId(), p.tenantId(), p.v());
    assertThat(signer.verify(new ExecutionReceipt("k2", otherKid, r.signature()))).isFalse();
    assertThat(signer.verify(new ExecutionReceipt("k2", p, r.signature()))).isFalse();
    assertThat(signer.verify(new ExecutionReceipt("unknown", p, r.signature()))).isFalse();
  }

  @Test
  void payloadSerializesAlphabeticallyWithV1AndOutcomeAllow() throws Exception {
    ExecutionReceipt r = sign();
    assertThat(r.kid()).isEqualTo("k1");
    assertThat(JSON.writeValueAsString(r.payload())).isEqualTo(
        "{\"actionHash\":\"" + HASH + "\",\"decisionId\":\"dec-1\",\"expiresAt\":\"2026-11-08T10:05:00Z\","
            + "\"issuedAt\":\"2026-11-08T10:00:00Z\",\"kid\":\"k1\",\"outcome\":\"ALLOW\",\"policyVersionId\":42,"
            + "\"tenantId\":\"tenant-1\",\"v\":1}");
    assertThat(new String(JcsCanonicalizer.canonicalize(JSON.valueToTree(r.payload())), StandardCharsets.UTF_8))
        .isEqualTo(JSON.writeValueAsString(r.payload()));
    String receiptJson = JSON.writeValueAsString(r);
    assertThat(receiptJson).startsWith("{\"kid\":\"k1\",\"payload\":{").contains("\"signature\":\"");
  }

  @Test
  void expiresAtIsIssuedAtPlusTtl() {
    ExecutionReceipt r = sign();
    assertThat(Instant.parse(r.payload().expiresAt())).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    assertThat(Instant.parse(r.payload().issuedAt())).isEqualTo(T0);

    ExecutionReceiptSigner longer = new ExecutionReceiptSigner(keys, Duration.ofSeconds(90), clock);
    assertThat(longer.sign("d", "t", HASH, 1L, T0).payload().expiresAt()).isEqualTo("2026-11-08T10:01:30Z");
  }

  /** 本地窗口 issuedAt − 60s ≤ now &lt; expiresAt（expiresAt 排他）。 */
  @Test
  void verifyWindowBoundaries() {
    ExecutionReceipt r = sign();
    Instant expiresAt = T0.plus(Duration.ofMinutes(5));

    clock.set(T0.minusSeconds(60));
    assertThat(signer.verify(r)).isTrue();
    clock.set(T0.minusSeconds(61));
    assertThat(signer.verify(r)).isFalse();
    clock.set(expiresAt.minusMillis(1));
    assertThat(signer.verify(r)).isTrue();
    clock.set(expiresAt);
    assertThat(signer.verify(r)).isFalse();
  }
}
