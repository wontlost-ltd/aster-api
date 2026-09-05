package io.aster.policy.rest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /audit/verify-chain} 端点必须走**分页**校验。
 *
 * <p>★为什么这条要用源码断言而不是行为断言：
 * {@code verifyChain} 与 {@code verifyChainPaginated} 对同一份数据
 * **返回完全相同的结果** —— 差别只在「一次性读进堆」还是「分页读」。
 * 故任何基于返回值的测试都无法区分二者：实测把端点改回无分页版，
 * 全部既有测试依然全绿。
 *
 * <p>这正是此缺陷能长期存在的原因：分页实现写好了、有自己的单元测试、
 * 测试也通过 —— 但**生产路径从不调用它**。
 * 测试证明了「这个方法是对的」，没证明「系统用了这个方法」。
 *
 * <p>本用例守的就是那条接线。它不优雅，但它是唯一能变红的形式。
 *
 * <p>背景：端点上方的 30 天护栏限的是**时间**不是**行数** ——
 * 高流量租户 30 天的审计日志足以 OOM 一个 1 vCPU 的 pod。
 */
class AuditVerifyChainPaginationTest {

    private static final Path RESOURCE = Path.of(
            "src/main/java/io/aster/policy/rest/AuditLogResource.java");

    private String source() throws IOException {
        return Files.readString(RESOURCE, StandardCharsets.UTF_8);
    }

    @Test
    void endpointUsesPaginatedVerifier() throws IOException {
        String src = source();
        assertTrue(src.contains("chainVerifier.verifyChainPaginated("),
                "verify-chain 端点必须调用 verifyChainPaginated —— "
                        + "无分页版会把整个时间窗的审计日志一次性读进堆");
    }

    @Test
    void endpointDoesNotUseUnboundedVerifier() throws IOException {
        String src = source();
        // ★反向断言：光有正向断言不够 —— 两个调用同时存在时正向断言依然通过，
        //   而只要还有一条无分页调用，OOM 向量就没被消除。
        assertFalse(src.contains("chainVerifier.verifyChain(tenantId"),
                "verify-chain 端点仍在调用无分页的 verifyChain");
    }

    @Test
    void pageSizeIsBounded() throws IOException {
        String src = source();
        assertTrue(src.contains("CHAIN_VERIFY_PAGE_SIZE"),
                "分页大小应是具名常量，便于审查与调整");
    }
}
