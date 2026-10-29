package com.insight.agent.attribution;

import java.util.List;
import java.util.Locale;

/**
 * 指标路由：问题里说的是哪个可加指标，就按哪个归因。
 *
 * <p>只认可加分解的指标（gmv / order_count）。比率类指标（退款率等）即使出现也不路由——
 * 它们不满足可加分解，硬拆是数学错误，由指标守卫拒绝。
 */
public final class MetricRouter {

    public static final String GMV = "gmv";
    public static final String ORDER_COUNT = "order_count";

    private static final List<String> GMV_WORDS = List.of("gmv", "销售额", "成交额", "交易额");
    private static final List<String> ORDER_WORDS = List.of("订单量", "订单数", "单量", "成交单数");

    private MetricRouter() {
    }

    /** 问题里是否点名了受支持的指标（用于会话补全判断"这是不是一个新问题"）。 */
    public static boolean mentionsMetric(String question) {
        return containsAny(question, GMV_WORDS) || containsAny(question, ORDER_WORDS);
    }

    /** 只提一边：按那个指标（显式词优先于默认值）；都没提或都提：按默认指标。 */
    public static String route(String question, String defaultMetric) {
        boolean gmv = containsAny(question, GMV_WORDS);
        boolean order = containsAny(question, ORDER_WORDS);
        if (gmv && !order) {
            return GMV;
        }
        if (order && !gmv) {
            return ORDER_COUNT;
        }
        return defaultMetric;
    }

    public static String label(String metric) {
        return ORDER_COUNT.equals(metric) ? "订单量" : "GMV";
    }

    /** 某个受支持指标的同义词（守卫放行用）。 */
    public static List<String> wordsFor(String metric) {
        return ORDER_COUNT.equals(metric) ? ORDER_WORDS : GMV_WORDS;
    }

    /** 另一个受支持指标的同义词：出现也不拒绝，交给路由选。 */
    public static List<String> otherWords(String metric) {
        return ORDER_COUNT.equals(metric) ? GMV_WORDS : ORDER_WORDS;
    }

    private static boolean containsAny(String question, List<String> words) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String text = question.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
