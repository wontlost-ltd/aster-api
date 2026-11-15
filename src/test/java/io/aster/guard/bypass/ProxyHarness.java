package io.aster.guard.bypass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 阶段 B 代理拉起夹具（#466，计划 Task B1）：以子进程运行 {@code e2e/guard-bypass/injected-agent.mjs}，
 * 它再以 stdio 拉起钉死版本的已发布包 {@code @aster-cloud/aster-guard} 的 {@code aster-guard-mcp}，
 * 代理的上游是 {@code upstream-recorder.mjs}（每次被调用把 {@code {tool, arguments}} 追加到账本 JSONL）。
 *
 * <p>证明的是「装上就是这样」：真实 aster-api（本 Quarkus 测试实例，鉴权打开）+ 发布出来的 npm 包 + 脚本化 Agent。
 * 代理逻辑本身由 aster-guard 仓的夹具层（ADR 0054 §7）证明，这里不重复。
 *
 * <p>节点缺失：环境变量 {@value #REQUIRE_NODE_ENV}{@code =1}（CI 设置）时缺 Node 24+ 或 {@code node_modules} 即失败；
 * 未设置时以 {@link Assumptions#abort} 跳过并给出原因（本地开发机可以不装 Node）。
 */
public final class ProxyHarness {
  public static final String REQUIRE_NODE_ENV = "ASTER_GUARD_BYPASS_REQUIRE_NODE";
  /** 代理的 {@code --executor-id}：链上 consume / result 的执行方标识。 */
  public static final String EXECUTOR_ID = "aster-guard-mcp@guard-bypass";
  private static final int MIN_NODE_MAJOR = 24;
  /** 单次 Agent 运行的上限：拉起两层 Node 子进程 + 数次决策，正常 2–5 秒。 */
  private static final long RUN_TIMEOUT_SECONDS = 60;
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 上游账本的一行：工具真的被调用了一次。{@code argumentsJson} 为上游收到的参数原文（重新序列化）。 */
  public record LedgerLine(String tool, JsonNode arguments, String argumentsJson) {}

  /**
   * Agent 的一次场景运行：stdout 的单行 JSON {@code {scenario, calls: [{step, tool, arguments, isError, text,
   * structuredContent, meta}], selfServe}}。
   */
  public record AgentRun(JsonNode raw) {
    public List<JsonNode> calls() {
      List<JsonNode> calls = new ArrayList<>();
      raw.path("calls").forEach(calls::add);
      return calls;
    }

    /** 按步骤名取调用；不存在即断言失败。 */
    public JsonNode call(String step) {
      return calls().stream().filter(c -> step.equals(c.path("step").asText())).findFirst()
          .orElseThrow(() -> new AssertionError("Agent 输出中没有步骤 " + step + "：" + raw));
    }

    /** 代理的结构化返回 {@code asterGuard}：拒绝 / 阻断时在 {@code structuredContent}（上游未声明 outputSchema）。 */
    public static JsonNode guard(JsonNode call) {
      return call.path("structuredContent").path("asterGuard");
    }

    public JsonNode selfServe() {
      return raw.path("selfServe");
    }
  }

  private final Path dir;
  private final Map<String, String> env;
  private final Path ledger;

  private ProxyHarness(Path dir, Map<String, String> env, Path ledger) {
    this.dir = dir;
    this.env = env;
    this.ledger = ledger;
  }

  /**
   * 准备一个指向 {@code baseUrl} 的代理夹具；每次 {@link #run} 都新起一对 Agent / 代理 / 上游进程，
   * 账本跨运行累积在 {@code ledger}。
   *
   * @param policy {@code <module>/<function>}
   */
  public static ProxyHarness start(String baseUrl, String apiKey, String tenantId, String policy, Path ledger) {
    Path dir = requireNodeEnvironment();
    try {
      Files.deleteIfExists(ledger);
      Files.createFile(ledger);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, String> env = Map.of(
        "BYPASS_BASE_URL", baseUrl,
        "BYPASS_POLICY", policy,
        "BYPASS_TENANT", tenantId,
        "BYPASS_LEDGER", ledger.toAbsolutePath().toString(),
        "BYPASS_EXECUTOR", EXECUTOR_ID,
        "ASTER_GUARD_API_KEY", apiKey);
    return new ProxyHarness(dir, env, ledger);
  }

  /** 运行一个场景（{@code read | delete | refund | inject}），返回 Agent 的输出。 */
  public AgentRun run(String scenario) {
    // stdout / stderr 都落到文件：子进程挂起时 waitFor 的超时仍然生效（直接读管道会无限阻塞）。
    Path stdout = tempFile("agent-stdout-");
    Path stderr = tempFile("agent-stderr-");
    ProcessBuilder pb = new ProcessBuilder("node", "injected-agent.mjs", scenario)
        .directory(dir.toFile())
        .redirectOutput(stdout.toFile())
        .redirectError(stderr.toFile());
    pb.environment().putAll(env);
    try {
      Process p = pb.start();
      p.getOutputStream().close();
      if (!p.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        Assertions.fail("Agent 场景 " + scenario + " 超时；stderr：\n" + read(stderr));
      }
      String out = read(stdout).strip();
      if (p.exitValue() != 0 || out.isEmpty()) {
        Assertions.fail("Agent 场景 " + scenario + " 退出码 " + p.exitValue() + "；stdout：" + out
            + "\nstderr：\n" + read(stderr));
      }
      return new AgentRun(tree(out.substring(out.lastIndexOf('\n') + 1)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } finally {
      deleteQuietly(stdout);
      deleteQuietly(stderr);
    }
  }

  /** 上游账本：每行是上游工具的一次真实调用（业务侧副作用）。 */
  public List<LedgerLine> ledger() {
    List<LedgerLine> lines = new ArrayList<>();
    for (String line : readLines(ledger)) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode node = tree(line);
      lines.add(new LedgerLine(node.path("tool").asText(), node.path("arguments"), node.path("arguments").toString()));
    }
    return lines;
  }

  public List<LedgerLine> ledger(String tool) {
    return ledger().stream().filter(l -> l.tool().equals(tool)).toList();
  }

  /**
   * 定位 {@code e2e/guard-bypass} 并确认 Node 24+ 与依赖已安装；缺失时按 {@value #REQUIRE_NODE_ENV} 失败或跳过。
   * Gradle 测试任务的工作目录是项目根。
   */
  static Path requireNodeEnvironment() {
    Path dir = Path.of(System.getProperty("user.dir")).resolve("e2e/guard-bypass").normalize();
    String missing = missingPrerequisite(dir);
    if (missing != null) {
      if ("1".equals(System.getenv(REQUIRE_NODE_ENV))) {
        Assertions.fail(REQUIRE_NODE_ENV + "=1 但" + missing);
      }
      Assumptions.abort("本地无法运行阶段 B 代理用例：" + missing
          + "（安装 Node 24 后在 e2e/guard-bypass 执行 pnpm install --frozen-lockfile）");
    }
    return dir;
  }

  private static String missingPrerequisite(Path dir) {
    if (!Files.isRegularFile(dir.resolve("injected-agent.mjs"))) {
      return "找不到 " + dir.resolve("injected-agent.mjs");
    }
    if (!Files.isRegularFile(dir.resolve("node_modules/@aster-cloud/aster-guard/dist/mcp/cli.js"))) {
      return "缺少 " + dir.resolve("node_modules") + "（未执行 pnpm install --frozen-lockfile）";
    }
    String version = nodeVersion();
    if (version == null) {
      return "PATH 上没有可执行的 node";
    }
    int major = Integer.parseInt(version.replaceFirst("^v", "").split("\\.")[0]);
    if (major < MIN_NODE_MAJOR) {
      return "node " + version + " 低于 " + MIN_NODE_MAJOR + "（@aster-cloud/aster-guard engines）";
    }
    return null;
  }

  private static String nodeVersion() {
    try {
      Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || !out.matches("v\\d+(\\.\\d+)*")) {
        p.destroyForcibly();
        return null;
      }
      return out;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static JsonNode tree(String json) {
    try {
      return JSON.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> readLines(Path path) {
    try {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "(无法读取 stderr：" + e.getMessage() + ")";
    }
  }

  private static Path tempFile(String prefix) {
    try {
      return Files.createTempFile(prefix, ".log");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 临时日志，删除失败不影响结论
    }
  }
}
