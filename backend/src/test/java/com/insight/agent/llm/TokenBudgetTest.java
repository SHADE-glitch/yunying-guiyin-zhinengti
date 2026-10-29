package com.insight.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenBudgetTest {

    @Test
    @DisplayName("按 prompt / completion 分开记，合计等于已用")
    void accumulatesBothSides() {
        TokenBudget budget = new TokenBudget(1000);

        budget.charge(120, 30);
        budget.charge(200, 50);

        assertThat(budget.prompt()).isEqualTo(320);
        assertThat(budget.completion()).isEqualTo(80);
        assertThat(budget.used()).isEqualTo(400);
        assertThat(budget.exhausted()).isFalse();
    }

    @Test
    @DisplayName("用满即算耗尽")
    void exhaustedWhenReachingLimit() {
        TokenBudget budget = new TokenBudget(100);

        budget.charge(100, 0);

        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    @DisplayName("上限为 0 表示不限制，离线演示和单测不该被预算干扰")
    void zeroLimitMeansUnlimited() {
        TokenBudget budget = new TokenBudget(0);

        budget.charge(999_999, 999_999);

        assertThat(budget.exhausted()).isFalse();
    }
}
