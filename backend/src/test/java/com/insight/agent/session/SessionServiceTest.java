package com.insight.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 会话落账：建会话幂等、状态可载、轮次只留最近三条。
 */
@Testcontainers
class SessionServiceTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("insight")
            .withUsername("insight")
            .withPassword("insight");

    static SessionService sessions;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        sessions = new SessionService(jdbc, new ObjectMapper());
    }

    @Test
    @DisplayName("会话建一次、复用多次；客户端传来的合法 id 被沿用")
    void ensureIsIdempotent() {
        UUID first = sessions.ensure(null);
        UUID again = sessions.ensure(first.toString());

        assertThat(again).isEqualTo(first);
        assertThat(sessions.load(first).turnCount()).isZero();
        assertThat(sessions.load(first).metric()).isEqualTo("gmv");
    }

    @Test
    @DisplayName("记录一轮之后：指标/范围/轮次都能载回")
    void recordThenLoad() {
        UUID id = sessions.ensure(null);
        UUID runId = UUID.randomUUID();
        sessions.record(id, "为什么华东GMV跌了", "为什么华东GMV跌了", "ATTRIBUTION",
                runId, "gmv", Map.of("REGION", "华东"));

        SessionService.State state = sessions.load(id);
        assertThat(state.metric()).isEqualTo("gmv");
        assertThat(state.scope()).containsEntry("REGION", "华东");
        assertThat(state.lastRunId()).isEqualTo(runId);
        assertThat(state.turnCount()).isEqualTo(1);
        assertThat(sessions.recent(id, 3)).hasSize(1);
    }

    @Test
    @DisplayName("只保留最近三条轮次")
    void recentIsLimited() {
        UUID id = sessions.ensure(null);
        for (int i = 1; i <= 4; i++) {
            sessions.record(id, "问题" + i, "问题" + i, "ATTRIBUTION",
                    UUID.randomUUID(), "gmv", Map.of());
        }

        List<SessionService.Turn> turns = sessions.recent(id, 3);
        assertThat(turns).hasSize(3);
        assertThat(turns.get(0).question()).isEqualTo("问题4");
        assertThat(turns.get(2).question()).isEqualTo("问题2");
        assertThat(sessions.load(id).turnCount()).isEqualTo(4);
    }
}
