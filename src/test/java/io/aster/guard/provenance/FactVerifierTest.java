package io.aster.guard.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.provenance.FactVerifier.VerifiedFacts;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.aster.guard.provenance.TestFactSigner.ISSUER;
import static io.aster.guard.provenance.TestFactSigner.KID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ADR 0040 §11 D3：签名事实片段验签（纯 JUnit + Mockito，签发方表以 mock 代替）。 */
class FactVerifierTest {
  private static final String TENANT = "tenant-a";
  private static final Instant NOW = Instant.parse("2026-11-07T01:05:00Z");
  private static final Instant ISSUED = Instant.parse("2026-11-07T01:00:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-11-07T01:10:00Z");
  private static final String CLAIMS = "{\"context.amount\":250000,\"principal.roles\":[\"ops-bot\"]}";
  private static final String SOURCES = "{\"context.amount\":\"catalog.ledgerAmount\",\"principal.roles\":\"idp.businessRoles\"}";
  private static final String ACTION = "{\"principal\":{\"id\":\"agent-42\",\"type\":\"agent\",\"roles\":[\"ops-bot\"]},"
      + "\"action\":{\"name\":\"refund\"},\"resource\":{\"type\":\"order\",\"id\":\"O-1\"},"
      + "\"context\":{\"amount\":250000,\"currency\":null}}";
  private static final ObjectMapper JSON = new ObjectMapper();

  private FactIssuerStore store;
  private TestFactSigner signer;

  @BeforeEach
  void setUp() {
    store = mock(FactIssuerStore.class);
    signer = TestFactSigner.newKey();
    register(signer, ISSUER, KID, true, null);
  }

  private void register(TestFactSigner s, String issuer, String kid, boolean enabled, Instant notAfter) {
    GuardFactIssuerEntity row = new GuardFactIssuerEntity();
    row.tenantId = TENANT;
    row.issuer = issuer;
    row.kid = kid;
    row.publicKey = s.publicKey();
    row.enabled = enabled;
    row.notAfter = notAfter;
    when(store.find(TENANT, issuer, kid)).thenReturn(Optional.of(row));
  }

  private TestFactSigner valid() {
    return signer.fresh().audience(TENANT).window(ISSUED, EXPIRES).claims(CLAIMS).sources(SOURCES);
  }

  private static ObjectNode action(String actionJson, JsonNode... facts) {
    ObjectNode a = (ObjectNode) read(actionJson);
    ArrayNode list = a.putArray("facts");
    for (JsonNode f : facts) {
      list.add(f);
    }
    return a;
  }

  private static ObjectNode action(JsonNode... facts) {
    return action(ACTION, facts);
  }

