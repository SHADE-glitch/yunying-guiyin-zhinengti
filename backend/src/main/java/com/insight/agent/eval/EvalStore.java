package com.insight.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 评测批次的存取。批次是评测的唯一事实来源：结果、进度、元数据都在这里，
 * files 只是导出视图。
 */
@Component
public class EvalStore {

    public record EvalBatchDraft(
            String kind, String label, String note, UUID baselineId,
            EvalMetadata metadata, int progressTotal) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EvalStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID create(EvalBatchDraft draft) {
        UUID id = UUID.randomUUID();
        EvalMetadata meta = draft.metadata();
        jdbc.update("""
                insert into eval_batch (id, kind, label, note, baseline_id, git_sha, model,
                                        embedding_model, as_of, config, prompt_hashes,
                                        dataset_version, dataset_size, progress_total)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                id, draft.kind(), draft.label(), draft.note(), draft.baselineId(),
                meta.gitSha(), meta.model(), meta.embeddingModel(),
                meta.asOf() == null ? null : Timestamp.from(meta.asOf()),
                json(meta.config()), json(meta.promptHashes()),
                meta.datasetVersion(), meta.datasetSize(), draft.progressTotal());
        return id;
    }

    public void updateProgress(UUID id, int done, int total) {
        jdbc.update("update eval_batch set progress_done = ?, progress_total = ? where id = ?",
                done, total, id);
    }

    public void finish(UUID id, String status, Map<String, Object> summary, String error) {
        if ("SUCCEEDED".equals(status)) {
            jdbc.update("""
                    update eval_batch
                       set status = ?, summary = ?::jsonb, error = null,
                           progress_done = progress_total, finished_at = now()
                     where id = ?
                    """, status, json(summary), id);
        } else {
            jdbc.update("""
                    update eval_batch
                       set status = ?, summary = ?::jsonb, error = ?, finished_at = now()
                     where id = ?
                    """, status, json(summary), truncate(error), id);
        }
    }

    /** 服务重启后，上一次没跑完的批次不能永远停在 RUNNING。 */
    public int failInterrupted() {
        return jdbc.update("""
                update eval_batch
                   set status = 'FAILED', error = 'interrupted by service restart', finished_at = now()
                 where status = 'RUNNING'
                """);
    }

    public void saveCaseResult(UUID batchId, EvalCaseResult result) {
        jdbc.update("""
                insert into eval_case_result (batch_id, case_id, question, kind, expected, actual,
                                              passed, outcome_match, path_match, leaf_match, answer_match,
                                              steps, probes, tokens, degraded, failure_kind,
                                              run_id, duration_ms, detail)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                batchId, result.caseId(), result.question(), result.kind(),
                result.expected(), result.actual(), result.passed(),
                result.outcomeMatch(), result.pathMatch(), result.leafMatch(), result.answerMatch(),
                result.steps(), result.probes(), result.tokens(), result.degraded(),
                result.failureKind(), result.runId(), result.durationMs(), json(result.detail()));
    }

    public void saveRetrievalResult(UUID batchId, EvalRetrievalResult result) {
        jdbc.update("""
                insert into eval_retrieval_result (batch_id, mode, query, expected_ids,
                                                   retrieved_ids, recall, reciprocal_rank)
                values (?, ?, ?, string_to_array(?, ',')::bigint[],
                        string_to_array(?, ',')::bigint[], ?, ?)
                """,
                batchId, result.mode(), result.query(),
                ids(result.expectedIds()), ids(result.retrievedIds()),
                result.recall(), result.reciprocalRank());
    }

    public List<EvalBatch> list(String kind, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        if (kind == null || kind.isBlank()) {
            return jdbc.query("select * from eval_batch order by started_at desc limit ?",
                    this::mapBatch, safeLimit);
        }
        return jdbc.query("select * from eval_batch where kind = ? order by started_at desc limit ?",
                this::mapBatch, kind, safeLimit);
    }

    public Optional<EvalBatch> find(UUID id) {
        return jdbc.query("select * from eval_batch where id = ?", this::mapBatch, id)
                .stream().findFirst();
    }

    public Optional<EvalBatch> latestSucceeded(String kind, String datasetVersion) {
        return jdbc.query("""
                select * from eval_batch
                 where kind = ? and status = 'SUCCEEDED'
                   and dataset_version is not distinct from ?
                 order by started_at desc limit 1
                """, this::mapBatch, kind, datasetVersion)
                .stream().findFirst();
    }

    public List<EvalCaseResult> caseResults(UUID batchId) {
        return jdbc.query("""
                select case_id, question, kind, expected, actual, passed, outcome_match,
                       path_match, leaf_match, answer_match, steps, probes, tokens,
                       degraded, failure_kind, run_id, duration_ms, detail
                  from eval_case_result where batch_id = ? order by case_id, id
                """, (rs, i) -> new EvalCaseResult(
                rs.getLong("case_id"), rs.getString("question"), rs.getString("kind"),
                rs.getString("expected"), rs.getString("actual"), rs.getBoolean("passed"),
                rs.getBoolean("outcome_match"), rs.getBoolean("path_match"),
                rs.getBoolean("leaf_match"), rs.getBoolean("answer_match"),
                rs.getInt("steps"), rs.getInt("probes"), rs.getInt("tokens"),
                rs.getBoolean("degraded"), rs.getString("failure_kind"),
                (UUID) rs.getObject("run_id"), rs.getInt("duration_ms"),
                map(rs.getString("detail"))), batchId);
    }

    public List<EvalRetrievalResult> retrievalResults(UUID batchId) {
        return jdbc.query("""
                select mode, query, expected_ids, retrieved_ids, recall, reciprocal_rank
                  from eval_retrieval_result where batch_id = ? order by mode, id
                """, (rs, i) -> new EvalRetrievalResult(
                rs.getString("mode"), rs.getString("query"),
                longArray(rs, "expected_ids"), longArray(rs, "retrieved_ids"),
                rs.getDouble("recall"), rs.getDouble("reciprocal_rank")), batchId);
    }

    private EvalBatch mapBatch(ResultSet rs, int rowNum) throws SQLException {
        return new EvalBatch(
                (UUID) rs.getObject("id"),
                rs.getString("kind"),
                rs.getString("label"),
                rs.getString("note"),
                rs.getString("status"),
                (UUID) rs.getObject("baseline_id"),
                rs.getString("git_sha"),
                rs.getString("model"),
                rs.getString("embedding_model"),
                instant(rs.getTimestamp("as_of")),
                map(rs.getString("config")),
                stringMap(rs.getString("prompt_hashes")),
                rs.getString("dataset_version"),
                rs.getInt("dataset_size"),
                rs.getInt("progress_done"),
                rs.getInt("progress_total"),
                map(rs.getString("summary")),
                rs.getString("error"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("评测元数据序列化失败", e);
        }
    }

    private Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, String> stringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String ids(List<Long> ids) {
        return ids == null ? "" : ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
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

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
