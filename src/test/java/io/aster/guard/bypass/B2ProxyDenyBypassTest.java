package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.JsonNode;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;
import java.util.UUID;

import static io.aster.guard.bypass.BypassFixture.DPO_KEY;
import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #466 第 2、4 条在代理层的复现（阶段 B，计划 Task B2）：经钉死版本的已发布 {@code aster-guard-mcp}，
 * 规则 DENY 与等待超时仍 PENDING 的调用都不转发上游（即使审批随后通过）；代理已执行的 ALLOW 决策，
 * 再用低层 API 重放 consume 得 409 {@code receipt_consumed}，上游仍只有一次调用。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class B2ProxyDenyBypassTest {
  private static final String TENANT = BypassFixture.tenant("b2proxy");
  private static final String OTHER = BypassFixture.tenant("b2proxy-other");
  private static final String PROBE = DeployedMapParamProbeTest.MODULE + "/" + DeployedMapParamProbeTest.FUNCTION;

  @Inject BypassFixture api;
  @TestHTTPResource("/") URL baseUrl;
  @TempDir Path tmp;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
  }

  private ProxyHarness proxy(String policy) {
    return ProxyHarness.start(baseUrl.toString(), MEMBER_KEY, TENANT, policy, tmp.resolve("ledger.jsonl"));
  }

  /** ADR 0054 §7 第 2 条在发布包上：规则 DENY → {@code status: denied}，上游未调用，链上有决策、无 consume 尝试。 */
  @Test
  void ruleDenyIsNotForwarded() {
    ProxyHarness proxy = proxy(BypassFixture.DENY_MODULE + "/decide");
    JsonNode read = proxy.run("read").call("read");
    JsonNode guard = ProxyHarness.AgentRun.guard(read);

    assertThat(read.path("isError").asBoolean()).as(read.toString()).isTrue();
    assertThat(guard.path("status").asText()).isEqualTo("denied");
    String decisionId = guard.path("decisionId").asText();
    assertThat(proxy.ledger()).as("上游不得被调用").isEmpty();
    BypassAssertions.decisionRecorded(api, MEMBER_KEY, decisionId, "DENY", null);
    BypassAssertions.noConsumeAttempt(api, MEMBER_KEY, decisionId);
  }

  /**
   * 待审批：代理等到 {@code --approval-timeout}（夹具为 0）仍 PENDING 即返回 {@code status: pending}、不转发；
   * DPO 随后批准也不会让这次调用转发（结构化字段是非授权信息，ADR 0054 §2.2），收据无人消耗。
   */
  @Test
  void pendingIsNotForwardedEvenAfterApproval() {
    ProxyHarness proxy = proxy(PROBE);
    JsonNode del = proxy.run("delete").call("delete");
    JsonNode guard = ProxyHarness.AgentRun.guard(del);

    assertThat(del.path("isError").asBoolean()).as(del.toString()).isTrue();
    assertThat(guard.path("status").asText()).isEqualTo("pending");
    String decisionId = guard.path("decisionId").asText();
    assertThat(guard.path("poll").asText()).isEqualTo("GET /api/v1/guard/decisions/" + decisionId);
    Response approved = api.approve(DPO_KEY, guard.path("approvalId").asText(), "approve after proxy gave up");
    assertThat(approved.statusCode()).as(approved.asString()).isEqualTo(200);

    BypassAssertions.decisionRecorded(api, MEMBER_KEY, decisionId, "ALLOW", null);
    assertThat(proxy.ledger()).as("上游不得被调用").isEmpty();
    BypassAssertions.noConsumeAttempt(api, MEMBER_KEY, decisionId);
  }

  /** #466 第 4 条：代理执行 ALLOW 后，低层重放同一决策的 consume → 409 {@code receipt_consumed}，上游仍一次。 */
  @Test
  void replayConsumeAfterProxyExecutedIs409() {
    ProxyHarness proxy = proxy(PROBE);
    JsonNode refund = proxy.run("refund").call("refund");
    assertThat(refund.path("isError").asBoolean()).as(refund.toString()).isFalse();
    String decisionId = refund.path("meta").path("asterGuard").path("decisionId").asText();
    String attemptId = refund.path("meta").path("asterGuard").path("attemptId").asText();
    String actionHash = api.decision(MEMBER_KEY, decisionId).jsonPath().getString("actionHash");
    assertThat(actionHash).isNotBlank();

    Response replay = api.consume(MEMBER_KEY, decisionId, actionHash, UUID.randomUUID().toString(), "bypass-replayer");

    assertThat(replay.statusCode()).as(replay.asString()).isEqualTo(409);
    assertThat(replay.jsonPath().getString("error")).isEqualTo("receipt_consumed");
    assertThat(replay.jsonPath().getString("executorId")).isEqualTo(ProxyHarness.EXECUTOR_ID);
    assertThat(replay.jsonPath().getString("attemptId")).isEqualTo(attemptId);
    assertThat(proxy.ledger("refund_customer")).hasSize(1);
    BypassAssertions.executedOnceBy(api, MEMBER_KEY, decisionId, attemptId, ProxyHarness.EXECUTOR_ID);
  }
}
