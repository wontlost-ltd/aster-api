package io.aster.guard.reconcile;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.aster.guard.reconcile.ReconcileFixture.effect;
import static io.aster.guard.reconcile.ReconcileFixture.reportRaw;
import static io.aster.guard.reconcile.ReconcileFixture.sideEffects;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code POST /api/v1/guard/side-effects}（ADR 0054 §10.2）端到端。 */
@QuarkusTest
class GuardSideEffectResourceTest {

  private static String batch(String... effects) {
    return "{\"effects\":[" + String.join(",", effects) + "]}";
  }

  @Test
  void acceptsBatchAndIsIdempotent() {
    String t = ReconcileFixture.tenant("ingest");
    String decisionId = UUID.randomUUID().toString();
    String body = batch(effect("e-1", "billing-db", decisionId, UUID.randomUUID().toString(), "ab".repeat(32)),
        effect("e-2", "billing-db", null, null, null));

    Response first = reportRaw(t, body);
    assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
    assertThat(first.asString()).isEqualTo("{\"accepted\":2,\"duplicates\":0}");
    Response again = reportRaw(t, body);
    assertThat(again.asString()).isEqualTo("{\"accepted\":0,\"duplicates\":2}");

    var rows = sideEffects(t);
    assertThat(rows).hasSize(2);
    assertThat(rows).allSatisfy(r -> {
      assertThat(r.reportedBy).isEqualTo("user-reconcile-reporter");
      assertThat(r.reconciledAt).isNull();
      assertThat(r.receivedAt).isNotNull();
    });
    assertThat(rows.get(0).decisionId).isEqualTo(UUID.fromString(decisionId));
    assertThat(rows.get(0).actionName).isEqualTo("read_customer_record");
    assertThat(rows.get(0).resourceId).isEqualTo("C-1001");
    assertThat(rows.get(1).decisionId).isNull();
    assertThat(rows.get(1).attemptId).isNull();
  }

  @Test
  void duplicateWithinBatchCountsOnce() {
    String t = ReconcileFixture.tenant("ingest-dup");
    Response r = reportRaw(t, batch(effect("e-1", "crm", null, null, null), effect("e-1", "crm", null, null, null)));
    assertThat(r.asString()).isEqualTo("{\"accepted\":1,\"duplicates\":1}");
  }

  @Test
  void rejectsWholeBatchOnOneInvalid() {
    String t = ReconcileFixture.tenant("ingest-bad");
    Response r = reportRaw(t, batch(effect("e-1", "crm", null, null, null), effect("e-2", "Billing", null, null, null)));
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(r.asString()).isEqualTo("{\"error\":\"invalid_side_effect\",\"index\":1,\"field\":\"source\"}");
    assertThat(sideEffects(t)).isEmpty();
  }

  @Test
  void emptyBatchIsWholeInvalid() {
    Response r = reportRaw(ReconcileFixture.tenant("ingest-empty"), "{\"effects\":[]}");
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(r.asString()).isEqualTo("{\"error\":\"invalid_side_effect\",\"field\":\"effects\"}");
  }

  @Test
  void duplicateJsonKeyAndMalformedRejected() {
    String t = ReconcileFixture.tenant("ingest-dupkey");
    String one = effect("e-1", "crm", null, null, null);
    for (String body : new String[] {"{\"effects\":[" + one + "],\"effects\":[" + one + "]}", "{\"effects\":",
        "{\"effects\":\"x\"}", "[1]"}) {
      Response r = reportRaw(t, body);
      assertThat(r.statusCode()).as(body).isEqualTo(400);
      assertThat(r.asString()).as(body).isEqualTo("{\"error\":\"invalid_side_effect\"}");
    }
    assertThat(sideEffects(t)).isEmpty();
  }

  @Test
  void sameEffectIdDifferentTenantsAreDistinct() {
    String a = ReconcileFixture.tenant("ingest-a");
    String b = ReconcileFixture.tenant("ingest-b");
    String body = batch(effect("e-1", "crm", null, null, null));
    assertThat(reportRaw(a, body).asString()).isEqualTo("{\"accepted\":1,\"duplicates\":0}");
    assertThat(reportRaw(b, body).asString()).isEqualTo("{\"accepted\":1,\"duplicates\":0}");
    assertThat(sideEffects(a)).hasSize(1);
    assertThat(sideEffects(b)).hasSize(1);
  }
}
