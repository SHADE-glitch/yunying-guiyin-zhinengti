package com.insight.agent.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 展示层：运行留痕的只读查询。
 *
 * <p>定位是"展示"而不是 AI 能力：只 select {@code RunRecorder} / {@code AttributionNodeWriter}
 * 已经落库的留痕表，不新增写入、不参与任何判定。前端的历史与详情页只消费这两个端点。
 */
@RestController
public class RunsController {

    public record RunSummary(String id, String question, String mode, String status,
                             String attributionOutcome, int stepCount, int promptTokens,
                             int completionTokens, String createdAt, String finishedAt) {
    }

    public record StepRow(long id, int stepNo, String type, String toolName, JsonNode input,
                          JsonNode output, Integer latencyMs, int promptTokens,
                          int completionTokens, String createdAt) {
    }

    public record NodeRow(long id, Long parentId, int depth, String dimension, String targetValue,
                          String metricCode, BigDecimal currentValue, BigDecimal baselineValue,
                          BigDecimal delta, BigDecimal contribution, String verdict,
                          Long evidenceStepId, String queryHash, Integer rowCount) {
    }

    public record RunDetail(RunSummary run, String answer, String error,
                            List<StepRow> steps, List<NodeRow> nodes) {
    }

    /** 详情行：比列表多 answer / error 两个大文本字段。 */
    private record RunRow(String id, String question, String mode, String status, String outcome,
                          int stepCount, int promptTokens, int completionTokens,
                          String createdAt, String finishedAt, String answer, String error) {

        RunSummary summary() {
            return new RunSummary(id, question, mode, status, outcome, stepCount,
                    promptTokens, completionTokens, createdAt, finishedAt);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RunsController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/api/runs")
    public List<RunSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.query("""
                select id, question, mode, status, attribution_outcome, step_count,
                       prompt_tokens, completion_tokens, created_at, finished_at
                  from agent_run
                 order by created_at desc
                 limit ?
                """, (rs, i) -> new RunSummary(
                        rs.getString("id"), rs.getString("question"), rs.getString("mode"),
                        rs.getString("status"), rs.getString("attribution_outcome"),
                        rs.getInt("step_count"), rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"), iso(rs, "created_at"), iso(rs, "finished_at")),
                Math.min(limit, 200));
    }

    @GetMapping("/api/runs/stats")
    public Map<String, Object> stats() {
        // 成本/时延面板的数据源：全部来自留痕表的聚合，不新增任何埋点。
        // 列名用带引号的驼峰别名，和前端 RunStats 类型对齐；percentile 要 cast 成
        // numeric 才能用双参 round（PG 没有 round(double precision, integer)）。
        return jdbc.queryForMap("""
                select count(*)::int as runs,
                       count(*) filter (where status = 'SUCCEEDED')::int as succeeded,
                       count(*) filter (where status = 'FAILED')::int as failed,
                       count(*) filter (where mode = 'ATTRIBUTION')::int as "attributionRuns",
                       count(*) filter (where attribution_outcome in
                           ('BUDGET_EXHAUSTED', 'INVALID_PROPOSAL_LIMIT', 'DATA_INCOMPLETE'))::int as "guardedEnds",
                       coalesce(sum(prompt_tokens + completion_tokens), 0)::bigint as "totalTokens",
                       coalesce(round(avg(prompt_tokens + completion_tokens)), 0)::bigint as "avgTokens",
                       coalesce(round(percentile_cont(0.5) within group
                           (order by extract(epoch from finished_at - created_at))::numeric), 0) as "p50Seconds",
                       coalesce(round(percentile_cont(0.9) within group
                           (order by extract(epoch from finished_at - created_at))::numeric), 0) as "p90Seconds"
                  from agent_run
                """);
    }

    @GetMapping("/api/runs/{id}")
    public RunDetail detail(@PathVariable("id") UUID id) {
        List<RunRow> runs = jdbc.query("""
                select id, question, mode, status, attribution_outcome, step_count,
                       prompt_tokens, completion_tokens, created_at, finished_at, answer, error
                  from agent_run
                 where id = ?
                """, (rs, i) -> new RunRow(
                        rs.getString("id"), rs.getString("question"), rs.getString("mode"),
                        rs.getString("status"), rs.getString("attribution_outcome"),
                        rs.getInt("step_count"), rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"), iso(rs, "created_at"),
                        iso(rs, "finished_at"), rs.getString("answer"), rs.getString("error")), id);
        if (runs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found: " + id);
        }
        RunRow run = runs.get(0);

        List<StepRow> steps = jdbc.query("""
                select id, step_no, type, tool_name, input, output, latency_ms,
                       prompt_tokens, completion_tokens, created_at
                  from agent_step
                 where run_id = ?
                 order by step_no, id
                """, (rs, i) -> new StepRow(
                        rs.getLong("id"), rs.getInt("step_no"), rs.getString("type"),
                        rs.getString("tool_name"), json(rs, "input"), json(rs, "output"),
                        (Integer) rs.getObject("latency_ms"), rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"), iso(rs, "created_at")), id);

        List<NodeRow> nodes = jdbc.query("""
                select id, parent_id, depth, dimension, target_value, metric_code,
                       current_value, baseline_value, delta, contribution, verdict,
                       evidence_step_id, query_hash, row_count
                  from attribution_node
                 where run_id = ?
                 order by depth, id
                """, (rs, i) -> new NodeRow(
                        rs.getLong("id"), (Long) rs.getObject("parent_id"), rs.getInt("depth"),
                        rs.getString("dimension"), rs.getString("target_value"),
                        rs.getString("metric_code"), rs.getBigDecimal("current_value"),
                        rs.getBigDecimal("baseline_value"), rs.getBigDecimal("delta"),
                        rs.getBigDecimal("contribution"), rs.getString("verdict"),
                        (Long) rs.getObject("evidence_step_id"), rs.getString("query_hash"),
                        (Integer) rs.getObject("row_count")), id);

        return new RunDetail(run.summary(), run.answer(), run.error(), steps, nodes);
    }

    private JsonNode json(ResultSet rs, String column) {
        try {
            String raw = rs.getString(column);
            return raw == null ? null : mapper.readTree(raw);
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("留痕 jsonb 解析失败: " + column, e);
        }
    }

    private static String iso(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant().toString();
    }
}
