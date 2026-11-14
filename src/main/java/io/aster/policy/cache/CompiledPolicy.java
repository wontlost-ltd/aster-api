package io.aster.policy.cache;

import io.aster.policy.compiler.CompilationMetadata;
import io.aster.policy.governance.RuleMetadata;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 编译后的策略缓存结构
 *
 * 缓存编译后的策略，避免重复编译。
 * 包含版本信息、源码哈希、Core JSON 和元数据。
 */
public class CompiledPolicy {
    private final String versionId;
    /** 版本源码内容哈希（{@code PolicyVersion.sourceHash}），随评估结果进证据事件。 */
    private final String sourceHash;
    /** 完整编译输入的信封哈希（{@code PolicyVersion.sourceEnvelopeSha256}），旧版本为 null。 */
    private final String sourceEnvelopeSha256;
    private final String coreJson;
    private final CompilationMetadata metadata;
    private final Instant compiledAt;
    /** 非空表示该版本无法执行所请求的函数（确定性失败），coreJson 为 null。 */
    private final String entryFailure;
    /**
     * 按函数名缓存的规则元数据：每个编译产物只解析一次 Core IR。随产物一起被缓存淘汰，条目数不超过模块的函数数，
     * 不另设容量上限；元数据只取决于不可变的 coreJson 与随 jar 固定的注册表，所以不需要失效。
     */
    private final ConcurrentHashMap<String, RuleMetadata> ruleMetadataByFunction = new ConcurrentHashMap<>();
    /**
     * 按函数名缓存的 guard 形状输入分类（ADR 0042 §12 D2）：与 {@link #ruleMetadataByFunction} 同一生命周期与理由。
     * 推导结果可为 null（超上限 / 非记录参数），以 Optional 存放（ConcurrentHashMap 不收 null 值）。
     */
    private final ConcurrentHashMap<String, java.util.Optional<io.aster.policy.governance.InputClassification>>
        guardClassificationByFunction = new ConcurrentHashMap<>();

    public CompiledPolicy(
        String versionId,
        String sourceHash,
        String coreJson,
        CompilationMetadata metadata
    ) {
        this(versionId, sourceHash, null, coreJson, metadata, null);
    }

    public CompiledPolicy(
        String versionId,
        String sourceHash,
        String sourceEnvelopeSha256,
        String coreJson,
        CompilationMetadata metadata
    ) {
        this(versionId, sourceHash, sourceEnvelopeSha256, coreJson, metadata, null);
    }

    private CompiledPolicy(
        String versionId,
        String sourceHash,
        String sourceEnvelopeSha256,
        String coreJson,
        CompilationMetadata metadata,
        String entryFailure
    ) {
        this.versionId = versionId;
        this.sourceHash = sourceHash;
        this.sourceEnvelopeSha256 = sourceEnvelopeSha256;
        this.coreJson = coreJson;
        this.metadata = metadata;
        this.compiledAt = Instant.now();
        this.entryFailure = entryFailure;
    }

    /**
     * 该版本的入口函数选择失败（函数不存在 / 被 main 抢占）。对同一 versionId 结果是确定的，
     * 缓存下来避免配置错误的部署在每个请求上都重新加载并编译。
     */
    public static CompiledPolicy entryFailure(String versionId, String sourceHash, String message) {
        return new CompiledPolicy(versionId, sourceHash, null, null, null, message);
    }

    /** 取该产物中函数 {@code function} 的 guard 形状输入分类；首次调用时用 {@code compute} 算出并记住（可为 null）。 */
    public io.aster.policy.governance.InputClassification guardClassification(String function,
            Function<String, io.aster.policy.governance.InputClassification> compute) {
        return guardClassificationByFunction
            .computeIfAbsent(function, fn -> java.util.Optional.ofNullable(compute.apply(fn)))
            .orElse(null);
    }

    /**
     * 取该产物中函数 {@code function} 的规则元数据；首次调用时用 {@code compute} 从 coreJson 算出并记住。
     */
    public RuleMetadata ruleMetadata(String function, Function<String, RuleMetadata> compute) {
        return ruleMetadataByFunction.computeIfAbsent(function, compute);
    }

    /** 入口选择失败的原因；成功编译时为 null。 */
    public String getEntryFailure() {
        return entryFailure;
    }

    public String getVersionId() {
        return versionId;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public String getSourceEnvelopeSha256() {
        return sourceEnvelopeSha256;
    }

    public String getCoreJson() {
        return coreJson;
    }

    public CompilationMetadata getMetadata() {
        return metadata;
    }

    public Instant getCompiledAt() {
        return compiledAt;
    }

    @Override
    public String toString() {
        return "CompiledPolicy{" +
            "versionId='" + versionId + '\'' +
            ", sourceHash='" + sourceHash + '\'' +
            ", compiledAt=" + compiledAt +
            '}';
    }
}
