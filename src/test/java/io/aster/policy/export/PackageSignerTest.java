package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.signing.Ed25519KeyRing;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 包签名器（ADR 0059 §6.2，修订 3 P2-R2 / P2-R3）：签名向量、缺密钥、无签名、kid 未进锚与 sig schema。 */
class PackageSignerTest {
  static final Path FIXTURES = Path.of("src/test/resources/package-v1");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final byte[] MANIFEST = "{\"format\":\"aster-policy-package\"}".getBytes(StandardCharsets.UTF_8);
  private static final String SEED = "Dg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4ODg4";

  private static Ed25519KeyRing ring(String kid, String seed, String extra) {
    String json = "{\"revision\":1,\"keys\":[{\"kid\":\"" + kid + "\",\"privateKey\":\"" + seed + "\"" + extra + "}]}";
    return Ed25519KeyRing.parse(json, kid, CLOCK, "aster.export.signing-keys");
  }

  private static Optional<ExportRuntimes.Target> anchored(String... kids) {
    return Optional.of(new ExportRuntimes.Target("cloud.aster-lang:aster-cli", "1.0.49", List.of(kids)));
  }

  @Test
  void signingVectorsReproduceWithTestKey() throws Exception {
    String seed = Files.readString(FIXTURES.resolve("keys/test-ed25519.priv.b64u")).strip();
    Ed25519KeyRing ring = ring("test-2026-11", seed, "");
    JsonNode vectors = JSON.readTree(FIXTURES.resolve("vectors/signing.json").toFile()).get("vectors");
    int checked = 0;
    for (JsonNode v : vectors) {
      if (!v.get("valid").asBoolean()) {
        continue;
      }
      byte[] canonical = Base64.getUrlDecoder().decode(v.get("canonicalUtf8B64u").asText());
      PackageSigner.SignOutcome out = PackageSigner.decide(canonical, Optional.of(ring), anchored("test-2026-11"),
          false, CLOCK);
      assertThat(out).isInstanceOf(PackageSigner.Signed.class);
      JsonNode sig = JSON.readTree(((PackageSigner.Signed) out).sigJson());
      assertThat(sig.get("sig").asText()).as(v.get("name").asText()).isEqualTo(v.get("sigFile").get("sig").asText());
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(4);
  }

  @Test
  void missingKeysRefusesUnlessAllowUnsigned() {
    assertThat(PackageSigner.decide(MANIFEST, Optional.empty(), anchored(), false, CLOCK))
        .isEqualTo(new PackageSigner.Refused("export_signing_unavailable"));
    assertThat(PackageSigner.decide(MANIFEST, Optional.empty(), anchored(), true, CLOCK))
        .isEqualTo(new PackageSigner.Unsigned());
  }

  @Test
  void unanchoredKidRefusedEvenWhenAllowUnsigned() {
    Ed25519KeyRing ring = ring("export-2026-11a", SEED, "");
    assertThat(PackageSigner.decide(MANIFEST, Optional.of(ring), anchored("other"), true, CLOCK))
        .isEqualTo(new PackageSigner.Refused("export_signing_kid_unanchored"));
  }

  /**
   * 上线形态（A6 锁文件 bump 之后）：仓内锁文件两个 target 的 {@code trustAnchorKids} 都是生产 kid {@code export-2026-11a}
   * （已发布的 aster-cli 1.0.52 与 ts 3.5.0 内置该锚），生产密钥据此签名；不在锚内的 kid 仍 fail-closed。
   */
  @Test
  void productionKidIsAnchoredByShippedLock() {
    Ed25519KeyRing ring = ring("export-2026-11a", SEED, "");
    ExportRuntimes.Lock shipped = ExportRuntimes.parse(ExportRuntimes.readResource(ExportRuntimes.DEFAULT_RESOURCE));
    for (String target : new String[] {"jre", "node"}) {
      PackageSigner.SignOutcome outcome = PackageSigner.decide(MANIFEST, Optional.of(ring),
          Optional.ofNullable(shipped.targets().get(target)), false, CLOCK);
      assertThat(outcome).as(target).isInstanceOf(PackageSigner.Signed.class);
      assertThat(((PackageSigner.Signed) outcome).kid()).isEqualTo("export-2026-11a");
    }
    Ed25519KeyRing other = ring("export-2026-12a", SEED, "");
    assertThat(PackageSigner.decide(MANIFEST, Optional.of(other), Optional.ofNullable(shipped.targets().get("jre")),
        true, CLOCK)).isEqualTo(new PackageSigner.Refused("export_signing_kid_unanchored"));
  }

  @Test
  void nullTargetIsRuntimeUnavailable() {
    assertThat(PackageSigner.decide(MANIFEST, Optional.empty(), Optional.empty(), true, CLOCK))
        .isEqualTo(new PackageSigner.Refused("export_runtime_unavailable"));
  }

  @Test
  void activeKeyExpiredAfterStartupRefused() {
    Ed25519KeyRing ring = ring("export-2026-11a", SEED, ",\"notAfter\":\"2026-11-19T00:00:00Z\"");
    Clock later = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);
    assertThat(PackageSigner.decide(MANIFEST, Optional.of(ring), anchored("export-2026-11a"), false, later))
        .isEqualTo(new PackageSigner.Refused("export_signing_unavailable"));
  }

  @Test
  void sigJsonIsCanonicalAndMatchesSigSchema() throws Exception {
    Ed25519KeyRing ring = ring("export-2026-11a", SEED, "");
    PackageSigner.Signed signed = (PackageSigner.Signed) PackageSigner.decide(MANIFEST, Optional.of(ring),
        anchored("export-2026-11a"), false, CLOCK);
    assertThat(signed.kid()).isEqualTo("export-2026-11a");
    String text = new String(signed.sigJson(), StandardCharsets.UTF_8);
    assertThat(text).startsWith("{\"alg\":\"Ed25519\",\"issuer\":\"aster-cloud\",\"kid\":\"export-2026-11a\",\"sig\":\"")
        .endsWith("\"}").doesNotContain(" ").doesNotContain("\n");
    assertThat(JsonSchemas.errors(FIXTURES.resolve("schema/sig-1.0.schema.json"), JSON.readTree(text))).isEmpty();
  }
}
