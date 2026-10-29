package com.insight.agent.attribution;

/**
 * 问题里的指标不在受支持范围（gmv / order_count），无法归因。
 *
 * <p>单独一个异常类型而不是复用 IllegalArgumentException：控制器要把它映射成
 * {@code UNSUPPORTED_METRIC} 错误码，前端据此提示"换一种问法"，和真正的故障分开。
 */
public class UnsupportedMetricException extends RuntimeException {

    private final String metric;

    public UnsupportedMetricException(String metric) {
        super("本次归因只支持 GMV / 订单量 口径，问题里问的是「" + metric + "」。可以换成「为什么 GMV 跌了」，或等指标扩展后再问。");
        this.metric = metric;
    }

    public String metric() {
        return metric;
    }
}
