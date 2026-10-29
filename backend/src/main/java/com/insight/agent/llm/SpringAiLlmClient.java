package com.insight.agent.llm;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

@Component
@Profile("!mock")
public class SpringAiLlmClient implements LlmClient {

    private final ChatModel chatModel;

    public SpringAiLlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
        // internalToolExecutionEnabled(false)：只要模型"说要调什么"，执行权留给我们自己。
        // 这是整个项目能加护栏、加 HITL、加 trace 的前提。
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools.stream().map(SpringAiLlmClient::toCallback).toList())
                .internalToolExecutionEnabled(false)
                .temperature(0.0)
                .build();

        ChatResponse response = chatModel.call(new Prompt(messages, options));
        AssistantMessage output = response.getResult().getOutput();

        List<ToolCallReq> calls = output.getToolCalls().stream()
                .map(tc -> new ToolCallReq(tc.id(), tc.name(), tc.arguments()))
                .toList();

        Usage usage = response.getMetadata().getUsage();
        int promptTokens = usage == null ? 0 : usage.getPromptTokens().intValue();
        int completionTokens = usage == null ? 0 : usage.getCompletionTokens().intValue();

        return new LlmResponse(output.getText(), calls, promptTokens, completionTokens);
    }

    /** 只用来把工具定义送进请求体；executor 永不被调用（自动执行已关闭）。 */
    private static ToolCallback toCallback(ToolSpec spec) {
        return FunctionToolCallback.<Map<String, Object>, String>builder(spec.name(), args -> "")
                .description(spec.description())
                .inputSchema(spec.inputSchema())
                .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                .build();
    }
}
