package com.insight.agent.eval;

import java.util.Map;
import java.util.UUID;

/** 一条用例的判定结果。detail 里放诊断信息（路径、facts 统计、错误原因）。 */
public record EvalCaseResult(
        long caseId,
        String question,
        String kind,
        String expected,
        String actual,
        boolean passed,
        boolean outcomeMatch,
        boolean pathMatch,
        boolean leafMatch,
        boolean answerMatch,
        int steps,
        int probes,
        int tokens,
        boolean degraded,
        String failureKind,
        UUID runId,
        int durationMs,
        Map<String, Object> detail) {
}
