package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.insight.agent.attribution.AttributionPorts.ProposalContext;
import com.insight.agent.attribution.AttributionPorts.Scope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 规则版提议器的顺序与跳过条件。 */
class RuleBasedProposerTest {

    private static ProposalContext ctx(List<Dimension> used, Set<Dimension> exhausted) {
        return new ProposalContext(1, used, exhausted, List.of());
    }

    @Test
    @DisplayName("没有约束时按 品类 → 区域 → 门店 的顺序挑第一个")
    void picksInFixedOrder() {
        assertThat(new RuleBasedProposer(Scope.root()).propose(ctx(List.of(), Set.of())))
                .isEqualTo(Dimension.CATEGORY);
    }

    @Test
    @DisplayName("已试过的维度跳过")
    void skipsUsed() {
        assertThat(new RuleBasedProposer(Scope.root())
                .propose(ctx(List.of(Dimension.CATEGORY), Set.of())))
                .isEqualTo(Dimension.REGION);
    }

    @Test
    @DisplayName("探测失败到上限的维度跳过，不会反复提议同一个")
    void skipsExhausted() {
        assertThat(new RuleBasedProposer(Scope.root())
                .propose(ctx(List.of(), Set.of(Dimension.CATEGORY))))
                .isEqualTo(Dimension.REGION);
    }

    @Test
    @DisplayName("问题里已钉死的维度不提议，否则只会拆出一个分组白烧预算")
    void skipsDimensionFixedByQuestion() {
        Scope root = Scope.root().with(Dimension.CATEGORY, "火锅");

        assertThat(new RuleBasedProposer(root).propose(ctx(List.of(), Set.of())))
                .isEqualTo(Dimension.REGION);
    }

    @Test
    @DisplayName("三个维度都不可用时返回 null，交给状态机结束")
    void returnsNullWhenNothingIsLeft() {
        assertThat(new RuleBasedProposer(Scope.root())
                .propose(ctx(List.of(Dimension.CATEGORY), Set.of(Dimension.REGION, Dimension.SHOP))))
                .isNull();
    }
}
