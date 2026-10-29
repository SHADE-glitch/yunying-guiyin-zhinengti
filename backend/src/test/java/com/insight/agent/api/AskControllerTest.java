package com.insight.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.AgentLoop;
import com.insight.agent.agent.AgentProperties;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.guard.OutOfScopeGuard;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import com.insight.agent.llm.ToolSpec;
import com.insight.agent.tool.AgentTool;
import com.insight.agent.tool.ToolOutcome;
import com.insight.agent.tool.ToolRegistry;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * /api/ask 的最小回归：查数据这条主路径没被归因改动碰坏。
 *
 * <p>用假的 LlmClient 和一个假工具，不连真模型、不花 key。SSE 的帧是直接断言的——
 * 事件名是前端唯一依赖的东西，改名等于改接口。
 */
class AskControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("工具调用 → 答案 → done，全程走原路径，没有归因事件")
    void streamsToolCallThenAnswer() throws Exception {
        AgentProperties props = new AgentProperties();
        props.setMaxSteps(5);
        props.getReflection().setFailureThreshold(99);

        ToolRegistry registry = new ToolRegistry(List.of(new StubSqlTool()), MAPPER);
        AgentLoop loop = new AgentLoop(new OneToolCallThenAnswer(), registry,
                mock(RunRecorder.class), props, new ClassPathResource("prompts/system.txt"));

        MockMvc mvc = mvc(loop);

        MvcResult pending = mvc.perform(get("/api/ask").param("q", "退款率怎么算"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:tool_call").contains("run_sql");
        assertThat(body).contains("event:tool_result").contains("OK");
        assertThat(body).contains("event:answer").contains("退款率 = 退款订单数 ÷ 已支付订单数");
        assertThat(body).contains("event:done").contains("\"succeeded\":true");
        // 这条路径里根本没有 AttributionService：出现 verdict 就说明接错线了
        assertThat(body).doesNotContain("event:verdict");
    }

    @Test
    @DisplayName("模型挂掉：SSE 发 error 事件，不是 500 空响应")
    void modelFailureEmitsErrorEvent() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getReflection().setFailureThreshold(99);

        AgentLoop loop = new AgentLoop((messages, tools) -> {
            throw new IllegalStateException("model down");
        }, new ToolRegistry(List.of(new StubSqlTool()), MAPPER),
                mock(RunRecorder.class), props, new ClassPathResource("prompts/system.txt"));

        MockMvc mvc = mvc(loop);

        MvcResult pending = mvc.perform(get("/api/ask").param("q", "退款率怎么算"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // completeWithError 会让 MockMvc 把异常抛回来，但 error 事件在抛之前已经写出去了
        MvcResult result;
        try {
            result = mvc.perform(asyncDispatch(pending)).andReturn();
        } catch (ServletException expected) {
            result = pending;
        }
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:error").contains("model down");
    }

    /**
     * Spring Boot 会自动把 StringHttpMessageConverter 配成 UTF-8，standalone 模式不会，
     * 不补这一步中文会变成问号——这是测试环境的差异，不是产品行为。
     */
    private static MockMvc mvc(AgentLoop loop) {
        OutOfScopeGuard guard = mock(OutOfScopeGuard.class);
        when(guard.classify(anyString())).thenReturn(new OutOfScopeGuard.Verdict(
                OutOfScopeGuard.Intent.BUSINESS, null));
        return MockMvcBuilders.standaloneSetup(new AskController(loop, guard, mock(RunRecorder.class), MAPPER))
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8))
                .build();
    }

    /** 第一次调工具，第二次给答案。 */
    static class OneToolCallThenAnswer implements LlmClient {
        private int calls;

        @Override
        public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
            calls++;
            if (calls == 1) {
                return new LlmResponse("", List.of(new ToolCallReq("c1", "run_sql",
                        "{\"sql\":\"select count(*) from voucher_order\"}")), 12, 6);
            }
            return LlmResponse.text("退款率 = 退款订单数 ÷ 已支付订单数，全平台 5.00%", 12, 6);
        }
    }

    static class StubSqlTool implements AgentTool {

        @Override
        public String name() {
            return "run_sql";
        }

        @Override
        public String description() {
            return "run a read-only query";
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}";
        }

        @Override
        public ToolOutcome execute(Map<String, Object> args) {
            return ToolOutcome.ok("[{\"count\":49761}]");
        }
    }
}
