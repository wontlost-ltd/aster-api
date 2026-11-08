package io.aster.guard.provenance;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 一次 guard 请求的逐字段来源（ADR 0040 §11 D1）：不在 {@code verified} 里的叶子路径一律为 declared。
 * 来源不传给规则，只进响应、决策行与收据；全部集合按字典序且不可变。
 *
 * @param verified                 verified 的点路径（服务端解析与签名事实的并集）
 * @param factIssuers              验签通过的签发方 {@code issuer#kid}
 * @param factSources              已验签载荷的 {@code sources}：路径 → 签发端来源标识
 * @param principalIdentityMatched 服务端是否证实 {@code principal.id} 即提交者（ADR 0040 §11 D2 第 3 条）；
 *                                 仅 {@code principal.type == "user"} 时非 null
 * @param factExpiresAt            全部事实中最早的 {@code expiresAt}；无事实为 null
 */
public record InputSources(SortedSet<String> verified, SortedSet<String> factIssuers,
                           SortedMap<String, String> factSources, Boolean principalIdentityMatched,
                           Instant factExpiresAt) {
  /** 全部 declared、无事实、未做身份判定。 */
  public static final InputSources NONE = new InputSources(null, null, null, null, null);

  public InputSources {
    verified = Collections.unmodifiableSortedSet(verified == null ? new TreeSet<>() : new TreeSet<>(verified));
    factIssuers = Collections.unmodifiableSortedSet(factIssuers == null ? new TreeSet<>() : new TreeSet<>(factIssuers));
    factSources = Collections.unmodifiableSortedMap(factSources == null ? new TreeMap<>() : new TreeMap<>(factSources));
  }

  /**
   * 路径是否 verified：自身或任一祖先路径在 {@code verified} 中（覆盖父对象的事实同时证实其下全部叶子）。
   * 点路径按嵌套解释，与验签时 claim 路径的解析一致。
   */
  public boolean isVerified(String path) {
    for (String p = path; p != null; p = parent(p)) {
      if (verified.contains(p)) {
        return true;
      }
    }
    return false;
  }

  /** 收据 {@code factSources} 的列表形态：{@code <path>=<source id>}，按路径字典序。 */
  public List<String> factSourceEntries() {
    return factSources.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList();
  }

  private static String parent(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? null : path.substring(0, dot);
  }
}
