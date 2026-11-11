package io.aster.guard;

import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardDecisionResponse.ApprovalView;
import io.aster.guard.api.GuardDecisionResponse.InputSourcesView;
import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.guard.api.GuardDecisionResponse.ReceiptView;
import io.aster.guard.provenance.InputSources;
import io.aster.guard.receipt.ExecutionReceipt;
import io.aster.policy.evidence.AgentIdentity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.aster.guard.GuardText.clip;

/**
 * 决策草稿 → 响应体 / 决策行 / 待审批行的唯一组装处。
 *
 * <p>评估开决策（{@link GuardDecisionService}）与从证据开决策（{@link GuardEvidenceDecisionService}）共用：
 * 两条路径落库的列与响应字段必须一致，审批服务与列表视图才能不区分来源。
 */
final class GuardDecisionAssembler {
  static final int MAX_COLUMN_TEXT = 255;

  private final GuardJson json;

  GuardDecisionAssembler(GuardJson json) {
    this.json = json;
  }

  /** 旧请求体只带 model/session（provider 缺失）时补 unknown；其余走 AgentIdentity 统一校验，非法转 invalid_action。 */
  static AgentIdentity declaredAgent(AgentIdentity raw) {
    if (raw == null) {
      return null;
    }
    try {
      return raw.provider() == null ? AgentIdentity.ofModel(raw.model(), raw.session()) : AgentIdentity.validated(raw);
    } catch (IllegalArgumentException e) {
      throw new GuardInvalidActionException(e.getMessage());
    }
  }

  static GuardDecisionResponse buildResponse(UUID decisionId, GuardDecisionDraft draft, AppendedRecord receipt,
                                             UUID approvalId, Instant expiresAt, ExecutionReceipt executionReceipt) {
    ApprovalView approval = approvalId == null ? null
        : new ApprovalView(expiresAt.toString(), approvalId.toString(), draft.requiredRole(), GuardApprovalStatus.PENDING.name());
    ReceiptView receiptView = receipt == null ? null
        : new ReceiptView(receipt.auditId(), receipt.currentHash(), receipt.prevHash());
    return new GuardDecisionResponse(approval, draft.controls(), decisionId.toString(), draft.effectiveOutcome(),
        executionReceipt, inputSourcesView(draft.inputSources()), draft.outcome(), draft.policyVersion(), draft.reason(),
        draft.reasonCodeValue(), receiptView, draft.role(), draft.ruleId());
  }

  /** verified 为空（全部 declared）时整个对象不写（ADR 0040 §11 D4），无事实的现有调用方响应逐字节不变。 */
  static InputSourcesView inputSourcesView(InputSources sources) {
    if (sources.verified().isEmpty()) {
      return null;
    }
    return new InputSourcesView(List.copyOf(sources.factIssuers()), null, List.copyOf(sources.verified()));
  }

  /**
   * 来源三列（ADR 0040 §11 D4）：verified 路径与签发方为 JSON 字符串数组，空则 null（旧行同为 null = 全 declared / 无事实）。
   * 请求方身份按列宽截到 255：只用于四眼「相等即拒绝」比对，截断只会多拒绝、不会放过同一人
   * （审批人标识本身限 1–255 字符）。策略版本缺失（从证据开的决策）时版本列留空。
   */
  GuardDecisionEntity toEntity(UUID decisionId, GuardDecisionDraft draft, AppendedRecord receipt,
                               GuardDecisionResponse response, String performedBy, String apiKeyId) {
    PolicyVersionView version = draft.policyVersion();
    GuardDecisionEntity e = new GuardDecisionEntity();
    e.id = decisionId;
    e.tenantId = draft.tenantId();
    e.idempotencyKey = draft.idempotencyKey();
    e.policyModule = draft.policyModule();
    e.policyFunction = draft.policyFunction();
    e.policyVersionId = version == null ? null : version.id();
    e.sourceHash = version == null ? null : version.sourceHash();
    e.principalId = draft.principalId();
    e.actionName = draft.actionName();
    e.resourceType = draft.resourceType();
    e.resourceId = draft.resourceId();
    e.contextHash = draft.contextHash();
    e.actionHash = draft.actionHash();
    e.outcome = draft.outcome();
    e.reason = draft.reason();
    e.requiredRole = draft.requiredRole();
    e.reasonCode = draft.reasonCodeValue();
    e.ruleId = clip(draft.ruleId(), MAX_COLUMN_TEXT);
    e.controls = draft.controls().isEmpty() ? null : json.writeJson(draft.controls());
    e.receiptAuditId = receipt == null ? null : receipt.auditId();
    e.receiptHash = receipt == null ? null : receipt.currentHash();
    e.responseJson = json.writeJson(response);
    e.requestedBy = clip(performedBy, MAX_COLUMN_TEXT);
    e.requestedApiKeyId = clip(apiKeyId, MAX_COLUMN_TEXT);
    InputSources sources = draft.inputSources();
    e.verifiedFields = sources.verified().isEmpty() ? null : json.writeJson(sources.verified());
    e.factIssuers = sources.factIssuers().isEmpty() ? null : json.writeJson(sources.factIssuers());
    e.factExpiresAt = sources.factExpiresAt();
    return e;
  }

  static GuardApprovalEntity toApproval(UUID approvalId, UUID decisionId, GuardDecisionDraft draft, Instant expiresAt) {
    GuardApprovalEntity a = new GuardApprovalEntity();
    a.id = approvalId;
    a.tenantId = draft.tenantId();
    a.decisionId = decisionId;
    a.outcome = draft.outcome();
    a.requiredRole = draft.requiredRole();
    a.principalId = draft.principalId();
    a.status = GuardApprovalStatus.PENDING;
    a.expiresAt = expiresAt;
    return a;
  }
}
