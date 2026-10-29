package com.insight.agent.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insight.agent.attribution.ContributionMath.Item;
import com.insight.agent.attribution.ContributionMath.Scored;
import com.insight.agent.attribution.ContributionMath.Split;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ContributionMathTest {

    private static final BigDecimal TOL = new BigDecimal("0.05");
    private static final BigDecimal THRESHOLD = new BigDecimal("0.6");

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** 构造一个 delta 为指定值、基期为 1000 的项。 */
    private static Item item(String name, String delta) {
        BigDecimal base = d("1000");
        return new Item(name, base.add(d(delta)), base);
    }

    private static Scored find(Split split, String value) {
        return split.items().stream().filter(s -> s.value().equals(value)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("T1-T4 符号与完备性")
    class SignAndCompleteness {

        @Test
        @DisplayName("T1 总量下跌时，下跌子项贡献为负，Σ=-1")
        void declineYieldsNegativeShares() {
            Split split = ContributionMath.split(
                    List.of(item("A", "-60"), item("B", "-30"), item("C", "-10")),
                    d("-100"), TOL);

            assertThat(find(split, "A").contribution()).isEqualByComparingTo("-0.6");
            assertThat(find(split, "B").contribution()).isEqualByComparingTo("-0.3");
            assertThat(find(split, "C").contribution()).isEqualByComparingTo("-0.1");
            assertThat(split.sumContribution()).isEqualByComparingTo("-1.0");
            assertThat(split.consistent()).isTrue();
        }

        @Test
        @DisplayName("T2 总量上涨时，上涨子项贡献为正，Σ=+1")
        void growthYieldsPositiveShares() {
            Split split = ContributionMath.split(
                    List.of(item("A", "60"), item("B", "40")),
                    d("100"), TOL);

            assertThat(find(split, "A").contribution()).isEqualByComparingTo("0.6");
            assertThat(find(split, "B").contribution()).isEqualByComparingTo("0.4");
            assertThat(split.sumContribution()).isEqualByComparingTo("1.0");
            assertThat(split.consistent()).isTrue();
        }

        @Test
        @DisplayName("T3 子项跌幅超过总量跌幅时 |c|>1，不截断")
        void shareCanExceedOne() {
            Split split = ContributionMath.split(
                    List.of(item("A", "-60"), item("B", "50")),
                    d("-10"), TOL);

            assertThat(find(split, "A").contribution()).isEqualByComparingTo("-6.0");
            assertThat(find(split, "B").contribution()).isEqualByComparingTo("5.0");
            assertThat(split.sumContribution()).isEqualByComparingTo("-1.0");
            assertThat(split.consistent()).isTrue();
        }

        @Test
        @DisplayName("T4 分组只覆盖 80% 时判为不完备")
        void incompletePartitionIsFlagged() {
            Split split = ContributionMath.split(
                    List.of(item("A", "-60"), item("B", "-20")),
                    d("-100"), TOL);

            assertThat(split.sumContribution()).isEqualByComparingTo("-0.8");
            assertThat(split.consistent()).isFalse();
        }
    }

    @Nested
    @DisplayName("T5-T6 T10 候选选择")
    class Selection {

        @Test
        @DisplayName("T5 最大同向子项低于阈值时不选主因")
        void belowThresholdYieldsNothing() {
            Split split = ContributionMath.split(
                    List.of(item("A", "-55"), item("B", "-45")), d("-100"), TOL);

            assertThat(ContributionMath.selectDominant(split, THRESHOLD)).isEmpty();
        }

        @Test
        @DisplayName("T6 逆势上涨的子项不能当选跌幅主因")
        void oppositeDirectionIsNeverSelected() {
            Split split = ContributionMath.split(
                    List.of(item("UP", "90"), item("DOWN", "-190")), d("-100"), TOL);

            assertThat(find(split, "UP").contribution()).isEqualByComparingTo("0.9");
            Optional<Scored> dominant = ContributionMath.selectDominant(split, THRESHOLD);
            assertThat(dominant).isPresent();
            assertThat(dominant.get().value()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("T10 贡献度完全并列时按字典序取，结果可复现")
        void tieBreaksDeterministically() {
            Split split = ContributionMath.split(
                    List.of(item("B", "-50"), item("A", "-50")), d("-100"), TOL);

            // |c| = 0.5 低于默认阈值 0.6，这里用 0.4 单独验证并列时的排序规则
            assertThat(ContributionMath.selectDominant(split, new BigDecimal("0.4")))
                    .map(Scored::value).contains("A");
        }
    }

    @Nested
    @DisplayName("T7-T9 显著性与信息量")
    class Significance {

        @Test
        @DisplayName("T7 变化低于 3% 视为不显著")
        void smallChangeIsNotSignificant() {
            assertThat(ContributionMath.isSignificant(
                    d("10050"), d("10000"), d("0.03"), d("100"))).isFalse();
            // +2% 不显著，+3% 才算
            assertThat(ContributionMath.isSignificant(
                    d("10200"), d("10000"), d("0.03"), d("100"))).isFalse();
            assertThat(ContributionMath.isSignificant(
                    d("10300"), d("10000"), d("0.03"), d("100"))).isTrue();
        }

        @Test
        @DisplayName("T8 基期为 0 时走绝对阈值")
        void zeroBaselineUsesAbsoluteThreshold() {
            assertThat(ContributionMath.isSignificant(d("50"), d("0"), d("0.03"), d("100"))).isFalse();
            assertThat(ContributionMath.isSignificant(d("150"), d("0"), d("0.03"), d("100"))).isTrue();
        }

        @Test
        @DisplayName("T9 单值维度没有信息量")
        void singleValueHasNoInformation() {
            assertThat(ContributionMath.hasInformation(List.of(item("only", "-10")))).isFalse();
            assertThat(ContributionMath.hasInformation(List.of(item("a", "-10"), item("b", "5")))).isTrue();
        }

        @Test
        @DisplayName("父节点变化为 0 时拒绝拆分，不做除零")
        void zeroParentDeltaIsRejected() {
            assertThatThrownBy(() -> ContributionMath.split(List.of(item("A", "-1")), d("0"), TOL))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("T17-T20 局部贡献度（相对父节点）")
    class LocalContribution {

        @Test
        @DisplayName("T17 第二层用父节点变化作分母")
        void secondLevelUsesParentDelta() {
            Split split = ContributionMath.split(
                    List.of(item("火锅", "-45"), item("烧烤", "10"), item("咖啡", "-65")),
                    d("-100"), TOL);

            assertThat(find(split, "火锅").contribution()).isEqualByComparingTo("-0.45");
            assertThat(find(split, "烧烤").contribution()).isEqualByComparingTo("0.10");
            assertThat(find(split, "咖啡").contribution()).isEqualByComparingTo("-0.65");
            assertThat(split.sumContribution()).isEqualByComparingTo("-1.0");
        }

        @Test
        @DisplayName("T18 父节点变化不足 3% 时停止下钻")
        void flatParentStopsDrilling() {
            assertThat(ContributionMath.isSignificant(d("998"), d("1000"), d("0.03"), d("100"))).isFalse();
        }

        @Test
        @DisplayName("T19 局部贡献度同样允许 |c|>1")
        void localShareCanExceedOne() {
            Split split = ContributionMath.split(
                    List.of(item("A", "-45"), item("B", "35")), d("-10"), TOL);

            assertThat(find(split, "A").contribution()).isEqualByComparingTo("-4.5");
            assertThat(find(split, "B").contribution()).isEqualByComparingTo("3.5");
            assertThat(split.consistent()).isTrue();
        }

        @Test
        @DisplayName("T20 同一子项在不同父节点下贡献度不同，证明没有混用全局分母")
        void sameChildDiffersByParent() {
            Item child = item("火锅", "-45");

            Scored underRoot = ContributionMath.split(List.of(child), d("-100"), TOL).items().get(0);
            Scored underParent = ContributionMath.split(List.of(child), d("-60"), TOL).items().get(0);

            assertThat(underRoot.contribution()).isEqualByComparingTo("-0.45");
            assertThat(underParent.contribution()).isEqualByComparingTo("-0.75");
        }
    }

    @Nested
    @DisplayName("T21-T22 高基数折叠")
    class HighCardinality {

        private List<Item> shops(int count) {
            List<Item> items = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                // 第 i 个门店的跌幅为 -(i * 10)，保证 |Δ| 严格递减
                items.add(item("shop" + i, String.valueOf(-i * 10)));
            }
            return items;
        }

        @Test
        @DisplayName("T21 取 Top-10 后剩余合并为其他，且合计仍完备")
        void foldKeepsCompleteness() {
            List<Item> folded = ContributionMath.foldTopN(shops(13), 10, "其他");

            assertThat(folded).hasSize(11);
            Item other = folded.stream().filter(i -> i.value().equals("其他")).findFirst().orElseThrow();
            // 按 |Δ| 降序保留变化最大的 10 个（shop13 到 shop4），
            // 剩下的是变化最小的 shop3 + shop2 + shop1 = -30 -20 -10
            assertThat(other.delta()).isEqualByComparingTo("-60");

            BigDecimal totalDelta = folded.stream().map(Item::delta).reduce(BigDecimal.ZERO, BigDecimal::add);
            Split split = ContributionMath.split(folded, totalDelta, TOL);
            assertThat(split.consistent()).isTrue();
        }

        @Test
        @DisplayName("T22 其他成为最大贡献项时，结论只能停在集合层面")
        void otherCanBeDominant() {
            // 一个大幅变化 + 100 个微小变化：Top-10 会保留那个大项和 9 个小项，
            // 剩余 91 个小项之和反而成为最大贡献来源。这是"其他"能当选的唯一形态。
            List<Item> items = new ArrayList<>();
            items.add(item("big", "-100"));
            for (int i = 1; i <= 100; i++) {
                items.add(item("s" + i, "-5"));
            }

            List<Item> folded = ContributionMath.foldTopN(items, 10, "其他");
            BigDecimal totalDelta = folded.stream().map(Item::delta).reduce(BigDecimal.ZERO, BigDecimal::add);
            Split split = ContributionMath.split(folded, totalDelta, TOL);

            assertThat(ContributionMath.selectDominant(split, THRESHOLD))
                    .map(Scored::value).contains("其他");
        }
    }
}
