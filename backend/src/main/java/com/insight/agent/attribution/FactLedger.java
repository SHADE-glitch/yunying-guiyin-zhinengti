package com.insight.agent.attribution;

import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Scored;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * facts 的机械校验器：只用结构化证据说话。
 *
 * <p>能机械判定的只有三类，全部来自算出来的数，不靠"读起来像不像"：
 *
 * <ol>
 *   <li>数字和实体在不在证据里；</li>
 *   <li>方向对不对——主体跌了却写"上涨"；</li>
 *   <li>数值有没有放错位置——本期写成基期、子项写成整体。</li>
 * </ol>
 *
 * <p>因果解释单独一类：它本来就不该出现在 facts 里，出现即视为推测。这里只认真正的因果
 * 连接词，"原因尚不能确定"这种没有下因果断言的句子不触发。
 *
 * <p>做不到的必须说清楚：一句话只要数字和实体都对得上，语义仍可能是错的。例如"整体变化
 * 8845.46"如果 8845.46 是子项的变化，这里只有带上"本期/基期"这种结构才拦得住；不带就漏。
 * 所以这个类的目标是"不让能被证据否定的句子进 facts"，而不是"证明 facts 都对"——
 * 判不了的降级为不确定，不假装验证过。
 */
final class FactLedger {

    enum Verdict { SUPPORTED, UNSUPPORTED, CONTRADICTED, CAUSAL }

    record Check(Verdict verdict, String reason) {
        static final Check SUPPORTED = new Check(Verdict.SUPPORTED, "");
        static final Check UNSUPPORTED = new Check(Verdict.UNSUPPORTED, "");
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?%?");

    private static final Pattern DIRECTION =
            Pattern.compile("上涨|上升|增长|增加|提升|走高|回升|下跌|下降|减少|下滑|降低|回落");
    private static final Pattern UP = Pattern.compile("上涨|上升|增长|增加|提升|走高|回升");

    /** 真正的因果连接词。"原因""解释""为什么"不算——它们不构成因果断言。 */
    private static final Pattern CAUSAL = Pattern.compile("因为|由于|导致|造成|所以|因此|归因于|源于|引发|致使");

    /** 本期/基期 + 中间一小段 + 数字；中间那段要检查，防止把"本期下滑 47.3%"当成绝对值。 */
    private static final Pattern ROLE = Pattern.compile("(本期|基期)([^，。；\\n]{0,10}?)(-?\\d[\\d,]*(?:\\.\\d+)?%?)");

    private final Totals root;
    private final Map<String, Scored> items = new LinkedHashMap<>();
    private final Set<String> numbers = new HashSet<>();
    private final Set<String> parentNumbers = new HashSet<>();
    private final Set<String> childNumbers = new HashSet<>();

    FactLedger(Totals root, List<AttributionReporter.Level> levels) {
        this.root = root;
        collect(root.current(), root.baseline(), root.delta(), null, parentNumbers);
        numbers.addAll(parentNumbers);
        for (AttributionReporter.Level level : levels) {
            for (Scored item : level.items()) {
                items.putIfAbsent(item.value(), item);
                collect(item.current(), item.baseline(), item.delta(), item.contribution(), childNumbers);
            }
        }
        numbers.addAll(childNumbers);
    }

    Check check(String fact) {
        if (CAUSAL.matcher(fact).find()) {
            return new Check(Verdict.CAUSAL, "含因果表述");
        }
        Check direction = direction(fact);
        if (direction != null) {
            return direction;
        }
        Check role = role(fact);
        if (role != null) {
            return role;
        }
        return grounded(fact) ? Check.SUPPORTED : Check.UNSUPPORTED;
    }

    /** 方向词前面的最近实体就是主语；窗口里没有实体就不判，宁可漏判也不乱扣。 */
    private Check direction(String fact) {
        Matcher matcher = DIRECTION.matcher(fact);
        while (matcher.find()) {
            boolean up = UP.matcher(matcher.group()).matches();
            Scored subject = nearest(fact.substring(Math.max(0, matcher.start() - 12), matcher.start()));
            if (subject == null) {
                continue;
            }
            int sign = subject.delta().signum();
            if (sign == 0) {
                return new Check(Verdict.CONTRADICTED, "证据显示 " + subject.value() + " 变化为 0，与方向表述不符");
            }
            if ((sign > 0) != up) {
                return new Check(Verdict.CONTRADICTED, "方向与证据相反");
            }
        }
        return null;
    }

