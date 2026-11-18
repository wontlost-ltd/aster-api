package io.aster.policy.export;

import aster.core.canonical.CanonicalJson;
import aster.core.ir.CoreIrFormatException;
import aster.core.ir.CoreIrJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Core IR 的去列投影哈希 {@code coreIr.positionFreeSha256}（ADR 0059 修订 1 A2）：两引擎的 IR 只在 origin 的列号上不同，
 * B 包本地重编译后按本哈希判定「同一 IR」。
 *
 * <p>算法：{@link CoreIrJson#read} 严格读入（版本预检 → schema → 严格绑定 → 不变式）→ 规范序列化 → 对每个非 null 的
 * {@code origin} 删除 {@code start.col} 与 {@code end.col}（缺失即抛）→ core {@link CanonicalJson} 序列化 → SHA-256 小写 hex。
 * 列车 eco-1.0.50 后（计划 T14）改为调用 core {@code PolicySourceCompiler.positionFreeSha256} 并删除本类。
 */
public final class PositionFreeHash {
  private static final ObjectMapper JSON = new ObjectMapper();

  private PositionFreeHash() {
  }

  /**
   * @throws IllegalArgumentException 不是合法 v2 Core IR，或某个 origin 缺 start / end 的 col
   */
  public static String sha256(String canonicalCoreJson) {
    JsonNode tree;
    try {
      tree = JSON.readTree(CoreIrJson.serialize(CoreIrJson.read(canonicalCoreJson)));
    } catch (CoreIrFormatException | JsonProcessingException e) {
      throw new IllegalArgumentException("Core IR 无法严格读入: " + e.getMessage(), e);
    }
    stripColumns(tree);
    return sha256Hex(CanonicalJson.canonicalJson(tree));
  }

  private static void stripColumns(JsonNode node) {
    if (node.isArray()) {
      node.forEach(PositionFreeHash::stripColumns);
      return;
    }
    if (!node.isObject()) {
      return;
    }
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      if ("origin".equals(field.getKey()) && field.getValue().isObject()) {
        removeCol(field.getValue(), "start");
        removeCol(field.getValue(), "end");
      } else {
        stripColumns(field.getValue());
      }
    }
  }

  private static void removeCol(JsonNode origin, String side) {
    JsonNode pos = origin.get(side);
    if (!(pos instanceof ObjectNode obj) || !obj.has("col")) {
      throw new IllegalArgumentException("origin." + side + " 缺少 col");
    }
    obj.remove("col");
  }

  static String sha256Hex(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }
}
