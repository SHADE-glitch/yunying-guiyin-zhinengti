package com.insight.agent.agent;

import com.insight.agent.tool.ToolOutcome;
import java.util.HashMap;
import java.util.Map;

/**
 * Reflection 的判定逻辑，与循环解耦，方便单测。
 *
 * <p>规则：连续 N 次工具调用没拿到有效结果，就插入一条自省提示，最多 M 轮。
 * 为什么要连续计数而不是单次失败：模型偶尔写错一条 SQL 是正常的，自己就能改；
 * 连续失败说明它卡在同一个错误假设上，这时候才值得打断它。
 */
public class ReflectionTracker {

    private final int failureThreshold;
    private final int maxRounds;

    private int consecutiveFailures;
    private int rounds;
    private final Map<String, Integer> attempts = new HashMap<>();

    private String lastTool;
    private String lastSignature;
    private ToolOutcome lastOutcome;

    public ReflectionTracker(int failureThreshold, int maxRounds) {
        this.failureThreshold = failureThreshold;
        this.maxRounds = maxRounds;
    }

    public void record(String tool, String args, ToolOutcome outcome) {
        String signature = tool + "|" + (args == null ? "" : args);
        attempts.merge(signature, 1, Integer::sum);

        this.lastTool = tool;
        this.lastSignature = signature;
        this.lastOutcome = outcome;

        if (outcome.unusable()) {
            consecutiveFailures++;
        } else {
            consecutiveFailures = 0;
        }
    }

    public boolean shouldReflect() {
        return consecutiveFailures >= failureThreshold && rounds < maxRounds;
    }

    /** 生成自省提示并消耗一轮额度。没有可反思的内容时返回 null。 */
    public String consumeReflectionMessage() {
        if (!shouldReflect()) {
            return null;
        }
        rounds++;

        int repeats = attempts.getOrDefault(lastSignature, 1);
        String repeatNote = repeats > 1
                ? "注意：你已经用完全相同的参数调用过 " + repeats + " 次，不要重复同样的调用。"
                : "";

        String message = """
                [自省] 你连续 %d 次工具调用没有拿到有效结果。
                最近一次：%s → %s：%s
                %s
                请换一种方式再试，最多再试 %d 次：
                1. 字段名或表名不确定 → 先调用 get_schema
                2. 结果为空 → 放宽过滤条件，或换一个时间窗
                3. 口径不确定 → 先调用 search_knowledge
                如果确实查不到，直接说明查不到，不要编造。
                """.formatted(
                consecutiveFailures,
                lastTool,
                lastOutcome.status(),
                abbreviate(lastOutcome.content()),
                repeatNote,
                maxRounds - rounds + 1);

        consecutiveFailures = 0;
        return message;
    }

    public int rounds() {
        return rounds;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
