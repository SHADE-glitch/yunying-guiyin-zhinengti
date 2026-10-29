package com.insight.agent.session;

import com.insight.agent.attribution.MetricRouter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 追问补全：只在高置信时补，判不准就重问。
 *
 * <p>高置信 = 第一轮之后 + 短问题（≤20 字）+ 没点名指标，并且要么认出了新的范围词
 * （"那华南呢"），要么是明显的延续词（"为什么跌了"/"继续"）。补全只做文本层面：
 * 把继承来的范围/指标写进问题，交给既有的范围解析与指标路由——不改任何判定逻辑。
 */
@Component
public class FollowUpCompleter {

    public enum Kind { AS_IS, INHERIT, CLARIFY }

    public record Completion(Kind kind, String resolvedQuestion, String note) {

        static Completion asIs(String question) {
            return new Completion(Kind.AS_IS, question, null);
        }

        static Completion clarify(String question, String note) {
            return new Completion(Kind.CLARIFY, question, note);
        }
    }

    private static final List<String> CONTINUATION_WORDS = List.of(
            "跌", "涨", "变化", "原因", "继续", "然后", "呢", "分析", "看看", "看下");

    public Completion complete(String question, SessionService.State state,
                               boolean hasExplicitScope, boolean mentionsMetric) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            return Completion.clarify(q, "问题为空");
        }
        if (state == null || state.turnCount() == 0) {
            return Completion.asIs(q);
        }
        if (mentionsMetric) {
            return Completion.asIs(q);
        }
        if (q.length() > 20) {
            return Completion.asIs(q);
        }
        String metricLabel = MetricRouter.label(state.metric());
        if (hasExplicitScope) {
            return new Completion(Kind.INHERIT, q + " 指标 " + metricLabel,
                    "继承上一轮指标：" + metricLabel);
        }
        if (!state.scope().isEmpty() && isContinuation(q)) {
            return new Completion(Kind.INHERIT,
                    q + " " + scopeText(state.scope()) + " 指标 " + metricLabel,
                    "继承上一轮范围与指标");
        }
        return Completion.clarify(q,
                "问得不够具体，请补充指标或范围（例如「华东 GMV 为什么跌了」）");
    }

    private static boolean isContinuation(String q) {
        for (String word : CONTINUATION_WORDS) {
            if (q.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String scopeText(Map<String, String> scope) {
        StringBuilder text = new StringBuilder();
        scope.values().forEach(value -> {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(value);
        });
        return text.toString();
    }
}
