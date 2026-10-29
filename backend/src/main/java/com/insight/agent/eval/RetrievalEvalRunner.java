package com.insight.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.rag.KnowledgeSearchService;
import com.insight.agent.rag.SearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 检索批次执行器。检索评测不需要聊天模型，但需要 embedding 服务。 */
@Component
public class RetrievalEvalRunner {

    public record Prepared(
            RetrievalEvalRequest request,
            int topK,
            List<KnowledgeSearchService.Mode> modes,
            EvalDatasetService.Dataset<EvalDatasetService.RetrievalCase> dataset,
            EvalMetadata metadata,
            int progressTotal) {
    }

    private final EvalDatasetService datasets;
    private final EvalStore store;
    private final KnowledgeSearchService search;
    private final ObjectMapper mapper;
    private final String model;
    private final String embeddingModel;
    private final String envGitSha;

    public RetrievalEvalRunner(EvalDatasetService datasets, EvalStore store,
                               KnowledgeSearchService search, ObjectMapper mapper,
                               @Value("${spring.ai.openai.chat.options.model:unknown}") String model,
                               @Value("${spring.ai.openai.embedding.options.model:unknown}") String embeddingModel,
                               @Value("${GIT_SHA:unknown}") String envGitSha) {
        this.datasets = datasets;
        this.store = store;
        this.search = search;
        this.mapper = mapper;
        this.model = model;
        this.embeddingModel = embeddingModel;
        this.envGitSha = envGitSha;
    }

    public Prepared prepare(RetrievalEvalRequest raw) {
        RetrievalEvalRequest request = raw == null ? RetrievalEvalRequest.empty() : raw;
        request.validate();
        int topK = request.topKOrDefault();
        List<KnowledgeSearchService.Mode> modes = request.modes() == null || request.modes().isEmpty()
                ? List.of(KnowledgeSearchService.Mode.values())
                : request.modes().stream().map(KnowledgeSearchService.Mode::valueOf).toList();
        EvalDatasetService.Dataset<EvalDatasetService.RetrievalCase> dataset = datasets.retrievalDataset();
        if (dataset.size() == 0) {
            throw new IllegalArgumentException("检索评测集为空，先导入 scripts/seed_knowledge.sql");
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("topK", topK);
        config.put("modes", modes.stream().map(Enum::name).toList());

        String gitSha = request.gitSha() == null || request.gitSha().isBlank()
                ? envGitSha : request.gitSha().strip();
        EvalMetadata metadata = new EvalMetadata(gitSha, model, embeddingModel, null,
                Map.copyOf(config), Map.of(), dataset.version(), dataset.size());
        return new Prepared(request, topK, modes, dataset, metadata,
                modes.size() * dataset.size());
    }

    public void execute(UUID batchId, Prepared prepared) {
        List<EvalRetrievalResult> results = new ArrayList<>();
        int done = 0;
        try {
            for (KnowledgeSearchService.Mode mode : prepared.modes()) {
                for (EvalDatasetService.RetrievalCase evalCase : prepared.dataset().cases()) {
                    List<Long> retrieved = search.search(evalCase.query(), prepared.topK(), mode)
                            .stream().map(SearchHit::id).toList();
                    EvalRetrievalResult result = toResult(mode, evalCase, retrieved);
                    results.add(result);
                    store.saveRetrievalResult(batchId, result);
                    done++;
                    store.updateProgress(batchId, done, prepared.progressTotal());
                }
            }
            RetrievalEvalSummary summary = RetrievalEvalSummary.from(results, prepared.topK());
            store.finish(batchId, "SUCCEEDED", mapper.convertValue(summary,
                    new TypeReference<Map<String, Object>>() { }), null);
        } catch (RuntimeException e) {
            RetrievalEvalSummary summary = results.isEmpty()
                    ? null : RetrievalEvalSummary.from(results, prepared.topK());
            store.finish(batchId, "FAILED", summary == null ? Map.of() : mapper.convertValue(summary,
                    new TypeReference<Map<String, Object>>() { }), e.getMessage());
        }
    }

    private static EvalRetrievalResult toResult(KnowledgeSearchService.Mode mode,
                                                EvalDatasetService.RetrievalCase evalCase,
                                                List<Long> retrieved) {
        List<Long> expected = evalCase.expectedDocIds();
        long hit = expected.stream().filter(retrieved::contains).count();
        double recall = expected.isEmpty() ? 0 : (double) hit / expected.size();
        int firstRank = -1;
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                firstRank = i + 1;
                break;
            }
        }
        double rr = firstRank > 0 ? 1.0 / firstRank : 0;
        return new EvalRetrievalResult(mode.name(), evalCase.query(), expected, retrieved,
                Math.round(recall * 10000) / 10000.0, Math.round(rr * 10000) / 10000.0);
    }
}
