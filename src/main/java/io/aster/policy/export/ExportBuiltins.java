package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 某 target 引擎的纯内置函数白名单（ADR 0059 修订 3 P2-R6，P1 T10 的生成文件）：{@code {"aliases":{…},"engine":…,"names":[…]}}。
 * 查名前先按 {@code aliases} 归一（{@code times} → {@code mul}）。
 *
 * <p>jre：{@code export/export-builtins-jre.json} 是 aster-lang-truffle {@code 52e96e1} 的
 * {@code src/main/resources/aster/truffle/export-builtins.json} 逐字节副本（{@code ExportBuiltinsTest} 与兄弟检出比对），
 * 列车 eco-1.0.50 后改读 truffle jar 内的原文件（计划 T14）。node：ts 的同形文件（计划 T22）复制进来之前为空，
 * 调用方据此返回 503 {@code export_runtime_unavailable}，与锁文件 {@code node:null} 一致。
 */
public final class ExportBuiltins {
  static final String JRE_RESOURCE = "export/export-builtins-jre.json";
  static final String NODE_RESOURCE_PROPERTY = "aster.export.node-builtins-resource";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, Optional<ExportBuiltins>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

  private final String engine;
  private final Set<String> names;
  private final Map<String, String> aliases;

  private ExportBuiltins(String engine, Set<String> names, Map<String, String> aliases) {
    this.engine = engine;
    this.names = Set.copyOf(names);
    this.aliases = Map.copyOf(aliases);
  }

  /** 该 target 的白名单；node 在 ts 文件就绪前为空。 */
  public static Optional<ExportBuiltins> forTarget(String target) {
    return CACHE.computeIfAbsent(target, t -> switch (t) {
      case "jre" -> Optional.of(load(JRE_RESOURCE));
      case "node" -> nodeResource().map(ExportBuiltins::load);
      default -> Optional.empty();
    });
  }

  /**
   * node 白名单的类路径资源：配置 {@value #NODE_RESOURCE_PROPERTY}（缺省不配）。T22 把 ts 的生成文件复制进仓后改为固定资源；
   * 在那之前只有测试 profile 指向测试副本，用以覆盖 Node 导出路径。
   */
  static Optional<String> nodeResource() {
    return org.eclipse.microprofile.config.ConfigProvider.getConfig()
        .getOptionalValue(NODE_RESOURCE_PROPERTY, String.class).filter(s -> !s.isBlank());
  }

  /** 测试钩子：清空缓存，使配置变更（不同测试 profile）生效。 */
  static void resetCache() {
    CACHE.clear();
  }

  static ExportBuiltins load(String resource) {
    try (InputStream in = ExportBuiltins.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("导出白名单不存在: " + resource);
      }
      JsonNode root = JSON.readTree(in);
      Set<String> names = new LinkedHashSet<>();
      root.get("names").forEach(n -> names.add(n.asText()));
      Map<String, String> aliases = new HashMap<>();
      root.get("aliases").properties().forEach(e -> aliases.put(e.getKey(), e.getValue().asText()));
      return new ExportBuiltins(root.get("engine").asText(), names, aliases);
    } catch (IOException e) {
      throw new UncheckedIOException("导出白名单无法读取: " + resource, e);
    }
  }

  public String engine() {
    return engine;
  }

  /** 名字（先按别名归一）是否为该引擎的纯内置函数。 */
  public boolean contains(String name) {
    return names.contains(aliases.getOrDefault(name, name));
  }
}
