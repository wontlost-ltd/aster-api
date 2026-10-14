package io.aster.policy.rest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 导出并发闸：占满即拒、归还后可再取，许可数取自配置。 */
class CedarExportGateTest {

  @Test
  void rejectsWhenAllPermitsAreHeldAndRecoversOnRelease() {
    CedarExportGate gate = new CedarExportGate(1);
    assertThat(gate.limit()).isEqualTo(1);
    assertThat(gate.tryAcquire()).isTrue();
    assertThat(gate.tryAcquire()).isFalse();
    gate.release();
    assertThat(gate.tryAcquire()).isTrue();
  }

  @Test
  void nonPositiveConfigFallsBackToOnePermit() {
    assertThat(new CedarExportGate(0).limit()).isEqualTo(1);
  }
}
