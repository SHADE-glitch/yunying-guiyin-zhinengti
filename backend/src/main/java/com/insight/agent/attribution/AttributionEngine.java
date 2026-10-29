package com.insight.agent.attribution;

import com.insight.agent.attribution.AttributionPorts.DimensionProposer;
import com.insight.agent.attribution.AttributionPorts.ProbeResult;
import com.insight.agent.attribution.AttributionPorts.ProposalContext;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.SplitProvider;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Item;
import com.insight.agent.attribution.ContributionMath.Scored;
import com.insight.agent.attribution.ContributionMath.Split;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 归因状态机。
 *
 * <p>模型只做一件事：提议下一个拆哪个维度。候选选择、贡献度计算、阈值判断、
 * 终止条件全部在这里，都是确定性的代码。
 *
 * <p>为什么不让模型判断"是不是这个原因"：它会编。让代码算出"这个子项占了父节点跌幅的 82%"，
 * 它编不了。
 */
public class AttributionEngine {

    public static final String AGGREGATE_LABEL = "其他";

    private final AttributionConfig config;

    public AttributionEngine(AttributionConfig config) {
        this.config = config;
    }

    public AttributionResult run(DimensionProposer proposer, SplitProvider provider) {
        return run(proposer, provider, AttributionListener.NOOP);
    }

    /**
     * 带观察者的入口。观察者只收事件，不参与判定：状态机的结论和没有观察者时完全一致。
     */
    public AttributionResult run(DimensionProposer proposer, SplitProvider provider, AttributionListener listener) {
        AttributionResult result = execute(proposer, provider, listener);
        listener.onFinish(result);
        return result;
    }

