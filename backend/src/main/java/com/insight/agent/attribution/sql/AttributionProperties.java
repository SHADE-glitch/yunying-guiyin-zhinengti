package com.insight.agent.attribution.sql;

import com.insight.agent.attribution.AttributionConfig;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 归因的全部可调参数，前缀 {@code agent.attribution}。
 *
 * <p>分两组看：**查询资源**（窗口、预算、行数、超时）决定打数据库花多少；
 * **判定阈值**（层数、探针数、贡献度门槛、显著性门槛、容差）决定什么算"主因"。
 *
 * <p>为什么不把安全规则配置化：SqlGuard 的语句白名单、只读账号、单条语句是不变量，
 * 配错了就是漏洞，不该给运维一个开关。这里的参数调松了结论更激进、调紧了更保守，
 * 属于业务口径，可以调。
 *
 * <p>校验放在 {@code @PostConstruct}：配置错了启动就失败，而不是等第一次归因
 * 才抛一个看不懂的错。
 */
@Component
@ConfigurationProperties(prefix = "agent.attribution")
public class AttributionProperties {

    /** 指标口径。支持 gmv / order_count（都可加分解），加新指标要同时改 SqlSplitProvider.valueExpression。 */
    private String metric = "gmv";

    /** 当前窗口长度；基期是同长度的紧邻窗口。 */
    private int windowDays = 30;

    /** 单次归因允许打到数据库的查询次数上限，硬约束。0 表示按 1 + max-probes × 3 推导。 */
    private int queryBudget = 0;

    /** 单次归因允许消耗的 token 上限；超了就停用模型（提议降级为规则版、结论降级为计算结果）。 */
    private int tokenBudget = 20000;

    /** 分组查询的行数上限。门店维度会返回全部门店，必须留够；触顶即判失败，不静默截断。 */
    private int probeMaxRows = 1000;

    /** 单条查询超时秒数。 */
    private int timeoutSeconds = 10;

    /** 窗口锚点。留空用当前时间；测试和复盘时钉死它才能复现。 */
    private Instant asOf;

    /** 下钻层数（根节点不计）。 */
    private int maxDepth = 2;

    /** 一次归因最多做几次拆分探测。 */
    private int maxProbes = 8;

    /** 子项贡献度达到多少才算"主要变化来源"。只说明值得继续查，不说明是根本原因。 */
    private BigDecimal threshold = new BigDecimal("0.6");

    /** 变化显著性门槛：相对变化。 */
    private BigDecimal minChangeRatio = new BigDecimal("0.03");

    /** 基期为 0 时的绝对门槛。 */
    private BigDecimal minChangeAbsolute = new BigDecimal("100");

    /** 完备性容差：|Σc − sign(Δ父)| 超过它就判数据不完整。 */
    private BigDecimal tolerance = new BigDecimal("0.05");

    /** 连续多少次无效提议就熔断。 */
    private int invalidProposalLimit = 3;

    /** 同一维度连续失败几次就标记耗尽。 */
    private int dimensionFailureLimit = 2;

    /** 高基数维度折叠后保留几个明细。 */
    private int topN = 10;

    /** 是否让模型提议维度。关掉就全程用规则版，用于离线演示和回归。 */
    private boolean modelProposerEnabled = true;

    @PostConstruct
    public void validate() {
        require(windowDays >= 1, "window-days 必须 >= 1");
        require(queryBudget >= 0, "query-budget 不能为负（0 表示按 1 + max-probes×3 推导）");
        require(tokenBudget >= 0, "token-budget 不能为负（0 表示不限制）");
        require(probeMaxRows >= 1, "probe-max-rows 必须 >= 1");
        require(timeoutSeconds >= 1, "timeout-seconds 必须 >= 1");
        require(maxDepth >= 1 && maxDepth <= 4, "max-depth 必须在 1..4 之间（每多一层都多一次全量分组查询）");
        require(maxProbes >= 1, "max-probes 必须 >= 1");
        require(threshold.signum() > 0 && threshold.compareTo(BigDecimal.ONE) <= 0,
                "threshold 必须在 (0, 1] 之间");
        require(minChangeRatio.signum() >= 0 && minChangeRatio.compareTo(BigDecimal.ONE) < 0,
                "min-change-ratio 必须在 [0, 1) 之间");
        require(minChangeAbsolute.signum() >= 0, "min-change-absolute 必须 >= 0");
        require(tolerance.signum() >= 0 && tolerance.compareTo(BigDecimal.ONE) < 0,
                "tolerance 必须在 [0, 1) 之间");
        require(invalidProposalLimit >= 1, "invalid-proposal-limit 必须 >= 1");
        require(dimensionFailureLimit >= 1, "dimension-failure-limit 必须 >= 1");
        require(topN >= 1, "top-n 必须 >= 1");
        require("gmv".equals(metric) || "order_count".equals(metric),
                "metric 只支持 gmv / order_count，当前是: " + metric);
    }

