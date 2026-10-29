package com.insight.agent.attribution;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次归因的全局查询预算。
 *
 * <p>为什么不能只靠 {@code maxDepth × maxProbes} 推断：状态机只对"真正参与拆分"的探针计数，
 * 而空结果、被护栏拒绝、scope 名称解析这些同样会打到数据库。用状态机的计数推总数会少算。
 * 这里在**执行前**扣减，所以"实际执行次数 ≤ limit"是结构保证，不是估算。
 *
 * <p>每次归因新建一个实例，不跨请求共享。
 */
public final class QueryBudget {

    private final int limit;
    private final AtomicInteger used = new AtomicInteger();

    public QueryBudget(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("查询预算必须为正数: " + limit);
        }
        this.limit = limit;
    }

    /** 尝试占用一次查询额度。返回 false 表示预算已用尽，调用方不得执行查询。 */
    public boolean tryAcquire() {
        while (true) {
            int current = used.get();
            if (current >= limit) {
                return false;
            }
            if (used.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public int used() {
        return used.get();
    }

    public int limit() {
        return limit;
    }

    public int remaining() {
        return limit - used.get();
    }
}
