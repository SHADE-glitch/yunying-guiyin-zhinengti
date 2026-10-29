package com.insight.agent.attribution.proposal;

import com.insight.agent.attribution.AttributionPorts.DimensionProposer;
import com.insight.agent.attribution.AttributionPorts.ProposalContext;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.Dimension;

/**
 * 模型优先，不可用时降级到规则版。
 *
 * <p>回退策略是**显式**的两条，不是"出错就兜住"：
 *
 * <p>1. 模型**不可用**（调用异常、超时、限流）→ 立刻降级，并且本次运行剩余步骤都用规则版。
 * 每个维度都重试一次模型只会让一次归因从 3 秒变成 30 秒，而模型挂了不会在 3 秒内恢复。
 *
 * <p>2. 模型**给了不合法内容**（枚举外的值、已用过的维度、问题里已固定的维度）→ **不降级**，
 * 返回 null 交给 ProposalGuard 计数。这是 prompt 质量问题：回退能救这次请求，但会把
 * "模型总是提议错维度"这个事实藏起来，永远改不掉。
 *
 * <p>降级不绕过任何检查：这个类只负责"选哪个维度"，维度合法性、深度、预算仍然由
 * 状态机和 ProposalGuard 判定，与用哪个提议器无关。
 */
public class FallbackDimensionProposer implements DimensionProposer {

    private final DimensionProposer primary;
    private final DimensionProposer fallback;
    private final Scope rootScope;

    private boolean degraded;
    private String degradeReason;

    public FallbackDimensionProposer(DimensionProposer primary, DimensionProposer fallback, Scope rootScope) {
        this.primary = primary;
        this.fallback = fallback;
        this.rootScope = rootScope;
    }

    @Override
    public Dimension propose(ProposalContext context) {
        Dimension proposed = pick(context);
        if (proposed == null) {
            return null;
        }
        // 两个提议器都过这一关：模型和规则版都不许提议"已经试过"或"问题已固定"的维度
        if (context.used().contains(proposed) || context.exhausted().contains(proposed)
                || rootScope.contains(proposed)) {
            return null;
        }
        return proposed;
    }

    private Dimension pick(ProposalContext context) {
        if (primary != null && !degraded) {
            try {
                return primary.propose(context);
            } catch (ProposerUnavailableException e) {
                degraded = true;
                degradeReason = e.getMessage();
            }
        }
        return fallback.propose(context);
    }

    /** 本次运行是否已降级到规则版。 */
    public boolean degraded() {
        return degraded;
    }

    public String degradeReason() {
        return degradeReason;
    }
}
