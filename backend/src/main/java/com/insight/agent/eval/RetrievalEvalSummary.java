package com.insight.agent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检索批次汇总：每个模式单独一组，不把三种模式平均成一个数。 */
public record RetrievalEvalSummary(int cases, int topK, List<ModeSummary> modes) {

    public record ModeSummary(String mode, int cases, double recallAtK, double mrr) {
    }

    public static RetrievalEvalSummary from(List<EvalRetrievalResult> results, int topK) {
        Map<String, List<EvalRetrievalResult>> byMode = new LinkedHashMap<>();
        for (EvalRetrievalResult result : results) {
            byMode.computeIfAbsent(result.mode(), key -> new ArrayList<>()).add(result);
        }
        List<ModeSummary> modes = new ArrayList<>();
        for (Map.Entry<String, List<EvalRetrievalResult>> entry : byMode.entrySet()) {
            List<EvalRetrievalResult> group = entry.getValue();
            modes.add(new ModeSummary(
                    entry.getKey(), group.size(),
                    round(group.stream().mapToDouble(EvalRetrievalResult::recall).average().orElse(0)),
                    round(group.stream().mapToDouble(EvalRetrievalResult::reciprocalRank).average().orElse(0))));
        }
        int cases = byMode.values().stream().mapToInt(List::size).max().orElse(0);
        return new RetrievalEvalSummary(cases, topK, List.copyOf(modes));
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
