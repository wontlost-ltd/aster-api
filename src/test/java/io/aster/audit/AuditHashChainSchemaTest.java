package io.aster.audit;

import io.aster.test.BlockingDbTestHelper;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 Task 3.1 - 验证 audit_logs 表哈希链字段迁移
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
public class AuditHashChainSchemaTest {

    @Inject
    BlockingDbTestHelper db;

    @Test
    void testAuditLogsTableHasHashChainColumns() {
        String sql = """
            SELECT column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_name = 'audit_logs'
            AND column_name IN ('prev_hash', 'current_hash')
            ORDER BY column_name
            """;

        Set<String> foundColumns = new HashSet<>();

        db.query(sql, row -> {
            String columnName = row.getString("column_name");
            String dataType = row.getString("data_type");
            String isNullable = row.getString("is_nullable");

            foundColumns.add(columnName);

            assertTrue(dataType.contains("character varying"),
                columnName + " should be VARCHAR, but was " + dataType);
            assertTrue("YES".equals(isNullable),
                columnName + " should allow NULL for backward compatibility");
        });

        assertTrue(foundColumns.contains("prev_hash"), "prev_hash column should exist");
        assertTrue(foundColumns.contains("current_hash"), "current_hash column should exist");
    }

    @Test
    void testHashChainIndexesExist() {
        String sql = """
            SELECT indexname
            FROM pg_indexes
            WHERE tablename = 'audit_logs'
            AND indexname IN ('idx_audit_logs_current_hash', 'idx_audit_logs_tenant_time')
            ORDER BY indexname
            """;

        Set<String> foundIndexes = new HashSet<>();
        db.query(sql, row -> foundIndexes.add(row.getString("indexname")));

        assertTrue(foundIndexes.contains("idx_audit_logs_current_hash"),
            "idx_audit_logs_current_hash index should exist");
        assertTrue(foundIndexes.contains("idx_audit_logs_tenant_time"),
            "idx_audit_logs_tenant_time index should exist");
    }

    @Test
    void testBackwardCompatibility() {
        // 插入一条旧记录（prev_hash 和 current_hash 为 NULL）
        String insertSql = """
            INSERT INTO audit_logs (event_type, timestamp, tenant_id, performed_by, success)
            VALUES ('TEST_EVENT', NOW(), 'test-tenant', 'test-user', true)
            RETURNING id, prev_hash, current_hash
            """;

        List<Map<String, Object>> rows = db.queryList(insertSql);
        assertTrue(rows.size() == 1, "Insert should return one row");
        Map<String, Object> result = rows.get(0);

        long id = ((Number) result.get("id")).longValue();
        assertTrue(id > 0, "Record should be inserted successfully");

        assertTrue(result.get("prev_hash") == null,
            "prev_hash should be NULL for backward compatibility");
        assertTrue(result.get("current_hash") == null,
            "current_hash should be NULL for backward compatibility");

        // 清理测试数据
        db.executeAsAuditMaintenance("DELETE FROM audit_logs WHERE id = ?", id);
    }
}
