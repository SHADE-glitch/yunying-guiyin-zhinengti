package com.insight.agent.attribution;

import com.insight.agent.attribution.ContributionMath.Item;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 状态机与外部世界的两个接口。
 *
 * <p>阶段 2 用假实现跑状态机，阶段 3 换成 SQL 和模型，状态机本身不用改。
 * 这样"控制流对不对"和"SQL 写得对不对"可以分开验证。
 */
public final class AttributionPorts {

    private AttributionPorts() {
    }

    /** 查询范围：父节点累积下来的过滤条件。 */
    public record Scope(Map<Dimension, String> filters) {

        public static Scope root() {
            return new Scope(Map.of());
        }

        public Scope with(Dimension dimension, String value) {
            Map<Dimension, String> next = new LinkedHashMap<>(filters);
            next.put(dimension, value);
            return new Scope(Map.copyOf(next));
        }

        public boolean contains(Dimension dimension) {
            return filters.containsKey(dimension);
        }
    }

    public record Totals(BigDecimal current, BigDecimal baseline, Evidence evidence) {

        /** 无证据的构造：单测里用，真实查询必须带 evidence。 */
        public Totals(BigDecimal current, BigDecimal baseline) {
            this(current, baseline, null);
        }

        public BigDecimal delta() {
            return current.subtract(baseline);
        }
    }

    /** 探针结果。三种状态对应三种不同的后续动作，不能合并。 */
    public record ProbeResult(Status status, List<Item> items, String error, Evidence evidence) {

        public enum Status {
            /** 拆分成功 */
            OK,
            /** 该维度只有一个取值，拆了也没有信息量 */
            NO_INFORMATION,
            /** 被护栏拒绝 / 超时 / 空结果 / 异常 */
            FAILED
        }

        public ProbeResult(Status status, List<Item> items, String error) {
            this(status, items, error, null);
        }

        public static ProbeResult ok(List<Item> items) {
            return new ProbeResult(Status.OK, items, null, null);
        }

        public static ProbeResult ok(List<Item> items, Evidence evidence) {
            return new ProbeResult(Status.OK, items, null, evidence);
        }

        public static ProbeResult noInformation() {
            return new ProbeResult(Status.NO_INFORMATION, List.of(), null, null);
        }

        public static ProbeResult failed(String error) {
            return new ProbeResult(Status.FAILED, List.of(), error, null);
        }

        public static ProbeResult failed(String error, Evidence evidence) {
            return new ProbeResult(Status.FAILED, List.of(), error, evidence);
        }
    }

    /**
     * 给提议器的上下文：已经到第几层、哪些维度用过了、哪些维度已经废了、当前路径是什么。
     *
     * <p>{@code exhausted} 必须单独传：探测反复失败的维度只进了 {@code exhausted}，不进
     * {@code used}，只报 used 的话提议器会一直提议它，把无效提议计数烧到熔断。
     */
    public record ProposalContext(int depth, List<Dimension> used, Set<Dimension> exhausted, List<String> path) {
    }

    /** 由模型实现：提议下一个要拆的维度。返回 null 表示它放弃提议。 */
    public interface DimensionProposer {
        Dimension propose(ProposalContext context);
    }

    /** 由 SQL 层实现：取根节点总量、按维度拆分。 */
    public interface SplitProvider {
        Totals rootTotals();

        ProbeResult probe(Dimension dimension, Scope scope);
    }
}
