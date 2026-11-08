package io.aster.guard.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.GuardInvalidActionException;
import io.aster.guard.receipt.JcsCanonicalizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 签名事实片段验签（ADR 0040 §11 D3）：校验请求体 {@code action.facts[]}，返回其 {@code claims} 覆盖的路径。
 *
 * <p>规则（任一条不满足 → 整个请求 400 {@code invalid_fact}，不降级为 declared）：
 * <ul>
 *   <li>≤ {@value #MAX_FACTS} 条；全部事实的 claims 合计 ≤ {@value #MAX_CLAIMS}；路径 ≤ {@value #MAX_PATH_LENGTH} 字符；</li>
 *   <li>成员恰为 {@code alg, audience, claims, expiresAt, issuedAt, issuer, kid, signature, sources}：
 *       不受签名覆盖的额外成员一律拒绝；</li>
 *   <li>{@code alg} 只接受 {@code Ed25519}；签发方 {@code (tenant, issuer, kid)} 存在、{@code enabled}、
 *       {@code now < notAfter}；{@code audience == tenantId}；</li>
 *   <li>{@code issuedAt − 60s <= now < expiresAt}（{@code expiresAt} 排他，与执行收据一致），
 *       {@code issuedAt < expiresAt} 且 {@code expiresAt − issuedAt <= 24h}；</li>
 *   <li>签名 = Ed25519 作用于除 {@code signature} 外八个成员的 JCS 字节串（{@code jcs-sha256/v1} 的规范化部分）；</li>
 *   <li>每个 claim 路径（点路径，只沿对象成员下行）在 {@code action} 中存在，且两边 JCS 字节相等
 *       （{@code 250000}、{@code 250000.0}、{@code 2.5E5} 相等；与字符串 {@code "250000"} 不等）；</li>
 *   <li>{@code sources} 键集 == {@code claims} 键集，值为 ≤ {@value #MAX_SOURCE_LENGTH} 字符的字符串；</li>
 *   <li>点路径只按嵌套解释：路径所经对象里与该路径相撞的含 {@code .} 字面键 → {@code ambiguous_path}；</li>
 *   <li>类型化字符串字段（{@link #TYPED_STRING_PATHS}）上的值必须是 JSON 字符串，{@code principal.roles} 必须是
 *       字符串数组（覆盖其父对象的 claim 同样检查）→ 否则 {@code claim_type_mismatch}。</li>
 * </ul>
 *
 * <p>签发方按主键直查（不缓存，至多 {@value #MAX_FACTS} 次），时间取注入的 {@link Clock}。
 */
@ApplicationScoped
public class FactVerifier {
  public static final int MAX_FACTS = 8;
  public static final int MAX_CLAIMS = 64;
  public static final int MAX_PATH_LENGTH = 128;
  public static final int MAX_SOURCE_LENGTH = 64;
  static final Duration ISSUED_AT_SKEW = Duration.ofSeconds(60);
  static final Duration MAX_VALIDITY = Duration.ofHours(24);
  /** 签名覆盖的成员（JCS 会再按码元序排序，这里的顺序无语义）。 */
  private static final List<String> SIGNED_FIELDS =
      List.of("alg", "audience", "claims", "expiresAt", "issuedAt", "issuer", "kid", "sources");
  private static final String SIGNATURE_FIELD = "signature";
  private static final int SIGNATURE_BYTES = 64;
  private static final int PUBLIC_KEY_BYTES = 32;
  /**
   * 请求体中按 {@code String} 类型绑定的字段：Jackson 会把数字 / 布尔强转为字符串、null 由 mapper 补缺省值，
   * 规则读到的不是原文值，所以这些路径上只认 JSON 字符串。
   */
  static final Set<String> TYPED_STRING_PATHS = Set.of("principal.id", "principal.type", "action.name",
      "resource.type", "resource.id", "agent.provider", "agent.model", "agent.version", "agent.session");
  /** 按 {@code List<String>} 绑定的字段：只认字符串数组。 */
  static final String TYPED_STRING_ARRAY_PATH = "principal.roles";
  /** Ed25519 SubjectPublicKeyInfo 前缀（RFC 8410）：其后接 32 字节原始公钥即为 X.509 编码。 */
  private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

  /**
   * 验签结果。{@code issuersByPath} / {@code issuerKids} 中的签发方写作 {@code issuer#kid}；
   * {@code sourcesByPath} 在同一路径有多个签发方时取字典序最小的 {@code issuer#kid} 所给的来源标识
   * （与 facts 排列无关）；{@code earliestExpiresAt} 为全部事实中最早的 {@code expiresAt}，无事实时为 null。
   */
  public record VerifiedFacts(SortedSet<String> paths, SortedMap<String, SortedSet<String>> issuersByPath,
                              SortedSet<String> issuerKids, SortedMap<String, String> sourcesByPath,
                              Instant earliestExpiresAt) {
    public static final VerifiedFacts EMPTY = new VerifiedFacts(Collections.emptySortedSet(),
        Collections.emptySortedMap(), Collections.emptySortedSet(), Collections.emptySortedMap(), null);
  }

  /** 解析后的单条事实；{@code payload} 为签名载荷的 JCS 字节串。 */
  private record SignedFact(String issuer, String kid, String alg, String audience, Instant issuedAt, Instant expiresAt,
                            ObjectNode claims, ObjectNode sources, byte[] signature, byte[] payload) {
    String issuerKid() {
      return issuer + "#" + kid;
    }

    GuardInvalidFactException reject(String reason, String message) {
      return new GuardInvalidFactException(issuer, kid, reason, message);
    }
  }

  private final FactIssuerStore store;
  private final Clock clock;

  @Inject
  public FactVerifier(FactIssuerStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * 验签 {@code rawAction.facts}（请求体 {@code action} 原文树）。没有 {@code facts}、为 null 或空数组 →
   * {@link VerifiedFacts#EMPTY}。
   *
   * @throws GuardInvalidFactException 任一条事实不满足类注释所列规则
   */
  public VerifiedFacts verify(String tenantId, JsonNode rawAction) {
    JsonNode facts = rawAction == null ? null : rawAction.get("facts");
    if (facts == null || facts.isNull() || (facts.isArray() && facts.isEmpty())) {
      return VerifiedFacts.EMPTY;
    }
    List<SignedFact> parsed = parseAll(facts);
    Instant now = clock.instant();
    for (SignedFact fact : parsed) {
      verifyOne(tenantId, rawAction, fact, now);
    }
    return collect(parsed);
  }

  /**
   * 只做结构与规范化检查（不查签发方、不验签、不比对 action）：供请求体解析阶段在计算 {@code actionHash} 之前调用，
   * 使 facts 内的不安全数值 / 孤立代理项报 {@code invalid_fact}，而不是笼统的 {@code invalid_action}。
   * {@code facts} 缺失或不是数组时什么都不做（留给 {@link #verify} 报告）。
   */
  public static void requireCanonicalFacts(JsonNode rawAction) {
    JsonNode facts = rawAction == null ? null : rawAction.get("facts");
    if (facts == null || !facts.isArray()) {
      return;
    }
    for (JsonNode fact : facts) {
      if (fact.isObject()) {
        canonicalPayload(fact, textOrNull(fact, "issuer"), textOrNull(fact, "kid"));
      }
    }
  }

  private static List<SignedFact> parseAll(JsonNode facts) {
    if (!facts.isArray()) {
      throw new GuardInvalidFactException(null, null, GuardInvalidFactException.MALFORMED, "facts 必须是数组");
    }
    if (facts.size() > MAX_FACTS) {
      throw new GuardInvalidFactException(null, null, GuardInvalidFactException.TOO_MANY_FACTS,
          "facts 超过 " + MAX_FACTS + " 条");
    }
    List<SignedFact> parsed = new ArrayList<>();
    int claims = 0;
    for (JsonNode node : facts) {
      SignedFact fact = parse(node);
      claims += fact.claims().size();
      parsed.add(fact);
    }
    if (claims > MAX_CLAIMS) {
      throw new GuardInvalidFactException(null, null, GuardInvalidFactException.TOO_MANY_CLAIMS,
          "facts 的 claims 合计超过 " + MAX_CLAIMS + " 项");
    }
    return parsed;
  }

  private static SignedFact parse(JsonNode node) {
    if (!node.isObject()) {
      throw new GuardInvalidFactException(null, null, GuardInvalidFactException.MALFORMED, "facts[] 每项必须是对象");
    }
    String issuer = textOrNull(node, "issuer");
    String kid = textOrNull(node, "kid");
    requireExactFields(node, issuer, kid);
    ObjectNode claims = requireClaims(node, issuer, kid);
    ObjectNode sources = requireSources(node, issuer, kid);
    return new SignedFact(issuer, kid, requireText(node, "alg", issuer, kid), requireText(node, "audience", issuer, kid),
        requireInstant(node, "issuedAt", issuer, kid), requireInstant(node, "expiresAt", issuer, kid), claims, sources,
        decodeSignature(node.get(SIGNATURE_FIELD).textValue()), canonicalPayload(node, issuer, kid));
  }

  /** 成员集合必须恰为八个签名成员加 {@code signature}，且字符串成员为字符串。 */
  private static void requireExactFields(JsonNode node, String issuer, String kid) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    Set<String> expected = new TreeSet<>(SIGNED_FIELDS);
    expected.add(SIGNATURE_FIELD);
    if (!names.equals(expected) || issuer == null || kid == null || !node.get(SIGNATURE_FIELD).isTextual()) {
      throw new GuardInvalidFactException(issuer, kid, GuardInvalidFactException.MALFORMED,
          "事实成员须恰为 " + expected + "，issuer / kid / signature 为字符串");
    }
  }

  private static ObjectNode requireClaims(JsonNode node, String issuer, String kid) {
    JsonNode claims = node.get("claims");
    if (!claims.isObject() || claims.isEmpty()) {
      throw new GuardInvalidFactException(issuer, kid, GuardInvalidFactException.MALFORMED, "claims 必须是非空对象");
    }
    claims.fieldNames().forEachRemaining(path -> requirePath(path, issuer, kid));
    return (ObjectNode) claims;
  }

  private static void requirePath(String path, String issuer, String kid) {
    if (path.length() > MAX_PATH_LENGTH) {
      throw new GuardInvalidFactException(issuer, kid, GuardInvalidFactException.PATH_TOO_LONG,
          "claim 路径超过 " + MAX_PATH_LENGTH + " 字符");
    }
  }

  private static ObjectNode requireSources(JsonNode node, String issuer, String kid) {
    JsonNode sources = node.get("sources");
    if (!sources.isObject()) {
      throw new GuardInvalidFactException(issuer, kid, GuardInvalidFactException.MALFORMED, "sources 必须是对象");
    }
    for (JsonNode value : sources) {
      if (!value.isTextual() || value.textValue().length() > MAX_SOURCE_LENGTH) {
        throw new GuardInvalidFactException(issuer, kid, GuardInvalidFactException.INVALID_SOURCE,
            "sources 的值须为不超过 " + MAX_SOURCE_LENGTH + " 字符的字符串");
      }
    }
    return (ObjectNode) sources;
  }

  private static String requireText(JsonNode node, String field, String issuer, String kid) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new GuardInvalidFactException(issuer, kid, GuardInvalidFactException.MALFORMED, field + " 必须是字符串");
    }
    return value.textValue();
  }

  /** ISO-8601 带偏移（{@code Z} 或 {@code ±hh:mm}）。 */
  private static Instant requireInstant(JsonNode node, String field, String issuer, String kid) {
    String text = requireText(node, field, issuer, kid);
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException e) {
      throw new GuardInvalidFactException(issuer, kid, GuardInvalidFactException.MALFORMED, field + " 不是 ISO-8601 时间");
    }
  }

  /** 无法解码或长度不是 64 字节时返回 null，在验签步骤按 {@code bad_signature} 拒绝。 */
  private static byte[] decodeSignature(String signature) {
    try {
      byte[] raw = Base64.getUrlDecoder().decode(signature);
      return raw.length == SIGNATURE_BYTES ? raw : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * 签名载荷：八个签名成员（缺失的成员由 {@link #requireExactFields} 先行拒绝）的 JCS 字节串。
   * 孤立代理项可能就在 issuer / kid 里，而它无法写进 UTF-8 响应体，所以 {@code invalid_string} 不回显二者。
   */
  private static byte[] canonicalPayload(JsonNode node, String issuer, String kid) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    SIGNED_FIELDS.stream().filter(node::has).forEach(f -> payload.set(f, node.get(f)));
    try {
      return JcsCanonicalizer.canonicalize(payload);
    } catch (JcsCanonicalizer.InvalidJsonValueException e) {
      boolean echo = !JcsCanonicalizer.INVALID_STRING.equals(e.code());
      throw new GuardInvalidFactException(echo ? issuer : null, echo ? kid : null, e.code(),
          "事实无法规范化: " + e.getMessage());
    }
  }

  private void verifyOne(String tenantId, JsonNode rawAction, SignedFact fact, Instant now) {
    if (!GuardFactIssuerEntity.ALG_ED25519.equals(fact.alg())) {
      throw fact.reject(GuardInvalidFactException.UNSUPPORTED_ALG, "只支持 alg=Ed25519");
    }
    GuardFactIssuerEntity row = requireUsableIssuer(tenantId, fact, now);
    if (!tenantId.equals(fact.audience())) {
      throw fact.reject(GuardInvalidFactException.AUDIENCE_MISMATCH, "audience 与本租户不符");
    }
    requireWindow(fact, now);
    if (!signatureValid(row.publicKey, fact)) {
      throw fact.reject(GuardInvalidFactException.BAD_SIGNATURE, "签名无效");
    }
    fact.claims().properties().forEach(claim -> requireClaimMatches(rawAction, fact, claim.getKey(), claim.getValue()));
    if (!keys(fact.claims()).equals(keys(fact.sources()))) {
      throw fact.reject(GuardInvalidFactException.SOURCES_MISMATCH, "sources 键集须与 claims 键集完全相等");
    }
  }

  private GuardFactIssuerEntity requireUsableIssuer(String tenantId, SignedFact fact, Instant now) {
    GuardFactIssuerEntity row = store.find(tenantId, fact.issuer(), fact.kid())
        .orElseThrow(() -> fact.reject(GuardInvalidFactException.UNKNOWN_ISSUER, "本租户未登记该签发方 issuer#kid"));
    if (!row.enabled) {
      throw fact.reject(GuardInvalidFactException.ISSUER_DISABLED, "签发方已禁用");
    }
    if (row.notAfter != null && !now.isBefore(row.notAfter)) {
      throw fact.reject(GuardInvalidFactException.ISSUER_EXPIRED, "签发方已过 notAfter");
    }
    return row;
  }

  private static void requireWindow(SignedFact fact, Instant now) {
    if (!fact.issuedAt().isBefore(fact.expiresAt())) {
      throw fact.reject(GuardInvalidFactException.INVALID_WINDOW, "expiresAt 须晚于 issuedAt");
    }
    if (Duration.between(fact.issuedAt(), fact.expiresAt()).compareTo(MAX_VALIDITY) > 0) {
      throw fact.reject(GuardInvalidFactException.WINDOW_TOO_LONG, "expiresAt − issuedAt 超过 24h");
    }
    if (now.isBefore(fact.issuedAt().minus(ISSUED_AT_SKEW))) {
      throw fact.reject(GuardInvalidFactException.NOT_YET_VALID, "issuedAt 晚于当前时间超过 60s");
    }
    if (!now.isBefore(fact.expiresAt())) {
      throw fact.reject(GuardInvalidFactException.EXPIRED, "事实已过 expiresAt");
    }
  }

  /** 公钥为签发方表中的 base64url 32 字节原始公钥（登记时已校验）；任何解码或验签异常按无效签名处理。 */
  private static boolean signatureValid(String publicKey, SignedFact fact) {
    if (fact.signature() == null) {
      return false;
    }
    try {
      byte[] raw = Base64.getUrlDecoder().decode(publicKey);
      if (raw.length != PUBLIC_KEY_BYTES) {
        return false;
      }
      Signature verifier = Signature.getInstance(GuardFactIssuerEntity.ALG_ED25519);
      verifier.initVerify(toPublicKey(raw));
      verifier.update(fact.payload());
      return verifier.verify(fact.signature());
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      return false;
    }
  }

  private static PublicKey toPublicKey(byte[] raw) throws GeneralSecurityException {
    byte[] spki = Arrays.copyOf(ED25519_SPKI_PREFIX, ED25519_SPKI_PREFIX.length + raw.length);
    System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
    return KeyFactory.getInstance(GuardFactIssuerEntity.ALG_ED25519).generatePublic(new X509EncodedKeySpec(spki));
  }

  private static void requireClaimMatches(JsonNode rawAction, SignedFact fact, String path, JsonNode claimed) {
    requireUnambiguous(rawAction, fact, path);
    JsonNode actual = resolve(rawAction, path);
    if (actual == null) {
      throw fact.reject(GuardInvalidFactException.CLAIM_PATH_ABSENT, "claim 路径在 action 中不存在: " + path);
    }
    if (!Arrays.equals(JcsCanonicalizer.canonicalize(claimed), canonicalActionValue(actual))) {
      throw fact.reject(GuardInvalidFactException.CLAIM_MISMATCH, "claim 与 action 中的值不相等: " + path);
    }
    requireTypedValues(fact, path, claimed);
  }

  /**
   * 沿 claim 路径逐级下行，检查经过的每个对象：其中含 {@code .} 的字面键拼成的点路径若等于 claim 路径、是它的前缀
   * 或以它为前缀，同一点路径就有两种解读（收据与审批视图只记点路径），整个请求拒绝。
   * claim 覆盖的值内部的键不检查：那些叶子都随 claim 一起 verified，不存在歧义。
   */
  private static void requireUnambiguous(JsonNode rawAction, SignedFact fact, String path) {
    JsonNode node = rawAction;
    String prefix = "";
    for (String segment : path.split("\\.", -1)) {
      if (node == null || !node.isObject()) {
        return;
      }
      String base = prefix;
      node.fieldNames().forEachRemaining(key -> {
        String dotted = base.isEmpty() ? key : base + "." + key;
        if (key.indexOf('.') >= 0 && collides(dotted, path)) {
          throw fact.reject(GuardInvalidFactException.AMBIGUOUS_PATH,
              "claim 路径与 action 中含 '.' 的字面键相撞: " + path);
        }
      });
      prefix = base.isEmpty() ? segment : base + "." + segment;
      node = node.get(segment);
    }
  }

  private static boolean collides(String dotted, String path) {
    return dotted.equals(path) || path.startsWith(dotted + ".") || dotted.startsWith(path + ".");
  }

  /** claim 路径本身或其下的类型化字段：值（若存在）须为字符串，{@code principal.roles} 须为字符串数组。 */
  private static void requireTypedValues(SignedFact fact, String path, JsonNode claimed) {
    for (String typed : TYPED_STRING_PATHS) {
      JsonNode value = valueAt(claimed, path, typed);
      if (value != null && !value.isTextual()) {
        throw fact.reject(GuardInvalidFactException.CLAIM_TYPE_MISMATCH, typed + " 的 claim 必须是字符串");
      }
    }
    JsonNode roles = valueAt(claimed, path, TYPED_STRING_ARRAY_PATH);
    if (roles != null && !(roles.isArray() && allTextual(roles))) {
      throw fact.reject(GuardInvalidFactException.CLAIM_TYPE_MISMATCH,
          TYPED_STRING_ARRAY_PATH + " 的 claim 必须是字符串数组");
    }
  }

  /** claim 路径为 {@code path}、值为 {@code claimed} 时，{@code typed} 处的值；typed 不在该 claim 覆盖范围内返回 null。 */
  private static JsonNode valueAt(JsonNode claimed, String path, String typed) {
    if (typed.equals(path)) {
      return claimed;
    }
    return typed.startsWith(path + ".") ? resolve(claimed, typed.substring(path.length() + 1)) : null;
  }

  private static boolean allTextual(JsonNode array) {
    for (JsonNode item : array) {
      if (!item.isTextual()) {
        return false;
      }
    }
    return true;
  }

  /** 点路径只沿对象成员下行；任一段缺失或遇到非对象返回 null（显式 null 叶子是存在的值）。 */
  private static JsonNode resolve(JsonNode root, String path) {
    JsonNode node = root;
    for (String segment : path.split("\\.", -1)) {
      if (node == null || !node.isObject() || !node.has(segment)) {
        return null;
      }
      node = node.get(segment);
    }
    return node;
  }

  /** action 原文已在计算 actionHash 时通过同一规范化；这里失败说明调用方跳过了该步骤，按 invalid_action 拒绝。 */
  private static byte[] canonicalActionValue(JsonNode value) {
    try {
      return JcsCanonicalizer.canonicalize(value);
    } catch (IllegalArgumentException e) {
      throw new GuardInvalidActionException("action 无法规范化: " + e.getMessage());
    }
  }

  private static SortedSet<String> keys(ObjectNode node) {
    SortedSet<String> keys = new TreeSet<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  private static VerifiedFacts collect(List<SignedFact> facts) {
    SortedMap<String, SortedSet<String>> issuersByPath = new TreeMap<>();
    SortedSet<String> issuerKids = new TreeSet<>();
    SortedMap<String, SortedMap<String, String>> sourcesByIssuer = new TreeMap<>();
    Instant earliest = null;
    for (SignedFact fact : facts) {
      issuerKids.add(fact.issuerKid());
      fact.claims().fieldNames().forEachRemaining(path -> {
        issuersByPath.computeIfAbsent(path, p -> new TreeSet<>()).add(fact.issuerKid());
        sourcesByIssuer.computeIfAbsent(path, p -> new TreeMap<>())
            .putIfAbsent(fact.issuerKid(), fact.sources().get(path).textValue());
      });
      earliest = earliest == null || fact.expiresAt().isBefore(earliest) ? fact.expiresAt() : earliest;
    }
    SortedMap<String, SortedSet<String>> frozenIssuers = new TreeMap<>();
    issuersByPath.forEach((path, issuers) -> frozenIssuers.put(path, Collections.unmodifiableSortedSet(issuers)));
    SortedMap<String, String> sourcesByPath = new TreeMap<>();
    sourcesByIssuer.forEach((path, byIssuer) -> sourcesByPath.put(path, byIssuer.get(byIssuer.firstKey())));
    return new VerifiedFacts(Collections.unmodifiableSortedSet(new TreeSet<>(issuersByPath.keySet())),
        Collections.unmodifiableSortedMap(frozenIssuers), Collections.unmodifiableSortedSet(issuerKids),
        Collections.unmodifiableSortedMap(sourcesByPath), earliest);
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }
}
