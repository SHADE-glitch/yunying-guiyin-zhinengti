package com.insight.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 运行留痕。每条 SQL、每次工具调用、每步 token 都落库。
 * 面试里"你怎么排查一次错误的归因"就靠这张表回答。
 */
@Component
public class RunRecorder {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RunRecorder(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void startRun(UUID runId, String question) {
        startRun(runId, question, "LOOKUP");
    }

    /**
     * mode 显式传入，不依赖列默认值：将来加第三种模式时，忘记传参的代码会静默变成查数，
     * 这种错误很难在日志里发现。
     */
    public void startRun(UUID runId, String question, String mode) {
        jdbc.update("insert into agent_run (id, question, status, mode) values (?, ?, 'RUNNING', ?)",
                runId, question, mode);
    }

    public void recordStep(UUID runId, int stepNo, String type, String toolName,
                           Object input, String output, int latencyMs, int promptTokens, int completionTokens) {
        recordStepReturningId(runId, stepNo, type, toolName, input, output, latencyMs,
                promptTokens, completionTokens);
    }

    /** 归因需要拿到 step id 去做证据绑定，所以这里返回主键。 */
    public long recordStepReturningId(UUID runId, int stepNo, String type, String toolName,
                                      Object input, String output, int latencyMs,
                                      int promptTokens, int completionTokens) {
        Long id = jdbc.queryForObject("""
                insert into agent_step (run_id, step_no, type, tool_name, input, output,
                                        latency_ms, prompt_tokens, completion_tokens)
                values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                returning id
                """, Long.class,
                runId, stepNo, type, toolName, toJson(input), toJson(output),
                latencyMs, promptTokens, completionTokens);
        return id == null ? 0L : id;
    }

    /** 归因结局单独写：finishRun 的签名是查数流程在用的，不为了一个新字段改它。 */
    public void recordAttributionOutcome(UUID runId, String outcome) {
        jdbc.update("update agent_run set attribution_outcome = ? where id = ?", outcome, runId);
    }

    public void finishRun(UUID runId, String status, int steps, int promptTokens,
                          int completionTokens, String answer, String error) {
        jdbc.update("""
                update agent_run
                   set status = ?, step_count = ?, prompt_tokens = ?, completion_tokens = ?,
                       answer = ?, error = ?, finished_at = now()
                 where id = ?
                """, status, steps, promptTokens, completionTokens, answer, error, runId);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + String.valueOf(value).replace("\"", "'") + "\"";
        }
    }
}
