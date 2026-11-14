package io.aster.guard.deploy;

import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aster.audit.chain.AuditChainAppender;
import io.aster.guard.GuardReceiptBuilder;
import io.aster.guard.deploy.PolicyDeployment.DeployResult;
import io.aster.guard.deploy.PolicyDeployment.Deployed;
import io.aster.guard.deploy.PolicyDeployment.Rejected;
import io.aster.guard.deploy.PolicyDeployment.Withdrawn;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.event.EventType;
import io.aster.policy.parser.UserAliasValidator;
import io.aster.policy.rest.model.VocabularyBounds;
import io.aster.policy.service.PolicyVersionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * cloud 策略部署（ADR 0054 §11 D1，R66）：把 cloud 某策略的默认版本部署为 aster-api 的活跃 {@link PolicyVersion}，
 * 让 {@code POST /guard/decisions} 的 {@code policyModule / policyFunction} 能解析到它；或把它撤回。
 *
 * <p>顺序：校验请求与 {@code sourceSha256} → 冻结词汇与别名 → 用同一（源码、别名、词汇）编译并核对模块头与入口函数
 * →（单一事务）按 cloud 策略与地址取 advisory 锁 → 拒绝旧代际 → 地址归属 → 按 cloud 版本 id 建版本或复用已有行
 * → 直接批准（保留稳定性门禁）→ 激活（乐观锁并发保护）→ 记代际 → 同事务入链 {@code POLICY_DEPLOYED}。
 * 任何一步失败整体回滚：不留半成品行，现活跃版本不受影响，同一请求可原样重试。
 *
 * <p>锁顺序：cloud 策略锁 → 地址锁 → 版本链锁（{@code createVersion} 内）→ 版本 / 目录行锁 → 租户审计链锁（最后）。
 * 同一 cloud 策略的部署 / 撤回由第一把锁串行，代际比较因此是原子的。
 */
@ApplicationScoped
public class PolicyDeploymentService {

  /** 外部标识（cloud 策略 / 版本 id）的上限，与列宽一致。 */
  static final int MAX_REF = 100;
  /** 地址分量上限，与 {@code policy_catalog} 列宽一致。 */
  static final int MAX_NAME = 200;
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final ObjectMapper CANONICAL = new ObjectMapper()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  @Inject PolicyVersionService versionService;
  @Inject PolicyCompiler compiler;
  @Inject AuditChainAppender appender;
  @Inject GuardReceiptBuilder receiptBuilder;
  @Inject EntityManager entityManager;

  /** 部署；返回 {@link Deployed} 或 {@link Rejected}，不抛业务异常。 */
  public DeployResult deploy(String tenantId, String performedBy, PolicyDeployment d) {
    try {
      Prepared prepared = prepare(d);
      return QuarkusTransaction.requiringNew().call(() -> apply(tenantId, performedBy, d, prepared));
    } catch (Rejection r) {
      return r.rejected;
    } catch (WebApplicationException e) {
      return mapGate(e);
    }
  }

  /** 撤回该 cloud 策略的全部地址；无此部署 404，旧代际 409。 */
  public DeployResult withdraw(String tenantId, String performedBy, String cloudPolicyId, long generation) {
    try {
      requireRef("cloudPolicyId", cloudPolicyId);
      requireGeneration(generation);
      return QuarkusTransaction.requiringNew().call(() -> applyWithdraw(tenantId, performedBy, cloudPolicyId, generation));
    } catch (Rejection r) {
      return r.rejected;
    }
  }

  /** 冻结输入与编译产物：全部在事务外完成（编译是 CPU 密集，不占连接）。 */
  private record Prepared(String aliasSetJson, String vocabularyJson, String coreJson) {}

