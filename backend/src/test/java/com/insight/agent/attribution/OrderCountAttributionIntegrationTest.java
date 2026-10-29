package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.AgentEvent;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.attribution.AttributionService.AttributionRun;
import com.insight.agent.attribution.persistence.AttributionNodeWriter;
import com.insight.agent.attribution.persistence.EvidenceBinding;
import com.insight.agent.attribution.sql.AttributionProperties;
import com.insight.agent.attribution.sql.JdbcSqlExecutor;
import com.insight.agent.guard.SqlGuard;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 第二个指标走通归因：状态机、分解数学、证据链都不认识指标，指标只从 valueExpression 进。
 * seed 埋的异常本身就是"订单量降 65%"，所以 order_count 口径下同样应定位到火锅。
 *
 * <p>两个入口各测一遍：配置默认 order_count（不含指标词的问题），和配置默认 gmv
 * 但问题点名"订单量"（按问题路由）。落库的 metric_code 必须和实际口径一致——
 * 曾经这里写死了配置值，问题路由后证据链标错指标。
 */
@Testcontainers
class OrderCountAttributionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("insight")
            .withUsername("insight")
            .withPassword("insight");

    static JdbcTemplate jdbc;
    static AttributionService service;

    @BeforeAll
    static void setUp() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        runScript("scripts/seed.sql");

        AttributionProperties props = new AttributionProperties();
        props.setAsOf(jdbc.queryForObject("select max(created_at) from voucher_order",
                Timestamp.class).toInstant());
        props.setMetric("order_count");
        // 规则版提议器：本测试考的是指标通路，不是模型提议
        props.setModelProposerEnabled(false);

        ObjectMapper mapper = new ObjectMapper();
        LlmClient stub = (messages, tools) -> LlmResponse.text("stub", 10, 10);
        service = new AttributionService(
                new JdbcSqlExecutor(jdbc, new SqlGuard()),
                props,
                new RunRecorder(jdbc, mapper),
                new AttributionNodeWriter(jdbc, new EvidenceBinding(jdbc), mapper),
                new AttributionScopeParser(jdbc),
                stub, mapper,
                new ClassPathResource("prompts/attribution-proposer.txt"),
                new ClassPathResource("prompts/attribution-reporter.txt"));
    }

    @Test
    void attributesTheSeededAnomalyUnderOrderCount() {
        List<AgentEvent> events = new ArrayList<>();
        AttributionRun run = service.run("为什么华东跌了", events::add);

        assertThat(run.result().outcome())
                .isIn(AttributionOutcome.DOMINANT_FOUND, AttributionOutcome.MULTI_FACTOR);
        assertThat(run.result().path()).anySatisfy(node -> {
            assertThat(node.dimension()).isEqualTo(Dimension.CATEGORY);
            assertThat(node.value()).isEqualTo("火锅");
            assertThat(node.contribution().abs()).isGreaterThanOrEqualTo(new BigDecimal("0.6"));
        });
        assertThat(jdbc.queryForObject(
                "select count(*) from attribution_node where run_id = ?",
                Integer.class, run.runId())).isGreaterThan(0);
        assertThat(jdbc.queryForList(
                "select distinct metric_code from attribution_node where run_id = ?",
                String.class, run.runId())).containsExactly("order_count");
    }

    @Test
    void routesToOrderCountWhenTheQuestionNamesIt() throws Exception {
        // 默认配置的指标是 gmv；问题点名"订单量"，路由应在不改配置的情况下切到 order_count
        AttributionProperties gmvProps = new AttributionProperties();
        gmvProps.setAsOf(jdbc.queryForObject("select max(created_at) from voucher_order",
                Timestamp.class).toInstant());
        gmvProps.setModelProposerEnabled(false);

        ObjectMapper mapper = new ObjectMapper();
        LlmClient stub = (messages, tools) -> LlmResponse.text("stub", 10, 10);
        AttributionService routed = new AttributionService(
                new JdbcSqlExecutor(jdbc, new SqlGuard()),
                gmvProps,
                new RunRecorder(jdbc, mapper),
                new AttributionNodeWriter(jdbc, new EvidenceBinding(jdbc), mapper),
                new AttributionScopeParser(jdbc),
                stub, mapper,
                new ClassPathResource("prompts/attribution-proposer.txt"),
                new ClassPathResource("prompts/attribution-reporter.txt"));

        AttributionRun run = routed.run("为什么华东订单量跌了", event -> { });

        assertThat(run.result().outcome())
                .isIn(AttributionOutcome.DOMINANT_FOUND, AttributionOutcome.MULTI_FACTOR);
        assertThat(run.result().path()).anySatisfy(node -> {
            assertThat(node.dimension()).isEqualTo(Dimension.CATEGORY);
            assertThat(node.value()).isEqualTo("火锅");
        });
        assertThat(jdbc.queryForList(
                "select distinct metric_code from attribution_node where run_id = ?",
                String.class, run.runId())).containsExactly("order_count");
    }

    private static void runScript(String path) throws Exception {
        String script = Files.readString(Path.of(path));
        try (Connection connection = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
