package io.aster.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardDecisionResponse.ReceiptView;
import io.aster.guard.api.GuardFromEvidenceRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 从证据开决策的重放分支（不启动 Quarkus：重放在查链与落库之前返回）。 */
class GuardEvidenceDecisionServiceTest {
  private static final String TENANT = "t-evidence-unit";
  private static final String CID = "ev-unit-1";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final GuardDecisionStore store = mock(GuardDecisionStore.class);
  private final GuardDecisionService decisionService = mock(GuardDecisionService.class);
  private final GuardEvidenceDecisionService service = new GuardEvidenceDecisionService(store, decisionService,
      new GuardActionMapper(), objectMapper, Duration.ofHours(24));

  private GuardDecisionEntity storedRow(GuardDecisionResponse response) throws Exception {
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.id = UUID.fromString(response.decisionId());
    e.tenantId = TENANT;
    e.evidenceCorrelationId = CID;
    e.responseJson = objectMapper.writeValueAsString(response);
    return e;
  }

  private static GuardDecisionResponse response(String effectiveOutcome) {
    return new GuardDecisionResponse(null, List.of("GDPR:ART17"), UUID.randomUUID().toString(), effectiveOutcome,
        "REQUIRE_APPROVAL", null, null, null, new ReceiptView(11L, "h", "p"), "Data Protection Officer", "R-1");
  }

  @Test
  void 决策行命中且可回读时回放当前响应() throws Exception {
    GuardDecisionResponse stored = response("PENDING");
    GuardDecisionResponse current = response("ALLOW");
    GuardDecisionEntity row = storedRow(stored);
    when(store.findByEvidenceCorrelationId(TENANT, CID)).thenReturn(Optional.of(row));
    when(decisionService.get(TENANT, row.id)).thenReturn(Optional.of(current));

    assertThat(service.createFromEvidence(TENANT, "u-req", new GuardFromEvidenceRequest(CID, null))).isEqualTo(current);
    verify(store, never()).saveDecision(any(), any());
  }

  @Test
  void 决策行命中但回读为空时回放行内响应_不再插入() throws Exception {
    GuardDecisionResponse stored = response("PENDING");
    GuardDecisionEntity row = storedRow(stored);
    when(store.findByEvidenceCorrelationId(TENANT, CID)).thenReturn(Optional.of(row));
    when(decisionService.get(TENANT, row.id)).thenReturn(Optional.empty());

    assertThat(service.createFromEvidence(TENANT, "u-req", new GuardFromEvidenceRequest(CID, null))).isEqualTo(stored);
    verify(store, never()).saveDecision(any(), any());
  }
}
