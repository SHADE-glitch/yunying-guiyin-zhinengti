package com.insight.agent.eval;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 一次评测批次。元数据先于结果存在：没有代码版本、模型、as-of、配置、prompt 指纹和
 * 数据集版本，两次评测的数字就不可比，"这次比上次好"只是一句话。
 */
public record EvalBatch(
        UUID id,
        String kind,
        String label,
        String note,
        String status,
        UUID baselineId,
        String gitSha,
        String model,
        String embeddingModel,
        Instant asOf,
        Map<String, Object> config,
        Map<String, String> promptHashes,
        String datasetVersion,
        int datasetSize,
        int progressDone,
        int progressTotal,
        Map<String, Object> summary,
        String error,
        Instant startedAt,
        Instant finishedAt) {

    public boolean running() {
        return "RUNNING".equals(status);
    }

    public static final String KIND_AGENT = "AGENT";
    public static final String KIND_RETRIEVAL = "RETRIEVAL";
}
