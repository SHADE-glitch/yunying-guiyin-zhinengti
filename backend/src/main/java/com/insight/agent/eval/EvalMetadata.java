package com.insight.agent.eval;

import java.time.Instant;
import java.util.Map;

/** 批次开始时冻结的环境。任何一项缺失，复盘时都无法解释"为什么这次数字不一样"。 */
public record EvalMetadata(
        String gitSha,
        String model,
        String embeddingModel,
        Instant asOf,
        Map<String, Object> config,
        Map<String, String> promptHashes,
        String datasetVersion,
        int datasetSize) {
}
