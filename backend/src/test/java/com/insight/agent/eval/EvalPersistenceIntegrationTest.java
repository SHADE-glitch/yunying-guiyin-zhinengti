package com.insight.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
 * 评测批次必须能落库、能按数据集版本找回、运行时只读账号能写。
 * 这三件事任一不成立，"可回归"就只是 UI 上的一个按钮。
 */
@Testcontainers
class EvalPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("insight")
            .withUsername("insight")
            .withPassword("insight");

    static JdbcTemplate owner;
    static JdbcTemplate runtime;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        runtime = new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), "insight_ro", "insight_ro"));
    }

    @Test
    @DisplayName("批次、用例结果、汇总进度和数据集版本可完整往返")
    void batchRoundTrip() {
        EvalStore store = new EvalStore(owner, MAPPER);
        EvalMetadata metadata = new EvalMetadata("abc123", "chat-model", "embedding-model",
                Instant.parse("2026-10-01T00:00:00Z"),
                Map.of("threshold", 0.55, "maxDepth", 2),
                Map.of("proposerVariant", "default", "proposerSha", "sha-p"),
                "dataset-v1", 4);
        UUID id = store.create(new EvalStore.EvalBatchDraft(
                EvalBatch.KIND_AGENT, "baseline", "round-trip", null, metadata, 4));

        EvalBatch created = store.find(id).orElseThrow();
        assertThat(created.status()).isEqualTo("RUNNING");
        assertThat(created.config()).containsEntry("threshold", 0.55);
        assertThat(created.promptHashes()).containsEntry("proposerSha", "sha-p");
        assertThat(created.asOf()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));

        store.saveCaseResult(id, new EvalCaseResult(1, "华东为什么跌了", "ATTRIBUTION",
                "MULTI_FACTOR", "MULTI_FACTOR", true, true, true, true, true,
                4, 3, 2400, false, null, UUID.randomUUID(), 1200,
                Map.of("path", List.of("CATEGORY=火锅"), "message", "ok")));
        store.updateProgress(id, 1, 4);
        store.finish(id, "SUCCEEDED", Map.of("overall", Map.of("passRate", 1.0)), null);

        EvalBatch finished = store.find(id).orElseThrow();
        assertThat(finished.status()).isEqualTo("SUCCEEDED");
        assertThat(finished.progressDone()).isEqualTo(4);
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(finished.summary()).containsKey("overall");

        List<EvalCaseResult> cases = store.caseResults(id);
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).detail()).containsKey("path");
        assertThat(cases.get(0).runId()).isNotNull();

        assertThat(store.latestSucceeded(EvalBatch.KIND_AGENT, "dataset-v1"))
                .get().extracting(EvalBatch::id).isEqualTo(id);
        assertThat(store.latestSucceeded(EvalBatch.KIND_AGENT, "dataset-v2")).isEmpty();
    }

    @Test
    @DisplayName("检索批次的结果数组与指标可往返")
    void retrievalRoundTrip() {
        EvalStore store = new EvalStore(owner, MAPPER);
        EvalMetadata metadata = new EvalMetadata("abc123", "chat-model", "embedding-model",
                null, Map.of("topK", 5), Map.of(), "retrieval-v1", 2);
        UUID id = store.create(new EvalStore.EvalBatchDraft(
                EvalBatch.KIND_RETRIEVAL, "retrieval", null, null, metadata, 3));

        store.saveRetrievalResult(id, new EvalRetrievalResult(
                "HYBRID", "GMV 怎么算", List.of(1L, 2L), List.of(2L, 5L), 0.5, 1.0));
        store.finish(id, "SUCCEEDED", Map.of("cases", 1), null);

        List<EvalRetrievalResult> results = store.retrievalResults(id);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).expectedIds()).containsExactly(1L, 2L);
        assertThat(results.get(0).retrievedIds()).containsExactly(2L, 5L);
        assertThat(results.get(0).recall()).isEqualTo(0.5);
        assertThat(results.get(0).reciprocalRank()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("运行时只读账号可以写评测账本，但业务表仍只读")
    void runtimeRoleCanWriteEvalLedger() {
        EvalStore store = new EvalStore(runtime, MAPPER);
        EvalMetadata metadata = new EvalMetadata("abc123", "chat-model", "embedding-model",
                Instant.now(), Map.of(), Map.of(), "dataset-r", 1);
        UUID id = store.create(new EvalStore.EvalBatchDraft(
                EvalBatch.KIND_AGENT, "ro-batch", null, null, metadata, 1));
        store.saveCaseResult(id, new EvalCaseResult(1, "q", "LOOKUP", "SUCCEEDED", "SUCCEEDED",
                true, true, true, true, true, 2, 0, 100, false, null,
                UUID.randomUUID(), 10, Map.of()));
        store.finish(id, "SUCCEEDED", Map.of(), null);

        assertThat(store.find(id).orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThat(store.caseResults(id)).hasSize(1);

        UUID session = UUID.randomUUID();
        runtime.update("insert into session_state (id) values (?)", session);
        runtime.update("delete from session_state where id = ?", session);
    }

    @Test
    @DisplayName("中断的 RUNNING 批次在重启后被标记为 FAILED")
    void interruptedBatchIsFailed() {
        EvalStore store = new EvalStore(owner, MAPPER);
        UUID id = store.create(new EvalStore.EvalBatchDraft(EvalBatch.KIND_AGENT, "interrupted",
                null, null, new EvalMetadata("sha", "model", "emb", null, Map.of(), Map.of(),
                "dataset-i", 1), 1));

        assertThat(store.failInterrupted()).isGreaterThanOrEqualTo(1);

        assertThat(store.find(id).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(store.find(id).orElseThrow().error()).contains("interrupted");
    }

    @Test
    @DisplayName("数据集版本随标注内容变化，而不是手写标签")
    void datasetVersionFollowsContent() {
        EvalDatasetService datasets = new EvalDatasetService(owner, MAPPER);
        owner.update("""
                insert into eval_agent_case (question, kind, expected_outcome, expected_path)
                values (?, ?, ?, ?::jsonb)
                """, "version-test", "ATTRIBUTION", "MULTI_FACTOR", "[\"CATEGORY=火锅\"]");

        String version = datasets.agentDataset().version();
        assertThat(datasets.agentDataset().cases())
                .anyMatch(c -> "version-test".equals(c.question())
                        && c.expectedPath().equals(List.of("CATEGORY=火锅")));

        // 同一份内容重新导入会换自增 id，但数据集版本必须不变
        owner.update("delete from eval_agent_case");
        owner.update("""
                insert into eval_agent_case (question, kind, expected_outcome, expected_path)
                values (?, ?, ?, ?::jsonb)
                """, "version-test", "ATTRIBUTION", "MULTI_FACTOR", "[\"CATEGORY=火锅\"]");
        assertThat(datasets.agentDataset().version()).isEqualTo(version);

        owner.update("update eval_agent_case set expected_leaf = '火锅' where question = 'version-test'");
        assertThat(datasets.agentDataset().version()).isNotEqualTo(version);
    }

    @Test
    @DisplayName("检索数据集版本同样随标注变化")
    void retrievalDatasetVersionFollowsContent() {
        EvalDatasetService datasets = new EvalDatasetService(owner, MAPPER);
        owner.update("insert into eval_case (query, expected_doc_ids) values (?, ?::bigint[])",
                "retrieval-version", "{1,2}");

        assertThat(datasets.retrievalDataset().cases())
                .anyMatch(c -> "retrieval-version".equals(c.query())
                        && c.expectedDocIds().equals(List.of(1L, 2L)));

        String version = datasets.retrievalDataset().version();
        owner.update("delete from eval_case");
        owner.update("insert into eval_case (query, expected_doc_ids) values (?, ?::bigint[])",
                "retrieval-version", "{1,2}");
        assertThat(datasets.retrievalDataset().version()).isEqualTo(version);

        owner.update("insert into eval_case (query, expected_doc_ids) values (?, ?::bigint[])",
                "retrieval-version-2", "{3}");
        assertThat(datasets.retrievalDataset().version()).isNotEqualTo(version);
    }
}
