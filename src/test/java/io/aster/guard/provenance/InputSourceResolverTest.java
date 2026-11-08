package io.aster.guard.provenance;

import io.aster.guard.api.GuardActionRequest.Action;
import io.aster.guard.api.GuardActionRequest.ActionName;
import io.aster.guard.api.GuardActionRequest.Principal;
import io.aster.guard.provenance.FactVerifier.VerifiedFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR 0040 §11 D1 / D2 / D4：逐字段来源判定与重放时的事实失效判定（纯 JUnit + Mockito）。 */
class InputSourceResolverTest {
  private static final String TENANT = "tenant-a";
  private static final String USER = "user-7";
  private static final String ISSUER_KID = "idp.example.com#2026-11";
  private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

  private FactIssuerStore store;

  @BeforeEach
  void setUp() {
    store = mock(FactIssuerStore.class);
  }

  private static Action action(String id, String type, List<String> roles) {
    return new Action(new Principal(id, type, roles), new ActionName("refund"), null, Map.of("amount", 1), null);
  }

  private static VerifiedFacts facts(String issuerKid, Instant expiresAt, Map<String, String> sourcesByPath) {
    SortedSet<String> paths = new TreeSet<>(sourcesByPath.keySet());
    SortedMap<String, SortedSet<String>> issuersByPath = new TreeMap<>();
    paths.forEach(p -> issuersByPath.put(p, new TreeSet<>(Set.of(issuerKid))));
    return new VerifiedFacts(paths, issuersByPath, new TreeSet<>(Set.of(issuerKid)), new TreeMap<>(sourcesByPath),
        expiresAt);
  }

  private InputSourceResolver resolverAt(Instant now) {
    return new InputSourceResolver(store, Clock.fixed(now, ZoneOffset.UTC));
  }

  private void issuerRow(boolean enabled, Instant notAfter) {
    GuardFactIssuerEntity row = new GuardFactIssuerEntity();
    row.tenantId = TENANT;
    row.issuer = "idp.example.com";
    row.kid = "2026-11";
    row.enabled = enabled;
    row.notAfter = notAfter;
    when(store.find(TENANT, "idp.example.com", "2026-11")).thenReturn(Optional.of(row));
  }

  @Test
  void userPrincipalEqualToPerformedByIsVerified() {
    InputSources s = InputSourceResolver.resolve(action(USER, "user", null), VerifiedFacts.EMPTY, USER, Set.of());

    assertThat(s.verified()).containsExactly("principal.id");
    assertThat(s.principalIdentityMatched()).isTrue();
    assertThat(s.factIssuers()).isEmpty();
    assertThat(s.factSources()).isEmpty();
    assertThat(s.factExpiresAt()).isNull();
    assertThat(s.isVerified("principal.id")).isTrue();
    assertThat(s.isVerified("context.amount")).isFalse();
  }

  @Test
  void otherIdOrAgentTypeIsDeclared() {
    InputSources otherId = InputSourceResolver.resolve(action("user-8", "user", null), VerifiedFacts.EMPTY, USER, Set.of());
    InputSources agentType = InputSourceResolver.resolve(action(USER, "agent", null), VerifiedFacts.EMPTY, USER, Set.of());
    InputSources defaultType = InputSourceResolver.resolve(action(USER, null, null), VerifiedFacts.EMPTY, USER, Set.of());
    InputSources anonymous = InputSourceResolver.resolve(action("anonymous", "user", null), VerifiedFacts.EMPTY,
        "anonymous", Set.of());
    InputSources trial = InputSourceResolver.resolve(action("trial-anonymous", "user", null), VerifiedFacts.EMPTY,
        "trial-anonymous", Set.of());

    assertThat(otherId.verified()).isEmpty();
    assertThat(otherId.principalIdentityMatched()).isFalse();
    // principal.type 非 user（含缺省即 agent）：不做 (a) 判定，principalIdentityMatched 不写
    assertThat(agentType.verified()).isEmpty();
    assertThat(agentType.principalIdentityMatched()).isNull();
    assertThat(defaultType.verified()).isEmpty();
    assertThat(defaultType.principalIdentityMatched()).isNull();
    // 无已验证身份的提交者不能「证实」任何主体
    assertThat(anonymous.verified()).isEmpty();
    assertThat(anonymous.principalIdentityMatched()).isFalse();
    assertThat(trial.verified()).isEmpty();
    assertThat(InputSources.NONE.verified()).isEmpty();
  }

