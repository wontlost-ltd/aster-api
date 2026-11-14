package io.aster.guard.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 副作用批量上报请求体（ADR 0054 §10.2）与其校验。校验是纯函数：任一条不合法即抛
 * {@link InvalidSideEffectException}（带首个不合法条目的下标与字段名），整批不写。
 */
public record SideEffectReport(List<Effect> effects) {
  public static final int MAX_EFFECTS = 500;
  static final int MAX_EFFECT_ID = 128;
  static final int MAX_TEXT = 255;
  /** 上报时刻允许超前服务端时钟的上限（时钟偏差）。 */
  static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
  private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  /** 与 {@code GuardReceiptService.UUID_SHAPE} 同形。 */
  private static final Pattern UUID_SHAPE =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern ACTION_HASH = Pattern.compile("[0-9a-f]{64}");

  /**
   * 一条副作用。{@code decisionId / attemptId / actionHash} 是上报方从 consume 票据里拿到的凭据，可缺省——
   * 缺 decisionId 本身就是要抓的情形。
   */
  public record Effect(String effectId, String source, String occurredAt, ActionRef action, ResourceRef resource,
                       String decisionId, String attemptId, String actionHash) {
    /** 已校验后调用。 */
    public Instant occurredInstant() {
      return OffsetDateTime.parse(occurredAt).toInstant();
    }

    public UUID decisionUuid() {
      return decisionId == null ? null : UUID.fromString(decisionId);
    }
  }

  public record ActionRef(String name) {}

  public record ResourceRef(String type, String id) {}

  /** 校验失败；{@code index = -1} 表示整体（条数或请求体本身）。 */
  public static final class InvalidSideEffectException extends RuntimeException {
    private final int index;
    private final String field;

    public InvalidSideEffectException(int index, String field) {
      super("invalid side effect at " + index + ": " + field);
      this.index = index;
      this.field = field;
    }

    public int index() {
      return index;
    }

    public String field() {
      return field;
    }
  }

  public static void validate(SideEffectReport r, Instant now) {
    if (r == null || r.effects() == null || r.effects().isEmpty() || r.effects().size() > MAX_EFFECTS) {
      throw new InvalidSideEffectException(-1, "effects");
    }
    for (int i = 0; i < r.effects().size(); i++) {
      String field = invalidField(r.effects().get(i), now);
      if (field != null) {
        throw new InvalidSideEffectException(i, field);
      }
    }
  }

  /** 首个不合法字段名；合法返回 null。顺序固定，测试据此断言。 */
  static String invalidField(Effect e, Instant now) {
    if (e == null) {
      return "effect";
    }
    if (!boundedText(e.effectId(), MAX_EFFECT_ID) || e.effectId().chars().anyMatch(Character::isISOControl)) {
      return "effectId";
    }
    if (e.source() == null || !SOURCE.matcher(e.source()).matches()) {
      return "source";
    }
    if (!occurredAtValid(e.occurredAt(), now)) {
      return "occurredAt";
    }
    if (e.action() == null) {
      return "action";
    }
    if (!boundedText(e.action().name(), MAX_TEXT)) {
      return "action.name";
    }
    if (e.resource() != null && tooLong(e.resource().type())) {
      return "resource.type";
    }
    if (e.resource() != null && tooLong(e.resource().id())) {
      return "resource.id";
    }
    if (e.decisionId() != null && !UUID_SHAPE.matcher(e.decisionId()).matches()) {
      return "decisionId";
    }
    if (e.attemptId() != null && !UUID_SHAPE.matcher(e.attemptId()).matches()) {
      return "attemptId";
    }
    if (e.actionHash() != null && !ACTION_HASH.matcher(e.actionHash()).matches()) {
      return "actionHash";
    }
    return null;
  }

  private static boolean boundedText(String s, int max) {
    return s != null && !s.isEmpty() && s.length() <= max;
  }

  private static boolean tooLong(String s) {
    return s != null && s.length() > MAX_TEXT;
  }

  private static boolean occurredAtValid(String s, Instant now) {
    if (s == null) {
      return false;
    }
    try {
      return !OffsetDateTime.parse(s).toInstant().isAfter(now.plus(MAX_CLOCK_SKEW));
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
