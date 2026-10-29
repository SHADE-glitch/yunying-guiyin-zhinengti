package com.insight.agent.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Agent 批次的汇总。Guard/Session 单独成组：安全行为退化不能被归因/查数的
 * 平均分掩盖——这也是回归门禁把两类单独卡住的原因。
 */
public record AgentEvalSummary(
        AttributionGroup attribution,
        Group lookup,
        Group guard,
        Group session,
        Group overall,
        Efficiency efficiency,
        int degradedRuns,
        int failedCases,
        Map<String, Integer> failureKinds) {

    public record Group(int cases, int passed, double passRate) {
    }

    public record AttributionGroup(
            int cases, int passed, double passRate,
            double outcomeAccuracy, double pathAccuracy, double leafAccuracy) {
    }

    public record Efficiency(
            double avgSteps, double avgProbes, double avgTokens,
            double avgAttributionTokens, double avgLookupTokens, long totalTokens) {
    }

    public static AgentEvalSummary from(List<EvalCaseResult> results) {
        List<EvalCaseResult> attribution = ofKind(results, "ATTRIBUTION");
        List<EvalCaseResult> lookup = ofKind(results, "LOOKUP");
        List<EvalCaseResult> guard = ofKind(results, "GUARD");
        List<EvalCaseResult> session = ofKind(results, "SESSION");

        int aPassed = countPassed(attribution);
        AttributionGroup aGroup = new AttributionGroup(
                attribution.size(), aPassed, rate(aPassed, attribution.size()),
                rate(count(attribution, EvalCaseResult::outcomeMatch), attribution.size()),
                rate(count(attribution, EvalCaseResult::pathMatch), attribution.size()),
                rate(count(attribution, EvalCaseResult::leafMatch), attribution.size()));

        Group lGroup = group(lookup);
        Group gGroup = group(guard);
        Group sGroup = group(session);
        Group overall = group(results);

        List<EvalCaseResult> measured = results.stream()
                .filter(r -> r.kind().equals("ATTRIBUTION") || r.kind().equals("LOOKUP"))
                .toList();
        Efficiency efficiency = new Efficiency(
                avg(measured, EvalCaseResult::steps),
                avg(attribution, EvalCaseResult::probes),
                avg(measured, EvalCaseResult::tokens),
                avg(attribution, EvalCaseResult::tokens),
                avg(lookup, EvalCaseResult::tokens),
                measured.stream().mapToLong(EvalCaseResult::tokens).sum());

        Map<String, Integer> failureKinds = new TreeMap<>();
        for (EvalCaseResult result : results) {
            if (result.failureKind() != null) {
                failureKinds.merge(result.failureKind(), 1, Integer::sum);
            }
        }

        return new AgentEvalSummary(
                aGroup, lGroup, gGroup, sGroup, overall, efficiency,
                (int) results.stream().filter(EvalCaseResult::degraded).count(),
                (int) results.stream().filter(r -> !r.passed()).count(),
                Map.copyOf(new LinkedHashMap<>(failureKinds)));
    }

    private static List<EvalCaseResult> ofKind(List<EvalCaseResult> results, String kind) {
        return results.stream().filter(r -> kind.equals(r.kind())).toList();
    }

    private static Group group(List<EvalCaseResult> results) {
        int passed = countPassed(results);
        return new Group(results.size(), passed, rate(passed, results.size()));
    }

    private static int countPassed(List<EvalCaseResult> results) {
        return (int) results.stream().filter(EvalCaseResult::passed).count();
    }

    private static int count(List<EvalCaseResult> results,
                             java.util.function.Predicate<EvalCaseResult> predicate) {
        return (int) results.stream().filter(predicate).count();
    }

    private static double avg(List<EvalCaseResult> results,
                              java.util.function.ToIntFunction<EvalCaseResult> field) {
        return results.isEmpty() ? 0 : round(results.stream().mapToInt(field).average().orElse(0));
    }

    private static double rate(int hit, int total) {
        return total == 0 ? 0 : round((double) hit / total);
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
