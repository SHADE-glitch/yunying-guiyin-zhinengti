package com.insight.agent.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.insight.agent.eval.EvalBatch;
import com.insight.agent.eval.EvalComparator;
import com.insight.agent.eval.EvalDatasetService;
import com.insight.agent.eval.EvalJobService;
import com.insight.agent.eval.EvalPromptLibrary;
import com.insight.agent.eval.EvalStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EvalControllerTest {

    private final EvalJobService jobs = mock(EvalJobService.class);
    private final EvalStore store = mock(EvalStore.class);
    private final EvalDatasetService datasets = mock(EvalDatasetService.class);
    private final EvalPromptLibrary prompts = mock(EvalPromptLibrary.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new EvalController(jobs, store, new EvalComparator(), datasets, prompts))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new ObjectMapper().registerModule(new JavaTimeModule())))
            .build();

    @Test
    @DisplayName("启动批次：202 立刻返回批次快照，而不是等评测跑完")
    void startReturnsAcceptedBatch() throws Exception {
        EvalBatch batch = batch(EvalBatch.KIND_AGENT);
        when(jobs.startAgent(any())).thenReturn(batch);

        mvc.perform(post("/api/eval/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\":0.55}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(batch.id().toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    @DisplayName("非法覆盖参数：400 带可读原因，不吞成 500")
    void invalidRequestIsBadRequest() throws Exception {
        when(jobs.startAgent(any())).thenThrow(new IllegalArgumentException("threshold 必须在 (0, 1] 之间"));

        mvc.perform(post("/api/eval/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("threshold 必须在 (0, 1] 之间"));
    }

    @Test
    @DisplayName("批次不存在：404，不返回空对象")
    void missingBatchIsNotFound() throws Exception {
        when(store.find(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/eval/batches/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("跨类型对比：400，避免拿检索批次比 Agent 批次")
    void compareDifferentKindsIsBadRequest() throws Exception {
        UUID base = UUID.randomUUID();
        UUID cand = UUID.randomUUID();
        when(store.find(base)).thenReturn(Optional.of(batch(EvalBatch.KIND_AGENT, base)));
        when(store.find(cand)).thenReturn(Optional.of(batch(EvalBatch.KIND_RETRIEVAL, cand)));

        mvc.perform(post("/api/eval/compare").param("baseline", base.toString())
                        .param("candidate", cand.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("批次详情：元数据、用例结果、检索结果一起返回")
    void batchDetailIncludesResults() throws Exception {
        EvalBatch batch = batch(EvalBatch.KIND_AGENT);
        when(store.find(batch.id())).thenReturn(Optional.of(batch));
        when(store.caseResults(batch.id())).thenReturn(List.of());

        mvc.perform(get("/api/eval/batches/{id}", batch.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.id").value(batch.id().toString()))
                .andExpect(jsonPath("$.cases").isArray())
                .andExpect(jsonPath("$.retrievalCases").isArray());
    }

    private static EvalBatch batch(String kind) {
        return batch(kind, UUID.randomUUID());
    }

    private static EvalBatch batch(String kind, UUID id) {
        return new EvalBatch(id, kind, "label", null, "RUNNING", null, "sha", "model",
                "embedding", Instant.parse("2026-10-01T00:00:00Z"), Map.of(), Map.of(),
                "dataset", 1, 0, 1, Map.of(), null, Instant.now(), null);
    }
}
