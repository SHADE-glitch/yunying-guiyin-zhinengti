package com.insight.agent.eval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 启动一次 Agent 评测。所有覆盖项都是可选的；空表示用当前默认配置。
 * 覆盖只作用于这一批，不改全局配置，也不影响正在服务的 API。
 */
public record AgentEvalRequest(
        String label,
        String note,
        UUID baselineId,
        String gitSha,
        Instant asOf,
        BigDecimal threshold,
        Integer maxProbes,
        Integer maxDepth,
        Integer queryBudget,
        Integer tokenBudget,
        Boolean modelProposerEnabled,
        String proposerVariant,
        String reporterVariant,
        Integer maxSteps,
        Integer lookupTokenBudget) {

    public static AgentEvalRequest empty() {
        return new AgentEvalRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public void validate() {
        if (threshold != null && (threshold.signum() <= 0
                || threshold.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("threshold 必须在 (0, 1] 之间");
        }
        if (maxProbes != null && (maxProbes < 1 || maxProbes > 16)) {
            throw new IllegalArgumentException("maxProbes 必须在 1..16 之间");
        }
        if (maxDepth != null && (maxDepth < 1 || maxDepth > 4)) {
            throw new IllegalArgumentException("maxDepth 必须在 1..4 之间");
        }
        if (queryBudget != null && queryBudget < 0) {
            throw new IllegalArgumentException("queryBudget 不能为负");
        }
        if (tokenBudget != null && tokenBudget < 0) {
            throw new IllegalArgumentException("tokenBudget 不能为负");
        }
        if (maxSteps != null && (maxSteps < 1 || maxSteps > 24)) {
            throw new IllegalArgumentException("maxSteps 必须在 1..24 之间");
        }
        if (lookupTokenBudget != null && lookupTokenBudget < 0) {
            throw new IllegalArgumentException("lookupTokenBudget 不能为负");
        }
    }

    public String labelOr(String fallback) {
        return label == null || label.isBlank() ? fallback : label.strip();
    }
}
