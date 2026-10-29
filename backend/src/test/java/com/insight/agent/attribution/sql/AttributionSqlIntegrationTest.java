package com.insight.agent.attribution.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insight.agent.attribution.AttributionConfig;
import com.insight.agent.attribution.AttributionEngine;
import com.insight.agent.attribution.AttributionNode;
import com.insight.agent.attribution.AttributionOutcome;
import com.insight.agent.attribution.AttributionPorts.ProbeResult;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.AttributionResult;
import com.insight.agent.attribution.ContributionMath;
import com.insight.agent.attribution.ContributionMath.Item;
import com.insight.agent.attribution.Dimension;
import com.insight.agent.attribution.Evidence;
import com.insight.agent.attribution.QueryBudget;
import com.insight.agent.attribution.persistence.EvidenceBinding;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 在真 PG 上验证迁移和归因 SQL。
 *
 * <p>为什么要起容器：迁移里的 {@code nulls not distinct}、{@code jsonb}、外键行为、
 * 以及"城市级 region_id 向上汇总成大区"这件事，都是 PG 特有或数据相关的，
 * 假执行器证明不了。单测证明"拿到结果后怎么反应"，这里证明"SQL 本身对"。
 *
 * <p>用确定性种子（setseed 0.42），所以断言可以写死具体大区——重跑结果一致。
 */
