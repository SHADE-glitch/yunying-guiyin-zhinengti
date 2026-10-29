package com.insight.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 读取评测集并计算版本指纹。
 *
 * <p>版本号不是"日期"或"v2"这种手写标签，而是用例内容（题目、期望、setup）的 sha256。
 * 任何一条标注被改动，版本都会变；不同版本之间不比较门禁，只展示差异。
 */
@Component
public class EvalDatasetService {

    public record AgentCase(
            long id, String question, String kind, String expectedOutcome, String expectedLeaf,
            List<String> expectedPath, String expectedAnswerContains, List<String> expectedAnswerAny,
            String expectedError, String setupQuestion, List<String> expectedResolvedContains) {
    }

    public record RetrievalCase(long id, String query, List<Long> expectedDocIds) {
    }

    public record Dataset<T>(List<T> cases, String version) {

        public int size() {
            return cases.size();
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EvalDatasetService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Dataset<AgentCase> agentDataset() {
        List<AgentCase> cases = jdbc.query("""
                select id, question, kind, expected_outcome, expected_leaf, expected_path::text,
                       expected_answer_contains, expected_answer_any, expected_error,
                       setup_question, expected_resolved_contains
                  from eval_agent_case order by id
                """, (rs, i) -> {
            List<String> any = textArray(rs, "expected_answer_any");
            if (any.isEmpty()) {
                String single = rs.getString("expected_answer_contains");
                if (single != null && !single.isBlank()) {
                    any = List.of(single);
                }
            }
            return new AgentCase(
                    rs.getLong("id"), rs.getString("question"), rs.getString("kind"),
                    rs.getString("expected_outcome"), rs.getString("expected_leaf"),
                    path(rs.getString("expected_path")),
                    rs.getString("expected_answer_contains"), any,
                    rs.getString("expected_error"), rs.getString("setup_question"),
                    textArray(rs, "expected_resolved_contains"));
        });
        return new Dataset<>(List.copyOf(cases), hash(cases));
    }

    public Dataset<RetrievalCase> retrievalDataset() {
        List<RetrievalCase> cases = jdbc.query(
                "select id, query, expected_doc_ids from eval_case order by id",
                (rs, i) -> new RetrievalCase(rs.getLong("id"), rs.getString("query"),
                        longArray(rs, "expected_doc_ids")));
        return new Dataset<>(List.copyOf(cases), hashRetrieval(cases));
    }

    /** 数据末端。评测窗口钉在这里，而不是 now()。 */
    public Instant dataAsOf() {
        Timestamp max = jdbc.queryForObject("select max(created_at) from voucher_order", Timestamp.class);
        if (max == null) {
            throw new IllegalStateException("voucher_order 没有数据，先运行 seed.sql");
        }
        return max.toInstant();
    }

    private List<String> path(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("expected_path 不是合法 JSON: " + json, e);
        }
    }

    private static String hash(List<AgentCase> cases) {
        StringBuilder text = new StringBuilder();
        for (AgentCase c : cases) {
            // 不含自增 id：同一份 SQL 重新导入会换 id，但数据集并没有变。
            text.append(c.question()).append('\u0001')
                    .append(c.kind()).append('\u0001')
                    .append(nullSafe(c.expectedOutcome())).append('\u0001')
                    .append(nullSafe(c.expectedLeaf())).append('\u0001')
                    .append(String.join("|", c.expectedPath())).append('\u0001')
                    .append(nullSafe(c.expectedAnswerContains())).append('\u0001')
                    .append(String.join("|", c.expectedAnswerAny())).append('\u0001')
                    .append(nullSafe(c.expectedError())).append('\u0001')
                    .append(nullSafe(c.setupQuestion())).append('\u0001')
                    .append(String.join("|", c.expectedResolvedContains()))
                    .append('\u0002');
        }
        return sha256(text.toString());
    }

    private static String hashRetrieval(List<RetrievalCase> cases) {
        StringBuilder text = new StringBuilder();
        for (RetrievalCase c : cases) {
            text.append(c.query()).append('\u0001')
                    .append(c.expectedDocIds().stream().map(String::valueOf)
                            .reduce((a, b) -> a + "," + b).orElse(""))
                    .append('\u0002');
        }
        return sha256(text.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算数据集版本失败", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (!(raw instanceof Object[] values)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value != null) {
                out.add(String.valueOf(value));
            }
        }
        return List.copyOf(out);
    }

    private static List<Long> longArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (!(raw instanceof Object[] values)) {
            return List.of();
        }
        List<Long> out = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value instanceof Number number) {
                out.add(number.longValue());
            }
        }
        return List.copyOf(out);
    }
}
