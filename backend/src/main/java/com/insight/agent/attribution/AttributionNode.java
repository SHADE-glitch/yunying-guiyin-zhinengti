package com.insight.agent.attribution;

import java.math.BigDecimal;

/**
 * 路径上的一个节点。
 *
 * <p>contribution 统一是"相对父节点变化"的贡献度。depth 1 的父是根，
 * 所以根层贡献度看起来就是全局贡献度——这是同一个公式的特例，不是另一套口径。
 */
public record AttributionNode(
        int depth,
        Dimension dimension,
        String value,
        BigDecimal current,
        BigDecimal baseline,
        BigDecimal delta,
        BigDecimal contribution,
        Evidence evidence) {

    /** 无证据的构造：单测里用。 */
    public AttributionNode(int depth, Dimension dimension, String value, BigDecimal current,
                           BigDecimal baseline, BigDecimal delta, BigDecimal contribution) {
        this(depth, dimension, value, current, baseline, delta, contribution, null);
    }
}
