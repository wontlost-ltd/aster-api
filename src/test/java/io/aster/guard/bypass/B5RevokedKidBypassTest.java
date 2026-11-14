package io.aster.guard.bypass;

import io.aster.guard.api.ExecutionReceiptAssert;
import io.aster.guard.api.GuardReceiptResourceAuthTest;
import io.aster.guard.bypass.ReferenceExecutor.Refused;
import io.aster.guard.receipt.GuardReceiptEntity;
import io.aster.test.PostgresTestResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 5 条之撤销 kid（ADR 0040 §12 D6 紧急撤销）：复用 {@link GuardReceiptResourceAuthTest.AuthProfile}
 * （{@code test-b} 为 {@code revoked: true}、active 为 {@code test-a}、{@code revision} 1），不新建第三套 profile。
 *
 * <p>先开 ALLOW（收据由 test-a 签），再把收据行的 kid 改为 test-b，模拟「该收据由已撤销的 kid 签发」——
 * 与 {@code GuardReceiptResourceAuthTest#revokedKidIs409WithRevision} 同一写法。执行方本地缓存的 JWKS 可能仍含该 kid
 * （撤销的最大滞后是 {@code /keys} 的 {@code max-age}），所以撤销的判定点是服务端 consume。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(GuardReceiptResourceAuthTest.AuthProfile.class)
@Tag("guard-bypass")
class B5RevokedKidBypassTest {
  private static final String TENANT = BypassFixture.tenant("b5r");
  private static final String OTHER = BypassFixture.tenant("b5r-other");

  @Inject BypassFixture api;
  private SideEffectLedger ledger;
  private ReferenceExecutor executor;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
    ledger = new SideEffectLedger();
    executor = new ReferenceExecutor(api, MEMBER_KEY, TENANT, ledger, Clock.systemUTC());
  }

  @Test
  void revokedKidIs409AndNotExecuted() {
    String body = BypassFixture.allowBody(250000);
    Response d = api.decide(MEMBER_KEY, body);
    assertThat(d.statusCode()).as(d.asString()).isEqualTo(200);
    assertThat(d.jsonPath().getString("effectiveOutcome")).isEqualTo("ALLOW");
    String decisionId = d.jsonPath().getString("decisionId");
    UUID id = UUID.fromString(decisionId);
    QuarkusTransaction.requiringNew().run(() -> GuardReceiptEntity.update("kid = 'test-b' where decisionId = ?1", id));

    // 本地验签通过（收据由仍在 JWKS 中的 test-a 签），服务端按收据行的 kid 拒绝
    assertThat(executor.run(d.asString(), body, "revoked")).isEqualTo(new Refused("consume", "kid_revoked"));
    assertThat(executor.attackConsume(decisionId, ExecutionReceiptAssert.actionHashOf(body), "revoked"))
        .isEqualTo(new Refused("consume", "kid_revoked"));
    assertThat(executor.lastConsume().statusCode()).isEqualTo(409);
    assertThat(executor.lastConsume().asString()).isEqualTo("{\"error\":\"kid_revoked\",\"kid\":\"test-b\",\"revision\":1}");
    BypassAssertions.notConsumed(api, MEMBER_KEY, decisionId);
    BypassAssertions.noSideEffect(ledger, decisionId);
  }
}
