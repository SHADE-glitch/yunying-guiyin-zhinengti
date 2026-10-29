package com.insight.agent.attribution.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.insight.agent.attribution.AttributionPorts.DimensionProposer;
import com.insight.agent.attribution.AttributionPorts.ProposalContext;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.attribution.Dimension;
import com.insight.agent.attribution.RuleBasedProposer;
import com.insight.agent.llm.BudgetedLlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.TokenBudget;
import com.insight.agent.llm.ToolCallReq;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 降级策略的边界：什么情况回退、什么情况不回退、回退后还能不能乱提议。
 */
class FallbackDimensionProposerTest {

    private static final ProposalContext FIRST = new ProposalContext(1, List.of(), Set.of(), List.of());

    @Test
    @DisplayName("模型可用：直接用模型的结果，不碰规则版")
    void usesModelWhenAvailable() {
        List<String> calls = new ArrayList<>();
        FallbackDimensionProposer proposer = new FallbackDimensionProposer(
                context -> {
                    calls.add("model");
                    return Dimension.REGION;
                },
                context -> {
                    calls.add("rules");
                    return Dimension.CATEGORY;
                },
                Scope.root());

        assertThat(proposer.propose(FIRST)).isEqualTo(Dimension.REGION);
        assertThat(calls).containsExactly("model");
        assertThat(proposer.degraded()).isFalse();
    }

    @Test
    @DisplayName("模型不可用：立刻降级，且本次运行后续不再调模型")
    void degradesOnceAndStaysDegraded() {
        List<String> calls = new ArrayList<>();
        FallbackDimensionProposer proposer = new FallbackDimensionProposer(
                context -> {
                    calls.add("model");
                    throw new ProposerUnavailableException("timeout", null);
                },
                context -> {
                    calls.add("rules");
                    return Dimension.CATEGORY;
                },
                Scope.root());

        assertThat(proposer.propose(FIRST)).isEqualTo(Dimension.CATEGORY);
        assertThat(proposer.propose(FIRST)).isEqualTo(Dimension.CATEGORY);

        assertThat(proposer.degraded()).isTrue();
        assertThat(proposer.degradeReason()).contains("timeout");
        assertThat(calls).containsExactly("model", "rules", "rules");
    }

    @Test
    @DisplayName("模型给了非法值：不回退，交给 ProposalGuard 计数（这是 prompt 问题，藏起来就改不掉）")
    void invalidProposalDoesNotFallBack() {
        FallbackDimensionProposer proposer = new FallbackDimensionProposer(
                context -> null,
                context -> Dimension.CATEGORY,
                Scope.root());

        assertThat(proposer.propose(FIRST)).isNull();
        assertThat(proposer.degraded()).isFalse();
    }

    @Test
    @DisplayName("已试过的维度：两个提议器都不许再提，统一返回 null")
    void rejectsAlreadyProbedDimension() {
        ProposalContext used = new ProposalContext(2, List.of(Dimension.CATEGORY), Set.of(), List.of("CATEGORY=火锅"));
        FallbackDimensionProposer proposer = new FallbackDimensionProposer(
                context -> Dimension.CATEGORY,
                context -> Dimension.CATEGORY,
                Scope.root());

        assertThat(proposer.propose(used)).isNull();
    }

    @Test
    @DisplayName("问题里已固定的维度：不许提议，否则只会拆出一个分组白烧预算")
    void rejectsDimensionFixedByQuestion() {
        Scope root = Scope.root().with(Dimension.REGION, "华东");
        FallbackDimensionProposer proposer = new FallbackDimensionProposer(
                context -> Dimension.REGION,
                context -> Dimension.REGION,
                root);

        assertThat(proposer.propose(FIRST)).isNull();
    }

    @Test
    @DisplayName("token 预算耗尽：提议器降级到规则版，不把异常抛给状态机")
    void tokenBudgetExhaustionFallsBackToRules() {
        TokenBudget budget = new TokenBudget(10);
        LlmDimensionProposer model = new LlmDimensionProposer(
                new BudgetedLlmClient((messages, tools) -> new LlmResponse("", List.of(
                        new ToolCallReq("c1", "propose_dimension", "{\"dimension\":\"REGION\"}")), 100, 0),
                        budget),
                new ObjectMapper(), "sys", "为什么跌了", Scope.root());
        FallbackDimensionProposer proposer = new FallbackDimensionProposer(
                model, new RuleBasedProposer(Scope.root()), Scope.root());

        // 第一次还来得及问模型；第二次预算已用尽，必须走规则版
        assertThat(proposer.propose(FIRST)).isEqualTo(Dimension.REGION);
        assertThat(proposer.propose(FIRST)).isEqualTo(Dimension.CATEGORY);
        assertThat(proposer.degraded()).isTrue();
    }

    @Test
    @DisplayName("探测失败到上限的维度：包装层也不许再放行")
    void rejectsExhaustedDimension() {
        ProposalContext exhausted = new ProposalContext(2, List.of(), Set.of(Dimension.REGION), List.of());
        FallbackDimensionProposer proposer = new FallbackDimensionProposer(
                context -> Dimension.REGION,
                context -> Dimension.REGION,
                Scope.root());

        assertThat(proposer.propose(exhausted)).isNull();
    }

    @Test
    @DisplayName("没配模型：全程规则版，不算降级")
    void worksWithoutModel() {
        FallbackDimensionProposer proposer = new FallbackDimensionProposer(
                null, context -> Dimension.SHOP, Scope.root());

        assertThat(proposer.propose(FIRST)).isEqualTo(Dimension.SHOP);
        assertThat(proposer.degraded()).isFalse();
    }
}
