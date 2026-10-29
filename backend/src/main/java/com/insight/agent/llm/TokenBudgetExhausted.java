package com.insight.agent.llm;

/**
 * token 预算用尽。
 *
 * <p>继承 RuntimeException 是刻意的：提议器把它当"模型不可用"降级到规则版，
 * 报告器把它当"模型不可用"返回确定性结论——两条既有路径都不用改。
 */
public class TokenBudgetExhausted extends RuntimeException {

    public TokenBudgetExhausted(String message) {
        super(message);
    }
}
