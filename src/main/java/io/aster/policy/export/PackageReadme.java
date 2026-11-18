package io.aster.policy.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 包内 {@code README.md}（ADR 0059 §6.2 / §5）：按界面语言选模板 {@code export/readme/<locale>.md}（en / zh / de；hi 与未知
 * locale 用 en），以 {@code {{key}}} 占位替换。内容只依赖包本身的字段，不含时间或请求信息，因而可复现。
 */
public final class PackageReadme {
  private static final List<String> LOCALES = List.of("en", "zh", "de");
  private static final Map<String, String> FACT_SOURCES_NOTE = Map.of(
      "en", " The policy profile declares these fact sources: ",
      "zh", "策略档案声明的事实来源：",
      "de", " Das Richtlinienprofil deklariert diese Faktenquellen: ");

  /** 模板需要的值。 */
  public record Data(String moduleName, int version, String target, String entry, String cliName, String cliVersion,
                     String jreCliVersion, String tsCliVersion, List<String> factSources) {
  }

  private PackageReadme() {
  }

  public static byte[] render(String uiLocale, Data d) {
    String locale = LOCALES.contains(uiLocale) ? uiLocale : "en";
    Map<String, String> values = new LinkedHashMap<>();
    values.put("moduleName", d.moduleName());
    values.put("version", Integer.toString(d.version()));
    values.put("target", d.target());
    values.put("entry", d.entry());
    values.put("cliName", d.cliName());
    values.put("cliVersion", d.cliVersion());
    values.put("jreVersion", d.jreCliVersion());
    values.put("tsVersion", d.tsCliVersion() == null ? "latest" : d.tsCliVersion());
    values.put("factSourcesNote", d.factSources().isEmpty() ? ""
        : FACT_SOURCES_NOTE.get(locale) + String.join(", ", d.factSources()) + ("zh".equals(locale) ? "。" : "."));
    String text = template(locale);
    for (Map.Entry<String, String> v : values.entrySet()) {
      text = text.replace("{{" + v.getKey() + "}}", v.getValue());
    }
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String template(String locale) {
    String name = "export/readme/" + locale + ".md";
    try (InputStream in = PackageReadme.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("README 模板不存在: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
