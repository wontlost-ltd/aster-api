package io.aster.guard;

import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.policy.event.AuditEvent;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 单测桩：把 mock 的 {@link GuardDecisionStore#register} 展开为「{@code appender.appendSync} → 构造行 →
 * {@code store.saveDecision}」，编排单测因此仍能逐步断言链事件与写入的行（追加失败 → 以 null 构造行）。
 * 锁内复查与同一事务的原子性由真实存储承担，见 {@code GuardDecisionResourceTest} / {@code GuardFromEvidenceResourceTest}
 * 的并发用例（#522）。
 */
final class SequentialRegistrationStub {
  private SequentialRegistrationStub() {
  }

  @SuppressWarnings("unchecked")
  static void install(GuardDecisionStore store, AuditChainAppender appender) {
    when(store.register(any(), any(), any(), any())).thenAnswer(inv -> {
      AuditEvent event = inv.getArgument(2);
      Function<AppendedRecord, GuardDecisionRegistration.Rows> rows = inv.getArgument(3);
      AppendedRecord chain;
      try {
        chain = appender.appendSync(event);
      } catch (AuditChainAppender.AuditAppendException e) {
        chain = null;
      }
      GuardDecisionRegistration.Rows written = rows.apply(chain);
      store.saveDecision(written.decision(), written.approval(), written.receipt(), written.input());
      return new GuardDecisionRegistration.Registered(written);
    });
  }
}
