package io.aster.policy.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发闸门的**暴力**测试：打真 HTTP 端点，不是打 PermitLease。
 *
 * <p><b>为什么要有这一层</b>：单测驱动的是 {@code PermitLease} 本身，证明不了
 * REST 方法**接线正确**。审计实证过一次教训——{@code guardSetup}/{@code guardAsync}
 * 曾是零调用点的死代码，生产手写了一份等价逻辑，把生产的归还路径全删掉单测照样全绿。
 * 这个 IT 走完整链路：HMAC 校验 → 闸门 acquire → worker 执行 → 归还。
 *
 * <p><b>判据</b>：数百次并发请求打完、在途请求收敛后，
 * {@code availablePermits()} 必须**精确回到**闸门上限。
 * 少了 = 泄漏（单向累积，最终整站 503）；多了 = 双重释放（悄悄抬高上限）。
 */
@QuarkusTest
@TestProfile(EvaluateSourceGateBruteForceIT.HmacProfile.class)
class EvaluateSourceGateBruteForceIT {

    private static final String PATH = "/api/v1/policies/evaluate-source";
    private static final String HMAC_KEY = "s2-1a-0-characterization-key-32b!";
    private static final String TENANT = "tenant-gate-brute";
    private static final String ROLE = "MEMBER";

    public static class HmacProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aster.plan-gate.hmac-key", HMAC_KEY,
                "aster.internal.signature.enabled", "false"
            );
        }
    }

    // ---------------------------------------------------------------
    // 反射读闸门状态：许可是 private static，生产不该为测试开后门
    // ---------------------------------------------------------------

    private static Semaphore permits() throws Exception {
        Field f = PolicyEvaluationResource.class.getDeclaredField("EVAL_SOURCE_PERMITS");
        f.setAccessible(true);
        return (Semaphore) f.get(null);
    }

    private static int limit() throws Exception {
        Field f = PolicyEvaluationResource.class.getDeclaredField("EVAL_SOURCE_PERMITS_COUNT");
        f.setAccessible(true);
        return (int) f.get(null);
    }

    /** 等在途请求收敛：许可回到上限即停，最多等 timeoutMs。 */
    private static void awaitDrain(int expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (permits().availablePermits() == expected) {
                return;
            }
            Thread.sleep(25);
        }
    }

    // ---------------------------------------------------------------
    // HMAC 签名（与 PolicyEvaluationReplayOrderingTest 同构造）
    // ---------------------------------------------------------------

    private static String sha256Hex(String body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sign(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static Response post(String body, String tag, String query) throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "gate-brute-" + tag + "-" + System.nanoTime();
        String canonical = "POST\n" + PATH + "\n" + ts + "\n" + nonce + "\n"
            + sha256Hex(body) + "\n" + TENANT + "\n" + ROLE;
        return given()
            .header("Content-Type", "application/json")
            .header("X-Internal-Caller", "cloud-bff")
            .header("X-Aster-Timestamp", Long.toString(ts))
            .header("X-Aster-Nonce", nonce)
            .header("X-Internal-Signature", sign(canonical))
            .header("X-Tenant-Id", TENANT)
            .header("X-User-Role", ROLE)
            .body(body)
            .when()
            .post(PATH + query);
    }

    private static final String VALID =
        "{\"source\":\"Module probe.\\nRule main given seed as Int, produce Int:\\n  Return seed plus 1.\","
        + "\"context\":{\"seed\":41},\"functionName\":\"main\",\"locale\":\"en-US\"}";

    /** 编译期就错的源码：走异常路径，验证异常也归还许可。 */
    private static final String MALFORMED =
        "{\"source\":\"Module probe.\\nRule main given seed Int\\n  Return seed.\","
        + "\"context\":{\"seed\":41},\"functionName\":\"main\",\"locale\":\"en-US\"}";

    // ---------------------------------------------------------------

    @Test
    void 并发打满闸门后许可必须精确归位() throws Exception {
        final int limit = limit();
        final int total = 3000;
        assertThat(permits().availablePermits())
            .as("前置：闸门应处于空闲态").isEqualTo(limit);

        ExecutorService pool = Executors.newFixedThreadPool(128);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger busy503 = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger minSeen =
            new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        Thread sampler = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int a = permits().availablePermits();
                    minSeen.updateAndGet(m -> Math.min(m, a));
                    Thread.sleep(1);
                }
            } catch (Exception ignored) { }
        });
        sampler.setDaemon(true);
        sampler.start();
        try {
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    try {
                        // 混合成功与异常路径：异常路径同样必须归还
                        String body = (n % 4 == 3) ? MALFORMED : VALID;
                        int sc = post(body, "load" + n, "?trace=false").statusCode();
                        if (sc == 200) {
                            ok.incrementAndGet();
                        } else if (sc == 503) {
                            busy503.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);
        }

        sampler.interrupt();
        awaitDrain(limit, 30_000);

        System.out.printf("  [闸门暴力] 上限=%d 总请求=%d 200=%d 503=%d 其他=%d 峰值占用=%d%n",
            limit, total, ok.get(), busy503.get(), other.get(), limit - minSeen.get());

        assertThat(ok.get() + busy503.get())
            .as("请求应当么成功、么被闸门拒绝——不该有第三种结局")
            .isEqualTo(total - other.get());

        // ★闸门必须真的被打满过——否则「许可归位」只是「负载太轻没触发」的假通过。
        //   峰值占用由 1ms 采样线程观测，实测能稳定打到 20/20。
        assertThat(limit - minSeen.get())
            .as("闸门未被打满，本次负载不足以证明任何事——调高 total 或线程数")
            .isEqualTo(limit);

        // ★核心断言
        assertThat(permits().availablePermits())
            .as("★许可必须精确归位：少了=泄漏（最终整站 503），多了=双重释放（悄悄抬高上限）")
            .isEqualTo(limit);
    }

    @Test
    void 反复发起再取消不得绕过闸门() throws Exception {
        // ★这是闸门要防的攻击本身：若取消会提前归还许可，
        //   而 worker 仍在烧 CPU，则反复「发起再取消」可让实际并发远超上限。
        final int limit = limit();
        assertThat(permits().availablePermits()).isEqualTo(limit);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 120; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    try {
                        // 极短超时 → 客户端侧断连，服务端 worker 可能仍在跑
                        given()
                            .config(io.restassured.RestAssured.config()
                                .httpClient(io.restassured.config.HttpClientConfig.httpClientConfig()
                                    .setParam("http.socket.timeout", 15)
                                    .setParam("http.connection.timeout", 15)))
                            .header("Content-Type", "application/json")
                            .header("X-Internal-Caller", "cloud-bff")
                            .body(VALID)
                            .when()
                            .post(PATH);
                    } catch (Exception expected) {
                        // 超时/断连是本用例的目的
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);
        }

        awaitDrain(limit, 60_000);

        System.out.printf("  [取消暴力] 上限=%d 结束许可=%d%n", limit, permits().availablePermits());
        assertThat(permits().availablePermits())
            .as("★反复取消后许可必须完整归位——否则闸门可被当作 DoS 放大器")
            .isEqualTo(limit);
    }
}
