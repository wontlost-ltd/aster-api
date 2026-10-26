package io.aster.policy.rest;

import io.aster.security.apikey.InternalCallerSigning;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * /evaluate-source 的墙钟上界（端到端）：指数分支递归策略必须在看门狗上限附近被中止，
 * 回 422 + {@code execution_timeout}，并及时归还并发许可——随后的正常请求照常成功。
 *
 * <p>用缺省上限（5s）而不在测试里改系统属性：TrufflePolicyRuntime 在类加载时读取同一属性，
 * 共享 JVM 里临时改小可能被它“定格”，殃及其它 /evaluate 用例。
 */
@QuarkusTest
@TestProfile(EvaluateSourceWatchdogTest.HmacProfile.class)
class EvaluateSourceWatchdogTest {

    private static final String HMAC_KEY = "it-internal-caller-key-32bytes-min!";
    private static final String PATH = "/api/v1/policies/evaluate-source";
    private static final String TENANT = "tenant-it-watchdog";

    /** 走内部 HMAC 签名通道（与 cloud BFF 相同），不依赖 public / trial 旁路。 */
    public static class HmacProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", HMAC_KEY,
                "aster.security.signature.enabled", "false",
                "aster.security.evaluate-source.public", "false",
                "aster.security.evaluate-source.trial.enabled", "false");
        }
    }

    /** 每层两次自调用：深度 40 ≈ 2^41 次调用、栈深仅 40，不受栈溢出兜底，只能靠墙钟停下。 */
    private static final String EXPONENTIAL_SOURCE =
        "Module watchdog.it.\\n\\n"
            + "Rule boom given n as Int, produce Int:\\n"
            + "  If n equals to 0:\\n"
            + "    Return 1.\\n"
            + "  Return boom(n minus 1) plus boom(n minus 1).";

    /** 缺省上限 5s + 安全点轮询、Context 关闭与 CI 抖动的余量。 */
    private static final long MAX_ELAPSED_MS = 5_000 + 7_000;

    private static String body(int n) {
        return "{\"source\":\"" + EXPONENTIAL_SOURCE + "\",\"context\":{\"n\":" + n + "},"
            + "\"functionName\":\"boom\",\"locale\":\"en-US\"}";
    }

    private static Response post(String body) {
        Map<String, String> headers = InternalCallerSigning.headers(HMAC_KEY, "POST", PATH, "", body,
            TENANT, "MEMBER", "", List.of());
        return given()
            .header("Content-Type", "application/json")
            .headers(headers)
            .body(body)
            .when().post(PATH);
    }

    private static Semaphore permits() throws Exception {
        Field f = PolicyEvaluationResource.class.getDeclaredField("EVAL_SOURCE_PERMITS");
        f.setAccessible(true);
        return (Semaphore) f.get(null);
    }

    /** 许可在 worker 的 finally 中归还，可能略晚于响应写出；短暂轮询等待其回到基线。 */
    private static int awaitPermits(Semaphore permits, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (permits.availablePermits() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return permits.availablePermits();
    }

    @Test
    void 指数递归被中止_回422_并归还许可() throws Exception {
        Semaphore permits = permits();
        int baseline = permits.availablePermits();

        long start = System.currentTimeMillis();
        Response timedOut = post(body(40));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(timedOut.statusCode()).as(timedOut.asString()).isEqualTo(422);
        assertThat(timedOut.jsonPath().getString("code")).isEqualTo("execution_timeout");
        assertThat(elapsed).as("应在墙钟上限附近停下").isLessThan(MAX_ELAPSED_MS);
        assertThat(awaitPermits(permits, baseline)).as("超时后许可必须归还").isEqualTo(baseline);

        // 同一端点随后的正常请求不受影响：2^5 = 32
        Response ok = post(body(5));
        assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
        assertThat(ok.jsonPath().getInt("result")).isEqualTo(32);
        assertThat(awaitPermits(permits, baseline)).isEqualTo(baseline);
    }
}
