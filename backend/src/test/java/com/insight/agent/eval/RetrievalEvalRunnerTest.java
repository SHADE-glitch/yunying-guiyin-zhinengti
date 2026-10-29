package com.insight.agent.eval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.rag.KnowledgeSearchService;
import com.insight.agent.rag.SearchHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetrievalEvalRunnerTest {

    @Test
    @DisplayName("每个模式每条 query 的结果都先落库，再写批次汇总")
    void persistsEveryQueryResult() {
        EvalDatasetService datasets = mock(EvalDatasetService.class);
        when(datasets.retrievalDataset()).thenReturn(new EvalDatasetService.Dataset<>(
                List.of(new EvalDatasetService.RetrievalCase(1, "GMV 怎么算", List.of(7L))), "dataset-v1"));

        EvalStore store = mock(EvalStore.class);
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        when(search.search(anyString(), anyInt(), any()))
                .thenReturn(List.of(new SearchHit(7, "metric", "GMV", "content", 1.0)));

        RetrievalEvalRunner runner = new RetrievalEvalRunner(datasets, store, search,
                new ObjectMapper(), "chat-model", "embedding-model", "git-sha");
        RetrievalEvalRunner.Prepared prepared = runner.prepare(new RetrievalEvalRequest(
                "test", null, null, null, 5, List.of("HYBRID", "KEYWORD")));

        runner.execute(UUID.randomUUID(), prepared);

        verify(store, times(2)).saveRetrievalResult(any(), any());
        verify(store).finish(any(), eq("SUCCEEDED"), anyMap(), isNull());
    }
}