    private Scored nearest(String window) {
        Scored nearest = null;
        int best = -1;
        for (Map.Entry<String, Scored> entry : items.entrySet()) {
            int at = window.lastIndexOf(entry.getKey());
            if (at > best) {
                best = at;
                nearest = entry.getValue();
            }
        }
        return nearest;
    }

    /**
     * 本期/基期的数值有没有放错位置。
     *
     * <p>一句话里出现多个主体时直接不判：判不准就不判，比误降级更糟的是让读者以为校验过了。
     */
    private Check role(String fact) {
        BigDecimal current = roleNumber(fact, "本期");
        BigDecimal baseline = roleNumber(fact, "基期");
        if (current == null && baseline == null) {
            return null;
        }
        List<Scored> mentioned = mentioned(fact);
        if (mentioned.size() > 1) {
            return null;
        }
        if (mentioned.size() == 1) {
            Scored subject = mentioned.get(0);
            if (current != null && baseline != null
                    && same(current, subject.baseline()) && same(baseline, subject.current())
                    && !same(subject.current(), subject.baseline())) {
                return new Check(Verdict.CONTRADICTED, "本期与基期数值写反");
            }
            if (current != null && !same(current, subject.current())) {
                return new Check(Verdict.CONTRADICTED, "本期数值与证据不符");
            }
            if (baseline != null && !same(baseline, subject.baseline())) {
                return new Check(Verdict.CONTRADICTED, "基期数值与证据不符");
            }
            return null;
        }
        // 没提子项：这是根口径的句子，最容易被写成"整体"
        if (current != null && !same(current, root.current())) {
            return new Check(Verdict.CONTRADICTED,
                    childOnly(current) ? "把子项数值当成了整体数值" : "本期数值与根口径不符");
        }
        if (baseline != null && !same(baseline, root.baseline())) {
            return new Check(Verdict.CONTRADICTED,
                    childOnly(baseline) ? "把子项数值当成了整体数值" : "基期数值与根口径不符");
        }
        return null;
    }

    /** 取"本期/基期"后面那个绝对值；带百分号或中间夹着方向词的，说明是相对表述，不当作口径值。 */
    private static BigDecimal roleNumber(String fact, String label) {
        Matcher matcher = ROLE.matcher(fact);
        while (matcher.find()) {
            if (!label.equals(matcher.group(1))) {
                continue;
            }
            String gap = matcher.group(2);
            String token = matcher.group(3);
            if (token.endsWith("%") || DIRECTION.matcher(gap).find()) {
                return null;
            }
            return new BigDecimal(token.replace(",", ""));
        }
        return null;
    }

    private List<Scored> mentioned(String fact) {
        List<Scored> out = new ArrayList<>();
        for (Map.Entry<String, Scored> entry : items.entrySet()) {
            if (!entry.getKey().isBlank() && fact.contains(entry.getKey())) {
                out.add(entry.getValue());
            }
        }
        return out;
    }

    private boolean childOnly(BigDecimal value) {
        String token = normalize(value.toPlainString());
        return childNumbers.contains(token) && !parentNumbers.contains(token);
    }

    private boolean grounded(String fact) {
        for (String entity : items.keySet()) {
            if (!entity.isBlank() && fact.contains(entity)) {
                return true;
            }
        }
        Matcher matcher = NUMBER.matcher(fact);
        while (matcher.find()) {
            if (numbers.contains(normalize(matcher.group()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return false;
        }
        return left.setScale(2, RoundingMode.HALF_UP).compareTo(right.setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    private static void collect(BigDecimal current, BigDecimal baseline, BigDecimal delta,
                                BigDecimal contribution, Set<String> into) {
        add(into, current);
        add(into, baseline);
        add(into, delta);
        add(into, contribution);
        if (baseline != null && baseline.signum() != 0 && delta != null) {
            // 模型会说"下降 47%"，这个数不在证据里但是算得出来的，也要认
            add(into, delta.multiply(BigDecimal.valueOf(100))
                    .divide(baseline.abs(), 1, RoundingMode.HALF_UP));
        }
    }

    private static void add(Set<String> into, BigDecimal value) {
        if (value == null) {
            return;
        }
        into.add(normalize(value.toPlainString()));
        into.add(normalize(value.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        into.add(normalize(value.abs().toPlainString()));
        into.add(normalize(value.abs().setScale(2, RoundingMode.HALF_UP).toPlainString()));
    }

    private static String normalize(String token) {
        String cleaned = token.replace(",", "").replace("%", "");
        if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1);
        }
        // 去掉多余的尾零：-0.9762 与 -0.97620 视为同一个数
        if (cleaned.contains(".")) {
            cleaned = cleaned.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return cleaned;
    }
}
