package com.insight.agent.llm;

/**
 * 一次运行的 token 预算。
 *
 * <p>和查询预算同构：先记账、后判断，超了就停，不靠"大概估一下"。
 * 一次归因是单线程跑的，所以这里不加同步——加锁只会掩盖调用方并发使用的问题。
 */
public final class TokenBudget {

    private final int limit;
    private int prompt;
    private int completion;

    public TokenBudget(int limit) {
        this.limit = limit;
    }

    public void charge(int promptTokens, int completionTokens) {
        this.prompt += Math.max(0, promptTokens);
        this.completion += Math.max(0, completionTokens);
    }

    public int prompt() {
        return prompt;
    }

    public int completion() {
        return completion;
    }

    public int used() {
        return prompt + completion;
    }

    public int limit() {
        return limit;
    }

    /** 上限 <= 0 视为不限制：离线演示和单测里不想被预算干扰。 */
    public boolean exhausted() {
        return limit > 0 && used() >= limit;
    }
}
