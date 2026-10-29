package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.AgentEvent;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.attribution.AttributionScopeParser.AmbiguousScopeException;
import com.insight.agent.attribution.AttributionService.AttributionRun;
import com.insight.agent.attribution.persistence.AttributionNodeWriter;
import com.insight.agent.attribution.persistence.EvidenceBinding;
import com.insight.agent.attribution.sql.AttributionProperties;
import com.insight.agent.attribution.sql.JdbcSqlExecutor;
import com.insight.agent.attribution.sql.SqlExecutor;
import com.insight.agent.guard.SqlGuard;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import com.insight.agent.llm.ToolSpec;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * 编排层的端到端测试：问题 → 根范围 → 查询 → 落库 → 事件。
 *
 * <p>这一层最容易被写成"跑通就行"，所以断言分两半：结论对不对（path），
 * 和留痕全不全（agent_run / agent_step / attribution_node 三张表是否对得上）。
 * 后者才是"结论可回溯"的证明。
 */
@Testcontainers
class AttributionServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("insight")
            .withUsername("insight")
            .withPassword("insight");

    static JdbcTemplate jdbc;
    static AttributionProperties props;
    static AttributionService service;
    static ScriptedLlm llm;

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

        Instant asOf = jdbc.queryForObject("select max(created_at) from voucher_order", Timestamp.class).toInstant();
        props = new AttributionProperties();
        props.setAsOf(asOf);

        llm = new ScriptedLlm();
        service = buildService(new JdbcSqlExecutor(jdbc, new SqlGuard()));
    }

    private static AttributionService buildService(SqlExecutor executor) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return new AttributionService(
                executor,
                props,
                new RunRecorder(jdbc, mapper),
                new AttributionNodeWriter(jdbc, new EvidenceBinding(jdbc), mapper),
                new AttributionScopeParser(jdbc),
                llm, mapper,
                new ClassPathResource("prompts/attribution-proposer.txt"),
                new ClassPathResource("prompts/attribution-reporter.txt"));
    }

    /**
     * 按工具名回放的假模型：提议维度返回 CATEGORY，写结论返回一条有证据的 fact
     * 和一条编造的 fact（88% 不在证据里），用来验证"编的会被降级"。
     */
    static final class ScriptedLlm implements LlmClient {

        final AtomicBoolean proposerFails = new AtomicBoolean(false);
        final AtomicBoolean proposerCalled = new AtomicBoolean(false);
        final AtomicInteger findingsCalls = new AtomicInteger();

        @Override
        public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
            String tool = tools.get(0).name();
            if ("propose_dimension".equals(tool)) {
                proposerCalled.set(true);
                if (proposerFails.get()) {
                    throw new IllegalStateException("model timeout");
                }
                String prompt = messages.get(1).getText();
                String used = line(prompt, "已试过的维度：");
                String root = line(prompt, "根范围：");
                for (String candidate : new String[]{"CATEGORY", "REGION", "SHOP"}) {
                    if (!used.contains(candidate) && !root.contains(candidate)) {
                        // 带上真实量级的 token：全 0 会让 token 预算永远不触发，测出来的是假的
                        return new LlmResponse("", List.of(new ToolCallReq("c1", tool,
                                "{\"dimension\":\"" + candidate + "\"}")), 1200, 80);
                    }
                }
                return new LlmResponse("", List.of(), 0, 0);
            }
            findingsCalls.incrementAndGet();
            // 有证据的那条直接从证据里抄：手写数字会随种子/窗口漂移，
            // 抄下来才真的在测"抄证据的能留下、编的留不下"
            String grounded = "火锅" + line(messages.get(1).getText(), "  火锅：");
            String args = """
                    {"facts":["%s",
                              "某竞品门店全线崩盘 88%%"],
                     "hypothesis":["可能是周边竞品活动分流"],
                     "uncertainty":["缺少竞品活动数据"]}
                    """.formatted(grounded);
            return new LlmResponse("", List.of(new ToolCallReq("c2", tool, args)), 1500, 200);
        }

        private static String line(String text, String prefix) {
            for (String row : text.split("\n")) {
                if (row.startsWith(prefix)) {
                    return row.substring(prefix.length());
                }
            }
            return "";
        }
    }

    @Test
    @DisplayName("问华东：根范围从问题里认出来，一路定位到火锅")
    void questionAboutEastChinaFindsHotpot() {
        List<AgentEvent> events = new ArrayList<>();

        AttributionRun run = service.run("为什么华东 GMV 跌了", events::add);

        assertThat(run.rootScope().filters()).containsEntry(Dimension.REGION, "华东");
        assertThat(run.result().path()).isNotEmpty();
        AttributionNode first = run.result().path().get(0);
        assertThat(first.dimension()).isEqualTo(Dimension.CATEGORY);
        assertThat(first.value()).isEqualTo("火锅");
        assertThat(first.contribution()).isLessThanOrEqualTo(new java.math.BigDecimal("-0.6"));

        assertThat(events).isNotEmpty();
        assertThat(events.get(0).type()).isEqualTo("baseline");
        assertThat(events.get(events.size() - 2).type()).isEqualTo("verdict");
        assertThat(events.get(events.size() - 1).type()).isEqualTo("answer");
        assertThat(events.stream().filter(e -> e.type().equals("probe")).count()).isPositive();
    }

    @Test
    @DisplayName("留痕：run 有模式和结局，节点带证据，证据指回的 SQL 指纹一致")
    void persistsRunStepsAndNodes() {
        AttributionRun run = service.run("为什么华东 GMV 跌了", event -> { });
        UUID runId = run.runId();

        Map<String, Object> agentRun = jdbc.queryForMap(
                "select status, mode, attribution_outcome from agent_run where id = ?", runId);
        assertThat(agentRun.get("mode")).isEqualTo("ATTRIBUTION");
        assertThat(agentRun.get("attribution_outcome")).isEqualTo(run.result().outcome().name());
        assertThat(agentRun.get("status")).isIn("SUCCEEDED", "FAILED");

        List<Map<String, Object>> nodes = jdbc.queryForList(
                "select depth, verdict, target_value, evidence_step_id, query_hash, row_count "
                        + "from attribution_node where run_id = ? order by depth, id", runId);
        assertThat(nodes).isNotEmpty();
        assertThat(nodes.get(0).get("verdict")).isEqualTo("ROOT");
        assertThat(nodes).anySatisfy(n -> assertThat(n.get("verdict")).isEqualTo("DOMINANT"));
        assertThat(nodes).anySatisfy(n -> assertThat(n.get("verdict")).isEqualTo("PROBED"));
        assertThat(nodes).allSatisfy(n -> {
            assertThat(n.get("evidence_step_id")).isNotNull();
            assertThat(n.get("query_hash")).isNotNull();
            assertThat(n.get("row_count")).isNotNull();
        });

        // 证据链：节点 → 步骤 → 步骤里记录的 SQL，指纹必须对得上
        for (Map<String, Object> node : nodes) {
            Long stepId = ((Number) node.get("evidence_step_id")).longValue();
            String recorded = jdbc.queryForObject(
                    "select input->>'sql' from agent_step where id = ?", String.class, stepId);
            assertThat(recorded).isNotNull();
            assertThat(Evidence.hash(recorded)).isEqualTo(node.get("query_hash"));
        }
    }

    @Test
    @DisplayName("问题没提范围：根就是全平台，变化不显著时如实说，不硬给主因")
    void questionWithoutScopeFallsBackToPlatformRoot() {
        AttributionRun run = service.run("为什么 GMV 跌了", event -> { });

        assertThat(run.rootScope().filters()).isEmpty();
        assertThat(run.result().outcome()).isEqualTo(AttributionOutcome.NO_SIGNIFICANT_CHANGE);
        assertThat(run.result().path()).isEmpty();

        Integer rootNodes = jdbc.queryForObject(
                "select count(*) from attribution_node where run_id = ? and verdict = 'ROOT'",
                Integer.class, run.runId());
        assertThat(rootNodes).isEqualTo(1);
    }

    @Test
    @DisplayName("问题里有歧义：拒绝猜，但这次提问必须留痕")
    void ambiguousQuestionIsRejectedAndRecorded() {
        String question = "华东和华南为什么跌了";

        assertThatThrownBy(() -> service.run(question, event -> { }))
                .isInstanceOf(AmbiguousScopeException.class)
                .hasMessageContaining("华东");

        // 解析失败也要有 run：不然"用户到底问过多少次模糊问题"永远统计不出来
        Map<String, Object> row = jdbc.queryForMap(
                "select status, mode, attribution_outcome, error from agent_run where question = ?", question);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("mode")).isEqualTo("ATTRIBUTION");
        assertThat(row.get("attribution_outcome")).isEqualTo("FAILED");
        assertThat(String.valueOf(row.get("error"))).contains("无法确定归因范围");
    }

    @Test
    @DisplayName("问的不是 GMV：拒绝归因，并留下这次提问")
    void unsupportedMetricIsRejectedAndRecorded() {
        String question = "为什么退款率跌了";

        assertThatThrownBy(() -> service.run(question, event -> { }))
                .isInstanceOf(UnsupportedMetricException.class)
                .hasMessageContaining("退款率");

        Map<String, Object> row = jdbc.queryForMap(
                "select status, mode, attribution_outcome, error from agent_run where question = ?", question);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("mode")).isEqualTo("ATTRIBUTION");
        assertThat(row.get("attribution_outcome")).isEqualTo("FAILED");
        assertThat(String.valueOf(row.get("error"))).contains("只支持 GMV");
    }

    @Test
    @DisplayName("根范围从问题里带出来：同时提到区域和品类就都进根范围")
    void questionMentioningTwoDimensionsScopesBoth() {
        AttributionRun run = service.run("华东的火锅为什么跌了", event -> { });

        assertThat(run.rootScope().filters())
                .containsEntry(Dimension.REGION, "华东")
                .containsEntry(Dimension.CATEGORY, "火锅");
        // 两个维度都钉死了，第一层只能拆门店
        assertThat(run.result().path()).allSatisfy(node ->
                assertThat(node.dimension()).isEqualTo(Dimension.SHOP));
    }

    @Test
    @DisplayName("模型可用时由模型提议维度，不降级")
    void modelProposerIsUsedWhenAvailable() {
        AttributionRun run = service.run("为什么华东 GMV 跌了", event -> { });

        assertThat(run.degraded()).isFalse();
        assertThat(llm.proposerCalled.get()).isTrue();
        assertThat(run.path().get(0).value()).isEqualTo("火锅");
    }

    @Test
    @DisplayName("模型挂了：本次运行降级到规则版，结论不受影响")
    void modelFailureDegradesToRules() {
        llm.proposerFails.set(true);
        try {
            AttributionRun run = service.run("为什么华东 GMV 跌了", event -> { });

            assertThat(run.degraded()).isTrue();
            assertThat(run.path().get(0).value()).isEqualTo("火锅");
        } finally {
            llm.proposerFails.set(false);
        }
    }

    @Test
    @DisplayName("编造的事实被降级：88% 不在证据里，不能留在 facts")
    void fabricatedFactIsDemoted() {
        AttributionRun run = service.run("为什么华东 GMV 跌了", event -> { });

        assertThat(run.findings().facts())
                .anySatisfy(fact -> assertThat(fact).contains("火锅"))
                .allSatisfy(fact -> assertThat(fact).doesNotContain("88%"));
        assertThat(run.findings().uncertainty())
                .anySatisfy(text -> assertThat(text).contains("无证据支撑"));
        assertThat(run.findings().hypothesis()).isNotEmpty();
    }

    @Test
    @DisplayName("预算耗尽：如实返回 BUDGET_EXHAUSTED，不崩，也不让模型对着空证据编")
    void budgetExhaustionIsReported() {
        props.setQueryBudget(1);
        try {
            AttributionRun run = service.run("为什么华东 GMV 跌了", event -> { });

            assertThat(run.result().outcome()).isEqualTo(AttributionOutcome.BUDGET_EXHAUSTED);
            assertThat(run.findings().hypothesis()).isEmpty();
            assertThat(run.findings().uncertainty())
                    .anySatisfy(text -> assertThat(text).contains("没有产出可用的拆分证据"));
        } finally {
            props.setQueryBudget(16);
        }
    }

    @Test
    @DisplayName("数据不完整：服务层一次模型调用都不发，结论明确说不能确定主因")
    void dataIncompleteSkipsModelEntirely() throws Exception {
        StubSqlExecutor stub = new StubSqlExecutor()
                .respond(List.of(rootRow("850.00", "1000.00")))
                .respond(List.of(groupRow("火锅", "500.00", "600.00"),
                        groupRow("电影", "350.00", "380.00")));
        AttributionService stubService = buildService(stub);

        int before = llm.findingsCalls.get();
        AttributionRun run = stubService.run("为什么 GMV 跌了", event -> { });

        assertThat(run.result().outcome()).isEqualTo(AttributionOutcome.DATA_INCOMPLETE);
        assertThat(llm.findingsCalls.get()).isEqualTo(before);
        assertThat(run.findings().hypothesis()).isEmpty();
        assertThat(run.findings().facts()).anySatisfy(text -> assertThat(text).contains("差额"));
        assertThat(run.findings().uncertainty())
                .anySatisfy(text -> assertThat(text).contains("无法可靠确定主要变化来源"));
    }

    @Test
    @DisplayName("token 预算耗尽：结论降级为计算结果，归因本身仍然给出结论")
    void tokenBudgetExhaustionDegradesInsteadOfFailing() {
        props.setTokenBudget(1);
        try {
            AttributionRun run = service.run("为什么华东 GMV 跌了", event -> { });

            // 第一次调用已经发出（成本事前不可知），所以提议器这一层不一定降级；
            // 但报告器一定拿不到模型，结论必须退回计算结果，而不是报错或编原因
            assertThat(run.result().outcome()).isEqualTo(AttributionOutcome.MULTI_FACTOR);
            assertThat(run.result().path()).isNotEmpty();
            assertThat(run.findings().hypothesis()).isEmpty();
            assertThat(run.findings().uncertainty())
                    .anySatisfy(text -> assertThat(text).contains("模型不可用"));
        } finally {
            props.setTokenBudget(20000);
        }
    }

    @Test
    @DisplayName("P3 跑挂时写下 FAILED 结局，同时保留原始异常")
    void failedRunRecordsOutcomeAndKeepsOriginalError() throws Exception {
        // 没有预置响应：第一条查询就抛，异常一路穿出状态机
        AttributionService broken = buildService(new StubSqlExecutor());
        String question = "为什么 GMV 跌了（P3 故障注入）";

        assertThatThrownBy(() -> broken.run(question, event -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有预置响应");

        Map<String, Object> row = jdbc.queryForMap(
                "select status, mode, attribution_outcome, error from agent_run where question = ?", question);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("mode")).isEqualTo("ATTRIBUTION");
        assertThat(row.get("attribution_outcome")).isEqualTo("FAILED");
        assertThat(String.valueOf(row.get("error"))).contains("没有预置响应");
    }

    /** 按调用顺序返回预置行：根口径一条，分组一条。 */
    static final class StubSqlExecutor implements SqlExecutor {

        private final java.util.Deque<List<Map<String, Object>>> responses = new java.util.ArrayDeque<>();

        StubSqlExecutor respond(List<Map<String, Object>> rows) {
            responses.addLast(rows);
            return this;
        }

        @Override
        public List<Map<String, Object>> query(String sql, List<Object> bound, int timeoutSeconds, int maxRows) {
            if (responses.isEmpty()) {
                throw new IllegalStateException("没有预置响应: " + sql);
            }
            return responses.removeFirst();
        }
    }

    private static Map<String, Object> rootRow(String cur, String base) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("cur", new java.math.BigDecimal(cur));
        row.put("base", new java.math.BigDecimal(base));
        return row;
    }

    private static Map<String, Object> groupRow(String label, String cur, String base) {
        Map<String, Object> row = rootRow(cur, base);
        row.put("label", label);
        return row;
    }

    /** 整段脚本在同一个连接里执行：setseed 是会话级的，换连接数据就不确定了。 */
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
