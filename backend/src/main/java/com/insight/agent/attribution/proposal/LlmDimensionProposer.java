package com.insight.agent.attribution.proposal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.attribution.AttributionPorts.DimensionProposer;
import com.insight.agent.attribution.AttributionPorts.ProposalContext;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.Dimension;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import com.insight.agent.llm.ToolSpec;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 让模型提议下一个拆哪个维度。
 *
 * <p>用工具调用而不是解析自由文本：维度是一个三值枚举，工具 schema 里的 enum 就是
 * 结构化输出，模型没法"自由发挥"出一个没有数据的维度。返回 null 表示模型没给出合法值，
 * 交给 ProposalGuard 计数——那是 prompt 的问题，不是可用性问题。
 */
public class LlmDimensionProposer implements DimensionProposer {

    private static final String TOOL_NAME = "propose_dimension";

    private static final String TOOL_DESCRIPTION =
            "决定下一步按哪个维度拆分当前范围。只能选一个，不能重复已用过的维度。";

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final String systemPrompt;
    private final String question;
    private final Scope rootScope;

    public LlmDimensionProposer(LlmClient llm, ObjectMapper mapper, String systemPrompt,
                                String question, Scope rootScope) {
        this.llm = llm;
        this.mapper = mapper;
        this.systemPrompt = systemPrompt;
        this.question = question;
        this.rootScope = rootScope;
    }

    @Override
    public Dimension propose(ProposalContext context) {
        Set<Dimension> allowed = allowed(context);
        if (allowed.isEmpty()) {
            // 三个维度全废了，问模型也只是让它从枚举里挑一个废的
            return null;
        }
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(describe(context, allowed)));

        LlmResponse response;
        try {
            response = llm.chat(messages, List.of(tool(allowed)));
        } catch (RuntimeException e) {
            throw new ProposerUnavailableException("模型提议失败: " + e.getMessage(), e);
        }

        if (!response.hasToolCalls()) {
            return null;
        }
        ToolCallReq call = response.toolCalls().get(0);
        if (!TOOL_NAME.equals(call.name())) {
            return null;
        }
        try {
            JsonNode args = mapper.readTree(call.arguments());
            String value = args.path("dimension").asText("");
            if (value.isBlank()) {
                return null;
            }
            Dimension dimension = Dimension.valueOf(value);
            // schema 已经只给合法值，这里再挡一次：模型仍可能照旧回一个废维度
            return allowed.contains(dimension) ? dimension : null;
        } catch (Exception e) {
            // 模型给了枚举外的值或坏 JSON：算无效提议，不猜
            return null;
        }
    }

    private String describe(ProposalContext context, Set<Dimension> allowed) {
        StringBuilder text = new StringBuilder();
        text.append("问题：").append(question).append('\n');
        text.append("根范围：").append(rootScope.filters().isEmpty() ? "全平台" : render(rootScope)).append('\n');
        text.append("已下钻路径：")
                .append(context.path().isEmpty() ? "无" : String.join(" → ", context.path())).append('\n');
        text.append("已试过的维度：")
                .append(context.used().isEmpty() ? "无" : context.used().toString()).append('\n');
        if (!context.exhausted().isEmpty()) {
            text.append("不可再选的维度（多次探测失败）：").append(context.exhausted()).append('\n');
        }
        text.append("当前深度：").append(context.depth()).append('\n');
        text.append("可选维度：").append(allowed).append('\n');
        text.append("请调用 ").append(TOOL_NAME).append(" 选出下一步要拆的维度。");
        return text.toString();
    }

    /**
     * 把已用过和已废掉的维度从枚举里剔掉，再交给模型。
     *
     * <p>不这么做的话，模型只能从旧枚举里挑，挑中废维度就变成一次无效提议；
     * 连续三次就把整次归因熔断，而真正的问题只是"这个维度试过了"。
     */
    private static Set<Dimension> allowed(ProposalContext context) {
        Set<Dimension> allowed = EnumSet.allOf(Dimension.class);
        allowed.removeAll(context.used());
        allowed.removeAll(context.exhausted());
        return allowed;
    }

    /** 维度枚举在 schema 里动态生成，避免给模型一个它不该选的选项。 */
    private static ToolSpec tool(Set<Dimension> allowed) {
        String values = allowed.stream().map(d -> "\"" + d.name() + "\"").collect(Collectors.joining(", "));
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "dimension": {"type": "string", "enum": [%s],
                                  "description": "下一步要拆分的维度"},
                    "reason": {"type": "string", "description": "一句话说明为什么先拆这个维度"}
                  },
                  "required": ["dimension"]
                }
                """.formatted(values);
        return new ToolSpec(TOOL_NAME, TOOL_DESCRIPTION, schema);
    }

    private static String render(Scope scope) {
        StringBuilder text = new StringBuilder();
        scope.filters().forEach((dimension, value) ->
                text.append(text.isEmpty() ? "" : "、").append(dimension.name()).append('=').append(value));
        return text.toString();
    }
}
