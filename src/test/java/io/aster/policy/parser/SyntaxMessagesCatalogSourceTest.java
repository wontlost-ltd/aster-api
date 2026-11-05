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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语法错误文案的来源（aster-api#423 / #439）：唯一真相源是 aster-lang-locales 的
 * {@code syntax-diagnostics/<locale-id>.json}（随 aster-lang-locales-en jar 分发，编辑器经 npm 读同一份）。
 * 本仓不再保留副本，也不再按语言代码（{@code zh.json}）兜底。
 */
class SyntaxMessagesCatalogSourceTest {

    private static void write(Path root, String name, String json) throws IOException {
        Path file = root.resolve("syntax-diagnostics").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static URLClassLoader loaderOf(Path root) throws IOException {
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    }

    @Test
    void readsLocalesCatalogByLocaleId(@TempDir Path root) throws IOException {
        write(root, "zh-CN.json", "{\"syntaxError\": \"来自 locales\"}");
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(SyntaxMessages.read(loader, "zh")).containsEntry("syntaxError", "来自 locales");
        }
    }

    /** 旧的按语言代码命名的本地副本不再被读取：locales 目录缺失时得到空 Map，由调用方退回英文。 */
    @Test
    void ignoresLegacyLanguageCodeFile(@TempDir Path root) throws IOException {
        write(root, "de.json", "{\"syntaxError\": \"Syntaxfehler\"}");
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(SyntaxMessages.read(loader, "de")).isEmpty();
        }
    }

    @Test
    void unknownLanguageReadsNothing(@TempDir Path root) throws IOException {
        write(root, "en-US.json", "{\"syntaxError\": \"Syntax error\"}");
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(SyntaxMessages.read(loader, "fr")).isEmpty();
        }
    }

    /** 运行时 classpath 必须带四种语言的 locales 目录：缺了就意味着 pin 到的 aster-lang-locales-en 不对。 */
    @Test
    void classpathCarriesLocalesCatalogForEveryLanguage() {
        ClassLoader loader = SyntaxMessages.class.getClassLoader();
        for (String lang : List.of("en", "zh", "de", "hi")) {
            assertThat(SyntaxMessages.read(loader, lang)).as(lang).containsKey("syntaxError");
        }
    }
}
