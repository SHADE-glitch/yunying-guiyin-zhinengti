package com.insight.agent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 批次对比与回归判定。
 *
 * <p>对比不是"看两个通过率"，而是分三层看：
 * 质量（结局/路径/叶子/查数）、安全（guard/session 任何退化都失败）、
 * 成本（token 与降级次数）。数据集版本不同直接失败——不同题目的分数不可比。
 */
@Component
public class EvalComparator {

    public EvalComparison compareAgent(EvalBatch baseline, List<EvalCaseResult> baseResults,
                                       EvalBatch candidate, List<EvalCaseResult> candResults,
                                       EvalGatePolicy policy) {
        requireKind(baseline, candidate, EvalBatch.KIND_AGENT);
        AgentEvalSummary b = AgentEvalSummary.from(baseResults);
        AgentEvalSummary c = AgentEvalSummary.from(candResults);

        List<EvalComparison.MetricDelta> metrics = List.of(
                metric("overall.passRate", b.overall().passRate(), c.overall().passRate()),
                metric("attribution.passRate", b.attribution().passRate(), c.attribution().passRate()),
                metric("attribution.outcome", b.attribution().outcomeAccuracy(), c.attribution().outcomeAccuracy()),
                metric("attribution.path", b.attribution().pathAccuracy(), c.attribution().pathAccuracy()),
                metric("attribution.leaf", b.attribution().leafAccuracy(), c.attribution().leafAccuracy()),
                metric("lookup.passRate", b.lookup().passRate(), c.lookup().passRate()),
                metric("guard.passRate", b.guard().passRate(), c.guard().passRate()),
                metric("session.passRate", b.session().passRate(), c.session().passRate()),
                metric("efficiency.avgTokens", b.efficiency().avgTokens(), c.efficiency().avgTokens()),
                metric("efficiency.avgProbes", b.efficiency().avgProbes(), c.efficiency().avgProbes()),
                metric("degradedRuns", b.degradedRuns(), c.degradedRuns()));

        List<EvalComparison.CaseDiff> diffs = agentDiffs(baseResults, candResults);
        List<String> violations = new ArrayList<>();
        checkBatch(baseline, candidate, violations);
        drop("OVERALL", b.overall().passRate(), c.overall().passRate(),
                policy.maxPassRateDrop(), violations);
        drop("ATTRIBUTION_OUTCOME", b.attribution().outcomeAccuracy(), c.attribution().outcomeAccuracy(),
                policy.maxOutcomeDrop(), violations);
        drop("ATTRIBUTION_PATH", b.attribution().pathAccuracy(), c.attribution().pathAccuracy(),
                policy.maxPathDrop(), violations);
        drop("ATTRIBUTION_LEAF", b.attribution().leafAccuracy(), c.attribution().leafAccuracy(),
                policy.maxLeafDrop(), violations);
        drop("LOOKUP", b.lookup().passRate(), c.lookup().passRate(),
                policy.maxPassRateDrop(), violations);
        drop("GUARD", b.guard().passRate(), c.guard().passRate(),
                policy.maxSafetyDrop(), violations);
        drop("SESSION", b.session().passRate(), c.session().passRate(),
                policy.maxSafetyDrop(), violations);

        for (EvalComparison.CaseDiff diff : diffs) {
            if ("REGRESSED".equals(diff.status())
                    && ("GUARD".equals(diff.kind()) || "SESSION".equals(diff.kind()))) {
                violations.add("SAFETY_CASE_REGRESSION: [" + diff.kind() + "] " + diff.question());
            }
        }
        if (b.efficiency().avgTokens() > 0) {
            double increase = (c.efficiency().avgTokens() - b.efficiency().avgTokens())
                    / b.efficiency().avgTokens();
            if (increase > policy.maxTokenIncreaseRatio() + 1e-9) {
                violations.add(String.format("TOKEN_REGRESSION: avgTokens +%.1f%% > %.0f%%",
                        increase * 100, policy.maxTokenIncreaseRatio() * 100));
            }
        }
        if (c.degradedRuns() > b.degradedRuns() + policy.maxDegradedIncrease()) {
            violations.add("DEGRADED_REGRESSION: " + b.degradedRuns() + " -> " + c.degradedRuns());
        }

        return new EvalComparison(EvalBatch.KIND_AGENT, baseline, candidate, metrics, diffs,
                List.of(), gate(violations, policy));
    }

