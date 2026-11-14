package io.aster.guard.snapshot;

import io.aster.guard.GuardActionMapper;
import io.aster.policy.governance.InputClassification;
import io.aster.policy.governance.PiiMark;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 评估分类行写入（ADR 0042 §12 D1）：只写可审批结论，先写者为准，失败不上抛。 */
class EvaluationClassificationWriterTest {
  private static final InputClassification CLS = new InputClassification(
      List.of("context.applicant.email"), List.of(new PiiMark("context.applicant.email", "L2", "email", "ir")));

  /** 内存实现：与 JPA 实现同一「先写者为准」语义。 */
  static final class MemoryStore implements ClassificationRowStore {
    final Map<String, GuardEvaluationClassificationEntity> rows = new HashMap<>();
    RuntimeException failWith;

    @Override
    public boolean insertIfAbsent(GuardEvaluationClassificationEntity row) {
      if (failWith != null) {
        throw failWith;
      }
      return rows.putIfAbsent(row.tenantId + "|" + row.correlationId, row) == null;
    }

    @Override
    public Optional<GuardEvaluationClassificationEntity> find(String tenantId, String correlationId) {
      return Optional.ofNullable(rows.get(tenantId + "|" + correlationId));
    }
  }

  private final MemoryStore store = new MemoryStore();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final GuardActionMapper mapper = new GuardActionMapper();
  private final EvaluationClassificationWriter writer = new EvaluationClassificationWriter(store, mapper, registry);

  private double failures() {
    return registry.get(EvaluationClassificationWriter.WRITE_FAILED).counter().count();
  }

  private static Map<String, Object> named() {
    Map<String, Object> applicant = new LinkedHashMap<>();
    applicant.put("email", "a@b.cc");
    applicant.put("amount", 1.10);
    return Map.of("applicant", applicant);
  }

  @Test
  void writesOnlyForApprovableOutcomes() {
    writer.record("t1", "c-allow", named(), "ALLOW", CLS, false);
    writer.record("t1", "c-deny", named(), "DENY", CLS, false);
    writer.record("t1", "c-none", named(), null, CLS, false);
    writer.record("t1", "c-ra", named(), "REQUIRE_APPROVAL", CLS, false);
    writer.record("t1", "c-esc", named(), "ESCALATE", CLS, false);
    assertThat(store.rows.keySet()).containsExactlyInAnyOrder("t1|c-ra", "t1|c-esc");
  }

  @Test
  void simulateDoesNotWrite() {
    writer.record("t1", "c1", named(), "REQUIRE_APPROVAL", CLS, true);
    assertThat(store.rows).isEmpty();
  }

  @Test
  void namedContextGetsInputHash() {
    Map<String, Object> ctx = named();
    writer.record("t1", "c1", ctx, "REQUIRE_APPROVAL", CLS, false);
    GuardEvaluationClassificationEntity row = writer.find("t1", "c1").orElseThrow();
    assertThat(row.inputHash).isEqualTo(mapper.contextHash(ctx)).hasSize(64);
    assertThat(InputClassification.fromJson(row.classification)).isEqualTo(CLS);
    assertThat(row.createdAt).isNotNull();
  }

  @Test
  void positionalContextHasNullHash() {
    writer.record("t1", "c1", new Object[]{named()}, "REQUIRE_APPROVAL", CLS, false);
    writer.record("t1", "c2", List.of(named()), "REQUIRE_APPROVAL", CLS, false);
    assertThat(store.rows.values()).hasSize(2).allSatisfy(row -> {
      assertThat(row.inputHash).isNull();
      assertThat(row.classification).isNull();
    });
  }

  @Test
  void nullClassificationStillBindsHash() {
    writer.record("t1", "c1", named(), "REQUIRE_APPROVAL", null, false);
    GuardEvaluationClassificationEntity row = writer.find("t1", "c1").orElseThrow();
    assertThat(row.inputHash).isNotNull();
    assertThat(row.classification).isNull();
  }

  @Test
  void writeFailureDoesNotThrow() {
    store.failWith = new IllegalStateException("db down");
    assertThatCode(() -> writer.record("t1", "c1", named(), "REQUIRE_APPROVAL", CLS, false))
        .doesNotThrowAnyException();
    assertThat(failures()).isEqualTo(1.0);
  }

  @Test
  void secondWriteSameCorrelationIdKeepsFirst() {
    writer.record("t1", "c1", named(), "REQUIRE_APPROVAL", CLS, false);
    String firstHash = writer.find("t1", "c1").orElseThrow().inputHash;
    writer.record("t1", "c1", Map.of("other", 1), "ESCALATE", null, false);
    GuardEvaluationClassificationEntity row = writer.find("t1", "c1").orElseThrow();
    assertThat(row.inputHash).isEqualTo(firstHash);
    assertThat(InputClassification.fromJson(row.classification)).isEqualTo(CLS);
    assertThat(failures()).isEqualTo(1.0);
  }
}
