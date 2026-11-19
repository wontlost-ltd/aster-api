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
 * <p>jre：直接读 aster-lang-truffle jar 内的生成文件 {@code aster/truffle/export-builtins.json}（与平台实际加载的
 * truffle 引擎同一版本，不在本仓保留副本）。node：{@code export/export-builtins-node.json} 是锁文件 {@code node.cliVersion}
 * 对应 aster-lang-ts tag 的 {@code src/policy-package/export-builtins.json} 逐字节副本（平台不运行 ts，无 jar 可读；
 * {@code ExportBuiltinsTest} 与兄弟检出比对），随锁文件的 node 版本一起更新。
 */
public final class ExportBuiltins {
  static final String JRE_RESOURCE = "aster/truffle/export-builtins.json";
  static final String NODE_RESOURCE = "export/export-builtins-node.json";
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

  /** 该 target 的白名单；未知 target 为空。 */
  public static Optional<ExportBuiltins> forTarget(String target) {
    return CACHE.computeIfAbsent(target, t -> switch (t) {
      case "jre" -> Optional.of(load(JRE_RESOURCE));
      case "node" -> Optional.of(load(NODE_RESOURCE));
      default -> Optional.empty();
    });
  }

  /** 测试钩子：覆盖某 target 的白名单（{@code Optional.empty()} 模拟该 target 无白名单），{@code null} 恢复缺省。 */
  static void setForTest(String target, Optional<ExportBuiltins> builtins) {
    if (builtins == null) {
      CACHE.remove(target);
    } else {
      CACHE.put(target, builtins);
    }
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