  @Test
  void rolesVerifiedOnlyIfSubsetOfBusinessRoles() {
    Set<String> business = Set.of("ops", "finance");

    InputSources subset = InputSourceResolver.resolve(action(USER, "user", List.of("ops")), VerifiedFacts.EMPTY, USER,
        business);
    InputSources oneOutside = InputSourceResolver.resolve(action(USER, "user", List.of("ops", "admin")),
        VerifiedFacts.EMPTY, USER, business);
    InputSources otherPrincipal = InputSourceResolver.resolve(action("user-8", "user", List.of("ops")),
        VerifiedFacts.EMPTY, USER, business);
    InputSources emptyRoles = InputSourceResolver.resolve(action(USER, "user", List.of()), VerifiedFacts.EMPTY, USER,
        business);

    assertThat(subset.verified()).containsExactly("principal.id", "principal.roles");
    // 数组是一个叶子：有一项不在已验证业务角色内 → 整个 principal.roles 为 declared
    assertThat(oneOutside.verified()).containsExactly("principal.id");
    assertThat(otherPrincipal.verified()).isEmpty();
    assertThat(emptyRoles.verified()).containsExactly("principal.id");
  }

  @Test
  void factsAndServerResolutionUnion() {
    VerifiedFacts v = facts(ISSUER_KID, NOW.plusSeconds(600), Map.of("context.amount", "ledger.amount"));

    InputSources s = InputSourceResolver.resolve(action(USER, "user", List.of("ops")), v, USER, Set.of("ops"));

    assertThat(s.verified()).containsExactly("context.amount", "principal.id", "principal.roles");
    assertThat(s.factIssuers()).containsExactly(ISSUER_KID);
    assertThat(s.factSources()).containsExactly(Map.entry("context.amount", "ledger.amount"));
    assertThat(s.factSourceEntries()).containsExactly("context.amount=ledger.amount");
    assertThat(s.factExpiresAt()).isEqualTo(NOW.plusSeconds(600));
  }

  @Test
  void principalIdentityMatchedRecordedEvenWhenFactCovers() {
    VerifiedFacts v = facts(ISSUER_KID, NOW.plusSeconds(600), Map.of("principal.id", "idp.sub"));

    InputSources matched = InputSourceResolver.resolve(action(USER, "user", null), v, USER, Set.of());
    InputSources unmatched = InputSourceResolver.resolve(action(USER, "user", null), v, "someone-else", Set.of());

    assertThat(matched.verified()).containsExactly("principal.id");
    assertThat(matched.principalIdentityMatched()).isTrue();
    // 签发方说主体是 USER，但服务端没有证实提交者就是 USER：仍 verified（事实覆盖），identityMatched 记 false
    assertThat(unmatched.verified()).containsExactly("principal.id");
    assertThat(unmatched.principalIdentityMatched()).isFalse();
  }

  @Test
  void factCoveringParentObjectVerifiesItsLeaves() {
    VerifiedFacts v = facts(ISSUER_KID, NOW.plusSeconds(600), Map.of("context.customer", "crm.customer"));

    InputSources s = InputSourceResolver.resolve(action("agent-1", "agent", null), v, USER, Set.of());

    assertThat(s.isVerified("context.customer")).isTrue();
    assertThat(s.isVerified("context.customer.tier")).isTrue();
    assertThat(s.isVerified("context.customerTier")).isFalse();
    assertThat(s.isVerified("context")).isFalse();
  }

  @Test
  void noFactsAreNeverStale() {
    assertThat(resolverAt(NOW).factsStale(TENANT, null, List.of())).isFalse();
    verifyNoInteractions(store);
  }

  /** fact_expires_at 排他：now == expiresAt 即 stale。 */
  @Test
  void staleAtOrAfterFactExpiry() {
    issuerRow(true, null);
    Instant expires = NOW.plusSeconds(60);

    assertThat(resolverAt(expires.minusMillis(1)).factsStale(TENANT, expires, List.of(ISSUER_KID))).isFalse();
    assertThat(resolverAt(expires).factsStale(TENANT, expires, List.of(ISSUER_KID))).isTrue();
    assertThat(resolverAt(expires.plusSeconds(1)).factsStale(TENANT, expires, List.of(ISSUER_KID))).isTrue();
  }

  @Test
  void staleWhenIssuerDisabledPastNotAfterOrGone() {
    Instant expires = NOW.plusSeconds(600);

    issuerRow(false, null);
    assertThat(resolverAt(NOW).factsStale(TENANT, expires, List.of(ISSUER_KID))).isTrue();
    issuerRow(true, NOW);
    assertThat(resolverAt(NOW).factsStale(TENANT, expires, List.of(ISSUER_KID))).isTrue();
    issuerRow(true, NOW.plusMillis(1));
    assertThat(resolverAt(NOW).factsStale(TENANT, expires, List.of(ISSUER_KID))).isFalse();
    when(store.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    assertThat(resolverAt(NOW).factsStale(TENANT, expires, List.of(ISSUER_KID))).isTrue();
  }
}
