package io.aster.policy.parser;

import aster.core.identifier.CaseFold;
import aster.core.identifier.VocabularyScopes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 编译产物身份（ADR 0051 §7/§9）：「用哪版解析算法」与「用哪份词汇」的唯一来源。
 *
 * <p>同一源码在不同词汇或不同解析算法下产出不同 Core IR，进程内编译缓存键必须同时纳入
 * {@link #toolchainId()} 与 {@link #scopesHash(VocabularyScopes)}，否则会串用编译产物；
 * 对外的工具链身份（{@code ToolchainIdentityProvider}）与 source envelope 也由这里派生。
 * toolchainId 不含进程级构建号，由对外身份追加 {@code ;build=}。
 *
 * <p>aster-lang-core 只导出折叠表版本（{@link CaseFold#VERSION}），Core IR ABI 与解析算法版本
 * 在 Java 侧没有常量，故在此定义一次，取值须与 aster-lang-ts {@code version.ts} 一致。
 */
public final class CompileIdentity {

    /** Core IR ABI 版本（与 TS CORE_IR_ABI 一致）：按位置解析改变了 IR 契约。 */
    public static final String CORE_IR_ABI = "1.1";

    /** 引擎名：区分 Java 与 TS（cloud 为 ts）实现。 */
    public static final String ENGINE = "java";

    /** 名称解析算法版本：R1/R2 规则变化时递增。 */
    public static final int RESOLVER_VERSION = 1;

    private CompileIdentity() {
    }

    /** 进程内恒定，类加载时算一次。 */
    private static final String TOOLCHAIN_ID = "abi=" + CORE_IR_ABI
        + ";core=" + ENGINE
        + ";validator=" + UserAliasValidator.VERSION
        + ";resolver=" + RESOLVER_VERSION
        + ";casefold=" + CaseFold.VERSION;

    /** 影响编译结果的分量：IR ABI、引擎、别名校验器、解析算法与折叠表版本。 */
    public static String toolchainId() {
        return TOOLCHAIN_ID;
    }

    /**
     * envelope 第 5 段：无词汇（null 或没有任何条目）为空串，不序列化空 scopes 与 schemaVersion；
     * 否则为 canonicalJson（只含字面量宏也算有词汇，宏会改变编译输入）。
     */
    public static String scopesJson(VocabularyScopes scopes) {
        return hasEntries(scopes) ? scopes.canonicalJson() : "";
    }

    /** sha256(scopesJson)；无词汇为空串。 */
    public static String scopesHash(VocabularyScopes scopes) {
        return hasEntries(scopes) ? sha256(scopes.canonicalJson()) : "";
    }

    /** 词汇是否至少有一个条目（任一角色或字面量宏）。 */
    public static boolean hasEntries(VocabularyScopes s) {
        return s != null && !(s.types().isEmpty() && s.fields().isEmpty() && s.enumMembers().isEmpty()
            && s.functions().isEmpty() && s.literals().isEmpty());
    }

    /** 实际加载的 aster-lang-core 版本（jar MANIFEST Implementation-Version），取不到为 dev。 */
    public static String coreEngineVersion() {
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
