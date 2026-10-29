package com.insight.agent.attribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.AttributionPorts.Totals;
import com.insight.agent.attribution.ContributionMath.Scored;
import com.insight.agent.attribution.ContributionMath.Split;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 把计算结果写成给业务看的三段式结论，并且**机械校验** facts。
 *
 * <p>为什么不让模型自己保证"不编"：它做不到，而且它说"我保证"没有意义。
 * 所以 facts 里每一条都要能在证据里找到落点——要么提到一个证据里的数字，
 * 要么提到一个证据里的实体名（大区/品类/门店）。找不到的条目降级到 uncertainty，
 * 不删掉，因为"模型想说但没证据"这件事本身值得被看见。
 *
 * <p>hypothesis 不做这个校验：它本来就是推测，硬要求证据会逼模型把猜测写成事实。
 */
public class AttributionReporter {

    private static final String TOOL_NAME = "submit_findings";

    private static final ToolSpec TOOL = new ToolSpec(TOOL_NAME,
            "提交归因结论的三段式表述",
            """
                    {
                      "type": "object",
                      "properties": {
                        "facts": {"type": "array", "items": {"type": "string"},
                                  "description": "只陈述证据里出现的数字和关系"},
                        "hypothesis": {"type": "array", "items": {"type": "string"},
                                       "description": "可能的业务原因，必须明确是推测"},
                        "uncertainty": {"type": "array", "items": {"type": "string"},
                                        "description": "证据不足、无法判断的地方"}
                      },
                      "required": ["facts", "hypothesis", "uncertainty"]
                    }
                    """);

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final String systemPrompt;

    public AttributionReporter(LlmClient llm, ObjectMapper mapper, String systemPrompt) {
        this.llm = llm;
        this.mapper = mapper;
        this.systemPrompt = systemPrompt;
    }

    /**
     * 一层拆分的证据。
     *
     * <p>{@code consistent} 必须跟着走：拆分先落库、后做完备性校验，所以到达这里的分组
     * 可能已经被判定"对不上父节点"。把它当成已验证证据去写结论，正是 DATA_INCOMPLETE
     * 想避免的事。
     */
    public record Level(int depth, Dimension dimension, Scope scope, List<Scored> items,
                        BigDecimal parentDelta, boolean consistent) {

        public static Level of(int depth, Dimension dimension, Scope scope, Split split) {
            return new Level(depth, dimension, scope, split.items(), split.parentDelta(), split.consistent());
        }
    }

    public Findings report(String question, Scope rootScope, Totals root, List<Level> levels,
                           AttributionResult result, boolean degraded) {
        if (result.outcome() == AttributionOutcome.DATA_INCOMPLETE
                || result.outcome() == AttributionOutcome.FAILED) {
            // 数据自己都对不上，再让模型写一段"主要变化来源是……"就是在给错结论包装。
            // 这里不问模型，只把已有数据和缺口如实列出来。
            return incomplete(result, root, levels);
        }
        if (levels.isEmpty()) {
            // 没有任何拆分证据时不要问模型：它只能编。预算耗尽、根口径不显著都属于这种情况
            return deterministic(result, levels, "本次没有产出可用的拆分证据，未生成原因推测");
        }
        String evidence = render(question, rootScope, root, levels, result, degraded);
        try {
            LlmResponse response = llm.chat(
                    List.of(new SystemMessage(systemPrompt), new UserMessage(evidence)),
                    List.of(TOOL));
            Findings raw = parse(response);
            if (raw != null) {
                return verify(raw, root, levels);
            }
        } catch (RuntimeException e) {
            // 模型不可用：降级为纯计算结果，不编造原因
            return deterministic(result, levels, "模型不可用（" + e.getMessage() + "），以下仅为计算结果");
        }
        return deterministic(result, levels, "模型未按格式返回结论，以下仅为计算结果");
    }

    /**
     * 数据不完整时的确定性说明。
     *
     * <p>三段都按"已有 / 缺口 / 不能下什么结论"来写：根口径和每层分组的合计数是已有的，
     * 分组合计与父节点的差额是缺口，主因是明确不能给的。
     */
    private Findings incomplete(AttributionResult result, Totals root, List<Level> levels) {
        List<String> facts = new ArrayList<>();
        facts.add("根口径：本期 " + plain(root.current()) + "，基期 " + plain(root.baseline())
                + "，变化 " + plain(root.delta()));
        for (Level level : levels) {
            BigDecimal sum = level.items().stream()
                    .map(Scored::delta)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gap = sum.subtract(level.parentDelta());
            facts.add("第 " + level.depth() + " 层（" + level.dimension().label() + "）共 "
                    + level.items().size() + " 个分组，变化合计 " + plain(sum)
                    + "，父节点变化 " + plain(level.parentDelta())
                    + "，差额 " + plain(gap)
                    + (level.consistent() ? "（在容差内）" : "（超出容差，不可用于归因）"));
        }

        List<String> uncertainty = new ArrayList<>();
        uncertainty.add("当前分组数据未能完整解释父节点变化，因此无法可靠确定主要变化来源");
        uncertainty.add("上面列出的分组只作为证据保留，不构成完整解释，不能据此认定主因");
        uncertainty.add("需要先补齐缺失分组或核对口径，才能继续归因");
        return new Findings(List.copyOf(facts), List.of(), List.copyOf(uncertainty));
    }

