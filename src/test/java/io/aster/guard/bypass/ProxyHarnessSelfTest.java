package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.JsonNode;
import io.aster.guard.DeployedMapParamProbeTest;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;

import static io.aster.guard.bypass.BypassFixture.MEMBER_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 B 夹具自检（#466，计划 Task B1）：ALLOW 的调用经已发布代理真的转发到上游、恰好一次，
 * 链上恰一条权威 consume 与一条 executed 回写——否则其它代理层用例里「上游账本为空」就证明不了什么。
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(BypassProfile.class)
@Tag("guard-bypass")
class ProxyHarnessSelfTest {
  private static final String TENANT = BypassFixture.tenant("proxy-self");
  private static final String OTHER = BypassFixture.tenant("proxy-self-other");

  @Inject BypassFixture api;
  @TestHTTPResource("/") URL baseUrl;
  @TempDir Path tmp;
  private ProxyHarness proxy;

  @BeforeEach
  void setUp() {
    api.reset(TENANT, OTHER);
    api.seedKeys(TENANT, OTHER);
    proxy = ProxyHarness.start(baseUrl.toString(), MEMBER_KEY, TENANT,
        DeployedMapParamProbeTest.MODULE + "/" + DeployedMapParamProbeTest.FUNCTION, tmp.resolve("ledger.jsonl"));
  }

  @Test
  void allowedReadIsForwardedOnce() {
    JsonNode read = proxy.run("read").call("read");

    assertThat(read.path("isError").asBoolean()).as(read.toString()).isFalse();
    assertThat(read.path("text").asText()).contains("C-1001");
    JsonNode grant = read.path("meta").path("asterGuard");
    String decisionId = grant.path("decisionId").asText();
    String attemptId = grant.path("attemptId").asText();
    assertThat(decisionId).as(read.toString()).isNotBlank();
    assertThat(attemptId).isNotBlank();

    assertThat(proxy.ledger()).singleElement().satisfies(line -> {
      assertThat(line.tool()).isEqualTo("read_customer_record");
      assertThat(line.argumentsJson()).isEqualTo("{\"id\":\"C-1001\"}");
    });
    BypassAssertions.decisionRecorded(api, MEMBER_KEY, decisionId, "ALLOW", null);
    BypassAssertions.executedOnceBy(api, MEMBER_KEY, decisionId, attemptId, ProxyHarness.EXECUTOR_ID);
  }
}
