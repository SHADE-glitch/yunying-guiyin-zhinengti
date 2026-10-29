package com.insight.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvalComparatorTest {

    private final EvalComparator comparator = new EvalComparator();
    private final EvalGatePolicy policy = EvalGatePolicy.defaults();

    @Test
    @DisplayName("同结果同成本：门禁通过，用例全部 SAME_PASS")
    void identicalBatchesPass() {
        EvalBatch baseline = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        EvalBatch candidate = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        List<EvalCaseResult> results = List.of(
                attr(1, true, 100), lookup(2, true, 100), guard(3, true), session(4, true));

        EvalComparison comparison = comparator.compareAgent(baseline, results, candidate, results, policy);

        assertThat(comparison.gate().passed()).isTrue();
        assertThat(comparison.gate().violations()).isEmpty();
        assertThat(comparison.cases()).allMatch(diff -> "SAME_PASS".equals(diff.status()));
    }

    @Test
    @DisplayName("结局准确率下降：门禁点名 ATTRIBUTION_OUTCOME，diff 标 REGRESSED")
    void outcomeDropFailsGate() {
        EvalBatch baseline = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        EvalBatch candidate = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        List<EvalCaseResult> base = List.of(attr(1, true, 100), lookup(2, true, 100));
        List<EvalCaseResult> cand = List.of(attr(1, false, 100), lookup(2, true, 100));

        EvalComparison comparison = comparator.compareAgent(baseline, base, candidate, cand, policy);

        assertThat(comparison.gate().passed()).isFalse();
        assertThat(comparison.gate().violations())
                .anyMatch(v -> v.startsWith("ATTRIBUTION_OUTCOME_REGRESSION"));
        assertThat(comparison.cases())
                .anyMatch(diff -> diff.caseId() == 1 && "REGRESSED".equals(diff.status()));
    }

    @Test
    @DisplayName("守卫退化即使总分只掉一条，也按安全失败处理")
    void guardRegressionFailsAsSafety() {
        EvalBatch baseline = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        EvalBatch candidate = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        List<EvalCaseResult> base = List.of(
                attr(1, true, 100), lookup(2, true, 100), guard(3, true), session(4, true));
        List<EvalCaseResult> cand = List.of(
                attr(1, true, 100), lookup(2, true, 100), guard(3, false), session(4, true));

        EvalComparison comparison = comparator.compareAgent(baseline, base, candidate, cand, policy);

        assertThat(comparison.gate().passed()).isFalse();
        assertThat(comparison.gate().violations())
                .anyMatch(v -> v.startsWith("GUARD_REGRESSION"))
                .anyMatch(v -> v.startsWith("SAFETY_CASE_REGRESSION"));
    }

    @Test
    @DisplayName("数据集版本不同：直接判失败，分数不可比")
    void datasetMismatchFailsGate() {
        EvalBatch baseline = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        EvalBatch candidate = batch("AGENT", "SUCCEEDED", "dataset-b", Map.of());
        List<EvalCaseResult> results = List.of(attr(1, true, 100));

        EvalComparison comparison = comparator.compareAgent(baseline, results, candidate, results, policy);

        assertThat(comparison.gate().passed()).isFalse();
        assertThat(comparison.gate().violations()).anyMatch(v -> v.startsWith("DATASET_MISMATCH"));
    }

    @Test
    @DisplayName("token 平均成本涨超过 20%：判成本回归")
    void tokenRegressionFailsGate() {
        EvalBatch baseline = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        EvalBatch candidate = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        List<EvalCaseResult> base = List.of(attr(1, true, 100), lookup(2, true, 100));
        List<EvalCaseResult> cand = List.of(attr(1, true, 130), lookup(2, true, 130));

        EvalComparison comparison = comparator.compareAgent(baseline, base, candidate, cand, policy);

        assertThat(comparison.gate().passed()).isFalse();
        assertThat(comparison.gate().violations()).anyMatch(v -> v.startsWith("TOKEN_REGRESSION"));
    }

    @Test
    @DisplayName("非成功批次不能作为基线通过门禁")
    void unfinishedBatchFailsGate() {
        EvalBatch baseline = batch("AGENT", "RUNNING", "dataset-a", Map.of());
        EvalBatch candidate = batch("AGENT", "SUCCEEDED", "dataset-a", Map.of());
        List<EvalCaseResult> results = List.of(attr(1, true, 100));

        EvalComparison comparison = comparator.compareAgent(baseline, results, candidate, results, policy);

        assertThat(comparison.gate().violations())
                .anyMatch(v -> v.startsWith("BASELINE_NOT_SUCCEEDED"));
    }

    @Test
    @DisplayName("检索召回下降：按模式点名，不平均成一个总分")
    void retrievalRecallDropFailsGate() {
        EvalBatch baseline = batch("RETRIEVAL", "SUCCEEDED", "dataset-r", Map.of("topK", 5));
        EvalBatch candidate = batch("RETRIEVAL", "SUCCEEDED", "dataset-r", Map.of("topK", 5));
        List<EvalRetrievalResult> base = List.of(
                retrieval("HYBRID", "q", 1.0, 1.0), retrieval("KEYWORD", "q", 1.0, 1.0));
        List<EvalRetrievalResult> cand = List.of(
                retrieval("HYBRID", "q", 0.5, 0.5), retrieval("KEYWORD", "q", 1.0, 1.0));

        EvalComparison comparison = comparator.compareRetrieval(baseline, base, candidate, cand, policy);

        assertThat(comparison.gate().passed()).isFalse();
        assertThat(comparison.gate().violations()).anyMatch(v -> v.startsWith("RECALL_HYBRID_REGRESSION"));
        assertThat(comparison.retrievalCases())
                .anyMatch(diff -> diff.mode().equals("HYBRID") && "REGRESSED".equals(diff.status()));
    }

    @Test
    @DisplayName("topK 不同：recall@3 和 recall@5 不是同一把尺子")
    void retrievalTopKMismatchFailsGate() {
        EvalBatch baseline = batch("RETRIEVAL", "SUCCEEDED", "dataset-r", Map.of("topK", 5));
        EvalBatch candidate = batch("RETRIEVAL", "SUCCEEDED", "dataset-r", Map.of("topK", 10));
        List<EvalRetrievalResult> results = List.of(retrieval("HYBRID", "q", 1.0, 1.0));

        EvalComparison comparison = comparator.compareRetrieval(baseline, results, candidate, results, policy);

        assertThat(comparison.gate().violations()).anyMatch(v -> v.startsWith("TOPK_MISMATCH"));
    }

    private static EvalBatch batch(String kind, String status, String datasetVersion,
                                   Map<String, Object> config) {
        return new EvalBatch(UUID.randomUUID(), kind, "label", null, status, null,
                "git-sha", "chat-model", "embedding-model", Instant.parse("2026-10-01T00:00:00Z"),
                config, Map.of("proposerVariant", "default"), datasetVersion, 4,
                0, 0, Map.of(), null, Instant.now(), Instant.now());
    }

    private static EvalCaseResult attr(long id, boolean passed, int tokens) {
        return new EvalCaseResult(id, "q-" + id, "ATTRIBUTION", "MULTI_FACTOR",
                passed ? "MULTI_FACTOR" : "NO_SIGNIFICANT_CHANGE", passed,
                passed, passed, passed, true, 3, 2, tokens, false,
                passed ? null : "OUTCOME", UUID.randomUUID(), 100, Map.of());
    }

    private static EvalCaseResult lookup(long id, boolean passed, int tokens) {
        return new EvalCaseResult(id, "q-" + id, "LOOKUP", "SUCCEEDED",
                passed ? "SUCCEEDED" : "FAILED", passed, true, true, true, passed,
                2, 0, tokens, false, passed ? null : "ANSWER_MISS",
                UUID.randomUUID(), 100, Map.of());
    }

    private static EvalCaseResult guard(long id, boolean passed) {
        return new EvalCaseResult(id, "q-" + id, "GUARD", "OUT_OF_SCOPE",
                passed ? "OUT_OF_SCOPE" : "IN_SCOPE", passed, passed, true, true, true,
                0, 0, 0, false, passed ? null : "EXPECTED_ERROR", null, 1, Map.of());
    }

    private static EvalCaseResult session(long id, boolean passed) {
        return new EvalCaseResult(id, "q-" + id, "SESSION", "contains 华南",
                passed ? "华南" : "CLARIFY", passed, passed, true, true, true,
                0, 0, 0, false, passed ? null : "RESOLVED", null, 1, Map.of());
    }

    private static EvalRetrievalResult retrieval(String mode, String query, double recall, double rr) {
        return new EvalRetrievalResult(mode, query, List.of(1L), List.of(1L), recall, rr);
    }
}
