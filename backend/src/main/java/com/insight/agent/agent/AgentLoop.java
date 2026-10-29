package com.insight.agent.agent;

import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import com.insight.agent.tool.ToolOutcome;
import com.insight.agent.tool.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 手写的工具调用循环。
 *
 * <p>为什么不交给框架自动执行：每一步都要能记 trace、能被护栏拦截、能中途停下来等人工确认。
 * 框架自动执行是黑盒，HITL 就无从谈起。
 */
@Component
public class AgentLoop {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final RunRecorder recorder;
    private final AgentProperties props;
    private final String systemPrompt;

    @Autowired
    public AgentLoop(LlmClient llm,
                     ToolRegistry tools,
                     RunRecorder recorder,
                     AgentProperties props,
                     @Value("classpath:prompts/system.txt") Resource promptResource) throws IOException {
        this(llm, tools, recorder, props, promptResource.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 评测变体用：prompt 已经解析成文本。 */
    public AgentLoop(LlmClient llm,
                     ToolRegistry tools,
                     RunRecorder recorder,
                     AgentProperties props,
                     String systemPrompt) {
        this.llm = llm;
        this.tools = tools;
        this.recorder = recorder;
        this.props = props;
        this.systemPrompt = systemPrompt;
    }

    public AgentRunResult run(String question, Consumer<AgentEvent> sink) {
        UUID runId = UUID.randomUUID();
        recorder.startRun(runId, question);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(question));

        ReflectionTracker reflection = new ReflectionTracker(
                props.getReflection().getFailureThreshold(),
                props.getReflection().getMaxRounds());

        int promptTokens = 0;
        int completionTokens = 0;
        int step = 0;

        try {
            for (step = 1; step <= props.getMaxSteps(); step++) {
                // 预算检查放在调用之前：已经超了还继续问，只会让超支更多。
                // 已经拿到的答案不受影响——这一步不发出请求，直接把上一轮结果交出去。
                int budget = props.getTokenBudget();
                if (budget > 0 && promptTokens + completionTokens >= budget) {
                    String message = "已达 token 预算 " + budget + "，停止继续调用模型。可以换个问法，或把问题拆小一点。";
                    recorder.finishRun(runId, "FAILED", step - 1, promptTokens, completionTokens,
                            message, "TOKEN_BUDGET");
                    sink.accept(AgentEvent.of("answer", Map.of("text", message)));
                    return new AgentRunResult(runId, message, step - 1,
                            promptTokens, completionTokens, false, "TOKEN_BUDGET");
                }
                LlmResponse resp = llm.chat(messages, tools.specs());
                promptTokens += resp.promptTokens();
                completionTokens += resp.completionTokens();

                if (!resp.hasToolCalls()) {
                    String answer = resp.content() == null ? "" : resp.content();
                    recorder.finishRun(runId, "SUCCEEDED", step, promptTokens, completionTokens, answer, null);
                    sink.accept(AgentEvent.of("answer", Map.of("text", answer)));
                    return new AgentRunResult(runId, answer, step, promptTokens, completionTokens, true, null);
                }

                messages.add(toAssistantMessage(resp));

                for (ToolCallReq call : resp.toolCalls()) {
                    long start = System.currentTimeMillis();
                    sink.accept(AgentEvent.of("tool_call",
                            Map.of("step", step, "tool", call.name(), "args", safe(call.arguments()))));

                    ToolOutcome outcome = tools.invoke(call.name(), call.arguments());
                    int latency = (int) (System.currentTimeMillis() - start);

                    reflection.record(call.name(), call.arguments(), outcome);

                    recorder.recordStep(runId, step,
                            outcome.unusable() ? "TOOL_" + outcome.status() : "TOOL_RESULT",
                            call.name(),
                            Map.of("args", safe(call.arguments())),
                            truncate(outcome.content(), 4000), latency, 0, 0);

                    sink.accept(AgentEvent.of("tool_result", Map.of(
                            "step", step,
                            "tool", call.name(),
                            "status", outcome.status().name(),
                            "result", truncate(outcome.content(), 800))));

                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    call.id(), call.name(), truncate(outcome.content(), 8000))))
                            .build());
                }

                // 工具都处理完，再看要不要打断模型。放在这里而不是单个工具后面，
                // 是因为一轮里可能有多个并行调用，逐个打断会误判。
                String reflectionMessage = reflection.consumeReflectionMessage();
                if (reflectionMessage != null) {
                    messages.add(new SystemMessage(reflectionMessage));
                    recorder.recordStep(runId, step, "REFLECT", null,
                            Map.of("round", reflection.rounds()), reflectionMessage, 0, 0, 0);
                    sink.accept(AgentEvent.of("reflect", Map.of(
                            "step", step,
                            "round", reflection.rounds(),
                            "message", reflectionMessage)));
                }
            }

            String exhausted = "已达到最大步数 " + props.getMaxSteps()
                    + "，未能得出结论。可以换个问法，或把问题拆小一点。";
            recorder.finishRun(runId, "FAILED", step - 1, promptTokens, completionTokens, exhausted, "MAX_STEPS");
            sink.accept(AgentEvent.of("answer", Map.of("text", exhausted)));
            return new AgentRunResult(runId, exhausted, step - 1, promptTokens, completionTokens, false, "MAX_STEPS");

        } catch (Exception ex) {
            recorder.finishRun(runId, "FAILED", step, promptTokens, completionTokens, null, ex.getMessage());
            throw new IllegalStateException("Agent 运行失败: " + ex.getMessage(), ex);
        }
    }

    private AssistantMessage toAssistantMessage(LlmResponse resp) {
        List<AssistantMessage.ToolCall> calls = resp.toolCalls().stream()
                .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments()))
                .toList();
        return AssistantMessage.builder()
                .content(resp.content() == null ? "" : resp.content())
                .toolCalls(calls)
                .build();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
