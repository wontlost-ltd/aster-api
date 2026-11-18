package io.aster.policy.export;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aster.guard.receipt.JcsCanonicalizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * 组装 B 形态策略包（ADR 0059 §2–§3、§6，修订 3 P2-R14）：写全部载荷 → 计算 {@code files[]}（UTF-8 字节序）→ 以 RFC 8785
 * 规范字节写 {@code manifest.json}（manifest 键全为 ASCII、数字全为安全整数，在此域内与包格式的码点序规范 JSON 字节相同）→
 * 签名 → {@code SHA256SUMS}（GNU {@code <hex>␠␠<path>\n}，载荷 + manifest，按路径 UTF-8 字节序）→ 可复现 zip。
 *
 * <p>同一输入两次组装逐字节相同：不含导出时间、请求 id，与 JVM 默认时区无关。签名被拒（{@link PackageSigner.Refused}）时在
 * 写 zip 之前抛 {@link PackageExportUnavailableException}；zip 超过 {@link #MAX_ZIP_BYTES} 抛 {@link PackageTooLargeException}。
 */
@ApplicationScoped
public class PackageAssembler {
  public static final int MAX_ZIP_BYTES = 1_048_576;
  static final String FORMAT = "aster-policy-package";
  static final String FORMAT_VERSION = "1.0";
  static final int CORE_IR_VERSION = 2;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** manifest 中除 {@code files}、{@code source.sha256}、{@code tests.probes*} 以外的全部值（由服务按 Global Constraints 推导）。 */
  public record ManifestFields(
      String coreIrSha256, String positionFreeSha256, String toolchainId,
      List<String> effectsDeclared,
      String tenantId,
      List<String> factSources, boolean verdict,
      List<String> controls, String entry, String profile, String ruleId,
      String engine, String cliName, String cliVersion, Map<String, String> requires, String ecosystem,
      boolean allowStructural, ClaimedRef claimedRef, String envelopeSha256, String locale,
      NodeCheck nodeCheck) {
    public ManifestFields {
      effectsDeclared = List.copyOf(effectsDeclared);
      factSources = List.copyOf(factSources);
      controls = List.copyOf(controls);
      requires = Map.copyOf(requires);
    }
  }

  /** manifest {@code source.claimedRef}（修订 3 P2-R11 的有类型形状）。 */
  public record ClaimedRef(String policyId, int version, String status, String approvedAt) {
  }

  /** manifest {@code tests.nodeCheck}；jre 包为 null。 */
  public record NodeCheck(String tsVersion, int agreed) {
  }

  /** 组装输入。{@code vocabularyCanonicalJson} 为 null 表示无词汇（不写该文件）；{@code aliasSetCanonicalJson} 无别名时为 {@code "null"}。 */
  public record PackageInputs(String moduleName, String target, String uiLocale, byte[] source,
                              String aliasSetCanonicalJson, String vocabularyCanonicalJson,
                              EntrySchemaGenerator.EntrySchemas schemas, ProbeRunner.ProbesFile probes,
                              ManifestFields fields, String jreCliVersion, String tsCliVersion) {
  }

  /** 组装结果；{@code kid} 为 null 表示无签名包。 */
  public record AssembledPackage(byte[] zip, String zipSha256, String manifestSha256, String rootName, String kid) {
  }

  private final BiFunction<byte[], String, PackageSigner.SignOutcome> signer;

  @Inject
  public PackageAssembler(PackageSigner signer) {
    this(signer::sign);
  }

  public PackageAssembler(BiFunction<byte[], String, PackageSigner.SignOutcome> signer) {
    this.signer = signer;
  }

  public AssembledPackage assemble(PackageInputs in) {
    ManifestFields f = in.fields();
    TreeMap<String, byte[]> payload = DeterministicZip.newFileMap();
    payload.put("README.md", PackageReadme.render(in.uiLocale(), new PackageReadme.Data(in.moduleName(),
        f.claimedRef().version(), in.target(), f.entry(), f.cliName(), f.cliVersion(), in.jreCliVersion(),
        in.tsCliVersion(), f.factSources())));
    payload.put("policy/policy.aster", in.source());
    payload.put("policy/compile/alias-set.json", utf8(in.aliasSetCanonicalJson()));
    if (in.vocabularyCanonicalJson() != null) {
      payload.put("policy/compile/vocabulary.json", utf8(in.vocabularyCanonicalJson()));
    }
    payload.put("schema/input.json", in.schemas().inputJson());
    payload.put("schema/output.json", in.schemas().outputJson());
    payload.put("tests/probes.json", in.probes().json());

    byte[] manifest = JcsCanonicalizer.canonicalize(manifest(in, payload));
    TreeMap<String, byte[]> files = DeterministicZip.newFileMap();
    files.putAll(payload);
    files.put("manifest.json", manifest);
    String kid = switch (signer.apply(manifest, in.target())) {
      case PackageSigner.Signed s -> {
        files.put("manifest.sig.json", s.sigJson());
        yield s.kid();
      }
      case PackageSigner.Unsigned u -> null;
      case PackageSigner.Refused r -> throw new PackageExportUnavailableException(r.errorCode());
    };
    TreeMap<String, byte[]> summed = DeterministicZip.newFileMap();
    summed.putAll(payload);
    summed.put("manifest.json", manifest);
    files.put("SHA256SUMS", sha256sums(summed));

    String root = in.moduleName() + "-v" + f.claimedRef().version() + "-" + in.target() + "-b";
    byte[] zip = DeterministicZip.write(root, files);
    if (zip.length > MAX_ZIP_BYTES) {
      throw new PackageTooLargeException(zip.length, MAX_ZIP_BYTES);
    }
    return new AssembledPackage(zip, sha256(zip), sha256(manifest), root, kid);
  }

  private static ObjectNode manifest(PackageInputs in, TreeMap<String, byte[]> payload) {
    ManifestFields f = in.fields();
    ObjectNode m = F.objectNode();
    ObjectNode coreIr = m.putObject("coreIr");
    coreIr.put("positionFreeSha256", f.positionFreeSha256());
    coreIr.put("sha256", f.coreIrSha256());
    coreIr.put("toolchainId", f.toolchainId());
    coreIr.put("version", CORE_IR_VERSION);
    ObjectNode effects = m.putObject("effects");
    effects.set("declared", strings(f.effectsDeclared()));
    effects.put("io", false);
    ObjectNode exportedBy = m.putObject("exportedBy");
    exportedBy.put("issuer", PackageSigner.ISSUER);
    exportedBy.put("tenantId", f.tenantId());
    ArrayNode filesNode = m.putArray("files");
    payload.forEach((path, bytes) -> filesNode.addObject().put("path", path).put("sha256", sha256(bytes))
        .put("size", bytes.length));
    m.put("format", FORMAT);
    m.put("formatVersion", FORMAT_VERSION);
    ObjectNode governance = m.putObject("governance");
    governance.put("enforced", false);
    governance.set("factSources", strings(f.factSources()));
    governance.put("verdict", f.verdict());
    m.put("mode", "b");
    ObjectNode policy = m.putObject("policy");
    policy.set("controls", strings(f.controls()));
    policy.putArray("entries").addObject().put("default", true).put("function", f.entry())
        .put("inputSchema", "#/$defs/entry." + f.entry()).put("outputSchema", "#/$defs/entry." + f.entry());
    policy.put("moduleName", in.moduleName());
    policy.put("profile", f.profile());
    policy.put("ruleId", f.ruleId());
    ObjectNode runtime = m.putObject("runtime");
    runtime.putObject("cli").put("name", f.cliName()).put("version", f.cliVersion());
    runtime.put("ecosystem", f.ecosystem());
    runtime.put("engine", f.engine());
    ObjectNode requires = runtime.putObject("requires");
    new TreeMap<>(f.requires()).forEach(requires::put);
    ObjectNode source = m.putObject("source");
    source.put("allowStructural", f.allowStructural());
    ObjectNode claimed = source.putObject("claimedRef");
    claimed.put("approvedAt", f.claimedRef().approvedAt());
    claimed.put("policyId", f.claimedRef().policyId());
    claimed.put("status", f.claimedRef().status());
    claimed.put("version", f.claimedRef().version());
    source.put("envelopeSha256", f.envelopeSha256());
    source.put("locale", f.locale());
    source.put("sha256", sha256(in.source()));
    m.put("target", in.target());
    ObjectNode tests = m.putObject("tests");
    if (f.nodeCheck() == null) {
      tests.putNull("nodeCheck");
    } else {
      tests.putObject("nodeCheck").put("agreed", f.nodeCheck().agreed()).put("checkedBy", PackageSigner.ISSUER)
          .put("tsVersion", f.nodeCheck().tsVersion());
    }
    tests.put("probes", in.probes().count());
    tests.put("probesSha256", sha256(in.probes().json()));
    return m;
  }

  static byte[] sha256sums(TreeMap<String, byte[]> files) {
    StringBuilder sb = new StringBuilder();
    files.forEach((path, bytes) -> sb.append(sha256(bytes)).append("  ").append(path).append('\n'));
    return utf8(sb.toString());
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode a = F.arrayNode();
    values.forEach(a::add);
    return a;
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }
}