    private Findings parse(LlmResponse response) {
        if (!response.hasToolCalls() || !TOOL_NAME.equals(response.toolCalls().get(0).name())) {
            return null;
        }
        try {
            JsonNode args = mapper.readTree(response.toolCalls().get(0).arguments());
            return new Findings(texts(args.path("facts")), texts(args.path("hypothesis")),
                    texts(args.path("uncertainty")));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> {
            String text = node.asText("");
            if (!text.isBlank()) {
                out.add(text.strip());
            }
        });
        return List.copyOf(out);
    }

    /**
     * facts 逐条校验。判不了的、说反了的、把因果当事实的，都不删——降级并写明原因，
     * 因为"模型想说但没证据"这件事本身值得被看见。
     *
     * <p>判定逻辑在 {@link FactLedger}，这里只负责把三类降级分到正确的段落：
     * 因果表述进 hypothesis（它本来就是推测），其余进 uncertainty（它们是错的或没依据的）。
     */
    private Findings verify(Findings raw, Totals root, List<Level> levels) {
        FactLedger ledger = new FactLedger(root, levels);
        List<String> facts = new ArrayList<>();
        List<String> hypothesis = new ArrayList<>(raw.hypothesis());
        List<String> uncertainty = new ArrayList<>(raw.uncertainty());
        for (String fact : raw.facts()) {
            FactLedger.Check check = ledger.check(fact);
            switch (check.verdict()) {
                case SUPPORTED -> facts.add(fact);
                case CAUSAL -> hypothesis.add("（因果表述，已从 facts 移入推测）" + fact);
                case CONTRADICTED -> uncertainty.add("（" + check.reason() + "，已从 facts 降级）" + fact);
                case UNSUPPORTED -> uncertainty.add("（无证据支撑，已从 facts 降级）" + fact);
            }
        }
        return new Findings(List.copyOf(facts), List.copyOf(hypothesis), List.copyOf(uncertainty));
    }

    private String render(String question, Scope rootScope, Totals root, List<Level> levels,
                          AttributionResult result, boolean degraded) {
        StringBuilder text = new StringBuilder();
        text.append("问题：").append(question).append('\n');
        text.append("根范围：").append(rootScope.filters().isEmpty() ? "全平台" : rootScope.filters())
                .append('\n');
        text.append("根口径：本期 ").append(plain(root.current()))
                .append("，基期 ").append(plain(root.baseline()))
                .append("，变化 ").append(plain(root.delta())).append('\n');
        for (Level level : levels) {
            if (!level.consistent()) {
                continue;
            }
            text.append("第 ").append(level.depth()).append(" 层（").append(level.dimension().name())
                    .append("，范围 ").append(level.scope().filters()).append("）：\n");
            for (Scored item : level.items()) {
                text.append("  ").append(item.value())
                        .append("：本期 ").append(plain(item.current()))
                        .append("，基期 ").append(plain(item.baseline()))
                        .append("，变化 ").append(plain(item.delta()))
                        .append("，贡献 ").append(plain(item.contribution())).append('\n');
            }
        }
        text.append("程序结论：").append(result.outcome().name()).append("，").append(result.message()).append('\n');
        if (degraded) {
            text.append("注意：本次维度提议已降级为规则版（模型不可用）。\n");
        }
        text.append("请调用 ").append(TOOL_NAME).append(" 输出 facts / hypothesis / uncertainty。");
        return text.toString();
    }

    /** 模型不可用时的兜底：只复述算出来的东西，不给原因。 */
    private static Findings deterministic(AttributionResult result, List<Level> levels, String note) {
        List<String> facts = new ArrayList<>();
        facts.add("程序结论：" + result.message());
        if (!levels.isEmpty()) {
            Level last = levels.get(levels.size() - 1);
            for (Scored item : last.items()) {
                facts.add(item.value() + " 变化 " + plain(item.delta()) + "，贡献 " + plain(item.contribution()));
            }
        }
        List<String> uncertainty = new ArrayList<>();
        uncertainty.add(note);
        uncertainty.add("未生成业务原因推测，需要人工判断");
        return new Findings(List.copyOf(facts), List.of(), List.copyOf(uncertainty));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.toPlainString();
    }
}
