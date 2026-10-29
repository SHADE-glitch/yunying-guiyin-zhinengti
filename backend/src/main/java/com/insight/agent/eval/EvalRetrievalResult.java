package com.insight.agent.eval;

import java.util.List;

/** 检索批次里一条 query 在一种模式下的结果。 */
public record EvalRetrievalResult(
        String mode,
        String query,
        List<Long> expectedIds,
        List<Long> retrievedIds,
        double recall,
        double reciprocalRank) {
}
