package com.insight.agent.attribution;

/**
 * 查询预算用尽。
 *
 * <p>单独一个异常而不是返回 FAILED：预算是全局硬约束，不该被当成"这个维度试不通"。
 * 状态机收到它就终止整个归因，而不是换个维度继续试。
 */
public class QueryBudgetExhausted extends RuntimeException {

    public QueryBudgetExhausted(String message) {
        super(message);
    }
}
