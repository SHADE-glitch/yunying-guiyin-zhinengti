package com.insight.agent.attribution;

import com.insight.agent.attribution.AttributionPorts.ProbeResult;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Scored;
import com.insight.agent.attribution.ContributionMath.Split;

/**
 * 状态机的观察者。只读，不能改变判定结果——所有方法都是默认空实现，
 * 状态机不知道有没有人在听。
 *
 * <p>分成三段是因为持久化和 SSE 要的东西不同：落库要根节点、每次探测、每次拆分；
 * 前端要的是"现在查到哪了"。两边都从这里取，不各写一套。
 */
public interface AttributionListener {

    AttributionListener NOOP = new AttributionListener() {
    };

    /** 根口径查完，可能是 NO_SIGNIFICANT_CHANGE 之前的最后一次回调。 */
    default void onBaseline(Totals root) {
    }

    /** 每次探测都会回调，包括空结果、被拒、超时——排查"为什么没结论"要靠它。 */
    default void onProbe(int depth, Dimension dimension, Scope scope, ProbeResult result) {
    }

    /**
     * 拆分完成，无论有没有选出主因。{@code selected} 为空表示这一层没有单项达到阈值
     * （MULTI_FACTOR），或数据不完整——两种情况候选都要留证据。
     */
    default void onSplit(int depth, Dimension dimension, Scope scope, Split split, Scored selected,
                         Evidence evidence) {
    }

    /** 归因结束，无论结局。 */
    default void onFinish(AttributionResult result) {
    }
}
