package com.insight.agent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BudgetedLlmClientTest {

    private static LlmClient answering(int prompt, int completion) {
        return (messages, tools) -> new LlmResponse("", List.of(), prompt, completion);
    }

    @Test
    @DisplayName("正常调用：透传响应并记账")
    void passesThroughAndCharges() {
        TokenBudget budget = new TokenBudget(1000);
        BudgetedLlmClient client = new BudgetedLlmClient(answering(100, 20), budget);

        client.chat(List.of(), List.of());

        assertThat(budget.used()).isEqualTo(120);
    }

    @Test
    @DisplayName("用尽后不再发请求：已经超了还问只会超更多")
    void refusesToCallDelegateWhenExhausted() {
        TokenBudget budget = new TokenBudget(50);
        AtomicInteger calls = new AtomicInteger();
        BudgetedLlmClient client = new BudgetedLlmClient((messages, tools) -> {
            calls.incrementAndGet();
            return new LlmResponse("", List.of(), 100, 0);
        }, budget);

        client.chat(List.of(), List.of());
        assertThat(budget.exhausted()).isTrue();

        assertThatThrownBy(() -> client.chat(List.of(), List.of()))
                .isInstanceOf(TokenBudgetExhausted.class)
                .hasMessageContaining("预算用尽");
        assertThat(calls).hasValue(1);
    }
}
