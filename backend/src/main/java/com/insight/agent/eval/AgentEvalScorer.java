package com.insight.agent.eval;

import java.util.List;

/**
 * 任务级评测的打分。
 *
 * <p>只做"实际发生了什么 vs 应该发生什么"的比对，不做 LLM-as-judge：那要再花一次模型调用，
 * 而且同一份输出两次评分可能不一样。这里比的是结构化的东西——结局、路径、叶子。
 *
 * <p>路径按**前缀**比，不是全等：先拆品类还是先拆区域都是合法选择，写死整条路会把
 * "另一条正确的路"判成错。路径写成 {@code "CATEGORY=火锅"}，和用例表里的写法一致。
 */
public final class AgentEvalScorer {

    private AgentEvalScorer() {
    }

    public enum Kind { ATTRIBUTION, LOOKUP }

    /** 一条用例的判定结果。 */
    public record Scored(String question, Kind kind, String expected, String actual,
                         boolean outcomeMatch, boolean pathMatch, boolean leafMatch,
                         boolean answerMatch, int steps, int probes, int tokens, boolean degraded) {

        public boolean passed() {
            return outcomeMatch && pathMatch && leafMatch && answerMatch;
        }
    }

    public record Group(int cases, int passed, double passRate) {
    }

    public record AttributionGroup(int cases, int passed, double passRate,
                                   double outcomeAccuracy, double pathAccuracy, double leafAccuracy) {
    }

    public record Efficiency(double avgSteps, double avgProbes, double avgTokens) {
    }

    public record Report(AttributionGroup attribution, Group lookup, Efficiency efficiency,
                         int degradedRuns, List<Scored> failures) {
    }

    public static boolean outcomeMatches(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    /**
     * 答案里有没有该出现的词。
     *
     * <p>关键词不是"正确答案"，是"读没读到那份口径"的最低证据：问退款率的分母，
     * 答案里得出现"退款订单数"，否则就是绕过去了。留空表示这条用例不校验答案内容。
     */
    public static boolean answerContains(String expected, String answer) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return answer != null && answer.contains(expected);
    }

    /** 期望路径按前缀比：允许模型多下钻一层，但不允许走错分支。 */
    public static boolean pathMatches(List<String> expected, List<String> actual) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (actual == null || actual.size() < expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 叶子是路径最后一个节点的值，用来回答"结论落在哪个值上"。 */
    public static boolean leafMatches(String expectedLeaf, List<String> actual) {
        if (expectedLeaf == null || expectedLeaf.isBlank()) {
            return true;
        }
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        String last = actual.get(actual.size() - 1);
        int at = last.indexOf('=');
        return expectedLeaf.equals(at >= 0 ? last.substring(at + 1) : last);
    }

    public static Report summarize(List<Scored> scored) {
        List<Scored> attribution = scored.stream().filter(s -> s.kind() == Kind.ATTRIBUTION).toList();
        List<Scored> lookup = scored.stream().filter(s -> s.kind() == Kind.LOOKUP).toList();

        int aPassed = (int) attribution.stream().filter(Scored::passed).count();
        AttributionGroup aGroup = new AttributionGroup(
                attribution.size(), aPassed, rate(aPassed, attribution.size()),
                rate((int) attribution.stream().filter(Scored::outcomeMatch).count(), attribution.size()),
                rate((int) attribution.stream().filter(Scored::pathMatch).count(), attribution.size()),
                rate((int) attribution.stream().filter(Scored::leafMatch).count(), attribution.size()));

        int lPassed = (int) lookup.stream().filter(Scored::passed).count();
        Group lGroup = new Group(lookup.size(), lPassed, rate(lPassed, lookup.size()));

        // 探针数只在归因路径上存在，把查数题的 0 一起平均会把这个指标稀释掉
        Efficiency efficiency = new Efficiency(
                avg(scored.stream().mapToInt(Scored::steps).average()),
                avg(attribution.stream().mapToInt(Scored::probes).average()),
                avg(scored.stream().mapToInt(Scored::tokens).average()));

        List<Scored> failures = scored.stream().filter(s -> !s.passed()).toList();
        return new Report(aGroup, lGroup, efficiency,
                (int) scored.stream().filter(Scored::degraded).count(), failures);
    }

    private static double rate(int hit, int total) {
        return total == 0 ? 0 : round((double) hit / total);
    }

    private static double avg(java.util.OptionalDouble value) {
        return value.isPresent() ? round(value.getAsDouble()) : 0;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
