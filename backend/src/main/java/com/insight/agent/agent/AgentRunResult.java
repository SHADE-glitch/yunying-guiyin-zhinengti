package com.insight.agent.agent;

import java.util.UUID;

public record AgentRunResult(
        UUID runId,
        String answer,
        int steps,
        int promptTokens,
        int completionTokens,
        boolean succeeded,
        String error) {
}
