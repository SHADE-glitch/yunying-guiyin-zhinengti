package com.insight.agent.attribution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 变化分解的纯计算。不碰数据库、不碰模型，所有分支都能用单测钉住。
 *
 * <p>核心约定：贡献度 c = Δ_子 / |Δ_父|。
 * 分母恒为正，符号只由子项方向决定——c<0 往下拉，c>0 往上推，与父节点涨跌无关。
 * 分组完备时 Σc = sign(Δ_父)，这个性质是完备性校验的依据。
 */
public final class ContributionMath {

    /** 贡献度保留 4 位小数。再多没有业务意义，反而让求和校验更难对齐。 */
    private static final int SCALE = 4;

    private ContributionMath() {
    }

    public record Item(String value, BigDecimal current, BigDecimal baseline) {

        public BigDecimal delta() {
            return current.subtract(baseline);
        }
    }

    public record Scored(String value, BigDecimal current, BigDecimal baseline,
                         BigDecimal delta, BigDecimal contribution) {
    }

    public record Split(BigDecimal parentDelta, List<Scored> items,
                        BigDecimal sumContribution, boolean consistent) {

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /**
     * 按父节点变化拆分。
     *
     * @param parentDelta 父节点的变化量，不能为 0（调用方先用 {@link #isSignificant} 判断）
     * @param tolerance   完备性容差
     */
    public static Split split(List<Item> items, BigDecimal parentDelta, BigDecimal tolerance) {
        if (parentDelta.signum() == 0) {
            throw new IllegalArgumentException("父节点变化为 0 时不能拆分：局部贡献度的分母无意义");
        }
        BigDecimal denominator = parentDelta.abs();
        BigDecimal sum = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

        List<Scored> scored = new ArrayList<>();
        for (Item item : items) {
            BigDecimal delta = item.delta();
            BigDecimal contribution = delta.divide(denominator, SCALE, RoundingMode.HALF_UP);
            scored.add(new Scored(item.value(), item.current(), item.baseline(), delta, contribution));
            sum = sum.add(contribution);
        }

        BigDecimal expected = BigDecimal.valueOf(parentDelta.signum());
        boolean consistent = !scored.isEmpty()
                && sum.subtract(expected).abs().compareTo(tolerance) <= 0;

        return new Split(parentDelta, List.copyOf(scored), sum, consistent);
    }

    /**
     * 选出主要变化来源。
     *
     * <p>必须同向：父节点下跌时，逆势上涨的子项不能当选"跌幅主因"。
     * 并列时取字典序最小，保证同一份数据每次跑出同样的结论。
     */
    public static Optional<Scored> selectDominant(Split split, BigDecimal threshold) {
        if (split == null || split.isEmpty()) {
            return Optional.empty();
        }
        int direction = split.parentDelta().signum();
        return split.items().stream()
                .filter(s -> s.contribution().signum() == direction)
                .filter(s -> s.contribution().abs().compareTo(threshold) >= 0)
                .min(Comparator
                        .comparing((Scored s) -> s.contribution().abs().negate())
                        .thenComparing(Scored::value));
    }

    /** 变化是否显著。基期为 0 时退化为绝对阈值，不做除法。 */
    public static boolean isSignificant(BigDecimal current, BigDecimal baseline,
                                        BigDecimal minRatio, BigDecimal minAbsolute) {
        BigDecimal delta = current.subtract(baseline);
        if (baseline.signum() == 0) {
            return delta.abs().compareTo(minAbsolute) >= 0;
        }
        return delta.abs().divide(baseline.abs(), SCALE, RoundingMode.HALF_UP)
                .compareTo(minRatio) >= 0;
    }

    /** 只有一个取值的维度没有信息量，c 恒为 ±1。 */
    public static boolean hasInformation(List<Item> items) {
        return items != null && items.size() > 1;
    }

    /**
     * 高基数维度折叠：按 |Δ| 降序取前 n，其余合并为"其他"。
     *
     * <p>排序键必须是变化量，不是当前值——要回答"谁造成了变化"，
     * 按绝对值排序才能同时抓住大涨和大跌。
     * "其他"是剩余项的**求和**，不是丢弃，否则完备性校验会失败。
     */
    public static List<Item> foldTopN(List<Item> items, int n, String otherLabel) {
        if (items == null || items.size() <= n) {
            return items == null ? List.of() : List.copyOf(items);
        }
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator
                .comparing((Item i) -> i.delta().abs()).reversed()
                .thenComparing(Item::value));

        List<Item> kept = new ArrayList<>(sorted.subList(0, n));
        BigDecimal otherCurrent = BigDecimal.ZERO;
        BigDecimal otherBaseline = BigDecimal.ZERO;
        for (Item item : sorted.subList(n, sorted.size())) {
            otherCurrent = otherCurrent.add(item.current());
            otherBaseline = otherBaseline.add(item.baseline());
        }
        kept.add(new Item(otherLabel, otherCurrent, otherBaseline));
        return List.copyOf(kept);
    }
}
