package com.insight.agent.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import com.insight.agent.llm.ToolSpec;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 入口守卫：先判断"这是不是一个本地生活运营问题"，再决定要不要进 Agent 循环。
 *
 * <p>先过业务词表（零成本，覆盖绝大多数真实提问）；没命中才花一次小调用，
 * 让模型只能返回一个三值枚举——和 propose_dimension 同一个模式：把模型的输出空间压成枚举。
 *
 * <p>模型不可用时放行（fail-open）：入口守卫是体验与成本的优化，不是最后一道安全边界。
 * 误拒一个正常问题的代价比放过一个越界问题更高——后者还有 SqlGuard 与只读账号兜着。
 */
@Component
public class OutOfScopeGuard {

    public enum Intent { BUSINESS, OUT_OF_SCOPE, CLARIFY }

    public record Verdict(Intent intent, String reason) {

        public boolean allowed() {
            return intent == Intent.BUSINESS;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OutOfScopeGuard.class);

    private static final String TOOL_NAME = "classify_intent";

    /** 业务名词表：指标、维度、实体、口径类概念。只收名词（含业务动词），不收"为什么/分析"这类通用词。 */
    private static final List<String> BUSINESS_WORDS = List.of(
            "gmv", "销售额", "成交额", "交易额", "订单", "单量", "客单价", "退款", "核销", "复购",
            "新客", "转化", "券", "优惠", "履约", "折扣", "动销", "毛利", "收入", "流水",
            "区域", "大区", "城市", "门店", "商户", "品类", "商家", "店铺",
            "华东", "华南", "华北", "华中", "西南", "火锅", "咖啡", "电影", "ktv", "早教",
            "美甲", "烧烤", "桌游", "美发", "儿童乐园", "生鲜", "超市",
            "口径", "指标", "归因", "环比", "同比", "涨跌", "涨了", "跌了", "下降", "上升", "变化",
            "原因", "贡献", "趋势", "异常", "多少", "怎么算", "统计", "排名");

    private static final String SYSTEM_PROMPT = """
            你是问题入口分类器。只判断问题是否属于本地生活运营数据分析范围：
            指标（GMV/订单/退款/券等）、维度（区域/品类/门店/商户）、数据口径与规则。
            你只调用 classify_intent，不要回答原问题本身。""";

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public OutOfScopeGuard(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    /** 拒绝时给用户看的话：越界给两个示例问法，说不清就让补范围。 */
    public static String rejectMessage(Intent intent) {
        return switch (intent) {
            case OUT_OF_SCOPE -> "我只回答本地生活运营数据问题。可以试试：「为什么华东 GMV 跌了」「退款率怎么算」。";
            case CLARIFY -> "这个问题我没把握，请补充指标或范围，例如「华东 GMV 为什么跌了」。";
            case BUSINESS -> "";
        };
    }

    public Verdict classify(String question) {
        if (question == null || question.isBlank()) {
            return new Verdict(Intent.CLARIFY, "问题为空");
        }
        String text = question.toLowerCase(Locale.ROOT);
        for (String word : BUSINESS_WORDS) {
            if (text.contains(word)) {
                return new Verdict(Intent.BUSINESS, null);
            }
        }
        return classifyByModel(question);
    }

    /** 词表没命中：一次小调用，模型只能从三个值里选一个。任何异常都放行。 */
    private Verdict classifyByModel(String question) {
        try {
            List<Message> messages = List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage("问题：" + question + "\n请调用 " + TOOL_NAME + "。"));
            LlmResponse response = llm.chat(messages, List.of(tool()));
            if (!response.hasToolCalls()) {
                return new Verdict(Intent.BUSINESS, null);
            }
            ToolCallReq call = response.toolCalls().get(0);
            if (!TOOL_NAME.equals(call.name())) {
                return new Verdict(Intent.BUSINESS, null);
            }
            JsonNode args = mapper.readTree(call.arguments());
            Intent intent = Intent.valueOf(args.path("intent").asText("BUSINESS"));
            return new Verdict(intent, args.path("reason").asText(null));
        } catch (Exception e) {
            // fail-open：分类器不可用不能变成功能不可用
            log.warn("入口分类器不可用，按业务问题放行: {}", e.getMessage());
            return new Verdict(Intent.BUSINESS, null);
        }
    }

    private static ToolSpec tool() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "intent": {"type": "string", "enum": ["BUSINESS", "OUT_OF_SCOPE", "CLARIFY"],
                               "description": "BUSINESS=本地生活运营问题；OUT_OF_SCOPE=明显无关；CLARIFY=可能相关但说不清"},
                    "reason": {"type": "string", "description": "一句话理由"}
                  },
                  "required": ["intent"]
                }
                """;
        return new ToolSpec(TOOL_NAME, "判断问题是否属于本地生活运营数据分析范围", schema);
    }
}
