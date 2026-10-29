package com.insight.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.insight.agent.agent.AgentEvent;
import com.insight.agent.agent.AgentLoop;
import com.insight.agent.agent.AgentProperties;
import com.insight.agent.agent.AgentRunResult;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import com.insight.agent.llm.ToolSpec;
import com.insight.agent.tool.AgentTool;
import com.insight.agent.tool.ToolOutcome;
import com.insight.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;

class AgentLoopReflectionTest {

    @Test
    void injectsReflectionAfterConsecutiveFailures() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getReflection().setFailureThreshold(2);
        props.getReflection().setMaxRounds(2);

        RunRecorder recorder = mock(RunRecorder.class);
        ToolRegistry registry = new ToolRegistry(List.of(new AlwaysRejectingTool()), new ObjectMapper());

        AgentLoop loop = new AgentLoop(new TwoFailuresThenAnswer(), registry, recorder, props,
                new ClassPathResource("prompts/system.txt"));

        List<AgentEvent> events = new ArrayList<>();
        AgentRunResult result = loop.run("查一下", events::add);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.answer()).isEqualTo("done");
        assertThat(events).anyMatch(e -> e.type().equals("reflect"));
        verify(recorder).recordStep(any(), anyInt(), eq("REFLECT"), any(), any(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void stopsAtMaxStepsWhenModelNeverAnswers() throws Exception {
        AgentProperties props = new AgentProperties();
        props.setMaxSteps(3);
        props.getReflection().setFailureThreshold(99);

        RunRecorder recorder = mock(RunRecorder.class);
        ToolRegistry registry = new ToolRegistry(List.of(new AlwaysRejectingTool()), new ObjectMapper());
        AgentLoop loop = new AgentLoop(new AlwaysCallsTool(), registry, recorder, props,
                new ClassPathResource("prompts/system.txt"));

        AgentRunResult result = loop.run("查一下", e -> { });

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).isEqualTo("MAX_STEPS");
        assertThat(result.steps()).isEqualTo(3);
    }

    @Test
    void stopsWhenTokenBudgetExceeded() throws Exception {
        AgentProperties props = new AgentProperties();
        props.setMaxSteps(6);
        // 每次调用 15 token，上限 10：第一步之后就该停，不再发第二次请求
        props.setTokenBudget(10);
        props.getReflection().setFailureThreshold(99);

        RunRecorder recorder = mock(RunRecorder.class);
        ToolRegistry registry = new ToolRegistry(List.of(new AlwaysRejectingTool()), new ObjectMapper());
        AgentLoop loop = new AgentLoop(new AlwaysCallsTool(), registry, recorder, props,
                new ClassPathResource("prompts/system.txt"));

        AgentRunResult result = loop.run("查一下", e -> { });

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).isEqualTo("TOKEN_BUDGET");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.answer()).contains("token 预算");
    }

    /** 前两次都调工具，第三次给答案。 */
    static class TwoFailuresThenAnswer implements LlmClient {
        private int calls;

        @Override
        public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
            calls++;
            if (calls <= 2) {
                return new LlmResponse("", List.of(new ToolCallReq("c" + calls, "run_sql", "{}")), 10, 5);
            }
            return LlmResponse.text("done", 10, 5);
        }
    }

    static class AlwaysCallsTool implements LlmClient {
        private int calls;

        @Override
        public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
            calls++;
            return new LlmResponse("", List.of(new ToolCallReq("c" + calls, "run_sql", "{}")), 10, 5);
        }
    }

    static class AlwaysRejectingTool implements AgentTool {
        @Override
        public String name() {
            return "run_sql";
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{}}";
        }

        @Override
        public ToolOutcome execute(Map<String, Object> args) {
            return ToolOutcome.rejected("只允许 SELECT");
        }
    }
}
