package io.aster.security.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部调用签名的**全仓契约**（issue #231）。
 *
 * <p><b>为什么需要这道门禁</b>：cloud 侧于 2026-08-01 关闭 v1 兼容窗口时，
 * 盘点结论是「aster-api 侧唯一签名实现 InternalCallSigner 只产 v2」——
 * <b>但那句话当时就不准确</b>：{@code PlanGateService} 与
 * {@code SnapshotWarmupService} 都在手写 v1 canonical，没走共享实现。
 *
 * <p>后果被 fail-open 静默吞掉：401 → {@code PlanInfo.failOpen()} → 返回 pro 档，
 * 于是**权益判定一直在放行**，没有异常、没有告警。
 * 这个 bug 是做 What-If 端到端验证时才发现的——<b>单测全绿</b>，
 * 因为它是跨服务契约，不是任一侧的内部逻辑。
 *
 * <p>本测试锁住：任何人再手写 v1 canonical，构建就红。
 */
class InternalSigningContractTest {

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * v1 canonical 的特征：{@code "METHOD\n" + path + "\n" + timestamp}。
     *
     * <p>只三段、不含 nonce 与 bodyHash——300s 时钟窗内可原样换 body 重放。
     */
    private static final List<String> V1_MARKERS = List.of(
        "\"GET\\n\" + path",
        "\"POST\\n\" + path",
        "\"PUT\\n\" + path",
        "\"DELETE\\n\" + path"
    );

    /**
     * 允许保留 v1 的**入站验签**类。
     *
     * <p>★这些是 api **接收** cloud 调用的方向，cloud 侧同样发 v1
     * （见 aster-cloud {@code src/lib/plan-gate-client.ts}）——
     * 两端匹配，当前工作正常。
     *
     * <p>它们**仍是可重放的**（v1 不绑 body/nonce），应当作为独立工作项
     * 双端一起升到 v2；但那需要协调发布顺序，不在 issue #231 的范围内。
     * 此处显式登记而非放宽扫描规则，使这笔债**可见**。
     */
    private static final List<String> INBOUND_V1_VERIFIERS = List.of(
        "ApiKeyCacheResource.java",
        "PlanCacheResource.java"
    );

    private static List<String> scanForV1() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // 共享签名实现自身允许出现 canonical 片段
                if (f.endsWith("InternalCallSigner.java")) {
                    continue;
                }
                String name = f.getFileName().toString();
                if (INBOUND_V1_VERIFIERS.contains(name)) {
                    continue;
                }
                String src = Files.readString(f);
                for (String marker : V1_MARKERS) {
                    if (src.contains(marker)) {
                        hits.add(MAIN.relativize(f).toString() + " —— " + marker);
                    }
                }
            }
        }
        return hits;
    }

    @Test
    void 全仓不得手写v1_canonical() throws Exception {
        assertThat(scanForV1())
            .as("★内部调用签名必须走 InternalCallSigner（只产 v2：nonce + bodySha256）。"
                + "手写 v1 会被 cloud 以 invalid_signature 401 拒绝，"
                + "而 PlanGate 的 fail-open 会把它静默转成 pro 档权益（issue #231）")
            .isEmpty();
    }

    @Test
    void 曾经出问题的两个类现在走共享签名() throws Exception {
        for (String rel : List.of(
            "io/aster/billing/PlanGateService.java",
            "io/aster/billing/snapshot/SnapshotWarmupService.java")) {
            String src = Files.readString(MAIN.resolve(rel));
            assertThat(src)
                .as("%s 必须走 InternalCallSigner", rel)
                .contains("InternalCallSigner.sign(");
            assertThat(src)
                .as("%s 必须发送 nonce 头（v2 的组成部分）", rel)
                .contains("X-Aster-Nonce");
        }
    }

    @Test
    void v2签名的path不得含query() {
        // ★cloud 侧 verifyInternalSignature 用的是 url.pathname——
        //   把 query 签进去会与验签端对不上。
        //   这条我自己踩过：最初探测 snapshot/full?limit=10 时把 query 也签了，
        //   于是误判「连 v2 都被拒」，实际是我的测试错了。
        InternalCallSigner.Signed a = InternalCallSigner.sign(
            "k".repeat(32), "GET", "/api/internal/snapshot/full", "");
        InternalCallSigner.Signed b = InternalCallSigner.sign(
            "k".repeat(32), "GET", "/api/internal/snapshot/full?limit=10", "");

        assertThat(a.signature())
            .as("含 query 与不含 query 必须产出不同签名——"
                + "提醒调用方只能传 pathname")
            .isNotEqualTo(b.signature());
    }

    @Test
    void 签名三要素齐备() {
        InternalCallSigner.Signed s = InternalCallSigner.sign(
            "k".repeat(32), "GET", "/api/internal/tenant/t1/plan", "");

        assertThat(s.timestamp()).as("时间戳").isNotBlank();
        assertThat(s.nonce()).as("★nonce——v1 缺的正是它，导致可重放").isNotBlank();
        assertThat(s.signature()).as("签名").isNotBlank();
    }

    @Test
    void nonce每次不同() {
        // nonce 的**不可预测性正是安全诉求**：相同即退化成可重放
        String key = "k".repeat(32);
        String n1 = InternalCallSigner.sign(key, "GET", "/p", "").nonce();
        String n2 = InternalCallSigner.sign(key, "GET", "/p", "").nonce();
        assertThat(n1).isNotEqualTo(n2);
    }
}
