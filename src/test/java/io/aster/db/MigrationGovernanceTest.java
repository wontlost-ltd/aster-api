package io.aster.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway migration 治理守卫（纯文件系统，无需 Quarkus/DB）。
 *
 * <p>生产 Flyway 配置是 {@code out-of-order=false} 且**不设** {@code ignore-migration-patterns}
 * （application.properties:194，及 :190-191 的说明），即生产本身就 fail-fast。宽松的
 * {@code out-of-order=true} + {@code ignore-migration-patterns=*:missing} 只加在
 * {@code %dev}/{@code %test}（:195-198）—— 而那正是开发者日常运行的 profile：
 * migration 被改动或乱序会在本地被**静默吞掉**，推到生产才炸。
 * 本守卫把"migration 不可变 + 版本唯一 + 命名规范"用测试强制执行，
 * 让违规在宽松 profile 下也能于合并前暴露。
 *
 * <p><b>checksum golden</b>：每个 migration 的内容 SHA-256 锁定在
 * {@code src/test/resources/db/migration-checksums.golden}。修改已发布 migration 会触发失败，
 * 强制开发者：要么还原（migration 应不可变），要么新增 migration 表达变更 + 更新 golden 并说明理由。
 * ★golden **只在文件不存在时整体生成**（见 migrationChecksumStable 的 !Files.exists 分支）；
 * 文件已存在时不会自动追加或更新，新增/改动 migration 后须手工维护 golden 条目。
 */
@DisplayName("Flyway migration 治理守卫")
class MigrationGovernanceTest {

    private static final Path MIGRATION_DIR =
        Paths.get("src/main/resources/db/migration");
    private static final Path GOLDEN_FILE =
        Paths.get("src/test/resources/db/migration-checksums.golden");
    // V<version>__<description>.sql —— 版本段允许语义化点分（如 6.10.0）
    private static final Pattern MIGRATION_NAME =
        Pattern.compile("^V(\\d+(?:\\.\\d+)*)__([a-zA-Z0-9_]+)\\.sql$");

