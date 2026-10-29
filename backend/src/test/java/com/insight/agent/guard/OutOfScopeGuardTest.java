package com.insight.agent.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.guard.OutOfScopeGuard.Intent;
import com.insight.agent.guard.OutOfScopeGuard.Verdict;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 入口守卫：词表命中零成本放行；词表未命中才问模型；模型任何异常都放行。
 *
 * <p>放行（fail-open）是刻意的：入口守卫是体验与成本优化，不是安全边界；
 * 误拒正常问题比放过越界问题的代价更高，后者还有 SqlGuard 与只读账号兜着。
 */
class OutOfScopeGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("业务词命中：直接放行，不调模型")
    void businessVocabularyNeverCallsModel() {
        AtomicBoolean called = new AtomicBoolean();
        LlmClient llm = (messages, tools) -> {
            called.set(true);
            return LlmResponse.text("should not happen", 1, 1);
        };
        OutOfScopeGuard guard = new OutOfScopeGuard(llm, MAPPER);

        assertThat(guard.classify("为什么华东GMV跌了").intent()).isEqualTo(Intent.BUSINESS);
        assertThat(called).isFalse();
    }

    @Test
    @DisplayName("写诗：词表未命中，模型分类器返回 OUT_OF_SCOPE")
    void poemIsRejectedByModelClassifier() {
        LlmClient llm = (messages, tools) -> new LlmResponse(null, List.of(new ToolCallReq(
                "call_1", "classify_intent",
                "{\"intent\":\"OUT_OF_SCOPE\",\"reason\":\"与运营数据无关\"}")), 50, 10);
        OutOfScopeGuard guard = new OutOfScopeGuard(llm, MAPPER);

        Verdict verdict = guard.classify("帮我写一首诗");

        assertThat(verdict.intent()).isEqualTo(Intent.OUT_OF_SCOPE);
        assertThat(OutOfScopeGuard.rejectMessage(verdict.intent()))
                .contains("为什么华东 GMV 跌了")
                .contains("退款率怎么算");
    }

    @Test
    @DisplayName("模型挂了：放行，不把分类器故障变成功能故障")
    void modelFailureFailsOpen() {
        LlmClient llm = (messages, tools) -> {
            throw new IllegalStateException("connection refused");
        };
        OutOfScopeGuard guard = new OutOfScopeGuard(llm, MAPPER);

        assertThat(guard.classify("帮我写一首诗").intent()).isEqualTo(Intent.BUSINESS);
    }

    @Test
    @DisplayName("模型没给工具调用或给了坏 JSON：一律放行")
    void malformedClassifierAnswerFailsOpen() {
        LlmClient textOnly = (messages, tools) -> LlmResponse.text("我不确定", 10, 5);
        assertThat(new OutOfScopeGuard(textOnly, MAPPER).classify("帮我写一首诗").intent())
                .isEqualTo(Intent.BUSINESS);

        LlmClient badJson = (messages, tools) -> new LlmResponse(null, List.of(new ToolCallReq(
                "call_1", "classify_intent", "not-json")), 10, 5);
        assertThat(new OutOfScopeGuard(badJson, MAPPER).classify("帮我写一首诗").intent())
                .isEqualTo(Intent.BUSINESS);
    }

    @Test
    @DisplayName("空问题：CLARIFY，不花模型调用")
    void blankQuestionIsClarify() {
        LlmClient llm = (messages, tools) -> LlmResponse.text("should not happen", 1, 1);
        assertThat(new OutOfScopeGuard(llm, MAPPER).classify("  ").intent()).isEqualTo(Intent.CLARIFY);
    }
}
