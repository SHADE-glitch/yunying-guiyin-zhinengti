package com.insight.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insight.agent.agent.AgentEvent;
import com.insight.agent.agent.AgentLoop;
import com.insight.agent.agent.AgentRunResult;
import com.insight.agent.agent.RunRecorder;
import com.insight.agent.guard.OutOfScopeGuard;
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
 * 唯一的对外入口。
 *
 * <p>用 SSE 而不是一次性返回：归因要跑多步，用户需要看到"正在查什么"，
 * 否则一个 20 秒的请求只会让人以为卡死。
 */
@RestController
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final AgentLoop agentLoop;
    private final OutOfScopeGuard guard;
    private final RunRecorder recorder;
    private final ObjectMapper mapper;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public AskController(AgentLoop agentLoop, OutOfScopeGuard guard, RunRecorder recorder,
                         ObjectMapper mapper) {
        this.agentLoop = agentLoop;
        this.guard = guard;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    @GetMapping(value = "/api/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestParam("q") String question) {
        SseEmitter emitter = new SseEmitter(180_000L);

        pool.submit(() -> {
            try {
                // 入口守卫：越界问题不进工具循环，省一次完整的打库与烧 token
                OutOfScopeGuard.Verdict verdict = guard.classify(question);
                if (!verdict.allowed()) {
                    deny(emitter, question, verdict);
                    return;
                }

                AgentRunResult result = agentLoop.run(question, event -> send(emitter, event));

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("runId", result.runId().toString());
                done.put("steps", result.steps());
                done.put("tokens", result.promptTokens() + result.completionTokens());
                done.put("succeeded", result.succeeded());
                send(emitter, AgentEvent.of("done", done));

                emitter.complete();
            } catch (Exception ex) {
                log.error("ask failed: {}", question, ex);
                try {
                    send(emitter, AgentEvent.of("error", Map.of("message", String.valueOf(ex.getMessage()))));
                } catch (Exception ignored) {
                    // 客户端已经断开，没有补救动作
                }
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }

    /** 被入口守卫拦下：同样留痕（用户真的问过），但不进工具循环。 */
    private void deny(SseEmitter emitter, String question, OutOfScopeGuard.Verdict verdict) {
        UUID runId = UUID.randomUUID();
        recorder.startRun(runId, question, "LOOKUP");
        recorder.finishRun(runId, "FAILED", 0, 0, 0, null, verdict.intent().name());
        send(emitter, AgentEvent.of("error",
                Map.of("code", verdict.intent().name(),
                        "message", OutOfScopeGuard.rejectMessage(verdict.intent()))));
        emitter.complete();
    }

    private void send(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(mapper.writeValueAsString(event.data())));
        } catch (IOException ex) {
            // 客户端断开：抛出去让循环停下，避免继续烧 token
            throw new UncheckedIOException(ex);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
