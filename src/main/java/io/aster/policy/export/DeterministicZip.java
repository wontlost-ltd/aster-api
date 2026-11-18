package io.aster.policy.export;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 可复现 zip（ADR 0059 修订 3 P2-R14）：与 {@link java.util.zip.ZipOutputStream} 的 deflate 写法逐字段相同
 * （UTF-8 名置位 11、数据描述符置位 3、方法 8、{@code Deflater(9, nowrap)}、版本 20），只写文件条目、不写目录条目，
 * 条目名 {@code <root>/<path>} 按路径 UTF-8 字节序；每条 DOS 时间 1980-01-01 00:00、无 extra field、无注释。
 *
 * <p>不直接用 {@code ZipOutputStream}：JDK 把 1980-01-01 00:00 当作「1980 年以前」的哨兵值，会为每个条目追加按本地时区
 * 计算的扩展时间戳 extra field（0x5455），既违反「无 extra」又使字节随 JVM 默认时区变化。手写的本地头 / 中央目录 / EOCD
 * 只有固定字段，与 conformance {@code b-zh.zip} 的时间与标志形态一致。
 */
public final class DeterministicZip {
  /** 路径按 UTF-8 字节序（无符号）比较。 */
  public static final Comparator<String> UTF8_ORDER =
      (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  private static final int VERSION = 20;
  private static final int FLAGS = (1 << 11) | (1 << 3);
  private static final int METHOD_DEFLATED = 8;
  private static final int DOS_TIME = 0;
  /** 1980-01-01：((1980 − 1980) << 9) | (1 << 5) | 1。 */
  private static final int DOS_DATE = (1 << 5) | 1;

  private record Written(byte[] name, long crc, int csize, int size, int offset) {
  }

  private DeterministicZip() {
  }

  /** 按 UTF-8 字节序排序的空文件表。 */
  public static TreeMap<String, byte[]> newFileMap() {
    return new TreeMap<>(UTF8_ORDER);
  }

  public static byte[] write(String root, Map<String, byte[]> files) {
    TreeMap<String, byte[]> sorted = newFileMap();
    sorted.putAll(files);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<Written> written = new ArrayList<>();
    for (Map.Entry<String, byte[]> f : sorted.entrySet()) {
      byte[] name = (root + "/" + f.getKey()).getBytes(StandardCharsets.UTF_8);
      byte[] data = f.getValue();
      byte[] deflated = deflate(data);
      CRC32 crc = new CRC32();
      crc.update(data);
      Written w = new Written(name, crc.getValue(), deflated.length, data.length, out.size());
      out.writeBytes(localHeader(name));
      out.writeBytes(deflated);
      out.writeBytes(dataDescriptor(w));
      written.add(w);
    }
    int cdOffset = out.size();
    for (Written w : written) {
      out.writeBytes(centralEntry(w));
    }
    out.writeBytes(endOfCentralDirectory(written.size(), out.size() - cdOffset, cdOffset));
    return out.toByteArray();
  }

  private static byte[] deflate(byte[] data) {
    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
    try {
      deflater.setInput(data);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      while (!deflater.finished()) {
        out.write(buf, 0, deflater.deflate(buf));
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
  }

  private static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static byte[] localHeader(byte[] name) {
    ByteBuffer b = le(30 + name.length);
    b.putInt(0x04034b50).putShort((short) VERSION).putShort((short) FLAGS).putShort((short) METHOD_DEFLATED)
        .putShort((short) DOS_TIME).putShort((short) DOS_DATE)
        .putInt(0).putInt(0).putInt(0)
        .putShort((short) name.length).putShort((short) 0).put(name);
    return b.array();
  }

  private static byte[] dataDescriptor(Written w) {
    return le(16).putInt(0x08074b50).putInt((int) w.crc()).putInt(w.csize()).putInt(w.size()).array();
  }

  private static byte[] centralEntry(Written w) {
    ByteBuffer b = le(46 + w.name().length);
    b.putInt(0x02014b50).putShort((short) VERSION).putShort((short) VERSION).putShort((short) FLAGS)
        .putShort((short) METHOD_DEFLATED).putShort((short) DOS_TIME).putShort((short) DOS_DATE)
        .putInt((int) w.crc()).putInt(w.csize()).putInt(w.size())
        .putShort((short) w.name().length).putShort((short) 0).putShort((short) 0)
        .putShort((short) 0).putShort((short) 0).putInt(0).putInt(w.offset()).put(w.name());
    return b.array();
  }

  private static byte[] endOfCentralDirectory(int count, int cdSize, int cdOffset) {
    return le(22).putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
        .putShort((short) count).putShort((short) count).putInt(cdSize).putInt(cdOffset).putShort((short) 0).array();
  }
}