    @Test
    @DisplayName("migration 命名规范：V<version>__<desc>.sql")
    void migrationNamingConvention() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : versionedMigrations()) {
            String name = f.getFileName().toString();
            if (!MIGRATION_NAME.matcher(name).matches()) {
                violations.add(name);
            }
        }
        assertThat(violations)
            .as("不符合 V<version>__<desc>.sql 命名的 migration（会让 Flyway 解析失败或乱序）")
            .isEmpty();
    }

    @Test
    @DisplayName("migration 版本号唯一（out-of-order 下重复版本号 = 危险）")
    void migrationVersionsUnique() throws IOException {
        Map<String, List<String>> byVersion = new TreeMap<>();
        for (Path f : versionedMigrations()) {
            Matcher m = MIGRATION_NAME.matcher(f.getFileName().toString());
            if (m.matches()) {
                byVersion.computeIfAbsent(m.group(1), v -> new ArrayList<>())
                    .add(f.getFileName().toString());
            }
        }
        List<Map.Entry<String, List<String>>> dups = byVersion.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .toList();
        assertThat(dups)
            .as("重复版本号的 migration——out-of-order=true 下 Flyway 行为不确定，必须唯一")
            .isEmpty();
    }

    @Test
    @DisplayName("已发布 migration 不可变（checksum golden 锁定内容）")
    void migrationChecksumStable() throws IOException {
        // 计算当前每个 migration 的 SHA-256
        Map<String, String> current = new TreeMap<>();
        for (Path f : versionedMigrations()) {
            current.put(f.getFileName().toString(), sha256(f));
        }

        if (!Files.exists(GOLDEN_FILE)) {
            // 首次：生成 golden（提交后即锁定）
            writeGolden(current);
            // 生成即视为通过——下次运行起强制不可变
            return;
        }

        Map<String, String> golden = readGolden();
        List<String> modified = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : golden.entrySet()) {
            String cur = current.get(e.getKey());
            if (cur == null) {
                missing.add(e.getKey());
            } else if (!cur.equals(e.getValue())) {
                modified.add(e.getKey());
            }
        }

        assertThat(modified)
            .as("已发布 migration 内容被修改——migration 应不可变。"
                + "若变更确属必要：新增一个 migration 表达变更，而非改历史；"
                + "若确需修订历史（极少）：更新 %s 并在 PR 说明理由+回放验证", GOLDEN_FILE)
            .isEmpty();
        assertThat(missing)
            .as("golden 登记的 migration 文件被删除——已发布 migration 不应删除（破坏既有环境的 checksum）")
            .isEmpty();
    }

    @Test
    @DisplayName("新增 migration 必须登记进 golden（防漏检）")
    void newMigrationsRegistered() throws IOException {
        if (!Files.exists(GOLDEN_FILE)) {
            return; // 首次运行由 checksum 测试生成
        }
        Set<String> golden = new HashSet<>(readGolden().keySet());
        List<String> unregistered = new ArrayList<>();
        for (Path f : versionedMigrations()) {
            if (!golden.contains(f.getFileName().toString())) {
                unregistered.add(f.getFileName().toString());
            }
        }
        assertThat(unregistered)
            .as("新增 migration 未登记 golden——请手工把 `文件名=SHA256` 追加进 %s 并一并提交"
                + "（锁定其不可变性）。注意：golden 只在**首次、文件不存在时**整体生成，"
                + "已存在时不会自动追加，重跑本测试不会让它变绿。"
                + "取 checksum：shasum -a 256 src/main/resources/db/migration/<文件名>", GOLDEN_FILE)
            .isEmpty();
    }

    @Test
    @DisplayName("Flyway 治理风险配置有意识保留（文档化提醒）")
    void flywayRiskConfigDocumented() throws IOException {
        Path props = Paths.get("src/main/resources/application.properties");
        String content = Files.readString(props, StandardCharsets.UTF_8);
        // 这两项是已知治理风险（静默吞 migration 漂移）。本守卫测试正是它们的补偿控制：
        // 若未来收紧配置（去掉 out-of-order），本断言更新即可。此处仅确认配置未被悄悄改动
        // 导致守卫的前提假设失效。
        boolean outOfOrder = content.contains("quarkus.flyway.out-of-order=true");
        boolean ignoreMissing = content.contains("ignore-migration-patterns=*:missing");
        // 不强制要求保留——只要其一存在，checksum golden 守卫就是必要的补偿控制
        assertThat(outOfOrder || ignoreMissing)
            .as("若已收紧 Flyway 配置（移除 out-of-order + ignore-missing），"
                + "可放宽本测试；当前配置宽松，checksum 守卫为必要补偿")
            .isTrue();
    }

    /**
     * {@code policy_versions.tenant_id} 在被引用之前必须已被"补回"（issue #283）。
     *
     * <p>★背景（实测，非静态推理）：{@code V6.1.0} 的文件后半段是一段**可执行的**
     * "回滚" SQL —— 它先 {@code ADD COLUMN} 五列、又在同一个文件里立刻
     * {@code DROP COLUMN} 全删。干净库只跑到 V6.1.0 时实测
     * {@code tenant_id} 的列数为 <b>0</b>。
     *
     * <p>而 {@code V6.8.0} / {@code V6.9.0} / {@code V6.11.0} 都引用 tenant_id 却
     * <b>没有任何列存在性保护</b>——{@code CREATE INDEX IF NOT EXISTS} 只保护
     * <b>索引名</b>，不保护<b>列引用</b>。链条目前不坏，唯一原因是
     * {@code V6.4.0} 又把这五列补了回来（该文件开头写着"修复先前脚本中意外回滚
     * 导致的缺失"），而 {@code 6.4.0 < 6.8.0}。全链实测 tenant_id 列数为 <b>1</b>。
     *
     * <p>即这是一条**靠版本号顺序维系的隐式不变量**，此前无人守护：一旦有人删掉
     * 或改动 V6.4.0 的补回语句，迁移链会在 6.8.0 硬失败（SQLSTATE 42703），
     * 而这类错误只在真实建库时才暴露。
     *
     * <p>Flyway 迁移一经发布不可改内容（校验和；本仓已刻意移除
     * {@code repair-at-start}，见 application.properties 注释），故不能去修 V6.1.0，
     * 只能用本测试把"补回必须早于引用"这条顺序钉死。
     */
    @Test
    @DisplayName("tenant_id 的补回迁移必须早于所有引用它的迁移（issue #283）")
    void tenantIdRestoredBeforeFirstUse() throws IOException {
        // ★只匹配**明确把 tenant_id 归属到 policy_versions** 的两种写法：
        //     1) ALTER TABLE policy_versions ... ADD COLUMN tenant_id   （提供者）
        //     2) ON policy_versions (... tenant_id ...)                  （索引引用者）
        //
        //   为什么不做更宽的匹配（这一版是收窄后的结果，前几版都误报）：
        //     · "同一语句里同时出现两词" → V5.2.0 的
        //       `INSERT INTO workflow_state (... tenant_id ...) SELECT ... FROM policy_versions`
        //       会命中，但那个 tenant_id 属于 workflow_state；
        //     · `CREATE TABLE security_event (tenant_id ...)`（V6.0.0）同理。
        //   tenant_id 这个列名在本库至少 4 张表上存在，任何不绑定表名的判据都会误报。
        //
        //   代价：`SELECT tenant_id ... FROM policy_versions` 这种形态不被计为引用者
        //   （V6.8.0 的物化视图就是这样）。可以接受——该文件里同时有形态 2 的
        //   CREATE INDEX，仍会被覆盖到；且本测试要守的是**顺序不变量**，
        //   宁可漏判也不能误判（误判会让门禁在无辜改动上变红，最终被人关掉）。
        Pattern provides = Pattern.compile(
            "ALTER\\s+TABLE\\s+policy_versions\\b[\\s\\S]*?"
                + "ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?tenant_id\\b",
            Pattern.CASE_INSENSITIVE);
        // ★同一个文件里若**又把列删掉**，它就不是有效提供者。
        //   V6.1.0 正是这种：先 ADD 五列、再 DROP 五列，净效果是"没有该列"。
        //   不排除它的话，earliestProvider 会停在 6.1.0，后面所有引用都被判为"已满足"，
        //   门禁就永远不会变红——实测确认过：删掉 V6.4.0 的补回语句后本用例仍绿。
        Pattern revokes = Pattern.compile(
            "ALTER\\s+TABLE\\s+policy_versions\\b[\\s\\S]*?"
                + "DROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?tenant_id\\b",
            Pattern.CASE_INSENSITIVE);
        Pattern indexOnPolicyVersions = Pattern.compile(
            "ON\\s+policy_versions\\s*\\([^)]*\\btenant_id\\b", Pattern.CASE_INSENSITIVE);

        String earliestProvider = null;
        List<String> usersWithoutGuard = new ArrayList<>();

        // ★必须按**语义版本**排序，不能用文件名字典序：字典序下
        //   "V6.11.0" < "V6.4.0"（'1' < '4'），会把 V6.11.0 误判成"早于提供者"。
        //   Flyway 自己走的是版本号顺序，这里必须与之一致。
        for (Path f : migrationsInFlywayOrder()) {
            String name = f.getFileName().toString();
            String sql = stripComments(Files.readString(f, StandardCharsets.UTF_8));

            boolean addsColumn = provides.matcher(sql).find();
            boolean dropsColumn = revokes.matcher(sql).find();
            if (addsColumn && !dropsColumn) {
                if (earliestProvider == null) earliestProvider = name;
                continue; // 净效果是"提供了该列"
            }
            if (addsColumn) {
                continue; // 加了又删（V6.1.0）——净效果为无，既不是提供者也不算引用者
            }

            // 纯引用者：逐语句判断是否命中上述两种形态之一。
            boolean referencesColumn = false;
            for (String stmt : sql.split(";")) {
                if (indexOnPolicyVersions.matcher(stmt).find()) {
                    referencesColumn = true;
                    break;
                }
            }
            if (referencesColumn && earliestProvider == null) {
                usersWithoutGuard.add(name);
            }
        }

        assertThat(earliestProvider)
            .as("没有任何迁移 ADD COLUMN tenant_id —— 不变量的前提消失了")
            .isNotNull();

        assertThat(usersWithoutGuard)
            .as("这些迁移在 policy_versions 上引用 tenant_id，但在它们之前没有任何迁移"
                + "提供该列（%s 是最早的提供者）。V6.1.0 会删掉该列，"
                + "故引用者必须排在提供者之后，否则干净库建库时报 42703。",
                earliestProvider)
            .isEmpty();
    }

    // ============================================================
    // helpers
    // ============================================================

    /**
     * 去掉 SQL 行注释。
     *
     * <p>★必需：多个 migration 的**注释里**提到 policy_versions 与 tenant_id
     * （例如 V6.8.0 开头解释"按 tenant_id 聚合"），若不剥离会被当成真实引用而误报。
     * 判据必须落在可执行 SQL 上，不能落在描述性文字上。
     */
    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "");
    }

    /**
     * 按 Flyway 的**版本号顺序**（而非文件名字典序）列出迁移。
     *
     * <p>字典序下 {@code V6.11.0 < V6.4.0}（逐字符比较 '1' < '4'），
     * 与 Flyway 的实际执行顺序相反。任何依赖"谁在谁之前"的断言都必须用本方法。
     */
    private static List<Path> migrationsInFlywayOrder() throws IOException {
        return versionedMigrations().stream()
            .sorted(Comparator.comparing(p -> versionKey(p.getFileName().toString())))
            .toList();
    }

    /** 把 V6.11.0 变成可比较的定宽串（006.011.000），供版本号排序用。 */
    private static String versionKey(String fileName) {
        var m = MIGRATION_NAME.matcher(fileName);
        if (!m.matches()) return fileName;
        StringBuilder sb = new StringBuilder();
        for (String part : m.group(1).split("\\.")) {
            sb.append(String.format("%04d.", Integer.parseInt(part)));
        }
        return sb.toString();
    }

    private static List<Path> versionedMigrations() throws IOException {
        try (Stream<Path> s = Files.list(MIGRATION_DIR)) {
            return s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("V") && n.endsWith(".sql");
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        }
    }

    private static String sha256(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // 规范化换行（CRLF→LF）使 checksum 不受 git autocrlf 影响
            byte[] bytes = Files.readString(f, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, String> readGolden() throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(GOLDEN_FILE)) {
            p.load(in);
        }
        Map<String, String> out = new TreeMap<>();
        p.forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }

    private static void writeGolden(Map<String, String> checksums) throws IOException {
        Files.createDirectories(GOLDEN_FILE.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Flyway migration checksum golden (SHA-256, LF-normalized).\n");
        sb.append("# 已发布 migration 不可变——修改历史 migration 会触发 MigrationGovernanceTest 失败。\n");
        sb.append("# 新增 migration：本文件已存在时**不会**自动追加，须手工加一行\n");
        sb.append("#   `文件名=SHA256`（shasum -a 256 <migration 文件>），连同本文件一起提交。\n");
        checksums.forEach((name, hash) -> sb.append(name).append('=').append(hash).append('\n'));
        Files.writeString(GOLDEN_FILE, sb.toString(), StandardCharsets.UTF_8);
    }
}
