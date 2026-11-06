package io.aster.policy.rest;

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
 * 评估诊断文案的来源（aster-api#445）：唯一真相源是 aster-lang-locales 的
 * {@code evaluation-diagnostics/<locale-id>.json}（随 aster-lang-locales-en jar 分发，aster-lang-locales#121），
 * 本仓不再保留副本。
 */
class EvaluationDiagnosticsCatalogSourceTest {

    private static void write(Path root, String name, String json) throws IOException {
        Path file = root.resolve("evaluation-diagnostics").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static URLClassLoader loaderOf(Path root) throws IOException {
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    }

    @Test
    void readsLocalesCatalogByLocaleId(@TempDir Path root) throws IOException {
        write(root, "zh-CN.json", "{\"entryAmbiguous.error\": \"来自 locales\"}");
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(EvaluationDiagnostics.read(loader, "zh")).containsEntry("entryAmbiguous.error", "来自 locales");
        }
    }

    @Test
    void missingCatalogReadsNothing(@TempDir Path root) throws IOException {
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(EvaluationDiagnostics.read(loader, "de")).isEmpty();
        }
    }

    @Test
    void unknownLanguageReadsNothing(@TempDir Path root) throws IOException {
        write(root, "en-US.json", "{\"entryAmbiguous.error\": \"Entry function is ambiguous\"}");
        try (URLClassLoader loader = loaderOf(root)) {
            assertThat(EvaluationDiagnostics.read(loader, "fr")).isEmpty();
        }
    }

    /** 运行时 classpath 必须带四种语言的 locales 目录：缺了就意味着 pin 到的 aster-lang-locales-en 不对。 */
    @Test
    void classpathCarriesLocalesCatalogForEveryLanguage() {
        ClassLoader loader = EvaluationDiagnostics.class.getClassLoader();
        for (String lang : List.of("en", "zh", "de", "hi")) {
            assertThat(EvaluationDiagnostics.read(loader, lang)).as(lang).containsKey("entryAmbiguous.error");
        }
    }

    /** 本仓不得再带同路径副本：同路径资源会遮蔽 locales jar，文案改了也不生效。 */
    @Test
    void catalogComesFromLocalesJarNotLocalCopy() {
        URL url = EvaluationDiagnostics.class.getClassLoader().getResource("evaluation-diagnostics/en-US.json");
        assertThat(url).isNotNull();
        // 来自 jar（aster-lang-locales-en）而不是本仓 build/resources/main 目录
        assertThat(url.getProtocol()).as("%s 应来自 aster-lang-locales-en jar", url).isEqualTo("jar");
    }
}
