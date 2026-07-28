package io.aster.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公开 REST API 契约守卫（golden）。
 *
 * <p>客户/集成方依赖 {@code /api/v1/*} 这层对外接口。此前 API 形态无锁定，
 * 任何意外的端点删除、路径变更、HTTP 方法变更都不会在合并前被捕获。本守卫把
 * 公开 API 的「{HTTP 方法} {完整路径}」清单锁定在 golden，与
 * {@code CoreIrSchemaAbiTest}（内部 IR 契约）形成「内部 IR + 外部 API」双契约。
 *
 * <p><b>实现</b>：直接扫描 {@code src/main/java} 源文件提取 JAX-RS 注解
 * （类级 {@code @Path} + 方法级 HTTP 注解 + 方法级 {@code @Path}）。不走 ArchUnit
 * classpath 导入——Quarkus 测试下 main 类不在 ArchUnit 默认扫描 location。
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li>新增端点：运行测试一次自动追加 golden（向后兼容，不算 breaking）；</li>
 *   <li>删除/改路径/改 HTTP 方法：触发失败 → 强制确认是否 breaking change，
 *       走 API 版本化（{@code /api/v2/}）或 deprecation 流程，而非悄悄改 v1。</li>
 * </ul>
 *
 * <p>仅锁公开面（{@code /api/v1/*}）。内部端点（{@code /api/internal/*}）和
 * 无版本前缀的旧端点可自由演进，不进契约。
 */
@DisplayName("公开 REST API 契约守卫")
class PublicApiContractTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java/io/aster");
    private static final Path GOLDEN_FILE =
        Paths.get("src/test/resources/api/public-api-contract.golden");

    /** 只锁这些前缀下的对外 API。 */
    private static final String PUBLIC_PREFIX = "/api/v1/";

    // 类/方法级 @Path("...")
    private static final Pattern PATH_ANN =
        Pattern.compile("@Path\\(\\s*\"([^\"]*)\"\\s*\\)");
    // 方法级 HTTP 注解
    private static final Pattern HTTP_ANN =
        Pattern.compile("@(GET|POST|PUT|DELETE|PATCH)\\b");

    @Test
    @DisplayName("公开 API 端点清单与 golden 一致（删/改端点触发失败）")
    void publicApiContractStable() throws IOException {
        Set<String> current = collectPublicEndpoints();

        if (!Files.exists(GOLDEN_FILE)) {
            writeGolden(current);
            return; // 首次生成
        }

        Set<String> golden = readGolden();

        List<String> removed = new ArrayList<>();
        for (String g : golden) {
            if (!current.contains(g)) {
                removed.add(g);
            }
        }
        List<String> added = new ArrayList<>();
        for (String c : current) {
            if (!golden.contains(c)) {
                added.add(c);
            }
        }

        assertThat(removed)
            .as("公开 API 端点被删除或路径/方法变更——这对集成方是 breaking change。"
                + "应走 API 版本化（/api/v2/）或 deprecation；若确属有意，更新 %s 并在 PR 说明。"
                + "（新增端点是向后兼容的，运行测试会自动追加 golden）", GOLDEN_FILE)
            .isEmpty();

        if (!added.isEmpty()) {
            Set<String> merged = new TreeSet<>(golden);
            merged.addAll(current);
            writeGolden(merged);
            assertThat(added)
                .as("发现新增公开 API 端点（向后兼容）。已自动追加进 %s，请把更新后的"
                    + " golden 一并提交以锁定契约。新增端点：", GOLDEN_FILE)
                .isEmpty();
        }
    }

    @Test
    @DisplayName("公开 API 端点非空（防扫描失效导致空契约误过）")
    void publicApiNotEmpty() throws IOException {
        assertThat(collectPublicEndpoints())
            .as("未扫描到任何 /api/v1/* 端点——源文件扫描可能失效，契约守卫将形同虚设")
            .isNotEmpty();
    }

    // ============================================================
    // 源文件扫描提取端点
    // ============================================================

    private static Set<String> collectPublicEndpoints() throws IOException {
        Set<String> endpoints = new TreeSet<>();
        for (Path javaFile : resourceFiles()) {
            String src = Files.readString(javaFile, StandardCharsets.UTF_8);
            String classPath = classLevelPath(src);
            if (classPath == null || !startsWithVersionPrefix(classPath)) {
                continue;
            }
            endpoints.addAll(methodEndpoints(src, classPath));
        }
        return endpoints;
    }

    /** *Resource.java 源文件（REST 端点定义都在这些类里）。 */
    private static List<Path> resourceFiles() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN_JAVA)) {
            return s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith("Resource.java"))
                .sorted()
                .toList();
        }
    }

    /** 类级 @Path（出现在第一个 class 声明之前的 @Path）。 */
    private static String classLevelPath(String src) {
        int classDecl = firstClassDeclIndex(src);
        Matcher m = PATH_ANN.matcher(src);
        if (m.find() && m.start() < classDecl) {
            return m.group(1);
        }
        return null;
    }

    /** 扫描类体内每个 HTTP 注解，往后就近找方法级 @Path，拼完整路径。 */
    private static Set<String> methodEndpoints(String src, String classPath) {
        Set<String> out = new TreeSet<>();
        Matcher http = HTTP_ANN.matcher(src);
        while (http.find()) {
            String method = http.group(1);
            // 在 HTTP 注解之后的小窗口里找方法级 @Path（紧邻方法注解块）；
            // 找不到则方法路径为空（= 类路径本身的端点）
            String window = src.substring(http.end(),
                Math.min(src.length(), http.end() + 400));
            String methodPath = nearestMethodPath(window);
            String full = joinPath(classPath, methodPath);
            if (full.startsWith(PUBLIC_PREFIX)) {
                out.add(method + " " + full);
            }
        }
        return out;
    }

    /**
     * 在 HTTP 注解后到方法体 '{' 之前的注解区里找 @Path（避免误抓下一个方法的）。
     *
     * <p>★定位方法体起点时必须**跳过字符串字面量内的花括号**。原实现取
     * {@code window.indexOf('{')}，而 {@code @Path("/{policyId}/rollback")} 里的
     * {@code '{'} 先出现，注解区被截断成 {@code @Path("/}，正则匹配不到——于是
     * **所有带路径参数的端点都从契约里静默消失**（实测 28 个，跨 13 个 resource 类，
     * 含 ADMIN 变更与 internal 接口）。契约文件因此长期少了这批端点，改动它们不会
     * 触发任何 breaking-change 告警。
     */
    private static String nearestMethodPath(String window) {
        int body = methodBodyStart(window);
        String anns = body >= 0 ? window.substring(0, body) : window;
        Matcher m = PATH_ANN.matcher(anns);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 找方法体 '{' 的下标，忽略字符串字面量内的花括号（如 {@code @Path("/{id}")}）。
     *
     * @return 方法体起始下标；找不到返回 -1
     */
    private static int methodBodyStart(String window) {
        boolean inString = false;
        for (int i = 0; i < window.length(); i++) {
            char c = window.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // 跳过转义字符
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                return i;
            }
        }
        return -1;
    }

    private static int firstClassDeclIndex(String src) {
        Matcher m = Pattern.compile("\\b(?:public\\s+)?(?:final\\s+)?class\\s+\\w+").matcher(src);
        return m.find() ? m.start() : src.length();
    }

    private static boolean startsWithVersionPrefix(String classPath) {
        String p = classPath.startsWith("/") ? classPath : "/" + classPath;
        return p.startsWith(PUBLIC_PREFIX) || PUBLIC_PREFIX.startsWith(p + "/");
    }

    private static String joinPath(String classPath, String methodPath) {
        String c = classPath == null ? "" : classPath;
        String mt = methodPath == null ? "" : methodPath;
        if (!c.startsWith("/")) {
            c = "/" + c;
        }
        if (mt.isEmpty()) {
            return stripTrailingSlash(c);
        }
        if (!mt.startsWith("/")) {
            mt = "/" + mt;
        }
        return stripTrailingSlash(c) + mt;
    }

    private static String stripTrailingSlash(String s) {
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ============================================================
    // golden IO
    // ============================================================

    private static Set<String> readGolden() throws IOException {
        Set<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(GOLDEN_FILE, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }

    private static void writeGolden(Set<String> endpoints) throws IOException {
        Files.createDirectories(GOLDEN_FILE.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Public REST API contract golden ({HTTP method} {path}).\n");
        sb.append("# 客户集成依赖此契约——删除/改路径/改方法 = breaking，走 /api/v2 或 deprecation。\n");
        sb.append("# 新增端点（向后兼容）：运行 PublicApiContractTest 会自动追加，连同本文件提交。\n");
        endpoints.forEach(e -> sb.append(e).append('\n'));
        Files.writeString(GOLDEN_FILE, sb.toString(), StandardCharsets.UTF_8);
    }
}
