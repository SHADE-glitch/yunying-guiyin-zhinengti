package com.insight.agent.guard;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 数据库权限是第三条并列边界：即使 SqlGuard 被绕过，运行时账号也写不了业务表。
 * 应用自己的 trace 三张表例外——每次提问都要落痕。
 */
@Testcontainers
class ReadOnlyRoleTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("insight")
            .withUsername("insight")
            .withPassword("insight");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("业务表可读不可写；trace 三表可写")
    void runtimeRoleCannotWriteBusinessTables() {
        assertThatCode(() -> execute("select count(*) from voucher_order")).doesNotThrowAnyException();

        assertThatThrownBy(() -> execute("""
                insert into voucher_order (user_id, voucher_id, shop_id, region_id, category_id,
                                           pay_amount, status, created_at)
                values (1, 1, 1, 1, 1, 1, 'PAID', now())
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");

        assertThatCode(() -> execute("""
                insert into agent_run (id, question, status)
                values ('00000000-0000-0000-0000-000000000001', 'q', 'RUNNING')
                """)).doesNotThrowAnyException();
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                PG.getJdbcUrl(), "insight_ro", "insight_ro");
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
