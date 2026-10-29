package com.insight.agent.attribution.sql;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 归因的两个对比窗口：本期与紧邻的等长基期。
 *
 * <p>整个归因过程只算一次，所有探针共用同一组窗口——否则根节点和子节点
 * 比的是不同区间，完备性校验会莫名其妙地失败。
 */
public record QueryWindow(Instant currentStart, Instant currentEnd,
                          Instant baselineStart, Instant baselineEnd) {

    public static QueryWindow of(Instant asOf, int days) {
        if (days <= 0) {
            throw new IllegalArgumentException("窗口长度必须为正数: " + days);
        }
        Instant anchor = asOf == null ? Instant.now() : asOf;
        Instant currentStart = anchor.minus(days, ChronoUnit.DAYS);
        Instant baselineStart = anchor.minus(2L * days, ChronoUnit.DAYS);
        return new QueryWindow(currentStart, anchor, baselineStart, currentStart);
    }
}