  private Prepared prepare(PolicyDeployment d) {
    requireRef("cloudPolicyId", d.cloudPolicyId());
    requireRef("cloudVersionId", d.cloudVersionId());
    requireGeneration(d.generation());
    requireName("moduleName", d.moduleName());
    requireName("functionName", d.functionName());
    if (d.source() == null || d.source().isBlank()) {
      throw invalid("缺少 source");
    }
    if (d.locale() == null || d.locale().isBlank()) {
      throw invalid("缺少 locale");
    }
    if (d.sourceSha256() == null || !SHA256_HEX.matcher(d.sourceSha256()).matches()) {
      throw invalid("sourceSha256 须为 64 位小写 hex");
    }
    if (!sha256(d.source()).equals(d.sourceSha256())) {
      throw invalid("sourceSha256 与源码 UTF-8 字节的 sha256 不符");
    }
    VocabularyScopes scopes = freezeVocabularyScopes(d.vocabulary());
    String vocabularyJson = scopes == null ? null : canonicalJson(d.vocabulary());
    String aliasSetJson = freezeAliasSet(d.aliasSet(), d.locale(), scopes);
    CompilationResult compiled = compiler.compile(d.source(), d.locale(), aliasSetJson, scopes, false);
    if (!compiled.isSuccess()) {
      throw new Rejection(new Rejected(422, "compile_failed", "源码编译失败",
          Map.of("errors", compiled.getErrors(), "diagnostics", compiled.getDiagnostics())));
    }
    requireAddress(compiled.getCoreJson(), d.moduleName(), d.functionName());
    return new Prepared(aliasSetJson, vocabularyJson, compiled.getCoreJson());
  }

  private DeployResult apply(String tenantId, String performedBy, PolicyDeployment d, Prepared p) {
    lock("guard-deploy:" + tenantId + ':' + d.cloudPolicyId());
    lock("guard-deploy-address:" + tenantId + ':' + d.moduleName() + '.' + d.functionName());
    List<PolicyCatalog> owned = ownedCatalogs(tenantId, d.cloudPolicyId());
    if (rejectStale(owned, d.generation()) && !deployedAt(owned, d)) {
      throw sameGenerationConflict();
    }
    PolicyCatalog target = targetCatalog(tenantId, d);
    for (PolicyCatalog other : owned) {
      if (!other.id.equals(target.id)) {
        // 新默认版本的模块头 / 入口改了地址：旧地址不得继续按旧版本放行。
        versionService.withdrawCatalog(other);
        other.externalGeneration = d.generation();
      }
    }
    Optional<PolicyVersion> existing = PolicyVersion.<PolicyVersion>find(
        "tenantId = ?1 and externalRef = ?2", tenantId, d.cloudVersionId()).firstResultOptional();
    PolicyVersion version = existing.isPresent()
        ? reuse(existing.get(), d, performedBy)
        : create(target, d, p, performedBy);
    target.externalGeneration = d.generation();
    target.updatedAt = Instant.now();
    target.persist();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("cloudPolicyId", d.cloudPolicyId());
    m.put("cloudVersionId", d.cloudVersionId());
    m.put("cloudVersion", d.cloudVersion());
    m.put("created", existing.isEmpty());
    m.put("functionName", d.functionName());
    m.put("generation", d.generation());
    m.put("moduleName", d.moduleName());
    m.put("policyVersionId", version.id);
    m.put("sourceHash", version.sourceHash);
    appender.appendInCurrentTransaction(
        receiptBuilder.policyDeploymentEvent(EventType.POLICY_DEPLOYED, tenantId, performedBy, m));
    return new Deployed(version.id, version.version.intValue(), version.sourceHash, existing.isEmpty());
  }

