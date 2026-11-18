package io.aster.policy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.DynamicCnlExecutor.DynamicExecutionException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 探针的平台期望值（ADR 0059 修订 3 P2-R12）：每条输入经 {@code /evaluate-json} 同一执行路径
 * （{@link DynamicCnlExecutor#executeCoreIrJson}，含 #558 的按声明类型转换）求值，单条墙钟上限 {@link #PROBE_TIMEOUT_MS}。
 * 超时与资源耗尽（栈溢出等）不在承诺内，丢弃；入参不合法（生成器在递归 Data 上的兜底）同样丢弃；成功写
 * {@code {"ok":true,"value":V}}（{@code V} 为值规范 JSON），其余执行失败写 {@code {"code":"EVAL_ERROR","ok":false}}。
 * 文件为值规范 JSON {@code {"probes":[{"entry","expected","input"}…]}}。
 */
public final class ProbeRunner {
  public static final long PROBE_TIMEOUT_MS = 1_000L;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String EVAL_ERROR = "{\"code\":\"EVAL_ERROR\",\"ok\":false}";

  /** {@code tests/probes.json} 的字节、条数与 SHA-256。 */
  public record ProbesFile(byte[] json, int count, String sha256) {
  }

  private ProbeRunner() {
  }

  public static ProbesFile run(String coreJson, String entry, List<JsonNode> inputs) {
    List<String> probes = new ArrayList<>();
    for (JsonNode input : inputs) {
      String expected = expected(coreJson, entry, input);
      if (expected != null) {
        probes.add("{\"entry\":" + ValueJson.write(JSON.getNodeFactory().textNode(entry))
            + ",\"expected\":" + expected + ",\"input\":" + ValueJson.write(input) + "}");
      }
    }
    byte[] bytes = ("{\"probes\":[" + String.join(",", probes) + "]}").getBytes(StandardCharsets.UTF_8);
    return new ProbesFile(bytes, probes.size(), PositionFreeHash.sha256Hex(new String(bytes, StandardCharsets.UTF_8)));
  }

  /** 一条探针的期望；返回 null 表示丢弃。 */
  private static String expected(String coreJson, String entry, JsonNode input) {
    Object context = input.isEmpty() ? null : JSON.convertValue(input, Map.class);
    Object result;
    try {
      result = DynamicCnlExecutor.executeCoreIrJson(coreJson, context, entry, PROBE_TIMEOUT_MS).result();
    } catch (DynamicExecutionException e) {
      if (e.kind() == DynamicExecutionException.Kind.TIMEOUT
          || e.kind() == DynamicExecutionException.Kind.INVALID_INPUT || resourceExhausted(e)) {
        return null;
      }
      return EVAL_ERROR;
    } catch (StackOverflowError | OutOfMemoryError e) {
      return null;
    }
    try {
      return "{\"ok\":true,\"value\":" + ValueJson.write(JSON.valueToTree(result)) + "}";
    } catch (IllegalArgumentException e) {
      return EVAL_ERROR;
    }
  }

  private static boolean resourceExhausted(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof StackOverflowError || t instanceof OutOfMemoryError) {
        return true;
      }
      if (t instanceof org.graalvm.polyglot.PolyglotException pe && pe.isResourceExhausted()) {
        return true;
      }
    }
    return false;
  }
}
