package io.aster.guard;

import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.guard.snapshot.GuardDecisionInputEntity;

import java.util.Objects;

/**
 * 决策注册（#522）的入参与结果：{@link GuardDecisionStore#register} 在同一事务、同一 (tenant, 键) 咨询锁内完成
 * 「复查既有决策 → 追加决策事件 → 写决策行」，链上不会留下指向不存在决策的收据。
 */
public final class GuardDecisionRegistration {
  private GuardDecisionRegistration() {
  }

  /** 去重键的来源：{@code POST /decisions} 的幂等键，或 from-evidence 的证据相关键。 */
  public enum KeyKind {
    IDEMPOTENCY, EVIDENCE
  }

  /** 去重键：同租户同键的注册在锁内串行，后到者在锁内复查到先到者的决策行。 */
  public record Key(KeyKind kind, String value) {
    public Key {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(value, "value");
    }

    public static Key idempotency(String value) {
      return new Key(KeyKind.IDEMPOTENCY, value);
    }

    public static Key evidence(String value) {
      return new Key(KeyKind.EVIDENCE, value);
    }
  }

  /**
   * 一次注册要写入的行与对外响应（同 {@link GuardDecisionStore#saveDecision} 的四类行）。
   *
   * @param approval 建了待审批时的审批行，其余为 null
   * @param receipt  直接 ALLOW 时的执行收据行，其余为 null
   * @param input    建了审批行时的输入快照，其余为 null
   */
  public record Rows(GuardDecisionEntity decision, GuardApprovalEntity approval, GuardReceiptEntity receipt,
                     GuardDecisionInputEntity input, GuardDecisionResponse response) {
    public Rows {
      Objects.requireNonNull(decision, "decision");
      Objects.requireNonNull(response, "response");
    }
  }

  /** 注册结果。 */
  public sealed interface Result permits Registered, Existing {}

  /** 本次写入：决策事件与各行已在同一事务提交。 */
  public record Registered(Rows rows) implements Result {}

  /** 锁内复查发现同键先到者（已提交）：本次什么都没写，调用方按幂等重放返回先到者的决策。 */
  public record Existing(GuardDecisionEntity decision) implements Result {}
}
