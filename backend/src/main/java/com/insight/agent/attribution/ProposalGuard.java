package com.insight.agent.attribution;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 维度提议的准入控制。
 *
 * <p>三种无效：已经拆过的、被标记为无信息量的、模型给不出合法维度的。
 * 无效提议单独计数，且和探针预算分开——一个说明模型不听话，一个说明试得太多。
 */
public class ProposalGuard {

    public enum Verdict { VALID, ALREADY_PROBED, EXHAUSTED }

    private final int failureLimit;

    private final Set<Dimension> probed = new HashSet<>();
    private final Set<Dimension> exhausted = new HashSet<>();
    private final Map<Dimension, Integer> failures = new HashMap<>();

    private int invalidStreak;
    private int invalidTotal;

    public ProposalGuard(int failureLimit) {
        this.failureLimit = failureLimit;
    }

    public Verdict validate(Dimension dimension) {
        if (exhausted.contains(dimension)) {
            return Verdict.EXHAUSTED;
        }
        if (probed.contains(dimension)) {
            return Verdict.ALREADY_PROBED;
        }
        return Verdict.VALID;
    }

    /** 记一次无效提议，返回连续无效次数。 */
    public int recordInvalid() {
        invalidStreak++;
        invalidTotal++;
        return invalidStreak;
    }

    /** 探针成功：维度封存，连续无效计数归零。 */
    public void recordSuccess(Dimension dimension) {
        probed.add(dimension);
        invalidStreak = 0;
    }

    /** 维度没有信息量（只有一个取值）：标记耗尽，并按无效提议计。 */
    public void recordNoInformation(Dimension dimension) {
        exhausted.add(dimension);
        probed.add(dimension);
        invalidStreak++;
        invalidTotal++;
    }

    /**
     * 探针失败（被拒 / 超时 / 空结果）：不增加也不归零连续无效计数。
     * 同一维度累计失败到上限后标记耗尽，之后再提议就算无效。
     */
    public void recordProbeFailure(Dimension dimension) {
        int count = failures.merge(dimension, 1, Integer::sum);
        if (count >= failureLimit) {
            exhausted.add(dimension);
        }
    }

    public int invalidStreak() {
        return invalidStreak;
    }

    public int invalidTotal() {
        return invalidTotal;
    }

    public Set<Dimension> probed() {
        return Set.copyOf(probed);
    }

    /** 已经不能再用、但没进 probed 的维度（同一维度探测连续失败到上限）。 */
    public Set<Dimension> exhausted() {
        return Set.copyOf(exhausted);
    }
}
