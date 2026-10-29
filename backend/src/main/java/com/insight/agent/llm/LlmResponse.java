package com.insight.agent.llm;

import java.util.List;

public record LlmResponse(
        String content,
        List<ToolCallReq> toolCalls,
        int promptTokens,
        int completionTokens) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static LlmResponse text(String content, int promptTokens, int completionTokens) {
        return new LlmResponse(content, List.of(), promptTokens, completionTokens);
    }
}
