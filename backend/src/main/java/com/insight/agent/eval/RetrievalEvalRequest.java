package com.insight.agent.eval;

import java.util.List;
import java.util.UUID;

public record RetrievalEvalRequest(
        String label,
        String note,
        UUID baselineId,
        String gitSha,
        Integer topK,
        List<String> modes) {

    public static RetrievalEvalRequest empty() {
        return new RetrievalEvalRequest(null, null, null, null, null, null);
    }

    public int topKOrDefault() {
        return topK == null ? 5 : topK;
    }

    public void validate() {
        int k = topKOrDefault();
        if (k < 1 || k > 20) {
            throw new IllegalArgumentException("topK 必须在 1..20 之间");
        }
        if (modes != null) {
            for (String mode : modes) {
                if (!mode.equals("VECTOR") && !mode.equals("KEYWORD") && !mode.equals("HYBRID")) {
                    throw new IllegalArgumentException("未知检索模式: " + mode);
                }
            }
        }
    }

    public String labelOr(String fallback) {
        return label == null || label.isBlank() ? fallback : label.strip();
    }
}