    private AttributionResult execute(DimensionProposer proposer, SplitProvider provider,
                                      AttributionListener listener) {
        Totals root;
        try {
            root = provider.rootTotals();
        } catch (QueryBudgetExhausted e) {
            return result(AttributionOutcome.BUDGET_EXHAUSTED, List.of(), 0, 0, false, e.getMessage(), null);
        }
        Evidence rootEvidence = root.evidence();
        listener.onBaseline(root);

        if (!significant(root.current(), root.baseline())) {
            return result(AttributionOutcome.NO_SIGNIFICANT_CHANGE, List.of(), 0, 0, false,
                    "总变化未达到显著性门槛，不做归因", rootEvidence);
        }

        Scope scope = Scope.root();
        BigDecimal parentDelta = root.delta();
        List<AttributionNode> path = new ArrayList<>();
        ProposalGuard guard = new ProposalGuard(config.dimensionFailureLimit());
        int probes = 0;

        for (int depth = 1; depth <= config.maxDepth(); depth++) {
            boolean advanced = false;

            while (!advanced) {
                if (probes >= config.maxProbes()) {
                    return result(AttributionOutcome.BUDGET_EXHAUSTED, path, probes, guard.invalidTotal(), false,
                            "探针预算用尽，未收敛", rootEvidence);
                }
                if (guard.invalidStreak() >= config.invalidProposalLimit()) {
                    return result(AttributionOutcome.INVALID_PROPOSAL_LIMIT, path, probes, guard.invalidTotal(), false,
                            "连续无效提议达到上限", rootEvidence);
                }

                Dimension dimension = proposer.propose(
                        new ProposalContext(depth, List.copyOf(guard.probed()), guard.exhausted(), describe(path)));

                if (dimension == null) {
                    // 模型给不出合法维度（包括它编了一个不在枚举里的值）
                    guard.recordInvalid();
                    continue;
                }
                if (guard.validate(dimension) != ProposalGuard.Verdict.VALID) {
                    guard.recordInvalid();
                    continue;
                }

                ProbeResult probe;
                try {
                    probe = provider.probe(dimension, scope);
                } catch (QueryBudgetExhausted e) {
                    // 预算用尽是全局硬约束：不换维度继续试，直接终止
                    return result(AttributionOutcome.BUDGET_EXHAUSTED, path, probes, guard.invalidTotal(), false,
                            e.getMessage(), rootEvidence);
                }
                listener.onProbe(depth, dimension, scope, probe);

                if (probe.status() == ProbeResult.Status.NO_INFORMATION) {
                    guard.recordNoInformation(dimension);
                    continue;
                }
                probes++;
                if (probe.status() == ProbeResult.Status.FAILED) {
                    // 被护栏拒绝 / 超时 / 空结果：换一个维度再来，不终止
                    guard.recordProbeFailure(dimension);
                    continue;
                }

                List<Item> items = probe.items();
                if (!ContributionMath.hasInformation(items)) {
                    guard.recordNoInformation(dimension);
                    continue;
                }
                if (dimension.highCardinality()) {
                    items = ContributionMath.foldTopN(items, config.topN(), AGGREGATE_LABEL);
                }

                Split split = ContributionMath.split(items, parentDelta, config.tolerance());
                Optional<Scored> dominant = ContributionMath.selectDominant(split, config.threshold());
                // 先回调再判结局：没有主因、数据不完整时，这一层的候选也要留证据，
                // 否则事后只能看到"结论是分散"，看不到"分散在哪几个值上"
                listener.onSplit(depth, dimension, scope, split, dominant.orElse(null), probe.evidence());

                if (!split.consistent()) {
                    return result(AttributionOutcome.DATA_INCOMPLETE, path, probes, guard.invalidTotal(), false,
                            "分组汇总与父节点总量偏差过大，无法给出可靠归因", rootEvidence);
                }
                if (dominant.isEmpty()) {
                    // 上一层的结论仍然成立，只是这一层拆不开——两者要分开说，
                    // 否则会得到"没有主因"和路径里明明有火锅这样自相矛盾的报告
                    String message = path.isEmpty()
                            ? "变化分散在多个子项，没有单项达到阈值"
                            : "已定位到 " + path.get(path.size() - 1).value()
                                    + "，但它内部的变化分散在多个子项，没有单项达到阈值";
                    return result(AttributionOutcome.MULTI_FACTOR, path, probes, guard.invalidTotal(), false,
                            message, rootEvidence);
                }

                Scored selected = dominant.get();
                guard.recordSuccess(dimension);
                path.add(new AttributionNode(depth, dimension, selected.value(),
                        selected.current(), selected.baseline(), selected.delta(), selected.contribution(),
                        probe.evidence()));

                if (depth == config.maxDepth() || dimension.leaf()) {
                    // 门店已是最细维度：即使 maxDepth 还没到，也没有下一层可拆。
                    // 继续问提议器只会得到"没有可拆维度"，把一次成功的归因拖成无效提议熔断。
                    return result(AttributionOutcome.DOMINANT_FOUND, path, probes, guard.invalidTotal(),
                            AGGREGATE_LABEL.equals(selected.value()),
                            "已定位到主要变化来源", rootEvidence);
                }
                if (!significant(selected.current(), selected.baseline())) {
                    return result(AttributionOutcome.INSUFFICIENT_EVIDENCE, path, probes, guard.invalidTotal(),
                            AGGREGATE_LABEL.equals(selected.value()),
                            "选中子项自身变化不显著，继续下钻的分母没有意义", rootEvidence);
                }

                scope = scope.with(dimension, selected.value());
                parentDelta = selected.delta();
                advanced = true;
            }
        }

        return result(AttributionOutcome.BUDGET_EXHAUSTED, path, probes, guard.invalidTotal(), false,
                "达到深度上限仍未收敛", rootEvidence);
    }

    private boolean significant(BigDecimal current, BigDecimal baseline) {
        return ContributionMath.isSignificant(current, baseline,
                config.minChangeRatio(), config.minChangeAbsolute());
    }

    private static List<String> describe(List<AttributionNode> path) {
        return path.stream().map(n -> n.dimension().name() + "=" + n.value()).toList();
    }

    private static AttributionResult result(AttributionOutcome outcome, List<AttributionNode> path,
                                            int probes, int invalid, boolean aggregate, String message,
                                            Evidence rootEvidence) {
        return new AttributionResult(outcome, List.copyOf(path), probes, invalid, aggregate, message, rootEvidence);
    }
}
