package io.aster.policy.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.signing.Jwks;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * aster-api ≡ jre CLI 黄金门（ADR 0059 P2 T15，Review Focus 2）：对 conformance 每个可求值的黄金源码，aster-api 导出的 jre 包
 * <ol>
 *   <li>经 {@code java -jar aster-cli.jar verify --probes} 退出码 0、{@code ok:true}；</li>
 *   <li>每条探针 {@code run} 的信封 {@code value} 与 {@code tests/probes.json} 的平台期望值（{@code /evaluate-json} 同一执行路径
 *       经 {@link ValueJson#write} 写出，见 {@link ProbeRunner}）逐字节相同（修订 1 A1）；</li>
 *   <li>平台期望 {@code EVAL_ERROR} 的探针，CLI 也以 {@code EVAL_ERROR} 失败。</li>
 * </ol>
 * 平台每个包恰有一个入口（修订 3 P2-R9），因此每个黄金源码按 fixture 列出的每个入口各导出一次
 * （{@code a-multi-entry} 的 {@code ratio} 覆盖 EVAL_ERROR）。
 *
 * <p>CLI fat jar 由系统属性 {@code aster.cli.jar} 给出（Gradle 透传 {@code -Daster.cli.jar=…}），未设置时跳过；CI 设
 * {@code ASTER_EXPORT_GOLDEN_REQUIRE_JAR=1}，缺失即失败而不是跳过。不一致时修 aster-api，不改期望。
 */
@QuarkusTest
class JreCliGoldenGateTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonFactory FACTORY = new JsonFactory();
  static final List<String> GOLDEN = List.of(
      "b-credit-limit", "b-vocab-de", "b-zh", "b-alias", "a-decimal", "a-inputs", "a-multi-entry");
  private static final long CLI_TIMEOUT_S = 120;

  @Inject PackageExportService service;
  @Inject ExportSigningKeys keys;

  @TempDir Path tmp;

  /** 一条探针的比对结果。 */
  private record Outcome(String pkg, String input, boolean agreed, String detail) {
  }

  @Test
  void platformPackagesAgreeWithTheJreCli() throws Exception {
    String jarProp = System.getProperty("aster.cli.jar", "");
    Path jar = jarProp.isBlank() ? null : Path.of(jarProp).toAbsolutePath();
    boolean present = jar != null && Files.isRegularFile(jar);
    if ("1".equals(System.getenv("ASTER_EXPORT_GOLDEN_REQUIRE_JAR"))) {
      assertThat(present).as("CI 要求黄金门实际执行：-Daster.cli.jar=" + jarProp + " 必须是存在的 jre CLI fat jar").isTrue();
    }
    assumeTrue(present, "未给出 jre CLI jar（-Daster.cli.jar），跳过黄金门");

    var ring = keys.ring().orElseThrow();
    Path jwks = tmp.resolve("jwks.json");
    Files.writeString(jwks, JSON.writeValueAsString(Jwks.render(ring.all(), ring.revision(), Instant.now())));

    Map<String, Path> zips = new LinkedHashMap<>();
    Map<String, String> probesJson = new LinkedHashMap<>();
    for (Map.Entry<String, PackageExportService.ExportSpec> e : specs().entrySet()) {
      PackageAssembler.AssembledPackage p = service.export(e.getValue(), null);
      Path zip = tmp.resolve(e.getKey() + ".zip");
      Files.write(zip, p.zip());
      zips.put(e.getKey(), zip);
      byte[] probes = PackageAssemblerTest.unzip(p.zip()).get("tests/probes.json");
      probesJson.put(e.getKey(), new String(probes, StandardCharsets.UTF_8));
    }

    int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      List<Future<Outcome>> verifies = new ArrayList<>();
      for (Map.Entry<String, Path> z : zips.entrySet()) {
        verifies.add(pool.submit(() -> verify(jar, z.getKey(), z.getValue(), jwks)));
      }
      List<Future<Outcome>> runs = new ArrayList<>();
      int evalErrors = 0;
      for (Map.Entry<String, Path> z : zips.entrySet()) {
        for (String probe : rawElements(rawMember(probesJson.get(z.getKey()), "probes"))) {
          String input = rawMember(probe, "input");
          String expected = rawMember(probe, "expected");
          if (isEvalError(expected)) {
            evalErrors++;
          }
          runs.add(pool.submit(() -> run(jar, z.getKey(), z.getValue(), jwks, input, expected)));
        }
      }
      List<Outcome> failures = new ArrayList<>();
      for (Future<Outcome> f : verifies) {
        Outcome o = f.get();
        if (!o.agreed()) {
          failures.add(o);
        }
      }
      int agreed = 0;
      for (Future<Outcome> f : runs) {
        Outcome o = f.get();
        if (o.agreed()) {
          agreed++;
        } else {
          failures.add(o);
        }
      }
      System.out.printf("jre CLI 黄金门：%d 个包 verify --probes，%d 条探针（EVAL_ERROR %d 条），一致 %d / %d%n",
          zips.size(), runs.size(), evalErrors, agreed, runs.size());
      assertThat(failures).as("与 jre CLI 不一致的包 / 探针").isEmpty();
      assertThat(runs).as("探针总数").isNotEmpty();
      assertThat(evalErrors).as("至少一条 EVAL_ERROR 探针（a-multi-entry ratio）").isPositive();
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * ADR 0059 修订 4（计划 T6 Step 1）：aster-api 带排版导出的《静夜思》jre 包经 jre CLI 核验——{@code verify --recompile --probes}
   * 全部检查项（含 {@code display}、{@code display-ir}）通过；{@code show} 打印原诗；{@code run} 得 {@code 静夜思}；
   * {@code run --display} 对 conformance 的编辑文本给出唯一解 / 歧义的同一结论。签名的 1.1 包一定通过 CLI 的 verify（D11 不变式）。
   */
  @Test
  void layoutPackageVerifiesShowsAndRunsOnTheJreCli() throws Exception {
    Path jar = requireJar();
    assumeTrue(jar != null, "未给出 jre CLI jar（-Daster.cli.jar），跳过黄金门");
    var ring = keys.ring().orElseThrow();
    Path jwks = tmp.resolve("jwks.json");
    Files.writeString(jwks, JSON.writeValueAsString(Jwks.render(ring.all(), ring.revision(), Instant.now())));
    PackageAssembler.AssembledPackage p = service.export(PackageLayoutGateTest.jingyesi("jre"), null,
        io.aster.test.JingyesiPolicy.LAYOUT_JSON);
    assertThat(p.layout().status()).isEqualTo("included");
    Path zip = tmp.resolve(p.rootName() + ".zip");
    Files.write(zip, p.zip());
    String pkg = zip.toString();
    String anchor = jwks.toString();

    Cli verify = cli(jar, null, "verify", pkg, "--recompile", "--probes", "--jwks", anchor);
    assertThat(verify.exit()).as(verify.stdout() + verify.stderr()).isZero();
    JsonNode checks = JSON.readTree(verify.stdout()).get("checks");
    assertThat(checks.findValuesAsText("name"))
        .containsExactly("schema", "signature", "integrity", "display", "recompile", "display-ir", "probes");
    assertThat(checks.findValues("ok")).allMatch(JsonNode::asBoolean);

    Cli show = cli(jar, null, "show", pkg, "--jwks", anchor);
    assertThat(show.exit()).as(show.stderr()).isZero();
    assertThat(show.stdout()).isEqualTo("床前明月光，\n疑是地上霜。\n举头望明月，\n低头思故乡。\n");
    assertThat(show.stderr()).contains("思故乡 → 静夜思");
    Cli canonical = cli(jar, null, "show", pkg, "--canonical", "--jwks", anchor);
    assertThat(canonical.stdout()).isEqualTo(io.aster.test.JingyesiPolicy.SOURCE + "\n");

    Cli run = cli(jar, "{}", "run", pkg, "--input", "-", "--jwks", anchor);
    assertThat(run.exit()).as(run.stderr()).isZero();
    assertThat(JSON.readTree(run.stdout()).get("value").asText()).isEqualTo("静夜思");

    Path edits = EntrySchemaGeneratorTest.FIXTURES.resolve("display/edits/b-jingyesi").toAbsolutePath();
    Map<String, String> unique = Map.of("unchanged.txt", "静夜思", "trailing-newline.txt", "静夜思", "last-line-literal.txt", "月光");
    for (Map.Entry<String, String> e : unique.entrySet()) {
      Cli r = cli(jar, "{}", "run", pkg, "--display", edits.resolve(e.getKey()).toString(), "--input", "-", "--jwks", anchor);
      assertThat(r.exit()).as(e.getKey() + " " + r.stdout() + r.stderr()).isZero();
      JsonNode out = JSON.readTree(r.stdout());
      assertThat(out.get("value").asText()).as(e.getKey()).isEqualTo(e.getValue());
      assertThat(out.at("/meta/display/matchesPackage").asBoolean()).as(e.getKey()).isEqualTo("静夜思".equals(e.getValue()));
      assertThat(out.at("/meta/coreIrSha256").isNull()).isTrue();
    }
    Cli ambiguous = cli(jar, "{}", "run", pkg, "--display", edits.resolve("repeat-prefix.txt").toString(), "--input", "-",
        "--jwks", anchor);
    assertThat(ambiguous.exit()).isEqualTo(2);
    assertThat(JSON.readTree(ambiguous.stdout()).at("/error/code").asText()).isEqualTo("DISPLAY_AMBIGUOUS");
  }

  /** CLI jar：CI 要求必须给出（{@code ASTER_EXPORT_GOLDEN_REQUIRE_JAR=1}），本地未给出时返回 null。 */
  private static Path requireJar() {
    String jarProp = System.getProperty("aster.cli.jar", "");
    Path jar = jarProp.isBlank() ? null : Path.of(jarProp).toAbsolutePath();
    boolean present = jar != null && Files.isRegularFile(jar);
    if ("1".equals(System.getenv("ASTER_EXPORT_GOLDEN_REQUIRE_JAR"))) {
      assertThat(present).as("CI 要求黄金门实际执行：-Daster.cli.jar=" + jarProp + " 必须是存在的 jre CLI fat jar").isTrue();
    }
    return present ? jar : null;
  }

  /** 各黄金源码的导出请求：fixture 的 default 入口，另按其余入口各导出一次。 */
  private static Map<String, PackageExportService.ExportSpec> specs() throws Exception {
    Map<String, PackageExportService.ExportSpec> out = new LinkedHashMap<>();
    for (String pkg : GOLDEN) {
      PackageExportService.ExportSpec s = PackageExportServiceTest.spec(pkg);
      out.put(pkg, s);
      JsonNode fixture = JSON.readTree(
          EntrySchemaGeneratorTest.FIXTURES.resolve("src/" + pkg + "/fixture.json").toFile());
      for (JsonNode e : fixture.get("entries")) {
        String fn = e.get("function").asText();
        if (!e.path("default").asBoolean()) {
          out.put(pkg + "-" + fn, new PackageExportService.ExportSpec(s.source(), s.locale(), s.aliasSet(),
              s.allowStructural(), s.vocabulary(), fn, s.target(), s.uiLocale(), s.claimedRef(), s.tenantId()));
        }
      }
    }
    return out;
  }

  private Outcome verify(Path jar, String pkg, Path zip, Path jwks) throws Exception {
    Cli r = cli(jar, null, "verify", zip.toString(), "--probes", "--jwks", jwks.toString());
    JsonNode out = JSON.readTree(r.stdout());
    boolean ok = r.exit() == 0 && out.path("ok").asBoolean(false);
    return new Outcome(pkg, "verify --probes", ok, "exit=" + r.exit() + " stdout=" + r.stdout() + " stderr=" + r.stderr());
  }

  private Outcome run(Path jar, String pkg, Path zip, Path jwks, String input, String expected) throws Exception {
    Cli r = cli(jar, input, "run", zip.toString(), "--input", "-", "--jwks", jwks.toString());
    String stdout = r.stdout().strip();
    boolean agreed;
    if (isEvalError(expected)) {
      JsonNode out = JSON.readTree(stdout);
      agreed = r.exit() != 0 && isEvalError(out.path("error"), out.path("ok"));
    } else {
      // 信封是值规范 JSON，按原文取 value，与平台期望的 value 原文逐字节比较
      agreed = r.exit() == 0 && rawMember(stdout, "value").equals(rawMember(expected, "value"));
    }
    return new Outcome(pkg, input, agreed, "expected=" + expected + " cli=" + stdout + " stderr=" + r.stderr());
  }

  /** 平台期望是否为 {@code {"code":"EVAL_ERROR","ok":false}}（按字段判定，不按子串，免得字符串结果含该词时误判）。 */
  private static boolean isEvalError(String expected) throws IOException {
    JsonNode e = JSON.readTree(expected);
    return isEvalError(e, e.path("ok"));
  }

  private static boolean isEvalError(JsonNode withCode, JsonNode ok) {
    return ok.isBoolean() && !ok.booleanValue() && "EVAL_ERROR".equals(withCode.path("code").asText());
  }

  private record Cli(int exit, String stdout, String stderr) {
  }

  private Cli cli(Path jar, String stdin, String... args) throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString()));
    cmd.addAll(List.of(args));
    // stdout / stderr 落盘：不在读管道上阻塞，超时才能真正生效
    Path out = Files.createTempFile(tmp, "cli", ".out");
    Path err = Files.createTempFile(tmp, "cli", ".err");
    Process p = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
    try (OutputStream in = p.getOutputStream()) {
      if (stdin != null) {
        in.write(stdin.getBytes(StandardCharsets.UTF_8));
      }
    }
    if (!p.waitFor(CLI_TIMEOUT_S, TimeUnit.SECONDS)) {
      p.destroyForcibly().waitFor();
      return new Cli(-1, Files.readString(out), "timeout after " + CLI_TIMEOUT_S + "s");
    }
    return new Cli(p.exitValue(), Files.readString(out), Files.readString(err));
  }

  /** 顶层 JSON 对象中某成员值的原文（不经解析再序列化，保证逐字节比较）。 */
  static String rawMember(String objectJson, String name) throws IOException {
    try (JsonParser p = FACTORY.createParser(objectJson)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        throw new IllegalArgumentException("不是 JSON 对象: " + objectJson);
      }
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        String field = p.currentName();
        p.nextToken();
        int start = (int) p.currentTokenLocation().getCharOffset();
        p.skipChildren();
        p.finishToken(); // 字符串值是惰性读取的，不 finish 时位置停在开引号之后
        int end = (int) p.currentLocation().getCharOffset();
        if (field.equals(name)) {
          return objectJson.substring(start, end);
        }
      }
    }
    throw new IllegalArgumentException("缺成员 " + name + ": " + objectJson);
  }

  /** 顶层 JSON 数组各元素的原文。 */
  static List<String> rawElements(String arrayJson) throws IOException {
    List<String> out = new ArrayList<>();
    try (JsonParser p = FACTORY.createParser(arrayJson)) {
      if (p.nextToken() != JsonToken.START_ARRAY) {
        throw new IllegalArgumentException("不是 JSON 数组: " + arrayJson);
      }
      while (p.nextToken() != JsonToken.END_ARRAY) {
        int start = (int) p.currentTokenLocation().getCharOffset();
        p.skipChildren();
        p.finishToken();
        out.add(arrayJson.substring(start, (int) p.currentLocation().getCharOffset()));
      }
    }
    return out;
  }
}
