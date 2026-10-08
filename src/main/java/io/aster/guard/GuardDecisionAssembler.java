package io.aster.guard;

import io.aster.audit.chain.AuditChainAppender.AppendedRecord;
import io.aster.guard.api.GuardDecisionResponse;
import io.aster.guard.api.GuardDecisionResponse.ApprovalView;
import io.aster.guard.api.GuardDecisionResponse.PolicyVersionView;
import io.aster.guard.api.GuardDecisionResponse.ReceiptView;
import io.aster.policy.evidence.AgentIdentity;

import java.time.Instant;
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
                                             UUID approvalId, Instant expiresAt) {
    ApprovalView approval = approvalId == null ? null
        : new ApprovalView(expiresAt.toString(), approvalId.toString(), draft.requiredRole(), GuardApprovalStatus.PENDING.name());
    ReceiptView receiptView = receipt == null ? null
        : new ReceiptView(receipt.auditId(), receipt.currentHash(), receipt.prevHash());
    return new GuardDecisionResponse(approval, draft.controls(), decisionId.toString(), draft.effectiveOutcome(),
        draft.outcome(), draft.policyVersion(), draft.reason(), draft.reasonCodeValue(), receiptView, draft.role(),
        draft.ruleId());
  }

  /**
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
