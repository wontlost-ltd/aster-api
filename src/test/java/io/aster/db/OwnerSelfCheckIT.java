package io.aster.db;

import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;

/** PostgreSQL 上验证 owner-selfcheck 的宽松路径。 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class OwnerSelfCheckIT {
    @Inject
    DataSource dataSource;

    @Test
    void lenientCallbackDoesNotAbortWhenRolesAreMissing() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET aster.owner_check = 'lenient'");
            // 测试容器默认不创建专用角色；此路径确认回调可在角色缺失时安全跳过。
            assertThatCode(() -> statement.execute(callbackSql()))
                .doesNotThrowAnyException();
        }
    }

    private static String callbackSql() throws Exception {
        try (var input = OwnerSelfCheckIT.class.getResourceAsStream("/db/migration/afterMigrate__owner_selfcheck.sql")) {
            if (input == null) throw new IllegalStateException("owner selfcheck callback resource missing");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("${ownerCheck}", "lenient");
        }
    }
}
