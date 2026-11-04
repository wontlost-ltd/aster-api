package io.aster.policy.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 语法错误文案的来源（aster-api#423）：唯一真相源是 aster-lang-locales 的
 * {@code syntax-diagnostics/<locale-id>.json}（随 aster-lang-locales-en jar 分发，编辑器经 npm 读同一份）。
 *
 * <p>过渡期：pin 到的 locales 版本还没带这个目录时，退回本仓 {@code syntax-diagnostics/<语言>.json} 副本；
 * 两者同时在 classpath 上时，本地副本的每条文案必须与 locales 逐字一致，避免过渡期两边漂移。
 */
class SyntaxMessagesCatalogSourceTest {

    private static final Map<String, String> LOCALE_IDS =
        Map.of("en", "en-US", "zh", "zh-CN", "de", "de-DE", "hi", "hi-IN");

    private static void write(Path root, String name, String json) throws IOException {
        Path file = root.resolve("syntax-diagnostics").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static URLClassLoader loaderOf(Path root) throws IOException {
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    }

    @Test
    void prefersLocalesCatalogOverLocalCopy(@TempDir Path root) throws IOException {
        write(root, "zh-CN.json", "{\"syntaxError\": \"来自 locales\"}");
        write(root, "zh.json", "{\"syntaxError\": \"本地副本\"}");
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(SyntaxMessages.read(loader, "zh")).containsEntry("syntaxError", "来自 locales");
        }
    }

    @Test
    void fallsBackToLocalCopyWhenLocalesCatalogAbsent(@TempDir Path root) throws IOException {
        write(root, "de.json", "{\"syntaxError\": \"Syntaxfehler\"}");
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(SyntaxMessages.read(loader, "de")).containsEntry("syntaxError", "Syntaxfehler");
        }
    }

    @Test
    void unknownLanguageReadsNothing(@TempDir Path root) throws IOException {
        write(root, "en-US.json", "{\"syntaxError\": \"Syntax error\"}");
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(SyntaxMessages.read(loader, "fr")).isEmpty();
        }
    }

    /** 过渡期防漂移：locales 目录在 classpath 上时（本地 composite 或 pin 升级后），本地副本必须是它的逐字子集。 */
    @Test
    void localCopyAgreesWithLocalesCatalog() throws IOException {
        ClassLoader loader = SyntaxMessages.class.getClassLoader();
        assumeTrue(loader.getResource("syntax-diagnostics/en-US.json") != null,
            "pin 到的 aster-lang-locales 还没带 syntax-diagnostics 目录，跳过（aster-api#423）");
        for (String lang : List.of("en", "zh", "de", "hi")) {
            Map<String, String> shared = SyntaxMessages.readResource(loader, "syntax-diagnostics/" + LOCALE_IDS.get(lang) + ".json");
            Map<String, String> local = SyntaxMessages.readResource(loader, "syntax-diagnostics/" + lang + ".json");
            assertThat(shared).as(lang).isNotNull();
            assertThat(local).as(lang).isNotNull();
            assertThat(shared).as("%s：本地副本须与 locales 逐字一致", lang).containsAllEntriesOf(local);
        }
    }
}
