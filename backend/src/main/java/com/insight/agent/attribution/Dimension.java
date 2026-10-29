package com.insight.agent.attribution;

/** 可下钻的维度。枚举限定是刻意的：不让模型自由发挥，否则它会提议一个没有数据的维度。 */
public enum Dimension {

    REGION("区域", false),
    CATEGORY("品类", false),
    SHOP("门店", true);

    private final String label;
    private final boolean highCardinality;

    Dimension(String label, boolean highCardinality) {
        this.label = label;
        this.highCardinality = highCardinality;
    }

    public String label() {
        return label;
    }

    /** 高基数维度需要折叠成 Top-N + 其他，否则探针结果会撑爆 prompt。 */
    public boolean highCardinality() {
        return highCardinality;
    }

    /** 是否已是最细粒度，不能再下钻。 */
    public boolean leaf() {
        return this == SHOP;
    }
}
