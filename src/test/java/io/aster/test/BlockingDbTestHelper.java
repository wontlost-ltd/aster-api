package io.aster.test;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 测试用 blocking DB 辅助（#57）。生产已移除 reactive 持久化，测试的 DB setup/cleanup 也
 * 统一到 blocking JDBC（Agroal {@link DataSource}），彻底不依赖 reactive-pg-client。
 *
 * <p>提供覆盖原 vertx {@code Pool} 用法的最小 API：无结果的 DML（DELETE/UPDATE/INSERT）、
 * 带参 DML、SELECT 遍历行、以及取单值。每次调用自获取/释放连接（try-with-resources），
 * autocommit，语义与原 {@code pgPool.query(...).execute().await()} 一致。
 */
@ApplicationScoped
public class BlockingDbTestHelper {

    @Inject
    DataSource dataSource;

    /** 执行一条无结果 DML（DELETE/UPDATE/INSERT），返回受影响行数。 */
    public int execute(String sql, Object... params) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("test DB execute failed: " + sql, e);
        }
    }

    /**
     * 在<b>显式声明的特权事务</b>内执行 DML，用于绕过 audit_logs 的 append-only 触发器
     * （V6.22.0）。
     *
     * <p>★为什么不让 {@link #execute} 默认绕过：那等于让测试跑在一个与生产不同的
     * 数据库语义上——生产禁止的操作在测试里静默成功，append-only 保护就永远测不到。
     * 现在测试必须像保留期清理任务一样**显式声明意图**，与生产走同一条豁免通道。
     *
     * <p>用途仅限两类：
     * <ol>
     *   <li>测试夹具清理（{@code DELETE FROM audit_logs}）；</li>
     *   <li>模拟攻击者篡改（{@code UPDATE audit_logs SET ...}），验证链能检出。</li>
     * </ol>
     *
     * <p>注意：{@code SET LOCAL} 只在当前事务内生效，故这里显式关闭 autocommit、
     * 手动提交，确保变量与 DML 处于同一事务；连接归还后不会泄漏到其他测试。
     */
    public int executeAsAuditMaintenance(String sql, Object... params) {
        return executeWithAuditGrant("aster.audit_retention_job", sql, params);
    }

    /**
     * 保留期清理通道：只应能 DELETE，<b>不应</b>能 UPDATE。
     * 与 {@link #executeAsAuditMaintenance} 同通道，命名区分是为了让断言意图自明。
     */
    public int executeAsAuditRetention(String sql, Object... params) {
        return executeWithAuditGrant("aster.audit_retention_job", sql, params);
    }

    /**
     * 篡改模拟通道：唯一能对 audit_logs 执行 UPDATE 的路径，仅供
     * 「验证篡改能被检出」类用例使用。
     *
     * <p>★与保留期通道**分开**是刻意的：两者性质不同，合用一个开关会让
     * 「清理任务」顺带获得改写审计记录的能力。生产代码从不设置本变量。
     */
    public int executeAsAuditTamper(String sql, Object... params) {
        return executeWithAuditGrant("aster.audit_tamper_simulation", sql, params);
    }

    private int executeWithAuditGrant(String settingName, String sql, Object... params) {
        try (Connection c = dataSource.getConnection()) {
            // ★不能无条件接管事务：调用方可能是 @Transactional 测试方法，
            //   此时连接已被 JTA 登记（enlisted），手动 setAutoCommit/commit/rollback
            //   会抛 "Attempting to rollback while enlisted in a transaction"。
            //   用 getAutoCommit() 判断：false = 已在外部事务中，我们只搭车不接管。
            boolean managedExternally = !c.getAutoCommit();

            if (managedExternally) {
                // 已在外部事务里：SET LOCAL 会在该事务内生效，由外部负责提交/回滚。
                applyGrant(c, settingName);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    bind(ps, params);
                    return ps.executeUpdate();
                }
            }

            // 自管事务：SET LOCAL 必须与 DML 同事务，故显式关掉 autocommit。
            c.setAutoCommit(false);
            try {
                applyGrant(c, settingName);
                int affected;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    bind(ps, params);
                    affected = ps.executeUpdate();
                }
                c.commit();
                return affected;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("test DB privileged execute failed: " + sql, e);
        }
    }

    /** settingName 为本类内的字面量常量，不接受外部输入，无注入面。 */
    private static void applyGrant(Connection c, String settingName) throws SQLException {
        try (PreparedStatement grant = c.prepareStatement(
                "SET LOCAL " + settingName + " = 'on'")) {
            grant.execute();
        }
    }

    /** 执行 SELECT，对每一行调用 consumer（consumer 从 {@link Row} 读列）。 */
    public void query(String sql, Consumer<Row> rowConsumer, Object... params) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rowConsumer.accept(new Row(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("test DB query failed: " + sql, e);
        }
    }

    /** 执行 SELECT 并返回所有行（列名→值 的有序 map 列表）。 */
    public List<Map<String, Object>> queryList(String sql, Object... params) {
        List<Map<String, Object>> out = new ArrayList<>();
        query(sql, row -> out.add(row.asMap()), params);
        return out;
    }

    /** 取单行单列 long（如 count(*)）；无行返回 0。 */
    public long queryLong(String sql, Object... params) {
        long[] result = {0L};
        boolean[] seen = {false};
        query(sql, row -> {
            if (!seen[0]) {
                result[0] = row.getLong(1);
                seen[0] = true;
            }
        }, params);
        return result[0];
    }

    /** 取单行单列 String（第 1 列）；无行返回 null。 */
    public String queryString(String sql, Object... params) {
        String[] result = {null};
        boolean[] seen = {false};
        query(sql, row -> {
            if (!seen[0]) {
                result[0] = row.getString(1);
                seen[0] = true;
            }
        }, params);
        return result[0];
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** 单行读取包装，按列名或列序号取值。 */
    public static final class Row {
        private final ResultSet rs;

        Row(ResultSet rs) {
            this.rs = rs;
        }

        public String getString(String col) {
            try { return rs.getString(col); } catch (SQLException e) { throw wrap(e); }
        }

        public String getString(int idx) {
            try { return rs.getString(idx); } catch (SQLException e) { throw wrap(e); }
        }

        public long getLong(String col) {
            try { return rs.getLong(col); } catch (SQLException e) { throw wrap(e); }
        }

        public long getLong(int idx) {
            try { return rs.getLong(idx); } catch (SQLException e) { throw wrap(e); }
        }

        public Object get(String col) {
            try { return rs.getObject(col); } catch (SQLException e) { throw wrap(e); }
        }

        Map<String, Object> asMap() {
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                var md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    m.put(md.getColumnLabel(i), rs.getObject(i));
                }
                return m;
            } catch (SQLException e) {
                throw wrap(e);
            }
        }

        private static RuntimeException wrap(SQLException e) {
            return new RuntimeException("test DB row read failed", e);
        }
    }
}