    /** 转成状态机用的不可变配置。 */
    public AttributionConfig toConfig() {
        return new AttributionConfig(maxDepth, maxProbes, threshold, minChangeRatio, minChangeAbsolute,
                tolerance, invalidProposalLimit, dimensionFailureLimit, topN);
    }

    /**
     * 复制一份用于评测变体。评测只改这一份，不碰正在服务的全局配置。
     */
    public AttributionProperties copy() {
        AttributionProperties copy = new AttributionProperties();
        copy.metric = metric;
        copy.windowDays = windowDays;
        copy.queryBudget = queryBudget;
        copy.tokenBudget = tokenBudget;
        copy.probeMaxRows = probeMaxRows;
        copy.timeoutSeconds = timeoutSeconds;
        copy.asOf = asOf;
        copy.maxDepth = maxDepth;
        copy.maxProbes = maxProbes;
        copy.threshold = threshold;
        copy.minChangeRatio = minChangeRatio;
        copy.minChangeAbsolute = minChangeAbsolute;
        copy.tolerance = tolerance;
        copy.invalidProposalLimit = invalidProposalLimit;
        copy.dimensionFailureLimit = dimensionFailureLimit;
        copy.topN = topN;
        copy.modelProposerEnabled = modelProposerEnabled;
        return copy;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("agent.attribution 配置非法: " + message);
        }
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public int getQueryBudget() {
        // 0 = 由探测上限推导：1 次根口径 + 每次探测最多 3 次查询（维度值解析 + 分组查询）。
        // 这样改 max-probes 时预算跟着走，不会静默失配。
        return queryBudget > 0 ? queryBudget : 1 + maxProbes * 3;
    }

    public void setQueryBudget(int queryBudget) {
        this.queryBudget = queryBudget;
    }

    public int getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(int tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public int getProbeMaxRows() {
        return probeMaxRows;
    }

    public void setProbeMaxRows(int probeMaxRows) {
        this.probeMaxRows = probeMaxRows;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public void setAsOf(Instant asOf) {
        this.asOf = asOf;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxProbes() {
        return maxProbes;
    }

    public void setMaxProbes(int maxProbes) {
        this.maxProbes = maxProbes;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }

    public BigDecimal getMinChangeRatio() {
        return minChangeRatio;
    }

    public void setMinChangeRatio(BigDecimal minChangeRatio) {
        this.minChangeRatio = minChangeRatio;
    }

    public BigDecimal getMinChangeAbsolute() {
        return minChangeAbsolute;
    }

    public void setMinChangeAbsolute(BigDecimal minChangeAbsolute) {
        this.minChangeAbsolute = minChangeAbsolute;
    }

    public BigDecimal getTolerance() {
        return tolerance;
    }

    public void setTolerance(BigDecimal tolerance) {
        this.tolerance = tolerance;
    }

    public int getInvalidProposalLimit() {
        return invalidProposalLimit;
    }

    public void setInvalidProposalLimit(int invalidProposalLimit) {
        this.invalidProposalLimit = invalidProposalLimit;
    }

    public int getDimensionFailureLimit() {
        return dimensionFailureLimit;
    }

    public void setDimensionFailureLimit(int dimensionFailureLimit) {
        this.dimensionFailureLimit = dimensionFailureLimit;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public boolean isModelProposerEnabled() {
        return modelProposerEnabled;
    }

    public void setModelProposerEnabled(boolean modelProposerEnabled) {
        this.modelProposerEnabled = modelProposerEnabled;
    }
}
