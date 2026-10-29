package com.insight.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.attribution.AttributionScopeParser.AmbiguousScopeException;
import com.insight.agent.attribution.AttributionService;
import com.insight.agent.attribution.UnsupportedMetricException;
import com.insight.agent.guard.OutOfScopeGuard;
import com.insight.agent.session.SessionPreparer;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 归因入口的错误码映射：前端靠 code 分支，不靠 message 文案。
 *
 * <p>不跑真归因——服务层用 mock，只验证"哪种异常变成哪个 code"。
 * 结论对不对由 {@code AttributionServiceIntegrationTest} 负责，两层不重复。
 */
class AttributionControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("问错指标：UNSUPPORTED_METRIC，不是笼统的 FAILED")
    void unsupportedMetricHasItsOwnCode() throws Exception {
        String body = errorBodyFor(new UnsupportedMetricException("退款率"));

        assertThat(body).contains("event:error").contains("UNSUPPORTED_METRIC").contains("退款率");
        assertThat(body).doesNotContain("\"code\":\"FAILED\"");
    }

    @Test
    @DisplayName("问题有歧义：仍然是 AMBIGUOUS_SCOPE，两个 code 不混")
    void ambiguousScopeKeepsItsOwnCode() throws Exception {
        String body = errorBodyFor(new AmbiguousScopeException("区域同时出现了「华东」和「华南」"));

        assertThat(body).contains("AMBIGUOUS_SCOPE").contains("华东");
        assertThat(body).doesNotContain("UNSUPPORTED_METRIC");
    }

    private static String errorBodyFor(RuntimeException failure) throws Exception {
        AttributionService service = mock(AttributionService.class);
        when(service.run(anyString(), any())).thenThrow(failure);

        SessionPreparer preparer = mock(SessionPreparer.class);
        when(preparer.prepare(any(), anyString())).thenReturn(new SessionPreparer.Prepared(
                UUID.randomUUID(), "为什么跌了", "为什么跌了", null));

        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AttributionController(
                        service, businessGuard(), preparer, mock(RunRecorder.class), MAPPER))
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8))
                .build();

        MvcResult pending = mvc.perform(get("/api/attribute").param("q", "为什么跌了"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // completeWithError 会把异常抛回来，但 error 事件在抛之前已经写出去了
        MvcResult result;
        try {
            result = mvc.perform(asyncDispatch(pending)).andReturn();
        } catch (ServletException expected) {
            result = pending;
        }
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static OutOfScopeGuard businessGuard() {
        OutOfScopeGuard guard = mock(OutOfScopeGuard.class);
        when(guard.classify(anyString())).thenReturn(new OutOfScopeGuard.Verdict(
                OutOfScopeGuard.Intent.BUSINESS, null));
        return guard;
    }
}
