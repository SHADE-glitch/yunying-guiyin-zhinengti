package com.insight.agent.eval;

import java.util.List;

/** 一个批次的完整视图：元数据 + 汇总 + 逐用例结果。 */
public record EvalBatchDetail(
        EvalBatch batch,
        List<EvalCaseResult> cases,
        List<EvalRetrievalResult> retrievalCases) {
}
