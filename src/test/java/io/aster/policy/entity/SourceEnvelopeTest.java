package io.aster.policy.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * source envelope 哈希测试（ADR 0022 §11.5 C1）——纯静态方法，无需 DB。
 *
 * <p>核心命题：envelope 覆盖完整编译输入，**别名变则哈希变** → 堵住"源码哈希对得上、
 * 别名被替换"的篡改窗口（旧 sourceHash 只哈希 content 做不到）。
 */
class SourceEnvelopeTest {

    private static final String CONTENT = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 2.";

    @Test
    void deterministicForSameInputs() {
        String a = PolicyVersion.computeSourceEnvelope(CONTENT, "{\"TIMES\":[\"multiplied by\"]}", "en-US", "abi=1.0;build=x", "");
        String b = PolicyVersion.computeSourceEnvelope(CONTENT, "{\"TIMES\":[\"multiplied by\"]}", "en-US", "abi=1.0;build=x", "");
        assertEquals(a, b, "相同输入必须产出相同 envelope");
        assertEquals(64, a.length(), "SHA-256 hex 64 字符");
    }

    @Test
    void differsWhenAliasSetChanges_antiTamper() {
        // 同 content，别名不同 → envelope 不同（核心防篡改属性）
        String withAliasA = PolicyVersion.computeSourceEnvelope(CONTENT, "{\"TIMES\":[\"multiplied by\"]}", "en-US", "tc", "");
        String withAliasB = PolicyVersion.computeSourceEnvelope(CONTENT, "{\"TIMES\":[\"scaled by\"]}", "en-US", "tc", "");
        assertNotEquals(withAliasA, withAliasB, "别名被替换 → envelope 必须变（防篡改）");
    }

    @Test
    void differsWhenAliasAddedToBareSource() {
        String bare = PolicyVersion.computeSourceEnvelope(CONTENT, null, "en-US", "tc", "");
        String aliased = PolicyVersion.computeSourceEnvelope(CONTENT, "{\"TIMES\":[\"multiplied by\"]}", "en-US", "tc", "");
        assertNotEquals(bare, aliased, "加别名 → envelope 必须变");
    }

    @Test
    void differsWhenToolchainChanges() {
        // 引擎升级（H6）：工具链身份变 → envelope 变 → 可识别非原工具链重编
        String v1 = PolicyVersion.computeSourceEnvelope(CONTENT, null, "en-US", "abi=1.0;build=a", "");
        String v2 = PolicyVersion.computeSourceEnvelope(CONTENT, null, "en-US", "abi=1.0;build=b", "");
        assertNotEquals(v1, v2);
    }

    @Test
    void differsWhenLocaleChanges() {
        String en = PolicyVersion.computeSourceEnvelope(CONTENT, null, "en-US", "tc", "");
        String zh = PolicyVersion.computeSourceEnvelope(CONTENT, null, "zh-CN", "tc", "");
        assertNotEquals(en, zh);
    }

    @Test
    void lengthPrefixPreventsFieldBoundaryAmbiguity() {
        // 长度前缀分隔：("ab","c") 与 ("a","bc") 不能碰撞
        String x = PolicyVersion.computeSourceEnvelope("ab", "c", "", "", "");
        String y = PolicyVersion.computeSourceEnvelope("a", "bc", "", "", "");
        assertNotEquals(x, y, "字段边界不得因拼接产生歧义");
    }

    @Test
    void nullAliasEqualsEmptyButDiffersFromPresent() {
        String nullAlias = PolicyVersion.computeSourceEnvelope(CONTENT, null, "en-US", "tc", "");
        String emptyAlias = PolicyVersion.computeSourceEnvelope(CONTENT, "", "en-US", "tc", "");
        assertEquals(nullAlias, emptyAlias, "null 与空串别名等价（都=无别名）");
    }

    @Test
    void chainLinkUsesEnvelopeWhenPresent() {
        // C1-a：带 envelope 的版本，链接用 envelope（进哈希链）；否则回落 sourceHash。
        PolicyVersion withEnv = new PolicyVersion();
        withEnv.sourceHash = "aaaa";
        withEnv.sourceEnvelopeSha256 = "bbbb";
        assertEquals("bbbb", withEnv.chainLink(), "有 envelope → 链接=envelope");

        PolicyVersion noEnv = new PolicyVersion();
        noEnv.sourceHash = "aaaa";
        noEnv.sourceEnvelopeSha256 = null;
        assertEquals("aaaa", noEnv.chainLink(), "无 envelope → 回落 sourceHash（向后兼容）");
    }
}
