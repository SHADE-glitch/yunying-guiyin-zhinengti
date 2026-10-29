package com.insight.agent.attribution;

import java.math.BigDecimal;

public record AttributionConfig(
        int maxDepth,
        int maxProbes,
        BigDecimal threshold,
        BigDecimal minChangeRatio,
        BigDecimal minChangeAbsolute,
        BigDecimal tolerance,
        int invalidProposalLimit,
        int dimensionFailureLimit,
        int topN) {

    public static AttributionConfig defaults() {
        return new AttributionConfig(
                2,
                8,
                new BigDecimal("0.6"),
                new BigDecimal("0.03"),
                new BigDecimal("100"),
                new BigDecimal("0.05"),
                3,
                2,
                10);
    }
}
