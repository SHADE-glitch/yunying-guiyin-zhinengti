package com.insight.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.AgentEvent;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.attribution.AttributionScopeParser.AmbiguousScopeException;
import com.insight.agent.attribution.AttributionService;
import com.insight.agent.attribution.AttributionService.AttributionRun;
import com.insight.agent.attribution.UnsupportedMetricException;
import com.insight.agent.guard.OutOfScopeGuard;
import com.insight.agent.session.SessionPreparer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 归因入口。
 *
 * <p>和 /api/ask 分开而不是复用一个入口：两者的返回结构不同（查数是自由文本，
 * 归因是"根口径 + 每层拆分 + 结论"的结构化路径），混在一个端点里前端得靠猜。
 * 也便于分别限流和分别观察。
 *
 * <p>事件顺序：baseline → probe* → verdict → answer → done。每一步算完就推，
 * 因为一次归因要打十几次库，攒到最后再返回等于让用户对着空屏等。
 */
@RestController
public class AttributionController {

    private static final Logger log = LoggerFactory.getLogger(AttributionController.class);

    private final AttributionService service;
    private final OutOfScopeGuard guard;
    private final SessionPreparer preparer;
    private final RunRecorder recorder;
    private final ObjectMapper mapper;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public AttributionController(AttributionService service, OutOfScopeGuard guard,
                                 SessionPreparer preparer, RunRecorder recorder, ObjectMapper mapper) {
        this.service = service;
        this.guard = guard;
        this.preparer = preparer;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    @GetMapping(value = "/api/attribute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter attribute(@RequestParam("q") String question,
                                @RequestParam(value = "sessionId", required = false) String sessionId) {
        SseEmitter emitter = new SseEmitter(180_000L);

        pool.submit(() -> {
            try {
                // 入口守卫：越界问题不进归因循环，省一次完整的打库与烧 token
                OutOfScopeGuard.Verdict verdict = guard.classify(question);
                if (!verdict.allowed()) {
                    deny(emitter, question, verdict.intent().name(),
                            OutOfScopeGuard.rejectMessage(verdict.intent()));
                    return;
                }

                // 会话补全：只在高置信时继承范围/指标，判不准就重问
                SessionPreparer.Prepared prepared = preparer.prepare(sessionId, question);
                if (prepared.clarify()) {
                    deny(emitter, question, "CLARIFY", prepared.note());
                    return;
                }

                AttributionRun run = service.run(prepared.resolved(), event -> send(emitter, event));
                preparer.record(prepared, run);

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("runId", run.runId().toString());
                done.put("outcome", run.result().outcome().name());
                done.put("sessionId", prepared.sessionId().toString());
                done.put("resolved", prepared.resolved());
                if (prepared.note() != null) {
                    done.put("note", prepared.note());
                }
                send(emitter, AgentEvent.of("done", done));
                emitter.complete();
            } catch (AmbiguousScopeException ex) {
                // 问题本身有歧义：说清楚要改哪里，不要替用户猜一个范围
                log.warn("attribution scope ambiguous: {}", question);
                fail(emitter, "AMBIGUOUS_SCOPE", ex.getMessage());
            } catch (UnsupportedMetricException ex) {
                // 口径不支持：和"跑挂了"分开报，前端据此提示换问法，而不是让用户重试
                log.warn("attribution metric unsupported: {} ({})", question, ex.metric());
                fail(emitter, "UNSUPPORTED_METRIC", ex.getMessage());
            } catch (Exception ex) {
                log.error("attribute failed: {}", question, ex);
                fail(emitter, "FAILED", String.valueOf(ex.getMessage()));
            }
        });

        return emitter;
    }

    /** 入口拦截（越界/重问）：同样留痕（用户真的问过），但不创建归因节点、不花查询预算。 */
    private void deny(SseEmitter emitter, String question, String code, String message) {
        UUID runId = UUID.randomUUID();
        recorder.startRun(runId, question, "ATTRIBUTION");
        recorder.finishRun(runId, "FAILED", 0, 0, 0, null, code);
        send(emitter, AgentEvent.of("error", Map.of("code", code, "message", message)));
        emitter.complete();
    }

    private void fail(SseEmitter emitter, String code, String message) {
        try {
            send(emitter, AgentEvent.of("error", Map.of("code", code, "message", message)));
        } catch (Exception ignored) {
            // 客户端已经断开，没有补救动作
        }
        emitter.completeWithError(new IllegalStateException(code + ": " + message));
    }

    private void send(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(mapper.writeValueAsString(event.data())));
        } catch (IOException ex) {
            // 客户端断开：抛出去让归因停下，不再继续打库
            throw new UncheckedIOException(ex);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
