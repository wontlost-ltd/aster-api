package io.aster.policy.export;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.parser.CompileIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 导出运行时锁文件（ADR 0059 修订 3 P2-R3）：{@code export/export-runtimes.lock.json} 为每个 target 记一项
 * {@code {cliName, cliVersion, trustAnchorKids}}，{@code node} 可为 {@code null}（该 target 不可导出）。
 * manifest {@code runtime.cli} 的唯一来源，也是「先发布锚，再签名」的机械检查：签名 kid 必须在该 target 的
 * {@code trustAnchorKids} 中。锁文件由列车 L4 pin bump 与生态版本一同更新。
 *
 * <p>启动时严格读取（未知字段、缺字段、类型不符即启动失败）。{@code jre.cliVersion} 必须等于本进程实际加载的
 * 引擎版本（truffle jar 的 Implementation-Version，未 stamp 时退回 core jar 的版本——同一列车同一版本号）；
 * 两者都取不到（{@code dev}：本地 / CI 的 composite 构建）时跳过比较。
 */
@ApplicationScoped
public class ExportRuntimes {
  public static final String DEFAULT_RESOURCE = "export/export-runtimes.lock.json";
  static final Set<String> TARGETS = Set.of("jre", "node");

  private static final ObjectMapper STRICT_JSON = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final Set<String> TOP_FIELDS = Set.of("formatVersion", "jre", "node");
  private static final Set<String> TARGET_FIELDS = Set.of("cliName", "cliVersion", "trustAnchorKids");

  /** 锁文件中一个 target 的 CLI 坐标与内置信任锚 kid。 */
  public record Target(String cliName, String cliVersion, List<String> trustAnchorKids) {
    public Target {
      trustAnchorKids = List.copyOf(trustAnchorKids);
    }
  }

  /** 解析后的锁文件；值为 {@code null} 的 target 不在 map 中。 */
  public record Lock(Map<String, Target> targets) {
    public Lock {
      targets = Map.copyOf(targets);
    }
  }

  @ConfigProperty(name = "aster.export.runtimes-lock", defaultValue = DEFAULT_RESOURCE)
  String resource;

  private Lock lock;

  @PostConstruct
  void load() {
    lock = parse(readResource(resource));
    requireEngineMatches(lock, engineVersion());
  }

  /** 该 target 的锁文件项；锁文件中为 {@code null} 或未知 target 时为空。 */
  public Optional<Target> target(String target) {
    return Optional.ofNullable(lock.targets().get(target));
  }

  /** manifest {@code runtime.ecosystem}：本进程加载的生态列车版本（core jar 版本，未 stamp 为 dev）。 */
  public String ecosystem() {
    return CompileIdentity.coreEngineVersion();
  }

  /** 实际加载的 JVM 引擎版本：truffle jar 的 Implementation-Version，取不到退回 core，仍取不到为 dev。 */
  static String engineVersion() {
    String truffle = aster.truffle.AsterLanguage.class.getPackage().getImplementationVersion();
    return truffle == null || truffle.isBlank() ? CompileIdentity.coreEngineVersion() : truffle;
  }

  static void requireEngineMatches(Lock lock, String engineVersion) {
    Target jre = lock.targets().get("jre");
    if (jre == null || "dev".equals(engineVersion) || jre.cliVersion().equals(engineVersion)) {
      return;
    }
    throw new IllegalStateException("导出锁文件 jre.cliVersion=" + jre.cliVersion()
        + " 与实际加载的引擎版本 " + engineVersion + " 不一致：列车 pin bump 时须同步更新 " + DEFAULT_RESOURCE);
  }

  static String readResource(String name) {
    try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("导出锁文件不存在: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("导出锁文件无法读取: " + name, e);
    }
  }

  /**
   * 严格解析锁文件。
   *
   * @throws IllegalStateException 不是合法 JSON、含重复键或未知字段、缺字段或类型不符
   */
  public static Lock parse(String json) {
    JsonNode root;
    try {
      root = STRICT_JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw invalid("不是合法 JSON 或含重复键");
    }
    if (root == null || !root.isObject()) {
      throw invalid("必须是 JSON 对象");
    }
    requireExactFields(root, TOP_FIELDS, "顶层");
    JsonNode format = root.get("formatVersion");
    if (!format.isInt() || format.intValue() != 1) {
      throw invalid("formatVersion 必须是 1");
    }
    Map<String, Target> targets = new java.util.TreeMap<>();
    for (String name : List.of("jre", "node")) {
      JsonNode node = root.get(name);
      if (node.isNull() && "jre".equals(name)) {
        throw invalid("jre 不得为 null（只有 node 可在 ts 版本进锁文件前为 null）");
      }
      if (!node.isNull()) {
        targets.put(name, readTarget(node, name));
      }
    }
    return new Lock(targets);
  }

  private static Target readTarget(JsonNode node, String name) {
    if (!node.isObject()) {
      throw invalid(name + " 必须是对象或 null");
    }
    requireExactFields(node, TARGET_FIELDS, name);
    String cliName = requireText(node.get("cliName"), name + ".cliName");
    String cliVersion = requireText(node.get("cliVersion"), name + ".cliVersion");
    JsonNode kidsNode = node.get("trustAnchorKids");
    if (!kidsNode.isArray()) {
      throw invalid(name + ".trustAnchorKids 必须是字符串数组");
    }
    List<String> kids = new ArrayList<>();
    for (JsonNode kid : kidsNode) {
      kids.add(requireText(kid, name + ".trustAnchorKids[]"));
    }
    return new Target(cliName, cliVersion, kids);
  }

  private static String requireText(JsonNode node, String where) {
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      throw invalid(where + " 必须是非空字符串");
    }
    return node.textValue();
  }

  private static void requireExactFields(JsonNode node, Set<String> fields, String where) {
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      String name = it.next();
      if (!fields.contains(name)) {
        throw invalid(where + " 含未知字段: " + name);
      }
    }
    for (String field : fields) {
      if (!node.has(field)) {
        throw invalid(where + " 缺字段: " + field);
      }
    }
  }

  private static IllegalStateException invalid(String reason) {
    return new IllegalStateException("导出锁文件 " + DEFAULT_RESOURCE + " 无效: " + reason);
  }
}
