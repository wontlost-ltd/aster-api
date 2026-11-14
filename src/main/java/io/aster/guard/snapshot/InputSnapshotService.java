package io.aster.guard.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.GuardActionMapper;
import io.aster.guard.GuardApprovalConflictException;
import io.aster.guard.GuardApprovalEntity;
import io.aster.guard.GuardApprovalStatus;
import io.aster.guard.GuardDecisionEntity;
import io.aster.guard.GuardReceiptBuilder;
import io.aster.guard.api.GuardApprovalInputView;
import io.aster.guard.provenance.InputSourceResolver;
import io.aster.policy.common.PIIRedactor;
import io.aster.policy.entity.AuditLog;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.governance.InputClassification;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 审批输入视图与原值解除（ADR 0042 §12 D5 / D7 / D8）。
 *
 * <p>输入只经审批行可达：跨租户与不存在一律 {@code approval_not_found}。{@code GET} 永远返回打码视图、无任何写副作用；
 * 原值只经 {@link #reveal} 返回：同一事务内先锁审批行（与审批 / 过期迁移同一把行锁），在锁内查本人解除行，首次解除时在
 * <b>同一事务</b>里追加 {@code ACTION_GUARD_INPUT_REVEAL} 事件并插入解除行。入链失败 → 503 {@code receipt_unavailable}，
 * 事务回滚、不返回原值（没有留痕就不解除）；行锁使同一人并发首次解除恰好一条事件。
 *
 * <p>阻塞方法：须在非事务的 worker 线程调用。
 */
@ApplicationScoped
public class InputSnapshotService {
  public static final String AVAILABLE = "available";
  public static final String UNAVAILABLE = "unavailable";
  public static final String PURGED = "purged";
  public static final String INTEGRITY_FAILED = "integrity_failed";
  public static final String TOO_LARGE = "too_large";
  /** 视图上限（ADR 0042 §12 D7）：渲染后 UTF-8 ≤ 1 MiB。 */
  static final int MAX_VIEW_BYTES = 1 << 20;
  private static final Logger LOG = Logger.getLogger(InputSnapshotService.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
  private static final PIIRedactor REDACTOR = new PIIRedactor();

  private ObjectMapper objectMapper;
  private GuardReceiptBuilder receiptBuilder;
  private InputSourceResolver inputSourceResolver;
  private Duration inputRetention;
  /** 在当前事务内追加审计事件；测试可替换为失败实现。 */
  Function<AuditEvent, AppendedRecord> revealAppender;

  /** CDI 客户端代理所需。 */
  protected InputSnapshotService() {
  }

  @Inject
  public InputSnapshotService(ObjectMapper objectMapper, AuditChainAppender appender,
                              GuardReceiptBuilder receiptBuilder, InputSourceResolver inputSourceResolver,
                              @ConfigProperty(name = "aster.guard.input-retention", defaultValue = "P90D")
                              Duration inputRetention) {
    this.objectMapper = objectMapper;
    this.receiptBuilder = receiptBuilder;
    this.inputSourceResolver = inputSourceResolver;
    this.inputRetention = inputRetention;
    this.revealAppender = appender::appendInCurrentTransaction;
  }

  /**
   * 一条审批的输入快照及其状态：{@code status ≠ available} 时 {@code view} 为 null。
   *
   * @param maskedView   打码视图（所有审批人相同）
   * @param inputSources 视图哈希中的 {@code inputSources}（不含随时间变化的 factsStale）
   */
  public record Snapshot(String status, GuardDecisionEntity decision, GuardDecisionInputEntity input,
                         Map<String, Object> action, InputClassification classification,
                         Map<String, Object> inputSources, InputViewRenderer.RenderedView maskedView) {
    public boolean available() {
      return AVAILABLE.equals(status);
    }
  }

  /** 测试替换入链实现（模拟审计链写入失败）；返回原实现以便恢复。经 CDI 客户端代理调用，作用于真实实例。 */
  Function<AuditEvent, AppendedRecord> replaceRevealAppenderForTest(Function<AuditEvent, AppendedRecord> replacement) {
    Function<AuditEvent, AppendedRecord> previous = revealAppender;
    revealAppender = replacement;
    return previous;
  }

  /** 打码视图；不产生任何写副作用。 */
  public GuardApprovalInputView view(String tenantId, UUID approvalId, String user) {
    return QuarkusTransaction.requiringNew().call(() -> {
      GuardApprovalEntity a = GuardApprovalEntity.findByIdAndTenant(approvalId, tenantId)
          .orElseThrow(() -> notFound(approvalId));
      Snapshot s = load(a);
      return toView(a, s, s.maskedView(), false, hasRevealed(a, user));
    });
  }

  /**
   * 解除原值：审批 PENDING 且审批人已验证业务角色包含 {@code required_role}（ESCALATE 无所需角色，不可解除）。
   *
   * @throws GuardApprovalConflictException {@code approval_not_found}(404)、{@code approval_not_pending}(409)、
   *                                        {@code reveal_not_permitted}(403，附 verifiedRoles)、
   *                                        {@code input_not_available}(409)、{@code receipt_unavailable}(503)
   */
  public GuardApprovalInputView reveal(String tenantId, UUID approvalId, String user, List<String> verifiedRoles) {
    return QuarkusTransaction.requiringNew().call(() -> {
      GuardApprovalEntity a = GuardApprovalEntity.<GuardApprovalEntity>find("id = ?1 and tenantId = ?2", approvalId,
          tenantId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResultOptional().orElseThrow(() -> notFound(approvalId));
      if (a.status != GuardApprovalStatus.PENDING || a.isExpired(Instant.now())) {
        throw new GuardApprovalConflictException("approval_not_pending", "审批已处理或已过期: " + approvalId);
      }
      if (a.requiredRole == null || !verifiedRoles.contains(a.requiredRole)) {
        throw new GuardApprovalConflictException("reveal_not_permitted", "解除原值需要该审批的所需业务角色",
            Map.of("verifiedRoles", verifiedRoles));
      }
      Snapshot s = load(a);
      if (!s.available()) {
        throw new GuardApprovalConflictException("input_not_available", "该审批的输入快照不可用: " + s.status(),
            Map.of("status", s.status()));
      }
      InputViewRenderer.RenderedView revealed = render(s, true);
      // 上限同样作用于解除视图：短的 $pii 占位可能掩盖很长的原值
      if (revealed.sizeBytes() > MAX_VIEW_BYTES) {
        throw new GuardApprovalConflictException("input_not_available", "解除后的输入视图超过 1 MiB",
            Map.of("status", TOO_LARGE));
      }
      if (!hasRevealed(a, user)) {
        record(a, user, revealed);
      }
      return toView(a, s, revealed, true, true);
    });
  }

  /** 列表用：本租户中有输入行的决策 id（一次 in 查询，空输入不访问数据库）。 */
  public Set<UUID> decisionsWithInput(String tenantId, java.util.Collection<UUID> decisionIds) {
    if (decisionIds.isEmpty()) {
      return Set.of();
    }
    return QuarkusTransaction.requiringNew().call(() -> GuardDecisionInputEntity
        .<GuardDecisionInputEntity>list("tenantId = ?1 and decisionId in ?2", tenantId, decisionIds)
        .stream().map(e -> e.decisionId).collect(java.util.stream.Collectors.toSet()));
  }

  /** 审批快照哈希（A7）：该审批人的打码视图哈希与（已解除时）解除视图哈希。 */
  public record ApproverHashes(String status, String masked, String revealed) {
  }

  /** 供审批时计算：不改动任何行。 */
  public ApproverHashes hashesFor(GuardApprovalEntity a, String user) {
    return QuarkusTransaction.requiringNew().call(() -> {
      Snapshot s = load(a);
      if (!s.available()) {
        return new ApproverHashes(s.status(), null, null);
      }
      String revealed = hasRevealed(a, user) ? render(s, true).hash() : null;
      return new ApproverHashes(s.status(), s.maskedView().hash(), revealed);
    });
  }

  /**
   * 审批加锁迁移内复核（须在持有审批行锁的事务内调用）：本人此刻是否有解除记录。解除同样先锁审批行，
   * 所以在锁内读到的就是与本次迁移串行化后的结果。
   */
  public boolean revealedNow(GuardApprovalEntity a, String user) {
    return hasRevealed(a, user);
  }

  /** 入链 → 插解除行，同一事务；入链失败抛 503，事务随之回滚。 */
  private void record(GuardApprovalEntity a, String user, InputViewRenderer.RenderedView revealed) {
    AppendedRecord appended;
    try {
      appended = revealAppender.apply(receiptBuilder.inputRevealEvent(a, user, revealed.revealedFields(),
          InputViewRenderer.MASK_RULES, revealed.hash()));
    } catch (RuntimeException e) {
      LOG.errorf(e, "审批输入解除事件写入失败，不返回原值: approval=%s", a.id);
      throw new GuardApprovalConflictException("receipt_unavailable", "解除留痕写入审计链失败，请稍后重试");
    }
    GuardInputRevealEntity row = new GuardInputRevealEntity();
    row.approvalId = a.id;
    row.revealedBy = user;
    row.tenantId = a.tenantId;
    row.revealedAt = Instant.now();
    row.auditId = appended.auditId();
    row.persistAndFlush();
  }

  private static boolean hasRevealed(GuardApprovalEntity a, String user) {
    return user != null && GuardInputRevealEntity.findById(new GuardInputRevealEntity.Key(a.id, user)) != null;
  }

  /**
   * 读取并判定状态（须在事务内调用）：无输入行 → 审批终态且已过保留期为 {@code purged}，否则 {@code unavailable}
   * （修订前的决策）；明文 context 重算的哈希与决策行不等 → {@code integrity_failed}；打码视图超过 1 MiB → {@code too_large}。
   */
  Snapshot load(GuardApprovalEntity a) {
    GuardDecisionEntity d = GuardDecisionEntity.findByIdAndTenant(a.decisionId, a.tenantId).orElse(null);
    Optional<GuardDecisionInputEntity> row = d == null ? Optional.empty()
        : GuardDecisionInputEntity.findByDecisionAndTenant(d.id, a.tenantId);
    if (row.isEmpty()) {
      return new Snapshot(purgedOrUnavailable(a), d, null, null, null, null, null);
    }
    GuardDecisionInputEntity in = row.get();
    Map<String, Object> action = parse(in.actionJson);
    if (action == null || !intact(action, in, d)) {
      return new Snapshot(INTEGRITY_FAILED, d, in, null, null, null, null);
    }
    InputClassification c;
    try {
      c = InputClassification.fromJson(in.classification);
    } catch (IllegalArgumentException e) {
      return new Snapshot(INTEGRITY_FAILED, d, in, null, null, null, null);
    }
    Snapshot partial = new Snapshot(AVAILABLE, d, in, action, c, inputSources(d), null);
    InputViewRenderer.RenderedView masked = render(partial, false);
    if (masked.sizeBytes() > MAX_VIEW_BYTES) {
      return new Snapshot(TOO_LARGE, d, in, null, null, null, null);
    }
    return new Snapshot(AVAILABLE, d, in, action, c, partial.inputSources(), masked);
  }

  /** 审批的输入状态（列表项与审批事件用）。 */
  public String status(GuardApprovalEntity a) {
    return QuarkusTransaction.requiringNew().call(() -> load(a).status());
  }

  private String purgedOrUnavailable(GuardApprovalEntity a) {
    if (a.status == GuardApprovalStatus.PENDING) {
      return UNAVAILABLE;
    }
    Instant terminal = a.decidedAt != null ? a.decidedAt : a.expiresAt;
    return terminal != null && !terminal.plus(inputRetention).isAfter(Instant.now()) ? PURGED : UNAVAILABLE;
  }

  /** 明文 context 按 sha256-sorted-json/v1 重算，须同时等于输入行与决策行的 context_hash。 */
  @SuppressWarnings("unchecked")
  private static boolean intact(Map<String, Object> action, GuardDecisionInputEntity in, GuardDecisionEntity d) {
    Object ctx = action.get("context");
    if (ctx != null && !(ctx instanceof Map<?, ?>)) {
      return false;
    }
    String hash = new GuardActionMapper().contextHash((Map<String, Object>) ctx);
    return hash.equals(in.contextHash) && d != null && hash.equals(d.contextHash);
  }

  private InputViewRenderer.RenderedView render(Snapshot s, boolean revealed) {
    return InputViewRenderer.render(s.decision().id, s.input().contextHash, s.action(), s.classification(),
        s.input().marksSource, s.input().inputBinding, s.inputSources(), revealed, REDACTOR);
  }

  /** 决策行的 verified / factIssuers 与决策收据的 factSources（链上原样，字典序）；不含 factsStale。 */
  private Map<String, Object> inputSources(GuardDecisionEntity d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("verified", strings(d.verifiedFields));
    m.put("factIssuers", strings(d.factIssuers));
    m.put("factSources", factSources(d));
    return m;
  }

  private List<String> factSources(GuardDecisionEntity d) {
    if (d.receiptAuditId == null) {
      return List.of();
    }
    AuditLog receipt = AuditLog.findById(d.receiptAuditId);
    Map<String, Object> metadata = receipt == null ? null : parse(receipt.metadata);
    if (metadata == null || !(metadata.get("factSources") instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().filter(String.class::isInstance).map(String.class::cast).sorted().toList();
  }

  private List<String> strings(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, STRINGS);
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private Map<String, Object> parse(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, MAP);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private GuardApprovalInputView toView(GuardApprovalEntity a, Snapshot s, InputViewRenderer.RenderedView v,
                                        boolean revealed, boolean revealedByMe) {
    String decisionId = a.decisionId.toString();
    if (!s.available()) {
      return new GuardApprovalInputView(a.id.toString(), s.decision() == null ? null : s.decision().contextHash,
          decisionId, null, null, s.input() == null ? null : s.input().inputBinding, null, null,
          s.input() == null ? null : s.input().marksSource, InputViewRenderer.MASK_RULES, null, false, revealedByMe,
          s.status());
    }
    GuardDecisionEntity d = s.decision();
    boolean stale = inputSourceResolver.factsStale(d.tenantId, d.factExpiresAt, strings(d.factIssuers));
    return new GuardApprovalInputView(a.id.toString(), s.input().contextHash, decisionId, stale ? Boolean.TRUE : null,
        v.input(), s.input().inputBinding, v.hash(), s.inputSources(), s.input().marksSource,
        InputViewRenderer.MASK_RULES, v.piiMarks(), revealed, revealedByMe, AVAILABLE);
  }

  private static GuardApprovalConflictException notFound(UUID approvalId) {
    return new GuardApprovalConflictException("approval_not_found", "审批不存在: " + approvalId);
  }

  /** 视图状态取值全集（契约用）。 */
  public static final Set<String> STATUSES = Set.of(AVAILABLE, UNAVAILABLE, PURGED, INTEGRITY_FAILED, TOO_LARGE);
}
