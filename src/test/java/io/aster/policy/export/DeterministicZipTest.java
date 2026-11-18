package io.aster.policy.export;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 可复现 zip（修订 3 P2-R14）：同输入同字节、与默认时区无关、本地头与中央目录逐项一致、无 extra、标志位 3 与 11、只有方法 8。 */
class DeterministicZipTest {
  private static final Map<String, byte[]> FILES = Map.of(
      "tests/probes.json", "{\"probes\":[]}".getBytes(StandardCharsets.UTF_8),
      "README.md", "# 说明\n".getBytes(StandardCharsets.UTF_8),
      "SHA256SUMS", "x".repeat(2000).getBytes(StandardCharsets.UTF_8),
      "policy/policy.aster", "Module 块.遮蔽.\n".getBytes(StandardCharsets.UTF_8));

  private record Entry(String name, int flags, int method, int extraLength, long crc, long csize, long size) {
  }

  @Test
  void sameInputSameBytesAcrossTimeZones() {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
      byte[] kiritimati = DeterministicZip.write("块.遮蔽-v1-jre-b", FILES);
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      byte[] utc = DeterministicZip.write("块.遮蔽-v1-jre-b", FILES);
      byte[] again = DeterministicZip.write("块.遮蔽-v1-jre-b", new TreeMap<>(FILES));
      assertThat(kiritimati).isEqualTo(utc).isEqualTo(again);
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void structureMatchesTheRules() {
    byte[] zip = DeterministicZip.write("m-v3-jre-b", FILES);
    ByteBuffer b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
    int eocd = zip.length - 22;
    assertThat(b.getInt(eocd)).as("EOCD 恰在末尾、无注释").isEqualTo(0x06054b50);
    assertThat(b.getShort(eocd + 20)).isZero();
    int count = Short.toUnsignedInt(b.getShort(eocd + 10));
    int cdOffset = b.getInt(eocd + 16);
    List<Entry> central = new ArrayList<>();
    List<Integer> localOffsets = new ArrayList<>();
    int p = cdOffset;
    for (int i = 0; i < count; i++) {
      assertThat(b.getInt(p)).isEqualTo(0x02014b50);
      int nameLen = Short.toUnsignedInt(b.getShort(p + 28));
      int extraLen = Short.toUnsignedInt(b.getShort(p + 30));
      int commentLen = Short.toUnsignedInt(b.getShort(p + 32));
      central.add(new Entry(new String(zip, p + 46, nameLen, StandardCharsets.UTF_8), Short.toUnsignedInt(b.getShort(p + 8)),
          Short.toUnsignedInt(b.getShort(p + 10)), extraLen, Integer.toUnsignedLong(b.getInt(p + 16)),
          Integer.toUnsignedLong(b.getInt(p + 20)), Integer.toUnsignedLong(b.getInt(p + 24))));
      assertThat(commentLen).isZero();
      assertThat(Short.toUnsignedInt(b.getShort(p + 12))).as("DOS 时间 00:00").isZero();
      assertThat(Short.toUnsignedInt(b.getShort(p + 14))).as("DOS 日期 1980-01-01").isEqualTo((0 << 9) | (1 << 5) | 1);
      localOffsets.add(b.getInt(p + 42));
      p += 46 + nameLen + extraLen + commentLen;
    }
    assertThat(central).extracting(Entry::name).containsExactly(
        "m-v3-jre-b/README.md", "m-v3-jre-b/SHA256SUMS", "m-v3-jre-b/policy/policy.aster", "m-v3-jre-b/tests/probes.json");
    assertThat(localOffsets.getFirst()).isZero();
    for (int i = 0; i < count; i++) {
      Entry e = central.get(i);
      assertThat(e.flags()).as(e.name()).isEqualTo((1 << 11) | (1 << 3));
      assertThat(e.method()).isEqualTo(8);
      assertThat(e.extraLength()).isZero();
      int lh = localOffsets.get(i);
      assertThat(b.getInt(lh)).isEqualTo(0x04034b50);
      int nameLen = Short.toUnsignedInt(b.getShort(lh + 26));
      assertThat(Short.toUnsignedInt(b.getShort(lh + 6))).isEqualTo(e.flags());
      assertThat(Short.toUnsignedInt(b.getShort(lh + 8))).isEqualTo(e.method());
      assertThat(Short.toUnsignedInt(b.getShort(lh + 28))).as("本地头无 extra").isZero();
      assertThat(new String(zip, lh + 30, nameLen, StandardCharsets.UTF_8)).isEqualTo(e.name());
      int desc = lh + 30 + nameLen + (int) e.csize();
      assertThat(b.getInt(desc)).as("数据描述符签名").isEqualTo(0x08074b50);
      assertThat(Integer.toUnsignedLong(b.getInt(desc + 4))).isEqualTo(e.crc());
      assertThat(Integer.toUnsignedLong(b.getInt(desc + 12))).isEqualTo(e.size());
    }
  }

  @Test
  void utf8ByteOrderNotUtf16() {
    assertThat(DeterministicZip.UTF8_ORDER.compare("｡", "😀")).isNegative();
    assertThat(DeterministicZip.UTF8_ORDER.compare("README.md", "SHA256SUMS")).isNegative();
  }
}
