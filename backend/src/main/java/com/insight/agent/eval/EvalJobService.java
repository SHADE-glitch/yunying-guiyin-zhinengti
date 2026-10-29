package com.insight.agent.eval;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 评测任务调度。
 *
 * <p>评测是分钟级任务，不能塞在一次 HTTP 请求里：POST 建批次立刻返回，
 * 页面/脚本轮询进度。批次中断（服务重启）也要留下 FAILED，而不是永远 RUNNING。
 */
@Service
public class EvalJobService {

    private static final Logger log = LoggerFactory.getLogger(EvalJobService.class);
    private static final DateTimeFormatter LABEL_TIME = DateTimeFormatter.ofPattern("MMdd-HHmm");
    /** 两个在跑、两个排队；再多的批次只会排队烧时间，不如直接拒绝。 */
    private static final int MAX_OUTSTANDING = 4;

    private final EvalStore store;
    private final AgentEvalRunner agentRunner;
    private final RetrievalEvalRunner retrievalRunner;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicInteger outstanding = new AtomicInteger();

    public EvalJobService(EvalStore store, AgentEvalRunner agentRunner,
                          RetrievalEvalRunner retrievalRunner) {
        this.store = store;
        this.agentRunner = agentRunner;
        this.retrievalRunner = retrievalRunner;
    }

    @PostConstruct
    void failInterrupted() {
        int failed = store.failInterrupted();
        if (failed > 0) {
            log.warn("标记 {} 个中断的评测批次为 FAILED", failed);
        }
    }

    public EvalBatch startAgent(AgentEvalRequest request) {
        reserveSlot();
        AgentEvalRunner.Prepared prepared;
        UUID id;
        try {
            prepared = agentRunner.prepare(request);
            UUID baselineId = resolveBaseline(request == null ? null : request.baselineId(),
                    EvalBatch.KIND_AGENT, prepared.metadata().datasetVersion());
            String label = request == null ? null : request.labelOr("agent-" + now());
            String note = request == null ? null : request.note();
            id = store.create(new EvalStore.EvalBatchDraft(
                    EvalBatch.KIND_AGENT, label, note, baselineId, prepared.metadata(),
                    prepared.dataset().size()));
        } catch (RuntimeException e) {
            outstanding.decrementAndGet();
            throw e;
        }
        pool.submit(() -> {
            try {
                agentRunner.execute(id, prepared);
            } catch (RuntimeException e) {
                log.error("Agent 评测批次失败: {}", id, e);
                store.finish(id, "FAILED", Map.of(), rootMessage(e));
            } finally {
                outstanding.decrementAndGet();
            }
        });
        return store.find(id).orElseThrow();
    }

    public EvalBatch startRetrieval(RetrievalEvalRequest request) {
        reserveSlot();
        RetrievalEvalRunner.Prepared prepared;
        UUID id;
        try {
            prepared = retrievalRunner.prepare(request);
            UUID baselineId = resolveBaseline(request == null ? null : request.baselineId(),
                    EvalBatch.KIND_RETRIEVAL, prepared.metadata().datasetVersion());
            String label = request == null ? null : request.labelOr("retrieval-" + now());
            String note = request == null ? null : request.note();
            id = store.create(new EvalStore.EvalBatchDraft(
                    EvalBatch.KIND_RETRIEVAL, label, note, baselineId, prepared.metadata(),
                    prepared.progressTotal()));
        } catch (RuntimeException e) {
            outstanding.decrementAndGet();
            throw e;
        }
        pool.submit(() -> {
            try {
                retrievalRunner.execute(id, prepared);
            } catch (RuntimeException e) {
                log.error("检索评测批次失败: {}", id, e);
                store.finish(id, "FAILED", Map.of(), rootMessage(e));
            } finally {
                outstanding.decrementAndGet();
            }
        });
        return store.find(id).orElseThrow();
    }

    private void reserveSlot() {
        if (outstanding.incrementAndGet() > MAX_OUTSTANDING) {
            outstanding.decrementAndGet();
            throw new IllegalArgumentException(
                    "评测队列已满（最多 " + MAX_OUTSTANDING + " 个在跑/排队），等当前批次结束再提交");
        }
    }

    private UUID resolveBaseline(UUID requested, String kind, String datasetVersion) {
        if (requested != null) {
            EvalBatch baseline = store.find(requested)
                    .orElseThrow(() -> new IllegalArgumentException("baseline 批次不存在: " + requested));
            if (!kind.equals(baseline.kind())) {
                throw new IllegalArgumentException("baseline 类型不一致: " + baseline.kind() + " ≠ " + kind);
            }
            return requested;
        }
        return store.latestSucceeded(kind, datasetVersion).map(EvalBatch::id).orElse(null);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static String now() {
        return LocalDateTime.now().format(LABEL_TIME);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
