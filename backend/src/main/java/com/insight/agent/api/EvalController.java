package com.insight.agent.api;

import com.insight.agent.eval.AgentEvalRequest;
import com.insight.agent.eval.EvalBatch;
import com.insight.agent.eval.EvalBatchDetail;
import com.insight.agent.eval.EvalComparator;
import com.insight.agent.eval.EvalComparison;
import com.insight.agent.eval.EvalDatasetService;
import com.insight.agent.eval.EvalGatePolicy;
import com.insight.agent.eval.EvalJobService;
import com.insight.agent.eval.EvalPromptLibrary;
import com.insight.agent.eval.EvalStore;
import com.insight.agent.eval.RetrievalEvalRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测实验台。批次是异步的：POST 建批次立刻返回，页面轮询进度。
 *
 * <p>评测会真调模型，不能像普通查询那样同步等；也不该把"跑一次"做成一次性的
 * 接口调用——不落库的分数事后无法解释是哪版代码、哪个 prompt、哪个数据集跑出来的。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalJobService jobs;
    private final EvalStore store;
    private final EvalComparator comparator;
    private final EvalDatasetService datasets;
    private final EvalPromptLibrary prompts;

    public EvalController(EvalJobService jobs, EvalStore store, EvalComparator comparator,
                          EvalDatasetService datasets, EvalPromptLibrary prompts) {
        this.jobs = jobs;
        this.store = store;
        this.comparator = comparator;
        this.datasets = datasets;
        this.prompts = prompts;
    }

    @PostMapping("/agent/runs")
    public ResponseEntity<?> startAgent(@RequestBody(required = false) AgentEvalRequest request) {
        try {
            return ResponseEntity.accepted().body(jobs.startAgent(request));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/retrieval/runs")
    public ResponseEntity<?> startRetrieval(@RequestBody(required = false) RetrievalEvalRequest request) {
        try {
            return ResponseEntity.accepted().body(jobs.startRetrieval(request));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/batches")
    public List<EvalBatch> batches(@RequestParam(required = false) String kind,
                                   @RequestParam(defaultValue = "20") int limit) {
        return store.list(kind, limit);
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<?> batch(@PathVariable UUID id) {
        EvalBatch batch = store.find(id).orElse(null);
        if (batch == null) {
            return error(HttpStatus.NOT_FOUND, "批次不存在: " + id);
        }
        return ResponseEntity.ok(new EvalBatchDetail(batch,
                store.caseResults(id), store.retrievalResults(id)));
    }

    /**
     * 基线/候选对比。数据集版本不同不阻止对比，但门禁必定失败——
     * 不同题目上的分数本来就不该拿来比。
     */
    @PostMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam UUID baseline, @RequestParam UUID candidate) {
        EvalBatch base = store.find(baseline).orElse(null);
        EvalBatch cand = store.find(candidate).orElse(null);
        if (base == null || cand == null) {
            return error(HttpStatus.NOT_FOUND,
                    base == null ? "baseline 批次不存在: " + baseline : "candidate 批次不存在: " + candidate);
        }
        if (!base.kind().equals(cand.kind())) {
            return error(HttpStatus.BAD_REQUEST,
                    "只能比较同类型批次: " + base.kind() + " vs " + cand.kind());
        }
        EvalGatePolicy policy = EvalGatePolicy.defaults();
        EvalComparison comparison = EvalBatch.KIND_RETRIEVAL.equals(base.kind())
                ? comparator.compareRetrieval(base, store.retrievalResults(baseline),
                        cand, store.retrievalResults(candidate), policy)
                : comparator.compareAgent(base, store.caseResults(baseline),
                        cand, store.caseResults(candidate), policy);
        return ResponseEntity.ok(comparison);
    }

    /** 数据集版本与用例清单。版本是内容 sha256，改任何一条标注都会变。 */
    @GetMapping("/datasets")
    public Map<String, Object> datasets() {
        EvalDatasetService.Dataset<EvalDatasetService.AgentCase> agent = datasets.agentDataset();
        EvalDatasetService.Dataset<EvalDatasetService.RetrievalCase> retrieval = datasets.retrievalDataset();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", Map.of("version", agent.version(), "size", agent.size(), "cases", agent.cases()));
        out.put("retrieval", Map.of("version", retrieval.version(), "size", retrieval.size(),
                "cases", retrieval.cases()));
        return out;
    }

    @GetMapping("/prompts")
    public List<EvalPromptLibrary.PromptInfo> prompts() {
        return prompts.list();
    }

    @GetMapping("/gate")
    public EvalGatePolicy gate() {
        return EvalGatePolicy.defaults();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
