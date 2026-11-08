package io.aster.guard.provenance;

import io.aster.guard.api.GuardActionRequest;
import io.aster.guard.provenance.FactVerifier.VerifiedFacts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 逐字段来源判定（ADR 0040 §11 D1 / D2）与重放时的事实失效判定（§11 D4）。
 *
 * <p>verified 的来源只有两条：(a) 服务端解析——{@code principal.type == "user"} 且 {@code principal.id} 等于提交者
 * {@code performedBy}，此时 {@code principal.roles} 每一项都在提交者的已验证业务角色内才 verified（数组是一个叶子）；
 * (b) 已验签的签名事实覆盖的路径。两者取并集，任何来源都只会把路径提升为 verified。
 * from-evidence 的 (c)（HMAC 签过的 userId）即 (a) 的同一判定：performedBy 就是签名内的用户。
 */
@ApplicationScoped
public class InputSourceResolver {
  public static final String PRINCIPAL_ID = "principal.id";
  public static final String PRINCIPAL_ROLES = "principal.roles";
  static final String USER_TYPE = "user";
  /** 未经任何认证通道时 performedBy 的终态（{@code RequestIdentityResolver}）：没有可证实的身份。 */
  private static final Set<String> UNVERIFIED_ACTORS = Set.of("anonymous", "trial-anonymous");

  private final FactIssuerStore store;
  private final Clock clock;

  @Inject
  public InputSourceResolver(FactIssuerStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * 判定本次请求的来源。{@code facts} 须已由 {@link FactVerifier#verify} 验签（失败的请求到不了这里）。
   *
   * @param performedBy   提交者（{@code RequestIdentityResolver.performedBy()}）
   * @param businessRoles 提交者的已验证业务角色（{@code RequestIdentityResolver.businessRoles()}）
   */
  public static InputSources resolve(GuardActionRequest.Action a, VerifiedFacts facts, String performedBy,
                                     Set<String> businessRoles) {
    GuardActionRequest.Principal principal = a == null ? null : a.principal();
    SortedSet<String> verified = new TreeSet<>(facts.paths());
    Boolean identityMatched = null;
    if (principal != null && USER_TYPE.equals(principal.type())) {
      identityMatched = isVerifiedActor(performedBy) && performedBy.equals(principal.id());
    }
    if (Boolean.TRUE.equals(identityMatched)) {
      verified.add(PRINCIPAL_ID);
      if (rolesVerified(principal.roles(), businessRoles)) {
        verified.add(PRINCIPAL_ROLES);
      }
    }
    return new InputSources(verified, facts.issuerKids(), facts.sourcesByPath(), identityMatched,
        facts.earliestExpiresAt());
  }

  /**
   * 重放 / 回读时首次决策的事实是否已失效：{@code now >= factExpiresAt}（排他），或任一记录的签发方
   * 现已不存在、{@code enabled=false} 或 {@code now >= notAfter}。无事实的决策恒为 false，不查表。
   */
  public boolean factsStale(String tenantId, Instant factExpiresAt, Collection<String> issuerKids) {
    if (factExpiresAt == null && issuerKids.isEmpty()) {
      return false;
    }
    Instant now = clock.instant();
    if (factExpiresAt != null && !now.isBefore(factExpiresAt)) {
      return true;
    }
    return issuerKids.stream().anyMatch(issuerKid -> !issuerUsable(tenantId, issuerKid, now));
  }

  /** {@code issuer#kid}：issuer 与 kid 都不含 {@code #}（签发方登记时校验），按首个 {@code #} 拆分。 */
  private boolean issuerUsable(String tenantId, String issuerKid, Instant now) {
    int hash = issuerKid.indexOf('#');
    if (hash < 0) {
      return false;
    }
    return store.find(tenantId, issuerKid.substring(0, hash), issuerKid.substring(hash + 1))
        .map(row -> row.enabled && (row.notAfter == null || now.isBefore(row.notAfter)))
        .orElse(false);
  }

  /** 空数组没有可证实的内容，不标 verified（与缺省 roles 一致，避免无意义地改变响应）。 */
  private static boolean rolesVerified(List<String> roles, Set<String> businessRoles) {
    return roles != null && !roles.isEmpty() && businessRoles != null && businessRoles.containsAll(roles);
  }

  private static boolean isVerifiedActor(String performedBy) {
    return performedBy != null && !performedBy.isBlank() && !UNVERIFIED_ACTORS.contains(performedBy);
  }
}
