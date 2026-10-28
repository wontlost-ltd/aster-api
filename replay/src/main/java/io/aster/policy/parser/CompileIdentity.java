package io.aster.policy.parser;

import aster.core.identifier.CaseFold;
import aster.core.identifier.VocabularyScopes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 编译产物身份（ADR 0051 §7/§9）：进程内编译缓存键用来区分「用哪版解析算法」与「用哪份词汇」。
 *
 * <p>同一源码在不同词汇或不同解析算法下产出不同 Core IR，缓存键必须同时纳入
 * {@link #toolchainId()} 与 {@link #scopesHash(VocabularyScopes)}，否则会串用编译产物。
 * toolchainId 只含影响编译结果的分量；进程级构建号不在其中，因为缓存不跨进程。
 */
public final class CompileIdentity {

    /** 名称解析算法版本：R1/R2 规则变化时递增。 */
    public static final int RESOLVER_VERSION = 1;

    private CompileIdentity() {
    }

    /** 进程内恒定，类加载时算一次。 */
    private static final String TOOLCHAIN_ID = "abi=" + aster.core.lexicon.LexiconAbiVersion.V1.version
        + ";core=" + coreEngineVersion()
        + ";validator=" + UserAliasValidator.VERSION
        + ";resolver=" + RESOLVER_VERSION
        + ";casefold=" + CaseFold.VERSION;

    /** 影响编译结果的引擎分量：词法 ABI、core 实现版本、别名校验器、解析算法与折叠表版本。 */
    public static String toolchainId() {
        return TOOLCHAIN_ID;
    }

    /** sha256(canonicalJson)；无词汇为空串。 */
    public static String scopesHash(VocabularyScopes scopes) {
        return scopes == null ? "" : sha256(scopes.canonicalJson());
    }

    private static String coreEngineVersion() {
        String v = aster.core.canonicalizer.Canonicalizer.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
