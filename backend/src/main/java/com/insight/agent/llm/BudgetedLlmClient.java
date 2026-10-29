package com.insight.agent.llm;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 给模型调用套一层预算。
 *
 * <p>用完就不再发请求——已经超了还继续问，只会让超支更多。
 * 抛出的 {@link TokenBudgetExhausted} 会被上层的既有降级路径接住：
 * 提议器 → 规则版，报告器 → 确定性结论。所以"超预算"不是故障，是降级。
 */
public final class BudgetedLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final TokenBudget budget;

    public BudgetedLlmClient(LlmClient delegate, TokenBudget budget) {
        this.delegate = delegate;
        this.budget = budget;
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
        if (budget.exhausted()) {
            throw new TokenBudgetExhausted("token 预算用尽（上限 " + budget.limit() + "），停止调用模型");
        }
        LlmResponse response = delegate.chat(messages, tools);
        budget.charge(response.promptTokens(), response.completionTokens());
        return response;
    }

    public TokenBudget budget() {
        return budget;
    }
}
