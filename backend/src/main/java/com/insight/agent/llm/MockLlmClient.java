package com.insight.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 无 Key 时的确定性替身，只为验证循环、SSE 和落库链路，不代表任何模型能力。
 * 用 -Dspring.profiles.active=mock 启用。
 */
@Component
@Profile("mock")
public class MockLlmClient implements LlmClient {

    private final AtomicInteger turn = new AtomicInteger();

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
        int t = turn.incrementAndGet();
        if (t == 1) {
            String sql = "select count(*) as cnt from voucher_order";
            return new LlmResponse(
                    "",
                    List.of(new ToolCallReq("call_1", "run_sql",
                            "{\"sql\":\"" + sql + "\"}")),
                    120, 18);
        }
        String lastToolResult = messages.stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage)
                .reduce((a, b) -> b)
                .map(m -> {
                    var trm = (org.springframework.ai.chat.messages.ToolResponseMessage) m;
                    return trm.getResponses().isEmpty() ? "{}" : trm.getResponses().get(0).responseData();
                })
                .orElse("{}");
        return LlmResponse.text("[mock] 查询完成，工具返回：" + lastToolResult, 200, 30);
    }
}
