package com.insight.agent.attribution;

import java.util.List;
import java.util.Locale;

/**
 * 指标守卫：问题里明确点名"不支持的指标"就拒绝，别按另一个口径硬答。
 *
 * <p>受支持指标（gmv / order_count）的词互相放行——由 {@link MetricRouter} 决定用哪个；
 * 只有出现不支持的指标（退款率、客单价等）才拒绝。方向刻意不对称：
 * 宁可漏判（当默认指标处理），不可误拒——误拒等于把功能下线。
 *
 * <p>词表来自知识库里已有的指标定义，不自己发明同义词。
 */
public final class AttributionMetricGuard {

    /** 不支持的指标。出现任意一个就拒绝，除非同时出现了受支持指标的词。 */
    private static final List<String> OTHER_METRICS = List.of(
            "客单价", "退款率", "核销率", "活跃用户", "复购率",
            "新客占比", "转化率", "券领取率", "券使用率", "券过期率", "退款金额");

    private AttributionMetricGuard() {
    }

    /** 兼容旧调用：按默认指标（gmv）判定。 */
    public static void check(String question) {
        check(question, MetricRouter.GMV);
    }

    public static void check(String question, String metric) {
        if (question == null || question.isBlank()) {
            return;
        }
        String text = question.toLowerCase(Locale.ROOT);
        if (containsAny(text, MetricRouter.wordsFor(metric))) {
            return;
        }
        // 点名了另一个受支持指标：不拒绝，交给路由
        if (containsAny(text, MetricRouter.otherWords(metric))) {
            return;
        }
        for (String word : OTHER_METRICS) {
            if (text.contains(word)) {
                throw new UnsupportedMetricException(word);
            }
        }
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
