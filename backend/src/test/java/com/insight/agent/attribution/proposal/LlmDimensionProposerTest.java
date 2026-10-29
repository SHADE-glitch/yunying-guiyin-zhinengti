package com.insight.agent.attribution.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.attribution.AttributionPorts.ProposalContext;
import com.insight.agent.attribution.AttributionPorts.Scope;
import com.insight.agent.attribution.Dimension;
import com.insight.agent.llm.LlmClient;
import com.insight.agent.llm.LlmResponse;
import com.insight.agent.llm.ToolCallReq;
import com.insight.agent.llm.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

/**
 * 模型版提议器给出的选项必须只剩可用的维度。
 *
 * <p>这里盯的不是"模型听不听话"，而是"我们有没有把一个不能选的维度摆到它面前"。
 */
class LlmDimensionProposerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 记录每次被问到的工具 schema，并按 {@code answer} 回一个维度。 */
    static class RecordingLlm implements LlmClient {
        final List<String> schemas = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        String answer = "CATEGORY";
        int calls;

        @Override
        public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
            calls++;
            schemas.add(tools.get(0).inputSchema());
            this.messages.add(messages.get(1).getText());
            return new LlmResponse("", List.of(new ToolCallReq("call-1", "propose_dimension",
                    "{\"dimension\":\"" + answer + "\"}")), 0, 0);
        }
    }

    private static LlmDimensionProposer proposer(LlmClient llm) {
        return new LlmDimensionProposer(llm, MAPPER, "sys", "为什么华东跌了", Scope.root());
    }

    private static ProposalContext ctx(List<Dimension> used, Set<Dimension> exhausted) {
        return new ProposalContext(2, used, exhausted, List.of("REGION=华东"));
    }

    @Test
    @DisplayName("探测失败的维度不出现在工具枚举里，模型无从选中")
    void exhaustedDimensionIsNotOffered() {
        RecordingLlm llm = new RecordingLlm();

        proposer(llm).propose(ctx(List.of(), Set.of(Dimension.REGION)));

        assertThat(llm.schemas.get(0)).doesNotContain("REGION")
                .contains("CATEGORY").contains("SHOP");
    }

    @Test
    @DisplayName("已试过的维度同样从枚举里去掉")
    void usedDimensionIsNotOffered() {
        RecordingLlm llm = new RecordingLlm();

        proposer(llm).propose(ctx(List.of(Dimension.CATEGORY), Set.of()));

        assertThat(llm.schemas.get(0)).doesNotContain("CATEGORY")
                .contains("REGION").contains("SHOP");
    }

    @Test
    @DisplayName("模型硬要回一个不可用的维度：返回 null 交给 ProposalGuard，不猜")
    void rejectsDimensionOutsideAllowedSet() {
        RecordingLlm llm = new RecordingLlm();
        llm.answer = "REGION";

        assertThat(proposer(llm).propose(ctx(List.of(), Set.of(Dimension.REGION)))).isNull();
    }

    @Test
    @DisplayName("三个维度全废：一次模型都不调")
    void neverCallsModelWhenNothingIsLeft() {
        RecordingLlm llm = new RecordingLlm();

        assertThat(proposer(llm).propose(ctx(List.of(Dimension.CATEGORY),
                Set.of(Dimension.REGION, Dimension.SHOP)))).isNull();
        assertThat(llm.calls).isZero();
    }

    @Test
    @DisplayName("上下文里明确告诉模型哪些维度废了")
    void tellsModelWhichDimensionsAreExhausted() {
        RecordingLlm llm = new RecordingLlm();

        proposer(llm).propose(ctx(List.of(), Set.of(Dimension.REGION)));

        assertThat(llm.messages.get(0)).contains("不可再选的维度（多次探测失败）：[REGION]");
    }
}