@Testcontainers
class AttributionSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("insight")
            .withUsername("insight")
            .withPassword("insight");

    static JdbcTemplate jdbc;
    static Instant asOf;
    static AttributionProperties props;

    @BeforeAll
    static void setUp() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DriverManagerDataSource ds = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);

        runScript("scripts/seed.sql");

        asOf = jdbc.queryForObject("select max(created_at) from voucher_order", Timestamp.class).toInstant();
        props = new AttributionProperties();
        props.setAsOf(asOf);
    }

    @Test
    @DisplayName("迁移：加列带默认值，历史行自动变成 LOOKUP")
    void migrationAddsModeWithBackfillDefault() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'agent_run'",
                String.class);
        assertThat(columns).contains("mode", "attribution_outcome");

        String modeDefault = jdbc.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_name = 'agent_run' and column_name = 'mode'", String.class);
        assertThat(modeDefault).contains("LOOKUP");

        String nullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_name = 'agent_run' and column_name = 'mode'", String.class);
        assertThat(nullable).isEqualTo("NO");

        UUID runId = UUID.randomUUID();
        jdbc.update("insert into agent_run (id, question, status) values (?, '历史行', 'SUCCEEDED')", runId);
        assertThat(jdbc.queryForObject("select mode from agent_run where id = ?", String.class, runId))
                .isEqualTo("LOOKUP");
    }

    @Test
    @DisplayName("迁移：节点表有两条外键，证据列可为空但必须指向真实步骤")
    void migrationCreatesNodeTableWithForeignKeys() {
        Integer fks = jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints "
                        + "where table_name = 'attribution_node' and constraint_type = 'FOREIGN KEY'",
                Integer.class);
        // run_id / parent_id / evidence_step_id
        assertThat(fks).isEqualTo(3);

        UUID runId = newRun();
        assertThatThrownBy(() -> insertRootNode(runId, 999_999L))
                .isInstanceOf(DataIntegrityViolationException.class);

        long nodeId = insertRootNode(runId, null);
        assertThat(nodeId).isPositive();
    }

    @Test
    @DisplayName("迁移：删除 run 级联删除节点")
    void deletingRunCascadesNodes() {
        UUID runId = newRun();
        insertRootNode(runId, null);

        jdbc.update("delete from agent_run where id = ?", runId);

        assertThat(jdbc.queryForObject(
                "select count(*) from attribution_node where run_id = ?", Integer.class, runId)).isZero();
    }

    @Test
    @DisplayName("证据绑定：指纹与步骤记录的 SQL 一致才放行")
    void evidenceBindingChecksFingerprint() {
        UUID runId = newRun();
        String sql = "select 1";
        Long stepId = jdbc.queryForObject(
                "insert into agent_step (run_id, step_no, type, tool_name, input) "
                        + "values (?, 1, 'TOOL_CALL', 'attribution_probe', ?::jsonb) returning id",
                Long.class, runId, "{\"sql\": \"" + sql + "\"}");

        EvidenceBinding binding = new EvidenceBinding(jdbc);
        binding.verify(stepId, Evidence.of(sql, 1));

        assertThatThrownBy(() -> binding.verify(stepId, Evidence.of("select 2", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不一致");
        assertThatThrownBy(() -> binding.verify(stepId + 1000, Evidence.of(sql, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("根口径：和直查 SQL 完全一致")
    void rootTotalsMatchesDirectQuery() {
        SqlSplitProvider provider = provider(new QueryBudget(16));

        Totals totals = provider.rootTotals();
        BigDecimal expectedCurrent = jdbc.queryForObject(
                "select sum(pay_amount) from voucher_order where status in ('PAID','USED','REFUNDED') "
                        + "and created_at >= ? and created_at < ?",
                BigDecimal.class, Timestamp.from(asOf.minusSeconds(30L * 86400)), Timestamp.from(asOf));

        assertThat(totals.current()).isEqualByComparingTo(expectedCurrent);
        assertThat(totals.baseline()).isPositive();
    }

    @Test
    @DisplayName("区域探针：城市级数据汇总成 4 个大区，合计等于根口径")
    void regionGroupsReconcileWithRoot() {
        SqlSplitProvider provider = provider(new QueryBudget(16));
        Totals root = provider.rootTotals();

        ProbeResult probe = provider.probe(Dimension.REGION, Scope.root());

        assertThat(probe.status()).isEqualTo(ProbeResult.Status.OK);
        assertThat(probe.items()).hasSize(4);
        BigDecimal sum = probe.items().stream().map(Item::current)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(root.current());
        assertThat(probe.items()).extracting(Item::value).contains("华东");
    }

    @Test
    @DisplayName("父级 scope：在华东范围内按品类拆，合计等于华东总量")
    void categoryProbeInheritsRegionScope() {
        SqlSplitProvider provider = provider(new QueryBudget(16));
        Scope eastChina = Scope.root().with(Dimension.REGION, "华东");
        BigDecimal eastChinaCurrent = provider.probe(Dimension.REGION, Scope.root()).items().stream()
                .filter(i -> i.value().equals("华东"))
                .map(Item::current)
                .findFirst()
                .orElseThrow();

        ProbeResult probe = provider.probe(Dimension.CATEGORY, eastChina);

        assertThat(probe.status()).isEqualTo(ProbeResult.Status.OK);
        BigDecimal sum = probe.items().stream().map(Item::current)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(eastChinaCurrent);
        assertThat(probe.items()).extracting(Item::value).contains("火锅");
    }

    @Test
    @DisplayName("门店探针：全量返回，折叠后变化量总和不变")
    void shopProbeFoldsWithoutLosingValue() {
        SqlSplitProvider provider = provider(new QueryBudget(16));

        ProbeResult probe = provider.probe(Dimension.SHOP, Scope.root());

        assertThat(probe.status()).isEqualTo(ProbeResult.Status.OK);
        assertThat(probe.items().size()).isGreaterThan(10);
        BigDecimal rawDelta = probe.items().stream().map(Item::delta)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Item> folded = ContributionMath.foldTopN(probe.items(), 10, AttributionEngine.AGGREGATE_LABEL);

        assertThat(folded).hasSize(11);
        assertThat(folded.get(10).value()).isEqualTo(AttributionEngine.AGGREGATE_LABEL);
        assertThat(folded.stream().map(Item::delta).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(rawDelta);
    }

    @Test
    @DisplayName("全平台口径：埋的异常在平台级只有约 -3%，低于门槛就不该硬给主因")
    void platformLevelChangeIsBelowGate() {
        SqlSplitProvider provider = provider(new QueryBudget(16));

        AttributionResult result = new AttributionEngine(AttributionConfig.defaults())
                .run(context -> Dimension.REGION, provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.NO_SIGNIFICANT_CHANGE);
        assertThat(result.probes()).isZero();
        assertThat(result.rootEvidence()).isNotNull();
    }

    @Test
    @DisplayName("根范围=华东：同样的问题，从华东出发就能定位到火锅")
    void engineFindsCategoryWithinRegionScope() {
        Scope eastChina = Scope.root().with(Dimension.REGION, "华东");
        SqlSplitProvider provider = new SqlSplitProvider(
                new JdbcSqlExecutor(jdbc, new com.insight.agent.guard.SqlGuard()),
                props, new QueryBudget(16), QueryWindow.of(asOf, props.getWindowDays()), eastChina);

        AttributionResult result = new AttributionEngine(configWithMaxDepth(1))
                .run(context -> Dimension.CATEGORY, provider);

        assertThat(result.outcome()).isEqualTo(AttributionOutcome.DOMINANT_FOUND);
        assertThat(result.path()).hasSize(1);

        AttributionNode node = result.path().get(0);
        assertThat(node.dimension()).isEqualTo(Dimension.CATEGORY);
        assertThat(node.value()).isEqualTo("火锅");
        assertThat(node.contribution()).isLessThanOrEqualTo(new BigDecimal("-0.6"));
        assertThat(node.evidence()).isNotNull();
        assertThat(result.rootEvidence()).isNotNull();
    }

    @Test
    @DisplayName("预算：一次真实归因的查询次数不超过配置上限")
    void engineStaysWithinBudget() {
        QueryBudget budget = new QueryBudget(3);
        Scope eastChina = Scope.root().with(Dimension.REGION, "华东");
        SqlSplitProvider provider = new SqlSplitProvider(
                new JdbcSqlExecutor(jdbc, new com.insight.agent.guard.SqlGuard()),
                props, budget, QueryWindow.of(asOf, props.getWindowDays()), eastChina);

        new AttributionEngine(AttributionConfig.defaults())
                .run(context -> Dimension.CATEGORY, provider);

        assertThat(budget.used()).isLessThanOrEqualTo(3);
    }

    private static AttributionConfig configWithMaxDepth(int maxDepth) {
        return new AttributionConfig(maxDepth, 8, new BigDecimal("0.6"), new BigDecimal("0.03"),
                new BigDecimal("100"), new BigDecimal("0.05"), 3, 2, 10);
    }

    private static SqlSplitProvider provider(QueryBudget budget) {
        return new SqlSplitProvider(new JdbcSqlExecutor(jdbc, new com.insight.agent.guard.SqlGuard()),
                props, budget, QueryWindow.of(asOf, props.getWindowDays()));
    }

    private static UUID newRun() {
        UUID runId = UUID.randomUUID();
        jdbc.update("insert into agent_run (id, question, status, mode) values (?, '归因测试', 'RUNNING', 'ATTRIBUTION')",
                runId);
        return runId;
    }

    private static long insertRootNode(UUID runId, Long evidenceStepId) {
        Long id = jdbc.queryForObject("""
                insert into attribution_node (run_id, depth, metric_code, window_start, window_end,
                                              baseline_start, baseline_end, verdict, evidence_step_id)
                values (?, 0, 'gmv', now() - interval '30 days', now(),
                        now() - interval '60 days', now() - interval '30 days', 'ROOT', ?)
                returning id
                """, Long.class, runId, evidenceStepId);
        return id == null ? 0L : id;
    }

    /** 整段脚本在同一个连接里执行：setseed 是会话级的，换连接就不是确定性数据了。 */
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
