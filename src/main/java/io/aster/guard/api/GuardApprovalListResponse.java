package io.aster.guard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * {@code GET /api/v1/guard/approvals} 的响应信封（ADR 0040 §4.2）：字段按字母序输出；
 * {@code items} 为空时仍写出 {@code []}，客户端无需判空。{@code page} / {@code size} 为夹取后的实际取值。
 */
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuardApprovalListResponse(List<GuardApprovalView> items, int page, int size) {

  public GuardApprovalListResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