  private static JsonNode read(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException(json, e);
    }
  }

  private VerifiedFacts verify(JsonNode rawAction) {
    return verifyAt(NOW, rawAction);
  }

  private VerifiedFacts verifyAt(Instant now, JsonNode rawAction) {
    return new FactVerifier(store, Clock.fixed(now, ZoneOffset.UTC)).verify(TENANT, rawAction);
  }

  private static void assertRejected(ThrowingCallable call, String reason) {
    assertThatThrownBy(call).isInstanceOfSatisfying(GuardInvalidFactException.class,
        e -> assertThat(e.reason()).as(e.getMessage()).isEqualTo(reason));
  }

  private void assertRejected(JsonNode rawAction, String reason) {
    assertRejected(() -> verify(rawAction), reason);
  }

  @Test
  void validFactVerifiesPaths() {
    VerifiedFacts v = verify(action(valid().sign()));

    assertThat(v.paths()).containsExactly("context.amount", "principal.roles");
    assertThat(v.issuersByPath()).containsOnlyKeys("context.amount", "principal.roles");
    assertThat(v.issuersByPath().get("context.amount")).containsExactly(ISSUER + "#" + KID);
    assertThat(v.issuerKids()).containsExactly(ISSUER + "#" + KID);
    assertThat(v.sourcesByPath()).containsEntry("context.amount", "catalog.ledgerAmount")
        .containsEntry("principal.roles", "idp.businessRoles").hasSize(2);
    assertThat(v.earliestExpiresAt()).isEqualTo(EXPIRES);
  }

  @Test
  void noFactsIsEmpty() {
    assertThat(verify(read(ACTION))).isSameAs(VerifiedFacts.EMPTY);
    assertThat(verify(action())).isSameAs(VerifiedFacts.EMPTY);
    ObjectNode explicitNull = (ObjectNode) read(ACTION);
    explicitNull.putNull("facts");
    assertThat(verify(explicitNull)).isSameAs(VerifiedFacts.EMPTY);
    assertThat(verify(null)).isSameAs(VerifiedFacts.EMPTY);
    assertThat(VerifiedFacts.EMPTY.paths()).isEmpty();
    assertThat(VerifiedFacts.EMPTY.earliestExpiresAt()).isNull();
  }

  @Test
  void tamperedClaim() {
    ObjectNode fact = valid().sign();
    ((ObjectNode) fact.get("claims")).put("context.amount", 1);
    String action = ACTION.replace("\"amount\":250000", "\"amount\":1");

    assertRejected(action(action, fact), GuardInvalidFactException.BAD_SIGNATURE);
  }

  @Test
  void tamperedSources() {
    ObjectNode fact = valid().sign();
    ((ObjectNode) fact.get("sources")).put("context.amount", "agent.selfReported");

    assertRejected(action(fact), GuardInvalidFactException.BAD_SIGNATURE);
  }

  @Test
  void tamperedSignatureOrEnvelope() {
    ObjectNode badSig = valid().sign();
    badSig.put("signature", "not*base64");
    ObjectNode shortSig = valid().sign();
    shortSig.put("signature", "AAAA");
    ObjectNode laterExpiry = valid().sign();
    laterExpiry.put("expiresAt", EXPIRES.plusSeconds(60).toString());

    assertRejected(action(badSig), GuardInvalidFactException.BAD_SIGNATURE);
    assertRejected(action(shortSig), GuardInvalidFactException.BAD_SIGNATURE);
    assertRejected(action(laterExpiry), GuardInvalidFactException.BAD_SIGNATURE);
  }

  @Test
  void expired() {
    ObjectNode fact = valid().window(NOW.minus(Duration.ofMinutes(20)), NOW.minus(Duration.ofMinutes(10))).sign();

    assertRejected(action(fact), GuardInvalidFactException.EXPIRED);
  }

  @Test
  void notYetValidBeyondSkew() {
    ObjectNode fact = valid().window(NOW.plus(Duration.ofMinutes(2)), NOW.plus(Duration.ofMinutes(10))).sign();

    assertRejected(action(fact), GuardInvalidFactException.NOT_YET_VALID);
  }

  @Test
  void windowOver24h() {
    Instant issued = NOW.minus(Duration.ofMinutes(1));
    ObjectNode over = valid().window(issued, issued.plus(Duration.ofHours(24)).plusMillis(1)).sign();
    ObjectNode exactly = valid().window(issued, issued.plus(Duration.ofHours(24))).sign();
    ObjectNode inverted = valid().window(NOW.plusSeconds(30), NOW.plusSeconds(10)).sign();

    assertRejected(action(over), GuardInvalidFactException.WINDOW_TOO_LONG);
    assertThat(verify(action(exactly)).paths()).hasSize(2);
    assertRejected(action(inverted), GuardInvalidFactException.INVALID_WINDOW);
  }

  @Test
  void expiresAtBoundaryIsExclusive() {
    ObjectNode fact = valid().sign();

    assertRejected(() -> verifyAt(EXPIRES, action(fact)), GuardInvalidFactException.EXPIRED);
    assertThat(verifyAt(EXPIRES.minusMillis(1), action(fact)).paths()).hasSize(2);
    assertThat(verifyAt(ISSUED.minusSeconds(60), action(fact)).paths()).hasSize(2);
    assertRejected(() -> verifyAt(ISSUED.minusSeconds(61), action(fact)), GuardInvalidFactException.NOT_YET_VALID);
  }

  @Test
  void audienceMismatch() {
    assertRejected(action(valid().audience("tenant-b").sign()), GuardInvalidFactException.AUDIENCE_MISMATCH);
  }

  @Test
  void unknownKid() {
    assertRejected(action(valid().kid("2026-12").sign()), GuardInvalidFactException.UNKNOWN_ISSUER);
    assertRejected(action(valid().issuer("idp.other.com").sign()), GuardInvalidFactException.UNKNOWN_ISSUER);
  }

  @Test
  void signedByAnotherKeyUnderRegisteredKid() {
    TestFactSigner impostor = TestFactSigner.newKey();
    ObjectNode fact = impostor.audience(TENANT).window(ISSUED, EXPIRES).claims(CLAIMS).sources(SOURCES).sign();

    assertRejected(action(fact), GuardInvalidFactException.BAD_SIGNATURE);
  }

  @Test
  void disabledIssuer() {
    register(signer, ISSUER, KID, false, null);

    assertRejected(action(valid().sign()), GuardInvalidFactException.ISSUER_DISABLED);
  }

  @Test
  void pastNotAfter() {
    register(signer, ISSUER, KID, true, NOW);
    assertRejected(action(valid().sign()), GuardInvalidFactException.ISSUER_EXPIRED);

    register(signer, ISSUER, KID, true, NOW.plusMillis(1));
    assertThat(verify(action(valid().sign())).paths()).hasSize(2);
  }

  @Test
  void nonEd25519Alg() {
    assertRejected(action(valid().alg("RS256").sign()), GuardInvalidFactException.UNSUPPORTED_ALG);
    assertRejected(action(valid().alg("ed25519").sign()), GuardInvalidFactException.UNSUPPORTED_ALG);
  }

  @Test
  void claimPathAbsentInAction() {
    ObjectNode deeper = valid().claims("{\"context.customer.tier\":\"gold\"}")
        .sources("{\"context.customer.tier\":\"crm\"}").sign();
    ObjectNode nullLeafPresent = valid().claims("{\"context.currency\":null}").sources("{\"context.currency\":\"ledger\"}")
        .sign();

    assertRejected(action(deeper), GuardInvalidFactException.CLAIM_PATH_ABSENT);
    assertThat(verify(action(nullLeafPresent)).paths()).containsExactly("context.currency");
  }

  @Test
  void claimValueDiffersFromAction() {
    ObjectNode fact = valid().claims("{\"context.amount\":100}").sources("{\"context.amount\":\"ledger\"}").sign();
    ObjectNode roles = valid().claims("{\"principal.roles\":[\"ops-bot\",\"admin\"]}")
        .sources("{\"principal.roles\":\"idp\"}").sign();

    assertRejected(action(fact), GuardInvalidFactException.CLAIM_MISMATCH);
    assertRejected(action(roles), GuardInvalidFactException.CLAIM_MISMATCH);
  }

  @Test
  void sourcesKeySetMismatch() {
    ObjectNode missing = valid().sources("{\"context.amount\":\"catalog.ledgerAmount\"}").sign();
    ObjectNode extra = valid().sources("{\"context.amount\":\"a\",\"principal.roles\":\"b\",\"context.x\":\"c\"}").sign();

    assertRejected(action(missing), GuardInvalidFactException.SOURCES_MISMATCH);
    assertRejected(action(extra), GuardInvalidFactException.SOURCES_MISMATCH);
  }

  @Test
  void sourceValueOver64CharsOrNotString() {
    ObjectNode longSource = valid().sources("{\"context.amount\":\"" + "s".repeat(65) + "\",\"principal.roles\":\"b\"}").sign();
    ObjectNode atLimit = valid().sources("{\"context.amount\":\"" + "s".repeat(64) + "\",\"principal.roles\":\"b\"}").sign();
    ObjectNode number = valid().sources("{\"context.amount\":1,\"principal.roles\":\"b\"}").sign();

    assertRejected(action(longSource), GuardInvalidFactException.INVALID_SOURCE);
    assertThat(verify(action(atLimit)).sourcesByPath()).containsEntry("context.amount", "s".repeat(64));
    assertRejected(action(number), GuardInvalidFactException.INVALID_SOURCE);
  }

  @Test
  void claimPathOver128Chars() {
    String path = "context." + "p".repeat(121);
    ObjectNode fact = valid().claims("{\"" + path + "\":1}").sources("{\"" + path + "\":\"s\"}").sign();

    assertThat(path).hasSize(129);
    assertRejected(action(fact), GuardInvalidFactException.PATH_TOO_LONG);
  }

  @Test
  void malformedFact() {
    ObjectNode missingSignature = valid().sign();
    missingSignature.remove("signature");
    ObjectNode unknownField = valid().sign();
    unknownField.put("nonce", "n-1");
    ObjectNode badTime = valid().sign();
    badTime.put("issuedAt", "yesterday");
    ObjectNode emptyClaims = valid().claims("{}").sources("{}").sign();
    ObjectNode notArray = (ObjectNode) read(ACTION);
    notArray.set("facts", valid().sign());

    assertRejected(action(missingSignature), GuardInvalidFactException.MALFORMED);
    assertRejected(action(unknownField), GuardInvalidFactException.MALFORMED);
    assertRejected(action(badTime), GuardInvalidFactException.MALFORMED);
    assertRejected(action(emptyClaims), GuardInvalidFactException.MALFORMED);
    assertRejected(action(read("\"fact\"")), GuardInvalidFactException.MALFORMED);
    assertRejected(notArray, GuardInvalidFactException.MALFORMED);
  }

  @Test
  void moreThan8Facts() {
    JsonNode[] eight = IntStream.range(0, 8).mapToObj(i -> valid().sign()).toArray(JsonNode[]::new);
    JsonNode[] nine = IntStream.range(0, 9).mapToObj(i -> valid().sign()).toArray(JsonNode[]::new);

    assertThat(verify(action(eight)).issuerKids()).containsExactly(ISSUER + "#" + KID);
    assertRejected(action(nine), GuardInvalidFactException.TOO_MANY_FACTS);
  }

  @Test
  void moreThan64Claims() {
    String context = IntStream.range(0, 65).mapToObj(i -> "\"c" + i + "\":" + i).collect(Collectors.joining(",", "{", "}"));
    String actionJson = "{\"principal\":{\"id\":\"a\"},\"action\":{\"name\":\"n\"},\"context\":" + context + "}";

    assertThat(verify(action(actionJson, claimsFact(0, 32), claimsFact(32, 64))).paths()).hasSize(64);
    assertRejected(action(actionJson, claimsFact(0, 32), claimsFact(32, 65)), GuardInvalidFactException.TOO_MANY_CLAIMS);
  }

  private ObjectNode claimsFact(int from, int to) {
    String claims = IntStream.range(from, to).mapToObj(i -> "\"context.c" + i + "\":" + i)
        .collect(Collectors.joining(",", "{", "}"));
    String sources = IntStream.range(from, to).mapToObj(i -> "\"context.c" + i + "\":\"s\"")
        .collect(Collectors.joining(",", "{", "}"));
    return valid().claims(claims).sources(sources).sign();
  }

  /**
   * Review Focus 5：claim 与 action 的比较是 JCS 字节比较。数字按 double 规范化后同为 {@code 250000}；
   * 字符串 {@code "250000"} 是另一个值；claims 中的不安全整数在验签之前就以 unsafe_integer 拒绝。
   */
  @Test
  void claimEqualityIsJcsBytes() {
    for (String actionAmount : List.of("250000.0", "2.5E5", "250000")) {
      JsonNode raw = action(ACTION.replace("\"amount\":250000", "\"amount\":" + actionAmount), valid().sign());
      assertThat(verify(raw).paths()).as(actionAmount).contains("context.amount");
    }
    ObjectNode decimalClaim = valid().claims("{\"context.amount\":2.5E5}").sources("{\"context.amount\":\"l\"}").sign();
    assertThat(verify(action(decimalClaim)).paths()).containsExactly("context.amount");

    JsonNode asString = action(ACTION.replace("\"amount\":250000", "\"amount\":\"250000\""), valid().sign());
    assertRejected(asString, GuardInvalidFactException.CLAIM_MISMATCH);

    ObjectNode unsafe = valid().claims("{\"context.amount\":9007199254740992}").sources("{\"context.amount\":\"l\"}")
        .unsigned();
    assertRejected(action(ACTION.replace("\"amount\":250000", "\"amount\":\"9007199254740992\""), unsafe),
        GuardInvalidFactException.UNSAFE_INTEGER);
    ObjectNode loneSurrogate = valid().claims("{\"context.amount\":\"\\ud800\"}").sources("{\"context.amount\":\"l\"}")
        .unsigned();
    assertRejected(action(loneSurrogate), GuardInvalidFactException.INVALID_STRING);
  }

  /** 签名载荷逐字节锁定：成员按 UTF-16 码元序、无空白、数字按 ECMAScript 输出（2.5E5 → 250000）、不含 signature。 */
  @Test
  void signedPayloadExactJcsBytes() {
    TestFactSigner fixture = signer.fresh()
        .sources("{\"principal.roles\":\"idp.businessRoles\",\"context.amount\":\"catalog.ledgerAmount\"}")
        .claims("{\"principal.roles\":[\"ops-bot\"],\"context.amount\":2.5E5}")
        .window(ISSUED, EXPIRES)
        .audience(TENANT);

    assertThat(fixture.payloadText()).isEqualTo("{\"alg\":\"Ed25519\",\"audience\":\"tenant-a\","
        + "\"claims\":{\"context.amount\":250000,\"principal.roles\":[\"ops-bot\"]},"
        + "\"expiresAt\":\"2026-11-07T01:10:00Z\",\"issuedAt\":\"2026-11-07T01:00:00Z\","
        + "\"issuer\":\"idp.example.com\",\"kid\":\"2026-11\","
        + "\"sources\":{\"context.amount\":\"catalog.ledgerAmount\",\"principal.roles\":\"idp.businessRoles\"}}");
    assertThat(verify(action(fixture.sign())).paths()).containsExactly("context.amount", "principal.roles");
  }

  @Test
  void twoIssuersSamePathRecordsBoth() {
    TestFactSigner other = TestFactSigner.newKey().issuer("aaa.ledger.example").kid("k1");
    register(other, "aaa.ledger.example", "k1", true, null);
    ObjectNode fromLedger = other.audience(TENANT).window(ISSUED, EXPIRES.minusSeconds(120))
        .claims("{\"context.amount\":250000}").sources("{\"context.amount\":\"ledger.amount\"}").sign();

    VerifiedFacts v = verify(action(valid().sign(), fromLedger));

    assertThat(v.paths()).containsExactly("context.amount", "principal.roles");
    assertThat(v.issuersByPath().get("context.amount")).containsExactly("aaa.ledger.example#k1", ISSUER + "#" + KID);
    assertThat(v.issuersByPath().get("principal.roles")).containsExactly(ISSUER + "#" + KID);
    assertThat(v.issuerKids()).containsExactly("aaa.ledger.example#k1", ISSUER + "#" + KID);
    // 同一路径多个来源标识时取字典序最小的 issuer#kid 所给的值，结果与 facts 的排列顺序无关
    assertThat(v.sourcesByPath()).containsEntry("context.amount", "ledger.amount");
    assertThat(verify(action(fromLedger, valid().sign())).sourcesByPath()).isEqualTo(v.sourcesByPath());
    assertThat(v.earliestExpiresAt()).isEqualTo(EXPIRES.minusSeconds(120));
  }

  @Test
  void anyBadFactRejectsWholeRequestWithItsIssuerAndKid() {
    ObjectNode bad = valid().kid("2026-12").sign();

    assertThatThrownBy(() -> verify(action(valid().sign(), bad)))
        .isInstanceOfSatisfying(GuardInvalidFactException.class, e -> {
          assertThat(e.issuer()).isEqualTo(ISSUER);
          assertThat(e.kid()).isEqualTo("2026-12");
          assertThat(e.reason()).isEqualTo(GuardInvalidFactException.UNKNOWN_ISSUER);
        });
  }

  // ---- 点路径歧义与类型化字段（Task 12 评审遗留 (c) / (d)） ----

  private ObjectNode signedClaim(String claims, String sources) {
    return signer.fresh().audience(TENANT).window(ISSUED, EXPIRES).claims(claims).sources(sources).sign();
  }

  /** claim 路径按嵌套解释；路径所经对象里有含 '.' 的字面键且与该路径相撞 → ambiguous_path。 */
  @Test
  void dottedLiteralKeyUnderClaimedPrefixIsRejected() {
    String nested = "{\"principal\":{\"id\":\"agent-42\"},\"action\":{\"name\":\"refund\"},"
        + "\"context\":{\"customer\":{\"tier\":\"gold\"},\"customer.tier\":\"bronze\"}}";
    String literalOnly = "{\"principal\":{\"id\":\"agent-42\"},\"action\":{\"name\":\"refund\"},"
        + "\"context\":{\"customer.tier\":\"gold\"}}";
    String deeper = "{\"principal\":{\"id\":\"agent-42\"},\"action\":{\"name\":\"refund\"},"
        + "\"context\":{\"customer\":{\"tier\":\"gold\"},\"customer.tier.extra\":1}}";
    ObjectNode fact = signedClaim("{\"context.customer.tier\":\"gold\"}", "{\"context.customer.tier\":\"crm.tier\"}");

    assertRejected(action(nested, fact), GuardInvalidFactException.AMBIGUOUS_PATH);
    assertRejected(action(literalOnly, fact), GuardInvalidFactException.AMBIGUOUS_PATH);
    assertRejected(action(deeper, fact), GuardInvalidFactException.AMBIGUOUS_PATH);
  }

  /** 与 claim 路径不相撞的含 '.' 字面键（另一分支、或 claim 覆盖的值内部）不影响验签。 */
  @Test
  void dottedKeyOutsideClaimedPathIsAccepted() {
    String other = "{\"principal\":{\"id\":\"agent-42\"},\"action\":{\"name\":\"refund\"},"
        + "\"context\":{\"customer\":{\"tier\":\"gold\"},\"note.text\":\"x\",\"order\":{\"line.1\":2}}}";
    ObjectNode tierFact = signedClaim("{\"context.customer.tier\":\"gold\"}", "{\"context.customer.tier\":\"crm.tier\"}");
    ObjectNode orderFact = signedClaim("{\"context.order\":{\"line.1\":2}}", "{\"context.order\":\"erp.order\"}");

    assertThat(verify(action(other, tierFact)).paths()).containsExactly("context.customer.tier");
    assertThat(verify(action(other, orderFact)).paths()).containsExactly("context.order");
  }

  /**
   * principal.id 等类型化字符串字段：Jackson 会把数字强转成字符串（42 → "42"），规则看到的与签发方签的不是同一类型，
   * 所以这些路径上的 claim 必须是 JSON 字符串；principal.roles 必须是字符串数组；覆盖其父对象的 claim 同样检查。
   */
  @Test
  void nonStringClaimOnTypedStringFieldIsRejected() {
    String numericId = "{\"principal\":{\"id\":42,\"type\":\"user\"},\"action\":{\"name\":\"refund\"}}";
    String numericRole = "{\"principal\":{\"id\":\"u-1\",\"roles\":[7]},\"action\":{\"name\":\"refund\"}}";
    String nullResource = "{\"principal\":{\"id\":\"u-1\"},\"action\":{\"name\":\"refund\"},\"resource\":{\"id\":null}}";

    assertRejected(action(numericId, signedClaim("{\"principal.id\":42}", "{\"principal.id\":\"idp.sub\"}")),
        GuardInvalidFactException.CLAIM_TYPE_MISMATCH);
    assertRejected(action(numericId, signedClaim("{\"principal\":{\"id\":42,\"type\":\"user\"}}",
        "{\"principal\":\"idp.subject\"}")), GuardInvalidFactException.CLAIM_TYPE_MISMATCH);
    assertRejected(action(numericRole, signedClaim("{\"principal.roles\":[7]}", "{\"principal.roles\":\"idp.roles\"}")),
        GuardInvalidFactException.CLAIM_TYPE_MISMATCH);
    assertRejected(action(nullResource, signedClaim("{\"resource.id\":null}", "{\"resource.id\":\"erp.id\"}")),
        GuardInvalidFactException.CLAIM_TYPE_MISMATCH);
  }

  @Test
  void stringClaimOnTypedStringFieldIsAccepted() {
    String userAction = "{\"principal\":{\"id\":\"u-1\",\"type\":\"user\",\"roles\":[\"ops\"]},\"action\":{\"name\":\"refund\"},"
        + "\"resource\":{\"type\":\"order\"}}";
    ObjectNode fact = signedClaim("{\"principal.id\":\"u-1\",\"principal.roles\":[\"ops\"],\"resource.type\":\"order\"}",
        "{\"principal.id\":\"idp.sub\",\"principal.roles\":\"idp.roles\",\"resource.type\":\"erp.type\"}");

    assertThat(verify(action(userAction, fact)).paths()).containsExactly("principal.id", "principal.roles", "resource.type");
  }

  /**
   * ADR 0040 §11 D3「不做」：agent.* 不接受签名事实（模型身份的可证来源留 ADR 0041 §10）。
   * agent 整体、agent 下任一叶子都拒绝；仅以 agent 为前缀的其它顶层键（agentX）不受影响（此处因不存在而报 claim_path_absent）。
   */
  @Test
  void claimOnAgentSubtreeIsRejected() {
    String withAgent = "{\"principal\":{\"id\":\"u-1\"},\"action\":{\"name\":\"refund\"},"
        + "\"agent\":{\"provider\":\"anthropic\",\"model\":\"m\"}}";

    assertRejected(action(withAgent, signedClaim("{\"agent.model\":\"m\"}", "{\"agent.model\":\"gw.model\"}")),
        GuardInvalidFactException.CLAIM_PATH_NOT_ALLOWED);
    assertRejected(action(withAgent, signedClaim("{\"agent\":{\"provider\":\"anthropic\",\"model\":\"m\"}}",
        "{\"agent\":\"gw.agent\"}")), GuardInvalidFactException.CLAIM_PATH_NOT_ALLOWED);
    assertRejected(action(withAgent, signedClaim("{\"agentX\":1}", "{\"agentX\":\"x\"}")),
        GuardInvalidFactException.CLAIM_PATH_ABSENT);
  }
}