    public EvalComparison compareRetrieval(EvalBatch baseline, List<EvalRetrievalResult> baseResults,
                                           EvalBatch candidate, List<EvalRetrievalResult> candResults,
                                           EvalGatePolicy policy) {
        requireKind(baseline, candidate, EvalBatch.KIND_RETRIEVAL);
        RetrievalEvalSummary b = RetrievalEvalSummary.from(baseResults, intConfig(baseline, "topK", 0));
        RetrievalEvalSummary c = RetrievalEvalSummary.from(candResults, intConfig(candidate, "topK", 0));

        Map<String, RetrievalEvalSummary.ModeSummary> baseModes = byMode(b);
        Map<String, RetrievalEvalSummary.ModeSummary> candModes = byMode(c);
        List<EvalComparison.MetricDelta> metrics = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        checkBatch(baseline, candidate, violations);
        int baseTopK = intConfig(baseline, "topK", 0);
        int candTopK = intConfig(candidate, "topK", 0);
        if (baseTopK != candTopK) {
            // recall@3 和 recall@5 不是同一把尺子，数字碰巧接近也不能比
            violations.add("TOPK_MISMATCH: " + baseTopK + " vs " + candTopK);
        }

        for (Map.Entry<String, RetrievalEvalSummary.ModeSummary> entry : baseModes.entrySet()) {
            String mode = entry.getKey();
            RetrievalEvalSummary.ModeSummary bm = entry.getValue();
            RetrievalEvalSummary.ModeSummary cm = candModes.get(mode);
            if (cm == null) {
                violations.add("MODE_MISSING: " + mode);
                continue;
            }
            metrics.add(metric("recall[" + mode + "]", bm.recallAtK(), cm.recallAtK()));
            metrics.add(metric("mrr[" + mode + "]", bm.mrr(), cm.mrr()));
            drop("RECALL_" + mode, bm.recallAtK(), cm.recallAtK(), policy.maxRecallDrop(), violations);
            drop("MRR_" + mode, bm.mrr(), cm.mrr(), policy.maxMrrDrop(), violations);
        }
        // 候选新增的模式没有基线可比，不构成退化；也不塞一个 baseline=0 的假指标。

        return new EvalComparison(EvalBatch.KIND_RETRIEVAL, baseline, candidate, metrics, List.of(),
                retrievalDiffs(baseResults, candResults), gate(violations, policy));
    }

    private static Map<String, RetrievalEvalSummary.ModeSummary> byMode(RetrievalEvalSummary summary) {
        Map<String, RetrievalEvalSummary.ModeSummary> out = new LinkedHashMap<>();
        for (RetrievalEvalSummary.ModeSummary mode : summary.modes()) {
            out.put(mode.mode(), mode);
        }
        return out;
    }

    private static List<EvalComparison.CaseDiff> agentDiffs(List<EvalCaseResult> base,
                                                            List<EvalCaseResult> cand) {
        Map<Long, EvalCaseResult> byId = new LinkedHashMap<>();
        for (EvalCaseResult result : cand) {
            byId.put(result.caseId(), result);
        }
        List<EvalComparison.CaseDiff> diffs = new ArrayList<>();
        for (EvalCaseResult b : base) {
            EvalCaseResult c = byId.remove(b.caseId());
            if (c == null) {
                diffs.add(diff(b, null, "ONLY_BASELINE"));
            } else {
                String status;
                if (b.passed() && !c.passed()) {
                    status = "REGRESSED";
                } else if (!b.passed() && c.passed()) {
                    status = "IMPROVED";
                } else {
                    status = b.passed() ? "SAME_PASS" : "SAME_FAIL";
                }
                diffs.add(diff(b, c, status));
            }
        }
        byId.values().forEach(c -> diffs.add(diff(null, c, "ONLY_CANDIDATE")));
        return diffs;
    }

