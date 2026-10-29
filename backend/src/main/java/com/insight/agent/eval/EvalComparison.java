package com.insight.agent.eval;

import java.util.List;
import java.util.UUID;

/** 两个批次的对比。门禁结果和用例级 diff 一起返回：失败时能直接看到是哪一条退化。 */
public record EvalComparison(
        String kind,
        EvalBatch baseline,
        EvalBatch candidate,
        List<MetricDelta> metrics,
        List<CaseDiff> cases,
        List<RetrievalDiff> retrievalCases,
        Gate gate) {

    public record MetricDelta(String name, double baseline, double candidate, double delta) {
    }

    public record CaseDiff(
            long caseId, String question, String kind, String status, String expected,
            String baselineActual, String candidateActual, String failureKind,
            UUID baselineRunId, UUID candidateRunId) {
    }

    public record RetrievalDiff(
            String mode, String query, String status,
            double baselineRecall, double candidateRecall,
            double baselineRr, double candidateRr) {
    }

    public record Gate(boolean passed, List<String> violations, EvalGatePolicy policy) {
    }
}
