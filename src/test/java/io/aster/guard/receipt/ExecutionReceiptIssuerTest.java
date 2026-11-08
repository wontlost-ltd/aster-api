package io.aster.guard.receipt;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 执行收据签发：毫秒截断、收据行与载荷一致、缺 actionHash 按 receipt_unavailable（不抛 NPE）、策略版本可缺省。 */
class ExecutionReceiptIssuerTest {
  private static final Instant NOW = Instant.parse("2026-11-08T01:00:00.987654Z");
  private static final String CONFIG = "{\"revision\":1,\"keys\":["
      + "{\"kid\":\"k1\",\"privateKey\":\"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE\"}]}";
  private static final String HASH = "a".repeat(64);

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final ExecutionReceiptSigner signer =
      new ExecutionReceiptSigner(ReceiptSigningKeys.parse(CONFIG, "k1", clock), Duration.ofMinutes(5), clock);
  private final ExecutionReceiptIssuer issuer = new ExecutionReceiptIssuer(signer, clock);

  @Test
  void issueNowTruncatesToMillisAndRowMatchesPayload() {
    UUID id = UUID.randomUUID();

    ExecutionReceiptIssuer.Issued issued = issuer.issueNow(id, "t1", HASH, 42L);

    ExecutionReceipt.Payload p = issued.receipt().payload();
    assertThat(p.issuedAt()).isEqualTo("2026-11-08T01:00:00.987Z");
    assertThat(p.expiresAt()).isEqualTo("2026-11-08T01:05:00.987Z");
    assertThat(signer.verify(issued.receipt())).isTrue();
    GuardReceiptEntity row = issued.row();
    assertThat(row.decisionId).isEqualTo(id);
    assertThat(row.issuedAt.toString()).isEqualTo(p.issuedAt());
    assertThat(row.expiresAt.toString()).isEqualTo(p.expiresAt());
    assertThat(row.signature).isEqualTo(issued.receipt().signature());
    assertThat(row.createdAt).isEqualTo(NOW);
  }

  @Test
  void missingActionHashIsReceiptUnavailable() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> issuer.issueNow(id, "t1", null, 42L))
        .isInstanceOf(ExecutionReceiptUnavailableException.class);
    assertThatThrownBy(() -> issuer.issue(id, "t1", null, null, NOW))
        .isInstanceOf(ExecutionReceiptUnavailableException.class);
  }

  /** 无已保存策略版本（evaluateSource 锚定的 from-evidence 决策）照常签发，载荷省略 policyVersionId。 */
  @Test
  void missingPolicyVersionStillIssues() {
    UUID id = UUID.randomUUID();

    ExecutionReceiptIssuer.Issued issued = issuer.issue(id, "t1", HASH, null, NOW);

    assertThat(issued.receipt().payload().policyVersionId()).isNull();
    assertThat(issued.receipt().payload().actionHash()).isEqualTo(HASH);
    assertThat(signer.verify(issued.receipt())).isTrue();
    assertThat(issued.row().actionHash).isEqualTo(HASH);
    assertThat(issuer.available()).isTrue();
  }
}
