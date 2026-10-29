package com.insight.agent.attribution;

/**
 * 归因的终止结局。
 *
 * <p>刻意分成多个值而不是统一的"成功/失败"：
 * "试得太多"、"模型不听话"、"数据对不上"是三种完全不同的问题，
 * 排查时要一眼看出是哪一类。
 */
public enum AttributionOutcome {

    /** 找到主要变化来源，且已到最大深度 */
    DOMINANT_FOUND,

    /** 变化分散，没有单项达到阈值 */
    MULTI_FACTOR,

    /** 总变化不显著，不做归因 */
    NO_SIGNIFICANT_CHANGE,

    /** 分组汇总与父节点总量对不上 */
    DATA_INCOMPLETE,

    /** 父节点变化趋零，继续下钻的分母没有意义 */
    INSUFFICIENT_EVIDENCE,

    /** 连续无效提议达到上限 */
    INVALID_PROPOSAL_LIMIT,

    /** 探针预算用尽 */
    BUDGET_EXHAUSTED,

    /** 其他异常 */
    FAILED
}