    private static EvalComparison.CaseDiff diff(EvalCaseResult b, EvalCaseResult c, String status) {
        EvalCaseResult edge = b != null ? b : c;
        String failure = c != null && c.failureKind() != null ? c.failureKind()
                : b != null ? b.failureKind() : null;
        return new EvalComparison.CaseDiff(
                edge.caseId(), edge.question(), edge.kind(), status, edge.expected(),
                b == null ? null : b.actual(),
                c == null ? null : c.actual(),
                failure,
                b == null ? null : b.runId(),
                c == null ? null : c.runId());
    }

    private static List<EvalComparison.RetrievalDiff> retrievalDiffs(
            List<EvalRetrievalResult> base, List<EvalRetrievalResult> cand) {
        Map<String, EvalRetrievalResult> byKey = new LinkedHashMap<>();
        for (EvalRetrievalResult result : cand) {
            byKey.put(key(result), result);
        }
        List<EvalComparison.RetrievalDiff> diffs = new ArrayList<>();
        for (EvalRetrievalResult b : base) {
            EvalRetrievalResult c = byKey.remove(key(b));
            if (c == null) {
                diffs.add(new EvalComparison.RetrievalDiff(b.mode(), b.query(), "ONLY_BASELINE",
                        b.recall(), 0, b.reciprocalRank(), 0));
            } else {
                String status = c.recall() > b.recall() + 1e-9 ? "IMPROVED"
                        : c.recall() < b.recall() - 1e-9 ? "REGRESSED" : "SAME";
                diffs.add(new EvalComparison.RetrievalDiff(b.mode(), b.query(), status,
                        b.recall(), c.recall(), b.reciprocalRank(), c.reciprocalRank()));
            }
        }
        byKey.values().forEach(c -> diffs.add(new EvalComparison.RetrievalDiff(
                c.mode(), c.query(), "ONLY_CANDIDATE", 0, c.recall(), 0, c.reciprocalRank())));
        return diffs;
    }

    private static String key(EvalRetrievalResult result) {
        return result.mode() + "\u0001" + result.query();
    }

    private static void checkBatch(EvalBatch baseline, EvalBatch candidate, List<String> violations) {
        if (!Objects.equals(baseline.datasetVersion(), candidate.datasetVersion())) {
            violations.add("DATASET_MISMATCH: " + shortSha(baseline.datasetVersion())
                    + " vs " + shortSha(candidate.datasetVersion()));
        }
        if (!"SUCCEEDED".equals(baseline.status())) {
            violations.add("BASELINE_NOT_SUCCEEDED: " + baseline.status());
        }
        if (!"SUCCEEDED".equals(candidate.status())) {
            violations.add("CANDIDATE_NOT_SUCCEEDED: " + candidate.status());
        }
    }

    private static void drop(String name, double baseline, double candidate, double allowed,
                             List<String> violations) {
        if (baseline - candidate > allowed + 1e-9) {
            violations.add(String.format("%s_REGRESSION: %.4f -> %.4f (drop %.4f > %.4f)",
                    name, baseline, candidate, baseline - candidate, allowed));
        }
    }

    private static EvalComparison.MetricDelta metric(String name, double baseline, double candidate) {
        return new EvalComparison.MetricDelta(name, round(baseline), round(candidate),
                round(candidate - baseline));
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private static EvalComparison.Gate gate(List<String> violations, EvalGatePolicy policy) {
        return new EvalComparison.Gate(violations.isEmpty(), List.copyOf(violations), policy);
    }

    private static void requireKind(EvalBatch baseline, EvalBatch candidate, String kind) {
        if (!kind.equals(baseline.kind()) || !kind.equals(candidate.kind())) {
            throw new IllegalArgumentException("只能比较同类型批次: "
                    + baseline.kind() + " vs " + candidate.kind());
        }
    }

    private static int intConfig(EvalBatch batch, String key, int fallback) {
        Object value = batch.config().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String shortSha(String sha) {
        return sha == null ? "null" : sha.substring(0, Math.min(12, sha.length()));
    }
}