  private DeployResult applyWithdraw(String tenantId, String performedBy, String cloudPolicyId, long generation) {
    lock("guard-deploy:" + tenantId + ':' + cloudPolicyId);
    List<PolicyCatalog> owned = ownedCatalogs(tenantId, cloudPolicyId);
    if (owned.isEmpty()) {
      throw new Rejection(new Rejected(404, "not_found", "无此部署", null));
    }
    if (rejectStale(owned, generation) && owned.stream().anyMatch(c -> c.defaultVersionId != null)) {
      throw sameGenerationConflict();
    }
    boolean changed = false;
    for (PolicyCatalog catalog : owned) {
      changed |= versionService.withdrawCatalog(catalog);
      catalog.externalGeneration = generation;
    }
    if (changed) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("cloudPolicyId", cloudPolicyId);
      m.put("generation", generation);
      m.put("addresses", owned.stream().map(c -> c.moduleName + '.' + c.functionName).toList());
      appender.appendInCurrentTransaction(
          receiptBuilder.policyDeploymentEvent(EventType.POLICY_WITHDRAWN, tenantId, performedBy, m));
    }
    return new Withdrawn();
  }

  /** 已有同 cloud 版本的行：源码与地址必须一致，之后只做（重新）激活。 */
  private PolicyVersion reuse(PolicyVersion v, PolicyDeployment d, String performedBy) {
    if (!d.source().equals(v.content) || !d.moduleName().equals(v.moduleName)
        || !d.functionName().equals(v.functionName)) {
      throw new Rejection(new Rejected(409, "deployment_mismatch",
          "该 cloud 版本已以不同的源码或地址部署过", Map.of("policyVersionId", v.id)));
    }
    if (v.status == io.aster.policy.entity.VersionStatus.DRAFT) {
      versionService.approveFromCloud(v.id, performedBy);
    }
    if (!Boolean.TRUE.equals(v.active)) {
      versionService.reactivateVersion(v.id, performedBy);
    }
    return v;
  }

  private PolicyVersion create(PolicyCatalog target, PolicyDeployment d, Prepared p, String performedBy) {
    PolicyVersion v = versionService.createVersion(target.id, d.source(), d.locale(),
        PolicyVersionService.CLOUD_DEPLOY_SOURCE_KIND, null, p.aliasSetJson(), p.vocabularyJson(), p.coreJson());
    v.externalRef = d.cloudVersionId();
    v.createdBy = performedBy;
    v.persist();
    versionService.approveFromCloud(v.id, performedBy);
    versionService.activateVersion(v.id, performedBy);
    return v;
  }

  /** 地址目录：不存在则新建并归属本 cloud 策略；已归属他人（含 aster-api 原生策略）409 {@code address_conflict}。 */
  private PolicyCatalog targetCatalog(String tenantId, PolicyDeployment d) {
    PolicyCatalog catalog = PolicyCatalog.<PolicyCatalog>find(
        "tenantId = ?1 and moduleName = ?2 and functionName = ?3", tenantId, d.moduleName(), d.functionName())
        .firstResult();
    if (catalog == null) {
      catalog = new PolicyCatalog();
      catalog.id = UUID.randomUUID();
      catalog.tenantId = tenantId;
      catalog.moduleName = d.moduleName();
      catalog.functionName = d.functionName();
      catalog.externalRef = d.cloudPolicyId();
      catalog.createdAt = Instant.now();
      catalog.updatedAt = catalog.createdAt;
      catalog.persist();
      return catalog;
    }
    if (!d.cloudPolicyId().equals(catalog.externalRef)) {
      throw new Rejection(new Rejected(409, "address_conflict",
          "该租户已有其它策略占用 " + d.moduleName() + '.' + d.functionName(), null));
    }
    return catalog;
  }

  private static List<PolicyCatalog> ownedCatalogs(String tenantId, String cloudPolicyId) {
    return PolicyCatalog.<PolicyCatalog>find("tenantId = ?1 and externalRef = ?2", tenantId, cloudPolicyId).list();
  }

  /**
   * 代际门：更小的代际 409 {@code stale_generation}。
   *
   * @return 是否与已存代际相等——相等只允许是同一意图的重放（崩溃或响应丢失后的重试），由调用方核对
   */
  private static boolean rejectStale(List<PolicyCatalog> owned, long generation) {
    long stored = owned.stream().map(c -> c.externalGeneration).filter(g -> g != null)
        .mapToLong(Long::longValue).max().orElse(0L);
    if (generation < stored) {
      throw new Rejection(new Rejected(409, "stale_generation", "已有更新的部署代际",
          Map.of("generation", stored)));
    }
    return generation == stored;
  }

  /** 同代际重放的意图核对：该 cloud 策略当前正好以同一 cloud 版本、同一地址处于部署状态。 */
  private static boolean deployedAt(List<PolicyCatalog> owned, PolicyDeployment d) {
    return owned.stream()
        .filter(c -> c.defaultVersionId != null)
        .anyMatch(c -> d.moduleName().equals(c.moduleName) && d.functionName().equals(c.functionName)
            && PolicyVersion.<PolicyVersion>findByIdOptional(c.defaultVersionId)
                .map(v -> d.cloudVersionId().equals(v.externalRef)).orElse(false));
  }

  /** 同一代际只能代表一个意图；意图不同说明 cloud 侧代际分配出错，拒绝而不是覆盖。 */
  private static Rejection sameGenerationConflict() {
    return new Rejection(new Rejected(409, "generation_conflict", "同一部署代际已用于另一意图", null));
  }

  private void lock(String key) {
    entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .setParameter("key", key)
        .getSingleResult();
  }

  /** 词汇：先过结构上限，再严格构建；有条目才冻结（无条目与无词汇同一编译输入）。 */
  private static VocabularyScopes freezeVocabularyScopes(Map<String, Object> vocabulary) {
    String bound = VocabularyBounds.validate(vocabulary);
    if (bound != null) {
      throw new Rejection(new Rejected(422, "vocabulary_invalid", bound, null));
    }
    try {
      return PolicyVersion.scopesOf(vocabulary);
    } catch (IllegalStateException e) {
      throw new Rejection(new Rejected(422, "vocabulary_invalid", e.getMessage(), null));
    }
  }

  /** 别名：kind 名须可识别，按词汇作用域做碰撞校验后规范化；空别名为 null。 */
  private static String freezeAliasSet(Map<String, List<String>> aliasSet, String locale, VocabularyScopes scopes) {
    if (aliasSet == null || aliasSet.isEmpty()) {
      return null;
    }
    Map<SemanticTokenKind, List<String>> parsed = new EnumMap<>(SemanticTokenKind.class);
    for (Map.Entry<String, List<String>> e : aliasSet.entrySet()) {
      try {
        parsed.put(SemanticTokenKind.valueOf(e.getKey()), e.getValue() == null ? List.of() : e.getValue());
      } catch (IllegalArgumentException | NullPointerException unknown) {
        throw invalid("aliasSet 含未知 kind: " + e.getKey());
      }
    }
    UserAliasValidator.Result result = UserAliasValidator.validate(parsed, locale, scopes);
    if (!result.valid()) {
      throw new Rejection(new Rejected(422, "compile_failed", "用户自定义别名校验失败",
          Map.of("errors", result.errors())));
    }
    return UserAliasValidator.canonicalJson(parsed);
  }

  /** Core IR 的模块名须等于 {@code moduleName}，且含名为 {@code functionName} 的函数。 */
  private static void requireAddress(String coreJson, String moduleName, String functionName) {
    CoreModel.Module module;
    try {
      module = CoreIrJson.read(coreJson);
    } catch (Exception e) {
      throw new Rejection(new Rejected(422, "compile_failed", "Core IR 无法读取: " + e.getMessage(), null));
    }
    if (!moduleName.equals(module.name)) {
      throw invalid("源码 Module 头为 " + module.name + "，与 moduleName 不符");
    }
    boolean found = module.decls != null && module.decls.stream()
        .anyMatch(decl -> decl instanceof CoreModel.Func f && functionName.equals(f.name));
    if (!found) {
      throw new Rejection(new Rejected(422, "function_not_found", "源码中没有入口函数 " + functionName, null));
    }
  }

  /** 门禁与并发保护抛出的 HTTP 异常：422 = 稳定性门禁，409 = 并发激活。 */
  private static Rejected mapGate(WebApplicationException e) {
    int status = e.getResponse() == null ? 500 : e.getResponse().getStatus();
    if (status == 422) {
      return new Rejected(422, "stability_blocked", e.getMessage(), null);
    }
    if (status == 409) {
      return new Rejected(409, "concurrent_activation", "同一策略的另一次激活已并发提交，请重试", null);
    }
    throw e;
  }

  private static void requireRef(String field, String value) {
    if (value == null || value.isBlank() || value.length() > MAX_REF || value.chars().anyMatch(Character::isISOControl)) {
      throw invalid(field + " 须为 1–" + MAX_REF + " 个字符且不含控制字符");
    }
  }

  private static void requireName(String field, String value) {
    if (value == null || value.isBlank() || value.length() > MAX_NAME || value.chars().anyMatch(Character::isISOControl)) {
      throw invalid(field + " 须为 1–" + MAX_NAME + " 个字符且不含控制字符");
    }
  }

  private static void requireGeneration(long generation) {
    if (generation < 1) {
      throw invalid("generation 须为正整数");
    }
  }

  private static Rejection invalid(String message) {
    return new Rejection(new Rejected(400, "invalid_deployment", message, null));
  }

  private static String canonicalJson(Map<String, Object> vocabulary) {
    try {
      return CANONICAL.writeValueAsString(vocabulary);
    } catch (JsonProcessingException e) {
      throw new Rejection(new Rejected(422, "vocabulary_invalid", "词汇无法序列化", null));
    }
  }

  static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 携带拒绝结果的控制流异常：在事务内抛出即回滚，由入口方法转成 {@link Rejected}。 */
  static final class Rejection extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient Rejected rejected;

    Rejection(Rejected rejected) {
      super(rejected.error(), null, false, false);
      this.rejected = rejected;
    }
  }
}
